package com.forgemaster.metrics;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每日快照与趋势的运行时协调者（v2.23 · 切片 2）。
 *
 * <p>职责只有三件：<b>按钟采样 → 交给异步落盘 → 把内存里的样本借给报表 / GUI / 导出</b>。
 * 它不负责排版（那是 {@link MetricsReporter} 与 {@link MetricsGui}），
 * 也不负责文件格式（那是 {@link MetricsHistory}）。</p>
 *
 * <h2>线程模型（任务书硬约束 5 / 6）</h2>
 * <pre>
 *   异步定时器（runTaskTimerAsynchronously）
 *        └─ runTask            → 主线程：MetricsData.capture() 抄一份纯数据，塞进 last
 *              └─ runTaskAsynchronously → 异步：算当日增量 + 写文件 + 裁剪旧文件 + 刷缓存
 * </pre>
 * <p>为什么定时器是异步、取数却要跳回主线程：{@code getOnlinePlayers()} 与
 * {@code StatsStore} 的档案表都只在主线程安全。异步定时器负责「计时」这件事
 * （到点必响、不受主线程卡顿影响），真正碰 Bukkit 的那一瞬跳回主线程，
 * 之后的计算与磁盘 IO 全部回到异步线程——主线程上只剩一次内存遍历，
 * 200 个档案量级是微秒级。</p>
 *
 * <h2>为什么样本缓存要在内存里</h2>
 * <p>GUI 打开必须瞬时（点击到界面出现之间不能有磁盘 IO）。所以近 N 天的样本
 * 在启动时异步读进 {@link #cache}，之后每次采样在尾部追加一条——一份不可变列表，
 * 靠 volatile 整体替换做到无锁读。GUI / 命令线程只读这份副本。</p>
 */
public final class MetricsService {

    /** 采样间隔上限：一天。再长就没有「趋势」可言了 */
    public static final int MAX_INTERVAL_MINUTES = 24 * 60;
    /** 历史保留上限：一年。再多也该导出 CSV 归档，不该留在插件目录里 */
    public static final int MAX_HISTORY_DAYS = 365;

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 一天的汇总（趋势图 / 文字趋势 / CSV 都从这里取）。
     *
     * <p>字段取当天<b>最后一条</b>样本的值——样本本身是当日累计，所以最后一条就是当天总量。</p>
     */
    public record DayTotal(LocalDate day, long produced, long consumed, long forged,
                           int samples, double avgOnline) {

        public long net() {
            return produced - consumed;
        }
    }

    private final ForgingMasterPlugin plugin;
    private final MetricsHistory history;
    private final AtomicBoolean writing = new AtomicBoolean();

    /** 近 N 天样本（不可变，整体替换；读线程不需要加锁） */
    private volatile List<MetricsHistory.Dated> cache = List.of();
    /** 最近一次主线程取数（GUI 直接用，避免每次开界面再遍历一遍档案） */
    private volatile MetricsData last = null;
    /** 已做过裁剪的日期：一天只扫一次目录 */
    private volatile LocalDate lastPruneDay = null;

    private int taskId = -1;

    public MetricsService(ForgingMasterPlugin plugin) {
        this(plugin, null);
    }

    /**
     * @param history 自检时注入临时目录的历史存储；生产传 {@code null} 用插件数据目录
     */
    public MetricsService(ForgingMasterPlugin plugin, MetricsHistory history) {
        this.plugin = plugin;
        this.history = history != null ? history
                : new MetricsHistory(plugin.getDataFolder(), plugin.getLogger());
    }

    // ------------------------------------------------------------ 生命周期

    /** 启用 / 重载时调用：按配置排采样定时器并异步载入已有样本 */
    public void start() {
        stop();
        if (!enabled()) {
            info("数据看板已关闭（config.yml → metrics.enabled），不采样");
            return;
        }
        int minutes = intervalMinutes();
        long period = 20L * 60L * minutes;
        try {
            BukkitTask task = Bukkit.getScheduler().runTaskTimerAsynchronously(
                    plugin, this::tickAsync, period, period);
            taskId = task.getTaskId();
        } catch (RuntimeException ex) {
            warn("启动采样定时器失败: " + ex.getMessage());
            return;
        }
        // 首条样本等到一个间隔之后：刚启动时流水计数是 0，此刻采样只会往当天文件里
        // 塞一条全零样本，把「当日产出」的起点画成 0（其实只是还没开张）
        refreshCacheAsync();
        pruneAsync(false);
        info("数据看板已启动：每 " + minutes + " 分钟一条样本，保留 " + historyDays() + " 天");
    }

    /** 关服 / 重载时调用：只停定时器，不写文件（关服时的磁盘 IO 让给各存档，见报告说明） */
    public void stop() {
        int id = taskId;
        taskId = -1;
        if (id == -1) {
            return;
        }
        try {
            Bukkit.getScheduler().cancelTask(id);
        } catch (RuntimeException ex) {
            warn("停止采样定时器失败: " + ex.getMessage());
        }
    }

    /** reloadAll：停下再按新配置重排 */
    public void reload() {
        stop();
        start();
    }

    public boolean running() {
        return taskId != -1;
    }

    public MetricsHistory history() {
        return history;
    }

    /** 最近一次取数（可能为 null：还没采过） */
    public MetricsData lastData() {
        return last;
    }

    // ------------------------------------------------------------ 配置（全部带钳制）

    public boolean enabled() {
        try {
            return plugin.settings().flag("metrics.enabled", true);
        } catch (RuntimeException ex) {
            return true;
        }
    }

    /** 采样间隔（分钟）：非法值 / 0 / 负数一律退回 30，上限一天 */
    public int intervalMinutes() {
        int raw = 30;
        try {
            raw = plugin.settings().intOf("metrics.snapshot-interval-minutes", 30);
        } catch (RuntimeException ignored) {
            raw = 30;
        }
        return MetricsMath.clamp(MetricsMath.positive(raw, 30), 1, MAX_INTERVAL_MINUTES);
    }

    /** 历史保留天数：非法值退回 30，上限一年 */
    public int historyDays() {
        int raw = 30;
        try {
            raw = plugin.settings().intOf("metrics.history-days", 30);
        } catch (RuntimeException ignored) {
            raw = 30;
        }
        return MetricsMath.clamp(MetricsMath.positive(raw, 30), 1, MAX_HISTORY_DAYS);
    }

    /** 基尼告警阈值：NaN / 越界退回 0.55，并夹到 [0,1] */
    public double giniWarn() {
        double raw = 0.55;
        try {
            raw = plugin.settings().num("metrics.gini-warn", 0.55);
        } catch (RuntimeException ignored) {
            raw = 0.55;
        }
        return MetricsMath.clampTo(raw, 0.0, 1.0, 0.55);
    }

    /** 报表 / GUI 每类理由显示前几条：1~50 */
    public int topN() {
        int raw = 10;
        try {
            raw = plugin.settings().intOf("metrics.top-n", 10);
        } catch (RuntimeException ignored) {
            raw = 10;
        }
        return MetricsMath.clamp(MetricsMath.positive(raw, 10), 1, 50);
    }

    // ------------------------------------------------------------ 采样

    /**
     * 定时器回调（<b>异步线程</b>）：跳回主线程取数，再跳回异步落盘。
     *
     * <p>两侧都包 try/catch：定时任务里漏一个异常，Bukkit 会把这个任务<b>永久注销</b>，
     * 之后看板就再也不会采样了，而且只留一条堆栈。</p>
     */
    private void tickAsync() {
        if (!enabled()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, this::captureOnMain);
        } catch (RuntimeException ex) {
            warn("采样调度失败（本轮跳过）: " + ex.getMessage());
        }
    }

    /** 主线程：抄一份纯数据，再排异步落盘 */
    private void captureOnMain() {
        MetricsData data;
        try {
            data = MetricsData.capture(plugin);
        } catch (RuntimeException ex) {
            warn("采集快照失败（本轮跳过）: " + ex.getMessage());
            return;
        }
        last = data;
        if (!enabled()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> persist(data));
        } catch (RuntimeException ex) {
            warn("写入调度失败（本轮跳过）: " + ex.getMessage());
        }
    }

    /**
     * 立即采一条（命令 / 自检 / 手动补一条用）。
     *
     * <p>主线程调用会当场取数再异步落盘；异步调用则先跳回主线程。</p>
     *
     * @return 是否已排队（关掉看板 / 调度失败返回 false）
     */
    public boolean sampleNow() {
        if (!enabled()) {
            return false;
        }
        if (Bukkit.isPrimaryThread()) {
            captureOnMain();
            return true;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, this::captureOnMain);
            return true;
        } catch (RuntimeException ex) {
            warn("采样调度失败: " + ex.getMessage());
            return false;
        }
    }

    /**
     * 异步：算当日增量 → 追加样本 → 裁剪旧文件 → 刷内存缓存。
     *
     * <p>单飞（{@link #writing}）：上一轮还在写就跳过本轮。采样间隔最短 1 分钟，
     * 而一次写盘正常情况下是毫秒级；真撞上了说明磁盘出了状况，
     * 那时候排队只会把 IO 队列越堆越长，丢一条样本反而是对的。</p>
     */
    private void persist(MetricsData data) {
        if (!writing.compareAndSet(false, true)) {
            return;
        }
        try {
            LocalDate today = LocalDate.now();
            MetricsHistory.Day existing = history.read(today);
            MetricsHistory.Base base = existing.base() != null
                    ? existing.base()
                    : MetricsHistory.Base.of(data);
            MetricsSample sample = MetricsSample.ofDay(
                    System.currentTimeMillis() / 1000L, data, base);
            int written = history.append(today, base, sample);
            if (written < 0) {
                warn("写入 " + MetricsHistory.fileName(today) + " 失败，样本未落盘");
                return;
            }
            pruneAsync(true);
            cache = mergeCache(cache, new MetricsHistory.Dated(today, sample), historyDays());
        } catch (RuntimeException ex) {
            warn("写入每日快照失败: " + ex.getMessage());
        } finally {
            writing.set(false);
        }
    }

    /**
     * 把新样本并进内存缓存：去掉同一天同 `at` 的旧条目、按时间排序、丢掉超窗口的日期。
     *
     * <p>纯内存操作（不读盘）：采样每 30 分钟一次，为了加一条样本去重读 30 个文件是浪费。</p>
     */
    private static List<MetricsHistory.Dated> mergeCache(List<MetricsHistory.Dated> old,
                                                         MetricsHistory.Dated add, int days) {
        List<MetricsHistory.Dated> out = new ArrayList<>(old == null ? List.of() : old);
        out.removeIf(d -> d != null && add != null
                && d.sample() != null && d.sample().at() == add.sample().at());
        if (add != null) {
            out.add(add);
        }
        LocalDate from = LocalDate.now().minusDays(Math.max(1, days) - 1L);
        out.removeIf(d -> d == null || d.day() == null || d.day().isBefore(from));
        out.sort((a, b) -> Long.compare(a.sample().at(), b.sample().at()));
        return List.copyOf(out);
    }

    /** 异步重读全部日文件（启动 / 重载 / 手动刷新缓存） */
    public void refreshCacheAsync() {
        final int days = historyDays();
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    cache = List.copyOf(history.recent(days));
                } catch (RuntimeException ex) {
                    warn("读取历史样本失败: " + ex.getMessage());
                }
            });
        } catch (RuntimeException ex) {
            warn("读取历史样本调度失败: " + ex.getMessage());
        }
    }

    /**
     * 异步裁剪旧文件。
     *
     * @param onlyOncePerDay true = 一天只扫一次目录（采样路径用）；false = 立刻扫（启动用）
     */
    private void pruneAsync(boolean onlyOncePerDay) {
        LocalDate today = LocalDate.now();
        if (onlyOncePerDay && today.equals(lastPruneDay)) {
            return;
        }
        lastPruneDay = today;
        final int keep = historyDays();
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    int removed = history.prune(keep);
                    if (removed > 0) {
                        info("裁剪过期看板文件 " + removed + " 个（保留 " + keep + " 天）");
                        cache = List.copyOf(history.recent(keep));
                    }
                } catch (RuntimeException ex) {
                    warn("裁剪过期看板文件失败: " + ex.getMessage());
                }
            });
        } catch (RuntimeException ex) {
            warn("裁剪调度失败: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ 汇总（只读内存）

    /** 内存里的近 N 天样本（不可变副本，可安全遍历） */
    public List<MetricsHistory.Dated> cached() {
        return cache;
    }

    public int sampleCount() {
        return cache.size();
    }

    /**
     * 按天汇总最近 {@code days} 天（升序，老 → 新）。
     *
     * <p>某天有多个样本时取<b>最后一条</b>：样本是当日累计量，最后一条即当天总量。
     * 用「求和」会把累计量越加越大——那是最容易犯又最难发现的口径错误。</p>
     */
    public List<DayTotal> dayTotals(int days) {
        int keep = MetricsMath.clamp(MetricsMath.positive(days, historyDays()), 1, MAX_HISTORY_DAYS);
        LocalDate from = LocalDate.now().minusDays(keep - 1L);
        Map<LocalDate, List<MetricsSample>> byDay = new LinkedHashMap<>();
        for (MetricsHistory.Dated d : cache) {
            if (d == null || d.day() == null || d.sample() == null || d.day().isBefore(from)) {
                continue;
            }
            byDay.computeIfAbsent(d.day(), k -> new ArrayList<>()).add(d.sample());
        }
        List<DayTotal> out = new ArrayList<>(byDay.size());
        for (Map.Entry<LocalDate, List<MetricsSample>> e : byDay.entrySet()) {
            List<MetricsSample> list = new ArrayList<>(e.getValue());
            list.sort((a, b) -> Long.compare(a.at(), b.at()));
            MetricsSample lastOne = list.get(list.size() - 1);
            long onlineSum = 0;
            for (MetricsSample s : list) {
                onlineSum += Math.max(0, s.online());
            }
            out.add(new DayTotal(e.getKey(), lastOne.produced(), lastOne.consumed(),
                    lastOne.forged(), list.size(),
                    MetricsMath.average(onlineSum, list.size())));
        }
        out.sort((a, b) -> a.day().compareTo(b.day()));
        return out;
    }

    /** 近 N 天产出合计 */
    public long totalProduced(int days) {
        long sum = 0;
        for (DayTotal d : dayTotals(days)) {
            sum = MetricsMath.add(sum, d.produced());
        }
        return sum;
    }

    /** 近 N 天消耗合计 */
    public long totalConsumed(int days) {
        long sum = 0;
        for (DayTotal d : dayTotals(days)) {
            sum = MetricsMath.add(sum, d.consumed());
        }
        return sum;
    }

    /** 近 N 天净通胀 */
    public long totalNet(int days) {
        return totalProduced(days) - totalConsumed(days);
    }

    /** 最近一条样本的时刻（毫秒），0 = 还没有样本 */
    public long lastSampleAtMillis() {
        long at = 0;
        for (MetricsHistory.Dated d : cache) {
            if (d != null && d.sample() != null && d.sample().at() > at) {
                at = d.sample().at();
            }
        }
        return at * 1000L;
    }

    // ------------------------------------------------------------ 文字趋势（命令用）

    /**
     * 趋势块：多行 MiniMessage，行间用 {@code <newline>} 分隔（与
     * {@link MetricsReporter#text()} 同一约定，调用方 split 后逐行发送）。
     *
     * <p>纯只读：只读内存缓存与配置。样本为空时给一句「还没采到样本」而不是空字符串——
     * 空字符串会让命令看起来「执行了但什么都没发生」。</p>
     */
    public String trendText() {
        int days = historyDays();
        List<DayTotal> totals = dayTotals(days);
        if (totals.isEmpty()) {
            return "<gray>趋势 <dark_gray>· </dark_gray><dark_gray>还没有样本（每 "
                    + intervalMinutes() + " 分钟采一条，保留 " + days + " 天）</dark_gray>";
        }
        long produced = totalProduced(days);
        long consumed = totalConsumed(days);
        long net = produced - consumed;
        int dayCount = totals.size();
        String lastDay = DAY_FMT.format(totals.get(dayCount - 1).day());

        List<String> lines = new ArrayList<>(8);
        lines.add("<gray>趋势 <dark_gray>(近 " + days + " 天 · " + dayCount + " 天有数据 · "
                + sampleCount() + " 条样本)</dark_gray>");
        lines.add("<gray>├ 产出 <white>" + MetricsMath.group(produced) + "</white>"
                + "<dark_gray> · </dark_gray><gray>消耗 <white>" + MetricsMath.group(consumed)
                + "</white><dark_gray> · </dark_gray><gray>净通胀 <white>"
                + MetricsMath.signed(net) + "</white>");
        lines.add("<gray>├ 日均 产出 <white>"
                + MetricsMath.group(Math.round(MetricsMath.average(produced, dayCount)))
                + "</white><dark_gray> · </dark_gray><gray>消耗 <white>"
                + MetricsMath.group(Math.round(MetricsMath.average(consumed, dayCount)))
                + "</white>");
        lines.add("<gray>├ 最近一天 <white>" + lastDay + "</white><dark_gray> · </dark_gray>"
                + "<gray>产出 <white>" + MetricsMath.group(totals.get(dayCount - 1).produced())
                + "</white><dark_gray> · </dark_gray><gray>消耗 <white>"
                + MetricsMath.group(totals.get(dayCount - 1).consumed()) + "</white>");
        lines.add("<gray>└ 保留 <white>" + days + "</white> 天 · 当前 <white>"
                + history().days().size() + "</white> 个日文件 <dark_gray>(超期自动删)</dark_gray>");
        return String.join("<newline>", lines);
    }

    // ------------------------------------------------------------ CSV 导出

    /**
     * 异步导出近 {@code days} 天样本为 CSV，完成后回主线程在聊天栏报路径与行数。
     *
     * <p>为什么先取内存快照再排任务：{@link #cache} 可能在导出过程中被采样线程替换，
     * 拿着旧引用遍历没问题（列表不可变），但如果中途再读一次就可能拿到不同版本的列表，
     * 出现「行数报 120、文件里 121 行」。</p>
     */
    public void exportAsync(CommandSender to, int days) {
        final int keep = MetricsMath.clamp(MetricsMath.positive(days, historyDays()), 1, MAX_HISTORY_DAYS);
        final LocalDate from = LocalDate.now().minusDays(keep - 1L);
        final List<MetricsHistory.Dated> ready = new ArrayList<>();
        for (MetricsHistory.Dated d : cache) {
            if (d != null && d.day() != null && !d.day().isBefore(from)) {
                ready.add(d);
            }
        }
        final String fileName = MetricsCsv.exportName(System.currentTimeMillis());
        final File dir = plugin.getDataFolder();

        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<MetricsHistory.Dated> rows = ready;
                if (rows.isEmpty()) {
                    // 缓存还没载入（刚启动就导出）：退化成读盘，异步线程读文件是安全的
                    try {
                        rows = history.recent(keep);
                    } catch (RuntimeException ex) {
                        rows = List.of();
                    }
                }
                int count = -1;
                String error = null;
                File out = new File(dir, fileName);
                try {
                    count = MetricsCsv.export(out, rows);
                } catch (Exception ex) {
                    error = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                }
                final int rowsWritten = count;
                final String failure = error;
                try {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            reportExport(to, out, rowsWritten, failure, keep));
                } catch (RuntimeException ignored) {
                    // 插件正在卸载：没人接收回执就算了，文件已经写好了
                }
            });
        } catch (RuntimeException ex) {
            Msg.prefix(to, "<red>导出调度失败: " + ex.getMessage() + "</red>");
        }
    }

    /** 导出回执（主线程） */
    private static void reportExport(CommandSender to, File out, int rows, String failure, int days) {
        if (to == null) {
            return;
        }
        if (failure != null) {
            Msg.prefix(to, "<red>导出失败：" + failure + "</red>");
            return;
        }
        if (rows <= 0) {
            Msg.prefix(to, "<yellow>近 " + days + " 天还没有样本，没有可导出的行。</yellow>");
            return;
        }
        Msg.prefix(to, "<green>已导出 <white>" + rows + "</white> 行（近 " + days + " 天）</green>");
        Msg.send(to, "<dark_gray>→ " + out.getAbsolutePath());
    }

    /** 时间戳 → 本地时间字符串（GUI 上「上次采样」用） */
    public static String timeOf(long epochMillis) {
        if (epochMillis <= 0) {
            return "—";
        }
        return DateTimeFormatter.ofPattern("MM-dd HH:mm")
                .format(java.time.Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    // ------------------------------------------------------------ 日志

    private void info(String msg) {
        if (plugin != null) {
            plugin.getLogger().info("[metrics] " + msg);
        }
    }

    private void warn(String msg) {
        Plugin p = plugin;
        if (p != null) {
            p.getLogger().warning("[metrics] " + msg);
        }
    }
}
