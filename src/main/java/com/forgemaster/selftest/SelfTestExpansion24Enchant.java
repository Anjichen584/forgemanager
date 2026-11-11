package com.forgemaster.selftest;

import org.bukkit.configuration.file.YamlConfiguration;

/**
 * v2.24 拓展切片一（附魔 / 词条新机制）的自检护栏。
 *
 * <p>全部是<b>纯函数 / 纯数值层面</b>的断言：不伪造 Player、不造 Bukkit 物品 ——
 * 机制参数的上限夹取（淬锋层封顶 / 加成夹取、饮命点数夹取、处决比例夹取、
 * 冰噬 tick 夹取）都在 {@code EnchantEffects} 与 {@code DamageBudget.Caps}
 * 的纯函数层完成，这里钉住它们就等于钉住了线上行为；
 * 配置接线用临时 {@link YamlConfiguration} 往返，不碰真实 config.yml。</p>
 *
 * <p>要伪造 Player 才能测的部分（受击清零、击杀回耐久的落账）留给真机验证 ——
 * 为一次断言造 Player 桩的维护成本远高于收益。</p>
 */
public final class SelfTestExpansion24Enchant {

    private SelfTestExpansion24Enchant() {
    }

    private static void require(boolean cond, String msg) {
        if (!cond) {
            throw new IllegalStateException(msg);
        }
    }

