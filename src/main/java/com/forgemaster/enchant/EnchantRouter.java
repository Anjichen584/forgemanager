package com.forgemaster.enchant;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 附魔台的点击路由（v2.23.2，照 {@code PassGuiRouter} 的懒注册做法）。
 *
 * <h2>为什么要自备一份路由</h2>
 * {@code ForgeListener} 只认它自己那几个 holder，对陌生 holder 是「不取消」的，
 * 那样玩家能把装饰图标、甚至把信息栏拖出去。所以这里把附魔台的点击全接过来：
 * 三个自由格（书 / 矿 / 成品）允许玩家自己放进取出，其余一律取消。
 *
 * <h2>关闭时必须把东西还给玩家</h2>
 * 界面一关，格子里的书和矿会随 Inventory 一起蒸发（Bukkit 不会自动退还）。
 * 所以 {@code onClose} 一定要主动 {@code give} —— 这条是这类界面最经典的丢物品 bug。
 */
public final class EnchantRouter implements Listener {

    private static boolean ready;

    /** 首次打开界面时注册（不碰主类） */
    static void ensure(ForgingMasterPlugin plugin) {
        if (ready) {
            return;
        }
        ready = true;
        Bukkit.getPluginManager().registerEvents(new EnchantRouter(plugin), plugin);
    }

    /** 插件卸载时调用，否则 /reload 之后监听器会重复注册 */
    public static void reset() {
        ready = false;
    }

    private final ForgingMasterPlugin plugin;

    private EnchantRouter(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof EnchantGui.Holder holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        // 别人打开的界面不算你的（防跨玩家复用）
        if (!holder.player().equals(player.getUniqueId())) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }

        int raw = event.getRawSlot();
        // shift 点击一律取消：原版会把整叠物品「智能插入」顶部空位，
        // 一旦某个装饰格因为重画时机被清空，玩家就能把东西刷进去（这类界面最常见的刷物品口）
        if (event.isShiftClick()) {
            event.setCancelled(true);
            return;
        }
        // 点在自己背包里（raw >= 顶部尺寸）：放行。v2.26.5（审计 #嫌疑）：原来
        // setCancelled(false) 会强行覆盖背包保护类插件的决定——Router 只该管
        // 自己的顶部格子，别反向放行别人的取消。
        if (raw >= EnchantGui.SIZE) {
            return;
        }
        if (raw == EnchantGui.ACTION_SLOT) {
            event.setCancelled(true);
            plugin.enchantTable().apply(player, event.getInventory());
            return;
        }
        if (raw == EnchantGui.CLOSE_SLOT) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }
        if (raw == EnchantGui.BOOK_SLOT || raw == EnchantGui.ORE_SLOT
                || raw == EnchantGui.RESULT_SLOT) {
            // 自由格：放取由玩家决定（shift 已在上方统一拦掉）
            event.setCancelled(false);
            // 放完东西下一 tick 重画信息栏（延迟一 tick 才拿得到新内容）
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.getOpenInventory().getTopInventory().getHolder() instanceof EnchantGui.Holder) {
                    EnchantGui.render(plugin, player, player.getOpenInventory().getTopInventory());
                }
            });
            return;
        }
        // 其余装饰格：一律取消
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof EnchantGui.Holder)) {
            return;
        }
        for (int slot : event.getRawSlots()) {
            if (slot < EnchantGui.SIZE
                    && slot != EnchantGui.BOOK_SLOT && slot != EnchantGui.ORE_SLOT
                    && slot != EnchantGui.RESULT_SLOT) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof EnchantGui.Holder holder)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        // v2.26.6（审计 #6）：归属校验必须先于退款循环——原来在循环之后（且校验完直接
        // return 是死代码），第三方经 invsee 类插件关别人台子会把书/矿拿走
        if (!holder.player().equals(player.getUniqueId())) {
            return;
        }
        // 把三个自由格里的东西还给玩家，否则关界面即销毁
        for (int slot : EnchantGui.FREE_SLOTS) {
            ItemStack item = event.getInventory().getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            var left = player.getInventory().addItem(item);
            for (ItemStack drop : left.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
            event.getInventory().setItem(slot, new ItemStack(Material.AIR));
        }
    }
}