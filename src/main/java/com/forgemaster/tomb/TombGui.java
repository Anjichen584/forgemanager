package com.forgemaster.tomb;

import com.forgemaster.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 剑冢全服列表界面。每格是一把埋下的剑快照，左键挑战，主人左键取回，右键查看。
 */
public final class TombGui implements InventoryHolder {

    public static final int SIZE = 54;
    public static final int CAPACITY = 45;

    private final TombManager manager;
    private final Map<Integer, String> slotIds = new HashMap<>();
    private Inventory inventory;

    public TombGui(TombManager manager) {
        this.manager = manager;
    }

    /** 构建界面（每次打开都重建，保证列表最新） */
    public TombGui build() {
        inventory = Bukkit.createInventory(this, SIZE, Msg.mm("<dark_gray>剑冢 <dark_gray>| <#4e7fff>"
                + manager.all().size() + " 把剑"));
        slotIds.clear();
        int slot = 0;
        for (TombManager.Tomb t : manager.all()) {
            if (slot >= CAPACITY) {
                break;
            }
            inventory.setItem(slot, render(t));
            slotIds.put(slot, t.id);
            slot++;
        }
        ItemStack info = new ItemStack(Material.BARRIER);
        ItemMeta meta = info.getItemMeta();
        if (meta != null) {
            meta.displayName(Msg.mm("<red>关闭").decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(Msg.mm("<gray>左键：召唤残影挑战 · 主人左键：取回").decoration(TextDecoration.ITALIC, false));
            lore.add(Msg.mm("<gray>右键：查看详情").decoration(TextDecoration.ITALIC, false));
            lore.add(Msg.mm("<gray>埋剑：<white>/zhujian tomb bury</white>").decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            info.setItemMeta(meta);
        }
        inventory.setItem(SIZE - 5, info);
        return this;
    }

    private ItemStack render(TombManager.Tomb t) {
        ItemStack it = t.snapshot.clone();
        ItemMeta meta = it.getItemMeta();
        if (meta == null) {
            return it;
        }
        List<Component> lore = new ArrayList<>();
        lore.add(Msg.mm("<dark_gray>—— 剑冢 · 编号 <white>" + t.id + "</white> ——").decoration(TextDecoration.ITALIC, false));
        lore.add(Msg.mm("<gray>剑名: <#ffd166>" + t.swordName).decoration(TextDecoration.ITALIC, false));
        lore.add(Msg.mm("<gray>主人: <white>" + t.ownerName).decoration(TextDecoration.ITALIC, false));
        lore.add(Msg.mm("<gray>剑胚: <white>" + t.materialId + " <dark_gray>· <gray>品质 <white>" + t.quality
                + " <dark_gray>· <gray>评分 <white>" + Msg.fmt(t.score, 1)).decoration(TextDecoration.ITALIC, false));
        lore.add(Msg.mm("<gray>埋入: <white>" + new SimpleDateFormat("MM-dd HH:mm").format(t.buriedAt))
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Msg.mm("<gray>挑战 <white>" + t.challenges + "</white> 次 · 斩影 <white>" + t.wins + "</white> 次")
                .decoration(TextDecoration.ITALIC, false));
        String remnant = manager.challenges().previewLine(t);
        if (!remnant.isBlank()) {
            lore.add(Msg.mm(remnant).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Msg.mm("<dark_gray>左键: 召唤残影 <gray>（自己的剑 = 取回） <dark_gray>· 右键: 详情")
                .decoration(TextDecoration.ITALIC, false));
        if (t.broken) {
            lore.add(Msg.mm("<gray>状态: <red>碎剑留档</red> <dark_gray>· 碎在 +" + t.brokenAt
                    + " · 不能取回</dark_gray>").decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    /** 点击位置对应的剑编号（空位返回 null） */
    public String idAt(int slot) {
        return slotIds.get(slot);
    }

    public TombManager manager() {
        return manager;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}