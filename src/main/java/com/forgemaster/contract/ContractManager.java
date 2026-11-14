package com.forgemaster.contract;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.econ.CoinReason;
import com.forgemaster.form.FormRegistry;
import com.forgemaster.quality.Quality;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * 龙税周目（ForgeTax 核心循环的移植）：每个 MC 游戏日黄昏开贡品窗口，
 * 午夜巨龙验收——每人独立契约，税额逐日递增；交满龙把剑吃掉并发圣物三选一，
 * 连续交不上破契约重开。估值 / 税额 / 时间尺度全部可配，且走
 * {@link RunClock}/{@link TributePolicy}/{@link FailurePolicy} 三个暂定接口，
 * 以后换玩法只换实现不动调用方。
 */
public final class ContractManager {

    private final ForgingMasterPlugin plugin;
    private final ContractStore store;
    private final RelicPool relics;
    private final Random random = new Random();
    private BukkitTask task;

    public ContractManager(ForgingMasterPlugin plugin, ContractStore store, RelicPool relics) {
        this.plugin = plugin;
        this.store = store;
        this.relics = relics;
        bindInterfaces();
    }

    // ------------------------------------------------------------ 生命周期

    public void start() {
        stop();
        // 审计 #7：落盘改 dirty + 30 秒异步冲刷，冲刷任务不依赖契约开关，常挂无害
        store.startAutoFlush();
        if (!active()) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickClock, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        store.stop(); // 关服 / reload 强制冲刷一次（内部含 save）
    }

    public boolean active() {
        return plugin.settings().flag("contract.enabled", false);
    }

    public ContractStore store() {
        return store;
    }

    public RelicPool relics() {
        return relics;
    }

    // ------------------------------------------------------------ 暂定接口绑定

    private void bindInterfaces() {
        // config 开着才 bind；关着保持 no-op（ContractRegistry 的语义）
        com.forgemaster.contract.ContractRegistry reg = plugin.contract();
        if (reg != null) {
            reg.bind(new McDayClock(), new ManagerTribute(), new ManagerFailure());
        }
    }

    /** MC 游戏日时钟：一个 MC 日 = 一天，午夜结算 */
    private final class McDayClock implements RunClock {
        @Override
        public int day() {
            return 1; // 全服时钟没有「个人天数」概念；个人天数看 ContractStore.Run.day
        }

        @Override
        public long ticksRemaining() {
            World w = world();
            if (w == null) {
                return -1;
            }
            long tod = w.getFullTime() % 24000L;
            long settle = settleTime();
            return tod <= settle ? settle - tod : 24000L - tod + settle;
        }

        @Override
        public boolean isTributeDue() {
            return windowOpen();
        }
    }

    /** 估值 / 税额策略：走 config 公式 */
    private final class ManagerTribute implements TributePolicy {
        @Override
        public long requiredValue(int day, UUID playerId) {
            return requirement(day, 0, relicBonus(playerId));
        }

        @Override
        public long appraise(int day, List<ItemStack> items) {
            long total = 0;
            for (ItemStack item : items) {
                total += ContractManager.this.appraise(item);
            }
            return total;
        }
    }

    /** 破契约惩罚：宽限用尽后的结算文本 */
    private final class ManagerFailure implements FailurePolicy {
        @Override
        public String onMissed(UUID playerId, int day, long shortfall, int missedStreak) {
            return breakContractText(playerId, shortfall);
        }
    }

    // ------------------------------------------------------------ 时钟

    private World world() {
        String name = plugin.settings().str("contract.world", "");
        World w = name == null || name.isBlank() ? null : Bukkit.getWorld(name);
        return w != null ? w : Bukkit.getWorlds().get(0);
    }

    /** 黄昏开贡品窗口（刻） */
    public long windowTime() {
        return Math.max(0, Math.min(23000, plugin.settings().intOf("contract.window-open", 13000)));
    }

    /** 午夜结算（刻） */
    public long settleTime() {
        return Math.max(0, Math.min(23000, plugin.settings().intOf("contract.settle-time", 18000)));
    }

    private boolean windowOpen() {
        World w = world();
        if (w == null) {
            return false;
        }
        long tod = w.getFullTime() % 24000L;
        return tod >= windowTime() && tod < settleTime();
    }

