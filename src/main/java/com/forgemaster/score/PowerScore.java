package com.forgemaster.score;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.affix.Affix;
import com.forgemaster.gem.GemService;
import com.forgemaster.util.Keys;
import com.forgemaster.quality.Quality;
import com.forgemaster.util.MaterialLookup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 统一战力评分（路线图切片 5 · D）。
 *
 * <pre>
 * power = 基础(品质 × 材质) × 强化系数 × 蜕变连乘 × 词条系数 × 宝石系数 × 剑灵系数
 * </pre>
 *
 * <h2>为什么是「乘」而不是「加」</h2>
 * 加法会让「一把 +20 但词条稀烂的剑」和「一把 +2 但满词条的剑」同分，而玩家体感完全不是这样：
 * 强化到高段本身就是门槛（材料贵、碎剑风险），蜕变更是不可逆的质变。乘法能表达
 * 「越往上越贵」的复利感——但每一项的系数都钳在很小范围（见各方法注释），
 * 避免出现「一项爆表压死其它项」的畸形高分。
 *
 * <h2>为什么权重全部走配置</h2>
 * 路线图 §六 明确写了「权重需要真实数据校准，等切片 1-3 跑两周再定」。所以这里
 * **不给任何魔法数字**：每一项系数、每一个词条的贡献值、评级分界全部读
 * {@code power-score.*}，服主可以在真实成交数据上自己调，调完 {@code /zhujian reload} 立刻生效。
 * 代码里的默认值只是「能跑起来」的保守初值，不是结论。
 *
 * <h2>防刷</h2>
 * 只读物品自身的 PDC（材料 / 品质 / 强化等级 / 蜕变阶 / 词条 id / 宝石 / 灵性），
 * 全部由服务器写入，客户端伪造不了；且**不做任何跨物品累加**，所以
 * 「复制一堆剑堆分」这条路不存在——分数永远属于单件物品。
 */
public final class PowerScore {

    /** 分数上限：防极端配置把 long 撑爆，也防排行页显示成天文数字 */
    private static final long MAX_POWER = 9_999_999L;

    private PowerScore() {
    }

    /**
     * 一件物品的战力分。
     *
     * <p>非锻造品返回 0（不参与拍卖推荐与排行，也不该在名片上显示）。</p>
     */
    public static long of(ForgingMasterPlugin plugin, ItemStack item) {
        return Math.min(MAX_POWER, Math.max(0L, raw(plugin, item)));
    }

    /** 原始分（未钳制，供拆解展示用；对外一律用 {@link #of}） */
    private static long raw(ForgingMasterPlugin plugin, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return 0L;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return 0L;
        }
        var pdc = meta.getPersistentDataContainer();
        Byte forged = pdc.get(Keys.FORGED, PersistentDataType.BYTE);
        if (forged == null || forged != 1) {
            return 0L; // 不是锻造品：没有可比性，直接 0
        }
        Quality q = MaterialLookup.qualityOf(item);
        var mat = MaterialLookup.materialOf(plugin, item);
        // ① 基础分：品质 power()（粗糙 0.5 … 神话 5.0）× 材质档位加成
        double base = Math.max(0.1, q == null ? 1.0 : q.power());
        int tier = mat == null ? 1 : Math.max(1, mat.tier());
        base *= 1.0 + cfg(plugin, "power-score.material-per-tier", 0.12) * (tier - 1);
        base *= cfg(plugin, "power-score.base-multiplier", 100.0);

        // ② 强化系数：线性但带软上限，避免「+99 一把顶一万把」
        int level = levelOf(plugin, item);
        double strength = 1.0 + cfg(plugin, "power-score.strengthen-per-level", 0.055) * level;
        strength = Math.min(strength, cfg(plugin, "power-score.strengthen-cap", 8.0));

