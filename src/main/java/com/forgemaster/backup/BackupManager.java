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
 * <p>备份目录：plugins/ForgingMaster/backups/&lt;时间戳&gt;/，包含存在的存档文件：
 * data.yml、commissions.yml、manuals.yml、auction.yml、tombs.yml。</p>
 *
 * <p>回档前会先自动备份一次当前存档（前缀 pre-restore-），方便反悔。</p>
 */
public final class BackupManager {

    /**
     * 需要备份的存档文件（不存在的跳过）。
     *
     * <p><b>v2.23.1 修 P0：原来只有 10 份，漏了 8 份</b>。回档后会出现最迷惑的状态——
     * 「剑币回到 3 天前，但行会仓库还是现在的」「委托重置了，砧位上的剑还在」。
     * 现在与代码实际落盘的 18 份文件一一对应：新增存档文件时必须同步加到这个数组，
     * {@link #backup} 会把「漏列」以外的文件全部原样复制，所以这份清单是唯一的真相源。</p>
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
            "contract.yml",          // 契约圣物
            // v2.23.1 补：这 8 份此前完全不在备份里
            "guild-data.yml",        // 行会 + 行会仓库 + 行会委托
            "orders.yml",            // 锻造订单
            "anvils.yml",            // 铁砧砧位与砧上会话
            "pass-data.yml",         // 赛季通行证进度
            "codex-data.yml",        // 剑谱图鉴收集
            "spectrum-data.yml",     // 剑谱/万象
            "daily-data.yml",        // 每日签到
            "bounty-data.yml",       // 悬赏已领名单（v2.23.1 新增的文件）
    };

    /** 需要整目录备份的子目录（趋势快照是派生数据，回档时一并替换才自洽） */
    private static final String METRICS_DIR = "metrics";

    private static final String DIR_NAME = "backups";
    private static final String PRE_RESTORE_PREFIX = "pre-restore-";

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
        if (present.isEmpty()) {
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
        try {
            for (String f : SAVE_FILES) {
                File src = new File(dir, f);
                if (src.isFile()) {
                    Files.copy(src.toPath(), new File(dataDir, f).toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            // metrics/ 这类子目录：整目录替换（趋势快照是派生数据，一并回档才自洽）
            File srcMetrics = new File(dir, METRICS_DIR);
            if (srcMetrics.isDirectory()) {
                File dstMetrics = new File(dataDir, METRICS_DIR);
                copyDir(srcMetrics, dstMetrics);
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
        swallow(stage, "contract.yml", () -> plugin.contractManager().store().save());
        swallow(stage, "manuals.yml", () -> plugin.manuals().save());
        // story.yml 是章节定义（config 驱动），运行时状态只有 progress 一份
        swallow(stage, "story-progress.yml", () -> plugin.story().save());
        swallow(stage, "daily-data.yml", () -> plugin.dailyStore().save());
        swallow(stage, "season.yml", () -> plugin.seasons().save());
        swallow(stage, "hall-strengthen.yml", () -> plugin.hallOfFame().strengthen().save());
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
        swallow("回档后", "contract.yml", () -> plugin.contractManager().store().load());
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
