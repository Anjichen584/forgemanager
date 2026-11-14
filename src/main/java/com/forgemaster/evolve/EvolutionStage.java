package com.forgemaster.evolve;

/**
 * 一个锻阶（蜕变阶段）的定义，全部字段来自 config 的 {@code evolution.stages.<n>.*}。
 *
 * <p>锻阶是挂在强化等级带上的门槛：强化到 {@link #level()} 级后必须先蜕变
 * 才能继续往上锻。蜕变成功后物品拿到阶段铭牌、独立属性乘区与光效；
 * 阶段值从 1 开始，0 = 未蜕变。</p>
 *
 * @param index          阶段号，1 开始
 * @param level          触发门槛的强化等级（如 +5 / +10 / +15 …）
 * @param title          阶段铭牌（精钢 / 寒钢 / 流星 …）
 * @param color          阶段主题色（MiniMessage hex，如 {@code #7fd3ff}）
 * @param bonus          本阶的独立属性乘区（1.25 = +25%），总乘区 = 各阶连乘
 * @param costCoins      蜕变手续费（剑币）
 * @param materialAmount 蜕变要吃的锻造材料个数（材料种类跟胚子的强化材料走）
 * @param successBase    该阶的明牌基础成功率（0-1），品质加成另算
 * @param failDrop       失败时强化等级最多掉几级（实际掉 1..failDrop 随机），0 = 不掉
 */
public record EvolutionStage(int index, int level, String title, String color,
                             double bonus, long costCoins, int materialAmount,
                             double successBase, int failDrop) {

    /** config 缺段时的内置默认：六阶门槛，覆盖到 tier1 cap 20 之上 */
    public static EvolutionStage[] defaults() {
        // v2.17.3 乘区重定：各阶 bonus 连乘 ≈ ×1.77（旧值连乘 ≈ ×5.0，
        // 叠上强化 +60% 后满配直接 +699%，太夸张）。改的是默认值，
        // 服主配置里写死的数字以配置为准。
        // v2.26.7：同步 step14 重定后的现行表（原 defaults 是 2.19.2 前的旧经济曲线，
        // evolution.stages 段缺失的盘会回退到旧值——第 6 阶期望 9.7 万币）。
        return new EvolutionStage[]{
                new EvolutionStage(1, 5, "精钢", "#7fd3ff", 1.06, 600, 8, 0.95, 1),
                new EvolutionStage(2, 10, "寒钢", "#4ee36b", 1.07, 1400, 16, 0.88, 1),
                new EvolutionStage(3, 15, "流星", "#b06bff", 1.09, 3000, 24, 0.78, 2),
                new EvolutionStage(4, 20, "传世", "#ffb84d", 1.10, 6000, 32, 0.66, 2),
                new EvolutionStage(5, 25, "不朽", "#ff3d6e", 1.12, 12000, 48, 0.52, 3),
                new EvolutionStage(6, 30, "造化", "#ffd166", 1.15, 24000, 64, 0.40, 3),
        };
    }
}
