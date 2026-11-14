# v2.26.1 交付报告（给失忆的自己）

> 上一轮：v2.25.9（自检 528 项，压测 32 并发 15 阶段 318/0/0）

## 0. 一分钟状态

| 项 | 值 |
| --- | --- |
| 版本 | **2.26.1** |
| 产物 | `target/ForgingMaster-2.26.1.jar`（1,692,974 B） |
| `config-version` | **23**（step32 补 `contest.history-keep`） |
| 自检 | **539 项，失败 0**（下限 = **539**；550→539 是删两个模块连带删钉，见 §0） |
| 压测 | 见下方 §1.6（本轮 32 并发实跑数字） |
| README | 397 行 / 39.7 KB（上限 450 行 / 48 KB） |
| `SAVE_FILES` | **22 份**（+ contest-history / power-log / hall-legacy）+ `metrics-*.yml` 散文件 + `seasons/` |
| 测试服 | `max-players=48` |

## 1. 本轮改了什么

### 1.1 四个新功能（都是「已有的数，终于看得见」）

| 功能 | 入口 | 备份 |
| --- | --- | --- |
| 战力曲线 | `/zhujian power log` | `power-log.yml`（每人 12 条，10 分钟去重窗口） |
| 战力对比 | `/zhujian power compare <玩家>` | 纯只读，不落地 |
| 历届赛果 | `/zhujian contest history [届次号]` | `contest-history.yml`（保留 N 届） |
| 比试排行 | `rank duel` 第六维 | `Stats.duelWins/duelLosses` |
| 公开档案 | `/zhujian stats of <玩家>` | 纯只读，白名单字段 |

### 1.2 审计轮：另一个模型说「没问题」，实际六个缺陷

**背景**：用户先让 glm5.3flash 审了好几遍、得到「没问题了」的结论，再要求独立复核。
复核结果不成立 —— 除了这六条，连**两道门都没在当前代码上跑过**：
`concurrent4-report.json` 是 2 bot 跑法（PASS 52 / WARN 4），四个阶段因人数不足直接跳过，
`duelRank` 的核心链路（发起→拒绝→应战→退服清锁）**一行都没真跑**。

| # | 缺陷 | 位置 | 修法 |
| --- | --- | --- | --- |
| 1 | `stats of` 会给任意名字建空档案 | `ForgingCommand.statsOfPublic` | 改 `peek()` + 判空 + 空档案友好回执 |
| 2 | `contest.history-keep` 只活在 Java 默认值里 | `config.yml` / `Settings` / 迁移 | 补内置键 + 进 `REQUIRED_CONFIG_KEYS` + step32 + `ContestManager.reloadHistory()` |
| 3 | 两份新存档漏在备份清单外 | `BackupManager` | 清单 + `flushAll` + `reloadAllSilently` 三处 |
| 4 | `power log` 打印 `FINE` / `iron` | `ForgingCommand.powerLog` | 展示层翻 `Quality.display()` / `ForgeMaterial.display()`，盘上仍存稳定键 |
| 5 | 去重分支「回写失败仍报成功」 | `PowerLogStore.append` / `ContestHistoryStore.record` | 各自加 `dirty` 标记：脏则这次补写并如实返回 |
| 6 | help 写死「514 项 / 18 份存档」 | `ForgingCommand.helpLines` | 项数不报数；份数取 `SAVE_FILES.length` |

另外顺手修的：`Metric.parse` 挤行、`contest history` 不再被 `contest.enabled` 连坐（只读档案不该被功能开关关掉）、
`ContestHistoryStore.record` 的 `old.code` 空值 NPE 防御。

### 1.3 修 #3 的坑：只往 SAVE_FILES 加一行是错的

`restore()` 的顺序是「flush 脏数据 → 前置备份 → 覆盖文件 → **只重载不保存**」。
加了清单却不补 `reloadAllSilently`，回档后内存里还是新赛果，下一次 `record()` 整份回写
就把回档静默吞掉 —— v2.23.1 那个 P0 的同型。

