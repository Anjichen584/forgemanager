package com.forgemaster.mentor;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.econ.CoinRules;
import com.forgemaster.econ.CoinReason;
import com.forgemaster.quality.Quality;
import com.forgemaster.stats.StatsStore;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * v2.0 师徒系统。
 *
 * <p>规则：</p>
 * <ul>
 *   <li><b>拜师</b>：徒弟执行 {@code /zhujian master <玩家>}，对方执行 {@code /zhujian accept} 收徒。
 *       邀请 {@code mentor.invite-expire-seconds} 秒内有效；对方须在线。</li>
 *   <li><b>名额</b>：一人只能有一个师父；师父的徒弟数上限 {@code mentor.max-disciples}。
 *       禁止形成循环师承链（A 的师父链回到 A 时拒绝）。</li>
 *   <li><b>解除</b>：徒弟 {@code /zhujian master leave}；师父 {@code /zhujian master expel <徒弟>}。
 *       解除后徒弟进入 {@code mentor.unbind-cooldown-hours} 小时冷却，冷却期内不能再拜师。</li>
 *   <li><b>剑币分成</b>：徒弟每锻成一把剑，师父得一份剑币 = 徒弟本次锻造剑币（CoinRules）× {@code mentor.coin-share-rate}。
 *       只在徒弟锻造时结算；<b>师父离线也照常入账</b>（数据按 UUID 存在 StatsStore 里），只要仍是绑定关系即可。</li>
 *   <li><b>徒弟加成</b>：徒弟锻造时，若师父<b>在线</b>，徒弟的高温容差额外 +{@code mentor.apprentice-tolerance-bonus}
 *       （例如 0.05 = +5%，叠加在专精容差之上）。师父离线则无加成。</li>
 *   <li><b>离线 / 删号</b>：所有查询都用 UUID，不要求在线；找不到玩家名时显示 UUID 前 8 位，不抛异常。</li>
 * </ul>
 *
 * <p>数据持久化：StatsStore 的 {@code mentor}（徒弟侧，师父 UUID 字符串）、
 * {@code disciples}（师父侧，徒弟 UUID 字符串列表）、{@code mentor-cooldown-at}（徒弟侧冷却时间戳）。</p>
 */
public final class MentorManager {

    /** 循环检查时最多沿师承链往上走的层数，防止数据异常死循环 */
    private static final int MAX_DEPTH = 32;

    private final ForgingMasterPlugin plugin;
    /** 待处理邀请：key = 师父（被邀请方）UUID；仅内存，重启即失效 */
    private final Map<UUID, Invite> invites = new HashMap<>();

    private record Invite(UUID apprentice, long expiresAt) {
    }

