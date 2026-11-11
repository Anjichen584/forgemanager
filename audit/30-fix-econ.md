# 30 · 经济闭环缺陷修复单（v2.23.1）

对应审计：`audit/20-bughunt-econ.md`
可写范围：`auction/`、`commission/`、`bounty/`、`dissolve/`、`econ/`、`order/`（仅过期退款）、`guild/GuildService.java`
未运行 `mvn`（按要求交由 Lead 统一编译）。未改 `config.yml` 与任何禁区文件。

改动文件清单（9 个）：

| 文件 | 修的是 20 号报告里的哪一条 |
|---|---|
| `econ/EconomyBridge.java` | P1 双账本（新增离线按名收发入口） |
| `econ/Wallet.java` | P1 双账本（新增 `giveTo` / `takeFrom` / `ledgerName`） |
| `auction/AuctionStore.java` | P0-1（新增 `replaceMail`） |
| `auction/AuctionManager.java` | P0-1、P0-2、P1 双账本 |
| `bounty/BountyManager.java` | P1 悬赏 20 分钟换日 + 已领不落盘 |
| `commission/CommissionManager.java` | P1 委托从不落盘、P2 `purgeExpired` 口径、P1 双账本 |
| `dissolve/DissolveService.java` | P1 剑尘 64 截断、`dustOf` 溢出 |
| `order/OrderService.java` | P2 过期退款漏发（仅此一条） |
| `guild/GuildService.java` | P2 解散逐人退款漏发 |

---

## P0

### P0-1 拍卖 `claim` 崩溃复制物品

- **位置**：`auction/AuctionManager.java:361-399`、`auction/AuctionStore.java:237-258`（`replaceMail` 新增）
- **根因**：`takeMail()` 先把内存邮箱整体删掉、只 `markDirty()`，再往背包 `addItem`。auction.yml 是 5 秒异步冲刷，这段窗口崩溃 → 磁盘邮箱仍是满的 → 重启后再领一次。
- **改法**：顺序反转。先把能塞的塞进背包，塞不下的（`addItem` 返回的 leftover）收集成 `remain`，然后 `replaceMail(uuid, remain)` 写回内存并**立即 `store.save()` 同步落盘**。
- **改后行为**：磁盘上只有两种状态——「一件都没领（物品还在邮箱）」或「已领走的已从邮箱扣掉、只剩塞不下的」。背包满时提示「邮箱里还剩 N 件」，绝不复制。该路径不再依赖 5 秒异步窗口。
- **自检建议**：单测 `replaceMail(empty)` 后 `mailOf` 返回空表且文件里该 `mail.<uuid>` 段消失；模拟「背包塞满 → claim」后 `mailOf` 数量不为 0。

### P0-2 单据与剑币分文件异步落盘 → 造币 / 吞币

- **位置**：`auction/AuctionManager.java:110-152`（新增 `commitStoreNow` / `commitMoneyNow` / `warnLost`）、`:196-212`（挂单）、`:237-268`（竞价）、`:297-337`（一口价）、`:341-359`（下架）、`:403-445`（到期结算）
- **根因**：auction.yml 5 秒冲刷、data.yml 5 分钟冲刷（`ForgingMasterPlugin.java:119`）。一次成交同时改两边，中间那条几分钟的窗口只落一侧就能出两种事故：只落 auction.yml → 货已进买家邮箱但钱没扣（买家白拿）；只落 data.yml → 挂单还在，重启后再结一次（卖家二次收款 = 造币）。
- **改法**：引入两个同步提交点，并在每条路径上按固定顺序串起来：
  1. `commitMoneyNow()` = `stats().save()`（同步写 data.yml）
  2. `commitStoreNow()` = `store.save()`（同步写 auction.yml）

  各路径的顺序（**这是本次修复的核心，请逐条核对**）：
  | 路径 | 顺序 |
  |---|---|
  | 挂单 `sell` | 扣手续费 → 落钱 → 落单据 |
  | 竞价 `bid` | 扣买家款 → 落钱 → 改单据 → 落单据 → 退旧买家 → 落钱 |
  | 一口价 `buyout` | 扣买家款 → 落钱 → finish+落单据 →（竞态则全退+落钱）→ 退次高 → 付卖家 → 落钱 |
  | 下架 `cancel` | finish → 落单据（不动钱） |
  | 到期结算 `settleExpired` | finish → 落单据 → 付卖家 → 落钱 |
  | 领取 `claim` | 入包 → `replaceMail` → 落单据（不动钱） |
