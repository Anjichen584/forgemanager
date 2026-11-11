# ForgingMaster-Lore 代码质量审计（04）

- 审计对象：`D:\IDEA\mc-plugins\ForgingMaster-Lore`（Paper 1.21.11，包根 `com.forgemaster`）
- 审计方式：只读核查，唯一写入为本文件。所有证据均为实读的 `文件:行号`，未实测运行时行为。
- 说明：自检断言实际统计为 **346 项**（`add(...)` 调用计数，SelfTestSuite.java），并非口传的 286 项。

---

## 一、超长类拆分方案（不改任何行为）

原则：每个大类按「职责 → 新类」机械搬移，方法体一行不动；新类只持有原类已注入的依赖，依赖方向单向向下（编排层 → 领域层 → 纯函数层）。

| # | 原类（行数） | 拆分包结构 | 迁移方法（证据：方法定义行） | 依赖方向 | 工作量 | 风险 |
|---|---|---|---|---|---|---|
| S1 | `selftest/SelfTestSuite.java`（4557） | `selftest/SelfTestSuite`（保留 run/report/add/require 骨架，:63-121）+ `selftest/SelfTestSupport`（sampleSword:3312、comboPatcher:296、frenzyPatcher:310、stagePatcher:453、attrAmount:467、loreCount:481、pdcDouble:3302、loreText:3548、stripStrengthenKeys:3595 等取样/补丁/断言工具）+ `selftest/checks/AnvilChecks`（registerAnvilForge:2144-2951，约 800 行独立成类）+ `checks/StrengthenChecks`（registerStrengthenRisk:1998、registerV210:497、registerV29:1054、registerV27:1169、registerV26:1505、registerV25:1710、registerAffixScalingStrengthen:3097）+ `checks/ContractChecks`（registerContractLoop:149、registerTickets:222、registerEvolution:323）+ `checks/CoreChecks`（registerForgeFlow:4008、registerScoreAndStats:4145、registerCoinsStreakShop:4197、registerAuctionCommission:4257、registerContestDuelMentor:4334、registerGrowthEnvSpirit:4380、registerTombRiskEventsStory:4445、registerRankingSeasonBackup:4482、registerSocketsAndGuards:2951、registerDurabilityGem:3612、registerEconomyArmor:3859、registerMaterialsAffixesQuench:3923） | 各组 `register()` 只调用 `add()`；Support 不依赖 checks；checks 之间不互调 | L | 中（add/require 语义必须原样共享；搬动时易漏 `checks` 列表注册顺序，需保持原 register() 顺序） |
| S2 | `command/ForgingCommand.java`（2765） | `command/ForgingCommand`（onCommand:39 分发 + onTabComplete:2396）+ `command/PlayerCmds`（stats:384、profile:443、rank:464、top:349、board:525、talent:127、talentPanel:206、achievements:337、manual:592、record:601、guide:1043、hallOfFame:2244、season:505）+ `command/ForgeCmds`（give:259、hammer:1020、gem:709、gemGive:748、gemList:825、enchant:864、strengthen:895、evolve:1057、strip:1165、reforge:1193、grade:1337、codex:1386、copy:615、repair:575、salvage:1430、forgeRisk:2133）+ `command/EconCmds`（money:1250、moneyCoin:2324、shangjin:1130、contest:643、duel:540、shop:2190）+ `command/AdminCmds`（backup:1468、restore:1488、selftest:1514、legacySelftest:1525、worldEvent:2167、env:2203、story:2229）+ `command/Tab`（tabComplete0:2410 及全部 tab* :2467-2718）+ `command/CmdArgs`（safeInt:583、parseIntSafe:848、parseDouble:2381、pad:1420、signedPct:2224、players:2719、filter:2755） | CmdArgs 为纯函数无依赖；各 Cmds → plugin 门面；Tab → 各 Cmds 提供候选 | L | 中（legacySelftest:1525-2133 单方法 600 行，搬动时注意它是历史兼容入口） |
| S3 | `strengthen/StrengthenService.java`（1927） | `strengthen/StrengthenService`（保留状态读写与 strike 编排 :912-1167）+ `strengthen/StrikeMath`（纯函数：baseSuccessChance:245、breakChance:437、odds:658、afterStrike:619、tierOf:708、resolveFail:462、oddsLine:1182）+ `strengthen/StrikeApply`（apply:1599、baseOf:1664、replace:1683、rewriteLore:1709、rebalanceArmor:1507、migrateLegacyKeys:1564、strengthenLine:1786）+ `strengthen/Simulator`（simulateStats:1311、simulate:1396、solve:1447）+ `strengthen/ItemCounters`（intOf:1893、writeInt:1905、addInt:1917、comboOf/furnaceOf/failsOf 等 :478-534、879-901） | ItemCounters/StrikeMath 无 Bukkit 物品状态副作用之外依赖；Apply → StrikeMath；Service → 全部；Simulator 只读 | L | 中（apply/strike 强耦合 PDC 键语义，拆后必须保证同一 meta 读写顺序不变） |
| S4 | `forge/ForgeManager.java`（1614） | `forge/ForgeManager`（start/stop/session/start/begin :71-238、tick 调度 :537）+ `forge/StageEngine`（tickHeating:610、tickHammering:644、startFolding:668、tickFolding:717、clickFolding:733、finishFolding:781、startGrinding:808、tickGrinding:836、clickGrinding:965、finishGrinding:988、clickHeating:1018、clickHammering:1077、startQuenching:1171、clickQuenching:1151）+ `forge/ForgeSettle`（settle:1205、giveResult:1427、announce:1393、weightedScore:1185、heatScore:568 已是 static）+ `forge/RepairFlow`（beginRepair:171、findBlueprint:227、finishRepair:884、replaceRepaired:945）+ `forge/StageDirector`（spawnStage:463、findAnvilTop:499、announceEnv:408、envBonusText:424） | Manager → StageEngine → ForgeSettle；StageDirector 只读 EnvManager；各阶段只经 ForgeSession 传状态 | L | 中（tick/tickSession:581 是全部阶段的心跳，拆后调度顺序不能变） |
| S5 | `gem/GemService.java`（1393） | `gem/GemService`（smelt:301、createGem:263、merge:672）+ `gem/SocketOps`（socketsOf:80、gemSocketsOf:90、enchantSocketsOf:100、enchantLimitOf:119、usedSockets:230、socketLimitOf:240、freezeEnchantSockets:151）+ `gem/GemInlay`（socket:362、socketEnchant:444、inlay:799、inlayEnchant:1031、extract:521、extractEnchant:585、reapplyGemEffects:889、applyGem:996）+ `gem/GemLore`（gemLore:1169-1181、rebuildGemBlock:1182-1270、isSocketTitle:1271、enchantLine:1276、enchantName:1282、roman:1292、encode/encodeEnchants:1358-1369） | GemLore 纯函数（static 已大半）；SocketOps 只读 PDC；GemInlay → SocketOps + GemLore | M | 中低（rebuildGemBlock 已 static，迁移机械） |
| S6 | `anvil/AnvilForgeManager.java`（1377） | `anvil/AnvilForgeManager`（place/take/strike :144-292、tick:1147、load/save :1271-1354）+ `anvil/AnvilHolo`（refresh:344、despawn:982、boardText:1005、boardLine:1085、scaleLine:1102、gateHint:1059、gateLine:1112、color:1132）+ `anvil/StrikeAnimation`（playStrikeAnimation:835、strikeFrame:767、headOffset:812、pivotOffset:817、handleLength:793、spawnFlameTrail:931、spawnImpactFlame:941、spawnSparks:953、hammerDisplayMaterial:740、hammerBillboard:747）+ `anvil/AnvilConfigMigrator`（migrateDisplayConfig:429-625、setComment:628、sword* 姿态取值 :653-722） | Manager → Holo/Animation → StrengthenService；Migrator 只在启动被 Plugin:134 调一次 | M | 低（strikeFrame/headOffset/pivotOffset 已被自检直接断言，搬动保持签名即可，见 T1） |
| S7 | `tomb/TombChallenge.java`（1011） | `tomb/TombChallenge`（challenge:138、start/tick/finish :280-357、spawnShadow:358、onShadowDeath:469、grantRewards:528）+ `tomb/ShadowScaling`（levelOf:587、materialOf:607、tierOf:622、swordAttackOf:628、mobOf:646、mobKeyOf:656、defaultMobKey:692、defaultTier:703、qualityCoefOf:716、scaledHealth:726、scaledAttack:738、rewardRate:747、rewardAmount:756、clampValue:765、parseEntity:773、baseHealthOf:790、mobLabel:812——几乎全 static）+ `tomb/ChallengeCooldown`（cooldowns map :103、canChallenge:206、cooldownRemainingMs:226、markCooldown:574、静态 cooldownRemaining/Until:250-255） | ShadowScaling 纯函数零依赖；Cooldown 只依赖时钟；Challenge → 两者 | S | 低 |
| S8 | `affix/Reforge.java`（951） | `affix/Reforge`（reforge:92、preview:199、candidates:405、weightedPick:453、quenchPool:443）+ `affix/AffixWrite`（applyModifier:472、removeAffixModifier:500、applyAggregates:522、addAffixRaw:582、removeAffixRaw:598、bumpDouble:556、bumpInt:565）+ `affix/AffixLore`（rewriteLore:694、rangeOf:861、amount:879、numText:896、trim:903、takeLine:790、isAffixTitle/Entry/NoAffix:776-785）+ `affix/ReforgeCost`（costOf:273、materialAmountOf:291、materialItemOf:306） | 注意：Reforge 是静态单例（plugin() 缓存 :934-940），拆分时 AffixWrite/Lore 仍是 static 工具，依赖方向 Reforge → 其余，禁止反向 | M | 中（static 传递的 plugin 缓存是隐患，见 D5 合并建议） |

