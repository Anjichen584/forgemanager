package com.forgemaster.guild;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.gui.ForgeMenu;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 行会仓库面板（v2.23 · P1-6）：54 格固定布局，前 {@code rows×9} 格是真仓储，
 * 其余填灰玻璃占位（占位格永不可点，挡掉 shift 流入），49 = 关闭。
 *
 * <p>与 {@link GuildGui} 的静态快照不同，这里是<b>活的</b>：放入 / 取出即点即存，
 * 点击路由在 {@link GuildGuiRouter}（懒注册，不碰主类）——仓储区放行原版移动，
 * 占位区一律取消；成员取出权限在路由里查 {@link GuildWarehouse#canWithdraw}。
 * 内容的落盘走「点击后下一 tick 逐格提交 + 关闭 / 掉线逐格提交 → 标脏 → GuildService
 * 5 秒异步冲刷」（v2.23.1 修 P0-2：原来是整仓快照覆盖，两人同开必复制 / 丢失）。</p>
 *
 * <h2>槽位不变量（v2.23.1 修 P0-1，改行数前必读）</h2>
 * <pre>
 *   仓储区     [0, capacity)         capacity = rows×9，rows ∈ [1, 5] ⇒ 最多 45 格
 *   装饰区     [STORAGE_MAX, SIZE)   STORAGE_MAX = 45，灰玻璃占位，永不可点
 *   关闭槽     49                    落在装饰区内
 * </pre>
 * <p>硬约束：<b>仓储区与「装饰 / 关闭」槽的索引区间必须完全不相交</b>，即恒有
 * {@code capacity ≤ STORAGE_MAX = 45 < CLOSE_SLOT = 49}。原先 {@code rows} 上限是 6
 * （54 格），49 号格同时是仓储格和关闭钮：{@link #open} 先回填真物、再写关闭图标，
 * 于是那格物品每次开仓都被覆盖销毁，且点击路由先判 {@code CLOSE_SLOT} 再判容量，
 * 就算满仓也只会关窗、取不出货。现在 45 &lt; 49，两个区间天然分离。</p>
 */
public final class GuildWarehouseGui {

    public static final int SIZE = 54;
    /** 仓储区最大格数 = 5 行 × 9；必须小于 {@link #CLOSE_SLOT}（见类注释不变量） */
    public static final int STORAGE_MAX_ROWS = 5;
    public static final int STORAGE_MAX = STORAGE_MAX_ROWS * 9;
    public static final int CLOSE_SLOT = 49;

    /** 面板持有者（路由只认这个 holder） */
    public static final class Holder implements InventoryHolder {

        private final ForgingMasterPlugin plugin;
        private final GuildService.Guild guild;
        private Inventory inventory;
        /** 仓储区容量（rows×9，已按 {@link #STORAGE_MAX} 钳过）；占位区从 capacity() 开始 */
        private final int capacity;
        /** 打开这个面板的玩家（冲突回滚要把物品退还给他） */
        private final java.util.UUID viewer;
        /** 逐格提交的比对基线：本次会话「上次看到的」每格内容，提交成功后就地更新 */
        private final ItemStack[] baseline;
        /** 已排了一次下一 tick 提交就不再重复排（主线程专用标记） */
        boolean snapshotQueued;

        Holder(ForgingMasterPlugin plugin, GuildService.Guild guild, int capacity,
               java.util.UUID viewer, ItemStack[] baseline) {
            this.plugin = plugin;
            this.guild = guild;
            this.capacity = capacity;
            this.viewer = viewer;
            this.baseline = baseline;
        }

        public ForgingMasterPlugin plugin() {
            return plugin;
        }

        public GuildService.Guild guild() {
            return guild;
        }

        /** 打开面板的玩家 uuid（掉线后 Bukkit.getPlayer 会返回 null） */
        public java.util.UUID viewer() {
            return viewer;
        }

        /** 仓储区格数；路由用它区分「仓储区 / 占位区」 */
        public int capacity() {
            return capacity;
        }

        /** 这一格的比对基线（null = 上次看到的是空格） */
        ItemStack baselineAt(int slot) {
            return slot >= 0 && slot < baseline.length ? baseline[slot] : null;
        }

        /** 提交成功后更新基线（存副本，防外面继续改同一个 ItemStack 实例） */
        void baselineAt(int slot, ItemStack item) {
            if (slot >= 0 && slot < baseline.length) {
                baseline[slot] = (item == null || item.getType().isAir()) ? null : item.clone();
            }
        }

        void inventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private GuildWarehouseGui() {
    }

    /** 打开所在行会的共享仓库 */
    public static void open(ForgingMasterPlugin plugin, Player player) {
        if (plugin == null || player == null) {
            return;
        }
        GuildService service = plugin.guilds();
        if (service == null || !service.enabled()) {
            Msg.prefix(player, "<red>行会功能暂未开放。</red>");
            return;
        }
        GuildService.Guild g = service.guildOf(player.getUniqueId());
        if (g == null) {
            Msg.prefix(player, "<gray>你还没有行会，先 <white>/zhujian guild create</white> 或找会长邀请。</gray>");
            return;
        }
        GuildGuiRouter.ensure(plugin); // 仓库路由与列表路由共用一个监听器

        GuildWarehouse wh = service.warehouse();
        // v2.23.1 修 P0-1：双保险把容量压在 STORAGE_MAX(45) 以内。
        // GuildWarehouse.clampRows 已经把行数钳到 5，这里再 Min 一次是为了「只改配置行数」
        // 或「将来有人把 clampRows 改回 6」时，面板也不会把仓储格铺到 49 号关闭钮上。
        int cap = Math.min(wh.capacity(), STORAGE_MAX);
        ItemStack[] contents = wh.contents(g.id);
        ItemStack[] baseline = new ItemStack[cap];
        for (int i = 0; i < cap && i < contents.length; i++) {
            ItemStack it = contents[i];
            baseline[i] = (it == null || it.getType().isAir()) ? null : it.clone();
        }
        Holder holder = new Holder(plugin, g, cap, player.getUniqueId(), baseline);
        Inventory inv = Bukkit.createInventory(holder, SIZE,
                Msg.mm("<dark_gray>行会仓库 <dark_gray>| <#ffd166>" + g.name));
        holder.inventory(inv);

        // 仓储区（0..cap-1）留空：shift 存入要落到第一个空格，占位玻璃会挡住它。
        // 占位区（cap..SIZE-1，含 49 号关闭钮）填灰玻璃，路由里永不可点。
        // cap ≤ 45 恒成立，所以 49 号格永远在占位区，不会与仓储格重叠（P0-1 的不变量）。
        ItemStack filler = ForgeMenu.filler();
        for (int i = cap; i < SIZE; i++) {
            inv.setItem(i, filler);
        }
        for (int i = 0; i < cap && i < baseline.length; i++) {
            if (baseline[i] != null) {
                inv.setItem(i, baseline[i].clone());
            }
        }

        boolean canTake = GuildWarehouse.canWithdraw(g.owner.equals(player.getUniqueId()), wh.memberWithdraw());
        inv.setItem(CLOSE_SLOT, ForgeMenu.icon(Material.BARRIER, "<red>关闭", List.of(
                "<gray>左键关闭",
                canTake ? "<gray>点击格子即可放入 / 取出"
                        : "<gray>你只能放入，取出需要会长权限",
                "<dark_gray>容量 " + cap + " 格（guild.warehouse.rows）",
                "<dark_gray>行会委托：/zhujian guild quest")));

        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.2f);
    }
}
