package com.forgemaster.command;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.forge.ForgeManager;
import com.forgemaster.forge.ForgeSession;
import com.forgemaster.gui.ForgeMenu;
import com.forgemaster.affix.Affix;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.quality.Quality;
import com.forgemaster.stats.StatsStore;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;
import java.util.UUID;

public final class ForgingCommand implements CommandExecutor, TabCompleter {

    private final ForgingMasterPlugin plugin;

    public ForgingCommand(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                Msg.send(sender, "<red>控制台只能使用 /" + label + " reload|give|top</red>");
                return true;
            }
            if (!player.hasPermission("forgemaster.use")) {
                Msg.prefix(player, "<red>你没有权限使用锻造台。</red>");
                return true;
            }
            plugin.forge().openMenu(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("forgemaster.admin")) {
                    Msg.prefix(sender, "<red>权限不足。</red>");
                    return true;
                }
                plugin.reloadAll();
                Msg.prefix(sender, "<green>配置已重载："
                        + plugin.materials().all().size() + " 种剑胚 / "
                        + plugin.affixes().all().size() + " 条词条 / "
                        + plugin.quenches().size() + " 种淬火。");
            }
            case "give" -> give(sender, args);
            case "spirit", "jianling" -> plugin.spirit().command(sender, args);
            case "tomb", "jiantomb", "jianzhong" -> plugin.tombs().command(sender, args);
            case "tombrun", "mijing" -> plugin.tombrun().command(sender, args);
            case "achievements", "achv", "chengjiu" -> achievements(sender, args);
            case "top" -> top(sender, args);
            case "stats" -> stats(sender, args);
            case "profile", "dangan" -> profile(sender, args);
            case "rank", "paihang" -> rank(sender, args);
            case "season", "jijie" -> season(sender, args);
            case "codex" -> codex(sender, args);
            case "relicset", "taozhuang" -> relicSet(sender);
            case "collection", "shoucang", "tujian" -> collectionGui(sender);
            case "salvage", "melt" -> salvage(sender);
            case "dissolve", "jiapo", "fenjie" -> dissolve(sender, args);
            case "imprint", "cifu" -> imprint(sender, args);
            case "board", "orders" -> board(sender);
            case "duel", "bishi" -> duel(sender, args);
            case "repair", "yanmo" -> repair(sender);
            case "manual", "manuals", "jianpu" -> manual(sender);
            case "record", "dengji" -> record(sender);
            case "copy", "linmo" -> copy(sender, args);
            case "contest", "dasai" -> contest(sender, args);
            case "grade", "cailiao" -> grade(sender, args);
            case "gem", "baoshi", "baoshitai" -> gem(sender, args);
            case "enchant", "fumo", "fumoshuxiang" -> enchant(sender, args);
            case "strengthen", "qianghua", "duanzao", "duanzaodengji" -> strengthen(sender, args);
            case "hammer", "chongchui", "tiechui" -> hammer(sender);
            case "evolve", "tuibian", "jinhua", "jieduan" -> evolve(sender, args);
            case "contract", "longshui", "shui" -> contract(sender, args);
            case "shangjin", "bounty", "xuanshang" -> shangjin(sender, args);
            case "money", "coins", "qian", "coin" -> money(sender, args);
            case "env" -> env(sender);
            case "story" -> story(sender);
            case "spectrum", "mingjian", "pu" -> spectrum(sender);
            case "pass", "tongxingzheng" -> seasonPass(sender);
            case "halloffame" -> hallOfFame(sender, args);
            case "hall" -> hallShrine(sender, args);
            case "selftest" -> selftest(sender);
            // 战力评分（v2.23 切片 5）：手持剑的分数拆解 + 建议价
            case "power", "zhanli" -> power(sender);
            // 属性修复（v2.23.1）：把被强化倍率滚雪球污染的存量物品收敛回配置值
            case "fixattrs", "xiufu" -> fixAttributes(sender, args);
            case "config", "peizhi" -> configCheck(sender, args);
            // metrics 支持子命令：trend/csv/sample（无参 = v2.20 的实时体检）
            case "metrics", "jingji" -> metrics(sender, args);
            // v2.22.1：手上悬浮剑与护甲体型放大已移除，bigsword 子命令一并下线
            case "givefull", "maxitem", "manpei" -> giveFull(sender, args);
            case "auction", "paimai" -> com.forgemaster.auction.AuctionCommand.handle(plugin, sender, args);
            case "order", "dingdan" -> {
                // 锻造订单（玩家对玩家委托市场）：GUI 浏览 / post 发单 / cancel 取消
                if (plugin.orders() != null) {
                    plugin.orders().handle(sender, args);
                }
            }
            case "master", "shifu" -> {
                // 出师（v2.19）：graduate* 三个子命令走 GraduationService，不进 MentorManager
                String msub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
                if (msub.equals("graduate") || msub.equals("graduate-confirm") || msub.equals("graduates")
                        || msub.equals("queren") || msub.equals("chutu")) {
                    if (plugin.graduations() != null) {
                        plugin.graduations().handle(sender, args);
                    }
                } else if (plugin.mentors() != null) {
                    plugin.mentors().handle(sender, args);
                }
            }
            case "title", "chenghao" -> {
                // 称号扩展（v2.19）：GUI 列出全部已解锁称号，点击佩戴 / 脱下
                if (sender instanceof Player p) {
                    com.forgemaster.title.TitleGui.open(plugin, p);
                } else {
                    Msg.prefix(sender, "<red>换称号要在游戏里操作。</red>");
                }
            }
            case "accept" -> {
                if (plugin.mentors() != null) {
                    plugin.mentors().accept(sender);
                }
            }
            case "guild", "hanghui" -> {
                if (plugin.guilds() != null) {
                    plugin.guilds().handle(sender, args);
                }
            }
            case "shop", "shanghui" -> shop(sender);
            case "blackmarket", "heishi" -> blackmarket(sender);
            case "signin", "qiandao" -> signin(sender);
            case "talent", "tianfu" -> talent(sender, args);
            case "forge", "jixian" -> forgeRisk(sender, args);
            case "event", "shijian" -> worldEvent(sender, args);
            case "backup", "beifen" -> backup(sender);
            case "restore", "huandang" -> restore(sender, args);
            case "help" -> help(sender, label);
            default -> Msg.prefix(sender, "<red>未知子命令，试试 /" + label + " help</red>");
        }
        return true;
    }

    /**
     * {@code /zhujian talent [add <id>|reset [confirm]]}：匠人天赋。
     *
     * <p>点数来自专精等级（每 {@code talent.points-per-level} 级 1 点），七项加起来要 20 点，
     * 60 级只能拿 12 点，所以必须取舍；洗点收剑币，防「先点勤学练级、练满再洗成战斗力」。</p>
     */
    private void talent(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>要在游戏里用。</red>");
            return;
        }
        var store = plugin.stats();
        var st = store.get(p.getUniqueId());
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";

        if (sub.isEmpty() || sub.equals("info") || sub.equals("cha")) {
            talentPanel(p, st);
            return;
        }
        if (sub.equals("add") || sub.equals("dian") || sub.equals("jia")) {
            if (args.length < 3) {
                Msg.prefix(p, "<gray>用法: <white>/" + label() + " talent add <天赋 id></white>");
                Msg.prefix(p, "<gray>可选: " + talentIds());
                return;
            }
            var t = com.forgemaster.growth.Talents.Talent.byName(args[2]);
            if (t == null) {
                Msg.prefix(p, "<red>没有这个天赋，可选: " + talentIds());
                return;
            }
            if (!com.forgemaster.growth.Talents.canAdd(st, t)) {
                int left = com.forgemaster.growth.Talents.remaining(st);
                if (left <= 0) {
                    Msg.prefix(p, "<red>可用天赋点不够（<white>" + left + "</white>）。每 "
                            + com.forgemaster.growth.Talents.pointsPerLevel() + " 级给 1 点。");
                } else {
                    Msg.prefix(p, "<red>「<white>" + t.display() + "</white>」已经点满 "
                            + t.max() + " 点了。");
                }
                return;
            }
            com.forgemaster.growth.Talents.add(st, t);
            store.markDirty();
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.4f);
            Msg.prefix(p, "<green>已点</green> <white>" + t.display() + "</white> <dark_gray>"
                    + com.forgemaster.growth.Talents.levelOf(st, t) + "/" + t.max()
                    + "</dark_gray> <gray>· " + t.effect()
                    + " <dark_gray>· 剩余点数 " + com.forgemaster.growth.Talents.remaining(st));
            return;
        }
        if (sub.equals("reset") || sub.equals("xi") || sub.equals("xidian")) {
            int spent = com.forgemaster.growth.Talents.spent(st);
            if (spent <= 0) {
                Msg.prefix(p, "<gray>你还没点过天赋，没什么可洗的。");
                return;
            }
            long cost = Math.max(0, plugin.settings().longOf("talent.reset-cost", 2000));
            boolean confirm = args.length >= 3
                    && (args[2].equalsIgnoreCase("confirm") || args[2].equalsIgnoreCase("queren"));
            if (!confirm) {
                Msg.prefix(p, "<gray>洗点会退掉全部 <white>" + spent + "</white> 点，花 <white>"
                        + cost + "</white> 剑币。确认请再执行：<white>/" + label()
                        + " talent reset confirm</white>");
                return;
            }
            long coins = store.coins(p.getUniqueId());
            if (coins < cost) {
                Msg.prefix(p, "<red>剑币不够：洗点要 <white>" + cost + "</white>，你只有 <white>"
                        + coins + "</white>。");
                return;
            }
            if (cost > 0 && !store.takeCoins(p.getUniqueId(), cost, com.forgemaster.econ.CoinReason.OTHER_OUT)) {
                Msg.prefix(p, "<red>扣费失败，天赋没有重置。");
                return;
            }
            int n = com.forgemaster.growth.Talents.reset(st);
            store.markDirty();
            Msg.prefix(p, "<green>天赋已重置</green> <dark_gray>· </dark_gray><gray>退回 <white>" + n
                    + "</white> 点，花掉 <white>" + cost + "</white> 剑币。");
            return;
        }
        Msg.prefix(p, "<red>用法：/zhujian talent | talent add <id> | talent reset [confirm]</red>");
    }

    /** 天赋面板：点数、七项进度、当前实际生效的数值 */
    private void talentPanel(Player p, StatsStore.Stats st) {
        int earned = com.forgemaster.growth.Talents.earned(st.level);
        int spent = com.forgemaster.growth.Talents.spent(st);
        int left = com.forgemaster.growth.Talents.remaining(st);
        Msg.send(p, "<dark_gray>──── <gradient:#ffd166:#ff7a18><bold>匠人天赋</bold></gradient> "
                + "<dark_gray>────");
        Msg.send(p, "<gray>专精 <white>Lv." + st.level + "</white> <dark_gray>·</dark_gray> "
                + "<gray>已点 <white>" + spent + "</white><dark_gray>/</dark_gray><white>" + earned
                + "</white> <dark_gray>·</dark_gray> <gray>可用 <white>" + left + "</white>");
        if (st.level >= StatsStore.maxLevel()) {
            Msg.send(p, "<dark_gray>已到等级上限，点数不会再增加 <dark_gray>·</dark_gray> <gray>洗点 "
                    + plugin.settings().longOf("talent.reset-cost", 2000) + " 剑币");
        } else {
            int next = (com.forgemaster.growth.Talents.pointsPerLevel()
                    - st.level % com.forgemaster.growth.Talents.pointsPerLevel())
                    % com.forgemaster.growth.Talents.pointsPerLevel();
            Msg.send(p, "<dark_gray>再升 <white>" + (next == 0
                    ? com.forgemaster.growth.Talents.pointsPerLevel() : next)
                    + "</white> 级得 1 点 <dark_gray>·</dark_gray> <gray>洗点 "
                    + plugin.settings().longOf("talent.reset-cost", 2000) + " 剑币");
        }
        for (var t : com.forgemaster.growth.Talents.Talent.values()) {
            int cur = com.forgemaster.growth.Talents.levelOf(st, t);
            String bar = Msg.bar(cur / (double) t.max(), 10, "#ffd166", "#3a3a3a");
            Msg.send(p, "<white>" + t.display() + "</white> <dark_gray>" + t.id() + "</dark_gray> "
                    + bar + " <gray>" + cur + "/" + t.max() + " <dark_gray>·</dark_gray> <gray>"
                    + t.effect());
        }
        var kit = com.forgemaster.growth.Talents.kit(st);
        Msg.send(p, "<dark_gray>当前合计: <gray>容差 <white>+"
                + Msg.fmt(kit.tolerance() * 100, 0) + "%</white> <dark_gray>·</dark_gray> <gray>命中区 <white>+"
                + kit.zone() + "</white> <dark_gray>·</dark_gray> <gray>叠钢 <white>+"
                + Msg.fmt(kit.foldSeconds(), 1) + "s</white> <dark_gray>·</dark_gray> <gray>开刃 <white>+"
                + Msg.fmt(kit.grindWidth(), 1) + "</white>");
        Msg.send(p, "<dark_gray>　　　　　　<gray>降温 <white>×" + Msg.fmt(kit.heatFactor(), 2)
                + "</white> <dark_gray>·</dark_gray> <gray>额外词条 <white>+"
                + Msg.fmt(kit.affixChance() * 100, 0) + "%</white> <dark_gray>·</dark_gray> <gray>经验 <white>×"
                + Msg.fmt(kit.expMultiplier(), 2) + "</white>");
        Msg.send(p, "<gray>加点: <white>/" + label() + " talent add <id></white> <dark_gray>·</dark_gray> <gray>可选: "
                + talentIds());
    }

    private String talentIds() {
        StringBuilder sb = new StringBuilder();
        for (var t : com.forgemaster.growth.Talents.Talent.values()) {
            if (sb.length() > 0) {
                sb.append("<dark_gray>/</dark_gray>");
            }
            sb.append("<white>").append(t.id()).append("</white>");
        }
        return sb.toString();
    }

    private void give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("forgemaster.admin")) {
            Msg.prefix(sender, "<red>权限不足。</red>");
            return;
        }
        if (args.length < 3) {
            Msg.prefix(sender, "<gray>用法: /zhujian give <玩家> <剑胚id> [品质] [分数]</gray>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            Msg.prefix(sender, "<red>找不到在线玩家 " + args[1] + "</red>");
            return;
        }
        ForgeMaterial material = plugin.materials().get(args[2]);
        if (material == null) {
            // 装备不占材料表条目，是「剑胚 + 部位」现算出来的。
            // 这里补一条 armor-<剑胚id>-<部位> 的解析，方便管理员直接发装备。
            material = resolveArmorMaterial(args[2]);
        }
        if (material == null) {
            Msg.prefix(sender, "<red>未知剑胚: " + args[2] + "</red>");
            Msg.prefix(sender, "<gray>装备写法：<white>armor-<剑胚>-<部位></white>，"
                    + "例如 <white>armor-iron-chestplate</white>"
                    + "（部位：helmet / chestplate / leggings / boots）");
            return;
        }
        Quality quality = args.length >= 4 ? Quality.byName(args[3]) : Quality.LEGENDARY;
        if (quality == null) {
            Msg.prefix(sender, "<red>未知品质: " + args[3] + "</red>");
            return;
        }
        double score = args.length >= 5 ? parseDouble(args[4], 90) : Math.max(quality.minScore() + 2, 60);
        var item = plugin.items().buildDirect(target.getName(), material, quality, score);
        Map<Integer, org.bukkit.inventory.ItemStack> left = target.getInventory().addItem(item);
        for (var rest : left.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), rest);
        }
        Msg.prefix(sender, "<green>已给 " + target.getName() + " 生成一把 " + quality.colored() + " <gray>锻造品。</gray>");
    }

    /**
     * 解析 {@code armor-<剑胚id>-<部位>} 形式的装备 id。
     *
     * <p>装备胚是 {@link com.forgemaster.material.ArmorCatalog#create} 现算出来的，
     * 不在 {@code materials} 表里，所以 {@code /zhujian give} 默认查不到。
     * 需要按 id 直接发装备时走这里。</p>
     *
     * @return 装备胚；格式不对或该档没有对应护甲材质时返回 null
     */
    private ForgeMaterial resolveArmorMaterial(String raw) {
        if (raw == null) {
            return null;
        }
        String id = raw.toLowerCase(Locale.ROOT);
        if (!id.startsWith("armor-")) {
            return null;
        }
        // armor-<base>-<slot>：剑胚 id 里本身可能带连字符，所以从右边切
        int dash = id.lastIndexOf('-');
        if (dash <= 6) {
            return null;
        }
        String baseId = id.substring(6, dash);
        String slotName = id.substring(dash + 1);
        ForgeMaterial base = plugin.materials().get(baseId);
        if (base == null) {
            return null;
        }
        for (ForgeMaterial.Slot slot : ForgeMaterial.Slot.values()) {
            if (slot.name().equalsIgnoreCase(slotName)) {
                return com.forgemaster.material.ArmorCatalog.create(base, slot,
                        plugin.settings().num("armor.price-multiplier", 1.0));
            }
        }
        return null;
    }

    private void achievements(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Msg.send(sender, "<red>只能在游戏内查看成就。</red>");
            return;
        }
        if (!plugin.achievements().enabled()) {
            Msg.prefix(p, "<gray>成就系统未开启。</gray>");
            return;
        }
        new com.forgemaster.growth.AchvMenu(plugin).open(p, 0);
    }

    private void top(CommandSender sender, String[] args) {
        // /zhujian top legacy —— 历代名剑（赛季传承）
        if (args.length >= 2) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            if ("legacy".equals(sub) || "lishi".equals(sub) || "chuancheng".equals(sub)) {
                var seasons = plugin.seasons();
                var legacy = seasons == null ? null : seasons.legacy();
                if (legacy == null || !legacy.hasAny()) {
                    Msg.prefix(sender, "<gray>还没有赛季结算过，暂时没有历代名剑。</gray>");
                    return;
                }
                Msg.send(sender, "<dark_gray>──────── <gradient:#ffd166:#ff7a18><bold>历代名剑</bold></gradient> <dark_gray>────────");
                for (String line : legacy.lines(0)) {
                    Msg.send(sender, line);
                }
                return;
            }
        }
        List<Map.Entry<UUID, StatsStore.Stats>> list = plugin.stats().top(10);        if (list.isEmpty()) {
            Msg.prefix(sender, "<gray>还没有人锻出过东西。</gray>");
            return;
        }
        Msg.send(sender, "<dark_gray>──────── <gradient:#ffd166:#ff7a18><bold>锻造名人堂</bold></gradient> <dark_gray>────────");
        int rank = 1;
        for (Map.Entry<UUID, StatsStore.Stats> e : list) {
            StatsStore.Stats st = e.getValue();
            Quality q = Quality.byName(st.bestQuality);
            String qName = q == null ? "<gray>—" : q.colored();
            Msg.send(sender, "<gray>" + rank + ". <white>" + (st.name.isEmpty() ? e.getKey().toString().substring(0, 8) : st.name)
                    + " <dark_gray>|</dark_gray> " + Msg.fmt(st.bestScore, 1) + " 分 <dark_gray>|</dark_gray> "
                    + qName + " <dark_gray>|</dark_gray> 共 " + st.forged + " 次");
            rank++;
        }
    }

    private void stats(CommandSender sender, String[] args) {
        OfflinePlayer target;
        if (args.length >= 2) {
            if (!sender.hasPermission("forgemaster.admin")) {
                Msg.prefix(sender, "<red>只有管理员能查别人。</red>");
                return;
            }
            target = Bukkit.getOfflinePlayer(args[1]);
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            Msg.prefix(sender, "<red>控制台请指定玩家名。</red>");
            return;
        }
        StatsStore.Stats st = plugin.stats().get(target.getUniqueId());
        Quality q = Quality.byName(st.bestQuality);
        Msg.send(sender, "<dark_gray>──── <white>锻造档案</white> <dark_gray>────");
        Msg.send(sender, "<gray>玩家: <white>" + (target.getName() == null ? "?" : target.getName()));
        Msg.send(sender, "<gray>锻造次数: <white>" + st.forged);
        Msg.send(sender, "<gray>最高评分: <white>" + Msg.fmt(st.bestScore, 1));
        Msg.send(sender, "<gray>最佳品质: " + (q == null ? "<gray>—" : q.colored()));
        if (!st.byQuality.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            st.byQuality.forEach((k, v) -> {
                Quality qq = Quality.byName(k);
                sb.append(qq == null ? k : qq.colored()).append("<gray>×<white>").append(v).append("<gray>  ");
            });
            Msg.send(sender, "<gray>分布: " + sb);
        }
        Msg.send(sender, "");
        int need = StatsStore.expToNext(st.level);
        Msg.send(sender, "<gold>专精: <white>Lv." + st.level + " <dark_gray>|</dark_gray> "
                + Msg.fmt(st.exp, 0) + "/" + need + " 经验");
        Msg.send(sender, Msg.bar((double) st.exp / Math.max(1, need), 24, "#ffd166", "#3a3a3a"));
        int toNext = StatsStore.reputationToNextTitle(st.reputation);
        Msg.send(sender, "<gold>称号: <#ffd166>" + StatsStore.titleOf(st.reputation)
                + " <dark_gray>|</dark_gray> <gray>声望 <white>" + st.reputation
                + (toNext > 0 ? " <dark_gray>（再 " + toNext + " 到下一档）" : " <dark_gray>（已满档）"));
        Msg.send(sender, "<dark_gray>加成：容差 +" + Msg.fmt((StatsStore.toleranceMultiplier(st.level) - 1) * 100, 0)
                + "% · 命中区 +" + StatsStore.extraZoneWidth(st.level)
                + " · 叠钢 +" + Msg.fmt(StatsStore.extraFoldSeconds(st.level), 1) + "s"
                + " · 开刃 +" + Msg.fmt(StatsStore.extraGrindWidth(st.level), 1));
        Msg.send(sender, "<gray>图鉴: <white>" + st.codex.size() + " / "
                + (plugin.materials().all().size() * Quality.values().length) + " <gray>· 钢纹 <white>"
                + st.patterns.size() + " 种 <gray>· 熔炼 <white>" + st.salvaged + " 次");
        Msg.send(sender, "<gray>委托: <white>" + st.commissionsDone + "</white> 完成 <dark_gray>/</dark_gray> <white>"
                + st.commissionsFailed + "</white> 逾期 <dark_gray>·</dark_gray> 比试 <white>"
                + st.duelWins + "</white> 胜 <white>" + st.duelLosses + "</white> 负 <dark_gray>·</dark_gray> 研磨 <white>"
                + st.repaired + "</white> 次");
        if (st.flawless > 0) {
            Msg.send(sender, "<#ffd166>满分之作: <white>" + st.flawless + " 把");
        }
        Msg.send(sender, "<dark_gray>/zhujian codex 看图鉴 · /zhujian salvage 熔掉手上这把");
        com.forgemaster.ranking.ProfilePanel.show(plugin, sender, target.getUniqueId());
    }

    // ------------------------------------------------------------ 档案面板 / 排行（A4）

    /** /zhujian profile [玩家]：档案面板（剑币 / 连锻 / 声望 / 成就） */
    private void profile(CommandSender sender, String[] args) {
        OfflinePlayer target;
        if (args.length >= 2) {
            if (!sender.hasPermission("forgemaster.admin")) {
                Msg.prefix(sender, "<red>只有管理员能查别人。</red>");
                return;
            }
            target = Bukkit.getOfflinePlayer(args[1]);
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            Msg.prefix(sender, "<red>控制台请指定玩家名。</red>");
            return;
        }
        com.forgemaster.ranking.ProfilePanel.show(plugin, sender, target.getUniqueId());
    }

    /**
     * /zhujian rank [coins|reputation|streak|level]：玩家打开排行 GUI；
     * 控制台或 "text" 参数则输出文字前 10 名。
     */
    private void rank(CommandSender sender, String[] args) {
        com.forgemaster.ranking.Metric metric = com.forgemaster.ranking.Metric.COINS;
        boolean text = !(sender instanceof Player);
        for (int i = 1; i < args.length; i++) {
            if ("text".equalsIgnoreCase(args[i])) {
                text = true;
                continue;
            }
            com.forgemaster.ranking.Metric m = com.forgemaster.ranking.Metric.parse(args[i]);
            if (m == null) {
                Msg.prefix(sender, "<red>未知排行维度: " + args[i] + "</red><gray>（可选 coins / reputation / streak / level）</gray>");
                return;
            }
            metric = m;
        }
        if (text) {
            var rows = com.forgemaster.ranking.Rankings.top(plugin.stats(), metric, 10);
            Msg.send(sender, "<dark_gray>──── <gradient:#ffd166:#ff7a18><bold>" + metric.label() + "排行</bold></gradient> <dark_gray>────");
            if (rows.isEmpty()) {
                Msg.send(sender, "<gray>暂无上榜记录。");
                return;
            }
            for (var r : rows) {
                Msg.send(sender, "<gray>" + r.rank() + ". <white>" + r.name()
                        + " <dark_gray>|</dark_gray> " + metric.display(r.stats()));
            }
            return;
        }
        Player p = (Player) sender;
        if (!plugin.settings().flag("rank.enabled", true)) {
            Msg.prefix(p, "<red>排行功能没开。</red>");
            return;
        }
        p.openInventory(plugin.rankMenu().build(p, metric));
        p.playSound(p.getLocation(), org.bukkit.Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.2f);
    }

    /**
     * /zhujian season：查看当前赛季（所有人）。
     * /zhujian season end：立即结算当季（需要 forgemaster.admin）。
     */
    private void season(CommandSender sender, String[] args) {
        if (args.length >= 2 && "end".equalsIgnoreCase(args[1])) {
            if (!sender.hasPermission("forgemaster.admin")) {
                Msg.prefix(sender, "<red>权限不足。</red>");
                return;
            }
            Msg.prefix(sender, "<gray>正在结算第 " + plugin.seasons().number() + " 季……</gray>");
            Msg.prefix(sender, plugin.seasons().end("管理员手动结算"));
            return;
        }
        for (String line : plugin.seasons().statusLines()) {
            Msg.send(sender, line);
        }
        if (sender.hasPermission("forgemaster.admin")) {
            Msg.send(sender, "<dark_gray>管理员：/zhujian season end 立即结算");
        }
    }

    // ------------------------------------------------------------ 委托板

    private void board(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>委托板要在游戏里打开。</red>");
            return;
        }
        if (!plugin.settings().commissionEnabled()) {
            Msg.prefix(p, "<red>委托功能没开。</red>");
            return;
        }
        p.openInventory(plugin.commissions().buildBoard(p));
        p.playSound(p.getLocation(), org.bukkit.Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.2f);
    }

    // ------------------------------------------------------------ 比试

    private void duel(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>比试要在游戏里发起。</red>");
            return;
        }
        if (!plugin.settings().duelEnabled()) {
            Msg.prefix(p, "<red>比试功能没开。</red>");
            return;
        }
        if (args.length < 2) {
            Msg.prefix(p, "<gray>用法：<white>/zhujian duel <玩家></white> 发起 · "
                    + "<white>/zhujian duel accept</white> 应战 · <white>deny</white> 拒绝");
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        String result;
        switch (sub) {
            case "accept", "yes", "ok" -> result = plugin.duels().accept(p);
            case "deny", "no" -> result = plugin.duels().deny(p);
            default -> {
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    Msg.prefix(p, "<red>找不到在线玩家 " + args[1] + "。</red>");
                    return;
                }
                result = plugin.duels().challenge(p, target);
            }
        }
        if (result != null) {
            Msg.prefix(p, result);
        }
    }

    // ------------------------------------------------------------ 研磨修复

    private void repair(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>研磨要在游戏里做。</red>");
            return;
        }
        plugin.forge().openMenu(p, true);
    }

    private static int safeInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception ex) {
            return fallback;
        }
    }

    // ------------------------------------------------------------ 剑谱
    private void manual(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>剑谱要在游戏里看。</red>");
            return;
        }
        p.openInventory(plugin.manuals().buildList());
        p.playSound(p.getLocation(), org.bukkit.Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 1.2f);
    }

    private void record(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>登记剑谱要在游戏里做。</red>");
            return;
        }
        String result = plugin.manuals().record(p);
        Msg.prefix(p, result);
        // 登记成功后补一句「然后呢」，否则玩家不知道这本谱有什么用
        if (result.contains("已收录")) {
            Msg.prefix(p, "<gray>下一步：任何人拿 <white>/zhujian copy <谱号></white>（或点剑谱库里的书）"
                    + "就能照着重锻一次，随机序列和原谱一致，按相似度给复刻度与声望。");
        }
    }

    private void copy(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>临摹要在游戏里做。</red>");
            return;
        }
        if (args.length < 2) {
            // 无参数时先把当前状态摆出来——很多玩家不知道自己还锁着一本谱
            var active = plugin.manuals().activeCopy(p.getUniqueId());
            if (active != null) {
                Msg.prefix(p, "<#ffd166>你正在临摹《<white>" + active.name() + "</white>》"
                        + "<gray> · 剑胚锁定为 <white>" + active.materialId()
                        + "</white> · 目标 <white>" + Msg.fmt(active.score(), 1) + " 分</white>");
                Msg.prefix(p, "<gray>取消：<white>/zhujian copy stop</white>");
            }
            Msg.prefix(p, "<gray>用法：<white>/zhujian copy <谱号></white> 开始临摹 · "
                    + "<white>/zhujian copy stop</white> 取消 · <white>/zhujian manual</white> 看谱库");
            return;
        }
        if ("stop".equalsIgnoreCase(args[1]) || "cancel".equalsIgnoreCase(args[1])) {
            String r = plugin.manuals().cancelCopy(p.getUniqueId());
            Msg.prefix(p, r == null ? "<gray>你现在没在临摹。" : "<green>" + r);
            return;
        }
        Msg.prefix(p, plugin.manuals().startCopy(p, args[1]));
    }

    // ------------------------------------------------------------ 大赛

    private void contest(CommandSender sender, String[] args) {
        if (!plugin.settings().contestEnabled()) {
            Msg.prefix(sender, "<red>大赛功能没开。</red>");
            return;
        }
        if (args.length >= 2) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            if ("start".equalsIgnoreCase(sub)) {
                if (!sender.hasPermission("forgemaster.admin")) {
                    Msg.prefix(sender, "<red>只有管理员能强开赛。</red>");
                    return;
                }
                Msg.prefix(sender, plugin.contests().forceStart(
                        sender instanceof Player p ? p : null));
                return;
            }
            if ("list".equalsIgnoreCase(sub)) {
                List<String> board = plugin.contests().leaderboard();
                if (board.isEmpty()) {
                    Msg.prefix(sender, "<gray>当前还没有人上榜。");
                    return;
                }
                Msg.send(sender, "<dark_gray>──── <#ffd166>大赛榜</#ffd166> ────");
                for (String line : board) {
                    Msg.send(sender, line);
                }
                return;
            }
            // /zhujian contest watch —— 实时观战面板
            if ("watch".equalsIgnoreCase(sub) || "guanzhan".equalsIgnoreCase(sub)
                    || "kan".equalsIgnoreCase(sub)) {
                if (!(sender instanceof Player p)) {
                    Msg.prefix(sender, "<red>观战面板只能由玩家打开。</red>");
                    return;
                }
                com.forgemaster.contests.ContestWatchGui.open(plugin, p);
                return;
            }
            // /zhujian contest gui —— 赛况面板
            if ("gui".equalsIgnoreCase(sub) || "panel".equalsIgnoreCase(sub)
                    || "mianban".equalsIgnoreCase(sub)) {
                if (!(sender instanceof Player p)) {
                    Msg.prefix(sender, "<red>赛况面板只能由玩家打开。</red>");
                    return;
                }
                com.forgemaster.contests.ContestGui.open(plugin, p);
                return;
            }
        }
        Msg.prefix(sender, plugin.contests().statusLine());
        // 玩家直接敲 /zhujian contest 时，能开面板就直接开（控制台保持纯文本）
        if (args.length < 2 && sender instanceof Player p) {
            com.forgemaster.contests.ContestGui.open(plugin, p);
            return;
        }
        var asking = plugin.contests().materialAsking();
        if (asking != null && plugin.contests().running()) {
            Msg.prefix(sender, "<gray>指定剑胚：<white>" + asking.display()
                    + "</white>（选错会被拦下）");
        }
    }

    // ------------------------------------------------------------ 材料分级

    // ------------------------------------------------------------ 宝石台

    private void gem(CommandSender sender, String[] args) {
        // 管理员子命令先处理：这两条控制台也要能跑（发东西通常在后台上做）
        String sub0 = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (sub0.equals("give") || sub0.equals("fa") || sub0.equals("gei")) {
            gemGive(sender, args);
            return;
        }
        if (sub0.equals("list") || sub0.equals("types")) {
            gemList(sender);
            return;
        }
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>要在游戏里用。</red>");
            return;
        }
        if (plugin.gems() == null) {
            Msg.prefix(p, "<red>宝石系统没有初始化。</red>");
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "socket", "kaikong", "kai" -> plugin.gems().socket(p);
            // 附魔孔（v2.10）：和宝石孔完全分开
            case "esocket", "fumokong", "ekai" -> plugin.gems().socketEnchant(p);
            case "smelt", "ronglian", "lian" -> plugin.gems().smelt(p);
            case "inlay", "xiangqian", "qian" -> plugin.gems().inlay(p);
            case "extract", "chaixie", "qu" -> plugin.gems().extract(p);
            case "merge", "hecheng", "he" -> plugin.gems().merge(p);
            // 管理员发宝石（控制台也能用，所以上面已提前处理，这里只做兜底）
            case "give", "fa", "gei" -> gemGive(sender, args);
            case "list", "types" -> gemList(sender);
            default -> {
                p.openInventory(plugin.forge().menu().buildGemBench(p));
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.4f);
            }
        }
    }

    /** /zhujian gem give <玩家|all> <类型id|random> [等级 L1~L5] [数量] */
    private void gemGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("forgemaster.admin")) {
            Msg.prefix(sender, "<red>权限不足。</red>");
            return;
        }
        if (plugin.gems() == null) {
            Msg.prefix(sender, "<red>宝石系统没有初始化。</red>");
            return;
        }
        if (args.length < 4) {
            Msg.prefix(sender, "<gray>用法：<white>/zhujian gem give <玩家|all> <类型id|random> [L1~L5] [数量]</white>");
            Msg.prefix(sender, "<gray>类型：<white>" + gemTypeIds() + "</white>；<white>/zhujian gem list</white> 看中文名与效果。");
            return;
        }
        java.util.List<Player> targets = new ArrayList<>();
        if (args[2].equalsIgnoreCase("all") || args[2].equals("*")) {
            targets.addAll(Bukkit.getOnlinePlayers());
        } else {
            Player one = Bukkit.getPlayerExact(args[2]);
            if (one == null) {
                Msg.prefix(sender, "<red>找不到在线玩家 " + args[2] + "</red>");
                return;
            }
            targets.add(one);
        }
        if (targets.isEmpty()) {
            Msg.prefix(sender, "<red>没有在线玩家可以发。</red>");
            return;
        }

        com.forgemaster.gem.GemType type;
        if (args[3].equalsIgnoreCase("random") || args[3].equalsIgnoreCase("suiji")) {
            var all = com.forgemaster.gem.GemType.values();
            type = all[java.util.concurrent.ThreadLocalRandom.current().nextInt(all.length)];
        } else {
            type = com.forgemaster.gem.GemType.byId(args[3]);
            if (type == null) {
                // 也认中文名，省得管理員去记 id
                for (var t : com.forgemaster.gem.GemType.values()) {
                    if (t.gemName().equals(args[3])) {
                        type = t;
                        break;
                    }
                }
            }
        }
        if (type == null) {
            Msg.prefix(sender, "<red>未知宝石类型: " + args[3] + "</red>");
            Msg.prefix(sender, "<gray>可用：<white>" + gemTypeIds() + "</white>");
            return;
        }

        com.forgemaster.gem.GemType.Grade grade = com.forgemaster.gem.GemType.Grade.byName(
                args.length >= 5 ? args[4] : "L1");
        int count = args.length >= 6 ? Math.max(1, Math.min(64, parseIntSafe(args[5], 1))) : 1;

        for (Player target : targets) {
            int given = 0;
            for (int i = 0; i < count; i++) {
                org.bukkit.inventory.ItemStack gem = plugin.gems().createGem(type, grade);
                var left = target.getInventory().addItem(gem);
                for (var rest : left.values()) {
                    target.getWorld().dropItemNaturally(target.getLocation(), rest);
                }
                given++;
            }
            Msg.prefix(target, "<green>收到 <white>" + given + "</white> 颗 "
                    + grade.colored() + " <white>" + type.gemName() + "</white>。</green>"
                    + " <gray>主手装备 + 副手宝石 → <white>/zhujian gem inlay</white>");
            if (!target.equals(sender)) {
                Msg.prefix(sender, "<green>已给 <white>" + target.getName() + "</white> "
                        + given + " 颗 " + grade.colored() + " <white>" + type.gemName() + "</white>。");
            }
        }
    }

    /** /zhujian gem list：所有宝石 id 与效果，管理员发东西时对照用 */
    private void gemList(CommandSender sender) {
        Msg.send(sender, "<#ffb84d>宝石类型（<white>id</white> · 中文名 · 效果）：");
        for (var t : com.forgemaster.gem.GemType.values()) {
            Msg.send(sender, "  <white>" + t.id() + "</white> <dark_gray>·</dark_gray> "
                    + "<" + t.color() + ">" + t.gemName() + "</" + t.color() + ">"
                    + " <gray>" + t.unit() + "</gray>");
        }
        Msg.send(sender, "<gray>等级：<white>L1 / L2 / L3 / L4 / L5</white>（也认 COMMON / FINE / EPIC / LEGENDARY）");
        Msg.send(sender, "<gray>发：<white>/zhujian gem give 玩家 attack L5 3</white> · "
                + "<white>/zhujian gem give all random L3 1</white>");
    }

    private String gemTypeIds() {
        StringBuilder sb = new StringBuilder();
        for (var t : com.forgemaster.gem.GemType.values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(t.id());
        }
        return sb.toString();
    }

    private static int parseIntSafe(String raw, int def) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ------------------------------------------------------------ 附魔镶孔

    /**
     * /zhujian enchant
     *
     * <p>主手锻造品 + 副手附魔书 → 占一个孔把附魔镶进去。
     * 附魔与宝石共用孔位。</p>
     */
    private void enchant(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>要在游戏里用。</red>");
            return;
        }
        if (plugin.gems() == null) {
            Msg.prefix(p, "<red>宝石系统没有初始化。</red>");
            return;
        }
        // /zhujian enchant extract = 把最后一颗镶进去的附魔拆成附魔书
        // （这里以前读的是 args[2]，于是 `/zhujian enchant extract` 永远走不到拆分，
        //   反倒是 `/zhujian enchant xxx extract` 才能用——参数下标写错了）
        if (args.length >= 2) {
            String sub = args[1].toLowerCase(java.util.Locale.ROOT);
            if (sub.equals("extract") || sub.equals("qu") || sub.equals("quetu")
                    || sub.equals("qufu") || sub.equals("chai")) {
                plugin.gems().extractEnchant(p);
                return;
            }
        }
        plugin.gems().inlayEnchant(p);
    }

    // ------------------------------------------------------------ 锻造等级

    /**
     * /zhujian strengthen [info|sim]
     *
     * <p>v2.16 起强化搬到铁砧上了：这条命令不再「点一下升一级」，
     * 只负责讲清怎么敲，以及在聊天里把当前这把剑的明牌概率摆出来。</p>
     */
    private void strengthen(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>要在游戏里用。</red>");
            return;
        }
        var service = plugin.strengthen();
        if (service == null) {
            Msg.prefix(p, "<red>锻造等级模块没有初始化。</red>");
            return;
        }
        org.bukkit.inventory.ItemStack item = p.getInventory().getItemInMainHand();
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";

        // /zhujian strengthen merge —— 词条合成（不需要主手拿剑，与 /zhujian imprint merge 同一入口）
        if (sub.equals("merge") || sub.equals("hecheng") || sub.equals("he")) {
            Msg.prefix(p, com.forgemaster.affix.Imprint.merge(plugin, p));
            return;
        }

        // /zhujian strengthen plan <目标等级> [模拟局数] —— 强化规划器（v2.23）
        if (sub.equals("plan") || sub.equals("guihua")) {
            if (item == null || item.getType().isAir() || service.levelOf(item) < 0) {
                Msg.prefix(p, "<red>主手先拿一把锻造品再规划。</red>");
                return;
            }
            int target;
            try {
                target = args.length >= 3 ? Integer.parseInt(args[2]) : -1;
            } catch (NumberFormatException ex) {
                target = -1;
            }
            if (target < 0) {
                Msg.prefix(p, "<gray>用法：<white>/zhujian strengthen plan <目标等级> [模拟局数]</white>"
                        + "<dark_gray> · 例：/zhujian strengthen plan 50</dark_gray>");
                return;
            }
            int runs = 2000;
            if (args.length >= 4) {
                try {
                    runs = Integer.parseInt(args[3]);
                } catch (NumberFormatException ignored) {
                }
            }
            var plan = service.plan(item, target, runs);
            Msg.prefix(p, "<gold>强化规划 +<white>" + plan.fromLevel() + "</white> → +<white>"
                    + plan.targetLevel() + "</white></gold> <dark_gray>(模拟 " + plan.runs() + " 局)</dark_gray>");
            Msg.send(p, "<gray>期望 <white>" + Msg.fmt(plan.expHits(), 1) + "</white> 锤"
                    + " <dark_gray>·</dark_gray> 剑币 <white>" + Msg.fmt(plan.expCoins(), 0)
                    + "</white> <dark_gray>·</dark_gray> 材料 <white>" + Msg.fmt(plan.expMaterials(), 0) + "</white>");
            Msg.send(p, "<gray>期望碎剑 <white>" + Msg.fmt(plan.expBreaks(), 1)
                    + "</white> 把<dark_gray>（碎后按剑魂传承重开）</dark_gray> · 达成率 <white>"
                    + Msg.fmt(plan.reachedRate() * 100, 1) + "%</white>");
            Msg.send(p, "<dark_gray>口径：裸状态（无连击/狂热/符/圣物），实际只会更省；结果按上界读。"
                    + (plan.cappedRuns() > 0 ? " 发散 " + plan.cappedRuns() + " 局未计入达成。" : "")
                    + "</dark_gray>");
            return;
        }

        // 手里这把可能是旧版本打出来的：上限还写着老的按材料档位的数字（神话剑 +0/32）。
        // 这里顺手按品质同步一次，免得下面 describe / odds 全按错的上限算。
        if (item != null && !item.getType().isAir() && service.ensureCap(item)) {
            p.getInventory().setItemInMainHand(item);
        }

        if (item == null || item.getType().isAir() || service.levelOf(item) < 0) {
            if (sub.isEmpty() || sub.equals("info") || sub.equals("cha")) {
                Msg.prefix(p, "<gray>主手先拿一把锻造品，或者直接把剑放到铁砧上。");
                guide(p);
            } else {
                Msg.prefix(p, "<red>主手先拿一件锻造品。</red>");
            }
            return;
        }
        if (sub.equals("info") || sub.equals("cha")) {
            for (String piece : service.describe(item).split("<newline>")) {
                if (!piece.isBlank()) {
                    Msg.prefix(p, piece);
                }
            }
            // 明牌：成功 / 碎裂 / 大成 / 损伤 / 空锤一次摆清
            Msg.prefix(p, service.oddsLine(service.oddsOf(item, 0)));
            Msg.prefix(p, service.comboText(service.comboOf(item), service.noLossComboThreshold()));
            guide(p);
            return;
        }
        // /zhujian strengthen sim [目标等级] —— 纯模拟，不扣任何东西
        if (sub.equals("sim") || sub.equals("moni") || sub.equals("yuce")) {
            int target = service.capOf(item);
            if (args.length >= 3) {
                try {
                    target = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    Msg.prefix(p, "<red>目标等级要写数字，比如 <white>/" + label()
                            + " strengthen sim 20</white>。");
                    return;
                }
            }
            for (String piece : service.simulate(item, target).split("<newline>")) {
                if (!piece.isBlank()) {
                    Msg.prefix(p, piece);
                }
            }
            return;
        }
        // /zhujian strengthen curve —— 逐档曲线表（纯只读，v2.19.1）
        if (sub.equals("curve") || sub.equals("quxian") || sub.equals("tubiao")) {
            for (String piece : service.curveReport(item).split("<newline>")) {
                if (!piece.isBlank()) {
                    Msg.prefix(p, piece);
                }
            }
            return;
        }
        // /zhujian strengthen set <等级> —— 管理员直接写等级
        if (sub.equals("set") || sub.equals("shezhi")) {
            if (!p.hasPermission("forgemaster.admin")) {
                Msg.prefix(p, "<red>没有权限。</red>");
                return;
            }
            if (args.length < 3) {
                Msg.prefix(p, "<red>用法：<white>/" + label() + " strengthen set <等级></white>");
                return;
            }
            int level;
            try {
                level = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                Msg.prefix(p, "<red>等级要写数字。</red>");
                return;
            }
            if (!service.setLevel(item, level)) {
                Msg.prefix(p, "<red>写不进去：这件东西没有强化上限。</red>");
                return;
            }
            Msg.prefix(p, "<green>已设为 <white>+" + service.levelOf(item) + "</white>（上限 +"
                    + service.capOf(item) + "）。");
            return;
        }
        // /zhujian reforge <序号> —— 只重抽一条词条
        if (sub.equals("reforge") || sub.equals("chongzhu") || sub.equals("xilian")) {
            reforge(p, item, args);
            return;
        }
        // /zhujian strengthen strip [序号] —— 把一条词条剥成词条符
        if (sub.equals("strip") || sub.equals("bo") || sub.equals("bobo")) {
            strip(p, item, args);
            return;
        }
        // /zhujian strengthen graft —— 主手锻造品 + 副手词条符
        if (sub.equals("graft") || sub.equals("yizhi") || sub.equals("zhuang")) {
            Msg.prefix(p, com.forgemaster.affix.Imprint.graft(plugin, p, item,
                    p.getInventory().getItemInOffHand()));
            return;
        }
        // /zhujian strengthen jianyi —— 剑意（武器熟练度）
        if (sub.equals("jianyi") || sub.equals("prof") || sub.equals("proficiency")
                || sub.equals("shulian")) {
            var prof = plugin.proficiency();
            if (prof == null || !prof.enabled()) {
                Msg.prefix(p, "<red>剑意系统未启用。</red>");
                return;
            }
            for (String piece : prof.describe(item).split("<newline>")) {
                if (!piece.isBlank()) {
                    Msg.prefix(p, piece);
                }
            }
            return;
        }
        // 不带子命令 = 讲怎么敲 + 当前明牌
        Msg.prefix(p, "<gray>强化已改成 <white>铁砧上敲</white>，不再点按钮：");
        Msg.prefix(p, service.oddsLine(service.oddsOf(item, 0)));
        Msg.prefix(p, service.comboText(service.comboOf(item), service.noLossComboThreshold()));
        guide(p);
    }

    // ------------------------------------------------------------ 剑魄分解 / 词条合成

    /**
     * {@code /zhujian dissolve [confirm]}（别名 jiapo / fenjie）—— 剑魄分解。
     *
     * <p>主手持锻造品，确认后销毁并产出「剑尘」；不带 confirm 只报价。
     * 具体定价与产出见 {@link com.forgemaster.dissolve.DissolveService}。</p>
     */
    private void dissolve(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>要在游戏里用。</red>");
            return;
        }
        Msg.prefix(p, com.forgemaster.dissolve.DissolveService.dissolve(plugin, p, args));
    }

    /**
     * {@code /zhujian imprint merge}（别名 cifu）—— 词条合成。
     *
     * <p>背包里 3 张同名词条符合成 1 张数值 ×1.5 的同名词条符；
     * 也可以走 {@code /zhujian strengthen merge}（词条符的既有入口在 strengthen 下）。
     * 编码与剥离 / 移植共用 {@link com.forgemaster.affix.Imprint}。</p>
     */
    private void imprint(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>要在游戏里用。</red>");
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("merge") || sub.equals("hecheng") || sub.equals("he") || sub.isEmpty()) {
            Msg.prefix(p, com.forgemaster.affix.Imprint.merge(plugin, p));
            return;
        }
        Msg.prefix(p, "<red>用法：<white>/zhujian imprint merge</white> <gray>— "
                + "3 张同名词条符 → 1 张 ×1.5 的同名词条符</gray>");
    }

    /**
     * /zhujian hammer —— 领一把铁匠重锤（不消耗，可重复领）。
     */
    private void hammer(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>要在游戏里用。</red>");
            return;
        }
        if (!p.hasPermission("forgemaster.use")) {
            Msg.prefix(p, "<red>没有权限。</red>");
            return;
        }
        org.bukkit.inventory.ItemStack item = com.forgemaster.anvil.Hammer.create(plugin);
        var leftovers = p.getInventory().addItem(item);
        if (!leftovers.isEmpty()) {
            for (org.bukkit.inventory.ItemStack left : leftovers.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
            Msg.prefix(p, "<yellow>背包满了，重锤掉在脚下了。");
        } else {
            Msg.prefix(p, "<green>领到一把 <white>铁匠重锤</white>。"
                    + "<gray>主手拿剑右键铁砧放上去，再拿它左键敲。");
        }
    }

    /** 铁砧强化三步走 + 领锤提示 */
    private void guide(org.bukkit.entity.Player p) {
        Msg.send(p, "<dark_gray>────────────────────");
        Msg.send(p, "<gray>① 主手拿剑，<white>右键铁砧</white> 把剑放上去（会显示等级 / 成功率 / 碎裂率）");
        Msg.send(p, "<gray>② 手持 <white>重锤</white>，<white>左键铁砧</white> 落锤，一次一锤");
        Msg.send(p, "<gray>③ 再 <white>右键铁砧</white> 把剑取回来");
        if (!com.forgemaster.anvil.Hammer.isHammer(plugin, p.getInventory().getItemInMainHand())) {
            Msg.send(p, "<gray>没有锤子？<white>/" + label() + " hammer</white> 领一把（原版重锤也认）");
        }
        Msg.send(p, "<dark_gray>────────────────────");
    }

    /**
     * /zhujian evolve —— 蜕变（锻阶）。默认开界面；now 直掷；sim 纯模拟。
     */
    private void evolve(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>要在游戏里用。</red>");
            return;
        }
        var evo = plugin.evolutions();
        if (evo == null || !evo.enabled()) {
            Msg.prefix(p, "<red>锻阶功能已关闭。</red>");
            return;
        }
        org.bukkit.inventory.ItemStack item = p.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            Msg.prefix(p, "<red>主手先拿一件锻造品。</red>");
            return;
        }
        // 出厂剑身上没有 evolution 键，stageOf 现在按「是不是锻造品」判定，
        // 不能再拿 -1 当「不是锻造品」用（那会把刚锻好的剑顶回去）
        if (evo.stageOf(item) < 0) {
            Msg.prefix(p, "<red>主手这把不是锻造品，蜕变不了——锻造台打出来的才算。</red>");
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("sim") || sub.equals("moni") || sub.equals("yuce")) {
            for (String piece : evo.simulateEvolution(item).split("<newline>")) {
                if (!piece.isBlank()) {
                    Msg.prefix(p, piece);
                }
            }
            return;
        }
        if (sub.equals("now") || sub.equals("ok") || sub.equals("go")) {
            for (String piece : evo.evolve(p, item).split("<newline>")) {
                if (!piece.isBlank()) {
                    Msg.prefix(p, piece);
                }
            }
            return;
        }
        // 默认开界面；没有待蜕变门槛时给一句直掷结果当反馈
        if (!com.forgemaster.evolve.EvolutionMenu.open(plugin, p)) {
            for (String piece : evo.evolve(p, item).split("<newline>")) {
                if (!piece.isBlank()) {
                    Msg.prefix(p, piece);
                }
            }
        }
    }

    /**
     * /zhujian contract —— 龙税周目：默认开面板；join / quit / deposit。
     */
    private void contract(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>要在游戏里用。</red>");
            return;
        }
        var cm = plugin.contractManager();
        if (cm == null || !cm.active()) {
            Msg.prefix(p, "<red>龙税契约未开启（config: contract.enabled）。</red>");
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "join", "qian", "qiding" -> Msg.prefix(p, cm.join(p));
            case "quit", "tuichu" -> Msg.prefix(p, cm.quit(p));
            case "deposit", "gong", "jiao" -> Msg.prefix(p, cm.deposit(p));
            default -> com.forgemaster.gui.ContractMenu.openPanel(plugin, p);
        }
    }

    /**
     * /zhujian shangjin —— 每日悬赏：默认列表；claim <序号> 领赏。
     */
    private void shangjin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>要在游戏里用。</red>");
            return;
        }
        var bm = plugin.bounties();
        if (bm == null || !bm.enabled()) {
            Msg.prefix(p, "<red>悬赏功能未开启。</red>");
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("claim") || sub.equals("ling") || sub.equals("duihuan")) {
            int index = -1;
            if (args.length >= 3) {
                try {
                    index = Integer.parseInt(args[2]) - 1;
                } catch (NumberFormatException e) {
                    Msg.prefix(p, "<red>序号要写数字，比如 <white>/zhujian shangjin claim 1</white>。");
                    return;
                }
            }
            Msg.prefix(p, bm.claim(p, index));
            return;
        }
        for (String piece : bm.describe()) {
            Msg.prefix(p, piece);
        }
    }

    /** /zhujian strengthen reforge [序号|info] */
    /**
     * {@code /zhujian strengthen strip [序号]}：把主手第 N 条词条剥成「词条符」。
     *
     * <p>不带序号时只列当前词条与剥离费，不动物品——命令行的可预览原则跟重铸一致。</p>
     */
    private void strip(Player p, org.bukkit.inventory.ItemStack item, String[] args) {
        int count = com.forgemaster.affix.Reforge.affixCount(item);
        if (count <= 0) {
            Msg.prefix(p, "<red>这件没有词条可剥。</red>");
            return;
        }
        if (args.length < 3) {
            Msg.prefix(p, "<gray>这件有 <white>" + count + "</white> 条词条，剥哪一条？"
                    + " <white>/" + label() + " strengthen strip <序号></white>");
            for (int i = 0; i < count; i++) {
                Msg.prefix(p, "<gray>" + (i + 1) + ". " + com.forgemaster.affix.Reforge.preview(item, i));
            }
            return;
        }
        int index;
        try {
            index = Integer.parseInt(args[2]) - 1;
        } catch (NumberFormatException e) {
            Msg.prefix(p, "<red>序号要写数字（从 1 开始）。</red>");
            return;
        }
        if (index < 0 || index >= count) {
            Msg.prefix(p, "<red>没有第 " + (index + 1) + " 条词条，这件只有 " + count + " 条。</red>");
            return;
        }
        Msg.prefix(p, com.forgemaster.affix.Imprint.strip(plugin, p, item, index));
    }

    private void reforge(Player p, org.bukkit.inventory.ItemStack item, String[] args) {        int count = com.forgemaster.affix.Reforge.affixCount(item);
        if (count <= 0) {
            Msg.prefix(p, "<red>这件没有词条可重铸。</red>");
            return;
        }
        String third = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "";
        if (third.equals("gui") || third.equals("kj") || third.equals("jm") || third.equals("caidan")) {
            com.forgemaster.gui.ReforgeMenu.open(plugin, p);
            return;
        }
        if (third.isEmpty() || third.equals("info") || third.equals("list")) {
            Msg.prefix(p, "<gray>这件有 <white>" + count + "</white> 条词条，用法：");
            Msg.prefix(p, "<white>/" + label() + " strengthen reforge <序号></white> <gray>序号从 1 开始");
            for (int i = 0; i < count; i++) {
                Msg.prefix(p, "<gray>" + (i + 1) + ". " + com.forgemaster.affix.Reforge.preview(item, i));
            }
            return;
        }
        int index;
        try {
            index = Integer.parseInt(third) - 1;
        } catch (NumberFormatException e) {
            Msg.prefix(p, "<red>序号要写数字（从 1 开始）。</red>");
            return;
        }
        if (index < 0 || index >= count) {
            Msg.prefix(p, "<red>没有第 " + (index + 1) + " 条词条，这件只有 " + count + " 条。</red>");
            return;
        }
        long cost = com.forgemaster.affix.Reforge.costOf(item, index);
        long coins = plugin.stats().coins(p.getUniqueId());
        if (coins < cost) {
            Msg.prefix(p, "<red>重铸费不够：需要 <white>" + cost + "</white>，你只有 <white>" + coins + "</white>。");
            return;
        }
        String result = com.forgemaster.affix.Reforge.reforge(p, item, index,
                java.util.concurrent.ThreadLocalRandom.current());
        // 重铸内部可能在写之前就失败（词条被服主删了 / 候选池被占满），
        // 先收钱就会出现「钱扣了物品没变」，所以只在真成功时才扣
        if (result.startsWith("<green>重铸成功")) {
            plugin.stats().takeCoins(p.getUniqueId(), cost, com.forgemaster.econ.CoinReason.OTHER_OUT);
        }
        Msg.prefix(p, result);
    }

    /** 命令主名，用于提示里的完整写法 */
    private String label() {
        return "zhujian";
    }

    // ------------------------------------------------------------ 货币管理

    /**
     * 双账本对账（v2.19.1）：把「剑币」和「服务器经济」两本账一起摆出来，并标明
     * 宝石 / 黑市 / 签到 / 重铸 这批消费走的是哪一本。
     *
     * <p>存在理由：插件里本来就有两本账（剑币 = 插件内产出与消耗，服务器经济 = 锻造费）。
     * 之前宝石那批模块错走了服务器经济，玩家会看到「剑币明明够却提示不足」。
     * 把两本账并排显示，服主/玩家一眼就能判断是不是口径配错了。</p>
     */
    private void budgetLines(CommandSender to, Player who) {
        var wallet = plugin.wallet();
        if (wallet == null) {
            return;
        }
        long coins = plugin.stats().coins(who.getUniqueId());
        Msg.send(to, "<dark_gray>├ <gray>剑币（插件内产出：锻造 / 委托 / 大赛 / 拍卖）: <#ffd166>"
                + coins + "</#ffd166> 币");
        if (wallet.serverSinks()) {
            Msg.send(to, "<dark_gray>├ <gray>宝石 / 黑市 / 签到 / 重铸 扣: <white>服务器经济</white>"
                    + " <dark_gray>（economy.coin-sinks=server）");
        } else {
            Msg.send(to, "<dark_gray>├ <gray>宝石 / 黑市 / 签到 / 重铸 扣: <#ffd166>剑币</#ffd166>"
                    + " <dark_gray>（economy.coin-sinks=plugin，与商会 / 拍卖同一本账）");
        }
        Msg.send(to, "<dark_gray>└ <gray>锻造费扣: <white>服务器经济</white>"
                + " <dark_gray>（README 写明的唯一例外）");
    }

    /**
     * /zhujian money [balance|give|take] ...
     *
     * <p>走 EconomyBridge，不管服务器用的是 Vault、CMI 还是内置剑币，都能查和发。</p>
     */
    private void money(CommandSender sender, String[] args) {
        var eco = plugin.economy();
        if (eco == null) {
            Msg.prefix(sender, "<red>货币模块没有初始化。</red>");
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";

        // /zhujian money coin ... —— 内置剑币（商会 / 拍卖 / 强化用的那套）。
        // 上面的 give/take 操作的是外部经济（Vault / CMI，锻造费走那边）；
        // 管理员想发「剑币」必须走这里，不然发出去的钱在商店里花不了
        if (sub.equals("coin") || sub.equals("coins") || sub.equals("jianbi")) {
            moneyCoin(sender, args);
            return;
        }

        if (sub.isEmpty() || "balance".equals(sub) || "bal".equals(sub)) {
            if (args.length >= 3) {
                var target = Bukkit.getPlayerExact(args[2]);
                if (target == null) {
                    Msg.prefix(sender, "<red>玩家不在线：" + args[2]);
                    return;
                }
                Msg.prefix(sender, "<gray>" + target.getName() + " 的余额：<white>"
                        + eco.format((long) eco.balance(target)) + "</white> <dark_gray>（" + eco.backend().label() + "）");
                budgetLines(sender, target);
                return;
            }
            if (sender instanceof Player p) {
                Msg.prefix(p, "<gray>你的余额：<white>" + eco.format((long) eco.balance(p))
                        + "</white> <dark_gray>（" + eco.backend().label() + "）");
                budgetLines(p, p);
            } else {
                Msg.prefix(sender, "<gray>用法：<white>/zhujian money balance <玩家></white>"
                        + " 或 <white>/zhujian money give <玩家> <数量></white>");
            }
            return;
        }

        if (!sender.hasPermission("forgemaster.admin")) {
            Msg.prefix(sender, "<red>只有管理员能发钱 / 扣钱。</red>");
            return;
        }
        if (args.length < 4) {
            Msg.prefix(sender, "<gray>用法：<white>/zhujian money give|take <玩家> <数量>");
            return;
        }
        var target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            Msg.prefix(sender, "<red>玩家不在线：" + args[2] + " <gray>（发钱需要对方在线）");
            return;
        }
        long amount;
        try {
            amount = Math.abs(Long.parseLong(args[3].replace(",", "")));
        } catch (NumberFormatException ex) {
            Msg.prefix(sender, "<red>数量要写数字：" + args[3]);
            return;
        }
        if (amount <= 0) {
            Msg.prefix(sender, "<red>数量要大于 0。</red>");
            return;
        }

        if ("give".equals(sub) || "add".equals(sub)) {
            if (!eco.deposit(target, amount)) {
                Msg.prefix(sender, "<red>发钱失败，检查货币后端是否正常。</red>");
                return;
            }
            Msg.prefix(target, "<green>收到 <white>" + eco.format(amount) + "</white>。");
            Msg.prefix(sender, "<green>已给 <white>" + target.getName() + "</white> 发放 " + eco.format(amount)
                    + " <gray>· 余额 " + eco.format((long) eco.balance(target)));
            // 这里是「外部经济」（Vault / CMI 的钱），强化与商会花的是「剑币」，两个池子。
            // 不写清楚的话，服主发完钱一敲砧子还是提示剑币不够——非常容易踩。
            Msg.prefix(sender, "<dark_gray>注意：这是 " + eco.backend().label()
                    + " 的钱。强化 / 商会用的是<white>剑币</white>，发剑币用 <white>/"
                    + label() + " money coin give " + target.getName() + " " + amount + "</white>");
        } else if ("take".equals(sub) || "remove".equals(sub)) {
            if (!eco.withdraw(target, amount)) {
                Msg.prefix(sender, "<red>扣钱失败：余额不足或后端异常。</red>");
                return;
            }
            Msg.prefix(sender, "<green>已从 <white>" + target.getName() + "</white> 扣除 " + eco.format(amount)
                    + " <gray>· 余额 " + eco.format((long) eco.balance(target)));
        } else {
            Msg.prefix(sender, "<gray>用法：<white>/zhujian money give|take <玩家> <数量>");
        }
    }

    private void grade(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>要在游戏里用。</red>");
            return;
        }
        if (args.length >= 2) {
            if (!sender.hasPermission("forgemaster.admin")) {
                Msg.prefix(sender, "<red>只有管理员能发材料。</red>");
                return;
            }
            String sub = args[1].toLowerCase(Locale.ROOT);
            if ("up".equalsIgnoreCase(sub)) {
                boolean okUp = com.forgemaster.material.MaterialGrades.upgrade(p);
                Msg.prefix(p, okUp ? "<green>手上材料升了一档。"
                        : "<red>手上没东西，或者已经是最高档。");
                return;
            }
            if ("give".equalsIgnoreCase(sub) && args.length >= 3) {
                org.bukkit.Material mat = org.bukkit.Material.matchMaterial(args[2].toUpperCase(Locale.ROOT));
                if (mat == null || !mat.isItem()) {
                    Msg.prefix(p, "<red>没有这种材料：" + args[2]);
                    return;
                }
                com.forgemaster.material.MaterialGrade g = args.length >= 4
                        ? com.forgemaster.material.MaterialGrade.byName(args[3])
                        : com.forgemaster.material.MaterialGrade.REFINED;
                int amount = args.length >= 5 ? Math.max(1, safeInt(args[4], 8)) : 8;
                var item = com.forgemaster.material.MaterialGrades.stamp(new org.bukkit.inventory.ItemStack(mat, amount), g);
                p.getInventory().addItem(item);
                Msg.prefix(p, "<green>给了 <white>" + amount + "</white> 个 " + g.colored()
                        + " " + com.forgemaster.material.MaterialGrades.pretty(mat));
                return;
            }
        }
        // 无参数：看手上材料的档次效果
        var hand = p.getInventory().getItemInMainHand();
        var g = com.forgemaster.material.MaterialGrades.gradeOf(hand);
        Msg.send(sender, "<dark_gray>──── <#7ef2c8>材料分级</#7ef2c8> ────");
        for (var mg : com.forgemaster.material.MaterialGrade.values()) {
            Msg.send(sender, (mg == g ? "<yellow>▶ " : "<gray>  ") + mg.colored()
                    + " <dark_gray>·</dark_gray> 上限 <white>+" + Msg.fmt(mg.scoreBonus(), 0)
                    + "%</white> <dark_gray>·</dark_gray> 额外词条 <white>+" + mg.extraAffixes() + "</white>");
        }
        Msg.send(sender, "<gray>手上: " + g.colored() + " <dark_gray>(锻造时优先消耗高档料)</dark_gray>");
        Msg.send(sender, "<dark_gray>管理员: /zhujian grade up | grade give <材料> <档次> [数量]");
    }

    // ------------------------------------------------------------ 套装效果 2.0

    /**
     * {@code /zhujian relicset}：查看当前套装效果与主动技冷却状态。
     *
     * <p>纯读：列出 config 里配置的每个套装段、自己身上同材料件数、
     * 吃到的档位加成、主动技冷却剩余。改动要靠穿脱装备，不在命令里做。</p>
     */
    private void relicSet(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>套装状态只有玩家能看。</red>");
            return;
        }
        if (plugin.relicSets() == null) {
            Msg.prefix(sender, "<red>套装效果模块未加载。</red>");
            return;
        }
        Msg.send(p, plugin.relicSets().describe(p));
        // v2.23：预览面板（列表页 + 每套详情）
        com.forgemaster.relicset.RelicSetGui.open(plugin, p);
    }

    // ------------------------------------------------------------ 图鉴

    private void codex(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>图鉴要在游戏里看。</red>");
            return;
        }
        StatsStore.Stats st = plugin.stats().get(p.getUniqueId());
        List<ForgeMaterial> mats = new ArrayList<>(plugin.materials().sorted());
        int total = mats.size() * Quality.values().length;

        Msg.send(sender, "<dark_gray>──────── <white>锻造图鉴</white> <dark_gray>(<white>"
                + st.codex.size() + "<dark_gray>/<white>" + total + "<dark_gray>) ────────");
        for (ForgeMaterial m : mats) {
            StringBuilder line = new StringBuilder("<gray>" + pad(m.id(), 10) + " ");
            for (Quality q : Quality.values()) {
                boolean got = st.codex.contains(m.id().toLowerCase() + ":" + q.name());
                line.append(got ? "<#4ee36b>■" : "<dark_gray>□");
            }
            line.append("<dark_gray>  ");
            // 该材料已收集的最好品质
            Quality best = null;
            for (Quality q : Quality.values()) {
                if (st.codex.contains(m.id().toLowerCase() + ":" + q.name())) {
                    best = q;
                }
            }
            line.append(best == null ? "<dark_gray>未锻过" : best.colored());
            Msg.send(sender, line.toString());
        }
        Msg.send(sender, "<dark_gray>收集度: " + Msg.bar((double) st.codex.size() / Math.max(1, total), 30, "#4ee36b", "#3a3a3a"));
        if (st.codex.size() >= total) {
            Msg.send(sender, "<gradient:#ffd166:#ff7a18><bold>图鉴全收集！你是当世第一铸剑师。</bold></gradient>");
        }
    }

    /** 收藏图鉴（拓展第一档）：GUI 版收藏进度，/zhujian collection|shoucang|tujian */
    private void collectionGui(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>收藏图鉴要在游戏里看。</red>");
            return;
        }
        com.forgemaster.codex.CodexGui.open(plugin, p);
    }

    private String pad(String s, int len) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < len) {
            sb.append(' ');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 熔炼

    private void salvage(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>熔炼要在游戏里做。</red>");
            return;
        }
        org.bukkit.inventory.ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir() || !plugin.items().isForged(hand)) {
            Msg.prefix(p, "<red>手里拿的不是锻造品。</red>");
            return;
        }
        var meta = hand.getItemMeta();
        String matId = meta.getPersistentDataContainer()
                .get(com.forgemaster.util.Keys.MATERIAL, org.bukkit.persistence.PersistentDataType.STRING);
        ForgeMaterial mat = matId == null ? null : plugin.materials().get(matId);
        if (mat == null) {
            Msg.prefix(p, "<red>这把剑的剑胚信息损坏了，熔不了。</red>");
            return;
        }
        int refund = Math.max(1, mat.costAmount() / 2);
        hand.setAmount(hand.getAmount() - 1);
        p.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);

        var leftover = p.getInventory().addItem(new org.bukkit.inventory.ItemStack(mat.costItem(), refund));
        for (var rest : leftover.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
        plugin.stats().recordSalvage(p.getUniqueId());

        p.getWorld().spawnParticle(org.bukkit.Particle.LAVA, p.getLocation().add(0, 1, 0), 20, 0.4, 0.4, 0.4, 0.02);
        p.getWorld().spawnParticle(org.bukkit.Particle.SMOKE, p.getLocation().add(0, 1, 0), 20, 0.4, 0.5, 0.4, 0.04);
        p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_LAVA_POP, 0.9f, 0.7f);
        Msg.prefix(p, "<green>熔掉了 <white>" + mat.display() + "</white> 锻造品，回炉得到 <white>"
                + refund + " × " + com.forgemaster.material.MaterialGrades.pretty(mat.costItem()) + "</white>。");
    }

    // ------------------------------------------------------------ E3 备份 / 回档（需 admin）

    /** /zhujian backup：新建一份备份，然后列出可用备份 */
    private void backup(CommandSender sender) {
        if (!sender.hasPermission("forgemaster.admin")) {
            Msg.prefix(sender, "<red>权限不足。</red>");
            return;
        }
        try {
            String id = plugin.backups().backup("");
            if (id == null) {
                Msg.prefix(sender, "<red>没有找到任何存档文件，未创建备份。</red>");
                return;
            }
            Msg.prefix(sender, "<green>已备份存档到 <white>" + id + "</white>。</green>");
        } catch (java.io.IOException ex) {
            Msg.prefix(sender, "<red>备份失败：" + ex.getMessage() + "</red>");
            return;
        }
        plugin.backups().sendList(sender);
    }

    /** /zhujian restore <id>：先自动备份当前存档，再覆盖回档 */
    private void restore(CommandSender sender, String[] args) {
        if (!sender.hasPermission("forgemaster.admin")) {
            Msg.prefix(sender, "<red>权限不足。</red>");
            return;
        }
        if (args.length < 2) {
            Msg.prefix(sender, "<red>用法：/zhujian restore <备份id></red>");
            plugin.backups().sendList(sender);
            return;
        }
        try {
            String preId = plugin.backups().restore(args[1]);
            if (preId == null) {
                Msg.prefix(sender, "<red>找不到备份 <white>" + args[1] + "</white>（或 id 不合法）。</red>");
                return;
            }
            Msg.prefix(sender, "<green>已回档到 <white>" + args[1] + "</white>。</green>"
                    + " <gray>回档前的存档已自动备份为 <white>" + preId + "</white>。");
            // v2.23.1：restore 现在会 flush 全部 → 备份 → 覆盖 → 只重载（不 save），
            // 17 份存档的内存态都已按文件重建。仍建议重启，因为铁砧会话与悬赏名单
            // 没有公开 reload 入口，且个别模块的 5 秒冲刷可能与重载擦肩。
            Msg.prefix(sender, "<#ff9a3c>存档与内存缓存已同步重建（覆盖 "
                    + com.forgemaster.backup.BackupManager.SAVE_FILES.length + " 份文件 + metrics/ 目录）。"
                    + "建议尽快重启一次服务器，让铁砧砧位与悬赏名单也彻底归位。</#ff9a3c>");
        } catch (java.io.IOException ex) {
            Msg.prefix(sender, "<red>回档失败：" + ex.getMessage() + "</red>");
        }
    }

    /**
     * /zhujian config check —— 配置体检（v2.19.2）。
     *
     * <p>做两件事，都是这一轮审计里真踩过的坑：</p>
     * <ol>
     *   <li><b>缺键</b>：拿 {@link com.forgemaster.ForgingMasterPlugin#REQUIRED_CONFIG_KEYS}
     *       跟<b>盘上的原始文件</b>比。用 getConfig() 比没用——它带着运行时默认值，
     *       contains() 恒为 true。</li>
     *   <li><b>隐形键</b>：有些旋钮以前只写在代码里（重铸品质乘区、蜕变逐阶乘区、
     *       强化高段概率），服主看盘上那几个数根本推不出实际费用。这里把<b>实际生效值</b>
     *       直接摊开。</li>
     * </ol>
     *
     * <p>只读，不改任何东西。参数 {@code keys} 可加 {@code --all} 把缺的键全列出来
     * （默认最多列 12 个）。</p>
     */
    private void configCheck(CommandSender sender, String[] args) {
        if (!sender.hasPermission("forgemaster.admin")) {
            Msg.prefix(sender, "<red>权限不足。</red>");
            return;
        }
        boolean all = args.length >= 3 && (args[2].equals("--all") || args[2].equals("all"));
        var raw = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                new java.io.File(plugin.getDataFolder(), "config.yml"));

        // ---- 1. 缺键 ----
        java.util.List<String> missing = new ArrayList<>();
        for (String key : com.forgemaster.ForgingMasterPlugin.REQUIRED_CONFIG_KEYS) {
            if (!raw.contains(key)) {
                missing.add(key);
            }
        }
        Msg.prefix(sender, "<gray>配置体检 <dark_gray>(plugins/ForgingMaster/config.yml)");
        if (missing.isEmpty()) {
            Msg.prefix(sender, "<green>✓ 必需键齐全</green> <dark_gray>（"
                    + com.forgemaster.ForgingMasterPlugin.REQUIRED_CONFIG_KEYS.length + " 项）");
        } else {
            Msg.prefix(sender, "<red>✗ 缺 " + missing.size() + " 个必需键</red>"
                    + " <dark_gray>（缺键会静默用代码默认值，服主改不到）");
            int limit = all ? missing.size() : Math.min(12, missing.size());
            for (int i = 0; i < limit; i++) {
                Msg.send(sender, "<dark_gray>    · <gray>" + missing.get(i));
            }
            if (limit < missing.size()) {
                Msg.send(sender, "<dark_gray>    ……还有 " + (missing.size() - limit)
                        + " 个，加 <white>/" + label() + " config check all</white> 看全");
            }
            Msg.send(sender, "<dark_gray>  补法：备份后删掉 config.yml 重启，插件会重新生成完整注释版；"
                    + "或等启动时的 step 迁移自动补");
        }

        // ---- 2. 实际生效值 ----
        var s = plugin.settings();
        Msg.prefix(sender, "<gray>实际生效值 <dark_gray>（含代码默认，盘上没有也照跑）");
        Msg.send(sender, "<dark_gray>├ <gray>重铸品质乘区: <white>"
                + "粗糙 " + Msg.fmt(s.num("reforge.cost.quality.rough", 0.80), 2)
                + " · 普通 " + Msg.fmt(s.num("reforge.cost.quality.common", 1.00), 2)
                + " · 精良 " + Msg.fmt(s.num("reforge.cost.quality.fine", 1.25), 2)
                + " · 史诗 " + Msg.fmt(s.num("reforge.cost.quality.epic", 1.70), 2)
                + " · 传说 " + Msg.fmt(s.num("reforge.cost.quality.legendary", 2.40), 2)
                + " · 神话 " + Msg.fmt(s.num("reforge.cost.quality.mythic", 3.40), 2));
        StringBuilder evo = new StringBuilder();
        double prod = 1.0;
        for (int i = 1; i <= 6; i++) {
            String base = "evolution.stages." + i;
            double b = s.num(base + ".bonus", 1.0);
            prod *= b;
            evo.append(evo.length() == 0 ? "" : " · ")
                    .append(i).append(":×").append(Msg.fmt(b, 2))
                    .append("/").append(s.intOf(base + ".cost-coins", 0));
        }
        Msg.send(sender, "<dark_gray>├ <gray>蜕变逐阶（乘区/剑币）: <white>" + evo
                + "</white> <dark_gray>连乘 ×" + Msg.fmt(prod, 3));
        Msg.send(sender, "<dark_gray>├ <gray>强化高段: <white>成功率下限 "
                + Msg.fmt(s.num("anvil-forge.success.floor", 0.14) * 100, 0) + "%"
                + " · 顶段碎裂 " + Msg.fmt(s.num("anvil-forge.break.max", 0.18) * 100, 0) + "%"
                + " · 失败损伤占比 " + Msg.fmt(s.num("strengthen.tickets.damage-share", 0.35) * 100, 0) + "%</white>"
                + " <dark_gray>（用 <white>/" + label() + " strengthen curve</white> 看六档曲线）");
        // 暴击：这一行是服主最需要看见的——上限封到多少，直接决定「堆暴击」值不值。
        // 注意要显示**配置的上限**，不是某把武器的实际值：
        // 传 (0,0) 进去只会得到 0%，那样这行永远是「上限 0%」。
        var critLimits = com.forgemaster.combat.Crit.limitsOf(plugin);
        double critBase = critLimits.baseMultiplier();
        double critMaxChance = critLimits.maxChance();
        double critMaxDamage = critLimits.maxDamage();
        // 上限本身也走一遍 resolve，把 1.0 硬顶和「配置写坏」一起收敛掉
        var critCap = com.forgemaster.combat.Crit.resolve(
                critMaxChance, critMaxDamage, critMaxChance, critMaxDamage, critBase);
        Msg.send(sender, "<dark_gray>├ <gray>暴击口径: <white>暴击率上限 "
                + Msg.fmt(critCap.chance() * 100, 0) + "%"
                + " · 暴伤上限 +" + Msg.fmt((critCap.multiplier() - critBase) * 100, 0) + "%"
                + " → 最高 ×" + Msg.fmt(critCap.multiplier(), 2) + "</white>"
                + " <dark_gray>（倍率底数 ×" + Msg.fmt(critBase, 2) + "）");
        var wallet = plugin.wallet();
        Msg.send(sender, "<dark_gray>└ <gray>货币口径: <white>"
                + (wallet != null && wallet.serverSinks() ? "server（宝石/黑市/签到/重铸扣服务器经济）"
                        : "plugin（宝石/黑市/签到/重铸扣剑币）") + "</white>"
                + " <dark_gray>· 锻造费永远走服务器经济");
    }

    /**
     * /zhujian givefull —— 管理员懒人指令：一次发出满配锻造品（v2.21）。
     *
     * <p>为什么要它：正常路径拿一件满配要经过 生成 → 强化 99 次 → 蜕变 6 阶 →
     * 重铸词条 6 次 → 开孔 → 镶宝石，几十步且大量掷骰。做平衡测试、给主播演示、
     * 开服补偿都要用，每次手搓一遍不现实。</p>
     *
     * <p>每一维都走真实的写入路径（{@code setLevel} / {@code forceStage} /
     * {@code addAffixRaw} / {@code fillMax}），不写死 Lore 文案——
     * 否则会出现「显示满配、属性不生效」这种最难查的假货。</p>
     */
    private void giveFull(CommandSender sender, String[] args) {
        if (!sender.hasPermission("forgemaster.admin")) {
            Msg.prefix(sender, "<red>权限不足。</red>");
            return;
        }
        if (args.length < 3) {
            Msg.prefix(sender, "<gray>用法: /zhujian givefull <玩家> <剑胚|armor-剑胚-部位> [品质]</gray>");
            Msg.prefix(sender, "<gray>例：<white>/zhujian givefull Steve iron legendary</white>");
            Msg.prefix(sender, "<gray>　　<white>/zhujian givefull Steve armor-iron-chestplate mythic</white>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            Msg.prefix(sender, "<red>找不到在线玩家 " + args[1] + "</red>");
            return;
        }
        ForgeMaterial material = plugin.materials().get(args[2]);
        if (material == null) {
            material = resolveArmorMaterial(args[2]);
        }
        if (material == null) {
            Msg.prefix(sender, "<red>未知剑胚: " + args[2] + "</red>");
            return;
        }
        Quality quality = args.length >= 4 ? Quality.byName(args[3]) : Quality.MYTHIC;
        if (quality == null) {
            Msg.prefix(sender, "<red>未知品质: " + args[3] + "</red>");
            return;
        }

        // 1) 生成：评分给满（评分就是原始分，品质靠它落档）
        ItemStack item = plugin.items().buildDirect(target.getName(), material, quality, 100);

        // 2) 强化：拉到该品质的硬上限（神话 = +99）
        int level = 0;
        int cap = plugin.strengthen().capOf(item);
        if (cap > 0) {
            plugin.strengthen().setLevel(item, cap);
            level = cap;
        }

        // 3) 蜕变：拉到最高阶（走 forceStage，属性乘区与〔铭牌〕一起落）
        int stage = 0;
        if (plugin.evolutions() != null && plugin.evolutions().enabled()) {
            int s = plugin.evolutions().forceStage(item, Integer.MAX_VALUE);
            stage = Math.max(0, s);
        }

        // 4) 词条：填到上限，值取该词条上限；同一属性只出一次
        int affixes = 0;
        if (plugin.settings().flag("imprint.enabled", true)) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                int want = com.forgemaster.affix.Imprint.maxAffixes(plugin);
                java.util.Set<String> usedAttr = new java.util.HashSet<>();
                for (Affix a : plugin.affixes().all()) {
                    if (affixes >= want) {
                        break;
                    }
                    // 同一属性挂两条在数值上等价于一条更强的，但看起来像 bug，
                    // 所以按属性去重，优先铺开不同方向
                    String attrKey = a.attribute() != null
                            ? a.attribute().name() : a.kind().name();
                    if (!usedAttr.add(attrKey)) {
                        continue;
                    }
                    com.forgemaster.affix.Reforge.addAffixRaw(plugin, meta, a, a.max(),
                            material.armor() ? EquipmentSlotGroup.ARMOR : EquipmentSlotGroup.MAINHAND);
                    affixes++;
                }
                item.setItemMeta(meta);
            }
        }

        // 5) 宝石：孔开满 + 每孔一颗 Lv.5（数值取该类型上限 × 等级倍率）
        int sockets = 0;
        int enchSockets = 0;
        if (plugin.gems() != null) {
            int limit = plugin.gems().socketLimitOf(item);
            if (limit > 0) {
                plugin.gems().fillMax(item, limit, com.forgemaster.gem.GemType.Grade.L5);
                sockets = plugin.gems().socketsOf(item);
            }
            // 附魔孔是另一套孔池，fillMax 不管它。不管的话满配物品的附魔孔是 0，
            // 玩家拿到「满配」却发现连一本书都敲不进去——这是 v2.21 修的一个漏项。
            enchSockets = plugin.gems().fillMaxEnchantSockets(item);
        }

        // 6) 收尾重算：四个维度分开写，最后统一 apply 一次保证属性与 Lore 一致
        plugin.strengthen().apply(item);

        Map<Integer, ItemStack> left = target.getInventory().addItem(item);
        for (var rest : left.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), rest);
        }
        Msg.prefix(sender, "<green>已给 " + target.getName() + " 生成满配：</green>"
                + quality.colored() + " <gray>· 评分 100 · <white>+" + level
                + "</white> · <white>" + stage + " 阶</white> · 词条 <white>" + affixes
                + "</white> 条 · 宝石孔 <white>" + sockets
                + "</white> · 附魔孔 <white>" + enchSockets + "</white>");
        if (sender != target) {
            Msg.prefix(target, "<gold>管理员给了你一件满配锻造品。</gold>");
        }
    }

    /**
     * /zhujian metrics —— 服主经济体检（v2.20 切片 1）。
     *
     * <p>在这之前，所有关于经济的结论都只能拿静态配置推演：「打一把剑赚多少」
     * 「强化 +40 要多少把剑」全是模型值。真实服上产出与消耗什么形状、
     * 哪个模块在吸币——一个数字都看不到。这条命令把流水摊开。</p>
     *
     * <p>数据来自 {@code StatsStore} 的三处收口（加币 / 扣币 / 赛季清零），
     * 所以只要调用点带了理由是准的；没带理由的落进 `other-in`/`other-out`，
     * 那两桶的占比本身就是「还有多少地方没接上」的指标。</p>
     */
    private void metrics(CommandSender sender) {
        metrics(sender, new String[0]);
    }

    /**
     * 数据看板（v2.23 切片 2/3 接线）：
     * <ul>
     *   <li>{@code /zhujian admin metrics} —— 切片 1 的实时体检（剑币产出/消耗构成、总量、基尼），
     *       文案与 v2.20 完全一致，老用户用法不变。</li>
     *   <li>{@code /zhujian admin metrics trend [天数]} —— 近 N 天（默认 7，上限 history-days）
     *       的产出/消耗/净额/锻造数/碎裂数与趋势总结。</li>
     *   <li>{@code /zhujian admin metrics csv [天数]} —— 把同样的窗口导出成 CSV 存到
     *       {@code plugins/ForgingMaster/metrics-export/}，异步生成，完成后私聊路径。</li>
     *   <li>{@code /zhujian admin metrics sample} —— 立刻采一次样（调参后想看效果时用）。</li>
     * </ul>
     */
    private void metrics(CommandSender sender, String[] args) {
        if (!sender.hasPermission("forgemaster.admin")) {
            Msg.prefix(sender, "<red>权限不足。</red>");
            return;
        }
        if (!plugin.settings().flag("metrics.enabled", true)) {
            Msg.prefix(sender, "<yellow>数据看板已关闭。</yellow> <dark_gray>（config.yml → metrics.enabled）");
            return;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);
        var svc = plugin.metrics();
        if (svc == null) {
            Msg.prefix(sender, "<red>数据看板服务没有启动，请检查启动日志。</red>");
            return;
        }
        switch (sub) {
            case "trend", "qushi" -> {
                int days = clampDays(args.length > 1 ? args[1] : "7", svc.historyDays());
                Msg.send(sender, "<white>近 <#ffd166>" + days + "</#ffd166> 天趋势（每行一天，产出/消耗/净额/锻造/碎裂）：</white>");
                java.util.List<com.forgemaster.metrics.MetricsService.DayTotal> totals = svc.dayTotals(days);
                if (totals.isEmpty()) {
                    Msg.send(sender, "<gray>还没有采样数据。等一个采样周期（默认 "
                            + svc.intervalMinutes() + " 分钟），或先跑 /zhujian admin metrics sample。</gray>");
                    return;
                }
                for (var d : totals) {
                    Msg.send(sender, "<gray>" + d.day() + "</gray> <white>产出</white> <#8ce99a>+"
                            + d.produced() + "</#8ce99a> <white>消耗</white> <#ffa8a8>-"
                            + d.consumed() + "</#ffa8a8> <white>净</white> "
                            + (d.net() >= 0 ? "<#8ce99a>+" : "<#ffa8a8>") + d.net() + "</>"
                            + " <gray>锻造 " + d.forged() + " · 碎裂 " + d.breaks() + "</gray>");
                }
                for (String line : svc.trendText().split("<newline>")) {
                    if (!line.isBlank()) {
                        Msg.send(sender, line);
                    }
                }
            }
            case "csv", "daochu" -> {
                int days = clampDays(args.length > 1 ? args[1] : "30", svc.historyDays());
                svc.exportAsync(sender, days);
            }
            case "sample", "caiji" -> {
                boolean ok = svc.sampleNow();
                Msg.prefix(sender, ok ? "<green>已采样一次。</green>" : "<yellow>采样失败（看板关闭或存储不可写），详情见控制台。</yellow>");
            }
            case "gui", "mianban" -> {
                // 切片 3：三页 GUI（实时体检 / 近况趋势 / 分配分布）
                if (!(sender instanceof org.bukkit.entity.Player p)) {
                    Msg.prefix(sender, "<red>GUI 只能在游戏里打开；控制台请用无参命令看文本报表。</red>");
                    return;
                }
                com.forgemaster.metrics.MetricsGui.open(plugin, p);
            }
            default -> {
                String text;
                try {
                    text = new com.forgemaster.metrics.MetricsReporter(plugin).text();
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("生成经济报表失败: " + ex);
                    Msg.prefix(sender, "<red>报表生成失败，详情见控制台。</red>");
                    return;
                }
                for (String line : text.split("<newline>")) {
                    if (!line.isBlank()) {
                        Msg.send(sender, line);
                    }
                }
                Msg.send(sender, "<dark_gray>　用 <white>/zhujian admin metrics trend [天数]</white> 看历史趋势，"
                        + "<white>metrics csv [天数]</white> 导出 CSV，<white>metrics sample</white> 立刻采样。</dark_gray>");
            }
        }
    }

    /** 趋势/导出窗口：非数字或越界一律收敛到 [1, historyDays] */
    private static int clampDays(String raw, int max) {
        int n;
        try {
            n = Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException ex) {
            n = 7;
        }
        int hi = Math.max(1, max);
        return Math.max(1, Math.min(n, hi));
    }

    /**
     * /zhujian power —— 战力评分（v2.23 切片 5 · D）。
     *
     * <p>手里有什么就评什么，不看权限：这是玩家侧的自我认知工具，也是把
     * 「分数为什么这么高」讲清楚的地方（黑盒分数没人会信）。</p>
     */
    private void power(CommandSender sender) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            Msg.prefix(sender, "<red>只有玩家能用这个命令（要评手里的东西）。</red>");
            return;
        }
        if (!plugin.settings().flag("power-score.enabled", true)) {
            Msg.prefix(sender, "<yellow>战力评分已关闭。</yellow> <dark_gray>（config.yml → power-score.enabled）");
            return;
        }
        org.bukkit.inventory.ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            Msg.prefix(sender, "<red>手上没有东西可评。</red>");
            return;
        }
        for (String line : com.forgemaster.score.PowerScore.breakdown(plugin, held)) {
            Msg.send(sender, line);
        }
        long price = com.forgemaster.score.PowerScore.suggestPrice(plugin, held);
        if (price > 0) {
            Msg.send(sender, "<gray>拍卖/订单推荐价约 <white>" + price + "</white> 剑币"
                    + " <dark_gray>（战力 × price-per-power，仅供参考）</dark_gray></gray>");
        }
    }

    /**
     * /zhujian fixattrs [all] —— 属性修复（v2.23.1）。
     *
     * <h2>为什么必须有这个命令</h2>
     * 旧版 {@code baseOf} 会把「已经乘过倍率的 modifier」当成基础值再用一次，
     * 于是老物品越强化越离谱，而且那个虚高值还被当成快照写回盘上（永久化）。
     * 修复后的 {@code apply()} 会在下一次强化 / 蜕变时把数值收敛回配置值，
     * 但**玩家不再动那把剑，它就永远是虚高的**——生存服上这等于留了一把把外挂。
     *
     * <p>所以这里给服主一个明确的清理入口：</p>
     * <ul>
     *   <li>{@code /zhujian fixattrs} —— 修手持那一件（先给玩家看数字，再决定要不要全服扫）</li>
     *   <li>{@code /zhujian fixattrs all} —— 扫所有在线玩家（背包 + 盔甲 + 副手 + 末影箱）</li>
     *   <li>{@code /zhujian fixattrs containers} —— 扫「已加载」区块里的箱子/木桶等容器</li>
     * </ul>
     *
     * <p><b>只能扫在线玩家</b>：离线玩家的背包在 playerdata 里，动它必须加载玩家档案，
     * 那是另一个量级的风险。服里人齐的时候跑一次即可；命令结束会把「扫了多少件、
     * 改了多少件」如实报出来。</p>
     */
    private void fixAttributes(CommandSender sender, String[] args) {
        if (!sender.hasPermission("forgemaster.admin")) {
            Msg.prefix(sender, "<red>权限不足。</red>");
            return;
        }
        var repair = plugin.legacyRepair();
        if (repair == null) {
            Msg.prefix(sender, "<red>收敛模块没起来。</red>");
            return;
        }
        String mode = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "";
        boolean all = mode.equals("all") || mode.equals("全部");
        boolean containers = mode.equals("containers") || mode.equals("container") || mode.equals("容器");

        if (!all && !containers) {
            if (!(sender instanceof org.bukkit.entity.Player p)) {
                Msg.prefix(sender, "<red>控制台请用</red> <white>/zhujian fixattrs all</white>"
                        + " <red>或</red> <white>/zhujian fixattrs containers</white><red>。</red>");
                return;
            }
            org.bukkit.inventory.ItemStack held = p.getInventory().getItemInMainHand();
            if (held.getType().isAir() || !plugin.items().isForged(held)) {
                Msg.prefix(sender, "<red>主手要拿一件锻造品。</red>");
                return;
            }
            boolean changed = repair.repair(held);
            Msg.prefix(sender, changed
                    ? "<green>已收敛。</green> <gray>基础属性 / 吸血 / 减伤 / 气血 / 点燃 全部按当前上限重算。</gray>"
                    : "<yellow>数值本来就合规</yellow> <gray>（没有需要收敛的部分）。</gray>");
            for (String line : attrsOf(held)) {
                Msg.send(sender, line);
            }
            Msg.send(sender, "<dark_gray>　确认无误后用 <white>/zhujian fixattrs all</white> 扫在线玩家，"
                    + "或 <white>/zhujian fixattrs containers</white> 扫已加载的箱子。</dark_gray>");
            return;
        }

        if (containers) {
            int fixed = repair.sweepLoadedContainers();
            plugin.getLogger().info("收敛：已加载容器扫描完成，修正 " + fixed + " 件。");
            Msg.prefix(sender, "<green>容器扫描完成。</green> <gray>修正 <white>" + fixed + "</white> 件。</gray>");
            Msg.send(sender, "<dark_gray>　只扫「已加载」区块的容器 —— 不为了修物品去强制加载世界。"
                    + "未加载区块里的存货等它自然加载后再跑一次即可。</dark_gray>");
            return;
        }

        int players = 0;
        int fixed2 = 0;
        for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            players++;
            fixed2 += repair.sweepPlayer(p);
        }
        plugin.getLogger().info("收敛：扫描 " + players + " 名在线玩家，修正 " + fixed2 + " 件。");
        Msg.prefix(sender, "<green>在线收敛完成。</green> <gray>扫描 <white>" + players
                + "</white> 名玩家，修正 <white>" + fixed2 + "</white> 件。</gray>");
        Msg.send(sender, "<dark_gray>　离线玩家无需手动处理：他们下次上线会被自动扫一遍"
                + "（legacy-repair.on-join）。</dark_gray>");
    }

    /** 把一件物品当前的四个基础属性读出来（修复前后给服主看的对照） */
    private java.util.List<String> attrsOf(org.bukkit.inventory.ItemStack item) {
        java.util.List<String> out = new ArrayList<>();
        var meta = item.getItemMeta();
        if (meta == null) {
            return out;
        }
        StringBuilder sb = new StringBuilder("<gray>当前基础属性：</gray>");
        boolean any = false;
        for (var attr : new org.bukkit.attribute.Attribute[]{
                org.bukkit.attribute.Attribute.ATTACK_DAMAGE,
                org.bukkit.attribute.Attribute.ATTACK_SPEED,
                org.bukkit.attribute.Attribute.ARMOR,
                org.bukkit.attribute.Attribute.ARMOR_TOUGHNESS}) {
            var mods = meta.getAttributeModifiers(attr);
            if (mods == null) {
                continue;
            }
            for (var m : mods) {
                var key = m.getKey();
                if (key == null || !key.getKey().startsWith("fm_")) {
                    continue; // 只报本插件挂的，别把原版/其它插件的算进来
                }
                sb.append(" <white>").append(key.getKey()).append("</white>=")
                        .append(Msg.fmt(m.getAmount(), 2));
                any = true;
            }
        }
        if (any) {
            out.add(sb.toString());
        }
        return out;
    }

    /** E6：自检入口。具名检查项（60+）在 selftest/SelfTestSuite，逐项只读。 */
    private void selftest(CommandSender sender) {
        if (!sender.hasPermission("forgemaster.admin")) {
            Msg.prefix(sender, "<red>权限不足。</red>");
            return;
        }
        var suite = new com.forgemaster.selftest.SelfTestSuite(plugin);
        suite.report(sender, suite.run());
    }

    /** 旧版内联自检（E6 前保留，不再被调用）。 */
    @SuppressWarnings("unused")
    private void legacySelftest(CommandSender sender) {
        if (!sender.hasPermission("forgemaster.admin")) {
            Msg.prefix(sender, "<red>权限不足。</red>");
            return;
        }
        int ok = 0;
        java.util.List<String> errors = new ArrayList<>();
        org.bukkit.inventory.ItemStack sample = null;

        for (ForgeMaterial m : plugin.materials().all()) {
            for (Quality q : Quality.values()) {
                try {
                    org.bukkit.inventory.ItemStack item =
                            plugin.items().buildDirect("SelfTest", m, q, Math.min(99, q.minScore() + 1));
                    var meta = item.getItemMeta();
                    if (meta == null) {
                        throw new IllegalStateException("ItemMeta 为空");
                    }
                    if (!meta.getPersistentDataContainer().has(com.forgemaster.util.Keys.FORGED,
                            org.bukkit.persistence.PersistentDataType.BYTE)) {
                        throw new IllegalStateException("PDC 标记缺失");
                    }
                    if (q.affixCount() > 0 && meta.getAttributeModifiers() == null) {
                        throw new IllegalStateException("属性修饰器为空");
                    }
                    if (sample == null && m.id().equalsIgnoreCase("iron") && q == Quality.LEGENDARY) {
                        sample = item;
                    }
                    ok++;
                } catch (Exception ex) {
                    errors.add(m.id() + "/" + q.name() + " → " + ex);
                }
            }
        }

        for (var quench : plugin.quenches().all()) {
            try {
                var picked = plugin.affixes().pick(quench.pool(), 4);
                if (picked.isEmpty()) {
                    throw new IllegalStateException("词条池为空");
                }
            } catch (Exception ex) {
                errors.add("quench/" + quench.id() + " → " + ex);
            }
        }

        // 界面渲染自检：不依赖在线玩家，只验证槽位与渲染不炸
        try {
            ForgeMaterial first = plugin.materials().all().iterator().next();
            ForgeSession probe = new ForgeSession(java.util.UUID.randomUUID(), "SelfTest", first);
            probe.heatScore(1.0);
            probe.quench(plugin.quenches().all().get(0));
            probe.quality(Quality.EPIC);
            probe.finalScore(80);
            probe.result(plugin.items().buildDirect("SelfTest", first, Quality.EPIC, 80));
            var menu = plugin.forge().menu();

            var heat = menu.buildHeating(probe);
            if (heat == null || heat.getSize() != ForgeMenu.FORGE_SIZE) {
                throw new IllegalStateException("加热界面尺寸错误");
            }
            if (heat.getItem(10) == null || heat.getItem(13) == null) {
                throw new IllegalStateException("加热界面缺少风箱/出炉图标");
            }
            if (heat.getItem(ForgeMenu.QUIT_SLOT) == null) {
                throw new IllegalStateException("加热界面缺少退出键");
            }

            var hammer = menu.buildHammering(probe);
            if (hammer.getItem(ForgeMenu.STRIKE_SLOT) == null) {
                throw new IllegalStateException("锻打界面缺少敲击键");
            }
            for (int i = 0; i < ForgeMenu.TRACK_LEN; i++) {
                if (hammer.getItem(ForgeMenu.TRACK_START + i) == null) {
                    throw new IllegalStateException("轨道第 " + i + " 格为空");
                }
            }

            var quenchInv = menu.buildQuenching(probe, plugin.quenches().all());
            var quenchList = plugin.quenches().all();
            int[] qs = ForgeMenu.QUENCH_SLOTS;
            for (int i = 0; i < quenchList.size() && i < qs.length; i++) {
                var icon = quenchInv.getItem(qs[i]);
                if (icon == null || icon.getItemMeta() == null) {
                    throw new IllegalStateException("淬火槽 " + qs[i] + " 为空");
                }
                String name = Msg.plain(icon.getItemMeta().displayName());
                if (!name.equals(quenchList.get(i).display())) {
                    throw new IllegalStateException("淬火槽 " + qs[i] + " 放错了: " + name);
                }
            }
            if (quenchList.size() > qs.length) {
                throw new IllegalStateException("淬火介质超过 " + qs.length + " 种，界面放不下");
            }

            var result = menu.buildResult(probe, probe.result());
            if (result.getItem(13) == null) {
                throw new IllegalStateException("成品界面没有展品");
            }

            // 叠钢：序列生成 + 轨道渲染 + 点对全序列
            probe.foldSequence().clear();
            probe.foldSequence().addAll(java.util.List.of(0, 4, 8, 2));
            probe.foldCursor(0);
            probe.foldMissed(0);
            probe.foldDeadline(System.currentTimeMillis() + 8000);
            var foldInv = menu.buildFolding(probe);
            for (int i = 0; i < ForgeMenu.TRACK_LEN; i++) {
                if (foldInv.getItem(ForgeMenu.TRACK_START + i) == null) {
                    throw new IllegalStateException("叠钢轨道第 " + i + " 格为空");
                }
            }
            // 模拟点对 4 次
            for (int v : probe.foldSequence()) {
                if (v < 0 || v >= ForgeMenu.TRACK_LEN) {
                    throw new IllegalStateException("折叠序列越界: " + v);
                }
                probe.foldCursor(probe.foldCursor() + 1);
            }
            probe.foldScore((double) probe.foldCursor() / probe.foldSequence().size());
            if (probe.foldScore() < 0.999) {
                throw new IllegalStateException("叠钢满分计算错误: " + probe.foldScore());
            }
            if (probe.layers() != 4 || !"水波纹".equals(probe.pattern())) {
                throw new IllegalStateException("钢纹映射错误: " + probe.layers() + " → " + probe.pattern());
            }

            // 开刃：压力/绿区判定 + 界面渲染
            probe.grindValue(50);
            probe.grindTargetCenter(50);
            probe.grindTargetWidth(8);
            if (!probe.grindInZone()) {
                throw new IllegalStateException("开刃绿区判定失效（中心）");
            }
            probe.grindValue(80);
            if (probe.grindInZone()) {
                throw new IllegalStateException("开刃绿区判定失效（越界）");
            }
            probe.grindValue(50);
            var grindInv = menu.buildGrinding(probe);
            for (int i = 0; i < ForgeMenu.TRACK_LEN; i++) {
                if (grindInv.getItem(ForgeMenu.TRACK_START + i) == null) {
                    throw new IllegalStateException("开刃轨道第 " + i + " 格为空");
                }
            }
            if (grindInv.getItem(10) == null || grindInv.getItem(16) == null) {
                throw new IllegalStateException("开刃缺加压/减压按钮");
            }
            ok++;
        } catch (Exception ex) {
            errors.add("gui → " + ex);
        }

        // 结算公式自检：满配必须 100，关工序后权重仍归一
        try {
            double full = ForgeManager.weightedScore(1, 1, 1, 1, 15, true, true);
            if (Math.abs(full - 100.0) > 0.01) {
                throw new IllegalStateException("满配总分应为 100，实际 " + full);
            }
            double noFold = ForgeManager.weightedScore(1, 1, 0, 1, 15, false, true);
            if (Math.abs(noFold - 100.0) > 0.01) {
                throw new IllegalStateException("关闭叠钢后满配应为 100，实际 " + noFold);
            }
            double noBoth = ForgeManager.weightedScore(1, 1, 0, 0, 15, false, false);
            if (Math.abs(noBoth - 100.0) > 0.01) {
                throw new IllegalStateException("关闭两道工序后满配应为 100，实际 " + noBoth);
            }
            double zero = ForgeManager.weightedScore(0, 0, 0, 0, 0, true, true);
            if (zero != 0.0) {
                throw new IllegalStateException("全 0 应得 0 分，实际 " + zero);
            }
            // 单调性：某道变好，总分不能变低
            double a = ForgeManager.weightedScore(0.5, 0.5, 0.5, 0.5, 7, true, true);
            double b = ForgeManager.weightedScore(0.5, 0.5, 0.9, 0.5, 7, true, true);
            if (b < a) {
                throw new IllegalStateException("叠钢变好反而掉分");
            }
            ok++;
        } catch (Exception ex) {
            errors.add("score → " + ex);
        }

        // 档案/专精/图鉴自检
        try {
            java.util.UUID uid = Bukkit.getOfflinePlayer("SelfTest").getUniqueId();
            StatsStore.Stats before = plugin.stats().get(uid);
            int forged0 = before.forged;
            plugin.stats().record(uid, "SelfTest", Quality.FINE, 60.5,
                    plugin.materials().all().iterator().next().id(), "水波纹");
            StatsStore.Stats after = plugin.stats().get(uid);
            if (after.forged != forged0 + 1) {
                throw new IllegalStateException("锻造次数没累加");
            }
            if (!after.codex.contains(plugin.materials().all().iterator().next().id().toLowerCase()
                    + ":" + Quality.FINE.name())) {
                throw new IllegalStateException("图鉴没记上");
            }
            if (!after.patterns.contains("水波纹")) {
                throw new IllegalStateException("钢纹图鉴没记上");
            }
            if (after.level < 1) {
                throw new IllegalStateException("专精等级异常: " + after.level);
            }
            if (StatsStore.expToNext(1) <= 0) {
                throw new IllegalStateException("升级经验配置异常");
            }
            ok++;
        } catch (Exception ex) {
            errors.add("stats → " + ex);
        }

        // 状态机端到端：无玩家把 锻打→叠钢→开刃→淬火 全跑一遍
        try {
            ForgeMaterial m = plugin.materials().all().iterator().next();
            ForgeSession sim = new ForgeSession(java.util.UUID.randomUUID(), "SelfTest", m);
            sim.heatScore(1.0);
            sim.addStrike(ForgeSession.Hit.PERFECT);
            sim.phase(com.forgemaster.forge.ForgePhase.FOLDING);
            plugin.forge().simulateStartFolding(sim);
            if (sim.foldSequence().isEmpty()) {
                throw new IllegalStateException("叠钢没生成序列");
            }
            if (sim.foldSequence().size() < 3) {
                throw new IllegalStateException("折叠序列太短: " + sim.foldSequence().size());
            }
            plugin.forge().simulateFoldAll(sim);
            if (sim.phase() != com.forgemaster.forge.ForgePhase.GRINDING) {
                throw new IllegalStateException("叠钢完没进开刃，停在 " + sim.phase());
            }
            if (sim.foldScore() < 0.999) {
                throw new IllegalStateException("全点对却没满分: " + sim.foldScore());
            }
            if (sim.layers() != sim.foldSequence().size()) {
                throw new IllegalStateException("层数与序列长度不符");
            }
            plugin.forge().simulateGrind(sim);
            if (sim.phase() != com.forgemaster.forge.ForgePhase.QUENCHING) {
                throw new IllegalStateException("开刃完没进淬火，停在 " + sim.phase());
            }
            if (sim.grindTicksTotal() < plugin.settings().grindDurationTicks()) {
                throw new IllegalStateException("开刃时长不足");
            }
            if (sim.grindScore() < 0 || sim.grindScore() > 1) {
                throw new IllegalStateException("开刃分越界: " + sim.grindScore());
            }
            if (sim.grindValue() < 0 || sim.grindValue() > 100) {
                throw new IllegalStateException("压力越界: " + sim.grindValue());
            }
            double perfect = ForgeManager.weightedScore(sim.heatScore(), sim.hammerScore(),
                    sim.foldScore(), 1.0, 15, true, true);
            if (perfect < 90) {
                throw new IllegalStateException("全满分却不到 90 分: " + perfect);
            }
            ok++;
        } catch (Exception ex) {
            errors.add("sim → " + ex);
        }

        // 委托：生成 / 判定
        try {
            java.util.UUID owner = java.util.UUID.nameUUIDFromBytes("ForgingMasterSelfTest".getBytes());
            plugin.commissions().of(owner).clear();
            var orders = plugin.commissions().ensureFor(owner);
            if (orders.size() != plugin.settings().commissionSlots()) {
                throw new IllegalStateException("委托数量不对: " + orders.size());
            }
            for (var c : orders) {
                if (c.expired()) {
                    throw new IllegalStateException("新委托一生成就过期");
                }
                if (c.rewardRep() <= 0 || c.id() == null || c.id().isBlank()) {
                    throw new IllegalStateException("委托奖励/id 异常");
                }
            }
            // 拿一把普通品质的铁剑去判「要精良」的委托，必须被拒
            ForgeMaterial anyMat = plugin.materials().all().iterator().next();
            org.bukkit.inventory.ItemStack probe = plugin.items()
                    .buildDirect("SelfTest", anyMat, Quality.COMMON, 40);
            var strict = new com.forgemaster.commission.Commission("TEST", owner,
                    com.forgemaster.commission.Commission.Kind.QUALITY, "测试客人",
                    "any", Quality.LEGENDARY, null, 0, 0, "IRON_INGOT", 1, 10,
                    System.currentTimeMillis() + 60000);
            if (strict.missing(plugin.items(), probe) == null) {
                throw new IllegalStateException("普通品质居然满足了传说委托");
            }
            var loose = new com.forgemaster.commission.Commission("TEST2", owner,
                    com.forgemaster.commission.Commission.Kind.QUALITY, "测试客人",
                    "any", Quality.ROUGH, null, 0, 0, "IRON_INGOT", 1, 10,
                    System.currentTimeMillis() + 60000);
            if (loose.missing(plugin.items(), probe) != null) {
                throw new IllegalStateException("粗糙委托应当能收下普通剑: "
                        + loose.missing(plugin.items(), probe));
            }
            if (loose.missing(plugin.items(), new org.bukkit.inventory.ItemStack(Material.IRON_SWORD)) == null) {
                throw new IllegalStateException("非锻造品居然能交货");
            }

            // 存盘 → 重读，委托必须原样回来
            int before = plugin.commissions().of(owner).size();
            String firstId = plugin.commissions().of(owner).isEmpty()
                    ? null : plugin.commissions().of(owner).get(0).id();
            plugin.commissions().save();
            plugin.commissions().load();
            var reloaded = plugin.commissions().of(owner);
            if (reloaded.size() != before) {
                throw new IllegalStateException("委托存盘后数量变了: " + before + " → " + reloaded.size());
            }
            if (firstId != null && !reloaded.get(0).id().equals(firstId)) {
                throw new IllegalStateException("委托 id 没保住");
            }
            plugin.commissions().of(owner).clear();
            ok++;
        } catch (Exception ex) {
            errors.add("commission → " + ex);
        }

        // 耐久 / 研磨 / 声望
        try {
            ForgeMaterial m = plugin.materials().all().iterator().next();
            org.bukkit.inventory.ItemStack item = plugin.items()
                    .buildDirect("SelfTest", m, Quality.EPIC, 80);
            int maxDur = plugin.items().maxDurabilityOf(item);
            int dur = plugin.items().durabilityOf(item);
            if (maxDur <= 0 || dur != maxDur) {
                throw new IllegalStateException("耐久初值异常: " + dur + "/" + maxDur);
            }
            plugin.items().setDurability(item, 10);
            if (plugin.items().durabilityOf(item) != 10) {
                throw new IllegalStateException("耐久写回失败");
            }
            boolean broken = plugin.items().setDurability(item, 0);
            if (!broken) {
                throw new IllegalStateException("耐久归零没判成崩刃");
            }
            boolean hasWarning = plugin.items().loreOf(item).stream()
                    .anyMatch(c -> Msg.plain(c).contains("崩刃"));
            if (!hasWarning) {
                throw new IllegalStateException("崩刃提示没写进 Lore");
            }
            // 声望称号分档
            if (!"学徒".equals(StatsStore.titleOf(0))
                    || !"匠人".equals(StatsStore.titleOf(120))
                    || !"铸剑圣手".equals(StatsStore.titleOf(2500))) {
                throw new IllegalStateException("称号分档错误");
            }
            if (StatsStore.reputationToNextTitle(0) != 100) {
                throw new IllegalStateException("下一档声望提示错误");
            }
            ok++;
        } catch (Exception ex) {
            errors.add("durability → " + ex);
        }

        // 比试：同种子必须得到同一套随机条件
        try {
            java.util.UUID u = java.util.UUID.nameUUIDFromBytes("DuelSelfTest".getBytes());
            ForgeMaterial m = plugin.materials().all().iterator().next();
            ForgeSession s1 = new ForgeSession(u, "SelfTestA", m);
            ForgeSession s2 = new ForgeSession(u, "SelfTestB", m);
            s1.seed(424242L);
            s2.seed(424242L);
            int a1 = s1.rnd().nextInt(1000);
            int a2 = s2.rnd().nextInt(1000);
            if (a1 != a2) {
                throw new IllegalStateException("同种子随机结果不一致: " + a1 + " vs " + a2);
            }
            s1.seed(1L);
            s2.seed(2L);
            if (s1.rnd().nextInt(1000000) == s2.rnd().nextInt(1000000)) {
                throw new IllegalStateException("不同种子居然产生相同结果");
            }
            if (plugin.duels() == null) {
                throw new IllegalStateException("比试管理器没初始化");
            }
            ok++;
        } catch (Exception ex) {
            errors.add("duel → " + ex);
        }

        // 材料分级：标记读写 / 消耗优先级 / 上限加成
        try {
            org.bukkit.inventory.ItemStack raw = new org.bukkit.inventory.ItemStack(Material.IRON_INGOT, 3);
            var ref = com.forgemaster.material.MaterialGrades.stamp(raw,
                    com.forgemaster.material.MaterialGrade.REFINED);
            var cel = com.forgemaster.material.MaterialGrades.stamp(raw,
                    com.forgemaster.material.MaterialGrade.CELESTIAL);
            if (com.forgemaster.material.MaterialGrades.gradeOf(ref)
                    != com.forgemaster.material.MaterialGrade.REFINED) {
                throw new IllegalStateException("精炼料标记没保住");
            }
            if (com.forgemaster.material.MaterialGrades.gradeOf(cel)
                    != com.forgemaster.material.MaterialGrade.CELESTIAL) {
                throw new IllegalStateException("天外料标记没保住");
            }
            if (com.forgemaster.material.MaterialGrades.gradeOf(raw)
                    != com.forgemaster.material.MaterialGrade.COMMON) {
                throw new IllegalStateException("无标记应当算普通料");
            }
            // 上限加成必须真加到分数上
            double base = 80.0;
            ForgeMaterial anyM = plugin.materials().all().iterator().next();
            ForgeSession gs = new ForgeSession(java.util.UUID.nameUUIDFromBytes("GradeTest".getBytes()),
                    "SelfTest", anyM);
            gs.grade(com.forgemaster.material.MaterialGrade.CELESTIAL);
            double boosted = base * (1.0 + gs.grade().scoreBonus() / 100.0);
            if (boosted <= base) {
                throw new IllegalStateException("天外料没有抬上限");
            }
            if (gs.grade().extraAffixes() <= 0) {
                throw new IllegalStateException("天外料没有额外词条");
            }
            ok++;
        } catch (Exception ex) {
            errors.add("grade → " + ex);
        }

        // 剑谱：登记结构 / 复刻度 / 存盘往返
        try {
            ForgeMaterial m = plugin.materials().all().iterator().next();
            org.bukkit.inventory.ItemStack manualProbe = plugin.items()
                    .buildDirect("SelfTest", m, Quality.LEGENDARY, 88);
            // 谱必须从这把剑本身派生，才是真正的「自比对」
            var probeMeta = manualProbe.getItemMeta();
            var probePdc = probeMeta == null ? null : probeMeta.getPersistentDataContainer();
            String probeCsv = probePdc == null ? null
                    : probePdc.get(Keys.AFFIX_LIST, org.bukkit.persistence.PersistentDataType.STRING);
            List<String> probeAffixes = new ArrayList<>();
            if (probeCsv != null && !probeCsv.isBlank()) {
                for (String part : probeCsv.split(",")) {
                    if (!part.isBlank()) {
                        probeAffixes.add(part.trim());
                    }
                }
            }
            Integer probeLayers = probePdc == null ? null
                    : probePdc.get(Keys.LAYERS, org.bukkit.persistence.PersistentDataType.INTEGER);
            var entry = new com.forgemaster.manuals.ManualEntry("T001",
                    java.util.UUID.nameUUIDFromBytes("ManualTest".getBytes()), "SelfTest",
                    "测试谱", m.id(), Quality.LEGENDARY, 88, "水波纹",
                    probeLayers == null ? 0 : probeLayers, "water",
                    probeAffixes, 12345L,
                    System.currentTimeMillis());
            // 拿它自己去比，复刻度应当接近满分
            double self = entry.copyRateOf(plugin.items(), manualProbe);
            if (self < 0.55) {
                throw new IllegalStateException("自比对复刻度过低: " + Msg.fmt(self * 100, 1) + "%");
            }
            // 拿一把粗糙货去比，复刻度必须明显更低
            org.bukkit.inventory.ItemStack junk = plugin.items()
                    .buildDirect("SelfTest", m, Quality.ROUGH, 20);
            double off = entry.copyRateOf(plugin.items(), junk);
            if (off >= self) {
                throw new IllegalStateException("粗糙货复刻度居然不低于原品");
            }
            if (entry.copyRateOf(plugin.items(),
                    new org.bukkit.inventory.ItemStack(Material.DIRT)) > 0.01) {
                throw new IllegalStateException("非锻造品居然算复刻度");
            }
            // 存盘 → 重读
            int before = plugin.manuals().size();
            plugin.manuals().save();
            plugin.manuals().load();
            if (plugin.manuals().size() != before) {
                throw new IllegalStateException("剑谱存盘后数量变了: " + before
                        + " → " + plugin.manuals().size());
            }
            ok++;
        } catch (Exception ex) {
            errors.add("manual → " + ex);
        }

        // 大赛端到端：开赛 → 上报 → 上榜 → 结束
        try {
            var cm = plugin.contests();
            if (cm == null || !plugin.settings().contestEnabled()) {
                throw new IllegalStateException("大赛管理器没起来");
            }
            if (cm.running()) {
                cm.finishNow();
            }
            cm.forceStart(null);
            if (!cm.running()) {
                throw new IllegalStateException("强开赛后状态没变 running");
            }
            var asking = cm.materialAsking();
            if (asking == null) {
                throw new IllegalStateException("开赛后没有指定剑胚");
            }
            ForgeMaterial cmat = plugin.materials().get(asking.id());
            if (cmat == null) {
                throw new IllegalStateException("大赛剑胚 id 不在配置里: " + asking.id());
            }
            // 模拟两位参赛者交剑
            java.util.UUID ua = java.util.UUID.nameUUIDFromBytes("ContestA".getBytes());
            java.util.UUID ub = java.util.UUID.nameUUIDFromBytes("ContestB".getBytes());
            ForgeSession ca = new ForgeSession(ua, "ContestA", cmat);
            ForgeSession cb = new ForgeSession(ub, "ContestB", cmat);
            ca.finalScore(72.5);
            cb.finalScore(88.0);
            cm.report(ca);
            cm.report(cb);
            List<String> board = cm.leaderboard();
            if (board.size() != 2) {
                throw new IllegalStateException("上榜人数不对: " + board.size());
            }
            // 第一名必须是 88 分那位
            if (!board.get(0).contains("ContestB")) {
                throw new IllegalStateException("排行榜第一名算错了: " + board.get(0));
            }
            // 低分重交不该顶掉高分
            ca.finalScore(50.0);
            cm.report(ca);
            if (cm.leaderboard().size() != 2) {
                throw new IllegalStateException("重复上报算成新人了");
            }
            // 结束并清场
            cm.finishNow();
            if (cm.running()) {
                throw new IllegalStateException("结束后仍是 running");
            }
            if (!cm.leaderboard().isEmpty()) {
                throw new IllegalStateException("结束后榜单没清空");
            }
            ok++;
        } catch (Exception ex) {
            errors.add("contest → " + ex);
        }

        // 剑谱临摹端到端：登记 → 开临摹 → 结算复刻度
        try {
            ForgeMaterial m = plugin.materials().all().iterator().next();
            org.bukkit.inventory.ItemStack made = plugin.items()
                    .buildDirect("SelfTest", m, Quality.EPIC, 78);
            var entry = new com.forgemaster.manuals.ManualEntry("TC1",
                    java.util.UUID.nameUUIDFromBytes("CopyTest".getBytes()), "SelfTest",
                    "临摹样本", m.id(), Quality.EPIC, 78, "水波纹", 5, "water",
                    new ArrayList<>(List.of("crit_chance")), 9876L, System.currentTimeMillis());
            plugin.manuals().put(entry);

            java.util.UUID cu = java.util.UUID.nameUUIDFromBytes("CopyRun".getBytes());
            ForgeSession cs = new ForgeSession(cu, "SelfTest", m);
            cs.manualId("TC1");
            cs.finalScore(77.0);
            cs.result(made);
            plugin.manuals().reportCopy(cs, null);
            if (cs.copyRate() <= 0) {
                throw new IllegalStateException("临摹结算没算出复刻度");
            }
            if (plugin.manuals().get("TC1").copies() != 1) {
                throw new IllegalStateException("临摹次数没记上");
            }
            plugin.manuals().remove("TC1");
            if (plugin.manuals().get("TC1") != null) {
                throw new IllegalStateException("剑谱删不掉");
            }
            ok++;
        } catch (Exception ex) {
            errors.add("copy → " + ex);
        }

        // 铁砧演出自检：真实生成 Display 实体再敲一次，最后清掉
        try {
            org.bukkit.World world = Bukkit.getWorlds().get(0);
            Location stageLoc = world.getSpawnLocation().clone().add(0, 1, 0);
            ForgeMaterial first = plugin.materials().all().iterator().next();
            ForgeSession probe = new ForgeSession(java.util.UUID.randomUUID(), "SelfTest", first);
            probe.quench(plugin.quenches().all().get(0));
            probe.quality(Quality.EPIC);
            probe.finalScore(80);

            var stage = new com.forgemaster.stage.ForgeStage(plugin, probe, stageLoc, false);
            stage.spawn();
            stage.heatTick(90);
            stage.strike(ForgeSession.Hit.PERFECT);
            stage.strike(ForgeSession.Hit.GOOD);
            stage.strike(ForgeSession.Hit.MISS);
            stage.quench(probe.quench(), plugin.items().buildDirect("SelfTest", first, Quality.EPIC, 80));
            stage.despawn();

            // 残留检查
            final int[] leftover = {0};
            world.getNearbyEntities(stageLoc, 6, 6, 6,
                    e -> e.getScoreboardTags().contains(com.forgemaster.stage.ForgeStage.TAG))
                    .forEach(e -> leftover[0]++);
            if (leftover[0] > 0) {
                throw new IllegalStateException("演出实体没清干净，残留 " + leftover[0] + " 个");
            }
            ok++;
        } catch (Exception ex) {
            errors.add("stage → " + ex);
        }

        Msg.prefix(sender, "<green>自检完成：<white>" + ok + "</white> 个成品组合通过，失败 <white>"
                + errors.size() + "</white> 个。</green>");
        for (String err : errors) {
            Msg.send(sender, "<red>✗ " + err);
        }
        if (sample != null) {
            Msg.send(sender, "<gray>样品: <white>" + Msg.plain(sample.getItemMeta().displayName()));
            for (var line : sample.getItemMeta().lore()) {
                Msg.send(sender, "<dark_gray>  | <gray>" + Msg.plain(line));
            }
        }
    }

    // ------------------------------------------------------------ 极限锻造（D3）

    /** /zhujian forge risk：预设下一把剑按极限模式开工；/zhujian forge cancel 撤销预设 */
    private void forgeRisk(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>极限锻造要在游戏里用。</red>");
            return;
        }
        if (args.length < 2 || !"risk".equalsIgnoreCase(args[1]) && !"cancel".equalsIgnoreCase(args[1])) {
            Msg.prefix(p, "<gray>用法：<white>/zhujian forge risk</white> 预设极限锻造（下一把剑生效）· "
                    + "<white>/zhujian forge cancel</white> 撤销");
            return;
        }
        if ("cancel".equalsIgnoreCase(args[1])) {
            com.forgemaster.forge.RiskMode.disarm(p.getUniqueId());
            Msg.prefix(p, "<gray>已撤销极限锻造预设。");
            return;
        }
        if (!com.forgemaster.forge.RiskMode.enabled(plugin)) {
            Msg.prefix(p, "<red>极限锻造没开（risk.enabled = false）。</red>");
            return;
        }
        if (plugin.forge().isBusy(p)) {
            Msg.prefix(p, "<red>你手上还有活儿没干完，干完再预设。</red>");
            return;
        }
        com.forgemaster.forge.RiskMode.arm(p.getUniqueId());
        Msg.prefix(p, "<#ff5252><bold>极限锻造已预设</bold></#ff5252><gray>：下一把剑锻打更快、命中区更窄、"
                + "成功分数 +" + String.format(java.util.Locale.ROOT, "%.0f",
                (com.forgemaster.forge.RiskMode.scoreMultiplier(plugin) - 1) * 100)
                + "%，但每个阶段都可能炸剑（材料全失、连锻断）。5 分钟内选剑胚生效。");
        plugin.forge().openMenu(p);
    }

    // ------------------------------------------------------------ 世界事件（D4）

    /** /zhujian event [status|start <id>|stop]，start/stop 需要管理员权限 */
    private void worldEvent(CommandSender sender, String[] args) {
        var ev = plugin.events();
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        if ("status".equals(sub)) {
            Msg.prefix(sender, ev.statusLine());
            return;
        }
        if (!sender.hasPermission("forgemaster.admin")) {
            Msg.prefix(sender, "<red>只有管理员能手动开关世界事件。</red>");
            return;
        }
        if ("start".equals(sub)) {
            Msg.prefix(sender, ev.startManual(args.length >= 3 ? args[2] : null));
        } else if ("stop".equals(sub)) {
            Msg.prefix(sender, ev.stopManual());
        } else {
            Msg.prefix(sender, "<gray>用法：<white>/zhujian event</white> 看状态 · "
                    + "<white>start <id></white> 手动开启 · <white>stop</white> 结束（管理员）");
        }
    }

    // ------------------------------------------------------------ 商会

    private void shop(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>商会要在游戏里打开。</red>");
            return;
        }
        if (!plugin.shop().enabled()) {
            Msg.prefix(p, "<red>商会没开。</red>");
            return;
        }
        plugin.shop().open(p, 1);
    }

    /** /zhujian blackmarket|heishi：黑市——每日轮换特价（轻量留存拓展） */
    private void blackmarket(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>黑市要在游戏里打开。</red>");
            return;
        }
        var bm = plugin.blackmarket();
        if (bm == null || !bm.enabled()) {
            Msg.prefix(p, "<red>黑市今天没开。</red>");
            return;
        }
        bm.open(p);
    }

    /** /zhujian signin|qiandao：每日签到——每天首次领奖，7 天一轮递增（轻量留存拓展） */
    private void signin(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            Msg.prefix(sender, "<red>签到要在游戏里领。</red>");
            return;
        }
        var daily = plugin.dailyReward();
        if (daily == null || !daily.enabled()) {
            Msg.prefix(p, "<red>每日签到暂未开放。</red>");
            return;
        }
        daily.sign(p);
    }

    /** /zhujian env：控制台输出文字，玩家同时打开环境修正面板（w7-env） */
    private void env(CommandSender sender) {
        com.forgemaster.env.EnvContext env;
        if (sender instanceof Player player) {
            env = plugin.env().resolve(player);
        } else {
            env = plugin.env().resolve((org.bukkit.World) null);
        }
        Msg.prefix(sender, "<gray>当前环境：<white>" + env.weatherLabel() + "</white> · <white>"
                + env.timeLabel() + "</white>");
        Msg.send(sender, "<gray>  保温修正 <white>" + signedPct(env.heatBonus) + "</white>（加热降温倍率 × "
                + Msg.fmt(env.heatDecayMultiplier(), 2) + "）");
        Msg.send(sender, "<gray>  淬火收益 <white>" + signedPct(env.quenchBonus) + "</white>");
        Msg.send(sender, "<gray>  叠钢时长 <white>" + (env.foldSecondsBonus >= 0 ? "+" : "")
                + Msg.fmt(env.foldSecondsBonus, 1) + "s</white>");
        Msg.send(sender, "<gray>  开刃绿区 <white>" + (env.grindWidthBonus >= 0 ? "+" : "")
                + Msg.fmt(env.grindWidthBonus, 1) + "</white>");
        if (sender instanceof Player player) {
            player.openInventory(com.forgemaster.env.EnvPanel.build(env));
        }
    }

    private static String signedPct(double v) {
        return (v > 0 ? "+" : "") + Msg.fmt(v * 100, 0) + "%";
    }

    /** /zhujian story：查看当前剧情章节（w8 D5） */
    private void story(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Msg.prefix(sender, "<red>只能由玩家查看剧情。</red>");
            return;
        }
        var story = plugin.story();
        if (story == null) {
            Msg.prefix(player, "<red>剧情尚未加载。</red>");
            return;
        }
        Msg.prefix(player, "<gray>剧情进度（" + story.completedCount(player.getUniqueId()) + "/"
                + story.total() + "）：" + story.statusLine(player.getUniqueId()));
    }

    /** /zhujian spectrum|mingjian|pu：名剑谱（拓展第二档） */
    private void spectrum(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Msg.prefix(sender, "<red>只能由玩家查看名剑谱。</red>");
            return;
        }
        var sp = plugin.spectrum();
        if (sp == null || !sp.enabled()) {
            Msg.prefix(player, "<red>名剑谱暂未开放。</red>");
            return;
        }
        com.forgemaster.spectrum.SpectrumGui.open(plugin, player);
    }

    /** /zhujian pass|tongxingzheng：赛季通行证（拓展第二档） */
    private void seasonPass(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Msg.prefix(sender, "<red>只能由玩家查看赛季通行证。</red>");
            return;
        }
        var sp = plugin.seasonPass();
        if (sp == null || !sp.enabled()) {
            Msg.prefix(player, "<red>赛季通行证暂未开放。</red>");
            return;
        }
        com.forgemaster.pass.PassGui.open(plugin, player);
    }

    /** /zhujian halloffame set｜reload：设置名人堂盔甲架位置（w7-env E1） */
    private void hallOfFame(CommandSender sender, String[] args) {
        if (!sender.hasPermission("forgemaster.admin")) {
            Msg.prefix(sender, "<red>权限不足。</red>");
            return;
        }
        var hall = plugin.hallOfFame();
        if (hall == null) {
            Msg.prefix(sender, "<red>名人堂尚未加载。</red>");
            return;
        }
        String op = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if ("set".equals(op)) {
            if (!(sender instanceof Player player)) {
                Msg.prefix(sender, "<red>请由玩家站在目标位置执行 set。</red>");
                return;
            }
            hall.setLocation(player.getLocation().getBlock().getLocation().add(0.5, 0, 0.5));
            Msg.prefix(player, "<green>名人堂位置已设为当前位置，盔甲架已刷新。</green>");
            return;
        }
        if ("clear".equals(op)) {
            int n = hall.clearAll();
            Msg.prefix(sender, "<gray>已清理 <white>" + n + "</white> 个名人堂盔甲架。</gray>");
            return;
        }
        Msg.prefix(sender, "<gray>用法: /zhujian halloffame set｜clear</gray>");
    }

    /**
     * /zhujian hall set｜show｜del —— 名人殿堂（拓展第一档，ItemDisplay 实体展示）。
     *
     * <p>set = 把脚下设为殿堂原点（写 hall.yml 的 shrine 段）；show = 按当前排行
     * 立即重摆前三名（平时 30s 定时任务自动重刷）；del = 清空殿堂数据并撤掉实体。</p>
     */
    private void hallShrine(CommandSender sender, String[] args) {
        if (!sender.hasPermission("forgemaster.admin")) {
            Msg.prefix(sender, "<red>权限不足。</red>");
            return;
        }
        var shrine = plugin.hallShrine();
        if (shrine == null) {
            Msg.prefix(sender, "<red>名人殿堂尚未加载。</red>");
            return;
        }
        String op = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (op) {
            case "set" -> {
                if (!(sender instanceof Player player)) {
                    Msg.prefix(sender, "<red>请由玩家站在目标位置执行 set。</red>");
                    return;
                }
                Location at = player.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
                at.setYaw(player.getLocation().getYaw());
                shrine.setOrigin(at);
                Msg.prefix(player, "<green>名人殿堂原点已设在脚下（左右 1.5 格为第 2 / 3 名展位），已重摆。");
            }
            case "show" -> {
                if (shrine.origin() == null) {
                    Msg.prefix(sender, "<red>殿堂还没设原点，先 <white>/zhujian hall set</white>。</red>");
                    return;
                }
                shrine.refresh();
                Msg.prefix(sender, "<green>已按当前排行重摆名人殿堂（前三名）。</green>");
            }
            case "del", "delete", "remove", "clear" -> {
                shrine.clearOrigin();
                Msg.prefix(sender, "<gray>殿堂原点已删除，展示实体已撤掉。</gray>");
            }
            default -> Msg.prefix(sender, "<gray>用法: <white>/zhujian hall set</white> 设原点｜"
                    + "<white>show</white> 立即重摆｜<white>del</white> 删除");
        }
    }

    private void help(CommandSender sender, String label) {
        Msg.send(sender, "<dark_gray>──── <gradient:#ffd166:#ff7a18><bold>铸剑大师</bold></gradient> <dark_gray>────");
        Msg.send(sender, "<white>/" + label + " <gray>— 打开锻造台");
        Msg.send(sender, "<white>/" + label + " stats [玩家] <gray>— 锻造档案 / 专精 / 收集度");
        Msg.send(sender, "<white>/" + label + " codex <gray>— 锻造图鉴");
        Msg.send(sender, "<white>/" + label + " env <gray>— 当前天气 / 时辰 / 锻造修正面板");
        Msg.send(sender, "<white>/" + label + " story <gray>— 查看当前剧情章节");
        Msg.send(sender, "<white>/" + label + " config check <gray>— 配置体检：盘上缺哪些必需键 + 那些「只写在代码里」的实际生效值");
        Msg.send(sender, "<white>/" + label + " metrics <gray>— 经济体检：剑币产出/消耗构成、总量、基尼系数（服主）");
        Msg.send(sender, "<white>/" + label + " givefull <gray>— 懒人指令：一次发出满配锻造品（满强化 · 满蜕变 · 满词条 · 满宝石）");
        if (sender.hasPermission("forgemaster.admin")) {
            Msg.send(sender, "<white>/" + label + " halloffame set <gray>— 把名人堂盔甲架设在脚下（clear 清理）");
            Msg.send(sender, "<white>/" + label + " hall set|show|del <gray>— 名人殿堂：脚下设原点 / 立即重摆前三 / 删除");
        }
        Msg.send(sender, "<white>/" + label + " profile [玩家] <gray>— 档案面板（剑币 / 连锻 / 声望 / 成就）");
        Msg.send(sender, "<white>/" + label + " talent <gray>— 匠人天赋（每 5 级 1 点：add <id> / reset）");
        Msg.send(sender, "<white>/" + label + " rank [coins|reputation|streak|level] <gray>— 排行榜 GUI（text 看文字版）");
        Msg.send(sender, "<white>/" + label + " season <gray>— 查看当前赛季");
        Msg.send(sender, "<white>/" + label + " board <gray>— 锻造委托板（交货换声望）");
        Msg.send(sender, "<white>/" + label + " duel <玩家> <gray>— 发起比试（accept/deny 应答）");
        Msg.send(sender, "<white>/" + label + " repair <gray>— 研磨修复手上的剑");
        Msg.send(sender, "<white>/" + label + " manual <gray>— 剑谱库（点谱直接临摹）");
        Msg.send(sender, "<white>/" + label + " record <gray>— 把手上这把登记成剑谱");
        Msg.send(sender, "<white>/" + label + " copy <谱号> <gray>— 临摹（同种子复刻，stop 取消）");
        Msg.send(sender, "<white>/" + label + " contest <gray>— 锻造大赛状态 / list 看榜");
        Msg.send(sender, "<white>/" + label + " grade <gray>— 材料分级说明（管理员 up / give）");
        Msg.send(sender, "<white>/" + label + " auction <gray>— 拍卖行（挂单/竞价/一口价/领取）");
        Msg.send(sender, "<white>/" + label + " order <gray>— 锻造订单（post 发单悬赏 / cancel 取消 / 面板接单交付）");
        Msg.send(sender, "<white>/" + label + " master 玩家名 <gray>— 向在线玩家发出拜师邀请（master leave / expel 徒弟名 / 不带参看状态）");
        Msg.send(sender, "<white>/" + label + " accept <gray>— 收下拜师邀请");
        Msg.send(sender, "<white>/" + label + " guild <gray>— 行会商会（create 建会 / invite 邀人 / deposit 存金库 / list 全服行会 / upgrade 扩容）");
        Msg.send(sender, "<white>/" + label + " salvage <gray>— 熔掉手上的锻造品，回一半材料");
        Msg.send(sender, "<white>/" + label + " top <gray>— 锻造名人堂");
        Msg.send(sender, "<white>/" + label + " achievements <gray>— 成就界面（已达成 / 未达成 / 进度）");
        Msg.send(sender, "<white>/" + label + " shop <gray>— 商会（用剑币买原料与图纸）");
        Msg.send(sender, "<white>/" + label + " blackmarket <gray>— 黑市（每日轮换特价，限购）");
        Msg.send(sender, "<white>/" + label + " signin <gray>— 每日签到（7 天一轮递增，第 7 天大奖）");
        Msg.send(sender, "<white>/" + label + " gem <gray>— 宝石台（开孔 / 熔炼 / 镶嵌 / 拆卸 / 合成）");
        Msg.send(sender, "<dark_gray>    也可以直接 gem socket | smelt | inlay | extract | merge");
        Msg.send(sender, "<white>/" + label + " strengthen <gray>— 铁砧强化：主手拿剑右键铁砧放上去，手持重锤左键落锤（info 看明牌概率）");
        Msg.send(sender, "<dark_gray>    strengthen sim <目标> 看单档期望 · strengthen curve 看六档曲线表");
        Msg.send(sender, "<white>/" + label + " hammer <gray>— 领一把铁匠重锤（原版重锤 MACE 也认）");
        Msg.send(sender, "<white>/" + label + " strengthen strip <序号> <gray>— 剥一条词条成「词条符」（可交易、可存箱）");
        Msg.send(sender, "<white>/" + label + " strengthen graft <gray>— 主手锻造品 + 副手词条符：把词条装进去");
        Msg.send(sender, "<white>/" + label + " imprint merge <gray>— 3 张同名词条符 → 1 张 ×1.5 的同名词条符");
        Msg.send(sender, "<white>/" + label + " dissolve <gray>— 剑魄分解：拆掉手上锻造品换「剑尘」（强化材料不够时自动抵扣）");
        Msg.send(sender, "<white>/" + label + " enchant <gray>— 附魔书镶孔（主手装备 + 副手附魔书，与宝石共用孔位）");
        if (sender.hasPermission("forgemaster.admin")) {
            Msg.send(sender, "<white>/" + label + " give <玩家> <剑胚> [品质] [分数] <gray>— 直接发一把");
            Msg.send(sender, "<dark_gray>    装备用 <white>armor-<剑胚>-<部位></white>，例如 "
                    + "<white>give Steve armor-iron-chestplate LEGENDARY 90</white>");
            Msg.send(sender, "<white>/" + label + " gem give <玩家|all> <类型|random> [L1~L5] [数量] "
                    + "<gray>— 发宝石");
            Msg.send(sender, "<white>/" + label + " gem list <gray>— 列出所有宝石 id / 中文名 / 效果");
            Msg.send(sender, "<white>/" + label + " reload <gray>— 重载配置");
            Msg.send(sender, "<white>/" + label + " selftest <gray>— 运行时自检");
        }
        Msg.send(sender, "<dark_gray>玩法：烧红 → 卡点落锤 → 序列叠钢 → 稳住刃压 → 选淬火介质");
    }

    /**
     * {@code /zhujian money coin balance [玩家] | coin give|take <玩家> <数量>}：内置剑币。
     */
    private void moneyCoin(CommandSender sender, String[] args) {
        String op = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "";
        if (op.isEmpty() || "balance".equals(op) || "bal".equals(op)) {
            if (args.length >= 4) {
                var target = Bukkit.getPlayerExact(args[3]);
                if (target == null) {
                    Msg.prefix(sender, "<red>玩家不在线：" + args[3]);
                    return;
                }
                Msg.prefix(sender, "<gray>" + target.getName() + " 的剑币：<white>"
                        + plugin.stats().coins(target.getUniqueId()) + "</white>");
                return;
            }
            if (sender instanceof Player p) {
                Msg.prefix(p, "<gray>你的剑币：<white>" + plugin.stats().coins(p.getUniqueId()) + "</white>");
            } else {
                Msg.prefix(sender, "<gray>用法：<white>/zhujian money coin balance <玩家></white>");
            }
            return;
        }
        if (!sender.hasPermission("forgemaster.admin")) {
            Msg.prefix(sender, "<red>只有管理员能发剑币 / 扣剑币。</red>");
            return;
        }
        if (args.length < 5 || (!op.equals("give") && !op.equals("take"))) {
            Msg.prefix(sender, "<gray>用法：<white>/zhujian money coin give|take <玩家> <数量>");
            return;
        }
        var target = Bukkit.getPlayerExact(args[3]);
        if (target == null) {
            Msg.prefix(sender, "<red>玩家不在线：" + args[3]);
            return;
        }
        long amount;
        try {
            amount = Math.abs(Long.parseLong(args[4].replace(",", "")));
        } catch (NumberFormatException ex) {
            Msg.prefix(sender, "<red>数量要写数字。</red>");
            return;
        }
        if (amount <= 0) {
            Msg.prefix(sender, "<red>数量得大于 0。</red>");
            return;
        }
        if (op.equals("give")) {
            long now = plugin.stats().addCoins(target.getUniqueId(), amount);
            Msg.prefix(sender, "<green>已给 <white>" + target.getName() + " " + amount
                    + "</white> 剑币（余额 " + now + "）。");
        } else {
            if (!plugin.stats().takeCoins(target.getUniqueId(), amount, com.forgemaster.econ.CoinReason.OTHER_OUT)) {
                Msg.prefix(sender, "<red>对方剑币不足 " + amount + "，没有扣。</red>");
                return;
            }
            Msg.prefix(sender, "<green>已扣 <white>" + target.getName() + " " + amount + "</white> 剑币。");
        }
    }

    private double parseDouble(String raw, double def) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    // ------------------------------------------------------------ Tab 补全
    //
    // 逐层补全：每个子命令按自己的语法给候选，而不是只补第一层。
    // 例如 /zhujian money coin give <玩家> <数量> 一路按 Tab 就能走完，
    // /zhujian tomb take <编号> 的编号、/zhujian restore <备份id> 的 id 也都能补。

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> out;
        try {
            out = tabComplete0(sender, args);
        } catch (Throwable t) {
            // 补全里抛异常会让客户端拿不到回包（Paper 对空结果直接不发响应），
            // 所以这里兜住并留一条日志，别让它表现成「按 Tab 没反应」
            plugin.getLogger().warning("[tab] 补全异常 args=" + String.join("|", args) + " : " + t);
            return List.of();
        }
        return out;
    }

    private List<String> tabComplete0(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return filter(topLevelSubs(sender), args[0]);
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> tabGive(sender, args);
            case "gem", "baoshi", "baoshitai" -> tabGem(args);
            case "money", "coins", "qian", "coin" -> tabMoney(args);
            case "tomb", "jiantomb", "jianzhong" -> tabTomb(args);
            case "tombrun", "mijing" ->
                    args.length == 2 ? filter(List.of("enter", "status", "quit"), args[1]) : List.of();
            case "strengthen", "qianghua", "duanzao", "duanzaodengji" -> tabStrengthen(args);
            case "evolve", "tuibian", "jinhua", "jieduan" ->
                    args.length == 2 ? filter(List.of("sim", "now"), args[1]) : List.of();
            case "contract", "longshui", "shui" ->
                    args.length == 2 ? filter(List.of("join", "quit", "deposit"), args[1]) : List.of();
            case "shangjin", "bounty", "xuanshang" ->
                    args.length == 2 ? filter(List.of("claim"), args[1]) : List.of();
            case "contest", "dasai" ->
                    args.length == 2 ? filter(List.of("start", "list"), args[1]) : List.of();
            case "season", "jijie" ->
                    args.length == 2 ? filter(List.of("end"), args[1]) : List.of();
            case "rank", "paihang" ->
                    args.length == 2
                            ? filter(List.of("coins", "reputation", "streak", "level", "text"), args[1])
                            : List.of();
            case "forge", "jixian" ->
                    args.length == 2 ? filter(List.of("risk", "cancel"), args[1]) : List.of();
            case "halloffame" ->
                    args.length == 2 ? filter(List.of("set", "clear"), args[1]) : List.of();
            case "hall" ->
                    args.length == 2 ? filter(List.of("set", "show", "del"), args[1]) : List.of();
            case "enchant", "fumo", "fumoshuxiang" ->
                    args.length == 2 ? filter(List.of("extract"), args[1]) : List.of();
            case "dissolve", "jiapo", "fenjie" ->
                    args.length == 2 ? filter(List.of("confirm"), args[1]) : List.of();
            case "imprint", "cifu" ->
                    args.length == 2 ? filter(List.of("merge"), args[1]) : List.of();
            case "restore", "huandang" ->
                    args.length == 2 ? filter(backupIds(), args[1]) : List.of();
            case "copy", "linmo" -> tabCopy(args);
            case "grade", "cailiao" -> tabGrade(args);
            case "duel", "bishi" -> tabDuel(args);
            case "talent", "tianfu" -> tabTalent(args);
            case "event", "shijian" -> tabEvent(args);
            case "auction", "paimai" -> tabAuction(args);
            case "order", "dingdan" ->
                    args.length == 2 ? filter(List.of("post", "cancel"), args[1]) : List.of();
            case "master", "shifu" -> tabMaster(args);
            case "guild", "hanghui" -> tabGuild(args);
            case "spirit", "jianling" -> tabSpirit(args);
            default -> List.of();
        };
    }

    /** 第一层：所有子命令（管理员的几个只对管理员出现） */
    private List<String> topLevelSubs(CommandSender sender) {
        List<String> subs = new ArrayList<>(Arrays.asList(
                "stats", "profile", "rank", "codex", "env", "story", "spectrum", "mingjian", "pu",
                "pass", "tongxingzheng",
                "halloffame", "hall", "salvage", "board",
                "duel", "repair", "manual", "record", "copy", "contest", "grade", "top", "shop", "help",
                "master", "accept", "guild", "hanghui", "spirit", "tomb", "tombrun", "mijing",
                "achievements", "auction", "order", "dingdan",
                "strengthen", "hammer",
                "enchant", "talent", "evolve", "contract", "shangjin", "gem", "money", "forge", "event",
                "dissolve", "imprint", "collection", "shoucang", "tujian", "title", "chenghao"));
        if (sender.hasPermission("forgemaster.admin")) {
            subs.addAll(List.of("give", "reload", "selftest", "season", "backup", "restore",
                    "config", "metrics", "givefull"));
        }
        return subs;
    }

    private List<String> tabGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("forgemaster.admin")) {
            return List.of();
        }
        if (args.length == 2) {
            return filter(players(), args[1]);
        }
        if (args.length == 3) {
            List<String> ids = new ArrayList<>();
            plugin.materials().all().forEach(m -> ids.add(m.id()));
            // 装备：armor-<剑胚>-<部位>，管理员发东西常要，直接补全出来
            for (var m : plugin.materials().all()) {
                for (var slot : com.forgemaster.material.ForgeMaterial.Slot.values()) {
                    if (com.forgemaster.material.ArmorCatalog.create(m, slot, 1.0) != null) {
                        ids.add("armor-" + m.id() + "-" + slot.name().toLowerCase(Locale.ROOT));
                    }
                }
            }
            return filter(ids, args[2]);
        }
        if (args.length == 4) {
            List<String> qs = new ArrayList<>();
            for (Quality q : Quality.values()) {
                qs.add(q.name());
            }
            return filter(qs, args[3]);
        }
        if (args.length == 5) {
            return filter(List.of("50", "70", "85", "90", "95", "100"), args[4]);
        }
        return List.of();
    }

    private List<String> tabGem(String[] args) {
        if (args.length == 2) {
            return filter(List.of("socket", "esocket", "smelt", "inlay", "extract", "merge",
                    "enchant", "give", "list"), args[1]);
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (!sub.equals("give") && !sub.equals("fa") && !sub.equals("gei")) {
            return List.of();
        }
        if (args.length == 3) {
            List<String> names = new ArrayList<>(List.of("all"));
            names.addAll(players());
            return filter(names, args[2]);
        }
        if (args.length == 4) {
            List<String> types = new ArrayList<>(List.of("random"));
            for (var t : com.forgemaster.gem.GemType.values()) {
                types.add(t.id());
            }
            return filter(types, args[3]);
        }
        if (args.length == 5) {
            List<String> grades = new ArrayList<>();
            for (var g : com.forgemaster.gem.GemType.Grade.values()) {
                grades.add(g.name());
            }
            return filter(grades, args[4]);
        }
        if (args.length == 6) {
            return filter(List.of("1", "4", "8", "16", "32", "64"), args[5]);
        }
        return List.of();
    }

    /** 钱：/zhujian money [balance|give|take|coin] …，coin 后面还有一层 balance|give|take */
    private List<String> tabMoney(String[] args) {
        if (args.length == 2) {
            return filter(List.of("balance", "give", "take", "coin"), args[1]);
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("coin") || sub.equals("coins") || sub.equals("jianbi")) {
            if (args.length == 3) {
                return filter(List.of("balance", "give", "take"), args[2]);
            }
            if (args.length == 4) {
                return filter(players(), args[3]);
            }
            if (args.length == 5) {
                return filter(List.of("100", "1000", "10000", "50000", "100000"), args[4]);
            }
            return List.of();
        }
        if (args.length == 3) {
            return filter(players(), args[2]);
        }
        if (args.length == 4) {
            return filter(List.of("100", "1000", "10000", "50000", "100000"), args[3]);
        }
        return List.of();
    }

    private List<String> tabTomb(String[] args) {
        if (args.length == 2) {
            return filter(List.of("list", "bury", "take", "challenge", "info", "remove"), args[1]);
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        boolean wantsId = sub.equals("take") || sub.equals("challenge")
                || sub.equals("info") || sub.equals("remove");
        if (args.length == 3 && wantsId) {
            return filter(tombIds(), args[2]);
        }
        return List.of();
    }

    private List<String> tabStrengthen(String[] args) {
        if (args.length == 2) {
            return filter(List.of("info", "sim", "plan", "set", "reforge", "strip", "graft", "jianyi"), args[1]);
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 3) {
            if (sub.equals("sim") || sub.equals("set")) {
                return filter(List.of("5", "10", "15", "20", "25", "30", "40", "50", "60",
                        "70", "80", "90", "99"), args[2]);
            }
            if (sub.equals("reforge") || sub.equals("strip")) {
                return filter(List.of("1", "2", "3", "4", "5", "6"), args[2]);
            }
        }
        return List.of();
    }

    private List<String> tabCopy(String[] args) {
        if (args.length != 2) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(List.of("stop"));
        for (var e : plugin.manuals().all()) {
            ids.add(e.id());
        }
        return filter(ids, args[1]);
    }

    /** 材料分级：/zhujian grade [up|give <材料> <档次> [数量]] */
    private List<String> tabGrade(String[] args) {
        if (args.length == 2) {
            return filter(List.of("up", "give"), args[1]);
        }
        if (!args[1].equalsIgnoreCase("give")) {
            return List.of();
        }
        if (args.length == 3) {
            List<String> mats = new ArrayList<>();
            for (var m : plugin.materials().all()) {
                mats.add(m.costItem().name().toLowerCase(Locale.ROOT));
            }
            return filter(mats, args[2]);
        }
        if (args.length == 4) {
            List<String> grades = new ArrayList<>();
            for (var g : com.forgemaster.material.MaterialGrade.values()) {
                grades.add(g.name());
            }
            return filter(grades, args[3]);
        }
        if (args.length == 5) {
            return filter(List.of("8", "16", "32", "64"), args[4]);
        }
        return List.of();
    }

    private List<String> tabDuel(String[] args) {
        if (args.length != 2) {
            return List.of();
        }
        List<String> names = new ArrayList<>(List.of("accept", "deny"));
        names.addAll(players());
        return filter(names, args[1]);
    }

    private List<String> tabTalent(String[] args) {
        if (args.length == 2) {
            return filter(List.of("add", "reset"), args[1]);
        }
        if (args.length == 3) {
            if (args[1].equalsIgnoreCase("add")) {
                List<String> ids = new ArrayList<>();
                for (var t : com.forgemaster.growth.Talents.Talent.values()) {
                    ids.add(t.id());
                }
                return filter(ids, args[2]);
            }
            if (args[1].equalsIgnoreCase("reset")) {
                return filter(List.of("confirm"), args[2]);
            }
        }
        return List.of();
    }

    /** 世界事件：/zhujian event [status|start <id>|stop] */
    private List<String> tabEvent(String[] args) {
        if (args.length == 2) {
            return filter(List.of("status", "start", "stop"), args[1]);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("start")) {
            List<String> ids = new ArrayList<>();
            if (plugin.events() != null) {
                ids.addAll(plugin.events().ids());
            }
            return filter(ids, args[2]);
        }
        return List.of();
    }

    /** 拍卖行：/zhujian auction [browse|sell <起> [一口] [小时]|bid <id> <价>|buy <id>|cancel <id>|claim] */
    private List<String> tabAuction(String[] args) {
        if (args.length == 2) {
            return filter(List.of("browse", "sell", "bid", "buy", "cancel", "claim"), args[1]);
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("sell")) {
            if (args.length == 3) {
                return filter(List.of("100", "500", "1000", "5000"), args[2]);
            }
            if (args.length == 4) {
                return filter(List.of("0", "1000", "5000", "10000"), args[3]);
            }
            if (args.length == 5) {
                return filter(List.of("1", "6", "12", "24", "48"), args[4]);
            }
            return List.of();
        }
        boolean wantsLot = sub.equals("bid") || sub.equals("buy") || sub.equals("cancel");
        if (wantsLot && args.length == 3) {
            return filter(auctionLotIds(), args[2]);
        }
        if (sub.equals("bid") && args.length == 4) {
            return filter(List.of("100", "500", "1000", "5000"), args[3]);
        }
        return List.of();
    }

    private List<String> tabMaster(String[] args) {
        if (args.length != 2) {
            return List.of();
        }
        List<String> names = new ArrayList<>(List.of("status", "leave", "expel",
                "graduate", "graduate-confirm", "graduates"));
        names.addAll(players());
        return filter(names, args[1]);
    }

    /** 行会：/zhujian guild [create|invite|accept|deny|leave|kick|info|deposit|list|upgrade] */
    private List<String> tabGuild(String[] args) {
        if (args.length == 2) {
            return filter(List.of("create", "invite", "accept", "deny", "leave", "kick",
                    "info", "deposit", "list", "upgrade"), args[1]);
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        boolean wantsPlayer = sub.equals("invite") || sub.equals("kick");
        if (wantsPlayer && args.length == 3) {
            return filter(players(), args[2]);
        }
        return List.of();
    }

    private List<String> tabSpirit(String[] args) {
        if (args.length != 2) {
            return List.of();
        }
        return filter(List.of("info", "feed", "name", "show", "hide"), args[1]);
    }

    // ------------------------------------------------------------ 补全素材

    private List<String> players() {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        return names;
    }

    private List<String> tombIds() {
        List<String> ids = new ArrayList<>();
        if (plugin.tombs() != null) {
            for (var t : plugin.tombs().all()) {
                ids.add(t.id);
            }
        }
        return ids;
    }

    private List<String> backupIds() {
        List<String> ids = new ArrayList<>();
        if (plugin.backups() != null) {
            ids.addAll(plugin.backups().list());
        }
        return ids;
    }

    private List<String> auctionLotIds() {
        List<String> ids = new ArrayList<>();
        if (plugin.auction() != null && plugin.auction().store() != null) {
            for (var l : plugin.auction().store().all()) {
                ids.add(l.id());
            }
        }
        return ids;
    }

    private List<String> filter(List<String> source, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : source) {
            if (s.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(s);
            }
        }
        return out;
    }
}
