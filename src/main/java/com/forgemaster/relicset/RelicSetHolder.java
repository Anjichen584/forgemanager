package com.forgemaster.relicset;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/**
 * 套装矩阵界面（列表页 / 部件详情页）的 InventoryHolder：只挂上下文，
 * 写法对齐 gui 包的 {@code ContractHolder}——不挂 Inventory 引用之外的状态。
 */
public final class RelicSetHolder implements InventoryHolder {

    /** 页面模式 */
    public enum Mode {
        /** 列表页：全部套装（内置 + 配置） */
        LIST,
        /** 详情页：单套的四个部件位与收集状态 */
        DETAIL
    }

    private final UUID player;
    private final Mode mode;
    /** DETAIL 模式下的套装 id；LIST 模式为 null */
    private final String setId;
    private Inventory inventory;

    public RelicSetHolder(UUID player, Mode mode, String setId) {
        this.player = player;
        this.mode = mode;
        this.setId = setId;
    }

    public UUID player() {
        return player;
    }

    public Mode mode() {
        return mode;
    }

    public String setId() {
        return setId;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void inventory(Inventory inventory) {
        this.inventory = inventory;
    }
}
