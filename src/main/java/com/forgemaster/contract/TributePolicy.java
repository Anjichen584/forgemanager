package com.forgemaster.contract;

import java.util.List;
import java.util.UUID;

/**
 * 「税额怎么定 / 怎么收」——贡品验收策略接口（<b>暂定</b>，未接入任何玩法循环）。
 *
 * <p>ForgeTax 的龙按剑的总价值验收；这里把「价值怎么算、算谁的、
 * 收什么」抽象出来，留给后面定：单人契约还是全服共担、
 * 按品质估值还是按投入估值、宽限几次。</p>
 */
public interface TributePolicy {

    /** 第 day 天要交的最低总价值 */
    long requiredValue(int day, UUID playerId);

    /**
     * 验一批贡品剑，返回这批的总估值。
     *
     * @param day   第几天
     * @param items 玩家递进贡品窗口的锻造品
     */
    long appraise(int day, List<org.bukkit.inventory.ItemStack> items);

    /** 这笔税是否已经交够 */
    default boolean satisfied(int day, UUID playerId, long appraisedValue) {
        return appraisedValue >= requiredValue(day, playerId);
    }
}
