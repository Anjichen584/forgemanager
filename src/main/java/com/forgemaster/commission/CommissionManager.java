package com.forgemaster.commission;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.affix.Affix;
import com.forgemaster.config.Settings;
import com.forgemaster.econ.CoinReason;
import com.forgemaster.item.ItemFactory;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.quality.Quality;
import com.forgemaster.stats.StatsStore;
import com.forgemaster.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 锻造委托：生成、存盘、交付判定、界面。
 */
public final class CommissionManager {

    public static final int[] SLOTS = {10, 12, 14};
    public static final int HELD_SLOT = 22;
    public static final int CLOSE_SLOT = 18;
    public static final int REFRESH_SLOT = 26;

    private final ForgingMasterPlugin plugin;
    private final Settings settings;
    private final ItemFactory items;
    private final StatsStore stats;
    private final File file;
    private final Random random = new Random();
    private final Map<UUID, List<Commission>> byOwner = new HashMap<>();

    public CommissionManager(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.settings();
        this.items = plugin.items();
        this.stats = plugin.stats();
        this.file = new File(plugin.getDataFolder(), "commissions.yml");
    }

    // ------------------------------------------------------------ 存盘

    public void load() {
        byOwner.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = com.forgemaster.util.YamlIo.loadOrQuarantine(file, "CommissionManager", plugin.getLogger());
        ConfigurationSection root = yml.getConfigurationSection("players");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            try {
                UUID owner = UUID.fromString(key);
                List<Map<?, ?>> raw = root.getMapList(key + ".list");
                List<Commission> list = new ArrayList<>();
                for (Map<?, ?> m : raw) {
                    YamlConfiguration tmp = new YamlConfiguration();
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        tmp.set(String.valueOf(e.getKey()), e.getValue());
                    }
                    list.add(Commission.fromSection(owner, tmp));
                }
                byOwner.put(owner, list);
            } catch (Exception ex) {
                plugin.getLogger().warning("委托数据损坏，已跳过 " + key);
            }
        }
    }

    public void save() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            return;
        }
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, List<Commission>> e : byOwner.entrySet()) {
            // 空列表跳过：otherwise 每个曾经开过委托板的玩家都会在 yml 里留一个空 list，
            // 文件只增不减（audit/02-stability.md #5）。注意 expunge 时也要 yml.set(null)
            List<Commission> cs = e.getValue();
            if (cs == null || cs.isEmpty()) {
                yml.set("players." + e.getKey(), null);
                continue;
            }
            List<Map<String, Object>> list = new ArrayList<>();
            for (Commission c : cs) {
                list.add(c.toMap());
            }
            yml.set("players." + e.getKey() + ".list", list);
        }
        try {
            com.forgemaster.util.YamlIo.save(file, yml);
        } catch (IOException ex) {
            plugin.getLogger().warning("保存 commissions.yml 失败: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ 生成

    public List<Commission> of(UUID owner) {
        return byOwner.computeIfAbsent(owner, k -> new ArrayList<>());
    }

    /** 保证玩家有 N 张有效委托，过期的换成新的 */
    public void ensure(Player player) {
        ensureFor(player.getUniqueId());
    }

    /**
     * 无 Player 版本（自检 / 后台维护用）。
     *
     * <p>v2.23.1 修 P1：本方法会<b>真的改动</b>委托列表（踢过期、补新单），所以
     * 变动时必须落盘——否则 {@code save()} 在生产路径永远是死代码（原来唯一的
     * 调用点在自检里），重启后已交付的委托会从旧快照"复活"、可再交一次领奖。</p>
     */
    public List<Commission> ensureFor(UUID owner) {
        List<Commission> list = of(owner);
        if (!commissionEnabled()) {
            return list;
        }
        // 交付板只有 3 个固定槽位：slots 配比 3 大的话，多出来的委托永远无法交付，
        // 占位到期还会被记一次失败并断连锻链
        int slots = Math.min(SLOTS.length, Math.max(1, settings.commissionSlots()));
        boolean changed = list.removeIf(c -> {
            if (c.expired()) {
                stats.countCommission(owner, false);
                // v2.0 连锻链：委托逾期断链
                plugin.streaks().onBreak(owner, "委托逾期");
                return true;
            }
            return false;
        });
        int guard = 0;
        while (list.size() < slots && guard++ < 16) {
            Commission c = generate(owner);
            if (c == null) {
                break;
            }
            list.add(c);
            changed = true;
        }
        sortByDifficulty(list);
        // v2.23.1 修 P1：只有真的动过才写盘，避免每次开面板都整档重写
        if (changed) {
            save();
        }
        return list;
    }

    private void sortByDifficulty(List<Commission> list) {
        list.sort((a, b) -> Integer.compare(a.rewardRep(), b.rewardRep()));
    }

    private Commission generate(UUID owner) {
        Collection<ForgeMaterial> mats = plugin.materials().all();
        List<ForgeMaterial> pool = new ArrayList<>(mats);
        if (pool.isEmpty()) {
            // materials 段被清空时 nextInt(0) 会直接抛异常
            return null;
        }
        ForgeMaterial mat = pool.get(random.nextInt(pool.size()));
        int tier = Math.max(1, mat.tier());

        Commission.Kind kind = Commission.Kind.values()[random.nextInt(Commission.Kind.values().length)];
        String client = clientName();
        long expires = System.currentTimeMillis() + Math.max(1, settings.commissionExpireHours()) * 3_600_000L;

        String materialId = random.nextBoolean() ? mat.id() : "any";
        Quality qualityReq = null;
        String affixReq = null;
        int layersReq = 0;
        double scoreReq = 0;

        switch (kind) {
            case QUALITY -> {
                int idx = Math.min(Quality.values().length - 1, tier + random.nextInt(2));
                qualityReq = Quality.values()[idx];
            }
            case AFFIX -> {
                List<Affix> all = new ArrayList<>(plugin.affixes().all());
                if (all.isEmpty()) {
                    kind = Commission.Kind.SCORE;
                    scoreReq = 60;
                } else {
                    affixReq = all.get(random.nextInt(all.size())).id();
                    materialId = "any";
                }
            }
            case LAYERS -> layersReq = 3 + random.nextInt(4) + tier / 3;
            case SCORE -> scoreReq = 55 + random.nextInt(30);
        }

        int rewardRep = 15 + tier * 6 + (qualityReq == null ? 0 : qualityReq.ordinal() * 8)
                + layersReq * 2 + (int) (scoreReq / 4);
        int rewardAmount = 1 + random.nextInt(2) + tier / 3;

        return new Commission(shortId(), owner, kind, client, materialId, qualityReq, affixReq,
                layersReq, scoreReq, rewardItem(mat), rewardAmount, rewardRep, expires);
    }

    private String rewardItem(ForgeMaterial mat) {
        return mat.costItem().name();
    }

    private String clientName() {
        List<String> names = settings.commissionClients();
        return names.isEmpty() ? "客人" : names.get(random.nextInt(names.size()));
    }

    private String shortId() {
        String hex = Integer.toHexString(random.nextInt(0xFFFF)).toUpperCase();
        while (hex.length() < 4) {
            hex = "0" + hex;
        }
        return hex;
    }

    public boolean commissionEnabled() {
        return settings.commissionEnabled();
    }

    // ------------------------------------------------------------ 界面

    public Inventory buildBoard(Player player) {
        ensure(player);
        BoardHolder holder = new BoardHolder();
        Inventory inv = Bukkit.createInventory(holder, 27,
                Msg.mm("<dark_gray>锻造委托 <dark_gray>| <#ffd166>委托板"));
        holder.inventory(inv);

        for (int i = 0; i < 27; i++) {
            inv.setItem(i, filler());
        }
        inv.setItem(4, icon(Material.WRITABLE_BOOK, "<#ffd166>委托板",
                List.of("<gray>客人挂了单，按要求交货换声望。",
                        "<gray>点委托 = 用手上这把交付。",
                        "<gray>过期就作废，会重新挂新单。")));

        List<Commission> list = of(player.getUniqueId());
        for (int i = 0; i < SLOTS.length && i < list.size(); i++) {
            inv.setItem(SLOTS[i], renderOrder(list.get(i)));
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            inv.setItem(HELD_SLOT, icon(Material.ITEM_FRAME, "<gray>手上没有东西",
                    List.of("<dark_gray>先把要交付的剑拿在主手")));
        } else {
            List<String> lore = new ArrayList<>();
            lore.add("<dark_gray>拿着它点上面的委托即可交付");
            lore.add("");
            for (int i = 0; i < list.size(); i++) {
                String miss = list.get(i).missing(items, held);
                lore.add(miss == null
                        ? "<#4ee36b>✔ 满足委托 " + list.get(i).id()
                        : "<red>✘ " + list.get(i).id() + " " + miss);
            }
            ItemStack show = held.clone();
            ItemMeta meta = show.getItemMeta();
            if (meta != null) {
                List<Component> old = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                for (String l : lore) {
                    old.add(Msg.mm(l).decoration(TextDecoration.ITALIC, false));
                }
                meta.lore(old);
                show.setItemMeta(meta);
            }
            inv.setItem(HELD_SLOT, show);
        }

        inv.setItem(CLOSE_SLOT, icon(Material.BARRIER, "<red>关闭", List.of()));
        inv.setItem(REFRESH_SLOT, icon(Material.CLOCK, "<#7ef2c8>怎么换单",
                List.of("<gray>Shift + 左键点某张委托 = 退掉它换一张。",
                        "<dark_gray>不消耗次数，只是重新挂单")));
        return inv;
    }

    private ItemStack renderOrder(Commission c) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>客人: <white>" + c.clientName());
        lore.add("");
        String matName = "any".equalsIgnoreCase(c.materialId()) || c.materialId() == null
                ? "<white>任意剑胚"
                : "<white>" + c.materialId();
        lore.add("<gray>要求: " + matName);
        if (c.qualityReq() != null) {
            lore.add("<gray>品质: <white>" + c.qualityReq().display() + " <gray>以上");
        }
        if (c.affixReq() != null) {
            lore.add("<gray>词条: <white>" + c.affixReq());
        }
        if (c.layersReq() > 0) {
            lore.add("<gray>折叠: <white>" + c.layersReq() + " 层以上");
        }
        if (c.scoreReq() > 0) {
            lore.add("<gray>评分: <white>" + Msg.fmt(c.scoreReq(), 1) + " 以上");
        }
        lore.add("");
        lore.add("<gold>报酬: <white>" + c.rewardAmount() + " × "
                + com.forgemaster.material.MaterialGrades.pretty(c.rewardItemId()));
        lore.add("<gold>声望: <white>+" + c.rewardRep());
        lore.add("");
        lore.add("<gray>剩余: <white>" + Msg.fmt(c.remainMillis() / 3_600_000.0, 1) + " 小时");
        lore.add("<yellow>▶ 左键交付（用手上的剑）");
        lore.add("<dark_gray>Shift + 左键换单");
        return icon(materialIcon(c), "<gradient:#ffd166:#ff9a3c><bold>委托 " + c.id() + "</bold></gradient>", lore);
    }

    private Material materialIcon(Commission c) {
        switch (c.kind()) {
            case QUALITY:
                return Material.ANVIL;
            case AFFIX:
                return Material.ENCHANTED_BOOK;
            case LAYERS:
                return Material.PAPER;
            default:
                return Material.EXPERIENCE_BOTTLE;
        }
    }

    // ------------------------------------------------------------ 交付

    /**
     * 尝试交付。
     *
     * @return 是否成功
     */
    public boolean deliver(Player player, int slot) {
        List<Commission> list = of(player.getUniqueId());
        int index = -1;
        for (int i = 0; i < SLOTS.length; i++) {
            if (SLOTS[i] == slot) {
                index = i;
                break;
            }
        }
        if (index < 0 || index >= list.size()) {
            return false;
        }
        Commission c = list.get(index);
        // 打开板子到点击之间委托可能到期：过期的不许再交（ensureFor 重开界面才会换新）
        if (c.expired()) {
            Msg.prefix(player, "<red>这张委托已经逾期了，换一张吧。");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
            return false;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        String missing = c.missing(items, held);
        if (missing != null) {
            Msg.prefix(player, "<red>交不了：<white>" + missing + "</white>。");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
            return false;
        }

        held.setAmount(held.getAmount() - 1);
        player.getInventory().setItemInMainHand(held.getAmount() <= 0 ? null : held);

        List<Commission> rest = list;
        rest.remove(index);
        // v2.23.1 修 P1：销单必须**先落盘再发奖**。
        // 原来这里一行 save() 都没有（生产路径唯一调用在自检里），所以：
        //   · commissions.yml 若存在旧快照，重启后已交的委托原样回来 → 可再交一次领币；
        //   · 不存在则委托状态从不持久化，等于每次重启全换新单。
        // 现在先落盘：崩溃只会「单没了、奖没发」（可人工补），不会再出现重复交付。
        save();
        stats.countCommission(player.getUniqueId(), true);
        boolean promoted = stats.addReputation(player.getUniqueId(), c.rewardRep());
        if (plugin.ranks() != null) {
            plugin.ranks().refresh(player.getUniqueId()); // 段位：声望变化后即时刷新
        }
        if (plugin.achievements() != null) {
            plugin.achievements().check(player.getUniqueId(), "commission");
        }
        if (plugin.seasonPass() != null) plugin.seasonPass().earn(player.getUniqueId(), "commission"); // 赛季通行证：委托交付登记
        // v2.0 剑币：委托交货给币（不乘连锻倍率，连锻只看锻造本身）
        long coinGain = plugin.coinRules().commissionReward(c.rewardRep());
        // v2.23.1 修 P1：走 Wallet 而不是 stats()——coin-sinks=server 时与黑市/签到同一本账，
        // 否则玩家在这里赚的是剑币、在黑市花的却是服务器金币（两本账谁都花不出去）
        plugin.wallet().give(player, coinGain, CoinReason.COMMISSION);
        Msg.prefix(player, "<gray>委托剑币 +" + com.forgemaster.econ.CoinRules.fmt(coinGain));

        Material reward = Material.matchMaterial(c.rewardItemId());
        if (reward != null) {
            var leftover = player.getInventory().addItem(new ItemStack(reward, c.rewardAmount()));
            for (var r : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), r);
            }
        }
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
        player.getWorld().spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER,
                player.getLocation().add(0, 1, 0), 14, 0.4, 0.4, 0.4, 0.02);
        Msg.prefix(player, "<green>交货完成！</green> <gray>「" + c.clientName() + "」很满意 · 声望 <white>+"
                + c.rewardRep() + "</white> · 报酬 <white>" + c.rewardAmount() + " × "
                + com.forgemaster.material.MaterialGrades.pretty(c.rewardItemId()));
        if (promoted) {
            String title = StatsStore.titleOf(stats.get(player.getUniqueId()).reputation);
            plugin.getServer().broadcast(Msg.mm(Msg.PREFIX + "<gray>" + player.getName()
                    + " <gray>的声望称号变成了「<#ffd166>" + title + "</#ffd166>」。"));
        }
        ensure(player);
        return true;
    }

    /** 换单：把指定槽位的委托退掉重开（不占次数） */
    public void reroll(Player player, int slot) {
        List<Commission> list = of(player.getUniqueId());
        for (int i = 0; i < SLOTS.length; i++) {
            if (SLOTS[i] == slot && i < list.size()) {
                // materials 段被清空时 generate 返回 null（见 generate 的空池守卫）；
                // 不判空会把 null 写进列表，下次开板/交付直接 NPE 还留下脏数据
                Commission fresh = generate(player.getUniqueId());
                if (fresh == null) {
                    Msg.prefix(player, "<red>暂时没有可用剑胚，换不了单。");
                    return;
                }
                list.set(i, fresh);
                // v2.23.1 修 P1：换单同样要落盘，否则重启后换掉的单又回来（等价无限重掷）
                save();
                Msg.prefix(player, "<gray>换了一张新的委托。");
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
                return;
            }
        }
    }

    /**
     * 全服清理过期委托。
     *
     * <p>v2.23.1 修 P2：原来这里只 {@code removeIf(expired)}，与 {@link #ensureFor} 的
     * 清理分支口径不同——同样是逾期，走这条路不断连锻链、也不记 commission 失败，
     * 玩家可以挑路径规避断链惩罚。现在两条路统一成「记失败 + 断链 + 落盘」。</p>
     */
    public void purgeExpired() {
        boolean changed = false;
        for (Map.Entry<UUID, List<Commission>> e : byOwner.entrySet()) {
            UUID owner = e.getKey();
            if (e.getValue().removeIf(c -> {
                if (!c.expired()) {
                    return false;
                }
                stats.countCommission(owner, false);
                plugin.streaks().onBreak(owner, "委托逾期");
                return true;
            })) {
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    // ------------------------------------------------------------ 小工具

    private static ItemStack filler() {
        ItemStack it = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Msg.mm("<dark_gray>·").decoration(TextDecoration.ITALIC, false));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack icon(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) {
            return it;
        }
        meta.displayName(Msg.mm(name).decoration(TextDecoration.ITALIC, false));
        List<Component> lines = new ArrayList<>();
        for (String l : lore) {
            lines.add(Msg.mm(l).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lines);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }
}