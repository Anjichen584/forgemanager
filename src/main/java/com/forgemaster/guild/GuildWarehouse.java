package com.forgemaster.guild;

import com.forgemaster.ForgingMasterPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行会仓库（v2.23 · P1-6）：每个行会一个共享仓库，物品按行会 id 持久化到 guild-data.yml
 * 新加的 {@code warehouses.<行会id>.slots.<槽位>} 段（ItemStack 走 YamlConfiguration 的
 * setItemStack / getItemStack 序列化，与该文件现有读写风格一致；本文件无 tmp+rename 惯例，
 * 照 GuildService 原样直写）。
 *
 * <p>并发模型（v2.23.1 修 P0-2，照 {@link com.forgemaster.tomb.TombManager} 的 dirty + 异步冲刷模式）：
 * <b>逐格提交</b>。原先的做法是「点击 / 关闭时把整份 GUI 上层背包覆盖回内存态」，
 * 即所谓「后关者为准」——两人同时开仓时，A 取出物品（已进 A 背包）之后，B 手里那份
 * <i>旧视图</i>只要点一下或关个窗，就会把 A 取走的那一格<b>覆盖回仓库</b>（物品复制）；
 * 反过来 B 的旧视图里没有 A 的新存入，一覆盖就把 A 的东西抹掉（物品丢失）。</p>
 *
 * <p>现在每次只提交「本会话真的改动过的那一格」，并且写之前比对权威值：
 * {@link #commit} 要求调用方给出「这一格我上次看到的样子」（{@code expectedPrev}），
 * 与权威值不一致就判定为冲突、<b>拒绝写入</b>，由 GUI 层把权威值刷回去、把玩家的物品
 * 原样退还（绝不覆盖别人的改动）。{@link #snapshot} 保留只为兼容，路由已不再调用。</p>
 *
 * <p>容量：{@code guild.warehouse.rows}（默认 3，钳 <b>1~5</b> 行，每行 9 格）。
 * v2.23.1 修 P0-1：上限从 6 行改成 5 行，因为面板 49 号格是「关闭」按钮，
 * 6 行（54 格）时 49 号格会落进仓储区，被关闭图标覆盖 → 那格物品永久销毁。
 * 45 = 5×9 &lt; 49，仓储区与装饰/关闭槽从此不相交。配置写 6 会被钳成 5，
 * 被钳掉的第 6 行物品在下一次落盘时丢弃（容量即真理，不做溢出暂存）。</p>
 *
 * <p>权限：会长全权（存取皆可）；普通成员只能存，取出由
 * {@code guild.warehouse.member-withdraw}（默认 false）决定。判定收口在纯函数
 * {@link #canWithdraw(boolean, boolean)}，自检直接算。</p>
 */
public final class GuildWarehouse {

    private final ForgingMasterPlugin plugin;
    /** 行会 id → 仓库内容（真源；GUI Inventory 只是视图，快照后同步进来） */
    private final Map<String, ItemStack[]> stores = new LinkedHashMap<>();

    /**
     * 超出当前容量的老物品保留区（行会 id → 待归位物品）。
     *
     * <p>v2.23.0 的储物区上限是 6 行 54 格；v2.23.1 修 P0-1（关闭槽 49 被压掉）时把上限压到
     * 5 行 45 格。曾配 {@code rows: 6} 的服务器，第 45~53 格的物品在这条线上会被判「越界」。
     * 直接丢弃等于升级即吞物品，所以先进这里：随 {@link #write} 落盘到
     * {@code warehouses.<id>.overflow}，每次读盘与每次腾出空位时自动回流到空格。</p>
     */
    private final Map<String, List<ItemStack>> overflow = new LinkedHashMap<>();
    private volatile boolean dirty;

    public GuildWarehouse(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ 配置与纯函数

    /**
     * 行数钳制：1~5（纯函数，自检直接算）。
     *
     * <p>v2.23.1 修 P0-1：上限由 6 改成 5。面板固定 54 格且 49 号是「关闭」按钮，
     * 6 行 = 54 格会让 49 号格同时属于仓储区 → 每次开仓都被关闭图标覆盖，
     * 存在那格的物品永久销毁。5 行 = 45 格，恒小于 49，两者不相交。
     * 改这里之前先看 {@link GuildWarehouseGui} 的类注释不变量。</p>
     */
    public static int clampRows(int raw) {
        return Math.max(1, Math.min(GuildWarehouseGui.STORAGE_MAX_ROWS, raw));
    }

    /**
     * 取出权限判定（纯函数，自检直接算）：会长永远全权；普通成员由配置放行。
     *
     * @param isOwner                是否会长（本数据结构里只有会长一个职位，副会长暂无）
     * @param memberWithdrawAllowed  配置 {@code guild.warehouse.member-withdraw}
     */
    public static boolean canWithdraw(boolean isOwner, boolean memberWithdrawAllowed) {
        return isOwner || memberWithdrawAllowed;
    }

    /** 仓库行数（配置 {@code guild.warehouse.rows}，默认 3，钳 1~6） */
    public int rows() {
        return clampRows(plugin.settings().intOf("guild.warehouse.rows", 3));
    }

    /** 成员是否允许取出（配置 {@code guild.warehouse.member-withdraw}，默认 false） */
    public boolean memberWithdraw() {
        return plugin.settings().flag("guild.warehouse.member-withdraw", false);
    }

    /** 仓库总格数 = 行数 × 9 */
    public int capacity() {
        return rows() * 9;
    }

    // ------------------------------------------------------------ 内容存取

    /**
     * 取某行的仓库内容（懒建 + 按当前容量整形；容量缩小保留前缀、丢弃溢出）。
     * 返回的是内部数组，GUI 只读抄写；写入请走 {@link #commit}（逐格，带冲突校验）。
     */
    public synchronized ItemStack[] contents(String guildId) {
        int cap = capacity();
        ItemStack[] arr = stores.get(guildId);
        if (arr == null) {
            arr = new ItemStack[cap];
            stores.put(guildId, arr);
        } else if (arr.length != cap) {
            arr = Arrays.copyOf(arr, cap);
            stores.put(guildId, arr);
        }
        return arr;
    }

    /** 非空格数（面板用） */
    public synchronized int usage(String guildId) {
        ItemStack[] arr = stores.get(guildId);
        if (arr == null) {
            return 0;
        }
        int n = 0;
        for (ItemStack it : arr) {
            if (it != null && !it.getType().isAir()) {
                n++;
            }
        }
        return n;
    }

    /**
     * 读某一格的权威值（副本；空 / 空气返回 null）。冲突回滚时用它把权威值刷回 GUI。
     *
     * <p>v2.23.1 修 P0-2 新增。</p>
     */
    public synchronized ItemStack getSlot(String guildId, int slot) {
        ItemStack[] arr = contents(guildId);
        if (slot < 0 || slot >= arr.length) {
            return null;
        }
        ItemStack it = arr[slot];
        return (it == null || it.getType().isAir()) ? null : it.clone();
    }

    /**
     * 逐格提交（v2.23.1 修 P0-2）：<b>只写这一格</b>，并且写之前核对权威值。
     *
     * <p>规则：权威值必须仍等于 {@code expectedPrev}（调用方「上次看到的样子」）才写入；
     * 不相等说明别人在这期间改过这一格，直接拒写返回 {@code false}，
     * 让 GUI 层把权威值刷回去、把玩家的物品退还——<b>永不覆盖别人的改动</b>。</p>
     *
     * <p>这一条替换了原来「整仓覆盖 + 后关者为准」的快照，是复制 / 丢失两个方向
     * 同时被堵住的关键：只有真正被玩家改过、且没人抢在前面的那一格会落库。</p>
     *
     * @param expectedPrev 调用方上次看到的该格内容（null = 空格）
     * @param next         要写入的新内容（null = 清空）
     * @return true = 写入成功；false = 槽位越界或与别人的改动冲突（未写入任何东西）
     */
    public synchronized boolean commit(String guildId, int slot, ItemStack expectedPrev, ItemStack next) {
        ItemStack[] arr = contents(guildId);
        if (slot < 0 || slot >= arr.length) {
            return false;
        }
        ItemStack cur = arr[slot];
        ItemStack curN = (cur == null || cur.getType().isAir()) ? null : cur;
        ItemStack expN = (expectedPrev == null || expectedPrev.getType().isAir()) ? null : expectedPrev;
        if (!java.util.Objects.equals(curN, expN)) {
            return false; // 冲突：这一格已被别人改动，交给 GUI 层回滚
        }
        arr[slot] = (next == null || next.getType().isAir()) ? null : next.clone();
        markDirty();
        // 清空一格就顺手把保留区的老物品拉回来一件（升级后玩家一取东西就自动归位）
        if (arr[slot] == null) {
            drainOverflow(guildId, arr);
        }
        return true;
    }

    /**
     * 把保留区里的物品尽量塞回空格。
     *
     * <p>只在已经持有本对象锁的方法里调用（{@link #commit} / {@link #putFirstFree} /
     * {@link #read}）。全部归位后把该行会从保留表里摘掉，避免 {@code write} 反复写空段。</p>
     */
    private void drainOverflow(String guildId, ItemStack[] arr) {
        List<ItemStack> extra = overflow.get(guildId);
        if (extra == null || extra.isEmpty()) {
            return;
        }
        var it = extra.iterator();
        while (it.hasNext()) {
            ItemStack one = it.next();
            int free = -1;
            for (int i = 0; i < arr.length; i++) {
                if (arr[i] == null || arr[i].getType().isAir()) {
                    free = i;
                    break;
                }
            }
            if (free < 0) {
                break; // 还是满的，留着下次
            }
            arr[free] = one;
            it.remove();
            markDirty();
        }
        if (extra.isEmpty()) {
            overflow.remove(guildId);
        }
    }

    /**
     * 把物品塞进仓储区的第一个空格（v2.23.1 修 P0-2 的兜底路径）。
     *
     * <p>逐格提交遇到冲突、而操作者已经掉线（没法退背包）时用它，免得物品凭空消失。
     * 仓库满了返回 false，调用方负责记录日志。</p>
     */
    public synchronized boolean putFirstFree(String guildId, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return true;
        }
        ItemStack[] arr = contents(guildId);
        for (int i = 0; i < arr.length; i++) {
            ItemStack it = arr[i];
            if (it == null || it.getType().isAir()) {
                arr[i] = item.clone();
                markDirty();
                return true;
            }
        }
        // 仓库满：退一步塞给保留区（照样落盘），别让调用方以为成功却丢件
        List<ItemStack> extra = overflow.computeIfAbsent(guildId, k -> new ArrayList<>());
        extra.add(item.clone());
        markDirty();
        plugin.getLogger().warning("行会 " + guildId + " 仓库已满，1 件物品转入保留区（不会丢失）。");
        return false;
    }

    /**
     * 把 GUI 上层背包的前 capacity 格抄进内存态（<b>整仓覆盖</b>）。
     *
     * @deprecated v2.23.1 修 P0-2：整表覆盖是「后关者为准」的根源，会造成复制 / 丢失。
     *             点击与关闭路径已全部改成 {@link #commit} 逐格提交，路由不再调用本方法；
     *             保留签名只为不破坏既有调用点与自检。新代码请用 {@link #commit}。
     */
    @Deprecated
    public synchronized void snapshot(String guildId, Inventory inv) {
        ItemStack[] arr = contents(guildId);
        for (int i = 0; i < arr.length; i++) {
            ItemStack it = i < inv.getSize() ? inv.getItem(i) : null;
            arr[i] = (it == null || it.getType().isAir()) ? null : it.clone();
        }
        markDirty();
    }

    /** 行会解散时清掉它的仓库（物品不退还，解散提示由上层负责说明） */
    public synchronized void remove(String guildId) {
        if (stores.remove(guildId) != null) {
            markDirty();
        }
    }

    // ------------------------------------------------------------ 落盘

    public void markDirty() {
        dirty = true;
    }

    public boolean dirty() {
        return dirty;
    }

    /** GuildService.save() 成功落盘后清脏（沿用 TombManager 的「写完才清」语义） */
    public synchronized void clearDirty() {
        dirty = false;
    }

    /** 写进 guild-data.yml 的 warehouses 段（只写非空格） */
    public synchronized void write(YamlConfiguration yml) {
        for (Map.Entry<String, ItemStack[]> e : new LinkedHashMap<>(stores).entrySet()) {
            String base = "warehouses." + e.getKey();
            ItemStack[] arr = e.getValue();
            for (int i = 0; i < arr.length; i++) {
                ItemStack it = arr[i];
                if (it != null && !it.getType().isAir()) {
                    yml.set(base + ".slots." + i, it.clone());
                }
            }
            // 超容量的老物品（v2.23.0 时 rows 可到 6 = 54 格，v2.23.1 起上限 45 格）
            // 单独存一段，既不占用 GUI 索引，也不会在下次读盘时消失
            List<ItemStack> extra = overflow.get(e.getKey());
            if (extra != null && !extra.isEmpty()) {
                for (int i = 0; i < extra.size(); i++) {
                    yml.set(base + ".overflow." + i, extra.get(i).clone());
                }
            }
        }
    }

    /**
     * 从 guild-data.yml 读回 warehouses 段（坏数据跳格，不炸启动）。
     *
     * <p><b>超容量旧数据必须抢救，不能丢（v2.23.1）</b>：v2.23.0 允许多到 6 行（54 格），
     * 而 P0-1 的修复把储物区上限压到 5 行（45 格，给关闭槽腾地方）。若只是
     * {@code idx >= cap → 丢弃}，那么曾配 {@code rows: 6} 的服务器，第 45~53 格的老物品
     * 会在这次升级里静默蒸发。这里改为：先塞进当前空位；塞不下的进
     * {@code overflow} 保留区（随 {@link #write} 一起落盘），等仓库腾出空位
     * （{@link #putFirstFree} / {@link #commit} 清空一格）时自动回流。</p>
     */
    public synchronized void read(YamlConfiguration yml) {
        stores.clear();
        overflow.clear();
        ConfigurationSection root = yml.getConfigurationSection("warehouses");
        if (root == null) {
            return;
        }
        int cap = capacity();
        for (String id : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) {
                continue;
            }
            try {
                ItemStack[] arr = new ItemStack[cap];
                List<ItemStack> extra = new ArrayList<>();
                ConfigurationSection slots = sec.getConfigurationSection("slots");
                if (slots != null) {
                    for (String key : slots.getKeys(false)) {
                        int idx;
                        try {
                            idx = Integer.parseInt(key);
                        } catch (NumberFormatException ex) {
                            continue; // 坏键跳过
                        }
                        if (idx < 0) {
                            continue;
                        }
                        ItemStack it = slots.getItemStack(key);
                        if (it == null || it.getType().isAir()) {
                            continue;
                        }
                        if (idx < cap) {
                            arr[idx] = it;
                        } else {
                            // 超容量：进保留区，绝不丢弃
                            extra.add(it);
                        }
                    }
                }
                // 上一次运行已经进过保留区的，一并读回来
                ConfigurationSection ov = sec.getConfigurationSection("overflow");
                if (ov != null) {
                    for (String key : ov.getKeys(false)) {
                        ItemStack it = ov.getItemStack(key);
                        if (it != null && !it.getType().isAir()) {
                            extra.add(it);
                        }
                    }
                }
                // 先尝试把保留区物品塞进当前空位（升级后第一次读盘就自动归位）
                int failed = 0;
                for (ItemStack it : extra) {
                    int free = -1;
                    for (int i = 0; i < arr.length; i++) {
                        if (arr[i] == null || arr[i].getType().isAir()) {
                            free = i;
                            break;
                        }
                    }
                    if (free >= 0) {
                        arr[free] = it;
                    } else {
                        failed++;
                    }
                }
                stores.put(id, arr);
                if (failed > 0) {
                    // 仓库真的太满：留在保留区（照样落盘），并在控制台留一行可追溯记录
                    List<ItemStack> keep = new ArrayList<>();
                    for (ItemStack it : extra) {
                        if (it != null && !it.getType().isAir() && !containsIdentity(arr, it)) {
                            keep.add(it);
                        }
                    }
                    if (!keep.isEmpty()) {
                        overflow.put(id, keep);
                        plugin.getLogger().warning("行会 " + id + " 的仓库超出容量上限（" + cap
                                + " 格），" + keep.size() + " 件老物品已转入保留区（不会丢失，"
                                + "腾出空位后自动回流）。建议提醒玩家清仓。");
                    }
                }
            } catch (Exception ignored) {
                // 坏数据跳过，不影响启动
            }
        }
    }

    /**
     * 归位判定：容器里是否已存在「同一个对象引用」。
     *
     * <p>只用于 {@link #read} 里区分「刚塞进 arr 的那批」与「塞不下的那批」，
     * 因此比引用而不是 `equals`（`ItemStack.equals` 会把两件同样的剑视作同一个）。</p>
     */
    private static boolean containsIdentity(ItemStack[] arr, ItemStack target) {
        for (ItemStack it : arr) {
            if (it == target) {
                return true;
            }
        }
        return false;
    }
}
