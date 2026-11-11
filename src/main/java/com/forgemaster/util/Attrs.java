package com.forgemaster.util;

import com.forgemaster.material.ForgeMaterial;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 基础属性 modifier 的键名，以及「原版基数」的补回。
 *
 * <p>2.10 之前四件装备的护甲全用同一个键 {@code fm_armor}。原版的属性系统是
 * 按<b>键</b>去重的：身上同时穿两件都挂着 {@code fm_armor} 的装备时，
 * 后穿上的那件会顶掉先穿上的；更糟的是脱掉其中一件时，
 * 引擎把这个键整个移除，于是<b>另一件的护甲也一起没了</b>——护甲值直接清空。</p>
 *
 * <p>所以键名必须带上部位。武器只有主手一件，不需要区分，沿用原键名。</p>
 */
public final class Attrs {

    /** 插件写入的属性键统一前缀，用于识别「这件东西被动过」 */
    private static final String PFX = "fm_";

    private Attrs() {
    }

    /** 护甲 modifier 键名，例如 {@code fm_armor_chestplate} */
    public static String armorKey(ForgeMaterial.Slot slot) {
        return slot == null ? "fm_armor" : "fm_armor_" + slot.name().toLowerCase(Locale.ROOT);
    }

    /** 韧性 modifier 键名，例如 {@code fm_toughness_helmet} */
    public static String toughKey(ForgeMaterial.Slot slot) {
        return slot == null ? "fm_toughness" : "fm_toughness_" + slot.name().toLowerCase(Locale.ROOT);
    }

    /**
     * 四大基础属性键的完整集合（含老的无部位键与四个部位后缀键）。
     *
     * <p><b>为什么是白名单而不是前缀判断（v2.23.1 修 P0）</b>：词条键的形如
     * {@code fm_armor_plate_chestplate}，与基础键 {@code fm_armor_chestplate} 在字符串层面
     * 只差中间一段。用 {@code startsWith("fm_armor_")} 判断会把 {@code armor_plate} /
     * {@code tough_plate} 这两条词条一并当成基础键——崩刃时被摘掉、修好也只重建基础键，
     * 词条属性因此永久丢失（Lore 还在显示）。凡新增以 armor / toughness 开头的词条 id
     * 都会立刻复现同一类缺陷，所以这里必须精确枚举。</p>
     */
    private static final java.util.Set<String> BASE_KEYS = java.util.Set.of(
            "fm_atk", "fm_speed", "fm_armor", "fm_toughness",
            "fm_armor_helmet", "fm_armor_chestplate", "fm_armor_leggings", "fm_armor_boots",
            "fm_toughness_helmet", "fm_toughness_chestplate", "fm_toughness_leggings",
            "fm_toughness_boots");

    /** 这个键是不是「基础属性」那一类（精确匹配，绝不吞词条键）。 */
    public static boolean isBaseKey(String key) {
        if (key == null) {
            return false;
        }
        return BASE_KEYS.contains(key.toLowerCase(Locale.ROOT));
    }

