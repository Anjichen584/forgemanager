package com.forgemaster.gem;

import com.forgemaster.quality.Quality;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;

import java.util.List;
import java.util.Random;

/**
 * 宝石种类。宝石不给「直接镶嵌」的成品，必须先开孔再嵌。
 */
public enum GemType {

    // v2.17.3：赤玉基础区间 0.8~1.6 → 0.4~0.8——Lv5 ×5.0 后从 8.0 降到 4.0，
    // 三颗满级赤玉 +12 ≈ 强化乘区本体（25.9）的一半以内，不再反客为主。
    // 注意：已镶嵌宝石的数值存在 PDC 里，只对新熔炼的宝石生效。
    ATTACK("attack", "赤玉", "攻击力", Attribute.ATTACK_DAMAGE, null,
            0.4, 0.8, "#ff5b5b", "attack_damage"),
    // v2.21 暴击重调：原来 0.04~0.09，L5 倍率 ×5 后单颗就是 20%~45%——
    // 比一条「破绽」词条的 6%~22% 还猛，宝石和词条明显不是同一把尺子量的。
    // 降到 0.015~0.030（L5 = 7.5%~15%），和词条同量级。
    // 已镶嵌的宝石数值存在 PDC 里，所以老宝石保值，只影响新熔炼的。
    CRIT("crit", "星辉石", "暴击率", null, "crit",
            0.015, 0.030, "#ffd166", "crit"),
    // 同上：0.15~0.30 → L5 单颗 75%~150%，三颗就 +450%。
    // 降到 0.06~0.12（L5 = 30%~60%），和「致命」词条的 25%~90% 同量级。
    CRIT_DAMAGE("crit_damage", "破军石", "暴击伤害", null, "crit_damage",
            0.06, 0.12, "#ff8a3d", "crit_damage"),
    FIRE("fire", "焚天石", "点燃", null, "fire",
            1.0, 2.0, "#ff4d4d", "fire"),
    LIFESTEAL("lifesteal", "血玉", "生命偷取", null, "lifesteal",
            0.03, 0.06, "#b06bff", "lifesteal"),
    ARMOR("armor", "玄铁石", "护甲", Attribute.ARMOR, null,
            1.2, 2.4, "#9fb6c9", "armor"),
    TOUGHNESS("toughness", "金刚石", "盔甲韧性", Attribute.ARMOR_TOUGHNESS, null,
            0.5, 1.2, "#c9d6e3", "armor_toughness"),
    LIFE("life", "血珀", "生命上限", Attribute.MAX_HEALTH, null,
            2.0, 4.0, "#ff3d6e", "max_health"),
    SPEED("speed", "风行石", "移动速度", Attribute.MOVEMENT_SPEED, null,
            0.01, 0.02, "#7ef2c8", "movement_speed"),
    KNOCKBACK_RESIST("knockback_resist", "镇岳石", "击退抗性", Attribute.KNOCKBACK_RESISTANCE, null,
            0.03, 0.07, "#a0a0a0", "knockback_resistance"),
    ATTACK_SPEED("attack_speed", "疾风石", "攻击速度", Attribute.ATTACK_SPEED, null,
            0.02, 0.05, "#7fd3ff", "attack_speed");

    /** 宝石等级：五级封顶，三颗同级合成上一级 */
    public enum Grade {
        L1("Lv.1", "#9e9e9e", 1.0, 40),
        L2("Lv.2", "#4ee36b", 1.6, 28),
        L3("Lv.3", "#7fd3ff", 2.4, 18),
        L4("Lv.4", "#b06bff", 3.6, 10),
        L5("Lv.5", "#ffb84d", 5.0, 4);

        private final String display;
        private final String color;
        private final double mult;
        private final int weight;

        Grade(String display, String color, double mult, int weight) {
            this.display = display;
            this.color = color;
            this.mult = mult;
            this.weight = weight;
        }

        public String display() {
            return display;
        }

        public String color() {
            return color;
        }

        public double mult() {
            return mult;
        }

        public int weight() {
            return weight;
        }

        public String colored() {
            return "<" + color + ">" + display + "</" + color + ">";
        }

        /** 已经是最高级 */
        public boolean maxed() {
            return this == L5;
        }

        public Grade next() {
            return maxed() ? null : values()[ordinal() + 1];
        }

        public static Grade byName(String raw) {
            if (raw == null) {
                return L1;
            }
            for (Grade g : values()) {
                if (g.name().equalsIgnoreCase(raw)) {
                    return g;
                }
            }
            // 兼容旧数据：以前的 COMMON / FINE / EPIC / LEGENDARY
            return switch (raw.toUpperCase()) {
                case "COMMON" -> L1;
                case "FINE" -> L2;
                case "EPIC" -> L4;
                case "LEGENDARY" -> L5;
                default -> L1;
            };
        }

