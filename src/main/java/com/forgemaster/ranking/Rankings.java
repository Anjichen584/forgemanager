package com.forgemaster.ranking;

import com.forgemaster.growth.GrowthUtil;
import com.forgemaster.stats.StatsStore;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 排行计算。A4 排行与 E2 赛季归档共用：过滤自检数据、过滤空档案、按维度降序。
 *
 * <p>强化维度（{@link Metric#STRENGTHEN}）是特例：它没有历史档案，
 * 候选来自 {@link StrengthenSnapshot}——在线背包与落库历史取较大值，
 * 且背包里带着锻造品的玩家才会上榜。见该类的类注释。</p>
 */
public final class Rankings {

    /** 一行排行：rank 从 1 开始 */
    public record Row(int rank, UUID uuid, String name, double value, StatsStore.Stats stats) {
    }

    private Rankings() {
    }

    /** 是否计入排行：自检数据不进榜，完全没有过数据的档案也不进榜 */
    public static boolean active(StatsStore.Stats st) {
        if ("SelfTest".equals(st.name)) {
            return false;
        }
        // v2.26.0 审计轮补：原来只看 forged / coins / reputation / streak 四项，
        // 漏了**本轮才上线的比试维度**。后果很具体：一个只打过比试、没锻造过、
        // 手里没币、没声望、没连锻记录的号（duel 不收费，结算只 countDuel），
        // 会被判成「没有过数据」而整条从 rank duel 榜上消失 —— 明明 profile 里
        // 写着「3 胜 1 负」。这正是加 DUEL 维度时最该想到、却最容易漏的一处：
        // 「活跃档案」的定义没跟着新维度一起长。
        return st.forged > 0 || st.coins > 0 || st.reputation > 0 || st.streak > 0
                || st.duelWins > 0 || st.duelLosses > 0;
    }

    /**
     * 某维度某玩家的数值。
     *
     * <p>普通维度直接读档案；强化维度查 StrengthenSnapshot（{@link Metric#value} 对它恒为 0，
     * 因为 Stats 里没有这个字段）。</p>
     */
    public static double valueOf(Metric metric, UUID uuid, StatsStore.Stats st) {
        if (metric != null && metric.online()) {
            return StrengthenSnapshot.levelOf(uuid);
        }
        return metric == null || st == null ? 0 : metric.value(st);
    }

    /** 全部上榜档案，按维度降序（数值 > 0 才上榜） */
    public static List<Map.Entry<UUID, StatsStore.Stats>> sorted(StatsStore store, Metric metric) {
        List<Map.Entry<UUID, StatsStore.Stats>> list = new ArrayList<>();
        if (metric != null && metric.online()) {
            // 强化榜：候选 = 在线背包看到的 ∪ 档案里的历史最高，
            // 档案只用来提供称号 / 锻造次数等展示信息，没有档案就临时造一个空壳（不写回 store）
            Map<UUID, StatsStore.Stats> known = new HashMap<>();
            for (Map.Entry<UUID, StatsStore.Stats> e : store.snapshot()) {
                known.put(e.getKey(), e.getValue());
            }
            for (Map.Entry<UUID, StrengthenSnapshot.Best> e : StrengthenSnapshot.levels().entrySet()) {
                StrengthenSnapshot.Best b = e.getValue();
                if (b == null || b.level() <= 0) {
                    continue;
                }
                StatsStore.Stats st = known.get(e.getKey());
                if (st == null) {
                    st = new StatsStore.Stats();
                }
                if ("SelfTest".equals(st.name)) {
                    continue;
                }
                list.add(new AbstractMap.SimpleImmutableEntry<>(e.getKey(), st));
            }
        } else {
            for (Map.Entry<UUID, StatsStore.Stats> e : store.snapshot()) {
                StatsStore.Stats st = e.getValue();
                if (active(st) && metric.value(st) > 0) {
                    list.add(e);
                }
            }
        }
        list.sort(Comparator.comparingDouble(
                (Map.Entry<UUID, StatsStore.Stats> e) -> valueOf(metric, e.getKey(), e.getValue())).reversed());
        return list;
    }

    /** 前 limit 名 */
    public static List<Row> top(StatsStore store, Metric metric, int limit) {
        List<Map.Entry<UUID, StatsStore.Stats>> list = sorted(store, metric);
        List<Row> out = new ArrayList<>();
        int n = Math.min(Math.max(0, limit), list.size());
        for (int i = 0; i < n; i++) {
            Map.Entry<UUID, StatsStore.Stats> e = list.get(i);
            StatsStore.Stats st = e.getValue();
            out.add(new Row(i + 1, e.getKey(), nameOf(metric, st, e.getKey()),
                    valueOf(metric, e.getKey(), st), st));
        }
        return out;
    }

    /** 榜上的显示名：档案没名字时退回强化榜里记的玩家名 */
    private static String nameOf(Metric metric, StatsStore.Stats st, UUID uuid) {
        if (metric != null && metric.online()) {
            StrengthenSnapshot.Best b = StrengthenSnapshot.bestOf(uuid);
            if (b != null && (st.name == null || st.name.isBlank())) {
                return GrowthUtil.displayName(b.name(), uuid);
            }
        }
        return GrowthUtil.displayName(st.name, uuid);
    }

    /** 某玩家的名次，未上榜返回 -1 */
    public static int rankOf(StatsStore store, Metric metric, UUID uuid) {
        List<Map.Entry<UUID, StatsStore.Stats>> list = sorted(store, metric);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getKey().equals(uuid)) {
                return i + 1;
            }
        }
        return -1;
    }

    /** 上榜人数（任一维度都算） */
    public static int activeCount(StatsStore store) {
        int n = 0;
        for (Map.Entry<UUID, StatsStore.Stats> e : store.snapshot()) {
            if (active(e.getValue())) {
                n++;
            }
        }
        return n;
    }
}
