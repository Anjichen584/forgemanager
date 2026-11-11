package com.forgemaster.enchant;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * 原创附魔的物品读/写（v2.23.2）。
 *
 * <h2>两个方向</h2>
 * <ul>
 *   <li><b>装备 → 附魔列表</b>：{@link #of(ItemStack)} 读 {@code Keys.CUSTOM_ENCH}，
 *       经 {@link EnchantEffects#parse} 解析成 {@code Entry}（认不出的 id 丢弃、等级夹上限）。</li>
 *   <li><b>附魔列表 → 装备</b>：{@link #write(ItemStack, List)} 写回 PDC 并重画 Lore 区块。</li>
 * </ul>
 *
 * <h2>Lore 分块约定</h2>
 * 原创附魔的 Lore 统一以 {@code ✦ 原创附魔} 开头，整块重画。
 * 这样和原版附魔（由 MC 自己渲染在名字下方）、词条区块（{@code ◆ 词条}）互不干扰 ——
 * 各有各的前缀，谁也不会洗掉谁。
 */
public final class EnchantItem {

    /** 原创附魔 Lore 区块的固定前缀（纯文本，定位用） */
    public static final String BLOCK_PREFIX = "✦ 原创附魔";

    private EnchantItem() {
    }

    // ------------------------------------------------------------ 读

    /** 读物品上的原创附魔（锻造品、附魔书都适用） */
    public static List<EnchantEffects.Entry> of(ForgingMasterPlugin plugin, ItemStack item) {
        if (plugin == null || plugin.enchants() == null || item == null || item.getType().isAir()) {
            return List.of();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return List.of();
        }
        String raw = meta.getPersistentDataContainer()
                .get(Keys.CUSTOM_ENCH, PersistentDataType.STRING);
        return EnchantEffects.parse(plugin.enchants(), raw);
    }

    /** 这本书是不是本插件产出的原创附魔书 */
    public static boolean isBook(ItemStack item) {
        if (item == null || item.getType() != Material.ENCHANTED_BOOK) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(Keys.ENCH_BOOK, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------ 写

    /**
     * 把附魔列表写回物品，并重画 Lore 区块。
     *
     * @return true = 确实有改动
     */
    public static boolean write(ForgingMasterPlugin plugin, ItemStack item,
                                List<EnchantEffects.Entry> entries) {
        if (plugin == null || item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        String encoded = EnchantEffects.encode(entries);
        String before = meta.getPersistentDataContainer()
                .get(Keys.CUSTOM_ENCH, PersistentDataType.STRING);
        boolean changed = encoded == null
                ? before != null
                : !encoded.equals(before);
        if (encoded == null) {
            meta.getPersistentDataContainer().remove(Keys.CUSTOM_ENCH);
        } else {
            meta.getPersistentDataContainer().set(Keys.CUSTOM_ENCH, PersistentDataType.STRING, encoded);
        }
        redrawLore(meta, entries, EnchantEffects.Caps.fromConfig(plugin.getConfig()));
        item.setItemMeta(meta);
        return changed;
    }

    /**
     * 重画 Lore 的原创附魔区块。
     *
     * <p>先删掉旧区块（按 {@link #BLOCK_PREFIX} 前缀逐行扫），再整体插回。
     * 「先删后插」而不是逐行替换，是因为等级变化可能让行数变少
     * （比如把一条附魔彻底移除），逐行替换会留下孤儿行。</p>
     */
    public static void redrawLore(ItemMeta meta, List<EnchantEffects.Entry> entries) {
        redrawLore(meta, entries, EnchantEffects.Caps.defaults());
    }

    /**
     * 重画 Lore 的原创附魔区块 + 同步气血修饰符。
     *
     * <p>{@code caps} 必须由调用方从 config 取，不在这里读 —— 静态方法读不到 plugin，
     * 而写死 {@code Caps.defaults()} 就会变成「配置改了但附魔还在用旧上限」。</p>
     */
    public static void redrawLore(ItemMeta meta, List<EnchantEffects.Entry> entries,
                                  EnchantEffects.Caps caps) {
        if (meta == null) {
            return;
        }
        List<Component> lore = meta.lore() == null
                ? new ArrayList<>() : new ArrayList<>(meta.lore());

        // 找到区块起点，把它和它后面连续的缩进行一起删掉
        int start = -1;
        for (int i = 0; i < lore.size(); i++) {
            if (Msg.plain(lore.get(i)).startsWith(BLOCK_PREFIX)) {
                start = i;
                break;
            }
        }
        if (start >= 0) {
            int end = start + 1;
            while (end < lore.size()) {
                String p = Msg.plain(lore.get(end));
                // 区块内的行都以两个空格或「· 」缩进开头
                if (p.startsWith("  ") || p.startsWith("· ")) {
                    end++;
                } else {
                    break;
                }
            }
            lore.subList(start, end).clear();
        }

        if (entries == null || entries.isEmpty()) {
            meta.lore(lore);
            applyHealthModifier(meta, null, caps);
            return;
        }

        List<Component> block = new ArrayList<>();
        block.add(line("<gradient:#a78bfa:#4dc3ff>" + BLOCK_PREFIX + "</gradient>"));
        for (EnchantEffects.Entry e : entries) {
            block.add(line("<gray>  · <white>" + e.def().display() + " "
                    + roman(e.level()) + "</white> <dark_gray>(" + e.def().valueText(e.level())
                    + ")</dark_gray>"));
        }
        lore.addAll(block);
        meta.lore(lore);
        applyHealthModifier(meta, entries, caps);
    }

    /**
     * 韧骨（气血加成）走**物品自身的属性修饰符**，不是给玩家挂 modifier。
     *
     * <p>为什么这样更好：物品属性修饰符由 MC 在穿/脱时自动结算，
     * 插件不需要监听「玩家换了护甲」「下线再上线」「死亡重生」这些时机，
     * 也就不会出现「脱了装备血条不回缩」这类经典 bug。</p>
     *
     * <p>修饰符 key 固定（{@link #HEALTH_MODIFIER_KEY}），每次先删再加 ——
     * 重复上书只会替换数值，不会叠出一堆同名 modifier 把气血叠到天上。</p>
     */
    public static final String HEALTH_MODIFIER_KEY = "forgemaster:ench_fortitude";

    private static void applyHealthModifier(ItemMeta meta, List<EnchantEffects.Entry> entries,
                                            EnchantEffects.Caps caps) {
        org.bukkit.attribute.Attribute attr = org.bukkit.attribute.Attribute.MAX_HEALTH;
        // 先清掉自己上一次加的
        var existing = meta.getAttributeModifiers(attr);
        if (existing != null) {
            for (var m : new ArrayList<>(existing)) {
                if (m.getKey().getKey().startsWith("ench_fortitude")) {
                    meta.removeAttributeModifier(attr, m);
                }
            }
        }
        if (entries == null || entries.isEmpty()) {
            return;
        }
        double add = 0;
        for (EnchantEffects.Entry e : entries) {
            if (e.def().kind() == CustomEnchant.Kind.HEALTH_ADD) {
                add += e.def().at(e.level());
            }
        }
        if (add <= 0) {
            return;
        }
        // 与 combat.limits.max-bonus-health 同一口径：附魔自己再加一道更紧的夹子
        double cap = caps == null ? 0 : caps.maxHealth();
        add = Math.min(add, Math.max(0, cap));
        meta.addAttributeModifier(attr, new org.bukkit.attribute.AttributeModifier(
                org.bukkit.NamespacedKey.fromString(HEALTH_MODIFIER_KEY),
                add,
                org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER,
                org.bukkit.inventory.EquipmentSlotGroup.ARMOR));
    }

    // ------------------------------------------------------------ 附魔书工厂

    /**
     * 造一本原创附魔书。
     *
     * <p>用 {@code ENCHANTED_BOOK} 而不是自定义材质：玩家一眼认得这是「附魔书」，
     * 放进原版铁砧/书架体系也不会显得突兀。真实内容全在 PDC 里，改名/改 Lore 都伪造不出来
     * （伪造品没有 {@code Keys.ENCH_BOOK} 与合法的 {@code id:level}）。</p>
     */
    public static ItemStack book(ForgingMasterPlugin plugin, EnchantEffects.Entry entry) {
        ItemStack item = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null || entry == null) {
            return item;
        }
        meta.displayName(Msg.mm("<gradient:#a78bfa:#4dc3ff><bold>"
                + entry.def().display() + " " + roman(entry.level()) + "</bold></gradient>")
                .decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(Keys.ENCH_BOOK, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(Keys.CUSTOM_ENCH, PersistentDataType.STRING,
                entry.def().id() + ":" + entry.level());
        // 直接在这份 meta 上画 Lore，最后只 setItemMeta 一次
        List<EnchantEffects.Entry> entries = new ArrayList<>();
        entries.add(entry);
        redrawLore(meta, entries);
        item.setItemMeta(meta);
        return item;
    }

    /** 书上的附魔（一本书理论上只有一条，但读法统一） */
    public static EnchantEffects.Entry bookEntry(ForgingMasterPlugin plugin, ItemStack book) {
        List<EnchantEffects.Entry> list = of(plugin, book);
        return list.isEmpty() ? null : list.get(0);
    }

    // ------------------------------------------------------------ 工具

    public static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> String.valueOf(level);
        };
    }

    private static Component line(String mini) {
        return Msg.mm(mini).decoration(TextDecoration.ITALIC, false);
    }
}