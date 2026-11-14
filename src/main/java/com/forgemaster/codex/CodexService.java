package com.forgemaster.codex;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.affix.Affix;
import com.forgemaster.econ.CoinReason;
import com.forgemaster.quality.Quality;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 收藏图鉴（拓展第一档）：五类收藏进度 + 分类奖励 + 全收集称号。
 *
 * <p>存储走独立的 {@code codex-data.yml}（ContractStore 风格：构造加载、改动即存），
 * 而不是塞进 StatsStore 的 data.yml——StatsStore 的 load()/writePlayer() 是既有方法，
 * 图鉴条目自带「达成时间戳 + 已领奖状态」两组新维度，独立文件既不碰既有存档结构，
 * 也方便单独归档。</p>
 *
 * <p>收集判定优先复用已有记录：品质 / 评分 / 剑型主要从 {@code StatsStore} 的既有统计
 * 推导（byQuality / bestScore），圣物直接读 {@code ContractStore.Run.relics}；
 * 词条种类与剑灵命名没有现成记录，靠三处「登记」调用写入：
 * 锻造结算（ForgeManager.settle）、词条重铸出词条（Reforge.reforge）、
 * 剑灵命名（SpiritManager.rename）。</p>
 */
public final class CodexService {

    // ------------------------------------------------------------ 分类常量

    public static final String CAT_QUALITY = "quality";
    public static final String CAT_FORM = "form";
    public static final String CAT_SCORE = "score";
    public static final String CAT_RELIC = "relic";
    public static final String CAT_AFFIX = "affix";
    public static final String CAT_SPIRIT = "spirit";

    /** 页签顺序，与 CodexGui 的页签槽位一一对应 */
    public static final List<String> CATEGORIES = List.of(
            CAT_QUALITY, CAT_FORM, CAT_SCORE, CAT_RELIC, CAT_AFFIX, CAT_SPIRIT);

    /** 评分里程碑（单把剑最高分），按 StatsStore.bestScore 判定 */
    public static final int[] SCORE_TIERS = {50, 65, 75, 85, 92, 97};

    /** 剑灵命名的唯一条目键 */
    public static final String SPIRIT_KEY = CAT_SPIRIT + ":named";

    /** 全收集奖励的分类名（进度里也用它记「已领」） */
    public static final String CAT_ALL = "all";

    /** 单个玩家的图鉴记录 */
    public static final class Rec {
        /** 条目键（category:id）→ 首次达成时间戳（毫秒） */
        public final Map<String, Long> entries = new ConcurrentHashMap<>();
        /** 已领奖的分类 */
        public final java.util.Set<String> claimed = ConcurrentHashMap.newKeySet();
    }

    private final ForgingMasterPlugin plugin;
    private final File file;
    private final Map<UUID, Rec> recs = new ConcurrentHashMap<>();

    public CodexService(Plugin plugin) {
        this.plugin = (ForgingMasterPlugin) plugin;
        this.file = new File(plugin.getDataFolder(), "codex-data.yml");
        load();
    }

    // ------------------------------------------------------------ 存取（ContractStore 风格：改动即存）

