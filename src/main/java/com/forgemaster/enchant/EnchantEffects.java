package com.forgemaster.enchant;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 原创附魔的效果计算（v2.23.3 重做）：**纯函数，不碰 Bukkit 运行时**。
 *
 * <h2>和上一版的区别</h2>
 * v2.23.2 这一层算的是「伤害总加成 +25%」——本质还是原版锋利那套数值。
 * 现在算的是**机制参数**：溅射比例多少、蓄势窗口多长、刻痕阈值多高、卸力摊多少。
 * 参数对了机制才成立；参数错了机制会崩（比如卸力 100% 就等于无敌）。
 *
 * <h2>为什么仍然要纯函数</h2>
 * 机制型附魔最怕的不是「数字不好看」，而是「组合起来出现无敌/无限循环」。
 * 纯函数才能离线跑断言：满级替身 + 满级卸力 + 满级守夜叠在一起，会不会变成打不死。
 */
public final class EnchantEffects {

    public EnchantEffects() {
    }

    /**
     * 全部机制的上限参数。**默认值只存在这里一份。**
     *
     * <p>每个字段都对应一个「必须被夹住，否则机制会崩」的地方：</p>
     * <ul>
     *   <li>{@code maxSplashRatio} —— 溅射如果是 100%，单体剑就变成范围武器，原版武器全失业</li>
     *   <li>{@code maxDelayRatio} —— 卸力如果 100%，等于伤害无限延后 = 无敌</li>
     *   <li>{@code cheatCooldownMs} —— 替身没有冷却就是永久免死</li>
     *   <li>{@code maxRegenPerTick} —— 守夜回太快就替代了药水与食物体系</li>
     * </ul>
     */
    public record Caps(
            // 崩流
            double maxSplashRatio, double splashRadius,
            // 烬狱
            double maxPyreDamage, double pyreRadius,
            // 孤锋
            double maxLoneWolf, double loneWolfRadius,
            // 蓄锋
            double maxCharge, long chargeWindowMs,
            // 夺魄
            double maxHamperSeconds,
            // 刻痕
            double markThreshold, double maxMarkBonus,
            // 卸力
            double maxDelayRatio, long delayMs,
            // 替身
            double maxCheatHeal, long cheatCooldownMs,
            // 回响
            double maxRetaliate, long retaliateMs,
            // 守夜
            double maxRegenPerTick, long regenIntervalMs, long outOfCombatMs, int maxRegenLevel,
            // 拾遗
            double maxSalvageChance,
            // 望气
            double maxSenseRadius, long senseIntervalMs) {

        public static Caps defaults() {
            return new Caps(
                    0.40, 3.0,        // 崩流：最多 40% 溅射，半径 3 格
                    6.0, 3.0,         // 烬狱：最多 6 点，半径 3 格
                    0.15, 8.0,        // 孤锋：最多 +15%，判定半径 8 格
                    0.25, 2500L,      // 蓄锋：最多 +25%，窗口 2.5 秒
                    2.0,              // 夺魄：最多 2 秒
                    0.30, 0.10,       // 刻痕：累计削三成血触发，之后最多 +10%
                    0.50, 2000L,      // 卸力：最多摊 50%，摊到 2 秒内
                    0.30, 90_000L,    // 替身：免死回气最多 30%，冷却 90 秒
                    0.15, 3000L,      // 回响：最多 +15%，窗口 3 秒
                    2.5, 2000L, 5000L, 5, // 守夜：每 2 秒最多回 2.5 点，脱战 5 秒起算
                    0.30,             // 拾遗：最多 30% 几率
                    20.0, 3000L);     // 望气：最多 20 格，最快每 3 秒
        }

        public static Caps fromConfig(ConfigurationSection cfg) {
            if (cfg == null) {
                return defaults();
            }
            Caps d = defaults();
            return new Caps(
                    cfg.getDouble("enchant.caps.max-splash-ratio", d.maxSplashRatio()),
                    cfg.getDouble("enchant.caps.splash-radius", d.splashRadius()),
                    cfg.getDouble("enchant.caps.max-pyre-damage", d.maxPyreDamage()),
                    cfg.getDouble("enchant.caps.pyre-radius", d.pyreRadius()),
                    cfg.getDouble("enchant.caps.max-lone-wolf", d.maxLoneWolf()),
                    cfg.getDouble("enchant.caps.lone-wolf-radius", d.loneWolfRadius()),
                    cfg.getDouble("enchant.caps.max-charge", d.maxCharge()),
                    cfg.getLong("enchant.caps.charge-window-ms", d.chargeWindowMs()),
                    cfg.getDouble("enchant.caps.max-hamper-seconds", d.maxHamperSeconds()),
                    cfg.getDouble("enchant.caps.mark-threshold", d.markThreshold()),
                    cfg.getDouble("enchant.caps.max-mark-bonus", d.maxMarkBonus()),
                    cfg.getDouble("enchant.caps.max-delay-ratio", d.maxDelayRatio()),
                    cfg.getLong("enchant.caps.delay-ms", d.delayMs()),
                    cfg.getDouble("enchant.caps.max-cheat-heal", d.maxCheatHeal()),
                    cfg.getLong("enchant.caps.cheat-cooldown-ms", d.cheatCooldownMs()),
                    cfg.getDouble("enchant.caps.max-retaliate", d.maxRetaliate()),
                    cfg.getLong("enchant.caps.retaliate-ms", d.retaliateMs()),
                    cfg.getDouble("enchant.caps.max-regen-per-tick", d.maxRegenPerTick()),
                    cfg.getLong("enchant.caps.regen-interval-ms", d.regenIntervalMs()),
                    cfg.getLong("enchant.caps.out-of-combat-ms", d.outOfCombatMs()),
                    Math.max(1, cfg.getInt("enchant.caps.max-regen-level", d.maxRegenLevel())),
                    cfg.getDouble("enchant.caps.max-salvage-chance", d.maxSalvageChance()),
                    cfg.getDouble("enchant.caps.max-sense-radius", d.maxSenseRadius()),
                    cfg.getLong("enchant.caps.sense-interval-ms", d.senseIntervalMs()));
        }
    }

