package com.forgemaster.tools;

import com.forgemaster.combat.Crit;
import com.forgemaster.combat.DamageBudget;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 离线数值实测器（v2.23.1）。
 *
 * <h2>它解决什么问题</h2>
 * 这个工作区里没有 Paper 服务端 jar，{@code /zhujian selftest} 跑不起来。
 * 但「一刀 130 / 四刀一只坚守者 / 一刀吸半管」这类问题全都是<b>纯数值</b>问题，
 * 不需要真的进服就能测——前提是<b>模拟器与线上调同一份代码</b>。
 *
 * <p>所以这个类只依赖 {@link DamageBudget} 与 {@link Crit} 两个纯函数类（零 Bukkit），
 * 由 {@code tools/botrun.ps1} 在 {@code mvn clean package} 之后用
 * {@code java -cp target/classes} 拉起。跑出来的数字就是线上生效的数字。</p>
 *
 * <h2>「bot 全都跑一遍」是怎么跑的</h2>
 * 每个 bot 是一个「配装档位」（新手 / 中档 / 满配 / 极端堆料），各自跑完整条链路：
 * <pre>
 *   品质 × 材质 → 强化等级 → 蜕变阶数 → 词条加值 → 暴击 → 单次伤害上限 → 吸血
 * </pre>
 * 然后对每个 bot 结算「打坚守者几刀」「一刀回多少血」「期望秒伤」。最后跑一组
 * <b>断言</b>（超标即失败，退出码非 0），这样脚本能像 CI 一样被反复执行。
 *
 * <p>本类<b>不产生任何副作用</b>：不写文件、不连网络、不改配置。</p>
 */
public final class NumericSim {

    /** 原版参照物：下界合金剑 + 锋利 V ≈ 11 点一击（8 + 3），跳劈 ×1.5 */
    private static final double VANILLA_HIT = 11.0;
    /** 坚守者：500 血 */
    private static final double WARDEN_HP = 500.0;
    /** 玩家最大生命 */
    private static final double MAX_HP = 20.0;
    /** 玩家本体基础伤害（空手 1.0） */
    private static final double PLAYER_BASE = 1.0;

    private NumericSim() {
    }

    /** 一个「配装档位」= 一个 bot */
    private record Bot(String name, double attackAttr, double critChance, double critDamage,
                       double lifesteal, String note) {
    }