---

## 二、重复逻辑清单（逐对合并目标）

| # | 重复对 | 证据 | 合并目标 | 工作量 | 风险 |
|---|---|---|---|---|---|
| D1 | 概率→颜色映射 | `StrengthenService.chanceColor` :1878-1889 与 `AnvilForgeManager.color` :1132-1143 **逐字相同**（阈值 0.85/0.6/0.35，色值 #4ee36b/#ffd166/#ff9e4a/#ff6b6b） | 抽 `util/UiPalette.chanceColor(double)`；两处调用替换 | S | 低 |
| D2 | 明牌一行格式 | `StrengthenService.oddsLine` :1182-1192 与 `AnvilForgeManager.boardText` :1022-1028、`boardLine` :1092-1095 三处手拼「成功率 X% · 碎裂率 Y%」 | 全息/聊天统一走 `oddsLine`（或抽 `formatOddsShort`），改后跑自检 T2 的全息断言（SelfTestSuite:2662-2680）验证 | S | 低 |
| D3 | 费用曲线公式 | `Reforge.costOf` :273-289 与 `StrengthenService.costOf` :216-231 同款 `(base+per×n)×tierFactor^(t-1)`（Reforge :270 注释自认「同款算法」） | 抽 `econ/TierCostCurve.cost(base,per,factor,tier,index)`；差异点（Reforge 多一个品质乘数 :283-284）作为可选参数 | S | 低 |
| D4 | 材料数公式 | `Reforge.materialAmountOf` :291-304 与 `StrengthenService.materialAmountOf` :801-815 同款公式同款 ceil/max(1) 夹取 | 同上抽 `TierCostCurve.material(...)` | S | 低 |
| D5 | PDC 反查胚子/品质 | `StrengthenService.materialOf/qualityOf` :1797-1817、`Reforge.materialOf/qualityOf` :348-372、`GemService.qualityOf` :1380-1384 三份近似实现（失败兜底略有差异：StrengthenService 走 ArmorCatalog.resolve :1802-1804，Reforge 直接 registry 查） | 抽 `material/MaterialLookup` 静态入口，以 StrengthenService 版（ArmorCatalog 兜底）为唯一语义；Reforge/GemService 改调用。注意 Reforge 是 static 单例拿 plugin（:934-940），合并后可顺带消灭 `cached` 缓存 | M | 中（三处兜底语义不同，合并等于统一行为，需确认 Reforge 场景下 ArmorCatalog 兜底无副作用） |
| D6 | PDC 增量写 | `Reforge.bumpDouble/bumpInt` :556-580 与 `GemService.bumpPdc/bumpFireTicks` :940-964 | 抽 `util/PdcBump`（double/int/ticks 三个方法） | S | 低 |
| D7 | round(2 位) | `StrengthenService.round` :1924-1926 与 `Reforge.round` :910-912 | 并入 `util/Msg` 或新建 `util/Nums` | S | 低 |
| D8 | MiniMessage 行构造 | `Reforge.line` :925-927 与 `GemService.line` :1390-1392（同款 `Msg.mm(x).decoration(ITALIC,false)`） | 并入 `util/Msg.line(...)` | S | 低 |
| D9 | 装备槽组解析 | `Reforge.slotGroup` :385-403 与 `GemService.slotGroupOf` :1015-1029（PDC `ARMOR_SLOT` 兜底逻辑重复） | 抽 `material/SlotGroups.of(meta, mat)` | S | 低 |
| D10 | 命令解析工具 | `ForgingCommand.safeInt` :583-590、`parseIntSafe` :848-862、`parseDouble` :2381-2394 三份同义解析 | 合并为 `command/CmdArgs`（与 S2 配套），再供 AuctionCommand 等复用 | S | 低 |
| D11 | signedPct | `ForgingCommand.signedPct` :2224-2227 与 `ForgeManager.signedPct` :442-445 | 并入 `util/Msg` | S | 低 |
| D12 | 品质颜色硬编码散落 | `Quality.color()` 是正解（`TombChallenge.qualityColor` :901-910 正确走它），但 `StrengthenService.strengthenLine` :1787 仍硬编码 #ffd166/#7fd3ff/#9e9e9e 三段渐变；chanceColor（D1）与炉火色 :1869-1871 也是散落 hex | 品质相关色一律走 `Quality.color()`；概率/炉火状态色走 D1 的 UiPalette；#ffd166 全仓出现在 50+ 文件属 UI 主题色，建议后续统一 Theme 常量类（非本次必改） | M | 低 |

