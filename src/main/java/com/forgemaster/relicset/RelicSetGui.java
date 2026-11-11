package com.forgemaster.relicset;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 嵌套 record 的短名导入（套装 / 档位） */
import com.forgemaster.relicset.RelicSetService.RelicSet;
import com.forgemaster.relicset.RelicSetService.RelicTier;

/**
 * 套装矩阵预览面板（v2.23 P1-5）：只读展示，不做点击穿戴。
 *
 * <p><b>列表页</b>（{@link #open}）：全部套装（内置五套 + 配置覆盖 / 追加的）一格一套，
 * 显示套装名 / 所需件数档位 / 已收集件数 / 各档加成与主动技说明；
 * 点某套进<b>详情页</b>（{@link #openDetail}）：四个部件位各自的判定材料 id
 * 与玩家当前收集状态（绿色 = 已穿、灰色 = 缺），外加档位达标一览与主动技冷却。
 * 界面风格（MiniMessage 标题、panel 条目、槽位常量、点击路由）对齐 gui 包的
 * {@code ContractMenu}；点击处理由包内的 {@link RelicSetListener} 转发到
 * {@link #click}。</p>
 *
 * <p><b>接线</b>：入口 {@link #open(ForgingMasterPlugin, Player)} 是 public static，
 * {@code /zhujian relicset} 的命令分支（ForgingCommand#relicSet）加一行
 * {@code RelicSetGui.open(plugin, p)} 即可（命令文件不在本包所有权内，交由接线方处理）。</p>
 */
public final class RelicSetGui {

    /** 界面总格子数（6 行，与 ForgeMenu 一致的 54 格规格） */
    public static final int SIZE = 54;

