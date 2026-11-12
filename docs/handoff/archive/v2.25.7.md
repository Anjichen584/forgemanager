# 交付报告 · v2.25.7

> 写于 2026-11-12 14:00（Asia/Shanghai）。承接 v2.25.6（commit `c7fb15e`）。
> **这份文件是下一轮开工前的必读项**：上下文会被压缩，对话里的细节会丢，这里不会。

---

## 0. 一分钟状态

| 项 | 值 |
| --- | --- |
| 版本 | **2.25.7** |
| 产物 | `target/ForgingMaster-2.25.7.jar` |
| `config-version` | 20 |
| 自检 | **522 项，失败 0**（下限 = 522，**只许增不许减**） |
| 压测 | **5 并发** 12 阶段 **PASS 63 / WARN 0 / FAIL 0**（本轮从 4 提到 5） |
| 上一版 | v2.25.6（520 项，56/0/0）commit `c7fb15e` |
| README | 394 行 / 37 KB（上限 450 行 / 48 KB） |

---

## 1. 本轮做了什么：四条 P1（钱货时序）

主题是**「崩服一次就白送」**。崩服不总是玩家的错（OOM / 插件冲突 / 管理员重启），
一晚上撞上一次就是真损失。

| # | 问题 | 位置 | 修法 |
| --- | --- | --- | --- |
| 1 | 付费轨「先扣 5000 → 再写已购 → save 失败只 warning」＝ 花了钱什么都没拿到 | `pass/SeasonPassService.java` `buyPremium` | `save()` 改返回 boolean；顺序改「记录 → 落盘 → 扣钱」，两头都回滚 |
| 2 | 领奖「发奖 → 裸 save()」，落盘失败静默；崩在 flush 前重启可**重复领同一级** | `pass/SeasonPassService.java:608-622`、`:706-717` | 保留发奖在前（否则变成「记录有、奖励没发」更难查），但失败要 `SEVERE` + 提示 + 留对账线索 |
| 3 | 黑市限购 `recordPurchase` 写在**发货之后**；崩在中间额度回满 ⇒ **崩溃刷折扣货** | `blackmarket/BlackmarketService.java` | 改成「先占额度 → 落盘成功 → 才发货」；失败不发货且退款 |
| 4 | `wallet().give` 返回值被丢弃 —— 钱没到玩家手里，无日志无告警 | `order/OrderService.java:325`、`:368`、`commission/CommissionManager.java:400` | 照拍卖行 `warnLost`：`SEVERE`（含 uuid/金额/单号，可人工补发）+ 邮箱提示 |
| 5 | `anvil-forge.enabled` 只在 `place` 判，场上的砧位照样能锤/批量 | `anvil/AnvilForgeManager.java` `strike`/`strikeBatch` | 补判，且**放在「冷却静默」之前**（关停要提示一次，不能一声不响） |

### 新增 API

- `pass/SeasonPassService.save()` → `boolean`
- `blackmarket/DailyStore.save()` → `boolean`
- `blackmarket/DailyStore.tryReservePurchase(uuid, date, offerKey, limit)` —— 占额度 + 落盘，成功才 true
- `blackmarket/DailyStore.releaseReservation(uuid, date, offerKey)` —— 撤回，减到 0 清空壳
- `order/OrderService.warnLost(uuid, name, amount, what)` —— 私有，对账用

### 两条新钉子（522）

1. **黑市额度可占可撤**：占 → 读到 → 撤 → 归零 → 买满拒 → 多次占满后全撤不残留 → 撤不存在的记录不炸。
2. **通行证 `save()` 能报告成败**：正常环境必须返回 true（否则买付费轨永远被拒）。

---

## 1.5 本轮追加：连续轮次 + 并发 5 + 429 结论

### 定时任务：改成连续跑

`every_seconds` 3600 → **600**。一轮 10-15 分钟，干完时下一条提醒已在排队，
所以「结束＝立刻开始」。不设 60 秒是因为一轮会排出十几条提醒，结束时连珠炮触发；
要真正零空档，该改的是一轮的任务量，不是提醒频率。

### 并发 4 → 5（实测记录）

| 并发 | 结果 |
| --- | --- |
| 4 | PASS 56 / WARN 0 / FAIL 0 |
| **5** | **PASS 63 / WARN 0 / FAIL 0** ← 现默认 |
| 6 | JOIN_ONLY：上线 12.6 s，存活 6/6，0 节流 |
| 8 | JOIN_ONLY：上线 14.3 s，存活 8/8，0 节流 |

`run-stress.py` 里 `BOT_COUNT` 默认已改成 5；`concurrent4.js` 支持 `BOT_COUNT` / `JOIN_ONLY`。

### 429 结论（重要，别下次又从头查一遍）

- **本机测不出 429**：`auth:'offline'` + `online-mode=false`，握手不碰 Mojang 会话服。
- 真 429 只在**在线模式**。处理方式是**指数退避 + 抖动**（`joinWithRetry`，1.5→3→6→12 s，最多 4 次），
  不是固定 `sleep` —— 限流按时间窗放行，固定等待容易反复撞同一堵墙。
- 节流计入 `throttleEvents` 写进报告，**记 WARN 不记 FAIL**。
- `server_full` / `whitelist` / `banned` = **硬性拒绝**，立刻停下并说明，不重试。
  `BOT_COUNT=8` 撞 `max-players=5` 就是这个表现（测试服已调到 12）。
- **压测 FAIL 不会让定时任务停**（定时器独立）。定位法：`BOT_COUNT=4` 重跑，4 也红＝真回归。

### 顺带修的两个日志 bug