---

## 三、配置死键与默认值漂移

### 3.1 死键（代码完全不再读取）

| # | 键 | config.yml 位置 | 证据 | 处置建议 | 工作量 | 风险 |
|---|---|---|---|---|---|---|
| C1 | `strengthen.success.base/per-level/floor` | :182-185 | 全仓无 `settings().*(\"strengthen.success...\")` 读取；现行曲线读 `anvil-forge.success.*`（StrengthenService:245-254）。但 `warnMissingKeys` 仍把 `strengthen.success.base` 列为「缺少就告警」的必需键（ForgingMasterPlugin:473），诱导服主保留死键 | 从 config.yml 与 warnMissingKeys 同步删除，注释指路 anvil-forge.success | S | 低 |
| C2 | `strengthen.fail.break-base/break-per-level/break-max` | :198-200 | 同上无读取；碎裂率走 `anvil-forge.break.*`（StrengthenService:437-443）。`warnMissingKeys` 仍列 `strengthen.fail.break-max`（:474） | 同上删除 | S | 低 |
| C3 | `tomb.base-chance` / `tomb.mastery-bonus-per-level` / `tomb.mastery-bonus-cap` / `tomb.recipe.*`（7 个键） | :1214-1224 | 唯一消费者是 `TombManager.recipeChallenge` :423（配方复刻掷骰），而该路径已不挂命令/界面（config.yml:1185 自述、README:602 同述）；残影挑战（TombChallenge）不读这些键 | 整段移入「已废弃」注释或删除；若想保留配方复刻做彩蛋，先挂回命令 | S | 低 |
| C4 | `anvil-forge.animation.roll-deg / windup-deg / start-height / hit-height` | config.yml 现版仅注释提及（:331-332）；但更糟的是 `migrateDisplayConfig` version<3 分支**仍会主动把这 4 个死键写进老配置**（AnvilForgeManager:474-485），version<5 再补【已废弃】注释（:561-566） | 迁移停止写入死键（version≥8 的迁移动作里顺手 remove），或至少 version<3 分支删掉这四行 set | S | 低 |
| C5 | `strengthen.caps.1..5`（带「仅存档兼容」注释） | :169-174 | **半死**：仅 `anvil-forge.enabled=false` 的遗留分支读取（StrengthenService.capForTier :187-193、ItemFactory.strengthenCapOf :357-362）；`warnMissingKeys` 仍列 caps.1/caps.5（:463-464） | config 注释已如实；warnMissingKeys 里这两条应删（关着铁砧强化的服主才会踩） | S | 低 |
| C6 | `strengthen.cost.*` / `strengthen.material.*` | :175-206 | **半死**：同 C5 只在遗留分支读取（StrengthenService:226-228、:810-812）；且 `migrateStrengthenCosts`（Plugin:248-272）仍在为它们做迁移落盘——迁移器维护一条多数服主永远走不到的曲线 | 保留但把迁移器与键一起标注「仅 anvil-forge.enabled=false 生效」，或迁移动作顺带关闭时才跑 | S | 低 |

