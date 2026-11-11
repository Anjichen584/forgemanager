package com.forgemaster.forge;

/**
 * 锻造阶段。
 *
 * <p>2.19.0 起在开刃与淬火之间插了两道「收尾工序」：
 * <ul>
 *   <li>{@link #POLISHING} 抛光：光泽指针往返扫动，在亮区中心下刀，共 N 刀</li>
 *   <li>{@link #INLAY} 嵌银丝：银丝节点逐个亮起，在亮窗内点中才算嵌上</li>
 * </ul>
 * 两道工序都可以在 config 里单独关掉，关掉后权重按比例让给其它工序，总分仍是 100。</p>
 */
public enum ForgePhase {
    HEATING,
    HAMMERING,
    FOLDING,
    GRINDING,
    POLISHING,
    INLAY,
    QUENCHING,
    RESULT
}