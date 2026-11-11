# ForgingMaster-Lore 性能审计报告（只读）

- 对象：`D:\IDEA\mc-plugins\ForgingMaster-Lore`（Paper 1.21.11 / Java 21 / Maven，v2.17.3）
- 范围：`src/main/java` 全部 131 个文件、40461 行；未修改任何源码/配置/构建文件
- 影响量化约定：N = 在线人数；命中/点击频率按普通生存服经验估计（战斗命中 ~1 次/秒/战斗玩家，背包点击 ~0.5 次/秒/活跃玩家）
- 说明：全部行号均为审计时真实行号，代码摘录为原文截取

---

## 一、已核实线索逐条结论

| 线索 | 结论 |
|---|---|
| 1) forge/ForgeManager 每 tick 轮询 | 属实：`ForgeManager.java:90` `runTaskTimer(plugin, this::tick, 1L, 1L)`。tick 内 `sessions.isEmpty()` 时直接 return（`:538-540`），空转成本≈一次 map 判断，可忽略；但会话存在时每 tick 做 BossBar 更新（每 tick 一次 setTitle/setProgress 网络包，`:617-624`）与每 2/3 tick 全量重绘 GUI（见 #4）。可改为「有会话才启动、会话清零即取消」的事件驱动，收益小（P3）。 |
| 2) ~190 处 getItemMeta / ~195 处 Msg.mm | 实测 `getItemMeta()` 193 处、`Msg.mm(` 195 处。真正的高频热点集中在三条路径：① 每次挥砍命中的耐久 Lore 全量重写（#1）；② 剑灵养成的每命中写 meta（#2）；③ 锻造小游戏 GUI 每 2~3 tick 全量重建图标（#4）。其余多为命令/GUI 打开时的一次性调用，不构成热点。 |
| 3) 12 个常驻 runTaskTimer | 实测常驻重复任务 12 个 + 2 个按需任务（落锤演出 `AnvilForgeManager.java:927`、观战面板 `ContestWatchGui.java:247`）。逐个评估见第四节表格：多数已有空转早退，可拉长间隔的是 EventManager / ContestManager / ContractManager（1s→3~5s）；TombChallenge 的 ticker 启动后永不取消（#11）。 |
| 4) AnvilForgeManager.tick 每 3 秒全砧位扫描 | 属实：`AnvilForgeManager.java:79` `runTaskTimer(plugin, this::tick, 60L, 60L)`。每个砧位每 3 秒做 1 次 `getBlockAt`（区块读）+ 可能有 `Bukkit.getEntity` 解析（`:1147-1175`）。会话数线性扩展，100 个砧位 ≈ 每 3 秒 100 次方块读，尚可接受（P3）。真正的问题在落盘策略：dirty 标记只在 `stop()` 时 save（#10）。 |
| 5) 持久化主线程同步 YAML IO | 部分属实：`StatsStore` 自动保存已是 `runTaskTimerAsynchronously`（`StatsStore.java:673`），做得对；但 `AuctionStore.save` / `ContractStore.save` / `TombManager.save` 全部是主线程同步整文件重写，且 AuctionManager 每次竞价还**在主线程同步调 `plugin.stats().save()`** 整档重写 data.yml（#3、#7、#8）。`AnvilForgeManager` 有 dirty 标记却从不周期落盘，只在关服 save（#10，属数据安全而非性能）。 |
| 6) 粒子与 Display 成本 | 落锤演出每 tick 撒 FLAME/SMALL_FLAME 拖尾 + 砸中 18 粒火焰/岩浆/烟（`AnvilForgeManager.java:889-974`），但演出总共只有 `duration-ticks`（默认 12）tick，且实体 `setPersistent(false)`，成本有界（P3）。`ForgeStage` 同理为会话期短生命周期。`StoryManager` 与 `env/EnvPanel` 核实为纯文本/低频面板，无粒子热点。真正的持续粒子在 ForgeManager 加热/开刃阶段（每 3~4 tick 一次、受 `settings.particles()` 开关控制，`ForgeManager.java:635-637, 863-866`），量级可接受。 |

---

## 二、问题清单

### #1 每次挥砍命中触发耐久 Lore 全量重写（P0）

