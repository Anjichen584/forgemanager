package com.forgemaster.quench;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public final class QuenchRegistry {

    private final Plugin plugin;
    private final Map<String, Quench> quenches = new LinkedHashMap<>();

    public QuenchRegistry(Plugin plugin) {
        this.plugin = plugin;
    }

    public void load(FileConfiguration cfg) {
        quenches.clear();
        ConfigurationSection root = cfg.getConfigurationSection("quench");
        if (root == null) {
            plugin.getLogger().warning("config.yml 里没有 quench 段");
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) {
                continue;
            }
            try {
                Material icon = Material.matchMaterial(sec.getString("icon", "WATER_BUCKET"));
                if (icon == null) {
                    plugin.getLogger().warning("淬火介质 " + id + " 的 icon 无效，已跳过");
                    continue;
                }
                List<String> pool = new ArrayList<>();
                for (String s : sec.getStringList("pool")) {
                    pool.add(s.toLowerCase());
                }
                quenches.put(id.toLowerCase(), new Quench(
                        id,
                        sec.getString("display", id),
                        sec.getString("color", "#ffffff"),
                        icon,
                        sec.getDouble("score-bonus", 8),
                        pool,
                        sec.getString("description", "")
                ));
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "解析淬火介质 " + id + " 失败", ex);
            }
        }
        plugin.getLogger().info("已载入 " + quenches.size() + " 种淬火介质");
    }

    public Quench get(String id) {
        return id == null ? null : quenches.get(id.toLowerCase());
    }

    public List<Quench> all() {
        return new ArrayList<>(quenches.values());
    }

    public int size() {
        return quenches.size();
    }
}