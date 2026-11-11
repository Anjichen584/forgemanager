package com.forgemaster.selftest;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.spirit.SpiritManager;
import com.forgemaster.strengthen.StrengthenService;

import java.util.List;
import java.util.UUID;

/**
 * v2.25 拓展切片二（剑灵灵契重抽 / 强化保险）的自检护栏。
 *
 * <p>只测不依赖真实玩家事件的逻辑：重抽费用与每日上限、重抽守恒（纯函数部分）、
 * 保险费与理赔比例夹取、碎剑退款额、保险态清理。战斗事件层（碎剑时机的实际触发、
 * 命令二次确认的交互流）不在此覆盖，进服手测。</p>
 */
public final class SelfTestExpansion25Social {

    private SelfTestExpansion25Social() {
    }

    /** 由自检套件统一挂接：{@code SelfTestExpansion25Social.register(suite)} */
    public static void register(SelfTestSuite suite) {
        ForgingMasterPlugin plugin = org.bukkit.plugin.java.JavaPlugin
                .getPlugin(ForgingMasterPlugin.class);
        String g = "v2.25 切片二";

        // ① 重抽费用与每日上限：静态钳制 + 运行时读口一致
        suite.addExternal(g, "重抽费用/每日上限：cost≥0、daily-limit 夹进 [1,99]", () -> {
            if (SpiritManager.clampRerollCost(-1) != 0 || SpiritManager.clampRerollCost(0) != 0
                    || SpiritManager.clampRerollCost(2000) != 2000) {
                throw new IllegalStateException("重抽费用钳制失效（负数应夹 0，其余原样）");
            }
            if (SpiritManager.clampRerollDailyLimit(0) != 1
                    || SpiritManager.clampRerollDailyLimit(-5) != 1
                    || SpiritManager.clampRerollDailyLimit(3) != 3
                    || SpiritManager.clampRerollDailyLimit(999) != 99) {
                throw new IllegalStateException("每日上限钳制失效（应夹进 [1, 99]）");
            }
            SpiritManager sm = plugin.spirit();
            if (sm == null) {
                throw new IllegalStateException("SpiritManager 没起来");
            }
            if (sm.rerollCost() != SpiritManager.clampRerollCost(sm.rerollCost())
                    || sm.rerollDailyLimit() != SpiritManager.clampRerollDailyLimit(sm.rerollDailyLimit())) {
                throw new IllegalStateException("运行时重抽参数越过了代码夹子");
            }
        });

        // ② 每日次数判定：跨天重置、同天累加、脏数据按已用完（宁可不给不白送）
        suite.addExternal(g, "每日次数判定：canRerollToday / nextRerollStamp 互为镜像", () -> {
            if (!SpiritManager.canRerollToday(null, "2026-11-12", 1)) {
                throw new IllegalStateException("无记录应可重抽");
            }
            if (!SpiritManager.canRerollToday("2026-11-11|1", "2026-11-12", 1)) {
                throw new IllegalStateException("跨天应重新可抽");
            }
            if (SpiritManager.canRerollToday("2026-11-12|1", "2026-11-12", 1)) {
                throw new IllegalStateException("同天已用 1 次且限 1 次应拒绝");
            }
            if (!SpiritManager.canRerollToday("2026-11-12|2", "2026-11-12", 3)
                    || SpiritManager.canRerollToday("2026-11-12|3", "2026-11-12", 3)) {
                throw new IllegalStateException("限 3 次时用 2 次应放行、用 3 次应拒绝");
            }
            if (SpiritManager.canRerollToday("2026-11-12|垃圾", "2026-11-12", 1)) {
                throw new IllegalStateException("脏计数应按已用完处理");
            }
            if (SpiritManager.canRerollToday("2026-11-12|0", "2026-11-12", 0)) {
                throw new IllegalStateException("limit=0 应一律拒绝");
            }
            // 镜像：canRerollToday 放行后 nextRerollStamp 写下的戳必然再次被放行判定拒绝
            String s1 = SpiritManager.nextRerollStamp(null, "2026-11-12", 1);
            if (!"2026-11-12|1".equals(s1) || SpiritManager.canRerollToday(s1, "2026-11-12", 1)) {
                throw new IllegalStateException("首抽后的戳应为 date|1 且当天不可再抽");
            }
            String s3 = SpiritManager.nextRerollStamp("2026-11-12|2", "2026-11-12", 3);
            if (!"2026-11-12|3".equals(s3)) {
                throw new IllegalStateException("同天第三次重抽应累加为 3");
            }
            String sOver = SpiritManager.nextRerollStamp("2026-11-12|9", "2026-11-12", 2);
            if (!"2026-11-12|2".equals(sOver)) {
                throw new IllegalStateException("计数应封顶在 limit，防止配置调小后旧计数爆表");
            }
            String sNew = SpiritManager.nextRerollStamp("2026-11-11|9", "2026-11-12", 2);
            if (!"2026-11-12|1".equals(sNew)) {
                throw new IllegalStateException("跨天应从 1 重新起算");
            }
        });

        // ③ 重抽守恒（纯函数部分）：档位条数只由 slotsForTier 决定；affix_list 只摘灵契、锻造词条原样保留
        suite.addExternal(g, "重抽守恒：slotsForTier 定条数，removeSpiritIds 只摘灵契", () -> {
            if (SpiritManager.slotsForTier(0, 2) != 0 || SpiritManager.slotsForTier(1, 2) != 0
                    || SpiritManager.slotsForTier(2, 2) != 1 || SpiritManager.slotsForTier(4, 2) != 2
                    || SpiritManager.slotsForTier(9, 2) != 2 || SpiritManager.slotsForTier(4, 0) != 0) {
                throw new IllegalStateException("灵契条数公式应为 min(cap, max(0, tier-1))");
            }
            String csv = "sharpness,lifesteal,fire";
            // 灵契只摘指定 id：顺序、无关词条一个不丢（守恒的另一面）
            if (!"sharpness,fire".equals(SpiritManager.removeSpiritIds(csv, List.of("lifesteal")))) {
                throw new IllegalStateException("removeSpiritIds 不应动其它词条");
            }
            if (!"lifesteal,fire".equals(SpiritManager.removeSpiritIds(csv, List.of("SHARPNESS")))) {
                throw new IllegalStateException("id 匹配应忽略大小写");
            }
            if (!csv.equals(SpiritManager.removeSpiritIds(csv, List.of("不存在的")))) {
                throw new IllegalStateException("摘不存在的 id 应原样返回");
            }
            if (!"".equals(SpiritManager.removeSpiritIds(csv, List.of("sharpness", "lifesteal", "fire")))
                    || !"".equals(SpiritManager.removeSpiritIds(null, List.of("a")))  // v2.25 收口修钉子：漏了 !
                    || !"".equals(SpiritManager.removeSpiritIds("", List.of("a")))) {  // v2.25 收口修钉子：漏了 !
                throw new IllegalStateException("全摘应得空串；null / 空串输入应安全");
            }
            if (!csv.equals(SpiritManager.removeSpiritIds(csv, null))
                    || !csv.equals(SpiritManager.removeSpiritIds(csv, List.of()))) {
                throw new IllegalStateException("空摘除列表应原样返回");
            }
        });

        // ④ 保险费夹取：负数夹 0，运行时读口一致
        suite.addExternal(g, "保险费夹取：fee-per-strike ≥ 0，读口过同一夹子", () -> {
            if (StrengthenService.clampInsureFee(-1) != 0 || StrengthenService.clampInsureFee(0) != 0
                    || StrengthenService.clampInsureFee(200) != 200
                    || StrengthenService.clampInsureFee(Long.MAX_VALUE) != Long.MAX_VALUE) {
                throw new IllegalStateException("保险费钳制失效（负数应夹 0，其余原样）");
            }
            StrengthenService svc = plugin.strengthen();
            if (svc == null) {
                throw new IllegalStateException("StrengthenService 没起来");
            }
            if (svc.insureFee() != StrengthenService.clampInsureFee(svc.insureFee())) {
                throw new IllegalStateException("运行时保险费越过了代码夹子");
            }
        });

        // ⑤ refund-ratio 夹取：[0, 0.8]，读口一致
        suite.addExternal(g, "refund-ratio 夹取：应夹进 [0, 0.8]", () -> {
            if (StrengthenService.clampRefundRatio(1.0) != 0.8
                    || StrengthenService.clampRefundRatio(2.0) != 0.8
                    || StrengthenService.clampRefundRatio(-0.5) != 0.0
                    || StrengthenService.clampRefundRatio(0.5) != 0.5
                    || StrengthenService.clampRefundRatio(0.8) != 0.8) {
                throw new IllegalStateException("理赔比例钳制失效（应夹进 [0, 0.8]）");
            }
            StrengthenService svc = plugin.strengthen();
            if (svc != null && svc.insureRefundRatio()
                    != StrengthenService.clampRefundRatio(svc.insureRefundRatio())) {
                throw new IllegalStateException("运行时理赔比例越过了代码夹子");
            }
        });

        // ⑥ 碎剑退款额：只含材料折价、不含剑币费；任一因子非正返回 0
        suite.addExternal(g, "碎剑退款额：单价×数量×比例，非正因子为 0", () -> {
            if (StrengthenService.insuranceRefund(100, 3, 0.5) != 150) {
                throw new IllegalStateException("100 单价 × 3 材料 × 50% 应退 150");
            }
            if (StrengthenService.insuranceRefund(33, 3, 0.5) != 50) {
                throw new IllegalStateException("非整除应按四舍五入结算（33×3×0.5=49.5→50）");
            }
            if (StrengthenService.insuranceRefund(100, 3, 0.0) != 0
                    || StrengthenService.insuranceRefund(100, 3, -0.2) != 0
                    || StrengthenService.insuranceRefund(0, 3, 0.5) != 0
                    || StrengthenService.insuranceRefund(-5, 3, 0.5) != 0
                    || StrengthenService.insuranceRefund(100, 0, 0.5) != 0
                    || StrengthenService.insuranceRefund(100, -1, 0.5) != 0) {
                throw new IllegalStateException("比例/单价/数量非正时应一律返回 0");
            }
            // 比例写飞也要被夹住：unitPrice 99999 × 99 个 × ratio 99 → 仍按 0.8 封顶
            long capped = StrengthenService.insuranceRefund(99999, 99, 99.0);
            if (capped != Math.round(99999.0 * 99 * 0.8)) {
                throw new IllegalStateException("比例越界未按 0.8 封顶");
            }
        });

        // ⑦ 保险态清理：会话级开关 toggle / 定点清 / 全清都要干净
        suite.addExternal(g, "保险态清理：toggle 后 isInsured 可见，clear 不残留", () -> {
            StrengthenService svc = plugin.strengthen();
            if (svc == null) {
                throw new IllegalStateException("StrengthenService 没起来");
            }
            UUID fake = UUID.nameUUIDFromBytes("SelfTest/insure".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            svc.clearInsure(fake); // 归零起点，别的自检可能动过
            if (svc.isInsured(fake)) {
                throw new IllegalStateException("未开启时 isInsured 应为 false");
            }
            svc.setInsured(fake, true);
            if (!svc.isInsured(fake)) {
                throw new IllegalStateException("setInsured(true) 后应可见");
            }
            svc.setInsured(fake, false);
            if (svc.isInsured(fake)) {
                throw new IllegalStateException("setInsured(false) 应等价于清除");
            }
            svc.setInsured(fake, true);
            svc.clearInsure(fake);
            if (svc.isInsured(fake)) {
                throw new IllegalStateException("clearInsure 后不得残留");
            }
            svc.setInsured(fake, true);
            int cleaned = svc.clearAllInsured();
            if (cleaned < 1 || svc.isInsured(fake)) {
                throw new IllegalStateException("clearAllInsured 应清空全部保险态");
            }
        });
    }
}
