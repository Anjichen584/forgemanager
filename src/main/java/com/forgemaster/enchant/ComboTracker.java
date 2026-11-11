package com.forgemaster.enchant;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连击附魔的叠层状态（v2.23.2）。
 *
 * <p>「连击」是唯一一条需要**跨事件记状态**的原创附魔，所以单独拎出来。
 * 状态按「玩家 → 目标 + 时间戳 + 层数」存，规则：</p>
 * <ul>
 *   <li>打同一个目标、且间隔在窗口内 → 层数 +1；</li>
 *   <li>换目标、或超时 → 从 1 重新数；</li>
 *   <li>层数封顶由 {@code enchant.caps.combo-stacks} 决定。</li>
 * </ul>
 *
 * <p>注意这里有意的语义：{@link #consecutive} 返回的是<b>本刀之前已经连了几刀</b>。
 * 第一刀返回 0（没有连击加成），第二刀返回 1，以此类推 ——
 * 「第一刀就吃满连击」会让它变成一条纯常驻增伤，失去设计意义。</p>
 *
 * <p>内存安全：每个玩家只留一条记录（{@link Map} 按 uuid，覆盖写），
 * 玩家下线时 {@link #clear} 清掉，不会长成泄漏。</p>
 */
public final class ComboTracker {

    /** 一次连击记录 */
    private record Track(UUID target, long at, int count) {
    }

    private static final Map<UUID, Track> MAP = new ConcurrentHashMap<>();

    private ComboTracker() {
    }

    /**
     * 记一刀，并返回「这刀之前已经连了几刀」。
     *
     * @param windowMillis 连击窗口（毫秒），超过就重新计数
     * @param maxCount     最多数到几层（防止长驻战斗把层数堆成天文数字）
     */
    public static int consecutive(Player player, Entity target, long windowMillis, int maxCount) {
        if (player == null || target == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        Track prev = MAP.get(id);
        int count;
        if (prev != null
                && prev.target().equals(target.getUniqueId())
                && now - prev.at() <= Math.max(0, windowMillis)) {
            count = Math.min(prev.count() + 1, Math.max(1, maxCount));
        } else {
            count = 0; // 换了目标或超时：这一刀是新的第一刀
        }
        MAP.put(id, new Track(target.getUniqueId(), now, count));
        return count;
    }

    /** 玩家下线时清掉 */
    public static void clear(Player player) {
        if (player != null) {
            MAP.remove(player.getUniqueId());
        }
    }

    /** 当前记录（测试/面板用） */
    public static int peek(Player player) {
        Track t = player == null ? null : MAP.get(player.getUniqueId());
        return t == null ? 0 : t.count();
    }
}