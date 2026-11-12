# v2.25.9 交付报告（给失忆的自己）

> 上一轮：v2.25.8（`63cece1` + `15e90a8`，525 项，32 并发 252/0/0）

## 0. 一分钟状态

| 项 | 值 |
| --- | --- |
| 版本 | **2.25.9** |
| 产物 | `target/ForgingMaster-2.25.9.jar` |
| `config-version` | 20（本轮没动配置结构） |
| 自检 | **528 项，失败 0**（下限 = **528**，只许增不许减） |
| 压测 | **32 并发 15 阶段 / PASS 318 / WARN 0 / FAIL 0**（`botCount` 已核对 = 32） |
| README | 394 行 / 37.3 KB（上限 450 行 / 48 KB） |
| 测试服 | `max-players=48` |

## 1. 本轮改了什么

### 1.1 行会金库：同一文件两个钱口两种口径（真 P1）

`guild/GuildService.java` 两个玩家掏钱入口：

| 入口 | 原实现 |
| --- | --- |
| `deposit`（存金库） | `stats().takeCoins(...)` ← 直连 |
| `donate`（捐基金） | `wallet().take(...)`，注释写着「禁止直连 stats().takeCoins」 |

`economy.coin-sinks=server` 时：存金库绕过 Vault、捐基金走 Vault = **账本分裂**。
修法：`deposit` 对齐 `donate` 走 `wallet().take`。

### 1.2 金库存钱：扣了币、落盘失败就吞了（真 P1）

原路径 `扣币 → g.treasury += amount → save()`，`save()` 是 `void` 写失败只 warning。
玩家钱扣掉了、金库存量只在内存，重启归零。

修法照 `TombManager`：加 `boolean saveChecked()`（`save()` 委托它）；`deposit` 写失败就
`g.treasury -= amount` + 退回剑币 + SEVERE + 提示。
`guild/GuildService.java:1222-1300`（saveChecked）、`deposit` 段。

### 1.3 签到落盘：`save()` 早就返回 boolean，就是没人看（真 P1）

`DailyStore.save()` 从 v2.25.7 起返回 `boolean`，但签到写的是 `store.save();`。
写失败 → 「今天已签」只在内存 → 重启能再签 = 重复发奖。
现在判返回值，失败就退出发放（此时一件没发，回滚干净）。
`blackmarket/DailyRewardService.java:174`

### 1.4 查过但**不改**（记录结论，别再重查）

- `contract/ContractManager.java:435` 断约罚金忽略 `takeCoins` 返回值 —— 单向扣款、不涉及发货，
  扣不到只影响"罚没罚上"，不影响守恒 → **P2，不动**。
- `ForgingCommand.java:3362` 已是 `if (!stats().takeCoins(...))` → 本来就判了。

### 1.5 压测 12 → 15 阶段

新增 `spiritReroll` / `contract` / `pass`，每个只写守恒断言。

**三个阶段的第一次运行全报了 WARN（96 条），全是我的测试装置写错，不是插件问题**：

| 阶段 | 假 WARN 原因 | 真实发现 |
| --- | --- | --- |
| `spiritReroll` | 重抽要求灵性满档（1000），测试剑是 0 | 改成断言「闸门拦住且不扣费」—— 这比跑通更有价值 |
| `contract` | 正则漏了实际文案「契约已签。」 | **契约其实签上了**，是我的正则不对 |
| `pass` | 用 `String(win.title)` 拿到 `[object Object]` | 标题是文本组件，要 `JSON.stringify` |

改完后 `contract` 断言「重复 join 不重复收钱」、`spiritReroll` 断言「被拦住不扣费」、
`pass` 断言窗口真开出来并把真实标题写进 detail。

**`pass` 差点写成假测试**：`/zhujian pass` 只做 `seasonPass(sender)`，**不解析参数**，
根本没有 `pass claim` 命令。写成 `burst('/zhujian pass claim' × 3)` 会得到「0 次领奖 → PASS」。
这种阶段比不跑更坏。现在注释里写清了「领奖是 GUI 点击，聊天命令驱动不了」。

### 1.6 新增 3 条自检钉子（525 → 528）

| 钉子 | 护的是什么 |
| --- | --- |
| 行会金库扣款必须与基金同口径走 Wallet | 扫 `GuildService` 非注释行，不许再有 `stats().takeCoins`；并且**凡是调 `wallet().take` 的方法，同一方法里必须判 `saveChecked()`** |
| 签到进度落盘必须判返回值 | 扫源码，必须有 `if (!store.save())` 与 `restoreSignin(` |
| 签到回滚语义 | `restoreSignin` 能把进度退回旧值；原本没记录的整条删掉 |

### 1.7 收尾复检：本轮自己引入的三条 + 一个压测脚本坑

跑门之前先通读了本轮 diff，抓出三条**同族**缺陷（钱扣了 / 账没落 / 状态只在内存），
两条是本轮引入的：

| # | 缺陷 | 后果 |
| --- | --- | --- |
| 1 | `saveChecked()` 写盘成功后 `return !race` | 竞态时返回 false → 存金库倒扣 treasury + 退款，而盘上已记这笔钱 → 重启后金库凭空多钱 |
| 2 | 签到 `save()` 失败只 `return`，不回滚内存 | 之后任一次 save 会把「已签但没发奖」写进盘 → 玩家这天奖励没了、也领不了 |
| 3 | `fund donate` 没判落盘成败 | 与 deposit 同族：扣钱后写盘失败 = 吞钱（本轮只修了 deposit） |

