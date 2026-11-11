package com.forgemaster.spectrum;

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
 * 名剑谱界面自己的点击路由（照 ContestGuiRouter 的懒注册做法）。
 *
 * <p>{@code ForgeListener} 只认它那几个 holder，对本 holder 是「无操作且不取消」，
 * 所以这里自备一份：所有点击与拖拽一律取消，图标拿不出去。优先级压到 LOWEST，
 * 比 ForgeListener 的 HIGH 先跑。监听器第一次打开界面时注册，不用碰主类。</p>
 */
public final class SpectrumGuiRouter implements Listener {

    private static boolean ready;

    private SpectrumGuiRouter() {
    }

    /** 第一次打开名剑谱界面时注册一次 */
    static synchronized void ensure(ForgingMasterPlugin plugin) {
        if (ready || plugin == null) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(new SpectrumGuiRouter(), plugin);
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
        if (!(top.getHolder() instanceof SpectrumGui.Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !inTop(event, top)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == SpectrumGui.CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        SpectrumService.Entry entry = holder.entryAt(slot);
        if (entry == null) {
            return;
        }
        SpectrumService service = holder.plugin().spectrum();
        if (service == null) {
            return;
        }
        if (service.claimed(player.getUniqueId(), entry.id) || !service.allMet(player.getUniqueId(), entry)) {
            return; // 没满足或已领：点了也没反应，图标上的进度行已经说明差哪样
        }
        service.claim(player, entry);
        // 下一 tick 重画：这个界面正在被操作，别跟新界面抢背包
        Bukkit.getScheduler().runTask(holder.plugin(),
                () -> SpectrumGui.open(holder.plugin(), player));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SpectrumGui.Holder) {
            event.setCancelled(true);
        }
    }

    /** 点击是不是落在上层界面（点在玩家背包里就什么都不做） */
    private static boolean inTop(InventoryClickEvent event, Inventory top) {
        return event.getClickedInventory() != null && event.getClickedInventory().equals(top);
    }
}
