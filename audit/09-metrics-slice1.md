# 切片 1 交付记录 · v2.20.0

规划来源：[`08-expansion-plan.md`](08-expansion-plan.md)。
目标：把「经济只能靠推演」变成「服主能看真实流水」。

**结果：自检 383 → 386 全绿，30 个文件改动，4 路并行。**

---

## 一、地基：三个收口点

做之前先确认了一件事——剑币增减是不是只有一处收口。答案是三处，没有第四处：

```
StatsStore:396  加币
StatsStore:410  扣币
StatsStore:422  赛季清零
```

全仓库 grep `st.coins` 只找到读操作（排行 / 占位符 / 面板）和这三处写操作。
**这是整个切片能低成本落地的前提**：不用去几十个业务模块里找漏网的钱。

新增计数就加在这三处旁边，`resetSeasonal` 不碰它们（流水是服务器生命周期累计，
不是赛季量）。

API：

```java
public long addCoins(UUID id, long amount);                        // 默认 OTHER_IN
public long addCoins(UUID id, long amount, CoinReason reason);
public boolean takeCoins(UUID id, long amount);                    // 默认 OTHER_OUT
public boolean takeCoins(UUID id, long amount, CoinReason reason);
```

`Wallet.take/give` 同步加了理由重载——宝石台、黑市、签到这些走钱包的地方
才能归类。

只读快照 API 给报表用：`producedTotal` / `consumedTotal` / `producedByReason` /
`consumedByReason` / `totalCoins` / `playerCount` / `coinBalances`。

---

## 二、`CoinReason`：粗桶枚举

14 个产出桶 + 13 个消耗桶。设计要点：

1. **落盘键是 `key()` 不是 `name()`**：`forge` / `gem` / `auction-tax`。
   枚举常量改名不会让历史快照失配。
2. **`byKey` 永不返回 null**：认不出就按方向落到 `OTHER_IN` / `OTHER_OUT`。
3. **`sortKey()`** 让报表稳定排序（同额不抖）。
4. **先粗后细**：细化只是加枚举值，调用点不返工。

实施中追加了 4 个值，都是因为「混进其它桶就看不出来」：

| 新增 | 为什么必须单列 |
| --- | --- |
| `CONTRACT` | 龙税赏金既不是退款也不是成就，混进其它产出就看不到周目在发多少钱 |
| `AUCTION_BUY` | 买家侧与卖家侧之差就是抽成，并排两行能直接读出抽成率 |
| `PASS` | 通行证等级奖励 |
| `CODEX` | 图鉴 / 名剑谱收集奖励 |

`AUCTION_BUY` 记成**消耗**而不是中性转移，是刻意的：如果归进「其它消耗」，
拍卖成交额会淹在噪声里，服主永远看不出拍卖在吸多少币。

---

## 三、两个口径上的坑

### 3.1 退款要剔出去

退款回滚走 `REFUND`，报表里从**产出总额**和**产出构成**里剔除，单列一行。
不剔的话，一块钱转两圈就能刷出假通胀——拍卖出价被超越、扣款失败回退、
断线续做，全是这类。

### 3.2 基尼系数在小样本上必须闭嘴

第一版直接对全服算。实测输出：

```
剑币总量: 300 · 人均 8 · 中位 0
基尼系数: 0.97 偏高
```

33 个档案只有 1 个人持币。数学没错，但那 32 个零余额档案是「没玩过」或
「赛季重置后还没上线」，不是「被经济排斥的穷人」。**拿死亡档案衡量贫富差距**，
结果是告警天天挂着，等真不均有 0.7 时反而没人看了。

两处修正：

1. 基尼只在**持币玩家**（余额 > 0）上算，并如实写出排除了多少人
2. 持币玩家 < 5 人时显示「样本太少，暂不下结论」，不给红字

```
├ 基尼系数: 样本太少（1/5 人），暂不下结论 (按 1 名持币玩家算，36 人零余额已排除)
```

中位数保留全体口径：中位 0 配人均 8，正好说明大多数人手里没币——那是有用信息。

---

## 四、顺手逮到的两个真实问题

### 4.1 `Msg.fmt` 跟着 JVM locale 走

```java
String.format("%.1f", 63.9)   // 德语区 → "63,9"
```

这个函数渲染了玩家看到的所有百分比、倍率、曲线表。德语 / 法语区的服务器上
数字会变成逗号小数，既难读又和千分位混淆。已锁死 `Locale.ROOT`。

### 4.2 附魔镶孔扣钱后退不出物品

```java
if (cost > 0 && !wallet.take(player, cost, CoinReason.GEM)) return;
ItemMeta meta = item.getItemMeta();
if (meta == null) {
    return;          // ← 钱已经扣了
}
```

