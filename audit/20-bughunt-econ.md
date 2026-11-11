# 20 · 经济与奖励闭环 Bug Hunt（只读审计）

- 范围：`econ/`、`stats/`、`shop/`、`auction/`、`order/`、`bounty/`、`blackmarket/`、`dissolve/`、`commission/`、`pass/`、`guild/`（奖励发放）、`contests/`（奖励发放）
- 版本：v2.23.0 · Paper 1.21.11 / Java 21
- 方式：逐条顺调用链读到落盘与发奖两侧（含 GUI 路由与命令入口）。未改动任何源码与 config.yml。
- 落盘状态：**分批落盘，本版覆盖 16 条**（P0 2 条 / P1 8 条 / P2 6 条）。未完成项见文末「待确认」。

> 关键背景数据（后面多条依赖它）：
> - `ForgingMasterPlugin.java:119` → `stats.startAutoSave(20 * 300)`，即 data.yml **5 分钟**才冲刷一次。
> - `AuctionStore.startAutoFlush()` → auction.yml **5 秒**一轮。
> - 两份文件各自独立落盘、无事务边界 → 这是下面 P0-2 的根因。

---

## P0

### [P0] 拍卖行 `claim` 先清空邮箱再塞物品，崩溃即物品复制
- 位置：`src/main/java/com/forgemaster/auction/AuctionManager.java:287-299`（`store.takeMail` 在 292，`addItem` 在 294）；`src/main/java/com/forgemaster/auction/AuctionStore.java:231-235`
- 机制：`claim()` 第一步 `store.takeMail(uuid)` 把该玩家邮箱**从内存里整体移除**并只 `markDirty()`（不写盘），第二步才把物品 `addItem` 进背包。auction.yml 由 5 秒异步冲刷落盘；在这 5 秒内崩溃 / `stop` 前 OOM / 被 kill，磁盘上的 `mail.<uuid>` 仍然完整存在，而内存里已经没了。
- 触发：玩家攒多件邮件 → `/zhujian auction claim`（或点邮箱格）→ 同一 tick 内服务器崩溃 / 被面板强杀 / 断电。
- 影响：**物品复制**。重启后邮箱恢复，玩家再领一次；第一次进背包的物品已随 world save 写进 playerdata（玩家背包保存频率远高于 5 秒）。
- 最小修复：把「给物品」放在「清邮箱」之前，且邮箱清空要同步落盘；或先 `addItem` 收集 leftover，再把成功塞入的部分从内存邮箱里按索引删除，最后 `store.save()`：
  ```java
  public String claim(Player p) {
      List<ItemStack> items = store.mailOf(p.getUniqueId());
      if (items.isEmpty()) return "<gray>邮箱是空的。";
      List<ItemStack> remain = new ArrayList<>();
      for (ItemStack it : items) {
          for (ItemStack rest : p.getInventory().addItem(it).values()) {
              remain.add(rest);                    // 塞不下的留在邮箱，不落地
          }
      }
      store.replaceMail(p.getUniqueId(), remain);  // 新增：按剩余重写
      store.save();                                // 归属同步落盘后才算领完
      return "<green>已领取 " + (items.size() - remain.size()) + " 件物品。";
  }
  ```

### [P0] 拍卖行的「单据归属」与「剑币变动」分两个文件异步落盘，崩溃后可造币
- 位置：`src/main/java/com/forgemaster/auction/AuctionManager.java:249-265`（一口价）、`:314-320`（到期结算）、`:192-207`（竞价）；`AuctionStore.java:140-142`（只标脏）、`StatsStore.java:808-817`（5 分钟一存）
- 机制：一次成交要改两本账——auction.yml（挂单消失 / 物品进邮箱）和 data.yml（买家扣款、卖家入账、次高出价退款）。`store.finish()` 只 `markDirty()`，`stats().addCoins/takeCoins` 也只改内存。auction.yml 5 秒落盘、data.yml 5 分钟落盘，**两者之间最长有 ~5 分钟的窗口只落一侧**。
  - 只有 auction.yml 落了：挂单已消失、物品已进买家邮箱（买家能领到货），但买家的 `takeCoins` 没落盘 → 重启后买家钱还在。**买家白拿物品**；旧买家冻结款也没退（他的扣款早已落盘）→ 旧买家净损失。
  - 只有 data.yml 落了（`settleExpired` 的 `addCoins(seller)` 已落盘而 `finish` 未落盘）：重启后挂单还在 → 下一轮 `settleExpired` 再结一次 → **卖家二次收款**，凭空造币。
