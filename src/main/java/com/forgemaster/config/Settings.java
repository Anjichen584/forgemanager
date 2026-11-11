package com.forgemaster.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局玩法参数（config.yml 的 settings 段）。
 */
public final class Settings {

    private int idleTimeoutSeconds = 90;
    private double heatDecayPerTick = 0.055;
    private double bellowsGain = 8.0;
    private int bellowsCooldownTicks = 4;
    private int hammerIntervalTicks = 3;
    private boolean broadcastLegendary = true;
    private boolean anvilShortcut = true;
    private boolean consumeCost = true;
    private boolean particles = true;
    private boolean sounds = true;
    private boolean anvilAnimation = true;
    private boolean spawnTempAnvil = true;
    private int anvilSearchRadius = 4;
    private boolean foldingEnabled = true;
    private boolean grindingEnabled = true;
    private double foldBaseSeconds = 2.5;
    private double foldSecondsPerStep = 0.7;
    private int grindDurationTicks = 120;
    private double grindPressStep = 6.0;
    // ---- v2.19 收尾双工序 ----
    private boolean polishingEnabled = true;
    private int polishPasses = 3;
    private double polishSpeedBase = 2.6;
    private double polishWindowBase = 16.0;
    private boolean inlayEnabled = true;
    private int inlayWindowTicks = 14;
    private int inlayLengthBase = 3;
    private boolean prologueEnabled = true;
    private List<String> weaponNames = new ArrayList<>();

    // v1.2
    private boolean commissionEnabled = true;
    private int commissionSlots = 3;
    private int commissionExpireHours = 6;
    private List<String> commissionClients = new ArrayList<>();
    private boolean durabilityEnabled = true;
    private int durabilityLossPerHit = 1;
    /** 耐久 Lore 显示档宽（百分比）；0 = 每刀实时刷新 */
    private int durabilityLoreStep = 5;
    /** 护甲虚拟耐久扣账节流窗口（毫秒）；0 = 不节流 */
    private int armorWearThrottleMs = 500;
    private int repairCostAmount = 2;
    private double repairZoneWidth = 7.0;
    private int repairDurationTicks = 80;
    private boolean duelEnabled = true;

    // ---- v1.3 ----
    private int manualMaxEntries = 200;
    private boolean contestEnabled = true;
    private int contestIntervalMinutes = 30;
    private int contestDurationSeconds = 300;
    private int contestMinPlayers = 1;

