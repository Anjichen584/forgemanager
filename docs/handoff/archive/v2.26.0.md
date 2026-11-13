# v2.26.0 交付报告（给失忆的自己）

> 上一轮：v2.25.9（自检 528 项，压测 32 并发 15 阶段 318/0/0）

## 0. 一分钟状态

| 项 | 值 |
| --- | --- |
| 版本 | **2.26.0** |
| 产物 | `target/ForgingMaster-2.26.0.jar` |
| `config-version` | **23**（step32 补 `contest.history-keep`） |
| 自检 | **547 项，失败 0**（下限 = **547**，只许增不许减） |
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
mvn -B -q clean package            → exit 0，jar 1,706,892 B
_run_selftest_full.py              → 547 项 / 通过 547 / 失败 0
run-stress.py --key（32 bot）       → PASS 203 / WARN 0 / FAIL 0，exit 0
                                     _stress-20261113-160019.json
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

| # | 事 | 位置 | 为什么 |
| --- | --- | --- | --- |
| 1 | `bounty-data.yml` 是唯一「有 reload 无 flush」的项 | `BackupManager.flushAll` | 领赏/换日同步写，目前没有脏窗口；但这种「唯独它特殊」的项值得一句注释，否则下个人不知道该不该补 |
| 2 | `HallShrine.origin` 是字段缓存 | `HallShrine:68` | `hall.yml` 在清单里但 flush/reload 都是 0。展示位置在回档后到重启前是旧值。低危，但同 §3 第 2 条同理 |
| 3 | `metrics-export-<stamp>.csv` 不在备份 | `MetricsCsv:150` | 导出物，原则上可不备，但没有明文说明 —— 下次有人问「回档后导出的 CSV 呢」会翻半天 |

顺带值得做：`papi-cache/`（纯缓存，不必备但也没说明）；
`CrossPlay` 那类「跨模块装饰器」如果再加，注意自检的方法体切片是按单文件做的。

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
- [ ] 自检项数 ≥ **547**
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
- 文档：`docs/history/v2.26.x.md`（新建）、本文件、`README.md`（标题 + 索引行）、`AGENTS.md`（547 / 索引）