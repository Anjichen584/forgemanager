package com.forgemaster.auction;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.econ.CoinReason;
import com.forgemaster.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * 拍卖行核心：挂单 / 一口价 / 竞价 / 抽成 / 到期结算 / 邮箱领取。
 *
 * <p>剑币只通过 StatsStore 的 coins / addCoins / takeCoins 变动。</p>
 * <ul>
 *   <li>竞价：出价即扣款（冻结），被超越时全额退还上一位。</li>
 *   <li>抽成：卖家到手 = 成交价 - floor(成交价 × auction.tax-rate)，抽成部分销毁（不归任何人）。</li>
 *   <li>物品：成交货、流拍货、下架货统一进 owner 邮箱（auction.yml 的 mail 段），玩家用 claim 领取。</li>
 *   <li>落盘顺序：先改挂单 / 邮箱归属并 store 标脏，再动剑币并 stats.markDirty()；
 *       两份文件都由各自的低频异步任务冲刷，崩溃窗口语义同旧注释（最多漏退 / 漏发一次，可人工补），
 *       差别只是窗口从 0 变成 ≤5 秒（审计 #3 接受）。</li>
 * </ul>
 */
public final class AuctionManager {

    public static final int LIST_SIZE = 45;
    public static final int SLOT_MAIL = 45;
    public static final int SLOT_CLOSE = 49;
    public static final int SLOT_REFRESH = 53;

    private final ForgingMasterPlugin plugin;
    private final AuctionStore store;
    private final Random random = new Random();
    private int settleTaskId = -1;

    public AuctionManager(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.store = new AuctionStore(plugin);
    }

    public AuctionStore store() {
        return store;
    }

    public boolean enabled() {
        return plugin.settings().flag("auction.enabled", true);
    }

    public int defaultHours() {
        return Math.max(1, Math.min(maxHours(), plugin.settings().intOf("auction.default-hours", 24)));
    }

    /** 启动：载入挂单、注册界面监听、每 5 秒扫描一次到期拍品、启动异步落盘冲刷 */
    public void start() {
        store.load();
        store.startAutoFlush();
        Bukkit.getPluginManager().registerEvents(new AuctionListener(this), plugin);
        if (settleTaskId == -1) {
            settleTaskId = Bukkit.getScheduler()
                    .runTaskTimer(plugin, this::settleExpired, 100L, 100L).getTaskId();
        }
    }

    public void shutdown() {
        if (settleTaskId != -1) {
            Bukkit.getScheduler().cancelTask(settleTaskId);
            settleTaskId = -1;
        }
        // data.yml 的全量同步保存已由 onDisable 里 stats.save() 统一做（在本方法之前执行），
        // 这里只补脏标记，不再主线程直调整档重写
        plugin.stats().markDirty();
        // store.stop()：取消异步冲刷任务并强制把挂单 / 邮箱落一次盘
        store.stop();
    }

    // ------------------------------------------------------------ 配置

    private double taxRate() {
        return Math.max(0.0, Math.min(0.9, plugin.settings().num("auction.tax-rate", 0.05)));
    }

    private long maxPrice() {
        return Math.max(1L, (long) plugin.settings().num("auction.max-price", 1_000_000));
    }

    private long minIncrement() {
        return Math.max(1L, (long) plugin.settings().num("auction.min-increment", 10));
    }

    private int maxHours() {
        return Math.max(1, plugin.settings().intOf("auction.max-hours", 72));
    }

    private int maxPerPlayer() {
        return Math.max(1, plugin.settings().intOf("auction.max-per-player", 5));
    }

    private long listingFee() {
        return Math.max(0L, (long) plugin.settings().num("auction.listing-fee", 0));
    }

    // ------------------------------------------------------ v2.23.1 修 P0：同步提交点
    //
    // 原来会怎样：单据写 auction.yml（5 秒异步冲刷），剑币写 data.yml（5 分钟异步冲刷）。
    // 一次成交要同时改两边，中间那条几分钟的窗口里崩溃只会落一侧：
    //   · 只落 auction.yml → 挂单消失、货已进买家邮箱，但买家扣款没落盘 = 买家白拿货；
    //   · 只落 data.yml   → 挂单还在，重启后 settleExpired 再结一次 = 卖家二次收款（造币）。
    //
    // 现在为什么不会：所有动钱路径统一走下面两个提交点，顺序固定为
    //   ① 先扣买家钱并【立即同步】落 data.yml（此刻崩溃 = 买家自认倒霉，钱已付单未成）
    //   ② 再落 auction.yml（单据是权威：谁的货、谁的邮箱、谁是最高出价）
    //   ③ 最后退旧买家 / 付卖家，再同步落一次 data.yml
    // 任意一步崩溃都只会「漏发 / 漏退」，而且每一步都有 warning 留痕可人工补；
    // 不可能出现「货与钱都算了两份」或「凭空造币」。

