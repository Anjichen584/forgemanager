package com.forgemaster.econ;

import com.forgemaster.ForgingMasterPlugin;
import org.bukkit.entity.Player;

/**
 * 「花哪本账」的统一收口。
 *
 * <p>插件里其实有两本账，语义必须分开，混用会同时出现两种错：
 * 「明明有币却提示不足」和「白扣服务器钱」。</p>
 *
 * <ul>
 *   <li><b>剑币</b>（{@link com.forgemaster.stats.StatsStore}）：插件自己的硬通货。
 *       锻造奖励、委托、大赛、拍卖、强化、蜕变、宝石、黑市、签到、剑冢、行会金库……
 *       全部记在这一本。README「三、货币」写死：剑币是本插件唯一货币。</li>
 *   <li><b>服务器经济</b>（{@link EconomyBridge}）：Vault / CMI / 内置剑币三态。
 *       目前只有「锻造费」按设计走它（README「锻造费走服务器经济（v2.1）」）。</li>
 * </ul>
 *
 * <p><b>为什么要有这个类</b>：v2.18 新增的一批模块（宝石 / 黑市 / 签到 / 重铸）直接调了
 * {@code EconomyBridge}。服上装了 Vault 时，玩家锻造赚的是<em>剑币</em>，
 * 这些功能却在扣<em>服务器金币</em>——两本账谁都花不出去，表现为「有币却买不起」。
 * 收口到 {@link Wallet} 之后，同类消费一律走同一本账，改口径只需要动一个配置项。</p>
 *
 * <p>口径开关：{@code economy.coin-sinks}
 * <ul>
 *   <li>{@code plugin}（默认）——按 README 走剑币，与拍卖行 / 商会 / 强化一致。</li>
 *   <li>{@code server}——改走服务器经济，适合「想让宝石也吃 Vault 金币」的服。</li>
 * </ul>
 * 注意 {@code 内置剑币} 后端下两条路完全等价（{@link EconomyBridge} 的 COINS 分支就是
 * {@code StatsStore}），所以没装经济的单人服不受影响。</p>
 */
public final class Wallet {

    /** 消费口径：走内置剑币 */
    public static final String SINKS_PLUGIN = "plugin";
    /** 消费口径：走服务器经济（Vault / CMI / 内置兜底） */
    public static final String SINKS_SERVER = "server";

    private final ForgingMasterPlugin plugin;

