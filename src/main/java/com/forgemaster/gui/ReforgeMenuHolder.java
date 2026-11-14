package com.forgemaster.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * 词条重铸界面持有者。
 *
 * <p>除了把 {@code InventoryClickEvent} 精确路由回重铸界面之外，还顺带存了
 * 「二次确认」的状态：{@link #pendingIndex} 是当前被点过一次、正在等第二次点击的条目，
 * {@link #pendingUntil} 是这次确认的截止毫秒数，{@link #token} 是自增的批次号——
 * 每次进入 / 取消 / 确认都会 +1，这样 3 秒后那个延迟任务回来时就能判断
 * 「这次确认还是不是我当时开的那个」，不会把玩家后来的操作误清掉。</p>
 *
 * <p>状态放持有者里而不是静态 Map：界面一关，持有者就跟着没了，
 * 玩家重开界面天然回到「谁都没确认过」的状态，不会残留一次误触。</p>
 */
public final class ReforgeMenuHolder implements InventoryHolder {

    private Inventory inventory;

    /** 正在等第二次点击的条目下标；-1 = 没有待确认的条目 */
    private int pendingIndex = -1;

    /** 这次确认的截止时间（毫秒时间戳），过期就自动还原 */
    private long pendingUntil = 0L;

    /**
     * 待确认时主手物品的指纹（材质 + 词条 id 串）。
     *
     * <p>防的是「第一次点完，中途把主手换成别的东西，第二次点却重铸了另一件」：
     * 指纹对不上就当没确认过，必须重新点两次。</p>
     */
    private String pendingSignature = "";

    /** 确认批次号：进入 / 取消 / 确认都 +1，延迟任务靠它判断自己是否过期 */
    private int token = 0;

    /** v2.25.1：界面主人（「点击者 = 界面主人」契约，与 ForgeHolder 同款） */
    private final java.util.UUID owner;

    public ReforgeMenuHolder(java.util.UUID owner) {
        this.owner = owner == null ? java.util.UUID.randomUUID() : owner;
    }

    public java.util.UUID owner() {
        return owner;
    }

    public void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    // ------------------------------------------------------------ 二次确认状态

    /** 当前待确认的条目下标（-1 = 没有） */
    public int pendingIndex() {
        return pendingIndex;
    }

    /** 这一条现在是不是「已点过一次、等第二次」的红色状态（按时间戳 + 指纹判，过期即失效） */
    public boolean awaiting(int index, String signature) {
        return pendingIndex == index
                && System.currentTimeMillis() <= pendingUntil
                && pendingSignature.equals(signature == null ? "" : signature);
    }

    /** 进入待确认状态（红色），返回本次的批次号 */
    public int await(int index, long untilMillis, String signature) {
        this.pendingIndex = index;
        this.pendingUntil = untilMillis;
        this.pendingSignature = signature == null ? "" : signature;
        return ++token;
    }

    /** 清掉待确认状态（确认完成 / 超时还原 / 点了别处 / 关了界面都走这里） */
    public void clearPending() {
        this.pendingIndex = -1;
        this.pendingUntil = 0L;
        this.pendingSignature = "";
        this.token++;
    }

    /** 当前批次号（延迟任务开跑前记下来） */
    public int token() {
        return token;
    }

    /**
     * 批次号还对得上吗——即「这几秒里玩家没有取消、没有确认、没有换条目、没有换主手物品」。
     *
     * @param expectToken 派任务时记下的批次号
     * @param index       当时点的那一条
     * @param signature   当时主手物品的指纹
     */
    public boolean stillPending(int expectToken, int index, String signature) {
        return token == expectToken && pendingIndex == index
                && pendingSignature.equals(signature == null ? "" : signature);
    }
}
