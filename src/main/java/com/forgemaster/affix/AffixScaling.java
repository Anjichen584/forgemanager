package com.forgemaster.affix;

import com.forgemaster.ForgingMasterPlugin;

/**
 * 词条数值按材料档位缩放。
 *
 * <p>低档材料（铁、金）抽到的词条基础值低、随机波动也小，出什么都不会离谱；
 * 档位越高，词条基准越高、波动区间越宽——高档材料的词条能出好货，也能出平庸货。</p>
 *
 * <p>两套系数都在 config 的 {@code affix-scaling} 段里：</p>
 * <ul>
 *   <li>{@code value} —— 数值倍率，决定「基准有多高」；</li>
 *   <li>{@code spread} —— 波动倍率，决定「上下浮动有多宽」。</li>
 * </ul>
 */
public final class AffixScaling {

    private AffixScaling() {
    }

    /** 档位归一化：tier 1~5，越界夹住 */
    private static int tierIndex(int tier) {
        return Math.max(1, Math.min(5, tier));
    }

    private static boolean enabled(ForgingMasterPlugin plugin) {
        return plugin.settings().flag("affix-scaling.enabled", true);
    }

    /** 数值倍率：该档词条的基准值倍数 */
    public static double valueScale(ForgingMasterPlugin plugin, int tier) {
        if (!enabled(plugin)) {
            return 1.0;
        }
        return Math.max(0.0, plugin.settings().num(
                "affix-scaling.tiers." + tierIndex(tier) + ".value", defaultValue(tierIndex(tier))));
    }

    /** 波动倍率：该档词条的随机半宽倍数 */
    public static double spreadScale(ForgingMasterPlugin plugin, int tier) {
        if (!enabled(plugin)) {
            return 1.0;
        }
        return Math.max(0.0, plugin.settings().num(
                "affix-scaling.tiers." + tierIndex(tier) + ".spread", defaultSpread(tierIndex(tier))));
    }

    /** config 缺失时的兜底：低档弱且稳，高档强且飘 */
    public static double defaultValue(int tier) {
        return switch (tierIndex(tier)) {
            case 1 -> 0.50;
            case 2 -> 0.70;
            case 3 -> 1.00;
            case 4 -> 1.30;
            default -> 1.65;
        };
    }

    public static double defaultSpread(int tier) {
        return switch (tierIndex(tier)) {
            case 1 -> 0.40;
            case 2 -> 0.60;
            case 3 -> 1.00;
            case 4 -> 1.25;
            default -> 1.55;
        };
    }
}
