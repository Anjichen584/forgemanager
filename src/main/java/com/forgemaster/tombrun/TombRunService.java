package com.forgemaster.tombrun;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.tomb.TombChallenge;
import com.forgemaster.tomb.TombManager;
import com.forgemaster.util.Msg;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 剑冢秘境（tombrun）：在残影挑战之上做的分层副本——「秘境试炼」。
 *
 * <p>玩家付 {@code tombrun.ticket-coins} 剑币门票后，在身边连续刷 {@code tombrun.waves}
 * 定义的若干波残影守卫（数量与强度倍率 config 驱动，{@code mult} 直接乘在残影血量与攻击上）。
 * 全部波次清算即通关，发 {@code reward.coins} 剑币 + {@code reward.pages} 张「残页」；
 * 超时 / 死亡 / 退服 / 跑太远 / 主动放弃即判负，门票不退。残页可在剑冢挑战时抵扣挑战费
 * （见 {@link TombManager} 的残页折扣插入点）。</p>
 *
 * <h2>残影强度来源</h2>
 * <p>每一波的守卫档案（生物类型 / 强化等级 / 品质系数 / 攻击底子）直接复用
 * {@link TombChallenge} 的公开残影档案方法：从 {@link TombManager#all()} 里随机挑一条
 * 被埋剑记录作为「品质来源」，调 {@code levelOf / qualityCoefOf / mobOf / swordAttackOf /
 * scaledHealth / scaledAttack} 算属性，再乘波次 {@code mult}。只读剑冢内存登记表，
 * <b>不扫全服物品</b>。剑冢为空时按 +0 / 普通品质 / 僵尸兜底。</p>
 *
 * <h2>生命周期六路清理（全部照 TombChallenge 模式）</h2>
 * <ol>
 *   <li><b>退服</b>：{@link #onQuit(PlayerQuitEvent)} 立即判负清场；</li>
 *   <li><b>死亡</b>：{@link #onPlayerDeath(PlayerDeathEvent)} 立即判负清场；</li>
 *   <li><b>超时</b>：心跳里判 {@code now - startedAt >= timeout-seconds} 强制判负；</li>
 *   <li><b>跑太远</b>：心跳里判玩家离起跑点超过 {@code leave-radius}，宽限
 *       {@code away-seconds} 秒后判负；</li>
 *   <li><b>插件停用</b>：{@link #shutdown()}（onDisable 调）停心跳、撤守卫、清登记；</li>
 *   <li><b>全清</b>：{@link #onShadowDeath(EntityDeathEvent)} 里最后一波守卫死光 → 通关发奖。</li>
 * </ol>
 * 另有 {@link ChunkLoadEvent} 孤儿清扫兜底（带标记却不在任何进行中 run 里的实体直接删）。
 *
 * <h2>实体纪律</h2>
 * <p>守卫一律 {@code setPersistent(false)}（不进存档，崩服不留尸）+ 记分板标签
 * {@link #TAG} + PDC 标记；开服延迟清扫与区块加载孤儿清扫双保险，只认自己的标记，
 * 绝不碰玩家宠物与其它插件实体。</p>
 */
public final class TombRunService implements Listener {

    /** 秘境守卫的记分板标签（回收时唯一的判定依据） */
    public static final String TAG = "forgemaster_tombrun_shadow";

    /** 一场进行中的秘境试炼 */
    private static final class Run {
        final UUID player;
        /** 起跑点（跑太远的判定圆心） */
        final Location origin;
        final long startedAt;
        /** 当前波次（1 起） */
        int wave;
        /** 当前波还活着的守卫 */
        final List<UUID> alive = new ArrayList<>();
        /** 玩家跑远的起始时刻，0 = 人就在场 */
        long awaySince;
        /** 当前波守卫失踪（区块卸载等）的起始时刻，0 = 都在 */
        long missingSince;
        /** 下一波的延迟生成任务（判负 / 停用时撤掉） */
        int pendingTaskId = -1;

        Run(UUID player, Location origin, long startedAt) {
            this.player = player;
            this.origin = origin;
            this.startedAt = startedAt;
        }
    }

    /** 波次定义（config tombrun.waves 的一行） */
    public record WaveDef(int count, double mult) {
    }

    private final ForgingMasterPlugin plugin;
    private final TombManager tombs;
    private final Random random = new Random();

    /** 每人同时至多一个 run：key = 玩家 uuid */
    private final Map<UUID, Run> runs = new LinkedHashMap<>();

    private final NamespacedKey keyShadow;
    private final NamespacedKey keyPlayer;
    /** 残页 PDC 键（fm_torn_page） */
    private final NamespacedKey keyPage;

    private BukkitTask ticker;
    private boolean listening;

    public TombRunService(ForgingMasterPlugin plugin, TombManager tombs) {
        this.plugin = plugin;
        this.tombs = tombs;
        this.keyShadow = new NamespacedKey(plugin, "tombrun_shadow");
        this.keyPlayer = new NamespacedKey(plugin, "tombrun_shadow_player");
        this.keyPage = new NamespacedKey(plugin, "torn_page");
        // 构造发生在 onEnable 里（spectrum / seasonPass 之后），在这里挂监听与心跳不算「onEnable 之外的副作用」
        ensureListener();
        start();
        // 上一次崩服 / 被强杀可能把守卫留在世界里：开服两秒后按标记清一遍（只认自己的标记）
        Bukkit.getScheduler().runTaskLater(plugin, this::removeAllTagged, 40L);
    }

    // ------------------------------------------------------------ 公开 API

    /**
     * 进入秘境试炼：验开关 → 验没有进行中的 run → 收门票 → 生成第 1 波。
     *
     * <p>返回的是<b>已经发给玩家的那条提示</b>的 MiniMessage 原文（不带 {@code Msg.PREFIX}），
     * 提示已由本方法发出，命令层不必再发一次。</p>
     *
     * @param player 挑战者
     * @return MiniMessage 结果文本（校验失败时是失败原因）
     */
    public String startRun(Player player) {
        if (player == null) {
            return "<red>只有玩家能进入秘境。</red>";
        }
        ensureListener();
        start();

        if (!enabled()) {
            return say(player, "<red>剑冢秘境没开（<white>tombrun.enabled = false</white>）。</red>");
        }
        if (runs.containsKey(player.getUniqueId())) {
            return say(player, "<red>你已经在秘境试炼里了，先把这波守卫清完。</red>");
        }
        List<WaveDef> waves = waves();
        if (waves.isEmpty()) {
            return say(player, "<red>秘境波次没配好（<white>tombrun.waves</white> 为空），找管理员看看。</red>");
        }
        long ticket = ticketCoins();
        if (ticket > 0 && !plugin.stats().takeCoins(player.getUniqueId(), ticket, com.forgemaster.econ.CoinReason.TOMB_FEE)) {
            return say(player, "<red>进秘境需要 <white>" + ticket + "</white> 剑币门票，你不够。</red>");
        }

        List<UUID> spawned = spawnWave(player, 1, waves.get(0));
        if (spawned == null) {
            if (ticket > 0) {
                plugin.stats().addCoins(player.getUniqueId(), ticket); // 生成失败：门票原路退回
            }
            return say(player, "<red>秘境守卫没能凝成形，稍后再试（门票已退回）。</red>");
        }
        Run run = new Run(player.getUniqueId(), player.getLocation().clone(), System.currentTimeMillis());
        run.wave = 1;
        run.alive.addAll(spawned);
        runs.put(player.getUniqueId(), run);

        player.playSound(player.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 0.6f);
        player.playSound(player.getLocation(), Sound.BLOCK_SOUL_SOIL_BREAK, 1.0f, 0.7f);

        return say(player, "<gradient:#8d8d8d:#4e7fff><bold>秘境试炼 · 开</bold></gradient> <gray>第 <white>1</white>/"
                + waves.size() + " 波，守卫 <white>" + spawned.size() + "</white> 只 · 超时 <white>"
                + timeoutSeconds() + "</white> 秒。清完全部波次即通关。</gray>");
    }

    /**
     * 主动放弃：按判负结算（门票不退），等价于跑太远 / 超时的清理路径。
     *
     * @return MiniMessage 结果文本
     */
    public String quitRun(Player player) {
        Run run = player == null ? null : runs.get(player.getUniqueId());
        if (run == null) {
            return player == null ? "<red>只有玩家能操作。</red>"
                    : "<red>你现在没有进行中的秘境试炼。</red>";
        }
        fail(run, "你撤出了秘境");
        return "<gray>已撤出秘境试炼，门票不退。</gray>";
    }

    /**
     * 试炼进度一句话（/zhujian tombrun status 用）。
     *
     * @return 没在试炼里返回空串
     */
    public String statusLine(Player player) {
        Run run = player == null ? null : runs.get(player.getUniqueId());
        if (run == null) {
            return "";
        }
        long left = timeoutSeconds() * 1000L - (System.currentTimeMillis() - run.startedAt);
        return "<gray>秘境试炼：第 <white>" + run.wave + "</white>/" + waves().size()
                + " 波 <dark_gray>·</dark_gray> 守卫余 <white>" + run.alive.size()
                + "</white> <dark_gray>·</dark_gray> 剩余 <white>" + Math.max(0, left) / 1000 + "</white> 秒</gray>";
    }

    /** 进行中的试炼数（自检用） */
    public int runCount() {
        return runs.size();
    }

    /**
     * 清理路 5 —— 插件停用：停心跳、撤掉所有在册守卫与待生成任务、清空登记，
     * 再按标记全服扫一遍兜底（onDisable 调，幂等）。
     */
    public void shutdown() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        for (Run run : new ArrayList<>(runs.values())) {
            cancelPending(run);
            despawn(run);
        }
        runs.clear();
        removeAllTagged();
    }

    // ------------------------------------------------------------ 命令

    /** /zhujian tombrun [enter|status|quit]（别名 mijing） */
    public void command(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.prefix(sender, "<red>只有玩家能进入剑冢秘境。</red>");
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "enter";
        switch (sub) {
            case "enter", "jin", "start" -> Msg.prefix(player, startRun(player));
            case "status", "chakan" -> {
                String line = statusLine(player);
                Msg.prefix(player, line.isBlank()
                        ? "<gray>你不在秘境试炼里。用 <white>/zhujian tombrun</white> 付门票进入。</gray>"
                        : line);
            }
            case "quit", "fangqi" -> Msg.prefix(player, quitRun(player));
            default -> Msg.prefix(player, "<gray>用法: /zhujian tombrun [enter|status|quit]"
                    + " <dark_gray>（enter = 付门票进入秘境试炼）</dark_gray></gray>");
        }
    }

    // ------------------------------------------------------------ 调度

    private void start() {
        if (ticker != null) {
            return;
        }
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
    }

    /** 挂监听：只挂一次；非主线程留给下次 startRun() 再补 */
    private void ensureListener() {
        if (listening || !Bukkit.isPrimaryThread()) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        listening = true;
    }

    /** 每秒扫一遍：超时 / 跑太远 / 守卫失踪 / 仇恨回拴，六路里「超时」「跑太远」两路在这里走 */
    private void tick() {
        if (runs.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Run run : new ArrayList<>(runs.values())) {
            Player p = Bukkit.getPlayer(run.player);
            // 守卫失踪：区块卸载 / 被其它插件清场，宽限 30 秒后当作这一波已被清算
            if (allMissing(run)) {
                if (run.missingSince == 0) {
                    run.missingSince = now;
                } else if (now - run.missingSince >= 30_000L) {
                    waveCleared(run, p);
                }
                continue;
            }
            run.missingSince = 0;

            // 跑太远：离起跑点超 leave-radius，宽限 away-seconds 后判负
            double leave = leaveRadius();
            boolean away = p == null || !p.isOnline() || !p.getWorld().equals(run.origin.getWorld())
                    || (run.origin.getWorld() != null
                        && p.getLocation().distanceSquared(run.origin) > leave * leave);
            if (away) {
                if (run.awaySince == 0) {
                    run.awaySince = now;
                } else if (now - run.awaySince >= awaySeconds() * 1000L) {
                    fail(run, p == null ? "守卫散了" : "你离秘境太远");
                }
                continue;
            }
            run.awaySince = 0;

            // 超时：强制结算为失败
            if (now - run.startedAt >= timeoutSeconds() * 1000L) {
                fail(run, p, "时限已到，秘境守卫散去了");
                continue;
            }
            // 人还在场：把仇恨按回挑战者身上，免得守卫跑去打路人
            if (p != null && p.isOnline()) {
                for (UUID id : run.alive) {
                    if (Bukkit.getEntity(id) instanceof Mob mob && !p.equals(mob.getTarget())) {
                        mob.setTarget(p);
                    }
                }
            }
        }
    }

    /** 这一波的守卫是不是全都不在世界里（区块卸载 / 被清场） */
    private boolean allMissing(Run run) {
        for (UUID id : run.alive) {
            Entity e = Bukkit.getEntity(id);
            if (e != null && e.isValid()) {
                return false;
            }
        }
        return !run.alive.isEmpty();
    }

    // ------------------------------------------------------------ 六路清理之 1：退服

    /**
     * 清理路 1 —— 退服：玩家退出服务器立即判负清场。
     *
     * <p>不走心跳宽限（人都没了，守卫留着只会咬路人）。门票不退、不发奖，
     * 与跑太远 / 超时同一条 {@link #fail(Run, String)} 结算路径。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Run run = runs.get(event.getPlayer().getUniqueId());
        if (run != null) {
            fail(run, "你退出了服务器");
        }
    }

    // ------------------------------------------------------------ 六路清理之 2：死亡

    /**
     * 清理路 2 —— 死亡：玩家在试炼中倒下立即判负清场。
     *
     * <p>心跳里没有死亡分支兜底也安全：死亡事件先行清场后 run 已摘除，
     * 后续守卫死光时查不到 run，只会走 {@link #onShadowDeath} 的通用清理（不重复发奖）。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Run run = runs.get(event.getPlayer().getUniqueId());
        if (run != null) {
            fail(run, "你在秘境里倒下了");
        }
    }

    // ------------------------------------------------------------ 六路清理之 3：超时（tick 内判定）

    // ------------------------------------------------------------ 六路清理之 4：跑太远（tick 内判定）

    // ------------------------------------------------------------ 六路清理之 5：插件停用（见 shutdown 的 javadoc）

    // ------------------------------------------------------------ 六路清理之 6：全清（通关）

    /**
     * 清理路 6 —— 全清：最后一波守卫死光 → 通关发奖。
     *
     * <p>非末波清空 → 排下一波延迟生成；run 不在册（判负已清场）则只做通用清理。</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShadowDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (!dead.getScoreboardTags().contains(TAG)) {
            return; // 不是秘境守卫：原样放行，不碰别的东西
        }
        // 原版战利品与经验一律不给，奖励统一由插件发
        event.getDrops().clear();
        event.setDroppedExp(0);

        Run run = runOf(dead.getUniqueId());
        if (run == null) {
            return; // 孤儿守卫（判负后残留）：掉落已清，登记表里没它，直接放行
        }
        run.alive.remove(dead.getUniqueId());
        if (!run.alive.isEmpty()) {
            Player p = Bukkit.getPlayer(run.player);
            if (p != null && p.isOnline()) {
                Msg.action(p, "<gray>守卫余 <white>" + run.alive.size() + "</white> 只。</gray>");
            }
            return;
        }
        waveCleared(run, Bukkit.getPlayer(run.player));
    }

    /** 这一波清完了：排下一波，或全清通关 */
    private void waveCleared(Run run, Player p) {
        List<WaveDef> waves = waves();
        if (run.wave >= waves.size()) {
            win(run, p);
            return;
        }
        int next = run.wave + 1;
        run.wave = next;
        run.alive.clear();
        run.missingSince = 0;
        cancelPending(run);
        // 延迟 3 秒刷下一波，给玩家一点喘息；任务 id 记在 run 上，判负 / 停用时撤
        run.pendingTaskId = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            run.pendingTaskId = -1;
            Run live = runs.get(run.player);
            if (live != run) {
                return; // 判负 / 停用已清场：任务作废
            }
            Player sp = Bukkit.getPlayer(run.player);
            if (sp == null || !sp.isOnline() || sp.isDead()) {
                fail(run, "下一波守卫来时你已不在");
                return;
            }
            List<UUID> spawned = spawnWave(sp, next, waves.get(next - 1));
            if (spawned == null) {
                fail(run, "下一波守卫没能凝成形");
                return;
            }
            run.alive.addAll(spawned);
            sp.playSound(sp.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.4f, 0.8f);
            Msg.prefix(sp, "<gray>第 <white>" + next + "</white>/" + waves.size() + " 波来了：守卫 <white>"
                    + spawned.size() + "</white> 只。</gray>");
        }, 60L).getTaskId();
        if (p != null && p.isOnline()) {
            Msg.prefix(p, "<green>第 " + (next - 1) + " 波已清算。</green> <gray>下一波 <white>3</white> 秒后到。</gray>");
        }
    }

    /** 通关：发剑币 + 残页，登记赛季通行证，收尾 */
    private void win(Run run, Player p) {
        runs.remove(run.player);
        cancelPending(run);
        despawn(run);
        if (p == null || !p.isOnline()) {
            return; // 人不在线：没人可发，试炼作废（退服路径本就先判负，这里是兜底）
        }
        long coins = rewardCoins();
        if (coins > 0) {
            plugin.stats().addCoins(p.getUniqueId(), coins);
        }
        int pages = rewardPages();
        String pagesTxt = "<dark_gray>无</dark_gray>";
        if (pages > 0) {
            give(p, tornPage(pages));
            pagesTxt = "<#d8b45a>残页 ×" + pages + "</#d8b45a>";
        }
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.2f);
        p.spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 30, 0.5, 0.7, 0.5, 0.1);
        Msg.prefix(p, "<green><bold>秘境试炼通关！</bold></green> <gray>" + waves().size()
                + " 波守卫尽数散去，获得 <#ffd166>" + coins + "</#ffd166> 剑币 + " + pagesTxt
                + "。</gray> <dark_gray>残页可在剑冢挑战时抵扣挑战费。</dark_gray>");
        // 名剑谱（SpectrumService）：它只有 countTombWin(Player)（语义 = 剑冢残影胜场），
        // 没有秘境通关的公开登记方法——秘境不该蹭「斩影」计数，故这里不调，留此注明。
        // 赛季通行证：通用 earn(source) 公开登记方法存在，按 guard 调用（来源未配置时是空操作）
        if (plugin.seasonPass() != null) {
            plugin.seasonPass().earn(p.getUniqueId(), "tombrun-win");
        }
    }

    /** 判负：撤守卫、清登记、给一句提示（门票不退） */
    private void fail(Run run, String reason) {
        fail(run, Bukkit.getPlayer(run.player), reason);
    }

    private void fail(Run run, Player p, String reason) {
        runs.remove(run.player);
        cancelPending(run);
        despawn(run);
        if (p != null && p.isOnline()) {
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.9f, 0.9f);
            Msg.prefix(p, "<red>" + reason + "。</red> <gray>秘境试炼失败，门票不退。</gray>");
        }
    }

    private void cancelPending(Run run) {
        if (run.pendingTaskId != -1) {
            Bukkit.getScheduler().cancelTask(run.pendingTaskId);
            run.pendingTaskId = -1;
        }
    }

    /** 撤掉这一 run 在册的全部守卫（按 uuid 找、按标记核，绝不碰别的实体） */
    private void despawn(Run run) {
        for (UUID id : run.alive) {
            Entity e = Bukkit.getEntity(id);
            if (e != null && e.getScoreboardTags().contains(TAG)) {
                e.remove();
            }
        }
        run.alive.clear();
    }

    // ------------------------------------------------------------ 生成

    /**
     * 在玩家周围生成一波守卫。
     *
     * <p>生成思路与 {@link TombChallenge} 同源（找安全落脚点 + 标记 + 属性缩放）但独立实现；
     * 数量 / 强度倍率来自波次定义，生物类型与档案来自随机一条被埋剑（复用其公开档案方法）。</p>
     *
     * @return 生成的守卫 uuid；一只都没生成出来返回 null（调用方退门票）
     */
    private List<UUID> spawnWave(Player player, int waveNo, WaveDef wave) {
        TombManager.Tomb source = randomTomb();
        int level = source == null ? 0 : tombs.challenges().levelOf(source);
        double coef = TombChallenge.qualityCoefOf(source == null ? "COMMON" : source.quality);
        EntityType type = source == null ? EntityType.ZOMBIE : tombs.challenges().mobOf(source);
        double swordAttack = source == null ? 6.0 : tombs.challenges().swordAttackOf(source, coef);
        double mult = wave.mult();

        double health = TombChallenge.clampValue(
                TombChallenge.scaledHealth(TombChallenge.baseHealthOf(type), level, coef, levelPerLevel()) * mult,
                maxHealth());
        double attack = TombChallenge.clampValue(
                TombChallenge.scaledAttack(swordAttack, level, coef, levelPerLevel(), attackScale()) * mult,
                maxAttack());

        List<UUID> out = new ArrayList<>();
        int spawned = 0;
        for (int i = 0; i < wave.count(); i++) {
            Location spot = findSpot(player, spawned);
            if (spot == null) {
                break;
            }
            Mob guard = spawnGuard(player, source, spot, type, waveNo, level, health, attack);
            if (guard != null) {
                out.add(guard.getUniqueId());
                spawned++;
            }
        }
        if (spawned == 0) {
            return null;
        }
        World world = player.getWorld();
        world.spawnParticle(Particle.SOUL, player.getLocation().add(0, 1, 0), 40, 3, 1, 3, 0.02);
        return out;
    }

    /** 品质来源：从剑冢登记表随机挑一条（只读内存表，禁止扫全服物品）；空剑冢返回 null */
    private TombManager.Tomb randomTomb() {
        List<TombManager.Tomb> all = tombs.all();
        if (all.isEmpty()) {
            return null;
        }
        return all.get(random.nextInt(all.size()));
    }

    private Mob spawnGuard(Player player, TombManager.Tomb source, Location spot, EntityType type,
                           int waveNo, int level, double health, double attack) {
        World world = spot.getWorld();
        if (world == null) {
            return null;
        }
        Entity raw;
        try {
            raw = world.spawnEntity(spot, type);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("秘境守卫生成失败（" + type + "）：" + ex.getMessage());
            return null;
        }
        if (raw == null) {
            // 领地 / 反刷怪插件取消生成时，Craft 实现可能返回 null
            return null;
        }
        if (!(raw instanceof Mob mob)) {
            raw.remove();
            return null;
        }

        mob.addScoreboardTag(TAG);
        var pdc = mob.getPersistentDataContainer();
        pdc.set(keyShadow, PersistentDataType.BYTE, (byte) 1);
        pdc.set(keyPlayer, PersistentDataType.STRING, player.getUniqueId().toString());

        // v2.23 残影词缀：三波守卫同掷 0~2 条残影词缀（PDC 随实体走，死亡自然失效）
        List<String> affixIds = tombs.guardAffixes().apply(mob);

        // 实体纪律：不进存档 + 不自然消失（由本服务的六路清理负责生命周期）
        mob.setPersistent(false);
        mob.setRemoveWhenFarAway(false);
        mob.setCanPickupItems(false);
        mob.setAware(true);
        String swordName = source == null ? "无名守卫" : source.swordName;
        mob.customName(Msg.mm("<gray>秘境守卫 <dark_gray>·</dark_gray> 第" + waveNo + "波 <dark_gray>·</dark_gray> <#ffd166>+"
                + level + "</#ffd166> <dark_gray>" + swordName + "</dark_gray>"
                + tombs.guardAffixes().affixTag(affixIds))
                .decoration(TextDecoration.ITALIC, false));
        mob.setCustomNameVisible(true);
        mob.setGlowing(true);
        if (mob instanceof Zombie zombie) {
            zombie.setShouldBurnInDay(false);
            zombie.setBaby(false);
        }

        AttributeInstance maxHealth = mob.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(health);
        }
        double real = maxHealth == null ? health : maxHealth.getValue();
        mob.setHealth(Math.max(1, Math.min(health, real)));

        AttributeInstance attackAttr = mob.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attackAttr != null) {
            attackAttr.setBaseValue(attack);
        }

        // 装备只用普通铁剑占手：不发真剑、不给掉落（死亡事件里也统一清掉落）
        EntityEquipment equipment = mob.getEquipment();
        if (equipment != null) {
            equipment.setItemInMainHand(new ItemStack(Material.IRON_SWORD));
            equipment.setItemInMainHandDropChance(0f);
        }

        mob.setTarget(player);
        return mob;
    }

    /** 附近找一块安全落脚点（思路同 TombChallenge.findSpot，独立实现：兼顾已生成只数散开站位） */
    private Location findSpot(Player player, int index) {
        World world = player.getWorld();
        Location base = player.getLocation();
        int radius = spawnRadius();
        int surfaceY = world.getHighestBlockYAt(base.getBlockX(), base.getBlockZ());
        boolean outdoor = Math.abs(surfaceY - base.getBlockY()) <= 8;

        for (int i = 0; i < 32; i++) {
            // 按 index 偏移起始角，让一波守卫围着玩家散开，不全挤一个点
            double angle = (index * Math.PI / 4) + random.nextDouble() * Math.PI * 2;
            double dist = 2 + random.nextDouble() * Math.max(1, radius - 2);
            int x = base.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int z = base.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);
            int topY = outdoor ? world.getHighestBlockYAt(x, z) : base.getBlockY();
            for (int dy = 2; dy >= -3; dy--) {
                Location spot = new Location(world, x + 0.5, topY + dy + 1, z + 0.5);
                if (canStand(spot)) {
                    return spot;
                }
            }
        }
        // 兜底：玩家脚边两格
        Location fallback = base.clone().add(base.getDirection().normalize().multiply(2));
        return canStand(fallback) ? fallback : null;
    }

    private boolean canStand(Location spot) {
        Location feet = spot.clone();
        Location head = spot.clone().add(0, 1, 0);
        Location ground = spot.clone().add(0, -1, 0);
        if (!ground.getBlock().getType().isSolid()) {
            return false;
        }
        return feet.getBlock().isPassable() && head.getBlock().isPassable()
                && feet.getBlock().getType().isAir() && head.getBlock().getType().isAir();
    }

    // ------------------------------------------------------------ 孤儿清扫

    /** 按标记全服清扫（开服延迟 + shutdown 兜底）：只删带我们标记的，玩家宠物一个不碰 */
    private void removeAllTagged() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (e.getScoreboardTags().contains(TAG)) {
                    e.remove();
                }
            }
        }
    }

    /**
     * 区块加载孤儿清扫：守卫是 {@code setPersistent(false)}，不进存档，
     * 但同一次运行里区块卸载再加载会把它们带回来——带 TAG 却不在任何进行中
     * run 登记里的就是孤儿，直接删。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity e : event.getChunk().getEntities()) {
            if (!e.getScoreboardTags().contains(TAG)) {
                continue;
            }
            if (isTracked(e.getUniqueId())) {
                continue;
            }
            e.remove();
            plugin.getLogger().fine("清理孤儿秘境守卫 " + e.getUniqueId());
        }
    }

    /** 这个实体是不是某场进行中 run 的守卫 */
    private boolean isTracked(UUID entityId) {
        for (Run run : runs.values()) {
            if (run.alive.contains(entityId)) {
                return true;
            }
        }
        return false;
    }

    private Run runOf(UUID entityId) {
        for (Run run : runs.values()) {
            if (run.alive.contains(entityId)) {
                return run;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 残页（PDC 物品）

    /**
     * 造 {@code amount} 张残页：可堆叠的纸，PDC 键 {@code fm_torn_page}（本服务的
     * {@code plugin:forgemaster:torn_page} NamespacedKey）打 BYTE 1。
     *
     * <p>判定必须走 PDC（{@link #isTornPage}），名字 / lore 可被铁砧改，不算数。</p>
     */
    public ItemStack tornPage(int amount) {
        ItemStack page = new ItemStack(Material.PAPER, Math.max(1, amount));
        ItemMeta meta = page.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(keyPage, PersistentDataType.BYTE, (byte) 1);
            meta.displayName(Msg.mm("<#d8b45a>残页</#d8b45a>").decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Msg.mm("<gray>秘境守卫散落后留下的剑谱残页。</gray>").decoration(TextDecoration.ITALIC, false),
                    Msg.mm("<dark_gray>在剑冢挑战复刻时，每张抵 10% 挑战费（至多 5 张）。</dark_gray>")
                            .decoration(TextDecoration.ITALIC, false)));
            page.setItemMeta(meta);
        }
        return page;
    }

    /** 这件物品是不是残页（按 PDC 判定，玩家改名骗不过去） */
    public boolean isTornPage(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null
                && meta.getPersistentDataContainer().has(keyPage, PersistentDataType.BYTE);
    }

    /**
     * 数玩家背包（主背包 + 快捷栏）里一共有多少张残页。
     *
     * <p>静态入口：给 {@link TombManager} 的折扣插入点用，避免它反向依赖本服务的实例。</p>
     */
    public static int countPages(ForgingMasterPlugin plugin, Player player) {
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            total += pageCount(plugin, item);
        }
        return total;
    }

    /**
     * 从玩家背包扣 {@code want} 张残页（先清快捷栏再清背包，按堆叠从前往后扣）。
     *
     * @return 实际扣到的张数（可能少于 want，调用方按返回值打折）
     */
    public static int consumePages(ForgingMasterPlugin plugin, Player player, int want) {
        if (want <= 0) {
            return 0;
        }
        int taken = 0;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && taken < want; i++) {
            ItemStack item = contents[i];
            int n = pageCount(plugin, item);
            if (n <= 0) {
                continue;
            }
            int use = Math.min(n, want - taken);
            if (use >= item.getAmount()) {
                player.getInventory().setItem(i, null);
            } else {
                item.setAmount(item.getAmount() - use);
            }
            taken += use;
        }
        return taken;
    }

    /** 单件物品里的残页张数（0 = 不是残页） */
    private static int pageCount(ForgingMasterPlugin plugin, ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) {
            return 0;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return 0;
        }
        Byte flag = meta.getPersistentDataContainer()
                .get(pageKey(plugin), PersistentDataType.BYTE);
        return flag != null && flag == (byte) 1 ? item.getAmount() : 0;
    }

    private static NamespacedKey pageKey(ForgingMasterPlugin plugin) {
        return new NamespacedKey(plugin, "torn_page");
    }

    // ------------------------------------------------------------ 小工具

    private void give(Player player, ItemStack item) {
        Map<Integer, ItemStack> left = player.getInventory().addItem(item);
        for (ItemStack rest : left.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    /** 发一条带前缀的提示，并把同一段正文原样返回 */
    private String say(Player player, String body) {
        Msg.prefix(player, body);
        return body;
    }

    // ------------------------------------------------------------ 配置

    /** 秘境总开关 */
    public boolean enabled() {
        return plugin.settings().flag("tombrun.enabled", true);
    }

    /** 门票（剑币），0 = 免费 */
    public long ticketCoins() {
        return Math.max(0, plugin.settings().longOf("tombrun.ticket-coins", 300));
    }

    /** 波次表：tombrun.waves 的 count / mult，非法行跳过；全部非法返回空表（进不去） */
    public List<WaveDef> waves() {
        List<WaveDef> out = new ArrayList<>();
        for (Map<?, ?> raw : plugin.getConfig().getMapList("tombrun.waves")) {
            int count = raw.get("count") instanceof Number n ? n.intValue() : 0;
            double mult = raw.get("mult") instanceof Number n ? n.doubleValue() : 0;
            if (count >= 1 && mult > 0) {
                out.add(new WaveDef(Math.min(16, count), mult));
            }
        }
        return out;
    }

    /** 通关奖励剑币 */
    public long rewardCoins() {
        return Math.max(0, plugin.settings().longOf("tombrun.reward.coins", 800));
    }

    /** 通关奖励残页张数 */
    public int rewardPages() {
        return Math.max(0, plugin.settings().intOf("tombrun.reward.pages", 2));
    }

    /** 试炼总时限（秒），到点强制判负 */
    public int timeoutSeconds() {
        return Math.max(30, plugin.settings().intOf("tombrun.timeout-seconds", 180));
    }

    /** 守卫生成半径 */
    private int spawnRadius() {
        return Math.max(2, plugin.settings().intOf("tombrun.spawn-radius", 3));
    }

    /** 玩家离起跑点多远算「跑太远」 */
    private double leaveRadius() {
        return Math.max(4, plugin.settings().num("tombrun.leave-radius", 40));
    }

    /** 跑远 / 失踪多少秒后判负 */
    private int awaySeconds() {
        return Math.max(1, plugin.settings().intOf("tombrun.away-seconds", 10));
    }

    /** 守卫血量上限（mult 放大后的兜底） */
    private double maxHealth() {
        return Math.max(1, plugin.settings().num("tombrun.max-health", 400));
    }

    /** 守卫攻击上限 */
    private double maxAttack() {
        return Math.max(1, plugin.settings().num("tombrun.max-attack", 40));
    }

    /** 每级强化给守卫加的倍率（与 tomb.challenge 同义，秘境独立配） */
    private double levelPerLevel() {
        return Math.max(0, plugin.settings().num("tombrun.level-per-level", 0.08));
    }

    /** 原剑攻击折成守卫攻击的系数 */
    private double attackScale() {
        return Math.max(0.01, plugin.settings().num("tombrun.attack-scale", 0.55));
    }
}
