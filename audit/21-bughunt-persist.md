# 21 · 持久化 / 异步落盘 / 插件生命周期 审计（只读）

- 范围：所有 `*Store` / `*Manager` / `*Service` 的 load / save / flush / start / stop / shutdown / reload 链路，以及 `ForgingMasterPlugin` 的 onEnable / onDisable / reloadAll 编排。
- 方法：读 `ForgingMasterPlugin` 全文，grep 全部 `runTaskTimer*` / `registerEvents` / `save|load|flush|start|stop|shutdown` 签名，逐个走通 start → stop → reload 三条链。
- 版本：v2.23.0 / Paper 1.21.11 / Java 21。
- **分批落盘**：本文件覆盖 **16 条**已逐行走通、可复现的结论（P0×3 / P1×6 / P2×7）。未完成项见文末「未完成 / 待核」。

---

## P0

### [P0] `/zhujian reload` 每点一次就永久多挂一个 3 秒心跳：`AnvilForgeManager.start()` 丢弃了 ticker 句柄
- 位置：`src/main/java/com/forgemaster/anvil/AnvilForgeManager.java:100`（起任务）、`:105-120`（stop 不取消）、`src/main/java/com/forgemaster/ForgingMasterPlugin.java:721-724`（reload 顺序）
- 机制：`start()` 里 `Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 60L, 60L);` **返回值没有赋给任何字段**，类里也没有保存心跳任务的成员（只有 `flushTaskId` 是给异步冲刷用的）。因此 `stop()` 只能停异步冲刷、落盘、despawn、清 `sessions`，**无法取消这个同步心跳**。`reloadAll()` 走的是 `anvilForge.stop(); anvilForge.start();`，于是旧心跳一直活着，新心跳再加一个。
- 触发：连续执行 `/zhujian reload` N 次（服主调 config 时的常规操作）。
- 影响：刷新率变成 (N+1)×每 3 秒；`tick()` 里做会话超时判定、区块丢失补全息、`despawn`/`sessions.remove` + `dirty = true`，多份心跳会**重复 despawn 同一砧位、把同一格反复标脏**，配合异步冲刷变成"每 3 秒写一次 anvils.yml"的持续 IO；服主看到的是「重载几次后铁砧强化台开始卡、全息闪烁」。属永久性重复触发（重启才归零）。
- 最小修复：
  ```java
  // 字段
  private BukkitTask tickTask;
  // start()
  if (tickTask != null) { tickTask.cancel(); }          // 先停旧的，重复 start 幂等
  tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 60L, 60L);
  // stop()
  if (tickTask != null) { tickTask.cancel(); tickTask = null; }
  ```

### [P0] `BackupManager.restore()` 回档后 tombs / contract / season / hall / story 会被内存旧态盖回去，回档等于白做
- 位置：`src/main/java/com/forgemaster/backup/BackupManager.java:129-167`（restore）、`:149-154`（覆盖文件）、`:155-165`（只重建 stats + auction）
- 机制：restore 把备份目录里的 10 个文件 `Files.copy(..., REPLACE_EXISTING)` 盖回存档目录，然后**只**对 `StatsStore` 调 `load()`、对 `AuctionStore` 调 `reloadStore()`。而 `tombs.yml` / `contract.yml` / `season.yml` / `hall-yl` / `story-progress.yml` 的内存缓存（`TombManager.tombs`、`ContractStore.runs`、`SeasonManager.number/startedAt`、`StoryManager.chapterIndex`）**一个都没重建**。这些模块恰好都挂着周期落盘：`TombManager.startAutoFlush()` 5 秒一轮、`ContractStore.startAutoFlush()` 30 秒一轮，只要 `dirty` 还亮着（回档前它们自己刚被标脏过）就会立刻把**旧内存**整份重写回刚恢复的文件。
- 触发：`/zhujian backup` 造快照 → 服主继续玩 → `/zhujian restore <id>`。restore 返回"成功"，但 5 秒内 `tombs.yml` 变回回档前的样子。
- 影响：数据损坏（回档看似成功、实际被静默回滚），且管理员会以为"回档没用/插件坏了"。埋剑、契约周目、赛季编号、剧情进度全部受影响。
- 最小修复：restore 末尾补对称的重建 + 落盘前先压住脏标记：
  ```java
  // 覆盖文件之前：先把所有模块的任务停掉，避免旧内存回写
  plugin.tombs().stop();                      // 内部 cancelTask + save
  plugin.contractManager().stop();
  // 覆盖文件之后：按磁盘重建内存
  plugin.tombs().load();     plugin.tombs().startAutoFlush();
  plugin.contractManager().store().load();
  plugin.story().load();     plugin.seasons().start();   // start() 内部会重读 season.yml
  plugin.guilds().load();    plugin.orders().load();
  ```
  更省事的做法：restore 成功后直接要求/执行插件重启，并把 `SAVE_FILES` 做成唯一真相清单。

