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
            double maxSenseRadius, long senseIntervalMs,
            // v2.24 切片一：淬锋 / 饮命
            double maxStreakBonus, int maxStreakStacks, double maxDurLeech,
            // v2.25 切片一：血怒 / 坚韧
            double maxRage, long rageWindowMs, double maxStoutChance) {

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
                    20.0, 3000L,      // 望气：最多 20 格，最快每 3 秒
                    0.20, 5,          // 淬锋：满层最多 +20%，最多 5 层
                    3.0,              // 饮命：击杀最多回 3 点虚拟耐久
                    0.12, 4000L,      // 血怒：最多 +12%，被打后 4 秒窗口
                    0.40);            // 坚韧：单次免磨损几率最多 40%
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
                    Math.max(200L, cfg.getLong("enchant.caps.delay-ms", d.delayMs())), // v2.25 修 P2：<200ms 会让四期全部落在同一拍，「分期」语义失效
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
                    cfg.getLong("enchant.caps.sense-interval-ms", d.senseIntervalMs()),
                    // v2.24 切片一
                    cfg.getDouble("enchant.caps.max-streak-bonus", d.maxStreakBonus()),
                    Math.max(1, cfg.getInt("enchant.caps.max-streak-stacks", d.maxStreakStacks())),
                    cfg.getDouble("enchant.caps.max-dur-leech", d.maxDurLeech()),
                    // v2.25 切片一：血怒 / 坚韧
                    cfg.getDouble("enchant.caps.max-rage", d.maxRage()),
                    cfg.getLong("enchant.caps.rage-window-ms", d.rageWindowMs()),
                    cfg.getDouble("enchant.caps.max-stout-chance", d.maxStoutChance()));
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
                            double regenPerTick, double salvageChance, double senseRadius,
                            // v2.24 切片一：淬锋（每层增伤比例）/ 饮命（击杀回耐久点数）
                            double streakPerLevel, double durLeech,
                            // v2.25 切片一：血怒（受击后窗口增伤比例）/ 坚韧（虚拟耐久免磨损几率）
                            double rage, double stoutChance) {

        public static Mechanics none() {
            return new Mechanics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        public boolean any() {
            return splashRatio > 0 || pyreDamage > 0 || loneWolf > 0 || charge > 0
                    || hamperSeconds > 0 || markBonus > 0 || delayRatio > 0 || cheatHeal > 0
                    || retaliate > 0 || regenPerTick > 0 || salvageChance > 0 || senseRadius > 0
                    || streakPerLevel > 0 || durLeech > 0 || rage > 0 || stoutChance > 0;
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
                // 淬锋：perLevel 是「每层增伤」，存的是单层比例；总层封顶在结算处按
                // maxStreakStacks × 单层夹（见 CombatListener / streakBonusOf），这里只夹单层
                case STREAK -> m = withStreak(m, Math.min(v, caps.maxStreakBonus()));
                case DUR_LEECH -> m = withDurLeech(m, Math.min(v, caps.maxDurLeech()));
                // 血怒：perLevel 是「被打后窗口内的增伤比例」，窗口判定在结算处（EnchantState），
                // 这里只夹比例本身
                case RAGE -> m = withRage(m, Math.min(v, caps.maxRage()));
                // 坚韧：perLevel 是「每次免磨损几率」，掷骰在结算处，这里只夹几率
                case STOUT -> m = withStout(m, Math.min(v, caps.maxStoutChance()));
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
        double streak = 0, durLeech = 0;
        double rage = 0, stout = 0;
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
            streak = Math.max(streak, m.streakPerLevel());
            durLeech = Math.max(durLeech, m.durLeech());
            // 血怒/坚韧同理取大：两件甲都带血怒，+9% 与 +12% 取 12%，相加就会逼近无敌线
            rage = Math.max(rage, m.rage());
            stout = Math.max(stout, m.stoutChance());
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
                Math.min(sense, caps.maxSenseRadius()),
                Math.min(streak, caps.maxStreakBonus()),
                Math.min(durLeech, caps.maxDurLeech()),
                Math.min(rage, caps.maxRage()),
                Math.min(stout, caps.maxStoutChance()));
    }

    // ---- 记录构造（record 没有 wither，逐项写一遍最直观，也避免 Builder 那套样板）

    private static Mechanics withSplash(Mechanics m, double v) {
        return new Mechanics(v, m.pyreDamage(), m.loneWolf(), m.charge(), m.hamperSeconds(),
                m.markBonus(), m.delayRatio(), m.cheatHeal(), m.retaliate(), m.regenPerTick(),
                m.salvageChance(), m.senseRadius(), m.streakPerLevel(), m.durLeech(), m.rage(), m.stoutChance());
    }

    private static Mechanics withPyre(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), v, m.loneWolf(), m.charge(), m.hamperSeconds(),
                m.markBonus(), m.delayRatio(), m.cheatHeal(), m.retaliate(), m.regenPerTick(),
                m.salvageChance(), m.senseRadius(), m.streakPerLevel(), m.durLeech(), m.rage(), m.stoutChance());
    }

    private static Mechanics withLone(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), m.pyreDamage(), v, m.charge(), m.hamperSeconds(),
                m.markBonus(), m.delayRatio(), m.cheatHeal(), m.retaliate(), m.regenPerTick(),
                m.salvageChance(), m.senseRadius(), m.streakPerLevel(), m.durLeech(), m.rage(), m.stoutChance());
    }

    private static Mechanics withCharge(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), m.pyreDamage(), m.loneWolf(), v, m.hamperSeconds(),
                m.markBonus(), m.delayRatio(), m.cheatHeal(), m.retaliate(), m.regenPerTick(),
                m.salvageChance(), m.senseRadius(), m.streakPerLevel(), m.durLeech(), m.rage(), m.stoutChance());
    }

    private static Mechanics withHamper(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), m.pyreDamage(), m.loneWolf(), m.charge(), v,
                m.markBonus(), m.delayRatio(), m.cheatHeal(), m.retaliate(), m.regenPerTick(),
                m.salvageChance(), m.senseRadius(), m.streakPerLevel(), m.durLeech(), m.rage(), m.stoutChance());
    }

    private static Mechanics withMark(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), m.pyreDamage(), m.loneWolf(), m.charge(),
                m.hamperSeconds(), v, m.delayRatio(), m.cheatHeal(), m.retaliate(),
                m.regenPerTick(), m.salvageChance(), m.senseRadius(), m.streakPerLevel(), m.durLeech(), m.rage(), m.stoutChance());
    }

    private static Mechanics withDelay(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), m.pyreDamage(), m.loneWolf(), m.charge(),
                m.hamperSeconds(), m.markBonus(), v, m.cheatHeal(), m.retaliate(),
                m.regenPerTick(), m.salvageChance(), m.senseRadius(), m.streakPerLevel(), m.durLeech(), m.rage(), m.stoutChance());
    }

    private static Mechanics withCheat(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), m.pyreDamage(), m.loneWolf(), m.charge(),
                m.hamperSeconds(), m.markBonus(), m.delayRatio(), v, m.retaliate(),
                m.regenPerTick(), m.salvageChance(), m.senseRadius(), m.streakPerLevel(), m.durLeech(), m.rage(), m.stoutChance());
    }

    private static Mechanics withRetaliate(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), m.pyreDamage(), m.loneWolf(), m.charge(),
                m.hamperSeconds(), m.markBonus(), m.delayRatio(), m.cheatHeal(), v,
                m.regenPerTick(), m.salvageChance(), m.senseRadius(), m.streakPerLevel(), m.durLeech(), m.rage(), m.stoutChance());
    }

    private static Mechanics withRegen(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), m.pyreDamage(), m.loneWolf(), m.charge(),
                m.hamperSeconds(), m.markBonus(), m.delayRatio(), m.cheatHeal(), m.retaliate(),
                v, m.salvageChance(), m.senseRadius(), m.streakPerLevel(), m.durLeech(), m.rage(), m.stoutChance());
    }

    private static Mechanics withSalvage(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), m.pyreDamage(), m.loneWolf(), m.charge(),
                m.hamperSeconds(), m.markBonus(), m.delayRatio(), m.cheatHeal(), m.retaliate(),
                m.regenPerTick(), v, m.senseRadius(), m.streakPerLevel(), m.durLeech(), m.rage(), m.stoutChance());
    }

    private static Mechanics withSense(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), m.pyreDamage(), m.loneWolf(), m.charge(),
                m.hamperSeconds(), m.markBonus(), m.delayRatio(), m.cheatHeal(), m.retaliate(),
                m.regenPerTick(), m.salvageChance(), v, m.streakPerLevel(), m.durLeech(), m.rage(), m.stoutChance());
    }

    private static Mechanics withStreak(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), m.pyreDamage(), m.loneWolf(), m.charge(),
                m.hamperSeconds(), m.markBonus(), m.delayRatio(), m.cheatHeal(), m.retaliate(),
                m.regenPerTick(), m.salvageChance(), m.senseRadius(), v, m.durLeech(),
                m.rage(), m.stoutChance());
    }

    private static Mechanics withDurLeech(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), m.pyreDamage(), m.loneWolf(), m.charge(),
                m.hamperSeconds(), m.markBonus(), m.delayRatio(), m.cheatHeal(), m.retaliate(),
                m.regenPerTick(), m.salvageChance(), m.senseRadius(), m.streakPerLevel(), v,
                m.rage(), m.stoutChance());
    }

    private static Mechanics withRage(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), m.pyreDamage(), m.loneWolf(), m.charge(),
                m.hamperSeconds(), m.markBonus(), m.delayRatio(), m.cheatHeal(), m.retaliate(),
                m.regenPerTick(), m.salvageChance(), m.senseRadius(), m.streakPerLevel(),
                m.durLeech(), v, m.stoutChance());
    }

    private static Mechanics withStout(Mechanics m, double v) {
        return new Mechanics(m.splashRatio(), m.pyreDamage(), m.loneWolf(), m.charge(),
                m.hamperSeconds(), m.markBonus(), m.delayRatio(), m.cheatHeal(), m.retaliate(),
                m.regenPerTick(), m.salvageChance(), m.senseRadius(), m.streakPerLevel(),
                m.durLeech(), m.rage(), v);
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

    /**
     * 淬锋：当前层数下这一击的增伤比例（v2.24 切片一，纯函数）。
     *
     * <p>层封顶与总封顶都在这一处夹：层数超过 {@code maxStreakStacks} 按满层算，
     * 总加成超过 {@code maxStreakBonus} 截断。调用方传的 stacks 永远是
     * {@link EnchantState} 记的真实层数，配置改成离谱值也只会打到夹子上。</p>
     *
     * @param stacks 当前锋锐层数（受击会清零，见 {@code EnchantState.clearStreak}）
     */
    public double streakBonus(Mechanics m, Caps caps, int stacks) {
        if (m == null || caps == null || m.streakPerLevel() <= 0) {
            return 0;
        }
        int eff = Math.min(Math.max(0, stacks), caps.maxStreakStacks());
        return Math.min(eff * m.streakPerLevel(), Math.max(0, caps.maxStreakBonus()));
    }

    /**
     * 淬锋的层数推进（v2.24 切片一，纯函数）。
     *
     * <p>状态层（{@code EnchantState.noteHitStreak}）就用这一份封顶逻辑，
     * 自检不需要伪造 Player 也能钉住「层数封顶」这条红线 —— 封顶写两份
     * 就是「线上按 5 层算、自检按 8 层算」那种分歧的温床。</p>
     */
    public static int nextStreakStack(int current, int maxStacks) {
        return Math.min(Math.max(0, current) + 1, Math.max(1, maxStacks));
    }

    /** 饮命：击杀一次能回多少虚拟耐久点（v2.24 切片一，纯函数，已夹上限） */
    public double durLeechGain(Mechanics m, Caps caps) {
        if (m == null || caps == null) {
            return 0;
        }
        return Math.min(Math.max(0, m.durLeech()), Math.max(0, caps.maxDurLeech()));
    }

    /**
     * 血怒：这一击吃不吃窗口加成（v2.25 切片一，纯函数，已夹上限）。
     *
     * <p>窗口判定（被打后多久）是时间状态，归 {@code EnchantState.rageRemaining}；
     * 这里只认布尔结果 —— 好处是自检不需要伪造 Player 就能钉住「窗口外必须为 0」。
     * 加成与淬锋同属 enchBonus 加法区，最终仍会被 CombatListener 的兜底夹子
     * （各机制上限之和）再收一道。</p>
     *
     * @param inWindow true = 玩家仍处在「被打后 rage-window-ms」窗口内
     */
    public double rageBonus(Mechanics m, Caps caps, boolean inWindow) {
        if (m == null || caps == null || !inWindow || m.rage() <= 0) {
            return 0;
        }
        return Math.min(m.rage(), Math.max(0, caps.maxRage()));
    }

    /**
     * 坚韧：这次不掉虚拟耐久的几率（v2.25 切片一，纯函数，已夹上限）。
     *
     * <p>掷骰在结算处（CombatListener 两处扣账点）；几率本身在这里夹，
     * 武器与护甲四件两个口径共用同一份上限，不会出现「甲上夹了、剑上没夹」。</p>
     */
    public double stoutSkipChance(Mechanics m, Caps caps) {
        if (m == null || caps == null) {
            return 0;
        }
        return Math.min(Math.max(0, m.stoutChance()), Math.max(0, caps.maxStoutChance()));
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