    public void load(FileConfiguration cfg) {
        this.cfg = cfg;
        idleTimeoutSeconds = Math.max(20, cfg.getInt("settings.idle-timeout-seconds", 90));
        heatDecayPerTick = Math.max(0.0, cfg.getDouble("settings.heat-decay-per-tick", 0.075));
        bellowsGain = Math.max(1.0, cfg.getDouble("settings.bellows-gain", 8.0));
        bellowsCooldownTicks = Math.max(1, cfg.getInt("settings.bellows-cooldown-ticks", 4));
        hammerIntervalTicks = Math.max(1, cfg.getInt("settings.hammer-interval-ticks", 2));
        broadcastLegendary = cfg.getBoolean("settings.broadcast-legendary", true);
        anvilShortcut = cfg.getBoolean("settings.anvil-shortcut", true);
        consumeCost = cfg.getBoolean("settings.consume-cost", true);
        particles = cfg.getBoolean("settings.particles", true);
        sounds = cfg.getBoolean("settings.sounds", true);
        anvilAnimation = cfg.getBoolean("settings.anvil-animation", true);
        spawnTempAnvil = cfg.getBoolean("settings.spawn-temp-anvil", true);
        anvilSearchRadius = Math.max(0, cfg.getInt("settings.anvil-search-radius", 4));
        foldingEnabled = cfg.getBoolean("settings.folding-enabled", true);
        grindingEnabled = cfg.getBoolean("settings.grinding-enabled", true);
        // v2.17.3：改 getDouble——原来 getInt 会把配置里的 2.5 截成 2，与字面、兜底三方不一致
        foldBaseSeconds = Math.max(0.5, cfg.getDouble("settings.folding-base-seconds", 2.5));
        foldSecondsPerStep = Math.max(0.2, cfg.getDouble("settings.folding-seconds-per-step", 0.7));
        grindDurationTicks = Math.max(20, cfg.getInt("settings.grind-duration-ticks", 120));
        grindPressStep = Math.max(1.0, cfg.getDouble("settings.grind-press-step", 6.0));
        // v2.19：收尾双工序（抛光 / 嵌银丝），各自可单独关掉
        polishingEnabled = cfg.getBoolean("settings.polishing-enabled", true);
        polishPasses = Math.max(1, Math.min(6, cfg.getInt("settings.polishing-passes", 3)));
        polishSpeedBase = Math.max(0.6, Math.min(8.0, cfg.getDouble("settings.polishing-speed", 2.6)));
        polishWindowBase = Math.max(4.0, Math.min(40.0, cfg.getDouble("settings.polishing-window", 16.0)));
        inlayEnabled = cfg.getBoolean("settings.inlay-enabled", true);
        inlayWindowTicks = Math.max(5, Math.min(40, cfg.getInt("settings.inlay-window-ticks", 14)));
        inlayLengthBase = Math.max(2, Math.min(8, cfg.getInt("settings.inlay-length", 3)));
        prologueEnabled = cfg.getBoolean("settings.progression-enabled", true);

        commissionEnabled = cfg.getBoolean("commissions.enabled", true);
        commissionSlots = Math.max(1, Math.min(3, cfg.getInt("commissions.slots", 3)));
        commissionExpireHours = Math.max(1, cfg.getInt("commissions.expire-hours", 6));
        commissionClients = cfg.getStringList("commissions.clients");
        if (commissionClients.isEmpty()) {
            commissionClients = new ArrayList<>(List.of("铁匠铺老板", "城防军队长", "游侠", "矿工头子", "拍卖行管事"));
        }
        durabilityEnabled = cfg.getBoolean("settings.durability-enabled", true);
        durabilityLossPerHit = Math.max(0, cfg.getInt("settings.durability-loss-per-hit", 1));
        durabilityLoreStep = Math.max(0, Math.min(50, cfg.getInt("settings.durability-lore-step", 5)));
        armorWearThrottleMs = Math.max(0, Math.min(5000, cfg.getInt("settings.armor-wear-throttle-ms", 500)));
        repairCostAmount = Math.max(0, cfg.getInt("settings.repair-cost-amount", 2));
        repairZoneWidth = Math.max(2.0, cfg.getDouble("settings.repair-zone-width", 7.0));
        repairDurationTicks = Math.max(20, cfg.getInt("settings.repair-duration-ticks", 80));
        duelEnabled = cfg.getBoolean("settings.duel-enabled", true);

        manualMaxEntries = Math.max(1, cfg.getInt("settings.manual-max-entries", 200));
        contestEnabled = cfg.getBoolean("contest.enabled", true);
        contestIntervalMinutes = Math.max(1, cfg.getInt("contest.interval-minutes", 30));
        contestDurationSeconds = Math.max(30, cfg.getInt("contest.duration-seconds", 300));
        contestMinPlayers = Math.max(1, cfg.getInt("contest.min-players", 1));
        weaponNames = new ArrayList<>(cfg.getStringList("settings.weapon-names"));
        if (weaponNames.isEmpty()) {
            weaponNames = new ArrayList<>(List.of("无名之刃", "试炼之剑", "铸魂", "断钢"));
        }
    }

    public int idleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    public double heatDecayPerTick() {
        return heatDecayPerTick;
    }

    public double bellowsGain() {
        return bellowsGain;
    }

    public int bellowsCooldownTicks() {
        return bellowsCooldownTicks;
    }

    public int hammerIntervalTicks() {
        return hammerIntervalTicks;
    }

    public boolean broadcastLegendary() {
        return broadcastLegendary;
    }

    public boolean anvilShortcut() {
        return anvilShortcut;
    }

    public boolean consumeCost() {
        return consumeCost;
    }

    public boolean particles() {
        return particles;
    }

    public boolean sounds() {
        return sounds;
    }

    public boolean anvilAnimation() {
        return anvilAnimation;
    }

    public boolean spawnTempAnvil() {
        return spawnTempAnvil;
    }

    public int anvilSearchRadius() {
        return anvilSearchRadius;
    }

    public boolean foldingEnabled() {
        return foldingEnabled;
    }

