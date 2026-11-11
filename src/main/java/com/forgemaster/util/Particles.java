package com.forgemaster.util;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;

/**
 * 粒子发放的安全包装。
 *
 * <p>1.20.5 之后 {@link Particle} 分成了两类：不带数据的（{@code getDataType()}
 * 是 {@code Void.class}）和必须带数据的（{@code ENCHANT} / {@code DRAGON_BREATH}
 * 要 {@code Float}，{@code DUST} 要 {@link Particle.DustOptions}，
 * {@code BLOCK} 要 {@link org.bukkit.block.data.BlockData}…）。</p>
 *
 * <p>调用不带数据的那个重载去发带数据的粒子，Bukkit 会直接抛
 * {@code IllegalArgumentException: missing required data class java.lang.Float}，
 * 而且是<b>每发一次抛一次</b>——战斗里每帧都在刷，控制台基本就废了。</p>
 *
 * <p>这里按数据类型分流，拿不到合适数据的粒子直接跳过：宁可少一簇特效，
 * 也不要让异常把事件处理整个打断（打断之后同一个监听器后面的逻辑都不执行）。</p>
 */
public final class Particles {

    private Particles() {
    }

    /**
     * 发一簇粒子，按数据类型自动选择正确的重载。
     *
     * @param ox    散布半径 X
     * @param oy    散布半径 Y
     * @param oz    散布半径 Z
     * @param extra 速度 / 额外参数
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void spawn(Particle particle, Location loc, int count,
                             double ox, double oy, double oz, double extra) {
        if (particle == null || loc == null || count <= 0) {
            return;
        }
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        Class<?> dataType = particle.getDataType();
        try {
            if (dataType == null || dataType == Void.class) {
                world.spawnParticle(particle, loc, count, ox, oy, oz, extra);
            } else if (dataType == Float.class) {
                // ENCHANT / DRAGON_BREATH / SCULK_CHARGE：数据就是那个 float
                world.spawnParticle(particle, loc, count, ox, oy, oz, extra, 0.0f);
            } else {
                // DUST / BLOCK / ITEM 这类要构造专门的数据对象，
                // 这里没有上下文，直接不发，也不抛异常
                return;
            }
        } catch (IllegalArgumentException | UnsupportedOperationException ignored) {
            // 版本差异导致的数据类型对不上时静默跳过，特效缺失不影响玩法
        }
    }
}
