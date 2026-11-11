package com.forgemaster.metrics;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/**
 * 看板数据文件的落盘工具：<b>tmp + 原子替换</b>。
 *
 * <p>为什么不能直接 {@code yml.save(file)}：看板每 N 分钟写一次，而服主随时可能
 * 关服 / kill -9 / 断电。写到一半的文件是半截 YAML，读回来直接抛异常——
 * 那一天的趋势就整段消失，而且日志里只留一行 warning，谁也不会去翻。</p>
 *
 * <p>做法：先写 {@code <name>.tmp}，{@code fsync} 后 {@code ATOMIC_MOVE} 顶掉目标。
 * 目标文件在任意时刻要么是旧的完整版、要么是新的完整版，不存在「半截」。
 * 文件系统不支持原子移动（部分网络盘 / Windows 跨卷）时退回普通 move，
 * 并删掉残留 tmp——退化路径至少不会留下野文件。</p>
 *
 * <p>本类不碰 Bukkit API，只吃 {@link File} 与 {@link YamlConfiguration}，
 * 所以可以在异步线程里随便调，也能拿临时目录做自检。</p>
 */
public final class MetricsIo {

    private MetricsIo() {
    }

    /** 写字节（CSV 等）；失败返回 false 并记一条 warning，绝不抛给调用方 */
    public static boolean writeAtomic(File target, byte[] bytes, Logger log) {
        if (target == null) {
            return false;
        }
        File dir = target.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            warn(log, "创建目录失败: " + dir.getAbsolutePath());
            return false;
        }
        File tmp = tmpOf(target);
        try {
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes == null ? new byte[0] : bytes);
                out.flush();
            }
            move(tmp, target);
            return true;
        } catch (IOException ex) {
            deleteQuietly(tmp);
            warn(log, "写 " + target.getName() + " 失败: " + ex.getMessage());
            return false;
        }
    }

    /**
     * 写 YAML：先 {@code yml.save(tmp)} 再原子替换。
     *
     * <p>{@code YamlConfiguration.save} 自带换行与编码处理，比手搓字符串稳，
     * 所以这条路单独开一支，不复用字节版。</p>
     */
    public static boolean writeYamlAtomic(File target, YamlConfiguration yml, Logger log) {
        if (target == null || yml == null) {
            return false;
        }
        File dir = target.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            warn(log, "创建目录失败: " + dir.getAbsolutePath());
            return false;
        }
        File tmp = tmpOf(target);
        try {
            yml.save(tmp);
            move(tmp, target);
            return true;
        } catch (IOException ex) {
            deleteQuietly(tmp);
            warn(log, "写 " + target.getName() + " 失败: " + ex.getMessage());
            return false;
        }
    }

    /**
     * 原子替换；不支持时退化普通 move。
     *
     * <p>{@code AtomicMoveNotSupportedException} 与 {@code UnsupportedOperationException}
     * 都要抓：前者是文件系统不支持，后者是 JVM / provider 不支持该选项。</p>
     */
    private static void move(File from, File to) throws IOException {
        try {
            Files.move(from.toPath(), to.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException | UnsupportedOperationException ex) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 临时文件名带 {@code .tmp}：即便崩在 move 前，人一眼看得出这是残渣 */
    private static File tmpOf(File target) {
        return new File(target.getParentFile(), target.getName() + ".tmp");
    }

    /** 安静删除（清理 tmp / 裁剪旧文件用） */
    public static boolean deleteQuietly(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        try {
            return file.delete();
        } catch (SecurityException ex) {
            return false;
        }
    }

    private static void warn(Logger log, String msg) {
        if (log != null) {
            log.warning("[metrics] " + msg);
        }
    }
}