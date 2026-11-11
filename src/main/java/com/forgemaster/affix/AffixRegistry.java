package com.forgemaster.affix;

import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;

/**
 * 词条表。config.yml 的 affixes 段。
 */
public final class AffixRegistry {

    private final Plugin plugin;
    private final Map<String, Affix> affixes = new LinkedHashMap<>();
    private final Random random = new Random();

    public AffixRegistry(Plugin plugin) {
        this.plugin = plugin;
    }

    public void load(FileConfiguration cfg) {
        affixes.clear();
        ConfigurationSection root = cfg.getConfigurationSection("affixes");
        if (root == null) {
            plugin.getLogger().warning("config.yml 里没有 affixes 段");
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) {
                continue;
            }
            try {
                Affix.Kind kind = Affix.Kind.valueOf(sec.getString("kind", "ATTRIBUTE").toUpperCase());
                Attribute attribute = null;
                if (kind == Affix.Kind.ATTRIBUTE) {
                    attribute = matchAttribute(sec.getString("attribute", "attack_damage"));
                    if (attribute == null) {
                        plugin.getLogger().warning("词条 " + id + " 的属性名无效，已跳过");
                        continue;
                    }
                }
                affixes.put(id.toLowerCase(), new Affix(
                        id,
                        sec.getString("display", id),
                        kind,
                        attribute,
                        sec.getDouble("min", 1),
                        sec.getDouble("max", 2),
                        sec.getString("unit", ""),
                        sec.getBoolean("percent", false),
                        sec.getInt("weight", 10)
                ));
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "解析词条 " + id + " 失败", ex);
            }
        }
        plugin.getLogger().info("已载入 " + affixes.size() + " 条词条");
    }

    private Attribute matchAttribute(String name) {
        String key = name.toLowerCase().replace(' ', '_');
        return switch (key) {
            case "attack_damage" -> Attribute.ATTACK_DAMAGE;
            case "attack_speed" -> Attribute.ATTACK_SPEED;
            case "attack_knockback" -> Attribute.ATTACK_KNOCKBACK;
            case "movement_speed" -> Attribute.MOVEMENT_SPEED;
            case "max_health" -> Attribute.MAX_HEALTH;
            case "armor" -> Attribute.ARMOR;
            case "armor_toughness" -> Attribute.ARMOR_TOUGHNESS;
            case "knockback_resistance" -> Attribute.KNOCKBACK_RESISTANCE;
            case "sweeping_damage_ratio" -> Attribute.SWEEPING_DAMAGE_RATIO;
            default -> null;
        };
    }

    public Affix get(String id) {
        return id == null ? null : affixes.get(id.toLowerCase());
    }

    public Collection<Affix> all() {
        return affixes.values();
    }

    /**
     * 按淬火池抽取词条，池子抽完则从全表补齐；同名词条不重复。
     */
    public List<Affix> pick(List<String> pool, int count) {
        return pick(pool, count, null);
    }

    /**
     * 在给定池内抽；抽不满时只用 {@code fallbackIds} 补，绝不回退到整张词条表。
     *
     * <p>为什么必须这么改：原来的兜底是 {@code affixes.values()}——<b>整张表</b>。
     * 于是池子只有 2 条（油淬）而品质要 4 条时，剩下 2 条从全表捞，
     * 剑上会抽出「护甲值」「生命上限」，胸甲上会抽出「暴击率」。
     * 这就是「词条都是乱的」的根：抽不满就直接放弃了分类约束。</p>
     *
     * @param fallbackIds 补位池；{@code null} 表示沿用旧行为（整张表，仅供非物品场景）
     */
    public List<Affix> pick(List<String> pool, int count, List<String> fallbackIds) {
        List<Affix> result = new ArrayList<>();
        List<Affix> candidates = new ArrayList<>();
        for (String id : pool) {
            Affix a = get(id);
            if (a != null && !candidates.contains(a)) {
                candidates.add(a);
            }
        }
        if (candidates.isEmpty() && fallbackIds == null) {
            candidates.addAll(affixes.values());
        }
        while (result.size() < count && !candidates.isEmpty()) {
            Affix chosen = weightedPick(candidates);
            result.add(chosen);
            candidates.remove(chosen);
        }
        if (result.size() < count) {
            List<Affix> fallback = new ArrayList<>();
            if (fallbackIds == null) {
                fallback.addAll(affixes.values());
            } else {
                for (String id : fallbackIds) {
                    Affix a = get(id);
                    if (a != null) {
                        fallback.add(a);
                    }
                }
            }
            fallback.removeAll(result);
            while (result.size() < count && !fallback.isEmpty()) {
                Affix chosen = weightedPick(fallback);
                result.add(chosen);
                fallback.remove(chosen);
            }
        }
        return result;
    }

    /**
     * 只在给定池内抽，抽不满就少给，绝不回退到全表。
     *
     * <p>装备用这个，避免防御装备抽到「火焰附加」这种武器词条。</p>
     */
    public List<Affix> pickStrict(List<String> pool, int count) {
        List<Affix> result = new ArrayList<>();
        List<Affix> candidates = new ArrayList<>();
        for (String id : pool) {
            Affix a = get(id);
            if (a != null && !candidates.contains(a)) {
                candidates.add(a);
            }
        }
        while (result.size() < count && !candidates.isEmpty()) {
            Affix chosen = weightedPick(candidates);
            result.add(chosen);
            candidates.remove(chosen);
        }
        return result;
    }

    private Affix weightedPick(List<Affix> list) {
        int total = 0;
        for (Affix a : list) {
            total += a.weight();
        }
        int roll = random.nextInt(Math.max(1, total));
        int cursor = 0;
        for (Affix a : list) {
            cursor += a.weight();
            if (roll < cursor) {
                return a;
            }
        }
        return list.get(0);
    }
}