### [P0] 备份清单只有 10 个文件，漏掉 8 份真实存档：回档后状态集自相矛盾
- 位置：`src/main/java/com/forgemaster/backup/BackupManager.java:28-32`
- 机制：`SAVE_FILES` = `data.yml / commissions.yml / manuals.yml / auction.yml / tombs.yml / season.yml / hall.yml / hall-strengthen.yml / story-progress.yml / contract.yml`。盘上实际还有 `guild-data.yml`（行会 + 仓库物品 + 委托）、`pass-data.yml`（通行证积分与领取）、`daily-data.yml`（黑市限购 + 签到）、`spectrum-data.yml`（名剑谱）、`orders.yml`（锻造订单 + 托管剑）、`anvils.yml`（砧位上的剑）、`codex-data.yml`（图鉴）、`hall-legacy.yml`（历代名剑）。
- 触发：任意一次 `restore`。
- 影响：回档后"剑币回到过去、但砧位上的剑/订单托管剑/行会仓库还是现在"，玩家能复现物品（老账 + 新物品）；`backup()` 声称的"备份"也不完整——真正的收益是服主以为有备份。
- 最小修复：清单补全 + 用"存档目录下所有 *.yml（除 config.yml）"现算，避免以后再加 store 又漏：
  ```java
  File[] all = dataDir.listFiles((d, n) -> n.endsWith(".yml") && !n.equals("config.yml"));
  ```

---

## P1

### [P1] 异步冲刷线程与主线程共用非并发 HashMap，`CME` 直接穿透 `save()` → guild-data.yml 停写
- 位置：`src/main/java/com/forgemaster/guild/GuildService.java:954`（`new LinkedHashMap<>(players)`）、`:937`（`new ArrayList<>(guilds.values())`）、`:302-313`（任务起在异步线程）、`:904-906`（主线程 `players.computeIfAbsent`）、`:107-108`（`LinkedHashMap` / `HashMap`）
- 机制：`flushTask` 是 `runTaskTimerAsynchronously`，回调里直接 `save()`。`save()` 虽然标了 `synchronized`，但**主线程的写入口没有持同一把锁**（`member()`、`guilds.put`、`create/join/leave` 都是裸操作）。而两处快照拷贝写在 `for` 的初始化表达式里、**位于 per-item `try/catch ConcurrentModificationException` 之外**，所以一旦拷贝时撞上主线程 `put`，CME 会从 `save()` 抛出到调度器。
- 触发：玩家入会/退会/开仓库的同一时刻正好遇到 5 秒一次的异步冲刷（在线人数越多越容易撞）。
- 影响：该轮落盘整体作废（`dirty` 保持 true，但异常会打断这一轮并刷 SEVERE 栈）；行会仓库物品与委托进度全部靠这条 5 秒链落盘，反复失败时崩溃即丢一整个仓库。
- 最小修复：把拷贝也纳入兜底，并给变更入口加同一把锁：
  ```java
  public synchronized void save() {
      Map<String, Guild> gs; Map<UUID, Member> ps;
      try { gs = new LinkedHashMap<>(guilds); ps = new LinkedHashMap<>(players); }
      catch (java.util.ConcurrentModificationException ex) { dirty = true; return; }
      for (Guild g : gs.values()) { ... }
      for (Map.Entry<UUID, Member> e : ps.entrySet()) { ... }
  }
  // 并让 member()/create()/join()/leave() 走 synchronized(this) 或把两个 map 换成 ConcurrentHashMap
  ```

