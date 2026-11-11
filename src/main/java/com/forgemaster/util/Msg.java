package com.forgemaster.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * 文本 / 提示工具。全部走 MiniMessage，配置里写 &lt;red&gt; 这种标签。
 */
public final class Msg {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    public static final String PREFIX =
            "<gradient:#ffd166:#ff7a18><bold>铸剑</bold></gradient> <dark_gray>» </dark_gray>";

    private Msg() {
    }

    public static Component mm(String raw) {
        return raw == null ? Component.empty() : MM.deserialize(raw);
    }

    /**
     * 把 MiniMessage 转成 § 传统颜色码。
     *
     * <p>Bukkit 的 BossBar / 记分板标题只认 legacy，直接塞 MiniMessage 会把
     * {@code <gray>}、{@code <#D5B2DA>} 原样显示出来。</p>
     */
    public static String legacy(String mini) {
        if (mini == null) {
            return "";
        }
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection().serialize(MM.deserialize(mini));
    }

    public static void send(CommandSender to, String raw) {
        if (to == null) {
            return;
        }
        to.sendMessage(mm(raw));
    }

    public static void prefix(CommandSender to, String raw) {
        if (to == null) {
            return;
        }
        to.sendMessage(mm(PREFIX + raw));
    }

    public static void action(Player p, String raw) {
        if (p == null) {
            return;
        }
        p.sendActionBar(mm(raw));
    }

    public static String plain(Component c) {
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
    }

    /**
     * 生成一条进度条，用于温度 / 评分可视化。
     *
     * @param ratio      0.0 - 1.0
     * @param len        格子数量
     * @param fillColor  MiniMessage 颜色名（如 "red"、"#ff8800"）
     * @param emptyColor 未填充部分颜色名
     */
    public static String bar(double ratio, int len, String fillColor, String emptyColor) {
        double r = Math.max(0.0, Math.min(1.0, ratio));
        int solid = (int) Math.round(r * len);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(i < solid ? "<" + fillColor + ">|" : "<" + emptyColor + ">|");
        }
        return sb.toString();
    }

    /**
     * 方块进度条：用 ▰ / ▱ 画，比竖线好看得多，专门给物品 Lore 用。
     *
     * <p>没直接改 {@link #bar} —— 那支竖线进度条还在大赛、面板等一堆地方用着，
     * 换字符会连带改掉那些界面，这里只给耐久那一行单独开一个。</p>
     */
    public static String barBlocks(double ratio, int len, String fillColor, String emptyColor) {
        double r = Math.max(0.0, Math.min(1.0, ratio));
        int solid = (int) Math.round(r * len);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(i < solid ? "<" + fillColor + ">▰" : "<" + emptyColor + ">▱");
        }
        return sb.toString();
    }

    public static String fmt(double value, int decimals) {
        // 锁死 Locale.ROOT：默认 locale 跟着服务器 JVM 走，德语/法语区的小数点是逗号，
        // 「63.9%」会变成「63,9%」——而玩家看到的百分比、倍率表格到处都是这个函数
        // 渲染出来的，逗号既难读又和千分位混淆（v2.20 修）。
        return String.format(java.util.Locale.ROOT, "%." + decimals + "f", value);
    }
}