**自检也跟着改了写法**：新钉子按**方法体**分别核「这两个文件名在 flushAll / reloadAllSilently 里各出现一次」，
而不是数整文件出现次数 —— 清单 + 两处 swallow 凑够 3 次，但漏掉 reload 那条同样能凑够 3 次，那样就是假绿。

### 1.4 修 #2 的坑：`cfg.contains` 会顺着 defaults 撒谎

`step32ContestHistory` 必须用 `existsOnDisk()`。`contains` 对 jar 内已有的键恒为 true，
补键逻辑就成了恒 no-op 死代码（step29 已为同一个坑写过注释）。
`config-version` 22 → 23；同时给 `ContestManager` 加 `reloadHistory()`，
让 `reloadAll()`（走 `shutdown()` → `start()`）真正重建实例 —— 否则 `keep` 是构造期烘死的 final，改完永不生效。

### 1.5 赛季归档的口径声明（DUEL 暴露的老问题）

`Metric` 新增 `career()`：`LEVEL / STRENGTHEN / DUEL` 是生涯累计（`resetSeasonal` 刻意不清），
其余三维是本赛季。`SeasonManager.end()` 多写 `calibers.<维度>.career` + `calibers-note`。
**没有**改 `resetSeasonal` —— 清生涯战绩是数据事故。放在独立 `calibers` 段而不是 `rankings.<维度>` 下，
是为了不污染「那一层的键 = 名次」的结构。

### 1.6 两道门（本轮实跑）

```
mvn -B -q clean package            → exit 0，jar 1,692,974 B
_run_selftest_full.py              → 539 项 / 通过 539 / 失败 0
run-stress.py --key（32 bot）       → PASS 203 / WARN 0 / FAIL 0，exit 0
                                     _stress-20261114-142643.json（删两个模块后实跑）
                                     另一次 _stress-20261113-160019.json 同为 203/0/0
                                     botCount=32、botCountAlive=32、skipped=0、timedOut=false
                                     5 个关键阶段，约 12 分钟
run-stress.py（全量 17 阶段）        → 上一次全量 PASS 667 / WARN 1 / FAIL 0（_stress-20261113-142216.json，33 分钟）
```

唯一 WARN 是 `duelRank` 的「真实结算降级」：结算要双方在锻造小游戏（炉温/敲击/开刃实时节奏）
里各出一把分，装置无法稳定盲驱；`stats` 也没有 duel 的 give/take 管理命令（只有 rep），
所以不注入胜负，只做「榜 ↔ 档案」一致性对账。**那是装置如实声明能力边界，不是缺陷**。

`--key` 只跑 5 个守恒律阶段（auction / bid / burst / spiritReroll / contract），约 12 分钟，
用于「改完代码快速确认没把核心并发面改坏」。**它不是通过标准的替代品，发版前请跑全量。**

压测铁律不变：**`botCount` = 32，且 `botCountAlive` 也要 = 32**。
`botCount` 只是「打算跑几个」（`joinWithRetry` 失败会少 push，它照样等于设定值），
`botCountAlive` 才是真正连上的数量 —— 这个字段是本轮新增的，就为了让「32 设了、28 上了」可被发现。

### 1.7 用户在最后关头砍掉两个功能：极限锻造 + 剑身环绕

原话：「极限锻造是什么鬼，你加的吗，不管谁加的去掉然后还有个剑身环绕又是谁写的动画，我服了，都不要」。

**两个都不是本轮加的**，都来自 `5c9b40e`「v2.23.1: 全面查错 + 数值收敛」。查证方式：
`git log -S "RiskMode"` / `git log -S "SpiritDisplayService"`。这一条留个记号 ——
**功能被塞进一个标题写着「查错 + 收敛」的提交里，提交信息一字未提**，事后只能靠 `-S` 反推。

| 删掉的 | 用户看到的东西 | 整文件删 |
| --- | --- | --- |
| **极限锻造** | `/zhujian forge risk`：命中区缩窄、分数 +35%、每阶段 6% 炸剑 | `forge/RiskMode.java` |
| **剑身环绕** | `/zhujian spirit show\|hide`：主手剑在肩侧绕圈飞的浮游体，+2% 暴击 | `spiritdisplay/SpiritDisplayService.java`、`SelfTestSpiritDisplay.java` |

