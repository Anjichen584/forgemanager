package com.forgemaster.affix;

import com.forgemaster.util.Msg;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 一套「词条套装」的定义。
 *
 * <p>纯数据：不读配置、不碰物品、不引用 Bukkit 世界状态，自检可以直接
 * {@code new AffixSet(...)} 造一套出来断言匹配逻辑。配置解析在
 * {@link AffixSetService} 里做。</p>
 *
 * <p>一个套装 = <b>要求词条集合</b> + <b>若干效果参数</b>。所有效果参数都给一个
 * 「0 = 不启用」的语义，所以同一套数据结构既能表达只加攻击的套装，也能表达
 * 吸血 / 减伤 / 斩杀 / 施毒这种战斗向套装，服主在 config.yml 里随便挑着填。</p>
 *
 * <table border="1">
 *   <caption>参数含义</caption>
 *   <tr><th>字段</th><th>含义</th><th>0 时</th></tr>
 *   <tr><td>{@code bonusAttack}</td><td>集齐后额外攻击加成（ADD_SCALAR）</td><td>不挂修饰符</td></tr>
 *   <tr><td>{@code healPercent}</td><td>命中按伤害回血比例</td><td>不回血</td></tr>
 *   <tr><td>{@code damageReduce}</td><td>受到伤害减免比例</td><td>不减免</td></tr>
 *   <tr><td>{@code executeThreshold}/{@code executeBonus}</td><td>目标血量低于阈值时增伤</td><td>不增伤</td></tr>
 *   <tr><td>{@code slowTicks}/{@code slowAmplifier}</td><td>命中附加缓慢</td><td>不施药水效果</td></tr>
 * </table>
 *
 * @param id              套装 id（小写，同时决定修饰符键名 {@code fm_set_<id>}）
 * @param name            显示名（Lore / 命令里用）
 * @param requires        需要的词条 id（小写、去重）
 * @param bonusAttack     集齐后的额外攻击加成
 * @param healPercent     命中回血比例
 * @param damageReduce    受到伤害减免比例
 * @param executeThreshold 斩杀阈值（目标血量比例，0-1）
 * @param executeBonus    斩杀增伤比例
 * @param slowTicks       缓慢时长（tick）
 * @param slowAmplifier   缓慢等级（0 = 缓慢 I）
 * @param effectText      效果描述模板（支持 {heal} {reduce} {execute} {threshold} {slow} {bonus} {name}）
 */
public record AffixSet(
        String id,
        String name,
        List<String> requires,
        double bonusAttack,
        double healPercent,
        double damageReduce,
        double executeThreshold,
        double executeBonus,
        int slowTicks,
        int slowAmplifier,
        String effectText
) {

    /** 统一小写、去空白、去重；负数一律夹到 0，阈值夹到 [0,1] */
    public AffixSet {
        id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        name = (name == null || name.isBlank()) ? id : name.trim();
        LinkedHashSet<String> req = new LinkedHashSet<>();
        if (requires != null) {
            for (String r : requires) {
                if (r != null && !r.isBlank()) {
                    req.add(r.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        requires = List.copyOf(req);
        bonusAttack = Math.max(0, bonusAttack);
        healPercent = Math.max(0, healPercent);
        damageReduce = Math.max(0, damageReduce);
        executeThreshold = Math.max(0, Math.min(1.0, executeThreshold));
        executeBonus = Math.max(0, executeBonus);
        slowTicks = Math.max(0, slowTicks);
        slowAmplifier = Math.max(0, slowAmplifier);
        effectText = effectText == null ? "" : effectText.trim();
    }

    /**
     * 属性修饰符的键名：{@code fm_set_<套装id>}。
     *
     * <p>每个套装一个独立键，重刷时只删自己键名匹配的那几条，
     * 基础属性（{@code fm_atk} 等）、词条、剑意（{@code fm_prof_atk}）都不受影响。</p>
     */
    public String modifierKey() {
        return "fm_set_" + id;
    }

    /** 已有词条里还差哪几条（按 requires 顺序；全齐返回空表） */
    public List<String> missing(Collection<String> have) {
        List<String> out = new ArrayList<>();
        if (requires.isEmpty()) {
            return out;
        }
        LinkedHashSet<String> lower = normalize(have);
        for (String id : requires) {
            if (!lower.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** 已有词条是否集齐了本套装（空 requirements 视为不成立） */
    public boolean satisfied(Collection<String> have) {
        return !requires.isEmpty() && missing(have).isEmpty();
    }

    /** 已有词条命中几条（Lore 进度 x/y 用） */
    public int haveCount(Collection<String> have) {
        if (requires.isEmpty()) {
            return 0;
        }
        LinkedHashSet<String> lower = normalize(have);
        int n = 0;
        for (String id : requires) {
            if (lower.contains(id)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 效果描述文本（MiniMessage 纯文本，不含颜色标签）。
     *
     * <p>{@code effectText} 里的占位符按当前数值替换，服主改 {@code heal-percent}
     * 之后 Lore 上的百分比跟着变，不用两处一起改。</p>
     */
    public String effect() {
        if (effectText.isEmpty()) {
            return "";
        }
        return effectText
                .replace("{name}", name)
                .replace("{heal}", Msg.fmt(healPercent * 100, 0))
                .replace("{reduce}", Msg.fmt(damageReduce * 100, 0))
                .replace("{threshold}", Msg.fmt(executeThreshold * 100, 0))
                .replace("{execute}", Msg.fmt(executeBonus * 100, 0))
                .replace("{bonus}", Msg.fmt(bonusAttack * 100, 0))
                .replace("{slow}", trimSeconds(slowTicks / 20.0))
                .replace("{slow_amp}", String.valueOf(slowAmplifier + 1));
    }

    private static LinkedHashSet<String> normalize(Collection<String> have) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (have != null) {
            for (String s : have) {
                if (s != null && !s.isBlank()) {
                    out.add(s.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    /** 秒数：整数不带小数点，否则一位小数（3 / 2.5） */
    private static String trimSeconds(double seconds) {
        if (Math.abs(seconds - Math.rint(seconds)) < 0.001) {
            return String.valueOf((long) Math.rint(seconds));
        }
        return String.format(Locale.ROOT, "%.1f", seconds);
    }
}
