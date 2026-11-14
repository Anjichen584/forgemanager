package com.forgemaster.stats;

import com.forgemaster.quality.Quality;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家锻造档案（data.yml）：统计、专精等级、图鉴、熔炼次数。异步落盘。
 */
public final class StatsStore {

    public static final class Stats {
        public int forged;
        public double bestScore;
        public String bestQuality = "";
        public Map<String, Integer> byQuality = new HashMap<>();
        public String name = "";

        // 专精
        public int level = 1;
        public int exp;
        /** v2.9 天赋点分配：talent id → 已投点数（没点过的键不存） */
        public Map<String, Integer> talents = new LinkedHashMap<>();

        // 图鉴："materialId:QUALITY"
        public Set<String> codex = new LinkedHashSet<>();
        public Set<String> patterns = new LinkedHashSet<>();
        public int salvaged;
        public int flawless;

        // v1.2
        public int reputation;
        public int commissionsDone;
        public int commissionsFailed;
        public int duelWins;
        public int duelLosses;
        public int repaired;

        // v2.0
        // v2.26.4：volatile——异步冲刷线程无锁直读本字段，x64 上 long 写虽通常原子
        // 但无语言保证，一行换掉理论上的撕裂读
        public volatile long coins;
        public int streak;
        public int bestStreak;
        public long streakAt;
        public String mentor = "";
        public String disciple = "";
        /** v2.0 师徒：徒弟列表（师父侧，存 UUID 字符串）；mentor 为师父 UUID 字符串 */
        public Set<String> disciples = new LinkedHashSet<>();
        /** v2.0 师徒：上次解除关系的时间戳（毫秒），0 = 无冷却记录 */
        public long mentorCooldownAt;
        /** v2.19 出师：已出师标记（玩家 PDC 上另有 fm_graduated 标记互为印证，这里管落盘与离线查询） */
        public boolean graduated;
        /** v2.19 出师：出师时间戳（毫秒），0 = 早期数据未记录 */
        public long graduatedAt;
        /** v2.19 出师：师父侧已出师徒弟（UUID 字符串；出师即解除绑定，disciples 里不会再有他们） */
        public Set<String> graduates = new LinkedHashSet<>();
        public int spirits;
        public int tombs;
        public int achvPoints;
        public Set<String> achievements = new LinkedHashSet<>();

        // v2.0 · B4 匠衔段位
        /** 段位档序号（0 = 第一档），由 CraftRankManager 维护 */
        public int rankTier;
        /** 段位所在赛季编号，跨季时按 craft-rank.season-fall 回落 */
        public int rankSeason;

        // v2.5 强化：历史最高锻造等级（只涨不跌，排行「强化」维度用）
        public int bestStrengthen;
    }

    /** 声望称号：{最低声望, 称号} */
    private static final String[][] TITLES = {
            {"0", "学徒"},
            {"100", "匠人"},
            {"300", "宗师"},
            {"800", "大匠"},
            {"2000", "铸剑圣手"}
    };

    public static String titleOf(int reputation) {
        String best = TITLES[0][1];
        for (String[] row : TITLES) {
            if (reputation >= Integer.parseInt(row[0])) {
                best = row[1];
            }
        }
        return best;
    }

    /** 距离下一档称号还差多少声望（已满档返回 0） */
    public static int reputationToNextTitle(int reputation) {
        for (String[] row : TITLES) {
            int need = Integer.parseInt(row[0]);
            if (reputation < need) {
                return need - reputation;
            }
        }
        return 0;
    }

    private final Plugin plugin;
    private final File file;
    // v2.25.2（审计轮4）：裸 HashMap → ConcurrentHashMap。
    // 剑币主账这条路径上，主线程 record()/addCoins 会 computeIfAbsent 新档案（触发 resize），
    // 而异步自动保存线程同时在拷快照 —— HashMap 在这种并发下不只是抛 CME，
    // 结构都可能被写坏。换 CHM 之后「同一份数据被两个线程同时摸」才真的安全；
    // 下面 save0() 里那句 catch 保留当兜底（防御式，不该再触发）。
    private final Map<UUID, Stats> cache = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean dirty;
    private int saveTaskId = -1;
    /**
     * 落盘写锁：异步自动保存与主线程 save()（onDisable / BackupManager）可能同时执行，
     * 两个线程同时 {@code yml.save(file)} 写同一文件时「最后写者胜」，极端交错会写出
     * 半截 yaml 造成整份档案损坏——用互斥保证「组装 + 写文件」整段原子（audit 02-stability #13）。
     */
    private final Object saveLock = new Object();