- **改后行为**：任意一步崩溃只可能是**漏发 / 漏退**（每种都打 `warning`，带拍品号，可人工补），**不可能**二次结算、二次退款或凭空造币。窗口从「最长 5 分钟」压到「两次相邻文件写入之间」。
- **自检建议**：对 `bid` 做一次「扣款后立刻杀进程」的实验，重启后 `auction.yml` 里应无该次出价而 `data.yml` 里买家已扣（漏的单向）；反向实验不应出现「出价已记而钱未扣」。

---

## P1

### P1 悬赏按 MC 昼夜换日、已领名单不落盘 → 20 分钟刷一次赏金

- **位置**：`bounty/BountyManager.java:41-127`（新增 `file` / `load` / `save`，`worldDay()` 改口径）、`:193-210`（领赏顺序）
- **根因**：`worldDay()` 用 `getFullTime() / 24000`（MC 一日 = 现实 20 分钟），换日即 `claimed.clear()`，且 `claimed` 是纯内存 Set。重启也清空。
- **改法**：
  1. `worldDay()` → `LocalDate.now(ZoneId.systemDefault()).toEpochDay()`，与 `BlackmarketService` / `DailyRewardService` 同口径。
  2. 新增 `bounty-data.yml`（`day` + `claimed`），构造时 `load()`（`BountyManager` 的构造发生在 onEnable，`ForgingMasterPlugin.java:150`，此处做 IO 安全）；换日与领赏两个低频点各 `save()` 一次。
  3. 领赏顺序改为「收剑 → 记已领并落盘 → 发钱 → 落钱」，发钱失败回滚不了已领标记但会打 warning 让人工补。
- **改后行为**：一天（现实日）每个玩家每条悬赏只能领一次，跨重启保持；`/time set` 不再影响。
- **自检建议**：连续两次 `claim` 同一 index → 第二次必须拒绝；手动把 `bounty-data.yml` 的 `day` 改成昨天 → 重新 `load()` 后应能再领一次（换日语义正确）。

### P1 委托状态从不落盘 → 重启后已交付委托复活

- **位置**：`commission/CommissionManager.java:125-166`（`ensureFor` 加 `changed` + `save()`）、`:373-390`（`deliver` 销单后 `save()`）、`:411-424`（`reroll` 后 `save()`）、`:430-456`（`purgeExpired`）
- **根因**：`save()` 的唯一调用点在 `ForgingCommand.java:2272` 的自检块里，生产路径从未落盘。
- **改法**：三条会改动委托列表的路径全部接上同步 `save()`，且顺序固定为「先销单落盘、后发奖」；`ensureFor` 只有真的增删过才写盘，避免每次开面板整档重写。
- **改后行为**：交付后崩溃 → 单已销、奖没发（漏，可人工补）；不会再出现「单还在、剑币声望再领一次」。
- **自检建议**：交付一张委托 → 重启 → 该委托必须不在列表里。

### P1 剑尘 64 截断

- **位置**：`dissolve/DissolveService.java:105-125`（`createDust` 保留 64 上限并加注释）、`:193-208`（新增 `giveDust` 多叠）、`:210-224`（`dustOf` 改 `long` 中间量 + `int` 饱和钳制）、`:260-277`（`dissolve` 改调 `giveDust`）
- **根因**：`dissolve()` 只造一叠，`createDust` 钳 64。`+20 / 5 阶 / 神话` = 260，实得 64。
- **改法**：新增 `giveDust`，按 64 一叠循环造，塞不下的掉落；`dustOf` 中间量升 `long` 并在 `(int)` 强转前钳到 `[0, Integer.MAX_VALUE]`（原来 `(int)` 溢出会变负数，等于「分解倒扣」）。
- **改后行为**：到手数量精确等于确认文案里的数字。
- **自检建议**：构造 `dustOf = 260` 的假数据，`giveDust` 后背包+掉落的总剑尘数量必须是 260。

### P1 双账本分裂（收敛到 `Wallet`）

