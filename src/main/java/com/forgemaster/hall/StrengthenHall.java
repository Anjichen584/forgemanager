package com.forgemaster.hall;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 名人堂「最高锻造等级」条目：全服历史最高的那一把强化品。
 *
 * <p>只存一条——历史最高。比它低的一律丢掉；<b>打平不覆盖</b>，
 * 除非这次达成的时间更早（补录 / 导入老存档时会用到）。</p>
 *
 * <p>存档独立放在 {@code plugins/ForgingMaster/hall-strengthen.yml}，
 * 不和 hall.yml 混： hall.yml 由 {@code /zhujian halloffame set} 整份重写，
 * 混在里面会被一次 set 覆盖掉。</p>
 *
 * <p>字段：物品名（去色显示名）、等级、上限、材料 id / 材料名、锻造者名 / UUID、达成时间。</p>
 *
 * <p><b>写入时机</b>：强化成功后由调用方显式调用 {@link #record(Player, ItemStack)}，
 * 本类不监听事件、也不改动 StrengthenService。带上等级比较，所以也可以在
 * 每次强化结束（成功或失败）之后无脑调一次——低等级不会覆盖现有纪录。</p>
 */
public final class StrengthenHall {

    /** 一条强化纪录 */
    public static final class Entry {
        /** 物品显示名（已去色） */
        public final String itemName;
        public final int level;
        public final int cap;
        /** 材料 id，如 iron / starfall */
        public final String materialId;
        /** 材料显示名，如「铁剑」「星陨剑」 */
        public final String materialName;
        /** 锻造者名字（读物品 PDC 的 forger，退化时用强化者名字） */
        public final String forgerName;
        /** 锻造者 UUID 字符串，可空 */
        public final String forgerId;
        /** 达成时间（毫秒） */
        public final long achievedAt;

        public Entry(String itemName, int level, int cap, String materialId, String materialName,
                     String forgerName, String forgerId, long achievedAt) {
            this.itemName = itemName;
            this.level = level;
            this.cap = cap;
            this.materialId = materialId;
            this.materialName = materialName;
            this.forgerName = forgerName;
            this.forgerId = forgerId;
            this.achievedAt = achievedAt;
        }
    }

    private final ForgingMasterPlugin plugin;
    /** 上层陈列，用来在刷新纪录后立刻重摆盔甲架（可为 null） */
    private final HallOfFame hall;
    private final File file;
    private Entry best;
    private boolean loaded;

    public StrengthenHall(ForgingMasterPlugin plugin, HallOfFame hall) {
        this.plugin = plugin;
        this.hall = hall;
        this.file = new File(plugin.getDataFolder(), "hall-strengthen.yml");
    }

    // ------------------------------------------------------------ 配置

    /** 是否登记强化纪录（hall-of-fame.strengthen.enabled，默认 true） */
    public boolean enabled() {
        return plugin.settings().flag("hall-of-fame.strengthen.enabled", true);
    }

    /** 低于这个等级不登记（默认 1，避免 +1 就占住名人堂） */
    public int minLevel() {
        return Math.max(1, plugin.settings().intOf("hall-of-fame.strengthen.min-level", 1));
    }

    /** 刷新纪录时是否全服播报（默认 true） */
    public boolean broadcast() {
        return plugin.settings().flag("hall-of-fame.strengthen.broadcast", true);
    }

    /** 名人堂陈列里是否多生成一排强化纪录盔甲架（默认 true） */
    public boolean showRow() {
        return plugin.settings().flag("hall-of-fame.strengthen.show-row", true);
    }

    // ------------------------------------------------------------ 载入 / 保存

    /** 首次读写时才落盘读一次，避免依赖插件主类的初始化顺序 */
    private synchronized void ensure() {
        if (!loaded) {
            load();
        }
    }

    public synchronized void load() {
        loaded = true;
        best = null;
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = com.forgemaster.util.YamlIo.loadOrQuarantine(file, "StrengthenHall", plugin.getLogger());
        ConfigurationSection sec = yml.getConfigurationSection("entry");
        if (sec == null) {
            return;
        }
        try {
            best = new Entry(sec.getString("item-name", "无名锻造品"),
                    Math.max(0, sec.getInt("level", 0)),
                    Math.max(0, sec.getInt("cap", 0)),
                    sec.getString("material", ""),
                    sec.getString("material-name", ""),
                    sec.getString("forger", "?"),
                    sec.getString("forger-id", ""),
                    sec.getLong("achieved-at", 0L));
            if (best.level <= 0) {
                best = null;
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("名人堂强化纪录读取失败：" + ex.getMessage());
            best = null;
        }
    }

    public synchronized void save() {
        if (best == null) {
            return;
        }
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("无法创建插件数据目录");
            return;
        }
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("entry.item-name", best.itemName);
        yml.set("entry.level", best.level);
        yml.set("entry.cap", best.cap);
        yml.set("entry.material", best.materialId);
        yml.set("entry.material-name", best.materialName);
        yml.set("entry.forger", best.forgerName);
        yml.set("entry.forger-id", best.forgerId);
        yml.set("entry.achieved-at", best.achievedAt);
        try {
            com.forgemaster.util.YamlIo.save(file, yml);
        } catch (IOException ex) {
            plugin.getLogger().warning("保存 hall-strengthen.yml 失败: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ 写入

    /**
     * 拿手里的物品去比一次历史最高。
     *
     * <p>等级、上限、材料、物品名、锻造者全部从物品自身读（只读调用 StrengthenService），
     * 所以物品被交易 / 改名之后，纪录里留的仍是达成当时的那一份描述。</p>
     *
     * @return true = 刷新了全服纪录（已落盘；按配置可能已广播）
     */
    public boolean record(Player player, ItemStack item) {
        if (player == null || item == null) {
            return false;
        }
        if (plugin.strengthen() == null) {
            return false;
        }
        int level = plugin.strengthen().levelOf(item);
        if (level < minLevel()) {
            return false;
        }
        int cap = plugin.strengthen().capOf(item);
        ForgeMaterial mat = plugin.strengthen().materialOf(item);
        String materialName = mat == null ? "" : Msg.plain(Msg.mm(mat.display()));
        String forger = forgerOf(item);
        boolean changed = submit(itemNameOf(item), level, Math.max(cap, level),
                mat == null ? "" : mat.id(), materialName,
                forger == null || forger.isBlank() ? player.getName() : forger,
                player.getUniqueId().toString(), System.currentTimeMillis());
        if (changed && broadcast()) {
            Bukkit.broadcast(Msg.mm(Msg.PREFIX + "<gray>名人堂刷新：<white>" + player.getName()
                    + "</white> 锻出了全服最高的 <#7ef2c8>+" + level + "</#7ef2c8> 锻造等级 <dark_gray>（"
                    + (materialName.isEmpty() ? "未知材料" : materialName) + "）"));
        }
        // 纪录变了就顺手重摆盔甲架，不用等下一个刷新周期
        if (changed && hall != null) {
            hall.refresh();
        }
        return changed;
    }

    /**
     * 底层登记（自检 / 后台补录直接调这个，不会触发播报）。
     *
     * @param level 本次达成等级
     * @param at    达成时间（毫秒），<= 0 时用当前时间
     * @return true = 顶掉了旧纪录
     */
    public synchronized boolean submit(String itemName, int level, int cap, String materialId,
                                       String materialName, String forgerName, String forgerId, long at) {
        ensure();
        if (!enabled() || level < minLevel()) {
            return false;
        }
        long when = at > 0 ? at : System.currentTimeMillis();
        if (best != null) {
            // 只记历史最高：更低直接丢；打平不覆盖，除非这次达成得更早
            if (level < best.level) {
                return false;
            }
            if (level == best.level && when >= best.achievedAt) {
                return false;
            }
        }
        best = new Entry(itemName == null || itemName.isBlank() ? "无名锻造品" : itemName,
                level,
                Math.max(cap, level),
                materialId == null ? "" : materialId,
                materialName == null ? "" : materialName,
                forgerName == null || forgerName.isBlank() ? "?" : forgerName,
                forgerId == null ? "" : forgerId,
                when);
        save();
        return true;
    }

    // ------------------------------------------------------------ 读

    /** 当前历史最高；没有纪录返回 null */
    public synchronized Entry entry() {
        ensure();
        return best;
    }

    /** 一行陈列文本（MiniMessage），没有纪录返回 null */
    public synchronized String line() {
        Entry e = entry();
        if (e == null) {
            return null;
        }
        return "<#ffd166>强化之巅</#ffd166> <white>【" + e.itemName + "】</white>"
                + " <dark_gray>|</dark_gray> <#7ef2c8>+" + e.level + "</#7ef2c8><dark_gray>/</dark_gray><gray>+"
                + e.cap + "</gray>"
                + " <dark_gray>|</dark_gray> <gray>" + (e.materialName.isEmpty() ? "未知材料" : e.materialName)
                + " <dark_gray>|</dark_gray> <white>" + e.forgerName + "</white>"
                + " <dark_gray>" + stamp(e.achievedAt) + "</dark_gray>";
    }

    /** 多行明细（命令 / 面板用） */
    public synchronized List<String> detailLines() {
        Entry e = entry();
        List<String> out = new ArrayList<>();
        if (e == null) {
            out.add("<gray>还没有人留下强化纪录。");
            return out;
        }
        out.add("<gray>物品: <white>【" + e.itemName + "】");
        out.add("<gray>锻造等级: <#7ef2c8>+" + e.level + "</#7ef2c8><dark_gray>/</dark_gray><gray>+" + e.cap);
        out.add("<gray>材料: <white>" + (e.materialName.isEmpty() ? e.materialId : e.materialName)
                + (e.materialId.isEmpty() ? "" : " <dark_gray>(" + e.materialId + ")</dark_gray>"));
        out.add("<gray>锻造者: <white>" + e.forgerName);
        out.add("<gray>达成时间: <white>" + stamp(e.achievedAt));
        return out;
    }

    /** 纪元毫秒 → yyyy-MM-dd HH:mm */
    private static String stamp(long at) {
        if (at <= 0) {
            return "时间未知";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(at));
    }

    /** 物品显示名（去色），没有自定义名时退回原版材质名 */
    private static String itemNameOf(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.displayName() == null) {
            return item.getType().name();
        }
        String plain = Msg.plain(meta.displayName()).trim();
        return plain.isEmpty() ? item.getType().name() : plain;
    }

    /** 物品上刻的锻造者（PDC forger），没有就返回 null */
    private static String forgerOf(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer()
                .get(com.forgemaster.util.Keys.FORGER, org.bukkit.persistence.PersistentDataType.STRING);
    }
}
