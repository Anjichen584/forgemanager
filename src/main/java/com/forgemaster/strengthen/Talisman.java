package com.forgemaster.strengthen;

import com.forgemaster.ForgingMasterPlugin;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 护胚符：强化失败的碎裂判定命中时自动顶一次。
 *
 * <p>没它剑胚就没了；有它剑胚保住，但照样降 1 级——所以它不是免死金牌，
 * 只是把「归零」换成「掉一级」。</p>
 *
 * <p>v2.5 起符扩成三兄弟（护胚 / 稳胚 / 旺火），公共逻辑搬进 {@link Charm}；
 * 本类保留成「护胚符门面」，老调用方（命令、自检、商会）不用改。</p>
 *
 * <p>来源只有商会，不进掉落表。</p>
 */
public final class Talisman {

    private Talisman() {
    }

    /** 配置里的物品材质，读不到就退回 ECHO_SHARD */
    public static Material material(ForgingMasterPlugin plugin) {
        return Charm.material(plugin, Charm.TALISMAN);
    }

    public static String displayName() {
        return Charm.displayName(Charm.TALISMAN);
    }

    /** 造一张护胚符 */
    public static ItemStack create(ForgingMasterPlugin plugin) {
        return Charm.create(plugin, Charm.TALISMAN);
    }

    /** 这张物品是不是护胚符 */
    public static boolean isTalisman(ItemStack item) {
        return Charm.isCharm(item, Charm.TALISMAN);
    }

    /** 背包里一共有几张 */
    public static int count(Player player) {
        return Charm.count(player, Charm.TALISMAN);
    }

    /** 消耗 n 张；不够返回 false 且不动背包 */
    public static boolean take(Player player, int n) {
        return Charm.take(player, Charm.TALISMAN, n);
    }
}