- **位置**：`econ/EconomyBridge.java:266-360`（新增 `balanceByName` / `withdrawByName` / `depositByName`）；`econ/Wallet.java:141-240`（新增 `ledgerName` / `giveTo` / `takeFrom`）；已切换的调用点见下表
- **根因**：`Wallet` 走 `economy.coin-sinks` 口径，而拍卖/订单/商会/悬赏/大赛/委托硬编码 `plugin.stats()`。配成 `server` 后两本账各自记账。
- **改法**：不在各模块内部重写判定，只在 `Wallet` 加「按 UUID + 离线名收发」的入口，由它决定记哪本账；`EconomyBridge` 补按玩家名的读写（Vault/CMI 的接口本来就是 String 名，离线可用）。旧 `take/give(Player,…)` 与 `giveTo/takeFrom(UUID,String,…)` 是同一套判定。
- **已切换到 `Wallet` 的调用点（本次改动）**：
  | 位置 | 用途 |
  |---|---|
  | `auction/AuctionManager.java:197` | 挂单手续费 |
  | `auction/AuctionManager.java:241` | 竞价冻结 |
  | `auction/AuctionManager.java:259` | 旧买家退款 |
  | `auction/AuctionManager.java:307` | 一口价扣款 |
  | `auction/AuctionManager.java:315` | 一口价竞态退款 |
  | `auction/AuctionManager.java:325` | 次高出价退款 |
  | `auction/AuctionManager.java:331` | 一口价卖家货款 |
  | `auction/AuctionManager.java:428` | 到期结算卖家货款 |
  | `bounty/BountyManager.java:200` | 悬赏赏金 |
  | `commission/CommissionManager.java:387` | 委托交货剑币 |
  | `order/OrderService.java:358` | 订单过期退款（本次范围仅此一条） |
- ****仍未切换（不在本次可写范围，交给你决定）**：
  | 位置 | 用途 | 备注 |
  |---|---|---|
  | `shop/ShopManager.java:248` | 商会购货扣款 | `shop/` 不在我的可写范围 |
  | `shop/ShopManager.java:153` | 商会余额显示 | 与黑市 GUI（`BlackmarketGui.java:146` 用 `wallet`）显示的是两本账 |
  | `blackmarket/BlackmarketService.java:163` | 黑市购货 | 已走 Wallet，**无需改** |
  | `order/OrderService.java:222` | 发单托管扣款 | 越界（只授权改过期退款那条） |
  | `order/OrderService.java:294` | 交付悬赏发放 | 同上 |
  | `order/OrderService.java:333` | 取消订单退款 | 同上 |
  | `contests/ContestManager.java:247` | 大赛名次奖励 | `contests/` 不在可写范围 |
  | `guild/GuildService.java:670` | 行会金库存入 | 金库是内部账目，建议保持剑币 |
  | `guild/GuildService.java:785` | 行会周赛奖励 | 同上，建议保持剑币 |
  | `guild/GuildQuests.java:414` | 行会委托奖励 | `guild/GuildQuests.java` 不在可写范围 |
- **改后行为**：本次改到的 11 个点在同一口径下收付；混账只剩上表未切换的 6 处。
- **副作用与残余风险（请核对）**：`giveTo` 在「已接外部经济但打款失败」时会**回退到剑币兜底**并打 warning——宁可落到另一本账并留痕，也不让钱凭空消失。`takeFrom` 不做这种回退（避免跨账重复扣款）。

---

## P2

### P2 行会解散逐人退款 → 部分成员漏退

- **位置**：`guild/GuildService.java:619-663`
- **根因**：循环里「加钱（内存）→ `removeMember` + `save()`（同步写 guild-data.yml）」，成员被移出会立刻落盘而钱要等 5 分钟。中途崩溃 → 后半段成员的钱全丢且已不在 `members` 里。
- **改法**：先给所有人发完并 `stats().save()`，同时把 `g.treasury` 清零，再走拆除流程。
- **改后行为**：崩溃只可能停在「钱已发、行会还在」；重试时 `treasury == 0` 不会二次分赃。
- **自检建议**：手动把 `treasury` 设成「不能被人数整除」的值解散，各成员到手之和 + 余数必须等于原 treasury。

### P2 委托 `purgeExpired` 与 `ensureFor` 口径不一致

- **位置**：`commission/CommissionManager.java:430-456`
- **根因**：`purgeExpired` 只 `removeIf(expired)`，不记 `countCommission(false)`、不断连锻链；`ensureFor` 里两者都做。玩家可以挑路径规避断链。
- **改法**：`purgeExpired` 复用 `ensureFor` 的清理语义（记失败 + `streaks().onBreak` + 落盘）。
- **改后行为**：逾期一律断链并记失败，与走哪条清理路径无关。

