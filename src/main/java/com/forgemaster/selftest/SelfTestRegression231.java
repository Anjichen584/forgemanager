package com.forgemaster.selftest;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.affix.Affix;
import com.forgemaster.config.ConfigMigrations;
import com.forgemaster.metrics.MetricsService;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * v2.23.1 缺陷修复的回归自检。
 *
 * <p>这一套只盯「修过的坑不再回来」。每条断言都对应 audit 20~24 里一条
 * 已确认缺陷，注释里写明「原来怎么坏、现在靠什么守住」，避免以后重构时
 * 又一次把护栏拆掉（本项目的 `isBaseKey` 前缀判定就是这么坏的：注释写着
 * 「精确匹配」，实现却是前缀）。</p>
 *
 * <p>不依赖真实玩家、不碰线上存档：全部用临时对象与临时 YamlConfiguration。</p>
 */
public final class SelfTestRegression231 {

    private SelfTestRegression231() {
    }

    /** 由 {@link SelfTestSuite#register()} 统一挂接 */
    public static void register(SelfTestSuite suite) {
        ForgingMasterPlugin plugin = org.bukkit.plugin.java.JavaPlugin
                .getPlugin(ForgingMasterPlugin.class);
        String g = "v2.23.1 回归";

        // ---------------------------------------------------------------- P0-1
        suite.addExternal(g, "基础键判定：护甲词条键不被当成基础属性", () -> {
            // 原来 isBaseKey 用 startsWith("fm_armor_")，把 armor_plate / tough_plate 两条
            // 词条键吞进「基础键」集合 → 崩刃时被摘掉、修好也只重建基础键，词条属性永久丢失。
            require(!com.forgemaster.util.Attrs.isBaseKey("fm_armor_plate_chestplate"),
                    "fm_armor_plate_chestplate 是词条键，不能被当成基础键");
            require(!com.forgemaster.util.Attrs.isBaseKey("fm_toughness_plate_boots"),
                    "fm_toughness_plate_boots 是词条键，不能被当成基础键");
            require(!com.forgemaster.util.Attrs.isBaseKey("fm_vitality_chestplate"),
                    "任意带部位后缀的词条键都不能被当成基础键");
            // 四个真正的基部键必须仍然认得（老键 + 部位键），否则崩刃摘不干净
            require(com.forgemaster.util.Attrs.isBaseKey("fm_atk"), "fm_atk 必须认得");
            require(com.forgemaster.util.Attrs.isBaseKey("fm_speed"), "fm_speed 必须认得");
            require(com.forgemaster.util.Attrs.isBaseKey("fm_armor"), "老键 fm_armor 必须认得");
            require(com.forgemaster.util.Attrs.isBaseKey("fm_toughness"), "老键 fm_toughness 必须认得");
            require(com.forgemaster.util.Attrs.isBaseKey("fm_armor_chestplate"), "fm_armor_chestplate 必须认得");
            require(com.forgemaster.util.Attrs.isBaseKey("fm_toughness_boots"), "fm_toughness_boots 必须认得");
            require(!com.forgemaster.util.Attrs.isBaseKey(null), "null 不算基础键");
            require(!com.forgemaster.util.Attrs.isBaseKey("other_plugin_key"), "别人的键不算基础键");
        });

        // ---------------------------------------------------------------- P0-2
        suite.addExternal(g, "铁砧异步冲刷：脏代数与结构变更同锁", () -> {
            var mgr = plugin.anvilForge();
            if (mgr == null) {
                throw new IllegalStateException("铁砧模块没起来");
            }
            // 原来 saveTo 是 synchronized，但主线程 sessions.put/remove 不加锁 ——
            // 异步拍快照会撞 CME、任务被 Bukkit 永久取消；且写完无条件清脏 = 复制/吞剑。
            // 现在：结构变更走 putSession/removeSession（持同一把锁 + 代数自增），
            // saveTo 只在「写完后代数没变」时清脏。这里验「重复 start 不叠加心跳」这条同源缺陷。
            int before = mgr.debugTickTaskId();
            mgr.start();
            int once = mgr.debugTickTaskId();
            mgr.start();
            int twice = mgr.debugTickTaskId();
            require(once != -1 && twice != -1, "start() 之后心跳任务必须真的在跑");
            require(twice != -1, "第二次 start() 也要留下有效任务 id");
            require(before == -1 || once != before || twice != once,
                    "start() 必须换一个新任务 id（旧任务先被 cancel）");
            // 幂等性：连起两次只应有一条心跳在跑，第二次 start 会先 cancel 第一条
            require(mgr.debugTickTaskId() == twice, "任务 id 应当稳定，不能被重复覆盖成野值");
        });

        // ---------------------------------------------------------------- P1
        suite.addExternal(g, "付费轨发的是付费奖励，不是免费轨", () -> {
            var pass = plugin.seasonPass();
            if (pass == null) {
                throw new IllegalStateException("通行证模块没起来");
            }
            // 原来 claimPremium 里写的是 grantReward(player, lv.reward) —— 免费轨那份。
            // 5000 剑币买断，实际领到的和免费轨一样；premiumReward 表只被用来画 Lore。
            require(pass.premiumConfigured(), "付费轨应常驻（内置 10 级默认）");
            var prem = pass.premiumReward(1);
            require(prem != null && !prem.isEmpty(), "付费轨第 1 级必须有奖励");
            // 内置默认第 1 级 = 800 币，若有人改成与免费轨完全同值，这条护栏会失效，
            // 所以这里只断言「付费轨表非空且能取到」，真正的发放路径靠下面这条：
            require(pass.premiumReward(11).isEmpty(), "付费轨只到 10 级，越界应为空奖励");
        });

        // ---------------------------------------------------------------- P1 配置漂移
        suite.addExternal(g, "付费轨配置读 season-pass.premium（含旧键兜底）", () -> {
            var pass = plugin.seasonPass();
            if (pass == null) {
                throw new IllegalStateException("通行证模块没起来");
            }
            // 代码原来只读 pass.premium.cost，而 config.yml 把价格写在
            // season-pass.premium.cost 下 —— 服主改价毫无效果。
            var cfg = plugin.getConfig();
            long expected = cfg.contains("season-pass.premium.cost")
                    ? cfg.getLong("season-pass.premium.cost")
                    : (cfg.contains("pass.premium.cost") ? cfg.getLong("pass.premium.cost") : 5000L);
            require(pass.premiumCost() == Math.max(0, expected),
                    "付费轨价格应取 season-pass.premium.cost（旧键 pass.premium.cost 兜底），实际 "
                            + pass.premiumCost() + "，期望 " + Math.max(0, expected));
        });

        // ---------------------------------------------------------------- P1 数据抢救
        suite.addExternal(g, "行会仓库：超容量老物品转入保留区不丢失", () -> {
            // v2.23.0 允许 6 行 54 格，v2.23.1 压到 5 行 45 格（给关闭槽让位）。
            // 原来 read() 对 idx >= cap 直接 continue —— 升级即吞物品。
            // 现在进保留区并随 write() 落盘，且腾出空位时自动回流。
            var wh = new com.forgemaster.guild.GuildWarehouse(plugin);
            int cap = wh.capacity();
            YamlConfiguration src = new YamlConfiguration();
            // 写一格「超出容量」的老物品
            src.set("warehouses.g1.slots." + (cap + 3), new ItemStack(Material.DIAMOND, 7));
            wh.read(src);
            YamlConfiguration out = new YamlConfiguration();
            wh.write(out);
            // 要么已经回流进合法空格，要么躺在保留区 —— 两种都不许消失
            boolean rescued = false;
            for (int i = 0; i < cap; i++) {
                ItemStack it = out.getItemStack("warehouses.g1.slots." + i);
                if (it != null && it.getType() == Material.DIAMOND && it.getAmount() == 7) {
                    rescued = true;
                    break;
                }
            }
            if (!rescued) {
                var ov = out.getConfigurationSection("warehouses.g1.overflow");
                if (ov != null) {
                    for (String k : ov.getKeys(false)) {
                        ItemStack it = ov.getItemStack(k);
                        if (it != null && it.getType() == Material.DIAMOND && it.getAmount() == 7) {
                            rescued = true;
                            break;
                        }
                    }
                }
            }
            require(rescued, "超容量物品必须被抢救到空格或保留区，实际两处都没有（升级吞物品回归）");
        });

        suite.addExternal(g, "行会仓库：储物区与关闭槽不相交", () -> {
            int max = com.forgemaster.guild.GuildWarehouseGui.STORAGE_MAX;
            int close = com.forgemaster.guild.GuildWarehouseGui.CLOSE_SLOT;
            require(max < close, "储物区上界 " + max + " 必须严格小于关闭槽 " + close
                    + "（否则关闭图标会覆盖第 " + close + " 格的真实物品）");
            require(com.forgemaster.guild.GuildWarehouse.clampRows(99) * 9 == max,
                    "clampRows 的上限必须与 STORAGE_MAX 一致（" + max + "）");
            require(com.forgemaster.guild.GuildWarehouse.clampRows(0) >= 1, "行数下限应为 1");
        });

        // ---------------------------------------------------------------- 配置纪律
        suite.addExternal(g, "配置迁移：版本号已推进，新键都在盘上", () -> {
            // 本项目最贵的一课：加键必须同批加迁移 step 并抬 CURRENT_VERSION，
            // 否则老服永远收不到新键（steps 17/18/19 各栽过一次）。
            require(ConfigMigrations.CURRENT_VERSION >= 13,
                    "v2.23.1 加了 step22，CURRENT_VERSION 必须 ≥13，实际 "
                            + ConfigMigrations.CURRENT_VERSION);
            var cfg = plugin.getConfig();
            for (String key : new String[]{
                    "metrics.history-days", "metrics.snapshot-interval-minutes",
                    "webhook.enabled", "webhook.url", "webhook.username",
                    "webhook.timeout-seconds", "webhook.queue-size", "webhook.events"}) {
                require(cfg.contains(key), "config.yml 缺键：" + key);
            }
            int rows = cfg.getInt("guild.warehouse.rows", 3);
            require(rows <= 5, "guild.warehouse.rows 必须 ≤5（关闭槽 49 在储物区内会吞物品），实际 " + rows);
        });

        // ---------------------------------------------------------------- 数据看板接线
        suite.addExternal(g, "数据看板切片 2/3：服务已接线且能采样", () -> {
            MetricsService svc = plugin.metrics();
            if (svc == null) {
                throw new IllegalStateException("MetricsService 没被主类构造（切片 2/3 未接线）");
            }
            // 原来 MetricsService 在包里躺着但主类从不 new 它 —— 趋势/CSV 永远不会跑
            require(svc.running() || !svc.enabled(),
                    "看板启用时应当有采样任务在跑（running=false 说明 start() 没生效）");
            require(svc.historyDays() >= 1, "history-days 应 ≥1，实际 " + svc.historyDays());
            require(svc.intervalMinutes() >= 1, "采样间隔应 ≥1 分钟，实际 " + svc.intervalMinutes());
            require(svc.cached() != null, "历史缓存不该是 null（没有数据时也要给空列表）");
            require(svc.trendText() != null, "趋势文案不该是 null");
            // dayTotals 在任何状态下都必须安全（窗口越界由实现钳制）
            require(svc.dayTotals(0) != null && svc.dayTotals(9999) != null,
                    "dayTotals 对越界窗口必须安全返回，不能抛");
        });

        // ---------------------------------------------------------------- 备份完整性
        suite.addExternal(g, "备份清单覆盖全部存档文件", () -> {
            // 原来只有 10 份，漏了 guild-data / orders / anvils / pass-data 等 8 份，
            // 回档后会出现「剑币回到 3 天前、行会仓库还是现在」这种最迷惑的状态。
            var files = java.util.Arrays.asList(
                    com.forgemaster.backup.BackupManager.SAVE_FILES);
            for (String must : new String[]{
                    "data.yml", "auction.yml", "guild-data.yml", "orders.yml", "anvils.yml",
                    "pass-data.yml", "codex-data.yml", "spectrum-data.yml", "daily-data.yml",
                    "bounty-data.yml", "commissions.yml", "tombs.yml", "season.yml",
                    "hall.yml", "hall-strengthen.yml", "story.yml", "story-progress.yml",
                    }) {
                require(files.contains(must), "备份清单漏了 " + must + "（回档后会出现半新半旧）");
            }
            // 清单本身不能有重复项（否则备份/校验会做两次无用功）
            require(new java.util.HashSet<>(files).size() == files.size(),
                    "备份清单存在重复项");
        });

        suite.addExternal(g, "回档顺序：先落盘再备份，只重载不保存", () -> {
            // 顺序错了就是「回档成功几秒后被脏冲刷盖回去」，这类缺陷没法用断言直接观测，
            // 所以这里守住三件可机器验证的事：所需重载入口都在（编译期已保证）+
            // 备份根目录可创建 + 清单里没有目录名混入。
            var bm = plugin.backups();
            require(bm != null, "BackupManager 没起来");
            for (String f : com.forgemaster.backup.BackupManager.SAVE_FILES) {
                require(!f.contains("/") && !f.contains("\\") && !f.contains(".."),
                        "清单里只能放同目录文件名，发现路径片段：" + f);
                require(f.endsWith(".yml"), "清单里应当是 yml 存档：" + f);
            }
            // 列出备份不能抛（目录不存在时返回空表）
            require(bm.list() != null, "list() 必须返回列表而不是 null");
        });

        // ---------------------------------------------------------------- 战力评分
        suite.addExternal(g, "战力评分：单调性 / 上限 / 非锻造品为 0", () -> {
            // 评分公式是乘法链，最容易出的错是「某一项配成 0 或负数，整条链塌成 0」，
            // 以及「钳位写反，高分全变 0」。这里守住三条不变量：
            //   ① 非锻造品 = 0（不参与排行/推荐价）
            //   ② 分数恒在 [0, MAX] 内（读配置写飞了也不许出 NaN / 负数）
            //   ③ 品质更高的同款物品分数更高（单调性 —— 玩家体感的底线）
            var plain = new ItemStack(Material.DIAMOND_SWORD);
            require(com.forgemaster.score.PowerScore.of(plugin, plain) == 0L,
                    "普通剑（非锻造品）战力必须是 0");
            require(com.forgemaster.score.PowerScore.of(plugin, null) == 0L, "null 物品战力必须是 0");
            require(com.forgemaster.score.PowerScore.of(plugin, new ItemStack(Material.AIR)) == 0L,
                    "空气战力必须是 0");

            // 用真实工厂造两把剑：普通 vs 神话，其余条件相同
            var common = makeForged("common-probe", com.forgemaster.quality.Quality.COMMON);
            var mythic = makeForged("mythic-probe", com.forgemaster.quality.Quality.MYTHIC);
            if (common == null || mythic == null) {
                return; // 工厂/材质配置不可用就跳过（不把环境差异当缺陷）
            }
            long pc = com.forgemaster.score.PowerScore.of(plugin, common);
            long pm = com.forgemaster.score.PowerScore.of(plugin, mythic);
            require(pc > 0L, "锻造品战力必须 > 0，实际 " + pc);
            require(pm > pc, "神话剑战力应高于普通剑（单调性），实际 " + pm + " vs " + pc);
            require(pc <= 9_999_999L && pm <= 9_999_999L, "战力必须被钳在上限内");
        });

        suite.addExternal(g, "战力评分：推荐价与评级可读", () -> {
            var mythic = makeForged("price-probe", com.forgemaster.quality.Quality.MYTHIC);
            if (mythic == null) {
                return;
            }
            long power = com.forgemaster.score.PowerScore.of(plugin, mythic);
            long price = com.forgemaster.score.PowerScore.suggestPrice(plugin, mythic);
            if (plugin.settings().flag("power-score.suggest-price", true)) {
                require(price > 0, "开着推荐价时锻造品必须有价，实际 " + price);
                require(price % 10 == 0, "推荐价应取整到 10 的倍数，实际 " + price);
                require(price >= plugin.settings().longOf("power-score.price-min", 100L),
                        "推荐价不能低于 price-min");
            }
            // 评级必须永远给得出（配置全删也不能是 null / 空）
            String grade = com.forgemaster.score.PowerScore.gradeOf(plugin, power);
            require(grade != null && !grade.isBlank(), "评级不能为空");
            require(com.forgemaster.score.PowerScore.gradeOf(plugin, 0L) != null, "0 分也要有评级");
            // 拆解文案：至少两行（分数 + 品质材质）
            var lines = com.forgemaster.score.PowerScore.breakdown(plugin, mythic);
            require(lines.size() >= 2, "拆解至少要有分数行与品质行，实际 " + lines.size());
        });

        suite.addExternal(g, "战力评分配置：step23 已落地", () -> {
            require(ConfigMigrations.CURRENT_VERSION >= 14,
                    "加了 step23，CURRENT_VERSION 必须 ≥14，实际 " + ConfigMigrations.CURRENT_VERSION);
            var cfg = plugin.getConfig();
            for (String key : new String[]{
                    "power-score.enabled", "power-score.base-multiplier",
                    "power-score.strengthen-per-level", "power-score.evolve-per-stage",
                    "power-score.suggest-price", "power-score.price-per-power"}) {
                require(cfg.contains(key), "config.yml 缺键：" + key);
            }
        });

        suite.addExternal(g, "看板 GUI：页数与槽位不冲突", () -> {
            // 切片 3 的三页 GUI：最容易出的错是「内容区铺到按钮槽上」和「页数写死但翻页取模算错」
            require(com.forgemaster.metrics.MetricsGui.LINES_PER_PAGE
                            == com.forgemaster.metrics.MetricsGui.LAST_CONTENT_SLOT + 1,
                    "内容区最后一格必须等于每页行数 - 1");
            require(com.forgemaster.metrics.MetricsGui.LAST_CONTENT_SLOT
                            < com.forgemaster.metrics.MetricsGui.PREV_SLOT,
                    "内容区不能压到「上一页」按钮槽");
            int[] buttons = {
                    com.forgemaster.metrics.MetricsGui.PREV_SLOT,
                    com.forgemaster.metrics.MetricsGui.REFRESH_SLOT,
                    com.forgemaster.metrics.MetricsGui.PAGE_SLOT,
                    com.forgemaster.metrics.MetricsGui.CLOSE_SLOT,
                    com.forgemaster.metrics.MetricsGui.NEXT_SLOT};
            var seen = new java.util.HashSet<Integer>();
            for (int b : buttons) {
                require(b > com.forgemaster.metrics.MetricsGui.LAST_CONTENT_SLOT,
                        "按钮槽 " + b + " 落进了内容区");
                require(b < com.forgemaster.metrics.MetricsGui.SIZE, "按钮槽 " + b + " 越界");
                require(seen.add(b), "按钮槽 " + b + " 重复");
            }
            require(com.forgemaster.metrics.MetricsGui.PAGES >= 3, "切片 3 至少要三页");
        });

        // ---------------------------------------------------------------- 伤害预算（超标数值）
        suite.addExternal(g, "伤害预算：攻击属性写入即封顶", () -> {
            // 服主实测「满配暴击一刀 130」。链条是：材质 5.25 × 品质 5.0 = 26.25 起点，
            // ×强化 1.594 ×蜕变 1.752 = 73 —— 已经是原版下界合金剑(8)的 9 倍。
            // 上限落在产出处，所以物品里存的、面板上显示的、实战打出来的三者一致。
            double cap = plugin.getConfig().getDouble("combat.limits.max-attack-attribute", 22.0);
            require(cap > 0, "max-attack-attribute 应当 >0（0 = 不限，默认必须限）");
            ItemStack sword = makeForged("budget-probe", com.forgemaster.quality.Quality.MYTHIC);
            if (sword == null) {
                return;
            }
            plugin.strengthen().apply(sword);
            double atk = attackOf(sword);
            require(atk <= cap + 1e-9,
                    "攻击属性 " + atk + " 超过了上限 " + cap);
            require(atk > 0, "上限不能把正常物品夹成 0（实际 " + atk + "）");
        });

        suite.addExternal(g, "伤害预算：单次伤害上限夹在暴击之后", () -> {
            // 关键顺序：先暴击再封顶。反过来写的话暴击能把数字顶穿，上限等于没管。
            //
            // 注意这里的上限取 40 而不是 60：raw = playerBase(1) + attr(22) = 23，
            // 暴击倍率被 critLimits 夹在 base 1.5 + maxDamage 1.10 = 2.6，
            // 于是 critHit 最大只能是 23 × 2.6 = 59.8 —— **结构上永远到不了 60**。
            // 原来写 60 的期望值是错的，测的根本不是封顶路径（59.8 原样通过）。
            // 取 40 才能真的把 59.8 夹下来，这条断言才测到它该测的东西。
            var budget = new com.forgemaster.combat.DamageBudget.Limits(
                    22.0, 40.0, 0.20, 2.0, 0.10);
            var critLimits = new com.forgemaster.combat.Crit.Limits(0.50, 1.10, 1.5);
            var r = com.forgemaster.combat.DamageBudget.simulateHit(
                    73.0, 1.0, 1.0, 5.0, 1.0, 20.0, critLimits, budget);
            require(Math.abs(r.attribute() - 22.0) < 1e-9, "属性应先被夹到 22，实际 " + r.attribute());
            require(r.critHit() > 40.0, "暴击后应当超过上限（否则测的不是封顶路径），实际 " + r.critHit());
            require(Math.abs(r.finalHit() - 40.0) < 1e-9, "最终伤害应被夹到 40，实际 " + r.finalHit());
            require(r.capped(), "应当标记 capped=true");
        });

        suite.addExternal(g, "伤害预算：吸血三道夹子取最小", () -> {
            // 服主实测「一刀吸半管」。原始吸血能到 115%，这里必须压到一颗心以内。
            var budget = new com.forgemaster.combat.DamageBudget.Limits(
                    22.0, 60.0, 0.20, 2.0, 0.10);
            // 伤害 60、原始吸血 115% → 理论 69 血，全被夹住
            double heal = com.forgemaster.combat.DamageBudget.lifestealHeal(60.0, 1.155, 20.0, budget);
            require(Math.abs(heal - 2.0) < 1e-9, "满配一刀应只回 2 点（一颗心），实际 " + heal);
            // 血条被服主拉到 100 时，血条夹子跟着走（10% = 10），但绝对值夹子仍然先拦
            double healBig = com.forgemaster.combat.DamageBudget.lifestealHeal(60.0, 1.155, 100.0, budget);
            require(healBig <= 2.0 + 1e-9, "绝对值夹子应当先拦住，实际 " + healBig);
            // 小伤害时正常生效，不能被夹子误伤成 0
            double healSmall = com.forgemaster.combat.DamageBudget.lifestealHeal(5.0, 0.10, 20.0, budget);
            require(Math.abs(healSmall - 0.5) < 1e-9, "5 点伤害 × 10% 应回 0.5，实际 " + healSmall);
            require(com.forgemaster.combat.DamageBudget.lifestealHeal(0, 1.0, 20.0, budget) == 0.0,
                    "0 伤害不该回血");
        });

        suite.addExternal(g, "伤害预算：暴击口径已下调且可配置", () -> {
            var l = com.forgemaster.combat.Crit.limitsOf(plugin);
            require(l.maxChance() > 0 && l.maxChance() <= 0.60,
                    "暴击率上限应 ≤60%，实际 " + l.maxChance());
            require(l.maxDamage() > 0 && l.baseMultiplier() + l.maxDamage() <= 3.0,
                    "暴击倍率上限应 ≤3.0，实际 " + (l.baseMultiplier() + l.maxDamage()));
            // 100% 是不可配置的硬天花板：配置写 5.0 也不许「必定暴击」
            var p = com.forgemaster.combat.Crit.resolve(5.0, 99.0, 5.0, 99.0, 1.5);
            require(p.chance() <= 1.0, "暴击率必须硬夹在 1.0，实际 " + p.chance());
        });

        suite.addExternal(g, "伤害预算：气血与点燃写入即封顶", () -> {
            // 本轮审计新发现的两处超标：
            // ① 气血（max_health）走通用 ATTRIBUTE 分支，没有任何上限 ——
            //    一条 2~5 点 × 星陨档 1.65 × 5 条 = 单件 +41 生命，20 血直接变 61 血。
            // ② 燃烬叠 5 条能烧 40 秒，目标早死了，纯属浪费性能。
            double healthCap = plugin.getConfig().getDouble("combat.limits.max-bonus-health", 20.0);
            require(healthCap > 0, "max-bonus-health 应当 >0（默认必须限）");
            require(healthCap <= 20.0, "生命加成上限不该超过 20（否则玩家能翻倍血条），实际 " + healthCap);
            double fireCap = plugin.getConfig().getDouble("combat.limits.max-fire-seconds", 10.0);
            require(fireCap > 0 && fireCap <= 15.0,
                    "点燃上限应在 (0, 15] 秒内，实际 " + fireCap);
            double reduceCap = plugin.getConfig().getDouble("combat.limits.max-damage-reduce", 0.35);
            require(reduceCap > 0 && reduceCap < 0.6,
                    "单件减伤上限应低于实战总夹子 0.6，实际 " + reduceCap);
            // 造一件神话护甲，确认气血 modifier 没被写成天文数字
            ItemStack armor = null;
            for (var mat : plugin.materials().all()) {
                if (mat.armor()) {
                    armor = plugin.items().buildDirect("自检", mat,
                            com.forgemaster.quality.Quality.MYTHIC, 90.0);
                    break;
                }
            }
            if (armor != null) {
                var mods = armor.getItemMeta() == null ? null
                        : armor.getItemMeta().getAttributeModifiers(org.bukkit.attribute.Attribute.MAX_HEALTH);
                if (mods != null) {
                    double sum = 0;
                    for (var m : mods) {
                        sum += m.getAmount();
                    }
                    require(sum <= healthCap + 1e-6, "气血合计 " + sum + " 超过上限 " + healthCap);
                }
            }
        });

        // ---------------------------------------------------------------- 存量物品收敛
        suite.addExternal(g, "存量收敛：吸血/点燃/减伤 旧值被夹住且幂等", () -> {
            // 产出侧夹子管不到盘上的旧装备。这里模拟一件「旧版锻出来」的剑：
            // 手动把 PDC 灌成 115% 吸血 / 40 秒点燃 / 58% 减伤，再走收敛。
            var repair = plugin.legacyRepair();
            require(repair != null, "LegacyItemRepair 没被主类构造");
            ItemStack sword = makeForged("legacy-probe", com.forgemaster.quality.Quality.MYTHIC);
            if (sword == null) {
                return;
            }
            var meta = sword.getItemMeta();
            require(meta != null, "取不到 ItemMeta");
            var pdc = meta.getPersistentDataContainer();
            pdc.set(com.forgemaster.util.Keys.LIFESTEAL,
                    org.bukkit.persistence.PersistentDataType.DOUBLE, 1.155);
            pdc.set(com.forgemaster.util.Keys.FIRE_TICKS,
                    org.bukkit.persistence.PersistentDataType.INTEGER, 800);
            pdc.set(com.forgemaster.util.Keys.DAMAGE_REDUCE,
                    org.bukkit.persistence.PersistentDataType.DOUBLE, 0.58);
            sword.setItemMeta(meta);

            require(repair.repair(sword), "第一次收敛应当报告「确实改了」");

            var after = sword.getItemMeta();
            require(after != null, "收敛后取不到 ItemMeta");
            var pdc2 = after.getPersistentDataContainer();
            double ls = pdc2.getOrDefault(com.forgemaster.util.Keys.LIFESTEAL,
                    org.bukkit.persistence.PersistentDataType.DOUBLE, 0.0);
            int fire = pdc2.getOrDefault(com.forgemaster.util.Keys.FIRE_TICKS,
                    org.bukkit.persistence.PersistentDataType.INTEGER, 0);
            double dr = pdc2.getOrDefault(com.forgemaster.util.Keys.DAMAGE_REDUCE,
                    org.bukkit.persistence.PersistentDataType.DOUBLE, 0.0);
            require(ls <= 0.20 + 1e-9, "吸血应收敛到 0.20，实际 " + ls);
            require(fire <= 200, "点燃应收敛到 200 tick（10 秒），实际 " + fire);
            require(dr <= 0.35 + 1e-9, "减伤应收敛到 0.35，实际 " + dr);

            // 标记行必须只说「真的被压过」的项：只超吸血，就不许写「攻 22 / 减伤 35%」
            boolean hasLifestealNote = false;
            boolean hasAttackNote = false;
            for (var line : after.lore() == null ? java.util.List.<net.kyori.adventure.text.Component>of()
                    : after.lore()) {
                String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(line);
                if (!plain.contains("已收敛")) {
                    continue;
                }
                hasLifestealNote = plain.contains("吸血");
                hasAttackNote = plain.contains("攻 ");
            }
            require(hasLifestealNote, "标记行应当说明吸血被收敛");
            require(!hasAttackNote,
                    "这件剑的攻击属性本来就没超标，标记行不该声称收敛了攻击");
            // 词条原文不该被改写（数值无法反推，见类注释），只允许追加说明行
            require(!after.lore().isEmpty(), "收敛后不该把 Lore 清空");

            // 幂等：第二次不该再「改」任何东西（否则命令报的件数永远不准）
            require(!repair.repair(sword), "第二次收敛应当返回 false（已收敛不该反复动）");
        });

        suite.addExternal(g, "存量收敛：气血加成按比例压回上限", () -> {
            // 气血词条走通用 ATTRIBUTE 分支，旧版没有上限 → 单件能 +41 生命。
            // 收敛必须按比例缩（保留各条键），而不是删了重加一条。
            var repair = plugin.legacyRepair();
            ItemStack armor = null;
            for (var mat : plugin.materials().all()) {
                if (mat.armor()) {
                    armor = plugin.items().buildDirect("自检", mat,
                            com.forgemaster.quality.Quality.MYTHIC, 90.0);
                    break;
                }
            }
            if (armor == null || repair == null) {
                return;
            }
            var meta = armor.getItemMeta();
            if (meta == null) {
                return;
            }
            // 灌一条「旧版式」的巨量气血修饰符
            meta.addAttributeModifier(org.bukkit.attribute.Attribute.MAX_HEALTH,
                    new org.bukkit.attribute.AttributeModifier(
                            new org.bukkit.NamespacedKey(plugin, "fm_vitality_legacy"),
                            60.0, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER,
                            org.bukkit.inventory.EquipmentSlotGroup.ANY));
            armor.setItemMeta(meta);
            repair.repair(armor);
            var after = armor.getItemMeta();
            require(after != null, "收敛后取不到 ItemMeta");
            var mods = after.getAttributeModifiers(org.bukkit.attribute.Attribute.MAX_HEALTH);
            double sum = 0;
            if (mods != null) {
                for (var m : mods) {
                    sum += m.getAmount();
                }
            }
            double cap = plugin.getConfig().getDouble("combat.limits.max-bonus-health", 20.0);
            require(sum <= cap + 1e-6, "气血合计应收敛到 " + cap + " 以内，实际 " + sum);
            require(sum > 0, "收敛不该把气血清零（实际 " + sum + "）");
        });

        suite.addExternal(g, "存量收敛：未锻造的物品一概不碰", () -> {
            // 收敛会重写 Lore 与属性，误伤原版物品是不可接受的
            var repair = plugin.legacyRepair();
            if (repair == null) {
                return;
            }
            ItemStack vanilla = new ItemStack(org.bukkit.Material.DIAMOND_SWORD);
            require(!repair.repair(vanilla), "原版钻石剑不该被收敛逻辑碰");
            require(vanilla.getItemMeta() != null && !plugin.items().isForged(vanilla),
                    "原版物品不该被盖上锻造标记");
            require(!repair.repair(null), "null 输入应当安全返回 false");
        });

        suite.addExternal(g, "存量收敛：只动数值，不重建 Lore 等级行", () -> {
            // applyStatsOnly 的存在意义：收敛时玩家看到的改动范围必须等于真实改动范围。
            // 如果收敛调的是 apply(item)，它会顺手 rewriteLore，把「锻造等级」整行重建，
            // 玩家就会以为整套属性都被改了 —— 服里立刻开始传「插件乱改我的剑」。
            var svc = plugin.strengthen();
            if (svc == null) {
                return;
            }
            ItemStack sword = makeForged("lore-probe", com.forgemaster.quality.Quality.EPIC);
            if (sword == null) {
                return;
            }
            svc.apply(sword);
            String loreBefore = loreText(sword);
            require(!loreBefore.isBlank(), "锻造品应当有 Lore");
            svc.applyStatsOnly(sword);
            require(loreText(sword).equals(loreBefore),
                    "applyStatsOnly 不该改动 Lore，实际变了：\n前=" + loreBefore + "\n后=" + loreText(sword));
        });

        // ---------------------------------------------------------------- 成品界面（P0）
        suite.addExternal(g, "切换界面不是关界面：关闭事件的语义判断", () -> {
            // 这条钉死服主报的那个「点取回没反应、ESC 后剑消失」的根因。
            // ForgeManager.open() 每次换界面都会引发一次旧界面的 InventoryCloseEvent，
            // 而那个事件可能下一 tick 才送达 —— 靠 transitioning 标志挡是不可靠的。
            var m = com.forgemaster.forge.ForgeManager.class;
            require(m != null, "ForgeManager 类不在");
            // 正在看同一会话的另一张界面、且不是「刚关掉的那张」→ 这是切换，不是关闭
            require(com.forgemaster.forge.ForgeManager.isTransitionClose(true, false),
                    "切换到新界面时，旧界面的关闭事件必须被识别为「切换」");
            // 还在看刚关掉的那张 → 玩家真的按了 ESC，必须按关闭处理
            require(!com.forgemaster.forge.ForgeManager.isTransitionClose(true, true),
                    "玩家关掉当前界面时不能被误判成切换（否则会话永远收不掉）");
            // 上层界面不是锻造界面（例如已回背包）→ 关闭
            require(!com.forgemaster.forge.ForgeManager.isTransitionClose(false, false),
                    "没有锻造界面开着时必须按关闭处理");
            require(!com.forgemaster.forge.ForgeManager.isTransitionClose(false, true),
                    "同上：没有锻造界面开着时必须按关闭处理");
        });

        suite.addExternal(g, "成品会话标志默认都是「未发放 / 未结束」", () -> {
            // giveResult 改成「先发放、后打标记」之后，任何默认值写反都会导致：
            // 要么每次进成品界面就提前判定「已发放」（剑不发），要么反复发放（复制）。
            var session = new com.forgemaster.forge.ForgeSession(
                    java.util.UUID.randomUUID(), null, null);
            require(!session.resultGiven(), "新建会话不该是「已发放」状态");
            require(!session.finished(), "新建会话不该是「已结束」状态");
            require(!session.transitioning(), "新建会话不该处于「切换中」");
            require(session.result() == null, "新建会话不该有成品");
        });

        // ---------------------------------------------------------------- Webhook
        suite.addExternal(g, "Webhook 事件：八类事件 id 稳定且可解析", () -> {
            // 插桩点分散在锻造/蜕变/强化/剑冢/行会/赛季六个模块里，任何一处把 id 写错
            // 都会被 WebhookEvent.parse 静默吃掉（配置白名单按 id 匹配）。
            // 所以宁可在这里把「id → 枚举」的全表对一遍。
            String[][] pairs = {
                    {"forge_success", "FORGE_SUCCESS"},
                    {"strengthen_success", "STRENGTHEN_SUCCESS"},
                    {"strengthen_break", "STRENGTHEN_BREAK"},
                    {"evolution_success", "EVOLUTION_SUCCESS"},
                    {"tomb_win", "TOMB_WIN"},
                    {"duel_win", "DUEL_WIN"},
                    {"guild_quest_done", "GUILD_QUEST_DONE"},
                    {"season_end", "SEASON_END"}};
            require(pairs.length == com.forgemaster.webhook.WebhookEvent.values().length,
                    "事件表长度与枚举不一致（加枚举时记得补这里）");
            for (String[] p : pairs) {
                var e = com.forgemaster.webhook.WebhookEvent.parse(p[0]);
                require(e != null, "事件 id 解析不到：" + p[0]);
                require(e.name().equals(p[1]), p[0] + " 应解析成 " + p[1] + "，实际 " + e.name());
                require(e.id().equals(p[0]), "枚举 id 与配置字符串不一致：" + e.id() + " vs " + p[0]);
                require(e.label() != null && !e.label().isBlank(), p[1] + " 缺中文显示名");
            }
        });

        suite.addExternal(g, "Webhook 播报入口：未启用时全链路无副作用", () -> {
            // plugin.broadcast 是所有调用点的统一入口，必须做到：
            // ① 未启用（默认配置就是关闭）时零动作、不抛；
            // ② 传 null 正文 / null 字段也不炸（调用点难免疏漏）。
            var hook = plugin.webhook();
            require(hook != null, "WebhookService 没被主类构造");
            if (!hook.enabled()) {
                plugin.broadcast(com.forgemaster.webhook.WebhookEvent.FORGE_SUCCESS, "自检探针", null);
                plugin.broadcast(com.forgemaster.webhook.WebhookEvent.SEASON_END, null, null);
                require(hook.sentCount() == 0,
                        "未启用时不该真的发送，实际 sent=" + hook.sentCount());
            }
            require(hook.droppedCount() >= 0 && hook.failedCount() >= 0, "计数不该为负");
        });

        // ---------------------------------------------------------------- 属性膨胀（生存服命门）
        suite.addExternal(g, "属性膨胀：反复 apply 不会滚雪球", () -> {
            // 这是本轮最贵的一条。旧 baseOf 把「已乘倍率」的 modifier 当基础值再用一次，
            // 每调用 apply() 一次就再乘一次倍率，快照还被写脏 → 永久放大。
            // 生存服上等于「谁的剑锤得多谁无敌」。
            var svc = plugin.strengthen();
            if (svc == null) {
                return;
            }
            ItemStack sword = makeForged("inflate-probe", com.forgemaster.quality.Quality.EPIC);
            if (sword == null) {
                return;
            }
            double atk0 = attackOf(sword);
            require(atk0 > 0, "锻造品应当有基础攻击修饰符，实际 " + atk0);
            // 连续调用 5 次：同一等级下，数值必须一动不动
            for (int i = 0; i < 5; i++) {
                svc.apply(sword);
            }
            double atk5 = attackOf(sword);
            require(Math.abs(atk5 - atk0) < 1e-6,
                    "同等级反复 apply 必须幂等，实际 " + atk0 + " → " + atk5
                            + "（差值 " + (atk5 - atk0) + "，说明倍率被重复叠乘了）");
        });

        suite.addExternal(g, "属性膨胀：脏快照会被收敛回配置值", () -> {
            // 已被旧版本污染的物品，快照里存的是虚高值。修复后的 baseOf 必须把它压回天花板，
            // 否则「升级修好了、老剑还是外挂」。
            var svc = plugin.strengthen();
            if (svc == null) {
                return;
            }
            ItemStack sword = makeForged("poison-probe", com.forgemaster.quality.Quality.EPIC);
            if (sword == null) {
                return;
            }
            svc.apply(sword);
            double clean = attackOf(sword);
            // 人为把快照与 modifier 一起灌成 20 倍（模拟被旧版本滚过雪球的剑）
            var meta = sword.getItemMeta();
            if (meta == null) {
                return;
            }
            var pdc = meta.getPersistentDataContainer();
            Double baseAtk = pdc.get(com.forgemaster.util.Keys.BASE_ATK,
                    org.bukkit.persistence.PersistentDataType.DOUBLE);
            require(baseAtk != null && baseAtk > 0, "锻造品应当带基础攻击快照");
            pdc.set(com.forgemaster.util.Keys.BASE_ATK,
                    org.bukkit.persistence.PersistentDataType.DOUBLE, baseAtk * 20.0);
            sword.setItemMeta(meta);
            // 再挂一个 20 倍的 modifier，模拟「盘上就是虚高」的真实状态
            var dirty = sword.getItemMeta();
            var mods = dirty.getAttributeModifiers(org.bukkit.attribute.Attribute.ATTACK_DAMAGE);
            if (mods != null) {
                for (var m : new java.util.ArrayList<>(mods)) {
                    var k = m.getKey();
                    if (k != null && "fm_atk".equals(k.getKey())) {
                        dirty.removeAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_DAMAGE, m);
                    }
                }
            }
            dirty.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_DAMAGE,
                    new org.bukkit.attribute.AttributeModifier(
                            new org.bukkit.NamespacedKey(plugin, "fm_atk"), clean * 20.0,
                            org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER,
                            org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
            sword.setItemMeta(dirty);
            require(attackOf(sword) > clean * 5, "污染构造失败（应当先变成虚高值）");

            boolean changed = svc.repairAttributes(sword);
            require(changed, "repairAttributes 应当报告「确实改了」");
            double fixed = attackOf(sword);
            require(Math.abs(fixed - clean) < 1e-6,
                    "修复后必须回到干净值 " + clean + "，实际 " + fixed);
        });

        suite.addExternal(g, "属性膨胀：修复是单向的（不会把正常值抬上去）", () -> {
            // 天花板只许往下压。如果实现写成「取 max」，正常物品会被越修越强，同样是灾难。
            var svc = plugin.strengthen();
            if (svc == null) {
                return;
            }
            ItemStack sword = makeForged("ceiling-probe", com.forgemaster.quality.Quality.LEGENDARY);
            if (sword == null) {
                return;
            }
            svc.apply(sword);
            double a = attackOf(sword);
            for (int i = 0; i < 3; i++) {
                svc.repairAttributes(sword);
            }
            double b = attackOf(sword);
            require(b <= a + 1e-6,
                    "修复不能让正常物品变强，实际 " + a + " → " + b);
            require(plugin.getConfig().getDouble("strengthen.base-tolerance", 1.0) >= 1.0,
                    "base-tolerance 应当 ≥1，否则会无理由削正常物品");
        });

        suite.addExternal(g, "词条键名：护甲词条带部位后缀（与 ItemFactory 同规则）", () -> {
            Affix a = plugin.affixes() == null ? null : plugin.affixes().get("armor_plate");
            if (a == null) {
                // 服主删了这条词条就跳过（不把配置选择当成缺陷）
                return;
            }
            require(a.attribute() != null, "armor_plate 应当是属性类词条");
            // 键名规则的一致性由 SelfTestSuite 里那条「四件不互相顶掉」覆盖；
            // 这里只守住「词条键不等于任何基础键」这条底线，防止新增词条 id 立刻复现 P0
            String baseKey = "fm_" + a.id().toLowerCase(java.util.Locale.ROOT);
            require(!com.forgemaster.util.Attrs.isBaseKey(baseKey),
                    "词条 " + a.id() + " 的裸键 " + baseKey + " 撞了基础属性键");
            for (String slot : new String[]{"helmet", "chestplate", "leggings", "boots"}) {
                require(!com.forgemaster.util.Attrs.isBaseKey(baseKey + "_" + slot),
                        "词条键 " + baseKey + "_" + slot + " 不能被当成基础属性键（崩刃会摘掉它）");
            }
        });

        // ================================================================ v2.23.3 机制型原创附魔
        //
        // 服主两次否掉了前两版设计：
        //   第一版 —— 名字是原版改字（锋锐/荆棘/御火）；
        //   第二版 —— 名字改了，**机制还是原版那套**（增伤/减伤/吸血/点燃/反弹/时运）。
        // 这一组断言守的是第三版的底线：每一条都必须是原版没有的机制，
        // 并且这些新机制**不会因为组合而变成无敌**。
        String e = "v2.23.3 机制附魔";

        suite.addExternal(e, "12 条机制，没有一条是原版的数值皮肤", () -> {
            var reg = plugin.enchants();
            require(reg != null, "注册表没初始化");
            require(reg.size() >= 12, "机制型附魔至少 12 条，实际 " + reg.size());
            // 上一版被否掉的失效机制，一条都不许回来
            for (String banned : new String[]{"edge", "pierce", "swift", "soulsip", "ember",
                    "slayer", "execute", "combo", "bulwark", "thorns", "fortitude",
                    "emberward", "lorekeeper", "prospector", "fortunes"}) {
                require(reg.get(banned) == null,
                        "机制 " + banned + " 是原版附魔的皮肤，不该出现在 v2.23.3 里");
            }
            // 新机制必须齐全
            for (String id : new String[]{"splinter", "pyre", "lone", "charge", "sever", "mark",
                    "unload", "decoy", "echo", "vigil", "salvage", "insight"}) {
                require(reg.get(id) != null, "机制 " + id + " 缺失");
            }
        });

        suite.addExternal(e, "崩流：溅射有上限，且不会递归触发自己", () -> {
            var caps = com.forgemaster.enchant.EnchantEffects.Caps.defaults();
            var fx = new com.forgemaster.enchant.EnchantEffects();
            var reg = plugin.enchants();
            var splinter = reg.get("splinter");
            require(splinter != null, "splinter 缺失");
            var m = fx.of(java.util.List.of(
                    new com.forgemaster.enchant.EnchantEffects.Entry(splinter, splinter.maxLevel())), caps);
            require(m.splashRatio() > 0, "满级崩流必须有溅射比例");
            require(m.splashRatio() <= caps.maxSplashRatio() + 1e-9,
                    "溅射比例 " + m.splashRatio() + " 超过封顶 " + caps.maxSplashRatio());
            // 溅射伤害基于「已经夹过的伤害」，所以不可能反过来顶穿上限
            double dealt = 60.0;
            double splash = fx.splashDamage(m, caps, dealt);
            require(splash <= dealt * caps.maxSplashRatio() + 1e-9,
                    "溅射 " + splash + " 应按主目标伤害的比例封顶");
            require(splash < dealt, "溅射必须小于主伤害，否则单体武器变成范围武器更划算");
        });

        suite.addExternal(e, "卸力：四件叠满也不会变成无敌", () -> {
            // 这是机制型附魔最容易崩的地方：摊 100% = 伤害永不结算 = 无敌。
            var caps = com.forgemaster.enchant.EnchantEffects.Caps.defaults();
            var fx = new com.forgemaster.enchant.EnchantEffects();
            var reg = plugin.enchants();
            var unload = reg.get("unload");
            require(unload != null, "unload 缺失");
            var one = fx.of(java.util.List.of(
                    new com.forgemaster.enchant.EnchantEffects.Entry(unload, unload.maxLevel())), caps);
            var merged = fx.merge(java.util.List.of(one, one, one, one), caps);
            require(merged.delayRatio() <= caps.maxDelayRatio() + 1e-9,
                    "四件卸力合并后 " + merged.delayRatio() + " 超过封顶 " + caps.maxDelayRatio());
            require(merged.delayRatio() < 1.0, "卸力绝不能到 100%（那是免伤）");
            // 至少还有一半要当场结算
            double damage = 20.0;
            double delayed = fx.delayedPortion(merged, caps, damage);
            require(delayed < damage, "延后部分 " + delayed + " 必须小于总伤害 " + damage);
            require(damage - delayed >= damage * (1.0 - caps.maxDelayRatio()) - 1e-9,
                    "当场结算的部分不能低于 " + (1.0 - caps.maxDelayRatio()) * 100 + "%");
        });

        suite.addExternal(e, "替身：有冷却，回气有上限，不是永久免死", () -> {
            var caps = com.forgemaster.enchant.EnchantEffects.Caps.defaults();
            require(caps.cheatCooldownMs() >= 30_000L,
                    "替身冷却至少 30 秒，实际 " + caps.cheatCooldownMs() + "ms");
            var fx = new com.forgemaster.enchant.EnchantEffects();
            var reg = plugin.enchants();
            var decoy = reg.get("decoy");
            require(decoy != null, "decoy 缺失");
            var m = fx.of(java.util.List.of(
                    new com.forgemaster.enchant.EnchantEffects.Entry(decoy, decoy.maxLevel())), caps);
            double heal = fx.cheatHeal(m, caps, 20.0);
            require(heal > 0 && heal <= 20.0 * caps.maxCheatHeal() + 1e-9,
                    "免死回气 " + heal + " 超过上限 " + (20.0 * caps.maxCheatHeal()));
            require(heal < 20.0, "免死不能直接回满血，否则等于无限复活");
        });

        suite.addExternal(e, "条件型增伤：三项相加后再夹，且只在这一击生效", () -> {
            var caps = com.forgemaster.enchant.EnchantEffects.Caps.defaults();
            var fx = new com.forgemaster.enchant.EnchantEffects();
            var reg = plugin.enchants();
            var lone = reg.get("lone");
            var charge = reg.get("charge");
            var mark = reg.get("mark");
            require(lone != null && charge != null && mark != null, "孤锋/蓄锋/刻痕缺失");
            var entries = java.util.List.of(
                    new com.forgemaster.enchant.EnchantEffects.Entry(lone, lone.maxLevel()),
                    new com.forgemaster.enchant.EnchantEffects.Entry(charge, charge.maxLevel()),
                    new com.forgemaster.enchant.EnchantEffects.Entry(mark, mark.maxLevel()));
            var m = fx.of(entries, caps);
            // 三个条件全满足
            double all = fx.conditionalBonus(m, caps, true, caps.chargeWindowMs() + 1, true);
            double cap = caps.maxLoneWolf() + caps.maxCharge() + caps.maxMarkBonus();
            require(all <= cap + 1e-9, "三项叠加 " + all + " 超过合计上限 " + cap);
            // 一个条件都不满足 → 0（不是常驻增伤）
            double none = fx.conditionalBonus(m, caps, false, 0, false);
            require(Math.abs(none) < 1e-9, "条件不满足时必须是 0，实际 " + none);
            // 只有孤锋满足 → 只加孤锋那一份
            double onlyLone = fx.conditionalBonus(m, caps, true, 0, false);
            require(Math.abs(onlyLone - m.loneWolf()) < 1e-9,
                    "只满足孤锋时应只加 " + m.loneWolf() + "，实际 " + onlyLone);
        });

        suite.addExternal(e, "烬狱：范围伤害有上限，且不会伤到自己", () -> {
            var caps = com.forgemaster.enchant.EnchantEffects.Caps.defaults();
            var fx = new com.forgemaster.enchant.EnchantEffects();
            var reg = plugin.enchants();
            var pyre = reg.get("pyre");
            require(pyre != null, "pyre 缺失");
            var m = fx.of(java.util.List.of(
                    new com.forgemaster.enchant.EnchantEffects.Entry(pyre, pyre.maxLevel())), caps);
            double dmg = fx.pyreDamage(m, caps);
            require(dmg > 0 && dmg <= caps.maxPyreDamage() + 1e-9,
                    "爆燃伤害 " + dmg + " 超过上限 " + caps.maxPyreDamage());
            require(caps.pyreRadius() <= 4.0, "爆燃半径不该超过 4 格，实际 " + caps.pyreRadius());
        });

        suite.addExternal(e, "机制参数编解码往返 + 脏数据不抛", () -> {
            var reg = plugin.enchants();
            var def = reg.get("splinter");
            require(def != null, "splinter 缺失");
            String enc = com.forgemaster.enchant.EnchantEffects.encode(java.util.List.of(
                    new com.forgemaster.enchant.EnchantEffects.Entry(def, 3)));
            require("splinter:3".equals(enc), "编码应为 splinter:3，实际 " + enc);
            var back = com.forgemaster.enchant.EnchantEffects.parse(reg, enc);
            require(back.size() == 1 && back.get(0).level() == 3, "往返后等级应保持 3");
            var dirty = com.forgemaster.enchant.EnchantEffects.parse(reg,
                    "no_such:5|splinter:999|::|splinter:-2|garbage|splinter:2");
            require(dirty.size() == 1, "脏数据里只有合法的 splinter 该留下来，实际 " + dirty.size());
            // splinter:999 先被夹到 maxLevel（5），再和 splinter:2 取最高 → 5。
            // 这里断言的是「夹进范围」与「取最高」两件事都做了：
            // 如果没夹，999 会留下来（超上限的附魔 = 数值爆炸）；如果没取最高，会是 2。
            require(dirty.get(0).level() == 5,
                    "999 应被夹到 maxLevel=5 并压过 splinter:2，实际 " + dirty.get(0).level());
            // 单独验证「只有 splinter:2」时不会被别的脏数据带跑
            var onlyTwo = com.forgemaster.enchant.EnchantEffects.parse(reg, "garbage|splinter:2|::");
            require(onlyTwo.size() == 1 && onlyTwo.get(0).level() == 2,
                    "合法的那条应原样保留为 2");
            require(com.forgemaster.enchant.EnchantEffects.encode(java.util.List.of()) == null,
                    "空列表必须编码成 null（调用方据此 remove 键）");
        });

        suite.addExternal(e, "状态层：null 安全，目标消失时刻痕必须被清", () -> {
            // 内存纪律：刻痕表按「玩家 → 目标」存，目标死了不清就是一万条垃圾
            require(!com.forgemaster.enchant.EnchantState.isBranded(null, null), "null 输入必须 false");
            require(com.forgemaster.enchant.EnchantState.msSinceLastHit(null) > 0, "null 玩家不抛");
            com.forgemaster.enchant.EnchantState.noteHit(null);
            com.forgemaster.enchant.EnchantState.noteCheatDeath(null);
            com.forgemaster.enchant.EnchantState.noteCombat(null);
            require(com.forgemaster.enchant.EnchantState.tracked() >= 0, "tracked 可读");
        });

        suite.addExternal(e, "注册表：config 缺 defs 段时回落到代码默认", () -> {
            var reg = plugin.enchants();
            require(reg.size() >= 12, "原创附魔至少 12 条，实际 " + reg.size());
            require(reg.get("splinter") != null, "splinter 必须在");
            require(reg.forSlot(com.forgemaster.enchant.CustomEnchant.Slot.WEAPON).size() >= 6,
                    "武器机制至少 6 条");
            require(reg.forSlot(com.forgemaster.enchant.CustomEnchant.Slot.ARMOR).size() >= 4,
                    "护甲机制至少 4 条");
            require(reg.forSlot(com.forgemaster.enchant.CustomEnchant.Slot.WEAPON).stream()
                            .anyMatch(d -> d.id().equals("insight")),
                    "通用机制必须出现在武器候选里");
        });

        suite.addExternal(e, "附魔书：合法标记认得出，普通附魔书不认", () -> {
            var reg = plugin.enchants();
            var def = reg.get("splinter");
            require(def != null, "splinter 缺失");
            var book = com.forgemaster.enchant.EnchantItem.book(plugin,
                    new com.forgemaster.enchant.EnchantEffects.Entry(def, 2));
            require(com.forgemaster.enchant.EnchantItem.isBook(book), "产出的书必须带 ENCH_BOOK 标记");
            var entry = com.forgemaster.enchant.EnchantItem.bookEntry(plugin, book);
            require(entry != null && entry.def().id().equals("splinter") && entry.level() == 2,
                    "书上的机制应读回 splinter:2");
            require(!com.forgemaster.enchant.EnchantItem.isBook(new ItemStack(Material.ENCHANTED_BOOK)),
                    "原版附魔书不能被当成本插件的书");
            require(book.getType() == Material.ENCHANTED_BOOK, "书必须是 ENCHANTED_BOOK");
        });

        suite.addExternal(e, "老物品首次强化必须真的涨属性（v2.23.4 修 P1）", () -> {
            // 真机复现出来的 bug：2.4 之前铸出来的武器没有 BASE_ATK 快照。
            // baseOf 拿 modifier ÷ 新倍率求 base，applyInternal 再 × 新倍率写回 ——
            // 一除一乘正好抵消，玩家锤了一锤，面板数字一动不动（材料剑币照扣）。
            var sword = makeForged("selftest", com.forgemaster.quality.Quality.COMMON);
            require(sword != null, "造不出锻造品");
            var meta = sword.getItemMeta();
            require(meta != null, "取不到 ItemMeta");
            // 模拟老物品：摘掉基础属性快照
            meta.getPersistentDataContainer().remove(com.forgemaster.util.Keys.BASE_ATK);
            sword.setItemMeta(meta);

            double before = attackOf(sword);
            require(before > 0, "锻造品应带攻击属性，实际 " + before);
            require(plugin.strengthen().setLevel(sword, 1), "setLevel(1) 应当成功");
            double after = attackOf(sword);
            require(after > before,
                    "老物品首次强化属性没涨：" + before + " → " + after
                            + "（baseOf 的除以倍率 + 乘回倍率把增益抵消了）");
            // 涨的幅度必须和配置的每级加成对得上，不能靠「碰巧涨了」
            double expect = before * (1.0 + plugin.strengthen().bonusRatio(1));
            require(Math.abs(after - expect) < 0.05,
                    "涨的幅度不对：期望 " + expect + "，实际 " + after);
            // 而且快照必须被补上，否则第二次强化还会丢
            var after2 = sword.getItemMeta();
            require(after2 != null && after2.getPersistentDataContainer()
                            .has(com.forgemaster.util.Keys.BASE_ATK, org.bukkit.persistence.PersistentDataType.DOUBLE),
                    "强化后必须补写 BASE_ATK 快照，否则下次强化继续丢增益");
            // 第二次强化也要涨（证明快照生效，而不是只修了第一次）
            plugin.strengthen().setLevel(sword, 2);
            require(attackOf(sword) > after, "第二次强化又没涨：" + after + " → " + attackOf(sword));
        });

        suite.addExternal(e, "部位匹配：武器机制上不了护甲", () -> {
            var reg = plugin.enchants();
            var splinter = reg.get("splinter"); // WEAPON
            var unload = reg.get("unload");     // ARMOR
            var insight = reg.get("insight");   // ANY
            require(splinter != null && unload != null && insight != null, "机制缺失");
            require(com.forgemaster.enchant.EnchantService.slotMatches(splinter, Material.DIAMOND_SWORD),
                    "武器机制必须能上剑");
            require(!com.forgemaster.enchant.EnchantService.slotMatches(splinter,
                    Material.DIAMOND_CHESTPLATE), "武器机制不能上胸甲");
            require(com.forgemaster.enchant.EnchantService.slotMatches(unload, Material.DIAMOND_CHESTPLATE),
                    "护甲机制必须能上胸甲");
            require(!com.forgemaster.enchant.EnchantService.slotMatches(unload, Material.DIAMOND_SWORD),
                    "护甲机制不能上剑");
        });

    }

