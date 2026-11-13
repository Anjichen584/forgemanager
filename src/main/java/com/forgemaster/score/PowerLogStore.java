package com.forgemaster.score;

import com.forgemaster.ForgingMasterPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 战力评分曲线存储（power-log.yml）。
 *
 * <p>{@code /zhujian power} 每次评到一把锻造品就记一条，{@code /zhujian power log}
 * 回看最近若干条，让玩家看见自己「越锻越强」的趋势 —— 评分系统只有当下的一个数，
 * 有了曲线才是成长线的一部分。</p>
 *
 * <p><b>定位是观测数据，不是账本</b>：不参与任何经济口径，写盘失败丢的只是几条
 * 曲线点，不会产生「钱货时序」那类守恒问题。所以 {@link #append} 落盘失败时
 * 内存照常保留、返回 false 让调用方提示一句即可，不做回滚 —— 这与
 * {@code DailyStore} 的「先占额度再发货」是两个世界，别照抄那边的回滚样板。</p>
 *
 * <pre>
 * log:
 *   &lt;uuid&gt;:
 *     "0": {at: 1731470000000, p: 1234, q: 精良, m: iron, l: 5, s: 0}
 *     "1": {...}
 * </pre>
 */
public final class PowerLogStore {

    /** 每人最多留多少条：曲线只要趋势，留多了文件越养越大 */
    public static final int KEEP = 12;

    /** 同一把剑连续重复评分的去重窗口：10 分钟内各分项全同就不记第二条 */
    private static final long DEDUPE_WINDOW_MS = 10 * 60 * 1000L;

    /** 一条评分记录 */
    public static final class Entry {
        /** 评分时间（epoch millis） */
        public long at;
        /** 战力分（已钳制后的对外口径） */
        public long power;
        /** 品质名（品质缺失时为空串） */
        public String quality = "";
        /** 材质键（材质缺失时为空串） */
        public String material = "";
        /** 强化等级 */
        public int level;
        /** 蜕变阶 */
        public int stage;
    }

    private final ForgingMasterPlugin plugin;
    private final File file;
    /** uuid → 评分记录（时间正序，尾部最新） */
    private final Map<UUID, List<Entry>> log = new HashMap<>();
    /**
     * 内存里是否有还没落盘的改动。
     *
     * <p>这个标记存在的唯一理由：让 {@link #append} 的返回值不能说谎。save() 失败时内存留着
     * 那一笔、盘上却没有；紧接着来一条内容相同的评分本来会被去重分支吞掉，这时只返回 true
     * 就等于向调用方保证「盘是好的」—— 玩家什么提示都收不到，盘上却永远缺这一笔。
     * 这就是自检/压测里踩过的那种「假绿」：报告说 PASS，实际没验到东西。有了标记，
     * 去重分支能看出「上一笔还没落盘」，顺手补写一次。</p>
     */
    private boolean dirty;

    /**
     * 自检 / 运维观测用：当前是否有未落盘变更。
     *
     * <p>和 {@code AuctionStore#dirty()} 同口径。暴露出来是为了让「假绿」可被断言：
     * 光看 append 的返回值只能验到它自己说的话，能直接查标记才算钉住了。</p>
     */
    public boolean dirty() {
        return dirty;
    }

    public PowerLogStore(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "power-log.yml");
    }

    // ------------------------------------------------------------ 载入 / 保存

    public void load() {
        log.clear();
        if (!file.exists()) {
            // 没有文件＝盘上就是空的，内存也是空的，两边一致
            dirty = false;
            return;
        }
        YamlConfiguration yml = com.forgemaster.util.YamlIo.loadOrQuarantine(file, "PowerLogStore", plugin.getLogger());
        ConfigurationSection root = yml.getConfigurationSection("log");
        if (root != null) {
            for (String uuidKey : root.getKeys(false)) {
                ConfigurationSection sec = root.getConfigurationSection(uuidKey);
                if (sec == null) {
                    continue;
                }
                try {
                    UUID uuid = UUID.fromString(uuidKey);
                    List<Entry> list = new ArrayList<>();
                    for (String idx : sec.getKeys(false)) {
                        ConfigurationSection e = sec.getConfigurationSection(idx);
                        if (e == null) {
                            continue;
                        }
                        Entry en = new Entry();
                        en.at = e.getLong("at", 0L);
                        en.power = e.getLong("p", 0L);
                        en.quality = e.getString("q", "");
                        en.material = e.getString("m", "");
                        en.level = e.getInt("l", 0);
                        en.stage = e.getInt("s", 0);
                        list.add(en);
                    }
                    list.sort((a, b) -> Long.compare(a.at, b.at));
                    log.put(uuid, list);
                } catch (IllegalArgumentException ignored) {
                    // 坏键跳过，不影响启动
                }
            }
        }
        // 刚和盘对齐：内存里这份就是从盘上读来的，没有未落盘改动
        dirty = false;
        plugin.getLogger().info("已载入战力评分曲线：" + log.size() + " 份");
    }

    /**
     * 整份重建落盘（调用方决定时机；append 每次都会调）。
     *
     * <p>返回值就是「内存里的这一份现在盘上有没有」。失败时故意不清 {@link #dirty}：
     * 盘上还是旧内容，内存是新的，这个差异必须留到下一次 append 才有机会补写回去。</p>
     *
     * @return 落盘是否成功
     */
    public boolean save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, List<Entry>> e : log.entrySet()) {
            List<Entry> list = e.getValue();
            for (int i = 0; i < list.size(); i++) {
                Entry en = list.get(i);
                String base = "log." + e.getKey() + "." + i + ".";
                yml.set(base + "at", en.at);
                yml.set(base + "p", en.power);
                yml.set(base + "q", en.quality);
                yml.set(base + "m", en.material);
                yml.set(base + "l", en.level);
                yml.set(base + "s", en.stage);
            }
        }
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            com.forgemaster.util.YamlIo.save(file, yml);
            // 只有整份写成功才敢承认「盘追上了内存」；中途抛异常就保持 true
            dirty = false;
            return true;
        } catch (IOException ex) {
            plugin.getLogger().warning("战力评分曲线保存失败：" + ex.getMessage());
            return false;
        } catch (RuntimeException ex) {
            // v2.26.0 审计轮：YamlIo.save 除了 IOException 还可能抛 RuntimeException ——
            // Files.move 的 SecurityException、yml.save 的 IllegalArgumentException 都属这一类。
            // 原来只 catch IOException，这类异常会直接穿过 append() 打断 /zhujian power 命令，
            // 而且 dirty 保持 true 之外没有任何痕迹。契约是「返回值就是盘上有没有」，
            // 所以这里必须把它也翻成 false，让调用方拿到实话。
            plugin.getLogger().warning("战力评分曲线保存失败（未预期异常）：" + ex);
            return false;
        }
    }

    // ------------------------------------------------------------ 读写

    /** 某玩家的评分曲线（时间正序副本；没有记录给空表） */
    public List<Entry> of(UUID uuid) {
        List<Entry> list = log.get(uuid);
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    /**
     * 记一条评分。
     *
     * <p><b>连续重复去重</b>：与末尾一条在 {@link #DEDUPE_WINDOW_MS} 内、且六个字段全同时，
     * 视为「同一把剑同一次状态」，不再产生第二个曲线点 —— 玩家连点 {@code /zhujian power}
     * 不该把 12 条容量刷满。此时盘上已经等价地有这条记录，一般不需要重写整份文件。</p>
     *
     * <p><b>但「不需要重写」有一个例外</b>：如果上一次 {@link #save()} 失败了，内存里那一笔
     * 盘上是没有的，去重命中只说明「内存已有等价记录」，不代表「盘已有」。所以去重分支要
     * 看一眼 {@link #dirty}：脏就顺手补写一次，把上一笔真正落到盘上。这是为了让下面的
     * 返回值能对调用方说实话 —— 调用方拿 false 才提示玩家，绝不能出现「返回 true 但盘上缺一笔」。</p>
     *
     * @return 落盘是否成功。成功＝此刻内存里的曲线与盘上一致（包括「命中去重且盘上本来就有」
     *         这一种，盘上确实已经有等价记录）；失败＝内存里有、盘上缺，调用方可提示一句。
     *         入参非法（uuid/entry 为 null，或 power ≤ 0）同样返回 false —— 这一笔没记下。
     */
    public boolean append(UUID uuid, Entry entry) {
        if (uuid == null || entry == null || entry.power <= 0) {
            return false;
        }
        List<Entry> list = log.computeIfAbsent(uuid, k -> new ArrayList<>());
        // 连续重复去重：同一把剑短时间内反复评不产生噪音点
        if (!list.isEmpty()) {
            Entry last = list.get(list.size() - 1);
            if (entry.at - last.at < DEDUPE_WINDOW_MS
                    && last.power == entry.power
                    && last.level == entry.level
                    && last.stage == entry.stage
                    && last.quality.equals(entry.quality)
                    && last.material.equals(entry.material)) {
                if (!dirty) {
                    return true; // 盘上确实已经有等价记录，没必要重写盘
                }
                // 上一笔只活在内存里：这次补写，别让调用方以为盘是好的
                return save();
            }
        }
        list.add(entry);
        while (list.size() > KEEP) {
            list.remove(0);
        }
        // 先标脏再落盘：save() 内部抛异常/返回 false 时这个标记会留着，等下一次去重或 append 补写
        dirty = true;
        return save();
    }

    /**
     * 测试 / 调试用：清掉某个玩家的整条曲线（自检用它保证净效果为零）。
     *
     * <p>返回值改成 boolean 是为了不留「静默失败」：原来 {@code if (log.remove(uuid) != null) save();}
     * 判的是 Map 的返回值，save() 的结果直接丢掉 —— 清失败没人知道，自检那边以为已经
     * 净效果为零、实际文件里还留着脏数据。现在把 save() 的结果原样交出去。</p>
     *
     * <p>签名变化对调用方安全：Java 允许合法地忽略返回值，{@code store.remove(u);} 这种
     * 语句式写法照旧编译（SelfTestSuite 就是这么调的）。</p>
     *
     * <p>顺手把 {@link #dirty} 也冲掉：删掉一条之后剩下的内容照样要落盘，之前欠着的
     * 那笔补写也就一起写进去了。</p>
     *
     * @return 落盘是否成功；本来就没这条且没有待补写的改动时返回 true（盘上本来就是对的）
     */
    public boolean remove(UUID uuid) {
        boolean removed = log.remove(uuid) != null;
        if (!removed && !dirty) {
            return true; // 内存里没有、盘上也没有，两边一致，不用写
        }
        dirty = true;
        return save();
    }
}
