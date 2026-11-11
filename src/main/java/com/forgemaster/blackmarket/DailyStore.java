package com.forgemaster.blackmarket;

import com.forgemaster.ForgingMasterPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 黑市与每日签到共用的日期态存储（daily-data.yml）。
 *
 * <p>两个轻量留存拓展都围绕「现实日」转，落同一份文件省一次 IO 与两套读写样板：</p>
 * <pre>
 * market:                 # uuid → 日期 → 商品键 → 已购次数（每天清一层）
 *   &lt;uuid&gt;:
 *     "2026-11-10":
 *       "charm:talisman": 1
 * signin:                 # uuid → {day, last}
 *   &lt;uuid&gt;:
 *     day: 3              # 本轮内已签天数（1 起计；满一轮后回到 1）
 *     last: "2026-11-10"  # 上次签到的现实日（ISO，本地时区）
 * </pre>
 *
 * <p>整份重建落盘（StoryManager / SeasonPassService 风格）；保存时顺手把 market
 * 里非当天的日期层剪掉——限购只对今天有意义，历史层留着只会把文件越养越大。
 * 签到记录永不清理（断签不清零依赖它）。</p>
 */
public final class DailyStore {

    /** 一个玩家的签到进度 */
    public static final class SigninRecord {
        /** 本轮内已签到的天数（0 = 一轮还没开过张） */
        public int day;
        /** 上次签到的日期（ISO 字符串，null = 从没签过） */
        public String last;
    }

    private final ForgingMasterPlugin plugin;
    private final File file;
    /** uuid → 日期 → 商品键 → 已购次数 */
    private final Map<UUID, Map<String, Map<String, Integer>>> market = new HashMap<>();
    private final Map<UUID, SigninRecord> signin = new HashMap<>();

    public DailyStore(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "daily-data.yml");
    }

    // ------------------------------------------------------------ 载入 / 保存

    public void load() {
        market.clear();
        signin.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection mk = yml.getConfigurationSection("market");
        if (mk != null) {
            for (String uuidKey : mk.getKeys(false)) {
                ConfigurationSection dates = mk.getConfigurationSection(uuidKey);
                if (dates == null) {
                    continue;
                }
                try {
                    UUID uuid = UUID.fromString(uuidKey);
                    Map<String, Map<String, Integer>> byDate = new HashMap<>();
                    for (String date : dates.getKeys(false)) {
                        ConfigurationSection keys = dates.getConfigurationSection(date);
                        if (keys == null) {
                            continue;
                        }
                        Map<String, Integer> counts = new HashMap<>();
                        for (String key : keys.getKeys(false)) {
                            counts.put(key, Math.max(0, keys.getInt(key, 0)));
                        }
                        byDate.put(date, counts);
                    }
                    market.put(uuid, byDate);
                } catch (IllegalArgumentException ignored) {
                    // 坏键跳过，不影响启动
                }
            }
        }
        ConfigurationSection sg = yml.getConfigurationSection("signin");
        if (sg != null) {
            for (String uuidKey : sg.getKeys(false)) {
                ConfigurationSection sec = sg.getConfigurationSection(uuidKey);
                if (sec == null) {
                    continue;
                }
                try {
                    UUID uuid = UUID.fromString(uuidKey);
                    SigninRecord rec = new SigninRecord();
                    rec.day = Math.max(0, sec.getInt("day", 0));
                    rec.last = sec.getString("last", null);
                    signin.put(uuid, rec);
                } catch (IllegalArgumentException ignored) {
                    // 坏键跳过
                }
            }
        }
        plugin.getLogger().info("已载入黑市/签到数据：" + market.size() + " 份当日限购 / "
                + signin.size() + " 份签到进度");
    }

    /** 整份重建落盘；market 里非当天的日期层一并剪掉 */
    public void save() {
        String today = LocalDate.now().toString();
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, Map<String, Integer>>> e : market.entrySet()) {
            Map<String, Map<String, Integer>> byDate = e.getValue();
            byDate.keySet().removeIf(d -> !today.equals(d));
            for (Map.Entry<String, Map<String, Integer>> d : byDate.entrySet()) {
                for (Map.Entry<String, Integer> k : d.getValue().entrySet()) {
                    yml.set("market." + e.getKey() + "." + d.getKey() + "." + k.getKey(), k.getValue());
                }
            }
        }
        for (Map.Entry<UUID, SigninRecord> e : signin.entrySet()) {
            String base = "signin." + e.getKey();
            yml.set(base + ".day", e.getValue().day);
            if (e.getValue().last != null) {
                yml.set(base + ".last", e.getValue().last);
            }
        }
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("黑市/签到存档保存失败：" + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ 黑市限购

    /** 某玩家某天某商品已买了几个 */
    public int purchaseCount(UUID uuid, String date, String offerKey) {
        Map<String, Map<String, Integer>> byDate = market.get(uuid);
        if (byDate == null) {
            return 0;
        }
        Map<String, Integer> counts = byDate.get(date);
        return counts == null ? 0 : counts.getOrDefault(offerKey, 0);
    }

    /** 记一次购买（内存态；调用方负责 save()） */
    public void recordPurchase(UUID uuid, String date, String offerKey) {
        market.computeIfAbsent(uuid, k -> new HashMap<>())
                .computeIfAbsent(date, k -> new HashMap<>())
                .merge(offerKey, 1, Integer::sum);
    }

    // ------------------------------------------------------------ 每日签到

    /** 签到进度（没有记录就给一张白纸，不写进 map） */
    public SigninRecord signinOf(UUID uuid) {
        SigninRecord rec = signin.get(uuid);
        if (rec != null) {
            return rec;
        }
        return new SigninRecord();
    }

    /** 写签到进度（内存态；调用方负责 save()） */
    public void setSignin(UUID uuid, int day, String date) {
        SigninRecord rec = signin.computeIfAbsent(uuid, k -> new SigninRecord());
        rec.day = Math.max(0, day);
        rec.last = date;
    }

    /** 黑市 GUI 点击路由要用到当天日期的便捷方法（本地时区现实日） */
    public static String today() {
        return LocalDate.now().toString();
    }

    /** 便于调试 / 以后扩展：列出某玩家记录过的日期 */
    public List<String> marketDates(UUID uuid) {
        Map<String, Map<String, Integer>> byDate = market.get(uuid);
        return byDate == null ? new ArrayList<>() : new ArrayList<>(byDate.keySet());
    }
}