**位置**：`listener/CombatListener.java:62-63` → `item/ItemFactory.java:452-487`

```java
// CombatListener.java:62-63 —— 每次命中都调用
if (plugin.settings().durabilityEnabled() && durability > 0) {
    boolean broken = plugin.items().setDurability(weapon, durability - plugin.settings().durabilityLossPerHit());
```
```java
// ItemFactory.java:466-484 —— 每次都：getItemMeta + 全 Lore 两轮遍历 Msg.plain 序列化 + setItemMeta
List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
...
for (int i = 0; i < lore.size(); i++) {
    if (Msg.plain(lore.get(i)).startsWith("耐久")) { ... }      // 第 1 轮：整条 Lore Component→String
}
...
lore.removeIf(c -> Msg.plain(c).startsWith("崩刃"));            // 第 2 轮：再整条序列化一遍
...
meta.lore(lore);
com.forgemaster.util.Attrs.apply(item, meta);                   // setItemMeta + 属性组件重建（Attrs.java:204-210）
```

**影响**：成品 Lore 约 18~22 行。每次命中 ≈ 2×20 次 PlainText 序列化 + 1 次 setItemMeta（NMS 组件复制）+ 1 次 normalize 属性组件重建。50 人战斗服按 50 命中/秒算 ≈ 每秒 2000+ 次文本组件序列化与 50 次完整 meta 写回，是本库最高频的重活；`getItemMeta()` 193 处里最热的一条。
**建议**：`CombatListener.onHit` 已持有 meta，把耐久更新改为「PDC 先记新值，Lore 行只在跨档（每 10% 或崩刃/临界提示）时重写」；`setDurability` 增加接受外部 meta 的重载避免二次 `getItemMeta`；两轮 `Msg.plain` 遍历合并为一轮。改 `ItemFactory.setDurability` 与 `CombatListener.java:63` 调用点。
**工作量**：M

### #2 剑灵养成：每次命中 3~4 次 meta 读取 + 全 Lore 扫描写回（P1）

**位置**：`spirit/SpiritManager.java:252-264, 126-170, 243-246`

```java
// SpiritManager.java:260-264 —— 每命中一次
int tier = tierOf(spiritOf(weapon));        // spiritOf: isForged(1 次 getItemMeta) + 再 getItemMeta（:100-110）
if (addSpirit(player, weapon, gain)) {      // addSpirit: isForged + 又一次 getItemMeta（:127-130）
    player.getInventory().setItemInMainHand(weapon);   // 整槽写回
```
```java
// SpiritManager.java:243-246 —— 每次加灵性都整条 Lore removeIf 序列化
lore.removeIf(c -> Msg.plain(c).startsWith(plainPrefix));
lore.add(Msg.mm(mini).decoration(TextDecoration.ITALIC, false));
```

**影响**：灵性未满级的成品剑，每次命中 ≈ 4 次 `getItemMeta` + 1 轮全 Lore 序列化 + `Attrs.apply` + 背包槽写回；与 #1 叠加后同一击最多 7~8 次 meta 操作。灵性上限高时该状态持续整把剑的前半生命周期。
**建议**：`onHit` 里把 `spiritOf` 与 `addSpirit` 合并成单次 meta 读（传入已读的 meta/PDC 值）；加灵性按时间节流（如每 2 秒结算一次累计值，中间记在局部 map，PDC/Lore 只在落账时写一次）。改 `SpiritManager.java:260-264` 与 `addSpirit` 签名。
**工作量**：M

### #3 拍卖行动作在主线程同步整档落盘 auction.yml + data.yml（P1）

**位置**：`auction/AuctionManager.java:191-195`（竞价）、`:152`（挂单）、`:243`（一口价）、`:293`（到期结算）、`:77`（shutdown）；`auction/AuctionStore.java:106-136`；`stats/StatsStore.java:680-710`

```java
// AuctionManager.java:191-195 —— 每次竞价
store.put(l);                       // put() 内部直接 save()（AuctionStore.java:145-148）
if (prev != null) { plugin.stats().addCoins(prev, prevPrice); }
plugin.stats().save();              // 主线程同步：整份 data.yml 全量重写（StatsStore.java:680-710 非 async 版本）
```
```java
// AuctionStore.java:131-135 —— synchronized save：同步 IO + 全部挂单 ItemStack 序列化
yml.save(file);
```

