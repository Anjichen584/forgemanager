package com.forgemaster.hall;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.season.SeasonLegacy;
import com.forgemaster.stats.StatsStore;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 名人堂盔甲架陈列：展示全服最佳分 Top N。
 *
 * <p>位置与刷新间隔来自 config.yml 的 {@code hall-of-fame} 段；
 * 位置由 {@code /zhujian halloffame set} 写入 config 的 world / x / y / z。
 * 残留盔甲架通过 PersistentDataContainer 上的标记识别，关服与重载时统一清理。</p>
 *
 * <p>兼容赛季：排行数据统一走 {@link StatsStore#top(int)}，赛季重置后 bestScore 归零即可自动更新。</p>
 *
 * <p>强化维度：榜单末尾多一排「强化之巅」，展示全服历史最高锻造等级（{@link StrengthenHall}）。
 * 那一排用的是同一套 PDC 标记，所以关服 / 重载 / 刷新前会被 {@link #clearAll()} 一起清掉。</p>
 *
 * <p>传承维度：再往下是「历代名剑」——每季一个赛季结算时刻下的永久记录
 * （数据源 {@link com.forgemaster.season.SeasonLegacy}，最多摆最近
 * {@code season.legacy.show-count} 季，默认 5）。同样一套盔甲架 + 同一套 PDC 标记，
 * 所以四类条目共用一次 {@link #clearAll()}，互不残留。</p>
 *
 * <p>大赛维度：最后是「大赛冠军」——每届一场的赛果档案（v2.26.1 接上 v2.26.0 留的口子），
 * 数据源 {@link com.forgemaster.contests.ContestHistoryStore}（{@code contest-history.yml}），
 * 只读展示：摆最近几届的「届次 / 冠军 / 分数」，<b>不写任何存档、不碰经济</b>。
 * 档案为空（还没打过大赛）时摆一行空态文案；大赛模块没初始化时整块跳过。</p>
 *
 * <p><b>为什么这里也用盔甲架，而不是告示牌</b>：告示牌要在世界方块里落数据，
 * 摆的时候得改方块、清理时得还原，玩家在陈列区修个东西就可能被顺手拆掉或覆盖；
 * 而盔甲架是实体，能靠 PDC 标记精确回收，位置只由配置决定，不改动地形。
 * 四类条目共用一套生成 / 清理逻辑，不会出现「刷新把上一类擦掉了」这种事。</p>
 */
public final class HallOfFame {

    /** 强化纪录那一排的 rank 标记值：负数，和上面 1..N 的玩家名次区分开 */
    private static final int STRENGTHEN_RANK = -1;

    /** 历代名剑的 rank 标记基准：-1000 - 序号，和 -1 的强化排、1..N 的名次都不冲突 */
    private static final int LEGACY_RANK_BASE = -1000;

    /** 大赛冠军的 rank 标记基准：-2000 - 序号，和强化排 / 历代名剑 / 名次都不冲突 */
    private static final int CONTEST_RANK_BASE = -2000;

    /** 大赛冠军陈列最多摆最近几届（与历代名剑默认 showCount 对齐；档案本身保留更多） */
    static final int CONTEST_SHOW_ROWS = 5;

    private final ForgingMasterPlugin plugin;
    private final NamespacedKey markKey;
    private final NamespacedKey rankKey;
    private final StrengthenHall strengthen;
    private BukkitTask refreshTask;

    public HallOfFame(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.markKey = new NamespacedKey(plugin, "hall_of_fame");
        this.rankKey = new NamespacedKey(plugin, "hall_rank");
        this.strengthen = new StrengthenHall(plugin, this);
    }

    /** 「最高锻造等级」条目存档（强化成功时由调用方写入，见 StrengthenHall#record） */
    public StrengthenHall strengthen() {
        return strengthen;
    }

    /** 启动定时刷新。关闭或未配置位置时不启动。 */
    public void start() {
        stop();
        if (!enabled()) {
            return;
        }
        int interval = Math.max(1, plugin.getConfig().getInt("hall-of-fame.refresh-seconds", 300));
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, com.forgemaster.util.TaskGuard.guard("HallOfFame.refresh", this::refresh),
                20L, interval * 20L);
    }

    /** 停止刷新并清理残留盔甲架 */
    public void stop() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        clearAll();
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("hall-of-fame.enabled", true) && location() != null;
    }

    /** 读取配置中的陈列位置，未设置或世界不存在时返回 null */
    public Location location() {
        // 优先读 hall.yml（/zhujian halloffame set 写入），不存在再回退 config.yml 默认段
        File file = hallFile();
        ConfigurationSection sec;
        if (file.exists()) {
            sec = com.forgemaster.util.YamlIo.loadOrQuarantine(file, "HallOfFame", plugin.getLogger());
        } else {
            sec = plugin.getConfig().getConfigurationSection("hall-of-fame.location");
        }
        if (sec == null) {
            return null;
        }
        World world = Bukkit.getWorld(sec.getString("world", ""));
        if (world == null) {
            return null;
        }
        return new Location(world, sec.getDouble("x"), sec.getDouble("y"), sec.getDouble("z"));
    }

    /** 写入陈列位置（由 /zhujian halloffame set 调用）并立即刷新 */
    public void setLocation(Location loc) {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("world", loc.getWorld().getName());
        yml.set("x", loc.getX());
        yml.set("y", loc.getY());
        yml.set("z", loc.getZ());
        File file = hallFile();
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            com.forgemaster.util.YamlIo.save(file, yml);
        } catch (IOException ex) {
            plugin.getLogger().warning("名人堂位置保存失败：" + ex.getMessage());
            return;
        }
        start();
    }

    /** 位置存档文件：plugins/ForgingMaster/hall.yml */
    private File hallFile() {
        return new File(plugin.getDataFolder(), "hall.yml");
    }

    /** 刷新盔甲架：先清理旧的，再按 Top N 重新生成 */
    public void refresh() {
        clearAll();
        Location base = location();
        if (base == null) {
            return;
        }
        int topN = Math.max(1, Math.min(10, plugin.getConfig().getInt("hall-of-fame.size", 10)));
        List<Map.Entry<UUID, StatsStore.Stats>> top = plugin.stats().top(topN);
        // 每行间距 0.35 格，从位置向下排
        double step = 0.35;
        for (int i = 0; i < top.size(); i++) {
            Map.Entry<UUID, StatsStore.Stats> e = top.get(i);
            Location at = base.clone().add(0, -i * step, 0);
            spawnRow(at, i + 1, e.getValue());
        }
        // 下面这两类接在榜单之后，一行一个，往下继续排
        double next = -top.size() * step;
        // 强化之巅：没纪录或开关关掉就不生成
        if (strengthen.enabled() && strengthen.showRow()) {
            String line = strengthen.line();
            if (line != null) {
                spawnStrengthenRow(base.clone().add(0, next, 0), line);
                next -= step;
            }
        }
        // 历代名剑：一季一行，按赛季升序（旧的在上、新的在下），最多最近 N 季
        SeasonLegacy legacy = legacy();
        if (legacy != null && legacy.enabled() && legacy.showRow()) {
            List<SeasonLegacy.Legacy> recent = legacy.recent(legacy.showCount());
            for (int i = 0; i < recent.size(); i++) {
                spawnLegacyRow(base.clone().add(0, next - i * step, 0), i, legacy.describe(recent.get(i)));
            }
        }
        // 大赛冠军（v2.26.1）：一届一行，按届次升序（旧的在上、新的在下），最多最近 N 届。
        // 数据源只有 ContestHistoryStore（contest-history.yml），只读展示，不写档不碰经济；
        // 大赛模块没初始化时整块跳过，档案为空时 contestLines 给一行空态文案。
        var contests = plugin.contests();
        if (contests != null) {
            com.forgemaster.contests.ContestHistoryStore store = contests.historyStore();
            List<String> championLines = contestLines(store.recent(CONTEST_SHOW_ROWS), store.size());
            for (int i = 0; i < championLines.size(); i++) {
                spawnContestRow(base.clone().add(0, next - i * step, 0), i, championLines.get(i));
            }
        }
    }

    /**
     * 大赛冠军的展示行（v2.26.1，public static 是为了自检可以直接断言，不需要 Bukkit 世界）。
     *
     * <p>行格式照历代名剑的排法：{@code 第 N 届 · 冠军名 · 分数 · 剑胚}，末尾附档案号（种子号），
     * 方便对上 {@code /zhujian contest history <号>} 查单届详情。</p>
     *
     * <p>届次号 N 是档案内的序号（1 = 档案里最老的一届）：{@code contest-history.yml} 按
     * {@code contest.history-keep} 从头部裁最老的届，绝对届数无从考证，档案内序号是唯一
     * 不会撒谎的口径。</p>
     *
     * @param recent    最近几届赛果（旧 → 新）；为空时给一行空态文案，不抛异常
     * @param totalSize 档案总届数（用于把序号对齐到「档案里最老的一届 = 第 1 届」）
     */
    public static List<String> contestLines(List<com.forgemaster.contests.ContestHistoryStore.Entry> recent,
                                            int totalSize) {
        List<String> out = new ArrayList<>();
        if (recent == null || recent.isEmpty()) {
            out.add("<gray>大赛还没有冠军——第一场打完就有了。</gray>");
            return out;
        }
        int first = Math.max(1, totalSize - recent.size() + 1);
        for (int i = 0; i < recent.size(); i++) {
            com.forgemaster.contests.ContestHistoryStore.Entry e = recent.get(i);
            String winner = e.winner == null || e.winner.isBlank() ? "无名" : e.winner;
            String material = e.material == null || e.material.isBlank() ? "未知剑胚" : e.material;
            out.add("<#ffd166>第 " + (first + i) + " 届</#ffd166>"
                    + " <white>" + winner + "</white>"
                    + " <dark_gray>·</dark_gray> <gray>"
                    + com.forgemaster.contests.ContestHistoryStore.fmtScore(e.winnerScore) + " 分</gray>"
                    + " <dark_gray>·</dark_gray> <gray>" + material + "</gray>"
                    + " <dark_gray>" + (e.code == null || e.code.isBlank() ? "" : "#" + e.code) + "</dark_gray>");
        }
        return out;
    }

    /** 大赛冠军那一行：内容由 {@link #contestLines} 拼好，这里只负责摆盔甲架 */
    private void spawnContestRow(Location at, int index, String line) {
        World world = at.getWorld();
        if (world == null || line == null) {
            return;
        }
        ArmorStand stand = world.spawn(at, ArmorStand.class, s -> {
            s.setVisible(false);
            s.setGravity(false);
            s.setInvulnerable(true);
            s.setSmall(true);
            s.setMarker(true);
            s.setPersistent(false);
            s.setCustomNameVisible(true);
            s.customName(Msg.mm(line));
            // 和另外三类共用标记：关服 / 重载 / 刷新前的 clearAll() 一起收走
            s.getPersistentDataContainer().set(markKey, PersistentDataType.BYTE, (byte) 1);
            s.getPersistentDataContainer().set(rankKey, PersistentDataType.INTEGER, CONTEST_RANK_BASE - index);
        });
        if (stand == null) {
            plugin.getLogger().warning("名人堂盔甲架生成失败：大赛冠军第 " + (index + 1) + " 行");
        }
    }

    /**
     * 历代名剑的数据源。
     *
     * <p>每次刷新现取，不缓存：赛季管理器可能还没起来（返回 null，这一块直接跳过），
     * 而 SeasonLegacy 本身每次都从 hall-legacy.yml 读，不会拿到过期快照。</p>
     */
    private SeasonLegacy legacy() {
        var seasons = plugin.seasons();
        return seasons == null ? null : seasons.legacy();
    }

    /** 历代名剑那一行：内容由 {@link SeasonLegacy#describe} 拼好，这里只负责摆盔甲架 */
    private void spawnLegacyRow(Location at, int index, String line) {
        World world = at.getWorld();
        if (world == null || line == null) {
            return;
        }
        ArmorStand stand = world.spawn(at, ArmorStand.class, s -> {
            s.setVisible(false);
            s.setGravity(false);
            s.setInvulnerable(true);
            s.setSmall(true);
            s.setMarker(true);
            s.setPersistent(false);
            s.setCustomNameVisible(true);
            s.customName(Msg.mm(line));
            // 和另外两类共用标记：关服 / 重载 / 刷新前的 clearAll() 一起收走
            s.getPersistentDataContainer().set(markKey, PersistentDataType.BYTE, (byte) 1);
            s.getPersistentDataContainer().set(rankKey, PersistentDataType.INTEGER, LEGACY_RANK_BASE - index);
        });
        if (stand == null) {
            plugin.getLogger().warning("名人堂盔甲架生成失败：历代名剑第 " + (index + 1) + " 行");
        }
    }

    /** 强化纪录那一排：内容由 StrengthenHall#line 拼好，这里只负责摆盔甲架 */
    private void spawnStrengthenRow(Location at, String line) {
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        ArmorStand stand = world.spawn(at, ArmorStand.class, s -> {
            s.setVisible(false);
            s.setGravity(false);
            s.setInvulnerable(true);
            s.setSmall(true);
            s.setMarker(true);
            s.setPersistent(false);
            s.setCustomNameVisible(true);
            s.customName(Msg.mm(line));
            s.getPersistentDataContainer().set(markKey, PersistentDataType.BYTE, (byte) 1);
            s.getPersistentDataContainer().set(rankKey, PersistentDataType.INTEGER, STRENGTHEN_RANK);
        });
        if (stand == null) {
            plugin.getLogger().warning("名人堂盔甲架生成失败：强化纪录");
        }
    }

    private void spawnRow(Location at, int rank, StatsStore.Stats st) {
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        String name = st.name == null || st.name.isEmpty() ? "无名" : st.name;
        String title = StatsStore.titleOf(st.reputation);
        String line = "<#ffd166>#" + rank + "</#ffd166> <white>" + name
                + "</white> <dark_gray>|</dark_gray> <gray>" + Msg.fmt(st.bestScore, 1)
                + " 分</gray> <dark_gray>|</dark_gray> <#7ef2c8>" + title + "</#7ef2c8>";
        ArmorStand stand = world.spawn(at, ArmorStand.class, s -> {
            s.setVisible(false);
            s.setGravity(false);
            s.setInvulnerable(true);
            s.setSmall(true);
            s.setMarker(true);
            s.setPersistent(false);
            s.setCustomNameVisible(true);
            s.customName(Msg.mm(line));
            s.getPersistentDataContainer().set(markKey, PersistentDataType.BYTE, (byte) 1);
            s.getPersistentDataContainer().set(rankKey, PersistentDataType.INTEGER, rank);
        });
        if (stand == null) {
            plugin.getLogger().warning("名人堂盔甲架生成失败：第 " + rank + " 名");
        }
    }

    /** 清理本插件生成的所有盔甲架（已加载区块内），用于关服、重载、刷新前 */
    public int clearAll() {
        int removed = 0;
        Location base = location();
        if (base == null) {
            return 0;
        }
        World world = base.getWorld();
        if (world == null) {
            return 0;
        }
        for (Entity e : world.getEntitiesByClass(ArmorStand.class)) {
            if (e.getPersistentDataContainer().has(markKey, PersistentDataType.BYTE)) {
                e.remove();
                removed++;
            }
        }
        return removed;
    }
}
