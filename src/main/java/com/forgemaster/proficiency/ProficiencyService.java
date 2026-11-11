package com.forgemaster.proficiency;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * 剑意（武器熟练度）。
 *
 * <p>锻造品被玩家拿来砍怪，每砍死一只 +1 剑意，剑意攒过门槛就升一档，
 * 档位给一层<b>独立</b>的攻击加成。老武器从此不只是数值容器——用得越久，
 * 它越认这个人。</p>
 *
 * <p>四道门槛 + 一个 0 档（生涩），共 N+1 个称号：</p>
 * <table border="1">
 *   <caption>档位阶梯</caption>
 *   <tr><th>档</th><th>剑意</th><th>称号</th><th>攻击</th></tr>
 *   <tr><td>0</td><td>0</td><td>生涩</td><td>+0%</td></tr>
 *   <tr><td>1</td><td>100</td><td>顺手</td><td>+2%</td></tr>
 *   <tr><td>2</td><td>500</td><td>趁手</td><td>+4%</td></tr>
 *   <tr><td>3</td><td>2000</td><td>心意相通</td><td>+7%</td></tr>
 *   <tr><td>4</td><td>5000</td><td>剑即是我</td><td>+10%</td></tr>
 * </table>
 *
 * <p><b>和强化的关系</b>：强化是「基础属性快照 × 倍率」，用 {@code Operation.ADD_NUMBER}
 * 写基础键 {@code fm_atk} 等；剑意是<b>另一层</b>，用 {@code Operation.ADD_SCALAR}
 * 写在独立键 {@code fm_prof_atk} 上。原版属性结算顺序是
 * ADD_NUMBER → ADD_SCALAR → MULTIPLY_SCALAR_1，所以剑意天然落在「强化放大之后」，
 * 两者同时生效、互不覆盖——强化只删自己那四个键，剑意只删 {@code fm_prof_atk}。</p>
 *
 * <p><b>存哪</b>：剑意值存在物品自己的 PDC（键 {@code proficiency_kills}），跟着物品走，
 * 交易、掉落、放箱子都不会丢；不需要额外的存档文件。本包内的 NamespacedKey 自己建，
 * 不往 {@code util/Keys} 里塞。</p>
 *
 * <p><b>Lore</b>：只维护自己那一行 {@code 剑意: …}，不重画别人的区块
 * （品质 / 剑胚 / 耐久 / 词条 / 宝石孔 / 锻造者 都不碰）。</p>
 */
public final class ProficiencyService {

    /**
     * 剑意加成用的属性 modifier 键名。
     *
     * <p><b>固定</b>：每次重刷只删这一条再加新的，绝不碰
     * {@code fm_atk} / {@code fm_speed} / {@code fm_armor} / {@code fm_toughness}
     * 这些基础属性键，也不碰词条 / 宝石挂的键。</p>
     */
    public static final String ATTRIBUTE_KEY = "fm_prof_atk";

    /** Lore 里自己那一行的开头 */
    public static final String LORE_PREFIX = "剑意";

    /** 内置门槛（配置读不到时兜底） */
    private static final int[] DEFAULT_TIERS = {100, 500, 2000, 5000};

    /** 内置称号，比门槛多一个（0 档「生涩」） */
    private static final String[] DEFAULT_NAMES = {"生涩", "顺手", "趁手", "心意相通", "剑即是我"};

    /** 内置加成，对应 1..N 档（0 档没有加成） */
    private static final double[] DEFAULT_BONUSES = {0.02, 0.04, 0.07, 0.10};

    private final ForgingMasterPlugin plugin;
    private final NamespacedKey killsKey;

    public ProficiencyService(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        // 本包自建键，不动 util/Keys
        this.killsKey = new NamespacedKey(plugin, "proficiency_kills");
    }

    // ------------------------------------------------------------ 档位表

    public boolean enabled() {
        return plugin.settings().flag("proficiency.enabled", true);
    }

