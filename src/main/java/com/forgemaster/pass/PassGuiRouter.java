package com.forgemaster.pass;

import com.forgemaster.ForgingMasterPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * 赛季通行证界面自己的点击路由（照 SpectrumGuiRouter 的懒注册做法）。
 *
 * <p>{@code ForgeListener} 只认它那几个 holder，对本 holder 是「无操作且不取消」，
 * 所以这里自备一份：所有点击与拖拽一律取消，图标拿不出去。优先级压到 LOWEST，
 * 比 ForgeListener 的 HIGH 先跑。监听器第一次打开界面时注册，不用碰主类。</p>
 */
public final class PassGuiRouter implements Listener {

    private static boolean ready;

    /**
     * 付费轨「变红 → 3 秒内再点同一格」的待确认状态（v2.23.1 修 P1-2）。
     *
     * <p>原来这个状态挂在 {@link PassGui.Holder} 上（{@code armedBuySlot / armedBuyAt}），
     * 但每次点击后路由都会下一 tick 调 {@code PassGui.open(...)} 重画，而
     * {@code PassGui.open} 是 {@code new Holder(...)} —— 武装标记随重画一起被丢掉：
     * 图标永远不变红，第二击命中的是新 Holder（{@code armedBuySlot = -1}）又走「第一击」
     * 分支，于是付费轨<b>永远买不下来</b>。</p>
     *
     * <p>现在状态放在这里（按 uuid 存，不随界面重建而丢），并且<b>与槽位绑定</b>：
     * 只有「同一玩家 + 同一格 + 窗口内」才算成交，换一格点只会重新武装，不会误成交。</p>
     */
    private static final java.util.Map<java.util.UUID, Armed> ARMED = new java.util.concurrent.ConcurrentHashMap<>();

    /** 一次待确认购买：目标槽位 + 到期时刻（毫秒） */
    private record Armed(int slot, long expiresAt) {
    }

    private PassGuiRouter() {
    }

    /**
     * 这格此刻是不是处于「武装待确认」状态（纯查询，顺便清掉已过期的条目）。
     *
     * <p>{@link PassGui} 画图标时用它决定要不要变红，所以必须是 public static。
     * 槽位不匹配、或已超 {@link PassGui#BUY_CONFIRM_MILLIS} 都返回 false。</p>
     */
    public static boolean armed(java.util.UUID viewer, int slot) {
        if (viewer == null) {
            return false;
        }
        Armed a = ARMED.get(viewer);
        if (a == null) {
            return false;
        }
        if (System.currentTimeMillis() >= a.expiresAt()) {
            ARMED.remove(viewer, a); // 过期即清，别让这条记录一直挂着
            return false;
        }
        return a.slot() == slot;
    }

    /** 武装某格（第一击）；同一玩家后来的武装会覆盖前一次 */
    private static void arm(java.util.UUID viewer, int slot) {
        ARMED.put(viewer, new Armed(slot, System.currentTimeMillis() + PassGui.BUY_CONFIRM_MILLIS));
    }

    /** 解除武装（成交后 / 主动取消） */
    private static void disarm(java.util.UUID viewer) {
        if (viewer != null) {
            ARMED.remove(viewer);
        }
    }

    /** 第一次打开赛季通行证界面时注册一次 */
    static synchronized void ensure(ForgingMasterPlugin plugin) {
        if (ready || plugin == null) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(new PassGuiRouter(), plugin);
        ready = true;
    }

    /**
     * 插件卸载时复位。{@code ready} 是 static：/reload 后旧实例的监听器已随旧插件
     * 注销，但静态标记还是 true，ensure() 会直接返回，界面就失去点击拦截。
     */
    public static synchronized void reset() {
        ready = false;
        ARMED.clear(); // v2.23.1：/reload 后旧的武装记录没有意义，别跨实例带过去
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof PassGui.Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !inTop(event, top)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == PassGui.CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        Integer premiumNo = holder.premiumSlotLevels().get(slot);
        if (premiumNo != null) {
            handlePremiumClick(holder, player, slot, premiumNo);
            return;
        }
        Integer levelNo = holder.slotLevels().get(slot);
        if (levelNo == null) {
            return;
        }
        SeasonPassService service = holder.plugin().seasonPass();
        if (service == null || !service.canClaim(player.getUniqueId(), levelNo)) {
            return; // 没达标 / 已领 / 顺序没到：点了没反应，图标上的进度行已经说明差哪样
        }
        service.claim(player, levelNo);
        // 下一 tick 重画：这个界面正在被操作，别跟新界面抢背包
        Bukkit.getScheduler().runTask(holder.plugin(),
                () -> PassGui.open(holder.plugin(), player));
    }

    /**
     * 付费轨点击路由（v2.23）：未解锁 = 两步确认购买（照 ReforgeMenu「变红 → 再点一次」
     * 惯例，第一下只武装不扣钱，同一格 3 秒内再点才真扣）；已解锁 = 与免费轨一样
     * 逐级领取。付费轨点击后一律下一 tick 重画（武装变红 / 解锁后的领取态都要刷新）。
     */
    private void handlePremiumClick(PassGui.Holder holder, Player player, int slot, int levelNo) {
        SeasonPassService service = holder.plugin().seasonPass();
        if (service == null) {
            return;
        }
        if (!service.premiumBought(player.getUniqueId())) {
            // v2.23.1 修 P1-2：武装状态存在本类的静态表（按 uuid，不随 PassGui.open 重建而丢），
            // 并且与槽位绑定 —— 同一格 3 秒内再点才成交，换一格只会重新武装。
            if (armed(player.getUniqueId(), slot)) {
                disarm(player.getUniqueId());
                service.buyPremium(player); // 余额不足等情形由 service 自行提示
            } else {
                arm(player.getUniqueId(), slot); // 第一击：只武装（图标变红），不扣钱
            }
        } else {
            if (service.canClaimPremium(player.getUniqueId(), levelNo)) {
                service.claimPremium(player, levelNo);
            }
            // 没达标 / 已领 / 顺序没到不提示也行：图标进度行已说明差哪样
        }
        Bukkit.getScheduler().runTask(holder.plugin(),
                () -> PassGui.open(holder.plugin(), player));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof PassGui.Holder) {
            event.setCancelled(true);
        }
    }

    /**
     * v2.25.2（审计轮4）：退服要拆掉付费轨的「已武装」记录。
     *
     * <p>武装表是 static 按 uuid 存的，原来只有 /reload（{@link #reset()}）和三秒过期会清。
     * 于是「点一下武装 → 立刻退服 → 三秒内重进 → 再点同一格」＝ 一跳过确认直接
     * 扣 5000 剑币买断付费轨。玩家视角是「我刚回来点了一下，钱没了」。</p>
     */
    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        ARMED.remove(event.getPlayer().getUniqueId());
    }

    /** 点击是不是落在上层界面（点在玩家背包里就什么都不做） */
    private static boolean inTop(InventoryClickEvent event, Inventory top) {
        return event.getClickedInventory() != null && event.getClickedInventory().equals(top);
    }
}
