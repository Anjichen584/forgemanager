package com.forgemaster.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * 武器档位界面持有者。和主界面分开，避免只靠槽位号区分两个界面。
 */
public final class WeaponMenuHolder implements InventoryHolder {

    private Inventory inventory;

    public void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