        public static Grade roll(Random random) {
            int total = 0;
            for (Grade g : values()) {
                total += g.weight;
            }
            int roll = random.nextInt(Math.max(1, total));
            int cursor = 0;
            for (Grade g : values()) {
                cursor += g.weight;
                if (roll < cursor) {
                    return g;
                }
            }
            return L1;
        }
    }

    private final String id;
    private final String name;
    private final String unit;
    private final Attribute attribute;
    private final String pdcKey;
    private final double min;
    private final double max;
    private final String color;

    GemType(String id, String name, String unit, Attribute attribute, String pdcKey,
            double min, double max, String color, String unusedKey) {
        this.id = id;
        this.name = name;
        this.unit = unit;
        this.attribute = attribute;
        this.pdcKey = pdcKey;
        this.min = min;
        this.max = max;
        this.color = color;
    }

    public String id() {
        return id;
    }

    public String gemName() {
        return name;
    }

    public String unit() {
        return unit;
    }

    /** 走原版属性的宝石有值，走 PDC 的（暴击 / 吸血 / 点燃）为 null */
    public Attribute attribute() {
        return attribute;
    }

    public boolean percent() {
        return attribute == null && !"fire".equals(pdcKey);
    }

    /** PDC 类宝石的键名；属性类为 null */
    public String pdcKey() {
        return pdcKey;
    }

    public String color() {
        return color;
    }

    public String colored() {
        return "<" + color + ">" + name + "</" + color + ">";
    }

    /**
     * 该宝石在当前等级下的**理论上限**数值（基础区间上限 × 等级倍率）。
     *
     * <p>给「满配发放」用：管理员发的东西必须是同口径的最好值，不能随手填一个
     * 比真实熔炼还高的数。所以这里复用 {@link #max} 与 {@link Grade#mult()}，
     * 和 {@link #roll} 同一套算法，只是不掷随机。</p>
     */
    public double maxAt(Grade grade) {
        double raw = max * (grade == null ? Grade.L1 : grade).mult();
        return Math.round(raw * 100.0) / 100.0;
    }

    /** 掷一个数值：基础区间 × 等级倍率 */
    public double roll(Grade grade, Random random) {
        double raw = min + random.nextDouble() * (max - min);
        raw *= grade.mult();
        return Math.round(raw * 100.0) / 100.0;
    }

    /** 数值展示：百分比类显示成 % */
    public String format(double value) {
        String amount;
        if (percent()) {
            amount = "+" + trim(value * 100) + "%";
        } else if ("fire".equals(pdcKey)) {
            amount = "+" + trim(value) + "s";
        } else {
            amount = "+" + trim(value);
        }
        return "<gray>▪ <" + color + ">" + name + "</" + color + "> <aqua>" + amount + " <gray>" + unit;
    }

    private static String trim(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.001) {
            return String.valueOf((long) Math.rint(v));
        }
        return String.format("%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    public Material icon() {
        return switch (this) {
            case ATTACK -> Material.RED_DYE;
            case CRIT -> Material.GLOWSTONE_DUST;
            case CRIT_DAMAGE -> Material.BLAZE_POWDER;
            case FIRE -> Material.MAGMA_CREAM;
            case LIFESTEAL -> Material.GHAST_TEAR;
            case ARMOR -> Material.IRON_NUGGET;
            case TOUGHNESS -> Material.AMETHYST_SHARD;
            case LIFE -> Material.REDSTONE;
            case SPEED -> Material.FEATHER;
            case KNOCKBACK_RESIST -> Material.SHIELD;
            case ATTACK_SPEED -> Material.SUGAR;
        };
    }

    public static GemType byId(String id) {
        if (id == null) {
            return null;
        }
        for (GemType t : values()) {
            if (t.id.equalsIgnoreCase(id)) {
                return t;
            }
        }
        return null;
    }

    /**
     * 熔炼某种材料的宝石池。
     *
     * <p>宝石唯一来源是下界之星，其它矿物一律空池。</p>
     */
    public static List<GemType> poolOf(Material input) {
        if (input == Material.NETHER_STAR) {
            return List.of(values());
        }
        return List.of();
    }

    /** 下界之星是特殊投料：一颗就够，且保底 Lv.4 */
    public static boolean isStarInput(Material input) {
        return input == Material.NETHER_STAR;
    }

    /** 孔位上限的文案辅助（孔数按材料档位定，与品质无关） */
    public static String socketHint(int sockets) {
        return "<gray>按材料档位最多开 <white>" + sockets + "</white> 个孔（宝石与附魔共用）";
    }
}