    /**
     * 同步落 auction.yml。单笔成交里必须紧跟在一次归属变更之后调用，
     * 不能依赖 5 秒异步冲刷——那就是 P0 的窗口。
     */
    private void commitStoreNow() {
        store.save();
    }

    /**
     * 同步落 data.yml（剑币）。
     *
     * <p>{@code economy.coin-sinks=server} 时剑币不由本插件持有，StatsStore 里可能没有
     * 任何变动，这里多存一次只是空转，不影响正确性。</p>
     */
    private void commitMoneyNow() {
        if (plugin.stats() != null) {
            plugin.stats().save();
        }
    }

    /** 抽成后的卖家到手价，附在日志里方便人工对账 */
    private void warnLost(String what, String id) {
        plugin.getLogger().warning("拍卖行结算中断（" + what + "，拍品 #" + id
                + "）：该笔款项未落盘，请核对 data.yml 后人工补发");
    }

    /** 卖家实际到手的剑币（抽成向下取整） */
    public long proceeds(long price) {
        return price - (long) Math.floor(price * taxRate());
    }

    /** 下一口最低出价：无人出价 = 起拍价；有人出价 = 当前价 + 最低加价 */
    public long minBid(AuctionListing l) {
        return l.hasBidder() ? l.price() + minIncrement() : l.startPrice();
    }

    // ------------------------------------------------------------ 挂单

    public String sell(Player p, long start, long buyout, int hours) {
        if (!enabled()) {
            return "<red>拍卖行没开。";
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            return "<red>手上没有东西可以挂单。";
        }
        if (start < 1 || start > maxPrice()) {
            return "<red>起拍价需在 1 ~ " + maxPrice() + " 剑币之间。";
        }
        if (buyout != 0 && (buyout < start || buyout > maxPrice())) {
            return "<red>一口价必须为 0（不设）或在 起拍价 ~ " + maxPrice() + " 之间。";
        }
        int h = Math.max(1, Math.min(maxHours(), hours));
        // v2.23 切片 5：挂单前给一句「你的东西值多少」，避免玩家凭感觉定价
        long myPower = com.forgemaster.score.PowerScore.of(plugin, hand);
        StringBuilder hint = new StringBuilder();
        if (myPower > 0) {
            long suggest = com.forgemaster.score.PowerScore.suggestPrice(plugin, hand);
            hint.append("<dark_gray>　这件战力 <#ffd166>").append(myPower).append("</#ffd166>")
                    .append("（").append(com.forgemaster.score.PowerScore.gradeOf(plugin, myPower)).append("）");
            if (suggest > 0) {
                hint.append("，参考价 <white>").append(suggest).append("</white> 剑币");
            }
            hint.append("</dark_gray>");
        }
        long mine = 0;
        for (AuctionListing l : store.all()) {
            if (l.seller().equals(p.getUniqueId())) {
                mine++;
            }
        }
        if (mine >= maxPerPlayer()) {
            return "<red>你已经挂了 " + mine + " 件，上限 " + maxPerPlayer() + " 件。";
        }
        long fee = listingFee();
        // 挂单手续费：拍卖行自己的收费口（成交抽成是 proceeds() 隐式扣的，不走 takeCoins）
        // v2.23.1 修 P1：手续费改走 Wallet，与黑市/签到同一本账（原来硬编码 stats()，
        // coin-sinks=server 时这里扣剑币、黑市扣 Vault 金币，同一个玩家两本账分裂）
        var wallet = plugin.wallet();
        if (fee > 0 && !wallet.take(p, fee, CoinReason.AUCTION_TAX)) {
            return "<red>挂单手续费 " + fee + " 剑币不够。";
        }
        ItemStack stack = hand.clone();
        p.getInventory().setItemInMainHand(null);
        long now = System.currentTimeMillis();
        AuctionListing listing = new AuctionListing(newId(), p.getUniqueId(), p.getName(), stack,
                start, buyout, start, now, now + h * 3_600_000L);
        // v2.23.1 修 P0：先落钱（手续费已扣）、再落单据，两步都同步走，
        // 崩溃只会「钱扣了单没挂上」，不会「单挂上了货却还在手上」
        if (fee > 0) {
            commitMoneyNow();
        }
        store.put(listing);
        commitStoreNow();
        return "<green>已挂单 <white>#" + listing.id() + "</white>：起拍 " + start + " 剑币"
                + (buyout > 0 ? "，一口价 " + buyout : "") + "，" + h + " 小时后结束。"
                + hint;
    }

