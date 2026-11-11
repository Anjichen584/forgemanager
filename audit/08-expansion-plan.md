# 拓展规划 · v2.20 起

写这份文档前先把家底盘了一遍，避免规划出已经有的东西。

---

## 一、家底：已经有的

**51 个功能包**、168 个 Java 文件、21 个持久化 yml。按玩法层分：

| 层 | 已有模块 |
| --- | --- |
| **锻造核心** | 七工序（加热/锻打/叠钢/开刃/抛光/嵌银丝/淬火）、剑型 `form`、品质 `quality`、剑绘本 `manual`、临摹 `copy`、极限 `risk` |
| **成长** | 铁砧强化（五态抽票 + 连击 + 炉魂 + 护胚符）、蜕变六阶、锻造等级 `progression`、剑意 `proficiency`、天赋 `talent` |
| **装配** | 词条 `affix` + 重铸 + 词条符 `imprint`、宝石 `gem`、附魔孔、词条套装 `relicset`、剑灵 `spirit` / 展示 `spiritdisplay` |
| **经济** | 剑币双账本 `econ`、商会 `shop`、黑市 `blackmarket`、签到 `daily`、拍卖行 `auction`、P2P 锻造订单 `order`、剑魄分解 `dissolve` |
| **社交** | 委托 `commission`、大赛 `contests`、决斗 `duel`、师徒 `mentor`、行会 `guild`、悬赏 `bounty` |
| **留存** | 赛季 `season`、通行证 `pass`、图鉴 `codex`、成就、排行 `ranking`、名人堂 `hall`、称号 `title`、名剑谱 `spectrum`、剧情 `story`、事件 `events` |
| **周目** | 龙税 `contract`、剑冢 `tomb`、秘境 `tombrun`、环境 `env` |
| **工程** | 备份 `backup`、PAPI 占位符、自检 383 项、配置迁移 15 步、`/zhujian config check` |

**结论**：玩家侧玩法已经非常密。再往"加一个新玩法"方向堆，边际价值递减，还容易把经济搞乱。

---

## 二、判断标准

一个拓展值得做，要同时满足：

1. **补的是真缺口**，不是已有功能的变体
2. **成本可控**（不跨 100+ 文件）
3. **能被自检守住**（否则就是下个 bug）
4. **有明确受益者**（玩家 / 服主 / 两者）

按这四条筛，缺口只有两类：**运营侧的可观测性** 和 **社区侧的传播**。

---

## 三、四个候选

| 候选 | 补什么缺口 | 成本 | 风险 | 受益者 | 评分 |
| --- | --- | --- | --- | --- | --- |
| **A. 服主数据看板** `ServerMetrics` | 经济与循环**完全不可观测** | 中（新包 + 3 处埋点） | 低 | 服主 | ★★★★★ |
| **B. 锻造高光回放** `ForgeReplay` | 玩家侧内容/教学 | 高（记录 + 重放两套） | 中 | 玩家 | ★★☆ |
| **C. Discord / Webhook 播报** | 社区运营 | 低（1 个包） | 低 | 服主 | ★★★★ |
| **D. 战力评分与估价** `PowerScore` | 拍卖/订单/排行缺统一价值口径 | 中（权重 + 防刷） | 中 | 玩家 | ★★★ |

**推荐顺序：A → C → D，B 押后。**

理由：A 是**能力缺口**而不是功能缺口。现在整份审计里所有经济结论，都是我拿静态配置 +
马尔可夫链推出来的——"每打一把剑赚多少""强化 +40 要多少把剑"，全是模型值。
真实服务器上产出/消耗是什么曲线、哪个模块在吸币，**一个数字都看不到**。
补上 A 之后，后面所有数值调整都不用再靠推演。

---

## 四、A 的详细设计

### 4.1 为什么现在成本低

两个现成条件：

1. `StatsStore` 已经存了 18 个可聚合计数器——`forged` / `salvaged` / `flawless` /
   `coins` / `streak` / `bestStreak` / `commissionsDone` / `commissionsFailed` /
   `duelWins` / `duelLosses` / `repaired` / `spirits` / `tombs` / `riskMade` /
   `riskLost` / `achvPoints` / `bestStrengthen` / `reputation`。
   聚合这些**不需要新埋点**。
