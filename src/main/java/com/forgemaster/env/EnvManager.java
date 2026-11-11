package com.forgemaster.env;

import com.forgemaster.ForgingMasterPlugin;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * 环境修正的唯一汇总点。
 *
 * <p>读取锻造所在世界的天气与世界时间，结合 config.yml 的 {@code environment} 段，
 * 产出一份 {@link EnvContext}。ForgeManager 只读这份结果，不在各阶段散落判断。</p>
 *
 * <p>天气判定：{@code isThundering()} → storm；{@code hasStorm()} → rain；否则 clear。</p>
 * <p>时辰判定（世界 tick 0~23999）：23000~1000 黎明(dawn)；1000~12000 白天(day)；
 * 12000~13000 黄昏(dusk)；13000~23000 夜晚(night)。</p>
 */
public final class EnvManager {

    private final ForgingMasterPlugin plugin;

    public EnvManager(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    /** 开局用：按玩家所在世界解析一次环境 */
    public EnvContext resolve(Player player) {
        if (player == null) {
            return resolve((World) null);
        }
        return resolve(player.getWorld());
    }

    /** 按世界解析环境；world 为 null 时返回中性快照 */
    public EnvContext resolve(World world) {
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("environment");
        if (root == null || !root.getBoolean("enabled", true) || world == null) {
            return EnvContext.NEUTRAL;
        }

        String weatherKey = weatherKeyOf(world);
        String timeKey = timeKeyOf(world.getTime());

        ConfigurationSection w = root.getConfigurationSection("weather." + weatherKey);
        ConfigurationSection t = root.getConfigurationSection("time." + timeKey);

        double heat = num(w, "heat") + num(t, "heat");
        double quench = num(w, "quench") + num(t, "quench");
        double fold = num(w, "fold") + num(t, "fold");
        double grind = num(w, "grind") + num(t, "grind");

        String weatherLabel = label(w, weatherKey);
        String timeLabel = label(t, timeKey);

        return new EnvContext(weatherKey, timeKey, heat, quench, fold, grind, weatherLabel, timeLabel);
    }

    /** 天气档：storm / rain / clear */
    public static String weatherKeyOf(World world) {
        if (world.isThundering()) {
            return "storm";
        }
        if (world.hasStorm()) {
            return "rain";
        }
        return "clear";
    }

    /** 时辰档：dawn / day / dusk / night，tick 取模到 0~23999 */
    public static String timeKeyOf(long worldTime) {
        long t = ((worldTime % 24000L) + 24000L) % 24000L;
        if (t >= 23000L || t < 1000L) {
            return "dawn";
        }
        if (t < 12000L) {
            return "day";
        }
        if (t < 13000L) {
            return "dusk";
        }
        return "night";
    }

    private static double num(ConfigurationSection sec, String key) {
        return sec == null ? 0.0 : sec.getDouble(key, 0.0);
    }

    private static String label(ConfigurationSection sec, String fallback) {
        return sec == null ? fallback : sec.getString("label", fallback);
    }
}
