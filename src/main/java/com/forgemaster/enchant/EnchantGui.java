package com.forgemaster.enchant;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.util.Msg;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 附魔台界面（v2.23.2）。
 *
 * <h2>布局（54 格 = 6 行）</h2>
 * <pre>
 *   第 1 行   1  2  3 [成品] 5  6  7  8  9
 *   第 2 行       （留空，视觉上把上下分开）
 *   第 3 行        [书]      [信息]      [矿]
 *   第 4 行       （留空）
 *   第 5 行       （留空）
 *   第 6 行  [关闭]             [附魔]
 * </pre>
 *
 * <p>对应服主的描述：<b>靠上中间 = 成品、左边 = 书、右边 = 青金石/矿石、靠下中间 = 附魔按钮</b>。
 * 中间那格（slot 22）不是装饰 —— 它实时显示「成功率 / 需要几个矿 / 要花多少剑币 / 这一击会产出什么」。</p>
 *
 * <h2>为什么是 54 而不是 27</h2>
 * 27 格里要塞下「成品 + 书 + 矿 + 信息 + 按钮 + 关闭」，上下左右几乎贴在一起，
 * 玩家一眼分不清哪格是输入哪格是输出（这类界面最容易出现「把成品当输入塞回去」的误操作）。
 * 54 格给足了留白，输入区（左/右）与输出区（上）在空间上彻底分开。
 */
public final class EnchantGui {

    public static final int SIZE = 54;

    /** 成品预览（靠上中间） */
    public static final int RESULT_SLOT = 4;
    /** 书槽（左边） */
    public static final int BOOK_SLOT = 20;
    /** 矿石 / 青金石槽（右边） */
    public static final int ORE_SLOT = 24;
    /** 信息栏（正中间） */
    public static final int INFO_SLOT = 22;
    /** 附魔按钮（靠下中间） */
    public static final int ACTION_SLOT = 49;
    /** 关闭 */
    public static final int CLOSE_SLOT = 45;

    /** 三个输入/输出格：点击时不取消，允许玩家自己放取 */
    public static final int[] FREE_SLOTS = {BOOK_SLOT, ORE_SLOT, RESULT_SLOT};

    private EnchantGui() {
    }

    /** 每个界面一份 holder：路由靠它认领，防跨玩家复用 */
    static final class Holder implements InventoryHolder {
        private final java.util.UUID player;
        private Inventory inventory;

        Holder(java.util.UUID player) {
            this.player = player;
        }

        java.util.UUID player() {
            return player;
        }

        void inventory(Inventory inv) {
            this.inventory = inv;
        }

        @Override
        public Inventory getInventory() {
            if (inventory == null) {
                throw new IllegalStateException("EnchantGui.Holder 还没绑定 Inventory");
            }
            return inventory;
        }
    }

    /** 打开界面 */
    public static void open(ForgingMasterPlugin plugin, Player player) {
        Holder holder = new Holder(player.getUniqueId());
        Inventory inv = plugin.getServer().createInventory(holder, SIZE,
                Msg.mm("<dark_gray>附魔台 <dark_gray>| <white>原创附魔"));
        holder.inventory(inv);
        render(plugin, player, inv);
        EnchantRouter.ensure(plugin);
        player.openInventory(inv);
    }

    /** 整屏重画（保留三个自由格的当前内容） */
    static void render(ForgingMasterPlugin plugin, Player player, Inventory inv) {
        ItemStack book = inv.getItem(BOOK_SLOT);
        ItemStack ore = inv.getItem(ORE_SLOT);
        ItemStack result = inv.getItem(RESULT_SLOT);

        for (int i = 0; i < SIZE; i++) {
            if (i == BOOK_SLOT || i == ORE_SLOT || i == RESULT_SLOT) {
                continue;
            }
            inv.setItem(i, filler());
        }
        // 把自由格内容原样放回（重画时不能把玩家的东西吞掉）
        inv.setItem(BOOK_SLOT, book);
        inv.setItem(ORE_SLOT, ore);
        inv.setItem(RESULT_SLOT, result);

        inv.setItem(INFO_SLOT, infoIcon(plugin, player, inv));
        inv.setItem(ACTION_SLOT, actionIcon(plugin, player, inv));
        inv.setItem(CLOSE_SLOT, icon(Material.BARRIER, "<red>关闭",
                List.of("<gray>把书和矿取走，别留在格子里")));
    }

