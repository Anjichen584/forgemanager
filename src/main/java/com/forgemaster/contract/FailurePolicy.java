package com.forgemaster.contract;

import java.util.UUID;

/**
 * 「交不上怎么办」——失败惩罚策略接口（<b>暂定</b>，未接入任何玩法循环）。
 *
 * <p>ForgeTax 里交不上 = 死，服务器语境下可选：破契约重开（没收圣物 + 征走成品剑）、
 * 滚雪球欠款、或宽限 1-2 次再死。定下来之前一切调用都走 no-op。</p>
 */
public interface FailurePolicy {

    /**
     * 交不上税的结算。
     *
     * @param missedStreak 连续没交够的次数（含这一次）
     * @return 给玩家看的结算文本（MiniMessage）；no-op 实现返回空串
     */
    String onMissed(UUID playerId, int day, long shortfall, int missedStreak);
}
