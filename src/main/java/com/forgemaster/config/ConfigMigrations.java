package com.forgemaster.config;

import com.forgemaster.ForgingMasterPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 统一配置迁移层（audit/04-quality.md M1）。
 *
 * <p>把原来四个各自为政的迁移入口收编成一组<b>有序 step</b>，用 config.yml 顶层的
 * {@code config-version} 统一记版本。对没有 config-version 的老盘，各 step 仍沿用
 * 原入口的「值等于旧默认才改」判定（语义逐字保留，幂等性不变）；迁移成功后写入
 * {@code config-version: 1}，此后一律以版本号为准，不再做值比对——之后每次调参
 * 只需要追加一个 step 并推进 {@link #CURRENT_VERSION}。</p>
 *
 * <p>step 列表：</p>
 * <ol>
 *   <li>step1：强化费用下调（原 {@code ForgingMasterPlugin.migrateStrengthenCosts}，2.10.4）</li>
 *   <li>step2：重铸费用下调（原 {@code ForgingMasterPlugin.migrateReforgeCosts}，2.10.5）</li>
 *   <li>step3：属性乘区重定（原 {@code ForgingMasterPlugin.migrateBalanceMultiplier}，2.17.3）</li>
 *   <li>step4..step9：铁砧姿态 / 演出迁移（原 {@code AnvilForgeManager.migrateDisplayConfig}
 *       的 pose-version 1..7 各段动作，见 {@link #runPoseSteps}）</li>
 *   <li>step10：写入 {@code anvil-forge.display.pose-version: 7} 并热加载设置</li>
 *   <li>step11：补收尾双工序的参数键（{@code settings.polishing-*} / {@code settings.inlay-*} /
 *       {@code difficulty.polish-*} / {@code difficulty.inlay-window-per-tier}，2.19.0）</li>
 *   <li>step12：强化高段曲线重定（成功率下限 / 顶段碎裂 / 失败损伤占比 / 剑魂继承，2.19.1）</li>
 *   <li>step13：补「代码在读但盘上没有」的裸键（重铸权限/确认/词条池/品质乘区、
 *       工序开场提示、强化失败记录、货币口径开关，2.19.2）</li>
 *   <li>step14：蜕变六阶乘区/费用重排 + 图纸价压平（含黑市池原价同步，2.19.2）</li>
 *   <li>step15：整段缺失的配置段按内置默认补回（黑市货架 / 图鉴奖励 / 名剑谱 / 行会等，2.19.2）</li>
 *   <li>step16：补服主数据看板的 {@code metrics} 段（产出聚合 / 基尼预警 / 快照节奏，2.20）</li>
 *   <li>step17：补剑体放大（{@code strengthen.big-sword.*}）的 7 个键：开关 / 起效等级 /
 *       三个加倍里程碑 / 倍率 / 硬上限 / 刷新间隔 / 玩家自关开关（2.20）</li>
 *   <li>step20：剑体成长曲线起点 20→0（砧上逐锤变大从第一锤开始；2.22）</li>
 * </ol>
 *
 * <p>{@code pose-version} 保留为子版本键：迁移内部先读它决定从 step4..step10 的哪一档
 * 跳进来；pose-version 已是 7 的盘这六步整体 no-op（与原方法 version≥7 直接 return 一致）。
 * 物品级 PDC 版本（ARMOR_REBALANCE）是每件物品的状态，不属于 config-version，保持独立。</p>
 *
 * <h2>自检方式（说明性文档，不进 SelfTestSuite）</h2>
 *
 * <p>手动验证步骤：</p>
 * <ol>
 *   <li>临时把 config.yml 设成旧值：{@code strengthen.cost.base: 60 / per-level: 30 /
 *       tier-factor: 1.7}、{@code strengthen.material.per-level: 1 / tier-factor: 1.15}、
 *       {@code reforge.cost.base: 320 / per-index: 180 / tier-factor: 1.75}、
 *       {@code strengthen.bonus-per-level: 0.02}、{@code evolution.stages.1..6.bonus:
 *       1.25/1.25/1.30/1.30/1.35/1.40}、删除 config-version 键、删除
 *       anvil-forge.display.pose-version 键（或设为 1）。</li>
 *   <li>启动插件（即跑 {@link #run}），断言：上述各键全部变成新值
 *       （40/18/1.45、0.6/1.10、160/90/1.45、0.006、1.08/1.08/1.10/1.10/1.12/1.12、
 *       sword-transform=NONE、sword-pitch=-90、sword-scale=0.95、sword-height=1.03、
 *       pivot-height=2.10、swing-start-deg=-25、swing-hit-deg=170、flame.* 四键存在、
 *       anvil-forge.caps 六档齐全），且顶层 {@code config-version == 1}、
 *       {@code anvil-forge.display.pose-version == 7}。</li>
 *   <li>二次启动再跑一遍 {@link #run}：config-version 已 ≥1 直接 return，全部键保持
 *       原值不变（no-op），日志无迁移输出。</li>
 *   <li>服主手调过的值（如 bonus-per-level: 0.005、swing-hit-deg: 160）一个字不动。</li>
 * </ol>
 */
public final class ConfigMigrations {

    /**
     * config-version 的最新值；追加迁移 step 时同步 +1。
     *
     * <p><b>别忘这一步</b>：{@link #run} 的第一句就是
     * {@code config-version >= CURRENT_VERSION ? return}，只加 step 不提版本号，
     * 那个 step 对已在运行的服务器**永远不会执行**（steps 17/18/19 的教训各写了一遍，
     * 详见 step19 的注释）。v2.23.1 加 step22 时从 12 抬到 13，加 step23（战力评分）时再抬到 14，加 step24（属性天花板）时抬到 15。
     * v2.26.0 加 step32（大赛历届档案 history-keep）时抬到 23。</p>
     */
    public static final int CURRENT_VERSION = 23;

    private ConfigMigrations() {
    }

    /**
     * 启动时跑全部迁移 step，跑完统一 {@code saveConfig()} 一次并写 {@code config-version}。
     *
     * <p>config-version 已 ≥ {@link #CURRENT_VERSION} 的盘直接 return，什么都不碰。</p>
     */
    public static void run(ForgingMasterPlugin plugin) {
        FileConfiguration cfg = plugin.getConfig();
        if (cfg.getInt("config-version", 0) >= CURRENT_VERSION) {
            return; // 已经迁移过：之后一律以版本号为准，不再做值比对
        }
        step1StrengthenCosts(plugin, cfg);
        step2ReforgeCosts(plugin, cfg);
        step3BalanceMultiplier(plugin, cfg);
        runPoseSteps(plugin, cfg); // step4..step10
        step11PolishInlayKeys(plugin, cfg);
        step12HighTierCurve(plugin, cfg);
        step13MissingKeys(plugin, cfg);
        step14EvolutionAndBlueprints(plugin, cfg);
        // 注意：`metrics` 段不需要单独的 step16。step15 的「整段缺失就按内置资源补回」
        // 已经覆盖了它——实测 step16 写完内存里的 cfg 后，step15 比对的是磁盘原文、
        // 看不到那份未落盘的改动，于是立刻重复补了同一批键。留着只是多一条日志和 5 次重复赋值。
        step15RestoreMissingSections(plugin, cfg);
        step17BigSwordKeys(plugin, cfg);
        step18PlayerScaleKeys(plugin, cfg);
        step19CritKeys(plugin, cfg);
        step20SwordGrowthUnify(plugin, cfg);
        step21RemoveDeadBigSwordKeys(plugin, cfg);
        step22MetricsAndWarehouse(plugin, cfg);
        step23PowerScore(plugin, cfg);
        step24BaseTolerance(plugin, cfg);
        step25DamageBudget(plugin, cfg);
        step26LegacyRepair(plugin, cfg);
        step27Enchant(plugin, cfg);
        step28MechanicEnchant(plugin, cfg);
        step29DurabilityLoreCap(plugin, cfg);
        step30Expansion24(plugin, cfg);
        step31Expansion25(plugin, cfg);
        step32ContestHistory(plugin, cfg);
        cfg.set("config-version", CURRENT_VERSION);
        plugin.saveConfig();
    }

    /**
     * 铁砧姿态 / 演出迁移的独立入口（step4..step10），供
     * {@code AnvilForgeManager.migrateDisplayConfig} 的 deprecated 兼容路径直接调用。
     *
     * <p>pose-version ≥ 7 时直接 return（与原实现一致：不落盘、不写日志）；
     * 否则跑完写 pose-version: 7 并 {@code saveConfig()} 一次。</p>
     */
    public static void migrateDisplayPose(ForgingMasterPlugin plugin) {
        FileConfiguration cfg = plugin.getConfig();
        if (cfg.getInt("anvil-forge.display.pose-version", 1) >= 7) {
            return;
        }
        runPoseSteps(plugin, cfg);
        plugin.saveConfig();
    }

    // ------------------------------------------------------------ step1 强化费用（2.10.4）

    /**
     * 2.10.4：强化费用下调。
     *
     * <p>光改 config.yml 里的默认值对老服没用——人家盘上那份配置已经写着旧数字，
     * 配置优先级永远高于代码默认值。所以这里做一次「只在没被动过时才改」的迁移：
     * 三个费用键同时等于旧默认值（60 / 30 / 1.7）才判定为「服主没调过」，整体换新曲线。
     * 只要有一个对不上，就当服主自己有数，一个字都不碰。</p>
     */
    private static void step1StrengthenCosts(ForgingMasterPlugin plugin, FileConfiguration cfg) {
        boolean costUntouched = near(cfg.getDouble("strengthen.cost.base", -1), 60)
                && near(cfg.getDouble("strengthen.cost.per-level", -1), 30)
                && near(cfg.getDouble("strengthen.cost.tier-factor", -1), 1.7);
        boolean materialUntouched = near(cfg.getDouble("strengthen.material.per-level", -1), 1)
                && near(cfg.getDouble("strengthen.material.tier-factor", -1), 1.15);
        if (!costUntouched && !materialUntouched) {
            return;
        }
        if (costUntouched) {
            cfg.set("strengthen.cost.base", 40);
            cfg.set("strengthen.cost.per-level", 18);
            cfg.set("strengthen.cost.tier-factor", 1.45);
        }
        if (materialUntouched) {
            cfg.set("strengthen.material.per-level", 0.6);
            cfg.set("strengthen.material.tier-factor", 1.10);
        }
        plugin.getLogger().info("强化费用已按 2.10.4 新曲线下调"
                + (costUntouched ? "：手续费 60/30/1.7 → 40/18/1.45" : "")
                + (materialUntouched ? "，材料 1/1.15 → 0.6/1.10" : "")
                + "（原值等于旧默认值，判定为未改动；想用旧曲线自行改回去即可）");
    }

    // ------------------------------------------------------------ step2 重铸费用（2.10.5）

    /**
     * 2.10.5：重铸费用下调，规则同 step1。
     *
     * <p>三个键同时等于旧默认值（320 / 180 / 1.75）才判定为「服主没调过」，整体换新曲线；
     * 有一个对不上就一个字都不碰。</p>
     */
    private static void step2ReforgeCosts(ForgingMasterPlugin plugin, FileConfiguration cfg) {
        boolean untouched = near(cfg.getDouble("reforge.cost.base", -1), 320)
                && near(cfg.getDouble("reforge.cost.per-index", -1), 180)
                && near(cfg.getDouble("reforge.cost.tier-factor", -1), 1.75);
        if (!untouched) {
            return;
        }
        cfg.set("reforge.cost.base", 160);
        cfg.set("reforge.cost.per-index", 90);
        cfg.set("reforge.cost.tier-factor", 1.45);
        plugin.getLogger().info("重铸费用已按 2.10.5 新曲线下调：320/180/1.75 → 160/90/1.45"
                + "（原值等于旧默认值，判定为未改动；想用旧曲线自行改回去即可）");
    }

    // ------------------------------------------------------------ step3 属性乘区（2.17.3）

    /**
     * v2.17.3 乘区重定：强化每级 +2% → +0.6%，蜕变各阶 bonus 1.25~1.40 → 1.08~1.12。
     *
     * <p>旧值叠起来是「强化 ×1.6 × 蜕变 ×5.0 ≈ ×8.0」，+60 满配 Lore 直接写 +699%，
     * 25 点伤害变 200 上下。新值神话 +99 满蜕变 ≈ ×2.8（25 → 70 左右），且全程线性
     * 不提前触顶——神话上限是 +99，每级 +1% 会在 +60 就撞 0.60 的保险夹，后面
     * 39 级白敲。0.01 → 0.006 就是把曲线铺满整个 +99。只在原值仍等于旧默认时
     * 改写，服主调过的一个字不动；想用旧曲线自行改回去即可。</p>
     */
    private static void step3BalanceMultiplier(ForgingMasterPlugin plugin, FileConfiguration cfg) {
        double per = cfg.getDouble("strengthen.bonus-per-level", -1);
        boolean perLevel = near(per, 0.02) || near(per, 0.01);
        double[] oldBonus = {1.25, 1.25, 1.30, 1.30, 1.35, 1.40};
        double[] newBonus = {1.08, 1.08, 1.10, 1.10, 1.12, 1.12};
        boolean stagesUntouched = true;
        for (int i = 1; i <= 6; i++) {
            if (!near(cfg.getDouble("evolution.stages." + i + ".bonus", -1), oldBonus[i - 1])) {
                stagesUntouched = false;
                break;
            }
        }
        if (!perLevel && !stagesUntouched) {
            return;
        }
        if (perLevel) {
            cfg.set("strengthen.bonus-per-level", 0.006);
        }
        if (stagesUntouched) {
            for (int i = 1; i <= 6; i++) {
                cfg.set("evolution.stages." + i + ".bonus", newBonus[i - 1]);
            }
        }
        plugin.getLogger().info("属性乘区已按 2.17.3 重定"
                + (perLevel ? "：强化 0.02/级 → 0.006/级（曲线铺满 +99，不再 +60 触顶）" : "")
                + (stagesUntouched ? "，蜕变阶 bonus 1.25~1.40 → 1.08~1.12"
                        + "（满配总乘区 ×8.0 → ×2.8 左右）" : "")
                + "（原值等于旧默认值，判定为未改动）");
    }

    // ------------------------------------------------------------ step4..step10 姿态 / 演出

    /**
     * 铁砧姿态 / 落锤演出迁移（原 {@code AnvilForgeManager.migrateDisplayConfig}），
     * 按 pose-version 映射成 step4..step10：
     *
     * <ol>
     *   <li>step4（pose-version &lt; 2）：sword-transform / pitch / scale / height 补新默认</li>
     *   <li>step5（pose-version &lt; 3）：演出锤子 MACE / impact-tick / billboard / hammer-scale / duration</li>
     *   <li>step6（pose-version &lt; 4）：anvil-forge.caps 六档品质上限</li>
     *   <li>step7（pose-version &lt; 5）：pivot-height / handle-length / swing-* / flame.* 新键</li>
     *   <li>step8（pose-version &lt; 6）：swing-hit 35 → 150、swing-start −55 → 30</li>
     *   <li>step9（pose-version &lt; 7）：pivot 1.55 → 2.10、swing-start −25、swing-hit 170</li>
     *   <li>step10：写入 pose-version: 7 并热加载设置</li>
     * </ol>
     *
     * <p>pose-version ≥ 7 时整体 no-op（与原方法 version≥7 直接 return 一致：
     * 不落盘、不写日志、不覆盖任何键）。pose-version 键保留为子版本，
     * 迁移内部先读它决定从哪一档跳进来。</p>
     */
    private static void runPoseSteps(ForgingMasterPlugin plugin, FileConfiguration cfg) {
        String base = "anvil-forge.display.";
        int version = cfg.getInt(base + "pose-version", 1);
        if (version >= 7) {
            return; // 已经是最新的姿态与演出配置，服主后来改什么都不再动
        }
        if (version < 2) { // step4
            // 只把「还是老默认值」的项推到新默认，服主调过的值一律保留
            if (!cfg.isSet(base + "sword-transform")) {
                cfg.set(base + "sword-transform", "NONE");
            }
            if (Math.abs(cfg.getDouble(base + "sword-pitch", 0)) < 0.001) {
                cfg.set(base + "sword-pitch", -90);
            }
            if (Math.abs(cfg.getDouble(base + "sword-scale", 0.70) - 0.70) < 0.001) {
                cfg.set(base + "sword-scale", 0.95);
            }
            if (Math.abs(cfg.getDouble(base + "sword-height", 1.02) - 1.02) < 0.001) {
                cfg.set(base + "sword-height", 1.03);
            }
            // 老注释是 GROUND 时代的说法（“0 = 按 GROUND 平放”），换掉，免得下次又照注释去踩
            setComment(cfg, base + "sword-transform", java.util.List.of(
                    "剑的姿态：NONE = 自己算角度（默认，剑真的平躺在砧面上、原尺寸）",
                    "          GROUND = 老行为，用原版掉落物姿态（缩一半 + 下沉 2/16，看着像插进砧子里）",
                    "          FIXED = 立着（只有调试用）"));
            setComment(cfg, base + "sword-height", java.util.List.of(
                    "剑平躺时的高度：砧面在 y+1.0，1.03 让剑身恰好贴在面上不穿模"));
            setComment(cfg, base + "sword-pitch", java.util.List.of(
                    "NONE 姿态下 -90 = 把立着的模型扳倒成平躺；GROUND 姿态下要保持 0"));
            setComment(cfg, base + "sword-scale", java.util.List.of(
                    "剑的大小：NONE 没有原版那层 0.5 缩放，0.95 刚好铺满砧面不出边"));
        }
        if (version < 3) { // step5
            // v2.16.3：演出从「铁斧在平面里扭」改成「重锤竖直砸下」。
            // v2.17.3 起 version<3 分支不再写 roll-deg/windup-deg/start-height/hit-height
            // 这 4 个死键（代码早已不读，迁移器不该制造垃圾键，见 audit/04-quality.md C4）。
            String anim = "anvil-forge.animation.";
            if ("IRON_AXE".equalsIgnoreCase(String.valueOf(cfg.get(anim + "hammer-item", "")))) {
                cfg.set(anim + "hammer-item", "MACE");
            }
            if (!cfg.isSet(anim + "impact-tick")) {
                cfg.set(anim + "impact-tick", 7);
            }
            if (!cfg.isSet(anim + "billboard")) {
                cfg.set(anim + "billboard", "CENTER");
            }
            if (!cfg.isSet(anim + "hammer-scale")) {
                cfg.set(anim + "hammer-scale", 1.10);
            }
            if (cfg.getInt(anim + "duration-ticks", 10) == 10) {
                cfg.set(anim + "duration-ticks", 12); // 老默认 10 tick 太赶，砸不完整
            }
            setComment(cfg, anim + "hammer-item", java.util.List.of(
                    "演出里画的那把锤子：MACE = 原版重锤（锤头朝下砸下来）"));
        }
        if (version < 4) { // step6
            // v2.17：强化上限从「全物品一条 99」改成「按锻造品质逐级提高」。
            // 只补缺失的品质键，服主已经调过的值一律保留。
            String caps = "anvil-forge.caps.";
            java.util.Map<String, Integer> defaults = new java.util.LinkedHashMap<>();
            defaults.put("ROUGH", 40);
            defaults.put("COMMON", 50);
            defaults.put("FINE", 60);
            defaults.put("EPIC", 72);
            defaults.put("LEGENDARY", 86);
            defaults.put("MYTHIC", 99);
            boolean anyMissing = false;
            for (var e : defaults.entrySet()) {
                if (!cfg.isSet(caps + e.getKey())) {
                    cfg.set(caps + e.getKey(), e.getValue());
                    anyMissing = true;
                }
            }
            if (anyMissing) {
                setComment(cfg, "anvil-forge.max-level", java.util.List.of(
                        "强化上限：v2.17 起按锻造品质逐级提高，见下面的 caps",
                        "这条只剩两个用处：老物品没有品质键时兜底，以及成功率 / 碎裂率曲线的分母"));
                setComment(cfg, "anvil-forge.caps", java.util.List.of(
                        "各品质的强化上限（严格递增）：粗糙 40 → 普通 50 → 精良 60 → 史诗 72 → 传说 86 → 神话 99",
                        "改小会让超限的老剑被夹回上限；改大只是放开天花板，不会白送等级"));
            }
        }
        if (version < 5) { // step7
            // v2.17.2：演出从「整体垂直下落」改成「柄底不动、锤头绕柄砸下来」，并加火焰。
            // 新键一律补默认值；v2.16 的四个旧键保留（服主可能还在读），只是不再生效。
            String anim = "anvil-forge.animation.";
            java.util.Map<String, Object> dflt = new java.util.LinkedHashMap<>();
            dflt.put("pivot-height", 1.55);
            dflt.put("handle-length", 0.46);
            dflt.put("swing-start-deg", -55);
            dflt.put("swing-hit-deg", 35);
            dflt.put("swing-rebound-deg", 12);
            dflt.put("flame.enabled", true);
            dflt.put("flame.trail", 2);
            dflt.put("flame.count", 18);
            dflt.put("flame.spread", 0.30);
            boolean anyNew = false;
            for (var e : dflt.entrySet()) {
                if (!cfg.isSet(anim + e.getKey())) {
                    cfg.set(anim + e.getKey(), e.getValue());
                    anyNew = true;
                }
            }
            if (anyNew) {
                setComment(cfg, anim + "pivot-height", java.util.List.of(
                        "柄底（手的位置）高度，从方块底面算：砧面 1.0，1.55 = 砧面上方 0.55",
                        "锤柄钉在这个点上不动，锤头绕它画弧砸下来"));
                setComment(cfg, anim + "handle-length", java.util.List.of(
                        "柄底到锤身中心的长度（模型单位 × hammer-scale）：决定画弧的半径"));
                setComment(cfg, anim + "swing-start-deg", java.util.List.of(
                        "起手角度：负数 = 锤头向后扬起（0 = 锤头朝上，90 = 锤头朝下）"));
                setComment(cfg, anim + "swing-hit-deg", java.util.List.of(
                        "砸中角度：正直向下再越过一点，锤头正好压到剑上"));
                setComment(cfg, anim + "flame.enabled", java.util.List.of(
                        "落锤带火：飞行段火星拖尾 + 砸中一捧火"));
                setComment(cfg, anim + "roll-deg", java.util.List.of(
                        "【已废弃 v2.17.2】动作改成绕柄旋转后这个值不再生效，留着只为老配置不报错"));
                setComment(cfg, anim + "start-height", java.util.List.of(
                        "【已废弃 v2.17.2】锤子不再整体下移，改由 pivot-height + swing-*-deg 决定轨迹"));
                setComment(cfg, anim + "hit-height", java.util.List.of(
                        "【已废弃 v2.17.2】同上"));
            }
        }
        if (version < 6) { // step8
            // v2.17.2 二修：35° 那个「砸中角度」根本没把锤头转下去（还在柄上方），
            // 实际要转到 120°~160° 才够得着剑面。只改「还是上一版默认值」的配置。
            String anim = "anvil-forge.animation.";
            double hitNow = cfg.getDouble(anim + "swing-hit-deg", 150);
            if (Math.abs(hitNow - 35) < 0.01) {
                cfg.set(anim + "swing-hit-deg", 150);
                setComment(cfg, anim + "swing-hit-deg", java.util.List.of(
                        "砸中角度：转过竖直（90°）继续往下压，150 = 锤头落到剑面高度",
                        "35° 那种小角度是「举着锤子不动」，v2.17.2 起默认改成 150"));
            }
            double startNow = cfg.getDouble(anim + "swing-start-deg", 30);
            if (Math.abs(startNow + 55) < 0.01) {
                cfg.set(anim + "swing-start-deg", 30);
            }
        }
        if (version < 7) { // step9
            // v2.17.3 三修：start=30 / hit=150 关于竖直线对称，锤头轨迹的弦正好是一条
            // 竖直线（弧线外扩只有 0.25 格），加上 pivot 1.55 太低、锤头全长 ~1.05 格，
            // 砸中时锤头埋进砧子——整段看着就是「从上面垂直掉下来」。改成明显的抡弧：
            // 起手 −25°（锤头向后扬）、砸中 170°（锤头压到剑面上）、pivot 抬到 2.10。
            // 只改「还是上一版默认值」的键，服主调过的保留。
            String anim = "anvil-forge.animation.";
            boolean moved = false;
            if (Math.abs(cfg.getDouble(anim + "pivot-height", 2.10) - 1.55) < 0.01) {
                cfg.set(anim + "pivot-height", 2.10);
                moved = true;
            }
            if (Math.abs(cfg.getDouble(anim + "swing-start-deg", -25) - 30) < 0.01) {
                cfg.set(anim + "swing-start-deg", -25);
                moved = true;
            }
            double hitNow7 = cfg.getDouble(anim + "swing-hit-deg", 170);
            if (Math.abs(hitNow7 - 150) < 0.01 || Math.abs(hitNow7 - 35) < 0.01) {
                cfg.set(anim + "swing-hit-deg", 170);
                moved = true;
            }
            if (moved) {
                setComment(cfg, anim + "pivot-height", java.util.List.of(
                        "柄底（手的位置）高度，从方块底面算：砧面 1.0，2.10 = 砧面上方 1.10 格",
                        "锤柄钉在这个点上不动，锤头绕它画弧砸下来；锤头全长约 1.05 格，",
                        "pivot 太低砸中时锤头会埋进砧子，2.10 时砸中点正好落在剑面上"));
                setComment(cfg, anim + "swing-start-deg", java.util.List.of(
                        "起手角度：负数 = 锤头向后扬起（0 = 锤头朝上，正角 = 越过竖直往下压）",
                        "-25 = 明显的举锤预备；跟砸中角差得越大，弧线越看得出来"));
                setComment(cfg, anim + "swing-hit-deg", java.util.List.of(
                        "砸中角度：170 = 锤头几乎转到正下方，落在剑面上方一点",
                        "注意 start 与 hit 对称（如 30/150）会让锤头走成一条竖直线，看着像垂直掉落"));
            }
        }
        // step10：写入子版本并热加载设置
        cfg.set(base + "pose-version", 7);
        plugin.settings().load(cfg); // 内存里的值立刻跟上，别等下次 reload
        plugin.getLogger().info("配置迁移：铁砧上的剑改成自己算姿态（sword-transform: NONE），"
                + "落锤演出改成「柄底不动、锤头绕柄砸下 + 火焰」（animation.pivot-height / swing-*-deg / flame），"
                + "强化上限改成按品质逐级提高（anvil-forge.caps，粗糙 40 → 神话 99）。");
    }

    /**
     * step11（2.19.0）：补收尾双工序（抛光 / 嵌银丝）的参数键。
     *
     * <p>只写<b>文件里真的没有</b>的键。判定用盘上那份 config.yml 的独立副本
     * （{@link YamlConfiguration#loadConfiguration} 不带 defaults）：{@code plugin.getConfig()}
     * 挂着 jar 内的 defaults，很多 Bukkit 版本上 {@code contains} 会顺着 defaults 答「有」，
     * 拿它判存在性会把新键永远漏掉。服主自己加过、调过的值一个字不动——这些键全是新增项，
     * 插进去的值就是出厂默认，所以补键本身不改变任何行为；已经在用的盘重跑一遍是 no-op。</p>
     *
     * <p>盘读不出来就整体跳过（日志提一句）：宁可少补键，也不赌一把去覆盖服主的调参。</p>
     */
    private static void step11PolishInlayKeys(ForgingMasterPlugin plugin, FileConfiguration cfg) {
        YamlConfiguration disk;
        try {
            disk = YamlConfiguration.loadConfiguration(
                    new java.io.File(plugin.getDataFolder(), "config.yml"));
        } catch (Throwable t) {
            plugin.getLogger().warning("配置迁移 step11 跳过：读不出盘上的 config.yml（" + t + "）");
            return;
        }
        int added = 0;

        added += putIfAbsent(cfg, disk, "settings.polishing-enabled", true, java.util.List.of(
                "抛光：光泽指针往返扫，在亮区上下刀（关掉后这道工序跳过，权重让给其它工序）"));
        added += putIfAbsent(cfg, disk, "settings.polishing-passes", 3, java.util.List.of(
                "抛光一共下几刀（1~6）"));
        added += putIfAbsent(cfg, disk, "settings.polishing-speed", 2.6, java.util.List.of(
                "指针每 tick 扫过的百分点（0.6~8.0，越大越难）"));
        added += putIfAbsent(cfg, disk, "settings.polishing-window", 16.0, java.util.List.of(
                "亮区半宽（百分点，4.0~40.0，越小越难）"));
        added += putIfAbsent(cfg, disk, "settings.inlay-enabled", true, java.util.List.of(
                "嵌银丝：银丝节点逐个亮起，亮窗内点中才算嵌上"));
        added += putIfAbsent(cfg, disk, "settings.inlay-window-ticks", 14, java.util.List.of(
                "单根银丝的亮窗时长（tick，5~40，越小越难）"));
        added += putIfAbsent(cfg, disk, "settings.inlay-length", 3, java.util.List.of(
                "银丝基础根数（2~8，实际根数还随材料档位 +1）"));
        added += putIfAbsent(cfg, disk, "difficulty.polish-speed-per-tier", 0.16, java.util.List.of(
                "每高一档，抛光指针 +16%（越快越难）"));
        added += putIfAbsent(cfg, disk, "difficulty.polish-window-per-tier", 0.09, java.util.List.of(
                "每高一档，抛光亮区 -9%（最低 55%）"));
        added += putIfAbsent(cfg, disk, "difficulty.inlay-window-per-tier", 0.10, java.util.List.of(
                "每高一档，银丝亮窗 -10%（最低 50%）"));

        if (added > 0) {
            plugin.getLogger().info("配置迁移：补上收尾双工序的 " + added + " 个新键"
                    + "（settings.polishing-* / settings.inlay-* / difficulty.polish-* / "
                    + "difficulty.inlay-*），值均为出厂默认，已有键不动。");
        }
    }

    // ------------------------------------------------------------ step12 强化高段曲线（2.19.1）

    /**
     * step12（2.19.1）：强化「高段变墙」重定。
     *
     * <p>实测口径（{@code simulateStats} 的马尔可夫链，裸锤、不含迭浪/符）：</p>
     * <pre>
     *   旧值（floar .10 / break.max .30 / damage-share .5）
     *     +60 达成率 0.72%   +72 0.011%（约 9000 把剑）   +99 5e-10（本质不可能）
     *   新值（floor .14 / break.max .18 / damage-share .35）
     *     +60 达成率 5.9%    +72 0.55%（约 180 把剑）     +86/+99 仍要靠符
     * </pre>
     *
     * <p>结论：品质上限写到 99，但旧曲线在 +70 往上是一条死胡卢——碎裂风险在几百锤里
     * 复利，任何非零碎率都会把达成率稀释成 0。这轮只做三件事，都不改判定逻辑：</p>
     * <ul>
     *   <li>{@code success.floor} 0.10 → 0.14：顶段不再那么绝望；</li>
     *   <li>{@code break.max} 0.30 → 0.18：顶段碎裂从 ~27% 压到 ~16%；</li>
     *   <li>{@code damage-share} 0.5 → 0.35：失败里「空锤」变多、降级变少，
     *       负漂移收窄；</li>
     *   <li>{@code inherit} rate 0.25 → 0.35、cap 10 → 20：碎剑回收更实，
     *       高段碎了不至于从零再爬。</li>
     * </ul>
     *
     * <p>判定规则与 step1/2/3 一致：四个键<b>同时</b>等于旧默认值才判定为「服主没调过」，
     * 整体换新；有一个对不上就一个字都不碰。</p>
     */
    private static void step12HighTierCurve(ForgingMasterPlugin plugin, FileConfiguration cfg) {
        boolean untouched = near(cfg.getDouble("anvil-forge.success.floor", -1), 0.10)
                && near(cfg.getDouble("anvil-forge.break.max", -1), 0.30)
                && near(cfg.getDouble("strengthen.tickets.damage-share", -1), 0.5);
        if (!untouched) {
            return;
        }
        cfg.set("anvil-forge.success.floor", 0.14);
        cfg.set("anvil-forge.break.max", 0.18);
        cfg.set("strengthen.tickets.damage-share", 0.35);
        boolean inheritUntouched = near(cfg.getDouble("strengthen.inherit.rate", -1), 0.25)
                && cfg.getInt("strengthen.inherit.cap", -1) == 10;
        if (inheritUntouched) {
            cfg.set("strengthen.inherit.rate", 0.35);
            cfg.set("strengthen.inherit.cap", 20);
        }
        plugin.getLogger().info("强化高段曲线已按 2.19.1 重定："
                + "成功率下限 0.10 → 0.14，顶段碎裂 0.30 → 0.18，"
                + "失败损伤占比 0.5 → 0.35"
                + (inheritUntouched ? "，剑魂继承 0.25/+10 → 0.35/+20" : "")
                + "（原值等于旧默认值，判定为未改动；想用旧曲线自行改回去即可）");
    }

    // ------------------------------------------------------------ step13 补漂移键（2.19.2）

    /**
     * step13（2.19.2）：把「代码一直在读、但 config.yml 里根本没有」的键补进盘上的配置。
     *
     * <p>这轮做了一次全量比对（代码里 {@code settings().num/intOf/flag/str/strList} 的
     * 实参 vs 盘上的键树），逮到 7 个裸键。它们的共同问题是：<b>代码有默认值，所以功能一直
     * 正常，但服主完全看不见、改不了</b>——README 或类注释里却写着「可配置」。其中
     * {@code reforge.cost.quality.*} 更隐蔽：一张 0.80/1.00/1.25/1.70/2.40/3.40 的品质
     * 乘区表只存在于 Java 的 {@code switch} 里，星陨神话重铸一条的实际费用因此是
     * 160 × 1.45⁴ × 3.40 ≈ 2,405 剑币，而盘上只看得到 {@code cost.base: 160}。</p>
     *
     * <p>补法与 step11 一致：{@link #putIfAbsent}，只在盘上缺这个键时才写，
     * 写入的值就是代码里的默认值——所以<b>行为零变化</b>，只是让这些旋钮变得可见。</p>
     */
    private static void step13MissingKeys(ForgingMasterPlugin plugin, FileConfiguration cfg) {
        YamlConfiguration disk;
        try {
            disk = YamlConfiguration.loadConfiguration(
                    new java.io.File(plugin.getDataFolder(), "config.yml"));
        } catch (Throwable t) {
            plugin.getLogger().warning("配置迁移 step13 跳过：读不出盘上的 config.yml（" + t + "）");
            return;
        }
        int added = 0;

        // 重铸：界面权限 / 确认窗口 / 装备词条池
        added += putIfAbsent(cfg, disk, "reforge.permission", "forgemaster.use",
                java.util.List.of("进重铸界面需要的权限"));
        added += putIfAbsent(cfg, disk, "reforge.confirm-ticks", 60L,
                java.util.List.of("点「确认重铸」后维持多久有效（tick，60 = 3 秒）"));
        added += putIfAbsent(cfg, disk, "reforge.armor-pool",
                new java.util.ArrayList<>(java.util.List.of(
                        "armor_plate", "tough_plate", "vitality", "knockback_resist", "aegis")),
                java.util.List.of("装备重铸的防御词条池（抽不满也不回退到武器词条）"));

        // 重铸：品质乘区（原先只写在代码 switch 里）
        added += putIfAbsent(cfg, disk, "reforge.cost.quality.rough", 0.80,
                java.util.List.of("品质乘区：粗糙"));
        added += putIfAbsent(cfg, disk, "reforge.cost.quality.common", 1.00,
                java.util.List.of("品质乘区：普通"));
        added += putIfAbsent(cfg, disk, "reforge.cost.quality.fine", 1.25,
                java.util.List.of("品质乘区：精良"));
        added += putIfAbsent(cfg, disk, "reforge.cost.quality.epic", 1.70,
                java.util.List.of("品质乘区：史诗"));
        added += putIfAbsent(cfg, disk, "reforge.cost.quality.legendary", 2.40,
                java.util.List.of("品质乘区：传说"));
        added += putIfAbsent(cfg, disk, "reforge.cost.quality.mythic", 3.40,
                java.util.List.of("品质乘区：神话"));

        // 其余三个裸键
        added += putIfAbsent(cfg, disk, "settings.stage-intro-ticks", 14,
                java.util.List.of("每道工序开场提示停留多少 tick（0 = 不提示）"));
        added += putIfAbsent(cfg, disk, "strengthen.fail.tomb-record", true,
                java.util.List.of("强化失败（碎剑 / 掉级）是否记进剑冢挑战记录"));

        // 上一轮（2.19.1）新加的货币口径开关：老服盘上也没有
        added += putIfAbsent(cfg, disk, "economy.coin-sinks", "plugin",
                java.util.List.of("宝石 / 黑市 / 签到 / 重铸 扣哪本账",
                        "plugin（默认）= 剑币，与商会 / 拍卖同一本账；server = 走 Vault/CMI",
                        "锻造费不受此开关影响，永远走服务器经济"));

        if (added > 0) {
            plugin.getLogger().info("配置迁移 step13：补了 " + added
                    + " 个「代码在读但盘上没有」的键（值等于代码默认，行为不变，"
                    + "只是让这些旋钮在 config.yml 里可见）");
        }
    }

    // ------------------------------------------------------------ step14 蜕变曲线 + 图纸价（2.19.2）

    /**
     * step14（2.19.2）：蜕变费用/乘区重排 + 图纸价格压平。
     *
     * <p><b>蜕变的问题不是「贵」，是「白贵」</b>：旧表 1.08/1.08/1.10/1.10/1.12/1.12
     * 里第 6 阶和第 5 阶乘区一模一样（都是 1.12），而第 6 阶要 45,000 剑币、
     * 成功率只有 40%（带保底期望 2.18 次）——<b>期望 97,920 剑币买一个称号</b>。
     * 第 3、4 阶同理（都是 1.10）。全表只有三个不同的乘区值，成对出现。</p>
     *
     * <p>新表把乘区改成严格单调（1.06/1.07/1.09/1.10/1.12/1.15），
     * <b>连乘 ×1.752 ≈ 旧 ×1.770</b>——总上限不动（2.17.3 那条「别再叠」的约束照旧），
     * 只是把「每阶都得有实打实的增益」这件事补上。费用同时下压到
     * 600/1400/3000/6000/12000/24000，全表期望 155,246 → 89,105 剑币
     * （1,522 → 874 把 FINE 剑）。</p>
     *
     * <p><b>图纸</b>：星陨图纸 1600 比护胚符 1500 还贵，可护胚符能救一条命、
     * 图纸只给一次性 +15% 加热容差——性价比顺序反了。压到 800，
     * 整条阶梯改成贴近「用途不随档位变」的平缓曲线。</p>
     *
     * <p>判定沿用老规矩：<b>逐项</b>比旧值，等于旧默认才改；服主调过的任何一阶、
     * 任何一个图纸价都一个字不动。</p>
     */
    private static void step14EvolutionAndBlueprints(ForgingMasterPlugin plugin, FileConfiguration cfg) {
        // ---- 蜕变六阶：bonus 与 cost-coins 必须同时等于旧值才整体换 ----
        double[][] oldBonus = {{1.08}, {1.08}, {1.10}, {1.10}, {1.12}, {1.12}};
        int[] oldCost = {600, 1500, 4000, 9000, 20000, 45000};
        double[] newBonus = {1.06, 1.07, 1.09, 1.10, 1.12, 1.15};
        int[] newCost = {600, 1400, 3000, 6000, 12000, 24000};
        int stagesChanged = 0;
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            String base = "evolution.stages." + (i + 1);
            double b = cfg.getDouble(base + ".bonus", -1);
            int c = cfg.getInt(base + ".cost-coins", -1);
            if (!near(b, oldBonus[i][0]) || c != oldCost[i]) {
                continue; // 服主调过这一阶，别碰
            }
            if (near(newBonus[i], b) && newCost[i] == c) {
                continue; // 新旧本来就一样（第 1 阶）
            }
            cfg.set(base + ".bonus", newBonus[i]);
            cfg.set(base + ".cost-coins", newCost[i]);
            stagesChanged++;
            detail.append(detail.length() == 0 ? "" : "、")
                    .append("阶").append(i + 1).append(" ×").append(b).append("→×")
                    .append(newBonus[i]).append(" / ").append(c).append("→").append(newCost[i]);
        }

        // ---- 图纸价：逐项看是否还是旧价 ----
        String[] bpIds = {"copper", "diamond", "netherite", "starfall"};
        int[] bpOld = {260, 480, 900, 1600};
        int[] bpNew = {240, 360, 560, 800};
        int bpChanged = 0;
        for (int i = 0; i < bpIds.length; i++) {
            int cur = cfg.getInt("shop.blueprints." + bpIds[i], -1);
            if (cur == bpOld[i]) {
                cfg.set("shop.blueprints." + bpIds[i], bpNew[i]);
                bpChanged++;
            }
        }

        // ---- 黑市池里的图纸原价同步（否则黑市卖得比商会还贵）----
        int poolChanged = 0;
        try {
            var raw = cfg.getList("blackmarket.pool");
            if (raw != null) {
                java.util.List<Object> rebuilt = new java.util.ArrayList<>(raw.size());
                boolean touched = false;
                for (Object o : raw) {
                    if (!(o instanceof java.util.Map<?, ?> m)) {
                        rebuilt.add(o);
                        continue;
                    }
                    Object type = m.get("type");
                    Object id = m.get("id");
                    Object price = m.get("price");
                    Integer nu = null;
                    if ("blueprint".equals(String.valueOf(type)) && price instanceof Number n) {
                        for (int i = 0; i < bpIds.length; i++) {
                            if (bpIds[i].equals(String.valueOf(id)) && n.intValue() == bpOld[i]) {
                                nu = bpNew[i];
                                break;
                            }
                        }
                    }
                    if (nu == null) {
                        rebuilt.add(o);
                        continue;
                    }
                    java.util.Map<Object, Object> copy = new java.util.LinkedHashMap<>(m);
                    copy.put("price", nu);
                    rebuilt.add(copy);
                    touched = true;
                    poolChanged++;
                }
                if (touched) {
                    cfg.set("blackmarket.pool", rebuilt);
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("step14：黑市池图纸价同步失败（" + t + "），商会价已改，黑市保持原价");
        }

        if (stagesChanged == 0 && bpChanged == 0 && poolChanged == 0) {
            return;
        }
        plugin.getLogger().info("配置迁移 step14：蜕变 " + stagesChanged + " 阶 / 商会图纸 "
                + bpChanged + " 项 / 黑市原价 " + poolChanged + " 项已按 2.19.2 重排"
                + (detail.length() == 0 ? "" : "（" + detail + "）")
                + "（只改「值等于旧默认」的项，服主调过的不动；想用旧曲线自行改回去）");
    }

    // ------------------------------------------------------------ step15 整段缺失自愈（2.19.2）

    /**
     * step15（2.19.2）：把「整段从盘上消失」的配置段按内置默认补回来。
     *
     * <p>缘起：拿测试服的 config.yml 跟内置资源做<b>节点级</b>比对，发现盘上少了
     * <b>13 个整段、187 个节点</b>——{@code blackmarket} / {@code codex} / {@code guild} /
     * {@code spectrum} / {@code relic-set} / {@code dissolve} / {@code season-pass} /
     * {@code daily} / {@code tombrun} / {@code titles} / {@code order} / {@code hall} /
     * {@code placeholder}。</p>
     *
     * <p>为什么会这样：以前所有迁移都是「补某个标量键」，而 {@link #putIfAbsent} 只认点号路径。
     * 只要服主的 config.yml 生成得早，后来新加的功能段就永远进不去——这些段不会报错、
     * 不会告警，功能的默认值全靠 Java 兜底。</p>
     *
     * <p><b>最要命的是黑市</b>：{@code blackmarket.pool} 在代码里<b>没有兜底</b>
     * （{@code BlackmarketService.pool()} 直接读 {@code getMapList}，空就是空），
     * 于是 {@code offers()} 恒返回空列表——界面能打开，货架永远空着，日志一声不响。
     * 同类风险还有 {@code codex.rewards}、{@code spectrum}、{@code titles} 这些纯内容段。</p>
     *
     * <p>补法刻意保守：<b>只处理「整段一个节点都不在盘上」的顶层段</b>，
     * 段内只要有一个键存在就完全不碰（那说明服主在管这段）。补的是内置资源里的原值，
     * 所以行为只会回到「设计预期」，不会盖掉任何调参。</p>
     */
    private static void step15RestoreMissingSections(ForgingMasterPlugin plugin, FileConfiguration cfg) {
        YamlConfiguration disk;
        try {
            disk = YamlConfiguration.loadConfiguration(
                    new java.io.File(plugin.getDataFolder(), "config.yml"));
        } catch (Throwable t) {
            plugin.getLogger().warning("配置迁移 step15 跳过：读不出盘上的 config.yml（" + t + "）");
            return;
        }
        YamlConfiguration res;
        try (java.io.InputStream in = plugin.getResource("config.yml")) {
            if (in == null) {
                plugin.getLogger().warning("配置迁移 step15 跳过：jar 里没有 config.yml 资源");
                return;
            }
            res = YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable t) {
            plugin.getLogger().warning("配置迁移 step15 跳过：读不出内置 config.yml（" + t + "）");
            return;
        }

        int restored = 0;
        int nodes = 0;
        StringBuilder names = new StringBuilder();
        java.util.Set<String> coveredBy = new java.util.HashSet<>();

        for (String path : res.getKeys(true)) {
            if ("config-version".equals(path) || path.startsWith("config-version.")) {
                continue;
            }
            if (disk.contains(path)) {
                continue; // 盘上有 → 服主在管，不碰
            }
            // 祖先已经整块补过的话，这棵子树已经跟着拷进来了
            boolean covered = false;
            for (String pre : coveredBy) {
                if (path.startsWith(pre + ".")) {
                    covered = true;
                    break;
                }
            }
            if (covered) {
                continue;
            }

            Object v = res.get(path);
            if (v instanceof org.bukkit.configuration.ConfigurationSection) {
                // 整块缺失：把这块下面的所有叶子一次拷过来
                int inner = 0;
                for (String sub : res.getKeys(true)) {
                    if (!sub.startsWith(path + ".") || res.isConfigurationSection(sub)) {
                        continue;
                    }
                    cfg.set(sub, res.get(sub));
                    inner++;
                }
                if (inner == 0) {
                    cfg.createSection(path); // 纯空段也要建出来，服主才看得到
                }
                coveredBy.add(path);
                restored++;
                nodes += Math.max(1, inner);
                names.append(names.length() == 0 ? "" : "、").append(path)
                        .append("(").append(inner).append("键)");
            } else {
                // 叶子（标量 / 列表）：直接补，行为和 putIfAbsent 一致
                cfg.set(path, v);
                restored++;
                nodes++;
                names.append(names.length() == 0 ? "" : "、").append(path);
                coveredBy.add(path);
            }
        }

        if (restored > 0) {
            plugin.getLogger().info("配置迁移 step15：盘上缺 " + restored + " 处配置（共 "
                    + nodes + " 个键），已按内置默认整块补回 —— " + names
                    + "。这些键以前只能靠代码默认值跑；其中黑市（blackmarket.pool）连兜底都没有，"
                    + "整段缺失时货架会一直是空的。想删回原样，改完 config-version 再删即可");
        }
    }


    // ------------------------------------------------------------ step17 剑体放大（2.20）

    /**
     * step17（2.20）：补「剑体放大」{@code strengthen.big-sword.*} 的配置键。
     *
     * <p>新玩法：强化等级越高的剑，在主手里看起来越大——三个里程碑各翻一倍
     * （+33 ×2 / +66 ×4 / +99 ×8），纯视觉、不改伤害。所有键都由
     * {@code SwordGrowthService} 读，读不到时走代码默认值，所以老服不补键也能跑；
     * 但服主在 config.yml 里看不见这些旋钮，也就调不了「+99 到底多大」——
     * 这条迁移只为把它们摆到台面上。</p>
     *
     * <p>补法与 step13 一致：拿盘上那份独立副本（{@link YamlConfiguration#loadConfiguration}，
     * 不带 jar 内 defaults，否则 {@code contains} 会顺着默认值答「有」，新键永远补不进去）
     * 逐键判存在；只在盘上真的缺时才写，写入值就是代码默认，<b>行为零变化</b>。
     * {@code milestones} 是整数列表，{@link #putIfAbsent} 走的是同一条 {@code cfg.set} 路径，
     * 列表同样适用，只是注释要多写两行说明三个加倍点的语义。</p>
     *
     * <p>盘读不出来就整体跳过（日志提一句）：宁可少补键，也不赌一把去覆盖服主的调参。</p>
     */
    /**
     * step18：补「装备（护甲）放大」的配置键。
     *
     * <p>为什么单独一步而不是塞进 step17：{@code player-scale} 是 step17 写完之后才加的，
     * 而盘上 {@code config-version} 已经到 8 的服务器不会再跑 step17。
     * 迁移的版本号是一次性闸门——已经升过阈值的老配置不会回头，
     * 所以每加一批键就必须再抬一次版本号，否则那些服务器永远缺键；
     * 而缺键是**静默降级**到代码默认，服主改了也不生效。
     *
     * <p>这批键是被 {@code /zhujian config check} 实测抓出来的，不是推演出来的。
     */
    /**
     * step19：补暴击结算口径的配置键。
     *
     * <p>这是同一类教训的第三次：加配置键必须同批加迁移步骤，并且抬 {@code CURRENT_VERSION}。
     * 已经跑到 9 的服务器不会再执行 step18，所以这一批得单独一步。</p>
     *
     * <p>注意这批键**改变行为**（暴击率 179% → 封顶 75%，倍率 ×1.25 → ×1.75 起），
     * 和前面几步「值等于代码默认、行为不变」的性质不同。所以日志措辞也不一样，
     * 并且只在盘上确实缺这些键时才写——服主已经自己调过的值不动。</p>
     */
    private static void step19CritKeys(ForgingMasterPlugin plugin, FileConfiguration cfg) {
        YamlConfiguration disk;
        try {
            disk = YamlConfiguration.loadConfiguration(
                    new java.io.File(plugin.getDataFolder(), "config.yml"));
        } catch (Throwable t) {
            plugin.getLogger().warning("配置迁移 step19 跳过：读不出盘上的 config.yml（" + t + "）");
            return;
        }
        int added = 0;
        added += putIfAbsent(cfg, disk, "combat.crit.max-chance", 0.75, java.util.List.of(
                "暴击率上限；1.0 是硬天花板，堆超过这个值的部分不再生效"));
        added += putIfAbsent(cfg, disk, "combat.crit.max-damage", 2.5, java.util.List.of(
                "暴伤上限（在原版暴击倍率之上再加多少）；0.75 上限 + 2.5 = 最高 ×4.0"));
        added += putIfAbsent(cfg, disk, "combat.crit.base-multiplier", 1.5, java.util.List.of(
                "暴击倍率底数；原版跳跃暴击就是 ×1.5，低于它会出现「词条暴击不如跳劈」"));
        if (added > 0) {
            // 这一批会改变战斗手感，必须说清楚，不能让服主以为只是补了几个键
            plugin.getLogger().info("配置迁移 step19：补了 " + added
                    + " 个暴击配置键。注意这会让暴击率封顶 "
                    + cfg.getDouble("combat.crit.max-chance", 0.75)
                    + "、暴伤封顶 +" + cfg.getDouble("combat.crit.max-damage", 2.5)
                    + "（原来是无限叠加）");
        }
    }

    private static void step18PlayerScaleKeys(ForgingMasterPlugin plugin, FileConfiguration cfg) {
        YamlConfiguration disk;
        try {
            disk = YamlConfiguration.loadConfiguration(
                    new java.io.File(plugin.getDataFolder(), "config.yml"));
        } catch (Throwable t) {
            plugin.getLogger().warning("配置迁移 step18 跳过：读不出盘上的 config.yml（" + t + "）");
            return;
        }
        String base = "strengthen.big-sword.player-scale.";
        int added = 0;

        added += putIfAbsent(cfg, disk, base + "enabled", true, java.util.List.of(
                "穿戴护甲强化越高、玩家整体体型越大（走 Attribute.SCALE，会同时改碰撞箱与视线高度）"));
        added += putIfAbsent(cfg, disk, base + "min-pieces", 2, java.util.List.of(
                "至少穿几件强化过的护甲才生效（一件就变大会很怪）"));
        added += putIfAbsent(cfg, disk, base + "multiplier", 1.10, java.util.List.of(
                "按穿戴护甲的平均强化等级算，每个里程碑乘这个数（1.10^3 = 1.331）"));
        added += putIfAbsent(cfg, disk, base + "max-scale", 1.40, java.util.List.of(
                "体型上限；给太大会让碰撞箱和视高变得离谱"));
        added += putIfAbsent(cfg, disk, base + "min-level", 20, java.util.List.of(
                "平均强化等级低于这个值不放大"));
        if (!disk.contains(base + "milestones")) {
            cfg.set(base + "milestones", java.util.List.of(33, 66, 99));
            added++;
        }
        if (added > 0) {
            plugin.getLogger().info("配置迁移 step18：补了 " + added
                    + " 个装备放大配置键，值等于代码默认，行为不变");
        }
    }

    private static void step17BigSwordKeys(ForgingMasterPlugin plugin, FileConfiguration cfg) {
        YamlConfiguration disk;
        try {
            disk = YamlConfiguration.loadConfiguration(
                    new java.io.File(plugin.getDataFolder(), "config.yml"));
        } catch (Throwable t) {
            plugin.getLogger().warning("配置迁移 step17 跳过：读不出盘上的 config.yml（" + t + "）");
            return;
        }
        String base = "strengthen.big-sword.";
        int added = 0;

        added += putIfAbsent(cfg, disk, base + "enabled", true, java.util.List.of(
                "剑体放大总开关：关掉后强化再高也不挂牌（手持物品保持原尺寸）"));
        added += putIfAbsent(cfg, disk, base + "min-level", 0, java.util.List.of(
                "成长曲线起点：0 = 从 +1 起每锤都大一点点（v2.22 起砧上与手上共用这条曲线）"));
        added += putIfAbsent(cfg, disk, base + "multiplier", 2.0, java.util.List.of(
                "每个里程碑在上一个基础上乘这个数（默认 2.0 = 逐级翻倍）"));
        added += putIfAbsent(cfg, disk, base + "max-scale", 8.0, java.util.List.of(
                "硬上限，防止配置写错把模型放到穿模"));
        // v2.22.1：手上悬浮剑 / 护甲体型放大已移除，不再补 offset-* / pose /
        // show-from-scale / player-scale 等已下线的键；盘上还留着的属于无害死键。

        // 列表键单独判：三个加倍点，整数列表，不能写成 "33,66,99" 这种字符串
        if (!disk.contains(base + "milestones")) {
            cfg.set(base + "milestones", java.util.List.of(33, 66, 99));
            cfg.set("config-version", CURRENT_VERSION);
            setComment(cfg, base + "milestones", java.util.List.of(
                    "三个加倍点（整数列表，必须严格递增）：+33 ×2、+66 ×4、+99 ×8",
                    "项数与 multiplier 相乘得到最终倍数；改这里等于改「哪几级开始变大」"));
            added++;
        }

        if (added > 0) {
            plugin.getLogger().info("配置迁移 step17：补了 " + added
                    + " 个剑体放大配置键（strengthen.big-sword.enabled / min-level / milestones / "
                    + "multiplier / max-scale / update-ticks / per-player-toggle），"
                    + "值等于代码默认，行为不变");
        }
    }

    /**
     * step20（2.22）：剑体成长曲线起点迁移。
     *
     * <p>{@code min-level} 20 → 0：曲线起点提前（砧上从第一锤起就逐锤变大）。
     * 只改「还是旧默认 20」的盘，服主自己调过的值一个字不动。</p>
     *
     * <p>v2.22.1：手上悬浮剑 / 护甲体型放大已移除，本 step 不再迁移 offset-* /
     * show-from-scale / pose / hilt-offset / player-scale 等已下线的键——
     * 盘上留着的属于无害死键，功能不读它们。</p>
     */
    private static void step20SwordGrowthUnify(ForgingMasterPlugin plugin, FileConfiguration cfg) {
        YamlConfiguration disk;
        try {
            disk = YamlConfiguration.loadConfiguration(
                    new java.io.File(plugin.getDataFolder(), "config.yml"));
        } catch (Throwable t) {
            plugin.getLogger().warning("配置迁移 step20 跳过：读不出盘上的 config.yml（" + t + "）");
            return;
        }
        String base = "strengthen.big-sword.";

        if (disk.contains(base + "min-level") && disk.getInt(base + "min-level", 20) == 20) {
            cfg.set(base + "min-level", 0);
            setComment(cfg, base + "min-level", java.util.List.of(
                    "成长曲线起点：0 = 从 +1 起每锤都大一点点（砧上剑身成长曲线，v2.22）"));
            plugin.getLogger().info("配置迁移 step20：成长曲线起点 min-level 20 → 0");
        }
    }


    /**
     * step21（2.23）：清掉已下线功能的死配置键。
     *
     * <p>v2.22.1 移除了「手上悬浮剑」与「穿护甲体型放大」，这两个功能读过的键
     * （{@code offset-*} / {@code pose} / {@code show-from-scale} / {@code hilt-offset} /
     * {@code update-ticks} / {@code per-player-toggle} / 整个 {@code player-scale} 子树）
     * 现在没有任何代码在读，留在盘上只会误导服主。本 step 统一删除。</p>
     */
    private static void step21RemoveDeadBigSwordKeys(ForgingMasterPlugin plugin, FileConfiguration cfg) {
        String base = "strengthen.big-sword.";
        String[] dead = {
                base + "offset-forward", base + "offset-right", base + "offset-down",
                base + "show-from-scale", base + "pose", base + "hilt-offset",
                base + "update-ticks", base + "per-player-toggle",
        };
        int removed = 0;
        for (String key : dead) {
            if (cfg.contains(key)) {
                cfg.set(key, null);
                removed++;
            }
        }
        if (cfg.contains(base + "player-scale")) {
            cfg.set(base + "player-scale", null);
            removed++;
        }
        if (removed > 0) {
            plugin.getLogger().info("配置迁移 step21：清掉 " + removed
                    + " 组已下线功能的死配置键（悬浮剑 / 护甲体型放大）");
        }
    }

    /**
     * step22（2.23.1）：数据看板切片 2/3 的键 + 行会仓库行数收敛 + Webhook 段。
     *
     * <p>三类内容，都是「代码已经在读、但老盘上还没有」的键：</p>
     * <ol>
     *   <li>{@code metrics.history-days} / {@code metrics.snapshot-interval-minutes}——
     *       v2.23.1 把 {@code MetricsService} 真正接上了主类生命周期，这两个键从此有用；
     *       老盘没有时走代码默认（30 / 30），但服主改不了，属于本项目的「加键必须加迁移」纪律。</li>
     *   <li>{@code guild.warehouse.rows} 收敛到 ≤5：v2.23.0 允许 6 行 54 格，而关闭槽固定在 49，
     *       第 49 格的真实物品每次开仓都被关闭图标覆盖销毁（P0-1）。上限压到 5 行后
     *       {@code [0,45)} 与装饰区 {@code [45,54)} 不再相交。超容量的老物品由
     *       {@code GuildWarehouse.read} 转入保留区并随盘保存，不会丢。</li>
     *   <li>{@code webhook.*}：v2.23 拓展 C 的出站播报段，默认关闭。</li>
     * </ol>
     */
    private static void step22MetricsAndWarehouse(ForgingMasterPlugin plugin, FileConfiguration cfg) {
        YamlConfiguration disk;
        try {
            disk = YamlConfiguration.loadConfiguration(
                    new java.io.File(plugin.getDataFolder(), "config.yml"));
        } catch (RuntimeException ex) {
            disk = new YamlConfiguration();
        }
        int written = 0;
        written += putIfAbsent(cfg, disk, "metrics.history-days", 30, java.util.List.of(
                "趋势快照保留天数（v2.23 切片 2）：metrics trend / csv 的窗口上限"));
        written += putIfAbsent(cfg, disk, "metrics.snapshot-interval-minutes", 30, java.util.List.of(
                "多久采一次样（分钟，下限 1）：每天一份 metrics-<yyyyMMdd>.yml"));
        // 仓库行数：只在明显越界时收敛，别把服主正当的 1~4 改动抹掉
        int rows = cfg.getInt("guild.warehouse.rows", 3);
        if (rows > 5) {
            cfg.set("guild.warehouse.rows", 5);
            written++;
            plugin.getLogger().warning("配置迁移 step22：guild.warehouse.rows 原为 " + rows
                    + "（>5），已收敛到 5。v2.23.0 的 6 行会让第 49 格与关闭按钮重叠、"
                    + "每次开仓销毁该格物品；超出 45 格的老物品已转入保留区（不会丢失）。");
        }
        // Webhook 段（默认关闭，url 为空即空壳）
        written += putIfAbsent(cfg, disk, "webhook.enabled", false, java.util.List.of(
                "出站 Webhook 播报总开关（v2.23 拓展 C）：false = 完全不启动消费线程"));
        written += putIfAbsent(cfg, disk, "webhook.url", "", java.util.List.of(
                "接收地址（http/https）。留空 = 不发送；支持 Discord / 企业微信 / 自建 HTTP 转发"));
        written += putIfAbsent(cfg, disk, "webhook.username", "铸剑大师", java.util.List.of(
                "发送方显示名（写进 JSON 的 username 字段）"));
        written += putIfAbsent(cfg, disk, "webhook.timeout-seconds", 10, java.util.List.of(
                "单次请求超时（秒）。超时即静默丢弃，不重试不刷屏"));
        written += putIfAbsent(cfg, disk, "webhook.queue-size", 64, java.util.List.of(
                "有界队列长度：满了丢最新一条，绝不阻塞主线程"));
        written += putIfAbsent(cfg, disk, "webhook.events", java.util.List.of(
                "forge_success", "strengthen_break", "evolution_success", "season_end"),
                java.util.List.of("要播报的事件 id；留空列表 = 全开。可选：forge_success /"
                        + " strengthen_success / strengthen_break / evolution_success / tomb_win /"
                        + " duel_win / guild_quest_done / season_end"));
        if (written > 0) {
            plugin.getLogger().info("配置迁移 step22：补齐数据看板 / Webhook 配置键与仓库行数上限，共 "
                    + written + " 处");
        }
    }

    /**
     * step23（2.23.1）：战力评分（路线图切片 5 · D）的权重段。
     *
     * <p>只补「关键技术键 + 一个可用的 grades 表」：权重明细（affix-weights 每一项、
     * material-per-tier 等）在代码里都有默认值，服主想调再去 config.yml 手写即可，
     * 全量铺 20 多行反而会把他们的配置文件冲得面目全非。</p>
     */
    private static void step23PowerScore(ForgingMasterPlugin plugin, FileConfiguration cfg) {
        YamlConfiguration disk;
        try {
            disk = YamlConfiguration.loadConfiguration(
                    new java.io.File(plugin.getDataFolder(), "config.yml"));
        } catch (RuntimeException ex) {
            disk = new YamlConfiguration();
        }
        int written = 0;
        written += putIfAbsent(cfg, disk, "power-score.enabled", true, java.util.List.of(
                "战力评分总开关（v2.23 切片 5）：power = 基础 × 强化 × 蜕变 × 词条 × 宝石 × 剑灵"));
        written += putIfAbsent(cfg, disk, "power-score.base-multiplier", 100.0, java.util.List.of(
                "基础分倍率：品质 power()（粗糙0.5…神话5.0）× 材质加成 × 本值"));
        written += putIfAbsent(cfg, disk, "power-score.strengthen-per-level", 0.055, java.util.List.of(
                "每强化 +1 级的系数增量"));
        written += putIfAbsent(cfg, disk, "power-score.evolve-per-stage", 1.35, java.util.List.of(
                "每蜕变一阶的乘数（复利；蜕变不可逆所以比强化高）"));
        written += putIfAbsent(cfg, disk, "power-score.suggest-price", true, java.util.List.of(
                "是否在拍卖/订单/命令里给推荐价"));
        written += putIfAbsent(cfg, disk, "power-score.price-per-power", 2.5, java.util.List.of(
                "推荐价单价：战力 × 本值（取整到 10，并受 price-min 兜底）"));
        if (written > 0) {
            plugin.getLogger().info("配置迁移 step23：补齐战力评分配置段，共 " + written + " 处"
                    + "（其余权重走代码默认，需要细调请直接在 config.yml 补写）");
        }
    }

    /**
     * step24（2.23.1）：属性膨胀护栏 {@code strengthen.base-tolerance}。
     *
     * <p>老版本的 {@code baseOf} 会把「已经乘过强化倍率的 modifier」当成基础值再用一次，
     * 老物品越强化属性越离谱（详见 {@code StrengthenService#baseOf} 的注释）。
     * 修复后的天花板是「配置算出来的基础值 × 本容差」，默认 1.0 = 严格按配置。
     * 补这个键是为了让服主在「配置被自己调低过、玩家手里是按老配置锻的剑」时
     * 有个过渡旋钮，而不是一刀砍到底。</p>
     */
    private static void step24BaseTolerance(ForgingMasterPlugin plugin, FileConfiguration cfg) {
        YamlConfiguration disk;
        try {
            disk = YamlConfiguration.loadConfiguration(
                    new java.io.File(plugin.getDataFolder(), "config.yml"));
        } catch (RuntimeException ex) {
            disk = new YamlConfiguration();
        }
        int written = putIfAbsent(cfg, disk, "strengthen.base-tolerance", 1.0, java.util.List.of(
                "基础属性天花板 = 配置基础值 × 本值（默认 1.0 严格按配置）。",
                "只压虚高、不抬正常值；小于 1 会被忽略。存量物品用 /zhujian fixattrs 修复"));
        if (written > 0) {
            plugin.getLogger().info("配置迁移 step24：补上属性天花板 strengthen.base-tolerance（默认 1.0）");
        }
    }

    /**
     * step25（2.23.1）：伤害预算 {@code combat.limits.*} + 暴击口径下调。
     *
     * <h2>为什么要动老盘的数值</h2>
     * 服主实测「满配暴击一刀 130、四刀一只坚守者、一刀吸血接近半管」。这不是某一个键写错，
     * 而是四层乘区各自都合理、叠起来失控 —— 老盘的 {@code combat.crit} 还是 0.75 / 2.5（→ ×4.0），
     * 而 {@code combat.limits} 根本不存在，吸血因此完全没有上限（词条能叠到 115%）。
     *
     * <p>所以这一步做两件事：补上 5 个预算键；把暴击上限<b>只在仍是旧默认值时才</b>下调
     * （0.75 → 0.50、2.5 → 1.10）。服主自己改过的值原样保留 —— 迁移不许覆盖人工调参。</p>
     */
    private static void step25DamageBudget(ForgingMasterPlugin plugin, FileConfiguration cfg) {
        YamlConfiguration disk;
        try {
            disk = YamlConfiguration.loadConfiguration(
                    new java.io.File(plugin.getDataFolder(), "config.yml"));
        } catch (RuntimeException ex) {
            disk = new YamlConfiguration();
        }
        int written = 0;
        written += putIfAbsent(cfg, disk, "combat.limits.max-attack-attribute", 22.0, java.util.List.of(
                "单个物品攻击属性的上限；原版下界合金剑 = 8，满配压在 22"));
        written += putIfAbsent(cfg, disk, "combat.limits.max-hit-damage", 60.0, java.util.List.of(
                "单次命中最终伤害上限（含暴击，未计护甲）；写在暴击之后才管得住"));
        written += putIfAbsent(cfg, disk, "combat.limits.max-lifesteal", 0.20, java.util.List.of(
                "吸血比例上限；不夹的话词条能叠到 115%，一刀回半管"));
        written += putIfAbsent(cfg, disk, "combat.limits.max-lifesteal-per-hit", 2.0, java.util.List.of(
                "单次吸血回血绝对值上限（半颗心 = 1.0）"));
        written += putIfAbsent(cfg, disk, "combat.limits.max-lifesteal-max-health", 0.10, java.util.List.of(
                "单次吸血最多回最大生命的百分之几"));
        written += putIfAbsent(cfg, disk, "combat.limits.max-damage-reduce", 0.35, java.util.List.of(
                "单件装备减伤上限；四件 + 全词条原始累加能到 58%"));
        written += putIfAbsent(cfg, disk, "combat.limits.max-bonus-health", 20.0, java.util.List.of(
                "生命上限加成上限；气血词条单件能堆到 +41 生命"));
        written += putIfAbsent(cfg, disk, "combat.limits.max-fire-seconds", 10.0, java.util.List.of(
                "点燃时长上限（秒）；叠 5 条燃烬能烧 40 秒"));

        // 只在「还是旧默认值」时下调，避免覆盖服主的人工调参
        double oldChance = disk.getDouble("combat.crit.max-chance", 0.75);
        if (Math.abs(oldChance - 0.75) < 1e-9) {
            cfg.set("combat.crit.max-chance", 0.50);
            written++;
        }
        double oldDamage = disk.getDouble("combat.crit.max-damage", 2.5);
        if (Math.abs(oldDamage - 2.5) < 1e-9) {
            cfg.set("combat.crit.max-damage", 1.10);
            written++;
        }
        if (written > 0) {
            plugin.getLogger().info("配置迁移 step25：补上 combat.limits 伤害预算（5 键）"
                    + (Math.abs(oldChance - 0.75) < 1e-9 || Math.abs(oldDamage - 2.5) < 1e-9
                    ? "，并把暴击上限从 0.75/2.5 下调到 0.50/1.10（原值仍是旧默认才动）" : ""));
        }
    }

    /**
     * step26（2.23.1）：存量物品收敛开关 {@code legacy-repair.*}。
     *
     * <p>产出侧的夹子管不到盘上的旧装备 —— 玩家把旧剑挂在墙上，它永远是旧数值。
     * 这一步补上「收敛触发点」的配置：默认上线自动扫（覆盖离线玩家的唯一安全手段），
     * 扫完给玩家一句说明（否则玩家只看到数值莫名其妙少了）。</p>
     */
    private static void step26LegacyRepair(ForgingMasterPlugin plugin, FileConfiguration cfg) {
        YamlConfiguration disk;
        try {
            disk = YamlConfiguration.loadConfiguration(
                    new java.io.File(plugin.getDataFolder(), "config.yml"));
        } catch (RuntimeException ex) {
            disk = new YamlConfiguration();
        }
        int written = 0;
        written += putIfAbsent(cfg, disk, "legacy-repair.on-join", true, java.util.List.of(
                "玩家上线自动收敛背包+盔甲+副手+末影箱里的旧装备",
                "选上线而不是开服扫全服：离线背包在 playerdata 里，强加载档案风险太大"));
        written += putIfAbsent(cfg, disk, "legacy-repair.on-join-delay-ticks", 40, java.util.List.of(
                "登录后延迟多少 tick 再扫（40 = 2 秒），避免客户端显示与服务端不一致"));
        written += putIfAbsent(cfg, disk, "legacy-repair.notify-player", true, java.util.List.of(
                "修过之后告诉玩家，否则玩家只会觉得数值莫名其妙少了"));
        if (written > 0) {
            plugin.getLogger().info("配置迁移 step26：补上 legacy-repair（存量物品收敛）3 键");
        }
    }

    /**
     * step27（v2.23.2 / v2.23.3）：附魔台的**费用与守卫**骨架。
     *
     * <p>v2.23.3 把「机制上限」整段搬去了 step28 —— 因为机制换了
     * （原版皮肤 → 溅射/蓄势/刻痕/卸力…），旧的上限键全部作废。
     * 这里只剩与机制无关的部分：多少钱、耗几个矿、能带几条、白名单。</p>
     *
     * <p>故意不逐条补 {@code defs.*}：12 条机制的默认值在
     * {@link com.forgemaster.enchant.EnchantRegistry#defaults()} 里，
     * config 缺键时注册表直接回落到代码定义。如果迁移往服主盘上塞一份「旧默认」，
     * 以后我们调整默认数值，老服就永远跟不上了。</p>
     */
    private static void step27Enchant(ForgingMasterPlugin plugin, FileConfiguration cfg) {
        YamlConfiguration disk;
        try {
            disk = YamlConfiguration.loadConfiguration(
                    new java.io.File(plugin.getDataFolder(), "config.yml"));
        } catch (RuntimeException ex) {
            disk = new YamlConfiguration();
        }
        int written = 0;
        written += putIfAbsent(cfg, disk, "enchant.cost.base-coin", 40L, java.util.List.of(
                "附魔台每次操作的固定费用"));
        written += putIfAbsent(cfg, disk, "enchant.cost.coin-per-level", 60L, java.util.List.of(
                "每升一级额外加多少费用"));
        written += putIfAbsent(cfg, disk, "enchant.cost.ore-per-level", 4, java.util.List.of(
                "升一级消耗几个矿（按等级翻倍：4 / 8 / 16 ...）"));
        written += putIfAbsent(cfg, disk, "enchant.max-per-item", 4, java.util.List.of(
                "一件装备最多几条原创附魔；同一条重复上书只升级不占条数"));
        written += putIfAbsent(cfg, disk, "enchant.guard.enabled", true, java.util.List.of(
                "附魔白名单守卫：拦在附魔台与铁砧，只作用于本插件的锻造品"));
        written += putIfAbsent(cfg, disk, "enchant.guard.vanilla-whitelist",
                java.util.List.of(), java.util.List.of(
                        "允许的原版附魔 key（如 sharpness / protection）",
                        "留空 = 全部原版附魔都允许；想收紧时再列"));
        if (written > 0) {
            plugin.getLogger().info("配置迁移 step27：补上附魔台费用/守卫 " + written + " 键");
        }
    }

    /**
     * v2.23.3：机制型附魔的参数。
     *
     * <p>v2.23.2 的 step27 写的是「增伤 25% / 吸血 10% / 点燃 4 秒」这类**原版皮肤**的参数，
     * 那一套已经被服主否掉，键也随之作废。这里写的是新机制的参数（溅射比例、蓄势窗口、
     * 刻痕阈值、卸力上限……），并把作废的旧键**摘掉**——留着它们只会让服主以为还能调。</p>
     *
     * <p>只摘 {@code enchant.caps.*} 里的旧机制键，不碰 {@code enchant.defs.*}
     * （defs 是服主可能自己调过的东西）。</p>
     */
    /**
     * 2.23.5：耐久 Lore 的显示档宽加一层绝对上限。
     *
     * <p>问题的形状：档宽原来只有百分比（默认 5%），对几千点耐久的剑来说
     * 就是「要砍 195 刀 Lore 上的数字才动一次」——玩家砍完一只怪，
     * 看到的还是满耐久，自然会报「内置耐久不掉 / 是不是加了无法破坏」。</p>
     *
     * <p>修法：百分比之外再夹一个「最多 N 点」的上限（默认 5 点）。
     * 这里只补键，不动服主已调过的 {@code durability-lore-step}；
     * 键已存在时 putIfAbsent 语义，一个字都不碰。</p>
     */
    /**
     * step32（v2.26.0）：大赛历届档案的保留届数 {@code contest.history-keep}。
     *
     * <p>为什么单独开一步：这个键 v2.26.0 才加，而 jar 内置 config.yml 里有它 ——
     * 老档（磁盘上没有这个键）会让 {@code cfg.contains} 顺着 defaults 答「有」，
     * 于是服主在 config.yml 里永远找不到、也改不了这个旋钮。所以必须走
     * {@link #existsOnDisk} 而不是 {@code cfg.contains}，口径与 step29 同一个教训。</p>
     */
    private static void step32ContestHistory(ForgingMasterPlugin plugin, FileConfiguration cfg) {
        if (!existsOnDisk(plugin, "contest.history-keep")) {
            cfg.set("contest.history-keep", 20);
            plugin.getLogger().info("[migrate] step32：补 contest.history-keep = 20（大赛历届档案保留届数）");
        }
    }

    /**
     * step31（v2.25 拓展）：血怒/坚韧附魔上限、灵契重抽、强化保险三段新键。
     * 与 step30 同口径：contains 判断，只补缺，绝不覆盖服主已有值。
     */
    private static void step31Expansion25(ForgingMasterPlugin plugin, FileConfiguration cfg) {
        int written = 0;
        for (String[] kv : new String[][]{
                {"enchant.caps.max-rage", "0.12"},
                {"enchant.caps.rage-window-ms", "4000"},
                {"enchant.caps.max-stout-chance", "0.40"},
                {"enchant.defs.rage.enabled", "true"},
                {"enchant.defs.rage.max-level", "4"},
                {"enchant.defs.rage.per-level", "0.03"},
                {"enchant.defs.rage.weight", "65"},
                {"enchant.defs.stout.enabled", "true"},
                {"enchant.defs.stout.max-level", "5"},
                {"enchant.defs.stout.per-level", "0.08"},
                {"enchant.defs.stout.weight", "85"},
                {"spirit.reroll.cost", "2000"},
                {"spirit.reroll.daily-limit", "1"},
                {"strengthen.insure.enabled", "true"},
                {"strengthen.insure.fee-per-strike", "200"},
                {"strengthen.insure.refund-ratio", "0.5"}}) {
            if (!existsOnDisk(plugin, kv[0])) {
                cfg.set(kv[0], typed(kv[1]));
                written++;
            }
        }
        if (written > 0) {
            plugin.getLogger().info("[migrate] step31：v2.25 拓展补键 " + written
                    + " 组（血怒/坚韧上限与定义、灵契重抽、强化保险）");
        }
    }

    /**
     * step30（v2.24 拓展）：把两个切片的新键补进老配置。全部有代码默认，
     * 缺键不迁移也能跑 —— 这里补键只是让服主在 config.yml 里看得到、能改。
     * 口径与 step15 一致：contains 判断，绝不覆盖服主已有的值。
     */
    private static void step30Expansion24(ForgingMasterPlugin plugin, FileConfiguration cfg) {
        int written = 0;
        // ---- 切片一：附魔/词条新机制
        for (String[] kv : new String[][]{
                {"enchant.defs.temper.enabled", "true"},
                {"enchant.defs.temper.max-level", "5"},
                {"enchant.defs.temper.per-level", "0.04"},
                {"enchant.defs.temper.weight", "80"},
                {"enchant.defs.siphon.enabled", "true"},
                {"enchant.defs.siphon.max-level", "5"},
                {"enchant.defs.siphon.per-level", "0.5"},
                {"enchant.defs.siphon.weight", "70"},
                {"enchant.caps.max-streak-bonus", "0.20"},
                {"enchant.caps.max-streak-stacks", "5"},
                {"enchant.caps.max-dur-leech", "3.0"},
                {"combat.caps.max-chill-ticks", "40"},
                {"combat.caps.max-execute", "0.30"},
                {"combat.caps.execute-threshold", "0.15"}}) {
            if (!existsOnDisk(plugin, kv[0])) {
                cfg.set(kv[0], typed(kv[1]));
                written++;
            }
        }
        // 词条两条（模板整段；缺哪段补哪段）
        if (!existsOnDisk(plugin, "affixes.chill")) {
            cfg.set("affixes.chill.display", "冰噬");
            cfg.set("affixes.chill.kind", "CHILL");
            cfg.set("affixes.chill.min", 1);
            cfg.set("affixes.chill.max", 2);
            cfg.set("affixes.chill.unit", "秒减速");
            cfg.set("affixes.chill.weight", 10);
            written++;
        }
        if (!existsOnDisk(plugin, "affixes.execute")) {
            cfg.set("affixes.execute.display", "处决");
            cfg.set("affixes.execute.kind", "EXECUTE");
            cfg.set("affixes.execute.min", 0.06);
            cfg.set("affixes.execute.max", 0.30);
            cfg.set("affixes.execute.unit", "残血增伤");
            cfg.set("affixes.execute.percent", true);
            cfg.set("affixes.execute.weight", 8);
            written++;
        }
        // ---- 切片二：剑灵灵击 / 冢主温养 / 商会科技 / 声望兑换
        for (String[] kv : new String[][]{
                {"spirit.surge.enabled", "true"},
                {"spirit.surge.chance", "0.08"},
                {"spirit.surge.damage", "2.0"},
                {"spirit.surge.radius", "2.5"},
                {"spirit.surge.cooldown-ms", "6000"},
                {"spirit.surge.min-tier", "3"},
                {"tomb.nurture-spirit", "5"},
                {"guild.tech.cost-base", "2000"},
                {"guild.tech.cost-per-level", "1500"},
                {"guild.tech.max-level", "5"},
                {"guild.tech.discount-per-level", "0.02"},
                {"guild.tech.success-per-level", "0.01"},
                {"redeem.coin-rate", "10"},
                {"redeem.coin-max", "500"},
                {"redeem.charms.talisman", "40"},
                {"redeem.charms.steady", "60"}}) {
            if (!existsOnDisk(plugin, kv[0])) {
                cfg.set(kv[0], typed(kv[1]));
                written++;
            }
        }
        if (written > 0) {
            plugin.getLogger().info("[migrate] step30：v2.24 拓展补键 " + written
                    + " 组（淬锋/饮命附魔、冰噬/处决词条、灵击、冢主温养、商会科技、声望兑换）");
        }
    }

    /** 迁移值按字符串里有没有小数点/布尔词落成对应类型，避免整段变成字符串 */
    private static Object typed(String raw) {
        if (raw.equals("true") || raw.equals("false")) {
            return Boolean.parseBoolean(raw);
        }
        try {
            if (raw.contains(".")) {
                return Double.parseDouble(raw);
            }
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return raw;
        }
    }

    private static void step29DurabilityLoreCap(ForgingMasterPlugin plugin, FileConfiguration cfg) {
        // v2.25.1 修 P2（审计轮3-B3）：existsOnDisk 判定 —— cfg.contains 会顺 defaults
        // 答「有」（jar 内 config.yml 已带这些键），整套补键变成恒 no-op 死代码
        if (!existsOnDisk(plugin, "settings.durability-lore-max-points")) {
            cfg.set("settings.durability-lore-max-points", 5);
            plugin.getLogger().info("[migrate] step29：补上 settings.durability-lore-max-points（默认 5）"
                    + " —— 几千点耐久的剑不会再「数字不动」");
        }
    }

    /** v2.25.1：键是否真的写在了盘上的 config.yml（不含 jar 内默认段） */
    private static boolean existsOnDisk(ForgingMasterPlugin plugin, String path) {
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(
                new java.io.File(plugin.getDataFolder(), "config.yml"));
        return disk.contains(path);
    }

    private static void step28MechanicEnchant(ForgingMasterPlugin plugin, FileConfiguration cfg) {
        YamlConfiguration disk;
        try {
            disk = YamlConfiguration.loadConfiguration(
                    new java.io.File(plugin.getDataFolder(), "config.yml"));
        } catch (RuntimeException ex) {
            disk = new YamlConfiguration();
        }

        // ---- 先摘掉已作废的旧机制上限（它们对应的机制已经整条删除）
        int removed = 0;
        for (String dead : new String[]{
                "enchant.caps.max-damage-bonus",
                "enchant.caps.max-lifesteal-add",
                "enchant.caps.max-fire-seconds",
                "enchant.caps.max-attack-speed",
                "enchant.caps.max-damage-reduce",
                "enchant.caps.max-bonus-health",
                "enchant.caps.max-thorns",
                "enchant.caps.max-thorns-flat",
                "enchant.caps.max-fire-resist",
                "enchant.caps.max-exp-bonus",
                "enchant.caps.max-coin-bonus",
                "enchant.caps.max-drop-chance",
                "enchant.caps.execute-threshold",
                "enchant.caps.combo-stacks",
                "enchant.caps.combo-window-ms"}) {
            if (cfg.contains(dead)) {
                cfg.set(dead, null);
                removed++;
            }
        }
        // 旧的 defs 同样是作废机制，留着会让玩家抽出「认不出来的附魔」
        for (String dead : new String[]{
                "enchant.defs.edge", "enchant.defs.pierce", "enchant.defs.swift",
                "enchant.defs.soulsip", "enchant.defs.ember", "enchant.defs.slayer",
                "enchant.defs.execute", "enchant.defs.combo", "enchant.defs.bulwark",
                "enchant.defs.thorns", "enchant.defs.fortitude", "enchant.defs.emberward",
                "enchant.defs.lorekeeper", "enchant.defs.prospector", "enchant.defs.fortunes"}) {
            if (cfg.contains(dead)) {
                cfg.set(dead, null);
                removed++;
            }
        }

        // ---- 再补上新机制参数（putIfAbsent：绝不覆盖服主已调的数值）
        int written = 0;
        written += putIfAbsent(cfg, disk, "enchant.caps.max-splash-ratio", 0.40, java.util.List.of(
                "崩流：最多溅多少比例的伤害（再高单体剑就变范围武器）"));
        written += putIfAbsent(cfg, disk, "enchant.caps.splash-radius", 3.0, java.util.List.of(
                "崩流：波及半径（格）"));
        written += putIfAbsent(cfg, disk, "enchant.caps.max-pyre-damage", 6.0, java.util.List.of(
                "烬狱：击杀爆燃的固定伤害，不乘任何倍率"));
        written += putIfAbsent(cfg, disk, "enchant.caps.pyre-radius", 3.0, java.util.List.of(
                "烬狱：爆燃半径（格）"));
        written += putIfAbsent(cfg, disk, "enchant.caps.max-lone-wolf", 0.15, java.util.List.of(
                "孤锋：周围无旁人时的增伤封顶"));
        written += putIfAbsent(cfg, disk, "enchant.caps.lone-wolf-radius", 8.0, java.util.List.of(
                "孤锋：判定半径（格），半径内有别的玩家就失效"));
        written += putIfAbsent(cfg, disk, "enchant.caps.max-charge", 0.25, java.util.List.of(
                "蓄锋：停手够久后第一击的增伤封顶"));
        written += putIfAbsent(cfg, disk, "enchant.caps.charge-window-ms", 2500L, java.util.List.of(
                "蓄锋：距上次命中超过这个毫秒数才算蓄满"));
        written += putIfAbsent(cfg, disk, "enchant.caps.max-hamper-seconds", 2.0, java.util.List.of(
                "夺魄：减速+虚弱时长封顶（秒）"));
        written += putIfAbsent(cfg, disk, "enchant.caps.mark-threshold", 0.30, java.util.List.of(
                "刻痕：对同一目标累计削掉其最大生命的这个比例后刻印"));
        written += putIfAbsent(cfg, disk, "enchant.caps.max-mark-bonus", 0.10, java.util.List.of(
                "刻痕：刻印后的增伤封顶"));
        written += putIfAbsent(cfg, disk, "enchant.caps.max-delay-ratio", 0.50, java.util.List.of(
                "卸力：最多把多少比例的伤害延后（1.0 = 免伤，禁止）"));
        written += putIfAbsent(cfg, disk, "enchant.caps.delay-ms", 2000L, java.util.List.of(
                "卸力：延后多久结算完（分期扣，不是一次性）"));
        written += putIfAbsent(cfg, disk, "enchant.caps.max-cheat-heal", 0.30, java.util.List.of(
                "替身：免死后回复的最大生命比例"));
        written += putIfAbsent(cfg, disk, "enchant.caps.cheat-cooldown-ms", 90000L, java.util.List.of(
                "替身：冷却（毫秒），没有冷却就是永久免死"));
        written += putIfAbsent(cfg, disk, "enchant.caps.max-retaliate", 0.15, java.util.List.of(
                "回响：受击后对攻击者的增伤封顶"));
        written += putIfAbsent(cfg, disk, "enchant.caps.retaliate-ms", 3000L, java.util.List.of(
                "回响：受击后的生效窗口（毫秒）"));
        written += putIfAbsent(cfg, disk, "enchant.caps.max-regen-per-tick", 2.5, java.util.List.of(
                "守夜：单次回复的生命封顶"));
        written += putIfAbsent(cfg, disk, "enchant.caps.regen-interval-ms", 2000L, java.util.List.of(
                "守夜：多久回一次（毫秒）"));
        written += putIfAbsent(cfg, disk, "enchant.caps.out-of-combat-ms", 5000L, java.util.List.of(
                "守夜：脱离战斗多久后才开始回"));
        written += putIfAbsent(cfg, disk, "enchant.caps.max-regen-level", 5, java.util.List.of(
                "守夜：等级上限（内部夹子用）"));
        written += putIfAbsent(cfg, disk, "enchant.caps.max-salvage-chance", 0.30, java.util.List.of(
                "拾遗：挖掘矿石额外得经验的几率封顶"));
        written += putIfAbsent(cfg, disk, "enchant.caps.max-sense-radius", 20.0, java.util.List.of(
                "望气：侦测半径封顶（格）"));
        written += putIfAbsent(cfg, disk, "enchant.caps.sense-interval-ms", 3000L, java.util.List.of(
                "望气：侦测间隔（毫秒），防止动作栏刷屏"));

        // 新机制的 defs 骨架（只补缺，不覆盖）
        written += putIfAbsent(cfg, disk, "enchant.defs.splinter", null, java.util.List.of(
                "崩流：每级 +8% 溅射比例"));
        written += putIfAbsent(cfg, disk, "enchant.defs.pyre", null, java.util.List.of(
                "烬狱：每级 +2 点爆燃伤害"));
        written += putIfAbsent(cfg, disk, "enchant.defs.lone", null, java.util.List.of(
                "孤锋：每级 +3% 独斗增伤"));
        written += putIfAbsent(cfg, disk, "enchant.defs.charge", null, java.util.List.of(
                "蓄锋：每级 +5% 蓄势增伤"));
        written += putIfAbsent(cfg, disk, "enchant.defs.sever", null, java.util.List.of(
                "夺魄：每级 +0.4 秒减速"));
        written += putIfAbsent(cfg, disk, "enchant.defs.mark", null, java.util.List.of(
                "刻痕：每级 +2% 刻印增伤"));
        written += putIfAbsent(cfg, disk, "enchant.defs.unload", null, java.util.List.of(
                "卸力：每级 +10% 伤害延后"));
        written += putIfAbsent(cfg, disk, "enchant.defs.decoy", null, java.util.List.of(
                "替身：每级 +10% 免死回气"));
        written += putIfAbsent(cfg, disk, "enchant.defs.echo", null, java.util.List.of(
                "回响：每级 +3% 受击反击"));
        written += putIfAbsent(cfg, disk, "enchant.defs.vigil", null, java.util.List.of(
                "守夜：每级 +0.5 点脱战回复"));
        written += putIfAbsent(cfg, disk, "enchant.defs.salvage", null, java.util.List.of(
                "拾遗：每级 +6% 额外经验几率"));
        written += putIfAbsent(cfg, disk, "enchant.defs.insight", null, java.util.List.of(
                "望气：每级 +2 格侦测半径"));

        if (written > 0 || removed > 0) {
            plugin.getLogger().info("配置迁移 step28：机制附魔 新增 " + written
                    + " 键，摘掉作废旧键 " + removed + " 个");
        }
    }


    /** 键只在盘上确实没有时才写；返回写了几个（1 / 0），方便汇总日志 */
    private static int putIfAbsent(FileConfiguration cfg, YamlConfiguration disk,
                                   String path, Object def, java.util.List<String> comment) {
        if (disk.contains(path)) {
            return 0;
        }
        cfg.set(path, def);
        cfg.set("config-version", CURRENT_VERSION); // 与 run() 收尾同一个值，二次写无副作用
        setComment(cfg, path, comment);
        return 1;
    }

    /** setComments 在个别版本上没有，缺了就只是少一行说明，不该让开服炸掉 */
    private static void setComment(FileConfiguration cfg,
                                   String path, java.util.List<String> comment) {
        try {
            // 老键上带的是行内注释（`key: value # ...`），不清掉的话新注释写不进去
            cfg.setInlineComments(path, java.util.List.of());
        } catch (Throwable ignored) {
        }
        try {
            cfg.setComments(path, comment);
        } catch (Throwable ignored) {
        }
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }
}
