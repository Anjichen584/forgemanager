# GUI 缺陷修复记录（v2.23.1）

修复范围严格限定：`guild/`、`pass/`、`title/`、`order/`、`gui/ContractMenu.java`。
`listener/ForgeListener.java`（onDrag 漏 holder）已按分工转给他人，本次未触碰。
未运行 `mvn`（由 Lead 统一编译）。

修复条数：**7 条**（P0 × 2、P1 × 5）。每条都在代码里留了「原来会怎样 / 现在为什么不会」的中文注释，标 `v2.23.1 修 P0-x / P1-x`。

---

## P0-1 行会仓库关闭槽压掉第 49 格物品

- **位置**：`guild/GuildWarehouseGui.java`（`SIZE / STORAGE_MAX_ROWS / STORAGE_MAX / CLOSE_SLOT`、`open()`）、`guild/GuildWarehouse.java:clampRows`、`guild/SelfTestGuild.java`（断言同步）
- **根因**：`CLOSE_SLOT = 49`，而 `rows` 上限是 6 → `capacity()` 最大 54，49 号格同时属于仓储区和关闭钮。`open()` 的顺序是「填占位 → 回填真物 → 写关闭图标」，最后一步把那格物品覆盖掉；下次开仓同样如此，所以存在 49 号格的物品**永久销毁、且取不出来**（点击路由先判 `CLOSE_SLOT` 再判容量，只会关窗）。
- **改法**：容量上限钳到 **5 行 = 45 格**，并在 GUI 层再加一道 `Math.min(wh.capacity(), STORAGE_MAX)` 双保险。关闭钮保持 49，仓储区 `[0,45)` 与装饰区 `[45,54)` 从此不相交。
- **改后行为**：仓储区恒 `≤ 45 < 49`，关闭图标永远落在装饰区；配置写 `rows: 6` 会被钳成 5（第 6 行物品在下次落盘时丢弃，与原本「容量即真理」的口径一致）。
- **自检**：`SelfTestGuild` 的断言改成「钳 1~5」，并**新增两条不变量断言**：`STORAGE_MAX < CLOSE_SLOT`、`clampRows(99)*9 == STORAGE_MAX`。

关键代码（`GuildWarehouseGui.java`）：

```java
public static final int SIZE = 54;
/** 仓储区最大格数 = 5 行 × 9；必须小于 CLOSE_SLOT（见类注释不变量） */
public static final int STORAGE_MAX_ROWS = 5;
public static final int STORAGE_MAX = STORAGE_MAX_ROWS * 9;   // 45
public static final int CLOSE_SLOT = 49;                       // 落在装饰区 [45,54)

// open()：双保险把容量压在 STORAGE_MAX 以内
int cap = Math.min(wh.capacity(), STORAGE_MAX);
ItemStack[] contents = wh.contents(g.id);
ItemStack[] baseline = new ItemStack[cap];
for (int i = 0; i < cap && i < contents.length; i++) {
    ItemStack it = contents[i];
    baseline[i] = (it == null || it.getType().isAir()) ? null : it.clone();
}
Holder holder = new Holder(plugin, g, cap, player.getUniqueId(), baseline);
Inventory inv = Bukkit.createInventory(holder, SIZE, ...);

// 占位区（cap..SIZE-1，含 49 号关闭钮）填灰玻璃，路由里永不可点
ItemStack filler = ForgeMenu.filler();
for (int i = cap; i < SIZE; i++) {
    inv.setItem(i, filler);
}
for (int i = 0; i < cap && i < baseline.length; i++) {
    if (baseline[i] != null) {
        inv.setItem(i, baseline[i].clone());
    }
}
```

槽位区间（写进类注释当不变量）：

```
仓储区     [0, capacity)          capacity = rows×9，rows ∈ [1,5] ⇒ 最多 45 格
装饰区     [STORAGE_MAX, SIZE)    STORAGE_MAX = 45，灰玻璃占位，永不可点
关闭槽     49                     落在装饰区内
硬约束     capacity ≤ STORAGE_MAX = 45 < CLOSE_SLOT = 49
```

---

