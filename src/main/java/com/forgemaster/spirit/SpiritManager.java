package com.forgemaster.spirit;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.affix.Affix;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 剑灵：成品养灵。
 *
 * <p>灵性来源：击中敌人（spirit.gain-per-hit）、花剑币喂养（/zhujian spirit feed）。
 * 跨档时：档位越高击中特效越华丽；第 2 档起每档给一条"灵契"词条（从 AffixRegistry 抽取）。
 * 剑名由玩家用 /zhujian spirit name 设定，存在 PDC。</p>
 *
 * <p>命中养灵带时间节流：普通命中只把灵性记进内存（每玩家一条 pending），
 * 满 2 秒 / 灵性升档 / 退服 / 服关时才一次性写 PDC + Lore，省掉每次命中整条
 * Lore 的序列化写回。升档不受节流影响：过线那一击立即落账并触发特效。</p>
 */
public final class SpiritManager implements Listener {

    private static final List<Integer> DEFAULT_TIERS = List.of(0, 50, 150, 400, 900);
    private static final List<String> DEFAULT_NAMES = List.of("初醒", "通灵", "凝魄", "化灵", "神器");
    private static final Particle[] TIER_PARTICLES = {
            Particle.ENCHANT, Particle.END_ROD, Particle.SOUL_FIRE_FLAME, Particle.DRAGON_BREATH
    };

    /** 养灵落账节流窗口：窗口内的命中只记内存，到点才落 PDC + Lore */
    private static final long FLUSH_MILLIS = 2000;

    /** 一个玩家挂着的待落账灵性（base = 首次累计时那把剑 PDC 里的灵性） */
    private static final class PendingSpirit {
        final int base;
        final long since;
        int amount;

        PendingSpirit(int base, long since) {
            this.base = base;
            this.since = since;
        }
    }

    private final ForgingMasterPlugin plugin;
    private final NamespacedKey granted;
    private final Random random = new Random();
    /** 每玩家一条待落账灵性；命中热路径只动这里，冲刷时才碰物品 */
    private final Map<UUID, PendingSpirit> pending = new HashMap<>();

    /** v2.25.1：待确认的灵契重抽（uuid → 预览时写进剑 PDC 的 nonce），confirm 校验同一把剑 */
    private final Map<UUID, Integer> pendingReroll = new HashMap<>();
    /** 灵击冷却：玩家 uuid → 解禁时刻毫秒。仅内存，重启即丢（冷却不值得落盘） */
    private final Map<UUID, Long> surgeCooldowns = new HashMap<>();

