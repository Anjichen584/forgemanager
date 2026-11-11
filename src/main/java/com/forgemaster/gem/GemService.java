package com.forgemaster.gem;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.econ.CoinReason;
import com.forgemaster.quality.Quality;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Random;

/**
 * 宝石系统：开孔 → 熔炼宝石 → 镶嵌。
 *
 * <p>装备上的孔位与宝石都放在 PDC，宝石镶嵌时直接写 AttributeModifier 或累加 PDC 汇总值，
 * 不用重建物品，镶嵌完立刻生效。</p>
 */
public final class GemService {

    /** 一颗已镶嵌的宝石 */
    public record GemEntry(GemType type, double value, GemType.Grade grade) {
        public String encode() {
            return type.id() + ":" + value + ":" + grade.name();
        }

        public static GemEntry decode(String raw) {
            String[] parts = raw.split(":");
            if (parts.length < 3) {
                return null;
            }
            GemType type = GemType.byId(parts[0]);
            if (type == null) {
                return null;
            }
            try {
                return new GemEntry(type, Double.parseDouble(parts[1]), GemType.Grade.byName(parts[2]));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }

    private final ForgingMasterPlugin plugin;
    private final Random random = new Random();

    public GemService(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ 读物品

    public boolean isGem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte mark = meta.getPersistentDataContainer().get(Keys.GEM, PersistentDataType.BYTE);
        return mark != null && mark == 1;
    }

    public int socketsOf(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) {
            return 0;
        }
        Integer v = meta.getPersistentDataContainer().get(Keys.SOCKETS, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    /** 宝石孔数（v2.10 起 {@link #socketsOf} 只表示宝石孔，这里只是语义化别名） */
    public int gemSocketsOf(ItemStack item) {
        return socketsOf(item);
    }

    /**
     * 附魔孔数。
     *
     * <p>键存在就用键上的值；键缺失 = 2.10 之前的老物品，回退成宝石孔数，
     * 免得老东西升级后凭空少一半孔位。</p>
     */
    public int enchantSocketsOf(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) {
            return 0;
        }
        Integer v = meta.getPersistentDataContainer()
                .get(Keys.ENCHANT_SOCKETS, PersistentDataType.INTEGER);
        if (v != null) {
            return Math.max(0, v);
        }
        return socketsOf(item);
    }

    /**
     * 附魔孔上限，按材料档位定，和宝石孔用同一套阶梯但<b>独立配置</b>。
     *
     * <p>想做出「宝石孔富、附魔孔穷」的层次，改
     * {@code gems.enchant-sockets.tiers.*} 即可，不影响宝石那边。</p>
     */
    public int enchantLimitOf(ItemStack item) {
        if (item == null || !plugin.items().isForged(item)) {
            return 0;
        }
        // FORGED 门已过；解析本体统一走 util/MaterialLookup（audit 04 D5；id 为 null 时 resolve 返回 null）
        var mat = com.forgemaster.util.MaterialLookup.resolve(plugin, plugin.items().materialIdOf(item));
        int tier = mat == null ? 1 : mat.tier();
        int idx = Math.max(1, Math.min(5, tier));
        return Math.max(0, plugin.settings().intOf(
                "gems.enchant-sockets.tiers." + idx, defaultSockets(idx)));
    }

    /** 已占用的宝石孔 */
    public int usedGemSockets(ItemStack item) {
        return gemsOf(item).size();
    }

    /** 已占用的附魔孔 */
    public int usedEnchantSockets(ItemStack item) {
        return enchantsOf(item).size();
    }

    /**
     * 把老物品的附魔孔"冻结"下来。
     *
     * <p>老物品没有 {@code ENCHANT_SOCKETS} 键，{@link #enchantSocketsOf} 会回退成
     * 宝石孔数。但一旦这次开孔把宝石孔推到 N+1，回退值也跟着涨——等于老物品
     * 每开一个宝石孔就白送一个附魔孔。</p>
     *
     * <p>所以在<b>开宝石孔之前</b>先把回退值落成真实键值，之后就各算各的。</p>
     */
    private void freezeEnchantSockets(ItemMeta meta) {
        var pdc = meta.getPersistentDataContainer();
        if (pdc.has(Keys.ENCHANT_SOCKETS, PersistentDataType.INTEGER)) {
            return;
        }
        Integer gem = pdc.get(Keys.SOCKETS, PersistentDataType.INTEGER);
        pdc.set(Keys.ENCHANT_SOCKETS, PersistentDataType.INTEGER, gem == null ? 0 : Math.max(0, gem));
    }

    public List<GemEntry> gemsOf(ItemStack item) {
        List<GemEntry> list = new ArrayList<>();
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) {
            return list;
        }
        String raw = meta.getPersistentDataContainer().get(Keys.GEMS, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            return list;
        }
        for (String part : raw.split("\\|")) {
            GemEntry e = GemEntry.decode(part);
            if (e != null) {
                list.add(e);
            }
        }
        return list;
    }

    /** 一条已镶进孔里的附魔 */
    public record EnchantEntry(org.bukkit.enchantments.Enchantment enchantment, int level) {
        public String encode() {
            return enchantment.getKey().getKey() + ":" + level;
        }

        public static EnchantEntry decode(String raw) {
            String[] parts = raw.split(":");
            if (parts.length < 2) {
                return null;
            }
            var ench = org.bukkit.Registry.ENCHANTMENT.get(
                    NamespacedKey.minecraft(parts[0].toLowerCase()));
            if (ench == null) {
                return null;
            }
            try {
                return new EnchantEntry(ench, Integer.parseInt(parts[1]));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }

    /** 已镶进孔里的附魔 */
    public List<EnchantEntry> enchantsOf(ItemStack item) {
        List<EnchantEntry> list = new ArrayList<>();
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) {
            return list;
        }
        Set<org.bukkit.enchantments.Enchantment> seen = new LinkedHashSet<>();
        String raw = meta.getPersistentDataContainer().get(Keys.ENCHANTS, PersistentDataType.STRING);
        if (raw != null && !raw.isBlank()) {
            for (String part : raw.split("\\|")) {
                EnchantEntry e = EnchantEntry.decode(part);
                if (e != null && seen.add(e.enchantment())) {
                    list.add(e);
                }
            }
        }
        // 账目对齐（v2.21）：把物品上「真实存在但你不知道」的附魔也算进来。
        //
        // 为什么必须并集：附魔孔上限只看这张表。以前表里只有插件自己写的一行行，
        // 一旦有别的途径给锻造品挂上原版附魔——老版本物品、别的插件、管理员 /give——
        // 表里就少算，玩家可以继续镶到超限，而且 Lore 显示的数字是错的。
        // 并集之后「已用孔数」永远不小于真实附魔数，只会更严，不会更松。
        for (var e : meta.getEnchants().entrySet()) {
            if (!seen.add(e.getKey())) {
                // 表里已有：取更大的等级，绝不因为读原版值把等级改小
                for (int k = 0; k < list.size(); k++) {
                    EnchantEntry cur = list.get(k);
                    if (cur.enchantment().equals(e.getKey()) && e.getValue() > cur.level()) {
                        list.set(k, new EnchantEntry(e.getKey(), e.getValue()));
                        break;
                    }
                }
                continue;
            }
            list.add(new EnchantEntry(e.getKey(), e.getValue()));
        }
        return list;
    }

    /**
     * 已占用的孔数合计。
     *
     * <p>v2.10 起宝石与附魔各走各的池子，这个数只用于显示「一共镶了几个」，
     * 不再参与容量判定——判定请用 {@link #usedGemSockets} /
     * {@link #usedEnchantSockets} 配各自的上限。</p>
     */
    public int usedSockets(ItemStack item) {
        return gemsOf(item).size() + enchantsOf(item).size();
    }

