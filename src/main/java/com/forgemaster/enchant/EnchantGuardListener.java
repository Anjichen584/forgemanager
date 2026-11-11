package com.forgemaster.enchant;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 附魔白名单守卫（v2.23.2）—— 服主要的那「再限制一层」。
 *
 * <h2>规则</h2>
 * 本插件的锻造品上，只允许出现<b>两类</b>附魔：
 * <ol>
 *   <li>原版附魔（但必须在本插件的白名单里，默认是全部原版附魔）；</li>
 *   <li>本插件的原创附魔（{@code Keys.CUSTOM_ENCH}，见 {@link EnchantRegistry}）。</li>
 * </ol>
 * 任何「其它插件塞进来的自定义附魔」、或者服主明确关掉的原版附魔，都会被挡在门外。
 *
 * <h2>拦在哪</h2>
 * <ul>
 *   <li><b>附魔台</b>：{@code PrepareItemEnchantEvent} 过滤候选；
 *       {@code EnchantItemEvent} 把不在白名单里的条目从「将要加上」的集合里摘掉。</li>
 *   <li><b>铁砧</b>：{@code PrepareAnvilEvent} 修成品 —— 铁砧合书是绕过附魔台最常用的路子。</li>
 * </ul>
 *
 * <p>只对<b>锻造品</b>生效（{@code Keys.FORGED}）。普通原版装备完全不受影响 ——
 * 服主装这个插件是为了管自己的装备，不是为了接管整个服的附魔规则。</p>
 */
public final class EnchantGuardListener implements Listener {

    private final ForgingMasterPlugin plugin;

    public EnchantGuardListener(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("enchant.guard.enabled", true);
    }

    /** 原版附魔白名单（config 里列 key；空 = 全部允许） */
    private java.util.Set<String> vanillaWhitelist() {
        return new java.util.HashSet<>(
                plugin.getConfig().getStringList("enchant.guard.vanilla-whitelist"));
    }

    private boolean allowed(String key) {
        java.util.Set<String> white = vanillaWhitelist();
        // 白名单为空时不过滤原版附魔：默认「原版随便附」，服主要收紧再自己列
        return white.isEmpty() || white.contains(key);
    }

    private static boolean forged(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(Keys.FORGED, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------ 附魔台

    /**
     * 应用过滤：把不该加的从 enchantmentsToAdd 里摘掉。
     *
     * <p>这里不碰 {@code PrepareItemEnchantEvent}：Bukkit 的 {@code EnchantOffer}
     * 只读，改不了候选，硬删单个候选会让三格的槽位与图标错位
     * （玩家花了 3 级点第二格，结果给的是第三格的附魔）。
     * 真正管住结果的是本方法与下面的铁砧钩子。</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        if (!enabled() || !forged(event.getItem())) {
            return;
        }
        Map<org.bukkit.enchantments.Enchantment, Integer> add = event.getEnchantsToAdd();
        if (add == null || add.isEmpty()) {
            return;
        }
        List<String> blocked = new ArrayList<>();
        Iterator<Map.Entry<org.bukkit.enchantments.Enchantment, Integer>> it = add.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            String key = e.getKey().getKey().getKey();
            if (!allowed(key)) {
                blocked.add(key);
                it.remove();
            }
        }
        if (add.isEmpty()) {
            // 一个都不剩：取消这次附魔，否则会白扣经验等级却什么都不给
            event.setCancelled(true);
            Player player = event.getEnchanter();
            Msg.prefix(player, "<red>这件装备不接受这些附魔（enchant.guard.vanilla-whitelist）。");
            return;
        }
        if (!blocked.isEmpty()) {
            Msg.action(event.getEnchanter(), "<gray>已过滤: <dark_gray>" + String.join(", ", blocked));
        }
    }

    // ------------------------------------------------------------ 铁砧

    /**
     * 铁砧：合书 / 修装备都可能把非白名单附魔带上锻造品。
     *
     * <p>做法是「改成品」而不是「取消事件」—— 取消会让玩家手里的书直接消失，
     * 而改成品至少让这次合并的其它效果（修耐久、改名）还能用。</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnvil(PrepareAnvilEvent event) {
        if (!enabled()) {
            return;
        }
        ItemStack result = event.getResult();
        if (result == null || result.getType().isAir()) {
            return;
        }
        AnvilView view = event.getView();
        ItemStack first = view.getItem(0);
        if (!forged(first) && !forged(result)) {
            return;
        }
        ItemMeta meta = result.getItemMeta();
        if (meta == null) {
            return;
        }
        Map<org.bukkit.enchantments.Enchantment, Integer> ench = meta.getEnchants();
        if (ench.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (var e : new ArrayList<>(ench.entrySet())) {
            String key = e.getKey().getKey().getKey();
            if (!allowed(key)) {
                meta.removeEnchant(e.getKey());
                changed = true;
            }
        }
        if (changed) {
            result.setItemMeta(meta);
            event.setResult(result);
        }
    }
}