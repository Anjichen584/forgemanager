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
        long fullTime = w.getFullTime();
        long fullDay = fullTime / 24000L;
        long tod = fullTime % 24000L;
        if (tod >= settleTime() && store.lastSettledFullDay() < fullDay) {
            store.lastSettledFullDay(fullDay);
            settleDay();
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
    }

    // ------------------------------------------------------------ 加入 / 退出

    public String join(Player p) {
        if (!active()) {
            return "<red>龙税契约未开启。";
        }
        ContractStore.Run r = store.ensure(p.getUniqueId());
        if (r.joined && r.day > 1) {
            return "<gray>你已经在契约中了——今天要交 <white>" + requirement(r.day, r.missed, relicBonus(p.getUniqueId()))
                    + "</white> 估值。";
        }
        r.joined = true;
        store.markDirty(); // 审计 #7：改动即存改为标脏，30 秒异步冲刷
        // 开局三选一（ForgeTax day1 starter gift 的移植）
        offerRelics(p.getUniqueId());
        Msg.prefix(p, "<gradient:#ff3d6e:#b06bff><bold>巨龙的低语在你耳边响起——</bold></gradient>");
        return "<gray>契约已签。今晚龙要 <white>" + requirement(r.day, 0, relicBonus(p.getUniqueId()))
                + "</white> 估值，黄昏后 <white>/zhujian contract</white> 打开贡品窗口交剑。";
    }

    public String quit(Player p) {
        ContractStore.Run r = store.run(p.getUniqueId());
        if (r == null || !r.joined) {
            return "<red>你还没签契约。";
        }
        store.remove(p.getUniqueId());
        return "<gray>契约撕毁。巨龙记住了这件事——本局圣物全部作废。";
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
        if (plugin.stats() != null && pct > 0) {
            long coins = plugin.stats().coins(id);
            loss = Math.round(coins * pct);
            if (loss > 0) {
                plugin.stats().takeCoins(id, loss, CoinReason.OTHER_OUT);
            }
        }
        String name = Bukkit.getOfflinePlayer(id).getName();
        Bukkit.broadcast(Msg.mm("<gradient:#ff3d6e:#ff7a3d><bold>巨龙的怒吼撕裂了夜空——</bold></gradient> "
                + "<white>" + (name == null ? "一名铁匠" : name) + "</white> <red>交不上税，契约粉碎，"
                + "圣物被尽数夺走，剑币少了 " + loss + "。</red>"));
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
        List<String> pending = new ArrayList<>(List.of(r.pickPending.split(",")));
        if (!pending.contains(relicId) || !relics.enabled(relicId)) {
            return null;
        }
        // 先校验定义存在再入库：池子被改后 relicId 可能查不到 def，入库再 NPE 就晚了
        RelicPool.Def def = relics.get(relicId);
        if (def == null) {
            return null;
        }
        r.relics.add(relicId);
        r.pickPending = "";
        store.markDirty(); // 审计 #7：标脏交异步冲刷
        return "<gradient:#ffd166:#ff7a3d>圣物到手：</gradient> <bold>" + def.name()
                + "</bold> <dark_gray>——</dark_gray> " + def.desc();
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