### 3.2 默认值漂移（代码内置兜底 ≠ config.yml 发布值）

| # | 键 | 代码兜底（位置） | config.yml 值（位置） | 影响 | 工作量 | 风险 |
|---|---|---|---|---|---|---|
| C7 | `anvil-forge.strike-cooldown-ms` | 300（AnvilForgeManager:1257） | 450（:260） | 删键的服务会从 450 变 300ms，锤速变快 | S | 低 |
| C8 | `anvil-forge.break.max` | 0.35（StrengthenService:440） | 0.30（:277） | 删键服务 +99 碎率上限 30%→35% | S | 低 |
| C9 | `settings.heat-decay-per-tick` | 0.055（Settings.java:58） | 0.075（:11） | 删键服务降温变慢 27% | S | 低 |
| C10 | `settings.hammer-interval-ticks` | 3（Settings.java:61） | 2（:17） | 删键服务指针变慢 | S | 低 |
| C11 | `settings.folding-base-seconds` | 3（Settings.java:72） | 2.5（:42）——且该键用 `getInt` 读 **2.5 会被截成 2**，实际行为=2 ≠ 配置字面 2.5 ≠ 兜底 3，三方不一致 | 改 `getDouble` + 代码兜底对齐 2.5 | S | 中（叠钢时长直接变） |
| C12 | `settings.folding-seconds-per-step` | 0.9（Settings.java:73） | 0.7（:43） | 同上 | S | 低 |
| C13 | `tomb.challenge.spawn-radius` | 4（TombChallenge:973） | 3（:1192） | 残影生成半径漂移 | S | 低 |
| C14 | `tomb.challenge.leave-radius` | 24（TombChallenge:979） | 40（:1193） | 删键服务跑出 24 格就算离开，比配置严苛 | S | 低 |

