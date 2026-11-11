package com.forgemaster.spectrum;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.form.SwordForm;
import com.forgemaster.gui.ForgeMenu;
import com.forgemaster.quality.Quality;
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
 * 名剑谱面板：条目列表 + 各项条件当前值/目标值 + 领取。
 *
 * <p>静态快照界面（打开时画一次），点领取或领完刷新就重画一次，
 * 不挂定时任务（照 ContestGui 的省任务做法）。</p>
 *
 * <p>槽位（27 格，本类独占）：</p>
 * <pre>
 *   0  1  2  3  4  5  6  7  8     4 = 谱面状态（已领 N/M）
 *   9 10 11 12 13 14 15 16 17     10/12/14/16 = 条目（最多 4 条，超出只显示前 4）
 *  18 19 20 21 22 23 24 25 26     26 = 关闭
 * </pre>
 */
public final class SpectrumGui {

    public static final int SIZE = 27;

    /** 谱面状态 */
    public static final int STATUS_SLOT = 4;
    /** 条目槽位 */
    public static final int[] ENTRY_SLOTS = {10, 12, 14, 16};
    /** 关闭 */
    public static final int CLOSE_SLOT = 26;

    /** 面板持有者 */
    public static final class Holder implements InventoryHolder {

        private final ForgingMasterPlugin plugin;
        private final UUID viewer;
        /** 槽位 → 条目 id（点击路由用） */
        private final java.util.Map<Integer, String> slotEntries = new java.util.LinkedHashMap<>();
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

        java.util.Map<Integer, String> slotEntries() {
            return slotEntries;
        }

        /** 槽位上的条目（没有返回 null） */
        public SpectrumService.Entry entryAt(int slot) {
            String id = slotEntries.get(slot);
            return id == null ? null : plugin.spectrum() == null ? null : plugin.spectrum().entry(id);
        }

        void inventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private SpectrumGui() {
    }

    /** 打开名剑谱面板 */
    public static void open(ForgingMasterPlugin plugin, Player player) {
        if (plugin == null || player == null) {
            return;
        }
        SpectrumGuiRouter.ensure(plugin);
        SpectrumService service = plugin.spectrum();
        if (service == null || !service.enabled()) {
            Msg.prefix(player, "<red>名剑谱暂未开放。</red>");
            return;
        }

        Holder holder = new Holder(plugin, player.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, SIZE,
                Msg.mm("<dark_gray>名剑谱 <dark_gray>| <#ffd166>名剑"));
        holder.inventory(inv);
        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, ForgeMenu.filler());
        }

        UUID uuid = player.getUniqueId();
        List<SpectrumService.Entry> entries = service.entries();

        inv.setItem(STATUS_SLOT, ForgeMenu.icon(Material.WRITABLE_BOOK,
                "<#ffd166><bold>名剑谱</bold>",
                List.of("<gray>给顶级神兵绑的任务线。",
                        "<gray>条件全由你的锻造档案推导，",
                        "<dark_gray>不看你背包里有什么。",
                        "",
                        "<gray>已留名: <#ffd166>" + service.claimedCount(uuid)
                                + "</#ffd166> / <white>" + entries.size(),
                        service.titleOf(uuid).isEmpty()
                                ? "<dark_gray>称号: 尚无"
                                : "<gray>称号: <#ffd166>" + service.titleOf(uuid) + "</#ffd166>")));

        int max = Math.min(entries.size(), ENTRY_SLOTS.length);
        for (int i = 0; i < max; i++) {
            SpectrumService.Entry e = entries.get(i);
            int slot = ENTRY_SLOTS[i];
            inv.setItem(slot, entryIcon(plugin, service, e, uuid));
            holder.slotEntries().put(slot, e.id);
        }

        inv.setItem(CLOSE_SLOT, ForgeMenu.icon(Material.BARRIER, "<red>关闭", List.of(
                "<gray>左键关闭")));

        player.openInventory(inv);
    }

    /** 单个条目的图标：条件逐项 当前/目标，全满足才亮（换金剑图标） */
    private static ItemStack entryIcon(ForgingMasterPlugin plugin, SpectrumService service,
                                       SpectrumService.Entry e, UUID uuid) {
        boolean claimed = service.claimed(uuid, e.id);
        boolean ready = service.canClaim(uuid, e);
        List<String> lore = new ArrayList<>();
        for (String line : e.lore) {
            lore.add(line);
        }
        lore.add("");
        for (SpectrumService.Condition c : e.conditions) {
            boolean ok = service.met(uuid, c);
            String line;
            if (c.type.equals("quality")) {
                int cur = service.currentOf(uuid, c);
                line = service.labelOf(c) + " <dark_gray>（当前: "
                        + (cur <= 0 ? "—" : Quality.values()[Math.min(cur, Quality.values().length - 1)].colored())
                        + "）";
            } else {
                line = service.labelOf(c) + " <white>" + service.currentOf(uuid, c)
                        + "</white><dark_gray>/</dark_gray><white>" + service.targetOf(c) + "</white>";
            }
            lore.add((ok ? "<green>· " : "<gray>· ") + line + (ok ? " <green>（达成）" : "") + "</green>");
        }
        lore.add("");
        List<String> rewards = new ArrayList<>();
        if (e.form != null) {
            SwordForm f = plugin.forms() == null ? null : plugin.forms().get(e.form);
            rewards.add("<gray>剑型: <#ffd166><bold>" + (f == null ? e.form : f.display())
                    + "</bold></#ffd166> <dark_gray>（专属）</dark_gray>");
        }
        if (e.title != null) {
            rewards.add("<gray>称号: <#ffd166>" + e.title + "</#ffd166>");
        }
        if (e.tombFeeDiscount < 1.0) {
            rewards.add("<gray>剑冢挑战费: <#ffd166>" + Math.round(e.tombFeeDiscount * 100) + "%</#ffd166>");
        }
        if (e.coins > 0) {
            rewards.add("<gray>剑币: <#ffd166>+" + e.coins + "</#ffd166>");
        }
        lore.addAll(rewards);
        lore.add("");
        if (claimed) {
            lore.add("<green>已留名（奖励已领取）");
        } else if (ready) {
            lore.add("<yellow>▶ 条件齐备！左键领取");
        } else {
            lore.add("<dark_gray>条件未满足");
        }
        Material icon = claimed || ready ? Material.GOLDEN_SWORD : e.icon;
        String name = claimed ? "<gray><st>" + e.display + "</st> <green>✔"
                : ready ? "<#ffd166><bold>" + e.display + "</bold></#ffd166>"
                : "<#b0b8c4>" + e.display;
        return ForgeMenu.icon(icon, name, lore);
    }

    /**
     * 槽位 → 用途。给自检用：断言没有两个功能撞同一格，不用真开游戏。
     */
    public static java.util.Map<Integer, String> slotUsage() {
        java.util.Map<Integer, String> map = new java.util.LinkedHashMap<>();
        map.put(STATUS_SLOT, "谱面状态");
        for (int slot : ENTRY_SLOTS) {
            map.put(slot, "条目");
        }
        map.put(CLOSE_SLOT, "关闭");
        return map;
    }
}
