package com.forgemaster.auction;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/** 拍卖行浏览界面持有者：记录每个格子对应的拍品 id，用于路由点击。 */
public final class AuctionHolder implements InventoryHolder {

    private final Map<Integer, String> ids = new HashMap<>();
    private Inventory inventory;

    public void inventory(Inventory inv) {
        this.inventory = inv;
    }

    public void bind(int slot, String id) {
        ids.put(slot, id);
    }

    public String idAt(int slot) {
        return ids.get(slot);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
