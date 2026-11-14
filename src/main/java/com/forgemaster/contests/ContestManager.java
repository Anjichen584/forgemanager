package com.forgemaster.contests;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.econ.CoinReason;
import com.forgemaster.forge.ForgePhase;
import com.forgemaster.forge.ForgeSession;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.quality.Quality;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 锻造大赛：定时自动开赛，全员同剑胚同种子，实时排行榜，结束按名次发声望。
 */
public final class ContestManager {

    private final ForgingMasterPlugin plugin;
    private final Random random = new Random();

    private boolean running;
    private ForgeMaterial material;
    private long seed;
    private String contestId;
    private long endsAt;
    private long nextAt;

    /** 参赛者 → 分数（按提交顺序） */
    private final Map<UUID, Double> scores = new LinkedHashMap<>();
    private final Map<UUID, String> names = new LinkedHashMap<>();

    /** 交剑时的成品品质：观战面板显示「已完成 · 品质 ×× · 分数 ××」 */
    private final Map<UUID, Quality> qualities = new LinkedHashMap<>();

    /** 本场在锻造台前登记过的人（uuid → 名字），含还没交剑的；观战面板要给这类人显示当前阶段 */
    private final Map<UUID, String> entrants = new LinkedHashMap<>();

    private BossBar bar;
    private BukkitTask task;

