package com.forgemaster.auction;

import com.forgemaster.util.Msg;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * 拍卖行界面点击路由。
 * 左键 = 按最低加价出价；右键 = 一口价；Shift+左键 = 下架自己的拍品。
 */
public final class AuctionListener implements Listener {

    private final AuctionManager manager;

    public AuctionListener(AuctionManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof AuctionHolder holder)) {
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
        if (slot == AuctionManager.SLOT_CLOSE) {
            player.closeInventory();
            return;
        }
        if (slot == AuctionManager.SLOT_REFRESH) {
            player.openInventory(manager.buildBrowse(player));
            return;
        }
        if (slot == AuctionManager.SLOT_MAIL) {
            Msg.prefix(player, manager.claim(player));
            player.openInventory(manager.buildBrowse(player));
            return;
        }
        String id = holder.idAt(slot);
        if (id == null) {
            return;
        }
        ClickType click = event.getClick();
        String result;
        if (click.isShiftClick() && click.isLeftClick()) {
            result = manager.cancel(player, id);
        } else if (click.isRightClick()) {
            result = manager.buyout(player, id);
        } else {
            result = manager.quickBid(player, id);
        }
        Msg.prefix(player, result);
        player.openInventory(manager.buildBrowse(player));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof AuctionHolder) {
            event.setCancelled(true);
        }
    }
}
