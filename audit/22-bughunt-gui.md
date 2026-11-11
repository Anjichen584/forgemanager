# ForgingMaster-Lore v2.23 GUI 路由 / 物品安全 / 点击语义 审计

分批落盘，覆盖 8 条（P0 × 2、P1 × 6），未完成项见末尾。

审计基线：Paper 1.21.11 / Java 21，源码只读，未做任何修改。
判定口径：P0 = 物品复制 / 丢失 / 崩溃；P1 = 玩家可操作的错误行为；P2 = 体验问题（本次不收录颜色、文案、命名、微性能）。

---

### [P0] 行会仓库容量可到 54 格，第 49 格的物品每次开仓都被「关闭」图标覆盖销毁
- 位置：`src/main/java/com/forgemaster/guild/GuildWarehouseGui.java:29`、`GuildWarehouseGui.java:94-120`，配套 `GuildWarehouse.java:61-73`
- 机制：`CLOSE_SLOT = 49`。`GuildWarehouse.rows()` 把配置 `guild.warehouse.rows` 钳在 1~6，`capacity() = rows*9`，最大 **54**。`open()` 的顺序是：先 `for (int i = cap; i < SIZE; i++)` 填占位玻璃（`:101`）→ 再 `for (int i = 0; i < cap && i < contents.length; i++)` 回填真仓储（`:105`）→ 最后 `inv.setItem(CLOSE_SLOT, barrier)`（`:112`）。当 `cap >= 50` 时 49 号格在仓储区内：本次打开先用真实物品占住 49 号，紧接着被关闭图标覆盖；下一次打开同样如此，**存在 49 号格的物品永远不会显示，也永远取不出来**。
- 触发：服务器把 `guild.warehouse.rows` 设为 6（容量 54），成员往第 50 格（rawSlot 49）存东西，然后关仓再开。
- 影响：物品丢失（最多一整堆永久蒸发）。同时 `GuildGuiRouter` 的点击顺序是「先判 `CLOSE_SLOT`（`:136`）再判 `raw >= capacity()`（`:140`）」，即使在容量 54 的情况下点 49 号格也只会关窗、取不出货。
- 最小修复：把「关闭位」挪出仓储区，容量上限压到 49 以内。
  ```java
  // GuildWarehouse.java
  private static int clampRows(int rows) { return Math.max(1, Math.min(5, rows)); } // 5*9=45 <= 49
  // GuildWarehouseGui.java:112 —— 绘制顺序后置不受影响，但加一道硬保护
  if (cap > CLOSE_SLOT) {
      // 容量与关闭位冲突：关闭位改到最后一行末尾，并把该格从仓储区排除
  }
  ```

### [P0] 行会仓库整表覆盖快照 + 「后关者为准」，两人同时开仓即可复制物品
- 位置：`src/main/java/com/forgemaster/guild/GuildWarehouse.java:113`、`src/main/java/com/forgemaster/guild/GuildGuiRouter.java:105-112`、`:131`、`:160`、`:181-197`
- 机制：真源是内存里的 `ItemStack[]`；`snapshot(guildId, inv)` 的做法是先把 `contents()` 整块取出，再把 GUI 上层背包前 `capacity` 格**整表覆盖**回去，注释自称「后快照者为准」。`GuildWarehouseGui.open()` 每次都新建 `Inventory` + 新 `Holder`，但两人拿到的是同一份内存数组的拷贝。于是 A 取出物品（物品进 A 背包）后，B 手里那份**仍然显示该物品**的旧 GUI 只要发生任意一次点击 / 拖拽 / 关仓，就会走 `queueSnapshot`（`:160`）或 `onClose`（`:105`）把 B 的整份视图覆盖回真源 —— 被 A 取走的那一格在仓库里**复活**。
- 触发：同一行会两名成员 A、B 同时打开仓库。A 取走一堆钻石（进背包）→ B 随便点一下仓库任意格或直接按 ESC 关仓。
- 影响：物品复制。钻石同时在 A 的背包和仓库里。反向同样成立：B 的旧视图里没有 A 新存入的物品，B 一关仓就把 A 的存入抹掉 → 物品丢失。
- 最小修复：改成「按格差量提交」，不要整表覆盖；并且在快照前校验持有者仍是当前唯一活跃会话。
  ```java
  // GuildWarehouse.java —— 逐格比对，只写回 GUI 与真源不同的格
  public synchronized void snapshot(String guildId, Inventory inv) {
      ItemStack[] arr = contents(guildId);
      for (int i = 0; i < Math.min(arr.length, inv.getSize()); i++) {
          ItemStack now = inv.getItem(i);
          ItemStack old = arr[i];
          if (!Objects.equals(now, old)) {   // 只有真的变了才写
              arr[i] = now == null ? null : now.clone();
          }
      }
      markDirty();
  }
  ```
  并给 `GuildWarehouseGui.Holder` 加会话令牌，仓库被第二个人打开时把第一份 GUI 标记为失效（点击直接 `closeInventory()`，不再快照）。