    private void tickClock() {
        if (!active()) {
            return;
        }
        World w = world();
        if (w == null) {
            return;
        }
        // v2.25.2（4 并发查错）：这是每 20 tick 的主线程重复任务，抛一次异常就被
        // Bukkit 永久取消 —— 之后龙税窗口提示、每日结算全部停摆，只能重启救。
        try {
            long fullTime = w.getFullTime();
            long fullDay = fullTime / 24000L;
            long tod = fullTime % 24000L;
            if (tod >= settleTime() && store.lastSettledFullDay() < fullDay) {
                // v2.25.2：游标必须在结算<b>成功之后</b>推进。原来是先写游标再结算，
                // settleDay 中途抛异常 → 这一天永久跳过（玩家的超额结转 / 圣物三选一全没了），
                // 而且游标已经落盘，重启也补不回来。
                settleDay();
                store.lastSettledFullDay(fullDay);
            } else if (tod == windowTime()) {
                // 黄昏：提示所有在线在契玩家贡品窗口开了
                for (Player p : Bukkit.getOnlinePlayers()) {
                    ContractStore.Run r = store.run(p.getUniqueId());
                    if (r != null && r.joined) {
                        Msg.action(p, "<gradient:#b06bff:#4dc3ff>「龙息已在山脊上盘旋——今日贡品还差 "
                                + Math.max(0, requirement(r.day, r.missed, relicBonus(p.getUniqueId())) - r.pool)
                                + " 估值」</gradient>");
                    }
                }
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("龙税时钟跳拍（跳过本轮，下一轮重试）：" + ex);
        }
    }

    // ------------------------------------------------------------ 加入 / 退出

    // v2.26.2 修复：原来已签守卫是 `r.joined && r.day > 1`，周目第 1 天重复 join 会
    // 绕过判定再发一轮圣物三选一（offer 只排除已持有），刷满圣物池。改为只要已签就拒绝。
    //
    // v2.26.3 修复（用户实测反馈：`/zhujian contract join` 多敲几次圣物就满了）：
    // 上面那条只堵住了「已签再 join」，**没堵 quit→join**，而且还有第二道口子：
    // `store.ensure()` 在找不到档案时会**直接建一个 joined = true 的 Run**，
    // 于是第一次 join 就误判成「已签」、什么都不发（功能像是坏的），
    // 而玩家一旦 quit（remove 掉档案）再 join，ensure 又建一份新档案、
    // owned 列表清空 → offer 从 12 个圣物里重新抽 3 个。
    // 反复 quit→join 等于无限重掷，定向钓凤凰灰这种稀有圣物，几轮就能拿满。
    //
    // 正确口径有三条，必须一起成立：
    //   ① 用 `store.run()`（只读）判在契，绝不用 `ensure()` 判 —— ensure 会带副作用建档；
    //   ② quit 不再是「清档」，而是「标为已退出」，让档案留在盘上；
    //   ③ join 时若档案标记为已退出，**继承原有圣物池**，不重新发三选一。
    // 这样「退契」只是退出循环（圣物失效），不是「清空重抽」。
    public String join(Player p) {
        if (!active()) {
            return "<red>龙税契约未开启。";
        }
        UUID id = p.getUniqueId();
        ContractStore.Run existing = store.run(id);
        if (existing != null && existing.joined) {
            return "<gray>你已经在契约中了——今天要交 <white>"
                    + requirement(existing.day, existing.missed, relicBonus(id))
                    + "</white> 估值。";
        }
        // 已退出过的旧档：原样复活，圣物与周目进度都保留，不再发三选一
        if (existing != null && !existing.joined) {
            existing.joined = true;
            store.markDirty();
            Msg.prefix(p, "<gradient:#ff3d6e:#b06bff><bold>巨龙重新接纳了你——</bold></gradient>");
            return "<gray>契约已续。今晚龙要 <white>"
                    + requirement(existing.day, existing.missed, relicBonus(id))
                    + "</white> 估值。圣物依旧在你手上。";
        }
        ContractStore.Run r = store.ensure(id);
        r.joined = true;
        store.markDirty(); // 审计 #7：改动即存改为标脏，30 秒异步冲刷
        // 只有**首次**签约才发开局三选一（上面两条分支已把「重签」拦掉）
        offerRelics(id);
        Msg.prefix(p, "<gradient:#ff3d6e:#b06bff><bold>巨龙的低语在你耳边响起——</bold></gradient>");
        return "<gray>契约已签。今晚龙要 <white>" + requirement(r.day, 0, relicBonus(id))
                + "</white> 估值，黄昏后 <white>/zhujian contract</white> 打开贡品窗口交剑。";
    }

    /**
     * 退出契约。
     *
     * <p>v2.26.3：**不再 remove 档案**。原来清档会让 {@link #join} 认不出「这个人来过」，
     * 重新签约时圣物池从空开始、又发一轮三选一 —— 反复退出重进等于免费重掷稀有圣物。
     * 现在只翻 {@code joined = false}：圣物按 {@link #relicBonus} 的 `!r.joined` 分支失效，
     * 但记录留在盘上，重签时继承，重掷的口子就没了。</p>
     */
    public String quit(Player p) {
        ContractStore.Run r = store.run(p.getUniqueId());
        if (r == null || !r.joined) {
            return "<red>你还没签契约。";
        }
        r.joined = false;
        // 未领的三选一同时作废：否则退出后还能 pick，等于白拿一个圣物
        r.pickPending = "";
        store.markDirty();
        return "<gray>契约撕毁。巨龙记住了这件事——圣物在你手上只能沉睡，"
                + "重新签约可以唤回它们（进度不会重置）。";
    }

    /** ForgeManager 结算时静默拉入（auto-join 开着才生效） */
    public void autoJoin(UUID playerId) {
        if (active() && plugin.settings().flag("contract.auto-join", true)
                && store.run(playerId) == null) {
            ContractStore.Run r = store.ensure(playerId);
            offerRelics(playerId);
            Player p = Bukkit.getPlayer(playerId);
            if (p != null) {
                Msg.prefix(p, "<gray>锻造了第一把剑，巨龙替你把契约签了。"
                        + "<white>/zhujian contract</white> 查看贡品要求。</gray>");
            }
        }
    }

    public boolean joined(UUID id) {
        ContractStore.Run r = store.run(id);
        return r != null && r.joined;
    }

    // ------------------------------------------------------------ 估值

    /**
     * 一把剑的龙税估值：品质基数 × 强化 × 锻阶乘区 × 剑型 × 圣物。
     * 只读 PDC，不需要玩家在线。
     */
    public long appraise(ItemStack item) {
        if (item == null || item.getType().isAir() || plugin.items() == null
                || !plugin.items().isForged(item)) {
            return 0;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return 0;
        }
        var pdc = meta.getPersistentDataContainer();
        String qName = pdc.get(Keys.QUALITY, PersistentDataType.STRING);
        Quality q = qName == null ? Quality.COMMON : Quality.byName(qName);
        int qIdx = q == null ? 1 : q.ordinal();
        List<Double> bases = plugin.settings().numList("contract.value.base-per-quality",
                java.util.List.of(40.0, 80.0, 160.0, 320.0, 640.0, 1280.0));
        double base = 80.0;
        if (bases.size() == 6) {
            base = bases.get(qIdx);
        }
        int level = pdc.getOrDefault(Keys.STRENGTHEN, PersistentDataType.INTEGER, 0);
        double perLevel = plugin.settings().num("contract.value.per-strengthen-level", 0.15);
        double v = base * (1.0 + perLevel * level);

        int stage = pdc.getOrDefault(Keys.EVOLUTION, PersistentDataType.INTEGER, 0);
        if (stage > 0 && plugin.evolutions() != null) {
            double evoMult = plugin.evolutions().registry().cumulativeMult(stage);
            double weight = plugin.settings().num("contract.value.stage-weight", 1.0);
            v *= 1.0 + (evoMult - 1.0) * weight;
        }
        String formId = pdc.get(Keys.FORM, PersistentDataType.STRING);
        FormRegistry forms = plugin.forms();
        if (forms != null && formId != null) {
            var form = forms.get(formId);
            if (form != null) {
                v *= form.valueMult();
            }
        }
        return Math.max(1, Math.round(v));
    }

    /** 第 day 天的税额：基数 × 递增^（day-1） × 欠息 × 圣物折扣 */
    public long requirement(int day, int missed, RelicBonus rb) {
        double base = plugin.settings().num("contract.tax.base", 200);
        double growth = Math.max(1.0, plugin.settings().num("contract.tax.growth", 1.30));
        double missPenalty = Math.max(0, plugin.settings().num("contract.tax.miss-penalty", 0.30));
        double v = base * Math.pow(growth, Math.max(0, day - 1)) * (1.0 + missPenalty * Math.max(0, missed));
        return Math.max(1, Math.round(v * rb.taxMult()));
    }

    // ------------------------------------------------------------ 入贡

    /** 把主手锻造品投进贡品窗口：估值计入今日池子，剑被龙收走 */
    public String deposit(Player p) {
        if (!active()) {
            return "<red>龙税契约未开启。";
        }
        ContractStore.Run r = store.run(p.getUniqueId());
        if (r == null || !r.joined) {
            return "<red>你还没签契约：<white>/zhujian contract join</white>";
        }
        ItemStack main = p.getInventory().getItemInMainHand();
        long value = appraise(main);
        if (value <= 0) {
            return "<red>主手得拿一件锻造品——龙只吃锻炉里出来的东西。";
        }
        p.getInventory().setItemInMainHand(null);
        p.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME,
                p.getLocation().add(0, 1.2, 0), 12, 0.3, 0.4, 0.3, 0.02);
        p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.4f, 1.4f);
        r.pool += value;
        store.markDirty(); // 审计 #7：入贡是高频动作，不再主线程同步落盘
        // v2.25.5 修（与 TombManager.bury 同型）：入贡是「吞」，主手已经 setItemInMainHand(null)，
        // 剑只活在内存里。ContractStore 的异步冲刷周期是 600 ticks = 30 秒 —— 这个窗口内
        // 崩服/被杀进程，剑两头都没有（池子没涨、手里也没了）。用一次同步落盘换掉这 30 秒。
        store.save();
        long req = requirement(r.day, r.missed, relicBonus(p.getUniqueId()));
        if (r.pool >= req) {
            return "<green>贡品已够！</green><gray>等午夜巨龙验收（还剩 "
                    + Math.max(0, ticksToSettle() / 1200) + " 分钟）。</gray>";
        }
        return "<gray>已入贡 <white>" + value + "</white> 估值，今日进度 <white>" + r.pool
                + "/" + req + "</white>。</gray>";
    }

    private long ticksToSettle() {
        World w = world();
        if (w == null) {
            return 0;
        }
        long tod = w.getFullTime() % 24000L;
        long settle = settleTime();
        return tod <= settle ? settle - tod : 24000L - tod + settle;
    }

    // ------------------------------------------------------------ 结算 / 破契约

    private void settleDay() {
        List<UUID> ids = new ArrayList<>(store.all().keySet());
        double carryRate = Math.max(0, Math.min(1, plugin.settings().num("contract.tax.carry-rate", 0.3)));
        long clearBonus = Math.max(0, plugin.settings().intOf("contract.tax.full-clear-bonus", 300));
        for (UUID id : ids) {
            ContractStore.Run r = store.run(id);
            if (r == null || !r.joined) {
                continue;
            }
            RelicBonus rb = relicBonus(id);
            long req = requirement(r.day, r.missed, rb);
            Player p = Bukkit.getPlayer(id);
            if (r.pool >= req) {
                // ---- 交满：龙吃剑、超额结转、圣物三选一 ----
                long excess = r.pool - req;
                r.carry = Math.round(excess * carryRate);
                r.pool = r.carry;
                r.day++;
                r.missed = 0;
                long bonus = Math.round(req * 0.1) + clearBonus;
                if (plugin.stats() != null) {
                    // 龙税赏金单独成桶（v2.20）：它既不是退款也不是成就，
                    // 混进「其它产出」就永远看不出周目循环在发多少钱
                    plugin.stats().addCoins(id, bonus, CoinReason.CONTRACT);
                }
                offerRelics(id);
                if (p != null) {
                    p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_AMBIENT, 0.6f, 1.0f);
                    p.getWorld().spawnParticle(Particle.DRAGON_BREATH,
                            p.getLocation().add(0, 2, 0), 20, 0.6, 0.8, 0.6, 0.02);
                    Msg.prefix(p, "<gradient:#4ee36b:#ffd166><bold>巨龙收下了贡品，满意地打了个饱嗝。</bold></gradient> "
                            + "<gray>赏金 <white>" + bonus + "</white>，明晚要 <white>"
                            + requirement(r.day, 0, rb) + "</white> 估值。</gray>");
                    if (!r.pickPending.isBlank()) {
                        com.forgemaster.gui.ContractMenu.openRelicPick(plugin, p);
                    }
                }
            } else {
                // ---- 交不上：龙把贡品连本带利吞了 ----
                long shortfall = req - r.pool;
                r.pool = 0;
                r.missed++;
                int grace = Math.max(1, plugin.settings().intOf("contract.tax.grace", 2));
                if (r.missed >= grace) {
                    String text = breakContractText(id, shortfall);
                    if (p != null) {
                        Msg.prefix(p, text);
                    }
                } else {
                    if (p != null) {
                        p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 0.7f);
                        Msg.prefix(p, "<red>贡品不够，巨龙把剑全吞了还欠着 <white>" + shortfall
                                + "</white> 估值！</red><gray>（宽限 " + (grace - r.missed)
                                + " 次，明晚税额 +30%）</gray>");
                    }
                }
            }
        }
        // 审计 #7：结算循环里 N 个破产玩家原来各写一次盘，现在循环外统一标脏一次，
        // 由 30 秒异步冲刷兜底落盘
        store.markDirty();
        Bukkit.broadcast(Msg.mm("<gradient:#ff3d6e:#b06bff><bold>〔龙税〕</bold></gradient> "
                + "<gray>巨龙清点了今晚的贡品。</gray>"));
    }

    /** 破契约：周目重开、圣物没收、扣币、全服龙怒 */
    private String breakContractText(UUID id, long shortfall) {
        ContractStore.Run r = store.run(id);
        if (r != null) {
            r.day = 1;
            r.missed = 0;
            r.pool = 0;
            r.carry = 0;
            r.relics.clear();
            r.phoenixDay = -1;
            r.pickPending = "";
        }
        store.markDirty(); // 审计 #7：标脏交异步冲刷（settleDay 循环里不再逐人落盘）
        double pct = Math.max(0, Math.min(0.5, plugin.settings().num("contract.tax.break-coin-pct", 0.2)));
        long loss = 0;
        boolean collected = false;
        if (plugin.stats() != null && pct > 0) {
            long coins = plugin.stats().coins(id);
            loss = Math.round(coins * pct);
            if (loss > 0) {
                // v2.26.0 修 P2：返回值原来被忽略 —— 余额在 coins() 读取与 takeCoins 之间
                // 变化（并发扣款 / 实际余额不足被拒）时罚金没收上来，广播却照样喊
                // 「剑币少了 N」= 全服看到一条假账。现在按实际结果措辞，收缴失败留痕。
                collected = plugin.stats().takeCoins(id, loss, CoinReason.OTHER_OUT);
                if (!collected) {
                    plugin.getLogger().warning("[龙税] 断约罚金未能收缴：目标 " + id
                            + " 应缴 " + loss + "（余额在读取与扣款之间发生了变化，契约本身照常粉碎）");
                }
            }
        }
        // v2.23.7 修 P2：主线程 getOfflinePlayer(uuid).getName() 对从未进服的
        // 存档玩家会触发阻塞式数据查档（Paper 明确警告 may block），午夜结算
        // 多人破产时会卡顿。优先在线名，其次 stats 里记账的名字，都不再碰 BlockingManager。
        String name = null;
        Player self = Bukkit.getPlayer(id);
        if (self != null) {
            name = self.getName();
        } else if (plugin.stats() != null) {
            String cached = plugin.stats().get(id).name;
            if (cached != null && !cached.isBlank()) {
                name = cached;
            }
        }
        String taxEnd = (loss > 0 && !collected)
                ? "罚金分文未能收缴——囊中早已空空。"
                : "圣物被尽数夺走，剑币少了 " + loss + "。";
        Bukkit.broadcast(Msg.mm("<gradient:#ff3d6e:#ff7a3d><bold>巨龙的怒吼撕裂了夜空——</bold></gradient> "
                + "<white>" + (name == null ? "一名铁匠" : name) + "</white> <red>交不上税，契约粉碎，"
                + taxEnd + "</red>"));
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.playSound(online.getLocation(), Sound.ENTITY_ENDER_DRAGON_DEATH, 0.7f, 0.8f);
        }
        return "<red><bold>契约粉碎。</bold></red><gray>欠款 <white>" + shortfall
                + "</white> 估值，巨龙没收了你的全部圣物"
                + (loss > 0 ? "，抓走了 <white>" + loss + "</white> 剑币" : "")
                + "。周目重开，明天是新的一天。</gray>";
    }

    // ------------------------------------------------------------ 圣物

    /** 把三选一写进 run（pickPending），在线则直接开选界面 */
    public void offerRelics(UUID id) {
        ContractStore.Run r = store.run(id);
        if (r == null) {
            return;
        }
        List<RelicPool.Def> offer = relics.offer(r.relics, 3, random);
        if (offer.isEmpty()) {
            r.pickPending = "";
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (RelicPool.Def d : offer) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(d.id());
        }
        r.pickPending = sb.toString();
        store.markDirty(); // 审计 #7：标脏交异步冲刷
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            com.forgemaster.gui.ContractMenu.openRelicPick(plugin, p);
        }
    }

    /** 圣物三选一点名；成功返回给玩家看的文本，失败返回 null */
    public String pickRelic(UUID id, String relicId) {
        ContractStore.Run r = store.run(id);
        if (r == null || r.pickPending == null || r.pickPending.isBlank()) {
            return null;
        }
        // v2.26.3：退出契约的人不许领待选圣物。quit 已经把 pickPending 清空，
        // 这里是第二道防线 —— 将来若有人改 quit 忘了清，也不会变成「退出后还能白拿」。
        if (!r.joined) {
            return null;
        }
        List<String> pending = new ArrayList<>(List.of(r.pickPending.split(",")));
        if (!pending.contains(relicId) || !relics.enabled(relicId)) {
            return null;
        }
        // 先校验定义存在再入库：池子被改后 relicId 可能查不到 def，入库再 NPE 就晚了
        RelicPool.Def def = relics.get(relicId);
        if (def == null) {
            return null;
        }
        // v2.26.0：先清再发（单次消费结构）。所有校验通过后才消费 pending，再入库圣物；
        // 同一 pending 只可能被消费一次——第二个领奖入口（聊天 / GUI）撞到的必然是空
        // pending，拿失败回执且零状态变化，重复发放在结构上被挡死。
        r.pickPending = "";
        r.relics.add(relicId);
        store.markDirty(); // 审计 #7：标脏交异步冲刷
        return "<gradient:#ffd166:#ff7a3d>圣物到手：</gradient> <bold>" + def.name()
                + "</bold> <dark_gray>——</dark_gray> " + def.desc();
    }

    /**
     * v2.26.0：聊天补领（{@code /zhujian contract pick <1|2|3>}），给离线错过选界面的玩家用。
     *
     * <p>幂等红线：无 pending / 序号越界 / 候选已失效，都返回明确回执且<b>不产生任何状态变化</b>；
     * 选中一次后 pending 已被 {@link #pickRelic} 的单次消费结构清空，重复 pick 只会拿到
     * 「没有待选」的回执。只发圣物，全程不碰任何剑币路径。</p>
     *
     * @param index 候选序号，1 起
     */
    public String pickPendingByIndex(UUID id, int index) {
        ContractStore.Run r = store.run(id);
        if (r == null || r.pickPending == null || r.pickPending.isBlank()) {
            return "<red>你现在没有待选的圣物（已经选过，或者还没交满贡）。</red>";
        }
        String[] ids = r.pickPending.split(",");
        if (index < 1 || index > ids.length) {
            return "<red>序号越界：这次只有 <white>" + ids.length + "</white> 个候选，"
                    + "写 <white>1</white> 到 <white>" + ids.length + "</white>。</red>";
        }
        String ok = pickRelic(id, ids[index - 1]);
        return ok != null ? ok
                : "<red>第 " + index + " 号候选当前不可选（圣物配置可能被改），换个序号或用面板挑。</red>";
    }

    /** 待选圣物的名字列表（聊天展示用）；无 pending 返回空表 */
    public List<String> pendingNames(UUID id) {
        List<String> out = new ArrayList<>();
        ContractStore.Run r = store.run(id);
        if (r == null || r.pickPending == null || r.pickPending.isBlank()) {
            return out;
        }
        for (String relicId : r.pickPending.split(",")) {
            RelicPool.Def d = relics.get(relicId);
            out.add(d == null ? relicId : d.name());
        }
        return out;
    }

    /** 圣物聚合加成：没在契 / 没圣物返回 ZERO */
    public RelicBonus relicBonus(UUID id) {
        ContractStore.Run r = store.run(id);
        if (r == null || !r.joined || r.relics.isEmpty()) {
            return RelicBonus.ZERO;
        }
        double success = 0, great = 0, inherit = 0;
        double breakMult = 1.0, valueMult = 1.0, taxMult = 1.0;
        int frenzyExtra = 0, furnacePerFail = 0, cornerstoneExtra = 0;
        boolean comboKeep = false, phoenix = false;
        for (String relicId : r.relics) {
            if (!relics.enabled(relicId)) {
                continue;
            }
            switch (relicId) {
                case "bent-nail" -> success += 0.04;
                case "lucky-coal" -> great += 0.05;
                case "black-knot" -> breakMult *= 0.90;
                case "dragonscale" -> breakMult *= 0.85;
                case "inspection-stamp" -> valueMult *= 1.15;
                case "sealed-rebate" -> taxMult *= 0.90;
                case "giants-spine" -> frenzyExtra += 2;
                case "wax-thread" -> comboKeep = true;
                case "golden-cog" -> furnacePerFail += 2;
                case "unyielding-stone" -> cornerstoneExtra += 1;
                case "phoenix-ash" -> phoenix = true;
                case "broken-oath" -> inherit += 0.15;
                default -> {
                }
            }
        }
        return new RelicBonus(success, great, breakMult, valueMult, taxMult,
                frenzyExtra, comboKeep, inherit, furnacePerFail, cornerstoneExtra, phoenix);
    }

    /** 凤凰灰今天还能不能用（每个周目日一次） */
    public boolean canPhoenix(UUID id) {
        ContractStore.Run r = store.run(id);
        if (r == null || !relics.enabled("phoenix-ash") || !r.relics.contains("phoenix-ash")) {
            return false;
        }
        return r.phoenixDay != r.day;
    }

    public void markPhoenixUsed(UUID id) {
        ContractStore.Run r = store.run(id);
        if (r != null) {
            r.phoenixDay = r.day;
            store.markDirty(); // 审计 #7：标脏交异步冲刷
        }
    }

    /** 周目日（给凤凰灰 / 展示用） */
    public int runDay(UUID id) {
        ContractStore.Run r = store.run(id);
        return r == null ? 1 : r.day;
    }

    /** 面板摘要（MiniMessage 多段） */
    public List<String> describe(Player p) {
        List<String> out = new ArrayList<>();
        if (!active()) {
            out.add("<red>龙税契约未开启。");
            return out;
        }
        ContractStore.Run r = store.run(p.getUniqueId());
        if (r == null || !r.joined) {
            out.add("<gray>你还没签契约：<white>/zhujian contract join</white>");
            return out;
        }
        RelicBonus rb = relicBonus(p.getUniqueId());
        long req = requirement(r.day, r.missed, rb);
        out.add("<gradient:#ff3d6e:#b06bff><bold>〔龙税契约〕</bold></gradient> <gray>周目日</gray> <white>"
                + r.day + "</white>");
        out.add("<gray>今晚要：</gray><white>" + req + "</white> <gray>估值</gray> <dark_gray>│</dark_gray> "
                + "<gray>已入贡：</gray><white>" + r.pool + "</white>"
                + (r.carry > 0 ? " <dark_gray>（含结转 " + r.carry + "）</dark_gray>" : ""));
        out.add("<gray>黄昏后主手拿剑 <white>/zhujian contract deposit</white> 入贡，午夜验收。</gray>");
        if (r.missed > 0) {
            out.add("<red>欠税 " + r.missed + " 次（宽限用尽 = 破契约）</red>");
        }
        if (!r.relics.isEmpty()) {
            StringBuilder sb = new StringBuilder("<gray>圣物：</gray>");
            for (String id : r.relics) {
                RelicPool.Def d = relics.get(id);
                if (d != null) {
                    sb.append("<white>").append(d.name()).append("</white> ");
                }
            }
            out.add(sb.toString().trim());
        }
        if (r.pickPending != null && !r.pickPending.isBlank()) {
            out.add("<gold>有待选圣物：<white>/zhujian contract</white> 打开面板挑选。</gold>");
        }
        out.add("<dark_gray>距结算约 " + (ticksToSettle() / 1200) + " 分钟（游戏时）</dark_gray>");
        return out;
    }
}
