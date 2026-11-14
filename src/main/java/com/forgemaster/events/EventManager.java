package com.forgemaster.events;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.material.MaterialGrade;
import com.forgemaster.material.MaterialGrades;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 全服世界事件（D4）：陨星降临、矿脉涌现等。
 *
 * <p>事件期间每隔一段时间，在每位在线玩家附近空投稀有材料，并全服公告。
 * 定时随机开启（events.interval-minutes），或管理员手动开启。
 * 参数全部从 config.yml 的 events 段现读，热重载即生效。
 * 关服时取消调度，并清掉还没被捡走的空投物。</p>
 */
public final class EventManager {

    private final ForgingMasterPlugin plugin;
    private final Random random = new Random();
    private final Set<UUID> spawned = new HashSet<>();
    /** 落点记录：Item 是持久的，区块卸载后 Bukkit.getEntity 看不到，清理时按位置拉回区块 */
    private final Map<UUID, Location> spawnedAt = new HashMap<>();
    private BukkitTask task;
    private String activeId;
    private int elapsed;
    private int idle;
    /**
     * 事件轮换表（拓展第二档骨架，{@code events.schedule}）：空 = 旧行为（types 里均匀随机）。
     * 启动时解析一次；没有 schedule 段时列表为空，调度路径与旧版完全一致。
     */
    private List<ScheduledEvent> schedule = List.of();

    public EventManager(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    /** 启动 1 秒一次的调度（先取消旧的，避免重复） */
    public void start() {
        if (task != null) {
            task.cancel();
        }
        parseSchedule();
        task = Bukkit.getScheduler().runTaskTimer(plugin, com.forgemaster.util.TaskGuard.guard("EventManager.tick", this::tick), 20L, 20L);
    }

    /** 关服：取消调度，清掉残留空投物 */
    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        activeId = null;
        removeSpawned();
    }

    // ------------------------------------------------------------ 查询

    public boolean enabled() {
        return plugin.getConfig().getBoolean("events.enabled", true);
    }

    public boolean active() {
        return activeId != null;
    }

    public List<String> ids() {
        List<String> out = new ArrayList<>();
        ConfigurationSection types = plugin.getConfig().getConfigurationSection("events.types");
        if (types != null) {
            out.addAll(types.getKeys(false));
        }
        return out;
    }

    public String statusLine() {
        if (activeId != null) {
            return "<#b06bff>世界事件进行中：<white>" + displayOf(activeId)
                    + "</white> <dark_gray>| <gray>剩余 <white>" + Math.max(0, durationSeconds() - elapsed) + "s";
        }
        if (!enabled()) {
            return "<gray>世界事件未启用（events.enabled = false）。";
        }
        return "<gray>当前没有世界事件，约 <white>" + Math.max(0, intervalMinutes() * 60 - idle) / 60
                + "</white> 分钟后可能随机开启。";
    }

    // ------------------------------------------------------------ 手动控制

    /** 管理员手动开启，返回给发起者的提示 */
    public String startManual(String rawId) {
        if (!enabled()) {
            return "<red>世界事件没开（events.enabled = false）。</red>";
        }
        if (activeId != null) {
            return "<red>已经有一场事件在进行：" + displayOf(activeId) + "</red>";
        }
        String id = rawId == null ? "" : rawId.toLowerCase(Locale.ROOT);
        if (typeSection(id) == null) {
            return "<red>没有这个事件：" + rawId + "（可用：" + String.join(", ", ids()) + "）</red>";
        }
        begin(id);
        return "<green>已手动开启事件：" + displayOf(id) + "</green>";
    }

    /** 管理员手动结束 */
    public String stopManual() {
        if (activeId == null) {
            return "<gray>当前没有进行中的事件。</gray>";
        }
        endEvent(true);
        return "<green>已结束当前事件。</green>";
    }

    // ------------------------------------------------------------ 调度

    private void tick() {
        if (!enabled()) {
            if (activeId != null) {
                endEvent(false);
            }
            return;
        }
        if (activeId != null) {
            elapsed++;
            if (elapsed % spawnIntervalSeconds() == 0) {
                spawnRound();
            }
            if (elapsed >= durationSeconds()) {
                endEvent(true);
            }
            return;
        }
        idle++;
        if (idle >= intervalMinutes() * 60) {
            idle = 0;
            String pick = pickEvent();
            if (pick != null) {
                begin(pick);
            }
        }
    }

    // ------------------------------------------------------------ 事件轮换表（拓展第二档骨架）

