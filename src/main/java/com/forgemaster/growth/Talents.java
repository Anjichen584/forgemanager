package com.forgemaster.growth;

import com.forgemaster.stats.StatsStore;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 匠人天赋：专精等级每 {@code points-per-level}（默认 5）级给 1 点，自己分配。
 *
 * <p>为什么要有：等级曲线在 v2.9 被砍陡了，光靠「等级数字变大」撑不住长期目标，
 * 而且原来的等级加成是<b>一条直线</b>——练到 60 级等于把所有加成拿满，中间没有任何选择。
 * 天赋把这条直线拆成几个方向，让「练上去」之后还有「怎么点」。</p>
 *
 * <p>所有效果都是<b>加在既有公式上的小修正</b>，不新增一套独立判定：</p>
 * <ul>
 *   <li>手感 → 加热容差 ×(1+3%/点)</li>
 *   <li>眼力 → 锻打命中区 +1 格/点</li>
 *   <li>控火 → 降温速度 ×(1-6%/点)</li>
 *   <li>稳手 → 叠钢时限 +0.4s/点</li>
 *   <li>刃感 → 开刃绿区 +0.8/点</li>
 *   <li>火候 → 额外词条概率 +6%/点</li>
 *   <li>勤学 → 锻造经验 +8%/点</li>
 * </ul>
 *
 * <p>点数是<b>总量约束</b>：60 级一共 12 点，七项加起来要 20 点，所以必然要取舍。
 * 洗点收剑币（{@code talent.reset-cost}），不做免费重置——否则「先点勤学练级、
 * 练满再洗成战斗力」就没有成本了。</p>
 */
public final class Talents {

    /** 一项天赋 */
    public enum Talent {
        TOUCH("touch", "手感", 3, "加热容差 +3%/点"),
        EYE("eye", "眼力", 2, "锻打命中区 +1 格/点"),
        EMBER("ember", "控火", 3, "降温速度 -6%/点"),
        FOLD("fold", "稳手", 3, "叠钢时限 +0.4s/点"),
        EDGE("edge", "刃感", 3, "开刃绿区 +0.8/点"),
        LUCK("luck", "火候", 3, "额外词条概率 +6%/点"),
        STUDY("study", "勤学", 3, "锻造经验 +8%/点");

        private final String id;
        private final String display;
        private final int max;
        private final String effect;

        Talent(String id, String display, int max, String effect) {
            this.id = id;
            this.display = display;
            this.max = max;
            this.effect = effect;
        }

        public String id() {
            return id;
        }

        public String display() {
            return display;
        }

        public int max() {
            return max;
        }

        public String effect() {
            return effect;
        }

        /** 按 id / 中文名 / 枚举名找一个（大小写不敏感），找不到返回 null */
        public static Talent byName(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String key = raw.trim().toLowerCase(Locale.ROOT);
            for (Talent t : values()) {
                if (t.id.equals(key) || t.name().toLowerCase(Locale.ROOT).equals(key)
                        || t.display.equals(raw.trim())) {
                    return t;
                }
            }
            return null;
        }
    }

    /**
     * 一次锻造开始时固化的天赋效果。
     *
     * <p>固化的理由跟 {@code blueprintBonus} / 环境修正一致：锻造中途玩家洗点或升级，
     * 不能出现「前半程用旧数值、后半程用新数值」的撕裂。</p>
     */
    public record Kit(double tolerance, int zone, double heatFactor,
                      double foldSeconds, double grindWidth,
                      double affixChance, double expMultiplier) {

        public static final Kit NONE = new Kit(0, 0, 1.0, 0, 0, 0, 1.0);
    }

    private Talents() {
    }

    private static int pointsPerLevel = 5;

    /** {@code talent.points-per-level}，插件启用 / reload 时灌一次 */
    public static void configure(int perLevel) {
        pointsPerLevel = Math.max(1, perLevel);
    }

    public static int pointsPerLevel() {
        return pointsPerLevel;
    }

    /** 这个等级一共拿到过多少点 */
    public static int earned(int level) {
        return Math.max(0, level) / pointsPerLevel;
    }

    /** 已经点掉多少点 */
    public static int spent(StatsStore.Stats st) {
        if (st == null || st.talents == null) {
            return 0;
        }
        int sum = 0;
        for (int v : st.talents.values()) {
            sum += Math.max(0, v);
        }
        return sum;
    }

    /** 还能点几点 */
    public static int remaining(StatsStore.Stats st) {
        return st == null ? 0 : Math.max(0, earned(st.level) - spent(st));
    }

    public static int levelOf(StatsStore.Stats st, Talent t) {
        if (st == null || st.talents == null || t == null) {
            return 0;
        }
        Integer v = st.talents.get(t.id());
        return v == null ? 0 : Math.max(0, Math.min(t.max(), v));
    }

    /** 能不能再加一点（点数够 + 没到该项上限） */
    public static boolean canAdd(StatsStore.Stats st, Talent t) {
        return st != null && t != null && remaining(st) > 0 && levelOf(st, t) < t.max();
    }

    /** 加一点；成功返回 true，失败（点数不够 / 到顶）返回 false 且不改数据 */
    public static boolean add(StatsStore.Stats st, Talent t) {
        if (!canAdd(st, t)) {
            return false;
        }
        if (st.talents == null) {
            st.talents = new LinkedHashMap<>();
        }
        st.talents.merge(t.id(), 1, Integer::sum);
        return true;
    }

    /** 清空（洗点），返回清掉了几点 */
    public static int reset(StatsStore.Stats st) {
        if (st == null || st.talents == null) {
            return 0;
        }
        int n = spent(st);
        st.talents.clear();
        return n;
    }

    /** 从档案读出全部效果，锻造开始时固化到 session */
    public static Kit kit(StatsStore.Stats st) {
        if (st == null) {
            return Kit.NONE;
        }
        return new Kit(
                levelOf(st, Talent.TOUCH) * 0.03,
                levelOf(st, Talent.EYE),
                1.0 - levelOf(st, Talent.EMBER) * 0.06,
                levelOf(st, Talent.FOLD) * 0.4,
                levelOf(st, Talent.EDGE) * 0.8,
                levelOf(st, Talent.LUCK) * 0.06,
                1.0 + levelOf(st, Talent.STUDY) * 0.08);
    }

    /** 经验倍率：只吃「勤学」，统计 / 落盘在 StatsStore.record 里用 */
    public static double expMultiplier(Map<String, Integer> talents) {
        if (talents == null) {
            return 1.0;
        }
        Integer v = talents.get(Talent.STUDY.id());
        return 1.0 + Math.max(0, v == null ? 0 : v) * 0.08;
    }
}