- 触发：任何一次成交/结算后 5 分钟内发生崩溃或被强杀；服主重启时若 `onDisable` 未完整执行（`kill -9`、面板强制停止）概率显著上升。
- 影响：**经济复制 / 玩家净损失**，且账面上无法自证（两文件都"没报错"）。
- 最小修复：成交这类「同时动两本账」的路径改成「先落 auction.yml，再动钱并立即落 data.yml」：
  ```java
  if (!store.finish(id, p.getUniqueId(), l.item())) { ... }
  store.save();                      // 单据侧先落地
  plugin.stats().addCoins(l.seller(), gain, CoinReason.AUCTION);
  plugin.stats().save();             // 钱侧立刻落地（StatsStore.save() 已是同步 + 写锁）
  ```
  `settleExpired()` 里的 `addCoins` 同样在循环外补一次 `plugin.stats().save()`。

---

## P1

### [P1] 赛季通行证付费轨领到的其实是免费轨奖励，付费轨奖励永不发放
- 位置：`src/main/java/com/forgemaster/pass/SeasonPassService.java:625-628`（`claimPremium`）；对照 `:406-408`（`premiumReward`）
- 机制：`claimPremium` 取的是 `Level lv = level(levelNo)`，然后 `grantReward(player, lv.reward)`——`lv.reward` 是 `season-pass.levels[].reward`，即**免费轨**那份。全项目 `premiumReward(int)` 只在 `PassGui.java:291` 和 `:319` 被调用，**只用于画图标 Lore**，发奖路径一次都没用。`defaultPremiumRewards()`（`:241-254`，第 1 级 800 币、第 10 级 8000 币 + 2 张符）从来发不出去。
- 触发：任何玩家花 5000 剑币 `buyPremium` 后逐级领付费轨。
- 影响：**功能失效 + 白花剑币**。玩家付费轨领到的与免费轨完全相同；若某级免费轨 reward 为空（`isEmpty()`），则"领取成功"但什么都没发，同时该级被记成已领（见下一条）。
- 最小修复：
  ```java
  Level lv = level(levelNo);
  progress(uuid, seasonNumber()).premiumClaimed.add(levelNo);
  List<String> got = grantReward(player, premiumReward(levelNo));  // 改这一行
  ```

### [P1] 两轨领奖都是「先记已领、再发奖」，发奖抛异常就是永久吞奖励
- 位置：`src/main/java/com/forgemaster/pass/SeasonPassService.java:561-563`（免费轨）、`:626-628`（付费轨）；发奖体 `:576-606`
- 机制：`claimed.add(levelNo)` / `premiumClaimed.add(levelNo)` 在 `grantReward(...)` **之前**执行。`grantReward` 里 `player.getInventory().addItem(...)`、`charm.setAmount(r.charmAmount)`、`new ItemStack(m, amount)` 都可能抛（`addItem` 对非法堆叠会 `IllegalArgumentException`；`Reward` 的数量只做了 `Math.max(1, x)` 下界钳制，`SeasonPassService.java:65-67`，没有上界）。一旦抛出，`claimed` 已进内存，异常会不会传到定时 `flush()` 取决于调用点——而 `save()` 在该方法末尾，抛异常时**不会执行**；下一轮 `flush()`（`ForgingMasterPlugin.java:296`）会把"已领但没发奖"的状态写进 pass-data.yml。
- 触发：`pass.premium.rewards.<级>.charm.amount` 或 `material.amount` 配成大于该物品最大堆叠的数（例如 `charm: {amount: 5}`，符的最大堆叠是 1）；或玩家背包处于极端状态导致 `addItem` 抛。
- 影响：**玩家永久损失该级奖励**（等级被标记已领，后台无补偿记录）。
- 最小修复：先发奖、后记已领，并用 `try/finally` 保证状态只在发奖成功后提交：
  ```java
  List<String> got = grantReward(player, lv.reward);
  progress(uuid, seasonNumber()).claimed.add(levelNo);   // 挪到发奖之后
  save();
  ```
  同时在 `Reward` 构造里给 `materialAmount` / `charmAmount` 加上界：`Math.min(64, Math.max(1, x))`。

