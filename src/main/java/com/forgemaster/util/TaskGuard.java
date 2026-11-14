package com.forgemaster.util;

/**
 * 周期任务守卫（v2.26.6）：把 Bukkit 重复任务的入口包一层 try，异常只记日志不清任务。
 *
 * <p>为什么需要它：Bukkit 的 {@code runTaskTimer} 里抛一次 RuntimeException 就会被
 * **永久取消**——之后这个功能整个停摆，只能重启救。v2.25.2 在 ContractManager.tickClock
 * 上第一次踩到并单独包了 try，v2.26.6 审计轮发现同款裸奔遍布全仓（大赛 ticker、
 * 世界事件 ticker、赛季结算 ticker、名人堂刷新、殿堂 tick、剧情 check、行会周结算、
 * 附魔周期任务），统一在这里收口。</p>
 *
 * <p>用法：把 {@code runTaskTimer(plugin, this::tick, ...)} 改成
 * {@code runTaskTimer(plugin, TaskGuard.guard("XxxManager.tick", this::tick), ...)}。
 * 语义：跳过本轮、下一轮重试——与 ContractManager.tickClock 的既有口径一致。</p>
 *
 * <p><b>约定：被 guard 的任务体必须可安全重入</b>——异常可能发生在任务中途，
 * 下一轮会从头重跑。结算类任务体要保证归档/落盘是覆盖写、重跑不重复发放
 * （参照 ContractStore 游标与 SeasonManager.end 的幂等口径）。</p>
 */
public final class TaskGuard {

    private TaskGuard() {
    }

    public static Runnable guard(String what, Runnable body) {
        return () -> {
            try {
                body.run();
            } catch (RuntimeException ex) {
                org.bukkit.Bukkit.getLogger().warning("[任务守卫] " + what
                        + " 抛异常，跳过本轮（下一轮重试）：" + ex);
            }
        };
    }
}
