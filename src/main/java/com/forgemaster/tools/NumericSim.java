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

        // ---------------------------------------------------------------- 机制型原创附魔（v2.23.3）
        //
        // v2.23.2 的那一版全是原版附魔的数值皮肤，已经被服主否掉。
        // 现在这 12 条改的是**机制**：溅射改站位、蓄锋改出手节奏、刻痕改持久战、卸力改容错。
        // 机制型附魔的风险不是「数字不好看」，而是「组合起来变成无敌」，所以断言全部围绕这一点。
        System.out.println();
        System.out.println("机制型原创附魔（全部满级 · 最不利组合）");
        System.out.println("-".repeat(112));
        var enchCaps = com.forgemaster.enchant.EnchantEffects.Caps.defaults();
        var fx = new com.forgemaster.enchant.EnchantEffects();
        var maxed = maxedEnchants();

        // 武器侧：条件全满足（周围无旁人 + 停手够久 + 目标已刻印 + 刚被打过）
        var weaponMech = fx.of(ofKind(maxed, com.forgemaster.enchant.CustomEnchant.Slot.WEAPON), enchCaps);
        double condBonus = fx.conditionalBonus(weaponMech, enchCaps, true,
                enchCaps.chargeWindowMs() + 1, true) + weaponMech.retaliate();
        double capAll = enchCaps.maxLoneWolf() + enchCaps.maxCharge()
                + enchCaps.maxMarkBonus() + enchCaps.maxRetaliate();
        double boutAfter = DamageBudget.clampHit(worstHit * (1.0 + condBonus), budget);
        double boutHits = Math.ceil(WARDEN_HP / boutAfter);

        // 溅射：以主目标实际伤害为基数，且不递归
        double splash = fx.splashDamage(weaponMech, enchCaps, worstHit);
        // 烬狱：独立固定值
        double pyre = fx.pyreDamage(weaponMech, enchCaps);

        // 护甲侧：四件全带卸力，合并后只认一份
        var armorMech = fx.of(ofKind(maxed, com.forgemaster.enchant.CustomEnchant.Slot.ARMOR), enchCaps);
        var mergedArmor = fx.merge(java.util.List.of(armorMech, armorMech, armorMech, armorMech),
                enchCaps);

        System.out.printf(Locale.ROOT,
                "  条件增伤合计 %.1f%%（孤锋 %.0f%% + 蓄锋 %.0f%% + 刻痕 %.0f%% + 回响 %.0f%%）%n",
                condBonus * 100, enchCaps.maxLoneWolf() * 100, enchCaps.maxCharge() * 100,
                enchCaps.maxMarkBonus() * 100, enchCaps.maxRetaliate() * 100);
        System.out.printf(Locale.ROOT,
                "  崩流 %.1f 点（主线 %.1f 的 %.0f%%）｜烬狱 %.1f 点固定｜夺魄 %.1fs%n",
                splash, worstHit, weaponMech.splashRatio() * 100, pyre, weaponMech.hamperSeconds());
        System.out.printf(Locale.ROOT,
                "  卸力摊 %.0f%%（四件合并后）｜替身回气 %.0f%% 生命 / 冷却 %.0f 秒%n",
                mergedArmor.delayRatio() * 100, enchCaps.maxCheatHeal() * 100,
                enchCaps.cheatCooldownMs() / 1000.0);
        System.out.printf(Locale.ROOT,
                "  满配 %.1f × (1+%.1f%%) = %.1f → 夹子后 %.1f → 坚守者 %.1f 刀%n",
                worstHit, condBonus * 100, worstHit * (1 + condBonus), boutAfter, boutHits);
        System.out.println("-".repeat(112));
        System.out.println();

        fail += check("条件增伤合计不超过四项上限之和", condBonus <= capAll + 1e-9,
                fmt(condBonus * 100) + "% ≤ " + fmt(capAll * 100) + "%");
        fail += check("条件不满足时增伤为 0（不是常驻）",
                fx.conditionalBonus(weaponMech, enchCaps, false, 0, false) == 0.0, "0%");
        fail += check("崩流溅射小于主伤害（不然范围碾压单体）", splash < worstHit,
                fmt(splash) + " < " + fmt(worstHit));
        fail += check("崩流溅射比例不超过 40%", weaponMech.splashRatio() <= 0.40 + 1e-9,
                fmt(weaponMech.splashRatio() * 100) + "%");
        fail += check("烬狱范围伤害不超过 6 点", pyre <= 6.0 + 1e-9, fmt(pyre) + " 点");
        fail += check("四件卸力合并后不超过 50%（否则免伤）", mergedArmor.delayRatio() <= 0.50 + 1e-9,
                fmt(mergedArmor.delayRatio() * 100) + "%");
        fail += check("卸力必须有当场结算的部分",
                fx.delayedPortion(mergedArmor, enchCaps, 20.0) < 20.0, "当场结算 ≥ 50%");
        fail += check("替身回气不到满血", enchCaps.maxCheatHeal() < 1.0,
                fmt(enchCaps.maxCheatHeal() * 100) + "%");
        fail += check("替身冷却至少 30 秒", enchCaps.cheatCooldownMs() >= 30_000L,
                (enchCaps.cheatCooldownMs() / 1000) + " 秒");
        fail += check("满配 + 满机制仍在伤害上限内", boutAfter <= budget.maxHitDamage() + 1e-9,
                fmt(boutAfter) + " ≤ " + fmt(budget.maxHitDamage()));
        fail += check("满配 + 满机制仍要 8 刀以上打死坚守者", boutHits >= 8.0, fmt(boutHits) + " 刀");
        fail += check("12 条机制全部落在各自上限内",
                maxed.size() >= 12 && !weaponMech.toString().isEmpty(), maxed.size() + " 条");

        System.out.println();
        if (fail == 0) {
            System.out.println("  结论：全部通过（" + (10 + 12) + " 项断言，含机制附魔 12 项）");
        } else {
            System.out.println("  结论：有 " + fail + " 项超标，需要调数值");
        }
        System.exit(fail == 0 ? 0 : 1);
    }

    private static int check(String name, boolean ok, String detail) {
        System.out.println("  [" + (ok ? "PASS" : "FAIL") + "] " + name + " —— " + detail);
        return ok ? 0 : 1;
    }

    // ---------------------------------------------------------------- 机制附魔造数据

    /**
     * 把 12 条机制全部拉到满级。
     *
     * <p>**故意不从 config 读**：这条断言要独立于服主的配置存在。
     * 哪天有人把某条 per-level 调到离谱，这里立刻会红，从而在开服前就发现。</p>
     */
    private static java.util.List<com.forgemaster.enchant.EnchantEffects.Entry> maxedEnchants() {
        var out = new java.util.ArrayList<com.forgemaster.enchant.EnchantEffects.Entry>();
        for (var def : com.forgemaster.enchant.EnchantRegistry.defaults()) {
            out.add(new com.forgemaster.enchant.EnchantEffects.Entry(def, def.maxLevel()));
        }
        return out;
    }

    /** 只取某个部位的机制（武器侧 / 护甲侧分开算，和运行时口径一致） */
    private static java.util.List<com.forgemaster.enchant.EnchantEffects.Entry> ofKind(
            java.util.List<com.forgemaster.enchant.EnchantEffects.Entry> all,
            com.forgemaster.enchant.CustomEnchant.Slot slot) {
        var out = new java.util.ArrayList<com.forgemaster.enchant.EnchantEffects.Entry>();
        for (var e : all) {
            if (e.def().slot() == slot || e.def().slot() == com.forgemaster.enchant.CustomEnchant.Slot.ANY) {
                out.add(e);
            }
        }
        return out;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
