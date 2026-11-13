package com.forgemaster.contests;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 锻造大赛的历届赛果档案（v2.26.0）：{@code contest-history.yml}。
 *
 * <p>以前赛果（contestCode / 榜单）在 {@link ContestManager#finish()} 发完奖就被清掉，
 * 用完即弃。现在结算时落一份历史：<b>只增不改</b>，保留最近
 * {@code contest.history-keep} 届（默认 20，配置可调），防止文件无限膨胀。</p>
 *
 * <pre>
 * history:
 *   0:                      # 旧 → 新 依次排列，裁剪从头部删
 *     code: "A3F2B1"        # 本届种子号
 *     at: 1731470000000     # 结算时间戳（毫秒）
 *     material: "铁锭"       # 剑胚显示名
 *     winner: "Steve"       # 冠军名
 *     winner-score: 12.3
 *     players: 5            # 已交剑人数
 *     top: ["1. Steve:12.3", ...]   # 前 5 名快照
 * </pre>
 *
 * <p>存档一律走 {@link com.forgemaster.util.YamlIo}（loadOrQuarantine + 原子 save），
 * 照 {@code contract/ContractStore} 的样板。</p>
 *
 * <p><b>落盘时机</b>：结算点（{@code finish()}）直接同步 save 并判返回值，不走
 * markDirty + 异步冲刷。理由：赛果是<b>单次不可再生事件</b>——契约状态漏一笔下一轮
 * 还能补，一届大赛结束就结束了，异步窗口（≤30 秒）内崩溃 = 这届档案永久丢失；
   而一场一次的同步原子写，代价完全可接受。返回 false（写盘失败）时调用方打 SEVERE
 * 留痕，内存里这届仍在，下一次结算会连同补写出去。</p>
 */
public final class ContestHistoryStore {

    /** 一届赛果的档案条目（归档后不再修改） */
    public static final class Entry {
        public String code;             // 种子号（6 位 hex）
        public long at;                 // 结算时间戳（毫秒）
        public String material = "";    // 剑胚显示名
        public String winner = "";      // 冠军名
        public double winnerScore;      // 冠军分数
        public int players;             // 已交剑人数
        public List<String> top = new ArrayList<>(); // 前 5 名：「名次.名字:分数」

        /**
         * 深拷贝（v2.26.0）：{@link #recent} / {@link #byCode} 一律返回副本。
         *
         * <p>{@code top} 是 List，浅拷贝会共享同一个列表 —— 调用方 add 一下就改到了
         * 「已归档不再修改」的赛史上。所以这里显式 new 一个 ArrayList。</p>
         */
        Entry copy() {
            Entry c = new Entry();
            c.code = code;
            c.at = at;
            c.material = material;
            c.winner = winner;
            c.winnerScore = winnerScore;
            c.players = players;
            c.top = top == null ? new ArrayList<>() : new ArrayList<>(top);
            return c;
        }
    }

    private final Plugin plugin;
    private final File file;
    private final int keep;
    /** 旧 → 新 */
    private final List<Entry> entries = new ArrayList<>();

    /**
     * 磁盘内容落后于内存（有届次还没落盘）。
     *
     * <p>为什么要这个标记：「已在档」的分支恒返回 true 才不撒谎 —— 但前提是那一届<b>真的在盘上</b>。
     * 上一次 save() 失败时新加的条目只在内存里，此时若对同 code 再来一次 record() 直接回 true，
     * 调用方就会以为「已经在盘上了」，那个 SEVERE 说要补写的一届反而永远补不上。
     * 所以「已在档」只在磁盘同步时才等于成功。</p>
     */
    private boolean dirty;

    /** 正式构造：plugins/ForgingMaster/contest-history.yml，保留届数走配置 */
    public ContestHistoryStore(Plugin plugin) {
        this(plugin, new File(plugin.getDataFolder(), "contest-history.yml"),
                Math.max(1, plugin.getConfig().getInt("contest.history-keep", 20)));
    }

    /** 自检用：指定文件与保留届数，不碰真实存档 */
    public ContestHistoryStore(Plugin plugin, File file, int keep) {
        this.plugin = plugin;
        this.file = file;
        this.keep = Math.max(1, keep);
    }

    // ------------------------------------------------------------ 载入 / 保存

    public synchronized void load() {
        entries.clear();
        dirty = false; // 刚跟磁盘对齐
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = com.forgemaster.util.YamlIo.loadOrQuarantine(
                file, this.getClass().getSimpleName(), plugin.getLogger());
        ConfigurationSection root = yml.getConfigurationSection("history");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection cs = root.getConfigurationSection(key);
            if (cs == null) {
                continue;
            }
            Entry e = new Entry();
            e.code = cs.getString("code", "");
            e.at = cs.getLong("at", 0);
            e.material = cs.getString("material", "");
            e.winner = cs.getString("winner", "");
            e.winnerScore = cs.getDouble("winner-score", 0);
            e.players = Math.max(0, cs.getInt("players", 0));
            e.top = new ArrayList<>(cs.getStringList("top"));
            entries.add(e);
        }
        // v2.26.0 审计轮：读回来也要按 keep 裁剪。
        // 原来只有 record() 的新增路径会裁，于是「服主把 contest.history-keep 从 20 改成 5」
        // 这件事只在下一届大赛结算时才生效一次 —— 而 reloadHistory() 重建后立刻又会把
        // 那 20 届全读回来，看起来像改了配置没用。读入时就裁到 keep，语义才自洽。
        // 注意只裁内存、不主动写盘：load() 的契约是「按盘重建」，写盘留给下一次 record()。
        if (entries.size() > keep) {
            java.util.List<Entry> trimmed = new ArrayList<>(entries.subList(entries.size() - keep, entries.size()));
            entries.clear();
            entries.addAll(trimmed);
            dirty = true; // 内存与盘已经不一致（盘上还有被裁掉的旧届），等下一次 record() 一并写回
        }
    }

    /** 整份重建落盘。@return 是否写盘成功，调用方必须判（赛果不可再生） */
    public synchronized boolean save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            String base = "history." + i;
            yml.set(base + ".code", e.code == null ? "" : e.code);
            yml.set(base + ".at", e.at);
            yml.set(base + ".material", e.material == null ? "" : e.material);
            yml.set(base + ".winner", e.winner == null ? "" : e.winner);
            yml.set(base + ".winner-score", e.winnerScore);
            yml.set(base + ".players", e.players);
            yml.set(base + ".top", new ArrayList<>(e.top));
        }
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                plugin.getLogger().warning("contest-history.yml 建目录失败");
                dirty = true;
                return false;
            }
            com.forgemaster.util.YamlIo.save(file, yml);
            dirty = false;
            return true;
        } catch (IOException ex) {
            plugin.getLogger().warning("contest-history.yml 写入失败: " + ex.getMessage());
            dirty = true; // 内存里有东西没进盘：下一次 record() 的「已在档」不能直接报成功
            return false;
        } catch (RuntimeException ex) {
            // 同 PowerLogStore.save 的修法：YamlIo.save 也可能抛 RuntimeException
            // （SecurityException / IllegalArgumentException），漏掉它会穿过 record()
            // 打断整条归档链，而返回值契约「true = 已落盘」会被隐式违背。
            plugin.getLogger().warning("contest-history.yml 写入失败（未预期异常）: " + ex);
            dirty = true;
            return false;
        }
    }

    // ------------------------------------------------------------ 归档 / 查询

    /**
     * 归档一届（追加到尾部，超出 keep 从头部裁掉最老的）。
     *
     * <p>幂等：同一 code 已在档时直接返回 true，不产生第二条 —— finish() 可能因
     * tick 超时与 shutdown 双路径各触发一次，档案只增不改，不允许重复条目。</p>
     *
     * <p>「已在档」分支不做任何 I/O：档案只增不改，重复归档不改变文件内容，整份重写纯属浪费；
     * 更糟的是重写一旦失败会返回 false，让调用方误判成「这届还只在内存里」，SEVERE 日志跟着撒谎。</p>
     *
     * <p>但「已在档」只有在磁盘同步时才等于成功：上一次 save() 失败留下未落盘的条目时，
     * 这里必须先补写一次再回 true，否则调用方（ContestManager.archiveContest）会以为
     * 那届已经在盘上，Severe 里承诺的「下一场连带补写」永远不会发生。</p>
     *
     * @return true = 已在档且已落盘，或本次新增已成功落盘；false = 参数非法（null / code 空），
     *         或本次新增落盘失败（调用方须留痕）
     */
    public synchronized boolean record(Entry e) {
        if (e == null || e.code == null || e.code.isBlank()) {
            return false;
        }
        for (Entry old : entries) {
            // 防御：Entry 是 public 可变字段，entries 的内容既来自 load() 也可能来自外部构造的实例；
            // byCode() 早就防了这一层，这里对齐，别让一个空 code 用 NPE 打断整条归档链
            if (old.code != null && old.code.equalsIgnoreCase(e.code)) {
                // 已在档即成功：内容不可能变，不重写文件 —— 除非上一轮压根没写进盘
                return !dirty || save();
            }
        }
        entries.add(e);
        while (entries.size() > keep) {
            entries.remove(0);
        }
        return save();
    }

    /** 最近 n 届（旧的在前）；n 超出总量给全部。返回副本，见 {@link Entry#copy()} */
    public synchronized List<Entry> recent(int n) {
        int from = Math.max(0, entries.size() - Math.max(0, n));
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries.subList(from, entries.size())) {
            out.add(e.copy());
        }
        return out;
    }

    /**
     * 按届次号（种子号，忽略大小写）查单届；没有返回 null。
     *
     * <p>v2.26.0 审计轮：返回**副本**而不是内部对象。原来直接 return e，
     * 调用方拿到的是活引用 —— 改一下就把「已归档的赛史」改了，而且不经 save()，
     * 磁盘和内存当场不一致。现在的调用点（{@code archiveContest} 判重用）只读，
     * 但「只读」是靠调用方自觉，不是靠类型约束；封在这里才不依赖下个人的克制。</p>
     */
    public synchronized Entry byCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (Entry e : entries) {
            if (e.code != null && e.code.equalsIgnoreCase(code.trim())) {
                return e.copy();
            }
        }
        return null;
    }

    /** 当前在档届数 */
    public synchronized int size() {
        return entries.size();
    }

    /** 档案行用的时间格式（本地时区） */
    public static String fmtTime(long at) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(at));
    }

    /** 一届的分数格式化（两处展示共用，避免 Locale 逗号混进文案） */
    public static String fmtScore(double score) {
        return String.format(Locale.ROOT, "%.1f", score);
    }
}
