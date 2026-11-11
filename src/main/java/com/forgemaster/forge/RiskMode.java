package com.forgemaster.forge;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 极限锻造（RISK 模式）的规则与开关。数值全部来自 config.yml 的 {@code risk} 段，每次读取，热重载即生效。
 *
 * <p>玩家用 {@code /zhujian forge risk} 「预设」一次，之后选的下一把剑胚以 RISK 模式开工。</p>
 */
public final class RiskMode {

    /** 预设有效期：超过这个时间还没选剑胚就作废 */
    private static final long ARM_TTL_MS = 5 * 60_000L;

    private static final Map<UUID, Long> ARMED = new ConcurrentHashMap<>();

    private RiskMode() {
    }

    // ------------------------------------------------------------ 预设开关

    public static boolean enabled(ForgingMasterPlugin plugin) {
        return plugin.getConfig().getBoolean("risk.enabled", true);
    }

    public static void arm(UUID uuid) {
        ARMED.put(uuid, System.currentTimeMillis());
    }

    public static void disarm(UUID uuid) {
        ARMED.remove(uuid);
    }

    public static boolean isArmed(UUID uuid) {
        Long at = ARMED.get(uuid);
        if (at == null) {
            return false;
        }
        if (System.currentTimeMillis() - at > ARM_TTL_MS) {
            // 过期条目顺带清掉：ARMED 不能是全插件唯一「只增不减」的静态容器
            ARMED.remove(uuid, at);
            return false;
        }
        return true;
    }

    /** 开工时调用：有有效预设则消耗它并返回 true */
    public static boolean consume(UUID uuid) {
        boolean ok = isArmed(uuid);
        ARMED.remove(uuid);
        return ok;
    }

    // ------------------------------------------------------------ 参数

    /** 锻打指针走一格的间隔（tick），越小越快 */
    public static int hammerInterval(ForgingMasterPlugin plugin) {
        return Math.max(1, plugin.getConfig().getInt("risk.hammer-interval-ticks", 2));
    }

    /** 命中区缩窄的格数（作用于半径） */
    public static int zoneShrink(ForgingMasterPlugin plugin) {
        return Math.max(0, plugin.getConfig().getInt("risk.zone-shrink", 1));
    }

    /** 成功分数的乘数，例如 35 → 1.35 */
    public static double scoreMultiplier(ForgingMasterPlugin plugin) {
        double pct = plugin.getConfig().getDouble("risk.score-bonus-percent", 35.0);
        return 1.0 + Math.max(0.0, pct) / 100.0;
    }

    /** 每个阶段结束时爆剑的概率（0 ~ 0.95） */
    public static double explodeChance(ForgingMasterPlugin plugin) {
        double c = plugin.getConfig().getDouble("risk.explode-chance", 0.06);
        return Math.max(0.0, Math.min(0.95, c));
    }

    // ------------------------------------------------------------ 产物标记

    /** 给极限锻造的成品打上 RISK_MADE 标记，并在 Lore 末尾加一行 */
    public static void markRisk(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(Keys.RISK_MADE, PersistentDataType.BYTE, (byte) 1);
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Msg.mm("<#ff5252><bold>◆ 极限锻造</bold></#ff5252> <dark_gray>（险中求胜）</dark_gray>")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
    }

    /** 是否为极限锻造产物 */
    public static boolean isRiskMade(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte mark = meta.getPersistentDataContainer().get(Keys.RISK_MADE, PersistentDataType.BYTE);
        return mark != null && mark == 1;
    }
}
