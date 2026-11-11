package com.forgemaster.pass;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.gui.ForgeMenu;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 赛季通行证面板：顶部积分进度条 + 免费 / 付费双轨道奖励列表（v2.23）。
 *
 * <p>静态快照界面（打开时画一次），领取成功由路由下一 tick 重画，
 * 不挂定时任务（照 SpectrumGui / ContestGui 的省任务做法）。</p>
 *
 * <p>槽位（54 格，本类独占）：</p>
 * <pre>
 *   0  1  2  3  4  5  6  7  8     0-8 = 积分进度条（toward 下一级），4 = 状态牌
 *   9 10 11 12 13 14 15 16 17     10-16 / 19-25 / 28-34 = 免费轨 20 级（7 列 × 3 行）
 *  18 19 20 21 22 23 24 25 26
 *  27 28 29 30 31 32 33 34 35
 *  36 37 38 39 40 41 42 43 44     36-44 / 45-48 + 50-53 = 付费轨 17 级（49 留给关闭）
 *  45 46 47 48 49 50 51 52 53
 * </pre>
 *
 * <p>付费轨未解锁时整排画锁（铁栏杆），点任一锁 = 武装购买，3 秒内再点同一格确认
 * （照 ReforgeMenu 的「变红 → 再点一次」两步确认惯例）。已解锁则与免费轨一样
 * 逐级领取。付费轨与免费轨共用同一张积分门槛表，两轨并行各领各的。</p>
 *
 * <p>状态三色：可领亮金（金块 / 下界合金块图标）、已领灰勾（灰色染料）、
 * 未达标暗色（灰色玻璃）。</p>
 */
public final class PassGui {

    public static final int SIZE = 54;

    /** 进度条行 */
    public static final int BAR_FROM = 0;
    public static final int BAR_TO = 8;
    /** 状态牌（当前积分 / 已领等级 / 下一级） */
    public static final int STATUS_SLOT = 4;
    /** 等级槽位：21 格最多摆 21 级，config 现配 20 级 */
    public static final int[] LEVEL_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    /**
     * 付费轨槽位：第 4、5 行全宽共 17 格，49 留给关闭按钮。
     * 付费轨最多展示 17 级（config 现配 20 级，多出的级仍在轨道上、积分照样爬，
     * 只是这一页摆不下——与免费轨 21 格上限同一性质）。
     */
    public static final int[] PREMIUM_SLOTS = {
            36, 37, 38, 39, 40, 41, 42, 43, 44,
            45, 46, 47, 48, 50, 51, 52, 53
    };
    /** 付费轨购买的两步确认窗口（毫秒），照 ReforgeMenu 的 3 秒惯例 */
    public static final long BUY_CONFIRM_MILLIS = 3000;
    /** 关闭 */
    public static final int CLOSE_SLOT = 49;

    /** 面板持有者 */
    public static final class Holder implements InventoryHolder {

        private final ForgingMasterPlugin plugin;
        private final UUID viewer;
        /** 槽位 → 等级号（点击路由用） */
        private final Map<Integer, Integer> slotLevels = new LinkedHashMap<>();
        /** 付费轨槽位 → 等级号（点击路由用；未解锁也填，路由按已购与否分流） */
        private final Map<Integer, Integer> premiumSlotLevels = new LinkedHashMap<>();
        /** 面板对应的 Inventory（createHolder 后由 open 回填） */
        private Inventory inventory;

        Holder(ForgingMasterPlugin plugin, UUID viewer) {
            this.plugin = plugin;
            this.viewer = viewer;
        }

        public ForgingMasterPlugin plugin() {
            return plugin;
        }

        public UUID viewer() {
            return viewer;
        }

        Map<Integer, Integer> slotLevels() {
            return slotLevels;
        }

        Map<Integer, Integer> premiumSlotLevels() {
            return premiumSlotLevels;
        }

        /**
         * 这格是不是处于「武装待确认」状态（同一格 + 未超时）。
         *
         * <p>v2.23.1 修 P1-2：判定委托给 {@link PassGuiRouter#armed}。
         * 原先状态存在 Holder 里，而 {@link PassGui#open} 每次都 {@code new Holder(...)}，
         * 一旦重画（每次点击都会重画）武装标记就丢，图标永远不变红、第二击永远命中「第一击」，
         * 付费轨买不下来。状态必须活在 Holder 之外。</p>
         */
        boolean buyArmed(int slot) {
            return PassGuiRouter.armed(viewer, slot);
        }

        void inventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private PassGui() {
    }

    /** 打开赛季通行证面板 */
    public static void open(ForgingMasterPlugin plugin, Player player) {
        if (plugin == null || player == null) {
            return;
        }
        PassGuiRouter.ensure(plugin);
        SeasonPassService service = plugin.seasonPass();
        if (service == null || !service.enabled()) {
            Msg.prefix(player, "<red>赛季通行证暂未开放。</red>");
            return;
        }

        Holder holder = new Holder(plugin, player.getUniqueId());
        int season = service.seasonNumber();
        Inventory inv = Bukkit.createInventory(holder, SIZE,
                Msg.mm("<dark_gray>赛季通行证 <dark_gray>| <#ffd166>第 " + season + " 季"));
        holder.inventory(inv);
        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, ForgeMenu.filler());
        }