        // ③ 蜕变连乘：每阶乘一个系数（默认 1.35），阶数由配置决定，天然复利
        int stage = stageOf(plugin, item);
        double evolve = Math.pow(cfg(plugin, "power-score.evolve-per-stage", 1.35), Math.max(0, stage));

        // ④ 词条：按 id 查权重；未知 id 用 fallback（服主自定义词条也不至于 0 贡献）
        double affix = 1.0;
        for (String id : affixIds(meta)) {
            affix += cfg(plugin, "power-score.affix-weights." + id,
                    cfg(plugin, "power-score.affix-fallback", 0.06));
        }

        // ⑤ 宝石：按「类型权重 × 数值 × 品质权重」累加，再合成系数
        double gems = 1.0;
        var gs = plugin.gems();
        if (gs != null) {
            for (GemService.GemEntry e : gs.gemsOf(item)) {
                double per = cfg(plugin, "power-score.gem-per-point", 0.004);
                gems += per * Math.max(0, e.value())
                        * (1.0 + cfg(plugin, "power-score.gem-grade-weight", 0.15) * Math.max(0, e.grade().ordinal()));
            }
        }
        // 附魔孔里的附魔也算：等级越高越值钱（每级一个小系数）
        if (gs != null) {
            for (GemService.EnchantEntry e : gs.enchantsOf(item)) {
                gems += cfg(plugin, "power-score.enchant-per-level", 0.03)
                        * Math.max(0, e.level());
            }
        }

        // ⑥ 剑灵：按灵性饱满度（0~1）线性加成
        double spirit = 1.0;
        var sm = plugin.spirit();
        if (sm != null) {
            int cur = sm.spiritOf(item);
            int max = maxSpiritOf(meta);
            if (max > 0 && cur > 0) {
                double fill = Math.min(1.0, (double) cur / (double) max);
                spirit += cfg(plugin, "power-score.spirit-weight", 0.30) * fill;
            }
        }

