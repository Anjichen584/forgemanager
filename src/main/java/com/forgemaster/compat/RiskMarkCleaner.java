package com.forgemaster.compat;

import com.forgemaster.ForgingMasterPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * 极限锻造残留清洗（v2.26.7）。
 *
 * <h2>为什么需要</h2>
 * v2.26.1 整模块移除了极限锻造（{@code forge/RiskMode}，随 {@code cc0ef73} 删除），
 * 但它当年给成品打过两类痕迹，还留在盘上的旧装备里：
 * <ul>
 *   <li>PDC 键 {@code forgemaster:risk_made}（BYTE 1）——现在全仓没有任何代码读它，纯死数据；</li>
 *   <li>Lore 末尾一行「<red>◆ 极限锻造</red>（险中求胜）」——玩家可见，且描述的功能已不存在。</li>
 * </ul>
 *
 * <p>本类做的是<b>清洗不是销毁</b>：剥掉这两类痕迹，物品本体（强化/词条/耐久）原样保留——
 * 极限锻造产物本来就是一个普通锻造品加一个标记，不该连剑一起没收。玩家可见的 Lore
 * 只按纯文本「极限锻造」特征匹配当年那一行，不会误伤其他内容。</p>
 *
 * <p>扫描口径与 {@link LegacyItemRepair} 完全一致：在线玩家（背包 / 盔甲 / 副手 / 末影箱）
 * 与已加载区块的容器；不为清洗强制加载区块。全程主线程、幂等（无标记不动 meta）。</p>
 */
public final class RiskMarkCleaner {

    private final ForgingMasterPlugin plugin;
    private final NamespacedKey riskMade;

    /** 当年 RiskMode 落在 Lore 里的特征词（纯文本匹配用） */
    private static final String LORE_MARK = "极限锻造";

    public RiskMarkCleaner(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.riskMade = new NamespacedKey(plugin, "risk_made");
    }

    /** 一件物品是否带极限锻造痕迹（PDC 标记或 Lore 特征行） */
    public boolean marked(ItemStack item) {
        if (item == null || item.getType().isAir() || !plugin.items().isForged(item)) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        if (meta.getPersistentDataContainer().has(riskMade, PersistentDataType.BYTE)) {
            return true;
        }
        List<Component> lore = meta.lore();
        if (lore == null) {
            return false;
        }
        for (Component line : lore) {
            if (plain(line).contains(LORE_MARK)) {
                return true;
            }
        }
        return false;
    }

    /** 清洗一件物品：剥 PDC 标记 + 删 Lore 特征行。返回是否动了 meta（幂等） */
    public boolean clean(ItemStack item) {
        if (!marked(item)) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        meta.getPersistentDataContainer().remove(riskMade);
        List<Component> lore = meta.lore();
        if (lore != null) {
            List<Component> kept = new ArrayList<>();
            for (Component line : lore) {
                if (!plain(line).contains(LORE_MARK)) {
                    kept.add(line);
                }
            }
            meta.lore(kept.isEmpty() ? null : kept);
        }
        item.setItemMeta(meta);
        return true;
    }

    /** 扫一个在线玩家（背包 / 盔甲 / 副手 / 末影箱），返回清洗件数 */
    public int cleanPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return 0;
        }
        int fixed = 0;
        fixed += cleanInventory(player.getInventory());
        for (ItemStack it : player.getInventory().getArmorContents()) {
            if (clean(it)) {
                fixed++;
            }
        }
        if (clean(player.getInventory().getItemInOffHand())) {
            fixed++;
        }
        try {
            fixed += cleanInventory(player.getEnderChest());
        } catch (RuntimeException ex) {
            plugin.getLogger().fine("末影箱扫描失败（已跳过）：" + ex);
        }
        return fixed;
    }

    /** 扫全服在线玩家 */
    public int cleanOnline() {
        int fixed = 0;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            fixed += cleanPlayer(p);
        }
        return fixed;
    }

    /** 扫已加载区块的容器（不强制加载） */
    public int cleanLoadedContainers() {
        int fixed = 0;
        for (var world : plugin.getServer().getWorlds()) {
            for (var chunk : world.getLoadedChunks()) {
                for (var state : chunk.getTileEntities()) {
                    if (state instanceof org.bukkit.inventory.InventoryHolder holder) {
                        Inventory inv = holder.getInventory();
                        if (inv != null) {
                            fixed += cleanInventory(inv);
                        }
                    }
                }
            }
        }
        return fixed;
    }

    /** 扫一个容器/背包 */
    public int cleanInventory(Inventory inv) {
        if (inv == null) {
            return 0;
        }
        int fixed = 0;
        for (ItemStack it : inv.getContents()) {
            if (clean(it)) {
                fixed++;
            }
        }
        return fixed;
    }

    // ------------------------------------------------------------ 内部

    private static String plain(Component c) {
        return com.forgemaster.util.Msg.plain(c);
    }
}
