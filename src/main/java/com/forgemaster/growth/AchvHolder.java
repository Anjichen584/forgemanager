package com.forgemaster.growth;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * 成就界面持有者。Lead 的 InventoryClickEvent 里判断 holder 类型即可。
 */
public final class AchvHolder implements InventoryHolder {

    private final int page;
    private final int pages;
    private Inventory inventory;

    public AchvHolder(int page, int pages) {
        this.page = Math.max(0, page);
        this.pages = Math.max(1, pages);
    }

    public int page() {
        return page;
    }

    public int pages() {
        return pages;
    }

    public void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
