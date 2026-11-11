# 32 · v2.23.1 查错与修复总报告（验证篇）

> 本报告是这一轮的**收口文档**：审计分五条线并行，确认缺陷 → 修复 → 编译验证 → 加回归自检。
> 明细各自成篇：`20-bughunt-econ` / `21-bughunt-persist` / `22-bughunt-gui` / `23-bughunt-core`，
> 修复记录：`27-fix-forge` / `28-fix-strengthen` / `29-fix-gui` / `30-fix-econ`。
>
> **注意**：`24-bughunt-content` 这一线（内容模块：剑冢词缀 / 秘境 / 套装 / 剑灵显形 / 委托周期）
> 的产出被转用于修复任务，报告未落盘；该线的部分疑点已在下面「未修 / 待办」里留名。

---

## 一、验证结论

| 项 | 结果 |
| --- | --- |
| `mvn -B -DskipTests clean package` | **BUILD SUCCESS**，`target/ForgingMaster-2.23.1.jar`（1,496,029 B） |
| `mvn -B -q compile` | 通过（仅剩既有 deprecation：`Sound.valueOf`、`OldEnum#name()`，非本轮引入） |
| 配置键漂移机器比对（`tools/cfgkeys.ps1`） | 代码读键 468 / 盘上键路径 1462，**真实缺失 0 条**（剩余命中全是动态前缀拼接与迁移运行时写入键） |
| 新增/修改源文件 | 41 个（含 8 个 metrics 新文件、3 个 webhook 新文件、1 个回归自检） |
| 源码总文件数 | 197 |

### 配置键漂移是怎么算出来的

`tools/cfgkeys.ps1` 把 `config.yml` 按缩进解析成完整键路径集合，再从源码里抽出所有
「字面量第一参数」的读键调用（`settings().intOf/num/flag/str/strList/section`、
`getConfig().getX`），两边做机器比对。这个脚本在调试中踩过三个坑，修好后才可信：

1. 纯注释值（`key:   # 说明`）被 `:\s*` 吃掉空格后以 `#` 开头，若不单独判定，
   该段不会压栈 → **整段子键全被误报缺失**；
2. 行内映射（`attack-damage: { multiplier: 1.25 }`）的子键要展开；
3. 用 `| Select-Object -First N` 截断输出会**提前终止管道**，导致脚本没跑到写文件那步，
   读到的是上一版残留结果。

修正后：`pass.premium.cost` / `pass.premium.rewards` 是全库唯一真实漂移
（代码读 `pass.premium.*`，盘上写在 `season-pass.premium.*`），已修 + 加迁移 step22。

---

## 二、修复总表

### P0（5 条，均已修）

