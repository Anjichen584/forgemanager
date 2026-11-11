package com.forgemaster.metrics;

import com.forgemaster.util.Msg;

import java.util.List;

/**
 * 看板用到的纯数学 / 纯排版工具（v2.23 · 切片 2/3）。
 *
 * <p>为什么单独拎出来：这些函数既被聊天栏报表用，又被三页 GUI、趋势块、自检用。
 * 留在 {@code MetricsReporter} 里是 private，GUI 只能复制一份公式——两份除法迟早分叉，
 * 那种分叉不会报错，只会让「报表说 0.42、看板说 0.51」。所以搬到这里，全部 public static、
 * 全部不碰 Bukkit、全部对空输入有定义。</p>
 *
 * <p><b>除零铁律</b>（任务书硬约束 3）：0 玩家 / 0 产出 / 0 分母时人均、基尼、占比、比率
 * 一律返回 0，绝不漏 NaN 或 Infinity 出去。这类值一旦进了 MiniMessage 就是
 * 「人均 NaN 剑币」，服主会当成插件坏了。</p>
 */
public final class MetricsMath {

    private MetricsMath() {
    }

    // ------------------------------------------------------------ 基本聚合

    /**
     * 求和（升序余额、持有者列表都用它）。
     *
     * <p>对 null 元素与负值直接跳过；溢出时返回 {@link Long#MAX_VALUE} 而不是绕回成负数——
     * 余额量级远到不了 long 上限，但真到了也不该让报表抛异常或显示负数。</p>
     */
    public static long sum(List<Long> values) {
        long s = 0;
        if (values == null) {
            return 0;
        }
        for (Long v : values) {
            if (v == null || v <= 0) {
                continue;
            }
            s = add(s, v);
        }
        return s;
    }

    /** 饱和加法：越界就停在 {@link Long#MAX_VALUE}，绝不绕回 */
    public static long add(long a, long b) {
        if (b > 0 && Long.MAX_VALUE - a < b) {
            return Long.MAX_VALUE;
        }
        if (b < 0 && Long.MIN_VALUE - a > b) {
            return Long.MIN_VALUE;
        }
        return a + b;
    }

    /**
     * 人均：分母 <= 0 一律 0。
     *
     * <p>玩家数为 0 的空服是新服常态，不是异常——报表要能安安静静地显示 0。</p>
     */
    public static long perCapita(long total, int players) {
        if (players <= 0 || total <= 0) {
            return 0;
        }
        return Math.round((double) total / players);
    }

    /** 平均值（double 口径）；count <= 0 返回 0 */
    public static double average(long total, long count) {
        if (count <= 0 || total <= 0) {
            return 0.0;
        }
        double v = (double) total / count;
        return Double.isFinite(v) ? v : 0.0;
    }

    /**
     * 中位数：偶数个取中间两个的平均。
     *
     * <p>两个中间值相加就可能是奇数，所以用 double 算平均——取整会把那半个剑币抹掉；
     * 展示时再四舍五入（剑币本身没有小数单位）。要求入参已升序。</p>
     */
    public static double median(List<Long> asc) {
        int n = asc == null ? 0 : asc.size();
        if (n <= 0) {
            return 0.0;
        }
        if ((n & 1) == 1) {
            return asc.get(n / 2);
        }
        Long a = asc.get(n / 2 - 1);
        Long b = asc.get(n / 2);
        return ((a == null ? 0 : a) + (double) (b == null ? 0 : b)) / 2.0;
    }

    /**
     * 基尼系数：{@code G = 2·Σ(i·x_i) / (n·Σx) - (n+1)/n}，x 升序、i 从 1 开始。
     *
     * <p>0 = 人人一样多，越接近 1 越集中。总和为 0（新服 / 全清零）或只剩一个人时
     * 返回 0：这两种情况算出来没有经济含义，报「健康」比报 NaN 强。</p>
     */
    public static double gini(List<Long> asc, long total) {
        int n = asc == null ? 0 : asc.size();
        if (n <= 1 || total <= 0) {
            return 0.0;
        }
        double weighted = 0;
        for (int i = 0; i < n; i++) {
            Long v = asc.get(i);
            weighted += (i + 1) * (double) (v == null ? 0L : v);
        }
        double g = (2.0 * weighted) / ((double) n * (double) total) - (double) (n + 1) / n;
        if (!Double.isFinite(g)) {
            return 0.0;
        }
        // 浮点误差可能给出 -1e-17 这种；夹到 [0,1] 免得报表里出现 -0.00
        return Math.max(0.0, Math.min(1.0, g));
    }

    /** 便捷重载：自己求和 */
    public static double gini(List<Long> asc) {
        return gini(asc, sum(asc));
    }

