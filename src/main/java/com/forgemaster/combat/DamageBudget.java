package com.forgemaster.combat;

/**
 * 伤害预算：把「一击到底能有多疼、一击到底能吸多少血」收进一个纯函数层。
 *
 * <h2>为什么要有这一层（v2.23.1 修 P0）</h2>
 *
 * <p>生存服服主的实测反馈是：<b>满配暴击一刀 130，四刀一只坚守者，一刀吸血接近半管</b>。
 * 把这条链子拆开就能看到问题不在某一处配置，而在**四层乘区各自都没有绝对上限**：</p>
 *
 * <pre>
 *   基础攻击  5.25（星陨）× 5.0（神话品质）= 26.25   ← 已经是原版下界合金剑(8)的 3.3 倍
 *   × 强化    (1 + 99×0.006) = 1.594                ← 线性，还算老实
 *   × 蜕变    1.06×1.07×1.09×1.10×1.12×1.15 = 1.752 ← 复利
 *   × 词条    +5 条锋锐，星陨档 1.65 倍 → 最多 +21.4 ← 加法，但基数已经被上面放大了
 *   × 暴击    1.5 + 2.5 = 4.0                      ← {@code combat.crit} 已有上限，但上限本身过高
 *   × 吸血    最多 0.14×1.65×5 = 1.155 → 115%      ← **完全没有上限**
 * </pre>
 *
 * <p>{@link Crit} 已经把暴击率压进 [0, 75%]、暴伤压进 [0, +250%]，方向是对的，但
 * 「+250%」意味着 ×4.0 —— 一个满配玩家能稳定吃满这个乘区，于是 26 → 105 再暴击 420。
 * 吸血更严重：它是**按伤害比例回血**，伤害越高回得越多，两个失控叠在一起，
 * 一刀回满一管血，任何 PVE 内容都失去意义。</p>
 *
 * <h2>设计原则</h2>
 * <ol>
 *   <li><b>只设天花板，不做曲线</b>：单层软上限（例如「超过 20 的部分打 5 折」）算起来难解释，
 *       玩家也看不懂自己为什么被削。硬上限至少是「一到就停」，能写进 Lore 说清楚。</li>
 *   <li><b>上限全部进配置</b>：{@code combat.limits.*}，服主自己决定服务器要多硬。</li>
 *   <li><b>默认值对齐原版手感</b>：下界合金剑 + 锋利 V ≈ 11–12 点一击，满配锻造剑压在
 *       <b>约 2 倍原版</b>（22 攻击属性 / 暴击后 ~57）—— 强，但不是「四刀打死坚守者」。
 *       坚守者 500 血：满配约 9 刀，中档约 20 刀，这才像在打 BOSS。</li>
 * </ol>
 *
 * <p>本类<b>不依赖 Bukkit</b>：{@code CombatListener} 与离线模拟器（{@code tools/}）调的是
 * 同一份代码，所以模拟器跑出来的数就是线上生效的数 —— 这是「实测」而不是「估算」的前提。</p>
 */
public final class DamageBudget {

    private DamageBudget() {
    }

