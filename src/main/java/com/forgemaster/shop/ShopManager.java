package com.forgemaster.shop;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.econ.CoinReason;
import com.forgemaster.econ.CoinRules;
import com.forgemaster.gui.ForgeMenu;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.material.MaterialGrade;
import com.forgemaster.material.MaterialGrades;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 商会：用剑币买原料（按档次）和剑胚图纸。
 *
 * <p>价格全部从 config.yml 的 {@code shop} 段现读（不缓存），
 * 所以 /zhujian reload 之后立即生效。</p>
 *
 * <pre>
 * shop:
 *   enabled: true
 *   batch-size: 8              # 左键一次买几个原料（右键永远 1 个）
 *   materials:                 # 原版材料名(小写) → 各档单价
 *     iron_ingot: {common: 20, refined: 60, celestial: 180}
 *   blueprints:                # 剑胚 id → 图纸单价；没写的剑胚不卖图纸
 *     iron: 120
 * </pre>
 */
public final class ShopManager {

    public static final int SIZE = 45;
    public static final int PER_PAGE = 21;

    /** 每页商品槽位（3 行 × 7 列） */
    private static final int[] CONTENT = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    private static final int PREV = 38;
    private static final int BALANCE = 40;
    private static final int NEXT = 42;
    private static final int CLOSE = 44;

    private final ForgingMasterPlugin plugin;
    private final NamespacedKey blueprintKey;

    public ShopManager(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.blueprintKey = new NamespacedKey(plugin, "blueprint");
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("shop.enabled", true);
    }

