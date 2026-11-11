package com.forgemaster.tomb;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.econ.CoinReason;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 剑冢：把成品埋进全服可见的剑冢（tombs.yml），别人可以挑战复刻，成功拿剑币与声望，失败扣费。
 *
 * <p>存档字段：id、主人 uuid/名字、剑名、埋入时间、剑的快照（Base64 的 ItemStack 字节）、
 * 材料 id、品质、评分。挑战规则见 config.yml 的 tomb 段。</p>
 */
public final class TombManager {

    /** 一座坟 */
    public static final class Tomb {
        public final String id;
        public final UUID owner;
        public final String ownerName;
        public final String swordName;
        public final long buriedAt;
        public final String materialId;
        public final String quality;
        public final double score;
        public final ItemStack snapshot;
        public int challenges;
        public int wins;
        /** 配方：材料等级（COMMON/REFINED/CELESTIAL）、词条 id 集合（小写）、钢纹层数 */
        public String grade = "COMMON";
        public Set<String> affixes = new HashSet<>();
        public int layers;
        /** 这条是不是「碎剑留档」：强化时碎掉的剑只进档，不能被取回 */
        public boolean broken;
        /** 碎在 +几（仅 broken = true 时有意义） */
        public int brokenAt;

        public Tomb(String id, UUID owner, String ownerName, String swordName, long buriedAt,
                    String materialId, String quality, double score, ItemStack snapshot) {
            this.id = id;
            this.owner = owner;
            this.ownerName = ownerName;
            this.swordName = swordName;
            this.buriedAt = buriedAt;
            this.materialId = materialId;
            this.quality = quality;
            this.score = score;
            this.snapshot = snapshot;
        }
    }

    private final ForgingMasterPlugin plugin;
    private final File file;
    // 审计 #8：save 改为异步冲刷后，主线程的埋/取会与异步遍历并发，
    // LinkedHashMap 换 ConcurrentHashMap（all() 本就按 buriedAt 重排，插入顺序无用），
    // 弱一致迭代不抛 CME；Tomb 上的 challenges/wins 是 int 自增，读取无撕裂风险
    private final Map<String, Tomb> tombs = new java.util.concurrent.ConcurrentHashMap<>();
    private final Random random = new Random();
    /** 残影挑战引擎（v2.7）：把剑冢记录变成一场架 */
    private final TombChallenge challengeEngine;
    /** 残影词缀引擎（v2.23）：剑冢残影与秘境守卫共用的词缀掷签 / PDC 标记 / 伤害监听 */
    private final TombGuardAffixes guardAffixes;
    /** 有未落盘的变更时为真；异步冲刷任务只在它为真时才写文件（审计 #8） */
    private volatile boolean dirty;
    private int flushTaskId = -1;