### [P1] `CommissionManager.save()` 在生产路径从未被调用，委托交付状态永不落盘
- 位置：`src/main/java/com/forgemaster/commission/CommissionManager.java:338-404`（`deliver`，全程无 `save()`）、`:407-424`（`reroll`，无 `save()`）、`:130-157`（`ensureFor` 生成新单，无 `save()`）、`:92-116`（`save()` 本体）
- 机制：`save()` 的唯一调用点是 `ForgingCommand.java:2272`，而那段代码在自检块里（`UUID.nameUUIDFromBytes("ForgingMasterSelfTest")`，随后紧跟 `load()`）。`load()` 在 `onEnable` 里读 commissions.yml（`:63-90`），所以：
  - 文件不存在 → 每轮启动都是全新委托，`deliver` 掉的单子从不消失，表现为"每次重启委托全换新"；交付奖励不会重复，但状态全部丢失。
  - 文件存在（老版本或自检写出的快照）且条目 `expires` 未到 → 重启后 `load()` 把**已交付过的委托原样读回来**，`ensureFor` 不会清理未过期的条目。
- 触发：玩家交付委托（`ForgingListener`→`deliver`）后，服务器在本次生命周期内重启且 commissions.yml 里保留着未过期条目。
- 影响：**重复领取**（同一张委托的剑币 + 声望 + 报酬物可再领一次）；以及委托系统的状态持久化整体失效。
- 最小修复：`deliver()` 在 `stats` 结算前插入 `save()`，`reroll()` / `ensureFor()` 末尾同样补 `save()`（`ensureFor` 里 `list.add` 之后）：
  ```java
  rest.remove(index);
  save();                                  // 先销单落盘，再发奖
  stats.countCommission(player.getUniqueId(), true);
  ```

### [P1] 每日悬赏按 MC 昼夜（20 分钟）换日并清空已领名单，可无限刷赏金
- 位置：`src/main/java/com/forgemaster/bounty/BountyManager.java:69-71`（`worldDay()` = `getFullTime() / 24000`）、`:56-67`（`current()` 换日即 `claimed.clear()`）、`:45`（`claimed` 纯内存 `Set`）、`:138-139`
- 机制：`getFullTime()` 每 24000 tick（现实 20 分钟）加 1。于是 `worldDay != day` 每 20 分钟成立一次，`claimed.clear()` + `roll()` 重掷 → 已领记录被清空，且三条悬赏换新。类注释写的是"按世界天数做种子，每天给全服刷 3 条赏金""同一自然日所有人看到同一组悬赏"，与实现不符；同项目内 `BlackmarketService`（`:26-31`）与 `DailyRewardService` 已明确改用 `LocalDate` 并写下了"不能用 dayTime"的理由。
- 触发：玩家手握达标的强化剑，每 20 分钟（或重启后立刻，因为 `claimed` 只在内存）重复 `claim` 同一档位；`/time add` 也能立刻触发换日。
- 影响：**刷币**（用一把剑反复换赏金，赏金 `base 400 × (1+0.8q) × …` 单次可达数千）；同时"每日"悬赏语义完全失效。
- 最小修复：日期口径换成现实日，且已领名单落盘：
  ```java
  private long worldDay() { return java.time.LocalDate.now().toEpochDay(); }
  // claimed 改成「uuid:index → 日期字符串」，随 BountyManager 的存档落盘，跨重启不清
  ```

