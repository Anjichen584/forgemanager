package com.forgemaster.metrics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条采样样本（{@code metrics-<yyyyMMdd>.yml} 里 {@code samples} 列表的一项）。
 *
 * <p>字段对齐 {@code audit/08-expansion-plan.md} 4.3 节：</p>
 * <pre>
 *   at        采样时刻（epoch 秒）
 *   online    采样时在线人数
 *   totalCoins 全服剑币总量（当时）
 *   produced   <b>当日</b>累计产出（不含退款，口径同文字报表）
 *   consumed   <b>当日</b>累计消耗
 *   forged     <b>当日</b>锻造总数
 *   breaks     <b>当日</b>极限锻造炸剑数
 *   modules    <b>当日</b>各消耗理由的金额增量（模块热度 = 谁在吸币）
 * </pre>
 *
 * <p>为什么是「当日累计」而不是「这一段增量」：当日累计允许任意丢样本——
 * 服务器重启、错过一次采样、手动删掉几行，最后一条仍然是当天的正确总量。
 * 若存「每段增量」，丢一条就永久少算一笔，趋势线会暗暗变小，谁也发现不了。</p>
 *
 * <p>为什么各字段都要钳非负：{@code produced == -3} 只可能来自重置计数或坏文件，
 * 但一旦进趋势图就会画出负的柱子。宁可显示 0。（正常路径由 {@link #ofDay} 保证不会负。）</p>
 */
public record MetricsSample(long at, int online, long totalCoins,
                            long produced, long consumed, long forged, long breaks,
                            Map<String, Long> modules) {

    /** 紧凑构造器：全部钳非负，模块表归一成不可变副本 */
    public MetricsSample {
        at = Math.max(0L, at);
        online = Math.max(0, online);
        totalCoins = Math.max(0L, totalCoins);
        produced = Math.max(0L, produced);
        consumed = Math.max(0L, consumed);
        forged = Math.max(0L, forged);
        breaks = Math.max(0L, breaks);
        modules = normalize(modules);
    }

    /** 净通胀：两边都已钳到非负，减法不会绕回 */
    public long net() {
        return produced - consumed;
    }

    /** 没有模块数据（首条样本 / 老文件） */
    public static Map<String, Long> noModules() {
        return Map.of();
    }

    /** 某个理由键的当日金额；没有返回 0 */
    public long module(String key) {
        if (key == null) {
            return 0;
        }
        Long v = modules.get(key);
        return v == null ? 0 : Math.max(0, v);
    }

    /**
     * 归一化模块表：丢 null 键、空键、null 值与非正值。
     *
     * <p>0 值桶占一行只是噪音（清账 / 迁移可能留下），而且会让 YAML 里堆满
     * {@code shop: 0}。返回 {@link Map#of()} 而不是空 LinkedHashMap，方便自检直接比对。</p>
     */
    private static Map<String, Long> normalize(Map<String, Long> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> out = new LinkedHashMap<>(raw.size());
        for (Map.Entry<String, Long> e : raw.entrySet()) {
            String k = e.getKey() == null ? "" : e.getKey().trim();
            if (k.isEmpty() || e.getValue() == null) {
                continue;
            }
            long v = e.getValue();
            if (v <= 0) {
                continue;
            }
            out.put(k, v);
        }
        return out.isEmpty() ? Map.of() : Collections.unmodifiableMap(out);
    }

    /**
     * 与基线相减，得出「当日累计」样本。
     *
     * <p>基线是当天第一条样本时的生命周期计数。相减全部 {@code Math.max(0, ...)}：
     * 服主中途执行过 {@code resetFlowCounters()}（自检 / 手动清账）会让当前值小于基线，
     * 那时当天增量只能停在 0，直到跨天重新立基线——宁可少算，不要画出负柱。</p>
     *
     * <p>退款不单列字段：它就是「本来不该动的钱」，与文字报表一致地剔出产出。</p>
     */
    public static MetricsSample ofDay(long at, MetricsData data, MetricsHistory.Base base) {
        if (data == null) {
            return new MetricsSample(at, 0, 0, 0, 0, 0, 0, Map.of());
        }
        long baseProduced = base == null ? 0 : base.producedRaw();
        long baseRefund = base == null ? 0 : base.refund();
        long baseConsumed = base == null ? 0 : base.consumed();
        long baseForged = base == null ? 0 : base.forged();
        long baseBreaks = base == null ? 0 : base.breaks();

        long producedDelta = Math.max(0, data.producedRaw - baseProduced);
        long refundDelta = Math.max(0, data.refund - baseRefund);
        long produced = Math.max(0, producedDelta - refundDelta);
        long consumed = Math.max(0, data.consumed - baseConsumed);
        long forged = Math.max(0, data.forged - baseForged);
        long breaks = Math.max(0, data.breaks - baseBreaks);

        Map<String, Long> modules = new LinkedHashMap<>();
        if (data.consumedBy != null && !data.consumedBy.isEmpty()) {
            for (Map.Entry<String, Long> e : data.consumedBy.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                long before = base == null ? 0 : base.module(e.getKey());
                long delta = Math.max(0, e.getValue() - before);
                if (delta > 0) {
                    modules.put(e.getKey(), delta);
                }
            }
        }
        return new MetricsSample(at, data.online, data.totalCoins, produced, consumed,
                forged, breaks, modules);
    }
}