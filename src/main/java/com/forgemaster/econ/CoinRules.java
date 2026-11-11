package com.forgemaster.econ;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.quality.Quality;

/**
 * 剑币产出规则。所有产出来源集中在这里，方便统一调平衡。
 * 配置项走 {@code econ.*}，用 Settings 的通用访问器取，不必改 Settings。
 */
public final class CoinRules {

    private final ForgingMasterPlugin plugin;

    public CoinRules(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    /** 锻成一把剑给多少币：底薪 + 品质档 + 分数，再乘连锻链倍率 */
    public long forgeReward(Quality quality, double score, int streak) {
        return forgeReward(quality, score, streak, 1.0);
    }

    /**
     * 同上，带行会加成（拓展第三档）：倍率由 ForgeManager 算好传入（guard null，
     * 1.0 = 无行会 / 未达档），只在 CoinRules 锻造奖励这一处乘。
     */
    public long forgeReward(Quality quality, double score, int streak, double guildMultiplier) {
        var s = plugin.settings();
        long base = s.intOf("coins.forge-base", 12);
        long byQuality = (long) quality.ordinal() * s.intOf("coins.forge-per-quality", 15);
        long byScore = Math.round(score) * s.intOf("coins.forge-per-score", 1);
        double mult = com.forgemaster.stats.StatsStore.streakMultiplier(streak)
                * Math.max(1.0, guildMultiplier);
        long raw = Math.round((base + byQuality + byScore) * mult);
        return Math.max(1, raw);
    }

    /** 委托交货给的币 */
    public long commissionReward(int reputationReward) {
        var s = plugin.settings();
        return Math.max(1, s.intOf("coins.commission-base", 40)
                + (long) reputationReward * s.intOf("coins.commission-per-rep", 2));
    }

    /** 大赛名次给的币 */
    public long contestReward(int rank) {
        var s = plugin.settings();
        return switch (rank) {
            case 1 -> s.intOf("coins.contest-first", 300);
            case 2 -> s.intOf("coins.contest-second", 180);
            case 3 -> s.intOf("coins.contest-third", 100);
            default -> s.intOf("coins.contest-other", 40);
        };
    }

    /** 剑币的展示文本 */
    public static String fmt(long coins) {
        return "<#ffd166>" + coins + "</#ffd166> <gray>币";
    }
}