### 3.3 代码读取但 config.yml 缺失的键（反向漂移）

| # | 键 | 代码位置与兜底 | 缺失影响 | 工作量 | 风险 |
|---|---|---|---|---|---|
| C15 | `strengthen.kindle.bonus` | Charm.java:124，兜底 0.15 | 旺火符加成只能靠代码默认，config.yml 无键可调 | S | 低 |
| C16 | `settings.stage-intro-ticks` | ForgeManager:392，兜底 14 | 开场演出时长不可配（README:125 却当可配项描述） | S | 低 |
| C17 | `reforge.permission` | ReforgeMenu.java:127，兜底 \"forgemaster.use\" | 重铸界面权限不可配 | S | 低 |

---

## 四、迁移层 version 键统一方案

| # | 现状 | 证据 | 方案 | 工作量 | 风险 |
|---|---|---|---|---|---|
| M1 | 四个迁移入口各自为政：①`migrateStrengthenCosts`（ForgingMasterPlugin:84,248-272）无版本键，靠「三键同时等于旧默认值」判定；②`migrateReforgeCosts`（:85,325-339）同法；③`migrateBalanceMultiplier`（:86,287-317）同法；④`AnvilForgeManager.migrateDisplayConfig`（:134,429-625）有自己的 `anvil-forge.display.pose-version`（:432，当前 7）。另有物品级版本 `ARMOR_REBALANCE_VERSION=1`（StrengthenService:1492，PDC 标记） | ①新建 `config/ConfigMigrations` 单类，config.yml 顶层统一 `config-version: N`；四个迁移各成一个有序 step（step1 strengthenCosts、step2 reforgeCosts、step3 balanceMultiplier、step4..10 吸收 pose-version 1..7 的全部动作），跑完一次性 `saveConfig()` 并把 config-version 写到最新。②对没有 config-version 的老盘：保留现有「值等于旧默认才改」的判定作为 step 守卫（幂等性已有），迁移成功后写入 config-version，此后一律以版本号为准，不再做值比对。③`pose-version` 保留为子命名空间版本亦可，但主版本必须先推进（迁移器内部把 pose-version 映射成 step 序号）。④物品级 PDC 版本（ARMOR_REBALANCE）是**每件物品的状态**，不属于 config-version，保持独立。⑤收益：修 C4（死键写入）时只需加一个 step，而不是再堆第 8 层 if | M | 中（老盘首启会多跑一遍值判定，必须保持现有 near() 判定语义逐字不变；pose-version<7 的盘映射错档会漏迁，需自检覆盖，见 T2） |

