package com.forgemaster.growth;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.stats.StatsStore;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 匠衔段位（B4）。赛季制：段位按声望（或专精等级）划档，只升不降，
 * 赛季切换时按 {@code craft-rank.season-fall} 回落若干档（E2 规则）。
 *
 * <p>赛季编号优先读 {@link com.forgemaster.season.SeasonManager#number()}；
 * SeasonManager 不可用时退回 config 的 season.number。段位字段
 * {@code rankTier}（档序号）与 {@code rankSeason}（上次计算所在赛季）存在 data.yml。</p>
 */
public final class CraftRankManager {

    /** 段位档：名称 + 起点 */
    public record Tier(String name, double min) {
    }

    /** config 没写 craft-rank.tiers 时的兜底档 */
    private static final List<Tier> DEFAULTS = List.of(
            new Tier("学徒", 0),
            new Tier("匠人", 100),
            new Tier("宗师", 300),
            new Tier("大匠", 800),
            new Tier("铸剑圣手", 2000));

    private final ForgingMasterPlugin plugin;

    public CraftRankManager(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("craft-rank.enabled", true);
    }

    /** 跨档时是否全服广播（config craft-rank.broadcast） */
    public boolean broadcast() {
        return plugin.getConfig().getBoolean("craft-rank.broadcast", true);
    }

    /** 划档依据：reputation（声望）或 level（专精等级） */
    public String basis() {
        return plugin.getConfig().getString("craft-rank.basis", "reputation").toLowerCase(java.util.Locale.ROOT);
    }

    /** 赛季切换时回落几档 */
    public int fallPerSeason() {
        return Math.max(0, plugin.getConfig().getInt("craft-rank.season-fall", 1));
    }

    /** 当前赛季编号（接 E2 的 SeasonManager，不可用时读 config） */
    public int currentSeason() {
        if (plugin.seasons() != null) {
            return plugin.seasons().number();
        }
        return Math.max(1, plugin.getConfig().getInt("season.number", 1));
    }

    /** 段位表（按起点升序）；config 缺失或格式错误时用内置兜底 */
    public List<Tier> tiers() {
        List<Tier> out = new ArrayList<>();
        List<Map<?, ?>> raw = plugin.getConfig().getMapList("craft-rank.tiers");
        for (Map<?, ?> m : raw) {
            Object name = m.get("name");
            Object min = m.get("min");
            if (name == null || !(min instanceof Number num)) {
                continue;
            }
            out.add(new Tier(String.valueOf(name), num.doubleValue()));
        }
        if (out.isEmpty()) {
            return DEFAULTS;
        }
        out.sort(Comparator.comparingDouble(Tier::min));
        return out;
    }

    /** 段位名（档序号越界时钳到合法范围） */
    public String nameAt(int tier) {
        List<Tier> list = tiers();
        int i = Math.max(0, Math.min(tier, list.size() - 1));
        return list.get(i).name();
    }

    /** 某玩家当前段位名（不做赛季回落，仅展示；展示前应先 refresh） */
    public String nameOf(UUID uuid) {
        return nameAt(plugin.stats().get(uuid).rankTier);
    }

    /**
     * 刷新段位：先做赛季回落，再按依据值升档。只升不降（赛季内）。
     * 升档时发称号并按配置全服播报。
     */
    public void refresh(UUID uuid) {
        if (!enabled() || uuid == null || plugin.stats() == null) {
            return;
        }
        StatsStore store = plugin.stats();
        StatsStore.Stats s = store.get(uuid);
        int season = currentSeason();

        int tier = s.rankTier;
        int seasonAt = s.rankSeason;
        if (seasonAt == 0) {
            seasonAt = season; // 首次进入段位系统，不回落
        } else if (seasonAt != season) {
            int steps = Math.max(0, season - seasonAt);
            tier = Math.max(0, tier - fallPerSeason() * steps);
            seasonAt = season;
        }

        List<Tier> list = tiers();
        int target = targetIndex(s, list);
        int newTier = Math.max(tier, target);

        if (newTier != s.rankTier || seasonAt != s.rankSeason) {
            store.setRank(uuid, newTier, seasonAt);
        }
        if (newTier > tier) {
            announce(uuid, s, list.get(Math.min(newTier, list.size() - 1)).name());
        }
    }

    private int targetIndex(StatsStore.Stats s, List<Tier> list) {
        double value = "level".equals(basis()) ? s.level : s.reputation;
        int idx = 0;
        for (int i = 0; i < list.size(); i++) {
            if (value >= list.get(i).min()) {
                idx = i;
            }
        }
        return idx;
    }

    private void announce(UUID uuid, StatsStore.Stats s, String name) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            GrowthUtil.title(p, "<gradient:#ffd166:#ff7a18><bold>段位晋升</bold></gradient>",
                    "<gray>你现在是「<#ffd166>" + name + "</#ffd166>」");
            Msg.prefix(p, "<gold>匠衔晋升为 <#ffd166>" + name + "</#ffd166>！");
        }
        if (broadcast()) {
            plugin.getServer().broadcast(Msg.mm(Msg.PREFIX + "<gray>" + GrowthUtil.displayName(s.name, uuid)
                    + " <gray>晋升匠衔「<#ffd166>" + name + "</#ffd166>」！"));
        }
    }
}
