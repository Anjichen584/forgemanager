package com.forgemaster.blackmarket;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.econ.CoinReason;
import com.forgemaster.econ.EconomyBridge;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.material.MaterialGrade;
import com.forgemaster.material.MaterialGrades;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 黑市（每日轮换特价，轻量留存拓展）。
 *
 * <p>日期口径：用<b>服务器本地时区的现实日</b>（{@link LocalDate#now()}），不用主世界
 * dayTime。理由：Minecraft 一个昼夜只有 20 分钟真实时间（dayTime 0~24000 循环），
 * 按 dayTime 轮换会让「每日」一天换几十批，签到/限购语义全毁；现实日还天然不受
 * doDaylightCycle 关闭、/time set 影响，跨重启稳定。种子 = 日期 + 主世界名，
 * 同一现实日内全服（多世界共享主世界名）看到同一批货、同一批折扣。</p>
 *
 * <p>商品池 config 驱动（{@code blackmarket.pool}，条目格式同 events.schedule 的
 * 行内 map 风格）：{@code type} = charm / material / blueprint，{@code id}、
 * {@code price}（剑币原价）、可选 {@code weight}（默认 1）与 material 的
 * {@code grade}。只许消耗型商品：符 / 锻造原料 / 图纸；material 条目里武器装备
 * 材质会被直接剔除，不进池。</p>
 *
 * <p>每件上架商品的折扣在同一现实日内用同一种子重放，价格与货单全服一致；
 * 购买走 {@link EconomyBridge}（外部经济优先，没接就退回内置剑币，与商会同口径）。
 * 每人每件每天限购 {@code blackmarket.daily-limit} 次，已购次数存
 * {@link DailyStore}（daily-data.yml）。</p>
 */
public final class BlackmarketService {

    /** 商品池里的一条原始商品（config 解析结果） */
    public record PoolItem(String key, String type, String id, MaterialGrade grade,
                           long basePrice, int weight) {
    }

    /** 今天实际架上的特价货：商品 + 折扣 + 现价 */
    public record Offer(PoolItem item, double discount, long price) {
    }

    private final ForgingMasterPlugin plugin;
    private final DailyStore store;

    public BlackmarketService(ForgingMasterPlugin plugin, DailyStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    // ------------------------------------------------------------ 开关 / 入口

    public boolean enabled() {
        return plugin.getConfig().getBoolean("blackmarket.enabled", true);
    }

    /** /zhujian blackmarket：打开今日黑市 */
    public void open(Player p) {
        BlackmarketGui.open(plugin, p);
    }

    public DailyStore store() {
        return store;
    }

    /** 主插件引用（GUI / 路由要拿 materials、economy 等） */
    public ForgingMasterPlugin plugin() {
        return plugin;
    }

    // ------------------------------------------------------------ 今日货单

    /**
     * 今天上架的特价货。每次现算（商会同款「不缓存」做法，/zhujian reload 立即生效），
     * 但随机序列由 种子 = 日期#主世界名 决定，同一天内结果稳定。
     */
    public List<Offer> offers() {
        List<PoolItem> pool = pool();
        List<Offer> out = new ArrayList<>();
        if (pool.isEmpty()) {
            return out;
        }
        int size = Math.max(1, plugin.getConfig().getInt("blackmarket.size", 4));
        double dMin = clamp(plugin.getConfig().getDouble("blackmarket.discount-min", 0.55));
        double dMax = clamp(plugin.getConfig().getDouble("blackmarket.discount-max", 0.8));
        if (dMax < dMin) {
            double t = dMin;
            dMin = dMax;
            dMax = t;
        }

        String seed = today() + "#" + primaryWorldName();
        Random r = new Random(seed.hashCode());

        List<PoolItem> rest = new ArrayList<>(pool);
        while (out.size() < size && !rest.isEmpty()) {
            int total = 0;
            for (PoolItem it : rest) {
                total += it.weight();
            }
            PoolItem chosen = null;
            if (total > 0) {
                int pick = r.nextInt(total);
                for (PoolItem it : rest) {
                    pick -= it.weight();
                    if (pick < 0) {
                        chosen = it;
                        break;
                    }
                }
            }
            if (chosen == null) {
                chosen = rest.get(rest.size() - 1);
            }
            rest.remove(chosen);
            double discount = dMin + (dMax - dMin) * r.nextDouble();
            long price = Math.max(1, Math.round(chosen.basePrice() * discount));
            out.add(new Offer(chosen, discount, price));
        }
        return out;
    }

    /** 某玩家今天还能买几件这条特价（限购 - 已购，最小 0） */
    public int remaining(Player p, Offer offer) {
        int limit = Math.max(1, plugin.getConfig().getInt("blackmarket.daily-limit", 2));
        int bought = store.purchaseCount(p.getUniqueId(), today(), offer.item().key());
        return Math.max(0, limit - bought);
    }

    // ------------------------------------------------------------ 购买

    /**
     * 买 1 件特价货：限购 → 扣款（EconomyBridge）→ 发货 → 记次数。
     * 条件不满足时自行提示，不抛异常。
     */
    public void buy(Player p, Offer offer) {
        if (!enabled()) {
            Msg.prefix(p, "<red>黑市今天没开。</red>");
            return;
        }
        UUID id = p.getUniqueId();
        int limit = Math.max(1, plugin.getConfig().getInt("blackmarket.daily-limit", 2));
        String date = today();
        int bought = store.purchaseCount(id, date, offer.item().key());
        if (bought >= limit) {
            Msg.prefix(p, "<red>这件你今天已经买过 <white>" + bought + "</white> 次，达到限购了。"
                    + "</red><gray>明天黑市换新货。</gray>");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
            return;
        }
        var wallet = plugin.wallet();
        if (!wallet.take(p, offer.price(), CoinReason.BLACKMARKET)) {
            Msg.prefix(p, "<red>剑币不够。</red> 这件要 " + wallet.format(offer.price())
                    + "<gray>，你只有 </gray>" + wallet.format(wallet.coins(p)) + "<gray>。</gray>");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
            return;
        }

        ItemStack goods = buildItem(offer);
        if (goods == null) {
            // 理论到不了（池解析时就剔掉了坏条目）；真到了把钱退回去
            wallet.give(p, offer.price(), CoinReason.REFUND);
            Msg.prefix(p, "<red>这件货出了问题，剑币已退回。</red>");
            return;
        }
        for (ItemStack rest : p.getInventory().addItem(goods).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
        store.recordPurchase(id, date, offer.item().key());
        store.save();

        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
        Msg.prefix(p, "<dark_gray>黑市</dark_gray> <green>购入</green> " + displayName(offer)
                + " <white>×1</white>，花费 " + wallet.format(offer.price())
                    + "<gray>（原价 " + wallet.format(offer.item().basePrice())
                    + "<gray>，" + Math.round(offer.discount() * 100) + "% 价）"
                    + " <gray>今日还可买 </gray><white>"
                + Math.max(0, limit - bought - 1) + "</white><gray> 次。</gray>");
    }

    /** 生成要发货的实物（商品数据格式与商会完全一致） */
    public ItemStack buildItem(Offer offer) {
        PoolItem item = offer.item();
        return switch (item.type()) {
            case "charm" -> com.forgemaster.strengthen.Charm.create(plugin, item.id());
            case "blueprint" -> {
                ForgeMaterial fm = plugin.materials() == null ? null : plugin.materials().get(item.id());
                yield fm == null ? null : plugin.shop().buildBlueprint(fm);
            }
            case "material" -> {
                Material m = Material.matchMaterial(item.id());
                yield m == null || !m.isItem() ? null
                        : MaterialGrades.stamp(new ItemStack(m), item.grade() == null
                                ? MaterialGrade.COMMON : item.grade());
            }
            default -> null;
        };
    }

    /** 商品显示名（消息里用） */
    public String displayName(Offer offer) {
        PoolItem item = offer.item();
        return switch (item.type()) {
            case "charm" -> com.forgemaster.strengthen.Charm.displayName(item.id());
            case "blueprint" -> {
                ForgeMaterial fm = plugin.materials() == null ? null : plugin.materials().get(item.id());
                yield "<#ffd166>图纸 · </#ffd166>" + (fm == null ? item.id() : fm.display());
            }
            default -> (item.grade() == null ? "" : item.grade().colored() + " ")
                    + "<white>" + materialLabel(item) + "</white>";
        };
    }

    private String materialLabel(PoolItem item) {
        Material m = Material.matchMaterial(item.id());
        return m == null ? item.id() : MaterialGrades.pretty(m);
    }

    // ------------------------------------------------------------ 解析

    /** 今天日期（ISO，服务器本地时区）——口径理由见类注释 */
    private static String today() {
        return LocalDate.now().toString();
    }

    private static String primaryWorldName() {
        return Bukkit.getWorlds().isEmpty() ? "world" : Bukkit.getWorlds().get(0).getName();
    }

    private static double clamp(double v) {
        return Math.max(0.05, Math.min(1.0, v));
    }

    /**
     * 现读商品池并剔除坏条目：id 认不出、价格 ≤0、material 是武器装备材质的都不要。
     * 黑市只卖消耗型（符 / 原料 / 图纸），见类注释。
     */
    private List<PoolItem> pool() {
        List<PoolItem> out = new ArrayList<>();
        for (Map<?, ?> raw : plugin.getConfig().getMapList("blackmarket.pool")) {
            String type = str(raw.get("type"), "charm").toLowerCase(Locale.ROOT);
            String id = str(raw.get("id"), null);
            long price = raw.get("price") instanceof Number n ? n.longValue() : -1;
            int weight = raw.get("weight") instanceof Number n ? Math.max(1, n.intValue()) : 1;
            if (id == null || id.isBlank() || price <= 0) {
                continue;
            }
            switch (type) {
                case "charm" -> {
                    String norm = com.forgemaster.strengthen.Charm.normalize(id);
                    if (norm == null) {
                        continue;
                    }
                    out.add(new PoolItem("charm:" + norm, "charm", norm, null, price, weight));
                }
                case "material" -> {
                    Material m = Material.matchMaterial(id.toUpperCase(Locale.ROOT));
                    if (m == null || !m.isItem() || isGear(m)) {
                        continue;
                    }
                    MaterialGrade g = parseGrade(raw.get("grade"));
                    out.add(new PoolItem("material:" + m.name().toLowerCase(Locale.ROOT)
                            + ":" + g.name().toLowerCase(Locale.ROOT),
                            "material", m.name(), g, price, weight));
                }
                case "blueprint" -> {
                    ForgeMaterial fm = plugin.materials() == null ? null : plugin.materials().get(id);
                    if (fm == null) {
                        continue;
                    }
                    out.add(new PoolItem("blueprint:" + fm.id(), "blueprint", fm.id(), null, price, weight));
                }
                default -> {
                    // 未知类型不进池
                }
            }
        }
        return out;
    }

    /** 武器装备材质黑名单：黑市只卖消耗品，成品不许上架 */
    private static boolean isGear(Material m) {
        String n = m.name();
        return n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_PICKAXE")
                || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.endsWith("_HELMET")
                || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                || n.endsWith("_HORSE_ARMOR") || n.endsWith("_BANNER")
                || m == Material.BOW || m == Material.CROSSBOW || m == Material.TRIDENT
                || m == Material.MACE || m == Material.SHIELD || m == Material.ELYTRA;
    }

    private static MaterialGrade parseGrade(Object raw) {
        if (raw instanceof String s) {
            try {
                return MaterialGrade.valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // 掉回普通料
            }
        }
        return MaterialGrade.COMMON;
    }

    private static String str(Object raw, String def) {
        return raw instanceof String s && !s.isBlank() ? s : def;
    }
}