| # | 位置 | 缺陷 | 修法 |
| --- | --- | --- | --- |
| 1 | `util/Attrs.java` + `item/ItemFactory.stripBaseModifiers` + `affix/Reforge` | `isBaseKey` 用前缀判定，`fm_armor_plate_<部位>` / `fm_toughness_plate_<部位>` 被当成基础属性键：崩刃摘掉、修复只重建基础键 → **词条属性永久丢失**；重铸时旧值删不掉、另挂一条 → **属性叠加**；`*_plate` 被拦死 → **词条 0 值** | 改成精确白名单（4 裸键 + 8 部位键）。同一处判定修好三个出口。`Reforge` 新增 `affixKey(meta, affix)` 按 `ARMOR_SLOT` 补部位后缀，与 `ItemFactory` 写法对齐 |
| 2 | `anvil/AnvilForgeManager.saveTo` / 全部结构变更点 | ① `synchronized saveTo` 保护不到主线程 `sessions.put/remove` → 异步拍快照 CME → **重复任务被 Bukkit 永久取消**，此后砧位不再落盘；② 写完**无条件**清脏 → 取剑后崩溃 = **复制**，放剑后崩溃 = **吞** | 新增 `dirtySeq` 脏代数；`putSession` / `removeSession` 持同一把锁；`saveTo` 锁内拍快照、锁外写盘、代数相等才清脏；写失败保留 dirty |
| 3 | `StrengthenService.baseOf`（属性滚雪球） | 无快照老物品每次强化都在 `base×mult` 上再乘一次倍率，且虚高值被写回快照永久化 —— 生存服上等于「谁锤得多谁无敌」，Lore 还写着 +7 | 天花板规则 `min(取到的基础值, 配置基础值 × strengthen.base-tolerance)`；无快照时用 `modifier ÷ 当前倍率` 反推；三条路径全过天花板，**只压虚高不抬正常值**；新增 `/zhujian fixattrs [all]` 收敛存量 + 迁移 step24 |
| 4 | `backup/BackupManager`（清单 + `restore` 顺序） | ① 清单只有 10 份，实际落盘 18 份 —— 回档后会出现「剑币回到 3 天前、行会仓库还是现在」这种最迷惑的状态；② `restore` 先备份**再** flush（前置备份抓的是旧盘，反悔点比现实更早）；③ 覆盖后只重建了 `data.yml` / `auction.yml` 两个内存态，其余模块带着 dirty 标记，5 秒~5 分钟内把刚恢复的文件**原样盖回去** —— 管理员看到「回档成功」，几秒后悄悄回滚 | 清单补到 18 份 + `metrics/` 整目录；顺序改为 flush 全部 → 备份 → 覆盖 → **只重载不保存**（十个模块各 `load()`/`reloadStore()`，逐个 try 避免半落盘）；刻意**不调用** `reloadAll()`——它对 spectrum/seasonPass/dailyStore 会先 `save()`，等于用回档前的内存盖回档后的文件 |
| 5 | `combat` 数值超标（实测一刀 130 / 四刀守者 / 吸半管） | 四层乘区各自合理、叠起来失控：材质 5.25×品质 5.0 = 26.25 起点 → ×强化 1.594 → ×蜕变 1.752 = **73**（原版剑 8）；暴击再 ×4.0；吸血词条 0.14×1.65×5 条 = **115%** 且**完全没有上限**。另发现气血词条无上限（单件 +41 生命）、点燃叠 5 条 40 秒。 | 新增 `DamageBudget` 纯数值层 + `combat.limits.*` 8 键：攻击属性 22 / 单次伤害 60（**夹在暴击之后**）/ 吸血 20%（三道夹子取最小）/ 单件减伤 0.35 / 气血 20 / 点燃 10 秒；暴击口径 0.75+2.5 → 0.50+1.10（×2.6）。写入端同步封顶，Lore 与实战一致。实测：满配 130 → **59.8**，守者 4 刀 → **8.4 刀**，吸血半管 → **2 点**。 |

### P1 / P2（本轮已修，按模块）

**铁砧**（`AnvilForgeManager`）
- `start()` 丢弃 `runTaskTimer` 句柄 → 每次 reload 永久多挂一条 3 秒心跳 → 存 `tickTaskId` + `stopTickTask()`。
- `strike` / `strikeBatch` 不检查 `anvil-forge.enabled`（只有 `place` 检查）→ 两条路径补同一道门。
- `dropReturn` 先删会话再发现世界为 null → **剑静默消失** → 改为「无处可去就保留会话」。

**锻造**（`forge/ForgeManager`）
- `replaceRepaired` 按「材料 + 旧耐久」扫背包 → 同档同耐久的另一把被覆盖成克隆（**复制成品**）→ 开局记槽位、收尾只按槽位替换 + 三重校验，失效则发给玩家，删掉模糊匹配。
- `discard` 在 RESULT 阶段不发成品（`cancel` / `onClose` / `shutdown` 都发）→ 断线吞成品 → 补发，落点必须过「已加载世界」回查，并留可追溯日志。

**战斗**（`listener/CombatListener`、`listener/ForgeListener`）
- 投掷物不归因射手 → 词条不结算 + 耐久不扣（远程白嫖）→ damager 展开为玩家或玩家投掷物。
- 暴击在 `HIGH` 改伤害 → 改 `HIGHEST`，算出的 `dealt` 直接传给吸血，不再二次读 `getFinalDamage()`。
- `ForgeHolder` 分支不校验点击者是会话主人 → 补齐（与 `ContractHolder` / `EvolutionHolder` 一致）。
- `onDrag` 漏 `ContractHolder` / `EvolutionHolder` / `EnvPanelHolder` → 拖入即蒸发 → 补齐，12 项 holder 与 `onClick` 逐项对齐。

