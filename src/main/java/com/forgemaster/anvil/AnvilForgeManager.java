package com.forgemaster.anvil;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.strengthen.StrengthenService;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 铁砧强化台（v2.16，ForgeTax 式）。
 *
 * <p>玩法只有三步，全部在方块上完成，没有界面：</p>
 * <ol>
 *   <li><b>右键铁砧</b>（主手拿锻造品）：把剑放上去，手上那把收走；砧子上方浮起
 *       剑的 ItemDisplay 与一块 TextDisplay，写清「当前等级 / 成功率 / 碎裂率 / 连击」。</li>
 *   <li><b>手持重锤左键敲</b>：每敲一次吃一次材料 + 剑币，按明牌概率掷骰；
 *       <b>按住 Shift 左键</b>则批量落锤（v2.23），连续敲到上限 / 碎 / 满 / 断材料为止。</li>
 *   <li><b>再右键</b>：把剑取回来（背包满就掉在砧上）。</li>
 * </ol>
 *
 * <p><b>不丢剑</b>：砧位状态落盘到 {@code anvils.yml}，关服 / 重载后原样恢复；
 * 方块被拆、被炸、会话超时都会把剑还给主人（人不在线就掉在砧上，不吞）。</p>
 */
public final class AnvilForgeManager {

    /** 铁砧 / 开裂铁砧 / 破损铁砧都算砧位 */
    public static boolean isAnvilBlock(Material type) {
        return type == Material.ANVIL
                || type == Material.CHIPPED_ANVIL
                || type == Material.DAMAGED_ANVIL;
    }

    private final ForgingMasterPlugin plugin;
    private final Map<String, AnvilForgeSession> sessions = new LinkedHashMap<>();
    private final File file;
    /**
     * 有未落盘变更。
     *
     * <p>v2.23（P2-2）起改走「标脏 + 异步冲刷」：主线程动作路径只置位，真正的写盘
     * 交给低频异步冲刷任务（参照 TombManager 的 dirty 模式），所以要用 volatile
     * 让异步线程看到最新值。</p>
     */
    private volatile boolean dirty;

    /**
     * 脏代数：每次结构变更（放剑 / 取剑 / 碎剑 / 归还 / 载入）自增。
     *
     * <p><b>为什么需要它（v2.23.1 修 P0）</b>：{@code saveTo} 原先在进入方法时拍快照，
     * 写完无条件 {@code dirty = false}。若写盘窗口（几十 ms）内玩家把剑取回，这次变更
     * 会被那一行清掉 —— 文件里留着「砧位上有剑」、内存里剑已在背包，重启后就是复制。
     * 反向（刚放上就崩）则是吞。用代数比较代替布尔：只有「写完时代数没再变过」才清脏。</p>
     */
    private volatile long dirtySeq;

    /** 结构变更的统一入口：置脏并把代数加一（调用方必须已持有本对象锁，或位于主线程的加锁块内） */
    private void markDirty() {
        dirty = true;
        dirtySeq++;
    }

    /** 放会话进表（与异步冲刷的「拍快照」段互斥，v2.23.1 修 P0） */
    private void putSession(AnvilForgeSession s) {
        synchronized (this) {
            sessions.put(s.key(), s);
            markDirty();
        }
    }

    /** 从表里删会话（与异步冲刷的「拍快照」段互斥）；真的删掉了才算一次变更 */
    private void removeSession(String key) {
        synchronized (this) {
            if (sessions.remove(key) != null) {
                markDirty();
            }
        }
    }
    /** 异步冲刷任务 id；-1 = 没在跑 */
    private int flushTaskId = -1;

    /**
     * 每 3 秒方块校验心跳的任务 id；-1 = 没在跑。
     *
     * <p><b>为什么要存句柄（v2.23.1 修 P0）</b>：{@code start()} 原先直接
     * {@code runTaskTimer(...)} 丢弃返回值，{@code stop()} 只停冲刷任务，这个心跳永远取消不掉。
     * 而 {@code /zhujian reload} 走 {@code stop(); start();}，于是每点一次 reload 就永久多挂
     * 一个每 3 秒执行的心跳：多份心跳重复 despawn 全息、重复把 dirty 置位，
     * reload 越多次，砧位刷新与落盘频率越高。</p>
     */
    private int tickTaskId = -1;

