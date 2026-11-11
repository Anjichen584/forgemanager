package com.forgemaster.quality;

/**
 * 成品品质。评分越高，品质越好，词条越多，属性倍率越大。
 */
public enum Quality {

    ROUGH("粗糙", "#8d8d8d", 0.0, 0, 0.55, "劣质", 0.5),
    COMMON("普通", "#eeeeee", 35.0, 1, 0.80, "", 1.0),
    FINE("精良", "#4ee36b", 55.0, 2, 1.00, "优良", 1.5),
    EPIC("史诗", "#b06bff", 72.0, 3, 1.22, "稀世", 2.2),
    LEGENDARY("传说", "#ffb84d", 86.0, 4, 1.45, "传世", 3.0),
    MYTHIC("神话", "#ff3d6e", 95.0, 5, 1.75, "不朽", 5.0);

    private final String display;
    private final String color;
    private final double minScore;
    private final int affixCount;
    private final double power;
    private final String title;
    private final double durabilityMult;

    Quality(String display, String color, double minScore, int affixCount, double power,
            String title, double durabilityMult) {
        this.display = display;
        this.color = color;
        this.minScore = minScore;
        this.affixCount = affixCount;
        this.power = power;
        this.title = title;
        this.durabilityMult = durabilityMult;
    }

    public String display() {
        return display;
    }

    public String color() {
        return color;
    }

    public double minScore() {
        return minScore;
    }

    public int affixCount() {
        return affixCount;
    }

    public double power() {
        return power;
    }

    public String title() {
        return title;
    }

    /** 耐久倍数：劣质半把，神话五倍 */
    public double durabilityMult() {
        return durabilityMult;
    }

    /** 该品质最多能开几个宝石孔 */
    public int socketLimit() {
        return switch (this) {
            case ROUGH, COMMON -> 1;
            case FINE -> 2;
            case EPIC -> 3;
            case LEGENDARY -> 4;
            case MYTHIC -> 5;
        };
    }

    public String colored() {
        return "<color:" + color + ">" + display + "</color>";
    }

    /** 界面里代表这个品质的物品图标 */
    public org.bukkit.Material icon() {
        return switch (this) {
            case ROUGH -> org.bukkit.Material.STONE_SWORD;
            case COMMON -> org.bukkit.Material.IRON_SWORD;
            case FINE -> org.bukkit.Material.GOLDEN_SWORD;
            case EPIC -> org.bukkit.Material.DIAMOND_SWORD;
            case LEGENDARY -> org.bukkit.Material.NETHERITE_SWORD;
            case MYTHIC -> org.bukkit.Material.NETHERITE_SWORD;
        };
    }

    public static Quality of(double score) {
        Quality result = ROUGH;
        for (Quality q : values()) {
            if (score >= q.minScore) {
                result = q;
            }
        }
        return result;
    }

    public static Quality byName(String name) {
        for (Quality q : values()) {
            if (q.name().equalsIgnoreCase(name) || q.display.equals(name)) {
                return q;
            }
        }
        return null;
    }
}