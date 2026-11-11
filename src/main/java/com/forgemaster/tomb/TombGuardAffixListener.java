package com.forgemaster.tomb;

import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.List;
import java.util.Random;

/**
 * 残影守卫词缀的伤害监听（v2.23）：攻击侧与受击侧效果都走 {@link EntityDamageByEntityEvent}。
 *
 * <h2>攻击侧（守卫 → 玩家）</h2>
 * <ul>
 *   <li>{@code attack_damage}：伤害乘区（{@code outgoingDamage} 乘区函数）；</li>
 *   <li>{@code crit}：按概率掷骰，命中后伤害 ×crit 倍率并给受击者一撮 CRIT 粒子反馈；</li>
 *   <li>{@code fire}：命中点燃 {@code fire.seconds} 秒；</li>
 *   <li>{@code lifesteal}：守卫按最终伤害比例回血。</li>
 * </ul>
 *
 * <h2>受击侧（玩家 → 守卫）</h2>
 * <ul>
 *   <li>{@code damage_reduce}：守卫受到的伤害按比例减免（含玩家射出的弹射物）。</li>
 * </ul>
 *
 * <h2>防误伤</h2>
 * <p>两个方向都先用 {@link TombGuardAffixes#isGuard} 校验 PDC 标记：
 * 没标记的普通怪物、玩家宠物、其它插件实体一律原样放行。监听只改伤害与挂点燃 / 回血，
 * 不取消事件——词缀是「变强」不是「无敌」。守卫死亡时 PDC 随实体消失，
 * 不需要任何失效清理。</p>
 *
 * <p>监听由 {@link TombGuardAffixes} 构造时统一注册，插件主类无需接线。</p>
 */
public final class TombGuardAffixListener implements Listener {

    private final TombGuardAffixes affixes;
    /** 暴击掷骰用；可断言的判定逻辑在 {@link TombGuardAffixes#critHit} 纯函数里 */
    private final Random random = new Random();

    public TombGuardAffixListener(TombGuardAffixes affixes) {
        this.affixes = affixes;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGuardCombat(EntityDamageByEntityEvent event) {
        // 攻击侧：带词缀标记的守卫打玩家
        if (event.getDamager() instanceof Mob mob && event.getEntity() instanceof Player victim
                && affixes.isGuard(mob)) {
            handleGuardAttack(event, mob, victim);
            return;
        }
        // 受击侧：玩家（含其弹射物）打带词缀标记的守卫
        if (event.getEntity() instanceof Mob mob && isPlayerAttack(event.getDamager())
                && affixes.isGuard(mob)) {
            List<String> ids = affixes.affixIdsOf(mob);
            if (ids.contains(TombGuardAffixes.DAMAGE_REDUCE)) {
                event.setDamage(TombGuardAffixes.incomingDamage(
                        event.getDamage(), true, affixes.damageReducePercent()));
            }
        }
    }

    /** 攻击侧四件套：伤害乘区 / 暴击 / 点燃 / 饮血。没挂任何输出词缀时零开销返回 */
    private void handleGuardAttack(EntityDamageByEntityEvent event, Mob mob, Player victim) {
        List<String> ids = affixes.affixIdsOf(mob);
        if (ids.isEmpty()) {
            return;
        }
        boolean crit = ids.contains(TombGuardAffixes.CRIT)
                && TombGuardAffixes.critHit(random.nextDouble(), affixes.critChance());
        double dmg = TombGuardAffixes.outgoingDamage(event.getDamage(),
                ids.contains(TombGuardAffixes.ATTACK_DAMAGE), affixes.attackDamageMultiplier(),
                crit, affixes.critMultiplier());
        event.setDamage(dmg);

        if (ids.contains(TombGuardAffixes.FIRE)) {
            int seconds = affixes.fireSeconds();
            if (seconds > 0) {
                // 取较长的一边：残影的火不顶掉更久的原版点燃，也不被短点燃刷新掉
                victim.setFireTicks(Math.max(victim.getFireTicks(), seconds * 20));
            }
        }
        if (crit) {
            victim.getWorld().spawnParticle(Particle.CRIT,
                    victim.getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0.1);
        }
        if (ids.contains(TombGuardAffixes.LIFESTEAL)) {
            double heal = TombGuardAffixes.lifestealHeal(dmg, affixes.lifestealFraction());
            if (heal > 0 && !mob.isDead()) {
                mob.heal(heal); // heal 内部按最大血量封顶，守卫满血时等于白回
            }
        }
    }

    /** 是不是玩家发起的攻击：直接动手，或弹射物（箭 / 三叉戟）的射手是玩家 */
    private static boolean isPlayerAttack(Entity damager) {
        if (damager instanceof Player) {
            return true;
        }
        return damager instanceof Projectile projectile && projectile.getShooter() instanceof Player;
    }
}