    /** 由 {@link SelfTestSuite#register()} 统一挂接 */
    public static void register(SelfTestSuite suite) {
        String g = "v2.24 切片一 附魔/词条";

        // ---------------------------------------------------------------- 1. 淬锋：层封顶
        suite.addExternal(g, "淬锋：连击层数封顶在 max-streak-stacks", () -> {
            // 逐次命中推进 10 次，层数必须停在 5（默认 max-streak-stacks = 5）
            int stacks = 0;
            for (int i = 0; i < 10; i++) {
                stacks = com.forgemaster.enchant.EnchantEffects.nextStreakStack(stacks, 5);
            }
            require(stacks == 5, "连打 10 次层数应该停在 5，实际 " + stacks);
            // 配置写 0 / 负数也不能把层数钳成 0 层或负层（Math.max(1, ...) 兜底）
            int one = 0;
            for (int i = 0; i < 10; i++) {
                one = com.forgemaster.enchant.EnchantEffects.nextStreakStack(one, 0);
            }
            require(one == 1, "maxStacks=0 时应退化成 1 层，实际 " + one);
            // 受击清零后从头叠（清零动作本身需要 Player，真机验证；这里钉推进函数的边界）
            require(com.forgemaster.enchant.EnchantEffects.nextStreakStack(-3, 5) == 1,
                    "负数脏值应归到 1 层");
        });

        // ---------------------------------------------------------------- 2. 淬锋：加成夹取
        suite.addExternal(g, "淬锋：加成封顶在 max-streak-bonus，且层数超限不穿透", () -> {
            var caps = com.forgemaster.enchant.EnchantEffects.Caps.defaults();
            var fx = new com.forgemaster.enchant.EnchantEffects();
            // 单层 0.5（远超默认单层 0.04）：9 层 × 0.5 = 4.5，必须被夹在 0.20
            var m = bigStreakMechanics(0.5);
            require(fx.streakBonus(m, caps, 9) == caps.maxStreakBonus(),
                    "层数堆上天也不该越过 max-streak-bonus（0.20），实际 "
                            + fx.streakBonus(m, caps, 9));
            // 正常档：满 5 层 × 0.04 = 0.20，恰好压线
            var normal = bigStreakMechanics(0.04);
            require(Math.abs(fx.streakBonus(normal, caps, 5) - 0.20) < 1e-9,
                    "满 5 层应是 +20%，实际 " + fx.streakBonus(normal, caps, 5));
            require(Math.abs(fx.streakBonus(normal, caps, 2) - 0.08) < 1e-9,
                    "2 层应是 +8%，实际 " + fx.streakBonus(normal, caps, 2));
            // 零层 / 负层 = 没有加成
            require(fx.streakBonus(normal, caps, 0) == 0.0, "0 层不该有加成");
            // 机制参数里没有淬锋时恒为 0（没带淬锋剑的玩家不该白拿增伤）
            require(fx.streakBonus(com.forgemaster.enchant.EnchantEffects.Mechanics.none(),
                    caps, 9) == 0.0, "无淬锋词条不该有加成");
        });

        // ---------------------------------------------------------------- 3. 饮命：上限夹取
        suite.addExternal(g, "饮命：击杀回耐久封顶在 max-dur-leech", () -> {
            var caps = com.forgemaster.enchant.EnchantEffects.Caps.defaults();
            var fx = new com.forgemaster.enchant.EnchantEffects();
            // 机制参数被配成 10 点（离谱值）→ 必须夹回 3.0
            var m = bigDurLeechMechanics(10.0);
            require(fx.durLeechGain(m, caps) == caps.maxDurLeech(),
                    "饮命不该越过 max-dur-leech（3.0），实际 " + fx.durLeechGain(m, caps));
            // 正常档：5 级 × 0.5 = 2.5 点，在 3.0 之内原样放行
            var normal = bigDurLeechMechanics(2.5);
            require(fx.durLeechGain(normal, caps) == 2.5,
                    "2.5 点不该被夹，实际 " + fx.durLeechGain(normal, caps));
            // 0 / 负值 = 不回（以及无机制恒 0）
            require(fx.durLeechGain(bigDurLeechMechanics(-1.0), caps) == 0.0,
                    "负的饮命值不该给出正点数");
            require(fx.durLeechGain(com.forgemaster.enchant.EnchantEffects.Mechanics.none(),
                    caps) == 0.0, "无饮命词条不该回耐久");
        });

        // ---------------------------------------------------------------- 4. 处决：上限夹取 + 配置接线
        suite.addExternal(g, "处决：增伤封顶 max-execute，config 可覆盖", () -> {
            var caps = com.forgemaster.combat.DamageBudget.Caps.defaults();
            require(caps.maxExecute() == 0.30, "处决默认上限应是 0.30，实际 " + caps.maxExecute());
            require(caps.clampExecute(0.9) == 0.30,
                    "0.9 应被夹回 0.30，实际 " + caps.clampExecute(0.9));
            require(caps.clampExecute(0.18) == 0.18, "合规值 0.18 不该被动");
            require(caps.clampExecute(-0.1) == 0.0, "负值应归零");
            // 配置接线：combat.caps.max-execute 覆盖默认（临时 YamlConfiguration，不碰真实文件）
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("combat.caps.max-execute", 0.5);
            require(com.forgemaster.combat.DamageBudget.Caps.fromConfig(cfg)
                            .clampExecute(0.9) == 0.5,
                    "config 覆盖的 max-execute 没生效");
            // 越限写入（0.9）落到 0.30 —— 写入即封顶，Lore 数与实战数一致
            YamlConfiguration empty = new YamlConfiguration();
            require(com.forgemaster.combat.DamageBudget.Caps.fromConfig(empty)
                            .clampExecute(0.9) == 0.30,
                    "无 config 时处决上限回落默认 0.30");
        });

        // ---------------------------------------------------------------- 5. 冰噬：tick 上限夹取
        suite.addExternal(g, "冰噬：减速 tick 封顶 max-chill-ticks，config 可覆盖", () -> {
            var caps = com.forgemaster.combat.DamageBudget.Caps.defaults();
            require(caps.maxChillTicks() == 40, "冰噬默认上限应是 40 tick（2 秒），实际 "
                    + caps.maxChillTicks());
            require(caps.clampChillTicks(200) == 40,
                    "10 秒减速应被夹回 2 秒，实际 " + caps.clampChillTicks(200));
            require(caps.clampChillTicks(20) == 20, "合规 1 秒不该被动");
            require(caps.clampChillTicks(-5) == 0, "负 tick 应归零");
            // 配置接线：combat.caps.max-chill-ticks 覆盖默认
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("combat.caps.max-chill-ticks", 60);
            require(com.forgemaster.combat.DamageBudget.Caps.fromConfig(cfg)
                            .clampChillTicks(200) == 60,
                    "config 覆盖的 max-chill-ticks 没生效");
        });

        // ---------------------------------------------------------------- 6. 默认表与合并
        suite.addExternal(g, "默认表：temper/siphon 已注册且机制参数齐全", () -> {
            var defs = com.forgemaster.enchant.EnchantRegistry.defaults();
            var temper = defs.stream().filter(d -> d.id().equals("temper")).findFirst().orElse(null);
            require(temper != null, "temper 没进默认附魔表");
            require(temper.kind() == com.forgemaster.enchant.CustomEnchant.Kind.STREAK,
                    "temper 机制应是 STREAK");
            require(temper.maxLevel() == 5 && temper.slot()
                            == com.forgemaster.enchant.CustomEnchant.Slot.WEAPON,
                    "temper 应是武器 5 级");
            require(Math.abs(temper.at(5) - 0.20) < 1e-9,
                    "temper 满级单层应是 0.20，实际 " + temper.at(5));
            var siphon = defs.stream().filter(d -> d.id().equals("siphon")).findFirst().orElse(null);
            require(siphon != null, "siphon 没进默认附魔表");
            require(siphon.kind() == com.forgemaster.enchant.CustomEnchant.Kind.DUR_LEECH,
                    "siphon 机制应是 DUR_LEECH");
            require(Math.abs(siphon.at(4) - 2.0) < 1e-9,
                    "siphon 4 级应是 2.0 点，实际 " + siphon.at(4));
            // Lore 数值文案认得出新机制（switch 漏分支会直接编译失败，这里是行为兜底）
            require(temper.valueText(5).contains("20"), "temper 的 Lore 文案应含 20");
            require(siphon.valueText(5).contains("2.5"), "siphon 的 Lore 文案应含 2.5");
        });

        // ---------------------------------------------------------------- 7. 词条 roll 口径
        suite.addExternal(g, "词条：冰噬整秒取档、处决不越界", () -> {
            var rnd = new java.util.Random(42);
            var chill = new com.forgemaster.affix.Affix("chill", "冰噬",
                    com.forgemaster.affix.Affix.Kind.CHILL, null, 1, 2, "秒减速", false, 10);
            for (int i = 0; i < 200; i++) {
                double v = chill.roll(rnd);
                require(v == Math.rint(v) && v >= 1 && v <= 2,
                        "冰噬必须整秒且在 [1,2]，roll 出 " + v);
            }
            var execute = new com.forgemaster.affix.Affix("execute", "处决",
                    com.forgemaster.affix.Affix.Kind.EXECUTE, null, 0.06, 0.30, "残血增伤", true, 8);
            for (int i = 0; i < 200; i++) {
                double v = execute.roll(rnd);
                require(v >= 0.06 && v <= 0.30 + 1e-9,
                        "处决 roll 应在 [0.06,0.30]（缩放前），roll 出 " + v);
            }
        });
    }

    /** 造一个只有淬锋参数的 Mechanics（绕过私有 with*，直接用公开 record 构造） */
    private static com.forgemaster.enchant.EnchantEffects.Mechanics bigStreakMechanics(double perLevel) {
        return new com.forgemaster.enchant.EnchantEffects.Mechanics(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, perLevel, 0);
    }

    /** 造一个只有饮命参数的 Mechanics */
    private static com.forgemaster.enchant.EnchantEffects.Mechanics bigDurLeechMechanics(double durLeech) {
        return new com.forgemaster.enchant.EnchantEffects.Mechanics(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, durLeech);
    }
}
