package com.forgemaster.ranking;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.gui.ForgeMenu;
import com.forgemaster.quality.Quality;
import com.forgemaster.stats.StatsStore;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 排行榜 GUI 渲染（纯展示）。上方 45 格为榜单，下方一行为维度切换与关闭。
 *
 * <p>维度按钮占了底行 45~48 与 50（49 是关闭、53 是我的成绩）。
 * 「强化」维度读的是历史最高等级（v2.5 起落库），离线玩家也有名次。</p>
 */
public final class RankMenu {

    public static final int SIZE = 54;
    /** 榜单格子数（前 5 行） */
    public static final int LIST_SIZE = 45;
    /** 维度切换按钮的槽位（底行 45~48 + 50，跳过 49 的关闭），顺序与 Metric 常量顺序一一对应 */
    public static final int[] METRIC_SLOTS = {45, 46, 47, 48, 50};
    public static final int CLOSE_SLOT = 49;
    public static final int MY_SLOT = 53;

    private final ForgingMasterPlugin plugin;

    public RankMenu(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    public Inventory build(Player viewer, Metric metric) {
        // 强化维度关掉时回落到剑币榜，别打开一个空维度
        if (metric != null && metric.online() && !StrengthenSnapshot.enabled(plugin)) {
            metric = Metric.COINS;
        }
        RankHolder holder = new RankHolder(metric);
        Inventory inv = Bukkit.createInventory(holder, SIZE,
                Msg.mm("<dark_gray>铸剑大师 <dark_gray>| <#ffd166>排行 · " + metric.label()));
        holder.inventory(inv);

        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, ForgeMenu.filler());
        }

        List<Row> rows = new ArrayList<>();
        int rank = 1;
        for (var row : Rankings.top(plugin.stats(), metric, LIST_SIZE)) {
            rows.add(new Row(rank++, row));
        }
        if (rows.isEmpty()) {
            inv.setItem(22, ForgeMenu.icon(Material.BARRIER, "<gray>暂无数据",
                    metric.online()
                            ? List.of("<dark_gray>此刻在线的玩家背包里没有强化过的锻造品。",
                                      "<dark_gray>这一维度看历史最高等级，已下线的玩家也在榜上。")
                            : List.of("<dark_gray>还没有人在这一项上留下记录。")));
        }
        for (Row r : rows) {
            inv.setItem(r.rank - 1, entryIcon(r.rank, r.row, metric));
        }

        for (int i = 0; i < Metric.values().length && i < METRIC_SLOTS.length; i++) {
            Metric m = Metric.values()[i];
            // 强化维度关掉时不画这个按钮，槽位留成填充物
            if (m.online() && !StrengthenSnapshot.enabled(plugin)) {
                continue;
            }
            boolean current = m == metric;
            String name = (current ? "<gradient:#ffd166:#ff7a18><bold>▶ " : "<#9aa0a6>")
                    + m.label() + (current ? "</bold></gradient>" : "</#9aa0a6>");
            List<String> lore = new ArrayList<>();
            lore.add(current ? "<gray>当前榜单" : "<yellow>▶ 左键切换");
            if (m.online()) {
                lore.add("<dark_gray>历史最高等级 · 只涨不跌");
            }
            inv.setItem(METRIC_SLOTS[i], ForgeMenu.icon(m.icon(), name, lore));
        }
        inv.setItem(CLOSE_SLOT, ForgeMenu.icon(Material.BARRIER, "<red>关闭", List.of()));

        if (viewer != null) {
            int mine = Rankings.rankOf(plugin.stats(), metric, viewer.getUniqueId());
            StatsStore.Stats st = plugin.stats().get(viewer.getUniqueId());
            List<String> lore = new ArrayList<>();
            lore.add("<gray>你的名次: <white>" + (mine < 0 ? "未上榜" : "#" + mine));
            lore.add("<gray>" + metric.display(viewer.getUniqueId(), st));
            inv.setItem(MY_SLOT, ForgeMenu.icon(Material.PLAYER_HEAD, "<#7ef2c8>我的成绩", lore));
        }
        return inv;
    }

    private record Row(int rank, Rankings.Row row) {
    }

    private ItemStack entryIcon(int rank, Rankings.Row row, Metric metric) {
        StatsStore.Stats st = row.stats();
        Quality best = Quality.byName(st.bestQuality);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + metric.label() + ": <white>" + metric.display(row.uuid(), st));
        if (metric.online()) {
            StrengthenSnapshot.Best b = StrengthenSnapshot.bestOf(row.uuid());
            if (b != null) {
                lore.add("<gray>最佳: <white>【" + b.itemName() + "】 <dark_gray>· <gray>"
                        + (b.materialName().isEmpty() ? "未知材料" : b.materialName()));
                lore.add("<gray>离上限还差 <white>+" + Math.max(0, b.cap() - b.level())
                        + " <dark_gray>（+" + b.cap() + " 封顶）");
            }
            lore.add("<dark_gray>历史最高 · 掉级不回退");
        }
        lore.add("<gray>称号: <#ffd166>" + StatsStore.titleOf(st.reputation)
                + " <dark_gray>· <gray>成就 <white>" + st.achievements.size());
        lore.add("<gray>锻造 <white>" + st.forged + " <gray>次"
                + (best == null ? "" : " <dark_gray>· 最佳 " + best.colored()));
        return ForgeMenu.icon(rankMaterial(rank), medal(rank) + " <white>" + row.name(), lore);
    }

    /** 前三名用金银铜图标，其余统一用纸 */
    private static Material rankMaterial(int rank) {
        return switch (rank) {
            case 1 -> Material.GOLD_BLOCK;
            case 2 -> Material.IRON_BLOCK;
            case 3 -> Material.COPPER_BLOCK;
            default -> Material.PAPER;
        };
    }

    private static String medal(int rank) {
        return switch (rank) {
            case 1 -> "<#ffd166>#1";
            case 2 -> "<#d0d7de>#2";
            case 3 -> "<#e08a52>#3";
            default -> "<gray>#" + rank;
        };
    }
}