        double total = base * strength * evolve * affix * gems * spirit;
        if (Double.isNaN(total) || Double.isInfinite(total)) {
            return 0L; // 配置写飞了（除以 0 / 溢出）不该把排行变成 NaN
        }
        return Math.round(total);
    }

    /**
     * 拆解展示：逐项列出「哪一项贡献了多少」。
     *
     * <p>这是战力分能被玩家接受的前提——一个不解释的黑盒分数没人会信。
     * 每行都是「项名 + 系数」，倍率而非绝对值，便于对照配置调参。</p>
     */
    public static List<String> breakdown(ForgingMasterPlugin plugin, ItemStack item) {
        List<String> out = new ArrayList<>();
        if (item == null || item.getType().isAir()) {
            return out;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return out;
        }
        Byte forged = meta.getPersistentDataContainer().get(Keys.FORGED, PersistentDataType.BYTE);
        if (forged == null || forged != 1) {
            out.add("<gray>这不是锻造品，没有战力分。</gray>");
            return out;
        }
        Quality q = MaterialLookup.qualityOf(item);
        var mat = MaterialLookup.materialOf(plugin, item);
        long power = of(plugin, item);
        out.add("<white>战力</white> <#ffd166>" + power + "</#ffd166> <gray>（" + gradeOf(plugin, power) + "）</gray>");
        out.add("<gray>品质 <white>" + (q == null ? "?" : q.display())
                + "</white> · 材质 <white>" + (mat == null ? "?" : mat.display())
                + "</white>（档 " + (mat == null ? "?" : String.valueOf(mat.tier())) + "）</gray>");
        out.add("<gray>强化 <white>+" + levelOf(plugin, item) + "</white>"
                + " · 蜕变 <white>" + stageOf(plugin, item) + " 阶</white>"
                + " · 词条 <white>" + affixIds(meta).size() + " 条</white></gray>");
        var gs = plugin.gems();
        int gemCount = gs == null ? 0 : gs.gemsOf(item).size();
        int enchCount = gs == null ? 0 : gs.enchantsOf(item).size();
        if (gemCount > 0 || enchCount > 0) {
            out.add("<gray>宝石 <white>" + gemCount + "</white> 颗 · 附魔 <white>" + enchCount + "</white> 条</gray>");
        }
        return out;
    }

    /**
     * 两把剑的对比拆解（v2.26.0）：{@code mine} 是自己的主手，{@code theirs} 是对方主手。
     *
     * <p>和 {@link #breakdown} 同一个数据源（PDC + 各服务读数），纯只读：
     * 不写 PDC、不落盘、不碰经济。任何一边不是锻造品时直接说明原因返回，
     * 不硬比 —— 和一把铁锭比分数没有意义。</p>
     *
     * @param otherName 对方名字（只用于展示）
     */
    public static List<String> compare(ForgingMasterPlugin plugin, ItemStack mine, String otherName, ItemStack theirs) {
        List<String> out = new ArrayList<>();
        if (mine == null || mine.getType().isAir()) {
            out.add("<red>你手上没有东西，先拿一把剑。</red>");
            return out;
        }
        if (theirs == null || theirs.getType().isAir()) {
            out.add("<red>" + otherName + " 手上没有东西，比不了。</red>");
            return out;
        }
        if (!isForged(mine)) {
            out.add("<red>你手上这件不是锻造品，没有战力分。</red>");
            return out;
        }
        if (!isForged(theirs)) {
            out.add("<red>" + otherName + " 手上那件不是锻造品，没有可比性。</red>");
            return out;
        }
        long pa = of(plugin, mine);
        long pb = of(plugin, theirs);
        Quality qa = MaterialLookup.qualityOf(mine);
        Quality qb = MaterialLookup.qualityOf(theirs);
        var ma = MaterialLookup.materialOf(plugin, mine);
        var mb = MaterialLookup.materialOf(plugin, theirs);
        out.add("<white>战力对比</white> <#ffd166>" + pa + "</#ffd166> <gray>vs</gray> <#ffd166>" + pb + "</#ffd166>");
        if (pa == pb) {
            out.add("<gray>势均力敌，分毫不差。</gray>");
        } else {
            long diff = Math.abs(pa - pb);
            String winner = pa > pb ? "你的" : otherName + " 的";
            out.add("<gray>" + winner + "更高，差 <white>" + diff + "</white> 分（"
                    + (diff * 100 / Math.max(1, Math.min(pa, pb))) + "%）</gray>");
        }
        out.add("<dark_gray>────</dark_gray> <white>你</white> <dark_gray>|</dark_gray> <white>" + otherName + "</white>");
        out.add(row("品质", qa == null ? "?" : qa.display(), qb == null ? "?" : qb.display()));
        out.add(row("材质", ma == null ? "?" : ma.display() + "（档 " + ma.tier() + "）",
                mb == null ? "?" : mb.display() + "（档 " + mb.tier() + "）"));
        out.add(row("强化", "+" + levelOf(plugin, mine), "+" + levelOf(plugin, theirs)));
        out.add(row("蜕变", stageOf(plugin, mine) + " 阶", stageOf(plugin, theirs) + " 阶"));
        out.add(row("词条", affixIds(mine.getItemMeta()).size() + " 条",
                affixIds(theirs.getItemMeta()).size() + " 条"));
        var gs = plugin.gems();
        int ga = gs == null ? 0 : gs.gemsOf(mine).size() + gs.enchantsOf(mine).size();
        int gb = gs == null ? 0 : gs.gemsOf(theirs).size() + gs.enchantsOf(theirs).size();
        out.add(row("宝石/附魔", ga + " 件", gb + " 件"));
        var sm = plugin.spirit();
        int sa = sm == null ? 0 : Math.max(0, sm.spiritOf(mine));
        int sb = sm == null ? 0 : Math.max(0, sm.spiritOf(theirs));
        out.add(row("灵性", String.valueOf(sa), String.valueOf(sb)));
        return out;
    }

    /** 对比小行：「标签 <white>a</white> <dark_gray>|</dark_gray> <white>b</white>」 */
    private static String row(String label, String a, String b) {
        return "<gray>" + label + ":</gray> <white>" + a + "</white> <dark_gray>|</dark_gray> <white>" + b + "</white>";
    }

    /** 是不是锻造品（PDC 由服务器写入，客户端伪造不了） */
    private static boolean isForged(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte forged = meta.getPersistentDataContainer().get(Keys.FORGED, PersistentDataType.BYTE);
        return forged != null && forged == 1;
    }

    /**
     * 评级：把裸分翻译成人话。
     *
     * <p>分界走配置（{@code power-score.grades.<名>.min}），默认五档，
     * 服主加档只要新增一段配置，不用改代码。</p>
     */
    public static String gradeOf(ForgingMasterPlugin plugin, long power) {
        var sec = plugin.settings().section("power-score.grades");
        String best = "凡品";
        long bestMin = Long.MIN_VALUE;
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                long min = plugin.settings().longOf("power-score.grades." + key + ".min", Long.MIN_VALUE);
                if (power >= min && min >= bestMin) {
                    bestMin = min;
                    best = plugin.settings().str("power-score.grades." + key + ".label", key);
                }
            }
        }
        return best;
    }

    /**
     * 拍卖/订单的推荐价：战力 × 单价系数，取整到「好听的数」。
     *
     * <p>不做市场博弈，只给一个「别离谱」的锚点：服主可以关掉
     * （{@code power-score.suggest-price: false}），也可以调
     * {@code power-score.price-per-power}。</p>
     */
    public static long suggestPrice(ForgingMasterPlugin plugin, ItemStack item) {
        if (!plugin.settings().flag("power-score.suggest-price", true)) {
            return -1L;
        }
        long power = of(plugin, item);
        if (power <= 0) {
            return -1L;
        }
        double unit = cfg(plugin, "power-score.price-per-power", 2.5);
        long raw = Math.round(power * unit);
        long min = (long) cfg(plugin, "power-score.price-min", 100.0);
        long target = Math.max(min, raw);
        // 取整到 10 的倍数：报价页面上 1237 看着像随机数，1240 像人定的
        return Math.max(10L, Math.round(target / 10.0) * 10L);
    }

    // ------------------------------------------------------------ 读数辅助

    private static int levelOf(ForgingMasterPlugin plugin, ItemStack item) {
        var s = plugin.strengthen();
        if (s == null) {
            return 0;
        }
        try {
            return Math.max(0, s.levelOf(item));
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static int stageOf(ForgingMasterPlugin plugin, ItemStack item) {
        var e = plugin.evolutions();
        if (e == null) {
            return 0;
        }
        try {
            return Math.max(0, e.stageOf(item));
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    /** 灵性上限：读 PDC；老物品没这个键时按当前灵性当满（即视为饱满，不吃加成也不扣分） */
    private static int maxSpiritOf(ItemMeta meta) {
        Integer max = meta.getPersistentDataContainer()
                .get(Keys.SPIRIT_MAX, PersistentDataType.INTEGER);
        return max == null ? 0 : Math.max(0, max);
    }

    /** 词条 id 列表（格式与 Reforge/Imprint 一致：逗号分隔） */
    private static List<String> affixIds(ItemMeta meta) {
        String csv = meta.getPersistentDataContainer()
                .get(Keys.AFFIX_LIST, PersistentDataType.STRING);
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String s : csv.split(",")) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** 配置读取的兜底包装：配置写坏（非数字）时用默认值，绝不让评分抛异常 */
    private static double cfg(ForgingMasterPlugin plugin, String path, double def) {
        try {
            double v = plugin.settings().num(path, def);
            return Double.isNaN(v) || Double.isInfinite(v) ? def : v;
        } catch (RuntimeException ex) {
            return def;
        }
    }
}