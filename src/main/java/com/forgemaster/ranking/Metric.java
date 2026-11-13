package com.forgemaster.ranking;

import com.forgemaster.stats.StatsStore;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 排行维度。A4 的排行 GUI / 命令与 E2 的赛季归档共用这一份定义。
 *
 * <p>注意 {@link #STRENGTHEN}：锻造等级没有任何历史存档（StatsStore 里没有这个字段），
 * 它的数值来自 {@link StrengthenSnapshot} 的<b>历史最高</b>，所以
 * {@link #value(StatsStore.Stats)} 对它恒返回 0，真实数值要走
 * {@link Rankings#valueOf(Metric, UUID, StatsStore.Stats)}。</p>
 *
 * <h2>口径（v2.26.0 补）：哪些维度是「本赛季」，哪些是「生涯累计」</h2>
 * <pre>
 * 维度        取值字段                        resetSeasonal 清？  口径
 * COINS       coins                           清                  本赛季
 * REPUTATION  reputation                      清                  本赛季
 * STREAK      streak / streakAt / bestStreak  清                  本赛季
 * LEVEL       level × 100000 + exp            不清                生涯累计
 * STRENGTHEN  StrengthenSnapshot 历史最高      不清                生涯累计
 * DUEL        duelWins / duelLosses           不清（刻意）        生涯累计
 * </pre>
 *
 * <p>这张表不是装饰：{@code SeasonManager.end()} 把<b>每个</b>维度都写进赛季档案，
 * 而「不清」的那三维度写进去的是当下的生涯累计榜 —— 读档案的人很容易把
 * 「第 N 季 比试榜」误读成「第 N 季打了多少场」。口径由 {@link #career()} 显式给出，
 * 归档时一并落盘，见 {@code SeasonManager#end(String)}。</p>
 */
public enum Metric {

    COINS("剑币", Material.GOLD_INGOT, "coins", "jianbi"),
    REPUTATION("声望", Material.ENCHANTED_BOOK, "reputation", "shengwang"),
    STREAK("连锻", Material.BLAZE_POWDER, "streak", "lianduan"),
    LEVEL("专精", Material.EXPERIENCE_BOTTLE, "level", "zhuanjing"),
    STRENGTHEN("强化", Material.NETHERITE_SWORD, "strengthen", "qianghua", "forge-level", "duanzao"),
    // v2.26.0：比试维度 —— 数据是现成的（DuelManager 结算处 countDuel 落 StatsStore），
    // 这里只补「看得见」：按胜场排，胜场同再比谁输得少（乘一百万隔离两个量纲）
    DUEL("比试", Material.WOODEN_SWORD, "duel", "bishi");

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

    /**
     * 这一维度是「生涯累计」还是「本赛季」（v2.26.0）。
     *
     * <p>为什么要有这个方法，而不是直接去改 {@code StatsStore#resetSeasonal()}：
     * LEVEL / STRENGTHEN / DUEL 三个维度的底层字段（level、bestStrengthen、duelWins）
     * 是<b>只涨不跌的生涯战绩</b> —— 专精等级、历史最高强化、比试胜负都是玩家攒了很久的东西。
     * 赛季结算把它们清零 = 数据事故（玩家的历史最高强化和生涯胜场直接消失）。
     * 所以「不清」是刻意的，问题只在于<b>归档文本没写清口径</b>：
     * season-N.yml 里这三个榜与剑币/声望/连锻榜长得一模一样，读档案的人会把
     * 「第 N 季 比试榜」当成「第 N 季的比试成绩」。这里把口径变成可读的代码，
     * 让归档处有唯一的判定来源，不靠注释口口相传。</p>
     *
     * @return true = 生涯累计（赛季结算后不清零，归档进该季档案的是「截至该季结算时的生涯值」）
     */
    public boolean career() {
        return this == LEVEL || this == STRENGTHEN || this == DUEL;
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
            // 比试：胜场为主键（乘一百万与负场量纲隔离），同胜场比谁输得少
            case DUEL -> st.duelWins * 1_000_000.0 - st.duelLosses;
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
            case DUEL -> st.duelWins + " 胜 " + st.duelLosses + " 负";
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

    /**
     * Tab 补全用：这一维度能被 {@link #parse(String)} 认下来的全部写法
     * （枚举名 / 中文标签 / 别名，统一小写）。
     *
     * <p>放在枚举里而不是抄在命令类的补全表里：解析规则改一次，补全跟着变，
     * 不会出现「补全给出一个 {@code parse} 又不认」的假候选。</p>
     */
    public List<String> tokens() {
        List<String> out = new ArrayList<>();
        out.add(name().toLowerCase(java.util.Locale.ROOT));
        out.add(label);
        for (String a : aliases) {
            out.add(a);
        }
        return out;
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
