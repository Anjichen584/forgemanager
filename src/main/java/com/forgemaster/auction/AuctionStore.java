package com.forgemaster.auction;

import com.forgemaster.ForgingMasterPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 拍卖行持久化（auction.yml）：
 * listings = 在售拍品；mail = 待领取物品（成交的货 / 流拍退回 / 下架退回）。
 * 所有物品归属都先落盘再生效，重启不丢单。
 *
 * <p>落盘策略：变更只翻 dirty 标记，由低频异步任务（5 秒一轮）冲刷写盘，
 * 主线程动作路径不再做同步 IO；插件关闭 / 回档前走 {@link #stop()} / {@link #save()}
 * 强制冲刷。异步窗口（≤5 秒）内崩溃可能漏掉最后一笔挂单 / 邮箱变更，属审计
 * #3 接受的窗口（与旧「先落盘后动钱」的顺序注释一致，兜底靠人工核对）。</p>
 */
public final class AuctionStore {

    private final ForgingMasterPlugin plugin;
    private final File file;
    private final Map<String, AuctionListing> listings = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> mail = new LinkedHashMap<>();
    /** 有未落盘的变更时为真；异步冲刷任务只在它为真时才写文件 */
    private volatile boolean dirty;
    private int flushTaskId = -1;

    public AuctionStore(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "auction.yml");
    }

    public synchronized void load() {
        listings.clear();
        mail.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        // Bukkit 对解析失败的 yml 只告警并当空文件处理：一条 item 串反序列化失败
        // 就会把整个拍卖行静默清空。先把原文件改名保留，再当空的来。
        if (yml.getConfigurationSection("listings") == null
                && yml.getConfigurationSection("mail") == null
                && file.length() > 0) {
            File broken = new File(file.getParentFile(),
                    "auction.yml.broken-" + System.currentTimeMillis());
            if (file.renameTo(broken)) {
                plugin.getLogger().severe("auction.yml 无法解析，已改名保留为 " + broken.getName()
                        + "，挂单与邮箱未加载（请人工检查恢复）");
            }
            return;
        }
        ConfigurationSection root = yml.getConfigurationSection("listings");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection sec = root.getConfigurationSection(id);
                if (sec == null) {
                    continue;
                }
                try {
                    if (!(sec.get("item") instanceof ItemStack item)) {
                        continue;
                    }
                    AuctionListing l = new AuctionListing(id,
                            UUID.fromString(sec.getString("seller", "")),
                            sec.getString("seller-name", "?"),
                            item,
                            sec.getLong("start-price", 1),
                            sec.getLong("buyout", 0),
                            sec.getLong("price", 1),
                            sec.getLong("created-at", 0),
                            sec.getLong("ends-at", 0));
                    String bidder = sec.getString("bidder", "");
                    if (!bidder.isEmpty()) {
                        l.setBidder(UUID.fromString(bidder), sec.getString("bidder-name", "?"));
                    }
                    l.bids(sec.getInt("bids", 0));
                    listings.put(id, l);
                } catch (Exception ex) {
                    plugin.getLogger().warning("拍卖数据损坏，已跳过 " + id + ": " + ex.getMessage());
                }
            }
        }
        ConfigurationSection mailRoot = yml.getConfigurationSection("mail");
        if (mailRoot != null) {
            for (String key : mailRoot.getKeys(false)) {
                try {
                    List<ItemStack> items = new ArrayList<>();
                    for (Object o : yml.getList("mail." + key, List.of())) {
                        if (o instanceof ItemStack it) {
                            items.add(it);
                        }
                    }
                    if (!items.isEmpty()) {
                        mail.put(UUID.fromString(key), items);
                    }
                } catch (Exception ex) {
                    plugin.getLogger().warning("拍卖邮箱数据损坏，已跳过 " + key);
                }
            }
        }
        plugin.getLogger().info("拍卖行已载入 " + listings.size() + " 件拍品，" + mail.size() + " 个待领邮箱");
        // 磁盘即真相：重载后内存与磁盘一致，任何残留脏标记都作废
        dirty = false;
    }

    /** 启动低频异步冲刷：仅 dirty 时才真正写文件（5 秒一轮），主线程动作路径零同步 IO */
    public void startAutoFlush() {
        if (flushTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(flushTaskId);
        }
        flushTaskId = plugin.getServer().getScheduler()
                .runTaskTimerAsynchronously(plugin, this::flushIfDirty, 100L, 100L).getTaskId();
    }

    /** 关服冲刷：停掉异步任务并强制落一次盘 */
    public void stop() {
        if (flushTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(flushTaskId);
            flushTaskId = -1;
        }
        save();
    }

    private void flushIfDirty() {
        if (dirty) {
            save();
        }
    }

    /** 标记有未落盘的变更；真正的写盘交给低频异步冲刷 */
    public void markDirty() {
        dirty = true;
    }

    /** 自检 / 运维观测用：当前是否有未落盘变更 */
    public boolean dirty() {
        return dirty;
    }

    /**
     * 同步写盘。正常变更路径不再直调它（改走 markDirty + 异步冲刷），
     * 只剩关服冲刷与回档前快照（flushStore）强制调用。
     */
    public synchronized void save() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("无法创建插件数据目录");
            return;
        }
        YamlConfiguration yml = new YamlConfiguration();
        for (AuctionListing l : listings.values()) {
            String base = "listings." + l.id();
            yml.set(base + ".seller", l.seller().toString());
            yml.set(base + ".seller-name", l.sellerName());
            yml.set(base + ".item", l.item());
            yml.set(base + ".start-price", l.startPrice());
            yml.set(base + ".buyout", l.buyout());
            yml.set(base + ".price", l.price());
            yml.set(base + ".created-at", l.createdAt());
            yml.set(base + ".ends-at", l.endsAt());
            yml.set(base + ".bidder", l.hasBidder() ? l.bidder().toString() : "");
            yml.set(base + ".bidder-name", l.bidderName());
            yml.set(base + ".bids", l.bids());
        }
        for (Map.Entry<UUID, List<ItemStack>> e : mail.entrySet()) {
            if (!e.getValue().isEmpty()) {
                yml.set("mail." + e.getKey(), new ArrayList<>(e.getValue()));
            }
        }
        try {
            yml.save(file);
            // 写盘与全部变更共用 this 锁：期间不可能插进新变更，这里清脏是安全的
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().warning("保存 auction.yml 失败: " + ex.getMessage());
        }
    }

    /**
     * 原子操作：移除挂单 + 把物品写入 owner 邮箱（归属先于动钱变更），标脏交异步冲刷。
     *
     * @return 这张单子确实由本次调用移除才 true；已被别的路径结掉（竞态）返回 false，
     *         调用方据此把钱退回去，避免「没拿到东西还被扣款」
     */
    public synchronized boolean finish(String id, UUID owner, ItemStack item) {
        if (listings.remove(id) == null) {
            return false;
        }
        mail.computeIfAbsent(owner, k -> new ArrayList<>()).add(item);
        markDirty();
        return true;
    }

    public synchronized void put(AuctionListing l) {
        listings.put(l.id(), l);
        markDirty();
    }

    public synchronized void remove(String id) {
        listings.remove(id);
        markDirty();
    }

    public synchronized AuctionListing get(String id) {
        return listings.get(id);
    }

    public synchronized List<AuctionListing> all() {
        return new ArrayList<>(listings.values());
    }

    public synchronized void addMail(UUID owner, ItemStack item) {
        mail.computeIfAbsent(owner, k -> new ArrayList<>()).add(item);
        markDirty();
    }

    public synchronized List<ItemStack> mailOf(UUID owner) {
        List<ItemStack> list = mail.get(owner);
        return list == null ? List.of() : new ArrayList<>(list);
    }

    /** 取出并清空该玩家的邮箱 */
    public synchronized List<ItemStack> takeMail(UUID owner) {
        List<ItemStack> list = mail.remove(owner);
        markDirty();
        return list == null ? new ArrayList<>() : list;
    }

    /**
     * v2.23.1 修 P0：把玩家邮箱<b>整体替换</b>成 {@code remain}（空表 = 删掉该玩家段）。
     *
     * <p>原来会怎样：领取走 {@link #takeMail} 先把内存邮箱清空、再把物品塞背包，
     * 而清空只标脏不落盘（5 秒异步冲刷）。背包塞完之后崩溃 → 磁盘邮箱仍在 →
     * 重启后能再领一次，物品凭空多一份。</p>
     *
     * <p>现在为什么不会：领取改走本方法「塞不进的部分原样留在邮箱」，由调用方
     * 紧随其后同步 {@link #save()}。磁盘上要么是「还没领」（物品没进背包），
     * 要么是「已经领完、只剩塞不下的那几件」，不存在两边都算一份的中间态。</p>
     */
    public synchronized void replaceMail(UUID owner, List<ItemStack> remain) {
        if (remain == null || remain.isEmpty()) {
            mail.remove(owner);
        } else {
            mail.put(owner, new ArrayList<>(remain));
        }
        markDirty();
    }
}
