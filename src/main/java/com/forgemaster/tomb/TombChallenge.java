package com.forgemaster.tomb;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.gem.GemType;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.quality.Quality;
import com.forgemaster.strengthen.Charm;
import com.forgemaster.util.Keys;
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
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 剑冢残影挑战：把「前主人的剑」变成一场真刀真枪的架。
 *
 * <p>剑冢原本只是陈列柜——埋进去的剑躺着，只能看和取回。这里给每条记录召唤一只
 * <b>残影</b>：按原剑的材料档位挑敌对生物，按原剑的强化等级与品质放大血量与攻击，
 * 打赢了按比例返还原剑的耗材（外带一点宝石 / 护胚符的彩头），打输或超时无惩罚。</p>
 *
 * <h2>流程</h2>
 * <ol>
 *   <li>{@link #challenge(Player, String)}：校验（开关 / 条目存在 / 不是自己埋的 / 没有进行中的挑战 / 冷却），
 *       在玩家附近找一块安全落脚点生成残影，打标记并登记；</li>
 *   <li>每秒 {@link #tick()}：玩家离线 / 死亡 / 跑远超过 {@code leave-radius} 开始计时，
 *       满 {@code away-seconds}（默认 60 秒）就回收残影并清掉这场挑战；</li>
 *   <li>{@link EntityDeathEvent} 里判定胜负：清空原版掉落与经验，按比例发奖，写冷却，
 *       按 {@code tomb.challenge.consume} 决定要不要把剑冢条目一起消耗掉。</li>
 * </ol>
 *
 * <h2>冷却</h2>
 * <p>冷却状态<b>只存内存</b>（{@code cooldowns}），重启即丢——不值得为它落盘，
 * 也不该因为重启把玩家卡一整天。默认 {@code tomb.challenge.cooldown-hours = 24}，
 * 设 0 等于不限次。默认只在「打赢」时写冷却：打输 / 超时只是结束这一场，
 * 可以立刻再战（{@code tomb.challenge.cooldown-on-loss} 可改成输了也进冷却）。</p>
 *
 * <h2>标记与清理</h2>
 * <p>残影身上同时打记分板标签 {@link #TAG} 与 PDC 键（tomb / 玩家），
 * 回收一律「按标记找、按标记删」，绝不碰玩家自己的宠物与其它实体。</p>
 */
public final class TombChallenge implements Listener {

    /** 残影的记分板标签（回收时唯一的判定依据） */
    public static final String TAG = "forgemaster_tomb_shadow";

    /** 一场进行中的挑战 */
    private static final class Active {
        final UUID player;
        final String tombId;
        final UUID entity;
        final long startedAt;
        /** 玩家离开 / 死亡 / 跑远的起始时刻，0 = 人就在残影边上 */
        long awaySince;
        /** 拿不到实体（区块卸载等）的起始时刻，0 = 拿得到 */
        long missingSince;

        Active(UUID player, String tombId, UUID entity, long startedAt) {
            this.player = player;
            this.tombId = tombId;
            this.entity = entity;
            this.startedAt = startedAt;
        }
    }

    private final ForgingMasterPlugin plugin;
    private final TombManager tombs;
    private final Random random = new Random();

    /** 每人至多一场：key = 玩家 uuid */
    private final Map<UUID, Active> active = new LinkedHashMap<>();
    /** 冷却：key = 玩家 uuid → (剑冢编号 → 解禁时刻毫秒)。仅内存，重启即丢 */
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    private final NamespacedKey keyShadow;
    private final NamespacedKey keyTomb;
    private final NamespacedKey keyPlayer;

    private BukkitTask ticker;
    private boolean listening;

    public TombChallenge(ForgingMasterPlugin plugin, TombManager tombs) {
        this.plugin = plugin;
        this.tombs = tombs;
        this.keyShadow = new NamespacedKey(plugin, "tomb_shadow");
        this.keyTomb = new NamespacedKey(plugin, "tomb_shadow_tomb");
        this.keyPlayer = new NamespacedKey(plugin, "tomb_shadow_player");
        // 构造发生在 onEnable 里（TombManager 的构造），在这里挂监听与心跳不算「onEnable 之外的副作用」
        ensureListener();
        start();
        // 上一次崩服 / 被强杀可能把残影留在世界里：开服一秒后按标记清一遍（只认自己的标记）
        Bukkit.getScheduler().runTaskLater(plugin, this::removeAllTagged, 40L);
    }

    // ------------------------------------------------------------ 公开 API

    /**
     * 发起挑战：在玩家附近召唤这条剑冢记录的残影。
     *
     * <p>返回的是<b>已经发给玩家的那条提示</b>的 MiniMessage 原文（不带 {@code Msg.PREFIX}），
     * 方便命令层打日志或做自检断言。提示已经由本方法发出，命令层不必再发一次，
     * 重复发送会在聊天栏出现两条一样的话。</p>
     *
     * @param player 挑战者
     * @param tombId 剑冢编号（大小写不敏感）
     * @return MiniMessage 结果文本（校验失败时是失败原因）
     */
    public String challenge(Player player, String tombId) {
        if (player == null) {
            return "<red>只有玩家能召唤残影。</red>";
        }
        ensureListener();
        start();

        TombManager.Tomb t = tombs.get(tombId);
        if (t == null) {
            return say(player, "<red>剑冢里没有编号 <white>" + tombId + "</white> 的剑。</red>");
        }
        if (!enabled()) {
            return say(player, "<red>剑冢残影挑战没开（<white>tomb.challenge.enabled = false</white>）。</red>");
        }
        if (t.owner.equals(player.getUniqueId())) {
            return say(player, "<red>这是你自己埋下的剑。要拿回去请用 <white>/zhujian tomb take "
                    + t.id + "</white>。</red>");
        }
        Active running = active.get(player.getUniqueId());
        if (running != null) {
            return say(player, "<red>你还有一场残影没了结（编号 <white>" + running.tombId
                    + "</white>），先分出胜负再谈别的。</red>");
        }
        long cd = cooldownRemainingMs(player, t.id);
        if (cd > 0) {
            return say(player, "<red>「<#ffd166>" + t.swordName + "</#ffd166>」的残影还没凝回来，"
                    + "还要等 <white>" + duration(cd) + "</white>。</red>");
        }

        Location spot = findSpot(player);
        if (spot == null) {
            return say(player, "<red>附近找不到能让残影站住的地方，换个开阔点的地方再试。</red>");
        }

        int level = levelOf(t);
        double coef = qualityCoefOf(t.quality);
        EntityType type = mobOf(t);
        double health = clampValue(
                scaledHealth(baseHealthOf(type), level, coef, levelPerLevel()), maxHealth());
        double attack = clampValue(
                scaledAttack(swordAttackOf(t, coef), level, coef, levelPerLevel(), attackScale()), maxAttack());

        Mob shadow = spawnShadow(player, t, spot, type, level, health, attack);
        if (shadow == null) {
            return say(player, "<red>残影没能凝成形，稍后再试。</red>");
        }
        active.put(player.getUniqueId(), new Active(player.getUniqueId(), t.id, shadow.getUniqueId(),
                System.currentTimeMillis()));
        // 「挑战 N 次」在这里记，跟胜负无关；「斩影 N 次」只在死亡结算里加
        t.challenges++;
        tombs.markDirty(); // 审计 #8：标脏交异步冲刷

        player.playSound(player.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 0.6f);
        player.playSound(player.getLocation(), Sound.BLOCK_SOUL_SOIL_BREAK, 1.0f, 0.7f);
        spot.getWorld().spawnParticle(Particle.SOUL, spot.clone().add(0, 1, 0), 40, 0.5, 0.7, 0.5, 0.02);

        return say(player, "<gray>残影气息：<" + qualityColor(t.quality) + ">+" + level + " "
                + swordLabel(t) + "</" + qualityColor(t.quality) + ">"
                + " <dark_gray>·</dark_gray> <gray>" + mobLabel(type)
                + " <dark_gray>·</dark_gray> <gray>血量 <white>" + Msg.fmt(health, 0)
                + "</white> <dark_gray>·</dark_gray> <gray>攻击 <white>" + Msg.fmt(attack, 1) + "</white>");
    }

    /**
     * 是否可以挑战：开关、条目存在、不是自己埋的、没有进行中的挑战、不在冷却里。
     *
     * <p>纯查询，不产生任何副作用（不生成实体、不发消息）。</p>
     */
    public boolean canChallenge(Player player, String tombId) {
        if (player == null || !enabled()) {
            return false;
        }
        TombManager.Tomb t = tombs.get(tombId);
        if (t == null || t.owner.equals(player.getUniqueId())) {
            return false;
        }
        if (active.containsKey(player.getUniqueId())) {
            return false;
        }
        return cooldownRemainingMs(player, t.id) <= 0;
    }

    /**
     * 距离这个人能再次挑战这条剑冢还剩多少毫秒，0 = 现在就能打。
     *
     * <p>{@code tomb.challenge.cooldown-hours} 为 0 时恒返回 0（不限次）。
     * 冷却记在内存里，重启后所有人立刻可打——这是刻意的，不在注释外另做持久化。</p>
     */
    public long cooldownRemainingMs(Player player, String tombId) {
        if (player == null || tombId == null || cooldownHours() <= 0) {
            return 0;
        }
        Map<String, Long> mine = cooldowns.get(player.getUniqueId());
        if (mine == null) {
            return 0;
        }
        Long until = mine.get(tombId.trim().toUpperCase(Locale.ROOT));
        if (until == null) {
            return 0;
        }
        long left = cooldownRemaining(until, System.currentTimeMillis());
        if (left <= 0) {
            mine.remove(tombId.trim().toUpperCase(Locale.ROOT));
            if (mine.isEmpty()) {
                cooldowns.remove(player.getUniqueId());
            }
            return 0;
        }
        return left;
    }

    /** 冷却剩余毫秒（纯函数，自检可以直接喂时间戳） */
    public static long cooldownRemaining(long until, long now) {
        return Math.max(0, until - now);
    }

    /** 从现在起 {@code hours} 小时后的解禁时刻；{@code hours ≤ 0} 返回 now（等于不冷却） */
    public static long cooldownUntil(long now, double hours) {
        if (hours <= 0) {
            return now;
        }
        return now + (long) (hours * 3_600_000L);
    }

    /** 进行中的挑战数（自检用） */
    public int activeCount() {
        return active.size();
    }

    /** 清掉所有残影与心跳任务（onDisable 调） */
    public void shutdown() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        active.clear();
        cooldowns.clear();
        removeAllTagged();
    }

    // ------------------------------------------------------------ 调度

    private void start() {
        if (ticker != null) {
            return;
        }
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
    }

    /** 挂死亡监听：只挂一次；非主线程留给下次 challenge() 再补 */
    private void ensureListener() {
        if (listening || !Bukkit.isPrimaryThread()) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        listening = true;
    }

    /** 每秒扫一遍：玩家离开 / 死亡 / 跑远超时就回收 */
    private void tick() {
        if (active.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Active> snapshot = new ArrayList<>(active.values());
        for (Active a : snapshot) {
            Entity shadow = entityOf(a);
            if (shadow == null) {
                // 区块卸载 / 被别的插件清场：先记着，超过宽限期就当残影已经散了
                if (a.missingSince == 0) {
                    a.missingSince = now;
                } else if (now - a.missingSince >= awaySeconds() * 1000L) {
                    finish(a, null, "残影失了踪迹");
                }
                continue;
            }
            a.missingSince = 0;

            Player p = Bukkit.getPlayer(a.player);
            boolean away = p == null || !p.isOnline() || p.isDead()
                    || !p.getWorld().equals(shadow.getWorld())
                    || p.getLocation().distanceSquared(shadow.getLocation())
                    > leaveRadius() * leaveRadius();
            if (away) {
                if (a.awaySince == 0) {
                    a.awaySince = now;
                } else if (now - a.awaySince >= awaySeconds() * 1000L) {
                    finish(a, p, "残影散了");
                }
                continue;
            }
            a.awaySince = 0;
            // 人还在场，把仇恨按回挑战者身上，免得残影跑去打路人
            if (shadow instanceof Mob mob && !p.equals(mob.getTarget())) {
                mob.setTarget(p);
            }
        }
    }

    /** 回收：删实体、清登记、给一句提示（默认不罚，冷却只在赢的时候写） */
    private void finish(Active a, Player p, String reason) {
        Entity shadow = entityOf(a);
        if (shadow != null) {
            shadow.remove();
        }
        active.remove(a.player);
        boolean punished = false;
        if (cooldownOnLoss() && cooldownHours() > 0) {
            markCooldown(a.player, a.tombId);
            punished = true;
        }
        if (p != null && p.isOnline()) {
            p.sendActionBar(Msg.mm("<gray>" + reason + " <dark_gray>· 无惩罚"));
            Msg.prefix(p, "<gray>" + reason + "（<white>" + a.tombId + "</white>），无惩罚"
                    + (punished ? "，冷却已记上。" : "，冷却也没写——想再打随时来。") + "</gray>");
        }
    }

    // ------------------------------------------------------------ 生成

    private Mob spawnShadow(Player player, TombManager.Tomb t, Location spot, EntityType type,
                            int level, double health, double attack) {
        World world = spot.getWorld();
        if (world == null) {
            return null;
        }
        Entity raw;
        try {
            raw = world.spawnEntity(spot, type);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("剑冢残影生成失败（" + type + "）：" + ex.getMessage());
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
        pdc.set(keyTomb, PersistentDataType.STRING, t.id);
        pdc.set(keyPlayer, PersistentDataType.STRING, player.getUniqueId().toString());

        // v2.23 残影词缀：掷 0~2 条残影词缀写进 PDC（PDC 随实体走，死亡自然失效）
        List<String> affixIds = tombs.guardAffixes().apply(mob);

        mob.setPersistent(true);
        mob.setRemoveWhenFarAway(false);
        mob.setCanPickupItems(false);
        mob.setAware(true);
        mob.customName(Msg.mm("<gray>" + t.ownerName + " 的残影 <dark_gray>·</dark_gray> <#ffd166>+"
                + level + "</#ffd166>" + tombs.guardAffixes().affixTag(affixIds))
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

        // 装备只用「同材质的普通物品 + 名字」：真实原剑会掉出来，绝对不放
        EntityEquipment equipment = mob.getEquipment();
        if (equipment != null) {
            ItemStack blade = new ItemStack(bladeMaterial(t));
            ItemMeta meta = blade.getItemMeta();
            if (meta != null) {
                meta.displayName(Msg.mm("<#d8d8d8>" + t.swordName + "</#d8d8d8>")
                        .decoration(TextDecoration.ITALIC, false));
                blade.setItemMeta(meta);
            }
            equipment.setItemInMainHand(blade);
            equipment.setItemInMainHandDropChance(0f);
        }

        mob.setTarget(player);
        return mob;
    }

    /** 附近找一块安全落脚点：上方两格能站人、脚下是实心方块 */
    private Location findSpot(Player player) {
        World world = player.getWorld();
        Location base = player.getLocation();
        int radius = spawnRadius();
        int surfaceY = world.getHighestBlockYAt(base.getBlockX(), base.getBlockZ());
        boolean outdoor = Math.abs(surfaceY - base.getBlockY()) <= 8;

        for (int i = 0; i < 32; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
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

    // ------------------------------------------------------------ 胜负

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShadowDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (!dead.getScoreboardTags().contains(TAG)) {
            return; // 不是残影：原样放行，不碰别的东西
        }
        // 原版战利品与经验一律不给，奖励统一由插件发
        event.getDrops().clear();
        event.setDroppedExp(0);

        Active a = activeOf(dead.getUniqueId());
        if (a == null) {
            return;
        }
        active.remove(a.player);

        Player player = Bukkit.getPlayer(a.player);
        TombManager.Tomb t = tombs.get(a.tombId);
        if (t != null) {
            // 「挑战次数」在召唤时就记过了，这里只加胜场，否则一次召唤会算两次
            t.wins++;
            tombs.markDirty(); // 审计 #8：标脏交异步冲刷
        }
        markCooldown(a.player, a.tombId);

        if (player == null || !player.isOnline()) {
            return; // 人不在线：冷却照算，奖励不发（没人可发）
        }
        if (t == null) {
            return;
        }

        List<String> got = grantRewards(player, t);
        if (plugin.spectrum() != null) plugin.spectrum().countTombWin(player); // 名剑谱：剑冢胜场登记
        if (plugin.seasonPass() != null) plugin.seasonPass().earn(player.getUniqueId(), "tomb-win"); // 赛季通行证：剑冢胜利登记
        // v2.23 拓展 C：剑冢胜利 → Webhook
        plugin.broadcast(com.forgemaster.webhook.WebhookEvent.TOMB_WIN,
                "【剑冢】" + player.getName() + " 斩下残影「" + t.swordName + "」",
                java.util.Map.of("player", player.getName(),
                        "sword", String.valueOf(t.swordName),
                        "id", String.valueOf(t.id)));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.2f);
        String loot = got.isEmpty() ? "<dark_gray>什么也没留下</dark_gray>"
                : "<dark_gray>[</dark_gray>" + String.join("<dark_gray> + </dark_gray>", got)
                + "<dark_gray>]</dark_gray>";
        Msg.prefix(player, "<green>残影已斩。</green> <gray>「<#ffd166>" + t.swordName
                + "</#ffd166>」的记忆散了，留下 " + loot);

        if (consume()) {
            tombs.adminRemove(t.id);
            Msg.prefix(player, "<dark_gray>这条剑冢记录已随残影一同散去。</dark_gray>");
        }

        if (broadcast()) {
            Bukkit.broadcast(Msg.mm(Msg.PREFIX + "<gray>" + player.getName() + " 斩下了「<#ffd166>"
                    + t.swordName + "</#ffd166>」的残影（+" + levelOf(t) + " · "
                    + qualityColored(t.quality) + "）。"));
        }
    }

    /**
     * 按原剑的材料与品质发一部分耗材，外加概率彩头。
     *
     * <p>每一档耗材按 {@code rate}（默认 40%~60%，随强化等级线性抬高）折算，
     * 取整后为 0 的档位不发；全部为 0 时兜底给第一档 1 个，免得白打一场。</p>
     *
     * @return 给玩家看的到手清单（MiniMessage 片段）
     */
    private List<String> grantRewards(Player player, TombManager.Tomb t) {
        List<String> got = new ArrayList<>();
        ForgeMaterial mat = materialOf(t);
        int level = levelOf(t);
        double rate = rewardRate(level,
                plugin.settings().num("tomb.challenge.reward.rate-base", 0.40),
                plugin.settings().num("tomb.challenge.reward.rate-per-level", 0.02),
                plugin.settings().num("tomb.challenge.reward.rate-max", 0.60));

        Map<Material, Integer> costs = new LinkedHashMap<>();
        if (mat != null) {
            costs.putAll(mat.costs());
        }
        boolean any = false;
        for (Map.Entry<Material, Integer> e : costs.entrySet()) {
            int amount = rewardAmount(e.getValue(), rate);
            if (amount <= 0) {
                continue;
            }
            give(player, new ItemStack(e.getKey(), amount));
            got.add("<white>" + amount + "</white> 个 <white>" + pretty(e.getKey()) + "</white>");
            any = true;
        }
        if (!any) {
            Material first = mat != null ? mat.costItem()
                    : (costs.isEmpty() ? Material.IRON_INGOT : costs.keySet().iterator().next());
            give(player, new ItemStack(first, 1));
            got.add("<white>1</white> 个 <white>" + pretty(first) + "</white>");
        }

        if (random.nextDouble() < bonusChance()) {
            double charmWeight = plugin.settings().num("tomb.challenge.reward.charm-weight", 0.5);
            if (random.nextDouble() < charmWeight) {
                give(player, Charm.create(plugin, Charm.TALISMAN));
                got.add("<dark_gray> + </dark_gray><#9f7aea>护胚符");
            } else {
                GemType type = GemType.values()[random.nextInt(GemType.values().length)];
                GemType.Grade grade = GemType.Grade.roll(random);
                give(player, plugin.gems().createGem(type, grade));
                got.add("<dark_gray> + </dark_gray>" + type.colored() + " <dark_gray>("
                        + grade.colored() + "<dark_gray>)");
            }
        }
        return got;
    }

    private void markCooldown(UUID player, String tombId) {
        double hours = cooldownHours();
        if (hours <= 0) {
            return;
        }
        cooldowns.computeIfAbsent(player, k -> new HashMap<>())
                .put(tombId.trim().toUpperCase(Locale.ROOT),
                        cooldownUntil(System.currentTimeMillis(), hours));
    }

    // ------------------------------------------------------------ 残影档案

    /** 这条记录碎在 / 强化到 +几：优先 PDC，碎剑留档退回 brokenAt */
    public int levelOf(TombManager.Tomb t) {
        if (t == null) {
            return 0;
        }
        int level = 0;
        ItemStack snap = t.snapshot;
        if (snap != null) {
            ItemMeta meta = snap.getItemMeta();
            if (meta != null) {
                level = meta.getPersistentDataContainer()
                        .getOrDefault(Keys.STRENGTHEN, PersistentDataType.INTEGER, 0);
            }
        }
        if (t.broken) {
            level = Math.max(level, t.brokenAt);
        }
        return Math.max(0, level);
    }

    /** 原剑的胚子：先按快照反查，查不到退回记录里的材料 id */
    public ForgeMaterial materialOf(TombManager.Tomb t) {
        if (t == null) {
            return null;
        }
        ForgeMaterial mat = null;
        if (t.snapshot != null) {
            mat = plugin.strengthen().materialOf(t.snapshot);
        }
        if (mat == null) {
            mat = plugin.materials().get(t.materialId);
        }
        return mat;
    }

    /** 材料档位：查得到胚子用胚子的 tier，查不到按 id 兜底 */
    public int tierOf(TombManager.Tomb t) {
        ForgeMaterial mat = materialOf(t);
        return mat == null ? defaultTier(t == null ? null : t.materialId) : mat.tier();
    }

    /** 原剑的攻击底子 = 默认剑基准 + 快照里的 BASE_ATK（含品质倍率） */
    public double swordAttackOf(TombManager.Tomb t, double qualityCoef) {
        double base = plugin.settings().num("tomb.challenge.sword-base-attack", 6.0);
        double extra = 0;
        if (t != null && t.snapshot != null) {
            ItemMeta meta = t.snapshot.getItemMeta();
            if (meta != null) {
                extra = meta.getPersistentDataContainer()
                        .getOrDefault(Keys.BASE_ATK, PersistentDataType.DOUBLE, 0.0);
            }
        }
        if (extra <= 0) {
            ForgeMaterial mat = materialOf(t);
            extra = mat == null ? 0 : mat.bonusAttack() * Math.max(0.1, qualityCoef);
        }
        return Math.max(1.0, base + extra);
    }

    /** 这条记录的残影是谁（读配置映射） */
    public EntityType mobOf(TombManager.Tomb t) {
        ForgeMaterial mat = materialOf(t);
        String id = mat == null ? (t == null ? "iron" : t.materialId) : mat.id();
        int tier = mat == null ? defaultTier(id) : mat.tier();
        int safeTier = Math.max(1, Math.min(5, tier));
        EntityType fallback = parseEntity(defaultMobKey(safeTier), EntityType.ZOMBIE);
        return parseEntity(mobKeyOf(id, safeTier), fallback);
    }

    /** 生物类型键名：先看按材料的覆盖，再看按档位的覆盖，最后用内置默认 */
    public String mobKeyOf(String materialId, int tier) {
        int safeTier = Math.max(1, Math.min(5, tier));
        String def = defaultMobKey(safeTier);
        if (materialId != null && !materialId.isBlank()) {
            String raw = plugin.settings().str(
                    "tomb.challenge.mobs." + materialId.trim().toLowerCase(Locale.ROOT), "");
            if (raw != null && !raw.isBlank()) {
                return raw.trim().toUpperCase(Locale.ROOT);
            }
        }
        String raw = plugin.settings().str("tomb.challenge.mobs." + safeTier, "");
        if (raw != null && !raw.isBlank()) {
            return raw.trim().toUpperCase(Locale.ROOT);
        }
        return def;
    }

    /** 剑冢详情里的一行预估（给 info 命令用） */
    public String previewLine(TombManager.Tomb t) {
        if (t == null || !enabled()) {
            return "";
        }
        int level = levelOf(t);
        double coef = qualityCoefOf(t.quality);
        EntityType type = mobOf(t);
        double health = clampValue(scaledHealth(baseHealthOf(type), level, coef, levelPerLevel()), maxHealth());
        double attack = clampValue(
                scaledAttack(swordAttackOf(t, coef), level, coef, levelPerLevel(), attackScale()), maxAttack());
        return "<gray>残影: <white>" + mobLabel(type) + "</white> <dark_gray>·</dark_gray> <gray>血量 <white>"
                + Msg.fmt(health, 0) + "</white> <dark_gray>·</dark_gray> <gray>攻击 <white>"
                + Msg.fmt(attack, 1) + "</white>";
    }

    // ------------------------------------------------------------ 纯逻辑（自检可测）

    /** 档位的默认生物：铁→僵尸、铜→尸壳、钻石→凋灵骷髅、下界合金→猪灵蛮兵、星陨→监守者 */
    public static String defaultMobKey(int tier) {
        return switch (Math.max(1, Math.min(5, tier))) {
            case 1 -> "ZOMBIE";
            case 2 -> "HUSK";
            case 3 -> "WITHER_SKELETON";
            case 4 -> "PIGLIN_BRUTE";
            default -> "WARDEN";
        };
    }

    /** 材料 id 的兜底档位（配置里读不到胚子时用） */
    public static int defaultTier(String materialId) {
        String id = materialId == null ? "" : materialId.trim().toLowerCase(Locale.ROOT);
        return switch (id) {
            case "gold", "iron" -> 1;
            case "copper" -> 2;
            case "diamond" -> 3;
            case "netherite" -> 4;
            case "starfall" -> 5;
            default -> 1;
        };
    }

    /** 品质系数：直接用 {@link Quality#power()}（粗糙 0.55 → 神话 1.75） */
    public static double qualityCoefOf(String qualityRaw) {
        Quality q = Quality.byName(qualityRaw);
        return q == null ? Quality.COMMON.power() : q.power();
    }

    /**
     * 残影血量 = 生物基础血量 × (1 + 强化等级 × perLevel) × 品质系数。
     *
     * <p>例：僵尸 20 血、+10、品质系数 1.0、perLevel 0.08 → 20 × 1.8 × 1.0 = 36。</p>
     */
    public static double scaledHealth(double baseHealth, int level, double qualityCoef, double perLevel) {
        double lv = 1 + Math.max(0, level) * Math.max(0, perLevel);
        double q = qualityCoef <= 0 ? 1.0 : qualityCoef;
        return Math.max(1, baseHealth * lv * q);
    }

    /**
     * 残影攻击 = 原剑攻击底子 × (1 + 强化等级 × perLevel) × 品质系数 × attackScale。
     *
     * <p>原剑攻击底子见 {@link #swordAttackOf}；attackScale 默认 0.55，
     * 把「玩家一刀的伤害」折成「怪物一击的伤害」，免得残影一刀秒人。</p>
     */
    public static double scaledAttack(double swordAttack, int level, double qualityCoef,
                                      double perLevel, double attackScale) {
        double lv = 1 + Math.max(0, level) * Math.max(0, perLevel);
        double q = qualityCoef <= 0 ? 1.0 : qualityCoef;
        double scale = attackScale <= 0 ? 1.0 : attackScale;
        return Math.max(1, swordAttack * lv * q * scale);
    }

    /** 发材料比例：base + 等级 × perLevel，超过 max 就按 max 收（max ≤ 0 表示不封顶） */
    public static double rewardRate(int level, double base, double perLevel, double max) {
        double raw = Math.max(0, base) + Math.max(0, level) * Math.max(0, perLevel);
        if (max > 0) {
            raw = Math.min(max, raw);
        }
        return Math.max(0, raw);
    }

    /** 单项材料折算：round(原量 × 比例)，夹在 [0, 原量] */
    public static int rewardAmount(int original, double rate) {
        if (original <= 0 || rate <= 0) {
            return 0;
        }
        int amount = (int) Math.round(original * rate);
        return Math.max(0, Math.min(original, amount));
    }

    /** 上限夹取 */
    public static double clampValue(double value, double max) {
        if (max <= 0) {
            return Math.max(0.0, value);
        }
        return Math.max(0.0, Math.min(max, value));
    }

    /** 把配置里写的生物名转成 EntityType，非生物 / 认不出就退回 fallback */
    public static EntityType parseEntity(String raw, EntityType fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            EntityType type = EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            Class<? extends Entity> cls = type.getEntityClass();
            if (cls == null || !LivingEntity.class.isAssignableFrom(cls)) {
                return fallback;
            }
            return type;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    /** 生物基础血量（不生成实体，直接读注册表里的默认属性） */
    public static double baseHealthOf(EntityType type) {
        if (type == null) {
            return 20;
        }
        try {
            if (!type.hasDefaultAttributes()) {
                return 20;
            }
            var defaults = type.getDefaultAttributes();
            if (defaults != null) {
                AttributeInstance inst = defaults.getAttribute(Attribute.MAX_HEALTH);
                if (inst != null && inst.getBaseValue() > 0) {
                    return inst.getBaseValue();
                }
            }
        } catch (RuntimeException ex) {
            // 认不出的类型按 20 血兜底，不影响开打
        }
        return 20;
    }

    /** 生物的中文名（消息里好看一点） */
    public static String mobLabel(EntityType type) {
        if (type == null) {
            return "残影";
        }
        return switch (type) {
            case ZOMBIE -> "僵尸";
            case HUSK -> "尸壳";
            case WITHER_SKELETON -> "凋灵骷髅";
            case PIGLIN_BRUTE -> "猪灵蛮兵";
            case WARDEN -> "监守者";
            default -> type.name();
        };
    }

    // ------------------------------------------------------------ 小工具

    private Entity entityOf(Active a) {
        Entity e = Bukkit.getEntity(a.entity);
        if (e == null || !e.isValid()) {
            return null;
        }
        return e.getScoreboardTags().contains(TAG) ? e : null;
    }

    private Active activeOf(UUID entityId) {
        for (Active a : active.values()) {
            if (a.entity.equals(entityId)) {
                return a;
            }
        }
        return null;
    }

    private void removeAllTagged() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (e.getScoreboardTags().contains(TAG)) {
                    e.remove(); // 只删带我们标记的，玩家自己的宠物一个不碰
                }
            }
        }
    }

    /**
     * 区块重新加载时清孤儿残影。
     *
     * <p>残影是 {@code setPersistent(true)} + {@code setRemoveWhenFarAway(false)} 的，
     * 区块卸载后它不会自然消失，下次加载就原地复活；而 {@link #removeAllTagged()} 只能
     * 扫到已加载区块，崩服留下的残影永远扫不掉。这里按「有没有人正在挑战」判定：
     * 带 TAG 却不在 {@code active} 登记里的，就是孤儿。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
        for (Entity e : event.getChunk().getEntities()) {
            if (!e.getScoreboardTags().contains(TAG)) {
                continue;
            }
            if (isTracked(e.getUniqueId())) {
                continue;
            }
            e.remove();
            plugin.getLogger().fine("清理孤儿残影 " + e.getUniqueId());
        }
    }

    /** 这个实体是不是某场进行中的挑战的残影 */
    private boolean isTracked(UUID entityId) {
        for (Active a : active.values()) {
            if (a.entity.equals(entityId)) {
                return true;
            }
        }
        return false;
    }

    private Material bladeMaterial(TombManager.Tomb t) {
        ForgeMaterial mat = materialOf(t);
        if (mat != null && mat.item() != null && mat.item().isItem()) {
            return mat.item();
        }
        return Material.IRON_SWORD;
    }

    private String swordLabel(TombManager.Tomb t) {
        ForgeMaterial mat = materialOf(t);
        String name = mat == null ? t.materialId : mat.id();
        return t.swordName == null || t.swordName.isBlank() ? name : t.swordName;
    }

    private String qualityColor(String raw) {
        Quality q = Quality.byName(raw);
        return (q == null ? Quality.COMMON : q).color();
    }

    private String qualityColored(String raw) {
        Quality q = Quality.byName(raw);
        Quality use = q == null ? Quality.COMMON : q;
        return "<color:" + use.color() + ">" + use.display() + "</color>";
    }

    private static String pretty(Material mat) {
        return com.forgemaster.material.MaterialGrades.pretty(mat);
    }

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

    private String duration(long ms) {
        long total = Math.max(0, ms) / 1000L;
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        StringBuilder sb = new StringBuilder();
        if (hours > 0) {
            sb.append(hours).append(" 小时 ");
        }
        if (hours > 0 || minutes > 0) {
            sb.append(minutes).append(" 分 ");
        }
        sb.append(seconds).append(" 秒");
        return sb.toString().trim();
    }

    // ------------------------------------------------------------ 配置

    /** 残影挑战总开关 */
    public boolean enabled() {
        return plugin.settings().flag("tomb.challenge.enabled", true) && tombs.enabled();
    }

    /** 每级强化给残影加的倍率（血量与攻击共用） */
    private double levelPerLevel() {
        return Math.max(0, plugin.settings().num("tomb.challenge.level-per-level", 0.08));
    }

    /** 原剑攻击折成残影攻击的系数 */
    private double attackScale() {
        return Math.max(0.01, plugin.settings().num("tomb.challenge.attack-scale", 0.55));
    }

    /** 血量上限（监守者这类 500 血的优先挨这一刀） */
    private double maxHealth() {
        return Math.max(1, plugin.settings().num("tomb.challenge.max-health", 300));
    }

    /** 攻击上限 */
    private double maxAttack() {
        return Math.max(1, plugin.settings().num("tomb.challenge.max-attack", 40));
    }

    /** 召唤半径（默认对齐 config.yml 发布值 3） */
    private int spawnRadius() {
        return Math.max(2, plugin.settings().intOf("tomb.challenge.spawn-radius", 3));
    }

    /** 玩家离残影多远算「跑远了」（默认对齐 config.yml 发布值 40） */
    private double leaveRadius() {
        return Math.max(4, plugin.settings().num("tomb.challenge.leave-radius", 40));
    }

    /** 玩家离线 / 死亡 / 跑远多少秒后回收残影 */
    private int awaySeconds() {
        return Math.max(1, plugin.settings().intOf("tomb.challenge.away-seconds", 60));
    }

    /** 冷却小时数，0 = 不限 */
    private double cooldownHours() {
        return Math.max(0, plugin.settings().num("tomb.challenge.cooldown-hours", 24));
    }

    /** 打赢是否把这条剑冢记录一起消耗掉 */
    private boolean consume() {
        return plugin.settings().flag("tomb.challenge.consume", false);
    }

    /** 打赢是否全服播报 */
    private boolean broadcast() {
        return plugin.settings().flag("tomb.challenge.broadcast", true);
    }

    /** 打赢额外给彩头的概率（默认 15%） */
    private double bonusChance() {
        return Math.max(0, Math.min(1, plugin.settings().num("tomb.challenge.reward.bonus-chance", 0.15)));
    }

    /** 传闻里「打输也进冷却」的开关：默认关闭，只记赢的那一场 */
    private boolean cooldownOnLoss() {
        return plugin.settings().flag("tomb.challenge.cooldown-on-loss", false);
    }
}