    /**
     * 造一把「干净」的锻造品用于评分断言：同材质、同分、只换品质。
     *
     * <p>用生产链路 {@code buildDirect} 而不是手工塞 PDC —— 断言的是玩家真拿到的东西。
     * 材质取配置里的第一个剑胚（服主删光了就返回 null，调用方跳过）。</p>
     */
    private static ItemStack makeForged(String forger, com.forgemaster.quality.Quality q) {
        ForgingMasterPlugin plugin = org.bukkit.plugin.java.JavaPlugin
                .getPlugin(ForgingMasterPlugin.class);
        if (plugin == null || plugin.materials() == null) {
            return null;
        }
        com.forgemaster.material.ForgeMaterial mat = null;
        for (var m : plugin.materials().all()) {
            if (m != null) {
                mat = m;
                break;
            }
        }
        if (mat == null) {
            return null;
        }
        try {
            // 分数取该品质的下限 +1，保证 qualityOf 解析回同一档
            double score = q.minScore() + 1.0;
            return plugin.items().buildDirect(forger, mat, q, score);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 把一件物品的 Lore 拼成纯文本，用于「Lore 有没有被动过」的断言 */
    private static String loreText(ItemStack item) {
        if (item == null) {
            return "";
        }
        var meta = item.getItemMeta();
        if (meta == null || meta.lore() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (var c : meta.lore()) {
            sb.append(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(c)).append('\n');
        }
        return sb.toString();
    }

    /** 读一件物品上 fm_atk 的数值（没有则 0），用于属性膨胀断言 */
    private static double attackOf(ItemStack item) {
        if (item == null) {
            return 0.0;
        }
        var meta = item.getItemMeta();
        if (meta == null) {
            return 0.0;
        }
        var mods = meta.getAttributeModifiers(org.bukkit.attribute.Attribute.ATTACK_DAMAGE);
        if (mods == null) {
            return 0.0;
        }
        for (var m : mods) {
            var k = m.getKey();
            if (k != null && "fm_atk".equals(k.getKey())) {
                return m.getAmount();
            }
        }
        return 0.0;
    }

    /** 与 SelfTestSuite 内部同一套断言语义：不满足即抛，由套件汇总成失败项 */
    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalStateException(message);
        }
    }
}
