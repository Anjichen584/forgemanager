package com.forgemaster.enchant;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 原创附魔的「非战斗」侧接线（v2.23.2）。
 *
 * <p>战斗相关的（伤害 / 吸血 / 点燃 / 减伤 / 生命）在 {@code CombatListener} 与
 * {@code EnchantGuardListener} 里，因为那两处已经在算同一批数值，
 * 拆开成两个监听器只会让「一道伤害被加了两次」这种 bug 更容易发生。</p>
 *
 * <p>这里负责三件互不干涉的事：</p>
 * <ol>
 *   <li><b>右键上书</b>：手持原创附魔书右键 → 附到主手装备。</li>
 *   <li><b>挖矿额外掉落</b>：采掘附魔按几率多掉一份原矿（只对矿石方块，且只 +1）。</li>
 *   <li><b>经验加成</b>：聚灵附魔按倍率补经验。</li>
 * </ol>
 */
public final class EnchantListener implements Listener {

    private final ForgingMasterPlugin plugin;

    public EnchantListener(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    private EnchantEffects.Caps caps() {
        return EnchantEffects.Caps.fromConfig(plugin.getConfig());
    }

    // ------------------------------------------------------------ 右键上书

    /**
     * 手持附魔书右键 → 附到主手装备。
     *
     * <p>只认主手事件（{@code EquipmentSlot.HAND}），否则一次右键会触发两次
     * （主手 + 副手各一次），等于附了两遍。</p>
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack book = event.getItem();
        if (!EnchantItem.isBook(book)) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack target = player.getInventory().getItemInMainHand();
        // 主手拿的就是这本书，装备在副手 —— 换一下，让「书在手上」这个姿势成立
        if (target != null && target.getType() == Material.ENCHANTED_BOOK) {
            target = player.getInventory().getItemInOffHand();
        }
        if (target == null || target.getType().isAir()) {
            return;
        }
        event.setCancelled(true);
        if (plugin.enchantTable().applyBook(player, book, target)) {
            // 消耗一本书
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (EnchantItem.isBook(hand)) {
                hand.setAmount(hand.getAmount() - 1);
                player.getInventory().setItemInMainHand(hand.getAmount() <= 0
                        ? new ItemStack(Material.AIR) : hand);
            } else {
                ItemStack off = player.getInventory().getItemInOffHand();
                if (EnchantItem.isBook(off)) {
                    off.setAmount(off.getAmount() - 1);
                    player.getInventory().setItemInOffHand(off.getAmount() <= 0
                            ? new ItemStack(Material.AIR) : off);
                }
            }
        }
    }

    // ------------------------------------------------------------ 挖矿额外掉落

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isOre(block.getType())) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool == null || tool.getType().isAir()) {
            return;
        }
        List<EnchantEffects.Entry> entries = new ArrayList<>(EnchantItem.of(plugin, tool));
        if (entries.isEmpty()) {
            return;
        }
        EnchantEffects.Caps caps = caps();
        double chance = plugin.enchantEffects().dropChance(entries, caps);
        if (chance <= 0 || ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }
        // 只 +1 份，且只掉这个方块自己的产物 —— 不给「无限复制钻石」留口子
        Material bonus = bonusDrop(block.getType());
        if (bonus == null) {
            return;
        }
        block.getWorld().dropItemNaturally(block.getLocation(), new ItemStack(bonus, 1));
    }

    /** 矿石 → 额外掉落物 */
    private static Material bonusDrop(Material ore) {
        return switch (ore) {
            case COAL_ORE, DEEPSLATE_COAL_ORE -> Material.COAL;
            case IRON_ORE, DEEPSLATE_IRON_ORE -> Material.RAW_IRON;
            case COPPER_ORE, DEEPSLATE_COPPER_ORE -> Material.RAW_COPPER;
            case GOLD_ORE, DEEPSLATE_GOLD_ORE, NETHER_GOLD_ORE -> Material.RAW_GOLD;
            case REDSTONE_ORE, DEEPSLATE_REDSTONE_ORE -> Material.REDSTONE;
            case LAPIS_ORE, DEEPSLATE_LAPIS_ORE -> Material.LAPIS_LAZULI;
            case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE -> Material.DIAMOND;
            case EMERALD_ORE, DEEPSLATE_EMERALD_ORE -> Material.EMERALD;
            case NETHER_QUARTZ_ORE -> Material.QUARTZ;
            case ANCIENT_DEBRIS -> Material.ANCIENT_DEBRIS;
            default -> null;
        };
    }

    private static boolean isOre(Material m) {
        String n = m.name();
        return n.endsWith("_ORE") || n.equals("ANCIENT_DEBRIS");
    }

    // ------------------------------------------------------------ 经验加成

    /**
     * 聚灵：经验获取加成。
     *
     * <p>Bukkit 没有「经验已被给予」的可拦截事件，所以这里用
     * {@code PlayerExpChangeEvent}（经验球被吸收后触发）补差额。
     * 只影响打怪/挖矿的自然经验，不影响 {@code /xp} 与铁砧（那是玩家自己决定的支出）。</p>
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onExp(org.bukkit.event.player.PlayerExpChangeEvent event) {
        Player player = event.getPlayer();
        int amount = event.getAmount();
        if (amount <= 0) {
            return;
        }
        ItemStack main = player.getInventory().getItemInMainHand();
        List<EnchantEffects.Entry> entries = new ArrayList<>(EnchantItem.of(plugin, main));
        // 护甲上的通用附魔也算
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor != null && !armor.getType().isAir()) {
                entries.addAll(EnchantItem.of(plugin, armor));
            }
        }
        if (entries.isEmpty()) {
            return;
        }
        double mul = plugin.enchantEffects().expMul(entries, caps());
        if (mul <= 1.0) {
            return;
        }
        int extra = (int) Math.round(amount * (mul - 1.0));
        if (extra > 0) {
            event.setAmount(amount + extra);
        }
    }
}