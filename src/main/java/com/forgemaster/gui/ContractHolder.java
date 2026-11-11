package com.forgemaster.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.List;
import java.util.UUID;

/**
 * 龙税契约界面（贡品面板 / 圣物三选一）的 InventoryHolder：只挂上下文。
 */
public final class ContractHolder implements InventoryHolder {

    /** 面板模式 */
    public enum Mode { PANEL, PICK }

    private final UUID player;
    private final Mode mode;
    /** PICK 模式下的三个候选圣物 id */
    private final List<String> offer;
    private Inventory inventory;

    public ContractHolder(UUID player, Mode mode, List<String> offer) {
        this.player = player;
        this.mode = mode;
        this.offer = offer == null ? List.of() : offer;
    }

    public UUID player() {
        return player;
    }

    public Mode mode() {
        return mode;
    }

    public List<String> offer() {
        return offer;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void inventory(Inventory inventory) {
        this.inventory = inventory;
    }
}