    public MentorManager(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ 配置

    public boolean enabled() {
        return plugin.settings().flag("mentor.enabled", true);
    }

    private int maxDisciples() {
        return Math.max(1, plugin.settings().intOf("mentor.max-disciples", 3));
    }

    private long inviteExpireMs() {
        return Math.max(10, plugin.settings().intOf("mentor.invite-expire-seconds", 60)) * 1000L;
    }

    private double cooldownHours() {
        return Math.max(0.0, plugin.settings().num("mentor.unbind-cooldown-hours", 24));
    }

    private long cooldownMs() {
        return (long) (cooldownHours() * 3_600_000L);
    }

    private double coinShareRate() {
        return clamp(plugin.settings().num("mentor.coin-share-rate", 0.10), 0.0, 1.0);
    }

    private double apprenticeBonusRate() {
        return clamp(plugin.settings().num("mentor.apprentice-tolerance-bonus", 0.05), 0.0, 0.5);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private StatsStore stats() {
        return plugin.stats();
    }

    // ------------------------------------------------------------ 命令入口

    /** /zhujian master ... */
    public void handle(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>拜师要在游戏里操作。</red>");
            return;
        }
        if (!enabled()) {
            Msg.prefix(p, "<red>师徒系统没开。</red>");
            return;
        }
        if (args.length < 2) {
            status(p);
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status", "info" -> status(p);
            case "leave", "unbind" -> leave(p);
            case "expel", "kick" -> {
                if (args.length < 3) {
                    Msg.prefix(p, "<gray>用法：<white>/zhujian master expel 徒弟名</white></gray>");
                } else {
                    expel(p, args[2]);
                }
            }
            default -> invite(p, args[1]);
        }
    }

    /** /zhujian accept —— 收下待处理的拜师邀请 */
    public void accept(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>收徒要在游戏里操作。</red>");
            return;
        }
        if (!enabled()) {
            Msg.prefix(p, "<red>师徒系统没开。</red>");
            return;
        }
        Invite inv = invites.remove(p.getUniqueId());
        if (inv == null) {
            Msg.prefix(p, "<gray>你现在没有待处理的拜师邀请。</gray>");
            return;
        }
        if (System.currentTimeMillis() > inv.expiresAt()) {
            Msg.prefix(p, "<gray>拜师邀请已过期。</gray>");
            return;
        }
        Player apprentice = Bukkit.getPlayer(inv.apprentice());
        if (apprentice == null) {
            Msg.prefix(p, "<gray>对方已经下线，邀请作废。</gray>");
            return;
        }
        String err = canBind(inv.apprentice(), p.getUniqueId());
        if (err != null) {
            Msg.prefix(p, "<red>" + err + "</red>");
            return;
        }
        bind(inv.apprentice(), p.getUniqueId());
        // 师徒关系在绑定成功的当下即可达成成就，无需等待下一次锻造。
        if (plugin.achievements() != null) {
            plugin.achievements().check(inv.apprentice(), "mentor");
            plugin.achievements().check(p.getUniqueId(), "mentor");
        }
        Msg.prefix(p, "<green>你收下了 <white>" + apprentice.getName() + "</white> 为徒。</green>");
        Msg.prefix(apprentice, "<green>" + p.getName() + " 收你为徒！你锻造时他的容差会给你加成，"
                + "你每锻成一把剑，他都能分到一份剑币。</green>");
    }

    // ------------------------------------------------------------ 拜师 / 收徒 / 解除