    /**
     * 这件物品最多能开几个孔——<b>按材料档位定</b>。
     *
     * <p>铁 / 金 1 孔，铜 1 孔，钻石 2 孔，下界合金 2 孔，星陨 3 孔。
     * 品质不再影响孔数（品质的价值体现在词条数与属性倍率上）。</p>
     */
    public int socketLimitOf(ItemStack item) {
        if (item == null || !plugin.items().isForged(item)) {
            return 0;
        }
        // FORGED 门已过；解析本体统一走 util/MaterialLookup（audit 04 D5；id 为 null 时 resolve 返回 null）
        var mat = com.forgemaster.util.MaterialLookup.resolve(plugin, plugin.items().materialIdOf(item));
        int tier = mat == null ? 1 : mat.tier();
        int idx = Math.max(1, Math.min(5, tier));
        return Math.max(0, plugin.settings().intOf("gems.sockets.tiers." + idx, defaultSockets(idx)));
    }

    /** 缺省孔数阶梯：铁/金 1、铜 1、钻石 2、下界合金 2、星陨 3 */
    public static int defaultSockets(int tier) {
        return switch (Math.max(1, Math.min(5, tier))) {
            case 1, 2 -> 1;
            case 3, 4 -> 2;
            default -> 3;
        };
    }

    // ------------------------------------------------------------ 宝石物品

    public ItemStack createGem(GemType type, GemType.Grade grade) {
        return createGem(type, grade, type.roll(grade, random));
    }

    /** 指定数值建宝石（拆卸返还时保留原值） */
    public ItemStack createGem(GemType type, GemType.Grade grade, double value) {
        ItemStack item = new ItemStack(type.icon());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Msg.mm(grade.colored() + " <" + type.color() + ">" + type.gemName() + "</" + type.color() + ">")
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(line("<dark_gray>—— 锻造宝石 ——</dark_gray>"));
        lore.add(line("<gray>品质: " + grade.colored()));
        lore.add(line(type.format(value)));
        lore.add(line(""));
        lore.add(line("<gray>用法: <white>主手装备、副手宝石</white> 后"));
        lore.add(line("<white>/zhujian gem inlay</white> <gray>镶嵌"));
        lore.add(line("<dark_gray>装备要先开孔"));
        meta.lore(lore);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(Keys.GEM, PersistentDataType.BYTE, (byte) 1);
        pdc.set(Keys.GEM_TYPE, PersistentDataType.STRING, type.id());
        pdc.set(Keys.GEM_VALUE, PersistentDataType.DOUBLE, value);
        pdc.set(Keys.GEM_GRADE, PersistentDataType.STRING, grade.name());

        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        com.forgemaster.util.Attrs.apply(item, meta);
        return item;
    }

    // ------------------------------------------------------------ 熔炼

    /** 主手矿物 → 随机宝石 */
    public void smelt(Player player) {
        if (!enabled()) {
            Msg.prefix(player, "<red>宝石系统已关闭。</red>");
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            Msg.prefix(player, "<red>主手要拿下界之星。</red>");
            return;
        }
        if (isGem(hand) || plugin.items().isForged(hand)) {
            Msg.prefix(player, "<red>主手拿的是成品或宝石。</red>");
            return;
        }
        Material input = hand.getType();
        // 宝石唯一来源：下界之星。别的矿一律熔不出宝石
        if (!GemType.isStarInput(input)) {
            Msg.prefix(player, "<red>只有 <#b06bff>下界之星</#b06bff> 能熔出宝石。</red>"
                    + " <gray>其它矿物熔不出宝石，别浪费。");
            return;
        }
        int need = Math.max(1, plugin.settings().intOf("gems.smelt.star-amount", 1));
        if (hand.getAmount() < need) {
            Msg.prefix(player, "<red>星熔需要 <white>" + need + "</white> 个下界之星，你只有 <white>"
                    + hand.getAmount() + "</white> 个。</red>");
            return;
        }
        long cost = plugin.settings().longOf("gems.smelt.star-cost-coins", 300);
        var wallet = plugin.wallet();
        if (cost > 0 && !wallet.take(player, cost, CoinReason.GEM)) {
            Msg.prefix(player, "<red>星熔费不足：需要 <white>" + wallet.format(cost) + "</white>。</red>");
            return;
        }

        hand.setAmount(hand.getAmount() - need);
        player.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);

        List<GemType> pool = GemType.poolOf(input);
        GemType type = pool.get(random.nextInt(pool.size()));
        // 星熔只出 Lv.1，往上全靠三合一
        GemType.Grade grade = GemType.Grade.L1;
        ItemStack gem = createGem(type, grade);

        var leftover = player.getInventory().addItem(gem);
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }

