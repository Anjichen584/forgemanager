package com.forgemaster.material;

/**
 * 材料品质分级：同一种矿石也有粗细之分，直接影响成品上限。
 *
 * <p>玩家背包里的材料按 PDC 标记分档；锻造时优先消耗高档材料。</p>
 */
public enum MaterialGrade {

    /** 普通料：一切按标准算 */
    COMMON("普通料", "<gray>", 0.0, 0, "#9e9e9e"),

    /** 精炼料：上限 +6%，词条不变 */
    REFINED("精炼料", "<#7ef2c8>", 6.0, 0, "#7ef2c8"),

    /** 天外陨铁：上限 +12%，额外 1 条词条，成品发光 */
    CELESTIAL("天外料", "<#ffd166>", 12.0, 1, "#ffd166");

    private final String display;
    private final String color;
    private final double scoreBonus;
    private final int extraAffixes;
    private final String hex;

    MaterialGrade(String display, String color, double scoreBonus, int extraAffixes, String hex) {
        this.display = display;
        this.color = color;
        this.scoreBonus = scoreBonus;
        this.extraAffixes = extraAffixes;
        this.hex = hex;
    }

    public String display() {
        return display;
    }

    public String color() {
        return color;
    }

    /** 结算时额外加的分数 */
    public double scoreBonus() {
        return scoreBonus;
    }

    /** 额外词条数 */
    public int extraAffixes() {
        return extraAffixes;
    }

    public String hex() {
        return hex;
    }

    public String colored() {
        return "<" + hex + ">" + display + "</" + hex + ">";
    }

    public boolean atLeast(MaterialGrade other) {
        return ordinal() >= other.ordinal();
    }

    public static MaterialGrade byName(String name) {
        if (name == null) {
            return COMMON;
        }
        for (MaterialGrade g : values()) {
            if (g.name().equalsIgnoreCase(name)) {
                return g;
            }
        }
        return COMMON;
    }

    /** 升一档（已经是最高则原样返回） */
    public MaterialGrade upgraded() {
        int next = Math.min(values().length - 1, ordinal() + 1);
        return values()[next];
    }
}