## P0-2 行会仓库整表覆盖 → 复制 / 丢失

- **位置**：`guild/GuildWarehouse.java`（新增 `commit / getSlot / putFirstFree`，`snapshot` 标 `@Deprecated`）、`guild/GuildWarehouseGui.java`（`Holder` 带 `viewer` + `baseline`）、`guild/GuildGuiRouter.java`（`commitAll / queueCommit / resyncIfStale / refreshAsync / returnToOperator`、`onClose`、新增 `onQuit`）
- **根因**：原来是「点击 / 关闭时把整份 GUI 上层背包覆盖回内存态」，注释写着「后关者为准」。A 取走物品（已进 A 背包）后，B 那份还开着的旧视图只要点一下或关个窗，就把**整表**写回去，A 取走的那格原样复活（复制）；反向抹掉 A 的新存入（丢失）。
- **改法（三层）**：
  1. **逐格提交**：`GuildWarehouse.commit(guildId, slot, expectedPrev, next)` 只写一格，且写前校验「权威值仍等于调用方上次看到的样子」，不等就**拒写**。
  2. **会话基线 + 冲突回滚**：`Holder` 持有 `baseline[]`（打开时的真源副本）。`commitAll` 只处理 `live != baseline` 的格子（本会话真动过的），提交成功就地更新 baseline；冲突则以权威值为准刷回 GUI，并把玩家的物品**退还**（掉线则塞进仓储区第一个空格，满仓才丢弃并记日志）。
  3. **点击前权威值校验**：只做逐格提交仍然堵不住一条路——A 取走后 B 的旧界面**还显示**那件物品，B 把它点到光标上时，GUI 格变空、权威值本来也是空，逐格提交只会判「无改动」，复制照样成立。所以每次点击先比「baseline vs 权威值」，只要有一格不一致就取消这一下、并下一 tick 用权威值重开面板（`resyncIfStale` + `refreshAsync`）。
- **改后行为**：两人同开时，任何一方的改动只会落在自己真正动过、且没人抢先的格子上；别人的改动永远不被覆盖；看到陈旧内容时点击被拦下并刷新，不会再出现「幻影物品被取走」。
- **掉线路径**：新增 `PlayerQuitEvent` 处理器走同一套 `commitAll`，**不依赖 `InventoryCloseEvent`**（停服 / 被踢 / `/reload` 下「关仓事件一定先来」不牢靠）。`commitAll` 幂等，关闭事件与掉线事件重复触发只是空转。
- **自检建议**：`GuildWarehouse.commit` 已成纯内存操作，可直接在自检里造两个「基线」并发提交同一格，断言第二次返回 `false`。

关键代码（逐格提交，`GuildWarehouse.java`）：

```java
public synchronized boolean commit(String guildId, int slot, ItemStack expectedPrev, ItemStack next) {
    ItemStack[] arr = contents(guildId);
    if (slot < 0 || slot >= arr.length) {
        return false;
    }
    ItemStack cur = arr[slot];
    ItemStack curN = (cur == null || cur.getType().isAir()) ? null : cur;
    ItemStack expN = (expectedPrev == null || expectedPrev.getType().isAir()) ? null : expectedPrev;
    if (!java.util.Objects.equals(curN, expN)) {
        return false; // 冲突：这一格已被别人改动，交给 GUI 层回滚
    }
    arr[slot] = (next == null || next.getType().isAir()) ? null : next.clone();
    markDirty();
    return true;
}
```

关键代码（差异提交 + 冲突回滚，`GuildGuiRouter.java`）：

```java
private static void commitAll(GuildWarehouseGui.Holder warehouse, Inventory inv) {
    ...
    int cap = Math.min(warehouse.capacity(), inv.getSize());
    for (int i = 0; i < cap; i++) {
        ItemStack live = normalize(inv.getItem(i));
        ItemStack base = warehouse.baselineAt(i);
        if (live == base || java.util.Objects.equals(live, base)) {
            continue; // 这一格本会话没动过
        }
        if (wh.commit(warehouse.guild().id, i, base, live)) {
            warehouse.baselineAt(i, live);
            continue;
        }
        // 冲突：权威值优先。把 GUI 刷回权威值，玩家的改动退还给他
        ItemStack authoritative = wh.getSlot(warehouse.guild().id, i);
        warehouse.baselineAt(i, authoritative);
        inv.setItem(i, authoritative == null ? null : authoritative.clone());
        returnToOperator(warehouse, player, live);
    }
}
```

