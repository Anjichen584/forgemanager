package com.forgemaster.affix;

import org.bukkit.attribute.Attribute;

import java.util.Random;

/**
 * 一条词条。数值在 [min, max] 之间随机，落地到属性或 PDC 自定义效果。
 */
public final class Affix {

    public enum Kind {
        /** 原版属性（AttributeModifier） */
        ATTRIBUTE,
        /** 暴击率（自定义） */
        CRIT_CHANCE,
        /** 暴击伤害（自定义） */
        CRIT_DAMAGE,
        /** 生命偷取（自定义） */
        LIFESTEAL,
        /** 点燃（自定义） */
        FIRE,
        /** 受到伤害减免（自定义，装备用） */
        DAMAGE_REDUCTION,
        /**
         * 冰噬（v2.24 切片一，自定义）：命中附加短暂减速（Slowness II）。
         * 数值口径与 FIRE 相同——「整秒」，落 PDC 时换算成 tick 并过
         * {@code combat.caps.max-chill-ticks} 夹子。
         */
        CHILL,
        /**
         * 处决（v2.24 切片一，自定义）：目标血量比例低于阈值时本击增伤。
         * 阈值在 {@code combat.caps.execute-threshold}（默认 15%），增伤在暴击之前、
         * 伤害上限之后被 DamageBudget 结算，不是绕过预算的后门。
         */
        EXECUTE
    }

    private final String id;
    private final String display;
    private final Kind kind;
    private final Attribute attribute;
    private final double min;
    private final double max;
    private final String unit;
    private final boolean percent;
    private final int weight;

    public Affix(String id, String display, Kind kind, Attribute attribute,
                 double min, double max, String unit, boolean percent, int weight) {
        this.id = id;
        this.display = display;
        this.kind = kind;
        this.attribute = attribute;
        this.min = min;
        this.max = max;
        this.unit = unit;
        this.percent = percent;
        this.weight = Math.max(1, weight);
    }

    public String id() {
        return id;
    }

    public String display() {
        return display;
    }

    public Kind kind() {
        return kind;
    }

    public Attribute attribute() {
        return attribute;
    }

    public String unit() {
        return unit;
    }

    public boolean percent() {
        return percent;
    }

    public int weight() {
        return weight;
    }

    /** 配置里的取值下限（未缩放） */
    public double min() {
        return min;
    }

    /** 配置里的取值上限（未缩放） */
    public double max() {
        return max;
    }

    /** 不按材料档位缩放：等价于 valueScale = spreadScale = 1 */
    public double roll(Random random) {
        return roll(random, 1.0, 1.0);
    }

    /**
     * 按材料档位缩放后掷一个值。
     *
     * <p>基准 = (min+max)/2 × valueScale，波动半宽 = (max-min)/2 × spreadScale，
     * 结果落在「基准 ± 半宽」之间。低档材料 valueScale/spreadScale 都小，
     * 数值低且稳定；高档材料两者都大，能出极品也能出平庸货。</p>
     */
    public double roll(Random random, double valueScale, double spreadScale) {
        double center = (min + max) / 2.0 * Math.max(0.0, valueScale);
        double half = (max - min) / 2.0 * Math.max(0.0, spreadScale);
        double raw = half <= 0
                ? center
                : center + (random.nextDouble() * 2.0 - 1.0) * half;
        if (kind == Kind.FIRE || kind == Kind.CHILL) {
            // 点燃 / 冰噬时长按整秒取，别出现 2.23 秒这种（冰噬与点燃同一口径，v2.24 切片一）
            return Math.max(1, Math.rint(raw));
        }
        return Math.max(0, Math.round(raw * 100.0) / 100.0);
    }

    /** 词条在 loot 里显示的一行 */
    public String line(double value) {
        String amount = percent
                ? "+" + trim(value * 100.0) + "%"
                : "+" + trim(value);
        // 纯文本必须以「▪」开头：剥空词条、重画镶嵌块都靠这个前缀识别词条行
        return "<gray>▪ <white>" + display + "</white> <aqua>" + amount
                + "</aqua> <dark_gray>" + unit + "</dark_gray>";
    }

    private String trim(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.001) {
            return String.valueOf((long) Math.rint(v));
        }
        return String.format("%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}