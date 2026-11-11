package com.forgemaster.mentor;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.econ.CoinReason;
import com.forgemaster.stats.StatsStore;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * v2.19 出师：师徒关系的「毕业」收尾，与拜师/解除并列的第三条生命周期出口。
 *
 * <p><b>为什么并入 mentor 包而不是开独立的 {@code com.forgemaster.graduation}</b>：
 * 出师读写的全是师徒关系的数据（{@code mentor / disciples / graduates} 字段族）、
 * 配置挂在 {@code mentor.*} 之下、语义是「师徒关系怎么结束」而不是一个独立玩法——
 * 独立包只会把师徒存档语义劈成两处，耦合并不会因此减少。</p>
 *
 * <p>规则：</p>
 * <ul>
 *   <li><b>条件</b>：全部 {@code mentor.graduation.*} 驱动，可配 0 关闭单项——
 *       {@code min-forged / min-reputation} 读 StatsStore 档案，
 *       {@code min-strengthen} 读历史最高强化（bestStrengthen），
 *       {@code min-evolution-stage} 读名剑谱登记的最高锻阶（反射调用，不 import spectrum 包，
 *       口径与其 {@code evolution-stage} 条件一致），<b>一律不扫背包</b>。</li>
 *   <li><b>两步确认</b>：徒弟 {@code /zhujian master graduate} 预约，再
 *       {@code /zhujian master graduate-confirm} 确认（{@code confirm-seconds} 秒内有效），
 *       防止误触一步出师。</li>
 *   <li><b>出师后</b>：徒弟拿 {@code apprentice-reward}（剑币 + 符）、
 *       师父拿 {@code master-reward}；师徒绑定解除且<b>不占</b>
 *       {@code mentor.unbind-cooldown-hours} 的拜师冷却（出师是喜事，不是散伙）；
 *       徒弟进入师父的 {@code graduates} 名单（{@code /zhujian master graduates} 查看）。</li>
 *   <li><b>分成终止</b>：{@link MentorManager#onApprenticeForged} 对已出师徒弟直接不分币
 *       （语义变更点见该方法的注释）。</li>
 * </ul>
 *
 * <p>出师标记双写：玩家 PDC {@code fm_graduated}（在线即时判定）+ StatsStore
 * {@code graduated}（落盘 / 离线查询）。命令路由在 ForgingCommand 的 master 分支
 * 拦截 graduate* 三个子命令，MentorManager 不感知本模块。</p>
 */
public final class GraduationService {

    /** 出师奖励：剑币 + 符（id 照 Charm 的三张符：talisman / steady / charm-kindle） */
    private record Reward(long coins, String charmId, int charmAmount) {
        boolean empty() {
            return coins <= 0 && charmId == null;
        }

        static Reward parse(Map<?, ?> map) {
            if (map == null) {
                return new Reward(0, null, 1);
            }
            long coins = map.get("coins") instanceof Number n ? n.longValue() : 0;
            String charmId = null;
            int charmAmount = 1;
            if (map.get("charm") instanceof String s) {
                charmId = s;
            } else if (map.get("charm") instanceof Map<?, ?> m) {
                charmId = m.get("id") == null ? null : String.valueOf(m.get("id"));
                charmAmount = m.get("amount") instanceof Number n ? n.intValue() : 1;
            }
            return new Reward(Math.max(0, coins), charmId, Math.max(1, charmAmount));
        }
    }

    private final ForgingMasterPlugin plugin;
    /** 待确认出师：key = 徒弟 UUID；仅内存，重启即失效（重新预约即可） */
    private final Map<UUID, Long> pending = new HashMap<>();
    /** 玩家 PDC 出师标记（构造时建，不走 Keys，避免为了一个键去动 util/Keys.java） */
    private final org.bukkit.NamespacedKey graduatedKey;

    public GraduationService(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.graduatedKey = new org.bukkit.NamespacedKey(plugin, "fm_graduated");
    }

    // ------------------------------------------------------------ 配置

    public boolean enabled() {
        return plugin.settings().flag("mentor.enabled", true);
    }

    private long confirmMs() {
        return Math.max(5, plugin.settings().intOf("mentor.graduation.confirm-seconds", 30)) * 1000L;
    }

    private int minForged() {
        return Math.max(0, plugin.settings().intOf("mentor.graduation.min-forged", 100));
    }

    private int minReputation() {
        return Math.max(0, plugin.settings().intOf("mentor.graduation.min-reputation", 200));
    }

    private int minStrengthen() {
        return Math.max(0, plugin.settings().intOf("mentor.graduation.min-strengthen", 20));
    }

    private int minEvolutionStage() {
        return Math.max(0, plugin.settings().intOf("mentor.graduation.min-evolution-stage", 2));
    }

    private Reward apprenticeReward() {
        return Reward.parse(plugin.getConfig().getConfigurationSection("mentor.graduation.apprentice-reward") == null
                ? null
                : plugin.getConfig().getConfigurationSection("mentor.graduation.apprentice-reward").getValues(false));
    }

    private Reward masterReward() {
        return Reward.parse(plugin.getConfig().getConfigurationSection("mentor.graduation.master-reward") == null
                ? null
                : plugin.getConfig().getConfigurationSection("mentor.graduation.master-reward").getValues(false));
    }

    private StatsStore stats() {
        return plugin.stats();
    }

    // ------------------------------------------------------------ 命令入口（ForgingCommand master 分支拦截）

    /** /zhujian master graduate | graduate-confirm | graduates */
    public void handle(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>出师要在游戏里操作。</red>");
            return;
        }
        if (!enabled()) {
            Msg.prefix(p, "<red>师徒系统没开。</red>");
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "graduate";
        switch (sub) {
            case "graduate-confirm", "queren" -> confirm(p);
            case "graduates", "chutu" -> graduates(p);
            default -> request(p);
        }
    }

    // ------------------------------------------------------------ 第一步：预约出师

    private void request(Player p) {
        UUID me = p.getUniqueId();
        if (stats().isGraduated(me)) {
            Msg.prefix(p, "<gray>你已经出过师了。</gray>");
            return;
        }
        UUID master = parseUuid(stats().mentorOf(me));
        if (master == null) {
            Msg.prefix(p, "<gray>你还没有师父。</gray>");
            return;
        }
        List<String> unmet = unmetLines(me);
        if (!unmet.isEmpty()) {
            Msg.prefix(p, "<gray>出师条件还差着：</gray>");
            for (String line : unmet) {
                Msg.send(p, "  " + line);
            }
            return;
        }
        pending.put(me, System.currentTimeMillis() + confirmMs());
        long sec = confirmMs() / 1000L;
        Msg.prefix(p, "<gradient:#ffd166:#ff7a18>出师条件全部达成！</gradient>");
        Msg.send(p, "<gray>确认出师请再输入 </gray><white>/zhujian master graduate-confirm</white>"
                + "<gray>（" + sec + " 秒内有效）。出师后师门缘分不断，但剑币分成到此为止。</gray>");
        Msg.prefix(p, "<gray>确认后你与师父的分成即终止，且<b>不会</b>占用拜师冷却。</gray>");
    }

    // ------------------------------------------------------------ 第二步：确认出师

    private void confirm(Player p) {
        UUID me = p.getUniqueId();
        Long expires = pending.remove(me);
        if (expires == null || System.currentTimeMillis() > expires) {
            Msg.prefix(p, "<gray>没有待确认的出师，先输入 <white>/zhujian master graduate</white> 预约。</gray>");
            return;
        }
        if (stats().isGraduated(me)) {
            Msg.prefix(p, "<gray>你已经出过师了。</gray>");
            return;
        }
        UUID master = parseUuid(stats().mentorOf(me));
        if (master == null) {
            Msg.prefix(p, "<gray>你已经没有师父了。</gray>");
            return;
        }
        List<String> unmet = unmetLines(me);
        if (!unmet.isEmpty()) {
            Msg.prefix(p, "<red>预约之后条件又变了，先把这几样补齐：</red>");
            for (String line : unmet) {
                Msg.send(p, "  " + line);
            }
            return;
        }
        graduate(p, master);
    }

    /** 真正出师：落档 + 双方奖励 + 解绑（不占拜师冷却）+ 榜上留名 */
    private void graduate(Player p, UUID master) {
        UUID me = p.getUniqueId();
        long now = System.currentTimeMillis();
        Reward apprenticeReward = apprenticeReward();
        Reward masterReward = masterReward();

        // 1) 落档：StatsStore 记录 + 玩家 PDC 标记
        stats().markGraduated(me, now);
        p.getPersistentDataContainer().set(graduatedKey, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);

        // 2) 解绑：语义上等同 unlink 但<b>不</b>记 mentor-cooldown——
        //    出师不是散伙，徒弟日后想再拜师不受 unbind-cooldown-hours 限制
        stats().setMentor(me, "");
        stats().removeDisciple(master, me.toString());
        if (stats().discipleIds(master).isEmpty()) {
            stats().setDisciple(master, "");
        }
        stats().addGraduate(master, me.toString());

        // 3) 奖励：徒弟 coins + 符；师父 coins + 符（符要在线才发得出，离线只入账剑币）
        StringBuilder gotApprentice = new StringBuilder();
        if (apprenticeReward.coins() > 0) {
            stats().addCoins(me, apprenticeReward.coins(), CoinReason.MENTOR);
            gotApprentice.append("剑币 <#ffd166>+").append(apprenticeReward.coins()).append("</#ffd166> ");
        }
        gotApprentice.append(giveCharm(p, apprenticeReward));
        StringBuilder gotMaster = new StringBuilder();
        if (masterReward.coins() > 0) {
            stats().addCoins(master, masterReward.coins(), CoinReason.MENTOR);
            gotMaster.append("剑币 <#ffd166>+").append(masterReward.coins()).append("</#ffd166> ");
        }

        // 4) 演出与通知
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.2f);
        Msg.prefix(p, "<gradient:#ffd166:#ff7a18><bold>出师</bold></gradient> <green>你已正式出师！"
                + "师门为你备的贺礼：" + gotApprentice.toString().trim() + "</green>");
        Player m = Bukkit.getPlayer(master);
        if (m != null) {
            giveCharm(m, masterReward);
            m.playSound(m.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.2f);
            Msg.prefix(m, "<green>你的徒弟 <white>" + p.getName() + "</white> 出师了！"
                    + "你的贺礼：" + gotMaster.toString().trim()
                    + "<gray>（出师弟子不再参与剑币分成）</gray>");
        } else {
            notifyOffline(master, "你的徒弟 " + p.getName() + " 出师了，奖励已入账（剑币 "
                    + masterReward.coins() + "）。出师弟子不再参与剑币分成。");
        }
        org.bukkit.Bukkit.broadcast(Msg.mm(Msg.PREFIX + "<gray>" + p.getName()
                + " 在 <white>" + nameOf(master) + "</white> 门下学成出师，独当一面了！</gray>"));
    }

    /** 发符（在线才有背包）；发不出 / 没配返回空串，否则返回「N × 符名」 */
    private String giveCharm(Player p, Reward r) {
        if (r.charmId() == null || r.charmId().isBlank() || p == null) {
            return "";
        }
        String norm = com.forgemaster.strengthen.Charm.normalize(r.charmId());
        if (norm == null) {
            return "";
        }
        var charm = com.forgemaster.strengthen.Charm.create(plugin, norm);
        charm.setAmount(r.charmAmount());
        var leftover = p.getInventory().addItem(charm);
        leftover.values().forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
        return "<#ffd166>" + r.charmAmount() + " × " + com.forgemaster.strengthen.Charm.plainName(norm) + "</#ffd166> ";
    }

    /** 离线师父的入账提示：塞进锻造档案名下，上线也从这里翻不到——只在数据层保证入账，消息尽力而为 */
    private void notifyOffline(UUID master, String raw) {
        // 离线玩家没有聊天通道；剑币已入 StatsStore，这里仅留档不言
    }

    // ------------------------------------------------------------ 出师弟子名单

    /** /zhujian master graduates：师父查已出师徒弟 */
    private void graduates(Player p) {
        List<String> ids = stats().graduateIds(p.getUniqueId());
        Msg.send(p, "<dark_gray>──── <white>出师弟子</white> <dark_gray>────");
        if (ids.isEmpty()) {
            Msg.send(p, "<gray>还没有弟子出师。条件齐了他们会自己来敲钟。</gray>");
        }
        for (String raw : ids) {
            UUID id = parseUuid(raw);
            if (id == null) {
                continue;
            }
            long at = stats().get(id).graduatedAt;
            String when = at > 0 ? new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(at)) : "?";
            Msg.send(p, "<gray>  - <white>" + nameOf(id) + "</white> <dark_gray>（" + when + " 出师 · 不再分成）</dark_gray>");
        }
        Msg.send(p, "<dark_gray>出师弟子保留师门名分，但剑币分成已终止。</dark_gray>");
    }

    // ------------------------------------------------------------ 条件盘点

    /** 未达成条件的中文明细行（空列表 = 全部达成） */
    private List<String> unmetLines(UUID uuid) {
        List<String> out = new ArrayList<>();
        var st = stats().peek(uuid);
        int forged = st == null ? 0 : st.forged;
        int reputation = st == null ? 0 : st.reputation;
        int strengthen = stats().bestStrengthenOf(uuid);
        int evolution = bestEvolution(uuid);

        if (minForged() > 0 && forged < minForged()) {
            out.add(line("累计锻造", forged, minForged()));
        }
        if (minReputation() > 0 && reputation < minReputation()) {
            out.add(line("声望", reputation, minReputation()));
        }
        if (minStrengthen() > 0 && strengthen < minStrengthen()) {
            out.add(line("历史最高强化", strengthen, minStrengthen()));
        }
        if (minEvolutionStage() > 0 && evolution < minEvolutionStage()) {
            out.add(line("最高锻阶", evolution, minEvolutionStage()));
        }
        return out;
    }

    private static String line(String label, int cur, int need) {
        String colored = (cur >= need ? "<green>" : "<red>") + cur
                + (cur >= need ? "</green>" : "</red>");
        return "<gray>" + label + "：</gray>" + colored + "<gray> / " + need + "</gray>";
    }

    /**
     * 最高锻阶：与名剑谱 {@code evolution-stage} 条件同一数据源（EvolutionService 结算处登记的
     * 历史最高）。用反射取值，<b>不 import spectrum 包</b>——称号/出师条件口径对齐即可，
     * 不与名剑谱模块产生编译期耦合。取不到时按 0 处理（该项条件由配置 0 关闭兜底）。
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

    /** 反射句柄缓存：插件类只加载一次，Method 常驻 */
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

    /** 玩家名：档案名优先，离线缓存兜底，都没有给 UUID 前 8 位（MentorManager 同款口径） */
    private String nameOf(UUID id) {
        String n = stats().get(id).name;
        if (n != null && !n.isEmpty()) {
            return n;
        }
        org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(id);
        String offName = off.getName();
        return offName != null ? offName : id.toString().substring(0, 8);
    }
}