        UUID uuid = player.getUniqueId();
        List<SeasonPassService.Level> levels = service.levels();

        // ---- 顶部进度条：向着下一级要的积分 ----
        int claimed = service.claimedLevel(uuid);
        SeasonPassService.Level next = service.level(claimed + 1);
        long points = service.points(uuid);
        double ratio;
        if (next == null) {
            ratio = 1.0; // 全领完：进度条拉满
        } else if (next.need <= 0) {
            ratio = 1.0;
        } else {
            long prevNeed = claimed > 0 && service.level(claimed) != null
                    ? service.level(claimed).need : 0;
            long span = Math.max(1, next.need - prevNeed);
            ratio = Math.max(0.0, Math.min(1.0, (points - prevNeed) / (double) span));
        }
        int solid = (int) Math.round(ratio * (BAR_TO - BAR_FROM + 1));
        for (int slot = BAR_FROM; slot <= BAR_TO; slot++) {
            boolean lit = slot - BAR_FROM < solid;
            inv.setItem(slot, ForgeMenu.icon(lit ? Material.LIME_STAINED_GLASS_PANE
                            : Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>·", List.of()));
        }

        inv.setItem(STATUS_SLOT, ForgeMenu.icon(Material.CLOCK,
                "<#ffd166><bold>第 " + season + " 季通行证</bold>",
                List.of("<gray>当前积分: <#ffd166>" + points,
                        "<gray>免费轨已领: <#ffd166>" + claimed + "</#ffd166> / <white>" + levels.size(),
                        next == null ? "<green>免费轨已全部领完！"
                                : "<gray>下一级: <white>第 " + next.level + " 级</white> 还差 <#ffd166>"
                                + Math.max(0, next.need - points) + "</#ffd166> 积分",
                        "",
                        service.premiumBought(uuid)
                                ? "<green>付费轨: 已解锁，与免费轨并行领"
                                : "<gray>付费轨: <#ffd166>" + service.premiumCost()
                                + "</#ffd166> 币解锁（下方付费轨任一格点击）",
                        "",
                        "<dark_gray>积分来源：锻造 / 强化 / 蜕变 /",
                        "<dark_gray>剑冢 / 比试 / 委托交付",
                        "<dark_gray>新赛季开始后旧季奖励作废，积分重爬")));

        // ---- 免费轨 20 级奖励 ----
        int max = Math.min(levels.size(), LEVEL_SLOTS.length);
        for (int i = 0; i < max; i++) {
            SeasonPassService.Level lv = levels.get(i);
            int slot = LEVEL_SLOTS[i];
            inv.setItem(slot, levelIcon(service, lv, uuid));
            holder.slotLevels().put(slot, lv.level);
        }

        // ---- 付费轨（v2.23）：未解锁画锁 + 价格（两步确认购买），已解锁逐级领取 ----
        if (service.premiumConfigured()) {
            boolean bought = service.premiumBought(uuid);
            int maxP = Math.min(levels.size(), PREMIUM_SLOTS.length);
            for (int i = 0; i < maxP; i++) {
                SeasonPassService.Level lv = levels.get(i);
                int slot = PREMIUM_SLOTS[i];
                inv.setItem(slot, bought
                        ? premiumLevelIcon(service, lv, uuid)
                        : premiumLockIcon(service, lv, holder, slot));
                holder.premiumSlotLevels().put(slot, lv.level);
            }
        }

        inv.setItem(CLOSE_SLOT, ForgeMenu.icon(Material.BARRIER, "<red>关闭", List.of(
                "<gray>左键关闭")));

        player.openInventory(inv);
    }

    /** 奖励内容 lore（免费轨 / 付费轨图标共用） */
    private static void addRewardLore(List<String> lore, SeasonPassService service,
                                      SeasonPassService.Reward reward) {
        if (!reward.isEmpty()) {
            lore.add("<gray>奖励:");
            if (reward.coins > 0) {
                lore.add("<gray>· 剑币 <#ffd166>+" + reward.coins + "</#ffd166>");
            }
            if (reward.materialId != null) {
                lore.add("<gray>· <#ffd166>" + reward.materialAmount + " × "
                        + service.materialLabel(reward.materialId) + "</#ffd166>");
            }
            if (reward.charmId != null) {
                lore.add("<gray>· <#ffd166>" + reward.charmAmount + " × "
                        + com.forgemaster.strengthen.Charm.plainName(reward.charmId) + "</#ffd166>");
            }
        } else {
            lore.add("<dark_gray>（本级无奖励）");
        }
    }

    /** 免费轨单级图标：可领亮金、已领灰勾、未达标暗色 */
    private static ItemStack levelIcon(SeasonPassService service, SeasonPassService.Level lv, UUID uuid) {
        boolean claimed = service.claimed(uuid, lv.level);
        boolean ready = service.canClaim(uuid, lv.level);
        long points = service.points(uuid);

        List<String> lore = new ArrayList<>();
        lore.add("<gray>所需积分: <#ffd166>" + lv.need + "</#ffd166> <dark_gray>（当前 " + points + "）");
        lore.add("");
        addRewardLore(lore, service, lv.reward);
        lore.add("");
        if (claimed) {
            lore.add("<green>✔ 已领取");
        } else if (ready) {
            lore.add("<yellow>▶ 积分已够！左键领取");
        } else {
            lore.add("<dark_gray>积分未达标" + (lv.level > service.claimedLevel(uuid) + 1
                    ? "（需先领上一级）" : "") + "</dark_gray>");
        }
        Material icon = claimed ? Material.GRAY_DYE
                : ready ? Material.GOLD_BLOCK
                : Material.GRAY_STAINED_GLASS_PANE;
        String name = claimed ? "<gray><st>第 " + lv.level + " 级</st> <green>✔"
                : ready ? "<#ffd166><bold>第 " + lv.level + " 级</bold></#ffd166>"
                : "<#b0b8c4>第 " + lv.level + " 级";
        return ForgeMenu.icon(icon, name, lore);
    }

    /** 付费轨单级图标（已解锁后）：可领亮合金、已领灰勾、未达标暗色 */
    private static ItemStack premiumLevelIcon(SeasonPassService service,
                                              SeasonPassService.Level lv, UUID uuid) {
        boolean claimed = service.premiumClaimed(uuid, lv.level);
        boolean ready = service.canClaimPremium(uuid, lv.level);
        long points = service.points(uuid);

        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>付费轨 <#ffd166>★</#ffd166> <dark_gray>（与免费轨并行逐级领）");
        lore.add("<gray>所需积分: <#ffd166>" + lv.need + "</#ffd166> <dark_gray>（当前 " + points + "）");
        lore.add("");
        addRewardLore(lore, service, service.premiumReward(lv.level));
        lore.add("");
        if (claimed) {
            lore.add("<green>✔ 已领取");
        } else if (ready) {
            lore.add("<yellow>▶ 积分已够！左键领取");
        } else {
            lore.add("<dark_gray>积分未达标" + (lv.level > service.premiumClaimedLevel(uuid) + 1
                    ? "（需先领付费轨上一级）" : "") + "</dark_gray>");
        }
        Material icon = claimed ? Material.GRAY_DYE
                : ready ? Material.NETHERITE_BLOCK
                : Material.GRAY_STAINED_GLASS_PANE;
        String name = claimed ? "<gray><st>付费轨 第 " + lv.level + " 级</st> <green>✔"
                : ready ? "<#ffd166><bold>付费轨 第 " + lv.level + " 级</bold></#ffd166>"
                : "<#b0b8c4>付费轨 第 " + lv.level + " 级";
        return ForgeMenu.icon(icon, name, lore);
    }

    /** 付费轨锁图标（未解锁）：锁 + 价格；武装中的那格变红，提示再点一次确认 */
    private static ItemStack premiumLockIcon(SeasonPassService service, SeasonPassService.Level lv,
                                             Holder holder, int slot) {
        boolean armed = holder.buyArmed(slot);
        long cost = service.premiumCost();

        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>付费轨（本季未解锁）");
        lore.add("");
        addRewardLore(lore, service, service.premiumReward(lv.level));
        lore.add("");
        lore.add(cost > 0
                ? "<gray>解锁价: <#ffd166>" + cost + "</#ffd166> 剑币（买断本季）"
                : "<gray>本季免费解锁（点击即得）");
        lore.add("<dark_gray>本季限买一次，跨赛季重置");
        lore.add("");
        if (armed) {
            lore.add("<red><bold>▶ 再点一次：确认购买（不可退）</bold>");
            return ForgeMenu.icon(Material.MAGMA_BLOCK,
                    "<red><bold>确认解锁付费轨？</bold>", lore);
        }
        lore.add("<yellow>左键点击 → 变红 → " + (BUY_CONFIRM_MILLIS / 1000)
                + " 秒内再点一次</yellow> <gray>确认购买");
        return ForgeMenu.icon(Material.IRON_BARS,
                "<gray>付费轨 第 " + lv.level + " 级 <dark_red>（未解锁）", lore);
    }

    /**
     * 槽位 → 用途。给自检用：断言没有两个功能撞同一格，不用真开游戏。
     */
    public static Map<Integer, String> slotUsage() {
        Map<Integer, String> map = new LinkedHashMap<>();
        for (int slot = BAR_FROM; slot <= BAR_TO; slot++) {
            map.put(slot, "积分进度条");
        }
        for (int slot : LEVEL_SLOTS) {
            map.put(slot, "免费轨等级奖励");
        }
        for (int slot : PREMIUM_SLOTS) {
            map.put(slot, "付费轨等级奖励");
        }
        map.put(CLOSE_SLOT, "关闭");
        return map;
    }
}
