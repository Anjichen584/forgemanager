package com.forgemaster.guild;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 行会界面自己的点击路由（照 {@link com.forgemaster.pass.PassGuiRouter} 的懒注册做法）。
 *
 * <p>{@code ForgeListener} 只认它那几个 holder，对本包的 holder 是「无操作且不取消」，
 * 所以这里自备一份，两种 holder：</p>
 * <ul>
 *   <li><b>{@link GuildGui.Holder}（行会列表）</b>：静态快照，所有点击与拖拽一律取消，
 *       图标拿不出去，只有关闭钮有效。</li>
 *   <li><b>{@link GuildWarehouseGui.Holder}（行会仓库，v2.23）</b>：活的仓储——仓储区
 *       （0..capacity-1）放行原版移动（放入 / 取出即点即存），占位区与关闭钮一律取消；
 *       成员取出权限在 {@link GuildWarehouse#canWithdraw} 里判。放行的改动在下一 tick
 *       <b>逐格提交</b>进 {@link GuildWarehouse}（写前校验权威值，冲突则回滚并退还物品）
 *       并标脏，交 GuildService 的 5 秒异步冲刷落盘。</li>
 * </ul>
 * 优先级压到 LOWEST，比 ForgeListener 的 HIGH 先跑。监听器第一次打开界面时注册，
 * 不用碰主类。
 */
public final class GuildGuiRouter implements Listener {

    private static boolean ready;

    /** 取出被拒的提示节流：玩家 uuid → 上次提示毫秒（1 秒一条，防连点刷屏） */
    private static final WeakHashMap<UUID, Long> withdrawWarn = new WeakHashMap<>();

    private GuildGuiRouter() {
    }

    /** 第一次打开行会界面时注册一次 */
    static synchronized void ensure(ForgingMasterPlugin plugin) {
        if (ready || plugin == null) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(new GuildGuiRouter(), plugin);
        ready = true;
    }

    /**
     * 插件卸载时复位。{@code ready} 是 static：/reload 后旧实例的监听器已随旧插件
     * 注销，但静态标记还是 true，ensure() 会直接返回，界面就失去点击拦截。
     */
    public static synchronized void reset() {
        ready = false;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (holder instanceof GuildGui.Holder) {
            // 行会列表：静态快照，全部取消，只留关闭钮
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player
                    && event.getRawSlot() == GuildGui.CLOSE_SLOT
                    && inTop(event, top)) {
                player.closeInventory();
            }
            return;
        }
        if (holder instanceof GuildWarehouseGui.Holder warehouse) {
            routeWarehouse(event, warehouse, top);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (holder instanceof GuildGui.Holder) {
            event.setCancelled(true);
            return;
        }
        if (holder instanceof GuildWarehouseGui.Holder warehouse) {
            int size = top.getSize();
            for (int raw : event.getRawSlots()) {
                // 拖到占位区（含关闭钮）就整单取消；只碰仓储区与背包的放行（原版分配）
                if (raw >= 0 && raw < size && raw >= warehouse.capacity()) {
                    event.setCancelled(true);
                    return;
                }
            }
            queueCommit(warehouse);
        }
    }

    /**
     * 关仓即提交：最后状态一定落内存（随后标脏异步落盘）。
     *
     * <p>v2.23.1 修 P0-2：从「整表覆盖快照」改成「逐格提交 + 冲突校验」，
     * 见 {@link #commitAll}。幂等，重复触发没有副作用。</p>
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof GuildWarehouseGui.Holder warehouse) {
            commitAll(warehouse, event.getInventory());
        }
    }

    /**
     * 掉线兜底提交（v2.23.1 修 P0-2）。
     *
     * <p>不依赖 {@link InventoryCloseEvent}：服务器停服、被踢、{@code /reload} 等路径下
     * 「关仓事件一定先来」这个前提并不牢靠，而玩家手上的改动只存在于活 Inventory 里。
     * 这里在离开时再逐格提交一次——{@link #commitAll} 是幂等的，
     * 之前已经提交过的格子 {@code live == baseline}，直接空转。</p>
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof GuildWarehouseGui.Holder warehouse) {
            commitAll(warehouse, top);
        }
    }

    // ------------------------------------------------------------ 仓库路由

    private void routeWarehouse(InventoryClickEvent event, GuildWarehouseGui.Holder warehouse,
                                Inventory top) {
        event.setCancelled(true); // 先全取消，放行分支再解
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // v2.26.6（审计 #7）：点击者必须是面板的 viewer——仓库的冲突回滚永远退给
        // warehouse.viewer()，invsee 类插件点别人面板时物品会落到面板主人手上
        if (!player.getUniqueId().equals(warehouse.viewer())) {
            return;
        }
        int raw = event.getRawSlot();
        int size = top.getSize();

        if (raw < 0) {
            return; // 点窗口外（丢弃光标）保持取消：仓里拿出来的东西只能进背包或放回仓库
        }
        // v2.23.1 修 P0-2 第二道闸：只要真源被别人改过，这一下就不许落地（含 shift 存入），
        // 取消并下一 tick 用权威值重开面板。否则「取走别人已经取走的幻影物品」会变成复制。
        if (resyncIfStale(warehouse, player)) {
            return; // 保持取消
        }
        if (raw >= size) {
            // 点自己的背包：原版操作照常（shift 点背包 = 存入，人人可做）
            event.setCancelled(false);
            if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                queueCommit(warehouse);
            }
            return;
        }
        if (raw == GuildWarehouseGui.CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (raw >= warehouse.capacity()) {
            return; // 占位区：保持取消，图标拿不出去也放不进来
        }

        // 仓储区：判定这一下是不是「往外拿」
        ItemStack slotItem = top.getItem(raw);
        boolean occupied = slotItem != null && !slotItem.getType().isAir();
        boolean take = isTake(event, occupied);
        if (take) {
            GuildService.Guild g = warehouse.guild();
            GuildService service = warehouse.plugin().guilds();
            boolean owner = g.owner.equals(player.getUniqueId());
            boolean allowed = service != null && GuildWarehouse.canWithdraw(owner,
                    service.warehouse().memberWithdraw());
            if (!allowed) {
                warnWithdraw(player);
                return; // 保持取消：成员只能存
            }
        }
        event.setCancelled(false);
        queueCommit(warehouse); // 放入 / 取出都改内容，下一 tick 逐格提交进真源（带冲突校验）
    }

    /**
     * 这一下点击会不会从仓储格往外拿（纯判定）：
     * 空手点有货（左键全拿 / 右键半拿 / 热键换出 / 副手换出）都算；
     * 手上有货时的双击收集与热键换位也算；往空格或同型格放货不算（存入，人人可做）。
     *
     * <p>v2.23.1 修 P1-1：补上 {@link InventoryAction#SWAP_WITH_CURSOR}。
     * 光标持物去点一个「有货」的仓储格，原版动作就是 SWAP_WITH_CURSOR——把光标上的
     * 东西放进格子、把格子里的东西换到光标上，这显然也是一次「往外拿」。
     * 原先漏判这一类，于是它被判成存入 → 放行 → 取出权限检查（{@code guild.warehouse.member-withdraw}）
     * 根本没执行；无取出权限的成员拿一组泥土右键点一下就能把仓库里的下界合金锭换出来。</p>
     */
    private static boolean isTake(InventoryClickEvent event, boolean occupied) {
        if (!occupied) {
            return false;
        }
        ItemStack cursor = event.getCursor();
        if (cursor == null || cursor.getType().isAir()) {
            return true;
        }
        InventoryAction action = event.getAction();
        return action == InventoryAction.COLLECT_TO_CURSOR
                || action == InventoryAction.HOTBAR_SWAP
                || action == InventoryAction.SWAP_WITH_CURSOR; // v2.23.1 修 P1-1
    }

    /** 下一 tick 把活 Inventory 逐格提交进真源（点击当下读的是改动前的状态，必须延一拍） */
    private static void queueCommit(GuildWarehouseGui.Holder warehouse) {
        if (warehouse.snapshotQueued) {
            return;
        }
        warehouse.snapshotQueued = true;
        try {
            Bukkit.getScheduler().runTask(warehouse.plugin(), () -> {
                warehouse.snapshotQueued = false;
                commitAll(warehouse, warehouse.getInventory());
            });
        } catch (IllegalStateException | IllegalArgumentException ignored) {
            warehouse.snapshotQueued = false; // 插件正在关停，调度失败就不排了
        }
    }

    /**
     * 逐格提交（v2.23.1 修 P0-2）：把 GUI 里「本会话真的改动过」的格子一格一格写进权威存储。
     *
     * <p>原先是 {@code GuildWarehouse.snapshot()} 整表覆盖 —— 两人同时开仓时，
     * A 取走的物品会被 B 的旧视图整表写回复活（复制）；B 缺了 A 的新存入则被抹掉（丢失）。
     * 现在只处理 {@code live != baseline}（本会话动过）的格子，而且每格都要过
     * {@link GuildWarehouse#commit} 的冲突校验（权威值仍等于 baseline 才写）：
     * 别人抢在前面的格子一律拒写，以权威值为准、把 GUI 刷回权威值、把玩家的物品退还，
     * <b>绝不覆盖别人的改动</b>。</p>
     *
     * <p>幂等：提交成功就同步更新 baseline，重复调用（点击后一 tick + 关闭 + 掉线）看到的
     * 都是 {@code live == baseline}，自然空转。</p>
     */
    private static void commitAll(GuildWarehouseGui.Holder warehouse, Inventory inv) {
        if (warehouse == null || inv == null) {
            return;
        }
        GuildService service = warehouse.plugin().guilds();
        if (service == null) {
            return;
        }
        GuildWarehouse wh = service.warehouse();
        Player player = Bukkit.getPlayer(warehouse.viewer());
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

    /** 空气归一成 null，好让 equals 与「空 vs 空」对齐 */
    private static ItemStack normalize(ItemStack it) {
        return (it == null || it.getType().isAir()) ? null : it;
    }

    /**
     * 冲突时把玩家刚放进去 / 换出来的那份物品原样退还。
     *
     * <p>注意顺序：调用点已经先把 GUI 那格刷成权威值了，所以 {@code item} 只在玩家背包里
     * 存一份引用，不会出现「GUI 里一份 + 背包里一份」的复制。
     * 玩家已经掉线（{@code Bukkit.getPlayer} 为 null / 已离线）时退不了背包，
     * 就塞进仓储区第一个空格（{@link GuildWarehouse#putFirstFree}），实在满仓才丢弃并记日志。</p>
     */
    private static void returnToOperator(GuildWarehouseGui.Holder warehouse, Player player, ItemStack item) {
        if (item == null) {
            return;
        }
        if (player != null && player.isOnline()) {
            java.util.Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            for (ItemStack rest : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
            Msg.prefix(player, "<yellow>仓库这一格刚被别人改动过，已按当前内容回滚，你的物品退回背包。");
            return;
        }
        GuildService service = warehouse.plugin().guilds();
        if (service != null && service.warehouse().putFirstFree(warehouse.guild().id, item)) {
            return;
        }
        warehouse.plugin().getLogger().warning("行会仓库冲突回滚时退不回物品（行会 "
                + warehouse.guild().id + "，" + item.getType() + " x" + item.getAmount() + "）");
    }

    /**
         * 点之前先确认「看到的就是真源」（v2.23.1 修 P0-2 的第二道闸）。
         *
         * <p>只有逐格提交是不够的：A 取走物品后，B 那份还开着的旧界面<b>仍然显示</b>着那件物品
         * （服务端不会主动刷新别人的容器）。如果 B 直接把它点到光标上，B 的 GUI 格里变成空、
         * 权威值也已经是空 —— 逐格提交只会判定「无改动」，物品却在 B 手里，照样是一份复制。</p>
         *
         * <p>所以每次点击先比一遍「会话基线 vs 权威值」：只要有任何一格不一致，说明别人在你
         * 开仓期间动过仓库，这一下直接取消，并下一 tick 用权威值重开面板（{@link #refreshAsync}）。
         * 玩家不会因为「点了没反应」困惑——提示里说清了是内容变了。</p>
         *
         * <p>注意比的是 {@code baseline} 而不是 GUI 当前值：上一击留下的、还没提交的改动
         * （{@code snapshotQueued} 排队中）不应该被误判成「别人改过」。</p>
         */
        private static boolean resyncIfStale(GuildWarehouseGui.Holder warehouse, Player player) {
            GuildService service = warehouse.plugin().guilds();
            if (service == null) {
                return false;
            }
            GuildWarehouse wh = service.warehouse();
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
                refreshAsync(warehouse, player);
            }
            return stale;
        }

        /** 下一 tick 用权威值重开面板（重开即重建 baseline，不再手动改活 Inventory） */
        private static void refreshAsync(GuildWarehouseGui.Holder warehouse, Player player) {
            try {
                Bukkit.getScheduler().runTask(warehouse.plugin(), () -> {
                    if (player == null || !player.isOnline()) {
                        return;
                    }
                    Inventory now = player.getOpenInventory().getTopInventory();
                    if (!(now.getHolder() instanceof GuildWarehouseGui.Holder h) || h != warehouse) {
                        return; // 玩家已经关掉了，别再硬开回来
                    }
                    GuildWarehouseGui.open(warehouse.plugin(), player);
                });
            } catch (IllegalStateException | IllegalArgumentException ignored) {
                // 插件正在关停，不排任务
            }
        }

        /** 取出被拒提示，1 秒节流 */
    private static void warnWithdraw(Player player) {
        long now = System.currentTimeMillis();
        Long last = withdrawWarn.get(player.getUniqueId());
        if (last != null && now - last < 1000L) {
            return;
        }
        withdrawWarn.put(player.getUniqueId(), now);
        Msg.prefix(player, "<red>行会仓库：你没有取出权限（默认只有会长能取，"
                + "成员取出可由服务器 guild.warehouse.member-withdraw 开放）。</red>");
    }

    /** 点击是不是落在上层界面（点在玩家背包里就什么都不做） */
    private static boolean inTop(InventoryClickEvent event, Inventory top) {
        return event.getClickedInventory() != null && event.getClickedInventory().equals(top);
    }
}
