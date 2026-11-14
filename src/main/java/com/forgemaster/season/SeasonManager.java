package com.forgemaster.season;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.ranking.Metric;
import com.forgemaster.ranking.Rankings;
import com.forgemaster.stats.StatsStore;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 赛季管理（E2）。
 *
 * <ul>
 *   <li>当前赛季状态存 plugins/ForgingMaster/season.yml（编号 + 开始时间）。</li>
 *   <li>到期（config season.duration-days）自动结算，或管理员 /zhujian season end 手动结算。</li>
 *   <li>结算 = 归档当季排行到 seasons/season-N.yml（一季一个文件）→ 重置剑币 / 声望 / 连锻 → 广播结果。</li>
 *   <li>归档的六个维度<b>口径不同</b>：剑币 / 声望 / 连锻是本赛季（结算清零），
 *       专精 / 强化 / 比试是生涯累计（结算不清零，见 {@link Metric#career()}）。
 *       档案里靠 {@code calibers.*} 段声明口径，别让读档的人把生涯累计榜当成赛季成绩。</li>
 *   <li>结算末尾顺带做一次「剑传承」：全服最强的一把刻进 hall-legacy.yml（{@link SeasonLegacy}），
 *       失败只记一条 warning，不回滚结算。</li>
 *   <li>成就与图鉴不清，专精等级不清。</li>
 *   <li>归档失败则不重置，保证数据不丢。</li>
 * </ul>
 */
public final class SeasonManager {

    public static final long DAY_MS = 86_400_000L;
    private static final int ARCHIVE_TOP = 20;
    private static final int BROADCAST_TOP = 3;

    private final ForgingMasterPlugin plugin;
    private final File stateFile;
    private final File archiveDir;
    /** 跨赛季剑传承（结算时自动刻录，见 {@link SeasonLegacy}） */
    private final SeasonLegacy legacy;
    private int number = 1;
    private long startedAt;
    private int taskId = -1;

    public SeasonManager(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.stateFile = new File(plugin.getDataFolder(), "season.yml");
        this.archiveDir = new File(plugin.getDataFolder(), "seasons");
        this.legacy = new SeasonLegacy(plugin);
    }

    /**
     * 跨赛季剑传承（名人堂「历代名剑」的数据源）。
     *
     * <p>放在这里而不是让各调用方自己 new：一份文件一个实例，
     * 名人堂陈列 / 命令 / 结算写入看到的永远是同一份数据。</p>
     */
    public SeasonLegacy legacy() {
        return legacy;
    }

    /** 载入赛季状态并启动到期检查（每分钟一次）。 */
    public void start() {
        if (stateFile.exists()) {
            YamlConfiguration yml = com.forgemaster.util.YamlIo.loadOrQuarantine(stateFile, "SeasonManager", plugin.getLogger());
            number = Math.max(1, yml.getInt("number", 1));
            startedAt = yml.getLong("started-at", System.currentTimeMillis());
        } else {
            // 首次启动：编号取 config 的 season.number，之后以 season.yml 为准
            number = Math.max(1, plugin.getConfig().getInt("season.number", 1));
            startedAt = System.currentTimeMillis();
            saveState();
        }
        shutdown();
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, com.forgemaster.util.TaskGuard.guard("SeasonManager.tick", this::tick), 20L * 60, 20L * 60).getTaskId();
    }

    /** v2.25：公开读档。回档（restore）后按盘上 season.yml 重建内存态。 */
    public void loadState() {
        if (stateFile.exists()) {
            YamlConfiguration yml = com.forgemaster.util.YamlIo.loadOrQuarantine(stateFile, "SeasonManager", plugin.getLogger());
            number = Math.max(1, yml.getInt("number", 1));
            startedAt = yml.getLong("started-at", System.currentTimeMillis());
        }
    }

    /** v2.25：公开落盘。回档前把当前赛季状态冲进 season.yml，防脏冲刷盖回。 */
    public void save() {
        saveState();
    }

    public void shutdown() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    public int number() {
        return number;
    }

    public long startedAt() {
        return startedAt;
    }

    public boolean autoEnabled() {
        return plugin.getConfig().getBoolean("season.enabled", true);
    }

    public int durationDays() {
        return Math.max(1, plugin.getConfig().getInt("season.duration-days", 30));
    }

    public long endsAt() {
        return startedAt + durationDays() * DAY_MS;
    }

    private void tick() {
        if (!autoEnabled()) {
            return;
        }
        if (System.currentTimeMillis() >= endsAt()) {
            end("赛季到期，自动结算");
        }
    }

    /** 当前赛季状态（给 /zhujian season 用） */
    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        long left = endsAt() - System.currentTimeMillis();
        out.add("<dark_gray>──── <gradient:#ffd166:#ff7a18><bold>赛季</bold></gradient> <dark_gray>────");
        out.add("<gold>当前: <white>第 " + number + " 季");
        out.add("<gray>开始: <white>" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(startedAt)));
        if (autoEnabled()) {
            if (left <= 0) {
                out.add("<gray>预计结算: <white>已到期，等待下一次检查");
            } else {
                out.add("<gray>预计结算: <white>" + (left / DAY_MS) + " 天 " + (left % DAY_MS / 3_600_000L) + " 小时后"
                        + " <dark_gray>（每 " + durationDays() + " 天一季）");
            }
        } else {
            out.add("<gray>自动结算: <white>已关闭（仅手动 /zhujian season end）");
        }
        return out;
    }

    /**
     * 结算当季：归档 → 重置 → 进入下一季 → 广播。
     *
     * @param reason 结算原因，写进归档与广播
     * @return 给操作者的结果文本；归档失败时返回失败原因且不做任何重置
     */
    public String end(String reason) {
        StatsStore store = plugin.stats();
        long now = System.currentTimeMillis();
        int ended = number;

        YamlConfiguration yml = new YamlConfiguration();
        yml.set("season", ended);
        yml.set("started-at", startedAt);
        yml.set("ended-at", now);
        yml.set("reason", reason);
        // v2.26.0：口径声明。Metric.values() 里的六个维度并不是同一种东西 ——
        // 剑币 / 声望 / 连锻是「本赛季」（resetSeasonal 会清），
        // 专精 / 强化 / 比试是「生涯累计」（底层字段是玩家攒了很久的只涨不跌战绩，刻意不清）。
        // 两者在这个文件里长得一模一样，只凭 rankings.<维度> 读档案的人会把
        // 「第 N 季 比试榜」误读成「第 N 季打了多少场」。所以把口径写进档案本身：
        // 数据说话，不依赖读档的人先看完 SeasonManager 的注释。
        // 单独放在 calibers 段而不是塞进 rankings.<维度> 下，是为了不弄脏那一层
        // 「键 = 名次」的结构（否则遍历子键解析名次的地方会撞上一个非数字键）。
        for (Metric m : Metric.values()) {
            // v2.26.0 审计轮：这三处 toLowerCase() 必须带 Locale.ROOT。
            // 不带的话走 JVM 默认 locale —— 土耳其语环境下 "I".toLowerCase() 得到的是
            // 无点 ı 而不是 i，于是 COINS 会写成 "coıns"，跟历史归档里的 "coins" 是两个键。
            // 同一个服内读写一致所以不会立刻报错，但**换服务器 / 换 locale 之后旧赛季档案就读不到了**。
            // Metric.tokens() 那边已经是 Locale.ROOT，这里曾经不一致。
            yml.set("calibers." + m.name().toLowerCase(java.util.Locale.ROOT) + ".career", m.career());
            yml.set("calibers." + m.name().toLowerCase(java.util.Locale.ROOT) + ".label", m.label());
        }
        yml.set("calibers-note", "career=true 的维度是本文件写入时刻的生涯累计值（结算不清零，故相邻几季档案里的同一榜单会重复）；"
                + "career=false 的才是本赛季成绩（结算已清零，下一季从零开始）。");
        for (Metric m : Metric.values()) {
            // 生涯维度归档的是「截至本季结算时的生涯值」：同一份榜单在连续几季档案里会重复出现，
            // 那不是 bug —— 它记录的是那一刻的天花板。改动这一行为前先想清楚：
            // 把这些字段清掉 = 玩家的历史最高强化 / 生涯胜场直接消失，是数据事故。
            for (Rankings.Row r : Rankings.top(store, m, ARCHIVE_TOP)) {
                String base = "rankings." + m.name().toLowerCase(java.util.Locale.ROOT) + "." + r.rank();
                yml.set(base + ".uuid", r.uuid().toString());
                yml.set(base + ".name", r.name());
                yml.set(base + ".value", r.value());
                yml.set(base + ".coins", r.stats().coins);
                yml.set(base + ".reputation", r.stats().reputation);
                yml.set(base + ".best-streak", r.stats().bestStreak);
                yml.set(base + ".achievements", r.stats().achievements.size());
            }
        }

        File file = new File(archiveDir, "season-" + ended + ".yml");
        try {
            if (!archiveDir.exists() && !archiveDir.mkdirs()) {
                return "无法创建 seasons 目录，本季未结算。";
            }
            com.forgemaster.util.YamlIo.save(file, yml);
        } catch (IOException ex) {
            plugin.getLogger().warning("赛季归档失败: " + ex.getMessage());
            return "归档失败（" + ex.getMessage() + "），本季未重置。";
        }

        // 播报必须在重置之前：broadcast 现读排行榜，重置后数值全 0，榜单会变成空气
        broadcast(ended, reason);

        store.resetSeasonal();
        store.save();

        number = ended + 1;
        startedAt = now;
        saveState();

        // 跨赛季剑传承：把本季最强的一把刻进名人堂（一季一条，只增不删）。
        // 位置放在播报之后：名剑入堂播报接在「新赛季已开启」后面，读起来顺；
        // 且 bestScore / bestQuality / bestStrengthen 都是生涯字段、不被 resetSeasonal 清掉，
        // 重置前后读到的是同一组数据，放这里不会漏掉任何一把。
        // 外面必须套住：传承写盘失败 / 名人堂重摆炸了，都不能把已经完成的赛季结算带下去。
        try {
            if (legacy.record(ended)) {
                SeasonLegacy.Legacy rec = legacy.ofSeason(ended);
                plugin.getLogger().info("第 " + ended + " 季名剑已刻入名人堂："
                        + (rec == null ? "?" : rec.playerName() + " / " + rec.materialName()
                        + " / " + rec.quality() + " / " + Msg.fmt(rec.score(), 1) + " 分 / +" + rec.strengthenLevel()));
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("第 " + ended + " 季剑传承刻录失败（赛季结算已完成，不影响运行）: " + ex);
        }

        // v2.23 拓展 C：赛季结算播报。放在所有提前 return 之后 —— 上面两条失败路径
        // 都没有真的结算，播出去会让群里以为赛季已经重置了。
        broadcastEnd(ended, reason);

        return "第 " + ended + " 季已结算，归档到 seasons/" + file.getName() + "，进入第 " + number + " 季。";
    }

    /**
     * 结算完成后的 Webhook 播报（v2.23 拓展 C）。
     *
     * <p>单独抽出来是因为 {@link #end(String)} 里有多个提前 return（目录建不出来、归档失败），
     * 那些路径**没有真的结算**，不该播报「赛季已结束」。只有走到最后一行才调这里。</p>
     */
    private void broadcastEnd(int ended, String reason) {
        plugin.broadcast(com.forgemaster.webhook.WebhookEvent.SEASON_END,
                "【赛季】第 " + ended + " 季已结算（" + reason + "），进入第 " + number + " 季",
                java.util.Map.of("season", String.valueOf(ended),
                        "reason", String.valueOf(reason),
                        "next", String.valueOf(number)));
    }

    private void broadcast(int ended, String reason) {
        StatsStore store = plugin.stats();
        Bukkit.broadcast(Msg.mm(Msg.PREFIX + "<gradient:#ffd166:#ff7a18><bold>第 " + ended + " 季结算</bold></gradient> "
                + "<gray>（" + reason + "）"));
        for (Metric m : new Metric[]{Metric.COINS, Metric.REPUTATION, Metric.STREAK}) {
            List<Rankings.Row> rows = Rankings.top(store, m, BROADCAST_TOP);
            if (rows.isEmpty()) {
                continue;
            }
            StringBuilder sb = new StringBuilder("<dark_gray>        " + m.label() + " ");
            for (Rankings.Row r : rows) {
                sb.append("<gold>#").append(r.rank()).append(" <white>").append(r.name())
                        .append("<gray>(").append(m.display(r.stats())).append(") ");
            }
            Bukkit.broadcast(Msg.mm(sb.toString()));
        }
        Bukkit.broadcast(Msg.mm("<dark_gray>        新赛季已开启：第 " + number + " 季。成就与图鉴保留。"));
    }

    private void saveState() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("无法创建插件数据目录");
            return;
        }
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("number", number);
        yml.set("started-at", startedAt);
        try {
            com.forgemaster.util.YamlIo.save(stateFile, yml);
        } catch (IOException ex) {
            plugin.getLogger().warning("保存 season.yml 失败: " + ex.getMessage());
        }
    }
}
