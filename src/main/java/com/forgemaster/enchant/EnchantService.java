package com.forgemaster.enchant;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.econ.CoinReason;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 附魔台的业务逻辑（v2.23.2）。
 *
 * <h2>两条通路</h2>
 * <ol>
 *   <li><b>台子产书</b>：书 + 矿 → 更好的附魔书（见 {@link #apply}）。台子只产书，不直接上身。</li>
 *   <li><b>书附上装</b>：手持附魔书右键 → 附到主手装备（见 {@link #applyBook}）。</li>
 * </ol>
 *
 * <p>为什么拆成两步而不是「台子里放装备直接附」：一是布局里没有装备格（服主要的是
 * 书/矿/成品/按钮那套），二是右键上书符合 MC 玩家十几年的肌肉记忆，
 * 三是这样「产出」和「消耗」两个动作分开，出错时（比如附错装备）还能靠不右键来止损。</p>
 *
 * <h2>消耗与失败</h2>
 * 升级有失败率，但<b>失败只损耗矿石和剑币，书不消失</b>。
 * 反过来的设计（失败爆书）在生存服里会直接劝退玩家 —— 一本书要挖十几组矿才换得来。
 */
public final class EnchantService {

    /** 允许当催化剂的矿，以及它的加成倍率 */
    private static final Map<Material, Double> ORES = new LinkedHashMap<>();
    /** 可以当「空白书」的原料 */
    private static final List<Material> BLANK_BOOKS = List.of(Material.BOOK, Material.ENCHANTED_BOOK);

    static {
        ORES.put(Material.LAPIS_LAZULI, 1.00);
        ORES.put(Material.AMETHYST_SHARD, 1.05);
        ORES.put(Material.EMERALD, 1.08);
        ORES.put(Material.DIAMOND, 1.12);
        ORES.put(Material.ECHO_SHARD, 1.18);
        ORES.put(Material.NETHERITE_SCRAP, 1.25);
    }

    /**
     * 升级成功率表：下标 = 目标等级。
     *
     * <p>只到 8 级，9 级以上按最后一项（0.30）走 —— 服主把某条附魔的 max-level 调到 10
     * 也不会出现「0% 永远上不去」的死值。</p>
     */
    private static final double[] UPGRADE_CHANCE = {
            1.00, // 0（占位）
            1.00, // 1
            1.00, // 2：第一次升级必定成功，给玩家一个正反馈
            0.85, // 3
            0.70, // 4
            0.55, // 5
            0.45, // 6
            0.40, // 7
            0.35  // 8
    };

    private final ForgingMasterPlugin plugin;

    public EnchantService(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ 数值（单一来源）

    /** 一次附魔的费用与消耗。全部从 config 读，默认值只在这里写一份。 */
    public record Cost(int oreCost, long coinCost, double chance) {
    }

    private long baseCoin() {
        return Math.max(0, plugin.getConfig().getLong("enchant.cost.base-coin", 40L));
    }

    private long coinPerLevel() {
        return Math.max(0, plugin.getConfig().getLong("enchant.cost.coin-per-level", 60L));
    }

    private int orePerLevel() {
        return Math.max(1, plugin.getConfig().getInt("enchant.cost.ore-per-level", 4));
    }

    public int maxPerItem() {
        return Math.max(1, plugin.getConfig().getInt("enchant.max-per-item", 4));
    }

    /** 升级到 {@code targetLevel} 的花费与成功率 */
    public Cost upgradeCost(int targetLevel, Material ore) {
        double mul = ORES.getOrDefault(ore, 1.0);
        int oreCost = orePerLevel() * (1 << Math.max(0, Math.min(targetLevel - 2, 5)));
        long coin = baseCoin() + coinPerLevel() * Math.max(1, targetLevel - 1);
        double base = targetLevel - 1 < UPGRADE_CHANCE.length
                ? UPGRADE_CHANCE[Math.max(0, targetLevel - 1)]
                : UPGRADE_CHANCE[UPGRADE_CHANCE.length - 1];
        // 基础 100% 的档位不打折（* 倍率后仍按 100%），其余档位可以靠好矿抬上去
        double chance = base >= 1.0 ? 1.0 : Math.min(1.0, base * mul);
        return new Cost(oreCost, coin, chance);
    }

    /** 空白书生成一条新附魔的花费 */
    public Cost generateCost(Material ore) {
        double mul = ORES.getOrDefault(ore, 1.0);
        return new Cost(orePerLevel(), baseCoin(), Math.min(1.0, 1.0 * mul));
    }

    // ------------------------------------------------------------ 计划

    /** 一次附魔操作的预览（GUI 信息栏、按钮都用它） */
    public record Plan(boolean ok, String reason, String resultName, double chance,
                       int oreCost, long coinCost, Action action) {
    }

    public enum Action {
        NONE, GENERATE, UPGRADE
    }

    /**
     * 算出「现在点附魔会发生什么」，不做任何修改。
     *
     * <p>纯查询：GUI 每画一次就调一次，所以不能有副作用。</p>
     */
    public Plan plan(Player player, ItemStack book, ItemStack ore) {
        if (book == null || book.getType().isAir()) {
            return fail("左边放一本附魔书或空白书");
        }
        if (!BLANK_BOOKS.contains(book.getType())) {
            return fail("书槽只收书（普通书 / 附魔书）");
        }
        if (ore == null || ore.getType().isAir()) {
            return fail("右边放青金石或矿石");
        }
        if (!ORES.containsKey(ore.getType())) {
            return fail("这种矿不能当催化剂");
        }
        if (ore.getAmount() < 1) {
            return fail("矿石不够");
        }

        EnchantEffects.Entry entry = EnchantItem.bookEntry(plugin, book);
        if (entry == null) {
            // 空白书 → 随机生成一条
            Cost cost = generateCost(ore.getType());
            if (ore.getAmount() < cost.oreCost()) {
                return fail("需要 " + cost.oreCost() + " 个" + oreLabel(ore.getType()));
            }
            return new Plan(true, "", "随机原创附魔 I", cost.chance(),
                    cost.oreCost(), cost.coinCost(), Action.GENERATE);
        }

        int target = entry.level() + 1;
        if (target > entry.def().maxLevel()) {
            return fail(entry.def().display() + " 已满级（" + entry.def().maxLevel() + " 级）");
        }
        Cost cost = upgradeCost(target, ore.getType());
        if (ore.getAmount() < cost.oreCost()) {
            return fail("需要 " + cost.oreCost() + " 个" + oreLabel(ore.getType())
                    + "，现在只有 " + ore.getAmount());
        }
        String name = entry.def().display() + " " + EnchantItem.roman(target);
        return new Plan(true, "", name, cost.chance(), cost.oreCost(), cost.coinCost(), Action.UPGRADE);
    }

    private static Plan fail(String reason) {
        return new Plan(false, reason, "", 0, 0, 0, Action.NONE);
    }

    // ------------------------------------------------------------ 执行

    /**
     * 在界面上点「附魔」。
     *
     * @return true = 成功产出（结果已写进成品格）
     */
    public boolean apply(Player player, Inventory gui) {
        ItemStack book = gui.getItem(EnchantGui.BOOK_SLOT);
        ItemStack ore = gui.getItem(EnchantGui.ORE_SLOT);
        ItemStack occupied = gui.getItem(EnchantGui.RESULT_SLOT);
        Plan plan = plan(player, book, ore);
        if (!plan.ok()) {
            Msg.prefix(player, "<red>" + plan.reason());
            return false;
        }
        if (occupied != null && !occupied.getType().isAir()) {
            Msg.prefix(player, "<red>先把成品格的东西拿走。");
            return false;
        }

        // v2.26.5（审计嫌疑 #3）：空白书 + 附魔注册表全空时，random() 产不出任何词条，
        // 原来走到掷骰成功后才 return——币和矿已经扣了，无退款。先探一次池子再扣费。
        if (EnchantItem.bookEntry(plugin, book) == null
                && plugin.enchants().random(
                        java.util.concurrent.ThreadLocalRandom.current(), null) == null) {
            Msg.prefix(player, "<red>没有可用的原创附魔（配置里全关了？），本次不扣费。</red>");
            return false;
        }

        // 先扣费，再掷骰：扣费失败就什么都别消耗
        var wallet = plugin.wallet();
        if (plan.coinCost() > 0 && !wallet.take(player, plan.coinCost(), CoinReason.ENCHANT)) {
            Msg.prefix(player, "<red>附魔费不足：需要 <white>"
                    + wallet.format(plan.coinCost()) + "</white>。");
            return false;
        }

        // 消耗矿石（无论成败都消耗 —— 这是失败率的成本）
        consumeOre(gui, plan.oreCost());

        EnchantEffects.Entry entry = EnchantItem.bookEntry(plugin, book);
        // 100% 的档位直接算成功：别让浮点比较决定「必定成功」到底成不成功
        boolean success = plan.chance() >= 1.0
                || ThreadLocalRandom.current().nextDouble() < plan.chance();

        if (!success) {
            player.playSound(player.getLocation(),
                    org.bukkit.Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.6f, 0.7f);
            Msg.prefix(player, "<red>附魔失败 <dark_gray>| <gray>矿石耗掉了，书还在，再来一次。");
            EnchantGui.render(plugin, player, gui);
            return false;
        }

        ItemStack result;
        if (entry == null) {
            // 空白书：随机一条
            CustomEnchant def = plugin.enchants().random(
                    ThreadLocalRandom.current(), null);
            if (def == null) {
                Msg.prefix(player, "<red>没有可用的原创附魔（配置里全关了？）。");
                return false;
            }
            int level = rollLevel(ore.getType(), def);
            result = EnchantItem.book(plugin, new EnchantEffects.Entry(def, level));
        } else {
            result = EnchantItem.book(plugin, new EnchantEffects.Entry(entry.def(), entry.level() + 1));
        }

        // 书被消耗（台子上那本），产物放成品格
        //
        // v2.26.3 修复（审计报告：附魔台 GUI 整叠吞书）：
        // 原来无条件 `gui.setItem(BOOK_SLOT, AIR)` —— 书格里放了几本，
        // 点一次就整格清空，只消耗「一本」的代价却吞掉整摞。
        // 现在按件递减：还剩就保留剩余数量，只扣掉本次真正消耗的那 1 本。
        consumeOneBook(gui, book);
        gui.setItem(EnchantGui.RESULT_SLOT, result);
        player.playSound(player.getLocation(),
                org.bukkit.Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.4f);
        player.playSound(player.getLocation(),
                org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.8f);
        Msg.prefix(player, "<#a78bfa>附魔成功 <dark_gray>| <white>" + plan.resultName());
        EnchantGui.render(plugin, player, gui);
        return true;
    }

    /** 好矿能开出更高等级的书 */
    private int rollLevel(Material ore, CustomEnchant def) {
        int cap = Math.min(def.maxLevel(), 1);
        if (ORES.getOrDefault(ore, 1.0) >= 1.12) {
            cap = Math.min(def.maxLevel(), 2);
        }
        if (ORES.getOrDefault(ore, 1.0) >= 1.25) {
            cap = Math.min(def.maxLevel(), 3);
        }
        return cap <= 1 ? 1 : ThreadLocalRandom.current().nextInt(1, cap + 1);
    }

    /**
     * 从书格消耗**一本**书，剩下的原样留在格子里。
     *
     * <p>以前这里是 {@code gui.setItem(BOOK_SLOT, new ItemStack(AIR))}：
     * 书格放了 2 本以上，点一次附魔只结算一本，但整格被清成空气 ——
     * 多余的附魔书凭空消失。按件递减才是「消耗一本」的正确语义。</p>
     */
    private void consumeOneBook(Inventory gui, ItemStack book) {
        if (book == null || book.getType().isAir()) {
            gui.setItem(EnchantGui.BOOK_SLOT, new ItemStack(Material.AIR));
            return;
        }
        int left = book.getAmount() - 1;
        if (left <= 0) {
            gui.setItem(EnchantGui.BOOK_SLOT, new ItemStack(Material.AIR));
            return;
        }
        // 复制一份再改数量：直接改传入引用会连带污染调用方手里那个 ItemStack
        ItemStack remain = book.clone();
        remain.setAmount(left);
        gui.setItem(EnchantGui.BOOK_SLOT, remain);
    }

    private void consumeOre(Inventory gui, int amount) {
        ItemStack ore = gui.getItem(EnchantGui.ORE_SLOT);
        if (ore == null || ore.getType().isAir()) {
            return;
        }
        int left = ore.getAmount() - amount;
        if (left <= 0) {
            gui.setItem(EnchantGui.ORE_SLOT, new ItemStack(Material.AIR));
        } else {
            ItemStack copy = ore.clone();
            copy.setAmount(left);
            gui.setItem(EnchantGui.ORE_SLOT, copy);
        }
    }

    // ------------------------------------------------------------ 上书

    /**
     * 手持附魔书右键 → 附到主手装备。
     *
     * <p>这一层是「限制」的落点：</p>
     * <ul>
     *   <li>目标必须是本插件的锻造品（{@code Keys.FORGED}）—— 原版装备不吃原创附魔；</li>
     *   <li>部位要匹配（武器附魔上不了护甲）；</li>
     *   <li>同一件装备原创附魔条数不超过 {@code enchant.max-per-item}；</li>
     *   <li>同一条附魔重复上书只升等级，不占新坑位。</li>
     * </ul>
     */
    public boolean applyBook(Player player, ItemStack book, ItemStack target) {
        EnchantEffects.Entry entry = EnchantItem.bookEntry(plugin, book);
        if (entry == null) {
            return false;
        }
        if (target == null || target.getType().isAir()) {
            Msg.prefix(player, "<red>主手要拿一件装备，才能把书附上去。");
            return false;
        }
        ItemMeta meta = target.getItemMeta();
        if (meta == null) {
            return false;
        }
        if (!meta.getPersistentDataContainer().has(Keys.FORGED, PersistentDataType.BYTE)) {
            Msg.prefix(player, "<red>原创附魔只能附在本插件锻造的装备上。");
            return false;
        }
        if (!slotMatches(entry.def(), target.getType())) {
            Msg.prefix(player, "<red>" + entry.def().display() + " 只能附在"
                    + entry.def().slot().label() + "上。");
            return false;
        }

        List<EnchantEffects.Entry> entries = new ArrayList<>(EnchantItem.of(plugin, target));
        int index = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).def().id().equals(entry.def().id())) {
                index = i;
                break;
            }
        }
        if (index >= 0) {
            EnchantEffects.Entry cur = entries.get(index);
            if (cur.level() >= entry.def().maxLevel()) {
                Msg.prefix(player, "<red>" + entry.def().display() + " 已经满级了。");
                return false;
            }
            // 升一级（书上的等级不比装备低才有意义；取两者较小者 +1 太绕，直接按装备等级 +1）
            int next = Math.min(entry.def().maxLevel(), cur.level() + 1);
            if (entry.level() > next) {
                next = entry.level(); // 书本身更高就用书上的
            }
            entries.set(index, new EnchantEffects.Entry(entry.def(), next));
        } else {
            if (entries.size() >= maxPerItem()) {
                Msg.prefix(player, "<red>这件装备的原创附魔已满（上限 "
                        + maxPerItem() + " 条）。");
                return false;
            }
            entries.add(entry);
        }

        EnchantItem.write(plugin, target, entries);
        // 把改好的装备放回**它原本所在的那只手**。
        //
        // 以前这里是 `setItemInMainHand(target)` —— 无条件写主手，于是实际能走到的
        // 唯一姿势（主手拿书、副手拿剑，见 EnchantListener 只处理 EquipmentSlot.HAND）
        // 会同时坏两件事：
        //   ① 剑被写进主手，把书覆盖掉 → 书凭空消失（玩家白丢一本）
        //   ② 副手那把剑仍在原地 → 一把剑变两把（**物品复制**）
        // 两者都是「右键一次就刷出来」的量产漏洞，比数值不平衡严重得多。
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();
        ItemStack mainNow = inv.getItemInMainHand();
        if (mainNow != null && EnchantItem.isBook(mainNow)) {
            // 主手还是书 → 装备原本在副手，写回副手，别碰主手
            inv.setItemInOffHand(target);
        } else {
            inv.setItemInMainHand(target);
        }
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.5f);
        Msg.prefix(player, "<#a78bfa>已附上 <white>" + entry.def().display() + " "
                + EnchantItem.roman(entries.stream()
                .filter(e -> e.def().id().equals(entry.def().id()))
                .findFirst().map(EnchantEffects.Entry::level).orElse(entry.level()))
                + "</white>");
        return true;
    }

    /** 部位匹配：武器附魔只上武器，护甲附魔只上护甲，通用随意 */
    public static boolean slotMatches(CustomEnchant def, Material material) {
        if (def == null || material == null) {
            return false;
        }
        boolean weapon = isWeapon(material);
        boolean armor = isArmor(material);
        return switch (def.slot()) {
            case WEAPON -> weapon;
            case ARMOR -> armor;
            case ANY -> weapon || armor;
        };
    }

    public static boolean isWeapon(Material m) {
        String n = m.name();
        return n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_TRIDENT")
                || n.equals("MACE") || n.equals("BOW") || n.endsWith("_HOE");
    }

    public static boolean isArmor(Material m) {
        String n = m.name();
        return n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE")
                || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                || n.equals("TURTLE_HELMET") || n.equals("ELYTRA");
    }

    private static String oreLabel(Material ore) {
        return switch (ore) {
            case LAPIS_LAZULI -> "青金石";
            case AMETHYST_SHARD -> "紫水晶";
            case DIAMOND -> "钻石";
            case EMERALD -> "绿宝石";
            case NETHERITE_SCRAP -> "下界合金碎片";
            case ECHO_SHARD -> "回响碎片";
            default -> "矿石";
        };
    }
}
