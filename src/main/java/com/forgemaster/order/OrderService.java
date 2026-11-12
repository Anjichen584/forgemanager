package com.forgemaster.order;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.quality.Quality;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 锻造订单：玩家对玩家的委托市场（/zhujian order，别名 dingdan）。
 *
 * <p>存档照 {@link com.forgemaster.commission.CommissionManager} 的 yml 风格
 * （orders.yml，同步落盘），离线交付复用拍卖行邮箱
 * （AuctionStore.addMail，货先进 auction.yml 的 mail 段再由玩家 claim）。</p>
 *
 * <p><b>托管 / 退款的崩溃窗口语义</b>（对照 AuctionManager 的「先归属后动钱」注释）：
 * 订单单据（orders.yml）是唯一真相，每步变更先落盘再动剑币，全程同步写盘、无异步窗口：</p>
 * <ul>
 *   <li>发单：先扣悬赏（takeCoins 托管），再落订单单据。崩溃窗口 = 已扣款未落单 →
 *       发单人漏一张单（人工补）。反向（先落单后扣款）崩溃会变成「无主托管」，接单人
 *       交付时凭空造币，通胀方向更糟，故与 AuctionManager.bid 的「冻结在前」同序。</li>
 *   <li>取消（仅未接单）：先删单据落盘，再退悬赏。崩溃只会「漏退」（可人工补），
 *       不会出现单据还在又退了一次钱的二次退款。</li>
 *   <li>交付：第一步剑先记到订单上并置 DONE（归属先落盘，防重复交付）；第二步剑进
 *       发单人邮箱（AuctionStore.addMail）；第三步才动钱给接单人。崩溃落在 1→2 之间：
 *       orders.yml 里留下带剑的 DONE 单，load() 时 reconcile 自动续做（剑补进邮箱）；
 *       落在 2→3 之间：悬赏漏发（可人工补，语义同 AuctionManager.buyout 的先 finish 后 takeCoins）。</li>
 *   <li>过期：先置 EXPIRED 落盘（防重复结算），再退剑（若托管槽有剑 → 退接单人邮箱）、
 *       再退悬赏给发单人。崩溃只会漏退 / 漏发一次，可人工补。</li>
 * </ul>
 *
 * <p>手续费 floor(悬赏 × order.fee-rate) 直接消失（不进任何账户）——这是剑币回收口，
 * 与拍卖行 tax-rate 的销毁语义一致。</p>
 */
public final class OrderService {

    /** 同一发单人同时最多几张活跃单（OPEN + TAKEN），config 驱动 */
    public static final int POST_MAX_DEFAULT = 3;
    /** 同一接单人同时只能接 1 单（写死防刷，不给 config） */
    private static final int TAKE_MAX = 1;

    private final ForgingMasterPlugin plugin;
    private final File file;
    private final Random random = new Random();
    private final Map<String, Order> orders = new LinkedHashMap<>();

