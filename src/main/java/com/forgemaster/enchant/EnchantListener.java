package com.forgemaster.enchant;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;

/**
 * 机制型原创附魔的全部接线（v2.23.3）。
 *
 * <h2>12 条机制各自的落点</h2>
 * <table border="1">
 *   <caption>钩子分配</caption>
 *   <tr><th>机制</th><th>钩子</th><th>关键防崩设计</th></tr>
 *   <tr><td>崩流（溅射）</td><td>命中后（MONITOR）</td><td>溅射伤害不再触发附魔，防递归</td></tr>
 *   <tr><td>烬狱（爆燃）</td><td>EntityDeathEvent</td><td>只对玩家造成的击杀生效；范围伤害不吃倍率</td></tr>
 *   <tr><td>孤锋 / 蓄锋 / 刻痕</td><td>CombatListener 里读状态</td><td>三者相加只夹一次</td></tr>
 *   <tr><td>夺魄（打断）</td><td>命中后</td><td>只给 Slowness + Weakness，不改 AI（改 AI 会卡怪）</td></tr>
 *   <tr><td>卸力（延后）</td><td>HIGH 记比例 + MONITOR 按最终伤害切账</td><td>延后部分**仍会结算**，只是晚 2 秒，不是免除</td></tr>
 *   <tr><td>替身（免死）</td><td>致命伤时（MONITOR，卸力切账后判）</td><td>冷却 + 回气有上限，防永久免死</td></tr>
 *   <tr><td>回响（反击）</td><td>受伤记状态 + 命中读状态</td><td>只对「刚打过我的那个目标」生效</td></tr>
 *   <tr><td>守夜（脱战回复）</td><td>周期任务</td><td>每 2 秒一次、单次有上限</td></tr>
 *   <tr><td>拾遗（额外经验）</td><td>BlockBreakEvent</td><td>给经验不给物品，绕开「刷矿」</td></tr>
 *   <tr><td>望气（侦测）</td><td>周期任务</td><td>节流到每 3 秒以上，防动作栏刷屏</td></tr>
 * </table>
 *
 * <h2>为什么卸力和替身必须分开写</h2>
 * 卸力是「把伤害摊开」，替身是「这一次不死」。如果写在一起（比如都走取消事件），
 * 卸力会变成免伤；如果卸力延后的那部分伤害在 2 秒后把玩家打死，替身还要能救 ——
 * 所以延后伤害走一个独立的调度，并且同样经过替身检查。
 */
public final class EnchantListener implements Listener {

    private final ForgingMasterPlugin plugin;

    public EnchantListener(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    private EnchantEffects.Caps caps() {
        return EnchantEffects.Caps.fromConfig(plugin.getConfig());
    }

    private EnchantEffects fx() {
        return plugin.enchantEffects();
    }

    /** 一件装备上的机制参数 */
    private EnchantEffects.Mechanics mechanicsOf(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return EnchantEffects.Mechanics.none();
        }
        List<EnchantEffects.Entry> entries = EnchantItem.of(plugin, item);
        return entries.isEmpty() ? EnchantEffects.Mechanics.none() : fx().of(entries, caps());
    }

