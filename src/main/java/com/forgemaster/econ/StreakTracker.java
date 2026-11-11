package com.forgemaster.econ;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.stats.StatsStore;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * 连锻链的玩法层封装：续链、断链、静默超时检查与玩家提示。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>每把成功锻出（非炸剑）的剑 +1 连，倍率见 {@link StatsStore#streakMultiplier(int)}。</li>
 *   <li>断链：炸剑（粗糙品）、锻造作废（放弃 / 界面关闭 / 炉子冷了）、委托逾期、
 *       距上次成功锻造超过 {@link StatsStore#STREAK_WINDOW_MS}。</li>
 * </ul>
 */
public final class StreakTracker {

    private final ForgingMasterPlugin plugin;

    public StreakTracker(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    private StatsStore stats() {
        return plugin.stats();
    }

    /** 开工前检查：静默超过窗口则断链并提示 */
    public void checkSilence(Player p) {
        StatsStore.Stats st = stats().get(p.getUniqueId());
        if (st.streak > 0 && st.streakAt > 0
                && System.currentTimeMillis() - st.streakAt > StatsStore.STREAK_WINDOW_MS) {
            onBreak(p.getUniqueId(), "超过 1 小时没有锻造");
        }
    }

    /** 成功锻出一把剑：续链，返回当前连数 */
    public int onSuccess(Player p) {
        return stats().bumpStreak(p.getUniqueId());
    }

    public void onBreak(Player p, String reason) {
        onBreak(p.getUniqueId(), reason);
    }

    /** 断链（离线也能调用，只在玩家在线时发提示） */
    public void onBreak(UUID uuid, String reason) {
        int old = stats().breakStreak(uuid);
        if (old <= 0) {
            return;
        }
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            Msg.prefix(p, "<red>连锻断了</red> <dark_gray>（" + reason + "）</dark_gray> "
                    + "<gray>原本第 <white>" + old + "</white> 连");
            Msg.action(p, "<red>连锻断了 <dark_gray>| <gray>" + reason);
        }
    }

    /** 锻造结算后的提示：链数、倍率、本次剑币；streak<=0 表示本把断链 */
    public void tipAfterForge(Player p, int streak, long coins) {
        if (streak <= 0) {
            Msg.prefix(p, "<gray>剑币 +" + CoinRules.fmt(coins) + "<gray>（本把没成链）");
            return;
        }
        String now = String.format("%.2f", StatsStore.streakMultiplier(streak));
        String next = String.format("%.2f", StatsStore.streakMultiplier(streak + 1));
        Msg.prefix(p, "<gray>连锻 <#ffd166>第 " + streak + " 连</#ffd166> <gray>×<white>" + now
                + "</white> · 剑币 +" + CoinRules.fmt(coins)
                + " <dark_gray>| <gray>下一把 ×<white>" + next + "</white>，1 小时内接着锻就不断链");
        Msg.action(p, "<#ffd166>连锻 " + streak + " <dark_gray>| <gray>下一把 ×" + next);
    }

    /** 开工时提示当前链状态（没链则不提示） */
    public void showStatus(Player p) {
        StatsStore.Stats st = stats().get(p.getUniqueId());
        if (st.streak <= 0) {
            return;
        }
        String next = String.format("%.2f", StatsStore.streakMultiplier(st.streak + 1));
        Msg.prefix(p, "<gray>当前连锻 <#ffd166>第 " + st.streak + " 连</#ffd166> <gray>，这把成了就是 ×<white>"
                + next + "</white>。炸剑或放弃会断链。");
    }
}
