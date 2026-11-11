package com.forgemaster.contests;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.forge.ForgePhase;
import com.forgemaster.forge.ForgeSession;
import com.forgemaster.gui.ForgeMenu;
import com.forgemaster.quality.Quality;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 锻造大赛「实时观战」面板。
 *
 * <p>给没参赛的人看：每个参赛者一行（名字 / 当前阶段 / 分数 / 名次），
 * 已交剑的显示成品品质，顶部是剩余时间、参赛人数和当前领先者。</p>
 *
 * <p>刷新策略：一个面板一个 {@link BukkitTask}，默认每 20 tick（1 秒）拉一次
 * {@link ContestManager#contestants()} 快照。每格渲染成一个「签名」（材质 + 名字 + lore），
 * 签名跟上一帧一样就直接跳过 {@code setItem}，所以只有真正变了的格子会被重画，
 * 界面不会整体重建、不会闪。</p>
 *
 * <p>任务收尾：界面关掉（{@code InventoryCloseEvent}）、玩家退出、大赛结束、
 * 面板被换成别的界面、插件卸载（{@link #shutdown()}）这五条路都会 cancel 任务并从
 * {@link #OPEN} 里摘掉，不留悬挂的每秒任务。</p>
 */
public final class ContestWatchGui {

    /** 面板持有者：点击 / 关闭事件靠它认人，跟 ForgeHolder 一个路子 */
    public static final class Holder implements InventoryHolder {

        private final ForgingMasterPlugin plugin;
        private final UUID viewer;
        private Inventory inventory;

        Holder(ForgingMasterPlugin plugin, UUID viewer) {
            this.plugin = plugin;
            this.viewer = viewer;
        }

        public ForgingMasterPlugin plugin() {
            return plugin;
        }

        public UUID viewer() {
            return viewer;
        }

        void inventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    /** 一格要画什么：材质 + 标题 + lore */
    private record Cell(Material mat, String name, List<String> lore) {
    }

    /** 空槽的统一底纹（跟主界面同款） */
    private static final Cell FILLER =
            new Cell(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>·</dark_gray>", List.of());

    /** 打开着的面板：一个玩家一份 */
    private static final Map<UUID, Panel> OPEN = new LinkedHashMap<>();

    private ContestWatchGui() {
    }

    /** 一个玩家 + 一个界面 + 一个刷新任务 */
    private static final class Panel {

        final UUID viewerId;
        final Inventory inv;
        /** 槽位 → 上一帧签名，用来做「只重画变化的格子」 */
        final Map<Integer, String> rendered = new HashMap<>();
        BukkitTask task;

        Panel(UUID viewerId, Inventory inv) {
            this.viewerId = viewerId;
            this.inv = inv;
        }
    }

    // ------------------------------------------------------------ 对外 API

    /**
     * 打开观战面板。命令层（{@code /zhujian contest watch}）直接调这个。
     *
     * <p>没有进行中的大赛时不建界面，只发一条提示——空界面比没有界面更让人困惑。</p>
     */
    public static void open(ForgingMasterPlugin plugin, Player player) {
        if (plugin == null || player == null) {
            return;
        }
        String blocked = blockedReason(plugin, player);
        if (blocked != null) {
            Msg.prefix(player, "<red>" + blocked);
            return;
        }
        // 观战面板的点击 / 关闭 / 退出三个监听都在 ContestGuiRouter 里；直接走命令
        // 打开时（没先开过赛况面板）也必须把路由注册上，否则图标能被拖走、关界面也不停表
        ContestGuiRouter.ensure(plugin);
        // 重复打开：先把旧面板连任务一起收掉，再开一份新的，避免两个任务抢同一个背包
        stop(OPEN.get(player.getUniqueId()));

        Holder holder = new Holder(plugin, player.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, rows(plugin) * 9,
                Msg.mm("<dark_gray>锻造大赛 <dark_gray>| <#ffd166>实时观战"));
        holder.inventory(inv);
        Panel panel = new Panel(player.getUniqueId(), inv);
        OPEN.put(player.getUniqueId(), panel);

        player.openInventory(inv);
        render(plugin, panel);   // 先画一帧：开面板的瞬间就有内容，不用等第一次 tick
        startTicker(plugin, panel);
    }

    /** 这个玩家是不是正开着观战面板（界面还是我们这层、还是这一份） */
    public static boolean watching(Player player) {
        if (player == null) {
            return false;
        }
        Panel panel = OPEN.get(player.getUniqueId());
        if (panel == null) {
            return false;
        }
        return player.isOnline()
                && player.getOpenInventory().getTopInventory().getHolder() instanceof Holder holder
                && holder.viewer().equals(panel.viewerId)
                && holder.getInventory() == panel.inv;
    }

    /** 当前观战人数（顺手把已经下线的人从表里清掉） */
    public static int watcherCount() {
        int n = 0;
        for (Panel panel : new ArrayList<>(OPEN.values())) {
            Player viewer = Bukkit.getPlayer(panel.viewerId);
            if (viewer == null || !viewer.isOnline()) {
                stop(panel);
                continue;
            }
            n++;
        }
        return n;
    }

    /** 停掉所有刷新任务（插件卸载时调） */
    public static void shutdown() {
        for (Panel panel : new ArrayList<>(OPEN.values())) {
            stopTicker(panel);
        }
        OPEN.clear();
    }

    /**
     * 不能观战的原因；能看返回 {@code null}。
     *
     * <p>判定顺序：插件没初始化 → 大赛系统没起来 → 功能开关关着 → 玩家身份 → 有没有比赛在跑 →
     * 是不是正开着锻造界面。</p>
     */
    public static String blockedReason(ForgingMasterPlugin plugin, Player player) {
        if (plugin == null) {
            return "插件还没初始化。";
        }
        ContestManager manager = plugin.contests();
        if (manager == null) {
            return "大赛系统没有初始化。";
        }
        if (!plugin.settings().contestEnabled()) {
            return "大赛功能已关闭，观战面板也一起关了。";
        }
        if (!manager.running()) {
            return "当前没有进行中的大赛。";
        }
        if (player == null) {
            return "观战面板只能由玩家打开。";
        }
        // 锻造界面开着时打开观战，会把锻造界面挤掉 —— ForgeManager 认为「界面被关 = 这炉作废」，
        // 材料和锻造费都不退。所以这里先拦住，让他自己收尾。
        // 唯一例外是已经到 RESULT（成品界面）：那一层被关掉只会把成品塞回背包，不会亏东西。
        if (plugin.forge() != null && plugin.forge().isBusy(player)) {
            ForgeSession session = plugin.forge().session(player);
            if (session == null || session.phase() != ForgePhase.RESULT) {
                return "你正开着锻造界面，先收尾这一炉再看观战。";
            }
        }
        return null;
    }

    /** 阶段中文名：观战面板和自检都用这一份映射 */
    public static String phaseLabel(ForgePhase phase) {
        if (phase == null) {
            return "已离场";
        }
        return switch (phase) {
            case HEATING -> "加热";
            case HAMMERING -> "锻打";
            case FOLDING -> "叠钢";
            case GRINDING -> "开刃";
            case POLISHING -> "抛光";
            case INLAY -> "嵌银丝";
            case QUENCHING -> "淬火";
            case RESULT -> "已完成";
        };
    }

    /** 关闭按钮的槽位（最后一排正中） */
    public static int closeSlot(int size) {
        return Math.max(0, size - 5);
    }

    /** 面板行数：3~6，配置兜底 6 */
    public static int rows(ForgingMasterPlugin plugin) {
        int rows = plugin == null ? 6 : plugin.settings().intOf("contest.watch.rows", 6);
        return Math.max(3, Math.min(6, rows));
    }

    /**
     * 参赛者一格行数：首行给顶部信息、末行给按钮，中间全给参赛者。
     *
     * <p>自检可以直接断言：{@code 9 + capacity(54) - 1 == 44 < 54 - 9}，也就是参赛行
     * 永远不会踩到顶部信息和末行按钮。</p>
     */
    public static int capacity(int size) {
        return Math.max(9, (Math.max(3, size / 9) - 2) * 9);
    }

    // ------------------------------------------------------------ 刷新任务

    private static void startTicker(ForgingMasterPlugin plugin, Panel panel) {
        stopTicker(panel);
        int ticks = Math.max(1, plugin.settings().intOf("contest.watch.refresh-ticks", 20));
        panel.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                tick(plugin, panel);
            } catch (RuntimeException ex) {
                // 一帧画崩了就收摊，别让坏面板变成每秒刷一遍控制台报错；
                // 但要留一行日志，否则面板「莫名消失」无从排查
                plugin.getLogger().warning("观战面板渲染异常已收摊: " + ex);
                stop(panel);
            }
        }, ticks, ticks);
    }

    private static void tick(ForgingMasterPlugin plugin, Panel panel) {
        if (!plugin.isEnabled()) {
            stop(panel);
            return;
        }
        Player viewer = Bukkit.getPlayer(panel.viewerId);
        if (viewer == null || !viewer.isOnline()
                || !(viewer.getOpenInventory().getTopInventory().getHolder() instanceof Holder holder)
                || !holder.viewer().equals(panel.viewerId)
                || holder.getInventory() != panel.inv) {
            stop(panel);   // 人下线了 / 界面被换成别的了
            return;
        }
        ContestManager manager = plugin.contests();
        if (manager == null || !manager.running()) {
            // 大赛在观看过程中结束：定格成结束态，然后把任务停掉（面板留在屏幕上，玩家自己关）
            renderEnded(panel);
            stopTicker(panel);
            return;
        }
        render(plugin, panel);
    }

    private static void stopTicker(Panel panel) {
        if (panel != null && panel.task != null) {
            panel.task.cancel();
            panel.task = null;
        }
    }

    private static void stop(Panel panel) {
        if (panel == null) {
            return;
        }
        stopTicker(panel);
        OPEN.remove(panel.viewerId, panel);
    }

    /**
     * 界面关闭（ContestGuiRouter 转进来的）。
     *
     * <p>只认「关的正是登记着的那一份界面」：玩家关面板再立刻重开时，
     * 旧界面的 close 事件会晚一步到，不能让它把新面板的任务掐了。</p>
     */
    static void onClose(Holder holder) {
        if (holder == null) {
            return;
        }
        Panel panel = OPEN.get(holder.viewer());
        if (panel != null && holder.getInventory() == panel.inv) {
            stop(panel);
        }
    }

    /** 玩家退出 */
    static void onQuit(UUID id) {
        if (id == null) {
            return;
        }
        stop(OPEN.get(id));
    }

    // ------------------------------------------------------------ 渲染

    private static void render(ForgingMasterPlugin plugin, Panel panel) {
        ContestManager manager = plugin.contests();
        if (manager == null) {
            renderEnded(panel);
            stopTicker(panel);
            return;
        }

        int size = panel.inv.getSize();
        Map<Integer, Cell> cells = new HashMap<>();

        long left = manager.remainingMillis();
        long total = Math.max(1L, plugin.settings().contestDurationSeconds() * 1000L);
        var asking = manager.materialAsking();
        String code = manager.contestCode();

        // —— 首行：剩余时间 / 题目 / 领先者 / 人数 / 说明
        cells.put(0, new Cell(Material.CLOCK, "<#ffd166>剩余时间", List.of(
                "<gray>本场还剩 <white>" + clock(left),
                Msg.bar(left / (double) total, 20, left <= 30_000L ? "#ff5252" : "#ffd166", "#3a3a3a"),
                "<dark_gray>面板每秒自动刷新")));

        cells.put(2, new Cell(Material.ANVIL, "<#ffd166>本场题目", List.of(
                "<gray>剑胚: " + (asking == null ? "<gray>—" : asking.display()),
                "<gray>种子: <white>" + (code == null ? "—" : code),
                "<gray>限时: <white>" + plugin.settings().contestDurationSeconds() + " 秒")));

        String leader = manager.leaderName();
        if (leader == null) {
            cells.put(4, new Cell(Material.GOLDEN_HELMET, "<#ffd166>当前领先", List.of(
                    "<gray>还没人交剑",
                    "<dark_gray>第一把剑出炉就会挂在这")));
        } else {
            cells.put(4, new Cell(Material.GOLDEN_HELMET, "<#ffd166>当前领先", List.of(
                    "<white>" + leader + "</white>",
                    "<gray>分数: <white>" + Msg.fmt(manager.leaderScore(), 1),
                    "<gray>已交剑: <white>" + manager.submittedCount()
                            + "</white> / " + manager.participantCount() + " 人")));
        }

        int totalPeople = manager.participantCount();
        int submitted = manager.submittedCount();
        cells.put(6, new Cell(Material.PLAYER_HEAD, "<#ffd166>参赛人数", List.of(
                "<gray>交剑: <white>" + submitted,
                "<gray>在打: <white>" + manager.activeCount(),
                "<gray>合计: <white>" + totalPeople)));

        int ticks = Math.max(1, plugin.settings().intOf("contest.watch.refresh-ticks", 20));
        cells.put(8, new Cell(Material.COMPARATOR, "<#9fe8ff>观战面板", List.of(
                "<gray>阶段: 加热 → 锻打 → 叠钢 → 开刃 → 淬火",
                "<gray>刷新: <white>" + Msg.fmt(ticks / 20.0, 1) + " 秒",
                "<dark_gray>只重画变化的格子，不重建整界面")));

        // —— 中间：一人一行
        List<ContestManager.Contestant> list = manager.contestants();
        if (!plugin.settings().flag("contest.watch.show-left", true)) {
            List<ContestManager.Contestant> kept = new ArrayList<>();
            for (ContestManager.Contestant c : list) {
                if (c.submitted() || c.forging()) {
                    kept.add(c);
                }
            }
            list = kept;
        }
        int capacity = capacity(size);
        int shown = Math.min(capacity, list.size());
        for (int i = 0; i < shown; i++) {
            cells.put(9 + i, cellOf(list.get(i), panel.viewerId));
        }

        // —— 末行：我的身份 / 关闭 / 图例（或未显示人数）
        int base = size - 9;
        int myRank = 0;
        for (ContestManager.Contestant c : list) {
            if (panel.viewerId.equals(c.id()) && c.submitted()) {
                myRank = c.rank();
                break;
            }
        }
        if (manager.isEntrant(panel.viewerId)) {
            List<String> lore = new ArrayList<>();
            lore.add("<gray>你也在本场参赛名单里");
            if (myRank > 0) {
                lore.add("<gray>当前名次: <#ffd166>第 " + myRank + " 名</#ffd166>");
                lore.add("<dark_gray>你自己那一行的末尾标着「这是你」");
            } else {
                lore.add("<gray>还没交剑，交剑前分数线是空的");
            }
            cells.put(base, new Cell(Material.GOLDEN_SWORD, "<#4ee36b>你正在比赛", lore));
        } else {
            cells.put(base, new Cell(Material.ENDER_EYE, "<gray>你是旁观者", List.of(
                    "<dark_gray>看别人的火候，不耽误自己的活儿")));
        }

        cells.put(closeSlot(size), new Cell(Material.BARRIER, "<red>关闭面板", List.of(
                "<gray>左键关闭观战")));

        if (list.size() > shown) {
            cells.put(size - 1, new Cell(Material.PAPER, "<gray>还有 " + (list.size() - shown) + " 人没显示",
                    List.of("<dark_gray>行数上限 " + shown + " 行",
                            "<dark_gray>调 contest.watch.rows 可以多显示几行")));
        } else {
            cells.put(size - 1, new Cell(Material.BOOK, "<#9fe8ff>图例", List.of(
                    "<gray>① ② ③ 是前三名",
                    "<gray>图标看阶段: 火/砧/纸/砂轮/水桶",
                    "<dark_gray>灰字 = 还没交剑")));
        }

        for (int slot = 0; slot < size; slot++) {
            apply(panel, slot, cells.getOrDefault(slot, FILLER));
        }
    }

    /** 大赛结束 / 系统没起来时的定格画面 */
    private static void renderEnded(Panel panel) {
        int size = panel.inv.getSize();
        Map<Integer, Cell> cells = new HashMap<>();
        cells.put(4, new Cell(Material.BELL, "<#ffd166>本场大赛已结束", List.of(
                "<gray>名次和奖励已经结算过了",
                "<dark_gray>下一场开始后重新打开就能看")));
        cells.put(closeSlot(size), new Cell(Material.BARRIER, "<red>关闭面板", List.of(
                "<gray>左键关闭观战")));
        for (int slot = 0; slot < size; slot++) {
            apply(panel, slot, cells.getOrDefault(slot, FILLER));
        }
    }

    /** 一行参赛者 */
    private static Cell cellOf(ContestManager.Contestant c, UUID viewer) {
        List<String> lore = new ArrayList<>();
        Material mat;
        String title;

        if (c.submitted()) {
            Quality quality = c.quality();
            mat = quality == null ? Material.NETHERITE_SWORD : quality.icon();
            title = badge(c.rank()) + " <white>" + c.name() + "</white>";
            lore.add("<gray>阶段: <#4ee36b>已完成");
            lore.add("<gray>成品品质: " + (quality == null ? "<gray>—" : quality.colored()));
            lore.add("<gray>分数: <white>" + Msg.fmt(c.score(), 1));
            lore.add("<gray>名次: <#ffd166>第 " + c.rank() + " 名</#ffd166>");
        } else if (c.forging()) {
            mat = phaseIcon(c.phase());
            title = "<dark_gray>—</dark_gray> <white>" + c.name() + "</white>";
            lore.add("<gray>阶段: <white>" + phaseLabel(c.phase()));
            lore.add("<gray>分数: <dark_gray>还没交剑");
            lore.add("<gray>名次: <dark_gray>—");
        } else {
            mat = Material.COAL;
            title = "<dark_gray>—</dark_gray> <gray>" + c.name() + "</gray>";
            lore.add("<gray>阶段: <dark_gray>已离开锻造台");
            lore.add("<gray>分数: <dark_gray>还没交剑");
            lore.add("<gray>名次: <dark_gray>—");
        }

        if (viewer != null && viewer.equals(c.id())) {
            lore.add("");
            lore.add("<yellow>◀ 这是你");
        }
        return new Cell(mat, title, lore);
    }

    private static Material phaseIcon(ForgePhase phase) {
        if (phase == null) {
            return Material.COAL;
        }
        return switch (phase) {
            case HEATING -> Material.BLAZE_POWDER;
            case HAMMERING -> Material.IRON_INGOT;
            case FOLDING -> Material.PAPER;
            case GRINDING -> Material.GRINDSTONE;
            case POLISHING -> Material.PAPER;
            case INLAY -> Material.STRING;
            case QUENCHING -> Material.WATER_BUCKET;
            case RESULT -> Material.CHEST;
        };
    }

    private static String badge(int rank) {
        return switch (rank) {
            case 1 -> "<#ffd166>①</#ffd166>";
            case 2 -> "<#dddddd>②</#dddddd>";
            case 3 -> "<#c98a4b>③</#c98a4b>";
            default -> "<dark_gray>" + rank + ".</dark_gray>";
        };
    }

    /**
     * 写入一格，但只在内容变了的时候写。
     *
     * <p>签名 = 材质 + 名字 + lore 拼出来的串；跟上一帧一模一样就直接返回，
     * 连 ItemStack 都不新建——既省对象，也不会让客户端整格重绘。</p>
     */
    private static void apply(Panel panel, int slot, Cell cell) {
        String signature = cell.mat().name() + '\u0000' + cell.name() + '\u0000'
                + String.join("\u0001", cell.lore());
        if (signature.equals(panel.rendered.get(slot))) {
            return;
        }
        panel.rendered.put(slot, signature);
        panel.inv.setItem(slot, ForgeMenu.icon(cell.mat(), cell.name(), cell.lore()));
    }

    private static String clock(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        return String.format("%d:%02d", seconds / 60L, seconds % 60L);
    }
}