    /** 信息栏：把「这一击会发生什么」讲清楚 */
    private static ItemStack infoIcon(ForgingMasterPlugin plugin, Player player, Inventory inv) {
        ItemStack book = inv.getItem(BOOK_SLOT);
        ItemStack ore = inv.getItem(ORE_SLOT);
        var plan = plugin.enchantTable().plan(player, book, ore);

        List<String> lore = new ArrayList<>();
        lore.add("<gray>书 <dark_gray>→</dark_gray> <white>"
                + (book == null || book.getType().isAir() ? "空" : name(book)) + "</white>");
        lore.add("<gray>矿 <dark_gray>→</dark_gray> <white>"
                + (ore == null || ore.getType().isAir() ? "空" : name(ore)) + "</white>");
        lore.add("");
        if (!plan.ok()) {
            lore.add("<red>" + plan.reason());
            lore.add("<dark_gray>左边放书，右边放青金石或矿石");
        } else {
            lore.add("<gray>产出: <white>" + plan.resultName() + "</white>");
            lore.add("<gray>成功率: <white>" + Msg.fmt(plan.chance() * 100, 0) + "%</white>");
            lore.add("<gray>消耗: <white>" + plan.oreCost() + " 个" + oreName(ore) + "</white>");
            lore.add("<gray>费用: <#ffd166>" + plugin.economy().format(plan.coinCost()) + "</#ffd166>");
            if (plan.chance() < 1.0) {
                lore.add("<dark_gray>失败只损耗矿石，书不消失");
            }
        }
        lore.add("");
        lore.add("<dark_gray>原创附魔上限: " + plugin.enchants().size() + " 条");
        return icon(Material.ENCHANTED_BOOK, "<#a78bfa>附魔台", lore);
    }

    /** 附魔按钮 */
    private static ItemStack actionIcon(ForgingMasterPlugin plugin, Player player, Inventory inv) {
        var plan = plugin.enchantTable().plan(player, inv.getItem(BOOK_SLOT), inv.getItem(ORE_SLOT));
        if (!plan.ok()) {
            return icon(Material.GRAY_DYE, "<gray>附魔", List.of("<red>" + plan.reason()));
        }
        return icon(Material.ENCHANTING_TABLE, "<#a78bfa><bold>附 魔</bold>", List.of(
                "<gray>产出: <white>" + plan.resultName() + "</white>",
                "<gray>成功率: <white>" + Msg.fmt(plan.chance() * 100, 0) + "%</white>",
                "<gray>消耗: <white>" + plan.oreCost() + " 个" + oreName(inv.getItem(ORE_SLOT)) + "</white>",
                "<gray>费用: <#ffd166>" + plugin.economy().format(plan.coinCost()) + "</#ffd166>",
                "",
                "<yellow>▶ 左键附魔"));
    }

    // ------------------------------------------------------------ 工具

    static ItemStack filler() {
        return icon(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
    }

    static ItemStack icon(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Msg.mm(name).decoration(TextDecoration.ITALIC, false));
        if (!lore.isEmpty()) {
            List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
            for (String l : lore) {
                lines.add(Msg.mm(l).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lines);
        }
        item.setItemMeta(meta);
        return item;
    }

    private static String name(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "空";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.displayName() == null) {
            return item.getType().name();
        }
        // v2.26.4：名字会被拼回 MiniMessage lore 重解析——改名物品（铁砧普通物品随便改）
        // 可携带 <click:run_command> 注入看板的人。返回前统一转义。
        return Msg.esc(Msg.plain(meta.displayName()));
    }

    private static String oreName(ItemStack ore) {
        if (ore == null || ore.getType().isAir()) {
            return "矿";
        }
        return switch (ore.getType()) {
            case LAPIS_LAZULI -> "青金石";
            case AMETHYST_SHARD -> "紫水晶";
            case DIAMOND -> "钻石";
            case EMERALD -> "绿宝石";
            case NETHERITE_SCRAP -> "下界合金碎片";
            case ECHO_SHARD -> "回响碎片";
            default -> "矿";
        };
    }
}