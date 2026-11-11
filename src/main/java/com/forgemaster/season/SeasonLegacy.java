package com.forgemaster.season;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.growth.GrowthUtil;
import com.forgemaster.hall.StrengthenHall;
import com.forgemaster.quality.Quality;
import com.forgemaster.stats.StatsStore;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 跨赛季「剑传承」：每次赛季结算，把全服最强的一把锻造品永久刻进名人堂。
 *
 * <p>为什么要它：赛季一重置，强化进度、剑币、声望全归零，玩家攒了一整季的剑
 * 就只剩一串历史数字。这里给它一个「留名」的出口——一个赛季留一条永久记录，
 * 只增不删，下一季、下十季的人都能翻到「第 3 季那把 +28 的传说剑是谁锻的」。</p>
 *
 * <p><b>候选从哪来</b>：只读 {@link StatsStore#snapshot()} 里已有的历史最优
 * （{@code bestScore} / {@code bestQuality} / {@code bestStrengthen}），
 * <b>不遍历在线玩家背包</b>。赛季结算时队伍多半在睡觉，这个功能必须能在无人时跑完。</p>
 *
 * <p><b>评判标准</b>（复合分，三个权重都可在 config.yml 里调）：</p>
 * <pre>
 *     复合分 = 品质序号 × 1000 + 锻造评分 × 1 + 强化等级 × 50
 * </pre>
 * <ul>
 *   <li>品质权重 1000：一档品质（约 1000 分）压得住评分满值（约 100）+ 强化满级
 *       （32 × 50 = 1600 里的大半），但压不住「品质 + 满强化」的联合优势——
 *       想让高一级品质直接封死低品质的最优解，把 {@code weight.quality} 提到 2000 以上即可；</li>
 *   <li>评分权重 1：评分本身是 0~100 的小数，原样吃进来，不和别的维度抢量级；</li>
 *   <li>强化权重 50：满强化（+20 铁剑 ~ +32 星陨）约值 1000~1600 分，正好是「一档品质」的量级。
 *       意思是「低一档胚子但捶到满级」能追平「高一档胚子裸奔」，
 *       这正是强化系统该有的分量——不然赛季末大家只在比胚子。</li>
 * </ul>
 *
 * <p><b>存档</b>：独立文件 {@code plugins/ForgingMaster/hall-legacy.yml}。
 * 不塞 hall.yml（那份会被 {@code /zhujian halloffame set} 整份重写），
 * 也不塞 hall-strengthen.yml（那是「强化之巅」单条纪录的地盘）。</p>
 *
 * <p><b>写盘时机</b>：{@link SeasonManager#end(String)} 结算末尾调
 * {@link #record(int)}；外面套了 try/catch，这里怎么炸都不会把赛季结算带下去。</p>
 *
 * <p><b>空数据不崩</b>：没人有记录 / 全员零分 / 赛季编号非法 / 文件不存在，
 * {@link #record(int)} 一律安静返回 false，读接口返回空表。</p>
 */
public final class SeasonLegacy {

    /**
     * 一个赛季的那把名剑（永久记录）。
     *
     * @param seasonNumber    赛季编号（从 1 起）
     * @param playerUuid      锻造者 UUID
     * @param playerName      锻造者名字（结算当时的名字，之后改名不回填）
     * @param materialId      材料 id（如 iron / starfall），认不出来时为空串
     * @param materialName    材料显示名（如「铁剑」），认不出来时为空串
     * @param quality         品质枚举名（ROUGH / COMMON / FINE / EPIC / LEGENDARY / MYTHIC）
     * @param score           锻造评分
     * @param strengthenLevel 结算时该玩家的历史最高强化等级
     * @param composite       复合分（见类注释的公式）
     * @param endedAt         刻录时间（毫秒）
     */
    public record Legacy(int seasonNumber, UUID playerUuid, String playerName,
                         String materialId, String materialName, String quality,
                         double score, int strengthenLevel, double composite, long endedAt) {
    }

    /** 存档文件名：和 hall.yml / hall-strengthen.yml 并列，互不影响 */
    private static final String FILE_NAME = "hall-legacy.yml";

    // 默认权重（config.yml 里没有这几个键时用它们）
    private static final double DEFAULT_WEIGHT_QUALITY = 1000.0;
    private static final double DEFAULT_WEIGHT_SCORE = 1.0;
    private static final double DEFAULT_WEIGHT_STRENGTHEN = 50.0;

    private final ForgingMasterPlugin plugin;
    private final File file;

    public SeasonLegacy(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
    }

    // ------------------------------------------------------------ 配置

    /** 传承总开关（season.legacy.enabled，默认 true）；关掉后结算不刻录，历史记录照样能查 */
    public boolean enabled() {
        return plugin.settings().flag("season.legacy.enabled", true);
    }

    /** 刻录成功时是否全服播报（season.legacy.broadcast，默认 true） */
    public boolean broadcast() {
        return plugin.settings().flag("season.legacy.broadcast", true);
    }

    /** 名人堂陈列里是否摆「历代名剑」（season.legacy.show-row，默认 true） */
    public boolean showRow() {
        return plugin.settings().flag("season.legacy.show-row", true);
    }

    /** 名人堂陈列最多摆最近几个赛季（season.legacy.show-count，默认 5） */
    public int showCount() {
        return Math.max(1, Math.min(50, plugin.settings().intOf("season.legacy.show-count", 5)));
    }

    /** 品质权重（season.legacy.weight.quality，默认 1000） */
    public double weightQuality() {
        return Math.max(0, plugin.settings().num("season.legacy.weight.quality", DEFAULT_WEIGHT_QUALITY));
    }

    /** 评分权重（season.legacy.weight.score，默认 1） */
    public double weightScore() {
        return Math.max(0, plugin.settings().num("season.legacy.weight.score", DEFAULT_WEIGHT_SCORE));
    }

    /** 强化等级权重（season.legacy.weight.strengthen，默认 50） */
    public double weightStrengthen() {
        return Math.max(0, plugin.settings().num("season.legacy.weight.strengthen", DEFAULT_WEIGHT_STRENGTHEN));
    }

    // ------------------------------------------------------------ 复合分

    /**
     * 默认权重下的复合分（纯静态，方便自检直接断言）。
     *
     * <p>{@code 品质序号 × 1000 + 评分 + 强化等级 × 50}，保留两位小数。
     * 负值按 0 处理，免得坏数据算出负分把排序搞乱。</p>
     */
    public static double compositeRaw(int qualityOrdinal, double score, int strengthenLevel) {
        return round2(DEFAULT_WEIGHT_QUALITY * Math.max(0, qualityOrdinal)
                + DEFAULT_WEIGHT_SCORE * Math.max(0, score)
                + DEFAULT_WEIGHT_STRENGTHEN * Math.max(0, strengthenLevel));
    }

    /**
     * 按当前配置权重算复合分（结算与自检都用这个）。
     *
     * @param qualityOrdinal 品质序号（{@link Quality#ordinal()}，0 = 粗糙）
     */
    public double composite(int qualityOrdinal, double score, int strengthenLevel) {
        return round2(weightQuality() * Math.max(0, qualityOrdinal)
                + weightScore() * Math.max(0, score)
                + weightStrengthen() * Math.max(0, strengthenLevel));
    }

    // ------------------------------------------------------------ 刻录

    /**
     * 结算刻录：挑出全服最强的一把，写进 {@code hall-legacy.yml}。
     *
     * @param seasonNumber 刚结束的赛季编号（1 起）
     * @return true = 真的刻上了；false = 开关关闭 / 本季没人留下记录 / 该赛季已刻过 / 落盘失败
     */
    public synchronized boolean record(int seasonNumber) {
        if (!enabled() || seasonNumber < 1) {
            return false;
        }
        StatsStore store = plugin.stats();
        if (store == null) {
            return false;
        }
        Legacy best = pick(store);
        if (best == null) {
            // 空服 / 本季谁都没锻过：这是正常情况，不是错误
            return false;
        }
        Map<Integer, Legacy> all = load();
        if (all.containsKey(seasonNumber)) {
            // 历史不改写：同一赛季已经刻过就保留旧的（重复结算 / 手工重跑都落在这里）
            return false;
        }
        Legacy stamped = new Legacy(seasonNumber, best.playerUuid(), best.playerName(),
                best.materialId(), best.materialName(), best.quality(), best.score(),
                best.strengthenLevel(), best.composite(), System.currentTimeMillis());
        all.put(seasonNumber, stamped);
        if (!save(all)) {
            return false;
        }
        if (broadcast()) {
            Bukkit.broadcast(Msg.mm(Msg.PREFIX
                    + "<gradient:#ffd166:#ff7a18><bold>名剑入堂</bold></gradient> <gray>"
                    + describe(stamped)));
        }
        // 刻完立刻重摆陈列，不用等下一个刷新周期（名人堂没初始化就算了）
        var hall = plugin.hallOfFame();
        if (hall != null) {
            try {
                hall.refresh();
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("名剑入堂后重摆陈列失败: " + ex.getMessage());
            }
        }
        return true;
    }

    /**
     * 从历史最优里挑一个赢家。
     *
     * <p>排序：复合分降序 → 评分降序 → 强化等级降序 → UUID 字符串升序（最后这道只是为了
     * 让并列时每次跑出来的赢家都一样，方便排查和自检）。</p>
     *
     * @return 未定赛季的临时记录；没人够格返回 null
     */
    private Legacy pick(StatsStore store) {
        UUID winId = null;
        StatsStore.Stats win = null;
        double winComposite = 0;

        for (Map.Entry<UUID, StatsStore.Stats> e : store.snapshot()) {
            UUID id = e.getKey();
            StatsStore.Stats st = e.getValue();
            if (id == null || st == null || !candidate(st)) {
                continue;
            }
            double composite = composite(qualityOrdinal(st.bestQuality, st.bestScore),
                    st.bestScore, st.bestStrengthen);
            if (win == null || better(composite, st, id, winComposite, win, winId)) {
                winId = id;
                win = st;
                winComposite = composite;
            }
        }
        if (win == null || winComposite <= 0) {
            return null;
        }
        String[] material = materialOf(winId, win);
        return new Legacy(0, winId, GrowthUtil.displayName(win.name, winId),
                material[0], material[1],
                qualityName(win.bestQuality, win.bestScore),
                round2(win.bestScore), Math.max(0, win.bestStrengthen),
                winComposite, 0L);
    }

    /** 候选资格：自检数据不算，完全空白的档案也不算 */
    private static boolean candidate(StatsStore.Stats st) {
        if (st.name == null || st.name.isBlank()) {
            // 没有名字的档案 = 自检 / 命令造出来的假 UUID（如 fm-strengthen-rule），
            // 让它上榜会把 UUID 前 8 位当成玩家名刻进永久存档
            return false;
        }
        if ("SelfTest".equals(st.name)) {
            return false;
        }
        return st.bestScore > 0 || st.bestStrengthen > 0 || st.forged > 0;
    }

    /** 挑战者是否赢过擂主（复合分 → 评分 → 强化 → UUID 字典序） */
    private static boolean better(double composite, StatsStore.Stats st, UUID id,
                                  double bestComposite, StatsStore.Stats best, UUID bestId) {
        if (composite != bestComposite) {
            return composite > bestComposite;
        }
        if (st.bestScore != best.bestScore) {
            return st.bestScore > best.bestScore;
        }
        if (st.bestStrengthen != best.bestStrengthen) {
            return st.bestStrengthen > best.bestStrengthen;
        }
        return id.toString().compareTo(bestId.toString()) < 0;
    }

    // ------------------------------------------------------------ 读

    /** 全部历史记录，按赛季升序（空数据返回空表，不是 null） */
    public List<Legacy> all() {
        return new ArrayList<>(load().values());
    }

    /**
     * 最近 n 个赛季，与 {@link #all()} 同序（赛季升序）。
     *
     * @param n 取几个；{@code <= 0} 返回空表，超过总数时返回全部
     */
    public List<Legacy> recent(int n) {
        List<Legacy> list = all();
        if (n <= 0 || list.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(list.subList(Math.max(0, list.size() - n), list.size()));
    }

    /** 查某个赛季的名剑，没有返回 null */
    public Legacy ofSeason(int seasonNumber) {
        if (seasonNumber < 1) {
            return null;
        }
        return load().get(seasonNumber);
    }

    /** 有没有任何记录 */
    public boolean hasAny() {
        return !load().isEmpty();
    }

    /**
     * 一行展示文本（MiniMessage）：{@code 第 N 季 · 玩家名 · 品质 · 评分 · 强化 +N}，
     * 认得出材料时后面再补一个材料名。
     *
     * @param legacy 可为 null（返回一句「还没有记录」）
     */
    public String describe(Legacy legacy) {
        if (legacy == null) {
            return "<gray>还没有历代名剑的记录。</gray>";
        }
        String name = GrowthUtil.displayName(legacy.playerName(), legacy.playerUuid());
        Quality q = Quality.byName(legacy.quality() == null ? "" : legacy.quality());
        String qualityText = q == null
                ? "<gray>" + (isBlank(legacy.quality()) ? "未知品质" : legacy.quality()) + "</gray>"
                : q.colored();
        String material = !isBlank(legacy.materialName()) ? legacy.materialName()
                : !isBlank(legacy.materialId()) ? legacy.materialId() : "未知材料";
        return "<#ffd166>第 " + legacy.seasonNumber() + " 季</#ffd166>"
                + " <white>" + name + "</white>"
                + " <dark_gray>·</dark_gray> " + qualityText
                + " <dark_gray>·</dark_gray> <gray>" + Msg.fmt(legacy.score(), 1) + " 分</gray>"
                + " <dark_gray>·</dark_gray> <#7ef2c8>强化 +" + legacy.strengthenLevel() + "</#7ef2c8>"
                + " <dark_gray>·</dark_gray> <gray>" + material + "</gray>";
    }

    /**
     * 多行展示（命令 / 面板用）：最近 n 季各一行，赛季升序。
     *
     * @param n {@code <= 0} 时按 {@link #showCount()} 取
     */
    public List<String> lines(int n) {
        List<Legacy> list = recent(n <= 0 ? showCount() : n);
        List<String> out = new ArrayList<>();
        if (list.isEmpty()) {
            out.add("<gray>还没有历代名剑的记录——等这一季结算吧。");
            return out;
        }
        for (Legacy l : list) {
            out.add(describe(l));
        }
        return out;
    }

    /** 刻录时间（毫秒）→ yyyy-MM-dd HH:mm，给命令当补充信息用 */
    public static String stamp(long at) {
        if (at <= 0) {
            return "时间未知";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(at));
    }

    // ------------------------------------------------------------ 材料还原

    /**
     * 猜这把名剑的材料 id / 显示名。
     *
     * <p>StatsStore 只存 bestScore / bestQuality / bestStrengthen，没有材料字段，
     * 所以按两条线索还原，两条都不需要玩家在线：</p>
     * <ol>
     *   <li>名人堂「强化之巅」那条纪录如果属于同一个人，直接借它的材料（最准，那是一把真实物品）；</li>
     *   <li>否则翻该玩家图鉴里的 {@code 材料id:品质}，优先挑与 bestQuality 同品质的，
     *       再按 id 字典序取第一个（图鉴是无序集合，取字典序是为了同一份存档每次跑出同一个答案）。</li>
     * </ol>
     *
     * @return {材料 id, 材料显示名}；认不出来返回两个空串（展示时写「未知材料」）
     */
    private String[] materialOf(UUID uuid, StatsStore.Stats st) {
        // 线索一：强化之巅 —— 只在它确实属于这个人时借用
        try {
            var hall = plugin.hallOfFame();
            if (hall != null && uuid != null) {
                StrengthenHall.Entry entry = hall.strengthen().entry();
                if (entry != null && uuid.toString().equals(entry.forgerId) && !isBlank(entry.materialId)) {
                    return new String[]{entry.materialId, entry.materialName};
                }
            }
        } catch (RuntimeException ignored) {
            // 名人堂还没起来 / 读盘失败：退回线索二，不影响刻录
        }
        // 线索二：图鉴
        String quality = qualityName(st.bestQuality, st.bestScore);
        List<String> hits = new ArrayList<>();
        List<String> any = new ArrayList<>();
        for (String raw : st.codex) {
            if (raw == null) {
                continue;
            }
            int at = raw.lastIndexOf(':');
            if (at <= 0 || at >= raw.length() - 1) {
                continue;
            }
            String id = raw.substring(0, at);
            String q = raw.substring(at + 1);
            any.add(id);
            if (q.equalsIgnoreCase(quality)) {
                hits.add(id);
            }
        }
        List<String> pool = hits.isEmpty() ? any : hits;
        if (pool.isEmpty()) {
            return new String[]{"", ""};
        }
        pool.sort(String::compareToIgnoreCase);
        String id = pool.get(0);
        var mat = plugin.materials().get(id);
        return new String[]{id, mat == null ? "" : Msg.plain(Msg.mm(mat.display()))};
    }

    // ------------------------------------------------------------ 品质换算

    /** 品质序号；存档里品质空 / 拼错时按评分反推（自检数据、老存档都能兜住） */
    private static int qualityOrdinal(String quality, double score) {
        Quality q = Quality.byName(quality == null ? "" : quality);
        if (q == null) {
            q = Quality.of(Math.max(0, score));
        }
        return q == null ? 0 : q.ordinal();
    }

    /** 存进 YAML 的品质名：统一写枚举名，别把「传说」这种显示名写进去 */
    private static String qualityName(String quality, double score) {
        Quality q = Quality.byName(quality == null ? "" : quality);
        if (q == null) {
            q = Quality.of(Math.max(0, score));
        }
        return q == null ? Quality.ROUGH.name() : q.name();
    }

    // ------------------------------------------------------------ 载入 / 保存

    /**
     * 读全部记录。
     *
     * <p>不做内存缓存，每次都从盘上读：文件本来就小（一季一条），
     * 换来的好处是「谁 new 了 SeasonLegacy 都看到同一份数据」——
     * 赛季管理器、名人堂陈列、命令三条路径共享同一份文件，不会有人拿着过期快照。</p>
     */
    private Map<Integer, Legacy> load() {
        Map<Integer, Legacy> out = new TreeMap<>();
        if (!file.exists()) {
            return out;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yml.getConfigurationSection("seasons");
        if (root == null) {
            return out;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec == null) {
                continue;
            }
            try {
                int n = sec.getInt("season-number", parseInt(key, 0));
                if (n < 1) {
                    continue;
                }
                String rawId = sec.getString("player-uuid", "");
                if (rawId == null || rawId.isBlank()) {
                    plugin.getLogger().warning("hall-legacy.yml 第 " + n + " 季缺少 player-uuid，已跳过");
                    continue;
                }
                UUID uuid = UUID.fromString(rawId.trim());
                out.put(n, new Legacy(n, uuid,
                        sec.getString("player-name", ""),
                        sec.getString("material-id", ""),
                        sec.getString("material-name", ""),
                        qualityName(sec.getString("quality", ""), sec.getDouble("score", 0)),
                        round2(sec.getDouble("score", 0)),
                        Math.max(0, sec.getInt("strengthen-level", 0)),
                        round2(sec.getDouble("composite",
                                compositeRaw(qualityOrdinal(sec.getString("quality", ""),
                                        sec.getDouble("score", 0)),
                                        sec.getDouble("score", 0),
                                        sec.getInt("strengthen-level", 0)))),
                        sec.getLong("ended-at", 0L)));
            } catch (Exception ignored) {
                // 单条坏数据跳过，不影响其它赛季
            }
        }
        return out;
    }

    /** 整份写回（赛季升序）。写失败返回 false，让 {@link #record} 报「没刻上」 */
    private boolean save(Map<Integer, Legacy> all) {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("无法创建插件数据目录，剑传承未落盘");
            return false;
        }
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(List.of(
                "跨赛季剑传承（SeasonLegacy）：每个赛季结算时刻一条永久记录，只增不删。",
                "由 SeasonManager.end() 自动写入，别手改；命令 /zhujian top legacy 只读。"));
        for (Map.Entry<Integer, Legacy> e : new TreeMap<>(all).entrySet()) {
            Legacy l = e.getValue();
            if (l == null) {
                continue;
            }
            String base = "seasons." + l.seasonNumber();
            yml.set(base + ".season-number", l.seasonNumber());
            yml.set(base + ".player-uuid", l.playerUuid() == null ? "" : l.playerUuid().toString());
            yml.set(base + ".player-name", l.playerName() == null ? "" : l.playerName());
            yml.set(base + ".material-id", l.materialId() == null ? "" : l.materialId());
            yml.set(base + ".material-name", l.materialName() == null ? "" : l.materialName());
            yml.set(base + ".quality", l.quality() == null ? "" : l.quality());
            yml.set(base + ".score", round2(l.score()));
            yml.set(base + ".strengthen-level", Math.max(0, l.strengthenLevel()));
            yml.set(base + ".composite", round2(l.composite()));
            yml.set(base + ".ended-at", l.endedAt());
        }
        try {
            yml.save(file);
            return true;
        } catch (IOException ex) {
            plugin.getLogger().warning("保存 " + FILE_NAME + " 失败: " + ex.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------ 小工具

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static int parseInt(String raw, int def) {
        try {
            return Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}
