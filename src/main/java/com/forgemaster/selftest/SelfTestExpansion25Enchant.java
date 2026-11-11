package com.forgemaster.selftest;

import org.bukkit.configuration.file.YamlConfiguration;

/**
 * v2.25 拓展切片一（血怒 / 坚韧 附魔）的自检护栏。
 *
 * <p>写法沿用 v2.24 那套：<b>纯函数 / 临时 YamlConfiguration</b>，不伪造 Player、
 * 不造 Bukkit 物品。窗口判定（EnchantState.rageRemaining）与掷骰本身依赖
 * 时间与随机数，属真机验证范围；这里钉住的是**参数层**：窗口内外的取值口径、
 * 上限夹取、合并取大、默认表完整性 —— 这些错了线上行为必错，钉住它们就够。</p>
 */
public final class SelfTestExpansion25Enchant {

    private SelfTestExpansion25Enchant() {
    }

    private static void require(boolean cond, String msg) {
        if (!cond) {
            throw new IllegalStateException(msg);
        }
    }

    /** 由 {@link SelfTestSuite#register()} 统一挂接 */
    public static void register(SelfTestSuite suite) {
        String g = "v2.25 切片一 血怒/坚韧";

        // ---------------------------------------------------------------- 1. 血怒：窗口内/外取值
        suite.addExternal(g, "血怒：窗口外恒为 0，窗口内给足比例", () -> {
            var caps = com.forgemaster.enchant.EnchantEffects.Caps.defaults();
            var fx = new com.forgemaster.enchant.EnchantEffects();
            var m = bigRageStoutMechanics(0.09, 0);
            // 窗口外（没挨过打 / 窗口已过）：一个百分点的加成都不能漏出去
            require(fx.rageBonus(m, caps, false) == 0.0,
                    "血怒窗口外不该有任何加成，实际 " + fx.rageBonus(m, caps, false));
            // 窗口内：满级 4 × 0.03 = 0.12 原样放行
            require(Math.abs(fx.rageBonus(m, caps, true) - 0.09) < 1e-9,
                    "窗口内 3 级应是 +9%，实际 " + fx.rageBonus(m, caps, true));
            // 机制参数里没有血怒时恒为 0（没带血怒甲的玩家不该白拿增伤）
            require(fx.rageBonus(com.forgemaster.enchant.EnchantEffects.Mechanics.none(),
                    caps, true) == 0.0, "无血怒词条不该有窗口加成");
            // 负值脏值不给加成
            require(fx.rageBonus(bigRageStoutMechanics(-0.5, 0), caps, true) == 0.0,
                    "负的血怒参数不该给出正加成");
        });

        // ---------------------------------------------------------------- 2. 血怒：上限夹取
        suite.addExternal(g, "血怒：加成封顶在 max-rage（默认 0.12），config 可覆盖", () -> {
            var caps = com.forgemaster.enchant.EnchantEffects.Caps.defaults();
            var fx = new com.forgemaster.enchant.EnchantEffects();
            require(caps.maxRage() == 0.12, "血怒默认上限应是 0.12，实际 " + caps.maxRage());
            // 配置被写成离谱大数：参数 0.9 必须夹回 0.12
            var m = bigRageStoutMechanics(0.9, 0);
            require(fx.rageBonus(m, caps, true) == caps.maxRage(),
                    "血怒不该越过 max-rage（0.12），实际 " + fx.rageBonus(m, caps, true));
            // 配置接线：enchant.caps.max-rage 覆盖默认（临时 YamlConfiguration，不碰真实文件）
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("enchant.caps.max-rage", 0.20);
            cfg.set("enchant.caps.rage-window-ms", 6000L);
            var overridden = com.forgemaster.enchant.EnchantEffects.Caps.fromConfig(cfg);
            require(overridden.maxRage() == 0.20, "config 覆盖的 max-rage 没生效");
            require(overridden.rageWindowMs() == 6000L, "config 覆盖的 rage-window-ms 没生效");
            require(fx.rageBonus(m, overridden, true) == 0.20,
                    "覆盖后的夹子应放行到 0.20");
            // 无 config 时回落默认
            YamlConfiguration empty = new YamlConfiguration();
            var back = com.forgemaster.enchant.EnchantEffects.Caps.fromConfig(empty);
            require(back.maxRage() == 0.12 && back.rageWindowMs() == 4000L,
                    "无 config 时血怒上限/窗口应回落默认");
        });

        // ---------------------------------------------------------------- 3. 坚韧：上限夹取
        suite.addExternal(g, "坚韧：免磨损几率封顶在 max-stout-chance（默认 0.40），config 可覆盖", () -> {
            var caps = com.forgemaster.enchant.EnchantEffects.Caps.defaults();
            var fx = new com.forgemaster.enchant.EnchantEffects();
            require(caps.maxStoutChance() == 0.40,
                    "坚韧默认上限应是 0.40，实际 " + caps.maxStoutChance());
            // 离谱值必须夹回；几率超 40% 就等于接近不磨损，经济红线失守
            var m = bigRageStoutMechanics(0, 0.9);
            require(fx.stoutSkipChance(m, caps) == caps.maxStoutChance(),
                    "坚韧不该越过 max-stout-chance（0.40），实际 " + fx.stoutSkipChance(m, caps));
            // 正常档：满级 5 × 0.08 = 0.40，恰好压线放行
            require(Math.abs(fx.stoutSkipChance(bigRageStoutMechanics(0, 0.40), caps) - 0.40) < 1e-9,
                    "满级 40% 应原样放行");
            require(Math.abs(fx.stoutSkipChance(bigRageStoutMechanics(0, 0.24), caps) - 0.24) < 1e-9,
                    "3 级 24% 不该被夹");
            // 0 / 负值 / 无机制恒 0
            require(fx.stoutSkipChance(bigRageStoutMechanics(0, -0.1), caps) == 0.0,
                    "负的坚韧几率应归零");
            require(fx.stoutSkipChance(com.forgemaster.enchant.EnchantEffects.Mechanics.none(),
                    caps) == 0.0, "无坚韧词条不该免磨损");
            // 配置接线
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("enchant.caps.max-stout-chance", 0.5);
            require(com.forgemaster.enchant.EnchantEffects.Caps.fromConfig(cfg)
                            .maxStoutChance() == 0.5,
                    "config 覆盖的 max-stout-chance 没生效");
        });

        // ---------------------------------------------------------------- 4. Mechanics merge：新字段取大不叠加
        suite.addExternal(g, "合并：血怒/坚韧跨件取大不叠加，且合并后再夹一次", () -> {
            var caps = com.forgemaster.enchant.EnchantEffects.Caps.defaults();
            var fx = new com.forgemaster.enchant.EnchantEffects();
            // 胸甲带 3 级血怒(9%) + 2 级坚韧(16%)，护腿带 4 级血怒(12%) + 3 级坚韧(24%)
            var chest = bigRageStoutMechanics(0.09, 0.16);
            var legs = bigRageStoutMechanics(0.12, 0.24);
            var merged = fx.merge(java.util.List.of(chest, legs), caps);
            // 取大：9% 与 12% 取 12%，不是 21%；16% 与 24% 取 24%，不是 40%
            require(Math.abs(merged.rage() - 0.12) < 1e-9,
                    "血怒合并应取大（0.12），实际 " + merged.rage());
            require(Math.abs(merged.stoutChance() - 0.24) < 1e-9,
                    "坚韧合并应取大（0.24），实际 " + merged.stoutChance());
            // 两件都写成离谱值：合并后的夹子必须兜住
            var crazy = fx.merge(java.util.List.of(
                    bigRageStoutMechanics(0.9, 0.9), bigRageStoutMechanics(0.8, 0.8)), caps);
            require(crazy.rage() <= caps.maxRage(),
                    "合并后血怒必须再夹一次，实际 " + crazy.rage());
            require(crazy.stoutChance() <= caps.maxStoutChance(),
                    "合并后坚韧必须再夹一次，实际 " + crazy.stoutChance());
        });

        // ---------------------------------------------------------------- 5. 默认表完整性
        suite.addExternal(g, "默认表：rage/stout 已注册且参数齐全", () -> {
            var defs = com.forgemaster.enchant.EnchantRegistry.defaults();
            var rage = defs.stream().filter(d -> d.id().equals("rage")).findFirst().orElse(null);
            require(rage != null, "rage 没进默认附魔表");
            require(rage.kind() == com.forgemaster.enchant.CustomEnchant.Kind.RAGE,
                    "rage 机制应是 RAGE");
            require(rage.maxLevel() == 4 && rage.slot()
                            == com.forgemaster.enchant.CustomEnchant.Slot.ARMOR,
                    "rage 应是护甲 4 级");
            require(Math.abs(rage.at(4) - 0.12) < 1e-9,
                    "rage 满级应是 +12%，实际 " + rage.at(4));
            var stout = defs.stream().filter(d -> d.id().equals("stout")).findFirst().orElse(null);
            require(stout != null, "stout 没进默认附魔表");
            require(stout.kind() == com.forgemaster.enchant.CustomEnchant.Kind.STOUT,
                    "stout 机制应是 STOUT");
            require(stout.maxLevel() == 5 && stout.slot()
                            == com.forgemaster.enchant.CustomEnchant.Slot.ANY,
                    "stout 应是通用 5 级");
            require(Math.abs(stout.at(5) - 0.40) < 1e-9,
                    "stout 满级应是 40%，实际 " + stout.at(5));
            // Lore 数值文案认得出新机制（switch 漏分支会直接编译失败，这里是行为兜底）
            require(rage.valueText(4).contains("12"), "rage 的 Lore 文案应含 12");
            require(stout.valueText(5).contains("40"), "stout 的 Lore 文案应含 40");
        });

        // ---------------------------------------------------------------- 6. of()：单件夹取入口认得新 Kind
        suite.addExternal(g, "of()：RAGE/STOUT 条目按 perLevel 折算并夹上限", () -> {
            var caps = com.forgemaster.enchant.EnchantEffects.Caps.defaults();
            var fx = new com.forgemaster.enchant.EnchantEffects();
            var rageDef = com.forgemaster.enchant.EnchantRegistry.defaults().stream()
                    .filter(d -> d.id().equals("rage")).findFirst().orElseThrow();
            var stoutDef = com.forgemaster.enchant.EnchantRegistry.defaults().stream()
                    .filter(d -> d.id().equals("stout")).findFirst().orElseThrow();
            // 超级离谱的 per-level 配置也要被 of() 的单件夹子按住
            var rageCrazy = new com.forgemaster.enchant.CustomEnchant(
                    "rage", "血怒", 4, com.forgemaster.enchant.CustomEnchant.Kind.RAGE,
                    com.forgemaster.enchant.CustomEnchant.Slot.ARMOR, 9.0, 1, "x");
            var m1 = fx.of(java.util.List.of(
                    new com.forgemaster.enchant.EnchantEffects.Entry(rageCrazy, 4)), caps);
            require(m1.rage() == caps.maxRage(),
                    "of() 应把离谱血怒夹回 0.12，实际 " + m1.rage());
            var stoutCrazy = new com.forgemaster.enchant.CustomEnchant(
                    "stout", "坚韧", 5, com.forgemaster.enchant.CustomEnchant.Kind.STOUT,
                    com.forgemaster.enchant.CustomEnchant.Slot.ANY, 9.0, 1, "x");
            var m2 = fx.of(java.util.List.of(
                    new com.forgemaster.enchant.EnchantEffects.Entry(stoutCrazy, 5)), caps);
            require(m2.stoutChance() == caps.maxStoutChance(),
                    "of() 应把离谱坚韧夹回 0.40，实际 " + m2.stoutChance());
            // 正常档：2 级血怒 = 6%，1 级坚韧 = 8%
            var m3 = fx.of(java.util.List.of(
                    new com.forgemaster.enchant.EnchantEffects.Entry(rageDef, 2),
                    new com.forgemaster.enchant.EnchantEffects.Entry(stoutDef, 1)), caps);
            require(Math.abs(m3.rage() - 0.06) < 1e-9, "2 级血怒应为 6%，实际 " + m3.rage());
            require(Math.abs(m3.stoutChance() - 0.08) < 1e-9, "1 级坚韧应为 8%，实际 " + m3.stoutChance());
        });
    }

    /** 造一个只有血怒/坚韧参数的 Mechanics（绕过私有 with*，直接用公开 record 构造） */
    private static com.forgemaster.enchant.EnchantEffects.Mechanics bigRageStoutMechanics(
            double rage, double stoutChance) {
        return new com.forgemaster.enchant.EnchantEffects.Mechanics(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, rage, stoutChance);
    }
}
