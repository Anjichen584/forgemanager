package com.forgemaster.util;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.material.ArmorCatalog;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.quality.Quality;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * PDC 反查工具：把物品 meta 上的材料 id / 品质还原成领域对象。
 *
 * <p>为什么收敛到这里：{@code StrengthenService}、{@code Reforge}、{@code GemService}
 * 各自有一份「读 meta → 解析 Keys.MATERIAL / Keys.QUALITY」的近似实现
 * （audit 04-quality D5），兜底细节略有出入还各自漂移。统一语义取三者并集中最稳的一种：
 * 材料走 {@link ArmorCatalog#resolve}（装备 id 也能还原成胚子），品质无键 / 键值非法一律兜底
 * {@link Quality#COMMON}（损坏数据不再外泄 null）。解析本体是纯读取，无副作用。</p>
 *
 * <p>「必须有 FORGED 标记才允许反查」是业务校验而不是解析，不属于这里的职责——
 * 需要这道门的入口（如 {@code StrengthenService.materialOf/qualityOf}）在调用方自行判断。</p>
 */
public final class MaterialLookup {

    private MaterialLookup() {
    }

    /** meta 上的材料 id 原文（{@code Keys.MATERIAL}）；meta 为 null 或无键返回 null */
    public static String materialIdOf(ItemMeta meta) {
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(Keys.MATERIAL, PersistentDataType.STRING);
    }

    /**
     * 材料 id 反查胚子：剑胚 id 直接查注册表，{@code armor-<剑胚>-<部位>} 装备 id
     * 由 ArmorCatalog 还原；id 无效（胚子被服主删掉等）返回 null。
     */
    public static ForgeMaterial resolve(ForgingMasterPlugin plugin, String id) {
        return ArmorCatalog.resolve(plugin.materials(), id,
                plugin.settings().num("armor.price-multiplier", 1.0));
    }

    /** meta 反查胚子；meta 为 null 或没有材料键返回 null */
    public static ForgeMaterial materialOf(ForgingMasterPlugin plugin, ItemMeta meta) {
        return resolve(plugin, materialIdOf(meta));
    }

    /** 物品反查胚子；item 为 null 返回 null */
    public static ForgeMaterial materialOf(ForgingMasterPlugin plugin, ItemStack item) {
        if (item == null) {
            return null;
        }
        return materialOf(plugin, item.getItemMeta());
    }

    /** meta 反查品质：meta 为 null、无键、键值非法一律兜底 COMMON */
    public static Quality qualityOf(ItemMeta meta) {
        if (meta == null) {
            return Quality.COMMON;
        }
        String raw = meta.getPersistentDataContainer().get(Keys.QUALITY, PersistentDataType.STRING);
        Quality q = raw == null ? null : Quality.byName(raw);
        return q == null ? Quality.COMMON : q;
    }

    /** 物品反查品质：item 为 null / 空气时兜底 COMMON */
    public static Quality qualityOf(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return Quality.COMMON;
        }
        return qualityOf(item.getItemMeta());
    }
}
