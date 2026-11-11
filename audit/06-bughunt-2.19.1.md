# 2.19.1 缺陷审计与修复

审计基线：`ForgingMaster-2.19.0.jar`，自检 **372/372 通过**。
修复后基线：`ForgingMaster-2.19.1.jar`，自检 **378/378 通过**（净增 6 条断言）。

审计口径沿用 `00-baseline.md`：只写**能在代码里指到行、能在数值上算出数**的问题，
不写「可能」「建议关注」。每条给出：根因 → 影响 → 修法 → 验证。

---

## 汇总

| # | 严重度 | 问题 | 根因位置 | 状态 |
| --- | --- | --- | --- | --- |
| 1 | **P0** | 拍卖一口价：扣款失败仍给卖家打款 + 退次高出价 = 凭空造币 | `AuctionManager.buyout()` | 已修 |
| 2 | **P1** | 拍卖到期结算：同一单可被子路径抢先结案后重复给卖家打款 | `AuctionManager.settleExpired()` | 已修 |
| 3 | **P1** | 货币口径分裂：宝石/黑市/签到/重铸扣的是**服务器金币**，玩家赚的是**剑币** | `GemService`、`BlackmarketService`、`DailyRewardService`、`ReforgeMenu` | 已修 |
| 4 | **P1** | 一次锻造最多触发 **14 次** `codex-data.yml` 全量重写（主线程） | `CodexService.record()/registerForge()` | 已修 |
| 5 | **P1** | 每锻一把剑都整档重写 `pass-data.yml`（主线程） | `SeasonPassService.earn()` | 已修 |
| 6 | **P1** | 强化模拟器口径错误：把「空锤」当降级、漏掉大成 +2，`+99` 期望跑到 2.6e18 | `StrengthenService.simulateStats()` | 已修 |
| 7 | **P1** | 品质上限写到 +99，但 `+72` 以上实际不可达（碎裂风险复利） | 配置曲线 | 已重定（step12） |
| 8 | P2 | 模拟器对「期望发散」段输出天文数字而非说明 | `StrengthenService.simulate()` | 已修 |

---

## 1（P0）拍卖一口价凭空造币

**根因**（旧代码，`AuctionManager.buyout`）：

```java
// 先把拍品结掉（归属落盘），再动钱…
store.finish(id, p.getUniqueId(), l.item());
if (!plugin.stats().takeCoins(p.getUniqueId(), cost)) {
    plugin.getLogger().warning("一口价扣款异常 #" + id + "（" + cost + " 剑币），请人工核对");
}
if (l.hasBidder() && !selfTop) { plugin.stats().addCoins(l.bidder(), l.price()); }
long gain = proceeds(l.buyout());
plugin.stats().addCoins(l.seller(), gain);
```

`takeCoins` 失败时只打一条 warning，然后**照样**给卖家打款、给次高出价退款。
钱从三个方向出去（卖家货款 + 次高退款 + 买家拿走物品），一分没进来。

**影响**：任何一次 `takeCoins` 失败（余额在 237 行校验后被别处花掉、`StatsStore`
异常）都变成一次无本套现，且只在日志里留一行文字，服主基本发现不了。

**修法**：改成「先扣钱、再结案」，扣不到就不成交；结案失败原路退回。

```java
if (!plugin.stats().takeCoins(p.getUniqueId(), cost)) {
    return "<red>扣款失败，一口价没有成交，请稍后再试。";
}
if (!store.finish(id, p.getUniqueId(), l.item())) {
    plugin.stats().addCoins(p.getUniqueId(), cost);
    plugin.stats().markDirty();
    return "<red>这件拍品刚刚被结掉了，剑币已退回。";
}
```

为此把 `AuctionStore.finish(...)` 从 `void` 改成 `boolean`（`listings.remove(id) == null`
即返回 false）。方法签名收窄，全部旧调用点源码兼容。

**验证**：新增断言「结案只能成功一次（防二次给卖家打款）」——同一个 id 连结两次，
第一次 true、第二次 false，且单子已不在列表里。

---

## 2（P1）到期结算重复付款

**根因**：`settleExpired()` 遍历 `store.all()` 的副本，对每张到期单无条件
`finish` 后 `addCoins(seller, gain)`。若这张单已被别的路径（玩家 `${buyout}`、
`/zhujian auction cancel`）结掉，`finish` 是空操作，但卖家**照样**再收一次钱。

