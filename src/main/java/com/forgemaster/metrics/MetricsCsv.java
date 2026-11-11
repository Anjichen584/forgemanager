package com.forgemaster.metrics;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 样本 → CSV（丢进 Excel / Numbers / pandas 就能画图）。
 *
 * <p>为什么手搓而不引库：任务书禁止第三方依赖，而 CSV 的「难」只在转义一件事上——
 * 字段里出现逗号、双引号、换行时必须整体加引号、内部双引号翻倍（RFC 4180）。
 * 三个 {@code if} 的事，引一个 3MB 的 commons-csv 不划算。</p>
 *
 * <p>两个工程细节：</p>
 * <ul>
 *   <li><b>BOM</b>：Excel 打开无 BOM 的 UTF-8 CSV 会把中文表头显示成乱码。
 *       这里写 {@code \uFEFF} 开头，Linux 上的 {@code head} / pandas 也不受影响。</li>
 *   <li><b>CRLF</b>：RFC 4180 要求 CRLF，Excel 对 LF 也认，但 Windows 记事本只认 CRLF。</li>
 * </ul>
 *
 * <p>模块表压成一列 {@code key=value; key=value}：CSV 的列是固定的，而模块键会随
 * {@code CoinReason} 扩值而增加；展开成动态列会让不同批次的导出文件列不一致，
 * 反而是表格软件的灾难。</p>
 */
public final class MetricsCsv {

    /** 表头（与 {@link #rowText} 的列一一对应） */
    public static final String HEADER =
            "日期,时刻,在线,剑币总量,当日产出,当日消耗,净通胀,当日锻造,当日炸剑,模块金额";

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private MetricsCsv() {
    }

    /**
     * 单个字段转义（RFC 4180）。
     *
     * <p>只在这三种情况下加引号：含逗号、含双引号、含 CR/LF。引号内部的双引号翻倍。
     * 前后空白不额外处理——不去 trim 数据，trim 掉的可能是真内容。</p>
     */
    public static String field(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        boolean needQuote = raw.indexOf(',') >= 0 || raw.indexOf('"') >= 0
                || raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0;
        if (!needQuote) {
            return raw;
        }
        return '"' + raw.replace("\"", "\"\"") + '"';
    }

    /** 数值列：转成字符串再走同一套转义（负数 / 大数都不特殊处理） */
    public static String num(long value) {
        return field(Long.toString(value));
    }

    /** 模块表 → {@code shop=41; gem=33}（按金额降序，稳定输出） */
    public static String modules(Map<String, Long> modules) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : MetricsData.ranked(modules)) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** 一条样本 → 一行（不含行尾换行） */
    public static String rowText(LocalDate day, MetricsSample s) {
        if (s == null) {
            return "";
        }
        LocalDate date = day;
        String time = "";
        if (s.at() > 0) {
            Instant instant = Instant.ofEpochSecond(s.at());
            if (date == null) {
                date = instant.atZone(ZoneId.systemDefault()).toLocalDate();
            }
            time = STAMP.format(instant.atZone(ZoneId.systemDefault()));
        }
        return String.join(",",
                field(date == null ? "" : date.toString()),
                field(time),
                num(s.online()),
                num(s.totalCoins()),
                num(s.produced()),
                num(s.consumed()),
                num(s.net()),
                num(s.forged()),
                num(s.breaks()),
                field(modules(s.modules())));
    }

    /** 完整 CSV 文本（表头 + 全部行，CRLF） */
    public static String build(List<MetricsHistory.Dated> rows) {
        StringBuilder sb = new StringBuilder(256 + (rows == null ? 0 : rows.size()) * 96);
        sb.append('\uFEFF').append(HEADER).append("\r\n");
        if (rows != null) {
            for (MetricsHistory.Dated d : rows) {
                if (d == null || d.sample() == null) {
                    continue;
                }
                sb.append(rowText(d.day(), d.sample())).append("\r\n");
            }
        }
        return sb.toString();
    }

    /**
     * 导出到文件（tmp + 原子替换）。
     *
     * @return 写出的数据行数（不含表头）
     * @throws IOException 写失败（调用方负责提示服主）
     */
    public static int export(File target, List<MetricsHistory.Dated> rows) throws IOException {
        if (target == null) {
            throw new IOException("导出路径为空");
        }
        int count = 0;
        if (rows != null) {
            for (MetricsHistory.Dated d : rows) {
                if (d != null && d.sample() != null) {
                    count++;
                }
            }
        }
        byte[] bytes = build(rows).getBytes(StandardCharsets.UTF_8);
        if (!MetricsIo.writeAtomic(target, bytes, null)) {
            throw new IOException("写文件失败: " + target.getName());
        }
        return count;
    }

    /** 导出文件名：{@code metrics-export-20261111-093000.csv} */
    public static String exportName(long epochMillis) {
        Instant instant = Instant.ofEpochMilli(epochMillis);
        return "metrics-export-" + FILE_STAMP.format(instant.atZone(ZoneId.systemDefault())) + ".csv";
    }
}