package com.forgemaster.duel;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.quality.Quality;
import com.forgemaster.util.Keys;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 比的「跨档压档」：把高等级方的强化加成<strong>临时</strong>压到低等级方那一档。
 *
 * <p>实现方式：<b>不碰物品 NBT</b>，只在玩家属性上加两个
 * {@link AttributeModifier.Operation#ADD_NUMBER} 的负修正
 * （{@code ATTACK_DAMAGE} / {@code ATTACK_SPEED}），差额 = 基准属性 ×
 * （原等级的强化倍率 − 目标等级的强化倍率）。所以物品上的
 * {@code fm_atk} / {@code fm_speed} 原样不动，Lore 也不会变。</p>
 *
 * <p><b>清理</b>：修正用 {@link AttributeInstance#addTransientModifier} 挂，
 * 只存在于内存、不会写进玩家存档；另外 {@link #applied} 记一份
 * 「玩家 → 已应用修正」，退出 / 关服 / 对局结束都按它还原。
 * 兜底清理点见 {@link DuelTierListener} 与 {@code DuelManager#onQuit} /
 * {@code DuelManager#shutdown}。</p>
 */
public final class DuelBalance {

    /** 物品上基础属性的两条 modifier 键名（与 StrengthenService 里保持一致，只读） */
    private static final String ITEM_KEY_ATK = "fm_atk";
    private static final String ITEM_KEY_SPEED = "fm_speed";

    private final ForgingMasterPlugin plugin;
    private final NamespacedKey keyAtk;
    private final NamespacedKey keySpeed;

    /** 玩家 → 已应用的压档修正；清理时按这张表逐个还原 */
    private final Map<UUID, Applied> applied = new HashMap<>();

    public DuelBalance(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.keyAtk = new NamespacedKey(plugin, "duel_balance_atk");
        this.keySpeed = new NamespacedKey(plugin, "duel_balance_spd");
    }

    /**
     * 一条已应用的压档修正。
     *
     * @param attack 从攻击力里扣掉的数值（正数）
     * @param speed  从攻速里扣掉的数值（正数）
     */
    public record Applied(UUID player, int fromLevel, int toLevel, double attack, double speed) {
    }

    // ------------------------------------------------------------ 应用

    /**
     * 把玩家 {@code fromLevel} 的强化加成临时压到 {@code toLevel}。
     *
     * <p>幂等：同一个人重复调用会先还原上一次的修正再重算，不会叠加。</p>
     *
     * @return 实际应用的修正；不需要压（等级已经不高 / 读不到基准值）返回 null
     */
    public Applied apply(Player player, int fromLevel, int toLevel) {
        if (player == null) {
            return null;
        }
        // 先还原上一次，避免重复开局叠加两条负修正
        clear(player.getUniqueId());
        if (fromLevel <= toLevel) {
            return null;
        }
        double diff = plugin.strengthen().bonusRatio(fromLevel)
                - plugin.strengthen().bonusRatio(toLevel);
        if (diff <= 1e-6) {
            return null;
        }
        ItemStack main = player.getInventory().getItemInMainHand();
        double baseAtk = baseOf(main, Keys.BASE_ATK, ITEM_KEY_ATK, Attribute.ATTACK_DAMAGE, fromLevel);
        double baseSpeed = baseOf(main, Keys.BASE_SPEED, ITEM_KEY_SPEED, Attribute.ATTACK_SPEED, fromLevel);
        double cutAtk = round(baseAtk * diff);
        double cutSpeed = round(baseSpeed * diff);
        if (cutAtk < 1e-4 && cutSpeed < 1e-4) {
            // 例如手上拿的不是锻造品，压了也没意义
            return null;
        }
        setModifier(player, Attribute.ATTACK_DAMAGE, keyAtk, -cutAtk);
        setModifier(player, Attribute.ATTACK_SPEED, keySpeed, -cutSpeed);
        Applied record = new Applied(player.getUniqueId(), fromLevel, toLevel, cutAtk, cutSpeed);
        applied.put(record.player(), record);
        return record;
    }

    // ------------------------------------------------------------ 还原

    /** 还原某个玩家的临时修正（离线 / 没记录都安全） */
    public void clear(UUID uuid) {
        if (uuid == null) {
            return;
        }
        applied.remove(uuid);
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            strip(player);
        }
        // 玩家不在线也不用管：transient modifier 本来就不进存档
    }

    /** 全量还原（关服 / 插件停用兜底） */
    public void clearAll() {
        applied.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            strip(player);
        }
    }

    /** 是否正被压档（自检 / 排查用） */
    public boolean isBalanced(UUID uuid) {
        return uuid != null && applied.containsKey(uuid);
    }

    public Applied appliedOf(UUID uuid) {
        return uuid == null ? null : applied.get(uuid);
    }

    public int activeCount() {
        return applied.size();
    }

    private void strip(Player player) {
        setModifier(player, Attribute.ATTACK_DAMAGE, keyAtk, 0);
        setModifier(player, Attribute.ATTACK_SPEED, keySpeed, 0);
    }

    /**
     * 挂 / 换一条属性修正；amount 为 0 表示只删不挂。
     *
     * <p>用 transient 版本：只活在内存里，服务器崩了也不会在玩家存档里留脏修正。</p>
     */
    private void setModifier(Player player, Attribute attribute, NamespacedKey key, double amount) {
        AttributeInstance inst = player.getAttribute(attribute);
        if (inst == null) {
            return;
        }
        inst.removeModifier(key);
        if (Math.abs(amount) > 1e-4) {
            inst.addTransientModifier(new AttributeModifier(key, amount,
                    AttributeModifier.Operation.ADD_NUMBER));
        }
    }

    // ------------------------------------------------------------ 基准属性

    /**
     * 取该武器「0 级」时的基础属性，用来算要扣多少。
     *
     * <p>优先级与 {@code StrengthenService#apply} 一致：PDC 快照 → 身上已放大的
     * modifier 反推 → 材料 × 品质兜底。老物品没有快照时用第二条。</p>
     */
    private double baseOf(ItemStack item, NamespacedKey snapshotKey, String modifierKey,
                          Attribute attribute, int level) {
        if (item == null || item.getType().isAir()) {
            return 0;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return 0;
        }
        Double stored = meta.getPersistentDataContainer().get(snapshotKey, PersistentDataType.DOUBLE);
        if (stored != null) {
            return Math.max(0, stored);
        }
        double mult = 1.0 + plugin.strengthen().bonusRatio(level);
        Collection<AttributeModifier> mods = meta.getAttributeModifiers(attribute);
        if (mods != null) {
            for (AttributeModifier m : mods) {
                NamespacedKey key = m.getKey();
                if (key != null && modifierKey.equals(key.getKey())) {
                    return mult <= 1e-6 ? 0 : Math.max(0, m.getAmount() / mult);
                }
            }
        }
        ForgeMaterial mat = plugin.strengthen().materialOf(item);
        Quality q = plugin.strengthen().qualityOf(item);
        if (mat == null || q == null) {
            return 0;
        }
        double base = attribute == Attribute.ATTACK_DAMAGE ? mat.bonusAttack() : mat.bonusSpeed();
        return Math.max(0, base * q.power());
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