---

## 五、自检覆盖缺口

实测：SelfTestSuite 共 346 条 `add()` 断言。以下核查结果与任务假设的差异已标明。

| # | 对象 | 现状证据 | 需补断言要点 | 工作量 | 风险 |
|---|---|---|---|---|---|
| T1 | anvil 演出 headOffset 锤头几何 | **已覆盖**：SelfTestSuite:2583-2645 已断言 strikeFrame 逐帧角度单调、pivotOffset 模长恒等于柄长（:2620-2627）、砸中帧 `headOffset(hit,1.0)` 的 y 落在 0.75~1.45 区间（:2629-2632）、done 帧必现（:2636-2642）。任务假设的缺口不存在 | 可补两条加固：①`headOffset(0°)` y 分量 ≈ handleLength+0.5×scale（MODEL_HEAD_LEN 语义，AnvilForgeManager:803,812-814）；②拖尾用的 head 偏移在 impact 帧的 x 分量 ≠ 0（绕柄弧线有水平漂移，防回退成竖直下落） | S | 低 |
| T2 | migrateBalanceMultiplier | **未覆盖**（全文无 migrat/bonus-per-level/0.006 相关断言） | ①临时 config 写旧默认（`strengthen.bonus-per-level: 0.02`、`evolution.stages.1..6.bonus: 1.25/1.25/1.30/1.30/1.35/1.40`），跑迁移后断言 0.006 与 1.08/1.08/1.10/1.10/1.12/1.12（对照 ForgingMasterPlugin:291-292）；②已迁移值（0.006）再跑一遍必须 no-op 且不落盘告警；③服主改过的值（如 0.005、stage bonus 1.15）一个字不动（:300 的 untouched 判定） | M | 低 |
| T3 | capForQuality | **已覆盖**：默认阶梯（SelfTestSuite:2443-2450 神话=99）、全息读同一份 cap（:2673-2674）、ensureCap 单向同步与幂等（:2490-2505）、armor 场景（:2794,2986,3163,3188） | 可补一条：`anvil-forge.caps.COMMON=10` 配置覆盖后 capOf=10 且 level>10 的剑 strike 返回 REFUSED（覆盖 StrengthenService:130-135 的「可逐项覆盖」路径——现在只测了内置默认） | S | 低 |
| T4 | migrateDisplayConfig（pose-version 1→7） | **未覆盖**（全文无 pose-version/migrateDisplayConfig 断言；README:1860 称曾手测过 v2.16→v2 升级，但不在自检内） | 临时 config 写 pose-version:1 + 旧值（无 sword-transform、pitch 0、scale 0.70、height 1.02），跑迁移后断言：pose-version==7（AnvilForgeManager:619）、sword-transform=NONE、pitch=-90、scale=0.95、pivot-height=2.10、swing-start=-25、swing-hit=170、flame.* 四键存在（:532-541）、roll-deg 等 4 死键不再被写（或被 remove，对应 C4）；服主手调过的值（如 scale 1.1）保留 | M | 低 |

