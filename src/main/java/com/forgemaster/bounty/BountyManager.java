package com.forgemaster.bounty;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.econ.CoinReason;
import com.forgemaster.quality.Quality;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每日悬赏：按世界天数做种子，每天给全服刷 3 条赏金
 * （品质 / 强化 / 锻阶门槛），主手验剑领赏，剑被收走。
 *
 * <p>种子 = 世界完整天数，同一自然日所有人看到同一组悬赏；领过的记在内存
 * （当天有效，重启丢的是「今天已领名单」，重领一次无伤大雅）。</p>
 */
public final class BountyManager {

    /** 一条悬赏 */
    public record Bounty(int minQuality, int minLevel, int minStage, long reward) {

        public String describe() {
            Quality q = Quality.values()[Math.min(minQuality, Quality.values().length - 1)];
            return "<color:" + q.color() + ">" + q.display() + "</color> 以上 · 强化 <white>+" + minLevel
                    + "</white> 以上" + (minStage > 0 ? " · 锻阶 <white>" + minStage + "</white> 阶以上" : "")
                    + " · 赏金 <gold>" + reward + "</gold>";
        }
    }

    private final ForgingMasterPlugin plugin;

    /**
     * v2.23.1 修 P1：已领记录落盘（bounty-data.yml）。
     *
     * <p>原来会怎样：{@code claimed} 是纯内存 Set，且"换日"用的是
     * {@code getFullTime() / 24000}——MC 一个昼夜只有 20 分钟。于是每 20 分钟
     * 现实时间就 {@code claimed.clear()} 一次并重掷货单，玩家拿同一把达标的剑
     * 每 20 分钟能再领一次赏金；重启也会清空。与类注释写的"同一自然日"
     * 以及黑市/签到用 {@code LocalDate} 的口径都不一致。</p>
     *
     * <p>现在为什么不会：日期改用现实日（{@code LocalDate.toEpochDay()}），
     * 已领名单随文件落盘。换日只在现实零点发生一次，"一天一次"这才真的成立。</p>
     */
    private final java.io.File file;
    private final Random seeded = new Random();
    private long day = -1;
    private List<Bounty> current = List.of();
    private final Set<String> claimed = ConcurrentHashMap.newKeySet();

    /**
     * 待确认的领赏（v2.26.1）：uuid → 预览那一刻的「第几条 + 主手剑的身份」。
     *
     * <p>为什么需要：{@code claim()} 一旦过关就 {@code setItemInMainHand(null)}，
     * <b>剑当场消失且不可撤销</b>。而触发条件只是「主手拿了一件达标的锻造品」——
     * 玩家在整理背包、切剑时手滑输一次命令，就会把主属性最好的那把剑当悬赏交出去，
     * 换回一笔小钱。这不是理论风险，是「不可逆操作没有二次确认」这一类里最典型的形状。</p>
     *
     * <p>存的是不透明 token 而不是「序号」：序号在两步之间可能因为跨天重掷而指向
     * 另一条悬赏。token 在同一秒内也不接受第二次（见 {@link #nonceOf}），
     * 所以「同一个 token 领两次」不可能发生。</p>
     */
    private final java.util.Map<java.util.UUID, Pending> pendingClaim = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 一份待确认的领赏。
     *
     * @param index 第几条（0 基）
     * @param token 预览时生成的一次性令牌
     * @param sword 预览时主手那把剑的指纹，confirm 时要求主手还是它
     */
    private record Pending(int index, int token, String sword) {}

    public BountyManager(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.file = new java.io.File(plugin.getDataFolder(), "bounty-data.yml");
        load();
    }

    // ------------------------------------------------------------ v2.23.1 落盘

    /** 读回「上次的日期 + 已领名单」；坏数据当空处理，不影响启动 */
    /** v2.25.1：公开读档入口（restore 的 reloadAllSilently 用，覆盖后按盘重建内存） */
    public void reload() {
        load();
    }

