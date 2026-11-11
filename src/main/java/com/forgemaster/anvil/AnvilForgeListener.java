package com.forgemaster.anvil;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.util.Msg;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * 铁砧强化的交互层（v2.16，ForgeTax 式）。
 *
 * <p>把「右键放剑 / 左键落锤 / 右键取剑」三件事接到方块事件上，没有任何区间界面。
 * 优先级用 {@link EventPriority#LOW}：要抢在别的「潜行右键开锻造台」之类的快捷方式前面，
 * 否则玩家拿着剑蹲一下就被弹进菜单了。</p>
 *
 * <p>不干扰原版：手上不是锻造品、不是重锤，砧上也没有剑时，一律不取消事件——
 * 普通玩家照常能用原版铁砧修东西。</p>
 */
public final class AnvilForgeListener implements Listener {

    private final ForgingMasterPlugin plugin;
    private final AnvilForgeManager manager;
    /** 提示节流：同一句话 N 毫秒内只发一次，按住左键不会把聊天刷爆 */
    private final java.util.Map<java.util.UUID, Long> lastHint = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long HINT_GAP_MS = 2500L;

    public AnvilForgeListener(ForgingMasterPlugin plugin, AnvilForgeManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    /** 这条提示是不是该放行（节流） */
    private boolean hintOk(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastHint.get(player.getUniqueId());
        if (last != null && now - last < HINT_GAP_MS) {
            return false;
        }
        lastHint.put(player.getUniqueId(), now);
        return true;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !AnvilForgeManager.isAnvilBlock(block.getType())) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("forgemaster.use")) {
            return;
        }
        Action action = event.getAction();

        if (action == Action.RIGHT_CLICK_BLOCK) {
            handleRightClick(event, player, block);
            return;
        }
        if (action == Action.LEFT_CLICK_BLOCK) {
            handleLeftClick(event, player, block);
        }
    }

    /** 右键：砧上有剑就取回，没有就把手上的剑放上去 */
    private void handleRightClick(PlayerInteractEvent event, Player player, Block block) {
        AnvilForgeSession session = manager.sessionAt(block);
        if (session != null) {
            event.setCancelled(true);
            String msg = manager.take(player, block);
            if (msg != null) {
                Msg.prefix(player, msg);
            }
            return;
        }
        // 潜行右键空砧 = 老的「打开锻造台」快捷方式，让给 ForgeListener 处理。
        // 不潜行才是「放剑上砧」，两条路互不踩。
        if (player.isSneaking() && plugin.settings().anvilShortcut()) {
            return;
        }
        ItemStack inHand = player.getInventory().getItemInMainHand();
        boolean forged = plugin.items() != null && plugin.items().isForged(inHand);
        boolean hammer = Hammer.isHammer(plugin, inHand);
        if (!forged) {
            if (hammer) {
                // 拿着锤子对着空砧：别把玩家晾在那儿，告诉他要先放剑
                event.setCancelled(true);
                if (hintOk(player)) {
                    Msg.prefix(player, "<gray>先 <white>右键</white> 把剑放上铁砧，再拿重锤敲。");
                }
                player.sendActionBar(Msg.mm("<gray>砧子是空的 <dark_gray>·</dark_gray> 主手拿剑右键放上去"));
            }
            return;
        }
        event.setCancelled(true);
        Msg.prefix(player, manager.place(player, block, inHand));
    }

    /** 左键：手持重锤就落锤（按住 Shift = 批量连敲到终止条件），否则提示 */
    private void handleLeftClick(PlayerInteractEvent event, Player player, Block block) {
        AnvilForgeSession session = manager.sessionAt(block);
        ItemStack inHand = player.getInventory().getItemInMainHand();
        boolean hammer = Hammer.isHammer(plugin, inHand);

        if (session == null) {
            if (hammer) {
                event.setCancelled(true);
                if (hintOk(player)) {
                    Msg.prefix(player, "<gray>砧子是空的——先 <white>右键</white> 把剑放上去。");
                }
                player.sendActionBar(Msg.mm("<gray>空砧 <dark_gray>·</dark_gray> 主手拿剑右键放剑"));
            }
            return;
        }
        // 砧上有剑：左键无论拿没拿锤子都拦下来，免得把铁砧敲坏
        event.setCancelled(true);
        if (!hammer) {
            if (hintOk(player)) {
                Msg.prefix(player, "<gray>要落锤得手持 <white>重锤</white>"
                        + "（<dark_gray>/zhujian hammer</dark_gray> 领一把）。");
            }
            player.sendActionBar(Msg.mm("<gray>手持重锤左键落锤"));
            return;
        }
        if (player.isSneaking()) {
            // 按住 Shift：批量落锤（v2.23 P1-1），连续敲到上限 / 碎 / 满 / 断材料，
            // 结束时一条聊天总结；逐锤结果只上 ActionBar 不刷聊天
            manager.strikeBatch(player, block);
            return;
        }
        String msg = manager.strike(player, block);
        if (msg != null) {
            Msg.prefix(player, msg);
        }
    }

    /** 拆砧：先把剑还了，再拦下这一下 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!AnvilForgeManager.isAnvilBlock(block.getType())) {
            return;
        }
        if (manager.sessionAt(block) == null) {
            return;
        }
        event.setCancelled(true);
        Msg.prefix(event.getPlayer(), "<red>砧上还有剑，先把剑取回来（右键）。");
    }

    /** 爆炸：砧位上的剑先还人，不跟着铁砧一起消失 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        releaseAll(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        releaseAll(event.blockList());
    }

    private void releaseAll(java.util.List<Block> blocks) {
        for (Block b : blocks) {
            if (AnvilForgeManager.isAnvilBlock(b.getType())) {
                manager.releaseBlock(b);
            }
        }
    }

    /** 区块重新加载：全息实体是非持久化的，得补回来 */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        manager.onChunkLoad(event.getChunk());
    }

    /** 铁砧方块类型是否受管（自检用） */
    public static boolean manages(Material type) {
        return AnvilForgeManager.isAnvilBlock(type);
    }
}