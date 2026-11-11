package com.forgemaster.relicset;

import com.forgemaster.ForgingMasterPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * 套装效果 2.0 的事件入口：穿戴扫描、清理还原、主动技触发。
 *
 * <p><b>独立监听</b>（与 AffixSetListener 同样的注册方式，由主类注册），
 * 穿脱走「登录 / 重生 / 点背包」三个时机延迟重算——本 Paper 版本没有
 * {@code PlayerArmorChangeEvent}，与 {@code ArmorRebalanceListener} 的挂载点对齐：</p>
 * <ul>
 *   <li>{@link PlayerJoinEvent} / {@link PlayerRespawnEvent}：登录、重生后装备落定，延迟一 tick 重算；</li>
 *   <li>{@link InventoryClickEvent}（MONITOR）：shift 穿甲、快捷栏交换、点盔甲槽都会走到，延迟一 tick；</li>
 *   <li>{@link PlayerDeathEvent}：还原属性（装备掉落 / 保留两种情况都不会留脏修正，
 *       keepInventory 的情况由重生后的扫描重挂回来）；</li>
 *   <li>{@link PlayerQuitEvent}：还原属性（transient 修饰符本来不落盘，这里再清一次内存里的差量表）；</li>
 *   <li>{@link PluginDisableEvent}：本插件停用时把所有在线玩家的套装修正整批还原
 *       （与 DuelTierListener 同一写法，不动主类 onDisable 的内部逻辑）。</li>
 * </ul>
 *
 * <p><b>主动技</b>：{@link PlayerInteractEvent} 只认主手 LEFT_CLICK_BLOCK / LEFT_CLICK_AIR
 * 且潜行（{@code event.getHand() == HAND} 防双手双触发）。判定与冷却全在
 * {@link RelicSetService#tryCast(Player)}，这里只做事件过滤，不取消事件——
 * 左键该拆方块照样拆，套装技是「捎带手」的一击。</p>
 */
public final class RelicSetListener implements Listener {

    private final ForgingMasterPlugin plugin;
    private final RelicSetService sets;

    public RelicSetListener(ForgingMasterPlugin plugin, RelicSetService sets) {
        this.plugin = plugin;
        this.sets = sets;
    }

    // ------------------------------------------------------------ 穿脱：重算

    /** 登录：可能穿着套装下线的，装备落定后重算一遍 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        scheduleScan(event.getPlayer());
    }

    /** 重生：死亡时已还原，这里按重生后的装备重新挂 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        scheduleScan(event.getPlayer());
    }

    /** 点背包：shift 穿甲 / 脱甲 / 快捷栏交换都走这里，延迟一 tick 等槽位稳定 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            scheduleScan(player);
        }
    }

    // ------------------------------------------------------------ 套装矩阵 GUI

    /**
     * 套装矩阵界面（{@link RelicSetHolder}）的点击：整单取消（只读面板，
     * 玩家不能从里面拿东西），再转发给 {@link RelicSetGui#click} 做页面跳转。
     *
     * <p>与本类的 {@link #onClick(InventoryClickEvent)}（MONITOR 重算被动）并行：
     * 在预览面板里点来点去也会触发一次套装重算，幂等无害。</p>
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onGuiClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof RelicSetHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getInventory()) {
            return; // 点的是玩家自己的背包，只取消不路由
        }
        if (event.getWhoClicked() instanceof Player player) {
            RelicSetGui.click(plugin, player, holder, event.getRawSlot());
        }
    }

    /** 套装矩阵界面里的拖拽一并拦死（只读面板不允许任何物品进出） */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGuiDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof RelicSetHolder) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ 清理：还原

    /** 死亡：装备无论掉不掉，先把套装修正还原（keepInventory 由重生扫描重挂） */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        sets.strip(event.getEntity());
    }

    /** 退服：还原（transient 修饰符不落盘，这里主要是清内存差量表） */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        sets.strip(event.getPlayer());
    }

    /** 本插件停用：所有在线玩家的套装修正整批还原，不在存档里留脏键 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() == plugin) {
            sets.stripAll();
        }
    }

    /**
     * 延迟一 tick 再扫：登录和点击的瞬间背包还在被服务端改写，立刻动手
     * 偶尔会被回滚掉（与 ArmorRebalanceListener 同一口）。
     */
    private void scheduleScan(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            try {
                sets.applyBonuses(player);
            } catch (RuntimeException ex) {
                // 套装失败绝不能影响玩家正常游戏
                plugin.getLogger().warning("套装效果重算出错：" + ex.getMessage());
            }
        });
    }

    // ------------------------------------------------------------ 主动技：潜行 + 左键

    /**
     * 潜行 + 左键（方块或空气）触发落星斩。
     *
     * <p>只认主手（{@code getHand() == HAND}），不取消事件——左键拆方块 / 打实体
     * 的原版行为照常。冷却中与未达档位的反馈都在 service 里推 ActionBar。</p>
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.LEFT_CLICK_AIR) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.isSneaking()) {
            return;
        }
        if (!player.hasPermission("forgemaster.use")) {
            return;
        }
        try {
            sets.tryCast(player);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("套装主动技触发出错：" + ex.getMessage());
        }
    }
}
