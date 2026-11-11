package com.forgemaster.commission;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/** 委托板的界面持有者，用来路由点击。 */
public final class BoardHolder implements InventoryHolder {

    private Inventory inventory;

    public void inventory(Inventory inv) {
        this.inventory = inv;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory == null ? Bukkit.createInventory(null, 27) : inventory;
    }
}