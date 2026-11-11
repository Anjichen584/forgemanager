package com.forgemaster.enchant;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 原创附魔的<b>纯函数</b>效果计算（v2.23.2）。
 *
 * <h2>为什么必须是纯函数</h2>
 * 数值平衡是最容易「越改越乱」的地方。把「一条附魔 = 多少效果」压成不碰 Bukkit 的纯计算，
 * 才能离线跑断言：附魔全满级、5 条武器附魔全叠上，最终倍率是多少、有没有顶穿上限，
 * 一条命令就能验，不用起服。
 *
 * <h2>三条纪律</h2>
 * <ol>
 *   <li><b>加法，不乘法。</b>乘法叠乘会骗人：{@code 1.075 × 1.06 × 1.10 × 1.15 × 1.09 ≈ 1.59}，
 *       每条看着都「很小」，合起来是 +59%。加法 {@code 1 + Σ} 一眼就能看清总量。</li>
 *   <li><b>先汇总、后夹一次。</b>不是每条各自夹 —— 那样 8 条各自合法，加起来照样越界。</li>
 *   <li><b>夹子比 {@code combat.limits.*} 更紧。</b>附魔只能在已有伤害预算里分一小块，
 *       绝不能变成绕过预算的后门。圣上（{@code combat.limits}）永远是最后一道。</li>
 * </ol>
 */
public final class EnchantEffects {

    public EnchantEffects() {
    }

    /**
     * 附魔自己的上限。**所有默认值只存在这里一份**（v2.23.1 的教训：
     * {@code Crit} 曾在两个方法里各写一份默认值，改一处漏一处）。
     */
    public record Caps(double maxDamageBonus, double maxLifestealAdd, double maxFireSeconds,
                       double maxAttackSpeed, double maxReduce, double maxHealth,
                       double maxThorns, double maxThornsFlat, double maxFireResist,
                       double maxExpBonus, double maxCoinBonus, double maxDropChance,
                       double executeThreshold, int comboStacks) {

        public static Caps defaults() {
            return new Caps(
                    0.25,   // 伤害总加成（断金+贯札+诛邪+摧枯+迭浪 全部加完的封顶）
                    0.10,   // 吸血加成
                    4.0,    // 点燃加成（秒）
                    0.08,   // 攻速加成
                    0.10,   // 减伤加成
                    8.0,    // 生命加成
                    0.15,   // 荆棘百分比
                    2.0,    // 荆棘单次绝对值上限
                    0.30,   // 辟火减免
                    0.50,   // 经验加成
                    0.15,   // 剑币加成
                    0.35,   // 挖矿额外掉落几率
                    0.25,   // 摧枯触发血量阈值
                    3);     // 迭浪最多叠几层
        }

        public static Caps fromConfig(ConfigurationSection cfg) {
            if (cfg == null) {
                return defaults();
            }
            Caps d = defaults();
            return new Caps(
                    cfg.getDouble("enchant.caps.max-damage-bonus", d.maxDamageBonus()),
                    cfg.getDouble("enchant.caps.max-lifesteal-add", d.maxLifestealAdd()),
                    cfg.getDouble("enchant.caps.max-fire-seconds", d.maxFireSeconds()),
                    cfg.getDouble("enchant.caps.max-attack-speed", d.maxAttackSpeed()),
                    cfg.getDouble("enchant.caps.max-damage-reduce", d.maxReduce()),
                    cfg.getDouble("enchant.caps.max-bonus-health", d.maxHealth()),
                    cfg.getDouble("enchant.caps.max-thorns", d.maxThorns()),
                    cfg.getDouble("enchant.caps.max-thorns-flat", d.maxThornsFlat()),
                    cfg.getDouble("enchant.caps.max-fire-resist", d.maxFireResist()),
                    cfg.getDouble("enchant.caps.max-exp-bonus", d.maxExpBonus()),
                    cfg.getDouble("enchant.caps.max-coin-bonus", d.maxCoinBonus()),
                    cfg.getDouble("enchant.caps.max-drop-chance", d.maxDropChance()),
                    cfg.getDouble("enchant.caps.execute-threshold", d.executeThreshold()),
                    Math.max(1, cfg.getInt("enchant.caps.combo-stacks", d.comboStacks())));
        }
    }

