package com.forgemaster.spirit;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.affix.Affix;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
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

    private int maxValue() {
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
        int cap = Math.max(0, plugin.settings().intOf("spirit.max-extra-affixes", 2));
        return Math.min(cap, Math.max(0, tier - 1));
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
            default -> Msg.prefix(player, "<gray>用法: <white>/zhujian spirit [info|feed|name <剑名>|show|hide]</white>");
        }
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
        if (!plugin.stats().takeCoins(player.getUniqueId(), cost, com.forgemaster.econ.CoinReason.OTHER_OUT)) {
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
