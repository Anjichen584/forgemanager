package com.forgemaster.item;

import com.forgemaster.quality.Quality;

/**
 * 绚丽 Lore 的视觉层：按品质换一套完整主题——粗框装饰线、称号绶带、
 * 通栏渐变星级、评分条、评语。
 *
 * <p>这里只放「纯视觉」的行。所有被其它模块按纯文本前缀定位的契约行
 * （「耐久」「◆ 词条」「锻造者」「套装」「履历」「锻造等级」「崩刃」）
 * 仍归 {@link ItemFactory} 与各自的服务管，不要挪进来。</p>
 *
 * <p>设计语言（参考主流服的 RGB 渐变 Lore 排版）：粗 box-drawing 线
 * ━ 做外框、线中央镶大徽记、星级整行吃一条渐变而不是逐星上色、
 * 高品质用多色流彩（rainbow / 五段渐变），低品质刻意收着用灰，
 * 用「朴素衬托华丽」拉开档位感。</p>
 */
final class LoreArt {

    /** 工序条 / 评分条空段的颜色 */
    static final String BAR_EMPTY = "#3a3a3a";

    private LoreArt() {
    }

    /**
     * 顶部装饰框：粗线 ━ 夹中央大徽记。
     *
     * <p>ROUGH 用最细的 ┄ 和灰铁色；COMMON 银白；FINE 翠绿；EPIC 紫晶；
     * LEGENDARY 三段金焰渐变；MYTHIC 整行五色流彩（rainbow 逐字换色）。</p>
     */
    static String topRule(Quality q) {
        return switch (q) {
            case ROUGH -> "<gradient:#4f4f4f:#8d8d8d:#4f4f4f>▫ ┄┄┄┄┄┄┄┄┄┄ ◇ ┄┄┄┄┄┄┄┄┄┄ ▫</gradient>";
            case COMMON -> "<gradient:#9a9a9a:#ffffff:#9a9a9a>✧ ────────── ❖ ────────── ✧</gradient>";
            case FINE -> "<gradient:#1f8f4d:#7dffa8:#1f8f4d>✦ ━━━━━━━━━━ ❖ ━━━━━━━━━━ ✦</gradient>";
            case EPIC -> "<gradient:#6a0dad:#d9a6ff:#6a0dad>✦ ━━━━━━━━━━ ◈ ━━━━━━━━━━ ✦</gradient>";
            case LEGENDARY -> "<gradient:#ff5a1f:#ffd166:#fff3b0:#ffd166:#ff5a1f>"
                    + "✦ ━━━━━━━━━━ ✹ ━━━━━━━━━━ ✦</gradient>";
            case MYTHIC -> "<rainbow>✦ ━━━━━━━━━━ ✹ ━━━━━━━━━━ ✦</rainbow>";
        };
    }

    /** 底部装饰框：与顶部同款，渐变方向倒过来首尾呼应 */
    static String bottomRule(Quality q) {
        return switch (q) {
            case ROUGH -> "<gradient:#8d8d8d:#4f4f4f:#8d8d8d>▫ ┄┄┄┄┄┄┄┄┄┄ ◇ ┄┄┄┄┄┄┄┄┄┄ ▫</gradient>";
            case COMMON -> "<gradient:#ffffff:#9a9a9a:#ffffff>✧ ────────── ❖ ────────── ✧</gradient>";
            case FINE -> "<gradient:#7dffa8:#1f8f4d:#7dffa8>✦ ━━━━━━━━━━ ❖ ━━━━━━━━━━ ✦</gradient>";
            case EPIC -> "<gradient:#d9a6ff:#6a0dad:#d9a6ff>✦ ━━━━━━━━━━ ◈ ━━━━━━━━━━ ✦</gradient>";
            case LEGENDARY -> "<gradient:#fff3b0:#ffd166:#ff5a1f:#ffd166:#fff3b0>"
                    + "✦ ━━━━━━━━━━ ✹ ━━━━━━━━━━ ✦</gradient>";
            case MYTHIC -> "<rainbow>✦ ━━━━━━━━━━ ✹ ━━━━━━━━━━ ✦</rainbow>";
        };
    }

