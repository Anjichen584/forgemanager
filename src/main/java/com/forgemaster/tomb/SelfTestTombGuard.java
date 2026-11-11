package com.forgemaster.tomb;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.selftest.SelfTestSuite;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Zombie;

import java.util.HashSet;
import java.util.List;
import java.util.Random;

/**
 * 残影词缀模块的外部自检（v2.23 残影守卫词缀化配套回归）。
 *
 * <p>覆盖五层：词缀池白名单过滤与钳制、每只数量分布掷签、掷签确定性
 * （{@code rollIds} 注入 Random，同 seed 同结果）、PDC 写读往返（真生成一只
 * Zombie 挂标记读回，测完即删）、伤害乘区纯函数断言。监听层面的实际战斗行为
 * （点燃 / 饮血 / 暴击在 EntityDamageByEntityEvent 里的落点）不在此覆盖，进服手测。</p>
 */
public final class SelfTestTombGuard {

    private SelfTestTombGuard() {
    }

    /** 由自检套件统一挂接：{@code SelfTestTombGuard.register(suite)} */
    public static void register(SelfTestSuite suite) {
        ForgingMasterPlugin plugin = org.bukkit.plugin.java.JavaPlugin
                .getPlugin(ForgingMasterPlugin.class);
        String g = "v2.23 残影词缀";

        suite.addExternal(g, "词缀池读取与钳制", () -> {
            TombGuardAffixes ax = plugin.tombs().guardAffixes();
            if (ax == null) {
                throw new IllegalStateException("残影词缀引擎没起来");
            }
            // 白名单过滤：未知 id / 空白 / 重复全部剔除，已知 id 保留顺序
            List<String> filtered = TombGuardAffixes.filterPool(
                    List.of("crit", "攻击", "fire", "crit", "  ", "lifesteal", "no_such"));
            if (!filtered.equals(List.of("crit", "fire", "lifesteal"))) {
                throw new IllegalStateException("白名单过滤结果不对: " + filtered);
            }
            if (!TombGuardAffixes.filterPool(null).isEmpty()) {
                throw new IllegalStateException("null 池没返回空表");
            }
            if (!TombGuardAffixes.filterPool(List.of("unknown", "nope")).isEmpty()) {
                throw new IllegalStateException("全未知池没返回空表");
            }
            // max-per-guard 钳制：负数 → 0，超池 → 池大小，合法值原样
            if (TombGuardAffixes.clampMaxPerGuard(-1, 6) != 0
                    || TombGuardAffixes.clampMaxPerGuard(-99, 6) != 0) {
                throw new IllegalStateException("max-per-guard 负数没归 0");
            }
            if (TombGuardAffixes.clampMaxPerGuard(99, 6) != 6
                    || TombGuardAffixes.clampMaxPerGuard(3, 6) != 3
                    || TombGuardAffixes.clampMaxPerGuard(2, 0) != 0) {
                throw new IllegalStateException("max-per-guard 上界 / 合法值 / 空池钳制失效");
            }
            // 引擎真实读取：默认池非空且全是已知 id，max 在 [0, 池大小]
            List<String> live = ax.pool();
            if (live.isEmpty()) {
                throw new IllegalStateException("词缀池读出来是空的");
            }
            for (String id : live) {
                if (!TombGuardAffixes.isKnownId(id)) {
                    throw new IllegalStateException("池里混进了未知 id: " + id);
                }
            }
            if (ax.maxPerGuard() < 0 || ax.maxPerGuard() > live.size()) {
                throw new IllegalStateException("maxPerGuard 出界: " + ax.maxPerGuard()
                        + " / 池 " + live.size());
            }
        });

        suite.addExternal(g, "每只数量分布掷签", () -> {
            // 权重表 index = 条数：roll 落点走累计表落档
            List<Double> w = List.of(0.35, 0.40, 0.25);
            if (TombGuardAffixes.pickCount(0.0, w, 2) != 0
                    || TombGuardAffixes.pickCount(0.34, w, 2) != 0) {
                throw new IllegalStateException("roll 落在第 0 档没返回 0");
            }
            if (TombGuardAffixes.pickCount(0.35, w, 2) != 1
                    || TombGuardAffixes.pickCount(0.74, w, 2) != 1) {
                throw new IllegalStateException("roll 落在第 1 档没返回 1");
            }
            if (TombGuardAffixes.pickCount(0.75, w, 2) != 2
                    || TombGuardAffixes.pickCount(0.999, w, 2) != 2) {
                throw new IllegalStateException("roll 落在第 2 档没返回 2");
            }
            // max = 0 强制 0 条；权重全 0 → 0 条；负权重按 0 处理后照常落档
            if (TombGuardAffixes.pickCount(0.9, w, 0) != 0) {
                throw new IllegalStateException("max=0 没强制 0 条");
            }
            if (TombGuardAffixes.pickCount(0.9, List.of(0.0, 0.0, 0.0), 2) != 0) {
                throw new IllegalStateException("全零权重没返回 0 条");
            }
            if (TombGuardAffixes.pickCount(0.9, List.of(-1.0, 2.0, -3.0), 2) != 1) {
                throw new IllegalStateException("负权重清洗后落档不对（应为 1 条）");
            }
            // 引擎默认权重是 0/1/2 条三档
            TombGuardAffixes ax = plugin.tombs().guardAffixes();
            if (ax.countWeights().size() < 2) {
                throw new IllegalStateException("默认数量分布不足两档: " + ax.countWeights());
            }
        });

        suite.addExternal(g, "掷词缀确定性：同 seed 同结果", () -> {
            List<String> pool = TombGuardAffixes.DEFAULT_POOL;
            List<Double> w = List.of(0.35, 0.40, 0.25);
            for (long seed : new long[]{1L, 42L, 20260101L}) {
                List<String> a = TombGuardAffixes.rollIds(pool, 2, w, new Random(seed));
                List<String> b = TombGuardAffixes.rollIds(pool, 2, w, new Random(seed));
                if (!a.equals(b)) {
                    throw new IllegalStateException("同 seed 掷签不一致 seed=" + seed
                            + " a=" + a + " b=" + b);
                }
                if (a.size() > 2) {
                    throw new IllegalStateException("掷签超出 max=2: " + a);
                }
                if (new HashSet<>(a).size() != a.size()) {
                    throw new IllegalStateException("同一次掷签出现了重复词缀: " + a);
                }
            }
            // max=0 → 恒空表；权重表压到一档时条数恒定；满池掷 2 条不重复且都在池内
            if (!TombGuardAffixes.rollIds(pool, 0, w, new Random(7)).isEmpty()) {
                throw new IllegalStateException("max=0 还掷出了词缀");
            }
            List<String> one = TombGuardAffixes.rollIds(pool, 99, List.of(0.0, 1.0), new Random(7));
            if (one.size() != 1) {
                throw new IllegalStateException("权重表只有 1 条档位时条数不是 1: " + one);
            }
            List<String> two = TombGuardAffixes.rollIds(pool, 2, List.of(0.0, 0.0, 1.0), new Random(9));
            if (two.size() != 2 || !pool.containsAll(two)) {
                throw new IllegalStateException("掷 2 条的结果不对: " + two);
            }
        });

        suite.addExternal(g, "PDC 写读往返（Zombie 挂标记读回）", () -> {
            var worlds = org.bukkit.Bukkit.getWorlds();
            if (worlds.isEmpty()) {
                throw new IllegalStateException("没有可用世界");
            }
            var world = worlds.get(0);
            TombGuardAffixes ax = plugin.tombs().guardAffixes();
            // 必须在已加载的区块里生成，否则 Bukkit 会静默失败（同铁砧演出用例的套路）
            var at = world.getSpawnLocation().add(0.5, 1, 0.5);
            at.getChunk().load();
            Zombie z = (Zombie) world.spawnEntity(at, EntityType.ZOMBIE);
            try {
                if (!z.isValid()) {
                    throw new IllegalStateException("Zombie 没生成出来（可能被其它插件取消）");
                }
                // 未挂签的实体不得被误判
                if (ax.isGuard(z) || !ax.affixIdsOf(z).isEmpty()) {
                    throw new IllegalStateException("没挂标记的 Zombie 被误判为守卫");
                }
                // 挂签 → 读回，PDC 往返一致
                List<String> applied = ax.apply(z, List.of("lifesteal", "fire"));
                if (!applied.equals(List.of("lifesteal", "fire"))) {
                    throw new IllegalStateException("挂签返回值不对: " + applied);
                }
                if (!ax.isGuard(z)) {
                    throw new IllegalStateException("挂签后 isGuard 没认出来");
                }
                List<String> back = ax.affixIdsOf(z);
                if (!back.equals(List.of("lifesteal", "fire"))) {
                    throw new IllegalStateException("PDC 往返不一致: " + back);
                }
                // 名字彩色段：多词条以 · 相连且带色值
                String tag = ax.affixTag(back);
                if (!tag.contains("饮血") || !tag.contains("灼魂") || !tag.contains("·")) {
                    throw new IllegalStateException("词缀名字段缺前缀: " + tag);
                }
                if (!ax.affixTag(List.of()).isEmpty()) {
                    throw new IllegalStateException("空词缀的名字段应返回空串");
                }
            } finally {
                z.remove(); // 测完即删，不给世界留尸
            }
            // 空表挂签 = 不打标记：监听侧按无词缀守卫放行
            Zombie z2 = (Zombie) world.spawnEntity(at, EntityType.ZOMBIE);
            try {
                ax.apply(z2, List.of());
                if (ax.isGuard(z2) || !ax.affixIdsOf(z2).isEmpty()) {
                    throw new IllegalStateException("空表挂签不该打标记");
                }
            } finally {
                z2.remove();
            }
        });

        suite.addExternal(g, "伤害乘区计算纯函数", () -> {
            // 攻击侧：基础 × attack_damage 倍率 × 暴击倍率；未挂词缀的乘区不动
            eq(TombGuardAffixes.outgoingDamage(10, false, 1.25, false, 1.5), 10.0, "无乘区");
            eq(TombGuardAffixes.outgoingDamage(10, true, 1.25, false, 1.5), 12.5, "attack_damage 乘区");
            eq(TombGuardAffixes.outgoingDamage(10, false, 1.25, true, 1.5), 15.0, "仅暴击");
            eq(TombGuardAffixes.outgoingDamage(10, true, 1.25, true, 1.5), 18.75, "乘区叠加");
            eq(TombGuardAffixes.outgoingDamage(-3, true, 1.25, false, 1.5), 0.0, "负伤害钳 0");
            // 受击减免：×(1 - 比例)，比例钳 [0,1]
            eq(TombGuardAffixes.incomingDamage(20, true, 0.25), 15.0, "25% 减免");
            eq(TombGuardAffixes.incomingDamage(20, true, 1.5), 0.0, "超额减免钳 0");
            eq(TombGuardAffixes.incomingDamage(20, false, 0.25), 20.0, "未挂减免不动");
            // 饮血：伤害 × 比例，负伤害 / 负比例 → 0，比例超 1 按 1 封顶
            eq(TombGuardAffixes.lifestealHeal(10, 0.3), 3.0, "30% 饮血");
            if (TombGuardAffixes.lifestealHeal(-5, 0.3) != 0
                    || TombGuardAffixes.lifestealHeal(10, -0.2) != 0) {
                throw new IllegalStateException("负伤害 / 负比例的饮血没归 0");
            }
            eq(TombGuardAffixes.lifestealHeal(10, 2.0), 10.0, "饮血比例封顶 100%");
            // 暴击判定：roll < chance 才触发，chance ≤ 0 永不触发
            if (!TombGuardAffixes.critHit(0.19, 0.2) || TombGuardAffixes.critHit(0.2, 0.2)) {
                throw new IllegalStateException("暴击边界判定不对");
            }
            if (TombGuardAffixes.critHit(0.0, 0.0) || TombGuardAffixes.critHit(-1, 0.5)) {
                throw new IllegalStateException("零概率 / 负 roll 的暴击判定不对");
            }
        });
    }

    /** 浮点断言小工具（套件的 require 是私有的，这里用异常表达失败） */
    private static void eq(double actual, double expected, String what) {
        if (Math.abs(actual - expected) > 1e-9) {
            throw new IllegalStateException(what + " 计算不对: 期望 " + expected + " 实得 " + actual);
        }
    }
}