**影响**：`StatsStore.save()` 是全玩家档案的 YAML 全量重写（`new LinkedHashMap<>(cache)` 后逐玩家 30+ 键 set）。data.yml 随注册人数无上限增长，千人档 ≈ 数 MB；竞价高峰每秒数次 = 主线程反复出现 10ms+ 级 IO 卡顿。注：自动保存线程本身是异步的（`StatsStore.java:673`），问题只在拍卖行这些**同步直调**点。
**建议**：拍卖/竞价路径删掉 `plugin.stats().save()` 直调，只 `markDirty()` 交给已有的异步定时保存（`StatsStore.startAutoSave` 已存在）；`AuctionStore.save` 改为 dirty 标记 + 低频异步落盘（竞态由现有先落盘后退款的顺序保证兜住，异步窗口内崩溃最多漏退，与现状注释一致）。改 `AuctionManager.java:152,195,243,293` 与 `AuctionStore.put/remove/finish/addMail/takeMail`。
**工作量**：M

### #4 锻造小游戏 GUI 高频全量重绘 + BossBar 每 tick 更新（P2）

**位置**：`gui/ForgeMenu.java:460-493`（renderGrindTrack 每 2 tick ×9 格）、`:339-360`（renderTrack）、`:284-303`（renderTemperature 每 3 tick）、`:703-715`（icon：new ItemStack+getItemMeta+Msg.mm+setItemMeta）；`forge/ForgeManager.java:617-624`（BossBar 每 tick）、`:852-858`、`:626-641`

```java
// ForgeManager.java:852-858 —— 开刃阶段每 2 tick
if (s.tickCounter() % 2 == 0) {
    Inventory open = topOf(p);
    if (open != null) {
        menu.renderGrindTrack(s, open);     // 9 格全部重建 icon
        menu.renderGrindInfo(s, open);
    }
}
```
```java
// ForgeMenu.java:491 —— 每格都是完整 icon 构建（含 Msg.mm 解析 + setItemMeta）
inv.setItem(TRACK_START + i, icon(mat, name, List.of()));
```
```java
// ForgeManager.java:622-623 —— BossBar 每 tick setProgress + setTitle（新字符串→Component→网络包）
bar.setProgress(temp / 120.0);
...
bar.setTitle("炉温 " + (int) temp + "°  |  最佳 " + (int) m.idealTemp() + "° ±" + (int) m.tolerance());
```

**影响**：单个锻造中的玩家 ≈ 开刃期每秒 10 次 ×10 格 `setItemMeta`（每次 NMS 组件复制）+ 10~20 次 `Msg.mm` 解析；BossBar 每 tick 一个 title 包（20 包/秒/人，title 每次都是新 Component）。20 人同时开锻 ≈ 每秒 2000 次 setItemMeta + 4000 次 MiniMessage 解析 + 400 个 BossBar 包。只在锻造会话期间发生，但这是本玩法 100% 的在线时长。
**建议**：轨道格做差量更新（只 set 指针离开/进入的 2~3 格）；`icon()` 对固定文案缓存已解析的 Component（name/lore 常量池化）；BossBar `setTitle` 降到每 20 tick 一次（温度取整本身变化慢），`setProgress` 保留。改 `ForgeMenu.renderGrindTrack/renderTrack/renderTemperature` 与 `ForgeManager.java:622`。
**工作量**：M

### #5 每次背包点击都排一个护甲扫描任务（P2）

**位置**：`listener/ArmorRebalanceListener.java:104-109, 115-141`；`strengthen/StrengthenService.java:1507-1556`

```java
// ArmorRebalanceListener.java:104-109 —— 任何 InventoryClickEvent（含箱子/工作台/聊天迁移物品）
public void onClick(InventoryClickEvent event) {
    if (event.getWhoClicked() instanceof Player player) {
        scheduleScan(player);      // 每次点击 new 一个主线程任务
    }
}
```
```java
// ArmorRebalanceListener.java:129-141 —— 任务里 4 槽位：getItem + clone + isForged(meta) + rebalanceArmor(再 meta)
ItemStack copy = item.clone();
if (plugin.strengthen().rebalanceArmor(copy)) { ... }
```