2. 剑币增减只有三处收口（`StatsStore:396` 加、`:410` 扣、`:422` 赛季清零），
   加产出/消耗计数是**单文件 3 行**。

### 4.2 唯一的改动：给钱加上"理由"

现在的签名是 `addCoins(uuid, amount)` / `takeCoins(uuid, amount)`，分不清钱从哪来、
到哪去。加一层重载：

```java
// StatsStore
public long addCoins(UUID uuid, long amount) { return addCoins(uuid, amount, "unknown"); }
public long addCoins(UUID uuid, long amount, String reason) {
    ...
    produced += amount; producedBy.merge(reason, amount, Long::sum);
}

public boolean takeCoins(UUID uuid, long amount) { return takeCoins(uuid, amount, "unknown"); }
public boolean takeCoins(UUID uuid, long amount, String reason) { ... }
```

旧调用点**一行不用改**，理由是 `unknown`。然后逐个把关键调用点补上理由：

```
锻造奖励 / 委托交货 / 大赛名次 / 拍卖成交 / 签到 / 悬赏 / 剑冢挑战  → 产出
商会 / 拍卖税 / 强化费 / 蜕变费 / 宝石费 / 黑市 / 重铸 / 分解 / 行会  → 消耗
```

`sink` 名字统一走一个枚举（`CoinReason`），避免字符串拼错导致桶爆炸。

### 4.3 交付物

**配置**（step16 迁移补段）：

```yaml
metrics:
  enabled: true
  snapshot-interval-minutes: 30   # 多久采一次样
  history-days: 30                # 趋势保留多少天
  top-n: 10                       # 排行榜取前几
  gini-warn: 0.55                 # 基尼系数超过这个值，看板标红提醒
```

**数据**：`metrics-<yyyyMMdd>.yml`，每天一个文件，只增不删（超期删老文件）：

```yaml
samples:
  - at: 1731300000
    online: 12
    totalCoins: 486201
    produced: 128400      # 当日累计产出
    consumed: 96550       # 当日累计消耗
    forged: 87
    breaks: 9
    modules: { shop: 41, gem: 33, strengthen: 22, auction: 7 }
```

**命令**：

| 命令 | 说明 |
| --- | --- |
| `/zhujian admin metrics` | 三页 GUI：经济 / 循环 / 模块热度 |
| `/zhujian admin metrics text` | 文字版，写进控制台日志（后台也能看） |
| `/zhujian admin metrics export` | 把近 N 天样本导成 CSV（丢进表格就能画图） |

权限：`forgemaster.admin.metrics`。

**指标**：

| 页 | 指标 |
| --- | --- |
| 经济 | 剑币总量 / 人均 / 中位数 / 基尼系数（Top10% 占比）/ 30 日净通胀 |
| 循环 | 产出 vs 消耗（分理由）、每日锻造数、品质分布、强化碎裂率、平均评分、蜕变尝试/成功率 |
| 模块 | 各模块调用次数排行、人均使用深度（多少人用过 ≥N 次） |

### 4.4 自检断言（4 条，都能确定性命中）

1. **账恒等**：对同一玩家，`余额 == 初始 + produced - consumed`（用假 UUID 跑一轮）
2. **理由不丢**：`CoinReason` 枚举里每个值都在 `producedBy`/`consumedBy` 里能落桶，
   且不存在拼错的游离桶
3. **除零安全**：0 玩家 / 0 产出时，人均、基尼系数、比率全部返回 0，不是 NaN
4. **快照往返**：写一次快照 → 读回 → 字段齐全且数值一致

### 4.5 它怎么"回本"

做完之后，下面这些以后都不用再靠推演：

- 强化 +40 到底要多少把剑（现在只有模型值 62 把，实际可能 40 也可能 200）
- 蜕变重排后总成本降 43% 到底够不够（服主能看到几天内有没有人真的推到第 6 阶）
- 哪个 sink 在吸币（如果 80% 消耗都发生在强化，那调宝石费是白费劲）
- 基尼系数高不高（少数人囤币 → 该加累进税还是加产出）

---

## 五、C 的详细设计（短）

