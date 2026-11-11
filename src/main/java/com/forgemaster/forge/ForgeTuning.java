package com.forgemaster.forge;

import com.forgemaster.ForgingMasterPlugin;

/**
 * 锻造难度曲线。
 *
 * <p>所有节奏参数都按材料档位（tier 1~5）缩放：档位越高，指针越快、降温越快、
 * 叠钢时限越短、开刃漂移越猛、绿区越窄。基础值本身也偏快，整体手感更紧。</p>
 *
 * <p>系数都在 config 的 {@code difficulty} 段里，服主可以整体调软或调硬。</p>
 */
public final class ForgeTuning {

    private ForgeTuning() {
    }

    /** 档位差：tier 1 → 0，tier 5 → 4 */
    private static int span(int tier) {
        return Math.max(0, Math.min(4, tier - 1));
    }

    private static double perTier(ForgingMasterPlugin plugin, String path, double def) {
        return plugin.settings().num("difficulty." + path, def);
    }

    /** 敲打指针每走一格要几 tick，档位越高越短（下限 1 tick） */
    public static int hammerInterval(ForgingMasterPlugin plugin, int tier) {
        if (!enabled(plugin)) {
            return plugin.settings().hammerIntervalTicks();
        }
        double mult = 1.0 + perTier(plugin, "hammer-speed-per-tier", 0.12) * span(tier);
        return Math.max(1, (int) Math.round(plugin.settings().hammerIntervalTicks() / mult));
    }

    /** 每 tick 降温幅度，档位越高降得越快 */
    public static double heatDecay(ForgingMasterPlugin plugin, int tier) {
        if (!enabled(plugin)) {
            return plugin.settings().heatDecayPerTick();
        }
        double mult = 1.0 + perTier(plugin, "heat-decay-per-tier", 0.14) * span(tier);
        return plugin.settings().heatDecayPerTick() * mult;
    }

    /** 叠钢时限的缩放（档位越高越短，最低 45%） */
    public static double foldSecondsScale(ForgingMasterPlugin plugin, int tier) {
        if (!enabled(plugin)) {
            return 1.0;
        }
        double scale = 1.0 - perTier(plugin, "fold-time-per-tier", 0.11) * span(tier);
        return Math.max(0.45, scale);
    }

    /** 开刃压力漂移的缩放（档位越高越猛） */
    public static double grindDriftScale(ForgingMasterPlugin plugin, int tier) {
        if (!enabled(plugin)) {
            return 1.0;
        }
        return 1.0 + perTier(plugin, "grind-drift-per-tier", 0.16) * span(tier);
    }

    /** 开刃绿区的缩放（档位越高越窄，最低 55%） */
    public static double grindWidthScale(ForgingMasterPlugin plugin, int tier) {
        if (!enabled(plugin)) {
            return 1.0;
        }
        double scale = 1.0 - perTier(plugin, "grind-width-per-tier", 0.09) * span(tier);
        return Math.max(0.55, scale);
    }

    /**
     * 总分缩放。高档位天然压分，让神话/传说只留给手最稳的人。
     * 默认每档 -1.5%，tier 5 是 94%。
     */
    public static double scoreFactor(ForgingMasterPlugin plugin, int tier) {
        if (!enabled(plugin)) {
            return 1.0;
        }
        double factor = 1.0 - perTier(plugin, "score-penalty-per-tier", 0.015) * span(tier);
        return Math.max(0.6, factor);
    }

    /** 抛光指针扫动速度的缩放（档位越高扫得越快） */
    public static double polishSpeedScale(ForgingMasterPlugin plugin, int tier) {
        if (!enabled(plugin)) {
            return 1.0;
        }
        return 1.0 + perTier(plugin, "polish-speed-per-tier", 0.16) * span(tier);
    }

    /** 抛光亮区的缩放（档位越高越窄，最低 55%） */
    public static double polishWindowScale(ForgingMasterPlugin plugin, int tier) {
        if (!enabled(plugin)) {
            return 1.0;
        }
        double scale = 1.0 - perTier(plugin, "polish-window-per-tier", 0.09) * span(tier);
        return Math.max(0.55, scale);
    }

    /** 嵌银丝亮窗时长的缩放（档位越高越短，最低 50%） */
    public static double inlayWindowScale(ForgingMasterPlugin plugin, int tier) {
        if (!enabled(plugin)) {
            return 1.0;
        }
        double scale = 1.0 - perTier(plugin, "inlay-window-per-tier", 0.10) * span(tier);
        return Math.max(0.50, scale);
    }

    /** 嵌银丝的根数：基础 + 每两档 +1，夹在 2~8（轨道只有 9 格，留一格余量） */
    public static int inlayLength(int base, int tier) {
        return Math.max(2, Math.min(8, base + span(tier) / 2));
    }

    private static boolean enabled(ForgingMasterPlugin plugin) {
        return plugin.settings().flag("difficulty.enabled", true);
    }
}
