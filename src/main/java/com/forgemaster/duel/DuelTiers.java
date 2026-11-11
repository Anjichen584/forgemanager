package com.forgemaster.duel;

import com.forgemaster.ForgingMasterPlugin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 竞技的「锻造等级分档」。
 *
 * <p>比试按双方<strong>主手武器</strong>的锻造等级（{@code /zhujian strengthen} 强出来的那级）
 * 划档，默认 0-9 / 10-19 / 20+ 三档：同档才能开打，免得一把 +32 星陨剑
 * 去屠 +5 铁剑。档位边界、总开关、跨档策略都在 config.yml 的
 * {@code duel.strengthen-tier.*} 里，服主关掉后就是原来的无差别比试。</p>
 *
 * <p>这里只负责「读等级 + 算档位」，一个字节的物品数据都不改；
 * 临时压档在 {@link DuelBalance} 里做。</p>
 */
public final class DuelTiers {

    private final ForgingMasterPlugin plugin;

    public DuelTiers(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ 开关

    /** 分级匹配总开关；关掉后既不卡档位也不压档，完全恢复老行为 */
    public boolean enabled() {
        return plugin.settings().flag("duel.strengthen-tier.enabled", true);
    }

    /** 跨档能不能打：默认 false，直接拒绝并提示双方档位 */
    public boolean crossAllowed() {
        return plugin.settings().flag("duel.strengthen-tier.allow-cross", false);
    }

    /** 跨档开打时，是否把高等级方临时压到低等级方（默认压） */
    public boolean balanceEnabled() {
        return plugin.settings().flag("duel.strengthen-tier.balance", true);
    }

    // ------------------------------------------------------------ 档位

    /**
     * 档位边界（升序、去重、只留正数）。
     *
     * <p>默认 {@code [10, 20]} → 0-9 / 10-19 / 20+ 三档。写成
     * <code>duel.strengthen-tier.bounds: [10, 20]</code> 即可，改完
     * {@code /zhujian reload} 生效。</p>
     */
    public List<Integer> bounds() {
        List<String> raw = plugin.settings().strList("duel.strengthen-tier.bounds",
                List.of("10", "20"));
        List<Integer> out = new ArrayList<>();
        for (String s : raw) {
            int v = parseInt(s, -1);
            // 边界必须 > 0，否则 0-9 那档会被撑成空档
            if (v > 0 && !out.contains(v)) {
                out.add(v);
            }
        }
        out.sort(Integer::compareTo);
        return out;
    }

    /** 等级 → 档位序号（0 起）。等级小于所有边界就是 0 档 */
    public int tierOf(int level) {
        int lv = Math.max(0, level);
        int tier = 0;
        for (int bound : bounds()) {
            if (lv < bound) {
                break;
            }
            tier++;
        }
        return tier;
    }

    /** 档位序号 → 显示用的区间文本：0-9 / 10-19 / 20+ */
    public String labelOf(int tier) {
        List<Integer> bs = bounds();
        int idx = Math.max(0, Math.min(bs.size(), tier));
        int low = idx == 0 ? 0 : bs.get(idx - 1);
        if (idx >= bs.size()) {
            return low + "+";
        }
        return low + "-" + (bs.get(idx) - 1);
    }

    /** 档位总数（自检 / 提示用） */
    public int tierCount() {
        return bounds().size() + 1;
    }

    // ------------------------------------------------------------ 玩家读数

    /** 玩家主手武器的锻造等级；非锻造品、空手都按 0 级算（≈ 最弱档） */
    public int weaponLevel(Player player) {
        if (player == null) {
            return 0;
        }
        ItemStack main = player.getInventory().getItemInMainHand();
        if (main == null || main.getType().isAir()) {
            return 0;
        }
        // 非锻造品 levelOf 返回 -1，比试里统一当 0 级处理
        return Math.max(0, plugin.strengthen().levelOf(main));
    }

    /** 一次把等级、档位、档位文本读齐，避免同一处重复算三遍 */
    public Snapshot of(Player player) {
        int level = weaponLevel(player);
        int tier = tierOf(level);
        return new Snapshot(level, tier, labelOf(tier));
    }

    /** 某个玩家的分档快照 */
    public record Snapshot(int level, int tier, String label) {
    }

    // ------------------------------------------------------------ 辅助

    private static int parseInt(String raw, int def) {
        if (raw == null) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}