    /** 主手 + 四件护甲合并后的机制参数 */
    private EnchantEffects.Mechanics playerMechanics(Player player) {
        List<EnchantEffects.Mechanics> parts = new ArrayList<>(5);
        ItemStack main = player.getInventory().getItemInMainHand();
        if (main.getType() != Material.AIR) {
            parts.add(mechanicsOf(main));
        }
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            if (piece != null && !piece.getType().isAir()) {
                parts.add(mechanicsOf(piece));
            }
        }
        return parts.isEmpty() ? EnchantEffects.Mechanics.none() : fx().merge(parts, caps());
    }

    // ============================================================ 右键上书

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
        if (target != null && target.getType() == Material.ENCHANTED_BOOK) {
            target = player.getInventory().getItemInOffHand();
        }
        if (target == null || target.getType().isAir()) {
            return;
        }
        event.setCancelled(true);
        if (plugin.enchantTable().applyBook(player, book, target)) {
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

    // ============================================================ 输出侧（攻击之后）

    /**
     * 命中之后的机制：崩流（溅射）、夺魄（打断）、刻痕（累计）。
     *
     * <p>压在 MONITOR：此时 {@code CombatListener}（HIGH）已经把伤害夹完了，
     * 这里读到的是玩家**真正打出去的数字**。溅射基于它算，才不会绕过伤害预算。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        Entity target = event.getEntity();
        if (!(target instanceof LivingEntity living)) {
            return;
        }
        EnchantEffects.Caps caps = caps();
        EnchantEffects.Mechanics m = mechanicsOf(player.getInventory().getItemInMainHand());
        if (!m.any()) {
            return;
        }
        double dealt = event.getFinalDamage();

        // 蓄锋：记下这一刀的时刻（三条件型机制的时间基准）
        EnchantState.noteHit(player);
        EnchantState.noteCombat(player);

        // 刻痕：累计对同一目标的输出，够阈值就刻印
        double maxHp = maxHealthOf(living);
        if (m.markBonus() > 0
                && EnchantState.addMarkDamage(player, living, dealt, maxHp, caps.markThreshold())) {
            player.sendActionBar(Msg.mm("<#a78bfa>刻痕已成 <dark_gray>| <gray>对其伤害提升"));
            player.getWorld().spawnParticle(org.bukkit.Particle.ENCHANT,
                    living.getLocation().add(0, 1, 0), 18, 0.4, 0.5, 0.4, 0.1);
        }

        // 崩流：对目标周围的**其它**敌人造成独立伤害，且不再触发附魔
        if (m.splashRatio() > 0) {
            double splash = fx().splashDamage(m, caps, dealt);
            if (splash > 0) {
                int hit = 0;
                for (Entity near : living.getNearbyEntities(caps.splashRadius(),
                        caps.splashRadius(), caps.splashRadius())) {
                    if (near.equals(player) || near.equals(living)) {
                        continue;
                    }
                    if (!(near instanceof Monster) && !(near instanceof Player)) {
                        continue; // 只波及敌对生物与玩家，不误伤动物
                    }
                    if (hit >= 4) {
                        break; // 一次最多波及 4 个，防「一入人堆全死」
                    }
                    // 用 setNoDamageTicks(0) 之外的干净方式：直接 damage 会造成二次事件链，
                    // 所以这里只对非玩家目标结算，玩家之间不溅射（PVP 争议最小）
                    if (near instanceof Player) {
                        continue;
                    }
                    ((LivingEntity) near).damage(splash);
                    hit++;
                }
            }
        }

        // 夺魄：减速 + 虚弱，打断节奏（不改 AI，避免怪物卡住）
        if (m.hamperSeconds() > 0) {
            int ticks = (int) Math.round(m.hamperSeconds() * 20);
            living.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 0, false, true));
            living.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, ticks, 0, false, true));
        }
    }

    /**
     * 烬狱：击杀时尸身爆燃。
     *
     * <p>只对「玩家击杀」生效（{@code getEntity().getKiller()}），
     * 不然怪物互殴也会炸，服务器会莫名其妙满天爆炸。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        // 刻痕清理：目标死了，它的记录必须掉
        EnchantState.clearMark(event.getEntity());

        EnchantEffects.Caps caps = caps();
        EnchantEffects.Mechanics m = mechanicsOf(killer.getInventory().getItemInMainHand());
        double pyre = fx().pyreDamage(m, caps);
        if (pyre <= 0) {
            return;
        }
        Location at = event.getEntity().getLocation();
        at.getWorld().spawnParticle(org.bukkit.Particle.FLAME, at.add(0, 0.5, 0), 30, 0.6, 0.4, 0.6, 0.05);
        at.getWorld().playSound(at, org.bukkit.Sound.ITEM_FIRECHARGE_USE, 1.2f, 0.6f);
        for (Entity near : at.getWorld().getNearbyEntities(at, caps.pyreRadius(),
                caps.pyreRadius(), caps.pyreRadius())) {
            if (near.equals(killer) || near.equals(event.getEntity())) {
                continue;
            }
            if (!(near instanceof LivingEntity victim) || near instanceof Player) {
                continue; // 玩家击杀怪物时炸怪，不炸玩家（PVP 不乱杀）
            }
            // v2.23.7 修 P2：不归因（damage(pyre) 而非 damage(pyre, killer)）。
            // 带归因的伤害会再发一个 EntityDamageByEntityEvent，CombatListener
            // 把爆炸当普通挥击结算：掷暴击、按爆炸伤害吸血、扣武器耐久，
            // 链式击杀还会级联放大。溅射侧早已是无归因的 damage(splash)。
            victim.damage(pyre);
            victim.setFireTicks(Math.max(victim.getFireTicks(), 40));
        }
    }

    // ============================================================ 防御侧

    /**
     * 卸力 + 替身。
     *
     * <p>顺序很重要，必须**先卸力、后替身**：</p>
     * <ol>
     *   <li>卸力把这次伤害的一部分推迟到 2 秒后，所以「当场致死」的判定要用剩下的部分；</li>
     *   <li>如果剩下的部分仍然致死，替身再救；</li>
     *   <li>延后的那部分 2 秒后照常扣血，同样过一遍替身检查。</li>
     * </ol>
     *
     * <p>反过来写（先替身后卸力）会让替身替「本来不该死的伤害」触发，
     * 90 秒冷却白白浪费，玩家会觉得这条附魔坏了。</p>
     *
     * <p>v2.23.7 修 P1「卸力对穿甲玩家反而增伤」：HIGH 只记延后**比例**，
     * 真正的切账挪到 {@link #onDamagedFinal}（MONITOR）按**最终伤害**口径算 ——
     * 立即部分照常吃护甲 / 词条减伤，延后部分却曾经是裸伤直接扣血，
     * 总承伤 = (D-d)·r + d &gt; 不带卸力的 D·r，卸力成了增伤词条。
     * 现在立即与延后都按 final×(1-ratio) / final×ratio 切分，两者严格同口径。</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        EnchantEffects.Caps caps = caps();
        EnchantEffects.Mechanics m = playerMechanics(player);
        if (!m.any()) {
            return;
        }

        EnchantState.noteCombat(player);
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            EnchantState.noteAttackedBy(player, byEntity.getDamager());
        }

        double damage = event.getDamage();
        if (damage <= 0) {
            return;
        }

        // ---- 卸力：HIGH 只记比例，MONITOR 才切账（原因见方法注释）
        if (m.delayRatio() > 0) {
            double delayed = fx().delayedPortion(m, caps, damage);
            if (delayed > 0.01) {
                pendingUnload.put(player.getUniqueId(),
                        new PendingUnload(event, Math.min(1.0, delayed / damage)));
            }
        }
    }

    /**
     * 卸力切账 + 替身免死（MONITOR：所有插件改完伤害之后）。
     *
     * <p>为什么在 MONITOR 才动手：护甲 / 词条减伤 / 其它插件都改完之后
     * {@code getFinalDamage()} 才是玩家真正要吃的数。从 BASE 里扣回
     * {@code final × ratio}，立即伤害恰好等于 {@code final × (1-ratio)}，
     * 延后部分总额也是 {@code final × ratio} —— 与不带卸力严格等账，
     * 不受任何插件改伤害的顺序影响。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamagedFinal(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        EnchantEffects.Caps caps = caps();
        EnchantEffects.Mechanics m = playerMechanics(player);
        if (!m.any()) {
            return;
        }

        // ---- 卸力切账：只认 HIGH 那次登记的同一个事件，防串账
        PendingUnload pending = pendingUnload.remove(player.getUniqueId());
        double ratio = pending != null && pending.event() == event ? pending.ratio() : 0;
        if (ratio > 0) {
            double finalDamage = event.getFinalDamage();
            double take = Math.min(finalDamage, finalDamage * ratio);
            if (take > 0.01) {
                try {
                    double base = event.getDamage(EntityDamageEvent.DamageModifier.BASE);
                    event.setDamage(EntityDamageEvent.DamageModifier.BASE, base - take);
                } catch (UnsupportedOperationException unsupported) {
                    // 个别伤害类型不提供 BASE：退化为整单减（延后总额不变）
                    event.setDamage(Math.max(0, event.getDamage() - take));
                }
                scheduleDelayed(player, take, (int) Math.max(1, caps.delayMs() / 50));
            }
        }

        // ---- 替身：切完账后按**最终伤害**判致死（比 HIGH 时按裸伤害更准）
        if (m.cheatHeal() > 0 && wouldKill(player, event.getFinalDamage())) {
            if (EnchantState.cheatDeathReady(player, caps.cheatCooldownMs())) {
                event.setCancelled(true);
                EnchantState.noteCheatDeath(player);
                double max = maxHealthOf(player);
                double heal = fx().cheatHeal(m, caps, max);
                player.setHealth(Math.min(max, Math.max(1.0, heal)));
                player.getWorld().spawnParticle(org.bukkit.Particle.TOTEM_OF_UNDYING,
                        player.getLocation().add(0, 1, 0), 40, 0.5, 0.8, 0.5, 0.3);
                player.playSound(player.getLocation(), org.bukkit.Sound.ITEM_TOTEM_USE, 1.0f, 1.2f);
                Msg.action(player, "<#a78bfa>替身替你受了这一击 <dark_gray>| <gray>冷却 90 秒");
            }
        }
    }

    /** HIGH 记下的待切账卸力（事件引用一起存，MONITOR 只消费同一个事件） */
    private record PendingUnload(EntityDamageEvent event, double ratio) {
    }

    private final java.util.Map<java.util.UUID, PendingUnload> pendingUnload = new java.util.HashMap<>();

    /**
     * 延后伤害：2 秒内分 4 期扣。
     *
     * <p>分期而不是一次性：一次性等于没延后（只是换了个 tick 死）。
     * 分期才能让玩家在中间吃口面包、放个治疗，这才叫「卸力」。</p>
     */
    private void scheduleDelayed(Player player, double total, int ticks) {
        int periods = 4;
        double each = total / periods;
        for (int i = 1; i <= periods; i++) {
            final double part = each;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline() || player.isDead()) {
                    return;
                }
                double hp = player.getHealth();
                if (hp <= part) {
                    // 延后伤害也会致死：先问替身
                    EnchantEffects.Caps caps = caps();
                    EnchantEffects.Mechanics m = playerMechanics(player);
                    if (m.cheatHeal() > 0
                            && EnchantState.cheatDeathReady(player, caps.cheatCooldownMs())) {
                        EnchantState.noteCheatDeath(player);
                        double max = maxHealthOf(player);
                        player.setHealth(Math.min(max, Math.max(1.0, fx().cheatHeal(m, caps, max))));
                        player.getWorld().spawnParticle(org.bukkit.Particle.TOTEM_OF_UNDYING,
                                player.getLocation().add(0, 1, 0), 40, 0.5, 0.8, 0.5, 0.3);
                        Msg.action(player, "<#a78bfa>替身替你受了延后之伤");
                        return;
                    }
                    player.setHealth(0.0);
                    return;
                }
                player.setHealth(hp - part);
            }, (long) ticks / periods * i);
        }
    }

    // ============================================================ 挖矿 / 经验侧

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        EnchantEffects.Caps caps = caps();
        EnchantEffects.Mechanics m = mechanicsOf(player.getInventory().getItemInMainHand());
        if (m.salvageChance() <= 0) {
            return;
        }
        if (!isOre(event.getBlock().getType())) {
            return;
        }
        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() >= m.salvageChance()) {
            return;
        }
        // 给经验而不是给物品：物品会跟时运叠加变成刷矿，经验不会
        int exp = 1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(3);
        player.giveExp(exp);
        player.getWorld().spawnParticle(org.bukkit.Particle.ENCHANT,
                event.getBlock().getLocation().add(0.5, 0.5, 0.5), 8, 0.3, 0.3, 0.3, 0.1);
    }

    private static boolean isOre(Material m) {
        String n = m.name();
        return n.endsWith("_ORE") || n.equals("ANCIENT_DEBRIS");
    }

    // ============================================================ 周期任务（守夜 / 望气）

    /** 由插件启动：每 2 秒跑一次，处理守夜回复与望气侦测 */
    public void tick() {
        EnchantEffects.Caps caps = caps();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            EnchantEffects.Mechanics m = playerMechanics(player);
            if (!m.any()) {
                continue;
            }

            // ---- 守夜：脱战够久才回
            if (m.regenPerTick() > 0
                    && EnchantState.outOfCombat(player, caps.outOfCombatMs())) {
                var attr = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                double max = attr == null ? 20.0 : attr.getValue();
                if (player.getHealth() > 0 && player.getHealth() < max) {
                    double heal = Math.min(m.regenPerTick(), caps.maxRegenPerTick());
                    player.setHealth(Math.min(max, player.getHealth() + heal));
                }
            }

            // ---- 望气：定期提示最近敌人
            if (m.senseRadius() > 0 && EnchantState.senseDue(player, caps.senseIntervalMs())) {
                LivingEntity nearest = null;
                double best = Double.MAX_VALUE;
                for (Entity near : player.getNearbyEntities(m.senseRadius(), m.senseRadius(),
                        m.senseRadius())) {
                    if (!(near instanceof Monster monster)) {
                        continue;
                    }
                    double d = monster.getLocation().distanceSquared(player.getLocation());
                    if (d < best) {
                        best = d;
                        nearest = monster;
                    }
                }
                if (nearest != null) {
                    Msg.action(player, "<#4dc3ff>望气 <dark_gray>| <gray>"
                            + nearest.getType().name().toLowerCase(java.util.Locale.ROOT)
                            + " <white>" + Math.round(Math.sqrt(best)) + "</white> 格外");
                }
            }
        }
    }

    // ============================================================ 清理

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        EnchantState.clear(event.getPlayer());
        pendingUnload.remove(event.getPlayer().getUniqueId());
    }

    /** 目标消失（区块卸载、被移除）时清刻痕，防止表无限长 */
    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        EnchantState.clearMark(event.getEntity());
    }

    /**
     * v2.23.7 修 P2：刻痕/刻印表原来只挂在 {@link EntityDeathEvent} 上清，
     * 怪物随区块卸载 despawn 不触发死亡事件 —— 挂机打怪场景下每玩家的
     * 表能积累上千条永不清理的 UUID。Paper 的
     * {@link com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent}
     * 覆盖死亡 / despawn / 区块卸载三条路，统一在这里清。
     */
    @EventHandler
    public void onEntityRemove(com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent event) {
        EnchantState.clearMark(event.getEntity());
    }

    // ============================================================ 工具

    private static double maxHealthOf(LivingEntity entity) {
        var attr = entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        return attr == null ? 20.0 : attr.getValue();
    }

    /** 这次伤害会不会打死玩家 */
    private static boolean wouldKill(Player player, double damage) {
        // 护甲减伤还没结算，所以要留余量：只要「减伤前的伤害」可能致死就问一次。
        // 宁可多触发一次替身（有 90 秒冷却兜着），也不能让该救的没救。
        return player.getHealth() - damage <= 0.5;
    }
}