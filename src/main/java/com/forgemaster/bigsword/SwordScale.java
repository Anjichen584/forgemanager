package com.forgemaster.bigsword;

import java.util.ArrayList;
import java.util.List;

/**
 * 强化等级 → 剑体视觉倍数。**纯函数，不碰 Bukkit**，所以自检可以直接断言曲线。
 *
 * <p>服主的要求原话：「放大倍数直接加倍加倍再加倍，+99 我要他跟泰坦 mod 那种剑一样大」。</p>
 *
 * <h2>为什么是指数插值而不是线性</h2>
 *
 * <p>线性插值在视觉上是错的。人的眼睛对尺寸是**对数**感知的：把一个模型从 1 放大到 2
 * 是「明显大了一圈」，从 7 放大到 8 几乎看不出来。所以三个加倍点之间必须按几何级数过渡，
 * 每一段的手感才一致——这正是「加倍、加倍、再加倍」描述的东西。</p>
 *
 * <p>另一个效果是它天然压住了低段：+20 才刚起步（×1.1 左右），玩家不会在 +20 就看到
 * 一把比人还长的剑，强化前期的「成长感」还在。</p>
 *
 * <h2>里程碑的语义</h2>
 *
 * <p>{@code milestones: [33, 66, 99]} + {@code multiplier: 2.0} 意思是：
 * +33 时 ×2、+66 时 ×4、+99 时 ×8。第 i 个里程碑的倍数是 {@code multiplier^(i+1)}，
 * 再被 {@code max-scale} 夹住。</p>
 */
public final class SwordScale {

    private final List<Integer> milestones;
    private final List<Double> scales;
    private final double multiplier;
    private final double base;
    private final double max;
    private final int minLevel;

    /**
     * @param milestones 加倍点（强化等级）；会被排序去重，非法值（≤0、重复）丢掉
     * @param multiplier 每个加倍点在上一档基础上乘多少；≤1 视为无意义，整体退化成「不放大」
     * @param base       起点倍数（通常是 1.0）
     * @param max        硬上限，防止配置写错把模型放到穿模
     * @param minLevel   低于这个强化等级不参与放大（也不挂牌）
     */
    public SwordScale(List<Integer> milestones, double multiplier,
                      double base, double max, int minLevel) {
        this.multiplier = Double.isFinite(multiplier) && multiplier > 1.0 ? multiplier : 1.0;
        this.base = Double.isFinite(base) && base > 0 ? base : 1.0;
        this.max = Double.isFinite(max) && max >= this.base ? max : this.base;
        this.minLevel = Math.max(0, minLevel);

        // 排序去重：服主可能写成 [66, 33, 99] 或者手滑重复，这里全兜住
        List<Integer> ms = new ArrayList<>();
        if (milestones != null) {
            for (Integer m : milestones) {
                if (m != null && m > 0 && m > this.minLevel && !ms.contains(m)) {
                    ms.add(m);
                }
            }
        }
        ms.sort(Integer::compareTo);
        this.milestones = List.copyOf(ms);

        // 预先算好每一档的倍数，at() 里就不用反复 pow
        List<Double> ss = new ArrayList<>(ms.size());
        for (int i = 0; i < ms.size(); i++) {
            double s = this.base * Math.pow(this.multiplier, i + 1);
            ss.add(Math.min(this.max, s));
        }
        this.scales = List.copyOf(ss);
    }

    /** 从配置读。配置写错（负数、乱序、multiplier≤1）一律退化成安全值，绝不抛异常 */
    public static SwordScale from(com.forgemaster.ForgingMasterPlugin plugin) {
        List<Integer> ms = new ArrayList<>();
        if (plugin != null) {
            for (int v : plugin.getConfig().getIntegerList("strengthen.big-sword.milestones")) {
                ms.add(v);
            }
        }
        if (ms.isEmpty()) {
            ms = List.of(33, 66, 99);
        }
        double mult = plugin == null ? 2.0
                : plugin.getConfig().getDouble("strengthen.big-sword.multiplier", 2.0);
        double max = plugin == null ? 8.0
                : plugin.getConfig().getDouble("strengthen.big-sword.max-scale", 8.0);
        // v2.22：曲线起点默认 0——这条曲线现在同时驱动砧上剑身（AnvilForgeManager），
        // 「强化时逐锤变大」要从第一锤就看得见，不能像手上挂牌那样压到 +20 才起步
        int minLevel = plugin == null ? 0
                : plugin.getConfig().getInt("strengthen.big-sword.min-level", 0);
        return new SwordScale(ms, mult, 1.0, max, minLevel);
    }

    /**
     * 某个强化等级的视觉倍数。
     *
     * <p>低于 {@link #minLevel()} 恒为 {@code base}（即「不放大」）；
     * 超过最后一个里程碑恒为那一档（已被 {@code max} 夹住）。</p>
     */
    public double at(int strengthenLevel) {
        if (strengthenLevel < minLevel || milestones.isEmpty()) {
            return base;
        }
        int prevLevel = minLevel;
        double prevScale = base;
        for (int i = 0; i < milestones.size(); i++) {
            int level = milestones.get(i);
            double scale = scales.get(i);
            if (strengthenLevel >= level) {
                prevLevel = level;
                prevScale = scale;
                continue;
            }
            // 落在 (prevLevel, level) 之间：按几何级数插值，手感均匀
            int span = level - prevLevel;
            if (span <= 0) {
                return Math.min(max, scale);
            }
            double t = (double) (strengthenLevel - prevLevel) / span;
            double v = prevScale * Math.pow(scale / prevScale, t);
            return Math.min(max, Double.isFinite(v) ? v : prevScale);
        }
        return Math.min(max, prevScale);
    }

    /** 这个等级该不该挂牌。倍数 ≤ 1.05 时挂牌纯属浪费实体（肉眼也看不出来） */
    public boolean shows(int strengthenLevel) {
        return at(strengthenLevel) > 1.05;
    }

    public int minLevel() {
        return minLevel;
    }

    public double baseScale() {
        return base;
    }

    public double maxScale() {
        return max;
    }

    public double multiplier() {
        return multiplier;
    }

    public List<Integer> milestones() {
        return milestones;
    }

    /** 给玩家看的曲线描述，例如「+33 ×2.0 · +66 ×4.0 · +99 ×8.0」 */
    public String describe() {
        if (milestones.isEmpty()) {
            return "未配置加倍点（不会放大）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < milestones.size(); i++) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append("+").append(milestones.get(i)).append(" ×")
                    .append(trim(scales.get(i)));
        }
        return sb.toString();
    }

    private static String trim(double v) {
        // 整数值不显示小数点：2 而不是 2.0（文案里更利落）
        if (Math.abs(v - Math.rint(v)) < 1e-9) {
            return String.valueOf((long) Math.rint(v));
        }
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}