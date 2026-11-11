package com.forgemaster.enchant;

import com.forgemaster.ForgingMasterPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 原创附魔注册表（v2.23.2）。
 *
 * <p>15 条，全部 config 驱动：`enchant.defs.<id>` 下的 {@code enabled / max-level / per-level / weight}
 * 都能改，改了立刻生效（注册表在 load 时重建，不缓存静态常量）。</p>
 *
 * <h2>数值上限一览（为什么不会超标）</h2>
 * 每条附魔的 {@code per-level × max-level} 都刻意压得很低，而且**汇总时按加法并再过全局夹子**：
 *
 * <table border="1">
 *   <caption>最坏情况（全满级、全部叠满）</caption>
 *   <tr><th>类别</th><th>理论合计</th><th>{@code enchant.caps} 封顶</th><th>再叠一层</th></tr>
 *   <tr><td>伤害（锋锐+破甲+猎杀+斩首+连击满层）</td><td>+47.5%</td><td>+25%</td><td>—</td></tr>
 *   <tr><td>吸血加成</td><td>+6%</td><td>+10%</td><td>{@code combat.limits} 20%</td></tr>
 *   <tr><td>点燃时长</td><td>+1.8 秒</td><td>+4 秒</td><td>{@code combat.limits} 10 秒</td></tr>
 *   <tr><td>减伤加成（四件合计）</td><td>+30%</td><td>+10%</td><td>{@code combat.limits} 35%</td></tr>
 *   <tr><td>生命加成（四件合计）</td><td>+30</td><td>+8</td><td>{@code combat.limits} 20</td></tr>
 * </table>
 *
 * <p>注意伤害那一行：<b>理论上限 +47.5% 会被夹到 +25%</b>，
 * 也就是说「把 8 条武器附魔全堆满」也只能拿到 +25%，而不是叠出 +47.5%。
 * 这就是「加法汇总 + 只夹一次」的意义 —— 每条各自合法、加起来不越界。</p>
 */
public final class EnchantRegistry {

    private final ForgingMasterPlugin plugin;

    /** 插入顺序保持稳定：GUI 排序、Lore 排序都靠它 */
    private final Map<String, CustomEnchant> byId = new LinkedHashMap<>();