    public OrderService(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "orders.yml");
    }

    // ------------------------------------------------------------ 配置（全部 config 驱动，reload 即生效）

    public boolean enabled() {
        return plugin.settings().flag("order.enabled", true);
    }

    public int maxActivePerPlayer() {
        return Math.max(1, plugin.settings().intOf("order.max-active-per-player", POST_MAX_DEFAULT));
    }

    public int expireHours() {
        return Math.max(1, plugin.settings().intOf("order.expire-hours", 72));
    }

    public int repReward() {
        return Math.max(0, plugin.settings().intOf("order.rep-reward", 10));
    }

    /** 手续费比例，clamp 0 ~ 0.9 */
    public double feeRate() {
        return Math.max(0.0, Math.min(0.9, plugin.settings().num("order.fee-rate", 0.05)));
    }

    /** 单张订单悬赏上限：复用拍卖行的 max-price，避免悬赏溢出 long */
    public long maxBounty() {
        return Math.max(1L, (long) plugin.settings().num("auction.max-price", 1_000_000));
    }

    /** 接单人实际到手 = 悬赏 - floor(悬赏 × fee-rate)，手续费销毁 */
    public long payout(long bounty) {
        return bounty - (long) Math.floor(bounty * feeRate());
    }

    // ------------------------------------------------------------ 存盘

    public void load() {
        orders.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yml.getConfigurationSection("orders");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) {
                continue;
            }
            try {
                Order o = Order.fromSection(sec);
                if (o != null) {
                    orders.put(o.id(), o);
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("订单数据损坏，已跳过 " + id + ": " + ex.getMessage());
            }
        }
        reconcile();
        plugin.getLogger().info("锻造订单已载入 " + orders.size() + " 张");
    }

    /** 同步落盘。订单量小（发单上限 × 玩家数），每步结算直接写，无异步窗口。 */
    public synchronized void save() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            return;
        }
        YamlConfiguration yml = new YamlConfiguration();
        for (Order o : orders.values()) {
            yml.set("orders." + o.id(), o.toMap());
        }
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("保存 orders.yml 失败: " + ex.getMessage());
        }
    }

    /**
     * 崩溃续做：托管槽里还留着剑的单，说明上一次交付 / 过期退剑在两步之间断掉了。
     * DONE 单的剑归发单人（交付续做）；其余（EXPIRED / 异常态）的剑归接单人（退剑续做）。
     */
    private void reconcile() {
        boolean changed = false;
        for (Order o : orders.values()) {
            ItemStack it = o.item();
            if (it == null || it.getType().isAir()) {
                continue;
            }
            UUID dest = o.state() == Order.State.DONE || !o.hasTaker() ? o.poster() : o.taker();
            addMail(dest, it, "订单 #" + o.id() + " 的托管剑（断线续做）");
            o.item(null);
            changed = true;
        }
        if (changed) {
            save();
        }
    }

    /** 离线交付统一走拍卖行邮箱（AuctionStore.addMail，货先落 auction.yml 的 mail 段） */
    private void addMail(UUID owner, ItemStack item, String why) {
        if (plugin.auction() == null || owner == null) {
            plugin.getLogger().warning("拍卖行未启用，订单托管剑无处可退（" + why + "），请人工处理");
            return;
        }
        plugin.auction().store().addMail(owner, item);
        // v2.25.2（4 并发查错）：跨文件交接必须两头都落盘。
        // addMail 只标脏（auction.yml 的 mail 段最多晚 5 秒才写），而调用方紧接着会
        // save() 把 orders.yml 里这把剑删掉 —— 两条序列一叠加，5 秒窗口内崩服就是
        // 「订单没了、邮箱也没有」= 玩家托管剑凭空消失。
        // 拍行自己的 claim() 就是「先 replaceMail 再 store.save()」，这里照同一口径。
        plugin.auction().store().save();
    }

    // ------------------------------------------------------------ 发单

    /**
     * 发单：主手锻造品做样品解析需求（材料 / 品质 / 强化从 PDC 读），悬赏现扣托管。
     *
     * @return 给玩家的提示（Msg mini-message）
     */
    public String post(Player p, long bounty) {
        if (!enabled()) {
            return "<red>订单市场没开。";
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir() || !plugin.items().isForged(hand)) {
            return "<red>主手要拿一把锻造品做样品。";
        }
        if (bounty < 1 || bounty > maxBounty()) {
            return "<red>悬赏需在 1 ~ " + maxBounty() + " 剑币之间。";
        }
        int mine = 0;
        for (Order o : orders.values()) {
            if (o.poster().equals(p.getUniqueId())
                    && (o.state() == Order.State.OPEN || o.state() == Order.State.TAKEN)) {
                mine++;
            }
        }
        if (mine >= maxActivePerPlayer()) {
            return "<red>你已有 " + mine + " 张活跃订单，上限 " + maxActivePerPlayer() + " 张。";
        }

        var meta = hand.getItemMeta();
        if (meta == null) {
            return "<red>物品数据异常。";
        }
        var pdc = meta.getPersistentDataContainer();
        String material = pdc.get(Keys.MATERIAL, PersistentDataType.STRING);
        if (material == null || material.isBlank()) {
            material = "any";
        }
        String qn = pdc.get(Keys.QUALITY, PersistentDataType.STRING);
        Quality quality = qn == null ? null : Quality.byName(qn);
        Integer strengthen = pdc.get(Keys.STRENGTHEN, PersistentDataType.INTEGER);

        // 悬赏托管扣款在前，订单单据随后落盘（崩溃窗口语义见类注释）
        if (!plugin.stats().takeCoins(p.getUniqueId(), bounty, com.forgemaster.econ.CoinReason.OTHER_OUT)) {
            return "<red>剑币不足，托管悬赏需要 " + bounty + " 剑币。";
        }
        plugin.stats().markDirty();
        long now = System.currentTimeMillis();
        Order o = new Order(newId(), p.getUniqueId(), p.getName(),
                material, quality, strengthen == null ? 0 : Math.max(0, strengthen),
                bounty, Order.State.OPEN, null, null,
                now, now + expireHours() * 3_600_000L, null);
        orders.put(o.id(), o);
        save();
        // v2.23 切片 5：定价提示 —— 悬赏 vs 样品战力，让发单的人知道自己在开什么价
        StringBuilder hint = new StringBuilder();
        long power = com.forgemaster.score.PowerScore.of(plugin, hand);
        if (power > 0) {
            long suggest = com.forgemaster.score.PowerScore.suggestPrice(plugin, hand);
            hint.append("<dark_gray>　样品战力 <#ffd166>").append(power).append("</#ffd166>")
                    .append("（").append(com.forgemaster.score.PowerScore.gradeOf(plugin, power)).append("）");
            if (suggest > 0) {
                hint.append("，参考悬赏 <white>").append(suggest).append("</white> 剑币");
            }
            hint.append("</dark_gray>");
        }
        return "<green>订单 <white>#" + o.id() + "</white> 已挂出，悬赏 <gold>" + bounty
                + "</gold> 剑币已托管，" + expireHours() + " 小时后过期。" + hint;
    }

    // ------------------------------------------------------------ 接单 / 交付

    /** 接单：同时只能接 1 单；不能接自己的单 */
    public String take(Player p, String id) {
        Order o = orders.get(id);
        if (o == null || o.state() != Order.State.OPEN) {
            return "<red>这张订单不存在或已被接走。";
        }
        if (o.poster().equals(p.getUniqueId())) {
            return "<red>不能接自己的订单。";
        }
        for (Order mine : orders.values()) {
            if (mine.state() == Order.State.TAKEN && p.getUniqueId().equals(mine.taker())) {
                return "<red>你已接了订单 #" + mine.id() + "，交付后才能接下一单。";
            }
        }
        o.taker(p.getUniqueId(), p.getName());
        o.state(Order.State.TAKEN);
        save();
        return "<green>接单成功！做出达标的剑后用 <white>/zhujian order</white> 打开面板点「交付」。";
    }

    /**
     * 交付：主手拿着达标锻造品，服务端核验材料 / 品质 / 强化 ≥ 需求。
     * 剑走邮箱给发单人，悬赏（扣手续费）到账接单人 + 声望小奖励。
     */
    public String deliver(Player p, String id) {
        Order o = orders.get(id);
        if (o == null) {
            return "<red>这张订单不存在。";
        }
        if (o.state() != Order.State.TAKEN || !p.getUniqueId().equals(o.taker())) {
            return "<red>这不是你接的订单。";
        }
        if (o.expired()) {
            return "<red>这张订单已过期，等系统结算退单。";
        }
        ItemStack held = p.getInventory().getItemInMainHand();
        String missing = o.missing(plugin.items(), held);
        if (missing != null) {
            return "<red>交不了：<white>" + missing + "</white>。";
        }

        ItemStack sword = held.clone();
        held.setAmount(held.getAmount() - 1);
        p.getInventory().setItemInMainHand(held.getAmount() <= 0 ? null : held);

        // 第一步：剑先记到订单并置 DONE（归属先落盘，防重复交付 / 防剑丢失）
        o.item(sword);
        o.state(Order.State.DONE);
        save();
        // 第二步：剑进发单人邮箱（离线也能领）
        addMail(o.poster(), sword, "订单 #" + o.id() + " 交付");
        o.item(null);
        save();
        // 第三步：动钱——悬赏扣手续费给接单人，手续费销毁（剑币回收口）
        long pay = payout(o.bounty());
        // v2.25.2（审计轮4）：发钱必须走 Wallet 收口。直连 stats().addCoins 在
        // economy.coin-sinks=server 时会分裂两本账（扣的是金币、发的是剑币＝凭空造币）。
        plugin.wallet().give(p, pay, com.forgemaster.econ.CoinReason.BOUNTY);
        plugin.stats().markDirty();
        int rep = repReward();
        if (rep > 0) {
            boolean promoted = plugin.stats().addReputation(p.getUniqueId(), rep);
            if (plugin.ranks() != null) {
                plugin.ranks().refresh(p.getUniqueId()); // 声望变化后即时刷新段位
            }
            if (promoted) {
                String title = com.forgemaster.stats.StatsStore.titleOf(
                        plugin.stats().get(p.getUniqueId()).reputation);
                plugin.getServer().broadcast(Msg.mm(Msg.PREFIX + "<gray>" + p.getName()
                        + " <gray>的声望称号变成了「<#ffd166>" + title + "</#ffd166>」。"));
            }
        }
        notifyPlayer(o.poster(), "<green>你挂的订单 #" + o.id() + " 已被 " + p.getName()
                + " 交付，锻造品进了你的邮箱（/zhujian auction claim 领取）。");
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
        return "<green>交付完成！悬赏到账 <gold>" + pay + "</gold> 剑币（手续费 "
                + (o.bounty() - pay) + " 已销毁）" + (rep > 0 ? " · 声望 <white>+" + rep : "") + "。";
    }

    // ------------------------------------------------------------ 取消 / 过期

    /** 取消未接单订单：全额退还托管悬赏 */
    public String cancel(Player p, String id) {
        Order o = orders.get(id);
        if (o == null) {
            return "<red>这张订单不存在。";
        }
        if (!o.poster().equals(p.getUniqueId())) {
            return "<red>这不是你发的订单。";
        }
        if (o.state() != Order.State.OPEN) {
            return "<red>已被人接单，不能取消了。";
        }
        // 先删单据落盘（结案），再退款：崩溃只会漏退，不会二次退款
        orders.remove(id);
        save();
        // v2.25.2（审计轮4）：退款与当初扣款必须同一本账，走 Wallet（同 take 的判定）
        plugin.wallet().give(p, o.bounty(), com.forgemaster.econ.CoinReason.BOUNTY);
        plugin.stats().markDirty();
        return "<green>订单 #" + id + " 已取消，托管悬赏 <gold>" + o.bounty() + "</gold> 剑币全额退回。";
    }

    /** 过期结算：OPEN / TAKEN 到期 → 悬赏退发单人；托管槽有剑 → 剑退接单人邮箱 */
    public void settleExpired() {
        boolean changed = false;
        for (Order o : orders.values()) {
            if (o.state() != Order.State.OPEN && o.state() != Order.State.TAKEN) {
                continue;
            }
            if (!o.expired()) {
                continue;
            }
            // 先结案落盘，防崩溃后重复结算（重复退款 = 刷钱）
            o.state(Order.State.EXPIRED);
            save();
            changed = true;
            if (o.item() != null && !o.item().getType().isAir()) {
                addMail(o.taker(), o.item(), "订单 #" + o.id() + " 过期退剑");
                o.item(null);
                save();
            }
            if (o.bounty() > 0) {
                // v2.23.1 修 P2：原来这里是 addCoins + markDirty，而上一行的
                // o.state(EXPIRED)+save() 已经把「已结案」写进 orders.yml（同步）。
                // 中间崩溃 → 单据已结、悬赏没入账，发单人的托管币永久消失。
                // 现在：退款走 Wallet（与黑市/签到同一本账），并同步落一次 data.yml。
                // 顺序仍是「先结案后发钱」（反了就是重复退款），但两次写盘紧挨着，
                // 且失败会留 warning，可人工补。
                var wallet = plugin.wallet();
                if (!wallet.giveTo(o.poster(), o.posterName(), o.bounty(),
                        com.forgemaster.econ.CoinReason.REFUND)) {
                    plugin.getLogger().warning("订单 #" + o.id() + " 过期退款失败（"
                            + o.bounty() + " 币），请人工补发");
                }
                plugin.stats().save();
            }
            notifyPlayer(o.poster(), "<yellow>你的订单 #" + o.id() + " 过期了，托管悬赏 "
                    + o.bounty() + " 剑币已退回。");
            if (o.hasTaker()) {
                notifyPlayer(o.taker(), "<yellow>你接的订单 #" + o.id() + " 过期未交付，已作废。");
            }
        }
        if (changed) {
            save();
        }
    }

    /** 清掉已结案的 DONE / EXPIRED 单（悬赏与剑都清算完了），防 orders.yml 只增不减 */
    public void purgeSettled() {
        boolean changed = orders.values().removeIf(o ->
                (o.state() == Order.State.DONE || o.state() == Order.State.EXPIRED)
                        && (o.item() == null || o.item().getType().isAir()));
        if (changed) {
            save();
        }
    }

    /** 定时维护（5 分钟一轮）：先结算过期，再清已结案单 */
    public void maintain() {
        settleExpired();
        purgeSettled();
    }

    // ------------------------------------------------------------ 查询

    public synchronized Order get(String id) {
        return orders.get(id);
    }

    /** 全部 OPEN 单，按悬赏降序（GUI 用） */
    public synchronized List<Order> openByBounty() {
        List<Order> list = new ArrayList<>();
        for (Order o : orders.values()) {
            if (o.state() == Order.State.OPEN && !o.expired()) {
                list.add(o);
            }
        }
        list.sort(Comparator.comparingLong(Order::bounty).reversed());
        return list;
    }

    /** 该玩家已接的 TAKEN 单（同时最多 1 张） */
    public synchronized Order takenBy(UUID taker) {
        for (Order o : orders.values()) {
            if (o.state() == Order.State.TAKEN && taker.equals(o.taker())) {
                return o;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 命令入口（/zhujian order，别名 dingdan）

    public void handle(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>订单市场要在游戏里使用。</red>");
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "", "browse", "list" -> OrderGui.open(plugin, p);
            case "post", "fabu" -> {
                if (args.length < 3) {
                    usage(p);
                    return;
                }
                long bounty;
                try {
                    bounty = Long.parseLong(args[2]);
                } catch (NumberFormatException ex) {
                    Msg.prefix(p, "<red>悬赏金额不对。</red>");
                    return;
                }
                Msg.prefix(p, post(p, bounty));
            }
            case "cancel", "quxiao" -> {
                if (args.length < 3) {
                    usage(p);
                    return;
                }
                Msg.prefix(p, cancel(p, args[2].toUpperCase(Locale.ROOT)));
            }
            default -> usage(p);
        }
    }

    private void usage(Player p) {
        Msg.prefix(p, "<gray>用法: <white>/zhujian order</white> 打开面板 · "
                + "<white>/zhujian order post <悬赏></white> 用主手样品发单 · "
                + "<white>/zhujian order cancel <编号></white> 取消未接单订单");
    }

    // ------------------------------------------------------------ 小工具

    private String newId() {
        while (true) {
            String hex = Integer.toHexString(random.nextInt(0xFFFFFF)).toUpperCase(Locale.ROOT);
            while (hex.length() < 6) {
                hex = "0" + hex;
            }
            if (!orders.containsKey(hex)) {
                return hex;
            }
        }
    }

    private void notifyPlayer(UUID uuid, String text) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            Msg.prefix(online, text);
        }
    }

    /** 订单需求的展示行（GUI 与命令共用） */
    static List<String> requirementLines(Order o) {
        List<String> lore = new ArrayList<>();
        String matName = "any".equalsIgnoreCase(o.materialId()) || o.materialId() == null
                ? "<white>任意剑胚"
                : "<white>" + o.materialId();
        lore.add("<gray>材料: " + matName);
        lore.add("<gray>品质: " + (o.qualityReq() == null
                ? "<white>不限" : "<white>" + o.qualityReq().display() + " <gray>以上"));
        lore.add("<gray>强化: " + (o.strengthenReq() > 0
                ? "<white>+" + o.strengthenReq() + " <gray>以上" : "<white>不限"));
        return lore;
    }

    /** 主手样品的 PDC 快照（发单确认用） */
    static String sampleSummary(ItemStack hand) {
        var meta = hand.getItemMeta();
        if (meta == null) {
            return "?";
        }
        var pdc = meta.getPersistentDataContainer();
        String material = pdc.get(Keys.MATERIAL, PersistentDataType.STRING);
        String qn = pdc.get(Keys.QUALITY, PersistentDataType.STRING);
        Quality q = qn == null ? null : Quality.byName(qn);
        Integer strengthen = pdc.get(Keys.STRENGTHEN, PersistentDataType.INTEGER);
        return (material == null ? "any" : material)
                + " / " + (q == null ? "不限" : q.display())
                + " / +" + (strengthen == null ? 0 : strengthen);
    }

}
