package com.forgemaster.title;

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
 * 称号面板的点击路由（照 SpectrumGuiRouter 的懒注册做法）。
 *
 * <p>{@code ForgeListener} 不认本 holder（无操作且不取消），这里自备一份：
 * 所有点击与拖拽一律取消，图标拿不出去。优先级压到 LOWEST，比 ForgeListener 的 HIGH 先跑。
 * 监听器第一次打开界面时注册，不用碰主类（卸载时 {@code TitleGuiRouter.reset()}）。</p>
 */
public final class TitleGuiRouter implements Listener {

    private static boolean ready;

    private TitleGuiRouter() {
    }

    /** 第一次打开称号面板时注册一次 */
    static synchronized void ensure(ForgingMasterPlugin plugin) {
        if (ready || plugin == null) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(new TitleGuiRouter(), plugin);
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
        if (!(top.getHolder() instanceof TitleGui.Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() == null
                || !event.getClickedInventory().equals(top)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == TitleGui.CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == TitleGui.TAKEOFF_SLOT) {
            TitleService service = holder.plugin().titles();
            if (service != null) {
                service.takeOff(player);
                redraw(holder, player);
            }
            return;
        }
        String id = holder.titleIdAt(slot);
        if (id == null) {
            return;
        }
        TitleService service = holder.plugin().titles();
        if (service == null) {
            return;
        }
        TitleService.Title t = service.byId(id);
        if (t == null) {
            return;
        }
        TitleService.Title worn = service.wornOf(player.getUniqueId());
        if (worn != null && worn.id.equals(t.id)) {
            service.takeOff(player);
        } else {
            service.wear(player, t);
        }
        redraw(holder, player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof TitleGui.Holder) {
            event.setCancelled(true);
        }
    }

    /** 下一 tick 重画：这个界面正在被操作，别跟新界面抢背包 */
    private static void redraw(TitleGui.Holder holder, Player player) {
        Bukkit.getScheduler().runTask(holder.plugin(), () -> {
            if (player.isOnline()) {
                TitleGui.open(holder.plugin(), player);
            }
        });
    }
}