    public static void main(String[] args) {
        DamageBudget.Limits budget = DamageBudget.Limits.defaults();
        Crit.Limits critLimits = Crit.Limits.defaults();

        List<Bot> bots = new ArrayList<>();
        // 攻击属性 = 材质基础 × 品质倍率 × 强化 × 蜕变（数值取自 config.yml 的实际配置）
        // 5.25 是星陨 bonus-attack，5.0 是神话品质 power()，1.594 是 +99 级，1.752 是六阶蜕变连乘
        bots.add(new Bot("新手·铁剑+5", 1.4 * 1.0 * (1 + 5 * 0.006), 0.10, 0.30, 0.05,
                "铁胚、普通品质、随便锤了 5 下"));
        bots.add(new Bot("中档·钻石+25", 3.15 * 3.0 * 1.15, 0.35, 0.80, 0.25,
                "钻石胚 + 史诗品质 + 三阶蜕变 + 两条吸血词条"));
        bots.add(new Bot("满配·星陨+99·六阶", 5.25 * 5.0 * 1.594 * 1.752, 0.75, 2.50, 1.155,
                "服主昨天那一套：星陨 + 神话 + 满强化 + 满蜕变 + 满词条（吸血 115%）"));
        bots.add(new Bot("极端·理论堆满", 5.25 * 5.0 * 1.594 * 1.752 * 1.30, 1.79, 7.30, 2.0,
                "把暴击率/暴伤/吸血全按「理论上限」堆，用来验证夹子是否兜得住"));

        System.out.println("=".repeat(112));
        System.out.println("  铸剑大师 数值实测 · 离线模拟器（与线上同一套 DamageBudget / Crit）");
        System.out.println("=".repeat(112));
        System.out.printf(Locale.ROOT,
                "  %-22s %8s %8s %7s %7s %8s %7s %8s %7s%n",
                "bot", "属性", "普通伤害", "暴击", "暴伤倍", "暴击伤害", "上限截断", "吸血/刀", "砍守者");
        System.out.println("-".repeat(112));

        double worstHits = Double.MAX_VALUE;
        double worstHealPct = 0;
        // 满配的「每一击实际伤害」，用来算「再加满附魔之后还超不超上限」
        double worstHit = 0;
        for (Bot b : bots) {
            DamageBudget.Result r = DamageBudget.simulateHit(
                    b.attackAttr(), PLAYER_BASE, b.critChance(), b.critDamage(),
                    b.lifesteal(), MAX_HP, critLimits, budget);
            double hitsCrit = WARDEN_HP / r.finalHit();
            double healPct = r.healCrit() / MAX_HP * 100.0;
            if (b.name().startsWith("满配") || b.name().startsWith("极端")) {
                worstHits = Math.min(worstHits, hitsCrit);
                worstHealPct = Math.max(worstHealPct, healPct);
                worstHit = Math.max(worstHit, r.finalHit());
            }
            System.out.printf(Locale.ROOT,
                    "  %-22s %8.1f %8.1f %7.0f%% %7.2f %8.1f %7s %8.1f %7.1f%n",
                    b.name(), r.attribute(), r.raw(), r.chance() * 100, r.multiplier(),
                    r.finalHit(), r.capped() ? "是" : "否", r.healCrit(), hitsCrit);
            System.out.println("       └─ " + b.note());
        }

        System.out.println("-".repeat(112));
        System.out.printf(Locale.ROOT, "  原版参照：下界合金剑+锋利V ≈ %.0f 伤害/击，坚守者 %.0f 血 → 约 %.0f 刀%n",
                VANILLA_HIT, WARDEN_HP, WARDEN_HP / VANILLA_HIT);
        System.out.println();

        int fail = 0;
        fail += check("满配单次伤害不超过原版的 5.5 倍", budget.maxHitDamage() <= VANILLA_HIT * 5.5,
                "上限 " + budget.maxHitDamage() + " vs 原版 " + VANILLA_HIT);
        fail += check("满配打死坚守者至少 8 刀（原来 4 刀）", worstHits >= 8.0,
                "实测 " + fmt(worstHits) + " 刀");
        fail += check("满配一刀吸血不超过一条血的 15%", worstHealPct <= 15.0,
                "实测 " + fmt(worstHealPct) + "% / 刀");
        fail += check("暴击倍率上限不超过 3.0 倍", critLimits.baseMultiplier() + critLimits.maxDamage() <= 3.0,
                "实测 " + fmt(critLimits.baseMultiplier() + critLimits.maxDamage()) + " 倍");
        fail += check("暴击率上限不超过 60%", critLimits.maxChance() <= 0.60,
                "实测 " + fmt(critLimits.maxChance() * 100) + "%");
        fail += check("攻击属性上限不超过原版武器的 3 倍", budget.maxAttackAttribute() <= VANILLA_HIT * 3,
                "实测 " + fmt(budget.maxAttackAttribute()));

        // 幂等 / 单调性：夹子不能出现「越夹越大」或 NaN 传染
        double a = DamageBudget.clampHit(1000, budget);
        double b2 = DamageBudget.clampHit(a, budget);
        fail += check("伤害夹子幂等", Math.abs(a - b2) < 1e-9, fmt(a) + " → " + fmt(b2));
        fail += check("NaN 不传染", DamageBudget.clampHit(Double.NaN, budget) == 0.0
                        && DamageBudget.lifestealHeal(Double.NaN, 1, MAX_HP, budget) == 0.0,
                "NaN 已归零");
        fail += check("吸血对 0 伤害返回 0", DamageBudget.lifestealHeal(0, 1.0, MAX_HP, budget) == 0.0, "ok");
        fail += check("无限预算时不夹", DamageBudget.clampHit(9999, DamageBudget.Limits.unlimited()) == 9999,
                "unlimited 直通");

        // ---------------------------------------------------------------- 原创附魔（v2.23.2）
        //
        // 这一组的命题只有一个：把 15 条原创附魔全部堆到满级、还叠上最有利的条件
        // （打敌对生物 + 目标残血 + 连击满层），最终伤害**仍然**被 combat.limits 管住。
        //
        // 附魔自己有一道 +25% 的封顶；就算它全给满，满配 59.8 变成 74.75，
        // 再进 DamageBudget.clampHit 还是要落回 60。这就是「两道夹子」的意义。
        System.out.println();
        System.out.println("原创附魔（全满级最坏情况）");
        System.out.println("-".repeat(112));
        var enchCaps = com.forgemaster.enchant.EnchantEffects.Caps.defaults();
        var fx = new com.forgemaster.enchant.EnchantEffects();
        java.util.List<com.forgemaster.enchant.EnchantEffects.Entry> maxed = maxedEnchants();

        double enchMul = fx.hitMultiplier(maxed, enchCaps, true, 0.01, enchCaps.comboStacks());
        var enchWeapon = fx.weapon(maxed, enchCaps);
        var enchArmor = fx.armor(maxed, enchCaps);
        System.out.printf(Locale.ROOT,
                "  伤害倍率 %.3f（封顶 %.2f）｜吸血 +%.1f%%｜点燃 +%.1fs｜攻速 +%.1f%%%n",
                enchMul, 1.0 + enchCaps.maxDamageBonus(), enchWeapon.lifestealAdd() * 100,
                enchWeapon.fireAddSeconds(), enchWeapon.attackSpeedAdd() * 100);
        System.out.printf(Locale.ROOT,
                "  减伤 +%.1f%%｜气血 +%.1f｜荆棘 %.1f%%（单次≤%.0f）｜御火 %.1f%%%n",
                enchArmor.reduceAdd() * 100, enchArmor.healthAdd(),
                enchArmor.thornsPct() * 100, enchArmor.thornsFlat(), enchArmor.fireResist() * 100);
        System.out.printf(Locale.ROOT, "  经验 ×%.2f｜剑币 ×%.2f｜挖矿额外掉落 %.1f%%%n",
                fx.expMul(maxed, enchCaps), fx.coinMul(maxed, enchCaps),
                fx.dropChance(maxed, enchCaps) * 100);

        // 满配 + 全满附魔，再过伤害夹子
        double withEnch = worstHit * enchMul;
        double afterBudget = DamageBudget.clampHit(withEnch, budget);
        double hitsWithEnch = Math.ceil(WARDEN_HP / afterBudget);
        System.out.printf(Locale.ROOT,
                "  满配 %.1f × 附魔 %.3f = %.1f → 夹子后 %.1f → 坚守者 %.1f 刀%n",
                worstHit, enchMul, withEnch, afterBudget, hitsWithEnch);
        System.out.println("-".repeat(112));
        System.out.println();

        fail += check("附魔伤害总加成不超过 +25%", enchMul <= 1.0 + enchCaps.maxDamageBonus() + 1e-9,
                fmt(enchMul) + " 倍");
        fail += check("附魔吸血加成不超过 +10%", enchWeapon.lifestealAdd() <= enchCaps.maxLifestealAdd() + 1e-9,
                fmt(enchWeapon.lifestealAdd() * 100) + "%");
        fail += check("附魔点燃加成不超过 +4 秒", enchWeapon.fireAddSeconds() <= enchCaps.maxFireSeconds() + 1e-9,
                fmt(enchWeapon.fireAddSeconds()) + " 秒");
        fail += check("附魔减伤加成不超过 +10%", enchArmor.reduceAdd() <= enchCaps.maxReduce() + 1e-9,
                fmt(enchArmor.reduceAdd() * 100) + "%");
        fail += check("附魔气血加成不超过 +8", enchArmor.healthAdd() <= enchCaps.maxHealth() + 1e-9,
                fmt(enchArmor.healthAdd()));
        fail += check("满配 + 满附魔仍在伤害上限内", afterBudget <= budget.maxHitDamage() + 1e-9,
                fmt(afterBudget) + " ≤ " + fmt(budget.maxHitDamage()));
        fail += check("满配 + 满附魔仍要 8 刀以上打死坚守者", hitsWithEnch >= 8.0,
                fmt(hitsWithEnch) + " 刀");
        fail += check("附魔加成是加法（8 条相加不再乘一次）",
                Math.abs(fx.hitMultiplier(twoEnchants(), enchCaps, false, 1.0, 0)
                        - (1.0 + EDGE_PER + PIERCE_PER)) < 1e-9,
                "两条 1 级 = +" + fmt((EDGE_PER + PIERCE_PER) * 100) + "%");
        fail += check("四件护甲合并后减伤只夹一次",
                fx.mergeArmor(java.util.List.of(enchArmor, enchArmor, enchArmor, enchArmor), enchCaps)
                        .reduceAdd() <= enchCaps.maxReduce() + 1e-9,
                "四件合计 ≤ " + fmt(enchCaps.maxReduce() * 100) + "%");

        System.out.println();
        if (fail == 0) {
            System.out.println("  结论：全部通过（" + (10 + 9) + " 项断言，含原创附魔 9 项）");
        } else {
            System.out.println("  结论：有 " + fail + " 项超标，需要调数值");
        }
        System.exit(fail == 0 ? 0 : 1);
    }

