package com.forgemaster.selftest;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.guild.GuildService;
import com.forgemaster.spirit.SpiritManager;
import com.forgemaster.stats.StatsStore;
import com.forgemaster.tomb.TombManager;
import com.forgemaster.util.Keys;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.UUID;

/**
 * v2.24 拓展切片二（剑灵灵击 / 冢主温养 / 商会基金科技 / 声望兑换）的自检护栏。
 *
 * <p>只测不依赖真实玩家事件的逻辑：参数夹取、温养上限、经济守恒、科技乘区、
 * 声望拒付、兑换上限。战斗事件层（灵击炸怪不炸玩家）、温养写回快照的进服表现
 * 不在此覆盖，进服手测。</p>
 */
public final class SelfTestExpansion24Social {

    private SelfTestExpansion24Social() {
    }

    /** 由自检套件统一挂接：{@code SelfTestExpansion24Social.register(suite)} */
    public static void register(SelfTestSuite suite) {
        ForgingMasterPlugin plugin = org.bukkit.plugin.java.JavaPlugin
                .getPlugin(ForgingMasterPlugin.class);
        String g = "v2.24 切片二";

        // ① 灵击参数夹取：静态钳制 + 运行时配置读取都不得越过代码上限
        suite.addExternal(g, "灵击参数夹取：chance/damage/radius 上限与冷却/档位下限", () -> {
            if (SpiritManager.clampSurgeChance(0.99) != 0.15 || SpiritManager.clampSurgeChance(-1) != 0) {
                throw new IllegalStateException("chance 钳制失效（应夹进 [0, 0.15]）");
            }
            if (SpiritManager.clampSurgeDamage(99) != 4.0 || SpiritManager.clampSurgeDamage(-2) != 0) {
                throw new IllegalStateException("damage 钳制失效（应夹进 [0, 4.0]）");
            }
            if (SpiritManager.clampSurgeRadius(30) != 3.0 || SpiritManager.clampSurgeRadius(-1) != 0) {
                throw new IllegalStateException("radius 钳制失效（应夹进 [0, 3.0]）");
            }
            if (SpiritManager.clampSurgeCooldownMs(0) != 3000
                    || SpiritManager.clampSurgeCooldownMs(8000) != 8000) {
                throw new IllegalStateException("cooldown-ms 下限 3000 失效");
            }
            if (SpiritManager.clampSurgeMinTier(0) != 1 || SpiritManager.clampSurgeMinTier(3) != 3) {
                throw new IllegalStateException("min-tier 下限 1 失效");
            }
            // 运行时读口与静态钳制必须一致（配置写飞了也要被夹回来）
            SpiritManager sm = plugin.spirit();
            if (sm == null) {
                throw new IllegalStateException("SpiritManager 没起来");
            }
            if (sm.surgeChance() != SpiritManager.clampSurgeChance(sm.surgeChance())
                    || sm.surgeDamage() != SpiritManager.clampSurgeDamage(sm.surgeDamage())
                    || sm.surgeRadius() != SpiritManager.clampSurgeRadius(sm.surgeRadius())
                    || sm.surgeCooldownMs() != SpiritManager.clampSurgeCooldownMs(sm.surgeCooldownMs())
                    || sm.surgeMinTier() != SpiritManager.clampSurgeMinTier(sm.surgeMinTier())) {
                throw new IllegalStateException("运行时灵击参数越过了代码上限");
            }
        });

        // ② 温养不超灵性上限：纯数值层折算 + 崩刃剑拒温
        suite.addExternal(g, "温养不超灵性上限：折算到 spirit.max 截断", () -> {
            if (TombManager.nurtureGain(998, 1000, 5) != 2) {
                throw new IllegalStateException("998+5 应折算成 +2");
            }
            if (TombManager.nurtureGain(500, 1000, 5) != 5) {
                throw new IllegalStateException("未满时应全额温养");
            }
            if (TombManager.nurtureGain(1000, 1000, 5) != 0
                    || TombManager.nurtureGain(500, 1000, 0) != 0
                    || TombManager.nurtureGain(500, 1000, -3) != 0) {
                throw new IllegalStateException("已满 / 0 / 负数温养应全落空");
            }
        });

        // ③ 崩刃剑不温养：broken 留档与快照耐久 0 都拒温，完好锻造品放行
        suite.addExternal(g, "崩刃剑不温养：broken / 耐久 0 拒温，完好锻造品放行", () -> {
            TombManager tm = plugin.tombs();
            if (tm == null) {
                throw new IllegalStateException("TombManager 没起来");
            }
            UUID owner = UUID.nameUUIDFromBytes("SelfTest/nurture".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            ItemStack intact = forgedSword(0);
            TombManager.Tomb ok = new TombManager.Tomb("TST1", owner, "自检", "完好之刃",
                    System.currentTimeMillis(), "iron", "COMMON", 60.0, intact);
            if (!tm.canNurture(ok)) {
                throw new IllegalStateException("完好锻造品应能被温养");
            }
            ItemStack snapped = forgedSword(Material.IRON_SWORD.getMaxDurability()); // 耐久打光
            TombManager.Tomb dead = new TombManager.Tomb("TST2", owner, "自检", "崩刃之刃",
                    System.currentTimeMillis(), "iron", "COMMON", 60.0, snapped);
            if (tm.canNurture(dead)) {
                throw new IllegalStateException("快照耐久 0 的崩刃剑不应被温养");
            }
            TombManager.Tomb broken = new TombManager.Tomb("TST3", owner, "自检", "碎剑",
                    System.currentTimeMillis(), "iron", "COMMON", 60.0, forgedSword(0));
            broken.broken = true;
            if (tm.canNurture(broken)) {
                throw new IllegalStateException("碎剑留档不应被温养");
            }
            if (tm.canNurture(null)) {
                throw new IllegalStateException("null 条目不应被温养");
            }
        });

        // ④ 基金守恒（纯数值层）：负捐款不动账，基金不足买科技不动账
        suite.addExternal(g, "基金守恒：捐款只加不减，买科技不足拒付", () -> {
            if (GuildService.fundAfterDonate(1000, 500) != 1500) {
                throw new IllegalStateException("捐款 500 应精确入账");
            }
            if (GuildService.fundAfterDonate(1000, -500) != 1000
                    || GuildService.fundAfterDonate(1000, 0) != 1000) {
                throw new IllegalStateException("负数 / 零捐款不应动账");
            }
            if (GuildService.fundAfterTech(3000, 2000) != 1000) {
                throw new IllegalStateException("基金够时应精确扣款");
            }
            if (GuildService.fundAfterTech(1999, 2000) != -1) {
                throw new IllegalStateException("基金不足应返回 -1（调用方不得动账）");
            }
            // 逐级递增价：1 级 = base，2 级 = base + per
            if (GuildService.techCost(2000, 1500, 0) != 2000
                    || GuildService.techCost(2000, 1500, 1) != 3500) {
                throw new IllegalStateException("科技逐级递增价不符");
            }
        });

        // ⑤ 科技乘区上限：锻造费折扣 ≤10%、成功率 ≤5%、等级硬上限 5
        suite.addExternal(g, "科技乘区上限：-10% / +5% 封顶，等级夹 5", () -> {
            if (GuildService.feeMultiplierOf(5, 0.02) != 0.90) {
                throw new IllegalStateException("满级 5 级锻造费应打九折");
            }
            if (GuildService.feeMultiplierOf(99, 0.02) != 0.90) {
                throw new IllegalStateException("超出 max-level 的等级也应被 10% 上限封死");
            }
            if (GuildService.feeMultiplierOf(0, 0.02) != 1.0
                    || GuildService.feeMultiplierOf(-1, 0.02) != 1.0) {
                throw new IllegalStateException("0 级 / 负级应无折扣");
            }
            if (GuildService.successBonusOf(5, 0.01) != 0.05
                    || GuildService.successBonusOf(99, 0.01) != 0.05) {
                throw new IllegalStateException("成功率加成应被 5% 上限封死");
            }
            if (GuildService.successBonusOf(0, 0.01) != 0.0) {
                throw new IllegalStateException("0 级不应有成功率加成");
            }
            if (GuildService.clampTechMaxLevel(0) != 1 || GuildService.clampTechMaxLevel(99) != 5
                    || GuildService.clampTechMaxLevel(5) != 5) {
                throw new IllegalStateException("科技等级上限应夹进 [1, 5]");
            }
            if (!GuildService.isTech("forge-discount") || !GuildService.isTech("FORGE-SUCCESS")
                    || GuildService.isTech("forge-speed")) {
                throw new IllegalStateException("科技 id 白名单失效");
            }
        });

        // ⑥ 声望扣减不足拒付：够才扣，扣后余额精确（假 UUID，不碰真实档案）
        suite.addExternal(g, "声望扣减不足拒付：spendReputation 够才扣", () -> {
            UUID fake = UUID.nameUUIDFromBytes("SelfTest/redeem-rep".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            StatsStore stats = plugin.stats();
            stats.addReputation(fake, -stats.get(fake).reputation); // 归零起点，别的自检可能动过
            if (stats.spendReputation(fake, 1)) {
                throw new IllegalStateException("0 声望应拒付");
            }
            stats.addReputation(fake, 100);
            if (!stats.spendReputation(fake, 60)) {
                throw new IllegalStateException("余额够时应扣成功");
            }
            if (stats.get(fake).reputation != 40) {
                throw new IllegalStateException("扣后余额应为 40，实得 " + stats.get(fake).reputation);
            }
            if (stats.spendReputation(fake, 100)) {
                throw new IllegalStateException("余额不足应拒付");
            }
            if (stats.get(fake).reputation != 40) {
                throw new IllegalStateException("拒付后余额不得变动");
            }
            if (!stats.spendReputation(fake, 0)) {
                throw new IllegalStateException("0 扣减应视为无操作成功");
            }
        });

        // ⑦ redeem 单次上限：≤0 拒付，超限夹到 coin-max，合法值原样放行
        suite.addExternal(g, "redeem 单次上限：夹进 [1, coin-max]", () -> {
            if (com.forgemaster.command.ForgingCommand.clampRedeemAmount(0, 500) != 0
                    || com.forgemaster.command.ForgingCommand.clampRedeemAmount(-5, 500) != 0) {
                throw new IllegalStateException("≤0 应返回 0（拒付）");
            }
            if (com.forgemaster.command.ForgingCommand.clampRedeemAmount(999999, 500) != 500) {
                throw new IllegalStateException("超限应夹到 coin-max");
            }
            if (com.forgemaster.command.ForgingCommand.clampRedeemAmount(30, 500) != 30) {
                throw new IllegalStateException("合法值应原样放行");
            }
            if (com.forgemaster.command.ForgingCommand.clampRedeemAmount(3, 0) != 1) {
                throw new IllegalStateException("max 下限 1：配置写 0 也不该把上限夹成 0");
            }
        });
    }

    /** 造一把带 FORGED 标记的铁剑（damage 指定耐久损耗，0 = 完好） */
    private static ItemStack forgedSword(int damage) {
        ItemStack sword = new ItemStack(Material.IRON_SWORD);
        ItemMeta meta = sword.getItemMeta();
        if (meta == null) {
            throw new IllegalStateException("铁剑拿不到 meta");
        }
        meta.getPersistentDataContainer().set(Keys.FORGED,
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        if (damage > 0) {
            ((org.bukkit.inventory.meta.Damageable) meta).setDamage(damage);
        }
        sword.setItemMeta(meta);
        return sword;
    }
}