连带必须一起删的（这才是容易漏的）：

1. **统计链路串删** —— `riskMade` / `riskLost` 从 `StatsStore` → `MetricsData` → `MetricsSample` →
   `MetricsHistory` → `MetricsCsv` → `MetricsGui` → `MetricsService`。其中 `breaks`（碎剑率）
   **唯一数据源就是极限锻造炸剑**，功能没了它永远 0 —— 留着就是「永远显示 0%、像没人碎过剑」的
   假绿，所以整条删。**注意 `StrengthenService` 里也有叫 `breaks` 的局部变量，那是强化概率数学，不能动。**
2. **称号维度** —— `SpectrumService` / `TitleService` 的 `case "risk-made"` / `"risk-lost"`，
   以及 `config.yml` 里 `risk-taker` 称号（`condition: { type: stat, key: risk-made, min: 100 }`）。
   留着则该称号**永远解锁不了**，且条件指向不存在的 stat key。
3. **必需键清单** —— `REQUIRED_CONFIG_KEYS` 的 3 个 `risk.*`。**这条是自检逮出来的**：
   源码和清单都删干净了，但部署的是旧 jar，自检报「内置 config.yml 缺 3 个必需键」。
   重新 `mvn package` + 重新部署后消失 —— 自检读的是**打进 jar 的那份 config.yml**，不是源码目录。
4. **PDC 键** —— `Keys.SPIRIT_DISPLAY` / `SPIRIT_DISPLAY_BONUS`：删声明之后还得删 `onEnable` 里的
   `= new NamespacedKey(...)` 初始化，否则编译红；再检查 javadoc 有没有 `{@link #SPIRIT_DISPLAY}` 悬挂
   （`BIG_SWORD_DISPLAY` 注释里正好有一句）。
5. **一条会变成假绿的钉子** —— 原断言「剑体放大实体不能误打成剑灵标记」，键删了以后它恒真。
   **恒真的钉子等于没有钉子**，改写为断言它真正想守的：实体必须带自己的键且类型正确。

数字变化：自检 **550 → 539**（11 条钉子只测这两个模块，随被测对象一起删）。
与 `AGENTS.md` 第 6 条「只许增不许减」冲突 —— 已在规则下面写例外说明，**不能悄悄改数字**。
jar 1,713,949 B → 1,692,974 B（-21 KB）。
### 压测连红两次，两次都不是插件缺陷

这一轮压测跑了三次才绿，前两次都报「剑灵重抽」扣费不守恒：

| 报错 | 差额 | 真相 |
| --- | --- | --- |
| `每日限拦截却扣了钱 — 197660 → 158128` | 39532 | 服务端日志 12:15:31：「巨龙的怒吼撕裂了夜空—— Conc24 交不上税，契约粉碎…剑币少了 39532」，**同一秒对 31 个 bot 全扣** |
| `重抽扣费不守恒 — 应扣 2000，实扣 41932` | 41932 | 41932 = 2000（重抽）+ 39932（龙税），同上 |

根因：`ContractManager.isTributeDue()` → `windowOpen()`，**按游戏内昼夜**判断「今晚该交税」。
压测跑 33 分钟必定跨过结算时刻，全服龙税同时落到每个已签契约的 bot 头上，
而阶段里的余额断言把它算成了自己的账。

修法两层：断言只对「自己触发的那一笔」负责（差额精确到单价才判 FAIL，否则点名差额降 WARN）；
治根靠 `gamerule doDaylightCycle false` + `time set day` 把世界钉在白天，交税窗口永不打开。

## 2. 下一轮先做这 3 条

> v2.26.0 收尾时这里原本列了三条「低危待办」。**后来逐条查证，结论是三样东西**：
> 一条是真缺口（会静默吞掉回档）、一条是显示问题、一条根本不该做。
> 前两条已修并加了自检钉子（548 项里的「回档链路不留唯独它特殊的例外」），
> 第三条改为在下面写清楚「为什么不修」。教训是：**「低危待办」这个词同时承担了
> 「必须修 / 可以修 / 别修」三种含义，下一个人分不出来** —— 清单要么写结论，要么别列。