        player.playSound(player.getLocation(), Sound.BLOCK_LAVA_POP, 0.8f, 1.4f);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.8f);
        player.getWorld().spawnParticle(org.bukkit.Particle.LAVA, player.getLocation().add(0, 1, 0),
                12, 0.3, 0.3, 0.3, 0.01);
        player.getWorld().spawnParticle(org.bukkit.Particle.END_ROD, player.getLocation().add(0, 1, 0),
                24, 0.5, 0.5, 0.5, 0.05);
        Msg.prefix(player, "<gray>星熔出 " + grade.colored() + " " + type.colored() + " <gray>· " + type.unit()
                + " <dark_gray>（3 颗同级可合上一级）");
    }

    // ------------------------------------------------------------ 开孔

    /** 主手锻造品开一个孔 */
    public void socket(Player player) {
        if (!enabled()) {
            Msg.prefix(player, "<red>宝石系统已关闭。</red>");
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!plugin.items().isForged(item)) {
            Msg.prefix(player, "<red>主手要拿一件锻造品才能开孔。</red>");
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        int sockets = socketsOf(item);
        int limit = socketLimitOf(item);
        if (limit <= 0) {
            Msg.prefix(player, "<red>查不到这件装备的材料档位，开不了孔。</red>");
            return;
        }
        if (sockets >= limit) {
            Msg.prefix(player, "<red>这件最多 <white>" + limit
                    + "</white> 个孔（按材料档位），已经开满了。</red>");
            return;
        }

        Material costItem = Material.matchMaterial(
                plugin.settings().str("gems.socket.cost-item", "IRON_BLOCK"));
        int costAmount = Math.max(1, plugin.settings().intOf("gems.socket.cost-amount", 2));
        long baseCost = plugin.settings().longOf("gems.socket.cost-coins", 500);
        long price = baseCost * (sockets + 1);

        if (costItem != null) {
            int have = com.forgemaster.material.MaterialGrades.totalCount(player, costItem);
            if (have < costAmount) {
                Msg.prefix(player, "<red>开孔需要 <white>" + costAmount + "</white> 个 "
                        + com.forgemaster.material.MaterialGrades.pretty(costItem)
                        + "，你只有 <white>" + have + "</white> 个。</red>");
                return;
            }
        }
        var wallet = plugin.wallet();
        if (price > 0 && !wallet.take(player, price, CoinReason.GEM)) {
            Msg.prefix(player, "<red>开孔费不足：需要 <white>" + wallet.format(price) + "</white>。</red>");
            return;
        }
        if (costItem != null) {
            com.forgemaster.material.MaterialGrades.consume(player, costItem, costAmount);
        }

        // 孔越多越容易失手；失败只亏材料与费用，不毁物品
        double chance = Math.max(0.35, 1.0 - sockets * 0.15);
        if (random.nextDouble() > chance) {
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.9f, 0.6f);
            Msg.prefix(player, "<red>钻偏了，孔没开成（材料与费用已消耗）。</red> <gray>下次成功率 "
                    + Msg.fmt(chance * 100, 0) + "%");
            return;
        }

        sockets++;
        meta = item.getItemMeta();
        // 先把老物品的附魔孔冻结成实值，再抬宝石孔——
        // 反过来的话回退值会跟着涨，等于白送附魔孔
        freezeEnchantSockets(meta);
        meta.getPersistentDataContainer().set(Keys.SOCKETS, PersistentDataType.INTEGER, sockets);
        meta.lore(gemLore(meta, sockets,
                enchantSocketsOf(item), gemsOf(item), enchantsOf(item)));
        com.forgemaster.util.Attrs.apply(item, meta);
        player.getInventory().setItemInMainHand(item);

        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1.3f);
        Msg.prefix(player, "<green>开孔成功！</green> <gray>宝石孔现在是 <white>" + sockets + "/" + limit
                + "</white>。");
    }

    /**
     * 开附魔孔（v2.10）。
     *
     * <p>和宝石开孔完全独立：自己的上限、自己的费用、自己的成功率计数。
     * 老物品第一次开附魔孔时会先把宝石孔数冻结过来当起点，
     * 所以老玩家不会吃亏，也不会被白送到超过历史水平。</p>
     */
    /**
     * 一次附魔书的镶嵌计划（纯函数，不扣钱、不写物品）。
     *
     * <p>抽出来的原因：这段判定原来埋在 {@code socketEnchant(Player)} 里，
     * 要造玩家才能测——于是「一本多附魔书越限」这种 bug 从来没被自检发现。
     * 现在它是 {@code ItemStack + Map} 进、{@code EnchantPlan} 出，可以直接断言。</p>
     *
     * @param item 已确认是锻造品的目标装备
     * @param book 附魔书的 {@code 附魔→等级} 表
     */
    public EnchantPlan planEnchant(ItemStack item, Map<org.bukkit.enchantments.Enchantment, Integer> book) {
        List<GemEntry> gems = gemsOf(item);
        List<EnchantEntry> enchants = enchantsOf(item);
        int sockets = enchantSocketsOf(item);
        if (sockets <= 0) {
            return new EnchantPlan(List.of(), Map.of(), "这件装备还没有附魔孔，先开孔。");
        }
        List<org.bukkit.enchantments.Enchantment> usable = new ArrayList<>();
        for (var e : book.entrySet()) {
            if (e.getKey().canEnchantItem(item)) {
                usable.add(e.getKey());
            }
        }
        if (usable.isEmpty()) {
            return new EnchantPlan(List.of(), Map.of(), "这本书上的附魔都镶不进这件装备。");
        }
        // 互斥附魔（锋利 vs 亡灵杀手）原版不允许共存。书里同时带两条是改包或别的插件造的，
        // 镶上去会得到一把原版规则下不可能存在的剑——留着当 bug 源头，不如当场拒掉。
        for (int a = 0; a < usable.size(); a++) {
            for (int b = a + 1; b < usable.size(); b++) {
                if (usable.get(a).conflictsWith(usable.get(b))) {
                    return new EnchantPlan(List.of(), Map.of(), "这本书里有互斥的附魔（"
                            + enchantName(usable.get(a)) + " 和 " + enchantName(usable.get(b))
                            + "），镶不进去。");
                }
            }
        }
        List<org.bukkit.enchantments.Enchantment> toAdd = new ArrayList<>();
        Map<org.bukkit.enchantments.Enchantment, Integer> toUpgrade = new LinkedHashMap<>();
        for (var ench : usable) {
            int want = Math.max(1, Math.min(ench.getMaxLevel(), book.getOrDefault(ench, 1)));
            EnchantEntry existing = null;
            for (EnchantEntry e : enchants) {
                if (e.enchantment().equals(ench)) {
                    existing = e;
                    break;
                }
            }
            if (existing == null) {
                for (EnchantEntry e : enchants) {
                    if (e.enchantment().conflictsWith(ench)) {
                        return new EnchantPlan(List.of(), Map.of(), "「" + enchantName(ench)
                                + "」和装备上已有的「" + enchantName(e.enchantment())
                                + "」互斥，镶不进去。");
                    }
                }
                toAdd.add(ench);
            } else if (want > existing.level()) {
                toUpgrade.put(ench, want);
            }
            // 书上级数不高于已有的，当作没变化——绝不能 addEnchant 覆盖成更低等级
        }
        if (toAdd.isEmpty() && toUpgrade.isEmpty()) {
            return new EnchantPlan(List.of(), Map.of(),
                    "书上的附魔都已经镶上了，等级也不比这本低，镶了不会变。");
        }
        // 关键判定：这本书**会新增几条**，不是「整本是不是都已经在上面」。
        // 旧写法用整本判定，一本书 3 条附魔、装备 0 条、只有 1 个孔时会直接放行 → 3/1 越限。
        int remaining = sockets - enchants.size();
        if (toAdd.size() > remaining) {
            return new EnchantPlan(List.of(), Map.of(), "这本书要占 " + toAdd.size()
                    + " 个附魔孔，你还剩 " + Math.max(0, remaining) + " 个（"
                    + enchants.size() + "/" + sockets + "）。先去开孔，或者分几本书少量多次地敲。");
        }
        return new EnchantPlan(toAdd, toUpgrade, null);
    }

    /**
     * 镶嵌计划：新增（占新孔）/ 升级（不占孔）/ 拒绝理由。
     *
     * @param reject 非 null 表示整本拒绝，物品与钱都不该动
     */
    public record EnchantPlan(List<org.bukkit.enchantments.Enchantment> toAdd,
                              Map<org.bukkit.enchantments.Enchantment, Integer> toUpgrade,
                              String reject) {
        public boolean rejected() {
            return reject != null;
        }

        public boolean noChange() {
            return !rejected() && toAdd.isEmpty() && toUpgrade.isEmpty();
        }
    }

    /**
     * 满配用：把附魔孔直接开到该装备的材料档位上限（跳过费用与掷骰）。
     *
     * <p>为什么不复用 {@link #socketEnchant}：那个是逐次开的——要扣书、扣钱、
     * 掷成功率，而且一次只 +1。管理员发满配时这些都不适用。</p>
     *
     * @return 开完之后的附魔孔数
     */
    public int fillMaxEnchantSockets(ItemStack item) {
        if (item == null || !plugin.items().isForged(item)) {
            return 0;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return 0;
        }
        int limit = enchantLimitOf(item);
        if (limit <= 0) {
            return enchantSocketsOf(item);
        }
        // 老物品先把回退值落成真值，否则写进去会被回退逻辑盖掉
        freezeEnchantSockets(meta);
        meta.getPersistentDataContainer().set(Keys.ENCHANT_SOCKETS, PersistentDataType.INTEGER, limit);
        meta.lore(gemLore(meta, socketsOf(item), limit, gemsOf(item), enchantsOf(item)));
        item.setItemMeta(meta);
        com.forgemaster.util.Attrs.apply(item, item.getItemMeta());
        return limit;
    }

    public void socketEnchant(Player player) {
        if (!enabled()) {
            Msg.prefix(player, "<red>宝石系统已关闭。</red>");
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!plugin.items().isForged(item)) {
            Msg.prefix(player, "<red>主手要拿一件锻造品才能开附魔孔。</red>");
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        // 老物品：先把宝石孔数冻结成附魔孔的起点，再往上加
        freezeEnchantSockets(meta);
        com.forgemaster.util.Attrs.apply(item, meta);

        int sockets = enchantSocketsOf(item);
        int limit = enchantLimitOf(item);
        if (limit <= 0) {
            Msg.prefix(player, "<red>查不到这件装备的材料档位，开不了附魔孔。</red>");
            return;
        }
        if (sockets >= limit) {
            Msg.prefix(player, "<red>这件最多 <white>" + limit
                    + "</white> 个附魔孔（按材料档位），已经开满了。</red>");
            return;
        }

        Material costItem = Material.matchMaterial(
                plugin.settings().str("gems.enchant-socket.cost-item", "BOOK"));
        int costAmount = Math.max(1, plugin.settings().intOf("gems.enchant-socket.cost-amount", 8));
        long baseCost = plugin.settings().longOf("gems.enchant-socket.cost-coins", 800);
        long price = baseCost * (sockets + 1);

        if (costItem != null) {
            int have = com.forgemaster.material.MaterialGrades.totalCount(player, costItem);
            if (have < costAmount) {
                Msg.prefix(player, "<red>开附魔孔需要 <white>" + costAmount + "</white> 个 "
                        + com.forgemaster.material.MaterialGrades.pretty(costItem)
                        + "，你只有 <white>" + have + "</white> 个。</red>");
                return;
            }
        }
        var wallet = plugin.wallet();
        if (price > 0 && !wallet.take(player, price, CoinReason.GEM)) {
            Msg.prefix(player, "<red>开附魔孔费不足：需要 <white>" + wallet.format(price) + "</white>。</red>");
            return;
        }
        if (costItem != null) {
            com.forgemaster.material.MaterialGrades.consume(player, costItem, costAmount);
        }

        double chance = Math.max(0.35, 1.0 - sockets * 0.15);
        if (random.nextDouble() > chance) {
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.9f, 0.6f);
            Msg.prefix(player, "<red>钻偏了，附魔孔没开成（材料与费用已消耗）。</red> <gray>下次成功率 "
                    + Msg.fmt(chance * 100, 0) + "%");
            return;
        }

        sockets++;
        meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(Keys.ENCHANT_SOCKETS, PersistentDataType.INTEGER, sockets);
        meta.lore(gemLore(meta, socketsOf(item), sockets, gemsOf(item), enchantsOf(item)));
        com.forgemaster.util.Attrs.apply(item, meta);
        player.getInventory().setItemInMainHand(item);

        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.3f);
        Msg.prefix(player, "<green>开附魔孔成功！</green> <gray>附魔孔现在是 <white>" + sockets + "/" + limit
                + "</white>。");
    }

    // ------------------------------------------------------------ 拆卸

    /** 把装备上最后一颗宝石取下来，有碎裂概率 */
    public void extract(Player player) {
        if (!enabled()) {
            Msg.prefix(player, "<red>宝石系统已关闭。</red>");
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!plugin.items().isForged(item)) {
            Msg.prefix(player, "<red>主手要拿一件锻造品。</red>");
            return;
        }
        List<GemEntry> gems = gemsOf(item);
        if (gems.isEmpty()) {
            Msg.prefix(player, "<red>这件装备上没有宝石。</red>");
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            Msg.prefix(player, "<red>这件物品读不出数据，拆不了。</red>");
            return;
        }
        long cost = plugin.settings().longOf("gems.extract.cost-coins", 300);
        var wallet = plugin.wallet();
        if (cost > 0 && !wallet.take(player, cost, CoinReason.GEM)) {
            Msg.prefix(player, "<red>拆卸费不足：需要 <white>" + wallet.format(cost) + "</white>。</red>");
            return;
        }

        int index = gems.size() - 1;
        GemEntry last = gems.get(index);
        List<GemEntry> before = new ArrayList<>(gems);
        gems.remove(index);
        meta.getPersistentDataContainer().set(Keys.GEMS, PersistentDataType.STRING, encode(gems));
        // 列表整体前移了，属性 modifier 与 PDC 汇总都要按新旧差量重算
        reapplyGemEffects(meta, before, gems);
        // 四参版本：同时重画附魔条目。两参会把附魔 ▪ 行整块清掉且不重画
        meta.lore(gemLore(meta, socketsOf(item), enchantSocketsOf(item), gems, enchantsOf(item)));
        com.forgemaster.util.Attrs.apply(item, meta);
        player.getInventory().setItemInMainHand(item);

        double success = plugin.settings().num("gems.extract.success", 0.8);
        if (random.nextDouble() > success) {
            player.playSound(player.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.9f, 0.8f);
            Msg.prefix(player, "<red>宝石碎了……</red> <gray>孔位空出来了，装备没受损。");
            return;
        }
        ItemStack gem = createGem(last.type(), last.grade(), last.value());
        give(player, gem);
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.8f, 1.2f);
        Msg.prefix(player, "<green>拆下来了：</green>" + last.grade().colored() + " " + last.type().colored());
    }

    // ------------------------------------------------------------ 附魔取回

    /**
     * 把装备上<b>最后一条</b>镶进孔里的附魔取回来，有打散概率。
     *
     * <p>和 {@link #extract(Player)} 对称：宝石能拆，附魔也不该锁死。
     * 失败时附魔没了、孔位照样空出来、装备不受损、不给书——费用照扣，
     * 所以别在垃圾附魔上浪费钱。</p>
     *
     * <p>成功后给一本<b>只带这一条附魔</b>的附魔书，等级原样保留：
     * 主手装备 + 副手这本书走 {@link #inlayEnchant(Player)} 能原样镶回去，
     * PDC 里 {@code ENCHANTS} 的 {@code id:level} 记录往返一致。</p>
     */
    public void extractEnchant(Player player) {
        if (!enabled()) {
            Msg.prefix(player, "<red>宝石系统已关闭。</red>");
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!plugin.items().isForged(item)) {
            Msg.prefix(player, "<red>主手要拿一件锻造品。</red>");
            return;
        }
        List<EnchantEntry> enchants = enchantsOf(item);
        if (enchants.isEmpty()) {
            Msg.prefix(player, "<red>这件装备上没镶附魔，取不出东西。</red>");
            return;
        }
        long cost = plugin.settings().longOf("gems.enchant-extract.cost-coins", 300);
        var wallet = plugin.wallet();
        if (cost > 0 && !wallet.take(player, cost, CoinReason.GEM)) {
            Msg.prefix(player, "<red>取回费不足：需要 <white>" + wallet.format(cost) + "</white>。</red>");
            return;
        }

        int sockets = socketsOf(item);
        List<GemEntry> gems = gemsOf(item);
        int index = enchants.size() - 1;
        EnchantEntry last = enchants.get(index);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            // 读不出 meta 就别白扣钱
            if (cost > 0) {
                wallet.give(player, cost, CoinReason.REFUND);
            }
            Msg.prefix(player, "<red>这件装备读不出来，没动它。</red>");
            return;
        }
        // 附魔与孔位记录一起回退：孔位空出来，物品本身不受损
        meta.removeEnchant(last.enchantment());
        enchants.remove(index);
        meta.getPersistentDataContainer().set(Keys.ENCHANTS, PersistentDataType.STRING,
                encodeEnchants(enchants));
        meta.lore(gemLore(meta, socketsOf(item), sockets, gems, enchants));
        com.forgemaster.util.Attrs.apply(item, meta);
        player.getInventory().setItemInMainHand(item);

        double success = plugin.settings().num("gems.enchant-extract.success", 0.8);
        if (random.nextDouble() > success) {
            player.playSound(player.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.9f, 0.8f);
            Msg.prefix(player, "<red>附魔散了……</red> <gray>孔位空出来了，装备没受损。");
            return;
        }
        give(player, createEnchantBook(last.enchantment(), last.level()));
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.6f);
        Msg.prefix(player, "<green>取回来了：</green><white>" + enchantName(last.enchantment())
                + " " + roman(last.level()) + "</white> <gray>· 附魔书（"
                + (gems.size() + enchants.size()) + "/" + sockets + "）");
    }

    /**
     * 建一本只带一条附魔的附魔书。
     *
     * <p>等级原样写进 stored enchant，{@link #inlayEnchant(Player)} 读的就是这里，
     * 所以「取回 → 再镶回去」的附魔和等级都对得上，不会缩水。</p>
     */
    private ItemStack createEnchantBook(org.bukkit.enchantments.Enchantment ench, int level) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = book.getItemMeta();
        if (!(meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta storage)) {
            return book;
        }
        storage.addStoredEnchant(ench, Math.max(1, level), true);
        storage.displayName(Msg.mm("<light_purple>附魔书</light_purple> <dark_gray>·</dark_gray> <white>"
                        + enchantName(ench) + " " + roman(level))
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(line("<gray>用法: <white>主手装备、副手附魔书</white> 后"));
        lore.add(line("<white>/zhujian enchant</white> <gray>镶回镶嵌孔"));
        lore.add(line("<dark_gray>镶进去会占一个镶嵌孔"));
        storage.lore(lore);

        com.forgemaster.util.Attrs.apply(book, storage);
        return book;
    }

    // ------------------------------------------------------------ 合成

    /** 主手 + 副手同类型同档宝石，合成高一档 */
    public void merge(Player player) {
        if (!enabled()) {
            Msg.prefix(player, "<red>宝石系统已关闭。</red>");
            return;
        }
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        if (!isGem(main) || !isGem(off)) {
            Msg.prefix(player, "<red>主手和副手都要拿宝石，第三颗会从背包自动找。</red>");
            return;
        }
        ItemMeta mainMeta = main.getItemMeta();
        ItemMeta offMeta = off.getItemMeta();
        if (mainMeta == null || offMeta == null) {
            return;
        }
        GemType typeA = GemType.byId(mainMeta.getPersistentDataContainer()
                .get(Keys.GEM_TYPE, PersistentDataType.STRING));
        GemType typeB = GemType.byId(offMeta.getPersistentDataContainer()
                .get(Keys.GEM_TYPE, PersistentDataType.STRING));
        GemType.Grade gradeA = GemType.Grade.byName(mainMeta.getPersistentDataContainer()
                .get(Keys.GEM_GRADE, PersistentDataType.STRING));
        GemType.Grade gradeB = GemType.Grade.byName(offMeta.getPersistentDataContainer()
                .get(Keys.GEM_GRADE, PersistentDataType.STRING));

        if (typeA == null || typeB == null || typeA != typeB) {
            Msg.prefix(player, "<red>两颗宝石的种类要一样。</red>");
            return;
        }
        if (gradeA != gradeB) {
            Msg.prefix(player, "<red>两颗宝石的等级要一样（" + gradeA.display() + " + " + gradeB.display() + "）。</red>");
            return;
        }
        GemType.Grade next = gradeA.next();
        if (next == null) {
            Msg.prefix(player, "<red>" + gradeA.display() + " 已经是最高级（Lv.5），合不上去了。</red>");
            return;
        }
        // 三合一：主手 + 副手 + 背包里再来一颗同名同档
        int thirdSlot = findSameGemSlot(player, typeA, gradeA);
        if (thirdSlot < 0) {
            Msg.prefix(player, "<red>三合一需要 3 颗</red> <gray>· 背包里还差一颗 "
                    + gradeA.colored() + " " + typeA.colored());
            return;
        }

        long cost = plugin.settings().longOf("gems.merge.cost-coins", 200);
        var wallet = plugin.wallet();
        if (cost > 0 && !wallet.take(player, cost, CoinReason.GEM)) {
            Msg.prefix(player, "<red>合成费不足：需要 <white>" + wallet.format(cost) + "</white>。</red>");
            return;
        }

        double chance = plugin.settings().num(
                "gems.merge.chance-" + (gradeA.ordinal() + 1), 0.5);

        main.setAmount(main.getAmount() - 1);
        off.setAmount(off.getAmount() - 1);
        player.getInventory().setItemInMainHand(main.getAmount() <= 0 ? null : main);
        player.getInventory().setItemInOffHand(off.getAmount() <= 0 ? null : off);
        ItemStack third = player.getInventory().getItem(thirdSlot);
        if (third != null) {
            third.setAmount(third.getAmount() - 1);
            if (third.getAmount() <= 0) {
                player.getInventory().setItem(thirdSlot, null);
            }
        }

        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.6f);
        com.forgemaster.util.Particles.spawn(org.bukkit.Particle.ENCHANT,
                player.getLocation().add(0, 1, 0), 20, 0.4, 0.4, 0.4, 0.4);

        if (random.nextDouble() > chance) {
            // 失败保留一颗原档，不至于三颗全没
            give(player, createGem(typeA, gradeA));
            player.playSound(player.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.8f, 0.9f);
            Msg.prefix(player, "<red>合成失败</red> <gray>· 保住一颗 " + gradeA.colored()
                    + " <gray>（成功率 " + Msg.fmt(chance * 100, 0) + "%）");
            return;
        }

        ItemStack merged = createGem(typeA, next);
        give(player, merged);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.9f);
        Msg.prefix(player, "<green>合成成功！</green>" + gradeA.colored() + " ×3 → "
                + next.colored() + " " + typeA.colored());
    }

    /** 在背包里找一颗同名同档宝石（跳过主手槽），返回槽位，找不到 -1 */
    private int findSameGemSlot(Player player, GemType type, GemType.Grade grade) {
        var inv = player.getInventory();
        int held = inv.getHeldItemSlot();
        for (int i = 0; i < 36; i++) {
            if (i == held) {
                continue;
            }
            ItemStack it = inv.getItem(i);
            if (!isGem(it)) {
                continue;
            }
            ItemMeta meta = it.getItemMeta();
            if (meta == null) {
                continue;
            }
            String t = meta.getPersistentDataContainer().get(Keys.GEM_TYPE, PersistentDataType.STRING);
            String g = meta.getPersistentDataContainer().get(Keys.GEM_GRADE, PersistentDataType.STRING);
            if (type.id().equalsIgnoreCase(t) && grade.name().equalsIgnoreCase(g)) {
                return i;
            }
        }
        return -1;
    }

    /** 背包塞不下就丢地上 */
    private void give(Player player, ItemStack item) {
        for (ItemStack rest : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    // ------------------------------------------------------------ 宝石剥离

    /** 把一颗宝石从物品上摘掉（属性 / 孔位数据都要回退） */

    // ------------------------------------------------------------ 镶嵌

    /** 主手装备 + 副手宝石 */
    public void inlay(Player player) {
        if (!enabled()) {
            Msg.prefix(player, "<red>宝石系统已关闭。</red>");
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        if (!plugin.items().isForged(item)) {
            Msg.prefix(player, "<red>主手要拿锻造品。</red>");
            return;
        }
        if (!isGem(off)) {
            Msg.prefix(player, "<red>副手要拿一颗宝石。</red>");
            return;
        }
        int sockets = gemSocketsOf(item);
        List<GemEntry> gems = gemsOf(item);
        List<EnchantEntry> enchants = enchantsOf(item);
        if (sockets <= 0) {
            Msg.prefix(player, "<red>这件装备还没有宝石孔，先 <white>/zhujian gem socket</white> 开孔。</red>");
            return;
        }
        if (gems.size() >= sockets) {
            Msg.prefix(player, "<red>宝石孔已经满了（" + gems.size() + "/" + sockets
                    + "），没法再镶。</red> <gray>附魔孔是另一套，用 <white>/zhujian enchant</white>。");
            return;
        }

        // 先验宝石数据再扣钱：副手 PDC 损坏时不能白扣镶嵌费
        ItemMeta gemMeta = off.getItemMeta();
        GemType type = GemType.byId(gemMeta.getPersistentDataContainer()
                .get(Keys.GEM_TYPE, PersistentDataType.STRING));
        Double value = gemMeta.getPersistentDataContainer().get(Keys.GEM_VALUE, PersistentDataType.DOUBLE);
        GemType.Grade grade = GemType.Grade.byName(gemMeta.getPersistentDataContainer()
                .get(Keys.GEM_GRADE, PersistentDataType.STRING));
        if (type == null || value == null) {
            Msg.prefix(player, "<red>这颗宝石数据损坏了。</red>");
            return;
        }

        long cost = plugin.settings().longOf("gems.inlay.cost-coins", 200);
        var wallet = plugin.wallet();
        if (cost > 0 && !wallet.take(player, cost, CoinReason.GEM)) {
            Msg.prefix(player, "<red>镶嵌费不足：需要 <white>" + wallet.format(cost) + "</white>。</red>");
            return;
        }

        int index = gems.size();
        ItemMeta meta = item.getItemMeta();
        List<GemEntry> before = new ArrayList<>(gems);
        gems.add(new GemEntry(type, value, grade));
        meta.getPersistentDataContainer().set(Keys.GEMS, PersistentDataType.STRING, encode(gems));
        meta.getPersistentDataContainer().set(Keys.SOCKETS, PersistentDataType.INTEGER, sockets);
        // 属性 modifier 按下标挂载，列表变了必须整体重刷；
        // PDC 那几类（暴击 / 暴伤 / 吸血 / 点燃）按新旧差量补，别整体覆盖掉词条与剑灵的部分
        reapplyGemEffects(meta, before, gems);
        meta.lore(gemLore(meta, sockets, enchantSocketsOf(item), gems, enchantsOf(item)));
        com.forgemaster.util.Attrs.apply(item, meta);
        player.getInventory().setItemInMainHand(item);

        off.setAmount(off.getAmount() - 1);
        player.getInventory().setItemInOffHand(off.getAmount() <= 0 ? null : off);

        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.4f);
        Msg.prefix(player, "<green>镶嵌成功：</green>" + grade.colored() + " " + type.colored()
                + " <gray>· " + type.unit() + " <gray>（" + gems.size() + "/" + sockets + "）");
    }

    /**
     * 按新的宝石列表重刷这件物品的<b>全部</b>宝石效果。
     *
     * <p>两类效果走的是完全不同的路，必须分开处理：</p>
     * <ul>
     *   <li><b>走原版属性的</b>（攻击 / 护甲 / 韧性 / 生命 / 移速 / 击退抗性 / 攻速）：
     *       modifier 键是 {@code fm_gem_<列表下标>}，列表一变就整组摘掉重挂。
     *       只摘一条会在「宝石串里有坏数据被跳过」时摘错，或者留下永久残留。</li>
     *   <li><b>走 PDC 汇总的</b>（暴击 / 暴伤 / 吸血 / 点燃）：PDC 里同时还躺着
     *       词条和剑灵加的部分，整体覆盖会把它们抹掉，所以按「新总和 − 旧总和」补差量。</li>
     * </ul>
     *
     * <p><b>2.10.6 修的真 bug</b>：这条路径以前<b>只</b>重挂属性宝石，PDC 那四类从来没写过。
     * 于是 Lore 上宝石明明白白写着「暴击率 +45%」，判定读的 {@code crit_chance} 里却只有词条那点，
     * 玩家把暴击堆到 100% 以上照样不暴击——显示和实际是两套账。</p>
     *
     * <p>老物品（升级前镶的）PDC 里没有宝石那部分，用 {@link Keys#GEM_EFFECTS} 标记判定：
     * 没标记就当作「旧贡献为 0」，把当前整份宝石补进去，然后打标记，不会重复叠加。</p>
     *
     * @param before 改动前的宝石列表（老物品会被忽略，见上面的标记说明）
     * @param after  改动后的宝石列表
     */
    private void reapplyGemEffects(ItemMeta meta, List<GemEntry> before, List<GemEntry> after) {
        List<GemEntry> now = after == null ? List.of() : after;

        // ---- 1) 原版属性：先全摘，再按新下标重挂 ----
        Set<Attribute> attrs = new LinkedHashSet<>();
        for (GemType t : GemType.values()) {
            if (t.attribute() != null) {
                attrs.add(t.attribute());
            }
        }
        for (Attribute attr : attrs) {
            var mods = meta.getAttributeModifiers(attr);
            if (mods == null) {
                continue;
            }
            for (AttributeModifier m : List.copyOf(mods)) {
                if (m.getKey() != null && m.getKey().getKey().startsWith("fm_gem_")) {
                    meta.removeAttributeModifier(attr, m);
                }
            }
        }
        for (int i = 0; i < now.size(); i++) {
            GemEntry g = now.get(i);
            if (g.type().attribute() != null) {
                applyGem(meta, g.type(), g.value(), i);
            }
        }

        // ---- 2) PDC 汇总：按差量补 ----
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        boolean counted = pdc.has(Keys.GEM_EFFECTS, PersistentDataType.BYTE);
        List<GemEntry> old = counted && before != null ? before : List.of();
        bumpPdc(pdc, Keys.CRIT_CHANCE, sumOf(old, GemType.CRIT), sumOf(now, GemType.CRIT));
        bumpPdc(pdc, Keys.CRIT_DAMAGE, sumOf(old, GemType.CRIT_DAMAGE), sumOf(now, GemType.CRIT_DAMAGE));
        bumpPdc(pdc, Keys.LIFESTEAL, sumOf(old, GemType.LIFESTEAL), sumOf(now, GemType.LIFESTEAL));
        bumpFireTicks(pdc, sumOf(old, GemType.FIRE), sumOf(now, GemType.FIRE));
        pdc.set(Keys.GEM_EFFECTS, PersistentDataType.BYTE, (byte) 1);
    }

    /** 某类宝石的数值总和（宝石本身已经是放大后的最终值） */
    private static double sumOf(List<GemEntry> gems, GemType type) {
        double sum = 0;
        for (GemEntry g : gems) {
            if (g.type() == type) {
                sum += g.value();
            }
        }
        return sum;
    }

    /** PDC 里的暴击 / 暴伤 / 吸血：在现有值上加减差量，永不为负 */
    private static void bumpPdc(PersistentDataContainer pdc, NamespacedKey key, double from, double to) {
        double delta = to - from;
        if (Math.abs(delta) < 1e-9) {
            return;
        }
        double current = pdc.getOrDefault(key, PersistentDataType.DOUBLE, 0.0);
        double value = Math.max(0, current + delta);
        pdc.set(key, PersistentDataType.DOUBLE, Math.round(value * 100.0) / 100.0);
    }

    /** 点燃按 tick 记，差量取整后补 */
    private static void bumpFireTicks(PersistentDataContainer pdc, double from, double to) {
        long delta = Math.round((to - from) * 20);
        if (delta == 0) {
            return;
        }
        int current = pdc.getOrDefault(Keys.FIRE_TICKS, PersistentDataType.INTEGER, 0);
        pdc.set(Keys.FIRE_TICKS, PersistentDataType.INTEGER, (int) Math.max(0, current + delta));
    }

    /**
     * 自检入口：直接对一件物品按「旧列表 → 新列表」重刷宝石效果。
     *
     * <p>生产路径是 {@link #inlay} / {@link #extract} 内部调用，没有玩家对象时走这条，
     * 好在自检里单独钉住「宝石数值必须真的进 PDC」这件事。</p>
     */
    public void reapplyGemEffectsForTest(ItemStack item, List<GemEntry> before, List<GemEntry> after) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        reapplyGemEffects(meta, before, after);
        com.forgemaster.util.Attrs.apply(item, meta);
    }

    /**
     * 给升级前镶过宝石的老物品补一次 PDC 效果（登录 / 重生 / 点背包时由维修入口调用）。
     *
     * @return 是否真的改了东西
     */
    public boolean repairGemEffects(ItemStack item) {
        if (item == null || item.getType().isAir() || !plugin.items().isForged(item)) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null
                || meta.getPersistentDataContainer().has(Keys.GEM_EFFECTS, PersistentDataType.BYTE)) {
            return false;
        }
        List<GemEntry> gems = gemsOf(item);
        reapplyGemEffects(meta, List.of(), gems);
        com.forgemaster.util.Attrs.apply(item, meta);
        return !gems.isEmpty();
    }

    /** 把一颗宝石写进物品属性 */
    private void applyGem(ItemMeta meta, GemType type, double value, int index) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (type.attribute() != null) {
            EquipmentSlotGroup group = slotGroupOf(meta);
            double applied = plugin.compat() == null
                    ? value
                    : plugin.compat().cap(type.attribute(), value);
            meta.addAttributeModifier(type.attribute(), new AttributeModifier(
                    new NamespacedKey(plugin, "fm_gem_" + index),
                    applied,
                    AttributeModifier.Operation.ADD_NUMBER,
                    group));
            return;
        }
        // 暴击 / 暴伤 / 吸血 / 点燃不在这里写：它们走 PDC 汇总，
