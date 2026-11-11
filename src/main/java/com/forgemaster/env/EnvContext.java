package com.forgemaster.env;

/**
 * 一次锻造开局时固化的「环境修正」快照。
 *
 * <p>纯数据 + 只读查询，不持有任何 Bukkit 对象：{@code EnvManager.resolve(player)}
 * 在开局时算一次，塞进 {@code ForgeSession}，整局工序都用同一份修正，避免玩家中途
 * 走进雨中导致前后判定不一致。</p>
 *
 * <p>单位约定：</p>
 * <ul>
 *   <li>{@link #heatBonus} —— 倍率修正。+0.10 表示「保温 +10%」（降温更慢 / 升温更省热量），
 *       -0.02 表示散热更快。接线建议 {@code decay = heatDecayPerTick * (1.0 - heatBonus)}。</li>
 *   <li>{@link #quenchBonus} —— 倍率修正。+0.08 表示淬火收益 +8%，结算时乘 {@code (1.0 + quenchBonus)}。</li>
 *   <li>{@link #foldSecondsBonus} —— 绝对秒数修正。+0.5 表示叠钢多给 0.5 秒，-0.3 表示少给。</li>
 *   <li>{@link #grindWidthBonus} —— 绝对宽度修正，和 {@code grindTargetWidth} /
 *       {@code settings.repairZoneWidth()} 同一量纲，+1.2 表示绿区宽 1.2。</li>
 * </ul>
 */
public final class EnvContext {

    /** 天气档：{@code "storm"} / {@code "rain"} / {@code "clear"} */
    public final String weatherKey;

    /** 时辰档：{@code "dawn"} / {@code "day"} / {@code "dusk"} / {@code "night"} */
    public final String timeKey;

    /** 加热保温修正（倍率，可正可负） */
    public final double heatBonus;

    /** 淬火收益修正（倍率，可正可负） */
    public final double quenchBonus;

    /** 叠钢时长修正（秒，可正可负） */
    public final double foldSecondsBonus;

    /** 开刃绿区宽度修正（绝对量，可正可负） */
    public final double grindWidthBonus;

    private final String weatherLabel;
    private final String timeLabel;

    public EnvContext(String weatherKey,
                      String timeKey,
                      double heatBonus,
                      double quenchBonus,
                      double foldSecondsBonus,
                      double grindWidthBonus,
                      String weatherLabel,
                      String timeLabel) {
        this.weatherKey = weatherKey == null ? "clear" : weatherKey;
        this.timeKey = timeKey == null ? "day" : timeKey;
        this.heatBonus = heatBonus;
        this.quenchBonus = quenchBonus;
        this.foldSecondsBonus = foldSecondsBonus;
        this.grindWidthBonus = grindWidthBonus;
        this.weatherLabel = weatherLabel == null ? this.weatherKey : weatherLabel;
        this.timeLabel = timeLabel == null ? this.timeKey : timeLabel;
    }

    /**
     * 兜底中性快照：全 0、"clear" / "day"。
     *
     * <p>{@code resolve(null)} 会优先用 config 里的晴朗 / 白天文案，这条常量只在
     * config 完全读不到时兜底，任何地方都能安全引用。</p>
     */
    public static final EnvContext NEUTRAL =
            new EnvContext("clear", "day", 0.0, 0.0, 0.0, 0.0, "晴朗", "白天");

    /** 中文天气，如 "雷雨"。内容来自 config，可直接塞进 MiniMessage 模板 */
    public String weatherLabel() {
        return weatherLabel;
    }

    /** 中文时辰，如 "夜晚" */
    public String timeLabel() {
        return timeLabel;
    }

    /** 是否有任何一项非 0 修正 */
    public boolean hasBonus() {
        return heatBonus != 0.0 || quenchBonus != 0.0 || foldSecondsBonus != 0.0 || grindWidthBonus != 0.0;
    }

    /** 四舍五入到 1 位小数后的修正，便于和会话里的 double 比较 / 打日志 */
    public String debugKey() {
        return weatherKey + "/" + timeKey;
    }

    /** 换一份文案（文案变了但数值不变时用，比如 reload config 后重渲染） */
    public EnvContext withLabels(String weatherLabel, String timeLabel) {
        return new EnvContext(weatherKey, timeKey, heatBonus, quenchBonus, foldSecondsBonus, grindWidthBonus,
                weatherLabel, timeLabel);
    }

    /** 叠钢时长的实际用法：给个安全的非负结果，避免负修正把时间压成 0 或负数 */
    public double foldSecondsOf(double baseSeconds) {
        return Math.max(0.2, baseSeconds + foldSecondsBonus);
    }

    /** 开刃宽度的实际用法：同样兜底到不小于 1.0 */
    public double grindWidthOf(double baseWidth) {
        return Math.max(1.0, baseWidth + grindWidthBonus);
    }

    /** 淬火收益的实际倍率 */
    public double quenchMultiplier() {
        return Math.max(0.0, 1.0 + quenchBonus);
    }

    /** 加热保温的实际降温倍率（越小降温越慢） */
    public double heatDecayMultiplier() {
        return Math.max(0.0, 1.0 - heatBonus);
    }

    @Override
    public String toString() {
        return "EnvContext[" + debugKey()
                + " heat=" + heatBonus
                + " quench=" + quenchBonus
                + " fold=" + foldSecondsBonus
                + " grind=" + grindWidthBonus + "]";
    }
}
