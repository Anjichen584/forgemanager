package com.forgemaster.pass;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.selftest.SelfTestSuite;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.UUID;

/**
 * 通行证付费轨（v2.23 · P1-7）的运行时自检，分组「v2.23 通行证付费轨」。
 *
 * <p>约束与套件一致：假数据玩家统一 {@code SelfTest} 前缀的确定性 UUID；
 * 存档检查只在<b>临时文件</b>上做写读往返，绝不碰真实的 pass-data.yml。</p>
 *
 * <p>接线：{@code SelfTestSuite} 里调 {@code SelfTestPass.register(suite)} 一行即可
 * （本类只依赖套件的公开 {@link SelfTestSuite#addExternal} 口，不碰套件内部）。</p>
 */
public final class SelfTestPass {

    private SelfTestPass() {
    }

    /** 挂接本模块的自检项 */
    public static void register(SelfTestSuite suite) {
        String g = "v2.23 通行证付费轨";

        suite.addExternal(g, "付费轨配置读取钳制", () -> {
            ForgingMasterPlugin plugin = plugin();
            var sp = pass(plugin);
            // 默认价与钳制：config 没写 pass.premium.cost 时落代码默认 5000；负价一律钳 0
            require(SeasonPassService.DEFAULT_PREMIUM_COST == 5000, "付费轨默认价应为 5000");
            require(sp.premiumCost() >= 0, "解锁价不允许为负，实际 " + sp.premiumCost());
            require(SeasonPassService.clampCost(-1) == 0 && SeasonPassService.clampCost(0) == 0
                    && SeasonPassService.clampCost(5000) == 5000, "负价要钳到 0，非负原样保留");
            // 奖励字段钳制：负数钳下限（coins≥0 / 数量≥1）
            var r = new SeasonPassService.Reward(-50, "netherite_ingot", -3, "steady", 0);
            require(r.coins == 0 && r.materialAmount == 1 && r.charmAmount == 1,
                    "奖励负数字段要钳到下限");
            // 付费轨级号钳制：只认 1 ~ 轨道级数，越界 / 非数字丢弃
            int maxLevel = sp.levels().size();
            require(maxLevel >= 10, "轨道级数应 ≥ 10（默认 config 配 20 级），实际 " + maxLevel);
            require(SeasonPassService.clampRewardLevelKey("3", maxLevel) == 3, "合法级号应保留");
            require(SeasonPassService.clampRewardLevelKey(" 5 ", maxLevel) == 5, "级号应容忍空白");
            require(SeasonPassService.clampRewardLevelKey("0", maxLevel) == 0
                    && SeasonPassService.clampRewardLevelKey("-2", maxLevel) == 0
                    && SeasonPassService.clampRewardLevelKey("abc", maxLevel) == 0
                    && SeasonPassService.clampRewardLevelKey(String.valueOf(maxLevel + 1), maxLevel) == 0,
                    "越界 / 非数字级号要丢弃");
            // 内置默认付费轨常驻：1 级 800 币起，越界级为空奖励
            require(sp.premiumConfigured(), "付费轨（内置默认 10 级）应常驻");
            require(sp.premiumReward(1).coins == 800, "默认付费轨第 1 级应为 800 币");
            require(sp.premiumReward(11).isEmpty(), "默认付费轨只到 10 级，越界级应为空奖励");
        });

        suite.addExternal(g, "付费轨槽位与关闭按钮不冲突", () -> {
            var usage = PassGui.slotUsage();
            require(!usage.containsKey(PassGui.CLOSE_SLOT) || "关闭".equals(usage.get(PassGui.CLOSE_SLOT)),
                    "关闭格必须只标「关闭」");
            for (int slot : PassGui.PREMIUM_SLOTS) {
                require(slot != PassGui.CLOSE_SLOT, "付费轨槽位 " + slot + " 撞了关闭格");
                require(slot >= 0 && slot < PassGui.SIZE, "付费轨槽位 " + slot + " 越界");
            }
            // 免费轨与付费轨不能共用同一格
            for (int free : PassGui.LEVEL_SLOTS) {
                for (int prem : PassGui.PREMIUM_SLOTS) {
                    require(free != prem, "免费轨格 " + free + " 与付费轨撞格");
                }
            }
        });

        suite.addExternal(g, "购买资格判定：已购周期内重复购拒绝", () -> {
            ForgingMasterPlugin plugin = plugin();
            var fresh = pass(plugin);
            UUID buyer = selfTestUuid("pass-premium-buyer");
            UUID other = selfTestUuid("pass-premium-fresh");
            File file = tempFile(plugin);
            try {
                int season = fresh.seasonNumber();
                YamlConfiguration yml = new YamlConfiguration();
                yml.set("players." + buyer + "." + season + ".premium", true);
                yml.save(file);
                var svc = new SeasonPassService(plugin, file);
                svc.load();
                require(svc.premiumBought(buyer), "已购状态应能从 pass-data.yml 读出");
                String blocked = svc.premiumBuyBlockedReason(buyer);
                require(blocked != null && blocked.contains("重复购买"),
                        "周期内已购再买要被拒绝，实际=" + blocked);
                require(svc.premiumBuyBlockedReason(other) == null,
                        "未购玩家应判定为可买");
            } finally {
                file.delete();
            }
        });

        suite.addExternal(g, "付费轨领奖一次性（防重复领）", () -> {
            ForgingMasterPlugin plugin = plugin();
            UUID who = selfTestUuid("pass-premium-claim");
            File file = tempFile(plugin);
            try {
                int season = pass(plugin).seasonNumber();
                YamlConfiguration yml = new YamlConfiguration();
                String base = "players." + who + "." + season;
                yml.set(base + ".points", 1_000_000L);
                yml.set(base + ".premium", true);
                yml.set(base + ".premium-claimed", java.util.List.of(1));
                yml.save(file);
                var svc = new SeasonPassService(plugin, file);
                svc.load();
                require(svc.levels().size() >= 3, "轨道级数应 ≥ 3 才能测顺序");
                String again = svc.premiumClaimBlockedReason(who, 1);
                require(again != null && again.contains("领过"),
                        "已领级再领必须拒绝，实际=" + again);
                require(svc.premiumClaimBlockedReason(who, 2) == null,
                        "已领第 1 级 + 积分够，第 2 级应可领");
                require(svc.premiumClaimBlockedReason(who, 3) != null,
                        "跳过第 2 级直接领第 3 级要被拒绝");
                // 领掉第 2 级后立刻变成「已领」：一次性保证
                svc.forcePremiumClaimed(who, 2);
                String oneShot = svc.premiumClaimBlockedReason(who, 2);
                require(oneShot != null && oneShot.contains("领过"),
                        "同一级领第二次必须拒绝，实际=" + oneShot);
                require(svc.premiumClaimedLevel(who) == 2, "付费轨已领最高级应为 2");
                // 没买付费轨的人一个级都领不了
                UUID poor = selfTestUuid("pass-premium-notbought");
                String notBought = svc.premiumClaimBlockedReason(poor, 1);
                require(notBought != null && notBought.contains("购买"),
                        "未解锁付费轨领奖要被拒绝，实际=" + notBought);
            } finally {
                file.delete();
            }
        });

        suite.addExternal(g, "赛季重置后付费状态清空", () -> {
            ForgingMasterPlugin plugin = plugin();
            UUID now = selfTestUuid("pass-premium-current");
            UUID old = selfTestUuid("pass-premium-stale");
            File file = tempFile(plugin);
            try {
                int season = pass(plugin).seasonNumber();
                YamlConfiguration yml = new YamlConfiguration();
                // 当季已购 + 旧季（9999）也买过领过：旧季是只读履历，绝不进当季
                yml.set("players." + now + "." + season + ".premium", true);
                yml.set("players." + now + ".9999.premium", true);
                yml.set("players." + now + ".9999.premium-claimed", java.util.List.of(5));
                yml.set("players." + old + ".9999.premium", true);
                yml.set("players." + old + ".9999.premium-claimed", java.util.List.of(3));
                yml.save(file);
                var svc = new SeasonPassService(plugin, file);
                svc.load();
                require(svc.premiumBought(now), "当季已购应生效");
                require(svc.premiumClaimedLevel(now) == 0,
                        "旧季的付费轨领奖履历不能带进当季（付费状态随赛季清空）");
                require(!svc.premiumBought(old),
                        "只在旧季买过 = 新赛季付费状态为空，应从 0 级重新开始");
                String stale = svc.premiumClaimBlockedReason(old, 1);
                require(stale != null && stale.contains("购买"),
                        "旧季已购不能拿来当季领奖，实际=" + stale);
            } finally {
                file.delete();
            }
        });

        suite.addExternal(g, "pass-data.yml 新字段写读往返（临时文件）", () -> {
            ForgingMasterPlugin plugin = plugin();
            UUID who = selfTestUuid("pass-premium-roundtrip");
            File file = tempFile(plugin);
            try {
                var svcA = new SeasonPassService(plugin, file);
                svcA.load();
                long before = svcA.earn(who, "forge");
                require(before > 0, "积分登记（forge 来源）应生效，实际 " + before);
                svcA.markPremiumBought(who);
                svcA.forcePremiumClaimed(who, 1);
                svcA.forcePremiumClaimed(who, 2);
                svcA.save();
                // 另一个实例从同一文件读回，验证新字段真落了盘、格式没写歪
                var svcB = new SeasonPassService(plugin, file);
                svcB.load();
                require(svcB.points(who) == before, "积分往返不一致：" + svcB.points(who) + " ≠ " + before);
                require(svcB.premiumBought(who), "premium 字段往返失败");
                require(svcB.premiumClaimed(who, 1) && svcB.premiumClaimed(who, 2),
                        "premium-claimed 字段往返失败");
                require(svcB.premiumClaimedLevel(who) == 2, "付费轨已领最高级应为 2");
            } finally {
                file.delete();
            }
        });
    }