    /** 物品上的一条原创附魔实例 */
    public record Entry(CustomEnchant def, int level) {
        public String encode() {
            return def.id() + ":" + level;
        }
    }

    // ------------------------------------------------------------ 序列化

    public static List<Entry> parse(EnchantRegistry registry, String raw) {
        List<Entry> out = new ArrayList<>();
        if (registry == null || raw == null || raw.isBlank()) {
            return out;
        }
        Map<String, Entry> byId = new LinkedHashMap<>();
        for (String part : raw.split("\\|")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int idx = p.lastIndexOf(':');
            if (idx <= 0) {
                continue;
            }
            CustomEnchant def = registry.get(p.substring(0, idx));
            if (def == null) {
                continue;
            }
            int lv;
            try {
                lv = Integer.parseInt(p.substring(idx + 1).trim());
            } catch (NumberFormatException ex) {
                continue;
            }
            lv = Math.max(1, Math.min(lv, def.maxLevel()));
            Entry prev = byId.get(def.id());
            if (prev == null || lv > prev.level()) {
                byId.put(def.id(), new Entry(def, lv));
            }
        }
        out.addAll(byId.values());
        return out;
    }

    public static String encode(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(e.encode());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 机制参数汇总

    /**
     * 一件装备上所有附魔的参数汇总（已按 {@link Caps} 夹过）。
     *
     * <p>同一种机制在一件装备上只会出现一条（{@link #parse} 按 id 去重），
     * 但护甲四件会各算一份，由调用方合并 —— 合并时**再夹一次**，不是各自夹完相加。</p>
     */
    public record Mechanics(double splashRatio, double pyreDamage, double loneWolf,
                            double charge, double hamperSeconds, double markBonus,
                            double delayRatio, double cheatHeal, double retaliate,
                            double regenPerTick, double salvageChance, double senseRadius) {

        public static Mechanics none() {
            return new Mechanics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        public boolean any() {
            return splashRatio > 0 || pyreDamage > 0 || loneWolf > 0 || charge > 0
                    || hamperSeconds > 0 || markBonus > 0 || delayRatio > 0 || cheatHeal > 0
                    || retaliate > 0 || regenPerTick > 0 || salvageChance > 0 || senseRadius > 0;
        }
    }

    /** 单件装备的机制参数（每类各夹一次，因为单件上同类不会重复） */
    public Mechanics of(List<Entry> entries, Caps caps) {
        if (entries == null || entries.isEmpty() || caps == null) {
            return Mechanics.none();
        }
        Mechanics m = Mechanics.none();
        for (Entry e : entries) {
            double v = e.def().at(e.level());
            if (v <= 0) {
                continue;
            }
            switch (e.def().kind()) {
                case SPLASH -> m = withSplash(m, Math.min(v, caps.maxSplashRatio()));
                case PYRE -> m = withPyre(m, Math.min(v, caps.maxPyreDamage()));
                case LONE_WOLF -> m = withLone(m, Math.min(v, caps.maxLoneWolf()));
                case CHARGE -> m = withCharge(m, Math.min(v, caps.maxCharge()));
                case HAMPER -> m = withHamper(m, Math.min(v, caps.maxHamperSeconds()));
                case MARK -> m = withMark(m, Math.min(v, caps.maxMarkBonus()));
                case DELAY_DAMAGE -> m = withDelay(m, Math.min(v, caps.maxDelayRatio()));
                case CHEAT_DEATH -> m = withCheat(m, Math.min(v, caps.maxCheatHeal()));
                case RETALIATE -> m = withRetaliate(m, Math.min(v, caps.maxRetaliate()));
                case REGEN -> m = withRegen(m, Math.min(v, caps.maxRegenPerTick()));
                case SALVAGE -> m = withSalvage(m, Math.min(v, caps.maxSalvageChance()));
                case SENSE -> m = withSense(m, Math.min(v, caps.maxSenseRadius()));
            }
        }
        return m;
    }

    /**
     * 多件装备合并（护甲四件）。**合并后再夹一次**，这是全局唯一的一处再夹。
     *
     * <p>为什么要这样：四件各带满级卸力（50%），如果各自夹完相加就是 200%，
     * 那玩家等于无敌。合并后只认 {@code maxDelayRatio}（50%）。</p>
     */
    public Mechanics merge(List<Mechanics> parts, Caps caps) {
        if (parts == null || parts.isEmpty() || caps == null) {
            return Mechanics.none();
        }
        double splash = 0, pyre = 0, lone = 0, charge = 0, hamper = 0, mark = 0;
        double delay = 0, cheat = 0, retaliate = 0, regen = 0, salvage = 0, sense = 0;
        for (Mechanics m : parts) {
            // 同一种机制取「最大值」而不是相加：两件护甲都带卸力，摊 50% 与摊 50% 没有区别，
            // 相加只会变成无敌。溅射/烬狱是输出侧，同理取大不叠加。
            splash = Math.max(splash, m.splashRatio());
            pyre = Math.max(pyre, m.pyreDamage());
            lone = Math.max(lone, m.loneWolf());
            charge = Math.max(charge, m.charge());
            hamper = Math.max(hamper, m.hamperSeconds());
            mark = Math.max(mark, m.markBonus());
            delay = Math.max(delay, m.delayRatio());
            cheat = Math.max(cheat, m.cheatHeal());
            retaliate = Math.max(retaliate, m.retaliate());
            regen = Math.max(regen, m.regenPerTick());
            salvage = Math.max(salvage, m.salvageChance());
            sense = Math.max(sense, m.senseRadius());
        }
        return new Mechanics(
                Math.min(splash, caps.maxSplashRatio()),
                Math.min(pyre, caps.maxPyreDamage()),
                Math.min(lone, caps.maxLoneWolf()),
                Math.min(charge, caps.maxCharge()),
                Math.min(hamper, caps.maxHamperSeconds()),
                Math.min(mark, caps.maxMarkBonus()),
                Math.min(delay, caps.maxDelayRatio()),
                Math.min(cheat, caps.maxCheatHeal()),
                Math.min(retaliate, caps.maxRetaliate()),
                Math.min(regen, caps.maxRegenPerTick()),
                Math.min(salvage, caps.maxSalvageChance()),
                Math.min(sense, caps.maxSenseRadius()));
    }

    // ---- 记录构造（record 没有 wither，逐项写一遍最直观，也避免 Builder 那套样板）

    private static Mechanics withSplash(Mechanics m, double v) {
        return new Mechanics(v, m.pyreDamage(), m.loneWolf(), m.charge(), m.hamperSeconds(),
                m.markBonus(), m.delayRatio(), m.cheatHeal(), m.retaliate(), m.regenPerTick(),
                m.salvageChance(), m.senseRadius());
    }

    private static Mechanics withPyre(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), v, m.loneWolf(), m.charge(), m.hamperSeconds(),
                m.markBonus(), m.delayRatio(), m.cheatHeal(), m.retaliate(), m.regenPerTick(),
                m.salvageChance(), m.senseRadius());
    }

