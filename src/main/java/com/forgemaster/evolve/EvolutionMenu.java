package com.forgemaster.evolve;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.evolve.EvolutionService.Snapshot;
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
 * 蜕变确认界面：ForgeTax 式明牌——成功率、消耗、失败后果、收益全部摆在桌面上。
 *
 * <p>27 格：中间是装备本体，左右各一块信息牌（成功率 / 消耗 / 失败 / 收益），
 * 底部中央确认蜕变、左下取消。确认按钮执行时校验主手仍是这件锻造品。</p>
 */
public final class EvolutionMenu {

    /** 确认按钮槽位 */
    public static final int CONFIRM_SLOT = 22;
    /** 取消按钮槽位 */
    public static final int CANCEL_SLOT = 18;
    /** 装备展示槽位 */
    public static final int DISPLAY_SLOT = 13;

    private EvolutionMenu() {
    }

    /**
     * 给玩家打开蜕变界面。
     *
     * @return 是否成功打开（没有待蜕变门槛 / 不是锻造品时返回 false，不发提示）
     */
    public static boolean open(ForgingMasterPlugin plugin, Player player) {
        EvolutionService evo = plugin.evolutions();
        if (evo == null || !evo.enabled()) {
            return false;
        }
        ItemStack main = player.getInventory().getItemInMainHand();
        Snapshot snap = evo.snapshotOf(main);
        if (snap == null) {
            return false;
        }
        EvolutionStage stage = snap.stage();
        EvolutionHolder holder = new EvolutionHolder(player.getUniqueId(), stage.index());
        Inventory inv = Bukkit.createInventory(holder, 27,
                Msg.mm("<color:" + stage.color() + ">〔锻阶〕蜕变 · " + stage.title() + "</color>"));
        holder.inventory(inv);

        inv.setItem(DISPLAY_SLOT, main.clone());

        boolean gateOk = snap.strengthenLevel() >= stage.level();
        inv.setItem(10, panel(Material.CLOCK, "<color:" + stage.color() + ">成功率（明牌）</color>", List.of(
                "<white>" + Msg.fmt(snap.chance() * 100, 0) + "%</white> <gray>基础 "
                        + Msg.fmt(stage.successBase() * 100, 0) + "% + 品质加成</gray>",
                "<gray>炉火保底：</gray><white>" + snap.pityNow() + "/" + snap.pityNeed()
                        + "</white><gray>（攒满必成）</gray>",
                // 门槛 = 这一阶的强化上限：进度直接写成 +当前/+上限，没满就别让玩家点确认才发现白点
                gateOk
                        ? "<gray>强化进度：</gray><white>+" + Math.min(snap.strengthenLevel(), stage.level())
                        + "/+" + stage.level() + "</white><gray> ✓ 已满</gray>"
                        : "<red>强化还没满：+" + snap.strengthenLevel() + "/+" + stage.level()
                        + "（还差 " + (stage.level() - snap.strengthenLevel()) + " 级）</red>")));
        inv.setItem(11, panel(Material.CHEST, "<yellow>消耗</yellow>", List.of(
                "<white>" + snap.materialAmount() + "</white> <gray>个 "
                        + (snap.material() == null ? "材料" : com.forgemaster.material.MaterialGrades.pretty(snap.material()))
                        + "</gray>",
                "<white>" + snap.coins() + "</white> <gray>剑币</gray>")));
        inv.setItem(15, panel(Material.TNT, "<red>失败后果</red>", List.of(
                "<gray>不掉锻阶，强化级掉</gray><white> 1.."
                        + Math.max(1, stage.failDrop()) + "</white>",
                "<gray>稳胚符可托住等级</gray>")));
        inv.setItem(16, panel(Material.NETHER_STAR, "<#ffd166>收益</#ffd166>", List.of(
                "<white>属性乘区 ×" + Msg.fmt(
                        plugin.evolutions().registry().cumulativeMult(stage.index()), 2) + "</white>",
                stage.index() >= plugin.evolutions().registry().glintStage()
                        ? "<#9f7aea>蜕变后开始发光</#9f7aea>"
                        : "<gray>下一阶发光</gray>")));

        inv.setItem(CONFIRM_SLOT, panel(Material.ANVIL,
                "<color:" + stage.color() + "><bold>✦ 确认蜕变 ✦</bold></color>",
                gateOk
                        ? List.of("<gray>掷一次骰，命运写在铁砧上</gray>")
                        : List.of("<gray>先把这一阶锻满 <white>+" + stage.level() + "</white></gray>",
                        "<dark_gray>强化没满，确认也掷不了</dark_gray>")));
        inv.setItem(CANCEL_SLOT, panel(Material.BARRIER, "<red>取消</red>", List.of()));

        player.openInventory(inv);
        return true;
    }

    /** 点击路由：由 ForgeListener 转发 */
    public static void click(ForgingMasterPlugin plugin, Player player, int rawSlot) {
        if (rawSlot == CANCEL_SLOT) {
            player.closeInventory();
            return;
        }
        if (rawSlot != CONFIRM_SLOT) {
            return;
        }
        player.closeInventory();
        // 确认时重新校验主手：界面开着的时候换剑 / 吞剑都不行
        ItemStack main = player.getInventory().getItemInMainHand();
        EvolutionService evo = plugin.evolutions();
        if (evo == null || main == null || main.getType().isAir() || !plugin.items().isForged(main)) {
            Msg.prefix(player, "<red>主手得拿着那件要蜕变的锻造品。");
            return;
        }
        for (String piece : evo.evolveFromGui(player, main).split("<newline>")) {
            if (!piece.isBlank()) {
                Msg.prefix(player, piece);
            }
        }
    }

    private static ItemStack panel(Material mat, String name, List<String> lore) {
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
