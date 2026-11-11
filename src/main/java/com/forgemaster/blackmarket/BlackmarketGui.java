package com.forgemaster.blackmarket;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.gui.ForgeMenu;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.material.MaterialGrade;
import com.forgemaster.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 黑市面板：今日 4 件特价货 + 余额 + 关闭。
 *
 * <p>静态快照界面（打开时画一次），买完重画一次，不挂定时任务（照 SpectrumGui 的省任务做法）。
 * 点击路由在 {@link BlackmarketRouter}（照 PassGuiRouter 的懒注册做法）。</p>
 *
 * <p>槽位（27 格，本类独占）：</p>
 * <pre>
 *   0  1  2  3  4  5  6  7  8     4 = 黑市状态（日期 / 折扣区间）
 *   9 10 11 12 13 14 15 16 17     10/12/14/16 = 商品（最多 4 件）
 *  18 19 20 21 22 23 24 25 26     22 = 剑币余额，26 = 关闭
 * </pre>
 */
public final class BlackmarketGui {

    public static final int SIZE = 27;

    /** 黑市状态 */
    public static final int STATUS_SLOT = 4;
    /** 商品槽位 */
    public static final int[] ENTRY_SLOTS = {10, 12, 14, 16};
    /** 余额 */
    public static final int BALANCE_SLOT = 22;
    /** 关闭 */
    public static final int CLOSE_SLOT = 26;

    /** 面板持有者 */
    public static final class Holder implements InventoryHolder {

        private final ForgingMasterPlugin plugin;
        private final UUID viewer;
        /** 槽位 → 今日特价（点击路由用） */
        private final Map<Integer, BlackmarketService.Offer> slotOffers = new LinkedHashMap<>();
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

        /** 槽位上的特价（没有返回 null） */
        public BlackmarketService.Offer offerAt(int slot) {
            return slotOffers.get(slot);
        }

        Map<Integer, BlackmarketService.Offer> slotOffers() {
            return slotOffers;
        }

        void inventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private BlackmarketGui() {
    }

    /** 打开黑市面板 */
    public static void open(ForgingMasterPlugin plugin, Player player) {
        if (plugin == null || player == null) {
            return;
        }
        BlackmarketRouter.ensure(plugin);
        BlackmarketService service = plugin.blackmarket();
        if (service == null || !service.enabled()) {
            Msg.prefix(player, "<red>黑市今天没开。</red>");
            return;
        }

        Holder holder = new Holder(plugin, player.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, SIZE,
                Msg.mm("<dark_gray>铸剑大师 <dark_gray>| <#b388ff>黑市"));
        holder.inventory(inv);
        render(inv, holder, player);
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_BARREL_OPEN, 0.8f, 0.9f);
    }

    /** 重画（余额变化、买完之后都走这里） */
    public static void render(Inventory inv, Holder holder, Player viewer) {
        BlackmarketService service = holder.plugin().blackmarket();
        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, ForgeMenu.filler());
        }
        holder.slotOffers().clear();
        if (service == null) {
            return;
        }
        List<BlackmarketService.Offer> offers = service.offers();

        inv.setItem(STATUS_SLOT, ForgeMenu.icon(Material.ENDER_CHEST,
                "<#b388ff><bold>黑市 · 今日特价</bold>",
                List.of("<gray>风声传来，商会外有人在卖便宜的货。",
                        "<gray>每天换一批，全服同价，",
                        "<gray>每件每人限购 <white>"
                                + holder.plugin().getConfig().getInt("blackmarket.daily-limit", 2)
                                + "</white> 次。",
                        "<dark_gray>" + DailyStore.today())));

        int max = Math.min(offers.size(), ENTRY_SLOTS.length);
        for (int i = 0; i < max; i++) {
            BlackmarketService.Offer offer = offers.get(i);
            int slot = ENTRY_SLOTS[i];
            inv.setItem(slot, offerIcon(service, offer, viewer));
            holder.slotOffers().put(slot, offer);
        }

        long bal = holder.plugin().wallet().coins(viewer);
        inv.setItem(BALANCE_SLOT, ForgeMenu.icon(Material.GOLD_INGOT, "<#ffd166>剑币余额",
                List.of("<gray>当前: " + holder.plugin().wallet().format(bal),
                        "",
                        "<dark_gray>黑市只收剑币，谢绝还价")));
        inv.setItem(CLOSE_SLOT, ForgeMenu.icon(Material.BARRIER, "<red>关闭", List.of()));
    }

    /** 一件特价的展示图标（真货在 buy() 里另建，展示图标不带 PDC） */
    private static ItemStack offerIcon(BlackmarketService service, BlackmarketService.Offer offer,
                                       Player viewer) {
        BlackmarketService.PoolItem item = offer.item();
        int pct = Math.round((float) offer.discount() * 100);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>原价 <st><white>" + offer.item().basePrice() + "</white></st>"
                + " <#7ef2c8>→ <white>" + offer.price() + "</white>"
                + " <#b388ff>（" + pct + "% 价）</#b388ff>");
        int remain = service.remaining(viewer, offer);
        lore.add(remain > 0
                ? "<gray>今日限购: <white>还可买 " + remain + "</white> 次"
                : "<red>今日已购满，明天再来</red>");
        lore.add("");
        lore.add("<yellow>▶ 左键购买 1 件");

        return switch (item.type()) {
            case "charm" -> charmIcon(service, item.id(), lore);
            case "blueprint" -> blueprintIcon(service, item.id(), lore);
            default -> {
                MaterialGrade g = item.grade();
                Material m = Material.matchMaterial(item.id());
                yield m == null ? ForgeMenu.icon(Material.BARRIER, "<red>坏条目</red>", lore)
                        : ForgeMenu.icon(m, (g == null ? "" : g.colored() + " ")
                                + "<white>" + com.forgemaster.material.MaterialGrades.pretty(m), lore);
            }
        };
    }

    /** 符的图标：用真符做底、附加黑市价签（跟商会 SPECIAL 的做法一致） */
    private static ItemStack charmIcon(BlackmarketService service, String charmId, List<String> lore) {
        ItemStack icon = com.forgemaster.strengthen.Charm.create(service.plugin(), charmId);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            List<Component> lores = meta.lore() == null
                    ? new ArrayList<>() : new ArrayList<>(meta.lore());
            for (String line : lore) {
                lores.add(Msg.mm(line).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lores);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private static ItemStack blueprintIcon(BlackmarketService service, String id, List<String> lore) {
        ForgeMaterial fm = service.plugin().materials() == null
                ? null : service.plugin().materials().get(id);
        String name = "<#ffd166>图纸 · </#ffd166>" + (fm == null ? id : fm.display());
        return ForgeMenu.icon(Material.PAPER, name, lore);
    }
}