    public ContestManager(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ 生命周期

    public void start() {
        stopTicker();
        if (!enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        long interval = Math.max(1, plugin.settings().contestIntervalMinutes()) * 60_000L;
        // 下次开赛：距上次整点间隔的下一个点
        nextAt = now + interval;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void shutdown() {
        try {
            // v2.25 修 P2（审计轮2-4）：reload / 关服时若大赛进行中，先走完整结算
            // （发声望 / 剑币 / 播报），否则交过剑的玩家分数凭空蒸发、BossBar 无声消失。
            if (running) {
                finish();
            }
        } finally {
            // finish() 会广播、发声望、查成就，任何一步抛异常都不能把下面这些留下没做完：
            // ticker 不取消会在关服后继续跑，档案实例不置空则本次 reload 白费。
            running = false;
            stopTicker();
            if (bar != null) {
                bar.removeAll();
                bar = null;
            }
            // 放在 finish 之后：archiveContest 需要旧实例把这一届落盘，先置空就把本届档案弄丢了。
            // reloadAll() 是 shutdown → start，只有在 shutdown 这侧丢弃实例，服主改的
            // contest.history-keep 才会被下一次 historyStore() 重新读取（见 reloadHistory 注释）。
            reloadHistory();
        }
    }

    private void stopTicker() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public boolean enabled() {
        return plugin.settings().contestEnabled();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (running) {
            updateBar();
            if (now >= endsAt) {
                finish();
            }
            return;
        }
        if (now >= nextAt) {
            // 在线人数不够就顺延，别让空场次刷屏
            int need = Math.max(1, plugin.settings().contestMinPlayers());
            if (Bukkit.getOnlinePlayers().size() < need) {
                nextAt = now + 60_000L;
                return;
            }
            openContest();
        }
    }

    // ------------------------------------------------------------ 开赛 / 结束

    /** 手动开赛（管理命令），返回提示 */
    public String forceStart(Player by) {
        if (running) {
            return "<red>大赛正在进行中。";
        }
        if (!enabled()) {
            // enabled=false 时 start() 不调度 ticker：强行开赛会变成没有结算的常驻 BossBar
            return "<red>大赛功能没开（<white>contest.enabled = false</white>），先改配置再 reload。";
        }
        openContest();
        return "<green>已强行开赛（" + (material == null ? "?" : material.display()) + "）。";
    }

    private void openContest() {
        List<ForgeMaterial> pool = new ArrayList<>(plugin.materials().all());
        if (pool.isEmpty()) {
            nextAt = System.currentTimeMillis() + 60_000L;
            return;
        }
        material = pool.get(random.nextInt(pool.size()));
        seed = random.nextLong();
        // 低位 24 位转 6 位十六进制：原来直接 substring(0,6)，
        // 种子落在 0xFFFFF 以内时字符串不足 6 位会抛 StringIndexOutOfBounds
        contestId = String.format("%06X", seed & 0xFFFFFFL);
        scores.clear();
        names.clear();
        qualities.clear();
        entrants.clear();
        running = true;
        endsAt = System.currentTimeMillis() + Math.max(1, plugin.settings().contestDurationSeconds()) * 1000L;

        bar = Bukkit.createBossBar(Msg.legacy(barTitle()), BarColor.YELLOW, BarStyle.SEGMENTED_20);
        for (Player p : Bukkit.getOnlinePlayers()) {
            bar.addPlayer(p);
        }

        String head = "<gradient:#ffd166:#ff7a18><bold>锻造大赛开始！</bold></gradient>";
        Bukkit.broadcast(Msg.mm(Msg.PREFIX + head));
        Bukkit.broadcast(Msg.mm("<dark_gray>        剑胚 <white>" + material.display()
                + "</white> · 种子 <white>" + contestId + "</white> · 限时 <white>"
                + plugin.settings().contestDurationSeconds() + " 秒</white>"));
        Bukkit.broadcast(Msg.mm("<dark_gray>        全员同一份火候，<white>/zhujian</white> 选该剑胚开锻，"
                + "出分即上榜。"));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.2f);
            p.showTitle(net.kyori.adventure.title.Title.title(
                    Msg.mm("<gradient:#ffd166:#ff7a18><bold>锻 造 大 赛</bold></gradient>"),
                    Msg.mm("<gray>" + material.display() + " · " + plugin.settings().contestDurationSeconds() + " 秒"),
                    net.kyori.adventure.title.Title.Times.times(
                            java.time.Duration.ofMillis(200),
                            java.time.Duration.ofMillis(1400),
                            java.time.Duration.ofMillis(400))));
        }
        nextAt = endsAt + Math.max(1, plugin.settings().contestIntervalMinutes()) * 60_000L;
    }

    private void updateBar() {
        if (bar == null) {
            return;
        }
        long left = Math.max(0, endsAt - System.currentTimeMillis());
        double progress = Math.max(0, Math.min(1, left / (double) (Math.max(1,
                plugin.settings().contestDurationSeconds()) * 1000L)));
        bar.setProgress(progress);
        bar.setColor(progress > 0.5 ? BarColor.GREEN : progress > 0.25 ? BarColor.YELLOW : BarColor.RED);
        bar.setTitle(Msg.legacy(barTitle()));
    }

    private String barTitle() {
        StringBuilder sb = new StringBuilder();
        sb.append("<white>大赛 <#D5B2DA>").append(contestId == null ? "" : contestId)
                .append("  ").append(material == null ? "" : material.display())
                .append("  <#ffd166>剩 ").append(Math.max(0, (endsAt - System.currentTimeMillis()) / 1000))
                .append("s  <dark_gray>|  ");
        List<Map.Entry<UUID, Double>> top = ranking();
        if (top.isEmpty()) {
            sb.append("<gray>还没人交剑");
        } else {
            for (int i = 0; i < Math.min(3, top.size()); i++) {
                if (i > 0) {
                    sb.append("<dark_gray>  ");
                }
                sb.append("<white>").append(i + 1).append('.').append(names.getOrDefault(top.get(i).getKey(), "?"))
                        .append(' ').append(String.format("%.1f", top.get(i).getValue()));
            }
        }
        return sb.toString();
    }

    /** 立即结束（管理命令 / 自检用） */
    public String finishNow() {
        if (!running) {
            return "<red>现在没有大赛在进行。";
        }
        finish();
        return "<green>已提前结束本场大赛。";
    }

    private void finish() {
        running = false;
        // 观战用的活表先清掉；榜单（scores/names）留到发奖之后再清
        qualities.clear();
        entrants.clear();
        if (bar != null) {
            bar.removeAll();
            bar = null;
        }
        List<Map.Entry<UUID, Double>> top = ranking();
        if (top.isEmpty()) {
            Bukkit.broadcast(Msg.mm(Msg.PREFIX + "<gray>大赛结束，无人交剑……下次加油。"));
            return;
        }

        int[] repByRank = {120, 70, 45};
        Bukkit.broadcast(Msg.mm(Msg.PREFIX + "<gradient:#ffd166:#ff7a18><bold>锻造大赛结束</bold></gradient> "
                + "<dark_gray>| 种子 " + contestId));
        int rank = 1;
        double lastScore = Double.NaN;
        int place = 0;
        for (Map.Entry<UUID, Double> e : top) {
            if (rank > 5) {
                break;
            }
            // 同分并列同名次：按列表序发奖会让同分的两个人拿不同的声望 / 剑币
            if (Double.compare(e.getValue(), lastScore) != 0) {
                place = rank;
                lastScore = e.getValue();
            }
            String name = names.getOrDefault(e.getKey(), "?");
            int rep = place <= repByRank.length ? repByRank[place - 1] : 12;
            plugin.stats().addReputation(e.getKey(), rep);
            // v2.0 剑币：大赛名次给币
            // v2.26.4（审计 #9b）：改走 Wallet.giveTo 收口——top5 可能离线，原来直发
            // 剑币账，server 模式下与其他 Wallet 支出两本账分裂
            long coinGain = plugin.coinRules().contestReward(place);
            plugin.wallet().giveTo(e.getKey(), name, coinGain, CoinReason.CONTEST);
            // 声望、剑币和名次奖励结算后立即判定成就；check 同时刷新匠衔。
            if (plugin.achievements() != null) {
                plugin.achievements().check(e.getKey(), "contest");
            }
            Bukkit.broadcast(Msg.mm("<dark_gray>        " + medal(place) + " <white>" + name
                    + "</white>  <#" + hex(place) + ">" + String.format("%.1f", e.getValue())
                    + "</#" + hex(place) + "> 分 <dark_gray>·</dark_gray> 声望 <white>+" + rep));
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) {
                p.playSound(p.getLocation(), place == 1 ? Sound.UI_TOAST_CHALLENGE_COMPLETE
                        : Sound.BLOCK_NOTE_BLOCK_BELL, 0.9f, 1.2f);
            }
            rank++;
        }
        if (top.size() > 5) {
            Bukkit.broadcast(Msg.mm("<dark_gray>        ……另有 " + (top.size() - 5) + " 位参赛者"));
        }
        archiveContest(top);
        scores.clear();
        names.clear();
    }

