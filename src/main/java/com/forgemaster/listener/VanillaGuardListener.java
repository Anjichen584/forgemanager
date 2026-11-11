package com.forgemaster.listener;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.util.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.EnchantingInventory;
import org.bukkit.inventory.GrindstoneInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * 拦住「插件锻造品」进原版附魔台 / 铁砧 / 砂轮。
 *
 * <p><b>只拦插件物品，不封方块。</b>普通钻石剑该附魔附魔、该修修、该改名改名，
 * 原版玩法一点不受影响；只有 {@code /zhujian} 造出来的东西放进去会被弹出来。</p>
 *
 * <p>以前的写法是把整个附魔台和铁砧封死（连右键都不让点），那等于把原版附魔
 * 和修理一起废了，服主明确要的是「插件装备禁止交互」，不是「服里不许有附魔台」。</p>
 *
 * <p><b>为什么still要拦这么多层</b>：光挡「放进去」这一下不够——
 * shift 点击、鼠标拖拽、漏斗、别的插件代操作都能绕过单点校验。
 * 所以照旧从「点击 / 拖拽 / 生成结果 / 附魔事件」四个入口收口，
 * 任何一个入口漏了，其它几个兜住。</p>
 *
 * <p>配置 {@code vanilla-guard.block-all: true} 可以退回旧行为
 * （连原版物品也一起封、方块右键直接拦），供确实不想要原版附魔的服使用。</p>
 */
public final class VanillaGuardListener implements Listener {

    private final ForgingMasterPlugin plugin;

