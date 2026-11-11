package com.forgemaster.evolve;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * 蜕变界面（锻阶）的 InventoryHolder：只挂上下文，逻辑在 EvolutionMenu / EvolutionService。
 */
public final class EvolutionHolder implements InventoryHolder {

    private final java.util.UUID player;
    private final int stageIndex;
    private Inventory inventory;

    public EvolutionHolder(java.util.UUID player, int stageIndex) {
        this.player = player;
        this.stageIndex = stageIndex;
    }

    /** 打开界面时的持有者玩家 */
    public java.util.UUID player() {
        return player;
    }

    /** 界面展示的目标阶段号（下一阶） */
    public int stageIndex() {
        return stageIndex;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void inventory(Inventory inventory) {
        this.inventory = inventory;
    }
}