同流程的「取回」分支早就有 `REFUND` 兜底，镶孔这里漏了：玩家白付 300 剑币。
已补退款 + 提示。

---

## 五、并行施工切分（4 路）

文件域完全不重叠，Lead 同时拿 `econ/` + `stats/` + `command/` 做共享契约：

| 分工 | 文件域 | 产出 |
| --- | --- | --- |
| Lead | `econ/CoinReason`、`econ/Wallet`、`stats/StatsStore`、`command/ForgingCommand` | 契约 + 命令 + 帮助 + 补全 |
| A 产出埋点 | `auction/` `commission/` `contests/` `bounty/` `tomb/` `pass/` `mentor/` `codex/` `forge/` `spectrum/` `DailyRewardService` | 23 处 |
| B 消耗埋点 | `shop/` `gem/` `strengthen/` `affix/` `guild/` `contract/` `blackmarket/*Service` `gui/ReforgeMenu` | 26 处 |
| C 报表 | 新建 `metrics/MetricsReporter` | 378 行 |
| D 自检+配置 | `selftest/` `config/` `resources/config.yml` `ForgingMasterPlugin` | 3 断言 + `metrics` 段 |

合并后一次编译通过。所有子智能体都被要求**不跑 mvn**（并发构建会争 `target/`），
由 Lead 统一编译，这一步避免了最容易翻车的地方。

---

## 六、砍掉的一个 step16

`selftest-migrate` 按任务书加了 `step16MetricsKeys`（补 `metrics` 段的 5 个键），
但实测日志出现两次同样的写入：

```
step16：补了 5 个看板配置键…
step15：盘上缺 1 处配置（共 5 个键）…metrics(5键)
```

原因：**step15 比对的是磁盘原文**，看不到 step16 还没落盘的 cfg 改动，
于是立刻重复补了同一批键。而 step15 的「整段缺失就按内置资源补回」本来就覆盖
`metrics` 这种全新区段。

留着只是多一条日志、多 5 次重复赋值、外加一段永远不会有人读的代码。**删掉**，
并在调用点留一条注释说明为什么不需要它。`CURRENT_VERSION` 保持 7（step15 行为
在本版确有变化）。

---

## 七、并行施工的覆盖率陷阱（这轮最该记住的教训）

4 路并行按**目录**分域，看起来很干净：产出侧一串、消耗侧一串、报表一个包、
自检+配置一个组。合并、编译、自检全绿。**然后做了一次「裸调用残留」扫描**：

```bash
grep -rn 'takeCoins(\|addCoins(' src/main/java | grep -v CoinReason
```

结果抓出 6 处没人管的：

| 位置 | 语义 | 为什么漏 |
| --- | --- | --- |
| `growth/AchievementEvaluator:121` | 成就发钱 | `growth/` **谁都没分到**（成就系统在 growth 包，不在 codex 包） |
| `command/ForgingCommand:230` | 某处手续费 | 命令层归 Lead，Lead 只改了自己写的 metrics 分支 |
| `command/ForgingCommand:2752` | 玩家间转钱 | 同上 |
| `order/OrderService:222` | 发单扣悬赏（托管） | `order/` 谁都没分到 |
| `spirit/SpiritManager:538` | 剑灵获取费 | `spirit/` 谁都没分到 |
| `tombrun/TombRunService:168` | 秘境门票 | `tombrun/` 谁都没分到（分的是 `tomb/`） |

**全绿的测试没能发现它们**——因为 386 条断言里没有一条校验「所有调用点都带了理由」，
而带默认值的旧签名让漏掉的调用点照样编译、照样运行，只是数据落进「其它」桶。

这类问题的特征：**沉默**。功能不坏、测试不红、日志不响，只是报表上「其它消耗」
莫名占了 40%，而服主永远不知道那 40% 是什么。

修法有两层：

1. 立即补上这 6 处（本次已做）
2. **让「其它」桶的占比本身成为指标**：报表里 `other-in` / `other-out`
   占比过高就说明还有地方没接上——这是唯一能长期守住的办法

教训：**按目录分域会漏掉没人认领的目录**。更稳的分法是「先 grep 出全部调用点，
再按调用点清单分」，而不是按目录分。测试全绿不等于覆盖完整，尤其是带默认值的兼容层。

---

## 八、现状与下一步

- 自检 **386/386**
- `/zhujian metrics` 实机验证通过（空服输出也干净，不报错、无 NaN）
- 产出/消耗计数由自检断言覆盖（恒等式 + 失败不记账）

切片 2（每日快照 + 30 日趋势）和切片 3（GUI + CSV）待做。
先把「数字是对的」做实，再谈好看。