**修法**：两个分支都用返回值把关，结不掉就 `continue`：

```java
if (!store.finish(l.id(), l.bidder(), l.item())) { continue; }
...
if (!store.finish(l.id(), l.seller(), l.item())) { continue; }
```

**验证**：同 #1 的 `finish` 幂等断言覆盖了这一前提。

---

## 3（P1）货币口径分裂——两本账

这是本轮最隐蔽的一个：**不报错、不崩、数值也不异常，但功能就是用不了。**

### 插件的两本账

| 账本 | 载体 | 谁在用 |
| --- | --- | --- |
| **剑币** | `StatsStore`（`addCoins` / `takeCoins` / `coins`） | 锻造奖励、委托、大赛、拍卖、强化、蜕变、商店、词条、剑冢、行会、签到**应**在此 |
| **服务器经济** | `EconomyBridge`（Vault / CMI / 内置兜底） | 只有**锻造费**（README：「锻造费走服务器经济（v2.1）」） |

README 第 816 行写死：「剑币是本插件的唯一货币，所有增减都走 `StatsStore.addCoins` / `takeCoins`」。

### 分裂现场

`grep plugin.economy()` 全量核对后，扣费口径是**两套并存**：

| 模块 | 旧口径 | 应是 |
| --- | --- | --- |
| `ForgeManager` 锻造费 | `eco` | ✅ 设计如此（唯一例外） |
| `GemService` 星熔/开孔/拆卸/合成/镶嵌/附魔孔（8 处） | `eco` | ❌ 剑币 |
| `BlackmarketService` 黑市购买 | `eco` | ❌ 剑币 |
| `DailyRewardService` 签到发币 | `eco` | ❌ 剑币 |
| `ReforgeMenu` 重铸费三处 | `eco` | ❌ 剑币 |
| 强化 / 蜕变 / 拍卖 / 商店 / 词条 / 契约 / 剑冢 / 行会 / 委托 | `stats` | ✅ |

### 影响（装了 Vault 的服 100% 复现）

玩家锻造一把剑赚 ~500 **剑币**，然后去镶宝石，系统要 **200 服务器金币**。
玩家剑币账户里有几万，Vault 余额是 0 → 提示「镶嵌费不足」，但界面上剑币余额
写得明明白白。反过来，Vault 有钱的玩家镶宝石完全不消耗游戏内产出，
等于宝石是免费送的。

测试服就是 Vault + CMI 环境，所以这个 bug 一直存在但从没被断言抓到——
自检里只验了 `plugin.economy() != null`，没验「谁扣哪本账」。

### 修法

新增 `com.forgemaster.econ.Wallet` 作为唯一消费入口：

```java
public boolean take(Player player, long amount) {
    if (serverSinks()) { return plugin.economy().withdraw(player, amount); }
    return plugin.stats().takeCoins(player.getUniqueId(), amount);
}
public boolean give(Player player, long amount) { /* 退费走同一本账，防「扣金币退剑币」 */ }
```

配 `economy.coin-sinks`（`plugin` 默认 / `server`）一个开关决定口径，
默认按 README 走剑币。`COINS` 后端下两条路完全等价，单人服无感。

四个模块 12 处调用点全部改走 `Wallet`；`ForgingMasterPlugin.wallet()` 暴露实例。

**验证**：
- 「消费口径统一：剑币账户只有一本账」（口径值合法、`Wallet` 与 `StatsStore` 同源读写）
- 「宝石 / 黑市 / 签到 / 重铸 不再直连 Vault」（源码级守卫，开发树里生效）

---

## 4（P1）一次锻造 14 次全量写盘

**根因**：`CodexService.record()` 每次登记新条目就 `save()`，而 `save()` 是
**整份 `codex-data.yml` 重建 + 落盘**。`registerForge()` 一次锻造会连续登记：

品质 1 + 剑型 1 + 评分档 6（50/65/75/85/92/97 全达标）+ 词条最多 6 = **14 条**。

主线程上 14 次全文件重写，玩家越多单次越贵（`save()` 遍历全部玩家的全部条目）。

**修法**：拆出 `recordInMemory()`（只改内存、返回是否新增），`registerForge()`
用 `changed |=` 累积，最后合并成**一次** `save()`。单条登记（重铸出词条、
剑灵命名）仍即时落盘。