```java
// 第二道闸：点之前先确认「看到的就是真源」
private static boolean resyncIfStale(GuildWarehouseGui.Holder warehouse, Player player) {
    boolean stale = false;
    for (int i = 0; i < warehouse.capacity(); i++) {
        ItemStack auth = wh.getSlot(warehouse.guild().id, i);
        if (java.util.Objects.equals(auth, warehouse.baselineAt(i))) {
            continue;
        }
        stale = true;
        break;
    }
    if (stale) {
        Msg.prefix(player, "<yellow>仓库内容刚被别人改动过，面板已刷新，请再点一次。</yellow>");
        refreshAsync(warehouse, player);   // 下一 tick 用权威值重开，重开即重建 baseline
    }
    return stale;
}
```

```java
@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
public void onClose(InventoryCloseEvent event) {
    if (event.getInventory().getHolder() instanceof GuildWarehouseGui.Holder warehouse) {
        commitAll(warehouse, event.getInventory());
    }
}

@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
public void onQuit(PlayerQuitEvent event) {
    Inventory top = event.getPlayer().getOpenInventory().getTopInventory();
    if (top.getHolder() instanceof GuildWarehouseGui.Holder warehouse) {
        commitAll(warehouse, top);
    }
}
```

---

## P1-1 `isTake` 漏 `SWAP_WITH_CURSOR`

- **位置**：`guild/GuildGuiRouter.java:isTake`
- **根因**：光标持物去点「有货」的仓储格，原版动作是 `SWAP_WITH_CURSOR`（把手里的东西放进格子、把格里的东西换到光标上），但白名单只认 `COLLECT_TO_CURSOR` / `HOTBAR_SWAP`，于是被判成「存入」放行，取出权限检查根本没跑。
- **改法**：白名单补 `InventoryAction.SWAP_WITH_CURSOR`。
- **改后行为**：无取出权限的成员拿一组泥土右键仓储格不再能换走下界合金锭，会走 `warnWithdraw` 提示并保持取消。

---

## P1-2 付费轨两步确认武装状态被重画丢掉（付费轨买不下来）

- **位置**：`pass/PassGuiRouter.java`（新增静态 `ARMED` 表 + `armed / arm / disarm`）、`pass/PassGui.java`（`Holder.buyArmed` 改为委托，删掉 `armedBuySlot / armedBuyAt`）
- **根因**：武装状态挂在 `PassGui.Holder` 上，而每次点击后路由都下一 tick 调 `PassGui.open(...)` 重画，`open` 里是 `new Holder(...)` —— 状态随重画一起丢。图标永远不变红，第二击命中的是新 Holder（`armedBuySlot = -1`）又走「第一击」分支，形成无限确认循环，`buyPremium` 永远走不到。
- **改法**：状态移到 `PassGuiRouter` 的静态表（按 uuid 存，不随界面重建而丢），并**与槽位绑定**：只有「同一玩家 + 同一格 + 3 秒窗口内」才算成交，换一格点只会重新武装，不会误成交。`PassGui.premiumLockIcon` 通过 `holder.buyArmed(slot)` → `PassGuiRouter.armed(uuid, slot)` 读到真实状态，变红终于可见。`reset()` 里一并清表（`/reload` 不带旧状态）。
- **改后行为**：第一击点红 → 3 秒内再点同一格真正扣钱解锁；在另一格点只会把武装挪过去。

---

## P1-3 称号界面 4 号状态格被条目覆盖

