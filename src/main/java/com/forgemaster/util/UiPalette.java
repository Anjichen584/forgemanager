package com.forgemaster.util;

/**
 * UI 状态色（概率 / 进度类）。
 *
 * <p>和品质色分开管：品质色描述「物品档位」，统一走 {@code Quality.color()}；
 * 这里放的是描述「数值好坏程度」的连续量色阶。原先这套「概率越高越绿」的映射
 * 在 {@code StrengthenService.chanceColor} 与 {@code AnvilForgeManager.color}
 * 各有一份逐字相同的实现（audit 04-quality D1），合并到这里作为唯一出处。</p>
 */
public final class UiPalette {

    private UiPalette() {
    }

    /** 成功率越高越绿：≥85% 绿、≥60% 金、≥35% 橙、其余红 */
    public static String chanceColor(double chance) {
        if (chance >= 0.85) {
            return "#4ee36b";
        }
        if (chance >= 0.6) {
            return "#ffd166";
        }
        if (chance >= 0.35) {
            return "#ff9e4a";
        }
        return "#ff6b6b";
    }
}