**影响**：活跃玩家 ≈ 0.5 点击/秒 → 每人每秒 1 个任务对象 + 4 次 `getItem` + 4 次 `clone()` + 4~8 次 `getItemMeta`（`isForged` 一次、`rebalanceArmor` 内一次）。100 人在线 ≈ 每秒 100 个调度任务 + 800 次 meta 克隆。已校准物品最终在 marker 处早退（`StrengthenService.java:1515-1519`），但前置的 clone 与 meta 读已经付出。
**建议**：`scheduleScan` 加「每玩家 pending 去重」（一个 `Set<UUID>`，扫描完成后移除，天然合并连续点击）；`scan` 先只读 marker（一次 meta 读）判定再决定 clone；或把 `onClick` 收窄为 `event.getSlot() >= 36 && event.getSlot() <= 39 || event.isShiftClick()`。改 `ArmorRebalanceListener.java:104-109`。
**工作量**：S

### #6 玩家受击时两个监听器各自重复读护甲 meta（P2）

**位置**：`listener/ForgeListener.java:361-387`（EntityDamageEvent HIGH）、`affix/AffixSetListener.java:151-196`（EntityDamageEvent NORMAL）

```java
// ForgeListener.java:366-377 —— 每 4 件护甲各 getItemMeta
for (ItemStack item : player.getInventory().getArmorContents()) {
    ...
    var meta = item.getItemMeta();
    Double v = meta.getPersistentDataContainer().get(Keys.DAMAGE_REDUCE, ...);
```
```java
// AffixSetListener.java:171-183 —— 同一事件里又是 主手+副手+4 件甲 各走 matchedSets → have() → Reforge.affixIdsOf → getItemMeta
collectReduce(player.getInventory().getItemInMainHand(), best);
collectReduce(player.getInventory().getItemInOffHand(), best);
for (ItemStack piece : player.getInventory().getArmorContents()) { collectReduce(piece, best); }
```

**影响**：玩家每次受到任意伤害（摔落/火/怪）≈ 4 + 6 = 10 次 `getItemMeta`。受击频率高时（怪围攻/岩浆）每玩家每秒可达 5~10 次伤害事件。两监听器读的是同一批物品的同一类数据。
**建议**：合并为一个监听器一次遍历 6 件装备，同时取 `DAMAGE_REDUCE`（词条）与套装减伤；或按「一件装备的词条 id 列表」一次 PDC 读（`AFFIX_LIST` 已含全部信息，`DAMAGE_REDUCE` 也可并读）。改 `ForgeListener.onDamaged` 与 `AffixSetListener.damageReduceOf`。
**工作量**：M

### #7 契约系统「改动即存」同步落盘，settleDay 循环内多次写（P2）

**位置**：`contract/ContractStore.java:80-100`（save 同步全文件）；`contract/ContractManager.java:201, 313, 378, 383, 409, 453, 473, 527`（9 个调用点）

```java
// ContractManager.java:378-383 —— 午夜结算循环里，每个破产玩家各落盘一次
String text = breakContractText(id, shortfall);   // 内部 store.save()（:409）
...
} else {
    store.save();                                 // 又一次
```
```java
// ContractStore.java:95-98
yml.save(file);   // 主线程同步 IO
```

**影响**：contract.yml 小（每玩家 8 键），单次 IO 约 1~3ms；但入贡（deposit）是玩家高频动作，settleDay 在 N 个在契玩家破产时循环写 N 次。属低危但纯浪费的主线程 IO。
**建议**：`ContractStore` 加 dirty 标记 + 30 秒异步冲刷（照抄 `StatsStore.startAutoSave` 模式）；`settleDay` 循环外统一 save 一次。改 `ContractManager.java:378,383` 与 `ContractStore.save` 调用点。
**工作量**：S

### #8 剑冢每次埋/取同步全量重写 tombs.yml（含 Base64 物品快照）（P2）

**位置**：`tomb/TombManager.java:174-202`（save），调用点 `:265, 352, 393, 471, 630`

