package com.forgemaster.backup;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.util.Msg;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * 存档备份 / 回档（E3）。
 *
 * <p>备份目录：plugins/ForgingMaster/backups/&lt;时间戳&gt;/，包含 {@link #SAVE_FILES} 里
 * 实际存在的存档文件（这份清单是唯一真相源，这里不再复述文件名 —— 复述过的那份永远会过期）。</p>
 *
 * <p>回档前会先自动备份一次当前存档（前缀 pre-restore-），方便反悔。</p>
 */
public final class BackupManager {

    /**
     * 需要备份的存档文件（不存在的跳过）。
     *
     * <p><b>v2.23.1 修 P0：原来只有 10 份，漏了 8 份</b>。回档后会出现最迷惑的状态——
     * 「剑币回到 3 天前，但行会仓库还是现在的」「委托重置了，砧位上的剑还在」。
     * 现在覆盖 21 份核心存档（经济 / 行会 / 委托 / 砧位 / 名人堂 / 剧情 / 赛季 / 通行证…）：
     * 新增存档文件时必须同步加到这个数组 —— {@link #backup} 只复制清单内的文件，清单外的
     * 一律不碰，所以这份数组是「什么会跟着回档一起退回」的唯一真相源。</p>
     *
     * <p><b>v2.26.0 补三份</b>（contest-history.yml / power-log.yml / hall-legacy.yml）：
     * 只加清单是不够的，回档后还必须按盘重建前两份的内存态，见 {@link #flushAll} /
     * {@link #reloadAllSilently}；hall-legacy.yml 没有内存缓存，只补清单即可。</p>
     *
     * <p>清单之外还有两类「不叫 .yml 的存档」，由 {@link #backup} 单独处理：
     * 数据根目录的散装趋势快照 {@code metrics-<yyyyMMdd>.yml}，与 {@code seasons/} 归档目录。</p>
     */
    public static final String[] SAVE_FILES = {
            // 经济与主体数据
            "data.yml",              // 剑币 / 玩家档案 / 统计
            "commissions.yml",       // 委托
            "manuals.yml",           // 剑谱
            "auction.yml",           // 拍卖
            "tombs.yml",             // 剑冢
            "season.yml",            // 赛季
            "hall.yml",              // 名人堂
            "hall-strengthen.yml",   // 名人堂强化榜
            "story-progress.yml",    // 剧情进度
            "story.yml",             // 剧情状态（v2.23.1 补）
            // v2.23.1 补：这 8 份此前完全不在备份里
            "guild-data.yml",        // 行会 + 行会仓库 + 行会委托
            "orders.yml",            // 锻造订单
            "anvils.yml",            // 铁砧砧位与砧上会话
            "pass-data.yml",         // 赛季通行证进度
            "codex-data.yml",        // 剑谱图鉴收集
            "spectrum-data.yml",     // 剑谱/万象
            "daily-data.yml",        // 每日签到
            "bounty-data.yml",       // 悬赏已领名单（v2.23.1 新增的文件）
            // v2.26.0 补：这两个文件都漏在清单外，回档时既不备份也不恢复
            "contest-history.yml",   // 历届大赛赛果：单次不可再生，见下方注释
            "power-log.yml",         // 战力评分曲线：观测数据，口径见下方注释
            "hall-legacy.yml",       // 跨赛季剑传承：只增不删的永久记录，见下方注释
    };

    /*
     * hall-legacy.yml 为什么也进备份（v2.26.0 审计轮补）：
     * 它和 hall.yml / hall-strengthen.yml 是同一族（名人堂三份），前两份早在清单里，
     * 唯独这一份从 v2.19 加入以来一直漏着 —— 结果回档时「名人堂名次回去了、每赛季那把名剑没回去」，
     * 两张表当场对不上。它是 SeasonManager.end() 刻下的永久记录（同一赛季已刻过就不改写），
     * 丢了没有任何补写路径，比 power-log 那种派生视图严重。
     * 与 contest-history 不同的是：SeasonLegacy **没有内存缓存**，每次 all()/ofSeason() 都
     * 直接 load() 磁盘，所以回档覆盖文件后不需要任何 reload 钩子，只补清单即可。
     */

    /*
     * contest-history.yml 为什么必须进备份：它记的是「哪一届谁夺冠」，是单次不可再生事件。
     * ContestHistoryStore 为了这件事专门放弃了异步冲刷（改成结算点同步原子写），代价已经付了；
     * 如果它不在清单里，回档时这一份既不进 pre-restore 也不被覆盖 —— 管理员回档到三天前，
     * 剑币、行会、委托全回去了，唯独赛史还停在今天，而「反悔」这条后路根本没有那三天的赛果。
     * 只加清单还不够：ContestHistoryStore 是 ContestManager 的惰性单例，内存里握着旧条目，
     * 下一次 record() 会整份回写把刚恢复的文件盖掉（与 v2.23.1 那个 P0 完全同型），
     * 所以 flushAll / reloadAllSilently 必须同时补上。
     *
     * power-log.yml 为什么也进备份（自洽口径：跟着 data.yml 一起进退）：
     * 它确实是观测数据、非账本（ForgingMasterPlugin:118），单份丢失可以接受 —— 但那说的是
     * 「崩溃丢最近几条曲线点」。回档语境下不一样：战力曲线是用 data.yml 里的剑算出来的派生视图，
     * 回档后 data.yml 回到三天前，若曲线不回退，/zhujian power 会显示「比存档里的剑更强」的
     * 记录，两张表当场对不上，管理员无法判断回档到底生效了没有。既然它和 data.yml 是同一批
     * 事实的两种视图，就一起进退；它的容量上限只有 12 点/人，进备份的代价可忽略。
     */

    /**
     * 数据根目录下的「散装快照文件」前缀与后缀：{@code metrics-<yyyyMMdd>.yml}。
     *
     * <p><b>v2.26.0 修：原来这里写的是 {@code METRICS_DIR = "metrics"}，那是死代码。</b>
     * 趋势快照从来不在 {@code metrics/} 子目录里 —— {@code MetricsService} 把
     * {@code plugin.getDataFolder()} 直接传给 {@code MetricsHistory}，文件名是
     * {@code MetricsHistory.PREFIX + yyyyMMdd + SUFFIX}，因此实际是数据根目录下的散文件
     * （测试服实测：{@code plugins/ForgingMaster/metrics-20261111.yml}）。
     * 于是 `isDirectory()` 恒为 false，那一段回档逻辑从来没执行过：
     * 趋势快照既不备份也不恢复，回档后 /zhujian metrics 仍显示回档之后的数据。</p>
     */
    private static final String METRICS_PREFIX = "metrics-";

    private static final String METRICS_SUFFIX = ".yml";

    /**
     * 需要整目录备份的子目录。
     *
     * <p>{@code seasons/} 存的是每赛季结算时导出的排行归档（{@code season-N.yml}）。
     * 它是「那一季的最终名次」的唯一留档，凭 season.yml 里的当前季状态重建不出来；
     * 与 hall-legacy.yml 同族（都是赛季系永久记录），此前同样漏在备份外。</p>
     */
    private static final String SEASONS_DIR = "seasons";

    private static final String DIR_NAME = "backups";
    private static final String PRE_RESTORE_PREFIX = "pre-restore-";

    /** 数据根目录下所有 metrics-*.yml（趋势快照散文件） */
    private static List<String> metricsFiles(File dataDir) {
        List<String> out = new ArrayList<>();
        File[] kids = dataDir.listFiles();
        if (kids == null) {
            return out;
        }
        for (File f : kids) {
            String n = f.getName();
            if (f.isFile() && n.startsWith(METRICS_PREFIX) && n.endsWith(METRICS_SUFFIX)) {
                out.add(n);
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    private final ForgingMasterPlugin plugin;

    public BackupManager(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    private File root() {
        return new File(plugin.getDataFolder(), DIR_NAME);
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
    }

    /**
     * 立即备份当前存档到新目录。
     *
     * @param prefix 目录名前缀（普通备份为空，回档前自动备份为 pre-restore-）
     * @return 新备份的目录 id；无存档可备份时返回 null
     */
    public synchronized String backup(String prefix) throws IOException {
        // v2.25.1 修 P2（审计轮3-B6）：/zhujian backup 原来不冲刷 —— 备份抓的是
        // 「缺最近 30 秒内存进度」的旧盘。与 restore 同款：先把脏数据落盘再备份。
        if (prefix == null || !prefix.equals(PRE_RESTORE_PREFIX)) {
            // restore 自己已经先 flushAll（避免重复冲刷），只有独立备份需要补
            flushAll("备份前");
        }
        File dataDir = plugin.getDataFolder();
        List<String> present = new ArrayList<>();
        for (String f : SAVE_FILES) {
            if (new File(dataDir, f).isFile()) {
                present.add(f);
            }
        }
        // v2.26.0：趋势快照是数据根目录下的散文件（metrics-<yyyyMMdd>.yml），不在 SAVE_FILES 里，
        // 也不是子目录。此前「整个 metrics/ 目录备份」是死代码，等于快照完全不参与备份。
        List<String> metrics = metricsFiles(dataDir);
        if (present.isEmpty() && metrics.isEmpty()) {
            return null;
        }
        String id = prefix + stamp();
        File target = new File(root(), id);
        // 同一秒多次备份时加序号，避免覆盖
        int n = 1;
        while (target.exists()) {
            target = new File(root(), id + "-" + n++);
        }
        // v2.25 修 P2：先写 -tmp 再原子改名。原来直接写最终目录，中途崩服会留下
        // 半份备份被 list() 当有效备份列出，回档进去就是混纪元数据。
        File tmp = new File(root(), target.getName() + "-tmp");
        deleteRecursively(tmp);
        if (!tmp.mkdirs()) {
            throw new IOException("无法创建备份目录 " + tmp.getName());
        }
        try {
            for (String f : present) {
                Files.copy(new File(dataDir, f).toPath(),
                        new File(tmp, f).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            // v2.26.0：趋势快照散文件一起进备份（与 restore 的恢复逻辑对称）
            for (String f : metrics) {
                Files.copy(new File(dataDir, f).toPath(),
                        new File(tmp, f).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            // v2.26.0：seasons/ 归档目录整目录复制（赛季最终名次唯一留档）
            File seasonsDir = new File(dataDir, SEASONS_DIR);
            if (seasonsDir.isDirectory()) {
                copyDir(seasonsDir, new File(tmp, SEASONS_DIR));
            }
            if (!tmp.renameTo(target)) {
                throw new IOException("备份目录改名失败（" + tmp.getName() + " -> " + target.getName() + "）");
            }
        } catch (IOException ex) {
            deleteRecursively(tmp);
            throw ex;
        }
        return target.getName();
    }

    /** v2.25：递归删除（只用于清理 -tmp 残留，路径恒在备份根目录内） */
    private static void deleteRecursively(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (k.isDirectory()) {
                    deleteRecursively(k);
                } else {
                    k.delete();
                }
            }
        }
        dir.delete();
    }

    /** 列出可用备份（按名字倒序，即最新在前）。只列普通备份与回档前备份，不列不合法目录 */
    public List<String> list() {
        List<String> out = new ArrayList<>();
        File[] dirs = root().listFiles(File::isDirectory);
        if (dirs == null) {
            return out;
        }
        Arrays.sort(dirs, (a, b) -> b.getName().compareTo(a.getName()));
        // v2.25：-tmp 是原子改名前的半成品，不是有效备份
        dirs = Arrays.stream(dirs).filter(d -> !d.getName().endsWith("-tmp")).toArray(File[]::new);
        for (File d : dirs) {
            out.add(d.getName());
        }
        return out;
    }

    /** 备份目录里包含的存档文件，用于展示 */
    public List<String> filesOf(String id) {
        List<String> out = new ArrayList<>();
        File dir = safeDir(id);
        if (dir == null) {
            return out;
        }
        for (String f : SAVE_FILES) {
            if (new File(dir, f).isFile()) {
                out.add(f);
            }
        }
        // v2.26.0 审计轮修：上面只列 SAVE_FILES，而 backup() 现在还搬趋势快照散文件
        // 与 seasons/ 归档目录 —— 它们在备份里真实存在，却不显示。管理员看清单会以为
        // 没备上，进而以为回档丢了快照。展示必须与实际一致，否则这份清单是在撒谎。
        List<String> metrics = metricsFiles(dir);
        if (!metrics.isEmpty()) {
            out.addAll(metrics);
        }
        if (new File(dir, SEASONS_DIR).isDirectory()) {
            out.add(SEASONS_DIR + "/");
        }
        return out;
    }

    /** 防止路径穿越：id 只能是目录名，不能含 / \ .. */
    private File safeDir(String id) {
        if (id == null || id.isBlank() || id.contains("/") || id.contains("\\") || id.contains("..")) {
            return null;
        }
        File dir = new File(root(), id);
        return dir.isDirectory() ? dir : null;
    }

    /**
     * 回档：先把内存里的所有脏数据落盘 → 自动备份当前存档 → 覆盖回档 → **只重载不保存**。
     *
     * <h2>为什么顺序必须是这样（v2.23.1 修 P0）</h2>
     * 原来的顺序是「先备份 → 再 flush 内存 → 覆盖 → 只重建 stats/auction 两个缓存」，两个方向都有洞：
     * <ol>
     *   <li><b>前置备份抓的是旧盘</b>：备份发生在 flush 之前，内存里还没落盘的进度（最多 5 分钟）
     *       不在 pre-restore 里 —— 回档想反悔时，反悔点比现实更早。</li>
     *   <li><b>回档被脏冲刷盖回（最贵的一条）</b>：17 个存档文件里只重建了 data.yml 与 auction.yml
     *       两个内存态。其余模块（guilds / orders / commissions / tombs / codex / anvils / pass…）
     *       内存里还是回档前的旧副本且带着 dirty 标记，它们的定时任务会在 5 秒~5 分钟内
     *       把刚恢复的文件<b>原样盖回去</b>。管理员看到「回档成功」，实际几秒后就悄悄回滚了。</li>
     * </ol>
     * 现在：先 flush 全部 → 备份（此刻盘面 = 真相）→ 覆盖 → 按文件重建**所有**内存态。
     * 重载一律走 {@code load()} / {@code reloadStore()}，<b>绝不调用任何 save()</b>——
     * 否则就是用回档前的内存去盖回档后的文件，比不重载更糟。
     *
     * @return 回档前自动备份的目录 id；备份目录不存在时返回 null
     */
    public synchronized String restore(String id) throws IOException {
        File dir = safeDir(id);
        if (dir == null) {
            return null;
        }
        // ① 先把手里的脏数据全部落盘：让「当前盘面」= 真实进度
        flushAll("回档前");
        // ② 再备份（此时备份的是真实进度，反悔点正确）
        String preId = backup(PRE_RESTORE_PREFIX);
        // ③ 覆盖存档目录
        File dataDir = plugin.getDataFolder();
        // 在 try 外声明：finally 之后还要用它判断「备份里到底有没有快照」并提示管理员
        List<String> backMetrics = metricsFiles(dir);
        try {
            for (String f : SAVE_FILES) {
                File src = new File(dir, f);
                if (src.isFile()) {
                    Files.copy(src.toPath(), new File(dataDir, f).toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            // v2.26.0：趋势快照散文件（metrics-<yyyyMMdd>.yml）一并回档。
            // 原来这里是 `new File(dir, "metrics")` 整目录替换 —— 那个目录从来不存在
            // （快照是根目录散文件），所以这段是死代码，快照完全不参与回档。
            // 现在的判据与 backup() 对称：备份目录里有哪些 metrics-*.yml 就恢复哪些；
            // 备份里没有的日子不删（那几天可能在备份之后才采样，属于「保持现值」）。
            // 用外面那个 backMetrics，别再调一次（两处结果必须一致才对称）
            for (String f : backMetrics) {
                Files.copy(new File(dir, f).toPath(), new File(dataDir, f).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            // seasons/ 归档目录：整目录替换（赛季最终名次唯一留档）
            File srcSeasons = new File(dir, SEASONS_DIR);
            if (srcSeasons.isDirectory()) {
                copyDir(srcSeasons, new File(dataDir, SEASONS_DIR));
                // v2.26.5（审计嫌疑）：copyDir 是 REPLACE_EXISTING 只增不删——备份之后
                // 新归档的 season-N.yml 会留在盘上，季号退回后历季名次里能看到「未来」。
                // 删掉备份点没有的归档文件，让目录与备份点严格一致。
                File dstSeasons = new File(dataDir, SEASONS_DIR);
                File[] keep = srcSeasons.listFiles();
                java.util.Set<String> keepNames = new java.util.HashSet<>();
                if (keep != null) {
                    for (File k : keep) {
                        keepNames.add(k.getName());
                    }
                }
                File[] stale = dstSeasons.listFiles();
                if (stale != null) {
                    for (File o : stale) {
                        if (!keepNames.contains(o.getName()) && o.delete()) {
                            plugin.getLogger().info("[restore] seasons/ 清掉备份点没有的归档：" + o.getName());
                        }
                    }
                }
            }
        } finally {
            // v2.25 修 P2：中途 IOException 时不能半途而废 —— flushAll 已跑、脏标记已清，
            // 定时冲刷不会纠正。无论成败都按磁盘重建内存，混态最多维持到这条命令结束。
            reloadAllSilently();
        }
        // v2.25：备份里没有的新版存档文件（升级后新增模块）保持现值，向调用方提示
        // 「一半新一半旧」的风险，让管理员知情而不是默默混纪元
        List<String> missing = new java.util.ArrayList<>();
        for (String f : SAVE_FILES) {
            if (!new File(dir, f).isFile() && new File(dataDir, f).isFile()) {
                missing.add(f);
            }
        }
        if (!missing.isEmpty()) {
            plugin.getLogger().warning("[restore] 备份 " + id + " 缺这些现存文件（保持现值，数据可能混纪元）: "
                    + String.join(", ", missing));
        }
        if (backMetrics.isEmpty()) {
            plugin.getLogger().warning("[restore] 备份 " + id + " 里没有 metrics-*.yml：趋势快照保持现值");
        }
        return preId == null ? "(无当前存档，未做前置备份)" : preId;
    }

    /**
     * 把各模块内存里的脏数据刷到盘上。
     *
     * <p>每个模块单独 try：某一个坏了不能让其余模块的进度留在内存里等死
     * （回档最怕的就是「一半落盘一半没落」）。</p>
     */
    private void flushAll(String stage) {
        swallow(stage, "data.yml", () -> plugin.stats().save());
        swallow(stage, "auction.yml", () -> plugin.auction().flushStore());
        swallow(stage, "guild-data.yml", () -> plugin.guilds().save());
        swallow(stage, "orders.yml", () -> plugin.orders().save());
        swallow(stage, "commissions.yml", () -> plugin.commissions().save());
        swallow(stage, "tombs.yml", () -> plugin.tombs().save());
        swallow(stage, "codex-data.yml", () -> plugin.codex().save());
        swallow(stage, "anvils.yml", () -> plugin.anvilForge().save());
        swallow(stage, "pass-data.yml", () -> plugin.seasonPass().save());
        swallow(stage, "spectrum-data.yml", () -> plugin.spectrum().save());
        // v2.25 修 P1：以下模块的存档在 SAVE_FILES 里、回档时会被覆盖，
        // 但 flushAll 此前漏了它们 —— 内存还是回档前副本，30 秒一轮的异步
        // 脏冲刷（contract 同型）或下一次变更即整文件回写，把回档静默吞掉。
        // 与 v2.23.1 修过的「stats 被脏冲刷盖回」完全同型，这里是漏网模块。
        swallow(stage, "manuals.yml", () -> plugin.manuals().save());
        // story.yml 是章节定义（config 驱动），运行时状态只有 progress 一份
        swallow(stage, "story-progress.yml", () -> plugin.story().save());
        swallow(stage, "daily-data.yml", () -> plugin.dailyStore().save());
        swallow(stage, "season.yml", () -> plugin.seasons().save());
        swallow(stage, "hall-strengthen.yml", () -> plugin.hallOfFame().strengthen().save());
        // v2.26.0 审计轮补：bounty-data.yml 原是全仓唯一「有 reload 无 flush」的项。
        // 回档把文件换成旧的，内存里还是回档前的 claimed，下一次 claim() 的 save()
        // 会整份回写 —— 回档结果被静默吞掉，连日志都没有。与 v2.23.1 / v2.25 同型。
        //
        // 注：hall.yml 这里**故意没有一行**，不是漏了。它的两次写（/zhujian halloffame set
        // 整份重写、HallShrine.setOrigin 改 shrine 段）都是变更即写盘，没有内存脏窗口，
        // 所以 flush 不产生任何内容差异。它需要的是 reload（见 reloadAllSilently），
        // 因为 HallShrine.origin 是字段缓存。
        swallow(stage, "bounty-data.yml", () -> plugin.bounties().flush());
        // v2.26.0：这两份都不走 markDirty + 异步冲刷，落盘时机各不相同，所以必须在这里显式落：
        // ① contest-history 只在结算点写一次（赛果不可再生），没有定时器兜底；
        // ② power-log 每次 append 就写（观测数据，同样没有定时器兜底）；
        // ③ 两者被回档覆盖后，内存旧副本会让下一次 record()/append() 整份回写，静默吞掉回档结果。
        // historyStore() 是惰性单例：这里若首次调用会连带 load 一次再 save，写出的内容与盘上
        // 完全一致，对「回档前盘面 = 真相」这个前提没有影响，不额外做存在性判断。
        swallow(stage, "contest-history.yml", () -> {
            var manager = plugin.contests();
            // 判空：构造期失败时 contests() 可能是 null，不能让一份档案把整条 flush 链带崩
            if (manager != null && !manager.historyStore().save()) {
                // save() 返回 false 不抛异常，swallow 抓不到 —— 而这份是单次不可再生的账本，
                // 静默失败等于「回档前盘面 = 真相」这个前提悄悄不成立，必须留一行痕
                plugin.getLogger().warning(stage + "写 contest-history.yml 返回失败："
                        + "这一届赛果不在 pre-restore 备份里，且单次不可再生");
            }
        });
        swallow(stage, "power-log.yml", () -> {
            var store = plugin.powerLog();
            if (store != null) {
                store.save();
            }
        });
    }

    /**
     * 只重载、不保存。这是回档能否真正生效的关键一步。
     *
     * <p><b>不要换成 {@code plugin.reloadAll()}</b>：那个方法为了让配置生效会对
     * spectrum / seasonPass / dailyStore 各调一次 {@code save()}，而此刻内存里
     * 还是回档前的数据——一调 save 就把刚恢复的文件盖回去了。</p>
     */
    private void reloadAllSilently() {
        swallow("回档后", "data.yml", () -> plugin.stats().load());
        swallow("回档后", "auction.yml", () -> plugin.auction().reloadStore());
        swallow("回档后", "guild-data.yml", () -> plugin.guilds().load());
        swallow("回档后", "orders.yml", () -> plugin.orders().load());
        swallow("回档后", "commissions.yml", () -> plugin.commissions().load());
        swallow("回档后", "tombs.yml", () -> plugin.tombs().load());
        swallow("回档后", "codex-data.yml", () -> plugin.codex().load());
        swallow("回档后", "pass-data.yml", () -> plugin.seasonPass().load());
        swallow("回档后", "spectrum-data.yml", () -> plugin.spectrum().load());
        // v2.25 修 P1：与 flushAll 同一批漏网模块 —— 覆盖文件后必须按盘重建内存，
        // 否则旧副本在下次冲刷时把回档结果盖回去（manuals 是变更即全量写，最急）
        swallow("回档后", "manuals.yml", () -> plugin.manuals().load());
        swallow("回档后", "story-progress.yml", () -> plugin.story().load());
        swallow("回档后", "daily-data.yml", () -> plugin.dailyStore().load());
        swallow("回档后", "season.yml", () -> plugin.seasons().loadState());
        swallow("回档后", "hall-strengthen.yml", () -> plugin.hallOfFame().strengthen().load());
        // v2.25.1 修 P1（审计轮3-B2）：铁砧与悬赏此前自称「没有公开 reload」——
        // 实际 AnvilForgeManager.loadFrom(File) 是 public，BountyManager 补了 reload()。
        // 这两份被异步冲刷（砧位 5 秒）或领赏（变更即存）整文件回写，必须重建。
        java.io.File anvilsFile = new java.io.File(plugin.getDataFolder(), "anvils.yml");
        if (anvilsFile.isFile()) {
            swallow("回档后", "anvils.yml", () -> plugin.anvilForge().loadFrom(anvilsFile));
        }
        swallow("回档后", "bounty-data.yml", () -> plugin.bounties().reload());
        // v2.26.0 审计轮补：hall.yml 覆盖后必须刷掉 HallShrine 的字段缓存。
        // origin() 是惰性加载 + 一次缓存，回档换了文件它并不知道 —— 于是「殿堂原点」
        // 在回档后到重启前一直指向旧坐标，而 /zhujian hall set 写回去的还是缓存值，
        // 等于回档对这个字段完全没生效。低危（只影响展示位置），但同 §3「字段缓存」
        // 那一类是同一个病，接上 reload 比留个「以后再修」干净。
        swallow("回档后", "hall.yml", () -> plugin.hallShrine().reloadOrigin());
        // v2.26.5（审计嫌疑）：metrics-*.yml 回档已修，但看板的 cache（启动时读入后
        // 只 append/merge）与 lastPruneDay 没重建——回档后 /zhujian metrics 与 CSV 导出
        // 仍显示回档前的趋势。reload() = stop+start，start() 会 refreshCacheAsync 按盘
        // 重建并重置裁剪游标。
        swallow("回档后", "metrics-*.yml", () -> plugin.metrics().reload());
        // v2.26.0：与 flushAll 同一批 —— 这两份被覆盖后必须按盘重建内存，否则旧副本会在
        // 下一次 record()（结算点同步写）/ append()（每次评分都写）时整份回写，
        // 把刚恢复的文件原样盖回去。这正是 v2.23.1 那个 P0 的同型，不能只加清单。
        swallow("回档后", "contest-history.yml", () -> {
            var manager = plugin.contests();
            if (manager != null) {
                // 回档后按盘重建；keep 配置由 ContestManager.reloadHistory() 在 reloadAll 链路处理，
                // 这里是回档专用路径 —— 只按盘重建数据，不动保留届数（回档不该改配置语义）。
                manager.historyStore().load();
            }
        });
        // power-log 的 load() 会把 dirty 清掉，所以重载后不会留下「假脏」让下一次评分
        // 立刻整份回写；这一点必须由 load() 保证，否则这里就是在制造脏标记。
        swallow("回档后", "power-log.yml", () -> {
            var store = plugin.powerLog();
            if (store != null) {
                store.load();
            }
        });
    }

    /** 单个模块的存/读失败只记一行日志，不中断整个回档 */
    private void swallow(String stage, String file, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            plugin.getLogger().warning(stage + "处理 " + file + " 失败（其余文件继续）: " + ex.getMessage());
        }
    }

    /** 递归覆盖目录（回档用；目标目录不存在就建） */
    private static void copyDir(File src, File dst) throws IOException {
        if (!dst.exists() && !dst.mkdirs()) {
            throw new IOException("无法创建目录 " + dst.getName());
        }
        File[] kids = src.listFiles();
        if (kids == null) {
            return;
        }
        for (File kid : kids) {
            File target = new File(dst, kid.getName());
            if (kid.isDirectory()) {
                copyDir(kid, target);
            } else {
                Files.copy(kid.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** 把 backup/restore 的结果发给管理员（统一文案） */
    public void sendList(CommandSender to) {
        List<String> ids = list();
        if (ids.isEmpty()) {
            Msg.prefix(to, "<gray>还没有任何备份。用 <white>/zhujian backup</white> 创建一份。");
            return;
        }
        Msg.prefix(to, "<gold>可用备份</gold> <dark_gray>(共 " + ids.size() + " 份，最新在前)</dark_gray>");
        int shown = 0;
        for (String id : ids) {
            if (shown++ >= 10) {
                Msg.send(to, "<dark_gray>  ……另有 " + (ids.size() - 10) + " 份未列出</dark_gray>");
                break;
            }
            Msg.send(to, "<gray>  · <white>" + id + "</white> <dark_gray>" + String.join(", ", filesOf(id)));
        }
        Msg.send(to, "<gray>回档用 <white>/zhujian restore <id></white>（回档前会自动再备份一次）");
    }
}