    /** 物品上的一条原创附魔实例 */
    public record Entry(CustomEnchant def, int level) {
        public String encode() {
            return def.id() + ":" + level;
        }
    }

    // ------------------------------------------------------------ 序列化

    /**
     * 解析 PDC 里的 {@code id:level|id:level}。
     *
     * <p>认不出的 id 直接丢掉（服主关掉了那条附魔 / 改过 id）。
     * 等级夹在 {@code 1..maxLevel}：盘上可能留着旧版本写得更高的值。</p>
     */
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

    /** 写回 PDC；空列表返回 null（调用方应当 remove 键，别存空串） */
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

    // ------------------------------------------------------------ 武器

    /**
     * 武器侧汇总。
     *
     * @param damageMul     最终伤害倍率（已含断金/贯札/诛邪/摧枯/迭浪，**已夹**）
     * @param lifestealAdd  吸血加成（还要再过 {@code DamageBudget.lifestealHeal}）
     * @param fireAddSeconds 点燃加成秒数（还要再过 {@code Caps.clampFireTicks}）
     * @param attackSpeedAdd 攻速加成（还要再过 {@code compat.attack-speed-cap}）
     * @param executeThreshold 摧枯触发阈值（血量比例）
     * @param comboStacks   迭浪最多几层
     */
    public record Weapon(double damageMul, double lifestealAdd, double fireAddSeconds,
                         double attackSpeedAdd, double executeThreshold, int comboStacks) {

        public static Weapon none() {
            return new Weapon(1.0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * 按「目标状态」算出这一次命中的伤害倍率。
     *
     * <p>分开算是因为诛邪只对敌对生物、摧枯只在目标残血时生效、迭浪要按层数。
     * 但<b>总量仍然只夹一次</b> —— 见类注释纪律 2。</p>
     *
     * @param monster  目标是不是敌对生物（Monster）
     * @param hpRatio  目标当前血量比例（0~1；未知传 1.0）
     * @param combos   当前迭浪层数（0 表示没叠）
     */
    public double hitMultiplier(List<Entry> entries, Caps caps, boolean monster,
                                double hpRatio, int combos) {
        if (entries == null || entries.isEmpty() || caps == null) {
            return 1.0;
        }
        double sum = 0;
        double executeSum = 0;
        double comboPer = 0;
        int comboMax = caps.comboStacks();
        for (Entry e : entries) {
            double v = e.def().at(e.level());
            if (v <= 0) {
                continue;
            }
            switch (e.def().kind()) {
                case DAMAGE_MUL, ARMOR_PIERCE -> sum += v;
                case VS_MONSTER -> {
                    if (monster) {
                        sum += v;
                    }
                }
                case EXECUTE -> {
                    if (hpRatio <= caps.executeThreshold()) {
                        executeSum += v;
                    }
                }
                case COMBO -> comboPer += v;
                default -> {
                    // 其余类别不走伤害通道
                }
            }
        }
        sum += executeSum;
        sum += comboPer * Math.max(0, Math.min(combos, comboMax));
        // 唯一的一次夹：所有伤害来源加完再压
        double capped = Math.min(sum, Math.max(0, caps.maxDamageBonus()));
        return 1.0 + capped;
    }

    public Weapon weapon(List<Entry> entries, Caps caps) {
        if (entries == null || entries.isEmpty() || caps == null) {
            return Weapon.none();
        }
        double lifesteal = 0;
        double fire = 0;
        double speed = 0;
        int combos = 0;
        for (Entry e : entries) {
            double v = e.def().at(e.level());
            if (v <= 0) {
                continue;
            }
            switch (e.def().kind()) {
                case LIFESTEAL_ADD -> lifesteal += v;
                case FIRE_ADD -> fire += v;
                case ATTACK_SPEED -> speed += v;
                case COMBO -> combos = Math.max(combos, caps.comboStacks());
                default -> {
                }
            }
        }
        return new Weapon(1.0,
                Math.min(lifesteal, Math.max(0, caps.maxLifestealAdd())),
                Math.min(fire, Math.max(0, caps.maxFireSeconds())),
                Math.min(speed, Math.max(0, caps.maxAttackSpeed())),
                caps.executeThreshold(), combos);
    }

    // ------------------------------------------------------------ 护甲

    public record Armor(double reduceAdd, double healthAdd, double fireResist,
                        double thornsPct, double thornsFlat) {

        public static Armor none() {
            return new Armor(0, 0, 0, 0, 0);
        }
    }

    public Armor armor(List<Entry> entries, Caps caps) {
        if (entries == null || entries.isEmpty() || caps == null) {
            return Armor.none();
        }
        double reduce = 0;
        double health = 0;
        double fireRes = 0;
        double thorns = 0;
        for (Entry e : entries) {
            double v = e.def().at(e.level());
            if (v <= 0) {
                continue;
            }
            switch (e.def().kind()) {
                case DAMAGE_REDUCE -> reduce += v;
                case HEALTH_ADD -> health += v;
                case FIRE_RESIST -> fireRes += v;
                case THORNS -> thorns += v;
                default -> {
                }
            }
        }
        return new Armor(
                Math.min(reduce, Math.max(0, caps.maxReduce())),
                Math.min(health, Math.max(0, caps.maxHealth())),
                Math.min(fireRes, Math.max(0, caps.maxFireResist())),
                Math.min(thorns, Math.max(0, caps.maxThorns())),
                Math.max(0, caps.maxThornsFlat()));
    }

    // ------------------------------------------------------------ 通用

    /** 经验倍率（1.0 = 不加成） */
    public double expMul(List<Entry> entries, Caps caps) {
        return 1.0 + bonus(entries, caps, CustomEnchant.Kind.EXP_BONUS, caps.maxExpBonus());
    }

    /** 剑币倍率 */
    public double coinMul(List<Entry> entries, Caps caps) {
        return 1.0 + bonus(entries, caps, CustomEnchant.Kind.COIN_BONUS, caps.maxCoinBonus());
    }

    /** 挖矿额外掉落几率（0~上限） */
    public double dropChance(List<Entry> entries, Caps caps) {
        return bonus(entries, caps, CustomEnchant.Kind.DROP_BONUS, caps.maxDropChance());
    }

    private double bonus(List<Entry> entries, Caps caps, CustomEnchant.Kind kind, double cap) {
        if (entries == null || entries.isEmpty() || caps == null) {
            return 0;
        }
        double sum = 0;
        for (Entry e : entries) {
            if (e.def().kind() == kind) {
                sum += e.def().at(e.level());
            }
        }
        return Math.min(sum, Math.max(0, cap));
    }

    /**
     * 多件装备的护甲效果合并（四件套各带附魔）。
     *
     * <p>合并后<b>再夹一次总上限</b>，不是各自夹完相加 —— 否则四件各 10% 就是 40%。</p>
     */
    public Armor mergeArmor(List<Armor> parts, Caps caps) {
        if (parts == null || parts.isEmpty() || caps == null) {
            return Armor.none();
        }
        double reduce = 0;
        double health = 0;
        double fireRes = 0;
        double thorns = 0;
        for (Armor a : parts) {
            reduce += a.reduceAdd();
            health += a.healthAdd();
            fireRes += a.fireResist();
            thorns += a.thornsPct();
        }
        return new Armor(
                Math.min(reduce, Math.max(0, caps.maxReduce())),
                Math.min(health, Math.max(0, caps.maxHealth())),
                Math.min(fireRes, Math.max(0, caps.maxFireResist())),
                Math.min(thorns, Math.max(0, caps.maxThorns())),
                Math.max(0, caps.maxThornsFlat()));
    }
}