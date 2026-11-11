package com.forgemaster.anvil;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * 一个铁砧位（v2.16 ForgeTax 式铁砧强化）。
 *
 * <p>玩家右键把剑放上来 → 这个对象记住「谁的剑、哪把剑、放在哪个方块上」，
 * 手上和背包里都不再留这把剑；左键落锤、再右键取回。全息用的是
 * {@link org.bukkit.entity.ItemDisplay}（剑本体）+ {@link org.bukkit.entity.TextDisplay}
 * （等级 / 成功率 / 碎裂率 / 连击），两个实体的 UUID 也存这里，方便刷新与回收。</p>
 *
 * <p>只做数据搬运，不做判定——所有规则在 {@link com.forgemaster.strengthen.StrengthenService}
 * 与 {@link AnvilForgeManager} 里。</p>
 */
public final class AnvilForgeSession {

    /** 方块坐标（世界名 + 整数坐标，不持有 Block 引用，避免区块卸载后变脏） */
    private final String world;
    private final int x;
    private final int y;
    private final int z;

    private final UUID owner;
    private final String ownerName;

    /** 砧上的剑（真实对象，改它 = 改玩家的剑） */
    private ItemStack sword;

    private final long placedAt;
    private long lastStrikeAt;

    /** 全息实体（可能因为区块卸载而失效，失效时重新生成） */
    private UUID swordDisplay;
    private UUID textDisplay;
    /** 文本内容指纹：内容没变就不重建 Component */
    private String lastText;

    public AnvilForgeSession(String world, int x, int y, int z,
                             UUID owner, String ownerName, ItemStack sword, long placedAt) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.owner = owner;
        this.ownerName = ownerName == null ? "" : ownerName;
        this.sword = sword;
        this.placedAt = placedAt;
        this.lastStrikeAt = 0L;
    }

    public String worldName() {
        return world;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public ItemStack sword() {
        return sword;
    }

    public void sword(ItemStack sword) {
        this.sword = sword;
    }

    public long placedAt() {
        return placedAt;
    }

    public long lastStrikeAt() {
        return lastStrikeAt;
    }

    public void lastStrikeAt(long at) {
        this.lastStrikeAt = at;
    }

    public UUID swordDisplay() {
        return swordDisplay;
    }

    public void swordDisplay(UUID id) {
        this.swordDisplay = id;
    }

    public UUID textDisplay() {
        return textDisplay;
    }

    public void textDisplay(UUID id) {
        this.textDisplay = id;
    }

    public String lastText() {
        return lastText;
    }

    public void lastText(String text) {
        this.lastText = text;
    }

    /** 方块坐标键：{@code world;x;y;z} */
    public String key() {
        return keyOf(world, x, y, z);
    }

    public static String keyOf(String world, int x, int y, int z) {
        return world + ";" + x + ";" + y + ";" + z;
    }

    public static String keyOf(Block block) {
        return keyOf(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    /** 铁砧方块本身是否还在（区块没加载时按「还在」处理，不误删） */
    public boolean blockPresent() {
        World w = Bukkit.getWorld(world);
        if (w == null) {
            return false;
        }
        if (!w.isChunkLoaded(x >> 4, z >> 4)) {
            return true;
        }
        return AnvilForgeManager.isAnvilBlock(w.getBlockAt(x, y, z).getType());
    }

    /** 铁砧正上方的位置 */
    public Location above(double dy) {
        World w = Bukkit.getWorld(world);
        if (w == null) {
            return null;
        }
        return new Location(w, x + 0.5, y + dy, z + 0.5);
    }
}