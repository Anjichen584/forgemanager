package com.forgemaster.env;

import com.forgemaster.util.Msg;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;

/**
 * 环境修正面板（只读展示）。
 *
 * <p>单排 9 格箱子：天气 / 时辰 / 四项修正。所有数据来自 {@link EnvContext}，
 * 点击无效果（事件由 EnvPanelHolder 统一拦截）。</p>
 */
public final class EnvPanel {

    private EnvPanel() {
    }

    public static Inventory build(EnvContext env) {
        EnvPanelHolder holder = new EnvPanelHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, Msg.mm("<gradient:#ffd166:#ff7a18><bold>环境修正</bold></gradient>"));
        holder.inventory(inv);

        inv.setItem(4, icon(weatherMaterial(env), "<white>天气：" + env.weatherLabel() + "</white>",
                List.of("<gray>天气由锻造所在世界决定", "<gray>开局时固化，整局不变")));
        inv.setItem(13, icon(timeMaterial(env), "<white>时辰：" + env.timeLabel() + "</white>",
                List.of("<gray>按世界时间划分", "<gray>开局时固化，整局不变")));

        inv.setItem(10, icon(Material.BLAZE_POWDER, "<#ff9a3c>保温修正 " + pct(env.heatBonus) + "</#ff9a3c>",
                List.of("<gray>加热降温倍率 × <white>" + Msg.fmt(env.heatDecayMultiplier(), 2) + "</white>")));
        inv.setItem(12, icon(Material.GOLDEN_SWORD, "<#ffd166>淬火收益 " + pct(env.quenchBonus) + "</#ffd166>",
                List.of("<gray>淬火结算时总分 × <white>" + Msg.fmt(env.quenchMultiplier(), 2) + "</white>")));
        inv.setItem(14, icon(Material.PAPER, "<#7fd3ff>叠钢时长 " + signed(env.foldSecondsBonus, "s") + "</#7fd3ff>",
                List.of("<gray>叠钢基础时间的额外增减")));
        inv.setItem(16, icon(Material.IRON_BARS, "<#7ef2c8>开刃绿区 " + signed(env.grindWidthBonus, "") + "</#7ef2c8>",
                List.of("<gray>开刃绿区半宽的额外增减")));

        inv.setItem(22, icon(Material.BARRIER, "<red>关闭</red>", List.of("<gray>点击关闭面板")));
        return inv;
    }

    private static Material weatherMaterial(EnvContext env) {
        return switch (env.weatherKey) {
            case "storm" -> Material.TRIDENT;
            case "rain" -> Material.WATER_BUCKET;
            default -> Material.GLOWSTONE_DUST;
        };
    }

    private static Material timeMaterial(EnvContext env) {
        return switch (env.timeKey) {
            case "dawn" -> Material.SUNFLOWER;
            case "dusk" -> Material.ORANGE_DYE;
            case "night" -> Material.CLOCK;
            default -> Material.YELLOW_DYE;
        };
    }

    private static String pct(double v) {
        return (v > 0 ? "+" : "") + Msg.fmt(v * 100, 0) + "%";
    }

    private static String signed(double v, String unit) {
        return (v > 0 ? "+" : "") + Msg.fmt(v, 1) + unit;
    }

    private static ItemStack icon(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Msg.mm(name).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
            for (String l : lore) {
                lines.add(Msg.mm(l).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            }
            meta.lore(lines);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 面板持有者：只用于识别界面，点击由 ForgeListener 忽略或关闭 */
    public static final class EnvPanelHolder implements org.bukkit.inventory.InventoryHolder {
        private Inventory inventory;

        void inventory(Inventory inv) {
            this.inventory = inv;
        }

        @Override
        public org.bukkit.inventory.Inventory getInventory() {
            return inventory;
        }
    }
}
