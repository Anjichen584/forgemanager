package com.forgemaster;

import com.forgemaster.affix.AffixRegistry;
import com.forgemaster.commission.CommissionManager;
import com.forgemaster.command.ForgingCommand;
import com.forgemaster.config.Settings;
import com.forgemaster.duel.DuelManager;
import com.forgemaster.econ.CoinRules;
import com.forgemaster.econ.StreakTracker;
import com.forgemaster.manuals.ManualStore;
import com.forgemaster.mentor.MentorManager;
import com.forgemaster.auction.AuctionManager;
import com.forgemaster.contests.ContestManager;
import com.forgemaster.forge.ForgeManager;
import com.forgemaster.item.ItemFactory;
import com.forgemaster.listener.CombatListener;
import com.forgemaster.listener.ForgeListener;
import com.forgemaster.material.MaterialRegistry;
import com.forgemaster.quench.QuenchRegistry;
import com.forgemaster.ranking.RankListener;
import com.forgemaster.ranking.RankMenu;
import com.forgemaster.season.SeasonManager;
import com.forgemaster.shop.ShopListener;
import com.forgemaster.shop.ShopManager;
import com.forgemaster.events.EventManager;
import com.forgemaster.stats.StatsStore;
import com.forgemaster.util.Keys;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 铸剑大师 —— 1.21.11 锻造小游戏。
 *
 * <p>五阶段玩法：加热（控温） → 锻打（节奏敲击） → 叠钢（序列连点）
 * → 开刃（控压保持） → 淬火（选介质定词条）。</p>
 */
public final class ForgingMasterPlugin extends JavaPlugin {

    private final Settings settings = new Settings();
    private final MaterialRegistry materials = new MaterialRegistry(this);
    private final AffixRegistry affixes = new AffixRegistry(this);
    private final QuenchRegistry quenches = new QuenchRegistry(this);

    private StatsStore stats;
    private ItemFactory items;
    private com.forgemaster.strengthen.StrengthenService strengthen;
    private com.forgemaster.evolve.EvolutionService evolutions;
    private com.forgemaster.contract.ContractRegistry contract;
    private com.forgemaster.contract.ContractManager contractManager;
    private com.forgemaster.form.FormRegistry forms;
    private com.forgemaster.bounty.BountyManager bounties;
    private com.forgemaster.proficiency.ProficiencyService proficiency;
    private com.forgemaster.affix.AffixSetService affixSets;
    private ForgeManager forge;
    private com.forgemaster.anvil.AnvilForgeManager anvilForge;
    private CommissionManager commissions;
    private DuelManager duels;
    private ManualStore manuals;
    private ContestManager contests;
    private AuctionManager auction;
    /** 锻造订单（玩家对玩家委托市场）：存 orders.yml，离线交付复用拍卖行邮箱 */
    private com.forgemaster.order.OrderService orders;
    private com.forgemaster.tomb.TombManager tombs;
    /** 剑冢秘境（tombrun）：残影挑战之上的分层副本，波次 / 门票 / 残页 config 驱动 */
    private com.forgemaster.tombrun.TombRunService tombrun;
    private com.forgemaster.spirit.SpiritManager spirit;
    private com.forgemaster.spiritdisplay.SpiritDisplayService spiritDisplay;
    /** 剑体放大（v2.20）：强化等级越高手里的剑越大；纯视觉 ItemDisplay，不改伤害 */
    private com.forgemaster.bigsword.SwordGrowthService swordGrowth;
    private com.forgemaster.codex.CodexService codex;
    private com.forgemaster.relicset.RelicSetService relicSets;
    private RankMenu rankMenu;
    private SeasonManager seasons;
    private com.forgemaster.growth.AchievementEvaluator achievements;
    private com.forgemaster.growth.CraftRankManager ranks;
    private CoinRules coinRules;
    private com.forgemaster.econ.EconomyBridge economy;
    /** 剑币消费口径收口（详见 {@link com.forgemaster.econ.Wallet}） */
    private com.forgemaster.econ.Wallet wallet;
    private com.forgemaster.compat.AntiCheatCompat compat;
    private com.forgemaster.gem.GemService gems;
    private StreakTracker streaks;
    private com.forgemaster.backup.BackupManager backups;
    private com.forgemaster.env.EnvManager env;
    private com.forgemaster.hall.HallOfFame hallOfFame;
    private com.forgemaster.hall.HallShrine hallShrine;
    private com.forgemaster.story.StoryManager story;
    private com.forgemaster.spectrum.SpectrumService spectrum;
    private com.forgemaster.pass.SeasonPassService seasonPass;

    /**
     * 数据看板（v2.20 切片 1 + v2.23 切片 2/3：周期快照、30 天趋势、基尼系数、CSV 导出）。
     *
     * <p>切片 2/3 的服务是 v2.23.1 接上线的：`MetricsService` 早就在包里躺着，
     * 但主类从未构造它 —— 于是 `metrics.history-days` / `metrics.snapshot-interval-minutes`
     * 这两个键改了没反应，趋势与 CSV 也永远不会跑。构造后由 {@link #onDisable()} 统一停。</p>
     */
    private com.forgemaster.metrics.MetricsService metrics;