    /**
     * 一套预算口径。
     *
     * @param maxAttackAttribute 单个物品攻击属性（fm_atk）的绝对值上限；&lt;= 0 表示不限
     * @param maxHitDamage       单次命中的最终伤害上限（已含暴击，未计护甲）；&lt;= 0 表示不限
     * @param maxLifestealPct    吸血比例上限（相对本次伤害）；&lt;= 0 表示不限
     * @param maxLifestealHeal   单次吸血回血的绝对值上限（半颗心 = 1.0）；&lt;= 0 表示不限
     * @param maxLifestealHpPct  单次吸血最多回「最大生命值的百分之几」；&lt;= 0 表示不限
     */
    public record Limits(double maxAttackAttribute, double maxHitDamage,
                         double maxLifestealPct, double maxLifestealHeal,
                         double maxLifestealHpPct) {

        /** 默认口径：对齐原版手感（见类注释的设计原则 3） */
        public static Limits defaults() {
            return new Limits(22.0, 60.0, 0.20, 2.0, 0.10);
        }

        /**
         * 从配置读全套上限。**所有调用点都必须走这里**（不要自己写 {@code getDouble(..., 默认值)}）。
         *
         * <p>v2.23.1 的教训：{@code Crit} 曾经在两个方法里各写一份默认值
         * （两参数 {@code resolve} 与 {@code fromConfig}），改数值时漏掉一处，
         * 就变成「线上按 1.10 算、自检按 2.5 算」这种最难查的分歧。
         * 数值上限的默认值只允许存在这一个地方。</p>
         */
        public static Limits fromConfig(org.bukkit.configuration.ConfigurationSection cfg) {
            if (cfg == null) {
                return defaults();
            }
            Limits d = defaults();
            return new Limits(
                    cfg.getDouble("combat.limits.max-attack-attribute", d.maxAttackAttribute()),
                    cfg.getDouble("combat.limits.max-hit-damage", d.maxHitDamage()),
                    cfg.getDouble("combat.limits.max-lifesteal", d.maxLifestealPct()),
                    cfg.getDouble("combat.limits.max-lifesteal-per-hit", d.maxLifestealHeal()),
                    cfg.getDouble("combat.limits.max-lifesteal-max-health", d.maxLifestealHpPct()));
        }

        /** 全不限（服主自己管数值时用） */
        public static Limits unlimited() {
            return new Limits(0, 0, 0, 0, 0);
        }
    }

    /**
     * 写入侧（物品产出）的上限集合：吸血 / 减伤 / 气血 / 点燃。
     *
     * <p>为什么和 {@link Limits} 分开：{@link Limits} 是「一次命中怎么结算」的口径，
     * 这个是「一件物品能带多少属性」的口径，读者与调用点都不同。
     * 但两者同样只允许在这里拥有一份默认值 —— 以前 {@code ItemFactory}、
     * {@code LegacyItemRepair}、{@code CombatListener} 各写了一遍 {@code 0.20} /
     * {@code 0.35} / {@code 20.0} / {@code 10.0}，改一处漏两处就是下一个 bug。</p>
     */
    public record Caps(double maxLifesteal, double maxDamageReduce,
                       double maxBonusHealth, double maxFireSeconds,
                       // v2.24 切片一：冰噬减速时长（tick）/ 处决增伤比例
                       int maxChillTicks, double maxExecute) {

        public static Caps defaults() {
            return new Caps(0.20, 0.35, 20.0, 10.0, 40, 0.30);
        }

        public static Caps fromConfig(org.bukkit.configuration.ConfigurationSection cfg) {
            if (cfg == null) {
                return defaults();
            }
            Caps d = defaults();
            return new Caps(
                    cfg.getDouble("combat.limits.max-lifesteal", d.maxLifesteal()),
                    cfg.getDouble("combat.limits.max-damage-reduce", d.maxDamageReduce()),
                    cfg.getDouble("combat.limits.max-bonus-health", d.maxBonusHealth()),
                    cfg.getDouble("combat.limits.max-fire-seconds", d.maxFireSeconds()),
                    // v2.24 切片一：这两个键在 combat.caps 下（与 combat.limits 的旧键分开，
                    // 是因为「冰噬/处决」是词条产出的新夹子，语义上属于词条口径而不是伤害预算）
                    Math.max(0, cfg.getInt("combat.caps.max-chill-ticks", d.maxChillTicks())),
                    cfg.getDouble("combat.caps.max-execute", d.maxExecute()));
        }

        /** 夹一个吸血值（&lt;=0 的上限表示不限） */
        public double clampLifesteal(double raw) {
            return maxLifesteal <= 0 ? Math.max(0, raw) : Math.max(0, Math.min(raw, maxLifesteal));
        }

        /** 夹一个减伤值 */
        public double clampReduce(double raw) {
            return maxDamageReduce <= 0 ? Math.max(0, raw) : Math.max(0, Math.min(raw, maxDamageReduce));
        }

        /** 夹一个气血加成 */
        public double clampHealth(double raw) {
            return maxBonusHealth <= 0 ? Math.max(0, raw) : Math.max(0, Math.min(raw, maxBonusHealth));
        }

    /** 夹一个点燃 tick 数（输入输出都是 tick） */
    public int clampFireTicks(int rawTicks) {
        if (maxFireSeconds <= 0) {
            return Math.max(0, rawTicks);
        }
        int cap = (int) Math.round(maxFireSeconds * 20);
        return Math.max(0, Math.min(rawTicks, cap));
    }

    /**
     * 夹一个冰噬减速 tick 数（v2.24 切片一，输入输出都是 tick）。
     *
     * <p>与 {@link #clampFireTicks(int)} 同模式：写入侧（ItemFactory）与结算侧
     * （CombatListener）都用这一份上限，存量旧物品在结算处再夹一道。
     * 上限 ≤ 0 视为「不夹」，与点燃的语义一致。</p>
     */
    public int clampChillTicks(int rawTicks) {
        if (maxChillTicks <= 0) {
            return Math.max(0, rawTicks);
        }
        return Math.max(0, Math.min(rawTicks, maxChillTicks));
    }

    /** 夹一个处决增伤比例（v2.24 切片一）：词条叠加 + 星陨档缩放之后也不能越过这条线 */
    public double clampExecute(double raw) {
        if (maxExecute <= 0) {
            return Math.max(0, raw);
        }
        return Math.max(0, Math.min(raw, maxExecute));
    }
    }

