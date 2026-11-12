package com.forgemaster.pass;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.econ.CoinReason;
import com.forgemaster.util.Msg;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 赛季通行证（拓展第二档 · 免费 + 付费双轨道）。
 *
 * <p>轨道全由 config.yml 的 {@code season-pass} 驱动：{@code points.<来源>} 定各行为
 * 给多少积分，{@code levels} 定每一级要多少积分、发什么奖励（剑币 / 材料 / 符），
 * Java 不硬编码任何数值。</p>
 *
 * <p>积分来源是<b>登记式</b>的：锻造 / 强化 / 蜕变 / 剑冢 / 比试 / 委托六个结算处
 * 各插一行 {@link #earn(UUID, String)}，不扫描任何存档。每次成功都记，不做每级去重。</p>
 *
 * <p>领取模型：积分够了<b>逐级手动</b>在 GUI 里点领，必须从第 1 级开始按顺序领
 * （等级 = 已领的最高级），不自动发放——防挂机白拿。已领记录存
 * {@code pass-data.yml}，键 = 赛季编号 + 玩家 uuid。</p>
 *
 * <p><b>付费轨道（v2.23）</b>：花剑币买断本季付费轨（{@code pass.premium.cost}，默认
 * 5000，每人每季限买一次），之后与免费轨<b>同等级并行逐级领</b>——等级门槛共用
 * {@code season-pass.levels} 的 need，奖励单独走 {@code pass.premium.rewards.<级>}
 * （格式与免费轨 reward 完全一致）；配置没写时落到代码内置 10 级默认奖励
 * （config.yml 归 ConfigMigrations 管，通行证模块不抢着改它）。扣币走
 * {@link com.forgemaster.econ.Wallet}（与黑市同一本账、同一消费口径），不直连
 * {@code plugin.economy()}。</p>
 *
 * <p>赛季切换语义：当前赛季号每次现读 {@code SeasonManager.number()}，不建调度任务。
 * 新赛季开始后旧赛季条目<b>只读不领</b>——未领的奖励过期即作废，不补领、不清档
 * （旧数据留在文件里只作履历）；积分从 0 重爬，等同「赛季重置一起重爬」。
 * 付费轨的「是否已购」存在赛季桶里，随免费轨一起按赛季自然重置。</p>
 */
public final class SeasonPassService {

    /** 单级奖励（全 config 驱动） */
    public static final class Reward {
        public final long coins;
        /** 锻造材料 id（MaterialRegistry 或原版 Material 名），null = 没有 */
        public final String materialId;
        public final int materialAmount;
        /** 符 id（talisman / steady / charm-kindle），null = 没有 */
        public final String charmId;
        public final int charmAmount;

        Reward(long coins, String materialId, int materialAmount, String charmId, int charmAmount) {
            this.coins = Math.max(0, coins);
            this.materialId = materialId == null || materialId.isBlank() ? null : materialId;
            this.materialAmount = Math.max(1, materialAmount);
            this.charmId = charmId == null || charmId.isBlank() ? null : charmId;
            this.charmAmount = Math.max(1, charmAmount);
        }

        /** 奖励有没有内容（空奖励的等级照常给等级数） */
        public boolean isEmpty() {
            return coins <= 0 && materialId == null && charmId == null;
        }
    }

    /** 单级轨道 */
    public static final class Level {
        public final int level;
        /** 领这一级需要的累计积分（绝对阈值，不是增量） */
        public final int need;
        public final Reward reward;

        Level(int level, int need, Reward reward) {
            this.level = level;
            this.need = Math.max(0, need);
            this.reward = reward;
        }
    }

    /** 一个玩家在一个赛季里的进度 */
    private static final class SeasonProgress {
        long points;
        /** 免费轨已领等级 */
        final Set<Integer> claimed = new LinkedHashSet<>();
        /** 本季是否已购付费轨（赛季桶内，随赛季重置） */
        boolean premiumBought;
        /** 付费轨已领等级 */
        final Set<Integer> premiumClaimed = new LinkedHashSet<>();
    }

    /** 一个玩家的全部赛季进度（键 = 赛季编号） */
    private static final class PlayerData {
        final Map<Integer, SeasonProgress> seasons = new HashMap<>();
    }

    private final ForgingMasterPlugin plugin;
    private final File file;
    private final List<Level> levels = new ArrayList<>();
    private final Map<String, Integer> pointSources = new LinkedHashMap<>();
    private final Map<UUID, PlayerData> data = new HashMap<>();
    /** 付费轨每级奖励（键 = 级号）；config {@code pass.premium.rewards.<级>} 覆盖内置默认 */
    private final Map<Integer, Reward> premiumRewards = new LinkedHashMap<>();
    /** 付费轨解锁价（{@code pass.premium.cost}，钳到 ≥ 0，0 = 免费解锁） */
    private long premiumCost = DEFAULT_PREMIUM_COST;

    /** 付费轨解锁价默认值（config 没写时用） */
    static final long DEFAULT_PREMIUM_COST = 5000;

    /**
     * 付费轨解锁价的钳制：负数一律当 0（免费解锁），不给「负价倒找钱」留口子。
     * 包内可见，给自检用。
     */
    static long clampCost(long raw) {
        return Math.max(0, raw);
    }

    /**
     * 付费轨奖励级号的钳制：只认 1 ~ 轨道级数，越界 / 非数字一律丢弃（返回 0）。
     * 包内可见，给自检用。
     */
    static int clampRewardLevelKey(String key, int maxLevel) {
        try {
            int v = Integer.parseInt(key.trim());
            return v >= 1 && v <= maxLevel ? v : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
    /**
     * 有未落盘的积分变动。
     *
     * <p>v2.19.1 之前 {@link #earn} 每登记一次就 {@link #save()} 一次整档——
     * 而炉次结算、委托交货、大赛、拍卖成交全都会登记积分，等于「每锻一把剑就把
     * pass-data.yml 从头重建一遍」。改成脏标记 + 定时合并落盘（{@link #flush}），
     * 与 AuctionStore 同一套写法。领取这种真事务仍然立即落盘。</p>
     */
    private volatile boolean dirty;
    /**
     * 脏代数（v2.25.5 修 T0）：{@link #save()} 原来用
     * {@code if (wasDirty && dirty) dirty = false;} 清脏 —— 但 {@code wasDirty} 与
     * {@code dirty} 是同一个 volatile 布尔，进入方法时必然都是 true，条件恒真，
     * 等于「无论写盘窗口内有没有新变更一律清脏」。窗口内 markDirty 的那一笔
     * （赛季积分 / 已领级数 / 付费轨购买）从此失去唯一的重试路径，被 kill 就永久丢。
     * 照 ContractStore 的口径改成代数：组装前取 seq，落盘后 seq 没前进才清脏。
     */
    private volatile long dirtySeq;

    public SeasonPassService(ForgingMasterPlugin plugin) {
        this(plugin, new File(plugin.getDataFolder(), "pass-data.yml"));
    }

    /**
     * 指定存档文件的构造（包内可见，自检的临时文件往返用；
     * 正常运行一律走上面的默认构造）。
     */
    SeasonPassService(ForgingMasterPlugin plugin, File file) {
        this.plugin = plugin;
        this.file = file;
    }

    // ------------------------------------------------------------ 载入 / 保存

    public void load() {
        levels.clear();
        pointSources.clear();
        data.clear();
        premiumRewards.clear();
        ConfigurationSection pts = plugin.getConfig().getConfigurationSection("season-pass.points");
        if (pts != null) {
            for (String key : pts.getKeys(false)) {
                pointSources.put(key.toLowerCase(java.util.Locale.ROOT), Math.max(0, pts.getInt(key, 0)));
            }
        }
        int idx = 0;
        for (Map<?, ?> raw : plugin.getConfig().getMapList("season-pass.levels")) {
            idx++;
            int need = raw.get("need") instanceof Number n ? n.intValue() : 0;
            Reward r = parseReward(raw.get("reward"));
            levels.add(new Level(idx, need, r));
        }
        loadPremiumConfig(idx);
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = com.forgemaster.util.YamlIo.loadOrQuarantine(file, "SeasonPassService", plugin.getLogger());
        ConfigurationSection players = yml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String uuidKey : players.getKeys(false)) {
            ConfigurationSection pSec = players.getConfigurationSection(uuidKey);
            if (pSec == null) {
                continue;
            }
            try {
                UUID uuid = UUID.fromString(uuidKey);
                PlayerData d = new PlayerData();
                for (String seasonKey : pSec.getKeys(false)) {
                    ConfigurationSection sSec = pSec.getConfigurationSection(seasonKey);
                    if (sSec == null) {
                        continue;
                    }
                    SeasonProgress sp = new SeasonProgress();
                    sp.points = Math.max(0, sSec.getLong("points", 0));
                    sp.claimed.addAll(sSec.getIntegerList("claimed"));
                    sp.premiumBought = sSec.getBoolean("premium", false);
                    sp.premiumClaimed.addAll(sSec.getIntegerList("premium-claimed"));
                    d.seasons.put(parseSeasonKey(seasonKey), sp);
                }
                data.put(uuid, d);
            } catch (IllegalArgumentException ignored) {
                // 坏键跳过，不影响启动
            }
        }
        plugin.getLogger().info("已载入赛季通行证：" + levels.size() + " 级 / " + data.size()
                + " 份进度记录 / 付费轨 " + premiumCost + " 币");
    }

    /** 付费轨的规范配置前缀：免费轨在 {@code season-pass.levels} 下，付费轨自然也在同段。 */
    static final String PREMIUM_PATH = "season-pass.premium";
    /** v2.23 文档里曾写作 {@code pass.premium.*}（README / javadoc / 迁移提示都这么写），保留为兼容别名。 */
    static final String PREMIUM_PATH_LEGACY = "pass.premium";

    /**
     * 付费轨奖励表：先落代码内置 10 级默认（config.yml 没有 premium 段也能玩），
     * 再让 config {@code season-pass.premium.rewards.<级>} 按级覆盖——奖励格式与免费轨
     * reward 完全一致。级号越界 / 非数字的键直接丢弃，不静默改轨。
     *
     * <p><b>v2.23.1 配置漂移修复</b>：本方法原先只读 {@code pass.premium.*}，而 config.yml
     * 把这两项写在 {@code season-pass.premium.*} 下——服主改了价格毫无效果（GUI 仍显示默认
     * 5000），自检也照不到（两边都有默认值）。现在规范键优先，旧键兜底，两处都写时以规范键为准。</p>
     */
    private void loadPremiumConfig(int levelCount) {
        premiumRewards.putAll(defaultPremiumRewards());
        premiumCost = clampCost(premiumLong("cost", DEFAULT_PREMIUM_COST));
        ConfigurationSection sec = premiumSection("rewards");
        if (sec == null || levelCount <= 0) {
            return;
        }
        for (String key : sec.getKeys(false)) {
            int no = clampRewardLevelKey(key, levelCount);
            if (no > 0) {
                premiumRewards.put(no, parseReward(sec.get(key)));
            }
        }
    }

    /** 付费轨取 long：规范键 → 兼容键 → 代码默认值（键不存在时才退下一级）。 */
    private long premiumLong(String leaf, long def) {
        String canonical = PREMIUM_PATH + "." + leaf;
        if (plugin.getConfig().contains(canonical)) {
            return plugin.getConfig().getLong(canonical, def);
        }
        return plugin.getConfig().getLong(PREMIUM_PATH_LEGACY + "." + leaf, def);
    }

    /** 付费轨取段：规范键 → 兼容键 → null。 */
    private ConfigurationSection premiumSection(String leaf) {
        ConfigurationSection sec = plugin.getConfig()
                .getConfigurationSection(PREMIUM_PATH + "." + leaf);
        if (sec != null) {
            return sec;
        }
        return plugin.getConfig().getConfigurationSection(PREMIUM_PATH_LEGACY + "." + leaf);
    }

    /** 代码内置的付费轨默认奖励（10 级，比免费轨同级略厚） */
    private static Map<Integer, Reward> defaultPremiumRewards() {
        Map<Integer, Reward> m = new LinkedHashMap<>();
        m.put(1, new Reward(800, null, 1, null, 1));
        m.put(2, new Reward(1200, null, 1, "steady", 1));
        m.put(3, new Reward(0, "netherite_ingot", 2, null, 1));
        m.put(4, new Reward(2000, null, 1, "talisman", 1));
        m.put(5, new Reward(2500, null, 1, null, 1));
        m.put(6, new Reward(0, "netherite_ingot", 3, null, 1));
        m.put(7, new Reward(3500, null, 1, "charm-kindle", 2));
        m.put(8, new Reward(4500, null, 1, null, 1));
        m.put(9, new Reward(0, "netherite_ingot", 4, null, 1));
        m.put(10, new Reward(8000, null, 1, "talisman", 2));
        return m;
    }

    private static int parseSeasonKey(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException ex) {
            return -1; // 旧格式/坏键：归到一个永远打不到当前赛季号的桶里，只读不领
        }
    }

    private static Reward parseReward(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return new Reward(0, null, 1, null, 1);
        }
        long coins = map.get("coins") instanceof Number n ? n.longValue() : 0;
        String matId = map.get("material") instanceof String s ? s : null;
        int matAmt = 1;
        if (matId == null && map.get("material") instanceof Map<?, ?> m) {
            Object id = m.get("id");
            matId = id == null ? null : String.valueOf(id);
            matAmt = m.get("amount") instanceof Number n ? n.intValue() : 1;
        }
        String charmId = map.get("charm") instanceof String s ? s : null;
        int charmAmt = map.get("charm") instanceof Map<?, ?> m
                ? (m.get("amount") instanceof Number n ? n.intValue() : 1) : 1;
        return new Reward(coins, matId, matAmt, charmId, charmAmt);
    }

    /** 整份重建落盘（StoryManager / SpectrumService 风格），登记积分或领取后都会调 */
    public void save() {
        // v2.25.2（4 并发查错）：原来第一行就 dirty = false，然后才组装+写盘。
        // 只要 yml.save 抛 IOException（盘满/文件被占），这一批变更就再也不会重试了 ——
        // 脏标记已经没了。改成写成功之后再清，且写盘窗口内有人 markDirty 就保持脏。
        // v2.25.5：代数必须在组装之前取（组装窗口内的 markDirty 不能被误清）
        long seq = dirtySeq;
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerData> e : data.entrySet()) {
            for (Map.Entry<Integer, SeasonProgress> s : e.getValue().seasons.entrySet()) {
                String base = "players." + e.getKey() + "." + s.getKey();
                yml.set(base + ".points", s.getValue().points);
                yml.set(base + ".claimed", new ArrayList<>(s.getValue().claimed));
                // v2.23：付费轨状态与免费轨同桶存放，赛季一换自然一起作废
                yml.set(base + ".premium", s.getValue().premiumBought);
                yml.set(base + ".premium-claimed", new ArrayList<>(s.getValue().premiumClaimed));
            }
        }
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            com.forgemaster.util.YamlIo.save(file, yml);
            // 写盘窗口内若有新的 markDirty（代数已前进），保持脏，下一次 flush 重写
            if (seq == dirtySeq) {
                dirty = false;
            }
        } catch (IOException ex) {
            dirty = true; // 写失败就得留脏，等下一次 flush 重试
            plugin.getLogger().warning("赛季通行证存档保存失败：" + ex.getMessage());
        }
    }

    /** 只记「有变动」，落盘交给 {@link #flush()}（高频路径用这个） */
    private void markDirty() {
        dirty = true;
        dirtySeq++;
    }

    /**
     * 把攒下的变动合并落盘一次；没变动就什么都不做。
     *
     * <p>由插件的定时任务每 10 秒调一次，关服时也会走 {@link #save()} 兜底，
     * 所以最坏情况只丢最后 10 秒的积分。</p>
     */
    public void flush() {
        if (dirty) {
            save();
        }
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("season-pass.enabled", true);
    }

    // ------------------------------------------------------------ 查询

    /** 轨道（config 顺序即等级顺序，1 起） */
    public List<Level> levels() {
        return new ArrayList<>(levels);
    }

    public Level level(int levelNo) {
        return levelNo >= 1 && levelNo <= levels.size() ? levels.get(levelNo - 1) : null;
    }

    /** 当前赛季号（现读 SeasonManager，不缓存——赛季切换自然生效） */
    public int seasonNumber() {
        return plugin.seasons() == null ? 1 : plugin.seasons().number();
    }

    /** 玩家在<b>当前赛季</b>的积分 */
    public long points(UUID uuid) {
        return progress(uuid, seasonNumber()).points;
    }

    /** 玩家在当前赛季已领的最高等级（0 = 一级没领） */
    public int claimedLevel(UUID uuid) {
        int max = 0;
        for (int no : progress(uuid, seasonNumber()).claimed) {
            max = Math.max(max, no);
        }
        return max;
    }

    public boolean claimed(UUID uuid, int levelNo) {
        return progress(uuid, seasonNumber()).claimed.contains(levelNo);
    }

    /**
     * 这一级现在能不能领：没领过、是「已领最高级 + 1」（逐级领）、且积分够。
     * 旧赛季 / 关闭状态一律不能领。
     */
    public boolean canClaim(UUID uuid, int levelNo) {
        if (!enabled() || seasonNumber() <= 0) {
            return false;
        }
        Level lv = level(levelNo);
        return lv != null && !claimed(uuid, levelNo) && levelNo == claimedLevel(uuid) + 1
                && points(uuid) >= lv.need;
    }

    // ------------------------------------------------------------ 付费轨查询（v2.23）

    /** 付费轨解锁价（已钳制 ≥ 0；0 = 免费解锁） */
    public long premiumCost() {
        return premiumCost;
    }

    /** 付费轨有没有内容（内置默认 10 级常驻，只有轨道本身为空才没有付费轨） */
    public boolean premiumConfigured() {
        return !levels.isEmpty() && !premiumRewards.isEmpty();
    }

    /** 本季付费轨是否已购（存在赛季桶里，跨赛季自然重置） */
    public boolean premiumBought(UUID uuid) {
        return progress(uuid, seasonNumber()).premiumBought;
    }

    /** 本季付费轨已领的最高等级（0 = 一级没领） */
    public int premiumClaimedLevel(UUID uuid) {
        int max = 0;
        for (int no : progress(uuid, seasonNumber()).premiumClaimed) {
            max = Math.max(max, no);
        }
        return max;
    }

    /** 本季付费轨这一级领了没有 */
    public boolean premiumClaimed(UUID uuid, int levelNo) {
        return progress(uuid, seasonNumber()).premiumClaimed.contains(levelNo);
    }

    /** 付费轨这一级的奖励内容（没配的级返回空奖励） */
    public Reward premiumReward(int levelNo) {
        return premiumRewards.getOrDefault(levelNo, new Reward(0, null, 1, null, 1));
    }

    /**
     * 付费轨这级现在能不能领：已购、没领过、「已领最高级 + 1」、积分够（门槛与免费轨
     * 共用同一张 {@code season-pass.levels} 表）。两轨互不干扰，并行逐级领。
     */
    public boolean canClaimPremium(UUID uuid, int levelNo) {
        return premiumClaimBlockedReason(uuid, levelNo) == null;
    }

    /**
     * 付费轨购买资格判定：返回 null = 可以买；否则返回给玩家看的拒绝文案。
     * 周期内（当季）每人限买一次——已购再点一律拒绝。
     * 扣不扣得起是 {@link #buyPremium} 里现查的事，这里只管资格。
     */
    public String premiumBuyBlockedReason(UUID uuid) {
        if (!enabled() || seasonNumber() <= 0) {
            return "<red>赛季通行证暂未开放。</red>";
        }
        if (!premiumConfigured()) {
            return "<red>本季没有付费轨道。</red>";
        }
        if (premiumBought(uuid)) {
            return "<red>本季付费轨你已经解锁过了，不能重复购买。</red>";
        }
        return null;
    }

    /**
     * 付费轨领奖资格判定：返回 null = 可以领；否则返回给玩家看的拒绝文案。
     * 公开给自检用——领奖一次性（防重复领）靠这条守门，真发放必须先过它。
     */
    public String premiumClaimBlockedReason(UUID uuid, int levelNo) {
        if (!enabled() || seasonNumber() <= 0) {
            return "<red>赛季通行证暂未开放。</red>";
        }
        Level lv = level(levelNo);
        if (lv == null) {
            return "<red>没有第 <#ffd166>" + levelNo + "</#ffd166> 级。</red>";
        }
        if (!premiumBought(uuid)) {
            return "<red>付费轨还没解锁：先花 <#ffd166>" + premiumCost
                    + "</#ffd166> 剑币购买才能领付费轨奖励。</red>";
        }
        if (premiumClaimed(uuid, levelNo)) {
            return "<red>付费轨第 <#ffd166>" + levelNo + "</#ffd166> 级你已经领过了。</red>";
        }
        if (levelNo != premiumClaimedLevel(uuid) + 1) {
            return "<red>付费轨要按顺序领，先领第 <#ffd166>" + (premiumClaimedLevel(uuid) + 1)
                    + "</#ffd166> 级。</red>";
        }
        if (points(uuid) < lv.need) {
            return "<red>积分还不够：需要 <#ffd166>" + lv.need + "</#ffd166>，你现在 <white>"
                    + points(uuid) + "</white>。</red>";
        }
        return null;
    }

    // ------------------------------------------------------------ 登记（由各结算处的一行插入点调用）

    /**
     * 登记一次积分（来源键 = config {@code season-pass.points.<source>}）。
     * 来源没配 / 配 0 / 通行证关闭时是空操作。每次成功都记，不去重。
     * 只写<b>当前赛季</b>的桶——新赛季开始后旧的进度自然变成只读履历。
     *
     * @return 登记后的当前赛季积分
     */
    public long earn(UUID uuid, String source) {
        if (!enabled() || uuid == null || source == null) {
            return points(uuid);
        }
        int gain = pointSources.getOrDefault(source.toLowerCase(java.util.Locale.ROOT), 0);
        if (gain <= 0) {
            return points(uuid);
        }
        SeasonProgress sp = progress(uuid, seasonNumber());
        sp.points += gain;
        markDirty(); // 高频：只记脏，定时合并落盘（v2.19.1）
        return sp.points;
    }

    // ------------------------------------------------------------ 付费轨购买（v2.23）

    /**
     * 花剑币买断本季付费轨。周期内（当季）每人限买一次；扣币走
     * {@link com.forgemaster.econ.Wallet}（与黑市 / 商会同一本账、同一消费口径），
     * 余额不足自行提示。买成即写 pass-data.yml。
     * 条件不满足返回 false 并已自行提示。
     */
    public boolean buyPremium(Player player) {
        if (player == null) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        String blocked = premiumBuyBlockedReason(uuid);
        if (blocked != null) {
            Msg.prefix(player, blocked);
            return false;
        }
        var wallet = plugin.wallet();
        long cost = premiumCost;
        if (cost > 0 && !wallet.take(player, cost, com.forgemaster.econ.CoinReason.PASS_PREMIUM)) {
            Msg.prefix(player, "<red>剑币不够。</red> 解锁付费轨要 <#ffd166>" + wallet.format(cost)
                    + "</#ffd166><gray>，你只有 </gray>" + wallet.format(wallet.coins(player)) + "<gray>。</gray>");
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
            return false;
        }
        progress(uuid, seasonNumber()).premiumBought = true;
        save();

        player.playSound(player.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.4f);
        Msg.prefix(player, "<gradient:#ffd166:#ff7a18><bold>赛季通行证</bold></gradient> <green>付费轨已解锁！"
                + "</green><gray>本季花费 </gray>" + wallet.format(cost)
                + "<gray>，付费轨奖励可与免费轨并行逐级领取。</gray>");
        return true;
    }

    /** 直接标记「本季已购」（包内可见，自检写读往返用；正常路径走 {@link #buyPremium}） */
    void markPremiumBought(UUID uuid) {
        progress(uuid, seasonNumber()).premiumBought = true;
        markDirty();
    }

    // ------------------------------------------------------------ 领取

    /**
     * 逐级手动领：必须是「已领最高级 + 1」且积分够。发剑币 / 材料 / 符，
     * 材料给不下的掉地上，已领即写 pass-data.yml。
     * 条件不满足返回 false 并已自行提示。
     */
    public boolean claim(Player player, int levelNo) {
        if (player == null) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        Level lv = level(levelNo);
        if (!enabled() || lv == null) {
            return false;
        }
        if (claimed(uuid, levelNo)) {
            Msg.prefix(player, "<red>第 <#ffd166>" + levelNo + "</#ffd166> 级你已经领过了。</red>");
            return false;
        }
        if (levelNo != claimedLevel(uuid) + 1) {
            Msg.prefix(player, "<red>要按顺序领，先领第 <#ffd166>" + (claimedLevel(uuid) + 1)
                    + "</#ffd166> 级。</red>");
            return false;
        }
        if (points(uuid) < lv.need) {
            Msg.prefix(player, "<red>积分还不够：需要 <#ffd166>" + lv.need + "</#ffd166>，你现在 <white>"
                    + points(uuid) + "</white>。</red>");
            return false;
        }
        progress(uuid, seasonNumber()).claimed.add(levelNo);
        List<String> got;
        try {
            got = grantReward(player, lv.reward);
        } catch (RuntimeException ex) {
            // v2.23.1 修 P1：原先「先记已领 → 再发奖」，发奖一旦抛异常（例如符的数量超过
            // 堆叠上限），这一级的奖励就永久吞掉、且玩家再也领不到。改成失败即回滚记录，
            // 让玩家能原样重试。
            progress(uuid, seasonNumber()).claimed.remove(levelNo);
            plugin.getLogger().severe("赛季通行证第 " + levelNo + " 级发奖异常，已回滚领取记录："
                    + ex);
            Msg.prefix(player, "<red>发奖时出了点问题，本次没有记你领取，请再点一次。</red>");
            return false;
        }
        save();

        player.playSound(player.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.2f);
        Msg.prefix(player, "<gradient:#ffd166:#ff7a18><bold>赛季通行证</bold></gradient> <gray>第 <#ffd166>"
                + levelNo + "</#ffd166> 级奖励已领取！</gray>"
                + (got.isEmpty() ? "" : " <white>" + String.join("　", got)));
        return true;
    }

    /**
     * 发一份等级奖励（免费轨 / 付费轨共用同一套机制）：剑币进 StatsStore（理由 PASS），
     * 材料与符塞背包、塞不下的掉地上。返回领取摘要，空奖励返回空列表。
     */
    private List<String> grantReward(Player player, Reward r) {
        List<String> got = new ArrayList<>();
        // v2.23.7 修 P2：剑币进账挪到最后。原来的顺序是「先 addCoins → 再发材料/符」，
        // 物品路径一旦抛异常，调用方回滚的只有领取记录 —— 已入账的剑币不收回，
        // 玩家再点一次就重复领币。物品发完（不会抛）之后再进账，失败重试不再复制。
        if (r.materialId != null) {
            ItemStack item = materialItem(r.materialId, r.materialAmount);
            if (item != null) {
                var leftover = player.getInventory().addItem(item);
                for (var rest : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), rest);
                }
                got.add("<#ffd166>" + r.materialAmount + " × " + materialLabel(r.materialId) + "</#ffd166>");
            }
        }
        if (r.charmId != null) {
            String norm = com.forgemaster.strengthen.Charm.normalize(r.charmId);
            ItemStack charm = com.forgemaster.strengthen.Charm.create(plugin,
                    norm == null ? com.forgemaster.strengthen.Charm.TALISMAN : norm);
            // v2.23.1 修 P1：原先直接 charm.setAmount(r.charmAmount)。配置里写超过该物品
            // 堆叠上限（例如符是 max-stack=1 的实体物品，而配置给了 2）时 setAmount 会抛
            // IllegalArgumentException，奖励整份发不出去。改成按上限拆成多叠，一叠都不少。
            int maxStack = Math.max(1, charm.getMaxStackSize());
            int remain = Math.max(1, r.charmAmount);
            while (remain > 0) {
                int n = Math.min(remain, maxStack);
                ItemStack one = charm.clone();
                one.setAmount(n);
                var leftover = player.getInventory().addItem(one);
                for (var rest : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), rest);
                }
                remain -= n;
            }
            got.add("<#ffd166>" + r.charmAmount + " × "
                    + com.forgemaster.strengthen.Charm.plainName(r.charmId) + "</#ffd166>");
        }
        if (r.coins > 0) {
            // 通行证等级奖励：达成条件领奖，暂归「成就奖励」桶（要单列请加 PASS 类枚举，调用点不用返工）
            plugin.stats().addCoins(player.getUniqueId(), r.coins, CoinReason.PASS);
            got.add(0, "剑币 <#ffd166>+" + r.coins + "</#ffd166>");
        }
        return got;
    }

    // ------------------------------------------------------------ 付费轨领取（v2.23）

    /**
     * 付费轨逐级手动领：必须已购、是「付费轨已领最高级 + 1」且积分够（门槛与免费轨
     * 同一张 need 表）。发奖走与免费轨完全相同的 {@link #grantReward}。
     * 条件不满足返回 false 并已自行提示。
     */
    public boolean claimPremium(Player player, int levelNo) {
        if (player == null) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        String blocked = premiumClaimBlockedReason(uuid, levelNo);
        if (blocked != null) {
            Msg.prefix(player, blocked);
            return false;
        }
        Level lv = level(levelNo);
        if (lv == null) {
            return false;
        }
        // v2.23.1 修 P1：这里原先发的是 lv.reward —— **免费轨**的那份奖励。
        // 付费轨花 5000 剑币买断，实际领到的和免费轨一模一样，premiumReward 表只被用来画
        // 图标 Lore。改成发付费轨自己的奖励表（没配也总有内置 10 级默认），并把「先记后发」
        // 改成失败回滚，与免费轨同一套顺序。
        Reward prem = premiumReward(levelNo);
        progress(uuid, seasonNumber()).premiumClaimed.add(levelNo);
        List<String> got;
        try {
            got = grantReward(player, prem);
        } catch (RuntimeException ex) {
            progress(uuid, seasonNumber()).premiumClaimed.remove(levelNo);
            plugin.getLogger().severe("付费轨第 " + levelNo + " 级发奖异常，已回滚领取记录："
                    + ex);
            Msg.prefix(player, "<red>发奖时出了点问题，本次没有记你领取，请再点一次。</red>");
            return false;
        }
        save();

        player.playSound(player.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.3f);
        Msg.prefix(player, "<gradient:#ffd166:#ff7a18><bold>赛季通行证</bold></gradient> <gray>付费轨第 <#ffd166>"
                + levelNo + "</#ffd166> 级奖励已领取！</gray>"
                + (got.isEmpty() ? "" : " <white>" + String.join("　", got)));
        return true;
    }

    /** 直接把一级记成付费轨已领（包内可见，自检写读往返用；正常路径走 {@link #claimPremium}） */
    void forcePremiumClaimed(UUID uuid, int levelNo) {
        if (level(levelNo) != null) {
            progress(uuid, seasonNumber()).premiumClaimed.add(levelNo);
            markDirty();
        }
    }

    /** 材料奖励的展示名（GUI 与消息共用） */
    public String materialLabel(String materialId) {
        var reg = plugin.materials();
        var fm = reg == null ? null : reg.get(materialId);
        if (fm != null) {
            return Msg.plain(Msg.mm(fm.display()));
        }
        Material m = Material.matchMaterial(materialId == null ? "" : materialId);
        return m == null ? materialId : com.forgemaster.material.MaterialGrades.pretty(m);
    }

    private ItemStack materialItem(String materialId, int amount) {
        var reg = plugin.materials();
        var fm = reg == null ? null : reg.get(materialId);
        Material m = fm != null ? fm.item() : Material.matchMaterial(materialId == null ? "" : materialId);
        return m == null ? null : new ItemStack(m, Math.max(1, amount));
    }

    // ------------------------------------------------------------ 内部

    private SeasonProgress progress(UUID uuid, int season) {
        return data.computeIfAbsent(uuid, k -> new PlayerData())
                .seasons.computeIfAbsent(season, k -> new SeasonProgress());
    }
}
