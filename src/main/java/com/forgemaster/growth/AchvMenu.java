package com.forgemaster.growth;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.stats.StatsStore;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 成就界面（B3）：/zhujian achievements 打开，展示已达成 / 未达成 / 进度。
 *
 * <p>一页 45 格（5 行），底部一行翻页与信息。点击翻页槽位切页，其他点击无效果。</p>
 */
public final class AchvMenu {

    private static final int PAGE_SIZE = 45;
    /** 翻页：上一页 / 下一页 / 档案摘要 */
    public static final int SLOT_PREV = 45;
    public static final int SLOT_INFO = 49;
    public static final int SLOT_NEXT = 53;

    private final ForgingMasterPlugin plugin;

    public AchvMenu(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    /** 打开第 page 页（从 0 开始） */
    public void open(Player player, int page) {
        if (!plugin.achievements().enabled()) {
            Msg.prefix(player, "<gray>成就系统未开启。</gray>");
            return;
        }
        List<AchievementDef> defs = plugin.achievements().defs();
        int pages = Math.max(1, (defs.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int p = Math.max(0, Math.min(page, pages - 1));

        AchvHolder holder = new AchvHolder(p, pages);
        Inventory inv = Bukkit.createInventory(holder, 54,
                Msg.mm("<gradient:#ffd166:#ff7a18><bold>成就</bold></gradient> <dark_gray>" + (p + 1) + "/" + pages));
        holder.inventory(inv);

        StatsStore.Stats st = plugin.stats().get(player.getUniqueId());
        int from = p * PAGE_SIZE;
        int to = Math.min(defs.size(), from + PAGE_SIZE);
        for (int i = from; i < to; i++) {
            inv.setItem(i - from, icon(defs.get(i), st));
        }

        if (p > 0) {
            inv.setItem(SLOT_PREV, named(Material.ARROW, "<yellow>上一页"));
        }
        if (p < pages - 1) {
            inv.setItem(SLOT_NEXT, named(Material.ARROW, "<yellow>下一页"));
        }
        inv.setItem(SLOT_INFO, summary(defs, st));
        player.openInventory(inv);
    }

    /** 点击处理：由 ForgeListener 路由到这里，返回 true 表示已处理 */
    public boolean click(InventoryClickEvent event, AchvHolder holder) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) {
            return true;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return true;
        }
        if (slot == SLOT_PREV && holder.page() > 0) {
            open(player, holder.page() - 1);
        } else if (slot == SLOT_NEXT && holder.page() < holder.pages() - 1) {
            open(player, holder.page() + 1);
        }
        return true;
    }

    private ItemStack icon(AchievementDef d, StatsStore.Stats st) {
        boolean done = st.achievements.contains(d.id());
        double now = plugin.achievements().progress(d, st);
        Material mat = done ? d.icon() : Material.GRAY_DYE;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(GrowthUtil.itemName((done ? "<green>✔ <white>" : "<gray>✘ <gray>") + d.name()));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(GrowthUtil.loreLine("<gray>" + d.desc()));
        lore.add(GrowthUtil.loreLine(""));
        if (done) {
            lore.add(GrowthUtil.loreLine("<green>已达成"));
        } else {
            lore.add(GrowthUtil.loreLine("<gray>进度 <white>" + fmt(now) + "</white> / " + fmt(d.need())));
            lore.add(GrowthUtil.loreLine(bar(now, d.need())));
        }
        lore.add(GrowthUtil.loreLine(""));
        lore.add(GrowthUtil.loreLine("<gray>奖励：" + (d.coins() > 0 ? "<gold>" + d.coins() + " 剑币 " : "")
                + (d.rep() > 0 ? "<#ffd166>" + d.rep() + " 声望 " : "")
                + (d.points() > 0 ? "<aqua>" + d.points() + " 成就点" : "")));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack summary(List<AchievementDef> defs, StatsStore.Stats st) {
        int done = 0;
        for (AchievementDef d : defs) {
            if (st.achievements.contains(d.id())) {
                done++;
            }
        }
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(GrowthUtil.itemName("<gradient:#ffd166:#ff7a18><bold>成就进度</bold></gradient>"));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(GrowthUtil.loreLine("<gray>已达成 <white>" + done + "</white> / " + defs.size()));
        lore.add(GrowthUtil.loreLine("<gray>成就点 <aqua>" + st.achvPoints));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack named(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(GrowthUtil.itemName(name));
        item.setItemMeta(meta);
        return item;
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : Msg.fmt(v, 1);
    }

    private static String bar(double now, double need) {
        double ratio = need <= 0 ? 1.0 : Math.max(0.0, Math.min(1.0, now / need));
        return Msg.bar(ratio, 10, "green", "dark_gray");
    }
}
