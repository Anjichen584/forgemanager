package com.forgemaster.guild;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.gui.ForgeMenu;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 行会列表面板（拓展第三档）：全服行会按当周积分排序一屏摆开，静态快照界面。
 *
 * <p>照 {@link com.forgemaster.pass.PassGui} 的省任务做法：打开时画一次，不挂刷新任务；
 * 点击路由在 {@link GuildGuiRouter}（懒注册，不碰主类）。</p>
 *
 * <p>槽位（54 格，本类独占）：0-44 = 行会列表（最多 45 个），49 = 关闭。</p>
 */
public final class GuildGui {

    public static final int SIZE = 54;
    public static final int LIST_FROM = 0;
    public static final int LIST_TO = 44;
    public static final int CLOSE_SLOT = 49;

    /** 面板持有者（路由只认这个 holder） */
    public static final class Holder implements InventoryHolder {

        private final ForgingMasterPlugin plugin;
        private Inventory inventory;

        Holder(ForgingMasterPlugin plugin) {
            this.plugin = plugin;
        }

        public ForgingMasterPlugin plugin() {
            return plugin;
        }

        void inventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private GuildGui() {
    }

    /** 打开全服行会列表 */
    public static void open(ForgingMasterPlugin plugin, Player player) {
        if (plugin == null || player == null) {
            return;
        }
        GuildGuiRouter.ensure(plugin);
        GuildService service = plugin.guilds();
        if (service == null || !service.enabled()) {
            Msg.prefix(player, "<red>行会功能暂未开放。</red>");
            return;
        }

        Holder holder = new Holder(plugin);
        Inventory inv = Bukkit.createInventory(holder, SIZE,
                Msg.mm("<dark_gray>行会商会 <dark_gray>| <#ffd166>全服行会"));
        holder.inventory(inv);
        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, ForgeMenu.filler());
        }

        List<GuildService.Guild> list = new ArrayList<>(service.all());
        // 当周积分高的排前面，积分同就看金库厚薄
        list.sort(Comparator.comparingDouble(GuildService.Guild::weekTotal).reversed()
                .thenComparingLong(g -> -g.treasury));
        int shown = Math.min(list.size(), LIST_TO - LIST_FROM + 1);
        for (int i = 0; i < shown; i++) {
            inv.setItem(LIST_FROM + i, guildIcon(service, list.get(i), player));
        }
        if (list.isEmpty()) {
            inv.setItem(22, ForgeMenu.icon(Material.MINECART,
                    "<gray>还没有行会",
                    List.of("<gray>第一个建会的人：",
                            "<white>/zhujian guild create <会名>")));
        }

        inv.setItem(CLOSE_SLOT, ForgeMenu.icon(Material.BARRIER, "<red>关闭", List.of(
                "<gray>左键关闭",
                "<dark_gray>入会：会长 /zhujian guild invite <玩家>",
                "<dark_gray>存钱：/zhujian guild deposit <剑币数>")));

        player.openInventory(inv);
        player.playSound(player.getLocation(), org.bukkit.Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 1.2f);
    }

    /** 单个行会图标：自己的会亮金，别人的灰白 */
    private static ItemStack guildIcon(GuildService service, GuildService.Guild g, Player viewer) {
        boolean mine = service.hasGuild(viewer.getUniqueId())
                && service.guildOf(viewer.getUniqueId()) != null
                && service.guildOf(viewer.getUniqueId()).id.equals(g.id);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>会长: <white>" + nameOf(service, g.owner));
        lore.add("<gray>成员: <white>" + g.members.size() + "</white> <dark_gray>·</dark_gray> "
                + service.bonusLine(g));
        lore.add("<gray>当周积分: <#ffd166>" + Msg.fmt(g.weekTotal(), 0) + "</#ffd166>");
        lore.add("<gray>金库: " + com.forgemaster.econ.CoinRules.fmt(g.treasury));
        if (mine) {
            lore.add("");
            lore.add("<green>▶ 这是你所在的行会");
        } else if (!service.hasGuild(viewer.getUniqueId())) {
            lore.add("");
            lore.add("<dark_gray>想加入？让会长邀请你：guild invite");
        }
        return ForgeMenu.icon(mine ? Material.GOLDEN_PICKAXE : Material.IRON_PICKAXE,
                (mine ? "<#ffd166>" : "<white>") + g.name, lore);
    }

    private static String nameOf(GuildService service, java.util.UUID id) {
        org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(id);
        String n = off == null ? null : off.getName();
        return (n == null || n.isEmpty()) ? (id == null ? "?" : id.toString().substring(0, 8)) : n;
    }
}