- `sp()` 用 `bot.username` 报错 → 被踢时是 `undefined`，日志打出「undefined 被踢」，认不出哪只 bot。改用传入的 `name`。
- `sp()` 只等 `spawn` → 被踢要傻等满 20 秒。现在同时监听 `kicked`/`error` 立刻失败。

## 2. 下一轮先做这 4 条（已定位，未动）

| # | 问题 | 位置 |
| --- | --- | --- |
| 1 | 黑市签到：先发剑币再发材料，catch 只回滚 `setSignin`（材料发一半的状态没处理） | `blackmarket/DailyRewardService.java:136-179` |
| 2 | `broken` 状态的剑冢仍可被挑战（`canNurture` 有 gate，挑战没有） | `tomb/TombChallenge.java:145-165` |
| 3 | `TombManager.take()` 在同步 `save()` 抛异常时仍把剑发出去 | `tomb/TombManager.java:568` |
| 4 | 并发压测缺 `spiritReroll` / `contract` / `pass` 三个阶段 | `tools/guitest/concurrent4.js` |

更远的面（已审过、结论「无 T0」）：命令 `args[n]` 越界、holder 判定顺序、12 个 Router 的 `reset()`、
`ADMIN_ONLY` 逐条判权 —— **不用重复审**。

---

## 3. 不能重犯的坑

1. **锻造系手续费永远是剑币**（`stats().takeCoins`）：强化/进化/铭刻/词条剥离。曾误判成「漏改 Wallet」，改完又回滚。
2. **静态检查类钉子会假绿也会假红**：路径推导错 = 永远跳过；命中 `ForgingMaster/` 里 2.10.6 的旧源码树 = 假红。
3. **并发压测固定 4 个 bot**，绝不上 10。
4. **加功能前先搜命令面**（`SUBCOMMANDS` + `helpLines()`）。差点加 `top rep`，而 `rank reputation` 早就有了。
5. **新存档一律走 `YamlIo`**，裸 `yml.save(file)` 自检判红。
6. **命令三处同源**：switch 分派 + `SUBCOMMANDS` + `helpLines()`。
7. **README 有体量预算 450 行 / 48 KB**，超了 `agentdocs.py check` 直接报错、pre-commit 拒绝提交。
   本轮叙事写 `docs/history/v<段>.md`，README 只加一行索引。
8. **写自检钉子时用真实日期**：`DailyStore.save()` 会剪掉非今天/昨天的日期层，
   用假日期会得到「计数没涨」的假红（本轮真的踩了一次）。
9. **共享 `src/**` 的 agent 要拿锁**：`python tools/agentdocs.py lock --as <名字>`，收尾 `unlock`。

---

## 4. 命令速查

```powershell
# 构建
& 'D:\apache-maven-3.8.8\bin\mvn.cmd' -B -q clean package

# 自检（测试服，约 30 秒，自己起服停服）
cd D:\IDEA\mc-plugins\ForgingMaster\testserver
& 'C:\Users\heiya\AppData\Local\Programs\Python\Python312\python.exe' _run_selftest_full.py
Select-String -Path '_full-selftest.txt' -Pattern '自检完成'

# 压测（固定 4 并发 12 阶段，约 5 分钟）
cd D:\IDEA\mc-plugins\ForgingMaster-Lore
& 'C:\Users\heiya\AppData\Local\Programs\Python\Python312\python.exe' tools\guitest\run-stress.py

# 文档闸门 / 身份卡校验 / 串行锁
& 'C:\Users\heiya\AppData\Local\Programs\Python\Python312\python.exe' tools\agentdocs.py check
& 'C:\Users\heiya\AppData\Local\Programs\Python\Python312\python.exe' tools\agentdocs.py doctor

# 推送（代理 socks5h://127.0.0.1:10808 已配好）
git add -A; git commit -m "..."; git push origin main
```

测试服：`server-port 25577`、`rcon 25599 / testpass`、`online-mode=false`。起服 ≈ 30-40 秒，跑之前确认服务已停。

---

## 4.5 运维坑（本轮新发现）

**自检和压测不能同时跑** —— 两者都占用 `testserver`（25577 / RCON 25599）。
本轮并行触发后自检直接 `UnicodeEncodeError` 挂在起服阶段，看起来像代码炸了，其实只是抢端口。
顺序必须是：`build` → 压测 → 等服关掉 → 自检；或者反过来，串行做。

**另一个坑：压测跑的是「当时 target 里那个 jar」**。本轮先把 pom 改成 2.25.7 之前就跑过一次，
结果压测部署的是 `2.25.6.jar`（虽然代码内容一样，但「测的」和「要发的」文件名不一致）。
规矩：**改完版本号 → 重新 build → 再跑压测**，让压测日志里的 jar 名等于要发布的那个。

## 5. 60 秒自查

1. `git status -sb` → `## main...origin/main`（clean）
2. `mvn -B -q clean package` → exit 0，jar 是 2.25.7
3. 自检 → `共 522 项，通过 522，失败 0`；**比 522 少说明有人删了钉子**
4. `grep -rn "yml.save(" src/main/java` → 除 `util/YamlIo.java` 与自检外应为 0
5. `agentdocs.py check` → 通过（README 未超预算）

---

## 6. 交付物清单

- 代码：`pass/SeasonPassService`、`blackmarket/BlackmarketService`、`blackmarket/DailyStore`、
  `order/OrderService`、`commission/CommissionManager`、`anvil/AnvilForgeManager`、`selftest/SelfTestSuite`
- 文档：README 头三行 + 索引表一行；`docs/history/v2.25.x.md` 追加「十四·补十六」
- 自检：520 → **522**