```java
// TombManager.java:195 —— 每座冢整份 ItemStack 快照 Base64 序列化
yml.set(base + "item", Base64.getEncoder().encodeToString(t.snapshot.serializeAsBytes()));
```

**影响**：tombs.yml 随埋剑数量无上限增长（每条含完整物品 NBT），每次埋剑/取剑/残影结算都全量重写并同步 IO。500 座剑冢时单次 save 预估 10~30ms 主线程卡顿。
**建议**：dirty 标记 + 异步低频落盘；或增量写（每冢独立 key 已具备条件，换 JSON Lines/每冢一文件可做 O(1) 追加）。最低成本做法与 #7 相同模式。改 `TombManager.save` 调用点。
**工作量**：S

### #9 ForgeManager 1-tick 常驻任务的空转与每 tick 拷贝（P3）

**位置**：`forge/ForgeManager.java:90, 537-559`

```java
ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
...
private void tick() {
    if (sessions.isEmpty()) { return; }                       // 无会话时空转，成本可忽略
    ...
    for (ForgeSession s : new ArrayList<>(sessions.values())) { // 有会话时每 tick 一次列表拷贝
```

**影响**：无会话时 ≈ 20 次/秒的一次 map.isEmpty 判断（可忽略）；有会话时每 tick 一个 `ArrayList` 包装（20 alloc/秒，微不足道）。真正要治的是 #4 里的每 tick BossBar/GUI 更新，而非这个调度本身。
**建议**：可选：`begin()` 时 `start()`、`cancel/discard/closeAfterResult` 后若无会话则 `stop()`，做成事件驱动；顺手把 `new ArrayList<>(sessions.values())` 换成迭代中收集待删列表。收益小，属卫生级。
**工作量**：S

### #10 AnvilForgeManager：3 秒扫描本身可接受，但 dirty 只在关服落盘（P3，含数据安全备注）

**位置**：`anvil/AnvilForgeManager.java:79, 82-88, 1147-1175, 1315-1354`

```java
// AnvilForgeManager.java:79 —— 每 3 秒
Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 60L, 60L);
// AnvilForgeManager.java:82-87 —— save 只发生在 stop()
public void stop() {
    ...
    save();
```

**影响**：tick 每 3 秒每砧位 1 次 `getBlockAt` + 1 次 `Bukkit.getEntity`（`:1168-1172`），线性扩展，100 砧位 ≈ 每 3 秒 200 次查询，可接受。性能角度无 P0 问题。**备注**：`dirty` 标记从不周期冲刷，服务器崩溃时砧上的剑（含玩家高强化值资产）全部回档到上次关服——这是可用性问题不是性能问题，顺带指出：建议每 60 秒 dirty 时异步 save 一次（照 StatsStore 模式）。
**建议**：见备注；扫描间隔无需调整。若要省方块读，可在 `releaseBlock`（方块拆除事件已接，`:1206-1211`）之外再挂 `BlockExplodeEvent` 兜底后把超时检测间隔拉长到 10 秒。
**工作量**：S

### #11 12 个常驻定时任务逐个评估（P3 汇总）

