package com.forgemaster.affix;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 词条套装：集齐指定几条第词条就触发一层「隐藏套装效果」。
 *
 * <p>词条现在是各自独立的加法，凑到哪条算哪条；套装把这堆散装词条变成
 * <b>组合目标</b>——玩家为了凑齐「锋利 + 吸血 + 暴击」会反复重铸第 3 条，
 * 这就是重铸/重锻的长期动力。</p>
 *
 * <p><b>判定</b>：读物品的 {@code affix_list}（{@link Reforge#affixIdsOf(ItemStack)}），
 * 看是否包含某套装 {@code requires} 的全部 id。多套可以同时成立（允许叠）。</p>
 *
 * <p><b>属性</b>：集齐后按 {@code ADD_SCALAR} 在 {@link Attribute#ATTACK_DAMAGE} 上挂
 * 一条独立修饰符，键名固定 {@code fm_set_<套装id>}。</p>
 * <ul>
 *   <li>重刷时<b>只删自己键名匹配的那几条</b>（前缀 {@value #MODIFIER_PREFIX}），
 *       {@code fm_atk / fm_speed / fm_armor / fm_toughness} 与剑意的 {@code fm_prof_atk} 一律不碰；</li>
 *   <li>幂等：反复 {@link #apply(ItemStack)} 不会堆修饰符；</li>
 *   <li>套装不满足（词条被重铸掉 / 全关掉）时撤掉对应修饰符。</li>
 * </ul>
 *
 * <p><b>Lore</b>：只维护自己那几行（前缀 {@value #LORE_PREFIX}），插在「◆ 词条」块后面
 * （没有词条块就落在「剑意」行下面），别人的区块一行都不重画。</p>
 *
 * <p><b>配置</b>：{@code config.yml} 的 {@code affix-set} 段。整套读不到时用内置的四套定义；
 * 某套的 {@code requires} 里有 {@code affixes} 段不存在的 id 时，那一套直接跳过并 warning，
 * 绝不抛异常（服主改配置不该把服务器搞崩）。</p>
 */
public final class AffixSetService {

    /** 属性修饰符键名前缀：每个套装一条，{@code fm_set_<套装id>} */
    public static final String MODIFIER_PREFIX = "fm_set_";

    /** Lore 里自己那几行的开头 */
    public static final String LORE_PREFIX = "套装";

    /** 词条块标题（与 {@code ItemFactory} / {@code Reforge} 一致） */
    private static final String AFFIX_TITLE = "◆ 词条";
    /** 「无词条」占位行（与 ItemFactory 一致） */
    private static final String NO_AFFIX = "（无词条";
    /** 词条行前缀（{@code Affix.line} 画的都是「▪ 名字 +值 单位」） */
    private static final String AFFIX_ENTRY = "▪";
    /** 剑意行前缀 */
    private static final String PROFICIENCY_PREFIX = "剑意";
    /** 锻造等级行前缀 */
    private static final String STRENGTHEN_PREFIX = "锻造等级";
    /** 履历行前缀（跟着锻造等级走） */
    private static final String HISTORY_PREFIX = "履历";

    private final ForgingMasterPlugin plugin;

    /** 配置解析结果缓存；config 重载后 ConfigurationSection 实例会换掉，据此自动失效 */
    private List<AffixSet> cache;
    private ConfigurationSection cacheSource;

    public AffixSetService(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    /** 插件实例（Listener 只有一个 service 参数时用它反查） */
    public ForgingMasterPlugin plugin() {
        return plugin;
    }

    // ------------------------------------------------------------ 开关

    public boolean enabled() {
        return plugin.settings().flag("affix-set.enabled", true);
    }

    /** 是否把套装行写进 Lore */
    public boolean loreEnabled() {
        return plugin.settings().flag("affix-set.lore", true);
    }

    /**
     * 没集齐但差一条时，是否显示灰色进度行（{@code 套装: 饮血 2/3}）。
     *
     * <p>默认<b>开</b>：套装玩法的乐趣一半在「差一条」的那口气上，
     * 只显示已集齐的话玩家永远不知道自己离触发还有多远，凑组合的动力就断了。
     * 灰色、不带效果描述，视觉上不抢已集齐的金色行。</p>
     */
    public boolean progressEnabled() {
        return plugin.settings().flag("affix-set.progress", true);
    }

    /** 受到伤害减免的总上限（多套叠加也不许减到免伤） */
    public double maxDamageReduce() {
        return Math.max(0, Math.min(1.0, plugin.settings().num("affix-set.max-damage-reduce", 0.30)));
    }

    // ------------------------------------------------------------ 套装表

    /** 全部生效中的套装（配置覆盖 + 内置兜底，已过滤掉词条 id 不存在的） */
    public List<AffixSet> all() {
        return sets();
    }

    /** 按 id 取套装；没有返回 null */
    public AffixSet set(String setId) {
        if (setId == null || setId.isBlank()) {
            return null;
        }
        String key = setId.trim().toLowerCase(Locale.ROOT);
        for (AffixSet s : sets()) {
            if (s.id().equals(key)) {
                return s;
            }
        }
        return null;
    }

    /** 套装显示名；id 不存在时原样返回 id（命令里不至于打印 null） */
    public String setName(String setId) {
        AffixSet s = set(setId);
        return s == null ? (setId == null ? "" : setId) : s.name();
    }

    /** 强制丢掉解析缓存（命令 /reload 后可选调用；不调也会因配置实例更换自动失效） */
    public void reload() {
        cache = null;
        cacheSource = null;
    }

    private List<AffixSet> sets() {
        ConfigurationSection section = plugin.settings().section("affix-set.sets");
        List<AffixSet> cached = cache;
        if (cached != null && cacheSource == section) {
            return cached;
        }
        List<AffixSet> built = build(section);
        cache = built;
        cacheSource = section;
        return built;
    }

    /**
     * 组装套装表：内置四套 → 配置逐项覆盖 / 追加自定义套 → 过滤掉词条 id 不存在的。
     *
     * <p>顺序固定为「内置在前、自定义在后」，Lore 与命令输出的顺序因此稳定。</p>
     */
    private List<AffixSet> build(ConfigurationSection root) {
        LinkedHashMap<String, AffixSet> map = new LinkedHashMap<>();
        for (AffixSet def : defaultSets()) {
            map.put(def.id(), def);
        }
        if (root != null) {
            for (String rawId : root.getKeys(false)) {
                ConfigurationSection sec = root.getConfigurationSection(rawId);
                if (sec == null) {
                    continue;
                }
                String id = rawId.trim().toLowerCase(Locale.ROOT);
                try {
                    map.put(id, parse(id, sec, map.get(id)));
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("解析词条套装 " + rawId + " 失败，已跳过: " + ex.getMessage());
                }
            }
        }
        List<AffixSet> out = new ArrayList<>(map.size());
        for (AffixSet s : map.values()) {
            if (s.requires().isEmpty()) {
                plugin.getLogger().warning("词条套装 " + s.id() + " 没配 requires，已跳过");
                continue;
            }
            String bad = unknownAffix(s);
            if (bad != null) {
                plugin.getLogger().warning("词条套装 " + s.id() + " 要求的词条 " + bad
                        + " 在 config.yml 的 affixes 段里不存在，该套装已跳过");
                continue;
            }
            out.add(s);
        }
        return List.copyOf(out);
    }

    /**
     * 一条 requires 里的词条 id 在注册表里查不到时返回它（该套装要跳过）。
     *
     * <p>词条表整个空着（配置还没载入 / 服主删干净了）时不判死——那不是这一套的问题，
     * 而且真往日志里刷一堆「词条不存在」只会盖住真正的原因。</p>
     */
    private String unknownAffix(AffixSet s) {
        AffixRegistry registry = plugin.affixes();
        if (registry == null || registry.all().isEmpty()) {
            return null;
        }
        for (String id : s.requires()) {
            if (registry.get(id) == null) {
                return id;
            }
        }
        return null;
    }

    /** 内置四套：配置读不到时的兜底，也是「服务端没配 affix-set 段」时的默认玩法 */
    private static List<AffixSet> defaultSets() {
        return List.of(
                // 锋利（锋锐）+ 吸血（饮血）+ 暴击（破绽）
                new AffixSet("bloodthirst", "饮血",
                        List.of("attack_damage", "lifesteal", "crit_chance"),
                        0.05, 0.08, 0, 0, 0, 0, 0, "命中回复 {heal}% 伤害"),
                // 护甲（厚甲）+ 韧性（坚壁）+ 减伤（庇护）
                new AffixSet("bulwark", "铁壁",
                        List.of("armor_plate", "tough_plate", "aegis"),
                        0.02, 0, 0.06, 0, 0, 0, 0, "受到伤害减少 {reduce}%"),
                // 锋利（锋锐）+ 暴伤（致命）+ 破甲位（轻捷：注册表里没有破甲词条）
                new AffixSet("executioner", "断罪",
                        List.of("attack_damage", "crit_damage", "attack_speed"),
                        0.06, 0, 0, 0.30, 0.15, 0, 0, "目标血量低于 {threshold}% 时伤害 +{execute}%"),
                // 点燃（燃烬）+ 中毒位（破绽：注册表里没有中毒词条）+ 吸血（饮血）
                new AffixSet("venom", "淬毒",
                        List.of("fire", "crit_chance", "lifesteal"),
                        0.03, 0, 0, 0, 0, 60, 0, "命中附加 {slow} 秒缓慢")
        );
    }

    /**
     * 解析一套的配置；字段缺失时用内置那套的值兜底（自定义套则用 0 / id）。
     *
     * @param id  套装 id（已小写）
     * @param sec 该套的配置段
     * @param def 同 id 的内置定义，可为 null（= 服主自定义的新套）
     */
    private static AffixSet parse(String id, ConfigurationSection sec, AffixSet def) {
        String name = sec.getString("name", def == null ? id : def.name());
        List<String> requires = lower(sec.getStringList("requires"));
        if (requires.isEmpty()) {
            requires = def == null ? List.of() : def.requires();
        }
        double bonusAttack = sec.getDouble("bonus-attack", def == null ? 0 : def.bonusAttack());
        double healPercent = sec.getDouble("heal-percent", def == null ? 0 : def.healPercent());
        double damageReduce = sec.getDouble("damage-reduce", def == null ? 0 : def.damageReduce());
        double threshold = sec.getDouble("execute-threshold", def == null ? 0 : def.executeThreshold());
        double executeBonus = sec.getDouble("execute-bonus", def == null ? 0 : def.executeBonus());
        // 缓慢时长优先认 slow-ticks，其次 slow-seconds（服主两种写法都见过）
        int slowTicks;
        if (sec.contains("slow-ticks")) {
            slowTicks = Math.max(0, sec.getInt("slow-ticks", def == null ? 0 : def.slowTicks()));
        } else {
            double fallbackSeconds = def == null ? 0 : def.slowTicks() / 20.0;
            double seconds = sec.getDouble("slow-seconds", fallbackSeconds);
            slowTicks = (int) Math.round(Math.max(0, seconds) * 20.0);
        }
        int slowAmplifier = Math.max(0, sec.getInt("slow-amplifier", def == null ? 0 : def.slowAmplifier()));
        String effect = sec.getString("effect-text", def == null ? "" : def.effectText());
        return new AffixSet(id, name, requires, bonusAttack, healPercent, damageReduce,
                threshold, executeBonus, slowTicks, slowAmplifier, effect);
    }

    private static List<String> lower(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw != null) {
            for (String s : raw) {
                if (s != null && !s.isBlank()) {
                    out.add(s.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------ 判定

    /** 已集齐的套装 id（按套装表顺序；可多套） */
    public List<String> matchedSets(ItemStack item) {
        return matchedSets(have(item));
    }

    /**
     * meta 版套装判定（审计 #6）：受击单遍扫描里同一件物品已经读过一次 meta，
     * 再走 {@link #matchedSets(ItemStack)} 会第二次 getItemMeta；这个重载让
     * {@code EquipmentDamageScan} 用同一份 meta 同时取词条减伤与套装减伤。
     */
    public List<String> matchedSetsByMeta(ItemMeta meta) {
        return matchedSets(haveOf(meta));
    }

    /**
     * 纯逻辑版：按词条 id 集合算已集齐的套装，<b>不碰物品</b>。
     *
     * <p>自检直接拿它断言匹配逻辑，不用造 ItemStack。</p>
     */
    public List<String> matchedSets(Collection<String> affixIds) {
        List<String> out = new ArrayList<>();
        for (AffixSet s : sets()) {
            if (s.satisfied(affixIds)) {
                out.add(s.id());
            }
        }
        return out;
    }

    /** 差一条就齐的套装 id（进度显示 / 命令提示用） */
    public List<String> nearSets(ItemStack item) {
        return nearOf(have(item), sets()).stream().map(AffixSet::id).toList();
    }

    /** 该物品是否集齐了指定套装 */
    public boolean satisfied(ItemStack item, String setId) {
        return satisfied(setId, have(item));
    }

    /** 纯逻辑版：给定词条 id 集合是否集齐指定套装 */
    public boolean satisfied(String setId, Collection<String> affixIds) {
        AffixSet s = set(setId);
        return s != null && s.satisfied(affixIds);
    }

    /** 所有集齐套装的总攻击加成（0.11 = +11%） */
    public double attackBonus(ItemStack item) {
        double sum = 0;
        List<String> ids = matchedSets(item);
        for (String id : ids) {
            AffixSet s = set(id);
            if (s != null) {
                sum += s.bonusAttack();
            }
        }
        return sum;
    }

    // ------------------------------------------------------------ 写属性 / Lore

    /**
     * 重刷属性与 Lore（幂等）。
     *
     * <p>先把自己以前挂的 {@code fm_set_*} 修饰符整批摘掉，再按当前集齐情况重挂——
     * 所以「反复 apply 不堆修饰符」「套装不满足时撤掉修饰符」两件事是同一个流程的
     * 两面，不需要额外的分支。</p>
     */
    public void apply(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        List<String> matched = enabled() ? matchedSets(item) : List.of();

        // 1) 摘掉自己以前挂的：只认 fm_set_ 前缀，基础键 / 词条键 / 剑意键一律不碰
        var existing = meta.getAttributeModifiers(Attribute.ATTACK_DAMAGE);
        if (existing != null) {
            for (AttributeModifier m : new ArrayList<>(existing)) {
                NamespacedKey key = m.getKey();
                if (key != null && key.getKey().startsWith(MODIFIER_PREFIX)) {
                    meta.removeAttributeModifier(Attribute.ATTACK_DAMAGE, m);
                }
            }
        }
        // 2) 按当前集齐的套装重挂：一套一条，键名 fm_set_<套装id>
        EquipmentSlotGroup group = groupOf(meta);
        for (String id : matched) {
            AffixSet s = set(id);
            if (s == null || s.bonusAttack() <= 0) {
                continue;
            }
            // ADD_SCALAR：在「基础 + 强化 + 词条」全部加起来之后再乘，独立一层
            meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(
                    new NamespacedKey(plugin, s.modifierKey()),
                    round(s.bonusAttack(), 4),
                    AttributeModifier.Operation.ADD_SCALAR,
                    group));
        }
        com.forgemaster.util.Attrs.apply(item, meta);
        rewriteLore(item);
    }

    /**
     * 只局部刷新 Lore 的套装行（不动属性），给「别的模块刚重画完 Lore」时补一行用。
     *
     * <p>幂等：没有套装行可画、也没旧行可删时直接返回，一个字节都不写。</p>
     */
    public void rewriteLore(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());

        // 1) 先清掉历史遗留的自己那几行（可能有多行 / 重复行），记一下原位置
        int removedAt = -1;
        for (int i = lore.size() - 1; i >= 0; i--) {
            if (isSetLine(plain(lore.get(i)))) {
                removedAt = i;
                lore.remove(i);
            }
        }
        // 2) 该画什么
        List<String> texts = (enabled() && loreEnabled()) ? lines(item) : List.of();
        if (texts.isEmpty() && removedAt < 0) {
            return; // 没东西可画也没旧行可删：不写物品
        }
        // 3) 插在「◆ 词条」块后面 / 「剑意」行下面
        int at = insertIndex(lore);
        if (at < 0) {
            at = removedAt >= 0 ? removedAt : lore.size();
        }
        at = Math.max(0, Math.min(at, lore.size()));
        List<Component> block = new ArrayList<>(texts.size());
        for (String t : texts) {
            block.add(line(t));
        }
        lore.addAll(at, block);
        meta.lore(lore);
        com.forgemaster.util.Attrs.apply(item, meta);
    }

    // ------------------------------------------------------------ Lore 文本

    /**
     * Lore 里的套装行（MiniMessage，多行用 {@code \n} 连接；没有可显示的行时返回空串）。
     *
     * <p>格式：{@code 套装: 饮血 · 命中回复 8% 伤害}；
     * 没集齐但差一条时是灰色 {@code 套装: 饮血 2/3}。</p>
     */
    public String line(ItemStack item) {
        List<String> lines = lines(item);
        return lines.isEmpty() ? "" : String.join("\n", lines);
    }

    /** 拆开版（Lore 写入 / 自检按行断言更方便） */
    public List<String> lines(ItemStack item) {
        List<String> out = new ArrayList<>();
        if (!enabled()) {
            return out;
        }
        List<String> have = have(item);
        List<AffixSet> all = sets();
        List<AffixSet> matched = matchedOf(have, all);
        for (AffixSet s : matched) {
            String effect = s.effect();
            out.add("<gray>" + LORE_PREFIX + ": <#ffd166>" + s.name() + "</#ffd166>"
                    + (effect.isEmpty() ? "" : " <dark_gray>·</dark_gray> <gray>" + effect));
        }
        if (!out.isEmpty() || !progressEnabled()) {
            return out;
        }
        // 一条都没集齐：灰色进度行，最多两条，别把 Lore 撑成进度表
        int shown = 0;
        for (AffixSet s : nearOf(have, all)) {
            if (shown >= 2) {
                break;
            }
            out.add("<dark_gray>" + LORE_PREFIX + ": " + s.name() + " "
                    + s.haveCount(have) + "/" + s.requires().size() + "</dark_gray>");
            shown++;
        }
        return out;
    }

    /**
     * 命令用的多行状态文本（纯读，不改物品）。
     */
    public String describe(ItemStack item) {
        if (!enabled()) {
            return "<red>词条套装已关闭。";
        }
        List<String> have = have(item);
        List<AffixSet> all = sets();
        List<AffixSet> matched = matchedOf(have, all);
        StringBuilder sb = new StringBuilder();
        sb.append("<gray>词条套装: <white>已集齐 ").append(matched.size())
                .append("</white><dark_gray>/</dark_gray><white>").append(all.size()).append("</white>");
        if (all.isEmpty()) {
            sb.append("<newline><red>一套都没有（config.yml 的 affix-set.sets 是空的）");
            return sb.toString();
        }
        if (matched.isEmpty()) {
            sb.append("<newline><dark_gray>（还没有集齐的套装）</dark_gray>");
        }
        for (AffixSet s : matched) {
            sb.append("<newline><gray>✔ <#ffd166>").append(s.name()).append("</#ffd166>")
                    .append(" <dark_gray>·</dark_gray> <gray>").append(s.effect());
            if (s.bonusAttack() > 0) {
                sb.append(" <aqua>(攻击 +").append(Msg.fmt(s.bonusAttack() * 100, 0)).append("%)</aqua>");
            }
        }
        for (AffixSet s : nearOf(have, all)) {
            sb.append("<newline><gray>… ").append(s.name())
                    .append(" <white>").append(s.haveCount(have)).append("</white><dark_gray>/</dark_gray><white>")
                    .append(s.requires().size()).append("</white> <dark_gray>· 还差 ")
                    .append(String.join("、", missingNames(s, have))).append("</dark_gray>");
        }
        sb.append("<newline><gray>套装攻击加成: <aqua>+")
                .append(Msg.fmt(attackBonus(item) * 100, 0)).append("%</aqua>");
        return sb.toString();
    }

    /** 差的那几条的显示名（用词条表的 display，查不到退回 id） */
    private List<String> missingNames(AffixSet s, List<String> have) {
        List<String> out = new ArrayList<>();
        for (String id : s.missing(have)) {
            Affix a = plugin.affixes() == null ? null : plugin.affixes().get(id);
            out.add(a == null ? id : a.display());
        }
        return out.isEmpty() ? List.of("—") : out;
    }

    // ------------------------------------------------------------ 内部

    /** 物品上的词条 id（小写、去重、保序） */
    private List<String> have(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return new ArrayList<>();
        }
        ItemMeta meta = item.getItemMeta();
        return meta == null ? new ArrayList<>() : haveOf(meta);
    }

    /** meta 版 {@link #have(ItemStack)}：给受击单遍扫描复用同一份 meta（审计 #6） */
    private List<String> haveOf(ItemMeta meta) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String id : Reforge.affixIdsOf(meta)) {
            if (id != null && !id.isBlank()) {
                out.add(id.trim().toLowerCase(Locale.ROOT));
            }
        }
        return new ArrayList<>(out);
    }

    private List<AffixSet> matchedOf(ItemStack item) {
        return matchedOf(have(item), sets());
    }

    private static List<AffixSet> matchedOf(List<String> have, List<AffixSet> all) {
        List<AffixSet> out = new ArrayList<>();
        for (AffixSet s : all) {
            if (s.satisfied(have)) {
                out.add(s);
            }
        }
        return out;
    }

    /** 差一条就齐的套装（保持套装表顺序） */
    private static List<AffixSet> nearOf(List<String> have, List<AffixSet> all) {
        List<AffixSet> out = new ArrayList<>();
        for (AffixSet s : all) {
            if (!s.requires().isEmpty() && s.missing(have).size() == 1) {
                out.add(s);
            }
        }
        return out;
    }

    /** 把套装行插在哪：「◆ 词条」块之后 → 「剑意」之后 → 「锻造等级 / 履历」之后 → -1 */
    private static int insertIndex(List<Component> lore) {
        int at = -1;
        for (int i = 0; i < lore.size(); i++) {
            String p = plain(lore.get(i));
            if (p.startsWith(AFFIX_TITLE) || p.startsWith(NO_AFFIX)) {
                at = i + 1;
            }
        }
        if (at >= 0) {
            // 顺着词条行往下走，落到词条块最后一行之后
            while (at < lore.size() && plain(lore.get(at)).startsWith(AFFIX_ENTRY)) {
                at++;
            }
            return at;
        }
        for (int i = 0; i < lore.size(); i++) {
            if (plain(lore.get(i)).startsWith(PROFICIENCY_PREFIX)) {
                return i + 1;
            }
        }
        for (int i = 0; i < lore.size(); i++) {
            if (plain(lore.get(i)).startsWith(STRENGTHEN_PREFIX)) {
                int j = i + 1;
                while (j < lore.size() && plain(lore.get(j)).startsWith(HISTORY_PREFIX)) {
                    j++;
                }
                return j;
            }
        }
        return -1;
    }

    private static boolean isSetLine(String plain) {
        return plain != null && plain.startsWith(LORE_PREFIX);
    }

    /** 装备部位决定 modifier 的 equipment group；武器一律主手 */
    private static EquipmentSlotGroup groupOf(ItemMeta meta) {
        ForgeMaterial.Slot slot = ForgeMaterial.Slot.byName(
                meta.getPersistentDataContainer().get(Keys.ARMOR_SLOT, PersistentDataType.STRING));
        return slot == null ? EquipmentSlotGroup.MAINHAND : slot.group();
    }

    private static Component line(String mini) {
        return Msg.mm(mini).decoration(TextDecoration.ITALIC, false);
    }

    private static String plain(Component c) {
        return Msg.plain(c).trim();
    }

    private static double round(double v, int decimals) {
        double f = Math.pow(10, Math.max(0, decimals));
        return Math.round(v * f) / f;
    }
}