    /**
     * 出站 Webhook 播报（v2.23 拓展 C）。
     *
     * <p>事件走有界队列 + 独立守护线程，绝不在主线程做网络 IO；未配置 url 时是个空壳，
     * 所有 {@code dispatch} 立刻返回。配置键见 config.yml 的 {@code webhook} 段，
     * 由 {@code ConfigMigrations.step22} 补给老盘。</p>
     */
    private com.forgemaster.webhook.WebhookService webhook;
    /** 存量物品收敛（v2.23.1）：上线扫描与 fixattrs 命令共用 */
    private com.forgemaster.compat.LegacyItemRepair legacyRepair;
    /** 原创附魔注册表 + 附魔台业务 + 纯函数效果计算（v2.23.2） */
    private com.forgemaster.enchant.EnchantRegistry enchants;
    private com.forgemaster.enchant.EnchantService enchantTable;
    private final com.forgemaster.enchant.EnchantEffects enchantEffects =
            new com.forgemaster.enchant.EnchantEffects();
    /** 黑市（每日轮换特价）与每日签到：共用一份 daily-data.yml 日期态 */
    private com.forgemaster.blackmarket.DailyStore dailyStore;
    /** 战力评分曲线（v2.26.0）：power log 的观测数据，非账本 */
    private com.forgemaster.score.PowerLogStore powerLogStore;
    private com.forgemaster.blackmarket.BlackmarketService blackmarket;
    private com.forgemaster.blackmarket.DailyRewardService dailyReward;
    private ShopManager shop;
    private EventManager events;
    private MentorManager mentors;
    private com.forgemaster.mentor.GraduationService graduations;
    private com.forgemaster.title.TitleService titles;
    private com.forgemaster.guild.GuildService guilds;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // 统一迁移层（M1）：强化/重铸费用、属性乘区、铁砧姿态/演出全部收编为有序 step，
        // 顶层 config-version 记版本；必须先于各服务构造改掉（尤其 2.17.3 的乘区重定）
        com.forgemaster.config.ConfigMigrations.run(this);
        Keys.init(this);

        settings.load(getConfig());
        applyProgressionConfig();
        materials.load(getConfig());
        com.forgemaster.material.ArmorCatalog.load(getConfig());
        affixes.load(getConfig());
        quenches.load(getConfig());

        stats = new StatsStore(this);
        stats.load();
        stats.startAutoSave(20 * 300);
        // 专精开关：关掉后所有等级加成归零
        StatsStore.progressionEnabled(settings.flag("settings.progression-enabled", true));
        seasons = new SeasonManager(this);
        achievements = new com.forgemaster.growth.AchievementEvaluator(this);
        ranks = new com.forgemaster.growth.CraftRankManager(this);
        seasons.start();
        coinRules = new CoinRules(this);
        economy = new com.forgemaster.econ.EconomyBridge(this);
        economy.init();
        wallet = new com.forgemaster.econ.Wallet(this);
        if (wallet.serverSinks() && economy.external()) {
            getLogger().info("宝石 / 黑市 / 签到 / 重铸 的消费按 economy.coin-sinks=server 走服务器经济（"
                    + economy.backend().label() + "），与剑币账户分开记账");
        }
        compat = new com.forgemaster.compat.AntiCheatCompat(this);
        compat.init();
        gems = new com.forgemaster.gem.GemService(this);
        streaks = new StreakTracker(this);
        backups = new com.forgemaster.backup.BackupManager(this);

        env = new com.forgemaster.env.EnvManager(this);
        items = new ItemFactory(this, settings, affixes, quenches);
        strengthen = new com.forgemaster.strengthen.StrengthenService(this);
        evolutions = new com.forgemaster.evolve.EvolutionService(this,
                new com.forgemaster.evolve.EvolutionRegistry(settings), strengthen);
        contract = new com.forgemaster.contract.ContractRegistry(this);
        forms = new com.forgemaster.form.FormRegistry(settings);
        contractManager = new com.forgemaster.contract.ContractManager(this,
                new com.forgemaster.contract.ContractStore(this),
                new com.forgemaster.contract.RelicPool(settings));
        bounties = new com.forgemaster.bounty.BountyManager(this);
        proficiency = new com.forgemaster.proficiency.ProficiencyService(this);
        affixSets = new com.forgemaster.affix.AffixSetService(this);
        forge = new ForgeManager(this);
        forge.start();

        // v2.16 ForgeTax 式铁砧强化：右键放剑 / 左键落锤 / 连击无损，全在方块上做完
        // （原 migrateDisplayConfig 调用已收编进 ConfigMigrations.run，启动最前面统一跑）
        anvilForge = new com.forgemaster.anvil.AnvilForgeManager(this);
        anvilForge.start();
        Bukkit.getPluginManager().registerEvents(
                new com.forgemaster.anvil.AnvilForgeListener(this, anvilForge), this);

        commissions = new CommissionManager(this);
        commissions.load();
        duels = new DuelManager(this);
        manuals = new ManualStore(this);
        manuals.load();
        contests = new ContestManager(this);
        tombs = new com.forgemaster.tomb.TombManager(this);
        tombs.load();
        // 审计 #8：tombs.yml 落盘改 dirty + 5 秒异步冲刷，主线程埋/取不再同步整文件重写
        tombs.startAutoFlush();
        spirit = new com.forgemaster.spirit.SpiritManager(this);
        // 剑灵显形（拓展第三档）：灵性第 3 档及以上的命名剑主手显形 ItemDisplay 浮游体，
        // 自带 40L 巡检 / 30s 孤儿清扫 / 退服换世界死亡即时撤除，构造即挂监听并起巡检
        spiritDisplay = new com.forgemaster.spiritdisplay.SpiritDisplayService(this);
        // 剑体成长曲线（v2.22.1 起只管曲线）：砧上剑身按 strengthen.big-sword.* 的
        // 里程碑曲线逐锤变大；手上悬浮剑与护甲体型放大已移除，服务只负责
        // 持有曲线 + 清扫旧版残留实体 / 修饰符。
        swordGrowth = new com.forgemaster.bigsword.SwordGrowthService(this);
        swordGrowth.start();
        contests.start();

        // 收藏图鉴（拓展第一档）：进度存 codex-data.yml，点击路由随服务一起注册
        codex = new com.forgemaster.codex.CodexService(this);
        Bukkit.getPluginManager().registerEvents(new com.forgemaster.codex.CodexListener(this), this);

        // 套装效果 2.0（拓展第二档）：按材料件数判定的独立套装，配置驱动，
        // 与词条套装（affix-set）共存——键名前缀 fm_set_armor_ / fm_set_cd 与 fm_set_ 区分
        relicSets = new com.forgemaster.relicset.RelicSetService(this);
        Bukkit.getPluginManager().registerEvents(
                new com.forgemaster.relicset.RelicSetListener(this, relicSets), this);

