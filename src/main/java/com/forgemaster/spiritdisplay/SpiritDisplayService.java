package com.forgemaster.spiritdisplay;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 剑灵显形（拓展第三档）：灵性达到第 3 档及以上、且剑灵已命名的成品剑，
 * 会在主手玩家肩侧显形为一枚 {@link ItemDisplay} 浮游体——剑的迷你展示
 * （缩放 {@code spirit.display.scale}，默认 0.35），绕玩家 0.8 格缓慢环绕：
 * 每 2 tick 更新一次 transformation（角度自旋 + 正弦浮动），
 * {@code Billboard.FIXED} 的朝向由环绕角度自己算（不依赖自动 billboard）。
 *
 * <p><b>档位成长（v2.23）</b>：显形倍数随灵性档位变大——当前档位每上一档，
 * 在 {@code spirit.display.scale} 的基础上再乘 {@code spirit.display.growth-per-tier}
 * （默认 1.15，钳 1.0~2.0，纯函数见 {@link #scaleForTier}）。SpiritManager 落账
 * 不会通知本模块，倍数由 40L 巡检周期重算：与上次记录的倍数比对，真变了才
 * {@code setTransformation}（先设 {@code interpolationDuration} 让长大是渐变不是跳变；
 * 倍数没动就完全不碰实体）。/zhujian spirit show|hide 走同一条 spawnFor 路径，
 * 倍数同样生效。</p>
 *
 * <p><b>包选择理由</b>：仿照 hall / spectrum / relicset 的惯例——每个拓展档独立成包。
 * 显形是实体密集型模块（生成 / 动画 / 巡检 / 五路事件清理），与 {@code spirit}
 * 包里刚做过性能修复的物品侧逻辑（PDC 节流落账、Lore 刷新）互不掺和，
 * 独立包让 SpiritManager 的命中热路径不再多背一块实体代码。</p>
 *
 * <p><b>效果加成</b>：显形期间给<b>主手那把剑</b>的 PDC 暴击率加
 * {@code spirit.display.bonus.crit-chance}（默认 +2%）——与
 * {@code SpiritManager.grantAffix} / {@code GemService} 的
 * {@link Keys#CRIT_CHANCE} PDC 汇总同口径（战斗侧 CombatListener 只读物品 PDC），
 * 写法同 GemService 的差量补：加成时 {@code += amount}，撤除时 {@code -= amount}，
 * 并在剑上打 {@link Keys#SPIRIT_DISPLAY_BONUS} 标记记下本次加成量，
 * 撤除时按标记逐件扣回（扫背包 + 副手 + 光标 + 死亡掉落列表），找不到就放弃、绝不扣到别的剑上。</p>
 *
 * <p><b>生命周期铁律</b>（对应 audit/02-stability.md 实体章节的全部教训）：
 * <ul>
 *   <li>实体一律 {@code setPersistent(false)}（不进存档，崩服 / 强杀不留幽灵，
 *       教训 #8 空投残留）+ {@code setInvulnerable(true)}（不被打死漏 loot）；</li>
 *   <li>PDC 打 {@link Keys#SPIRIT_DISPLAY} 标记（值 = 主人 UUID），与殿堂的
 *       HALL_DISPLAY、砧位的 ANVIL_SESSION 各用各的键（教训 #10：模块间互不认领）；</li>
 *   <li>玩家退服 / 换世界 / 死亡 → 事件里立即撤除并扣回加成（教训 #9：不能只靠巡检）；</li>
 *   <li>巡检任务 40L 间隔、玩家数 &gt; 0 才跑；其中每 30 秒做一次全量孤儿清扫
 *       （标记实体的 owner 不在线，或同主人出现重复实体 → 撤，教训 #8 的启动清扫思路）；</li>
 *   <li>onDisable 全撤（教训 #9：插件在演出窗口内被禁用也要能收干净），stop() 幂等；</li>
 *   <li>每玩家同时最多 1 个：生成前先查旧（active 表 + 兜底扫标记），旧撤新立。</li>
 * </ul></p>
 */
public final class SpiritDisplayService implements Listener {

    /** 巡检任务间隔（tick）：40L = 2 秒，同时负责清孤儿与旧撤新立 */
    private static final long PATROL_TICKS = 40L;
    /** 孤儿全量清扫的节奏：每 15 轮巡检 = 30 秒一轮 */
    private static final int SWEEP_EVERY = 15;
    /** 动画刷新间隔（tick）：每 2 tick 更新一次 transformation */
    private static final long ANIMATE_TICKS = 2L;
    /** 每次动画的自旋步进（弧度） */
    private static final float SPIN_STEP = 0.09f;
    /** 涨档时的插值时长（tick）：约 1 秒完成渐变长大，不跳变 */
    private static final int GROWTH_INTERP_TICKS = 20;
    /** 倍数比对精度：浮点误差之内视为「没变」，不重复 setTransformation */
    private static final float RESCALE_EPSILON = 1e-6f;

    private final ForgingMasterPlugin plugin;
    /** 巡检任务；stop() 置空，幂等 */
    private BukkitTask patrol;
    /** 每玩家最多一个活体显形记录 */
    private final Map<UUID, Active> active = new HashMap<>();
    /** 玩家用 /zhujian spirit hide 主动隐藏（本会话内不再自动显形），退服即清 */
    private final Set<UUID> hidden = new HashSet<>();
    /** 孤儿清扫计数器 */
    private int sweepCounter;

    /** 一个玩家的活体显形：实体 UUID + 动画任务 + 主手剑快照（旧撤新立的判据） */
    private static final class Active {
        final UUID display;
        final BukkitTask animator;
        final String swordSnapshot;
        float angle;
        /** 显形体的当前灵性档位（SpiritManager 落账不通知本模块，靠巡检重算记录） */
        int lastTier;
        /** 当前档位的显形倍数（动画每帧套用它，涨档时由巡检更新并触发插值） */
        float tierScale;

        Active(UUID display, BukkitTask animator, String swordSnapshot,
               int lastTier, float tierScale) {
            this.display = display;
            this.animator = animator;
            this.swordSnapshot = swordSnapshot;
            this.lastTier = lastTier;
            this.tierScale = tierScale;
        }
    }

    public SpiritDisplayService(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        // 与 SpiritManager 同款：没有独立 start 时序要求，构造即挂监听 + 起巡检
        Bukkit.getPluginManager().registerEvents(this, plugin);
        startPatrol();
    }

    // ------------------------------------------------------------ 配置

    /** 显形总开关（隐含要求剑灵系统本身开启） */
    public boolean displayEnabled() {
        return plugin.spirit() != null
                && plugin.spirit().enabled()
                && plugin.settings().flag("spirit.display.enabled", true);
    }

    private int minTier() {
        return Math.max(0, plugin.settings().intOf("spirit.display.min-tier", 3));
    }

    private boolean requireNamed() {
        return plugin.settings().flag("spirit.display.require-named", true);
    }

    private double orbitRadius() {
        return Math.max(0.3, Math.min(2.0, plugin.settings().num("spirit.display.orbit-radius", 0.8)));
    }

    private float scale() {
        double s = plugin.settings().num("spirit.display.scale", 0.35);
        return (float) Math.max(0.1, Math.min(1.0, s));
    }

    /**
     * 每上一档灵性档位的显示倍数（v2.23）：读 {@code spirit.display.growth-per-tier}，
     * 默认 1.15。这里只读原始值；钳制（1.0~2.0、非法值退化）统一收在纯函数
     * {@link #scaleForTier} 里，方便自检直接断言曲线。
     */
    private double growthPerTier() {
        return plugin.settings().num("spirit.display.growth-per-tier", 1.15);
    }

    private boolean bonusEnabled() {
        return plugin.settings().flag("spirit.display.bonus.enabled", true);
    }

    private double bonusCrit() {
        return Math.max(0, plugin.settings().num("spirit.display.bonus.crit-chance", 0.02));
    }

    // ------------------------------------------------------------ 生命周期

    private void startPatrol() {
        stopPatrol();
        patrol = Bukkit.getScheduler().runTaskTimer(plugin, this::patrol, PATROL_TICKS, PATROL_TICKS);
    }

    private void stopPatrol() {
        if (patrol != null) {
            patrol.cancel();
            patrol = null;
        }
    }

    /**
     * 关服 / 重载：停巡检 + 撤掉全部显形体并扣回加成。
     * 可安全重复调用（任务置空、map 清空后再次调用都是空操作）——幂等。
     */
    public void stop() {
        stopPatrol();
        removeAll();
        hidden.clear();
    }

    /** 撤掉所有显形体（onDisable / 配置关闭用）；在线玩家的加成一并差量扣回 */
    private void removeAll() {
        for (UUID id : new ArrayList<>(active.keySet())) {
            Active a = active.remove(id);
            if (a != null) {
                a.animator.cancel();
                Entity e = Bukkit.getEntity(a.display);
                if (e != null) {
                    e.remove();
                }
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    revertBonus(p);
                }
            }
        }
    }

    // ------------------------------------------------------------ 显形条件

    /** 主手这把剑是否够格显形：第 3 档及以上 +（可选）已命名 */
    private boolean qualifies(Player player, ItemStack sword) {
        if (!displayEnabled() || player == null
                || sword == null || sword.getType().isAir()
                || !plugin.items().isForged(sword)) {
            return false;
        }
        int tier = plugin.spirit().tierOf(plugin.spirit().spiritOf(sword));
        if (tier < minTier()) {
            return false;
        }
        return !requireNamed() || !plugin.spirit().nameOf(sword).isBlank();
    }

    /**
     * 主手剑快照（材质 + 显示名）：用于「切了别的剑」的旧撤新立判定。
     * 故意不含 PDC / 耐久——剑灵涨灵性、掉耐久都不该触发重建，否则战斗里每两秒闪一次。
     */
    private static String snapshotOf(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item.getType().name();
        }
        Component name = meta.displayName();
        return item.getType().name() + "|" + (name == null ? "" : Msg.plain(name));
    }

    // ------------------------------------------------------------ 巡检

    /**
     * 40L 巡检：玩家数 &gt; 0 才干活。
     * 每轮逐玩家核对显形条件，做「旧撤新立」；每 30 秒额外做一次全量孤儿清扫。
     */
    private void patrol() {
        if (!displayEnabled()) {
            // 配置关了 / 剑灵系统关了：全撤（map 空时是空操作）
            removeAll();
            return;
        }
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            return; // 没人就不跑，任务空转
        }
        if (++sweepCounter >= SWEEP_EVERY) {
            sweepCounter = 0;
            sweepOrphans();
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            Active a = active.get(id);
            if (hidden.contains(id)) {
                if (a != null) {
                    removeFor(id); // hide 之后还挂着就撤掉
                }
                continue;
            }
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (!qualifies(p, hand)) {
                if (a != null) {
                    removeFor(id); // 灵性降档 / 剑被收起 / 未命名：条件不再满足
                }
                continue;
            }
            if (a == null) {
                spawnFor(p, hand); // 条件达标且没有显形：立
                continue;
            }
            Entity d = Bukkit.getEntity(a.display);
            if (d == null || !d.isValid()) {
                // 实体丢了（区块卸载 / 被清）：旧记录作废重立
                removeFor(id);
                spawnFor(p, hand);
                continue;
            }
            if (!a.swordSnapshot.equals(snapshotOf(hand))) {
                // 主手换了别的剑（非热键切换的路径，如 F 键互换漏网）：旧撤新立
                removeFor(id);
                spawnFor(p, hand);
                continue;
            }
            rescaleForTier(hand, a);
        }
    }

    /**
     * 涨档重算（v2.23，巡检周期内调用）：SpiritManager 落账后不会通知本模块，
     * 所以在每轮 40L 巡检里重读主手剑的灵性档位、重算显形倍数。
     * 倍数没变（{@link #needsRescale} 判定）就完全不碰实体，绝不每轮重发
     * transformation；真变了才先设 {@code interpolationDuration} 再
     * {@code setTransformation}，让浮游体在约 1 秒内渐变长大 / 缩小。
     */
    private void rescaleForTier(ItemStack sword, Active a) {
        int tier = plugin.spirit().tierOf(plugin.spirit().spiritOf(sword));
        a.lastTier = tier;
        float nextScale = (float) scaleForTier(tier, growthPerTier());
        if (!needsRescale(a.tierScale, nextScale)) {
            return; // 倍数没变：什么都不做（涨档但 growth-per-tier=1.0 时也走这里）
        }
        a.tierScale = nextScale;
        Entity d = Bukkit.getEntity(a.display);
        if (d instanceof Display disp) {
            // 先设插值再换 transformation：客户端从当前姿态向新倍数平滑过渡
            disp.setInterpolationDelay(0);
            disp.setInterpolationDuration(GROWTH_INTERP_TICKS);
            float bob = (float) Math.sin(a.angle * 2) * 0.06f;
            disp.setTransformation(pose(a.angle * 2, bob, nextScale));
        }
    }

    /**
     * 全量孤儿清扫（30 秒一轮）：所有已加载世界里带 {@link Keys#SPIRIT_DISPLAY}
     * 标记的 ItemDisplay——owner 不在线 → 孤儿，撤；owner 在线但不在本服务的
     * active 表里（重复生成 / 巡检换实体后的残留副本）→ 撤。
     * 只处理已加载区块的实体列表，不会触发区块同步加载。
     */
    private void sweepOrphans() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntitiesByClass(ItemDisplay.class)) {
                String ownerStr = e.getPersistentDataContainer()
                        .get(Keys.SPIRIT_DISPLAY, PersistentDataType.STRING);
                if (ownerStr == null) {
                    continue; // 不是本模块的实体（殿堂 / 砧位各有各的键），不碰
                }
                UUID owner;
                try {
                    owner = UUID.fromString(ownerStr);
                } catch (IllegalArgumentException ex) {
                    e.remove(); // 标记损坏，无法认主：按孤儿撤
                    continue;
                }
                Player ownerOnline = Bukkit.getPlayer(owner);
                Active a = active.get(owner);
                if (ownerOnline == null || a == null || !a.display.equals(e.getUniqueId())) {
                    e.remove();
                    plugin.getLogger().fine("剑灵显形：撤除孤儿实体 " + e.getUniqueId()
                            + "（主人 " + (ownerOnline == null ? "不在线" : "存在重复实体") + "）");
                }
            }
        }
    }

    // ------------------------------------------------------------ 生成 / 撤除

    /** 给玩家主手剑立一个显形体（每玩家最多 1 个：生成前先查旧，旧撤新立） */
    private void spawnFor(Player player, ItemStack sword) {
        removeFor(player.getUniqueId());
        World world = player.getWorld();
        double radius = orbitRadius();
        // 初始位置：玩家右肩侧
        Location at = player.getLocation().add(radius, 1.2, 0);
        ItemStack shown = sword.clone();
        // v2.23：立体时就按当前档位定倍数（/zhujian spirit show 走同一条路，倍数同样生效）。
        // 首次出现不设插值——初始 transformation 直接生效，渐变留给涨档时用。
        int tier = plugin.spirit().tierOf(plugin.spirit().spiritOf(sword));
        float tierScale = (float) scaleForTier(tier, growthPerTier());
        ItemDisplay display = world.spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(shown);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED); // 朝向由动画里按环绕角自己算
            d.setGravity(false);
            d.setInvulnerable(true);
            d.setPersistent(false); // 审计铁律：非持久，崩服 / 强杀不留档
            d.setShadowRadius(0f);
            d.setShadowStrength(0f);
            d.setViewRange(0.8f);
            d.setTransformation(pose(0f, 0f, tierScale));
            // PDC 标记：值 = 主人 UUID，孤儿清扫 / 模块隔离都靠它认领
            d.getPersistentDataContainer().set(Keys.SPIRIT_DISPLAY,
                    PersistentDataType.STRING, player.getUniqueId().toString());
        });
        if (display == null) {
            return;
        }
        boolean bonused = bonusEnabled() && applyBonus(player, sword);
        UUID playerId = player.getUniqueId();
        BukkitTask animator = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> animate(playerId, display.getUniqueId()), ANIMATE_TICKS, ANIMATE_TICKS);
        active.put(playerId, new Active(display.getUniqueId(), animator,
                snapshotOf(sword), tier, tierScale));
        if (bonused) {
            Msg.action(player, "<#b06bff>剑灵显形</#b06bff> <gray>暴击率 +"
                    + String.format(java.util.Locale.ROOT, "%.0f", bonusCrit() * 100) + "%</gray>");
        }
    }

    /** 撤掉某玩家的显形体并差量扣回加成；玩家不在线时只撤实体（加成随事件路径另行扣回） */
    private void removeFor(UUID playerId) {
        Active a = active.remove(playerId);
        if (a == null) {
            return;
        }
        a.animator.cancel();
        Entity e = Bukkit.getEntity(a.display);
        if (e != null) {
            e.remove();
        }
        Player p = Bukkit.getPlayer(playerId);
        if (p != null) {
            revertBonus(p);
        }
    }

    /** 立即撤除（事件路径用，player 对象仍有效）：撤实体 + 扣回背包 / 副手 / 光标里的加成 */
    private void removeNow(Player player) {
        removeFor(player.getUniqueId());
        revertBonus(player);
    }

    // ------------------------------------------------------------ 动画

    /** 每 2 tick：绕玩家环绕（teleport）+ transformation 自旋 / 正弦浮动 */
    private void animate(UUID playerId, UUID displayId) {
        Entity entity = Bukkit.getEntity(displayId);
        Player player = Bukkit.getPlayer(playerId);
        if (!(entity instanceof ItemDisplay display) || !display.isValid()
                || player == null || !player.isOnline()) {
            return; // 巡检兜底撤，这里只停帧
        }
        Active a = active.get(playerId);
        if (a == null) {
            return;
        }
        a.angle += SPIN_STEP;
        double radius = orbitRadius();
        double x = Math.cos(a.angle) * radius;
        double z = Math.sin(a.angle) * radius;
        Location at = player.getLocation().add(x, 1.2, z);
        // Billboard FIXED 自己算朝向：让剑身随环绕角面向外侧
        at.setYaw((float) Math.toDegrees(a.angle));
        at.setPitch(0f);
        display.teleport(at);
        float bob = (float) Math.sin(a.angle * 2) * 0.06f; // 正弦浮动（translation.y）
        display.setTransformation(pose(a.angle * 2, bob, a.tierScale));
    }

    /** 姿态：绕自身 Y 自旋 + 固定侧倾，缩放 scale × 灵性档位倍数，translation.y 承载正弦浮动 */
    private Transformation pose(float spinY, float bobY, float tierScale) {
        float scale = scale() * tierScale;
        Quaternionf rotation = new Quaternionf().rotateY(spinY).rotateZ(0.55f);
        return new Transformation(
                new Vector3f(0f, bobY, 0f),
                rotation,
                new Vector3f(scale, scale, scale),
                new Quaternionf());
    }

    // ------------------------------------------------------------ 档位成长曲线（纯函数，自检直测）

    /**
     * 灵性档位 → 显形倍数（v2.23 纯函数，不碰 Bukkit，自检可直测曲线）。
     * 每上一档在基础上乘 {@code perTierGrowth}：0 档恒为 1.0、1 档 = g、3 档 = g³。
     *
     * <p>钳制规则（配置写错一律退化成安全值，绝不抛异常，写法同 {@code SwordScale}）：
     * {@code perTierGrowth} 钳到 1.0~2.0（负数 / 0 / NaN / 超大都归到边界，
     * 上限 2.0 防止配置手滑把浮游体放到穿模）；{@code tier} 钳到 ≥ 0
     * （负档位视为 0 档 = 不放大，低于起显档位本来就不显形，行为保持现状）。</p>
     */
    static double scaleForTier(int tier, double perTierGrowth) {
        double g = Double.isFinite(perTierGrowth)
                ? Math.max(1.0, Math.min(2.0, perTierGrowth))
                : 1.0;
        int t = Math.max(0, tier);
        return Math.pow(g, t);
    }

    /**
     * 倍数没变就不重复 setTransformation 的判定纯函数：目标倍数与当前倍数
     * 差值在浮点精度（{@value #RESCALE_EPSILON}）内视为「没变」。
     * 巡检每轮都会算出新倍数，靠它挡住无意义的实体写入。
     */
    static boolean needsRescale(float currentScale, float targetScale) {
        return Math.abs(targetScale - currentScale) > RESCALE_EPSILON;
    }

    // ------------------------------------------------------------ 效果加成（差量式）

    /**
     * 给主手剑的 PDC 暴击率加 bonus.crit-chance（同 Keys.CRIT_CHANCE 汇总口径），
     * 并打 SPIRIT_DISPLAY_BONUS 标记记下本次加成量。
     */
    private boolean applyBonus(Player player, ItemStack sword) {
        double amount = bonusCrit();
        if (amount <= 0) {
            return false;
        }
        ItemMeta meta = sword.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(Keys.CRIT_CHANCE, PersistentDataType.DOUBLE,
                pdc.getOrDefault(Keys.CRIT_CHANCE, PersistentDataType.DOUBLE, 0.0) + amount);
        pdc.set(Keys.SPIRIT_DISPLAY_BONUS, PersistentDataType.DOUBLE, amount);
        sword.setItemMeta(meta);
        player.getInventory().setItemInMainHand(sword);
        return true;
    }

    /**
     * 按标记差量扣回：扫背包（含盔甲 / 副手）、光标——凡带 SPIRIT_DISPLAY_BONUS
     * 标记的物品，CRIT_CHANCE 扣回标记量并摘掉标记。找不到（剑已丢出背包）就放弃，
     * 绝不凭记忆扣到别的剑上。逐槽幂等：重复调用不会二次扣。
     */
    private void revertBonus(Player player) {
        PlayerInventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack it = inv.getItem(slot);
            if (it != null && revertOne(it)) {
                inv.setItem(slot, it);
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && !cursor.getType().isAir() && revertOne(cursor)) {
            player.setItemOnCursor(cursor);
        }
    }

    /** 对单件物品扣回加成；返回是否真的改了 meta（调用方负责写回槽位） */
    private boolean revertOne(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Double amount = pdc.get(Keys.SPIRIT_DISPLAY_BONUS, PersistentDataType.DOUBLE);
        if (amount == null || amount == 0) {
            return false;
        }
        pdc.set(Keys.CRIT_CHANCE, PersistentDataType.DOUBLE,
                Math.max(0.0, pdc.getOrDefault(Keys.CRIT_CHANCE, PersistentDataType.DOUBLE, 0.0) - amount));
        pdc.remove(Keys.SPIRIT_DISPLAY_BONUS);
        item.setItemMeta(meta);
        return true;
    }

    // ------------------------------------------------------------ 事件（五条立即清理路径）

    /**
     * 清理路径①：玩家退服。quit 事件里背包 / 光标仍可写，是扣回加成的最后窗口。
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        removeNow(player);
        hidden.remove(player.getUniqueId()); // hide 是会话级的，退服即忘
    }

    /**
     * 清理路径②：换世界。显形体绑人不绑世界，直接撤（新世界的由巡检重立）。
     */
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        removeNow(event.getPlayer());
    }

    /**
     * 清理路径③：死亡。背包与掉落列表两头都扫一遍加成标记
     * （keepInventory 两种取值下都不会漏扣）。
     */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        removeNow(player);
        for (ItemStack drop : event.getDrops()) {
            revertOne(drop);
        }
    }

    /**
     * 清理路径④：热键切换主手。旧剑立即撤除扣回，下一 tick 条件仍满足就立新剑
     * （不等巡检，切换观感即时）。
     */
    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        removeNow(player);
        Bukkit.getScheduler().runTask(plugin, () -> tryShow(player));
    }

    /** F 键主副手互换同样换掉了主手剑：与热键切换同款处理 */
    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        removeNow(player);
        Bukkit.getScheduler().runTask(plugin, () -> tryShow(player));
    }

    /**
     * 清理路径⑤：丢出带加成标记的剑 → 就地扣回掉落物上的暴击，
     * 堵住「丢剑保加成」的口子（丢弃的物品不再受背包扫描覆盖）。
     */
    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        revertOne(event.getItemDrop().getItemStack());
    }

    // ------------------------------------------------------------ 命令入口

    /** 尝试立显形（热键切换 / 巡检共用）：条件达标且当前没有活体才立 */
    private void tryShow(Player player) {
        if (!player.isOnline() || active.containsKey(player.getUniqueId()) || hidden.contains(player.getUniqueId())) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (qualifies(player, hand)) {
            spawnFor(player, hand);
        }
    }

    /** /zhujian spirit show：立即显形主手剑；条件不满足时给出原因 */
    public void show(Player player) {
        if (!displayEnabled()) {
            Msg.prefix(player, "<red>剑灵显形未开启。</red>");
            return;
        }
        hidden.remove(player.getUniqueId());
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (active.containsKey(player.getUniqueId())) {
            Msg.prefix(player, "<gray>剑灵已经显形着了。</gray>");
            return;
        }
        if (hand.getType().isAir() || !plugin.items().isForged(hand)) {
            Msg.prefix(player, "<red>主手要拿一把锻造品。</red>");
            return;
        }
        int tier = plugin.spirit().tierOf(plugin.spirit().spiritOf(hand));
        if (tier < minTier()) {
            Msg.prefix(player, "<red>灵性要达到「" + plugin.spirit().tierName(minTier())
                    + "」档才能显形（当前 " + plugin.spirit().tierName(tier) + "）。</red>");
            return;
        }
        if (requireNamed() && plugin.spirit().nameOf(hand).isBlank()) {
            Msg.prefix(player, "<red>无名之剑无法显形——先用 <white>/zhujian spirit name</white> 给剑灵起名。</red>");
            return;
        }
        spawnFor(player, hand);
        Msg.prefix(player, "<#b06bff>剑灵显形了。</#b06bff>");
    }

    /** /zhujian spirit hide：本会话内隐藏（撤体 + 扣回），巡检不再自动重立；退服即忘 */
    public void hide(Player player) {
        hidden.add(player.getUniqueId());
        removeNow(player);
        Msg.prefix(player, "<gray>剑灵隐去了（重新显形：<white>/zhujian spirit show</white>）。</gray>");
    }
}
