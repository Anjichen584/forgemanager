package com.forgemaster.blackmarket;

import com.forgemaster.ForgingMasterPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * 黑市界面自己的点击路由（照 PassGuiRouter / SpectrumGuiRouter 的懒注册做法）。
 *
 * <p>{@code ForgeListener} 只认它那几个 holder，对本 holder 是「无操作且不取消」，
 * 所以这里自备一份：所有点击与拖拽一律取消，图标拿不出去。优先级压到 LOWEST，
 * 比 ForgeListener 的 HIGH 先跑。监听器第一次打开黑市时注册，不用碰主类。</p>
 */
public final class BlackmarketRouter implements Listener {

    private static boolean ready;

    private BlackmarketRouter() {
    }

    /** 第一次打开黑市界面时注册一次 */
    static synchronized void ensure(ForgingMasterPlugin plugin) {
        if (ready || plugin == null) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(new BlackmarketRouter(), plugin);
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
        if (!(top.getHolder() instanceof BlackmarketGui.Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !inTop(event, top)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == BlackmarketGui.CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        BlackmarketService.Offer offer = holder.offerAt(slot);
        if (offer == null) {
            return;
        }
        BlackmarketService service = holder.plugin().blackmarket();
        if (service == null || !service.enabled()) {
            return;
        }
        service.buy(player, offer);
        // 下一 tick 重画：这个界面正在被操作，别跟新界面抢背包
        Bukkit.getScheduler().runTask(holder.plugin(), () -> {
            if (player.isOnline() && holder.plugin().blackmarket() != null) {
                Inventory topNow = player.getOpenInventory().getTopInventory();
                if (topNow.getHolder() instanceof BlackmarketGui.Holder h) {
                    BlackmarketGui.render(topNow, h, player);
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof BlackmarketGui.Holder) {
            event.setCancelled(true);
        }
    }

    /** 点击是不是落在上层界面（点在玩家背包里就什么都不做） */
    private static boolean inTop(InventoryClickEvent event, Inventory top) {
        return event.getClickedInventory() != null && event.getClickedInventory().equals(top);
    }
}