    // ------------------------------------------------------------ 竞价

    public String bid(Player p, String id, long amount) {
        AuctionListing l = store.get(id);
        if (l == null) {
            return "<red>这件拍品不存在或已结束。";
        }
        if (l.expired(System.currentTimeMillis())) {
            return "<red>这件拍品已到期，等待结算。";
        }
        if (l.seller().equals(p.getUniqueId())) {
            return "<red>不能竞拍自己的拍品。";
        }
        if (l.hasBidder() && l.bidder().equals(p.getUniqueId())) {
            return "<gray>你已经是最高出价了。";
        }
        if (l.hasBuyout() && amount >= l.buyout()) {
            return "<yellow>出价已达一口价，请直接用 <white>/zhujian auction buy " + id + "</white>";
        }
        long min = minBid(l);
        if (amount < min) {
            return "<red>最低出价 " + min + " 剑币。";
        }
        // 出价即冻结：钱没被系统吃掉，被超越时原路退（REFUND），成交时归卖家（AUCTION）——
        // 所以冻结这笔记 AUCTION_BUY（买家侧），与卖家侧 AUCTION 之差即抽成
        // v2.23.1 修 P1：走 Wallet，coin-sinks=server 时与黑市同账
        var wallet = plugin.wallet();
        if (!wallet.take(p, amount, CoinReason.AUCTION_BUY)) {
            return "<red>剑币不足，出价需要 " + amount + " 剑币。";
        }
        // v2.23.1 修 P0：买家扣款先同步落盘，再改单据。反过来的顺序（先落单据）
        // 一旦崩溃就是「买家占了最高价却没付钱」，结算时卖家凭空收到钱 = 造币。
        commitMoneyNow();
        UUID prev = l.hasBidder() ? l.bidder() : null;
        String prevName = l.bidderName();
        long prevPrice = l.price();
        l.setBidder(p.getUniqueId(), p.getName());
        l.price(amount);
        l.bids(l.bids() + 1);
        // 单据（新的最高出价者）是权威，紧跟着同步落盘；
        // 崩溃在「钱已落、单未落」= 买家白付一次（漏），不会出现二次结算
        store.put(l);
        commitStoreNow();
        if (prev != null) {
            // 退旧买家：此时单据已经认定出新赢家，退款失败最多是「旧买家漏退」
            if (!wallet.giveTo(prev, prevName, prevPrice, CoinReason.REFUND)) {
                warnLost("旧买家退款", id);
            }
            commitMoneyNow();
        }
        if (prev != null) {
            notifyPlayer(prev, "<yellow>你在 #" + id + " 的出价被超越，" + prevPrice + " 剑币已退还。");
        }
        return "<green>出价 " + amount + " 剑币成功，你现在是最高价。";
    }

    /** 快速出价：按当前最低加价出一口 */
    public String quickBid(Player p, String id) {
        AuctionListing l = store.get(id);
        if (l == null) {
            return "<red>这件拍品不存在或已结束。";
        }
        return bid(p, id, minBid(l));
    }

    // ------------------------------------------------------------ 一口价