    /** 读出当前配置的全部商品（每次都重新读，热重载生效） */
    public List<ShopEntry> entries() {
        List<ShopEntry> out = new ArrayList<>();
        ConfigurationSection mats = plugin.getConfig().getConfigurationSection("shop.materials");
        if (mats != null) {
            for (String key : mats.getKeys(false)) {
                Material mat = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
                ConfigurationSection sec = mats.getConfigurationSection(key);
                if (mat == null || !mat.isItem() || sec == null) {
                    continue;
                }
                for (MaterialGrade g : MaterialGrade.values()) {
                    long price = sec.getLong(g.name().toLowerCase(Locale.ROOT), -1);
                    if (price > 0) {
                        out.add(new ShopEntry(ShopEntry.Kind.MATERIAL, mat, g, null, price));
                    }
                }
            }
        }
        ConfigurationSection bps = plugin.getConfig().getConfigurationSection("shop.blueprints");
        if (bps != null) {
            for (ForgeMaterial fm : plugin.materials().sorted()) {
                long price = bps.getLong(fm.id(), -1);
                if (price > 0) {
                    out.add(new ShopEntry(ShopEntry.Kind.BLUEPRINT, Material.PAPER, null, fm, price));
                }
            }
        }
        ConfigurationSection specials = plugin.getConfig().getConfigurationSection("shop.specials");
        if (specials != null) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String key : specials.getKeys(false)) {
                // 三个 id：talisman（护胚符）/ steady（稳胚符）/ charm-kindle（旺火符）
                String id = com.forgemaster.strengthen.Charm.normalize(key);
                if (id == null || !seen.add(id)) {
                    continue;
                }
                long price = specials.getLong(key, -1);
                if (price <= 0) {
                    continue;
                }
                out.add(new ShopEntry(ShopEntry.Kind.SPECIAL,
                        com.forgemaster.strengthen.Charm.material(plugin, id),
                        null, null, price, id));
            }
        }
        return out;
    }

    public int pageCount(int size) {
        return Math.max(1, (size + PER_PAGE - 1) / PER_PAGE);
    }

    /** 打开商会，page 从 1 开始 */
    public void open(Player p, int page) {
        ShopHolder holder = new ShopHolder(Math.max(1, page));
        Inventory inv = Bukkit.createInventory(holder, SIZE, Msg.mm("<dark_gray>铸剑大师 <dark_gray>| <#ffd166>商会"));
        holder.inventory(inv);
        render(inv, holder, p);
        p.openInventory(inv);
        p.playSound(p.getLocation(), org.bukkit.Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.1f);
    }

    /** 重绘当前页（余额、价格、翻页后都走这里） */
    private void render(Inventory inv, ShopHolder holder, Player viewer) {
        List<ShopEntry> list = entries();
        int pages = pageCount(list.size());
        int page = Math.max(1, Math.min(holder.page(), pages));
        holder.page(page);

        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, ForgeMenu.filler());
        }
        int base = (page - 1) * PER_PAGE;
        for (int i = 0; i < CONTENT.length; i++) {
            int idx = base + i;
            if (idx >= list.size()) {
                break;
            }
            inv.setItem(CONTENT[i], entryIcon(list.get(idx)));
        }

        long bal = plugin.stats().coins(viewer.getUniqueId());
        inv.setItem(BALANCE, ForgeMenu.icon(Material.GOLD_INGOT, "<#ffd166>剑币余额", List.of(
                "<gray>当前: " + CoinRules.fmt(bal),
                "<gray>第 <white>" + page + "</white> / " + pages + " 页",
                "",
                "<dark_gray>剑币来自锻造、委托与大赛")));
        if (page > 1) {
            inv.setItem(PREV, ForgeMenu.icon(Material.ARROW, "<#7ef2c8>◀ 上一页", List.of()));
        }
        if (page < pages) {
            inv.setItem(NEXT, ForgeMenu.icon(Material.ARROW, "<#7ef2c8>下一页 ▶", List.of()));
        }
        inv.setItem(CLOSE, ForgeMenu.icon(Material.BARRIER, "<red>关闭", List.of()));
    }

    private ItemStack entryIcon(ShopEntry e) {
        if (e.kind() == ShopEntry.Kind.BLUEPRINT) {
            ForgeMaterial fm = e.forge();
            return ForgeMenu.icon(Material.PAPER, "<#ffd166>图纸 · </#ffd166>" + fm.display(), List.of(
                    "<gray>单价: " + CoinRules.fmt(e.unitPrice()),
                    "<gray>剑胚: " + fm.display(),
                    "",
                    "<yellow>▶ 左键购买"));
        }
        if (e.kind() == ShopEntry.Kind.SPECIAL) {
            ItemStack icon = com.forgemaster.strengthen.Charm.create(plugin, specialId(e));
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                List<net.kyori.adventure.text.Component> lore = meta.lore() == null
                        ? new ArrayList<>() : new ArrayList<>(meta.lore());
                lore.add(Msg.mm("<gray>单价: " + CoinRules.fmt(e.unitPrice()))
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                lore.add(Msg.mm("<yellow>▶ 左键购买 1 张")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                meta.lore(lore);
                icon.setItemMeta(meta);
            }
            return icon;
        }
        MaterialGrade g = e.grade();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>档次: " + g.colored());
        if (g.scoreBonus() > 0) {
            lore.add("<gray>成品上限: <white>+" + String.format(Locale.ROOT, "%.0f", g.scoreBonus()) + "%");
        }
        if (g.extraAffixes() > 0) {
            lore.add("<gray>额外词条: <white>+" + g.extraAffixes());
        }
        lore.add("<gray>单价: " + CoinRules.fmt(e.unitPrice()));
        lore.add("");
        lore.add("<yellow>▶ 左键买 " + batchSize() + " 个");
        lore.add("<yellow>▶ 右键买 1 个");
        return ForgeMenu.icon(e.material(), g.colored() + " <white>" + MaterialGrades.pretty(e.material()), lore);
    }

    /** 点击处理（由 ShopListener 调用，已确认是商会界面） */
    public void handleClick(Player p, ShopHolder holder, int slot, ClickType click) {
        Inventory inv = holder.getInventory();
        if (slot == CLOSE) {
            p.closeInventory();
            return;
        }
        if (slot == PREV || slot == NEXT) {
            int pages = pageCount(entries().size());
            int next = slot == PREV ? holder.page() - 1 : holder.page() + 1;
            if (next < 1 || next > pages) {
                return;
            }
            holder.page(next);
            render(inv, holder, p);
            p.playSound(p.getLocation(), org.bukkit.Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.2f);
            return;
        }
        int pos = indexOfSlot(slot);
        if (pos < 0) {
            return;
        }
        List<ShopEntry> list = entries();
        int idx = (holder.page() - 1) * PER_PAGE + pos;
        if (idx >= list.size()) {
            return;
        }
        ShopEntry e = list.get(idx);
        boolean one = e.kind() == ShopEntry.Kind.BLUEPRINT
                || e.kind() == ShopEntry.Kind.SPECIAL
                || click.isRightClick();
        int qty = one ? 1 : batchSize();
        buy(p, e, qty);
        render(inv, holder, p);
    }

    /** 扣币 → 发货。余额不足直接拒绝，不发货。 */
    private void buy(Player p, ShopEntry e, int qty) {
        UUID id = p.getUniqueId();
        long total = e.unitPrice() * qty;
        if (!plugin.stats().takeCoins(id, total, CoinReason.SHOP)) {
            Msg.prefix(p, "<red>剑币不够。</red> 需要 " + CoinRules.fmt(total)
                    + "<gray>，你只有 </gray>" + CoinRules.fmt(plugin.stats().coins(id)) + "<gray>。</gray>");
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
            return;
        }

        List<ItemStack> goods = new ArrayList<>();
        String name;
        if (e.kind() == ShopEntry.Kind.BLUEPRINT) {
            goods.add(buildBlueprint(e.forge()));
            name = "图纸 · " + e.forge().display();
        } else if (e.kind() == ShopEntry.Kind.SPECIAL) {
            String charmId = specialId(e);
            for (int i = 0; i < qty; i++) {
                goods.add(com.forgemaster.strengthen.Charm.create(plugin, charmId));
            }
            name = com.forgemaster.strengthen.Charm.displayName(charmId);
        } else {
            int max = Math.max(1, e.material().getMaxStackSize());
            int remain = qty;
            while (remain > 0) {
                int n = Math.min(max, remain);
                goods.add(MaterialGrades.stamp(new ItemStack(e.material(), n), e.grade()));
                remain -= n;
            }
            name = e.grade().colored() + " <white>" + MaterialGrades.pretty(e.material());
        }

        for (ItemStack it : goods) {
            for (ItemStack rest : p.getInventory().addItem(it).values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), rest);
            }
        }
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
        Msg.prefix(p, "<green>购入 </green>" + name + " <white>×" + qty + "</white>，花费 "
                + CoinRules.fmt(total) + "<gray>，余额 </gray>" + CoinRules.fmt(plugin.stats().coins(id)) + "<gray>。</gray>");
    }

    public ItemStack buildBlueprint(ForgeMaterial fm) {
        int bonus = (int) Math.round(plugin.settings().num("blueprint.tolerance-bonus", 0.15) * 100);
        ItemStack item = ForgeMenu.icon(Material.PAPER, "<#ffd166>图纸 · </#ffd166>" + fm.display(), List.of(
                "<gray>记着 " + fm.display() + " <gray>的锻造要诀。",
                "<gray>开工时放在背包里：加热容差 <#4ee36b>+" + bonus + "%</#4ee36b>",
                plugin.settings().flag("blueprint.required", false)
                        ? "<dark_gray>没有图纸将无法锻造该剑胚" : "<dark_gray>有图纸打得更稳",
                "<dark_gray>商会出品"));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(blueprintKey, PersistentDataType.STRING, fm.id());
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 读图纸上的剑胚 id（不是图纸返回 null），给以后的玩法接入用 */
    public String blueprintOf(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(blueprintKey, PersistentDataType.STRING);
    }

    private int batchSize() {
        return Math.max(1, Math.min(64, plugin.getConfig().getInt("shop.batch-size", 8)));
    }

    /** SPECIAL 条目的符 id，认不出时退回护胚符 */
    private static String specialId(ShopEntry e) {
        String id = com.forgemaster.strengthen.Charm.normalize(e.id());
        return id == null ? com.forgemaster.strengthen.Charm.TALISMAN : id;
    }

    private static int indexOfSlot(int slot) {
        for (int i = 0; i < CONTENT.length; i++) {
            if (CONTENT[i] == slot) {
                return i;
            }
        }
        return -1;
    }
}
