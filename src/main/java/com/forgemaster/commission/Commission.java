package com.forgemaster.commission;

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
 * 一张锻造委托：某个客人要一把满足条件的剑。
 */
public final class Commission {

    public enum Kind {
        /** 指定剑胚 + 最低品质 */
        QUALITY,
        /** 必须带某个词条 */
        AFFIX,
        /** 折叠层数达到要求 */
        LAYERS,
        /** 总评分达标 */
        SCORE
    }

    private final String id;
    private final UUID owner;
    private final Kind kind;
    private final String clientName;

    private final String materialId;   // null / "any" = 不限剑胚
    private final Quality qualityReq;  // null = 不限
    private final String affixReq;     // null = 不限
    private final int layersReq;       // 0 = 不限
    private final double scoreReq;     // 0 = 不限

    private final String rewardItemId; // 奖励物品（材料 id 的 costItem 名）
    private final int rewardAmount;
    private final int rewardRep;
    private final long expiresAt;

    public Commission(String id, UUID owner, Kind kind, String clientName,
                      String materialId, Quality qualityReq, String affixReq,
                      int layersReq, double scoreReq,
                      String rewardItemId, int rewardAmount, int rewardRep, long expiresAt) {
        this.id = id;
        this.owner = owner;
        this.kind = kind;
        this.clientName = clientName;
        this.materialId = materialId;
        this.qualityReq = qualityReq;
        this.affixReq = affixReq;
        this.layersReq = layersReq;
        this.scoreReq = scoreReq;
        this.rewardItemId = rewardItemId;
        this.rewardAmount = rewardAmount;
        this.rewardRep = rewardRep;
        this.expiresAt = expiresAt;
    }

    public String id() {
        return id;
    }

    public UUID owner() {
        return owner;
    }

    public Kind kind() {
        return kind;
    }

    public String clientName() {
        return clientName;
    }

    public String materialId() {
        return materialId;
    }

    public Quality qualityReq() {
        return qualityReq;
    }

    public String affixReq() {
        return affixReq;
    }

    public int layersReq() {
        return layersReq;
    }

    public double scoreReq() {
        return scoreReq;
    }

    public String rewardItemId() {
        return rewardItemId;
    }

    public int rewardAmount() {
        return rewardAmount;
    }

    public int rewardRep() {
        return rewardRep;
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

    // ------------------------------------------------------------ 判定

    /**
     * 判断一件实物是否满足委托。
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
        if (affixReq != null) {
            String list = pdc.get(Keys.AFFIX_LIST, PersistentDataType.STRING);
            if (list == null || !ListContains(list, affixReq)) {
                return "缺词条（要 " + affixReq + "）";
            }
        }
        if (layersReq > 0) {
            Integer layers = pdc.get(Keys.LAYERS, PersistentDataType.INTEGER);
            if (layers == null || layers < layersReq) {
                return "折叠层数不够（要 " + layersReq + " 层以上）";
            }
        }
        if (scoreReq > 0) {
            Double score = pdc.get(Keys.SCORE, PersistentDataType.DOUBLE);
            if (score == null || score < scoreReq) {
                return "评分不够（要 " + String.format("%.1f", scoreReq) + " 分以上）";
            }
        }
        return null;
    }

    private static boolean ListContains(String csv, String value) {
        for (String part : csv.split(",")) {
            if (part.trim().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 配置读写

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind.name());
        m.put("client", clientName);
        m.put("material", materialId == null ? "any" : materialId);
        m.put("quality", qualityReq == null ? "" : qualityReq.name());
        m.put("affix", affixReq == null ? "" : affixReq);
        m.put("layers", layersReq);
        m.put("score", scoreReq);
        m.put("reward-item", rewardItemId);
        m.put("reward-amount", rewardAmount);
        m.put("reward-rep", rewardRep);
        m.put("expires", expiresAt);
        return m;
    }

    public static Commission fromSection(UUID owner, ConfigurationSection sec) {
        String id = sec.getString("id", "?");
        Kind kind;
        try {
            kind = Kind.valueOf(sec.getString("kind", "QUALITY"));
        } catch (IllegalArgumentException ex) {
            kind = Kind.QUALITY;
        }
        String qn = sec.getString("quality", "");
        Quality q = qn == null || qn.isBlank() ? null : Quality.byName(qn);
        return new Commission(
                id, owner, kind,
                sec.getString("client", "客人"),
                sec.getString("material", "any"),
                q,
                blankToNull(sec.getString("affix", "")),
                sec.getInt("layers", 0),
                sec.getDouble("score", 0),
                sec.getString("reward-item", "IRON_INGOT"),
                sec.getInt("reward-amount", 1),
                sec.getInt("reward-rep", 20),
                sec.getLong("expires", System.currentTimeMillis() + 3_600_000L));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}