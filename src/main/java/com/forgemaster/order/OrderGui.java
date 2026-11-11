package com.forgemaster.order;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 锻造订单面板（照拍卖行浏览界面的格局）：
 * 0-44 = 全服 OPEN 订单（按悬赏降序），45 = 我的接单（交付口），49 关闭，53 刷新。
 *
 * <p>每张订单显示需求明细与「自己的完成度」（拿主手的东西现场校验一遍）。
 * 左键接单；Shift+左键取消自己发的未接单订单；45 号槽左键 = 用主手的剑交付。</p>
 */
public final class OrderGui {

    public static final int LIST_SIZE = 45;
    public static final int SLOT_TAKEN = 45;
    public static final int SLOT_CLOSE = 49;
    public static final int SLOT_REFRESH = 53;

    private OrderGui() {
    }

    public static void open(ForgingMasterPlugin plugin, Player p) {
        OrderGuiRouter.ensure(plugin); // 懒注册：第一次打开面板才挂点击路由
        Holder holder = new Holder(plugin, p.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, 54,
                Msg.mm("<dark_gray>锻造订单 <dark_gray>| <#ffd166>悬赏市场"));
        holder.inventory(inv);
        render(inv, holder, p);
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 1.2f);
    }

    /** 画 / 重画面板（打开与点击路由后共用） */
    public static void render(Inventory inv, Holder holder, Player p) {
        OrderService service = holder.plugin().orders();
        if (service == null) {
            return;
        }
        // v2.23.1 修 P1-4：重画前先清掉上一轮的绑定。
        // 原先只 inv.clear() 不碰这两个字段：订单列表变短（被别人接走 / 过期）之后，
        // slotOrders 里还留着已经不显示的订单 id，于是点那些「现在只是灰玻璃」的格子
        // 会拿陈旧 id 去 take / deliver —— take 被 TAKE_MAX=1 挡住却已把玩家锁进
        // 「已接单」状态（他自己看不到任何单），deliver 则用陈旧 id 交付。
        // 现在映射与画面永远同源：本次画出来的槽位才有绑定。
        holder.resetBindings();
        inv.clear();
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, filler());
        }
        inv.setItem(4, icon(Material.WRITABLE_BOOK, "<#ffd166>锻造订单",
                List.of("<gray>玩家发的悬赏单：做出达标的剑来领赏。",
                        "<gray>发单: <white>主手拿样品 /zhujian order post <悬赏>",
                        "<gray>左键接单 · Shift+左键取消自己的单",
                        "<gray>接单后把达标剑拿主手，点下面「交付」。")));

        ItemStack held = p.getInventory().getItemInMainHand();
        com.forgemaster.item.ItemFactory items = holder.plugin().items();
        List<Order> open = service.openByBounty();
        int shown = Math.min(LIST_SIZE, open.size());
        for (int i = 0; i < shown; i++) {
            Order o = open.get(i);
            inv.setItem(i, orderIcon(items, o, held, p.getUniqueId()));
            holder.bind(i, o.id());
        }

        Order mine = service.takenBy(p.getUniqueId());
        if (mine != null) {
            holder.bindTaken(mine.id());
            inv.setItem(SLOT_TAKEN, takenIcon(items, mine, held));
        } else {
            inv.setItem(SLOT_TAKEN, icon(Material.MINECART,
                    "<gray>没有进行中的接单",
                    List.of("<gray>左键上面的订单接一单。",
                            "<dark_gray>同时只能接 1 单")));
        }

        inv.setItem(SLOT_CLOSE, icon(Material.BARRIER, "<red>关闭", List.of()));
        inv.setItem(SLOT_REFRESH, icon(Material.CLOCK, "<#7ef2c8>刷新",
                List.of("<gray>挂出中 <white>" + open.size() + "</white> 张"
                        + (open.size() > LIST_SIZE ? "（只显示悬赏最高的 " + LIST_SIZE + " 张）" : ""))));
    }

    private static ItemStack orderIcon(com.forgemaster.item.ItemFactory items,
                                       Order o, ItemStack held, UUID viewer) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>发单人: <white>" + o.posterName());
        lore.add("");
        lore.addAll(OrderService.requirementLines(o));
        lore.add("");
        lore.add("<gold>悬赏: <white>" + o.bounty() + " 剑币");
        lore.add("<gray>剩余: <white>" + Msg.fmt(o.remainMillis() / 3_600_000.0, 1) + " 小时");
        lore.add("");
        if (o.poster().equals(viewer)) {
            lore.add("<yellow>这是你发的单 · Shift+左键取消（全额退悬赏）");
        } else {
            String miss = o.missing(items, held);
            lore.add(miss == null
                    ? "<#4ee36b>✔ 手上这把达标，可接单"
                    : "<dark_gray>完成度: <red>✘ " + miss);
            lore.add("<yellow>▶ 左键接单");
        }
        return icon(Material.IRON_SWORD,
                "<gradient:#ffd166:#ff9a3c><bold>订单 #" + o.id() + "</bold></gradient>", lore);
    }

    private static ItemStack takenIcon(com.forgemaster.item.ItemFactory items,
                                       Order o, ItemStack held) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>发单人: <white>" + o.posterName());
        lore.add("");
        lore.addAll(OrderService.requirementLines(o));
        lore.add("");
        lore.add("<gold>悬赏: <white>" + o.bounty() + " 剑币");
        lore.add("<gray>剩余: <white>" + Msg.fmt(o.remainMillis() / 3_600_000.0, 1) + " 小时");
        lore.add("");
        String miss = o.missing(items, held);
        lore.add(miss == null
                ? "<#4ee36b>✔ 手上这把达标，点这里交付"
                : "<red>✘ " + miss);
        lore.add("<yellow>▶ 左键交付（用主手的剑）");
        return icon(Material.NETHERITE_SWORD,
                "<gradient:#7ef2c8:#4ee36b><bold>我的接单 #" + o.id() + "</bold></gradient>", lore);
    }

    private static ItemStack filler() {
        ItemStack it = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Msg.mm("<dark_gray>·").decoration(TextDecoration.ITALIC, false));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack icon(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) {
            return it;
        }
        meta.displayName(Msg.mm(name).decoration(TextDecoration.ITALIC, false));
        List<Component> lines = new ArrayList<>();
        for (String l : lore) {
            lines.add(Msg.mm(l).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lines);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    /** 订单面板持有者：记录槽位 → 订单 id，点击路由用 */
    public static final class Holder implements InventoryHolder {

        private final ForgingMasterPlugin plugin;
        private final UUID viewer;
        private final Map<Integer, String> slotOrders = new HashMap<>();
        private String takenId;
        private Inventory inventory;

        Holder(ForgingMasterPlugin plugin, UUID viewer) {
            this.plugin = plugin;
            this.viewer = viewer;
        }

        public ForgingMasterPlugin plugin() {
            return plugin;
        }

        public UUID viewer() {
            return viewer;
        }

        public void bind(int slot, String id) {
            slotOrders.put(slot, id);
        }

        /**
         * 清空上一轮的槽位映射与成交标记（v2.23.1 修 P1-4：每次 {@link #render} 开头调用）。
         *
         * <p>不清的后果：列表变短后旧槽位仍指向已经不显示的订单，点灰玻璃就会拿陈旧 id
         * 去接单 / 交付。清完之后「有绑定」与「画出了东西」严格对应，
         * 点任何装饰格 / 占位格只会走到 {@code idAt() == null} 的空转分支。</p>
         */
        public void resetBindings() {
            slotOrders.clear();
            takenId = null;
        }

        public String idAt(int slot) {
            return slotOrders.get(slot);
        }

        public void bindTaken(String id) {
            this.takenId = id;
        }

        public String takenId() {
            return takenId;
        }

        void inventory(Inventory inv) {
            this.inventory = inv;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