    public SpiritManager(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.granted = new NamespacedKey(plugin, "spirit_granted");
        // 管理器没有独立的 start()，退服 / 服关冲刷的监听就在构造时挂上
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // ------------------------------------------------------------ 配置读取

    public boolean enabled() {
        return plugin.settings().flag("spirit.enabled", true);
    }

    /** 灵性上限（v2.24 温养要给剑冢快照补灵性，改成公开读口） */
    public int maxValue() {
        return Math.max(1, plugin.settings().intOf("spirit.max", 1000));
    }

    public List<Integer> tiers() {
        List<Integer> list = plugin.getConfig().getIntegerList("spirit.tiers");
        return list.isEmpty() ? DEFAULT_TIERS : list;
    }

    private List<String> tierNames() {
        List<String> list = plugin.getConfig().getStringList("spirit.tier-names");
        return list.isEmpty() ? DEFAULT_NAMES : list;
    }

    public int tierOf(int value) {
        List<Integer> th = tiers();
        int tier = 0;
        for (int i = 0; i < th.size(); i++) {
            if (value >= th.get(i)) {
                tier = i;
            }
        }
        return tier;
    }

    public String tierName(int tier) {
        List<String> names = tierNames();
        return names.get(Math.max(0, Math.min(tier, names.size() - 1)));
    }

    /** 该档位应有的灵契词条数：第 2 档起每档 1 条，受 max-extra-affixes 限制 */
    public int slotsOf(int tier) {
        return slotsForTier(tier, Math.max(0, plugin.settings().intOf("spirit.max-extra-affixes", 2)));
    }

    /**
     * 档位 → 灵契条数的纯函数（重抽守恒的基准：重抽前后都按它补满）。
     *
     * <p>抽出静态版是为了让自检能不依赖配置直接断言「重抽前后条数守恒」：
     * 摘掉几条就补回几条，条数永远等于 {@code min(cap, max(0, tier-1))}。</p>
     */
    public static int slotsForTier(int tier, int cap) {
        return Math.min(Math.max(0, cap), Math.max(0, tier - 1));
    }

    // ------------------------------------------------------------ 灵击（v2.24 切片二）

    /**
     * 剑灵灵击：灵性 ≥ min-tier 档的锻造剑击杀时几率「灵体出鞘」，对尸体位置
     * radius 内的怪物结算一次 damage 点无归因伤害（玩家不受伤）。
     *
     * <p>参数上限在代码里写死一份（照项目「config 驱动 + 代码夹取」惯例）：
     * chance ≤ 0.15、damage ≤ 4.0、radius ≤ 3.0、cooldown-ms ≥ 3000、min-tier ≥ 1。</p>
     */

    /** 触发概率夹取：[0, 0.15]，防「配置写 1.0 = 每杀必炸」 */
    public static double clampSurgeChance(double v) {
        return Math.max(0, Math.min(0.15, v));
    }

    /** 单怪伤害夹取：[0, 4.0]，灵击是锦上添花的特效不是清场技能 */
    public static double clampSurgeDamage(double v) {
        return Math.max(0, Math.min(4.0, v));
    }

    /** 波及半径夹取：[0, 3.0]，照烬狱 pyreRadius 的尺度 */
    public static double clampSurgeRadius(double v) {
        return Math.max(0, Math.min(3.0, v));
    }

    /** 冷却下限 3000ms：灵击带伤害结算，零冷却等于白送一个群体技能 */
    public static long clampSurgeCooldownMs(long v) {
        return Math.max(3000, v);
    }

    /** 档位下限 ≥1：初醒档的剑不该带灵击，这是高阶剑灵的排面 */
    public static int clampSurgeMinTier(int v) {
        return Math.max(1, v);
    }

    /** 灵击总开关（跟着 spirit.enabled 一起关） */
    public boolean surgeEnabled() {
        return enabled() && plugin.settings().flag("spirit.surge.enabled", true);
    }

    /** 触发概率（已夹取） */
    public double surgeChance() {
        return clampSurgeChance(plugin.settings().num("spirit.surge.chance", 0.08));
    }

    /** 单怪伤害（已夹取） */
    public double surgeDamage() {
        return clampSurgeDamage(plugin.settings().num("spirit.surge.damage", 2.0));
    }

    /** 波及半径（已夹取） */
    public double surgeRadius() {
        return clampSurgeRadius(plugin.settings().num("spirit.surge.radius", 2.5));
    }

    /** 每玩家冷却毫秒（已夹取） */
    public long surgeCooldownMs() {
        return clampSurgeCooldownMs(plugin.settings().longOf("spirit.surge.cooldown-ms", 6000));
    }

    /** 灵性档位下限（已夹取，spirit.tiers 的档序号，0 起） */
    public int surgeMinTier() {
        return clampSurgeMinTier(plugin.settings().intOf("spirit.surge.min-tier", 3));
    }

    /** 距离该玩家下一次灵击还剩多少毫秒，0 = 现在就能触发（自检可喂固定时钟） */
    public long surgeCooldownRemaining(UUID playerId, long nowMillis) {
        Long until = surgeCooldowns.get(playerId);
        return until == null ? 0 : Math.max(0, until - nowMillis);
    }

    // ------------------------------------------------------------ 灵契重抽（v2.25 切片二）

    /**
     * 灵契重抽：灵性满档的锻造剑花剑币把灵契词条整批重掷一次（保留灵性与剑名），
     * 每天限 {@code spirit.reroll.daily-limit} 次，次数记在剑的 PDC 上（跟剑走，换号不重置）。
     *
     * <p>守恒口径：旧的灵契从 Lore / 属性 modifier / 聚合 PDC / {@code affix_list}
     * 四处干净摘除后，按 {@link #slotsForTier} 补满同样条数——条数只由档位与
     * {@code max-extra-affixes} 决定，重抽不改变灵契总量。</p>
     */

    /** Lore 里灵契行的识别前缀（grantAffix 写行时的同一份字面量） */
    private static final String SPIRIT_LORE_TAG = "◆ 灵契";
    /** 灵契属性词条的 modifier 键前缀（grantAffix 写键的同一份规则） */
    private static final String SPIRIT_MOD_PREFIX = "fm_spirit_";

    /** 重抽费用夹取：[0, +∞)，配置写负数按免费处理而不是拒绝执行 */
    public static long clampRerollCost(long v) {
        return Math.max(0, v);
    }

    /** 每日次数夹取：[1, 99]。配置写 0 会把功能永久锁死，按 1 次兜底 */
    public static int clampRerollDailyLimit(int v) {
        return Math.max(1, Math.min(99, v));
    }

    /** 重抽费用（已夹取） */
    public long rerollCost() {
        return clampRerollCost(plugin.settings().longOf("spirit.reroll.cost", 2000));
    }

    /** 每日重抽次数上限（已夹取） */
    public int rerollDailyLimit() {
        return clampRerollDailyLimit(plugin.settings().intOf("spirit.reroll.daily-limit", 1));
    }

    /**
     * 纯函数：这把剑今天还能不能重抽。
     *
     * <p>PDC 上的戳格式是 {@code yyyy-MM-dd|当日已用次数}；日期不是今天 = 次数已随
     * 新的一天作废。计数解析不动（手改 PDC / 旧数据）按「已用完」处理——
     * 宁可让玩家等一天，也不让脏数据白送一次重抽。</p>
     */
    public static boolean canRerollToday(String stored, String today, int limit) {
        if (limit <= 0) {
            return false;
        }
        if (stored == null || stored.isBlank()) {
            return true;
        }
        int bar = stored.indexOf('|');
        String date = bar < 0 ? stored : stored.substring(0, bar);
        if (!date.equals(today)) {
            return true;
        }
        int count = limit; // 解析不动按已用完：与 nextRerollStamp 的写入格式互为镜像
        if (bar >= 0) {
            try {
                count = Integer.parseInt(stored.substring(bar + 1).trim());
            } catch (NumberFormatException ex) {
                // 保持 limit（已用完）
            }
        }
        return count < limit;
    }

    /**
     * 纯函数：重抽之后的下一个日期戳。
     *
     * <p>同一天累加计数（封顶 limit，超出不再涨，防止配置调小后旧计数爆表）；
     * 跨天从 1 重新起算。</p>
     */
    public static String nextRerollStamp(String stored, String today, int limit) {
        int count = 0;
        if (stored != null && stored.startsWith(today + "|")) {
            try {
                count = Integer.parseInt(stored.substring(today.length() + 1).trim());
            } catch (NumberFormatException ex) {
                count = 0;
            }
        }
        int cap = Math.max(1, limit);
        return today + "|" + Math.min(Math.max(0, count) + 1, cap);
    }

    /**
     * 纯函数：从 {@code affix_list}（锻造词条与灵契混存一张表）里只摘掉灵契那几个 id。
     *
     * <p>锻造词条的顺序与内容原样保留——重抽只动灵契，不动淬火词条。</p>
     */
    public static String removeSpiritIds(String csv, java.util.Collection<String> remove) {
        if (csv == null || csv.isBlank() || remove == null || remove.isEmpty()) {
            return csv == null ? "" : csv;
        }
        Set<String> rm = new HashSet<>();
        for (String s : remove) {
            rm.add(s.trim().toLowerCase(Locale.ROOT));
        }
        List<String> kept = new ArrayList<>();
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (t.isEmpty() || rm.contains(t.toLowerCase(Locale.ROOT))) {
                continue;
            }
            kept.add(t);
        }
        return String.join(",", kept);
    }