| 任务 | 位置 | 间隔 | 空转早退 | 评估与建议 |
|---|---|---|---|---|
| ForgeManager ticker | `forge/ForgeManager.java:90` | 1 tick | 有（`:538`） | 可事件驱动启停，收益小（#9） |
| ContractManager tickClock | `contract/ContractManager.java:53` | 1s | 有（`:163-166`），且 `contract.enabled=false` 时根本不调度（`:50-52`） | 逻辑依赖 `tod == windowTime()` 精确命中，1s 是合理粒度；可将窗口判定改为区间比较后拉长到 2~3s。S |
| AuctionManager settleExpired | `auction/AuctionManager.java:68` | 5s | 无（直接遍历挂单） | 到期粒度是小时级，100L→1200L（1 分钟）完全够，遍历与 `expired()` 时间比较成本同步降 12 倍。S |
| AnvilForgeManager tick | `anvil/AnvilForgeManager.java:79` | 3s | 有（`:1148`） | 保持；落盘策略见 #10 |
| ContestManager tick | `contests/ContestManager.java:68` | 1s | 无 | 未开赛分支只是时间比较（`:100-108`），可未开赛时 5s、开赛时 1s（BossBar 平滑）。S |
| EventManager tick | `events/EventManager.java:60` | 1s | 无 | 只做计数器自增（`:133-158`），1s→5s 后 `spawnIntervalSeconds` 粒度需同步放大。S |
| HallOfFame refresh | `hall/HallOfFame.java:79` | 300s | — | 合理，保持 |
| SeasonManager tick | `season/SeasonManager.java:74` | 60s | 有（`:105-107`） | 合理，保持 |
| StatsStore 异步保存 | `stats/StatsStore.java:673` | 可配 | 有 dirty 判断（`:674`） | 架构正确；全量重写成本见 #14 |
| StoryManager check | `story/StoryManager.java:104` | 10s（可配） | 无（遍历在线玩家，只读 Stats 字段） | 成本极低，保持 |
| TombChallenge ticker | `tomb/TombChallenge.java:284` | 1s | 有（`:298`） | **缺陷**：第一次挑战 `start()` 后永不取消，`active` 清空后仍每秒空跑并 `new ArrayList` 快照（`:302`）。`finish()` 里 `active.isEmpty()` 时 cancel。S |
| ForgingMasterPlugin 清理 | `ForgingMasterPlugin.java:204-208` | 300s | — | `purgeExpired` 只是 removeIf（`CommissionManager.java:411-415`），合理 |

另有两个按需任务：`AnvilForgeManager.java:927`（落锤演出，12 tick 自毁）与 `ContestWatchGui.java:247`（每观众 1 个、面板关闭即 cancel，`:257-278` 有完备的停止条件）——均无泄漏风险。

### #12 Msg.mm 无缓存，高频路径重复解析（P2/P3）

**位置**：`util/Msg.java:22-24`；高频调用点 `forge/ForgeManager.java:634`（每 3 tick action bar）、`:728, 860`、`gui/ForgeMenu.java` 全部 icon 文案、`anvil/AnvilForgeManager.java:290`（每锤 action bar）、`listener/CombatListener.java:57-74`（耐久警告 action bar）

```java
public static Component mm(String raw) {
    return raw == null ? Component.empty() : MM.deserialize(raw);   // 每次全量解析
```

**影响**：MiniMessage.deserialize 对短串约 1~5µs，但 #4 的 icon 重绘把调用量放大到每秒数千次，加起来是毫秒级/秒的纯 CPU。动作条字符串大半是常量前缀 + 一个数字。
**建议**：给 `Msg` 加一层小型 `ConcurrentHashMap<String, Component>` 缓存（只缓存常量串，带数字的用 `Component.text()` 拼接替代字符串模板）；优先处理 ForgeMenu icon 与 action bar 路径。改 `Msg.mm` 与 #4 列出的调用点。
**工作量**：S

### #13 findAnvilTop 开局方块扫描（P3）

**位置**：`forge/ForgeManager.java:499-527`；默认半径 `config/Settings.java:26`（anvilSearchRadius = 4）

```java
for (int dx = -radius; dx <= radius; dx++) {
    for (int dy = -2; dy <= 2; dy++) {
        for (int dz = -radius; dz <= radius; dz++) {
            org.bukkit.block.Block block = world.getBlockAt(bx + dx, by + dy, bz + dz);
```

**影响**：9×5×9 = 405 次 `getBlockAt`，每次开锻一次。单人开锻 405 次方块读 ≈ 0.1~0.5ms，多人同时开锻（大赛开始瞬间）会叠加成一次可感知尖峰。半径拉到 8 时变 2025 次。
**建议**：大赛/多人场景下可把扫描延后到 `runTaskLater` 分帧；或先扫 `dy` 外圈常见高度。低优先级。改 `spawnStage` 调用点（`:468`）。
**工作量**：S

### #14 StatsStore 全量重写 data.yml 与排行榜全排序（P3）

**位置**：`stats/StatsStore.java:680-710`（save：`snapshot` 全玩家浅拷贝 + 逐玩家 30+ 键）、`:637-645`（snapshot 同步块）、`:647-667`（top/topByLevel 每次全量排序）

```java
synchronized (cache) {
    snapshot = new LinkedHashMap<>(cache);      // 全玩家拷贝
}
...
yml.save(file);                                  // 全文件重写（异步任务里，OK）
```