    /** 剑意值要写进 Lore 吗（关掉只保留属性加成） */
    public boolean loreEnabled() {
        return plugin.settings().flag("proficiency.lore", true);
    }

    /**
     * 各档门槛，严格递增。
     *
     * <p>读 {@code proficiency.tiers}（int 列表 / 字符串列表都行，按字符串解析）。
     * 解析失败或出现非递增的脏值时跳过那一条；整条读不到就用内置阶梯。</p>
     */
    public int[] tiers() {
        List<String> raw = plugin.settings().strList("proficiency.tiers", null);
        if (raw == null || raw.isEmpty()) {
            return DEFAULT_TIERS.clone();
        }
        int[] buf = new int[raw.size()];
        int n = 0;
        int last = 0;
        for (String s : raw) {
            int v = parseInt(s, -1);
            if (v <= last) {
                continue;
            }
            buf[n++] = v;
            last = v;
        }
        return n == 0 ? DEFAULT_TIERS.clone() : Arrays.copyOf(buf, n);
    }

    /** 最高档位（= 门槛个数） */
    public int maxTier() {
        return tiers().length;
    }

    /**
     * 纯函数：剑意值 → 档位（0..N）。
     *
     * <p>不读物品、不读玩家，自检可以直接断言。</p>
     */
    public int tierIndexOf(int kills) {
        int[] table = tiers();
        int tier = 0;
        for (int i = 0; i < table.length; i++) {
            if (kills >= table[i]) {
                tier = i + 1;
            }
        }
        return tier;
    }

    /** 0 档起算的称号；越界按内置名字兜底 */
    public String tierName(int tier) {
        int idx = Math.max(0, tier);
        List<String> names = plugin.settings().strList("proficiency.tier-names", null);
        if (names != null && idx < names.size()) {
            String s = names.get(idx);
            if (s != null && !s.isBlank()) {
                return s.trim();
            }
        }
        return idx < DEFAULT_NAMES.length ? DEFAULT_NAMES[idx] : "第 " + idx + " 档";
    }

    /** 称号配色，0 档灰、满档金 */
    public String tierColor(int tier) {
        return switch (Math.max(0, tier)) {
            case 0 -> "#9e9e9e";
            case 1 -> "#7fd3ff";
            case 2 -> "#4ee36b";
            case 3 -> "#9f7aea";
            default -> "#ffd166";
        };
    }

    /**
     * 该档位的攻击加成（0.02 = +2%）。
     *
     * <p>读 {@code proficiency.bonuses}；缺项按内置阶梯兜底，0 档恒为 0。</p>
     */
    public double bonusOf(int tier) {
        if (tier <= 0) {
            return 0;
        }
        int idx = tier - 1;
        List<String> raw = plugin.settings().strList("proficiency.bonuses", null);
        if (raw != null && idx < raw.size()) {
            double v = parseDouble(raw.get(idx), Double.NaN);
            if (!Double.isNaN(v) && v >= 0) {
                return v;
            }
        }
        return idx < DEFAULT_BONUSES.length ? DEFAULT_BONUSES[idx] : DEFAULT_BONUSES[DEFAULT_BONUSES.length - 1];
    }

    /**
     * 下一档的门槛<b>绝对值</b>（不是「还差多少」）；满档返回 -1。
     *
     * <p>例：门槛 {@code [100,500,2000,5000]} 时 {@code nextThreshold(0) == 100}、
     * {@code nextThreshold(2) == 2000}、{@code nextThreshold(4) == -1}。
     * 想看还差多少用 {@link #remainingToNext(ItemStack)}。</p>
     */
    public int nextThreshold(int tier) {
        int[] table = tiers();
        int idx = Math.max(0, tier);
        return idx >= table.length ? -1 : table[idx];
    }

    /** 离下一档还差多少剑意；满档返回 0 */
    public int remainingToNext(ItemStack item) {
        int next = nextThreshold(tierOf(item));
        if (next <= 0) {
            return 0;
        }
        return Math.max(0, next - killsOf(item));
    }

    // ------------------------------------------------------------ 读物品

