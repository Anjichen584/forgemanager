package com.forgemaster.dissolve;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.quality.Quality;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * 剑魄分解：把手上不再需要的锻造品拆成「剑尘」。
 *
 * <p>与 {@code /zhujian salvage}（熔材料）走的是两条回收线：salvage 熔的是
 * <b>材料</b>（回一半锻造成本），dissolve 拆的是<b>剑魄</b>——强化等级、锻阶、
 * 品质一起折算成剑尘。剑尘平时没用，只在强化时材料不够的时候按 1:1 抵扣差额，
 * 相当于把旧剑的投入存进一张「通用材料券」。</p>
 *
 * <p>剑尘产出公式（向下取整）：</p>
 * <pre>数量 = (base-dust + per-level × 强化等级 + per-stage × 蜕变阶) × 品质系数</pre>
 *
 * <p>品质系数默认 ROUGH 0.5 / COMMON 1 / FINE 1.5 / EPIC 2 / LEGENDARY 3 / MYTHIC 4；
 * 神话另给 1 个下界之星（神话胚本身就是要拿远古残骸换的，纯剑尘补不回成本）。
 * 履历里有碎剑记录的不打折——碎过又锻回来的剑更难得，回收时不惩罚。</p>
 *
 * <p>剑魂传承按碎剑的 <b>50%</b> 记账（{@code grantInherit(player, level / 2)}）：
 * 碎剑是被动损失，传承是全额补偿；分解是玩家主动选择、还全额拿回剑尘，
 * 再给全额传承就等于「把碎剑的补偿位白嫖一遍」，会催生刷传承的套利口。
 * 压到一半既保留保底，也不抢碎剑的补偿定位。</p>
 *
 * <p>配置段 {@code dissolve.*}；本类不注册监听器，剑尘只在强化扣材料时被消费。</p>
 */
public final class DissolveService {

    private DissolveService() {
    }

    // ------------------------------------------------------------ 开关与配置

    public static boolean enabled(ForgingMasterPlugin plugin) {
        return plugin != null && plugin.settings().flag("dissolve.enabled", true);
    }

    /** 基础剑尘（任何品质、任何等级都至少有这一份） */
    private static int baseDust(ForgingMasterPlugin plugin) {
        return Math.max(0, plugin.settings().intOf("dissolve.base-dust", 5));
    }

    /** 每级强化折多少剑尘 */
    private static int perLevel(ForgingMasterPlugin plugin) {
        return Math.max(0, plugin.settings().intOf("dissolve.per-level", 1));
    }

    /** 每一阶蜕变折多少剑尘 */
    private static int perStage(ForgingMasterPlugin plugin) {
        return Math.max(0, plugin.settings().intOf("dissolve.per-stage", 8));
    }

    /** 品质系数（dissolve.quality-coeff.<品质> 可逐项覆盖，缺省按 0.5/1/1.5/2/3/4） */
    public static double qualityCoeff(ForgingMasterPlugin plugin, Quality q) {
        Quality quality = q == null ? Quality.COMMON : q;
        double def = switch (quality) {
            case ROUGH -> 0.5;
            case COMMON -> 1.0;
            case FINE -> 1.5;
            case EPIC -> 2.0;
            case LEGENDARY -> 3.0;
            case MYTHIC -> 4.0;
        };
        return Math.max(0, plugin.settings().num("dissolve.quality-coeff." + quality.name(), def));
    }

    /** 单次强化最多用多少剑尘抵扣差额（剑币手续费不在此列） */
    public static int maxOffsetPerStrike(ForgingMasterPlugin plugin) {
        return Math.max(0, plugin.settings().intOf("dissolve.max-offset-per-strike", 10));
    }

