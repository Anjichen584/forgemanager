package com.forgemaster.affix;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.econ.CoinReason;
import com.forgemaster.item.ItemFactory;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 词条符：把一条词条从一把剑上<b>剥</b>下来，装到另一把剑上。
 *
 * <p>重铸解决的是「这条不想要」，剥离解决的是「这条不想浪费」。原来的词条只有在
 * 重铸时被覆盖掉的命，一把打到 +20 的剑上留了一条好词条，换胚子就只能扔掉重打；
 * 现在可以剥成「词条符」，作为物品交易 / 存箱 / 攒着等下一把好胚子。</p>
 *
 * <p>三条硬规则：</p>
 * <ul>
 *   <li><b>数值原样搬运</b>：词条符里存的是剥离那一刻的实际值，不回滚、不重抽；
 *       否则「剥下来再装回去」就成了洗数值的漏洞。</li>
 *   <li><b>同 id 不重复</b>：目标剑上已经有这条词条时直接拒绝，与重铸的抽取规则一致。</li>
 *   <li><b>武器 / 装备分池</b>：装备词条只能进护甲，武器词条只能进武器。词条符上
 *       记了 {@code imprint_armor}，跨池移植会被拦下。</li>
 * </ul>
 *
 * <p>配置段 {@code imprint.*}；所有费用都走内置剑币（与强化、重铸一致）。</p>
 */
public final class Imprint {

    private static final int DEFAULT_MAX_AFFIXES = 6;

    private Imprint() {
    }

    // ------------------------------------------------------------ 开关与查询

    public static boolean enabled(ForgingMasterPlugin plugin) {
        return plugin != null && plugin.settings().flag("imprint.enabled", true);
    }

    /** 一件物品最多几条词条（默认 6，与「七工序满分 +1、专精 +1」的上限一致） */
    public static int maxAffixes(ForgingMasterPlugin plugin) {
        return Math.max(1, plugin.settings().intOf("imprint.max-affixes", DEFAULT_MAX_AFFIXES));
    }

    /** 是不是词条符 */
    public static boolean isImprint(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        return meta.getPersistentDataContainer().has(Keys.IMPRINT_AFFIX, PersistentDataType.STRING);
    }

    public static String affixIdOf(ItemStack item) {
        if (!isImprint(item)) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .get(Keys.IMPRINT_AFFIX, PersistentDataType.STRING);
    }

    public static double valueOf(ItemStack item) {
        if (!isImprint(item)) {
            return 0;
        }
        Double v = item.getItemMeta().getPersistentDataContainer()
                .get(Keys.IMPRINT_VALUE, PersistentDataType.DOUBLE);
        return v == null ? 0 : v;
    }

    /** 这条词条符是不是装备词条（只能进护甲） */
    public static boolean armorOnly(ItemStack item) {
        if (!isImprint(item)) {
            return false;
        }
        Byte b = item.getItemMeta().getPersistentDataContainer()
                .get(Keys.IMPRINT_ARMOR, PersistentDataType.BYTE);
        return b != null && b == 1;
    }

    // ------------------------------------------------------------ 造词条符

