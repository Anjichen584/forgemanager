package com.forgemaster.material;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 装备胚目录：把「剑胚档位 + 部位」组合成一件可锻造的装备。
 *
 * <p>装备不单独占配置条目，直接继承同档剑胚的温度手感、耗材与锻造费，
 * 只在材质、护甲加成上做区分。这样服主调剑胚数值时装备会跟着走。</p>
 *
 * <p>装备的基础护甲 / 韧性走 config 的 {@code armor.tiers} 段，
 * 与武器的基础攻击一起被削过一刀，再由锻造等级放大。</p>
 */
public final class ArmorCatalog {

    /** 剑胚 id → {材质前缀, 中文前缀, 颜色} */
    private static final Map<String, String[]> LOOK = new LinkedHashMap<>();

    /** 剑胚 id → {基础护甲, 基础韧性}，来自 config 的 armor.tiers */
    private static final Map<String, double[]> STATS = new LinkedHashMap<>();

    /**
     * 部位护甲占比，来自 config 的 {@code armor.slot-factor}。
     *
     * <p>{@code armor.tiers} 里写的是<b>整套</b>的护甲总量，四个部位各按这个比例分。
     * 以前四个部位拿同一个数，结果胸甲和靴子一样厚——和原版差得离谱。</p>
     *
     * <p>兜底值取原版钻石套的比例（3 / 8 / 6 / 3）：头盔 15%、胸甲 40%、
     * 护腿 30%、靴子 15%。</p>
     */
    private static final Map<ForgeMaterial.Slot, Double> SLOT_FACTOR =
            new java.util.EnumMap<>(ForgeMaterial.Slot.class);

    /** 部位韧性占比；原版头盔没有韧性，所以头盔那格是 0 */
    private static final Map<ForgeMaterial.Slot, Double> SLOT_TOUGH_FACTOR =
            new java.util.EnumMap<>(ForgeMaterial.Slot.class);

    static {
        SLOT_FACTOR.put(ForgeMaterial.Slot.HELMET, 0.15);
        SLOT_FACTOR.put(ForgeMaterial.Slot.CHESTPLATE, 0.40);
        SLOT_FACTOR.put(ForgeMaterial.Slot.LEGGINGS, 0.30);
        SLOT_FACTOR.put(ForgeMaterial.Slot.BOOTS, 0.15);

        SLOT_TOUGH_FACTOR.put(ForgeMaterial.Slot.HELMET, 0.0);
        SLOT_TOUGH_FACTOR.put(ForgeMaterial.Slot.CHESTPLATE, 0.40);
        SLOT_TOUGH_FACTOR.put(ForgeMaterial.Slot.LEGGINGS, 0.30);
        SLOT_TOUGH_FACTOR.put(ForgeMaterial.Slot.BOOTS, 0.30);
    }

    static {
        LOOK.put("iron", new String[]{"IRON", "铁", "#9e9e9e"});
        LOOK.put("gold", new String[]{"GOLDEN", "金", "#ffd166"});
        LOOK.put("copper", new String[]{"COPPER", "铜", "#e08a52"});
        LOOK.put("diamond", new String[]{"DIAMOND", "钻石", "#4ee3d6"});
        LOOK.put("netherite", new String[]{"NETHERITE", "下界合金", "#8a5a4a"});
        LOOK.put("starfall", new String[]{"NETHERITE", "星陨", "#b06bff"});
    }

    /** 档位兜底：按 tier 猜材质前缀 */
    private static final String[] TIER_PREFIX = {"IRON", "GOLDEN", "COPPER", "DIAMOND", "NETHERITE", "NETHERITE"};
    private static final String[] TIER_CN = {"铁", "金", "铜", "钻石", "下界合金", "星陨"};
    private static final String[] TIER_COLOR = {"#9e9e9e", "#ffd166", "#e08a52", "#4ee3d6", "#8a5a4a", "#b06bff"};
    /** 兜底护甲 / 韧性（整套总量，按部位占比再拆） */
    private static final double[] TIER_ARMOR = {15.0, 11.0, 12.0, 20.0, 20.0, 24.0};
    private static final double[] TIER_TOUGH = {0, 0, 0, 12.0, 16.0, 20.0};

    private ArmorCatalog() {
    }

