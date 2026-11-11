package com.forgemaster.title;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.quality.Quality;
import com.forgemaster.stats.StatsStore;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * v2.19 称号扩展：config 驱动的成就称号，与既有声望称号（{@link StatsStore#titleOf}）并行。
 *
 * <p><b>定义</b>：config.yml 顶层 {@code titles} 列表，每条
 * {@code {id, display, color, icon, condition: {type, id/key, min}}}。
 * 条件 type 只认 5 种——{@code material / strengthen-level / evolution-stage / quality / stat}，
 * 口径与名剑谱（spectrum）一致，但<b>不 import spectrum 包</b>：
 * 取值全部自包含（StatsStore 档案 + 图鉴 codex + 反射读取名剑谱登记的最高锻阶），
 * 避免称号模块对名剑谱的编译期耦合。写错 type 的称号永远不满足。</p>
 *
 * <p><b>解锁</b>：条件即解锁——解锁状态从档案实时推导（锻造类统计只涨不跌、
 * bestStrengthen 只涨不跌、byQuality / codex 是历史累计），不落盘、不需要「已领」记录。</p>
 *
 * <p><b>佩戴</b>：手动佩戴 &gt; 声望称号。佩戴状态存<b>玩家 PDC</b>
 * （{@code fm_worn_title} = 称号 id）而不是独立 yml 或 data.yml 字段：它是纯玩家展示态，
 * PDC 随玩家存档自动持久化，不需要新建文件、不需要接存取管线，也不给 StatsStore
 * 再加字段；称号本身不入数据（条件实时推导），要存的只有「戴了哪个」这一个值。
 * 代价是离线玩家的占位符查询读不到 PDC——此时回退声望称号（聊天前缀只对在线玩家有意义）。</p>
 *
 * <p><b>渲染</b>：唯一插入点在 {@code ForgePlaceholders.resolve} 的 {@code title} 分支
 * （聊天插件经 {@code %forgemaster_title%} 渲染前缀），走 {@link #renderTitle}：
 * 先查佩戴、佩戴仍解锁则用佩戴称号，否则回退 {@link StatsStore#titleOf}。</p>
 */
public final class TitleService {

    /** 单个解锁条件（与名剑谱条件同口径，自包含实现） */
    public static final class Condition {
        public final String type;
        /** quality / material 条件的目标 id（品质名 / 材料 id） */
        public final String id;
        /** stat 条件的统计键 */
        public final String key;
        /** 数值目标（0 = 关闭该项，条件永不满足——配 0 等于删掉这条称号） */
        public final int min;

        Condition(String type, String id, String key, int min) {
            this.type = type == null ? "" : type.toLowerCase(Locale.ROOT).trim();
            this.id = id == null ? "" : id;
            this.key = key == null ? "" : key;
            this.min = Math.max(0, min);
        }

        static Condition parse(Map<?, ?> raw) {
            String type = raw.get("type") == null ? "" : String.valueOf(raw.get("type"));
            int min = raw.get("min") instanceof Number n ? n.intValue() : 1;
            String id = raw.get("id") == null ? "" : String.valueOf(raw.get("id"));
            String key = raw.get("key") == null ? "" : String.valueOf(raw.get("key"));
            return new Condition(type, id, key, min);
        }
    }

    /** 单条称号定义 */
    public static final class Title {
        public final String id;
        public final String display;
        /** MiniMessage 颜色（"#ff3d6e" 或颜色名），GUI 与渲染共用 */
        public final String color;
        public final Material icon;
        public final Condition condition;

        Title(String id, String display, String color, Material icon, Condition condition) {
            this.id = id;
            this.display = display;
            this.color = color;
            this.icon = icon;
            this.condition = condition;
        }

        /** 带色显示名（MiniMessage） */
        public String colored() {
            if (color == null || color.isBlank()) {
                return display;
            }
            return "<color:" + color + ">" + display + "</color>";
        }

        /** legacy § 码版显示名（PAPI 前缀用，聊天插件大多吃传统色码） */
        public String legacy() {
            return Msg.legacy(colored());
        }
    }

    private final ForgingMasterPlugin plugin;
    /** 佩戴标记（玩家 PDC，值 = 称号 id；键不存在 = 没佩戴） */
    private final NamespacedKey wornKey;
    private final Map<String, Title> titles = new LinkedHashMap<>();

    public TitleService(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.wornKey = new NamespacedKey(plugin, "fm_worn_title");
    }

    // ------------------------------------------------------------ 载入

    /** config 的 titles 列表灌进来（onEnable / reloadAll 各调一次），全 config 无硬编码 */
    public void load() {
        titles.clear();
        for (Map<?, ?> raw : plugin.getConfig().getMapList("titles")) {
            String id = raw.get("id") == null ? "" : String.valueOf(raw.get("id")).toLowerCase(Locale.ROOT).trim();
            if (id.isEmpty()) {
                continue;
            }
            String display = raw.get("display") == null ? id : String.valueOf(raw.get("display"));
            String color = raw.get("color") == null ? "" : String.valueOf(raw.get("color"));
            Material icon = raw.get("icon") == null ? Material.NAME_TAG : Material.matchMaterial(String.valueOf(raw.get("icon")));
            if (icon == null) {
                icon = Material.NAME_TAG;
            }
            Condition cond = raw.get("condition") instanceof Map<?, ?> c ? Condition.parse(c) : null;
            if (cond == null) {
                plugin.getLogger().warning("称号 " + id + " 缺 condition，已跳过。");
                continue;
            }
            titles.put(id, new Title(id, display, color, icon, cond));
        }
        if (!titles.isEmpty()) {
            plugin.getLogger().info("已载入称号扩展：" + titles.size() + " 条（config titles 段）");
        }
    }

    // ------------------------------------------------------------ 查询

    public boolean enabled() {
        return !titles.isEmpty();
    }

    /** 全部称号（config 顺序） */
    public List<Title> all() {
        return new ArrayList<>(titles.values());
    }

    public Title byId(String id) {
        return id == null ? null : titles.get(id.toLowerCase(Locale.ROOT));
    }

    /** 该称号是否已解锁（条件实时推导，未知 type 永远不满足） */
    public boolean unlocked(UUID uuid, Title t) {
        if (uuid == null || t == null || t.condition.min <= 0) {
            return false;
        }
        int cur = switch (t.condition.type) {
            case "material" -> hasMaterial(uuid, t.condition.id) ? 1 : 0;
            case "strengthen-level" -> plugin.stats().bestStrengthenOf(uuid);
            case "evolution-stage" -> bestEvolution(uuid);
            case "quality" -> Math.max(0, bestQualityOrdinal(uuid));
            case "stat" -> statValue(uuid, t.condition.key);
            default -> 0;
        };
        return cur >= t.condition.min;
    }

    /** 当前佩戴的称号（没佩戴 / 已不在配置里返回 null） */
    public Title wornOf(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        String id = Bukkit.getOfflinePlayer(uuid).getPersistentDataContainer()
                .get(wornKey, PersistentDataType.STRING);
        return id == null || id.isEmpty() ? null : byId(id);
    }

    // ------------------------------------------------------------ 佩戴 / 脱下

    /** 佩戴（必须已解锁）；成功返回 true 并已提示 */
    public boolean wear(Player p, Title t) {
        if (p == null || t == null) {
            return false;
        }
        if (!unlocked(p.getUniqueId(), t)) {
            Msg.prefix(p, "<red>「" + t.colored() + "<red>」还没解锁。</red>");
            return false;
        }
        p.getPersistentDataContainer().set(wornKey, PersistentDataType.STRING, t.id);
        Msg.prefix(p, "<green>已佩戴称号「" + t.colored() + "<green>」，聊天前缀即刻生效。</green>");
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.4f);
        return true;
    }

    /** 脱下（没佩戴时静默成功，幂等） */
    public void takeOff(Player p) {
        if (p == null) {
            return;
        }
        Title old = wornOf(p.getUniqueId());
        p.getPersistentDataContainer().remove(wornKey);
        if (old != null) {
            Msg.prefix(p, "<gray>已脱下称号「" + old.colored() + "<gray>」，恢复声望称号。</gray>");
        }
    }

    // ------------------------------------------------------------ 渲染（PAPI 插入点调用）

    /**
     * 称号渲染总入口：先查佩戴（佩戴 &gt; 声望），佩戴称号仍在配置里且仍解锁就用它，
     * 否则回退声望称号。PAPI {@code %forgemaster_title%}（聊天前缀的现有渲染点）一行调用。
     *
     * @param plugin     主插件
     * @param uuid       玩家（null = 回退声望称号）
     * @param reputation 声望值（回退用）
     * @return legacy § 码称号文本（聊天格式直接可用）
     */
    public static String renderTitle(ForgingMasterPlugin plugin, UUID uuid, int reputation) {
        TitleService svc = plugin == null ? null : plugin.titles();
        if (svc != null && svc.enabled()) {
            Title worn = svc.wornOf(uuid);
            // guard：佩戴称号可能被改配置删掉 / 条件口径变化，不在了就回退声望称号
            if (worn != null && svc.unlocked(uuid, worn)) {
                return worn.legacy();
            }
        }
        return StatsStore.titleOf(reputation);
    }

    // ------------------------------------------------------------ 条件取值（自包含，不扫背包，口径对齐名剑谱）

    /** 「曾锻出过某材料的剑」：读图鉴 codex（材料:品质），同名剑谱口径 */
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

    /** 最高达成品质序数（-1 = 从未锻造） */
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

    /**
     * stat 条件取值：键位与名剑谱完全一致（口径对齐），自包含实现。
     * 未知键返回 0。
     */
    private int statValue(UUID uuid, String key) {
        if (key == null || key.isBlank()) {
            return 0;
        }
        var st = plugin.stats().peek(uuid);
        if (st == null) {
            return 0;
        }
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

    /**
     * 最高锻阶：与名剑谱 {@code evolution-stage} 条件同一数据源（EvolutionService 结算处登记）。
     * 反射取值避免 import spectrum 包；取不到按 0 处理。
     */
    private int bestEvolution(UUID uuid) {
        Object svc = plugin.spectrum();
        if (svc == null || uuid == null) {
            return 0;
        }
        try {
            java.lang.reflect.Method m = SpectrumBestEvolution.METHOD;
            if (m == null) {
                return 0;
            }
            Object r = m.invoke(svc, uuid);
            return r instanceof Number n ? Math.max(0, n.intValue()) : 0;
        } catch (ReflectiveOperationException | SecurityException ex) {
            return 0;
        }
    }

    /** 反射句柄缓存（插件类只加载一次） */
    private static final class SpectrumBestEvolution {
        static final java.lang.reflect.Method METHOD = lookup();

        private static java.lang.reflect.Method lookup() {
            try {
                return Class.forName("com.forgemaster.spectrum.SpectrumService")
                        .getMethod("bestEvolution", UUID.class);
            } catch (ReflectiveOperationException | SecurityException ex) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------ 杂项

    /** 条件的中文短标签（GUI 进度行用），口径同名剑谱 */
    public String labelOf(Title t) {
        Condition c = t.condition;
        return switch (c.type) {
            case "material" -> "持有材料 " + (c.id.isEmpty() ? "?" : c.id);
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
            default -> key;
        };
    }

    /** 条件当前值（GUI 进度行用；未知 type 给 0） */
    public int currentValue(UUID uuid, Title t) {
        if (uuid == null || t == null) {
            return 0;
        }
        return switch (t.condition.type) {
            case "material" -> hasMaterial(uuid, t.condition.id) ? 1 : 0;
            case "strengthen-level" -> plugin.stats().bestStrengthenOf(uuid);
            case "evolution-stage" -> bestEvolution(uuid);
            case "quality" -> Math.max(0, bestQualityOrdinal(uuid));
            case "stat" -> statValue(uuid, t.condition.key);
            default -> 0;
        };
    }

    /** 条件目标值（material 类给 1） */
    public int targetOf(Title t) {
        if (t == null) {
            return 0;
        }
        return switch (t.condition.type) {
            case "material" -> 1;
            case "quality" -> {
                Quality q = Quality.byName(t.condition.id);
                yield q == null ? Integer.MAX_VALUE : q.ordinal();
            }
            default -> t.condition.min;
        };
    }

    /** 供上位查询用：配置段是否存在（自检/调试用，不参与判定） */
    public boolean configured() {
        ConfigurationSection cs = plugin.getConfig().getConfigurationSection("titles");
        return plugin.getConfig().getMapList("titles").size() > 0 || cs != null;
    }

    /** 供 GUI / 命令提示：佩戴键名（调试用） */
    public String wornKeyName() {
        return wornKey.toString();
    }

    /** 供离线查询兜底：OfflinePlayer 形态的佩戴查询（与 wornOf 同源） */
    public Title wornOf(OfflinePlayer off) {
        if (off == null) {
            return null;
        }
        String id = off.getPersistentDataContainer().get(wornKey, PersistentDataType.STRING);
        return id == null || id.isEmpty() ? null : byId(id);
    }
}