    /** 列表页套装条目的 21 个槽位（第 2-4 行的第 2-8 列） */
    public static final int[] LIST_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };

    /** 列表页顶部的汇总说明条目 */
    public static final int INFO_SLOT = 4;
    /** 详情页的档位汇总条目 */
    public static final int TIER_SLOT = 4;
    /** 详情页的四个部件位条目（靴子 / 护腿 / 胸甲 / 头盔，顺序与 getArmorContents 一致） */
    public static final int[] PIECE_SLOTS = {10, 12, 14, 16};
    /** 详情页返回列表按钮 */
    public static final int BACK_SLOT = 48;
    /** 两页共用的关闭按钮 */
    public static final int CLOSE_SLOT = 49;

    private RelicSetGui() {
    }

    // ------------------------------------------------------------ 纯函数（自检用）

    /** 列表页标题（纯函数）：套装总数会拼进标题 */
    public static String listTitle(int size) {
        return "<gradient:#b06bff:#4dc3ff>〔套装〕套装矩阵 · " + size + " 套</gradient>";
    }

    /** 详情页标题（纯函数）：套装名与当前收集件数会拼进标题 */
    public static String detailTitle(String setName, int count) {
        return "<gradient:#b06bff:#4dc3ff>〔套装〕" + setName + " · " + count + "/4 件</gradient>";
    }

    // ------------------------------------------------------------ 列表页

    /** 打开套装矩阵列表页（{@code /zhujian relicset} 的 GUI 入口） */
    public static void open(ForgingMasterPlugin plugin, Player player) {
        RelicSetService sets = plugin.relicSets();
        if (sets == null) {
            return;
        }
        List<RelicSet> all = sets.all();
        RelicSetHolder holder = new RelicSetHolder(player.getUniqueId(), RelicSetHolder.Mode.LIST, null);
        Inventory inv = Bukkit.createInventory(holder, SIZE, Msg.mm(listTitle(all.size())));
        holder.inventory(inv);

        inv.setItem(INFO_SLOT, panel(plugin, Material.BOOK,
                "<#b06bff><bold>套装效果 2.0</bold></#b06bff>",
                List.of("<gray>按「同材料件数」判定，同 id 才算同套。</gray>",
                        "<gray>共 </gray><white>" + all.size() + "</white><gray> 套（内置 + 配置）。</gray>",
                        "<gray>主动技用法: </gray><white>主手同套武器，潜行 + 左键。</white>")));
        int i = 0;
        for (RelicSet set : all) {
            if (i >= LIST_SLOTS.length) {
                break; // 21 格放不下就先截断（现役套装数远不到）
            }
            inv.setItem(LIST_SLOTS[i++], setIcon(plugin, player, sets, set));
        }
        inv.setItem(CLOSE_SLOT, panel(plugin, Material.BARRIER, "<red>关闭</red>", List.of()));
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.6f, 1.2f);
    }

    /** 列表页的一个套装条目：名称 / 判定材料 / 已收集 / 各档加成与主动技 */
    private static ItemStack setIcon(ForgingMasterPlugin plugin, Player player,
                                     RelicSetService sets, RelicSet set) {
        ForgeMaterial mat = plugin.materials() == null ? null : plugin.materials().get(set.id());
        Material icon = mat != null ? mat.icon() : Material.IRON_CHESTPLATE;
        int count = sets.countPieces(player, set.id());
        int tier = RelicSetService.tierFor(count, set.tiers());

        List<String> lore = new ArrayList<>();
        lore.add("<gray>判定材料: </gray><white>" + set.id()
                + "</white> <dark_gray>(armor-" + set.id() + "-*)</dark_gray>");
        lore.add("<gray>已收集: </gray><white>" + count + "/4</white>"
                + (tier > 0 ? " <green>✔ " + tier + " 件档生效</green>" : " <yellow>未达档</yellow>"));
        lore.add("");
        for (Map.Entry<Integer, RelicTier> e : set.tiers().entrySet()) {
            StringBuilder line = new StringBuilder(count >= e.getKey()
                    ? "<green>✔</green>" : "<dark_gray>□</dark_gray>");
            line.append(" <white>").append(e.getKey()).append(" 件:</white> ");
            RelicTier t = e.getValue();
            if (t.armorBonus() > 0) {
                line.append("<aqua>护甲 +").append(Msg.fmt(t.armorBonus(), 1)).append("</aqua>");
                if (t.activeSkill() != null) {
                    line.append(" ");
                }
            }
            if (t.activeSkill() != null) {
                line.append("<#b06bff>主动技 ").append(RelicSetService.skillDisplay(t.activeSkill()))
                        .append("</#b06bff>");
            }
            lore.add(line.toString());
        }
        lore.add("");
        lore.add("<dark_gray>点击查看部件详情</dark_gray>");
        String name = (tier > 0 ? "<#ffd166><bold>" : "<gray><bold>")
                + set.name() + "</bold>" + (tier > 0 ? "</#ffd166>" : "</gray>");
        return panel(plugin, icon, name, lore);
    }

    // ------------------------------------------------------------ 详情页

    /** 打开某个套装的部件详情页（列表页点套装条目进入） */
    public static void openDetail(ForgingMasterPlugin plugin, Player player, String setId) {
        RelicSetService sets = plugin.relicSets();
        if (sets == null) {
            return;
        }
        RelicSet set = sets.set(setId);
        if (set == null) {
            open(plugin, player); // 套装可能在两次点击之间被配置删掉，兜底回列表
            return;
        }
        int count = sets.countPieces(player, set.id());
        RelicSetHolder holder = new RelicSetHolder(player.getUniqueId(), RelicSetHolder.Mode.DETAIL, set.id());
        Inventory inv = Bukkit.createInventory(holder, SIZE, Msg.mm(detailTitle(set.name(), count)));
        holder.inventory(inv);

        inv.setItem(TIER_SLOT, tierIcon(plugin, player, sets, set));

        // 部件位状态：getArmorContents 顺序 = 靴子 / 护腿 / 胸甲 / 头盔
        ItemStack[] armor = player.getInventory().getArmorContents();
        String[] labels = {"靴子", "护腿", "胸甲", "头盔"};
        String[] suffixes = {"boots", "leggings", "chestplate", "helmet"};
        for (int i = 0; i < PIECE_SLOTS.length; i++) {
            boolean worn = set.id().equalsIgnoreCase(sets.baseIdOf(i < armor.length ? armor[i] : null));
            inv.setItem(PIECE_SLOTS[i], panel(plugin,
                    worn ? Material.LIME_DYE : Material.GRAY_DYE,
                    (worn ? "<green>✔ " : "<gray>✗ ") + labels[i]
                            + (worn ? " 已穿戴</green>" : " 未穿戴</gray>"),
                    List.of("<gray>判定: </gray><white>armor-" + set.id() + "-"
                            + suffixes[i] + "</white>",
                            worn ? "<dark_gray>计入件数</dark_gray>"
                                    : "<dark_gray>穿上同材料的这个部位即计入</dark_gray>")));
        }
        inv.setItem(BACK_SLOT, panel(plugin, Material.ARROW, "<yellow>返回列表</yellow>", List.of()));
        inv.setItem(CLOSE_SLOT, panel(plugin, Material.BARRIER, "<red>关闭</red>", List.of()));
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.1f);
    }

    /** 详情页顶部的档位汇总条目：各档达标状态 + 主动技冷却 */
    private static ItemStack tierIcon(ForgingMasterPlugin plugin, Player player,
                                      RelicSetService sets, RelicSet set) {
        ForgeMaterial mat = plugin.materials() == null ? null : plugin.materials().get(set.id());
        Material icon = mat != null ? mat.icon() : Material.IRON_CHESTPLATE;
        int count = sets.countPieces(player, set.id());
        int tier = RelicSetService.tierFor(count, set.tiers());

        List<String> lore = new ArrayList<>();
        lore.add("<gray>判定材料: </gray><white>" + set.id() + "</white>"
                + (mat != null ? " <dark_gray>(" + mat.display() + ")</dark_gray>" : ""));
        lore.add("<gray>已收集: </gray><white>" + count + "/4</white>");
        lore.add("");
        for (Map.Entry<Integer, RelicTier> e : set.tiers().entrySet()) {
            boolean reached = count >= e.getKey();
            StringBuilder line = new StringBuilder(reached ? "<green>✔</green>" : "<dark_gray>□</dark_gray>");
            line.append(" <white>").append(e.getKey()).append(" 件档:</white> ");
            RelicTier t = e.getValue();
            if (t.armorBonus() > 0) {
                line.append("<aqua>护甲 +").append(Msg.fmt(t.armorBonus(), 1)).append("</aqua>");
                if (t.activeSkill() != null) {
                    line.append(" ");
                }
            }
            if (t.activeSkill() != null) {
                line.append("<#b06bff>主动技 ").append(RelicSetService.skillDisplay(t.activeSkill()))
                        .append("</#b06bff>");
            }
            lore.add(line.toString());
        }
        if (tier > 0) {
            RelicTier cur = set.tiers().get(tier);
            if (cur.activeSkill() != null) {
                lore.add("");
                long remain = sets.remainingCooldown(player, set.id());
                lore.add(remain > 0
                        ? "<gray>主动技冷却: </gray><red>" + fmtSec(remain) + "</red>"
                        : "<green>主动技已就绪</green>");
            }
        }
        return panel(plugin, icon,
                (tier > 0 ? "<#ffd166><bold>" : "<gray><bold>") + set.name() + "</bold>"
                        + (tier > 0 ? "</#ffd166>" : "</gray>"),
                lore);
    }

    private static String fmtSec(long millis) {
        return String.format(Locale.ROOT, "%.1f", millis / 1000.0);
    }

    // ------------------------------------------------------------ 点击路由

    /**
     * 点击路由：由 {@link RelicSetListener} 的 InventoryClickEvent 处理器转发
     * （事件已在外层取消，这里只做页面跳转，全部只读）。
     */
    public static void click(ForgingMasterPlugin plugin, Player player, RelicSetHolder holder, int rawSlot) {
        if (holder.mode() == RelicSetHolder.Mode.LIST) {
            int idx = listIndex(rawSlot);
            List<RelicSet> all = plugin.relicSets().all();
            if (idx >= 0 && idx < all.size()) {
                openDetail(plugin, player, all.get(idx).id());
                return;
            }
            if (rawSlot == CLOSE_SLOT) {
                player.closeInventory();
            }
            return;
        }
        // 详情页
        if (rawSlot == BACK_SLOT) {
            open(plugin, player);
            return;
        }
        if (rawSlot == CLOSE_SLOT) {
            player.closeInventory();
        }
    }

    /** 槽位 → 列表页套装下标（不在套装槽位上返回 -1，纯函数） */
    private static int listIndex(int rawSlot) {
        for (int i = 0; i < LIST_SLOTS.length; i++) {
            if (LIST_SLOTS[i] == rawSlot) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------ 条目绘制

    /**
     * 通用条目（与 ContractMenu.panel 同一写法）：MiniMessage 渲染、去斜体。
     */
    private static ItemStack panel(ForgingMasterPlugin plugin, Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Msg.mm(name)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
        for (String l : lore) {
            lines.add(Msg.mm(l).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        }
        meta.lore(lines);
        item.setItemMeta(meta);
        return item;
    }
}