**强化**（`strengthen/StrengthenService`）
- 护胚符顶住碎裂时 `toLevel` 仍返回 `level-1` → ActionBar 报假掉级 → 抽 `keptLevel` 各报各的。
- 剑尘抵扣时虚报「花掉 N 个材料」→ 新增 `matUsed = need - dustUse`，7 处 `StrikeResult` 统一用它。
- ~~`baseOf` 属性膨胀~~：**已修（v2.23.1 收口，见 P0 第 3 条）**。原推理「除以 mult 会按等级削基础属性」是对的，但正确解法不是不除，而是**除完再过天花板** `min(值, 配置基础值 × base-tolerance)`——只压虚高、不抬正常值。

**通行证**（`pass/SeasonPassService`）
- `claimPremium` 发的是 `lv.reward`（免费轨）→ 付费轨 5000 剑币买断却领到免费奖励 → 改发 `premiumReward(levelNo)`。
- 先记已领再发奖（发奖抛异常即永久吞）→ 失败回滚领取记录，允许重试。
- 符 `setAmount(>最大堆叠)` 抛异常 → 按上限拆多叠发放。
- 配置漂移 → `premiumLong` / `premiumSection` 规范键优先、`pass.premium.*` 兜底。

**行会仓库**（`guild/GuildWarehouse*`、`GuildGuiRouter`）
- `CLOSE_SLOT=49` 与容量 54 重叠 → 第 49 格每次开仓被关闭图标覆盖销毁 → 上限压到 5 行 45 格，装饰区 `[45,54)` 与储物区 `[0,45)` 不相交，不变量写进类注释。
- 整表快照「后关者为准」→ 逐格 `commit(expectedPrev)` 冲突拒写 + `resyncIfStale` 点击前对账 + `onQuit` 走同一套（不依赖 `InventoryCloseEvent`）。
- 超容量老物品原本 `continue` 丢弃 → 进 `overflow` 保留区随盘保存、腾空自动回流（**升级不吞物品**，见下方升级提醒）。
- `isTake` 漏 `SWAP_WITH_CURSOR` → 越权取出 → 补白名单。

**GUI 其余**
- 付费轨武装状态随每 tick 重建的 Holder 丢失 → **永远买不下来** → 移到 `PassGuiRouter` 静态表，按 uuid + 槽位 + 3 秒窗口。
- 称号 GUI 状态格写 4 号、条目循环从 0 起覆盖 → 误点即换称号 → 新增纯函数 `entrySlot(int)`，两套索引不相交。
- 订单 GUI `render` 不清 `slotOrders` / `takenId` → 点灰格用陈旧 id → `resetBindings()`。
- 圣物页 `continue` 跳过 `i++` → 显示槽与点击下标错位（**领错圣物**）→ 下标无条件前进 + 下架项灰玻璃占位。

**经济账务**（`auction/`、`bounty/`、`commission/`、`dissolve/`、`econ/`、`order/`、`guild/GuildService`）
- 拍卖 `claim` 先清邮箱再入包 → 崩溃复制 → 「先入包 → 剩余留邮箱 → 同步落盘」。
- 单据（`auction.yml` 5s）与剑币（`data.yml` 5min）分文件异步落盘 → 五条路径固定顺序，崩溃收敛成**漏发**而非二次结算。
- 悬赏用 `getFullTime()/24000` 当自然日 → 每 20 分钟换日 + 无限刷 → 改真实日期 + `bounty-data.yml` 持久化已领名单。
- 委托 `save()` 生产路径从未调用（唯一调用点在自检里）→ 四条路径接上。
- 剑尘钳 64 且只造一叠（+20 神话剑应得 260 实得 64）→ 按 64 多叠 + `dustOf` 升 `long` 饱和钳制。
- 双账本（`Wallet` vs 各模块硬编码 `stats()`）→ 收敛到 `Wallet`，11 个调用点切换；`shop`（2）/ `order`（3）/ `contests`（1）/ `guild`（2）未切换，清单在 `30-fix-econ.md`。
- 行会解散逐人写盘的部分漏退 → 先全员发钱 + `stats.save()` + treasury 清零，再拆会（重试不二次分赃）。
- 订单过期先结案后加钱（漏退）→ 改 `wallet().giveTo` + 立即落盘。

