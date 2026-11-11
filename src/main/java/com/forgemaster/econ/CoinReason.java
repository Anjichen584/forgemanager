package com.forgemaster.econ;

import java.util.Locale;

/**
 * 剑币流动的「理由」标签（v2.20 · 服主数据看板的地基）。
 *
 * <p>为什么需要它：原来 {@code StatsStore.addCoins(uuid, amount)} / {@code takeCoins}
 * 只知道「加了多少 / 扣了多少」，不知道钱从哪来、到哪去。于是所有关于经济的结论都只能
 * 靠静态配置推演——「打一把剑赚 102 剑币」「强化 +40 要 62 把剑」全是模型值，
 * 真实服务器上哪个模块在吸币、产出与消耗是否平衡，一个数字都看不到。</p>
 *
 * <p>设计取舍：<b>先粗后细</b>。这里给的是 8~10 个粗桶，看板一眼能看懂；
 * 细化只需往枚举里加值，调用点不用返工。粗桶永远存在，细桶按需增加。</p>
 *
 * <p>持久化用 {@link #key()}（小写短横线），不用 {@code name()}——
 * 这样枚举常量改名不会让历史快照失配。</p>
 */
public enum CoinReason {

    // ---------------------------------------------------------------- 产出
    FORGE("forge", "锻造奖励", Flow.PRODUCE),
    COMMISSION("commission", "委托交货", Flow.PRODUCE),
    CONTEST("contest", "大赛名次", Flow.PRODUCE),
    AUCTION("auction", "拍卖成交", Flow.PRODUCE),
    SIGNIN("signin", "每日签到", Flow.PRODUCE),
    BOUNTY("bounty", "悬赏", Flow.PRODUCE),
    TOMB("tomb", "剑冢挑战", Flow.PRODUCE),
    ACHIEVEMENT("achievement", "成就奖励", Flow.PRODUCE),
    /** 通行证等级奖励：混进成就桶就看不出「赛季奖励在发多少钱」 */
    PASS("pass", "通行证奖励", Flow.PRODUCE),
    /** 图鉴 / 名剑谱收集奖励 */
    CODEX("codex", "图鉴名谱", Flow.PRODUCE),
    MENTOR("mentor", "师徒分成", Flow.PRODUCE),
    /** 龙税周目：交满贡品的赏金（既不是退款也不是成就，单列才看得出周目经济） */
    CONTRACT("contract", "龙税贡品", Flow.PRODUCE),
    /** v2.24 声望兑换所：拿声望换剑币（单列才看得出「声望变现」的量） */
    REDEEM("redeem", "声望兑换", Flow.PRODUCE),
    /** 退款 / 回滚（拍卖流拍、扣款失败回退、订单断线续做）——不算真实产出，报表里单列 */
    REFUND("refund", "退款回滚", Flow.PRODUCE),
    OTHER_IN("other-in", "其它产出", Flow.PRODUCE),

    // ---------------------------------------------------------------- 消耗
    SHOP("shop", "商会采购", Flow.CONSUME),
    STRENGTHEN("strengthen", "强化费", Flow.CONSUME),
    EVOLVE("evolve", "蜕变费", Flow.CONSUME),
    GEM("gem", "宝石台", Flow.CONSUME),
    ENCHANT("enchant", "附魔台", Flow.CONSUME),
    REFORGE("reforge", "重铸费", Flow.CONSUME),
    IMPRINT("imprint", "词条符", Flow.CONSUME),
    DISSOLVE("dissolve", "剑魄分解", Flow.CONSUME),
    AUCTION_TAX("auction-tax", "拍卖抽成", Flow.CONSUME),
    /**
     * 拍卖买入（买家付款）。
     *
     * <p>刻意记成「消耗」而不是中性转移：买家侧 AUCTION_BUY 与卖家侧 AUCTION
     * 之差，正好就是被系统吃掉的抽成——看板并排两行就能直接读出抽成率。
     * 若归入 other-out，这笔会淹没在「其它消耗」里。</p>
     */
    AUCTION_BUY("auction-buy", "拍卖买入", Flow.CONSUME),
    BLACKMARKET("blackmarket", "黑市", Flow.CONSUME),
    TOMB_FEE("tomb-fee", "剑冢费用", Flow.CONSUME),
    GUILD("guild", "行会", Flow.CONSUME),
    /** v2.25：专精洗点（从 OTHER_OUT 分出来，报表看得出洗点总量） */
    TALENT_RESET("talent-reset", "天赋洗点", Flow.CONSUME),
    OTHER_OUT("other-out", "其它消耗", Flow.CONSUME);

    /** 钱往哪个方向走 */
    public enum Flow {
        PRODUCE, CONSUME
    }

    private final String key;
    private final String label;
    private final Flow flow;

    CoinReason(String key, String label, Flow flow) {
        this.key = key;
        this.label = label;
        this.flow = flow;
    }

    /** 持久化键：小写短横线，改名不破坏历史快照 */
    public String key() {
        return key;
    }

    /** 报表里显示的中文名 */
    public String label() {
        return label;
    }

    public Flow flow() {
        return flow;
    }

    public boolean isProduce() {
        return flow == Flow.PRODUCE;
    }

    /** 按持久化键反查；认不出就按方向落到 OTHER_*，绝不返回 null */
    public static CoinReason byKey(String key, Flow fallbackFlow) {
        if (key != null) {
            String k = key.toLowerCase(Locale.ROOT);
            for (CoinReason r : values()) {
                if (r.key.equals(k)) {
                    return r;
                }
            }
        }
        return fallbackFlow == Flow.PRODUCE ? OTHER_IN : OTHER_OUT;
    }

    /** 报表排序用：先方向（产出在前），再按 key */
    public String sortKey() {
        return (isProduce() ? "0-" : "1-") + key;
    }
}