package com.forgemaster.util;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * 物品 PDC 键。所有锻造品靠这里的键做运行时判定，防止被复制 / 伪造。
 */
public final class Keys {

    public static NamespacedKey FORGED;
    public static NamespacedKey MATERIAL;
    public static NamespacedKey QUALITY;
    public static NamespacedKey SCORE;
    public static NamespacedKey FORGER;
    public static NamespacedKey QUENCH;
    public static NamespacedKey CRIT_CHANCE;
    public static NamespacedKey CRIT_DAMAGE;
    public static NamespacedKey LIFESTEAL;
    public static NamespacedKey FIRE_TICKS;
    public static NamespacedKey TITLE;

    // ---- v1.2 扩展 ----
    public static NamespacedKey AFFIX_LIST;
    public static NamespacedKey PATTERN;
    public static NamespacedKey LAYERS;
    public static NamespacedKey DURABILITY;
    public static NamespacedKey DURABILITY_MAX;
    public static NamespacedKey REPAIR_COUNT;

    // ---- v1.3 ----
    /** 材料分级标记（打在矿石上） */
    public static NamespacedKey GRADE;
    /** 成品用的材料等级 */
    public static NamespacedKey USED_GRADE;

    // ---- v2.0 ----
    /** 剑灵：灵性值 */
    public static NamespacedKey SPIRIT;
    /** 剑灵：灵性上限 */
    public static NamespacedKey SPIRIT_MAX;
    /** 剑灵：名字 */
    public static NamespacedKey SPIRIT_NAME;
    // ---- v2.26.6 锻造品唯一身份 ----
    /** 锻造品唯一身份（UUID 字符串，物品诞生时写一次，之后永不改写——只允许 ItemFactory 与 StrengthenService 出现） */
    public static NamespacedKey ITEM_UID;

    /** 赛季编号 */
    public static NamespacedKey SEASON;

    // ---- v2.1 ----
    /** 装备部位（HELMET / CHESTPLATE / LEGGINGS / BOOTS），武器没有这个键 */
    public static NamespacedKey ARMOR_SLOT;
    /** 装备：受到伤害减免（0-1 的小数） */
    public static NamespacedKey DAMAGE_REDUCE;

    // ---- v2.24 切片一：词条新机制 ----
    /** 冰噬：命中附加减速的时长（tick，已过 max-chill-ticks 夹子） */
    public static NamespacedKey CHILL_TICKS;
    /** 处决：目标血量低于阈值时的增伤比例（0-1 小数，已过 max-execute 夹子） */
    public static NamespacedKey EXECUTE;

    // ---- v2.25 切片二：灵契重抽 ----
    /**
     * 灵契重抽的日期戳（打在<b>物品</b>上，值 = {@code yyyy-MM-dd|当日已重抽次数}）。
     *
     * <p>跟剑走而不是跟玩家走：换号 / 借号都不重置每日次数，剑与剑之间互不影响。</p>
     */
    public static NamespacedKey SPIRIT_REROLL_DATE;
    /** v2.25.1：灵契重抽的预览-确认绑定（预览写 nonce，confirm 校验同一把剑） */
    public static NamespacedKey SPIRIT_REROLL_NONCE;

    // ---- v2.2 宝石 ----
    /** 宝石物品标记 */
    public static NamespacedKey GEM;
    /** 宝石类型 id */
    public static NamespacedKey GEM_TYPE;
    /** 宝石数值 */
    public static NamespacedKey GEM_VALUE;
    /** 宝石品质 */
    public static NamespacedKey GEM_GRADE;
    /** 装备的宝石孔位数 */
    public static NamespacedKey SOCKETS;
    /**
     * 附魔孔位数（v2.10 起与宝石孔分开）。
     *
     * <p>这个键缺失时按「老物品」处理：回退成宝石孔数，保证 2.10 之前的东西
     * 不会被砍掉一半孔位。2.10 之后新锻出来的物品在创建时就写 0，
     * 所以「有键且为 0」= 新物品、附魔孔还没开。</p>
     */
    public static NamespacedKey ENCHANT_SOCKETS;
    /** 已镶嵌的宝石（type:value:grade|...） */
    public static NamespacedKey GEMS;
    /**
     * 已镶嵌的附魔（id:level|...）。
     *
     * <p>v2.10 起附魔走独立的「附魔孔」，不再和宝石抢同一组孔位。</p>
     */
    public static NamespacedKey ENCHANTS;