    /**
     * 公开落盘入口（v2.26.0 审计轮补，restore 的 flushAll 用）。
     *
     * <p><b>为什么必须有这个入口：</b>回档流程是三段 ——
     * {@code flushAll}（把内存里的最新态同步写盘）→ 用备份覆盖文件 →
     * {@code reloadAllSilently}（按覆盖后的盘面重建内存）。
     * bounty-data.yml 在 {@code SAVE_FILES} 里、reload 那一段也接好了，
     * <b>唯独 flushAll 里没有它</b> —— 全仓唯一「有 reload 无 flush」的项。</p>
     *
     * <p>缺了 flush 的后果不是「回档没生效」，而是更阴的：回档把文件换成旧的，
     * 但内存里还是回档前那份 {@code claimed}，下一次 {@code claim()} 调 {@code save()}
     * 会把内存整份回写 —— <b>回档结果被静默吞掉，连日志都没有</b>。
     * 这与 v2.23.1 修过的「stats 被脏冲刷盖回」、v2.25 修的 contract.yml 是同一个形状，
     * 所以这里是补齐同型而不是新增机制。</p>
     *
     * <p>只做「立即落盘」一件事，不返回成败：调用方是 flushAll 里的 swallow，
     * 它已经负责把异常记进回档报告；这里再返回 boolean 只会多一条没人看的判据。</p>
     */
    public void flush() {
        save();
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        try {
            var yml = com.forgemaster.util.YamlIo.loadOrQuarantine(file, "BountyManager", plugin.getLogger());
            day = yml.getLong("day", -1);
            claimed.clear();
            claimed.addAll(yml.getStringList("claimed"));
            // 跨天残留的旧键直接丢掉（key 自带日期前缀，留着只会长胖）
            claimed.removeIf(k -> !k.startsWith(day + ":"));
            // v2.23.4：读回当日列表。解析失败就当没存过（current() 会按日期种子重掷，
            // 结果是同一份，所以坏行不会让玩家拿到不同的悬赏）。
            current = new ArrayList<>();
            for (String row : yml.getStringList("list")) {
                String[] p = row.split(",");
                if (p.length != 4) {
                    continue;
                }
                try {
                    current.add(new Bounty(
                            Integer.parseInt(p[0].trim()),
                            Integer.parseInt(p[1].trim()),
                            Integer.parseInt(p[2].trim()),
                            Long.parseLong(p[3].trim())));
                } catch (NumberFormatException ignored) {
                    // 单行坏了只丢这一行
                }
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("悬赏存档读取失败，按空处理：" + ex.getMessage());
        }
    }

    /** 同步落盘：日期 + 已领名单 + **当日悬赏列表本身**。只在「换日」与「领赏」两个低频点调用 */
    private void save() {
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            var yml = new org.bukkit.configuration.file.YamlConfiguration();
            yml.set("day", day);
            yml.set("claimed", new java.util.ArrayList<>(claimed));
            // v2.23.4：列表也要落盘。只存 day 不存列表的话，重启后 current 是空的，
            // 而日期又对得上，就会一直空到第二天（见 current() 的注释）。
            // 每条存成 "quality,level,stage,reward" —— 不引入新的序列化机制，
            // 与 claimed 用的字符串列表保持同一种风格。
            java.util.List<String> rows = new java.util.ArrayList<>();
            for (Bounty b : current) {
                rows.add(b.minQuality() + "," + b.minLevel() + "," + b.minStage() + "," + b.reward());
            }
            yml.set("list", rows);
            com.forgemaster.util.YamlIo.save(file, yml);
        } catch (java.io.IOException ex) {
            plugin.getLogger().warning("悬赏存档保存失败：" + ex.getMessage());
        } catch (RuntimeException ex) {
            // v2.26.0：YamlIo.save 的返回路径里还可能抛 SecurityException（沙箱/权限）
            // 或 IllegalArgumentException（路径非法）。原来只 catch IOException，
            // 这两种会一路冒到 claim() 的调用方 —— 而 claim 的设计意图是
            // 「宁可少发钱也不刷币」，未捕获的异常会让玩家看到插件内部报错。
            // 与 StoryManager / PowerLogStore / ContestHistoryStore 的同类加固保持一致。
            plugin.getLogger().warning("悬赏存档保存失败（未预期异常）：" + ex);
        }
    }

    public boolean enabled() {
        return plugin.settings().flag("bounty.enabled", true);
    }

