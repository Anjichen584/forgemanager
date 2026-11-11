package com.forgemaster.manuals;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.forge.ForgeSession;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.quality.Quality;
import com.forgemaster.stats.StatsStore;
import com.forgemaster.util.Keys;
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
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 剑谱库：登记成品 → 别人照着临摹（同种子复刻）。
 */
public final class ManualStore implements InventoryHolder {

    public static final int LIST_START = 9;
    public static final int LIST_LEN = 18;
    public static final int CLOSE_SLOT = 22;

    private final ForgingMasterPlugin plugin;
    private final File file;
    private final Map<String, ManualEntry> entries = new HashMap<>();
    private final Set<String> seenSignatures = new LinkedHashSet<>();

    /** 谁正在临摹哪本谱 */
    private final Map<UUID, String> copying = new HashMap<>();

    private Inventory inventory;

    public ManualStore(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "manuals.yml");
    }

    // ------------------------------------------------------------ 存盘

    public void load() {
        entries.clear();
        seenSignatures.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        List<Map<?, ?>> raw = yml.getMapList("manuals");
        for (Map<?, ?> m : raw) {
            try {
                YamlConfiguration tmp = new YamlConfiguration();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    tmp.set(String.valueOf(e.getKey()), e.getValue());
                }
                ManualEntry entry = ManualEntry.fromSection(tmp);
                entries.put(entry.id(), entry);
                seenSignatures.add(signature(entry.materialId(), entry.quality(), entry.name()));
            } catch (Exception ex) {
                plugin.getLogger().warning("剑谱数据损坏，已跳过一条");
            }
        }
        if (!entries.isEmpty()) {
            plugin.getLogger().info("已载入 " + entries.size() + " 本剑谱");
        }
    }

    public void save() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            return;
        }
        YamlConfiguration yml = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (ManualEntry e : entries.values()) {
            list.add(e.toMap());
        }
        yml.set("manuals", list);
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("保存 manuals.yml 失败: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ 查询

    public List<ManualEntry> all() {
        List<ManualEntry> list = new ArrayList<>(entries.values());
        list.sort(Comparator.comparingDouble(ManualEntry::score).reversed()
                .thenComparingLong(ManualEntry::createdAt));
        return list;
    }

    public ManualEntry get(String id) {
        return entries.get(id);
    }

    public int size() {
        return entries.size();
    }

    private static String signature(String materialId, Quality q, String name) {
        return materialId.toLowerCase() + "|" + (q == null ? "?" : q.name()) + "|" + name;
    }

    // ------------------------------------------------------------ 登记

    /**
     * 把手上这把登记成剑谱。
     *
     * @return 提示文本
     */
    public String record(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!plugin.items().isForged(hand)) {
            return "<red>手上要拿一把锻造品才能登记。";
        }
        var meta = hand.getItemMeta();
        if (meta == null) {
            return "<red>物品数据异常。";
        }
        var pdc = meta.getPersistentDataContainer();
        String matId = pdc.get(Keys.MATERIAL, PersistentDataType.STRING);
        String qn = pdc.get(Keys.QUALITY, PersistentDataType.STRING);
        Quality q = qn == null ? Quality.COMMON : Quality.byName(qn);
        Double score = pdc.get(Keys.SCORE, PersistentDataType.DOUBLE);
        String pattern = pdc.get(Keys.PATTERN, PersistentDataType.STRING);
        Integer layers = pdc.get(Keys.LAYERS, PersistentDataType.INTEGER);
        String quench = pdc.get(Keys.QUENCH, PersistentDataType.STRING);
        String csv = pdc.get(Keys.AFFIX_LIST, PersistentDataType.STRING);

        String rawName = Msg.plain(meta.displayName());
        String name = rawName == null || rawName.isBlank() ? "无名之刃" : rawName;
        // 去掉品质前缀，只留剑名
        if (name.contains("·")) {
            name = name.substring(name.indexOf('·') + 1);
        }

        if (entries.size() >= plugin.settings().manualMaxEntries()) {
            return "<red>剑谱库满了（" + entries.size() + " 本），先删几本。";
        }
        String sig = signature(matId == null ? "?" : matId, q, name);
        if (seenSignatures.contains(sig)) {
            return "<gray>这本谱已经有人登记过了：《<white>" + name + "</white>》。";
        }

        List<String> affixes = new ArrayList<>();
        if (csv != null && !csv.isBlank()) {
            for (String part : csv.split(",")) {
                if (!part.isBlank()) {
                    affixes.add(part.trim());
                }
            }
        }

        // 种子：用成品的分数+词条+时间派生一个稳定的伪种子，保证临摹可复现
        long seed = 31L * (matId == null ? 0 : matId.hashCode())
                + (qn == null ? 0 : qn.hashCode())
                + (long) ((score == null ? 0 : score) * 1000)
                + (layers == null ? 0 : layers) * 7717L
                + affixes.hashCode();

        String id = shortId(sig);
        // 4 位 id 只有 65536 种：撞上会静默覆盖旧谱（旧谱丢了还占着签名不能再登记），冲突就加盐重算
        int salt = 0;
        while (entries.containsKey(id) && salt < 16) {
            id = shortId(sig + "#" + salt++);
        }
        ManualEntry entry = new ManualEntry(id, player.getUniqueId(), player.getName(), name,
                matId == null ? "iron" : matId, q,
                score == null ? 0 : score,
                pattern == null ? "" : pattern,
                layers == null ? 0 : layers,
                quench == null ? "none" : quench,
                affixes, seed, System.currentTimeMillis());
        entries.put(id, entry);
        seenSignatures.add(sig);
        save();
        plugin.stats().addReputation(player.getUniqueId(), 15);

        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.9f, 1.4f);
        return "<green>已收录剑谱《<white>" + name + "</white>》</green> <dark_gray>(" + id + ")"
                + " <gray>· 声望 <white>+15</white> · 临摹命令 <white>/zhujian copy " + id + "</white>";
    }

    private String shortId(String sig) {
        String hex = Integer.toHexString(Math.abs(sig.hashCode()) % 0xFFFF).toUpperCase();
        while (hex.length() < 4) {
            hex = "0" + hex;
        }
        return hex;
    }

    // ------------------------------------------------------------ 临摹

    /** 当前正在临摹的谱（无则 null） */
    public ManualEntry activeCopy(UUID uuid) {
        String id = copying.get(uuid);
        return id == null ? null : entries.get(id);
    }

    public String cancelCopy(UUID uuid) {
        return copying.remove(uuid) == null ? null : "已取消临摹。";
    }

    /**
     * 开始临摹：锁定剑胚与种子。
     *
     * @return 提示文本
     */
    public String startCopy(Player player, String id) {
        ManualEntry entry = entries.get(id.toUpperCase());
        if (entry == null) {
            return "<red>没有这本剑谱：" + id;
        }
        ForgeMaterial mat = plugin.materials().get(entry.materialId());
        if (mat == null) {
            return "<red>这本谱用的剑胚（" + entry.materialId() + "）已经不在配置里了。";
        }
        copying.put(player.getUniqueId(), entry.id());
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 1.3f);
        return "<green>开始临摹《<white>" + entry.name() + "</white>》</green> <dark_gray>(" + entry.id() + ")"
                + " <gray>· 用 <white>" + mat.display() + "</white> 开工，种子已锁定"
                + " · 目标 <white>" + Msg.fmt(entry.score(), 1) + " 分</white>";
    }

    /**
     * 临摹完成：算复刻度、给反馈与声望。
     */
    public void reportCopy(ForgeSession s, Player p) {
        String id = s.manualId();
        ManualEntry entry = id == null ? null : entries.get(id);
        if (entry == null) {
            return;
        }
        copying.remove(s.uuid());
        if (s.result() == null) {
            return;
        }
        double rate = entry.copyRateOf(plugin.items(), s.result());
        s.copyRate(rate);
        entry.addCopy(rate);
        save();

        String tier;
        int rep;
        if (rate >= 0.9) {
            tier = "<gradient:#ffd166:#ff7a18><bold>神还原！</bold></gradient>";
            rep = 40;
        } else if (rate >= 0.7) {
            tier = "<#7ef2c8><bold>有几分神韵</bold></#7ef2c8>";
            rep = 22;
        } else if (rate >= 0.45) {
            tier = "<#ffd166>看得出是仿的</#ffd166>";
            rep = 10;
        } else {
            tier = "<gray>不太像</gray>";
            rep = 3;
        }
        plugin.stats().addReputation(s.uuid(), rep);
        if (plugin.ranks() != null) {
            plugin.ranks().refresh(s.uuid());
        }
        if (p != null) {
            p.playSound(p.getLocation(), rate >= 0.9 ? Sound.UI_TOAST_CHALLENGE_COMPLETE
                    : Sound.BLOCK_NOTE_BLOCK_BELL, 0.9f, rate >= 0.9 ? 1.3f : 1.0f);
            p.sendMessage(Msg.mm(Msg.PREFIX + "<gray>临摹《<white>" + entry.name() + "</white>》完成："
                    + tier + " <dark_gray>|</dark_gray> 复刻度 <white>"
                    + Msg.fmt(rate * 100, 1) + "%</white> <dark_gray>·</dark_gray> 声望 <white>+"
                    + rep + "</white>"));
            p.sendMessage(Msg.mm("<dark_gray>    原谱 <white>" + Msg.fmt(entry.score(), 1)
                    + "</white> 分 · 你 <white>" + Msg.fmt(s.finalScore(), 1) + "</white> 分 · 词条目标 "
                    + entry.affixIds().size() + " 条"));
        }
        if (rate >= 0.9 && plugin.getConfig().getBoolean("settings.broadcast-legendary", true)) {
            plugin.getServer().broadcast(Msg.mm(Msg.PREFIX + "<gray>" + s.playerName()
                    + " <gray>完美临摹了《<#ffd166>" + entry.name() + "</#ffd166>》，复刻度 <white>"
                    + Msg.fmt(rate * 100, 1) + "%</white>。"));
        }
    }

    /** 程序化登记一本谱（管理 / 自检用） */
    public void put(ManualEntry entry) {
        entries.put(entry.id(), entry);
        seenSignatures.add(signature(entry.materialId(), entry.quality(), entry.name()));
    }

    /** 删除一本谱（管理命令） */
    public String remove(String id) {
        ManualEntry e = entries.remove(id.toUpperCase());
        if (e == null) {
            return "<red>没有这本谱：" + id;
        }
        seenSignatures.remove(signature(e.materialId(), e.quality(), e.name()));
        save();
        return "<green>已删除《" + e.name() + "》。";
    }

    // ------------------------------------------------------------ 界面

    public Inventory buildList() {
        ArrayList<ManualEntry> list = new ArrayList<>(all());
        int rows = Math.max(3, Math.min(6, 1 + (list.size() + 8) / 9));
        Inventory inv = Bukkit.createInventory(this, rows * 9,
                Msg.mm("<dark_gray>剑谱库 <dark_gray>| <#7ef2c8>" + list.size() + " 本"));
        this.inventory = inv;

        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler());
        }
        int slot = 0;
        for (ManualEntry e : list) {
            if (slot >= inv.getSize() - 9) {
                break;
            }
            inv.setItem(slot++, render(e));
        }
        inv.setItem(inv.getSize() - 4, icon(Material.BARRIER, "<red>关闭",
                List.of("<gray>主手拿成品 → <white>/zhujian record</white> 登记新谱",
                        "<gray>点谱 → 直接开始临摹")));
        return inv;
    }

    private ItemStack render(ManualEntry e) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>著者: <white>" + e.authorName());
        lore.add("<gray>剑胚: <white>" + e.materialId()
                + " <dark_gray>·</dark_gray> " + (e.quality() == null ? "?" : e.quality().colored()));
        lore.add("<gray>原谱评分: <white>" + Msg.fmt(e.score(), 1));
        if (e.pattern() != null && !e.pattern().isBlank()) {
            lore.add("<gray>钢纹: <white>" + e.pattern() + " <dark_gray>(" + e.layers() + " 层)</dark_gray>");
        }
        if (!e.affixIds().isEmpty()) {
            lore.add("<gold>词条目标: <white>" + String.join("<dark_gray>,</dark_gray> ", e.affixIds()));
        }
        lore.add("");
        lore.add("<gray>已被临摹: <white>" + e.copies() + " 次"
                + (e.bestCopyRate() > 0 ? " <dark_gray>·</dark_gray> 最高复刻 <white>"
                + Msg.fmt(e.bestCopyRate() * 100, 0) + "%" : ""));
        lore.add("<yellow>▶ 左键开始临摹（同种子复刻）");
        return icon(e.quality() == null ? Material.WRITABLE_BOOK : e.quality().icon(), 
                "<gradient:#7ef2c8:#4ea8ff><bold>《" + e.name() + "》</bold></gradient>", lore);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    // ------------------------------------------------------------ 小工具

    private static ItemStack filler() {
        ItemStack it = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Msg.mm("<dark_gray>·").decoration(TextDecoration.ITALIC, false));
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