### [P1] `StatsStore.save0()` 写完无条件清脏：写盘窗口里发生的改动被当成"已保存"
- 位置：`src/main/java/com/forgemaster/stats/StatsStore.java:838-856`
- 机制：`save0()` 先在锁内拍一份 `snapshot`（浅拷贝 map），然后逐个 `writePlayer(...)` 组装 `yml`，最后 `yml.save(file)`，成功就 `dirty = false`（只有 CME 才会保留脏）。**在"拍快照"和"清脏"之间主线程写入的新数据（锻造结算、加币、成就）不在这次 yml 里，但脏标记照样被清掉**。注释写的是"落盘期间又有新改动就保持脏"，实现只在 CME 分支成立。
- 触发：5 分钟一次的自动保存正好跨过一次锻造结算 / 一次拍卖成交（都是毫秒级窗口，但一天几百次总能撞上）。
- 影响：这一笔改动要等**下一次**别的写入把 `dirty` 重新点亮才会进盘；如果之后没人动，直到 `onDisable` 的 `stats.save()`（或崩服）才会补上。崩服即丢。
- 最小修复：用代际号判定，而不是靠猜：
  ```java
  private final java.util.concurrent.atomic.AtomicLong gen = new AtomicLong();
  // 所有 dirty = true 处同时 gen.incrementAndGet();
  long g = gen.get();
  ... 组装 + yml.save(file) ...
  if (gen.get() == g) dirty = false;   // 期间没人改过才清
  ```

### [P1] 回档期间 StatsStore 的 5 分钟自动保存还活着，`load()` 又不在 `saveLock` 内 → 旧内存盖回刚回档的 data.yml
- 位置：`src/main/java/com/forgemaster/stats/StatsStore.java:819-825`（save 加锁）、`:133-208`（`load()` 无锁）、`src/main/java/com/forgemaster/backup/BackupManager.java:138-160`
- 机制：restore 的顺序是 `stats.save()` → `Files.copy` 覆盖 `data.yml` → `stats.load()`。整段**没有任何一处停掉 `saveTaskId`**。异步自动保存是 `runTaskTimerAsynchronously`、5 分钟一轮，只要它在这几毫秒里醒来且 `dirty` 为真，就会把**旧内存**写回 `data.yml`，覆盖掉刚恢复的文件；`load()` 随后读到的是被污染的数据。另外 `load()` 自己 `cache.clear()` + 重建，与异步 `save0()` 遍历 `cache` 之间没有互斥（`synchronized (cache)` 只在快照拷贝处生效，`save0` 的写文件阶段仍持有旧快照，尚可；但 `load` 的 `cache.clear()` 会让正在组装的 `writePlayer` 读到 `null` 之外的空档）。
- 触发：低概率但一旦命中就是"回档后数据没变"。
- 影响：数据损坏（回档无效，且不可察觉）。
- 最小修复：restore 前后显式停/起自动保存，并把 `load()` 也纳入 `saveLock`：
  ```java
  // BackupManager.restore
  plugin.stats().stopAutoSave();  // 新增：cancelTask(saveTaskId); saveTaskId = -1;
  ...
  plugin.stats().load();
  plugin.stats().startAutoSave(20 * 300);
  // StatsStore
  public void load() { synchronized (saveLock) { load0(); } }
  ```

