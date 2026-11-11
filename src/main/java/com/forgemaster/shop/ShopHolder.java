package com.forgemaster.shop;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * 商会界面持有者，记录当前页码。
 */
public final class ShopHolder implements InventoryHolder {

    private Inventory inventory;
    private int page;

    public ShopHolder(int page) {
        this.page = page;
    }

    public int page() {
        return page;
    }

    public void page(int page) {
        this.page = page;
    }

    public void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