    /** 一条轮换表项：事件 id（已归一化）+ 权重 */
    private record ScheduledEvent(String id, int weight) {
    }

    /**
     * 解析 {@code events.schedule}：
     * {@code [{type: starfall, weight: 6}, {type: dragon-tide, weight: 2}]}。
     *
     * <p>没有这个段（或解析出空表）时保持旧行为：在 {@code events.types} 里均匀随机。
     * 配置写法变化要重启（或重新加载插件）才生效。</p>
     */
    private void parseSchedule() {
        List<ScheduledEvent> out = new ArrayList<>();
        try {
            for (Map<?, ?> e : plugin.getConfig().getMapList("events.schedule")) {
                Object type = e.get("type");
                if (type == null) {
                    continue;
                }
                int weight = 1;
                Object w = e.get("weight");
                if (w instanceof Number n) {
                    weight = Math.max(1, n.intValue());
                } else if (w != null) {
                    try {
                        weight = Math.max(1, Integer.parseInt(String.valueOf(w)));
                    } catch (NumberFormatException ignored) {
                    }
                }
                out.add(new ScheduledEvent(canonicalId(String.valueOf(type)), weight));
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("解析 events.schedule 失败，回退为旧版均匀随机：" + ex.getMessage());
        }
        schedule = out;
        if (!out.isEmpty()) {
            plugin.getLogger().info("事件轮换表已启用（events.schedule）：" + out.size() + " 项，按权重轮换。");
        }
    }

    /**
     * 轮换表别名归一化：{@code starfall} = 默认星陨事件
     * （即 {@code events.types} 里的 {@code meteor}）；其余 id 原样透传
     * （{@code dragon-tide} 等占位事件直接用自己的段）。
     */
    private String canonicalId(String raw) {
        String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if ("starfall".equals(id) || "star-fall".equals(id) || "star_fall".equals(id)) {
            return typeSection("meteor") != null ? "meteor" : id;
        }
        return id;
    }

    /**
     * 挑一个要开启的事件 id。
     *
     * <p>轮换表为空 = 旧行为原样（均匀随机，行为完全一致）；非空 = 按权重轮换。</p>
     */
    private String pickEvent() {
        if (schedule.isEmpty()) {
            List<String> all = ids();
            return all.isEmpty() ? null : all.get(random.nextInt(all.size()));
        }
        int total = 0;
        for (ScheduledEvent s : schedule) {
            total += s.weight();
        }
        int roll = random.nextInt(Math.max(1, total));
        for (ScheduledEvent s : schedule) {
            roll -= s.weight();
            if (roll < 0) {
                return s.id();
            }
        }
        return schedule.get(schedule.size() - 1).id();
    }

    private void begin(String id) {
        activeId = id;
        elapsed = 0;
        idle = 0;
        ConfigurationSection sec = typeSection(id);
        String text = sec == null ? "" : sec.getString("announce-start", "");
        if (!text.isEmpty()) {
            Bukkit.broadcast(Msg.mm(Msg.PREFIX + text));
        }
        spawnRound();
    }

    private void endEvent(boolean announce) {
        String id = activeId;
        activeId = null;
        if (announce && id != null) {
            ConfigurationSection sec = typeSection(id);
            String text = sec == null ? "" : sec.getString("announce-end", "");
            if (!text.isEmpty()) {
                Bukkit.broadcast(Msg.mm(Msg.PREFIX + text));
            }
        }
    }

    // ------------------------------------------------------------ 空投

    private void spawnRound() {
        ConfigurationSection sec = typeSection(activeId);
        if (sec == null) {
            return;
        }
        List<Drop> table = parseDrops(sec.getConfigurationSection("drops"));
        if (table.isEmpty()) {
            return;
        }
        pruneSpawned();
        int cap = maxActiveDrops();
        int perPlayer = dropsPerPlayer();
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (int i = 0; i < perPlayer && spawned.size() < cap; i++) {
                Location loc = randomGround(p);
                Drop d = pick(table);
                ItemStack stack = MaterialGrades.stamp(new ItemStack(d.material, d.amount(random)), d.grade);
                Item item = loc.getWorld().dropItem(loc, stack);
                item.setVelocity(new Vector(0, 0, 0));
                item.setPickupDelay(10);
                spawned.add(item.getUniqueId());
                spawnedAt.put(item.getUniqueId(), loc.clone());
                loc.getWorld().spawnParticle(Particle.END_ROD, loc.clone().add(0, 0.5, 0), 20, 0.4, 0.4, 0.4, 0.02);
                loc.getWorld().playSound(loc, Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.2f);
            }
        }
    }