    private static Mechanics withLone(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), m.pyreDamage(), v, m.charge(), m.hamperSeconds(),
                m.markBonus(), m.delayRatio(), m.cheatHeal(), m.retaliate(), m.regenPerTick(),
                m.salvageChance(), m.senseRadius());
    }

    private static Mechanics withCharge(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), m.pyreDamage(), m.loneWolf(), v, m.hamperSeconds(),
                m.markBonus(), m.delayRatio(), m.cheatHeal(), m.retaliate(), m.regenPerTick(),
                m.salvageChance(), m.senseRadius());
    }

    private static Mechanics withHamper(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), m.pyreDamage(), m.loneWolf(), m.charge(), v,
                m.markBonus(), m.delayRatio(), m.cheatHeal(), m.retaliate(), m.regenPerTick(),
                m.salvageChance(), m.senseRadius());
    }

    private static Mechanics withMark(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), m.pyreDamage(), m.loneWolf(), m.charge(),
                m.hamperSeconds(), v, m.delayRatio(), m.cheatHeal(), m.retaliate(),
                m.regenPerTick(), m.salvageChance(), m.senseRadius());
    }

    private static Mechanics withDelay(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), m.pyreDamage(), m.loneWolf(), m.charge(),
                m.hamperSeconds(), m.markBonus(), v, m.cheatHeal(), m.retaliate(),
                m.regenPerTick(), m.salvageChance(), m.senseRadius());
    }

    private static Mechanics withCheat(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), m.pyreDamage(), m.loneWolf(), m.charge(),
                m.hamperSeconds(), m.markBonus(), m.delayRatio(), v, m.retaliate(),
                m.regenPerTick(), m.salvageChance(), m.senseRadius());
    }

    private static Mechanics withRetaliate(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), m.pyreDamage(), m.loneWolf(), m.charge(),
                m.hamperSeconds(), m.markBonus(), m.delayRatio(), m.cheatHeal(), v,
                m.regenPerTick(), m.salvageChance(), m.senseRadius());
    }

    private static Mechanics withRegen(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), m.pyreDamage(), m.loneWolf(), m.charge(),
                m.hamperSeconds(), m.markBonus(), m.delayRatio(), m.cheatHeal(), m.retaliate(),
                v, m.salvageChance(), m.senseRadius());
    }

    private static Mechanics withSalvage(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), m.pyreDamage(), m.loneWolf(), m.charge(),
                m.hamperSeconds(), m.markBonus(), m.delayRatio(), m.cheatHeal(), m.retaliate(),
                m.regenPerTick(), v, m.senseRadius());
    }

    private static Mechanics withSense(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), m.pyreDamage(), m.loneWolf(), m.charge(),
                m.hamperSeconds(), m.markBonus(), m.delayRatio(), m.cheatHeal(), m.retaliate(),
                m.regenPerTick(), m.salvageChance(), v);
    }

    // ------------------------------------------------------------ 伤害加成（条件型）

    /**
     * 这一击的额外伤害比例（加法，已夹）。
     *
     * <p>三个条件型机制都只影响**这一击**，不是常驻增伤：</p>
     * <ul>
     *   <li>孤锋：八格内没有别的玩家（鼓励单挑，人多就失效）</li>
     *   <li>蓄锋：距上次命中超过窗口（奖励「不乱挥」，惩罚乱点）</li>
     *   <li>刻痕：目标已被刻印（长期战收益）</li>
     * </ul>
     *
     * <p>三者相加后仍然只夹一次，且夹子比 {@code combat.limits} 更紧。</p>
     */
    public double conditionalBonus(Mechanics m, Caps caps, boolean alone,
                                   long msSinceLastHit, boolean marked) {
        if (m == null || caps == null) {
            return 0;
        }
        double sum = 0;
        if (alone) {
            sum += m.loneWolf();
        }
        if (msSinceLastHit >= caps.chargeWindowMs()) {
            sum += m.charge();
        }
        if (marked) {
            sum += m.markBonus();
        }
        // 三个条件同时满足的理论上限 50%，夹到「孤锋+蓄锋+刻痕」三项各自上限之和
        double cap = caps.maxLoneWolf() + caps.maxCharge() + caps.maxMarkBonus();
        return Math.min(sum, Math.max(0, cap));
    }

    /**
     * 溅射伤害（独立结算，**不触发附魔**）。
     *
     * @param dealt 本次对主目标的实际伤害
     */
    public double splashDamage(Mechanics m, Caps caps, double dealt) {
        if (m == null || caps == null || dealt <= 0) {
            return 0;
        }
        return Math.min(dealt * m.splashRatio(), dealt * caps.maxSplashRatio());
    }

    /** 烬狱：击杀时的范围伤害 */
    public double pyreDamage(Mechanics m, Caps caps) {
        if (m == null || caps == null) {
            return 0;
        }
        return Math.min(m.pyreDamage(), caps.maxPyreDamage());
    }

    /** 卸力：这次伤害里有多少要延后 */
    public double delayedPortion(Mechanics m, Caps caps, double damage) {
        if (m == null || caps == null || damage <= 0) {
            return 0;
        }
        return Math.min(damage * m.delayRatio(), damage * caps.maxDelayRatio());
    }

    /** 替身：免死之后回多少血（按最大生命比例） */
    public double cheatHeal(Mechanics m, Caps caps, double maxHealth) {
        if (m == null || caps == null || maxHealth <= 0) {
            return 0;
        }
        return maxHealth * Math.min(m.cheatHeal(), caps.maxCheatHeal());
    }
}