### [P1] 行会仓库取出权限可被「光标持物右键换位」绕过
- 位置：`src/main/java/com/forgemaster/guild/GuildGuiRouter.java:168-178`
- 机制：`isTake` 判定「这一下是不是往外拿」时，光标非空只认 `COLLECT_TO_CURSOR` 和 `HOTBAR_SWAP`，**漏了 `SWAP_WITH_CURSOR`**。仓储格有货、光标也持货时的右键 / 左键是一次 `SWAP_WITH_CURSOR`：原版会把手里的东西放进格子、把格里的东西换到光标上。这条路径被判成 `take = false`，于是 `setCancelled(false)` 放行（`:159`），`guild.warehouse.member-withdraw` 的权限检查（`:148-158`）根本没执行。
- 触发：成员（无取出权限）主手抓一把泥土 → 打开行会仓库 → 拿泥土右键点装着下界合金锭的仓储格 → 合金锭换到光标上，泥土留在仓里 → 关仓前把光标上的合金锭塞进自己背包。
- 影响：越权取出（权限绕过），等价于把 `member-withdraw=false` 配置失效。
- 最小修复：
  ```java
  return event.getAction() == InventoryAction.COLLECT_TO_CURSOR
          || event.getAction() == InventoryAction.HOTBAR_SWAP
          || event.getAction() == InventoryAction.SWAP_WITH_CURSOR;  // 光标持物换位同样是「往外拿」
  ```

### [P1] 赛季通行证付费轨的两步确认武装状态每次都丢，付费轨永远买不下来
- 位置：`src/main/java/com/forgemaster/pass/PassGuiRouter.java:90-103`、`src/main/java/com/forgemaster/pass/PassGui.java:151`
- 机制：`handlePremiumClick` 第一击执行 `holder.armBuy(slot)`（`:94`），然后**一律** `Bukkit.getScheduler().runTask(..., () -> PassGui.open(plugin, player))`（`:102-103`）。但 `PassGui.open` 在 `:151` 每次 `new Holder(plugin, uuid)` —— 全新 Holder 的 `armedBuySlot = -1`。所以武装标记随重画一起被丢掉：`premiumLockIcon` 里 `holder.buyArmed(slot)` 永远为 false，图标不变红；第二击命中新 Holder，又是 `armBuy` 第一击分支。玩家点多少次都在「第一击」。
- 触发：未购买通行证的玩家打开 `/zhujian pass`，连点同一格付费轨两次、三次。
- 影响：付费轨无法购买（`SeasonPassService.buyPremium` 永远走不到），整个付费奖励线不可达；玩家看到「点了没反应」。
- 最小修复：武装状态不能挂在每次重建的 Holder 上，改用按玩家持有的静态表，或者重画时复用同一个 Holder。
  ```java
  // PassGui.java —— 重画时优先复用同玩家的旧 Holder
  public static void open(ForgingMasterPlugin plugin, Player player) {
      Holder holder = HOLDERS.computeIfAbsent(player.getUniqueId(),
              k -> new Holder(plugin, k));   // HOLDERS: Map<UUID, Holder>，关闭时 remove
      ...
  }
  ```