    /**
     * 造一张词条符（不发给玩家，纯构造）。
     *
     * @param armor 这条词条属于装备池（进护甲）还是武器池
     */
    public static ItemStack build(ForgingMasterPlugin plugin, Affix a, double value, boolean armor) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Msg.mm("<gradient:#ffd166:#ff7a18><bold>词条符 · " + a.display()
                + "</bold></gradient>").decoration(TextDecoration.ITALIC, false));

        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(line("<dark_gray>—— 铸剑大师 · 词条符 ——</dark_gray>"));
        lore.add(line(a.line(value)));
        lore.add(line("<gray>类别: <white>" + (armor ? "装备" : "武器")
                + " <dark_gray>·</dark_gray> <gray>来源: <white>剥离"));
        lore.add(line(""));
        lore.add(line("<gray>主手拿锻造品，副手拿这张符，执行"));
        lore.add(line("<white>/zhujian strengthen graft</white> <gray>把词条装进去"));
        meta.lore(lore);

        var pdc = meta.getPersistentDataContainer();
        pdc.set(Keys.IMPRINT_AFFIX, PersistentDataType.STRING, a.id().toLowerCase(Locale.ROOT));
        pdc.set(Keys.IMPRINT_VALUE, PersistentDataType.DOUBLE, round(value));
        if (armor) {
            pdc.set(Keys.IMPRINT_ARMOR, PersistentDataType.BYTE, (byte) 1);
        }
        com.forgemaster.util.Attrs.apply(item, meta);
        return item;
    }

    // ------------------------------------------------------------ 剥离

    /**
     * 剥离主手锻造品的第 {@code index} 条词条（0 基）。
     *
     * @return 给玩家看的 MiniMessage 文本（成功 / 失败原因都在里面）
     */
    public static String strip(ForgingMasterPlugin plugin, Player player, ItemStack item, int index) {
        if (!enabled(plugin)) {
            return "<red>词条剥离没开（<white>imprint.enabled = false</white>）。";
        }
        if (item == null || item.getType().isAir() || !plugin.items().isForged(item)) {
            return "<red>只有锻造品上的词条能剥。";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return "<red>这件物品读不出数据。";
        }
        List<String> ids = new ArrayList<>(Reforge.affixIdsOf(item));
        if (ids.isEmpty()) {
            return "<red>这件身上没有词条，没什么可剥的。";
        }
        if (index < 0 || index >= ids.size()) {
            return "<red>没有第 " + (index + 1) + " 条词条（这件一共 " + ids.size() + " 条）。";
        }
        Affix a = plugin.affixes().get(ids.get(index).trim());
        if (a == null) {
            return "<red>这条词条（<white>" + ids.get(index) + "</white>）在 config.yml 里已经找不到了，"
                    + "剥下来也装不回去，先让服主补回来。";
        }
        Double value = Reforge.valueOf(meta, a);
        if (value == null) {
            return "<red>读不出这条词条的数值（Lore 可能被别的插件改过），剥下来会变成 0，先找服主看看。";
        }

        long cost = stripCost(plugin, meta, index);
        long coins = plugin.stats().coins(player.getUniqueId());
        if (coins < cost) {
            return "<red>剥离费不够：需要 <white>" + cost + "</white>，你只有 <white>" + coins + "</white>。";
        }

        boolean armor = isArmor(meta);
        plugin.stats().takeCoins(player.getUniqueId(), cost, CoinReason.IMPRINT);
        try {
            return stripApply(plugin, player, item, meta, ids, index, a, value, armor, cost);
        } catch (RuntimeException ex) {
            // 写物品失败必须退钱，不能让玩家白扣
            plugin.stats().addCoins(player.getUniqueId(), cost, CoinReason.REFUND);
            plugin.getLogger().warning("词条剥离失败（已退 " + cost + " 剑币）: " + ex.getMessage());
            return "<red>剥离出了点问题（" + ex.getMessage() + "），剑币已退回，词条没有动。";
        }
    }

    /** 剥离的写入阶段：只在扣费之后执行，异常由 strip 兜住退款 */
    private static String stripApply(ForgingMasterPlugin plugin, Player player, ItemStack item,
                                     ItemMeta meta, List<String> ids, int index,
                                     Affix a, double value, boolean armor, long cost) {
        ids.remove(index);
        Reforge.removeAffixRaw(meta, a, value);
        meta.getPersistentDataContainer().set(Keys.AFFIX_LIST, PersistentDataType.STRING,
                String.join(",", ids));
        Reforge.redrawAffixes(plugin, meta, ids, null, 0);
        com.forgemaster.util.Attrs.apply(item, meta);
        // 套装可能因为少了一条而散掉，Lore 行与属性一起重刷
        ItemFactory.applyAffixSet(plugin, item);
        // 主手读到的是镜像，显式写回一格，保证 PDC / 属性 / Lore 一定落到物品上
        player.getInventory().setItemInMainHand(item);

        ItemStack imprint = build(plugin, a, value, armor);
        give(player, imprint);

        return "<green>剥离成功</green> <dark_gray>[</dark_gray><white>" + (index + 1)
                + "<dark_gray>/</dark_gray>" + (ids.size() + 1) + "<dark_gray>]</dark_gray> "
                + a.display() + " " + amount(a, value)
                + " <dark_gray>→</dark_gray> <#ffd166>词条符</#ffd166>"
                + " <dark_gray>· 花掉 " + cost + " 剑币</dark_gray>";
    }

    // ------------------------------------------------------------ 移植

    /**
     * 把副手的词条符装进主手锻造品。
     *
     * @return 给玩家看的 MiniMessage 文本
     */
    public static String graft(ForgingMasterPlugin plugin, Player player, ItemStack item, ItemStack imprint) {
        if (!enabled(plugin)) {
            return "<red>词条移植没开（<white>imprint.enabled = false</white>）。";
        }
        if (item == null || item.getType().isAir() || !plugin.items().isForged(item)) {
            return "<red>主手要拿着一件锻造品。";
        }
        if (!isImprint(imprint)) {
            return "<red>副手要拿一张词条符（用 <white>/zhujian strengthen strip <序号></white> 剥出来）。";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return "<red>这件物品读不出数据。";
        }
        Affix a = plugin.affixes().get(affixIdOf(imprint));
        if (a == null) {
            return "<red>这张符上的词条在 config.yml 里已经没有了，装不进去。";
        }

        boolean armor = isArmor(meta);
        if (armorOnly(imprint) != armor) {
            return armor
                    ? "<red>这是武器词条，装不进护甲。"
                    : "<red>这是装备词条，只能装进护甲。";
        }

        List<String> ids = new ArrayList<>(Reforge.affixIdsOf(item));
        String id = a.id().toLowerCase(Locale.ROOT);
        if (ids.contains(id)) {
            return "<red>这件已经有「<white>" + a.display() + "</white>」了，同一条词条不能叠两次。";
        }
        int max = maxAffixes(plugin);
        if (ids.size() >= max) {
            return "<red>这件已经有 <white>" + ids.size() + "</white> 条词条，上限 " + max
                    + " 条。先剥掉一条再来。";
        }

        double value = valueOf(imprint);
        if (value <= 0) {
            return "<red>这张符的数值是 0（数据可能坏了），装进去等于白装，先找服主看看。";
        }

        long cost = graftCost(plugin, meta);
        long coins = plugin.stats().coins(player.getUniqueId());
        if (coins < cost) {
            return "<red>移植费不够：需要 <white>" + cost + "</white>，你只有 <white>" + coins + "</white>。";
        }

        plugin.stats().takeCoins(player.getUniqueId(), cost, CoinReason.IMPRINT);
        try {
            return graftApply(plugin, player, item, meta, imprint, ids, a, value, cost, max);
        } catch (RuntimeException ex) {
            plugin.stats().addCoins(player.getUniqueId(), cost, CoinReason.REFUND);
            plugin.getLogger().warning("词条移植失败（已退 " + cost + " 剑币）: " + ex.getMessage());
            return "<red>移植出了点问题（" + ex.getMessage() + "），剑币已退回，词条没有动。";
        }
    }

    /** 移植的写入阶段：只在扣费之后执行，异常由 graft 兜住退款 */
    private static String graftApply(ForgingMasterPlugin plugin, Player player, ItemStack item,
                                     ItemMeta meta, ItemStack imprint, List<String> ids, Affix a,
                                     double value, long cost, int max) {
        String id = a.id().toLowerCase(Locale.ROOT);
        ids.add(id);
        Reforge.addAffixRaw(plugin, meta, a, value, groupOf(meta));
        meta.getPersistentDataContainer().set(Keys.AFFIX_LIST, PersistentDataType.STRING,
                String.join(",", ids));
        Reforge.redrawAffixes(plugin, meta, ids, a, value);
        com.forgemaster.util.Attrs.apply(item, meta);
        ItemFactory.applyAffixSet(plugin, item);
        player.getInventory().setItemInMainHand(item);

        // 符只吃一张；主手那件是镜像，写回一格保证落盘
        ItemStack off = imprint.clone();
        off.setAmount(off.getAmount() - 1);
        player.getInventory().setItemInOffHand(off.getAmount() <= 0 ? null : off);

        return "<green>移植成功</green> <dark_gray>[</dark_gray><white>" + a.display() + " "
                + amount(a, value) + "<dark_gray>]</dark_gray>"
                + " <dark_gray>· 词条 " + ids.size() + "/" + max + " · 花掉 " + cost + " 剑币</dark_gray>";
    }

    // ------------------------------------------------------------ 合成

    /**
     * {@code /zhujian imprint merge}：背包里 3 张同名（同 affix id）词条符，
     * 合成 1 张「数值 ×1.5」的同名词条符。
     *
     * <p>数值上限 = {@link AffixRegistry} 里该词条 {@code max} × {@code imprint.merge.cap-mult}
     * （默认 2.0），超出截断；不足 0.1 的零头直接向下截断。三张全部消耗；
     * 名字不一致 / 数量不足 / 剑币不够时一张都不动。</p>
     *
     * <p>目标词条的取法：主手拿着词条符就合成它那一族；否则背包里只有一族凑满 3 张时
     * 自动选它；多族都够时会列出来，让玩家把其中一张拿在主手再合。</p>
     *
     * <p>产出复用 {@link #build}，PDC / Lore 与剥离出来的词条符完全同构，
     * 能直接走现有移植（graft）流程。</p>
     *
     * @return 给玩家看的 MiniMessage 文本
     */
    public static String merge(ForgingMasterPlugin plugin, Player player) {
        if (!plugin.settings().flag("imprint.merge.enabled", true)) {
            return "<red>词条合成没开（<white>imprint.merge.enabled = false</white>）。</red>";
        }
        double mult = Math.max(1.0, plugin.settings().num("imprint.merge.mult", 1.5));
        double capMult = Math.max(1.0, plugin.settings().num("imprint.merge.cap-mult", 2.0));
        long cost = Math.max(0, plugin.settings().longOf("imprint.merge.cost-coins", 2000));

        // ---- 扫背包：affix id → 各槽位数量 / 最高值 / 池子标记 ----
        var inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        java.util.Map<String, List<int[]>> stacks = new java.util.LinkedHashMap<>(); // id → [槽位, 数量]
        java.util.Map<String, Double> best = new java.util.HashMap<>();
        java.util.Map<String, Boolean> armors = new java.util.HashMap<>();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack it = contents[slot];
            if (!isImprint(it)) {
                continue;
            }
            String id = affixIdOf(it).toLowerCase(Locale.ROOT);
            stacks.computeIfAbsent(id, k -> new ArrayList<>()).add(new int[]{slot, it.getAmount()});
            best.merge(id, valueOf(it), Math::max);
            armors.putIfAbsent(id, armorOnly(it));
        }
        if (stacks.isEmpty()) {
            return "<red>背包里没有词条符（用 <white>/zhujian strengthen strip <序号></white> 剥出来）。</red>";
        }

        // ---- 定目标词条族 ----
        String targetId = null;
        ItemStack held = player.getInventory().getItemInMainHand();
        if (isImprint(held)) {
            targetId = affixIdOf(held).toLowerCase(Locale.ROOT);
        }
        List<String> ready = new ArrayList<>();
        for (var e : stacks.entrySet()) {
            int total = 0;
            for (int[] s : e.getValue()) {
                total += s[1];
            }
            if (total >= 3) {
                ready.add(e.getKey());
            }
        }
        if (targetId == null) {
            if (ready.size() == 1) {
                targetId = ready.get(0);
            } else if (ready.isEmpty()) {
                return "<red>背包里没有哪一族凑满 3 张同名词条符。</red>";
            } else {
                StringBuilder names = new StringBuilder();
                for (String id : ready) {
                    Affix a = plugin.affixes().get(id);
                    if (names.length() > 0) {
                        names.append("<gray>、</gray>");
                    }
                    names.append("<white>").append(a == null ? id : a.display()).append("</white>");
                }
                return "<gray>有好几族都凑够了 3 张，把其中一张拿在主手再合：<newline>" + names;
            }
        }
        if (!ready.contains(targetId)) {
            int total = 0;
            for (int[] s : stacks.getOrDefault(targetId, List.of())) {
                total += s[1];
            }
            return "<red>「<white>" + targetId + "</white>」只有 <white>" + total
                    + "</white> 张，合成要 3 张同名（同 affix id）的。</red>";
        }
        Affix a = plugin.affixes().get(targetId);
        if (a == null) {
            return "<red>这张符上的词条（<white>" + targetId
                    + "</white>）在 config.yml 里已经没有了，合成不了。</red>";
        }

        // ---- 剑币预检：失败在这里返回，一张都不消耗 ----
        long coins = plugin.stats().coins(player.getUniqueId());
        if (coins < cost) {
            return "<red>合成费不够：需要 <white>" + cost + "</white>，你只有 <white>" + coins + "</white>。</red>";
        }

        // ---- 算产出数值：最高值 ×1.5，封顶 max × cap-mult，不足 0.1 的零头截断 ----
        double value = Math.floor(Math.min(a.max() * capMult, best.get(targetId) * mult) * 10.0) / 10.0;
        if (value <= 0) {
            return "<red>这一族最高只有 <white>" + best.get(targetId)
                    + "</white>，乘出来不足 0.1，合成不了。</red>";
        }

        // ---- 消耗 3 张（校验都过了才动手）----
        int left = 3;
        for (int[] s : stacks.get(targetId)) {
            if (left <= 0) {
                break;
            }
            ItemStack it = inv.getItem(s[0]);
            if (it == null || !isImprint(it)) {
                continue;
            }
            int take = Math.min(left, it.getAmount());
            it.setAmount(it.getAmount() - take);
            if (it.getAmount() <= 0) {
                inv.setItem(s[0], null);
            } else {
                inv.setItem(s[0], it);
            }
            left -= take;
        }
        if (cost > 0) {
            plugin.stats().takeCoins(player.getUniqueId(), cost, CoinReason.IMPRINT);
        }
        give(player, build(plugin, a, value, Boolean.TRUE.equals(armors.get(targetId))));
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.4f);
        return "<green>合成成功</green> <dark_gray>[</dark_gray><white>" + a.display() + " "
                + amount(a, value) + "<dark_gray>]</dark_gray>"
                + " <dark_gray>· 吃掉 3 张 · 花掉 " + cost + " 剑币"
                + (value >= a.max() * capMult ? " · 已到数值上限" : "") + "</dark_gray>";
    }

    // ------------------------------------------------------------ 费用

    /** 剥离费：按当前档位与序号（越靠后越贵） */
    public static long stripCost(ForgingMasterPlugin plugin, ItemMeta meta, int index) {
        double base = plugin.settings().num("imprint.strip.cost-base", 260);
        double perIndex = plugin.settings().num("imprint.strip.cost-per-index", 120);
        double tierFactor = plugin.settings().num("imprint.strip.tier-factor", 1.5);
        return cost(base + perIndex * Math.max(0, index), tierFactor, tierOf(plugin, meta));
    }

    /** 移植费：只按档位，跟装第几条无关 */
    public static long graftCost(ForgingMasterPlugin plugin, ItemMeta meta) {
        double base = plugin.settings().num("imprint.graft.cost-base", 480);
        double tierFactor = plugin.settings().num("imprint.graft.tier-factor", 1.6);
        return cost(base, tierFactor, tierOf(plugin, meta));
    }

    private static long cost(double base, double tierFactor, int tier) {
        double v = base * Math.pow(Math.max(1.0, tierFactor), Math.max(0, tier - 1));
        return Math.max(0, Math.round(v));
    }

    private static int tierOf(ForgingMasterPlugin plugin, ItemMeta meta) {
        String id = meta.getPersistentDataContainer().get(Keys.MATERIAL, PersistentDataType.STRING);
        ForgeMaterial mat = id == null || plugin.materials() == null ? null : plugin.materials().get(id);
        return mat == null ? 1 : Math.max(1, mat.tier());
    }

    // ------------------------------------------------------------ 小工具

    private static boolean isArmor(ItemMeta meta) {
        return meta.getPersistentDataContainer().has(Keys.ARMOR_SLOT, PersistentDataType.STRING);
    }

    private static EquipmentSlotGroup groupOf(ItemMeta meta) {
        ForgeMaterial.Slot slot = ForgeMaterial.Slot.byName(
                meta.getPersistentDataContainer().get(Keys.ARMOR_SLOT, PersistentDataType.STRING));
        return slot == null ? EquipmentSlotGroup.MAINHAND : slot.group();
    }

    private static void give(Player player, ItemStack item) {
        var leftover = player.getInventory().addItem(item);
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    private static String amount(Affix a, double value) {
        String text = a.percent()
                ? "+" + trim(value * 100.0) + "%"
                : "+" + trim(value);
        return "<aqua>" + text + "</aqua> <gray>" + a.unit();
    }

    private static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static net.kyori.adventure.text.Component line(String mini) {
        return Msg.mm(mini).decoration(TextDecoration.ITALIC, false);
    }
}
