package com.forgemaster.blackmarket;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.econ.CoinReason;
import com.forgemaster.econ.EconomyBridge;
import com.forgemaster.util.Msg;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 每日签到（轻量留存拓展）：每天首次 {@code /zhujian signin} 领签到奖。
 *
 * <p>周期与递进（断签不清零）：奖励表 {@code daily.rewards} 全 config 驱动（默认 7 项，
 * 最后一项是大奖），周期长度 = 奖励项数。玩家只记「本轮内已签天数 day」与
 * 「上次签到日期 last」；当天已签直接拒绝；隔了几天没签<b>不清零也不前进</b>——
 * 下一次签到拿的还是「day + 1」那一档，只有把本轮最后一档领完，下一次才回到第 1 档
 * 开新的一轮。所以「连续签到」其实按本轮累计天数计，跨天未领不亏也不送。</p>
 *
 * <p>日期口径与黑市一致：服务器本地时区的现实日（{@link LocalDate#now()}），
 * 理由见 {@link BlackmarketService} 类注释（MC 昼夜只有 20 分钟，不能用 dayTime）。</p>
 *
 * <p>存储用包内共享的 {@link DailyStore}（daily-data.yml 的 {@code signin} 段）——
 * 两个拓展同绕「现实日」转，共用一份日期态文件与读写样板；奖励发放走
 * {@link EconomyBridge}（剑币）+ {@code Charm.create}（符），与通行证同款数据格式。</p>
 */
public final class DailyRewardService {

    /** 单日奖励（全 config 驱动，格式同 season-pass.levels[].reward） */
    public record Reward(long coins, String materialId, int materialAmount,
                         String charmId, int charmAmount) {

        public boolean isEmpty() {
            return coins <= 0 && materialId == null && charmId == null;
        }
    }

    private final ForgingMasterPlugin plugin;
    private final DailyStore store;
    private final List<Reward> rewards = new ArrayList<>();

    public DailyRewardService(ForgingMasterPlugin plugin, DailyStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    // ------------------------------------------------------------ 载入

    /** 启动 / reload 时重建奖励表（配置现读会太散，这里集中解析一次） */
    public void load() {
        rewards.clear();
        for (Map<?, ?> raw : plugin.getConfig().getMapList("daily.rewards")) {
            long coins = raw.get("coins") instanceof Number n ? n.longValue() : 0;
            String matId = raw.get("material") instanceof String s && !s.isBlank() ? s : null;
            int matAmt = raw.get("material-amount") instanceof Number n ? n.intValue() : 1;
            String charmId = raw.get("charm") instanceof String s && !s.isBlank() ? s : null;
            int charmAmt = raw.get("charm-amount") instanceof Number n ? n.intValue() : 1;
            rewards.add(new Reward(Math.max(0, coins), matId, Math.max(1, matAmt),
                    charmId, Math.max(1, charmAmt)));
        }
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("daily.enabled", true) && !rewards.isEmpty();
    }

    /** 奖励表（第 n 项 = 本轮第 n 天） */
    public List<Reward> rewards() {
        return new ArrayList<>(rewards);
    }

    // ------------------------------------------------------------ 查询

    /** 玩家本轮内已签天数（0 = 一轮还没开张） */
    public int dayOf(UUID uuid) {
        return store.signinOf(uuid).day;
    }

    /** 上次签到日期（null = 从没签过） */
    public String lastSign(UUID uuid) {
        return store.signinOf(uuid).last;
    }

    /** 今天签过没有 */
    public boolean signedToday(UUID uuid) {
        String last = lastSign(uuid);
        return last != null && last.equals(LocalDate.now().toString());
    }

    /** 今天来签能拿第几档（只读预览，1 起计；没奖励可拿返回 0） */
    public int nextDay(UUID uuid) {
        if (rewards.isEmpty()) {
            return 0;
        }
        int day = dayOf(uuid);
        return day >= rewards.size() ? 1 : day + 1;
    }

    // ------------------------------------------------------------ 签到

    /**
     * 签到领奖：当天已签拒绝；否则按 {@link #nextDay} 发奖并推进 day。
     * 剑币走 EconomyBridge，符走 Charm.create，材料发不下的掉地上。
     */
    public void sign(Player p) {
        if (rewards.isEmpty()) {
            Msg.prefix(p, "<red>签到奖励没配置（daily.rewards）。</red>");
            return;
        }
        UUID uuid = p.getUniqueId();
        String today = LocalDate.now().toString();
        if (signedToday(uuid)) {
            Msg.prefix(p, "<gray>今天已经签过了（本轮第 <white>" + dayOf(uuid) + "</white> 天）。"
                    + "</gray><dark_gray>明天再来。</dark_gray>");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
            return;
        }
        int day = nextDay(uuid);
        Reward r = rewards.get(day - 1);

        var wallet = plugin.wallet();
        List<String> got = new ArrayList<>();
        if (r.coins() > 0 && wallet.give(p, r.coins(), CoinReason.SIGNIN)) {
            got.add("剑币 <#ffd166>+" + wallet.format(r.coins()) + "</#ffd166>");
        }
        if (r.materialId() != null) {
            ItemStack item = materialItem(r.materialId(), r.materialAmount());
            if (item != null) {
                for (ItemStack rest : p.getInventory().addItem(item).values()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), rest);
                }
                got.add("<#7ef2c8>" + r.materialAmount() + " × " + materialLabel(r.materialId()) + "</#7ef2c8>");
            }
        }
        if (r.charmId() != null) {
            String norm = com.forgemaster.strengthen.Charm.normalize(r.charmId());
            ItemStack charm = com.forgemaster.strengthen.Charm.create(plugin,
                    norm == null ? com.forgemaster.strengthen.Charm.TALISMAN : norm);
            charm.setAmount(r.charmAmount());
            for (ItemStack rest : p.getInventory().addItem(charm).values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), rest);
            }
            got.add("<#b388ff>" + r.charmAmount() + " × "
                    + com.forgemaster.strengthen.Charm.plainName(r.charmId()) + "</#b388ff>");
        }

        store.setSignin(uuid, day, today);
        store.save();

        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.2f);
        boolean grand = day == rewards.size();
        Msg.prefix(p, "<gradient:#ffd166:#ff7a18><bold>每日签到</bold></gradient> <gray>本轮第 <white>"
                + day + " / " + rewards.size() + "</white> 天"
                + (grand ? " <#ffd166><bold>· 大奖！</bold></#ffd166>" : "")
                + (got.isEmpty() ? "<dark_gray>（这档没配奖励）</dark_gray>"
                : " <white>" + String.join("　", got) + "</white>")
                + "<gray>。</gray><dark_gray>断签不清零，隔天来签接着领下一档。</dark_gray>");
    }

    // ------------------------------------------------------------ 奖励物

    private ItemStack materialItem(String materialId, int amount) {
        var reg = plugin.materials();
        var fm = reg == null ? null : reg.get(materialId);
        Material m = fm != null ? fm.item() : Material.matchMaterial(materialId == null ? "" : materialId);
        return m == null ? null : new ItemStack(m, Math.max(1, amount));
    }

    /** 材料奖励的展示名（与通行证 materialLabel 同款） */
    private String materialLabel(String materialId) {
        var reg = plugin.materials();
        var fm = reg == null ? null : reg.get(materialId);
        if (fm != null) {
            return Msg.plain(Msg.mm(fm.display()));
        }
        Material m = Material.matchMaterial(materialId == null ? "" : materialId);
        return m == null ? materialId : com.forgemaster.material.MaterialGrades.pretty(m);
    }
}