    public String buyout(Player p, String id) {
        AuctionListing l = store.get(id);
        if (l == null) {
            return "<red>这件拍品不存在或已结束。";
        }
        if (l.expired(System.currentTimeMillis())) {
            return "<red>这件拍品已到期，等待结算。";
        }
        if (l.seller().equals(p.getUniqueId())) {
            return "<red>不能买自己的拍品。";
        }
        if (!l.hasBuyout()) {
            return "<red>这件没有设一口价，请用出价。";
        }
        boolean selfTop = l.hasBidder() && l.bidder().equals(p.getUniqueId());
        long cost = l.buyout() - (selfTop ? l.price() : 0);
        var wallet = plugin.wallet();
        if (wallet.coins(p) < cost) {
            return "<red>剑币不足，一口价还需 " + cost + " 剑币。";
        }
        // 先扣钱、再结案：v2.19.1 修。旧顺序是「先 finish 再 takeCoins」，
// 一旦 takeCoins 失败就只打一条 warning，然后照样给卖家打款、给次高出价退款——
// 等于凭空造币（买家和系统谁都没出这笔钱）。现在扣不到钱就不成交。
        // 买家付货款：这笔记账是「钱从买家走到卖家」，不是系统销毁，
        // 所以记 AUCTION_BUY（卖家侧入账记 AUCTION，两行之差就是隐式抽成）
        // v2.23.1 修 P1：走 Wallet，与黑市/签到同一本账
        if (!wallet.take(p, cost, CoinReason.AUCTION_BUY)) {
            return "<red>扣款失败，一口价没有成交，请稍后再试。";
        }
        // v2.23.1 修 P0：买家扣款立即同步落盘，再改单据；顺序颠倒会在崩溃时
        // 留下「货已进邮箱、钱没扣」的白拿路径
        commitMoneyNow();
        if (!store.finish(id, p.getUniqueId(), l.item())) {
            // 竞态：这一瞬间被别的路径结掉了，钱原路退回
            if (!wallet.giveTo(p.getUniqueId(), p.getName(), cost, CoinReason.REFUND)) {
                warnLost("一口价竞态退款", id);
            }
            commitMoneyNow();
            return "<red>这件拍品刚刚被结掉了，剑币已退回。";
        }
        // 单据落盘（挂单消失 + 货进买家邮箱）是权威：此刻崩溃 = 卖家漏收货款（可人工补），
        // 绝不会出现「挂单还在、重启再结一次」的二次收款
        commitStoreNow();
        if (l.hasBidder() && !selfTop) {
            if (!wallet.giveTo(l.bidder(), l.bidderName(), l.price(), CoinReason.REFUND)) {
                warnLost("次高出价退款", id);
            }
            notifyPlayer(l.bidder(), "<yellow>#" + id + " 被一口价买走了，你的出价 " + l.price() + " 剑币已退还。");
        }
        long gain = proceeds(l.buyout());
        if (!wallet.giveTo(l.seller(), l.sellerName(), gain, CoinReason.AUCTION)) {
            warnLost("卖家货款", id);
        }
        commitMoneyNow();
        notifyPlayer(l.seller(), "<green>你的拍品 #" + id + " 被一口价买走，到手 " + gain + " 剑币。");
        return "<green>一口价成交！拍品已进入你的邮箱，用 <white>/zhujian auction claim</white> 领取。";
    }

    // ------------------------------------------------------------ 下架 / 领取

    public String cancel(Player p, String id) {
        AuctionListing l = store.get(id);
        if (l == null) {
            return "<red>这件拍品不存在或已结束。";
        }
        if (!l.seller().equals(p.getUniqueId())) {
            return "<red>这不是你的拍品。";
        }
        if (l.hasBidder()) {
            return "<red>已经有人出价，不能下架。";
        }
        if (!store.finish(id, p.getUniqueId(), l.item())) {
            return "<red>这件拍品刚刚被结掉了。";
        }
        // v2.23.1 修 P0：下架是纯归属变更，同样要立刻同步落盘，
        // 否则「货已进邮箱但磁盘上还在售」→ 重启后可再卖一次
        commitStoreNow();
        return "<green>#" + id + " 已下架，物品进入你的邮箱。";
    }