    public TombManager(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "tombs.yml");
        this.challengeEngine = new TombChallenge(plugin, this);
        this.guardAffixes = new TombGuardAffixes(plugin);
    }

    /** 残影挑战引擎（召唤 / 冷却 / 回收都在它身上） */
    public TombChallenge challenges() {
        return challengeEngine;
    }

    /** 残影词缀引擎（v2.23，秘境守卫也经 {@code tombs().guardAffixes()} 用它） */
    public TombGuardAffixes guardAffixes() {
        return guardAffixes;
    }

    // ------------------------------------------------------------ 配置

    public boolean enabled() {
        return plugin.settings().flag("tomb.enabled", true);
    }

    private int maxPerPlayer() {
        return Math.max(1, plugin.settings().intOf("tomb.max-per-player", 5));
    }

    private boolean ownerCanRetrieve() {
        return plugin.settings().flag("tomb.owner-can-retrieve", true);
    }

    private long retrieveFee() {
        return Math.max(0, plugin.settings().longOf("tomb.retrieve-fee", 0));
    }

    private long challengeFee() {
        return Math.max(0, plugin.settings().longOf("tomb.challenge-fee", 30));
    }

    private long challengeWinCoins() {
        return Math.max(0, plugin.settings().longOf("tomb.challenge-win-coins", 120));
    }

    private int challengeWinRep() {
        return Math.max(0, plugin.settings().intOf("tomb.challenge-win-reputation", 20));
    }

    private int challengeLoseRep() {
        return Math.max(0, plugin.settings().intOf("tomb.challenge-lose-reputation", 5));
    }

    /** 基础复刻成功率；每高一档品质降低 difficulty-per-quality，每级专精加 mastery-bonus-per-level */
    private double baseChance() {
        return plugin.settings().num("tomb.base-chance", 0.6);
    }

    // ------------------------------------------------------------ 载入 / 保存

    public void load() {
        tombs.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yml.getConfigurationSection("tombs");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) {
                continue;
            }
            try {
                ItemStack snap = ItemStack.deserializeBytes(Base64.getDecoder().decode(sec.getString("item", "")));
                Tomb t = new Tomb(id,
                        UUID.fromString(sec.getString("owner", "")),
                        sec.getString("owner-name", "?"),
                        sec.getString("sword-name", "无名之刃"),
                        sec.getLong("buried-at", System.currentTimeMillis()),
                        sec.getString("material", "iron"),
                        sec.getString("quality", "COMMON"),
                        sec.getDouble("score", 0),
                        snap);
                t.challenges = sec.getInt("challenges", 0);
                t.wins = sec.getInt("wins", 0);
                t.grade = sec.getString("grade", "COMMON");
                t.affixes = new HashSet<>(sec.getStringList("affixes"));
                t.layers = sec.getInt("layers", 0);
                t.broken = sec.getBoolean("broken", false);
                t.brokenAt = sec.getInt("broken-at", 0);
                tombs.put(id, t);
            } catch (Exception ex) {
                plugin.getLogger().warning("剑冢条目 " + id + " 损坏，已跳过");
            }
        }
        plugin.getLogger().info("已载入 " + tombs.size() + " 座剑冢");
        // 磁盘即真相：重载后内存与磁盘一致，任何残留脏标记都作废
        dirty = false;
    }

    /**
     * 启动低频异步冲刷（5 秒一轮）：仅 dirty 时才真正写文件。
     *
     * <p>审计 #8：tombs.yml 每条含完整物品 Base64 快照且随埋剑数量无上限增长，
     * 原来每次埋/取/结算都主线程同步全量重写，500 座剑冢时单次 save 约 10~30ms。
     * 改为标脏 + 异步冲刷后，主线程动作路径零同步 IO；异步窗口（≤5 秒）内崩溃
     * 最多漏最后一笔埋/取变更，与拍卖行同一套 dirty 模式。</p>
     */
    public void startAutoFlush() {
        if (flushTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(flushTaskId);
        }
        flushTaskId = plugin.getServer().getScheduler()
                .runTaskTimerAsynchronously(plugin, this::flushIfDirty, 100L, 100L).getTaskId();
    }

    /** 关服冲刷：停掉异步任务并强制落一次盘（onDisable 调用） */
    public synchronized void stop() {
        if (flushTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(flushTaskId);
            flushTaskId = -1;
        }
        save();
    }

    private void flushIfDirty() {
        if (dirty) {
            save();
        }
    }

    /** 标记有未落盘的变更；真正的写盘交给低频异步冲刷（审计 #8） */
    public void markDirty() {
        dirty = true;
    }

    /** 自检 / 运维观测用：当前是否有未落盘变更 */
    public boolean dirty() {
        return dirty;
    }

    /**
     * 同步写盘。正常变更路径不再直调它（改走 markDirty + 异步冲刷），
     * 只剩关服冲刷（stop）强制调用；冲刷任务在异步线程调它，因此保留 synchronized
     * 与主线程 stop / 回档快照互斥。
     */
    public synchronized void save() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            return;
        }
        YamlConfiguration yml = new YamlConfiguration();
        for (Tomb t : tombs.values()) {
            String base = "tombs." + t.id + ".";
            yml.set(base + "owner", t.owner.toString());
            yml.set(base + "owner-name", t.ownerName);
            yml.set(base + "sword-name", t.swordName);
            yml.set(base + "buried-at", t.buriedAt);
            yml.set(base + "material", t.materialId);
            yml.set(base + "quality", t.quality);
            yml.set(base + "score", t.score);
            yml.set(base + "challenges", t.challenges);
            yml.set(base + "wins", t.wins);
            yml.set(base + "grade", t.grade);
            yml.set(base + "affixes", new java.util.ArrayList<>(t.affixes));
            yml.set(base + "layers", t.layers);
            yml.set(base + "broken", t.broken);
            yml.set(base + "broken-at", t.brokenAt);
            yml.set(base + "item", Base64.getEncoder().encodeToString(t.snapshot.serializeAsBytes()));
        }
        try {
            yml.save(file);
            // 写盘与全部变更共用 this 锁：期间不可能插进新变更，这里清脏是安全的
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().warning("保存 tombs.yml 失败: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ 查询

    public List<Tomb> all() {
        List<Tomb> list = new ArrayList<>(tombs.values());
        list.sort(Comparator.comparingLong((Tomb t) -> t.buriedAt).reversed());
        return list;
    }

    public Tomb get(String id) {
        return id == null ? null : tombs.get(id.toUpperCase(Locale.ROOT));
    }

    public long countOf(UUID owner) {
        return tombs.values().stream()
                .filter(t -> t.owner.equals(owner))
                .filter(t -> !t.broken)
                .count();
    }

    // ------------------------------------------------------------ 埋剑

    /** 埋手上的剑。成功返回提示，失败返回 null 并已自行提示 */
    public void bury(Player player) {
        if (!enabled()) {
            Msg.prefix(player, "<red>剑冢暂未开放。</red>");
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir() || !plugin.items().isForged(hand)) {
            Msg.prefix(player, "<red>主手要拿一把锻造品才能埋进剑冢。</red>");
            return;
        }
        if (countOf(player.getUniqueId()) >= maxPerPlayer()) {
            Msg.prefix(player, "<red>你在剑冢里已经埋了 " + maxPerPlayer() + " 把剑，取回一把再来。</red>");
            return;
        }
        var meta = hand.getItemMeta();
        var pdc = meta.getPersistentDataContainer();
        String mat = pdc.getOrDefault(Keys.MATERIAL, PersistentDataType.STRING, "iron");
        String quality = pdc.getOrDefault(Keys.QUALITY, PersistentDataType.STRING, "COMMON");
        double score = pdc.getOrDefault(Keys.SCORE, PersistentDataType.DOUBLE, 0.0);
        String swordName = plugin.spirit() == null ? "" : plugin.spirit().nameOf(hand);
        if (swordName.isBlank()) {
            swordName = Msg.plain(meta.displayName());
        }

        ItemStack snapshot = hand.clone();
        String id = newId();
        Tomb tomb = new Tomb(id, player.getUniqueId(), player.getName(), swordName,
                System.currentTimeMillis(), mat, quality, score, snapshot);
        // 配方：材料等级 / 词条集合 / 钢纹层数，挑战时用来比对
        tomb.grade = pdc.getOrDefault(Keys.USED_GRADE, PersistentDataType.STRING, "COMMON");
        tomb.layers = pdc.getOrDefault(Keys.LAYERS, PersistentDataType.INTEGER, 0);
        String csv = pdc.getOrDefault(Keys.AFFIX_LIST, PersistentDataType.STRING, "");
        for (String part : csv.split(",")) {
            if (!part.isBlank()) {
                tomb.affixes.add(part.trim().toLowerCase(Locale.ROOT));
            }
        }
        tombs.put(id, tomb);
        player.getInventory().setItemInMainHand(null);
        markDirty(); // 审计 #8：标脏交 5 秒异步冲刷，主线程埋剑不再同步整文件重写
        plugin.stats().countTomb(player.getUniqueId());
        if (plugin.achievements() != null) {
            plugin.achievements().check(player.getUniqueId(), "tomb");
        }

        player.playSound(player.getLocation(), Sound.BLOCK_GRAVEL_PLACE, 1.0f, 0.7f);
        Msg.prefix(player, "<gray>「<#ffd166>" + swordName + "</#ffd166>」已入葬剑冢，编号 <white>" + id
                + "</white>。全服可见，别人可以挑战复刻。");
        if (plugin.getConfig().getBoolean("tomb.broadcast", true)) {
            Bukkit.broadcast(Msg.mm(Msg.PREFIX + "<gray>" + player.getName() + " 把「<#ffd166>" + swordName
                    + "</#ffd166>」埋进了剑冢（<white>" + quality + "</white> · " + Msg.fmt(score, 1) + " 分）。"));
        }
    }

    /**
     * 强化碎剑时的留档（旧签名，等价于 {@link #recordBroken(Player, ItemStack, int)}）。
     *
     * <p>v2.5 并行开发时这里一度是另一套实现，两套写法会让「碎剑」条目不标记 broken，
     * 于是能被 {@link #take(Player, String)} 取回去。现在统一委托到 recordBroken，
     * 只保留签名给老调用方。</p>
     *
     * @param broken       碎掉的剑的副本
     * @param levelAtBreak 碎在 +几
     * @param swordName    剑名（记录里优先用物品自身的显示名）
     * @deprecated 用 {@link #recordBroken(Player, ItemStack, int)}
     */
    @Deprecated
    public void buryBroken(Player player, ItemStack broken, int levelAtBreak, String swordName) {
        recordBroken(player, broken, levelAtBreak);
    }

    /**
     * 碎剑留档：强化时剑胚碎掉的，物品照样消失，但这项记录留在剑冢里。
     *
     * <p>记录内容：锻造者（{@code Keys.FORGER}，读不到退玩家名）、碎在 +几、材料、品质、评分，
     * 外加剑的快照与配方（材料等级 / 词条 / 钢纹层数），方便后人查看当年这把剑长什么样。
     * 留档条目 <b>不能取回</b>，也不占用 {@code tomb.max-per-player} 的名额。</p>
     *
     * @param owner    碎剑的玩家（可空，用物品上的锻造者兜底）
     * @param item     碎掉的剑（调用方随后会把它从主手移除）
     * @param brokenAt 碎掉时的锻造等级
     * @return 剑冢编号；剑冢关闭或不是锻造品时返回 null
     */
    public String recordBroken(Player owner, ItemStack item, int brokenAt) {
        if (!enabled() || item == null || item.getType().isAir()) {
            return null;
        }
        if (!plugin.items().isForged(item)) {
            return null;
        }
        var meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        var pdc = meta.getPersistentDataContainer();
        String mat = pdc.getOrDefault(Keys.MATERIAL, PersistentDataType.STRING, "iron");
        String quality = pdc.getOrDefault(Keys.QUALITY, PersistentDataType.STRING, "COMMON");
        double score = pdc.getOrDefault(Keys.SCORE, PersistentDataType.DOUBLE, 0.0);
        String forger = pdc.getOrDefault(Keys.FORGER, PersistentDataType.STRING,
                owner == null ? "?" : owner.getName());

        String swordName = plugin.spirit() == null ? "" : plugin.spirit().nameOf(item);
        if (swordName == null || swordName.isBlank()) {
            swordName = Msg.plain(meta.displayName());
        }
        if (swordName == null || swordName.isBlank()) {
            swordName = "无名之刃";
        }

        ItemStack snapshot = item.clone();
        String id = newId();
        Tomb tomb = new Tomb(id,
                owner == null ? new UUID(0L, 0L) : owner.getUniqueId(),
                forger == null ? "?" : forger,
                swordName, System.currentTimeMillis(), mat, quality, score, snapshot);
        tomb.grade = pdc.getOrDefault(Keys.USED_GRADE, PersistentDataType.STRING, "COMMON");
        tomb.layers = pdc.getOrDefault(Keys.LAYERS, PersistentDataType.INTEGER, 0);
        String csv = pdc.getOrDefault(Keys.AFFIX_LIST, PersistentDataType.STRING, "");
        for (String part : csv.split(",")) {
            if (!part.isBlank()) {
                tomb.affixes.add(part.trim().toLowerCase(Locale.ROOT));
            }
        }
        tomb.broken = true;
        tomb.brokenAt = Math.max(0, brokenAt);
        tombs.put(id, tomb);
        markDirty(); // 审计 #8：标脏交异步冲刷
        return id;
    }

    private String newId() {
        for (int i = 0; i < 50; i++) {
            String id = String.format(Locale.ROOT, "%04X", random.nextInt(0xFFFF));
            if (!tombs.containsKey(id)) {
                return id;
            }
        }
        return String.valueOf(System.currentTimeMillis() % 100000);
    }

    // ------------------------------------------------------------ 取回

    public void take(Player player, String rawId) {
        Tomb t = get(rawId);
        if (t == null) {
            Msg.prefix(player, "<red>剑冢里没有编号 " + rawId + " 的剑。</red>");
            return;
        }
        if (!t.owner.equals(player.getUniqueId())) {
            Msg.prefix(player, "<red>这把剑不是你埋的。</red>");
            return;
        }
        if (t.broken) {
            Msg.prefix(player, "<red>「" + t.swordName + "」是碎剑留档（碎在 +" + t.brokenAt
                    + "），只剩个记录，取不回来了。</red>");
            return;
        }
        if (!ownerCanRetrieve()) {
            Msg.prefix(player, "<red>本服设置：埋下的剑不能再取回。</red>");
            return;
        }
        long fee = retrieveFee();
        if (fee > 0 && !plugin.stats().takeCoins(player.getUniqueId(), fee, CoinReason.TOMB_FEE)) {
            Msg.prefix(player, "<red>取回需要 <white>" + fee + "</white> 剑币，你不够。</red>");
            return;
        }
        tombs.remove(t.id);
        markDirty(); // 审计 #8：标脏交异步冲刷（取剑路径）
        giveItem(player, t.snapshot.clone());
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_CHAIN, 1.0f, 1.0f);
        Msg.prefix(player, "<gray>已从剑冢取回「<#ffd166>" + t.swordName + "</#ffd166>」。</gray>");
    }

    // ------------------------------------------------------------ 挑战

    /**
     * 召唤「残影」挑战（v2.7 主入口）：{@code /zhujian tomb challenge <编号>} 走这里。
     *
     * <p>委托给 {@link TombChallenge#challenge(Player, String)}：按原剑材料档位选生物、
     * 按强化等级与品质放大血量攻击，打赢按比例返还耗材。返回值就是已经发给玩家的
     * 那条提示（MiniMessage 原文，不带前缀），命令层不必再发一次。</p>
     *
     * @param player 挑战者
     * @param tombId 剑冢编号
     * @return MiniMessage 结果文本
     */
    public String challenge(Player player, String tombId) {
        return challengeEngine.challenge(player, tombId);
    }

    /**
     * 挑战复刻（旧玩法，v2.7 起不再由命令 / 界面挂接，保留给需要的一方自行调用）。
     *
     * <p>主手持自己锻的剑，与埋剑配方逐项比对得出复刻率，再付挑战费判定。
     * 成功率 = base-chance × 复刻率 + min(mastery-bonus-cap, 专精 × mastery-bonus-per-level)，
     * 限定在 [0.05, 0.95]。成功：发剑币 + 声望；失败：声望扣减。挑战费不退。主人不能挑战自己的剑。</p>
     */
    public void recipeChallenge(Player player, String rawId) {
        Tomb t = get(rawId);
        if (t == null) {
            Msg.prefix(player, "<red>剑冢里没有编号 " + rawId + " 的剑。</red>");
            return;
        }
        if (t.owner.equals(player.getUniqueId())) {
            Msg.prefix(player, "<red>不能挑战自己埋下的剑，取回它请用 <white>/zhujian tomb take " + t.id + "</white>。</red>");
            return;
        }
        // 挑战需要主手拿着自己锻出的剑来复刻，与埋剑配方逐项比对（先检查，再扣费）
        ItemStack attempt = player.getInventory().getItemInMainHand();
        if (attempt.getType().isAir() || !plugin.items().isForged(attempt)) {
            Msg.prefix(player, "<red>挑战要主手拿着一把你自己锻出的剑，用来照着配方复刻。</red>");
            return;
        }
        long fee = challengeFee();
        // 名剑谱（拓展第二档）：守冢人条目解锁后挑战费按折扣倍率收取
        if (plugin.spectrum() != null) fee = Math.round(fee * plugin.spectrum().tombFeeMultiplier(player.getUniqueId()));
        // ---- 残页折扣（剑冢秘境扩展，唯一插入点）----
        // 最多用 tomb.page-discount-max 张残页，每张抵 tomb.page-discount-per 的挑战费。
        // 这里只「数页折价」：真正的扣页延迟到下方扣费成功之后，避免页扣了费没收上。
        int pagesUsed = 0;
        if (fee > 0) {
            int cap = Math.max(0, plugin.settings().intOf("tomb.page-discount-max", 5));
            double per = plugin.settings().num("tomb.page-discount-per", 0.10);
            if (cap > 0 && per > 0) {
                int want = Math.min(cap, com.forgemaster.tombrun.TombRunService.countPages(plugin, player));
                if (want > 0) {
                    long discounted = Math.max(0, Math.round(fee * (1.0 - per * want)));
                    if (discounted < fee) {
                        fee = discounted;
                        pagesUsed = want;
                    }
                }
            }
        }
        if (fee > 0 && !plugin.stats().takeCoins(player.getUniqueId(), fee, CoinReason.TOMB_FEE)) {
            Msg.prefix(player, "<red>挑战需要 <white>" + fee + "</white> 剑币，你不够。</red>");
            return;
        }
        // 残页折扣：扣费成功后才真正消耗残页（按返回数播报，防竞态少扣）
        if (pagesUsed > 0) {
            int really = com.forgemaster.tombrun.TombRunService.consumePages(plugin, player, pagesUsed);
            if (really > 0) {
                Msg.prefix(player, "<gray>使用了 <white>" + really + "</white> 张<#d8b45a>残页</#d8b45a>，挑战费已折至 <white>"
                        + fee + "</white> 剑币。</gray>");
            }
        }
        // 配方比对得出复刻率（0~1），专精只做小幅加成
        double rate = recipeMatchRate(t, attempt);
        int level = plugin.stats().get(player.getUniqueId()).level;
        double masteryBonus = Math.min(
                plugin.settings().num("tomb.mastery-bonus-cap", 0.10),
                level * plugin.settings().num("tomb.mastery-bonus-per-level", 0.005));
        double chance = baseChance() * rate + masteryBonus;
        chance = Math.max(0.05, Math.min(0.95, chance));

        t.challenges++;
        boolean win = random.nextDouble() < chance;
        if (win) {
            t.wins++;
            long coins = challengeWinCoins();
            plugin.stats().addCoins(player.getUniqueId(), coins, CoinReason.TOMB);
            int rep = challengeWinRep();
            plugin.stats().addReputation(player.getUniqueId(), rep);
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.2f);
            Msg.prefix(player, "<green>复刻成功！</green> 「" + t.swordName + "」得到 <#ffd166>" + coins
                    + "</#ffd166> 剑币，声望 <white>+" + rep + "</white>。");
        } else {
            int loss = challengeLoseRep();
            plugin.stats().addReputation(player.getUniqueId(), -loss);
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.9f, 0.9f);
            Msg.prefix(player, "<red>复刻失败。</red> 「" + t.swordName + "」的火候你没摸透，挑战费不退，声望 <white>-"
                    + loss + "</white>。");
        }
        markDirty(); // 审计 #8：挑战计数变更标脏交异步冲刷
    }

    /**
     * 配方比对：挑战者用自己的锻造品（主手）去复刻埋剑配方，逐项比对，返回 0~1 的复刻率。
     *
     * <p>四项：材料 id（必须相同，否则直接 0）、材料等级、词条集合（忽略顺序，F1 近似）、钢纹层数（按差距衰减）。
     * 权重与衰减系数全部来自 config tomb.recipe.*。</p>
     */
    private double recipeMatchRate(Tomb t, ItemStack attempt) {
        double wMat = plugin.settings().num("tomb.recipe.weight-material", 0.30);
        double wGrade = plugin.settings().num("tomb.recipe.weight-grade", 0.15);
        double wAffix = plugin.settings().num("tomb.recipe.weight-affix", 0.35);
        double wLayer = plugin.settings().num("tomb.recipe.weight-layers", 0.20);
        double sum = wMat + wGrade + wAffix + wLayer;
        if (sum <= 0 || attempt == null || !plugin.items().isForged(attempt)) {
            return 0;
        }
        var pdc = attempt.getItemMeta().getPersistentDataContainer();

        // 1. 材料 id：不同直接判 0
        String mat = pdc.getOrDefault(Keys.MATERIAL, PersistentDataType.STRING, "");
        double matScore = mat.equalsIgnoreCase(t.materialId) ? 1.0 : 0.0;

        // 2. 材料等级：完全相同 1 分，差一档按 grade-step-penalty 扣
        String grade = pdc.getOrDefault(Keys.USED_GRADE, PersistentDataType.STRING, "COMMON");
        int gap = Math.abs(gradeRank(grade) - gradeRank(t.grade));
        double gradeScore = Math.max(0, 1.0 - gap * plugin.settings().num("tomb.recipe.grade-step-penalty", 0.5));

        // 3. 词条集合（忽略顺序）：F1 = 2·交集 / (埋剑数 + 挑战数)
        Set<String> mine = new HashSet<>();
        String csv = pdc.getOrDefault(Keys.AFFIX_LIST, PersistentDataType.STRING, "");
        for (String part : csv.split(",")) {
            if (!part.isBlank()) {
                mine.add(part.trim().toLowerCase(Locale.ROOT));
            }
        }
        double affixScore;
        if (t.affixes.isEmpty() && mine.isEmpty()) {
            affixScore = 1.0;
        } else {
            Set<String> inter = new HashSet<>(t.affixes);
            inter.retainAll(mine);
            affixScore = (2.0 * inter.size()) / (t.affixes.size() + mine.size());
        }

        // 4. 钢纹层数：差 0 为 1 分，每差一层扣 layer-step-penalty
        int layers = pdc.getOrDefault(Keys.LAYERS, PersistentDataType.INTEGER, 0);
        double layerScore = Math.max(0, 1.0
                - Math.abs(layers - t.layers) * plugin.settings().num("tomb.recipe.layer-step-penalty", 0.1));

        double rate = (matScore * wMat + gradeScore * wGrade + affixScore * wAffix + layerScore * wLayer) / sum;
        return Math.max(0, Math.min(1, rate));
    }

    private static int gradeRank(String g) {
        return switch (g == null ? "" : g.toUpperCase(Locale.ROOT)) {
            case "REFINED" -> 1;
            case "CELESTIAL" -> 2;
            default -> 0;
        };
    }

    private static int qualityIndex(String q) {
        return switch (q == null ? "" : q.toUpperCase(Locale.ROOT)) {
            case "ROUGH" -> 0;
            case "COMMON" -> 1;
            case "FINE" -> 2;
            case "EPIC" -> 3;
            case "LEGENDARY" -> 4;
            case "MYTHIC" -> 5;
            default -> 1;
        };
    }

    // ------------------------------------------------------------ 命令

    /** /zhujian tomb [list|bury|take <id>|challenge <id>|info <id>] */
    public void command(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.prefix(sender, "<red>只有玩家能使用剑冢。</red>");
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (sub) {
            case "list", "gui" -> player.openInventory(new TombGui(this).build().getInventory());
            case "bury", "埋" -> bury(player);
            case "take", "qu" -> {
                if (args.length < 3) {
                    Msg.prefix(player, "<gray>用法: /zhujian tomb take <编号></gray>");
                    return;
                }
                take(player, args[2]);
            }
            case "challenge", "tiaozhan" -> {
                if (args.length < 3) {
                    Msg.prefix(player, "<gray>用法: /zhujian tomb challenge <编号>（召唤这条记录的残影）</gray>");
                    return;
                }
                challenge(player, args[2]);
            }
            case "remove", "delete", "shandiao" -> {
                if (!player.hasPermission("forgemaster.admin")) {
                    Msg.prefix(player, "<red>只有管理员能销毁剑冢。</red>");
                    return;
                }
                if (args.length < 3) {
                    Msg.prefix(player, "<gray>用法: /zhujian tomb remove <编号></gray>");
                    return;
                }
                String id = args[2].toUpperCase(Locale.ROOT);
                if (!tombs.containsKey(id)) {
                    Msg.prefix(player, "<red>没有这个剑冢: " + id);
                    return;
                }
                adminRemove(id);
                Msg.prefix(player, "<green>已销毁剑冢 <white>" + id + "</white>。");
            }
            case "info" -> {
                if (args.length < 3) {
                    Msg.prefix(player, "<gray>用法: /zhujian tomb info <编号></gray>");
                    return;
                }
                info(player, args[2]);
            }
            default -> Msg.prefix(player, "<gray>用法: /zhujian tomb [list|bury|take <id>|challenge <id>|info <id>]"
                    + " <dark_gray>（challenge = 召唤残影）</dark_gray></gray>");
        }
    }

    public void info(Player player, String rawId) {
        Tomb t = get(rawId);
        if (t == null) {
            Msg.prefix(player, "<red>剑冢里没有编号 " + rawId + " 的剑。</red>");
            return;
        }
        Msg.send(player, "<dark_gray>──── <gradient:#8d8d8d:#4e7fff><bold>剑冢 · " + t.id + "</bold></gradient> <dark_gray>────");
        Msg.send(player, "<gray>剑名: <#ffd166>" + t.swordName + "</#ffd166> <dark_gray>· <gray>主人: <white>" + t.ownerName);
        Msg.send(player, "<gray>剑胚: <white>" + t.materialId + " <dark_gray>· <gray>品质: <white>" + t.quality
                + " <dark_gray>· <gray>评分: <white>" + Msg.fmt(t.score, 1));
        Msg.send(player, "<gray>挑战 <white>" + t.challenges + "</white> 次 · 斩影 <white>" + t.wins + "</white> 次");
        if (t.broken) {
            Msg.send(player, "<gray>状态: <red>碎剑留档</red> <dark_gray>· 碎在 <white>+" + t.brokenAt
                    + "</white> · 只作纪念，不能取回</dark_gray>");
        } else {
            Msg.send(player, "<gray>状态: <#4ee36b>完整</#4ee36b> <dark_gray>· 埋入 "
                    + new java.text.SimpleDateFormat("MM-dd HH:mm").format(t.buriedAt) + "</dark_gray>");
        }
        // 残影预估（v2.7）：让挑战者开打前先掂量一下
        String remnant = challengeEngine.previewLine(t);
        if (!remnant.isBlank()) {
            Msg.send(player, remnant);
            Msg.send(player, "<dark_gray>挑战请用 <white>/zhujian tomb challenge " + t.id + "</white>");
        }
    }

    /** 管理员在线删除（用于清理） */
    public void adminRemove(String rawId) {
        if (tombs.remove(rawId.toUpperCase(Locale.ROOT)) != null) {
            markDirty(); // 审计 #8：标脏交异步冲刷
        }
    }

    private void giveItem(Player player, ItemStack item) {
        Map<Integer, ItemStack> left = player.getInventory().addItem(item);
        for (ItemStack rest : left.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }
}
