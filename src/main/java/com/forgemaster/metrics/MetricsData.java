package com.forgemaster.metrics;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.econ.CoinReason;
import com.forgemaster.stats.StatsStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次「主线程取数」的结果：把 {@code StatsStore} 里的实时状态拷成一份<b>纯数据</b>。
 *
 * <p>为什么必须先拷一份：看板的落盘与统计都在异步线程跑，而 {@code StatsStore} 的
 * {@code Stats} 字段是主线程随时在读写的普通字段。异步线程直接遍历
 * {@code stats.snapshot()} 拿到的 entry 仍然指向活的 {@code Stats} 对象，
 * 轻则读到「一半旧一半新」，重则撞上 HashMap 结构变化抛
 * {@code ConcurrentModificationException}——那会让当天的样本直接丢失。</p>
 *
 * <p>所以规矩是：<b>主线程只做「把需要的数字抄下来」这一件事</b>，
 * 抄完这份不可变数据就可以随便丢给异步线程，之后主线程怎么改都影响不到它。
 * 这也是任务书「异步任务不能遍历 Bukkit 集合」的落地方式。</p>
 *
 * <p>字段口径：{@code producedRaw} / {@code consumed} / {@code forged}
 * 全是<b>服务器生命周期</b>累计（{@code StatsStore} 的计数器不随赛季重置）。
 * 当日增量由 {@link MetricsSample#ofDay} 与基线相减得到。</p>
 */
public final class MetricsData {

    /** 单个玩家的计数副本（模块热度 / 使用深度用） */
    public record PlayerRow(int forged, int bestStrengthen, int level, long coins,
                            int salvaged, int repaired, double bestScore) {
    }

    /** 采样时的在线人数 */
    public final int online;
    /** 有档案的玩家数（含离线） */
    public final int players;
    /** 全服剑币总量 */
    public final long totalCoins;
    /** 生命周期累计产出（<b>含</b>退款） */
    public final long producedRaw;
    /** 生命周期累计消耗 */
    public final long consumed;
    /** 生命周期累计退款回滚（产出方向，但不是真产出） */
    public final long refund;
    /** 按理由的累计产出（副本） */
    public final Map<String, Long> producedBy;
    /** 按理由的累计消耗（副本） */
    public final Map<String, Long> consumedBy;

    public final long forged;
    public final long salvaged;
    public final long repaired;
    public final long spirits;
    public final long tombs;
    public final long commissionsDone;
    public final long commissionsFailed;
    public final long duelWins;
    public final long duelLosses;
    public final long achvPoints;

    /** 品质 → 锻造件数（键为 {@code Quality.name()}） */
    public final Map<String, Long> byQuality;

    /** 全体余额（含 0，未排序） */
    public final List<Long> balances;

    /** 每玩家计数副本（与 {@link #balances} 同序） */
    public final List<PlayerRow> rows;

    public MetricsData(int online, int players, long totalCoins, long producedRaw, long consumed,
                       long refund, long forged, long salvaged,
                       long repaired, long spirits, long tombs, long commissionsDone,
                       long commissionsFailed, long duelWins, long duelLosses, long achvPoints,
                       Map<String, Long> producedBy, Map<String, Long> consumedBy,
                       Map<String, Long> byQuality, List<Long> balances, List<PlayerRow> rows) {
        this.online = Math.max(0, online);
        this.players = Math.max(0, players);
        this.totalCoins = Math.max(0L, totalCoins);
        this.producedRaw = Math.max(0L, producedRaw);
        this.consumed = Math.max(0L, consumed);
        this.refund = Math.max(0L, refund);
        this.forged = Math.max(0L, forged);
        this.salvaged = Math.max(0L, salvaged);
        this.repaired = Math.max(0L, repaired);
        this.spirits = Math.max(0L, spirits);
        this.tombs = Math.max(0L, tombs);
        this.commissionsDone = Math.max(0L, commissionsDone);
        this.commissionsFailed = Math.max(0L, commissionsFailed);
        this.duelWins = Math.max(0L, duelWins);
        this.duelLosses = Math.max(0L, duelLosses);
        this.achvPoints = Math.max(0L, achvPoints);
        this.producedBy = copy(producedBy);
        this.consumedBy = copy(consumedBy);
        this.byQuality = copy(byQuality);
        this.balances = balances == null ? List.of() : List.copyOf(balances);
        this.rows = rows == null ? List.of() : List.copyOf(rows);
    }

    private static Map<String, Long> copy(Map<String, Long> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> out = new LinkedHashMap<>(raw.size());
        for (Map.Entry<String, Long> e : raw.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            long v = Math.max(0L, e.getValue());
            if (v > 0) {
                out.put(e.getKey(), v);
            }
        }
        return Map.copyOf(out);
    }

    /** 空数据（stats 未就绪 / 启动早期）：所有方法都安全返回 0 */
    public static MetricsData empty(int online) {
        return new MetricsData(online, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                Map.of(), Map.of(), Map.of(), List.of(), List.of());
    }

    /**
     * 自检用的最小构造：只填流水与余额，其余清零。
     *
     * <p>生产路径永远走 {@link #capture}；这里给断言一个不依赖服务器状态的入口
     * （比如「当日增量 = 当前 − 基线 − 退款增量」这种纯算术）。</p>
     */
    public static MetricsData minimal(int online, long totalCoins, long producedRaw, long consumed,
                                      long refund, long forged,
                                      Map<String, Long> producedBy, Map<String, Long> consumedBy) {
        return new MetricsData(online, 0, totalCoins, producedRaw, consumed, refund,
                forged, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                producedBy, consumedBy, Map.of(), List.of(), List.of());
    }

    /**
     * 主线程取数。<b>只允许在主线程调用</b>（要读 Bukkit 的在线人数）。
     *
     * <p>单个玩家的档案读取失败就跳过那一个人，不放弃整次采样：一份坏档案不该让
     * 当天的趋势整段缺失。</p>
     */
    public static MetricsData capture(ForgingMasterPlugin plugin) {
        int online = 0;
        try {
            online = org.bukkit.Bukkit.getOnlinePlayers().size();
        } catch (RuntimeException ignored) {
            // 启动极早期 / 自检环境：拿不到就当 0
        }
        StatsStore stats = null;
        try {
            stats = plugin == null ? null : plugin.stats();
        } catch (RuntimeException ignored) {
            stats = null;
        }
        if (stats == null) {
            return empty(online);
        }

        long forged = 0;
        long salvaged = 0;
        long repaired = 0;
        long spirits = 0;
        long tombs = 0;
        long commDone = 0;
        long commFailed = 0;
        long duelW = 0;
        long duelL = 0;
        long achv = 0;
        long coins = 0;
        Map<String, Long> byQuality = new LinkedHashMap<>();
        List<Long> balances = new ArrayList<>();
        List<PlayerRow> rows = new ArrayList<>();

        List<Map.Entry<java.util.UUID, StatsStore.Stats>> snapshot;
        try {
            snapshot = stats.snapshot();
        } catch (RuntimeException ex) {
            snapshot = List.of();
        }
        for (Map.Entry<java.util.UUID, StatsStore.Stats> e : snapshot) {
            StatsStore.Stats st = e == null ? null : e.getValue();
            if (st == null) {
                continue;
            }
            try {
                long bal = Math.max(0L, st.coins);
                balances.add(bal);
                coins = MetricsMath.add(coins, bal);
                forged = MetricsMath.add(forged, Math.max(0, st.forged));
                salvaged = MetricsMath.add(salvaged, Math.max(0, st.salvaged));
                repaired = MetricsMath.add(repaired, Math.max(0, st.repaired));
                spirits = MetricsMath.add(spirits, Math.max(0, st.spirits));
                tombs = MetricsMath.add(tombs, Math.max(0, st.tombs));
                commDone = MetricsMath.add(commDone, Math.max(0, st.commissionsDone));
                commFailed = MetricsMath.add(commFailed, Math.max(0, st.commissionsFailed));
                duelW = MetricsMath.add(duelW, Math.max(0, st.duelWins));
                duelL = MetricsMath.add(duelL, Math.max(0, st.duelLosses));
                achv = MetricsMath.add(achv, Math.max(0, st.achvPoints));
                if (st.byQuality != null && !st.byQuality.isEmpty()) {
                    for (Map.Entry<String, Integer> q : st.byQuality.entrySet()) {
                        if (q.getKey() == null || q.getValue() == null || q.getValue() <= 0) {
                            continue;
                        }
                        byQuality.merge(q.getKey(), (long) q.getValue(), MetricsMath::add);
                    }
                }
                rows.add(new PlayerRow(st.forged, st.bestStrengthen, st.level, bal,
                        st.salvaged, st.repaired, st.bestScore));
            } catch (RuntimeException ignored) {
                // 单份坏档案跳过
            }
        }

        return new MetricsData(online, balances.size(), coins,
                stats.producedTotal(), stats.consumedTotal(),
                amountOf(stats.producedByReason(), CoinReason.REFUND.key()),
                forged, salvaged, repaired, spirits, tombs,
                commDone, commFailed, duelW, duelL, achv,
                stats.producedByReason(), stats.consumedByReason(), byQuality, balances, rows);
    }

    // ------------------------------------------------------------ 派生量（全部除零安全）

    /** 生命周期累计产出，剔除退款（与文字报表同口径） */
    public long produced() {
        return Math.max(0L, producedRaw - refund);
    }

    /** 净通胀（生命周期口径） */
    public long net() {
        return produced() - consumed;
    }

    /** 全体余额升序副本（中位 / 基尼都要求有序） */
    public List<Long> sortedBalances() {
        List<Long> out = new ArrayList<>(balances.size());
        for (Long v : balances) {
            out.add(v == null ? 0L : Math.max(0L, v));
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /** 持币玩家余额（> 0，升序）：基尼只在这些人身上算，理由见 {@code MetricsReporter} */
    public List<Long> holders() {
        List<Long> out = new ArrayList<>();
        for (Long v : sortedBalances()) {
            if (v != null && v > 0) {
                out.add(v);
            }
        }
        return out;
    }

    public int holderCount() {
        int n = 0;
        for (Long v : balances) {
            if (v != null && v > 0) {
                n++;
            }
        }
        return n;
    }

    /** 人均（按档案数，含零余额档案） */
    public long perCapita() {
        return MetricsMath.perCapita(totalCoins, players);
    }

    /** 中位数（全体口径，含零余额） */
    public double median() {
        return MetricsMath.median(sortedBalances());
    }

    /** 基尼系数（只在持币玩家上算） */
    public double gini() {
        List<Long> holders = holders();
        return MetricsMath.gini(holders, MetricsMath.sum(holders));
    }

    /** Top 10% 持币占比（只在持币玩家上算） */
    public double top10Share() {
        List<Long> holders = holders();
        return MetricsMath.topShare(holders, 0.10);
    }

    /** 某个理由键的累计消耗金额 */
    public long consumedOf(String key) {
        return amountOf(consumedBy, key);
    }

    /** 某个理由键的累计产出金额 */
    public long producedOf(String key) {
        return amountOf(producedBy, key);
    }

    /**
     * 「其它」桶占比：`other-in` + `other-out` 占各自方向的比例。
     *
     * <p>切片 1 的教训：没带理由的调用点会静静落进「其它」，报表上那 40% 无人认领。
     * 这个数就是「还有多少地方没接上」的体感指标，GUI 在超过阈值时标红。</p>
     */
    public double otherShare() {
        long otherIn = consumedOf(CoinReason.OTHER_IN.key()) + producedOf(CoinReason.OTHER_IN.key());
        long otherOut = consumedOf(CoinReason.OTHER_OUT.key()) + producedOf(CoinReason.OTHER_OUT.key());
        long whole = MetricsMath.add(produced(), consumed);
        return MetricsMath.share(MetricsMath.add(otherIn, otherOut), whole);
    }

    /** 总锻造件数（= 所有玩家 forged 之和；与 byQuality 求和应当一致） */
    public long qualityTotal() {
        long sum = 0;
        for (Long v : byQuality.values()) {
            if (v != null && v > 0) {
                sum = MetricsMath.add(sum, v);
            }
        }
        return sum;
    }

    /** 某个品质的件数 */
    public long qualityCount(String name) {
        if (name == null) {
            return 0;
        }
        for (Map.Entry<String, Long> e : byQuality.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue() == null ? 0 : Math.max(0, e.getValue());
            }
        }
        return 0;
    }


    /** 档案数里有多少人用过某个计数（>= min 次）；rows 为空返回 0 */
    public int playersWithForgedAtLeast(int min) {
        if (rows.isEmpty()) {
            return 0;
        }
        int threshold = Math.max(1, min);
        int n = 0;
        for (PlayerRow r : rows) {
            if (r.forged() >= threshold) {
                n++;
            }
        }
        return n;
    }

    /** 平均「历史最高评分」（只算有过锻造的人），0 数据返回 0 */
    public double averageBestScore() {
        double sum = 0;
        int n = 0;
        for (PlayerRow r : rows) {
            if (r.forged() > 0 && r.bestScore() > 0) {
                sum += r.bestScore();
                n++;
            }
        }
        return n <= 0 ? 0.0 : sum / n;
    }

    /** 平均专精等级（只算有档案的人） */
    public double averageLevel() {
        if (rows.isEmpty()) {
            return 0.0;
        }
        long sum = 0;
        for (PlayerRow r : rows) {
            sum += Math.max(0, r.level());
        }
        return MetricsMath.average(sum, rows.size());
    }

    /** 最高专精等级 */
    public int maxLevel() {
        int max = 0;
        for (PlayerRow r : rows) {
            if (r.level() > max) {
                max = r.level();
            }
        }
        return max;
    }

    /** 强化等级分桶人数：{0, 1-10, 11-20, 21-30, 31-40, 41-50, 51-60, >60} */
    public long[] strengthenBuckets() {
        long[] buckets = new long[8];
        for (PlayerRow r : rows) {
            buckets[bucketOf(r.bestStrengthen())]++;
        }
        return buckets;
    }

    /** 强化等级分桶边界（与 {@link #strengthenBuckets()} 对应） */
    public static String bucketLabel(int index) {
        return switch (index) {
            case 0 -> "0（未强化）";
            case 1 -> "1 ~ 10";
            case 2 -> "11 ~ 20";
            case 3 -> "21 ~ 30";
            case 4 -> "31 ~ 40";
            case 5 -> "41 ~ 50";
            case 6 -> "51 ~ 60";
            default -> "60 以上";
        };
    }

    private static int bucketOf(int level) {
        if (level <= 0) {
            return 0;
        }
        if (level <= 10) {
            return 1;
        }
        if (level <= 20) {
            return 2;
        }
        if (level <= 30) {
            return 3;
        }
        if (level <= 40) {
            return 4;
        }
        if (level <= 50) {
            return 5;
        }
        if (level <= 60) {
            return 6;
        }
        return 7;
    }

    // ------------------------------------------------------------ 工具

    /** 大小写不敏感的取值（理由键历史上有过大小写漂移） */
    public static long amountOf(Map<String, Long> by, String key) {
        if (by == null || key == null) {
            return 0L;
        }
        for (Map.Entry<String, Long> e : by.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key) && e.getValue() != null) {
                return Math.max(0L, e.getValue());
            }
        }
        return 0L;
    }

    /** 按金额降序排好的理由桶（GUI / CSV 都用这个顺序，同额按名稳定） */
    public static List<Map.Entry<String, Long>> ranked(Map<String, Long> by) {
        List<Map.Entry<String, Long>> out = new ArrayList<>();
        if (by == null) {
            return out;
        }
        for (Map.Entry<String, Long> e : by.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0) {
                continue;
            }
            out.add(Map.entry(e.getKey(), e.getValue()));
        }
        out.sort(Comparator.comparingLong((Map.Entry<String, Long> e) -> e.getValue()).reversed()
                .thenComparing(Map.Entry::getKey));
        return out;
    }
}
