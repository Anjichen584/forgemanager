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
        for (Bot b : bots) {
            DamageBudget.Result r = DamageBudget.simulateHit(
                    b.attackAttr(), PLAYER_BASE, b.critChance(), b.critDamage(),
                    b.lifesteal(), MAX_HP, critLimits, budget);
            double hitsCrit = WARDEN_HP / r.finalHit();
            double healPct = r.healCrit() / MAX_HP * 100.0;
            if (b.name().startsWith("满配") || b.name().startsWith("极端")) {
                worstHits = Math.min(worstHits, hitsCrit);
                worstHealPct = Math.max(worstHealPct, healPct);
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

        System.out.println();
        if (fail == 0) {
            System.out.println("  结论：全部通过（" + (10) + " 项断言）");
        } else {
            System.out.println("  结论：有 " + fail + " 项超标，需要调数值");
        }
        System.exit(fail == 0 ? 0 : 1);
    }

    private static int check(String name, boolean ok, String detail) {
        System.out.println("  [" + (ok ? "PASS" : "FAIL") + "] " + name + " —— " + detail);
        return ok ? 0 : 1;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}