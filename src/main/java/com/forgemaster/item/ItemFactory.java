package com.forgemaster.item;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.affix.Affix;
import com.forgemaster.config.Settings;
import com.forgemaster.forge.ForgeSession;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.quality.Quality;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 把一次锻造会话结算成实物物品。
 */
public final class ItemFactory {

    private final Plugin plugin;
    private final Settings settings;
    private final com.forgemaster.affix.AffixRegistry affixes;
    private final com.forgemaster.quench.QuenchRegistry quenches;
    private final Random random = new Random();

    public ItemFactory(Plugin plugin, Settings settings,
                       com.forgemaster.affix.AffixRegistry affixes,
                       com.forgemaster.quench.QuenchRegistry quenches) {
        this.plugin = plugin;
        this.settings = settings;
        this.affixes = affixes;
        this.quenches = quenches;
    }

    public ItemStack build(ForgeSession s) {
        ForgeMaterial mat = s.material();
        Quality q = s.quality();

        ItemStack item = new ItemStack(mat.item());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        boolean armor = mat.armor();
        String baseName;
        if (armor) {
            // 装备直接用胚子名（铁胸甲 / 星陨头盔），去色后重新上品质色
            baseName = Msg.plain(Msg.mm(mat.display()));
        } else {
            var names = settings.weaponNames();
            baseName = names.isEmpty() ? "无名剑" : names.get(random.nextInt(names.size()));
        }
        String titlePrefix = q.title().isEmpty() ? "" : q.title() + "·";
        String display = "<color:" + q.color() + "><bold>" + titlePrefix + baseName + "</bold></color>";
        meta.displayName(Msg.mm(display).decoration(TextDecoration.ITALIC, false));

        int durMax = durability(mat, q);
        int dur = durMax;

        List<Component> lore = new ArrayList<>();
        // 绚丽主题外框：粗框线 / 绶带 / 流彩星级 / 评分条按品质换色（LoreArt，纯视觉，不参与文本匹配）
        lore.add(line(LoreArt.topRule(q)));
        lore.add(line(LoreArt.ribbon(q)));
        lore.add(line(LoreArt.stars(q)));
        lore.add(line("<gray>品质 <dark_gray>▸</dark_gray> " + q.colored()
                + " <dark_gray>│</dark_gray> <white>" + Msg.fmt(s.finalScore(), 1)
                + "</white><dark_gray> 分</dark_gray>"));
        lore.add(line(LoreArt.scoreBar(q, s.finalScore())));
        lore.add(line("<gray>" + (armor ? "装备" : "剑胚") + " <dark_gray>▸</dark_gray> " + mat.display()
                + (s.grade() == null || s.grade() == com.forgemaster.material.MaterialGrade.COMMON
                ? "" : " <dark_gray>│</dark_gray> " + s.grade().colored())));
        // 剑型行（v2.14）：纯视觉展示；id 写 PDC 供剑型系统识别
        if (plugin instanceof com.forgemaster.ForgingMasterPlugin fm
                && fm.forms() != null && s.formId() != null) {
            var form = fm.forms().get(s.formId());
            if (form != null) {
                lore.add(line("<gray>剑型 <dark_gray>▸</dark_gray> <color:" + form.color() + ">"
                        + form.display() + "</color> <dark_gray>×"
                        + com.forgemaster.util.Msg.fmt(form.valueMult(), 2) + "</dark_gray>"));
            }
        }
        if (s.quench() != null) {
            lore.add(line("<gray>淬火 <dark_gray>▸</dark_gray> " + s.quench().colored()));
        }
        if (s.layers() > 0) {
            lore.add(line("<gray>钢纹 <dark_gray>▸</dark_gray> <white>" + s.pattern()
                    + "</white> <dark_gray>│</dark_gray> <gray>" + s.layers() + " 层折叠</gray>"));
        }
        lore.add(line(LoreArt.flavor(q)));
        lore.add(line(durabilityLine(dur, durMax)));
        int strengthenCap = strengthenCapFor(q, mat.tier());
        lore.add(line(com.forgemaster.strengthen.StrengthenService
                .strengthenLine(0, strengthenCap, 1.0)));
        lore.add(line(""));

        Map<Affix, Double> affixes = s.affixValues();
        if (affixes.isEmpty()) {
            // 前缀必须是「（」：GemService 重画镶嵌块时按这个前缀跳过占位行
            lore.add(line("<dark_gray>（无词条 · 火候和锤法还不够）</dark_gray>"));
        } else {
            // 纯文本必须<b>恰好</b>是「◆ 词条」：套装与重铸都靠这个常量定位整块
            lore.add(line("<gradient:#ffd166:#ff7a3d>◆ 词条</gradient>"));
            for (Map.Entry<Affix, Double> e : affixes.entrySet()) {
                lore.add(line(e.getKey().line(e.getValue())));
            }
        }

        lore.add(line(""));
        // 工序拆成两行小进度条，填充色跟品质主题走，比原来一长串数字直观
        lore.add(line("<dark_gray>❖</dark_gray> <gray>火候</gray> " + statBar(q, s.heatScore())
                + " <white>" + Msg.fmt(s.heatScore() * 100, 0) + "</white>"
                + "  <gray>锤打</gray> " + statBar(q, s.hammerScore())
                + " <white>" + Msg.fmt(s.hammerScore() * 100, 0) + "</white>"));
        lore.add(line("<dark_gray>❖</dark_gray> <gray>叠钢</gray> " + statBar(q, s.foldScore())
                + " <white>" + Msg.fmt(s.foldScore() * 100, 0) + "</white>"
                + "  <gray>开刃</gray> " + statBar(q, s.grindScore())
                + " <white>" + Msg.fmt(s.grindScore() * 100, 0) + "</white>"
                + "  <gray>完美</gray> <color:" + q.color() + ">" + s.perfects() + "</color> <gray>次</gray>"));
        // v2.19 收尾双工序：只在开着的时候出一行，关掉的道具 Lore 不多一行空格
        if (settings.polishingEnabled() || settings.inlayEnabled()) {
            StringBuilder row = new StringBuilder("<dark_gray>❖</dark_gray> ");
            if (settings.polishingEnabled()) {
                row.append("<gray>抛光</gray> ").append(statBar(q, s.polishScore()))
                        .append(" <white>").append(Msg.fmt(s.polishScore() * 100, 0)).append("</white>");
            }
            if (settings.inlayEnabled()) {
                if (settings.polishingEnabled()) {
                    row.append("  ");
                }
                row.append("<gray>银丝</gray> ").append(statBar(q, s.inlayScore()))
                        .append(" <white>").append(Msg.fmt(s.inlayScore() * 100, 0)).append("</white>");
            }
            lore.add(line(row.toString()));
        }
        // 纯文本必须以「锻造者」开头：GemService 靠这个前缀定位镶嵌块插入点
        lore.add(line("<gray>锻造者 <white>" + s.playerName() + "</white>"
                + (s.forgerTitle() == null || s.forgerTitle().isBlank()
                ? "" : " <gray>「<gradient:#ffd166:#ffa94d>" + s.forgerTitle()
                + "</gradient>」</gray>")));
        lore.add(line(LoreArt.bottomRule(q)));
        meta.lore(lore);

        if (!armor) {
            // 武器把原版属性藏掉，靠自绘 Lore；装备保留护甲值显示，玩家看得懂
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        }

        // 基础属性：材料 × 品质倍率。武器走主手，装备走对应部位槽。
        EquipmentSlotGroup group = armor ? mat.slot().group() : EquipmentSlotGroup.MAINHAND;
        if (armor) {
            // 装备：armor.tiers 是「整套总量」，按部位拆下来就是这件东西的<b>绝对值</b>，
            // 语义上本来就该顶掉原版条目（铁胸 COMMON = 6.0×0.8 = 4.8 点护甲）。
            // 所以这里不搬原版基数，靠 fm_armor_<部位> 单独撑起护甲值。
            double bonusArmor = roundStat(mat.bonusArmor() * q.power());
            if (bonusArmor != 0) {
                meta.addAttributeModifier(Attribute.ARMOR, new AttributeModifier(
                        new NamespacedKey(plugin, com.forgemaster.util.Attrs.armorKey(mat.slot())),
                        bonusArmor,
                        AttributeModifier.Operation.ADD_NUMBER,
                        group));
            }
            double tough = roundStat(mat.armorToughness() * q.power());
            if (tough != 0) {
                meta.addAttributeModifier(Attribute.ARMOR_TOUGHNESS, new AttributeModifier(
                        new NamespacedKey(plugin, com.forgemaster.util.Attrs.toughKey(mat.slot())),
                        tough,
                        AttributeModifier.Operation.ADD_NUMBER,
                        group));
            }
        } else {
            // 武器：bonus-attack / bonus-speed 是「加成」语义，必须叠在原版基线上。
            // 原版条目（铁剑 attack_damage +5、attack_speed -2.4）只在组件默认值里，
            // 不先搬进 meta 就会被下面的 addAttributeModifier 整体顶掉：
            // 剑芯 +1.12 会变成总伤（比木剑还低），攻速从 1.67 飙到 4.07。
            com.forgemaster.util.Attrs.keepVanilla(item, meta,
                    Attribute.ATTACK_DAMAGE, Attribute.ATTACK_SPEED);
            double attack = roundStat(mat.bonusAttack() * q.power());
            if (attack != 0) {
                meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(
                        new NamespacedKey(plugin, "fm_atk"),
                        attack,
                        AttributeModifier.Operation.ADD_NUMBER,
                        group));
            }
            double speed = roundStat(mat.bonusSpeed() * q.power());
            if (speed != 0) {
                meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(
                        new NamespacedKey(plugin, "fm_speed"),
                        speed,
                        AttributeModifier.Operation.ADD_NUMBER,
                        group));
            }
        }

        // ---- 收口：护甲上的武器向词条一律丢弃 ----
        //
        // 上面每个调用点都该自己选对池子，但「每个调用点都记得」不是可靠的设计——
        // buildDirect 就是这么漏了一整年。所以在真正写 PDC 的这一个点上再拦一道：
        // 谁再把攻击向词条塞给护甲，都不会落进物品。
        if (armor) {
            java.util.Iterator<java.util.Map.Entry<Affix, Double>> it = affixes.entrySet().iterator();
            while (it.hasNext()) {
                Affix a = it.next().getKey();
                if (!com.forgemaster.forge.ForgeManager.ARMOR_AFFIX_POOL.contains(a.id())) {
                    it.remove();
                    if (plugin != null) {
                        plugin.getLogger().warning("[锻造] 护甲 " + mat.id() + " 抽到了非防御词条 "
                                + a.id() + "，已丢弃（调用点选错了词条池）");
                    }
                }
            }
        }
        // 词条 → 属性 / PDC
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        double crit = 0, critDmg = 0, lifesteal = 0, damageReduce = 0, execute = 0;
        int fire = 0;
        int chill = 0;
        for (Map.Entry<Affix, Double> e : affixes.entrySet()) {
            Affix affix = e.getKey();
            double value = e.getValue();
            switch (affix.kind()) {
                case ATTRIBUTE -> {
                    if (affix.attribute() != null) {
                        // 移速 / 击退会被反作弊盯，先过一遍兼容收敛
                        double applied = value;
                        if (plugin instanceof com.forgemaster.ForgingMasterPlugin fm && fm.compat() != null) {
                            applied = fm.compat().cap(affix.attribute(), value);
                        }
                        // v2.23.1：气血（max_health）也必须有上限，否则单件能 +41 生命
                        applied = cappedAttribute(affix.attribute(), applied);
                        // 键必须带部位：护甲一次穿四件，若都用 fm_<词条id>，
                        // 两件都roll到「护甲值」就会共用一个键，穿上互相顶掉——
                        // 这正是自检「四件穿一起不会因为同一个键互相顶掉」盯的那个坑。
                        String keyName = armor
                                ? "fm_" + affix.id().toLowerCase() + "_" + mat.slot().name().toLowerCase()
                                : "fm_" + affix.id().toLowerCase();
                        meta.addAttributeModifier(affix.attribute(), new AttributeModifier(
                                new NamespacedKey(plugin, keyName),
                                applied,
                                AttributeModifier.Operation.ADD_NUMBER,
                                group));
                    }
                }
                case CRIT_CHANCE -> crit += value;
                case CRIT_DAMAGE -> critDmg += value;
                case LIFESTEAL -> lifesteal += value;
                case FIRE -> fire += (int) Math.round(value);
                // v2.24 切片一：冰噬与点燃同一口径（秒 → tick），处决是百分比
                case CHILL -> chill += (int) Math.round(value);
                case EXECUTE -> execute += value;
                case DAMAGE_REDUCTION -> damageReduce += value;
            }
        }

        pdc.set(Keys.FORGED, PersistentDataType.BYTE, (byte) 1);
        // v2.26.6：锻造品唯一身份。build 是全插件唯一写 FORGED 的生产点（正常锻造 /
        // give / givefull / buildDirect 全汇到这里），uid 跟着 FORGED 落笔一次、永不改写。
        // 有了它，两把 PDC 其余字段完全相同的剑 isSimilar 也会返回 false——
        // StrengthenService 碎剑收剑按 uid 定位，不再收错。
        pdc.set(Keys.ITEM_UID, PersistentDataType.STRING, java.util.UUID.randomUUID().toString());
        pdc.set(Keys.MATERIAL, PersistentDataType.STRING, mat.id());
        if (armor) {
            pdc.set(Keys.ARMOR_SLOT, PersistentDataType.STRING, mat.slot().name());
        }
        pdc.set(Keys.QUALITY, PersistentDataType.STRING, q.name());
        // v2.10：显式写 0，声明这件东西的附魔孔独立计算。
        // 老物品没有这个键，enchantSocketsOf() 会回退成宝石孔数，不会被砍孔。
        pdc.set(Keys.ENCHANT_SOCKETS, PersistentDataType.INTEGER, 0);
        pdc.set(Keys.SCORE, PersistentDataType.DOUBLE, round(s.finalScore()));
        pdc.set(Keys.FORGER, PersistentDataType.STRING, s.playerName());
        pdc.set(Keys.QUENCH, PersistentDataType.STRING, s.quench() == null ? "none" : s.quench().id());
        pdc.set(Keys.CRIT_CHANCE, PersistentDataType.DOUBLE, round(crit));
        pdc.set(Keys.CRIT_DAMAGE, PersistentDataType.DOUBLE, round(critDmg));
        // v2.23.1 修 P0：写入即封顶，让 Lore 上写的数与实战生效的数一致。
        // 以前这里存原始累加值（吸血能到 115%），而实战侧只有新加的夹子——
        // 玩家看到「+115% 吸血」却只回 2 点血，会当成 bug 报上来。
        pdc.set(Keys.LIFESTEAL, PersistentDataType.DOUBLE, round(cappedLifesteal(lifesteal)));
        pdc.set(Keys.FIRE_TICKS, PersistentDataType.INTEGER,
                caps().clampFireTicks(fire * 20));
        // v2.24 切片一：冰噬与处决「写入即封顶」——Lore 上写的数就是实战生效的数，
        // 不留「面板虚高、实战被砍」的老毛病（v2.23.1 的教训）
        pdc.set(Keys.CHILL_TICKS, PersistentDataType.INTEGER,
                caps().clampChillTicks(chill * 20));
        pdc.set(Keys.EXECUTE, PersistentDataType.DOUBLE, round(cappedExecute(execute)));
        pdc.set(Keys.DAMAGE_REDUCE, PersistentDataType.DOUBLE, round(cappedReduce(damageReduce)));
        pdc.set(Keys.AFFIX_LIST, PersistentDataType.STRING, affixIds(affixes));
        pdc.set(Keys.PATTERN, PersistentDataType.STRING, s.pattern());
        if (s.formId() != null) {
            pdc.set(Keys.FORM, PersistentDataType.STRING, s.formId());
        }
        pdc.set(Keys.LAYERS, PersistentDataType.INTEGER, s.layers());
        pdc.set(Keys.DURABILITY, PersistentDataType.INTEGER, dur);
        pdc.set(Keys.DURABILITY_MAX, PersistentDataType.INTEGER, durMax);
        pdc.set(Keys.REPAIR_COUNT, PersistentDataType.INTEGER, 0);
        pdc.set(Keys.USED_GRADE, PersistentDataType.STRING,
                (s.grade() == null ? com.forgemaster.material.MaterialGrade.COMMON : s.grade()).name());
        pdc.set(Keys.STRENGTHEN, PersistentDataType.INTEGER, 0);
        pdc.set(Keys.STRENGTHEN_CAP, PersistentDataType.INTEGER, strengthenCap);
        // 基础属性快照：强化只按这份放大，服主之后改 materials.* 不会追溯已铸成的剑
        if (armor) {
            pdc.set(Keys.BASE_ARMOR, PersistentDataType.DOUBLE, roundStat(mat.bonusArmor() * q.power()));
            pdc.set(Keys.BASE_TOUGH, PersistentDataType.DOUBLE, roundStat(mat.armorToughness() * q.power()));
        } else {
            pdc.set(Keys.BASE_ATK, PersistentDataType.DOUBLE, roundStat(mat.bonusAttack() * q.power()));
            pdc.set(Keys.BASE_SPEED, PersistentDataType.DOUBLE, roundStat(mat.bonusSpeed() * q.power()));
        }

        if (q.ordinal() >= Quality.LEGENDARY.ordinal()) {
            meta.setEnchantmentGlintOverride(Boolean.TRUE);
        }

        // 耐久只认插件自己的账（PDC 的 durability / durability_max，Lore 里那一条）。
        // 不封原版耐久的话，打怪挖矿照样掉本体耐久：两条账一起掉，
        // 原版条见底时整把剑直接碎，玩家看到的却是「Lore 上还有 80%」。
        meta.setUnbreakable(true);

        com.forgemaster.util.Attrs.apply(item, meta);
        applyAffixSet(plugin, item);
        return item;
    }

    // ------------------------------------------------------------ 数值封顶（v2.23.1）

    /**
     * 吸血写入前的封顶。上限走 {@link com.forgemaster.combat.DamageBudget.Caps#fromConfig}，
     * 与实战侧、存量收敛侧共用同一份默认值 —— 三处各写一遍 0.20 就是下一个分歧。
     */
    private double cappedLifesteal(double raw) {
        return caps().clampLifesteal(raw);
    }

    /** 点燃时长上限（秒）。叠 5 条燃烬能烧 40 秒，既超标也没意义 —— 目标早死了。 */
    private double cappedFireSeconds() {
        double cap = caps().maxFireSeconds();
        return cap <= 0 ? Double.MAX_VALUE : cap;
    }

    /** 单件装备的减伤上限 */
    private double cappedReduce(double raw) {
        return caps().clampReduce(raw);
    }

    /**
     * 处决增伤写入前的封顶（v2.24 切片一）。
     *
     * <p>上限走 {@link com.forgemaster.combat.DamageBudget.Caps#clampExecute}：
     * 处决是「残血增伤」，条数多 + 星陨档缩放能把原始值顶到远超设计值，
     * 与吸血同理必须写入即封顶。</p>
     */
    private double cappedExecute(double raw) {
        return caps().clampExecute(raw);
    }

    /**
     * 生命上限加成封顶。
     *
     * <p>这条是本次审计新发现的超标项：{@code vitality}（气血）词条走通用
     * {@code ATTRIBUTE} 分支，<b>没有任何上限</b>。一条 2~5 点、星陨档 ×1.65、
     * 五条词条 → 单件 +41 生命；四件套叠起来的理论上限足够把 20 血压到 100 以上，
     * 生存服上就是「站着让你打」。</p>
     */
    private double cappedHealth(double raw) {
        return caps().clampHealth(raw);
    }

    /** 写入侧上限集合（每次锻造读一次配置；构建物品不是热路径） */
    private com.forgemaster.combat.DamageBudget.Caps caps() {
        return com.forgemaster.combat.DamageBudget.Caps.fromConfig(plugin.getConfig());
    }
    /** 属性级封顶分派：气血走专用上限，其余原样放行（移速/击退已由 compat 收敛） */
    private double cappedAttribute(org.bukkit.attribute.Attribute attribute, double value) {
        if (attribute == org.bukkit.attribute.Attribute.MAX_HEALTH) {
            return cappedHealth(value);
        }
        return value;
    }

    /**
     * 词条套装的收尾：把 {@code fm_set_*} 修饰符与「套装: xxx」Lore 行补进成品。
     *
     * <p>必须在 {@code Attrs.apply(item, meta)} <b>之后</b>调——套装要读 PDC 里的
     * {@code affix_list} 和 {@code armor_slot}，那两样是随 meta 一起写进去的。</p>
     *
     * <p>词条一旦被重铸改变，也走同一个入口重刷（见 {@code Reforge}）。</p>
     */
    public static void applyAffixSet(Plugin owner, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        if (!(owner instanceof ForgingMasterPlugin fm) || fm.affixSets() == null) {
            return;
        }
        try {
            fm.affixSets().apply(item);
        } catch (RuntimeException ex) {
            // 套装只是锦上添花，失败不能让整把剑造不出来
            fm.getLogger().warning("套装写入失败: " + ex.getMessage());
        }
    }

    /** 管理命令 /give 用：直接按分数造一把，词条照样抽 */
    public ItemStack buildDirect(String forgerName, ForgeMaterial mat, Quality q, double score) {
        return buildDirect(forgerName, mat, q, score, null);
    }

    /** 锻造品唯一身份（无 uid 返回 null——存量老物在 strike 入口惰性补发） */
    public String uidOf(org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        var meta = item.getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(Keys.ITEM_UID, org.bukkit.persistence.PersistentDataType.STRING)) {
            return null;
        }
        return meta.getPersistentDataContainer().get(Keys.ITEM_UID, org.bukkit.persistence.PersistentDataType.STRING);
    }

    /**
     * 存量老物惰性补 uid：FORGED 已有而 uid 缺失时补写。只补缺失，**绝不覆盖已有值**
     * （与 seedSnapshot 的「系统/服主已写过的值不能盖」同一铁律）。返回是否动了 meta。
     * 只许主线程调（PDC/meta 约定）。
     */
    public boolean ensureUid(org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getType().isAir() || !isForged(item)) {
            return false;
        }
        var meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        var pdc = meta.getPersistentDataContainer();
        if (pdc.has(Keys.ITEM_UID, org.bukkit.persistence.PersistentDataType.STRING)) {
            return false;
        }
        pdc.set(Keys.ITEM_UID, org.bukkit.persistence.PersistentDataType.STRING,
                java.util.UUID.randomUUID().toString());
        item.setItemMeta(meta);
        return true;
    }

    public boolean isForged(org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        var meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte mark = meta.getPersistentDataContainer().get(Keys.FORGED, PersistentDataType.BYTE);
        return mark != null && mark == 1;
    }

    /** 从成品读出剑胚 id（非锻造品返回 null） */
    public String materialIdOf(org.bukkit.inventory.ItemStack item) {
        if (!isForged(item)) {
            return null;
        }
        var meta = item.getItemMeta();
        return meta == null ? null
                : meta.getPersistentDataContainer().get(Keys.MATERIAL, PersistentDataType.STRING);
    }

    public List<Component> loreOf(org.bukkit.inventory.ItemStack item) {
        var meta = item.getItemMeta();
        return meta == null || meta.lore() == null ? List.of() : meta.lore();
    }

    private static String affixIds(Map<Affix, Double> map) {
        StringBuilder sb = new StringBuilder();
        for (Affix a : map.keySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(a.id().toLowerCase());
        }
        return sb.toString();
    }

    /**
     * 成品耐久 = 胚子的基础耐久 × 品质倍数。
     *
     * <p>劣质半把、普通一把、精良 1.5、史诗 2.2、传说 3、神话 5。</p>
     */
    public static int durability(ForgeMaterial mat, Quality q) {
        return Math.max(1, (int) Math.round(mat.baseDurability() * q.durabilityMult()));
    }

    /**
     * 该档位成品的锻造等级上限（老曲线，只在铁砧强化关闭时用）。
     *
     * <p>读 config 的 {@code strengthen.caps.<tier>}，缺省按内置阶梯 20/22/25/28/32。</p>
     */
    public int strengthenCapOf(int tier) {
        int idx = Math.max(1, Math.min(5, tier));
        return Math.max(0, settings.intOf("strengthen.caps." + idx,
                com.forgemaster.strengthen.StrengthenService.defaultCap(idx)));
    }

    /**
     * 铸成时写进物品的强化上限（v2.17）。
     *
     * <p>铁砧强化开着时按<b>品质</b>取（粗糙 40 → 神话 99），关着才回落到老的
     * 材料档位曲线。以前这里无条件读材料档位，于是新打的神话剑一出生就是
     * 「+0/32」，和 {@code StrengthenService.capOf} 报的 99 直接对不上。</p>
     */
    public int strengthenCapFor(Quality q, int tier) {
        if (!settings.flag("anvil-forge.enabled", true)) {
            return strengthenCapOf(tier);
        }
        Quality quality = q == null ? Quality.COMMON : q;
        int def = com.forgemaster.strengthen.StrengthenService.defaultQualityCap(quality);
        return Math.max(1, Math.min(999, settings.intOf("anvil-forge.caps." + quality.name(), def)));
    }

    /** 兼容旧调用：没有胚子时按普通档兜底 */
    public static int maxDurability(Quality q) {
        return Math.max(1, (int) Math.round(ForgeMaterial.defaultBaseDurability(3) * q.durabilityMult()));
    }

    private static String durabilityLine(int cur, int max) {
        double ratio = max <= 0 ? 0 : (double) cur / max;
        String color = ratio > 0.6 ? "#4ee36b" : ratio > 0.25 ? "#ffd166" : "#ff5252";
        // 纯文本必须以「耐久」开头：三处代码靠这个前缀找回这一行重写数值
        return "<gray>耐久 <dark_gray>▸</dark_gray> <" + color + ">" + cur + "</" + color + ">"
                + "<dark_gray>/" + max + "</dark_gray> " + Msg.barBlocks(ratio, 12, color, "#3a3a3a");
    }

    /** 工序小进度条：8 段 ▰▱，填充色用品质主题色 */
    private static String statBar(Quality q, double ratio) {
        return Msg.barBlocks(ratio, 8, q.color(), LoreArt.BAR_EMPTY);
    }

    /**
     * 让锻造品只吃插件自己的虚拟耐久，不吃原版耐久。
     *
     * <p>2.10.3 之前的物品只写了 PDC 耐久，没封原版耐久：攻击 / 挖掘会同时消耗
     * 原版 {@code Damage} 组件，条见底整把剑直接消失，Lore 上却还显示得好好的。
     * 这里补两件事——{@code unbreakable} 置真、把历史累积的 {@code Damage} 清零。</p>
     *
     * @return 是否真的改了东西（调用方据此决定要不要写回槽位）
     */
    public static boolean enforceVirtualDurability(org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer()
                .has(Keys.FORGED, PersistentDataType.BYTE)) {
            return false;
        }
        boolean changed = false;
        if (!meta.isUnbreakable()) {
            meta.setUnbreakable(true);
            // 不能直接 setItemMeta：那会把 attribute_modifiers 组件整体换掉，
            // 刚补回来的原版基数又被洗一遍。统一走 apply（内部带 normalize）。
            com.forgemaster.util.Attrs.apply(item, meta);
            changed = true;
        }
        Integer damage = item.getData(io.papermc.paper.datacomponent.DataComponentTypes.DAMAGE);
        if (damage != null && damage > 0) {
            item.setData(io.papermc.paper.datacomponent.DataComponentTypes.DAMAGE, 0);
            changed = true;
        }
        return changed;
    }

    /** 读当前耐久；非锻造品返回 -1 */
    public int durabilityOf(org.bukkit.inventory.ItemStack item) {
        if (!isForged(item)) {
            return -1;
        }
        var meta = item.getItemMeta();
        Integer v = meta.getPersistentDataContainer().get(Keys.DURABILITY, PersistentDataType.INTEGER);
        return v == null ? -1 : v;
    }

    public int maxDurabilityOf(org.bukkit.inventory.ItemStack item) {
        if (!isForged(item)) {
            return -1;
        }
        var meta = item.getItemMeta();
        Integer v = meta.getPersistentDataContainer().get(Keys.DURABILITY_MAX, PersistentDataType.INTEGER);
        return v == null ? -1 : v;
    }

    /** 写回耐久并同步 Lore 那一行；返回是否已经崩刃 */
    public boolean setDurability(org.bukkit.inventory.ItemStack item, int value) {
        ItemMeta meta = item == null || item.getType().isAir() ? null : item.getItemMeta();
        return meta != null && setDurability(item, meta, value);
    }

    /**
     * 写回耐久（复用调用方已持有的 meta，命中热路径上省一次 getItemMeta）。
     *
     * <p>耐康新值总是先落 PDC；Lore 里的耐久行只在「显示值跨档」时才重写——
     * 档宽由 {@code settings.durability-lore-step} 控制（百分比，默认 5，0 = 每刀都刷新），
     * 或崩刃提示行的有无翻转。档内一击走快路，省掉整条 Lore 的两轮纯文本序列化与重挂；
     * 代价是档内显示的耐久数字停留在上次跨档时的值（PDC 才是结算用的真相）。</p>
     *
     * <p><b>v2.23.5 修「内置耐久看起来不掉」</b>：档宽原来是纯百分比（5%），
     * 三千多点的剑一刀掉 1 点，要砍 <b>195 刀</b> Lore 上的数字才跳一次 ——
     * 玩家砍完一只怪，看到的还是满耐久，自然当成「加了无法破坏」。
     * 现在档宽再夹一层绝对值上限 {@code settings.durability-lore-max-points}
     * （默认 5 点）：几千点的剑每 5 刀跳一次数字，小件仍按 5% 走。</p>
     *
     * <p>v2.23 起崩刃是<b>真·属性失效</b>：耐久从正值落到 0 的那一刻，摘掉全部基础属性
     * 修饰符（fm_atk / fm_speed / fm_armor / fm_toughness 系）——之前只停词条与套装结算，
     * 攻击 / 护甲 modifiers 还挂着，文案说「属性失效」实际只失效了一半。修复回耐久时由
     * {@code StrengthenService.apply} 按当前等级重建。词条的属性修饰符（如护甲值词条）
     * 不在此列，仍按「结算停用」的口径处理。</p>
     *
     * @return 是否已经崩刃（耐久归零），调用方据此走「打断装备」分支
     */
    public boolean setDurability(org.bukkit.inventory.ItemStack item, ItemMeta meta, int value) {
        if (item == null || meta == null) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (!pdc.has(Keys.FORGED, PersistentDataType.BYTE)) {
            return false;
        }
        Integer maxBoxed = pdc.get(Keys.DURABILITY_MAX, PersistentDataType.INTEGER);
        int max = maxBoxed == null ? 100 : maxBoxed;
        int cur = Math.max(0, Math.min(max, value));
        Integer oldBoxed = pdc.get(Keys.DURABILITY, PersistentDataType.INTEGER);
        int old = oldBoxed == null ? -1 : oldBoxed;

        // PDC 是结算真相，每次都写
        pdc.set(Keys.DURABILITY, PersistentDataType.INTEGER, cur);

        // 崩刃瞬间摘基础属性修饰符（真·属性失效）；只在「正值 → 0」的转变时做一次
        if (old > 0 && cur <= 0) {
            stripBaseModifiers(meta);
        }

        int step = settings.durabilityLoreStep();
        boolean bandChanged = step <= 0 ? old != cur
                : durabilityBand(old, max, step) != durabilityBand(cur, max, step);
        List<Component> lore = meta.lore();
        boolean rewrite = lore == null || old < 0
                || bandChanged
                || (old <= 0) != (cur <= 0);
        if (!rewrite) {
            // 档内一击：Lore 原样保留，只把带新 PDC 的 meta 装回去
            com.forgemaster.util.Attrs.apply(item, meta);
            restoreIfRepaired(item, old, cur);
            return cur <= 0;
        }

        if (lore == null) {
            lore = new ArrayList<>();
        } else {
            lore = new ArrayList<>(lore);
        }
        String fresh = durabilityLine(cur, max);
        // 两轮 Msg.plain 遍历合并为一轮：同时定位「耐久」行与旧的「崩刃」提示行。
        // 崩刃行每次写入至多产生一条，按首条定位与原来的 removeIf 等价
        int durIdx = -1;
        int brokenIdx = -1;
        for (int i = 0; i < lore.size(); i++) {
            String plain = Msg.plain(lore.get(i));
            if (durIdx < 0 && plain.startsWith("耐久")) {
                durIdx = i;
            } else if (brokenIdx < 0 && plain.startsWith("崩刃")) {
                brokenIdx = i;
            }
        }
        if (durIdx >= 0) {
            lore.set(durIdx, line(fresh));
        } else {
            lore.add(line(fresh));
        }
        // 旧的「崩刃」提示要先清掉，否则修好了还挂着红字
        if (brokenIdx >= 0) {
            lore.remove(brokenIdx);
        }
        if (cur <= 0) {
            lore.add(line("<red>崩刃：属性失效，需要重新研磨修复</red>"));
        }
        meta.lore(lore);
        com.forgemaster.util.Attrs.apply(item, meta);
        restoreIfRepaired(item, old, cur);
        return cur <= 0;
    }

    /**
     * 崩刃：摘掉基础属性修饰符（fm_atk / fm_speed / fm_armor / fm_toughness 及带部位的
     * fm_armor_&lt;部位&gt; / fm_toughness_&lt;部位&gt;）。键名<b>精确匹配</b>——词条键
     * （如 fm_armor_plate_chestplate）带额外后缀，不会被误摘。
     */
    private static void stripBaseModifiers(ItemMeta meta) {
        for (org.bukkit.attribute.Attribute attr : new org.bukkit.attribute.Attribute[]{
                org.bukkit.attribute.Attribute.ATTACK_DAMAGE,
                org.bukkit.attribute.Attribute.ATTACK_SPEED,
                org.bukkit.attribute.Attribute.ARMOR,
                org.bukkit.attribute.Attribute.ARMOR_TOUGHNESS}) {
            var mods = meta.getAttributeModifiers(attr);
            if (mods == null) {
                continue;
            }
            for (var m : new ArrayList<>(mods)) {
                var k = m.getKey();
                if (k != null && com.forgemaster.util.Attrs.isBaseKey(k.getKey())) {
                    meta.removeAttributeModifier(attr, m);
                }
            }
        }
    }

    /** 修复回耐久（0 → 正值）时按当前强化等级重建基础属性修饰符 */
    private void restoreIfRepaired(org.bukkit.inventory.ItemStack item, int old, int cur) {
        if (old != 0 || cur <= 0) {
            return;
        }
        if (plugin instanceof ForgingMasterPlugin fm && fm.strengthen() != null) {
            fm.strengthen().apply(item);
        }
    }

    /**
     * 耐久显示档宽（点数）：{@code step% × 上限}，但绝对值不超过
     * {@code settings.durability-lore-max-points}（默认 5 点）。
     *
     * <p>为什么要有绝对上限：纯百分比在大耐久品上等于「永不刷新」——
     * 3900 点的剑 5% 是 195 点，砍 195 刀数字才动一次。夹一层 5 点之后
     * 每 5 刀就能看到数字减少，而 Lore 重写的频率仍然只有 1/5，
     * 不会给每个命中都挂一次整条 Lore 的序列化。</p>
     *
     * @param max 该物品的最大耐久（PDC durability_max）
     * @return 档宽，至少 1
     */
    public int durabilityBandWidth(int max) {
        if (max <= 0) {
            return 1;
        }
        int step = settings.durabilityLoreStep();
        if (step <= 0) {
            return 1; // 实时模式：档宽 1 点 = 每掉 1 点就重写
        }
        int cap = Math.max(1, settings.intOf("settings.durability-lore-max-points", 5));
        int byPercent = Math.max(1, max * step / 100);
        return Math.min(byPercent, cap);
    }

    /**
     * 耐久显示档位：把当前耐久按 {@link #durabilityBandWidth} 压成档号
     * （step ≤ 0 时档号即数值）；档号没变就不值得重写 Lore。
     */
    private int durabilityBand(int cur, int max, int step) {
        if (max <= 0) {
            return 0;
        }
        if (step <= 0) {
            return cur; // 实时模式：数值一变档号就变，Lore 每刀刷新
        }
        return cur / durabilityBandWidth(max);
    }

    /** @param quenchId 指定淬火介质 id，null = 随机一种 */
    public ItemStack buildDirect(String forgerName, ForgeMaterial mat, Quality q, double score, String quenchId) {
        ForgeSession fake = new ForgeSession(java.util.UUID.nameUUIDFromBytes(forgerName.getBytes()), forgerName, mat);
        fake.quality(q);
        fake.finalScore(score);
        fake.heatScore(1.0);
        fake.addStrike(ForgeSession.Hit.PERFECT);

        // 让 /zhujian give 出来的成品看起来是"真锻出来的"：补齐叠钢与开刃
        int layers = 3 + random.nextInt(5);
        fake.foldCursor(layers);
        fake.foldScore(0.75 + random.nextDouble() * 0.25);
        fake.grindScore(0.7 + random.nextDouble() * 0.3);
        fake.grindTicksTotal(120);
        fake.grindTicksIn((int) Math.round(fake.grindScore() * 120));

        var all = quenches.all();
        if (!all.isEmpty()) {
            var quench = all.get(0);
            if (quenchId != null) {
                var chosen = quenches.get(quenchId);
                if (chosen != null) {
                    quench = chosen;
                }
            } else {
                quench = all.get(random.nextInt(all.size()));
            }
            fake.quench(quench);
            int count = Math.min(6, q.affixCount());
            double valueScale = 1.0;
            double spreadScale = 1.0;
            if (plugin instanceof com.forgemaster.ForgingMasterPlugin fm) {
                valueScale = com.forgemaster.affix.AffixScaling.valueScale(fm, mat.tier());
                spreadScale = com.forgemaster.affix.AffixScaling.spreadScale(fm, mat.tier());
            }
            // 护甲只吃防御词条——这里以前无条件用淬火池（全是攻击向），
            // 于是 /zhujian give armor-iron-chestplate 会发一件带「暴击率」的胸甲。
            // 而且暴击率在胸甲上是**死属性**：CombatListener 只读主手 PDC。
            List<Affix> chosen = mat.armor()
                    ? affixes.pickStrict(com.forgemaster.forge.ForgeManager.ARMOR_AFFIX_POOL, count)
                    : affixes.pick(quench.pool(), count,
                            com.forgemaster.forge.ForgeManager.WEAPON_AFFIX_POOL);
            for (Affix a : chosen) {
                fake.affixValues().put(a, a.roll(random, valueScale, spreadScale));
            }
        }
        return build(fake);
    }

    private static Component line(String mini) {
        return Msg.mm(mini).decoration(TextDecoration.ITALIC, false);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * 基础属性**数值**的精度（v2.23.4）：4 位小数。
     *
     * <p>必须与 {@code StrengthenService#roundStat} 一致。创建时写 2 位、强化时按 4 位重算，
     * 会让第一次 {@code apply()} 把 1.71 校正成 1.708 —— 玩家看到的是「我什么都没做，属性变了」，
     * 自检看到的则是「反复 apply 不幂等」。两边同精度，反复 apply 才真正一动不动。</p>
     *
     * <p>显示仍然走 {@link #round(double)}（2 位）：面板不需要给玩家看 1.1267。</p>
     */
    private static double roundStat(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}