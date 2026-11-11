package com.forgemaster.enchant;

/**
 * 一条原创附魔的定义（v2.23.2）。
 *
 * <h2>为什么不直接用原版 {@code Enchantment}</h2>
 * 原版附魔注册表是静态的，插件无法在运行时注册新的 {@code Enchantment} 实例
 * （除非动 NMS / 注册表注入，那会把兼容性赌在版本号上）。
 * 所以原创附魔走自己的通道：存 PDC（{@code Keys.CUSTOM_ENCH}），
 * 效果由插件自己在事件里算。
 *
 * <h2>数值纪律（这一条比功能重要）</h2>
 * 每条附魔**只声明「每级给多少」和「最多几级」**，绝对不给「最终值」。
 * 最终值一律由 {@link EnchantEffects} 汇总后再过一次全局夹子
 * （{@code enchant.caps.*}）。原因很直白：
 *
 * <ul>
 *   <li>附魔会叠——武器上 8 条、护甲上 4 条，每条单独看都温和，加起来就能顶穿上限；</li>
 *   <li>叠加如果按乘法算，1.075 × 1.06 × 1.10 × 1.15 × 1.09 ≈ 1.59，
 *       看起来「每条都很小」，实际却是 +59%；</li>
 *   <li>所以这里<b>全部按加法汇总</b>，再统一夹到配置上限。加法可预测、可验证、可解释。</li>
 * </ul>
 *
 * <p>另外每一类效果上限都比 {@code combat.limits.*} 更紧：
 * 附魔只是「在已有预算里再分一小块」，不能变成绕过伤害预算的后门。</p>
 *
 * @param id       稳定 id（存盘用，改了就是另一条附魔，不能动）
 * @param display  显示名（可改，只影响 Lore）
 * @param maxLevel 最高等级
 * @param kind     效果类别
 * @param slot     适用部位
 * @param perLevel 每级贡献（含义由 {@link Kind} 决定）
 * @param weight   随机产出权重（越大越常见）
 * @param desc     一行说明（Lore 用，{@code %v} 会被换成当前数值）
 */
public record CustomEnchant(String id, String display, int maxLevel, Kind kind, Slot slot,
                            double perLevel, double weight, String desc) {

    /** 效果类别。每一项都在 {@link EnchantEffects} 里有且仅有一个消费点。 */
    public enum Kind {
        /** 最终伤害加成（加法百分比） */
        DAMAGE_MUL,
        /** 破甲：额外伤害（加法百分比，等价于无视一部分护甲） */
        ARMOR_PIERCE,
        /** 攻速加成 */
        ATTACK_SPEED,
        /** 吸血加成（仍受 DamageBudget 三道夹子） */
        LIFESTEAL_ADD,
        /** 点燃时长加成（秒，仍受 max-fire-seconds 夹） */
        FIRE_ADD,
        /** 对敌对生物额外伤害 */
        VS_MONSTER,
        /** 斩杀：目标血量低于阈值时额外伤害 */
        EXECUTE,
        /** 连击：连续命中同目标叠层 */
        COMBO,
        /** 受伤减免（仍受 max-damage-reduce 夹） */
        DAMAGE_REDUCE,
        /** 荆棘：反弹受到伤害的百分比 */
        THORNS,
        /** 生命上限加成（仍受 max-bonus-health 夹） */
        HEALTH_ADD,
        /** 火焰伤害减免 */
        FIRE_RESIST,
        /** 经验加成 */
        EXP_BONUS,
        /** 挖矿额外掉落几率 */
        DROP_BONUS,
        /** 剑币产出加成 */
        COIN_BONUS
    }

    /** 适用部位 */
    public enum Slot {
        WEAPON("武器"),
        ARMOR("装备"),
        ANY("通用");

        private final String label;

        Slot(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 某一级实际贡献多少。
     *
     * <p>唯一入口，别在别处再乘一次 {@code perLevel} —— 那正是「散落多份公式」
     * 这类 bug 的起手式。</p>
     */
    public double at(int level) {
        int lv = Math.max(0, Math.min(level, maxLevel));
        return perLevel * lv;
    }

    /** 这条附魔在某一级的可读数值（Lore 用） */
    public String valueText(int level) {
        double v = at(level);
        return switch (kind) {
            case FIRE_ADD -> trim(v) + "秒";
            case ATTACK_SPEED, DAMAGE_MUL, ARMOR_PIERCE, VS_MONSTER, EXECUTE, COMBO,
                 DAMAGE_REDUCE, THORNS, FIRE_RESIST, EXP_BONUS, DROP_BONUS, COIN_BONUS ->
                    "+" + trim(v * 100) + "%";
            case LIFESTEAL_ADD -> "+" + trim(v * 100) + "%";
            case HEALTH_ADD -> "+" + trim(v);
        };
    }

    private static String trim(double v) {
        if (Math.abs(v - Math.round(v)) < 1e-9) {
            return String.valueOf(Math.round(v));
        }
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}