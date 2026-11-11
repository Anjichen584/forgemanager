package com.forgemaster.codex;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.affix.Affix;
import com.forgemaster.gui.ForgeMenu;
import com.forgemaster.quality.Quality;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 收藏图鉴面板：六个分类页签（品质 / 剑型 / 评分 / 圣物 / 词条 / 剑灵命名）。
 *
 * <p>已收集的格子显示彩色图标 + 达成日期，未收集显示灰色剪影 + 线索文案。
 * 槽位（54 格）：0-5 页签、8 关闭、9-44 内容区（支持翻页）、45 上一页、
 * 49 分类奖励领取、53 下一页。打开时画一次，无刷新任务。</p>
 */
public final class CodexGui {

    public static final int SIZE = 54;

    /** 六个页签槽位（0-5），顺序与 CodexService.CATEGORIES 一致 */
    public static final int TAB_QUALITY = 0;
    public static final int TAB_FORM = 1;
    public static final int TAB_SCORE = 2;
    public static final int TAB_RELIC = 3;
    public static final int TAB_AFFIX = 4;
    public static final int TAB_SPIRIT = 5;
    public static final int CLOSE_SLOT = 8;

    public static final int CONTENT_FROM = 9;
    public static final int CONTENT_TO = 44; // 含
    public static final int PREV_SLOT = 45;
    public static final int CLAIM_SLOT = 49;
    public static final int NEXT_SLOT = 53;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 面板持有者（记录当前页签与页码） */
    public static final class Holder implements InventoryHolder {

        private final ForgingMasterPlugin plugin;
        private final UUID viewer;
        private String tab;
        private int page;
        private Inventory inventory;

        Holder(ForgingMasterPlugin plugin, UUID viewer, String tab, int page) {
            this.plugin = plugin;
            this.viewer = viewer;
            this.tab = tab;
            this.page = page;
        }

        public ForgingMasterPlugin plugin() {
            return plugin;
        }

        public UUID viewer() {
            return viewer;
        }

        public String tab() {
            return tab;
        }

        public void tab(String tab) {
            this.tab = tab;
        }

        public int page() {
            return page;
        }

        public void page(int page) {
            this.page = page;
        }

        void inventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private CodexGui() {
    }

    /** 默认页签打开 */
    public static void open(ForgingMasterPlugin plugin, Player player) {
        open(plugin, player, CodexService.CAT_QUALITY, 0);
    }

    /** 指定页签与页码打开 */
    public static void open(ForgingMasterPlugin plugin, Player player, String tab, int page) {
        if (plugin == null || player == null) {
            return;
        }
        CodexService service = plugin.codex();
        if (service == null || !service.enabled()) {
            Msg.prefix(player, "<red>收藏图鉴没有开启。");
            return;
        }
        if (!CodexService.CATEGORIES.contains(tab)) {
            tab = CodexService.CAT_QUALITY;
        }

        UUID id = player.getUniqueId();
        Holder holder = new Holder(plugin, id, tab, Math.max(0, page));
        Inventory inv = Bukkit.createInventory(holder, SIZE,
                Msg.mm("<dark_gray>收藏图鉴 <dark_gray>| <#ffd166>" + tabTitle(tab)));
        holder.inventory(inv);
        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, ForgeMenu.filler());
        }

        // 页签行
        for (int i = 0; i < CodexService.CATEGORIES.size(); i++) {
            String cat = CodexService.CATEGORIES.get(i);
            boolean current = cat.equals(tab);
            int got = service.collectedOf(id, cat);
            int total = service.totalOf(cat);
            inv.setItem(i, ForgeMenu.icon(tabIcon(cat), tabTitle(cat),
                    List.of("<gray>收集: <white>" + got + "<dark_gray>/<white>" + total,
                            "",
                            current ? "<yellow>▶ 当前页签"
                                    : "<dark_gray>▶ 左键切换")));
        }

        inv.setItem(CLOSE_SLOT, ForgeMenu.icon(Material.BARRIER, "<red>关闭",
                List.of("<gray>左键关闭")));