### [P1] 分解剑魄的剑尘产出被 64 上限截断，高强化/神话剑凭空损失
- 位置：`src/main/java/com/forgemaster/dissolve/DissolveService.java:106-107`（`createDust` 里 `Math.min(64, amount)`）、`:235`（只调一次 `createDust`）、产出公式 `:92-96`
- 机制：`dustOf = floor((base + per-level×level + per-stage×stage) × 品质系数)`。默认值下 base=5、per-level=1、per-stage=8、MYTHIC 系数 4：一把 `+20 / 蜕变 5 阶 / 神话` 的剑 = (5+20+40)×4 = **260**，而 `createDust` 把数量钳到 64 并只造一叠。
- 触发：分解任何 `dustOf(...) > 64` 的剑（高强化 + 高锻阶 + 史诗/传说/神话基本都会命中）。
- 影响：**少发（玩家资产损失）**，最高可吞掉 ~75% 产出；`/zhujian dissolve` 的确认文案还会照实显示 "剑尘 ×260"，与实际到手 64 不符。
- 最小修复：`dissolve()` 里按最大堆叠拆叠发放（顺手 reuse 同文件的掉落逻辑）：
  ```java
  int remain = dust;
  while (remain > 0) {
      int n = Math.min(64, remain);
      for (ItemStack rest : player.getInventory().addItem(createDust(plugin, n)).values()) {
          player.getWorld().dropItemNaturally(player.getLocation(), rest);
      }
      remain -= n;
  }
  ```

### [P1] 双账本分裂：黑市/签到/通行证走 `Wallet`（可切 Vault），拍卖/订单/商会/悬赏/大赛硬编码 `StatsStore`
- 位置：`econ/Wallet.java:94-100,121-128`；走 Wallet 的：`blackmarket/BlackmarketService.java:163-164`、`blackmarket/DailyRewardService.java:128-130`、`pass/SeasonPassService.java:507-509`；走 `stats()` 的：`auction/AuctionManager.java:152,192,249`、`order/OrderService.java:222,294,333,358`、`shop/ShopManager.java:248,153`、`bounty/BountyManager.java:139`、`contests/ContestManager.java:247`、`commission/CommissionManager.java:381`
- 机制：`economy.coin-sinks` 配成 `server` 后，黑市/签到/付费轨买断扣的是 Vault 金币，而商会、拍卖挂单费、订单托管、行会金库仍然按剑币扣。UI 文案也跟着分裂：`BlackmarketGui.java:146` 显示 `wallet().coins()`（Vault 余额），`ShopManager.java:153` 显示 `stats().coins()`（剑币余额）——同一玩家在两个界面看到两个"剑币余额"。
- 触发：服主按 `Wallet` 类注释的建议把 `economy.coin-sinks` 设为 `server`。
- 影响：**功能失效**——玩家在黑市"有币买不起"（Vault 空）而在商会"有币"；退款也可能进错账（`Wallet.give` 与当初扣费口径不一致时就跨账）。若口径在挂单期间被 `/zhujian reload` 切换，退款直接进另一本账 = 造币。
- 最小修复：把所有 `plugin.stats().takeCoins/addCoins` 的**玩家钱包**调用统一换成 `plugin.wallet()`（托管/结算类内部账目才留 `stats()`），并让两个 GUI 的余额显示都走 `wallet()`。

### [P1] 付费轨买断「先扣币、后落盘」，落盘失败或崩溃即白花 5000 剑币
- 位置：`src/main/java/com/forgemaster/pass/SeasonPassService.java:509-516`
- 机制：`wallet.take(...)`（同步改 stats 内存）→ `progress(...).premiumBought = true` → `save()`。`save()` 内部 `catch (IOException)` 只打 warning（`:300-303`），**不抛也不回滚**；即使 `save()` 成功，从 `take` 到 `save` 之间的崩溃也会让"已扣币"留在 data.yml（5 分钟内由自动保存冲刷）而"已解锁"没写进 pass-data.yml。
- 触发：磁盘满 / 权限问题导致 `yml.save` 抛 IOException；或买断瞬间崩溃。
- 影响：**白花剑币**（钱扣了、轨没解锁），且玩家无法自证，只能人工补。
- 最小修复：先置标记并落盘，扣币失败则回滚标记：
  ```java
  progress(uuid, seasonNumber()).premiumBought = true;
  save();                                     // 归属先落地
  if (cost > 0 && !wallet.take(player, cost, CoinReason.OTHER_OUT)) {
      progress(uuid, seasonNumber()).premiumBought = false;
      save();
      ...提示...
      return false;
  }
  ```

