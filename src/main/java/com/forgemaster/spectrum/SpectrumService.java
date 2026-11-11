package com.forgemaster.spectrum;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.econ.CoinReason;
import com.forgemaster.form.SwordForm;
import com.forgemaster.quality.Quality;
import com.forgemaster.util.Msg;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 名剑谱（拓展第二档）：给顶级神兵绑锻造任务线。
 *
 * <p>条目由 config.yml 的 {@code spectrum.entries} 驱动，条件用一个小 DSL 表达，
 * Java 只认 5 种 type：{@code material / strengthen-level / evolution-stage / quality / stat}。
 * 取值一律走既有统计（StatsStore 档案、图鉴 codex）与本模块登记的两个计数
 * （剑冢残影胜场、历史最高锻阶），<b>不扫描背包</b>。</p>
 *
 * <p>存档 {@code spectrum-data.yml}，风格照 StoryManager 的进度存档：
 * {@code players.<uuid>.claimed / tomb-wins / best-evolution}，整份重建落盘。
 * 奖励一次性领取；领了的条目里带 form 奖励的，玩家后续锻造按
 * {@code spectrum.special-form-chance} 的概率优先抽中该 weight-0 特型。</p>
 */
public final class SpectrumService {

    /** 单个条件（DSL）。type 只认 5 种；写别的类型永远不满足。 */
    public static final class Condition {
        public final String type;
        /** material 条件的材料 id；quality 条件的品质名 */
        public final String id;
        /** stat 条件的统计键 */
        public final String key;
        /** 数值目标（quality 用 id 的品质序数比较，min 兜底） */
        public final int min;

        Condition(String type, String id, String key, int min) {
            this.type = type;
            this.id = id == null ? "" : id;
            this.key = key == null ? "" : key;
            this.min = Math.max(0, min);
        }

        static Condition parse(Map<?, ?> raw) {
            String type = String.valueOf(raw.get("type")).toLowerCase(Locale.ROOT).trim();
            int min = raw.get("min") instanceof Number n ? n.intValue() : 1;
            String id = raw.get("id") == null ? "" : String.valueOf(raw.get("id"));
            String key = raw.get("key") == null ? "" : String.valueOf(raw.get("key"));
            return new Condition(type, id, key, min);
        }
    }

    /** 单个名剑谱条目 */
    public static final class Entry {
        public final String id;
        public final String display;
        public final Material icon;
        public final List<String> lore;
        public final List<Condition> conditions;
        /** 奖励：专属剑型 id（可空） */
        public final String form;
        /** 奖励：称号（可空） */
        public final String title;
        /** 奖励：剑币 */
        public final long coins;
        /** 奖励：剑冢挑战费折扣（1.0 = 无，0.5 = 五折） */
        public final double tombFeeDiscount;

        Entry(String id, String display, Material icon, List<String> lore,
              List<Condition> conditions, String form, String title,
              long coins, double tombFeeDiscount) {
            this.id = id;
            this.display = display;
            this.icon = icon;
            this.lore = lore;
            this.conditions = conditions;
            this.form = form;
            this.title = title;
            this.coins = coins;
            this.tombFeeDiscount = tombFeeDiscount;
        }
    }

    /** 单个玩家的谱内数据 */
    private static final class PlayerData {
        final Set<String> claimed = new LinkedHashSet<>();
        int tombWins;
        int bestEvolution;
    }

    private final ForgingMasterPlugin plugin;
    private final File file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<UUID, PlayerData> data = new HashMap<>();

