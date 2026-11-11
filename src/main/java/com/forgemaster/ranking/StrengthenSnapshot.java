package com.forgemaster.ranking;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.stats.StatsStore;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 「锻造等级」榜的数据源。
 *
 * <p><b>v2.5 起是持久化的全服历史</b>：强化成功时 {@code StrengthenService} 会把等级
 * 写进 {@code StatsStore.Stats#bestStrengthen}（只涨不跌），这里读那份记录。</p>
 *
 * <p>仍然保留在线背包扫描，作用有两个：</p>
 * <ul>
 *   <li>拿到物品名与材料名（持久化只记等级，不记是哪把剑）；</li>
 *   <li>给历史遗留数据补录——老玩家第一次上线时扫到 +N 就写进档案，
 *       不用重新强化一遍。</li>
 * </ul>
 *
 * <p>两者按 <b>取较大值</b> 合并：在线扫描可能更高（刚强化还没落库），
 * 持久化可能更高（人不在线 / 剑放在箱子里）。</p>
 *
 * <p>结果按 {@code rank.strengthen.snapshot-seconds} 缓存（默认 5 秒），
 * 避免一次打开 GUI 反复扫全服背包。</p>
 */
public final class StrengthenSnapshot {

    /** 一个玩家的强化快照：他身上等级最高的那件锻造品 */
    public record Best(UUID uuid, String name, int level, int cap, String itemName, String materialName) {
    }

    private static volatile Map<UUID, Best> byUuid = Map.of();
    /** 档案对象 → 快照，供不带 UUID 的旧接口 Metric#display(Stats) 反查 */
    private static volatile Map<StatsStore.Stats, Best> byStats = Map.of();
    private static volatile long takenAt;

    private StrengthenSnapshot() {
    }

    // ------------------------------------------------------------ 配置

    /** 维度开关（rank.strengthen.enabled，默认 true） */
    public static boolean enabled(ForgingMasterPlugin plugin) {
        return plugin == null || plugin.settings().flag("rank.strengthen.enabled", true);
    }

    /** 缓存时长：rank.strengthen.snapshot-seconds，默认 5 秒；0 = 每次现扫 */
    private static long ttlMs(ForgingMasterPlugin plugin) {
        return Math.max(0, plugin.settings().intOf("rank.strengthen.snapshot-seconds", 5)) * 1000L;
    }

    // ------------------------------------------------------------ 读取

    /** 全部强化记录（uuid → 最高等级），在线扫描与落库历史取较大值 */
    public static Map<UUID, Best> levels() {
        ensureFresh();
        return byUuid;
    }

    /** 某玩家的强化记录，没有返回 null */
    public static Best bestOf(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        ensureFresh();
        return byUuid.get(uuid);
    }

    /** 某玩家身上最高的锻造等级，没有返回 0（榜单只收 > 0） */
    public static int levelOf(UUID uuid) {
        Best b = bestOf(uuid);
        return b == null ? 0 : b.level();
    }

    /** 按档案对象反查（Metric#value 拿不到 UUID，只能用这条路径） */
    public static Best forStats(StatsStore.Stats st) {
        if (st == null) {
            return null;
        }
        ensureFresh();
        return byStats.get(st);
    }

    /** 全服在线玩家里的最高一条，没有返回 null */
    public static Best top() {
        ensureFresh();
        Best best = null;
        for (Best b : byUuid.values()) {
            if (best == null || b.level() > best.level()) {
                best = b;
            }
        }
        return best;
    }

    /** 让下一次读取强制重扫（强化成功后想让榜单立刻反映时可调） */
    public static void invalidate() {
        takenAt = 0L;
    }

    // ------------------------------------------------------------ 扫描

    private static void ensureFresh() {
        ForgingMasterPlugin plugin = plugin();
        if (plugin == null) {
            return;
        }
        long ttl = ttlMs(plugin);
        if (ttl > 0 && System.currentTimeMillis() - takenAt < ttl) {
            return;
        }
        refresh(plugin);
    }

    /**
     * 强制重扫全部在线玩家背包。
     *
     * <p>只能在主线程调用（读的是玩家背包）。一次扫描 = 在线人数 × 41 个格子，
     * 每格只做一次 PDC 读取，量级很小；结果按 TTL 缓存，不要每格 GUI 都调。</p>
     */
    public static synchronized Map<UUID, Best> refresh(ForgingMasterPlugin plugin) {
        Map<UUID, Best> out = new LinkedHashMap<>();
        if (plugin != null && plugin.strengthen() != null && enabled(plugin)) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                Best b = scan(p, plugin);
                if (b != null && b.level() > 0) {
                    out.put(p.getUniqueId(), b);
                    // 顺手补录：老玩家第一次上线就把历史最高补进档案，不用重新强化
                    try {
                        plugin.stats().recordStrengthen(p.getUniqueId(), b.level());
                    } catch (RuntimeException ex) {
                        // 档案写失败不影响榜单展示，但要留痕——静默丢档没法排查
                        if (plugin.getLogger() != null) {
                            plugin.getLogger().warning("强化纪录落库失败: " + ex.getMessage());
                        }
                    }
                }
            }
        }
        // 持久化记录兜底：补上不在线的、剑放在箱子/末影箱里的玩家
        if (plugin != null && plugin.stats() != null) {
            java.util.Set<UUID> ranked = new java.util.LinkedHashSet<>(plugin.stats().strengthenRanked());
            for (UUID uuid : ranked) {
                int stored = plugin.stats().bestStrengthenOf(uuid);
                if (stored <= 0) {
                    continue;
                }
                Best cur = out.get(uuid);
                if (cur != null && cur.level() >= stored) {
                    continue;
                }
                Player online = Bukkit.getPlayer(uuid);
                String name = online != null ? online.getName() : plugin.stats().get(uuid).name;
                if (name == null || name.isBlank()) {
                    name = "???";
                }
                // 不在线时拿不到物品名与材料，留空，GUI 会显示「—」
                out.put(uuid, new Best(uuid, name, stored, stored, "", ""));
            }
        }
        Map<StatsStore.Stats, Best> index = new IdentityHashMap<>();
        if (plugin != null && plugin.stats() != null) {
            // 只认已存在的档案，别为了排行给没档案的玩家 createIfAbsent 出一堆空记录
            for (Map.Entry<UUID, StatsStore.Stats> e : plugin.stats().snapshot()) {
                Best b = out.get(e.getKey());
                if (b != null) {
                    index.put(e.getValue(), b);
                }
            }
        }
        byUuid = Collections.unmodifiableMap(out);
        byStats = index;
        takenAt = System.currentTimeMillis();
        return byUuid;
    }

    /** 扫一个玩家的背包（PlayerInventory#getContents 已含盔甲与副手），取等级最高的那件 */
    private static Best scan(Player p, ForgingMasterPlugin plugin) {
        Best best = null;
        for (ItemStack item : p.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            int level = plugin.strengthen().levelOf(item);
            if (level <= 0 || (best != null && level <= best.level())) {
                continue;
            }
            int cap = plugin.strengthen().capOf(item);
            ForgeMaterial mat = plugin.strengthen().materialOf(item);
            best = new Best(p.getUniqueId(), p.getName(), level, Math.max(cap, level),
                    itemNameOf(item),
                    mat == null ? "" : Msg.plain(Msg.mm(mat.display())));
        }
        return best;
    }

    private static String itemNameOf(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.displayName() == null) {
            return item.getType().name();
        }
        String plain = Msg.plain(meta.displayName()).trim();
        return plain.isEmpty() ? item.getType().name() : plain;
    }

    /**
     * 插件实例。
     *
     * <p>本类没有构造注入点（调用方都是静态的 Rankings / Metric），所以从 Bukkit 插件管理器反查；
     * 插件还没注册时拿不到，此时按「空快照」处理，不抛异常。</p>
     */
    private static ForgingMasterPlugin plugin() {
        try {
            return JavaPlugin.getPlugin(ForgingMasterPlugin.class);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
