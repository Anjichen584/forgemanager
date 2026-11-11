package com.forgemaster.material;

import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 材料分级的读写与消耗。矿石靠 PDC 上的 {@code grade} 键区分档次。
 */
public final class MaterialGrades {

    private MaterialGrades() {
    }

    /** 给一个物品打上等级标记（返回新物品，原物品不改） */
    public static ItemStack stamp(ItemStack base, MaterialGrade grade) {
        if (base == null || base.getType().isAir()) {
            return base;
        }
        ItemStack item = base.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        if (grade == MaterialGrade.COMMON) {
            meta.getPersistentDataContainer().remove(Keys.GRADE);
            meta.displayName(null);
            meta.lore(null);
            item.setItemMeta(meta);
            return item;
        }
        meta.getPersistentDataContainer().set(Keys.GRADE, PersistentDataType.STRING, grade.name());
        meta.displayName(Msg.mm(grade.colored() + " <white>" + pretty(base.getType()))
                .decoration(TextDecoration.ITALIC, false));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(Msg.mm("<dark_gray>—— 锻造材料 ——</dark_gray>").decoration(TextDecoration.ITALIC, false));
        lore.add(Msg.mm("<gray>档次: " + grade.colored()).decoration(TextDecoration.ITALIC, false));
        if (grade.scoreBonus() > 0) {
            lore.add(Msg.mm("<gray>成品上限: <#" + grade.hex().substring(1) + ">+"
                    + String.format("%.0f", grade.scoreBonus()) + "%</#"
                    + grade.hex().substring(1) + ">").decoration(TextDecoration.ITALIC, false));
        }
        if (grade.extraAffixes() > 0) {
            lore.add(Msg.mm("<gray>额外词条: <#" + grade.hex().substring(1) + ">+" + grade.extraAffixes()
                    + "</#" + grade.hex().substring(1) + ">").decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Msg.mm("<dark_gray>锻造时自动优先消耗高档料</dark_gray>").decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** 读等级（无标记 = 普通） */
    public static MaterialGrade gradeOf(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return MaterialGrade.COMMON;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return MaterialGrade.COMMON;
        }
        String raw = meta.getPersistentDataContainer().get(Keys.GRADE, PersistentDataType.STRING);
        return MaterialGrade.byName(raw);
    }

    /**
     * 统计玩家背包里某种材料的各档数量。
     *
     * @return 等级 → 数量
     */
    public static Map<MaterialGrade, Integer> countByGrade(Player player, Material material) {
        Map<MaterialGrade, Integer> map = new java.util.EnumMap<>(MaterialGrade.class);
        for (ItemStack it : player.getInventory().getContents()) {
            if (it == null || it.getType() != material) {
                continue;
            }
            map.merge(gradeOf(it), it.getAmount(), Integer::sum);
        }
        return map;
    }

    /** 合计数量 */
    public static int totalCount(Player player, Material material) {
        int sum = 0;
        for (int n : countByGrade(player, material).values()) {
            sum += n;
        }
        return sum;
    }

    /**
     * 按「从高到低」的优先级扣材料，返回本次实际使用的最高等级。
     *
     * @return 实际使用的等级；材料不足返回 null
     */
    public static MaterialGrade consume(Player player, Material material, int amount) {
        if (amount <= 0) {
            return MaterialGrade.COMMON;
        }
        if (totalCount(player, material) < amount) {
            return null;
        }
        int left = amount;
        MaterialGrade best = null;
        // 从最高档往下扣
        MaterialGrade[] desc = MaterialGrade.values();
        for (int i = desc.length - 1; i >= 0 && left > 0; i--) {
            MaterialGrade g = desc[i];
            org.bukkit.inventory.PlayerInventory inv = player.getInventory();
            ItemStack[] contents = inv.getContents();
            for (int slot = 0; slot < contents.length && left > 0; slot++) {
                ItemStack it = contents[slot];
                if (it == null || it.getType() != material || gradeOf(it) != g) {
                    continue;
                }
                int take = Math.min(left, it.getAmount());
                it.setAmount(it.getAmount() - take);
                if (it.getAmount() <= 0) {
                    inv.setItem(slot, null);
                } else {
                    inv.setItem(slot, it);
                }
                left -= take;
                if (best == null || g.atLeast(best)) {
                    best = g;
                }
            }
        }
        return best == null ? MaterialGrade.COMMON : best;
    }

    /**
     * 材料中文名表。锻造常用的矿石、锭、块都在这里，查不到再回退英文。
     */
    private static final Map<Material, String> CN_NAMES = new java.util.EnumMap<>(Material.class);

    static {
        // 铁
        put(Material.IRON_INGOT, "铁锭");
        put(Material.IRON_NUGGET, "铁粒");
        put(Material.IRON_BLOCK, "铁块");
        put(Material.RAW_IRON, "粗铁");
        put(Material.IRON_ORE, "铁矿石");
        put(Material.DEEPSLATE_IRON_ORE, "深层铁矿石");
        // 铜
        put(Material.COPPER_INGOT, "铜锭");
        put(Material.COPPER_BLOCK, "铜块");
        put(Material.RAW_COPPER, "粗铜");
        put(Material.COPPER_ORE, "铜矿石");
        put(Material.DEEPSLATE_COPPER_ORE, "深层铜矿石");
        // 金
        put(Material.GOLD_INGOT, "金锭");
        put(Material.GOLD_NUGGET, "金粒");
        put(Material.GOLD_BLOCK, "金块");
        put(Material.RAW_GOLD, "粗金");
        put(Material.GOLD_ORE, "金矿石");
        put(Material.DEEPSLATE_GOLD_ORE, "深层金矿石");
        put(Material.NETHER_GOLD_ORE, "下界金矿石");
        // 钻石
        put(Material.DIAMOND, "钻石");
        put(Material.DIAMOND_BLOCK, "钻石块");
        put(Material.DIAMOND_ORE, "钻石矿石");
        put(Material.DEEPSLATE_DIAMOND_ORE, "深层钻石矿石");
        // 下界合金
        put(Material.NETHERITE_INGOT, "下界合金锭");
        put(Material.NETHERITE_SCRAP, "下界合金碎片");
        put(Material.NETHERITE_BLOCK, "下界合金块");
        put(Material.ANCIENT_DEBRIS, "远古残骸");
        put(Material.NETHER_STAR, "下界之星");
        // 其他常见料
        put(Material.COAL, "煤炭");
        put(Material.CHARCOAL, "木炭");
        put(Material.COAL_BLOCK, "煤炭块");
        put(Material.EMERALD, "绿宝石");
        put(Material.LAPIS_LAZULI, "青金石");
        put(Material.REDSTONE, "红石");
        put(Material.QUARTZ, "下界石英");
        put(Material.FLINT, "燧石");
        put(Material.OBSIDIAN, "黑曜石");
    }

    private static void put(Material mat, String cn) {
        if (mat != null) {
            CN_NAMES.put(mat, cn);
        }
    }

    /** 材料物品名（给玩家看，中文优先） */
    public static String pretty(Material mat) {
        if (mat == null) {
            return "未知材料";
        }
        String cn = CN_NAMES.get(mat);
        if (cn != null) {
            return cn;
        }
        String n = mat.name().toLowerCase().replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        for (String w : n.split(" ")) {
            if (w.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    /** 材料物品名（按枚举 id 取，查不到时原样返回 id） */
    public static String pretty(String materialId) {
        if (materialId == null || materialId.isBlank()) {
            return "未知材料";
        }
        Material mat = Material.matchMaterial(materialId);
        return mat == null ? materialId : pretty(mat);
    }

    /** 手上材料升级一档（管理命令用） */
    public static boolean upgrade(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            return false;
        }
        MaterialGrade cur = gradeOf(hand);
        if (cur == MaterialGrade.CELESTIAL) {
            return false;
        }
        ItemStack next = stamp(hand, cur.upgraded());
        player.getInventory().setItemInMainHand(next);
        return true;
    }
}