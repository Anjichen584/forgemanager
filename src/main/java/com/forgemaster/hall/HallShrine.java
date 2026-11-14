package com.forgemaster.hall;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.quality.Quality;
import com.forgemaster.ranking.StrengthenSnapshot;
import com.forgemaster.stats.StatsStore;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 名人殿堂（拓展第一档）：把全服前三名用 {@link ItemDisplay} 平躺摆出来，
 * 头顶 {@link TextDisplay} 浮一块「玩家名 + 剑名 + 分数」名牌。
 *
 * <p>原点由 {@code /zhujian hall set}（需 forgemaster.admin）写进 {@code hall.yml}
 * 的 {@code shrine} 段（world / x / y / z / yaw）——与 {@link HallOfFame} 那套盔甲架榜的
 * 根键互不干扰。<b>注意</b>：{@code HallOfFame#setLocation}（{@code /zhujian halloffame set}）
 * 是整份重写 hall.yml，会顺带抹掉 {@code shrine} 段，重设旧榜位置后需要再跑一次
 * {@code /zhujian hall set}。</p>
 *
 * <p>展示位三个：原点、原点左 1.5 格、原点右 1.5 格（左右按设点玩家的朝向算）。
 * 数据源：{@code StatsStore.top(3)}（与 HallOfFame 同源，按历史最高分排）；
 * 没有任何锻造记录时回退为强化榜前三（{@link StrengthenSnapshot}，在线扫描 + 落库历史）。</p>
 *
 * <p><b>姿态</b>：复用 {@code AnvilForgeManager} 砧位展示的思路——
 * {@code ItemDisplayTransform.NONE} + 绕 Y 轴 90°、绕 X 轴 −90° 扳倒成平躺、缩放 0.95，
 * 在本类里独立实现（{@link #flatPose}），<b>不调用</b> AnvilForgeManager 的私有方法。</p>
 *
 * <p><b>安全约束</b>：殿堂实体统一打 {@link Keys#HALL_DISPLAY} 标记
 * （值 = {@code sword:排名} / {@code label:排名}），与砧位的 {@code ANVIL_SESSION}
 * 完全独立，互相的清理逻辑碰不到对方。</p>
 *
 * <p><b>生命周期</b>：实体一律 {@code setPersistent(false)}（不进存档）；一个
 * {@code hall.refresh-seconds}（默认 30）秒的定时任务负责重刷——只在殿堂区块已加载时
 * 清旧补新（照 AnvilForgeManager.onChunkLoad 的思路简化，不注册区块事件监听）；
 * 关服 / 重载 / {@code /zhujian hall del}（清空殿堂数据）时统一撤实体。</p>
 */
public final class HallShrine {

    private final ForgingMasterPlugin plugin;
    private BukkitTask task;
    /** 本轮生成的展示实体（非持久化，卸载即失效；清理时再按 PDC 标记兜底扫一遍） */
    private final List<UUID> spawned = new ArrayList<>();
    /** 缓存的原点；null = 未设置（set / del / 首次读取时更新） */
    private Location origin;

    public HallShrine(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    /** 展示位的一行数据（icon = 平躺展示用的代表剑外观） */
    private record Row(String player, String sword, String value, org.bukkit.Material icon, int rank) {
    }

    // ------------------------------------------------------------ 生命周期

    /** 启动 30s 重刷任务。未启用 / 未设原点时不启动（先停旧的避免重复）。 */
    public void start() {
        stopTask();
        if (!enabled()) {
            return;
        }
        long interval = Math.max(5, plugin.settings().intOf("hall.refresh-seconds", 30)) * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, com.forgemaster.util.TaskGuard.guard("HallShrine.tick", this::tick), 40L, interval);
    }

    /** 关服 / 重载：停任务并撤掉全部展示实体 */
    public void stop() {
        stopTask();
        clearDisplays();
    }

    private void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public boolean enabled() {
        return plugin.settings().flag("hall.enabled", true);
    }

    // ------------------------------------------------------------ 原点（hall.yml 的 shrine 段）

    /** 殿堂原点；未设置或世界不存在时返回 null */
    public Location origin() {
        if (origin == null) {
            origin = readOrigin();
        }
        return origin == null ? null : origin.clone();
    }

    /**
     * 丢掉字段缓存、按盘面重读原点（v2.26.0 审计轮补，restore 的 reloadAllSilently 用）。
     *
     * <p><b>为什么需要：</b>{@link #origin()} 是惰性加载 + 字段缓存 —— 第一次读之后
     * {@code origin} 就不再变。<b>回档把 hall.yml 换成旧的之后，内存里那份不动</b>，
     * 于是「殿堂原点」在回档后到重启前一直指向旧坐标：粒子/标牌摆在新位置，
     * 而 {@code /zhujian hall set} 写回去的又是缓存值，等于回档对这个字段完全没生效。</p>
     *
     * <p>只刷新坐标，<b>不碰展示任务</b>：清缓存后调用 {@link #origin()} 即可重新读盘。
     * 是否要重建粒子/标牌由调用方决定 —— 回档流程本身会在最后统一重摆，
     * 这里顺手 stop() 反而会让那次重摆落空（顺序敏感，别加）。</p>
     */
    public void reloadOrigin() {
        origin = null;
        // 立刻读一次：读不出来（未设置 / 文件没了 / 世界未加载）就让缓存保持 null，
        // 与「从未设置过」同义，origin() 下次调用会再试。异常不能冒出去 ——
        // 调用方是回档链里的 swallow，一份坏存档不该让整条回档报错。
        try {
            origin = readOrigin();
        } catch (RuntimeException ex) {
            origin = null;
            plugin.getLogger().warning("回档后重读殿堂原点失败，按未设置处理：" + ex);
        }
    }

    private Location readOrigin() {
        File file = hallFile();
        if (!file.exists()) {
            return null;
        }
        ConfigurationSection sec = com.forgemaster.util.YamlIo.loadOrQuarantine(file, "HallShrine", plugin.getLogger()).getConfigurationSection("shrine");
        if (sec == null) {
            return null;
        }
        World world = Bukkit.getWorld(sec.getString("world", ""));
        if (world == null) {
            return null;
        }
        return new Location(world,
                sec.getDouble("x"), sec.getDouble("y"), sec.getDouble("z"),
                (float) sec.getDouble("yaw"), 0f);
    }

    /**
     * 设为殿堂原点（{@code /zhujian hall set}）。
     *
     * <p>保留 hall.yml 里已有的其它内容（旧榜的根键），只追加 / 覆盖 {@code shrine} 段，
     * 随后立即重摆一次。</p>
     */
    public void setOrigin(Location loc) {
        File file = hallFile();
        YamlConfiguration yml = file.exists()
                ? com.forgemaster.util.YamlIo.loadOrQuarantine(file, "HallShrine", plugin.getLogger())
                : new YamlConfiguration();
        yml.set("shrine.world", loc.getWorld().getName());
        yml.set("shrine.x", loc.getX());
        yml.set("shrine.y", loc.getY());
        yml.set("shrine.z", loc.getZ());
        yml.set("shrine.yaw", loc.getYaw());
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("建不出数据目录，殿堂原点没存下来。");
                return;
            }
            com.forgemaster.util.YamlIo.save(file, yml);
        } catch (IOException ex) {
            plugin.getLogger().warning("保存殿堂原点失败: " + ex.getMessage());
            return;
        }
        origin = loc.clone();
        start();
        refresh();
    }

    /** 删除殿堂原点（{@code /zhujian hall del}）：清数据、撤实体、停任务 */
    public void clearOrigin() {
        File file = hallFile();
        if (file.exists()) {
            YamlConfiguration yml = com.forgemaster.util.YamlIo.loadOrQuarantine(file, "HallShrine", plugin.getLogger());
            yml.set("shrine", null);
            try {
                com.forgemaster.util.YamlIo.save(file, yml);
            } catch (IOException ex) {
                plugin.getLogger().warning("删除殿堂原点失败: " + ex.getMessage());
            }
        }
        origin = null;
        stop();
    }

    private File hallFile() {
        return new File(plugin.getDataFolder(), "hall.yml");
    }

    // ------------------------------------------------------------ 展示

    /**
     * 30s 定时重刷：只在殿堂区块已加载时清旧补新。
     * 区块没加载就什么都不做——实体本来就是非持久化的，下次加载后 30s 内会被这里补上。
     */
    private void tick() {
        Location base = origin();
        if (base == null) {
            return;
        }
        World world = base.getWorld();
        if (world == null || !world.isChunkLoaded(base.getBlockX() >> 4, base.getBlockZ() >> 4)) {
            return;
        }
        refresh();
    }

    /** 撤旧的、按当前排行重摆一次（命令 show 也走这里） */
    public void refresh() {
        clearDisplays();
        Location base = origin();
        if (base == null || !enabled()) {
            return;
        }
        World world = base.getWorld();
        if (world == null || !world.isChunkLoaded(base.getBlockX() >> 4, base.getBlockZ() >> 4)) {
            return;
        }
        List<Row> rows = topRows();
        if (rows.isEmpty()) {
            return;
        }
        // 三个展示位：原点、左 1.5、右 1.5（左右按设点时的朝向算）
        float yaw = base.getYaw();
        Vector facing = new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
        Vector left = new Vector(facing.getZ(), 0, -facing.getX()).multiply(1.5);
        Location[] slots = {
                base.clone().add(0, 0.6, 0),                    // 第 1 名：原点
                base.clone().add(left).add(0, 0.6, 0),          // 第 2 名：左 1.5
                base.clone().subtract(left).add(0, 0.6, 0)      // 第 3 名：右 1.5
        };
        for (Row row : rows) {
            if (row.rank() < 1 || row.rank() > slots.length) {
                continue;
            }
            Location at = slots[row.rank() - 1];
            spawnSword(at, row);
            spawnLabel(at.clone().add(0, 1.1, 0), row);
        }
    }

    /** 前三行数据：优先分数榜（StatsStore.top，与 HallOfFame 同源），没有就回退强化榜前三 */
    private List<Row> rowsForged() {
        List<Row> out = new ArrayList<>();
        List<Map.Entry<UUID, StatsStore.Stats>> top = plugin.stats().top(3);
        for (Map.Entry<UUID, StatsStore.Stats> e : top) {
            StatsStore.Stats st = e.getValue();
            Quality q = Quality.byName(st.bestQuality);
            String sword = q == null ? "锻造剑" : q.display() + "剑";
            out.add(new Row(
                    st.name == null || st.name.isEmpty() ? "无名" : st.name,
                    sword,
                    Msg.fmt(st.bestScore, 1) + " 分",
                    q == null ? org.bukkit.Material.IRON_SWORD : q.icon(),
                    out.size() + 1));
        }
        return out;
    }

    private List<Row> topRows() {
        List<Row> forged = rowsForged();
        if (!forged.isEmpty()) {
            return forged;
        }
        // 回退：强化榜前三（StrengthenSnapshot 的现成快照，在线扫描 + 落库历史，不新增扫描）
        List<Map.Entry<UUID, StrengthenSnapshot.Best>> ranked =
                new ArrayList<>(StrengthenSnapshot.levels().entrySet());
        ranked.sort(Comparator.comparingInt((Map.Entry<UUID, StrengthenSnapshot.Best> e) -> e.getValue().level())
                .reversed());
        List<Row> out = new ArrayList<>();
        for (Map.Entry<UUID, StrengthenSnapshot.Best> e : ranked) {
            if (out.size() >= 3) {
                break;
            }
            StrengthenSnapshot.Best b = e.getValue();
            String item = b.itemName() == null || b.itemName().isEmpty() ? "锻造剑" : b.itemName();
            out.add(new Row(
                    b.name() == null || b.name().isEmpty() ? "???" : b.name(),
                    item,
                    "+" + b.level() + " 级",
                    org.bukkit.Material.IRON_SWORD,
                    out.size() + 1));
        }
        return out;
    }

    /**
     * 平躺展示的剑：复用 AnvilForgeManager 砧位展示的思路（NONE 姿态 + 绕 X 轴 −90° 扳倒），
     * 姿态计算在本类独立实现，不调用其私有方法。
     */
    private void spawnSword(Location at, Row row) {
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        ItemStack stack = new ItemStack(row.icon());
        ItemDisplay sword = world.spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(stack);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            d.setGravity(false);
            d.setInvulnerable(true);
            d.setPersistent(false);
            d.setShadowRadius(0f);
            d.setShadowStrength(0f);
            d.setViewRange(0.8f);
            d.setTransformation(flatPose());
            d.getPersistentDataContainer().set(Keys.HALL_DISPLAY,
                    PersistentDataType.STRING, "sword:" + row.rank());
        });
        if (sword != null) {
            spawned.add(sword.getUniqueId());
        }
    }

    /** 名牌：玩家名 + 剑名 + 分数（浮在剑上方 1.1 格，永远面向玩家） */
    private void spawnLabel(Location at, Row row) {
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        String line = "<#ffd166>#" + row.rank() + "</#ffd166> <white>" + row.player()
                + "</white><newline><gray>" + row.sword()
                + " <dark_gray>|</dark_gray> <white>" + row.value() + "</white>";
        TextDisplay label = world.spawn(at, TextDisplay.class, d -> {
            d.text(Msg.mm(line));
            d.setBillboard(Display.Billboard.CENTER);
            d.setGravity(false);
            d.setInvulnerable(true);
            d.setPersistent(false);
            d.setSeeThrough(false);
            d.setShadowed(true);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setViewRange(1.0f);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(0x66000000));
            d.getPersistentDataContainer().set(Keys.HALL_DISPLAY,
                    PersistentDataType.STRING, "label:" + row.rank());
        });
        if (label != null) {
            spawned.add(label.getUniqueId());
        }
    }

    /**
     * 平躺姿态：绕 Y 轴 90°（与观察方向垂直躺着）+ 绕 X 轴 −90°（把立着的模型扳倒成平放），
     * 缩放 0.95 —— 与 AnvilForgeManager 的 NONE 姿态默认值一致。
     */
    private static Transformation flatPose() {
        float scale = 0.95f;
        Quaternionf left = new Quaternionf()
                .rotateY((float) Math.toRadians(90))
                .rotateX((float) Math.toRadians(-90));
        return new Transformation(
                new Vector3f(0f, 0f, 0f),
                left,
                new Vector3f(scale, scale, scale),
                new Quaternionf());
    }

    /** 撤掉殿堂实体：先收登记的 UUID，再按 PDC 标记在原世界兜底扫一遍（防跨刷新残留） */
    public int clearDisplays() {
        int removed = 0;
        for (UUID id : spawned) {
            Entity e = Bukkit.getEntity(id);
            if (e != null && e.isValid()
                    && e.getPersistentDataContainer().has(Keys.HALL_DISPLAY, PersistentDataType.STRING)) {
                e.remove();
                removed++;
            }
        }
        spawned.clear();
        Location base = origin();
        if (base != null) {
            World world = base.getWorld();
            if (world != null) {
                for (Class<? extends Display> type : List.of(ItemDisplay.class, TextDisplay.class)) {
                    for (Entity e : world.getEntitiesByClass(type)) {
                        if (e.getPersistentDataContainer().has(Keys.HALL_DISPLAY, PersistentDataType.STRING)) {
                            e.remove();
                            removed++;
                        }
                    }
                }
            }
        }
        return removed;
    }
}
