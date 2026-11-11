package com.forgemaster.metrics;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 每日快照文件的读写（{@code plugins/ForgingMaster/metrics-<yyyyMMdd>.yml}）。
 *
 * <p>文件结构（对齐 {@code audit/08-expansion-plan.md} 4.3 节，另加 {@code baseline}）：</p>
 * <pre>
 * version: 1
 * date: "2026-11-11"
 * baseline:                 # 当日第一条样本时的【生命周期】计数，算当日增量的起点
 *   produced: 128400        # 含退款（原始累计）
 *   refund: 1200            # 其中退款回滚
 *   consumed: 96550
 *   forged: 812
 *   breaks: 40
 *   modules:
 *     shop: 300
 *     gem: 100
 * samples:
 *   - at: 1731300000
 *     online: 12
 *     totalCoins: 486201
 *     produced: 0           # 当日累计（不含退款）
 *     consumed: 0
 *     forged: 0
 *     breaks: 0
 *     modules: { }
 * </pre>
 *
 * <p>为什么要有 {@code baseline}：样本存的是「当日累计」，而 {@code StatsStore} 的流水计数是
 * <b>服务器生命周期</b>累计。没有起点就只能把生命周期的数当成当天数，第一天就会显示
 * 「今日产出 12 万」——那个数字其实攒了三个月。起点写在文件里而不是只放内存，
 * 是为了重启后当天已采的样本仍然对得上。</p>
 *
 * <p>本类全部方法都是纯文件 IO（无 Bukkit 依赖，除 {@code YamlConfiguration}），
 * 线程安全由调用方保证「同一文件只有一个写者」，管道由 {@link MetricsService} 的单飞锁兜住。</p>
 */
public final class MetricsHistory {

    public static final String PREFIX = "metrics-";
    public static final String SUFFIX = ".yml";

    /** 单日样本上限（按 1 分钟间隔也够 33 小时）；超了丢最老的，避免坏配置把文件养爆 */
    public static final int MAX_SAMPLES_PER_DAY = 2000;

    /** 当前文件结构版本；将来改字段时用来做兼容分支 */
    public static final int FORMAT_VERSION = 1;

    private static final DateTimeFormatter NAME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 一条带日期的样本（趋势 / 导出都需要知道它属于哪一天） */
    public record Dated(LocalDate day, MetricsSample sample) {
    }

    /**
     * 当日增量基线：生命周期累计计数在当天起点的值。
     *
     * <p>{@code producedRaw} 是含退款的原始产出，{@code refund} 单列——这样当日产出
     * 与文字报表同口径（剔除退款），但基线本身仍能对上 {@code StatsStore} 的原始计数。</p>
     */
    public record Base(long producedRaw, long refund, long consumed, long forged, long breaks,
                       Map<String, Long> modules) {

        public Base {
            producedRaw = Math.max(0L, producedRaw);
            refund = Math.max(0L, refund);
            consumed = Math.max(0L, consumed);
            forged = Math.max(0L, forged);
            breaks = Math.max(0L, breaks);
            modules = modules == null || modules.isEmpty()
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(modules));
        }

        /** 某理由键的基线金额；没有返回 0 */
        public long module(String key) {
            if (key == null) {
                return 0;
            }
            Long v = modules.get(key);
            return v == null ? 0 : Math.max(0, v);
        }