    /**
     * 领取邮箱里全部物品。
     *
     * <p><b>原来会怎样</b>：先 {@code takeMail}（内存清空、只标脏）再往背包塞。
     * auction.yml 是 5 秒异步冲刷，这段窗口里崩溃 → 磁盘上邮箱还是满的，
     * 重启后再领一次 = 物品凭空多一份。</p>
     *
     * <p><b>现在为什么不会</b>：顺序反过来——先把能塞的都塞进背包，
     * 把塞不下的留在列表里，然后 {@code replaceMail} 写回内存并<b>立即同步落盘</b>。
     * 这条路径不再享受 5 秒窗口，任何时刻崩溃都只有两种状态：
     * 「一件都没领」（物品还在邮箱）或「已领走的已经从邮箱扣掉」。
     * 背包满了宁可让玩家看到「邮箱里还剩几件」，也绝不复制。</p>
     */
    public String claim(Player p) {
        List<ItemStack> items = store.mailOf(p.getUniqueId());
        if (items.isEmpty()) {
            return "<gray>邮箱是空的。";
        }
        List<ItemStack> remain = new java.util.ArrayList<>();
        int moved = 0;
        for (ItemStack it : items) {
            var leftover = p.getInventory().addItem(it.clone());
            if (leftover.isEmpty()) {
                moved++;
                continue;
            }
            // 这一件没塞进去（或只塞进去一部分）：剩下的原样留在邮箱，不清该条
            remain.addAll(leftover.values());
            moved++;
        }
        store.replaceMail(p.getUniqueId(), remain);
        // 同步落盘：先扣邮箱、后算「领到几件」——顺序反了就是复制
        store.save();
        if (remain.isEmpty()) {
            return "<green>已领取 " + moved + " 件物品。";
        }
        return "<green>已领取 " + moved + " 件物品</green><gray>，背包放不下的 "
                + remain.size() + " 件仍留在邮箱里（清出空位再来领）。</gray>";
    }

    // ------------------------------------------------------------ 到期结算

    /**
     * 结算到期拍品：有人出价 → 卖家收款（扣抽成）、赢家收货；无人出价 → 退回卖家邮箱。
     *
     * <p>v2.23.1 修 P0：这里原来只标脏，单据（auction.yml，5 秒）和剑币（data.yml，5 分钟）
     * 各自异步落盘。若 data.yml 先落、auction.yml 后落且中途崩溃，重启后挂单仍在 →
     * 下一轮再结一次 → 卖家二次收款（凭空造币）。现在改成
     * 「finish（移除挂单+货进邮箱）→ 同步落 auction.yml → 打款 → 同步落 data.yml」，
     * 崩溃只会漏发卖家货款（有 warning 留痕），不会重复结算。</p>
     */
    public void settleExpired() {
        long now = System.currentTimeMillis();
        var wallet = plugin.wallet();
        for (AuctionListing l : store.all()) {
            if (!l.expired(now)) {
                continue;
            }
            if (l.hasBidder()) {
                // 先结案再发钱，防崩溃后重结算时卖家二次收款。
                // v2.19.1：finish 现在返回「这张单确实是本次结掉的」，结不掉就一分钱都不发
                // v2.23.1：finish 之后立刻同步落盘，把「单据已结」变成磁盘事实
                if (!store.finish(l.id(), l.bidder(), l.item())) {
                    continue;
                }
                commitStoreNow();
                long gain = proceeds(l.price());
                if (!wallet.giveTo(l.seller(), l.sellerName(), gain, CoinReason.AUCTION)) {
                    warnLost("流拍/成交货款", l.id());
                }
                commitMoneyNow();
                notifyPlayer(l.seller(), "<green>你的拍品 #" + l.id() + " 以 " + l.price()
                        + " 成交，到手 " + gain + " 剑币。");
                notifyPlayer(l.bidder(), "<green>你以 " + l.price() + " 拍下 #" + l.id()
                        + "，用 <white>/zhujian auction claim</white> 领取。");
            } else {
                if (!store.finish(l.id(), l.seller(), l.item())) {
                    continue;
                }
                // 流拍只是改归属（货退卖家邮箱），同样同步落盘，避免「货在两处」
                commitStoreNow();
                notifyPlayer(l.seller(), "<yellow>拍品 #" + l.id() + " 流拍，已退回你的邮箱。");
            }
        }
    }

    /** 回档前强制落盘（BackupManager 用）：磁盘上的 auction.yml 是唯一真相 */
    public void flushStore() {
        store.save();
    }

    /** 回档后按磁盘重建内存（BackupManager 用） */
    public void reloadStore() {
        store.load();
    }

    // ------------------------------------------------------------ 界面

