package com.forgemaster.metrics;

import com.forgemaster.ForgingMasterPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * 数据看板 GUI 的点击路由（照 {@code TitleGuiRouter} 的懒注册做法）。
 *
 * <p>{@code ForgeListener} 不认本 holder，这里自备一份：所有点击与拖拽一律取消，
 * 纸片图标拿不出去。优先级 LOWEST，比 ForgeListener 的 HIGH 先跑。</p>
 */
public final class MetricsGuiRouter implements Listener {

    private static boolean ready;

    private MetricsGuiRouter() {
    }

    /** 第一次打开看板时注册一次 */
    static synchronized void ensure(ForgingMasterPlugin plugin) {
        if (ready || plugin == null) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(new MetricsGuiRouter(), plugin);
        ready = true;
    }

    /**
     * 插件卸载时复位。
     *
     * <p>{@code ready} 是 static：{@code /reload} 后旧实例的监听器已随旧插件注销，
     * 但静态标记还是 true，{@code ensure()} 会直接返回，界面就失去点击拦截。
     * 这是 {@code ReforgeMenu.listenerReady} 栽过的同一个坑，别再犯。</p>
     */
    public static synchronized void reset() {
        ready = false;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MetricsGui.Holder holder)) {
            return;
        }
        event.setCancelled(true); // 看板是只读面板：任何格子都不许动
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!holder.viewer().equals(player.getUniqueId())) {
            return; // 别人的面板（理论上不存在，兜一层）
        }
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= MetricsGui.SIZE) {
            return; // 点在玩家背包侧：已取消，什么都不做
        }
        var plugin = holder.plugin();
        int page = holder.page();
        switch (raw) {
            case MetricsGui.PREV_SLOT -> MetricsGui.open(plugin, player,
                    (page - 1 + MetricsGui.PAGES) % MetricsGui.PAGES);
            case MetricsGui.NEXT_SLOT -> MetricsGui.open(plugin, player,
                    (page + 1) % MetricsGui.PAGES);
            case MetricsGui.REFRESH_SLOT -> {
                var svc = plugin.metrics();
                if (svc != null) {
                    svc.sampleNow(); // 立刻采一次，玩家不用等一个采样周期
                }
                MetricsGui.open(plugin, player, page);
            }
            case MetricsGui.CLOSE_SLOT -> player.closeInventory();
            default -> {
                // 内容行与空白格：已取消，无动作
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof MetricsGui.Holder) {
            event.setCancelled(true);
        }
    }
}