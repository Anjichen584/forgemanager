package com.forgemaster.gui;

import com.forgemaster.forge.ForgeSession;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * 锻造界面持有者，用来把 InventoryClickEvent 精准路由到会话。
 */
public final class ForgeHolder implements InventoryHolder {

    private final ForgeSession session;
    private Inventory inventory;

    public ForgeHolder(ForgeSession session) {
        this.session = session;
    }

    public ForgeSession session() {
        return session;
    }

    public void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}