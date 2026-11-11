package com.forgemaster.anvil;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 强化重锤（v2.15 铁砧强化）。
 *
 * <p>手持重锤 <b>左键</b> 敲铁砧就是落锤，一次一锤：扣材料 + 扣剑币，
 * 然后按明牌概率结算。重锤本身不消耗、不掉耐久，是「工具」不是「消耗品」。</p>
 *
 * <p>判定顺序：</p>
 * <ol>
 *   <li>物品带 {@link Keys#HAMMER} 标记 → 认；</li>
 *   <li>否则看配置 {@code anvil-forge.hammer.allow-vanilla}（默认 true），
 *       材质在 {@code anvil-forge.hammer.items}（默认铁斧 / 重锤 / 石斧 / 铁镐）里也认。</li>
 * </ol>
 *
 * <p>第 2 条是为了让服主不必先发工具就能开玩：手里有把斧子就能敲。
 * 想强制「必须用插件发的那把」就把 {@code allow-vanilla} 关掉。</p>
 */
public final class Hammer {

    private Hammer() {
    }

    /** 配置里的重锤材质（老配置的单项键，读不到退回 MACE） */
    public static Material material(ForgingMasterPlugin plugin) {
        String raw = plugin.settings().str("anvil-forge.hammer.item", "MACE");
        Material m = raw == null ? null
                : Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
        if (m == null || !m.isItem()) {
            m = Material.MACE;
        }
        return m == null || !m.isItem() ? Material.IRON_INGOT : m;
    }

    /**
     * 所有能当重锤用的材质，第一项是 {@code /zhujian hammer} 发的那个。
     *
     * <p>v2.16.3 起改成列表：原版重锤（MACE）要打过试炼密室才拿得到，门槛太高，
     * 默认把 <b>铁斧</b> 放第一位当「铁匠重锤」，重锤仍然认。老配置只有单项
     * {@code anvil-forge.hammer.item} 的话，列表为空时自动退回那一项。</p>
     */
    public static List<Material> materials(ForgingMasterPlugin plugin) {
        List<Material> out = new ArrayList<>();
        for (String raw : plugin.settings().strList("anvil-forge.hammer.items", List.of())) {
            if (raw == null) {
                continue;
            }
            Material m = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
            if (m != null && m.isItem() && !out.contains(m)) {
                out.add(m);
            }
        }
        if (out.isEmpty()) {
            out.add(material(plugin));
        }
        return out;
    }

    /** 是否允许拿原版同材质物品当锤子 */
    public static boolean allowVanilla(org.bukkit.plugin.Plugin plugin) {
        if (plugin instanceof ForgingMasterPlugin fm) {
            return fm.settings().flag("anvil-forge.hammer.allow-vanilla", true);
        }
        return true;
    }

    /**
     * 这件物品能不能当强化锤用。
     *
     * @return true = 可以敲
     */
    public static boolean isHammer(ForgingMasterPlugin plugin, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            Byte mark = meta.getPersistentDataContainer().get(Keys.HAMMER, PersistentDataType.BYTE);
            if (mark != null && mark == 1) {
                return true;
            }
        }
        return allowVanilla(plugin) && materials(plugin).contains(item.getType());
    }

    /** 造一把强化锤（带 HAMMER 标记）；材质取 {@code hammer.items} 的第一项 */
    public static ItemStack create(ForgingMasterPlugin plugin) {
        ItemStack item = new ItemStack(materials(plugin).get(0));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Msg.mm("<#ffb84d><bold>铁匠重锤</bold>").decoration(TextDecoration.ITALIC, false));

        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(line("<dark_gray>—— 铁砧强化工具 ——</dark_gray>"));
        lore.add(line("<gray>① 主手拿剑 <white>右键铁砧</white>：把剑放上去"));
        lore.add(line("<gray>② 手持本锤 <white>左键铁砧</white>：落锤一次"));
        lore.add(line("<gray>③ <white>右键铁砧</white>：把剑取回来"));
        lore.add(line(""));
        lore.add(line("<gray>砧上会写明当前等级、成功率、碎裂率。"));
        lore.add(line("<gray>连击攒满触发 <#7ef2c8>无损连击强化</#7ef2c8>：下一锤必成不碎。"));
        lore.add(line("<gray>锤子不消耗，材料与剑币才消耗。"));
        lore.add(line("<dark_gray>铸剑大师 · 铁砧强化</dark_gray>"));
        meta.lore(lore);

        meta.getPersistentDataContainer().set(Keys.HAMMER, PersistentDataType.BYTE, (byte) 1);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static net.kyori.adventure.text.Component line(String mini) {
        return Msg.mm(mini).decoration(TextDecoration.ITALIC, false);
    }
}