压测里那两条固定假 WARN 也在这轮定位：`dropout` 阶段故意踢掉 Conc04，后面
`spiritReroll` / `contract` 仍把它算进断言。修法：阶段循环只喂「活着且没掉线」的 bot，
掉线靠 `bot.on('end')` 登记 —— `quit()` 后 mineflayer 不清空 `bot.entity`，光看 entity 会误判。

## 2. 下一轮先做这 3 条

| # | 事 | 位置 |
| --- | --- | --- |
| 1 | `spiritReroll` 只测到"闸门拦住"，真正的重抽竞态（nonce 复用 / 扣费时序）还没覆盖 —— 需要先造一把灵性 1000 的剑（`spirit feed` 或 GM 手段） | `tools/guitest/concurrent4.js` |
| 2 | 赛季通行证领奖只能 GUI 点击驱动，聊天命令进不去 → 阶段只覆盖"面板并发" | `pass/PassGui.java` |
| 3 | `contract/ContractManager.java:435` 罚金忽略返回值（P2，攒着一起处理） | `contract/ContractManager.java` |

**已确认不用再审**：命令 `args[n]` 越界、holder 判定顺序、12 个 Router 的 `reset()`、
`ADMIN_ONLY` 逐条判权、碎剑留档可挑战（设计如此）。

## 3. 不能重犯的坑

1. **锻造系手续费永远是剑币**（`stats().takeCoins`）：强化/进化/铭刻/词条剥离。曾误判成「漏改 Wallet」，改完又回滚。
   但**行会收钱**是反例：它跟基金同属 Wallet 侧，必须走 `wallet().take`。
2. **静态检查类钉子会假绿也会假红**：路径推导错 = 永远跳过；命中 `ForgingMaster/` 里 2.10.6 的旧源码树 = 假红。
   扫源码的钉子只扫**非注释行**，且失败要报出命中数。
3. **并发压测默认 32**（`BOT_COUNT` 可改）。**跑完必须核对 `botCount`** —— 被 clamp 静默截断过一次。
4. **加功能前先搜命令面**（`SUBCOMMANDS` + `helpLines()`）。差点加 `top rep`，而 `rank reputation` 早就有了。
5. **新存档一律走 `YamlIo`**，裸 `yml.save(file)` 自检判红。
6. **命令三处同源**：switch 分派 + `SUBCOMMANDS` + `helpLines()`。
7. **README 预算 450 行 / 48 KB**，超了 `agentdocs.py check` 报错、pre-commit 拒绝提交。
8. **写自检钉子用真实日期**：`DailyStore.save()` 会剪掉非今天/昨天的日期层，假日期得到假红。
9. **`save()` 返回 void 的路径都要怀疑**：钱货时序拿不到成败就会「货交了、盘没写」。要成败就加 `saveChecked()`。
10. **改版本号要在启动压测之前**，否则压测跑的是旧命名 jar（源码同但名字不同，交付说明会说不清）。
11. **新写的压测阶段，先确认那个命令真的有**：`/zhujian pass` 不解析参数，`pass claim` 不存在。
    断言 0 次也算 PASS 的阶段等于假绿。
12. **`win.title` 是文本组件**：用 `JSON.stringify()`，别用 `String()`（会得到 `[object Object]`）。

## 4. 命令速查

```powershell
# 构建
& 'D:\apache-maven-3.8.8\bin\mvn.cmd' -B -q clean package

# 自检（约 40 秒；先停服！）
cd D:\IDEA\mc-plugins\ForgingMaster\testserver
& 'C:\Users\heiya\AppData\Local\Programs\Python\Python312\python.exe' _run_selftest_full.py
# 输出会截断，用这个数项：
([regex]::Matches((Get-Content _full-selftest.txt -Raw),'✓')).Count

# 压测（32 并发 15 阶段，约 22 分钟；先停服！）
cd D:\IDEA\mc-plugins\ForgingMaster-Lore
& 'C:\Users\heiya\AppData\Local\Programs\Python\Python312\python.exe' tools\guitest\run-stress.py

# 只跑某几个阶段
node tools\guitest\concurrent4.js contract pass spiritReroll

# 连接池逻辑自检（不连服务器，10 项）
node tools\guitest\connpool.js

# 文档门
python tools\agentdocs.py check
```

## 5. 60 秒自查

- [ ] `pom.xml` 版本 = README 首行 = 部署的 jar 名（**先改版本号再 build 再压测**）
- [ ] 自检项数 ≥ 528
- [ ] 压测报告 `botCount=32`、`FAIL 0`、`WARN 0`（假 WARN 已修，不许再拿「脚本问题」当解释）
- [ ] README ≤ 450 行 / 48 KB
- [ ] 自检与压测**串行**跑
- [ ] 提交推送了

## 6. 交付物

- 代码：`guild/GuildService.java`、`blackmarket/DailyRewardService.java`、`blackmarket/DailyStore.java`、`selftest/SelfTestSuite.java`
- 工具：`tools/guitest/concurrent4.js`（+3 阶段 + 掉线 bot 不再参与断言）
- 文档：`docs/history/v2.25.x.md`（补二十）、本文件、`README.md`（标题 + 索引行）、`AGENTS.md`