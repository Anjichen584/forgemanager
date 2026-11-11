package com.forgemaster.ranking;

import com.forgemaster.stats.StatsStore;
import org.bukkit.Material;

import java.util.UUID;

/**
 * 排行维度。A4 的排行 GUI / 命令与 E2 的赛季归档共用这一份定义。
 *
 * <p>注意 {@link #STRENGTHEN}：锻造等级没有任何历史存档（StatsStore 里没有这个字段），
 * 它的数值来自 {@link StrengthenSnapshot} 的<b>历史最高</b>，所以
 * {@link #value(StatsStore.Stats)} 对它恒返回 0，真实数值要走
 * {@link Rankings#valueOf(Metric, UUID, StatsStore.Stats)}。</p>
 */
public enum Metric {

    COINS("剑币", Material.GOLD_INGOT, "coins", "jianbi"),
    REPUTATION("声望", Material.ENCHANTED_BOOK, "reputation", "shengwang"),
    STREAK("连锻", Material.BLAZE_POWDER, "streak", "lianduan"),
    LEVEL("专精", Material.EXPERIENCE_BOTTLE, "level", "zhuanjing"),
    STRENGTHEN("强化", Material.NETHERITE_SWORD, "strengthen", "qianghua", "forge-level", "duanzao");

    private final String label;
    private final Material icon;
    private final String[] aliases;

    Metric(String label, Material icon, String... aliases) {
        this.label = label;
        this.icon = icon;
        this.aliases = aliases;
    }

    public String label() {
        return label;
    }

    public Material icon() {
        return icon;
    }

    /** 是否走「强化榜」专用数据源而不是档案字段（目前只有锻造等级） */
    public boolean online() {
        return this == STRENGTHEN;
    }

    /** 排序用的数值。连锻只计算仍在有效期内的链（超过静默期即视为断链）。 */
    public double value(StatsStore.Stats st) {
        return switch (this) {
            case COINS -> st.coins;
            case REPUTATION -> st.reputation;
            case STREAK -> (st.streakAt > 0 && System.currentTimeMillis() - st.streakAt <= StatsStore.STREAK_WINDOW_MS)
                    ? st.streak : 0;
            // 等级优先，同级比经验（经验上限 < 100000）
            case LEVEL -> st.level * 100000.0 + st.exp;
            // 锻造等级没有历史存档：数值由 Rankings#valueOf 从历史最高取，这里只能是 0
            case STRENGTHEN -> 0;
        };
    }

    /** 展示文本 */
    public String display(StatsStore.Stats st) {
        return switch (this) {
            case COINS -> st.coins + " 剑币";
            case REPUTATION -> st.reputation + " 声望 · " + StatsStore.titleOf(st.reputation);
            case STREAK -> "第 " + st.streak + " 连（最高 " + st.bestStreak + "）";
            case LEVEL -> "Lv." + st.level + "（" + st.exp + "/" + StatsStore.expToNext(st.level) + "）";
            // 不带 UUID 的旧接口：靠档案对象反查快照（StatsStore 缓存的对象是唯一的）
            case STRENGTHEN -> {
                StrengthenSnapshot.Best b = StrengthenSnapshot.forStats(st);
                yield b == null ? "未上榜（历史最高）" : "+" + b.level() + "/+" + b.cap() + "（历史最高）";
            }
        };
    }

    /** 展示文本（带 UUID 版本：强化维度按 UUID 查历史最高，其余与 display(st) 一致） */
    public String display(UUID uuid, StatsStore.Stats st) {
        if (!online()) {
            return display(st);
        }
        StrengthenSnapshot.Best b = StrengthenSnapshot.bestOf(uuid);
        return b == null ? "未上榜（历史最高）" : "+" + b.level() + "/+" + b.cap() + "（历史最高）";
    }

    /** 按名字 / 别名 / 中文名解析，找不到返回 null */
    public static Metric parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        for (Metric m : values()) {
            if (m.name().equalsIgnoreCase(s) || m.label.equals(s)) {
                return m;
            }
            for (String a : m.aliases) {
                if (a.equalsIgnoreCase(s)) {
                    return m;
                }
            }
        }
        return null;
    }
}