    // ------------------------------------------------------------ 历届档案（v2.26.0）

    /** 惰性建档案：构造期 settings 未必就绪，结算 / 查询时才初始化（只初始化一次） */
    private ContestHistoryStore historyStore;

    /** 上次归档落盘失败、这一届只活在内存里（重建实例前必须先补写，否则这届静默蒸发） */
    private boolean archivePending;

    /** 历届赛果档案（/zhujian contest history 的数据源） */
    public synchronized ContestHistoryStore historyStore() {
        if (historyStore == null) {
            historyStore = new ContestHistoryStore(plugin);
            historyStore.load();
        }
        return historyStore;
    }

    /**
     * 丢弃当前档案实例，下次 {@link #historyStore()} 按最新配置（{@code contest.history-keep}）重建并重新 load。
     *
     * <p>为什么 shutdown 必须置空而不是保留：{@code keep} 是构造期从 config 读死的 final 字段，
     * 而 {@code reloadAll()} 走的是 {@code shutdown() → start()}，不重建实例 = 服主改完保留届数
     * 永远不生效，直到重启服务器（与 v2.23.1 修的那批「周期只在构造期读一次」同型坑）。</p>
     *
     * <p>调用点必须在 {@code finish()} 之后：archiveContest 要用旧实例把这一届写进去。
     * 置空前只在 {@code archivePending} 时补一次 save —— 已成功落盘的档案内容不会再变，
     * 每次重载都整份重写是纯浪费（跟 record() 重复分支同一类毛病）。</p>
     */
    public synchronized void reloadHistory() {
        if (historyStore == null) {
            return; // 从没被用到过：没有内存态可丢，也不必碰磁盘
        }
        if (archivePending && !historyStore.save()) {
            plugin.getLogger().warning("[大赛] 重载档案前补写 contest-history.yml 失败："
                    + "内存里尚未落盘的赛果会随实例重建一起丢弃");
        }
        archivePending = false;
        historyStore = null;
    }

