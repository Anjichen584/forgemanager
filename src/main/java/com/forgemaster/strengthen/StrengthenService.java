package com.forgemaster.strengthen;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.econ.CoinReason;
import com.forgemaster.evolve.EvolutionStage;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.material.MaterialGrades;
import com.forgemaster.quality.Quality;
import com.forgemaster.season.SeasonManager;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Random;

/**
 * 锻造等级（强化）。
 *
 * <p>每件锻造品自带一个等级上限，由材料档位决定：铁剑能锻到 +20，
 * 越往上的材料上限越高，但每级的收益是同一个百分比，所以高档材料
 * 的绝对值涨得多、低级材料涨得少——基础属性本身已经被削过一刀，
 * 满强化只是把它补回来再加一点点，不会出现叠加爆表。</p>
 *
 * <p><b>不是必成</b>：成功率随当前等级线性下降，见 {@link #successChance(int)}。
 * 失败会掷一次碎裂判定，碎了剑胚直接没；碎掉的剑会留档进剑冢。</p>
 *
 * <p><b>三条保底 / 助力线</b>（v2.5）：</p>
 * <ul>
 *   <li>{@link #furnaceOf(ItemStack)} 炉火：每失败一次 +1，攒满当前等级的
 *       {@link #furnaceNeed(int)} 点，下一级必成，成功后清零；</li>
 *   <li>{@link Charm#STEADY 稳胚符}：失败本该掉级时保住等级；</li>
 *   <li>{@link Charm#KINDLE 旺火符}：本次成功率 +N 个百分点；</li>
 *   <li>{@link Charm#TALISMAN 护胚符}：碎裂判定命中时顶一次。</li>
 * </ul>
 *
 * <p>三种符各自独立消耗、可同时生效。</p>
 *
 * <p><b>消耗对应材料</b>：强化吃的是这把剑自己的锻造材料（铁剑吃铁锭、星陨剑吃星陨锭），
 * 数量随等级递增、档位越贵要得越多。另外收一笔剑币手续费。</p>
 *
 * <p>只放大「材料基础属性」（攻击 / 攻速 / 护甲 / 韧性），不动词条与宝石。</p>
 */
public final class StrengthenService {

    /** 基础属性 modifier 的键名，强化时按这些键精确替换，不动词条加的同类属性 */
    private static final String KEY_ATK = "fm_atk";
    private static final String KEY_SPEED = "fm_speed";
    private static final String KEY_ARMOR = "fm_armor";
    private static final String KEY_TOUGH = "fm_toughness";

    private final ForgingMasterPlugin plugin;
    private final Random random = new Random();

    public StrengthenService(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ 读

    public boolean enabled() {
        return plugin.settings().flag("strengthen.enabled", true);
    }

    /** 铁砧强化总开关（v2.15：开启后全物品强化上限统一为 {@link #maxLevel()}） */
    public boolean anvilForgeEnabled() {
        return plugin.settings().flag("anvil-forge.enabled", true);
    }

    /** 全局强化上限（铁砧模式下不再按材料档位区分，默认 +99） */
    public int maxLevel() {
        int v = plugin.settings().intOf("anvil-forge.max-level", 99);
        return Math.max(1, Math.min(999, v));
    }

    /** 当前锻造等级；非锻造品返回 -1 */
    public int levelOf(ItemStack item) {
        if (!plugin.items().isForged(item)) {
            return -1;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return -1;
        }
        Integer v = meta.getPersistentDataContainer().get(Keys.STRENGTHEN, PersistentDataType.INTEGER);
        return v == null ? 0 : Math.max(0, v);
    }

    /**
     * 该物品的锻造等级上限。
     *
     * <p>v2.17 起铁砧模式下上限跟着<b>锻造品质</b>逐级走：粗糙 40 → 普通 50 → 精良 60
     * → 史诗 72 → 传说 86 → 神话 99。以前是全物品共享一条 {@code anvil-forge.max-level}，
     * 于是「一把粗糙剑」和「一把神话剑」能锤到一样高，品质失去意义。</p>
     *
     * <p>不再按材料档位区分；{@code Keys.STRENGTHEN_CAP} 只作为老物品的遗留读值，
     * 由 {@link #ensureCap(ItemStack)} 单向同步到新值（见那里的说明）。</p>
     */
    public int capOf(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !plugin.items().isForged(item)) {
            return 0;
        }
        if (anvilForgeEnabled()) {
            return capForQuality(qualityOf(item));
        }
        Integer stored = meta.getPersistentDataContainer()
                .get(Keys.STRENGTHEN_CAP, PersistentDataType.INTEGER);
        if (stored != null && stored > 0) {
            return stored;
        }
        ForgeMaterial mat = materialOf(item);
        return capForTier(mat == null ? 1 : mat.tier());
    }

    /**
     * 某个品质的强化上限；{@code anvil-forge.caps.<品质>} 可逐项覆盖。
     *
     * <p>缺省值在 {@link #defaultQualityCap(Quality)}，六个品质严格递增。</p>
     */
    public int capForQuality(Quality q) {
        Quality quality = q == null ? Quality.COMMON : q;
        int def = defaultQualityCap(quality);
        int v = plugin.settings().intOf("anvil-forge.caps." + quality.name(), def);
        return Math.max(1, Math.min(999, v));
    }

    /** 品质对应的默认强化上限：越好的剑胚越经得起反复锤打（神话封顶 +99） */
    public static int defaultQualityCap(Quality q) {
        if (q == null) {
            return 50;
        }
        return switch (q) {
            case ROUGH -> 40;
            case COMMON -> 50;
            case FINE -> 60;
            case EPIC -> 72;
            case LEGENDARY -> 86;
            case MYTHIC -> 99;
        };
    }

    /**
     * 把一件老物品的上限同步到当前品质该有的值。
     *
     * <p>为什么需要这个：{@code ItemFactory} 在铸成时就把上限（连同 Lore 里那个
     * 「+0/32」）写死了，而 v2.17 之前写的是<b>旧的按材料档位</b>的数字。于是
     * 「/zhujian give 出来的神话剑」显示 +0/32，明明是新版却顶着老上限——
     * 这条负责把 PDC 与 Lore 一起改写成新值，等级超了就夹回上限。</p>
     *
     * @return 真的改了东西返回 true（用于日志 / 自检断言）
     */
    public boolean ensureCap(ItemStack item) {
        if (!plugin.items().isForged(item)) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        int want = capOf(item);
        int level = levelOf(item);
        if (want <= 0 || level < 0) {
            return false;
        }
        int clamped = Math.min(level, want);
        Integer stored = meta.getPersistentDataContainer()
                .get(Keys.STRENGTHEN_CAP, PersistentDataType.INTEGER);
        if (stored != null && stored == want && clamped == level) {
            return false;
        }
        writeLevel(item, clamped, want);
        apply(item); // 重算属性 + 重写 Lore（apply 内部按 capOf 取新上限）
        return true;
    }

    /** 按档位取上限：旧版曲线保留；开启铁砧强化后全部改由 {@link #maxLevel()} 统一 */
    public int capForTier(int tier) {
        if (anvilForgeEnabled()) {
            return maxLevel();
        }
        int idx = Math.max(1, Math.min(5, tier));
        return Math.max(0, plugin.settings().intOf("strengthen.caps." + idx, defaultCap(idx)));
    }

    public static int defaultCap(int tier) {
        return switch (Math.max(1, Math.min(5, tier))) {
            case 1 -> 20;
            case 2 -> 22;
            case 3 -> 25;
            case 4 -> 28;
            default -> 32;
        };
    }

    /** 强化到 level 级时的属性倍率（0.4 = +40%）；每级 +0.6%，线性走到封顶 */
    public double bonusRatio(int level) {
        if (level <= 0) {
            return 0;
        }
        double per = Math.max(0, plugin.settings().num("strengthen.bonus-per-level", 0.006));
        double max = Math.max(0, plugin.settings().num("strengthen.max-bonus", 0.60));
        return Math.min(max, per * level);
    }

    /** 把当前等级升到 level 要多少剑币（手续费；v2.15 铁砧强化改用平缓的档位线性曲线） */
    public long costOf(ItemStack item, int level) {
        ForgeMaterial mat = materialOf(item);
        int tier = mat == null ? 1 : Math.max(1, Math.min(5, mat.tier()));
        if (anvilForgeEnabled()) {
            double base = Math.max(0, plugin.settings().num("anvil-forge.cost.base", 40));
            double per = Math.max(0, plugin.settings().num("anvil-forge.cost.per-level", 6));
            double tierFactor = Math.max(1.0, plugin.settings().num("anvil-forge.cost.tier-factor", 1.20));
            double raw = (base + per * Math.max(0, level)) * Math.pow(tierFactor, tier - 1);
            return Math.max(1, Math.round(raw));
        }
        double base = Math.max(0, plugin.settings().num("strengthen.cost.base", 40));
        double per = Math.max(0, plugin.settings().num("strengthen.cost.per-level", 18));
        double factor = Math.max(1.0, plugin.settings().num("strengthen.cost.tier-factor", 1.45));
        double raw = (base + per * Math.max(0, level)) * Math.pow(factor, tier - 1);
        return Math.max(1, Math.round(raw));
    }

    // ------------------------------------------------------------ 概率与材料

    /**
     * 不含赛季追赶的「基础成功率」曲线（v2.16 铁砧强化重写）。
     *
     * <p>线性下滑：{@code start} 起步，到 {@code max-level} 时落到 {@code floor}，
     * 之后一直贴着下限。默认 +0 时 90%，+99 时 10%——低段随便锤，高段每一锤都在赌，
     * 想稳就得靠连击攒出「无损连击强化」（见 {@link #noLossComboActive(ItemStack)}）。</p>
     *
     * <p>单独抽出来是为了让自检能分别验「基础曲线」和「赛季追赶」：
     * 这条只看等级，完全不受赛季状态影响；{@link #successChance(int)} = 它 + 追赶加成。</p>
     */
    public double baseSuccessChance(int level) {
        int max = Math.max(1, maxLevel());
        double start = Math.max(0, Math.min(1.0,
                plugin.settings().num("anvil-forge.success.start", 0.90)));
        double floor = Math.max(0.01, Math.min(1.0,
                plugin.settings().num("anvil-forge.success.floor", 0.14)));
        double t = Math.max(0.0, Math.min(1.0, (double) Math.max(0, level) / max));
        double v = start - (start - floor) * t;
        return Math.max(floor, Math.min(1.0, v));
    }

    /**
     * 从当前等级往上升一级的成功率（基础曲线 + 赛季追赶加成）。
     *
     * <p>{@code min(1.0, baseSuccessChance(level) + seasonCatchupBonus(level))}，
     * 追赶加成只增不减、且只对低等级段生效，见 {@link #seasonCatchupBonus(int)}。</p>
     *
     * <p>注意：现在它不再是「只吃 level」的纯函数了——赛季进度会随时间变化。
     * 但加成的性质保证了原有断言仍然成立：同一时刻下它仍然随等级严格递减
     * （加成随等级递增地变小），且永远不低于 {@code floor}。</p>
     */
    public double successChance(int level) {
        return Math.min(1.0, baseSuccessChance(level) + seasonCatchupBonus(level));
    }

    /**
     * 无损连击强化：连击攒到阈值，砧上的剑进入「下一锤必成、不碎、不掉级」状态。
     *
     * <p>ForgeTax 式的手感核心——高等级段失败率高，玩家靠连击把风险一次性抹平。
     * 打出去那一锤之后连击归零，重新攒。</p>
     */
    public boolean noLossComboActive(ItemStack item) {
        int combo = comboOf(item);
        int threshold = noLossComboThreshold();
        return threshold > 0 && combo >= threshold;
    }

    /** 无损连击强化的触发连击数；0 = 关闭（默认 5 连） */
    public int noLossComboThreshold() {
        int v = plugin.settings().intOf("anvil-forge.combo.no-loss-threshold", 5);
        return Math.max(0, v);
    }

    /** 连击每点抬多少成功率（砧上明牌也用这一份） */
    public double comboBonusPer() {
        return Math.max(0, plugin.settings().num("anvil-forge.combo.success-bonus-per", 0.03));
    }

    /** 连击成功率加成封顶 */
    public double comboBonusMax() {
        return Math.max(0, plugin.settings().num("anvil-forge.combo.success-bonus-max", 0.15));
    }

    // ------------------------------------------------------------ 赛季追赶

    /**
     * 追赶加成最多吃掉「离 100% 的剩余空间」的九成。
     *
     * <p>剩下的那一成留给炉火与旺火符：追赶只是「更好锤」，不该把某一级直接变成必成。
     * 顺带让低等级段始终保留一点点失败率，{@code successChance(3)} 在赛季末也是 99.75%
     * 而不是 100%，「成功率随等级递减」这条曲线不会被赛季末抹平。</p>
     */
    private static final double CATCHUP_HEADROOM_KEEP = 0.9;

