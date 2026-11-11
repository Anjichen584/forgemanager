package com.forgemaster.duel;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.forge.ForgeSession;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.stats.StatsStore;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 双人比试：同剑胚、同种子，各锻一把，比总分。
 */
public final class DuelManager {

    public static final class Match {
        public final String id;
        public final UUID a;
        public final UUID b;
        public final String aName;
        public final String bName;
        public final ForgeMaterial material;
        public final long seed;
        public final long createdAt = System.currentTimeMillis();

        public Double aScore;
        public Double bScore;

        /** 主手武器锻造等级：发起邀请时记录，应战时取「两次里较大的那个」防换剑绕判定 */
        public int aLevel;
        public int bLevel;
        /** 对应的档位序号（0 起） */
        public int aTier;
        public int bTier;
        /** 本局压档的目标等级；null = 本局没压档（同档 / 关掉了平衡） */
        public Integer balanceLevel;
        /** 应战时刻；null = 还在邀请期（邀请期超时由 inviteAt 清理） */
        public Long acceptedAt;

        Match(String id, Player a, Player b, ForgeMaterial material, long seed) {
            this.id = id;
            this.a = a.getUniqueId();
            this.b = b.getUniqueId();
            this.aName = a.getName();
            this.bName = b.getName();
            this.material = material;
            this.seed = seed;
        }

        public boolean involves(UUID uuid) {
            return a.equals(uuid) || b.equals(uuid);
        }

        public UUID opponentOf(UUID uuid) {
            return a.equals(uuid) ? b : a;
        }

        public boolean ready() {
            return aScore != null && bScore != null;
        }
    }

    /** 邀请：target -> matchId */
    private final Map<UUID, String> invites = new HashMap<>();
    private final Map<UUID, Long> inviteAt = new HashMap<>();
    private final Map<String, Match> matches = new HashMap<>();
    private final ForgingMasterPlugin plugin;
    private final Random random = new Random();
    /** 锻造等级分档（只读配置 + 算档位） */
    private final DuelTiers tiers;
    /** 跨档压档：临时属性修正的施加与还原 */
    private final DuelBalance balance;

    public DuelManager(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.tiers = new DuelTiers(plugin);
        this.balance = new DuelBalance(plugin);
        // 临时压档必须在「玩家退出 / 插件停用」时还原，这里自己挂监听，
        // 不依赖 ForgingMasterPlugin 的注册表（那个文件不归本模块改）
        Bukkit.getPluginManager().registerEvents(new DuelTierListener(plugin, this, balance), plugin);
        // v2.25 修 P2（审计轮2-5）：「应战后 30 分钟强制收场」原本只在
        // activeMatchOf 里顺带清 —— 双方 AFK 时没人再交互，锁永远不解。
        // 挂一个 60s 的轻量心跳兜底（空表扫一遍，成本可忽略）。
        Bukkit.getScheduler().runTaskTimer(plugin, this::cleanupExpiredInvites,
                20L * 60, 20L * 60);
    }

    public boolean enabled() {
        return plugin.settings().duelEnabled();
    }