### [P1] 契约台 / 进化台 / 环境面板的拖拽没拦，拖进去的物品随界面丢弃蒸发
- 位置：`src/main/java/com/forgemaster/listener/ForgeListener.java:346-359`（`onDrag`）、`:71`（`ContractHolder`）、`:87`（`EvolutionHolder`）、`:103`（`EnvPanel.EnvPanelHolder`）
- 机制：`ForgeListener.onDrag` 只对 `ForgeHolder`、`MainMenuHolder`、`WeaponMenuHolder`、`ArmorMenuHolder`、`GemMenuHolder`、`BoardHolder`、`AchvHolder`、`ManualStore`、`TombGui` 这些上层背包 `setCancelled(true)`。`ContractHolder`（契约台 27 格）、`EvolutionHolder`（进化 27 格）、`EnvPanel.EnvPanelHolder` 三个 holder **不在白名单里**，`InventoryDragEvent` 完全放行。而 `onClick` 分支里这三个都是「整单取消」的只读面板，意味着玩家只能用拖拽把物品放进这些 GUI。
- 触发：打开契约台面板（`Mode.PANEL`）或进化台 → 从背包拖一组物品到界面上任意空玻璃格 → 界面被关闭 / 重画。
- 影响：物品丢失。三个界面都不持有这些物品、关闭时也没有任何快照或返还逻辑，物品随 `Inventory` 一起被丢弃。
- 最小修复：把漏掉的三个 holder 补进 `onDrag` 的取消分支。
  ```java
  if (holder instanceof ForgeHolder || holder instanceof MainMenuHolder
          || holder instanceof WeaponMenuHolder || holder instanceof ArmorMenuHolder
          || holder instanceof GemMenuHolder || holder instanceof BoardHolder
          || holder instanceof AchvHolder || holder instanceof ManualStore
          || holder instanceof TombGui
          || holder instanceof ContractHolder          // 新增
          || holder instanceof EvolutionHolder         // 新增
          || holder instanceof EnvPanel.EnvPanelHolder)// 新增
  ) { event.setCancelled(true); }
  ```

### [P1] 称号界面 4 号状态格被条目列表覆盖，状态信息消失且 4 号格变成可误点的称号按钮
- 位置：`src/main/java/com/forgemaster/title/TitleGui.java:114`（状态图标写 4 号格）、`:126-136`（条目循环从 `FIRST_SLOT = 0` 起）
- 机制：`STATUS_SLOT = 4` 先被写入状态图标，随后条目绘制循环从槽位 0 开始连续覆盖 `MAX_ENTRIES = 44` 个格子，走到第 5 个条目时把 4 号格替换成称号按钮，并且 `slotTitles.put(4, t.id)` 把它登记成可点击的称号。点击路由按 `slotTitles` 分发，于是 4 号格既不是状态格，又能触发换称号。
- 触发：玩家有 ≥5 个已解锁称号时打开称号界面；想读状态却点到 4 号格。
- 影响：玩家在完全没预期的情况下被切换称号（误操作）；顶部状态行永久不可见。
- 最小修复：让条目区避开状态格。
  ```java
  // TitleGui.java —— 条目从 0 开始排时跳过 STATUS_SLOT
  int slot = FIRST_SLOT;
  for (Title t : list) {
      if (slot == STATUS_SLOT) slot++;      // 跳过状态格
      if (slot >= FIRST_SLOT + MAX_ENTRIES) break;
      ...
  }
  ```
  （或把 `STATUS_SLOT` 挪到第一行右侧并让 `FIRST_SLOT = 9`。）

