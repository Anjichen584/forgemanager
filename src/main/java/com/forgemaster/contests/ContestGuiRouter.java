package com.forgemaster.contests;

import com.forgemaster.ForgingMasterPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * 大赛两个界面（赛况面板 / 观战面板）自己的点击路由。
 *
 * <p>{@code ForgeListener} 只认它那几个 holder，对本包这两个 holder 是「无操作且不取消」，
 * 所以这里自备一份：所有点击与拖拽一律取消，图标拿不出去。优先级压到 LOWEST，
 * 比 ForgeListener 的 HIGH 先跑。</p>
 *
 * <p>监听器懒注册（第一次打开界面时注册），这样不用碰主类。</p>
 */
public final class ContestGuiRouter implements Listener {

    private static boolean ready;

    private ContestGuiRouter() {
    }

    /** 第一次打开大赛界面时注册一次 */
    static synchronized void ensure(ForgingMasterPlugin plugin) {
        if (ready || plugin == null) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(new ContestGuiRouter(), plugin);
        ready = true;
    }

    /**
     * 插件卸载时复位。
     *
     * <p>{@code ready} 是 static：`/reload` 或 PlugMan 重载后旧实例的监听器已经随
     * 旧插件注销，但静态标记还是 true，`ensure()` 会直接返回，两个界面就永久失去
     * 点击拦截与关闭清理。onDisable 里调一次即可。</p>
     */
    public static synchronized void reset() {
        ready = false;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();

        if (holder instanceof ContestWatchGui.Holder) {
            // 观战面板纯展示：所有操作都锁死，只留「关闭」
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player) || !inTop(event, top)) {
                return;
            }
            if (event.getRawSlot() == ContestWatchGui.closeSlot(top.getSize())) {
                player.closeInventory();
            }
            return;
        }

        if (holder instanceof ContestGui.Holder guiHolder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player) || !inTop(event, top)) {
                return;
            }
            int slot = event.getRawSlot();
            if (slot == ContestGui.WATCH_SLOT) {
                // 下一 tick 再开：这个界面正在关闭，别跟新界面抢背包
                Bukkit.getScheduler().runTask(guiHolder.plugin(),
                        () -> ContestWatchGui.open(guiHolder.plugin(), player));
            } else if (slot == ContestGui.CLOSE_SLOT) {
                player.closeInventory();
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof ContestWatchGui.Holder || holder instanceof ContestGui.Holder) {
            event.setCancelled(true);
        }
    }

    /** 界面关掉：立刻停掉这个玩家的每秒刷新任务 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof ContestWatchGui.Holder holder) {
            ContestWatchGui.onClose(holder);
        }
    }

    /** 玩家退出：界面可能不会再触发 close，这里兜一道 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        ContestWatchGui.onQuit(event.getPlayer().getUniqueId());
    }

    /** 点击是不是落在上层界面（点在玩家背包里就什么都不做） */
    private static boolean inTop(InventoryClickEvent event, Inventory top) {
        return event.getClickedInventory() != null && event.getClickedInventory().equals(top);
    }
}
