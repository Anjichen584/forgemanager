package com.forgemaster.stage;

import com.forgemaster.forge.ForgeSession;
import com.forgemaster.quench.Quench;
import com.forgemaster.util.Msg;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * 锻造现场演出：铁砧上躺一把剑，锤子砸下来冒火星，淬火时冒白汽。
 *
 * <p>全部用 Display 实体实现（不放置任何真实方块，不破坏地形）：
 * <ul>
 *   <li>{@link ItemDisplay} = 剑胚 / 成品</li>
 *   <li>{@link ItemDisplay} = 锤子（MACE 模型）</li>
 *   <li>{@link BlockDisplay} = 找不到真铁砧时的替身铁砧</li>
 * </ul>
 * 实体都是 {@code persistent=false}，关服 / 掉线不会留下垃圾。</p>
 */
public final class ForgeStage {

    public static final String TAG = "forgemaster_stage";

    private static final Vector3f NO_TRANSLATION = new Vector3f(0f, 0f, 0f);
    private static final Vector3f UNIT = new Vector3f(1f, 1f, 1f);
    private static final AxisAngle4f NO_ROTATION = new AxisAngle4f(0f, 0f, 0f, 1f);

    private final Plugin plugin;
    private final ForgeSession session;
    private final Location origin;
    private final boolean realAnvil;

    private ItemDisplay blade;
    private ItemDisplay hammer;
    private BlockDisplay anvilProp;
    private boolean closed;

    public ForgeStage(Plugin plugin, ForgeSession session, Location origin, boolean realAnvil) {
        this.plugin = plugin;
        this.session = session;
        this.origin = origin.clone();
        this.realAnvil = realAnvil;
    }

    // ------------------------------------------------------------ 生成

    public void spawn() {
        World world = origin.getWorld();
        if (world == null) {
            return;
        }

        if (!realAnvil) {
            BlockData anvilData = Material.ANVIL.createBlockData();
            anvilProp = world.spawn(origin.clone().add(0, -0.5, 0), BlockDisplay.class, entity -> {
                entity.setBlock(anvilData);
                entity.setTransformation(new Transformation(
                        new Vector3f(-0.5f, -0.5f, -0.5f), NO_ROTATION, UNIT, NO_ROTATION));
                entity.setBrightness(new Display.Brightness(15, 15));
                entity.setPersistent(false);
                entity.setShadowRadius(0.5f);
                entity.addScoreboardTag(TAG);
            });
        }

        ItemStack bladeItem = new ItemStack(session.material().item());
        ItemMeta meta = bladeItem.getItemMeta();
        if (meta != null) {
            meta.displayName(Msg.mm("<gray>" + Msg.plain(Msg.mm(session.material().display()))));
            bladeItem.setItemMeta(meta);
        }

        blade = world.spawn(facing(origin.clone().add(0, 0.06, 0)), ItemDisplay.class, entity -> {
            entity.setItemStack(bladeItem);
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setTransformation(flatBlade(0f, 0f));
            entity.setInterpolationDuration(3);
            entity.setInterpolationDelay(0);
            entity.setBrightness(new Display.Brightness(15, 15));
            entity.setPersistent(false);
            entity.setShadowRadius(0.35f);
            entity.addScoreboardTag(TAG);
        });

        hammer = world.spawn(facing(origin.clone().add(0.32, 0, 0.32)), ItemDisplay.class, entity -> {
            entity.setItemStack(new ItemStack(Material.MACE));
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setTransformation(raisedHammer());
            entity.setInterpolationDuration(2);
            entity.setInterpolationDelay(0);
            entity.setBrightness(new Display.Brightness(15, 15));
            entity.setPersistent(false);
            entity.setShadowRadius(0.3f);
            entity.addScoreboardTag(TAG);
        });

        // 出场：一声闷响 + 一点火星，告诉玩家"剑已经放上去了"
        world.playSound(origin, Sound.BLOCK_ANVIL_PLACE, 0.6f, 1.2f);
        world.spawnParticle(Particle.ELECTRIC_SPARK, origin.clone().add(0, 0.15, 0),
                10, 0.25, 0.05, 0.25, 0.02);
    }