**持久化**（`backup/` 部分由 persist 线接手，状态见下）
- `reloadAll()` 不重启 `contests` / `events` / `guilds` 调度器 → 4 个周期参数改了不生效 → 统一 stop → load → start。

---

## 三、新增内容

### 0. 数值实测器 `tools/botrun.ps1` + `NumericSim`

`NumericSim` 零 Bukkit 依赖，只调 `DamageBudget` / `Crit` 两个纯函数类，因此「离线跑出的数」＝「线上生效的数」。四个 bot 覆盖新手 → 中档 → 满配 → 极端堆料：

| bot | 攻击属性 | 普通伤害 | 暴击率 | 暴伤倍 | 暴击伤害 | 吸血/刀 | 砍坚守者（500 血） |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 新手·铁剑+5 | 1.4 | 2.4 | 10% | 1.80 | 4.4 | 0.2 | 113.8 刀 |
| 中档·钻石+25 | 10.9 | 11.9 | 35% | 2.30 | 27.3 | 2.0 | 18.3 刀 |
| 满配·星陨+99·六阶 | 22.0（原始 73，被夹） | 23.0 | 50% | 2.60 | 59.8 | 2.0 | **8.4 刀** |
| 极端·理论堆满 | 22.0 | 23.0 | 50% | 2.60 | 59.8 | 2.0 | 8.4 刀 |

原版参照：下界合金剑 + 锋利 V ≈ 11/击，砍守者约 45 刀。10 项断言全 PASS（含幂等、NaN 不传染、夹子单向性）。

### 1. 数据看板切片 2/3 真正接线

`MetricsService` 及其 7 个配套类（`MetricsData` / `MetricsHistory` / `MetricsSample` / `MetricsMath` / `MetricsIo` / `MetricsCsv` / `MetricsReporter`）此前**在包里躺着但主类从未构造**——趋势与 CSV 永远不会跑，`metrics.history-days` 改了没反应。现在：

- `ForgingMasterPlugin` 构造 + `start()` / `stop()` / `reload()` 全接线，`metrics()` 访问器。
- `ForgingCommand` 新增三个子命令：`metrics trend [天数]`（逐日产出/消耗/净额/锻造/碎裂 + 趋势总结）、`metrics csv [天数]`（导出 CSV）、`metrics sample`（立刻采样）。无参时与 v2.20 完全一致（老用法不破）。
- **切片 3 三页 GUI**：`MetricsGui` + `MetricsGuiRouter`（懒注册 + `reset()`）。三页 = 实时体检 / 近况趋势 / 分配分布；内容行铺成纸片（名字截断、Lore 全文），悬浮可读；`45` 内容区 + `45/48/49/50/53` 五个按钮槽互不重叠（自检断言）。所有点击与拖拽一律取消，`/zhujian admin metrics gui` 打开。

### 2. 出站 Webhook（拓展 C）

新增 `com.forgemaster.webhook`：`WebhookEvent`（8 类事件，稳定 id）+ `WebhookService` + `SelfTestWebhook`。

