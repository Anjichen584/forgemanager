package com.forgemaster.growth;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.stats.StatsStore;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 成就统一判定（B3）。所有成就检查只走这一个入口：{@link #check(UUID, String)}。
 *
 * <p>里程碑表来自 config.yml 的 {@code achievements.list}，每条 type 对应一个统计字段，
 * 见 {@link #progress(AchievementDef, StatsStore.Stats)}。达成后用 {@link StatsStore#grantAchievement}
 * 去重，奖励只发一次。检查结束顺带刷新匠衔段位（{@link CraftRankManager#refresh}）。</p>
 */
public final class AchievementEvaluator {

    private final ForgingMasterPlugin plugin;
    private final GrowthConfig cfg;

    public AchievementEvaluator(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.cfg = new GrowthConfig(plugin);
    }

    /** 是否开启成就系统（config achievements.enabled） */
    public boolean enabled() {
        return plugin.getConfig().getBoolean("achievements.enabled", true);
    }

    /** 读取全部里程碑定义（每次现读，改配置后 /zhujian reload 即生效） */
    public List<AchievementDef> defs() {
        List<AchievementDef> out = new ArrayList<>();
        ConfigurationSection sec = cfg.section("achievements.list");
        if (sec == null) {
            return out;
        }
        for (String id : sec.getKeys(false)) {
            ConfigurationSection d = sec.getConfigurationSection(id);
            if (d == null) {
                continue;
            }
            out.add(new AchievementDef(
                    id,
                    d.getString("name", id),
                    d.getString("desc", ""),
                    GrowthUtil.material(d.getString("icon"), Material.PAPER),
                    d.getString("type", ""),
                    d.getString("param", ""),
                    d.getDouble("need", 1),
                    d.getLong("coins", 0),
                    d.getInt("rep", 0),
                    d.getInt("points", 0),
                    d.getBoolean("broadcast", false)));
        }
        return out;
    }

    /**
     * 统一检查入口。event 仅作调用方标注（如 "forge"、"commission"、"duel"），
     * 里程碑数量很少，当前每次都全量判定，成本可以忽略。
     */
    public void check(UUID uuid, String event) {
        if (uuid == null || plugin.stats() == null) {
            return;
        }
        if (enabled()) {
            StatsStore store = plugin.stats();
            StatsStore.Stats s = store.get(uuid);
            Player p = Bukkit.getPlayer(uuid);
            for (AchievementDef d : defs()) {
                if (store.hasAchievement(uuid, d.id())) {
                    continue;
                }
                if (progress(d, s) + 1e-9 < d.need()) {
                    continue;
                }
                if (!store.grantAchievement(uuid, d.id(), d.points())) {
                    continue; // 并发或重复调用时去重
                }
                reward(uuid, p, d);
            }
        }
        if (plugin.ranks() != null) {
            plugin.ranks().refresh(uuid);
        }
    }

    /** 当前进度值（用于判定与 GUI 进度显示） */
    public double progress(AchievementDef d, StatsStore.Stats s) {
        return switch (d.type()) {
            case "forged" -> s.forged;
            case "flawless" -> s.flawless;
            case "best-streak" -> s.bestStreak;
            // 剑币为当前余额（档案里没有累计字段）
            case "coins" -> s.coins;
            case "reputation" -> s.reputation;
            case "level" -> s.level;
            case "commissions" -> s.commissionsDone;
            case "duel-wins" -> s.duelWins;
            case "spirits" -> s.spirits;
            case "tombs" -> s.tombs;
            case "codex" -> s.codex.size();
            case "patterns" -> s.patterns.size();
            case "quality" -> s.byQuality.getOrDefault(d.param(), 0);
            case "mentor" -> (s.mentor.isEmpty() && s.disciple.isEmpty()) ? 0 : 1;
            case "achievements" -> s.achievements.size();
            default -> 0;
        };
    }

    private void reward(UUID uuid, Player p, AchievementDef d) {
        StatsStore store = plugin.stats();
        if (d.coins() > 0) {
            store.addCoins(uuid, d.coins(), com.forgemaster.econ.CoinReason.ACHIEVEMENT);
        }
        if (d.rep() > 0) {
            store.addReputation(uuid, d.rep());
        }
        String desc = d.desc().isBlank() ? "" : " <gray>" + d.desc();
        String rewards = "<gray>奖励：" + (d.coins() > 0 ? "<gold>+" + d.coins() + " 剑币 " : "")
                + (d.rep() > 0 ? "<#ffd166>+" + d.rep() + " 声望 " : "")
                + (d.points() > 0 ? "<aqua>+" + d.points() + " 成就点" : "");
        if (p != null) {
            Msg.prefix(p, "<gold>成就达成：<white>" + d.name() + "</white>" + desc);
            Msg.prefix(p, rewards);
            GrowthUtil.title(p, "<gradient:#ffd166:#ff7a18><bold>成就达成</bold></gradient>",
                    "<white>" + d.name());
            p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        }
        if (d.broadcast()) {
            String who = p != null ? p.getName() : GrowthUtil.displayName(null, uuid);
            plugin.getServer().broadcast(Msg.mm(Msg.PREFIX + "<gray>" + who + " 达成成就「<gold>"
                    + d.name() + "</gold>」！"));
        }
    }
}