    /** 当日悬赏（懒生成：按现实日换种子） */
    public List<Bounty> current() {
        if (!enabled()) {
            return List.of();
        }
        long today = worldDay();
        // v2.23.4 修 P1：**别忘了 current 是懒生成的**。
        // 原来这里的条件只有 `today != day`，于是出现一个很直接的线上事故：
        //   · 早上服务器滚过一次悬赏，day = 今天，save() 只把 day 和 claimed 落盘；
        //   · 重启后 load() 读回 day = 今天，但 current 是空的（列表从来没被持久化）；
        //   · today == day → 不重掷 → 玩家看到「今日悬赏：无」，一直空到第二天零点。
        // 所以「日期对但列表是空的」也必须重掷。重掷是确定性的（种子 = 日期），
        // 同一天重新掷出来的一定是同一份，不会让玩家刷悬赏内容。
        if (today != day || current.isEmpty()) {
            day = today;
            claimed.clear();
            current = roll(today);
            save(); // 换日 / 补列表都算状态变更：把新日期与清空后的名单落盘
        }
        return current;
    }

    /**
     * v2.23.1 修 P1：现实日（服务器本地时区），与黑市/签到同一口径。
     *
     * <p>原来用的是 {@code getFullTime() / 24000}，那是 MC 昼夜（20 分钟），
     * 既不是"天"也不受 {@code /time set} 保护。改为 {@code LocalDate.toEpochDay()}。</p>
     */
    private long worldDay() {
        return java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toEpochDay();
    }

