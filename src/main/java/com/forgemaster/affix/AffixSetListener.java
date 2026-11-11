package com.forgemaster.affix;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.util.Keys;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.Map;

/**
 * 词条套装的战斗结算：饮血回血 / 铁壁减伤 / 断罪斩杀增伤 / 淬毒施缓慢。
 *
 * <p><b>独立监听</b>：不挂进 {@code CombatListener}（那个文件不归本模块动），
 * 优先级统一 {@link EventPriority#NORMAL}——</p>
 * <ul>
 *   <li>{@code EntityDamageByEntityEvent}：{@code CombatListener} 是 {@code HIGH}，
 *       于是「断罪」的 +15% 落在暴击倍率<b>之前</b>，暴击会把这 15% 一起放大（符合直觉：
 *       打残血暴击就是更疼）；反过来我们也不会被它的 {@code setDamage} 覆盖。</li>
 *   <li>{@code EntityDamageEvent}：{@code ForgeListener} 的护甲减伤也是 {@code HIGH}，
 *       它在自己的基础上再乘一次（乘法叠加），两边互不覆盖。铁壁自己还有总上限
 *       {@code affix-set.max-damage-reduce}，不会叠成免伤。</li>
 * </ul>
 *
 * <p>只改伤害数值、绝不把伤害改成 0 或负数：所有倍率都 ≥ 1 / ≤ 1 且算完夹一个
 * 极小下限 {@value #MIN_DAMAGE}。事件取消（{@code ignoreCancelled = true}）时不动。</p>
 *
 * <p><b>本类不在主类注册</b>：由 Lead 在 {@code ForgingMasterPlugin#onEnable} 里
 * {@code Bukkit.getPluginManager().registerEvents(new AffixSetListener(plugin, sets), this)}。</p>
 */
public final class AffixSetListener implements Listener {

    /** 伤害下限：再狠的减伤也不把这一击抹成 0 */
    private static final double MIN_DAMAGE = 1.0E-4;

    private final ForgingMasterPlugin plugin;
    private final AffixSetService sets;

    public AffixSetListener(ForgingMasterPlugin plugin, AffixSetService sets) {
        this.plugin = plugin;
        this.sets = sets;
    }

    /** 只给 service 的写法也能注册（插件实例从 service 里反查） */
    public AffixSetListener(AffixSetService sets) {
        this(sets == null ? null : sets.plugin(), sets);
    }

    // ------------------------------------------------------------ 玩家打人

