package com.forgemaster.combat;

import com.forgemaster.ForgingMasterPlugin;

/**
 * 暴击的结算数学：把所有 PDC 汇总出来的原始值，压成「真正生效的暴击率与倍率」。
 *
 * <p>为什么要有这一层，而不是在 {@code CombatListener} 里直接乘：</p>
 *
 * <p>v2.21 之前战斗侧是这么写的——</p>
 * <pre>
 * if (critChance &gt; 0 &amp;&amp; random &lt; critChance) {
 *     event.setDamage(event.getDamage() * (1.0 + critDamage));
 * }
 * </pre>
 *
 * <p>这段有两个洞，都不是笔误而是缺了一层：</p>
 *
 * <ol>
 *   <li><b>没有上限。</b>暴击率是词条 + 宝石 + 剑灵三方叠加的 PDC 数，
 *       满配能叠到 179%。{@code random < 1.79} 恒真 —— 等于「永久暴击」，
 *       而且玩家看不到任何提示。暴伤同理会到 +630%（×7.3）。</li>
 *   <li><b>倍率底数偏低，出现倒挂。</b>原版跳跃暴击本身就是 ×1.5，
 *       而自定义暴击是 ×(1 + 暴伤)。暴伤取最低档 +25% 时只有 ×1.25 ——
 *       比随便跳一下砍出去还弱。花了词条位换来的属性不如原版默认行为，这就是倒挂。</li>
 * </ol>
 *
 * <p>所以这里把「压上限」和「抬底数」都收进一个纯函数：{@code CombatListener}
 * 只负责掷骰与放特效，数值口径全在这里，自检可以直接断言。</p>
 */
public final class Crit {

    private Crit() {
    }

    /**
     * 结算后的暴击参数。
     *
     * @param chance     真正参与掷骰的暴击率，已夹在 {@code [0, maxChance]}
     * @param multiplier 暴击时的伤害倍率，已夹在 {@code [base, base + maxDamage]}
     * @param capped     原始值是否被上限截断（用来在日志/调参时发现「有人在堆无用属性」）
     */
    public record Profile(double chance, double multiplier, boolean capped) {

        /** 这一击是否暴击。概率口径与 {@link #chance()} 完全一致 */
        public boolean roll(java.util.Random random) {
            return chance > 0 && random.nextDouble() < chance;
        }
    }

    /**
     * 暴击口径（v2.23.1 抽取）：把「三个魔法数」收成一条可传递的记录。
     *
     * <p>抽出来的原因很实际：{@link DamageBudget} 要能在一处算出「这一击暴击后多少」，
     * 而 {@code CombatListener} 要能在一处算出「这一击到底暴不暴」——两处必须共用同一份
     * 口径，否则模拟器与线上会算出差一截的数。</p>
     *
     * @param maxChance      暴击率上限，&lt;= 0 表示不设上限
     * @param maxDamage      暴伤上限（在原版底数之上再加多少），&lt;= 0 表示不设上限
     * @param baseMultiplier 暴击倍率底数（原版 1.5）
     */
    public record Limits(double maxChance, double maxDamage, double baseMultiplier) {

        /**
         * 默认口径：暴击率 50%、暴伤 +110%（→ ×2.6）。
         *
         * <p><b>v2.23.1 下调</b>：原来是 75% / +250%（→ ×4.0）。满配玩家能稳定吃满 4 倍，
         * 叠上 26 点基础攻击就是一刀 100+，而服主实测 130 —— 四刀一只坚守者（500 血）。
         * 50% / +110% 意味着「大约每两刀暴一次、暴击约 2.6 倍」，
         * 期望收益 ≈ ×1.8，仍明显强于原版跳劈（×1.5），但不再是乘法放大器。</p>
         */
        public static Limits defaults() {
            return new Limits(0.50, 1.10, 1.5);
        }
    }

    /** 从插件配置读口径；配置缺失时用 {@link Limits#defaults()} */
    public static Limits limitsOf(ForgingMasterPlugin plugin) {
        if (plugin == null) {
            return Limits.defaults();
        }
        Limits d = Limits.defaults();
        return new Limits(
                plugin.getConfig().getDouble("combat.crit.max-chance", d.maxChance()),
                plugin.getConfig().getDouble("combat.crit.max-damage", d.maxDamage()),
                plugin.getConfig().getDouble("combat.crit.base-multiplier", d.baseMultiplier()));
    }

    /**
     * 按默认口径结算。
     *
     * <p><b>v2.23.1：这里改成了转调 {@link Limits#defaults()}</b>，不再写死 0.75 / 2.5。
     * 之前两处各写一份「默认值」（本方法与 {@link #fromConfig}），改数值时漏一处就会出现
     * 「线上按 1.10 算、离线自检按 2.5 算」这种最难查的分歧。</p>
     */
    public static Profile resolve(double rawChance, double rawDamage) {
        Limits l = Limits.defaults();
        return resolve(rawChance, rawDamage, l.maxChance(), l.maxDamage(), l.baseMultiplier());
    }

    /**
     * 结算。
     *
     * <p>倍率取 {@code base + 暴伤} 而不是 {@code 1 + 暴伤}：暴伤是「在原版暴击倍率之上
     * 再加多少」，所以一条 +90% 的致命词条给到 ×2.4（1.5 + 0.9），而不是 ×1.9。
     * 这样最低档的暴伤词条也不会弱于原版跳劈。</p>
     *
     * @param rawChance       PDC 汇总出来的原始暴击率（可能 &gt; 1）
     * @param rawDamage       PDC 汇总出来的原始暴伤（可能 &gt; 10）
     * @param maxChance       暴击率上限，&lt;= 0 表示不设上限
     * @param maxDamage       暴伤上限，&lt;= 0 表示不设上限
     * @param baseMultiplier  暴击倍率底数（原版为 1.5）；&lt; 1 时按 1 处理
     */
    public static Profile resolve(double rawChance, double rawDamage,
                                  double maxChance, double maxDamage, double baseMultiplier) {
        // NaN 直接当没有：配置写坏时不能让它传染到伤害计算
        if (Double.isNaN(rawChance)) {
            rawChance = 0;
        }
        if (Double.isNaN(rawDamage)) {
            rawDamage = 0;
        }
        double chance = Math.max(0.0, rawChance);
        double damage = Math.max(0.0, rawDamage);
        boolean capped = false;
        if (maxChance > 0 && chance > maxChance) {
            chance = maxChance;
            capped = true;
        }
        // 100% 是绝对上限，配置给再大也不允许「必定暴击」变成可配置项
        if (chance > 1.0) {
            chance = 1.0;
            capped = true;
        }
        if (maxDamage > 0 && damage > maxDamage) {
            damage = maxDamage;
            capped = true;
        }
        double base = Math.max(1.0, baseMultiplier);
        return new Profile(chance, base + damage, capped);
    }

    /** 从插件配置读口径；配置缺失时用 {@link Limits#defaults()} */
    public static Profile fromConfig(ForgingMasterPlugin plugin, double rawChance, double rawDamage) {
        if (plugin == null) {
            Limits d = Limits.defaults();
            return resolve(rawChance, rawDamage, d.maxChance(), d.maxDamage(), d.baseMultiplier());
        }
        Limits l = limitsOf(plugin);
        return resolve(rawChance, rawDamage, l.maxChance(), l.maxDamage(), l.baseMultiplier());
    }
}