    public StatsStore(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    public void load() {
        cache.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = com.forgemaster.util.YamlIo.loadOrQuarantine(file, this.getClass().getSimpleName(), plugin.getLogger());
        ConfigurationSection root = yml.getConfigurationSection("players");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec == null) {
                continue;
            }
            try {
                Stats st = new Stats();
                st.name = sec.getString("name", "");
                st.forged = sec.getInt("forged", 0);
                st.bestScore = sec.getDouble("best-score", 0);
                st.bestQuality = sec.getString("best-quality", "");
                st.level = Math.max(1, sec.getInt("level", 1));
                st.exp = Math.max(0, sec.getInt("exp", 0));
                ConfigurationSection ts = sec.getConfigurationSection("talents");
                if (ts != null) {
                    for (String tid : ts.getKeys(false)) {
                        int n = Math.max(0, ts.getInt(tid, 0));
                        // 未知 id（改版残留）直接丢弃：否则它计入 spent，可用点数被幽灵键吃掉
                        if (n > 0 && com.forgemaster.growth.Talents.Talent.byName(tid) != null) {
                            st.talents.put(tid.toLowerCase(java.util.Locale.ROOT), n);
                        }
                    }
                }
                st.salvaged = sec.getInt("salvaged", 0);
                st.flawless = sec.getInt("flawless", 0);
                st.reputation = sec.getInt("reputation", 0);
                st.commissionsDone = sec.getInt("commissions-done", 0);
                st.commissionsFailed = sec.getInt("commissions-failed", 0);
                st.duelWins = sec.getInt("duel-wins", 0);
                st.duelLosses = sec.getInt("duel-losses", 0);
                st.repaired = sec.getInt("repaired", 0);
            st.coins = sec.getLong("coins", 0);
            st.streak = sec.getInt("streak", 0);
            st.bestStreak = sec.getInt("best-streak", 0);
            st.streakAt = sec.getLong("streak-at", 0);
            st.mentor = sec.getString("mentor", "");
            st.disciple = sec.getString("disciple", "");
            st.disciples = new LinkedHashSet<>(sec.getStringList("disciples"));
            st.mentorCooldownAt = sec.getLong("mentor-cooldown-at", 0);
            st.graduated = sec.getBoolean("graduated", false);
            st.graduatedAt = sec.getLong("graduated-at", 0);
            st.graduates = new LinkedHashSet<>(sec.getStringList("graduates"));
            st.spirits = sec.getInt("spirits", 0);
            st.tombs = sec.getInt("tombs", 0);
            st.achvPoints = sec.getInt("achv-points", 0);
            st.bestStrengthen = Math.max(0, sec.getInt("best-strengthen", 0));
                st.rankTier = Math.max(0, sec.getInt("rank-tier", 0));
                st.rankSeason = Math.max(0, sec.getInt("rank-season", 0));
            st.achievements = new LinkedHashSet<>(sec.getStringList("achievements"));
                st.codex.addAll(sec.getStringList("codex"));
                st.patterns.addAll(sec.getStringList("patterns"));
                ConfigurationSection qs = sec.getConfigurationSection("by-quality");
                if (qs != null) {
                    for (String q : qs.getKeys(false)) {
                        st.byQuality.put(q, qs.getInt(q, 0));
                    }
                }
                cache.put(UUID.fromString(key), st);
            } catch (Exception ignored) {
                // 坏数据跳过，不影响启动
            }
        }
        plugin.getLogger().info("已载入 " + cache.size() + " 份锻造档案");
    }

    public Stats get(UUID uuid) {
        return cache.computeIfAbsent(uuid, k -> new Stats());
    }

    // ------------------------------------------------------------ 强化等级（v2.5）

    /**
     * 记一次锻造等级，**只涨不跌**。
     *
     * <p>强化失败会掉级，但「历史最高」不该跟着掉——排行榜要的是这把剑
     * 曾经锻到过哪儿，不是此刻在哪儿。</p>
     *
     * @return 是否刷新了纪录
     */
    public boolean recordStrengthen(UUID uuid, int level) {
        if (uuid == null || level <= 0) {
            return false;
        }
        Stats st = get(uuid);
        if (level <= st.bestStrengthen) {
            return false;
        }
        st.bestStrengthen = level;
        dirty = true;
        return true;
    }

    /** 历史最高锻造等级，没记录返回 0 */
    public int bestStrengthenOf(UUID uuid) {
        if (uuid == null) {
            return 0;
        }
        // 走 cache.get 而不是 get(uuid)：查询不该给陌生 UUID 建出一份空档案
        Stats st = cache.get(uuid);
        return st == null ? 0 : st.bestStrengthen;
    }

    /**
     * 只读查看档案：没有返回 null，<b>不创建</b>空档案。
     *
     * <p>PlaceholderAPI 桥这类高频查询用这条路径——{@link #get(UUID)} 会被
     * PAPI 对离线玩家的查询打出一堆空档案，落盘后把 data.yml 撑爆。</p>
     */
    public Stats peek(UUID uuid) {
        return uuid == null ? null : cache.get(uuid);
    }

    /** 所有有过强化记录的玩家（排行榜用），按等级降序 */
    public java.util.List<UUID> strengthenRanked() {
        java.util.List<UUID> out = new java.util.ArrayList<>();
        for (Map.Entry<UUID, Stats> e : cache.entrySet()) {
            if (e.getValue().bestStrengthen > 0) {
                out.add(e.getKey());
            }
        }
        out.sort((a, b) -> Integer.compare(
                get(b).bestStrengthen, get(a).bestStrengthen));
        return out;
    }

    /**
     * 升到下一级所需经验。
     *
     * <p>v2.9 起改成「基数 + 每级增量」两段可配（{@code progression.curve-base} /
     * {@code curve-per-level}），默认 120 + 55×Lv：比 v2.8 的 80 + 40×Lv 明显更陡，
     * 早期几级不再是一两把剑就过。</p>
     */
    public static int expToNext(int level) {
        return Math.max(1, expCurveBase + Math.max(0, level) * expCurvePerLevel);
    }

    /**
     * 一次锻造能拿多少经验（纯函数，自检直接算）。
     *
     * <p>v2.9 砍过一刀：原来 10 + 8×品质 + 分数/10，一把神话满分的剑能一次拿 60；
     * 现在 6 + 5×品质 + 分数×0.05，同样的剑只有 36，普通精良剑 ~25（原 ~37）。</p>
     */
    public static int expGain(double score, int qualityOrdinal) {
        double raw = expGainBase
                + Math.max(0, qualityOrdinal) * expGainPerQuality
                + Math.max(0, score) * expGainPerScore;
        return Math.max(1, (int) Math.round(raw));
    }

    /** 专精等级上限 */
    public static int maxLevel() {
        return Math.max(1, expMaxLevel);
    }

    /**
     * 把 {@code progression.*} 灌进来（插件启用 / reload 时调一次）。
     *
     * <p>用静态字段而不是每次读 config：{@code expToNext} 在排行、面板、自检里都是静态调用，
     * 而且 tick 里也会经过等级加成，热路径上不该反复查配置。</p>
     */
    public static void configureExp(int curveBase, int curvePerLevel,
                                    double gainBase, double gainPerQuality,
                                    double gainPerScore, int maxLevel) {
        expCurveBase = Math.max(1, curveBase);
        expCurvePerLevel = Math.max(0, curvePerLevel);
        expGainBase = Math.max(1, gainBase);
        expGainPerQuality = Math.max(0, gainPerQuality);
        expGainPerScore = Math.max(0, gainPerScore);
        expMaxLevel = Math.max(1, maxLevel);
    }

    private static int expCurveBase = 120;
    private static int expCurvePerLevel = 55;
    private static double expGainBase = 6;
    private static double expGainPerQuality = 5;
    private static double expGainPerScore = 0.05;
    private static int expMaxLevel = 60;

    /**
     * 记一笔锻造：统计 + 图鉴 + 专精经验。
     *
     * @return 升了几级（0 = 没升）
     */
    public int record(UUID uuid, String name, Quality quality, double score, String materialId, String pattern) {
        return record(uuid, name, quality, score, materialId, pattern, 1.0);
    }

    /**
     * 同上，但经验倍率由调用方给——锻造走 {@code session.talents().expMultiplier()}，
     * 即「开工那一刻固化」的那份；天赋「勤学」中途加点 / 洗点不影响正在结算的这一炉。
     */
    public int record(UUID uuid, String name, Quality quality, double score, String materialId, String pattern,
                      double expMultiplier) {
        Stats st = get(uuid);
        st.name = name;
        st.forged++;
        st.byQuality.merge(quality.name(), 1, Integer::sum);
        if (score > st.bestScore) {
            st.bestScore = score;
            st.bestQuality = quality.name();
        }
        st.codex.add(materialId.toLowerCase() + ":" + quality.name());
        if (pattern != null && !pattern.isBlank()) {
            st.patterns.add(pattern);
        }
        if (score >= 99.0) {
            st.flawless++;
        }

        int gained = expGain(score, quality.ordinal());
        // 天赋「勤学」：用调用方传入的固化倍率，不用实时档案（中途洗点不改这一炉的结果）
        gained = Math.max(1, (int) Math.round(gained * Math.max(0.0, expMultiplier)));
        int levels = 0;
        if (plugin.getConfig().getBoolean("settings.progression-enabled", true)) {
            // 满级后经验不再堆积：否则 exp 涨到几万，日后调大 max-level 会一次连跳、天赋点瞬间爆仓
            if (st.level < maxLevel()) {
                st.exp += gained;
            }
            while (st.exp >= expToNext(st.level) && st.level < maxLevel()) {
                st.exp -= expToNext(st.level);
                st.level++;
                levels++;
            }
        }
        dirty = true;
        return levels;
    }

    /** 外部直接改了档案字段（天赋点、剑冢记录等）后调用，触发下一次异步落盘 */
    public void markDirty() {
        dirty = true;
    }

    /** 加声望，返回是否跨了称号档 */
    public boolean addReputation(UUID uuid, int amount) {
        Stats st = get(uuid);
        String before = titleOf(st.reputation);
        st.reputation = Math.max(0, st.reputation + amount);
        dirty = true;
        return !before.equals(titleOf(st.reputation));
    }

    /**
     * 扣声望（v2.24 声望兑换所专用）：余额不足返回 false 且不扣，够才扣并标脏。
     *
     * <p>与 {@link #addReputation} 同一份 {@code Stats.reputation} 字段，不存在两本账；
     * 不走 addReputation(负数) 是因为那会把不足扣成 0（Math.max(0, …) 兜底），
     * 兑换必须「够才扣」，语义与 takeCoins 对齐。</p>
     */
    public boolean spendReputation(UUID uuid, int amount) {
        if (amount <= 0) {
            return true;
        }
        Stats st = get(uuid);
        if (st.reputation < amount) {
            return false;
        }
        st.reputation -= amount;
        dirty = true;
        return true;
    }

    // ------------------------------------------------------------ v2.0 剑币

    public long coins(UUID uuid) {
        return get(uuid).coins;
    }

    // ---- v2.20 经济流水计数（服主数据看板的地基）----
    //
    // 只在这里计数，别处不许再自己加。三个收口点：addCoins / takeCoins / resetSeasonal。
    // 计数是「服务器生命周期累计」，不参与赛季重置（resetSeasonal 不碰它们）。
    private long producedTotal;
    private long consumedTotal;
    private final Map<String, Long> producedBy = new java.util.LinkedHashMap<>();
    private final Map<String, Long> consumedBy = new java.util.LinkedHashMap<>();

    /** 加剑币，返回加后的余额。理由缺省 = 其它产出（新调用点请用带理由的重载） */
    public long addCoins(UUID uuid, long amount) {
        return addCoins(uuid, amount, com.forgemaster.econ.CoinReason.OTHER_IN);
    }

    /** 加剑币并记流水理由，返回加后的余额 */
    public long addCoins(UUID uuid, long amount, com.forgemaster.econ.CoinReason reason) {
        Stats st = get(uuid);
        // v2.26.4：饱和加法——原来 Math.max(0, coins+amount) 在溢出为负时把余额清零
        // （管理员 give 两次 Long.MAX 即可触发），现在饱和到 Long.MAX_VALUE
        long sum = st.coins + amount;
        st.coins = (sum < 0 && amount > 0) ? Long.MAX_VALUE : Math.max(0, sum);
        if (amount > 0) {
            com.forgemaster.econ.CoinReason r = reason == null
                    ? com.forgemaster.econ.CoinReason.OTHER_IN : reason;
            producedTotal += amount;
            producedBy.merge(r.key(), amount, Long::sum);
        }
        dirty = true;
        return st.coins;
    }

    /** 扣剑币，不够返回 false 且不扣。理由缺省 = 其它消耗 */
    public boolean takeCoins(UUID uuid, long amount) {
        return takeCoins(uuid, amount, com.forgemaster.econ.CoinReason.OTHER_OUT);
    }

    /** 扣剑币并记流水理由，不够返回 false 且不扣（不扣就不记） */
    public boolean takeCoins(UUID uuid, long amount, com.forgemaster.econ.CoinReason reason) {
        Stats st = get(uuid);
        if (amount <= 0) {
            return true;
        }
        if (st.coins < amount) {
            return false;
        }
        st.coins -= amount;
        com.forgemaster.econ.CoinReason r = reason == null
                ? com.forgemaster.econ.CoinReason.OTHER_OUT : reason;
        consumedTotal += amount;
        consumedBy.merge(r.key(), amount, Long::sum);
        dirty = true;
        return true;
    }

    // ------------------------------------------------------------ 流水快照（只读，报表用）

    /** 全服累计产出（剑币） */
    public long producedTotal() {
        return producedTotal;
    }

    /** 全服累计消耗（剑币） */
    public long consumedTotal() {
        return consumedTotal;
    }

    /** 按理由分组的累计产出；键是 {@link com.forgemaster.econ.CoinReason#key()} */
    public Map<String, Long> producedByReason() {
        return java.util.Collections.unmodifiableMap(producedBy);
    }

    /** 按理由分组的累计消耗 */
    public Map<String, Long> consumedByReason() {
        return java.util.Collections.unmodifiableMap(consumedBy);
    }

    /** 当前全服剑币总量（遍历缓存求和） */
    public long totalCoins() {
        long sum = 0;
        synchronized (cache) {
            for (Stats st : cache.values()) {
                sum += Math.max(0, st.coins);
            }
        }
        return sum;
    }

    /** 有档案的玩家数（含离线） */
    public int playerCount() {
        synchronized (cache) {
            return cache.size();
        }
    }

    /** 所有玩家的剑币余额快照（报表算中位数 / 基尼系数用；返回副本，调用方可随意排序） */
    public java.util.List<Long> coinBalances() {
        java.util.List<Long> out = new java.util.ArrayList<>();
        synchronized (cache) {
            for (Stats st : cache.values()) {
                out.add(Math.max(0, st.coins));
            }
        }
        return out;
    }

    /** 把流水计数清零（自检 / 服主手动重置用） */
    public void resetFlowCounters() {
        producedTotal = 0;
        consumedTotal = 0;
        producedBy.clear();
        consumedBy.clear();
    }

    /**
     * 赛季重置：清当季剑币 / 声望 / 连锻（含最高连锻）。
     * 成就、图鉴、专精、锻造统计保留。调用方负责先归档再调用，并手动 save()。
     */
    public void resetSeasonal() {
        synchronized (cache) {
            for (Stats st : cache.values()) {
                st.coins = 0;
                st.reputation = 0;
                st.streak = 0;
                st.streakAt = 0;
                st.bestStreak = 0;
            }
        }
        dirty = true;
    }

    // ------------------------------------------------------------ v2.0 连锻链

    /** 连锻链断链静默期：超过这个毫秒数就归零 */
    public static final long STREAK_WINDOW_MS = 3_600_000L;

    /**
     * 记录一次成功锻造对连锻链的影响。
     *
     * @return 本次的链数（1 = 刚起链）
     */
    public int bumpStreak(UUID uuid) {
        Stats st = get(uuid);
        long now = System.currentTimeMillis();
        if (st.streakAt > 0 && now - st.streakAt > STREAK_WINDOW_MS) {
            st.streak = 0;
        }
        st.streak++;
        st.streakAt = now;
        if (st.streak > st.bestStreak) {
            st.bestStreak = st.streak;
        }
        dirty = true;
        return st.streak;
    }

    /**
     * 断链（炸剑 / 逾期等）。
     *
     * @return 断掉之前的链数
     */
    public int breakStreak(UUID uuid) {
        Stats st = get(uuid);
        int old = st.streak;
        st.streak = 0;
        st.streakAt = 0;
        dirty = true;
        return old;
    }

    /** 连锻链的剑币倍率：第 N 连 ×(1 + 0.15(N-1))，上限 ×3 */
    public static double streakMultiplier(int streak) {
        if (streak <= 1) {
            return 1.0;
        }
        return Math.min(3.0, 1.0 + 0.15 * (streak - 1));
    }

    // ------------------------------------------------------------ v2.0 师徒

    public String mentorOf(UUID uuid) {
        return get(uuid).mentor;
    }

    public String discipleOf(UUID uuid) {
        return get(uuid).disciple;
    }

    public void setMentor(UUID uuid, String mentorName) {
        get(uuid).mentor = mentorName == null ? "" : mentorName;
        dirty = true;
    }

    public void setDisciple(UUID uuid, String discipleName) {
        get(uuid).disciple = discipleName == null ? "" : discipleName;
        dirty = true;
    }

    /** 师父侧：徒弟 UUID 列表（副本） */
    public List<String> discipleIds(UUID uuid) {
        return new ArrayList<>(get(uuid).disciples);
    }

    /** 师父侧：加一个徒弟（apprenticeId 为 UUID 字符串） */
    public void addDisciple(UUID master, String apprenticeId) {
        get(master).disciples.add(apprenticeId);
        dirty = true;
    }

    /** 师父侧：移除一个徒弟 */
    public void removeDisciple(UUID master, String apprenticeId) {
        get(master).disciples.remove(apprenticeId);
        dirty = true;
    }

    /** 上次解除师徒关系的时间戳（毫秒），0 = 无 */
    public long mentorCooldownAt(UUID uuid) {
        return get(uuid).mentorCooldownAt;
    }

    public void markMentorCooldown(UUID uuid, long at) {
        get(uuid).mentorCooldownAt = at;
        dirty = true;
    }

    // ------------------------------------------------------------ v2.19 出师

    /** 该玩家是否已出师 */
    public boolean isGraduated(UUID uuid) {
        return uuid != null && get(uuid).graduated;
    }

    /** 出师落档：标记 + 时间戳（玩家 PDC 上的 fm_graduated 标记由 GraduationService 另行打上） */
    public void markGraduated(UUID uuid, long at) {
        Stats st = get(uuid);
        st.graduated = true;
        st.graduatedAt = Math.max(0, at);
        dirty = true;
    }

    /** 师父侧：已出师徒弟列表（副本） */
    public List<String> graduateIds(UUID uuid) {
        return new ArrayList<>(get(uuid).graduates);
    }

    /** 师父侧：把徒弟移入已出师名单 */
    public void addGraduate(UUID master, String apprenticeId) {
        get(master).graduates.add(apprenticeId);
        dirty = true;
    }

    // ------------------------------------------------------------ v2.0 成就

    /** 达成一个成就，返回是否首次达成 */
    public boolean grantAchievement(UUID uuid, String id, int points) {
        Stats st = get(uuid);
        if (!st.achievements.add(id)) {
            return false;
        }
        st.achvPoints += points;
        dirty = true;
        return true;
    }

    /** 剑灵觉醒计数 +1（B3 成就用） */
    public void countSpirit(UUID uuid) {
        get(uuid).spirits++;
        dirty = true;
    }

    /** 剑冢埋剑计数 +1（B3 成就用） */
    public void countTomb(UUID uuid) {
        get(uuid).tombs++;
        dirty = true;
    }

    /** 设置段位（B4，CraftRankManager 专用） */
    public void setRank(UUID uuid, int tier, int season) {
        Stats st = get(uuid);
        st.rankTier = Math.max(0, tier);
        st.rankSeason = Math.max(0, season);
        dirty = true;
    }

    public boolean hasAchievement(UUID uuid, String id) {
        return get(uuid).achievements.contains(id);
    }

    public int achievementCount(UUID uuid) {
        return get(uuid).achievements.size();
    }

    public void countCommission(UUID uuid, boolean done) {
        Stats st = get(uuid);
        if (done) {
            st.commissionsDone++;
        } else {
            st.commissionsFailed++;
        }
        dirty = true;
    }

    public void countDuel(UUID uuid, boolean win) {
        Stats st = get(uuid);
        if (win) {
            st.duelWins++;
        } else {
            st.duelLosses++;
        }
        dirty = true;
    }

    public void countRepair(UUID uuid) {
        get(uuid).repaired++;
        dirty = true;
    }


    public void recordSalvage(UUID uuid) {
        Stats st = get(uuid);
        st.salvaged++;
        dirty = true;
    }

    /** 专精体系总开关（settings.progression-enabled），关掉后所有等级加成归零 */
    private static boolean progressionOn = true;

    public static void progressionEnabled(boolean on) {
        progressionOn = on;
    }

    public static boolean progressionEnabled() {
        return progressionOn;
    }

    /** 专精加成：高温容差倍率 */
    public static double toleranceMultiplier(int level) {
        if (!progressionOn) {
            return 1.0;
        }
        return 1.0 + Math.min(0.30, Math.max(0, level - 1) * 0.01);
    }

    /** 专精加成：命中区额外格数（每 8 级 +1，最多 +1） */
    public static int extraZoneWidth(int level) {
        if (!progressionOn) {
            return 0;
        }
        return level >= 8 ? 1 : 0;
    }

    /** 专精加成：折叠序列额外时间（秒） */
    public static double extraFoldSeconds(int level) {
        if (!progressionOn) {
            return 0.0;
        }
        return Math.min(2.0, Math.max(0, level - 1) * 0.06);
    }

    /** 专精加成：开刃目标区额外宽度 */
    public static double extraGrindWidth(int level) {
        if (!progressionOn) {
            return 0.0;
        }
        return Math.min(3.0, Math.max(0, level - 1) * 0.1);
    }

    /** 专精加成：词条额外概率（0~1），Lv10 起每 5 级 +10% */
    public double extraAffixChance(int level) {
        if (!progressionOn) {
            return 0.0;
        }
        int steps = Math.max(0, (level - 10) / 5);
        return Math.min(0.6, steps * 0.1);
    }

    /** 当前缓存的快照（排行 / 赛季归档用，避免遍历时被并发修改） */
    public List<Map.Entry<UUID, Stats>> snapshot() {
        List<Map.Entry<UUID, Stats>> out = new ArrayList<>();
        synchronized (cache) {
            for (Map.Entry<UUID, Stats> e : cache.entrySet()) {
                out.add(new java.util.AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue()));
            }
        }
        return out;
    }

    public List<Map.Entry<UUID, Stats>> top(int limit) {
        List<Map.Entry<UUID, Stats>> list = new ArrayList<>();
        for (Map.Entry<UUID, Stats> e : cache.entrySet()) {
            if (!"SelfTest".equals(e.getValue().name)) { // 自检数据不进排行榜
                list.add(e);
            }
        }
        list.sort(Comparator.comparingDouble((Map.Entry<UUID, Stats> e) -> e.getValue().bestScore).reversed());
        return list.subList(0, Math.min(limit, list.size()));
    }

    public List<Map.Entry<UUID, Stats>> topByLevel(int limit) {
        List<Map.Entry<UUID, Stats>> list = new ArrayList<>();
        for (Map.Entry<UUID, Stats> e : cache.entrySet()) {
            if (!"SelfTest".equals(e.getValue().name)) {
                list.add(e);
            }
        }
        list.sort(Comparator.comparingInt((Map.Entry<UUID, Stats> e) -> e.getValue().level).reversed());
        return list.subList(0, Math.min(limit, list.size()));
    }

    public void startAutoSave(int intervalTicks) {
        if (saveTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(saveTaskId);
        }
        saveTaskId = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!dirty) {
                return;
            }
            // v2.25.2（4 并发查错）：Bukkit 的重复任务只要抛一次异常就被<b>永久取消</b>，
            // 而且不会再有人把它起回来 —— 于是 data.yml 从此只能靠关服那次 save()。
            // 4 个玩家同时锻造时快照拷贝撞 CME 的概率不低，所以这里必须兜住，
            // 异常只记日志、脏标记保持，下一轮（5 秒后）照旧重试。
            try {
                save();
            } catch (RuntimeException ex) {
                // save0 内部已经把 IOException 收成日志；这里兜的是快照/组装期的运行时异常（如 CME），
                // 同样只记日志、脏标记保持，下一轮照旧重试，绝不让重复任务被取消
                plugin.getLogger().warning("data.yml 异步冲刷失败（保持脏，下一轮重试）：" + ex);
            }
        }, intervalTicks, intervalTicks).getTaskId();
    }

    public void save() {
        // 写锁覆盖「快照组装 + yml.save」整段：主线程 save() 与异步自动保存撞车时排队执行，
        // 不再并发写同一文件。锁内只有 yaml 组装与文件 IO，不摸 Bukkit API，主线程拿锁也只会等一次落盘
        synchronized (saveLock) {
            save0();
        }
    }

    private void save0() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("无法创建插件数据目录");
            return;
        }
        YamlConfiguration yml = new YamlConfiguration();
        Map<UUID, Stats> snapshot;
        boolean race = false;
        try {
            synchronized (cache) {
                snapshot = new LinkedHashMap<>(cache);
            }
        } catch (java.util.ConcurrentModificationException ex) {
            // v2.25.2：主线程 record()/addCoins 往 cache 里塞档案时<b>不持</b> cache 锁，
            // 这把锁保护不到被保护对象 —— 拷快照这一步就可能撞 CME。
            // 以前它在 try 之外，抛出去会连带打死异步冲刷任务；现在只当「这一轮没拷成」：
            // 不清脏、直接返回，下一轮重拷。
            return;
        }
        for (Map.Entry<UUID, Stats> e : snapshot.entrySet()) {
            String base = "players." + e.getKey();
            // 主线程可能在 record() / 师徒 / 成就里改同一份档案：
            // 一条 CME 会让整份 data.yml 写不出去，按玩家兜住，脏标记保持等下一轮
            try {
                writePlayer(yml, base, e.getValue());
            } catch (java.util.ConcurrentModificationException ex) {
                race = true;
            }
        }
        try {
                com.forgemaster.util.YamlIo.save(file, yml);
            // 落盘期间又有新改动（或撞上 CME）就保持脏，下一轮重存，别把新数据标记清掉
            if (!race) {
                dirty = false;
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("保存 data.yml 失败: " + ex.getMessage());
        }
    }

    /** 单个玩家的落盘；独立成方法是为了把 CME 兜在玩家粒度 */
    private void writePlayer(YamlConfiguration yml, String base, Stats st) {
        yml.set(base + ".name", st.name);
            yml.set(base + ".forged", st.forged);
            yml.set(base + ".best-score", Math.round(st.bestScore * 100.0) / 100.0);
            yml.set(base + ".best-quality", st.bestQuality);
            yml.set(base + ".level", st.level);
            yml.set(base + ".exp", st.exp);
            // 异步落盘时主线程可能正在 add/reset 同一个 map，浅拷贝一份再写，防 CME 炸掉整份存档
            for (Map.Entry<String, Integer> t : new java.util.LinkedHashMap<>(st.talents).entrySet()) {
                yml.set(base + ".talents." + t.getKey(), t.getValue());
            }
            yml.set(base + ".salvaged", st.salvaged);
            yml.set(base + ".flawless", st.flawless);
            yml.set(base + ".reputation", st.reputation);
            yml.set(base + ".commissions-done", st.commissionsDone);
            yml.set(base + ".commissions-failed", st.commissionsFailed);
            yml.set(base + ".duel-wins", st.duelWins);
            yml.set(base + ".duel-losses", st.duelLosses);
            yml.set(base + ".repaired", st.repaired);
            yml.set(base + ".coins", st.coins);
            yml.set(base + ".streak", st.streak);
            yml.set(base + ".best-streak", st.bestStreak);
            yml.set(base + ".streak-at", st.streakAt);
            yml.set(base + ".mentor", st.mentor);
            yml.set(base + ".disciple", st.disciple);
            yml.set(base + ".disciples", new ArrayList<>(st.disciples));
            yml.set(base + ".mentor-cooldown-at", st.mentorCooldownAt);
            yml.set(base + ".graduated", st.graduated);
            yml.set(base + ".graduated-at", st.graduatedAt);
            yml.set(base + ".graduates", new ArrayList<>(st.graduates));
            yml.set(base + ".spirits", st.spirits);
            yml.set(base + ".tombs", st.tombs);
            yml.set(base + ".achv-points", st.achvPoints);
            yml.set(base + ".best-strengthen", st.bestStrengthen);
            yml.set(base + ".rank-tier", st.rankTier);
            yml.set(base + ".rank-season", st.rankSeason);
            yml.set(base + ".achievements", new java.util.ArrayList<>(st.achievements));
            yml.set(base + ".codex", new ArrayList<>(st.codex));
            yml.set(base + ".patterns", new ArrayList<>(st.patterns));
            // byQuality 在 record() 里被 merge，异步/定时落盘时同样要拷贝防 CME
            for (Map.Entry<String, Integer> q : new java.util.LinkedHashMap<>(st.byQuality).entrySet()) {
                yml.set(base + ".by-quality." + q.getKey(), q.getValue());
            }
    }
}
