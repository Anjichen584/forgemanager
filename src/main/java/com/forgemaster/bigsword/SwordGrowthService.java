package com.forgemaster.bigsword;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.util.Keys;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

/**
 * 剑体成长曲线（v2.22 起只管曲线，不再挂任何实体 / 修饰符）。
 *
 * <p>历史上这个服务做过两件事——手上磁吸悬浮剑、穿护甲体型放大——都已按服主要求
 * 移除（v2.22.1）。现在它只剩一个职责：持有 {@link SwordScale} 成长曲线
 * （{@code strengthen.big-sword.*} 配置），供铁砧上的剑身成长读取
 * （{@code AnvilForgeManager.swordScaleFor}），保证「砧上逐锤变大」有唯一的一条曲线来源。</p>
 *
 * <p>保留的清理逻辑：</p>
 * <ul>
 *   <li>启动时按 {@code BIG_SWORD_DISPLAY} 标记清扫旧版悬浮剑残留实体
 *       （v2.20~2.22.0 挂过的 ItemDisplay 是非持久的，正常关服自清；硬崩残留在这里补刀）；</li>
 *   <li>停服时摘掉 {@code PLAYER_SCALE} 体型修饰符——v2.21 护甲放大的遗产，
 *       不摘的话老档案玩家会永久保持放大体型。</li>
 * </ul>
 */
public final class SwordGrowthService {

    private final ForgingMasterPlugin plugin;
    private final SwordScale scale;

    public SwordGrowthService(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.scale = SwordScale.from(plugin);
    }

    /** 成长曲线：砧上剑身大小 = sword-scale × 这条曲线的倍数 */
    public SwordScale scale() {
        return scale;
    }

    /** 剑体成长总开关（关闭时砧上回退 v2.17 的封顶曲线） */
    public boolean enabled() {
        return plugin.getConfig().getBoolean("strengthen.big-sword.enabled", true);
    }

    // ------------------------------------------------------------ 生命周期

    public void start() {
        cleanupOrphans();
    }

    public void stop() {
        cleanupOrphans();
        // v2.21 护甲放大的遗产清理：修饰符随玩家档案走，不摘的话玩家会永久变大
        for (Player p : Bukkit.getOnlinePlayers()) {
            var inst = p.getAttribute(org.bukkit.attribute.Attribute.SCALE);
            if (inst == null) {
                continue;
            }
            for (var mod : new java.util.ArrayList<>(inst.getModifiers())) {
                if (mod.getKey().equals(Keys.PLAYER_SCALE)) {
                    inst.removeModifier(mod);
                }
            }
        }
    }

    /**
     * 清扫旧版悬浮剑 / 崩服残留的展示实体。
     *
     * <p>这些实体是非持久的，正常关服会被清掉；但 kill -9 / 断电这类硬崩会留下残留。
     * 启动 / 停服各扫一遍，按 PDC 标记全量清，只用自己那个键，不碰别人的实体。</p>
     */
    private void cleanupOrphans() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntitiesByClass(ItemDisplay.class)) {
                if (e.getPersistentDataContainer().has(Keys.BIG_SWORD_DISPLAY,
                        PersistentDataType.STRING)) {
                    e.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("剑体放大：清扫了 " + removed + " 个旧版悬浮剑残留实体");
        }
    }
}