        auction = new AuctionManager(this);
        auction.start();
        // 锻造订单（拓展第三档 · 玩家委托市场）：照 codex 风格注册——
        // 存档 load 在此，onDisable save + OrderGuiRouter.reset()，reloadAll 里重新 load；
        // 托管剑的离线交付复用 auction 的邮箱，所以必须在 auction.start() 之后构造
        orders = new com.forgemaster.order.OrderService(this);
        orders.load();
        story = new com.forgemaster.story.StoryManager(this);
        story.load();
        story.start();
        // 名剑谱（拓展第二档）：条目 config 驱动，领取记录存 spectrum-data.yml
        spectrum = new com.forgemaster.spectrum.SpectrumService(this);
        spectrum.load();
        // 赛季通行证（拓展第二档 · 免费轨道）：轨道 config 驱动，进度存 pass-data.yml
        seasonPass = new com.forgemaster.pass.SeasonPassService(this);
        seasonPass.load();
        // 数据看板（v2.23 切片 2/3）：周期快照 + 30 天趋势 + 基尼系数 + CSV 导出。
        // 未启用时 start() 内部直接返回，不留任务。
        metrics = new com.forgemaster.metrics.MetricsService(this);
        metrics.start();
        // 出站 Webhook（v2.23 拓展 C）：未配置 url 时是空壳，dispatch 立刻返回
        webhook = new com.forgemaster.webhook.WebhookService(this);
        legacyRepair = new com.forgemaster.compat.LegacyItemRepair(this);
        Bukkit.getPluginManager().registerEvents(new com.forgemaster.compat.LegacyRepairListener(this, legacyRepair), this);
        // 原创附魔（v2.23.3）：注册表必须在 config 迁移之后建，否则读到的还是老配置
        enchants = new com.forgemaster.enchant.EnchantRegistry(this);
        enchantTable = new com.forgemaster.enchant.EnchantService(this);
        com.forgemaster.enchant.EnchantListener enchantListener =
                new com.forgemaster.enchant.EnchantListener(this);
        Bukkit.getPluginManager().registerEvents(enchantListener, this);
        Bukkit.getPluginManager().registerEvents(new com.forgemaster.enchant.EnchantGuardListener(this), this);
        // 守夜（脱战回复）与望气（侦测）靠周期任务；40 tick = 2 秒一次。
        // 放这里而不是每个玩家一条链：玩家上下线不用管任务生命周期，也就不会有「退了还在跑」的链。
        Bukkit.getScheduler().runTaskTimer(this, enchantListener::tick, 100L, 40L);
        webhook.start();
        // 剑冢秘境（tombrun）：依赖 tombs（残影档案复用）与 seasonPass（通关登记），构造即挂监听与心跳
        tombrun = new com.forgemaster.tombrun.TombRunService(this, tombs);
        // 黑市（每日轮换特价）+ 每日签到（轻量留存拓展）：同绕现实日转，共用 daily-data.yml；
        // 黑市 GUI 的点击路由照 pass 风格懒注册（第一次 open 时挂），卸载时 BlackmarketRouter.reset()
        dailyStore = new com.forgemaster.blackmarket.DailyStore(this);
        dailyStore.load();
        blackmarket = new com.forgemaster.blackmarket.BlackmarketService(this, dailyStore);
        dailyReward = new com.forgemaster.blackmarket.DailyRewardService(this, dailyStore);
        dailyReward.load();
        // 战力评分曲线（v2.26.0）：power log 的观测数据存储，卸载/reload 时随 dailyStore 一起落盘
        powerLogStore = new com.forgemaster.score.PowerLogStore(this);
        powerLogStore.load();
        hallOfFame = new com.forgemaster.hall.HallOfFame(this);
        hallOfFame.start();
        // 名人殿堂（拓展第一档）：ItemDisplay 平躺展示前三名，30s 定时重刷
        hallShrine = new com.forgemaster.hall.HallShrine(this);
        hallShrine.start();
        // PlaceholderAPI 桥（拓展第一档）：反射挂载，没装 PAPI / 配置关闭时静默跳过
        com.forgemaster.placeholder.ForgePlaceholders.register(this);
        mentors = new MentorManager(this);
        // 出师（v2.19）：/zhujian master graduate 两步确认出师，数据并入师徒字段族（graduated/graduates）
        graduations = new com.forgemaster.mentor.GraduationService(this);
        // 称号扩展（v2.19）：config titles 段驱动解锁，佩戴标记存玩家 PDC，佩戴 > 声望称号
        titles = new com.forgemaster.title.TitleService(this);
        titles.load();
        // 行会商会（拓展第三档 · 社交层）：数据存 guild-data.yml，周赛巡检/自动落盘随 start 挂起
        guilds = new com.forgemaster.guild.GuildService(this);
        guilds.load();
        guilds.start();

        shop = new ShopManager(this);
        events = new EventManager(this);
        events.start();
        if (contractManager != null) {
            contractManager.start();
        }
        Bukkit.getPluginManager().registerEvents(new ShopListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ForgeListener(this), this);
        Bukkit.getPluginManager().registerEvents(new CombatListener(this), this);
        Bukkit.getPluginManager().registerEvents(
                new com.forgemaster.proficiency.ProficiencyListener(this, proficiency), this);
        Bukkit.getPluginManager().registerEvents(
                new com.forgemaster.affix.AffixSetListener(this, affixSets), this);
        // 注意：剑冢残影引擎（TombChallenge）在自己的构造里已经挂了 EntityDeathEvent，
        // 这里绝对不能再 registerEvents 一次，否则残影死亡会结算两遍、奖励翻倍。
        // 词条重铸界面的点击路由要提前挂上，否则主菜单那个按钮点不动
        com.forgemaster.gui.ReforgeMenu.init(this);
        Bukkit.getPluginManager().registerEvents(new com.forgemaster.listener.VanillaGuardListener(this), this);
        rankMenu = new RankMenu(this);
        Bukkit.getPluginManager().registerEvents(new RankListener(this), this);
        // 2.10 护甲校准：老装备穿上时按新量级补一次护甲，并修掉四件共用
        // 一条 fm_armor 导致「穿两件脱一件护甲清空」的老问题
        Bukkit.getPluginManager().registerEvents(
                new com.forgemaster.listener.ArmorRebalanceListener(this), this);