    public Wallet(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 这批消费是否该走服务器经济。
     *
     * <p>每次现读配置，{@code /zhujian reload} 之后立刻生效，不用重启。</p>
     */
    public boolean serverSinks() {
        String raw = plugin.settings() == null ? SINKS_PLUGIN
                : plugin.settings().str("economy.coin-sinks", SINKS_PLUGIN);
        return SINKS_SERVER.equalsIgnoreCase(raw == null ? "" : raw.trim());
    }

    /** 当前口径的名字，给提示文案 / 自检用 */
    public String sinkLabel() {
        return serverSinks() ? "服务器经济" : "剑币";
    }

    /** 余额（按当前消费口径读同一本账） */
    public long coins(Player player) {
        if (player == null) {
            return 0;
        }
        if (serverSinks()) {
            EconomyBridge eco = plugin.economy();
            if (eco != null) {
                return (long) Math.floor(eco.balance(player));
            }
        }
        return plugin.stats().coins(player.getUniqueId());
    }

    /** 够不够（不扣） */
    public boolean has(Player player, long amount) {
        return amount <= 0 || coins(player) >= amount;
    }

    /** 扣费。成功 true；不足或接口异常 false。amount ≤ 0 视为免费并返回 true。 */
    public boolean take(Player player, long amount) {
        return take(player, amount, CoinReason.OTHER_OUT);
    }

    /** 扣费并记流水理由（v2.20 看板用）；走服务器经济时理由只进日志不影响钱包 */
    public boolean take(Player player, long amount, CoinReason reason) {
        if (amount <= 0) {
            return true;
        }
        if (player == null) {
            return false;
        }
        if (serverSinks()) {
            EconomyBridge eco = plugin.economy();
            if (eco != null) {
                return eco.withdraw(player, amount);
            }
        }
        return plugin.stats().takeCoins(player.getUniqueId(), amount, reason);
    }

    /**
     * 退费 / 发钱。
     *
     * <p>退费必须和当初的扣费走同一本账，否则会出现「扣了金币退成剑币」
     * 这种凭空造币的漏洞——所以这里复用 {@link #take} 的同一套判定。</p>
     */
    public boolean give(Player player, long amount) {
        return give(player, amount, CoinReason.OTHER_IN);
    }

    /** 发钱/退费并记流水理由 */
    public boolean give(Player player, long amount, CoinReason reason) {
        if (amount <= 0) {
            return true;
        }
        if (player == null) {
            return false;
        }
        if (serverSinks()) {
            EconomyBridge eco = plugin.economy();
            if (eco != null) {
                return eco.deposit(player, amount);
            }
        }
        plugin.stats().addCoins(player.getUniqueId(), amount, reason);
        return true;
    }

    /** 金额文案，跟着口径走（剑币显示「N 币」，服务器经济交给 Vault 格式化） */
    public String format(long amount) {
        if (serverSinks()) {
            EconomyBridge eco = plugin.economy();
            if (eco != null) {
                return eco.format(amount);
            }
        }
        return CoinRules.fmt(amount);
    }

    // ------------------------------------------------------------ v2.23.1 修 P1：双账本收敛（离线入口）

    /*
     * 原来会怎样：拍卖成交款、订单悬赏、行会金库退款这些「对方可能离线」的路径，
     * 调用方拿不到 Player，只能直接写 plugin.stats().addCoins(...)。于是 coin-sinks
     * 配成 server 后：黑市/签到/通行证扣 Vault 金币，而拍卖/订单仍按剑币收付——
     * 同一个玩家在两本账上各有余额，表现为「有币买不起」和「卖了东西收不到钱」。
     *
     * 现在为什么不会：把「这笔钱记在哪本账」的判断收敛到本类，补一对按 UUID 收发的入口
     * （剑币只需要 UUID；Vault/CMI 只需要玩家名，所以额外吃一个离线名字）。
     * 各模块不用再自己判口径，一律走 Wallet。
     */

    /** 当前生效账本的名字，给日志与自检用（"server:<后端>" / "plugin:coin"） */
    public String ledgerName() {
        if (!serverSinks()) {
            return "plugin:coin";
        }
        EconomyBridge eco = plugin.economy();
        if (eco == null || !eco.external()) {
            // coin-sinks=server 但没接外部经济：EconomyBridge 自己就退化成剑币，两路等价
            return "server:" + (eco == null ? "none" : "coins");
        }
        return "server:" + eco.backend().name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 给不在线的玩家发钱 / 退费（按口径写对应的账）。
     *
     * <p>顺序与失败处理：写账只有一步，不存在「先给东西后记账」。外部经济打款失败时
     * 退到剑币并打一条 warning——宁可落到另一本账并留痕，也不让这笔钱凭空消失。
     * 口径被 reload 切换的极端情况下最多是「退错账」，不会丢钱、不会造币。</p>
     *
     * @param name 离线玩家名（Vault/CMI 用；剑币路径不需要，可为 null）
     * @return 是否真的发出去了；false 时调用方应记录，等待人工补
     */
    public boolean giveTo(java.util.UUID uuid, String name, long amount, CoinReason reason) {
        if (amount <= 0) {
            return true;
        }
        if (uuid == null) {
            return false;
        }
        CoinReason r = reason == null ? CoinReason.OTHER_IN : reason;
        if (serverSinks()) {
            EconomyBridge eco = plugin.economy();
            if (eco != null && eco.external()) {
                String n = name == null || name.isBlank() ? lookupName(uuid) : name;
                if (n != null && eco.depositByName(n, amount)) {
                    return true;
                }
                plugin.getLogger().warning("外部经济打款失败（" + (n == null ? uuid : n) + " / "
                        + amount + "），已改记剑币兜底：" + r.key());
            }
        }
        plugin.stats().addCoins(uuid, amount, r);
        return true;
    }

    /**
     * 从不在线的玩家扣钱（目前只有拍卖挂单费/托管的对账分支会用到）。
     *
     * <p>余额不足返回 false 且不扣；剑币路径读 StatsStore 的实时余额，
     * 与 {@link #take(Player, long, CoinReason)} 同一套判定。</p>
     */
    public boolean takeFrom(java.util.UUID uuid, String name, long amount, CoinReason reason) {
        if (amount <= 0) {
            return true;
        }
        if (uuid == null) {
            return false;
        }
        CoinReason r = reason == null ? CoinReason.OTHER_OUT : reason;
        if (serverSinks()) {
            EconomyBridge eco = plugin.economy();
            if (eco != null && eco.external()) {
                String n = name == null || name.isBlank() ? lookupName(uuid) : name;
                if (n != null && eco.withdrawByName(n, amount)) {
                    return true;
                }
                // 外部经济余额不足 / 查不到人：不再回退到剑币，避免跨账重复扣款
                return false;
            }
        }
        return plugin.stats().takeCoins(uuid, amount, r);
    }

    /** 离线玩家名解析；查不到返回 null（不阻塞、不建档案） */
    private String lookupName(java.util.UUID uuid) {
        try {
            return org.bukkit.Bukkit.getOfflinePlayer(uuid).getName();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}