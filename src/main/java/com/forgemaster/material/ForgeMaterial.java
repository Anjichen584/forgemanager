package com.forgemaster.material;

import org.bukkit.Material;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一种可锻造的胚子：武器（剑）或装备（头盔 / 胸甲 / 护腿 / 靴子）。
 * 参数全部来自 config.yml。
 */
public final class ForgeMaterial {

    /** 装备部位；武器为 null */
    public enum Slot {
        HELMET("头盔", org.bukkit.inventory.EquipmentSlotGroup.HEAD, "HELMET", 0.8),
        CHESTPLATE("胸甲", org.bukkit.inventory.EquipmentSlotGroup.CHEST, "CHESTPLATE", 1.0),
        LEGGINGS("护腿", org.bukkit.inventory.EquipmentSlotGroup.LEGS, "LEGGINGS", 0.9),
        BOOTS("靴子", org.bukkit.inventory.EquipmentSlotGroup.FEET, "BOOTS", 0.75);

        private final String label;
        private final org.bukkit.inventory.EquipmentSlotGroup group;
        private final String suffix;
        private final double durabilityFactor;

        Slot(String label, org.bukkit.inventory.EquipmentSlotGroup group, String suffix, double durabilityFactor) {
            this.label = label;
            this.group = group;
            this.suffix = suffix;
            this.durabilityFactor = durabilityFactor;
        }

        public String label() {
            return label;
        }

        public org.bukkit.inventory.EquipmentSlotGroup group() {
            return group;
        }

        public String suffix() {
            return suffix;
        }

        /** 同档位下部位的耐久系数：胸甲最厚，靴子最薄 */
        public double durabilityFactor() {
            return durabilityFactor;
        }

        public static Slot byName(String raw) {
            if (raw == null) {
                return null;
            }
            for (Slot s : values()) {
                if (s.name().equalsIgnoreCase(raw)) {
                    return s;
                }
            }
            return null;
        }
    }

    private final String id;
    private final String display;
    private final Material item;
    private final Material icon;
    private final int tier;
    private final double idealTemp;
    private final double tolerance;
    private final int strikes;
    private final int zoneWidth;
    private final Map<Material, Integer> costs;
    private final double bonusAttack;
    private final double bonusSpeed;
    private final long price;
    private final Slot slot;
    private final double bonusArmor;
    private final double armorToughness;
    private final int baseDurability;

    public ForgeMaterial(String id, String display, Material item, Material icon, int tier,
                         double idealTemp, double tolerance, int strikes, int zoneWidth,
                         Material costItem, int costAmount, double bonusAttack, double bonusSpeed) {
        this(id, display, item, icon, tier, idealTemp, tolerance, strikes, zoneWidth,
                costItem == null || costAmount <= 0
                        ? Collections.emptyMap()
                        : Collections.singletonMap(costItem, costAmount),
                bonusAttack, bonusSpeed, 0L, null, 0.0, 0.0, defaultBaseDurability(tier));
    }

    public ForgeMaterial(String id, String display, Material item, Material icon, int tier,
                         double idealTemp, double tolerance, int strikes, int zoneWidth,
                         Map<Material, Integer> costs, double bonusAttack, double bonusSpeed,
                         long price, Slot slot, double bonusArmor, double armorToughness,
                         int baseDurability) {
        this.id = id;
        this.display = display;
        this.item = item;
        this.icon = icon;
        this.tier = tier;
        this.idealTemp = idealTemp;
        this.tolerance = tolerance;
        this.strikes = Math.max(1, strikes);
        this.zoneWidth = Math.max(0, Math.min(3, zoneWidth));
        this.costs = costs == null || costs.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(costs));
        this.bonusAttack = bonusAttack;
        this.bonusSpeed = bonusSpeed;
        this.price = Math.max(0, price);
        this.slot = slot;
        this.bonusArmor = bonusArmor;
        this.armorToughness = armorToughness;
        this.baseDurability = Math.max(1, baseDurability);
    }

    public String id() {
        return id;
    }

    public String display() {
        return display;
    }

    public Material item() {
        return item;
    }

    public Material icon() {
        return icon;
    }

    public int tier() {
        return tier;
    }

    public double idealTemp() {
        return idealTemp;
    }

    public double tolerance() {
        return tolerance;
    }

    public int strikes() {
        return strikes;
    }

    public int zoneWidth() {
        return zoneWidth;
    }

    /** 全部耗材：材质 → 数量 */
    public Map<Material, Integer> costs() {
        return costs;
    }

    /** 第一种耗材（委托奖励、熔炼返还用；多耗材时取第一种） */
    public Material costItem() {
        return costs.isEmpty() ? Material.IRON_INGOT : costs.keySet().iterator().next();
    }

    /** 第一种耗材的数量 */
    public int costAmount() {
        return costs.isEmpty() ? 0 : costs.values().iterator().next();
    }

    /** 是否多耗材（星陨这类） */
    public boolean multiCost() {
        return costs.size() > 1;
    }

    public double bonusAttack() {
        return bonusAttack;
    }

    public double bonusSpeed() {
        return bonusSpeed;
    }

    /** 锻造费（服务器货币） */
    public long price() {
        return price;
    }

    /** 装备部位；武器返回 null */
    public Slot slot() {
        return slot;
    }

    public boolean armor() {
        return slot != null;
    }

    /** 额外护甲值 */
    public double bonusArmor() {
        return bonusArmor;
    }

    /** 额外盔甲韧性 */
    public double armorToughness() {
        return armorToughness;
    }

    /** 基础耐久（品质倍数在结算时再乘） */
    public int baseDurability() {
        return baseDurability;
    }

    /** 配置没写 base-durability 时按档位兜底 */
    public static int defaultBaseDurability(int tier) {
        return switch (tier) {
            case 1 -> 250;
            case 2 -> 300;
            case 3 -> 800;
            case 4 -> 1300;
            default -> 1600;
        };
    }

    /** 温度是否落在最佳区间 */
    public boolean inBand(double temp) {
        return Math.abs(temp - idealTemp) <= tolerance;
    }

    /** 温度偏离最佳区间多远（0 = 正中） */
    public double deviation(double temp) {
        return Math.abs(temp - idealTemp);
    }
}