**影响**：自动保存在异步线程（`:673`），主线程无卡顿；但文件越大 IO/序列化越久，脏数据窗口越长；排行榜 `top()` 每次调用全量排序，调用方为排名 GUI 与 HallOfFame（300s 一次），频率低。
**建议**：中期可按季度/年份分文件或只写本轮变脏的玩家（需要 per-player dirty）；当前量级（<5 万档案）下保持现状可接受。S/M

### #15 观战/拍卖面板每次刷新全量重建图标（P3）

**位置**：`contests/ContestWatchGui.java:244-278`（每观众每 1s render，见 `render` 逐格 icon）、`auction/AuctionManager.java:317-364`（每次打开 browse 全量 clone 挂单物品 + 追加 8 行 lore）

```java
// ContestWatchGui.java:246-254 —— 每 20 tick 每观众重绘整面板
int ticks = Math.max(1, plugin.settings().intOf("contest.watch.refresh-ticks", 20));
panel.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> { ... render(plugin, panel); }, ticks, ticks);
```

**影响**：受观众数与挂单数约束（≤45 件 clone+lore），单次打开成本小；观战面板在大赛期间每观众每秒一次全量重绘，10 观众 = 每秒 10 次面板重建（每面板 ~54 icon）。
**建议**：`contest.watch.refresh-ticks` 默认 20 可保持，但 render 内可只更新变化格；拍卖 browse 是打开时一次性，保持。改 `ContestWatchGui.render`。
**工作量**：S

### #16 无 PlayerMoveEvent 监听（核实为正面结论）

全库 `@EventHandler` 共 41 处，**没有任何 PlayerMoveEvent 处理器**（已用 grep 全量核对）。玩家移动路径零开销；距离相关逻辑（TombChallenge 远离判定 `TombChallenge.java:316-320`、ForgeStage `shouldClose`）都走定时任务轮询，方向正确。此条无需修复。

---

## 三、正面发现（不改）

- `StatsStore` 自动保存走异步线程且带 dirty 标记与 CME 逐玩家兜底（`StatsStore.java:669-710`），是库内持久化的正确范本。
- `VanillaGuardListener.onInventoryClick`（`listener/VanillaGuardListener.java:179-199`）先判顶部容器类型再判定，普通背包点击零成本。
- `ForgeManager.tick` 的逐会话 try/catch 防任务被异常整体取消（`:544-557`），稳定性设计良好。
- Display 实体一律 `setPersistent(false)` + 区块加载补生成（`AnvilForgeManager.java:374, 1229-1244`），无实体残留风险。

## 四、Top10 修复优先级

| 序 | 编号 | 位置 | 问题 | 严重度 | 工作量 |
|---|---|---|---|---|---|
| 1 | #1 | ItemFactory.java:452-487 / CombatListener.java:62 | 每次命中全量重写耐久 Lore | P0 | M |
| 2 | #2 | SpiritManager.java:252-264 | 每命中 3~4 次 meta + 全 Lore 扫描 | P1 | M |
| 3 | #3 | AuctionManager.java:191-195 等 | 竞价主线程同步整档 data.yml + auction.yml | P1 | M |
| 4 | #4 | ForgeMenu.java:460-493 / ForgeManager.java:617-624 | 锻造 GUI 每 2 tick 全量重绘 + BossBar 每 tick 更新 | P2 | M |
| 5 | #5 | ArmorRebalanceListener.java:104-109 | 每次背包点击排扫描任务 | P2 | S |
| 6 | #6 | ForgeListener.java:361-387 / AffixSetListener.java:151-196 | 受击双监听器重复读 10 次护甲 meta | P2 | M |
| 7 | #7 | ContractStore.java:80-100 | 契约改动即同步落盘、结算循环多次写 | P2 | S |
| 8 | #8 | TombManager.java:174-202 | 剑冢同步全量重写（Base64 快照） | P2 | S |
| 9 | #11 | TombChallenge.java:284 | ticker 启动后永不取消（空转） | P3 | S |
| 10 | #12 | Msg.java:22-24 | MiniMessage 无缓存，高频重复解析 | P2 | S |

【审计完成，共 16 条】
