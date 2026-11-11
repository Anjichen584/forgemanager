package com.forgemaster.story;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.stats.StatsStore;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 剧情任务链：章节由 story.yml 驱动，进度存入 story-progress.yml。
 *
 * <p>进度计算：每章记录开始时的统计基线（commissionsDone 或 forged），
 * 当前值减去基线即章节进度，达到 goal 即完成并推送剧情文本。
 * 因此不需要改动委托或锻造代码，只读 StatsStore。</p>
 */
public final class StoryManager {

    /** 单个章节定义 */
    public record Chapter(String id, String title, String type, int goal, String text) {
    }

    private final ForgingMasterPlugin plugin;
    private final File progressFile;
    private final List<Chapter> chapters = new ArrayList<>();
    /** uuid -> 当前章节索引（== chapters.size() 表示全部完成） */
    private final Map<UUID, Integer> chapterIndex = new HashMap<>();
    /** uuid -> 当前章节开始时的统计基线 */
    private final Map<UUID, Integer> baseline = new HashMap<>();
    private BukkitTask task;
    private YamlConfiguration storyCfg = new YamlConfiguration();

    public StoryManager(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.progressFile = new File(plugin.getDataFolder(), "story-progress.yml");
    }

    public void load() {
        chapters.clear();
        File storyFile = new File(plugin.getDataFolder(), "story.yml");
        if (!storyFile.exists()) {
            plugin.saveResource("story.yml", false);
        }
        storyCfg = YamlConfiguration.loadConfiguration(storyFile);
        for (Map<?, ?> raw : storyCfg.getMapList("chapters")) {
            String id = String.valueOf(raw.get("id"));
            String title = String.valueOf(raw.get("title"));
            String type = String.valueOf(raw.get("type"));
            int goal = raw.get("goal") instanceof Number n ? n.intValue() : 1;
            String text = String.valueOf(raw.get("text"));
            chapters.add(new Chapter(id, title, type, Math.max(1, goal), text));
        }
        chapterIndex.clear();
        baseline.clear();
        if (progressFile.exists()) {
            YamlConfiguration prog = YamlConfiguration.loadConfiguration(progressFile);
            ConfigurationSection players = prog.getConfigurationSection("players");
            if (players != null) {
                for (String key : players.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(key);
                        chapterIndex.put(uuid, players.getInt(key + ".chapter", 0));
                        baseline.put(uuid, players.getInt(key + ".baseline", 0));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
    }

    public void save() {
        YamlConfiguration prog = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> e : chapterIndex.entrySet()) {
            String path = "players." + e.getKey();
            prog.set(path + ".chapter", e.getValue());
            prog.set(path + ".baseline", baseline.getOrDefault(e.getKey(), 0));
        }
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            prog.save(progressFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("剧情进度保存失败：" + ex.getMessage());
        }
    }

    public void start() {
        stop();
        if (!enabled()) {
            return;
        }
        int interval = Math.max(1, storyCfg.getInt("check-interval-seconds", 10));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::check, 20L, interval * 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        save();
    }

    public boolean enabled() {
        return storyCfg.getBoolean("enabled", true) && !chapters.isEmpty();
    }

    /** 当前章节（玩家尚未开始则为第 1 章）；全部完成返回 null */
    public Chapter current(UUID uuid) {
        int idx = chapterIndex.getOrDefault(uuid, 0);
        return idx >= chapters.size() ? null : chapters.get(idx);
    }

    public int completedCount(UUID uuid) {
        return Math.min(chapterIndex.getOrDefault(uuid, 0), chapters.size());
    }

    public int total() {
        return chapters.size();
    }

    /** 当前章节进度：已完成数（相对基线） */
    public int progressOf(UUID uuid) {
        Chapter c = current(uuid);
        if (c == null) {
            return 0;
        }
        return Math.max(0, statOf(uuid, c.type()) - baseline.getOrDefault(uuid, 0));
    }

    /** 检查所有在线玩家：推进章节并推送剧情文本 */
    public void check() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            advanceIfDone(p);
        }
    }

    /** 在锻造 / 委托成功后可手动触发，立即判定 */
    public void advanceIfDone(Player player) {
        UUID uuid = player.getUniqueId();
        if (!chapterIndex.containsKey(uuid)) {
            chapterIndex.put(uuid, 0);
            Chapter first = current(uuid);
            if (first != null) {
                baseline.put(uuid, statOf(uuid, first.type()));
            }
        }
        // 连续完成多章时循环推进
        while (true) {
            Chapter c = current(uuid);
            if (c == null || progressOf(uuid) < c.goal()) {
                return;
            }
            int next = chapterIndex.get(uuid) + 1;
            chapterIndex.put(uuid, next);
            Chapter after = current(uuid);
            if (after != null) {
                baseline.put(uuid, statOf(uuid, after.type()));
            }
            deliver(player, c, after == null);
        }
    }

    private void deliver(Player player, Chapter done, boolean allDone) {
        Msg.prefix(player, "<gradient:#ffd166:#ff7a18><bold>剧情</bold></gradient> <gray>「" + done.title()
                + "」完成</gray>");
        Msg.send(player, done.text());
        if (allDone) {
            Msg.prefix(player, "<#ffd166>你已走完全部剧情，铸剑之路仍在延续。</#ffd166>");
        }
        player.playSound(player.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
        save();
    }

    /** 统计量读取：commissions → commissionsDone，forged → forged */
    private int statOf(UUID uuid, String type) {
        StatsStore.Stats st = plugin.stats().get(uuid);
        return switch (type) {
            case "commissions" -> st.commissionsDone;
            case "forged" -> st.forged;
            default -> 0;
        };
    }

    /** 供 /zhujian story 展示的一行状态 */
    public String statusLine(UUID uuid) {
        Chapter c = current(uuid);
        if (c == null) {
            return "全部章节已完成（" + total() + "/" + total() + "）";
        }
        return c.title() + " <dark_gray>|</dark_gray> 进度 "
                + Math.min(progressOf(uuid), c.goal()) + "/" + c.goal()
                + "（" + (c.type().equals("forged") ? "锻造" : "委托") + "）";
    }
}
