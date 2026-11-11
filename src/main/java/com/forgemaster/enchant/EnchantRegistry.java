package com.forgemaster.enchant;

import com.forgemaster.ForgingMasterPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 原创附魔注册表（v2.23.3 重做）。
 *
 * <p>12 条，<b>每一条都是原版没有的机制</b>（对照表见 {@link CustomEnchant.Kind}）。
 * 全部 config 驱动：{@code enchant.defs.<id>} 下的 enabled / max-level / per-level / weight 可改。</p>
 *
 * <h2>为什么是 12 条而不是 15/20 条</h2>
 * 机制型附魔的编写成本不是「多写一个百分比」，而是「多一个能被破坏的系统」：
 * 溅射要防递归、卸力要防叠到无敌、替身要防刷命、刻痕要防内存泄漏。
 * 与其塞 20 条里一半是数值皮肤、一半半成品，不如 12 条全部落地可验证。
 * 服主想加，往 {@code enchant.defs} 里加就是了 —— 但新机制得先在代码里实现。
 */
public final class EnchantRegistry {

    private final ForgingMasterPlugin plugin;

    /** 插入顺序稳定：GUI 排序、Lore 排序都靠它 */
    private final Map<String, CustomEnchant> byId = new LinkedHashMap<>();

    public EnchantRegistry(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    /**
     * 内置 12 条。这是默认值，config 同名键会覆盖。
     *
     * <p>放在代码里而不是只放 config：config 被删掉某段时，从 config 重建会让
     * 那些物品上的附魔「消失」（id 认不出来），Lore 变成一串裸 id。
     * 代码里有兜底，至少显示名和参数仍然正确。</p>
     */
    public static List<CustomEnchant> defaults() {
        return List.of(
                // ---------------------------------------------------------- 武器：改变打法的
                new CustomEnchant("splinter", "崩流", 5, CustomEnchant.Kind.SPLASH,
                        CustomEnchant.Slot.WEAPON, 0.08, 100,
                        "刃气迸散，波及周围敌人 %v / 每级 ×8%"),
                new CustomEnchant("pyre", "烬狱", 3, CustomEnchant.Kind.PYRE,
                        CustomEnchant.Slot.WEAPON, 2.0, 70,
                        "击杀则尸身爆燃，周围 %v 点伤害"),
                new CustomEnchant("lone", "孤锋", 5, CustomEnchant.Kind.LONE_WOLF,
                        CustomEnchant.Slot.WEAPON, 0.03, 85,
                        "八格内无旁人时伤害 %v"),
                new CustomEnchant("charge", "蓄锋", 5, CustomEnchant.Kind.CHARGE,
                        CustomEnchant.Slot.WEAPON, 0.05, 80,
                        "停手两秒半后的第一击伤害 %v"),
                new CustomEnchant("sever", "夺魄", 5, CustomEnchant.Kind.HAMPER,
                        CustomEnchant.Slot.WEAPON, 0.4, 75,
                        "命中则滞其身，减速虚弱 %v"),
                new CustomEnchant("mark", "刻痕", 5, CustomEnchant.Kind.MARK,
                        CustomEnchant.Slot.WEAPON, 0.02, 60,
                        "对同一目标累计削其三成血后刻印，此后伤害 %v"),
                // ---------------------------------------------------------- 护甲：改变容错的
                new CustomEnchant("unload", "卸力", 5, CustomEnchant.Kind.DELAY_DAMAGE,
                        CustomEnchant.Slot.ARMOR, 0.10, 95,
                        "受伤的 %v 摊到两秒内，不当场致死"),
                new CustomEnchant("decoy", "替身", 3, CustomEnchant.Kind.CHEAT_DEATH,
                        CustomEnchant.Slot.ARMOR, 0.10, 45,
                        "致命伤时留一线生机，回气 %v（冷却 90 秒）"),
                new CustomEnchant("echo", "回响", 5, CustomEnchant.Kind.RETALIATE,
                        CustomEnchant.Slot.ARMOR, 0.03, 70,
                        "受击后三秒内对其伤害 %v"),
                new CustomEnchant("vigil", "守夜", 5, CustomEnchant.Kind.REGEN,
                        CustomEnchant.Slot.ARMOR, 0.5, 90,
                        "脱战五秒后每两秒回 %v"),
                // ---------------------------------------------------------- 通用：改变作息的
                new CustomEnchant("salvage", "拾遗", 5, CustomEnchant.Kind.SALVAGE,
                        CustomEnchant.Slot.ANY, 0.06, 75,
                        "采掘时 %v 几率另得经验"),
                new CustomEnchant("insight", "望气", 5, CustomEnchant.Kind.SENSE,
                        CustomEnchant.Slot.ANY, 2.0, 65,
                        "每数秒察知 %v 内敌踪")
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
                byId.put(def.id(), def);
                continue;
            }
            if (!sec.getBoolean("enabled", true)) {
                continue;
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
    public CustomEnchant random(Random rnd, CustomEnchant.Slot slot) {
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
}