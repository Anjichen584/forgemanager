package com.forgemaster.form;

/**
 * 剑型（ForgeTax sword forms 的移植）：同一炉里锻出来的剑按权重落进不同剑型，
 * 剑型只影响估值与外观，不改属性——出货的 variety 靠它。
 *
 * @param id        剑型 id（写进 PDC）
 * @param display   显示名（影太刀 / 重剑…）
 * @param color     Lore 颜色（MiniMessage hex）
 * @param weight    抽取权重
 * @param valueMult 稀有度乘区（v2.26.7 起仅作展示口径，龙税估值随契约移除）
 */
public record SwordForm(String id, String display, String color, int weight, double valueMult) {

    /** config 没配 forms 段时的内置默认 */
    public static SwordForm[] defaults() {
        return new SwordForm[]{
                new SwordForm("plain", "朴素", "#9e9e9e", 34, 1.0),
                new SwordForm("shadow", "影太刀", "#7fd3ff", 24, 1.25),
                new SwordForm("heavy", "重剑", "#ffb84d", 20, 1.45),
                new SwordForm("famed", "名刀", "#b06bff", 14, 1.8),
                new SwordForm("moon", "偃月", "#ff3d6e", 8, 2.3),
        };
    }
}