---

## 六、README.md 与实现的口径漂移（最误导前 20）

| # | README 位置 | 说法 | 实现真相（证据） | 危害 |
|---|---|---|---|---|
| R1 | :19 | 「铁 +20 起、星陨 +32」（v2.4 口径） | 现行上限按品质 40~99（StrengthenService.defaultQualityCap :138-150；config.yml:250-256） | 新服主照旧表配宣传，玩家按 +32 规划 |
| R2 | :47 | 「温度每秒自然下降约 1.1°」 | 发布配置 0.075/tick×20=1.5°/s（config.yml:11）；README:109 自己也写 1.5°/秒——同文档自相矛盾；1.1° 对应代码兜底 0.055（Settings.java:58） | 手感预期差 36% |
| R3 | :50 | 「边缘良好（0.62）」 | heatScore 在偏离=容差处为 1−0.22=0.78（ForgeManager:568-580） | 评分预期失真 |
| R4 | :125 | `stage-intro-ticks` 当可配项描述 | config.yml 无此键，代码兜底 14（ForgeManager:392，见 C16） | 服主在 config 里找不到键 |
| R5 | :272、:291 | 「strike-cooldown-ms 默认 300ms」 | 发布配置 450（config.yml:260）、代码兜底 300（AnvilForgeManager:1257），见 C7 | 调参参照错位 |
| R6 | :329-332 | 把 `roll-deg/windup-deg/start-height/hit-height` 当现行演出参数逐条讲解 | v2.17.2 起死键（config.yml:331-332 自标废弃；现行只读 swing-*，AnvilForgeManager:767-773） | 服主调这四个键毫无效果 |
| R7 | :339 | 迁移只写到「v2.17 推到 4」 | 实际代码已到 pose-version 7（AnvilForgeManager:433,619），漏 5/6/7 三轮（pivot-height、swing-* 两次改角、flame） | 老服主以为迁移没跑 |
| R8 | :355 | 「+20 附近撞八次必过」 | furnaceNeed(20)=5（need-mid，StrengthenService:843-851），8 次是 >20 档 | 保底预期差 |
| R9 | :583 | 「跑出 24 格持续 60 秒 → 回收」 | 发布配置 leave-radius:40（config.yml:1193）、代码兜底 24（TombChallenge:979），见 C14 | 与实机不符 |
| R10 | :686 | 「满强化（+20 ~ +32）值 1000~1600」 | 旧上限口径；现行 +40~+99，估值曲线随之不同 | 契约税额估算失真 |
| R11 | :1211 | 配置总表把 `strengthen success.*`、`fail.*`、`cost.*` 全列为活配置 | success.*、fail.break-* 是死键（见 C1/C2）；cost/material 仅遗留分支生效（C6） | 服主白调死键 |
| R12 | :1642 | 星陨「+32」强化表格 | 同 R1，现行神话 +99 | 数值表全错位 |
| R13 | :1825 | 蜕变 stage1「属性乘区 ×1.25」 | 现行 1.08（config.yml:355；migrateBalanceMultiplier 就是把 1.25 重定为 1.08 的，ForgingMasterPlugin:291-292,308） | 按旧乘区算战力 |
| R14 | :1829、:1860 | 「自检到 328/329 项」 | 当前 346 项 | 覆盖率口径过期 |
| R15 | :1915-1932 | 整段以现行口吻描述 v2.16.3「竖直下落」演出（`StrikeFrame(startY...)`、hit-height、roll-deg:180） | 已被「柄底不动、锤头绕柄旋转」取代（AnvilForgeManager:835-928 javadoc 明说「不再整体上下平移」） | 看了完全学不到现行演出模型 |
| R16 | :1950 | 「自检对应帧序列断言：下落段严格递减、砸中帧恰好等于 hit-height」 | 现行自检断言的是角度单调与 headOffset 几何（SelfTestSuite:2583-2645），不存在 hit-height 断言 | 维护者按错误口径改测试 |
| R17 | :2118 | 「strengthen.caps 20/22/25/28/32 全部落盘」当作生成配置核对项 | 该阶梯仅在 anvil-forge.enabled=false 时生效（C5）；现行是 anvil-forge.caps 六档 | 核对清单误导 |
| R18 | :152/:294 | 「hammer 默认发铁斧」与「item: MACE」两个说法并存 | config 实况：`hammer.item: MACE`（:263）+ `items: [IRON_AXE, MACE, ...]` 第一项 IRON_AXE（:266）；`/zhujian hammer` 发 items 第一项即铁斧（Hammer.java:99）。两处说法都对但未说明主次 | 小混淆，建议合并说明 |
| R19 | :47（同一行后半） | 「超过 112° 加热分 ×0.35」 | score×0.35 确认存在（ForgeManager:1054），但 112° 这个阈值在 ForgeManager 未检索到对应常量，疑为旧版本数字（本次未逐行核完加热全链，标注为待核） | 待核 |
| R20 | :1860 | 「升级路径也真跑过：改回 v2.16 老值…写 pose-version: 2」 | 现行代码直接迁到 7，中间态描述已不可能复现（会一路推到 7），自检亦无此用例（见 T4） | 升级验证方法过期 |

