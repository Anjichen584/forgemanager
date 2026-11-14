package com.forgemaster.evolve;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.econ.CoinReason;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.material.MaterialGrades;
import com.forgemaster.quality.Quality;
import com.forgemaster.strengthen.Charm;
import com.forgemaster.strengthen.StrengthenService;
import com.forgemaster.strengthen.StrengthenService.FailResult;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Random;

/**
 * 锻阶（蜕变）：ForgeTax 式的「明牌概率 → 蜕变质变」层，挂在强化等级带上。
 *
 * <p>强化到阶段门槛后必须先蜕变才能继续锻。蜕变是一次独立掷骰：
 * 成功率明牌（基础 + 品质加成 + 旺火符），失败不碎剑不掉锻阶、
 * 掉强化级（稳胚符可托住）；炉火攒满（复用 {@link Keys#FURNACE}）免骰必成。</p>
 *
 * <p><b>品质的角色是出身而不是天花板</b>：蜕变上限对全品质统一，
 * 高品质吃成功率加成与材料折扣——抽到高品质依然爽，低品质也有终点。</p>
 */
public final class EvolutionService {

    private final ForgingMasterPlugin plugin;
    private final EvolutionRegistry registry;
    private final StrengthenService strengthen;
    private final Random random = new Random();

    public EvolutionService(ForgingMasterPlugin plugin, EvolutionRegistry registry,
                            StrengthenService strengthen) {
        this.plugin = plugin;
        this.registry = registry;
        this.strengthen = strengthen;
    }

    public EvolutionRegistry registry() {
        return registry;
    }

    public boolean enabled() {
        return registry.enabled() && strengthen.enabled();
    }