- 有界 `ArrayBlockingQueue`（默认 64，钳 8~1024），满即丢最新——**主线程永不阻塞**；
- 独立守护线程 + `take()` 阻塞消费，`stop()` = 置标志 + `interrupt()` + `join(1s)`；
- 每类事件 **10 秒限流**（按枚举分桶）；连续失败 5 次**熔断 60 秒**，期间失败不记日志；
- JSON **手写转义**（`"` `\` `\n` `\r` `\t` + 所有 `< 0x20` → `\u00XX`），字段按 key 字典序稳定输出；
- 配置 `webhook.{enabled,url,username,timeout-seconds,queue-size,events}`，默认关闭、url 空即空壳；
- **八类事件全部插桩**：锻造成功（`ForgeManager`）/ 强化成功 + 碎剑（`StrengthenService.strike` 外包一层，覆盖铁砧 / 命令 / 批量三条入口）/ 蜕变成功（`EvolutionService.succeed`）/ 剑冢胜利（`TombChallenge`）/ 比试获胜（`DuelManager`，平局不播）/ 行会委托达成（`GuildQuests.complete`）/ 赛季结算（`SeasonManager`，放在所有提前 return 之后——目录建不出、归档失败这两条路径没有真的结算，播出去会误导）。统一入口 `plugin.broadcast(...)`，内部 try 掉一切异常。

### 3. 战力评分 PowerScore（路线图切片 5 · D）

新增 `com.forgemaster.score.PowerScore`：`power = 基础(品质×材质) × 强化 × 蜕变 × 词条 × 宝石 × 剑灵`

- **乘法而非加法**：强化高段是门槛、蜕变不可逆，加法会让「+20 词条稀烂」与「+2 满词条」同分；每项系数钳在小范围（强化上限 8 倍），不会一项爆表压死其它项。
- **权重全走配置** `power-score.*`（系数 / 每词条权重 / 评级分界 / 推荐价），`/zhujian reload` 生效；代码只给保守初值——路线图 §六 要求等切片 1-3 跑两周再按真实成交校准。
- **可解释**：`/zhujian power` 输出拆解（分数 / 品质材质 / 强化蜕变词条 / 宝石附魔 / 推荐价）。
- **防刷**：只读物品自身 PDC（全部服务器写入），不做跨物品累加，复制堆分不成立。
- 推荐价 = 战力 × `price-per-power`，取整到 10，`price-min` 兜底，可关闭。
- **已接进玩家侧**：拍卖条目 Lore 显示「战力 + 评级 + 参考价」，并在当前价偏离参考价一倍时标偏高/偏低；挂单成功消息追加「这件战力 X（评级），参考价 Y」；订单发单成功消息追加同样的样品定价提示。

---

## 四、回归自检（新增）

`SelfTestRegression231`（13 项，经 `addExternal` 挂接）：
1. 基础键精确性：`fm_armor_plate_chestplate` / `fm_toughness_plate_boots` / 任意带部位词条键 → **不是**基础键；4 裸键 + 2 部位键仍认得；`null` 与外部键不算。
2. 铁砧心跳：重复 `start()` 不叠加、任务 id 有效。
3. 付费轨奖励表常驻且越界为空。
4. 付费轨价格取 `season-pass.premium.cost`（旧键兜底）。
5. 仓库超容量抢救：写入 `slots.(cap+3)` 的物品读回后**必须**出现在合法空格或 `overflow` 区。
6. 储物区上界 < 关闭槽；`clampRows(99)*9 == STORAGE_MAX`；行数下限 ≥1。
7. 迁移纪律：`CURRENT_VERSION ≥ 13`；8 个新键都在盘上；`guild.warehouse.rows ≤ 5`。
8. 看板接线：`metrics()` 非 null、`running()` 与 `enabled()` 一致、`historyDays/intervalMinutes ≥ 1`、`dayTotals` 对越界窗口安全。
9. 词条键不撞基础键（新增词条 id 不会复现 P0）。
10. 备份清单完整性（18 份必备文件全在、无重复、无路径片段）。
11. 回档顺序守卫（清单项都是同目录 yml、list() 不抛）。
12. 战力评分：非锻造品 0 分、单调性（神话 > 普通）、上限钳制。
13. 战力评分：推荐价 >0 且整十、评级永不为空、拆解至少两行。
14. 战力评分配置：CURRENT_VERSION ≥14 且 6 个键在盘上。

`SelfTestWebhook`（5 项）：JSON 转义四个坑 + 控制字符 + 中文保留；JSON 结构与 fields 字典序稳定；事件 id 解析与未知项容错；限流按类型分桶；`stop()` 后 `dispatch` 不抛且不入队。

---

## 五、未修 / 待办（如实列出）

### 第三轮检修（同日）：成品界面「取不回」P0

服主报的症状：**锻完点「拿走」没反应，直接 ESC 关掉，剑就消失了。不是每次都触发。**

这不是「偶现玄学」，是一个**时序 bug**，根因在 `ForgeManager.open()`：

```java
session.transitioning(true);
p.openInventory(inv);
session.transitioning(false);   // ← 同步清掉，这道门等于没关
```

每次换界面（加热→锤打→…→成品）都会产生一次旧界面的 `InventoryCloseEvent`。
**这个事件不一定同步送达，很多时候是下一 tick 才到** `onInventoryClosed`。等它到时
`transitioning` 早已被清成 `false`，于是：

1. 进成品界面的顺序是「先 `phase(RESULT)`、再 `open(...)`」；
2. 迟到的关闭事件看到的是「phase == RESULT 且玩家关了界面」；
3. `giveResult → finished(true) → removeSession` —— **会话当场被销毁**；
4. 成品界面还开着，但 `handleClick` 第一行 `s.finished()` 直接 return → **点「拿走」毫无反应**；
5. 玩家 ESC 关掉界面，格子里那把剑（只是展示副本）随之消失 → 「剑没了」。

「不是每次都触发」正是因为只有那次关闭事件**恰好迟到**时才会踩到。

修法分三层，都不依赖 tick 时序：

| 层 | 位置 | 做法 |
| --- | --- | --- |
| 地基 | `ForgeManager.isTransitionClose(boolean, boolean)` | 纯函数语义判断：看「玩家此刻打开的上层界面是不是同一会话的另一张」，而不是看标志 |
| 门 | `ForgeManager.open()` | `transitioning` 改成**下一 tick** 再清（覆盖「迟到一 tick」的情形） |
| 兜底 | `ForgeListener.onClick` + `ForgeManager.reclaim()` | 会话已结算但成品界面还停着时，点哪都能把成品拿走 |

顺带修掉一个**永久丢东西**的隐患：`giveResult` 原来是「先 `resultGiven(true)`、再 `addItem`」，
中途抛异常就等于把剑永久吞掉且再也补不回来。改成**先发放、后打标记**，并加 `clone()`
（同一 ItemStack 对象同时被放在成品界面第 13 格展示，交给背包的必须是独立副本）。

同库其它两个 `InventoryCloseEvent` 处理器已核对安全：`ContestWatchGui.onClose` 只停刷新任务，
`GuildWarehouseGui.commitAll` 幂等。

### 第二轮检修（同日）：抓到并修掉 3 个真 bug

这三条都不是「数值好看不好看」，是玩家能直接踩到的功能失效，且都属于**同一类根因：同一个默认值/同一个标志存在两份**。

| # | 位置 | 症状 | 根因 | 修法 |
| --- | --- | --- | --- | --- |
| A | `gui/ReforgeMenu.listenerReady` | **重载插件后重铸界面点不动**（打开正常，点任何格子无反应） | 静态标志比插件实例活得长；`onDisable` 从不清零 → 新实例 `ensureListener()` 直接 return，监听器永不注册 | 新增 `ReforgeMenu.reset()`，挂在 `onDisable` 与其它八个 GuiRouter 同一位置 |
| B | `combat/Crit` | 线上按 `2.5` 暴伤上限算、自检按 `0.75/2.5` 断言 → 自检必红 | `resolve(2参)` 与 `fromConfig` 各写一份默认值，改数值时只改了一处 | `resolve(2参)` 转调 `Limits.defaults()`；`fromConfig` 的兜底也读 `defaults()`；同步更新 `SelfTestSuite` 里断言旧值的 2 处 |
| C | `ItemFactory` / `CombatListener` / `LegacyItemRepair` / `StrengthenService` | 改上限时漏改一处 → Lore 显示 20%、实战夹 15% | `0.20 / 0.35 / 20.0 / 10.0 / 22.0` 被四五个模块各写一遍 | 新增 `DamageBudget.Caps.fromConfig()` 与 `Limits.fromConfig()`，**所有调用点只许走这里**；`budgetAttack()` 改调 `DamageBudget.clampAttack` |

顺带交付上一轮承诺的拆包：

- `StrengthenService.applyStatsOnly(ItemStack)` —— 只重算属性、**完全不碰 Lore**。
  此前存量收敛调的是 `apply()`，它会顺手 `rewriteLore`，把「锻造等级」行整条重建，
  导致「只超了吸血的剑」看起来像整套属性都被改过。新增自检断言：`applyStatsOnly` 前后 Lore 文本必须逐字相同。

### 存量收敛已实装（原「已知残留」已关闭）

新增 `com.forgemaster.compat.LegacyItemRepair` + `LegacyRepairListener`，三个触发点：`/zhujian fixattrs`（手持）/ `all`（在线玩家，含末影箱）/ `containers`（已加载容器）；离线玩家由 `legacy-repair.on-join` 在上线时自动收敛（不碰 playerdata）。收敛项：基础属性重算、吸血→0.20、点燃→200 tick、减伤→0.35、气血按比例缩到 20。**幂等**（第二条断言验过），**不误伤原版物品**（第三条验过）。

残留限制：Lore 文本无法按条重算（`affix_list` 只存 id、不存每条的 roll 值），故只夹生效值并在 Lore 末尾打收敛说明；要干净 Lore 需重铸一次。

| 项 | 状态 | 原因 |
| --- | --- | --- |
| ~~`StrengthenService.baseOf` 属性膨胀~~ | **已修（P0 第 3 条）** | 天花板规则 + `/zhujian fixattrs [all]` 清理存量；三条回归断言（幂等 / 脏快照收敛 / 单向性）。 |
| 备份 `restore()` 停机顺序 / 清单补全 | **已修（本轮收口时接手）** | 见下方「P0-3 备份」。`StatsStore` 代数清脏与 `TombManager` 锁仍属 persist 线未完项。 |
| `shop`（2 处）/ `order`（3 处）/ `contests`（1 处）/ `guild`（2 处）钱路径未切 `Wallet` | 未切换 | 越出 econ 线授权范围，清单在 `30-fix-econ.md`。 |
| `StatsStore.addCoins` 负数守卫 / 溢出 | 未改 | 越界，留给 persist 线。 |
| 签到 / 黑市「先发货后记账」 | 未改 | `blackmarket/` 越界。 |
| 内容模块审计（`24-bughunt-content`） | **报告未落盘** | 该线产出被转用于强化修复；剑冢词缀权重全零、秘境任务 cancel、套装主动技冷却、委托周期边界等疑点**未逐条核实**。 |
| Webhook 事件插桩 | **已完成（八类全插）** | 见上方「出站 Webhook」；`SelfTestRegression231` 新增两条：事件 id 全表对账、未启用时播报链路零副作用。 |
| 看板切片 3 的「三页 GUI」 | **已完成（本轮收口时补）** | MetricsGui + MetricsGuiRouter，三页 + 翻页/刷新/关闭，槽位不重叠有自检断言。 |

### 升级提醒（务必转达服主）

1. **行会仓库**：v2.23.0 若配过 `guild.warehouse.rows: 6`，升级后迁移会自动收敛到 5。第 45~53 格物品**不会丢失**（转保留区并随盘保存，腾空自动回流），但建议进后台核一次仓库存量。
2. **配置版本**：`config-version` 将从 12 抬到 13，首次启动会写一次 `config.yml` 并补 8 个新键 + `webhook` 段（默认关闭）。
3. **吸血脉口径**：暴击伤害改在 `HIGHEST` 结算，吸血按「基础伤害 × 暴击」计算，比 v2.23.0 略高（不再吃护甲减伤）。若要与实际掉血一致，需另挂 `MONITOR` 监听。
4. **备份**：`BackupManager` 已修（清单 18 份 + `metrics/`，`restore` 顺序改正），但**第一次用新版本回档前仍建议手工整目录备份** `plugins/ForgingMaster/` —— 清单之外的运维自建文件不在覆盖范围。
5. **回档后建议重启**：铁砧砧位会话与悬赏已领名单没有公开 reload 入口，`restore` 只能靠「重启」彻底归位（命令提示里已写明）。

---

## 六、复现验证步骤

```bash
# 1) 编译 + 打包（本轮收工时绿）
mvn -B -DskipTests clean package
# → target/ForgingMaster-2.23.1.jar

# 2) 配置键漂移（应输出「代码读但 config.yml 无此键: 0」或仅剩动态前缀）
pwsh -NoProfile -File tools/cfgkeys.ps1

# 3) 进服全量自检
/zhujian selftest
# 期望：v2.23.1 回归组 9 项 + v2.23 Webhook 组 5 项全绿

# 4) 看板
/zhujian admin metrics
/zhujian admin metrics sample
/zhujian admin metrics trend 7

# 5) 关键手测（自检覆盖不到的）
#    - 穿锻造胸甲挨打到崩刃 → /zhujian repair → 检查 armor_plate 词条数值仍在
#    - /zhujian reload 连续 5 次 → 观察砧位心跳是否只有一条（全息不闪、不重复刷）
#    - 两人同时开同一行会仓库，各取一件 → 关仓后核对物品总数守恒
#    - 买断付费轨 → 领第 1 级 → 确认到账是付费轨default（800 币）而不是免费轨那份
```