// 由 reapplyGemEffects 按新旧差量统一补，避免和这里的逐条累加撞成双份。
    }

    /** 装备按部位槽，武器按主手 */
    private EquipmentSlotGroup slotGroupOf(ItemMeta meta) {
        String raw = meta.getPersistentDataContainer().get(Keys.ARMOR_SLOT, PersistentDataType.STRING);
        var slot = com.forgemaster.material.ForgeMaterial.Slot.byName(raw);
        return slot == null ? EquipmentSlotGroup.MAINHAND : slot.group();
    }

    // ------------------------------------------------------------ 附魔镶孔

    /**
     * 主手锻造品 + 副手附魔书 → 占一个孔，把书上的附魔镶进去。
     *
     * <p>附魔与宝石<b>共用同一组孔位</b>：铁 / 金 / 铜只有 1 孔，
     * 钻石 / 下界合金 2 孔，星陨 3 孔。镶宝石还是镶附魔，自己权衡。</p>
     *
     * <p>同一附魔已经在孔里时，高等级会覆盖低等级，不额外占孔。</p>
     */
    public void inlayEnchant(Player player) {
        if (!enabled()) {
            Msg.prefix(player, "<red>宝石系统已关闭。</red>");
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        if (!plugin.items().isForged(item)) {
            Msg.prefix(player, "<red>主手要拿锻造品。</red>");
            return;
        }
        if (off == null || off.getType() != Material.ENCHANTED_BOOK) {
            Msg.prefix(player, "<red>副手要拿一本附魔书。</red>");
            return;
        }
        ItemMeta offMeta = off.getItemMeta();
        if (!(offMeta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta storage)) {
            Msg.prefix(player, "<red>这本附魔书读不出附魔。</red>");
            return;
        }
        var stored = storage.getStoredEnchants();
        if (stored.isEmpty()) {
            Msg.prefix(player, "<red>这本附魔书是空的。</red>");
            return;
        }
        // 判定全部收敛到 planEnchant（纯函数、可自检）。这里只负责提示与执行。
        EnchantPlan plan = planEnchant(item, stored);
        if (plan.rejected()) {
            Msg.prefix(player, "<red>" + plan.reject() + "</red>");
            return;
        }
        int sockets = enchantSocketsOf(item);
        List<GemEntry> gems = gemsOf(item);
        List<EnchantEntry> enchants = enchantsOf(item);

        long cost = plugin.settings().longOf("gems.enchant.cost-coins", 300);
        var wallet = plugin.wallet();
        if (cost > 0 && !wallet.take(player, cost, CoinReason.GEM)) {
            Msg.prefix(player, "<red>附魔镶孔费不足：需要 <white>" + wallet.format(cost) + "</white>。</red>");
            return;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            // 扣完钱才读 meta，读不出必须原路退回——否则玩家白付一次镶孔费。
            // 同流程的「取回」分支早就有 REFUND 兜底，这里以前漏了（v2.20 补）。
            if (cost > 0) {
                wallet.give(player, cost, CoinReason.REFUND);
            }
            Msg.prefix(player, "<red>这件物品读不出数据，镶孔费已退回。</red>");
            return;
        }

        StringBuilder added = new StringBuilder();
        for (var ench : plan.toAdd()) {
            int level = Math.max(1, Math.min(ench.getMaxLevel(), stored.getOrDefault(ench, 1)));
            meta.addEnchant(ench, level, true);
            enchants.add(new EnchantEntry(ench, level));
            if (added.length() > 0) {
                added.append("<gray>、");
            }
            added.append("<white>").append(enchantName(ench)).append(" ")
                    .append(roman(level)).append("</white>");
        }
        for (var entry : plan.toUpgrade().entrySet()) {
            var ench = entry.getKey();
            int level = entry.getValue();
            meta.addEnchant(ench, level, true);
            for (int i = 0; i < enchants.size(); i++) {
                if (enchants.get(i).enchantment().equals(ench)) {
                    enchants.set(i, new EnchantEntry(ench, level));
                    break;
                }
            }
            if (added.length() > 0) {
                added.append("<gray>、");
            }
            added.append("<white>").append(enchantName(ench)).append(" ")
                    .append(roman(level)).append("<gray>(升级)</white>");
        }
        meta.getPersistentDataContainer().set(Keys.ENCHANTS, PersistentDataType.STRING,
                encodeEnchants(enchants));
        // 附魔走自己的孔，别去动宝石孔那边的键
        meta.getPersistentDataContainer().set(Keys.ENCHANT_SOCKETS, PersistentDataType.INTEGER, sockets);
        meta.lore(gemLore(meta, socketsOf(item), sockets, gems, enchants));
        com.forgemaster.util.Attrs.apply(item, meta);
        player.getInventory().setItemInMainHand(item);

        off.setAmount(off.getAmount() - 1);
        player.getInventory().setItemInOffHand(off.getAmount() <= 0 ? null : off);

        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.1f);
        // 分母只算附魔孔：以前写的是 gems.size() + enchants.size()，把宝石算进了附魔进度，
        // 看起来像「镶了宝石就快满孔了」，纯误导
        Msg.prefix(player, "<green>附魔镶孔成功：</green>" + added
                + " <gray>（附魔孔 " + enchants.size() + "/" + sockets + "）");
    }

    // ------------------------------------------------------------ Lore

    /** 重建镶嵌区块：孔数 + 已镶宝石 + 已镶附魔 */
    /**
     * 按装备类型给出宝石优先级顺序。
     *
     * <p>为什么不能直接用枚举顺序：枚举里 {@code ATTACK} 排第一，护甲拿到的是赤玉
     * （攻击力）——穿四件玄铁石之外的攻击宝石，等于给盾镶了一件刀。
     * 护甲优先要的是护甲值 / 韧性 / 生命上限 / 击退抗性。</p>
     *
     * <p>属性类宝石按 ATTRIBUTE 走，所以顺序只影响「先占哪个孔」，不影响数值上限。</p>
     */
    private List<GemType> preferredGemOrder(ItemStack item) {
        String n = item.getType().name();
        boolean armor = n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE")
                || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                || n.equals("ELYTRA") || n.equals("SHIELD") || n.equals("TURTLE_HELMET");
        List<GemType> order = new ArrayList<>();
        if (armor) {
            order.add(GemType.ARMOR);
            order.add(GemType.TOUGHNESS);
            order.add(GemType.LIFE);
            order.add(GemType.KNOCKBACK_RESIST);
            order.add(GemType.SPEED);
            order.add(GemType.LIFESTEAL);
        } else {
            order.add(GemType.ATTACK);
            order.add(GemType.CRIT);
            order.add(GemType.CRIT_DAMAGE);
            order.add(GemType.ATTACK_SPEED);
            order.add(GemType.FIRE);
            order.add(GemType.LIFESTEAL);
        }
        // 其余类型排在后面，保证孔多于上面 6 种时不会重复不该重复的
        for (GemType type : GemType.values()) {
            if (!order.contains(type)) {
                order.add(type);
            }
        }
        return order;
    }

    /**
     * 满配用：直接把宝石孔开到 {@code sockets} 个、每孔塞一颗 {@code grade} 档的宝石，
     * 并重建 lore。<b>不</b>扣钱、不掷成功率、不看玩家。
     *
     * <p>为什么需要它：{@link #socket} / {@link #inlay} 都是面向玩家的——要扣费、
     * 要掷命中、要从主手取物品。管理员想发一把满配剑时这些都不适用；而照着那些方法
     * 把 PDC 再写一遍等于把编码格式抄第二份，格式一改就两边不一致。
     * 所以「写进 PDC + 重画 lore」收敛到这里一处。</p>
     *
     * <p>宝石类型按枚举顺序取前 N 种（攻击 / 暴击 / 暴伤 / 点燃 …），数值取该类型上限
     * 乘等级倍率——和真实熔炼出来的同级宝石同口径，不是随手填的数。</p>
     */
    public ItemStack fillMax(ItemStack item, int sockets, GemType.Grade grade) {
        if (item == null || item.getType().isAir()) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        int slots = Math.max(0, Math.min(socketLimitOf(item), sockets));
        GemType.Grade g = grade == null ? GemType.Grade.L5 : grade;

        List<GemEntry> gems = new ArrayList<>();
        List<GemType> order = preferredGemOrder(item);
        for (int i = 0; i < slots; i++) {
            GemType type = order.get(i % order.size());
            gems.add(new GemEntry(type, type.maxAt(g), g));
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        // 先冻结附魔孔成实值，再抬宝石孔——顺序反了回退值会跟着涨（和 socket() 同一个坑）
        freezeEnchantSockets(meta);
        pdc.set(Keys.SOCKETS, PersistentDataType.INTEGER, slots);
        pdc.set(Keys.GEMS, PersistentDataType.STRING, encode(gems));
        meta.lore(gemLore(meta, slots, enchantSocketsOf(item), gems, enchantsOf(item)));
        item.setItemMeta(meta);
        com.forgemaster.util.Attrs.apply(item, item.getItemMeta());
        return item;
    }

    private List<Component> gemLore(ItemMeta meta, int sockets, List<GemEntry> gems) {
        return gemLore(meta, sockets, sockets, gems, List.of());
    }

    private List<Component> gemLore(ItemMeta meta, int gemSockets, int enchSockets,
                                    List<GemEntry> gems, List<EnchantEntry> enchants) {
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        return rebuildGemBlock(lore, gemSockets, enchSockets,
                gems == null ? List.of() : gems,
                enchants == null ? List.of() : enchants);
    }

    /** 兼容旧签名：只画宝石 */
    public static List<Component> rebuildGemBlock(List<Component> input, int sockets,
                                                  List<GemEntry> gems) {
        return rebuildGemBlock(input, sockets, sockets, gems, List.of());
    }

    /**
     * 兼容 2.9 及更早的四参签名：附魔孔按宝石孔数算（老物品的回退语义）。
     */
    public static List<Component> rebuildGemBlock(List<Component> input, int sockets,
                                                  List<GemEntry> gems, List<EnchantEntry> enchants) {
        return rebuildGemBlock(input, sockets, sockets, gems, enchants);
    }

    /**
     * 清掉旧的镶嵌块（含「（空孔 …）」提示），再把新块放回原来的位置。
     *
     * <p>旧版本的标题是「◆ 宝石孔」「◆ 镶嵌孔」，新版本是「◆ 镶嵌孔」，三种都认，
     * 所以升级后老物品第一次镶嵌就会自动换成新标题，不会留两套块。</p>
     *
     * <p>v2.10 起标题里的计数拆成「宝石 x/y · 附魔 a/b」两段——
     * 宝石和附魔各用各的孔池，一个满了不影响另一个。</p>
     *
     * <p>纯函数，方便自检直接验证排版结果。</p>
     */
    public static List<Component> rebuildGemBlock(List<Component> input, int gemSockets, int enchSockets,
                                                  List<GemEntry> gems, List<EnchantEntry> enchants) {
        List<Component> lore = new ArrayList<>(input);

        int insertAt = -1;
        while (true) {
            int start = -1;
            for (int i = 0; i < lore.size(); i++) {
                if (isSocketTitle(Msg.plain(lore.get(i)).trim())) {
                    start = i;
                    break;
                }
            }
            if (start < 0) {
                break;
            }
            if (insertAt < 0) {
                insertAt = start;
            }
            int end = start;
            while (end < lore.size()) {
                String p = Msg.plain(lore.get(end)).trim();
                // 块内的行：标题 / 条目（▪）/ 空孔提示（（）
                if (isSocketTitle(p) || p.startsWith("▪") || p.startsWith("（")) {
                    end++;
                } else {
                    break;
                }
            }
            lore.subList(start, end).clear();
        }

        if (gemSockets <= 0 && enchSockets <= 0) {
            return lore;
        }

        List<Component> block = new ArrayList<>();
        block.add(line("<gold>◆ 镶嵌孔 <gray>[宝石 " + gems.size() + "/" + gemSockets
                + " · 附魔 " + enchants.size() + "/" + enchSockets + "]</gold>"));
        if (gems.isEmpty() && enchants.isEmpty()) {
            block.add(line("<dark_gray>（空 · 副手拿宝石或附魔书镶嵌，两套孔互不占）</dark_gray>"));
        } else {
            for (GemEntry e : gems) {
                block.add(line(e.type().format(e.value())));
            }
            for (EnchantEntry e : enchants) {
                block.add(line(enchantLine(e)));
            }
        }

        // 放回原来的位置；之前没有宝石块就插在「锻造者」之前
        if (insertAt < 0 || insertAt > lore.size()) {
            insertAt = lore.size();
            for (int i = 0; i < lore.size(); i++) {
                if (Msg.plain(lore.get(i)).startsWith("锻造者")) {
                    insertAt = i;
                    break;
                }
            }
        }
        lore.addAll(insertAt, block);
        return lore;
    }

    /** 是否是镶嵌块标题（新老标题都认） */
    private static boolean isSocketTitle(String plain) {
        return plain.startsWith("◆ 宝石孔") || plain.startsWith("◆ 镶嵌孔");
    }

    /** 一条附魔在 Lore 里的显示 */
    private static String enchantLine(EnchantEntry e) {
        return "<gray>▪ <white>" + enchantName(e.enchantment())
                + " <aqua>" + roman(e.level()) + "</aqua>";
    }

    /** 附魔中文名；没收录的退回原版键名 */
    public static String enchantName(org.bukkit.enchantments.Enchantment ench) {
        if (ench == null) {
            return "未知附魔";
        }
        String key = ench.getKey().getKey();
        String hit = ENCHANT_CN.get(key);
        return hit == null ? key : hit;
    }

    /** 罗马数字；超出 10 直接阿拉伯数字 */
    public static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> String.valueOf(n);
        };
    }

    /** 附魔中文名表（原版 key → 中文） */
    private static final java.util.Map<String, String> ENCHANT_CN;

    static {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        m.put("sharpness", "锋利");
        m.put("smite", "亡灵杀手");
        m.put("bane_of_arthropods", "节肢杀手");
        m.put("knockback", "击退");
        m.put("fire_aspect", "火焰附加");
        m.put("looting", "抢夺");
        m.put("sweeping_edge", "横扫之刃");
        m.put("unbreaking", "耐久");
        m.put("mending", "经验修补");
        m.put("vanishing_curse", "绑定诅咒");
        m.put("binding_curse", "消失诅咒");
        m.put("protection", "保护");
        m.put("fire_protection", "火焰保护");
        m.put("feather_falling", "摔落保护");
        m.put("blast_protection", "爆炸保护");
        m.put("projectile_protection", "弹射物保护");
        m.put("respiration", "水下呼吸");
        m.put("aqua_affinity", "水下速掘");
        m.put("thorns", "荆棘");
        m.put("depth_strider", "深海探索者");
        m.put("frost_walker", "冰霜行者");
        m.put("soul_speed", "灵魂疾行");
        m.put("swift_sneak", "迅捷潜行");
        m.put("efficiency", "效率");
        m.put("silk_touch", "精准采集");
        m.put("fortune", "时运");
        m.put("power", "力量");
        m.put("punch", "冲击");
        m.put("flame", "火矢");
        m.put("infinity", "无限");
        m.put("luck_of_the_sea", "海之眷顾");
        m.put("lure", "饵钓");
        m.put("loyalty", "忠诚");
        m.put("impaling", "穿刺");
        m.put("riptide", "激流");
        m.put("channeling", "引雷");
        m.put("multishot", "多重射击");
        m.put("quick_charge", "快速装填");
        m.put("piercing", "穿透");
        m.put("density", "密度");
        m.put("breach", "破甲");
        m.put("wind_burst", "风爆");
        ENCHANT_CN = java.util.Collections.unmodifiableMap(m);
    }

    private static String encodeEnchants(List<EnchantEntry> enchants) {
        StringBuilder sb = new StringBuilder();
        for (EnchantEntry e : enchants) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(e.encode());
        }
        return sb.toString();
    }

    private static String encode(List<GemEntry> gems) {
        StringBuilder sb = new StringBuilder();
        for (GemEntry e : gems) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(e.encode());
        }
        return sb.toString();
    }

    private Quality qualityOf(ItemMeta meta) {
        // 解析统一走 util/MaterialLookup（audit 04 D5）；语义与旧实现完全一致
        return com.forgemaster.util.MaterialLookup.qualityOf(meta);
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("gems.enabled", true);
    }

    private static Component line(String mini) {
        return Msg.mm(mini).decoration(TextDecoration.ITALIC, false);
    }
}