    /* ------------------------------------------------------------------
     * 赛季追赶（本节总说明，每个方法另有 javadoc）
     *
     * 为什么要有：老玩家赛季前半段就把 +20 卡住了，新人进服面对的是同一条成功率
     * 曲线，剩下的日子里怎么锤都追不上，只能等下个赛季重置——追赶机制缺失。
     * 让赛季进度本身变成一条顺风：后进场的玩家锤低等级段更顺，赛季末最明显；
     * 高等级段（默认 > +12）完全不受影响，已经爬起来的那批人的优势不被抹平。
     *
     * 公式（只对 level ≤ level-threshold 生效）：
     *     追赶加成   = min(max-bonus, 本季已过天数 × per-day)
     *     本季已过天数 = 赛季进度 × 赛季总天数
     *     最终成功率 = min(1.0, 基础成功率 + 追赶加成)
     * 用「进度 × 总天数」而不是直接乘进度来表达，是因为 per-day 是「每天抬 1 个百分点」：
     * 默认 30 天一季、每天 +1 个百分点，赛季末自然顶到 max-bonus 的 25 个百分点；
     * 两道夹子一起生效，服主改赛季时长也不会算崩。
     *
     * 赛季中途会不会跳变：赛季内它是随时钟单调上升的缓坡，不跳。唯一的不连续在结算
     * 那一瞬间——SeasonManager.end() 把 startedAt 改成当前时间，进度归零、加成随之
     * 归零，这是刻意的（新赛季大家一起重爬）。strengthen() 只在开头读一次加成，
     * 同一次锻里不会出现「按 A 的概率收钱、按 B 的概率结算」；赛季到期但结算任务还没跑
     * （最多晚一分钟）的那一小段里，进度会算到 > 1.0，seasonProgress() 已夹到 1.0，
     * 加成停在 max-bonus，不会继续涨。
     *
     * 兜底：赛季状态拿不到（管理器未初始化 / 开始时间缺失 / 时长非正）时整块返回 0 ——
     * 强化照旧，只是没有追赶，绝不因为赛季数据异常而崩掉。
     * ------------------------------------------------------------------ */

    /** 追赶机制总开关（关掉后 {@link #seasonProgress()} 与加成恒为 0） */
    public boolean seasonCatchupEnabled() {
        return plugin.settings().flag("strengthen.season-catchup.enabled", true);
    }

    /** 追赶加成上限，默认 0.25 = 赛季末最多抬 25 个百分点 */
    public double seasonCatchupMaxBonus() {
        return Math.max(0, Math.min(1.0,
                plugin.settings().num("strengthen.season-catchup.max-bonus", 0.25)));
    }

    /** 追赶只帮到这个等级（含），默认 +12 */
    public int seasonCatchupLevelThreshold() {
        return Math.max(0, plugin.settings().intOf("strengthen.season-catchup.level-threshold", 12));
    }

    /** 每天抬几个百分点，默认 0.01（= 1 个百分点） */
    public double seasonCatchupPerDay() {
        return Math.max(0, plugin.settings().num("strengthen.season-catchup.per-day", 0.01));
    }

    /**
     * 本季已经过去的进度，{@code 0.0 ~ 1.0}（1.0 = 已经到期、等结算）。
     *
     * <p>赛季数据只读：用 {@link SeasonManager#startedAt()} 与
     * {@link SeasonManager#endsAt()} 这两个公开方法算，不改 {@code SeasonManager} 一个字。
     * 拿不到 / 数据不合理时返回 0。</p>
     */
    public double seasonProgress() {
        if (!seasonCatchupEnabled()) {
            return 0;
        }
        SeasonManager seasons = plugin.seasons();
        if (seasons == null) {
            return 0;
        }
        long start = seasons.startedAt();
        long end = seasons.endsAt();
        if (start <= 0 || end <= start) {
            return 0;
        }
        long now = System.currentTimeMillis();
        if (now <= start) {
            return 0;
        }
        double p = (double) (now - start) / (double) (end - start);
        return Math.max(0, Math.min(1.0, p));
    }

    /** 当前赛季编号；赛季管理器还没起来时返回 0（只给展示用） */
    public int seasonNumber() {
        SeasonManager seasons = plugin.seasons();
        return seasons == null ? 0 : Math.max(0, seasons.number());
    }

    /** 本季总天数；赛季管理器还没起来时返回 0 */
    public int seasonDurationDays() {
        SeasonManager seasons = plugin.seasons();
        return seasons == null ? 0 : Math.max(0, seasons.durationDays());
    }

    /**
     * 当前这一级能吃到的追赶加成，{@code 0 ~ max-bonus}。
     *
     * <p><b>可单独断言</b>：{@code successChance} 内部就是调它，自检可以把它和
     * {@link #baseSuccessChance(int)} 分开验，赛季状态怎么变都不会污染「基础曲线」的断言。</p>
     *
     * <p>三道夹子：</p>
     * <ul>
     *   <li>等级超过 {@code level-threshold}（默认 +12）→ 0，高等级段不受益；</li>
     *   <li>不超过 {@code max-bonus}，也不超过「离 100% 剩余空间 × 0.9」，只增不减；</li>
     *   <li>赛季数据拿不到 / 开关关闭 → 0。</li>
     * </ul>
     */
    public double seasonCatchupBonus(int level) {
        if (level < 0 || level > seasonCatchupLevelThreshold()) {
            return 0;
        }
        double maxBonus = seasonCatchupMaxBonus();
        if (maxBonus <= 0) {
            return 0;
        }
        double progress = seasonProgress();
        if (progress <= 0) {
            return 0;
        }
        int days = seasonDurationDays();
        if (days <= 0) {
            days = 30; // 兜底：赛季管理器给不出时长时按 30 天算，不让加成凭空归零
        }
        double raw = progress * days * seasonCatchupPerDay();
        double headroom = Math.max(0, 1.0 - baseSuccessChance(level)) * CATCHUP_HEADROOM_KEEP;
        return Math.max(0, Math.min(Math.min(maxBonus, raw), headroom));
    }

    /**
     * 失败之后碎掉剑胚的概率（v2.15 铁砧强化重写：随等级线性爬升）。
     *
     * <p>{@code base} 起步，到 {@code max-level} 时封顶 {@code break-max}。
     * 默认 +0 时 2%，+99 时 35%。连击攒出「无损连击强化」时这一项按 0 结算。</p>
     */
    public double breakChance(int level) {
        int max = maxLevel();
        double base = Math.max(0, plugin.settings().num("anvil-forge.break.base", 0.02));
        // 默认对齐 config.yml 发布值 0.30（原 0.35 漂移）
        double top = Math.max(0, plugin.settings().num("anvil-forge.break.max", 0.18));
        double t = Math.max(0.0, Math.min(1.0, (double) Math.max(0, level) / max));
        return Math.min(top, base + (top - base) * t);
    }

    /** 失败且没碎时是否降 1 级 */
    public boolean downgradeOnFail() {
        return plugin.settings().flag("strengthen.fail.downgrade", true);
    }

    /** 失败后的处置决定 */
    public record FailResult(boolean broken, boolean savedByTalisman, int newLevel) {
    }
    /**
     * 给定当前等级、碎裂骰、以及身上有没有护胚符，算出失败后怎么处置。
     *
     * <p>纯函数，不含随机也不改物品，方便自检直接验各种组合。
     * 稳胚符不在本函数里处理——它作用的不是「失败时怎么处置」，
     * 而是「处置完的降级要不要认」，由 {@link #strengthen(Player, ItemStack)} 收尾。</p>
     *
     * @param breakRoll 碎裂骰，落在 [0,1)
     */
    public FailResult resolveFail(int level, double breakRoll, boolean hasTalisman) {
        int down = downgradeOnFail() ? Math.max(0, level - 1) : level;
        boolean wantBreak = breakRoll < breakChance(level);
        if (!wantBreak) {
            return new FailResult(false, false, down);
        }
        // 碎裂命中：有护胚符就顶掉（符照样消耗），没有就真碎
        if (hasTalisman && Charm.enabled(plugin, Charm.TALISMAN)) {
            return new FailResult(false, true, down);
        }
        return new FailResult(true, false, level);
    }

    // ------------------------------------------------------------ 五态抽票（v2.13，ForgeTax 式）

    /** 连击：连续成功（含大成）次数；损伤/空锤/碎裂清零。PDC 跟剑走 */
    public int comboOf(ItemStack item) {
        return intOr(item, Keys.STRENGTHEN_COMBO, 0);
    }

    /** 炉魂狂热剩余次数（0 = 非狂热） */
    public int frenzyLeftOf(ItemStack item) {
        return intOr(item, Keys.FRENZY_LEFT, 0);
    }

    /** 炉魂狂热进度 */
    public int frenzyProgressOf(ItemStack item) {
        return intOr(item, Keys.FRENZY_PROGRESS, 0);
    }

    /** 基石护体是否已经用过（连击攒出的「首次碎裂转损伤」，每把剑一次） */
    public int cornerUsedOf(ItemStack item) {
        return intOr(item, Keys.CORNERSTONE_USED, 0);
    }

    public double greatBase() {
        return Math.max(0, Math.min(0.5, plugin.settings().num("strengthen.tickets.great-base", 0.08)));
    }

    public double greatPerCombo() {
        return Math.max(0, plugin.settings().num("strengthen.tickets.great-per-combo", 0.03));
    }

    /** 失败里「损伤 vs 空锤」的占比（0.5 = 一半一半） */
    public double damageShare() {
        return Math.max(0, Math.min(1, plugin.settings().num("strengthen.tickets.damage-share", 0.35)));
    }

    /** 连击到几档触发基石护体（首次碎裂转损伤）；0 = 关闭 */
    public int breakShieldCombo() {
        return Math.max(0, plugin.settings().intOf("anvil-forge.combo.break-shield", 4));
    }

    public int frenzyHits() {
        return Math.max(1, plugin.settings().intOf("strengthen.frenzy.hits", 10));
    }

    public int frenzyAttempts() {
        return Math.max(1, plugin.settings().intOf("strengthen.frenzy.attempts", 3));
    }

    public double frenzySuccessBonus() {
        return Math.max(0, plugin.settings().num("strengthen.frenzy.success-bonus", 0.10));
    }

    /** 剑魂传承：碎剑等级记几成给下一把新铸的剑；0 = 关闭 */
    public double inheritRate() {
        return Math.max(0, Math.min(1, plugin.settings().num("strengthen.inherit.rate", 0.35)));
    }

    public int inheritCap() {
        return Math.max(0, plugin.settings().intOf("strengthen.inherit.cap", 20));
    }

    /** 每次锤击推进狂热：进度攒满换次数；狂热期间每次消耗一次（圣物可加次数） */
    private void tickFrenzy(ItemStack item, boolean frenzyActive, int extraAttempts) {
        if (frenzyActive) {
            writeInt(item, Keys.FRENZY_LEFT, Math.max(0, frenzyLeftOf(item) - 1));
            return;
        }
        int progress = frenzyProgressOf(item) + 1;
        if (progress >= frenzyHits()) {
            writeInt(item, Keys.FRENZY_PROGRESS, 0);
            writeInt(item, Keys.FRENZY_LEFT, frenzyAttempts() + Math.max(0, extraAttempts));
            return;
        }
        writeInt(item, Keys.FRENZY_PROGRESS, progress);
    }

    // ------------------------------------------------------------ ForgeTax 明牌抽票

    /**
     * 捶一锤的结局档位。
     *
     * <p>ForgeTax 式铁砧强化只认这几种手感：碎裂 / 损伤 / 空锤 / 大成 / 成功，
     * 外加一个「无损连击强化」——连击攒够之后的下一锤必成、不碎、不掉级。</p>
     */
    public enum Tier {
        /** 剑胚碎了 */
        BREAK,
        /** 没碎但掉 1 级 */
        DAMAGE,
        /** 材料白费，等级不动 */
        HOLD,
        /** +2 级 */
        GREAT,
        /** +1 级 */
        SUCCESS,
        /** 无损连击强化：必成、不碎、不掉级 */
        NO_LOSS,
        /** 没锤成（已满 / 材料或剑币不够 / 门槛未过等），不掷骰、不扣费 */
        REFUSED
    }

    /**
     * 一锤的完整结果，给铁砧层拿去刷全息、播声音、写日志。
     *
     * @param tier        结局档位
     * @param fromLevel   落锤前等级
     * @param toLevel     落锤后等级
     * @param cap         上限
     * @param combo       本次之后的新连击
     * @param threshold   无损连击阈值（0 = 关闭）
     * @param noLossArmed 这一锤之后是否已经攒出无损连击
     * @param broken      剑胚是否真的碎了
     * @param coins       实际扣掉的剑币
     * @param materials   实际扣掉的材料数
     * @param message     给玩家看的一行 MiniMessage 文本（可能带 newline 分段）
     */
    public record StrikeResult(Tier tier, int fromLevel, int toLevel, int cap, int combo,
                               int threshold, boolean noLossArmed, boolean broken,
                               long coins, int materials, String message) {

        /** 等级是否涨了 */
        public boolean gained() {
            return tier == Tier.SUCCESS || tier == Tier.GREAT || tier == Tier.NO_LOSS;
        }

        /** 这一锤有没有真的掷骰（REFUSED 没有） */
        public boolean rolled() {
            return tier != Tier.REFUSED;
        }
    }

    /** 抽票表的档位顺序，和 {@link #odds} 返回的数组一一对应 */
    private static final Tier[] TIER_ORDER = {
            Tier.BREAK, Tier.DAMAGE, Tier.HOLD, Tier.GREAT, Tier.SUCCESS
    };

    /**
     * 纯函数：这一锤之后「等级 / 连击」怎么变。
     *
     * <p>抽出来是为了让铁砧面板、自检和 {@link #strike} 三处永远用同一套推进规则，
     * 不靠掷骰也能验证：无损连击必涨级、连击封在阈值上、损伤掉 1 级且连击归零。</p>
     *
     * @return {@code {新等级, 新连击}}
     */
    public int[] afterStrike(Tier tier, int level, int cap, int combo, int gain, int threshold) {
        int lv = Math.max(0, level);
        int cmb = Math.max(0, combo);
        int capV = Math.max(0, cap);
        int gainV = Math.max(1, gain);
        switch (tier) {
            case SUCCESS, GREAT, NO_LOSS -> {
                int next = Math.min(capV, lv + (tier == Tier.GREAT ? Math.max(2, gainV) : gainV));
                int nextCombo = tier == Tier.NO_LOSS ? 0 : cmb + 1;
                if (threshold > 0 && nextCombo > threshold) {
                    nextCombo = threshold; // 攒满就封在阈值上，全息显示「已就绪」
                }
                return new int[]{next, Math.max(0, nextCombo)};
            }
            case DAMAGE -> {
                return new int[]{Math.max(0, lv - 1), 0};
            }
            default -> {
                // HOLD / BREAK / REFUSED：等级不动，连击归零（圣物护住时由调用方另处理）
                return new int[]{lv, 0};
            }
        }
    }

