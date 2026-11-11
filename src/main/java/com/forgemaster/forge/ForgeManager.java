package com.forgemaster.forge;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.affix.Affix;
import com.forgemaster.affix.AffixRegistry;
import com.forgemaster.affix.AffixScaling;
import com.forgemaster.config.Settings;
import com.forgemaster.econ.CoinReason;
import com.forgemaster.gui.ForgeHolder;
import com.forgemaster.gui.ForgeMenu;
import com.forgemaster.item.ItemFactory;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.material.MaterialGrade;
import com.forgemaster.material.MaterialGrades;
import com.forgemaster.material.MaterialRegistry;
import com.forgemaster.quality.Quality;
import com.forgemaster.quench.Quench;
import com.forgemaster.quench.QuenchRegistry;
import com.forgemaster.stage.ForgeStage;
import com.forgemaster.stats.StatsStore;
import com.forgemaster.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 锻造流程调度：加热 → 锻打 → 淬火 → 成品。
 */
public final class ForgeManager {

    private final ForgingMasterPlugin plugin;
    private final MaterialRegistry materials;
    private final AffixRegistry affixes;
    private final QuenchRegistry quenches;
    private final ItemFactory items;
    private final StatsStore stats;
    private final Settings settings;
    private final ForgeMenu menu;

    private final Map<UUID, ForgeSession> sessions = new HashMap<>();

    /**
     * 研磨会话的「开局槽位」（v2.23.1 修 P1-A）。
     *
     * <p>以前 {@code finishRepair} → {@code replaceRepaired} 是拿「材料 id + 修复前耐久」
     * 线性扫背包找原物的：背包里两把同胚同耐久的剑时，会把<b>另一把</b>覆盖成修好那把的
     * 克隆 → 白送一件满强化成品，同时吞掉被覆盖那把。</p>
     *
     * <p>现在开局把 {@code getHeldItemSlot()} 记在这里，收尾只按这一个槽位替换；
     * 槽位已变（换手 / 丢出 / 被挪走）就绝不模糊匹配，退回「直接发给玩家」，
     * 宁可多一件也不复制。放在本类而不是 {@code ForgeSession} 里，是为了不动
     * 会话类的公开字段（同一个玩家同时只有一个会话，这层映射等价于会话字段）。</p>
     */
    private final Map<UUID, Integer> repairSlots = new HashMap<>();

    /**
     * 会话玩家最后一次在线位置（v2.23.1 修 P1-B）。
     *
     * <p>{@code discard} 走到时玩家已经离线，{@code OfflinePlayer#getLocation()}
     * 不保证拿得到落点；所以在 tick 里趁人还在线时留一份站位，供「离线发放成品」
     * 决定掉落坐标（优先用砧位 / 演出台的原点，其次才是这里）。</p>
     */
    private final Map<UUID, Location> lastLocations = new HashMap<>();

    private final Random random = new Random();
    private BukkitTask ticker;

    /**
     * tick 主循环迭代中待下场的会话（审计 #9）。
     *
     * <p>cancel / discard / finishRepair / maybeExplode 在迭代深处把会话从
     * {@code sessions} 里摘掉；改为先把它们记进这里、循环结束后统一 remove，
     * 才能省掉每 tick {@code new ArrayList<>(sessions.values())} 的整表拷贝而不触发 CME。</p>
     */
    private final List<ForgeSession> pendingRemovals = new ArrayList<>();
    /** true = 正在 tick 主循环里迭代 sessions，removeSession 只记账不真删 */
    private boolean ticking;

    /**
     * 装备专用词条池：全是防御向，抽不满也不回退到武器词条。
     *
     * <p>公开给 {@code affix.Reforge} 复用——词条重铸抽装备词条时必须是同一份池子，
     * 两边各抄一份迟早会漂移。</p>
     */
    public static final List<String> ARMOR_AFFIX_POOL = List.of(
            "armor_plate", "tough_plate", "vitality", "knockback_resist", "aegis");

    /**
     * 武器兜底词条池：整张词条表去掉护甲专用的那 5 条。
     *
     * <p>存在的理由是「淬火池太浅」——油淬只给 2 条候选，而精良以上品质要 4~6 条。
     * 以前抽不满就从**整张表**补，于是剑上会出现「护甲值」。
     * 现在只在武器向里补，剑永远抽不到防御词条，也永远抽不到重复。</p>
     *
     * <p>新增防御类词条时，记得同时加进 {@link #ARMOR_AFFIX_POOL}——
     * 这里靠 {@code 全集 - 护甲池} 自动排除，不用两边同时改。</p>
     */
    public static final List<String> WEAPON_AFFIX_POOL = List.of(
            "attack_damage", "attack_speed", "crit_chance", "crit_damage",
            "lifesteal", "fire", "knockback", "reach");

    public ForgeManager(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.materials = plugin.materials();
        this.affixes = plugin.affixes();
        this.quenches = plugin.quenches();
        this.items = plugin.items();
        this.stats = plugin.stats();
        this.settings = plugin.settings();
        this.menu = new ForgeMenu(plugin, settings);
        this.menu.pressStep(settings.grindPressStep());
        this.menu.grindDuration(settings.grindDurationTicks());
    }

    public ForgeMenu menu() {
        return menu;
    }

    public void start() {
        stop();
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
    }