    public void load() {
        recs.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = com.forgemaster.util.YamlIo.loadOrQuarantine(file, "CodexService", plugin.getLogger());
        ConfigurationSection players = yml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection cs = players.getConfigurationSection(key);
                if (cs == null) {
                    continue;
                }
                Rec r = new Rec();
                ConfigurationSection es = cs.getConfigurationSection("entries");
                if (es != null) {
                    for (String k : es.getKeys(false)) {
                        r.entries.put(k, es.getLong(k, 0L));
                    }
                }
                r.claimed.addAll(cs.getStringList("claimed"));
                recs.put(id, r);
            } catch (IllegalArgumentException ignored) {
                // 坏键名直接跳过，不让一份脏存档拖垮整个图鉴
            }
        }
        plugin.getLogger().info("已载入 " + recs.size() + " 份收藏图鉴");
    }

    public boolean save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Rec> e : recs.entrySet()) {
            String base = "players." + e.getKey();
            Rec r = e.getValue();
            for (Map.Entry<String, Long> en : r.entries.entrySet()) {
                yml.set(base + ".entries." + en.getKey(), en.getValue());
            }
            yml.set(base + ".claimed", new java.util.ArrayList<>(r.claimed));
        }
        try {
            com.forgemaster.util.YamlIo.save(file, yml);
            return true;
        } catch (IOException ex) {
            // v2.26.4：返回成败——claim 靠这个回滚「已领」，失败时不能假装成功
            plugin.getLogger().warning("codex-data.yml 写入失败: " + ex.getMessage());
            return false;
        }
    }

    public Rec rec(UUID id) {
        return recs.computeIfAbsent(id, k -> new Rec());
    }

    // ------------------------------------------------------------ 登记（三处调用点 + GUI 打开时的兜底推导）

    /**
     * 登记一条目；返回是否首次达成。
     *
     * <p>单条登记（重铸、剑灵命名）自己落一次盘就够。批量登记见
     * {@link #registerForge}：那条路径一次锻造会连续命中十几条，
     * 逐个 save() 等于把整份 codex-data.yml 在主线程重写十几遍。</p>
     */
    public boolean record(UUID id, String key) {
        if (!recordInMemory(id, key)) {
            return false;
        }
        save();
        return true;
    }

    /**
     * 只改内存、不落盘。返回是否首次达成。
     *
     * <p>调用方负责在整批登记完之后调一次 {@link #save()}——这是把
     * 「一次锻造 = 十几次全量写盘」压成一次的关键。</p>
     */
    private boolean recordInMemory(UUID id, String key) {
        if (id == null || key == null || key.isBlank()) {
            return false;
        }
        Rec r = rec(id);
        if (r.entries.containsKey(key)) {
            return false;
        }
        r.entries.put(key, System.currentTimeMillis());
        return true;
    }

    /** 条目首次达成时间戳；未达成返回 0 */
    public long at(UUID id, String key) {
        Long t = recs.get(id) == null ? null : recs.get(id).entries.get(key);
        return t == null ? 0L : t;
    }

    /** 锻造结算登记：品质 + 剑型 + 评分里程碑 + 本炉词条（ForgeManager.settle 调用） */
    public void registerForge(UUID id, Quality quality, String formId, double score,
                              java.util.Collection<Affix> affixes) {
        if (!enabled() || id == null || quality == null) {
            return;
        }
        // 一次锻造最多命中「品质 1 + 剑型 1 + 评分 6 + 词条 6」= 14 条，
        // 全部先记内存，最后合并成一次落盘（2.19.1 性能修复）
        boolean changed = recordInMemory(id, CAT_QUALITY + ":" + quality.name());
        if (formId != null && !formId.isBlank()) {
            changed |= recordInMemory(id, CAT_FORM + ":" + formId.toLowerCase(java.util.Locale.ROOT));
        }
        for (int tier : SCORE_TIERS) {
            if (score >= tier) {
                changed |= recordInMemory(id, CAT_SCORE + ":" + tier);
            }
        }
        if (affixes != null) {
            for (Affix a : affixes) {
                if (a != null) {
                    changed |= recordInMemory(id, CAT_AFFIX + ":" + a.id().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        if (changed) {
            save();
        }
    }

    /** 重铸出词条登记（Reforge.reforge 调用） */
    public void registerAffix(UUID id, String affixId) {
        if (!enabled() || id == null || affixId == null || affixId.isBlank()) {
            return;
        }
        record(id, CAT_AFFIX + ":" + affixId.toLowerCase(java.util.Locale.ROOT));
    }

    /** 剑灵命名达成登记（SpiritManager.rename 调用） */
    public void registerSpiritName(UUID id) {
        if (!enabled() || id == null) {
            return;
        }
        record(id, SPIRIT_KEY);
    }

    // ------------------------------------------------------------ 收集判定（优先走已有记录）

    /** 品质：登记过，或 StatsStore.byQuality 里锻出过（任意次数） */
    public boolean hasQuality(UUID id, Quality q) {
        if (at(id, CAT_QUALITY + ":" + q.name()) > 0) {
            return true;
        }
        Integer n = plugin.stats().get(id).byQuality.get(q.name());
        return n != null && n > 0;
    }

    /** 剑型：登记过（剑型在 v2.14 才加入，StatsStore 没有历史记录可推导） */
    public boolean hasForm(UUID id, String formId) {
        return formId != null && at(id, CAT_FORM + ":" + formId.toLowerCase(java.util.Locale.ROOT)) > 0;
    }

    /** 评分里程碑：登记过，或历史最高分达标 */
    public boolean hasScore(UUID id, int tier) {
        return at(id, CAT_SCORE + ":" + tier) > 0 || plugin.stats().get(id).bestScore >= tier;
    }

    /** 圣物：直接读 ContractStore.Run.relics（已有记录，不需要登记） */
    public boolean hasRelic(UUID id, String relicId) {
        com.forgemaster.contract.ContractStore.Run run = plugin.contractManager() == null
                ? null : plugin.contractManager().store().run(id);
        return run != null && run.relics.contains(relicId);
    }

    /** 词条种类：登记过（锻造结算 / 重铸时写入） */
    public boolean hasAffix(UUID id, String affixId) {
        return affixId != null
                && at(id, CAT_AFFIX + ":" + affixId.toLowerCase(java.util.Locale.ROOT)) > 0;
    }

    /** 剑灵命名：登记过 */
    public boolean hasSpiritName(UUID id) {
        return at(id, SPIRIT_KEY) > 0;
    }

    /** 玩家历史最高评分（StatsStore.bestScore），GUI 展示用 */
    public double statsBestScore(UUID id) {
        return plugin.stats().get(id).bestScore;
    }

    /** 某分类是否已收集满 */
    public boolean categoryComplete(UUID id, String cat) {
        return collectedOf(id, cat) >= totalOf(cat);
    }

    /** 全部分类收集满（不含 all） */
    public boolean allComplete(UUID id) {
        for (String cat : CATEGORIES) {
            if (!categoryComplete(id, cat)) {
                return false;
            }
        }
        return true;
    }

    /** 某分类已收集格数 */
    public int collectedOf(UUID id, String cat) {
        return switch (cat) {
            case CAT_QUALITY -> {
                int n = 0;
                for (Quality q : Quality.values()) {
                    if (hasQuality(id, q)) {
                        n++;
                    }
                }
                yield n;
            }
            case CAT_FORM -> {
                int n = 0;
                for (com.forgemaster.form.SwordForm f : forms()) {
                    if (hasForm(id, f.id())) {
                        n++;
                    }
                }
                yield n;
            }
            case CAT_SCORE -> {
                int n = 0;
                for (int t : SCORE_TIERS) {
                    if (hasScore(id, t)) {
                        n++;
                    }
                }
                yield n;
            }
            case CAT_RELIC -> {
                int n = 0;
                for (com.forgemaster.contract.RelicPool.Def d : relics()) {
                    if (hasRelic(id, d.id())) {
                        n++;
                    }
                }
                yield n;
            }
            case CAT_AFFIX -> {
                int n = 0;
                for (Affix a : plugin.affixes().all()) {
                    if (hasAffix(id, a.id())) {
                        n++;
                    }
                }
                yield n;
            }
            case CAT_SPIRIT -> hasSpiritName(id) ? 1 : 0;
            default -> 0;
        };
    }

    /** 某分类总格数 */
    public int totalOf(String cat) {
        return switch (cat) {
            case CAT_QUALITY -> Quality.values().length;
            case CAT_FORM -> Math.max(1, forms().size());
            case CAT_SCORE -> SCORE_TIERS.length;
            case CAT_RELIC -> Math.max(1, relics().size());
            case CAT_AFFIX -> Math.max(1, plugin.affixes().all().size());
            case CAT_SPIRIT -> 1;
            default -> 0;
        };
    }

    private List<com.forgemaster.form.SwordForm> forms() {
        if (plugin.forms() == null || plugin.forms().isEmpty()) {
            return List.of(com.forgemaster.form.SwordForm.defaults());
        }
        return plugin.forms().all();
    }

    private List<com.forgemaster.contract.RelicPool.Def> relics() {
        if (plugin.contractManager() == null) {
            return List.of(com.forgemaster.contract.RelicPool.defaults());
        }
        List<com.forgemaster.contract.RelicPool.Def> all = plugin.contractManager().relics().all();
        return all.isEmpty() ? List.of(com.forgemaster.contract.RelicPool.defaults()) : all;
    }

    // ------------------------------------------------------------ 奖励

    public boolean enabled() {
        return plugin.settings().flag("codex.enabled", true);
    }

    /** 完成一个分类的奖励剑币 */
    public int rewardOf(String cat) {
        return Math.max(0, plugin.settings().intOf("codex.rewards." + cat,
                plugin.settings().intOf("codex.reward-per-category", 500)));
    }

    /** 全收集奖励剑币 */
    public int allReward() {
        return Math.max(0, plugin.settings().intOf("codex.all-reward", 2000));
    }

    /** 全收集称号 */
    public String allTitle() {
        return plugin.settings().str("codex.all-title", "图鉴宗师");
    }

    public boolean claimed(UUID id, String cat) {
        return recs.get(id) != null && recs.get(id).claimed.contains(cat);
    }

    /**
     * 领取分类奖励：完成且未领才发剑币，只发一次。
     *
     * <p>顺带检查全收集：五类全满且「all」未领时再发一份全收集奖励。
     * 返回本次实际到手的剑币数（0 = 没发）。</p>
     */
    public long claim(PlayerView viewer, UUID id, String cat) {
        if (!CATEGORIES.contains(cat) || !categoryComplete(id, cat) || claimed(id, cat)) {
            return 0;
        }
        // v2.25 修 P1（审计轮2-1）：对齐「先落已领、再发钱」契约（悬赏/委托同款）。
        // 原来是 claimed 只进内存 → addCoins → 最后 save()，崩在中间重启后 claimed
        // 回滚到旧快照，同一分类可再领一次。现在两个标记先记、立即落盘，钱后发。
        // v2.26.4：save() 原来吞 IOException 只打 warning——写盘失败时「已领」只在内存，
        // 重启后可重领。失败就回滚 claimed 并拒绝发放（返回 0）。
        rec(id).claimed.add(cat);
        boolean allNow = allComplete(id) && !claimed(id, CAT_ALL);
        if (allNow) {
            rec(id).claimed.add(CAT_ALL);
        }
        if (!save()) {
            rec(id).claimed.remove(cat);
            if (allNow) {
                rec(id).claimed.remove(CAT_ALL);
            }
            plugin.getLogger().severe("图鉴领奖落盘失败，已回滚「已领」标记并拒绝发放：cat=" + cat);
            return 0;
        }
        long paid = plugin.stats().addCoins(id, rewardOf(cat), CoinReason.CODEX);
        if (allNow) {
            paid += plugin.stats().addCoins(id, allReward(), CoinReason.CODEX);
            if (viewer != null) {
                viewer.onAllComplete(allTitle(), allReward());
            }
        }
        return paid;
    }

    /** 领奖后的回调（发消息 / 广播用），GUI 实现它，service 不依赖 GUI 类 */
    public interface PlayerView {
        void onAllComplete(String title, int reward);
    }
}