    /**
     * v2.23.1 修 P1：按需造多叠发放剑尘，总数量精确等于 {@code total}。
     *
     * <p>原来会怎样：{@code dissolve()} 只调一次 {@code createDust(plugin, dust)}，
     * 而该构造器把数量钳在 64。+20 强化 / 蜕变 5 阶 / 神话（系数 4）的剑
     * 应得 {@code (5+20+40)×4 = 260}，玩家实际只拿到 64 个，损失约 75%。</p>
     *
     * <p>现在为什么不会：按 64 一叠循环造，塞不进背包的部分掉在地上，
     * 不合并进已有叠、不静默丢弃（合并会让"应得 260"和"实际到手"再次对不上账）。</p>
     */
    private static void giveDust(ForgingMasterPlugin plugin, Player player, int total) {
        int remain = Math.max(0, total);
        while (remain > 0) {
            int n = Math.min(64, remain);
            for (ItemStack rest : player.getInventory().addItem(createDust(plugin, n)).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
            remain -= n;
        }
    }

    /**
     * 这把剑能拆出多少剑尘（纯计算，不动物品）。
     *
     * @param level   强化等级（{@code levelOf}，非负）
     * @param stage   蜕变阶（{@code stageOf}，非负）
     * @param quality 品质
     */
    public static int dustOf(ForgingMasterPlugin plugin, int level, int stage, Quality quality) {
        long base = baseDust(plugin) + (long) perLevel(plugin) * Math.max(0, level)
                + (long) perStage(plugin) * Math.max(0, stage);
        // 品质系数放大后仍可能是天文数字（服主把 per-stage 配得很大）：钳到 int 上限，
        // 避免 (int) 强转成负数导致"分解反而倒扣"
        long scaled = (long) Math.floor(base * qualityCoeff(plugin, quality));
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, scaled));
    }

    // ------------------------------------------------------------ 剑尘物品

    /** 剑尘的材质（火药，对应 GUNPOWDER 外观） */
    private static Material dustMaterial() {
        return Material.GUNPOWDER;
    }

    /**
     * 造一批剑尘（数量 1~64，超出截断）。
     *
     * <p>v2.23.1 修 P1：这是**单叠**构造器，保留 64 上限（ItemStack 的硬限制）。
     * 需要发超过 64 个时必须走 {@link #giveDust}，否则高强化/神话剑的产出会被
     * 静默截断——原来 {@code dissolve()} 直接调本方法，一把 +20 / 5 阶 / 神话的剑
     * 应得 260 个，玩家只拿到 64 个。</p>
     */
    public static ItemStack createDust(ForgingMasterPlugin plugin, int amount) {
        ItemStack item = new ItemStack(dustMaterial(), Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Msg.mm("<#9f7aea>剑尘</#9f7aea>")
                    .decoration(TextDecoration.ITALIC, false));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(line("<dark_gray>—— 剑魄分解 · 产物 ——</dark_gray>"));
            lore.add(line("<gray>磨碎的剑魄，没有锋刃，只剩韧。"));
            lore.add(line("<gray>强化时材料不够，会自动按 <white>1:1</white> 抵扣差额"));
            lore.add(line("<gray>（单次最多抵 <white>" + maxOffsetPerStrike(plugin) + "</white> 个，"
                    + "剑币手续费不抵）。"));
            lore.add(line(""));
            lore.add(line("<dark_gray>右键无用 · 只作强化抵扣</dark_gray>"));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(Keys.DUST, PersistentDataType.INTEGER, 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 这堆东西是不是剑尘 */
    public static boolean isDust(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        Integer mark = meta.getPersistentDataContainer().get(Keys.DUST, PersistentDataType.INTEGER);
        return mark != null && mark == 1;
    }

    /** 背包里一共有多少剑尘 */
    public static int countDust(Player player) {
        if (player == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack it : player.getInventory().getContents()) {
            if (it != null && isDust(it)) {
                total += it.getAmount();
            }
        }
        return total;
    }

    /** 从背包里拿掉 n 个剑尘（调用前先用 {@link #countDust} 确认够） */
    public static void takeDust(Player player, int amount) {
        if (player == null || amount <= 0) {
            return;
        }
        int left = amount;
        var inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        for (int slot = 0; slot < contents.length && left > 0; slot++) {
            ItemStack it = contents[slot];
            if (it == null || !isDust(it)) {
                continue;
            }
            int take = Math.min(left, it.getAmount());
            // 不能 setAmount(0)：非法堆叠会出幽灵物品，扣完整格直接清 null
            it.setAmount(it.getAmount() - take);
            if (it.getAmount() <= 0) {
                inv.setItem(slot, null);
            } else {
                inv.setItem(slot, it);
            }
            left -= take;
        }
    }

    /**
     * 本次强化最多能抵多少剑尘：差额、单次上限、背包存量三者取小。
     *
     * <p>只算不扣；真正消费发生在强化扣费阶段（{@link #takeDust}）。</p>
     */
    public static int offsetAmount(ForgingMasterPlugin plugin, Player player, int shortfall) {
        if (shortfall <= 0) {
            return 0;
        }
        return Math.min(Math.min(shortfall, maxOffsetPerStrike(plugin)), countDust(player));
    }

    // ------------------------------------------------------------ 分解

    /**
     * {@code /zhujian dissolve [confirm]}：分解主手的锻造品。
     *
     * <p>不带 confirm 只报价不动物品（与天赋洗点同款两段式确认）。</p>
     *
     * @return 给玩家看的 MiniMessage 文本
     */
    public static String dissolve(ForgingMasterPlugin plugin, Player player, String[] args) {
        if (!enabled(plugin)) {
            return "<red>分解功能没开（<white>dissolve.enabled = false</white>）。</red>";
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir() || plugin.strengthen().levelOf(hand) < 0) {
            return "<red>主手先拿一件锻造品。</red>";
        }
        int level = plugin.strengthen().levelOf(hand);
        var evo = plugin.evolutions();
        int stage = evo == null ? 0 : Math.max(0, evo.stageOf(hand));
        Quality quality = plugin.strengthen().qualityOf(hand);
        int dust = dustOf(plugin, level, stage, quality);
        int broken = plugin.strengthen().brokenOf(hand);
        boolean mythic = quality == Quality.MYTHIC;

        boolean confirm = args != null && args.length >= 2
                && (args[1].equalsIgnoreCase("confirm") || args[1].equalsIgnoreCase("queren"));
        if (!confirm) {
            return "<gray>将分解主手的 " + quality.colored() + " <white>+" + level + "</white>"
                    + (stage > 0 ? " <gray>（蜕变 " + stage + " 阶）" : "")
                    + "<gray>，产出：</gray><#9f7aea>剑尘 ×" + dust + "</#9f7aea>"
                    + (mythic ? " <gray>+ <white>下界之星 ×1</white>" : "")
                    + "<newline><dark_gray>剑魂传承按碎剑的 50% 记账"
                    + (broken > 0 ? " · 履历碎剑 " + broken + " 次（不打折）" : "")
                    + "</dark_gray>"
                    + "<newline><gray>确认请再执行：<white>/zhujian dissolve confirm</white>";
        }

        // ---- 确认后：先记传承，再销毁、发产出（顺序错了销毁后就拿不到等级了）----
        // 传承按 50% 记账：grantInherit 内部是 floor(brokenLevel × rate)，
        // 传 level/2 即得 floor(level/2 × rate) = 全额的一半。理由见类注释。
        plugin.strengthen().grantInherit(player, level / 2);
        player.getInventory().setItemInMainHand(null);

        // v2.23.1 修 P1：改走 giveDust（按 64 一叠拆多叠），产出 > 64 时不再被截断
        giveDust(plugin, player, dust);
        if (mythic) {
            for (ItemStack rest : player.getInventory()
                    .addItem(new ItemStack(Material.NETHER_STAR, 1)).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 0.6f);
        return "<green>分解完成</green> <dark_gray>·</dark_gray> " + quality.colored()
                + " <white>+" + level + "</white> <gray>→</gray> <#9f7aea>剑尘 ×" + dust + "</#9f7aea>"
                + (mythic ? " <gray>+ 下界之星 ×1" : "")
                + "<gray> <dark_gray>· 传承记 " + (level / 2) + " 级（50%）</dark_gray>";
    }

    private static net.kyori.adventure.text.Component line(String mini) {
        return Msg.mm(mini).decoration(TextDecoration.ITALIC, false);
    }
}
