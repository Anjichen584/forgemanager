package com.forgemaster.tomb;

import com.forgemaster.ForgingMasterPlugin;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 残影守卫词缀引擎（v2.23）：给每只残影守卫随机挂 0~2 条「残影词缀」，让战斗有差异。
 *
 * <p>剑冢残影（{@link TombChallenge}）与剑冢秘境守卫（tombrun 的 TombRunService）
 * 共用这一套：生成时掷签 → 词缀 id 写进实体 PDC → 名字上挂彩色前缀区分；
 * 伤害侧效果（攻击乘区 / 点燃 / 饮血 / 暴击 / 受击减免）由
 * {@link TombGuardAffixListener} 按 PDC 标记处理，移速加成在挂签时直接上药水效果。
 * PDC 随实体走，残影死亡 / 实体移除时词缀自然失效，无需任何清理逻辑。</p>
 *
 * <h2>数据驱动</h2>
 * <p>词缀池、每只数量分布、各词缀触发数值全部走 {@code tomb-guard-affix.} 配置键，
 * 全部带代码默认值（见 {@code DEFAULT_POOL / DEFAULT_COUNTS} 与各 getter）。
 * 掷签用注入的 {@link Random}（纯函数 {@link #rollIds}），同 seed 同结果，自检可断言。</p>
 *
 * <h2>防误伤</h2>
 * <p>监听侧只认带 {@code fm:tomb_guard_affix} PDC 标记的实体（{@link #isGuard}），
 * 没标记的普通僵尸 / 玩家宠物 / 其它插件实体一律原样放行。词缀 id 白名单过滤
 * （{@link #filterPool}），配置里写了不认识的 id 直接剔除，不会写进 PDC。</p>
 */
public final class TombGuardAffixes {

    /** 词缀 id：对应 affix 语义里的 attack_damage（残影对玩家伤害乘区） */
    public static final String ATTACK_DAMAGE = "attack_damage";
    /** 词缀 id：lifesteal（残影攻击回血） */
    public static final String LIFESTEAL = "lifesteal";
    /** 词缀 id：fire（命中点燃） */
    public static final String FIRE = "fire";
    /** 词缀 id：damage_reduce（残影受伤害减免） */
    public static final String DAMAGE_REDUCE = "damage_reduce";
    /** 词缀 id：crit（概率 ×crit 倍率伤害） */
    public static final String CRIT = "crit";
    /** 词缀 id：speed（移速加成，挂签时上药水效果） */
    public static final String SPEED = "speed";

    /** 默认词缀池（配置 tomb-guard-affix.pool 未配时的代码默认值） */
    public static final List<String> DEFAULT_POOL = List.of(
            ATTACK_DAMAGE, LIFESTEAL, FIRE, DAMAGE_REDUCE, CRIT, SPEED);

    /** 默认每只数量分布：index i = 挂 i 条词缀的权重（默认 35% 不带 / 40% 一条 / 25% 两条） */
    public static final List<Double> DEFAULT_COUNTS = List.of(0.35, 0.40, 0.25);

    /** 词缀的中文前缀名（名字区分用，如「饮血残影」） */
    private static final java.util.Map<String, String> LABELS = java.util.Map.of(
            ATTACK_DAMAGE, "凶戾",
            LIFESTEAL, "饮血",
            FIRE, "灼魂",
            DAMAGE_REDUCE, "磐石",
            CRIT, "暴烈",
            SPEED, "疾影");

    /** 词缀的展示色（MiniMessage 色值，名字上色用） */
    private static final java.util.Map<String, String> COLORS = java.util.Map.of(
            ATTACK_DAMAGE, "#ff6b6b",
            LIFESTEAL, "#c92a2a",
            FIRE, "#ffa94d",
            DAMAGE_REDUCE, "#adb5bd",
            CRIT, "#e599f7",
            SPEED, "#74c0fc");

    private final ForgingMasterPlugin plugin;
    private final Random random = new Random();

    /** 守卫标记：BYTE 1 = 这只实体带着残影词缀（监听侧唯一判定依据） */
    private final NamespacedKey keyMark;
    /** 词缀清单：STRING，逗号分隔的词缀 id */
    private final NamespacedKey keyList;

    /**
     * 引擎构造：顺手把伤害监听挂上（调用发生在 onEnable 里 TombManager 的构造，
     * 此时插件已启用，registerEvents 合法），插件主类不需要额外接线。
     */
    public TombGuardAffixes(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.keyMark = new NamespacedKey(plugin, "tomb_guard_affix");
        this.keyList = new NamespacedKey(plugin, "tomb_guard_affix_list");
        Bukkit.getPluginManager().registerEvents(new TombGuardAffixListener(this), plugin);
    }

    // ------------------------------------------------------------ 配置（全部带代码默认值）

    /** 残影词缀总开关 */
    public boolean enabled() {
        return plugin.settings().flag("tomb-guard-affix.enabled", true);
    }

    /** 词缀池：过滤到已知 id 白名单，去重保序；配置没写用默认池 */
    public List<String> pool() {
        return filterPool(plugin.settings().strList("tomb-guard-affix.pool", DEFAULT_POOL));
    }

    /** 每只至多挂几条：负数归 0，超池按池大小收 */
    public int maxPerGuard() {
        return clampMaxPerGuard(plugin.settings().intOf("tomb-guard-affix.max-per-guard", 2), pool().size());
    }

    /** 每只数量分布：index i = 挂 i 条的权重（配置没写用默认三档） */
    public List<Double> countWeights() {
        return trimWeights(plugin.settings().numList("tomb-guard-affix.counts", DEFAULT_COUNTS));
    }

    /** attack_damage：残影对玩家伤害乘区（≥1，配小了按 1 算等于没配） */
    public double attackDamageMultiplier() {
        return Math.max(1.0, plugin.settings().num("tomb-guard-affix.attack-damage.multiplier", 1.25));
    }

    /** lifesteal：残影攻击回血比例（0~1） */
    public double lifestealFraction() {
        return Math.max(0, Math.min(1, plugin.settings().num("tomb-guard-affix.lifesteal.fraction", 0.30)));
    }

    /** fire：命中点燃秒数（0 = 不点燃） */
    public int fireSeconds() {
        return Math.max(0, plugin.settings().intOf("tomb-guard-affix.fire.seconds", 4));
    }

    /** damage_reduce：残影受伤害减免比例（0~0.9，留个活口免得打不动） */
    public double damageReducePercent() {
        return Math.max(0, Math.min(0.9, plugin.settings().num("tomb-guard-affix.damage-reduce.percent", 0.25)));
    }

    /** crit：暴击触发概率（0~1，0 = 不暴击） */
    public double critChance() {
        return Math.max(0, Math.min(1, plugin.settings().num("tomb-guard-affix.crit.chance", 0.20)));
    }

    /** crit：暴击伤害倍率（≥1） */
    public double critMultiplier() {
        return Math.max(1.0, plugin.settings().num("tomb-guard-affix.crit.multiplier", 1.5));
    }

    /** speed：移速药水等级（1 = Speed I） */
    public int speedLevel() {
        return Math.max(1, plugin.settings().intOf("tomb-guard-affix.speed.level", 1));
    }

    /** speed：药水时长（tick，默认 1 小时——守卫活不了那么久，死了自然失效） */
    public int speedDurationTicks() {
        return Math.max(20, plugin.settings().intOf("tomb-guard-affix.speed.duration-ticks", 72_000));
    }

    // ------------------------------------------------------------ 掷签（纯函数，Random 注入可断言确定性）

    /**
     * 词缀池白名单过滤：小写归一、剔未知 id、去重、保留顺序；null / 全未知返回空表。
     *
     * <p>这是配置进 PDC 之前的唯一闸门——PDC 里只会出现已知词缀 id，
     * 监听侧按 id 分派效果，认不出也不会炸。</p>
     */
    public static List<String> filterPool(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String id : raw) {
            if (id == null) {
                continue;
            }
            String norm = id.trim().toLowerCase(Locale.ROOT);
            if (isKnownId(norm) && !out.contains(norm)) {
                out.add(norm);
            }
        }
        return List.copyOf(out);
    }

    /** 这个 id 是不是已知词缀 */
    public static boolean isKnownId(String id) {
        return id != null && switch (id) {
            case ATTACK_DAMAGE, LIFESTEAL, FIRE, DAMAGE_REDUCE, CRIT, SPEED -> true;
            default -> false;
        };
    }

    /** max-per-guard 钳制：负数归 0，超过池大小按池大小收 */
    public static int clampMaxPerGuard(int raw, int poolSize) {
        return Math.max(0, Math.min(Math.max(0, poolSize), raw));
    }

    /** 数量分布权重清洗：负数按 0 处理；null / 空表退回默认三档 */
    public static List<Double> trimWeights(List<Double> raw) {
        if (raw == null || raw.isEmpty()) {
            return DEFAULT_COUNTS;
        }
        List<Double> out = new ArrayList<>();
        for (Double w : raw) {
            out.add(w == null || w < 0 ? 0.0 : w);
        }
        return List.copyOf(out);
    }

    /**
     * 按数量分布掷出这一只挂几条。
     *
     * <p>权重表 index i = 挂 i 条的权重：{@code roll ∈ [0,1)} 乘总权重后走累计表落档。
     * 零权重档位直接跳过；全零 / max=0 时恒为 0 条；落点超出 max 的档位按 max 收。</p>
     *
     * @param roll    ∈ [0,1) 的掷签值
     * @param weights 数量分布权重（index = 条数）
     * @param max     上限钳制
     * @return 挂几条（0 ~ min(max, weights.size-1)）
     */
    public static int pickCount(double roll, List<Double> weights, int max) {
        List<Double> w = trimWeights(weights);
        double total = 0;
        for (double v : w) {
            total += v;
        }
        if (total <= 0 || max <= 0) {
            return 0;
        }
        double target = Math.max(0, Math.min(0.999_999, roll)) * total;
        double cursor = 0;
        int index = 0;
        for (int i = 0; i < w.size(); i++) {
            cursor += w.get(i);
            if (target < cursor) {
                index = i;
                break;
            }
            index = i;
        }
        return Math.min(index, max);
    }

    /**
     * 掷一只守卫的词缀 id（纯函数，同 seed 同结果）。
     *
     * <p>两步走：先 {@link #pickCount} 定条数，再洗牌池子取前 n 条——洗牌用注入的
     * Random，天然去重且不偏科；条数超过池大小时按池大小收。</p>
     */
    public static List<String> rollIds(List<String> pool, int max, List<Double> weights, Random rng) {
        List<String> safePool = filterPool(pool);
        if (safePool.isEmpty()) {
            return List.of();
        }
        int count = pickCount(rng.nextDouble(), trimWeights(weights), clampMaxPerGuard(max, safePool.size()));
        if (count <= 0) {
            return List.of();
        }
        List<String> shuffled = new ArrayList<>(safePool);
        Collections.shuffle(shuffled, rng);
        return List.copyOf(shuffled.subList(0, count));
    }

    // ------------------------------------------------------------ 挂签 / 读回

    /**
     * 生成时掷签并挂到守卫身上：词缀写 PDC、speed 词缀当场给药水效果。
     *
     * @return 实际挂上的词缀 id（顺序即名字展示顺序）；开关关闭 / 掷到 0 条时返回空表且不打标记
     */
    public List<String> apply(Mob mob) {
        List<String> ids = enabled() ? rollIds(pool(), maxPerGuard(), countWeights(), random) : List.of();
        return apply(mob, ids);
    }

    /**
     * 按给定 id 直接挂签（{@link #apply(Mob)} 的注入路径，自检也用它）。
     *
     * <p>id 先过白名单过滤，认不出剔除；过滤后为空则不打任何标记
     * （PDC 干净，监听侧按无词缀守卫放行）。speed 词缀在挂签时直接上药水效果。</p>
     *
     * @return 过滤后实际挂上的词缀 id
     */
    public List<String> apply(Mob mob, List<String> ids) {
        List<String> safe = filterPool(ids);
        var pdc = mob.getPersistentDataContainer();
        if (safe.isEmpty()) {
            pdc.remove(keyMark);
            pdc.remove(keyList);
            return List.of();
        }
        pdc.set(keyMark, PersistentDataType.BYTE, (byte) 1);
        pdc.set(keyList, PersistentDataType.STRING, String.join(",", safe));
        if (safe.contains(SPEED)) {
            // false = 不显示粒子、无环境覆盖：守卫词条低调点，别把屏幕刷成蓝的
            mob.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                    speedDurationTicks(), Math.max(0, speedLevel() - 1), false, false));
        }
        return safe;
    }

    /** 这只实体是不是带词缀标记的残影守卫（监听侧唯一判定入口，严防误伤） */
    public boolean isGuard(LivingEntity entity) {
        return entity != null && entity.getPersistentDataContainer().has(keyMark, PersistentDataType.BYTE);
    }

    /** 读回这只守卫身上的词缀 id（PDC 往返；无标记 / 空 / 认不出的 id 一律剔除） */
    public List<String> affixIdsOf(LivingEntity entity) {
        if (!isGuard(entity)) {
            return List.of();
        }
        String csv = entity.getPersistentDataContainer().getOrDefault(keyList, PersistentDataType.STRING, "");
        return filterPool(Arrays.asList(csv.split(",")));
    }

    /**
     * 名字上的彩色词缀段：如 {@code <#c92a2a>饮血</#c92a2a>·<#ffa94d>灼魂</#ffa94d>}。
     *
     * <p>调用方把它追加到残影 / 守卫的自定义名后面（前面自行拼 {@code · } 分隔）；
     * 空词缀返回空串，名字保持原样。</p>
     */
    public String affixTag(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) {
                sb.append('·');
            }
            String color = COLORS.getOrDefault(id, "#d8d8d8");
            String label = LABELS.getOrDefault(id, id);
            sb.append('<').append(color).append('>').append(label).append("</").append(color).append('>');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 伤害乘区（纯函数，自检直接断言）

    /**
     * 攻击侧伤害：基础 × attack_damage 乘区 × 暴击倍率（未挂对应词缀的乘区不动）。
     *
     * <p>倍率下限 1（配置写小了当没配），基础伤害非负。</p>
     */
    public static double outgoingDamage(double base, boolean attackDamage, double adMult,
                                        boolean crit, double critMult) {
        double dmg = Math.max(0, base);
        if (attackDamage) {
            dmg *= Math.max(1.0, adMult);
        }
        if (crit) {
            dmg *= Math.max(1.0, critMult);
        }
        return dmg;
    }

    /**
     * 受击侧伤害：基础 × (1 - 减免比例)，比例钳在 [0,1]（减免后不为负）。
     */
    public static double incomingDamage(double base, boolean damageReduce, double reducePercent) {
        double dmg = Math.max(0, base);
        if (damageReduce) {
            dmg *= 1.0 - Math.max(0, Math.min(1, reducePercent));
        }
        return dmg;
    }

    /**
     * 饮血回血量 = 打出的伤害 × 回血比例（比例钳 [0,1]，负伤害 / 负比例 → 0）。
     */
    public static double lifestealHeal(double damageDealt, double fraction) {
        if (damageDealt <= 0 || fraction <= 0) {
            return 0;
        }
        return damageDealt * Math.min(1, fraction);
    }

    /**
     * 暴击判定：{@code roll < chance} 才触发；chance ≤ 0 永不触发（纯函数，监听喂 roll）。
     */
    public static boolean critHit(double roll, double chance) {
        // 负 roll / 非有限值视为未暴击（防御性：正常调用方喂的是 [0,1) 随机数）
        if (!(chance > 0) || !(roll >= 0) || !Double.isFinite(roll)) {
            return false;
        }
        return roll < Math.min(1.0, chance);
    }
}
