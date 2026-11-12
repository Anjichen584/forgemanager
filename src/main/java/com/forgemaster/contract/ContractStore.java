package com.forgemaster.contract;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 龙税契约的存档：{@code contract.yml}，每人一份 run 状态 + 一条全局结算游标。
 *
 * <p>落盘策略（审计 #7）：变更只翻 dirty 标记，由低频异步任务（30 秒一轮）
 * 冲刷写盘，主线程动作路径（入贡 / 领圣物 / 午夜结算）不再做同步 IO；
 * {@link ContractManager#stop()}（关服 / reload）经 {@link #stop()} 强制冲刷。
 * 异步窗口（≤30 秒）内崩溃最多漏最后一笔契约变更，文件每玩家仅 8 键、
 * 丢失面可控，与拍卖行同一套 dirty 模式。</p>
 */
public final class ContractStore {

    /** 一个玩家的周目状态 */
    public static final class Run {
        public boolean joined;
        public int day = 1;
        public int missed;
        public long pool;            // 本日已入贡估值
        public long carry;           // 上日超额结转
        public List<String> relics = new ArrayList<>();
        public int phoenixDay = -1;  // 上次用凤凰灰的周目日
        public String pickPending;   // 待选的圣物三选一：逗号分隔 id，空 = 无
    }

    private final Plugin plugin;
    private final File file;
    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();
    private volatile long lastSettledFullDay = -1;
    /** 有未落盘的变更时为真；异步冲刷任务只在它为真时才写文件（审计 #7） */
    private volatile boolean dirty;
    /** v2.25.1 修 P2（审计轮3-B4）：写盘代数 —— markDirty 在写盘窗口内自增，写完
     * 只在代数没变时才清脏（AnvilForgeManager 同款），杜绝「窗口内变更被吞」。 */
    private volatile long dirtySeq;
    private int flushTaskId = -1;

    public ContractStore(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "contract.yml");
        load();
    }

    public void load() {
        runs.clear();
        if (!file.exists()) {
            dirty = false;
            return;
        }
        YamlConfiguration yml = com.forgemaster.util.YamlIo.loadOrQuarantine(file, this.getClass().getSimpleName(), plugin.getLogger());
        lastSettledFullDay = yml.getLong("global.last-settled-full-day", -1);
        ConfigurationSection players = yml.getConfigurationSection("players");
        if (players == null) {
            dirty = false;
            return;
        }
        for (String key : players.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection cs = players.getConfigurationSection(key);
                if (cs == null) {
                    continue;
                }
                Run r = new Run();
                r.joined = cs.getBoolean("joined", true);
                r.day = Math.max(1, cs.getInt("day", 1));
                r.missed = Math.max(0, cs.getInt("missed", 0));
                r.pool = Math.max(0, cs.getLong("pool", 0));
                r.carry = Math.max(0, cs.getLong("carry", 0));
                r.relics = new ArrayList<>(cs.getStringList("relics"));
                r.phoenixDay = cs.getInt("phoenix-day", -1);
                r.pickPending = cs.getString("pick-pending", "");
                runs.put(id, r);
            } catch (IllegalArgumentException ignored) {
                // 坏键名直接跳过，不让一份脏存档拖垮整个契约系统
            }
        }
        // 磁盘即真相：重载后内存与磁盘一致，任何残留脏标记都作废
        dirty = false;
    }

    /** 启动低频异步冲刷（30 秒一轮）：仅 dirty 时才真正写文件，主线程动作路径零同步 IO */
    public void startAutoFlush() {
        if (flushTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(flushTaskId);
        }
        flushTaskId = plugin.getServer().getScheduler()
                .runTaskTimerAsynchronously(plugin, this::flushIfDirty, 600L, 600L).getTaskId();
    }

    /** 关服 / reload 冲刷：停掉异步任务并强制落一次盘 */
    public synchronized void stop() {
        if (flushTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(flushTaskId);
            flushTaskId = -1;
        }
        save();
    }

    private void flushIfDirty() {
        if (!dirty) {
            return;
        }
        // v2.25.2（4 并发查错）：Bukkit 重复任务抛一次异常即被永久取消 ——
        // 契约存档会就此不再自动落盘，直到关服。这里兜住，脏标记保持等下一轮。
        try {
            save();
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("contract.yml 异步冲刷失败（保持脏，下一轮重试）：" + ex);
        }
    }

    /** 标记有未落盘的变更；真正的写盘交给低频异步冲刷（审计 #7） */
    public void markDirty() {
        dirtySeq++;
        dirty = true;
    }

    /** 自检 / 运维观测用：当前是否有未落盘变更 */
    public boolean dirty() {
        return dirty;
    }

    /**
     * 同步写盘。正常变更路径不再直调它（改走 markDirty + 异步冲刷），
     * 只剩关服 / reload 冲刷（stop）强制调用。
     */
    public synchronized void save() {
        // v2.25.2（4 并发查错）：代数必须在「组装之前」取。原来在组装完之后取，
        // 组装窗口内主线程的 markDirty 会被当成「已经包含在本次写盘里」而被清掉 ——
        // 那一笔变更就永久丢了（脏标记没了，没人再写它）。
        long seq = dirtySeq;
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("global.last-settled-full-day", lastSettledFullDay);
        try {
            for (Map.Entry<UUID, Run> e : runs.entrySet()) {
                String base = "players." + e.getKey();
                Run r = e.getValue();
                yml.set(base + ".joined", r.joined);
                yml.set(base + ".day", r.day);
                yml.set(base + ".missed", r.missed);
                yml.set(base + ".pool", r.pool);
                yml.set(base + ".carry", r.carry);
                // r.relics 是主线程直改的 ArrayList（交付/破除会给它 add/clear），
                // 这里拷一份就可能撞 CME。以前它在 try 之外，抛出去会打死异步冲刷任务。
                yml.set(base + ".relics", new ArrayList<>(r.relics));
                yml.set(base + ".phoenix-day", r.phoenixDay);
                yml.set(base + ".pick-pending", r.pickPending == null ? "" : r.pickPending);
            }
        } catch (java.util.ConcurrentModificationException ex) {
            return; // 保持脏，下一轮重写；任务本身不许死
        }
        try {
                com.forgemaster.util.YamlIo.save(file, yml);
            // v2.25.1：写盘窗口内若有 markDirty（seq 已前进），保持脏让下一次冲刷重写；
            // 旧注释「清脏不会漏账」对变更侧不成立 —— Run 对象是主线程直改的，不进本锁
            if (seq == dirtySeq) {
                dirty = false;
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("contract.yml 写入失败: " + ex.getMessage());
        }
    }

    /** 取 run 状态；没有返回 null（未加入契约） */
    public Run run(UUID id) {
        return runs.get(id);
    }

    /** 没有就建（joined = true）；返回 run */
    public Run ensure(UUID id) {
        return runs.computeIfAbsent(id, k -> {
            Run r = new Run();
            r.joined = true;
            return r;
        });
    }

    public void remove(UUID id) {
        runs.remove(id);
        markDirty();
    }

    /** 全部在契玩家 */
    public Map<UUID, Run> all() {
        return runs;
    }

    public long lastSettledFullDay() {
        return lastSettledFullDay;
    }

    public void lastSettledFullDay(long v) {
        this.lastSettledFullDay = v;
        markDirty();
    }
}