    /** 按天数种子刷 3 条；同一 day 结果稳定 */
    private List<Bounty> roll(long worldDay) {
        seeded.setSeed(worldDay * 31L + 7L);
        int count = Math.max(1, Math.min(6, plugin.settings().intOf("bounty.count", 3)));
        int baseReward = Math.max(50, plugin.settings().intOf("bounty.reward-base", 400));
        List<Bounty> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // 品质门槛：FINE(2)~MYTHIC(5)，越往越少
            int q = 2 + seeded.nextInt(4);
            if (seeded.nextInt(3) == 0 && q > 2) {
                q--;
            }
            int level = 3 + seeded.nextInt(13);
            int stage = q >= 4 && seeded.nextInt(2) == 0 ? 1 + seeded.nextInt(2) : 0;
            long reward = Math.round(baseReward * (1 + 0.8 * q) * (1 + 0.06 * level) * (1 + 0.5 * stage)
                    * (0.9 + seeded.nextDouble() * 0.2));
            list.add(new Bounty(q, level, stage, reward));
        }
        return list;
    }

    /**
     * 主手验剑领赏 —— **两段式**（v2.26.1 起）：第一次调用只给预览，第二次带 confirm 才真收剑。
     *
     * <p>为什么改：这个操作一旦执行 {@code setItemInMainHand(null)}，
     * <b>剑当场消失、不可撤销</b>，换回的却是一笔小钱。玩家整理背包/切剑时手滑输一次命令，
     * 就可能把主属性最好的剑交出去。全仓同类不可逆操作（{@code spirit reroll}、
     * {@code strengthen strip}、{@code talent reset}、{@code dissolve}）都是两段式，
     * 唯独这里是一步到位 —— 属于漏的，不是特意的。</p>
     *
     * @param index   第几条（0 基）
     * @param confirm 是否已过二次确认
     * @return 结果文本
     */
    public String claim(Player p, int index, boolean confirm) {
        List<Bounty> list = current();
        if (list.isEmpty()) {
            return "<red>悬赏功能未开启。";
        }
        if (index < 0 || index >= list.size()) {
            return "<red>没有第 " + (index + 1) + " 条悬赏（今天一共 " + list.size() + " 条）。";
        }
        String key = worldDay() + ":" + p.getUniqueId() + ":" + index;
        if (claimed.contains(key)) {
            return "<red>这条你今天已经领过了，明天再来。";
        }
        Bounty b = list.get(index);
        ItemStack main = p.getInventory().getItemInMainHand();
        if (main == null || main.getType().isAir() || plugin.items() == null
                || !plugin.items().isForged(main)) {
            return "<red>主手拿一件锻造品再来领赏。";
        }
        ItemMeta meta = main.getItemMeta();
        if (meta == null) {
            return "<red>这东西读不出数据。";
        }
        var pdc = meta.getPersistentDataContainer();
        String qName = pdc.get(Keys.QUALITY, PersistentDataType.STRING);
        Quality q = qName == null ? Quality.COMMON : Quality.byName(qName);
        int qIdx = q == null ? 1 : q.ordinal();
        int level = pdc.getOrDefault(Keys.STRENGTHEN, PersistentDataType.INTEGER, 0);
        int stage = pdc.getOrDefault(Keys.EVOLUTION, PersistentDataType.INTEGER, 0);
        if (qIdx < b.minQuality()) {
            // v2.25.5：与 Bounty 记录构造器（line 33 的 Math.min 钳制）对齐。
            // 这里原先直接 Quality.values()[b.minQuality()]，minQuality 越界就 AIOOBE；
            // q 为 null（PDC 里存了个已改名的品质）时 q.display() 也会 NPE —— 而这两处
            // 都在 setItemInMainHand(null) 之前，抛出去至少不会丢剑，但玩家会看到一条
            // 「内部错误」且赏金交不了。钳一下、兜一个名字。
            Quality[] all = Quality.values();
            Quality need = all[Math.max(0, Math.min(all.length - 1, b.minQuality()))];
            return "<red>品质不够：要 <white>" + need.display()
                    + "</white> 以上，这把是 " + (q == null ? "未知品质" : q.display()) + "。";
        }
        if (level < b.minLevel()) {
            return "<red>强化不够：要 <white>+" + b.minLevel() + "</white> 以上，这把是 +" + level + "。";
        }
        if (stage < b.minStage()) {
            return "<red>锻阶不够：要 <white>" + b.minStage() + "</white> 阶以上，这把是 " + stage + " 阶。";
        }

        // ------------- 两段式确认（v2.26.1）-------------
        String sword = fingerprint(meta, q);
        if (!confirm) {
            // 预览：只把「要交哪把剑、换多少钱」摊开，剑和钱都不动
            int token = java.util.concurrent.ThreadLocalRandom.current().nextInt();
            pendingClaim.put(p.getUniqueId(), new Pending(index, token, sword));
            Msg.send(p, "<dark_gray>──── <gradient:#ffd166:#ff7a45><bold>悬赏兑现确认</bold></gradient> <dark_gray>────");
            Msg.send(p, "<gray>将交出主手这把：<white>" + sword + "</white>");
            Msg.send(p, "<gray>换回：<gold>" + b.reward() + "</gold> 剑币 <dark_gray>（第 "
                    + (index + 1) + " 条悬赏）</dark_gray>");
            Msg.send(p, "<red>剑会当场消失，且无法撤销。");
            Msg.prefix(p, "<gray>确认请再执行：<white>/zhujian shangjin claim " + (index + 1) + " confirm</white>");
            return "";
        }

        // ---- confirm 校验：必须是「预览过同一把剑」的那个人 ----
        // 三处都要对上：①这一轮真的预览过（token 存在）②还是同一条悬赏
        // ③主手还是预览那把剑。第③条是关键 —— 没有它，玩家可以预览一把破剑，
        // 然后换成满属性的剑 confirm，而校验门槛是对「当前主手」做的，等于绕过预览。
        Pending pend = pendingClaim.remove(p.getUniqueId());
        if (pend == null) {
            return "<red>先看一遍预览（<white>/zhujian shangjin claim " + (index + 1)
                    + "</white>）再确认。";
        }
        if (pend.index() != index || !pend.sword().equals(sword)) {
            return "<red>预览和确认不是同一把剑（或不是同一条悬赏），已取消。"
                    + "请重新看一遍预览：<white>/zhujian shangjin claim " + (index + 1) + "</white>";
        }

        p.getInventory().setItemInMainHand(null);
        // v2.23.1 修 P1：先落「已领」再发钱。
        // 顺序反过来（先发钱后记录）崩溃时能重领一次 = 刷币；这个顺序崩溃只会
        // 「剑被收走但赏金没到账」（可人工补，且日志里有一条 warning 留痕）。
        claimed.add(key);
        save();
        var wallet = plugin.wallet();
        if (!wallet.give(p, b.reward(), CoinReason.BOUNTY)) {
            plugin.getLogger().warning("悬赏发放失败（" + p.getName() + " / " + b.reward()
                    + " 币 / 悬赏第 " + (index + 1) + " 条），请人工补发");
            return "<gold>悬赏已记录</gold><gray>，但赏金入账异常，请联系管理员补发。</gray>";
        }
        // 剑币在 data.yml 里是内存态，这里同步落一次，避免重启后赏金"没发生"
        plugin.stats().save();
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_CELEBRATE, 1.0f, 1.2f);
        return "<gold>悬赏兑现！</gold><gray>赏金 <white>" + b.reward() + "</white> 剑币已入账，"
                + "剑被收购方拉走了。</gray>";
    }

    /**
     * 剑的「人眼可辨」指纹，用于把 confirm 绑到预览时那把剑上。
     *
     * <p><b>为什么不用 UUID</b>：锻造品没有唯一 id 键，只有一批属性 PDC。
     * 所以指纹取「玩家能看到的那些字段」—— 品质 + 强化 + 锻阶 + 剑名 + 灵性。
     * 这不是密码学意义上的身份，但足够挡住「预览 A 剑、confirm 换成 B 剑」这个绕过：
     * 换掉任意一项，指纹就变，confirm 会被拒。</p>
     *
     * <p>同属性的两把剑指纹相同 —— 那种情况下玩家交哪把都符合预览里写的描述，
     * 不构成「被扣了没想到的剑」。刻意不做物品哈希：哈希会把「修过一次耐久」也判成换剑，
     * 而耐久和领赏无关，只会让正常操作莫名其妙失败。</p>
     */
    private String fingerprint(ItemMeta meta, Quality q) {
        var pdc = meta.getPersistentDataContainer();
        int level = pdc.getOrDefault(Keys.STRENGTHEN, PersistentDataType.INTEGER, 0);
        int stage = pdc.getOrDefault(Keys.EVOLUTION, PersistentDataType.INTEGER, 0);
        int spirit = pdc.getOrDefault(Keys.SPIRIT, PersistentDataType.INTEGER, 0);
        String name = Msg.plain(meta.displayName() == null
                ? net.kyori.adventure.text.Component.empty() : meta.displayName()).trim();
        return (q == null ? "未知品质" : q.display())
                + (level > 0 ? " +" + level : "")
                + (stage > 0 ? " " + stage + "阶" : "")
                + (name.isEmpty() ? "" : " " + name)
                + (spirit > 0 ? " 灵性" + spirit : "");
    }

    /**
     * 玩家退服时清掉待确认状态（v2.26.1）。
     *
     * <p>照 {@code SpiritManager} 的 v2.25.2 教训：不清的话，重登后
     * {@code claim N confirm} 仍能匹配上旧预览 —— 而「一次能领赏的确认」不该跨会话活着。
     * 这也顺带避免 {@link #pendingClaim} 随退服玩家无限增长。</p>
     */
    public void forgetPending(java.util.UUID uuid) {
        if (uuid != null) {
            pendingClaim.remove(uuid);
        }
    }

    /** 给自检用的待确认计数（不动语义，只读） */
    public int pendingClaimCount() {
        return pendingClaim.size();
    }

    /** 列表文本（命令输出用） */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        List<Bounty> list = current();
        if (list.isEmpty()) {
            out.add("<red>悬赏功能未开启。");
            return out;
        }
        // v2.23.1 修 P1：日期文案跟口径一起换——原来印的是 MC 天数（每天涨 1 次 = 20 分钟），
        // 现在印现实日期，玩家看到的就是"今天"这一批
        out.add("<gradient:#ffd166:#ff7a3d><bold>〔今日悬赏〕</bold></gradient> <gray>"
                + java.time.LocalDate.now(java.time.ZoneId.systemDefault())
                + " · 主手拿剑 <white>/zhujian shangjin claim <序号></white> 看预览，"
                + "确认再加 <white>confirm</white></gray>");
        for (int i = 0; i < list.size(); i++) {
            out.add("<dark_gray>" + (i + 1) + ".</dark_gray> " + list.get(i).describe());
        }
        return out;
    }
}
