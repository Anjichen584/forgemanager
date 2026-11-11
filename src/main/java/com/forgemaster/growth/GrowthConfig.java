package com.forgemaster.growth;

import com.forgemaster.ForgingMasterPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 成长链路配置读取器。
 *
 * <p>优先读 config.yml 里已经合并进来的段（例如 {@code achievements.list}）；
 * 没合并时自动回退到 jar 内置的 {@code config.d/growth.yml}，
 * 所以本模块的文案与成就表全部由 YAML 提供，Java 里不写死中文。</p>
 */
final class GrowthConfig {

    private final ForgingMasterPlugin plugin;
    private YamlConfiguration bundled;

    GrowthConfig(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    /** 让下一次读取重新解析内置资源（/zhujian reload 时用） */
    void reload() {
        this.bundled = null;
    }

    /**
     * 取一个配置段。先看 config.yml，取不到（或空段）就看内置片段。
     *
     * @param path 例如 "achievements.list"、"rank"
     */
    ConfigurationSection section(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        ConfigurationSection live = plugin.settings() == null ? null : plugin.settings().section(path);
        if (live != null && !live.getKeys(false).isEmpty()) {
            return live;
        }
        return bundled().getConfigurationSection(path);
    }

    /** 内置的 config.d/growth.yml（只在没被合并进 config.yml 时生效） */
    YamlConfiguration bundled() {
        if (bundled == null) {
            bundled = new YamlConfiguration();
            InputStream in = plugin.getResource("config.d/growth.yml");
            if (in == null) {
                return bundled;
            }
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                bundled.load(reader);
            } catch (Exception ex) {
                plugin.getLogger().warning("读取内置 config.d/growth.yml 失败：" + ex.getMessage());
            }
        }
        return bundled;
    }
}