    /**
     * 按「语义」把属性组件整理成正确形态。
     *
     * <p>原版属性（铁剑 {@code base_attack_damage=+5}、铁胸甲 {@code armor.chestplate=+6}）
     * 只存在于组件的<b>默认值</b>里，{@link ItemMeta} 层看不到。只要用过一次
     * {@code meta.addAttributeModifier} 再 {@code setItemMeta}，组件就被整体替换成 meta
     * 里那几条，原版条目随之消失。所以每次装回 meta 之后都要按下面的语义收拾一次：</p>
     *
     * <ul>
     *   <li><b>武器</b>（{@code bonus-attack} / {@code bonus-speed} 是「加成」）——
     *       把原版 {@code attack_damage +5}、{@code attack_speed -2.4} 补回去。
     *       不补的话剑芯 +1.12 会变成总伤（比木剑还低），攻速从 1.67 飙到 4.07。</li>
     *   <li><b>装备</b>（{@code armor.tiers} 是整套总量，拆到单件就是绝对值）——
     *       把原版 {@code armor} / {@code armor_toughness} 条目<b>去掉</b>，
     *       只留 {@code fm_armor_<部位>}，否则护甲值会翻倍（铁胸 4.8+6=10.8）。</li>
     * </ul>
     *
     * <p>物品上没有 {@code fm_*} 条目时直接返回，不会污染 GUI 图标。幂等。</p>
     */
    public static boolean normalize(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        var current = item.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (current == null || current.modifiers().isEmpty()) {
            return false;
        }

        boolean touched = false;
        for (var e : current.modifiers()) {
            if (isOurs(e)) {
                touched = true;
                break;
            }
        }
        if (!touched) {
            return false;
        }

        List<ItemAttributeModifiers.Entry> merged = new ArrayList<>(current.modifiers());
        boolean armor = item.getPersistentDataContainer()
                .has(Keys.ARMOR_SLOT, org.bukkit.persistence.PersistentDataType.STRING);

        if (armor) {
            // 装备：绝对值语义，原版条目必须让位
            merged.removeIf(e -> !isOurs(e)
                    && (e.attribute() == org.bukkit.attribute.Attribute.ARMOR
                    || e.attribute() == org.bukkit.attribute.Attribute.ARMOR_TOUGHNESS));
        } else {
            // 武器：加成语义，原版基线必须保留
            var vanilla = new ItemStack(item.getType())
                    .getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
            if (vanilla != null) {
                for (var v : vanilla.modifiers()) {
                    if (v.attribute() != org.bukkit.attribute.Attribute.ATTACK_DAMAGE
                            && v.attribute() != org.bukkit.attribute.Attribute.ATTACK_SPEED) {
                        continue;
                    }
                    if (!containsSame(merged, v)) {
                        merged.add(v);
                    }
                }
            }
        }

        if (merged.size() == current.modifiers().size()) {
            return false;
        }

        var builder = ItemAttributeModifiers.itemAttributes();
        for (var e : merged) {
            builder.addModifier(e.attribute(), e.modifier(), e.getGroup());
        }
        item.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, builder.build());
        return true;
    }

    /** 这条 modifier 是不是插件自己写的（键名 {@code fm_*}） */
    private static boolean isOurs(ItemAttributeModifiers.Entry e) {
        var key = e.modifier().getKey();
        return key != null && key.getKey().toLowerCase(Locale.ROOT).startsWith(PFX);
    }

    /** 列表里是否已有「同属性 + 同键名」的条目 */
    private static boolean containsSame(List<ItemAttributeModifiers.Entry> list,
                                        ItemAttributeModifiers.Entry probe) {
        var probeKey = probe.modifier().getKey();
        if (probeKey == null) {
            return true;
        }
        for (var e : list) {
            var k = e.modifier().getKey();
            if (k != null && probeKey.getKey().equalsIgnoreCase(k.getKey())
                    && e.attribute() == probe.attribute()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把物品类型在某几个属性上的<b>原版条目</b>先写进 meta，再让调用方追加 {@code fm_*}。
     *
     * <p>用在「加成」语义的属性上：武器的 {@code attack_damage} 原版是 +5、
     * {@code attack_speed} 原版是 -2.4。这两条只存在于组件默认值里，
     * meta 一旦整体覆盖就会丢——剑芯的 +1.12 会变成总伤，攻速更会从 1.67 飙到 4.07。
     * 先把原版条目搬进 meta，之后任何一次 {@code setItemMeta} 都不会再把它洗掉。</p>
     *
     * <p>护甲的 {@code armor} 不走这里：配置里的 {@code armor.tiers} 是整套总量，
     * 拆到单件就是<b>绝对值</b>，语义上本来就该顶掉原版。</p>
     */
    public static void keepVanilla(ItemStack item, ItemMeta meta,
                                   org.bukkit.attribute.Attribute... attributes) {
        if (item == null || meta == null || attributes.length == 0) {
            return;
        }
        var vanilla = new ItemStack(item.getType())
                .getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (vanilla == null || vanilla.modifiers().isEmpty()) {
            return;
        }
        for (var e : vanilla.modifiers()) {
            boolean wanted = false;
            for (var a : attributes) {
                if (a == e.attribute()) {
                    wanted = true;
                    break;
                }
            }
            if (!wanted) {
                continue;
            }
            String key = e.modifier().getKey() == null ? null : e.modifier().getKey().getKey();
            if (key == null) {
                continue;
            }
            meta.addAttributeModifier(e.attribute(), e.modifier());
        }
    }

    /**
     * 装回 meta 并按语义整理属性组件。
     *
     * <p>等价于 {@code item.setItemMeta(meta)} + {@link #normalize(ItemStack)}。
     * 处理锻造成品 / 强化 / 重铸 / 宝石 / 剑意的路径一律走这个，别直接用 setItemMeta。</p>
     */
    public static void apply(ItemStack item, ItemMeta meta) {
        if (item == null) {
            return;
        }
        item.setItemMeta(meta);
        normalize(item);
    }
}