    // ---- v2.4 锻造等级 ----
    /** 锻造等级（强化等级），0 = 未强化 */
    public static NamespacedKey STRENGTHEN;
    /** 锻造等级上限（按材料档位固化到物品上，配置改了也不影响老物品） */
    public static NamespacedKey STRENGTHEN_CAP;
    /**
     * 生成当时的基础属性快照。
     *
     * <p>强化只按这份快照放大，不读当前配置——否则服主一改
     * {@code materials.*.bonus-attack}，老武器一强化就会被新值覆盖掉。</p>
     */
    public static NamespacedKey BASE_ATK;
    public static NamespacedKey BASE_SPEED;
    public static NamespacedKey BASE_ARMOR;
    public static NamespacedKey BASE_TOUGH;
    /** 护胚符标记（1 = 是护胚符） */
    public static NamespacedKey TALISMAN;

    /**
     * 护甲校准版本号。
     *
     * <p>2.10 把装备的基础护甲从「单件 0.35」改成「整套对齐原版」，老装备身上
     * 冻结的还是旧值。这个键记「这件东西按哪一版校准过」，穿上时发现版本偏旧
     * 就按当前配置重算一次，之后打上标记不再动。</p>
     */
    public static NamespacedKey ARMOR_REBALANCE;

    // ---- v2.5 强化补完 ----
    /** 炉火值：每失败一次 +1，攒满当前等级所需点数则下一级必成 */
    public static NamespacedKey FURNACE;
    /** 强化履历：累计失败次数 */
    public static NamespacedKey STRENGTHEN_FAILS;
    /** 强化履历：累计碎剑次数 */
    public static NamespacedKey STRENGTHEN_BROKEN;
    /** 强化履历：累计投入材料数 */
    public static NamespacedKey STRENGTHEN_INVEST;
    /** 稳胚符标记（1 = 是稳胚符，失败不掉级） */
    public static NamespacedKey CHARM_STEADY;
    /** 旺火符标记（1 = 是旺火符，本次成功率 +N 个百分点） */
    public static NamespacedKey CHARM_KINDLE;

    // ---- v2.8 词条符 ----
    /** 词条符：词条 id */
    public static NamespacedKey IMPRINT_AFFIX;
    /** 词条符：剥离时那一份的数值（移植时原样写回） */
    public static NamespacedKey IMPRINT_VALUE;
    /** 词条符：1 = 装备词条（只能进护甲） */
    public static NamespacedKey IMPRINT_ARMOR;

    /**
     * 宝石的 PDC 类效果（暴击 / 暴伤 / 吸血 / 点燃）已经写进物品的标记。
     *
     * <p>2.10.6 之前这些宝石只在 Lore 里有一行，PDC 汇总从来没被写过；
     * 有这个标记才能区分「已经算过」和「老物品还没算过」，避免升级后重复叠加。</p>
     */
    public static NamespacedKey GEM_EFFECTS;

    // ---- v2.12 锻阶（蜕变）----
    /** 锻阶（进化阶段），0 = 未蜕变；蜕变成功后 = 已完成的最高阶段 */
    public static NamespacedKey EVOLUTION;
    /** 蜕变失败次数（履历展示用） */
    public static NamespacedKey EVOLUTION_FAILS;

    // ---- v2.13 连击 / 狂热 / 护体（ForgeTax 五态抽票）----
    /** 强化连击：连续成功（含大成）的次数，损伤/空锤/碎裂清零 */
    public static NamespacedKey STRENGTHEN_COMBO;
    /** 炉魂狂热进度：每次锤击 +1，攒满换狂热次数 */
    public static NamespacedKey FRENZY_PROGRESS;
    /** 炉魂狂热剩余次数：狂热期间不碎 + 成功率加成 */
    public static NamespacedKey FRENZY_LEFT;
    /** 基石护体：连击攒出的「首次碎裂转损伤」是否已用掉（0/1） */
    public static NamespacedKey CORNERSTONE_USED;
    /** 玩家 PDC：剑魂传承——碎剑等级按比例记在玩家身上，下一把新铸的剑直接自带 */
    public static NamespacedKey INHERIT_LEVEL;

    // ---- v2.14 龙税周目 ----
    /** 剑型 id（ForgeTax sword forms 的移植，估值乘区用） */
    public static NamespacedKey FORM;

    // ---- v2.15 铁砧强化（ForgeTax 式重写）----
    /** 重锤标记（1 = 是重锤，左键敲铁砧强化用的道具） */
    public static NamespacedKey HAMMER;
    /** 砧位全息实体标记（值 = world;x;y;z，用来认出自己生成的全息并回收） */
    public static NamespacedKey ANVIL_SESSION;

    // ---- v2.18 剑尘（dissolve，分解回收）----
    /** 剑尘标记（1 = 是剑尘，分解锻造品的产出，强化材料不够时按 1:1 抵扣） */
    public static NamespacedKey DUST;