---

## Top10 重构优先级

1. **C11** `settings.folding-base-seconds` 类型缺陷：getInt 读 2.5 截成 2，行为与配置字面不符（唯一真正的行为 bug，先修）。
2. **T2** 补 migrateBalanceMultiplier 自检（v2.17.3 平衡迁移零断言，回归即裸奔）。
3. **C1+C2+C11 组** 清理死键 strengthen.success/fail.break-* 并同步 warnMissingKeys（消除对服主的主动误导）。
4. **C4** migrateDisplayConfig 停止写入 4 个死键（迁移器在制造垃圾配置）。
5. **M1** 统一 config-version 迁移层（四个入口合并，之后每次调参只加 step）。
6. **C7/C8/C9/C14** 四处默认值漂移对齐（删键场景的行为可预测性）。
7. **D1+D2** chanceColor 与明牌行格式合并（改动最小、全息/聊天一致性收益立现）。
8. **S6+S3** AnvilForgeManager / StrengthenService 拆分（v2.16-2.17 最活跃的两条线，Holo/Animation/StrikeMath 边界最清晰）。
9. **R15-R17** README 演出与自检章节按现行实现重写（文档误导面最大的一段）。
10. **D5+D12** PDC 反查三合一 + 品质颜色统一走 Quality.color()（消灭 Reforge 的 static plugin 缓存隐患）。

【审计完成，共 62 条】（S1-S8 拆分 8 条、D1-D12 重复 12 条、C1-C17 配置 17 条、M1 迁移 1 条、T1-T4 自检 4 条、R1-R20 README 20 条）
