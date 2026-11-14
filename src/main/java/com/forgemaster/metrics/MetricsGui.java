package com.forgemaster.metrics;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.gui.ForgeMenu;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 数据看板三页 GUI（v2.23 切片 3）。
 *
 * <p>三页分工（与切片 1/2 的命令输出同源，不另造口径）：</p>
 * <ul>
 *   <li><b>第 1 页 · 实时体检</b>：{@link MetricsReporter#text()} 的原文——剑币从哪来、到哪去。</li>
 *   <li><b>第 2 页 · 趋势</b>：近 N 天逐日产出/消耗/净额 + {@link MetricsService#trendText()}。</li>
 *   <li><b>第 3 页 · 分布</b>：基尼系数、采样情况、总量对比——判断「是不是少数人占了大头」。</li>
 * </ul>
 *
 * <h2>为什么把「文案行」直接做成物品名</h2>
 * 看板的内容本质是一堆带颜色的文本，硬拆成图标反而更难读。所以每页把行铺成
 * 纸片物品（名字=该行、Lore=同文，供悬浮细读），最多 45 行；超出部分截断并提示。
 * 这样 {@code /zhujian admin metrics} 与 GUI 永远说同一套话，不会出现
 * 「命令说一套、界面说另一套」的双口径问题。
 *
 * <p>静态快照界面（打开与翻页时各画一次），不挂定时任务。点击路由在
 * {@link MetricsGuiRouter}。</p>
 *
 * <p>槽位（54 格）：</p>
 * <pre>
 *   0 .. 44   内容行（最多 45 行）
 *   45 上一页   49 页码   53 下一页
 *   48 刷新     50 关闭
 * </pre>
 */
public final class MetricsGui {

    public static final int SIZE = 54;

    /** 每页内容行数上限 */
    public static final int LINES_PER_PAGE = 45;
    /** 内容区最后一格（含） */
    public static final int LAST_CONTENT_SLOT = LINES_PER_PAGE - 1;
    /** 上一页 */
    public static final int PREV_SLOT = 45;
    /** 刷新 */
    public static final int REFRESH_SLOT = 48;
    /** 页码指示 */
    public static final int PAGE_SLOT = 49;
    /** 关闭 */
    public static final int CLOSE_SLOT = 50;
    /** 下一页 */
    public static final int NEXT_SLOT = 53;

    /** 总页数（切片 3 固定三页） */
    public static final int PAGES = 3;

    public static final class Holder implements InventoryHolder {

        private final ForgingMasterPlugin plugin;
        private final UUID viewer;
        private final int page;
        private Inventory inventory;

        Holder(ForgingMasterPlugin plugin, UUID viewer, int page) {
            this.plugin = plugin;
            this.viewer = viewer;
            this.page = page;
        }

        public ForgingMasterPlugin plugin() {
            return plugin;
        }

        public UUID viewer() {
            return viewer;
        }

        public int page() {
            return page;
        }

        void inventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private MetricsGui() {
    }

    /** 打开第 1 页 */
    public static void open(ForgingMasterPlugin plugin, Player player) {
        open(plugin, player, 0);
    }

    /**
     * 打开指定页（0 基）。
     *
     * @param page 越界会被钳到 [0, PAGES-1]，翻页时不必自己判断
     */
    public static void open(ForgingMasterPlugin plugin, Player player, int page) {
        if (plugin == null || player == null) {
            return;
        }
        MetricsService svc = plugin.metrics();
        if (svc == null) {
            Msg.prefix(player, "<red>数据看板服务没有启动，请检查启动日志。</red>");
            return;
        }
        if (!svc.enabled()) {
            Msg.prefix(player, "<yellow>数据看板已关闭。</yellow> <dark_gray>（config.yml → metrics.enabled）");
            return;
        }
        int p = Math.max(0, Math.min(PAGES - 1, page));
        MetricsGuiRouter.ensure(plugin);

        Holder holder = new Holder(plugin, player.getUniqueId(), p);
        Inventory inv = Bukkit.createInventory(holder, SIZE,
                Msg.mm("<dark_gray>数据看板 <dark_gray>| " + titleOf(p)));
        holder.inventory(inv);
        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, ForgeMenu.filler());
        }

        List<String> lines = linesOf(plugin, svc, p);
        int shown = Math.min(lines.size(), LINES_PER_PAGE);
        for (int i = 0; i < shown; i++) {
            inv.setItem(i, lineItem(lines.get(i)));
        }
        if (lines.size() > LINES_PER_PAGE) {
            inv.setItem(LAST_CONTENT_SLOT, lineItem("<gray>……还有 "
                    + (lines.size() - LINES_PER_PAGE + 1)
                    + " 行没显示（用 /zhujian admin metrics 看全文）</gray>"));
        }

        inv.setItem(PREV_SLOT, button(Material.ARROW, "<white>◀ 上一页</white>",
                "<gray>第 " + (p + 1) + "/" + PAGES + " 页"));
        inv.setItem(PAGE_SLOT, button(Material.PAPER, "<#ffd166>第 " + (p + 1) + " / " + PAGES + " 页</#ffd166>",
                "<gray>" + titleOf(p)));
        inv.setItem(NEXT_SLOT, button(Material.ARROW, "<white>下一页 ▶</white>",
                "<gray>第 " + (p + 1) + "/" + PAGES + " 页"));
        inv.setItem(REFRESH_SLOT, button(Material.CLOCK, "<#8ce99a>刷新</#8ce99a>",
                "<gray>立即采样一次并重画本页"));
        inv.setItem(CLOSE_SLOT, button(Material.BARRIER, "<red>关闭</red>", null));
        player.openInventory(inv);
    }

    private static String titleOf(int page) {
        return switch (page) {
            case 0 -> "<#8ce99a>实时体检</#8ce99a>";
            case 1 -> "<#74c0fc>近况趋势</#74c0fc>";
            default -> "<#ffa8a8>分配分布</#ffa8a8>";
        };
    }

    /**
     * 某一页的所有行。
     *
     * <p>三页全部复用既有输出（{@link MetricsReporter} 与
     * {@link MetricsService#trendText()}），GUI 不产生任何新口径。</p>
     */
    static List<String> linesOf(ForgingMasterPlugin plugin, MetricsService svc, int page) {
        List<String> out = new ArrayList<>();
        switch (page) {
            case 0 -> {
                try {
                    String text = new MetricsReporter(plugin).text();
                    for (String line : text.split("<newline>")) {
                        if (!line.isBlank()) {
                            out.add(line);
                        }
                    }
                } catch (RuntimeException ex) {
                    out.add("<red>报表生成失败，详情见控制台。</red>");
                }
            }
            case 1 -> {
                int days = Math.max(1, Math.min(14, svc.historyDays()));
                out.add("<white>近 <#ffd166>" + days + "</#ffd166> 天逐日</white>");
                List<MetricsService.DayTotal> totals = svc.dayTotals(days);
                if (totals.isEmpty()) {
                    out.add("<gray>还没有采样数据（默认每 " + svc.intervalMinutes()
                            + " 分钟一次，点「刷新」可立刻采一次）</gray>");
                } else {
                    for (var d : totals) {
                        out.add("<gray>" + d.day() + "</gray> <white>产出</white> <#8ce99a>+"
                                + d.produced() + "</#8ce99a> <white>消耗</white> <#ffa8a8>-"
                                + d.consumed() + "</#ffa8a8> <white>净</white> "
                                + (d.net() >= 0 ? "<#8ce99a>+" : "<#ffa8a8>") + d.net() + "</>");
                    }
                }
                for (String line : svc.trendText().split("<newline>")) {
                    if (!line.isBlank()) {
                        out.add(line);
                    }
                }
            }
            default -> {
                out.add("<white>分配与健康度</white>");
                double gini = svc.lastData() == null ? 0.0 : svc.lastData().gini();
                double warn = svc.giniWarn();
                String color = (warn > 0 && gini >= warn) ? "#ffa8a8" : "#8ce99a";
                out.add("<gray>基尼系数</gray> <" + color + ">" + fmt(gini) + "</" + color + ">"
                        + (warn > 0 ? " <dark_gray>（警戒 " + fmt(warn) + "）</dark_gray>" : ""));
                out.add("<gray>采样次数</gray> <white>" + svc.sampleCount() + "</white>"
                        + " <gray>· 间隔</gray> <white>" + svc.intervalMinutes() + " 分钟</white>"
                        + " <gray>· 保留</gray> <white>" + svc.historyDays() + " 天</white>");
                out.add("<gray>近 7 天产出</gray> <#8ce99a>" + svc.totalProduced(7) + "</#8ce99a>"
                        + " <gray>消耗</gray> <#ffa8a8>" + svc.totalConsumed(7) + "</#ffa8a8>");
                out.add("<gray>近 30 天产出</gray> <#8ce99a>" + svc.totalProduced(30) + "</#8ce99a>"
                        + " <gray>消耗</gray> <#ffa8a8>" + svc.totalConsumed(30) + "</#ffa8a8>");
                out.add("<dark_gray>基尼越高说明剑币越集中在少数人手里；超过警戒值建议查一下"
                        + "产出理由里的前几项。</dark_gray>");
            }
        }
        return out;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    /** 一行文本 → 一个纸片；名字截断但 Lore 保留全文，悬浮能看全 */
    private static ItemStack lineItem(String line) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        String plain = stripTags(line);
        String name = plain.length() > 42 ? plain.substring(0, 41) + "…" : plain;
        meta.displayName(Msg.mm(name.isBlank() ? "<gray> </gray>" : "<white>" + escape(name) + "</white>"));
        if (plain.length() > 42) {
            meta.lore(List.of(Msg.mm("<gray>" + escape(plain) + "</gray>")));
        }
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack button(Material mat, String name, String lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Msg.mm(name));
            if (lore != null) {
                meta.lore(List.of(Msg.mm(lore)));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 去掉 MiniMessage 标签，得到纯文本。
     *
     * <p>物品名不能直接塞 MM 串（会被当字面量显示成 {@code <white>}），所以要剥标签；
     * {@code <newline>} 这类自定义标记也要剥。</p>
     */
    static String stripTags(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("<[^<>]{1,40}>", "").replace("</>", "");
    }

    /** 反向转义：纯文本里可能有 {@code <} {@code >}，直接塞进 MM 会被当标签吞掉 */
    private static String escape(String raw) {
        return raw.replace("<", "＜").replace(">", "＞");
    }
}