**已修（v2.26.0 审计轮补）**
- `BountyManager` 补 `public void flush()`，接进 `BackupManager.flushAll`。
  原来它是全仓唯一「有 reload 无 flush」的项：回档换文件后内存还是旧 `claimed`，
  下次 `claim()` 的 `save()` 整份回写，**回档被静默吞掉**。同 v2.23.1 / v2.25 那个形状。
  顺带把 `save()` 的异常面扩到 `RuntimeException`。
- `HallShrine` 补 `public void reloadOrigin()`，接进 `reloadAllSilently`。
  `origin` 是字段缓存，回档换了 `hall.yml` 它不知道，原点停在旧坐标直到重启。
  注意 `hall.yml` **只补 reload 不补 flush**：两次写都是变更即写，没有脏窗口
  （钉子会把「为了整齐去加 flush」拦下来）。

**不该修（已查证，别再翻）**
- `metrics-export-<stamp>.csv`：用户主动导出的产物，源数据 `metrics-<日期>.yml` 已备份，
  回档后重导即可。把历史 CSV 也一起还原反而奇怪 —— 它不是世界状态。
- `papi-cache/`：运行时 javac 编译 PAPI 扩展类的缓存目录，进程启动即重建，本质是临时产物。
- 两者塞进 `SAVE_FILES` 的实际坏处：备份被无上限历史 CSV 撑大 + 覆盖一个马上重编译的缓存目录。

**下一轮真正该做的**
| # | 事 | 位置 | 为什么 |
| --- | --- | --- | --- |
| 1 | `duelRank` 对 31 个 bot 各跑一遍，但每轮只用 2 个 | `concurrent4.js` | 单阶段占 17.7 分钟（全量 33 分钟的一半），29 次是重复验证同一件事 |
| 2 | 阶段内并发不可行，已被实测否掉 | 见 `concurrent4.js` 注释 | Minecraft RCON 串行收发：并发 8 条 `list` 只回 1 条。别再试 |
| 3 | `CrossPlay` 那类跨模块装饰器若再加 | `SelfTestSuite` | 自检的方法体切片是按单文件做的，跨文件拼接会漏 |
| 4 | **同类不可逆命令还有 4 处一步到位** | `DissolveService` / `TombManager` / `ContractManager` / `AnvilForgeManager` | 悬赏改两段式之后，剩下 4 个 `setItemInMainHand(null)` 仍然是一键入魂：分解、埋冢、契约入贡、放上铁砧。前三个语义上是玩家主动「献出」，误触概率低于悬赏（悬赏只是主手达标），所以本轮没动 —— **但这是同一个形状，要改就一起改** |

## 3. 不能重犯的坑

1. **「另一个模型说没问题」不是证据**。这次 glm5.3flash 审了几遍，六条真实缺陷一条都没看见，
   而且两道门都没在当前代码上跑过（旧报告是 2 bot）。**先看报告里的 `botCount` / 时间戳**。
2. **只往 `SAVE_FILES` 加一行是错的**：回档靠 `reloadAllSilently` 按盘重建内存态，
   缺这一步旧副本会把回档结果盖回去（v2.23.1 的 P0 同型）。
3. **`cfg.contains()` 对 jar 内已有的键恒 true**：补键迁移一律用 `existsOnDisk()`。
4. **惰性单例 + 构造期读配置 = 改了永不生效**：要让它随 `reloadAll` 生效，
   必须在 `shutdown()` 里置空（且要在 `finish()` 之后，否则丢当届档案）。
5. **去重分支不许裸 `return true`**：返回值的语义是「盘上有没有」，不是「内存里有没有」。
   上一笔写盘失败过时，这一笔必须补写并如实返回 —— 否则就是「假绿」。
6. **help 不许写死会漂的数字**：项数、存档份数、维度数这类从常量取或干脆不报。
7. **静态检查类钉子要按方法体核，不要数整文件出现次数**：写 3 处和写 2 处的区别会被凑数掩盖。
8. 其余照旧：锻造系手续费永远 `stats().takeCoins`；行会收钱走 `wallet().take`；
   新存档一律 `YamlIo`；命令三处同源；README ≤ 450 行 / 48 KB。