    /**
     * 称号绶带：品质称号用「」夹住、两侧缀菱形与星点，像勋章飘带。
     *
     * <p>COMMON 没有称号，降级成一串银色星尘分隔符，行数不塌。</p>
     */
    static String ribbon(Quality q) {
        if (q.title() == null || q.title().isEmpty()) {
            return "<dark_gray>·</dark_gray> ˖ <gradient:#e6e6e6:#ffffff>✦</gradient> ˖ <dark_gray>·</dark_gray>";
        }
        String[] chars = q.title().split("");
        StringBuilder spaced = new StringBuilder();
        for (int i = 0; i < chars.length; i++) {
            if (i > 0) {
                spaced.append(' ');
            }
            spaced.append(chars[i]);
        }
        String t = spaced.toString();
        return switch (q) {
            case ROUGH -> "<dark_gray>▫ ◇ 「 <gray>" + t + "</gray> 」 ◇ ▫</dark_gray>";
            case COMMON -> "<dark_gray>· ˖</dark_gray> 「 <white>" + t + "</white> 」 <dark_gray>˖ ·</dark_gray>";
            case FINE -> "<#1f8f4d>❖</#1f8f4d> <#7dffa8>「 " + t + " 」</#7dffa8> <#1f8f4d>❖</#1f8f4d>";
            case EPIC -> "<#d9a6ff>◆ 「 " + t + " 」 ◆</#d9a6ff>";
            case LEGENDARY -> "<gradient:#fff3b0:#ff7a3d>✦ 「 " + t + " 」 ✦</gradient>";
            case MYTHIC -> "<rainbow>✹ 「 " + t + " 」 ✹</rainbow>";
        };
    }

    /**
     * 稀有度星级：六星整行吃一条渐变，比逐星上色顺滑得多；
     * MYTHIC 全亮且走流彩。
     */
    static String stars(Quality q) {
        int filled = q.ordinal() + 1;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(i < filled ? "★" : "☆");
            if (i < 5) {
                sb.append(' ');
            }
        }
        if (q == Quality.MYTHIC) {
            return "<bold><rainbow>" + sb + "</rainbow></bold>";
        }
        String c = q.color();
        return "<bold><gradient:" + c + ":" + dim(c) + ":" + c + ">" + sb + "</gradient></bold>";
    }

    /**
     * 评分通栏：0-100 的渐变长条，分数越高条越满；
     * 填充段按品质渐变，空段暗灰收底。
     */
    static String scoreBar(Quality q, double score) {
        double ratio = Math.max(0.0, Math.min(1.0, score / 100.0));
        int solid = (int) Math.round(ratio * 20);
        StringBuilder fill = new StringBuilder();
        for (int i = 0; i < solid; i++) {
            fill.append('▰');
        }
        StringBuilder empty = new StringBuilder();
        for (int i = solid; i < 20; i++) {
            empty.append('▱');
        }
        String bar = fill.isEmpty() ? ""
                : (q == Quality.MYTHIC
                ? "<rainbow>" + fill + "</rainbow>"
                : "<gradient:" + q.color() + ":" + dim(q.color()) + ">" + fill + "</gradient>");
        return "<dark_gray>❮</dark_gray>" + bar + "<" + BAR_EMPTY + ">" + empty + "</" + BAR_EMPTY + ">"
                + "<dark_gray>❯</dark_gray>";
    }

    /** 品质评语，每档一句；高品质走流动渐变 */
    static String flavor(Quality q) {
        return switch (q) {
            case ROUGH -> "<dark_gray>火候未到，尚是凡铁</dark_gray>";
            case COMMON -> "<gray>一柄合格的剑，仅此而已</gray>";
            case FINE -> "<#7dffa8>炉火纯青，锋芒初露</#7dffa8>";
            case EPIC -> "<#d9a6ff>雷火淬体，寒光慑人</#d9a6ff>";
            case LEGENDARY -> "<gradient:#fff3b0:#ff7a3d:#fff3b0>百炼成钢，剑鸣如龙吟</gradient>";
            case MYTHIC -> "<rainbow>星辉淬刃，不朽之名镌于剑身</rainbow>";
        };
    }

    /**
     * 把 hex 色调暗一档：渐变两端用同色系深色，比「亮→亮」更有金属层次。
     * 非法输入原样返回，不让配色细节炸掉整把剑。
     */
    private static String dim(String hex) {
        try {
            int v = Integer.parseInt(hex.substring(1), 16);
            int r = (int) (((v >> 16) & 0xff) * 0.45);
            int g = (int) (((v >> 8) & 0xff) * 0.45);
            int b = (int) ((v & 0xff) * 0.45);
            return String.format("#%02x%02x%02x", r, g, b);
        } catch (RuntimeException ex) {
            return hex;
        }
    }
}
