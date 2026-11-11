package com.forgemaster.enchant;

/**
 * 一条原创附魔的定义（v2.23.3 重做）。
 *
 * <h2>设计红线：不做原版的数值皮肤</h2>
 * v2.23.2 的第一版被否掉了，原因值得写在这里：那 15 条全是「把原版附魔换个名字 + 调个百分比」——
 * 断金=锋利、燎原=火焰附加、芒刺=荆棘、辟火=火焰保护、探脉=时运、通慧=经验修补。
 * 玩家一眼就看穿是换皮，因为<b>机制完全一样，只是数字不同</b>。
 *
 * <p>所以这一版的判据不是「数值调多少」，而是：</p>
 * <ol>
 *   <li><b>原版有没有这个机制？</b>有的一律不做（增伤、减伤、吸血、点燃、反弹、时运、经验加成全部剔除）。</li>
 *   <li><b>它改变的是打法，还是只是数字？</b>只改数字的不做。
 *       改打法的才留：溅射改变站位、蓄势改变出手节奏、刻痕改变持久战、替身改变送死成本。</li>
 *   <li><b>能不能用一句话说清「你应该因此改变什么行为」？</b>说不清就是数值皮肤。</li>
 * </ol>
 *
 * <h2>数值纪律（沿用）</h2>
 * 每条附魔只声明「每级参数」，最终效果由 {@link EnchantEffects} 汇总后再过全局夹子。
 * 新机制虽然是「新」，但只要是伤害来源，就一样要被 {@code combat.limits} 管住 ——
 * 新机制不等于免检通道。
 *
 * @param id       稳定 id（存盘用）
 * @param display  显示名
 * @param maxLevel 最高等级
 * @param kind     机制
 * @param slot     适用部位
 * @param perLevel 每级参数（含义由 {@link Kind} 决定）
 * @param weight   随机产出权重
 * @param desc     说明（{@code %v} 会被换成当前等级数值）
 */