9. **改版本号要在启动压测之前**（否则跑的是旧命名 jar）。
10. **自检与压测必须串行**，且先停服。
11. **写钉子的人必须自己跑一遍。** 这轮我加了一条「存档清单无遗漏」的双向钉子，
    没跑就交了 —— 它扫出 25 个写入点，其中 3 个自检临时文件没进白名单，**一开始就是红的**。
12. **跑绿了不等于在守。** 这轮 5 个子智能体审出 8 条，其中三条（扫描器看不见常量拼路径、
    没有 `CURRENT_VERSION >= 23` 断言、存档守卫表三行文件名写错）在**全绿状态下一点声都不出**。
    判断一条钉子有没有用，唯一方法是**变异测试**：把被测代码改坏，看它红不红。
13. **假红和假绿一样坏。** 行尾注释 `addHelp(...);  // 原来写死 514 项` 会让「不许出现 514」当场变红，
    而代码是对的 —— 这类假红会让人去改钉子而不是改代码。`nonCommentSource` 现在剥行尾注释。
14. **`==1` 是危险的断言形状。** 它会把「更好的写法」判红（把 `SAVE_FILES.length` 提成局部变量）、
    也会被「加一行日志」判红。守「有没有做」用 `>=1`，守「没做错」用段内 `.save()` 计数为 0。

## 4. 命令速查

```powershell
# 构建
& 'D:\apache-maven-3.8.8\bin\mvn.cmd' -B -q clean package

# 自检（约 20 秒；先停服！）
cd D:\IDEA\mc-plugins\ForgingMaster\testserver
& 'C:\Users\heiya\AppData\Local\Programs\Python\Python312\python.exe' _run_selftest_full.py
# 项数/失败数从这里读（RCON 回包会被截断，_full-selftest.txt 才是全文）：
Select-String -Path _full-selftest.txt -Pattern '自检完成|✗'

# 压测（32 并发，约 17 分钟；先停服！）
cd D:\IDEA\mc-plugins\ForgingMaster-Lore
& 'C:\Users\heiya\AppData\Local\Programs\Python\Python312\python.exe' tools\guitest\run-stress.py

# 只跑某几个阶段
node tools\guitest\concurrent4.js contract pass spiritReroll duelRank

# 连接池逻辑自检（不连服务器，10 项）
node tools\guitest\connpool.js

# 文档门
python tools\agentdocs.py check
```

## 5. 60 秒自查

- [ ] `pom.xml` 版本 = README 首行 = 部署的 jar 名（**先改版本号再 build 再压测**）
- [ ] 自检项数 ≥ **539**
- [ ] 压测报告 `botCount=32` **且 `botCountAlive=32`**、`FAIL 0`（WARN 也应为 0；有人数不足跳过的阶段等于没测）
- [ ] README ≤ 450 行 / 48 KB
- [ ] `config-version` = 23，且 `step32` 在 `run()` 里被调用
- [ ] `SAVE_FILES` **22 份**，新加的三份在 `flushAll` / `reloadAllSilently` 都有对应条目
      （`hall-legacy.yml` 例外：无内存缓存，只补清单即可）
- [ ] 自检与压测**串行**跑
- [ ] 提交推送了

## 6. 交付物

- 新增：`score/PowerLogStore.java`、`contests/ContestHistoryStore.java`
- 改动：`ForgingCommand.java`、`ContestManager.java`、`Metric.java`、`RankMenu.java`、`RankListener.java`、
  `SeasonManager.java`、`BackupManager.java`、`ConfigMigrations.java`、`ForgingMasterPlugin.java`、
  `SelfTestSuite.java`、`config.yml`
- 工具：`tools/guitest/concurrent4.js`（`powerCompare` / `duelRank` 两阶段 + 掉线 bot 处理）
- 文档：`docs/history/v2.26.x.md`（新建）、本文件、`README.md`（标题 + 索引行）、`AGENTS.md`（539 / 索引）