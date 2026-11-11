package com.forgemaster.spiritdisplay;

import com.forgemaster.selftest.SelfTestSuite;

/**
 * 剑灵显形模块的外部自检（v2.23 剑灵显形成长配套回归）。
 *
 * <p>只测不依赖服务器实体的纯函数：档位 → 显形倍数曲线（{@link SpiritDisplayService#scaleForTier}）、
 * 配置钳制（负数 / 超大 / NaN 退化到边界）、以及「倍数没变不重复 setTransformation」
 * 的判定（{@link SpiritDisplayService#needsRescale}）。实体层面的行为
 * （涨档插值渐变、show/hide 倍数生效、巡检不重发）进服手测，不在此覆盖。</p>
 */
public final class SelfTestSpiritDisplay {

    private SelfTestSpiritDisplay() {
    }

    /** 由自检套件统一挂接：{@code SelfTestSpiritDisplay.register(suite)} */
    public static void register(SelfTestSuite suite) {
        String g = "v2.23 剑灵显形成长";

        suite.addExternal(g, "档位曲线：0 档不放大、每上一档 ×1.15（3 档 = 1.15³）", () -> {
            double eps = 1e-9;
            if (Math.abs(SpiritDisplayService.scaleForTier(0, 1.15) - 1.0) > eps) {
                throw new IllegalStateException("0 档应为 1.0，实际 "
                        + SpiritDisplayService.scaleForTier(0, 1.15));
            }
            if (Math.abs(SpiritDisplayService.scaleForTier(1, 1.15) - 1.15) > eps) {
                throw new IllegalStateException("1 档应为 1.15，实际 "
                        + SpiritDisplayService.scaleForTier(1, 1.15));
            }
            if (Math.abs(SpiritDisplayService.scaleForTier(3, 1.15)
                    - Math.pow(1.15, 3)) > eps) {
                throw new IllegalStateException("3 档应为 1.15³，实际 "
                        + SpiritDisplayService.scaleForTier(3, 1.15));
            }
            // 逐档递推一致：第 n+1 档 = 第 n 档 × g（浮点误差内）
            for (int t = 1; t <= 6; t++) {
                double prev = SpiritDisplayService.scaleForTier(t - 1, 1.15);
                double cur = SpiritDisplayService.scaleForTier(t, 1.15);
                if (Math.abs(cur - prev * 1.15) > prev * 1e-9) {
                    throw new IllegalStateException("档位 " + t + " 递推不一致: " + cur
                            + " != " + prev + " × 1.15");
                }
                if (cur < prev) {
                    throw new IllegalStateException("档位曲线在 " + t + " 档出现回缩");
                }
            }
        });

        suite.addExternal(g, "配置钳制：负数 / 0 / NaN 退化到 1.0，超大钳到 2.0，负档位视为 0 档", () -> {
            double eps = 1e-9;
            // perTierGrowth 钳到 1.0~2.0
            if (SpiritDisplayService.scaleForTier(3, -1.0) != 1.0) {
                throw new IllegalStateException("负数 growth 应退化成不放大（×1），实际 "
                        + SpiritDisplayService.scaleForTier(3, -1.0));
            }
            if (SpiritDisplayService.scaleForTier(3, 0.0) != 1.0) {
                throw new IllegalStateException("growth=0 应退化成不放大（×1），实际 "
                        + SpiritDisplayService.scaleForTier(3, 0.0));
            }
            if (SpiritDisplayService.scaleForTier(3, Double.NaN) != 1.0) {
                throw new IllegalStateException("NaN growth 应退化成不放大（×1），实际 "
                        + SpiritDisplayService.scaleForTier(3, Double.NaN));
            }
            if (SpiritDisplayService.scaleForTier(1, 99.0) != 2.0) {
                throw new IllegalStateException("超大 growth 应钳到上界 2.0，实际 "
                        + SpiritDisplayService.scaleForTier(1, 99.0));
            }
            if (Math.abs(SpiritDisplayService.scaleForTier(3, 2.0) - 8.0) > eps) {
                throw new IllegalStateException("growth=2.0 时 3 档应为 2³=8，实际 "
                        + SpiritDisplayService.scaleForTier(3, 2.0));
            }
            // tier 钳到 ≥ 0：负档位视为 0 档（低于起显档位本来就不显形）
            if (SpiritDisplayService.scaleForTier(-2, 1.15) != 1.0) {
                throw new IllegalStateException("负档位应视为 0 档（倍数 1.0），实际 "
                        + SpiritDisplayService.scaleForTier(-2, 1.15));
            }
        });

        suite.addExternal(g, "倍数没变不重复 setTransformation：needsRescale 的零差 / 微差 / 真变判定", () -> {
            // 没变：完全不碰实体
            if (SpiritDisplayService.needsRescale(1.15f, 1.15f)) {
                throw new IllegalStateException("倍数相同时不应判定需要重设 transformation");
            }
            if (SpiritDisplayService.needsRescale(1.0f, 1.0f)) {
                throw new IllegalStateException("1.0 倍（growth=1.0 恒定）时不应判定需要重设");
            }
            // 浮点噪声级差异（1e-7 < 1e-6 容差）：视为没变
            if (SpiritDisplayService.needsRescale(1.0f, 1.0f + 1e-7f)) {
                throw new IllegalStateException("浮点噪声级差异不应触发重设");
            }
            // 真变：涨一档 1.15 → 1.3225
            if (!SpiritDisplayService.needsRescale(1.15f, (float) Math.pow(1.15, 2))) {
                throw new IllegalStateException("涨档后倍数变了，必须判定需要重设 transformation");
            }
            // 降档（拔剑换低档剑 / 灵性受损回落）同样要缩回去
            if (!SpiritDisplayService.needsRescale((float) Math.pow(1.15, 3), 1.15f)) {
                throw new IllegalStateException("降档后倍数变小，也必须判定需要重设");
            }
        });
    }
}
