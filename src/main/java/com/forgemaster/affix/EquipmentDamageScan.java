package com.forgemaster.affix;

import com.forgemaster.util.Keys;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 受击装备单遍扫描（审计 #6）。
 *
 * <p>玩家每次受到伤害，{@code ForgeListener.onDamaged}（词条减伤，HIGH）与
 * {@code AffixSetListener.onPlayerDamaged}（套装铁壁减伤，NORMAL）原本各自遍历
 * 4~6 件装备，一次受击最多 10 次 {@code getItemMeta}。这里把数据读取合并为
 * <b>一次遍历</b>：主手 + 副手 + 四件护甲，每件只读一次 meta，同时取出
 * 词条减伤（{@code DAMAGE_REDUCE}）与套装减伤（{@code matchedSetsByMeta}），
 * 两个监听器各取自己那份结果，各自的事件优先级与结算逻辑不变。</p>
 *
 * <p><b>同一次伤害事件只扫一遍</b>：结果按「玩家 → 最近一次事件」缓存，
 * NORMAL 优先级的套装监听先算，HIGH 优先级的词条监听命中同一事件对象时
 * 直接复用缓存。事件对象是短命对象、条目被下一次事件覆盖，用总量上限兜底
 * 防止长期运行下条目堆积。全部访问都在主线程，synchronized 只是兜底。</p>
 */
public final class EquipmentDamageScan {

    /** 单次扫描结果：词条减伤总和 + 套装 id → 减伤值（同一套装多件只取最大） */
    public record Result(double affixReduce, Map<String, Double> setReduce) {
    }

    private static final Map<UUID, CacheEntry> CACHE = new HashMap<>();
    /** 缓存条目上限：超过整体清空（缓存只是省一次重复扫描，清掉不影响正确性） */
    private static final int CACHE_LIMIT = 512;

    private record CacheEntry(EntityDamageEvent event, Result result) {
    }

    private EquipmentDamageScan() {
    }

    /**
     * 扫描（或复用同事件缓存）该玩家身上 6 件装备的减伤数据。
     *
     * @param sets  套装服务；传 null 表示调用方不关心套装部分（如词条监听单独触发时）
     * @param event 当前伤害事件，用作同事件去重的 key
     */
    public static synchronized Result scan(Player player, EntityDamageEvent event, AffixSetService sets) {
        CacheEntry cached = CACHE.get(player.getUniqueId());
        if (cached != null && cached.event() == event) {
            return cached.result();
        }
        Result result = scanNow(player, sets);
        if (CACHE.size() >= CACHE_LIMIT) {
            CACHE.clear();
        }
        CACHE.put(player.getUniqueId(), new CacheEntry(event, result));
        return result;
    }

    private static Result scanNow(Player player, AffixSetService sets) {
        boolean wantSets = sets != null && sets.enabled();
        double affixReduce = 0;
        Map<String, Double> best = wantSets ? new LinkedHashMap<>() : Map.of();

        List<ItemStack> items = new ArrayList<>(6);
        items.add(player.getInventory().getItemInMainHand());
        items.add(player.getInventory().getItemInOffHand());
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            items.add(piece);
        }
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            // 每件只读一次 meta：词条减伤与套装判定共用这一份
            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                continue;
            }
            Double v = meta.getPersistentDataContainer()
                    .get(Keys.DAMAGE_REDUCE, PersistentDataType.DOUBLE);
            if (v != null) {
                affixReduce += v;
            }
            if (wantSets) {
                for (String id : sets.matchedSetsByMeta(meta)) {
                    AffixSet set = sets.set(id);
                    if (set != null && set.damageReduce() > 0) {
                        // 同一个套装重复出现只取最大那份，不累加（与原 collectReduce 一致）
                        best.merge(id, set.damageReduce(), Math::max);
                    }
                }
            }
        }
        return new Result(affixReduce, best);
    }
}