---

## P2

### [P2] 签到与黑市限购都是「先发货、后记录」，崩溃窗口内可重复领取
- 位置：`src/main/java/com/forgemaster/blackmarket/DailyRewardService.java:130-155`（发剑币/物品在 130-152，`store.setSignin` 在 154，`store.save()` 在 155）；`blackmarket/BlackmarketService.java:178-182`（发货在 178-180，`recordPurchase` 在 181，`save()` 在 182）
- 机制：奖励发放与"今日已签 / 已购次数"落盘之间隔着若干 tick 的工作（`addItem`、`dropItemNaturally`），且 `DailyStore.save()` 是整份重建写盘。窗口内崩溃 → 磁盘上 `signin.last` / `market.<uuid>.<date>` 还是旧值。
- 触发：签到/黑市购买后立刻崩服或被强杀。
- 影响：**重复领取**（同一天再签一次 / 超出 `daily-limit` 再买）。
- 最小修复：把状态写入+落盘提到发奖之前，发奖失败再回滚（黑市则把 `recordPurchase`+`save()` 挪到 `wallet.take` 成功之后、`addItem` 之前）。

### [P2] `StatsStore.addCoins` 对负数金额无守卫，且溢出会静默清零
- 位置：`src/main/java/com/forgemaster/stats/StatsStore.java:408-419`
- 机制：`st.coins = Math.max(0, st.coins + amount)`。`amount < 0` 时不走 `takeCoins` 的余额校验、也不进 `consumedBy` 流水，直接把余额压低（可到 0）；`st.coins + amount` 溢出为负时同样被 `Math.max(0, …)` 吞成 0，余额凭空消失。
- 触发：任何一处调用点传进负值或极大值（目前各调用点的金额都来自 config 或已钳制，属潜在雷区，非现网可触发）。
- 影响：**余额被静默清零**，且流水账（`producedTotal/consumedTotal`）与之对不上。
- 最小修复：`addCoins` 对 `amount <= 0` 直接返回当前余额；加法用 `Math.addExact` 并钳到 `Long.MAX_VALUE`：
  ```java
  if (amount <= 0) { return st.coins; }
  st.coins = st.coins > Long.MAX_VALUE - amount ? Long.MAX_VALUE : st.coins + amount;
  ```

### [P2] 订单过期结算「先置 EXPIRED 落盘、后加钱」，崩溃即悬赏永久丢失
- 位置：`src/main/java/com/forgemaster/order/OrderService.java:349-360`
- 机制：`o.state(EXPIRED); save();`（orders.yml 同步落盘）之后才 `plugin.stats().addCoins(o.poster(), o.bounty())`（仅内存 + `markDirty()`，最长 5 分钟才冲刷）。这中间的崩溃让单据已结案、钱没入账。
- 触发：过期结算（`maintain()` 每 5 分钟一轮）之后 5 分钟内崩溃。
- 影响：**发单人丢失托管悬赏**（漏退，文档已承认"可人工补"，但无任何对账记录）。
- 最小修复：`settleExpired()` 末尾补一次 `plugin.stats().save()`；或退悬赏改用 `Wallet.give` 并立即落盘。

