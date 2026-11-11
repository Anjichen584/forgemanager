package com.forgemaster.listener;

import com.forgemaster.ForgingMasterPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 老装备的护甲校准入口。
 *
 * <p>2.10 把装备护甲从「单件 0.35」提到原版量级，并且把四件共用一条
 * {@code fm_armor} modifier 的旧写法改成按部位分开。老装备身上还冻着旧值 / 旧键，
 * 需要在校准时补一遍——否则服主升了版本，玩家身上的甲还是纸做的。</p>
 *
 * <p>只做两件单调的事：<b>只升不降</b>、<b>键名换成按部位</b>。
 * 校准过的东西打 {@code armor_rebalance} 标记，之后每次进来都只是读一个整数就返回。</p>
 *
 * <p>这个 Paper 版本没有 {@code PlayerArmorChangeEvent}，所以用
 * 「登录 / 重生 / 点背包」三个时机把四个装备槽扫一遍。漏掉的极端情况
 * （比如死亡掉落又被别人捡走）会在那人下次登录时补上。</p>
 */
public class ArmorRebalanceListener implements Listener {

    /** 装备槽在玩家背包里的下标：靴 36 / 腿 37 / 胸 38 / 头 39 */
    private static final int BOOTS = 36;
    private static final int HELMET = 39;

    private final ForgingMasterPlugin plugin;

    public ArmorRebalanceListener(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    /** 登录：玩家可能是穿着甲下线的，这时只能主动扫一遍 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        scheduleScan(event.getPlayer());
        scheduleTotalRepair(event.getPlayer());
    }

    /** 重生：死亡后装备会被重新穿上，同样要补 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        scheduleScan(event.getPlayer());
        scheduleTotalRepair(event.getPlayer());
    }

    /**
     * 登录 / 重生时把整个背包过一遍，修「属性组件被顶掉」的老物品。
     *
     * <p>2.10.2 之前每次 {@code setItemMeta} 都会把原版基数（铁剑 +5、攻速 -2.4）
     * 洗掉，老剑因此只剩剑芯那点伤害。这里按语义重新整理一次：
     * 武器补回原版基线，装备保持绝对值。{@code normalize} 对没有 {@code fm_*}
     * 条目的普通物品直接返回，扫一遍很便宜。</p>
     */
    private void scheduleTotalRepair(Player player) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            try {
                var inv = player.getInventory();
                int fixed = 0;
                for (int raw = 0; raw <= 40; raw++) {
                    ItemStack item = inv.getItem(raw);
                    if (item == null || item.getType().isAir()) {
                        continue;
                    }
                    // getItem 给的是副本，改完必须 setItem 写回，否则等于什么都没做
                    boolean changed = com.forgemaster.util.Attrs.normalize(item);
                    // 顺手封掉原版耐久：老物品也可能已经在掉本体耐久了
                    changed |= com.forgemaster.item.ItemFactory.enforceVirtualDurability(item);
                    // 升级前镶的暴击 / 暴伤 / 吸血 / 点燃宝石只在 Lore 里，PDC 缺这一段
                    if (plugin.gems() != null) {
                        changed |= plugin.gems().repairGemEffects(item);
                    }
                    // v2.17：强化上限改成按品质逐级提高，老物品的 PDC / Lore 还冻着
                    // 旧的材料档位数字（神话剑 +0/32），这里一次性同步到新值
                    if (plugin.strengthen() != null) {
                        changed |= plugin.strengthen().ensureCap(item);
                    }
                    if (changed) {
                        inv.setItem(raw, item);
                        fixed++;
                    }
                }
                if (fixed > 0) {
                    plugin.getLogger().info("属性组件修复：" + player.getName()
                            + " 的 " + fixed + " 件物品补回了原版基数");
                }
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("属性组件修复出错：" + ex.getMessage());
            }
        }, 5L);
    }

    /** 点背包：只有真动了护甲（护甲槽位直点 / shift 穿脱）才扫，延迟一 tick 等槽位稳定 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // 审计 #5：原本任何背包点击（开箱子翻货、整理背包）都排一个扫描任务，
        // 100 人在线 ≈ 每秒 100 个任务 × 4 槽 clone + meta 读，而校准只关心护甲。
        // 收窄成两类：点在护甲槽位（36~39，含数字键换装）或 shift 点击（穿甲/脱甲主路径）。
        if (!event.isShiftClick() && (event.getSlot() < BOOTS || event.getSlot() > HELMET)) {
            return;
        }
        scheduleScan(player);
    }

    /** 已排了待办扫描任务的玩家：同 tick 内连续点击天然合并成一个任务（审计 #5） */
    private final java.util.Set<java.util.UUID> pendingScans = new java.util.HashSet<>();

    /**
     * 延迟一 tick 再扫：登录和点击的瞬间背包还在被服务端改写，
     * 立刻动手偶尔会被回滚掉。
     *
     * <p>审计 #5：加 per-player 去重——同一个玩家已有待办任务就不再排新的，
     * 任务执行时才从去重集合里移除；连续点击只会产出一次 4 槽扫描。</p>
     */
    private void scheduleScan(Player player) {
        if (!pendingScans.add(player.getUniqueId())) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            pendingScans.remove(player.getUniqueId());
            if (!player.isOnline()) {
                return;
            }
            try {
                scan(player);
            } catch (RuntimeException ex) {
                // 校准失败绝不能影响玩家正常游戏
                plugin.getLogger().warning("护甲校准出错：" + ex.getMessage());
            }
        });
    }

    private void scan(Player player) {
        for (int raw = BOOTS; raw <= HELMET; raw++) {
            ItemStack item = player.getInventory().getItem(raw);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            // rebalanceArmor 内部先读标记，没升级过的才干活
            ItemStack copy = item.clone();
            if (plugin.strengthen().rebalanceArmor(copy)) {
                player.getInventory().setItem(raw, copy);
            }
        }
    }
}