    public SpectrumService(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "spectrum-data.yml");
    }

    // ------------------------------------------------------------ 载入 / 保存

    public void load() {
        entries.clear();
        data.clear();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("spectrum.entries");
        if (root != null) {
            for (String key : root.getKeys(false)) {
                ConfigurationSection cs = root.getConfigurationSection(key);
                if (cs == null) {
                    continue;
                }
                Material icon = Material.matchMaterial(cs.getString("icon", "PAPER"));
                if (icon == null) {
                    icon = Material.PAPER;
                }
                List<Condition> conds = new ArrayList<>();
                for (Map<?, ?> raw : cs.getMapList("conditions")) {
                    conds.add(Condition.parse(raw));
                }
                ConfigurationSection rw = cs.getConfigurationSection("rewards");
                String form = rw == null ? null : rw.getString("form", "");
                String title = rw == null ? null : rw.getString("title", "");
                long coins = rw == null ? 0 : Math.max(0, rw.getLong("coins", 0));
                double discount = rw == null ? 1.0 : rw.getDouble("tomb-fee-discount", 1.0);
                entries.put(key, new Entry(key,
                        cs.getString("display", key), icon,
                        cs.getStringList("lore"), conds,
                        form == null || form.isBlank() ? null : form,
                        title == null || title.isBlank() ? null : title,
                        coins, Math.min(1.0, Math.max(0.0, discount))));
            }
        }
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                PlayerData d = new PlayerData();
                d.claimed.addAll(players.getStringList(key + ".claimed"));
                d.tombWins = Math.max(0, players.getInt(key + ".tomb-wins", 0));
                d.bestEvolution = Math.max(0, players.getInt(key + ".best-evolution", 0));
                data.put(uuid, d);
            } catch (IllegalArgumentException ignored) {
                // 坏键跳过，不影响启动
            }
        }
        plugin.getLogger().info("已载入名剑谱：" + entries.size() + " 条目 / " + data.size() + " 份领取记录");
    }

    /** 整份重建落盘（StoryManager 风格），领取 / 登记计数后都会调 */
    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerData> e : data.entrySet()) {
            String path = "players." + e.getKey();
            yml.set(path + ".claimed", new ArrayList<>(e.getValue().claimed));
            yml.set(path + ".tomb-wins", e.getValue().tombWins);
            yml.set(path + ".best-evolution", e.getValue().bestEvolution);
        }
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("名剑谱存档保存失败：" + ex.getMessage());
        }
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("spectrum.enabled", true);
    }

    // ------------------------------------------------------------ 查询

    public List<Entry> entries() {
        return new ArrayList<>(entries.values());
    }

    public Entry entry(String id) {
        return id == null ? null : entries.get(id.toLowerCase(Locale.ROOT));
    }

    public boolean claimed(UUID uuid, String entryId) {
        PlayerData d = data.get(uuid);
        return d != null && d.claimed.contains(entryId);
    }

    public int claimedCount(UUID uuid) {
        PlayerData d = data.get(uuid);
        return d == null ? 0 : d.claimed.size();
    }

    /** 剑冢残影胜场（本模块登记的计数） */
    public int tombWins(UUID uuid) {
        PlayerData d = data.get(uuid);
        return d == null ? 0 : d.tombWins;
    }

    /** 历史最高锻阶（本模块登记的计数，0 = 未蜕变） */
    public int bestEvolution(UUID uuid) {
        PlayerData d = data.get(uuid);
        return d == null ? 0 : d.bestEvolution;
    }

    private PlayerData data(UUID uuid) {
        return data.computeIfAbsent(uuid, k -> new PlayerData());
    }

    /**
     * 玩家当前可展示的称号：按 config 条目顺序取「最后一个已领取且带称号的条目」。
     * 没有返回空串。
     */
    public String titleOf(UUID uuid) {
        String title = "";
        for (Entry e : entries.values()) {
            if (e.title != null && claimed(uuid, e.id)) {
                title = e.title;
            }
        }
        return title;
    }

    // ------------------------------------------------------------ 条件取值（不扫描背包）

    /** 条件当前值（quality 条件返回最高品质序数，material 返回 0/1） */
    public int currentOf(UUID uuid, Condition c) {
        return switch (c.type) {
            case "material" -> hasMaterial(uuid, c.id) ? 1 : 0;
            case "strengthen-level" -> plugin.stats().bestStrengthenOf(uuid);
            case "evolution-stage" -> bestEvolution(uuid);
            case "quality" -> Math.max(0, bestQualityOrdinal(uuid));
            case "stat" -> statValue(uuid, c.key);
            default -> 0;
        };
    }

    /** 条件目标值（quality 返回目标品质序数；material 至少 1） */
    public int targetOf(Condition c) {
        return switch (c.type) {
            case "quality" -> {
                Quality q = Quality.byName(c.id);
                yield q == null ? Integer.MAX_VALUE : q.ordinal();
            }
            case "material" -> Math.max(1, c.min);
            default -> Math.max(1, c.min);
        };
    }

    /** 单个条件是否满足 */
    public boolean met(UUID uuid, Condition c) {
        return switch (c.type) {
            case "material" -> hasMaterial(uuid, c.id);
            case "quality" -> {
                Quality q = Quality.byName(c.id);
                yield q != null && bestQualityOrdinal(uuid) >= q.ordinal();
            }
            default -> currentOf(uuid, c) >= targetOf(c);
        };
    }

    /** 整条是否满足（未知 type 永远不满足：Java 只认 5 种） */
    public boolean allMet(UUID uuid, Entry e) {
        if (!enabled()) {
            return false;
        }
        for (Condition c : e.conditions) {
            if (!switch (c.type) {
                case "material", "strengthen-level", "evolution-stage", "quality", "stat" -> met(uuid, c);
                default -> false;
            }) {
                return false;
            }
        }
        return true;
    }

    public boolean canClaim(UUID uuid, Entry e) {
        return !claimed(uuid, e.id) && allMet(uuid, e);
    }

    /** 「拥有一把某材料的剑」：读图鉴 codex（材料:品质），曾锻出过即算 */
    private boolean hasMaterial(UUID uuid, String materialId) {
        if (materialId == null || materialId.isBlank()) {
            return false;
        }
        var st = plugin.stats().peek(uuid);
        if (st == null) {
            return false;
        }
        String prefix = materialId.toLowerCase(Locale.ROOT) + ":";
        for (String entry : st.codex) {
            if (entry.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 最高达成品质的序数（-1 = 从未锻造） */
    private int bestQualityOrdinal(UUID uuid) {
        var st = plugin.stats().peek(uuid);
        if (st == null) {
            return -1;
        }
        int best = -1;
        for (Quality q : Quality.values()) {
            if (st.byQuality.getOrDefault(q.name(), 0) > 0) {
                best = Math.max(best, q.ordinal());
            }
        }
        return best;
    }

    /** stat 条件取值：优先 StatsStore 公开档案；tomb-wins 用本模块登记的计数 */
    private int statValue(UUID uuid, String key) {
        if (key == null || key.isBlank()) {
            return 0;
        }
        if (key.equalsIgnoreCase("tomb-wins")) {
            return tombWins(uuid);
        }
        var st = plugin.stats().get(uuid);
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "forged", "duanzao" -> st.forged;
            case "commissions", "commissions-done" -> st.commissionsDone;
            case "commissions-failed" -> st.commissionsFailed;
            case "duel-wins" -> st.duelWins;
            case "duel-losses" -> st.duelLosses;
            case "tombs" -> st.tombs;
            case "spirits" -> st.spirits;
            case "repaired" -> st.repaired;
            case "salvaged" -> st.salvaged;
            case "flawless" -> st.flawless;
            case "reputation" -> st.reputation;
            case "coins" -> (int) Math.min(Integer.MAX_VALUE, st.coins);
            case "level" -> st.level;
            case "best-strengthen" -> st.bestStrengthen;
            case "best-streak" -> st.bestStreak;
            case "risk-made" -> st.riskMade;
            case "risk-lost" -> st.riskLost;
            case "achv-points" -> st.achvPoints;
            default -> 0;
        };
    }

    /** 条件的中文短标签（GUI 进度行用） */
    public String labelOf(Condition c) {
        return switch (c.type) {
            case "material" -> {
                var mat = plugin.materials() == null ? null : plugin.materials().get(c.id);
                yield mat == null ? "持有 " + c.id : "持有 " + Msg.plain(Msg.mm(mat.display()));
            }
            case "strengthen-level" -> "强化";
            case "evolution-stage" -> "锻阶";
            case "quality" -> {
                Quality q = Quality.byName(c.id);
                yield "品质 ≥ " + (q == null ? c.id : q.display());
            }
            case "stat" -> statLabel(c.key);
            default -> c.type;
        };
    }

    private static String statLabel(String key) {
        if (key == null) {
            return "统计";
        }
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "forged", "duanzao" -> "累计锻造";
            case "commissions", "commissions-done" -> "委托完成";
            case "commissions-failed" -> "委托失败";
            case "duel-wins" -> "比试胜场";
            case "duel-losses" -> "比试败场";
            case "tombs" -> "埋剑数";
            case "spirits" -> "剑灵觉醒";
            case "repaired" -> "研磨次数";
            case "salvaged" -> "熔炼次数";
            case "flawless" -> "满分剑";
            case "reputation" -> "声望";
            case "coins" -> "剑币";
            case "level" -> "专精等级";
            case "best-strengthen" -> "强化";
            case "best-streak" -> "连锻峰值";
            case "risk-made" -> "极限成功";
            case "risk-lost" -> "炸剑次数";
            case "achv-points" -> "成就点";
            case "tomb-wins" -> "剑冢胜场";
            default -> key;
        };
    }

    // ------------------------------------------------------------ 登记（由既有结算处的一行插入点调用）

    /**
     * 登记一次剑冢残影挑战胜利（TombChallenge.onShadowDeath 胜利结算处调用）。
     *
     * @return 登记后的胜场数
     */
    public int countTombWin(Player player) {
        if (player == null) {
            return 0;
        }
        PlayerData d = data(player.getUniqueId());
        d.tombWins++;
        save();
        return d.tombWins;
    }

    /**
     * 登记一次蜕变成功的历史最高锻阶（EvolutionService.succeed 结算处调用）。
     *
     * @return 登记后的最高锻阶
     */
    public int recordEvolution(UUID uuid, int stage) {
        if (uuid == null || stage <= 0) {
            return bestEvolution(uuid);
        }
        PlayerData d = data(uuid);
        if (stage > d.bestEvolution) {
            d.bestEvolution = stage;
            save();
        }
        return d.bestEvolution;
    }

    // ------------------------------------------------------------ 领取

    /**
     * 领取一条名剑谱奖励（一次性）：剑币入账，称号与剑型解锁记在已领集合里。
     * 条件不满足 / 已领取返回 false 并已自行提示。
     */
    public boolean claim(Player player, Entry e) {
        if (player == null || e == null) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        if (claimed(uuid, e.id)) {
            Msg.prefix(player, "<red>「<#ffd166>" + e.display + "</#ffd166>」你已经领过了。</red>");
            return false;
        }
        if (!allMet(uuid, e)) {
            Msg.prefix(player, "<red>「<#ffd166>" + e.display + "</#ffd166>」的条件还没齐，谱上看看差哪样。</red>");
            return false;
        }
        // v2.25 修 P1（审计轮2-1）：先落「已领」再发钱，与图鉴/悬赏/委托同一契约
        data(uuid).claimed.add(e.id);
        save();
        if (e.coins > 0) {
            plugin.stats().addCoins(uuid, e.coins, CoinReason.CODEX);
        }

        player.playSound(player.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.2f);
        Msg.prefix(player, "<gradient:#ffd166:#ff7a18><bold>名剑谱</bold></gradient> <gray>「<#ffd166>"
                + e.display + "</#ffd166>」达成！</gray>");
        StringBuilder got = new StringBuilder();
        if (e.form != null) {
            SwordForm f = plugin.forms() == null ? null : plugin.forms().get(e.form);
            got.append("专属剑型「<#ffd166>").append(f == null ? e.form : f.display())
                    .append("</#ffd166>」 ");
        }
        if (e.title != null) {
            got.append("称号「<#ffd166>").append(e.title).append("</#ffd166>」 ");
        }
        if (e.tombFeeDiscount < 1.0) {
            got.append("剑冢挑战费 <#ffd166>").append(Math.round(e.tombFeeDiscount * 100)).append("%</#ffd166> ");
        }
        if (e.coins > 0) {
            got.append("剑币 <#ffd166>+").append(e.coins).append("</#ffd166>");
        }
        Msg.send(player, got.toString().trim());
        if (e.form != null || e.title != null) {
            org.bukkit.Bukkit.broadcast(Msg.mm(Msg.PREFIX + "<gray>" + player.getName()
                    + " 在名剑谱上留下了「<#ffd166>" + e.display + "</#ffd166>」之名！"));
        }
        return true;
    }

    // ------------------------------------------------------------ 特型抽取（ForgeManager 结算处一行调用）

    /**
     * 名剑谱特型优先：玩家已解锁的 weight-0 专属剑型按
     * {@code spectrum.special-form-chance}（默认 30%）概率优先抽中。
     *
     * <p>没命中 / 没解锁 / 特型定义缺失时原样返回传入的普通抽取结果，
     * 对普通锻造零影响。</p>
     *
     * @param uuid     锻造者
     * @param random   结算用的随机源
     * @param fallback 普通池抽出来的剑型（可空）
     * @return 最终剑型
     */
    public SwordForm trySpecialForm(UUID uuid, Random random, SwordForm fallback) {
        if (!enabled() || plugin.forms() == null || uuid == null || random == null) {
            return fallback;
        }
        double chance = plugin.getConfig().getDouble("spectrum.special-form-chance", 0.30);
        if (chance <= 0 || random.nextDouble() >= chance) {
            return fallback;
        }
        List<SwordForm> pool = new ArrayList<>();
        PlayerData d = data.get(uuid);
        if (d == null || d.claimed.isEmpty()) {
            return fallback;
        }
        for (Entry e : entries.values()) {
            if (e.form == null || !d.claimed.contains(e.id)) {
                continue;
            }
            SwordForm f = plugin.forms().get(e.form);
            // 只认 weight 0 的特型：普通剑型走正常池，不在这里重复
            if (f != null && f.weight() == 0) {
                pool.add(f);
            }
        }
        if (pool.isEmpty()) {
            return fallback;
        }
        return pool.get(random.nextInt(pool.size()));
    }

    /**
     * 剑冢挑战费折扣倍率（1.0 = 无折扣）：取已领条目里最低的折扣。
     * 剑冢收费处一行调用即可生效。
     */
    public double tombFeeMultiplier(UUID uuid) {
        double best = 1.0;
        for (Entry e : entries.values()) {
            if (e.tombFeeDiscount < best && claimed(uuid, e.id)) {
                best = e.tombFeeDiscount;
            }
        }
        return best;
    }
}