    /**
     * ForgeTax 明牌概率表：{碎裂, 损伤, 空锤, 大成, 成功}，五项之和 = 1。
     *
     * <p>和服务端实际掷骰用的是同一份数据：{@code odds} 的第 0 项就是「这一锤碎掉」
     * 的<b>整体</b>概率（已经乘过失败率），所以铁砧全息上写的碎裂率不是条件概率，
     * 玩家看到多少就真是多少。</p>
     *
     * @param level       当前等级
     * @param combo       当前连击
     * @param noLossArmed 无损连击是否已攒满（这一锤必成）
     * @param kindleBonus 旺火符这类临时加成（百分点）
     * @param rb          圣物聚合加成
     * @param frenzy      炉魂狂热中（不碎）
     * @param cornerstone 基石护体生效中（不碎）
     */
    public double[] odds(int level, int combo, boolean noLossArmed, double kindleBonus,
                         com.forgemaster.contract.RelicBonus rb,
                         boolean frenzy, boolean cornerstone) {
        int lv = Math.max(0, level);
        double chance = Math.min(1.0, successChance(lv) + Math.max(0, kindleBonus)
                + Math.min(comboBonusMax(), Math.max(0, combo) * comboBonusPer())
                + (frenzy ? frenzySuccessBonus() : 0) + rb.success());
        double greatP = Math.max(0, Math.min(0.5,
                greatBase() + rb.great() + greatPerCombo() * Math.max(0, combo)));
        if (noLossArmed) {
            // 无损连击强化：这一锤必成、不碎、不掉级；大成与成功分摊 100%
            double g = Math.min(greatP, 1.0);
            return new double[]{0, 0, 0, g, 1.0 - g};
        }
        double breakCond = Math.max(0, Math.min(1.0, breakChance(lv) * rb.breakMult()));
        if (frenzy || cornerstone) {
            breakCond = 0;
        }
        greatP = Math.min(greatP, chance);
        double successP = Math.max(0, chance - greatP);
        double failP = Math.max(0, 1.0 - successP - greatP);
        double breakP = failP * breakCond;
        double rest = Math.max(0, failP - breakP);
        double damageP = rest * damageShare();
        double holdP = Math.max(0, rest - damageP);
        return new double[]{breakP, damageP, holdP, greatP, successP};
    }

    /** 当前物品的明牌抽票（读物品上的等级 / 连击 / 狂热） */
    public double[] oddsOf(ItemStack item, double kindleBonus) {
        return oddsOf(item, kindleBonus, com.forgemaster.contract.RelicBonus.ZERO);
    }

    /** 同上，带圣物聚合加成（在线玩家的真实 odds 走这个） */
    public double[] oddsOf(ItemStack item, double kindleBonus,
                           com.forgemaster.contract.RelicBonus rb) {
        int level = Math.max(0, levelOf(item));
        int combo = comboOf(item);
        boolean frenzy = frenzyLeftOf(item) > 0;
        boolean noLoss = noLossComboActive(item);
        boolean cornerstone = !frenzy && !noLoss && combo >= breakShieldCombo()
                && cornerUsedOf(item) < 1 + rb.cornerstoneExtra();
        return odds(level, combo, noLoss, kindleBonus, rb, frenzy, cornerstone);
    }

    /**
     * 纯函数：给定骰子和概率表，落在哪一档。
     *
     * <p>累计区间顺序和 {@link #TIER_ORDER} 一致，方便自检直接喂骰子验证边界。</p>
     */
    public static Tier tierOf(double roll, double[] odds) {
        if (odds == null || odds.length < TIER_ORDER.length) {
            return Tier.REFUSED;
        }
        double r = Math.max(0.0, Math.min(0.999999999, roll));
        // 边界归上档：0.10 + 0.20 在 double 里是 0.30000000000000004，
        // 不加容差的话「正好 0.30」会被判成损伤，明牌和实测就会差一档。
        final double eps = 1e-12;
        double cursor = 0;
        for (int i = 0; i < TIER_ORDER.length; i++) {
            cursor += Math.max(0, odds[i]);
            if (r + eps < cursor) {
                return TIER_ORDER[i];
            }
        }
        return Tier.SUCCESS;
    }

    // ------------------------------------------------------------ 强化规划器（v2.23）

    /**
     * 规划器统计结果（多次模拟的均值）。
     *
     * @param runs         实际跑的模拟局数
     * @param expHits      期望总锤数
     * @param expCoins     期望总剑币
     * @param expMaterials 期望总材料个数
     * @param expBreaks    期望碎剑次数（碎后按剑魂传承重开）
     * @param reachedRate  达成率（0~1；发散局按未达成计）
     */
    public record PlanResult(int fromLevel, int targetLevel, int runs,
                             double expHits, double expCoins, double expMaterials,
                             double expBreaks, double reachedRate, int cappedRuns) {
    }

    /**
     * Monte Carlo 推演：从这把剑当前等级锤到目标级，期望要多少锤 / 剑币 / 材料、碎几把剑。
     *
     * <p>口径（诚实声明）：按「无连击、无狂热、无符、无圣物、无炉魂保底」的裸状态推演。
     * 真实敲砧会攒无损连击与炉魂狂热，实际开销只会比这里低——所以结果按<b>上界</b>读。</p>
     *
     * <p>碎剑按剑魂传承重开（{@code floor(碎时等级 × inheritRate())}，封顶 {@code inheritCap()}）；
     * 单局超过 20 万锤视为发散（极端配置才会），该局计入 {@code cappedRuns}。</p>
     *
     * @param item        主手的锻造品（读当前等级 / 上限 / 材料档位）
     * @param targetLevel 目标等级（超出上限按上限算）
     * @param runs        模拟局数（1 ~ 20000，超出钳制）
     */
    public PlanResult plan(ItemStack item, int targetLevel, int runs) {
        int from = Math.max(0, levelOf(item));
        int cap = capOf(item);
        int target = Math.max(0, Math.min(cap, targetLevel));
        int n = Math.max(1, Math.min(20000, runs));
        if (from >= cap) {
            target = cap;
        }
        java.util.random.RandomGenerator rng = java.util.concurrent.ThreadLocalRandom.current();
        long sumHits = 0, sumCoins = 0, sumMats = 0, sumBreaks = 0;
        int reached = 0, capped = 0;
        for (int r = 0; r < n; r++) {
            int lv = from;
            int breaks = 0;
            long coins = 0, mats = 0, hits = 0;
            boolean done = lv >= target;
            while (!done && hits < 200_000) {
                coins += costOf(item, lv);
                mats += materialAmountOf(item, lv);
                hits++;
                double[] odds = odds(lv, 0, false, 0,
                        com.forgemaster.contract.RelicBonus.ZERO, false, false);
                Tier t = tierOf(rng.nextDouble(), odds);
                switch (t) {
                    case SUCCESS, NO_LOSS -> lv = Math.min(cap, lv + 1);
                    case GREAT -> lv = Math.min(cap, lv + 2);
                    case DAMAGE -> lv = Math.max(0, lv - 1);
                    case BREAK -> {
                        breaks++;
                        lv = Math.min(inheritCap(),
                                Math.max(0, (int) Math.floor(lv * inheritRate())));
                    }
                    case HOLD -> { }
                    default -> { }
                }
                done = lv >= target;
            }
            if (hits >= 200_000) {
                capped++;
            }
            if (lv >= target) {
                reached++;
            }
            sumHits += hits;
            sumCoins += coins;
            sumMats += mats;
            sumBreaks += breaks;
        }
        return new PlanResult(from, target, n,
                sumHits / (double) n, sumCoins / (double) n, sumMats / (double) n,
                sumBreaks / (double) n, reached / (double) n, capped);
    }

    /** 这一锤「整体碎裂率」（0~1），铁砧全息直接用 */
    public double overallBreakChance(ItemStack item) {
        double[] o = oddsOf(item, 0);
        return o[0];
    }

    /** 圣物聚合加成（对外只读入口：铁砧强化要用同一份） */
    public com.forgemaster.contract.RelicBonus relicBonusOf(Player player) {
        var cm = plugin.contractManager();
        return cm == null ? com.forgemaster.contract.RelicBonus.ZERO
                : cm.relicBonus(player.getUniqueId());
    }

    /**
     * 剑魂传承：碎剑把 {@code floor(等级 × rate)} 记到玩家 PDC 上，
     * 下一次 ForgeManager 结算新剑时由 {@link #applyInherit} 消费。
     * 残誓圣物给传承比例加成。
     */
    public void grantInherit(Player player, int brokenLevel) {
        double rate = inheritRate();
        var cm = plugin.contractManager();
        if (cm != null) {
            rate += cm.relicBonus(player.getUniqueId()).inherit();
        }
        if (rate <= 0 || brokenLevel <= 0) {
            return;
        }
        int grant = (int) Math.min(inheritCap(), Math.round(brokenLevel * rate));
        if (grant <= 0) {
            return;
        }
        var pdc = player.getPersistentDataContainer();
        Integer cur = pdc.get(Keys.INHERIT_LEVEL, PersistentDataType.INTEGER);
        pdc.set(Keys.INHERIT_LEVEL, PersistentDataType.INTEGER, Math.min(inheritCap(), (cur == null ? 0 : cur) + grant));
    }

    /**
     * 消费剑魂传承：新铸的剑直接自带等级（走 {@link #setLevel}，免费）。
     *
     * @return 实际继承到的等级；没有传承返回 0
     */
    public int applyInherit(java.util.UUID playerId, ItemStack fresh) {
        if (inheritRate() <= 0 || levelOf(fresh) != 0) {
            return 0;
        }
        org.bukkit.entity.Player player = plugin.getServer().getPlayer(playerId);
        if (player == null) {
            return 0;
        }
        var pdc = player.getPersistentDataContainer();
        Integer cur = pdc.get(Keys.INHERIT_LEVEL, PersistentDataType.INTEGER);
        if (cur == null || cur <= 0) {
            return 0;
        }
        int level = Math.min(cur, capOf(fresh));
        pdc.set(Keys.INHERIT_LEVEL, PersistentDataType.INTEGER, 0);
        if (level > 0) {
            setLevel(fresh, level);
        }
        return level;
    }

    private static int intOr(ItemStack item, org.bukkit.NamespacedKey key, int def) {
        if (item == null || item.getType().isAir()) {
            return def;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return def;
        }
        Integer v = meta.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
        return v == null ? def : v;
    }

    /** 强化到 level 要消耗几个对应材料（v2.15 铁砧强化：每级恒定，档位越高要得越多） */
    public int materialAmountOf(ItemStack item, int level) {
        ForgeMaterial mat = materialOf(item);
        int tier = mat == null ? 1 : Math.max(1, Math.min(5, mat.tier()));
        if (anvilForgeEnabled()) {
            double base = Math.max(0, plugin.settings().num("anvil-forge.material.base", 2));
            double factor = Math.max(1.0, plugin.settings().num("anvil-forge.material.tier-factor", 1.20));
            double raw = base * Math.pow(factor, tier - 1);
            return Math.max(1, (int) Math.ceil(raw));
        }
        double base = Math.max(0, plugin.settings().num("strengthen.material.base", 2));
        double per = Math.max(0, plugin.settings().num("strengthen.material.per-level", 0.6));
        double factor = Math.max(1.0, plugin.settings().num("strengthen.material.tier-factor", 1.10));
        double raw = (base + per * Math.max(0, level)) * Math.pow(factor, tier - 1);
        return Math.max(1, (int) Math.ceil(raw));
    }

    /**
     * 强化消耗的材料物品：<b>就是这把剑自己的锻造材料</b>
     * （铁剑吃铁锭、星陨剑吃星陨锭；多耗材的取第一种）。
     */
    public Material materialItemOf(ItemStack item) {
        ForgeMaterial mat = materialOf(item);
        return mat == null ? null : mat.costItem();
    }

    // ------------------------------------------------------------ 炉火保底

    /**
     * 炉火值：每失败一次攒 1 点，攒满 {@link #furnaceNeed(int)} 点则下一级必成。
     *
     * <p>存在物品 PDC 上，跟着剑走；成功后清零，碎剑则随剑一起消失。</p>
     */
    public int furnaceOf(ItemStack item) {
        return intOf(item, Keys.FURNACE);
    }

    /**
     * 该等级攒满炉火要几点。
     *
     * <p>等级 ≤10 → {@code strengthen.furnace.need-low}（默认 3），
     * ≤20 → {@code need-mid}（默认 5），更高 → {@code need-high}（默认 8）。</p>
     */
    public int furnaceNeed(int level) {
        int low = Math.max(1, plugin.settings().intOf("strengthen.furnace.need-low", 3));
        int mid = Math.max(1, plugin.settings().intOf("strengthen.furnace.need-mid", 5));
        int high = Math.max(1, plugin.settings().intOf("strengthen.furnace.need-high", 8));
        if (level <= 10) {
            return low;
        }
        return level <= 20 ? mid : high;
    }