### [P1] 全库 21 份存档都是 `yml.save(file)` 直写目标文件：写一半崩服 = 整份档案毁掉
- 位置：代表点 `StatsStore.java:849`、`TombManager.java:260`、`AuctionStore.java:179`、`ContractStore.java:144`、`GuildService.java:972`、`DailyStore.java:138`、`SeasonPassService.java:300`、`SpectrumService.java:184`、`OrderService.java:143`、`StoryManager.java:92`、`AnvilForgeManager.java:1432`、`CodexService.java:120`
- 机制：`YamlConfiguration.save(File)` 直接 `FileOutputStream` 截断重写，没有 tmp + rename。`load()` 侧普遍是"坏数据跳过"（`StatsStore:203`、`GuildService:263`、`DailyStore:87`、`SpectrumService:164`、`SeasonPassService:212`）或"整段当空"（`AuctionStore:50-60` 是唯一做了 `.broken` 改名兜底的），所以半截 yaml 的后果不是崩溃，而是**静默少数据/整份清空**。
- 触发：写盘瞬间被 kill -9 / 断电 / OOM killer（`data.yml` 与 `guild-data.yml` 都有几 MB 量级的增长空间）。
- 影响：数据丢失，且丢得无声无息（日志只有一行 warning 或干脆没有）；全服档案一起报废。
- 最小修复：统一一个工具方法，所有 `save()` 换成它：
  ```java
  static void atomicSave(YamlConfiguration yml, File target) throws IOException {
      File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
      yml.save(tmp);
      try {
          java.nio.file.Files.move(tmp.toPath(), target.toPath(),
              java.nio.file.StandardCopyOption.REPLACE_EXISTING,
              java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
          java.nio.file.Files.move(tmp.toPath(), target.toPath(),
              java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
  }
  ```

### [P1] `TombManager.save()` 的注释声称"写盘与全部变更共用 this 锁"，实际所有变更路径都不持锁
- 位置：`src/main/java/com/forgemaster/tomb/TombManager.java:236-266`（`synchronized save()` 与 261 行的注释）、变更点 `:327`、`:415`、`:456`、`:545`、`:721`、`startAutoFlush()` `:198-204`
- 机制：`tombs` 是 `ConcurrentHashMap`（`:79`），所以迭代不会 CME —— 但 `Tomb` 实体上的 `challenges / wins / layers / broken / brokenAt` 是**普通 int / boolean**，主线程 `t.challenges++`（`:545`）没有任何 `synchronized` 或 volatile。异步冲刷线程在 `save()` 里读这些字段，与主线程的写之间**不存在 happens-before 边**（唯一持锁的 `stop()` 只在关服跑）。注释把"CHM 免 CME"误当成"数据一致"。
- 触发：玩家挑战残影（`challenges++`、`wins++`）时撞上 5 秒冲刷。
- 影响：落盘值是"某次读到的旧值"，`challenges/wins` 计数偏少（不可复现的账不平）；极端情况下 `broken` 的写入滞后一轮。属低概率但要命的静默偏差。
- 最小修复：要么把变更都收进锁，要么把 Tomb 的可变计数改成 `AtomicInteger`/`volatile`：
  ```java
  public static final class Tomb {
      public final java.util.concurrent.atomic.AtomicInteger challenges = new AtomicInteger();
      public volatile int wins, layers, brokenAt;
      public volatile boolean broken;
  }
  // save(): yml.set(base + "challenges", t.challenges.get());
  ```

