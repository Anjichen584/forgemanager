package com.forgemaster.metrics;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.econ.CoinReason;
import com.forgemaster.stats.StatsStore;
import com.forgemaster.util.Msg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 经济体检报表（服主数据看板的「一屏结论」）。
 *
 * <p>为什么要有这一层：{@code StatsStore} 里攒的是流水计数——总量、按理由分桶、余额快照，
 * 都是「原料」。原料本身不解释任何事：产出一万、消耗九千，到底是健康还是通胀？
 * 钱是集中在一小撮人手里还是散得均匀？这些要算出来才看得见。本类只做「算 + 排版」，
 * 不碰存档、不碰调度，任何时刻调用都只是读一遍快照。</p>
 *
 * <p>为什么退款要单列：{@link CoinReason#REFUND} 走的是产出方向，但它记的是「这笔钱本来就
 * 不该动」——拍卖流拍回滚、断线续做退钱。把它算进产出，一块钱在几个人手里转两圈就能刷出
 * 一片通红，服主会以为在自己看不见的地方放了大水。所以累计产出与产出构成都把它剔掉，
 * 只在真有退款时单独提一行。</p>
 */
public final class MetricsReporter {

    /**
     * 算基尼系数的最少样本数。低于这个数不显示结论——理由见 {@link #giniLine}。
     * 不做成配置项：这是「统计常识」而不是「服务器口味」。
     */
    private static final int GINI_MIN_PLAYERS = 5;

    /** 行分隔符：调用方按它 split 后逐行发送，所以每一行都必须自带颜色标签 */
    private static final String NL = "<newline>";

    /** 一条流水桶：显示名 + 金额 */
    private record Row(String label, long amount) {
    }

    private final ForgingMasterPlugin plugin;

    public MetricsReporter(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 多行报表：每行是一条 MiniMessage 字符串，行与行之间用 {@code <newline>} 分隔。
     * 调用方会用 {@code .split("<newline>")} 拆开逐行发送。
     *
     * <p>纯只读：只读配置与流水快照，不写文件、不改状态、不调度任务。</p>
     */
    public String text() {
        StatsStore stats = stats();
        if (stats == null) {
            // 启动早期 / reload 中途 stats 还没挂上：给一句话而不是炸掉整个命令
            return "<gray>经济体检 <dark_gray>· </dark_gray><red>统计档案还没就绪，稍后再看</red>";
        }

        int topN = clamp(settingsInt("metrics.top-n", 10), 1, 50);
        // 阈值是概率量：配置写坏（负数 / NaN）就退回 0.55，不能让「一律标红」或「永不标红」上线
        double giniWarn = settingsNum("metrics.gini-warn", 0.55);
        if (!Double.isFinite(giniWarn)) {
            giniWarn = 0.55;
        }
        giniWarn = Math.max(0.0, Math.min(1.0, giniWarn));
        int historyDays = Math.max(1, settingsInt("metrics.history-days", 30));

        // ---- 余额快照：总量 / 人均 / 中位 / 基尼 全部出自同一份副本，
        // 免得「总量是这一秒的、中位是上一秒的」，两个数互相打架
        List<Long> asc = sortedBalances(stats);
        int players = asc.isEmpty() ? Math.max(0, stats.playerCount()) : asc.size();
        long coinTotal = asc.isEmpty() ? Math.max(0, stats.totalCoins()) : sum(asc);
        double median = median(asc);
        long perCapita = players <= 0 ? 0 : Math.round((double) coinTotal / players);

        // 基尼只在「持币玩家」上算。
        //
        // 不这么做的后果是实测出来的：测试服 33 个档案里只有 1 个人有 300 币，
        // 拿全体算基尼是 0.97，报表天天挂红字「偏高」。可那 32 个零余额档案
        // 是「没玩过」或者「赛季重置后还没上线」，不是「被经济排斥的穷人」——
        // 把它们算进去，等于用死亡玩家衡量贫富差距。
        // 中位数保留全体口径（「中间那个玩家有多少钱」本身就是要看的东西）。
        List<Long> holders = new ArrayList<>(asc.size());
        for (Long v : asc) {
            if (v != null && v > 0) {
                holders.add(v);
            }
        }
        double gini = gini(holders, sum(holders));
        int holderCount = holders.size();

        // ---- 流水
        long producedRaw = Math.max(0, stats.producedTotal());
        long consumed = Math.max(0, stats.consumedTotal());
        Map<String, Long> producedBy = stats.producedByReason();
        Map<String, Long> consumedBy = stats.consumedByReason();
        long refund = amountOf(producedBy, CoinReason.REFUND.key());
        long produced = Math.max(0, producedRaw - refund);

        List<Row> produceRows = flowRows(producedBy, CoinReason.REFUND.key());
        List<Row> consumeRows = flowRows(consumedBy, null);

        List<String> lines = new ArrayList<>(16);

        lines.add("<gray>经济体检 <dark_gray>(累计流水 · 不含退款)</dark_gray>");
        lines.add("<gray>├ 剑币总量: <white>" + group(coinTotal) + "</white>"
                + "<dark_gray> · </dark_gray><gray>人均 <white>" + group(perCapita) + "</white>"
                + "<dark_gray> · </dark_gray><gray>中位 <white>" + group(Math.round(median)) + "</white>");
        lines.add("<gray>├ 基尼系数: " + giniLine(gini, giniWarn, holderCount)
                + (holderCount < players
                        ? " <dark_gray>(按 " + holderCount + " 名持币玩家算，"
                          + (players - holderCount) + " 人零余额已排除)</dark_gray>"
                        : ""));
        lines.add("<gray>├ 累计产出: <white>" + group(produced) + "</white>"
                + "<dark_gray> · </dark_gray><gray>累计消耗: <white>" + group(consumed) + "</white>");
        lines.add("<gray>├ 净通胀: <white>" + signed(net(produced, consumed)) + "</white>"
                + netRatio(produced, consumed));

        appendFlowBlock(lines, "产出构成", topN, produceRows, produced);
        appendFlowBlock(lines, "消耗构成", topN, consumeRows, consumed);

        if (refund > 0) {
            lines.add("<dark_gray>退款回滚 <white>" + group(refund)
                    + "</white> 已从上面的产出总额与产出构成里剔掉（钱只是回退，不是新放的水）</dark_gray>");
        }
        lines.add("<dark_gray>口径：流水是生命周期累计；metrics.history-days = " + historyDays
                + " 天只留给后续趋势切片</dark_gray>");

        return String.join(NL, lines);
    }

    // ------------------------------------------------------------ 数据取用

    /** stats 可能为 null（启用时序 / 自检环境），取不到就返回 null 让上层降级 */
    private StatsStore stats() {
        if (plugin == null) {
            return null;
        }
        try {
            return plugin.stats();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private int settingsInt(String path, int def) {
        try {
            return plugin == null ? def : plugin.settings().intOf(path, def);
        } catch (RuntimeException ex) {
            return def;
        }
    }

    private double settingsNum(String path, double def) {
        try {
            return plugin == null ? def : plugin.settings().num(path, def);
        } catch (RuntimeException ex) {
            return def;
        }
    }

    /**
     * 基尼系数那一行。
     *
     * <p>为什么单独拎出来：基尼系数在小样本上毫无意义，但会响亮地误报。
     * 实测一个刚开服、33 个档案里只有 1 个人有钱的存档，算出来是 0.97 ——
     * 数字本身没错（数学上就是极不均），可「偏高」这个警告天天挂着，
     * 服主就会把唯一的告警当成背景噪音，等真不均有 0.7 时反而不看了。</p>
     *
     * <p>所以样本少于 {@link #GINI_MIN_PLAYERS} 人时直接显示「样本太少」并且
     * 不给结论——诚实地说「现在算不准」，好过给一个会被忽略的红字。</p>
     */
    private static String giniLine(double gini, double warn, int players) {
        if (players < GINI_MIN_PLAYERS) {
            return "<dark_gray>样本太少（" + players + "/" + GINI_MIN_PLAYERS
                    + " 人），暂不下结论</dark_gray>";
        }
        return "<white>" + Msg.fmt(gini, 2) + "</white> "
                + (gini > warn ? "<red>偏高</red>" : "<green>健康</green>");
    }

    /** 余额升序副本（中位 / 基尼都要求有序）；顺手把 null 记录与负余额挡掉 */
    private static List<Long> sortedBalances(StatsStore stats) {
        List<Long> raw;
        try {
            raw = stats.coinBalances();
        } catch (RuntimeException ex) {
            raw = null;
        }
        List<Long> out = new ArrayList<>(raw == null ? 0 : raw.size());
        if (raw != null) {
            for (Long v : raw) {
                out.add(v == null ? 0L : Math.max(0L, v));
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    private static long sum(List<Long> values) {
        long s = 0;
        for (Long v : values) {
            if (v == null || v <= 0) {
                continue;
            }
            // 余额都是「剑币个位数」，现实量级远不到 long 上限；真溢出也不该让报表抛异常
            if (Long.MAX_VALUE - s < v) {
                return Long.MAX_VALUE;
            }
            s += v;
        }
        return s;
    }

    private static long amountOf(Map<String, Long> by, String key) {
        if (by == null || key == null) {
            return 0;
        }
        for (Map.Entry<String, Long> e : by.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key) && e.getValue() != null) {
                return Math.max(0, e.getValue());
            }
        }
        return 0;
    }

    /**
     * 把「理由键 → 金额」翻成按金额降序的行。
     *
     * <p>标签走 {@link CoinReason#label()}，但反查是自己遍历枚举做的，不用
     * {@link CoinReason#byKey}——那支会把认不出的键吞成「其它产出」，
     * 历史数据 / 新加桶就再也看不出是哪一路了。认不出就显示 key 原文。</p>
     */
    private static List<Row> flowRows(Map<String, Long> by, String skipKey) {
        List<Row> rows = new ArrayList<>();
        if (by == null) {
            return rows;
        }
        for (Map.Entry<String, Long> e : by.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey();
            if (skipKey != null && skipKey.equalsIgnoreCase(key)) {
                continue;
            }
            long amount = e.getValue() == null ? 0 : e.getValue();
            if (amount <= 0) {
                // 0 值桶占一行只是噪音（清账 / 迁移可能留下）
                continue;
            }
            rows.add(new Row(labelOf(key), amount));
        }
        rows.sort(Comparator.comparingLong(Row::amount).reversed()
                .thenComparing(Row::label)); // 同额时按名稳定排序，自检输出才不会抖
        return rows;
    }

    private static String labelOf(String key) {
        if (key == null || key.isBlank()) {
            return "未标记";
        }
        for (CoinReason r : CoinReason.values()) {
            if (r.key().equalsIgnoreCase(key)) {
                return r.label();
            }
        }
        return key;
    }

    // ------------------------------------------------------------ 排版

    private static void appendFlowBlock(List<String> lines, String title, int topN,
                                        List<Row> rows, long total) {
        if (rows.isEmpty()) {
            lines.add("<gray>" + title + " <dark_gray>（还没有流水）</dark_gray>");
            return;
        }
        int shown = Math.min(topN, rows.size());
        lines.add("<gray>" + title + " <dark_gray>(前 " + shown + ")</dark_gray>");
        for (int i = 0; i < shown; i++) {
            Row r = rows.get(i);
            lines.add("<gray>├ " + r.label() + "  <white>" + group(r.amount())
                    + "</white>  <dark_gray>" + pct(r.amount(), total) + "</dark_gray>");
        }
        if (rows.size() > shown) {
            long rest = 0;
            for (int i = shown; i < rows.size(); i++) {
                rest += rows.get(i).amount();
            }
            lines.add("<gray>├ 其它 " + (rows.size() - shown) + " 项  <white>" + group(rest)
                    + "</white>  <dark_gray>" + pct(rest, total) + "</dark_gray>");
        }
    }

    /** 百分比：分母 <= 0 一律 0.0%，绝不让 NaN / Infinity 漏到聊天栏 */
    private static String pct(long amount, long total) {
        if (total <= 0 || amount <= 0) {
            return "0.0%";
        }
        double v = amount * 100.0 / total;
        return Double.isFinite(v) ? Msg.fmt(v, 1) + "%" : "0.0%";
    }

    /** 净通胀。两边都已被钳到非负，减法不会绕回，直接用 */
    private static long net(long produced, long consumed) {
        return produced - consumed;
    }

    /** 净通胀的括号备注；消耗为 0 时没有比例基准，不能除 */
    private static String netRatio(long produced, long consumed) {
        if (consumed <= 0) {
            return produced > 0
                    ? " <dark_gray>(还没有消耗，算不出比例)</dark_gray>"
                    : "";
        }
        double p = net(produced, consumed) * 100.0 / consumed;
        if (!Double.isFinite(p)) {
            return "";
        }
        return " <dark_gray>(" + (p > 0 ? "+" : "") + Msg.fmt(p, 1) + "%)</dark_gray>";
    }

    private static String signed(long value) {
        return value > 0 ? "+" + group(value) : group(value);
    }

    /**
     * 千分位：用逗号按三位一组切。
     *
     * <p>不用 {@code String.format("%,d")}——它跟 JVM 默认 locale 走，
     * 德语环境会给出点号、印度 locale 的分组位数都不是三位，服主看到的数字就变形了。
     * 负数与 {@code Long.MIN_VALUE} 都在字符串层处理，不做取绝对值（那会溢出）。</p>
     */
    private static String group(long value) {
        String s = Long.toString(value);
        String sign = "";
        if (s.startsWith("-")) {
            sign = "-";
            s = s.substring(1);
        }
        StringBuilder sb = new StringBuilder(s.length() + s.length() / 3 + 1);
        int head = s.length() % 3;
        if (head == 0) {
            head = 3;
        }
        sb.append(s, 0, head);
        for (int i = head; i < s.length(); i += 3) {
            sb.append(',').append(s, i, i + 3);
        }
        return sign + sb;
    }

    // ------------------------------------------------------------ 统计量

    /**
     * 中位数：偶数个取中间两个的平均。
     *
     * <p>两个中间值相加就可能是奇数，所以用 double 算平均——取整会把那半个剑币抹掉；
     * 展示时再四舍五入（剑币本身没有小数单位）。</p>
     */
    private static double median(List<Long> asc) {
        int n = asc == null ? 0 : asc.size();
        if (n <= 0) {
            return 0.0;
        }
        if ((n & 1) == 1) {
            return asc.get(n / 2);
        }
        return (asc.get(n / 2 - 1) + (double) asc.get(n / 2)) / 2.0;
    }

    /**
     * 基尼系数：{@code G = 2·Σ(i·x_i) / (n·Σx) - (n+1)/n}，x 升序、i 从 1 开始。
     *
     * <p>0 = 人人一样多，越接近 1 越集中。总和为 0（新服 / 全清零）或只剩一个人时
     * 返回 0：这两种情况算出来没有经济含义，报「健康」比报 NaN 强。</p>
     */
    private static double gini(List<Long> asc, long total) {
        int n = asc == null ? 0 : asc.size();
        if (n <= 1 || total <= 0) {
            return 0.0;
        }
        double weighted = 0;
        for (int i = 0; i < n; i++) {
            weighted += (i + 1) * (double) asc.get(i);
        }
        double g = (2.0 * weighted) / ((double) n * (double) total) - (double) (n + 1) / n;
        if (!Double.isFinite(g)) {
            return 0.0;
        }
        // 浮点误差可能给出 -1e-17 这种；夹到 [0,1] 免得报表里出现 -0.00
        return Math.max(0.0, Math.min(1.0, g));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}