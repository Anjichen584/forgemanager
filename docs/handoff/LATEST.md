# v2.25.8 交付报告（给失忆的自己）

> 上一轮：v2.25.7（`adaf50f`，522 项，32 并发 252/0/0）

## 0. 一分钟状态

| 项 | 值 |
| --- | --- |
| 版本 | **2.25.8** |
| 产物 | `target/ForgingMaster-2.25.8.jar` |
| `config-version` | 20（本轮没动配置结构） |
| 自检 | **525 项，失败 0**（下限 = **525**，只许增不许减） |
| 压测 | **32 并发** 12 阶段 **PASS 252 / WARN 0 / FAIL 0**（`botCount` 已核对 = 32，0 次节流） |
| 压测跑的 jar | `ForgingMaster-2.25.7.jar` —— 版本号是在压测启动后才改的，**源码零差异**，只有 `<version>` 不同。交付的 `2.25.8.jar` 另外补跑了一遍自检（525/0） |
| README | 394 行 / 37.3 KB（上限 450 行 / 48 KB） |
| 测试服 | `max-players=48` |

## 1. 本轮改了什么

### 1.1 签到发奖：币到手、记录回滚 = 可以反复刷（真 P1）

`blackmarket/DailyRewardService.java:128-200`

原顺序：`记签到 → save → wallet.give（币进钱包）→ 造材料（可能抛）→ 造符（可能抛）`，
catch 里**只回滚 `setSignin`**。于是材料 id 配错时：币已到手、签到记录被清，下次还能再领。

关键认识：**会抛的不是「发」，是「造」**。`materialItem()`（配置材料 id 写错）、
`Charm.create()`（注册表缺件 / 堆叠上限冲突）才抛；`wallet.give` 与 `inventory.addItem`
是「进背包或掉地上」，不抛。

修法：把构造整体提到发东西之前。

```
造材料 → 造符   ← 这一步抛：一件没发、进度没动，直接 return
记签到 → save
给币 → 进背包   ← 以下无可抛点
```

原来的 try/catch 整段删掉（没有可抛点了，留着只会掩盖问题）。

### 1.2 取剑：写盘失败也把剑发出去了（真 P1）

`tomb/TombManager.java:243-300`（新增 `saveChecked()`）、`:565-600`（取剑路径）

`save()` 是 `void`，写失败只 warning。于是磁盘满 / 文件被占用时：
`tombs.remove()` → `save()`（失败）→ `giveItem()` ⇒ 剑在包里而 `tombs.yml` 里那把剑还在，
**重启后又有一把**。

修法：`save()` 改为委托 `boolean saveChecked()`；取剑处

```java
if (!saveChecked()) {
    tombs.put(t.id, t);       // 内存放回
    markDirty();
    if (fee > 0) wallet().give(player, fee, CoinReason.REFUND);
    SEVERE 日志（玩家 / 编号 / 金额）
    return;                    // 不交剑
}
```

判据：**「存不进去就别交东西」**。这条路径要成败，不要"尽力而为"。

### 1.3 碎剑留档可挑战 —— 上一轮是我误判，本轮不改代码

上一轮把「`canNurture` 有 broken gate、挑战没有」列成 P1。查了三处证据后推翻：

1. `TombChallenge.levelOf():680` 有专为 broken 写的分支 `if (t.broken) level = max(level, t.brokenAt)`
   —— 只有挑战路径调 `levelOf`，若 broken 不该被挑战，这段是死代码。
2. `docs/manual/gameplay.md:391`：留档「不能 `take()` 取回、不占埋剑名额」，没说不能挑战。
3. 设计语义说得通：废铁取不回，但残影还能打，且按「碎在 +N」更强。

**结论：保持行为不变**，加了自检钉子把判断固定住，免得下一轮又翻案。

### 1.4 新增 3 条自检钉子（522 → 525）

| 钉子 | 护的是什么 |
| --- | --- |
| `剑冢 saveChecked 可写时返回 true` | 若写盘恒失败，取剑会被永久拦住 —— 这条先红 |
| `碎剑留档可被挑战（设计如此）` | 挡住"好心"加 broken gate 的未来改动 |
| `签到发奖：物品构造必须在发剑币之前` | 防有人把 `wallet.give` 又挪回构造前面 |

## 2. 下一轮先做这 3 条