### P2 订单过期退款漏发

- **位置**：`order/OrderService.java:357-372`
- **根因**：`o.state(EXPIRED) + save()`（orders.yml 同步）之后才 `addCoins` + `markDirty`（data.yml 最长 5 分钟）。中途崩溃 → 单据已结、悬赏没入账。
- **改法**：改走 `wallet().giveTo(poster, posterName, bounty, REFUND)`，随后立即 `stats().save()`；失败打 warning 带订单号。
- **改后行为**：泄漏窗口从分钟级压到「两次相邻写文件之间」，且方向仍是「漏发」（先结案后发钱，反了就是重复退款）。

---

## 对账与幂等：本次新增/修改的写入顺序

统一不变式：**任何「货 / 钱」的两段变更，最后的落盘顺序都选「漏发」而不是「多给」**。

| # | 路径 | 写入顺序 | 崩溃后的唯一可能 |
|---|---|---|---|
| 1 | 拍卖挂单 | 扣费 → data.yml → auction.yml | 钱扣了单没挂上 |
| 2 | 拍卖竞价 | 扣买家 → data.yml → auction.yml → 退旧买家 → data.yml | 买家白付 / 旧买家漏退 |
| 3 | 拍卖一口价 | 扣买家 → data.yml → auction.yml → 退次高 → 付卖家 → data.yml | 卖家漏收 / 次高漏退 |
| 4 | 拍卖到期结算 | auction.yml（移除挂单+货进邮箱）→ 付卖家 → data.yml | 卖家漏收 |
| 5 | 拍卖下架 | auction.yml | 无（不动钱） |
| 6 | 拍卖领取 | 入包 → auction.yml（`replaceMail`） | 一件都没领 |
| 7 | 悬赏领赏 | 收剑 → bounty-data.yml（已领）→ 发钱 → data.yml | 剑收了钱没到 |
| 8 | 委托交付 | 扣剑 → commissions.yml（销单）→ 发奖 | 单销了奖没发 |
| 9 | 委托换单 | commissions.yml | 换单未生效 |
| 10 | 委托过期清理 | commissions.yml（记失败+断链） | 逾期未记 |
| 11 | 订单过期退款 | orders.yml（结案）→ 发钱 → data.yml | 悬赏漏退 |
| 12 | 行会解散 | 全员发钱 → data.yml → 清零 treasury → guild-data.yml | 钱已发、行会还在（重试不重复） |

幂等性：

- `settleExpired` 依赖 `store.finish()` 的返回值（挂单是否由本次移除）作为唯一开关，配合「finish 后立刻落盘」，重启不会二次结算。
- `claim` 依赖 `replaceMail` 的**整体替换**而不是「按条删除」，重复调用第二次拿到的是空表，天然幂等。
- 悬赏的 key 含日期前缀（`epochDay:uuid:index`），换日前后互不干扰，且 `load()` 会丢弃非当前日的残留键。
- 行会解散把 `treasury` 清零并落盘，重入时 `share = 0`。

## 未修 / 存疑（不在可写范围或需你决策）

1. **`stats/StatsStore.addCoins` 负数无守卫与溢出清零**（`StatsStore.java:408-419`）——`stats/` 不在我的可写范围。建议改成饱和加法并对 `amount <= 0` 直接返回。
2. **签到 / 黑市「先发货后记账」**（`DailyRewardService.java:130-155`、`BlackmarketService.java:178-182`）——`blackmarket/` 不在可写范围。
3. **`pass/SeasonPassService` 的付费轨发错轨、先记已领后发奖、买断先扣币后落盘**——你已接手，我未动。
4. **`shop/`、`contests/` 的硬编码 `stats()`**——见上表「仍未切换」。
5. **`AuctionManager.claim` 的部分入包计数**：一件可堆叠物品只塞进去一部分时，`moved` 仍计 1，提示里的「已领取 N 件」会略高；`remain` 数量是准确的，不影响资产安全。想精确可改成按数量差计算，属体验项。
6. **`EconomyBridge` 的 `has()` + `withdraw()` 非原子**（`EconomyBridge.java:243-249`，旧有逻辑未动）：Vault 侧并发扣款理论上可透支，本插件内所有扣款都在主线程串行，实际不触发。