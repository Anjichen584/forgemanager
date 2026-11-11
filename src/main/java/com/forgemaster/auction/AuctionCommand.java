package com.forgemaster.auction;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.util.Msg;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * /zhujian auction 子命令入口。
 * 用法：auction [浏览] | sell <起拍价> [一口价|0] [小时] | bid <id> <金额> | buy <id> | cancel <id> | claim
 */
public final class AuctionCommand {

    private AuctionCommand() {
    }

    public static void handle(ForgingMasterPlugin plugin, CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>拍卖行要在游戏里使用。</red>");
            return;
        }
        AuctionManager am = plugin.auction();
        if (!am.enabled()) {
            Msg.prefix(p, "<red>拍卖行没开。</red>");
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "", "browse", "list" -> {
                p.openInventory(am.buildBrowse(p));
                p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 1.2f);
            }
            case "sell" -> {
                if (args.length < 3) {
                    usage(p);
                    return;
                }
                long start = parse(args[2], -1);
                long buyout = args.length >= 4 ? parse(args[3], -1) : 0;
                long hours = args.length >= 5 ? parse(args[4], -1) : am.defaultHours();
                if (start < 0 || buyout < 0 || hours <= 0) {
                    Msg.prefix(p, "<red>数字格式不对。</red>");
                    return;
                }
                Msg.prefix(p, am.sell(p, start, buyout, (int) hours));
            }
            case "bid" -> {
                if (args.length < 4) {
                    usage(p);
                    return;
                }
                long amount = parse(args[3], -1);
                if (amount <= 0) {
                    Msg.prefix(p, "<red>出价金额不对。</red>");
                    return;
                }
                Msg.prefix(p, am.bid(p, args[2], amount));
            }
            case "buy" -> {
                if (args.length < 3) {
                    usage(p);
                    return;
                }
                Msg.prefix(p, am.buyout(p, args[2]));
            }
            case "cancel" -> {
                if (args.length < 3) {
                    usage(p);
                    return;
                }
                Msg.prefix(p, am.cancel(p, args[2]));
            }
            case "claim" -> Msg.prefix(p, am.claim(p));
            default -> usage(p);
        }
    }

    private static void usage(Player p) {
        Msg.send(p, "<dark_gray>──── <#ffd166>拍卖行</#ffd166> ────");
        Msg.send(p, "<white>/zhujian auction <gray>— 打开拍卖行（左键出价 · 右键一口价）");
        Msg.send(p, "<white>/zhujian auction sell <起拍价> [一口价|0] [小时] <gray>— 挂手上这把");
        Msg.send(p, "<white>/zhujian auction bid <id> <金额> <gray>— 出价");
        Msg.send(p, "<white>/zhujian auction buy <id> <gray>— 一口价");
        Msg.send(p, "<white>/zhujian auction cancel <id> <gray>— 下架（无人出价时）");
        Msg.send(p, "<white>/zhujian auction claim <gray>— 领取邮箱里的物品");
    }

    private static long parse(String raw, long fallback) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
