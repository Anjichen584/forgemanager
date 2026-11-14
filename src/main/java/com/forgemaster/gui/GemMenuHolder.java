package com.forgemaster.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * 宝石台界面持有者。
 */
public final class GemMenuHolder implements InventoryHolder {

    /** v2.25.1：界面主人（「点击者 = 界面主人」契约，与 ForgeHolder 同款） */
    private final java.util.UUID owner;

    private Inventory inventory;

    public GemMenuHolder(java.util.UUID owner) {
        this.owner = owner == null ? java.util.UUID.randomUUID() : owner;
    }

    public java.util.UUID owner() {
        return owner;
    }

    public void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