    // ------------------------------------------------------------ 攻击属性

    /**
     * 把物品的攻击属性夹进预算。
     *
     * <p>调用点在 {@code StrengthenService.apply()} 写入 fm_atk 之前 ——
     * 也就是说上限是在**产出**处生效的，物品本身存的就已经是合规值，
     * 玩家在 Lore / 属性面板里看到的就是真实生效的数字，不存在「面板虚高、实战被砍」。</p>
     */
    public static double clampAttack(double attribute, Limits limits) {
        if (Double.isNaN(attribute) || attribute <= 0) {
            return 0.0;
        }
        if (limits == null || limits.maxAttackAttribute() <= 0) {
            return attribute;
        }
        return Math.min(attribute, limits.maxAttackAttribute());
    }

    // ------------------------------------------------------------ 单次伤害

    /**
     * 夹一次命中的最终伤害（已经过暴击乘区）。
     *
     * <p>为什么放在暴击之后：放在之前的话，暴击照样能把数字顶穿，等于没管。
     * 代价是「极高配玩家的暴击视觉上被截断」——这正是我们要的效果：
     * 上限到了就到顶，不是越堆越多。</p>
     */
    public static double clampHit(double damage, Limits limits) {
        if (Double.isNaN(damage) || damage <= 0) {
            return 0.0;
        }
        if (limits == null || limits.maxHitDamage() <= 0) {
            return damage;
        }
        return Math.min(damage, limits.maxHitDamage());
    }

    // ------------------------------------------------------------ 吸血

