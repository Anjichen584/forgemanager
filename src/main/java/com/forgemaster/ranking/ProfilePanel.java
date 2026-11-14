package com.forgemaster.ranking;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.stats.StatsStore;
import com.forgemaster.util.Msg;
import org.bukkit.command.CommandSender;

import java.util.UUID;

/**
 * 玩家档案面板的 v2.0 追加段：剑币余额、连锻链、声望称号、成就数、各榜名次。
 * 由 /zhujian stats 与 /zhujian profile 共用。
 */
public final class ProfilePanel {

    private ProfilePanel() {
    }

    public static void show(ForgingMasterPlugin plugin, CommandSender to, UUID uuid) {
        StatsStore store = plugin.stats();
        // v2.26.5（审计嫌疑 #2）：同 stats 命令——查任意 UUID 不再凭空建档
        StatsStore.Stats st = store.peek(uuid);
        if (st == null) {
            Msg.send(to, "<gray>这个玩家没有档案（没进过服或没锻过剑）。</gray>");
            return;
        }
        long now = System.currentTimeMillis();
        boolean alive = st.streak > 0 && st.streakAt > 0 && now - st.streakAt <= StatsStore.STREAK_WINDOW_MS;

        Msg.send(to, "<dark_gray>──── <gradient:#ffd166:#ff7a18><bold>档案面板</bold></gradient> <dark_gray>────");
        Msg.send(to, "<gold>剑币: <white>" + st.coins + " <gray>枚");
        Msg.send(to, "<gold>连锻链: " + (alive
                ? "<#ff9a3c>第 " + st.streak + " 连 <gray>（最高 " + st.bestStreak + "）"
                : "<gray>未在链上（最高 " + st.bestStreak + "）"));
        int toNext = StatsStore.reputationToNextTitle(st.reputation);
        Msg.send(to, "<gold>声望称号: <#ffd166>" + StatsStore.titleOf(st.reputation)
                + " <dark_gray>|</dark_gray> <gray>声望 <white>" + st.reputation
                + (toNext > 0 ? " <dark_gray>（再 " + toNext + " 到下一档）" : " <dark_gray>（已满档）"));
        Msg.send(to, "<gold>成就: <white>" + st.achievements.size() + " <gray>个 · 成就点 <white>" + st.achvPoints);
        Msg.send(to, "<gray>排名: 剑币 " + rankText(store, Metric.COINS, uuid)
                + " <dark_gray>· <gray>声望 " + rankText(store, Metric.REPUTATION, uuid)
                + " <dark_gray>· <gray>连锻 " + rankText(store, Metric.STREAK, uuid));
        // 强化名次来自 StatsStore 的历史最高等级（v2.5 起落库），离线玩家照样有名次
        int sr = Rankings.rankOf(store, Metric.STRENGTHEN, uuid);
        Msg.send(to, "<gray>强化: " + (sr < 0 ? "<dark_gray>未上榜" : "<white>#" + sr)
                + " <dark_gray>·</dark_gray> " + strengthenText(uuid)
                + " <dark_gray>（历史最高，离线也算）</dark_gray>");
        Msg.send(to, "<dark_gray>/zhujian rank [coins|reputation|streak|level|strengthen] 看排行榜");
    }

    private static String rankText(StatsStore store, Metric metric, UUID uuid) {
        int r = Rankings.rankOf(store, metric, uuid);
        return r < 0 ? "<dark_gray>未上榜" : "<white>#" + r;
    }

    /** 强化维度：先看强化榜里有没有这个人，有才谈名次 */
    private static String strengthenText(UUID uuid) {
        StrengthenSnapshot.Best b = StrengthenSnapshot.bestOf(uuid);
        if (b == null) {
            return "<dark_gray>未上榜";
        }
        return "<white>+" + b.level() + "<dark_gray>/</dark_gray><gray>+" + b.cap();
    }
}