    /** 炉火是否已经攒满（下一锻必成） */
    public boolean furnaceReady(ItemStack item) {
        int level = levelOf(item);
        return level >= 0 && furnaceOf(item) >= furnaceNeed(level);
    }

    // ------------------------------------------------------------ 履历计数

    /** 累计失败次数（履历） */
    public int failsOf(ItemStack item) {
        return intOf(item, Keys.STRENGTHEN_FAILS);
    }

    /** 累计碎剑次数（履历） */
    public int brokenOf(ItemStack item) {
        return intOf(item, Keys.STRENGTHEN_BROKEN);
    }

    /** 累计投入材料数（履历） */
    public int investOf(ItemStack item) {
        return intOf(item, Keys.STRENGTHEN_INVEST);
    }

    // ------------------------------------------------------------ v2.15 铁砧强化写入口

    /** 写连击数（铁砧强化的一锤一锤累计） */
    public void setCombo(ItemStack item, int combo) {
        writeInt(item, Keys.STRENGTHEN_COMBO, Math.max(0, combo));
    }

    /** 写炉火值（失败攒点，攒满下一锤必成） */
    public void setFurnace(ItemStack item, int value) {
        writeInt(item, Keys.FURNACE, Math.max(0, value));
    }

    /** 累加失败次数（履历） */
    public void addFails(ItemStack item, int delta) {
        addInt(item, Keys.STRENGTHEN_FAILS, delta);
    }

    /** 累加碎剑次数（履历） */
    public void addBroken(ItemStack item, int delta) {
        addInt(item, Keys.STRENGTHEN_BROKEN, delta);
    }

    /** 累加投入材料数（履历） */
    public void addInvest(ItemStack item, int delta) {
        addInt(item, Keys.STRENGTHEN_INVEST, delta);
    }

    // ------------------------------------------------------------ 写

/**
     * 强化一级（命令入口 / 自检入口）。
     *
     * <p>v2.16 起强化只有一条正路：{@code 右键把剑放上铁砧 → 手持重锤左键落锤}，
     * 入口在 {@code com.forgemaster.anvil}。这个方法保留成「手上那把剑」的薄包装，
     * 碎剑时自己从背包里清掉；日常玩家走铁砧，不经过这里。</p>
     */
    public String strengthen(Player player, ItemStack item) {
        return strike(player, item, null).message();
    }

    /**
     * 落一锤：扣材料 + 手续费 → 明牌掷骰 → 成功升级 / 失败降级或碎裂。
     *
     * <p>铁砧层（{@link com.forgemaster.anvil.AnvilForgeManager}）与命令层共用这一份结算，
     * 保证「砧上敲」和「后台模拟」永远是同一套概率。</p>
     *
     * @param onBroken 剑胚碎掉之后的清理动作；{@code null} = 由本方法按主手/背包自己找
     */
    public StrikeResult strike(Player player, ItemStack item,
                               java.util.function.Consumer<ItemStack> onBroken) {
        StrikeResult result = strike0(player, item, onBroken);
        // v2.23 拓展 C：Webhook 播报挂在这里而不是塞进 strike0 的 7 个 return 点。
        // 好处只有一个，但很关键：**所有**调用路径（铁砧落锤 / 命令 / 批量）都会经过本方法，
        // 一次插桩就覆盖全部入口，也不会因为以后新增分支而漏播。
        broadcastStrike(player, item, result);
        return result;
    }