    public VanillaGuardListener(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ 配置

    private boolean enabled() {
        return plugin.settings().flag("vanilla-guard.enabled", true);
    }

    /**
     * 是否退回「整个方块封死」的旧行为。
     *
     * <p>默认 false：只拦插件物品。设成 true 时连原版物品也拦，右键直接不让开。</p>
     */
    private boolean blockAll() {
        return plugin.settings().flag("vanilla-guard.block-all", false);
    }

    private boolean blockEnchantTable() {
        return plugin.settings().flag("vanilla-guard.enchanting-table", true);
    }

    private boolean blockAnvil() {
        return plugin.settings().flag("vanilla-guard.anvil", true);
    }

    private boolean blockGrindstone() {
        return plugin.settings().flag("vanilla-guard.grindstone", false);
    }

    private String msgEnchantTable() {
        return plugin.settings().str("vanilla-guard.message.enchanting-table",
                "<red>这把锻造品不能进附魔台——用 <white>/zhujian gem inlay</white> 把附魔书镶进孔里。");
    }

    private String msgAnvil() {
        return plugin.settings().str("vanilla-guard.message.anvil",
                "<red>这把锻造品不能进铁砧——修剑用 <white>/zhujian repair</white>，强化把剑放到铁砧上、手持重锤左键敲。");
    }

    private String msgGrindstone() {
        return plugin.settings().str("vanilla-guard.message.grindstone",
                "<red>这把锻造品不能进砂轮。");
    }

    // ------------------------------------------------------------ 判定核心

    private String messageOf(Table table) {
        return switch (table) {
            case ENCHANT -> msgEnchantTable();
            case ANVIL -> msgAnvil();
            case GRINDSTONE -> msgGrindstone();
        };
    }

    /** 三个受管的原版台子 */
    private enum Table {
        ENCHANT, ANVIL, GRINDSTONE
    }

    /** 方块 / 界面类型 → 台子；不相关返回 null */
    private Table tableOf(Material type) {
        return switch (type) {
            case ENCHANTING_TABLE -> Table.ENCHANT;
            case ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL -> Table.ANVIL;
            case GRINDSTONE -> Table.GRINDSTONE;
            default -> null;
        };
    }

    private Table tableOf(Inventory inventory) {
        if (inventory instanceof EnchantingInventory) {
            return Table.ENCHANT;
        }
        if (inventory instanceof AnvilInventory) {
            return Table.ANVIL;
        }
        if (inventory instanceof GrindstoneInventory) {
            return Table.GRINDSTONE;
        }
        // 兜底：别的插件用 createInventory 造的同类型界面
        return switch (inventory.getType()) {
            case ENCHANTING -> Table.ENCHANT;
            case ANVIL -> Table.ANVIL;
            case GRINDSTONE -> Table.GRINDSTONE;
            default -> null;
        };
    }

    /** 这个台子的开关是否打开 */
    private boolean enabled(Table table) {
        return switch (table) {
            case ENCHANT -> blockEnchantTable();
            case ANVIL -> blockAnvil();
            case GRINDSTONE -> blockGrindstone();
        };
    }

    /** 带配置开关的完整判定 */
    private boolean shouldBlock(ItemStack item, Table table) {
        if (!enabled() || !enabled(table)) {
            return false;
        }
        if (blockAll()) {
            return true;
        }
        return plugin.items() != null && plugin.items().isForged(item);
    }

    /**
     * 这个槽位是不是「输入位」——只有输入位（以及铁砧的结果位）才需要管。
     *
     * <p>玩家在自己的背包里点来点去不该被拦。</p>
     */
    private boolean isInputSlot(int rawSlot, Table table) {
        if (rawSlot < 0) {
            return false;
        }
        return switch (table) {
            // 附魔台：0 = 物品位，1 = 青金石位
            case ENCHANT -> rawSlot == 0;
            // 铁砧：0 / 1 输入，2 结果
            case ANVIL -> rawSlot <= 2;
            // 砂轮：0 / 1 输入，2 结果
            case GRINDSTONE -> rawSlot <= 2;
        };
    }

    // ------------------------------------------------------------ 第一道：点击

    /**
     * 拦「把锻造品放进台子」以及「从这里把东西拿走」。
     *
     * <p>覆盖普通点击、shift 点击、数字键交换、双击收集、以及把东西拖到结果位。</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!enabled() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        Table table = tableOf(top);
        if (table == null || !enabled(table)) {
            return;
        }

        int raw = event.getRawSlot();
        boolean topSide = raw >= 0 && raw < top.getSize();
        if (!topSide || !isInputSlot(raw, table)) {
            // 不在输入位：只处理「从自己背包 shift 推进台子」
            if (event.isShiftClick() && !topSide && shouldBlock(event.getCurrentItem(), table)) {
                event.setCancelled(true);
                Msg.prefix(player, messageOf(table));
            }
            return;
        }

        // ---- 以下都是「点在输入位」----

        // 1) 光标或鼠标上拿着锻造品往里放
        if (shouldBlock(event.getCursor(), table)) {
            event.setCancelled(true);
            Msg.prefix(player, messageOf(table));
            return;
        }

        // 2) 数字键交换：背包快捷栏那件会被换进来
        if (event.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY) {
            ItemStack hotbar = player.getInventory().getItem(event.getHotbarButton());
            if (shouldBlock(hotbar, table)) {
                event.setCancelled(true);
                Msg.prefix(player, messageOf(table));
            }
            return;
        }

        // 3) 副手交换
        if (event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND) {
            if (shouldBlock(player.getInventory().getItemInOffHand(), table)) {
                event.setCancelled(true);
                Msg.prefix(player, messageOf(table));
            }
            return;
        }

        // 4) shift 点击输入位本身 = 往外拿。
        //    这种情况要放行，否则万一锻造品已经在里面（插件重载前放进去的），
        //    玩家会拿不出来，卡死在台子里。
        if (event.isShiftClick()) {
            return;
        }

        // 5) 普通左/右键：光标空着就是「取出」，放行；
        //    上面第 1 条已经挡掉了「拿着锻造品放进去」。
    }

    /**
     * 拦鼠标拖拽：一次拖过多个槽，单点点击校验看不到。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!enabled() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Table table = tableOf(event.getView().getTopInventory());
        if (table == null || !enabled(table)) {
            return;
        }
        if (!shouldBlock(event.getOldCursor(), table)) {
            return;
        }
        for (int raw : event.getRawSlots()) {
            if (isInputSlot(raw, table)) {
                event.setCancelled(true);
                Msg.prefix(player, messageOf(table));
                return;
            }
        }
    }

    // ------------------------------------------------------------ 第二道：生成结果

    /**
     * 铁砧：只要输入里有一件锻造品，就不出结果。
     *
     * <p>不出结果比「取消点击」更彻底——玩家看见的是空的，不会以为能点。</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!enabled() || !enabled(Table.ANVIL)) {
            return;
        }
        AnvilInventory inv = event.getInventory();
        if (shouldBlock(inv.getItem(0), Table.ANVIL) || shouldBlock(inv.getItem(1), Table.ANVIL)) {
            event.setResult(null);
        }
    }

    /**
     * 附魔台：锻造品放进去连附魔选项都不生成。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareEnchant(PrepareItemEnchantEvent event) {
        if (!enabled() || !enabled(Table.ENCHANT)) {
            return;
        }
        if (shouldBlock(event.getItem(), Table.ENCHANT)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ 第三道：真正执行

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        if (!enabled() || !enabled(Table.ENCHANT)) {
            return;
        }
        if (shouldBlock(event.getItem(), Table.ENCHANT)) {
            event.setCancelled(true);
            if (event.getEnchanter() instanceof Player player) {
                Msg.prefix(player, msgEnchantTable());
            }
        }
    }

    // ------------------------------------------------------------ 自检用

    /** 这个台子当前是否受管（自检用） */
    public boolean blocks(Material type) {
        Table table = tableOf(type);
        return table != null && enabled() && enabled(table);
    }

    /** 这个界面当前是否受管（自检用） */
    public boolean blocksInventory(Inventory inventory) {
        Table table = tableOf(inventory);
        return table != null && enabled() && enabled(table);
    }

    /**
     * 这件物品放进这个台子会不会被拦（自检用）。
     */
    public boolean blocksItem(ItemStack item, Material type) {
        Table table = tableOf(type);
        if (table == null) {
            return false;
        }
        return shouldBlock(item, table);
    }
}