        /** 从一次采集结果立基线（当天第一条样本） */
        public static Base of(MetricsData data) {
            if (data == null) {
                return new Base(0, 0, 0, 0, 0, Map.of());
            }
            return new Base(data.producedRaw, data.refund, data.consumed,
                    data.forged, data.breaks, data.consumedBy);
        }
    }

    /** 一天的完整内容（{@code base} 可能为 null：文件刚建 / 老格式） */
    public record Day(Base base, List<MetricsSample> samples) {

        public Day {
            samples = samples == null ? List.of() : List.copyOf(samples);
        }

        public static Day empty() {
            return new Day(null, List.of());
        }

        public boolean isEmpty() {
            return samples.isEmpty();
        }
    }

    private final File dir;
    private final Logger log;

    /**
     * @param dir 数据目录（{@code plugin.getDataFolder()} 或自检的临时目录）
     * @param log 可为 null（自检环境）
     */
    public MetricsHistory(File dir, Logger log) {
        this.dir = dir;
        this.log = log;
    }

    public File dir() {
        return dir;
    }

    // ------------------------------------------------------------ 文件名

    /** 某天的数据文件 */
    public File fileOf(LocalDate day) {
        if (day == null) {
            return null;
        }
        return new File(dir, fileName(day));
    }

    /** {@code metrics-20261111.yml} */
    public static String fileName(LocalDate day) {
        return PREFIX + (day == null ? "00000000" : day.format(NAME_FMT)) + SUFFIX;
    }

    /** 反解文件名里的日期；不是本插件的日文件就返回 null */
    public static LocalDate parseName(String name) {
        if (name == null || !name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) {
            return null;
        }
        String mid = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
        if (mid.length() != 8) {
            return null;
        }
        try {
            return LocalDate.parse(mid, NAME_FMT);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    /** 目录里现有的日文件日期，<b>新到旧</b>（裁剪从尾部删，导出从头部取） */
    public List<LocalDate> days() {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            return List.of();
        }
        List<LocalDate> out = new ArrayList<>();
        for (File f : files) {
            LocalDate d = parseName(f.getName());
            if (d != null && f.isFile()) {
                out.add(d);
            }
        }
        out.sort(Comparator.reverseOrder());
        return out;
    }

    // ------------------------------------------------------------ 读

    /** 读某天的文件；不存在 / 坏文件都返回 {@link Day#empty()}（绝不抛） */
    public Day read(LocalDate day) {
        File file = fileOf(day);
        if (file == null || !file.isFile()) {
            return Day.empty();
        }
        YamlConfiguration yml;
        try {
            yml = YamlConfiguration.loadConfiguration(file);
        } catch (RuntimeException ex) {
            warn("读 " + file.getName() + " 失败（按空文件处理）: " + ex.getMessage());
            return Day.empty();
        }
        Base base = readBase(yml.getConfigurationSection("baseline"));
        List<MetricsSample> samples = readSamples(yml);
        return new Day(base, samples);
    }

    private static Base readBase(ConfigurationSection sec) {
        if (sec == null) {
            return null;
        }
        Map<String, Long> modules = new LinkedHashMap<>();
        ConfigurationSection mods = sec.getConfigurationSection("modules");
        if (mods != null) {
            for (String key : mods.getKeys(false)) {
                long v = Math.max(0L, mods.getLong(key, 0L));
                if (v > 0) {
                    modules.put(key, v);
                }
            }
        }
        return new Base(sec.getLong("produced", 0L), sec.getLong("refund", 0L),
                sec.getLong("consumed", 0L), sec.getLong("forged", 0L),
                sec.getLong("breaks", 0L), modules);
    }

    /**
     * 读 {@code samples} 列表。
     *
     * <p>用 {@code getMapList} 而不是逐条 {@code samples.0.at}：前者天然支持「中间坏一条
     * 只丢一条」，后者一旦某条缺字段就会写出「samples.3」这种带洞的键，读回来还得自己补洞。
     * 数字统一走 {@link #asLong} 收口——YAML 把 100 读成 Integer、把 1e10 读成 Long 或 Double，
     * 直接强转会在某个数字跨过 Integer.MAX 的那天炸掉。</p>
     */
    private List<MetricsSample> readSamples(YamlConfiguration yml) {
        List<Map<?, ?>> raw = yml.getMapList("samples");
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<MetricsSample> out = new ArrayList<>(raw.size());
        for (Map<?, ?> map : raw) {
            if (map == null) {
                continue;
            }
            try {
                out.add(new MetricsSample(
                        asLong(map.get("at")),
                        (int) asLong(map.get("online")),
                        asLong(map.get("totalCoins")),
                        asLong(map.get("produced")),
                        asLong(map.get("consumed")),
                        asLong(map.get("forged")),
                        asLong(map.get("breaks")),
                        asModules(map.get("modules"))));
            } catch (RuntimeException ignored) {
                // 单条坏样本不该让整天的趋势消失
            }
        }
        out.sort(Comparator.comparingLong(MetricsSample::at));
        return out;
    }

    private static Map<String, Long> asModules(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            String key = String.valueOf(e.getKey());
            long v = asLong(e.getValue());
            if (!key.isBlank() && v > 0) {
                out.put(key, v);
            }
        }
        return out;
    }

    /**
     * 宽松取 long：接受 Integer / Long / Double / String / Number。
     *
     * <p>非数字、NaN、超范围一律 0——坏文件降级成 0 比抛异常好：看板少一个点，
     * 服主还能看到别的；抛异常则整天的趋势全没了。</p>
     */
    static long asLong(Object raw) {
        if (raw instanceof Number n) {
            double v = n.doubleValue();
            if (!Double.isFinite(v)) {
                return 0L;
            }
            if (v >= (double) Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
            if (v <= (double) Long.MIN_VALUE) {
                return Long.MIN_VALUE;
            }
            return n.longValue();
        }
        if (raw instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ex) {
                return 0L;
            }
        }
        return 0L;
    }

    // ------------------------------------------------------------ 写

    /**
     * 整份重写某天（tmp + 原子替换）。
     *
     * @return 是否写成功
     */
    public boolean write(LocalDate day, Day content) {
        File file = fileOf(day);
        if (file == null) {
            return false;
        }
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("version", FORMAT_VERSION);
        if (day != null) {
            yml.set("date", day.toString());
        }
        Base base = content == null ? null : content.base();
        if (base != null) {
            yml.set("baseline.produced", base.producedRaw());
            yml.set("baseline.refund", base.refund());
            yml.set("baseline.consumed", base.consumed());
            yml.set("baseline.forged", base.forged());
            yml.set("baseline.breaks", base.breaks());
            for (Map.Entry<String, Long> e : base.modules().entrySet()) {
                yml.set("baseline.modules." + e.getKey(), e.getValue());
            }
        }
        List<Map<String, Object>> samples = new ArrayList<>();
        if (content != null) {
            for (MetricsSample s : content.samples()) {
                samples.add(toMap(s));
            }
        }
        yml.set("samples", samples);
        return MetricsIo.writeYamlAtomic(file, yml, log);
    }

    private static Map<String, Object> toMap(MetricsSample s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("at", s.at());
        map.put("online", s.online());
        map.put("totalCoins", s.totalCoins());
        map.put("produced", s.produced());
        map.put("consumed", s.consumed());
        map.put("forged", s.forged());
        map.put("breaks", s.breaks());
        map.put("modules", new LinkedHashMap<>(s.modules()));
        return map;
    }

    /**
     * 追加一条样本（读 → 合并 → 原子重写）。
     *
     * <p>基线只在「文件里还没有基线」时写入：已有基线就说明当天早就开张了，
     * 再改基线会把之前的样本全部算歪。</p>
     *
     * @param base   候选基线（{@code null} = 不提供）
     * @param sample 要追加的样本（{@code null} 直接忽略）
     * @return 写入后的样本条数；写失败返回 -1
     */
    public int append(LocalDate day, Base base, MetricsSample sample) {
        if (day == null || sample == null) {
            return -1;
        }
        Day existing = read(day);
        Base useBase = existing.base() != null ? existing.base() : base;
        List<MetricsSample> samples = new ArrayList<>(existing.samples());
        samples.add(sample);
        // 条数超上限：丢最老的。按 at 排序保证「最老」就是时间上最早的，而不是先写进来的
        samples.sort(Comparator.comparingLong(MetricsSample::at));
        if (samples.size() > MAX_SAMPLES_PER_DAY) {
            samples = new ArrayList<>(samples.subList(samples.size() - MAX_SAMPLES_PER_DAY, samples.size()));
        }
        return write(day, new Day(useBase, samples)) ? samples.size() : -1;
    }

    // ------------------------------------------------------------ 裁剪 / 汇总

    /**
     * 只保留最新的 {@code keepDays} 个日文件，删掉更老的。
     *
     * <p>删除顺序：从 {@link #days()}（新到旧）的第 {@code keepDays} 项往后删。
     * 删失败（文件被占用 / 权限）只记 warning 并继续——留一个老文件远好过让清理卡住。</p>
     *
     * @return 实际删掉的个数
     */
    public int prune(int keepDays) {
        int keep = Math.max(1, keepDays);
        List<LocalDate> all = days();
        int removed = 0;
        for (int i = keep; i < all.size(); i++) {
            File f = fileOf(all.get(i));
            if (f == null) {
                continue;
            }
            if (MetricsIo.deleteQuietly(f)) {
                removed++;
            } else if (f.isFile()) {
                warn("删不掉过期看板文件 " + f.getName() + "（权限或占用），下次再试");
            }
        }
        return removed;
    }

    /**
     * 读最近 {@code days} 天的全部样本，按时间升序。
     *
     * <p>读不全也照样返回已读到的部分：某天文件坏了，趋势应该「少一天」，
     * 而不是整块消失。</p>
     */
    public List<Dated> recent(int days) {
        int keep = Math.max(1, days);
        List<LocalDate> all = days();
        int take = Math.min(keep, all.size());
        List<Dated> out = new ArrayList<>();
        for (int i = 0; i < take; i++) {
            LocalDate day = all.get(i);
            for (MetricsSample s : read(day).samples()) {
                out.add(new Dated(day, s));
            }
        }
        out.sort(Comparator.comparingLong(d -> d.sample().at()));
        return out;
    }

    /** 最近一条样本（没有返回 null）；给「上次采样时间」这类展示用 */
    public Dated latest() {
        List<Dated> all = recent(1);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    private void warn(String msg) {
        if (log != null) {
            log.warning("[metrics] " + msg);
        }
    }
}