    /** 发起挑战，返回提示文本 */
    public String challenge(Player from, Player target) {
        if (!enabled()) {
            return "<red>比试功能被关掉了。</red>";
        }
        if (from.equals(target)) {
            return "<red>自己跟自己比不了。</red>";
        }
        Match existing = activeMatchOf(from.getUniqueId());
        if (existing != null) {
            return "<red>你还在比试中，先打完这一场。</red>";
        }
        if (activeMatchOf(target.getUniqueId()) != null) {
            return "<red>" + target.getName() + " 正在比试中。</red>";
        }
        // ---- 锻造等级分档：默认只允许同档开打 ----
        DuelTiers.Snapshot aSnap = tiers.of(from);
        DuelTiers.Snapshot bSnap = tiers.of(target);
        if (tiers.enabled() && !tiers.crossAllowed() && aSnap.tier() != bSnap.tier()) {
            return "<red>对方档位 <white>" + bSnap.label() + "</white>，你是 <white>"
                    + aSnap.label() + "</white>。</red>"
                    + "<newline><dark_gray>跨档比试没开：主手武器先锻到同一档再来"
                    + "（你 +" + aSnap.level() + " · 对方 +" + bSnap.level() + "）。";
        }
        List<ForgeMaterial> pool = new ArrayList<>(plugin.materials().all());
        if (pool.isEmpty()) {
            return "<red>没有可用的剑胚。</red>";
        }
        ForgeMaterial mat = pool.get(random.nextInt(pool.size()));
        long seed = random.nextLong();
        String id = Long.toHexString(seed).substring(0, 6).toUpperCase();

        Match match = new Match(id, from, target, mat, seed);
        match.aLevel = aSnap.level();
        match.bLevel = bSnap.level();
        match.aTier = aSnap.tier();
        match.bTier = bSnap.tier();
        matches.put(id, match);
        invites.put(target.getUniqueId(), id);
        inviteAt.put(target.getUniqueId(), System.currentTimeMillis());

        target.sendMessage(Msg.mm(Msg.PREFIX + "<gray><white>" + from.getName()
                + "</white> 向你发起锻造比试（<white>" + mat.display() + "</white>，同一份材料同样火候）。"));
        target.sendMessage(Msg.mm("<dark_gray>  → 输入 <white>/zhujian duel accept</white> 应战，"
                + "或 <white>/zhujian duel deny</white> 拒绝（60 秒内有效）"));
        target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, 1.6f);
        return "<green>已向 <white>" + target.getName() + "</white> 发起比试，等对方应战。</green>";
    }

    /**
     * 没人应战的邀请 60 秒过期；应战后 N 分钟没出分的比试也强制收场。
     *
     * <p>challenge() 在应战前就把 Match 放进了 {@code matches}，而 {@link #activeMatchOf}
     * 用它锁「正在比试」——不清理的话，对方一直不响应时双方就被永久锁死
     * （发不了新比试，锻造还被锁到指定剑胚），只能重进服务器。应战之后的
     * Match 此前没有任何超时兜底（accept 时已从 inviteAt 移除），这里一并扫。</p>
     */
    private void cleanupExpiredInvites() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> e : new HashMap<>(inviteAt).entrySet()) {
            if (now - e.getValue() > 60_000L) {
                String id = invites.remove(e.getKey());
                inviteAt.remove(e.getKey());
                if (id != null) {
                    Match m = matches.remove(id);
                    if (m != null) {
                        releaseBalance(m);
                    }
                }
            }
        }
        // 应战后 30 分钟无人交卷：强制收场，把双方放出来
        for (Match m : new ArrayList<>(matches.values())) {
            if (m.acceptedAt == null || m.ready()) {
                continue; // 邀请期的归上面管；已出完分的归 report() 管
            }
            if (now - m.acceptedAt > 30 * 60_000L) {
                matches.remove(m.id);
                releaseBalance(m);
                for (UUID uid : new UUID[]{m.a, m.b}) {
                    Player p = Bukkit.getPlayer(uid);
                    if (p != null) {
                        p.sendMessage(Msg.mm(Msg.PREFIX
                                + "<gray>这场比试超时没打完（30 分钟），已收场，锻造解锁了。"));
                    }
                }
            }
        }
    }

    /** 应战，返回提示文本 */
    public String accept(Player target) {
        String id = invites.remove(target.getUniqueId());
        inviteAt.remove(target.getUniqueId());
        if (id == null) {
            return "<red>没有待应战的邀请。</red>";
        }
        Match match = matches.get(id);
        if (match == null) {
            return "<red>这场比试已经过期。</red>";
        }
        Player a = Bukkit.getPlayer(match.a);
        if (a == null) {
            matches.remove(id);
            releaseBalance(match);
            return "<red>对手已经下线了。</red>";
        }

        // 邀请期间双方都可能换过武器：等级取「发起时」与「应战时」的较大值，
        // 临时换把低级剑不能把档位降下来绕过判定。
        DuelTiers.Snapshot aNow = tiers.of(a);
        DuelTiers.Snapshot bNow = tiers.of(target);
        int aLevel = Math.max(match.aLevel, aNow.level());
        int bLevel = Math.max(match.bLevel, bNow.level());
        int aTier = tiers.tierOf(aLevel);
        int bTier = tiers.tierOf(bLevel);
        match.aLevel = aLevel;
        match.bLevel = bLevel;
        match.aTier = aTier;
        match.bTier = bTier;

        if (tiers.enabled() && !tiers.crossAllowed() && aTier != bTier) {
            matches.remove(id);
            releaseBalance(match);
            String why = "<red>档位不符，比试取消：对方档位 <white>" + tiers.labelOf(bTier)
                    + "</white>，你是 <white>" + tiers.labelOf(aTier) + "</white>。</red>";
            a.sendMessage(Msg.mm(Msg.PREFIX + why));
            return why;
        }

        // ---- 跨档强制平衡：把高等级方的强化加成临时压到低等级方 ----
        if (tiers.enabled() && tiers.crossAllowed() && tiers.balanceEnabled() && aTier != bTier) {
            Player high = aLevel > bLevel ? a : target;
            DuelBalance.Applied fixed = balance.apply(high, Math.max(aLevel, bLevel),
                    Math.min(aLevel, bLevel));
            if (fixed != null) {
                match.balanceLevel = fixed.toLevel();
                for (Player p : List.of(a, target)) {
                    p.sendMessage(Msg.mm(Msg.PREFIX + "<gray>本局按 <white>+" + match.balanceLevel
                            + "</white> 档平衡：<white>" + high.getName()
                            + "</white> 的强化加成临时压到这一档"
                            + " <dark_gray>（不动物品，打完 / 退出自动还原）"));
                }
            }
        }

        // 应战时刻：之后由 cleanupExpiredInvites 兜底「应战了却一直不打」的比试，
        // 否则一方 AFK 不下线就把双方永久锁死（audit/02-stability.md #1）
        match.acceptedAt = System.currentTimeMillis();

        // 通知双方开锻
        for (Player p : List.of(a, target)) {
            Player other = p.equals(a) ? target : a;
            p.sendMessage(Msg.mm(Msg.PREFIX + "<gray>比试开始：<white>" + match.material.display()
                    + "</white> · 对手 <white>" + other.getName() + "</white> · 请各自 <white>/zhujian"
                    + "</white> 选同一剑胚动手。"));
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.4f, 1.4f);
        }
        return null;
    }

    public String deny(Player target) {
        String id = invites.remove(target.getUniqueId());
        inviteAt.remove(target.getUniqueId());
        if (id == null) {
            return "<gray>没有待处理的邀请。</gray>";
        }
        Match match = matches.remove(id);
        if (match != null) {
            Player a = Bukkit.getPlayer(match.a);
            if (a != null) {
                a.sendMessage(Msg.mm(Msg.PREFIX + "<red>" + target.getName() + " 拒绝了比试。</red>"));
            }
        }
        return "<gray>已拒绝。</gray>";
    }

    /** 玩家开局时若在比试中，绑定 matchId + 强制同一剑胚与种子 */
    public Match activeMatchOf(UUID uuid) {
        cleanupExpiredInvites();
        for (Match m : matches.values()) {
            if (m.involves(uuid) && !m.ready()) {
                return m;
            }
        }
        return null;
    }

    /** 开局前校验：比试中只能按指定剑胚打造 */
    public Match bindSession(ForgeSession session) {
        Match m = activeMatchOf(session.uuid());
        if (m == null) {
            return null;
        }
        session.duelId(m.id);
        session.seed(m.seed);
        return m;
    }

    /**
     * 一方出分。
     *
     * @return 结算播报（还没打完返回 null）
     */
    public String report(ForgeSession session) {
        if (session.duelId() == null) {
            return null;
        }
        Match m = matches.get(session.duelId());
        if (m == null) {
            return null;
        }
        if (m.a.equals(session.uuid())) {
            m.aScore = session.finalScore();
        } else {
            m.bScore = session.finalScore();
        }
        if (!m.ready()) {
            Player p = Bukkit.getPlayer(session.uuid());
            if (p != null) {
                p.sendMessage(Msg.mm(Msg.PREFIX + "<gray>你这把 <white>" + Msg.fmt(session.finalScore(), 1)
                        + "</white> 分，等对手出分……"));
            }
            return null;
        }
        matches.remove(m.id);
        return settle(m);
    }

    private String settle(Match m) {
        // 比试结束：先把跨档压档还原，再结算——不要把负属性修正带出对局
        releaseBalance(m);
        double a = m.aScore == null ? 0 : m.aScore;
        double b = m.bScore == null ? 0 : m.bScore;
        Player pa = Bukkit.getPlayer(m.a);
        Player pb = Bukkit.getPlayer(m.b);

        boolean draw = Math.abs(a - b) < 0.05;
        UUID winner = draw ? null : (a > b ? m.a : m.b);
        int winnerRep = 25;
        // 负方 / 平局不给声望：参与奖会让两个小号互刷比试白嫖段位（赢 25 + 输 6 = 每场 31 点）
        int loserRep = 0;

        if (pa != null) {
            plugin.stats().countDuel(pa.getUniqueId(), !draw && winner != null && winner.equals(m.a));
            plugin.stats().addReputation(pa.getUniqueId(), !draw && winner != null && winner.equals(m.a)
                    ? winnerRep : loserRep);
            pa.playSound(pa.getLocation(), draw ? Sound.BLOCK_NOTE_BLOCK_BELL : Sound.UI_TOAST_CHALLENGE_COMPLETE,
                    0.9f, 1.2f);
        }
        if (pb != null) {
            plugin.stats().countDuel(pb.getUniqueId(), !draw && winner != null && winner.equals(m.b));
            plugin.stats().addReputation(pb.getUniqueId(), !draw && winner != null && winner.equals(m.b)
                    ? winnerRep : loserRep);
            pb.playSound(pb.getLocation(), draw ? Sound.BLOCK_NOTE_BLOCK_BELL : Sound.UI_TOAST_CHALLENGE_COMPLETE,
                    0.9f, 1.2f);
        }
        if (plugin.ranks() != null) {
            if (pa != null) {
                plugin.ranks().refresh(pa.getUniqueId());
            }
            if (pb != null) {
                plugin.ranks().refresh(pb.getUniqueId());
            }
        }
        // v2.23 拓展 C：比试分胜负 → Webhook（平局不播，没有「胜者」可报）
        if (winner != null) {
            String winnerName = winner.equals(m.a) ? m.aName : m.bName;
            plugin.broadcast(com.forgemaster.webhook.WebhookEvent.DUEL_WIN,
                    "【比试】" + winnerName + " 获胜（"
                            + String.format(java.util.Locale.ROOT, "%.1f", Math.max(a, b)) + " : "
                            + String.format(java.util.Locale.ROOT, "%.1f", Math.min(a, b)) + "）",
                    java.util.Map.of("winner", winnerName,
                            "score", String.format(java.util.Locale.ROOT, "%.1f", Math.max(a, b))));
        }
        if (plugin.achievements() != null) {
            if (pa != null) {
                plugin.achievements().check(pa.getUniqueId(), "duel");
            }
            if (pb != null) {
                plugin.achievements().check(pb.getUniqueId(), "duel");
            }
        }
        if (!draw && winner != null && plugin.seasonPass() != null) plugin.seasonPass().earn(winner, "duel-win"); // 赛季通行证：比试胜利登记（report → settle 结算处）

        String head = draw
                ? "平分秋色！"
                : (winner.equals(m.a) ? m.aName : m.bName) + " 技高一筹！";
        String body = "<gray>" + m.aName + " <white>" + Msg.fmt(a, 1) + "</white>  vs  <white>"
                + Msg.fmt(b, 1) + "</white> " + m.bName
                + " <dark_gray>| " + m.material.display() + " 种子 " + m.id;
        plugin.getServer().broadcast(Msg.mm(Msg.PREFIX + "<gradient:#ffd166:#ff7a18><bold>比试结束 · "
                + head + "</bold></gradient>"));
        plugin.getServer().broadcast(Msg.mm("<dark_gray>        " + body));
        return head;
    }

    /** 玩家退出时清理邀请与比试 */
    public void onQuit(UUID uuid) {
        invites.remove(uuid);
        // 下线 = 对局中断：自己那份临时压档立刻还原
        balance.clear(uuid);
        for (Match m : new ArrayList<>(matches.values())) {
            if (m.involves(uuid)) {
                matches.remove(m.id);
                // 对局取消，对手的压档也一并还原
                releaseBalance(m);
                UUID other = m.opponentOf(uuid);
                Player o = Bukkit.getPlayer(other);
                if (o != null) {
                    o.sendMessage(Msg.mm(Msg.PREFIX + "<red>对手下线了，比试取消。</red>"));
                }
            }
        }
    }

    /**
     * 插件停用 / 关服：还原所有临时压档并丢掉进行中的对局。
     *
     * <p>由 {@link DuelTierListener} 在 {@code PluginDisableEvent} 里调用；
     * 另外 transient modifier 本身不进存档，这里只是把在线玩家的属性立刻恢复干净。</p>
     */
    public void shutdown() {
        for (Match m : matches.values()) {
            releaseBalance(m);
        }
        matches.clear();
        invites.clear();
        balance.clearAll();
    }

    /** 还原一场比试可能施加的临时属性修正（幂等） */
    private void releaseBalance(Match m) {
        if (m == null) {
            return;
        }
        balance.clear(m.a);
        balance.clear(m.b);
        m.balanceLevel = null;
    }

    public int activeCount() {
        return matches.size();
    }

    public StatsStore stats() {
        return plugin.stats();
    }

    /** 分档规则（只读），自检 / 别的模块查档位用 */
    public DuelTiers tiers() {
        return tiers;
    }

    /** 临时压档服务，自检时用来断言「应用了几条、有没有还原干净」 */
    public DuelBalance balanceService() {
        return balance;
    }
}