    /**
     * 把一次落锤的结果转成 Webhook 事件。
     *
     * <p>只播「成功」与「碎剑」两类：REFUSED（材料不够 / 到顶）是玩家自己的操作失误，
     * 播出去只会刷屏。异常一律吞掉——播报永远不能反过来影响主流程。</p>
     */
    private void broadcastStrike(Player player, ItemStack item, StrikeResult result) {
        if (player == null || result == null) {
            return;
        }
        try {
            var hook = plugin.webhook();
            if (hook == null || !hook.enabled()) {
                return;
            }
            String who = player.getName();
            String name = item == null ? "" : item.getType().name();
            switch (result.tier()) {
                case BREAK -> hook.dispatch(com.forgemaster.webhook.WebhookEvent.STRENGTHEN_BREAK,
                        net.kyori.adventure.text.Component.text(
                                "【碎剑】" + who + " 的 " + name + " 在 +" + result.fromLevel() + " 碎了"),
                        java.util.Map.of("player", who, "item", name,
                                "level", "+" + result.fromLevel()));
                case SUCCESS, GREAT -> hook.dispatch(
                        com.forgemaster.webhook.WebhookEvent.STRENGTHEN_SUCCESS,
                        net.kyori.adventure.text.Component.text("【强化】" + who + " " + name
                                + " +" + result.fromLevel() + " → +" + result.toLevel()),
                        java.util.Map.of("player", who, "item", name,
                                "from", "+" + result.fromLevel(), "to", "+" + result.toLevel()));
                default -> {
                    // REFUSED / 其它：不播
                }
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().fine("Webhook 播报强化结果失败（已忽略）：" + ex);
        }
    }

    /**
     * 真正的落锤实现（v2.23.1 起由 {@link #strike} 包一层再对外）。
     * 保持原来的 7 个 return 点不动，减少与既有逻辑的冲突面。
     */
    private StrikeResult strike0(Player player, ItemStack item,
                                 java.util.function.Consumer<ItemStack> onBroken) {
        if (!enabled()) {
            return refused(item, "<red>锻造等级功能已关闭。");
        }
        int level = levelOf(item);
        if (level < 0) {
            return refused(item, "<red>这不是锻造品，锻不了。");
        }
        // 材料 / 品质都解析不出来时不能收钱：属性没法重算，等于白扣
        ForgeMaterial mat = materialOf(item);
        Quality quality = qualityOf(item);
        if (mat == null || quality == null) {
            return refused(item, "<red>这把剑的胚子信息丢了（服主可能改过 materials 配置），锻不了。");
        }
        int cap = capOf(item);
        if (level >= cap) {
            return refused(item, "<red>已经到 <white>+" + cap + "</white>，再锻就断了。");
        }
        // v2.20：锻阶不再卡强化。
        //
        // 以前这里有一段「已到这一阶的强化上限 +5——先蜕变才解锁 +6」，把蜕变做成了
        // 强化的必经关卡。服主的反馈是「那个强化里的进化直接去掉，我要沉浸式强化」——
        // 每次锻满就被一个有独立界面、独立成功率、独立费用的系统拦住，手感是断的。
        //
        // 现在依赖关系只有单向：蜕变要求剑先锻到某等级（它的门槛还在），
        // 但强化只认品质上限（ROUGH 40 … MYTHIC 99），一路锻到底不会再被任何东西拦住。
        // pendingGate 保留给蜕变界面自己展示「下一阶在哪」，不再参与强化判定。

        // ---- 材料与手续费 ----
        Material costItem = materialItemOf(item);
        int need = materialAmountOf(item, level);
        long cost = costOf(item, level);
        // 剑尘抵扣（v2.18 dissolve）：材料不够时自动用背包里的剑尘按 1:1 补足差额
        //（上限 dissolve.max-offset-per-strike）。剑币手续费不抵；
        // 总供给（材料 + 可抵剑尘）不足才拒绝，原有的「材料不够就 REFUSED」语义不变。
        int dustUse = 0;
        if (costItem != null) {
            int have = MaterialGrades.totalCount(player, costItem);
            if (have < need) {
                dustUse = com.forgemaster.dissolve.DissolveService.offsetAmount(plugin, player,
                        need - have);
                if (have + dustUse < need) {
                    return refused(item, "<red>材料不够：要 <white>" + need + "</white> 个 "
                            + MaterialGrades.pretty(costItem) + "，你只有 <white>" + have + "</white> 个"
                            + "<gray>（剑尘最多再抵 <white>" + dustUse + "</white> 个，还是不够）</gray>。");
                }
            }
        }
        long coins = plugin.stats().coins(player.getUniqueId());
        if (coins < cost) {
            // 剑币 ≠ 服务器金币：强化花的是插件内置剑币（锻造赚来的），
            // 服主给了 Vault/CMI 的钱照样不够，所以这里直接把正确命令写出来。
            return refused(item, "<red>剑币不够：需要 <white>" + cost + "</white>，你只有 <white>"
                    + coins + "</white>。"
                    + "<newline><dark_gray>剑币靠锻造赚；管理员发放：<white>/zhujian money coin give "
                    + player.getName() + " " + cost + "</white>");
        }

        // ---- 这一锤的判定方式：炉火必成 / 无损连击 / 常规明牌 ----
        int combo = comboOf(item);
        int threshold = noLossComboThreshold();
        boolean noLoss = noLossComboActive(item);
        boolean frenzy = frenzyLeftOf(item) > 0;
        // 圣物聚合加成（龙税周目）：没在契返回 ZERO，不改变任何曲线
        com.forgemaster.contract.RelicBonus rb = relicBonusOf(player);
        boolean furnaceReady = furnaceOf(item) >= furnaceNeed(level);
        // 赛季追赶：一次锻只在这里读一次，同一次锻里成功率不会被赛季状态中途改写
        double catchup = seasonCatchupBonus(level);
        double baseChance = successChance(level);
        // 本来就必成（炉火 / 无损连击）时不该烧符：加成在 100% 上等于 0，烧了纯亏
        boolean kindle = !furnaceReady && !noLoss && baseChance < 1.0
                && Charm.enabled(plugin, Charm.KINDLE)
                && Charm.count(player, Charm.KINDLE) > 0;
        double kindleBonus = kindle ? Charm.kindleBonus(plugin) : 0;
        boolean cornerstone = !frenzy && !noLoss && combo >= breakShieldCombo()
                && cornerUsedOf(item) < 1 + rb.cornerstoneExtra();
        boolean armed = furnaceReady || noLoss;
        double[] odds = odds(level, combo, armed, kindleBonus, rb, frenzy,
                !armed && cornerstone);

        // ---- 扣费（扣了就不再退，空锤也照吃）----
        plugin.stats().takeCoins(player.getUniqueId(), cost, CoinReason.STRENGTHEN);
        if (costItem != null) {
            // 材料只扣实有的那部分，差额由剑尘补（dustUse 在材料检查段已经算好）
            MaterialGrades.consume(player, costItem, need - dustUse);
            if (dustUse > 0) {
                com.forgemaster.dissolve.DissolveService.takeDust(player, dustUse);
                Msg.prefix(player, "<gray>材料不够，自动用 <#9f7aea>剑尘</#9f7aea> 抵了 <white>"
                        + dustUse + "</white> 个 <white>" + MaterialGrades.pretty(costItem)
                        + "</white> <dark_gray>（背包还剩剑尘 "
                        + com.forgemaster.dissolve.DissolveService.countDust(player) + "）</dark_gray>。</gray>");
            }
        }
        // v2.23.1 修 P2（剑尘虚报消耗）：剑尘抵掉的那部分从来没有从背包里扣材料，
        // 原来文案与 StrikeResult.materials 一律写 need，等于把没花的材料也算进消耗——
        // 材料 3/5 + 剑尘抵 2 个时，玩家被告知「花掉 5 个」、批量总结也按 5 个累加。
        // 现在统一用 matUsed = 真正从背包扣走的数量（costItem 为 null 时一件都没扣）。
        int matUsed = costItem == null ? 0 : Math.max(0, need - dustUse);
        String costText = costItem == null
                ? " · 没有材料消耗"
                : " · 花掉 " + matUsed + " 个 " + MaterialGrades.pretty(costItem)
                        + (dustUse > 0 ? " + 剑尘 " + dustUse : "");
        if (kindle) {
            Charm.take(player, Charm.KINDLE, 1);
        }
        addInt(item, Keys.STRENGTHEN_INVEST, need);
        if (cornerstone) {
            writeInt(item, Keys.CORNERSTONE_USED, cornerUsedOf(item) + 1);
        }
        // 每次落锤都算「打铁」：狂热进度照攒（圣物可加狂热次数上限）
        tickFrenzy(item, frenzy, rb.frenzyExtra());

        double roll = random.nextDouble();
        Tier tier;
        int gain = 1;
        if (furnaceReady) {
            tier = Tier.SUCCESS;
        } else if (noLoss) {
            tier = Tier.NO_LOSS;
            gain = roll < odds[3] ? 2 : 1;
        } else {
            tier = tierOf(roll, odds);
            gain = tier == Tier.GREAT ? 2 : 1;
        }

        int kindlePct = (int) Math.round(kindleBonus * 100);
        int catchupPct = (int) Math.round(catchup * 100);
        String oddsText = oddsLine(odds);

        // ---- 成功族：成功 / 大成 / 无损连击 ----
        if (tier == Tier.SUCCESS || tier == Tier.GREAT || tier == Tier.NO_LOSS) {
            int[] step = afterStrike(tier, level, cap, combo, gain, threshold);
            int next = step[0];
            writeLevel(item, next, cap);
            writeInt(item, Keys.FURNACE, 0);
            int newCombo = step[1];
            writeInt(item, Keys.STRENGTHEN_COMBO, newCombo);
            apply(item);
            // 名人堂：刷新「最高锻造等级」条目（内部比大小，低等级不会覆盖纪录）
            if (plugin.hallOfFame() != null) {
                try {
                    plugin.hallOfFame().strengthen().record(player, item);
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("强化纪录写入名人堂失败: " + ex.getMessage());
                }
            }
            // 排行榜：落库「历史最高等级」，让强化榜变成全服历史而不是在线快照
            try {
                plugin.stats().recordStrengthen(player.getUniqueId(), next);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("强化等级落库失败: " + ex.getMessage());
            }
            com.forgemaster.ranking.StrengthenSnapshot.invalidate();
            if (plugin.seasonPass() != null) plugin.seasonPass().earn(player.getUniqueId(), "strengthen"); // 赛季通行证：强化成功登记（成功/大成/无损都算）
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.9f, 1.25f);
            if (tier == Tier.GREAT || gain > 1) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.5f);
            }
            if (next >= cap) {
                player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.4f, 1.6f);
            }
            String head = switch (tier) {
                case NO_LOSS -> "<#7ef2c8>无损连击强化！</#7ef2c8>";
                case GREAT -> "<gold>大成！</gold>";
                default -> furnaceReady ? "<gold>炉火已满，这一锤必成。</gold>" : "<green>落锤成功！</green>";
            };
            String body = head + " <white>+" + next + "</white><gray>/<white>+" + cap
                    + "</white> <gray>基础属性 <aqua>+" + Msg.fmt(bonusRatio(next) * 100, 0) + "%</aqua>"
                    + "<newline><dark_gray>成功率 " + Msg.fmt(baseChance * 100, 0) + "%"
                    + (catchupPct > 0 ? "<green>（赛季追赶 +" + catchupPct + "%）</green>" : "")
                    + (kindlePct > 0 ? "（旺火符 +" + kindlePct + "%）" : "")
                    + costText
                    + " + " + cost + " 剑币"
                    + "<newline>" + oddsText
                    + "<newline>" + comboText(newCombo, threshold) + "</dark_gray>";
            return new StrikeResult(tier, level, next, cap, newCombo, threshold,
                    threshold > 0 && newCombo >= threshold, false, cost, matUsed, body);
        }

        // ---- 失败族（损伤 / 空锤 / 碎裂）----
        addInt(item, Keys.STRENGTHEN_FAILS, 1);
        int needPoints = furnaceNeed(level);
        int furnaceNow = Math.min(needPoints, furnaceOf(item) + 1 + rb.furnacePerFail());
        writeInt(item, Keys.FURNACE, furnaceNow);
        if (!rb.comboKeep()) {
            writeInt(item, Keys.STRENGTHEN_COMBO, 0);
        }
        int comboNow = rb.comboKeep() ? combo : 0;
        String tail = "<dark_gray> · 炉火 " + furnaceNow + "/" + needPoints
                + "<newline>" + oddsText + "<newline>"
                + (rb.comboKeep() ? "连击被蜡线护住了。" : "连击断了。") + "</dark_gray>";

        if (tier == Tier.BREAK) {
            // ---- 碎裂：凤凰灰（每日一次免碎）→ 护胚符 → 真碎 ----
            String head = "<red>碎裂！</red><dark_gray>（碎率 " + Msg.fmt(odds[0] * 100, 0) + "%）";
            var cm = plugin.contractManager();
            if (cm != null && rb.phoenix() && cm.canPhoenix(player.getUniqueId())) {
                cm.markPhoenixUsed(player.getUniqueId());
                int halved = level / 2;
                writeLevel(item, halved, cap);
                apply(item);
                player.playSound(player.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.8f);
                return new StrikeResult(Tier.BREAK, level, halved, cap, comboNow, threshold,
                        false, false, cost, matUsed,
                        head + "<gradient:#ffd166:#ff7a3d>凤凰灰燃尽了——</gradient><gray>剑保住了，"
                                + "强化级烧掉一半，掉到 <white>+" + halved + "</white>。</gray>" + tail);
            }
            boolean hasTalisman = Charm.enabled(plugin, Charm.TALISMAN)
                    && Charm.count(player, Charm.TALISMAN) > 0;
            if (!hasTalisman) {
                addInt(item, Keys.STRENGTHEN_BROKEN, 1);
                String tombId = recordDestruction(player, item);
                if (onBroken != null) {
                    onBroken.accept(item);
                } else {
                    removeFromInventory(player, item);
                }
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 0.55f);
                return new StrikeResult(Tier.BREAK, level, level, cap, comboNow, threshold,
                        false, true, cost, matUsed,
                        head + "<dark_gray>，剑胚碎了"
                                + (tombId == null ? "" : "，残骸已收入剑冢留档 <white>" + tombId + "</white>") + "。"
                                + tail);
            }
            Charm.take(player, Charm.TALISMAN, 1);
            int leftTalisman = Charm.count(player, Charm.TALISMAN);
            // v2.23.1 修 P1：护胚符顶住之后有两种结局，落锤后等级并不一样——
            // 稳胚符又托住时物品还是原来的 level（上面根本没 writeLevel），只有没稳胚符才掉到 level-1。
            // 原来 return 里无条件写 Math.max(0, level - 1)，于是「稳胚符托住」的情况对外报了一个
            // 假等级：AnvilForgeManager.oneLine 按 toLevel != fromLevel 渲染成「碎了 +12→+11」，
            // 而物品仍然是 +12；日志/统计里也跟着错一级。现在用 keptLevel 把两种结局分开记，
            // tier 仍是 BREAK、broken 仍是 false（剑确实没碎），调用方按 tier/broken 判清理不受影响。
            int keptLevel = level;
            StringBuilder note = new StringBuilder("<gray>，<#9f7aea>护胚符</#9f7aea>顶了一次——剑胚保住了");
            if (Charm.enabled(plugin, Charm.STEADY) && Charm.count(player, Charm.STEADY) > 0) {
                Charm.take(player, Charm.STEADY, 1);
                note.append("，<#6bd3ff>稳胚符</#6bd3ff>又托住了等级");
            } else {
                keptLevel = Math.max(0, level - 1);
                writeLevel(item, keptLevel, cap);
                note.append("，掉到 <white>+").append(keptLevel).append("</white>");
            }
            apply(item);
            return new StrikeResult(Tier.BREAK, level, keptLevel, cap, comboNow, threshold,
                    false, false, cost, matUsed,
                    head + note + " <dark_gray>（护胚符剩 " + leftTalisman + " 张）</dark_gray>" + tail);
        }

        String head = "<red>落锤失败</red><dark_gray>（成功率 " + Msg.fmt(baseChance * 100, 0) + "%"
                + (catchupPct > 0 ? "，含赛季追赶 +" + catchupPct + "%" : "") + "）";
        if (tier == Tier.HOLD) {
            // ---- 空锤：材料白费，等级不动 ----
            apply(item);
            return new StrikeResult(Tier.HOLD, level, level, cap, comboNow, threshold,
                    false, false, cost, matUsed,
                    head + "<gray>，空锤——材料白费了，等级没动。</gray>" + tail);
        }
        // ---- 损伤：掉 1 级，稳胚符可托 ----
        if (Charm.enabled(plugin, Charm.STEADY) && Charm.count(player, Charm.STEADY) > 0) {
            Charm.take(player, Charm.STEADY, 1);
            int left = Charm.count(player, Charm.STEADY);
            apply(item);
            return new StrikeResult(Tier.DAMAGE, level, level, cap, comboNow, threshold,
                    false, false, cost, matUsed,
                    head + "<gray>，<#6bd3ff>稳胚符</#6bd3ff>托住了损伤——等级没降，仍是 <white>+"
                            + level + "</white> <dark_gray>（剩 " + left + " 张）</dark_gray>" + tail);
        }
        int dropped = Math.max(0, level - 1);
        writeLevel(item, dropped, cap);
        apply(item);
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.8f, 0.6f);
        return new StrikeResult(Tier.DAMAGE, level, dropped, cap, comboNow, threshold,
                false, false, cost, matUsed,
                head + "<gray>，损伤——掉到 <white>+" + dropped + "</white>" + tail);
    }

    /** 拒绝落锤（不掷骰、不扣费） */
    private StrikeResult refused(ItemStack item, String message) {
        int level = item == null ? -1 : levelOf(item);
        int cap = item == null ? 0 : capOf(item);
        return new StrikeResult(Tier.REFUSED, level, level, cap, 0,
                noLossComboThreshold(), false, false, 0, 0, message);
    }

    /**
     * 明牌一行的统一写法，铁砧全息与聊天反馈共用。
     *
     * <p>成功率带「含大成」，碎裂率是<b>整体</b>概率——玩家在砧子上看到多少就是多少。</p>
     */
    public String oddsLine(double[] odds) {
        if (odds == null || odds.length < 5) {
            return "";
        }
        double success = odds[3] + odds[4];
        return "<dark_gray>明牌 ▸ 成功 <" + com.forgemaster.util.UiPalette.chanceColor(success) + ">" + Msg.fmt(success * 100, 0)
                + "%</" + com.forgemaster.util.UiPalette.chanceColor(success) + ">（大成 " + Msg.fmt(odds[3] * 100, 0)
                + "%）<dark_gray> · 碎裂 <red>" + Msg.fmt(odds[0] * 100, 0)
                + "%</red> · 损伤 " + Msg.fmt(odds[1] * 100, 0)
                + "% · 空锤 " + Msg.fmt(odds[2] * 100, 0) + "%</dark_gray>";
    }

    /** 连击一行：攒到阈值就提示无损连击已就绪 */
    public String comboText(int combo, int threshold) {
        if (threshold <= 0) {
            return "<gray>连击 <white>" + combo + "</white>";
        }
        boolean ready = combo >= threshold;
        return ready
                ? "<gray>连击 <#7ef2c8>" + combo + "/" + threshold + " · 无损连击就绪</#7ef2c8>"
                : "<gray>连击 <white>" + combo + "</white><dark_gray>/" + threshold + "</dark_gray>";
    }

    /**
     * 剑胚碎裂：留档进剑冢 → 从主手拿掉（或直接清空数量）。
     *
     * @return 剑冢编号；没留档返回 null
     */
    private String recordDestruction(Player player, ItemStack item) {
        int level = levelOf(item);
        // 剑魂传承：碎了不白碎，等级按比例记给下一把新铸的剑
        grantInherit(player, level);
        if (plugin.tombs() == null || !plugin.settings().flag("strengthen.fail.tomb-record", true)) {
            return null;
        }
        try {
            return plugin.tombs().recordBroken(player, item, level);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("碎剑写入剑冢失败: " + ex.getMessage());
            return null;
        }
    }

    /**
     * 碎剑后从玩家身上把剑拿掉。
     *
     * <p>铁砧强化时剑不在背包里，走 {@code strike(..., onBroken)} 的自定义清理，
     * 不会进到这里；命令入口仍然按主手 → 背包的顺序找。</p>
     */
    private void removeFromInventory(Player player, ItemStack item) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (!main.getType().isAir() && main.isSimilar(item)) {
            player.getInventory().setItemInMainHand(null);
            return;
        }
        // 不能 setAmount(0)：那是非法堆叠，客户端会出现幽灵物品、存档也可能炸
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack slot = inv.getItem(i);
            if (slot != null && slot.isSimilar(item)) {
                inv.setItem(i, null);
                return;
            }
        }
    }

    /** 直接写到指定等级（管理命令 / 自检用），不扣费 */
    public boolean setLevel(ItemStack item, int level) {
        int cap = capOf(item);
        if (cap <= 0) {
            return false;
        }
        writeLevel(item, Math.max(0, Math.min(cap, level)), cap);
        apply(item);
        return true;
    }

    private void writeLevel(ItemStack item, int level, int cap) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(Keys.STRENGTHEN, PersistentDataType.INTEGER, level);
        pdc.set(Keys.STRENGTHEN_CAP, PersistentDataType.INTEGER, cap);
        com.forgemaster.util.Attrs.apply(item, meta);
    }

    // ------------------------------------------------------------ 强化模拟器

    /**
     * 一次强化的期望统计（纯数据，方便自检直接断言）。
     *
     * @param fromLevel        当前等级（-1 = 不是锻造品）
     * @param targetLevel      目标等级（已夹在 [当前等级, 上限] 之间）
     * @param cap              该物品的等级上限
     * @param downgradeOnFail  失败是否降级（决定有没有「原地踏步」的自环）
     * @param expectedAttempts 从当前锻到目标，期望要锤多少次
     * @param expectedMaterials 期望吃掉多少个对应材料
     * @param expectedCoins    期望花掉多少剑币
     * @param expectedBreaks   期望碎剑次数
     * @param reachChance      在碎之前最终达成目标的概率（0~1）
     */
    public record Simulation(int fromLevel, int targetLevel, int cap, boolean downgradeOnFail,
                             double expectedAttempts, double expectedMaterials, double expectedCoins,
                             double expectedBreaks, double reachChance) {

        /** 目标是否真的需要锻（等级合法且还有提升空间） */
        public boolean achievable() {
            return fromLevel >= 0 && cap > 0 && targetLevel > fromLevel;
        }
    }

    /**
     * 模拟从当前等级锻到目标等级的期望开销。
     *
     * <p><b>纯函数</b>：不扣材料、不扣剑币、不改物品。</p>
     *
     * <p>模型是一维马尔可夫链：等级 L 上锤一次，成功率 p(L)、碎裂率 b(L)；
     * 失败没碎时降到 L−1（{@code strengthen.fail.downgrade} 为真）或留在 L。
     * 所以不能简单用 1/p——降级会把已经付过的费用再付一遍。这里解两个
     * 线性方程组：</p>
     * <ul>
     *   <li>访问次数 u[L]：解出每一级上实际会锤多少次，
     *       期望总次数 = Σu、期望材料 = Σu·材料(L)、期望剑币 = Σu·费用(L)；</li>
     *   <li>达成概率 P[L]：解出从 L 出发、在碎之前摸到目标的概率，P[目标]=1。</li>
     * </ul>
     * <p>碎裂是吸收态，所以 1 − P[当前] 就是「这一趟锻下来剑没能活着到目标」的概率。</p>
     */
    public Simulation simulateStats(ItemStack item, int targetLevel) {
        int from = levelOf(item);
        int cap = capOf(item);
        boolean downgrade = downgradeOnFail();
        if (from < 0 || cap <= 0) {
            return new Simulation(from, targetLevel, cap, downgrade, 0, 0, 0, 0, 0);
        }
        int target = Math.max(from, Math.min(cap, targetLevel));
        if (target <= from) {
            return new Simulation(from, target, cap, downgrade, 0, 0, 0, 0, 1.0);
        }
        // 未知量是 0..target-1 级的访问次数（目标级不再锤）
        int n = target;
        // ---- 每级的一锤转移概率，和真实掷骰（odds）同一套口径 ----
        // 旧版这里把所有「非碎裂失败」都当成降 1 级，既没算门票里的「空锤」
        // （damage-share 那半边不降级），也没算大成 +2——高段因此被算成负漂移，
        // +99 的期望直接跑到天文数字，玩家看到的「还要多少锤」完全是假的。
        double[] pSucc = new double[n];
        double[] pGreat = new double[n];
        double[] pDamage = new double[n];
        double[] pHold = new double[n];
        for (int L = 0; L < n; L++) {
            double p = Math.min(1.0, successChance(L));
            double great = Math.min(Math.max(0, Math.min(0.5, greatBase())), p);
            double succ = Math.max(0, p - great);
            double fail = Math.max(0, 1.0 - p);
            double brk = fail * Math.max(0, Math.min(1.0, breakChance(L)));
            double rest = Math.max(0, fail - brk);            // 没碎的那部分失败
            double dmg = downgrade ? rest * damageShare() : 0;
            double hold = rest - dmg;
            pSucc[L] = succ;
            pGreat[L] = great;
            pDamage[L] = dmg;
            pHold[L] = hold;
        }

        // 期望访问次数：v[L] = 1{L=from} + Σ_M v[M]·P(M→L)，碎裂是吸收态（不再访问）
        double[][] visit = new double[n][n];
        double[] visitRhs = new double[n];
        for (int L = 0; L < n; L++) {
            // 自环：空锤留在本级；0 级掉不下去，损伤也留在本级
            visit[L][L] += 1.0 - pHold[L] - (L == 0 ? pDamage[L] : 0.0);
            // 从 L+1 损伤掉下来
            if (L + 1 < n) {
                visit[L][L + 1] -= pDamage[L + 1];
            }
            // 从 L-1 成功、从 L-2 大成上来
            if (L - 1 >= 0) {
                visit[L][L - 1] -= pSucc[L - 1];
            }
            if (L - 2 >= 0) {
                visit[L][L - 2] -= pGreat[L - 2];
            }
            visitRhs[L] = L == from ? 1.0 : 0.0;
        }
        double[] u = solve(visit, visitRhs);
        if (u == null) {
            return new Simulation(from, target, cap, downgrade, 0, 0, 0, 0, 0);
        }

        double attempts = 0;
        double materials = 0;
        double coins = 0;
        double breaks = 0;
        for (int L = 0; L < n; L++) {
            double v = Math.max(0, u[L]);
            attempts += v;
            materials += v * materialAmountOf(item, L);
            coins += v * costOf(item, L);
            breaks += v * (1.0 - Math.min(1.0, successChance(L))) * breakChance(L);
        }

        // 达成概率：h[L] = 在碎之前摸到目标的概率，h[>=target] = 1（碎裂 = 0）
        //   h[L] = hold·h[L] + dmg·h[max(0,L-1)] + succ·h[L+1] + great·h[L+2]
        double[][] reach = new double[n][n];
        double[] reachRhs = new double[n];
        for (int L = 0; L < n; L++) {
            reach[L][L] += 1.0 - pHold[L] - (L == 0 ? pDamage[L] : 0.0);
            if (L - 1 >= 0) {
                reach[L][L - 1] -= pDamage[L];
            }
            // 越过目标的那些分支直接算达成：系数搬到右边
            if (L + 1 < n) {
                reach[L][L + 1] -= pSucc[L];
            } else {
                reachRhs[L] += pSucc[L];
            }
            if (L + 2 < n) {
                reach[L][L + 2] -= pGreat[L];
            } else {
                reachRhs[L] += pGreat[L];
            }
        }
        double[] pr = solve(reach, reachRhs);
        double reachChance = pr == null ? 0 : Math.max(0, Math.min(1, pr[from]));

        // 高段负漂移时方程组会解出天文数字（3e15 那种），那不是「要锤三千万亿次」，
        // 而是「这段期望根本不收敛」。统一折成 -1，让 simulate() 说人话。
        if (!Double.isFinite(attempts) || attempts > 1e7) {
            return new Simulation(from, target, cap, downgrade, -1, -1, -1, -1, 0);
        }

        return new Simulation(from, target, cap, downgrade,
                attempts, materials, coins, breaks, reachChance);
    }

    /**
     * 强化模拟器的文本报告（给玩家看 / 命令用）。
     *
     * <p>纯函数，不扣任何东西、不改物品。</p>
     */
    public String simulate(ItemStack item, int targetLevel) {
        Simulation sim = simulateStats(item, targetLevel);
        if (sim.fromLevel() < 0) {
            return "<red>这不是锻造品，模拟不了。";
        }
        if (sim.cap() <= 0) {
            return "<red>这件物品没有强化上限，模拟不了。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<gray>强化模拟: <white>+").append(sim.fromLevel()).append("</white> → <white>+")
                .append(sim.targetLevel()).append("</white> <dark_gray>(上限 +")
                .append(sim.cap()).append(")</dark_gray>");
        if (sim.targetLevel() <= sim.fromLevel()) {
            sb.append("<newline><gray>目标不高于当前等级，不用锻。");
            return sb.toString();
        }
        Material item2 = materialItemOf(item);
        String matName = item2 == null ? "材料" : MaterialGrades.pretty(item2);
        sb.append("<newline><gray>成功率: <white>")
                .append(Msg.fmt(successChance(sim.fromLevel()) * 100, 0)).append("%</white> <dark_gray>(当前级)</dark_gray> → <white>")
                .append(Msg.fmt(successChance(sim.targetLevel() - 1) * 100, 0)).append("%</white> <dark_gray>(末级)")
                .append(sim.downgradeOnFail() ? " · 失败按损伤/空锤各半" : " · 失败不降级")
                .append(" · 大成 ").append(Msg.fmt(greatBase() * 100, 0)).append("%(+2 级)")
                .append("</dark_gray>");
        // 赛季追赶：上面两个成功率已经含它了，这里说明清楚，别让玩家以为数字凭空变高
        double simCatchup = seasonCatchupBonus(sim.fromLevel());
        int catchupThreshold = seasonCatchupLevelThreshold();
        if (simCatchup > 0.0005) {
            sb.append("<newline><gray>赛季追赶: <green>+").append(Msg.fmt(simCatchup * 100, 0))
                    .append("%</green> <dark_gray>（第 ").append(seasonNumber()).append(" 季 · 进度 ")
                    .append(Msg.fmt(seasonProgress() * 100, 0)).append("% · 只帮 +")
                    .append(catchupThreshold)
                    .append(" 及以下，已计入上面成功率）</dark_gray>");
        } else if (sim.fromLevel() > catchupThreshold && seasonProgress() > 0) {
            // 高等级段不吃追赶：说一句，免得老玩家以为机制坏了
            sb.append("<newline><gray>赛季追赶: <dark_gray>本级不吃（只帮 +").append(catchupThreshold)
                    .append(" 及以下，当前赛季进度 ")
                    .append(Msg.fmt(seasonProgress() * 100, 0)).append("%）</dark_gray>");
        }
        if (sim.expectedAttempts() < 0) {
            // 负漂移段：期望锤数发散，给个诚实的说法而不是编个天文数字
            sb.append("<newline><red>这一段期望不收敛：</red><gray>成功率已经低于损伤回流，"
                    + "硬锤只会越锤越低。要么靠 <white>连击</white> 攒无损、要么上 "
                    + "<white>护胚符 / 稳胚符</white>，别指望蛮力堆。</gray>");
            sb.append("<newline><dark_gray>（模拟器只算裸锤，不把连击 / 炉火 / 符算进期望）</dark_gray>");
            return sb.toString();
        }
        sb.append("<newline><gray>期望尝试: <white>").append(Msg.fmt(sim.expectedAttempts(), 1))
                .append("</white> 次");
        sb.append("<newline><gray>期望消耗: <white>").append(Msg.fmt(sim.expectedMaterials(), 0))
                .append("</white> 个 ").append(matName)
                .append(" <gray>+ <yellow>").append(Math.round(sim.expectedCoins())).append("</yellow> 剑币");
        sb.append("<newline><gray>期望碎剑: <white>").append(Msg.fmt(sim.expectedBreaks(), 2))
                .append("</white> 次");
        sb.append("<newline><gray>期望达成: <white>").append(Msg.fmt(sim.reachChance() * 100, 1))
                .append("%</white>");
        return sb.toString();
    }

    /**
     * 强化曲线报表（v2.19.1 新增）：把「从当前等级锻到各品质上限」的代价摆成一张表。
     *
     * <p>为什么要有这张表：品质上限是六档（40/50/60/72/86/99），但「能不能真的走到」
     * 只能靠概率算——同一套 {@link #simulateStats} 马尔可夫链，逐档跑一遍就行。
     * 服主调完 {@code anvil-forge} 直接敲一下这条命令，就能看出曲线是「渐进变难」
     * 还是「+70 之后断崖」，不用再靠蒙特卡洛脚本。</p>
     *
     * <p><b>纯函数</b>：不扣材料、不扣剑币、不改物品。口径与模拟器一致（裸锤，
     * 不含连击 / 炉火 / 符），所以真实上手会比表上宽松。</p>
     */
    public String curveReport(ItemStack item) {
        int from = levelOf(item);
        int cap = capOf(item);
        if (from < 0 || cap <= 0) {
            return "<red>这不是锻造品，算不出曲线。";
        }
        // 六档品质上限；服主可能改过，所以现读配置而不是写死数字
        java.util.List<Integer> targets = new java.util.ArrayList<>();
        for (String key : new String[]{"ROUGH", "COMMON", "FINE", "EPIC", "LEGENDARY", "MYTHIC"}) {
            int c = plugin.settings().intOf("anvil-forge.caps." + key, 0);
            if (c > from && c <= cap && !targets.contains(c)) {
                targets.add(c);
            }
        }
        if (!targets.contains(cap)) {
            targets.add(cap); // 兜底：本件上限本身一定要有一行
        }
        java.util.Collections.sort(targets);

        StringBuilder sb = new StringBuilder();
        sb.append("<gray>强化曲线 <dark_gray>(当前 <white>+").append(from)
                .append("</white> / 上限 <white>+").append(cap)
                .append("</white> · 裸锤口径，不含连击与符)</dark_gray>");
        double prevCoins = -1;
        for (int t : targets) {
            Simulation s = simulateStats(item, t);
            sb.append("<newline><white>+").append(t).append("</white> <dark_gray>·</dark_gray> ");
            if (s.expectedAttempts() < 0) {
                sb.append("<red>期望不收敛</red> <dark_gray>（负漂移段，靠连击/符）</dark_gray>");
                prevCoins = -1;
                continue;
            }
            sb.append("<gray>期望 <white>").append(Msg.fmt(s.expectedAttempts(), 0))
                    .append("</white> 锤 · <white>").append(wan(Math.round(s.expectedCoins())))
                    .append("</white> 剑币 · 达成 <white>")
                    .append(Msg.fmt(s.reachChance() * 100, s.reachChance() < 0.01 ? 3 : 1))
                    .append("%</white>");
            // 相邻档位的边际成本：让「+60 → +72 比 +50 → +60 贵多少」一眼看出来
            if (prevCoins >= 0) {
                long delta = Math.round(s.expectedCoins() - prevCoins);
                sb.append(" <dark_gray>(+" ).append(wan(delta)).append(")</dark_gray>");
            }
            prevCoins = s.expectedCoins();
        }
        sb.append("<newline><dark_gray>口径说明：这里只算裸锤。连击 / 炉魂 / 护胚符都会显著提高达成率，");
        sb.append("所以真实手感比表上宽松——表是「最坏情况的地板」。");
        return sb.toString();
    }

    /** 大数缩写成「万 / 亿」，避免聊天框里出现一长串数字 */
    private static String wan(long v) {
        long abs = Math.abs(v);
        if (abs < 10_000L) {
            return String.valueOf(v);
        }
        if (abs < 100_000_000L) {
            return Msg.fmt(v / 10_000.0, v % 10_000L == 0 ? 0 : 1) + " 万";
        }
        return Msg.fmt(v / 100_000_000.0, 1) + " 亿";
    }

    /** 小规模稠密线性方程组（带部分主元的高斯消元）；奇异返回 null */
    private static double[] solve(double[][] a, double[] b) {
        int n = b.length;
        double[] rhs = b.clone();
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) {
                    pivot = r;
                }
            }
            if (Math.abs(a[pivot][col]) < 1e-12) {
                return null;
            }
            if (pivot != col) {
                double[] tmp = a[pivot];
                a[pivot] = a[col];
                a[col] = tmp;
                double t = rhs[pivot];
                rhs[pivot] = rhs[col];
                rhs[col] = t;
            }
            double diag = a[col][col];
            for (int r = col + 1; r < n; r++) {
                double f = a[r][col] / diag;
                if (f == 0) {
                    continue;
                }
                for (int c = col; c < n; c++) {
                    a[r][c] -= f * a[col][c];
                }
                rhs[r] -= f * rhs[col];
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double s = rhs[r];
            for (int c = r + 1; c < n; c++) {
                s -= a[r][c] * x[c];
            }
            x[r] = s / a[r][r];
        }
        return x;
    }

    /** 护甲校准版本：2.10 起单件护甲按部位折算、按原版量级重定 */
    public static final int ARMOR_REBALANCE_VERSION = 2;

    /**
     * 把老装备的基础护甲按当前配置重新校准一次。
     *
     * <p>2.10 之前 {@code armor.tiers} 里存的是「单件 0.35」这种量级，
     * 铁甲一整套只有 1.4 点护甲，等于没穿。老装备身上冻着的是旧快照
     * （{@code BASE_ARMOR}），光改配置救不回来，得按当前配置重算一遍。</p>
     *
     * <p>只升不降：新值比旧值低的时候跳过，绝不让校准变成削弱。
     * 校准过就写 {@code ARMOR_REBALANCE} 标记，之后不再重复处理。</p>
     *
     * @param item 待校准的物品（会被原地改写并写回 meta）
     * @return 是否真的改动过
     */
    public boolean rebalanceArmor(ItemStack item) {
        if (item == null || item.getType().isAir() || !plugin.items().isForged(item)) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        Integer done = meta.getPersistentDataContainer()
                .get(Keys.ARMOR_REBALANCE, PersistentDataType.INTEGER);
        if (done != null && done >= ARMOR_REBALANCE_VERSION) {
            return false;
        }
        ForgeMaterial mat = materialOf(item);
        Quality q = qualityOf(item);
        if (mat == null || q == null || !mat.armor()) {
            return false;
        }
        double newArmor = round(mat.bonusArmor() * q.power());
        double newTough = round(mat.armorToughness() * q.power());
        double oldArmor = meta.getPersistentDataContainer()
                .getOrDefault(Keys.BASE_ARMOR, PersistentDataType.DOUBLE, 0.0);
        double oldTough = meta.getPersistentDataContainer()
                .getOrDefault(Keys.BASE_TOUGH, PersistentDataType.DOUBLE, 0.0);

        // 只升不降
        newArmor = Math.max(newArmor, oldArmor);
        newTough = Math.max(newTough, oldTough);
        boolean changed = Math.abs(newArmor - oldArmor) > 1e-6
                || Math.abs(newTough - oldTough) > 1e-6;

        // 顺手把旧键名（fm_armor / fm_toughness）换成按部位的键，
        // 否则四件装备共用一个键，脱一件会把别的护甲一起清掉
        changed |= migrateLegacyKeys(meta, mat);

        if (changed) {
            meta.getPersistentDataContainer().set(Keys.BASE_ARMOR,
                    PersistentDataType.DOUBLE, newArmor);
            meta.getPersistentDataContainer().set(Keys.BASE_TOUGH,
                    PersistentDataType.DOUBLE, newTough);
            com.forgemaster.util.Attrs.apply(item, meta);
            apply(item);
        }
        ItemMeta done2 = item.getItemMeta();
        if (done2 != null) {
            done2.getPersistentDataContainer().set(Keys.ARMOR_REBALANCE,
                    PersistentDataType.INTEGER, ARMOR_REBALANCE_VERSION);
            com.forgemaster.util.Attrs.apply(item, done2);
        }
        return changed;
    }

    /**
     * 把 2.10 之前的 {@code fm_armor} / {@code fm_toughness} 换成带部位的键。
     *
     * @return 是否替换过
     */
    private boolean migrateLegacyKeys(ItemMeta meta, ForgeMaterial mat) {
        String wantArmor = com.forgemaster.util.Attrs.armorKey(mat.slot());
        String wantTough = com.forgemaster.util.Attrs.toughKey(mat.slot());
        boolean changed = false;

        Collection<AttributeModifier> armorMods = meta.getAttributeModifiers(Attribute.ARMOR);
        if (armorMods != null) {
            for (AttributeModifier m : new ArrayList<>(armorMods)) {
                NamespacedKey key = m.getKey();
                if (key != null && "fm_armor".equals(key.getKey())) {
                    meta.removeAttributeModifier(Attribute.ARMOR, m);
                    changed = true;
                }
            }
        }
        Collection<AttributeModifier> toughMods =
                meta.getAttributeModifiers(Attribute.ARMOR_TOUGHNESS);
        if (toughMods != null) {
            for (AttributeModifier m : new ArrayList<>(toughMods)) {
                NamespacedKey key = m.getKey();
                if (key != null && "fm_toughness".equals(key.getKey())) {
                    meta.removeAttributeModifier(Attribute.ARMOR_TOUGHNESS, m);
                    changed = true;
                }
            }
        }
        changed |= migrateAffixKeys(meta, mat);
        return changed;
    }

    /**
     * 把护甲词条的旧 modifier 键 {@code fm_<词条id>} 换成 {@code fm_<词条id>_<部位>}。
     *
     * <p>为什么必须换：护甲是一次穿四件的。两件都 roll 到「护甲值」时，
     * 旧键名完全相同（都是 {@code fm_armor_plate}），原版按键去重，后面那件直接失效。
     * 也就是说——<b>同一个词条出在两件护甲上，只有一件生效</b>。</p>
     *
     * <p>这也解释了自检里那条「四件穿一起不会因为同一个键互相顶掉」为什么之前一直绿：
     * 它只检查 {@code Attribute.ARMOR} 上的键，而护甲上原本全是攻击词条
     * （{@code buildDirect} 的 bug），压根没有护甲属性词条参与检查。</p>
     *
     * <p>迁移是<b>原值搬家</b>：数值不动，只换键名，所以不会改变任何玩家已有的强度。</p>
     */
    private boolean migrateAffixKeys(ItemMeta meta, ForgeMaterial mat) {
        if (mat == null || mat.slot() == null) {
            return false;
        }
        String suffix = "_" + mat.slot().name().toLowerCase();
        var group = mat.slot().group();
        boolean changed = false;
        for (String id : com.forgemaster.forge.ForgeManager.ARMOR_AFFIX_POOL) {
            var affix = plugin.affixes() == null ? null : plugin.affixes().get(id);
            if (affix == null || affix.attribute() == null) {
                continue;
            }
            Collection<AttributeModifier> mods = meta.getAttributeModifiers(affix.attribute());
            if (mods == null) {
                continue;
            }
            for (AttributeModifier m : new ArrayList<>(mods)) {
                NamespacedKey key = m.getKey();
                if (key == null || !("fm_" + id).equals(key.getKey())) {
                    continue;
                }
                double amount = m.getAmount();
                var op = m.getOperation();
                meta.removeAttributeModifier(affix.attribute(), m);
                meta.addAttributeModifier(affix.attribute(), new AttributeModifier(
                        new NamespacedKey(plugin, "fm_" + id + suffix), amount, op, group));
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 按当前等级重算基础属性 modifier 与 Lore。
     *
     * <p>只替换 fm_atk / fm_speed / fm_armor* / fm_toughness* 这几个基础键，
     * 词条与宝石挂的同类属性原样保留。</p>
     */
    public void apply(ItemStack item) {
        applyInternal(item, true);
    }

    /**
     * 只重算属性，**完全不碰 Lore**（v2.23.1）。
     *
     * <h2>为什么要拆出这一版</h2>
     * 存量收敛（{@code LegacyItemRepair}）只需要把数值夹回上限。但 {@link #apply(ItemStack)}
     * 末尾会调 {@code rewriteLore}，把「锻造等级」行整条重建、把「锻阶 / 履历」行先删后插。
     *
     * <p>后果是：<b>一件只超了吸血的剑，等级行也会被重建一次</b>。内容通常一样（{@code +N}
     * 和倍率都没变），但玩家会看到「扫完之后 Lore 有动静」，以为整套属性都被改了，
     * 于是服里开始传「插件乱改我的剑」。</p>
     *
     * <p>所以收敛走这条路：数值该夹的照夹，Lore 一个字都不动，只在末尾追加一行
     * 「◆ 已收敛」。玩家看到的改动范围就是真实改动范围。</p>
     *
     * <p><b>代价（如实说明）</b>：不刷新 Lore 意味着「锻造等级 (基础属性 +X%)」里的
     * 百分比仍是旧文本。那个百分比由等级+锻阶算出、与上限无关，本来也不会变，所以这里没有信息损失；
     * 真正的落差是「面板词条文字 vs 实战数值」，那一行由收敛说明负责解释。</p>
     */
    public void applyStatsOnly(ItemStack item) {
        applyInternal(item, false);
    }

    /**
     * 属性重算主体。
     *
     * @param rewriteLore 是否刷新 Lore 的等级/锻阶/履历行
     */
    private void applyInternal(ItemStack item, boolean rewriteLore) {
        int level = levelOf(item);
        if (level < 0) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        ForgeMaterial mat = materialOf(item);
        Quality q = qualityOf(item);
        if (mat == null || q == null) {
            return;
        }
        int cap = capOf(item);
        // 锻阶乘区：蜕变各阶 bonus 连乘，与强化加成叠成总倍率
        double evoMult = 1.0;
        com.forgemaster.evolve.EvolutionService evo = plugin.evolutions();
        if (evo != null) {
            int stage = evo.stageOf(item);
            if (stage > 0) {
                evoMult = evo.registry().cumulativeMult(stage);
            }
        }
        double mult = (1.0 + bonusRatio(level)) * evoMult;

        boolean armor = mat.armor();
        EquipmentSlotGroup group = armor ? mat.slot().group() : EquipmentSlotGroup.MAINHAND;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (armor) {
            // 键名带部位：四件装备各挂各的，穿两件不会互相顶掉
            String keyArmor = com.forgemaster.util.Attrs.armorKey(mat.slot());
            String keyTough = com.forgemaster.util.Attrs.toughKey(mat.slot());
            double baseArmor = baseOf(meta, Keys.BASE_ARMOR, Attribute.ARMOR, keyArmor,
                    mat.bonusArmor() * q.power(), mult);
            double baseTough = baseOf(meta, Keys.BASE_TOUGH, Attribute.ARMOR_TOUGHNESS, keyTough,
                    mat.armorToughness() * q.power(), mult);
            replace(meta, Attribute.ARMOR, keyArmor, round(baseArmor * mult), group);
            replace(meta, Attribute.ARMOR_TOUGHNESS, keyTough, round(baseTough * mult), group);
            pdc.set(Keys.BASE_ARMOR, PersistentDataType.DOUBLE, round(baseArmor));
            pdc.set(Keys.BASE_TOUGH, PersistentDataType.DOUBLE, round(baseTough));
        } else {
            double baseAtk = baseOf(meta, Keys.BASE_ATK, Attribute.ATTACK_DAMAGE, KEY_ATK,
                    mat.bonusAttack() * q.power(), mult);
            double baseSpeed = baseOf(meta, Keys.BASE_SPEED, Attribute.ATTACK_SPEED, KEY_SPEED,
                    mat.bonusSpeed() * q.power(), mult);
            replace(meta, Attribute.ATTACK_DAMAGE, KEY_ATK, budgetAttack(baseAtk * mult), group);
            replace(meta, Attribute.ATTACK_SPEED, KEY_SPEED, round(baseSpeed * mult), group);
            pdc.set(Keys.BASE_ATK, PersistentDataType.DOUBLE, round(baseAtk));
            pdc.set(Keys.BASE_SPEED, PersistentDataType.DOUBLE, round(baseSpeed));
        }
        com.forgemaster.util.Attrs.apply(item, meta);
        if (rewriteLore) {
            rewriteLore(item, level, cap, mult);
        }
    }

    /**
     * 取该物品的基础属性基准值（**绝不允许返回被强化倍率污染过的值**）。
     *
     * <h2>这里原来有一个「越强化越强得离谱」的滚雪球（v2.23.1 修 P0）</h2>
     * 旧实现的优先级是「PDC 快照 → 物品上现有的基础 modifier 原值」，注释写着
     * 「老物品身上的基础 modifier 是等级 0 时的原值」。<b>这句话是错的</b>：
     * modifier 里存的是 {@code base × mult(当前等级)}，即**已经被倍率乘过**的结果。
     * 于是老物品（无快照）每次强化都是：
     * <pre>
     *   baseOf() 取到 base×mult(N)  →  replace 写入 (base×mult(N))×mult(N+1)
     *   → 同时把 base×mult(N) 当成新快照写回去（污染永久化）
     *   → 下一锤再乘一次 …
     * </pre>
     * 生存服的后果就是服主说的那句「那都别玩了」：多锤几次之后，一把剑的
     * 攻击力能顶到几十把，PVP 与刷怪全失去意义，而 Lore 上还写着 +7。
     *
     * <h2>现在的规则：以配置为天花板，永远只往下收敛</h2>
     * <ol>
     *   <li><b>有快照</b>：用快照，但同样过 {@link #clampBase} 天花板。</li>
     *   <li><b>无快照（2.4 前的老物品）</b>：拿现有 modifier 除以「当前等级倍率」
     *       反推基础值，再过天花板。除以倍率是唯一的还原手段——老物品没别的记录。</li>
     *   <li><b>都没有</b>：用配置算出来的 {@code fallback}。</li>
     * </ol>
     * 天花板 = {@code fallback × strengthen.base-tolerance}（默认 1.0，即严格等于配置值）。
     * 意思是：<b>任何物品的基础属性都不可能超过「当前配置为这把胚子算出的值」</b>。
     * 已经膨胀的老剑会在下一次强化 / 蜕变 / 手动修复时被收敛回来。
     *
     * <p>这是一个**单向**的修正：只可能把虚高的压下来，不会把正常物品抬上去。
     * 服主如果本来就把配置调高了，老物品确实会「看起来变强」——那是配置生效，不是本函数在加料。</p>
     *
     * @param mult 当前（写入后）的总倍率，用于反推老物品的基础值
     */
    private double baseOf(ItemMeta meta, NamespacedKey snapshotKey, Attribute attribute,
                          String modifierKey, double fallback, double mult) {
        Double stored = meta.getPersistentDataContainer().get(snapshotKey, PersistentDataType.DOUBLE);
        if (stored != null) {
            return clampBase(stored, fallback);
        }
        Double onItem = modifierAmount(meta, attribute, modifierKey);
        if (onItem != null) {
            // 反推：modifier = base × mult → base = modifier / mult
            double base = onItem / Math.max(1.0e-6, Math.abs(mult));
            return clampBase(base, fallback);
        }
        return Math.max(0.0, fallback);
    }

    /** 单个基础 modifier 的当前数值（没有返回 null，区别于「有但为 0」） */
    private Double modifierAmount(ItemMeta meta, Attribute attribute, String modifierKey) {
        Collection<AttributeModifier> mods = meta.getAttributeModifiers(attribute);
        if (mods == null) {
            return null;
        }
        for (AttributeModifier m : mods) {
            NamespacedKey key = m.getKey();
            if (key != null && modifierKey.equals(key.getKey())) {
                return m.getAmount();
            }
        }
        return null;
    }

    /**
     * 基础属性天花板：{@code fallback × strengthen.base-tolerance}。
     *
     * <p>默认容差 1.0 = 严格按配置。允许服主写 &gt;1 是为了照顾「配置被自己调低过、
     * 但玩家手里的剑是按老配置锻的」这种情况——给个过渡余地，而不是一刀砍到底。</p>
     */
    private double clampBase(double raw, double fallback) {
        if (!(raw > 0)) {
            return Math.max(0.0, fallback);
        }
        double tol = plugin.settings().num("strengthen.base-tolerance", 1.0);
        if (Double.isNaN(tol) || tol < 1.0) {
            tol = 1.0; // 容差小于 1 会无理由削正常物品，直接拒收
        }
        double ceiling = Math.max(0.0, fallback) * tol;
        // fallback 为 0 时（服主把某个胚子的 bonus 删了）不要因此把所有值清零
        if (ceiling <= 0) {
            return raw;
        }
        return Math.min(raw, ceiling);
    }

    /**
     * 修复一把物品的属性：按当前配置重算基础值与倍率，并把干净的快照写回去。
     *
     * <p>给两处用：① 强化 / 蜕变路径每次 {@link #apply(ItemStack)} 自动走一遍；
     * ② 服主手动修存量物品（{@link #repairAttributes(ItemStack)}）。</p>
     *
     * @return true = 数值真的被改动过（便于命令统计到底修了多少）
     */
    public boolean repairAttributes(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta before = item.getItemMeta();
        if (before == null) {
            return false;
        }
        String sig = attrSignature(before);
        apply(item);
        ItemMeta after = item.getItemMeta();
        if (after == null) {
            return false;
        }
        return !sig.equals(attrSignature(after));
    }

    /** 把一件物品上「所有基础属性 modifier」拼成可比较的签名（修复是否生效靠它判定） */
    private String attrSignature(ItemMeta meta) {
        StringBuilder sb = new StringBuilder();
        for (Attribute a : new Attribute[]{Attribute.ATTACK_DAMAGE, Attribute.ATTACK_SPEED,
                Attribute.ARMOR, Attribute.ARMOR_TOUGHNESS}) {
            Collection<AttributeModifier> mods = meta.getAttributeModifiers(a);
            if (mods == null) {
                continue;
            }
            java.util.TreeMap<String, Double> sorted = new java.util.TreeMap<>();
            for (AttributeModifier m : mods) {
                NamespacedKey k = m.getKey();
                if (k != null) {
                    sorted.put(k.getKey(), m.getAmount());
                }
            }
            sb.append(a.name()).append('=').append(sorted).append(';');
        }
        return sb.toString();
    }

    /**
     * 攻击属性写入前的最后一夹（v2.23.1 修 P0）。
     *
     * <p>上限落在<b>产出</b>处而不是实战处：物品里存的就已经是合规值，
     * 玩家在 Lore / 属性面板上看到的就是真实生效的数字，不会出现「面板很高、打出来被砍」。
     * 实战那边还有一道 {@code max-hit-damage} 兜底，两层互不替代。</p>
     */
    private double budgetAttack(double value) {
        // 走 DamageBudget 的统一口径，不在强化侧另写一份 22.0
        return round(com.forgemaster.combat.DamageBudget.clampAttack(
                round(value), com.forgemaster.combat.DamageBudget.Limits.fromConfig(plugin.getConfig())));
    }

    /** 精确替换某个基础属性 modifier，词条加的同类属性不受影响 */
    private void replace(ItemMeta meta, Attribute attribute, String keyName, double amount,
                         EquipmentSlotGroup group) {
        Collection<AttributeModifier> existing = meta.getAttributeModifiers(attribute);
        if (existing != null) {
            for (AttributeModifier m : new ArrayList<>(existing)) {
                NamespacedKey key = m.getKey();
                if (key != null && keyName.equals(key.getKey())) {
                    meta.removeAttributeModifier(attribute, m);
                }
            }
        }
        if (Math.abs(amount) > 1e-9) {
            meta.addAttributeModifier(attribute, new AttributeModifier(
                    new NamespacedKey(plugin, keyName), amount,
                    AttributeModifier.Operation.ADD_NUMBER, group));
        }
    }

    // ------------------------------------------------------------ Lore

    /**
     * 重写 Lore 里的「锻造等级」一行，并在它下面补一行「履历」。
     *
     * <p>履历只在有过失败记录时出现；每次重写都先清掉旧履历行，
     * 所以反复强化也只会留一行。</p>
     */
    private void rewriteLore(ItemStack item, int level, int cap, double mult) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        var lore = meta.lore() == null
                ? new ArrayList<net.kyori.adventure.text.Component>()
                : new ArrayList<>(meta.lore());
        // 先清旧履历 / 旧锻阶，避免重复 / 错位
        for (int i = lore.size() - 1; i >= 0; i--) {
            String p = Msg.plain(lore.get(i));
            if (p.startsWith("履历") || p.startsWith("锻阶")) {
                lore.remove(i);
            }
        }
        var comp = Msg.mm(strengthenLine(level, cap, mult))
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
        int at = -1;
        for (int i = 0; i < lore.size(); i++) {
            if (Msg.plain(lore.get(i)).startsWith("锻造等级")) {
                lore.set(i, comp);
                at = i;
                break;
            }
        }
        if (at < 0) {
            int insert = -1;
            for (int i = 0; i < lore.size(); i++) {
                if (Msg.plain(lore.get(i)).startsWith("耐久")) {
                    insert = i + 1;
                    break;
                }
            }
            at = insert >= 0 && insert <= lore.size() ? insert : lore.size();
            lore.add(at, comp);
        }
        // 锻阶行紧跟锻造等级行：蜕变过才画（evolutionLine 对 0 阶返回 null）
        com.forgemaster.evolve.EvolutionService evo = plugin.evolutions();
        int historyAt = at + 1;
        if (evo != null && evo.enabled()) {
            int stage = evo.stageOf(item);
            String evoLine = com.forgemaster.evolve.EvolutionService
                    .evolutionLine(evo.registry(), stage, evo.failsOf(item));
            if (evoLine != null) {
                lore.add(Math.min(lore.size(), at + 1),
                        Msg.mm(evoLine).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                historyAt = at + 2;
            }
        }
        String history = historyLine(item);
        if (history != null) {
            lore.add(Math.min(lore.size(), historyAt),
                    Msg.mm(history).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        com.forgemaster.util.Attrs.apply(item, meta);
    }

    /**
     * 履历行（MiniMessage）；没有失败记录返回 null。
     *
     * <p>格式：{@code 履历: 失败 3 次 · 碎剑 1 次 · 投入 47 个铁锭}</p>
     */
    public String historyLine(ItemStack item) {
        int fails = failsOf(item);
        if (fails <= 0) {
            return null;
        }
        int broken = brokenOf(item);
        int invest = investOf(item);
        Material mt = materialItemOf(item);
        String what = mt == null ? "材料" : MaterialGrades.pretty(mt);
        return "<dark_gray>履历: 失败 " + fails + " 次 · 碎剑 " + broken + " 次 · 投入 "
                + invest + " 个" + what + "</dark_gray>";
    }

    /** 锻造等级显示的文本（MiniMessage） */
    public static String strengthenLine(int level, int cap, double mult) {
        String color = level >= cap ? "#ffd166" : level >= cap * 0.75 ? "#7fd3ff" : "#9e9e9e";
        // 纯文本必须以「锻造等级」开头：刷新这一行时按前缀定位
        return "<gray>锻造等级 <dark_gray>▸</dark_gray> <" + color + ">+" + level + "</" + color + ">"
                + "<dark_gray>/+" + cap + "</dark_gray> <dark_gray>(基础属性 +"
                + Msg.fmt((mult - 1.0) * 100, 0) + "%)</dark_gray>";
    }

    // ------------------------------------------------------------ 辅助

    /** 从 PDC 反查剑胚 / 装备胚；查不到返回 null */
    public ForgeMaterial materialOf(ItemStack item) {
        // 保留 items().materialIdOf 的 FORGED 门：非锻造品一律 null（原语义不变）；
        // 解析本体（注册表直查 + armor- 装备 id 还原）统一走 MaterialLookup（audit 04 D5）
        String id = plugin.items().materialIdOf(item);
        return id == null ? null : com.forgemaster.util.MaterialLookup.resolve(plugin, id);
    }

    public Quality qualityOf(ItemStack item) {
        if (!plugin.items().isForged(item)) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        // FORGED 门已过，meta 理论上不会为 null，保留分支防意外；键值非法兜底 COMMON 而非外泄 null
        return meta == null ? null : com.forgemaster.util.MaterialLookup.qualityOf(meta);
    }

    /** 给玩家看的当前状态 */
    public String describe(ItemStack item) {
        int level = levelOf(item);
        if (level < 0) {
            return "<red>这不是锻造品。";
        }
        int cap = capOf(item);
        double mult = 1.0 + bonusRatio(level);
        StringBuilder sb = new StringBuilder();
        sb.append("<gray>锻造等级: <white>+").append(level).append("<gray>/<white>+").append(cap);
        sb.append(" <dark_gray>·</dark_gray> <gray>基础属性 <aqua>+")
                .append(Msg.fmt((mult - 1.0) * 100, 0)).append("%</aqua>");
        if (level >= cap) {
            sb.append(" <dark_gray>(已满)</dark_gray>");
            appendFurnace(sb, item, level);
            return sb.toString();
        }
        Material costItem = materialItemOf(item);
        int need = materialAmountOf(item, level);
        double plainChance = baseSuccessChance(level);
        double realChance = successChance(level);
        double catchup = seasonCatchupBonus(level);
        sb.append("<newline><gray>下一级 <white>+").append(level + 1).append("</white>");
        sb.append("<newline><gray>成功率: <").append(com.forgemaster.util.UiPalette.chanceColor(realChance)).append(">")
                .append(Msg.fmt(realChance * 100, 0)).append("%</>");
        // 赛季追赶：真实成功率比基础曲线高，就把「高在哪」写出来（≤ 0.5% 的零头不啰嗦）
        if (catchup > 0.0005) {
            sb.append(" <green>（赛季追赶 +").append(Msg.fmt(catchup * 100, 0)).append("%）</green>")
                    .append(" <dark_gray>基础 ").append(Msg.fmt(plainChance * 100, 0)).append("%</dark_gray>");
        }
        sb.append(" <dark_gray>· 失败碎裂 ").append(Msg.fmt(breakChance(level) * 100, 0)).append("%")
                .append(downgradeOnFail() ? " · 不碎则降 1 级" : "").append("</dark_gray>");
        sb.append("<newline><gray>消耗: <white>").append(need).append("</white> 个 ")
                .append(costItem == null ? "材料" : MaterialGrades.pretty(costItem));
        sb.append(" <gray>+ <yellow>").append(costOf(item, level)).append("</yellow> 剑币");
        appendFurnace(sb, item, level);
        String history = historyLine(item);
        if (history != null) {
            sb.append("<newline>").append(history);
        }
        return sb.toString();
    }

    /** 炉火进度：满则提示下一锻必成 */
    private void appendFurnace(StringBuilder sb, ItemStack item, int level) {
        int cur = furnaceOf(item);
        int need = furnaceNeed(level);
        if (cur <= 0) {
            return;
        }
        sb.append("<newline><gray>炉火: <").append(cur >= need ? "#ffd166" : "#ff9a3c").append(">")
                .append(cur).append("</").append(cur >= need ? "#ffd166" : "#ff9a3c")
                .append("><dark_gray>/</dark_gray><white>").append(need).append("</white>");
        if (cur >= need) {
            sb.append(" <gold>（下一锻必成）</gold>");
        }
    }

    // ------------------------------------------------------------ PDC 计数

    private static int intOf(ItemStack item, NamespacedKey key) {
        if (item == null || item.getType().isAir() || key == null) {
            return 0;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return 0;
        }
        Integer v = meta.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
        return v == null ? 0 : Math.max(0, v);
    }

    private static void writeInt(ItemStack item, NamespacedKey key, int value) {
        if (item == null || item.getType().isAir() || key == null) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, Math.max(0, value));
        com.forgemaster.util.Attrs.apply(item, meta);
    }

    private static void addInt(ItemStack item, NamespacedKey key, int delta) {
        if (delta == 0) {
            return;
        }
        writeInt(item, key, intOf(item, key) + delta);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