    // ---- 拓展第一档：名人殿堂 ----
    /**
     * 殿堂展示实体标记（值 = sword:排名 / label:排名）。
     *
     * <p>殿堂的 ItemDisplay / TextDisplay 专用，与砧位的 {@link #ANVIL_SESSION}
     * 完全独立——两个模块的清理逻辑互不认得对方实体，绝不共享 PDC 键。</p>
     */
    public static NamespacedKey HALL_DISPLAY;


    /**
     * 剑体放大实体标记（值 = 主人 UUID）。
     *
     * <p>和其他展示实体的键分开是有原因的：剑体放大是握在手里的巨型模型，
     * 生命周期、位置算法、清理时机都自成一套。共用一个键会导致
     * 「撤一个模块把另一个模块的实体一起撤了」这种事故。模块隔离是审计铁律。</p>
     */
    public static NamespacedKey BIG_SWORD_DISPLAY;

    /**
     * 玩家体型缩放修饰符的键（挂在玩家的 {@code Attribute.SCALE} 上）。
     *
     * <p>用独立 NamespacedKey 而不是无键 modifier：别的插件也可能动 SCALE，
     * 有键才能只摘自己那一条，不会把别人的加成一起清掉。</p>
     */
    public static NamespacedKey PLAYER_SCALE;

    // ---- 拓展第二档：套装效果 2.0（relicset）----
    /**
     * 套装主动技冷却（挂在<b>玩家</b> PDC，值 = {@code <套装id>:<就绪时间戳毫秒>}）。
     *
     * <p>与物品侧词条套装的 {@code fm_set_*} 属性键只共用了前缀风格：
     * 一个记在玩家身上、一个记在物品上，互不干扰。</p>
     */
    public static NamespacedKey RELIC_SET_CD;

    /**
     * 原创附魔（v2.23.2）：{@code id:level|id:level}。
     *
     * <p>和 {@link #ENCHANTS}（已镶进孔的原版附魔）分开存是刻意的：
     * 原版附魔的 id 一定能在 {@code Registry.ENCHANTMENT} 里查到，
     * 原创 id 查不到，混在一张表里会让 {@code EnchantEntry.decode} 把原创条目当脏数据丢掉。</p>
     */
    public static NamespacedKey CUSTOM_ENCH;

    /** 原创附魔书标记：这本书是附魔台产出的（防盗版：改 Lore 造不出这个 byte） */
    public static NamespacedKey ENCH_BOOK;

    /** 附魔台会话标识 */
    public static NamespacedKey ENCH_SESSION;

    private Keys() {
    }