        PluginCommand cmd = getCommand("zhujian");
        if (cmd != null) {
            ForgingCommand executor = new ForgingCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        getLogger().info("铸剑大师已点火："
                + materials.all().size() + " 种剑胚 / "
                + affixes.all().size() + " 条词条 / "
                + quenches.size() + " 种淬火介质。");

        warnMissingKeys();

        // 外部经济（CMI / EssentialsX）通常比本插件晚注册 Vault 服务，
        // 启动时探不到就再等一会儿重试两次。
        Bukkit.getScheduler().runTaskLater(this, this::retryEconomy, 60L);
        Bukkit.getScheduler().runTaskLater(this, this::retryEconomy, 200L);

        // 定期清掉过期委托，否则委托板会一直挂着失效条目
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (commissions != null) {
                commissions.purgeExpired();
            }
            // 锻造订单：结算过期单（退悬赏 / 退剑）并清已结案单
            if (orders != null) {
                orders.maintain();
            }
        }, 20L * 60, 20L * 300);

        // 积分类存档合并落盘（v2.19.1）：通行证积分每锻一把都会涨，
        // 之前是每次涨幅都整档重写 pass-data.yml，改成每 10 秒合并一次。
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (seasonPass != null) {
                seasonPass.flush();
            }
        }, 20L * 10, 20L * 10);
    }

    /**
     * 把 {@code progression.*} / {@code talent.*} 灌进静态配置。
     *
     * <p>经验表、天赋点数这些在 tick、排行榜、面板里都是静态调用，不适合每次都查 config；
     * 启动与 reload 各灌一次即可。</p>
     */
    private void applyProgressionConfig() {
        StatsStore.configureExp(
                settings.intOf("progression.curve-base", 120),
                settings.intOf("progression.curve-per-level", 55),
                settings.num("progression.gain-base", 6),
                settings.num("progression.gain-per-quality", 5),
                settings.num("progression.gain-per-score", 0.05),
                settings.intOf("progression.max-level", 60));
        com.forgemaster.growth.Talents.configure(
                settings.intOf("talent.points-per-level", 5));
    }

    private void retryEconomy() {
        if (economy != null && economy.retry()) {
            getLogger().info("已接入外部经济：" + economy.backend().label());
        }
        // 反作弊插件也常常后加载，补探一次
        if (compat != null && !compat.detected()) {
            compat.init();
        }
    }

    /**
     * 必需配置键清单（v2.19.2 从 {@link #warnMissingKeys()} 里抽出来）。
     *
     * <p>三处共用这一份，避免各写一套然后越漂越远：</p>
     * <ul>
     *   <li>启动时的缺键告警（{@link #warnMissingKeys()}）</li>
     *   <li>{@code /zhujian config check} 给服主看盘上到底缺什么</li>
     *   <li>自检：内置 config.yml 必须含全部键，否则构建产物本身就是漂的</li>
     * </ul>
     */
    public static final String[] REQUIRED_CONFIG_KEYS = {
            "settings.anvil-animation",
            "settings.anvil-search-radius",
            "settings.spawn-temp-anvil",
            "settings.folding-enabled",
            "settings.grinding-enabled",
            "settings.progression-enabled",
            "settings.durability-enabled",
            "settings.duel-enabled",
            "settings.manual-max-entries",
            "settings.weapon-names",
            "settings.grind-duration-ticks",
            "settings.grind-press-step",
            "commissions.enabled",
            "commissions.clients",
            "contest.enabled",
            "contest.interval-minutes",
            "contest.duration-seconds",
            // v2.26.0：历届赛果档案的保留届数。这键必须进必需键清单 —— 之前只存在于
            // ContestHistoryStore 的 Java 默认值里，服主在 config.yml 里既看不到也改不了。
            "contest.history-keep",
            "rank.enabled",
            "season.enabled",
            "season.duration-days",
            "coins.forge-base",
            "coins.forge-per-quality",
            "coins.forge-per-score",
            "coins.contest-first",
            "coins.contest-second",
            "coins.contest-third",
            "coins.contest-other",
            "coins.commission-base",
            "coins.commission-per-rep",
            "mentor.enabled",
            "mentor.max-disciples",
            "mentor.invite-expire-seconds",
            "mentor.unbind-cooldown-hours",
            "mentor.coin-share-rate",
            "mentor.apprentice-tolerance-bonus",
            "environment.enabled",
            "environment.announce-on-start",
            "environment.weather",
            "environment.time",
            "shop.enabled",
            "shop.batch-size",
            "shop.materials",
            "shop.blueprints",
            "blueprint.enabled",
            "blueprint.required",
            "blueprint.tolerance-bonus",
            "auction.enabled",
            "auction.tax-rate",
            "auction.min-increment",
            "risk.enabled",
            "risk.score-bonus-percent",
            "risk.explode-chance",
            "events.enabled",
            "events.interval-minutes",
            "events.types",
            "spirit.enabled",
            "spirit.max",
            "spirit.tiers",
            "spirit.tier-names",
            "tomb.enabled",
            "tomb.challenge-fee",
            "tomb.recipe.weight-material",
            "tomb.recipe.weight-affix",
            "hall-of-fame.enabled",
            "hall-of-fame.refresh-seconds",
            "achievements.enabled",
            "achievements.list",
            "craft-rank.enabled",
            "craft-rank.tiers",
            "economy.provider",
            "difficulty.enabled",
            "difficulty.score-penalty-per-tier",
            "armor.enabled",
            "armor.price-multiplier",
            "gems.enabled",
            "gems.socket.cost-coins",
            "gems.smelt.star-amount",
            "gems.smelt.star-cost-coins",
            "gems.inlay.cost-coins",
            "gems.extract.cost-coins",
            "gems.merge.cost-coins",
            "compat.anticheat-aware",
            "compat.speed-cap",
            "compat.knockback-cap",
            "affix-scaling.enabled",
            "affix-scaling.tiers.1.value",
            "affix-scaling.tiers.5.spread",
            "strengthen.enabled",
            "anvil-forge.enabled",
            "anvil-forge.max-level",
            "anvil-forge.owner-only",
            "anvil-forge.strike-cooldown-ms",
            "anvil-forge.hammer.item",
            "anvil-forge.hammer.allow-vanilla",
            "anvil-forge.success.start",
            "anvil-forge.success.floor",
            "anvil-forge.break.base",
            "anvil-forge.break.max",
            "anvil-forge.combo.no-loss-threshold",
            "anvil-forge.combo.success-bonus-per",
            "anvil-forge.combo.success-bonus-max",
            "anvil-forge.combo.break-shield",
            "anvil-forge.cost.base",
            "anvil-forge.cost.per-level",
            "anvil-forge.cost.tier-factor",
            "anvil-forge.material.base",
            "anvil-forge.material.tier-factor",
            "anvil-forge.display.height",
            "anvil-forge.display.sword-height",
            "anvil-forge.display.sword-yaw",
            "anvil-forge.display.sword-pitch",
            "anvil-forge.display.sword-scale",
            "anvil-forge.session-timeout-minutes",
            "strengthen.bonus-per-level",
            "strengthen.max-bonus",
            "strengthen.caps.1",
            "strengthen.caps.5",
            // v2.17.3：strengthen.cost.* / strengthen.success.* / strengthen.fail.break-max
            // 从必需清单移除——anvil-forge.enabled=true 时它们是死键（走 anvil-forge 段），
            // 见 audit/04-quality.md C1/C2/C6
            "armor.tiers.iron.armor",
            "armor.tiers.starfall.toughness",
            "gems.sockets.tiers.1",
            "gems.sockets.tiers.5",
            "gems.enchant.cost-coins",
            // 死键移除：strengthen.success.base / strengthen.fail.break-max
            // （现行曲线读 anvil-forge.success.* / anvil-forge.break.*）
            "strengthen.material.base",
            "strengthen.talisman.item",
            "strengthen.furnace.need-high",
            "strengthen.kindle.bonus",
            "gems.enchant-extract.cost-coins",
            "gems.enchant-extract.success",
            "shop.specials.talisman",
            "duel.strengthen-tier.enabled",
            "duel.strengthen-tier.bounds",
            "reforge.cost.base",
            "reforge.material.base",
            "hall-of-fame.strengthen.min-level",
            "rank.strengthen.snapshot-seconds",
            "strengthen.season-catchup.enabled",
            "strengthen.season-catchup.max-bonus",
            "strengthen.season-catchup.level-threshold",
            "strengthen.season-catchup.per-day",
            "proficiency.enabled",
            "proficiency.tiers",
            "proficiency.tier-names",
            "proficiency.bonuses",
            "affix-set.enabled",
            "affix-set.sets",
            "contest.watch.rows",
            "contest.watch.refresh-ticks",
            "season.legacy.enabled",
            "season.legacy.show-count",
            "season.legacy.weight.quality",
            "vanilla-guard.enabled",
            "vanilla-guard.enchanting-table",
            "vanilla-guard.anvil",
            "vanilla-guard.grindstone",
            "imprint.enabled",
            "imprint.max-affixes",
            "imprint.strip.cost-base",
            "imprint.strip.tier-factor",
            "imprint.graft.cost-base",
            "imprint.graft.tier-factor",
            "progression.curve-base",
            "progression.curve-per-level",
            "progression.gain-base",
            "progression.gain-per-quality",
            "progression.gain-per-score",
            "progression.max-level",
            "talent.points-per-level",
            "talent.reset-cost",
            "spirit.pvp",
            "reforge.permission",
            "reforge.confirm-ticks",
            "reforge.armor-pool",
            "reforge.cost.quality.rough",
            "reforge.cost.quality.common",
            "reforge.cost.quality.fine",
            "reforge.cost.quality.epic",
            "reforge.cost.quality.legendary",
            "reforge.cost.quality.mythic",
            "settings.stage-intro-ticks",
            "strengthen.fail.tomb-record",
            "economy.coin-sinks",
            // v2.20 看板：报表读配置时都有默认值，缺了不影响运行，
            // 但服主看不到旋钮，所以列进必需清单防漂移
            "metrics.enabled",
            "metrics.top-n",
            "metrics.gini-warn",
            // v2.23.1：切片 2/3 的 MetricsService 接上生命周期后这两个键才有用
            "metrics.history-days",
            "metrics.snapshot-interval-minutes",
            // v2.23 拓展 C：出站 Webhook（默认关闭）
            "webhook.enabled",
            "webhook.url",
            "webhook.username",
            "webhook.timeout-seconds",
            "webhook.queue-size",
            "webhook.events",
            // v2.23 切片 5：战力评分（缺键时走代码默认，服主改不了）
            "power-score.enabled",
            "power-score.base-multiplier",
            "power-score.strengthen-per-level",
            "power-score.evolve-per-stage",
            "power-score.suggest-price",
            "power-score.price-per-power",
            // v2.23.1：属性膨胀天花板（缺键时走代码默认 1.0，服主改不了）
            "strengthen.base-tolerance",
            // v2.23.1：伤害预算（暴击上限 / 单次伤害 / 吸血三道夹子）
            "combat.crit.max-chance", "combat.crit.max-damage", "combat.crit.base-multiplier",
            "combat.limits.max-attack-attribute", "combat.limits.max-hit-damage",
            "combat.limits.max-lifesteal", "combat.limits.max-lifesteal-per-hit",
            "combat.limits.max-lifesteal-max-health", "combat.limits.max-damage-reduce",
            "combat.limits.max-bonus-health", "combat.limits.max-fire-seconds",
            // v2.23.1：存量物品收敛（上线自动扫）
            "legacy-repair.on-join", "legacy-repair.on-join-delay-ticks", "legacy-repair.notify-player",
            // v2.23.2：原创附魔台（只校验骨架键；defs.* 缺了会回落到代码里的默认定义）
            "enchant.cost.base-coin", "enchant.cost.coin-per-level", "enchant.cost.ore-per-level",
            "enchant.max-per-item", "enchant.guard.enabled", "enchant.caps.max-splash-ratio",
            // v2.20 剑体成长曲线：砧上剑身大小读这几个键，缺了走默认值不影响运行
            // （v2.22.1：手上悬浮剑 / 护甲体型放大已移除，offset-* 与 player-scale 键下线）
            "strengthen.big-sword.enabled",
            "strengthen.big-sword.min-level",
            "strengthen.big-sword.multiplier",
            "combat.crit.max-chance", "combat.crit.max-damage", "combat.crit.base-multiplier",
    };

    /** 老版本的 config.yml 不会自动补新键（避免冲掉用户注释），这里只提醒一句 */
    private void warnMissingKeys() {
        // 必须读盘上的原始文件：getConfig() 可能带着运行时默认值，
        // 那样 contains() 恒为 true，这段告警就成了摆设
        org.bukkit.configuration.file.YamlConfiguration raw =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                        new java.io.File(getDataFolder(), "config.yml"));
        String[] expected = REQUIRED_CONFIG_KEYS;
        StringBuilder missing = new StringBuilder();
        for (String key : expected) {
            if (!raw.contains(key)) {
                if (missing.length() > 0) {
                    missing.append(", ");
                }
                missing.append(key);
            }
        }
        if (missing.length() > 0) {
            getLogger().warning("config.yml 缺少新配置项（已使用内置默认值，不影响运行）：" + missing);
            getLogger().warning("想要完整的注释版配置，可以备份后删除 plugins/ForgingMaster/config.yml 再重启。");
        }
    }

    @Override
    public void onDisable() {
        if (anvilForge != null) {
            anvilForge.stop();
        }
        if (forge != null) {
            forge.shutdown();
        }
        // 观战面板的刷新任务必须显式停，否则关服后仍有计时器在跑
        try {
            com.forgemaster.contests.ContestWatchGui.shutdown();
        } catch (RuntimeException ex) {
            getLogger().warning("停止观战面板失败: " + ex.getMessage());
        }
        // 路由的「已注册」标记是 static，重载后不会自动复位，这里手动清掉
        try {
            com.forgemaster.contests.ContestGuiRouter.reset();
        // ReforgeMenu 的静态 listenerReady 也要清：不清的话重载后重铸界面点不动（v2.23.1）
        com.forgemaster.gui.ReforgeMenu.reset();
        // 附魔台路由同理（v2.23.2）：不清的话重载后附魔台完全没反应
        com.forgemaster.enchant.EnchantRouter.reset();
        } catch (RuntimeException ex) {
            getLogger().warning("复位大赛界面路由失败: " + ex.getMessage());
        }
        // 剑冢残影：清掉还活着的残影与回收任务，别把孤儿实体留在存档里
        try {
            if (tombs != null && tombs.challenges() != null) {
                tombs.challenges().shutdown();
            }
        } catch (RuntimeException ex) {
            getLogger().warning("清理剑冢残影失败: " + ex.getMessage());
        }
        // 剑冢秘境：撤守卫（setPersistent(false)，本就不进存档）与心跳，兜底按标记全服清一遍
        try {
            if (tombrun != null) {
                tombrun.shutdown();
            }
        } catch (RuntimeException ex) {
            getLogger().warning("清理剑冢秘境守卫失败: " + ex.getMessage());
        }
        if (stats != null) {
            stats.save();
        }
        if (commissions != null) {
            commissions.save();
        }
        if (manuals != null) {
            manuals.save();
        }
        if (tombs != null) {
            tombs.stop(); // 关服强制冲刷（内部停掉异步任务并 save 一次）
        }
        if (contests != null) {
            contests.shutdown();
        }
        if (auction != null) {
            auction.shutdown();
        }
        if (orders != null) {
            orders.save();
        }
        try {
            com.forgemaster.order.OrderGuiRouter.reset();
        } catch (RuntimeException ex) {
            getLogger().warning("复位订单面板路由失败: " + ex.getMessage());
        }
        if (events != null) {
            events.shutdown();
        }
        if (seasons != null) {
            seasons.shutdown();
        }
        if (hallOfFame != null) {
            hallOfFame.stop();
        }
        if (hallShrine != null) {
            hallShrine.stop();
        }
        if (spiritDisplay != null) {
            // 剑灵显形：撤掉全部浮游体并差量扣回暴击加成（stop 幂等，重复调用安全）
            spiritDisplay.stop();
        }
        if (swordGrowth != null) {
            // 剑体放大：撤掉所有大剑实体并停刷新任务。不撤的话关服会把孤儿留进存档
            swordGrowth.stop();
        }
        if (story != null) {
            story.stop();
        }
        if (spectrum != null) {
            spectrum.save();
        }
        try {
            com.forgemaster.spectrum.SpectrumGuiRouter.reset();
        } catch (RuntimeException ex) {
            getLogger().warning("复位名剑谱界面路由失败: " + ex.getMessage());
        }
        try {
            com.forgemaster.title.TitleGuiRouter.reset();
        } catch (RuntimeException ex) {
            getLogger().warning("复位称号界面路由失败: " + ex.getMessage());
        }
        if (seasonPass != null) {
            seasonPass.save();
        }
        if (metrics != null) {
            // 看板：停采样任务 + 把当前这一天的最新一次采样落盘，别丢最后一段
            metrics.stop();
        }
        // Webhook：置停止标志 + 打断消费线程 + join(1s)，避免关服时挂住线程池
        if (webhook != null) { webhook.stop(); }
        try {
            com.forgemaster.pass.PassGuiRouter.reset();
        } catch (RuntimeException ex) {
            getLogger().warning("复位赛季通行证界面路由失败: " + ex.getMessage());
        }
        try {
            // v2.23 切片 3：看板 GUI 路由的 static ready 标记也要复位，
            // 否则 /reload 后 ensure() 直接返回、界面失去点击拦截（ReforgeMenu 栽过同一个坑）
            com.forgemaster.metrics.MetricsGuiRouter.reset();
        } catch (RuntimeException ex) {
            getLogger().warning("复位数据看板界面路由失败: " + ex.getMessage());
        }
        if (dailyStore != null) {
            dailyStore.save();
        }
        if (powerLogStore != null) {
            powerLogStore.save();
        }
        try {
            com.forgemaster.blackmarket.BlackmarketRouter.reset();
        } catch (RuntimeException ex) {
            getLogger().warning("复位黑市界面路由失败: " + ex.getMessage());
        }
        if (guilds != null) {
            guilds.shutdown();
        }
        try {
            com.forgemaster.guild.GuildGuiRouter.reset();
        } catch (RuntimeException ex) {
            getLogger().warning("复位行会界面路由失败: " + ex.getMessage());
        }
        if (contractManager != null) {
            contractManager.stop();
        }
        if (relicSets != null) {
            // 套装效果 2.0：停用时把所有在线玩家的套装修正还原（transient 不落盘的兜底）
            relicSets.stripAll();
        }
        getLogger().info("铸剑大师已封炉。");
    }

    public void reloadAll() {
        reloadConfig();
        settings.load(getConfig());
        applyProgressionConfig();
        materials.load(getConfig());
        com.forgemaster.material.ArmorCatalog.load(getConfig());
        affixes.load(getConfig());
        quenches.load(getConfig());
        if (evolutions != null) {
            evolutions.registry().load();
        }
        if (contract != null) {
            contract.reload();
        }
        if (forms != null) {
            forms.load(settings);
        }
        if (contractManager != null) {
            contractManager.relics().load(settings);
            contractManager.start();
        }
        if (economy != null) {
            economy.init();
        }
        if (forge != null) {
            forge.start();
        }
        if (anvilForge != null) {
            anvilForge.stop();
            anvilForge.start();
        }
        if (hallOfFame != null) {
            hallOfFame.stop();
            hallOfFame.start();
        }
        if (hallShrine != null) {
            hallShrine.stop();
            hallShrine.start();
        }
        if (story != null) {
            story.stop();
            story.load();
            story.start();
        }
        if (spectrum != null) {
            spectrum.save();
            spectrum.load();
        }
        if (titles != null) {
            titles.load();
        }
        if (seasonPass != null) {
            seasonPass.save();
            seasonPass.load();
        }
        // v2.23.1 修 P1（persist 审计）：这四处「周期」是构造/start 时读一次 config 的，
        // 只 reload 不重启调度器 = 服主改了数值毫无反应，直到重启。
        // 统一走 stop → start，与 anvilForge/hallOfFame 同一套幂等约定。
        if (contests != null) {
            contests.shutdown();
            contests.start();
        }
        if (events != null) {
            events.shutdown();
            events.start();
        }
        if (guilds != null) {
            // shutdown = stopTasks + save（先把脏数据落盘），再 load 重读磁盘，最后 start 重排
            // weekly / autosave / 5s 冲刷 / 委托轮询四条任务 —— 这样四个周期参数才会真正生效
            guilds.shutdown();
            guilds.load();
            guilds.start();
        }
        if (metrics != null) {
            // 看板：采样间隔 / 保留天数 / 基尼阈值都可能改了，reload 内部会重排采样任务
            metrics.reload();
        }
        if (webhook != null) { webhook.reload(); }
        if (dailyStore != null) {
            dailyStore.save();
        }
        if (powerLogStore != null) {
            powerLogStore.save();
        }
        if (dailyReward != null) {
            dailyReward.load();
        }
        if (relicSets != null) {
            // 套装效果 2.0：配置可能变了，给在线玩家按差量重挂 / 还原
            relicSets.reload();
        }
        if (orders != null) {
            // 订单参数全部 config 驱动即时生效；这里重读 orders.yml 对齐磁盘
            orders.load();
        }
    }

    public com.forgemaster.hall.HallOfFame hallOfFame() {
        return hallOfFame;
    }

    /** 名人殿堂（拓展第一档）：ItemDisplay 平躺展示前三名 */
    public com.forgemaster.hall.HallShrine hallShrine() {
        return hallShrine;
    }

    public com.forgemaster.story.StoryManager story() {
        return story;
    }

    /** 名剑谱（拓展第二档）：条目 config 驱动，领取记录存 spectrum-data.yml */
    public com.forgemaster.spectrum.SpectrumService spectrum() {
        return spectrum;
    }

    /** 赛季通行证（拓展第二档 · 免费轨道）：轨道 config 驱动，进度存 pass-data.yml */
    public com.forgemaster.pass.SeasonPassService seasonPass() {
        return seasonPass;
    }

    /** 数据看板（v2.23 切片 2/3）：周期快照 / 30 天趋势 / 基尼系数 / CSV 导出 */
    public com.forgemaster.metrics.MetricsService metrics() {
        return metrics;
    }

    /** 出站 Webhook 播报（v2.23 拓展 C）：各模块通过它 dispatch，未启用时全是空操作 */
    public com.forgemaster.webhook.WebhookService webhook() {
        return webhook;
    }

    /** 存量物品收敛（v2.23.1）：命令 / 上线 / 容器扫描三条路共用这一份逻辑 */
    public com.forgemaster.compat.LegacyItemRepair legacyRepair() {
        return legacyRepair;
    }

    /** 原创附魔注册表（v2.23.2）：解析 / 随机 / 部位过滤都走它 */
    public com.forgemaster.enchant.EnchantRegistry enchants() {
        return enchants;
    }

    /** 附魔台业务：plan（预览）/ apply（点按钮）/ applyBook（右键上书） */
    public com.forgemaster.enchant.EnchantService enchantTable() {
        return enchantTable;
    }

    /** 原创附魔效果计算（纯函数，无状态，可安全共享一份） */
    public com.forgemaster.enchant.EnchantEffects enchantEffects() {
        return enchantEffects;
    }

    /**
     * 一行播报：各模块不必自己判 null / 判开关 / 包 try。
     *
     * <p>这是给调用点用的唯一入口——所有 {@code webhook.dispatch} 都该走它，理由有两条：</p>
     * <ol>
     *   <li><b>播报永远不能影响主流程</b>：配置写坏、队列满、线程被打断，任何一种都不许
     *       把异常抛回锻造/强化/蜕变的业务路径。这里统一 try 掉。</li>
     *   <li><b>调用点保持一行</b>：散落的 {@code if (webhook != null && webhook.enabled())}
     *       既啰嗦又容易在某个分支里忘判而 NPE。</li>
     * </ol>
     *
     * @param type   事件类型
     * @param text   正文（纯文本即可，内部按 MiniMessage 解析？不——按字面量发送，避免播报端
     *               把玩家名里的 {@code <} 当标签）
     * @param fields 附加字段（可 null）
     */
    public void broadcast(com.forgemaster.webhook.WebhookEvent type,
                          String text, java.util.Map<String, String> fields) {
        try {
            if (webhook == null) {
                return;
            }
            webhook.dispatch(type, net.kyori.adventure.text.Component.text(
                    text == null ? "" : text), fields);
        } catch (RuntimeException ex) {
            getLogger().fine("Webhook 播报失败（已忽略）：" + ex);
        }
    }

    /** 黑市（每日轮换特价，轻量留存拓展）：货单 config 驱动，限购存 daily-data.yml */
    public com.forgemaster.blackmarket.BlackmarketService blackmarket() {
        return blackmarket;
    }

    /** 每日签到（轻量留存拓展）：奖励表 config 驱动，进度存 daily-data.yml */
    public com.forgemaster.blackmarket.DailyRewardService dailyReward() {
        return dailyReward;
    }

    public Settings settings() {
        return settings;
    }

    public MaterialRegistry materials() {
        return materials;
    }

    public AffixRegistry affixes() {
        return affixes;
    }

    public QuenchRegistry quenches() {
        return quenches;
    }

    public StatsStore stats() {
        return stats;
    }

    public CoinRules coinRules() {
        return coinRules;
    }

    public com.forgemaster.econ.EconomyBridge economy() {
        return economy;
    }

    /** 剑币消费口径（宝石 / 黑市 / 签到 / 重铸 共用一本账，见 {@link com.forgemaster.econ.Wallet}） */
    public com.forgemaster.econ.Wallet wallet() {
        return wallet;
    }

    public com.forgemaster.compat.AntiCheatCompat compat() {
        return compat;
    }

    public com.forgemaster.gem.GemService gems() {
        return gems;
    }

    public StreakTracker streaks() {
        return streaks;
    }

    public com.forgemaster.backup.BackupManager backups() {
        return backups;
    }

    public ItemFactory items() {
        return items;
    }

    public com.forgemaster.strengthen.StrengthenService strengthen() {
        return strengthen;
    }

    /** 锻阶（蜕变），v2.12：挂在强化等级带上的 ForgeTax 式质变层 */
    public com.forgemaster.evolve.EvolutionService evolutions() {
        return evolutions;
    }

    /** 龙税契约接口（暂定，未接入玩法）：预留 ForgeTax 循环的扩展点 */
    public com.forgemaster.contract.ContractRegistry contract() {
        return contract;
    }

    /** 龙税周目（v2.14）：时钟 / 入贡 / 结算 / 圣物 */
    /** v2.25：每日签到存档访问器（回档 flush/reload 用，此前无出口） */
    public com.forgemaster.blackmarket.DailyStore dailyStore() {
        return dailyStore;
    }

    /** v2.26.0：战力评分曲线访问器（power log） */
    public com.forgemaster.score.PowerLogStore powerLog() {
        return powerLogStore;
    }

    public com.forgemaster.contract.ContractManager contractManager() {
        return contractManager;
    }

    /** 剑型注册表（v2.14）：估值乘区与外观 */
    public com.forgemaster.form.FormRegistry forms() {
        return forms;
    }

    /** 每日悬赏（v2.14） */
    public com.forgemaster.bounty.BountyManager bounties() {
        return bounties;
    }

    /** 剑意（武器熟练度），v2.6 */
    public com.forgemaster.proficiency.ProficiencyService proficiency() {
        return proficiency;
    }

    /** 词条套装，v2.7 */
    public com.forgemaster.affix.AffixSetService affixSets() {
        return affixSets;
    }

    public ForgeManager forge() {
        return forge;
    }

    /** 铁砧强化台（v2.16）：砧位状态、全息、落锤入口都在这里 */
    public com.forgemaster.anvil.AnvilForgeManager anvilForge() {
        return anvilForge;
    }

    public CommissionManager commissions() {
        return commissions;
    }

    public DuelManager duels() {
        return duels;
    }

    public ManualStore manuals() {
        return manuals;
    }

    public AuctionManager auction() {
        return auction;
    }

    /** 锻造订单（玩家对玩家委托市场，/zhujian order，别名 dingdan） */
    public com.forgemaster.order.OrderService orders() {
        return orders;
    }

    public ContestManager contests() {
        return contests;
    }

    public com.forgemaster.tomb.TombManager tombs() {
        return tombs;
    }

    /** 剑冢秘境服务（/zhujian tombrun，别名 mijing） */
    public com.forgemaster.tombrun.TombRunService tombrun() {
        return tombrun;
    }

    public com.forgemaster.spirit.SpiritManager spirit() {
        return spirit;
    }

    public com.forgemaster.spiritdisplay.SpiritDisplayService spiritDisplay() {
        return spiritDisplay;
    }

    public com.forgemaster.bigsword.SwordGrowthService swordGrowth() {
        return swordGrowth;
    }

    public com.forgemaster.codex.CodexService codex() {
        return codex;
    }

    /** 套装效果 2.0（拓展第二档）：按材料件数判定的独立套装服务 */
    public com.forgemaster.relicset.RelicSetService relicSets() {
        return relicSets;
    }

    public com.forgemaster.env.EnvManager env() {
        return env;
    }

    public RankMenu rankMenu() {
        return rankMenu;
    }

    public com.forgemaster.growth.AchievementEvaluator achievements() {
        return achievements;
    }

    public com.forgemaster.growth.CraftRankManager ranks() {
        return ranks;
    }

    public SeasonManager seasons() {
        return seasons;
    }

    public EventManager events() {
        return events;
    }

    public ShopManager shop() {
        return shop;
    }

    public MentorManager mentors() {
        return mentors;
    }

    /** 出师服务（v2.19）：/zhujian master graduate 两步出师 */
    public com.forgemaster.mentor.GraduationService graduations() {
        return graduations;
    }

    /** 称号扩展（v2.19）：config titles 驱动解锁 + 手动佩戴（佩戴 &gt; 声望称号） */
    public com.forgemaster.title.TitleService titles() {
        return titles;
    }

    /** 行会商会（拓展第三档 · 社交层） */
    public com.forgemaster.guild.GuildService guilds() {
        return guilds;
    }
}