**验证**：既有图鉴断言全绿；`claim()` 的落盘语义未变。

---

## 5（P1）每锻一把重写 pass-data.yml

**根因**：`ForgeManager:1685` 每炉结算调 `seasonPass().earn(p, "forge")`，
而 `earn()` 末尾直接 `save()` —— 同样是整档重建写盘。委托交货、大赛、拍卖成交
也走这条路。

**修法**：改成脏标记 + 合并落盘，与 `AuctionStore` / `GuildService` 同一套写法：

```java
private volatile boolean dirty;
private void markDirty() { dirty = true; }
public void flush() { if (dirty) save(); }
```

`earn()` → `markDirty()`；`claim()`（真事务）仍即时 `save()`；
主类加一个每 10 秒的 `runTaskTimer` 调 `flush()`；`onDisable` 的
`seasonPass.save()` 兜底。最坏只丢最后 10 秒积分。

---

## 6（P1）强化模拟器口径错误

**根因**（旧 `simulateStats`）：把所有「非碎裂失败」一律当成**降 1 级**：

```java
visit[L][L - 1] -= q * (1.0 - b);   // 只看「失败且没碎」，不管门票怎么分
```

而真实掷骰（`odds()`）是五态：失败里 `damage-share: 0.5` 才是损伤降级，
另一半是**空锤**（原地不动）；成功里还切出一块 `greatBase 0.08` 是**大成 +2**。
模拟器既没算空锤缓冲，也没算大成加速 → 高段被判成强负漂移 → 期望值爆炸。

**实测偏差**（旧口径 vs 蒙特卡洛）：

| 目标 | 旧模拟器说 | 真实（五态） |
| --- | --- | --- |
| +40 | 约 1.9e5 剑币 | 约 5.9e4 |
| +60 | 1.9e5 剑币 | 约 2.9e4 |
| +86 | 6.5e10 | 有限 |
| +99 | **2.6e18** | 有限 |

玩家打 `/zhujian strengthen simulate` 看到的是「要锤两千万亿次」。

**修法**：按 `odds()` 的同一套概率建马尔可夫链——
`pSucc/pGreat/pDamage/pHold` 四数组逐级算出来，解两个线性方程组
（期望访问次数、达成概率），碎裂是吸收态。并对发散段返回 `-1` 哨兵，
由 `simulate()` 输出「这一段期望不收敛，靠连击/符」而不是天文数字。

**验证**：新增「模拟器与明牌抽票用同一套概率」——对 `+0/+10/+30/+60` 四档核对
五态和为 1、大成 = `greatBase`、成功+大成 = `successChance`、损伤 = 非碎失败 × `damage-share`。
另新增「期望消耗与逐级费用自洽」。

---

## 7（P1）品质上限是画出来的

用修好的模拟器（裸锤、不含连击/符）算「一次锻造成活到目标」的概率：

| 目标 | 旧曲线达成率 | 换成「把剑数」 | 新曲线达成率 | 换成「把剑数」 |
| --- | --- | --- | --- | --- |
| +40 (ROUGH) | 24.74% | 4 | 42.89% | 2 |
| +50 (COMMON) | 6.43% | 16 | 20.12% | 5 |
| +60 (FINE) | 0.72% | 139 | 5.94% | 17 |
| +72 (EPIC) | 0.0112% | 8,964 | 0.5494% | 182 |
| +86 (LEGENDARY) | 4e-8 | 1,866 万 | 0.0051% | 19,424 |
| +99 (MYTHIC) | 5e-10 | **5,381 亿** | 仍靠符 | — |

结论：旧曲线在 +70 往上是一条死胡同——碎裂风险在几百锤里复利，任何非零碎率
都会把达成率稀释成 0。**配置写着 +99，实际 +72 就封顶了。**

**修法**（`ConfigMigrations` step12，`config-version` 2 → 3）：

| 键 | 旧 | 新 | 理由 |
| --- | --- | --- | --- |
| `anvil-forge.success.floor` | 0.10 | **0.14** | 顶段不绝望 |
| `anvil-forge.break.max` | 0.30 | **0.18** | 顶段整体碎率 27% → 16% |
| `strengthen.tickets.damage-share` | 0.50 | **0.35** | 失败更多是空锤，负漂移收窄 |
| `strengthen.inherit.rate` | 0.25 | **0.35** | 碎剑回收更实 |
| `strengthen.inherit.cap` | 10 | **20** | 高段碎了不必从零爬 |

