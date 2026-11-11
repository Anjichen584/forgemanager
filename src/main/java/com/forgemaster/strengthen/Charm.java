package com.forgemaster.strengthen;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 强化符家族：护胚符 / 稳胚符 / 旺火符。
 *
 * <p>三种符共用同一套「放背包里、强化时按需自动消耗」的机制，
 * 各自的标记键、材质、文案都由本类统一给出，强化逻辑只负责判断
 * <i>什么时候</i>该消耗哪一张。</p>
 *
 * <table>
 *   <tr><th>id</th><th>名字</th><th>作用</th></tr>
 *   <tr><td>talisman</td><td>护胚符</td><td>碎裂判定命中时顶一次，把「碎」换成「降 1 级」</td></tr>
 *   <tr><td>steady</td><td>稳胚符</td><td>失败本该掉级时，这一次等级不降</td></tr>
 *   <tr><td>charm-kindle</td><td>旺火符</td><td>本次强化成功率 +N 个百分点</td></tr>
 * </table>
 *
 * <p>三者互不干扰、可同时生效，各自独立消耗。</p>
 */
public final class Charm {

    /** 护胚符（商会 id 与 PDC 标记都沿用这个老 id） */
    public static final String TALISMAN = "talisman";
    /** 稳胚符 */
    public static final String STEADY = "steady";
    /** 旺火符 */
    public static final String KINDLE = "charm-kindle";

    /** 全部 id，按商店展示顺序 */
    public static final List<String> ALL = List.of(TALISMAN, STEADY, KINDLE);

    private Charm() {
    }

    // ------------------------------------------------------------ id

