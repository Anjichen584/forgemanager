package com.forgemaster.enchant;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 机制型附魔需要的**跨事件状态**（v2.23.3）。
 *
 * <p>上一版只有「连击」需要记状态；这一版的 12 条里有一半需要：</p>
 * <ul>
 *   <li><b>蓄锋</b>：距上次命中过了多久</li>
 *   <li><b>刻痕</b>：对某个目标累计削了多少血</li>
 *   <li><b>替身</b>：距离上次免死过了多久</li>
 *   <li><b>回响</b>：最近被谁打过</li>
 *   <li><b>守夜</b>：上一次进入战斗是什么时候</li>
 *   <li><b>望气</b>：上一次侦测是什么时候</li>
 *   <li><b>淬锋</b>（v2.24 切片一）：当前锋锐层数（命中叠层、受击清零）</li>
 * </ul>
 *
 * <h2>内存纪律</h2>
 * 这些表都是「按玩家 UUID 存一条」，不是「按次存」。所以：</p>
 * <ol>
 *   <li>每个玩家最多 1 条记录，不会随在线时长增长；</li>
 *   <li>玩家下线时 {@link #clear} 必须清 —— 不清的话内存会随「来过多少人」线性涨；</li>
 *   <li>刻痕表按「玩家 → (目标 uuid → 累计伤害)」存，目标死亡时**必须**清掉它，
 *       否则杀过一万只怪就留一万条垃圾。</li>
 * </ol>
 */
public final class EnchantState {

    private EnchantState() {
    }

    /** 一个玩家的全部机制状态 */
    private static final class Data {
        /** 上次命中任何目标的时刻（蓄锋用） */
        long lastHitAt;
        /** 上次受击时刻（守夜用：脱战判定） */
        long lastCombatAt;
        /** 上次替身免死时刻（0 = 从没用过） */
        long lastCheatAt;
        /** 最近攻击我的实体（回响用） */
        UUID lastAttacker;
        /** 最近被攻击的时刻（回响窗口） */
        long lastAttackerAt;
        /** 上次望气侦测时刻 */
        long lastSenseAt;
        /** 刻痕：目标 uuid → 累计伤害 */
        final Map<UUID, Double> marks = new java.util.HashMap<>();
        /** 已被刻印的目标 */
        final java.util.Set<UUID> branded = new java.util.HashSet<>();
        /** 淬锋（v2.24 切片一）：当前锋锐层数。命中 +1、受击清零，不落盘 */
        int streakStacks;

        Data() {
        }
    }

    private static final Map<UUID, Data> MAP = new ConcurrentHashMap<>();

    private static Data of(Player player) {
        return MAP.computeIfAbsent(player.getUniqueId(), k -> new Data());
    }

    // ------------------------------------------------------------ 蓄锋

    /** 距上次命中的毫秒数（从没命中过 = 极长，等价于「蓄满了」） */
    public static long msSinceLastHit(Player player) {
        if (player == null) {
            return Long.MAX_VALUE / 4;
        }
        Data d = MAP.get(player.getUniqueId());
        if (d == null || d.lastHitAt <= 0) {
            return Long.MAX_VALUE / 4;
        }
        return Math.max(0, System.currentTimeMillis() - d.lastHitAt);
    }

    /** 记一次命中（蓄锋用） */
    public static void noteHit(Player player) {
        if (player == null) {
            return;
        }
        of(player).lastHitAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------ 刻痕

    /**
     * 累计对某个目标造成的伤害，够阈值就刻印。
     *
     * @param threshold 目标最大生命的比例（{@code enchant.caps.mark-threshold}）
     * @return true = 这一刻刚刚完成刻印（调用方可以播个特效）
     */
    public static boolean addMarkDamage(Player player, Entity target, double damage,
                                       double targetMaxHealth, double threshold) {
        if (player == null || target == null || damage <= 0 || targetMaxHealth <= 0) {
            return false;
        }
        Data d = of(player);
        UUID id = target.getUniqueId();
        if (d.branded.contains(id)) {
            return false; // 已经刻印过，不再累计
        }
        double total = d.marks.merge(id, damage, Double::sum);
        if (total >= targetMaxHealth * Math.max(0.01, threshold)) {
            d.branded.add(id);
            d.marks.remove(id);
            return true;
        }
        return false;
    }

    /** 这个目标是否已被刻印 */
    public static boolean isBranded(Player player, Entity target) {
        if (player == null || target == null) {
            return false;
        }
        Data d = MAP.get(player.getUniqueId());
        return d != null && d.branded.contains(target.getUniqueId());
    }

    /** 目标死亡或消失时清掉它的刻痕（**必须掉**：否则留垃圾） */
    public static void clearMark(Entity target) {
        if (target == null) {
            return;
        }
        UUID id = target.getUniqueId();
        for (Data d : MAP.values()) {
            d.marks.remove(id);
            d.branded.remove(id);
        }
    }

    // ------------------------------------------------------------ 淬锋（v2.24 切片一）

    /**
     * 命中时叠一层锋锐，封顶 {@code maxStacks}。
     *
     * <p>封顶在状态层就做掉（而不是只靠结算处的夹子）：这样配置被写成离谱大数时，
     * 层数本身也涨不上去，结算侧的 {@code EnchantEffects.streakBonus} 只需再夹一道。</p>
     */
    public static void noteHitStreak(Player player, int maxStacks) {
        if (player == null || maxStacks <= 0) {
            return;
        }
        Data d = of(player);
        // 封顶逻辑只有一份：EnchantEffects.nextStreakStack（自检钉的就是它）
        d.streakStacks = EnchantEffects.nextStreakStack(d.streakStacks, maxStacks);
    }

    /** 当前锋锐层数（0 = 还没叠过 / 刚被清掉） */
    public static int streakOf(Player player) {
        if (player == null) {
            return 0;
        }
        Data d = MAP.get(player.getUniqueId());
        return d == null ? 0 : d.streakStacks;
    }

    /**
     * 受击清零。**任何伤害都要清**（包括毒 / 摔落 / 火 tick）——
     * 机制的卖点就是「不挨打才有收益」，漏掉一种伤害类型就等于给那条路开白嫖通道。
     */
    public static void clearStreak(Player player) {
        if (player == null) {
            return;
        }
        Data d = MAP.get(player.getUniqueId());
        if (d != null) {
            d.streakStacks = 0;
        }
    }

    // ------------------------------------------------------------ 替身

    /** 替身是否可用（冷却结束） */
    public static boolean cheatDeathReady(Player player, long cooldownMs) {
        if (player == null) {
            return false;
        }
        Data d = MAP.get(player.getUniqueId());
        if (d == null || d.lastCheatAt <= 0) {
            return true;
        }
        return System.currentTimeMillis() - d.lastCheatAt >= Math.max(0, cooldownMs);
    }

    /** 距离替身冷却结束还有多少毫秒（0 = 好了） */
    public static long cheatDeathRemaining(Player player, long cooldownMs) {
        if (player == null) {
            return 0;
        }
        Data d = MAP.get(player.getUniqueId());
        if (d == null || d.lastCheatAt <= 0) {
            return 0;
        }
        long left = Math.max(0, cooldownMs) - (System.currentTimeMillis() - d.lastCheatAt);
        return Math.max(0, left);
    }

    /** 记一次替身触发 */
    public static void noteCheatDeath(Player player) {
        if (player != null) {
            of(player).lastCheatAt = System.currentTimeMillis();
        }
    }

    // ------------------------------------------------------------ 回响

    /** 记一次受击：谁打的我、什么时候 */
    public static void noteAttackedBy(Player player, Entity attacker) {
        if (player == null || attacker == null) {
            return;
        }
        // v2.23.7 修 P2：被箭 / 三叉戟打中时 damager 是投掷物本身，
        // 之前记的是箭的 UUID，回响拿射手 UUID 来比对恒 false ——
        // 回响对远程攻击者从不生效。这里把投掷物展开成射手。
        Entity effective = attacker;
        if (attacker instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof org.bukkit.entity.LivingEntity shooter) {
            effective = shooter;
        }
        Data d = of(player);
        d.lastAttacker = effective.getUniqueId();
        d.lastAttackerAt = System.currentTimeMillis();
        d.lastCombatAt = d.lastAttackerAt;
    }

    /** 这个目标是不是刚刚打过我（回响窗口内） */
    public static boolean isRetaliationTarget(Player player, Entity target, long windowMs) {
        if (player == null || target == null) {
            return false;
        }
        Data d = MAP.get(player.getUniqueId());
        if (d == null || d.lastAttacker == null) {
            return false;
        }
        if (!d.lastAttacker.equals(target.getUniqueId())) {
            return false;
        }
        return System.currentTimeMillis() - d.lastAttackerAt <= Math.max(0, windowMs);
    }

    // ------------------------------------------------------------ 守夜（脱战）

    /** 记一次进入战斗 */
    public static void noteCombat(Player player) {
        if (player != null) {
            of(player).lastCombatAt = System.currentTimeMillis();
        }
    }

    /** 是否已脱离战斗够久 */
    public static boolean outOfCombat(Player player, long outOfCombatMs) {
        if (player == null) {
            return false;
        }
        Data d = MAP.get(player.getUniqueId());
        if (d == null || d.lastCombatAt <= 0) {
            return true; // 从没打过架
        }
        return System.currentTimeMillis() - d.lastCombatAt >= Math.max(0, outOfCombatMs);
    }

    // ------------------------------------------------------------ 望气

    /** 望气节流：到了间隔就返回 true 并记时 */
    public static boolean senseDue(Player player, long intervalMs) {
        if (player == null) {
            return false;
        }
        Data d = of(player);
        long now = System.currentTimeMillis();
        if (d.lastSenseAt > 0 && now - d.lastSenseAt < Math.max(0, intervalMs)) {
            return false;
        }
        d.lastSenseAt = now;
        return true;
    }

    // ------------------------------------------------------------ 清理

    /** 玩家下线：整条清掉，防内存随来过多少人线性增长 */
    public static void clear(Player player) {
        if (player != null) {
            MAP.remove(player.getUniqueId());
        }
    }

    /** 当前在追踪多少玩家的状态（自检/面板用） */
    public static int tracked() {
        return MAP.size();
    }
}