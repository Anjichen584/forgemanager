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

        // v2.25.2（4 并发查错）：先记账、再发奖。原来发完奖才 setSignin ——
        // 发奖中途抛异常（背包同步、Charm.create、外部经济回调）或此刻崩服，
        // 就是「奖已到手、签到没记上」，下次进来还能再领一次（重复发币）。
        // 记账放在前面，发奖若抛异常就回滚记录：宁可让玩家重来一次，也不重复发。
        int prevDay = dayOf(uuid);
        String prevLast = lastSign(uuid);
        store.setSignin(uuid, day, today);
        store.save();
        var wallet = plugin.wallet();
        List<String> got = new ArrayList<>();
        try {
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
                // v2.25.2（审计轮4）：别直接 setAmount(charmAmount)。
                // 配置写超过该符的堆叠上限（符是 max-stack=1 的实体物品，配 2 就抛
                // IllegalArgumentException）时整份奖励发不出去；即便有回滚，玩家也被无故挡一次。
                // 照 SeasonPassService 的口径按上限拆叠，一叠都不少。
                int preMax = Math.max(1, charm.getMaxStackSize());
                int remain = Math.max(1, r.charmAmount());
                while (remain > 0) {
                    int n = Math.min(remain, preMax);
                    ItemStack one = charm.clone();
                    one.setAmount(n);
                    for (ItemStack rest : p.getInventory().addItem(one).values()) {
                        p.getWorld().dropItemNaturally(p.getLocation(), rest);
                    }
                    remain -= n;
                }
                got.add("<#b388ff>" + r.charmAmount() + " × "
                        + com.forgemaster.strengthen.Charm.plainName(r.charmId()) + "</#b388ff>");
            }
        } catch (RuntimeException ex) {
            store.setSignin(uuid, prevDay, prevLast);
            store.save();
            plugin.getLogger().warning("签到发奖失败，已回滚签到进度：" + ex);
            Msg.prefix(p, "<red>签到发奖出错，进度已回滚，请再试一次（管理员看控制台）。</red>");
            return;
        }

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
