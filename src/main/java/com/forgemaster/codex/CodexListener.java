package com.forgemaster.codex;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * 收藏图鉴面板的点击路由，照 {@link com.forgemaster.contests.ContestGuiRouter} 模式：
 * 所有点击与拖拽一律取消（图标拿不出去），页签 / 翻页 / 领奖 / 关闭在这里分发。
 *
 * <p>优先级压到 LOWEST，比 ForgeListener 的 HIGH 先跑；监听器在插件启用时注册
 * （onEnable 里与 CodexService 一起初始化），不懒注册。</p>
 */
public final class CodexListener implements Listener {

    private final ForgingMasterPlugin plugin;

    public CodexListener(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof CodexGui.Holder holder)
                || holder.plugin() != plugin) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() == null
                || !event.getClickedInventory().equals(top)) {
            return;
        }
        int slot = event.getRawSlot();
        CodexService service = plugin.codex();
        if (service == null) {
            return;
        }

        // 页签：0-5
        if (slot >= 0 && slot < CodexService.CATEGORIES.size()) {
            String tab = CodexService.CATEGORIES.get(slot);
            if (!tab.equals(holder.tab())) {
                switchTo(holder, player, tab, 0);
            }
            return;
        }
        switch (slot) {
            case CodexGui.CLOSE_SLOT -> player.closeInventory();
            case CodexGui.PREV_SLOT -> switchTo(holder, player, holder.tab(), holder.page() - 1);
            case CodexGui.NEXT_SLOT -> switchTo(holder, player, holder.tab(), holder.page() + 1);
            case CodexGui.CLAIM_SLOT -> claim(holder, player, service);
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof CodexGui.Holder holder
                && holder.plugin() == plugin) {
            event.setCancelled(true);
        }
    }

    /** 下一 tick 再开：当前界面正在关闭，别跟新界面抢背包 */
    private void switchTo(CodexGui.Holder holder, Player player, String tab, int page) {
        if (page < 0) {
            page = 0;
        }
        final String t = tab;
        final int p = page;
        Bukkit.getScheduler().runTask(plugin,
                () -> CodexGui.open(holder.plugin(), player, t, p));
    }

    private void claim(CodexGui.Holder holder, Player player, CodexService service) {
        String tab = holder.tab();
        long paid = service.claim(new CodexService.PlayerView() {
            @Override
            public void onAllComplete(String title, int reward) {
                Msg.prefix(player, "<gradient:#ffd166:#ff7a18><bold>全图鉴达成！</bold></gradient> "
                        + "<gray>获得称号「<#ffd166>" + title + "</#ffd166>」，奖励 <white>"
                        + reward + "</white> 剑币。");
                plugin.getServer().broadcast(Msg.mm(Msg.PREFIX + "<gray>" + player.getName()
                        + " <gray>集齐了收藏图鉴，获得称号「<#ffd166>" + title + "</#ffd166>」！"));
            }
        }, player.getUniqueId(), tab);
        if (paid > 0) {
            Msg.prefix(player, "<gray>分类奖励到账：<#ffd166>+" + paid + "</#ffd166> 剑币。");
        } else if (!service.claimed(player.getUniqueId(), tab)
                && !service.categoryComplete(player.getUniqueId(), tab)) {
            Msg.prefix(player, "<red>这个分类还没集满，先去补齐进度。");
        }
        switchTo(holder, player, tab, holder.page());
    }
}