    public boolean grindingEnabled() {
        return grindingEnabled;
    }

    public double foldBaseSeconds() {
        return foldBaseSeconds;
    }

    public double foldSecondsPerStep() {
        return foldSecondsPerStep;
    }

    public int grindDurationTicks() {
        return grindDurationTicks;
    }

    public double grindPressStep() {
        return grindPressStep;
    }

    // ---- v2.19 抛光 / 嵌银丝 ----

    public boolean polishingEnabled() {
        return polishingEnabled;
    }

    public int polishPasses() {
        return polishPasses;
    }

    public double polishSpeedBase() {
        return polishSpeedBase;
    }

    public double polishWindowBase() {
        return polishWindowBase;
    }

    public boolean inlayEnabled() {
        return inlayEnabled;
    }

    public int inlayWindowTicks() {
        return inlayWindowTicks;
    }

    public int inlayLengthBase() {
        return inlayLengthBase;
    }

    public boolean progressionEnabled() {
        return prologueEnabled;
    }

    public boolean commissionEnabled() {
        return commissionEnabled;
    }

    public int commissionSlots() {
        return commissionSlots;
    }

    public int commissionExpireHours() {
        return commissionExpireHours;
    }

    public List<String> commissionClients() {
        return commissionClients;
    }

    public boolean durabilityEnabled() {
        return durabilityEnabled;
    }

    public int durabilityLossPerHit() {
        return durabilityLossPerHit;
    }

    /** 耐久 Lore 显示档宽（百分比，默认 5）；0 = 每刀实时刷新 Lore 上的数字 */
    public int durabilityLoreStep() {
        return durabilityLoreStep;
    }

    /** 护甲虚拟耐久扣账节流窗口（毫秒，默认 500）；0 = 不节流，每次受伤都扣 */
    public int armorWearThrottleMs() {
        return armorWearThrottleMs;
    }

    public int repairCostAmount() {
        return repairCostAmount;
    }

    public double repairZoneWidth() {
        return repairZoneWidth;
    }

    public int repairDurationTicks() {
        return repairDurationTicks;
    }

    public boolean duelEnabled() {
        return duelEnabled;
    }

    public int manualMaxEntries() {
        return manualMaxEntries;
    }

    public boolean contestEnabled() {
        return contestEnabled;
    }

    public int contestIntervalMinutes() {
        return contestIntervalMinutes;
    }

    public int contestDurationSeconds() {
        return contestDurationSeconds;
    }

    public int contestMinPlayers() {
        return contestMinPlayers;
    }

    // ------------------------------------------------------------ v2.0 通用访问器
    // 各扩展模块用自己的配置路径直接取值，不必再改本文件。

    private ConfigurationSection cfg;

    /** 读 double，缺省返回 def */
    public double num(String path, double def) {
        return cfg == null ? def : cfg.getDouble(path, def);
    }

    /** 读 int，缺省返回 def */
    public int intOf(String path, int def) {
        return cfg == null ? def : cfg.getInt(path, def);
    }

    /** 读 long，缺省返回 def */
    public long longOf(String path, long def) {
        return cfg == null ? def : cfg.getLong(path, def);
    }

    /** 读 boolean，缺省返回 def */
    public boolean flag(String path, boolean def) {
        return cfg == null ? def : cfg.getBoolean(path, def);
    }

    /** 读字符串，缺省返回 def */
    public String str(String path, String def) {
        return cfg == null ? def : cfg.getString(path, def);
    }

    /** 读字符串列表 */
    public java.util.List<String> strList(String path, java.util.List<String> def) {
        if (cfg == null) {
            return def;
        }
        java.util.List<String> got = cfg.getStringList(path);
        return got.isEmpty() ? def : got;
    }

    /** 读 double 列表（估值曲线这类有序数值表）；缺省返回 def */
    public java.util.List<Double> numList(String path, java.util.List<Double> def) {
        if (cfg == null) {
            return def;
        }
        java.util.List<Double> got = cfg.getDoubleList(path);
        return got.isEmpty() ? def : got;
    }

    /** 读子段（可能为 null） */
    public ConfigurationSection section(String path) {
        return cfg == null ? null : cfg.getConfigurationSection(path);
    }

    public List<String> weaponNames() {
        return weaponNames;
    }
}