    /**
     * 结算归档：把本届赛果写进 {@link ContestHistoryStore}（只增不改，保留最近 N 届）。
     *
     * <p>只归档有人交剑的场次——冠军 / 分数是档案的核心字段，空场次归档只会刷屏。
     * 落盘时机选「结算点直接 save 并判返回值」：赛果单次不可再生，异步冲刷的崩溃
     * 窗口会永久丢一届；一场一次同步原子写代价可接受。写盘失败打 SEVERE 留痕，
     * 内存里这届仍在，下一场结算会连同补写。</p>
     */
    private void archiveContest(List<Map.Entry<UUID, Double>> top) {
        try {
            ContestHistoryStore.Entry e = new ContestHistoryStore.Entry();
            e.code = contestId == null ? "?" : contestId;
            e.at = System.currentTimeMillis();
            e.material = material == null ? "?" : material.display();
            e.winner = names.getOrDefault(top.get(0).getKey(), "?");
            e.winnerScore = top.get(0).getValue();
            e.players = scores.size();
            for (int i = 0; i < Math.min(5, top.size()); i++) {
                e.top.add((i + 1) + ". " + names.getOrDefault(top.get(i).getKey(), "?")
                        + ":" + ContestHistoryStore.fmtScore(top.get(i).getValue()));
            }
            // 先问一句在不在档：record() 的「已在档」分支是纯内存判断、不写盘，
            // 只有真新增那次才是整份重建（save 覆盖全部条目 = 连带把之前落盘失败的也补上）。
            // 实例只取一次：万一 reloadHistory() 在另一线程把字段置空，前后两次 historyStore()
            // 会落到两个不同实例上，这份档案就写进了马上要被丢弃的那一份。
            ContestHistoryStore store = historyStore();
            boolean known = store.byCode(e.code) != null;
            if (!store.record(e)) {
                // 只有「本次新增写盘失败」会走到这里（已在档恒 true），
                // 所以这条日志可以放心说「本届暂存内存」；置上标记让 reloadHistory 换实例前补写。
                archivePending = true;
                plugin.getLogger().severe("[大赛] 赛果归档落盘失败（contest-history.yml）：本届 "
                        + e.code + " 暂存内存，下一场结算会连带补写");
            } else if (!known) {
                // 新增那次写盘成功 = 整份档案已完整落盘，之前积压的待补写也随之兑现
                archivePending = false;
            }
        } catch (RuntimeException ex) {
            // 归档失败绝不能打断发奖主流程
            plugin.getLogger().warning("[大赛] 赛果归档异常：" + ex);
        }
    }

    private String medal(int rank) {
        return switch (rank) {
            case 1 -> "<#ffd166>①</#ffd166>";
            case 2 -> "<#dddddd>②</#dddddd>";
            case 3 -> "<#c98a4b>③</#c98a4b>";
            default -> "<dark_gray>" + rank + "</dark_gray>";
        };
    }

    private String hex(int rank) {
        return switch (rank) {
            case 1 -> "ffd166";
            case 2 -> "dddddd";
            case 3 -> "c98a4b";
            default -> "9e9e9e";
        };
    }

    // ------------------------------------------------------------ 参赛