### [P1] 订单界面重画不清槽位映射与成交标记，点占位玻璃会去接一个不存在的订单
- 位置：`src/main/java/com/forgemaster/order/OrderGui.java:53-93`（`render`）
- 机制：`render` 只 `inv.clear()` 再重填，但 `holder.slotOrders` **从不清空**，`takenId` 在 `mine == null` 的分支（`:78-87`）也**不重置**。当订单列表变短（别人取走 / 过期），上一轮留在映射里的槽位指向已经不显示的订单；`OrderGuiRouter` 直接按 `slotOrders.get(slot)` 分发 `take` / `deliver`。
- 触发：订单界面开着时列表缩短（例如自己的挂单被别人取走），然后点那些现在只是灰色玻璃的格子。
- 影响：`OrderService.take` 因 `TAKE_MAX = 1` 把玩家锁在「已经接了一单」的状态（他自己看不到任何单）；`deliver` 用陈旧 id 交付，界面与实际状态脱节。属于玩家可操作的错误行为。
- 最小修复：`render` 开头把两份状态一起清掉。
  ```java
  holder.slotOrders.clear();
  holder.takenId = null;      // 每次重画都以本次渲染结果为准
  inv.clear();
  ```

### [P1] 契约台「选圣物」页的显示槽位与点击索引会错位，点到的和你看到的不是同一件
- 位置：`src/main/java/com/forgemaster/gui/ContractMenu.java:123`（`click` 的 `Mode.PICK` 分支）、同文件 `openRelicPick` 的绘制循环
- 机制：绘制循环是「遇到 `def == null`（配置里已删除的圣物 id）就 `continue`」，但 `continue` **跳过了 `i++`**，所以显示索引只统计有效条目；而点击路由用的是 `holder.offer()` 里的**原始下标** `i` 去配 `PICK_SLOTS[i]`。两者一旦有缺口就完全错开：显示在 11 号格的是第二件，路由认为 11 号格是第一件。
- 触发：`pickPending` 里存着的某个圣物 id 被配置删除（改 config 后重载），玩家点「先放着」进入选圣物页。
- 影响：领到错的圣物，或点 11 号格提示「没有这个圣物」而实际物品仍在；同时光标上悬挂的圣物在关窗时可能不再返还。属于玩家可操作的错误行为。
- 最小修复：显示索引与路由索引统一——绘制时先用 `ids` 的原始下标定位槽位，无效条目画成灰玻璃而不是跳过。
  ```java
  for (int i = 0; i < ids.size() && i < PICK_SLOTS.length; i++) {
      RelicPool.Def def = cm.relics().get(ids.get(i));
      inv.setItem(PICK_SLOTS[i], def == null
              ? ForgeMenu.icon(Material.GRAY_DYE, "<red>该圣物已下架", List.of())
              : relicIcon(def));
  }
  ```

---

## 未完成项（本轮未覆盖，留给后续）

- `tomb/TombGui`、`env/EnvPanel`、`ranking/*`、`hall/*`、`growth/AchvMenu`、`commission/*` 的点击 / 拖拽 / 关闭分支未逐行读完。
- `auction/AuctionManager.buildBrowse` 把**真实物品**放进 0~44 号格，需要确认 `AuctionListener`（`HIGH` 优先级、未设 `ignoreCancelled`）在所有点击类型下都取消干净，特别是 `DOUBLE_CLICK` 与 `CONTROL_DROP`。
- `ContestWatchGui` 的 `rows()` / `capacity()` / `closeSlot(size) = size-5` 与参赛者列表起始格 9 的边界未做满容量推演。
- `codex/CodexGui` 的 `CLAIM_SLOT = 49` 与 `CONTENT_TO = 44`、翻页 clamp 的交互未读完全。
- `forge/ForgeManager.clickResult`（结算界面 13 号格放的是真实成品，`giveResult` 又把同一个 `ItemStack` 实例塞进背包）需确认没有任何路径能把 13 号格的物品取走。
- `PlayerQuitEvent` 掉线路径：行会仓库 `onClose` 在掉线时是否触发、`ForgeManager.discard` 是否覆盖所有阶段。