    // ------------------------------------------------------------ 内部

    private static ForgingMasterPlugin plugin() {
        var found = Bukkit.getPluginManager().getPlugin("ForgingMaster");
        require(found instanceof ForgingMasterPlugin, "主插件没有初始化");
        return (ForgingMasterPlugin) found;
    }

    private static SeasonPassService pass(ForgingMasterPlugin plugin) {
        var sp = plugin.seasonPass();
        require(sp != null, "赛季通行证服务没有初始化");
        return sp;
    }

    /** 自检专用确定性 UUID（不碰真实玩家档案） */
    private static UUID selfTestUuid(String tag) {
        return UUID.nameUUIDFromBytes(("SelfTest/" + tag).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 插件数据目录下的临时存档文件（用完即删，绝不碰真实 pass-data.yml） */
    private static File tempFile(ForgingMasterPlugin plugin) {
        File dir = plugin.getDataFolder();
        require(dir.isDirectory() || dir.mkdirs(), "插件数据目录不存在且建不出来");
        try {
            File f = File.createTempFile("selftest-pass-", ".yml", dir);
            f.deleteOnExit();
            return f;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("临时存档文件创建失败：" + ex.getMessage(), ex);
        }
    }

    private static void require(boolean cond, String msg) {
        if (!cond) {
            throw new IllegalStateException(msg);
        }
    }
}
