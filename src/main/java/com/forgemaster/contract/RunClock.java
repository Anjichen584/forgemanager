package com.forgemaster.contract;

/**
 * 「一天怎么算」——龙税契约的时间尺度接口（<b>暂定</b>，未接入任何玩法循环）。
 *
 * <p>ForgeTax 的节奏是「白天锻造、晚上交租」，一天的定义决定整个循环的
 * 压迫感。实现方可以选：MC 游戏日（约 20 分钟/天）、现实时间天、
 * 或按交税次数驱动。默认实现 {@link ContractRegistry#noop()} 恒返回「不推进」。</p>
 */
public interface RunClock {

    /** 当前是第几天（1 开始，单调递增） */
    int day();

    /** 当前天还剩多少 tick（用于倒计时 UI）；时钟不推进时返回 -1 */
    long ticksRemaining();

    /** 是否到了今晚交税的时刻（每个天只应触发一次 true） */
    boolean isTributeDue();
}
