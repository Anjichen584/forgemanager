package com.forgemaster.guild;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.econ.CoinReason;
import com.forgemaster.econ.CoinRules;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 行会商会（拓展第三档 · 社交层）。照 {@link com.forgemaster.mentor.MentorManager} 的模式做的：
 * 邀请仅内存、退会冷却按时间戳、离线成员照常入账、存档一份独立 yml（guild-data.yml）。
 *
 * <p>规则：</p>
 * <ul>
 *   <li><b>建会</b>：{@code /zhujian guild create <名>}。名称玩家自起，最长 {@code guild.name-max-chars}
 *       字（默认 8），全服唯一。一人一会；退会 / 被踢后 {@code guild.leave-cooldown-hours} 小时内
 *       不能建会或入新会。</li>
 *   <li><b>邀请</b>：只有会长能 {@code guild invite <玩家>}，对方 {@code /zhujian guild accept|deny}，
 *       {@code guild.invite-expire-seconds} 秒内有效（默认 60，照 mentor）。</li>
 *   <li><b>金库</b>：成员 {@code guild deposit <剑币数>} 存入；会长 {@code guild upgrade} 花金库
 *       扩成员上限 +{@code guild.upgrade-size}，最多 {@code guild.upgrade-max} 次（金库唯一消耗出口）。</li>
 *   <li><b>锻造加成</b>：按成员数阶梯（{@code guild.bonus.*}）：≥3 人每人锻造剑币 +5%、
 *       ≥5 人 +10%。倍率由 ForgeManager 算好传入 {@link CoinRules#forgeReward}。</li>
 *   <li><b>行会周赛</b>：每次锻造成功记当周积分（粗糙炸剑不算），低频巡检（默认每小时查一次
 *       世界周）发现跨周就结算：积分第一的行会全员广播，会长领 {@code guild.week-first-prize} 剑币。</li>
 *   <li><b>死角</b>：会长退会 = 行会解散，金库退还平分给所有成员（余数归会长）；普通成员退出
 *       贡献清零，已吃到的加成不追回（加成是锻造当下发的，天然不追回）。</li>
 *   <li><b>离线 / 删号</b>：全部按 UUID 存查，找不到名字显示 UUID 前 8 位，不抛异常。</li>
 * </ul>
 *
 * <p>数据持久化（guild-data.yml）：{@code guilds.<id>} 一会一节，{@code players.<uuid>} 记
 * 所属会与退会冷却。结构改动（建会 / 入会 / 退会 / 扩容）立即落盘；周积分只标脏，
 * 跟随低频自动存档。</p>
 */
public final class GuildService {

    /** 一条行会。members / weekPoints 的 key 都是 UUID 字符串（照 StatsStore 存法） */
    public static final class Guild {
        public final String id;
        public String name;
        public UUID owner;
        public final LinkedHashSet<String> members = new LinkedHashSet<>();
        public long treasury;
        /** 当周 forge 积分：成员 uuid → 分数总和 */
        public final Map<String, Double> weekPoints = new LinkedHashMap<>();
        /** 已扩容次数（成员上限 = max-members + upgrades × upgrade-size） */
        public int upgrades;
        /**
         * 基金账本（v2.24 切片二）：成员捐款只进不退（退会不退款），唯一出口是
         * 会长花基金买科技。与金库 treasury 分开记：金库解散平分退还，基金不退。
         */
        public long fund;
        /** 科技等级：techId（小写）→ 已购等级，只认 {@link #isTech(String)} 里的 id */
        public final Map<String, Integer> techs = new LinkedHashMap<>();

        Guild(String id) {
            this.id = id;
        }

        /** 当前成员上限 */
        public int cap(int base, int perUpgrade) {
            return Math.max(1, base + upgrades * Math.max(0, perUpgrade));
        }

        /** 当周积分总和 */
        public double weekTotal() {
            double sum = 0;
            for (double v : weekPoints.values()) {
                sum += v;
            }
            return sum;
        }

        /** 成员 UUID 列表（坏数据跳过） */
        public List<UUID> memberIds() {
            List<UUID> out = new ArrayList<>();
            for (String raw : members) {
                try {
                    out.add(UUID.fromString(raw));
                } catch (IllegalArgumentException ignored) {
                    // 坏数据跳过
                }
            }
            return out;
        }
    }

    /** 玩家侧：所属会 id + 上次退会时间戳（毫秒），0 = 无冷却记录 */
    private static final class Member {
        String guildId = "";
        long cooldownAt;
    }

    private final ForgingMasterPlugin plugin;
    private final File file;
    private final Map<String, Guild> guilds = new LinkedHashMap<>();
    private final Map<UUID, Member> players = new HashMap<>();

    /** 待处理入会邀请：key = 受邀方 UUID；仅内存，重启失效（照 MentorManager） */
    private final Map<UUID, Invite> invites = new HashMap<>();

    private record Invite(String guildId, UUID inviter, long expiresAt) {
    }

    /** 行会共享仓库（v2.23）：物品真源 + warehouses 段的读写都归它 */
    private final GuildWarehouse warehouse;
    /** 行会委托（v2.23）：状态真源 + guilds.<id>.quest 段的读写 + 轮询任务都归它 */
    private final GuildQuests quests;

    private int nextId = 1;
    /** 上次结算的世界周编号，0 = 首次启动（只对齐不结算） */
    private long lastWeek;
    private volatile boolean dirty;
    private BukkitTask weeklyTask;
    private BukkitTask autosaveTask;
    /** 仓库 / 委托的高频脏冲刷（5 秒一轮异步，照 TombManager.startAutoFlush） */
    private BukkitTask flushTask;

    public GuildService(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "guild-data.yml");
        this.warehouse = new GuildWarehouse(plugin);
        this.quests = new GuildQuests(plugin);
    }

    /** 行会共享仓库（GUI / 路由查询用） */
    public GuildWarehouse warehouse() {
        return warehouse;
    }

    /** 行会委托服务（命令 / 查询用） */
    public GuildQuests quests() {
        return quests;
    }

    // ------------------------------------------------------------ 配置

    public boolean enabled() {
        return plugin.settings().flag("guild.enabled", true);
    }

    private int maxMembers() {
        return Math.max(1, plugin.settings().intOf("guild.max-members", 8));
    }

    private int nameMaxChars() {
        return Math.max(1, plugin.settings().intOf("guild.name-max-chars", 8));
    }

    private long inviteExpireMs() {
        return Math.max(10, plugin.settings().intOf("guild.invite-expire-seconds", 60)) * 1000L;
    }

    private long cooldownMs() {
        long hours = plugin.settings().longOf("guild.leave-cooldown-hours", 24);
        return Math.max(0L, hours) * 3_600_000L;
    }

    private long upgradeCost() {
        return Math.max(0, plugin.settings().longOf("guild.upgrade-cost", 2000));
    }

    private int upgradeSize() {
        return Math.max(0, plugin.settings().intOf("guild.upgrade-size", 2));
    }

    private int upgradeMax() {
        return Math.max(0, plugin.settings().intOf("guild.upgrade-max", 3));
    }

    private long weekFirstPrize() {
        return Math.max(0, plugin.settings().longOf("guild.week-first-prize", 500));
    }

    /**
     * 锻造剑币加成倍率：按成员数阶梯取最高档，1.0 = 无加成。
     * 由 ForgeManager 在 CoinRules 的锻造奖励处传入（guard null 的一行 hook）。
     */
    public double forgeCoinMultiplier(UUID playerId) {
        if (playerId == null) {
            return 1.0;
        }
        Guild g = guildOf(playerId);
        if (g == null) {
            return 1.0;
        }
        return 1.0 + bonusRateOf(g);
    }

    /** 某个行会当前的加成档位（0 = 未达档） */
    public double bonusRateOf(Guild g) {
        if (g == null) {
            return 0.0;
        }
        int n = g.members.size();
        double rate = 0.0;
        if (n >= Math.max(1, plugin.settings().intOf("guild.bonus.tier1.members", 3))) {
            rate = Math.max(0.0, plugin.settings().num("guild.bonus.tier1.rate", 0.05));
        }
        if (n >= Math.max(1, plugin.settings().intOf("guild.bonus.tier2.members", 5))) {
            rate = Math.max(rate, Math.max(0.0, plugin.settings().num("guild.bonus.tier2.rate", 0.10)));
        }
        return rate;
    }

    /** 当前档位的加成文案（面板 / GUI 用） */
    public String bonusLine(Guild g) {
        if (g == null) {
            return "<gray>无行会</gray>";
        }
        return "<gray>成员 <white>" + g.members.size() + "</white> · 锻造剑币 <#ffd166>+"
                + Msg.fmt(bonusRateOf(g) * 100, 0) + "%</#ffd166>";
    }

    // ------------------------------------------------------------ 基金与科技（v2.24 切片二）

    /** 科技线一：锻造费折扣（每级 -2%，代码上限 10%） */
    public static final String TECH_FORGE_DISCOUNT = "forge-discount";
    /** 科技线二：锻造成功率（每级 +1%，代码上限 5%） */
    public static final String TECH_FORGE_SUCCESS = "forge-success";
    /** 科技等级的代码硬上限：两条线各 5 级封顶，配置再大也夹回来 */
    private static final int TECH_LEVEL_HARD_CAP = 5;

    /** 是不是合法的科技 id（大小写不敏感） */
    public static boolean isTech(String id) {
        String n = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        return TECH_FORGE_DISCOUNT.equals(n) || TECH_FORGE_SUCCESS.equals(n);
    }

    /** 科技价（纯数值层）：第 level → level+1 级 = base + per × level，负参夹 0 */
    public static long techCost(long base, long per, int level) {
        return Math.max(0, base) + Math.max(0, per) * Math.max(0, level);
    }

    /** 等级上限夹取：[1, 5]，科技倍区已经在代码里写死一份，不许配置越过 */
    public static int clampTechMaxLevel(int cfg) {
        return Math.max(1, Math.min(TECH_LEVEL_HARD_CAP, cfg));
    }

    /**
     * 捐款后的基金（纯数值层）：负数捐款一律不动账。
     * 守恒由调用方保证——钱包 take 成功才把返回值写进 g.fund，两步不同时就不落账。
     */
    public static long fundAfterDonate(long fund, long amount) {
        return fund + Math.max(0, amount);
    }

    /**
     * 买科技后的基金（纯数值层）：基金不足返回 -1 且调用方不得动账，
     * 这是「买科技扣基金」守恒的唯一闸门。
     */
    public static long fundAfterTech(long fund, long cost) {
        if (cost <= 0) {
            return fund;
        }
        return fund >= cost ? fund - cost : -1;
    }

    /**
     * 锻造费乘区（纯数值层）：1 - min(0.10, level × perLevel)。
     * 上限 10% 写死在这里，ForgeManager 的落点只管乘这一行。
     */
    public static double feeMultiplierOf(int level, double perLevel) {
        double disc = Math.min(0.10, Math.max(0, level) * Math.max(0, perLevel));
        return Math.max(0, 1.0 - disc);
    }

    /** 锻造成功率加成（纯数值层）：min(0.05, level × perLevel)，上限 5% 写死 */
    public static double successBonusOf(int level, double perLevel) {
        return Math.min(0.05, Math.max(0, level) * Math.max(0, perLevel));
    }

    /** 某玩家所属行会的某条科技等级（无会 / 未知 id = 0） */
    public int techLevel(UUID playerId, String techId) {
        Guild g = guildOf(playerId);
        if (g == null || techId == null) {
            return 0;
        }
        return g.techs.getOrDefault(techId.trim().toLowerCase(Locale.ROOT), 0);
    }

    /**
     * 锻造费乘区查询口（ForgeManager 落点一行调用）：
     * {@code fee = Math.round(fee * plugin.guilds().forgeFeeMultiplier(uuid))}。
     * 返回 1 - discount（1.0 = 无折扣，满级 0.90 = 锻造费九折）。
     */
    public double forgeFeeMultiplier(UUID player) {
        return feeMultiplierOf(techLevel(player, TECH_FORGE_DISCOUNT),
                Math.max(0, plugin.settings().num("guild.tech.discount-per-level", 0.02)));
    }

    /**
     * 锻造成功率加成查询口（ForgeManager 落点一行调用）：
     * 返回 0 ~ 0.05 的小数（0.05 = 满级 +5%）。
     */
    public double forgeSuccessBonus(UUID player) {
        return successBonusOf(techLevel(player, TECH_FORGE_SUCCESS),
                Math.max(0, plugin.settings().num("guild.tech.success-per-level", 0.01)));
    }

    /** 科技 1 级基础价 */
    private long techCostBase() {
        return Math.max(0, plugin.settings().longOf("guild.tech.cost-base", 2000));
    }

    /** 科技逐级递增价 */
    private long techCostPerLevel() {
        return Math.max(0, plugin.settings().longOf("guild.tech.cost-per-level", 1500));
    }

    /** 科技等级上限（已夹到代码硬上限 5） */
    private int techMaxLevel() {
        return clampTechMaxLevel(plugin.settings().intOf("guild.tech.max-level", 5));
    }

    private com.forgemaster.stats.StatsStore stats() {
        return plugin.stats();
    }

    // ------------------------------------------------------------ 生命周期

    public void load() {
        guilds.clear();
        players.clear();
        if (file.exists()) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
            nextId = Math.max(1, yml.getInt("next-id", 1));
            lastWeek = yml.getLong("last-week", 0);
            ConfigurationSection root = yml.getConfigurationSection("guilds");
            if (root != null) {
                for (String id : root.getKeys(false)) {
                    ConfigurationSection sec = root.getConfigurationSection(id);
                    if (sec == null) {
                        continue;
                    }
                    try {
                        Guild g = new Guild(id);
                        g.name = sec.getString("name", "");
                        g.owner = UUID.fromString(sec.getString("owner", ""));
                        g.members.addAll(sec.getStringList("members"));
                        g.treasury = sec.getLong("treasury", 0);
                        g.upgrades = Math.max(0, sec.getInt("upgrades", 0));
                        // v2.24：基金账本与科技等级读回（负数 / 未知 id 直接丢弃）
                        g.fund = Math.max(0, sec.getLong("fund", 0));
                        ConfigurationSection tw = sec.getConfigurationSection("techs");
                        if (tw != null) {
                            for (String techId : tw.getKeys(false)) {
                                if (isTech(techId)) {
                                    g.techs.put(techId.toLowerCase(Locale.ROOT),
                                            Math.max(0, tw.getInt(techId, 0)));
                                }
                            }
                        }
                        ConfigurationSection wp = sec.getConfigurationSection("week-points");
                        if (wp != null) {
                            for (String uuid : wp.getKeys(false)) {
                                double v = wp.getDouble(uuid, 0);
                                if (v > 0) {
                                    g.weekPoints.put(uuid, v);
                                }
                            }
                        }
                        guilds.put(id, g);
                    } catch (Exception ignored) {
                        // 坏数据跳过，不影响启动
                    }
                }
            }
            ConfigurationSection ps = yml.getConfigurationSection("players");
            if (ps != null) {
                for (String key : ps.getKeys(false)) {
                    ConfigurationSection sec = ps.getConfigurationSection(key);
                    if (sec == null) {
                        continue;
                    }
                    try {
                        Member m = new Member();
                        m.guildId = sec.getString("guild", "");
                        m.cooldownAt = sec.getLong("cooldown-at", 0);
                        players.put(UUID.fromString(key), m);
                    } catch (Exception ignored) {
                        // 坏 UUID 跳过
                    }
                }
            }
            // v2.23：仓库 / 委托段读回（同一份 yml，坏数据各自内部兜住）
            warehouse.read(yml);
            quests.read(yml);
        }
        // 首次启动只对齐周编号，不补结算（没有历史积分可结）
        if (lastWeek <= 0) {
            lastWeek = worldWeek();
        }
        plugin.getLogger().info("已载入 " + guilds.size() + " 个行会");
    }

    /** 起巡检任务：低频（默认每小时）查一次世界周 + 定期落盘 + 仓库/委托脏冲刷（照 TombManager） */
    public void start() {
        stopTasks();
        long checkHours = Math.max(1, plugin.settings().intOf("guild.week-check-hours", 1));
        weeklyTask = Bukkit.getScheduler().runTaskTimer(plugin, this::weeklyCheck,
                20L * 60, 20L * 60 * 60 * checkHours);
        autosaveTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (dirty) {
                save();
            }
        }, 20L * 300, 20L * 300);
        // v2.23：仓库物品 / 委托进度走 5 秒异步冲刷，主线程点击路径零同步 IO。
        // save() 全程 synchronized，与主线程的结构性 save 互斥
        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (warehouse.dirty() || quests.dirty()) {
                save();
            }
        }, 100L, 100L);
        quests.start(); // 委托低频轮询（读成员档案增量）
    }

    public void shutdown() {
        stopTasks();
        save();
    }

    private void stopTasks() {
        if (weeklyTask != null) {
            weeklyTask.cancel();
            weeklyTask = null;
        }
        if (autosaveTask != null) {
            autosaveTask.cancel();
            autosaveTask = null;
        }
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        quests.stop();
    }

    // ------------------------------------------------------------ 命令入口

    /** /zhujian guild|hanghui <sub> */
    public void handle(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>行会要在游戏里操作。</red>");
            return;
        }
        if (!enabled()) {
            Msg.prefix(p, "<red>行会功能没开。</red>");
            return;
        }
        String sub = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create", "jian", "li" -> {
                if (args.length < 3) {
                    Msg.prefix(p, "<gray>用法：<white>/zhujian guild create <会名（"
                            + nameMaxChars() + " 字内）></white></gray>");
                } else {
                    create(p, args[2]);
                }
            }
            case "invite", "yaoqing" -> {
                if (args.length < 3) {
                    Msg.prefix(p, "<gray>用法：<white>/zhujian guild invite <玩家></white></gray>");
                } else {
                    invite(p, args[2]);
                }
            }
            case "accept", "jieshou" -> accept(p);
            case "deny", "jujue" -> deny(p);
            case "leave", "tuichu" -> leave(p);
            case "kick", "tichu" -> {
                if (args.length < 3) {
                    Msg.prefix(p, "<gray>用法：<white>/zhujian guild kick <玩家></white></gray>");
                } else {
                    kick(p, args[2]);
                }
            }
            case "info", "xinxi", "chakan" -> info(p);
            case "deposit", "cun", "juan" -> {
                if (args.length < 3) {
                    Msg.prefix(p, "<gray>用法：<white>/zhujian guild deposit <剑币数></white></gray>");
                } else {
                    deposit(p, args[2]);
                }
            }
            case "list", "liebiao" -> GuildGui.open(plugin, p);
            case "upgrade", "kuorong", "shengji" -> upgrade(p);
            case "warehouse", "cangku", "ck" -> GuildWarehouseGui.open(plugin, p);
            case "quest", "renwu" -> quests.command(p, args);
            // v2.24 切片二：商会基金（捐款 / 查询）与商会科技（会长买级 / 查询）
            case "fund", "jijin" -> fundCommand(p, args);
            case "tech", "keji" -> techCommand(p, args);
            default -> status(p);
        }
    }

    // ------------------------------------------------------------ 建会

    private void create(Player p, String rawName) {
        UUID me = p.getUniqueId();
        if (guildOf(me) != null) {
            Msg.prefix(p, "<red>你已经在行会里了，先退会再建新的。</red>");
            return;
        }
        String err = cooldownBlock(me);
        if (err != null) {
            Msg.prefix(p, "<red>" + err + "</red>");
            return;
        }
        String name = rawName.trim();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > nameMaxChars()) {
            Msg.prefix(p, "<red>会名要 1~" + nameMaxChars() + " 个字。</red>");
            return;
        }
        for (Guild g : guilds.values()) {
            if (g.name.equalsIgnoreCase(name)) {
                Msg.prefix(p, "<red>会名 <white>" + name + "</white> 已被占用，换一个。</red>");
                return;
            }
        }
        String id = String.valueOf(nextId++);
        Guild g = new Guild(id);
        g.name = name;
        g.owner = me;
        g.members.add(me.toString());
        guilds.put(id, g);
        Member m = member(me);
        m.guildId = id;
        save();
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7f, 1.4f);
        Msg.prefix(p, "<green>行会「<white>" + name + "</white>」成立了！你是会长，"
                + "用 <white>/zhujian guild invite</white> 拉人，<white>/zhujian guild deposit</white> 存金库。");
    }

    // ------------------------------------------------------------ 邀请 / 应答

    private void invite(Player p, String targetName) {
        Guild g = guildOf(p.getUniqueId());
        if (g == null) {
            Msg.prefix(p, "<gray>你还没有行会，先 <white>/zhujian guild create</white> 建一个。</gray>");
            return;
        }
        if (!g.owner.equals(p.getUniqueId())) {
            Msg.prefix(p, "<red>只有会长能发入会邀请。</red>");
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            Msg.prefix(p, "<red>找不到在线玩家 " + targetName + "。</red>");
            return;
        }
        String err = canJoin(target.getUniqueId(), g);
        if (err != null) {
            Msg.prefix(p, "<red>" + err + "</red>");
            return;
        }
        long sec = inviteExpireMs() / 1000L;
        invites.put(target.getUniqueId(), new Invite(g.id, p.getUniqueId(),
                System.currentTimeMillis() + inviteExpireMs()));
        Msg.prefix(p, "<gray>已向 <white>" + target.getName() + "</white> 发出入会邀请（"
                + sec + " 秒内有效）。</gray>");
        Msg.prefix(target, "<white>" + p.getName() + "</white> <gray>邀请你加入行会「</gray>"
                + "<#ffd166>" + g.name + "</#ffd166><gray>」。输入 </gray>"
                + "<white>/zhujian guild accept</white><gray> 加入（" + sec + " 秒内有效）。</gray>");
    }

    /** /zhujian guild accept —— 收下入会邀请 */
    private void accept(Player p) {
        UUID me = p.getUniqueId();
        Invite inv = invites.remove(me);
        if (inv == null) {
            Msg.prefix(p, "<gray>你现在没有待处理的入会邀请。</gray>");
            return;
        }
        if (System.currentTimeMillis() > inv.expiresAt()) {
            Msg.prefix(p, "<gray>入会邀请已过期。</gray>");
            return;
        }
        Guild g = guilds.get(inv.guildId());
        if (g == null) {
            Msg.prefix(p, "<gray>这个行会已经解散了。</gray>");
            return;
        }
        Player inviter = Bukkit.getPlayer(inv.inviter());
        String err = canJoin(me, g);
        if (err != null) {
            Msg.prefix(p, "<red>" + err + "</red>");
            if (inviter != null) {
                Msg.prefix(inviter, "<gray>" + p.getName() + " 没能入会：" + err + "</gray>");
            }
            return;
        }
        g.members.add(me.toString());
        Member m = member(me);
        m.guildId = g.id;
        save();
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.4f);
        Msg.prefix(p, "<green>你加入了行会「<white>" + g.name + "</white>」！"
                + bonusLine(g) + "</green>");
        notifyOnline(g.owner, "<green>" + p.getName() + " 加入了行会（" + g.members.size()
                + "/" + capOf(g) + "）。</green>");
        if (inviter != null && !inviter.getUniqueId().equals(g.owner)) {
            Msg.prefix(inviter, "<green>" + p.getName() + " 加入了行会。</green>");
        }
    }

    /** /zhujian guild deny —— 拒绝邀请（照 mentor 的静默拒绝 + 告知发起人） */
    private void deny(Player p) {
        Invite inv = invites.remove(p.getUniqueId());
        if (inv == null) {
            Msg.prefix(p, "<gray>你现在没有待处理的入会邀请。</gray>");
            return;
        }
        Guild g = guilds.get(inv.guildId());
        if (g != null) {
            notifyOnline(g.owner, "<gray>" + p.getName() + " 拒绝了入会邀请。</gray>");
        }
        Msg.prefix(p, "<gray>已拒绝该入会邀请。</gray>");
    }

    /**
     * 能否加入行会。守卫照 MentorManager.canBind 抄：一人一会 + 冷却 + 名额。
     *
     * @return null 表示可以；否则返回给玩家看的中文原因
     */
    private String canJoin(UUID playerId, Guild g) {
        if (guildOf(playerId) != null) {
            return "已经有行会了，先退会再加入。";
        }
        String err = cooldownBlock(playerId);
        if (err != null) {
            return err;
        }
        if (g.members.size() >= capOf(g)) {
            return g.name + " 人满了（上限 " + capOf(g) + "）。";
        }
        return null;
    }

    /** 退会冷却：还在冷却返回提示文案，否则 null */
    private String cooldownBlock(UUID id) {
        long cd = cooldownMs();
        if (cd <= 0) {
            return null;
        }
        long last = member(id).cooldownAt;
        long now = System.currentTimeMillis();
        if (last > 0 && now - last < cd) {
            return "退会后需要冷却，还剩 " + minutesLeft(cd - (now - last)) + " 分钟才能加入新行会。";
        }
        return null;
    }

    // ------------------------------------------------------------ 退会 / 踢人 / 解散

    private void leave(Player p) {
        Guild g = guildOf(p.getUniqueId());
        if (g == null) {
            Msg.prefix(p, "<gray>你还没有行会。</gray>");
            return;
        }
        if (g.owner.equals(p.getUniqueId())) {
            disband(g, "会长 " + p.getName() + " 退会，行会解散。");
            return;
        }
        removeMember(g, p.getUniqueId(), true);
        Msg.prefix(p, "<gray>你已退出「<white>" + g.name + "</white>」，"
                + Msg.fmt(cooldownMs() / 3_600_000.0, 0) + " 小时内不能再加入新行会。</gray>");
        notifyOnline(g.owner, "<gray>成员 <white>" + p.getName() + "</white> 退出了行会（"
                + g.members.size() + "/" + capOf(g) + "）。</gray>");
    }

    private void kick(Player p, String name) {
        Guild g = guildOf(p.getUniqueId());
        if (g == null) {
            Msg.prefix(p, "<gray>你还没有行会。</gray>");
            return;
        }
        if (!g.owner.equals(p.getUniqueId())) {
            Msg.prefix(p, "<red>只有会长能踢人。</red>");
            return;
        }
        UUID targetId = null;
        for (String raw : g.members) {
            UUID id = parseUuid(raw);
            if (id == null || id.equals(g.owner)) {
                continue;
            }
            if (name.equalsIgnoreCase(nameOf(id))) {
                targetId = id;
                break;
            }
        }
        if (targetId == null) {
            Msg.prefix(p, "<red>行会成员里没有 " + name + "。</red>");
            return;
        }
        removeMember(g, targetId, true);
        Msg.prefix(p, "<gray>已将 <white>" + nameOf(targetId) + "</white> 移出行会。</gray>");
        notifyOnline(targetId, "<gray>你被「<white>" + g.name + "</white>」移出了，"
                + Msg.fmt(cooldownMs() / 3_600_000.0, 0) + " 小时内不能再加入新行会。</gray>");
    }

    /**
     * 把成员移出（退会 / 被踢共用）。贡献清零；已吃到的加成不追回
     * （加成是锻造当下乘进去的，这里没有任何记录可追也没必要追）。
     */
    private void removeMember(Guild g, UUID memberId, boolean markCooldown) {
        g.members.remove(memberId.toString());
        g.weekPoints.remove(memberId.toString()); // 退会贡献清零
        Member m = member(memberId);
        m.guildId = "";
        if (markCooldown) {
            m.cooldownAt = System.currentTimeMillis();
        }
        save();
    }

    /**
     * 解散行会：金库退还平分给所有成员（整数平分，余数归会长），全部记退会冷却。
     * 只有「会长退会」这一条路会走到这里（死角色规定）。
     */
    private void disband(Guild g, String reason) {
        List<UUID> members = new ArrayList<>();
        for (String raw : g.members) {
            UUID id = parseUuid(raw);
            if (id != null) {
                members.add(id);
            }
        }
        long share = members.isEmpty() ? 0 : g.treasury / members.size();
        long remainder = members.isEmpty() ? 0 : g.treasury - share * members.size();
        // v2.23.1 修 P2：先算完所有人的份额并统一发钱、统一落盘，再拆行会。
        //
        // 原来会怎样：循环里「给一个人加钱（内存）→ removeMember() → save() 同步写
        // guild-data.yml」，即成员被移出会立刻落盘，而钱只在 stats 内存里等 5 分钟。
        // 中途崩溃 → 后半段成员的钱全丢，而且他们已经不在 members 里，无法再结算。
        //
        // 现在为什么不会：先把钱全部发完并同步落一次 data.yml，再走拆除流程。
        // 崩溃只可能停在「钱已发、行会还在」，重试时 treasury 已为 0（见下面清零），
        // 不会二次分赃，也不会有人漏领。
        for (UUID id : members) {
            long give = share;
            if (id.equals(g.owner)) {
                give += remainder; // 余数归会长
            }
            if (give > 0) {
                stats().addCoins(id, give, CoinReason.REFUND);
            }
            notifyOnline(id, "<red>" + reason + "</red><gray>金库平分，你分得 "
                    + CoinRules.fmt(give) + "。</gray>");
        }
        g.treasury = 0; // 钱已全部分完：重试时不会重复发放
        save();
        stats().save(); // 剑币同步落盘，之后才允许行会消失
        for (UUID id : members) {
            removeMember(g, id, true);
        }
        guilds.remove(g.id);
        warehouse.remove(g.id); // v2.23：仓库随会解散清空
        quests.remove(g.id);    // v2.23：委托状态一并作废
        invites.values().removeIf(inv -> inv.guildId().equals(g.id)); // 挂着的邀请一并作废
        dirty = true;
        save();
        Bukkit.broadcast(Msg.mm(Msg.PREFIX + "<gray>行会「</gray><#ffd166>" + g.name
                + "</#ffd166><gray>」已解散。</gray>"));
    }

    // ------------------------------------------------------------ 金库 / 扩容

    private void deposit(Player p, String amountRaw) {
        Guild g = guildOf(p.getUniqueId());
        if (g == null) {
            Msg.prefix(p, "<gray>你还没有行会。</gray>");
            return;
        }
        long amount;
        try {
            amount = Long.parseLong(amountRaw.replace(",", ""));
        } catch (NumberFormatException ex) {
            Msg.prefix(p, "<red>数量要写数字：" + amountRaw);
            return;
        }
        if (amount <= 0) {
            Msg.prefix(p, "<red>数量要大于 0。</red>");
            return;
        }
        if (!stats().takeCoins(p.getUniqueId(), amount, CoinReason.GUILD)) {
            Msg.prefix(p, "<red>你的剑币不够 " + amount + "。</red>");
            return;
        }
        g.treasury += amount;
        dirty = true;
        save();
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.6f);
        Msg.prefix(p, "<green>已存入 <white>" + amount + "</white> 剑币，金库现有 "
                + CoinRules.fmt(g.treasury) + "。</green>");
        notifyOnline(g.owner, "<gray>成员 <white>" + p.getName() + "</white> 向金库存入 "
                + CoinRules.fmt(amount) + "。</gray>");
    }

    /** 会长花金库扩成员上限（金库唯一消耗出口） */
    private void upgrade(Player p) {
        Guild g = guildOf(p.getUniqueId());
        if (g == null) {
            Msg.prefix(p, "<gray>你还没有行会。</gray>");
            return;
        }
        if (!g.owner.equals(p.getUniqueId())) {
            Msg.prefix(p, "<red>只有会长能扩容。</red>");
            return;
        }
        if (g.upgrades >= upgradeMax()) {
            Msg.prefix(p, "<red>已经扩容 " + g.upgrades + " 次，到顶了。</red>");
            return;
        }
        long cost = upgradeCost();
        if (g.treasury < cost) {
            Msg.prefix(p, "<red>金库不够：扩容要 <white>" + cost + "</white>，现有 "
                    + g.treasury + "。</red>");
            return;
        }
        g.treasury -= cost;
        g.upgrades++;
        save();
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1.2f);
        Msg.prefix(p, "<green>扩容成功！成员上限 <white>" + capOf(g) + "</white>（已扩 "
                + g.upgrades + "/" + upgradeMax() + " 次），金库余 " + g.treasury + "。</green>");
    }

    // ------------------------------------------------------------ 基金 / 科技（v2.24 切片二）

    /** /zhujian guild fund [donate <数量>|info] */
    private void fundCommand(Player p, String[] args) {
        Guild g = guildOf(p.getUniqueId());
        if (g == null) {
            Msg.prefix(p, "<gray>你还没有行会。</gray>");
            return;
        }
        String op = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "info";
        switch (op) {
            case "donate", "juan" -> {
                if (args.length < 4) {
                    Msg.prefix(p, "<gray>用法：<white>/zhujian guild fund donate <数量></white></gray>");
                } else {
                    fundDonate(p, g, args[3]);
                }
            }
            case "info", "xinxi", "chakan" -> fundInfo(p, g);
            default -> Msg.prefix(p, "<gray>用法：<white>/zhujian guild fund [donate <数量>|info]</white></gray>");
        }
    }

    /** 成员向基金捐剑币。守恒：钱包 take 成功才写 g.fund；捐款只进不退（退会不退款） */
    private void fundDonate(Player p, Guild g, String amountRaw) {
        long amount;
        try {
            amount = Long.parseLong(amountRaw.replace(",", ""));
        } catch (NumberFormatException ex) {
            Msg.prefix(p, "<red>数量要写数字：" + amountRaw);
            return;
        }
        if (amount <= 0) {
            Msg.prefix(p, "<red>数量要大于 0。</red>");
            return;
        }
        // v2.23.7：扣款走 Wallet 收口（coin-sinks=server 时与 Vault 同账本），禁止直连 stats().takeCoins
        if (!plugin.wallet().take(p, amount, CoinReason.GUILD)) {
            Msg.prefix(p, "<red>你的剑币不够 " + amount + "。</red>");
            return;
        }
        g.fund = fundAfterDonate(g.fund, amount);
        save();
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.6f);
        Msg.prefix(p, "<green>已向基金捐入 <white>" + amount + "</white> 剑币，基金现有 "
                + CoinRules.fmt(g.fund) + "。</green>");
        notifyOnline(g.owner, "<gray>成员 <white>" + p.getName() + "</white> 向基金捐入 "
                + CoinRules.fmt(amount) + "（现有 " + CoinRules.fmt(g.fund) + "）。</gray>");
    }

    /** 基金余额与两条科技线当前等级 */
    private void fundInfo(Player p, Guild g) {
        Msg.send(p, "<dark_gray>──── <#ffd166>" + g.name + " · 商会基金</#ffd166> <dark_gray>────");
        Msg.send(p, "<gray>基金: <#ffd166>" + CoinRules.fmt(g.fund) + "</#ffd166> "
                + "<dark_gray>（捐款只进不退，唯一出口是会长买科技）</dark_gray>");
        techLines(p, g);
        Msg.send(p, "<dark_gray>/zhujian guild fund donate <数量> 捐款 · /zhujian guild tech buy <techId> 买科技</dark_gray>");
    }

    /** /zhujian guild tech [buy <techId>|info] */
    private void techCommand(Player p, String[] args) {
        Guild g = guildOf(p.getUniqueId());
        if (g == null) {
            Msg.prefix(p, "<gray>你还没有行会。</gray>");
            return;
        }
        String op = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "info";
        switch (op) {
            case "buy", "gou" -> {
                if (args.length < 4) {
                    Msg.prefix(p, "<gray>用法：<white>/zhujian guild tech buy <"
                            + TECH_FORGE_DISCOUNT + "|" + TECH_FORGE_SUCCESS + "></white></gray>");
                } else {
                    techBuy(p, g, args[3]);
                }
            }
            case "info", "xinxi" -> {
                Msg.send(p, "<dark_gray>──── <#ffd166>" + g.name + " · 商会科技</#ffd166> <dark_gray>────");
                techLines(p, g);
            }
            default -> Msg.prefix(p, "<gray>用法：<white>/zhujian guild tech [buy <techId>|info]</white></gray>");
        }
    }

    /** 两条科技线的当前等级 / 效果 / 下一级价格 */
    private void techLines(Player p, Guild g) {
        int maxLv = techMaxLevel();
        long base = techCostBase();
        long per = techCostPerLevel();
        double discPer = Math.max(0, plugin.settings().num("guild.tech.discount-per-level", 0.02));
        double succPer = Math.max(0, plugin.settings().num("guild.tech.success-per-level", 0.01));
        int lvDiscount = g.techs.getOrDefault(TECH_FORGE_DISCOUNT, 0);
        int lvSuccess = g.techs.getOrDefault(TECH_FORGE_SUCCESS, 0);
        double disc = 1.0 - feeMultiplierOf(lvDiscount, discPer);
        double succ = successBonusOf(lvSuccess, succPer);
        Msg.send(p, "<gray>" + TECH_FORGE_DISCOUNT + ": <white>" + lvDiscount + "/" + maxLv
                + "</white> <dark_gray>·</dark_gray> 锻造费 <#ffd166>-"
                + Msg.fmt(disc * 100, 0) + "%</#ffd166>"
                + (lvDiscount < maxLv ? " <dark_gray>· 下一级 " + techCost(base, per, lvDiscount) + "</dark_gray>" : " <green>已满级</green>"));
        Msg.send(p, "<gray>" + TECH_FORGE_SUCCESS + ": <white>" + lvSuccess + "/" + maxLv
                + "</white> <dark_gray>·</dark_gray> 成功率 <#ffd166>+"
                + Msg.fmt(succ * 100, 0) + "%</#ffd166>"
                + (lvSuccess < maxLv ? " <dark_gray>· 下一级 " + techCost(base, per, lvSuccess) + "</dark_gray>" : " <green>已满级</green>"));
        Msg.send(p, "<dark_gray>效果对全体成员生效：锻造费乘 " + Msg.fmt(feeMultiplierOf(lvDiscount, discPer), 2)
                + " · 成功率 +" + Msg.fmt(succ * 100, 0) + "%</dark_gray>");
    }

    /** 会长花基金买科技。守恒闸门：fundAfterTech 返回 -1 不动账 */
    private void techBuy(Player p, Guild g, String techRaw) {
        if (!g.owner.equals(p.getUniqueId())) {
            Msg.prefix(p, "<red>只有会长能花基金升级科技。</red>");
            return;
        }
        String techId = techRaw.trim().toLowerCase(Locale.ROOT);
        if (!isTech(techId)) {
            Msg.prefix(p, "<red>没有这个科技：<white>" + techRaw + "</white>，只有 <white>"
                    + TECH_FORGE_DISCOUNT + "</white> 与 <white>" + TECH_FORGE_SUCCESS + "</white>。</red>");
            return;
        }
        int cur = g.techs.getOrDefault(techId, 0);
        int maxLv = techMaxLevel();
        if (cur >= maxLv) {
            Msg.prefix(p, "<red>「" + techId + "」已经 " + cur + " 级，到顶了。</red>");
            return;
        }
        long cost = techCost(techCostBase(), techCostPerLevel(), cur);
        long after = fundAfterTech(g.fund, cost);
        if (after < 0) {
            Msg.prefix(p, "<red>基金不够：「" + techId + "」升 " + (cur + 1) + " 级要 <white>" + cost
                    + "</white>，现有 " + CoinRules.fmt(g.fund) + "。让大家 <white>/zhujian guild fund donate</white> 捐一捐。</red>");
            return;
        }
        g.fund = after;
        g.techs.put(techId, cur + 1);
        save();
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1.4f);
        Msg.prefix(p, "<green>科技「<white>" + techId + "</white>」升到 <white>" + (cur + 1) + "/" + maxLv
                + "</white> 级，基金支出 <white>" + cost + "</white>，余 " + CoinRules.fmt(g.fund) + "。</green>");
        for (UUID id : g.memberIds()) {
            if (!id.equals(p.getUniqueId())) {
                notifyOnline(id, "<gray>会长升级了商会科技「</gray><white>" + techId
                        + "</white><gray>」（" + (cur + 1) + " 级），锻造加成对全员生效。</gray>");
            }
        }
    }

    // ------------------------------------------------------------ 周赛登记 / 结算

    /**
     * 锻造成功登记当周积分（ForgeManager.settle 的 pass 登记点旁边一行调用）。
     * 粗糙炸剑由调用方挡掉；这里只认「有会籍的人」。
     */
    public void addWeekPoints(UUID playerId, double points) {
        if (playerId == null || points <= 0 || !enabled()) {
            return;
        }
        Guild g = guildOf(playerId);
        if (g == null) {
            return;
        }
        g.weekPoints.merge(playerId.toString(), points, Double::sum);
        dirty = true;
    }

    /** 世界周编号：主世界 fullTime / 168000 tick（7 个 MC 日 = 一周） */
    private long worldWeek() {
        if (Bukkit.getWorlds().isEmpty()) {
            return 0;
        }
        return Bukkit.getWorlds().get(0).getFullTime() / 168_000L;
    }

    /** 低频巡检：跨周才结算，平时直接返回（每小时跑一次也不心疼） */
    private void weeklyCheck() {
        long week = worldWeek();
        if (week <= lastWeek) {
            return;
        }
        settleWeek(week);
    }

    /** 结算上周：积分第一的行会全员广播，会长领奖；全员积分清零进入新一周 */
    private void settleWeek(long newWeek) {
        Guild best = null;
        double bestScore = 0;
        for (Guild g : guilds.values()) {
            double total = g.weekTotal();
            if (total > bestScore) {
                bestScore = total;
                best = g;
            }
        }
        for (Guild g : guilds.values()) {
            g.weekPoints.clear();
        }
        lastWeek = newWeek;
        dirty = true;
        save();
        if (best == null || bestScore <= 0) {
            return; // 上周没人锻剑，静默翻周
        }
        StringBuilder names = new StringBuilder();
        for (String raw : best.members) {
            UUID id = parseUuid(raw);
            if (id == null) {
                continue;
            }
            if (names.length() > 0) {
                names.append("<gray>、</gray>");
            }
            names.append("<white>").append(nameOf(id)).append("</white>");
        }
        Bukkit.broadcast(Msg.mm(Msg.PREFIX + "<gradient:#ffd166:#ff7a18><bold>行会周赛</bold></gradient> "
                + "<dark_gray>| 上周锻造积分榜首</dark_gray>"));
        Bukkit.broadcast(Msg.mm("<dark_gray>        <#ffd166>「" + best.name + "」</#ffd166> "
                + Msg.fmt(bestScore, 0) + " 分 <dark_gray>·</dark_gray> 成员：" + names));
        long prize = weekFirstPrize();
        if (prize > 0 && best.owner != null) {
            stats().addCoins(best.owner, prize, CoinReason.CONTEST);
            notifyOnline(best.owner, "<#ffd166>你的行会拿了周赛第一，会长奖励 "
                    + CoinRules.fmt(prize) + " 已入账。</#ffd166>");
        }
        for (UUID id : best.memberIds()) {
            Player pl = Bukkit.getPlayer(id);
            if (pl != null) {
                pl.playSound(pl.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.2f);
            }
        }
    }

    // ------------------------------------------------------------ 状态面板

    private void status(Player p) {
        Guild g = guildOf(p.getUniqueId());
        if (g == null) {
            Msg.send(p, "<dark_gray>──── <white>行会</white> <dark_gray>────");
            Msg.send(p, "<gray>行会: <white>无</white>");
            String err = cooldownBlock(p.getUniqueId());
            if (err != null) {
                Msg.send(p, "<gray>" + err);
            }
            Msg.send(p, "<dark_gray>/zhujian guild create <会名> 建会 · /zhujian guild list 全服行会");
            return;
        }
        info(p);
    }

    private void info(Player p) {
        Guild g = guildOf(p.getUniqueId());
        if (g == null) {
            status(p);
            return;
        }
        Msg.send(p, "<dark_gray>──── <#ffd166>行会「" + g.name + "」</#ffd166> <dark_gray>────");
        Msg.send(p, "<gray>会长: <white>" + nameOf(g.owner) + "</white> <dark_gray>·</dark_gray> "
                + bonusLine(g));
        Msg.send(p, "<gray>成员: <white>" + g.members.size() + "</white><gray>/" + capOf(g)
                + " <dark_gray>·</dark_gray> 已扩容 <white>" + g.upgrades + "</white><gray>/"
                + upgradeMax() + " <dark_gray>·</dark_gray> 扩容费 <white>" + upgradeCost() + "</white>");
        for (String raw : g.members) {
            UUID id = parseUuid(raw);
            if (id == null) {
                continue;
            }
            double pts = g.weekPoints.getOrDefault(raw, 0.0);
            Msg.send(p, "<gray>  - <white>" + nameOf(id) + "</white>"
                    + (id.equals(g.owner) ? " <#ffd166>[会长]</#ffd166>" : "")
                    + (Bukkit.getPlayer(id) != null ? "<green>在线</green>" : "<dark_gray>离线</dark_gray>")
                    + " <dark_gray>·</dark_gray> <gray>周积分 <white>" + Msg.fmt(pts, 0) + "</white>");
        }
        Msg.send(p, "<gray>金库: " + CoinRules.fmt(g.treasury) + " <dark_gray>·</dark_gray> "
                + "<gray>当周总积分 <white>" + Msg.fmt(g.weekTotal(), 0) + "</white>");
        // v2.23：仓库 / 委托一行速览
        Msg.send(p, "<gray>仓库: <white>" + warehouse.usage(g.id) + "/" + warehouse.capacity()
                + "</white> 格 <dark_gray>·</dark_gray> "
                + (GuildWarehouse.canWithdraw(g.owner.equals(p.getUniqueId()), warehouse.memberWithdraw())
                ? "<green>可存可取</green>" : "<gray>成员只存不取</gray>")
                + " <dark_gray>/zhujian guild warehouse</dark_gray>");
        GuildQuests.QuestState q = quests.peek(g.id);
        if (q != null && !q.type.isEmpty()) {
            Msg.send(p, "<gray>委托: <white>" + GuildQuests.typeName(q.type) + " ×" + q.target
                    + "</white> <dark_gray>·</dark_gray> 进度 <#ffd166>" + q.progress + "/" + q.target
                    + "</#ffd166>" + (q.completed ? " <green>已达成，/zhujian guild quest claim 领奖</green>" : ""));
        } else {
            Msg.send(p, "<gray>委托: <white>待刷新</white> <dark_gray>· /zhujian guild quest</dark_gray>");
        }
        Msg.send(p, "<dark_gray>/zhujian guild deposit <数> 存金库 · warehouse 仓库 · quest 委托 · "
                + "invite 邀人 · leave 退会 · "
                + (g.owner.equals(p.getUniqueId()) ? "kick 踢人 · upgrade 扩容" : "list 全服行会"));
    }

    // ------------------------------------------------------------ 查询（GUI 用）

    public boolean hasGuild(UUID id) {
        return guildOf(id) != null;
    }

    public Guild guildOf(UUID id) {
        if (id == null) {
            return null;
        }
        Member m = players.get(id);
        if (m == null || m.guildId.isEmpty()) {
            return null;
        }
        return guilds.get(m.guildId);
    }

    public List<Guild> all() {
        return new ArrayList<>(guilds.values());
    }

    private int capOf(Guild g) {
        return g.cap(maxMembers(), upgradeSize());
    }

    /** 玩家名：优先锻造档案，其次 Bukkit 离线缓存，最后 UUID 前 8 位（照 MentorManager） */
    private String nameOf(UUID id) {
        String n = stats().peek(id) == null ? "" : stats().peek(id).name;
        if (n != null && !n.isEmpty()) {
            return n;
        }
        OfflinePlayer off = Bukkit.getOfflinePlayer(id);
        String offName = off.getName();
        return offName != null ? offName : id.toString().substring(0, 8);
    }

    private void notifyOnline(UUID id, String raw) {
        if (id == null) {
            return;
        }
        Player pl = Bukkit.getPlayer(id);
        if (pl != null) {
            Msg.prefix(pl, raw);
        }
    }

    private Member member(UUID id) {
        return players.computeIfAbsent(id, k -> new Member());
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static long minutesLeft(long ms) {
        return Math.max(1, (ms + 59_999L) / 60_000L);
    }

    // ------------------------------------------------------------ 存档

    /**
     * 结构改动即存；周积分标脏等低频自动存（照 StatsStore 的 dirty 节流）。
     *
     * <p>v2.23：仓库 / 委托的 5 秒异步冲刷也会走到这里（照 TombManager 审计 #8 的
     * 语义保留 synchronized，把「组装 + 写文件」整段与主线程互斥）；遍历改为快照 +
     * 按行会兜 CME，撞了竞态就保持脏标记等下一轮重存。</p>
     */
    public synchronized void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("next-id", nextId);
        yml.set("last-week", lastWeek);
        boolean race = false;
        for (Guild g : new ArrayList<>(guilds.values())) {
            try {
                String base = "guilds." + g.id;
                yml.set(base + ".name", g.name);
                yml.set(base + ".owner", g.owner == null ? "" : g.owner.toString());
                yml.set(base + ".members", new ArrayList<>(g.members));
                yml.set(base + ".treasury", g.treasury);
                yml.set(base + ".upgrades", g.upgrades);
                // v2.24：基金账本与科技等级（照 weekPoints 的快照遍历，防 CME 写歪）
                yml.set(base + ".fund", g.fund);
                for (Map.Entry<String, Integer> e : new LinkedHashMap<>(g.techs).entrySet()) {
                    yml.set(base + ".techs." + e.getKey(), e.getValue());
                }
                for (Map.Entry<String, Double> e : new LinkedHashMap<>(g.weekPoints).entrySet()) {
                    yml.set(base + ".week-points." + e.getKey(), e.getValue());
                }
            } catch (java.util.ConcurrentModificationException ex) {
                race = true; // 这一行会写歪就别清脏，下一轮重存
            }
        }
        quests.write(yml);   // v2.23：guilds.<id>.quest 段
        warehouse.write(yml); // v2.23：warehouses.<id> 段
        for (Map.Entry<UUID, Member> e : new LinkedHashMap<>(players).entrySet()) {
            try {
                Member m = e.getValue();
                if (m.guildId.isEmpty() && m.cooldownAt <= 0) {
                    continue; // 无会无冷却的不占盘
                }
                String base = "players." + e.getKey();
                yml.set(base + ".guild", m.guildId);
                yml.set(base + ".cooldown-at", m.cooldownAt);
            } catch (java.util.ConcurrentModificationException ex) {
                race = true;
            }
        }
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("无法创建插件数据目录");
                return;
            }
            yml.save(file);
            // 写盘期间没有新改动进来才清脏；有竞态就保持脏等下一轮（照 StatsStore 语义）
            if (!race) {
                dirty = false;
                warehouse.clearDirty();
                quests.clearDirty();
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("保存 guild-data.yml 失败: " + ex.getMessage());
        }
    }
}