- **位置**：`title/TitleGui.java`（新增 `LAST_SLOT`、`entrySlot(int)`；条目循环改造）
- **根因**：状态图标写 4 号格，条目循环又从 0 起连续覆盖 44 格，走到第 5 个条目就把 4 号格换成称号按钮，还 `slotTitles.put(4, ...)` 登记成可点击 —— 状态信息永久不可见，玩家想读状态却误触换称号。
- **改法**：条目落位统一走纯函数 `entrySlot(index)`（越过 4 号格自动 +1），绘制与 `slotTitles` 登记**只用这一个函数**，两套索引不可能再错开。条目槽集合 = `[0,44] \ {4}`，与 `49` / `53` 三个装饰槽完全不相交。
- **改后行为**：4 号格只显示面板状态，点击无效；条目从 0,1,2,3,5,6… 顺铺，最多仍 44 条。

---

## P1-4 订单界面重画不清槽位映射与成交标记

- **位置**：`order/OrderGui.java`（新增 `Holder.resetBindings()`，`render` 开头调用）
- **根因**：`render` 只 `inv.clear()` 重填，`slotOrders` 从不清、`takenId` 在「没有接单」分支也不重置。订单列表变短后，旧槽位仍指向已经不显示的订单，点那些现在只是灰玻璃的格子会拿陈旧 id 去 `take` / `deliver`：`take` 被 `TAKE_MAX=1` 挡住却已把玩家锁进「已接单」（他自己看不到任何单）；`deliver` 用陈旧 id 交付。
- **改法**：`render` 开头 `holder.resetBindings(){ slotOrders.clear(); takenId = null; }`，映射与画面永远同源。
- **改后行为**：只有本次真正画出来的槽位才有绑定，点装饰 / 占位格走到 `idAt() == null` 空转（路由里本来就有这个 null 分支）。`SLOT_TAKEN` 在无接单时 `takenId == null`，路由的 `if (holder.takenId() != null)` 直接跳过。

---

## P1-5 圣物三选一显示槽与点击下标错位

- **位置**：`gui/ContractMenu.java`（`openRelicPick` 绘制循环、`click` 的 PICK 分支）
- **根因**：绘制是 `for (String id : ids)` + `def == null` 就 `continue`，`continue` 跳过了 `i++`，显示用的索引只统计有效条目；而点击路由用 `holder.offer()` 的**原始下标**配 `PICK_SLOTS[i]`。只要 `pickPending` 里有已下架的圣物 id，两者整体错开 → 领错圣物 / 点了报「没有这个圣物」。
- **改法**：改成按下标遍历 `for (int i = 0; i < ids.size() && i < PICK_SLOTS.length; i++)`，`i` 无条件前进，下架圣物画一格灰玻璃占位（不再跳过）；`click` 侧对下架 id 直接 return，不进 `pickRelic`。
- **改后行为**：显示槽 ↔ `offer()` 下标严格一一对应；圣物被配置删除时该格显式显示「这件圣物已下架」，点它无副作用，另外两件仍能正常领。

---

## 未修 / 存疑

1. **`rows: 6` 的老存档**：若某服曾配 6 行并在第 46~54 格存过物品，回退到 5 行后这些格会在下一次落盘时被丢弃（`read()` 里 `idx >= cap` 直接跳过）。这是「容量即真理」的既有口径，但**建议上线前进一次后台确认**，必要时临时把物品挪回前 45 格。
2. **`GuildWarehouse.snapshot`** 仍为 `public`（标了 `@Deprecated`），仓库路径已不调用；仅保留签名避免破坏既有调用点。若确认无外部引用可整块删除。
3. **`refreshAsync` 重开面板时玩家光标上的物品**：点击已被取消，光标内容由 Bukkit 按原版规则处理（关窗退回背包 / 掉落）。这是原版行为，未额外接管。
4. **`PassGuiRouter.ARMED`** 是 static：`/reload` 已在 `reset()` 里清空；玩家正常退服时不清理，条目会在 3 秒后过期并在 `armed()` 查询时惰性删除，不构成泄漏。
5. **`ForgeListener.onDrag` 漏 `ContractHolder` / `EvolutionHolder` / `EnvPanelHolder`**（我报告里的 P1）按分工归他人，本次未改；在修好之前，这三个界面仍然可以用拖拽把物品放进只读面板导致丢失。