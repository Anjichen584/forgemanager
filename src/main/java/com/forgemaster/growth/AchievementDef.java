package com.forgemaster.growth;

import org.bukkit.Material;

/**
 * 一条里程碑成就的定义（全部来自 YAML）。
 *
 * @param id        成就 id（存档里用的键）
 * @param name      显示名
 * @param desc      条件说明（界面里直接展示）
 * @param icon      界面图标
 * @param type      判定类型，见 {@link AchievementManager} 里的 matches()
 * @param param     判定参数（如品质名 FINE）
 * @param need      判定阈值
 * @param coins     奖励剑币
 * @param rep       奖励声望
 * @param points    成就点
 * @param broadcast 达成时是否全服播报
 */
public record AchievementDef(
        String id,
        String name,
        String desc,
        Material icon,
        String type,
        String param,
        double need,
        long coins,
        int rep,
        int points,
        boolean broadcast) {
}
