package com.forgemaster.listener;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.forge.ForgeManager;
import com.forgemaster.forge.ForgeSession;
import com.forgemaster.gui.ArmorMenuHolder;
import com.forgemaster.gui.ForgeHolder;
import com.forgemaster.gui.ForgeMenu;
import com.forgemaster.gui.GemMenuHolder;
import com.forgemaster.gui.MainMenuHolder;
import com.forgemaster.gui.WeaponMenuHolder;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.util.Msg;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.List;

/**
 * 所有界面与方块交互入口。
 */
public final class ForgeListener implements Listener {

    private final ForgingMasterPlugin plugin;
    private final ForgeManager manager;

    public ForgeListener(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.manager = plugin.forge();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();

        if (holder instanceof ForgeHolder forgeHolder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) {
                return;
            }
            if (event.getClick() == ClickType.DOUBLE_CLICK) {
                return;
            }
            ForgeSession session = forgeHolder.session();
            if (session == null) {
                return;
            }
            // v2.23.1 修 P2-E：原先缺「点击者 = 会话主人」这道校验，而同文件的
            // ContractHolder / EvolutionHolder 都有。任何让第三方看到同一份 Inventory
            // 的途径（/invsee 类、共享界面、GUI 同步 mod）都能替别人点「放弃 / 淬火选择」，
            // 白丢材料。handleClick 内部落账用的是 session.player()，所以这里不匹配就直接
            // return（事件已在上面 setCancelled(true)，不会放行任何点击）。
            if (!player.getUniqueId().equals(session.uuid())) {
                return;
            }
            // v2.23.1 兜底：会话已结算但成品界面还停在玩家面前时，点哪都要能把成品拿走。
            // 正常路径下这种状态不该出现（见 ForgeManager.open 与 onInventoryClosed），
            // 但真出现了，绝不能让玩家面对一个「点不动」的按钮，关掉之后剑就没了。
            if (session.finished()) {
                manager.reclaim(session, player);
                return;
            }
            manager.handleClick(session, event.getRawSlot(), event.getClick().isShiftClick());
            return;
        }

