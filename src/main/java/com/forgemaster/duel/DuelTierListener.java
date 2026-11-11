package com.forgemaster.duel;

import com.forgemaster.ForgingMasterPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;

/**
 * 比试压档的兜底清理。
 *
 * <p>临时属性修正只活在内存里，但「玩家退出 / 服务器关闭 / 对局中断」这三条路
 * 都得走一遍还原，否则同一名玩家下次进服还背着一条负属性修正。这里挂两个
 * 监听点，都放 {@link EventPriority#MONITOR}——只做清理，不改别人的事件流程：</p>
 *
 * <ul>
 *   <li>{@link PlayerQuitEvent}：清掉这名玩家的临时修正（对局取消本身仍由
 *       {@code ForgeListener} → {@code DuelManager#onQuit} 负责，那边也会再清一次，幂等）。</li>
 *   <li>{@link PluginDisableEvent}：只认本插件，调 {@code DuelManager#shutdown()}
 *       还原全部在线玩家的修正并丢掉进行中的对局。</li>
 * </ul>
 */
public final class DuelTierListener implements Listener {

    private final ForgingMasterPlugin plugin;
    private final DuelManager duels;
    private final DuelBalance balance;

    DuelTierListener(ForgingMasterPlugin plugin, DuelManager duels, DuelBalance balance) {
        this.plugin = plugin;
        this.duels = duels;
        this.balance = balance;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // 玩家下线：先把自己那份临时修正还原，再让原有流程取消他的比试
        balance.clear(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        // 别的插件停用不管；本插件停用时把所有在线玩家的压档还原
        if (event.getPlugin() != plugin) {
            return;
        }
        duels.shutdown();
    }
}