    /**
     * 正在演出的落锤 ItemDisplay 登记表（静态）。
     *
     * <p>为什么是静态表：落锤演出的 BukkitRunnable 在插件被 /reload 或 PlugMan 卸载时
     * 只会被取消，已经 spawn 的锤子 ItemDisplay 不会有人 remove——它会以非持久实体的
     * 形态悬浮在已加载区块里，直到区块卸载（audit 02-stability #9）。
     * 演出实体生成时登记到这里，{@link #stop()} 里统一 remove，卸载不留幽灵锤。</p>
     */
    private static final java.util.Set<ItemDisplay> ACTIVE_STRIKE_HAMMERS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public AnvilForgeManager(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "anvils.yml");
    }

    // ------------------------------------------------------------ 生命周期

    public void start() {
        load();
        // 实体要在世界 / 区块就绪之后再生成，晚一拍更稳
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (AnvilForgeSession s : new ArrayList<>(sessions.values())) {
                refresh(s, true);
            }
        }, 2L);
        // 每 3 秒校一次：方块被拆 / 区块卸载导致实体丢失 / 会话超时
        // 句柄必须留住：start() 会被 reloadAll() 反复调用，丢句柄就等于每次 reload 多挂一条心跳
        stopTickTask();
        tickTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 60L, 60L).getTaskId();
        // v2.23（P2-2）：anvils.yml 改异步周期冲刷，主线程动作路径不再同步写盘
        startAutoFlush();
    }

    /** 停掉 3 秒心跳（不落盘；落盘在 {@link #stop()} 里统一做） */
    private void stopTickTask() {
        if (tickTaskId != -1) {
            Bukkit.getScheduler().cancelTask(tickTaskId);
            tickTaskId = -1;
        }
    }

    /**
     * <b>仅自检用</b>：当前 3 秒心跳的任务 id（-1 = 没在跑）。
     *
     * <p>存在的唯一理由是让自检能断言「{@code /zhujian reload} 不会每次多挂一条心跳」
     * 这条回归（v2.23.1 修 P0：start() 原先丢弃 runTaskTimer 的返回值）。</p>
     */
    public int debugTickTaskId() {
        return tickTaskId;
    }

    /** <b>仅自检用</b>：当前异步冲刷任务 id（-1 = 没在跑） */
    public int debugFlushTaskId() {
        return flushTaskId;
    }

    public void stop() {
        // 关服同步兜底：停掉异步冲刷后强制落一次盘，最多漏异步窗口内的最后一笔
        stopTickTask();
        stopFlushTask();
        save();
        for (AnvilForgeSession s : sessions.values()) {
            despawn(s);
        }
        sessions.clear();
        // 演出锤子统一回收：插件禁用时 Bukkit 只取消任务，已生成的实体得在这里补一刀
        for (ItemDisplay hammer : ACTIVE_STRIKE_HAMMERS) {
            if (hammer != null && hammer.isValid()) {
                hammer.remove();
            }
        }
        ACTIVE_STRIKE_HAMMERS.clear();
    }

    /** 供命令 / 自检只读 */
    public Collection<AnvilForgeSession> sessions() {
        return List.copyOf(sessions.values());
    }

    public AnvilForgeSession sessionAt(Block block) {
        return block == null ? null : sessions.get(AnvilForgeSession.keyOf(block));
    }

    public int size() {
        return sessions.size();
    }

    // ------------------------------------------------------------ 放 / 取

    /**
     * 核心放剑：只登记砧位 + 生成全息，<b>不碰玩家背包</b>。
     *
     * <p>拆出来是为了两件事能共用同一套逻辑：{@link #place} 负责「从手上拿掉」，
     * 自检负责「不靠玩家也能验全息」。两边都走这里，不会出现「自检验过的和平常跑的不是一条路」。</p>
     *
     * @return 新建的会话；这一格已被占用或剑不合法时返回 null
     */
    public AnvilForgeSession placeCore(Block block, UUID owner, String ownerName, ItemStack sword) {
        if (block == null || !isAnvilBlock(block.getType())) {
            return null;
        }
        if (sword == null || sword.getType().isAir()) {
            return null;
        }
        StrengthenService st = plugin.strengthen();
        if (st == null || st.levelOf(sword) < 0) {
            return null;
        }
        String key = AnvilForgeSession.keyOf(block);
        if (sessions.containsKey(key)) {
            return null;
        }
        ItemStack copy = sword.clone();
        copy.setAmount(1);
        AnvilForgeSession session = new AnvilForgeSession(
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                owner, ownerName == null ? "" : ownerName, copy, System.currentTimeMillis());
        synchronized (this) {
            sessions.put(key, session);
            markDirty();
        }
        refresh(session, true);
        return session;
    }

    /**
     * 右键把剑放上铁砧。
     *
     * @return 给玩家看的一句话（MiniMessage）
     */
    public String place(Player player, Block block, ItemStack inHand) {
        if (!enabled()) {
            return "<red>铁砧强化已关闭。";
        }
        StrengthenService st = plugin.strengthen();
        if (inHand == null || inHand.getType().isAir()) {
            return "<gray>手上先拿一把锻造好的剑。";
        }
        if (st == null || st.levelOf(inHand) < 0) {
            return "<red>这不是锻造品，砧子不认。";
        }
        if (st.materialOf(inHand) == null || st.qualityOf(inHand) == null) {
            return "<red>这把剑的胚子信息丢了，先别放上来。";
        }
        String key = AnvilForgeSession.keyOf(block);
        AnvilForgeSession old = sessions.get(key);
        if (old != null) {
            if (!old.owner().equals(player.getUniqueId()) && ownerOnly()) {
                return "<red>这个砧位上有 <white>" + old.ownerName() + "</white> 的剑，别动。";
            }
            return "<gray>砧上已经有一把剑了，右键把它取回来。";
        }
        // 老物品的上限可能还是旧版本写死的（神话剑顶着 32），上砧前先按品质同步一次，
        // 否则这里会拿旧的 stored 值判「已满」或者 Lore 还写着错的上限
        if (st.ensureCap(inHand)) {
            player.getInventory().setItemInMainHand(inHand);
        }
        int level = st.levelOf(inHand);
        int cap = st.capOf(inHand);
        if (level >= cap) {
            return "<gold>这把已经 +" + cap + " 满了，砧子敲不动了。";
        }

        ItemStack sword = inHand.clone();
        sword.setAmount(1);
        // 从手上拿掉（数量 >1 时只拿一把，剩下的留在手上）
        if (inHand.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            inHand.setAmount(inHand.getAmount() - 1);
        }

        AnvilForgeSession session = placeCore(block, player.getUniqueId(), player.getName(), sword);
        if (session == null) {
            // 登记失败（并发或方块变了）：把剑还给玩家，绝不吃掉
            player.getInventory().addItem(sword).values()
                    .forEach(left -> block.getWorld()
                            .dropItemNaturally(block.getLocation().add(0.5, 1.0, 0.5), left));
            return "<red>砧位被占了，剑还给你。";
        }

        player.playSound(block.getLocation().add(0.5, 0.6, 0.5), Sound.BLOCK_ANVIL_PLACE, 0.7f, 1.35f);
        String od = boardLine(session);
        return "<green>剑搭上砧了。</green><newline>" + od
                + "<newline><dark_gray>手持重锤 <white>左键</white> 落锤 · <white>右键</white> 取回</dark_gray>";
    }

    /**
     * 右键把剑取回来。
     *
     * @return 给玩家看的一句话；砧上没剑返回 null（调用方不该拦事件）
     */
    public String take(Player player, Block block) {
        AnvilForgeSession session = sessionAt(block);
        if (session == null) {
            return null;
        }
        if (!session.owner().equals(player.getUniqueId()) && ownerOnly()) {
            return "<red>这是 <white>" + session.ownerName() + "</white> 的砧位。";
        }
        ItemStack sword = session.sword();
        removeSession(session.key());
        despawn(session);
        giveBack(player, sword, block);
        player.playSound(block.getLocation().add(0.5, 0.6, 0.5), Sound.ITEM_ARMOR_EQUIP_IRON, 0.8f, 1.1f);
        return "<gray>剑取回来了。";
    }

    /** 把剑塞回玩家背包；塞不下就掉在砧上，绝不吞 */
    private void giveBack(Player player, ItemStack sword, Block block) {
        if (sword == null || sword.getType().isAir()) {
            return;
        }
        var leftovers = player.getInventory().addItem(sword);
        if (!leftovers.isEmpty()) {
            for (ItemStack left : leftovers.values()) {
                block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 1.0, 0.5), left);
            }
            Msg.prefix(player, "<yellow>背包满了，剑掉在砧子上了。");
        }
    }

    // ------------------------------------------------------------ 落锤

    /**
     * 手持重锤左键敲铁砧（单锤）。
     *
     * @return 给玩家看的结果文本；砧上没剑返回 null
     */
    public String strike(Player player, Block block) {
        AnvilForgeSession session = sessionAt(block);
        if (session == null) {
            return null;
        }
        if (!session.owner().equals(player.getUniqueId()) && ownerOnly()) {
            return "<red>这是 <white>" + session.ownerName() + "</white> 的砧位。";
        }
        long now = System.currentTimeMillis();
        long cd = cooldownMs();
        if (cd > 0 && now - session.lastStrikeAt() < cd) {
            // 按住左键时客户端会一直发 LEFT_CLICK_BLOCK，这里必须完全静默，
            // 否则一秒能刷三四条「锤子还没抬起来」，比不提示还烦。
            return null;
        }
        session.lastStrikeAt(now);
        player.swingMainHand();

        Settled s = settle(player, block, session, true);
        StrengthenService.StrikeResult result = s.result();
        if (result.tier() == StrengthenService.Tier.REFUSED) {
            return result.message();
        }
        if (s.broken() || !sessions.containsKey(session.key())) {
            // 剑已经没了：绝不能再 refresh，否则会给一个已注销的会话重新生成幽灵全息
            return result.message();
        }
        // 逐锤的结果只上物品栏上方（不刷聊天），聊天里只留里程碑
        player.sendActionBar(Msg.mm(oneLine(result)));
        return milestone(result) ? result.message() : null;
    }

    /**
     * 批量落锤（v2.23 P1-1）：按住 Shift + 手持重锤左键铁砧触发。
     *
     * <p>连续落锤直到任一条件终止（{@link #batchStopAfter} 纯函数判定）：
     * ① 达到 {@code anvil-forge.batch-max} 上限；② 剑真碎（砧位当场清空）；
     * ③ 强化到顶（level ≥ cap）；④ 材料不够（REFUSED）。</p>
     *
     * <p>批量循环里每锤之间<b>跳过冷却</b>——冷却只为防手抖连点，批量是玩家主动的
     * 选择，不需要再防抖。逐锤结果只上 ActionBar（第 N/总数 锤 · 单锤变化），
     * 结束后一条聊天总结（总锤数 / 等级变化 / 消耗）。演出动画只播最后一锤：
     * 每锤都播 12 tick 动画，前后几把锤子会同时在砧上飞，串成一锅粥。</p>
     */
    public void strikeBatch(Player player, Block block) {
        AnvilForgeSession session = sessionAt(block);
        if (session == null) {
            return;
        }
        if (!session.owner().equals(player.getUniqueId()) && ownerOnly()) {
            Msg.prefix(player, "<red>这是 <white>" + session.ownerName() + "</white> 的砧位。");
            return;
        }
        StrengthenService st = plugin.strengthen();
        int max = batchMax();
        int startLevel = Math.max(0, st.levelOf(session.sword()));
        int endLevel = startLevel;
        long coins = 0;
        int materials = 0;
        int done = 0;
        BatchStop stop = null;
        StrengthenService.StrikeResult last = null;
        AnvilForgeSession lastSession = null;

        for (int i = 1; i <= max; i++) {
            // v2.25.1 修 P2（审计轮3-8）：批量最长 50 锤，慢服下结算 IO 可跨多个 tick，
            // 玩家中途退服时继续扣钱只是白扣 —— 每锤开头哨兵，退服即安静收尾。
            if (!player.isOnline()) {
                break;
            }
            AnvilForgeSession cur = sessionAt(block);
            if (cur == null) {
                // 砧位在循环中途没了（并发拆砧 / 超时回收）：按碎了收尾，不再补锤
                stop = done > 0 ? BatchStop.BROKEN : null;
                break;
            }
            int cap = st.capOf(cur.sword());
            int level = Math.max(0, st.levelOf(cur.sword()));
            stop = batchStopBefore(i, max, level, cap);
            if (stop != null) {
                break;
            }
            // 批量跳过冷却：冷却只为防手抖连点（见方法注释）；照样刷新时间戳，
            // 收手之后紧跟着的单锤路径也不会立刻连点
            cur.lastStrikeAt(System.currentTimeMillis());
            player.swingMainHand();
            Settled s = settle(player, block, cur, false);
            StrengthenService.StrikeResult r = s.result();
            if (r.tier() == StrengthenService.Tier.REFUSED) {
                // 材料不够：这一锤没掷骰也没消耗，不计入总数
                stop = BatchStop.REFUSED;
                break;
            }
            done++;
            coins += Math.max(0, r.coins());
            materials += Math.max(0, r.materials());
            last = r;
            lastSession = s.broken() ? null : cur;
            // 逐锤结果只上 ActionBar，格式「第 N/总数 锤 · 单锤变化」
            player.sendActionBar(Msg.mm(batchLine(i, max, r)));
            stop = batchStopAfter(done, max, s.broken(),
                    false, st.levelOf(cur.sword()) >= st.capOf(cur.sword()));
            if (stop != null) {
                if (stop == BatchStop.BROKEN) {
                    lastSession = null; // 真碎：没有「最后一锤的全息」可演了
                } else {
                    endLevel = Math.max(0, st.levelOf(cur.sword()));
                }
                break;
            }
            endLevel = Math.max(0, st.levelOf(cur.sword()));
        }

        // 演出只播最后一锤（纯视觉）：中间每锤都播 12 tick 动画会互相串帧
        if (last != null && lastSession != null && sessions.containsKey(lastSession.key())) {
            playStrikeAnimation(lastSession, block, last.tier());
        }

        Msg.prefix(player, batchSummary(stop, done, max, startLevel, endLevel, coins, materials));
    }

    /** 批量单锤的 ActionBar 行：第 N/总数 锤 · 单锤结果 */
    private String batchLine(int index, int max, StrengthenService.StrikeResult r) {
        return "<gray>第 <white>" + index + "</white>/<white>" + max + "</white> 锤</gray>"
                + "<dark_gray> · </dark_gray>" + oneLine(r);
    }

    /** 批量结束的聊天总结：总锤数 / 等级变化 / 消耗（一条，不复述每一锤） */
    private String batchSummary(BatchStop stop, int done, int max,
                                int startLevel, int endLevel, long coins, int materials) {
        String head;
        if (stop == BatchStop.BROKEN) {
            head = "<dark_red>批量落锤中断：剑碎了</dark_red>";
        } else if (stop == BatchStop.CAP) {
            head = "<gold>批量落锤收工：已强化到顶</gold>";
        } else if (stop == BatchStop.REFUSED) {
            head = "<yellow>批量落锤收工：材料不够，先停了</yellow>";
        } else if (stop == BatchStop.MAX) {
            head = "<gold>批量落锤收工：已达批量上限 " + max + " 锤</gold>";
        } else {
            head = "<gray>批量落锤收工</gray>";
        }
        StringBuilder sb = new StringBuilder(head)
                .append("<gray> · 共 <white>").append(done).append("</white> 锤");
        if (stop == BatchStop.BROKEN) {
            sb.append(" · <dark_red>残骸已进剑冢流程，砧位清空</dark_red>");
        } else {
            sb.append(" · 等级 <white>+").append(startLevel).append("</white>→<white>+")
                    .append(endLevel).append("</white>");
        }
        if (coins > 0) {
            sb.append(" · 消耗剑币 <yellow>").append(coins).append("</yellow>");
        }
        if (materials > 0) {
            sb.append(" · 消耗材料 <yellow>").append(materials).append("</yellow>");
        }
        return sb.append("</gray>").toString();
    }

    /** 批量终止原因 */
    public enum BatchStop {
        /** 达到批量上限（anvil-forge.batch-max） */
        MAX,
        /** 剑真碎（砧位已清空，剑进了剑冢流程） */
        BROKEN,
        /** 强化到顶（level ≥ cap） */
        CAP,
        /** 材料不够（REFUSED，没掷骰没消耗） */
        REFUSED
    }

    /**
     * 纯函数：批量第 {@code i} 锤<b>开敲前</b>的终止判断（i 从 1 起）；null = 可敲。
     *
     * <p>已到顶的剑没得敲，直接以 CAP 收尾，不浪费一次掷骰与材料检查。</p>
     */
    public static BatchStop batchStopBefore(int i, int max, int level, int cap) {
        if (level >= cap) {
            return BatchStop.CAP;
        }
        if (i > max) {
            return BatchStop.MAX;
        }
        return null;
    }

    /**
     * 纯函数：第 {@code strikesDone} 锤<b>结算后</b>的终止判断；null = 继续下一锤。
     *
     * <p>优先级：材料不够 &gt; 碎裂 &gt; 到顶 &gt; 批量上限——保命与保材料永远优先于敲满。</p>
     */
    public static BatchStop batchStopAfter(int strikesDone, int max, boolean broken,
                                           boolean refused, boolean atCap) {
        if (refused) {
            return BatchStop.REFUSED;
        }
        if (broken) {
            return BatchStop.BROKEN;
        }
        if (atCap) {
            return BatchStop.CAP;
        }
        if (strikesDone >= max) {
            return BatchStop.MAX;
        }
        return null;
    }

    /**
     * 批量上限（anvil-forge.batch-max）：默认 10，钳制在 1..50。
     *
     * <p>上限的意义是「一次按住 Shift 的最长连击」，不是资源锁——真正的成本
     * 每锤都在 {@code StrengthenService.strike} 里按明牌结算。</p>
     */
    public int batchMax() {
        return clampBatchMax(plugin.settings().intOf("anvil-forge.batch-max", 10));
    }

    /** 纯函数：批量上限钳制（自检直接断言这里，不读配置） */
    public static int clampBatchMax(int raw) {
        return Math.max(1, Math.min(50, raw));
    }

    /**
     * 单锤结算的共享核心：冷却 / 提示层之外的全部动作（掷骰、音效、演出、落账、全息刷新）。
     *
     * <p>抽出来是为了单锤路径和批量路径共用同一套判定，不复制粘贴整套结算。
     * 批量模式传 {@code playAnim = false}，演出由批量循环在结束时只播最后一锤。</p>
     */
    private Settled settle(Player player, Block block, AnvilForgeSession session, boolean playAnim) {
        ItemStack sword = session.sword();
        boolean[] broken = {false};
        StrengthenService.StrikeResult result = plugin.strengthen().strike(player, sword,
                gone -> {
                    // 剑碎了：砧位当场清空，全息撤掉
                    broken[0] = true;
                    removeSession(session.key());
                    despawn(session);
                });

        if (result.tier() == StrengthenService.Tier.REFUSED) {
            player.playSound(block.getLocation().add(0.5, 0.7, 0.5), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
            return new Settled(result, false);
        }

        playStrikeSound(player, block, result);
        // 演出：锤子砸下 + 火花（纯视觉，紧跟着这一锤的结果，不改判定）
        if (playAnim) {
            playStrikeAnimation(session, block, result.tier());
        }
        if (!broken[0] && sessions.containsKey(session.key())) {
            session.sword(sword);
            markDirty();
            // 全息跟着等级 / 概率一起变
            refresh(session, true);
        }
        return new Settled(result, broken[0]);
    }

    /** 单锤结算的内部结果：result + 剑是否当场真碎（真碎时砧位已被清空） */
    private record Settled(StrengthenService.StrikeResult result, boolean broken) {
    }

    /** 逐锤结果的精简单行，给 ActionBar 用 */
    private String oneLine(StrengthenService.StrikeResult r) {
        String head = switch (r.tier()) {
            case SUCCESS -> "<green>+1</green>";
            case GREAT -> "<gold>大成 +2</gold>";
            case NO_LOSS -> "<#7ef2c8>无损连击 +1</#7ef2c8>";
            case DAMAGE -> "<red>损伤 −1</red>";
            case HOLD -> "<gray>空锤</gray>";
            case BREAK -> "<dark_red>碎了</dark_red>";
            default -> "<gray>—</gray>";
        };
        String combo = r.threshold() > 0
                ? " <dark_gray>·</dark_gray> <gray>连击 " + r.combo() + "/" + r.threshold() + "</gray>"
                : "";
        String lv = r.toLevel() == r.fromLevel()
                ? " <dark_gray>+</dark_gray><white>" + r.toLevel() + "</white>"
                : " <dark_gray>+</dark_gray><white>" + r.fromLevel() + "</white><dark_gray>→</dark_gray><white>+"
                        + r.toLevel() + "</white>";
        return head + lv + combo;
    }

    /** 哪些结果值得占一行聊天（其余只走 ActionBar，免得敲二十下刷二十行） */
    private boolean milestone(StrengthenService.StrikeResult r) {
        return switch (r.tier()) {
            case BREAK, GREAT, NO_LOSS -> true;
            default -> r.noLossArmed() || r.toLevel() >= r.cap();
        };
    }

    private void playStrikeSound(Player player, Block block, StrengthenService.StrikeResult r) {
        Location at = block.getLocation().add(0.5, 0.7, 0.5);
        switch (r.tier()) {
            case BREAK -> {
                player.playSound(at, Sound.ENTITY_ITEM_BREAK, 1.0f, 0.55f);
                player.playSound(at, Sound.BLOCK_ANVIL_BREAK, 0.9f, 0.7f);
            }
            case GREAT, NO_LOSS -> {
                player.playSound(at, Sound.BLOCK_ANVIL_USE, 1.0f, 1.5f);
                player.playSound(at, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.6f);
            }
            case SUCCESS -> player.playSound(at, Sound.BLOCK_ANVIL_USE, 0.9f, 1.2f);
            case DAMAGE, HOLD -> player.playSound(at, Sound.BLOCK_ANVIL_LAND, 0.9f, 0.65f);
            default -> {
            }
        }
    }

    // ------------------------------------------------------------ 全息

    /** 刷新一个砧位的全息（剑实体 + 文本实体） */
    public void refresh(AnvilForgeSession session, boolean force) {
        Location base = session.above(height());
        if (base == null) {
            return;
        }
        World world = base.getWorld();
        if (world == null) {
            return;
        }
        // 区块没加载就先不生成，区块加载事件里会补
        if (!world.isChunkLoaded(base.getBlockX() >> 4, base.getBlockZ() >> 4)) {
            return;
        }
        String text = boardText(session);
        if (!force && text.equals(session.lastText())) {
            return;
        }
        session.lastText(text);

        // ---- 剑 ----
        // 平躺在砧面上：用 GROUND 姿态（掉落物那种平放），不是 FIXED（那会把剑立起来像插在砧子里）。
        Location swordAt = session.above(swordHeight());
        ItemDisplay sword = resolve(session.swordDisplay(), ItemDisplay.class);
        if (sword == null || !sword.isValid()) {
            sword = world.spawn(swordAt, ItemDisplay.class, d -> {
                d.setItemStack(session.sword() == null ? new ItemStack(Material.AIR) : session.sword());
                d.setBillboard(Display.Billboard.FIXED);
                d.setItemDisplayTransform(swordTransform());
                d.setGravity(false);
                d.setInvulnerable(true);
                d.setPersistent(false);
                d.setShadowRadius(0f);
                d.setShadowStrength(0f);
                d.setViewRange(0.8f);
                applySwordPose(d, session.sword(), 1.0);
                tag(d, session);
            });
            session.swordDisplay(sword.getUniqueId());
        } else {
            ItemStack shown = session.sword() == null ? new ItemStack(Material.AIR) : session.sword();
            // 每锤都 setItemStack 会让客户端把模型整个重建一遍（看起来在闪）。
            // 剑的外观材质不会随等级变，只有 Lore 变，所以材质没换就不重设。
            if (sword.getItemStack().getType() != shown.getType()) {
                sword.setItemStack(shown);
            }
            sword.teleport(swordAt);
            // 剑随等级长大：每锤之后也要把新的大小写回去，光换 itemStack 不会变尺寸
            applySwordPose(sword, session.sword(), 1.0);
        }

        // ---- 文本 ----
        Location textAt = session.above(height());
        TextDisplay label = resolve(session.textDisplay(), TextDisplay.class);
        if (label == null || !label.isValid()) {
            label = world.spawn(textAt, TextDisplay.class, d -> {
                d.text(Msg.mm(text));
                d.setBillboard(Display.Billboard.CENTER);
                d.setGravity(false);
                d.setInvulnerable(true);
                d.setPersistent(false);
                d.setSeeThrough(false);
                d.setShadowed(true);
                d.setAlignment(TextDisplay.TextAlignment.CENTER);
                d.setViewRange(1.0f);
                d.setBackgroundColor(org.bukkit.Color.fromARGB(0x66000000));
                tag(d, session);
            });
            session.textDisplay(label.getUniqueId());
        } else {
            label.text(Msg.mm(text));
            label.teleport(textAt);
        }
    }

    /** 剑平放在砧面上时离方块底面多高（砧面在 y+1.0 附近） */
    public double swordHeight() {
        return Math.max(0.5, Math.min(2.0, plugin.settings().num("anvil-forge.display.sword-height", 1.03)));
    }

    /**
     * 老配置迁移：v2.16.1 之前没有 {@code sword-transform} 这个键，剑一律走原版
     * {@code GROUND} 姿态——模型先被缩到 0.5、再下沉 2/16 格，看着不像「剑平躺在砧面上」，
     * 倒像插进砧子里。检测到老配置就补上新键并落盘；服主自己改过的值一个字都不动。
     *
     * @deprecated 实现已收编进统一迁移层
     * {@code com.forgemaster.config.ConfigMigrations}（audit/04-quality.md M1），
     * 启动时由 {@code ConfigMigrations.run} 统一按 step 跑（step4..step10）。
     * 保留本方法只为兼容外部直接调用：行为不变，内部委托给
     * {@code ConfigMigrations.migrateDisplayPose(plugin)}。
     */
    @Deprecated
    public void migrateDisplayConfig() {
        com.forgemaster.config.ConfigMigrations.migrateDisplayPose(plugin);
    }

    /**
     * 剑的展示姿态（用哪套原版模型变换）。
     *
     * <p>{@code NONE}（默认）＝ 角度全部自己算：模型按图标姿态立在 XY 平面、朝 +Z，
     * 再绕 X 轴扳倒 90° 就平躺在砧面上；大小 / 朝向完全由
     * {@code sword-scale / sword-yaw / sword-pitch} 决定，不受原版模型里
     * {@code display.ground} 的干扰——那套会先缩到一半，再往下沉 2/16 格，
     * 结果剑看着像插进砧子里，而不是躺在砧面上。</p>
     *
     * <p>{@code GROUND} ＝ v2.16 的老行为（掉落物姿态，小一号）；
     * {@code FIXED} ＝ 立着（只有调试时才用）。</p>
     */
    public ItemDisplay.ItemDisplayTransform swordTransform() {
        String raw = plugin.settings().str("anvil-forge.display.sword-transform", "NONE");
        try {
            return ItemDisplay.ItemDisplayTransform.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return ItemDisplay.ItemDisplayTransform.NONE;
        }
    }

    /** 剑在砧面上的朝向（角度）：90 = 与 X 轴垂直躺着，玩家从正面看是横着的 */
    public double swordYaw() {
        return plugin.settings().num("anvil-forge.display.sword-yaw", 90);
    }

    /**
     * 剑的俯仰：{@code NONE} 姿态下 -90 = 把立着的模型扳倒成平躺（默认）；
     * {@code GROUND} 姿态本就平放，默认 0。
     */
    public double swordPitch() {
        double def = swordTransform() == ItemDisplay.ItemDisplayTransform.NONE ? -90 : 0;
        return plugin.settings().num("anvil-forge.display.sword-pitch", def);
    }

    /** 剑的大小：{@code NONE} 姿态没有原版那层 0.5 缩放，默认 0.95 刚好躺在砧面上 */
    public double swordScale() {
        double def = swordTransform() == ItemDisplay.ItemDisplayTransform.NONE ? 0.95 : 0.70;
        return Math.max(0.2, Math.min(2.0, plugin.settings().num("anvil-forge.display.sword-scale", def)));
    }

    /**
     * 锻到上限时的剑大小：敲得越多，剑在砧面上越厚实。
     *
     * <p>v2.22 起只在剑体放大关闭（回退老曲线）时生效；统一曲线的上限走
     * {@code strengthen.big-sword.max-scale}。</p>
     */
    public double swordScaleMax() {
        return Math.max(swordScale(),
                plugin.settings().num("anvil-forge.display.sword-scale-max", 1.30));
    }

    /**
     * 这把剑此刻在砧面上该多大。
     *
     * <p>v2.22 起<b>与手上大剑共用同一条成长曲线</b>（{@code strengthen.big-sword.*} 的
     * 里程碑翻倍曲线）：{@code sword-scale × 曲线倍数}。同一把剑在砧上多大，
     * 取回拿在手里就多大——「取回以后拿在主手也是一样巨大的剑」靠的就是这一致性。
     * 剑体放大功能关闭时回退 v2.17.1 的老曲线（{@code sword-scale-max} 按
     * {@code level/cap} 的 SQRT/LINEAR 插值）。</p>
     *
     * <p>只影响外观，不碰任何数值。</p>
     */
    public double swordScaleFor(ItemStack sword) {
        double base = swordScale();
        com.forgemaster.strengthen.StrengthenService st = plugin.strengthen();
        if (sword == null || st == null) {
            return base;
        }
        var bs = plugin.swordGrowth();
        if (bs != null && bs.enabled()) {
            // 统一曲线：起点（min-level）、加倍点、上限全部来自 strengthen.big-sword.*
            int level = Math.max(0, st.levelOf(sword));
            return base * bs.scale().at(level);
        }
        double max = swordScaleMax();
        int cap = st.capOf(sword);
        int level = Math.max(0, st.levelOf(sword));
        double raw = cap <= 0 ? 0 : Math.max(0, Math.min(1, level / (double) cap));
        return base + (max - base) * growthCurve(raw);
    }

    /** 等级进度 → 大小进度：默认开方（前段长得快、后段收窄），可切回线性。仅回退曲线使用 */
    public double growthCurve(double raw) {
        if (raw <= 0) {
            return 0;
        }
        if (raw >= 1) {
            return 1;
        }
        String curve = plugin.settings().str("anvil-forge.display.sword-growth-curve", "SQRT");
        boolean linear = curve != null && curve.trim().equalsIgnoreCase("LINEAR");
        return linear ? raw : Math.sqrt(raw);
    }

    /** 把姿态（平躺角度 + 当前大小）写到剑实体上；剑长大了也走这里 */
    private void applySwordPose(ItemDisplay display, ItemStack sword, double scaleMul) {
        float scale = (float) (swordScaleFor(sword) * scaleMul);
        Quaternionf left = new Quaternionf()
                .rotateY((float) Math.toRadians(swordYaw()))
                .rotateX((float) Math.toRadians(swordPitch()));
        display.setTransformation(new Transformation(
                new Vector3f(0f, 0f, 0f),
                left,
                new Vector3f(scale, scale, scale),
                new Quaternionf()));
    }

    // ------------------------------------------------------------ 落锤演出

    /** 演出里那把锤子的外观材质：默认重锤（MACE），别再拿斧头糊弄 */
    public Material hammerDisplayMaterial() {
        String raw = plugin.settings().str("anvil-forge.animation.hammer-item", "MACE");
        Material m = raw == null ? null : Material.matchMaterial(raw.trim().toUpperCase(java.util.Locale.ROOT));
        return m != null && m.isItem() ? m : Material.MACE;
    }

    /** 演出锤子朝哪边画：CENTER = 永远面向玩家（从任何角度看都是一整把锤子） */
    public Display.Billboard hammerBillboard() {
        String raw = plugin.settings().str("anvil-forge.animation.billboard", "CENTER");
        try {
            return Display.Billboard.valueOf(raw == null ? "CENTER" : raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Display.Billboard.CENTER;
        }
    }

    /**
     * 落锤演出一帧的姿态：{@code angleDeg} = 模型绕 Z 轴（自身平面内）转了多少度。
     *
     * <p><b>pivot 在柄底</b>：实体位置从头到尾不动，只靠「旋转 + 反向平移」让
     * 锤头绕着手的位置画弧线砸下去。旋转 0° = 锤头朝上，正角度 = 锤头越过竖直向下。
     * 平移量由 {@code -R(θ)·(0, L, 0)} 现算，长度 L 固定，所以柄底严格钉在原地。</p>
     */
    public record StrikeFrame(float angleDeg, boolean impact, boolean done) {
    }

    /** 落锤演出第 {@code t} tick 的姿态（服务端真值，自检直接断言这里） */
    public StrikeFrame strikeFrame(int t) {
        int total = Math.max(6, plugin.settings().intOf("anvil-forge.animation.duration-ticks", 12));
        int impact = Math.max(3, Math.min(total - 1,
                plugin.settings().intOf("anvil-forge.animation.impact-tick", 7)));
        float start = (float) plugin.settings().num("anvil-forge.animation.swing-start-deg", -25);
        float hit = (float) plugin.settings().num("anvil-forge.animation.swing-hit-deg", 170);
        float rebound = (float) plugin.settings().num("anvil-forge.animation.swing-rebound-deg", 12);
        if (t < impact) {
            // 加速下劈：k² 让起手慢、临到砧面最快，像真的一锤子抡下去
            double k = t / (double) impact;
            return new StrikeFrame(start + (hit - start) * (float) (k * k), false, false);
        }
        if (t == impact) {
            return new StrikeFrame(hit, true, false);
        }
        if (t == impact + 1) {
            // 反震：锤头被砧面顶回来一点
            return new StrikeFrame(hit - rebound, false, false);
        }
        // 收锤：抬回起手角度，抬完就撤
        double k = (t - impact - 1) / (double) Math.max(1, total - impact - 1);
        float from = hit - rebound;
        return new StrikeFrame(from + (start - from) * (float) k, false, t > total);
    }

    /** 柄底到模型中心的长度（模型单位 × 缩放）：太小锤子会飘，太大就脱手 */
    public float handleLength(float scale) {
        double len = plugin.settings().num("anvil-forge.animation.handle-length", 0.46);
        return (float) (Math.max(0.05, Math.min(1.5, len)) * scale);
    }

    /**
     * 模型中心到锤头（模型顶端）的长度：原版物品模型在居中后大约占 ±0.5，
     * 重锤的锤头就在顶端 0.5 附近。柄底到锤头全长 = handleLength + 这一段，
     * 粒子拖尾要撒在锤头上而不是模型中心，否则轨迹看着还是一条竖线。
     */
    private static final float MODEL_HEAD_LEN = 0.5f;

    /**
     * 绕 Z 轴转 {@code deg} 后<b>锤头</b>相对柄底（pivot）的偏移：
     * {@code R(θ)·(0, handleLength + MODEL_HEAD_LEN×scale, 0)}。
     *
     * <p>{@link #pivotOffset} 算的是「模型中心」的偏移，只到柄底距离那一半；
     * 火星拖尾、自检的砸中高度都应该用这份，才是真的画在锤头上。</p>
     */
    public float[] headOffset(float deg, float scale) {
        return pivotOffset(deg, handleLength(scale) + MODEL_HEAD_LEN * scale);
    }

    /** 绕 Z 轴转 {@code deg} 后的模型中心相对柄底的偏移：{@code R(θ)·(0, L, 0)} */
    public static float[] pivotOffset(float deg, float length) {
        double rad = Math.toRadians(deg);
        return new float[]{(float) (-length * Math.sin(rad)), (float) (length * Math.cos(rad)), 0f};
    }

    /**
     * 落锤演出：<b>锤柄不动，锤头绕柄砸下来</b>。
     *
     * <p>实体只生成一次、钉在 pivot（柄底，默认砧面上方 1.10 格），之后每 tick 只写
     * transformation——旋转角从 {@code swing-start-deg}（起手扬起）加速扫到
     * {@code swing-hit-deg}（砸过竖直），平移固定取 {@code R(θ)·(0, L, 0)} 的反向，
     * 于是柄底严格不动、锤头画弧。<b>不再整体上下平移</b>：那是 v2.16 的做法，
     * 看着像「一把锤子从天上垂直掉下来」，不像人在砸。</p>
     *
     * <p>伴火焰：飞行段每 tick 在锤头位置撒火星，砸中时补一捧火。</p>
     *
     * <p>全是客户端可见的实体与粒子，<b>不参与任何判定</b>：不扣钱、不改等级。</p>
     */
    public void playStrikeAnimation(AnvilForgeSession session, org.bukkit.block.Block block,
                                    StrengthenService.Tier tier) {
        if (!plugin.settings().flag("anvil-forge.animation.enabled", true)) {
            return;
        }
        World world = block.getWorld();
        if (world == null) {
            return;
        }
        Location center = block.getLocation().add(0.5, 0.0, 0.5);
        // 手（柄底）抬到砧面上方约 1.1 格：锤头全长 ≈ 1.05 格，砸中（170°）时锤头
        // 正好落在剑面上方一点点。pivot 太低的话锤头砸中时会埋进砧子里。
        double pivotY = Math.max(0.3, Math.min(4.0,
                plugin.settings().num("anvil-forge.animation.pivot-height", 2.10)));
        final Location pivot = center.clone().add(0, pivotY, 0);
        final ItemDisplay[] holder = new ItemDisplay[1];
        final float scale = (float) plugin.settings().num("anvil-forge.animation.hammer-scale", 1.10);
        final float length = handleLength(scale);

        new org.bukkit.scheduler.BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                // 砧子被拆了就别演了；剑碎了（会话没了）照样把这一段演完，碎裂的火花才看得见
                if (!AnvilForgeManager.isAnvilBlock(block.getType())) {
                    cleanup();
                    return;
                }
                StrikeFrame f = strikeFrame(t);
                if (t == 0) {
                    holder[0] = world.spawn(pivot, ItemDisplay.class, d -> {
                        d.setItemStack(new ItemStack(hammerDisplayMaterial()));
                        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                        d.setBillboard(hammerBillboard());
                        d.setGravity(false);
                        d.setInvulnerable(true);
                        d.setPersistent(false);
                        d.setShadowRadius(0f);
                        d.setShadowStrength(0f);
                        d.setViewRange(1.0f);
                        pose(d, f.angleDeg(), scale, length);
                        // 登记进活动表：插件在演出中途被禁用时由 stop() 统一回收（见字段注释）
                        ACTIVE_STRIKE_HAMMERS.add(d);
                    });
                }
                ItemDisplay hammer = holder[0];
                if (hammer == null || !hammer.isValid()) {
                    cleanup();
                    return;
                }
                pose(hammer, f.angleDeg(), scale, length);
                // 位置一动不动：柄底就是 pivot，锤头靠旋转画弧（见 pivotOffset）
                // 拖尾撒在真实锤头上（headOffset ≈ 2×pivotOffset，pivotOffset 只是模型中心）
                float[] head = headOffset(f.angleDeg(), scale);
                Location headAt = pivot.clone().add(head[0], head[1], head[2]);
                if (plugin.settings().flag("anvil-forge.animation.flame.enabled", true)) {
                    spawnFlameTrail(world, headAt, f);
                }
                if (f.impact()) {
                    spawnSparks(world, center.clone().add(0, 1.03, 0), tier);
                    spawnImpactFlame(world, center.clone().add(0, 1.03, 0), tier);
                }
                if (f.done()) {
                    cleanup();
                    return;
                }
                t++;
            }

            /**
             * 绕柄旋转 + 反向平移。
             *
             * <p>Minecraft 的变换顺序是 {@code T · R · S}，平移在<b>旋转之后</b>作用于世界空间，
             * 所以想让柄底钉住原点，平移必须写成 {@code R(θ)·(0, L, 0)} 而不是固定的 (0, L, 0)——
             * 写成固定值的话，锤子一转柄就会横向滑走。</p>
             */
            private void pose(ItemDisplay d, float deg, float sc, float len) {
                float[] off = pivotOffset(deg, len);
                d.setTransformation(new Transformation(
                        new Vector3f(off[0], off[1], off[2]),
                        new Quaternionf().rotateZ((float) Math.toRadians(deg)),
                        new Vector3f(sc, sc, sc),
                        new Quaternionf()));
            }

            private void cleanup() {
                ItemDisplay hammer = holder[0];
                if (hammer != null) {
                    // 正常演完 / 砧子被拆的路径从这里摘除登记；ConcurrentHashMap 的 set 不收 null，先判空
                    ACTIVE_STRIKE_HAMMERS.remove(hammer);
                    if (hammer.isValid()) {
                        hammer.remove();
                    }
                }
                holder[0] = null;
                cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** 飞行段的火星拖尾：锤头走过的地方留一点火，砸下去才有「摩擦生热」的观感 */
    private void spawnFlameTrail(World world, Location headAt, StrikeFrame f) {
        int count = Math.max(1, plugin.settings().intOf("anvil-forge.animation.flame.trail", 2));
        world.spawnParticle(org.bukkit.Particle.FLAME, headAt, count, 0.04, 0.04, 0.04, 0.002);
        if (f.impact()) {
            return; // 砸中那一帧单独来一捧大的，别叠在拖尾上
        }
        world.spawnParticle(org.bukkit.Particle.SMALL_FLAME, headAt, 1, 0.03, 0.03, 0.03, 0.0);
    }

    /** 砸中时的火焰爆发：火 + 岩浆 + 烟，碎裂时更凶 */
    private void spawnImpactFlame(World world, Location at, StrengthenService.Tier tier) {
        int count = Math.max(1, plugin.settings().intOf("anvil-forge.animation.flame.count", 18));
        double spread = Math.max(0.05, plugin.settings().num("anvil-forge.animation.flame.spread", 0.30));
        world.spawnParticle(org.bukkit.Particle.FLAME, at, count, spread, 0.08, spread, 0.03);
        world.spawnParticle(org.bukkit.Particle.LAVA, at, Math.max(2, count / 3), spread * 0.6, 0.05, spread * 0.6, 0.0);
        world.spawnParticle(org.bukkit.Particle.SMOKE, at, Math.max(2, count / 4), spread * 0.8, 0.12, spread * 0.8, 0.01);
        if (tier == StrengthenService.Tier.BREAK) {
            world.spawnParticle(org.bukkit.Particle.SOUL_FIRE_FLAME, at, count / 2, spread * 0.7, 0.1, spread * 0.7, 0.02);
        }
    }

    /** 火花：CRIT 为主，碎裂时掺 LAVA + 烟，成功大成时加电火花 */
    private void spawnSparks(World world, Location at, StrengthenService.Tier tier) {
        if (!plugin.settings().flag("anvil-forge.spark.enabled", true)) {
            return;
        }
        int count = Math.max(1, plugin.settings().intOf("anvil-forge.spark.count", 18));
        double spread = Math.max(0.05, plugin.settings().num("anvil-forge.spark.spread", 0.35));
        world.spawnParticle(org.bukkit.Particle.CRIT, at, count, spread, 0.05, spread, 0.25);
        world.spawnParticle(org.bukkit.Particle.ELECTRIC_SPARK, at, Math.max(3, count / 3),
                spread * 0.6, 0.02, spread * 0.6, 0.02);
        switch (tier) {
            case BREAK -> {
                world.spawnParticle(org.bukkit.Particle.LAVA, at, count, spread, 0.05, spread, 0.1);
                world.spawnParticle(org.bukkit.Particle.LARGE_SMOKE, at, 6, spread * 0.7, 0.1, spread * 0.7, 0.02);
            }
            case GREAT, NO_LOSS -> {
                world.spawnParticle(org.bukkit.Particle.FIREWORK, at, count / 2, spread, 0.1, spread, 0.12);
                world.spawnParticle(org.bukkit.Particle.END_ROD, at, count / 3, spread * 0.5, 0.05, spread * 0.5, 0.02);
            }
            default -> world.spawnParticle(org.bukkit.Particle.FIREWORK, at, Math.max(3, count / 4),
                    spread * 0.8, 0.05, spread * 0.8, 0.05);
        }
    }

    private void tag(org.bukkit.entity.Entity entity, AnvilForgeSession session) {
        entity.getPersistentDataContainer().set(Keys.ANVIL_SESSION,
                PersistentDataType.STRING, session.key());
    }

    /** 撤掉一个砧位的全息 */
    public void despawn(AnvilForgeSession session) {
        ItemDisplay sword = resolve(session.swordDisplay(), ItemDisplay.class);
        if (sword != null && sword.isValid()) {
            sword.remove();
        }
        TextDisplay label = resolve(session.textDisplay(), TextDisplay.class);
        if (label != null && label.isValid()) {
            label.remove();
        }
        session.swordDisplay(null);
        session.textDisplay(null);
        session.lastText(null);
    }

    private <T extends org.bukkit.entity.Entity> T resolve(UUID id, Class<T> type) {
        if (id == null) {
            return null;
        }
        org.bukkit.entity.Entity e = Bukkit.getEntity(id);
        return type.isInstance(e) ? type.cast(e) : null;
    }

    /** 全息文本（MiniMessage，多行） */
    public String boardText(AnvilForgeSession session) {
        ItemStack sword = session.sword();
        StrengthenService st = plugin.strengthen();
        if (sword == null || st == null) {
            return "<gray>空砧";
        }
        int level = st.levelOf(sword);
        int cap = st.capOf(sword);
        double[] odds = st.oddsOf(sword, 0);
        double success = odds[3] + odds[4];
        double brk = odds[0];
        boolean armed = st.noLossComboActive(sword);
        int combo = st.comboOf(sword);
        int threshold = st.noLossComboThreshold();

        StringBuilder sb = new StringBuilder();
        String sc = com.forgemaster.util.UiPalette.chanceColor(success);
        sb.append("<gray>锻造等级 <white>+").append(level).append("</white><dark_gray>/</dark_gray><gold>+")
                .append(cap).append("</gold>");
        // 颜色标签必须整对成对写；漏了尖括号 MiniMessage 会整行解析失败、把标签原样吐到全息上
        sb.append("<newline><gray>成功率 <").append(sc).append(">").append(Msg.fmt(success * 100, 0))
                .append("%</").append(sc).append(">")
                .append(" <dark_gray>·</dark_gray> <gray>碎裂率 <red>")
                .append(Msg.fmt(brk * 100, 0)).append("%</red>");
        // 剑身倍率明牌：不然「越敲越大」全靠玩家自己盯模型，看不出就等于没做
        double shownScale = swordScaleFor(sword);
        if (shownScale > swordScale() + 0.001) {
            sb.append(" <dark_gray>·</dark_gray> <gray>剑身 <aqua>×")
                    .append(Msg.fmt(shownScale / swordScale(), 2)).append("</aqua>");
        }
        sb.append("<newline>").append(st.comboText(combo, threshold));
        if (armed) {
            sb.append("<newline><#7ef2c8>下一锤：无损连击强化（必成 · 不碎）</#7ef2c8>");
        }
        if (level >= cap) {
            sb.append("<newline><gold>已满级</gold>");
        } else {
            // 蜕变门槛：品质上限（如 +50）和「这一阶只能到 +5」是两码事。
            // 以前全息只写 +L/+50，玩家敲到 +5 就被拦，看着像坏了。
            String gate = gateHint(sword);
            if (gate != null) {
                sb.append("<newline>").append(gate);
            }
        }
        sb.append("<newline><dark_gray>重锤左键落锤 · 右键取剑</dark_gray>");
        return sb.toString();
    }

    /**
     * 这一阶的蜕变门槛提示；没被门槛挡住返回 null。
     *
     * <p>措辞要和 {@code StrengthenService.strike} 里的拒绝文案对得上，
     * 否则玩家在砧上看到一句、聊天里又是另一句。</p>
     */
    private String gateHint(ItemStack sword) {
        try {
            var evo = plugin.evolutions();
            if (evo == null || !evo.enabled()) {
                return null;
            }
            int stage = evo.stageOf(sword);
            var next = evo.registry().nextGate(stage);
            if (next == null) {
                return null;
            }
            int level = plugin.strengthen().levelOf(sword);
            if (level < next.level()) {
                // 还没到门槛：提前把「下一道坎」摆出来，省得撞上去才知道
                return "<dark_gray>本阶门槛 <white>+" + next.level() + "</white> · 到点先蜕变「"
                        + next.title() + "」</dark_gray>";
            }
            return "<gold>本阶已满 +" + next.level() + "</gold> <dark_gray>·</dark_gray> "
                    + "<gray>先蜕变「<white>" + next.title() + "</white>」才解锁 +"
                    + (next.level() + 1) + " 以上 <dark_gray>/zhujian evolve</dark_gray>";
        } catch (RuntimeException ex) {
            return null; // 全息不能因为查不到门槛就整个画不出来
        }
    }

    /** 全息文本的单行版（放上剑时聊天里回一句） */
    private String boardLine(AnvilForgeSession session) {
        ItemStack sword = session.sword();
        StrengthenService st = plugin.strengthen();
        int level = st.levelOf(sword);
        int cap = st.capOf(sword);
        double[] odds = st.oddsOf(sword, 0);
        double success = odds[3] + odds[4];
        return "<gray>强化 <white>+" + level + "</white><dark_gray>/</dark_gray><gold>+" + cap
                + "</gold> <dark_gray>·</dark_gray> <gray>成功率 <" + com.forgemaster.util.UiPalette.chanceColor(success) + ">"
                + Msg.fmt(success * 100, 0) + "%</" + com.forgemaster.util.UiPalette.chanceColor(success) + ">"
                + " <dark_gray>·</dark_gray> <gray>碎裂率 <red>" + Msg.fmt(odds[0] * 100, 0) + "%</red>"
                + scaleLine(sword)
                + gateLine(sword);

    }

    /** 一行版的剑身倍率尾巴；没长大返回空串 */
    private String scaleLine(ItemStack sword) {
        double base = swordScale();
        double now = swordScaleFor(sword);
        if (now <= base + 0.001) {
            return "";
        }
        return " <dark_gray>·</dark_gray> <gray>剑身 <aqua>×" + Msg.fmt(now / base, 2) + "</aqua>";
    }

    /** 一行版的蜕变门槛尾巴；没门槛返回空串（聊天里别塞废话） */
    private String gateLine(ItemStack sword) {
        try {
            var evo = plugin.evolutions();
            if (evo == null || !evo.enabled()) {
                return "";
            }
            var next = evo.registry().nextGate(evo.stageOf(sword));
            if (next == null) {
                return "";
            }
            int level = plugin.strengthen().levelOf(sword);
            return level >= next.level()
                    ? " <dark_gray>·</dark_gray> <gold>本阶已满 +" + next.level() + "（先蜕变「"
                            + next.title() + "」）</gold>"
                    : " <dark_gray>·</dark_gray> <gray>本阶门槛 +" + next.level() + "</gray>";
        } catch (RuntimeException ex) {
            return "";
        }
    }

    // ------------------------------------------------------------ 定时校验

    private void tick() {
        // v2.23（P2-2）：周期落盘已收编进异步冲刷（startAutoFlush，5 秒一轮），
        // 这里只留会话校验（方块被拆 / 超时 / 全息补生成），主线程不再碰磁盘。
        if (sessions.isEmpty()) {
            return;
        }
        long timeout = timeoutMinutes();
        long now = System.currentTimeMillis();
        for (AnvilForgeSession s : new ArrayList<>(sessions.values())) {
            if (!s.blockPresent()) {
                // 砧子没了：把剑还给主人 / 掉在原地
                dropReturn(s);
                continue;
            }
            if (timeout > 0 && now - s.placedAt() > timeout * 60_000L) {
                dropReturn(s);
                Player owner = Bukkit.getPlayer(s.owner());
                if (owner != null) {
                    Msg.prefix(owner, "<yellow>砧上的剑放太久了，已经还给你（或掉在砧子旁）。");
                }
                continue;
            }
            // 区块加载过但实体被清了（重启、卸载）：补一次全息
            World w = Bukkit.getWorld(s.worldName());
            if (w != null && w.isChunkLoaded(s.x() >> 4, s.z() >> 4)) {
                if (resolve(s.textDisplay(), TextDisplay.class) == null) {
                    refresh(s, true);
                }
            }
        }
    }

    /** 把砧上的剑还给主人；人不在线就掉在砧上 */
    private void dropReturn(AnvilForgeSession s) {
        // v2.23.1 修 P1：世界被卸载（Multiverse 切走资源世界 / 只加载主世界）且主人离线时，
        // 原先先 sessions.remove 再 dropAt，而 dropAt 拿不到世界就静默 return —— 剑从内存和
        // 文件里同时消失。这里改成「先确认有地方可去，再销毁会话」，无处可去就原样留着，
        // 等下一次 tick / 世界加载回来再还。
        ItemStack sword = s.sword();
        boolean hasSword = sword != null && !sword.getType().isAir();
        Player owner = Bukkit.getPlayer(s.owner());
        boolean ownerHere = owner != null && owner.isOnline();
        if (hasSword && !ownerHere && Bukkit.getWorld(s.worldName()) == null) {
            return;
        }
        removeSession(s.key());
        despawn(s);
        if (!hasSword) {
            return;
        }
        if (ownerHere) {
            var left = owner.getInventory().addItem(sword);
            for (ItemStack l : left.values()) {
                dropAt(s, l);
            }
            return;
        }
        dropAt(s, sword);
    }

    private void dropAt(AnvilForgeSession s, ItemStack item) {
        World w = Bukkit.getWorld(s.worldName());
        if (w == null) {
            return;
        }
        w.dropItemNaturally(new Location(w, s.x() + 0.5, s.y() + 1.0, s.z() + 0.5), item);
    }

    /** 方块被拆 / 被炸：把这一格上的东西按「还剑」处理 */
    public void releaseBlock(Block block) {
        AnvilForgeSession s = sessionAt(block);
        if (s != null) {
            dropReturn(s);
        }
    }

    /**
     * <b>仅自检用</b>：撤掉这一格的会话与全息，<b>不</b>把剑还回去、也不掉出来。
     *
     * <p>线上路径一律走 {@link #releaseBlock} / {@link #take}，别用这个——
     * 它存在的唯一理由是自检不该往世界里吐测试物品。</p>
     */
    public void discardQuietly(Block block) {
        AnvilForgeSession s = sessionAt(block);
        if (s == null) {
            return;
        }
        removeSession(s.key());
        despawn(s);
    }

    /** 区块加载时补全息（实体是非持久化的，卸载即消失） */
    public void onChunkLoad(org.bukkit.Chunk chunk) {
        if (sessions.isEmpty()) {
            return;
        }
        String world = chunk.getWorld().getName();
        for (AnvilForgeSession s : sessions.values()) {
            if (!s.worldName().equals(world)) {
                continue;
            }
            if ((s.x() >> 4) == chunk.getX() && (s.z() >> 4) == chunk.getZ()) {
                if (resolve(s.textDisplay(), TextDisplay.class) == null) {
                    refresh(s, true);
                }
            }
        }
    }

    // ------------------------------------------------------------ 配置

    public boolean enabled() {
        return plugin.settings().flag("anvil-forge.enabled", true);
    }

    public boolean ownerOnly() {
        return plugin.settings().flag("anvil-forge.owner-only", true);
    }

    public long cooldownMs() {
        // 默认对齐 config.yml 发布值 450（原 300，漂移：删键的服务会突然变快）
        return Math.max(0, plugin.settings().intOf("anvil-forge.strike-cooldown-ms", 450));
    }

    public double height() {
        return Math.max(1.0, Math.min(3.0, plugin.settings().num("anvil-forge.display.height", 1.35)));
    }

    /** 砧位超时分钟数；0 = 不超时 */
    public long timeoutMinutes() {
        return Math.max(0, plugin.settings().intOf("anvil-forge.session-timeout-minutes", 0));
    }

    // ------------------------------------------------------------ 落盘

    /**
     * 启动低频异步冲刷（5 秒一轮）：仅 dirty 时才真正写文件（v2.23 P2-2）。
     *
     * <p>审计 #10 原来是主线程 tick 每 60 秒同步 save()；anvils.yml 每砧位含完整
     * 物品快照，砧位一多主线程写盘会卡顿。改成 TombManager 同款 dirty 模式：
     * 变更路径只标脏（{@code dirty = true}），冲刷任务在异步线程写盘，
     * 主线程动作路径零同步 IO。异步窗口（≤5 秒）内崩溃最多漏最后一笔放/取/落锤变更。
     * 读状态（会话字段）只在主线程改，异步线程只读值写文件，不碰 Bukkit 世界数据。</p>
     */
    private void startAutoFlush() {
        if (flushTaskId != -1) {
            Bukkit.getScheduler().cancelTask(flushTaskId);
        }
        flushTaskId = Bukkit.getScheduler()
                .runTaskTimerAsynchronously(plugin, this::flushIfDirty, 100L, 100L).getTaskId();
    }

    /** 停掉异步冲刷任务（关服 / 自检里销毁管理器前调用） */
    private void stopFlushTask() {
        if (flushTaskId != -1) {
            Bukkit.getScheduler().cancelTask(flushTaskId);
            flushTaskId = -1;
        }
    }

    private void flushIfDirty() {
        if (dirty) {
            save();
        }
    }

    private void load() {
        loadFrom(file);
    }

    /**
     * 从指定文件读砧位。生产用 {@link #load()}（固定 anvils.yml），
     * 自检用临时文件验证「关服再开不丢剑」的往返，不碰真实存档。
     */
    public void loadFrom(File from) {
        sessions.clear();
        if (from == null || !from.exists()) {
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(from);
        var root = cfg.getConfigurationSection("anvils");
        if (root == null) {
            return;
        }
        for (String index : root.getKeys(false)) {
            String base = "anvils." + index;
            try {
                String world = cfg.getString(base + ".world");
                int x = cfg.getInt(base + ".x");
                int y = cfg.getInt(base + ".y");
                int z = cfg.getInt(base + ".z");
                String ownerRaw = cfg.getString(base + ".owner");
                ItemStack sword = cfg.getItemStack(base + ".sword");
                if (world == null || ownerRaw == null || sword == null || sword.getType().isAir()) {
                    continue;
                }
                UUID owner = UUID.fromString(ownerRaw);
                String ownerName = cfg.getString(base + ".owner-name", "");
                long placedAt = cfg.getLong(base + ".placed-at", System.currentTimeMillis());
                AnvilForgeSession s = new AnvilForgeSession(world, x, y, z, owner, ownerName, sword, placedAt);
                sessions.put(s.key(), s);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("读取砧位 " + index + " 失败，跳过：" + ex.getMessage());
            }
        }
        if (!sessions.isEmpty()) {
            plugin.getLogger().info("已恢复 " + sessions.size() + " 个铁砧强化位。");
        }
    }

    public void save() {
        saveTo(file);
    }

    /**
     * 把当前砧位写到指定文件（自检用临时文件，不碰 anvils.yml）。
     *
     * <p><b>锁的一致性（v2.23.1 修 P0）</b>：原先只有本方法 {@code synchronized}，
     * 而主线程的 {@code sessions.put/remove} 完全不加锁 —— 锁保护不到被保护对象。
     * 异步线程 {@code new ArrayList<>(sessions.values())} 撞上主线程 {@code remove}
     * 会抛 {@link java.util.ConcurrentModificationException}；Bukkit 的重复任务一旦抛异常
     * 就被永久取消（{@code flushTaskId} 还是旧值，冲刷再也不会跑），此后所有砧位变更
     * 只剩内存。现在：结构变更与本方法的「拍快照」段落互斥，写盘在锁外进行，
     * 只有「写完时代数没再变过」才清脏。</p>
     */
    public void saveTo(File to) {
        if (to == null) {
            return;
        }
        boolean isMain = to.equals(file);
        long seq;
        java.util.List<AnvilForgeSession> snap;
        YamlConfiguration cfg = new YamlConfiguration();
        synchronized (this) {
            if (isMain && !dirty && file.exists()) {
                return;
            }
            seq = dirtySeq;
            snap = new ArrayList<>(sessions.values());
        }
        int i = 0;
        for (AnvilForgeSession s : snap) {
            String base = "anvils." + i;
            cfg.set(base + ".world", s.worldName());
            cfg.set(base + ".x", s.x());
            cfg.set(base + ".y", s.y());
            cfg.set(base + ".z", s.z());
            cfg.set(base + ".owner", s.owner().toString());
            cfg.set(base + ".owner-name", s.ownerName());
            cfg.set(base + ".placed-at", s.placedAt());
            cfg.set(base + ".sword", s.sword());
            i++;
        }
        try {
            var parent = to.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("建不出数据目录，砧位没存下来。");
                return;
            }
            cfg.save(to);
        } catch (IOException ex) {
            // 保留 dirty，下一轮冲刷重试
            plugin.getLogger().warning("保存砧位失败: " + ex.getMessage());
            return;
        }
        if (isMain) {
            synchronized (this) {
                // 写盘期间若又有人放/取剑，代数已经变了，这一轮不算把新变更写进去了
                if (dirtySeq == seq) {
                    dirty = false;
                }
            }
        }
    }

    /** 只读：全息文本（自检用，不生成实体） */
    public List<String> debugBoard(AnvilForgeSession session) {
        List<Component> parts = new ArrayList<>();
        parts.add(Msg.mm(boardText(session)));
        List<String> out = new ArrayList<>();
        for (Component c : parts) {
            for (String line : Msg.plain(c).split("\n")) {
                out.add(line);
            }
        }
        return out;
    }

    /** 文本组件斜体关闭（TextDisplay 里用不到斜体，但保持和其它文本一致） */
    static Component plain(String mini) {
        return Msg.mm(mini).decoration(TextDecoration.ITALIC, false);
    }
}