    /** 重抽主流程；confirm = 是否已过二次确认（第一次调用只给预览） */
    private void reroll(Player player, boolean confirm) {
        ItemStack hand = heldSword(player);
        if (hand == null) {
            return;
        }
        // 与 feed 同款：先把挂账灵性冲进这把剑，避免随后改 PDC 让挂账找不到原剑
        flushPending(player, hand);
        int value = spiritOf(hand);
        int max = maxValue();
        if (value < max) {
            Msg.prefix(player, "<red>灵契重抽只对灵性满档（<white>" + max + "</white>）的剑开放，"
                    + "这把还差 <white>" + (max - value) + "</white> 点灵性。</red>");
            return;
        }
        int slots = slotsOf(tierOf(value));
        if (slots <= 0) {
            Msg.prefix(player, "<red>这一档还没有灵契词条（第 2 档起才有），没东西可重抽。</red>");
            return;
        }
        ItemMeta meta = hand.getItemMeta();
        if (meta == null) {
            return;
        }
        int limit = rerollDailyLimit();
        String today = java.time.LocalDate.now().toString();
        String stored = meta.getPersistentDataContainer()
                .get(Keys.SPIRIT_REROLL_DATE, PersistentDataType.STRING);
        if (!canRerollToday(stored, today, limit)) {
            Msg.prefix(player, "<red>这把剑今天已经重抽过灵契了（每日限 <white>" + limit
                    + "</white> 次），明天再来。</red>");
            return;
        }
        long cost = rerollCost();

        // v2.25.1 修 P1（审计轮3-3①）：confirm 与预览绑定同一把剑 —— 预览时在剑 PDC
        // 写一次性 nonce 并记在内存，confirm 校验主手 PDC 的 nonce 一致才执行，
        // 换了剑（另一把满灵性的）nonce 对不上，不会拿 A 的预览去改 B。
        int nonce = java.util.concurrent.ThreadLocalRandom.current().nextInt();
        if (!confirm) {
            // 预览：只描述，不扣钱、不动剑（照 dissolve / talent reset 的两段式确认）
            int pool = rerollPoolSize(meta);
            int used = usedToday(stored, today);
            meta.getPersistentDataContainer().set(Keys.SPIRIT_REROLL_NONCE,
                    PersistentDataType.INTEGER, nonce);
            com.forgemaster.util.Attrs.apply(hand, meta);
            player.getInventory().setItemInMainHand(hand);
            pendingReroll.put(player.getUniqueId(), nonce);
            Msg.send(player, "<dark_gray>──── <gradient:#b06bff:#4ea8ff><bold>灵契重抽</bold></gradient> <dark_gray>────");
            Msg.send(player, "<gray>将保留灵性（<#b06bff>" + tierName(tierOf(value)) + "</#b06bff>）与剑名，"
                    + "重掷 <white>" + slots + "</white> 条灵契词条。");
            Msg.send(player, "<gray>候选池: <white>" + pool + "</white> 条"
                    + "<dark_gray>（自动排除锻造词条与本剑已有词条，同名不重复）</dark_gray>");
            Msg.send(player, "<gray>费用: <yellow>" + cost + "</yellow> 剑币 <gray>· 今日剩余次数: <white>"
                    + Math.max(0, limit - used) + "</white><dark_gray>/" + limit + "</dark_gray>");
            Msg.prefix(player, "<gray>确认请再执行：<white>/zhujian spirit reroll confirm</white>");
            return;
        }

        // ---- confirm 与预览绑定校验（换剑 / 他人代点都会对不上 nonce）----
        Integer expected = pendingReroll.remove(player.getUniqueId());
        Integer stored2 = null;
        try {
            Integer n = meta.getPersistentDataContainer()
                    .get(Keys.SPIRIT_REROLL_NONCE, PersistentDataType.INTEGER);
            stored2 = n;
        } catch (Exception ex) {
            stored2 = null;
        }
        if (expected == null || !expected.equals(stored2)) {
            Msg.prefix(player, "<red>先看一遍预览（<white>/zhujian spirit reroll</white>）再确认，"
                    + "且确认时手里必须是预览那把剑。</red>");
            return;
        }

        // ---- 池子预检（v2.25.1 修 P1 审计轮3-3②）：摘掉旧灵契后候选池如果
        // 补不满 slots 条，直接拒绝 —— 原来是先扣钱、先摘光、抽不满也不退款，
        // 满锻造词条的剑会净丢全部灵契且当日次数被烧掉。
        // 灵契 id 从 Lore 行识别（与 stripSpiritAffixes 同一匹配口径），affix_list 里两者混存
        java.util.Set<String> spiritIds = new HashSet<>();
        var reg = plugin.affixes();
        if (meta.lore() != null && reg != null) {
            for (Component c : meta.lore()) {
                String plain = Msg.plain(c).trim();
                if (!plain.startsWith(SPIRIT_LORE_TAG)) {
                    continue;
                }
                String rest = plain.substring(SPIRIT_LORE_TAG.length()).trim();
                for (com.forgemaster.affix.Affix a : reg.all()) {
                    if (rest.startsWith("▪ " + a.display() + " ")) {
                        spiritIds.add(a.id().toLowerCase(java.util.Locale.ROOT));
                        break;
                    }
                }
            }
        }
        String affixList = meta.getPersistentDataContainer()
                .get(com.forgemaster.util.Keys.AFFIX_LIST, PersistentDataType.STRING);
        java.util.Set<String> kept = new HashSet<>();
        if (affixList != null) {
            for (String id : affixList.split("\\|")) {
                String t = id.trim();
                if (!t.isEmpty() && !spiritIds.contains(t.toLowerCase(java.util.Locale.ROOT))) {
                    kept.add(t.toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        int poolAfterStrip = 0;
        for (com.forgemaster.affix.Affix a : plugin.affixes().all()) {
            if (!kept.contains(a.id().toLowerCase(java.util.Locale.ROOT))) {
                poolAfterStrip++;
            }
        }
        if (poolAfterStrip < slots) {
            pendingReroll.put(player.getUniqueId(), expected); // 次数不烧，允许再确认
            Msg.prefix(player, "<red>候选池不够：摘掉旧灵契后只剩 <white>" + poolAfterStrip
                    + "</white> 条可选，补不满 <white>" + slots + "</white> 条灵契。"
                    + "锻造词条太多的剑先去重铸或剥离。</red>");
            return;
        }

        // ---- 扣费（Wallet 收口，与 feed 同一套账本）----
        if (!plugin.wallet().take(player, cost, com.forgemaster.econ.CoinReason.SPIRIT)) {
            Msg.prefix(player, "<red>重抽需要 <white>" + cost + "</white> 剑币，你不够。</red>");
            pendingReroll.put(player.getUniqueId(), expected);
            return;
        }

        // 1) 旧灵契干净摘除（Lore 行 + modifier + 聚合 PDC + affix_list）
        List<String> removed = stripSpiritAffixes(meta);
        // 2) 沿既有灵契抽取路径（grantAffix）补满同条数：条数守恒由 slotsForTier 保证
        int granted = 0;
        for (int i = 0; i < slots; i++) {
            if (!grantAffix(meta)) {
                break; // 词条池被本剑占满：宁可少给也不重复同名，文案里如实说
            }
            granted++;
        }
        if (granted < slots) {
            // 理论到不了（上面预检过）；真到了就退款 + 不写日期戳，宁可不赚这笔
            plugin.wallet().give(player, cost, com.forgemaster.econ.CoinReason.REFUND);
            com.forgemaster.util.Attrs.apply(hand, meta);
            player.getInventory().setItemInMainHand(hand);
            Msg.prefix(player, "<red>词条授予异常（授予 " + granted + "/" + slots
                    + "），费用已全额退回，今日次数未消耗。</red>");
            return;
        }
        // 3) 记日期戳（跟剑走）
        meta.getPersistentDataContainer().set(Keys.SPIRIT_REROLL_DATE,
                PersistentDataType.STRING, nextRerollStamp(stored, today, limit));
        refreshLines(meta, value, max);
        com.forgemaster.util.Attrs.apply(hand, meta);
        player.getInventory().setItemInMainHand(hand);
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.9f, 1.3f);
        Msg.prefix(player, "<#b06bff>灵契重抽完成</#b06bff> <gray>· 摘除 <white>" + removed.size()
                + "</white> 条，重新刻入 <white>" + granted + "</white> 条"
                + (granted < slots ? "<red>（候选池被占满，没抽满）</red>" : "")
                + " <dark_gray>· 花费 " + cost + " 剑币 · 今日剩余 "
                + Math.max(0, limit - usedToday(nextRerollStamp(stored, today, limit), today)) + "/" + limit
                + "</dark_gray></gray>");
    }

    /** 今天已经用掉的重抽次数（只给展示用；判断一律走 canRerollToday） */
    private static int usedToday(String stored, String today) {
        if (stored == null || !stored.startsWith(today + "|")) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(stored.substring(today.length() + 1).trim()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** 候选池大小：本剑 affix_list 之外的词条数（与 grantAffix 的排除规则同一口径） */
    private int rerollPoolSize(ItemMeta meta) {
        if (plugin.affixes() == null) {
            return 0;
        }
        Set<String> have = new HashSet<>();
        String csv = meta.getPersistentDataContainer()
                .getOrDefault(Keys.AFFIX_LIST, PersistentDataType.STRING, "");
        for (String part : csv.split(",")) {
            if (!part.isBlank()) {
                have.add(part.trim().toLowerCase(Locale.ROOT));
            }
        }
        int pool = 0;
        for (Affix a : plugin.affixes().all()) {
            if (!have.contains(a.id().toLowerCase(Locale.ROOT))) {
                pool++;
            }
        }
        return pool;
    }

    /**
     * 把剑上所有灵契词条摘干净：Lore「◆ 灵契」行、{@code fm_spirit_*} 属性 modifier、
     * 聚合 PDC 的负差量、{@code affix_list} 里的灵契 id。
     *
     * <p>为什么以 Lore 行为账本：affix_list 里锻造词条与灵契混存一张表，分不出谁是
     * 灵契；只有 Lore 行带「◆ 灵契」标记。摘除前从行文本反查词条 id 与数值，
     * 反查得到数值才做聚合负差量（与 Reforge 摘词条同一条保守口径：
     * 读不到旧值就只摘 modifier，不误扣宝石的份额）。</p>
     *
     * @return 摘掉的灵契 id 列表（小写）
     */
    private List<String> stripSpiritAffixes(ItemMeta meta) {
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        com.forgemaster.affix.AffixRegistry registry = plugin.affixes();
        List<String> removed = new ArrayList<>();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        for (Component c : lore) {
            String plain = Msg.plain(c).trim();
            if (!plain.startsWith(SPIRIT_LORE_TAG)) {
                continue;
            }
            String rest = plain.substring(SPIRIT_LORE_TAG.length()).trim();
            Affix matched = null;
            Double value = null;
            if (registry != null) {
                for (Affix a : registry.all()) {
                    String head = "▪ " + a.display() + " ";
                    if (rest.startsWith(head)) {
                        matched = a;
                        value = parseShownValue(rest.substring(head.length()).trim(), a.percent());
                        break;
                    }
                }
            }
            if (matched == null) {
                continue; // 注册表里已经删掉的词条：行稍后整行清掉，数值无从反查就不动聚合
            }
            // modifier：灵契用独立键名 fm_spirit_<id>，与锻造词条 / 基础属性键不重叠
            if (matched.kind() == Affix.Kind.ATTRIBUTE && matched.attribute() != null) {
                String key = SPIRIT_MOD_PREFIX + matched.id().toLowerCase(Locale.ROOT);
                Collection<AttributeModifier> mods = meta.getAttributeModifiers(matched.attribute());
                if (mods != null) {
                    for (AttributeModifier m : new ArrayList<>(mods)) {
                        if (m.getKey() != null && key.equals(m.getKey().getKey())) {
                            meta.removeAttributeModifier(matched.attribute(), m);
                        }
                    }
                }
            }
            // 聚合 PDC 做负差量：与 grantAffix 的累加互为逆运算（宝石 / 锻造词条份额不动）
            if (value != null) {
                switch (matched.kind()) {
                    case CRIT_CHANCE -> bumpDouble(pdc, Keys.CRIT_CHANCE, -value);
                    case CRIT_DAMAGE -> bumpDouble(pdc, Keys.CRIT_DAMAGE, -value);
                    case LIFESTEAL -> bumpDouble(pdc, Keys.LIFESTEAL, -value);
                    case FIRE -> bumpInt(pdc, Keys.FIRE_TICKS, -(int) Math.round(value) * 20);
                    default -> {
                        // ATTRIBUTE 与 v2.24 新词条在 grantAffix 里就不落聚合账，摘除自然无账可逆
                    }
                }
            }
            removed.add(matched.id().toLowerCase(Locale.ROOT));
        }
        if (removed.isEmpty()) {
            return removed;
        }
        lore.removeIf(c -> Msg.plain(c).trim().startsWith(SPIRIT_LORE_TAG));
        meta.lore(lore);
        String csv = pdc.getOrDefault(Keys.AFFIX_LIST, PersistentDataType.STRING, "");
        pdc.set(Keys.AFFIX_LIST, PersistentDataType.STRING, removeSpiritIds(csv, removed));
        return removed;
    }

    /** Lore 行里「+12%」/「+1.5 单位」→ 数值（百分比词条换算成小数；读不出返回 null） */
    private static Double parseShownValue(String rest, boolean percent) {
        if (rest.isEmpty() || rest.charAt(0) != '+') {
            return null;
        }
        StringBuilder num = new StringBuilder();
        for (int i = 1; i < rest.length(); i++) {
            char ch = rest.charAt(i);
            if (Character.isDigit(ch) || ch == '.') {
                num.append(ch);
            } else {
                break;
            }
        }
        if (num.isEmpty()) {
            return null;
        }
        try {
            double shown = Double.parseDouble(num.toString());
            return percent ? shown / 100.0 : shown;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 聚合 PDC 的 double 负差量（夹 0 下限，与 Reforge 同款） */
    private static void bumpDouble(PersistentDataContainer pdc, NamespacedKey key, double delta) {
        if (Math.abs(delta) < 1e-9) {
            return;
        }
        double cur = pdc.getOrDefault(key, PersistentDataType.DOUBLE, 0.0);
        pdc.set(key, PersistentDataType.DOUBLE,
                Math.round(Math.max(0, cur + delta) * 100.0) / 100.0);
    }

    /** 聚合 PDC 的 int 负差量（夹 0 下限） */
    private static void bumpInt(PersistentDataContainer pdc, NamespacedKey key, int delta) {
        if (delta == 0) {
            return;
        }
        int cur = pdc.getOrDefault(key, PersistentDataType.INTEGER, 0);
        pdc.set(key, PersistentDataType.INTEGER, Math.max(0, cur + delta));
    }

    // ------------------------------------------------------------ 读写

    public int spiritOf(ItemStack item) {
        if (!plugin.items().isForged(item)) {
            return 0;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return 0;
        }
        Integer v = meta.getPersistentDataContainer().get(Keys.SPIRIT, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    public String nameOf(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return "";
        }
        String n = meta.getPersistentDataContainer().get(Keys.SPIRIT_NAME, PersistentDataType.STRING);
        return n == null ? "" : n;
    }

    /**
     * 增加灵性（击中 / 喂养共用）。跨档时补灵契词条并给出提示。
     *
     * @return 是否真的增加了灵性
     */
    public boolean addSpirit(Player player, ItemStack item, int amount) {
        if (!enabled() || amount <= 0 || !plugin.items().isForged(item)) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && addSpirit(player, item, meta, amount);
    }

    /** 复用调用方已持有的 meta（命中热路径省一次 getItemMeta），语义与上一致 */
    public boolean addSpirit(Player player, ItemStack item, ItemMeta meta, int amount) {
        if (!enabled() || amount <= 0 || meta == null) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (!pdc.has(Keys.FORGED, PersistentDataType.BYTE)) {
            return false;
        }
        int cur = pdc.getOrDefault(Keys.SPIRIT, PersistentDataType.INTEGER, 0);
        int max = maxValue();
        if (cur >= max) {
            return false;
        }
        int next = Math.min(max, cur + amount);
        int oldTier = tierOf(cur);
        int newTier = tierOf(next);

        pdc.set(Keys.SPIRIT, PersistentDataType.INTEGER, next);
        pdc.set(Keys.SPIRIT_MAX, PersistentDataType.INTEGER, max);

        if (newTier > oldTier) {
            int have = pdc.getOrDefault(granted, PersistentDataType.INTEGER, 0);
            int slots = slotsOf(newTier);
            while (have < slots) {
                if (!grantAffix(meta)) {
                    break;
                }
                have++;
            }
            pdc.set(granted, PersistentDataType.INTEGER, have);
        }
        refreshLines(meta, next, max);
        com.forgemaster.util.Attrs.apply(item, meta);

        if (newTier > oldTier && player != null) {
            onTierUp(player, newTier);
            // 成就：灵性升档计为一次剑灵觉醒（剑灵数 +1，再判定成就）
            plugin.stats().countSpirit(player.getUniqueId());
            if (plugin.achievements() != null) {
                plugin.achievements().check(player.getUniqueId(), "spirit");
            }
        }
        return true;
    }

    /** 给一条本剑没有的词条（灵契） */
    private boolean grantAffix(ItemMeta meta) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String csv = pdc.getOrDefault(Keys.AFFIX_LIST, PersistentDataType.STRING, "");
        Set<String> have = new HashSet<>();
        for (String part : csv.split(",")) {
            if (!part.isBlank()) {
                have.add(part.trim().toLowerCase(Locale.ROOT));
            }
        }
        List<String> poolIds = new ArrayList<>();
        for (Affix a : plugin.affixes().all()) {
            if (!have.contains(a.id().toLowerCase(Locale.ROOT))) {
                poolIds.add(a.id());
            }
        }
        if (poolIds.isEmpty()) {
            return false;
        }
        List<Affix> picked = plugin.affixes().pick(poolIds, 1);
        if (picked.isEmpty()) {
            return false;
        }
        Affix affix = picked.get(0);
        double value = affix.roll(random);

        switch (affix.kind()) {
            case ATTRIBUTE -> {
                if (affix.attribute() != null) {
                    // 与 ItemFactory / Reforge 同一口：过兼容收敛（含 v2.17.3 攻速全局夹子）
                    double applied = plugin.compat() == null
                            ? value
                            : plugin.compat().cap(affix.attribute(), value);
                    meta.addAttributeModifier(affix.attribute(), new AttributeModifier(
                            new NamespacedKey(plugin, "fm_spirit_" + affix.id().toLowerCase(Locale.ROOT)),
                            applied,
                            AttributeModifier.Operation.ADD_NUMBER,
                            EquipmentSlotGroup.MAINHAND));
                }
            }
            case CRIT_CHANCE -> pdc.set(Keys.CRIT_CHANCE, PersistentDataType.DOUBLE,
                    pdc.getOrDefault(Keys.CRIT_CHANCE, PersistentDataType.DOUBLE, 0.0) + value);
            case CRIT_DAMAGE -> pdc.set(Keys.CRIT_DAMAGE, PersistentDataType.DOUBLE,
                    pdc.getOrDefault(Keys.CRIT_DAMAGE, PersistentDataType.DOUBLE, 0.0) + value);
            case LIFESTEAL -> pdc.set(Keys.LIFESTEAL, PersistentDataType.DOUBLE,
                    pdc.getOrDefault(Keys.LIFESTEAL, PersistentDataType.DOUBLE, 0.0) + value);
            case FIRE -> pdc.set(Keys.FIRE_TICKS, PersistentDataType.INTEGER,
                    pdc.getOrDefault(Keys.FIRE_TICKS, PersistentDataType.INTEGER, 0)
                            + (int) Math.round(value) * 20);
        }

        String id = affix.id().toLowerCase(Locale.ROOT);
        pdc.set(Keys.AFFIX_LIST, PersistentDataType.STRING, csv.isBlank() ? id : csv + "," + id);

        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Msg.mm("<#b06bff>◆ 灵契</#b06bff> " + affix.line(value)).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        return true;
    }

    /** 刷新 Lore 里的灵性行与剑名行（先删旧行再补到末尾） */
    private void refreshLines(ItemMeta meta, int value, int max) {
        String name = meta.getPersistentDataContainer()
                .getOrDefault(Keys.SPIRIT_NAME, PersistentDataType.STRING, "");
        double ratio = max <= 0 ? 0 : (double) value / max;
        int tier = tierOf(value);
        setLoreLine(meta, "灵性", "<gray>灵性: <#b06bff>" + tierName(tier)
                + " <dark_gray>(<white>" + value + "</white><dark_gray>/" + max + ")</dark_gray> "
                + Msg.bar(ratio, 10, "#b06bff", "#3a3a3a"));
        if (!name.isBlank()) {
            setLoreLine(meta, "剑名", "<gray>剑名: <#ffd166>" + name + "</#ffd166>");
        }
    }

    private void setLoreLine(ItemMeta meta, String plainPrefix, String mini) {
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.removeIf(c -> Msg.plain(c).startsWith(plainPrefix));
        lore.add(Msg.mm(mini).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
    }

    // ------------------------------------------------------------ 击中 / 特效

    /** 玩家用成品击中目标时调用（崩刃那一击不调用）；meta 由 CombatListener 复用传入 */
    public void onHit(Player player, ItemStack weapon, ItemMeta meta, Entity target) {
        if (!enabled()) {
            return;
        }
        int gain = plugin.settings().intOf("spirit.gain-per-hit", 1);
        if (gain <= 0) {
            return;
        }
        int tier = tierOf(meta.getPersistentDataContainer()
                .getOrDefault(Keys.SPIRIT, PersistentDataType.INTEGER, 0));
        if (settleHitSpirit(player, weapon, meta, gain, System.currentTimeMillis())) {
            // getItemInMainHand 返回副本，改 meta 后必须写回主手
            player.getInventory().setItemInMainHand(weapon);
        }
        if (tier >= 1 && target != null && plugin.settings().particles() && random.nextInt(3) == 0) {
            Particle p = TIER_PARTICLES[Math.min(tier - 1, TIER_PARTICLES.length - 1)];
            // 走安全包装：ENCHANT / DRAGON_BREATH 要 Float 数据，
            // 直接调不带数据的重载会每次命中抛一次 IllegalArgumentException
            com.forgemaster.util.Particles.spawn(p,
                    target.getLocation().add(0, 1.0, 0), 6, 0.3, 0.4, 0.3, 0.02);
        }
    }

    /** 命中一次的灵性结算（节流版）；返回是否写了物品，调用方据此写回主手槽位 */
    public boolean settleHitSpirit(Player player, ItemStack weapon, ItemMeta meta,
                                   int gain, long nowMillis) {
        return settleHitSpirit(player, player == null ? null : player.getUniqueId(),
                weapon, meta, gain, nowMillis);
    }

    /**
     * 节流核心（自检可注入假 UUID 与固定时钟，player 可为 null 跳过特效）。
     *
     * <p>普通命中只把 gain 记进内存 pending；满 {@value FLUSH_MILLIS} 毫秒、
     * 或这一击把灵性顶过档位线时，才把累计值一次性写进 PDC + Lore。
     * 升档即触发：过线那一击立即落账，特效与灵契词条解锁时机与旧版一致。</p>
     */
    public boolean settleHitSpirit(Player player, UUID playerId, ItemStack weapon, ItemMeta meta,
                                   int gain, long nowMillis) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int cur = pdc.getOrDefault(Keys.SPIRIT, PersistentDataType.INTEGER, 0);
        PendingSpirit pend = pending.get(playerId);
        if (pend != null && pend.base != cur) {
            // 手里这把剑不是挂账那把（切剑 / PDC 被别处写过）：先冲旧账再重新累计
            if (player != null) {
                flushPending(player, weapon);
            } else {
                pending.remove(playerId);
            }
            pend = null;
        }
        int staged = cur + (pend == null ? 0 : pend.amount);

        if (tierOf(staged + gain) > tierOf(staged)) {
            // 升档即触发：立即落账，特效 / 词条解锁照旧走 addSpirit
            boolean wrote = addSpirit(player, weapon, meta, staged + gain - cur);
            pending.remove(playerId);
            return wrote;
        }
        if (pend == null) {
            pend = new PendingSpirit(cur, nowMillis);
            pending.put(playerId, pend);
        }
        pend.amount = staged + gain - cur;
        if (nowMillis - pend.since >= FLUSH_MILLIS) {
            // 满 2 秒：把累计灵性一次性落进 PDC + Lore
            boolean wrote = addSpirit(player, weapon, meta, pend.amount);
            pending.remove(playerId);
            return wrote;
        }
        return false;
    }

    // ------------------------------------------------------------ 节流冲刷

    /** 把该玩家挂着的待落账灵性一次性写回；找不到原剑（已丢弃 / 移交）就放弃，不错写到别的剑 */
    private void flushPending(Player player, ItemStack preferred) {
        PendingSpirit pend = pending.remove(player.getUniqueId());
        if (pend == null || pend.amount <= 0) {
            return;
        }
        // 优先写主手：累计起点对得上基本就是同一把剑
        if (tryFlushInto(player, preferred, pend)) {
            player.getInventory().setItemInMainHand(preferred);
            return;
        }
        // 主手不是挂账那把（切剑 / 收起了）：在背包里按累计起点找回它
        PlayerInventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack it = inv.getItem(slot);
            if (it == null || it.getType().isAir()) {
                continue;
            }
            if (tryFlushInto(player, it, pend)) {
                inv.setItem(slot, it);
                return;
            }
        }
        // 背包里没有：这 ≤2 秒的灵性只能放弃，也不能凭空补给别的剑
    }

    /** 尝试把待落账灵性写进某件物品：仅当它确实是累计起点吻合的那把成品剑 */
    private boolean tryFlushInto(Player player, ItemStack item, PendingSpirit pend) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (!pdc.has(Keys.FORGED, PersistentDataType.BYTE)) {
            return false;
        }
        int cur = pdc.getOrDefault(Keys.SPIRIT, PersistentDataType.INTEGER, 0);
        if (cur != pend.base) {
            return false;
        }
        return addSpirit(player, item, meta, pend.amount);
    }

    /** 清掉某玩家的待落账灵性（冲刷兜底 / 自检清理用） */
    public void clearPending(UUID playerId) {
        pending.remove(playerId);
    }

    /** 服关冲刷：把所有在线玩家的待落账灵性写掉（onDisable 时序在物品仍可用时） */
    public void flushAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (pending.containsKey(p.getUniqueId())) {
                flushPending(p, p.getInventory().getItemInMainHand());
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (pending.containsKey(player.getUniqueId())) {
            // 退服事件里背包还能读写，正是冲刷的最后窗口
            flushPending(player, player.getInventory().getItemInMainHand());
        }
        surgeCooldowns.remove(player.getUniqueId()); // 冷却只对在线有意义，退服即清
        // v2.25.2（审计轮4）：重抽确认的 nonce 也要清。
        // 原来退服不清，重登后 `/zhujian spirit reroll confirm` 的 nonce 仍与剑上 PDC 匹配，
        // 于是可以跳过预览直接扣钱 —— 玩家以为自己还在看预览，钱已经没了。
        pendingReroll.remove(player.getUniqueId());
    }

    /**
     * 灵击：剑灵出鞘震慑。口径照抄烬狱（EnchantListener.onKill 的 v2.23.7 修法）：
     * MONITOR + ignoreCancelled，玩家击杀才触发；伤害用<b>无归因</b>
     * {@code victim.damage(dmg)}——带归因的伤害会再发一个 EntityDamageByEntityEvent，
     * CombatListener 会把它当普通挥击结算（掷暴击 / 吸血 / 扣耐久），链式放大。
     *
     * <p>只炸怪不炸玩家（PVP 不乱杀）；每玩家冷却存内存，退服即清。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSurgeKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || !surgeEnabled()) {
            return;
        }
        // 主手灵性档位：非锻造品 spiritOf 返回 0，天然过不了 minTier ≥ 1 的门槛
        int tier = tierOf(spiritOf(killer.getInventory().getItemInMainHand()));
        if (tier < surgeMinTier()) {
            return;
        }
        double chance = surgeChance();
        if (chance <= 0 || random.nextDouble() >= chance) {
            return;
        }
        long now = System.currentTimeMillis();
        if (surgeCooldownRemaining(killer.getUniqueId(), now) > 0) {
            return;
        }
        double dmg = surgeDamage();
        double radius = surgeRadius();
        if (dmg <= 0 || radius <= 0) {
            return;
        }
        surgeCooldowns.put(killer.getUniqueId(), now + surgeCooldownMs());

        Location at = event.getEntity().getLocation();
        // 粒子走安全包装（与 onHit 同一套，避免不带数据的重载抛 IllegalArgumentException）
        com.forgemaster.util.Particles.spawn(Particle.SOUL_FIRE_FLAME,
                at.clone().add(0, 0.8, 0), 24, radius * 0.4, 0.5, radius * 0.4, 0.03);
        at.getWorld().playSound(at, Sound.PARTICLE_SOUL_ESCAPE, 1.0f, 0.7f);
        int hit = 0;
        for (Entity near : at.getWorld().getNearbyEntities(at, radius, radius, radius)) {
            if (near.equals(killer) || near.equals(event.getEntity())) {
                continue;
            }
            if (!(near instanceof LivingEntity victim) || near instanceof Player) {
                continue; // 玩家击杀怪物时炸怪，不炸玩家（照烬狱：PVP 不乱杀）
            }
            // 无归因结算：不触发暴击 / 吸血 / 耐久链（v2.23.7 的口径）
            victim.damage(dmg);
            hit++;
        }
        if (hit > 0) {
            Msg.action(killer, "<#b06bff>剑灵出鞘</#b06bff> <gray>· 震慑了 " + hit + " 只怪物。</gray>");
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() == plugin) {
            flushAll();
        }
    }

    private void onTierUp(Player player, int tier) {
        String name = tierName(tier);
        player.showTitle(Title.title(
                Msg.mm("<gradient:#b06bff:#4ea8ff><bold>剑灵 · " + name + "</bold></gradient>"),
                Msg.mm("<gray>灵性觉醒" + (slotsOf(tier) > 0 ? "，灵契词条已刻入剑身" : "")),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1400), Duration.ofMillis(400))));
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.9f, 1.2f);
        Msg.prefix(player, "<#b06bff>你的剑灵升到了「" + name + "」</#b06bff>。");
    }

    // ------------------------------------------------------------ 命令

    /** 处理 /zhujian spirit [info|feed|name <剑名>] */
    public void command(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.prefix(sender, "<red>只有玩家能养剑。</red>");
            return;
        }
        if (!enabled()) {
            Msg.prefix(player, "<red>剑灵系统未开启。</red>");
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "info";
        switch (sub) {
            case "info", "view" -> info(player);
            case "feed", "yang" -> feed(player);
            case "name", "rename" -> {
                if (args.length < 3) {
                    Msg.prefix(player, "<gray>用法: <white>/zhujian spirit name <剑名></white>");
                    return;
                }
                rename(player, String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim());
            }
            // 灵契重抽（v2.25 切片二）：无参 = 预览，confirm = 真执行（两段式确认）
            case "reroll" -> reroll(player, args.length >= 3 && isConfirmWord(args[2]));
            // 剑灵显形（拓展第三档）：走独立的 spiritdisplay 服务，只在这里加转发分支
            case "show" -> {
                if (plugin.spiritDisplay() != null) {
                    plugin.spiritDisplay().show(player);
                }
            }
            case "hide" -> {
                if (plugin.spiritDisplay() != null) {
                    plugin.spiritDisplay().hide(player);
                }
            }
            default -> Msg.prefix(player, "<gray>用法: <white>/zhujian spirit [info|feed|name <剑名>|reroll [confirm]|show|hide]</white>");
        }
    }

    /** confirm 的两种写法（照 dissolve / talent reset 的惯例） */
    private static boolean isConfirmWord(String s) {
        return "confirm".equalsIgnoreCase(s) || "queren".equalsIgnoreCase(s);
    }

    private ItemStack heldSword(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir() || !plugin.items().isForged(hand)) {
            Msg.prefix(player, "<red>主手要拿一把锻造品。</red>");
            return null;
        }
        return hand;
    }

    private void info(Player player) {
        ItemStack hand = heldSword(player);
        if (hand == null) {
            return;
        }
        int value = spiritOf(hand);
        int max = maxValue();
        int tier = tierOf(value);
        List<Integer> th = tiers();
        String next = tier + 1 < th.size()
                ? "距「" + tierName(tier + 1) + "」还差 " + (th.get(tier + 1) - value)
                : "已达最高档";
        String name = nameOf(hand);
        Msg.send(player, "<dark_gray>──── <gradient:#b06bff:#4ea8ff><bold>剑灵</bold></gradient> <dark_gray>────");
        Msg.send(player, "<gray>剑名: <white>" + (name.isBlank() ? "未命名" : name));
        Msg.send(player, "<gray>灵性: <#b06bff>" + tierName(tier) + " <white>" + value + "<dark_gray>/" + max
                + " <gray>· " + next);
        Msg.send(player, "<gray>本档应有灵契词条: <white>" + slotsOf(tier) + "</white> 条");
        if (value >= max && slotsOf(tier) > 0) {
            Msg.send(player, "<gray>灵契重抽: <white>/zhujian spirit reroll</white> <dark_gray>（"
                    + rerollCost() + " 剑币 · 每日 " + rerollDailyLimit() + " 次）</dark_gray>");
        }
        Msg.send(player, "<dark_gray>击中敌人自动养灵；<white>/zhujian spirit feed</white> 花剑币喂养，"
                + "<white>/zhujian spirit name <剑名></white> 起名。");
    }

    private void feed(Player player) {
        ItemStack hand = heldSword(player);
        if (hand == null) {
            return;
        }
        // 先把挂着的命中灵性冲进这把剑，否则喂养改了 PDC 基数，挂账会找不到原剑被丢弃
        flushPending(player, hand);
        if (spiritOf(hand) >= maxValue()) {
            Msg.prefix(player, "<green>这把剑的灵性已经满了。</green>");
            return;
        }
        long cost = Math.max(0, plugin.settings().intOf("spirit.feed-coin-cost", 10));
        // v2.23.7：扣款走 Wallet 收口（coin-sinks=server 时与 Vault 同账本），
        // 直连 stats().takeCoins 会造成「黑市扣金币、喂养扣剑币」两本账分裂
        if (!plugin.wallet().take(player, cost, com.forgemaster.econ.CoinReason.SPIRIT)) {
            Msg.prefix(player, "<red>喂养需要 <white>" + cost + "</white> 剑币，你不够。</red>");
            return;
        }
        int gain = Math.max(1, plugin.settings().intOf("spirit.feed-spirit", 30));
        addSpirit(player, hand, gain);
        player.getInventory().setItemInMainHand(hand);
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_DRINK, 0.8f, 1.2f);
        Msg.prefix(player, "<gray>喂养成功：灵性 <#b06bff>+" + gain + "</#b06bff> · 花费 <white>"
                + cost + "</white> 剑币。");
    }

    private void rename(Player player, String raw) {
        ItemStack hand = heldSword(player);
        if (hand == null) {
            return;
        }
        int maxLen = Math.max(1, plugin.settings().intOf("spirit.name-max-length", 8));
        if (raw.isEmpty() || raw.length() > maxLen) {
            Msg.prefix(player, "<red>剑名长度要在 1 ~ " + maxLen + " 个字符之间。</red>");
            return;
        }
        if (raw.matches(".*[<>&§].*")) {
            Msg.prefix(player, "<red>剑名不能包含 < > & § 这些符号。</red>");
            return;
        }
        ItemMeta meta = hand.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(Keys.SPIRIT_NAME, PersistentDataType.STRING, raw);
        refreshLines(meta, spiritOf(hand), maxValue());
        com.forgemaster.util.Attrs.apply(hand, meta);
        player.getInventory().setItemInMainHand(hand);
        if (plugin.codex() != null) plugin.codex().registerSpiritName(player.getUniqueId()); // 收藏图鉴登记（拓展第一档）
        Msg.prefix(player, "<gray>剑已命名为「<#ffd166>" + raw + "</#ffd166>」。</gray>");
    }
}
