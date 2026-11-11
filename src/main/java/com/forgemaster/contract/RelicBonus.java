package com.forgemaster.contract;

/**
 * 圣物聚合加成：把一个玩家 run 内拥有的全部圣物折成一组数值，
 * 强化 / 估值 / 税额各处只认这一个聚合结果，不逐个查圣物 id。
 *
 * @param success          强化成功率加成（百分点，0.04 = +4%）
 * @param great            大成概率加成（百分点）
 * @param breakMult        碎裂率乘区（0.85 = -15%）
 * @param valueMult        剑估值乘区
 * @param taxMult          税额乘区（0.9 = 税额打九折）
 * @param frenzyExtra      炉魂狂热额外次数
 * @param comboKeep        损伤/空锤不断连击
 * @param inherit          剑魂传承比例加成（百分点）
 * @param furnacePerFail   炉火每次失败额外攒点
 * @param cornerstoneExtra 基石护体额外次数
 * @param phoenix          凤凰灰：每个周目日第一次碎裂免碎（等级减半）
 */
public record RelicBonus(double success, double great, double breakMult, double valueMult,
                         double taxMult, int frenzyExtra, boolean comboKeep, double inherit,
                         int furnacePerFail, int cornerstoneExtra, boolean phoenix) {

    public static final RelicBonus ZERO = new RelicBonus(
            0, 0, 1.0, 1.0, 1.0, 0, false, 0, 0, 0, false);
}
