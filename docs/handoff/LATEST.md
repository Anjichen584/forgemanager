# 交付报告 · v2.25.6

> 写于 2026-11-12 13:10（Asia/Shanghai）。commit `6d76938`，已 push `origin/main`。
> **这份文件是下一轮开工前的必读项**：上下文会被压缩，对话里的细节会丢，这里不会。

---

## 0. 一分钟状态

| 项 | 值 |
| --- | --- |
| 版本 | **2.25.6** |
| 产物 | `target/ForgingMaster-2.25.6.jar` |
| `config-version` | 20 |
| 自检 | **520 项，失败 0**（断言下限 = 520，**只许增不许减**） |
| 压测 | 4 并发 12 阶段 **PASS 56 / WARN 0 / FAIL 0** |
| 上一版 | v2.25.5（517 项，56/0/0）commit `e8bdc99` |
| 工作区 | clean，`## main...origin/main` |

---

## 1. 本轮改了什么

### 1.1 三条 P0（都是能被刷的口子，不是理论风险）

| # | 问题 | 位置 | 后果 |
| --- | --- | --- | --- |
| P0-1 | 秘境「守卫失踪」被当成「这一波已清算」，还 `continue` 短路掉「跑太远」判断 | `tombrun/TombRunService.java` `tick()` | 进场后 `/spawn` 站着不动，守卫随区块卸载变 null → 90 秒后照样发 800 剑币 + 2 残页（门票 300），零战斗可无限刷 |
| P0-2 | 剑冢挑战 `onQuit` 第一行 `cooldowns.remove(quitId)` 删掉**整张**冷却表 | `tomb/TombChallenge.java` | 斩影成功会记冷却 → 「打赢 → /quit → 重连」立刻重打同一把剑，`cooldown-hours=24` 形同虚设 |
| P0-3 | 订单托管 `post` 扣**剑币**（`stats().takeCoins`），取消/交付/过期三条退款路径走 `wallet()` | `order/OrderService.java` `post` | `coin-sinks=server` 的服上「发单 → 取消」一个循环把剑币 1:1 洗成 Vault 金币，主手样品不消耗，无上限 |

修法：
- P0-1：抽出纯函数 `TombRunService.missingAction(missingSince, now, awaySeconds)` → `WAIT` / `FAIL`；枚举**只许两个值**，谁改回「清算」自检当场红。顺带把硬编码 30 秒改成读 `tombrun.away-seconds`（配置里本来就有这个键，注释写的也是「判负」）。
- P0-2：抽出 `TombChallenge.pruneExpired(mine, now)`，退服只回收**已过期**条目，生效中的冷却留着（内存契约仍成立）。
- P0-3：改走 `wallet().take(p, bounty, CoinReason.OTHER_OUT)`；提示币名改用 `wallet().ledgerName()`，不再写死「剑币」。

### 1.2 第四条 P0（审计副产物）：砧位并发写盘

`anvils.yml` 是唯一装着玩家**实物剑**的存档，v2.25.5 接了原子写但留了两个洞：

- `util/YamlIo.java` 临时文件名固定 `<name>.tmp` → 两线程 save 同一存档会写同一个 tmp，字节流交错即坏档。**自检里 8 线程并发写原样复现了 3 次异常**。
- `anvil/AnvilForgeManager.java` `saveTo` 的 `synchronized` 只圈「拍快照」，写盘在锁外 → 异步冲刷撞 `/zhujian reload`（stop→save）或 `backup restore`（flushAll→save）会「旧快照压新快照」（剑复制）或坏档（砧位全失踪）。

修法：
1. `YamlIo` tmp 名带 `pid + tid + nano`；按**目标文件绝对路径**取锁（40+ 调用点不必各自记得加锁）；`ATOMIC_MOVE` 撞占用重试 3 次。
2. `saveTo` 整段（快照 + 写盘 + 清脏）进同一把锁。
3. `stop()` 写盘失败时**不再 `sessions.clear()`**，改为保留内存 + `SEVERE`（原来是「内存清空 + 文件还是旧的」，剑两头都没有）。

### 1.3 顺带的 P1

- `tomb/TombManager.java` `recordBroken` / `adminRemove` 只 `markDirty()`（5 秒窗口崩服会丢档 / 条目复活）→ 补同步 `save()`。
- `bounty/BountyManager.java` 品质数组未钳制 + `q.display()` 可 NPE。
- `forge/ForgeManager.java` `reclaim()` 缺 `h.session() == s`，会误关玩家另开的锻造界面。

### 1.4 新功能：`/zhujian anvil [all]`

砧位自查（我的剑在哪、放了多久、还剩多久自动还）。纯只读。动机：v2.25.5 修了「砧位上的剑会丢」，但玩家**没有任何办法确认剑还在不在**。

---

## 2. 下一轮先做这 4 条（已定位，未动）