    private static int check(String name, boolean ok, String detail) {
        System.out.println("  [" + (ok ? "PASS" : "FAIL") + "] " + name + " —— " + detail);
        return ok ? 0 : 1;
    }

    // ---------------------------------------------------------------- 原创附魔造数据

    /** 锋锐 / 破甲 的每级值，用来验证「加法而非乘法」（与 EnchantRegistry.defaults() 一致） */
    private static final double EDGE_PER = 0.015;
    private static final double PIERCE_PER = 0.012;

    /**
     * 把 15 条原创附魔全部拉到满级。
     *
     * <p>这里**故意不从 config 读**：这条断言要独立于服主的配置存在。
     * 如果哪天服主把某条 per-level 调到离谱，这里立刻会红，从而在开服前就发现。</p>
     */
    private static java.util.List<com.forgemaster.enchant.EnchantEffects.Entry> maxedEnchants() {
        var out = new java.util.ArrayList<com.forgemaster.enchant.EnchantEffects.Entry>();
        for (var def : com.forgemaster.enchant.EnchantRegistry.defaults()) {
            out.add(new com.forgemaster.enchant.EnchantEffects.Entry(def, def.maxLevel()));
        }
        return out;
    }

    private static java.util.List<com.forgemaster.enchant.EnchantEffects.Entry> twoEnchants() {
        var edge = com.forgemaster.enchant.EnchantRegistry.defaults().stream()
                .filter(d -> d.id().equals("edge")).findFirst().orElseThrow();
        var pierce = com.forgemaster.enchant.EnchantRegistry.defaults().stream()
                .filter(d -> d.id().equals("pierce")).findFirst().orElseThrow();
        return java.util.List.of(
                new com.forgemaster.enchant.EnchantEffects.Entry(edge, 1),
                new com.forgemaster.enchant.EnchantEffects.Entry(pierce, 1));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}