| # | 事 | 位置 |
| --- | --- | --- |
| 1 | 压测缺 `spiritReroll` / `contract` / `pass` 三个阶段（32 并发下批处理类竞态没被覆盖） | `tools/guitest/concurrent4.js` |
| 2 | 黑市每日签到与 `DailyStore` 的 `save()` 成败仍未被检查（本轮只修了发奖顺序） | `blackmarket/DailyRewardService.java` 开头 `store.save()` |
| 3 | `stats().takeCoins` 三处未归类（是否该走 Wallet） | `guild/GuildService:814`、`contract/ContractManager:431`、`ForgingCommand:3353` |

**已确认不用再审**：命令 `args[n]` 越界、holder 判定顺序、12 个 Router 的 `reset()`、
`ADMIN_ONLY` 逐条判权、碎剑留档可挑战（见 1.3）。

## 3. 不能重犯的坑

1. **锻造系手续费永远是剑币**（`stats().takeCoins`）：强化/进化/铭刻/词条剥离。曾误判成「漏改 Wallet」，改完又回滚。
2. **静态检查类钉子会假绿也会假红**：路径推导错 = 永远跳过；命中 `ForgingMaster/` 里 2.10.6 的旧源码树 = 假红。
3. **并发压测默认 32**（`BOT_COUNT` 可改）。旧规矩「固定 4 个、绝不上 10」已被实测推翻：
   连接层不是瓶颈，唯一硬墙是 `max-players`。**跑完必须核对报告里的 `botCount`** —— 被 clamp 静默截断过一次。
4. **加功能前先搜命令面**（`SUBCOMMANDS` + `helpLines()`）。差点加 `top rep`，而 `rank reputation` 早就有了。
5. **新存档一律走 `YamlIo`**，裸 `yml.save(file)` 自检判红。
6. **命令三处同源**：switch 分派 + `SUBCOMMANDS` + `helpLines()`。
7. **README 有体量预算 450 行 / 48 KB**，超了 `agentdocs.py check` 报错、pre-commit 拒绝提交。
   本轮叙事写 `docs/history/v<段>.md`，README 只改一行索引。
8. **写自检钉子时用真实日期**：`DailyStore.save()` 会剪掉非今天/昨天的日期层，假日期得到假红。
9. **`save()` 返回 void 的路径都要怀疑**：钱货时序类动作拿不到成败，就会「货交了、盘没写」。
   要成败就加 `saveChecked()` 这类返回值，别在调用处猜。

## 4. 命令速查

```powershell
# 构建
& 'D:\apache-maven-3.8.8\bin\mvn.cmd' -B -q clean package

# 自检（约 40 秒；先停服！）
cd D:\IDEA\mc-plugins\ForgingMaster\testserver
& 'C:\Users\heiya\AppData\Local\Programs\Python\Python312\python.exe' _run_selftest_full.py
# 输出可能截断，用这个数项：([regex]::Matches((Get-Content _full-selftest.txt -Raw),'✓')).Count

# 压测（32 并发，约 17 分钟；先停服！）
cd D:\IDEA\mc-plugins\ForgingMaster-Lore
& 'C:\Users\heiya\AppData\Local\Programs\Python\Python312\python.exe' tools\guitest\run-stress.py

# 只测连接行为（不跑 12 阶段）
$env:BOT_COUNT='32'; $env:JOIN_ONLY='1'; node tools\guitest\concurrent4.js

# 连接池逻辑自检（不连服务器，10 项）
node tools\guitest\connpool.js

# 文档门（README 预算 / 身份卡 / 索引）
python tools\agentdocs.py check
```

## 5. 60 秒自查

- [ ] `pom.xml` 版本 = README 第一行标题 = 部署的 jar 名（**改完版本号要重新 build 再压测**）
- [ ] 自检项数 ≥ 525
- [ ] 压测报告 `botCount=32` 且 `FAIL 0`
- [ ] README ≤ 450 行 / 48 KB
- [ ] 自检与压测**串行**跑（抢 25577/25599 端口会呈现成假崩溃）
- [ ] 提交推送了（`git log --oneline -1` 与 `origin/main` 一致）

## 6. 交付物

- 代码：`blackmarket/DailyRewardService.java`、`tomb/TombManager.java`、`selftest/SelfTestSuite.java`
- 文档：`docs/history/v2.25.x.md`（补十九）、本文件、`README.md`（标题 + 一行索引）、`AGENTS.md`、`docs/dev/build.md`
- 工具（上一轮）：`tools/guitest/netprobe.js`、`tools/guitest/connpool.js`