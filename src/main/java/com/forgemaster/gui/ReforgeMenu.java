package com.forgemaster.gui;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.affix.Affix;
import com.forgemaster.affix.Reforge;
import com.forgemaster.econ.CoinReason;
import com.forgemaster.material.MaterialGrade;
import com.forgemaster.material.MaterialGrades;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 词条重铸界面。
 *
 * <p>主手拿着带词条的锻造品打开：一条词条一个按钮，图标用词条自己的代表物品，
 * Lore 直接由 {@link Reforge#preview} 的描述 + {@link Reforge#costOf} 的费用拼出来。
 * 点一下只是「变红待确认」，3 秒内再点一次才真的走 {@link Reforge#reforge}——
 * 重铸是烧钱操作，中间不给一次反悔的机会迟早要被误触骂。</p>
 *
 * <p>真正干活的全在 {@code affix/Reforge.java}（只读，不改），这里只做三件事：
 * 渲染、二次确认、扣费。扣费规则与 {@code /zhujian strengthen reforge} 一致：
 * 剑币走 {@code EconomyBridge}，材料按 {@code reforge.consume-materials} 决定
 * 由 {@link Reforge} 自己扣还是界面这边扣（不会两边都扣）。</p>
 *
 * <p>点击监听在本类内部自注册：{@code listener/ForgeListener} 不认这个界面，
 * 而那个文件不在本模块的改动范围内，所以这里第一次打开时往 Bukkit 注册一个
 * 自己的 {@link ClickRouter}。</p>
 */
public final class ReforgeMenu {

    /** 界面尺寸 */
    public static final int SIZE = 27;

    /** 顶部说明 / 主手概览 */
    public static final int INFO_SLOT = 4;

    /** 词条按钮槽位（品质最高 5 条，多留几格兜底） */
    public static final int[] AFFIX_SLOTS = {10, 11, 12, 13, 14, 15, 16};

    /** 返回锻造主界面 */
    public static final int BACK_SLOT = 18;

    /** 关闭（屏障图标） */
    public static final int CLOSE_SLOT = 26;

    /** 二次确认有效期默认值（毫秒）：60 tick = 3 秒 */
    public static final long DEFAULT_CONFIRM_MS = 3000L;

    private static final String TITLE =
            "<dark_gray>铸剑大师 <dark_gray>| <#ff9a3c>词条重铸 <dark_gray>· <red>重铸会烧钱";

    /** 自己注册的监听器只装一次 */
    private static volatile boolean listenerReady;

    /**
     * 卸载时清掉「已注册」标志（v2.23.1 修）。
     *
     * <h2>这是一个真实的、玩家能看见的 bug</h2>
     * 静态字段活得比插件实例长。{@code onDisable} 之后重新 enable（PlugMan / 服务器
     * 重载 / 热更新），{@code init()} → {@code ensureListener()} 看到
     * {@code listenerReady == true} 就直接 return —— <b>监听器永远不会为新的插件实例
     * 重新注册</b>，于是重铸界面打开后点任何一个按钮都毫无反应。
     *
     * <p>更糟的是它只在「重载后」出现：刚开服一切正常，服主重载一次才爆炸，
     * 于是会怀疑是自己改坏了配置。</p>
     *
     * <p>所以这里必须由主类在 {@code onDisable} 调一次，和其它八个 GuiRouter
     * 的 {@code reset()} 同一个位置。</p>
     */
    public static void reset() {
        listenerReady = false;
    }

    private ReforgeMenu() {
    }

    // ------------------------------------------------------------ 1. 打开

    /**
     * 打开重铸界面（命令入口用这个）。
     *
     * <p>主手不是锻造品 / 一条词条都没有 / 重铸被服主关掉 / 没有权限时，
     * 只发一条红字提示，不扣任何东西、也不开一个空界面。</p>
     */
    public static void open(Player player) {
        ForgingMasterPlugin plugin = plugin();
        if (plugin == null) {
            Msg.prefix(player, "<red>锻造插件还没就绪，先等等。");
            return;
        }
        open(plugin, player);
    }

    /**
     * 提前注册点击监听。
     *
     * <p>必须在插件启动时调用一次：主菜单里那个「词条重铸」按钮靠 {@link ClickRouter}
     * 才能点开，而 {@code open()} 里才注册监听的话就成了死锁——点不到按钮就永远
     * 进不了 {@code open()}，监听永远不会注册。幂等，重复调无害。</p>
     */
    public static void init(ForgingMasterPlugin plugin) {
        if (plugin != null) {
            ensureListener(plugin);
        }
    }

    /** 同上，插件实例已知时直接传进来（自检 / 命令里已经拿到 plugin 的场景） */
    public static void open(ForgingMasterPlugin plugin, Player player) {
        if (plugin == null || player == null) {
            return;
        }
        ensureListener(plugin);
        String blocked = blockedReason(plugin, player);
        if (blocked != null) {
            Msg.prefix(player, blocked);
            return;
        }
        player.openInventory(build(plugin, player));
    }

    /** 能不能打开；能打开返回 null，否则返回红字原因 */
    public static String blockedReason(ForgingMasterPlugin plugin, Player player) {
        if (!plugin.settings().flag("reforge.enabled", true)) {
            return "<red>词条重铸已关闭。";
        }
        String perm = plugin.settings().str("reforge.permission", "forgemaster.use");
        if (perm != null && !perm.isBlank() && !player.hasPermission(perm)) {
            return "<red>你没有重铸词条的权限。";
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!plugin.items().isForged(hand)) {
            return "<red>主手要拿一件锻造品，才能看重铸。";
        }
        if (Reforge.affixCount(hand) <= 0) {
            return "<red>这件锻造品身上一条词条都没有，没什么可重铸的。";
        }
        return null;
    }

    /**
     * 主菜单上的「词条重铸」按钮（由 {@link ForgeMenu#buildMainMenu} 画）。
     *
     * <p>顺带把点击监听装上：主菜单每次重绘都会走这里，所以「玩家一次都没进过
     * 重铸界面」的那次点击也一定有人接——否则第一次点按钮会石沉大海。</p>
     */
    public static ItemStack entryIcon() {
        ForgingMasterPlugin plugin = plugin();
        if (plugin != null) {
            ensureListener(plugin);
        }
        return ForgeMenu.icon(Material.SMITHING_TABLE, "<#ff9a3c><bold>词条重铸</bold>",
                List.of("<gray>只重抽<b>一条</b>词条，其它条目原样不动。",
                        "<gray>主手要拿一件带词条的<b>锻造品</b>。",
                        "<red>重铸会烧钱：剑币 + 对应锻造材料。",
                        "",
                        "<yellow>▶ 左键进入"));
    }

    /** 只造界面不打开（自检里可以直接拿它断言槽位） */
    public static Inventory build(ForgingMasterPlugin plugin, Player player) {
        ReforgeMenuHolder holder = new ReforgeMenuHolder();
        Inventory inv = Bukkit.createInventory(holder, SIZE, Msg.mm(TITLE));
        holder.inventory(inv);
        render(plugin, player, holder);
        return inv;
    }

    // ------------------------------------------------------------ 2. 渲染

    /** 就地重绘（词条变了 / 确认状态变了都走这里，不重开界面，玩家看不见闪屏） */
    public static void render(ForgingMasterPlugin plugin, Player player, ReforgeMenuHolder holder) {
        Inventory inv = holder.getInventory();
        if (inv == null) {
            return;
        }
        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, ForgeMenu.filler());
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        boolean forged = plugin.items().isForged(hand);
        List<String> ids = Reforge.affixIdsOf(hand);

        inv.setItem(INFO_SLOT, infoButton(plugin, player, hand, forged, ids));

        String sig = signature(hand, ids);
        for (int i = 0; i < ids.size() && i < AFFIX_SLOTS.length; i++) {
            boolean confirm = holder.awaiting(i, sig);
            inv.setItem(AFFIX_SLOTS[i], affixButton(plugin, player, hand, i, ids.get(i), confirm));
        }

        inv.setItem(BACK_SLOT, ForgeMenu.icon(Material.ARROW, "<gray>返回锻造主界面",
                List.of("<yellow>▶ 左键返回")));
        inv.setItem(CLOSE_SLOT, ForgeMenu.icon(Material.BARRIER, "<red>关闭",
                List.of("<gray>随时可以走，词条一条都不会变。", "<yellow>▶ 左键关闭")));
    }

    /** 顶部概览：讲清楚「只重抽一条 / 会花钱」，外加主手是谁、几条词条 */
    private static ItemStack infoButton(ForgingMasterPlugin plugin, Player player,
                                        ItemStack hand, boolean forged, List<String> ids) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>重铸只重抽<b>一条</b>词条，其它条目原样不动。");
        lore.add("<gray>价格随<b>材料档位 / 品质 / 序号</b>上涨，"
                + "<red>剑币和材料都会真扣</red>。");
        lore.add("");
        lore.add("<gray>主手: <white>" + itemName(hand));
        lore.add("<gray>词条: <white>" + (forged ? ids.size() : 0) + " <gray>条");
        if (forged && ids.size() > AFFIX_SLOTS.length) {
            lore.add("<red>只显示前 " + AFFIX_SLOTS.length + " 条，多出来的请先用命令处理。");
        }
        lore.add("<gray>当前余额: <#ffd166>" + money(plugin, coinsOf(plugin, player)));
        lore.add("");
        lore.add("<yellow>点条目 → 变红 → <red>3 秒内再点一次</red><yellow>才真扣钱");
        lore.add("<dark_gray>点别处 / 等 3 秒 = 自动取消");
        return ForgeMenu.icon(Material.SMITHING_TABLE, "<#ff9a3c><bold>词条重铸</bold>", lore);
    }

    /** 一条词条一个按钮 */
    private static ItemStack affixButton(ForgingMasterPlugin plugin, Player player, ItemStack item,
                                         int index, String affixId, boolean confirm) {
        Affix affix = plugin.affixes().get(affixId == null ? "" : affixId.trim());
        String name = affix == null ? String.valueOf(affixId) : affix.display();

        List<String> lore = new ArrayList<>();
        // 词条名 / 当前数值 / 重铸后可能落在的区间：全部来自 Reforge.preview
        List<String> preview = previewLore(item, index);
        lore.addAll(preview);
        if (affix == null && preview.stream().noneMatch(l -> l.startsWith("<red>"))) {
            lore.add("<red>这条词条在 config.yml 里已经找不到了。");
        }
        if (affix == null) {
            lore.add("<dark_gray>先让服主把这条补回 config.yml，否则重铸会把它弄丢。");
        }
        lore.add("");

        long cost = Reforge.costOf(item, index);
        long have = coinsOf(plugin, player);
        lore.add("<gray>重铸费: <#ffd166>" + money(plugin, cost)
                + (have >= cost ? "" : " <red>(不够，你只有 " + money(plugin, have) + ")"));
        Material costItem = Reforge.materialItemOf(item);
        if (costItem != null) {
            int need = Reforge.materialAmountOf(item, index);
            int stock = MaterialGrades.totalCount(player, costItem);
            lore.add("<gray>材料: <white>" + need + " 个 " + MaterialGrades.pretty(costItem)
                    + " <" + (stock >= need ? "#4ee36b" : "#ff5252") + ">(" + stock + "/" + need + ")");
        }
        lore.add("");
        if (confirm) {
            lore.add("<red><bold>▶ 再点一次：确认重铸");
            lore.add("<dark_gray>超时或点别处自动取消");
        } else {
            lore.add("<yellow>▶ 左键选中这一条");
            lore.add("<dark_gray>先变红，再点一次才扣钱");
        }

        Material icon = confirm ? Material.RED_STAINED_GLASS_PANE : iconOf(affix);
        String title = confirm
                ? "<red><bold>！确认重铸 <dark_gray>·</dark_gray> <white>" + name
                : "<gold>◆ <white>" + name;
        return ForgeMenu.icon(icon, title, lore);
    }

    /**
     * 把 {@link Reforge#preview} 的描述拆成 Lore 行。
     *
     * <p>只留「第几条 · 第几档」与「现在: 名字 +值 (本档可能 X~Y)」两行：
     * 候选池那行太长，费用那行在本类里另外用 {@link Reforge#costOf} 重算，
     * 免得同一个数字出现两次口径不一致。失败原因（{@code <red>}）照抄不丢。</p>
     */
    private static List<String> previewLore(ItemStack item, int index) {
        List<String> out = new ArrayList<>();
        String raw = Reforge.preview(item, index);
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String piece : raw.split("\n")) {
            String line = piece.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("<gray>候选池") || line.startsWith("<dark_gray>（已排除")
                    || line.startsWith("<gray>费用")) {
                continue;
            }
            out.add(line);
        }
        return out;
    }

    // ------------------------------------------------------------ 3. 点击

    /** 槽位 → 词条下标；不是词条格子返回 -1 */
    public static int indexOfSlot(int slot) {
        for (int i = 0; i < AFFIX_SLOTS.length; i++) {
            if (AFFIX_SLOTS[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    /** 词条下标 → 槽位（自检用；超出范围返回 -1） */
    public static int slotOf(int index) {
        if (index < 0 || index >= AFFIX_SLOTS.length) {
            return -1;
        }
        return AFFIX_SLOTS[index];
    }

    /**
     * 处理一次点击。
     *
     * <p>第一次点：进入红色待确认，起一个 3 秒的还原任务；第二次点同一条：
     * 先校验（钱 / 材料 / 词条还在不在），再扣费，最后才 {@link Reforge#reforge}。
     * 校验不过一律「不扣费」并给出红字原因。</p>
     */
    public static void handleClick(ForgingMasterPlugin plugin, Player player,
                                   ReforgeMenuHolder holder, int slot) {
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == BACK_SLOT) {
            player.closeInventory();
            plugin.forge().openMenu(player);
            return;
        }
        int index = indexOfSlot(slot);
        if (index < 0) {
            // 点空白 / 点装饰格：顺手把待确认状态清掉，跟 Lore 里写的一致
            if (holder.pendingIndex() >= 0) {
                holder.clearPending();
                render(plugin, player, holder);
            }
            return;
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!plugin.items().isForged(hand)) {
            holder.clearPending();
            Msg.prefix(player, "<red>主手已经不是锻造品了，重铸不了。");
            player.closeInventory();
            return;
        }
        List<String> ids = Reforge.affixIdsOf(hand);
        if (ids.isEmpty()) {
            holder.clearPending();
            Msg.prefix(player, "<red>这件物品身上没有词条了，重铸不了。");
            player.closeInventory();
            return;
        }
        if (index >= ids.size()) {
            holder.clearPending();
            Msg.prefix(player, "<red>这件物品只剩 " + ids.size() + " 条词条，第 "
                    + (index + 1) + " 条已经不存在了。");
            render(plugin, player, holder);
            return;
        }

        String sig = signature(hand, ids);
        if (!holder.awaiting(index, sig)) {
            int token = holder.await(index, System.currentTimeMillis() + confirmWindowMs(plugin), sig);
            render(plugin, player, holder);
            if (plugin.settings().sounds()) {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
            }
            scheduleRevert(plugin, player, holder, token, index, sig);
            return;
        }

        // 第二次点击：确认
        holder.clearPending();
        doReforge(plugin, player, hand, index);
        if (player.isOnline()) {
            player.updateInventory(); // 手上那件的 Lore 刚被重写过，推一次让客户端刷新
            render(plugin, player, holder);
        }
    }

    /** 主手物品指纹：材质 + 词条 id 串，用来把「等待确认」钉死在某一件东西上 */
    private static String signature(ItemStack item, List<String> ids) {
        if (item == null) {
            return "air";
        }
        return item.getType().name() + "|" + String.join(",", ids);
    }

    /** 3 秒后把红色状态还原（换条 / 确认 / 换主手 / 关界面都会让这次任务自己作废） */
    private static void scheduleRevert(ForgingMasterPlugin plugin, Player player,
                                       ReforgeMenuHolder holder, int token, int index, String sig) {
        long ticks = Math.max(1L, plugin.settings().longOf("reforge.confirm-ticks", 60L));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !holder.stillPending(token, index, sig)) {
                return;
            }
            holder.clearPending();
            if (player.getOpenInventory().getTopInventory().getHolder() == holder) {
                render(plugin, player, holder);
            }
        }, ticks);
    }

    /** 确认窗口毫秒数：跟 confirm-ticks 同源，配置改了提示文案也跟着对 */
    private static long confirmWindowMs(ForgingMasterPlugin plugin) {
        long ticks = Math.max(1L, plugin.settings().longOf("reforge.confirm-ticks", 60L));
        return ticks * 50L;
    }

    // ------------------------------------------------------------ 4. 真扣钱 + 重铸

    /**
     * 真正执行一次重铸：先校验、再扣费、最后 {@link Reforge#reforge}。
     *
     * <p>顺序是刻意的：先花钱再重铸，正常路径下钱肯定付得出来（刚校验过）；
     * 万一重铸这步还是失败（词条表被人热改了之类），把钱和材料原样退回去，
     * 绝不出现「扣了费没换成」的半吊子状态。</p>
     */
    private static void doReforge(ForgingMasterPlugin plugin, Player player, ItemStack item, int index) {
        if (!plugin.settings().flag("reforge.enabled", true)) {
            Msg.prefix(player, "<red>词条重铸已关闭。");
            return;
        }
        if (index < 0 || index >= Reforge.affixCount(item)) {
            Msg.prefix(player, "<red>这一条词条已经不存在了。");
            return;
        }

        // preview 会把「词条被热删除 / 候选池被占满」提前说出来，先探一遍，别白扣钱
        String preview = Reforge.preview(item, index);
        if (preview != null && preview.startsWith("<red>")) {
            Msg.prefix(player, preview);
            return;
        }

        long cost = Reforge.costOf(item, index);
        Material costItem = Reforge.materialItemOf(item);
        int need = costItem == null ? 0 : Reforge.materialAmountOf(item, index);
        boolean selfConsume = plugin.settings().flag("reforge.consume-materials", false);

        long coins = coinsOf(plugin, player);
        if (cost > 0 && coins < cost) {
            Msg.prefix(player, "<red>重铸费不够：要 <white>" + money(plugin, cost)
                    + "</white>，你只有 <white>" + money(plugin, coins) + "</white>。");
            return;
        }
        if (costItem != null && need > 0) {
            int stock = MaterialGrades.totalCount(player, costItem);
            if (stock < need) {
                Msg.prefix(player, "<red>材料不够：要 <white>" + need + "</white> 个 "
                        + MaterialGrades.pretty(costItem) + "，你只有 <white>" + stock + "</white> 个。");
                return;
            }
        }

        // 1) 扣剑币
        boolean paid = cost > 0 && pay(plugin, player, cost);
        if (cost > 0 && !paid) {
            Msg.prefix(player, "<red>扣费失败，这次重铸没做，钱也没动。");
            return;
        }
        // 2) 扣材料（consume-materials 打开时由 Reforge 自己扣，这里别再扣一次）
        Material consumed = null;
        MaterialGrade grade = null;
        if (!selfConsume && costItem != null && need > 0) {
            MaterialGrade used = MaterialGrades.consume(player, costItem, need);
            if (used == null) {
                refund(plugin, player, cost);
                Msg.prefix(player, "<red>材料在最后一刻不够了，重铸取消，剑币已退回。");
                return;
            }
            consumed = costItem;
            grade = used;
        }

        // 3) 重铸
        String result = Reforge.reforge(player, item, index, ThreadLocalRandom.current());
        if (result != null && result.startsWith("<red>")) {
            // 只有极少数竞态会走到这里：退钱、退材料，物品保持原样
            refund(plugin, player, cost);
            if (consumed != null && grade != null) {
                giveBack(player, consumed, need, grade);
            }
            Msg.prefix(player, result);
            return;
        }
        Msg.prefix(player, result);
    }

    // ------------------------------------------------------------ 5. 钱 / 材料

    /** 余额：重铸价目表按剑币定价，所以走 {@link Wallet} 的同一本账 */
    private static long coinsOf(ForgingMasterPlugin plugin, Player player) {
        return plugin.wallet().coins(player);
    }

    private static boolean pay(ForgingMasterPlugin plugin, Player player, long amount) {
        return plugin.wallet().take(player, amount, CoinReason.REFORGE);
    }

    private static void refund(ForgingMasterPlugin plugin, Player player, long amount) {
        plugin.wallet().give(player, amount, CoinReason.REFUND);
    }

    /** 退材料：按当时扣掉的档位原样还回去，背包满了就掉在脚下 */
    private static void giveBack(Player player, Material material, int amount, MaterialGrade grade) {
        if (amount <= 0) {
            return;
        }
        ItemStack back = new ItemStack(material, amount);
        if (grade != null) {
            back = MaterialGrades.stamp(back, grade);
        }
        var left = player.getInventory().addItem(back);
        for (ItemStack overflow : left.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }

    // ------------------------------------------------------------ 6. 图标 / 文本

    /**
     * 词条的代表物品：能用 {@link Affix} 的字段就用（属性词条看 attribute），
     * 拿不到（配置里已删掉的词条）就退回 {@link Material#NAME_TAG}。
     */
    private static Material iconOf(Affix affix) {
        if (affix == null) {
            return Material.NAME_TAG;
        }
        Material icon = switch (affix.kind()) {
            case CRIT_CHANCE -> Material.SPECTRAL_ARROW;
            case CRIT_DAMAGE -> Material.DIAMOND_SWORD;
            case LIFESTEAL -> Material.RED_DYE;
            case FIRE -> Material.BLAZE_POWDER;
            // v2.24 切片一：冰噬 / 处决的图标（iconOf 是对词条 Kind 的穷举 switch 表达式，
            // 新增 Kind 必须在这里补分支，否则编译不过）
            case CHILL -> Material.PACKED_ICE;
            case EXECUTE -> Material.WITHER_SKELETON_SKULL;
            case DAMAGE_REDUCTION -> Material.SHIELD;
            case ATTRIBUTE -> attributeIcon(affix.attribute());
        };
        return icon == null ? Material.NAME_TAG : icon;
    }

    /** 属性 → 图标（按注册键名匹配，1.21 的 attribute 已经不是枚举了，别拿 name() 硬啃） */
    private static Material attributeIcon(Attribute attribute) {
        if (attribute == null || attribute.getKey() == null) {
            return Material.NAME_TAG;
        }
        String key = attribute.getKey().getKey().toLowerCase(Locale.ROOT);
        if (key.contains("toughness")) {
            return Material.NETHERITE_CHESTPLATE;
        }
        if (key.contains("armor")) {
            return Material.IRON_CHESTPLATE;
        }
        if (key.contains("attack_speed")) {
            return Material.GOLDEN_SWORD;
        }
        if (key.contains("attack_damage")) {
            return Material.IRON_SWORD;
        }
        if (key.contains("knockback")) {
            return Material.SLIME_BALL;
        }
        if (key.contains("movement_speed") || key.contains("flying_speed")) {
            return Material.FEATHER;
        }
        if (key.contains("health")) {
            return Material.GOLDEN_APPLE;
        }
        if (key.contains("jump")) {
            return Material.RABBIT_FOOT;
        }
        if (key.contains("luck")) {
            return Material.RABBIT_FOOT;
        }
        return Material.NAME_TAG;
    }

    /** 主手物品名（玩家改过的名字走纯文本，别让 < > 把 MiniMessage 拆了） */
    private static String itemName(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "空手";
        }
        String pretty = MaterialGrades.pretty(item.getType());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            var display = meta.displayName();
            if (display != null) {
                String plain = Msg.plain(display).trim();
                if (!plain.isEmpty()) {
                    pretty = plain;
                }
            }
        }
        return safe(pretty) + " <dark_gray>×" + Math.max(1, item.getAmount());
    }

    private static String safe(String raw) {
        return raw == null ? "" : raw.replace("<", "‹").replace(">", "›");
    }

    /** 金额文案：接了 Vault / CMI 就用服务器自己的货币名 */
    private static String money(ForgingMasterPlugin plugin, long amount) {
        return plugin.wallet().format(amount);
    }

    // ------------------------------------------------------------ 7. 插件实例 / 监听器

    /** 按类型找一次插件实例（本包不许改主类，所以不走构造注入） */
    private static ForgingMasterPlugin plugin() {
        var pm = Bukkit.getPluginManager();
        if (pm == null) {
            return null;
        }
        for (Plugin p : pm.getPlugins()) {
            if (p instanceof ForgingMasterPlugin fm && fm.isEnabled()) {
                return fm;
            }
        }
        return null;
    }

    /** 本界面自己的点击监听：ForgeListener 不认这个 holder，只能自备一份 */
    private static synchronized void ensureListener(ForgingMasterPlugin plugin) {
        if (listenerReady) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(new ClickRouter(plugin), plugin);
        listenerReady = true;
    }

    /**
     * 点击路由。
     *
     * <p>两件事：</p>
     * <ol>
     *   <li>重铸界面里的所有点击 / 拖拽一律取消，图标拿不出去；</li>
     *   <li>主菜单（{@link MainMenuHolder}）里 {@link ForgeMenu#REFORGE_SLOT} 这一格
     *       打开重铸界面。优先级压到 LOWEST，比 {@code ForgeListener} 的 HIGH 先跑：
     *       那边只认自己那几个槽位，对这个新按钮是无操作，但会把事件 cancel 掉，
     *       所以这里抢在它前面处理，界面留到下一 tick 再开。</li>
     * </ol>
     */
    public static final class ClickRouter implements Listener {

        private final ForgingMasterPlugin plugin;

        ClickRouter(ForgingMasterPlugin plugin) {
            this.plugin = plugin;
        }

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
        public void onClick(InventoryClickEvent event) {
            Inventory top = event.getView().getTopInventory();
            InventoryHolder holder = top.getHolder();

            if (holder instanceof ReforgeMenuHolder reforgeHolder) {
                // 锁死默认行为：拿物品 / 拖拽 / Shift 快移全部无效
                event.setCancelled(true);
                if (event.getClick() == ClickType.DOUBLE_CLICK) {
                    return;
                }
                if (!(event.getWhoClicked() instanceof Player player)) {
                    return;
                }
                Inventory clicked = event.getClickedInventory();
                if (clicked == null || !clicked.equals(top)) {
                    return; // 点在玩家背包里：什么都不做
                }
                handleClick(plugin, player, reforgeHolder, event.getRawSlot());
                return;
            }

            if (holder instanceof MainMenuHolder && event.getRawSlot() == ForgeMenu.REFORGE_SLOT) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
                    Bukkit.getScheduler().runTask(plugin, () -> open(plugin, player));
                }
            }
        }

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
        public void onDrag(InventoryDragEvent event) {
            if (event.getView().getTopInventory().getHolder() instanceof ReforgeMenuHolder) {
                event.setCancelled(true);
            }
        }

        /** 玩家下线：把待确认状态清掉，免得 3 秒后的还原任务对着一个幽灵界面干活 */
        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            var top = event.getPlayer().getOpenInventory().getTopInventory().getHolder();
            if (top instanceof ReforgeMenuHolder reforgeHolder) {
                reforgeHolder.clearPending();
            }
        }
    }
}