    /** 会话开局时：大赛期间只能打指定剑胚、共享种子 */
    public void bindSession(ForgeSession session) {
        if (!running) {
            return;
        }
        // 只要在本场期间开炉就算参赛者，观战面板会给他留一行显示当前阶段
        entrants.put(session.uuid(), session.playerName());
        session.seed(seed);
        if (session.material() != null && material != null
                && !material.id().equalsIgnoreCase(session.material().id())) {
            Player p = session.player();
            if (p != null) {
                Msg.prefix(p, "<red>大赛指定剑胚是 <white>" + material.display() + "</white>。");
            }
        }
    }

    /** 出分上榜 */
    public void report(ForgeSession s) {
        if (!running) {
            return;
        }
        Player p = s.player();
        UUID id = s.uuid();
        double score = s.finalScore();
        Double old = scores.get(id);
        if (old != null && old >= score) {
            if (p != null) {
                Msg.prefix(p, "<gray>这次 <white>" + Msg.fmt(score, 1)
                        + "</white> 分，没超过你之前的 <white>" + Msg.fmt(old, 1) + "</white>。");
            }
            return;
        }
        scores.put(id, score);
        names.put(id, s.playerName());
        qualities.put(id, s.quality());
        if (p != null) {
            int rank = rankOf(id);
            Msg.prefix(p, "<green>上榜！</green> <white>" + Msg.fmt(score, 1) + "</white> 分 <dark_gray>·</dark_gray> "
                    + "当前第 <white>" + rank + "</white> 名 <dark_gray>(共 " + scores.size() + " 人)</dark_gray>");
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.6f);
        }
        updateBar();
    }

    private int rankOf(UUID id) {
        List<Map.Entry<UUID, Double>> top = ranking();
        for (int i = 0; i < top.size(); i++) {
            if (top.get(i).getKey().equals(id)) {
                return i + 1;
            }
        }
        return top.size();
    }

    private List<Map.Entry<UUID, Double>> ranking() {
        List<Map.Entry<UUID, Double>> list = new ArrayList<>(scores.entrySet());
        list.sort(Comparator.comparingDouble((Map.Entry<UUID, Double> e) -> e.getValue()).reversed());
        return list;
    }

    // ------------------------------------------------------------ 查询

    public boolean running() {
        return running;
    }

    public String statusLine() {
        if (!enabled()) {
            return "<gray>大赛功能已关闭。";
        }
        if (running) {
            long left = Math.max(0, (endsAt - System.currentTimeMillis()) / 1000);
            return "<#ffd166>大赛进行中</#ffd166> <gray>· 剑胚 <white>"
                    + (material == null ? "?" : material.display()) + "</white> · 种子 <white>"
                    + contestId + "</white> · 剩 <white>" + left + " 秒</white> · 参赛 <white>"
                    + scores.size() + "</white> 人";
        }
        long left = Math.max(0, (nextAt - System.currentTimeMillis()) / 1000);
        return "<gray>下一场约 <white>" + (left / 60) + "</white> 分 <white>" + (left % 60)
                + "</white> 秒后开始（间隔 " + plugin.settings().contestIntervalMinutes() + " 分钟）";
    }

    public List<String> leaderboard() {
        List<String> out = new ArrayList<>();
        int i = 1;
        for (Map.Entry<UUID, Double> e : ranking()) {
            out.add("<dark_gray>" + i + ". <white>" + names.getOrDefault(e.getKey(), "?")
                    + " <gray>" + Msg.fmt(e.getValue(), 1));
            i++;
        }
        return out;
    }

    public Material_ materialAsking() {
        return material == null ? null : new Material_(material.id(), material.display());
    }

    // ------------------------------------------------------------ 观战快照
    // 全是只读查询，给 ContestWatchGui 每秒拉一次；不改任何已有方法签名。

    /**
     * 观战面板的一行。
     *
     * @param rank      名次，1 起；还没交剑的给 0
     * @param quality   交剑时的成品品质；没交剑为 null
     * @param submitted 已经交剑上榜
     * @param phase     正在锻造时的阶段；不在锻造台前为 null
     */
    public record Contestant(UUID id, String name, double score, int rank,
                             Quality quality, boolean submitted, ForgePhase phase) {

        /** 还在锻造台前（还没交剑，但也没走） */
        public boolean forging() {
            return !submitted && phase != null;
        }
    }

    /**
     * 本场参赛者快照：已交剑的按名次在前，正在锻打的按「在打 → 已离场」、再按名字排在后面。
     *
     * <p>没开赛时返回空表，观战面板据此显示空状态。</p>
     */
    public List<Contestant> contestants() {
        List<Contestant> out = new ArrayList<>();
        List<Map.Entry<UUID, Double>> top = ranking();
        for (int i = 0; i < top.size(); i++) {
            Map.Entry<UUID, Double> e = top.get(i);
            out.add(new Contestant(e.getKey(), names.getOrDefault(e.getKey(), "?"),
                    e.getValue(), i + 1, qualities.get(e.getKey()), true, null));
        }

        List<Contestant> rest = new ArrayList<>();
        for (Map.Entry<UUID, String> e : entrants.entrySet()) {
            UUID id = e.getKey();
            if (scores.containsKey(id)) {
                continue; // 已交剑的人上面那一圈已经出过了
            }
            ForgeSession s = liveSession(id);
            rest.add(new Contestant(id, e.getValue(), 0.0, 0,
                    s == null ? null : s.quality(), false, s == null ? null : s.phase()));
        }
        rest.sort(Comparator.comparingInt((Contestant c) -> c.forging() ? 0 : 1)
                .thenComparing(c -> c.name() == null ? "" : c.name(), String.CASE_INSENSITIVE_ORDER));
        out.addAll(rest);
        return out;
    }

    /** 当前在锻造台前的会话；人不在线 / 没在锻造返回 null */
    private ForgeSession liveSession(UUID id) {
        if (id == null || plugin.forge() == null) {
            return null;
        }
        Player p = Bukkit.getPlayer(id);
        return p == null ? null : plugin.forge().session(p);
    }

    /**
     * 参赛人数 = 已交剑 ∪ 在本场开过炉的（跟观战面板的行数一一对应，半路走了的也算）。
     */
    public int participantCount() {
        int n = scores.size();
        for (UUID id : entrants.keySet()) {
            if (!scores.containsKey(id)) {
                n++;
            }
        }
        return n;
    }

    /** 还在锻造台前、没交剑的人数 */
    public int activeCount() {
        int n = 0;
        for (UUID id : entrants.keySet()) {
            if (!scores.containsKey(id) && liveSession(id) != null) {
                n++;
            }
        }
        return n;
    }

    /** 已交剑人数 */
    public int submittedCount() {
        return scores.size();
    }

    /** 当前领先者名字；还没人交剑返回 null */
    public String leaderName() {
        List<Map.Entry<UUID, Double>> top = ranking();
        return top.isEmpty() ? null : names.getOrDefault(top.get(0).getKey(), "?");
    }

    /** 当前领先者的分数；还没人交剑返回 0 */
    public double leaderScore() {
        List<Map.Entry<UUID, Double>> top = ranking();
        return top.isEmpty() ? 0.0 : top.get(0).getValue();
    }

    /** 剩余毫秒；没开赛返回 0 */
    public long remainingMillis() {
        return running ? Math.max(0, endsAt - System.currentTimeMillis()) : 0;
    }

    /** 本场种子号；没开赛返回 null */
    public String contestCode() {
        return contestId;
    }

    /** 交剑时记下的成品品质；没交剑返回 null */
    public Quality qualityOf(UUID id) {
        return id == null ? null : qualities.get(id);
    }

    /** 是不是本场参赛者（已交剑或在锻造台前登记过） */
    public boolean isEntrant(UUID id) {
        return id != null && (scores.containsKey(id) || entrants.containsKey(id));
    }

    /** 给命令层用的轻量携带体 */
    public record Material_(String id, String display) {
    }
}