    /**
     * 物品当前锻阶（0 = 未蜕变；非锻造品返回 -1）。
     *
     * <p>「是不是锻造品」只认 {@link com.forgemaster.item.ItemFactory#isForged}，
     * <b>不看 PDC 里有没有 evolution 键</b>：出厂剑从来没人写过这个键，
     * 早期版本按「键缺失 = -1」判定，结果手里握着一把刚锻好的剑执行
     * {@code /zhujian evolve} 会被当成「不是锻造品」顶回去
     * （命令与界面两处入口都拿 {@code stageOf(...) < 0} 当拒绝条件）。</p>
     */
    public int stageOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !plugin.items().isForged(item)) {
            return -1;
        }
        Integer v = intOf(item, Keys.EVOLUTION);
        return v == null ? 0 : Math.max(0, v);
    }

    /** 蜕变失败累计次数 */
    public int failsOf(ItemStack item) {
        Integer v = intOf(item, Keys.EVOLUTION_FAILS);
        return v == null ? 0 : v;
    }

    /**
     * 当前卡住的门槛：强化等级已够到下一阶但还没蜕变时非 null。
     * 强化主流程靠它拦截（见 {@link StrengthenService#strengthen}）。
     */
    public EvolutionStage pendingGate(ItemStack item) {
        if (!enabled()) {
            return null;
        }
        int level = strengthen.levelOf(item);
        if (level < 0) {
            return null;
        }
        return registry.gateAt(Math.max(0, stageOf(item)), level);
    }

    /**
     * 明牌成功率（不含旺火符）：阶段基础 + 品质加成，夹在 1% 与 100% 之间。
     */
    public double successChance(ItemStack item, EvolutionStage stage) {
        Quality q = strengthen.qualityOf(item);
        double bonus = q == null ? 0 : registry.qualityBonusPerTier() * q.ordinal();
        return Math.max(0.01, Math.min(1.0, stage.successBase() + bonus));
    }

    /** 蜕变手续费（剑币） */
    public long costOf(EvolutionStage stage) {
        return stage.costCoins();
    }

    /**
     * 蜕变材料个数：阶段定义 × 品质折扣（高品质出身锻造时就花了大钱，蜕变更省）。
     */
    public int materialAmountOf(ItemStack item, EvolutionStage stage) {
        Quality q = strengthen.qualityOf(item);
        int tier = q == null ? 0 : q.ordinal();
        double discount = Math.min(0.9, registry.materialDiscountPerTier() * tier);
        return Math.max(1, (int) Math.round(stage.materialAmount() * (1.0 - discount)));
    }

    /** 蜕变吃的材料种类：跟胚子的强化材料走 */
    public Material materialItemOf(ItemStack item) {
        return strengthen.materialItemOf(item);
    }

    /** 一次蜕变的主入口，返回给玩家看的结果文本（多段用 &lt;newline&gt; 分隔） */
    public String evolve(Player player, ItemStack item) {
        if (!enabled()) {
            return "<red>锻阶功能已关闭。";
        }
        int level = strengthen.levelOf(item);
        if (level < 0) {
            return "<red>这不是锻造品，蜕变不了。";
        }
        ForgeMaterial mat = strengthen.materialOf(item);
        if (mat == null || strengthen.qualityOf(item) == null) {
            return "<red>这把剑的胚子信息丢了（服主可能改过 materials 配置），蜕变不了。";
        }
        EvolutionStage stage = registry.stage(Math.max(0, stageOf(item)) + 1);
        if (stage == null) {
            return stageOf(item) <= 0
                    ? "<red>还没够到第一个门槛（强化到 <white>+"
                    + (registry.nextGate(0) == null ? "?" : registry.nextGate(0).level())
                    + "</white> 再来蜕变）。"
                    : "<gold>已经蜕变到顶了，这把剑的名字会留在传说里。";
        }
        if (level < stage.level()) {
            // 门槛 = 这一阶能锻到的最高级：没满就别谈蜕变，把话说满
            return "<red>强化还没满这一阶：上限是 <white>+" + stage.level()
                    + "</white>，你才 <white>+" + level + "</white>（还差 <white>"
                    + (stage.level() - level) + "</white> 级）。"
                    + "<newline><gray>继续强化到 <white>+" + stage.level()
                    + "</white>，砧上敲满了再来蜕变。</gray>";
        }
        Material costItem = materialItemOf(item);
        int need = materialAmountOf(item, stage);
        long cost = costOf(stage);
        if (costItem != null) {
            int have = MaterialGrades.totalCount(player, costItem);
            if (have < need) {
                return "<red>材料不够：要 <white>" + need + "</white> 个 "
                        + MaterialGrades.pretty(costItem) + "，你只有 <white>" + have + "</white> 个。";
            }
        }
        long coins = plugin.stats().coins(player.getUniqueId());
        if (coins < cost) {
            return "<red>剑币不够：需要 <white>" + cost + "</white>，你只有 <white>" + coins + "</white>。";
        }

        // ---- 炉火保底 / 旺火符 ----
        int pityNeed = registry.pityAttempts();
        boolean pityReady = strengthen.furnaceOf(item) >= pityNeed;
        double baseChance = successChance(item, stage);
        boolean kindle = !pityReady && baseChance < 1.0
                && Charm.enabled(plugin, Charm.KINDLE) && Charm.count(player, Charm.KINDLE) > 0;
        double chance = baseChance;
        if (kindle) {
            chance = Math.max(0.01, Math.min(1.0, chance + Charm.kindleBonus(plugin)));
        }
        int kindlePct = (int) Math.round((chance - baseChance) * 100);

        plugin.stats().takeCoins(player.getUniqueId(), cost, CoinReason.EVOLVE);
        if (costItem != null) {
            MaterialGrades.consume(player, costItem, need);
        }
        if (kindle && !pityReady) {
            Charm.take(player, Charm.KINDLE, 1);
        }

        // ---- 掷骰 ----
        if (pityReady || random.nextDouble() < chance) {
            return succeed(player, item, stage, chance, kindlePct, pityReady);
        }
        return fail(player, item, stage, chance, kindlePct);
    }

    private String succeed(Player player, ItemStack item, EvolutionStage stage,
                           double chance, int kindlePct, boolean pityReady) {
        int prevStage = Math.max(0, stageOf(item));
        writeInt(item, Keys.EVOLUTION, stage.index());
        writeInt(item, Keys.FURNACE, 0);
        if (plugin.spectrum() != null) plugin.spectrum().recordEvolution(player.getUniqueId(), stage.index()); // 名剑谱：最高锻阶登记
        if (plugin.seasonPass() != null) plugin.seasonPass().earn(player.getUniqueId(), "evolution"); // 赛季通行证：蜕变成功登记
        // 属性乘区 + Lore 锻阶行都走 strengthen.apply() 统一重算
        strengthen.apply(item);
        applyVisual(item, stage);

        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.2f);
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.9f, 1.4f);
        player.getWorld().spawnParticle(Particle.EXPLOSION,
                player.getLocation().add(0, 1.2, 0), 6, 0.4, 0.5, 0.4, 0.02);

        // v2.23 拓展 C：蜕变成功 → Webhook（走 plugin.broadcast，永不抛回业务路径）
        plugin.broadcast(com.forgemaster.webhook.WebhookEvent.EVOLUTION_SUCCESS,
                "【蜕变】" + player.getName() + " 的 " + item.getType().name()
                        + " 进化为〔" + stage.title() + "〕（第 " + stage.index() + " 阶）",
                java.util.Map.of("player", player.getName(),
                        "stage", String.valueOf(stage.index()),
                        "title", stage.title()));

        if (stage.index() >= registry.broadcastStage()) {
            Bukkit.broadcast(Msg.mm("<gradient:#ffd166:#ff7a3d><bold>〔锻阶〕</bold></gradient> "
                    + "<white>" + player.getName() + "</white> 的佩剑蜕变成了 <bold><color:"
                    + stage.color() + ">〔" + stage.title() + "〕</color></bold>！"));
        }

        StringBuilder sb = new StringBuilder("<color:").append(stage.color())
                .append("><bold>蜕变成功</bold></color><gray> —— 锻阶 </gray><color:")
                .append(stage.color()).append("><bold>〔").append(stage.title()).append("〕</bold></color>")
                .append("<newline><dark_gray>成功率 ").append(Msg.fmt(chance * 100, 0)).append("%")
                .append(kindlePct > 0 ? "（旺火符 +" + kindlePct + "%）" : "")
                .append(pityReady ? " · 炉火已满必成" : "")
                .append(" · 属性乘区 ×").append(Msg.fmt(registry.cumulativeMult(stage.index()), 2))
                .append("</dark_gray>");
        EvolutionStage next = registry.nextGate(stage.index());
        if (next != null) {
            sb.append("<newline><gray>下一门槛：<white>+").append(next.level())
                    .append("</white> 蜕变「<color:").append(next.color()).append(">")
                    .append(next.title()).append("</color>」</gray>");
        }
        if (stage.index() >= registry.glintStage() && prevStage < registry.glintStage()) {
            sb.append("<newline><#9f7aea>这件装备开始隐隐发光……</#9f7aea>");
        }
        return sb.toString();
    }

    private String fail(Player player, ItemStack item, EvolutionStage stage,
                        double chance, int kindlePct) {
        addInt(item, Keys.EVOLUTION_FAILS, 1);
        // 炉火保底与强化共用一个计数：失败次数攒满蜕变必成，强化也会吃到必成——
        // 同一把剑上两套保底共享「失败的学费」，语义是通的
        int pityNow = Math.min(registry.pityAttempts(), strengthen.furnaceOf(item) + 1);
        writeInt(item, Keys.FURNACE, pityNow);
        String tail = "<dark_gray> · 炉火 " + pityNow + "/" + registry.pityAttempts() + "</dark_gray>";

        String head = "<red>蜕变失败</red><dark_gray>（成功率 " + Msg.fmt(chance * 100, 0) + "%"
                + (kindlePct > 0 ? "，含旺火符 +" + kindlePct + "%" : "") + "）";
        // 掉强化级，不掉锻阶：阶段是「出身瓶颈」，掉一次级就当重新爬坡
        int drop = stage.failDrop() <= 0 ? 0 : 1 + random.nextInt(stage.failDrop());
        if (drop <= 0) {
            strengthen.apply(item);
            return head + "<gray>，材料白费了。</gray>" + tail;
        }
        int level = strengthen.levelOf(item);
        // 稳胚符：本该掉级时托住（与强化同一张符，语义一致）
        if (Charm.enabled(plugin, Charm.STEADY) && Charm.count(player, Charm.STEADY) > 0) {
            Charm.take(player, Charm.STEADY, 1);
            int left = Charm.count(player, Charm.STEADY);
            strengthen.apply(item);
            return head + "<gray>，<#6bd3ff>稳胚符</#6bd3ff>托住了——等级没降，仍是 <white>+"
                    + level + "</white> <dark_gray>（剩 " + left + " 张）</dark_gray>" + tail;
        }
        int newLevel = Math.max(0, level - drop);
        strengthen.setLevel(item, newLevel);
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.8f, 0.6f);
        return head + "<gray>，掉到 <white>+" + newLevel + "</white>"
                + (newLevel < stage.level()
                ? "<red>，跌出门槛了，锻回来再蜕变</red>" : "") + "</gray>" + tail;
    }

    // ------------------------------------------------------------ 视觉重写

    /**
     * 蜕变成功的视觉质变：名称换成阶段铭牌、高阶挂光效。
     *
     * <p>名称格式 {@code 〔铭牌〕本名}——用〔〕与品质前缀「称号·」区分开，
     * 重写时能干净地把旧铭牌剥掉再叠新的，重复蜕变也不会叠出一串。</p>
     */
    public void applyVisual(ItemStack item, EvolutionStage stage) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        String plain = Msg.plain(meta.displayName() == null ? Component.empty() : meta.displayName());
        // v2.26.4：base 会被拼回 MiniMessage 重写物品名——蜕变品名会被交易对手看到，
        // esc 防改名输入的标签注入（当前锻造品名=配置生成，这是纵深，不是多余的）
        String base = Msg.esc(stripTitles(plain));
        boolean fancy = stage.index() >= 4;
        String display = "<color:" + stage.color() + "><bold>"
                + (fancy ? "✦ " : "") + "〔" + stage.title() + "〕" + base + (fancy ? " ✦" : "")
                + "</bold></color>";
        meta.displayName(Msg.mm(display).decoration(TextDecoration.ITALIC, false));

        if (stage.index() >= registry.glintStage()) {
            meta.setEnchantmentGlintOverride(Boolean.TRUE);
        }
        item.setItemMeta(meta);
    }

    /**
     * 把名称里历史留下的阶段铭牌与品质前缀剥掉，还原「本名」。
     *
     * <p>两种前缀格式互不重叠：品质是「称号·」，锻阶是「〔铭牌〕」，
     * 所以不管蜕变过几次都能剥干净。</p>
     */
    public static String stripTitles(String plain) {
        String s = plain == null ? "" : plain;
        if (s.startsWith("〔")) {
            int end = s.indexOf('〕');
            if (end >= 0) {
                s = s.substring(end + 1);
            }
        }
        // 神话品质的称号也叫「不朽」，但走「·」格式，不会被〔〕剥离误伤
        for (Quality q : Quality.values()) {
            String t = q.title();
            if (t != null && !t.isEmpty() && s.startsWith(t + "·")) {
                s = s.substring(t.length() + 1);
                break;
            }
        }
        return s.isEmpty() ? "无名剑" : s;
    }

    // ------------------------------------------------------------ Lore / 文本

    /**
     * 「锻阶」Lore 行：纯文本以「锻阶」开头，{@link StrengthenService#rewriteLore}
     * 按这个前缀定位刷新（与「锻造等级」同一套契约）。
     *
     * @param stage 当前阶段号；0 或负数返回 null（行不该存在）
     */
    public static String evolutionLine(EvolutionRegistry registry, int stage, int fails) {
        if (stage <= 0) {
            return null;
        }
        EvolutionStage def = registry.stage(stage);
        if (def == null) {
            return null;
        }
        EvolutionStage next = registry.nextGate(stage);
        String nextPart = next == null ? " <dark_gray>· 已至终阶</dark_gray>"
                : " <dark_gray>▸ 下一阶 +" + next.level() + "</dark_gray>";
        return "<color:" + def.color() + ">锻阶 <dark_gray>▸</dark_gray> <bold>〔" + def.title()
                + "〕</bold></color><dark_gray> ×" + Msg.fmt(registry.cumulativeMult(stage), 2)
                + (fails > 0 ? " · 曾败 " + fails + " 次" : "") + "</dark_gray>" + nextPart;
    }

    /** 蜕变模拟（/zhujian evolve sim）：纯文本多行，不扣任何东西 */
    public String simulateEvolution(ItemStack item) {
        if (!enabled()) {
            return "<red>锻阶功能已关闭。";
        }
        int stageNow = Math.max(0, stageOf(item));
        EvolutionStage stage = registry.stage(stageNow + 1);
        if (stage == null) {
            return "<gold>已经蜕变到顶了。";
        }
        if (strengthen.levelOf(item) < 0) {
            return "<red>这不是锻造品。";
        }
        Quality q = strengthen.qualityOf(item);
        double chance = successChance(item, stage);
        int need = materialAmountOf(item, stage);
        Material costItem = materialItemOf(item);
        StringBuilder sb = new StringBuilder("<gold>〔锻阶模拟〕</gold> ")
                .append("<gray>下一阶：</gray><color:").append(stage.color()).append(">〔")
                .append(stage.title()).append("〕</color>")
                .append("<newline><gray>门槛：</gray><white>+").append(stage.level())
                .append("</white><gray>（当前 +").append(Math.max(0, strengthen.levelOf(item)))
                .append("，还差 ").append(Math.max(0, stage.level() - Math.max(0, strengthen.levelOf(item))))
                .append(" 级锻满这一阶）</gray>")
                .append("<newline><gray>成功率：</gray><white>").append(Msg.fmt(chance * 100, 0))
                .append("%</white> <dark_gray>（基础 ").append(Msg.fmt(stage.successBase() * 100, 0))
                .append("%")
                .append(q == null ? "" : " + 品质加成 " + Msg.fmt(registry.qualityBonusPerTier() * q.ordinal() * 100, 0) + "%")
                .append("）</dark_gray>")
                .append("<newline><gray>消耗：</gray><white>").append(costItem == null ? "无" : need + " 个 " + MaterialGrades.pretty(costItem))
                .append("</white> + <white>").append(costOf(stage)).append("</white> 剑币")
                .append("<newline><gray>失败：</gray><dark_gray>不掉锻阶，强化级掉 1..")
                .append(Math.max(1, stage.failDrop())).append("；炉火 ").append(registry.pityAttempts())
                .append(" 次必成</dark_gray>")
                .append("<newline><gray>收益：</gray><white>属性乘区 ×")
                .append(Msg.fmt(registry.cumulativeMult(stage.index()), 2)).append("</white>");
        return sb.toString();
    }

    // ------------------------------------------------------------ PDC 辅助

    private static Integer intOf(ItemStack item, org.bukkit.NamespacedKey key) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
    }

    /**
     * 直接把物品设到某一锻阶。<b>只给管理员发放 / 自检用</b>——
     * 绕过成功率、材料、费用与赛季通行证登记，一次调用就改属性乘区与 Lore 铭牌。
     *
     * <p>为什么不在命令里手写 PDC：锻阶的属性乘区在 {@code strengthen.apply()} 里算，
     * 铭牌在 {@link #applyVisual} 里画，两处都要按顺序调一次。任何绕过它们的写法
     * 都会得到「Lore 写着造化六阶、属性还是 0 阶」这种半成品。</p>
     *
     * @param item  目标物品；不是锻造品时静默不做
     * @param stage 目标阶号，会被夹在 {@code 0..最高阶} 之间
     * @return 实际落到的阶号；物品不合法返回 -1
     */
    public int forceStage(ItemStack item, int stage) {
        if (item == null || !plugin.items().isForged(item) || item.getItemMeta() == null) {
            return -1;
        }
        int max = registry.all().size();
        int want = Math.max(0, Math.min(max, stage));
        EvolutionStage target = registry.stage(want);
        if (target == null) {
            return -1;
        }
        writeInt(item, Keys.EVOLUTION, target.index());
        // 失败计数清零：满配物品不该带着「再失败一次必成」的赊账
        writeInt(item, Keys.EVOLUTION_FAILS, 0);
        writeInt(item, Keys.FURNACE, 0);
        strengthen.apply(item);          // 属性乘区按新阶重算
        applyVisual(item, target);       // 〔铭牌〕前缀 + 高阶光效
        return target.index();
    }

    private void writeInt(ItemStack item, org.bukkit.NamespacedKey key, int value) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, value);
        item.setItemMeta(meta);
    }

    private static void addInt(ItemStack item, org.bukkit.NamespacedKey key, int delta) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        Integer cur = meta.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
        meta.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, (cur == null ? 0 : cur) + delta);
        item.setItemMeta(meta);
    }

    /** GUI / 命令共用的快照：蜕变界面展示用的全部数字一次算好 */
    public record Snapshot(EvolutionStage stage, int currentStage, int strengthenLevel,
                           double chance, int materialAmount, Material material,
                           long coins, int pityNow, int pityNeed) {
    }

    public Snapshot snapshotOf(ItemStack item) {
        int stageNow = Math.max(0, stageOf(item));
        EvolutionStage stage = registry.stage(stageNow + 1);
        if (stage == null) {
            return null;
        }
        return new Snapshot(stage, stageNow, Math.max(0, strengthen.levelOf(item)),
                successChance(item, stage), materialAmountOf(item, stage),
                materialItemOf(item), costOf(stage),
                Math.min(registry.pityAttempts(), strengthen.furnaceOf(item)),
                registry.pityAttempts());
    }

    /** GUI 确认后真正执行（带主手校验），返回结果文本 */
    public String evolveFromGui(Player player, ItemStack item) {
        return evolve(player, item);
    }
}