    public Inventory buildBrowse(Player p) {
        AuctionHolder holder = new AuctionHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Msg.mm("<dark_gray>拍卖行 <dark_gray>| <#ffd166>在售拍品"));
        holder.inventory(inv);

        List<AuctionListing> list = new ArrayList<>(store.all());
        list.sort(Comparator.comparingLong(AuctionListing::endsAt));
        long now = System.currentTimeMillis();
        int shown = Math.min(LIST_SIZE, list.size());
        for (int i = 0; i < shown; i++) {
            AuctionListing l = list.get(i);
            inv.setItem(i, listingIcon(l, now));
            holder.bind(i, l.id());
        }
        for (int i = LIST_SIZE; i < 54; i++) {
            inv.setItem(i, filler());
        }
        int mails = store.mailOf(p.getUniqueId()).size();
        inv.setItem(SLOT_MAIL, icon(Material.CHEST, "<#7ef2c8>待领取邮箱",
                List.of("<gray>待领物品: <white>" + mails + " 件", "<dark_gray>点击领取全部")));
        inv.setItem(SLOT_CLOSE, icon(Material.BARRIER, "<red>关闭", List.of()));
        inv.setItem(SLOT_REFRESH, icon(Material.CLOCK, "<#7ef2c8>刷新",
                List.of("<gray>在售 <white>" + list.size() + "</white> 件"
                        + (list.size() > LIST_SIZE ? "（仅显示前 " + LIST_SIZE + " 件）" : ""))));
        return inv;
    }

    private ItemStack listingIcon(AuctionListing l, long now) {
        ItemStack show = l.item().clone();
        ItemMeta meta = show.getItemMeta();
        if (meta == null) {
            return show;
        }
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(line(""));
        lore.add(line("<dark_gray>—— 拍卖 #" + l.id() + " ——"));
        lore.add(line("<gray>卖家: <white>" + l.sellerName()));
        lore.add(line("<gray>当前价: <gold>" + l.price() + " 剑币"
                + (l.hasBidder() ? " <dark_gray>(" + l.bidderName() + ")" : "")));
        lore.add(line("<gray>一口价: " + (l.hasBuyout() ? "<gold>" + l.buyout() + " 剑币" : "<dark_gray>无")));
        lore.add(line("<gray>出价次数: <white>" + l.bids()));
        lore.add(line("<gray>剩余: <white>" + Msg.fmt(Math.max(0L, l.endsAt() - now) / 3_600_000.0, 1) + " 小时"));
        // v2.23 切片 5：战力分与推荐价（买家最需要的一行「这东西值不值」）
        long power = com.forgemaster.score.PowerScore.of(plugin, l.item());
        if (power > 0) {
            long suggest = com.forgemaster.score.PowerScore.suggestPrice(plugin, l.item());
            lore.add(line("<dark_gray>—— 战力 <#ffd166>" + power + "</#ffd166> · "
                    + com.forgemaster.score.PowerScore.gradeOf(plugin, power) + " ——"));
            if (suggest > 0) {
                lore.add(line("<gray>参考价: <white>" + suggest + " 剑币</white>"
                        + (l.price() > suggest * 2 ? " <#ffa8a8>（当前价偏高）</#ffa8a8>"
                        : l.price() < suggest / 2 ? " <#8ce99a>（当前价偏低）</#8ce99a>" : "")));
            }
        }
        lore.add(line("<yellow>左键 出价 " + minBid(l) + " 剑币 · 右键 一口价"));
        lore.add(line("<dark_gray>Shift+左键 下架（自己的、无人出价时）"));
        meta.lore(lore);
        show.setItemMeta(meta);
        return show;
    }

    // ------------------------------------------------------------ 小工具

    private String newId() {
        while (true) {
            String hex = Integer.toHexString(random.nextInt(0xFFFFFF)).toUpperCase();
            while (hex.length() < 6) {
                hex = "0" + hex;
            }
            if (store.get(hex) == null) {
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

    private static ItemStack filler() {
        ItemStack it = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(line("<dark_gray>·"));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack icon(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) {
            return it;
        }
        meta.displayName(line(name));
        List<Component> lines = new ArrayList<>();
        for (String l : lore) {
            lines.add(line(l));
        }
        meta.lore(lines);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    private static Component line(String mini) {
        return Msg.mm(mini).decoration(TextDecoration.ITALIC, false);
    }
}