判定沿用既有约定：三/四个键**同时**等于旧默认值才整体换新，服主调过一个字不动。
新值下 +60 ≈ 17 把剑、+72 ≈ 182 把剑（可长期追求），+86/+99 保持符/连击门槛
（护胚符的经济价值不被抹平）。

---

## 未修 / 待观察

| 项 | 为什么不动 |
| --- | --- |
| 服务器货币单向流失（赚剑币、花金币） | 是 README 写明的设计（锻造费走外部经济），要改是经济模型决策，不是 bug |
| 蓝图成本 1600/把 vs 产出 ~507/把 | 属数值偏好，本轮未动；如需调只需改 `blueprint` 段 |
| 重铸 `quality.*` ×3.4 乘区未进配置 | 是硬编码但语义稳定，改动面大，留待下一轮 |
| `break.max` 配置 0.30 vs 代码 0.35 的历史不一致 | 代码 fallback 已随本轮统一到 0.18 |

### 一个未复现的偶发红项

某一次全量跑出现 `共 378 项，通过 377，失败 1`，但：

- RCON 存档只有 196 行（378 项本该更长）——**回包被聊天缓冲截断**，
  `✗` 那几行正好落在丢失区间；
- 紧接着连跑两次都是 378/378 全绿，无法复现。

当场加了诊断钩子（见下），下次再出现就能直接定位，不再靠猜。

---

## 顺带补的两处可观测性

### 1. 失败项写进服务器日志

`SelfTestSuite.report()` 之前只把 `✗` 发到命令发送方（RCON / 玩家聊天栏）。
项数一多，Minecraft 的聊天回包会截断，`RESULT-selftest-*.txt` 存档里就只剩
「失败 1」而查不到是哪条。现在失败项额外 `Bukkit.getLogger().warning(...)` 一次：

```
[SelfTest] ✗ 分组 / 项名 → 原因
```

`_selftest-run.log` 里 `grep "[SelfTest]"` 即可，不受聊天缓冲影响。

### 2. 双账本对账输出

`/zhujian money [balance]` 除原有余额外，多打三行：

```
├ 剑币（插件内产出：锻造 / 委托 / 大赛 / 拍卖）: 3,860 币
├ 宝石 / 黑市 / 签到 / 重铸 扣: 剑币（economy.coin-sinks=plugin，与商会 / 拍卖同一本账）
└ 锻造费扣: 服务器经济（README 写明的唯一例外）
```

口径配错时，玩家和服主一眼能看出来，不用再去翻 `config.yml`。

---

## 新增模块：`/zhujian strengthen curve`

同一套马尔可夫链的第二种用法：把「从当前等级锻到各品质上限」的代价摆成一张表。
服主调完 `anvil-forge` 敲一下就知道曲线是「渐进变难」还是「+70 之后断崖」，
不必再另写蒙特卡洛脚本。

实测输出（1 档剑胚、从 +0 起、裸锤口径）：

| 目标 | 期望锤 | 期望剑币 | 达成率 | 相邻档边际 |
| --- | --- | --- | --- | --- |
| +40 | 42.0 | 6,336 | 42.886% | — |
| +50 | 47.7 | 8,074 | 20.120% | +1,738 |
| +60 | 50.3 | 9,016 | 5.937% | +943 |
| +72 | 51.1 | 9,332 | 0.549% | +316 |
| +86 | 51.1 | 9,361 | 0.005% | +28 |
| +99 | 51.1 | 9,361 | 0.000% | +0 |

注意：期望锤数在 +60 之后基本饱和（≈51）——因为绝大多数跑在碎裂处终止，
再往上只是「同一批早死的跑」，不是「要锤更多次」。读这张表要看**达成率**那一列，
它才是真正的难度刻度。

断言：六档各一行、含达成率、含期望值、能渲染成纯文本、非锻造品被拦住。

---

## 刷新自检的方式

```powershell
mvn -q -o package -DskipTests
python tools/selftest.py
# 存档：D:\IDEA\mc-plugins\ForgingMaster\testserver\RESULT-selftest-ForgingMaster-2.19.1.txt
# 失败项：Select-String -Path testserver\_selftest-run.log -Pattern '\[SelfTest\]'
```

当前结果：`铸剑 » 自检完成：共 378 项，通过 378，失败 0。`（连跑两次一致）