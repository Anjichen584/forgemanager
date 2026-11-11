package com.forgemaster.contests;

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
import java.util.List;
import java.util.UUID;

/**
 * 锻造大赛面板：赛况总览 + 「实时观战」入口。
 *
 * <p>这个界面是静态快照（打开时画一次），需要实时数据就点 {@link #WATCH_SLOT} 进观战面板——
 * 那边有自己的每秒刷新任务，这边不挂任务，省得两个界面抢任务。</p>
 *
 * <p>槽位（27 格，本类独占，和主菜单的 24「锻造大赛」入口不是同一个界面）：</p>
 * <pre>
 *   0  1  2  3  4  5  6  7  8     4 = 赛况状态
 *   9 10 11 12 13 14 15 16 17     10 = 剑胚/种子, 12 = 参赛人数, 14 = 剩余时间, 16 = 当前领先
 *  18 19 20 21 22 23 24 25 26     18 = 榜单前五, 22 = 实时观战, 26 = 关闭
 * </pre>
 */
public final class ContestGui {

    public static final int SIZE = 27;

    /** 赛况状态（进行中 / 下一场倒计时） */
    public static final int STATUS_SLOT = 4;
    /** 剑胚 + 种子 */
    public static final int MATERIAL_SLOT = 10;
    /** 参赛人数 / 交剑数 */
    public static final int COUNT_SLOT = 12;
    /** 剩余时间 */
    public static final int TIME_SLOT = 14;
    /** 当前领先者 */
    public static final int LEADER_SLOT = 16;
    /** 榜单前五 */
    public static final int BOARD_SLOT = 18;
    /** 实时观战入口 */
    public static final int WATCH_SLOT = 22;
    /** 关闭 */
    public static final int CLOSE_SLOT = 26;

    /** 面板持有者 */
    public static final class Holder implements InventoryHolder {

        private final ForgingMasterPlugin plugin;
        private final UUID viewer;
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

        void inventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private ContestGui() {
    }

    /** 打开赛况面板 */
    public static void open(ForgingMasterPlugin plugin, Player player) {
        if (plugin == null || player == null) {
            return;
        }
        ContestGuiRouter.ensure(plugin);
        ContestManager manager = plugin.contests();
        if (manager == null) {
            Msg.prefix(player, "<red>大赛系统没有初始化。");
            return;
        }

        Holder holder = new Holder(plugin, player.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, SIZE,
                Msg.mm("<dark_gray>锻造大赛 <dark_gray>| <#ffd166>赛况"));
        holder.inventory(inv);
        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, ForgeMenu.filler());
        }

        boolean on = manager.running();
        long left = manager.remainingMillis();
        var asking = manager.materialAsking();
        String code = manager.contestCode();

        inv.setItem(STATUS_SLOT, ForgeMenu.icon(
                on ? Material.GOLDEN_SWORD : Material.CLOCK,
                on ? "<gradient:#ffd166:#ff7a18><bold>大赛进行中</bold></gradient>"
                        : "<gray><bold>没有进行中的大赛</bold>",
                List.of(Msg.plain(Msg.mm(manager.statusLine())),
                        "",
                        on ? "<yellow>▶ 看实时观战点下面那个眼睛"
                                : "<dark_gray>开赛会自动广播，到时再来")));

        inv.setItem(MATERIAL_SLOT, ForgeMenu.icon(Material.ANVIL, "<#ffd166>本场题目",
                List.of("<gray>剑胚: " + (asking == null ? "<gray>—" : asking.display()),
                        "<gray>种子: <white>" + (code == null ? "—" : code),
                        "<dark_gray>全员同一份火候")));

        inv.setItem(COUNT_SLOT, ForgeMenu.icon(Material.PLAYER_HEAD, "<#ffd166>参赛人数",
                List.of("<gray>交剑: <white>" + manager.submittedCount(),
                        "<gray>在打: <white>" + manager.activeCount(),
                        "<gray>合计: <white>" + manager.participantCount())));

        inv.setItem(TIME_SLOT, ForgeMenu.icon(Material.CLOCK, "<#ffd166>剩余时间",
                List.of(on ? "<gray>本场还剩 <white>" + (left / 1000) + " 秒"
                        : "<dark_gray>没在赛中")));

        String leader = manager.leaderName();
        inv.setItem(LEADER_SLOT, ForgeMenu.icon(Material.GOLDEN_HELMET, "<#ffd166>当前领先",
                leader == null
                        ? List.of("<gray>还没人交剑")
                        : List.of("<white>" + leader + "</white>",
                        "<gray>分数: <white>" + Msg.fmt(manager.leaderScore(), 1))));

        List<String> board = new ArrayList<>();
        List<String> lines = manager.leaderboard();
        if (lines.isEmpty()) {
            board.add("<gray>榜单还空着");
        } else {
            for (int i = 0; i < Math.min(5, lines.size()); i++) {
                board.add(lines.get(i));
            }
            if (lines.size() > 5) {
                board.add("<dark_gray>……另有 " + (lines.size() - 5) + " 人");
            }
        }
        inv.setItem(BOARD_SLOT, ForgeMenu.icon(Material.PAPER, "<#ffd166>榜单前五", board));

        inv.setItem(WATCH_SLOT, watchEntryIcon(plugin));

        inv.setItem(CLOSE_SLOT, ForgeMenu.icon(Material.BARRIER, "<red>关闭", List.of(
                "<gray>左键关闭")));

        player.openInventory(inv);
    }

    /**
     * 槽位 → 用途。给自检用：断言没有两个功能撞同一格、断言观战入口就在
     * {@link #WATCH_SLOT}，不用真开游戏。
     */
    public static java.util.Map<Integer, String> slotUsage() {
        java.util.Map<Integer, String> map = new java.util.LinkedHashMap<>();
        map.put(STATUS_SLOT, "赛况状态");
        map.put(MATERIAL_SLOT, "剑胚 / 种子");
        map.put(COUNT_SLOT, "参赛人数");
        map.put(TIME_SLOT, "剩余时间");
        map.put(LEADER_SLOT, "当前领先");
        map.put(BOARD_SLOT, "榜单前五");
        map.put(WATCH_SLOT, "实时观战");
        map.put(CLOSE_SLOT, "关闭");
        return map;
    }

    /** 观战入口图标：别的界面（比如主菜单）想放同一颗按钮时直接拿 */
    public static ItemStack watchEntryIcon(ForgingMasterPlugin plugin) {
        ContestManager manager = plugin == null ? null : plugin.contests();
        boolean on = manager != null && manager.running();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>每个参赛者一行：阶段 / 分数 / 名次。");
        lore.add("<gray>已交剑的能看到成品品质。");
        lore.add("<dark_gray>面板每秒自己刷新");
        lore.add("");
        lore.add(on ? "<yellow>▶ 左键进观战" : "<dark_gray>▶ 没有进行中的大赛");
        return ForgeMenu.icon(on ? Material.ENDER_EYE : Material.ENDER_PEARL,
                "<#7fd3ff><bold>实时观战</bold>", lore);
    }
}
