package com.forgemaster.guild;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.selftest.SelfTestSuite;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.Arrays;
import java.util.UUID;

/**
 * 行会拓展的外部自检（v2.23 行会仓库 + 行会委托配套回归）。
 *
 * <p>只测不依赖真实玩家事件的逻辑：仓库容量钳制、取出权限纯函数、委托进度推进 /
 * 达标 / 领奖一次性、委托配置钳制、guild-data.yml 仓库段写读往返（临时文件）。
 * GUI 点击层面的行为（shift 存入落仓储区、取出被拒提示节流）不在此覆盖，进服手测。</p>
 */
public final class SelfTestGuild {

    private SelfTestGuild() {
    }

    /** 由自检套件统一挂接：{@code SelfTestGuild.register(suite)} */
    public static void register(SelfTestSuite suite) {
        ForgingMasterPlugin plugin = org.bukkit.plugin.java.JavaPlugin
                .getPlugin(ForgingMasterPlugin.class);
        String g = "v2.23 行会拓展";

        // v2.23.1 修 P0-1：行数上限由 6 改成 5（6 行 = 54 格会让 49 号关闭钮落进仓储区，
        // 存在那格的物品每次开仓都被关闭图标覆盖销毁）。断言跟着改成 5。
        suite.addExternal(g, "仓库容量钳制：rows 钳 1~5，容量 = 行数 × 9", () -> {
            if (GuildWarehouse.clampRows(-3) != 1 || GuildWarehouse.clampRows(0) != 1
                    || GuildWarehouse.clampRows(1) != 1) {
                throw new IllegalStateException("rows 下界钳制失效");
            }
            if (GuildWarehouse.clampRows(3) != 3 || GuildWarehouse.clampRows(4) != 4) {
                throw new IllegalStateException("rows 合法值被改动");
            }
            if (GuildWarehouse.clampRows(5) != 5 || GuildWarehouse.clampRows(6) != 5
                    || GuildWarehouse.clampRows(99) != 5) {
                throw new IllegalStateException("rows 上界钳制失效（最多 5 行）");
            }
            // 不变量：仓储区必须与关闭槽完全不相交（P0-1 的根因就是这两者重叠）
            if (GuildWarehouseGui.STORAGE_MAX >= GuildWarehouseGui.CLOSE_SLOT) {
                throw new IllegalStateException("仓储区上界压到了关闭槽，槽位区间相交");
            }
            if (GuildWarehouse.clampRows(99) * 9 != GuildWarehouseGui.STORAGE_MAX) {
                throw new IllegalStateException("仓储区最大格数与 STORAGE_MAX 不一致");
            }
            // 容量跟着行数走；未开仓的行会 usage 应为 0
            GuildWarehouse wh = new GuildWarehouse(plugin);
            if (wh.capacity() != GuildWarehouse.clampRows(
                    plugin.settings().intOf("guild.warehouse.rows", 3)) * 9) {
                throw new IllegalStateException("capacity 与 rows 配置不一致");
            }
            if (wh.capacity() > GuildWarehouseGui.STORAGE_MAX) {
                throw new IllegalStateException("capacity 超出仓储区上界");
            }
            if (wh.usage("selftest-no-such-guild") != 0) {
                throw new IllegalStateException("未开仓的行会 usage 应为 0");
            }
        });

        suite.addExternal(g, "取出权限纯函数：会长全权，成员按配置放行", () -> {
            if (!GuildWarehouse.canWithdraw(true, false)) {
                throw new IllegalStateException("会长应永远能取出（全权）");
            }
            if (!GuildWarehouse.canWithdraw(true, true)) {
                throw new IllegalStateException("会长取出不应受配置影响");
            }
            if (GuildWarehouse.canWithdraw(false, false)) {
                throw new IllegalStateException("member-withdraw=false 时成员不能取出");
            }
            if (!GuildWarehouse.canWithdraw(false, true)) {
                throw new IllegalStateException("member-withdraw=true 时成员应能取出");
            }
        });

        suite.addExternal(g, "行会委托：进度推进 / 达标 / 领奖一次性（防重复领）", () -> {
            GuildQuests.QuestState q = new GuildQuests.QuestState();
            q.type = "forge";
            q.target = 10;
            q.progress = GuildQuests.applyProgress(5, 4);
            if (q.progress != 9 || GuildQuests.isComplete(q)) {
                throw new IllegalStateException("9/10 不应判达标");
            }
            UUID who = UUID.nameUUIDFromBytes("SelfTest/claim-once".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            if (GuildQuests.claimOneTime(q, who)) {
                throw new IllegalStateException("不应该在未达标的委托上领到奖");
            }
            q.progress = GuildQuests.applyProgress(q.progress, 5); // 9 + 5 = 14，允许超冲
            if (q.progress != 14) {
                throw new IllegalStateException("进度应冲到 14");
            }
            q.completed = true; // 对应 complete() 打标：进度冲线后由它置位
            if (!GuildQuests.isComplete(q)) {
                throw new IllegalStateException("达标后应判完成");
            }
            if (!GuildQuests.claimOneTime(q, who)) {
                throw new IllegalStateException("达标后首次领奖应成功");
            }
            if (GuildQuests.claimOneTime(q, who)) {
                throw new IllegalStateException("第二次领同一轮奖应被拒（防重复领）");
            }
            // 换人领不受第一人影响
            UUID other = UUID.nameUUIDFromBytes("SelfTest/claim-once-2".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            if (!GuildQuests.claimOneTime(q, other)) {
                throw new IllegalStateException("第二名成员应能独立领奖");
            }
        });

        suite.addExternal(g, "委托配置钳制：目标数量、奖励人数系数", () -> {
            if (GuildQuests.clampTarget(0) != 1 || GuildQuests.clampTarget(-9) != 1) {
                throw new IllegalStateException("target 下界钳制失效");
            }
            if (GuildQuests.clampTarget(25) != 25) {
                throw new IllegalStateException("target 合法值被改动");
            }
            if (GuildQuests.clampTarget(1_000_001L) != 1_000_000L) {
                throw new IllegalStateException("target 上界钳制失效");
            }
            // 奖励 = 基础 × (1 + (人数-1) × 系数)：3 人 ×0.1 = 1.2 倍，6 人 = 1.5 倍
            if (GuildQuests.rewardFor(100, 0.1, 1) != 100) {
                throw new IllegalStateException("单人不应吃人数系数");
            }
            if (GuildQuests.rewardFor(100, 0.1, 6) != 150) {
                throw new IllegalStateException("6 人 ×0.1 系数应为 150");
            }
            if (GuildQuests.rewardFor(100, -0.5, 6) != 100
                    || GuildQuests.rewardFor(100, 0.1, -3) != 100) {
                throw new IllegalStateException("负系数 / 负人数应钳回无加成");
            }
            if (GuildQuests.rewardFor(-5, 0.1, 3) != 0) {
                throw new IllegalStateException("负基础值奖励应钳 0");
            }
        });

        suite.addExternal(g, "guild-data.yml 仓库段写读往返（临时文件）", () -> {
            GuildWarehouse a = new GuildWarehouse(plugin);
            ItemStack sword = new ItemStack(Material.IRON_SWORD);
            ItemMeta meta = sword.getItemMeta();
            if (meta != null) {
                meta.displayName(net.kyori.adventure.text.Component.text("自检之刃"));
                sword.setItemMeta(meta);
            }
            int slotB = 5;
            a.contents("selftest-g1")[0] = sword;
            a.contents("selftest-g1")[slotB] = new ItemStack(Material.COAL, 3);
            a.markDirty();

            YamlConfiguration yml = new YamlConfiguration();
            a.write(yml);
            File file = tempFile(plugin);
            try {
                yml.save(file);
                GuildWarehouse b = new GuildWarehouse(plugin);
                b.read(YamlConfiguration.loadConfiguration(file));
                ItemStack[] back = b.contents("selftest-g1");
                if (back[0] == null || back[0].getType() != Material.IRON_SWORD) {
                    throw new IllegalStateException("槽 0 的剑没读回来");
                }
                if (!Arrays.equals(sword.serializeAsBytes(), back[0].serializeAsBytes())) {
                    throw new IllegalStateException("物品字节往返不一致（meta 丢了）");
                }
                if (back[slotB] == null || back[slotB].getAmount() != 3
                        || back[slotB].getType() != Material.COAL) {
                    throw new IllegalStateException("槽 " + slotB + " 的煤堆没按原样读回");
                }
                if (back[1] != null) {
                    throw new IllegalStateException("空槽不应被写出/读回");
                }
                if (b.usage("selftest-g1") != 2) {
                    throw new IllegalStateException("usage 统计不符: " + b.usage("selftest-g1"));
                }
            } finally {
                file.delete();
            }
        });
    }

    /** 插件数据目录下的临时存档文件（用完即删，绝不碰真实 guild-data.yml） */
    private static File tempFile(ForgingMasterPlugin plugin) {
        File dir = plugin.getDataFolder();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("插件数据目录不存在且建不出来");
        }
        try {
            File f = File.createTempFile("selftest-guild-", ".yml", dir);
            f.deleteOnExit();
            return f;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("临时存档文件创建失败：" + ex.getMessage(), ex);
        }
    }
}
