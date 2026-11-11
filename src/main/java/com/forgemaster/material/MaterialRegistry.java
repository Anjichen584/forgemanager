package com.forgemaster.material;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * 从 config.yml 读取材料表。
 */
public final class MaterialRegistry {

    private final Plugin plugin;
    private final Map<String, ForgeMaterial> materials = new LinkedHashMap<>();

    public MaterialRegistry(Plugin plugin) {
        this.plugin = plugin;
    }

    public void load(FileConfiguration cfg) {
        materials.clear();
        ConfigurationSection root = cfg.getConfigurationSection("materials");
        if (root == null) {
            plugin.getLogger().warning("config.yml 里没有 materials 段，材料表为空");
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) {
                continue;
            }
            try {
                Material item = Material.matchMaterial(sec.getString("item", "IRON_SWORD"));
                Material icon = Material.matchMaterial(sec.getString("icon", sec.getString("cost-item", "IRON_INGOT")));
                if (item == null || icon == null) {
                    plugin.getLogger().warning("材料 " + id + " 的物品名无效，已跳过");
                    continue;
                }
                Map<Material, Integer> costs = parseCosts(sec);
                ForgeMaterial m = new ForgeMaterial(
                        id,
                        sec.getString("display", id),
                        item,
                        icon,
                        sec.getInt("tier", 1),
                        sec.getDouble("ideal-temperature", 60),
                        Math.max(1.0, sec.getDouble("tolerance", 10)),
                        sec.getInt("strikes", 5),
                        sec.getInt("zone-width", 1),
                        costs,
                        sec.getDouble("bonus-attack", 1.0),
                        sec.getDouble("bonus-speed", 0.0),
                        (long) sec.getDouble("price", 0),
                        ForgeMaterial.Slot.byName(sec.getString("slot")),
                        sec.getDouble("bonus-armor", 0.0),
                        sec.getDouble("armor-toughness", 0.0),
                        sec.getInt("base-durability",
                                ForgeMaterial.defaultBaseDurability(sec.getInt("tier", 1)))
                );
                materials.put(id.toLowerCase(), m);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "解析材料 " + id + " 失败", ex);
            }
        }
        plugin.getLogger().info("已载入 " + materials.size() + " 种锻造材料");
    }

    /**
     * 解析耗材。优先读 {@code costs} 列表（支持多耗材），没有就退回单条的
     * {@code cost-item} / {@code cost-amount}。
     */
    private Map<Material, Integer> parseCosts(ConfigurationSection sec) {
        Map<Material, Integer> costs = new LinkedHashMap<>();
        List<?> list = sec.getList("costs");
        if (list != null) {
            for (Object raw : list) {
                String itemName = null;
                int amount = 1;
                if (raw instanceof ConfigurationSection cs) {
                    itemName = cs.getString("item", "");
                    amount = cs.getInt("amount", 1);
                } else if (raw instanceof Map<?, ?> map) {
                    // Bukkit 会把 list of map 还原成普通 Map
                    Object item = map.get("item");
                    Object amt = map.get("amount");
                    itemName = item == null ? "" : String.valueOf(item);
                    amount = parseInt(amt == null ? "1" : String.valueOf(amt), 1);
                } else if (raw instanceof String str) {
                    // 支持 "IRON_BLOCK x1" / "IRON_BLOCK:1" 简写
                    String[] parts = str.trim().split("\\s*[xX:]\\s*");
                    itemName = parts[0].trim();
                    amount = parts.length > 1 ? parseInt(parts[1], 1) : 1;
                }
                Material mat = itemName == null ? null : Material.matchMaterial(itemName.trim());
                if (mat != null && amount > 0) {
                    costs.merge(mat, amount, Integer::sum);
                }
            }
        }
        if (costs.isEmpty()) {
            Material cost = Material.matchMaterial(sec.getString("cost-item", "IRON_INGOT"));
            int amount = sec.getInt("cost-amount", 0);
            if (cost != null && amount > 0) {
                costs.put(cost, amount);
            }
        }
        return costs;
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public ForgeMaterial get(String id) {
        return id == null ? null : materials.get(id.toLowerCase());
    }

    public Collection<ForgeMaterial> all() {
        return materials.values();
    }

    public List<ForgeMaterial> sorted() {
        List<ForgeMaterial> list = new ArrayList<>(materials.values());
        list.sort((a, b) -> Integer.compare(a.tier(), b.tier()));
        return list;
    }

    public boolean isEmpty() {
        return materials.isEmpty();
    }
}