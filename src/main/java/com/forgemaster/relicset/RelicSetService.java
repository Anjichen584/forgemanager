package com.forgemaster.relicset;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.material.ArmorCatalog;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 套装效果 2.0（拓展第二档）：按「材料件数」判定的独立套装体系。
 *
 * <p>与词条套装（{@code affix-set}，按词条组合判定）完全独立：这里只看玩家身上
 * 有几件<b>同一个材料 id</b> 的锻造护甲（{@code armor-<剑胚id>-<部位>}，
 * 经 {@link ArmorCatalog#resolve} 解析出剑胚 id），件数达到配置档位就吃对应加成。</p>
 *
 * <p><b>内置 + 配置</b>（v2.23 套装矩阵）：Java 内置五套按材料件数判定的套装
 * （铁 / 金 / 铜 / 钻石 / 下界合金，与 {@code materials} 段的剑胚 id 一一对应），
 * 组装顺序仿照 {@code AffixSetService}：<b>内置在前、配置覆盖 / 追加在后</b>——
 * 配置段与内置同 id 时整段覆盖内置，新段落追加成第六套起；判定材料 id
 * 不在 {@code materials} 注册表里的套装会被过滤掉（注册表空壳时不过滤，防御配置未载入）。
 * 加成数值、档位件数、主动技 id 依旧全部可配，改配置即改行为。</p>
 *
 * <p><b>被动</b>：穿戴时按档位在 {@link Attribute#ARMOR} 上挂一条
 * <b>玩家侧临时</b>修正（transient，键名 {@value #MODIFIER_PREFIX} + 套装id，
 * 与物品侧词条套装的 {@code fm_set_<套装id>}、基础属性键互不冲突）。
 * 穿脱即时增删：以 {@code join / respawn / inventory-click} 三个时机重算
 * （与 {@code ArmorRebalanceListener} 的挂载点一致，本 Paper 版本没有
 * {@code PlayerArmorChangeEvent}），并在死亡 / 退服 / 插件停用时整批还原。</p>
 *
 * <p><b>主动技</b>：主手拿同套武器时潜行 + 左键触发（由 {@link RelicSetListener}
 * 调 {@link #tryCast(Player)}）。技名通过配置 {@code active: <技能id>} 引用，
 * Java 里注册一个技能实现表：{@code fallen-star} 落星斩（星陨）、
 * {@code swift-burst} 疾风爆发（金）、{@code vital-spring} 生机回涌（铜）、
 * {@code aegis-shield} 坚壁护盾（钻石）、{@code heavy-reap} 重击吸血（下界合金）；
 * 内置五套里铁卫是纯被动（不配 active）。哪个套装带主动技、几件解锁，全看配置。
 * 冷却记在玩家 PDC（键 {@code fm_set_cd}，值 {@code <套装id>:<就绪时间戳毫秒>}），
 * 倒计时走 ActionBar。</p>
 *
 * <p><b>共存</b>：玩家属性键固定 {@code fm_set_armor_} 前缀 + 挂在 ARMOR 属性上，
 * 不会碰 AffixSetService 的物品侧 {@code fm_set_*} 攻击修饰符，也不会碰
 * {@code fm_atk / fm_armor / fm_prof_atk} 等其它键；清理时只摘自己前缀的那几条。</p>
 */
public final class RelicSetService {

    /** 玩家侧护甲修正键前缀：每个套装一条，{@code fm_set_armor_<套装id>} */
    public static final String MODIFIER_PREFIX = "fm_set_armor_";

    private final ForgingMasterPlugin plugin;

    /** 配置解析结果缓存；config 重载后 ConfigurationSection 实例会换掉，据此自动失效 */
    private List<RelicSet> cache;
    private ConfigurationSection cacheSource;

    /** 每个玩家当前实际挂着的套装加成（套装id → 护甲值），重算时按差量增删 */
    private final Map<UUID, Map<String, Double>> applied = new HashMap<>();

    public RelicSetService(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ 开关与配置

    public boolean enabled() {
        return plugin.settings().flag("relic-set.enabled", true);
    }

    /**
     * 强制丢掉解析缓存并给在线玩家重算一遍（config reload 后调用）。
     *
     * <p>在线玩家身上的旧修正按差量重挂：数值改了就换，套装段删了就摘。</p>
     */
    public void reload() {
        cache = null;
        cacheSource = null;
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyBonuses(player);
        }
    }

    /** 全部生效中的套装（只来自 config；段不存在 = 空表，没有任何内置兜底） */
    public List<RelicSet> all() {
        ConfigurationSection section = plugin.settings().section("relic-set.sets");
        List<RelicSet> cached = cache;
        if (cached != null && cacheSource == section) {
            return cached;
        }
        List<RelicSet> built = build(section);
        cache = built;
        cacheSource = section;
        return built;
    }

    /** 按 id 取套装；没有返回 null */
    public RelicSet set(String setId) {
        if (setId == null || setId.isBlank()) {
            return null;
        }
        String key = setId.trim().toLowerCase(Locale.ROOT);
        for (RelicSet s : all()) {
            if (s.id().equals(key)) {
                return s;
            }
        }
        return null;
    }

    /**
     * 组装套装表：<b>内置五套在前</b>，配置段落逐项覆盖同 id / 追加新套在后，
     * 最后过滤掉判定材料 id 不在 {@code materials} 注册表里的套装。
     *
     * <p>配置段落里的<b>纯数字子键</b>是档位（件数 → 该档效果），其余子键
     * （{@code name} / {@code cooldown-seconds} 等）是元信息。解析失败的段落
     * 只 warning 跳过，绝不抛异常把服务器搞崩——与 AffixSetService 同一口。</p>
     */
    private List<RelicSet> build(ConfigurationSection root) {
        LinkedHashMap<String, RelicSet> configured = new LinkedHashMap<>();
        if (root != null) {
            for (String rawId : root.getKeys(false)) {
                ConfigurationSection sec = root.getConfigurationSection(rawId);
                if (sec == null) {
                    continue;
                }
                try {
                    RelicSet built = parseSet(rawId, sec);
                    if (!built.tiers().isEmpty()) {
                        configured.put(rawId.trim().toLowerCase(Locale.ROOT), built);
                    } else {
                        plugin.getLogger().warning("套装效果 " + rawId + " 没有配置任何数字档位（如 2: / 4:），已跳过");
                    }
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("解析套装效果 " + rawId + " 失败，已跳过: " + ex.getMessage());
                }
            }
        }
        List<RelicSet> out = new ArrayList<>();
        for (RelicSet s : merge(defaultSets(), List.copyOf(configured.values()))) {
            if (!materialKnown(s.id())) {
                plugin.getLogger().warning("套装效果 " + s.id() + " 的材料 id 在 config.yml 的 materials 段里不存在，该套装已跳过");
                continue;
            }
            out.add(s);
        }
        return List.copyOf(out);
    }

    /**
     * 内置五套（纯代码默认值，全部可被配置覆盖）：铁卫 / 鎏金 / 铜辉 / 坚晶 / 渊黑。
     *
     * <p>与 {@code materials} 段的剑胚 id 一一对应（iron / gold / copper / diamond /
     * netherite）。鎏金以上的四套各带一个简单主动技（药水 / 范围型），铁卫纯被动。</p>
     */
    static List<RelicSet> defaultSets() {
        return List.of(
                // 铁卫：纯被动，护甲稳扎稳打
                setOf("iron", "铁卫",
                        2, new RelicTier(3.0, null),
                        4, new RelicTier(6.0, null)),
                // 鎏金：疾风爆发（短时间移速 + 急迫）
                setOf("gold", "鎏金",
                        2, new RelicTier(2.0, null),
                        4, new RelicTier(4.0, "swift-burst")),
                // 铜辉：生机回涌（持续回血）
                setOf("copper", "铜辉",
                        2, new RelicTier(2.0, null),
                        4, new RelicTier(4.0, "vital-spring")),
                // 坚晶：坚壁护盾（抗性减伤）
                setOf("diamond", "坚晶",
                        2, new RelicTier(4.0, null),
                        4, new RelicTier(8.0, "aegis-shield")),
                // 渊黑：重击吸血（范围重击 + 按伤害回血）
                setOf("netherite", "渊黑",
                        2, new RelicTier(5.0, null),
                        4, new RelicTier(10.0, "heavy-reap")));
    }

    /** 两档位套装的小构造器（内置套都是 2 / 4 件两档，与星陨示例同构） */
    private static RelicSet setOf(String id, String name,
                                  int need1, RelicTier t1, int need2, RelicTier t2) {
        TreeMap<Integer, RelicTier> tiers = new TreeMap<>();
        tiers.put(need1, t1);
        tiers.put(need2, t2);
        return new RelicSet(id, name, 30, java.util.Collections.unmodifiableNavigableMap(tiers));
    }

    /**
     * 合并内置与配置套装（纯函数，自检用）：内置在前保持原序，配置同 id <b>整段覆盖</b>
     * 内置（含位置不后移），全新 id 追加在尾部；档位为空的配置段不覆盖内置。
     */
    static List<RelicSet> merge(List<RelicSet> builtins, List<RelicSet> configured) {
        LinkedHashMap<String, RelicSet> map = new LinkedHashMap<>();
        for (RelicSet s : builtins) {
            map.put(s.id(), s);
        }
        for (RelicSet s : configured) {
            if (!s.tiers().isEmpty()) {
                map.put(s.id(), s);
            }
        }
        return List.copyOf(map.values());
    }

    /**
     * 套装 id 对应的材料在注册表里找得到吗（能找到才可能穿出件数，找不到的套装是摆设）。
     *
     * <p>注册表整个空着（配置还没载入 / 空壳防御）时不判死——那不是套装的问题，
     * 与 AffixSetService 对词条注册表的处理同一口。</p>
     */
    private boolean materialKnown(String id) {
        if (plugin.materials() == null || plugin.materials().isEmpty()) {
            return true;
        }
        return plugin.materials().get(id) != null;
    }

    /**
     * 解析一个套装段：{@code starfall: {name: 星陨, 2: {...}, 4: {...}}}
     *
     * <p>数字档位键写在套装段第一层（与设计稿一致），元信息键混在同级，
     * 靠「是不是纯数字」区分。套装 id 会做键名清洗（小写、非法字符转下划线），
     * 保证能安全拼进 NamespacedKey。</p>
     */
    private RelicSet parseSet(String rawId, ConfigurationSection sec) {
        String id = rawId.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        String name = sec.getString("name", rawId);
        long cooldown = Math.max(1, sec.getLong("cooldown-seconds",
                Math.max(1, plugin.settings().intOf("relic-set.cooldown-seconds", 30))));
        TreeMap<Integer, RelicTier> tiers = new TreeMap<>();
        for (String key : sec.getKeys(false)) {
            int need;
            try {
                need = Integer.parseInt(key);
            } catch (NumberFormatException ex) {
                continue; // name / cooldown-seconds 等元信息键，不是档位
            }
            ConfigurationSection tierSec = sec.getConfigurationSection(key);
            if (tierSec == null) {
                continue;
            }
            double armorBonus = Math.max(0, tierSec.getDouble("armor-bonus", 0));
            String active = tierSec.getString("active");
            if (active != null && active.isBlank()) {
                active = null;
            }
            if (armorBonus <= 0 && active == null) {
                continue; // 这档既没加成也没主动技 = 没配，跳过
            }
            tiers.put(Math.max(1, need), new RelicTier(armorBonus, active));
        }
        return new RelicSet(id, name, cooldown, java.util.Collections.unmodifiableNavigableMap(tiers));
    }

    // ------------------------------------------------------------ 材料件数判定

    /**
     * 物品 PDC 里的材料 id（{@link Keys#MATERIAL}）还原成「套装用的基础材料 id」。
     *
     * <p>装备成品写的是 {@code armor-<剑胚id>-<部位>}：剥掉前缀和部位后缀，
     * 拿剩下的剑胚 id 反查 {@code MaterialRegistry}（空壳防御：查不到就当不同套）。
     * 武器胚直接用原 id。只有锻造品带 {@code MATERIAL} 键，普通物品一律返回 null。</p>
     */
    public String baseIdOf(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        String id = meta.getPersistentDataContainer().get(Keys.MATERIAL, PersistentDataType.STRING);
        if (id == null || id.isBlank()) {
            return null;
        }
        id = id.trim().toLowerCase(Locale.ROOT);
        if (id.startsWith("armor-")) {
            String rest = id.substring("armor-".length());
            int dash = rest.lastIndexOf('-');
            if (dash <= 0) {
                return null;
            }
            ForgeMaterial base = plugin.materials().get(rest.substring(0, dash));
            return base == null ? null : base.id();
        }
        ForgeMaterial mat = plugin.materials().get(id);
        return mat == null || mat.armor() ? null : mat.id();
    }

    /** 玩家四件护甲槽里「同材料」的件数（同 id 才算同套） */
    public int countPieces(Player player, String setId) {
        if (setId == null) {
            return 0;
        }
        int count = 0;
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            if (setId.equals(baseIdOf(piece))) {
                count++;
            }
        }
        return count;
    }

    /** 当前件数能吃到的最高档位（0 = 一档都没够） */
    public int highestTier(Player player, RelicSet set) {
        return tierFor(countPieces(player, set.id()), set.tiers());
    }

    /**
     * 件数 → 最高可达档位（纯函数，自检用）。
     *
     * <p>例：档位为 2 / 4 的套装穿 3 件只吃到 2 件档——<b>不吃满</b>，
     * 档位必须件数硬达标。0 = 一档都没够。</p>
     */
    public static int tierFor(int count, NavigableMap<Integer, RelicTier> tiers) {
        int best = 0;
        for (int need : tiers.keySet()) {
            if (count >= need) {
                best = Math.max(best, need);
            }
        }
        return best;
    }

    // ------------------------------------------------------------ 被动：穿戴护甲加成

    /**
     * 重算该玩家的套装护甲修正（幂等、按差量增删）。
     *
     * <p>对每个配置里的套装算出「当前件数吃到的 armor-bonus」，与上次实际挂着的
     * 值比对：变了就先 {@code removeModifier} 再按需 {@code addTransientModifier}。
     * transient 修饰符只活在内存里，服务器崩了也不会在玩家存档里留脏键——
     * 与 {@code DuelBalance} 的临时修正同一写法。</p>
     *
     * <p>调这个方法的时机（穿脱即时生效）：登录 / 重生 / 点背包（延迟一 tick 等槽位稳定）。
     * 套装关闭时等价于 {@link #strip(Player)}。</p>
     */
    public void applyBonuses(Player player) {
        if (!enabled()) {
            strip(player);
            return;
        }
        AttributeInstance inst = player.getAttribute(Attribute.ARMOR);
        if (inst == null) {
            return;
        }
        Map<String, Double> desired = new LinkedHashMap<>();
        for (RelicSet set : all()) {
            int tier = highestTier(player, set);
            if (tier <= 0) {
                continue;
            }
            double bonus = set.tiers().get(tier).armorBonus();
            if (bonus > 0) {
                desired.put(set.id(), bonus);
            }
        }
        Map<String, Double> prev = applied.getOrDefault(player.getUniqueId(), Map.of());
        Set<String> ids = new java.util.LinkedHashSet<>(prev.keySet());
        ids.addAll(desired.keySet());
        for (String id : ids) {
            double now = desired.getOrDefault(id, 0.0);
            double was = prev.getOrDefault(id, 0.0);
            if (Math.abs(now - was) < 1.0E-4) {
                continue; // 没变就不动：反复扫描不堆修饰符
            }
            inst.removeModifier(modifierKey(id));
            if (now > 0) {
                inst.addTransientModifier(new AttributeModifier(
                        modifierKey(id), now, AttributeModifier.Operation.ADD_NUMBER));
            }
        }
        applied.put(player.getUniqueId(), desired);
    }

    /** 还原该玩家的全部套装修正（死亡 / 退服 / 关闭套装时用），只摘自己前缀的键 */
    public void strip(Player player) {
        AttributeInstance inst = player.getAttribute(Attribute.ARMOR);
        if (inst != null) {
            for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
                var key = m.getKey();
                if (key != null && key.getKey().startsWith(MODIFIER_PREFIX)) {
                    inst.removeModifier(key);
                }
            }
        }
        applied.remove(player.getUniqueId());
    }

    /** 插件停用时把所有在线玩家的套装修正还原 */
    public void stripAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            strip(player);
        }
    }

    /** 该玩家当前实际生效的套装护甲加成总和（命令展示 / 自检用） */
    public double appliedBonus(Player player) {
        Map<String, Double> cur = applied.get(player.getUniqueId());
        if (cur == null) {
            return 0;
        }
        double sum = 0;
        for (double v : cur.values()) {
            sum += v;
        }
        return sum;
    }

    private org.bukkit.NamespacedKey modifierKey(String setId) {
        return new org.bukkit.NamespacedKey(plugin, MODIFIER_PREFIX + setId);
    }

    // ------------------------------------------------------------ 主动技

    /**
     * 尝试释放主动技（潜行 + 左键已被 Listener 过滤，这里只做套装侧校验）。
     *
     * @return true = 已释放（并写冷却）；false = 不满足条件或冷却中（冷却中会推 ActionBar 提示）
     */
    public boolean tryCast(Player player) {
        if (!enabled()) {
            return false;
        }
        // 主手必须拿着同套武器（材料 id 命中某个套装段）
        RelicSet set = set(baseIdOf(player.getInventory().getItemInMainHand()));
        if (set == null) {
            return false;
        }
        int tier = highestTier(player, set);
        if (tier <= 0) {
            return false;
        }
        String active = set.tiers().get(tier).activeSkill();
        if (active == null || active.isBlank()) {
            return false; // 该档没有主动技
        }
        long remain = remainingCooldown(player, set.id());
        if (remain > 0) {
            Msg.action(player, "<gray><#b06bff>" + set.name() + " 主动技</#b06bff> 冷却中 <white>"
                    + fmtSec(remain) + "</white></gray>");
            return false;
        }
        if (!runSkill(active, player, set)) {
            return false;
        }
        markCooldown(player, set.id(), set.cooldownSeconds());
        startCountdown(player, set);
        return true;
    }

    /** 技能实现表：fallen-star + 内置四技；新技能在这里加 case + config 加参数段 */
    private boolean runSkill(String skillId, Player player, RelicSet set) {
        return switch (skillId) {
            case "fallen-star" -> castFallenStar(player, set);
            case "swift-burst" -> castSwiftBurst(player);
            case "aegis-shield" -> castAegisShield(player);
            case "vital-spring" -> castVitalSpring(player);
            case "heavy-reap" -> castHeavyReap(player);
            default -> {
                plugin.getLogger().warning("套装 " + set.id() + " 配了未知主动技 " + skillId
                        + "（relic-set.skills 里也没有这个技能的参数段），已忽略");
                yield false;
            }
        };
    }

    /**
     * 落星斩：以玩家面前 3 格为中心落一捧火，对范围内敌人造成一次
     * 玩家攻击伤害 ×1.5 的额外伤害。
     *
     * <p>粒子量级参考 AnvilForgeManager 的砸中铁砧火（FLAME 为主 + SOUL_FIRE_FLAME
     * + LAVA + 烟），但<b>自己实现</b>，不调用它的私有方法。伤害走
     * {@link LivingEntity#damage(double, org.bukkit.entity.Entity)}——标准
     * {@code EntityDamageByEntityEvent} 管线、{@code damager} 是玩家本人，
     * 归属清晰，Vulcan 等反作弊按普通攻击事件处理。</p>
     */
    private boolean castFallenStar(Player player, RelicSet set) {
        ConfigurationSection skill = plugin.settings().section("relic-set.skills.fallen-star");
        double distance = skill == null ? 3.0 : Math.max(1.0, skill.getDouble("distance", 3.0));
        double radius = skill == null ? 3.0 : Math.max(1.0, skill.getDouble("radius", 3.0));
        double multiplier = skill == null ? 1.5 : Math.max(0.1, skill.getDouble("damage-multiplier", 1.5));

        Location center = player.getLocation().add(flatFacing(player).multiply(distance)).add(0, 0.9, 0);
        if (plugin.settings().particles()) {
            center.getWorld().spawnParticle(Particle.FLAME, center, 30, 0.9, 0.5, 0.9, 0.02);
            center.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, center, 14, 0.6, 0.4, 0.6, 0.02);
            center.getWorld().spawnParticle(Particle.LAVA, center, 8, 0.7, 0.3, 0.7, 0.0);
            center.getWorld().spawnParticle(Particle.LARGE_SMOKE, center, 8, 0.9, 0.5, 0.9, 0.01);
        }
        // 落锤（低音调的重锤落地）+ 碎裂（闷响爆炸），一捧火该有的两声
        center.getWorld().playSound(center, Sound.BLOCK_ANVIL_LAND, 0.9f, 1.4f);
        center.getWorld().playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 0.8f);

        double amount = Math.max(0.5, attackDamage(player) * multiplier);
        for (Entity e : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(e instanceof LivingEntity victim)) {
                continue;
            }
            if (victim.equals(player) || victim.isDead() || victim.isInvulnerable()
                    || victim instanceof ArmorStand) {
                continue;
            }
            // 标准 EntityDamageByEntityEvent，伤害归属 player（Vulcan 兼容：走普通攻击事件）
            victim.damage(amount, player);
            victim.setFireTicks(Math.max(victim.getFireTicks(), 40));
        }
        return true;
    }

    /** 玩家当前攻击伤害（含身上全部加成），属性缺失时按 1 兜底 */
    private static double attackDamage(Player player) {
        AttributeInstance inst = player.getAttribute(Attribute.ATTACK_DAMAGE);
        return inst == null ? 1.0 : inst.getValue();
    }

    /** 玩家水平朝向单位向量（俯仰角不影响落点，直视上下也有稳定落点） */
    private static Vector flatFacing(Player player) {
        double yaw = Math.toRadians(player.getLocation().getYaw());
        return new Vector(-Math.sin(yaw), 0, Math.cos(yaw)).normalize();
    }

    /**
     * 疾风爆发（鎏金）：短时间移速 + 急迫的药水效果型爆发，不碰实体。
     *
     * <p>参数段 {@code relic-set.skills.swift-burst}：{@code duration-seconds} /
     * {@code speed-amplifier} / {@code haste-amplifier}，缺省 8 秒、速度 II、急迫 I。</p>
     */
    private boolean castSwiftBurst(Player player) {
        ConfigurationSection skill = plugin.settings().section("relic-set.skills.swift-burst");
        int seconds = skill == null ? 8 : Math.max(1, skill.getInt("duration-seconds", 8));
        int speedAmp = skill == null ? 1 : Math.max(0, skill.getInt("speed-amplifier", 1));
        int hasteAmp = skill == null ? 0 : Math.max(0, skill.getInt("haste-amplifier", 0));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                seconds * 20, speedAmp, false, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,
                seconds * 20, hasteAmp, false, true, true));
        if (plugin.settings().particles()) {
            player.getWorld().spawnParticle(Particle.CLOUD,
                    player.getLocation().add(0, 0.6, 0), 16, 0.4, 0.3, 0.4, 0.05);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_RABBIT_JUMP, 0.8f, 1.2f);
        Msg.action(player, "<#ffd166>✦</#ffd166> <yellow>疾风爆发</yellow> <gray>移速与挖速提升 "
                + seconds + " 秒</gray>");
        return true;
    }

    /**
     * 坚壁护盾（坚晶）：抗性（Resistance）减伤药水效果型，数值稳、不碰伤害管线。
     *
     * <p>参数段 {@code relic-set.skills.aegis-shield}：{@code duration-seconds} /
     * {@code amplifier}（0 = 抗性 I 约 20% 减伤，1 = 抗性 II），缺省 8 秒抗性 I。</p>
     */
    private boolean castAegisShield(Player player) {
        ConfigurationSection skill = plugin.settings().section("relic-set.skills.aegis-shield");
        int seconds = skill == null ? 8 : Math.max(1, skill.getInt("duration-seconds", 8));
        int amplifier = skill == null ? 0 : Math.max(0, skill.getInt("amplifier", 0));
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,
                seconds * 20, amplifier, false, true, true));
        if (plugin.settings().particles()) {
            player.getWorld().spawnParticle(Particle.END_ROD,
                    player.getLocation().add(0, 1.0, 0), 14, 0.5, 0.5, 0.5, 0.01);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0f, 0.9f);
        Msg.action(player, "<#4dc3ff>✦</#4dc3ff> <#4dc3ff>坚壁护盾</#4dc3ff> <gray>受到伤害减少，持续 "
                + seconds + " 秒</gray>");
        return true;
    }

    /**
     * 生机回涌（铜辉）：持续回血的药水效果型（再生），受击后开更划算。
     *
     * <p>参数段 {@code relic-set.skills.vital-spring}：{@code duration-seconds} /
     * {@code amplifier}，缺省 10 秒再生 II。</p>
     */
    private boolean castVitalSpring(Player player) {
        ConfigurationSection skill = plugin.settings().section("relic-set.skills.vital-spring");
        int seconds = skill == null ? 10 : Math.max(1, skill.getInt("duration-seconds", 10));
        int amplifier = skill == null ? 1 : Math.max(0, skill.getInt("amplifier", 1));
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,
                seconds * 20, amplifier, false, true, true));
        if (plugin.settings().particles()) {
            player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                    player.getLocation().add(0, 1.0, 0), 12, 0.5, 0.5, 0.5, 0.0);
        }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.1f);
        Msg.action(player, "<#4ee36b>✦</#4ee36b> <green>生机回涌</green> <gray>持续回复生命，"
                + seconds + " 秒</gray>");
        return true;
    }

    /**
     * 重击吸血（渊黑）：身前小范围重击，按造成伤害的一定比例回血——
     * 落星斩的近身变体，伤害走标准 {@code EntityDamageByEntityEvent}（归属玩家）。
     *
     * <p>参数段 {@code relic-set.skills.heavy-reap}：{@code radius} /
     * {@code damage-multiplier} / {@code heal-ratio}，缺省半径 2.5、伤害 ×1.2、
     * 回血 50%（按「造成的伤害总额」算，打空了不回血）。</p>
     */
    private boolean castHeavyReap(Player player) {
        ConfigurationSection skill = plugin.settings().section("relic-set.skills.heavy-reap");
        double radius = skill == null ? 2.5 : Math.max(1.0, skill.getDouble("radius", 2.5));
        double multiplier = skill == null ? 1.2 : Math.max(0.1, skill.getDouble("damage-multiplier", 1.2));
        double healRatio = skill == null ? 0.5 : Math.max(0.0, Math.min(1.0, skill.getDouble("heal-ratio", 0.5)));

        Location center = player.getLocation().add(flatFacing(player).multiply(1.5)).add(0, 0.9, 0);
        if (plugin.settings().particles()) {
            center.getWorld().spawnParticle(Particle.SWEEP_ATTACK, center, 6, 0.8, 0.5, 0.8, 0.0);
            center.getWorld().spawnParticle(Particle.CRIT, center, 18, 0.8, 0.5, 0.8, 0.1);
        }
        center.getWorld().playSound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.8f);

        double amount = Math.max(0.5, attackDamage(player) * multiplier);
        double dealt = 0;
        for (Entity e : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(e instanceof LivingEntity victim)) {
                continue;
            }
            if (victim.equals(player) || victim.isDead() || victim.isInvulnerable()
                    || victim instanceof ArmorStand) {
                continue;
            }
            victim.damage(amount, player);
            dealt += amount;
        }
        if (dealt > 0 && healRatio > 0) {
            // heal() 会被最大生命截断，超量回血不会溢出
            player.heal(dealt * healRatio);
            Msg.action(player, "<#ff7a3d>✦</#ff7a3d> <red>重击吸血</red> <gray>汲取 "
                    + Msg.fmt(dealt * healRatio, 1) + " 点生命</gray>");
        }
        return true;
    }

    // ------------------------------------------------------------ 冷却

    /** 该套装主动技的剩余冷却毫秒（0 = 已就绪；冷却记在玩家 PDC，跨重进保留） */
    public long remainingCooldown(Player player, String setId) {
        if (setId == null) {
            return 0;
        }
        String raw = player.getPersistentDataContainer().get(Keys.RELIC_SET_CD, PersistentDataType.STRING);
        if (raw == null) {
            return 0;
        }
        int sep = raw.indexOf(':');
        if (sep <= 0) {
            return 0;
        }
        if (!raw.substring(0, sep).equals(setId)) {
            return 0; // 别的套装的冷却，互不影响
        }
        long ready;
        try {
            ready = Long.parseLong(raw.substring(sep + 1));
        } catch (NumberFormatException ex) {
            return 0;
        }
        return Math.max(0, ready - System.currentTimeMillis());
    }

    private void markCooldown(Player player, String setId, long seconds) {
        player.getPersistentDataContainer().set(Keys.RELIC_SET_CD, PersistentDataType.STRING,
                setId + ":" + (System.currentTimeMillis() + seconds * 1000L));
    }

    /** 释放后每秒把剩余冷却刷到 ActionBar，就绪时提示一次后停掉 */
    private void startCountdown(Player player, RelicSet set) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }
                long remain = remainingCooldown(player, set.id());
                if (remain <= 0) {
                    Msg.action(player, "<#b06bff>✦</#b06bff> <gray>" + set.name() + " 主动技已就绪</gray>");
                    cancel();
                    return;
                }
                Msg.action(player, "<gray><#b06bff>" + set.name() + " 主动技</#b06bff> 冷却 <white>"
                        + fmtSec(remain) + "</white></gray>");
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private static String fmtSec(long millis) {
        return String.format(Locale.ROOT, "%.1f", millis / 1000.0);
    }

    // ------------------------------------------------------------ 命令展示

    /** 命令 {@code /zhujian relicset} 的状态文本（纯读，不改任何东西） */
    public String describe(Player player) {
        if (!enabled()) {
            return "<red>套装效果已关闭（relic-set.enabled: false）。</red>";
        }
        List<RelicSet> all = all();
        StringBuilder sb = new StringBuilder("<gray>套装效果 <white>").append(all.size())
                .append("</white> 套（按材料件数判定，同 id 才算同套）");
        if (all.isEmpty()) {
            sb.append("<newline><red>内置与配置里都没有可用的套装段"
                    + "（relic-set.sets 为空且内置套的材料都被过滤了）。</red>");
            return sb.toString();
        }
        for (RelicSet set : all) {
            int count = countPieces(player, set.id());
            int tier = highestTier(player, set);
            sb.append("<newline>");
            if (tier > 0) {
                sb.append("<#ffd166>✔ ").append(set.name()).append("</#ffd166>");
            } else {
                sb.append("<gray>… ").append(set.name()).append("</gray>");
            }
            sb.append(" <dark_gray>(</dark_gray><white>").append(count).append(" 件</white><dark_gray>)</dark_gray>");
            // 当前档位的加成明细
            if (tier > 0) {
                RelicTier t = set.tiers().get(tier);
                if (t.armorBonus() > 0) {
                    sb.append(" <aqua>护甲 +").append(Msg.fmt(t.armorBonus(), 1)).append("</aqua>");
                }
                if (t.activeSkill() != null) {
                    sb.append(" <#b06bff>主动技: ").append(skillDisplay(t.activeSkill())).append("</#b06bff>");
                    long remain = remainingCooldown(player, set.id());
                    sb.append(remain > 0 ? " <dark_gray>冷却 " + fmtSec(remain) + "</dark_gray>"
                            : " <green>就绪</green>");
                }
            } else {
                // 没吃到档位：把各档效果列出来当目标
                for (Map.Entry<Integer, RelicTier> e : set.tiers().entrySet()) {
                    sb.append("<newline>  <dark_gray>").append(e.getKey()).append(" 件: </dark_gray>");
                    if (e.getValue().armorBonus() > 0) {
                        sb.append("<dark_gray>护甲 +").append(Msg.fmt(e.getValue().armorBonus(), 1)).append(" </dark_gray>");
                    }
                    if (e.getValue().activeSkill() != null) {
                        sb.append("<dark_gray>主动技: ").append(skillDisplay(e.getValue().activeSkill())).append("</dark_gray>");
                    }
                }
            }
        }
        sb.append("<newline><gray>当前套装护甲加成: <aqua>+")
                .append(Msg.fmt(appliedBonus(player), 1)).append("</aqua>");
        sb.append("<newline><dark_gray>主动技用法: 主手拿同套武器，潜行 + 左键。</dark_gray>");
        return sb.toString();
    }

    /** 技能 id 的显示名（GUI / 命令 / 自检共用）；未登记的技能原样返回 id */
    public static String skillDisplay(String skillId) {
        return switch (skillId) {
            case "fallen-star" -> "落星斩";
            case "swift-burst" -> "疾风爆发";
            case "aegis-shield" -> "坚壁护盾";
            case "vital-spring" -> "生机回涌";
            case "heavy-reap" -> "重击吸血";
            default -> skillId;
        };
    }

    // ------------------------------------------------------------ 数据结构

    /**
     * 一个档位的效果：额外护甲 + 可选的主动技 id。
     *
     * @param armorBonus  穿戴时挂到玩家 ARMOR 属性上的加法值（0 = 该档只有主动技）
     * @param activeSkill 主动技 id（{@code relic-set.skills.<id>} 的键）；null = 无主动技
     */
    public record RelicTier(double armorBonus, String activeSkill) {
    }

    /**
     * 一个套装：id 即配置段名（= 材料基础 id），档位表按件数升序。
     *
     * @param id              套装 id（已清洗，可安全拼进 NamespacedKey）
     * @param name            显示名
     * @param cooldownSeconds 主动技冷却秒数（全局默认可被套装段覆盖）
     * @param tiers           件数 → 该档效果；不可变，按件数有序
     */
    public record RelicSet(String id, String name, long cooldownSeconds,
                           NavigableMap<Integer, RelicTier> tiers) {
    }
}
