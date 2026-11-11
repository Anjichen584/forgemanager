package com.forgemaster.affix;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.material.MaterialGrades;
import com.forgemaster.quality.Quality;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 词条重铸：花材料把成品上的第 {@code index} 条词条重抽一次，其它词条原样不动。
 *
 * <p>原来的玩法是「想换词条就重锻一把整剑」，代价太大。重铸只动一条：
 * 位置（Lore 顺序 / {@code affix_list} 顺序）保持不动，词条池、材料档位缩放
 * 全部沿用锻造时的逻辑（{@link AffixRegistry} + {@link AffixScaling}），
 * 所以重铸出来的数值区间跟新锻一把是同一条曲线。</p>
 *
 * <p>三条铁律：</p>
 * <ol>
 *   <li><b>同 id 不重复</b>：抽取前把「本条之外的已存在词条」全部排除，抽不满宁可不给，
 *       也绝不让同一件物品上出现两条同名词条。</li>
 *   <li><b>只删自己那条 modifier</b>：属性词条按固定键名 {@code fm_<词条id>} 精确匹配后删除，
 *       基础属性的 {@code fm_atk / fm_speed / fm_armor / fm_toughness} 一律不碰。</li>
 *   <li><b>Lore 干净</b>：重铸完把整个「◆ 词条」块摘掉重画（连带清掉历史遗留的重复块与
 *       占位行），不留重复行、不串到宝石块。</li>
 * </ol>
 *
 * <p>纯静态工具类，不在主类注册；调用方负责扣费（{@link #costOf} / {@link #materialAmountOf}）。</p>
 */
public final class Reforge {

    /** 基础属性 modifier 的固定键名：这些键永远不能被词条重铸删掉 */
    private static final List<String> BASE_MODIFIER_KEYS = List.of("fm_atk", "fm_speed", "fm_armor", "fm_toughness");

    /** Lore 里词条块的标题（与 ItemFactory 一致） */
    private static final String AFFIX_TITLE = "◆ 词条";
    /** Lore 里「无词条」占位行（与 ItemFactory 一致） */
    private static final String NO_AFFIX = "（无词条";

    /**
     * 装备专用词条池兜底：与 {@code ForgeManager.ARMOR_AFFIX_POOL} 保持一致。
     *
     * <p>那边是 private，本目录之外的文件不许动，所以这里复制一份；服主要改就配
     * {@code reforge.armor-pool}。防御词条全是防御向，抽不满也不回退到武器词条。</p>
     */
    private static final List<String> DEFAULT_ARMOR_POOL =
            com.forgemaster.forge.ForgeManager.ARMOR_AFFIX_POOL;

    /** 插件实例缓存：静态工具类不方便走构造注入，这里按类型找一次就记住 */
    private static volatile ForgingMasterPlugin cached;

    private Reforge() {
    }

    // ------------------------------------------------------------ 1. 重铸

    /**
     * 重抽第 {@code index} 条词条（0 开始，按物品上的 {@code affix_list} 顺序）。
     *
     * <p>直接改传进来的这一摞物品：属性 modifier、聚合 PDC、{@code affix_list} 与
     * Lore 词条块都会被就地更新。材料扣不扣由 {@code reforge.consume-materials} 决定，
     * 默认关——费用留给调用方统一结算（避免和 GUI 那边重复扣）。</p>
     *
     * @param player 操作者，用来做材料校验与音效；可为 null（纯逻辑调用）
     * @param item   要重铸的成品（锻造品）
     * @param index  第几条词条
     * @param random 随机源；null 则临时 new 一个
     * @return 给玩家看的结果文本（MiniMessage），失败时是红字原因
     */
    public static String reforge(Player player, ItemStack item, int index, Random random) {
        ForgingMasterPlugin plugin = plugin();
        if (plugin == null) {
            return "<red>锻造插件还没就绪，先等等。";
        }
        if (!plugin.settings().flag("reforge.enabled", true)) {
            return "<red>词条重铸已关闭。";
        }
        if (item == null || item.getType().isAir()) {
            return "<red>手里没有可重铸的东西。";
        }
        if (!plugin.items().isForged(item)) {
            return "<red>这不是锻造品，重铸不了。";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return "<red>这件物品读不出数据，重铸不了。";
        }

        List<String> ids = affixIds(meta);
        if (ids.isEmpty()) {
            return "<red>这件物品身上没有词条，重铸不了。";
        }
        if (index < 0 || index >= ids.size()) {
            return "<red>没有第 " + (index + 1) + " 条词条（这件物品一共 " + ids.size() + " 条）。";
        }
        AffixRegistry registry = plugin.affixes();
        Affix old = registry.get(ids.get(index).trim());
        if (old == null) {
            return "<red>这一条词条（<white>" + ids.get(index) + "</white>）在 config.yml 里已经找不到了，"
                    + "重铸会把它弄丢，先让服主补回来。";
        }
        if (registry.all().isEmpty()) {
            return "<red>词条表是空的（config.yml 的 affixes 段），重铸不了。";
        }

        ItemMeta probe = meta;
        ForgeMaterial mat = materialOf(probe, plugin);
        int tier = tierOf(mat);
        boolean armor = (mat != null && mat.armor())
                || probe.getPersistentDataContainer().has(Keys.ARMOR_SLOT, PersistentDataType.STRING);

        List<Affix> candidates = candidates(plugin, probe, ids, index, armor);
        if (candidates.isEmpty()) {
            return "<red>能抽的词条都被这件物品占满了，换不了新的（可以先把别的词条重铸掉）。";
        }
        Random rnd = random == null ? new Random() : random;
        Affix fresh = weightedPick(candidates, rnd);

        // 与锻造时完全同一条缩放曲线：材料档位决定基准与波动
        double newValue = fresh.roll(rnd,
                AffixScaling.valueScale(plugin, tier),
                AffixScaling.spreadScale(plugin, tier));

        // 材料 / 费用（默认交给调用方结算，这里只在开关打开时自己扣）
        Material costItem = materialItemOf(item, plugin);
        int need = materialAmountOf(item, index);
        if (player != null && plugin.settings().flag("reforge.consume-materials", false) && costItem != null) {
            int have = MaterialGrades.totalCount(player, costItem);
            if (have < need) {
                return "<red>材料不够：要 <white>" + need + "</white> 个 "
                        + MaterialGrades.pretty(costItem) + "，你只有 <white>" + have + "</white> 个。";
            }
            MaterialGrades.consume(player, costItem, need);
        }

        // 旧值：属性词条从 Lore / modifier 反查，自定义词条只有 Lore 一份
        Double oldValue = valueOf(probe, old);

        // ---- 写 ----
        EquipmentSlotGroup group = slotGroup(probe, mat, armor);
        // 1) 属性 modifier：先按固定键名精确摘掉旧那条，再写新的
        applyModifier(plugin, probe, old, fresh, newValue, group);
        // 2) 聚合 PDC（暴击 / 生命偷取 / 点燃 / 减伤）：做差量，宝石与剑灵加过的部分照旧保留
        applyAggregates(probe, old, oldValue, fresh, newValue);
        // 3) affix_list 原位替换，顺序不动
        ids.set(index, fresh.id().toLowerCase(Locale.ROOT));
        probe.getPersistentDataContainer().set(Keys.AFFIX_LIST, PersistentDataType.STRING, String.join(",", ids));
        // 4) Lore 词条块整块重画
        rewriteLore(plugin, probe, ids, index, fresh, newValue);

        com.forgemaster.util.Attrs.apply(item, probe);
        if (player != null && plugin.codex() != null) plugin.codex().registerAffix(player.getUniqueId(), fresh.id()); // 收藏图鉴登记（拓展第一档）
        // 5) 词条变了，套装可能刚凑齐 / 刚被拆散：重刷修饰符与 Lore 行
        com.forgemaster.item.ItemFactory.applyAffixSet(plugin, item);

        if (player != null && plugin.settings().sounds()) {
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.9f, 1.15f);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.45f, 1.6f);
        }

        String head = "<green>重铸成功</green> <dark_gray>[</dark_gray><white>" + (index + 1)
                + "<dark_gray>/</dark_gray>" + ids.size() + "<dark_gray>]</dark_gray> ";
        String tail = old.id().equalsIgnoreCase(fresh.id())
                ? " <dark_gray>（同一条词条，数值重抽）</dark_gray>"
                : "";
        return head + "<dark_gray>旧</dark_gray> <white>" + old.display() + " " + amount(old, oldValue)
                + "</white> <dark_gray>→</dark_gray> <green>" + fresh.display() + " "
                + amount(fresh, newValue) + "</green>" + tail;
    }

    // ------------------------------------------------------------ 2. 预览

    /**
     * 只描述，不改物品：这一条现在是什么、重铸可能落在什么区间、要花多少。
     *
     * @return 多行 MiniMessage 文本；参数不合法时是红字原因
     */
    public static String preview(ItemStack item, int index) {
        ForgingMasterPlugin plugin = plugin();
        if (plugin == null) {
            return "<red>锻造插件还没就绪，先等等。";
        }
        if (item == null || item.getType().isAir() || !plugin.items().isForged(item)) {
            return "<red>这不是锻造品，没有词条可看重铸。";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return "<red>这件物品读不出数据。";
        }
        List<String> ids = affixIds(meta);
        if (ids.isEmpty()) {
            return "<red>这件物品身上没有词条。";
        }
        if (index < 0 || index >= ids.size()) {
            return "<red>没有第 " + (index + 1) + " 条词条（这件物品一共 " + ids.size() + " 条）。";
        }
        AffixRegistry registry = plugin.affixes();
        Affix old = registry.get(ids.get(index).trim());
        if (old == null) {
            return "<red>这一条词条（<white>" + ids.get(index) + "</white>）在 config.yml 里已经找不到了。";
        }
        ForgeMaterial mat = materialOf(meta, plugin);
        int tier = tierOf(mat);
        boolean armor = (mat != null && mat.armor())
                || meta.getPersistentDataContainer().has(Keys.ARMOR_SLOT, PersistentDataType.STRING);
        List<Affix> candidates = candidates(plugin, meta, ids, index, armor);

        StringBuilder sb = new StringBuilder();
        sb.append("<gold>◆ 词条重铸</gold> <dark_gray>·</dark_gray> <gray>第 <white>")
                .append(index + 1).append("</white>/<white>").append(ids.size()).append("</white> 条 · 第 ")
                .append(tier).append(" 档材料</gray>\n");
        sb.append("<gray>现在: <white>").append(old.display()).append(" ")
                .append(amount(old, valueOf(meta, old))).append("</white>")
                .append(" <dark_gray>(本档可能 ")
                .append(rangeOf(plugin, old, tier)).append(")</dark_gray>\n");
        if (candidates.isEmpty()) {
            sb.append("<red>没有可换的词条（能抽的全被这件物品占了）。</red>\n");
        } else {
            sb.append("<gray>候选池: ");
            int shown = 0;
            for (Affix a : candidates) {
                if (shown > 0) {
                    sb.append(" <dark_gray>/</dark_gray> ");
                }
                if (shown >= 6) {
                    sb.append("<dark_gray>… 共 ").append(candidates.size()).append(" 条</dark_gray>");
                    break;
                }
                sb.append("<white>").append(a.display()).append("</white> <aqua>")
                        .append(rangeOf(plugin, a, tier)).append("</aqua>");
                shown++;
            }
            sb.append('\n').append("<dark_gray>（已排除这件物品上其它词条，不会撞名）</dark_gray>\n");
        }
        Material costItem = materialItemOf(item, plugin);
        sb.append("<gray>费用: <yellow>").append(costOf(item, index)).append("</yellow> 剑币");
        if (costItem != null) {
            sb.append(" <gray>+ <white>").append(materialAmountOf(item, index)).append("</white> 个 ")
                    .append(MaterialGrades.pretty(costItem));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 3. 费用

    /**
     * 重铸第 {@code index} 条要多少剑币（手续费）。
     *
     * <p>与 {@code strengthen.costOf} 同款算法：基数 + 每条递增，再乘材料档位系数与品质系数。
     * 档位越高、品质越好、词条越靠后越贵。</p>
     */
    public static long costOf(ItemStack item, int index) {
        ForgingMasterPlugin plugin = plugin();
        if (plugin == null) {
            return 0;
        }
        ForgeMaterial mat = item == null ? null : materialOf(item, plugin);
        int tier = tierOf(mat);
        Quality q = qualityOf(item);
        double base = Math.max(0, plugin.settings().num("reforge.cost.base", 160));
        double per = Math.max(0, plugin.settings().num("reforge.cost.per-index", 90));
        double factor = Math.max(1.0, plugin.settings().num("reforge.cost.tier-factor", 1.45));
        double qMult = Math.max(0.1, plugin.settings().num(
                "reforge.cost.quality." + q.name().toLowerCase(Locale.ROOT), defaultQualityMult(q)));
        double raw = (base + per * Math.max(0, index)) * Math.pow(factor, tier - 1) * qMult;
        return Math.max(1, Math.round(raw));
    }

    /** 重铸第 {@code index} 条要几个对应材料（材料就是这把东西自己的锻造材料） */
    public static int materialAmountOf(ItemStack item, int index) {
        ForgingMasterPlugin plugin = plugin();
        if (plugin == null) {
            return 0;
        }
        ForgeMaterial mat = item == null ? null : materialOf(item, plugin);
        int tier = tierOf(mat);
        double base = Math.max(0, plugin.settings().num("reforge.material.base", 2));
        double per = Math.max(0, plugin.settings().num("reforge.material.per-index", 1));
        double factor = Math.max(1.0, plugin.settings().num("reforge.material.tier-factor", 1.15));
        double raw = (base + per * Math.max(0, index)) * Math.pow(factor, tier - 1);
        return Math.max(1, (int) Math.ceil(raw));
    }

    /** 重铸消耗的材料物品；解析不出胚子（服主改过 materials）时返回 null */
    public static Material materialItemOf(ItemStack item) {
        ForgingMasterPlugin plugin = plugin();
        return item == null || plugin == null ? null : materialItemOf(item, plugin);
    }

    private static Material materialItemOf(ItemStack item, ForgingMasterPlugin plugin) {
        ForgeMaterial mat = materialOf(item, plugin);
        return mat == null ? null : mat.costItem();
    }

    // ------------------------------------------------------------ 4. 读物品

    /** 读物品上的词条 id 列表（按写入顺序，可改） */
    public static List<String> affixIdsOf(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return new ArrayList<>();
        }
        ItemMeta meta = item.getItemMeta();
        return meta == null ? new ArrayList<>() : affixIds(meta);
    }

    /**
     * meta 版 {@link #affixIdsOf(ItemStack)}（审计 #6）：调用方已经读过 meta 时
     * （受击单遍扫描）不再重复 getItemMeta。meta 为 null 返回空列表。
     */
    public static List<String> affixIdsOf(ItemMeta meta) {
        return meta == null ? new ArrayList<>() : affixIds(meta);
    }

    /** 物品上有几条词条 */
    public static int affixCount(ItemStack item) {
        return affixIdsOf(item).size();
    }

    private static List<String> affixIds(ItemMeta meta) {
        String csv = meta.getPersistentDataContainer().get(Keys.AFFIX_LIST, PersistentDataType.STRING);
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** 物品的剑胚 / 装备胚；解析统一走 util/MaterialLookup（audit 04 D5：三份近似实现合一） */
    private static ForgeMaterial materialOf(ItemStack item, ForgingMasterPlugin plugin) {
        return com.forgemaster.util.MaterialLookup.materialOf(plugin, item);
    }

    private static ForgeMaterial materialOf(ItemMeta meta, ForgingMasterPlugin plugin) {
        return com.forgemaster.util.MaterialLookup.materialOf(plugin, meta);
    }

    private static Quality qualityOf(ItemStack item) {
        // 语义与旧实现一致：null 物品兜底 COMMON；区别仅在键值非法时也从 null 变 COMMON（加固）
        return com.forgemaster.util.MaterialLookup.qualityOf(item);
    }

    private static int tierOf(ForgeMaterial mat) {
        // 胚子解析不出来时按第 3 档兜底：至少别拿 1 档去缩一个星陨剑
        return mat == null ? 3 : Math.max(1, Math.min(5, mat.tier()));
    }

    /**
     * 词条 modifier 该落哪个槽位：武器主手，装备按部位。
     *
     * <p>胚子被服主从 config 里删掉、解析不出 {@link ForgeMaterial} 时，退回物品上
     * 固化好的 {@code armor_slot} 标记——否则装备词条会挂到主手上，穿了等于没穿。</p>
     */
    static EquipmentSlotGroup slotGroup(ItemMeta meta, ForgeMaterial mat, boolean armor) {
        if (!armor) {
            return EquipmentSlotGroup.MAINHAND;
        }
        if (mat != null && mat.slot() != null) {
            return mat.slot().group();
        }
        String raw = meta.getPersistentDataContainer().get(Keys.ARMOR_SLOT, PersistentDataType.STRING);
        ForgeMaterial.Slot slot = ForgeMaterial.Slot.byName(raw);
        return slot == null ? EquipmentSlotGroup.MAINHAND : slot.group();
    }

    // ------------------------------------------------------------ 5. 抽词条（不重名）

    /**
     * 组装本次可抽的候选池：先按池来，再把「本条之外的已存在词条」全部剔掉。
     *
     * <p>抽取阶段就排除掉，而不是抽完再判重——这样循环次数是常数，也不会出现
     * 「抽了 30 次都撞名只能返回失败」的假概率。</p>
     */
    private static List<Affix> candidates(ForgingMasterPlugin plugin, ItemMeta meta,
                                          List<String> ids, int index, boolean armor) {
        AffixRegistry registry = plugin.affixes();
        // 本条之外已经占用的 id：它们不允许再出现第二次
        List<String> taken = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            if (i == index) {
                continue;
            }
            Affix a = registry.get(ids.get(i).trim());
            if (a != null) {
                taken.add(a.id().toLowerCase(Locale.ROOT));
            }
        }
        List<String> pool = armor
                ? plugin.settings().strList("reforge.armor-pool", new ArrayList<>(DEFAULT_ARMOR_POOL))
                : quenchPool(plugin, meta);

        LinkedHashSet<Affix> out = new LinkedHashSet<>();
        for (String id : pool) {
            Affix a = registry.get(id);
            if (a != null && !taken.contains(a.id().toLowerCase(Locale.ROOT))) {
                out.add(a);
            }
        }
        // 池子被占满：武器可以回退到全表（与 AffixRegistry.pick 的行为一致），
        // 装备坚持只在防御池里抽，绝不把「火焰附加」这种武器词条塞到胸甲上
        if (out.isEmpty() && !armor) {
            for (Affix a : registry.all()) {
                if (!taken.contains(a.id().toLowerCase(Locale.ROOT))) {
                    out.add(a);
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** 武器按淬火介质给的词条池；介质丢了就返回空表（= 全表兜底） */
    private static List<String> quenchPool(ForgingMasterPlugin plugin, ItemMeta meta) {
        String qid = meta.getPersistentDataContainer().get(Keys.QUENCH, PersistentDataType.STRING);
        if (qid == null || qid.isBlank() || "none".equalsIgnoreCase(qid)) {
            return List.of();
        }
        var quench = plugin.quenches().get(qid);
        return quench == null ? List.of() : quench.pool();
    }

    /** 按 weight 加权抽一条 */
    private static Affix weightedPick(List<Affix> list, Random random) {
        int total = 0;
        for (Affix a : list) {
            total += a.weight();
        }
        int roll = random.nextInt(Math.max(1, total));
        int cursor = 0;
        for (Affix a : list) {
            cursor += a.weight();
            if (roll < cursor) {
                return a;
            }
        }
        return list.get(0);
    }

    // ------------------------------------------------------------ 6. 写属性 / PDC

    /**
     * 某条词条在这件物品上的 modifier 键名——<b>必须与 {@code ItemFactory} 的写法一致</b>。
     *
     * <p>物品侧（{@code ItemFactory:247-249}）给护甲写的是
     * {@code fm_<词条id>_<部位>}，因为一次穿四件，同一条 {@code vitality} 若都用
     * {@code fm_vitality} 会共用一个键、穿上互相顶掉。而本类原先一律用
     * {@code fm_<词条id>}，于是重铸 / 词条符移植时「旧值删不到、新值另挂一条」——
     * 旧那条部位键永久留在物品上，与新键同时结算，等于把属性重铸成叠加（v2.23.1 修 P1）。</p>
     */
    static String affixKey(ItemMeta meta, Affix a) {
        String base = "fm_" + a.id().toLowerCase(Locale.ROOT);
        String raw = meta.getPersistentDataContainer().get(Keys.ARMOR_SLOT, PersistentDataType.STRING);
        ForgeMaterial.Slot slot = ForgeMaterial.Slot.byName(raw);
        return slot == null ? base : base + "_" + slot.name().toLowerCase(Locale.ROOT);
    }

    /** 摘旧词条的 modifier → 写新词条的 modifier；基础属性键名永远不碰 */
    static void applyModifier(ForgingMasterPlugin plugin, ItemMeta meta,
                                      Affix old, Affix fresh, double newValue, EquipmentSlotGroup group) {
        if (old.kind() == Affix.Kind.ATTRIBUTE && old.attribute() != null) {
            removeAffixModifier(meta, old.attribute(), affixKey(meta, old));
        }
        if (fresh.kind() == Affix.Kind.ATTRIBUTE && fresh.attribute() != null) {
            double applied = newValue;
            // 移速 / 击退会被反作弊盯，先过一遍兼容收敛（与 ItemFactory 一致）
            if (plugin.compat() != null) {
                applied = plugin.compat().cap(fresh.attribute(), newValue);
            }
            String key = affixKey(meta, fresh);
            if (com.forgemaster.util.Attrs.isBaseKey(key)) {
                // 服主把词条 id 起成 atk / speed / armor / toughness 了：宁可这条不加属性，
                // 也不能把基础属性那条覆盖掉
                plugin.getLogger().warning("词条 " + fresh.id() + " 的 modifier 键名 " + key
                        + " 与基础属性冲突，本次不写属性");
                return;
            }
            meta.addAttributeModifier(fresh.attribute(), new AttributeModifier(
                    new org.bukkit.NamespacedKey(plugin, key),
                    round(applied),
                    AttributeModifier.Operation.ADD_NUMBER,
                    group));
        }
    }

    /** 按固定键名精确移除某条词条的 modifier，别的词条与基础属性不受影响 */
    static void removeAffixModifier(ItemMeta meta, Attribute attribute, String keyName) {
        if (com.forgemaster.util.Attrs.isBaseKey(keyName)) {
            return; // 兜底：基础属性键名绝不在这里删
        }
        Collection<AttributeModifier> existing = meta.getAttributeModifiers(attribute);
        if (existing == null) {
            return;
        }
        for (AttributeModifier m : new ArrayList<>(existing)) {
            var key = m.getKey();
            if (key != null && keyName.equals(key.getKey())) {
                meta.removeAttributeModifier(attribute, m);
            }
        }
    }

    /**
     * 自定义词条的聚合 PDC 做差量：扣掉旧那条、加上新那条。
     *
     * <p>不用「按全表重算」是因为宝石（{@code GemService}）与剑灵也往同几个键里累加，
     * 重算会把它们的贡献抹掉。做差量只动本词条那一份。</p>
     */
    static void applyAggregates(ItemMeta meta, Affix old, Double oldValue,
                                        Affix fresh, double newValue) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        double dCrit = 0, dCritDmg = 0, dSteal = 0, dReduce = 0;
        int dFire = 0;
        // 旧值读不到（Lore 被别的插件洗过）就只加不减：宁可多给一点，也不误扣宝石的份额
        if (oldValue != null) {
            double v = oldValue;
            switch (old.kind()) {
                case CRIT_CHANCE -> dCrit -= v;
                case CRIT_DAMAGE -> dCritDmg -= v;
                case LIFESTEAL -> dSteal -= v;
                case FIRE -> dFire -= (int) Math.round(v) * 20;
                case DAMAGE_REDUCTION -> dReduce -= v;
                default -> {
                }
            }
        }
        switch (fresh.kind()) {
            case CRIT_CHANCE -> dCrit += newValue;
            case CRIT_DAMAGE -> dCritDmg += newValue;
            case LIFESTEAL -> dSteal += newValue;
            case FIRE -> dFire += (int) Math.round(newValue) * 20;
            case DAMAGE_REDUCTION -> dReduce += newValue;
            default -> {
            }
        }
        bumpDouble(pdc, Keys.CRIT_CHANCE, dCrit);
        bumpDouble(pdc, Keys.CRIT_DAMAGE, dCritDmg);
        bumpDouble(pdc, Keys.LIFESTEAL, dSteal);
        bumpDouble(pdc, Keys.DAMAGE_REDUCE, dReduce);
        bumpInt(pdc, Keys.FIRE_TICKS, dFire);
    }

    private static void bumpDouble(PersistentDataContainer pdc,
                                   org.bukkit.NamespacedKey key, double delta) {
        if (Math.abs(delta) < 1e-9) {
            return;
        }
        double cur = pdc.getOrDefault(key, PersistentDataType.DOUBLE, 0.0);
        pdc.set(key, PersistentDataType.DOUBLE, round(Math.max(0, cur + delta)));
    }

    private static void bumpInt(PersistentDataContainer pdc, org.bukkit.NamespacedKey key, int delta) {
        if (delta == 0) {
            return;
        }
        int cur = pdc.getOrDefault(key, PersistentDataType.INTEGER, 0);
        pdc.set(key, PersistentDataType.INTEGER, Math.max(0, cur + delta));
    }

    // ------------------------------------------------------------ 6.5 剥离 / 移植用

    /**
     * 把一条词条按给定数值写进物品（属性 → modifier，聚合 → PDC 增量）。
     *
     * <p>{@link #applyModifier} 是「先摘旧的再挂新的」，旧新同一条时等于原地重写，
     * 所以移植（词条符进剑）与剥离（剑上的词条拿掉）可以共用同一套键名规则，
     * 基础属性键名的护栏也自动继承。</p>
     */
    public static void addAffixRaw(ForgingMasterPlugin plugin, ItemMeta meta, Affix a, double value,
                            EquipmentSlotGroup group) {
        applyModifier(plugin, meta, a, a, value, group);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        switch (a.kind()) {
            case CRIT_CHANCE -> bumpDouble(pdc, Keys.CRIT_CHANCE, value);
            case CRIT_DAMAGE -> bumpDouble(pdc, Keys.CRIT_DAMAGE, value);
            case LIFESTEAL -> bumpDouble(pdc, Keys.LIFESTEAL, value);
            case DAMAGE_REDUCTION -> bumpDouble(pdc, Keys.DAMAGE_REDUCE, value);
            case FIRE -> bumpInt(pdc, Keys.FIRE_TICKS, (int) Math.round(value) * 20);
            default -> {
            }
        }
    }

    /** 把一条词条从物品上摘干净：属性 modifier + 聚合 PDC 的份额都不留 */
    public static void removeAffixRaw(ItemMeta meta, Affix a, Double value) {
        if (a.kind() == Affix.Kind.ATTRIBUTE && a.attribute() != null) {
            removeAffixModifier(meta, a.attribute(), affixKey(meta, a));
        }
        if (value == null) {
            // 读不到旧值就只摘 modifier：宁可留一点点聚合值，也不误扣宝石/剑灵的份额
            return;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        double v = value;
        switch (a.kind()) {
            case CRIT_CHANCE -> bumpDouble(pdc, Keys.CRIT_CHANCE, -v);
            case CRIT_DAMAGE -> bumpDouble(pdc, Keys.CRIT_DAMAGE, -v);
            case LIFESTEAL -> bumpDouble(pdc, Keys.LIFESTEAL, -v);
            case DAMAGE_REDUCTION -> bumpDouble(pdc, Keys.DAMAGE_REDUCE, -v);
            case FIRE -> bumpInt(pdc, Keys.FIRE_TICKS, -(int) Math.round(v) * 20);
            default -> {
            }
        }
    }

    /**
     * 按新的词条表重画 Lore 块（剥离 / 移植共用）。
     *
     * <p>非空时走 {@link #rewriteLore}：没动过的条目直接照抄原行，数值一个字节都不变。
     * 空了就把整块摘掉换成占位行——不能留一个只有标题的空「◆ 词条」。</p>
     */
    public static void redrawAffixes(ForgingMasterPlugin plugin, ItemMeta meta, List<String> ids,
                              Affix fresh, double freshValue) {
        if (!ids.isEmpty()) {
            int index = fresh == null ? -1 : ids.size() - 1;
            rewriteLore(plugin, meta, ids, index, fresh, freshValue);
            return;
        }
        List<Component> lore = meta.lore() == null
                ? new ArrayList<>()
                : new ArrayList<>(meta.lore());
        // 与 rewriteLore 同一套扫法：以「◆ 词条」标题为界整块清。
        // 不能见 ▪ 就删——宝石 / 附魔的镶嵌条目也是 ▪ 开头，剥空词条时会把它们连带清掉
        int at = -1;
        while (true) {
            int start = -1;
            for (int i = 0; i < lore.size(); i++) {
                if (isAffixTitle(plain(lore.get(i)))) {
                    start = i;
                    break;
                }
            }
            if (start < 0) {
                break;
            }
            if (at < 0) {
                at = start;
            }
            int end = start;
            while (end < lore.size()
                    && (isAffixTitle(plain(lore.get(end)))
                    || isAffixEntry(plain(lore.get(end)))
                    || isNoAffix(plain(lore.get(end))))) {
                end++;
            }
            lore.subList(start, end).clear();
        }
        // 孤立的占位行（上次剥空留下的）
        for (int i = lore.size() - 1; i >= 0; i--) {
            if (isNoAffix(plain(lore.get(i)))) {
                if (at < 0) {
                    at = i;
                }
                lore.remove(i);
            }
        }
        String placeholder = "<dark_gray>" + NO_AFFIX + " · 火候和锤法还不够）</dark_gray>";
        if (at < 0) {
            // 整块词条痕迹都被外部插件洗掉了：别插到「锻造者」后面，先找它
            at = lore.size();
            for (int i = 0; i < lore.size(); i++) {
                if (plain(lore.get(i)).startsWith("锻造者")) {
                    at = i;
                    break;
                }
            }
        }
        lore.add(Math.min(Math.max(0, at), lore.size()), line(placeholder));
        meta.lore(lore);
    }

    // ------------------------------------------------------------ 7. Lore 重画

    /**
     * 重画 Lore 的「◆ 词条」块。
     *
     * <p>做法与 {@code GemService} 的镶嵌孔块一致：先把所有旧的词条块整段摘掉
     * （重复块一起清，历史脏数据顺手修好），记住原位置，再按新的 affix_list 重画后放回去。
     * 没被重铸的条目直接照抄原行，数值一个字节都不动。</p>
     */
    static void rewriteLore(ForgingMasterPlugin plugin, ItemMeta meta, List<String> ids,
                                    int index, Affix fresh, double freshValue) {
        List<Component> lore = meta.lore() == null
                ? new ArrayList<>()
                : new ArrayList<>(meta.lore());

        List<Component> oldBlockLines = new ArrayList<>();
        int insertAt = -1;
        while (true) {
            int start = -1;
            for (int i = 0; i < lore.size(); i++) {
                if (isAffixTitle(plain(lore.get(i)))) {
                    start = i;
                    break;
                }
            }
            if (start < 0) {
                break;
            }
            if (insertAt < 0) {
                insertAt = start;
            }
            int end = start;
            while (end < lore.size()) {
                String p = plain(lore.get(end));
                if (isAffixTitle(p) || isAffixEntry(p) || isNoAffix(p)) {
                    end++;
                } else {
                    break;
                }
            }
            oldBlockLines.addAll(lore.subList(start + 1, end));
            lore.subList(start, end).clear();
        }
        // 占位行「（无词条 · 火候和锤法还不够）」也清掉，记一下位置
        for (int i = lore.size() - 1; i >= 0; i--) {
            if (isNoAffix(plain(lore.get(i)))) {
                if (insertAt < 0) {
                    insertAt = i;
                }
                lore.remove(i);
            }
        }

        List<Component> block = new ArrayList<>();
        // 与 ItemFactory 锻造时同一款渐变标题，纯文本仍是「◆ 词条」
        block.add(line("<gradient:#ffd166:#ff7a3d>◆ 词条</gradient>"));
        for (int i = 0; i < ids.size(); i++) {
            Affix a;
            double value;
            if (i == index) {
                a = fresh;
                value = freshValue;
            } else {
                a = plugin.affixes().get(ids.get(i).trim());
                if (a == null) {
                    continue; // 配置里已经删掉的词条不画，免得画出一条没有数值的脏行
                }
                Component kept = takeLine(oldBlockLines, a);
                if (kept != null) {
                    block.add(kept); // 原行照抄：不重铸的条目数值绝不动
                    continue;
                }
                Double v = valueOf(meta, a);
                value = v == null ? 0.0 : v;
            }
            block.add(line(a.line(value)));
        }

        if (insertAt < 0 || insertAt > lore.size()) {
            insertAt = lore.size();
            for (int i = 0; i < lore.size(); i++) {
                if (plain(lore.get(i)).startsWith("锻造者")) {
                    insertAt = i;
                    break;
                }
            }
        }
        lore.addAll(insertAt, block);
        meta.lore(lore);
    }

    private static boolean isAffixTitle(String plain) {
        return plain.startsWith(AFFIX_TITLE);
    }

    /** 词条行：Affix.line 画的都是「▪ 名字 +值 单位」 */
    private static boolean isAffixEntry(String plain) {
        return plain.startsWith("▪");
    }

    private static boolean isNoAffix(String plain) {
        return plain.startsWith(NO_AFFIX);
    }

    /** 从旧块里按显示名捞回一条词条的原始行（捞到就移走，避免重复使用） */
    private static Component takeLine(List<Component> oldBlockLines, Affix a) {
        String head = "▪ " + a.display() + " ";
        for (int i = 0; i < oldBlockLines.size(); i++) {
            if (plain(oldBlockLines.get(i)).startsWith(head)) {
                return oldBlockLines.remove(i);
            }
        }
        return null;
    }

    /** 读某条词条当前的实际数值：先翻 Lore（显示值），属性词条再退回 modifier */
    public static Double valueOf(ItemMeta meta, Affix a) {
        if (meta == null || a == null) {
            return null;
        }
        List<Component> lore = meta.lore();
        if (lore != null) {
            String head = "▪ " + a.display() + " ";
            for (Component c : lore) {
                String p = plain(c);
                if (p.startsWith(head)) {
                    Double parsed = parseAmount(p.substring(head.length()).trim(), a.percent());
                    if (parsed != null) {
                        return parsed;
                    }
                }
            }
        }
        if (a.kind() == Affix.Kind.ATTRIBUTE && a.attribute() != null) {
            Collection<AttributeModifier> mods = meta.getAttributeModifiers(a.attribute());
            if (mods != null) {
                String key = affixKey(meta, a);
                for (AttributeModifier m : mods) {
                    var k = m.getKey();
                    if (k != null && key.equals(k.getKey())) {
                        return m.getAmount();
                    }
                }
            }
        }
        return null;
    }

    /** "+12%" / "+1.5 攻击力" → 数值（百分比词条换算成小数） */
    private static Double parseAmount(String rest, boolean percent) {
        if (rest.isEmpty() || rest.charAt(0) != '+') {
            return null;
        }
        StringBuilder num = new StringBuilder();
        for (int i = 1; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (Character.isDigit(c) || c == '.') {
                num.append(c);
            } else {
                break;
            }
        }
        if (num.isEmpty()) {
            return null;
        }
        try {
            double shown = Double.parseDouble(num.toString());
            return percent ? shown / 100.0 : shown;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------ 8. 文本 / 数值

    /** 该词条在当前档位下的大致区间，如「+0.40 ~ +1.30」 */
    private static String rangeOf(ForgingMasterPlugin plugin, Affix a, int tier) {
        double vs = AffixScaling.valueScale(plugin, tier);
        double ss = AffixScaling.spreadScale(plugin, tier);
        double center = (a.min() + a.max()) / 2.0 * Math.max(0.0, vs);
        double half = (a.max() - a.min()) / 2.0 * Math.max(0.0, ss);
        double low = center - half;
        double high = center + half;
        if (a.kind() == Affix.Kind.FIRE) {
            low = Math.max(1, Math.rint(low));
            high = Math.max(1, Math.rint(high));
        } else {
            low = Math.max(0, low);
            high = Math.max(0, high);
        }
        return numText(a, low) + "~" + numText(a, high);
    }

    /** 词条数值的展示（带符号、带单位） */
    private static String amount(Affix a, Double value) {
        if (a == null) {
            return "?";
        }
        if (value == null) {
            return a.percent() ? "+?%" : "+?";
        }
        String base = a.percent()
                ? "+" + trim(value * 100.0) + "%"
                : "+" + trim(value);
        if (!a.percent() && a.unit() != null && !a.unit().isBlank()) {
            base += " " + a.unit();
        }
        return base;
    }

    /** 区间用的纯数值文本（不重复单位） */
    private static String numText(Affix a, double value) {
        return a.percent()
                ? trim(value * 100.0) + "%"
                : trim(value);
    }

    /** 与 Affix 内部一致的数字截断（整数不带小数点，小数最多两位） */
    private static String trim(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.001) {
            return String.valueOf((long) Math.rint(v));
        }
        return String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double defaultQualityMult(Quality q) {
        return switch (q) {
            case ROUGH -> 0.80;
            case COMMON -> 1.00;
            case FINE -> 1.25;
            case EPIC -> 1.70;
            case LEGENDARY -> 2.40;
            case MYTHIC -> 3.40;
        };
    }

    private static Component line(String mini) {
        return Msg.mm(mini).decoration(TextDecoration.ITALIC, false);
    }

    private static String plain(Component c) {
        return Msg.plain(c).trim();
    }

    /** 按类型找一次插件实例并缓存 */
    private static ForgingMasterPlugin plugin() {
        ForgingMasterPlugin fm = cached;
        if (fm != null && fm.isEnabled()) {
            return fm;
        }
        var pm = Bukkit.getPluginManager();
        if (pm == null) {
            return null;
        }
        for (Plugin p : pm.getPlugins()) {
            if (p instanceof ForgingMasterPlugin found) {
                cached = found;
                return found;
            }
        }
        return null;
    }
}