    /**
     * 结算一次吸血到底回多少血。
     *
     * <p>三道夹子依次生效，缺一不可：</p>
     * <ol>
     *   <li><b>比例夹</b>：吸血词条本身最多 20%（原始值可能到 115%）。</li>
     *   <li><b>绝对夹</b>：单次最多回 2 点（一颗心）。</li>
     *   <li><b>血条夹</b>：单次最多回最大生命的 10%（20 血 → 2 点；服主把血条拉到 100 也不会失控）。</li>
     * </ol>
     *
     * <p>三道里取最小的那个。这样「一刀回半管」（10 点）在默认配置下变成「一刀回一颗心」，
     * 吸血仍然是可感知的续航，但打不死人了。</p>
     *
     * @param dealt     本次实际造成的伤害（已含暴击、已过 {@link #clampHit}）
     * @param lifesteal 物品上的吸血原始比例（可能 &gt; 1）
     * @param maxHealth 玩家最大生命值
     */
    public static double lifestealHeal(double dealt, double lifesteal, double maxHealth, Limits limits) {
        if (Double.isNaN(dealt) || Double.isNaN(lifesteal) || dealt <= 0 || lifesteal <= 0) {
            return 0.0;
        }
        // ① 比例夹：先修原始值，避免 115% 直接乘出一个离谱的数再被别处截
        double pct = lifesteal;
        if (limits != null && limits.maxLifestealPct() > 0) {
            pct = Math.min(pct, limits.maxLifestealPct());
        }
        double heal = dealt * pct;
        // ② 绝对夹
        if (limits != null && limits.maxLifestealHeal() > 0) {
            heal = Math.min(heal, limits.maxLifestealHeal());
        }
        // ③ 血条夹
        if (limits != null && limits.maxLifestealHpPct() > 0 && maxHealth > 0) {
            heal = Math.min(heal, maxHealth * limits.maxLifestealHpPct());
        }
        return Math.max(0.0, heal);
    }

    // ------------------------------------------------------------ 完整一击（给模拟器用）

    /**
     * 一次命中的完整结算：属性 → 基础伤害 → 暴击 → 伤害上限 → 吸血回血。
     *
     * <p>模拟器（{@code tools/botrun.ps1} → {@code NumericSim}）直接调这个，
     * 所以「离线跑出来的数」与线上链路是同一套算子。</p>
     *
     * @param attackAttribute 物品攻击属性（已被 {@link #clampAttack} 过或会被这里夹）
     * @param playerBase      玩家本体基础伤害（原版 1.0）
     * @param critChance      原始暴击率
     * @param critDamage      原始暴伤
     * @param lifesteal       原始吸血比例
     * @param maxHealth       玩家最大生命
     * @param critLimits      暴击口径（复用 {@link Crit} 的上限）
     * @param budget          伤害预算
     */
    public static Result simulateHit(double attackAttribute, double playerBase,
                                     double critChance, double critDamage, double lifesteal,
                                     double maxHealth,
                                     Crit.Limits critLimits, Limits budget) {
        double attr = clampAttack(attackAttribute, budget);
        double raw = Math.max(0.0, playerBase + attr);
        Crit.Profile crit = Crit.resolve(critChance, critDamage,
                critLimits.maxChance(), critLimits.maxDamage(), critLimits.baseMultiplier());
        double critHit = raw * crit.multiplier();
        double finalHit = clampHit(critHit, budget);
        double healOnCrit = lifestealHeal(finalHit, lifesteal, maxHealth, budget);
        double healNoCrit = lifestealHeal(clampHit(raw, budget), lifesteal, maxHealth, budget);
        return new Result(attr, raw, critHit, finalHit, healOnCrit, healNoCrit,
                crit.multiplier(), crit.chance(), finalHit < critHit - 1e-9);
    }

    /**
     * 一次命中的结算结果。
     *
     * @param attribute  夹过的攻击属性
     * @param raw        未暴击基础伤害（玩家本体 + 属性）
     * @param critHit    暴击后、封顶前的伤害
     * @param finalHit   最终生效伤害（可能被 {@code max-hit-damage} 截断）
     * @param healCrit   暴击时的吸血回血
     * @param healNormal 未暴击时的吸血回血
     * @param multiplier 生效暴击倍率
     * @param chance     生效暴击率
     * @param capped     是否被单次伤害上限截断
     */
    public record Result(double attribute, double raw, double critHit, double finalHit,
                         double healCrit, double healNormal,
                         double multiplier, double chance, boolean capped) {
    }
}