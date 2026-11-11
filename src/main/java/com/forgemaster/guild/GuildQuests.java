package com.forgemaster.guild;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.econ.CoinReason;
import com.forgemaster.econ.CoinRules;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 行会委托（v2.23 · P1-6）：全行会协作目标，配置驱动生成，达成后全会在线成员各领一份剑币。
 *
 * <p>两种目标类型：</p>
 * <ul>
 *   <li><b>集体锻剑</b>（forge）：成员累计锻造 N 把剑。进度埋点是<b>只读轮询</b>——
 *       每隔 {@code guild.quest.poll-seconds}（默认 300 秒）扫一遍成员档案
 *       {@link com.forgemaster.stats.StatsStore#peek} 的 {@code forged} 累计值，
 *       与本周期基线做差累加；首见成员先建档不追认历史（防旧产量白嫖新周期）。</li>
 *   <li><b>集体强化</b>（strengthen）：成员累计铁砧强化提升 N 个总等级。档案里只有
 *       「历史最高强化等级」（只涨不跌），没有累计计数——轮询读它的增量近似
 *       （只有刷出新高才计分，原地强化不计）。要精确计数就得改强化埋点，
 *       违反「不改其他包」约束；由此产生的少计由会长手动上报补（见下）。</li>
 * </ul>
 *
 * <p>周期：{@code guild.quest.cycle-hours}（默认 24）。=24 时对齐本地 0 点刷新，
 * 其余按「周期起点 + N 小时」滚动。刷新时换目标、清进度、清领奖名单、清基线与上报计数。</p>
 *
 * <p>会长手动上报（轮询读不到 / 轮询少计的兜底）：{@code /zhujian guild quest report
 * <成员> [数量]}，只有会长能用；每个成员每周期最多上报 {@code guild.quest.report-daily-cap}
 * 次（防刷），单次数量钳在 {@code guild.quest.report-max} 内。</p>
 *
 * <p>奖励：{@code 基础值 × (1 + (人数-1) × 人数系数)}，纯函数 {@link #rewardFor}。
 * 领奖一次性收口在 {@link #claimOneTime}：领过的人记进本周期 claimed 集合，
 * 刷新才清。状态存 guild-data.yml 的 {@code guilds.<id>.quest.*} 段，
 * 跟随 GuildService 的 dirty + 异步冲刷落盘。</p>
 */
public final class GuildQuests {

    /** 一条委托状态（随行会走，guild-data.yml 的 guilds.<id>.quest 段） */
    public static final class QuestState {
        /** 目标类型："forge" | "strengthen"（空 = 尚未初始化） */
        public String type = "";
        public long target;
        public long progress;
        /** 本周期起点（毫秒 epoch） */
        public long periodStart;
        public boolean completed;
        /** 已完成轮数（alternate 模式拿它轮流换目标类型） */
        public int cycles;
        /** 本周期已领奖成员（UUID 字符串） */
        public final LinkedHashSet<String> claimed = new LinkedHashSet<>();
        /** 轮询基线：成员 uuid → 上次读到的累计值（forged / bestStrengthen） */
        public final Map<String, Long> baseline = new LinkedHashMap<>();
        /** 本周期手动上报计数：成员 uuid → 次数 */
        public final Map<String, Integer> reports = new LinkedHashMap<>();
    }

    private final ForgingMasterPlugin plugin;
    private final Map<String, QuestState> states = new LinkedHashMap<>();
    private volatile boolean dirty;
    private BukkitTask pollTask;

    /** GuildService 构造时挂进来；运行期经 plugin.guilds() 反查服务 */
    public GuildQuests(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ 配置

    public boolean enabled() {
        return plugin.settings().flag("guild.quest.enabled", true);
    }

    /** 周期小时数（默认 24；=24 时对齐本地 0 点） */
    private int cycleHours() {
        return Math.max(1, plugin.settings().intOf("guild.quest.cycle-hours", 24));
    }

    /** 目标类型模式：forge / strengthen / random / alternate（默认轮流） */
    private String typeMode() {
        return plugin.settings().str("guild.quest.type", "alternate")
                .trim().toLowerCase(Locale.ROOT);
    }

    private long forgeTarget() {
        return clampTarget(plugin.settings().intOf("guild.quest.forge-target", 10));
    }

    private long strengthenTarget() {
        return clampTarget(plugin.settings().intOf("guild.quest.strengthen-target", 15));
    }

    private long rewardBase() {
        return Math.max(0, plugin.settings().longOf("guild.quest.reward-base", 100));
    }

    private double rewardPerMember() {
        return Math.max(0.0, plugin.settings().num("guild.quest.reward-per-member", 0.1));
    }

    /** 手动上报：每个成员每周期最多几次 */
    private int reportDailyCap() {
        return Math.max(1, plugin.settings().intOf("guild.quest.report-daily-cap", 5));
    }

    /** 手动上报：单次最多记多少进度 */
    private long reportMax() {
        return Math.max(1, plugin.settings().intOf("guild.quest.report-max", 3));
    }

    // ------------------------------------------------------------ 纯函数（自检直接算）

    /** 目标数量钳制：1~100 万（纯函数，防配置写歪） */
    public static long clampTarget(long raw) {
        return Math.max(1, Math.min(1_000_000L, raw));
    }

    /** 进度推进：负增量忽略、进度不下滑（纯函数） */
    public static long applyProgress(long progress, long delta) {
        return Math.max(0, progress + Math.max(0, delta));
    }

    /** 是否达标（纯函数） */
    public static boolean isComplete(QuestState q) {
        return q != null && q.completed;
    }

    /**
     * 奖励 = 基础值 × (1 + (人数-1) × 人数系数)，负值钳 0（纯函数）。
     * 人数系数按「满员行会多拿一些」的方向理解：3 人 ×0.1 系数 = 1.2 倍。
     */
    public static long rewardFor(long base, double perMember, int memberCount) {
        if (base <= 0) {
            return 0;
        }
        double mult = 1.0 + Math.max(0, memberCount - 1) * Math.max(0.0, perMember);
        return Math.max(0, Math.round(base * mult));
    }

    /**
     * 领奖一次性（纯逻辑，防重复领奖）：未达标不给；领过不给（claimed.add 幂等）。
     *
     * @return true = 这次真的领到了
     */
    public static boolean claimOneTime(QuestState q, UUID member) {
        if (q == null || !q.completed || member == null) {
            return false;
        }
        return q.claimed.add(member.toString());
    }

    /** 目标类型的中文名（面板 / 广播用） */
    public static String typeName(String type) {
        return "strengthen".equals(type) ? "集体强化" : "集体锻剑";
    }

    // ------------------------------------------------------------ 生命周期

    /** 低频轮询任务（GuildService.start() 调用） */
    public void start() {
        stop();
        long period = Math.max(60, plugin.settings().intOf("guild.quest.poll-seconds", 300)) * 20L;
        pollTask = Bukkit.getScheduler().runTaskTimer(plugin, this::poll, 20L * 60, period);
    }

    public void stop() {
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean dirty() {
        return dirty;
    }

    /** GuildService.save() 成功落盘后清脏 */
    public synchronized void clearDirty() {
        dirty = false;
    }

    /** 行会解散时清掉它的委托状态 */
    public void remove(String guildId) {
        if (states.remove(guildId) != null) {
            dirty = true;
        }
    }

    /**
     * 只读查看某行的委托状态（不触发刷新），没建过返回 null。
     * 面板速览用；要「保证新鲜」的路径走 {@link #command} 系。
     */
    public synchronized QuestState peek(String guildId) {
        return guildId == null ? null : states.get(guildId);
    }

    // ------------------------------------------------------------ 轮询

    /**
     * 低频轮询：跨周期就刷新；没达标就读成员档案增量推进度，达标广播。
     * 只读 StatsStore 缓存，不写任何其他包的状态。
     */
    private void poll() {
        if (!enabled() || plugin.guilds() == null) {
            return;
        }
        for (GuildService.Guild g : plugin.guilds().all()) {
            try {
                QuestState q = ensureFresh(g); // 顺带处理跨周期刷新
                if (q.completed) {
                    continue;
                }
                long delta = readDelta(g, q);
                if (delta > 0) {
                    q.progress = applyProgress(q.progress, delta);
                    dirty = true;
                    if (q.progress >= q.target) {
                        complete(g, q);
                    }
                }
            } catch (Exception ignored) {
                // 单行会异常不拖垮整轮
            }
        }
    }

    /**
     * 扫成员档案的累计值做差。forge 读 {@code forged}（累计锻造数），
     * strengthen 读 {@code bestStrengthen}（历史最高强化等级，单调不跌）。
     * 首见成员只建档不追认；档案值回退只对齐不倒扣。
     */
    private long readDelta(GuildService.Guild g, QuestState q) {
        long sum = 0;
        for (UUID id : g.memberIds()) {
            com.forgemaster.stats.StatsStore.Stats st = plugin.stats().peek(id);
            long cur;
            if ("strengthen".equals(q.type)) {
                cur = st == null ? 0 : st.bestStrengthen;
            } else {
                cur = st == null ? 0 : st.forged;
            }
            String key = id.toString();
            Long base = q.baseline.get(key);
            if (base == null) {
                q.baseline.put(key, cur); // 首见建档：不追认历史产量
                continue;
            }
            long d = cur - base;
            if (d > 0) {
                sum += d;
            }
            q.baseline.put(key, cur);
        }
        return sum;
    }

    // ------------------------------------------------------------ 周期

    /** 取行会当前委托；没建过 / 跨周期就先刷新 */
    private QuestState ensureFresh(GuildService.Guild g) {
        QuestState q = states.computeIfAbsent(g.id, k -> new QuestState());
        long now = System.currentTimeMillis();
        if (q.type.isEmpty()) {
            refresh(g, q, now);
        } else if (now - q.periodStart >= cycleHours() * 3_600_000L) {
            refresh(g, q, now);
        }
        return q;
    }

    /** 开新周期：换目标、清进度 / 领奖名单 / 基线 / 上报计数，向在线成员播报新目标 */
    private void refresh(GuildService.Guild g, QuestState q, long now) {
        String mode = typeMode();
        String type;
        if ("forge".equals(mode) || "strengthen".equals(mode)) {
            type = mode;
        } else if ("random".equals(mode)) {
            type = java.util.concurrent.ThreadLocalRandom.current().nextBoolean() ? "forge" : "strengthen";
        } else {
            type = q.cycles % 2 == 0 ? "forge" : "strengthen"; // alternate（默认）：轮流
        }
        q.type = type;
        q.target = "strengthen".equals(type) ? strengthenTarget() : forgeTarget();
        q.progress = 0;
        q.completed = false;
        q.periodStart = alignStart(now);
        q.cycles++;
        q.claimed.clear();
        q.baseline.clear();
        q.reports.clear();
        dirty = true;
        for (UUID id : g.memberIds()) {
            Player pl = Bukkit.getPlayer(id);
            if (pl != null) {
                Msg.prefix(pl, "<gray>行会委托刷新：</gray><#ffd166>" + typeName(type)
                        + " ×" + q.target + "</#ffd166><gray>，达成后全员领剑币（</gray>"
                        + "<white>/zhujian guild quest</white><gray> 查看进度）。</gray>");
            }
        }
    }

    /** 周期起点：24 小时制对齐本地 0 点，其余滚动 */
    private long alignStart(long now) {
        if (cycleHours() == 24) {
            return java.time.LocalDate.now()
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        return now;
    }

    /** 达成：标完成并向在线成员广播领奖提示 */
    private void complete(GuildService.Guild g, QuestState q) {
        if (q.completed) {
            return;
        }
        q.completed = true;
        dirty = true;
        for (UUID id : g.memberIds()) {
            Player pl = Bukkit.getPlayer(id);
            if (pl != null) {
                Msg.prefix(pl, "<#ffd166>行会委托达成！</#ffd166><gray>「</gray>"
                        + typeName(q.type) + "<gray>」进度已满，输入 </gray>"
                        + "<white>/zhujian guild quest claim</white><gray> 领取 "
                        + CoinRules.fmt(rewardOf(g)) + " 剑币。</gray>");
                pl.playSound(pl.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.2f);
            }
        }
        // v2.23 拓展 C：行会委托达成 → Webhook（note: 只播一次，在线成员提示在循环里）
        plugin.broadcast(com.forgemaster.webhook.WebhookEvent.GUILD_QUEST_DONE,
                "【行会委托】" + g.name + " 达成「" + typeName(q.type) + "」，"
                        + g.members.size() + " 名成员各可领 "
                        + CoinRules.fmt(rewardOf(g)) + " 剑币",
                java.util.Map.of("guild", g.name,
                        "quest", typeName(q.type),
                        "members", String.valueOf(g.members.size()),
                        "reward", CoinRules.fmt(rewardOf(g))));
    }

    /** 当前每个成员可领的剑币 */
    private long rewardOf(GuildService.Guild g) {
        return rewardFor(rewardBase(), rewardPerMember(), g.members.size());
    }

    // ------------------------------------------------------------ 命令（挂在 guild 命令体系内）

    /** /zhujian guild quest [info|claim|report <成员> [数量]] */
    public void command(Player p, String[] args) {
        if (!enabled()) {
            Msg.prefix(p, "<red>行会委托没开。</red>");
            return;
        }
        GuildService.Guild g = plugin.guilds().guildOf(p.getUniqueId());
        if (g == null) {
            Msg.prefix(p, "<gray>你还没有行会。</gray>");
            return;
        }
        String sub = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "claim", "lingjiang", "ling" -> claim(p, g);
            case "report", "shangbao" -> {
                if (args.length < 4) {
                    Msg.prefix(p, "<gray>用法：<white>/zhujian guild quest report <成员> [数量]</white></gray>");
                    return;
                }
                long amount = 1;
                if (args.length >= 5) {
                    try {
                        amount = Long.parseLong(args[4].replace(",", ""));
                    } catch (NumberFormatException ex) {
                        Msg.prefix(p, "<red>数量要写数字：" + args[4]);
                        return;
                    }
                }
                report(p, g, args[3], amount);
            }
            case "info", "xinxi", "" -> info(p, g);
            default -> Msg.prefix(p, "<gray>用法：<white>/zhujian guild quest [info|claim|report <成员> [数量]]</white></gray>");
        }
    }

    /** 委托面板（两行文字，不做 GUI——低频信息不值得占一个界面） */
    private void info(Player p, GuildService.Guild g) {
        QuestState q = ensureFresh(g);
        Msg.send(p, "<dark_gray>──── <#ffd166>行会委托</#ffd166> <dark_gray>────");
        Msg.send(p, "<gray>目标: <white>" + typeName(q.type) + " ×" + q.target + "</white> <dark_gray>·</dark_gray> "
                + "进度 <#ffd166>" + q.progress + "/" + q.target + "</#ffd166>"
                + (q.completed ? " <green>已达成</green>" : ""));
        Msg.send(p, "<gray>赏金: <white>" + CoinRules.fmt(rewardOf(g)) + "</white>/人 <dark_gray>·</dark_gray> "
                + (q.completed ? "<green>/zhujian guild quest claim 领取</green>"
                : "<gray>锻造 / 强化自动累计，会长可 report 手动上报</gray>"));
    }

    /** 在线成员领奖（一次性，领过不给） */
    private void claim(Player p, GuildService.Guild g) {
        QuestState q = ensureFresh(g);
        if (!q.completed) {
            Msg.prefix(p, "<gray>委托还没达成（" + typeName(q.type) + " " + q.progress + "/" + q.target
                    + "），继续干。</gray>");
            return;
        }
        if (!claimOneTime(q, p.getUniqueId())) {
            Msg.prefix(p, "<gray>这一轮你已经领过了，等下轮刷新。</gray>");
            return;
        }
        dirty = true;
        long reward = rewardOf(g);
        // 记 CONTEST 桶（协作竞赛类产出）；GUILD 桶是消耗向的，混进去会污染报表
        plugin.stats().addCoins(p.getUniqueId(), reward, CoinReason.CONTEST);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.9f, 1.4f);
        Msg.prefix(p, "<green>行会委托奖励已入账：<white>" + CoinRules.fmt(reward)
                + "</white> 剑币（基础 × 人数系数）。</green>");
    }

    /** 会长手动上报成员产出（防刷：每人每周期有次数上限，单次有量上限） */
    private void report(Player p, GuildService.Guild g, String targetName, long amountRaw) {
        if (!g.owner.equals(p.getUniqueId())) {
            Msg.prefix(p, "<red>只有会长能手动上报。</red>");
            return;
        }
        QuestState q = ensureFresh(g);
        if (q.completed) {
            Msg.prefix(p, "<gray>本轮委托已达成，等下轮再上报。</gray>");
            return;
        }
        UUID target = resolveMember(g, targetName);
        if (target == null) {
            Msg.prefix(p, "<red>行会成员里没有 " + targetName + "。</red>");
            return;
        }
        String key = target.toString();
        int done = q.reports.merge(key, 1, Integer::sum);
        if (done > reportDailyCap()) {
            q.reports.put(key, reportDailyCap()); // 拒绝后把计数压回上限
            Msg.prefix(p, "<red>" + nameOf(target) + " 本周期已被上报 " + reportDailyCap()
                    + " 次（上限 guild.quest.report-daily-cap），不能再报。</red>");
            return;
        }
        long amount = Math.max(1, Math.min(amountRaw, reportMax()));
        q.progress = applyProgress(q.progress, amount);
        dirty = true;
        Msg.prefix(p, "<gray>已记录 <white>" + nameOf(target) + "</white> 的产出 <white>+" + amount
                + "</white>，委托进度 <#ffd166>" + q.progress + "/" + q.target + "</#ffd166>（"
                + nameOf(target) + " 本周期上报 " + done + "/" + reportDailyCap() + " 次）。</gray>");
        if (q.progress >= q.target) {
            complete(g, q);
        }
    }

    /** 按名字找行会成员：先查在线成员，再查档案名 */
    private UUID resolveMember(GuildService.Guild g, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null && g.members.contains(online.getUniqueId().toString())) {
            return online.getUniqueId();
        }
        for (UUID id : g.memberIds()) {
            com.forgemaster.stats.StatsStore.Stats st = plugin.stats().peek(id);
            if (st != null && st.name != null && st.name.equalsIgnoreCase(name)) {
                return id;
            }
        }
        return null;
    }

    /** 玩家名：档案优先，Bukkit 离线缓存兜底，最后 UUID 前 8 位（照 GuildService.nameOf） */
    private String nameOf(UUID id) {
        com.forgemaster.stats.StatsStore.Stats st = plugin.stats().peek(id);
        if (st != null && st.name != null && !st.name.isEmpty()) {
            return st.name;
        }
        org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(id);
        String n = off == null ? null : off.getName();
        return (n == null || n.isEmpty()) ? id.toString().substring(0, 8) : n;
    }

    // ------------------------------------------------------------ 存档（guilds.<id>.quest 段，由 GuildService.save 统一写盘）

    /** 把全部委托状态写进 yml（key 是 guilds.<id>.quest.*） */
    public synchronized void write(YamlConfiguration yml) {
        for (Map.Entry<String, QuestState> e : new LinkedHashMap<>(states).entrySet()) {
            QuestState q = e.getValue();
            if (q.type.isEmpty()) {
                continue; // 未初始化的状态不占盘
            }
            String base = "guilds." + e.getKey() + ".quest";
            yml.set(base + ".type", q.type);
            yml.set(base + ".target", q.target);
            yml.set(base + ".progress", q.progress);
            yml.set(base + ".period-start", q.periodStart);
            yml.set(base + ".completed", q.completed);
            yml.set(base + ".cycles", q.cycles);
            yml.set(base + ".claimed", new ArrayList<>(q.claimed));
            for (Map.Entry<String, Long> b : new LinkedHashMap<>(q.baseline).entrySet()) {
                yml.set(base + ".baseline." + b.getKey(), b.getValue());
            }
            for (Map.Entry<String, Integer> r : new LinkedHashMap<>(q.reports).entrySet()) {
                yml.set(base + ".reports." + r.getKey(), r.getValue());
            }
        }
    }

    /** 从 guild-data.yml 读回委托状态（坏数据跳过，不炸启动） */
    public synchronized void read(YamlConfiguration yml) {
        states.clear();
        ConfigurationSection root = yml.getConfigurationSection("guilds");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) {
                continue;
            }
            ConfigurationSection qs = sec.getConfigurationSection("quest");
            if (qs == null) {
                continue;
            }
            try {
                QuestState q = new QuestState();
                String type = qs.getString("type", "");
                q.type = "forge".equals(type) ? "forge"
                        : ("strengthen".equals(type) ? "strengthen" : "");
                if (q.type.isEmpty()) {
                    continue; // 未知类型当没建过，进服后刷新一轮
                }
                q.target = clampTarget(qs.getLong("target", 1));
                q.progress = Math.max(0, qs.getLong("progress", 0));
                q.periodStart = qs.getLong("period-start", 0);
                q.completed = qs.getBoolean("completed", false);
                q.cycles = Math.max(0, qs.getInt("cycles", 0));
                q.claimed.addAll(qs.getStringList("claimed"));
                ConfigurationSection bs = qs.getConfigurationSection("baseline");
                if (bs != null) {
                    for (String k : bs.getKeys(false)) {
                        q.baseline.put(k, bs.getLong(k, 0));
                    }
                }
                ConfigurationSection rs = qs.getConfigurationSection("reports");
                if (rs != null) {
                    for (String k : rs.getKeys(false)) {
                        q.reports.put(k, Math.max(0, rs.getInt(k, 0)));
                    }
                }
                states.put(id, q);
            } catch (Exception ignored) {
                // 坏数据跳过
            }
        }
    }
}
