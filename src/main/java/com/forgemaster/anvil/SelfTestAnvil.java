package com.forgemaster.anvil;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.quality.Quality;
import com.forgemaster.selftest.SelfTestSuite;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * 铁砧模块的外部自检（v2.23 铁砧批量 + anvils.yml 异步冲刷配套回归）。
 *
 * <p>只测不依赖真实玩家事件的逻辑：批量终止条件的纯函数、批量上限钳制、
 * 落锤演出帧、anvils.yml 写读往返（全部用独立临时管理器 + 临时文件，
 * 不碰线上砧位与真实存档）。玩家点击层面的行为（Shift+左键触发批量、
 * 动画只播最后一锤）不在此覆盖，进服手测。</p>
 */
public final class SelfTestAnvil {

    private SelfTestAnvil() {
    }

    /** 由自检套件统一挂接：{@code SelfTestAnvil.register(suite)} */
    public static void register(SelfTestSuite suite) {
        ForgingMasterPlugin plugin = org.bukkit.plugin.java.JavaPlugin
                .getPlugin(ForgingMasterPlugin.class);
        String g = "v2.23 铁砧批量";

        suite.addExternal(g, "批量上限与演出帧：配置读取与钳制", () -> {
            var mgr = plugin.anvilForge();
            if (mgr == null) {
                throw new IllegalStateException("铁砧模块没起来");
            }
            // batch-max：默认 10，钳在 1..50，配置里写了出界值也不能炸
            int raw = plugin.settings().intOf("anvil-forge.batch-max", 10);
            if (mgr.batchMax() != AnvilForgeManager.clampBatchMax(raw)) {
                throw new IllegalStateException("batchMax 没走钳制: raw=" + raw
                        + " clamped=" + mgr.batchMax());
            }
            if (mgr.batchMax() < 1 || mgr.batchMax() > 50) {
                throw new IllegalStateException("batchMax 出界: " + mgr.batchMax());
            }
            if (AnvilForgeManager.clampBatchMax(0) != 1
                    || AnvilForgeManager.clampBatchMax(-7) != 1) {
                throw new IllegalStateException("batch-max 下界钳制失效");
            }
            if (AnvilForgeManager.clampBatchMax(51) != 50
                    || AnvilForgeManager.clampBatchMax(999) != 50) {
                throw new IllegalStateException("batch-max 上界钳制失效");
            }
            if (AnvilForgeManager.clampBatchMax(10) != 10
                    || AnvilForgeManager.clampBatchMax(1) != 1) {
                throw new IllegalStateException("batch-max 合法值被改动");
            }
            // 演出帧：起手不炸、砸中帧在配置的 impact-tick 上、总时长后必收锤
            int total = Math.max(6, plugin.settings().intOf("anvil-forge.animation.duration-ticks", 12));
            int impact = Math.max(3, Math.min(total - 1,
                    plugin.settings().intOf("anvil-forge.animation.impact-tick", 7)));
            if (mgr.strikeFrame(0).done()) {
                throw new IllegalStateException("第 0 帧就收锤");
            }
            if (!mgr.strikeFrame(impact).impact()) {
                throw new IllegalStateException("impact-tick=" + impact + " 那帧不是砸中帧");
            }
            // strikeFrame 语义：done = t > total（total 那一帧还要画收锤动作，total+1 才撤）
            if (!mgr.strikeFrame(total + 1).done() || mgr.strikeFrame(total).done()) {
                throw new IllegalStateException("duration-ticks=" + total + " 演完没收锤");
            }
        });

        suite.addExternal(g, "批量终止条件：纯函数优先级与边界", () -> {
            // 开敲前：到顶直接 CAP，不该浪费一次掷骰
            if (AnvilForgeManager.batchStopBefore(1, 10, 50, 50)
                    != AnvilForgeManager.BatchStop.CAP) {
                throw new IllegalStateException("到顶的剑开敲前没拦下");
            }
            if (AnvilForgeManager.batchStopBefore(1, 10, 49, 50) != null) {
                throw new IllegalStateException("没到顶的剑不该在开敲前被拦");
            }
            if (AnvilForgeManager.batchStopBefore(11, 10, 0, 50)
                    != AnvilForgeManager.BatchStop.MAX) {
                throw new IllegalStateException("超出上限的开敲请求没拦下");
            }
            // 结算后：上限 / 碎 / 满 各自终止，未触发则继续
            if (AnvilForgeManager.batchStopAfter(10, 10, false, false, false)
                    != AnvilForgeManager.BatchStop.MAX) {
                throw new IllegalStateException("敲满上限没有终止");
            }
            if (AnvilForgeManager.batchStopAfter(3, 10, false, false, false) != null) {
                throw new IllegalStateException("没触发任何条件却被终止");
            }
            if (AnvilForgeManager.batchStopAfter(3, 10, true, false, false)
                    != AnvilForgeManager.BatchStop.BROKEN) {
                throw new IllegalStateException("剑碎了没有终止");
            }
            if (AnvilForgeManager.batchStopAfter(3, 10, false, false, true)
                    != AnvilForgeManager.BatchStop.CAP) {
                throw new IllegalStateException("敲到顶没有终止");
            }
            if (AnvilForgeManager.batchStopAfter(3, 10, false, true, false)
                    != AnvilForgeManager.BatchStop.REFUSED) {
                throw new IllegalStateException("材料不够没有终止");
            }
            // 优先级：保命与保材料优先于敲满（碎 + 满 + 上限同时触发 → 按最高优先级报）
            if (AnvilForgeManager.batchStopAfter(10, 10, true, true, true)
                    != AnvilForgeManager.BatchStop.REFUSED) {
                throw new IllegalStateException("终止原因优先级不对");
            }
        });

        suite.addExternal(g, "anvils.yml 往返：批量改动后写盘能原样读回", () -> {
            ItemStack sword = plugin.items().buildDirect("SelfTest",
                    plugin.materials().get("iron"), Quality.COMMON, 60.0, null);
            if (sword == null) {
                throw new IllegalStateException("造不出测试剑");
            }
            plugin.strengthen().setLevel(sword, 7);
            plugin.strengthen().setCombo(sword, 2);
            java.util.UUID owner = java.util.UUID.nameUUIDFromBytes(
                    "SelfTestAnvilBatch".getBytes());

            java.io.File tmp = new java.io.File(plugin.getDataFolder(), "anvils-selftest-batch.yml");
            if (tmp.exists() && !tmp.delete()) {
                throw new IllegalStateException("旧的临时文件删不掉: " + tmp.getAbsolutePath());
            }
            var worlds = org.bukkit.Bukkit.getWorlds();
            var world = worlds.get(0);
            var block = world.getSpawnLocation().clone().add(0, 94, 0).getBlock();
            Material oldType = block.getType();
            block.setType(Material.ANVIL, false);
            // 独立临时管理器：绝不清空线上砧位（玩家可能正把剑放在砧上）
            var scratch = new AnvilForgeManager(plugin);
            try {
                if (scratch.placeCore(block, owner, "SelfTest", sword) == null) {
                    throw new IllegalStateException("放剑失败");
                }
                scratch.saveTo(tmp);
                if (!tmp.exists() || tmp.length() == 0) {
                    throw new IllegalStateException("砧位没写进盘: " + tmp.length());
                }
                // 模拟重启：换一个新实例从盘上读回
                var reloaded = new AnvilForgeManager(plugin);
                reloaded.loadFrom(tmp);
                if (reloaded.size() != 1) {
                    throw new IllegalStateException("读回后砧位数量不对: " + reloaded.size());
                }
                AnvilForgeSession back = reloaded.sessionAt(block);
                if (back == null) {
                    throw new IllegalStateException("读回来的砧位找不到了");
                }
                if (!back.owner().equals(owner)) {
                    throw new IllegalStateException("读回来主人变了");
                }
                if (plugin.strengthen().levelOf(back.sword()) != 7) {
                    throw new IllegalStateException("读回来的等级丢了: "
                            + plugin.strengthen().levelOf(back.sword()));
                }
                if (plugin.strengthen().comboOf(back.sword()) != 2) {
                    throw new IllegalStateException("读回来的连击丢了");
                }
            } finally {
                scratch.discardQuietly(block);
                block.setType(oldType, false);
                if (tmp.exists()) {
                    org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, tmp::delete, 1L);
                }
            }
        });
    }
}
