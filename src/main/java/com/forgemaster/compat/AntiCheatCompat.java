package com.forgemaster.compat;

import com.forgemaster.ForgingMasterPlugin;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;

/**
 * 反作弊兼容层。
 *
 * <p>Vulcan / Matrix 这类反作弊的 Velocity Check 是用「玩家实际移动速度」对比
 * 「预期速度」来判定作弊的。如果服务端给玩家加了很大的移速属性，或者让攻击者
 * 击退远远强于原版，反作弊有可能按原版公式算预期值，从而误判成 Velocity / Reach。</p>
 *
 * <p>所以检测到这类反作弊时，把移速与攻击击退两类加成收敛到安全区间。
 * 只压这两类，护甲、生命、暴击这些不影响移动判定的照旧。</p>
 */
public final class AntiCheatCompat {

    /** 需要留意的反作弊插件名（小写匹配） */
    private static final String[] ANTICHEATS = {
            "vulcan", "matrix", "grim", "spartan", "themis", "negativity", "verus"
    };

    private final ForgingMasterPlugin plugin;
    private boolean detected;
    private String detectedName = "";

    public AntiCheatCompat(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        detected = false;
        detectedName = "";
        for (String name : ANTICHEATS) {
            var found = Bukkit.getPluginManager().getPlugin(name);
            if (found != null && found.isEnabled()) {
                detected = true;
                detectedName = found.getName();
                break;
            }
        }
        if (detected && aware()) {
            plugin.getLogger().info("检测到反作弊 " + detectedName
                    + "，已启用属性兼容：移速上限 " + pct(speedCap())
                    + "，击退上限 " + String.format("%.2f", knockbackCap()));
        }
    }

    /** 是否启用了兼容（配置里可以关掉） */
    public boolean aware() {
        return plugin.settings().flag("compat.anticheat-aware", true);
    }

    public boolean detected() {
        return detected;
    }

    public String detectedName() {
        return detectedName;
    }

    /** 移速加成上限；没检测到反作弊时按配置的宽上限走 */
    public double speedCap() {
        if (detected) {
            return plugin.settings().num("compat.speed-cap", 0.05);
        }
        return plugin.settings().num("compat.speed-cap-loose", 0.35);
    }

    /** 攻击击退加成上限 */
    public double knockbackCap() {
        if (detected) {
            return plugin.settings().num("compat.knockback-cap", 0.6);
        }
        return plugin.settings().num("compat.knockback-cap-loose", 2.0);
    }

    /**
     * 攻速加成全局夹子（v2.17.3）：单条攻速词条 / 宝石最多 +0.6。
     *
     * <p>这是数值口径不是反作弊兼容，所以<b>不看 {@code aware()}</b>——
     * 没装反作弊也照样夹。星陨本体 bonus-speed 已在 v2.17.3 砍半，
     * 不夹的话满叠（星陨 + 攻速词条 + 疾风石）能顶到 5.4/s，超过原版 4.0 基准。</p>
     */
    public double attackSpeedCap() {
        return plugin.settings().num("compat.attack-speed-cap", 0.6);
    }

    /**
     * 收敛一条属性加成：移速 / 击退（反作弊兼容）与攻速（数值夹子），其余原样返回。
     */
    public double cap(Attribute attribute, double value) {
        if (attribute == null) {
            return value;
        }
        // 攻速夹子不依赖反作弊在场，放 aware() 之前
        if (attribute == Attribute.ATTACK_SPEED) {
            return Math.min(value, attackSpeedCap());
        }
        if (!aware()) {
            return value;
        }
        if (attribute == Attribute.MOVEMENT_SPEED) {
            return Math.min(value, speedCap());
        }
        if (attribute == Attribute.ATTACK_KNOCKBACK) {
            return Math.min(value, knockbackCap());
        }
        return value;
    }

    private static String pct(double v) {
        return String.format("%.0f%%", v * 100);
    }
}
