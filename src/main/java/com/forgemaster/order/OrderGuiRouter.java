package com.forgemaster.order;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * 订单面板自己的点击路由（照 BlackmarketRouter / GuildGuiRouter 的懒注册做法）。
 *
 * <p>所有点击与拖拽一律取消，图标拿不出去；优先级压到 LOWEST，比 ForgeListener 的
 * HIGH 先跑。监听器第一次打开订单面板时注册，不用碰主类的监听器注册段；
 * 卸载时由 onDisable 调 {@link #reset()} 复位静态标记（/reload 语义同黑市）。</p>
 */
public final class OrderGuiRouter implements Listener {

    private static boolean ready;

    private OrderGuiRouter() {
    }

    /** 第一次打开订单面板时注册一次 */
    static synchronized void ensure(ForgingMasterPlugin plugin) {
        if (ready || plugin == null) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(new OrderGuiRouter(), plugin);
        ready = true;
    }

    /**
     * 插件卸载时复位。{@code ready} 是 static：/reload 后旧实例的监听器已随旧插件
     * 注销，但静态标记还是 true，ensure() 会直接返回，面板就失去点击拦截。
     */
    public static synchronized void reset() {
        ready = false;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof OrderGui.Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !inTop(event, top)) {
            return;
        }
        OrderService service = holder.plugin().orders();
        if (service == null || !service.enabled()) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == OrderGui.SLOT_CLOSE) {
            player.closeInventory();
            return;
        }
        if (slot == OrderGui.SLOT_TAKEN) {
            // 交付口：主手拿着达标锻造品点这里
            if (holder.takenId() != null) {
                Msg.prefix(player, service.deliver(player, holder.takenId()));
            }
            rerender(holder, player);
            return;
        }
        String id = holder.idAt(slot);
        if (id == null) {
            return;
        }
        Order o = service.get(id);
        if (o != null && o.poster().equals(player.getUniqueId()) && event.isShiftClick()) {
            // 自己的单 Shift+左键 = 取消（全额退托管悬赏）
            Msg.prefix(player, service.cancel(player, id));
        } else {
            Msg.prefix(player, service.take(player, id));
        }
        rerender(holder, player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof OrderGui.Holder) {
            event.setCancelled(true);
        }
    }

    /** 下一 tick 重画：这个界面正在被操作，别跟新界面抢背包 */
    private void rerender(OrderGui.Holder holder, Player player) {
        Bukkit.getScheduler().runTask(holder.plugin(), () -> {
            if (!player.isOnline() || holder.plugin().orders() == null) {
                return;
            }
            Inventory topNow = player.getOpenInventory().getTopInventory();
            if (topNow.getHolder() instanceof OrderGui.Holder h) {
                OrderGui.render(topNow, h, player);
            }
        });
    }

    /** 点击是不是落在上层界面（点在玩家背包里就什么都不做） */
    private static boolean inTop(InventoryClickEvent event, Inventory top) {
        return event.getClickedInventory() != null && event.getClickedInventory().equals(top);
    }
}