    /** 当前剑意值；非锻造品 / 没打过 0 */
    public int killsOf(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return 0;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return 0;
        }
        Integer v = meta.getPersistentDataContainer().get(killsKey, PersistentDataType.INTEGER);
        return v == null ? 0 : Math.max(0, v);
    }

    /** 当前档位 0..N（0 = 生涩） */
    public int tierOf(ItemStack item) {
        return tierIndexOf(killsOf(item));
    }

    /**
     * Lore 那一行（MiniMessage，未转 Component）。
     *
     * <p>格式：{@code 剑意: 心意相通 · 1247/2000 (+7% 攻击)}；
     * 0 档不带加成，写 {@code 剑意: 生涩 · 12/100}。</p>
     *
     * <p>分母是<b>下一档门槛</b>，分子是当前剑意——所以这一行本身就是进度条。
     * 满档时分母改成自己的门槛（{@code 5000/5000}）。</p>
     */
    public String line(ItemStack item) {
        int kills = killsOf(item);
        int tier = tierIndexOf(kills);
        int[] table = tiers();
        int next = nextThreshold(tier);
        int goal = next > 0 ? next : (table.length == 0 ? Math.max(1, kills)
                : table[Math.min(tier, table.length) - 1]);
        String color = tierColor(tier);
        StringBuilder sb = new StringBuilder();
        sb.append("<gray>").append(LORE_PREFIX).append(": <").append(color).append(">")
                .append(tierName(tier)).append("</").append(color).append(">")
                .append(" <dark_gray>·</dark_gray> ")
                .append("<white>").append(kills).append("</white>")
                .append("<dark_gray>/</dark_gray><gray>").append(Math.max(1, goal)).append("</gray>");
        double bonus = bonusOf(tier);
        if (bonus > 0) {
            sb.append(" <aqua>(+").append(Msg.fmt(bonus * 100, 0)).append("% 攻击)</aqua>");
        }
        return sb.toString();
    }

    /** 命令用的多行状态文本（纯读，不改物品） */
    public String describe(ItemStack item) {
        if (!plugin.items().isForged(item)) {
            return "<red>这不是锻造品，没有剑意。";
        }
        int kills = killsOf(item);
        int tier = tierIndexOf(kills);
        int next = nextThreshold(tier);
        StringBuilder sb = new StringBuilder();
        sb.append("<gray>剑意: <white>").append(kills).append("</white> <dark_gray>·</dark_gray> <")
                .append(tierColor(tier)).append(">").append(tierName(tier))
                .append("</").append(tierColor(tier)).append(">");
        if (next < 0) {
            sb.append(" <gold>（已满档）</gold>");
        } else {
            sb.append("<newline><gray>下一档 <white>").append(tierName(tier + 1))
                    .append("</white> <dark_gray>·</dark_gray> <gray>还差 <white>")
                    .append(Math.max(0, next - kills)).append("</white> 剑意");
        }
        sb.append("<newline><gray>攻击加成: <aqua>+")
                .append(Msg.fmt(bonusOf(tier) * 100, 0)).append("%</aqua>");
        return sb.toString();
    }

    // ------------------------------------------------------------ 写

    /**
     * 直接写剑意值（管理命令 / 自检用），随后重刷属性与 Lore。
     *
     * @return 写入成功（非锻造品返回 false）
     */
    public boolean setKills(ItemStack item, int kills) {
        if (!plugin.items().isForged(item)) {
            return false;
        }
        writeKills(item, Math.max(0, kills));
        apply(item);
        return true;
    }

    /**
     * 加 1 剑意：写 PDC → 按新档位重刷属性 → 刷新 Lore 一行 → 跨档时提示 + 音效。
     *
     * <p>{@code player} 允许为 null（自检 / 后台写入时只算数不提示）。</p>
     */
    public void addKill(Player player, ItemStack item) {
        if (!enabled() || item == null || item.getType().isAir()) {
            return;
        }
        if (!plugin.items().isForged(item)) {
            return;
        }
        int before = killsOf(item);
        int oldTier = tierIndexOf(before);
        int after = before + 1;
        writeKills(item, after);
        int newTier = tierIndexOf(after);
        // 先刷新属性再提示：提示里读到的加成一定是已经生效的那个
        apply(item);
        if (newTier > oldTier) {
            celebrate(player, newTier, after);
        }
    }

    /**
     * 只按当前档位重刷属性（不改剑意值），顺带补一次 Lore 行。
     *
     * <p>强化 / 宝石这些模块重画 Lore 之后可以调一次，把剑意行补回去；
     * 幂等，反复调不会堆行。</p>
     */
    public void apply(ItemStack item) {
        if (item == null || item.getType().isAir() || !plugin.items().isForged(item)) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        int tier = tierOf(item);
        double bonus = bonusOf(tier);
        EquipmentSlotGroup group = groupOf(meta);
        // 精确替换：只删自己那条键，基础键 / 词条键原样留着
        Collection<AttributeModifier> existing = meta.getAttributeModifiers(Attribute.ATTACK_DAMAGE);
        if (existing != null) {
            for (AttributeModifier m : new ArrayList<>(existing)) {
                NamespacedKey key = m.getKey();
                if (key != null && ATTRIBUTE_KEY.equals(key.getKey())) {
                    meta.removeAttributeModifier(Attribute.ATTACK_DAMAGE, m);
                }
            }
        }
        if (bonus > 0) {
            // ADD_SCALAR：在「基础 + 强化 + 词条」全部加完之后再乘，独立一层
            meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(
                    new NamespacedKey(plugin, ATTRIBUTE_KEY), bonus,
                    AttributeModifier.Operation.ADD_SCALAR, group));
        }
        com.forgemaster.util.Attrs.apply(item, meta);
        if (loreEnabled()) {
            rewriteLore(item);
        }
    }

    /**
     * 保底回写：把剑意值与 Lore 行重新钉回物品。
     *
     * <p>剑意的唯一真相就是 PDC，本来不会丢；这里做两件幂等的小事——
     * 重写一次 PDC（数值不变），以及<b>只在 Lore 行缺失时</b>补回那一行
     * （别的模块重画 Lore 时可能把它漏掉）。玩家死亡时调一次，成本可忽略。</p>
     */
    public void persist(ItemStack item) {
        if (item == null || item.getType().isAir() || !plugin.items().isForged(item)) {
            return;
        }
        int kills = killsOf(item);
        if (kills <= 0) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(killsKey, PersistentDataType.INTEGER, kills);
        com.forgemaster.util.Attrs.apply(item, meta);
        if (loreEnabled() && !hasLoreLine(item)) {
            rewriteLore(item);
        }
    }

    // ------------------------------------------------------------ Lore

    /**
     * 重写 Lore 里的「剑意」一行。
     *
     * <p>规则：</p>
     * <ol>
     *   <li>已有一行以「剑意」开头 → 原位替换（不堆行）；</li>
     *   <li>否则插在「锻造等级」行下面，如果它后面紧跟着「履历」行，就插在履历下面；</li>
     *   <li>连「锻造等级」都找不到 → 插在「耐久」行后面；</li>
     *   <li>都没有 → 追加到末尾。</li>
     * </ol>
     *
     * <p>只动自己这一行，其它区块（品质 / 词条 / 宝石孔 / 锻造者）原样保留。</p>
     */
    public void rewriteLore(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        List<net.kyori.adventure.text.Component> lore = meta.lore() == null
                ? new ArrayList<>()
                : new ArrayList<>(meta.lore());
        net.kyori.adventure.text.Component comp = Msg.mm(line(item))
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
        // 1) 已有一行「剑意: …」→ 原位替换
        for (int i = 0; i < lore.size(); i++) {
            if (plainOf(lore.get(i)).startsWith(LORE_PREFIX)) {
                lore.set(i, comp);
                meta.lore(lore);
                com.forgemaster.util.Attrs.apply(item, meta);
                return;
            }
        }
        // 2) 找「锻造等级」，跳过紧跟其后的「履历」，插在两者下面
        int at = -1;
        for (int i = 0; i < lore.size(); i++) {
            if (plainOf(lore.get(i)).startsWith("锻造等级")) {
                at = i + 1;
                while (at < lore.size() && plainOf(lore.get(at)).startsWith("履历")) {
                    at++;
                }
                break;
            }
        }
        // 3) 没有锻造等级行 → 落在「耐久」下面
        if (at < 0) {
            for (int i = 0; i < lore.size(); i++) {
                if (plainOf(lore.get(i)).startsWith("耐久")) {
                    at = i + 1;
                    break;
                }
            }
        }
        // 4) 兜底追加
        if (at < 0 || at > lore.size()) {
            at = lore.size();
        }
        lore.add(at, comp);
        meta.lore(lore);
        com.forgemaster.util.Attrs.apply(item, meta);
    }

    /** 物品 Lore 里已经有剑意行了吗 */
    public boolean hasLoreLine(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.lore() == null) {
            return false;
        }
        for (net.kyori.adventure.text.Component c : meta.lore()) {
            if (plainOf(c).startsWith(LORE_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 跨档提示

    private void celebrate(Player player, int tier, int kills) {
        if (player != null) {
            String raw = plugin.settings().str("proficiency.tier-up-message",
                    "<#ffd166>剑意精进</#ffd166> <dark_gray>»</dark_gray> <gray>这把剑已至 "
                            + "<white>{name}</white> <dark_gray>(剑意 {kills} · 攻击 +{bonus}%)</dark_gray>");
            String text = raw
                    .replace("{name}", tierName(tier))
                    .replace("{tier}", String.valueOf(tier))
                    .replace("{kills}", String.valueOf(kills))
                    .replace("{bonus}", Msg.fmt(bonusOf(tier) * 100, 0))
                    .replace("{next}", nextThreshold(tier) < 0 ? "—" : String.valueOf(nextThreshold(tier)));
            Msg.prefix(player, text);
            if (plugin.settings().sounds()) {
                player.playSound(player.getLocation(),
                        soundOf(plugin.settings().str("proficiency.tier-up-sound", "BLOCK_ANVIL_USE")),
                        0.9f, 1.15f + 0.05f * tier);
            }
        }
        int min = plugin.settings().intOf("proficiency.broadcast-min-tier", 0);
        if (min > 0 && tier >= min) {
            String who = player == null ? "有人" : player.getName();
            Bukkit.getServer().broadcast(Msg.mm(Msg.PREFIX
                    + "<#ffd166>" + who + "</#ffd166> <gray>的锻造品剑意已至 <white>"
                    + tierName(tier) + "</white> <dark_gray>(剑意 " + kills + ")</dark_gray>"));
        }
    }

    // ------------------------------------------------------------ 辅助

    /** 装备部位决定 modifier 的 equipment group；武器一律主手 */
    private EquipmentSlotGroup groupOf(ItemMeta meta) {
        String raw = meta.getPersistentDataContainer().get(Keys.ARMOR_SLOT, PersistentDataType.STRING);
        ForgeMaterial.Slot slot = ForgeMaterial.Slot.byName(raw);
        return slot == null ? EquipmentSlotGroup.MAINHAND : slot.group();
    }

    private void writeKills(ItemStack item, int kills) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(killsKey, PersistentDataType.INTEGER, Math.max(0, kills));
        com.forgemaster.util.Attrs.apply(item, meta);
    }

    private static String plainOf(net.kyori.adventure.text.Component c) {
        return Msg.plain(c).trim();
    }

    private static Sound soundOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return Sound.BLOCK_ANVIL_USE;
        }
        try {
            return Sound.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Sound.BLOCK_ANVIL_USE;
        }
    }

    private static int parseInt(String raw, int def) {
        if (raw == null) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private static double parseDouble(String raw, double def) {
        if (raw == null) {
            return def;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}
