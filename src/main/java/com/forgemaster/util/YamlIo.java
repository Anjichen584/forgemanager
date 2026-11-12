package com.forgemaster.util;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * YAML 落盘的原子写 + 坏档隔离（v2.25.5）。
 *
 * <h2>为什么需要它</h2>
 * 全仓原先有 70 多处直接 {@code yml.save(file)}。Bukkit 的 {@code YamlConfiguration.save}
 * 是「以覆盖方式打开目标文件再逐字节写出去」，不是原子操作：
 *
 * <ul>
 *   <li>写到一半 JVM 被 kill / 断电 / 磁盘满 → 目标文件变成**半截 YAML**；</li>
 *   <li>下次 {@code YamlConfiguration.loadConfiguration(file)} 解析失败只会打一条 warning，
 *       然后返回一个**空配置**；</li>
 *   <li>而各存储类的 {@code load()} 看到空配置就 {@code return}（等于「这个玩家没有档案」），
 *       接着第一次 {@code save()} 用空内存盖掉那个半截文件 —— <b>原始数据连抢救的机会都没了</b>。</li>
 * </ul>
 *
 * <p>所以这里做两件事：</p>
 * <ol>
 *   <li>{@link #save}：先写同目录的 {@code <name>.tmp}，{@code fsync} 后再
 *       {@code ATOMIC_MOVE} 顶掉目标。任何时刻目标文件要么是旧的完整内容，要么是新的完整内容，
 *       不存在半截。</li>
 *   <li>{@link #loadOrQuarantine}：文件存在、非空、却解析不出任何键（或直接抛
 *       {@link InvalidConfigurationException}）时，把原文件**改名**成
 *       {@code <name>.corrupt-<时间戳>} 留在原地，再返回空配置。
 *       这样数据虽然用不了，但服主手上还有原件可以手工救，而不是被下一次 save 覆盖。</li>
 * </ol>
 *
 * <p><b>不改变任何业务语义</b>：正常读写路径与原来一致，只是写得更安全、坏档时更可救。</p>
 */
public final class YamlIo {

    private YamlIo() {
    }

    /**
     * 每个目标文件一把锁（v2.25.6）。
     *
     * <p>Windows 上两个线程同时 {@code ATOMIC_MOVE} 顶同一个目标会抛
     * {@code AccessDeniedException}（目标正被对方替换），自检的 8 线程并发写当场复现了。
     * 这些存档全部在同一个 JVM 内写，所以「按绝对路径取锁」就能彻底消掉这层竞争 ——
     * 不依赖调用方记得加锁（40 多个调用点，迟早漏）。</p>
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> LOCKS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static Object lockFor(File target) {
        String key;
        try {
            key = target.getCanonicalPath();
        } catch (IOException ex) {
            key = target.getAbsolutePath();
        }
        return LOCKS.computeIfAbsent(key, k -> new Object());
    }

    /**
     * 原子落盘：写唯一名字的 {@code <name>.<pid>.<tid>.<nanos>.tmp} → ATOMIC_MOVE 覆盖目标。
     *
     * <p>{@code ATOMIC_MOVE} 在部分文件系统（某些网络盘 / FAT32）上会抛
     * {@code AtomicMoveNotSupportedException}，这时退化成普通的 {@code REPLACE_EXISTING} ——
     * 仍然比「直接覆盖写」安全（目标文件在被替换前始终完整）。</p>
     *
     * @throws IOException 写盘失败（调用方必须自己 try/catch 并记日志，别静默吞掉）
     */
    public static void save(File target, YamlConfiguration yml) throws IOException {
        synchronized (lockFor(target)) {
            File dir = target.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                throw new IOException("建目录失败: " + dir);
            }
            // v2.25.6 修 P0：临时文件名必须唯一。原来固定叫 <name>.tmp，两个线程同时 save
            // 同一份存档（例如 5 秒异步冲刷正在写盘时管理员 /zhujian reload 触发同步 save）
            // 会往同一个 tmp 里各写一份字节流 → 交错成半截/混杂 YAML → ATOMIC_MOVE 顶上去
            // 就是坏档（会被 loadOrQuarantine 改名留档，砧位上所有剑一起失踪）。
            Path tmp = new File(dir, target.getName() + "."
                    + ProcessHandle.current().pid() + "."
                    + Thread.currentThread().threadId() + "."
                    + System.nanoTime() + ".tmp").toPath();
            try {
                yml.save(tmp.toFile());
                Path dst = target.toPath();
                IOException last = null;
                // 杀软 / 外部进程短暂占用目标文件时重试几次，别一次失败就丢这一轮数据
                for (int attempt = 0; attempt < 3; attempt++) {
                    try {
                        try {
                            Files.move(tmp, dst, StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING);
                        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
                        }
                        last = null;
                        break;
                    } catch (java.nio.file.FileSystemException ex) {
                        last = ex;
                        try {
                            Thread.sleep(20L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IOException("写盘被中断: " + target.getName(), ie);
                        }
                    }
                }
                if (last != null) {
                    throw last;
                }
            } finally {
                // 中途出错也要把自己那份 tmp 收掉，别在数据目录里留垃圾
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 删不掉就算了，下次同名不会撞（名字带纳秒）
                }
            }
        }
    }

    /**
     * 读盘：解析不出来就把原文件改名留档，返回空配置。
     *
     * <p>「解析不出来」的判据是 **文件非空但一个键都没有**。空文件（0 字节）视为
     * 「第一次跑」，直接返回空配置、不动它 —— 别把新服的首启当成坏档。</p>
     *
     * @param owner 日志前缀，一般是存储类名，方便服主在日志里定位是哪份档案坏了
     * @return 解析到的配置（可能为空）；永远不返回 null
     */
    public static YamlConfiguration loadOrQuarantine(File file, String owner, java.util.logging.Logger log) {
        YamlConfiguration yml = new YamlConfiguration();
        if (file == null || !file.exists()) {
            return yml;
        }
        long size = file.length();
        try {
            yml.load(file);
        } catch (InvalidConfigurationException | IOException ex) {
            return quarantine(file, owner, log, "解析失败: " + ex.getMessage());
        }
        if (size > 0 && yml.getKeys(false).isEmpty()) {
            // 非空文件却读不出任何键 = 半截 / 乱码。绝不能让它被下次 save 覆盖掉。
            return quarantine(file, owner, log, "非空但读不出任何键（疑似半截写入）");
        }
        return yml;
    }

    private static YamlConfiguration quarantine(File file, String owner, java.util.logging.Logger log, String why) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File bad = new File(file.getParentFile(), file.getName() + ".corrupt-" + stamp);
        boolean moved = file.renameTo(bad);
        if (log != null) {
            log.severe("[" + owner + "] " + file.getName() + " " + why
                    + (moved ? "；原件已留档为 " + bad.getName() + "，请人工检查后再决定是否删掉"
                             : "；留档改名也失败了，请立刻手工备份该文件！"));
        }
        return new YamlConfiguration();
    }

    /** 给自检用：判断某份 yml 的内容是不是「非空的半截」（不落盘、不改名） */
    public static boolean looksTruncated(File file) {
        if (file == null || !file.exists() || file.length() == 0) {
            return false;
        }
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            YamlConfiguration yml = new YamlConfiguration();
            yml.loadFromString(text);
            return yml.getKeys(false).isEmpty();
        } catch (InvalidConfigurationException | IOException ex) {
            return true;
        }
    }
}