package com.forgemaster.ranking;

import com.forgemaster.ForgingMasterPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * 排行榜界面的点击路由：切换维度 / 关闭。榜单格子只看不点。
 */
public final class RankListener implements Listener {

    private final ForgingMasterPlugin plugin;

    public RankListener(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof RankHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == RankMenu.CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        Metric[] metrics = Metric.values();
        for (int i = 0; i < RankMenu.METRIC_SLOTS.length && i < metrics.length; i++) {
            if (RankMenu.METRIC_SLOTS[i] == slot) {
                Metric next = metrics[i];
                // 强化维度关掉时那个槽位是填充物，点了什么都不做
                if (next.online() && !StrengthenSnapshot.enabled(plugin)) {
                    return;
                }
                if (next != holder.metric()) {
                    player.openInventory(plugin.rankMenu().build(player, next));
                }
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof RankHolder) {
            event.setCancelled(true);
        }
    }
}