```yaml
webhook:
  enabled: false
  url: ""                        # Discord / Slack 的 HTTP 入站地址
  username: "铸剑大师"
  events: [legendary, hall-fame, season-end, strengthen-60, contract-default]
  queue-size: 64                 # 有界队列，满了直接丢（绝不阻塞主线程）
  retry: 2
```

工程要点（都是老坑）：

1. **必须异步**：`CompletableFuture` + 有界队列，主线程只入队
2. **必须限流**：同一事件 10 秒内只发一次（防止刷屏 + 防止把 url 打到 429）
3. **转义**：Jackson 不在依赖里，手写 JSON 转义（引号 / 反斜杠 / 控制字符 / 非 BMP）
4. **失败静默**：网络失败只记 debug，不在控制台刷红

断言：URL 空时零发送、队列满时不阻塞（用计时断言 < 50ms）、JSON 转义后能被重新解析。

---

## 六、D 的概要（押后到 A 之后）

统一战力分 `PowerScore`：

```
power = 品质基准 × 评分系数 × (1 + 强化系数) × 蜕变连乘 × (1 + 词条和) × (1 + 宝石和) × (1 + 剑灵)
```

用途：拍卖推荐价、订单定价提示、排行、名片。

**为什么押后**：权重需要真实数据校准。等 A 上线跑两周，能看到"什么配置的剑真的有人抢"，
再定权重才不是拍脑袋。否则很容易做出一个"高分不等于好用"的分数，玩家会不信。

---

## 七、B 为什么押后

锻造回放（记录高光锻造的输入序列并重放）是很好的内容，但：

- **记录侧**：加热曲线每秒 20 采样 × 7 工序，一把剑几百个点，要压缩和切片
- **重放侧**：要在聊天栏或 GUI 里"演出"，需要一套独立的渲染状态机
- **和现有演出冲突**：`anvil-forge.display` 已经有一套盔甲架姿态演出

成本是 A 的 3 倍以上，收益是内容和教学。等 A 的数据证明"玩家确实在高分锻造上停留"再做。

---

## 八、路线图

| 切片 | 内容 | 可独立交付 |
| --- | --- | --- |
| **1** | `CoinReason` + `StatsStore` 产出/消耗计数 + `/zhujian admin metrics text` | ✅ 服主立刻能在控制台看到经济曲线 |
| 2 | 每日快照 `metrics-<date>.yml` + 30 日趋势 + 基尼系数 | ✅ |
| 3 | 三页 GUI + CSV 导出 | ✅ |
| 4 | C：Webhook 播报 | ✅ |
| 5 | D：战力评分（用切片 1-3 的数据校准权重） | ✅ |
| 6 | B：锻造回放 | 视前五切片反馈 |

切片 1 改动面：`econ/CoinReason`（新）、`stats/StatsStore`（3 处）、
`command/ForgingCommand`（1 个子命令）、`selftest`（2 条断言）、step16。

---

## 九、明确不做的

| 想法 | 为什么不做 |
| --- | --- |
| 挂机锻造队列 / 批量锻造 | 直接绕过七工序的输入，把玩法变成点按钮 |
| 第二套货币 | 现有双账本已经够绕，再加一本只会更乱 |
| 全量 i18n（messages.yml） | 168 个文件里的中文硬编码，改动面太大，受益面（非中文服）未知 |
| 新增玩家侧玩法模块 | 玩家侧已经很密（51 个包），再加是稀释而不是丰富 |
| 跨服数据同步 | 需要 Redis/数据库层，与"单文件持久化 + 同步写盘"的现有架构正面冲突 |

---

## 十、要拍板的一件事

切片 1 的埋点理由需要定**归组粒度**。两种选择：

- **粗（8~10 个桶）**：锻造 / 委托 / 大赛 / 拍卖 / 商店 / 强化 / 蜕变 / 宝石 / 黑市 / 其它
  → 看板一眼看懂，够用
- **细（每个模块一个桶）**：能精确到"词条符剥离费"
  → 数据更全，但看板会变成 30 行，且新模块容易漏登记

建议**先粗后细**：`CoinReason` 用枚举，细化只是加枚举值，不返工。