    /** 从 config 读各档装备的基础护甲 / 韧性；缺失时用内置兜底 */
    public static void load(FileConfiguration cfg) {
        STATS.clear();
        loadSlotFactors(cfg);
        ConfigurationSection root = cfg.getConfigurationSection("armor.tiers");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) {
                continue;
            }
            STATS.put(id.toLowerCase(), new double[]{
                    Math.max(0, sec.getDouble("armor", 0)),
                    Math.max(0, sec.getDouble("toughness", 0))
            });
        }
    }

    /** 读 {@code armor.slot-factor.<部位>.armor|toughness}；缺哪个就保留内置的原版比例 */
    private static void loadSlotFactors(FileConfiguration cfg) {
        ConfigurationSection sf = cfg.getConfigurationSection("armor.slot-factor");
        if (sf == null) {
            return;
        }
        for (ForgeMaterial.Slot slot : ForgeMaterial.Slot.values()) {
            String key = slot.name().toLowerCase(java.util.Locale.ROOT);
            double a = sf.getDouble(key + ".armor", Double.NaN);
            if (!Double.isNaN(a)) {
                SLOT_FACTOR.put(slot, Math.max(0, a));
            }
            double t = sf.getDouble(key + ".toughness", Double.NaN);
            if (!Double.isNaN(t)) {
                SLOT_TOUGH_FACTOR.put(slot, Math.max(0, t));
            }
        }
    }

    /**
     * 由剑胚生成一件装备胚。
     *
     * @param base            同档剑胚
     * @param slot            部位
     * @param priceMultiplier 价格系数
     * @return 装备胚；该档没有对应护甲材质时返回 null
     */
    public static ForgeMaterial create(ForgeMaterial base, ForgeMaterial.Slot slot, double priceMultiplier) {
        if (base == null || slot == null) {
            return null;
        }
        String[] look = lookOf(base);
        Material armorItem = Material.matchMaterial(look[0] + "_" + slot.suffix());
        if (armorItem == null) {
            return null;
        }
        String display = "<" + look[2] + ">" + look[1] + slot.label() + "</" + look[2] + ">";
        long price = Math.max(0, Math.round(base.price() * priceMultiplier));
        double[] stats = statsOf(base);
        // config 里的 armor.tiers 是「整套总量」，按部位占比拆到单件上：
        // 胸甲最厚、头盔和靴子最薄，和原版的分布一致。
        double slotArmor = stats[0] * SLOT_FACTOR.getOrDefault(slot, 0.25);
        double slotTough = stats[1] * SLOT_TOUGH_FACTOR.getOrDefault(slot, 0.25);
        return new ForgeMaterial(
                "armor-" + base.id() + "-" + slot.name().toLowerCase(),
                display,
                armorItem,
                armorItem,
                base.tier(),
                base.idealTemp(),
                base.tolerance(),
                Math.max(3, base.strikes() - 1),
                Math.min(2, base.zoneWidth() + 1),
                base.costs(),
                // 装备不加攻击/攻速
                0.0,
                0.0,
                price,
                slot,
                slotArmor,
                slotTough,
                Math.max(1, Math.round(base.baseDurability() * (float) slot.durabilityFactor())));
    }

    private static String[] lookOf(ForgeMaterial base) {
        String[] hit = LOOK.get(base.id().toLowerCase());
        if (hit != null) {
            return hit;
        }
        int idx = Math.max(0, Math.min(TIER_PREFIX.length - 1, base.tier() - 1));
        return new String[]{TIER_PREFIX[idx], TIER_CN[idx], TIER_COLOR[idx]};
    }

    /** {护甲, 韧性}：优先 config，其次按档位兜底 */
    private static double[] statsOf(ForgeMaterial base) {
        double[] hit = STATS.get(base.id().toLowerCase());
        if (hit != null) {
            return hit;
        }
        int idx = Math.max(0, Math.min(TIER_ARMOR.length - 1, base.tier() - 1));
        return new double[]{TIER_ARMOR[idx], idx < TIER_TOUGH.length ? TIER_TOUGH[idx] : 0};
    }

    /**
     * 把装备成品上的 PDC 材料 id（{@code armor-<剑胚>-<部位>}）还原成装备胚。
     *
     * <p>{@code MaterialRegistry} 里只有剑胚，装备是运行时组合出来的，
     * 所以强化、宝石这些按 {@code Keys.MATERIAL} 反查剑胚的地方都要走这里。</p>
     *
     * @param registry    剑胚注册表
     * @param id          物品上的材料 id
     * @param priceMult   价格系数（与锻造时用的一致即可，只影响展示）
     * @return 装备胚；不是装备 id 或剑胚已被删掉时返回 null
     */
    public static ForgeMaterial resolve(MaterialRegistry registry, String id, double priceMult) {
        if (registry == null || id == null) {
            return null;
        }
        ForgeMaterial direct = registry.get(id);
        if (direct != null) {
            return direct;
        }
        String lower = id.toLowerCase();
        if (!lower.startsWith("armor-")) {
            return null;
        }
        String rest = lower.substring("armor-".length());
        int dash = rest.lastIndexOf('-');
        if (dash <= 0) {
            return null;
        }
        ForgeMaterial base = registry.get(rest.substring(0, dash));
        ForgeMaterial.Slot slot = ForgeMaterial.Slot.byName(rest.substring(dash + 1));
        if (base == null || slot == null) {
            return null;
        }
        return create(base, slot, priceMult);
    }
}
