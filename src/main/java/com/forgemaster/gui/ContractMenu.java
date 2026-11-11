package com.forgemaster.gui;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.contract.ContractManager;
import com.forgemaster.contract.ContractStore;
import com.forgemaster.contract.RelicPool;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 龙税契约界面：贡品面板（今天要多少 / 已交多少 / 主手投入）与圣物三选一。
 */
public final class ContractMenu {

    /** 贡品面板：投入主手 / 关闭 */
    public static final int DEPOSIT_SLOT = 22;
    public static final int CLOSE_SLOT = 18;
    /** 圣物三选一的三个槽位 */
    public static final int[] PICK_SLOTS = {11, 13, 15};

    private ContractMenu() {
    }

    /** 打开贡品面板 */
    public static void openPanel(ForgingMasterPlugin plugin, Player p) {
        ContractManager cm = plugin.contractManager();
        if (cm == null || !cm.active()) {
            return;
        }
        ContractStore.Run r = cm.store().run(p.getUniqueId());
        if (r == null || !r.joined) {
            for (String line : cm.describe(p)) {
                Msg.prefix(p, line);
            }
            return;
        }
        // 有待选圣物先开三选一
        if (r.pickPending != null && !r.pickPending.isBlank()) {
            openRelicPick(plugin, p);
            return;
        }
        ContractHolder holder = new ContractHolder(p.getUniqueId(), ContractHolder.Mode.PANEL, List.of());
        Inventory inv = Bukkit.createInventory(holder, 27,
                Msg.mm("<gradient:#ff3d6e:#b06bff>〔龙税〕周目日 " + r.day + "</gradient>"));
        holder.inventory(inv);

        long req = cm.requirement(r.day, r.missed, cm.relicBonus(p.getUniqueId()));
        inv.setItem(4, panel(plugin, Material.DRAGON_HEAD, "<gradient:#ff3d6e:#b06bff><bold>今晚的贡品</bold></gradient>",
                List.of(
                        "<gray>要：</gray><white>" + req + "</white> <gray>估值</gray>",
                        "<gray>已入贡：</gray><white>" + r.pool + "</white>"
                                + (r.pool >= req ? " <green>✔ 已够</green>" : " <yellow>还差 " + (req - r.pool) + "</yellow>"),
                        (r.missed > 0 ? "<red>欠税 " + r.missed + " 次</red>" : "<gray>状态良好</gray>"))));
        inv.setItem(10, panel(plugin, Material.BOOK, "<yellow>圣物（" + r.relics.size() + "）</yellow>",
                relicLore(cm, r)));
        inv.setItem(12, panel(plugin, Material.CLOCK, "<#4dc3ff>午夜验收</#4dc3ff>",
                List.of("<gray>黄昏入贡，午夜巨龙清点。</gray>",
                        "<gray>交满发圣物，交不上宽限一次。</gray>")));

        inv.setItem(DEPOSIT_SLOT, panel(plugin, Material.NETHERITE_SWORD,
                "<green><bold>✦ 投入主手的锻造品 ✦</bold></green>",
                List.of("<gray>主手拿锻造品点这里，剑会被龙收走</gray>",
                        "<gray>估值计入今日贡品。</gray>")));
        inv.setItem(CLOSE_SLOT, panel(plugin, Material.BARRIER, "<red>关闭</red>", List.of()));
        p.openInventory(inv);
    }

