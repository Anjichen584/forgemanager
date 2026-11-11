package com.forgemaster.gui;

import com.forgemaster.material.ForgeMaterial;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * 装备锻造菜单持有者。
 *
 * <p>两段式：先选部位，再选档位。档位界面要记住玩家选的是哪个部位。</p>
 */
public final class ArmorMenuHolder implements InventoryHolder {

    public enum Stage {
        /** 选部位 */
        SLOT,
        /** 选档位 */
        TIER
    }

    private final Stage stage;
    private final ForgeMaterial.Slot slot;
    private Inventory inventory;

    public ArmorMenuHolder(Stage stage, ForgeMaterial.Slot slot) {
        this.stage = stage;
        this.slot = slot;
    }

    public Stage stage() {
        return stage;
    }

    /** 仅 TIER 阶段有值 */
    public ForgeMaterial.Slot slot() {
        return slot;
    }

    public void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