        if (holder instanceof com.forgemaster.gui.ContractHolder contractHolder) {
            // 龙税契约界面：贡品面板 / 圣物三选一
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) {
                return;
            }
            if (!player.getUniqueId().equals(contractHolder.player())) {
                return;
            }
            com.forgemaster.gui.ContractMenu.click(plugin, player, contractHolder, event.getRawSlot());
            return;
        }

        if (holder instanceof com.forgemaster.evolve.EvolutionHolder evoHolder) {
            // 蜕变界面：只有打开者本人能点，路由给 EvolutionMenu
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) {
                return;
            }
            if (!player.getUniqueId().equals(evoHolder.player())) {
                return;
            }
            com.forgemaster.evolve.EvolutionMenu.click(plugin, player, event.getRawSlot());
            return;
        }

        if (holder instanceof com.forgemaster.env.EnvPanel.EnvPanelHolder) {
            // 环境面板只读：取消点击，点关闭（27 格第 22 格）直接关闭
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player && event.getRawSlot() == 22) {
                player.closeInventory();
            }
            return;
        }

        if (holder instanceof com.forgemaster.manuals.ManualStore store) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) {
                return;
            }
            int slot = event.getRawSlot();
            int closeAt = top.getSize() - 4;
            if (slot == closeAt) {
                player.closeInventory();
                return;
            }
            if (slot >= 0 && slot < top.getSize() - 9) {
                var entries = store.all();
                if (slot < entries.size()) {
                    String id = entries.get(slot).id();
                    player.closeInventory();
                    Msg.prefix(player, store.startCopy(player, id));
                }
            }
            return;
        }

        if (holder instanceof com.forgemaster.growth.AchvHolder achvHolder) {
            new com.forgemaster.growth.AchvMenu(plugin).click(event, achvHolder);
            return;
        }
        if (holder instanceof com.forgemaster.commission.BoardHolder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) {
                return;
            }
            int slot = event.getRawSlot();
            if (slot == com.forgemaster.commission.CommissionManager.CLOSE_SLOT) {
                player.closeInventory();
                return;
            }
            // 换单：Shift + 点某张委托，退掉它换一张新的
            if (event.getClick().isShiftClick()) {
                plugin.commissions().reroll(player, slot);
                player.openInventory(plugin.commissions().buildBoard(player));
                return;
            }
            if (plugin.commissions().deliver(player, slot)) {
                player.closeInventory();
                player.openInventory(plugin.commissions().buildBoard(player));
                return;
            }
            // 交付失败或点在别处：原地重绘，保证手上物品的满足情况是最新的
            player.openInventory(plugin.commissions().buildBoard(player));
            return;
        }

        if (holder instanceof com.forgemaster.tomb.TombGui tombGui) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) {
                return;
            }
            int slot = event.getRawSlot();
            if (slot == top.getSize() - 5) {
                player.closeInventory();
                return;
            }
            String id = tombGui.idAt(slot);
            if (id == null) {
                return;
            }
            var tomb = tombGui.manager().get(id);
            if (tomb == null) {
                player.openInventory(new com.forgemaster.tomb.TombGui(tombGui.manager()).build().getInventory());
                return;
            }
            player.closeInventory();
            if (event.getClick().isRightClick()) {
                tombGui.manager().info(player, id);
            } else if (tomb.owner.equals(player.getUniqueId())) {
                tombGui.manager().take(player, id);
            } else {
                tombGui.manager().challenge(player, id);
            }
            return;
        }

        if (holder instanceof GemMenuHolder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) {
                return;
            }
            int slot = event.getRawSlot();
            player.closeInventory();
            switch (slot) {
                case 10 -> plugin.gems().socket(player);          // 宝石开孔
                case 11 -> plugin.gems().socketEnchant(player);   // 附魔开孔
                case 13 -> plugin.gems().smelt(player);           // 星熔宝石
                case 15 -> plugin.gems().inlay(player);           // 宝石镶嵌
                case 16 -> plugin.gems().inlayEnchant(player);    // 附魔镶嵌
                case 20 -> plugin.gems().extract(player);         // 拆卸宝石
                case 24 -> plugin.gems().merge(player);           // 合成宝石
                default -> {
                    // 关闭按钮或空白格：什么都不做
                }
            }
            return;
        }

        if (holder instanceof MainMenuHolder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) {
                return;
            }
            int slot = event.getRawSlot();
            if (slot == 11) {
                player.closeInventory();
                manager.openWeapons(player);
                return;
            }
            if (slot == 15) {
                player.closeInventory();
                manager.openArmorSlots(player);
                return;
            }
            if (slot == 22) {
                player.closeInventory();
                player.performCommand("zhujian codex");
            } else if (slot == 26) {
                player.closeInventory();
                player.performCommand("zhujian stats");
            } else if (slot == 18) {
                player.closeInventory();
                player.openInventory(plugin.commissions().buildBoard(player));
            } else if (slot == 20) {
                player.closeInventory();
                player.openInventory(plugin.manuals().buildList());
            } else if (slot == 24) {
                player.closeInventory();
                // v2.7：大赛现在有赛况面板了，别只发一条聊天文本让玩家找命令
                com.forgemaster.contests.ContestGui.open(plugin, player);
            } else if (slot == 25) {
                player.closeInventory();
                player.performCommand("zhujian rank");
            }
            return;
        }

        if (holder instanceof WeaponMenuHolder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) {
                return;
            }
            int slot = event.getRawSlot();
            if (slot == 17) {
                player.closeInventory();
                manager.openMenu(player);
                return;
            }
            List<ForgeMaterial> sorted = plugin.materials().sorted();
            int[] slots = ForgeMenu.MATERIAL_SLOTS;
            for (int i = 0; i < slots.length && i < sorted.size(); i++) {
                if (slots[i] == slot) {
                    player.closeInventory();
                    manager.begin(player, sorted.get(i));
                    return;
                }
            }
            return;
        }

        if (holder instanceof ArmorMenuHolder armorHolder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) {
                return;
            }
            int slot = event.getRawSlot();
            if (armorHolder.stage() == ArmorMenuHolder.Stage.SLOT) {
                if (slot == 22) {
                    player.closeInventory();
                    manager.openMenu(player);
                    return;
                }
                ForgeMaterial.Slot[] parts = ForgeMaterial.Slot.values();
                int[] positions = {11, 12, 13, 14};
                for (int i = 0; i < parts.length && i < positions.length; i++) {
                    if (positions[i] == slot) {
                        player.closeInventory();
                        manager.openArmorTiers(player, parts[i]);
                        return;
                    }
                }
                return;
            }
            // TIER 阶段
            if (slot == 22) {
                player.closeInventory();
                manager.openArmorSlots(player);
                return;
            }
            List<ForgeMaterial> sorted = plugin.materials().sorted();
            int[] slots = ForgeMenu.MATERIAL_SLOTS;
            int shown = 0;
            for (int i = 0; i < sorted.size() && shown < slots.length; i++) {
                ForgeMaterial base = sorted.get(i);
                if (com.forgemaster.material.ArmorCatalog.create(base, armorHolder.slot(), 1.0) == null) {
                    continue;
                }
                if (slots[shown] == slot) {
                    player.closeInventory();
                    manager.beginArmor(player, base, armorHolder.slot());
                    return;
                }
                shown++;
            }
        }
    }

    /**
     * 插件自绘界面一律不接受玩家拖入 / 拖出物品。
     *
     * <p>v2.23.1 修 P1（审计 22）：原来只拦了 9 个 holder，漏了
     * {@code ContractHolder}（龙税契约）、{@code EvolutionHolder}（蜕变）、
     * {@code EnvPanel.EnvPanelHolder}（环境面板）。往这三种界面里拖动，
     * 物品落在自绘格子里，界面一关随清空一起蒸发（物品丢失）。
     * {@code onClick} 早就有这三个分支，唯独拖动这条路漏写。</p>
     *
     * <p>现在与 {@link #onClick} 的 holder 清单逐项对齐——onClick 出现过的、
     * 且界面本身不放玩家物品的 holder 全部在此拦下：</p>
     * <ol>
     *   <li>{@code ForgeHolder} 锻造台</li>
     *   <li>{@code ContractHolder} 龙税契约</li>
     *   <li>{@code EvolutionHolder} 蜕变</li>
     *   <li>{@code EnvPanel.EnvPanelHolder} 环境面板（只读）</li>
     *   <li>{@code ManualStore} 剑谱商店</li>
     *   <li>{@code AchvHolder} 成就</li>
     *   <li>{@code BoardHolder} 委托板</li>
     *   <li>{@code TombGui} 剑冢</li>
     *   <li>{@code GemMenuHolder} 宝石</li>
     *   <li>{@code MainMenuHolder} 主菜单</li>
     *   <li>{@code WeaponMenuHolder} 武器选胚</li>
     *   <li>{@code ArmorMenuHolder} 装备选部位 / 档位</li>
     * </ol>
     * <p>新增界面时两边同时加，别只加 onClick。</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof ForgeHolder
                || holder instanceof com.forgemaster.gui.ContractHolder
                || holder instanceof com.forgemaster.evolve.EvolutionHolder
                || holder instanceof com.forgemaster.env.EnvPanel.EnvPanelHolder
                || holder instanceof com.forgemaster.manuals.ManualStore
                || holder instanceof com.forgemaster.growth.AchvHolder
                || holder instanceof com.forgemaster.commission.BoardHolder
                || holder instanceof com.forgemaster.tomb.TombGui
                || holder instanceof GemMenuHolder
                || holder instanceof MainMenuHolder
                || holder instanceof WeaponMenuHolder
                || holder instanceof ArmorMenuHolder) {
            // 整段取消：既不放进去，也不让拖出去
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamaged(org.bukkit.event.entity.EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        // 审计 #6：装备读取合并进 EquipmentDamageScan 单遍扫描——同一次伤害事件里
        // NORMAL 优先级的 AffixSetListener 已经扫过一遍并缓存，这里直接复用，
        // 只取词条减伤那份（套装部分归它用）；本监听器的优先级与结算逻辑不变。
        double reduce = com.forgemaster.affix.EquipmentDamageScan
                .scan(player, event, null).affixReduce();
        if (reduce <= 0) {
            return;
        }
        // 四件套叠满也不会完全免疫，最多减 60%
        reduce = Math.min(0.6, reduce);
        event.setDamage(event.getDamage() * (1.0 - reduce));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof ForgeHolder holder) {
            ForgeSession session = holder.session();
            if (session != null) {
                // 把「刚关掉的是哪张界面」传进去：切换界面与真关界面必须分开处理，
                // 否则进成品界面那一次切换会被当成关闭 → 会话提前销毁（v2.23.1）
                manager.onInventoryClosed(session, event.getInventory());
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ForgeSession session = manager.session(event.getPlayer());
        if (session != null) {
            manager.cancel(session, null);
        }
        if (plugin.duels() != null) {
            plugin.duels().onQuit(event.getPlayer().getUniqueId());
        }
        // 极限锻造预设跟着人走：退服就作废，别让静态 ARMED 只增不减
        com.forgemaster.forge.RiskMode.disarm(event.getPlayer().getUniqueId());
    }

    /** 潜行 + 右键铁砧 = 直接开锻造台 */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!plugin.settings().anvilShortcut()) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || !event.getPlayer().isSneaking()) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.ANVIL) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("forgemaster.use")) {
            return;
        }
        event.setCancelled(true);
        manager.openMenu(player);
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.5f, 1.2f);
    }
}