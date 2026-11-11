package com.forgemaster.evolve;

import com.forgemaster.config.Settings;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 锻阶配置表：从 config 的 {@code evolution.*} 段读全部阶段定义与全局参数。
 *
 * <p>整段没配置时落回 {@link EvolutionStage#defaults()}，保证开箱即玩；
 * 配置了就按配置走，缺哪个字段用内置默认补哪个。reload 时整体重读。</p>
 */
public final class EvolutionRegistry {

    private final Settings settings;
    private final Map<Integer, EvolutionStage> stages = new TreeMap<>();

    private boolean enabled;
    /** 品质加成：品质序数（ROUGH=0..MYTHIC=5）每档加多少成功率 */
    private double qualityBonusPerTier;
    /** 材料折扣：品质序数每档少吃多少比例的蜕变材料（0.05 = 神话打 75 折） */
    private double materialDiscountPerTier;
    /** 蜕变炉火保底：失败几次后必成（复用 Keys.FURNACE 计数） */
    private int pityAttempts;
    /** 这一阶起挂附魔光效 */
    private int glintStage;
    /** 这一阶起蜕变成功全服播报 */
    private int broadcastStage;

    public EvolutionRegistry(Settings settings) {
        this.settings = settings;
        load();
    }

    public void load() {
        enabled = settings.flag("evolution.enabled", true);
        qualityBonusPerTier = settings.num("evolution.quality-bonus-per-tier", 0.02);
        materialDiscountPerTier = Math.max(0, Math.min(0.5,
                settings.num("evolution.material-discount-per-tier", 0.05)));
        pityAttempts = Math.max(1, settings.intOf("evolution.pity-attempts", 3));
        glintStage = Math.max(0, settings.intOf("evolution.glint-stage", 3));
        broadcastStage = Math.max(0, settings.intOf("evolution.broadcast-stage", 5));

        stages.clear();
        ConfigurationSection root = settings.section("evolution.stages");
        if (root == null) {
            for (EvolutionStage s : EvolutionStage.defaults()) {
                stages.put(s.index(), s);
            }
            return;
        }
        for (String key : root.getKeys(false)) {
            int idx;
            try {
                idx = Integer.parseInt(key);
            } catch (NumberFormatException ex) {
                continue; // 阶段号必须是数字，别的键名直接忽略
            }
            ConfigurationSection cs = root.getConfigurationSection(key);
            if (cs == null) {
                continue;
            }
            EvolutionStage def = defOf(idx);
            stages.put(idx, new EvolutionStage(
                    idx,
                    cs.getInt("level", def.level()),
                    cs.getString("title", def.title()),
                    cs.getString("color", def.color()),
                    Math.max(1.0, cs.getDouble("bonus", def.bonus())),
                    Math.max(0, cs.getLong("cost-coins", def.costCoins())),
                    Math.max(1, cs.getInt("material-amount", def.materialAmount())),
                    Math.max(0.01, Math.min(1.0, cs.getDouble("success-base", def.successBase()))),
                    Math.max(0, cs.getInt("fail-drop", def.failDrop()))));
        }
        if (stages.isEmpty()) {
            // 配置段存在但一条都没解析出来：照旧给内置默认，别让功能悄悄死掉
            for (EvolutionStage s : EvolutionStage.defaults()) {
                stages.put(s.index(), s);
            }
        }
    }

    /** 第 idx 阶的内置默认（超出 defaults 长度时用最后一阶兜底改号） */
    private EvolutionStage defOf(int idx) {
        EvolutionStage[] defs = EvolutionStage.defaults();
        if (idx >= 1 && idx <= defs.length) {
            return defs[idx - 1];
        }
        EvolutionStage last = defs[defs.length - 1];
        return new EvolutionStage(idx, last.level() + 5 * (idx - defs.length),
                last.title(), last.color(), last.bonus(), last.costCoins(),
                last.materialAmount(), last.successBase(), last.failDrop());
    }

    public boolean enabled() {
        return enabled;
    }

    public double qualityBonusPerTier() {
        return qualityBonusPerTier;
    }

    public double materialDiscountPerTier() {
        return materialDiscountPerTier;
    }

    public int pityAttempts() {
        return pityAttempts;
    }

    public int glintStage() {
        return glintStage;
    }

    public int broadcastStage() {
        return broadcastStage;
    }

    /** 按阶段号取定义；没配置的号返回 null */
    public EvolutionStage stage(int index) {
        return stages.get(index);
    }

    /** 全部阶段，按阶段号升序 */
    public List<EvolutionStage> all() {
        return new ArrayList<>(stages.values());
    }

    public boolean isEmpty() {
        return stages.isEmpty();
    }

    /**
     * 累计属性乘区：1..stage 各阶 bonus 连乘；stage ≤ 0 返回 1.0。
     *
     * <p>乘区连乘而不是加法，是为了让「跨阶」的质感呈指数——
     * 每一阶都是一次质变，这也是 ForgeTax 那套升级手感的核心。</p>
     */
    public double cumulativeMult(int stage) {
        double mult = 1.0;
        for (int i = 1; i <= stage; i++) {
            EvolutionStage s = stages.get(i);
            if (s != null) {
                mult *= s.bonus();
            }
        }
        return mult;
    }

    /**
     * 强化等级对应的下一个未完成门槛：{@code stage 序号 = 当前阶段 + 1}，
     * 且当前强化等级已经够到它的 {@code level} 时返回它（强化被拦下，先蜕变）。
     *
     * <p>用 {@code >=} 而不是 {@code ==}：管理命令可能把等级直接顶过门槛，
     * 或蜕变失败掉级后重新爬上来，无论怎么走都别漏拦。</p>
     *
     * @param currentStage    已完成的阶段号（0 = 未蜕变）
     * @param strengthenLevel 当前强化等级
     */
    public EvolutionStage gateAt(int currentStage, int strengthenLevel) {
        EvolutionStage next = stages.get(currentStage + 1);
        return next != null && strengthenLevel >= next.level() ? next : null;
    }

    /** 当前阶段之后的下一个门槛（无论强化等级到没到）；没有返回 null */
    public EvolutionStage nextGate(int currentStage) {
        return stages.get(currentStage + 1);
    }
}
