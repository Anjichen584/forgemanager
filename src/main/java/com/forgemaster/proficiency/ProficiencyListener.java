package com.forgemaster.proficiency;

import com.forgemaster.ForgingMasterPlugin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 剑意的击杀监听。
 *
 * <p>只管两件事：</p>
 * <ul>
 *   <li>{@link EntityDeathEvent}：玩家用主手锻造品砍死<i>非玩家</i>生物 → +1 剑意；</li>
 *   <li>{@link PlayerDeathEvent}：<b>不扣剑意</b>（惩罚太劝退），只把剑意值保底回写一遍，
 *       顺便补回可能被别的模块重画 Lore 时漏掉的那一行。</li>
 * </ul>
 *
 * <p>注册由主类负责，本类没有静态副作用。</p>
 */
public final class ProficiencyListener implements Listener {

    private final ForgingMasterPlugin plugin;
    private final ProficiencyService service;

    public ProficiencyListener(ForgingMasterPlugin plugin, ProficiencyService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /**
     * 单参便捷构造：自己建一个 service。
     *
     * <p>service 本身无状态（唯一真相在物品 PDC 上），这样建也能跑；
     * 不过主类里已经有一个给命令 / 自检用的实例时，请走双参构造，省一个对象。</p>
     */
    public ProficiencyListener(ForgingMasterPlugin plugin) {
        this(plugin, new ProficiencyService(plugin));
    }

    /**
     * 击杀结算。
     *
     * <p>MONITOR + ignoreCancelled：等别的模块把它们对击杀的改写都做完再记数，
     * 保证「确实死了」才算剑意。玩家目标默认不统计（{@code proficiency.pvp} 可开），
     * 挡住互刷。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!service.enabled()) {
            return;
        }
        LivingEntity dead = event.getEntity();
        if (dead instanceof Player) {
            // 不统计 PVP 击杀，防刷
            if (!plugin.settings().flag("proficiency.pvp", false)) {
                return;
            }
        }
        Player killer = dead.getKiller();
        if (killer == null || killer.equals(dead)) {
            return;
        }
        // 远程击杀（弓 / 弩 / 投掷三叉戟）记不到「手上这把剑」头上：
        // 拉弓时主手是弓、射完立刻切剑都能白嫖剑意，所以一律不计
        var last = dead.getLastDamageCause();
        if (last != null && last.getDamageSource().getDirectEntity() instanceof Projectile) {
            return;
        }
        ItemStack weapon = killer.getInventory().getItemInMainHand();
        if (weapon == null || weapon.getType().isAir() || !plugin.items().isForged(weapon)) {
            return;
        }
        service.addKill(killer, weapon);
        // 背包读出来的是镜像，显式写回一格，保证 PDC / 属性 / Lore 一定落到物品上
        killer.getInventory().setItemInMainHand(weapon);
    }

    /**
     * 玩家死亡：不扣剑意，只做保底回写。
     *
     * <p>剑意的唯一真相是物品 PDC，掉地上、被捡走都跟着物品走；这里把背包里
     * 每一件锻造品的剑意值与 Lore 行重新钉一次，防的是「别的模块在这时重画
     * Lore 把我们那一行漏掉」。幂等，重复触发无副作用。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!service.enabled() || !plugin.settings().flag("proficiency.keep-on-death", true)) {
            return;
        }
        Player player = event.getEntity();
        if (player == null) {
            return;
        }
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (service.killsOf(item) <= 0) {
                continue;
            }
            try {
                service.persist(item);
            } catch (RuntimeException ex) {
                // 死亡事件里不为一件物品炸掉整条链，记一条日志继续
                plugin.getLogger().warning("剑意保底回写失败: " + ex.getMessage());
            }
        }
    }
}