    public boolean isBusy(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    public ForgeSession session(Player player) {
        return sessions.get(player.getUniqueId());
    }

    // ------------------------------------------------------------ 开局

    public void openMenu(Player player) {
        openMenu(player, false);
    }

    /** @param repairMode true = 进研磨台修复手上的剑 */
    public void openMenu(Player player, boolean repairMode) {
        if (isBusy(player)) {
            Msg.prefix(player, "<red>你手上还有活儿没干完。</red>");
            return;
        }
        if (materials.isEmpty()) {
            Msg.prefix(player, "<red>没有配置任何剑胚，检查 config.yml 的 materials 段。</red>");
            return;
        }
        if (repairMode) {
            beginRepair(player);
            return;
        }
        player.openInventory(menu.buildMainMenu(player, materials.sorted()));
        sound(player, Sound.BLOCK_ANVIL_PLACE, 0.6f, 1.4f);
    }

    /** 主界面 → 锻造武器 */
    public void openWeapons(Player player) {
        player.openInventory(menu.buildWeaponsMenu(player, materials.sorted()));
        sound(player, Sound.UI_BUTTON_CLICK, 0.6f, 1.3f);
    }

    /** 主界面 → 锻造装备（先选部位） */
    public void openArmorSlots(Player player) {
        if (!plugin.getConfig().getBoolean("armor.enabled", true)) {
            Msg.prefix(player, "<red>装备锻造已关闭。</red>");
            return;
        }
        player.openInventory(menu.buildArmorSlots(player));
        sound(player, Sound.UI_BUTTON_CLICK, 0.6f, 1.3f);
    }

    /** 装备 → 选档位 */
    public void openArmorTiers(Player player, ForgeMaterial.Slot slot) {
        player.openInventory(menu.buildArmorTiers(player, materials.sorted(), slot, armorPriceMultiplier()));
        sound(player, Sound.UI_BUTTON_CLICK, 0.6f, 1.3f);
    }

    private double armorPriceMultiplier() {
        return Math.max(0.0, plugin.settings().num("armor.price-multiplier", 1.0));
    }

    /** 装备开锻：由剑胚档位生出装备胚，再走同一套流程 */
    public void beginArmor(Player player, ForgeMaterial base, ForgeMaterial.Slot slot) {
        ForgeMaterial armor = com.forgemaster.material.ArmorCatalog.create(base, slot, armorPriceMultiplier());
        if (armor == null) {
            Msg.prefix(player, "<red>这一档没有对应的 " + slot.label() + " 材质，换一档试试。</red>");
            return;
        }
        begin(player, armor);
    }

    // ------------------------------------------------------------ 研磨修复

    /** 手持崩刃/磨损的锻造品进研磨台 */
    private void beginRepair(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir() || !items.isForged(held)) {
            Msg.prefix(player, "<red>主手要拿一把锻造品才能研磨。</red>");
            return;
        }
        int cur = items.durabilityOf(held);
        int max = items.maxDurabilityOf(held);
        if (cur < 0 || max <= 0) {
            Msg.prefix(player, "<red>这把剑没有耐久数据（旧版本物品）。</red>");
            return;
        }
        if (cur >= max) {
            Msg.prefix(player, "<green>这把剑还完好，不用修。</green>");
            return;
        }

        ForgeMaterial mat = materials.get(items.materialIdOf(held));
        Material costItem = mat == null ? Material.IRON_INGOT : mat.costItem();
        int need = settings.repairCostAmount();
        if (need > 0) {
            int have = ForgeMenu.count(player, costItem);
            if (have < need) {
                Msg.prefix(player, "<red>研磨需要 <white>" + need + " × " + costItem.name()
                        + "</white>，你只有 " + have + "。");
                return;
            }
            ForgeMenu.take(player, costItem, need);
        }

        ForgeSession session = ForgeSession.of(player, mat == null ? materials.sorted().get(0) : mat);
        session.repairing(true);
        session.repairTarget(held.clone());
        // v2.23.1 修 P1-A：把「开局那一刻的槽位」钉死，收尾时只认这一格。
        // 以前收尾按「材料 id + 旧耐久」全背包线性扫，两把同胚同耐久的剑会把不是目标的那把
        // 覆盖成修好那把的克隆（复制）并吞掉被覆盖那把。
        repairSlots.put(player.getUniqueId(), player.getInventory().getHeldItemSlot());
        session.mastery(stats.get(player.getUniqueId()).level);
        // 研磨绿区也吃「刃感」，不固化的话 Kit 恒为 NONE，点了也白点
        session.talents(com.forgemaster.growth.Talents.kit(stats.get(player.getUniqueId())));
        session.phase(ForgePhase.GRINDING);
        session.grindValue(50);
        session.grindTargetCenter(30 + session.rnd().nextInt(41));
        session.grindTargetWidth(settings.repairZoneWidth() + StatsStore.extraGrindWidth(session.mastery())
                + session.talents().grindWidth());
        session.grindDrift((session.rnd().nextDouble() - 0.5) * 2.0);
        session.grindDriftTimer(20);
        sessions.put(player.getUniqueId(), session);

        open(session, menu.buildGrinding(session, settings.repairDurationTicks()));
        sound(player, Sound.BLOCK_GRINDSTONE_USE, 1.0f, 0.9f);
        player.showTitle(Title.title(
                Msg.mm("<gradient:#7ef2c8:#4ea8ff><bold>研 磨</bold></gradient>"),
                Msg.mm("<gray>把刃压稳在绿区，恢复耐久"),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(900), Duration.ofMillis(300))));
        Msg.prefix(player, "<gray>当前耐久 <white>" + cur + "/" + max + "</white>，稳住 "
                + Msg.fmt(settings.repairDurationTicks() / 20.0, 1) + " 秒，越稳修得越多。");
    }

    /** 在背包里找指定剑胚的图纸，返回槽位；没有返回 -1 */
    private int findBlueprint(Player player, String materialId) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            String id = plugin.shop() == null ? null : plugin.shop().blueprintOf(inv.getItem(i));
            if (id != null && id.equalsIgnoreCase(materialId)) {
                return i;
            }
        }
        return -1;
    }

    public void begin(Player player, ForgeMaterial material) {
        if (isBusy(player)) {
            Msg.prefix(player, "<red>你已经在锻造台前了。</red>");
            return;
        }
        // v2.0 连锻链：开工前先检查静默是否超时，并提示当前链状态
        plugin.streaks().checkSilence(player);
        plugin.streaks().showStatus(player);

        // 比试中：剑胚被锁定，种子共享
        var duel = plugin.duels() == null ? null : plugin.duels().activeMatchOf(player.getUniqueId());
        if (duel != null && !duel.material.id().equalsIgnoreCase(material.id())) {
            Msg.prefix(player, "<red>比试指定剑胚是 <white>" + duel.material.display()
                    + "</white>，只能用它。");
            sound(player, Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
            return;
        }
        // 临摹：剑胚与种子被原谱锁定
        var copy = plugin.manuals() == null ? null : plugin.manuals().activeCopy(player.getUniqueId());
        if (copy != null && !copy.materialId().equalsIgnoreCase(material.id())) {
            Msg.prefix(player, "<red>临摹《" + copy.name() + "》只能用 <white>"
                    + copy.materialId() + "</white> 剑胚。");
            // 别让人卡死在这儿：明确告诉他怎么换
            Msg.prefix(player, "<gray>想锻别的剑胚，先 <white>/zhujian copy stop</white> 取消临摹。");
            sound(player, Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
            return;
        }

        // 图纸：required 时没图纸必须在这里就拒绝——放在扣费后面会出现
        // 「锻造费和材料都扣了，才被告知缺图纸」的白扣
        int blueprintSlot = -1;
        if (plugin.settings().flag("blueprint.enabled", true)) {
            blueprintSlot = findBlueprint(player, material.id());
            if (blueprintSlot < 0 && plugin.settings().flag("blueprint.required", false)) {
                Msg.prefix(player, "<red>锻造 " + material.display()
                        + " <red>需要《图纸 · " + material.display() + "》</red>"
                        + " <gray>· 去商会买：<white>/zhujian shop</white>");
                sound(player, Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
                return;
            }
        }

        // 耗材与锻造费：先查齐，再扣钱，最后扣料（扣料失败会退钱）
        MaterialGrade usedGrade = MaterialGrade.COMMON;
        long price = material.price();
        if (settings.consumeCost() && !material.costs().isEmpty()) {
            for (Map.Entry<Material, Integer> cost : material.costs().entrySet()) {
                int have = MaterialGrades.totalCount(player, cost.getKey());
                if (have < cost.getValue()) {
                    Msg.prefix(player, "<red>材料不够：需要 <white>" + cost.getValue()
                            + "</white> 个 " + MaterialGrades.pretty(cost.getKey())
                            + "，你只有 <white>" + have + "</white>。");
                    sound(player, Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
                    return;
                }
            }
            if (price > 0) {
                var eco = plugin.economy();
                if (!eco.has(player, price)) {
                    Msg.prefix(player, "<red>锻造费不足：需要 <white>" + eco.format(price)
                            + "</white>，你只有 <white>" + eco.format((long) eco.balance(player)) + "</white>。");
                    sound(player, Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
                    return;
                }
                if (!eco.withdraw(player, price)) {
                    Msg.prefix(player, "<red>扣费失败，锻造没有开始。请稍后再试。</red>");
                    sound(player, Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
                    return;
                }
            }
            MaterialGrade best = null;
            boolean taken = true;
            for (Map.Entry<Material, Integer> cost : material.costs().entrySet()) {
                MaterialGrade got = MaterialGrades.consume(player, cost.getKey(), cost.getValue());
                if (got == null) {
                    taken = false;
                    break;
                }
                if (best == null || got.atLeast(best)) {
                    best = got;
                }
            }
            if (!taken) {
                if (price > 0) {
                    plugin.economy().deposit(player, price);
                }
                Msg.prefix(player, "<red>材料在扣除时不够了，锻造取消，费用已退回。</red>");
                return;
            }
            usedGrade = best == null ? MaterialGrade.COMMON : best;
        }

        // 图纸：校验已在扣费前做过，这里只结算加成与消耗
        double blueprintBonus = 0.0;
        if (plugin.settings().flag("blueprint.enabled", true)) {
            int slot = blueprintSlot;
            if (slot >= 0) {
                blueprintBonus = plugin.settings().num("blueprint.tolerance-bonus", 0.15);
                if (plugin.settings().flag("blueprint.consume", true)) {
                    var inv = player.getInventory();
                    var bp = inv.getItem(slot);
                    if (bp != null) {
                        bp.setAmount(bp.getAmount() - 1);
                        if (bp.getAmount() <= 0) {
                            inv.setItem(slot, null);
                        }
                    }
                }
                Msg.prefix(player, "<gray>照着图纸打：加热容差 <#4ee36b>+"
                        + Msg.fmt(blueprintBonus * 100, 0) + "%</#4ee36b>"
                        + (plugin.settings().flag("blueprint.consume", true) ? " <dark_gray>（图纸已消耗）" : ""));
            }
        }

        ForgeSession session = ForgeSession.of(player, material);
        session.blueprintBonus(blueprintBonus);
        session.grade(usedGrade);
        // 环境修正：开局固化一次，整局只读 EnvContext（w7-env）
        session.env(plugin.env() == null ? null : plugin.env().resolve(player));
        session.mastery(stats.get(player.getUniqueId()).level);
        session.talents(com.forgemaster.growth.Talents.kit(stats.get(player.getUniqueId())));
        session.forgerTitle(StatsStore.titleOf(stats.get(player.getUniqueId()).reputation));
        // 极限锻造：有预设且配置开启才生效，开工时消耗预设（D3）
        session.risk(RiskMode.enabled(plugin) && RiskMode.consume(player.getUniqueId()));
        if (plugin.duels() != null) {
            plugin.duels().bindSession(session);
        }
        if (plugin.contests() != null) {
            plugin.contests().bindSession(session);
        }
        if (copy != null) {
            session.manualId(copy.id());
            session.seed(copy.seed());
        }
        session.phase(ForgePhase.HEATING);
        session.temperature(24.0 + session.rnd().nextInt(8));
        session.zoneCenter(1 + material.zoneWidth() + session.rnd().nextInt(Math.max(1, 7 - material.zoneWidth() * 2)));
        sessions.put(player.getUniqueId(), session);

        BossBar bar = Bukkit.createBossBar("炉温", BarColor.RED, BarStyle.SEGMENTED_20);
        bar.addPlayer(player);
        bar.setProgress(0.2);
        // 审计 #4：title 改成每 20 tick 才刷一次后，这里先铺一帧完整标题，
        // 免得开局第一秒顶着光秃秃的「炉温」两个字
        bar.setTitle("炉温 " + (int) session.temperature() + "°  |  最佳 "
                + (int) material.idealTemp() + "° ±" + (int) material.tolerance());
        session.bossBar(bar);

        spawnStage(player, session);

        player.showTitle(Title.title(
                Msg.mm("<gradient:#ffd166:#ff5e00><bold>点 火</bold></gradient>"),
                Msg.mm("<gray>把剑胚烧到最佳区间"),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1200), Duration.ofMillis(400))));

        // 先让玩家看一眼铁砧演出，再开界面：
        // 容器 GUI 一旦打开，鼠标被锁、视角转不了，世界里的动画就全被挡住了
        int intro = session.stage() == null ? 0
                : Math.max(0, plugin.settings().intOf("settings.stage-intro-ticks", 14));
        if (intro > 0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (sessions.get(player.getUniqueId()) == session && !session.finished()) {
                    open(session, menu.buildHeating(session));
                }
            }, intro);
        } else {
            open(session, menu.buildHeating(session));
        }

        Msg.prefix(player, "<gray>左键拉 <#ffd166>风箱</#ffd166> 升温，温度正好时点 <#4ee36b>取出剑坯</#4ee36b>。");
        announceEnv(player, session);
    }

    /** 开局环境提示：聊天栏 + ActionBar（受 environment.announce-on-start 控制），w7-env */
    private void announceEnv(Player player, ForgeSession session) {
        if (!plugin.getConfig().getBoolean("environment.announce-on-start", true)) {
            return;
        }
        com.forgemaster.env.EnvContext env = session.env();
        if (!env.hasBonus() && "clear".equals(env.weatherKey) && "day".equals(env.timeKey)) {
            Msg.prefix(player, "<gray>环境：<white>" + env.weatherLabel() + "</white> · <white>"
                    + env.timeLabel() + "</white> <dark_gray>（无修正）</dark_gray>");
            return;
        }
        Msg.prefix(player, "<gray>环境：<white>" + env.weatherLabel() + "</white> · <white>"
                + env.timeLabel() + "</white> " + envBonusText(env));
        Msg.action(player, "<gray>" + env.weatherLabel() + " · " + env.timeLabel() + " " + envBonusText(env));
    }

    /** 把四项修正格式化成一段简短文字，如 "淬火 +10% · 叠钢 +0.3s" */
    private static String envBonusText(com.forgemaster.env.EnvContext env) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (env.heatBonus != 0.0) {
            parts.add("保温 " + signedPct(env.heatBonus));
        }
        if (env.quenchBonus != 0.0) {
            parts.add("淬火 " + signedPct(env.quenchBonus));
        }
        if (env.foldSecondsBonus != 0.0) {
            parts.add("叠钢 " + (env.foldSecondsBonus > 0 ? "+" : "") + Msg.fmt(env.foldSecondsBonus, 1) + "s");
        }
        if (env.grindWidthBonus != 0.0) {
            parts.add("开刃宽 " + (env.grindWidthBonus > 0 ? "+" : "") + Msg.fmt(env.grindWidthBonus, 1));
        }
        return parts.isEmpty() ? "" : "<dark_gray>[</dark_gray><#ffd166>" + String.join(" · ", parts)
                + "</#ffd166><dark_gray>]</dark_gray>";
    }

    private static String signedPct(double v) {
        return (v > 0 ? "+" : "") + Msg.fmt(v * 100, 0) + "%";
    }

    /**
     * 切换界面，顺便屏蔽切换过程中的 InventoryCloseEvent。
     *
     * <h2>v2.23.1 修 P0：这里原来有一道「假门」，导致成品点不动、剑看起来消失</h2>
     * 旧写法是：
     * <pre>
     *   session.transitioning(true);
     *   p.openInventory(inv);
     *   session.transitioning(false);   // ← 同步清掉
     * </pre>
     * 问题在于：{@code openInventory} 引发的<b>旧界面关闭事件不一定同步送达</b>，
     * 很多情况下是<b>下一 tick</b>才到 {@link #onInventoryClosed}。等它到的时候，
     * {@code transitioning} 早就被清成 false 了 —— 这道门形同虚设。
     *
     * <p>而进入成品界面时顺序是「先 {@code s.phase(RESULT)}，再 {@code open(...)}」，
     * 所以那个迟到的关闭事件看到的是「phase == RESULT 且玩家关了界面」，于是：
     * {@code giveResult → finished(true) → removeSession}，<b>会话当场被销毁</b>。</p>
     *
     * <p>玩家的感受就是服主报的那个现象：成品界面还开着、点「拿走」毫无反应
     * （{@code handleClick} 第一行 {@code s.finished()} 直接 return），
     * 一按 ESC 关掉，格子里那把剑就"消失"了。而且它<b>不是每次都触发</b> ——
     * 只在那次关闭事件恰好迟到时才发生，所以极难复现。</p>
     *
     * <p>两道保险：<b>①</b> 这里改成下一 tick 再清标志（覆盖「迟到一 tick」）；
     * <b>②</b> {@link #onInventoryClosed} 改成看「玩家此刻是不是正看着同一个会话的
     * 另一个界面」—— 语义判断，与时序无关，这才是真正的地基。</p>
     */
    private void open(ForgeSession session, Inventory inv) {
        Player p = session.player();
        if (p == null) {
            return;
        }
        session.transitioning(true);
        p.openInventory(inv);
        // 不要同步清：关闭事件可能下一 tick 才送达。下一 tick 再放开。
        plugin.getServer().getScheduler().runTask(plugin, () -> session.transitioning(false));
    }

    // ------------------------------------------------------------ 现场演出

    /**
     * 找玩家附近最近的铁砧，把剑放上去开演。
     * 附近没铁砧时，按配置临时生成一个 Display 替身铁砧（不动真实地形）。
     */
    private void spawnStage(Player player, ForgeSession session) {
        if (!settings.anvilAnimation()) {
            return;
        }
        int radius = settings.anvilSearchRadius();
        Location anvilTop = findAnvilTop(player, radius);
        boolean realAnvil = anvilTop != null;

        if (!realAnvil) {
            if (!settings.spawnTempAnvil()) {
                return;
            }
            // 没有真铁砧：舞台放在视线正前方、并且高于视线——
            // 容器 GUI 打开后视角是锁死的，只有屏幕上方的世界露出来，放平视高度等于看不见
            double front = Math.max(1.0, plugin.settings().num("settings.stage-front-distance", 2.5));
            double lift = plugin.settings().num("settings.stage-lift-above-eye", 1.1);
            Location eye = player.getEyeLocation();
            org.bukkit.util.Vector dir = eye.getDirection().clone();
            dir.setY(0);
            if (dir.lengthSquared() < 0.001) {
                dir = new org.bukkit.util.Vector(0, 0, 1);
            }
            dir.normalize().multiply(front);
            anvilTop = eye.clone().add(dir);
            anvilTop.setY(eye.getY() + lift);
        } else {
            // GUI 会挡住屏幕下半部分，砧面要往上抬才看得见
            double offset = plugin.settings().num("settings.stage-height-offset", 1.4);
            anvilTop = anvilTop.clone().add(0, offset, 0);
        }

        ForgeStage stage = new ForgeStage(plugin, session, anvilTop, realAnvil);
        stage.spawn();
        session.stage(stage);
    }

    private Location findAnvilTop(Player player, int radius) {
        if (radius <= 0) {
            return null;
        }
        Location base = player.getLocation();
        org.bukkit.World world = player.getWorld();
        Location best = null;
        double bestDist = Double.MAX_VALUE;

        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    org.bukkit.block.Block block = world.getBlockAt(bx + dx, by + dy, bz + dz);
                    if (!isAnvil(block.getType())) {
                        continue;
                    }
                    double dist = block.getLocation().distanceSquared(base);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = block.getLocation().add(0.5, 1.0, 0.5);
                    }
                }
            }
        }
        return best;
    }

    private boolean isAnvil(Material type) {
        return type == Material.ANVIL
                || type == Material.CHIPPED_ANVIL
                || type == Material.DAMAGED_ANVIL;
    }

    // ------------------------------------------------------------ 主循环

    private void tick() {
        if (sessions.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long idleLimit = settings.idleTimeoutSeconds() * 1000L;

        // 审计 #9：不再每 tick 拷贝一份 values()。直接迭代活 map，
        // 迭代中要下场的会话由 removeSession 记进 pendingRemovals，循环外统一摘除。
        ticking = true;
        pendingRemovals.clear();
        try {
            for (ForgeSession s : sessions.values()) {
                // 单个会话炸掉不能带走整个 ticker：Bukkit 的重复任务一抛异常就永久 cancel，
                // 那样所有玩家会一起卡在「锻造中」
                try {
                    tickSession(s, now, idleLimit);
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "锻造会话 tick 异常，已中断该会话：" + s.uuid(), ex);
                    try {
                        cancel(s, "<red>这一炉出了点问题，炉子已经关了。材料不退还，请联系服主。</red>");
                    } catch (RuntimeException ignored) {
                        removeSession(s);
                    }
                }
            }
        } finally {
            ticking = false;
        }
        for (ForgeSession s : pendingRemovals) {
            sessions.remove(s.uuid());
        }
    }

    /**
     * 会话下场统一入口：正常路径立刻从 map 摘除；
     * tick 主循环迭代期间只记进 pendingRemovals，循环结束后统一删（防 CME）。
     */
    private void removeSession(ForgeSession s) {
        // v2.23.1：随会话一起清掉两张辅助表，别让它们随玩家数只增不减
        repairSlots.remove(s.uuid());
        lastLocations.remove(s.uuid());
        if (ticking) {
            pendingRemovals.add(s);
        } else {
            sessions.remove(s.uuid());
        }
    }

    /**
     * 加热评分（纯函数，自检可直接算）。
     *
     * <p>窗口内按偏差平方衰减，只有正中最佳温度才接近 100；窗口外从窗口边缘的
     * 0.78 线性落到 3 倍容差处的 0。<b>两段在 dev == tol 处必须接上</b>——
     * 原来第二段从 0.95 起步，导致「稍微偏出窗口」反而比「贴着窗口内沿」分高。</p>
     */
    public static double heatScore(double deviation, double tolerance) {
        double tol = tolerance < 1e-4 ? 1e-4 : tolerance;
        double dev = Math.abs(deviation);
        if (dev <= tol) {
            double t = dev / tol;
            return 1.0 - t * t * 0.22;
        }
        if (dev >= tol * 3) {
            return 0.0;
        }
        return (1.0 - 0.22) * (1.0 - (dev - tol) / (tol * 2));
    }

    private void tickSession(ForgeSession s, long now, long idleLimit) {        Player p = s.player();
        if (p == null || !p.isOnline()) {
            discard(s);
            return;
        }
        // v2.23.1 修 P1-B：趁玩家还在线留一份站位（每秒一次），
        // discard 走到时人已离线，靠这份快照决定成品该掉在哪个已加载世界
        if (lastLocations.get(s.uuid()) == null || s.tickCounter() % 20 == 0) {
            lastLocations.put(s.uuid(), p.getLocation().clone());
        }
        if (now - s.lastActionMillis() > idleLimit) {
            // RESULT 阶段挂机不算作废：cancel 会先把成品发出去
            cancel(s, s.phase() == ForgePhase.RESULT
                    ? "<gray>成品已经收进背包了。"
                    : "<red>炉子冷了，这次锻造作废。</red>");
            return;
        }

        s.tick();
        // 玩家跑远（或换维度）就把演出实体收掉，避免 Display 残留
        if (s.stage() != null && !s.stage().isClosed()
                && s.stage().shouldClose(p.getLocation())) {
            s.stage().despawn();
        }
        switch (s.phase()) {
            case HEATING -> tickHeating(s, p);
            case HAMMERING -> tickHammering(s);
            case FOLDING -> tickFolding(s, p);
            case GRINDING -> tickGrinding(s, p);
            case POLISHING -> tickPolishing(s, p);
            case INLAY -> tickInlay(s, p);
            default -> {
            }
        }
    }

    private void tickHeating(ForgeSession s, Player p) {
        double decay = ForgeTuning.heatDecay(plugin, s.material().tier());
        // 天赋「控火」：降温更慢 = 炉温更好控
        s.temperature(s.temperature() - decay * s.env().heatDecayMultiplier() * s.talents().heatFactor());
        ForgeMaterial m = s.material();
        double temp = s.temperature();

        BossBar bar = s.bossBar();
        if (bar != null) {
            // 进度条平滑感靠 setProgress，保留每 tick；title 的整数温度每秒才会变一次，
            // 每 tick 发一个新 title 组件（字符串拼接 → Component → 网络包）纯属浪费（审计 #4）
            bar.setProgress(temp / 120.0);
            boolean inBand = m.inBand(temp);
            bar.setColor(inBand ? BarColor.GREEN : (m.deviation(temp) < m.tolerance() * 2 ? BarColor.YELLOW : BarColor.RED));
            if (s.tickCounter() % 20 == 0) {
                bar.setTitle("炉温 " + (int) temp + "°  |  最佳 " + (int) m.idealTemp()
                        + "° ±" + (int) m.tolerance());
            }
        }

        if (s.tickCounter() % 3 == 0) {
            Inventory open = topOf(p);
            if (open != null) {
                menu.renderTemperature(s, open);
            }
            String hint = m.inBand(temp)
                    ? "<#4ee36b>温度正好！左键「取出剑坯」"
                    : temp < m.idealTemp() ? "<#7fd3ff>还没到火候 · 拉风箱" : "<#ff9a3c>太热了 · 等一等";
            Msg.action(p, "<#ff9a3c>" + (int) temp + "°  <dark_gray>|  " + hint);
            if (settings.particles() && random.nextInt(4) == 0) {
                p.getWorld().spawnParticle(Particle.FLAME, feet(p), 3, 0.25, 0.1, 0.25, 0.01);
            }
            if (s.stage() != null && !s.stage().isClosed()) {
                s.stage().heatTick(temp);
            }
        }
    }

    private void tickHammering(ForgeSession s) {
        int interval = s.risk() ? RiskMode.hammerInterval(plugin)
                : ForgeTuning.hammerInterval(plugin, s.material().tier());
        if (s.tickCounter() % interval != 0) {
            return;
        }
        int next = s.pointer() + s.direction();
        if (next >= ForgeMenu.TRACK_LEN || next < 0) {
            s.flipDirection();
            next = s.pointer() + s.direction();
        }
        s.pointer(next);

        Player p = s.player();
        if (p != null) {
            Inventory open = topOf(p);
            if (open != null) {
                menu.renderTrack(s, open);
            }
        }
    }

    // ------------------------------------------------------------ 叠钢

    private void startFolding(ForgeSession s, Player p) {
        ForgeMaterial m = s.material();
        int len = Math.min(9, 3 + m.tier() / 2);
        s.foldSequence().clear();
        int last = -1;
        for (int i = 0; i < len; i++) {
            int pick;
            do {
                pick = s.rnd().nextInt(ForgeMenu.TRACK_LEN);
            } while (pick == last);
            s.foldSequence().add(pick);
            last = pick;
        }
        s.foldCursor(0);
        s.foldMissed(0);
        s.foldScore(0);
        // 档位越高，叠钢时限越短
        double foldSeconds = (settings.foldBaseSeconds() + settings.foldSecondsPerStep() * len
                + StatsStore.extraFoldSeconds(s.mastery()) + s.talents().foldSeconds())
                * ForgeTuning.foldSecondsScale(plugin, m.tier());
        s.foldDeadline(System.currentTimeMillis()
                + (long) (s.env().foldSecondsOf(foldSeconds) * 1000));
        s.phase(ForgePhase.FOLDING);

        open(s, menu.buildFolding(s));
        sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.4f);
        if (p == null) {
            return;
        }
        p.showTitle(Title.title(
                Msg.mm("<gradient:#ffd166:#ff9a3c><bold>叠 钢</bold></gradient>"),
                Msg.mm("<gray>按顺序点出序列 · 共 " + len + " 层"),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(900), Duration.ofMillis(300))));
        Msg.prefix(p, "<gray>折叠序列已生成：<white>" + formatSequence(s)
                + "</white>，限时 <white>"
                + Msg.fmt(settings.foldBaseSeconds() + settings.foldSecondsPerStep() * len, 1) + "s</white>。");
    }

    private String formatSequence(ForgeSession s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.foldSequence().size(); i++) {
            if (i > 0) {
                sb.append(" → ");
            }
            sb.append(s.foldSequence().get(i) + 1);
        }
        return sb.toString();
    }

    private void tickFolding(ForgeSession s, Player p) {
        long now = System.currentTimeMillis();
        if (now >= s.foldDeadline()) {
            finishFolding(s, p, true);
            return;
        }
        if (s.tickCounter() % 5 == 0) {
            Inventory open = topOf(p);
            if (open != null) {
                menu.renderFoldInfo(s, open);
            }
            Msg.action(p, "<#ffb84d>折叠 " + s.foldCursor() + "/" + s.foldSequence().size()
                    + " <dark_gray>| 剩余 " + Msg.fmt((s.foldDeadline() - now) / 1000.0, 1) + "s");
        }
    }

    private void clickFolding(ForgeSession s, Player p, int slot) {
        if (slot == ForgeMenu.QUIT_SLOT) {
            cancel(s, "<red>你放弃了这次锻造，材料没有退还。</red>");
            return;
        }
        int index = slot - ForgeMenu.TRACK_START;
        if (index < 0 || index >= ForgeMenu.TRACK_LEN) {
            return;
        }
        List<Integer> seq = s.foldSequence();
        int cursor = s.foldCursor();
        if (cursor >= seq.size()) {
            finishFolding(s, p, false);
            return;
        }
        if (seq.get(cursor) == index) {
            s.foldCursor(cursor + 1);
            sound(p, Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.2f + cursor * 0.08f);
            if (settings.particles() && p != null) {
                p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, feet(p).add(0, 1.0, 0),
                        6, 0.25, 0.2, 0.25, 0.03);
            }
            if (s.foldCursor() >= seq.size()) {
                finishFolding(s, p, false);
                return;
            }
            Inventory open = topOf(p);
            if (open != null) {
                menu.renderFoldTrack(s, open);
                menu.renderFoldInfo(s, open);
            }
            Msg.action(p, "<#4ee36b>+1 层 <dark_gray>| " + s.layers() + " 层 · " + s.pattern());
        } else {
            s.foldMissed(s.foldMissed() + 1);
            sound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.9f, 0.6f);
            s.foldDeadline(s.foldDeadline() - 600L); // 罚 0.6 秒
            if (s.foldMissed() >= 3) {
                finishFolding(s, p, true);
                return;
            }
            Inventory open = topOf(p);
            if (open != null) {
                menu.renderFoldInfo(s, open);
            }
            Msg.action(p, "<red>点错了 <dark_gray>| 失手 " + s.foldMissed() + "/3");
        }
    }

    private void finishFolding(ForgeSession s, Player p, boolean timeout) {
        if (maybeExplode(s, p, "叠钢")) {
            return;
        }
        int total = Math.max(1, s.foldSequence().size());
        double score = (double) s.foldCursor() / total - s.foldMissed() * 0.08;
        s.foldScore(Math.max(0.0, Math.min(1.0, score)));

        if (p != null) {
            if (timeout) {
                Msg.prefix(p, "<red>折叠中断：停在 <white>" + s.layers() + "</white> 层（"
                        + s.pattern() + "）</red>");
            } else {
                Msg.prefix(p, "<green>折叠完成：<white>" + s.layers() + " 层</white> · "
                        + s.pattern() + " <dark_gray>(" + Msg.fmt(s.foldScore() * 100, 0) + "%)");
            }
        }

        if (settings.grindingEnabled()) {
            startGrinding(s, p);
        } else {
            afterGrinding(s, p);
        }
    }

    // ------------------------------------------------------------ 收尾工序链（v2.19）

    /**
     * 工序顺序表：叠钢 → 开刃 → 抛光 → 嵌银丝，最后一定是淬火。
     *
     * <p>纯函数，不碰会话也不碰 config，自检可以直接把「关掉某道工序之后走到哪」算清楚。</p>
     *
     * @param done 刚做完的那道工序（HEATING / HAMMERING / null 都当作「还没开始」）
     * @return 下一道该做的工序；剩下的都关掉就返回 {@link ForgePhase#QUENCHING}
     */
    public static ForgePhase nextStage(ForgePhase done, boolean foldOn, boolean grindOn,
                                       boolean polishOn, boolean inlayOn) {
        boolean[] on = {foldOn, grindOn, polishOn, inlayOn};
        ForgePhase[] order = {ForgePhase.FOLDING, ForgePhase.GRINDING,
                ForgePhase.POLISHING, ForgePhase.INLAY};
        int from = 0;
        if (done != null) {
            for (int i = 0; i < order.length; i++) {
                if (order[i] == done) {
                    from = i + 1;
                    break;
                }
            }
        }
        for (int i = from; i < order.length; i++) {
            if (on[i]) {
                return order[i];
            }
        }
        return ForgePhase.QUENCHING;
    }

    /** 按当前 config 开关走到下一道工序 */
    private void follow(ForgeSession s, Player p, ForgePhase done) {
        ForgePhase next = nextStage(done, settings.foldingEnabled(), settings.grindingEnabled(),
                settings.polishingEnabled(), settings.inlayEnabled());
        switch (next) {
            case FOLDING -> startFolding(s, p);
            case GRINDING -> startGrinding(s, p);
            case POLISHING -> startPolishing(s, p);
            case INLAY -> startInlay(s, p);
            default -> startQuenching(s, p);
        }
    }

    private void afterFolding(ForgeSession s, Player p) {
        follow(s, p, ForgePhase.FOLDING);
    }

    private void afterGrinding(ForgeSession s, Player p) {
        follow(s, p, ForgePhase.GRINDING);
    }

    private void afterPolishing(ForgeSession s, Player p) {
        follow(s, p, ForgePhase.POLISHING);
    }

    // ------------------------------------------------------------ 开刃

    private void startGrinding(ForgeSession s, Player p) {
        s.phase(ForgePhase.GRINDING);
        int tier = s.material().tier();
        s.grindValue(50);
        s.grindTargetCenter(30 + s.rnd().nextInt(41));
        // 档位越高，绿区越窄、压力漂得越猛
        s.grindTargetWidth(s.env().grindWidthOf((6 + s.rnd().nextInt(5)
                + StatsStore.extraGrindWidth(s.mastery()) + s.talents().grindWidth())
                * ForgeTuning.grindWidthScale(plugin, tier)));
        s.grindDrift((s.rnd().nextDouble() - 0.5) * 2.9 * ForgeTuning.grindDriftScale(plugin, tier));
        s.grindDriftTimer(18);
        s.grindTicksIn(0);
        s.grindTicksTotal(0);
        s.grindScore(0);

        open(s, menu.buildGrinding(s));
        sound(p, Sound.BLOCK_GRINDSTONE_USE, 0.9f, 1.1f);
        if (p == null) {
            return;
        }
        p.showTitle(Title.title(
                Msg.mm("<gradient:#7ef2c8:#4ea8ff><bold>开 刃</bold></gradient>"),
                Msg.mm("<gray>把刃压保持在绿色区间"),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(900), Duration.ofMillis(300))));
        Msg.prefix(p, "<gray>研磨 <white>" + Msg.fmt(settings.grindDurationTicks() / 20.0, 1)
                + " 秒</white>：压力会自己漂，用 <white>加压</white> / <white>减压</white> 拉回绿区。");
    }

    private void tickGrinding(ForgeSession s, Player p) {
        s.incGrindTicksTotal();

        if (s.grindDriftTimer() <= 0) {
            s.grindDriftTimer(12 + s.rnd().nextInt(14));
            s.grindDrift((s.rnd().nextDouble() - 0.5) * 2.9
                    * ForgeTuning.grindDriftScale(plugin, s.material().tier()));
        } else {
            s.grindDriftTimer(s.grindDriftTimer() - 1);
        }
        s.grindValue(s.grindValue() + s.grindDrift());
        if (s.grindInZone()) {
            s.incGrindTicksIn();
        }
        s.grindScore((double) s.grindTicksIn() / Math.max(1, s.grindTicksTotal()));

        if (s.tickCounter() % 2 == 0) {
            Inventory open = topOf(p);
            if (open != null) {
                menu.renderGrindTrack(s, open);
                menu.renderGrindInfo(s, open);
            }
        }
        if (s.tickCounter() % 4 == 0 && p != null) {
            Msg.action(p, (s.grindInZone() ? "<#4ee36b>压力正好 " : "<#ff9a3c>拉回绿区 ")
                    + "<dark_gray>| " + Msg.fmt(s.grindValue(), 0) + " | 稳定 "
                    + Msg.fmt(s.grindScore() * 100, 0) + "%");
            if (settings.particles()) {
                p.getWorld().spawnParticle(s.grindInZone() ? Particle.ELECTRIC_SPARK : Particle.SMOKE,
                        feet(p).add(0, 1.0, 0), 4, 0.25, 0.15, 0.25, 0.02);
            }
        }

        if (s.grindTicksTotal() >= grindTarget(s)) {
            if (s.repairing()) {
                finishRepair(s, p);
            } else {
                finishGrinding(s, p);
            }
        }
    }

    private int grindTarget(ForgeSession s) {
        return s.repairing() ? settings.repairDurationTicks() : settings.grindDurationTicks();
    }

    // ------------------------------------------------------------ 修复结算

    private void finishRepair(ForgeSession s, Player p) {
        s.grindScore((double) s.grindTicksIn() / Math.max(1, s.grindTicksTotal()));
        ItemStack target = s.repairTarget();
        // v2.23.1 修 P1-A：开局槽位必须在 removeSession 之前取出来（那里会清掉这张表），
        // -1 = 没有记录（理论上不会发生），走「直接发给玩家」而不做任何模糊匹配
        int repairSlot = repairSlots.getOrDefault(s.uuid(), -1);
        s.finished(true);
        removeSession(s); // 审计 #9：下场统一走 removeSession，tick 迭代中只记账
        if (s.bossBar() != null) {
            s.bossBar().removeAll();
            s.bossBar(null);
        }
        if (p == null) {
            return;
        }
        if (target == null) {
            Msg.prefix(p, "<red>修复目标丢失了。</red>");
            return;
        }

        int max = items.maxDurabilityOf(target);
        int before = items.durabilityOf(target);
        // 稳定度决定恢复量：满稳恢复 60%，最低 10%
        double ratio = 0.10 + s.grindScore() * 0.50;
        int gain = (int) Math.max(1, Math.round(max * ratio));
        int after = Math.min(max, before + gain);
        items.setDurability(target, after);
        plugin.stats().countRepair(p.getUniqueId());

        // 关键：把背包里那把「原物」换成修好的这把，不能直接在旁边再放一把，
        // 否则就是白送一件装备（旧剑还在手上）
        // v2.23.1 修 P1-A：只按开局槽位替换，见 replaceRepaired 的说明
        boolean replaced = replaceRepaired(p, target, before, repairSlot);
        if (!replaced) {
            // 原物已不在那一格（被挪走 / 丢出 / 交给别人）：直接把修好的这件发回去，
            // 绝不在背包里做模糊匹配——宁可多一件（玩家自己处理），也不复制
            if (p.getInventory().getItemInMainHand().getType().isAir()) {
                p.getInventory().setItemInMainHand(target);
            } else {
                var rest = p.getInventory().addItem(target);
                for (var r : rest.values()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), r);
                }
            }
            Msg.prefix(p, "<yellow>原来的那一格已经不是这件装备了（被挪走或丢掉），修好的这件已发给你。</yellow>");
        }

        sound(p, Sound.BLOCK_GRINDSTONE_USE, 1.0f, 1.4f);
        sound(p, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.8f);
        if (settings.particles()) {
            p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, feet(p).add(0, 1.0, 0),
                    18, 0.35, 0.3, 0.35, 0.04);
        }
        Msg.prefix(p, "<green>研磨完成：稳定度 <white>" + Msg.fmt(s.grindScore() * 100, 1)
                + "%</white> · 耐久 <white>" + before + " → " + after + " / " + max + "</white>"
                + (after >= max ? " <#4ee36b>（完全修复）" : ""));
        p.closeInventory();
    }

    /**
     * 把背包里「修复前的那一件」原地换成修好的（只认开局槽位）。
     *
     * <p><b>v2.23.1 修 P1-A（原样保留旧行为说明）：</b>以前是「材料 id + 修复前耐久」
     * 全背包线性扫，命中第一个就覆盖。两把同胚剑（同 {@code material_id}）耐久又恰好
     * 相同时（例如同一次副本里挨了同样刀数），研磨 A 会把 B 覆盖成「A 的克隆（耐久已修）」：
     * A 原地不动、B 变成 A 的副本 → 白送一件满强化成品，同时吞掉 B 的全部属性、词条、宝石。</p>
     *
     * <p>现在只按 {@link #beginRepair} 记下的槽位替换，并做三重校验：
     * 仍是锻造品、材料 id 与目标一致、耐久仍等于修复前的值。任一不满足就返回 false，
     * 由调用方走 {@code giveBack}（塞背包 / 掉在脚下），<b>绝不做模糊匹配</b>——
     * 最坏情况是多出一件同名装备（玩家可自行丢弃），而不是复制。</p>
     *
     * @param slot 开局时的 {@code getHeldItemSlot()}，-1 表示没有记录
     * @return 找到并替换返回 true
     */
    private boolean replaceRepaired(Player p, ItemStack repaired, int beforeDurability, int slot) {
        var inv = p.getInventory();
        if (slot < 0 || slot >= inv.getSize()) {
            return false; // 槽位无效：不猜、不扫，交给 giveBack
        }
        ItemStack it = inv.getItem(slot);
        if (it == null || it.getType().isAir() || !items.isForged(it)) {
            return false;
        }
        if (!java.util.Objects.equals(items.materialIdOf(it), items.materialIdOf(repaired))) {
            return false;
        }
        if (items.durabilityOf(it) != beforeDurability) {
            return false; // 这一格已经换成别的（或同胚另一把）：不覆盖
        }
        inv.setItem(slot, repaired);
        return true;
    }

    private void clickGrinding(ForgeSession s, Player p, int slot) {
        if (slot == ForgeMenu.QUIT_SLOT) {
            cancel(s, "<red>你放弃了这次锻造，材料没有退还。</red>");
            return;
        }
        double step = settings.grindPressStep();
        // 加压在左上、减压在右上；9~17 是轨道，不能放按钮
        if (slot == ForgeMenu.PRESS_UP_SLOT) {
            s.grindValue(s.grindValue() + step);
            sound(p, Sound.BLOCK_STONE_HIT, 0.5f, 1.4f);
        } else if (slot == ForgeMenu.PRESS_DOWN_SLOT) {
            s.grindValue(s.grindValue() - step);
            sound(p, Sound.BLOCK_STONE_HIT, 0.5f, 0.8f);
        } else {
            return;
        }
        Inventory open = topOf(p);
        if (open != null) {
            menu.renderGrindTrack(s, open);
            menu.renderGrindInfo(s, open);
        }
    }

    private void finishGrinding(ForgeSession s, Player p) {
        if (maybeExplode(s, p, "开刃")) {
            return;
        }
        s.grindScore((double) s.grindTicksIn() / Math.max(1, s.grindTicksTotal()));
        if (p != null) {
            Msg.prefix(p, "<green>开刃完成：稳定度 <white>"
                    + Msg.fmt(s.grindScore() * 100, 1) + "%</white>");
        }
        afterGrinding(s, p);
    }

    // ------------------------------------------------------------ 抛光（v2.19）

    private void startPolishing(ForgeSession s, Player p) {
        int tier = s.material().tier();
        s.polishPass(0);
        s.polishPasses(settings.polishPasses());
        s.polishHits(0);
        s.polishLastError(-1);
        s.polishScore(0);
        s.polishValue(0);
        s.polishDir(1);
        s.polishSpeed(settings.polishSpeedBase() * ForgeTuning.polishSpeedScale(plugin, tier));
        s.polishWidth(settings.polishWindowBase() * ForgeTuning.polishWindowScale(plugin, tier));
        rerollPolishTarget(s);
        s.polishDeadline(System.currentTimeMillis() + polishDeadlineMillis(s));
        s.phase(ForgePhase.POLISHING);

        open(s, menu.buildPolishing(s));
        sound(p, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.9f, 1.4f);
        if (p == null) {
            return;
        }
        p.showTitle(Title.title(
                Msg.mm("<gradient:#ffffff:#9fe8ff><bold>抛 光</bold></gradient>"),
                Msg.mm("<gray>指针扫进亮区时下刀 · 共 " + s.polishPasses() + " 刀"),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(900), Duration.ofMillis(300))));
        Msg.prefix(p, "<gray>光泽指针会自己来回扫：扫到 <white>亮区</white> 时点「下刀」，"
                + "越靠正中成色越好（共 " + s.polishPasses() + " 刀）。");
    }

    /** 抛光的兜底时限：一刀给 6 秒 + 4 秒启动余量 */
    private long polishDeadlineMillis(ForgeSession s) {
        return (s.polishPasses() * 6000L) + 4000L;
    }

    /** 重掷亮区中心：留出余量，别贴着标尺两端，否则指针扫不到正中 */
    private void rerollPolishTarget(ForgeSession s) {
        double margin = Math.min(45.0, s.polishWidth() + 5.0);
        double lo = margin;
        double hi = 100.0 - margin;
        if (hi <= lo) {
            s.polishCenter(50.0);
            return;
        }
        s.polishCenter(lo + s.rnd().nextDouble() * (hi - lo));
    }

    private void tickPolishing(ForgeSession s, Player p) {
        // 指针往返扫动：撞到 0 / 100 就反弹，和锻打指针同一套手感
        double next = s.polishValue() + s.polishDir() * s.polishSpeed();
        if (next > 100.0) {
            next = 200.0 - next;
            s.polishDir(-1);
        } else if (next < 0.0) {
            next = -next;
            s.polishDir(1);
        }
        s.polishValue(next);

        Inventory open = topOf(p);
        if (open != null) {
            menu.renderPolishTrack(s, open);
        }
        if (s.tickCounter() % 4 == 0 && p != null) {
            Msg.action(p, (s.polishInZone() ? "<#4ee36b>就是现在，下刀！" : "<#ff9a3c>等指针进亮区 ")
                    + "<dark_gray>| " + Msg.fmt(s.polishValue(), 0)
                    + " | 成色 " + Msg.fmt(s.polishScore() * 100, 0) + "%");
        }
        if (System.currentTimeMillis() > s.polishDeadline()) {
            finishPolishing(s, p, true);
        }
    }

    private void clickPolishing(ForgeSession s, Player p, int slot) {
        if (slot == ForgeMenu.QUIT_SLOT) {
            cancel(s, "<red>你放弃了这次锻造，材料没有退还。</red>");
            return;
        }
        if (slot != ForgeMenu.STRIKE_SLOT) {
            return;
        }
        double err = s.polishValue() - s.polishCenter();
        double grade = s.polishGrade(err);
        s.polishLastError(Math.abs(err));
        if (Math.abs(err) <= s.polishWidth()) {
            s.polishHits(s.polishHits() + 1);
        }
        int done = s.polishPass() + 1;
        s.polishPass(done);
        // 累计成色 = 已下刀的算术平均（漏掉的刀由 finishPolishing 按 0 摊平）
        s.polishScore((s.polishScore() * (done - 1) + grade) / done);

        sound(p, grade >= 0.999 ? Sound.BLOCK_NOTE_BLOCK_BELL : Sound.BLOCK_GRINDSTONE_USE,
                0.8f, grade >= 0.999 ? 1.6f : 1.1f);
        if (settings.particles() && p != null) {
            p.getWorld().spawnParticle(grade >= 0.999 ? Particle.ELECTRIC_SPARK : Particle.SMOKE,
                    feet(p).add(0, 1.0, 0), grade >= 0.999 ? 8 : 3, 0.25, 0.2, 0.25, 0.02);
        }

        if (done >= s.polishPasses()) {
            finishPolishing(s, p, false);
            return;
        }
        rerollPolishTarget(s);
        Inventory open = topOf(p);
        if (open != null) {
            menu.renderPolishTrack(s, open);
            menu.renderPolishInfo(s, open);
        }
        if (p != null) {
            Msg.action(p, (grade >= 0.999 ? "<#4ee36b>正中！" : grade > 0.4 ? "<#ffd166>还行" : "<#ff9a3c>偏了")
                    + " <dark_gray>| 第 " + done + "/" + s.polishPasses() + " 刀");
        }
    }

    private void finishPolishing(ForgeSession s, Player p, boolean timeout) {
        // 超时收工：没来得及下的刀按 0 分进场，成色不许凭空留着
        int done = s.polishPass();
        if (done < s.polishPasses()) {
            s.polishScore(done <= 0 ? 0.0 : s.polishScore() * done / s.polishPasses());
        }
        if (p != null) {
            Msg.prefix(p, (timeout ? "<red>抛光超时收工：" : "<green>抛光完成：")
                    + "成色 <white>" + Msg.fmt(s.polishScore() * 100, 1) + "%</white>"
                    + " <dark_gray>(" + s.polishHits() + "/" + s.polishPasses() + " 刀进亮区)");
        }
        // 抛光属于冷加工，极限锻造不在这里爆剑（爆剑点仍是锻打 / 叠钢 / 开刃）
        afterPolishing(s, p);
    }

    // ------------------------------------------------------------ 嵌银丝（v2.19）

    private void startInlay(ForgeSession s, Player p) {
        int tier = s.material().tier();
        int len = ForgeTuning.inlayLength(settings.inlayLengthBase(), tier);
        s.inlaySequence().clear();
        int last = -1;
        for (int i = 0; i < len; i++) {
            int pick;
            do {
                pick = s.rnd().nextInt(ForgeMenu.TRACK_LEN);
            } while (pick == last);
            s.inlaySequence().add(pick);
            last = pick;
        }
        int window = Math.max(5, (int) Math.round(
                settings.inlayWindowTicks() * ForgeTuning.inlayWindowScale(plugin, tier)));
        s.inlayWindow(window);
        s.inlayWindowTotal(window);
        s.inlayWindowLeft(window);
        s.inlayCursor(0);
        s.inlayHits(0);
        s.inlayMiss(0);
        s.inlayScore(0);
        s.inlayDeadline(System.currentTimeMillis() + inlayDeadlineMillis(len, window));
        s.phase(ForgePhase.INLAY);

        open(s, menu.buildInlay(s));
        sound(p, Sound.BLOCK_CHAIN_PLACE, 0.8f, 1.3f);
        if (p == null) {
            return;
        }
        p.showTitle(Title.title(
                Msg.mm("<gradient:#ffffff:#c9d6ff><bold>嵌 银 丝</bold></gradient>"),
                Msg.mm("<gray>银丝亮起时点它 · 共 " + len + " 根"),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(900), Duration.ofMillis(300))));
        Msg.prefix(p, "<gray>银丝会一根根亮起来：在 <white>亮窗内</white> 点中那根才算嵌上，"
                + "亮窗灭了就算失手（共 " + len + " 根，每根 " + window + " tick）。");
    }

    /** 嵌丝的兜底时限：每根按 3.2 倍亮窗算，再给 2 秒 */
    private long inlayDeadlineMillis(int len, int window) {
        return (long) len * window * 160L + 2000L;
    }

    /** 嵌丝成色 = 命中率 - 失手罚分（每根 0.05），夹在 0~1 */
    private double inlayScoreOf(ForgeSession s) {
        double raw = (double) s.inlayHits() / Math.max(1, s.inlaySequence().size())
                - s.inlayMiss() * 0.05;
        return Math.max(0.0, Math.min(1.0, raw));
    }

    private void tickInlay(ForgeSession s, Player p) {
        s.inlayWindowLeft(s.inlayWindowLeft() - 1);
        if (s.inlayWindowLeft() <= 0) {
            // 亮窗灭了：算失手，直接换下一根，不错位
            s.inlayMiss(s.inlayMiss() + 1);
            s.inlayCursor(s.inlayCursor() + 1);
            s.inlayWindowLeft(s.inlayWindow());
            sound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
            if (p != null) {
                Msg.action(p, "<red>这根亮了没点上 <dark_gray>| 失手 " + s.inlayMiss());
            }
        }
        s.inlayScore(inlayScoreOf(s));

        Inventory open = topOf(p);
        if (open != null) {
            menu.renderInlayTrack(s, open);
            if (s.tickCounter() % 3 == 0) {
                menu.renderInlayInfo(s, open);
            }
        }
        if (s.inlayDone() || System.currentTimeMillis() > s.inlayDeadline()) {
            finishInlay(s, p, s.inlayDone() ? false : true);
        }
    }

    private void clickInlay(ForgeSession s, Player p, int slot) {
        if (slot == ForgeMenu.QUIT_SLOT) {
            cancel(s, "<red>你放弃了这次锻造，材料没有退还。</red>");
            return;
        }
        int index = slot - ForgeMenu.TRACK_START;
        if (index < 0 || index >= ForgeMenu.TRACK_LEN) {
            return;
        }
        int target = s.inlayTarget();
        if (target < 0) {
            return;
        }
        if (index == target) {
            s.inlayHits(s.inlayHits() + 1);
            s.inlayCursor(s.inlayCursor() + 1);
            s.inlayWindowLeft(s.inlayWindow());
            sound(p, Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.2f + s.inlayCursor() * 0.06f);
            if (settings.particles() && p != null) {
                p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, feet(p).add(0, 1.0, 0),
                        5, 0.2, 0.2, 0.2, 0.02);
            }
        } else {
            // 点错不推进，但亮窗被缩短——乱点会被反过来惩罚
            s.inlayMiss(s.inlayMiss() + 1);
            s.inlayWindowLeft(Math.max(1, s.inlayWindowLeft() - 3));
            sound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.9f, 0.6f);
        }
        s.inlayScore(inlayScoreOf(s));

        if (s.inlayDone()) {
            finishInlay(s, p, false);
            return;
        }
        Inventory open = topOf(p);
        if (open != null) {
            menu.renderInlayTrack(s, open);
            menu.renderInlayInfo(s, open);
        }
        if (p != null) {
            Msg.action(p, (index == target ? "<#4ee36b>嵌上了" : "<red>点错了")
                    + " <dark_gray>| " + s.inlayCursor() + "/" + s.inlaySequence().size());
        }
    }

    private void finishInlay(ForgeSession s, Player p, boolean timeout) {
        s.inlayScore(inlayScoreOf(s));
        if (p != null) {
            Msg.prefix(p, (timeout ? "<red>嵌丝超时收工：" : "<green>银丝嵌好：")
                    + "成色 <white>" + Msg.fmt(s.inlayScore() * 100, 1) + "%</white>"
                    + " <dark_gray>(命中 " + s.inlayHits() + "/" + s.inlaySequence().size()
                    + "，失手 " + s.inlayMiss() + ")");
        }
        startQuenching(s, p);
    }

    public void handleClick(ForgeSession s, int slot, boolean shift) {
        Player p = s.player();
        if (p == null || s.finished()) {
            return;
        }
        s.touch();
        switch (s.phase()) {
            case HEATING -> clickHeating(s, p, slot);
            case HAMMERING -> clickHammering(s, p, slot);
            case FOLDING -> clickFolding(s, p, slot);
            case GRINDING -> clickGrinding(s, p, slot);
            case POLISHING -> clickPolishing(s, p, slot);
            case INLAY -> clickInlay(s, p, slot);
            case QUENCHING -> clickQuenching(s, p, slot);
            case RESULT -> clickResult(s, p, slot);
        }
    }

    private void clickHeating(ForgeSession s, Player p, int slot) {
        if (slot == ForgeMenu.QUIT_SLOT) {
            cancel(s, "<red>你放弃了这次锻造，材料没有退还。</red>");
            return;
        }
        if (slot == 10) {
            if (s.bellowsCooldown() > 0) {
                return;
            }
            s.bellowsCooldown(settings.bellowsCooldownTicks());
            s.temperature(s.temperature() + settings.bellowsGain());
            sound(p, Sound.BLOCK_FURNACE_FIRE_CRACKLE, 0.8f, 0.9f);
            sound(p, Sound.BLOCK_LAVA_POP, 0.4f, 1.6f);
            if (settings.particles()) {
                p.getWorld().spawnParticle(Particle.FLAME, feet(p).add(0, 0.8, 0), 10, 0.3, 0.3, 0.3, 0.02);
            }
            Inventory open = topOf(p);
            if (open != null) {
                menu.renderTemperature(s, open);
            }
            return;
        }
        if (slot == 13) {
            ForgeMaterial m = s.material();
            double dev = m.deviation(s.temperature());
            // v2.0 师徒：师父在线时徒弟容差额外加成（MentorManager 内规则）
            double mentorBonus = plugin.mentors() == null ? 0.0 : plugin.mentors().apprenticeToleranceBonus(p);
            double tol = m.tolerance() * StatsStore.toleranceMultiplier(s.mastery())
                    * (1.0 + mentorBonus) * (1.0 + s.blueprintBonus())
                    * (1.0 + s.talents().tolerance());
            // 容差配成 0 会让 dev/tol 变成 NaN，NaN 一路污染总分与品质判定
            if (tol < 1e-4) {
                tol = 1e-4;
            }
            double score = heatScore(dev, tol);
            if (s.temperature() > 112) {
                score *= 0.35;
                Msg.prefix(p, "<red>剑坯有点烧过头了……");
            }
            s.heatScore(Math.max(0.0, Math.min(1.0, score)));
            s.phase(ForgePhase.HAMMERING);
            s.pointer(0);
            s.zoneCenter(1 + m.zoneWidth() + s.rnd().nextInt(Math.max(1, 7 - m.zoneWidth() * 2)));

            if (s.bossBar() != null) {
                s.bossBar().removeAll();
                s.bossBar(null);
            }
            open(s, menu.buildHammering(s));
            sound(p, Sound.BLOCK_ANVIL_USE, 0.9f, 1.0f);
            p.showTitle(Title.title(
                    Msg.mm("<gradient:#7fd3ff:#4e7fff><bold>锻 打</bold></gradient>"),
                    Msg.mm("<gray>指针进绿格时敲击 · " + m.strikes() + " 锤"),
                    Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(900), Duration.ofMillis(300))));
            Msg.prefix(p, "<gray>加热评分 <white>" + Msg.fmt(s.heatScore() * 100, 1)
                    + "</white>，开始敲吧。");
        }
    }

    private void clickHammering(ForgeSession s, Player p, int slot) {
        if (slot == ForgeMenu.QUIT_SLOT) {
            cancel(s, "<red>你放弃了这次锻造，材料没有退还。</red>");
            return;
        }
        if (slot != ForgeMenu.STRIKE_SLOT) {
            return;
        }
        int dist = Math.abs(s.pointer() - s.zoneCenter());
        int zone = s.material().zoneWidth() + StatsStore.extraZoneWidth(s.mastery())
                + s.talents().zone();
        if (s.risk()) {
            zone = Math.max(0, zone - RiskMode.zoneShrink(plugin)); // 极限锻造：命中区变窄
        }
        ForgeSession.Hit hit = dist == 0
                ? ForgeSession.Hit.PERFECT
                : (dist <= zone ? ForgeSession.Hit.GOOD : ForgeSession.Hit.MISS);

        s.addStrike(hit);
        if (s.stage() != null && !s.stage().isClosed()) {
            s.stage().strike(hit);
        }
        // GUI 内再演一次：容器打开时视角是锁死的，世界里的动画很可能被界面挡住
        Inventory gui = topOf(p);
        if (gui != null) {
            String mark = hit == ForgeSession.Hit.PERFECT ? "<#4ee36b><bold>当 ！</bold>"
                    : hit == ForgeSession.Hit.GOOD ? "<#ffd166><bold>当 ！</bold>"
                    : "<#ff5252><bold>哐 ……</bold>";
            gui.setItem(13, ForgeMenu.icon(Material.MACE, mark,
                    List.of("<dark_gray>" + hit.display + " · " + Msg.fmt(s.hammerScore() * 100, 0) + " 分")));
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                Inventory now = topOf(p);
                if (now != null && s.phase() == ForgePhase.HAMMERING) {
                    menu.renderHammerInfo(s, now);
                    // 13 号位恢复成填充
                    now.setItem(13, ForgeMenu.filler());
                }
            }, 4L);
        }
        sound(p, hit == ForgeSession.Hit.MISS ? Sound.BLOCK_ANVIL_LAND : Sound.BLOCK_ANVIL_USE,
                hit == ForgeSession.Hit.MISS ? 0.6f : 1.0f,
                hit == ForgeSession.Hit.PERFECT ? 1.6f : 1.1f);
        if (settings.particles()) {
            p.getWorld().spawnParticle(hit == ForgeSession.Hit.MISS ? Particle.SMOKE : Particle.CRIT,
                    feet(p).add(0, 1.0, 0), hit == ForgeSession.Hit.PERFECT ? 14 : 6,
                    0.35, 0.2, 0.35, 0.05);
        }
        Msg.action(p, "<" + hit.color + ">" + hit.display
                + " <dark_gray>| 评分 " + Msg.fmt(s.hammerScore() * 100, 0));

        if (s.strikesDone() >= s.material().strikes()) {
            if (maybeExplode(s, p, "锻打")) {
                return;
            }
            if (settings.foldingEnabled()) {
                startFolding(s, p);
            } else {
                // 叠钢关掉：交给收尾工序链继续按开关往下走（开刃 → 抛光 → 嵌银丝 → 淬火）
                afterFolding(s, p);
            }
            return;
        }
        // 下一锤
        s.zoneCenter(1 + s.material().zoneWidth()
                + s.rnd().nextInt(Math.max(1, 7 - s.material().zoneWidth() * 2)));
        s.pointer(0);
        Inventory open = topOf(p);
        if (open != null) {
            menu.renderHammerInfo(s, open);
            menu.renderTrack(s, open);
        }
    }

    private void clickQuenching(ForgeSession s, Player p, int slot) {
        if (slot == ForgeMenu.QUIT_SLOT) {
            cancel(s, "<red>你放弃了这次锻造，材料没有退还。</red>");
            return;
        }
        List<Quench> list = quenches.all();
        Quench picked = null;
        int[] slots = ForgeMenu.QUENCH_SLOTS;
        for (int i = 0; i < slots.length && i < list.size(); i++) {
            if (slots[i] == slot) {
                picked = list.get(i);
                break;
            }
        }
        if (picked == null) {
            return;
        }
        settle(s, p, picked);
    }

    private void startQuenching(ForgeSession s, Player p) {
        s.phase(ForgePhase.QUENCHING);
        open(s, menu.buildQuenching(s, quenches.all()));
        sound(p, Sound.ITEM_BUCKET_FILL, 1.0f, 1.0f);
        if (p == null) {
            return;
        }
        p.showTitle(Title.title(
                Msg.mm("<gradient:#9fe8ff:#4ea8ff><bold>淬 火</bold></gradient>"),
                Msg.mm("<gray>选一种介质定格属性"),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(900), Duration.ofMillis(300))));
    }

    /**
     * 加权求总分（2.18 及以前的五工序口径）。
     *
     * <p>保留这个 7 参重载只为了让旧调用点语义不变：抛光 / 嵌银丝按「这道工序不存在」处理，
     * 结果与 2.18 的公式逐点一致（满配 100、全 0 得 0）。</p>
     */
    public static double weightedScore(double heat, double hammer, double fold, double grind,
                                       double quenchBonus, boolean foldOn, boolean grindOn) {
        return weightedScore(heat, hammer, fold, grind, 0, 0, quenchBonus,
                foldOn, grindOn, false, false);
    }

    /**
     * 加权求总分（2.19 起七工序口径）。
     *
     * <p>权重按「满配 100」的比例给：火候 20 / 锻打 35 / 叠钢 20 / 开刃 10 /
     * 抛光 8 / 嵌银丝 7 / 淬火 15。</p>
     *
     * <p>关掉某道工序时只把这道工序从权重总和里摘掉，<b>不做固定偏移</b>：
     * 剩下的工序按各自权重占比重新归一，满配仍是 100、全 0 仍是 0。这样以后再加
     * 工序不必手工配平系数，也不会出现「关掉一道之后满分变 97」的漂移。</p>
     */
    public static double weightedScore(double heat, double hammer, double fold, double grind,
                                       double polish, double inlay, double quenchBonus,
                                       boolean foldOn, boolean grindOn,
                                       boolean polishOn, boolean inlayOn) {
        double wHeat = 20, wHammer = 35, wFold = 20, wGrind = 10;
        double wPolish = 8, wInlay = 7, wQuench = 15;
        if (!foldOn) {
            wFold = 0;
        }
        if (!grindOn) {
            wGrind = 0;
        }
        if (!polishOn) {
            wPolish = 0;
        }
        if (!inlayOn) {
            wInlay = 0;
        }
        double totalWeight = wHeat + wHammer + wFold + wGrind + wPolish + wInlay + wQuench;
        double raw = heat * wHeat + hammer * wHammer + fold * wFold + grind * wGrind
                + polish * wPolish + inlay * wInlay + (quenchBonus / 15.0) * wQuench;
        return Math.max(0.0, Math.min(100.0, raw / totalWeight * 100.0));
    }

    /** 结算：加权求总分 → 品质 → 词条 → 出物品 */
    private void settle(ForgeSession s, Player p, Quench picked) {
        s.quench(picked);
        s.touch();

        double total = weightedScore(s.heatScore(), s.hammerScore(), s.foldScore(), s.grindScore(),
                s.polishScore(), s.inlayScore(), picked.scoreBonus(),
                settings.foldingEnabled(), settings.grindingEnabled(),
                settings.polishingEnabled(), settings.inlayEnabled());
        // 环境修正：淬火收益倍率（天气 + 时辰），w7-env
        total = Math.max(0.0, Math.min(100.0, total * s.env().quenchMultiplier()));
        // 材料档次抬上限：精炼 +6%，天外 +12%
        total = total * (1.0 + s.grade().scoreBonus() / 100.0);
        // 档位越高越压分，品质只留给手最稳的人
        total = total * ForgeTuning.scoreFactor(plugin, s.material().tier());
        if (s.risk()) {
            total = total * RiskMode.scoreMultiplier(plugin); // 极限锻造：成功分数提高（D3）
        }
        total = Math.max(0.0, Math.min(100.0, total));
        s.finalScore(Math.round(total * 10.0) / 10.0);
        s.quality(Quality.of(s.finalScore()));

        int count = s.quality().affixCount();
        boolean flawless = s.heatScore() >= 0.999 && s.hammerScore() >= 0.99
                && (!settings.foldingEnabled() || s.foldScore() >= 0.99)
                && (!settings.grindingEnabled() || s.grindScore() >= 0.99)
                && (!settings.polishingEnabled() || s.polishScore() >= 0.99)
                && (!settings.inlayEnabled() || s.inlayScore() >= 0.99);
        if (flawless) {
            count++; // 全流程无失手，多给一条
        }
        if (random.nextDouble() < stats.extraAffixChance(s.mastery()) + s.talents().affixChance()) {
            count++; // 专精加成：额外词条
        }
        count += s.grade().extraAffixes(); // 天外料额外词条
        count = Math.min(count, 6);
        s.affixValues().clear();
        // 装备只吃防御词条，武器按淬火介质的词条池抽
        List<Affix> pickedAffixes = s.material().armor()
                ? affixes.pickStrict(ARMOR_AFFIX_POOL, count)
                : affixes.pick(picked.pool(), count, WEAPON_AFFIX_POOL);
        for (Affix a : pickedAffixes) {
            // 词条按材料档位缩放：低级料数值低且稳定，高级料数值高、波动也大
            s.affixValues().put(a, a.roll(random,
                    AffixScaling.valueScale(plugin, s.material().tier()),
                    AffixScaling.spreadScale(plugin, s.material().tier())));
        }

        // 剑型（v2.14）：按权重随机定，估值乘区与 Lore 都吃它
        if (plugin.forms() != null && !plugin.forms().isEmpty()) {
            var pickedForm = plugin.forms().randomPick(random);
            // 名剑谱（拓展第二档）：已解锁的 weight-0 特型按 spectrum.special-form-chance 优先
            pickedForm = plugin.spectrum() != null
                    ? plugin.spectrum().trySpecialForm(p.getUniqueId(), random, pickedForm) : pickedForm;
            s.formId(pickedForm == null ? null : pickedForm.id());
        }

        ItemStack result = items.build(s);
        // 剑魂传承：上一把碎剑攒下的等级直接继承到这把新剑上（免费，setLevel 内部重算属性）
        int inherited = plugin.strengthen() != null
                ? plugin.strengthen().applyInherit(p.getUniqueId(), result) : 0;
        if (inherited > 0) {
            Msg.prefix(p, "<gradient:#9f7aea:#4dc3ff>剑魂传承：</gradient> <gray>碎剑之魂注入新胚，"
                    + "这把剑天生 <white>+" + inherited + "</white>。</gray>");
        }
        // 龙税契约 auto-join：锻了第一把剑就算入局
        if (plugin.contractManager() != null) {
            plugin.contractManager().autoJoin(p.getUniqueId());
        }
        if (s.risk()) {
            RiskMode.markRisk(result); // 打 RISK_MADE 标记
            stats.countRisk(p.getUniqueId(), true);
        }
        s.result(result);
        if (s.stage() != null && !s.stage().isClosed()) {
            s.stage().quench(picked, result);
        }
        s.phase(ForgePhase.RESULT);
        open(s, menu.buildResult(s, result));

        int levels = stats.record(p.getUniqueId(), p.getName(), s.quality(), s.finalScore(),
                s.material().id(), s.pattern(), s.talents().expMultiplier());
        if (plugin.codex() != null) plugin.codex().registerForge(p.getUniqueId(), s.quality(), s.formId(), s.finalScore(), s.affixValues().keySet()); // 收藏图鉴登记（拓展第一档）

        // v2.0 连锻链 + 剑币：炸剑（粗糙）断链且不吃倍率；其余品质续链
        int streakNow = 0;
        if (s.quality() == Quality.ROUGH) {
            plugin.streaks().onBreak(p, "炸剑");
        } else {
            streakNow = plugin.streaks().onSuccess(p);
        }
        if (s.quality() != Quality.ROUGH && plugin.seasonPass() != null) plugin.seasonPass().earn(p.getUniqueId(), "forge"); // 赛季通行证：锻造成功登记（炸剑粗糙不算）
        if (s.quality() != Quality.ROUGH && plugin.guilds() != null) plugin.guilds().addWeekPoints(p.getUniqueId(), s.finalScore()); // 行会周赛：当周 forge 积分（炸剑粗糙不算）
        long coinsEarned = plugin.coinRules().forgeReward(s.quality(), s.finalScore(), streakNow,
                plugin.guilds() == null ? 1.0 : plugin.guilds().forgeCoinMultiplier(p.getUniqueId())); // 行会加成：倍率算好传入（guard null）
        stats.addCoins(p.getUniqueId(), coinsEarned, CoinReason.FORGE);
        // v2.0 师徒：徒弟锻成，师父按比例分剑币（师父离线也入账）
        if (plugin.mentors() != null) {
            plugin.mentors().onApprenticeForged(p, s.quality(), coinsEarned);
        }
        plugin.streaks().tipAfterForge(p, streakNow, coinsEarned);

        int repGain = (int) Math.max(1, Math.round(s.finalScore() / 3.5));
        if (plugin.ranks() != null) {
            plugin.ranks().refresh(p.getUniqueId()); // 段位：声望变化后即时刷新
        }
        if (stats.addReputation(p.getUniqueId(), repGain)) {
            sound(p, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.4f);
            String title = StatsStore.titleOf(stats.get(p.getUniqueId()).reputation);
            p.showTitle(Title.title(
                    Msg.mm("<gradient:#ffd166:#ff7a18><bold>称号晋升</bold></gradient>"),
                    Msg.mm("<gray>你现在是「<#ffd166>" + title + "</#ffd166>」"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1600), Duration.ofMillis(500))));
            plugin.getServer().broadcast(Msg.mm(Msg.PREFIX + "<gray>" + p.getName()
                    + " <gray>的声望达到了新的称号：「<#ffd166>" + title + "</#ffd166>」。"));
        }
        if (levels > 0) {
            StatsStore.Stats st = stats.get(p.getUniqueId());
            sound(p, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
            p.showTitle(Title.title(
                    Msg.mm("<gradient:#ffd166:#ff9a3c><bold>专精 Lv." + st.level + "</bold></gradient>"),
                    Msg.mm("<gray>判定窗口更宽容了"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1400), Duration.ofMillis(400))));
            Msg.prefix(p, "<gold>锻造专精升到 <white>Lv." + st.level + "</white>！判定容差 "
                    + Msg.fmt((StatsStore.toleranceMultiplier(st.level) - 1) * 100, 0) + "% ↑");
            plugin.getServer().broadcast(Msg.mm(Msg.PREFIX + "<gray>" + p.getName()
                    + " <gray>的锻造专精升到了 <gold>Lv." + st.level + "</gold>。"));
        }
        sound(p, Sound.ITEM_BUCKET_FILL, 1.0f, 0.7f);
        sound(p, Sound.BLOCK_LAVA_POP, 0.8f, 0.6f);
        if (settings.particles()) {
            p.getWorld().spawnParticle(Particle.CLOUD, feet(p).add(0, 0.6, 0), 24, 0.5, 0.3, 0.5, 0.05);
        }
        announce(s, p);
        // v2.23 拓展 C：锻造成功 → Webhook（炸剑/粗糙不播，那是玩家的挫折不该外发）
        if (s.quality() != Quality.ROUGH) {
            plugin.broadcast(com.forgemaster.webhook.WebhookEvent.FORGE_SUCCESS,
                    "【锻造】" + p.getName() + " 锻出 " + s.quality().display()
                            + " · " + s.material().display() + "（评分 "
                            + String.format(java.util.Locale.ROOT, "%.1f", s.finalScore()) + "）",
                    java.util.Map.of("player", p.getName(),
                            "quality", s.quality().display(),
                            "material", s.material().display(),
                            "score", String.format(java.util.Locale.ROOT, "%.1f", s.finalScore()),
                            "coins", String.valueOf(coinsEarned)));
        }

        if (plugin.achievements() != null) {
            plugin.achievements().check(p.getUniqueId(), "forge");
        }
        if (plugin.duels() != null) {
            plugin.duels().report(s);
        }
        if (plugin.contests() != null) {
            plugin.contests().report(s);
        }
        if (plugin.manuals() != null && s.manualId() != null) {
            plugin.manuals().reportCopy(s, p);
        }
    }

    /**
     * 解除临摹的剑胚锁定。
     *
     * <p>正常打完的路径由 {@code settle()} → {@code reportCopy()} 自己收尾，
     * 这里只负责「中途放弃 / 关界面 / 炉子冷了 / 掉线」这条作废路径。</p>
     *
     * <p>v2.8 之前这几条路都没清，玩家点了剑谱后再放弃锻造，
     * {@code copying} 里那条记录会一直挂着，{@code begin()} 随即拒绝任何
     * 别的剑胚——只能把那一本谱从头搓完才能做别的东西。</p>
     */
    private void releaseCopy(ForgeSession s, Player p) {
        if (plugin.manuals() == null) {
            return;
        }
        String r = plugin.manuals().cancelCopy(s.uuid());
        if (r != null && p != null && p.isOnline()) {
            Msg.prefix(p, "<gray>临摹已取消，剑胚锁定解除。</gray>");
        }
    }

    /**
     * 结果界面的点击。
     *
     * <p>13 = 成品格、15 = 「拿走」按钮、{@link ForgeMenu#STRIKE_SLOT} = 「关闭」按钮，
     * 三者语义都是「结算并离场」，所以统一走 {@link #closeAfterResult}：
     * 发一次成品（{@code giveResult} 内部靠 {@code resultGiven} 幂等）+ 关窗 + 清会话。</p>
     *
     * <p>早先只让 13 / 22 关窗，点 15「拿走」只发物品不关窗，玩家会卡在
     * 「成品还在格子里、界面不关、再开锻造台提示还有活儿没干完」的死角。</p>
     */
    private void clickResult(ForgeSession s, Player p, int slot) {
        if (slot == 13 || slot == 15 || slot == ForgeMenu.STRIKE_SLOT) {
            closeAfterResult(s, p);
        }
    }

    private void closeAfterResult(ForgeSession s, Player p) {
        giveResult(s, p);
        s.finished(true);
        removeSession(s); // 审计 #9：下场统一走 removeSession，tick 迭代中只记账
        if (s.stage() != null) {
            s.stage().finish();
            s.stage(null);
        }
        if (p.getOpenInventory().getTopInventory().getHolder() instanceof ForgeHolder) {
            p.closeInventory();
        }
    }

    // ------------------------------------------------------------ 结算

    private void announce(ForgeSession s, Player p) {
        Quality q = s.quality();
        p.showTitle(Title.title(
                Msg.mm("<color:" + q.color() + "><bold>" + q.display() + "</bold></color>"),
                Msg.mm("<gray>总评分 <white>" + Msg.fmt(s.finalScore(), 1) + "</white> · " + s.quench().colored()),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1600), Duration.ofMillis(500))));

        if (q.ordinal() >= Quality.EPIC.ordinal()) {
            sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, q == Quality.MYTHIC ? 0.7f : 1.2f);
            sound(p, Sound.ENTITY_GENERIC_EXPLODE, 0.35f, 1.8f);
            if (settings.particles()) {
                p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, feet(p).add(0, 1.0, 0), 40, 0.6, 0.5, 0.6, 0.1);
            }
        }
        if (settings.broadcastLegendary() && q.ordinal() >= Quality.LEGENDARY.ordinal()) {
            Bukkit.broadcast(Msg.mm(Msg.PREFIX + "<white>" + p.getName() + "</white> 锻出了 "
                    + q.colored() + " <gray>【" + Msg.plain(s.result().displayName()) + "】"
                    + " <dark_gray>(" + Msg.fmt(s.finalScore(), 1) + " 分)"));
        }
        Msg.prefix(p, "<gray>锻造完成：" + q.colored() + " <white>"
                + Msg.plain(s.result().displayName()) + "</white>，得分 <white>"
                + Msg.fmt(s.finalScore(), 1) + "</white>。");
        if (!s.affixValues().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Affix, Double> e : s.affixValues().entrySet()) {
                if (sb.length() > 0) {
                    sb.append("<gray> · ");
                }
                sb.append("<white>").append(e.getKey().display());
            }
            Msg.prefix(p, "<gold>词条：</gold>" + sb);
        }
    }

    private void giveResult(ForgeSession s, Player p) {
        if (s.resultGiven()) {
            return;
        }
        ItemStack item = s.result();
        if (item == null || item.getType().isAir()) {
            return; // 没有成品（理论上进不了成品阶段）
        }
        // 用 clone：这个 ItemStack 同时被放在成品界面第 13 格展示，交给背包的必须是独立副本，
        // 免得「界面里那份被回收」和「背包里那份」变成同一个对象的两个持有者
        ItemStack copy = item.clone();
        try {
            Map<Integer, ItemStack> leftover = p.getInventory().addItem(copy);
            for (ItemStack rest : leftover.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), rest);
            }
        } catch (RuntimeException ex) {
            // 发放失败也必须让物品落地。绝不能「吞掉 + 标记已发放」——那是永久丢东西。
            plugin.getLogger().warning("成品发放失败，改为直接掉落：" + ex);
            p.getWorld().dropItemNaturally(p.getLocation(), copy);
        }
        // v2.23.1：先发放、后打标记。原来先标记，中途抛异常就等于把剑永久吞了
        s.resultGiven(true);
        sound(p, Sound.ENTITY_ITEM_PICKUP, 0.9f, 1.1f);
    }

    // ------------------------------------------------------------ 清理

    public void cancel(ForgeSession s, String reason) {
        Player p = s.player();
        // 已经打完、只是还没把成品收走的，取消只等于「关掉界面」：
        // 先把成品塞进背包，而且不算作废，不能断连锻链
        boolean resultPhase = s.phase() == ForgePhase.RESULT;
        if (resultPhase && p != null) {
            giveResult(s, p);
        }
        s.finished(true);
        if (p != null && !resultPhase) {
            // v2.0 连锻链：放弃 / 作废 / 炉子冷了 都断链
            plugin.streaks().onBreak(p, "锻造作废");
        }
        // 临摹：中途作废必须解除剑胚锁定。
        // resultPhase 时 settle() 已经通过 reportCopy 清过了，这里只补作废路径。
        if (!resultPhase) {
            releaseCopy(s, p);
        }
        removeSession(s); // 审计 #9：下场统一走 removeSession，tick 迭代中只记账
        if (s.stage() != null) {
            s.stage().despawn();
            s.stage(null);
        }
        if (s.bossBar() != null) {
            s.bossBar().removeAll();
            s.bossBar(null);
        }
        if (p != null) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof ForgeHolder h
                    && h.session() == s) {
                p.closeInventory();
            }
            if (reason != null) {
                Msg.prefix(p, reason);
                sound(p, Sound.ENTITY_ITEM_BREAK, 0.7f, 0.8f);
            }
        }
    }

    /**
     * 极限锻造的阶段爆剑判定（D3）。非 RISK 会话直接返回 false。
     * 爆了：材料计 riskLost、断连锻链、会话结束，返回 true 让调用方停止本阶段。
     */
    private boolean maybeExplode(ForgeSession s, Player p, String stageName) {
        if (!s.risk() || random.nextDouble() >= RiskMode.explodeChance(plugin)) {
            return false;
        }
        s.finished(true);
        removeSession(s); // 审计 #9：下场统一走 removeSession，tick 迭代中只记账
        stats.countRisk(s.uuid(), false);
        if (p != null) {
            plugin.streaks().onBreak(p, "极限锻造炸剑");
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof ForgeHolder h
                    && h.session() == s) {
                p.closeInventory();
            }
            Msg.prefix(p, "<red>极限锻造在「" + stageName + "」阶段炸剑了！</red> <gray>材料全部损失，连锻断了。</gray>");
            sound(p, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.9f);
            p.getWorld().spawnParticle(Particle.EXPLOSION, feet(p).add(0, 0.5, 0), 3, 0.3, 0.2, 0.3, 0.0);
        }
        if (s.stage() != null) {
            s.stage().despawn();
            s.stage(null);
        }
        if (s.bossBar() != null) {
            s.bossBar().removeAll();
            s.bossBar(null);
        }
        return true;
    }

    /**
     * 断线 / 被踢 / 换服：会话作废时把「已经结算完、还没发出去」的成品补发掉。
     *
     * <p><b>v2.23.1 修 P1-B：</b>{@code settle} 已经把成品放进 {@code session.result}、
     * 扣完全部材料与锻造费、抽完词条，甚至清掉了玩家的 {@code inherit_level}（剑魂传承）。
     * 此后玩家在这几秒里掉线 / 被反作弊踢 / 被 {@code /kick}，{@code tickSession}
     * 走 {@code discard}，而 {@code discard} <b>不调用</b> {@code giveResult}——
     * {@code cancel} / {@code onInventoryClosed} / {@code shutdown} 三条路都会发，唯独这条不发，
     * 于是成品连同已清零的剑魂传承永久消失，聊天里一句提示都没有。</p>
     *
     * <p>现在只要处于 {@code RESULT} 阶段且成品未发放，就一定要落地，且<b>不丢</b>：</p>
     * <ol>
     *   <li>玩家其实还在线（极端时序，例如刚被前台重新登录）→ 走正常的 {@code giveResult}；</li>
     *   <li>玩家离线 → 把成品丢在「砧位 / 演出台原点」对应的<b>已加载</b>世界；
     *       拿不到原点就用 tick 里留的最后站位；再不行退到主世界出生点。
     *       同时控制台 {@code warning} 一行「玩家离线，成品已掉落」，留可追溯记录。</li>
     * </ol>
     * <p>两条路都会置 {@code resultGiven(true)}，杜绝 shutdown / tick 再次补发导致复制。</p>
     */
    private void discard(ForgeSession s) {
        // v2.23.1 修 P1-B：先补发（必须在 removeSession / 清 stage 之前，stage 的原点要用来定位）
        deliverResultOnDiscard(s);
        s.finished(true);
        // 掉线 / 不在线：会话直接丢弃，临摹锁定也得跟着解除，否则下次上线还是被锁着
        releaseCopy(s, s.player());
        removeSession(s); // 审计 #9：下场统一走 removeSession，tick 迭代中只记账
        if (s.stage() != null) {
            s.stage().despawn();
            s.stage(null);
        }
        if (s.bossBar() != null) {
            s.bossBar().removeAll();
            s.bossBar(null);
        }
    }

    /**
     * 离线状态下的成品发放（v2.23.1 修 P1-B）。
     *
     * <p>离线玩家不能 {@code addItem}，所以这里是「宁可掉在地上也不销毁」的口径：
     * 优先丢在砧位 / 演出台原点所在的已加载世界，其次玩家最后站位，最后主世界出生点。
     * 全程在控制台留 warning + info 各一行，服主可据此复盘或人工补偿。</p>
     */
    private void deliverResultOnDiscard(ForgeSession s) {
        if (s.phase() != ForgePhase.RESULT || s.result() == null || s.resultGiven()) {
            return;
        }
        ItemStack item = s.result();
        Player online = s.player();
        if (online != null && online.isOnline()) {
            // 人还在（时序极端）：正常入包，塞不下会掉在脚下，giveResult 内部处理
            giveResult(s, online);
            return;
        }

        Location at = offlineDropLocation(s);
        org.bukkit.World world = loadedWorldOf(at);
        if (world == null || at == null) {
            // 世界全卸载 / 拿不到任何落点：不假装发放，打 SEVERE 留痕，等服主人工补偿
            plugin.getLogger().severe("[锻造] 玩家 " + s.playerName() + "(" + s.uuid()
                    + ") 离线且没有可用世界，成品未能落地："
                    + Msg.plain(item.displayName()) + " × " + item.getAmount() + "，需要人工补偿。");
            return;
        }
        s.resultGiven(true);
        Location dropAt = at.clone();
        dropAt.setWorld(world);
        world.dropItemNaturally(dropAt, item);
        plugin.getLogger().warning("[锻造] 玩家 " + s.playerName() + " 在成品界面断线，成品已掉落："
                + Msg.plain(item.displayName()) + " @ " + world.getName()
                + " " + dropAt.getBlockX() + "," + dropAt.getBlockY() + "," + dropAt.getBlockZ());
        plugin.getLogger().info("[锻造] 可追溯记录：uuid=" + s.uuid() + " 品质=" + s.quality().display()
                + " 得分=" + Msg.fmt(s.finalScore(), 1) + " 阶段=RESULT(离线补发)");
    }

    /**
     * 离线成品的落点：砧位 / 演出台原点 → 玩家最后站位 → 主世界出生点。
     *
     * @return 可能返回「世界已卸载」的位置，调用方必须再用 {@link #loadedWorldOf} 校验
     */
    private Location offlineDropLocation(ForgeSession s) {
        if (s.stage() != null) {
            Location origin = s.stage().origin();
            if (origin != null && loadedWorldOf(origin) != null) {
                return origin.clone().add(0, 1.0, 0);
            }
        }
        Location last = lastLocations.get(s.uuid());
        if (last != null && loadedWorldOf(last) != null) {
            return last.clone();
        }
        // 兜底：主世界出生点（运行期至少有一个世界加载着）
        if (!Bukkit.getWorlds().isEmpty()) {
            return Bukkit.getWorlds().get(0).getSpawnLocation();
        }
        return null;
    }

    /**
     * 位置对应的「仍然加载着」的世界；世界被卸载时返回 null。
     *
     * <p>直接拿 {@code location.getWorld()} 会拿到一个已卸载的 World 句柄，
     * 往上面 {@code dropItemNaturally} 等于把物品丢进虚空——必须回查 Bukkit 的世界表。</p>
     */
    private org.bukkit.World loadedWorldOf(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return null;
        }
        return Bukkit.getWorld(loc.getWorld().getName());
    }

    /** 玩家关掉界面（非切换、非结算）时调用 */
    /**
     * 玩家关掉了锻造界面。
     *
     * <h2>v2.23.1 修 P0：这里必须能分辨「切换界面」和「真的关掉」</h2>
     * 每次换界面（加热→锤打→…→成品）都会产生一次旧界面的关闭事件。
     * 靠 {@code transitioning} 标志去挡是不可靠的 —— 那个事件可能下一 tick 才送达，
     * 而标志早同步清掉了。于是进入成品界面的那一次「切换」会被当成「玩家关了成品界面」，
     * 会话被提前销毁：成品界面还开着、点「拿走」没反应、ESC 后剑看起来消失。
     *
     * <p>所以这里加一道<b>与时序无关</b>的语义判断：若玩家此刻打开的上层界面
     * 已经是同一个会话的另一张锻造界面，那这次「关闭」只是切换，直接忽略。</p>
     *
     * @param closed 刚刚被关闭的那张界面（用来区分「还在看同一张」和「已经切走了」）
     */
    public void onInventoryClosed(ForgeSession s, Inventory closed) {
        if (s.finished()) {
            return;
        }
        Player p = s.player();
        if (p != null && p.isOnline() && isViewingForgeScreen(p, s, closed)) {
            return; // 已经切到新界面了，这次只是切换
        }
        if (s.transitioning()) {
            return;
        }
        if (s.phase() == ForgePhase.RESULT) {
            if (p != null) {
                giveResult(s, p);
            }
            s.finished(true);
            removeSession(s); // 审计 #9：下场统一走 removeSession，tick 迭代中只记账
            if (s.stage() != null) {
                s.stage().finish();
                s.stage(null);
            }
            if (s.bossBar() != null) {
                s.bossBar().removeAll();
                s.bossBar(null);
            }
            return;
        }
        cancel(s, "<red>界面关掉了，这次锻造作废，材料没有退还。</red>");
    }

    /**
     * 玩家此刻是不是正看着「同一个会话的另一张」锻造界面。
     *
     * <p>{@code closed} 就是刚关掉的那张：如果玩家打开的上层界面<b>还是它</b>，
     * 说明这一下是被真正关掉的（ESC / 掉线清理），不是切换。</p>
     */
    private boolean isViewingForgeScreen(Player p, ForgeSession s, Inventory closed) {
        Inventory top;
        try {
            top = p.getOpenInventory().getTopInventory();
        } catch (RuntimeException ex) {
            return false;
        }
        if (top == null) {
            return false;
        }
        boolean sameSessionScreen = top.getHolder() instanceof ForgeHolder h && h.session() == s;
        return isTransitionClose(sameSessionScreen, top.equals(closed));
    }

    /**
     * 判断一次 {@code InventoryCloseEvent} 到底是「切换界面」还是「真的关掉」。
     *
     * <p>纯函数，不碰 Bukkit —— 自检可以离线把真值表钉死。这是这次 P0 的地基：
     * 靠 {@code transitioning} 标志挡是挡不住的（事件可能下一 tick 才送达，
     * 标志早被同步清掉了），必须看语义。</p>
     *
     * @param viewingSameSessionScreen 玩家此刻打开的上层界面是不是同一会话的锻造界面
     * @param sameInventory            那张界面是不是「刚被关掉的这一张」
     * @return true = 只是切换，别当关闭处理
     */
    public static boolean isTransitionClose(boolean viewingSameSessionScreen, boolean sameInventory) {
        return viewingSameSessionScreen && !sameInventory;
    }

    /**
     * 兜底取回：会话已经结算（被提前收掉），但成品界面还停留在玩家面前。
     *
     * <p>正路已经堵死了提前结算，这里只是最后一道保险 —— 宁可多给一次
     * （{@code giveResult} 内部 {@code resultGiven} 幂等），也不能让玩家眼巴巴点着
     * 一个「点不动」的按钮，关掉之后剑就没了。</p>
     */
    public void reclaim(ForgeSession s, Player p) {
        if (s == null || p == null) {
            return;
        }
        if (s.phase() == ForgePhase.RESULT) {
            giveResult(s, p);
        }
        if (p.getOpenInventory().getTopInventory().getHolder() instanceof ForgeHolder) {
            p.closeInventory();
        }
    }

    public void shutdown() {
        stop();
        for (ForgeSession s : new ArrayList<>(sessions.values())) {
            Player p = s.player();
            if (s.stage() != null) {
                s.stage().despawn();
                s.stage(null);
            }
            if (p != null) {
                if (s.phase() == ForgePhase.RESULT && s.result() != null && !s.resultGiven()) {
                    giveResult(s, p);
                }
                if (s.bossBar() != null) {
                    s.bossBar().removeAll();
                }
            }
        }
        sessions.clear();
    }

    // ------------------------------------------------------------ 工具

    private Location feet(Player p) {
        return p.getLocation().add(0, 0.9, 0);
    }

    private void sound(Player p, Sound sound, float volume, float pitch) {
        if (p != null && settings.sounds()) {
            p.playSound(p.getLocation(), sound, volume, pitch);
        }
    }

    /** 玩家当前打开的上层界面（玩家不在线 / 界面不是锻造界面时返回 null） */
    private Inventory topOf(Player p) {
        if (p == null) {
            return null;
        }
        Inventory inv = p.getOpenInventory().getTopInventory();
        return inv.getHolder() instanceof ForgeHolder ? inv : null;
    }

    // ------------------------------------------------------------ 无玩家驱动（自检用）

    /** 把会话推进到叠加阶段（玩家可为 null） */
    public void simulateStartFolding(ForgeSession s) {
        startFolding(s, s.player());
    }

    /** 按序列正确点完整个折叠（玩家可为 null） */
    public void simulateFoldAll(ForgeSession s) {
        while (s.phase() == ForgePhase.FOLDING && s.foldCursor() < s.foldSequence().size()) {
            clickFolding(s, s.player(), ForgeMenu.TRACK_START + s.foldSequence().get(s.foldCursor()));
        }
    }

    /** 空转开刃 + 两道收尾工序直到进淬火（玩家可为 null） */
    public void simulateGrind(ForgeSession s) {
        int guard = 0;
        while (guard++ < 4000) {
            switch (s.phase()) {
                case GRINDING -> tickGrinding(s, s.player());
                case POLISHING -> simulatePolishAll(s);
                case INLAY -> simulateInlayAll(s);
                // 走到淬火 / 成品 / 被取消就停
                default -> {
                    return;
                }
            }
        }
    }

    /** 把会话推进到抛光阶段（玩家可为 null，自检用） */
    public void simulateStartPolishing(ForgeSession s) {
        startPolishing(s, s.player());
    }

    /** 把会话推进到嵌银丝阶段（玩家可为 null，自检用） */
    public void simulateStartInlay(ForgeSession s) {
        startInlay(s, s.player());
    }

    /**
     * 模拟一次界面点击（玩家可为 null，自检用）。
     *
     * <p>{@link #handleClick} 的第一道闸是「玩家必须在线」——那是给真实点击事件用的护栏：
     * 玩家掉线后残留的会话不该再被点击推进。自检里的会话是假 UUID、永远拿不到 Player，
     * 走 {@code handleClick} 会被这道闸静默吞掉（点了等于没点）。所以离线夹具单独一条派发口：
     * 只用对 null 玩家安全的两道收尾工序（抛光的 {@code clickPolishing} 与嵌丝的
     * {@code clickInlay} 内部所有 {@code p} 用法都做了判空），其余工序仍回落到
     * {@code handleClick}，保持「离线不许推进」的语义。</p>
     */
    public void simulateClick(ForgeSession s, int slot) {
        switch (s.phase()) {
            case POLISHING -> clickPolishing(s, s.player(), slot);
            case INLAY -> clickInlay(s, s.player(), slot);
            default -> handleClick(s, slot, false);
        }
    }

    /** 手动走一 tick 当前工序（玩家可为 null，自检用） */
    public void simulateTick(ForgeSession s) {
        switch (s.phase()) {
            case GRINDING -> tickGrinding(s, s.player());
            case POLISHING -> tickPolishing(s, s.player());
            case INLAY -> tickInlay(s, s.player());
            default -> {
            }
        }
    }

    /** 每一刀都压在亮区正中（玩家可为 null），用来把抛光一路推完 */
    public void simulatePolishAll(ForgeSession s) {
        int guard = 0;
        while (s.phase() == ForgePhase.POLISHING && guard++ < 64) {
            // 指针停到正中再下刀：不需要真的 tick 扫过去
            s.polishValue(s.polishCenter());
            clickPolishing(s, s.player(), ForgeMenu.STRIKE_SLOT);
            if (s.phase() == ForgePhase.POLISHING) {
                s.polishDeadline(System.currentTimeMillis() + polishDeadlineMillis(s));
            }
        }
    }

    /** 每根银丝都在亮窗里点中（玩家可为 null） */
    public void simulateInlayAll(ForgeSession s) {
        int guard = 0;
        while (s.phase() == ForgePhase.INLAY && guard++ < 64) {
            int target = s.inlayTarget();
            if (target < 0) {
                finishInlay(s, s.player(), false);
                return;
            }
            clickInlay(s, s.player(), ForgeMenu.TRACK_START + target);
            if (s.phase() == ForgePhase.INLAY) {
                s.inlayDeadline(System.currentTimeMillis() + 10000L);
            }
        }
    }
}