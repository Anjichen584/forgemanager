package com.forgemaster.order;

import com.forgemaster.item.ItemFactory;
import com.forgemaster.quality.Quality;
import com.forgemaster.util.Keys;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 一张锻造订单：玩家对玩家的委托市场单据。
 *
 * <p>模型照 {@link com.forgemaster.commission.Commission} 的存档风格
 * （toMap / fromSection），但归属是玩家而不是系统客人：</p>
 * <ul>
 *   <li>需求 = 材料 id + 最低品质 + 最低强化级，发单时从主手「样品」的 PDC 解析；</li>
 *   <li>悬赏（剑币）发单时从发单人现扣托管，订单单据本身就是托管凭证；</li>
 *   <li>{@link #item} 是交付 / 过期结算过程中的剑托管槽（崩溃续做用，
 *       见 {@link OrderService} 的先归属后动钱注释）。</li>
 * </ul>
 */
public final class Order {

    public enum State {
        /** 挂出中，可被接单 / 取消 */
        OPEN,
        /** 已被接单，等待接单人交付 */
        TAKEN,
        /** 已交付结案（悬赏已发、剑已入发单人邮箱） */
        DONE,
        /** 已过期结案（悬赏已退、若收了剑已退接单人） */
        EXPIRED
    }

    private final String id;
    private final UUID poster;
    private final String posterName;

    private final String materialId;      // "any" = 不限剑胚
    private final Quality qualityReq;     // null = 不限
    private final int strengthenReq;      // 0 = 不限

    private final long bounty;            // 悬赏剑币（发单时托管）
    private State state;
    private UUID taker;                   // null = 未接单
    private String takerName;
    private final long createdAt;
    private final long expiresAt;
    /** 剑托管槽：只在交付 / 过期退剑的两步结算中间非空（崩溃续做标记） */
    private ItemStack item;

    public Order(String id, UUID poster, String posterName,
                 String materialId, Quality qualityReq, int strengthenReq,
                 long bounty, State state, UUID taker, String takerName,
                 long createdAt, long expiresAt, ItemStack item) {
        this.id = id;
        this.poster = poster;
        this.posterName = posterName;
        this.materialId = materialId;
        this.qualityReq = qualityReq;
        this.strengthenReq = strengthenReq;
        this.bounty = bounty;
        this.state = state;
        this.taker = taker;
        this.takerName = takerName;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.item = item;
    }

    public String id() {
        return id;
    }

    public UUID poster() {
        return poster;
    }

    public String posterName() {
        return posterName;
    }

    public String materialId() {
        return materialId;
    }

    public Quality qualityReq() {
        return qualityReq;
    }

    public int strengthenReq() {
        return strengthenReq;
    }

    public long bounty() {
        return bounty;
    }

    public State state() {
        return state;
    }

    public void state(State state) {
        this.state = state;
    }

    public boolean hasTaker() {
        return taker != null;
    }

    public UUID taker() {
        return taker;
    }

    public String takerName() {
        return takerName;
    }

    public void taker(UUID taker, String takerName) {
        this.taker = taker;
        this.takerName = takerName;
    }

    public long createdAt() {
        return createdAt;
    }

    public long expiresAt() {
        return expiresAt;
    }

    public boolean expired() {
        return System.currentTimeMillis() > expiresAt;
    }

    public long remainMillis() {
        return Math.max(0, expiresAt - System.currentTimeMillis());
    }

    public ItemStack item() {
        return item;
    }

    public void item(ItemStack item) {
        this.item = item;
    }

    // ------------------------------------------------------------ 判定

    /**
     * 判断一件实物是否满足订单需求。
     *
     * @return 差在哪里的说明；满足时返回 null
     */
    public String missing(ItemFactory items, ItemStack item) {
        if (item == null || !items.isForged(item)) {
            return "这不是锻造品";
        }
        var meta = item.getItemMeta();
        if (meta == null) {
            return "物品数据异常";
        }
        var pdc = meta.getPersistentDataContainer();

        if (materialId != null && !"any".equalsIgnoreCase(materialId)) {
            String got = pdc.get(Keys.MATERIAL, PersistentDataType.STRING);
            if (!materialId.equalsIgnoreCase(got)) {
                return "剑胚不对（要 " + materialId + "）";
            }
        }
        if (qualityReq != null) {
            String qn = pdc.get(Keys.QUALITY, PersistentDataType.STRING);
            Quality got = qn == null ? null : Quality.byName(qn);
            if (got == null || got.ordinal() < qualityReq.ordinal()) {
                return "品质不够（要 " + qualityReq.display() + " 以上）";
            }
        }
        if (strengthenReq > 0) {
            Integer lv = pdc.get(Keys.STRENGTHEN, PersistentDataType.INTEGER);
            if (lv == null || lv < strengthenReq) {
                return "强化等级不够（要 +" + strengthenReq + " 以上）";
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 配置读写

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("poster", poster.toString());
        m.put("poster-name", posterName);
        m.put("material", materialId == null ? "any" : materialId);
        m.put("quality", qualityReq == null ? "" : qualityReq.name());
        m.put("strengthen", strengthenReq);
        m.put("bounty", bounty);
        m.put("state", state.name());
        m.put("taker", taker == null ? "" : taker.toString());
        m.put("taker-name", takerName == null ? "" : takerName);
        m.put("created", createdAt);
        m.put("expires", expiresAt);
        // item 只在结算中间态非空；正常结案后为 null，Bukkit 会把它序列化成空段
        if (item != null) {
            m.put("item", item);
        }
        return m;
    }

    @SuppressWarnings("UnstableApiUsage")
    public static Order fromSection(ConfigurationSection sec) {
        String id = sec.getString("id", "?");
        UUID poster;
        try {
            poster = UUID.fromString(sec.getString("poster", ""));
        } catch (IllegalArgumentException ex) {
            poster = null;
        }
        if (poster == null) {
            return null;
        }
        String qn = sec.getString("quality", "");
        Quality q = qn == null || qn.isBlank() ? null : Quality.byName(qn);
        State state;
        try {
            state = State.valueOf(sec.getString("state", "OPEN"));
        } catch (IllegalArgumentException ex) {
            state = State.OPEN;
        }
        UUID taker = null;
        String takerRaw = sec.getString("taker", "");
        if (!takerRaw.isBlank()) {
            try {
                taker = UUID.fromString(takerRaw);
            } catch (IllegalArgumentException ignored) {
                // 接单人 uuid 损坏按未接单处理，悬赏仍归发单人
            }
        }
        ItemStack item = sec.get("item") instanceof ItemStack it ? it : null;
        return new Order(
                id, poster,
                sec.getString("poster-name", "?"),
                sec.getString("material", "any"),
                q,
                Math.max(0, sec.getInt("strengthen", 0)),
                Math.max(0, sec.getLong("bounty", 0)),
                state,
                taker,
                blankToNull(sec.getString("taker-name", "")),
                sec.getLong("created", 0),
                sec.getLong("expires", System.currentTimeMillis() + 3_600_000L),
                item);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
