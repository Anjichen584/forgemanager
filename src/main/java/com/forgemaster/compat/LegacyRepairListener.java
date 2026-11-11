package com.forgemaster.compat;

import com.forgemaster.ForgingMasterPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * 玩家上线时收敛他身上/末影箱里的旧装备（v2.23.1）。
 *
 * <h2>为什么用「上线」而不是「开服扫全服」</h2>
 * 离线玩家的背包存在 {@code world/playerdata/<uuid>.dat} 里。要在一开服就扫所有人，
 * 就得自己解析 NBT 或者强行加载每个玩家档案——前者容易写坏存档，后者会把内存打满。
 * 这两件事拿来换「修几个数值」，风险和收益完全不成比例。
 *
 * <p>而<b>玩家自己登录</b>时，服务端本来就要加载他的档案。这时候扫一遍是零额外成本，
 * 且覆盖效果等价：不管他多久没上线，只要有一天上线，身上就已经是收敛后的数值。
 * 之后能拿这把剑去打架的，也就是「已经上线过的人」。</p>
 *
 * <h2>为什么延后几 tick</h2>
 * {@code PlayerJoinEvent} 触发时，玩家的属性/背包还没完全同步到客户端。立刻改物品会出现
 * 「客户端显示旧数值、服务端是新数值」的错位，玩家得重开背包才看得到。
 * 默认延后 40 tick（2 秒，可配）足够覆盖登录同步，又不至于让玩家操作到一半物品突然变化。
 */
public final class LegacyRepairListener implements Listener {

    private final ForgingMasterPlugin plugin;
    private final LegacyItemRepair repair;

    public LegacyRepairListener(ForgingMasterPlugin plugin, LegacyItemRepair repair) {
        this.plugin = plugin;
        this.repair = repair;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("legacy-repair.on-join", true)) {
            return;
        }
        long delay = Math.max(1L, plugin.getConfig().getLong("legacy-repair.on-join-delay-ticks", 40L));
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return; // 登录还没完就退出的，下次再说
            }
            int fixed;
            try {
                fixed = repair.sweepPlayer(player);
            } catch (RuntimeException ex) {
                // 收敛失败绝不能影响玩家进服：这是后台清理，不是登录流程的一环
                plugin.getLogger().warning("上线收敛失败（已跳过，不影响进服）: " + ex);
                return;
            }
            if (fixed <= 0) {
                return;
            }
            plugin.getLogger().info("[收敛] " + player.getName() + " 的 " + fixed + " 件旧装备已按 2.23.1 上限修正");
            if (plugin.getConfig().getBoolean("legacy-repair.notify-player", true)) {
                com.forgemaster.util.Msg.prefix(player,
                        "<gray>你有 <white>" + fixed + "</white> 件旧装备的数值超过当前上限，"
                                + "已自动收敛（Lore 末尾有说明）。</gray>"
                                + "<newline><dark_gray>　想要干净的 Lore：拿它去 <white>/zhujian reforge</white> 重铸一次。</dark_gray>");
            }
        }, delay);
    }
}