    /** 让演出正对玩家：只调 yaw，不调 pitch，避免剑被扳成竖的 */
    private Location facing(Location at) {
        org.bukkit.entity.Player p = session.player();
        if (p == null) {
            return at;
        }
        Location loc = at.clone();
        org.bukkit.util.Vector dir = p.getEyeLocation().toVector().subtract(loc.toVector());
        if (dir.lengthSquared() < 0.01) {
            return loc;
        }
        loc.setYaw((float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ())));
        return loc;
    }

    /** 剑平躺在砧面上：绕 X 轴 -90° 放倒，再绕 Y 轴斜一点；bounce 为额外抬升 */
    private Transformation flatBlade(float bounce, float extraYawDeg) {
        return new Transformation(
                new Vector3f(0f, 0.02f + bounce, 0f),
                NO_ROTATION,
                new Vector3f(1.25f, 1.25f, 1.25f),
                new AxisAngle4f((float) Math.toRadians(-90), 1f, 0f, 0f));
    }

    private Transformation flatBladeWithYaw(float bounce, float yawDeg) {
        return new Transformation(
                new Vector3f(0f, 0.02f + bounce, 0f),
                new AxisAngle4f((float) Math.toRadians(yawDeg), 0f, 1f, 0f),
                new Vector3f(1.25f, 1.25f, 1.25f),
                new AxisAngle4f((float) Math.toRadians(-90), 1f, 0f, 0f));
    }

    /** 锤子举起：抬高 + 后仰 */
    private Transformation raisedHammer() {
        return new Transformation(
                new Vector3f(0f, 1.25f, 0f),
                new AxisAngle4f((float) Math.toRadians(-15), 1f, 0f, 0f),
                new Vector3f(1.0f, 1.0f, 1.0f),
                NO_ROTATION);
    }

    /** 锤子砸下：压低 + 前倾 */
    private Transformation struckHammer() {
        return new Transformation(
                new Vector3f(0f, 0.72f, 0f),
                new AxisAngle4f((float) Math.toRadians(-82), 1f, 0f, 0f),
                new Vector3f(1.0f, 1.0f, 1.0f),
                NO_ROTATION);
    }

    // ------------------------------------------------------------ 动作

    /** 落锤：锤子砸下 → 剑一颤 → 火星四溅，2 tick 后锤子抬起 */
    public void strike(ForgeSession.Hit hit) {
        if (closed || hammer == null || !hammer.isValid()) {
            return;
        }
        World world = origin.getWorld();
        float bounce = hit == ForgeSession.Hit.PERFECT ? 0.09f : (hit == ForgeSession.Hit.GOOD ? 0.05f : 0.02f);
        int sparks = hit == ForgeSession.Hit.PERFECT ? 22 : (hit == ForgeSession.Hit.GOOD ? 12 : 6);

        hammer.setInterpolationDuration(2);
        hammer.setTransformation(struckHammer());

        if (blade != null && blade.isValid()) {
            blade.setInterpolationDuration(2);
            blade.setTransformation(flatBladeWithYaw(bounce, hit == ForgeSession.Hit.PERFECT ? 8f : 3f));
        }

        if (world != null) {
            Location impact = origin.clone().add(0, 0.12, 0);
            world.spawnParticle(Particle.LAVA, impact, sparks / 3, 0.18, 0.02, 0.18, 0.01);
            world.spawnParticle(Particle.CRIT, impact, sparks, 0.22, 0.05, 0.22, 0.12);
            world.spawnParticle(Particle.ELECTRIC_SPARK, impact, sparks / 2, 0.2, 0.02, 0.2, 0.05);
            world.playSound(origin, Sound.BLOCK_ANVIL_USE,
                    hit == ForgeSession.Hit.MISS ? 0.5f : 0.9f,
                    hit == ForgeSession.Hit.PERFECT ? 1.7f : 1.2f);
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (closed || hammer == null || !hammer.isValid()) {
                return;
            }
            hammer.setInterpolationDuration(4);
            hammer.setTransformation(raisedHammer());
            if (blade != null && blade.isValid()) {
                blade.setInterpolationDuration(6);
                blade.setTransformation(flatBladeWithYaw(0f, 0f));
            }
        }, 3L);
    }

    /** 加热阶段的氛围：温度越高，砧面上火越旺 */
    public void heatTick(double temperature) {
        if (closed) {
            return;
        }
        World world = origin.getWorld();
        if (world == null) {
            return;
        }
        Location at = origin.clone().add(0, 0.12, 0);
        if (temperature > 75) {
            world.spawnParticle(Particle.FLAME, at, 4, 0.2, 0.02, 0.2, 0.01);
            world.spawnParticle(Particle.LAVA, at, 1, 0.15, 0.0, 0.15, 0.0);
        } else {
            world.spawnParticle(Particle.SMOKE, at, 3, 0.2, 0.05, 0.2, 0.01);
        }
    }

    /** 淬火：白汽炸开，剑胚直接换成成品 */
    public void quench(Quench quench, ItemStack result) {
        if (closed) {
            return;
        }
        World world = origin.getWorld();
        if (world != null) {
            Location at = origin.clone().add(0, 0.2, 0);
            world.spawnParticle(Particle.CLOUD, at, 40, 0.45, 0.25, 0.45, 0.06);
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, at, 16, 0.35, 0.15, 0.35, 0.02);
            world.playSound(origin, Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 0.7f);
            world.playSound(origin, Sound.BLOCK_LAVA_POP, 0.8f, 0.6f);
        }
        if (blade != null && blade.isValid() && result != null) {
            ItemStack show = result.clone();
            blade.setInterpolationDuration(4);
            blade.setTransformation(flatBladeWithYaw(0.18f, 12f));
            blade.setItemStack(show);
        }
        if (hammer != null && hammer.isValid()) {
            hammer.setInterpolationDuration(6);
            hammer.setTransformation(raisedHammer());
        }
    }

    /** 成品被拿走时的收尾闪光 */
    public void finish() {
        World world = origin.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.TOTEM_OF_UNDYING, origin.clone().add(0, 0.4, 0),
                    30, 0.4, 0.3, 0.4, 0.12);
        }
        despawn();
    }

    // ------------------------------------------------------------ 清理

    public void despawn() {
        closed = true;
        if (blade != null) {
            blade.remove();
            blade = null;
        }
        if (hammer != null) {
            hammer.remove();
            hammer = null;
        }
        if (anvilProp != null) {
            anvilProp.remove();
            anvilProp = null;
        }
    }

    public boolean isClosed() {
        return closed;
    }

    public Location origin() {
        return origin.clone();
    }

    /** 玩家跑太远就没必要继续演了 */
    public boolean shouldClose(Location playerLocation) {
        World world = origin.getWorld();
        if (world == null || playerLocation.getWorld() != world) {
            return true;
        }
        return playerLocation.distanceSquared(origin) > 48 * 48;
    }
}