package com.forgemaster.quench;

import org.bukkit.Material;

import java.util.List;

/**
 * 淬火介质。不同介质给不同词条池 + 评分加成。
 */
public final class Quench {

    private final String id;
    private final String display;
    private final String color;
    private final Material icon;
    private final double scoreBonus;
    private final List<String> pool;
    private final String description;

    public Quench(String id, String display, String color, Material icon, double scoreBonus,
                  List<String> pool, String description) {
        this.id = id;
        this.display = display;
        this.color = color;
        this.icon = icon;
        this.scoreBonus = scoreBonus;
        this.pool = pool;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public String display() {
        return display;
    }

    public String color() {
        return color;
    }

    public Material icon() {
        return icon;
    }

    public double scoreBonus() {
        return scoreBonus;
    }

    public List<String> pool() {
        return pool;
    }

    public String description() {
        return description;
    }

    public String colored() {
        return "<color:" + color + ">" + display + "</color>";
    }
}