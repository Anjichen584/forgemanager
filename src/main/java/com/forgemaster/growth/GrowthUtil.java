package com.forgemaster.growth;

import com.forgemaster.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Locale;

/**
 * 成长链路的公共小工具：参数剥离、图标解析、无斜体文本、标题。
 */
public final class GrowthUtil {

    private GrowthUtil() {
    }

    /** 若 args[0] 命中任一别名（如 "achv"/"mentor"/"rank"），剥掉它 */
    public static String[] strip(String[] args, String... names) {
        if (args == null || args.length == 0 || names == null || names.length == 0) {
            return args == null ? new String[0] : args;
        }
        String first = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT);
        for (String name : names) {
            if (name != null && first.equals(name.toLowerCase(Locale.ROOT))) {
                String[] rest = new String[args.length - 1];
                System.arraycopy(args, 1, rest, 0, rest.length);
                return rest;
            }
        }
        return args;
    }

    /** 配置里的字符串转 Material，转不动就用默认 */
    public static Material material(String name, Material def) {
        if (name == null || name.isBlank()) {
            return def;
        }
        Material m = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
        return m == null ? def : m;
    }

    /** 物品名（关闭斜体） */
    public static Component itemName(String raw) {
        return Msg.mm(raw == null ? "" : raw).decoration(TextDecoration.ITALIC, false);
    }

    /** 物品 Lore 行（关闭斜体） */
    public static Component loreLine(String raw) {
        return Msg.mm(raw == null ? "" : raw).decoration(TextDecoration.ITALIC, false);
    }

    /** 弹一次标题（玩家可能是 null） */
    public static void title(Player player, String head, String sub) {
        if (player == null) {
            return;
        }
        player.showTitle(Title.title(Msg.mm(head), Msg.mm(sub),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1400), Duration.ofMillis(400))));
    }

    /** 名字兜底：player 离线 / 名字空时返回短 UUID */
    public static String displayName(String name, java.util.UUID uuid) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        if (uuid == null) {
            return "?";
        }
        String raw = uuid.toString();
        return raw.length() >= 8 ? raw.substring(0, 8) : raw;
    }
}