### [P1] `/zhujian reload` 不重启 4 个调度器：`contest.interval-minutes` / `events` 排期 / `guild.week-check-hours` / `guild.quest.poll-seconds` 改了不生效
- 位置：`src/main/java/com/forgemaster/ForgingMasterPlugin.java:694-763`（reloadAll 全文，无 `contests.start()` / `events.start()` / `guilds.start()`）；对应读取点 `contests/ContestManager.java:66-69`、`events/EventManager.java:61-66`、`guild/GuildService.java:299-301`、`guild/GuildQuests.java:173-174`
- 机制：reloadAll 会 `reloadConfig()` + `settings.load()`，所以**每次用时读 `plugin.settings()` 的参数全部即时生效**；但四个"任务周期"参数是在 `start()` 里算好并交给 `runTaskTimer` 的，`reloadAll` 没再调这四个 `start()`（`start()` 本身都是幂等的：`stopTicker()/stopTasks()/stop()` 先跑），于是周期保持旧值直到重启。
- 触发：服主改 `contest.interval-minutes`、`events.schedule`、`guild.week-check-hours`、`guild.quest.poll-seconds` 后执行 `/zhujian reload`，看到"已重载"但行为不变。
- 影响：功能失效/配置漂移（表面成功），服主会重复改配置、重启反复试。
- 最小修复：在 reloadAll 末尾补 4 行（都幂等，安全）：
  ```java
  if (contests != null) { contests.start(); }
  if (events != null)   { events.start(); }
  if (guilds != null)   { guilds.start(); }   // 内部含 quests.start()
  ```

---

## P2

### [P2] `reloadAll()` 配置漂移普查结论：真正"构造时读一次"的只有 5 处，其余每次读 config
- 位置：`ForgingMasterPlugin.java:694-763`；被查模块逐个见下
- 机制 / 结论（服主最关心的一条，逐个走通）：
  | 模块 | reloadAll 里是否重载 | 配置读取时机 | 结论 |
  |---|---|---|---|
  | `commissions` | 否 | `this.settings = plugin.settings()` 同一实例，`commissionSlots()/commissionEnabled()` 每次调用现读 | **无漂移**（数据 `commissions.yml` 不在 reload 重读，符合预期） |
  | `codex` | 否 | `enabled()` 现读 `plugin.getConfig()`；条目奖励表非缓存 | **无漂移** |
  | `blackmarket` | 只 `dailyStore.save()`，不重载货单 | `pool()` 每次 `plugin.getConfig().getMapList("blackmarket.pool")` | **无漂移** |
  | `tombrun` | 否 | `waves()/ticketCoins()/timeoutSeconds()` 每次现读 | **无漂移**（仅 `startRun` 期间的参数变更按新值走） |
  | `tombs` | 否 | `enabled()/baseChance()/challengeFee()` 现读 | **无漂移**（`tombs.yml` 数据不重读，符合预期） |
  | `guilds` | 否 | 参数现读 `settings`，但**任务周期**在 `start()` 定 | **有漂移（见上一条 P1）** |
  | `metrics` | 否 | `MetricsReporter` 无状态，`text()` 每次现读 | **无漂移** |
  | `contests` / `events` | 否 | 周期/排期在 `start()` 定 | **有漂移（见上一条 P1）** |
- 影响：只影响上表标"有漂移"的 4 处；其余模块 reload 后参数即时生效，不必改。
- 最小修复：见上一条。

### [P2] `AuctionManager.shutdown()` 在最后一次 `stats.save()` 之后再 `markDirty()`：脏标记永远不被消费
- 位置：`src/main/java/com/forgemaster/auction/AuctionManager.java:81-83`、`ForgingMasterPlugin.java:598-600`（stats.save）vs `:613-615`（auction.shutdown）
- 机制：`onDisable` 先 `stats.save()`，再 `auction.shutdown()`，后者注释写"data.yml 的全量同步保存已由 onDisable 里 stats.save() 统一做（在本方法之前执行），这里只补脏标记"——但补脏之后再没有任何一次 `save()`，函数返回后调度器就停机了。这个 `markDirty()` 是纯空转，并且**注释与实现相反**：它声称"统计已由前面的 save 覆盖"，代码却在 save 之后点脏。
- 触发：每次关服。
- 影响：当前无害（钱在 save 之前就已进盘），但一旦有人把 `stats.save()` 挪到 `auction.shutdown()` 之后（很自然的"统一收尾"重构），最后一笔拍卖结算就永久丢失。属埋雷。
- 最小修复：删掉 `plugin.stats().markDirty();` 并改正注释；或把它挪到 `onDisable` 的 `stats.save()` 之前。