    /** 打开圣物三选一 */
    public static void openRelicPick(ForgingMasterPlugin plugin, Player p) {
        ContractManager cm = plugin.contractManager();
        if (cm == null) {
            return;
        }
        ContractStore.Run r = cm.store().run(p.getUniqueId());
        if (r == null || r.pickPending == null || r.pickPending.isBlank()) {
            return;
        }
        List<String> ids = List.of(r.pickPending.split(","));
        ContractHolder holder = new ContractHolder(p.getUniqueId(), ContractHolder.Mode.PICK, ids);
        Inventory inv = Bukkit.createInventory(holder, 27,
                Msg.mm("<gradient:#ffd166:#ff7a3d>〔龙税〕巨龙的赏赐 · 三选一</gradient>"));
        holder.inventory(inv);
        // v2.23.1 修 P1-5：显示槽必须由「条目在 ids 里的原始下标」决定，不能用另一个计数器。
        // 原来的循环是 for (String id : ids) + 遇到 def == null 就 continue —— continue 跳过了
        // 末尾的 i++，于是显示用的 i 只统计有效条目，而 click() 用的是 holder.offer() 的原始下标
        // 去配 PICK_SLOTS[i]。一旦 pickPending 里有配置已删除的圣物 id，两者就整体错开：
        // 显示在 13 号格的是第二件，路由认为 13 号格是第一件 → 领错圣物 / 点了报「没有这个圣物」。
        // 现在下标与槽位一一对应（i 无条件递增），下架的圣物留一格灰玻璃占位而不是跳过。
        for (int i = 0; i < ids.size() && i < PICK_SLOTS.length; i++) {
            String id = ids.get(i);
            int slot = PICK_SLOTS[i];
            RelicPool.Def def = cm.relics().get(id);
            if (def == null) {
                inv.setItem(slot, panel(plugin, Material.GRAY_DYE,
                        "<red>这件圣物已下架",
                        List.of("<gray>配置里已经没有这个 id，这一格作废。", "<dark_gray>" + id)));
                continue; // 占位：不再跳过下标，槽位与 ids 下标保持对齐
            }
            String rarity = "★".repeat(Math.max(1, Math.min(5, def.rarity())));
            inv.setItem(slot,
                    panel(plugin, Material.NETHER_STAR,
                            "<gradient:#ffd166:#ff7a3d><bold>" + def.name() + "</bold></gradient> <gold>" + rarity + "</gold>",
                            List.of(def.desc(), "", "<gray>点击拾取，run 内生效。</gray>")));
        }
        inv.setItem(22, panel(plugin, Material.ENDER_CHEST, "<gray>先放着（稍后再选）</gray>", List.of()));
        p.openInventory(inv);
    }

    private static List<String> relicLore(ContractManager cm, ContractStore.Run r) {
        List<String> lore = new ArrayList<>();
        if (r.relics.isEmpty()) {
            lore.add("<dark_gray>还没有圣物。</dark_gray>");
        }
        for (String id : r.relics) {
            RelicPool.Def d = cm.relics().get(id);
            if (d != null) {
                lore.add("<white>" + d.name() + "</white> <dark_gray>·</dark_gray> " + d.desc());
            }
        }
        return lore;
    }

    /** 点击路由：由 ForgeListener 转发 */
    public static void click(ForgingMasterPlugin plugin, Player player, ContractHolder holder, int rawSlot) {
        if (holder.mode() == ContractHolder.Mode.PANEL) {
            if (rawSlot == CLOSE_SLOT) {
                player.closeInventory();
                return;
            }
            if (rawSlot == DEPOSIT_SLOT) {
                player.closeInventory();
                for (String piece : plugin.contractManager().deposit(player).split("<newline>")) {
                    if (!piece.isBlank()) {
                        Msg.prefix(player, piece);
                    }
                }
            }
            return;
        }
        // PICK 模式：点三个圣物槽位之一
        // v2.23.1 修 P1-5：槽位 → offer() 下标一一对应（显示时已保证不改下标）；
        // 下架圣物的占位格点下去直接忽略，不进 pickRelic，免得多出一条「没有这个圣物」的噪声。
        for (int i = 0; i < PICK_SLOTS.length && i < holder.offer().size(); i++) {
            if (rawSlot == PICK_SLOTS[i]) {
                String id = holder.offer().get(i);
                ContractManager cm = plugin.contractManager();
                if (cm == null || cm.relics() == null || cm.relics().get(id) == null) {
                    return; // 该圣物已从配置下架，这一格是占位灰玻璃
                }
                String result = cm.pickRelic(player.getUniqueId(), id);
                player.closeInventory();
                if (result != null) {
                    player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.3f);
                    Msg.prefix(player, result);
                }
                return;
            }
        }
        if (rawSlot == 22) {
            player.closeInventory();
        }
    }

    private static ItemStack panel(ForgingMasterPlugin plugin, Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Msg.mm(name).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
        for (String l : lore) {
            lines.add(Msg.mm(l).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        }
        meta.lore(lines);
        item.setItemMeta(meta);
        return item;
    }
}