        // 内容区
        List<ItemStack> entries = entries(plugin, service, id, tab);
        int pages = Math.max(1, (entries.size() + contentSize() - 1) / contentSize());
        if (holder.page() >= pages) {
            holder.page(pages - 1);
        }
        int from = holder.page() * contentSize();
        int slot = CONTENT_FROM;
        for (int i = from; i < entries.size() && slot <= CONTENT_TO; i++, slot++) {
            inv.setItem(slot, entries.get(i));
        }

        // 翻页
        if (holder.page() > 0) {
            inv.setItem(PREV_SLOT, ForgeMenu.icon(Material.ARROW, "<#7fd3ff>上一页",
                    List.of("<gray>第 " + holder.page() + " / " + pages + " 页")));
        }
        if (holder.page() < pages - 1) {
            inv.setItem(NEXT_SLOT, ForgeMenu.icon(Material.ARROW, "<#7fd3ff>下一页",
                    List.of("<gray>第 " + (holder.page() + 2) + " / " + pages + " 页")));
        }

        // 分类奖励
        inv.setItem(CLAIM_SLOT, claimIcon(plugin, service, id, tab));

        player.openInventory(inv);
    }

    /** 分类奖励领取按钮 */
    public static ItemStack claimIcon(ForgingMasterPlugin plugin, CodexService service,
                                      UUID id, String tab) {
        int got = service.collectedOf(id, tab);
        int total = service.totalOf(tab);
        boolean complete = got >= total;
        boolean claimed = service.claimed(id, tab);
        String status;
        if (claimed) {
            status = "<#4ee36b>✔ 已领取";
        } else if (complete) {
            status = "<yellow>▶ 左键领取 <white>" + service.rewardOf(tab) + "</white> 剑币";
        } else {
            status = "<dark_gray>集满后可领 <white>" + service.rewardOf(tab) + "</white> 剑币";
        }
        return ForgeMenu.icon(complete ? Material.GOLDEN_APPLE : Material.BARRIER,
                "<#ffd166><bold>分类奖励</bold>", List.of(
                        "<gray>进度: " + Msg.bar(total == 0 ? 1 : (double) got / total, 20,
                                "#ffd166", "#3a3a3a"),
                        status,
                        service.allComplete(id)
                                ? "<gradient:#ffd166:#ff7a18><bold>全图鉴达成：「" + service.allTitle() + "」</bold></gradient>"
                                : "<dark_gray>全图鉴达成另有 " + service.allReward() + " 剑币"));
    }

    /** 页签 → 展示名 */
    public static String tabTitle(String cat) {
        return switch (cat) {
            case CodexService.CAT_QUALITY -> "品质收藏";
            case CodexService.CAT_FORM -> "剑型收藏";
            case CodexService.CAT_SCORE -> "评分里程碑";
            case CodexService.CAT_RELIC -> "圣物收藏";
            case CodexService.CAT_AFFIX -> "词条种类";
            case CodexService.CAT_SPIRIT -> "剑灵命名";
            default -> cat;
        };
    }

    /** 页签 → 图标 */
    public static Material tabIcon(String cat) {
        return switch (cat) {
            case CodexService.CAT_QUALITY -> Material.EMERALD;
            case CodexService.CAT_FORM -> Material.IRON_SWORD;
            case CodexService.CAT_SCORE -> Material.GOLD_INGOT;
            case CodexService.CAT_RELIC -> Material.TOTEM_OF_UNDYING;
            case CodexService.CAT_AFFIX -> Material.ENCHANTED_BOOK;
            case CodexService.CAT_SPIRIT -> Material.NAME_TAG;
            default -> Material.PAPER;
        };
    }

    private static int contentSize() {
        return CONTENT_TO - CONTENT_FROM + 1;
    }

    // ------------------------------------------------------------ 各分类条目

    private static List<ItemStack> entries(ForgingMasterPlugin plugin, CodexService service,
                                           UUID id, String tab) {
        return switch (tab) {
            case CodexService.CAT_QUALITY -> qualityEntries(service, id);
            case CodexService.CAT_FORM -> formEntries(plugin, service, id);
            case CodexService.CAT_SCORE -> scoreEntries(service, id);
            case CodexService.CAT_RELIC -> relicEntries(plugin, service, id);
            case CodexService.CAT_AFFIX -> affixEntries(plugin, service, id);
            case CodexService.CAT_SPIRIT -> spiritEntries(service, id);
            default -> List.of();
        };
    }

    private static List<ItemStack> qualityEntries(CodexService service, UUID id) {
        List<ItemStack> out = new ArrayList<>();
        for (Quality q : Quality.values()) {
            boolean got = service.hasQuality(id, q);
            long at = service.at(id, CodexService.CAT_QUALITY + ":" + q.name());
            out.add(ForgeMenu.icon(q.icon(),
                    got ? q.colored() + " <dark_gray>· 品质" : "<dark_gray>？？？ 剪影",
                    got ? List.of(
                            "<gray>品质: " + q.colored(),
                            "<gray>达成: <white>" + dateOf(at),
                            "",
                            "<dark_gray>已收录进图鉴")
                            : List.of(
                            "<gray>线索: 锻出一件" + q.display() + "品质的剑",
                            "<dark_gray>评分区间: " + (int) q.minScore() + " 分以上")));
        }
        return out;
    }

    private static List<ItemStack> formEntries(ForgingMasterPlugin plugin, CodexService service, UUID id) {
        List<ItemStack> out = new ArrayList<>();
        for (com.forgemaster.form.SwordForm f : formsList(plugin)) {
            boolean got = service.hasForm(id, f.id());
            long at = service.at(id, CodexService.CAT_FORM + ":" + f.id().toLowerCase(java.util.Locale.ROOT));
            String name = "<color:" + f.color() + ">" + f.display() + "</color> <dark_gray>· 剑型";
            out.add(ForgeMenu.icon(formIcon(f.id()),
                    got ? name : "<dark_gray>？？？ 剪影",
                    got ? List.of(
                            "<gray>剑型: <color:" + f.color() + ">" + f.display() + "</color>",
                            "<gray>达成: <white>" + dateOf(at),
                            "<gray>估值乘区: <white>×" + Msg.fmt(f.valueMult(), 2))
                            : List.of(
                            "<gray>线索: 铸出一把「<color:" + f.color() + ">" + f.display() + "</color>」剑型的成品",
                            "<dark_gray>剑型在淬火时随机定型")));
        }
        return out;
    }

    private static List<ItemStack> scoreEntries(CodexService service, UUID id) {
        List<ItemStack> out = new ArrayList<>();
        Material[] icons = {Material.IRON_SWORD, Material.IRON_SWORD, Material.GOLDEN_SWORD,
                Material.DIAMOND_SWORD, Material.NETHERITE_SWORD, Material.NETHERITE_SWORD};
        for (int i = 0; i < CodexService.SCORE_TIERS.length; i++) {
            int tier = CodexService.SCORE_TIERS[i];
            boolean got = service.hasScore(id, tier);
            long at = service.at(id, CodexService.CAT_SCORE + ":" + tier);
            out.add(ForgeMenu.icon(icons[Math.min(i, icons.length - 1)],
                    got ? "<#ffd166>" + tier + " 分里程碑" : "<dark_gray>？？？ 剪影",
                    got ? List.of(
                            "<gray>里程碑: <white>单把剑 " + tier + " 分",
                            "<gray>达成: <white>" + dateOf(at))
                            : List.of(
                            "<gray>线索: 单把剑评分达到 <white>" + tier + "</white> 分",
                            "<dark_gray>当前最高: <white>" + Msg.fmt(service.statsBestScore(id), 1) + " 分")));
        }
        return out;
    }

    private static List<ItemStack> relicEntries(ForgingMasterPlugin plugin, CodexService service, UUID id) {
        List<ItemStack> out = new ArrayList<>();
        for (com.forgemaster.contract.RelicPool.Def d : relicsList(plugin)) {
            boolean got = service.hasRelic(id, d.id());
            StringBuilder stars = new StringBuilder();
            for (int i = 0; i < Math.max(1, Math.min(5, d.rarity())); i++) {
                stars.append("★");
            }
            out.add(ForgeMenu.icon(relicIcon(d.rarity()),
                    got ? "<#ffb84d>" + d.name() + " <dark_gray>· 圣物" : "<dark_gray>？？？ 剪影",
                    got ? List.of(
                            "<gray>圣物: <white>" + d.name(),
                            "<gray>稀有度: <#ffd166>" + stars,
                            "<gray>效果: " + d.desc())
                            : List.of(
                            "<gray>线索: 在龙税周目里抽到圣物「<white>" + d.name() + "</white>」",
                            "<gray>稀有度: <dark_gray>" + stars)));
        }
        return out;
    }

    private static List<ItemStack> affixEntries(ForgingMasterPlugin plugin, CodexService service, UUID id) {
        List<ItemStack> out = new ArrayList<>();
        for (Affix a : plugin.affixes().all()) {
            boolean got = service.hasAffix(id, a.id());
            out.add(ForgeMenu.icon(Material.ENCHANTED_BOOK,
                    got ? "<#b06bff>" + a.display() + " <dark_gray>· 词条" : "<dark_gray>？？？ 剪影",
                    got ? List.of(
                            "<gray>词条: <white>" + a.display(),
                            "<gray>达成: <white>" + dateOf(service.at(id,
                                    CodexService.CAT_AFFIX + ":" + a.id().toLowerCase(java.util.Locale.ROOT))))
                            : List.of(
                            "<gray>线索: 获得一条「<white>" + a.display() + "</white>」词条",
                            "<dark_gray>锻造出词条或重铸抽到都会登记")));
        }
        return out;
    }

    private static List<ItemStack> spiritEntries(CodexService service, UUID id) {
        boolean got = service.hasSpiritName(id);
        return List.of(ForgeMenu.icon(Material.NAME_TAG,
                got ? "<#7fd3ff>赐名者 <dark_gray>· 剑灵命名" : "<dark_gray>？？？ 剪影",
                got ? List.of(
                        "<gray>达成: <white>" + dateOf(service.at(id, CodexService.SPIRIT_KEY)),
                        "<gray>你为一把剑取下了名字。")
                        : List.of(
                        "<gray>线索: 用 <white>/zhujian spirit name <名字></white> 为一把剑命名",
                        "<dark_gray>剑有灵，名有主。")));
    }

    // ------------------------------------------------------------ 小工具

    /** 达成日期；登记机制上线前（推导自旧统计）显示占位 */
    private static String dateOf(long at) {
        return at > 0
                ? DATE.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
                : "<dark_gray>登记前达成";
    }

    private static java.util.List<com.forgemaster.form.SwordForm> formsList(ForgingMasterPlugin plugin) {
        if (plugin.forms() == null || plugin.forms().isEmpty()) {
            return List.of(com.forgemaster.form.SwordForm.defaults());
        }
        return plugin.forms().all();
    }

    private static java.util.List<com.forgemaster.contract.RelicPool.Def> relicsList(ForgingMasterPlugin plugin) {
        if (plugin.contractManager() == null) {
            return List.of(com.forgemaster.contract.RelicPool.defaults());
        }
        List<com.forgemaster.contract.RelicPool.Def> all = plugin.contractManager().relics().all();
        return all.isEmpty() ? List.of(com.forgemaster.contract.RelicPool.defaults()) : all;
    }

    private static Material formIcon(String formId) {
        return switch (formId) {
            case "plain" -> Material.WOODEN_SWORD;
            case "shadow" -> Material.IRON_SWORD;
            case "heavy" -> Material.STONE_SWORD;
            case "famed" -> Material.GOLDEN_SWORD;
            case "moon" -> Material.DIAMOND_SWORD;
            default -> Material.IRON_SWORD;
        };
    }

    private static Material relicIcon(int rarity) {
        return switch (Math.max(1, Math.min(5, rarity))) {
            case 5 -> Material.ENCHANTED_GOLDEN_APPLE;
            case 4 -> Material.GOLDEN_APPLE;
            case 3 -> Material.EMERALD;
            case 2 -> Material.GOLD_INGOT;
            default -> Material.IRON_INGOT;
        };
    }
}