    /**
     * Top N% 占比：把升序列表最上面那一撮的金额加起来除以总额。
     *
     * <p>与基尼互补：基尼说「整体多不均」，Top10% 说「那一撮人握了多少钱」。
     * 后者更直观，服主一眼能翻译成「10 个人握着 7 成币」。</p>
     *
     * @param fraction 0~1，例如 0.1 = 前 10%；越界自动钳进 (0,1]
     */
    public static double topShare(List<Long> asc, double fraction) {
        int n = asc == null ? 0 : asc.size();
        long total = sum(asc);
        if (n <= 0 || total <= 0) {
            return 0.0;
        }
        double f = Double.isFinite(fraction) ? Math.max(0.0, Math.min(1.0, fraction)) : 0.1;
        int take = (int) Math.ceil(n * f);
        // 至少取 1 个：前 10% 在 3 人服上也是「1 个人」，数学上 ceil 已经保证，这里兜个底
        take = Math.max(1, Math.min(n, take));
        long top = 0;
        for (int i = n - take; i < n; i++) {
            Long v = asc.get(i);
            if (v != null && v > 0) {
                top = add(top, v);
            }
        }
        double share = (double) top / (double) total;
        return Double.isFinite(share) ? Math.max(0.0, Math.min(1.0, share)) : 0.0;
    }

    /** 占比（part / whole，0~1）；分母 <= 0 返回 0 */
    public static double share(long part, long whole) {
        if (whole <= 0 || part <= 0) {
            return 0.0;
        }
        double v = (double) part / (double) whole;
        return Double.isFinite(v) ? Math.max(0.0, Math.min(1.0, v)) : 0.0;
    }

    /**
     * 增长率（相对基准）。
     *
     * <p>基准 <= 0 时返回 0 而不是「无穷大」：新服第一天产出从 0 涨到 800，
     * 说「+∞%」既不可读也不可比较。</p>
     */
    public static double growth(long now, long before) {
        if (before <= 0 || now <= 0) {
            return 0.0;
        }
        double v = ((double) now - (double) before) * 100.0 / (double) before;
        return Double.isFinite(v) ? v : 0.0;
    }

    // ------------------------------------------------------------ 钳制

    public static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static long clampLong(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 夹到 [0,1]；NaN / Infinity 返回 0 */
    public static double unit(double v) {
        if (!Double.isFinite(v)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** 夹到 [lo,hi]；NaN / Infinity 返回 def */
    public static double clampTo(double v, double lo, double hi, double def) {
        if (!Double.isFinite(v)) {
            return def;
        }
        return Math.max(lo, Math.min(hi, v));
    }

    /** 读配置出来的正数（分钟 / 天数这类）：NaN、0、负数全部退化默认值 */
    public static int positive(int v, int def) {
        return v <= 0 ? def : v;
    }

    // ------------------------------------------------------------ 排版

    /**
     * 千分位：用逗号按三位一组切。
     *
     * <p>不用 {@code String.format("%,d")}——它跟 JVM 默认 locale 走，
     * 德语环境会给出点号、印度 locale 的分组位数都不是三位，服主看到的数字就变形了。
     * 负数与 {@code Long.MIN_VALUE} 都在字符串层处理，不做取绝对值（那会溢出）。</p>
     */
    public static String group(long value) {
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

    /** 带符号的千分位（净通胀这类可正可负的量） */
    public static String signed(long value) {
        return value > 0 ? "+" + group(value) : group(value);
    }

    /** 百分比：分母 <= 0 一律 0.0%，绝不让 NaN / Infinity 漏到聊天栏 */
    public static String percent(long amount, long total, int decimals) {
        if (total <= 0 || amount <= 0) {
            return zeroPercent(decimals);
        }
        double v = amount * 100.0 / total;
        if (!Double.isFinite(v)) {
            return zeroPercent(decimals);
        }
        return Msg.fmt(v, decimals) + "%";
    }

    /** 0~1 的比率直接印成百分比 */
    public static String percentOf(double ratio, int decimals) {
        double r = unit(ratio);
        return Msg.fmt(r * 100.0, decimals) + "%";
    }

    private static String zeroPercent(int decimals) {
        int d = Math.max(0, Math.min(6, decimals));
        if (d == 0) {
            return "0%";
        }
        return "0." + "0".repeat(d) + "%";
    }

    /**
     * 把「天」渲染成块状条形（趋势图用）。
     *
     * @param ratio 0~1
     * @param len   格子数
     */
    public static String bar(double ratio, int len, String fill, String empty) {
        return Msg.barBlocks(unit(ratio), Math.max(1, len), fill, empty);
    }

    /** 这个值在 min~max 里的比例（min==max 时返回 0，避免除零） */
    public static double ratioInRange(long value, long min, long max) {
        if (max <= min) {
            return 0.0;
        }
        double v = ((double) value - min) / ((double) max - min);
        return unit(v);
    }
}