public record CustomEnchant(String id, String display, int maxLevel, Kind kind, Slot slot,
                            double perLevel, double weight, String desc) {

    /**
     * 机制类型。**每一项都是原版没有的**。
     *
     * <p>逐条对照原版附魔，确认不重复：</p>
     * <table border="1">
     *   <caption>与原版机制的区别</caption>
     *   <tr><th>本插件</th><th>原版最接近的</th><th>差别在哪</th></tr>
     *   <tr><td>崩流（溅射）</td><td>横扫之刃</td><td>横扫只对「正面扇形 + 非玩家生物」且伤害固定；
     *       崩流是真正的圆形溅射，且溅射伤害独立结算、不触发附魔</td></tr>
     *   <tr><td>烬狱（击杀爆燃）</td><td>无</td><td>原版没有任何「击杀触发范围伤害」的机制</td></tr>
     *   <tr><td>孤锋（独斗）</td><td>无</td><td>原版伤害与「周围有没有队友」无关</td></tr>
     *   <tr><td>蓄锋（蓄势）</td><td>无</td><td>原版没有「出手间隔」概念，蓄势奖励的是「不乱挥」</td></tr>
     *   <tr><td>夺魄（打断）</td><td>无</td><td>原版没有打断施法/减速攻击者的机制</td></tr>
     *   <tr><td>刻痕（累计刻印）</td><td>无</td><td>原版伤害不随「对同一目标累计输出」成长</td></tr>
     *   <tr><td>卸力（伤害延后）</td><td>无</td><td>原版减伤是即时结算，卸力把伤害摊到 2 秒里</td></tr>
     *   <tr><td>替身（免死）</td><td>不死图腾</td><td>图腾是消耗品；替身是附魔，靠冷却而非消耗</td></tr>
     *   <tr><td>回响（受击反击）</td><td>无</td><td>原版没有「被谁打就对谁更痛」的窗口机制</td></tr>
     *   <tr><td>守夜（脱战回复）</td><td>无</td><td>原版回血靠饱食度，与战斗状态无关</td></tr>
     *   <tr><td>拾遗（额外经验）</td><td>经验修补</td><td>经验修补是「经验修耐久」，拾遗是「挖矿多给经验」</td></tr>
     *   <tr><td>望气（侦测）</td><td>无</td><td>原版没有主动侦测附近敌人的机制</td></tr>
     *   <tr><td>淬锋（连击锋锐）</td><td>无</td><td>原版没有「连续命中叠层、受伤即清」的机制 ——
     *       它奖励的是「不挨打地输出」，与无伤跑位打法绑定，受伤清零让白嫖不可能</td></tr>
     *   <tr><td>饮命（击杀回耐久）</td><td>经验修补</td><td>经验修补是「经验换原版耐久」；饮命是「击杀
     *       直接回虚拟耐久」，且崩刃的剑不回 —— 死剑不能靠杀怪自愈，研磨仍是唯一出路</td></tr>
     *   <tr><td>血怒（受击增伤窗）</td><td>无</td><td>原版没有「被打之后一段时间内打人更痛」的窗口；
     *       它与淬锋构成一对镜像：淬锋怕挨打，血怒吃挨打</td></tr>
     *   <tr><td>坚韧（耐久掷骰豁免）</td><td>耐久</td><td>耐久 III 只是降低期望磨损，照样必掉；
     *       坚韧是整次豁免的掷骰，且只管插件虚拟耐久那本账</td></tr>
     * </table>
     */
    public enum Kind {
        /** 溅射：命中时对目标周围的敌人造成独立伤害 */
        SPLASH,
        /** 击杀爆燃：击杀目标时尸身爆燃，对周围造成伤害 */
        PYRE,
        /** 独斗：周围无其他玩家时增伤 */
        LONE_WOLF,
        /** 蓄势：距上次命中够久，本击增伤 */
        CHARGE,
        /** 打断：命中时使目标减速 + 虚弱 */
        HAMPER,
        /** 刻痕：对同一目标累计输出达标后刻印，此后对其增伤 */
        MARK,
        /** 卸力：受到伤害的一部分延后分期结算 */
        DELAY_DAMAGE,
        /** 替身：致命伤时免死一次（有冷却） */
        CHEAT_DEATH,
        /** 回响：受击后一段时间内对攻击者增伤 */
        RETALIATE,
        /** 守夜：脱战后周期性回复 */
        REGEN,
        /** 拾遗：挖掘方块时额外获得经验 */
        SALVAGE,
        /** 望气：周期性提示最近敌对生物的方向与距离 */
        SENSE,
        /**
         * 淬锋（v2.24 切片一）：连续命中且不受伤时叠「锋锐」层，每层增伤；
         * 持剑者受到任何伤害立即清零。perLevel = 每层增伤比例。
         * 与原版最近似的只有「锋利」这种常驻数值——但淬锋是条件叠层 + 一受击归零，
         * 改变的是「能不能浪」：站撸挨一刀就全没。
         */
        STREAK,
        /**
         * 饮命（v2.24 切片一）：击杀时按等级回复武器虚拟耐久若干点。
         * perLevel = 每级回复点数。**崩刃（耐久 ≤ 0）的剑不回**——
         * 否则死了还能喝血复活耐久，「崩刃必须研磨」的红线就被绕过去了。
         */
        DUR_LEECH,
        /**
         * 血怒（v2.25 切片一）：受伤后窗口内自己的攻击增伤。perLevel = 每级增伤比例。
         * 与淬锋互补：淬锋奖励「不挨打地输出」，血怒奖励「敢挨打的换血打法」——
         * 原版没有任何「被打反而变强」的临时窗口机制。
         */
        RAGE,
        /**
         * 坚韧（v2.25 切片一）：每次虚拟耐久要扣账时掷骰，命中则这次不掉。
         * perLevel = 每级免磨损几率。与原版「耐久」附魔的区别：耐久 III 是降低
         * 掉耐久的期望值（还是必掉），坚韧是概率性整次豁免，且只护**插件虚拟
         * 耐久账**——原版本体耐久本来就 unbreakable 封死，没有可保护的第二本账。
         */
        STOUT
    }

    /** 适用部位 */
    public enum Slot {
        WEAPON("武器"),
        ARMOR("装备"),
        ANY("通用");

        private final String label;

        Slot(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 某一级实际参数（唯一入口，别在别处再乘一次 perLevel） */
    public double at(int level) {
        int lv = Math.max(0, Math.min(level, maxLevel));
        return perLevel * lv;
    }

    /** 这条附魔在某一级的可读数值（Lore 用） */
    public String valueText(int level) {
        double v = at(level);
        return switch (kind) {
            case SPLASH, LONE_WOLF, CHARGE, MARK, RETALIATE, DELAY_DAMAGE, SALVAGE, STREAK, RAGE ->
                    "+" + trim(v * 100) + "%";
            case PYRE -> trim(v) + " 点";
            case HAMPER -> trim(v) + " 秒";
            case REGEN -> trim(v) + " 点/次";
            case CHEAT_DEATH -> "+" + trim(v * 100) + "% 生命";
            case SENSE -> trim(v) + " 格";
            case DUR_LEECH -> trim(v) + " 点";
            case STOUT -> "+" + trim(v * 100) + "% 几率";
        };
    }

    private static String trim(double v) {
        if (Math.abs(v - Math.round(v)) < 1e-9) {
            return String.valueOf(Math.round(v));
        }
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}