| # | 问题 | 位置 |
| --- | --- | --- |
| 1 | 通行证领奖「先发后记」，`save()` 失败记录不落盘 → 重启可重领；`buyPremium` 先扣 5000 再写已购，失败不回滚 | `pass/SeasonPassService.java:608-622`（付费轨 `:706-717`）、`:555-563` |
| 2 | 黑市限购计数写在**发货之后** → 崩服可超 `daily-limit` 买折扣货 | `blackmarket/BlackmarketService.java:178-182` |
| 3 | `wallet().give` 返回值被丢弃，连 warning 都没有（拍卖行那侧有 `warnLost` 可照抄） | `order/OrderService.java:314`、`:354`、`commission/CommissionManager.java:400` |
| 4 | `anvil-forge.enabled` 只在 `place` 判，`strike`/`strikeBatch` 没判（README 曾称已补，实际未补） | `anvil/AnvilForgeManager.java:347-377`、`:391-466` |

更远的面（已审过、结论「无 T0」的）：命令 `args[n]` 越界、holder 判定顺序、12 个 Router 的 `reset()`、`ADMIN_ONLY` 逐条判权 —— 这些**不用再花时间重复审**。

---

## 3. 不能重犯的坑

1. **锻造系手续费永远是剑币**（`stats().takeCoins`）：强化 / 进化 / 铭刻 / 词条剥离。曾因误判成「漏改 Wallet」重写过一遍又 `git checkout` 回滚。判据：看钱是怎么挣的 —— 玩家之间流动跟 `coin-sinks`，锻造族永远剑币。
2. **静态检查类钉子会假绿也会假红**：路径推导写错 = 永远跳过（假绿）；向上找源码树命中了 `ForgingMaster/` 里 2.10.6 的旧树 = 假红。现在按「含 `util/YamlIo.java` + pom 版本一致」打分挑源码根。
3. **并发数不许提到 4 以上**（429 / 握手排队）。
4. **加命令前先搜现有命令面**：差点加 `/zhujian top rep`，而 `rank reputation` 早就有了（`Metric.REPUTATION`）。
5. **新存档一律走 `YamlIo`**，裸 `yml.save(file)` 会被「存档守卫」钉子判红。
6. **命令三处同源**：`onCommand` 的 switch 分派 + `SUBCOMMANDS` Tab 表 + `helpLines()`，漏一处自检红。
7. **管理命令要进 `ADMIN_ONLY` 且 handler 内部自判 `forgemaster.admin`**，只靠 Tab 隐藏不算。

---

## 4. 命令速查

```powershell
# 构建（maven 在固定路径）
& 'D:\apache-maven-3.8.8\bin\mvn.cmd' -B -q clean package

# 自检（测试服，约 30 秒；会自己起服停服）
cd D:\IDEA\mc-plugins\ForgingMaster\testserver
& 'C:\Users\heiya\AppData\Local\Programs\Python\Python312\python.exe' _run_selftest_full.py
Select-String -Path '_full-selftest.txt' -Pattern '自检完成'

# 压测（固定 4 并发 12 阶段；会自己起服、部署 jar、跑完关服）
cd D:\IDEA\mc-plugins\ForgingMaster-Lore
& 'C:\Users\heiya\AppData\Local\Programs\Python\Python312\python.exe' tools\guitest\run-stress.py

# 推送（必须走本机 socks 代理）
# 已配好 git config http.proxy / https.proxy = socks5h://127.0.0.1:10808
# 报 "Could not connect to 127.0.0.1:10808" = 代理没开，不是仓库坏
git add -A; git commit -m "..."; git push origin main
```

测试服：`server-port 25577`、`rcon 25599 / testpass`、`online-mode=false`。起服 ≈ 30-40 秒，跑之前先确认服务已停（日志锁）。

---

## 5. 60 秒自查（判断仓库有没有被动坏）

1. `git status -sb` → 应为 `## main...origin/main`（clean）。
2. `mvn -B -q clean package` → exit 0，`target/ForgingMaster-2.25.6.jar` 存在。
3. 自检输出 → `共 520 项，通过 520，失败 0`；**如果项数比 520 少，说明有人删了钉子**。
4. 扫一眼有没有裸写：`grep -rn "yml.save(" src/main/java` → 除 `util/YamlIo.java` 与自检外应为 0。

---

## 6. 交付物清单

- 代码：`src/main/java/com/forgemaster/**`（本轮动了 `util/YamlIo`、`anvil/AnvilForgeManager`、`tombrun/TombRunService`、`tomb/TombChallenge`、`tomb/TombManager`、`order/OrderService`、`bounty/BountyManager`、`forge/ForgeManager`、`command/ForgingCommand`、`selftest/SelfTestSuite`）
- 文档：README `## 十四·补十五`、索引表、头部三行
- 新文件：`util/YamlIo.java`（v2.25.5 引入，本轮修并发）
- 新命令：`/zhujian anvil [all]`
- 自检：517 → **520**