    private void invite(Player p, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            Msg.prefix(p, "<red>找不到在线玩家 " + targetName + "。</red>");
            return;
        }
        String err = canBind(p.getUniqueId(), target.getUniqueId());
        if (err != null) {
            Msg.prefix(p, "<red>" + err + "</red>");
            return;
        }
        long sec = inviteExpireMs() / 1000L;
        invites.put(target.getUniqueId(), new Invite(p.getUniqueId(), System.currentTimeMillis() + inviteExpireMs()));
        Msg.prefix(p, "<gray>已向 <white>" + target.getName() + "</white> 发出拜师邀请（" + sec + " 秒内有效）。</gray>");
        Msg.prefix(target, "<white>" + p.getName() + "</white> <gray>想拜你为师。输入 </gray>"
                + "<white>/zhujian accept</white><gray> 收徒（" + sec + " 秒内有效）。</gray>");
    }

    /**
     * 能否建立 apprentice → master 的师徒关系。
     *
     * @return null 表示可以；否则返回给玩家看的中文原因
     */
    private String canBind(UUID apprentice, UUID master) {
        if (apprentice.equals(master)) {
            return "不能拜自己为师。";
        }
        if (!stats().mentorOf(apprentice).isEmpty()) {
            return "你已经有师父了，先用 /zhujian master leave 解除。";
        }
        long cd = cooldownMs();
        long last = stats().mentorCooldownAt(apprentice);
        long now = System.currentTimeMillis();
        if (cd > 0 && last > 0 && now - last < cd) {
            return "解除师徒关系后需要冷却，还剩 " + minutesLeft(cd - (now - last)) + " 分钟。";
        }
        if (stats().discipleIds(master).size() >= maxDisciples()) {
            return "对方的徒弟已满（上限 " + maxDisciples() + " 人）。";
        }
        // 循环检查：从对方出发沿师承链往上走，遇到申请者即拒绝
        UUID cur = master;
        for (int i = 0; i < MAX_DEPTH && cur != null; i++) {
            if (cur.equals(apprentice)) {
                return "这样会形成循环师徒关系，不允许。";
            }
            cur = parseUuid(stats().mentorOf(cur));
        }
        return null;
    }

    private void bind(UUID apprentice, UUID master) {
        stats().setMentor(apprentice, master.toString());
        stats().addDisciple(master, apprentice.toString());
        // 成就「薪火相传」读的是师父侧的 disciple 字段（不是 disciples 集合），
        // 不写的话师父永远凑不齐这个成就
        stats().setDisciple(master, apprentice.toString());
    }

    private void leave(Player p) {
        UUID me = p.getUniqueId();
        String raw = stats().mentorOf(me);
        if (raw.isEmpty()) {
            Msg.prefix(p, "<gray>你还没有师父。</gray>");
            return;
        }
        UUID master = parseUuid(raw);
        unlink(me, master);
        Msg.prefix(p, "<gray>你已离开师门，" + Msg.fmt(cooldownHours(), 1) + " 小时内不能再次拜师。</gray>");
        notifyOnline(master, "<gray>徒弟 <white>" + p.getName() + "</white> 已离开你的门下。</gray>");
    }

    private void expel(Player p, String name) {
        UUID apprenticeId = null;
        for (String raw : stats().discipleIds(p.getUniqueId())) {
            UUID id = parseUuid(raw);
            if (id != null && name.equalsIgnoreCase(nameOf(id))) {
                apprenticeId = id;
                break;
            }
        }
        if (apprenticeId == null) {
            Msg.prefix(p, "<red>你的徒弟里没有 " + name + "。</red>");
            return;
        }
        unlink(apprenticeId, p.getUniqueId());
        Msg.prefix(p, "<gray>已将 <white>" + name + "</white> 逐出师门。</gray>");
        notifyOnline(apprenticeId, "<gray>师父 <white>" + p.getName() + "</white> 把你逐出了师门，"
                + Msg.fmt(cooldownHours(), 1) + " 小时内不能再拜师。</gray>");
    }

    /** 双向断开关系 + 给徒弟记冷却。master 可能为 null（数据异常时只清徒弟侧） */
    private void unlink(UUID apprentice, UUID master) {
        stats().setMentor(apprentice, "");
        if (master != null) {
            stats().removeDisciple(master, apprentice.toString());
            // 徒弟走光了就把师父侧的 disciple 字段也清掉，跟 bind 对称
            if (stats().discipleIds(master).isEmpty()) {
                stats().setDisciple(master, "");
            }
        }
        stats().markMentorCooldown(apprentice, System.currentTimeMillis());
    }

    // ------------------------------------------------------------ 状态面板

    private void status(Player p) {
        UUID me = p.getUniqueId();
        long now = System.currentTimeMillis();
        Msg.send(p, "<dark_gray>──── <white>师徒</white> <dark_gray>────");

        UUID master = parseUuid(stats().mentorOf(me));
        if (master == null) {
            Msg.send(p, "<gray>师父: <white>无</white>");
            long cd = cooldownMs();
            long last = stats().mentorCooldownAt(me);
            if (cd > 0 && last > 0 && now - last < cd) {
                Msg.send(p, "<gray>拜师冷却还剩 <white>" + minutesLeft(cd - (now - last)) + "</white> 分钟");
            }
        } else {
            boolean online = Bukkit.getPlayer(master) != null;
            Msg.send(p, "<gray>师父: <white>" + nameOf(master) + "</white> "
                    + (online
                    ? "<green>在线 · 锻造时容差 +" + Msg.fmt(apprenticeBonusRate() * 100, 0) + "%</green>"
                    : "<dark_gray>离线 · 无容差加成（分成照常）</dark_gray>"));
        }

        List<String> ids = stats().discipleIds(me);
        Msg.send(p, "<gray>徒弟: <white>" + ids.size() + "</white><gray>/" + maxDisciples()
                + " · 分成比例 <white>" + Msg.fmt(coinShareRate() * 100, 0) + "%</white>");
        for (String raw : ids) {
            UUID id = parseUuid(raw);
            if (id == null) {
                continue;
            }
            Msg.send(p, "<gray>  - <white>" + nameOf(id) + "</white> "
                    + (Bukkit.getPlayer(id) != null ? "<green>在线</green>" : "<dark_gray>离线</dark_gray>"));
        }
        Msg.send(p, "<dark_gray>/zhujian master 玩家名 拜师 · /zhujian accept 收徒 · "
                + "/zhujian master leave 离开 · /zhujian master expel 徒弟名 逐出");
    }

    // ------------------------------------------------------------ 锻造联动

    /**
     * 徒弟的高温容差加成：师父在线才生效，返回 0 表示无加成。
     * 由 ForgeManager 在加热结算时乘到容差上：tol *= (1 + 返回值)。
     */
    public double apprenticeToleranceBonus(Player apprentice) {
        if (apprentice == null || !enabled()) {
            return 0.0;
        }
        UUID master = parseUuid(stats().mentorOf(apprentice.getUniqueId()));
        if (master == null || Bukkit.getPlayer(master) == null) {
            return 0.0;
        }
        return apprenticeBonusRate();
    }

    /**
     * 徒弟锻成一把剑后调用：师父分一份剑币 = 徒弟本次剑币收益 × 分成比例（向上取整至少 1）。
     * 传入的 {@code apprenticeCoins} 应是 ForgeManager 里已算好的 coinsEarned（已含连锻倍率），
     * 不在这里重算，避免两处公式不一致。师父离线也入账；只在徒弟锻造成功时结算。
     *
     * @return 师父分到的剑币（没有师父或比例为 0 时返回 0）
     */
    public long onApprenticeForged(Player apprentice, Quality quality, long apprenticeCoins) {
        if (apprentice == null || quality == null || apprenticeCoins <= 0 || !enabled()) {
            return 0;
        }
        UUID apprenticeId = apprentice.getUniqueId();
        // 语义变更（v2.19 出师）：出师即师徒分成终止——graduates 名单里的徒弟锻造
        // 不再给师父分币（出师奖励在 GraduationService 一次性结清）；
        // 出师解绑不记 mentor-cooldown，与 leave/expel 的冷却语义区分开。
        if (stats().isGraduated(apprenticeId)) {
            return 0;
        }
        UUID master = parseUuid(stats().mentorOf(apprenticeId));
        if (master == null) {
            return 0;
        }
        double rate = coinShareRate();
        if (rate <= 0.0) {
            return 0;
        }
        long share = Math.max(1, Math.round(apprenticeCoins * rate));
        stats().addCoins(master, share, CoinReason.MENTOR);

        Player m = Bukkit.getPlayer(master);
        if (m != null) {
            Msg.prefix(m, "<gray>徒弟 <white>" + apprentice.getName() + "</white> 锻成一把 "
                    + quality.colored() + "，你分得 " + CoinRules.fmt(share) + "。</gray>");
        }
        return share;
    }

    // ------------------------------------------------------------ 工具

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** 玩家名：优先用锻造档案里记的名字，其次用 Bukkit 离线缓存，最后用 UUID 前 8 位 */
    private String nameOf(UUID id) {
        String n = stats().get(id).name;
        if (n != null && !n.isEmpty()) {
            return n;
        }
        OfflinePlayer off = Bukkit.getOfflinePlayer(id);
        String offName = off.getName();
        return offName != null ? offName : id.toString().substring(0, 8);
    }

    private void notifyOnline(UUID id, String raw) {
        if (id == null) {
            return;
        }
        Player pl = Bukkit.getPlayer(id);
        if (pl != null) {
            Msg.prefix(pl, raw);
        }
    }

    private static long minutesLeft(long ms) {
        return Math.max(1, (ms + 59_999L) / 60_000L);
    }
}