    /** 玩家附近随机取一个落点（取地表高度） */
    private Location randomGround(Player p) {
        World world = p.getWorld();
        int r = spawnRadius();
        int x = p.getLocation().getBlockX() + (int) Math.round((random.nextDouble() * 2 - 1) * r);
        int z = p.getLocation().getBlockZ() + (int) Math.round((random.nextDouble() * 2 - 1) * r);
        int y = world.getHighestBlockYAt(x, z) + 1;
        return new Location(world, x + 0.5, y, z + 0.5);
    }

    private void pruneSpawned() {
        java.util.Iterator<UUID> it = spawned.iterator();
        while (it.hasNext()) {
            UUID u = it.next();
            Entity e = Bukkit.getEntity(u);
            // v2.26.6（审计 #2）：e == null 只说明区块卸载（实体还在存档里），
            // 和 isDead 不是一回事——这里清了记录，shutdown 的 removeSpawned
            // 就永远找不到它，空投物残留世界。只有确认死亡才清。
            if (e != null && e.isDead()) {
                it.remove();
                spawnedAt.remove(u);
            }
        }
    }

    private void removeSpawned() {
        for (Map.Entry<UUID, Location> e : spawnedAt.entrySet()) {
            Entity en = Bukkit.getEntity(e.getKey());
            if (en == null && e.getValue() != null) {
                // 区块已卸载：实体还在存档里，Bukkit.getEntity 看不到。
                // 只扫已加载区块的话，空投就永久留在世界里（残影曾踩过同一个坑）
                e.getValue().getChunk().load();
                en = Bukkit.getEntity(e.getKey());
            }
            if (en != null) {
                en.remove();
            }
        }
        spawned.clear();
        spawnedAt.clear();
    }

    // ------------------------------------------------------------ 配置

    private ConfigurationSection typeSection(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        return plugin.getConfig().getConfigurationSection("events.types." + id);
    }

    private String displayOf(String id) {
        ConfigurationSection sec = typeSection(id);
        return sec == null ? id : sec.getString("display", id);
    }

    private List<Drop> parseDrops(ConfigurationSection drops) {
        List<Drop> out = new ArrayList<>();
        if (drops == null) {
            return out;
        }
        for (String key : drops.getKeys(false)) {
            ConfigurationSection d = drops.getConfigurationSection(key);
            if (d == null) {
                continue;
            }
            Material mat = Material.matchMaterial(d.getString("item", "").toUpperCase(Locale.ROOT));
            if (mat == null || !mat.isItem()) {
                plugin.getLogger().warning("世界事件掉落 " + key + " 的 item 无效，已跳过");
                continue;
            }
            int min = Math.max(1, d.getInt("min", 1));
            int max = Math.max(min, d.getInt("max", min));
            out.add(new Drop(mat, MaterialGrade.byName(d.getString("grade", "common")),
                    Math.max(1, d.getInt("weight", 1)), min, max));
        }
        return out;
    }

    private Drop pick(List<Drop> table) {
        int total = 0;
        for (Drop d : table) {
            total += d.weight;
        }
        int roll = random.nextInt(total);
        for (Drop d : table) {
            roll -= d.weight;
            if (roll < 0) {
                return d;
            }
        }
        return table.get(table.size() - 1);
    }

    private int intervalMinutes() {
        return Math.max(1, plugin.getConfig().getInt("events.interval-minutes", 60));
    }

    private int durationSeconds() {
        return Math.max(30, plugin.getConfig().getInt("events.duration-seconds", 180));
    }

    private int spawnIntervalSeconds() {
        return Math.max(1, plugin.getConfig().getInt("events.spawn-interval-seconds", 15));
    }

    private int spawnRadius() {
        return Math.max(2, plugin.getConfig().getInt("events.spawn-radius", 12));
    }

    private int dropsPerPlayer() {
        return Math.max(1, plugin.getConfig().getInt("events.drops-per-player", 1));
    }

    private int maxActiveDrops() {
        return Math.max(1, plugin.getConfig().getInt("events.max-active-drops", 60));
    }

    /** 一条掉落规则 */
    private static final class Drop {
        final Material material;
        final MaterialGrade grade;
        final int weight;
        final int min;
        final int max;

        Drop(Material material, MaterialGrade grade, int weight, int min, int max) {
            this.material = material;
            this.grade = grade;
            this.weight = weight;
            this.min = min;
            this.max = max;
        }

        int amount(Random rnd) {
            return min + rnd.nextInt(max - min + 1);
        }
    }
}