### [P2] 行会解散的平分退款逐人写盘，中途崩溃造成部分成员漏退
- 位置：`src/main/java/com/forgemaster/guild/GuildService.java:627-641`（`stats().addCoins` 在 635，`removeMember`→`save()` 在 637）
- 机制：循环里每处理一个成员就 `removeMember(...)` → `save()`（guild-data.yml 同步写盘），而 `addCoins` 只进 stats 内存。成员被移出会立刻落盘，钱要等 5 分钟。中途崩溃 → 后半段成员的 `addCoins` 全部丢失，且他们已不在 `members` 里，无法再结算。
- 触发：解散行会时（`disband`）崩溃 / 强杀。
- 影响：**成员漏退剑币**（金库余额随 `guilds.remove(g.id)` 一起消失）。
- 最小修复：循环只累加，循环外一次性发放并在结束前 `plugin.stats().save()`：
  ```java
  Map<UUID, Long> payouts = new HashMap<>();
  for (UUID id : members) { payouts.merge(id, share + (id.equals(g.owner) ? remainder : 0), Long::sum); }
  payouts.forEach((id, give) -> { if (give > 0) stats().addCoins(id, give, CoinReason.REFUND); });
  plugin.stats().save();     // 钱落地后才清行会
  ```

### [P2] 行会委托领奖同样是「先记已领、后发币」，状态走异步冲刷
- 位置：`src/main/java/com/forgemaster/guild/GuildQuests.java:407-414`（`claimOneTime` 在 407，`addCoins` 在 414）；状态落盘靠 `dirty` + `GuildService` 的低频冲刷（`GuildService.java:297-311`）
- 机制：`claimOneTime` 往本周期 `claimed` 集合写入并 `dirty = true`，随后发币（内存）。两个写入都不在本次调用里落盘。
- 触发：领奖后崩溃 / 强杀；本周期 `cycle-hours` 未到刷新。
- 影响：**同一周期内可重复领**（成员数 × 每轮赏金），或反向漏发。
- 最小修复：`claim()` 里在 `addCoins` 之后立即 `plugin.guilds().save()`（或让 `GuildQuests` 提供 `flush()`），并把 `claimOneTime` 挪到发币之后。

### [P2] 委托 `purgeExpired` 与 `ensureFor` 口径不一致：一条断链记失败、另一条静默丢弃
- 位置：`src/main/java/com/forgemaster/commission/CommissionManager.java:427-431`（`purgeExpired` 只 `removeIf(expired)`）对照 `:138-146`（`ensureFor` 里 `countCommission(false)` + `plugin.streaks().onBreak(...)`）
- 机制：过期委托由两条路径清理，一条把逾期算作失败并断连锻链，另一条什么都不记。
- 触发：玩家委托过期后，先触发的是 `purgeExpired`（`SelfTestSuite` 与后台维护会调）而不是重开委托板。
- 影响：**统计与连锻链结果不一致**（同样的逾期，有的玩家断链、有的不断），属于可被玩家"挑路径"规避的规则漏洞。
- 最小修复：把 `purgeExpired` 的实现改成复用 `ensureFor` 的清理分支（`countCommission(owner, false)` + `streaks().onBreak(owner, "委托逾期")`）。

---

## 待确认（未读到 / 证据不足，不下结论）

1. `SeasonPassService.grantReward` 里 `charm.setAmount(r.charmAmount)`（`:597`）与 `new ItemStack(m, amount)`（`:660`）在 Paper 1.21.11 下对"数量 > 最大堆叠"是否抛 `IllegalArgumentException`：需在真实服务端上验一次。若抛，P1「先记已领后发奖」的触发条件就从"极端背包"扩大到"任何配了 `charm.amount > 1` 的服"。
2. `AuctionManager.buyout` 的 `selfTop` 分支（`:239-241`）在"买家既是最高出价者、又在一口价窗口内被 `settleExpired` 抢先结案"时的退款守恒：`store.finish` 返回 false 时退回 `cost`（不含已冻结的 `l.price()`），需确认冻结款最终由谁承接。
3. `GuildService.settleWeek` 的 `weekFirstPrize` 发放周期：`worldWeek()` 用 `getFullTime()/168000`（7 个 MC 日 ≈ 现实 140 分钟），与"周赛"文案不符，但类注释明确写了按 MC 周计，暂判为设计选择，未计入正式条目。
4. `OrderService.deliver` 第 2→3 步之间 `addMail`（写 auction.yml 内存）与 `orders.yml` 同步 `save()` 的竞态：异步冲刷线程理论上可在此毫秒级窗口内先落 auction.yml，导致 `reconcile()` 重复投递同一把剑。概率极低，未实测。