package com.forgemaster.ranking;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * 排行榜界面持有者，用来把点击精准路由到 RankListener。
 */
public final class RankHolder implements InventoryHolder {

    private final Metric metric;
    private Inventory inventory;

    public RankHolder(Metric metric) {
        this.metric = metric;
    }

    public Metric metric() {
        return metric;
    }

    public void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