    /** 把配置里写的东西归一成三种 id 之一；认不出返回 null */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "talisman", "护胚符", "hupifu" -> TALISMAN;
            case "steady", "稳胚符", "wenpeifu" -> STEADY;
            case "charm-kindle", "kindle", "charm_kindle", "旺火符", "wanghuofu" -> KINDLE;
            default -> null;
        };
    }

    /** 该 id 是否合法 */
    public static boolean valid(String id) {
        return normalize(id) != null;
    }

    /** 对应的 PDC 标记键 */
    public static NamespacedKey keyOf(String id) {
        String norm = normalize(id);
        if (norm == null) {
            return Keys.TALISMAN;
        }
        return switch (norm) {
            case STEADY -> Keys.CHARM_STEADY;
            case KINDLE -> Keys.CHARM_KINDLE;
            default -> Keys.TALISMAN;
        };
    }

    // ------------------------------------------------------------ 配置

    /** 该符是否启用（strengthen.talisman|steady|kindle.enabled） */
    public static boolean enabled(ForgingMasterPlugin plugin, String id) {
        String norm = normalize(id);
        if (norm == null) {
            return false;
        }
        String section = switch (norm) {
            case STEADY -> "strengthen.steady";
            case KINDLE -> "strengthen.kindle";
            default -> "strengthen.talisman";
        };
        return plugin.settings().flag(section + ".enabled", true);
    }

    /** 配置里的物品材质，读不到退回各符默认值 */
    public static Material material(ForgingMasterPlugin plugin, String id) {
        String norm = normalize(id);
        String section = switch (norm == null ? TALISMAN : norm) {
            case STEADY -> "strengthen.steady";
            case KINDLE -> "strengthen.kindle";
            default -> "strengthen.talisman";
        };
        String def = switch (norm == null ? TALISMAN : norm) {
            case STEADY -> "AMETHYST_SHARD";
            case KINDLE -> "BLAZE_POWDER";
            default -> "ECHO_SHARD";
        };
        String raw = plugin.settings().str(section + ".item", def);
        Material m = raw == null ? null
                : Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
        if (m == null || !m.isItem()) {
            m = Material.matchMaterial(def);
        }
        return m == null || !m.isItem() ? Material.ECHO_SHARD : m;
    }

    /** 旺火符加多少成功率（百分点转小数，默认 0.15） */
    public static double kindleBonus(ForgingMasterPlugin plugin) {
        double v = plugin.settings().num("strengthen.kindle.bonus", 0.15);
        return Math.max(0, Math.min(1.0, v));
    }

    // ------------------------------------------------------------ 造符

    /** 名字（MiniMessage） */
    public static String displayName(String id) {
        return switch (normalize(id) == null ? TALISMAN : normalize(id)) {
            case STEADY -> "<#6bd3ff>稳胚符";
            case KINDLE -> "<#ff9a3c>旺火符";
            default -> "<#9f7aea>护胚符";
        };
    }

    /** 纯名字（去掉颜色标签，给消息拼装用） */
    public static String plainName(String id) {
        return switch (normalize(id) == null ? TALISMAN : normalize(id)) {
            case STEADY -> "稳胚符";
            case KINDLE -> "旺火符";
            default -> "护胚符";
        };
    }

    /** 造一张符 */
    public static ItemStack create(ForgingMasterPlugin plugin, String id) {
        String norm = normalize(id) == null ? TALISMAN : normalize(id);
        ItemStack item = new ItemStack(material(plugin, norm));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Msg.mm(displayName(norm)).decoration(TextDecoration.ITALIC, false));

        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        switch (norm) {
            case STEADY -> {
                lore.add(line("<dark_gray>—— 强化保护 ——</dark_gray>"));
                lore.add(line("<gray>放在背包里，强化失败本该掉级时"));
                lore.add(line("<gray>自动消耗一张，这一次等级不降。"));
                lore.add(line(""));
                lore.add(line("<gray>托得住等级，托不住剑胚。"));
            }
            case KINDLE -> {
                long pct = Math.round(kindleBonus(plugin) * 100);
                lore.add(line("<dark_gray>—— 强化助力 ——</dark_gray>"));
                lore.add(line("<gray>放在背包里，强化时自动消耗一张，"));
                lore.add(line("<gray>这一次的成功率 <#ff9a3c>+" + pct + "</#ff9a3c> 个百分点。"));
                lore.add(line(""));
                lore.add(line("<gray>只旺这一炉火，锻完就灭。"));
            }
            default -> {
                lore.add(line("<dark_gray>—— 强化保护 ——</dark_gray>"));
                lore.add(line("<gray>放在背包里，强化失败本该碎剑胚时"));
                lore.add(line("<gray>自动消耗一张，把「碎」换成「降 1 级」。"));
                lore.add(line(""));
                lore.add(line("<gray>保得住剑胚，保不住等级。"));
            }
        }
        lore.add(line("<dark_gray>商会出品"));
        meta.lore(lore);

        meta.getPersistentDataContainer().set(keyOf(norm), PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    // ------------------------------------------------------------ 读写

    /** 这张物品是不是指定种类的符 */
    public static boolean isCharm(ItemStack item, String id) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte mark = meta.getPersistentDataContainer().get(keyOf(id), PersistentDataType.BYTE);
        return mark != null && mark == 1;
    }

    /** 把背包里的符统统加起来（三符各自统计用） */
    public static int count(Player player, String id) {
        if (player == null) {
            return 0;
        }
        String norm = normalize(id);
        if (norm == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack it : player.getInventory().getContents()) {
            if (isCharm(it, norm)) {
                total += it.getAmount();
            }
        }
        return total;
    }

    /** 消耗 n 张；不够返回 false 且不动背包 */
    public static boolean take(Player player, String id, int n) {
        if (player == null || n <= 0) {
            return false;
        }
        String norm = normalize(id);
        if (norm == null || count(player, norm) < n) {
            return false;
        }
        int remain = n;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && remain > 0; i++) {
            ItemStack it = contents[i];
            if (!isCharm(it, norm)) {
                continue;
            }
            int use = Math.min(remain, it.getAmount());
            it.setAmount(it.getAmount() - use);
            remain -= use;
            if (it.getAmount() <= 0) {
                player.getInventory().setItem(i, null);
            }
        }
        if (norm.equals(KINDLE)) {
            player.playSound(player.getLocation(), Sound.ITEM_FIRECHARGE_USE, 0.8f, 1.4f);
        } else {
            player.playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 0.7f, 1.3f);
        }
        return true;
    }

    private static net.kyori.adventure.text.Component line(String mini) {
        return Msg.mm(mini).decoration(TextDecoration.ITALIC, false);
    }
}