    public static void init(Plugin plugin) {
        FORGED = new NamespacedKey(plugin, "forged");
        MATERIAL = new NamespacedKey(plugin, "material");
        QUALITY = new NamespacedKey(plugin, "quality");
        SCORE = new NamespacedKey(plugin, "score");
        FORGER = new NamespacedKey(plugin, "forger");
        QUENCH = new NamespacedKey(plugin, "quench");
        CRIT_CHANCE = new NamespacedKey(plugin, "crit_chance");
        CRIT_DAMAGE = new NamespacedKey(plugin, "crit_damage");
        LIFESTEAL = new NamespacedKey(plugin, "lifesteal");
        FIRE_TICKS = new NamespacedKey(plugin, "fire_ticks");
        TITLE = new NamespacedKey(plugin, "title");
        AFFIX_LIST = new NamespacedKey(plugin, "affix_list");
        PATTERN = new NamespacedKey(plugin, "pattern");
        LAYERS = new NamespacedKey(plugin, "layers");
        DURABILITY = new NamespacedKey(plugin, "durability");
        DURABILITY_MAX = new NamespacedKey(plugin, "durability_max");
        REPAIR_COUNT = new NamespacedKey(plugin, "repair_count");
        SPIRIT_REROLL_NONCE = new NamespacedKey(plugin, "spirit_reroll_nonce");
        GRADE = new NamespacedKey(plugin, "grade");
        USED_GRADE = new NamespacedKey(plugin, "used_grade");
        SPIRIT = new NamespacedKey(plugin, "spirit");
        SPIRIT_MAX = new NamespacedKey(plugin, "spirit_max");
        SPIRIT_NAME = new NamespacedKey(plugin, "spirit_name");
        SEASON = new NamespacedKey(plugin, "season");
        ITEM_UID = new NamespacedKey(plugin, "item_uid");
        ARMOR_SLOT = new NamespacedKey(plugin, "armor_slot");
        DAMAGE_REDUCE = new NamespacedKey(plugin, "damage_reduce");
        CHILL_TICKS = new NamespacedKey(plugin, "chill_ticks");
        EXECUTE = new NamespacedKey(plugin, "execute");
        // ---- v2.25 切片二：灵契重抽 ----
        SPIRIT_REROLL_DATE = new NamespacedKey(plugin, "spirit_reroll_date");

        GEM = new NamespacedKey(plugin, "gem");
        GEM_TYPE = new NamespacedKey(plugin, "gem_type");
        GEM_VALUE = new NamespacedKey(plugin, "gem_value");
        GEM_GRADE = new NamespacedKey(plugin, "gem_grade");
        SOCKETS = new NamespacedKey(plugin, "sockets");
        ENCHANT_SOCKETS = new NamespacedKey(plugin, "enchant_sockets");
        GEMS = new NamespacedKey(plugin, "gems");
        ENCHANTS = new NamespacedKey(plugin, "enchants");
        STRENGTHEN = new NamespacedKey(plugin, "strengthen");
        STRENGTHEN_CAP = new NamespacedKey(plugin, "strengthen_cap");
        BASE_ATK = new NamespacedKey(plugin, "base_atk");
        BASE_SPEED = new NamespacedKey(plugin, "base_speed");
        BASE_ARMOR = new NamespacedKey(plugin, "base_armor");
        BASE_TOUGH = new NamespacedKey(plugin, "base_tough");
        TALISMAN = new NamespacedKey(plugin, "talisman");
        ARMOR_REBALANCE = new NamespacedKey(plugin, "armor_rebalance");
        FURNACE = new NamespacedKey(plugin, "furnace");
        STRENGTHEN_FAILS = new NamespacedKey(plugin, "strengthen_fails");
        STRENGTHEN_BROKEN = new NamespacedKey(plugin, "strengthen_broken");
        STRENGTHEN_INVEST = new NamespacedKey(plugin, "strengthen_invest");
        CHARM_STEADY = new NamespacedKey(plugin, "charm_steady");
        CHARM_KINDLE = new NamespacedKey(plugin, "charm_kindle");
        IMPRINT_AFFIX = new NamespacedKey(plugin, "imprint_affix");
        IMPRINT_VALUE = new NamespacedKey(plugin, "imprint_value");
        IMPRINT_ARMOR = new NamespacedKey(plugin, "imprint_armor");
        GEM_EFFECTS = new NamespacedKey(plugin, "gem_effects");

        // ---- v2.12 锻阶（蜕变）----
        EVOLUTION = new NamespacedKey(plugin, "evolution");
        EVOLUTION_FAILS = new NamespacedKey(plugin, "evolution_fails");

        // ---- v2.13 连击 / 狂热 / 护体 ----
        STRENGTHEN_COMBO = new NamespacedKey(plugin, "strengthen_combo");
        FRENZY_PROGRESS = new NamespacedKey(plugin, "frenzy_progress");
        FRENZY_LEFT = new NamespacedKey(plugin, "frenzy_left");
        CORNERSTONE_USED = new NamespacedKey(plugin, "cornerstone_used");
        INHERIT_LEVEL = new NamespacedKey(plugin, "inherit_level");

        // ---- v2.14 龙税周目 ----
        FORM = new NamespacedKey(plugin, "form");

        // ---- v2.15 铁砧强化 ----
        HAMMER = new NamespacedKey(plugin, "hammer");
        ANVIL_SESSION = new NamespacedKey(plugin, "anvil_session");

        // ---- v2.18 剑尘（dissolve）----
        DUST = new NamespacedKey(plugin, "fm_dust");

        // ---- 拓展第一档：名人殿堂 ----
        HALL_DISPLAY = new NamespacedKey(plugin, "hall_display");

        // ---- 剑体放大（v2.20）----
        BIG_SWORD_DISPLAY = new NamespacedKey(plugin, "big_sword_display");
        PLAYER_SCALE = new NamespacedKey(plugin, "player_scale");

        // ---- 拓展第二档：套装效果 2.0 ----
        RELIC_SET_CD = new NamespacedKey(plugin, "fm_set_cd");

        // ---- 原创附魔（v2.23.2）----
        /** 原创附魔：{@code id:level|id:level}，与原版附魔的 {@link #ENCHANTS} 分开存 */
        CUSTOM_ENCH = new NamespacedKey(plugin, "fm_custom_ench");
        /** 附魔书成品标记：这本书是附魔台出来的原创附魔书 */
        ENCH_BOOK = new NamespacedKey(plugin, "fm_ench_book");
        /** 附魔台会话（防跨玩家复用界面） */
        ENCH_SESSION = new NamespacedKey(plugin, "fm_ench_session");
    }
}
