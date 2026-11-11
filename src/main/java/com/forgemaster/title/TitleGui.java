package com.forgemaster.title;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.gui.ForgeMenu;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 称号面板：列出 config 全部称号（已解锁 / 未解锁），点击佩戴，尾部按钮脱下。
 *
 * <p>静态快照界面（打开时画一次，佩戴 / 脱下后重画一次），不挂定时任务，
 * 做法照 {@code SpectrumGui}。点击路由在 {@link TitleGuiRouter}（懒注册）。</p>
 *
 * <p>槽位（54 格，本类独占）：</p>
 * <pre>
 *   条目槽    0 .. 44 中除 4 以外的 44 格（按 config 顺序铺，最多 44 条，超出截断并提示）
 *   4         面板状态（当前佩戴 / 已解锁数）—— 被条目区跳过，见 {@link #entrySlot(int)}
 *   49        脱下称号
 *   53        关闭
 * </pre>
 *
 * <p><b>两套索引必须不相交（v2.23.1 修 P1-3）</b>：原先状态图标写 4 号格，条目循环又从
 * {@code FIRST_SLOT = 0} 起连续覆盖 44 格，走到第 5 个条目就把 4 号格换成称号按钮，
 * 还 {@code slotTitles.put(4, ...)} 把它登记成可点击——状态信息永久不可见，
 * 玩家想读状态却误触换称号。现在条目落位统一走 {@link #entrySlot(int)}（跳过状态格），
 * 点击登记也只登记条目槽，4 号格既不是条目、也不响应点击。</p>
 */
public final class TitleGui {

    public static final int SIZE = 54;

    /** 面板状态（装饰槽，永不登记为可点击条目） */
    public static final int STATUS_SLOT = 4;
    /** 条目起始槽 */
    public static final int FIRST_SLOT = 0;
    /** 条目最多条数（要避开 {@link #STATUS_SLOT}，所以可用槽位是 45 格减去状态格 = 44） */
    public static final int MAX_ENTRIES = 44;
    /** 条目区可用槽位上限（含）：条目最多铺到 44 号格，再往后是脱下 / 关闭 */
    public static final int LAST_SLOT = 44;
    /** 脱下 */
    public static final int TAKEOFF_SLOT = 49;
    /** 关闭 */
    public static final int CLOSE_SLOT = 53;

    /**
     * 第 {@code index} 个条目该落在哪个槽位（纯函数，自检直接算）。
     *
     * <p>v2.23.1 修 P1-3：从 {@link #FIRST_SLOT} 顺铺，遇到 {@link #STATUS_SLOT} 跳过一格。
     * 于是条目槽集合 = [FIRST_SLOT, LAST_SLOT] \ {STATUS_SLOT}，与状态格 / 脱下 / 关闭
     * 三个装饰槽完全不相交。绘制与点击登记都只走这一个函数，不可能再错位。</p>
     */
    public static int entrySlot(int index) {
        int slot = FIRST_SLOT + index;
        if (slot >= STATUS_SLOT) {
            slot++; // 跳过状态格：4 号位永远留给面板状态
        }
        return slot;
    }

    /** 面板持有者 */
    public static final class Holder implements InventoryHolder {

        private final ForgingMasterPlugin plugin;
        private final UUID viewer;
        /** 槽位 → 称号 id（点击路由用） */
        private final Map<Integer, String> slotTitles = new LinkedHashMap<>();
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

        /** 槽位上的称号 id（没有返回 null） */
        public String titleIdAt(int slot) {
            return slotTitles.get(slot);
        }

        void inventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private TitleGui() {
    }

    /** 打开称号面板 */
    public static void open(ForgingMasterPlugin plugin, Player player) {
        if (plugin == null || player == null) {
            return;
        }
        TitleGuiRouter.ensure(plugin);
        TitleService service = plugin.titles();
        if (service == null || !service.enabled()) {
            Msg.prefix(player, "<red>还没有可用的称号。</red>");
            return;
        }

        Holder holder = new Holder(plugin, player.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, SIZE,
                Msg.mm("<dark_gray>称号 <dark_gray>| <#ffd166>佩戴"));
        holder.inventory(inv);
        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, ForgeMenu.filler());
        }

        UUID uuid = player.getUniqueId();
        List<TitleService.Title> all = service.all();
        TitleService.Title worn = service.wornOf(uuid);

        // 状态
        long unlockedCount = all.stream().filter(t -> service.unlocked(uuid, t)).count();
        inv.setItem(STATUS_SLOT, ForgeMenu.icon(Material.NAME_TAG,
                "<#ffd166><bold>称号</bold>",
                List.of("<gray>佩戴中的称号会显示在你的聊天前缀，",
                        "<gray>优先于声望称号。",
                        "",
                        "<gray>当前佩戴: <white>" + (worn == null ? "无（声望称号）" : Msg.plain(Msg.mm(worn.colored()))),
                        "<gray>已解锁: <#ffd166>" + unlockedCount + "</#ffd166> / <white>" + all.size(),
                        "<dark_gray>点击已解锁的称号佩戴</dark_gray>")));

        // 条目（v2.23.1 修 P1-3：落位统一走 entrySlot，自动跳过 4 号状态格，
        // 且只把真正的条目槽登记进 slotTitles —— 状态格不再变成称号按钮）
        int shown = 0;
        for (TitleService.Title t : all) {
            if (shown >= MAX_ENTRIES) {
                break;
            }
            int slot = entrySlot(shown);
            if (slot > LAST_SLOT) {
                break; // 兜底：条目区到顶就不再铺
            }
            boolean unlocked = service.unlocked(uuid, t);
            boolean wearing = worn != null && worn.id.equals(t.id);
            holder.slotTitles.put(slot, t.id);
            inv.setItem(slot, entryIcon(service, t, uuid, unlocked, wearing));
            shown++;
        }

        // 脱下 / 关闭
        inv.setItem(TAKEOFF_SLOT, ForgeMenu.icon(
                worn == null ? Material.GRAY_DYE : Material.LIME_DYE,
                "<yellow>脱下称号",
                worn == null
                        ? List.of("<gray>你现在没戴称号。")
                        : List.of("<gray>脱下「" + Msg.plain(Msg.mm(worn.colored())) + "</gray><gray>」，",
                                "<gray>恢复声望称号。")));
        inv.setItem(CLOSE_SLOT, ForgeMenu.icon(Material.BARRIER,
                "<red>关闭", List.of("<gray>收好你的名片。")));

        if (shown < all.size()) {
            Msg.prefix(player, "<gray>称号太多，面板只显示前 " + shown + " 条（config titles 共 "
                    + all.size() + " 条）。</gray>");
        }
        player.openInventory(inv);
    }

    private static ItemStack entryIcon(TitleService service, TitleService.Title t, UUID uuid,
                                       boolean unlocked, boolean wearing) {
        int cur = service.currentValue(uuid, t);
        int need = service.targetOf(t);
        List<String> lore = new ArrayList<>();
        String progress = (cur >= need ? "<green>" : "<red>") + cur
                + (cur >= need ? "</green>" : "</red>") + "<gray> / " + need;
        if (t.condition.type.equals("material") || t.condition.type.equals("quality")) {
            // 口径型条件（有/没有）：不给「x / y」进度，直接给达标与否
            progress = (cur >= need ? "<green>已达成" : "<red>未达成");
        }
        lore.add("<gray>解锁条件：" + service.labelOf(t) + " " + progress);
        lore.add("");
        if (wearing) {
            lore.add("<green>✔ 佩戴中");
            lore.add("<dark_gray>点击脱下");
        } else if (unlocked) {
            lore.add("<yellow>已解锁");
            lore.add("<gray>点击佩戴");
        } else {
            lore.add("<red>未解锁");
            lore.add("<dark_gray>条件达成后自动亮起");
        }
        return ForgeMenu.icon(t.icon,
                t.colored() + (wearing ? " <green>[佩戴中]" : ""),
                lore);
    }
}