    public EnchantRegistry(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    /**
     * 内置 15 条的定义。**这是默认值，不是唯一来源** —— config 里同名键会覆盖。
     *
     * <p>放在代码里而不是只放 config，是因为：config 被服主删掉某一段时，
     * 从 config 重建注册表会让那些物品上的附魔「消失」（id 认不出来），
     * Lore 变成一串裸 id。代码里有兜底，至少显示名和数值仍然是对的。</p>
     */
    public static List<CustomEnchant> defaults() {
        return List.of(
                // ---- 武器 ----
                new CustomEnchant("edge", "锋锐", 5, CustomEnchant.Kind.DAMAGE_MUL,
                        CustomEnchant.Slot.WEAPON, 0.015, 100, "最终伤害 %v"),
                new CustomEnchant("pierce", "破甲", 5, CustomEnchant.Kind.ARMOR_PIERCE,
                        CustomEnchant.Slot.WEAPON, 0.012, 90, "无视护甲，伤害 %v"),
                new CustomEnchant("swift", "疾风", 4, CustomEnchant.Kind.ATTACK_SPEED,
                        CustomEnchant.Slot.WEAPON, 0.015, 80, "攻击速度 %v"),
                new CustomEnchant("soulsip", "噬魂", 4, CustomEnchant.Kind.LIFESTEAL_ADD,
                        CustomEnchant.Slot.WEAPON, 0.015, 70, "吸血 %v"),
                new CustomEnchant("ember", "燃烬印记", 3, CustomEnchant.Kind.FIRE_ADD,
                        CustomEnchant.Slot.WEAPON, 0.6, 70, "点燃 %v"),
                new CustomEnchant("slayer", "猎杀", 5, CustomEnchant.Kind.VS_MONSTER,
                        CustomEnchant.Slot.WEAPON, 0.02, 90, "对敌对生物伤害 %v"),
                new CustomEnchant("execute", "斩首", 5, CustomEnchant.Kind.EXECUTE,
                        CustomEnchant.Slot.WEAPON, 0.03, 45, "目标残血时伤害 %v"),
                new CustomEnchant("combo", "连击", 3, CustomEnchant.Kind.COMBO,
                        CustomEnchant.Slot.WEAPON, 0.01, 60, "连续命中每层 %v"),
                // ---- 护甲 ----
                new CustomEnchant("bulwark", "坚壁", 5, CustomEnchant.Kind.DAMAGE_REDUCE,
                        CustomEnchant.Slot.ARMOR, 0.015, 100, "受到伤害减免 %v"),
                new CustomEnchant("thorns", "荆棘", 4, CustomEnchant.Kind.THORNS,
                        CustomEnchant.Slot.ARMOR, 0.03, 60, "反弹 %v 伤害"),
                new CustomEnchant("fortitude", "韧骨", 5, CustomEnchant.Kind.HEALTH_ADD,
                        CustomEnchant.Slot.ARMOR, 1.5, 80, "生命上限 %v"),
                new CustomEnchant("emberward", "御火", 4, CustomEnchant.Kind.FIRE_RESIST,
                        CustomEnchant.Slot.ARMOR, 0.06, 55, "火焰伤害减免 %v"),
                // ---- 通用 ----
                new CustomEnchant("lorekeeper", "聚灵", 5, CustomEnchant.Kind.EXP_BONUS,
                        CustomEnchant.Slot.ANY, 0.08, 75, "经验获取 %v"),
                new CustomEnchant("prospector", "采掘", 5, CustomEnchant.Kind.DROP_BONUS,
                        CustomEnchant.Slot.ANY, 0.06, 65, "挖矿额外掉落 %v"),
                new CustomEnchant("fortunes", "匠运", 5, CustomEnchant.Kind.COIN_BONUS,
                        CustomEnchant.Slot.ANY, 0.02, 50, "剑币产出 %v")
        );
    }

    /** 从 config 重建。config 缺的键回落到 {@link #defaults()}。 */
    public void load() {
        byId.clear();
        FileConfiguration cfg = plugin.getConfig();
        for (CustomEnchant def : defaults()) {
            String path = "enchant.defs." + def.id();
            ConfigurationSection sec = cfg.getConfigurationSection(path);
            if (sec == null) {
                // config 没有这一段（老配置 / 服主删了）：用代码里的默认定义，别让物品失忆
                byId.put(def.id(), def);
                continue;
            }
            if (!sec.getBoolean("enabled", true)) {
                continue; // 明确关掉的才不注册
            }
            int max = Math.max(1, sec.getInt("max-level", def.maxLevel()));
            double per = sec.getDouble("per-level", def.perLevel());
            double weight = sec.getDouble("weight", def.weight());
            byId.put(def.id(), new CustomEnchant(def.id(), def.display(), max, def.kind(),
                    def.slot(), per, weight, def.desc()));
        }
    }

    public int size() {
        return byId.size();
    }

    public Collection<CustomEnchant> all() {
        return byId.values();
    }

    public CustomEnchant get(String id) {
        return id == null ? null : byId.get(id.toLowerCase(java.util.Locale.ROOT));
    }

    /** 适用于某个部位的候选（{@code ANY} 两边都算） */
    public List<CustomEnchant> forSlot(CustomEnchant.Slot slot) {
        List<CustomEnchant> out = new ArrayList<>();
        for (CustomEnchant e : byId.values()) {
            if (e.slot() == slot || e.slot() == CustomEnchant.Slot.ANY) {
                out.add(e);
            }
        }
        return out;
    }

    /** 随机抽一条（按 weight）。{@code slot} 为 null 时不限部位。 */
    public CustomEnchant random(java.util.Random rnd, CustomEnchant.Slot slot) {
        List<CustomEnchant> pool = slot == null ? new ArrayList<>(byId.values()) : forSlot(slot);
        if (pool.isEmpty()) {
            return null;
        }
        double total = 0;
        for (CustomEnchant e : pool) {
            total += Math.max(0, e.weight());
        }
        if (total <= 0) {
            // 全被服主把权重写成 0：退化成均匀抽取，别返回 null 让界面卡住
            return pool.get(rnd.nextInt(pool.size()));
        }
        double roll = rnd.nextDouble() * total;
        for (CustomEnchant e : pool) {
            roll -= Math.max(0, e.weight());
            if (roll <= 0) {
                return e;
            }
        }
        return pool.get(pool.size() - 1);
    }

    public CustomEnchant random(CustomEnchant.Slot slot) {
        return random(ThreadLocalRandom.current() == null
                ? new java.util.Random() : new java.util.Random(), slot);
    }
}