### [P2] `SeasonPassService.save()` 先清脏再写盘：一次 IOException 就永久丢积分
- 位置：`src/main/java/com/forgemaster/pass/SeasonPassService.java:283-304`（`dirty = false;` 在 `:284`，`yml.save(file)` 在 `:300`）
- 机制：`save()` 第一行就 `dirty = false`，然后才组装 + 写文件；`catch (IOException)` 只 warning，不回滚脏标记。`flush()` 是"`dirty` 为真才 save"，于是写失败后这 10 秒攒下的积分再也不会被冲刷。
- 触发：磁盘满 / 备份占用 / 权限抖动时的写失败。
- 影响：积分丢失（通行证进度、领取状态）。
- 最小修复：把清脏挪进 try 的成功分支：
  ```java
  try { ... yml.save(file); dirty = false; }
  catch (IOException ex) { plugin.getLogger().warning(...); }   // dirty 保持 true，下一轮重试
  ```

### [P2] `DailyStore.save()` 用 JVM 默认时区的 `LocalDate.now()`，且带剪枝副作用
- 位置：`src/main/java/com/forgemaster/blackmarket/DailyStore.java:115-142`（`:116` 取日期、`:120` `removeIf` 剪层）、`today()` `:182-184`
- 机制：日期键来自 `LocalDate.now()`（JVM 默认时区）。容器里 `TZ=UTC` 而服主按本地日理解时，"每日"在本地 08:00 才翻；同一份逻辑还出现在 `BlackmarketService`（`:181` 用 `LocalDate.now()` 记购买）与 `DailyRewardService`（`:154`），三处各自取一次时间。另外 `save()` 不是纯写入——它会 `byDate.keySet().removeIf(d -> !today.equals(d))` **就地删掉非今日层**，即"落盘"顺带改内存。
- 触发：服务器 JVM 时区与玩家/服主时区不一致；或 23:59:59 购买、00:00:00 落盘。
- 影响：黑市限购在本地"一天"内被清零一次（可重复买）；签到连击按 UTC 日断裂/续上。属边界但可被玩家利用。
- 最小修复：日期口径统一收口，并在启动时打印实际口径：
  ```java
  // 一处定义，三处共用
  public static String today(ZoneId zone) { return LocalDate.now(zone).toString(); }
  // 配置 daily.timezone: Asia/Shanghai（默认 ZoneId.systemDefault()）
  ```
  并把剪枝从 `save()` 里挪出来，放到一个独立的 `prune(String today)`，由主线程在跨日时调用。

### [P2] `HallShrine.origin` 缓存永不复位：reload 后改殿堂坐标不生效
- 位置：`src/main/java/com/forgemaster/hall/HallShrine.java:110-115`（`if (origin == null) origin = readOrigin();`）、`ForgingMasterPlugin.java:729-732`（reload 只 `stop(); start();`）
- 机制：`origin` 是内存字段，只在为 null 时读 `hall.yml`；`stop()` / `start()` 都不清它。
- 触发：`/zhujian halloffame set`（写 `hall.yml`）或手改 `hall.yml` 后 `/zhujian reload`。
- 影响：展示实体仍摆在旧坐标，服主以为没生效；需重启服务器。
- 最小修复：`stop()` 里加一行 `origin = null;`（`start()` 会重新读）。