    /**
     * 玩家用锻造品命中：断罪增伤 → 饮血回血 → 淬毒施缓慢。
     *
     * <p>同一个效果在多个套装里都配置时<b>取最大值，不累加</b>：两套都写
     * {@code heal-percent} 不该变成两次回血，否则服主一加套装就数值爆炸。</p>
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerHit(EntityDamageByEntityEvent event) {
        if (!sets.enabled()) {
            return;
        }
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        double damage = event.getDamage();
        if (damage <= 0) {
            return;
        }
        ItemStack weapon = player.getInventory().getItemInMainHand();
        List<String> matched = sets.matchedSets(weapon);
        if (matched.isEmpty()) {
            return;
        }
        // 崩刃的剑只是一把普通剑：与 CombatListener 的判定保持一致
        if (brokenBlade(weapon)) {
            return;
        }

        Entity victim = event.getEntity();
        LivingEntity living = victim instanceof LivingEntity le ? le : null;

        double executeBonus = 0;
        double healPercent = 0;
        int slowTicks = 0;
        int slowAmplifier = 0;
        for (String id : matched) {
            AffixSet set = sets.set(id);
            if (set == null) {
                continue;
            }
            if (set.executeBonus() > 0 && set.executeThreshold() > 0
                    && living != null && healthRatio(living) < set.executeThreshold()) {
                executeBonus += set.executeBonus();
            }
            if (set.healPercent() > healPercent) {
                healPercent = set.healPercent();
            }
            if (set.slowTicks() > slowTicks) {
                slowTicks = set.slowTicks();
                slowAmplifier = set.slowAmplifier();
            }
        }

        double dealt = damage;
        // ---- 断罪：目标残血时增伤（多个套装叠加，一次性乘上去）----
        if (executeBonus > 0) {
            dealt = damage * (1.0 + executeBonus);
            event.setDamage(Math.max(MIN_DAMAGE, dealt));
        }
        // ---- 饮血：按本次结算的伤害回血 ----
        if (healPercent > 0) {
            heal(player, dealt * healPercent);
        }
        // ---- 淬毒：命中附加缓慢 ----
        if (slowTicks > 0 && living != null && !living.equals(player)) {
            try {
                living.addPotionEffect(new PotionEffect(
                        PotionEffectType.SLOWNESS, slowTicks, slowAmplifier, false, true, true));
            } catch (RuntimeException ex) {
                // 目标不吃药水（部分自定义实体）：效果放弃，绝不因为这里把整次命中搞挂
                plugin.getLogger().fine("套装缓慢效果施加失败: " + ex.getMessage());
            }
        }
    }

    // ------------------------------------------------------------ 玩家挨打

    /**
     * 玩家受到伤害：铁壁减伤。
     *
     * <p>同一套装在多件装备上同时满足<b>只算一次</b>（取最大），不同套装可以叠加，
     * 叠完再夹 {@code affix-set.max-damage-reduce}。否则四件护甲都凑齐铁壁就是 24%，
     * 再加别的套装会直接失控。</p>
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerDamaged(EntityDamageEvent event) {
        if (!sets.enabled()) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        double damage = event.getDamage();
        if (damage <= 0) {
            return;
        }
        // 审计 #6：装备读取改走 EquipmentDamageScan 单遍扫描（本监听器 NORMAL 先扫，
        // HIGH 的 ForgeListener 复用同一份缓存）；只取套装减伤那份，结算逻辑不变。
        double reduce = damageReduceOf(player, event);
        if (reduce <= 0) {
            return;
        }
        event.setDamage(Math.max(MIN_DAMAGE, damage * (1.0 - reduce)));
    }

    /** 该玩家当前身上（主手 / 副手 / 四件护甲）生效的套装减伤总和（已夹上限） */
    private double damageReduceOf(Player player, EntityDamageEvent event) {
        Map<String, Double> best = EquipmentDamageScan.scan(player, event, sets).setReduce();
        double total = 0;
        for (double v : best.values()) {
            total += v;
        }
        return Math.min(sets.maxDamageReduce(), total);
    }

    // ------------------------------------------------------------ 辅助

    /** 崩刃（耐久打光）的剑不结算套装效果 */
    private static boolean brokenBlade(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return true;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return true;
        }
        Integer durability = meta.getPersistentDataContainer()
                .get(Keys.DURABILITY, PersistentDataType.INTEGER);
        if (durability == null) {
            return false; // 没写耐久 = 不是靠本体耐久吃饭的锻造品，照常结算
        }
        if (durability > 0) {
            return false;
        }
        // 耐久 0：还要确认这把剑确实带着本体耐久标记（Damageable），别把「0 但有效」的当崩刃
        return meta instanceof Damageable;
    }

    /** 血量比例（0-1），属性异常时按「满血」兜底，避免误判斩杀 */
    private static double healthRatio(LivingEntity entity) {
        double max = maxHealthOf(entity);
        if (max <= 0) {
            return 1.0;
        }
        return Math.max(0, Math.min(1.0, entity.getHealth() / max));
    }

    private static double maxHealthOf(LivingEntity entity) {
        AttributeInstance attr = entity.getAttribute(Attribute.MAX_HEALTH);
        return attr == null ? 20.0 : attr.getValue();
    }

    /** 回血：夹在最大生命内，满血时不制造无意义的 setHealth */
    private void heal(Player player, double amount) {
        if (amount <= 0 || player.isDead()) {
            return;
        }
        double max = maxHealthOf(player);
        double current = player.getHealth();
        if (current >= max) {
            return;
        }
        double next = Math.min(max, current + amount);
        if (next <= current) {
            return;
        }
        player.setHealth(next);
        if (plugin.settings().particles()) {
            player.getWorld().spawnParticle(Particle.HEART,
                    player.getLocation().add(0, 1.6, 0), 3, 0.2, 0.2, 0.2, 0.0);
        }
    }
}
