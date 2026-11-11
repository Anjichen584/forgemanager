package com.forgemaster.listener;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.util.Keys;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 自定义词条的战斗结算：暴击 / 暴伤 / 生命偷取 / 点燃。
 *
 * <p>另含锻造护甲的虚拟耐久消耗（v2.22.1）：锻造品本体 unbreakable，原版耐久条冻结，
 * 插件只认 PDC 里自己的耐久账。武器那本账走 {@link #onHit}；护甲那本账此前<b>没有任何
 * 扣减代码</b>——穿上锻造甲挨打，内置耐久（如 4500）永远不动，这里补上。</p>
 */
public final class CombatListener implements Listener {

    private final ForgingMasterPlugin plugin;
    /** 护甲扣账节流：玩家 UUID → 上次扣账时间戳。窗口内的连续伤害（火焰 tick 等）只扣一次 */
    private final java.util.Map<java.util.UUID, Long> lastArmorWear = new java.util.HashMap<>();

    public CombatListener(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 穿着锻造护甲挨打：每件锻造护甲掉 {@code durability-loss-per-hit} 点虚拟耐久。
     *
     * <p>只在「护甲真的吃到了减伤」时扣——和原版行为一致：原版护甲只在 ARMOR 减伤
     * 生效的伤害里磨损护甲条，饥饿 / 摔落这类穿甲也无用的伤害不磨。
     * 判定方式用 {@code DamageModifier.ARMOR} 的减免量；个别伤害类型不提供该
     * 修饰符（抛 UnsupportedOperationException）就退回「有实际伤害就磨损」。</p>
     *
     * <p>崩刃（耐久归零）的护甲与崩刃的剑同规则：词条套装已经不结算
     * （{@code AffixSetListener.brokenBlade}），本体属性保持原样，去 {@code /zhujian repair} 研磨。</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWearerDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!plugin.settings().durabilityEnabled()) {
            return;
        }
        int loss = Math.max(0, plugin.settings().durabilityLossPerHit());
        if (loss <= 0) {
            return;
        }
        // 护甲没吃到减伤的伤害（如饥饿 / 虚空 / 掉落），磨不到护甲条。
        // v2.23.6 修「装备不掉虚拟耐久」：本版 Paper 的
        // getDamage(DamageModifier.ARMOR) 返回的是带符号的修正量 ——
        // 减免越多值越负（真机实测 -1.158 / final 0.289），旧代码拿它做
        // {@code absorbed <= 0} 早退，等于所有有护甲减伤的伤害全被拦掉，
        // 护甲一次账都扣不到。取绝对值才是真正的「吃到了多少减免」。
        double absorbed;
        try {
            absorbed = Math.abs(event.getDamage(EntityDamageEvent.DamageModifier.ARMOR));
        } catch (UnsupportedOperationException unsupported) {
            absorbed = event.getFinalDamage() > 0 ? 1 : 0;
        }
        if (absorbed <= 0) {
            return;
        }
        // 节流：火焰 / 药水这类逐 tick 的高频伤害在窗口内只扣一次账，
        // setItemMeta 会给在线玩家全量发包，不节流的话站火里每 tick 广播一次装备栏
        long throttle = plugin.settings().armorWearThrottleMs();
        if (throttle > 0) {
            long now = System.currentTimeMillis();
            Long last = lastArmorWear.get(player.getUniqueId());
            if (last != null && now - last < throttle) {
                return;
            }
            if (lastArmorWear.size() > 512) {
                lastArmorWear.entrySet().removeIf(e -> now - e.getValue() > 30_000);
            }
            lastArmorWear.put(player.getUniqueId(), now);
        }

        var inv = player.getInventory();
        ItemStack[] pieces = inv.getArmorContents();
        for (int i = 0; i < pieces.length && i < 4; i++) {
            ItemStack piece = pieces[i];
            if (piece == null || piece.getType().isAir() || !plugin.items().isForged(piece)) {
                continue;
            }
            int dur = plugin.items().durabilityOf(piece);
            if (dur <= 0) {
                continue; // 已崩刃：不刷屏，崩着就是一件普通装备
            }
            int next = Math.max(0, dur - loss);
            if (next == dur) {
                continue;
            }
            boolean broken = plugin.items().setDurability(piece, next);
            inv.setItem(36 + i, piece); // 护甲槽 36=靴 37=腿 38=胸 39=盔，与 getArmorContents 顺序一致
            if (broken) {
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.8f, 0.7f);
                player.sendActionBar(com.forgemaster.util.Msg.mm(
                        "<red>护甲崩裂了！<dark_gray>属性失效，去 <white>/zhujian repair</white> 研磨"));
            } else if (next <= 20) {
                player.sendActionBar(com.forgemaster.util.Msg.mm(
                        "<#ff9a3c>护甲磨损 <dark_gray>| <gray>耐久 " + next
                                + " <dark_gray>· <white>/zhujian repair"));
            }
        }
    }

    /**
     * 近战 / 远程统一结算：暴击 · 吸血 · 点燃 · 剑灵养灵 · 武器耐久。
     *
     * <p>v2.23.1 修 P1-C（投掷物归因）与 P2-D（优先级与吸血取值）：</p>
     * <ul>
     *   <li><b>原来</b>：首行 {@code damager instanceof Player} 早退，玩家射出的
     *       {@code Arrow} / {@code Trident} 直接 return —— 远程一发既不吃暴击/吸血/点燃，
     *       也不扣武器耐久，等于拿弓白嫖全套词条。现在把 damager 展开成「玩家本人」
     *       或「玩家射出的投掷物（{@code Projectile#getShooter()} 是 Player）」，
     *       武器仍取该玩家的主手。<b>非玩家射手（骷髅、掠夺者、发射器）不进这条分支</b>，
     *       行为与改动前完全一致。</li>
     *   <li><b>原来</b>：处理器挂在 {@link EventPriority#HIGH} 上改伤害，HIGH 之上还有
     *       HIGHEST / MONITOR 两档；别的战斗插件在更高优先级重写 {@code setDamage}
     *       会让暴击乘区整段丢失，先乘一次再被本插件乘一次则会叠乘。现在挂
     *       {@link EventPriority#HIGHEST}，乘区只落一次。</li>
     *   <li><b>原来</b>：吸血紧接着读 {@code getFinalDamage()}，那个值里含护甲/附魔
     *       与其它插件在同一优先级上的改写，时序不稳。现在把本次算出的 {@code dealt}
     *       （基础伤害 × 暴击乘区）直接传给吸血，不再二次读事件。</li>
     * </ul>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        // v2.23.6 修「武器一刀掉 2~3 点虚拟耐久」：一次挥击在原版里是
        // ENTITY_ATTACK + N 个 ENTITY_SWEEP_ATTACK 事件（横扫每蹭到一只
        // 挤在一起的怪就是独立一个事件），本监听器对每个事件都走一遍全套
        // 结算 —— 暴击 / 吸血 / 点燃 / 剑灵养灵 / 虚拟耐久全被横扫重复触发。
        // 横扫是原版的 AoE 回声（固定 1.0 伤害，不吃暴击与点燃），按原版
        // 口径整体跳过：只有主挥击那一次事件进结算。
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            return;
        }
        // v2.23.1 修 P1-C：玩家本人，或玩家射出的投掷物；其余（骷髅的箭等）保持不结算
        Player player;
        if (event.getDamager() instanceof Player direct) {
            player = direct;
        } else if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            player = shooter;
        } else {
            return;
        }
        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (weapon == null || weapon.getType().isAir()) {
            return;
        }
        ItemMeta meta = weapon.getItemMeta();
        if (meta == null) {
            return;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (!pdc.has(Keys.FORGED, PersistentDataType.BYTE)) {
            return;
        }

        double critChance = pdc.getOrDefault(Keys.CRIT_CHANCE, PersistentDataType.DOUBLE, 0.0);
        double critDamage = pdc.getOrDefault(Keys.CRIT_DAMAGE, PersistentDataType.DOUBLE, 0.0);
        double lifesteal = pdc.getOrDefault(Keys.LIFESTEAL, PersistentDataType.DOUBLE, 0.0);
        int fireTicks = pdc.getOrDefault(Keys.FIRE_TICKS, PersistentDataType.INTEGER, 0);

        // 原创附魔（v2.23.3 机制版）：这里**不再**给吸血/点燃加数值 ——
        // 那是原版附魔的皮肤，已经被否掉。现在只做「条件型增伤」：
        //   孤锋（周围无旁人） / 蓄锋（停手够久） / 刻痕（目标已被刻印） / 回响（刚被打过）
        // 四项相加后由 EnchantEffects 夹一次，且夹子比 combat.limits 更紧。
        var enchCaps = com.forgemaster.enchant.EnchantEffects.Caps.fromConfig(plugin.getConfig());
        var enchEntries = com.forgemaster.enchant.EnchantItem.of(plugin, weapon);
        var enchMech = enchEntries.isEmpty()
                ? com.forgemaster.enchant.EnchantEffects.Mechanics.none()
                : plugin.enchantEffects().of(enchEntries, enchCaps);
        // 回响在护甲上，要把四件一起算进来
        var fullMech = plugin.enchantEffects().merge(java.util.List.of(enchMech), enchCaps);
        boolean alone = countNearbyPlayers(player, enchCaps.loneWolfRadius()) == 0;
        long sinceLastHit = com.forgemaster.enchant.EnchantState.msSinceLastHit(player);
        boolean marked = com.forgemaster.enchant.EnchantState.isBranded(player, event.getEntity());
        double enchBonus = plugin.enchantEffects().conditionalBonus(
                fullMech, enchCaps, alone, sinceLastHit, marked);
        if (fullMech.retaliate() > 0 && com.forgemaster.enchant.EnchantState.isRetaliationTarget(
                player, event.getEntity(), enchCaps.retaliateMs())) {
            enchBonus += fullMech.retaliate();
        }
        // 兜底：即便配置被改成离谱的值，这一击的附魔增伤也越不过「四项上限之和」
        enchBonus = Math.min(enchBonus, enchCaps.maxLoneWolf() + enchCaps.maxCharge()
                + enchCaps.maxMarkBonus() + enchCaps.maxRetaliate());

        // 崩刃：耐久见底的剑只是一把普通剑
        int durability = pdc.getOrDefault(Keys.DURABILITY, PersistentDataType.INTEGER, -1);
        if (durability == 0) {
            player.sendActionBar(com.forgemaster.util.Msg.mm(
                    "<red>剑已崩刃 <dark_gray>| <gray>属性失效，去 <white>/zhujian repair</white> 研磨"));
            return;
        }

        if (plugin.settings().durabilityEnabled() && durability > 0) {
            // 复用上面已持有的 meta：命中热路径上不再二次 getItemMeta
            boolean broken = plugin.items().setDurability(weapon, meta, durability - plugin.settings().durabilityLossPerHit());
            if (broken) {
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 0.8f);
                player.sendActionBar(com.forgemaster.util.Msg.mm(
                        "<red>剑崩刃了！<dark_gray>属性已经失效，去 <white>/zhujian repair</white> 修复"));
                // 崩刃这一击不再吃词条加成
                return;
            }
            if (durability - plugin.settings().durabilityLossPerHit() <= 20) {
                player.sendActionBar(com.forgemaster.util.Msg.mm(
                        "<#ff9a3c>剑刃钝了 <dark_gray>| <gray>耐久 " + (durability - plugin.settings().durabilityLossPerHit())
                                + " <dark_gray>· <white>/zhujian repair"));
            }
        }

        // 剑灵：击中养灵（崩刃那一击已在上面 return）
        if (plugin.spirit() != null) {
            // 剑灵默认不认 PVP：没有开关的话互砍就能无限刷灵性 / 灵契词条
            boolean pvpTarget = event.getEntity() instanceof Player;
            if (!pvpTarget || plugin.settings().flag("spirit.pvp", false)) {
                // meta 一并传下去：剑灵侧不再自己读一次
                plugin.spirit().onHit(player, weapon, meta, event.getEntity());
            }
        }

        // 暴击：口径全在 Crit 里（压上限 + 抬底数），这里只管掷骰与表现。
        // 以前直接 random < critChance && damage * (1 + critDamage)：
        // 暴击率能叠到 179% → 永久暴击；暴伤最低档只有 ×1.25 → 比原版跳劈还弱。
        //
        // v2.23.1 修 P2-D：本次实际伤害统一收在 dealt 里，吸血只认它，不再二次读 getFinalDamage()
        double dealt = event.getDamage();
        // 原创附魔的条件型增伤（v2.23.3）：夹在暴击之前 —— 它和暴击一样属于「本插件加成」，
        // 必须一起被下面的 DamageBudget 管住。放在夹子之后就等于给它开了后门。
        if (enchBonus > 0) {
            dealt *= 1.0 + enchBonus;
        }
        var crit = com.forgemaster.combat.Crit.fromConfig(plugin, critChance, critDamage);
        if (crit.roll(ThreadLocalRandom.current())) {
            dealt = dealt * crit.multiplier();
            player.getWorld().spawnParticle(Particle.CRIT, event.getEntity().getLocation().add(0, 1.0, 0),
                    16, 0.3, 0.3, 0.3, 0.15);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.0f);
        }

        // v2.23.1 修 P0：伤害上限（服主实测「满配暴击一刀 130、四刀一只坚守者」）。
        // 夹在暴击之后，是因为放在之前的话暴击照样能顶穿上限，等于没管。
        var budget = limits();
        double capped = com.forgemaster.combat.DamageBudget.clampHit(dealt, budget);
        if (capped < dealt - 1e-9) {
            dealt = capped;
            player.sendActionBar(com.forgemaster.util.Msg.mm(
                    "<dark_gray>伤害已达上限 <white>" + com.forgemaster.util.Msg.fmt(capped, 1)
                            + "</white>（combat.limits.max-hit-damage）"));
        }
        if (Math.abs(dealt - event.getDamage()) > 1e-9) {
            event.setDamage(dealt);
        }

        // 吸血口径：本次基础伤害（已含暴击与伤害上限），与护甲减伤 / 其它插件改写解耦
        // v2.23.1 修 P0：吸血原来完全没有上限 —— 词条 0.14 × 星陨档 1.65 × 5 条 ≈ 115%，
        // 乘上高伤害就是「一刀回半管」。现在三道夹子（比例 / 绝对值 / 血条百分比）取最小。
        if (lifesteal > 0 && dealt > 0) {
            double max = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null
                    ? player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()
                    : 20.0;
            double heal = com.forgemaster.combat.DamageBudget.lifestealHeal(
                    dealt, lifesteal, max, limits());
            if (heal > 0 && player.getHealth() < max) {
                player.setHealth(Math.min(max, player.getHealth() + heal));
                player.getWorld().spawnParticle(Particle.HEART, player.getLocation().add(0, 1.6, 0),
                        3, 0.2, 0.2, 0.2, 0.0);
            }
        }

        // v2.23.1：点燃时长同样夹一手（存量物品里的 fire_ticks 可能是旧版堆出来的几十秒）
        if (fireTicks > 0 && event.getEntity() instanceof LivingEntity living) {
            int capTicks = (int) Math.round(
                    Math.max(0.0, plugin.getConfig().getDouble("combat.limits.max-fire-seconds", 10.0)) * 20);
            int applied = capTicks > 0 ? Math.min(fireTicks, capTicks) : fireTicks;
            living.setFireTicks(Math.max(living.getFireTicks(), applied));
        }
    }
/**
     * 伤害预算（v2.23.1）：每次命中读一次配置。
     *
     * <p>读配置的开销可以忽略（Bukkit 的 {@code MemorySection} 是内存 Map 查表），
     * 而缓存反而有坑：{@code /zhujian reload} 换了配置，缓存的旧上限会一直生效到重启。</p>
     */
    private com.forgemaster.combat.DamageBudget.Limits limits() {
        // 上限默认值只在 DamageBudget.Limits 里存一份，这里绝不重复写数字
        return com.forgemaster.combat.DamageBudget.Limits.fromConfig(plugin.getConfig());
    }

    /** 统计指定半径内的**其它玩家**数量（孤锋用：有队友在旁边就失效） */
    private static int countNearbyPlayers(org.bukkit.entity.Player player, double radius) {
        int n = 0;
        for (org.bukkit.entity.Entity e : player.getNearbyEntities(radius, radius, radius)) {
            if (e instanceof org.bukkit.entity.Player other && !other.equals(player)
                    && other.getGameMode() == org.bukkit.GameMode.SURVIVAL) {
                n++;
            }
        }
        return n;
    }
}