### [P2] 行会"世界周"用 `getFullTime()/168000` 而非现实时间：周结算会永不触发或重复触发
- 位置：`src/main/java/com/forgemaster/guild/GuildService.java:732-737`（`worldWeek()`）、`:739-746`（`weeklyCheck()`）、`:749-764`（`settleWeek()`）
- 机制：周编号 = `Bukkit.getWorlds().get(0).getFullTime() / 168_000L`。`getFullTime()` 只在世界**tick 推进**时增长，且能被 `/time add` 直接跳变；`getWorlds().get(0)` 取决于 bukkit.yml 的 worlds 顺序，换主世界后同一份 `last-week` 会与另一条时间线比较。
- 触发：`doDaylightCycle: false`（挂机服常见）→ 周编号不动 → 周赛永不结算；管理员 `/time add 168000` 或换主世界 → 编号跳变 → `week > lastWeek` 触发一次，`g.weekPoints.clear()` 把当前周的积分清空（正常应结算上周）。
- 影响：行会周赛奖励漏发或被清空；`settleWeek` 里 `save()` 之后才广播，若中途异常则积分已清但没人领奖。
- 最小修复：改现实时间周（与赛季一致的口径），并保留"只允许 +1"的防护：
  ```java
  private long worldWeek() {
      return java.time.LocalDate.now(java.time.ZoneId.of(zone()))
              .getLong(java.time.temporal.WeekFields.ISO.weekBasedYear()) ... // 或 epochDay / 7
  }
  // weeklyCheck(): if (week <= lastWeek) return; if (week - lastWeek > 1) { lastWeek = week - 1; }  // 跳周只结算一次
  ```

### [P2] `SpiritManager` 的待落账灵性只靠 `PluginDisableEvent` 冲刷，`/zhujian reload` 不冲刷
- 位置：`src/main/java/com/forgemaster/spirit/SpiritManager.java:423-429`（`flushAll()`）、`:440-445`（`onPluginDisable` 里调 `flushAll()`）、`ForgingMasterPlugin.java:694-763`（reloadAll 无 `spirit` 相关调用）
- 机制：灵性写入是 2 秒节流（`FLUSH_MILLIS`），挂账存在 `pending` map；唯一的全局兜底是 `PluginDisableEvent`（只在插件被停用时触发）。`/zhujian reload` 走 `reloadAll()`，既不触发该事件也不调 `flushAll()`。
- 触发：玩家刚喂完剑灵（≤2 秒）时服主执行 `/zhujian reload`，或该玩家立刻被踢下线以外的异常路径。
- 影响：≤2 秒的灵性丢失（小，但不可找回）；玩家看到的"灵性差几点升档"会被当成 bug。
- 最小修复：`reloadAll()` 开头加 `if (spirit != null) { spirit.flushAll(); }`（并放在 `reloadConfig()` 之前，保证按旧配置写完）。

---

## 未完成 / 待核（本轮未走完，未计入上面 16 条）

1. `GuildGuiRouter` / `OrderGuiRouter` / `TitleGuiRouter` / `SpectrumGuiRouter` / `PassGuiRouter` / `BlackmarketRouter` / `ContestGuiRouter` 的 static `registered` 标志：`onDisable` 逐个 `reset()`，但 `gui/ReforgeMenu.java:623-629` 的 `listenerReady` 标志**没有对应 reset**——需确认 PlugMan 式热卸载后再启用时，重铸界面点击路由是否永久失效（若只在全服重启场景则无影响）。
2. `ContestWatchGui` 的静态 `OPEN` 面板表与 `Panel.task`：`shutdown()` 是否遍历 `OPEN` 逐个 `stopTicker`（`:164` 起未读完），有无 reload 后残留面板。
3. `SpiritDisplayService.stop()` 之后没有 `start()` 的对称入口（`startPatrol` 是 private，仅构造里调）：需确认是否存在"stop 后巡检永久停摆"的路径。
4. `RelicSetService.startCountdown()`（`:695-713`）的 `BukkitRunnable` 句柄未保存，同一玩家重复触发会不会叠加 actionbar 刷新（冷却判定应挡住，需核 `oneshot` 重复入队）。
5. `EventManager` / `ContestManager` 在 reload 后 `activeId` / `running` 状态是否会与新配置打架（`start()` 重置 `nextAt`，对正在进行的活动影响未核）。
6. `settings()` 之外的 `plugin.getConfig()` 直读点（`StoryManager:103`、`HallOfFame:78`、`SeasonManager:69`、`CodexService:358`）在 `reloadConfig()` 之后是否需要缓存失效，仅抽查未穷举。