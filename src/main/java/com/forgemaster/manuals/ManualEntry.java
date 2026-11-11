package com.forgemaster.manuals;

import com.forgemaster.item.ItemFactory;
import com.forgemaster.quality.Quality;
import com.forgemaster.util.Keys;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一张剑谱：某把成品被登记后留下的"配方记忆"，后来者可以照着临摹。
 */
public final class ManualEntry {

    private final String id;
    private final UUID author;
    private final String authorName;
    private final String name;

    private final String materialId;
    private final Quality quality;
    private final double score;
    private final String pattern;
    private final int layers;
    private final String quenchId;
    private final List<String> affixIds;
    private final long seed;
    private final long createdAt;

    private int copies;
    private double bestCopyRate;

    public ManualEntry(String id, UUID author, String authorName, String name,
                       String materialId, Quality quality, double score, String pattern, int layers,
                       String quenchId, List<String> affixIds, long seed, long createdAt) {
        this.id = id;
        this.author = author;
        this.authorName = authorName;
        this.name = name;
        this.materialId = materialId;
        this.quality = quality;
        this.score = score;
        this.pattern = pattern;
        this.layers = layers;
        this.quenchId = quenchId;
        this.affixIds = affixIds == null ? new ArrayList<>() : new ArrayList<>(affixIds);
        this.seed = seed;
        this.createdAt = createdAt;
    }

    public String id() {
        return id;
    }

    public UUID author() {
        return author;
    }

    public String authorName() {
        return authorName;
    }

    public String name() {
        return name;
    }

    public String materialId() {
        return materialId;
    }

    public Quality quality() {
        return quality;
    }

    public double score() {
        return score;
    }

    public String pattern() {
        return pattern;
    }

    public int layers() {
        return layers;
    }

    public String quenchId() {
        return quenchId;
    }

    public List<String> affixIds() {
        return affixIds;
    }

    public long seed() {
        return seed;
    }

    public long createdAt() {
        return createdAt;
    }

    public int copies() {
        return copies;
    }

    public void addCopy(double rate) {
        copies++;
        if (rate > bestCopyRate) {
            bestCopyRate = rate;
        }
    }

    public double bestCopyRate() {
        return bestCopyRate;
    }

    /**
     * 计算一把新成品对本谱的复刻度（0~1）。
     *
     * <p>三个维度：词条重合度 60%、钢纹层数接近度 25%、评分接近度 15%。</p>
     */
    public double copyRateOf(ItemFactory items, ItemStack item) {
        if (item == null || !items.isForged(item)) {
            return 0;
        }
        var meta = item.getItemMeta();
        if (meta == null) {
            return 0;
        }
        var pdc = meta.getPersistentDataContainer();

        // 词条重合
        String csv = pdc.get(Keys.AFFIX_LIST, PersistentDataType.STRING);
        List<String> got = new ArrayList<>();
        if (csv != null && !csv.isBlank()) {
            for (String part : csv.split(",")) {
                if (!part.isBlank()) {
                    got.add(part.trim().toLowerCase());
                }
            }
        }
        double affixPart;
        if (affixIds.isEmpty()) {
            affixPart = got.isEmpty() ? 1.0 : 0.5;
        } else {
            int hit = 0;
            for (String want : affixIds) {
                if (got.contains(want.toLowerCase())) {
                    hit++;
                }
            }
            double recall = (double) hit / affixIds.size();
            double precision = got.isEmpty() ? 0 : (double) hit / got.size();
            affixPart = (recall * 0.7 + precision * 0.3);
        }

        // 钢纹层数
        Integer layersGot = pdc.get(Keys.LAYERS, PersistentDataType.INTEGER);
        int lg = layersGot == null ? 0 : layersGot;
        double layerPart = layers <= 0 ? (lg == 0 ? 1.0 : 0.5)
                : Math.max(0, 1.0 - Math.abs(lg - layers) / (double) Math.max(2, layers));

        // 评分接近
        Double scoreGot = pdc.get(Keys.SCORE, PersistentDataType.DOUBLE);
        double sg = scoreGot == null ? 0 : scoreGot;
        double scorePart = Math.max(0, 1.0 - Math.abs(sg - score) / 40.0);

        return Math.max(0, Math.min(1, affixPart * 0.60 + layerPart * 0.25 + scorePart * 0.15));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("author", author.toString());
        m.put("author-name", authorName);
        m.put("name", name);
        m.put("material", materialId);
        m.put("quality", quality == null ? Quality.COMMON.name() : quality.name());
        m.put("score", score);
        m.put("pattern", pattern == null ? "" : pattern);
        m.put("layers", layers);
        m.put("quench", quenchId == null ? "none" : quenchId);
        m.put("affixes", affixIds);
        m.put("seed", seed);
        m.put("created", createdAt);
        m.put("copies", copies);
        m.put("best-copy", bestCopyRate);
        return m;
    }

    public static ManualEntry fromSection(ConfigurationSection sec) {
        String qn = sec.getString("quality", Quality.COMMON.name());
        Quality q = Quality.byName(qn);
        UUID author;
        try {
            author = UUID.fromString(sec.getString("author", ""));
        } catch (Exception ex) {
            author = new UUID(0, 0);
        }
        ManualEntry e = new ManualEntry(
                sec.getString("id", "?"),
                author,
                sec.getString("author-name", "?"),
                sec.getString("name", "无名剑谱"),
                sec.getString("material", "iron"),
                q == null ? Quality.COMMON : q,
                sec.getDouble("score", 0),
                sec.getString("pattern", ""),
                sec.getInt("layers", 0),
                sec.getString("quench", "none"),
                sec.getStringList("affixes"),
                sec.getLong("seed", 0),
                sec.getLong("created", System.currentTimeMillis()));
        e.copies = sec.getInt("copies", 0);
        e.bestCopyRate = sec.getDouble("best-copy", 0);
        return e;
    }
}