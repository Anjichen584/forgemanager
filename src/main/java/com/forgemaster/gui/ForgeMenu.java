package com.forgemaster.gui;

import com.forgemaster.affix.Affix;
import com.forgemaster.forge.ForgeSession;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.quality.Quality;
import com.forgemaster.quench.Quench;
import com.forgemaster.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 所有界面的渲染。纯展示层，不包含游戏逻辑。
 */
public final class ForgeMenu {

    public static final int MENU_SIZE = 27;
    public static final int FORGE_SIZE = 27;

    /** 敲打轨道起始槽位，共 9 格 */
    public static final int TRACK_START = 9;
    public static final int TRACK_LEN = 9;
    public static final int STRIKE_SLOT = 22;
    public static final int QUIT_SLOT = 18;

    /** 淬火介质摆放槽位（与 QuenchRegistry 顺序一致） */
    public static final int[] QUENCH_SLOTS = {10, 11, 12, 14, 15, 16, 13};

    /** 开刃界面的加压 / 减压按钮（必须避开 9~17 的轨道） */
    public static final int PRESS_UP_SLOT = 0;
    public static final int PRESS_DOWN_SLOT = 8;

    /** 主菜单材料槽位 */
    public static final int[] MATERIAL_SLOTS = {10, 11, 12, 13, 14, 15, 16};

    /**
     * 主菜单「词条重铸」入口槽位。
     *
     * <p>主菜单 27 格里，4（标题）/ 11（锻造武器）/ 15（锻造装备）/ 18（委托板）/
     * 20（剑谱库）/ 22（图鉴）/ 24（大赛）/ 25（排行）/ 26（档案）都已被占用，
     * 13 是第 2 排正中间的空位，正好夹在「锻造武器」和「锻造装备」之间。</p>
     */
    public static final int REFORGE_SLOT = 13;

    private static final String FILLER_NAME = "<dark_gray>·</dark_gray>";

    /**
     * 固定文案的 MiniMessage 解析缓存（审计 #4）。
     *
     * <p>轨道差量更新后，高频路径每次重绘仍要重建 2~3 格 icon，其中的
     * 「命中区 / 指针 / 灰点」等文案全是常量串——每次 Msg.mm 全量解析纯属浪费。
     * Component 在 Adventure 里不可变，可以安全共享。缓存按「解析结果池化」
     * 而不是缓存 ItemStack：ItemStack 可变，共享有被改写放大的风险。</p>
     *
     * <p>动态文案（带数字的 lore 等）也会进缓存，用总量上限兜底防止无限增长；
     * 超限后不再收新条目，高频常量串早已在池里，不受影响。</p>
     */
    private static final Map<String, Component> TEXT_CACHE = new ConcurrentHashMap<>();
    private static final int TEXT_CACHE_LIMIT = 1024;

    /** 带斜体关闭的 MiniMessage 解析，常量串直接命中缓存 */
    private static Component cachedText(String raw) {
        Component cached = TEXT_CACHE.get(raw);
        if (cached != null) {
            return cached;
        }
        Component parsed = Msg.mm(raw).decoration(TextDecoration.ITALIC, false);
        if (TEXT_CACHE.size() < TEXT_CACHE_LIMIT) {
            TEXT_CACHE.put(raw, parsed);
        }
        return parsed;
    }

    /**
     * 每个锻造界面上一次渲染的轨道格状态（审计 #4 差量更新用）。
     *
     * <p>key 按 Inventory 弱引用持有，界面关闭随 GC 清掉；全部访问都在
     * 主线程（tick + 点击事件），WeakHashMap 的非线程安全在这里不构成问题。</p>
     */
    private static final Map<Inventory, int[]> TRACK_STATE = new WeakHashMap<>();
    /** 加热界面炉温格的上一次渲染签名，内容没变就跳过整格重建 */
    private static final Map<Inventory, String> TEMP_SIG = new WeakHashMap<>();

    private final Plugin plugin;
    private final com.forgemaster.config.Settings settings;

    public ForgeMenu(Plugin plugin, com.forgemaster.config.Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    // ---------------------------------------------------------------- 主菜单

    public Inventory buildMainMenu(Player player, List<ForgeMaterial> materials) {
        MainMenuHolder holder = new MainMenuHolder();
        Inventory inv = org.bukkit.Bukkit.createInventory(holder, MENU_SIZE,
                Msg.mm("<dark_gray>铸剑大师 <dark_gray>| <white>主界面"));
        holder.inventory(inv);

        for (int i = 0; i < MENU_SIZE; i++) {
            inv.setItem(i, filler());
        }

        inv.setItem(4, icon(Material.ANVIL,
                "<gradient:#ffd166:#ff7a18><bold>铸剑大师</bold></gradient>",
                List.of(
                        "<gray>把矿石烧红，敲出形状，再淬火定型。",
                        "<gray>三个阶段的操作都会影响最终品质。",
                        "",
                        "<dark_gray>加热 → 锻打 → 淬火"
                )));

        int weaponCount = materials.size();
        inv.setItem(11, icon(Material.IRON_SWORD, "<#7fd3ff><bold>锻造武器</bold>",
                List.of("<gray>剑胚 6 档，从铁剑到星陨剑。",
                        "<gray>扣耗材 + <#ffd166>锻造费</#ffd166>。",
                        "<dark_gray>当前 " + weaponCount + " 种剑胚",
                        "", "<yellow>▶ 左键进入")));
        inv.setItem(15, icon(Material.IRON_CHESTPLATE, "<#7ef2c8><bold>锻造装备</bold>",
                List.of("<gray>头盔 / 胸甲 / 护腿 / 靴子。",
                        "<gray>同样扣耗材与锻造费，吃防御词条。",
                        "", "<yellow>▶ 左键进入")));

        // 词条重铸入口：图标与点击监听的注册都在 ReforgeMenu 里，这边只放一个按钮
        inv.setItem(REFORGE_SLOT, ReforgeMenu.entryIcon());

        inv.setItem(22, icon(Material.BOOK, "<#7ef2c8>锻造图鉴",
                List.of("<gray>看自己锻过哪些剑胚 × 品质。",
                        "<gray>收集满了有彩蛋。", "", "<yellow>▶ 左键打开")));
        inv.setItem(26, icon(Material.WRITABLE_BOOK, "<#ffd166>锻造档案",
                List.of("<gray>次数、最高分、专精等级。",
                        "<dark_gray>手里拿着锻造品可以直接熔炼", "", "<yellow>▶ 左键查看")));
        inv.setItem(18, icon(Material.PAPER, "<#ff9a3c>委托板",
                List.of("<gray>客人挂单，按要求交货换声望。", "", "<yellow>▶ 左键打开")));
        inv.setItem(20, icon(Material.WRITTEN_BOOK, "<#a78bfa>剑谱库",
                List.of("<gray>别人锻出的名剑配方，可以照着临摹。",
                        "<dark_gray>主手拿成品 → /zhujian record 登记", "", "<yellow>▶ 左键打开")));
        inv.setItem(24, icon(Material.GOLDEN_SWORD, "<#ffd166>锻造大赛",
                List.of("<gray>全员同剑胚同种子，比谁分数高。",
                        "<dark_gray>/zhujian contest 看状态与榜单", "", "<yellow>▶ 左键看榜")));
        inv.setItem(25, icon(Material.GOLD_INGOT, "<#ffd166>剑币排行",
                List.of("<gray>按剑币、声望、连锻、专精分榜。",
                        "<dark_gray>/zhujian rank 查看", "", "<yellow>▶ 左键看榜")));
        return inv;
    }

    // ---------------------------------------------------------------- 武器 / 装备入口

    public Inventory buildWeaponsMenu(Player player, List<ForgeMaterial> materials) {
        WeaponMenuHolder holder = new WeaponMenuHolder();
        Inventory inv = org.bukkit.Bukkit.createInventory(holder, MENU_SIZE,
                Msg.mm("<dark_gray>铸剑大师 <dark_gray>| <#7fd3ff>锻造武器"));
        holder.inventory(inv);
        for (int i = 0; i < MENU_SIZE; i++) {
            inv.setItem(i, filler());
        }
        inv.setItem(4, icon(Material.IRON_SWORD, "<#7fd3ff><bold>选择剑胚</bold>",
                List.of("<gray>档位越高，耗材与锻造费越贵。",
                        "<dark_gray>星陨剑收五种矿物块")));

        int idx = 0;
        for (ForgeMaterial m : materials) {
            if (idx >= MATERIAL_SLOTS.length) {
                break;
            }
            inv.setItem(MATERIAL_SLOTS[idx++], materialIcon(player, m));
        }
        inv.setItem(17, icon(Material.ARROW, "<gray>返回主界面", List.of("<yellow>▶ 左键返回")));
        return inv;
    }

    public Inventory buildArmorSlots(Player player) {
        ArmorMenuHolder holder = new ArmorMenuHolder(ArmorMenuHolder.Stage.SLOT, null);
        Inventory inv = org.bukkit.Bukkit.createInventory(holder, MENU_SIZE,
                Msg.mm("<dark_gray>铸剑大师 <dark_gray>| <#7ef2c8>锻造装备"));
        holder.inventory(inv);
        for (int i = 0; i < MENU_SIZE; i++) {
            inv.setItem(i, filler());
        }
        inv.setItem(4, icon(Material.IRON_CHESTPLATE, "<#7ef2c8><bold>选择部位</bold>",
                List.of("<gray>先选部位，再选档位。",
                        "<dark_gray>装备吃护甲 / 韧性类词条")));

        ForgeMaterial.Slot[] slots = ForgeMaterial.Slot.values();
        int[] positions = {11, 12, 13, 14};
        for (int i = 0; i < slots.length && i < positions.length; i++) {
            ForgeMaterial.Slot slot = slots[i];
            Material icon = switch (slot) {
                case HELMET -> Material.IRON_HELMET;
                case CHESTPLATE -> Material.IRON_CHESTPLATE;
                case LEGGINGS -> Material.IRON_LEGGINGS;
                case BOOTS -> Material.IRON_BOOTS;
            };
            inv.setItem(positions[i], icon(icon, "<#7ef2c8>" + slot.label(),
                    List.of("<gray>六档可选：铁 / 金 / 铜 / 钻石 / 下界合金 / 星陨。",
                            "", "<yellow>▶ 左键选择部位")));
        }
        inv.setItem(22, icon(Material.ARROW, "<gray>返回主界面", List.of("<yellow>▶ 左键返回")));
        return inv;
    }

    public Inventory buildArmorTiers(Player player, List<ForgeMaterial> bases, ForgeMaterial.Slot slot,
                                     double priceMultiplier) {
        ArmorMenuHolder holder = new ArmorMenuHolder(ArmorMenuHolder.Stage.TIER, slot);
        Inventory inv = org.bukkit.Bukkit.createInventory(holder, MENU_SIZE,
                Msg.mm("<dark_gray>铸剑大师 <dark_gray>| <#7ef2c8>" + slot.label()));
        holder.inventory(inv);
        for (int i = 0; i < MENU_SIZE; i++) {
            inv.setItem(i, filler());
        }
        inv.setItem(4, icon(Material.ANVIL, "<#7ef2c8><bold>" + slot.label() + " · 选择档位</bold>",
                List.of("<gray>装备的护甲值来自材质，词条另有加成。",
                        "<dark_gray>锻造费与同档武器一致")));

        int idx = 0;
        for (ForgeMaterial base : bases) {
            if (idx >= MATERIAL_SLOTS.length) {
                break;
            }
            ForgeMaterial armor = com.forgemaster.material.ArmorCatalog.create(base, slot, priceMultiplier);
            if (armor == null) {
                continue;
            }
            inv.setItem(MATERIAL_SLOTS[idx++], armorIcon(player, armor));
        }
        inv.setItem(22, icon(Material.ARROW, "<gray>返回部位选择", List.of("<yellow>▶ 左键返回")));
        return inv;
    }

    private ItemStack armorIcon(Player player, ForgeMaterial m) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>部位: <white>" + (m.slot() == null ? "?" : m.slot().label()));
        lore.add("<gray>阶级: <white>" + "★".repeat(Math.max(1, m.tier())));
        lore.add("<gray>额外护甲: <#7ef2c8>+" + Msg.fmt(m.bonusArmor(), 1));
        if (m.armorToughness() > 0) {
            lore.add("<gray>盔甲韧性: <#7ef2c8>+" + Msg.fmt(m.armorToughness(), 1));
        }
        lore.add("<gray>最佳温度: <#ff9a3c>" + (int) m.idealTemp() + "° <gray>± " + (int) m.tolerance() + "°");
        lore.add("");
        appendCostLore(player, m, lore);
        lore.add("");
        lore.add("<yellow>▶ 左键开始锻造");
        return icon(m.icon(), m.display(), lore);
    }

    private ItemStack materialIcon(Player player, ForgeMaterial m) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>阶级: <white>" + "★".repeat(Math.max(1, m.tier())));
        lore.add("<gray>最佳温度: <#ff9a3c>" + (int) m.idealTemp() + "° <gray>± " + (int) m.tolerance() + "°");
        lore.add("<gray>需要敲打: <white>" + m.strikes() + " <gray>次");
        lore.add("<gray>判定窗口: <white>" + (m.zoneWidth() * 2 + 1) + " <gray>格");
        lore.add("");
        appendCostLore(player, m, lore);
        lore.add("");
        lore.add("<yellow>▶ 左键开始锻造");
        return icon(m.icon(), m.display(), lore);
    }

    /** 耗材与锻造费：多耗材逐条列出，缺料标红 */
    private void appendCostLore(Player player, ForgeMaterial m, List<String> lore) {
        if (m.costs().isEmpty()) {
            lore.add("<gray>耗材: <white>无");
        } else {
            for (var e : m.costs().entrySet()) {
                int need = e.getValue();
                int have = com.forgemaster.material.MaterialGrades.totalCount(player, e.getKey());
                boolean ok = have >= need;
                lore.add("<gray>耗材: <white>" + displayMat(e.getKey()) + " × " + need
                        + " <" + (ok ? "#4ee36b" : "#ff5252") + ">(" + have + "/" + need + ")");
            }
        }
        if (m.price() > 0) {
            lore.add("<gray>锻造费: <#ffd166>" + money(m.price()));
        }
    }

    /** 金额文案（接了 Vault / CMI 就用服务器的货币名） */
    private String money(long amount) {
        if (plugin instanceof com.forgemaster.ForgingMasterPlugin fm && fm.economy() != null) {
            return fm.economy().format(amount);
        }
        return amount + " 金币";
    }

    // ---------------------------------------------------------------- 加热

    public Inventory buildHeating(ForgeSession s) {
        ForgeHolder holder = new ForgeHolder(s);
        Inventory inv = org.bukkit.Bukkit.createInventory(holder, FORGE_SIZE,
                Msg.mm("<dark_gray>铸剑大师 <dark_gray>| <#ff9a3c>加热"));
        holder.inventory(inv);
        for (int i = 0; i < FORGE_SIZE; i++) {
            inv.setItem(i, filler());
        }
        inv.setItem(4, icon(Material.FURNACE, "<#ff9a3c>炉温", List.of(
                "<gray>把剑胚烧到最佳区间再取出来。",
                "<gray>温度会随时间下降。")));
        inv.setItem(10, icon(Material.BLAZE_POWDER,
                "<gradient:#ffd166:#ff5e00><bold>拉 风 箱</bold></gradient>",
                List.of("<gray>左键：+8° 炉温", "<dark_gray>每 0.2 秒可拉一次")));
        inv.setItem(13, icon(Material.ANVIL,
                "<gradient:#7ef29d:#39c26b><bold>取 出 剑 坯</bold></gradient>",
                List.of("<gray>左键：锁定当前温度，进入锻打", "<red>温度不对会扣品质")));
        inv.setItem(16, icon(s.material().icon(), s.material().display(), List.of(
                "<gray>最佳温度: <#ff9a3c>" + (int) s.material().idealTemp() + "°",
                "<gray>窗口: <white>± " + (int) s.material().tolerance() + "°")));
        inv.setItem(QUIT_SLOT, icon(Material.BARRIER, "<red>放弃锻造", List.of("<gray>材料不退还")));
        renderTemperature(s, inv);
        return inv;
    }

    public void renderTemperature(ForgeSession s, Inventory inv) {
        ForgeMaterial m = s.material();
        double temp = s.temperature();
        boolean inBand = m.inBand(temp);
        String color = inBand ? "#4ee36b" : (m.deviation(temp) < m.tolerance() * 2 ? "#ffd166" : "#ff5252");
        String state = temp > 110 ? "<red>过热！剑坯要烧化了"
                : inBand ? "<#4ee36b>温度正好 · 快取出"
                : temp < m.idealTemp() ? "<#7fd3ff>偏凉 · 再拉风箱"
                : "<#ff9a3c>偏热 · 等它降一降";

        // 审计 #4：炉温格整格重建（getItemMeta + 全 lore 解析 + setItemMeta）成本高，
        // 渲染签名（温度读数 + 颜色 + 状态）没变就整格跳过，行为等价只是少了重复写
        String sig = color + '|' + Msg.fmt(temp, 1) + '|' + state + '|' + s.material().id();
        String prev = TEMP_SIG.get(inv);
        if (sig.equals(prev)) {
            return;
        }
        TEMP_SIG.put(inv, sig);

        List<String> lore = new ArrayList<>();
        lore.add("<gray>当前温度: <color:" + color + "><bold>" + Msg.fmt(temp, 1) + "°</bold></color>");
        lore.add(Msg.bar(temp / 120.0, 20, color.replace("#", "#"), "#3a3a3a"));
        lore.add("");
        lore.add("<gray>最佳区间: <white>" + (int) (m.idealTemp() - m.tolerance()) + "° ~ "
                + (int) (m.idealTemp() + m.tolerance()) + "°");
        lore.add("<gray>状态: " + state);
        inv.setItem(4, icon(inBand ? Material.BLAST_FURNACE : Material.FURNACE,
                "<#ff9a3c>炉温", lore));
    }

    // ---------------------------------------------------------------- 锻打

    public Inventory buildHammering(ForgeSession s) {
        ForgeHolder holder = new ForgeHolder(s);
        Inventory inv = org.bukkit.Bukkit.createInventory(holder, FORGE_SIZE,
                Msg.mm("<dark_gray>铸剑大师 <dark_gray>| <#7fd3ff>锻打"));
        holder.inventory(inv);
        for (int i = 0; i < FORGE_SIZE; i++) {
            inv.setItem(i, filler());
        }
        inv.setItem(4, icon(Material.IRON_INGOT, "<#7fd3ff>锻打进度", List.of(
                "<gray>指针经过 <#4ee36b>绿色格</#4ee36b> 时敲击。",
                "<gray>正中 = 完美，偏一格 = 良好。")));
        inv.setItem(STRIKE_SLOT, icon(Material.ANVIL,
                "<gradient:#ffd166:#ff5e00><bold>敲 击</bold></gradient>",
                List.of("<gray>左键：落锤", "<dark_gray>看准指针再敲")));
        inv.setItem(QUIT_SLOT, icon(Material.BARRIER, "<red>放弃锻造", List.of("<gray>材料不退还")));
        renderHammerInfo(s, inv);
        renderTrack(s, inv);
        return inv;
    }

    public void renderHammerInfo(ForgeSession s, Inventory inv) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>已敲: <white>" + s.strikesDone() + " / " + s.material().strikes());
        lore.add("<gray>完美: <#4ee36b>" + s.perfects());
        lore.add("<gray>当前评分: <white>" + Msg.fmt(s.hammerScore() * 100, 1));
        lore.add("");
        lore.add(Msg.bar(s.hammerScore(), 20, "#7fd3ff", "#3a3a3a"));
        int left = s.material().strikes() - s.strikesDone();
        lore.add("<yellow>还剩 " + left + " 锤");
        inv.setItem(4, icon(Material.IRON_INGOT, "<#7fd3ff>锻打进度", lore));
    }

    public void renderTrack(ForgeSession s, Inventory inv) {
        // 审计 #4：每 2~3 tick 全量重建 9 格 icon（每格 new ItemStack + getItemMeta）
        // 改为差量更新——先算每格的「命中区 / 指针」状态位，和上一次相同的格子直接跳过，
        // 每次只重设指针离开/进入与命中区挪动的 2~3 格。
        int[] prev = TRACK_STATE.get(inv);
        if (prev == null) {
            // -1 保证首次渲染把 9 格全部铺上（覆盖 buildXxx 里放的填充格）
            prev = new int[TRACK_LEN];
            Arrays.fill(prev, -1);
            TRACK_STATE.put(inv, prev);
        }
        int pointer = s.pointer();
        int half = s.material().zoneWidth();
        int center = s.zoneCenter();
        for (int i = 0; i < TRACK_LEN; i++) {
            boolean isTarget = Math.abs(i - center) <= half;
            boolean isPointer = i == pointer;
            int state = (isTarget ? 1 : 0) | (isPointer ? 2 : 0);
            if (prev[i] == state) {
                continue;
            }
            prev[i] = state;
            Material mat;
            String name;
            if (isPointer && isTarget) {
                mat = Material.RED_STAINED_GLASS_PANE;
                name = "<red><bold>▼</bold> <#4ee36b>命中区";
            } else if (isPointer) {
                mat = Material.RED_STAINED_GLASS_PANE;
                name = "<red><bold>▼ 指针";
            } else if (isTarget) {
                mat = Material.LIME_STAINED_GLASS_PANE;
                name = "<#4ee36b>命中区";
            } else {
                mat = Material.GRAY_STAINED_GLASS_PANE;
                name = "<dark_gray>·";
            }
            inv.setItem(TRACK_START + i, icon(mat, name, List.of()));
        }
    }

    // ---------------------------------------------------------------- 叠钢

    public Inventory buildFolding(ForgeSession s) {
        ForgeHolder holder = new ForgeHolder(s);
        Inventory inv = org.bukkit.Bukkit.createInventory(holder, FORGE_SIZE,
                Msg.mm("<dark_gray>铸剑大师 <dark_gray>| <#ffb84d>叠钢"));
        holder.inventory(inv);
        for (int i = 0; i < FORGE_SIZE; i++) {
            inv.setItem(i, filler());
        }
        inv.setItem(4, icon(Material.PAPER, "<#ffb84d>折叠钢坯", List.of(
                "<gray>按顺序点出屏幕上的序列。",
                "<gray>点对一次 = 折叠一层，层数决定钢纹。")));
        renderFoldTrack(s, inv);
        renderFoldInfo(s, inv);
        inv.setItem(QUIT_SLOT, icon(Material.BARRIER, "<red>放弃锻造", List.of("<gray>材料不退还")));
        return inv;
    }

    /** 轨道 9 格做数字按钮，玩家按序列顺序点 */
    public void renderFoldTrack(ForgeSession s, Inventory inv) {
        for (int i = 0; i < TRACK_LEN; i++) {
            boolean next = !s.foldSequence().isEmpty()
                    && s.foldCursor() < s.foldSequence().size()
                    && s.foldSequence().get(s.foldCursor()) == i;
            inv.setItem(TRACK_START + i, icon(
                    next ? Material.LIME_STAINED_GLASS_PANE : Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                    (next ? "<#4ee36b><bold>" : "<gray>") + "[" + (i + 1) + "]",
                    next ? List.of("<#4ee36b>下一击点这里") : List.of("<dark_gray>待敲")));
        }
    }

    public void renderFoldInfo(ForgeSession s, Inventory inv) {
        StringBuilder seq = new StringBuilder();
        List<Integer> list = s.foldSequence();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                seq.append("<dark_gray> → ");
            }
            if (i < s.foldCursor()) {
                seq.append("<#4ee36b>").append(list.get(i) + 1);
            } else if (i == s.foldCursor()) {
                seq.append("<yellow><bold>").append(list.get(i) + 1).append("</bold>");
            } else {
                seq.append("<gray>").append(list.get(i) + 1);
            }
        }
        long remainMs = Math.max(0, s.foldDeadline() - System.currentTimeMillis());

        List<String> lore = new ArrayList<>();
        lore.add("<gray>折叠序列: " + seq);
        lore.add("");
        lore.add("<gray>已折: <white>" + s.foldCursor() + " / " + list.size()
                + " <gray>层 · 失手 <white>" + s.foldMissed());
        lore.add("<gray>当前钢纹: <white>" + s.pattern());
        lore.add("");
        lore.add("<gray>剩余时间: <white>" + Msg.fmt(remainMs / 1000.0, 1) + "s");
        lore.add(Msg.bar(s.foldScore(), 20, "#ffb84d", "#3a3a3a"));
        inv.setItem(4, icon(Material.PAPER, "<#ffb84d>折叠钢坯", lore));
    }

    // ---------------------------------------------------------------- 开刃

    public Inventory buildGrinding(ForgeSession s) {
        return buildGrinding(s, grindDuration);
    }

    public Inventory buildGrinding(ForgeSession s, int durationTicks) {
        this.grindDuration = Math.max(20, durationTicks);
        ForgeHolder holder = new ForgeHolder(s);
        Inventory inv = org.bukkit.Bukkit.createInventory(holder, FORGE_SIZE,
                Msg.mm("<dark_gray>铸剑大师 <dark_gray>| <#7ef2c8>开刃"));
        holder.inventory(inv);
        for (int i = 0; i < FORGE_SIZE; i++) {
            inv.setItem(i, filler());
        }
        inv.setItem(4, icon(Material.GRINDSTONE, "<#7ef2c8>研磨开刃", List.of(
                "<gray>把刃压保持在绿色区间内。",
                "<gray>压力会自己漂，靠加减压拉回来。")));
        // 注意：9~17 是轨道，按钮必须避开，否则会被 renderGrindTrack 盖掉
        inv.setItem(PRESS_UP_SLOT, icon(Material.IRON_BLOCK,
                "<gradient:#ff9a3c:#ff5252><bold>加 压</bold></gradient>",
                List.of("<gray>左键：压力 +" + Msg.fmt(pressStep, 0), "<dark_gray>刃口吃得更深")));
        inv.setItem(PRESS_DOWN_SLOT, icon(Material.FEATHER,
                "<gradient:#7ef2c8:#4ea8ff><bold>减 压</bold></gradient>",
                List.of("<gray>左键：压力 -" + Msg.fmt(pressStep, 0), "<dark_gray>刃口抬起来")));
        inv.setItem(QUIT_SLOT, icon(Material.BARRIER, "<red>放弃锻造", List.of("<gray>材料不退还")));
        renderGrindTrack(s, inv);
        renderGrindInfo(s, inv);
        return inv;
    }

    private double pressStep = 6.0;

    public void pressStep(double step) {
        this.pressStep = step;
    }

    public void renderGrindTrack(ForgeSession s, Inventory inv) {
        // 审计 #4：与 renderTrack 同一套差量思路——开刃区每局固定，压力指针每 2 tick
        // 漂一格，全量重绘 9 格纯属浪费；这里只重设指针离开/进入的 2~3 格。
        double value = s.grindValue();
        double center = s.grindTargetCenter();
        double width = s.grindTargetWidth();
        int[] prev = TRACK_STATE.get(inv);
        if (prev == null) {
            prev = new int[TRACK_LEN];
            Arrays.fill(prev, -1);
            TRACK_STATE.put(inv, prev);
        }
        for (int i = 0; i < TRACK_LEN; i++) {
            // 每格代表 100/9 的压力段
            double slotCenter = (i + 0.5) * (100.0 / TRACK_LEN);
            boolean inZone = Math.abs(slotCenter - center) <= width;
            int pointerIndex = (int) Math.floor(value / (100.0 / TRACK_LEN));
            if (pointerIndex >= TRACK_LEN) {
                pointerIndex = TRACK_LEN - 1;
            }
            if (pointerIndex < 0) {
                pointerIndex = 0;
            }
            boolean isPointer = pointerIndex == i;
            int state = (inZone ? 1 : 0) | (isPointer ? 2 : 0);
            if (prev[i] == state) {
                continue;
            }
            prev[i] = state;
            Material mat;
            String name;
            if (isPointer && inZone) {
                mat = Material.RED_STAINED_GLASS_PANE;
                name = "<red><bold>◆</bold> <#4ee36b>压力正好";
            } else if (isPointer) {
                mat = Material.RED_STAINED_GLASS_PANE;
                name = "<red><bold>◆</bold> 当前压力";
            } else if (inZone) {
                mat = Material.LIME_STAINED_GLASS_PANE;
                name = "<#4ee36b>开刃区";
            } else {
                mat = Material.GRAY_STAINED_GLASS_PANE;
                name = "<dark_gray>·";
            }
            inv.setItem(TRACK_START + i, icon(mat, name, List.of()));
        }
    }

    public void renderGrindInfo(ForgeSession s, Inventory inv) {
        int total = Math.max(1, durationOf(s));
        List<String> lore = new ArrayList<>();
        lore.add("<gray>压力: <white>" + Msg.fmt(s.grindValue(), 0)
                + " <dark_gray>| 目标 " + Msg.fmt(s.grindTargetCenter() - s.grindTargetWidth(), 0)
                + " ~ " + Msg.fmt(s.grindTargetCenter() + s.grindTargetWidth(), 0));
        lore.add(Msg.bar(s.grindValue() / 100.0, 20,
                s.grindInZone() ? "#4ee36b" : "#ff9a3c", "#3a3a3a"));
        lore.add("");
        lore.add("<gray>稳定时长: <white>" + Msg.fmt(s.grindScore() * 100, 1) + "%");
        lore.add(Msg.bar(s.grindScore(), 20, "#7ef2c8", "#3a3a3a"));
        lore.add("<gray>剩余: <white>" + Msg.fmt((total - s.grindTicksTotal()) / 20.0, 1) + "s");
        lore.add("");
        lore.add(s.grindInZone() ? "<#4ee36b>保持住！" : "<#ff9a3c>拉回绿区");
        inv.setItem(4, icon(s.grindInZone() ? Material.GRINDSTONE : Material.COBBLESTONE,
                "<#7ef2c8>研磨开刃", lore));
    }

    private int grindDuration = 120;

    public void grindDuration(int ticks) {
        this.grindDuration = Math.max(20, ticks);
    }

    /** 会话决定这局多久：修复用修复时长，正常开刃用开刃时长 */
    private int durationOf(ForgeSession s) {
        return s.repairing() ? settings.repairDurationTicks() : settings.grindDurationTicks();
    }

    // ---------------------------------------------------------------- 抛光（v2.19）

    public Inventory buildPolishing(ForgeSession s) {
        ForgeHolder holder = new ForgeHolder(s);
        Inventory inv = org.bukkit.Bukkit.createInventory(holder, FORGE_SIZE,
                Msg.mm("<dark_gray>铸剑大师 <dark_gray>| <white>抛光"));
        holder.inventory(inv);
        for (int i = 0; i < FORGE_SIZE; i++) {
            inv.setItem(i, filler());
        }
        inv.setItem(STRIKE_SLOT, icon(Material.PAPER,
                "<gradient:#ffffff:#9fe8ff><bold>下 刀</bold></gradient>",
                List.of("<gray>指针扫到亮区正中时左键",
                        "<gray>共 <white>" + s.polishPasses() + "</white> 刀，一刀一格光泽")));
        inv.setItem(QUIT_SLOT, icon(Material.BARRIER, "<red>放弃锻造", List.of("<gray>材料不退还")));
        renderPolishTrack(s, inv);
        renderPolishInfo(s, inv);
        return inv;
    }

    /**
     * 抛光光泽条：指针 + 亮区 + 满分区三态叠加，同样走差量更新。
     *
     * <p>指针每 tick 移动，全量重绘 9 格是浪费；这里把「是否在亮区 / 是否在满分区 /
     * 是否是当前指针」编成状态位，和上次相同的格子直接跳过。</p>
     */
    public void renderPolishTrack(ForgeSession s, Inventory inv) {
        int[] prev = TRACK_STATE.get(inv);
        if (prev == null) {
            prev = new int[TRACK_LEN];
            Arrays.fill(prev, -1);
            TRACK_STATE.put(inv, prev);
        }
        double value = s.polishValue();
        double center = s.polishCenter();
        double width = s.polishWidth();
        int pointerIndex = (int) Math.floor(value / (100.0 / TRACK_LEN));
        pointerIndex = Math.max(0, Math.min(TRACK_LEN - 1, pointerIndex));
        for (int i = 0; i < TRACK_LEN; i++) {
            double slotCenter = (i + 0.5) * (100.0 / TRACK_LEN);
            double off = Math.abs(slotCenter - center);
            boolean inZone = off <= width;
            boolean core = off <= width * 0.5;
            boolean isPointer = pointerIndex == i;
            int state = (inZone ? 1 : 0) | (isPointer ? 2 : 0) | (core ? 4 : 0);
            if (prev[i] == state) {
                continue;
            }
            prev[i] = state;
            Material mat;
            String name;
            if (isPointer && core) {
                mat = Material.RED_STAINED_GLASS_PANE;
                name = "<red><bold>◆</bold> <#4ee36b>正中";
            } else if (isPointer && inZone) {
                mat = Material.ORANGE_STAINED_GLASS_PANE;
                name = "<gold><bold>◆</bold> <#ffd166>亮区";
            } else if (isPointer) {
                mat = Material.YELLOW_STAINED_GLASS_PANE;
                name = "<yellow><bold>◆</bold> 指针";
            } else if (core) {
                mat = Material.LIME_STAINED_GLASS_PANE;
                name = "<#4ee36b>满分区";
            } else if (inZone) {
                mat = Material.WHITE_STAINED_GLASS_PANE;
                name = "<white>亮区";
            } else {
                mat = Material.GRAY_STAINED_GLASS_PANE;
                name = "<dark_gray>·";
            }
            inv.setItem(TRACK_START + i, icon(mat, name, List.of()));
        }
    }

    public void renderPolishInfo(ForgeSession s, Inventory inv) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>第 <white>" + Math.min(s.polishPasses(), s.polishPass() + 1)
                + " / " + s.polishPasses() + "</white> 刀 <dark_gray>| 命中 <white>" + s.polishHits());
        lore.add("<gray>指针 <white>" + Msg.fmt(s.polishValue(), 0) + "</white> <dark_gray>| 亮区 "
                + Msg.fmt(s.polishCenter() - s.polishWidth(), 0) + " ~ "
                + Msg.fmt(s.polishCenter() + s.polishWidth(), 0));
        lore.add(s.polishInZone() ? "<#4ee36b>就是现在，下刀！" : "<#ff9a3c>等指针扫进亮区");
        if (s.polishLastError() >= 0) {
            lore.add("<gray>上一刀偏差: <white>" + Msg.fmt(s.polishLastError(), 1));
        }
        long remain = s.polishDeadline() - System.currentTimeMillis();
        lore.add("<gray>剩余: <white>" + Msg.fmt(Math.max(0, remain) / 1000.0, 1) + "s");
        lore.add("");
        lore.add("<gray>光泽成色: <white>" + Msg.fmt(s.polishScore() * 100, 1) + "%");
        lore.add(Msg.bar(s.polishScore(), 20, "#9fe8ff", "#3a3a3a"));
        inv.setItem(4, icon(Material.PAPER, "<#9fe8ff>抛光", lore));
    }

    // ---------------------------------------------------------------- 嵌银丝（v2.19）

    public Inventory buildInlay(ForgeSession s) {
        ForgeHolder holder = new ForgeHolder(s);
        Inventory inv = org.bukkit.Bukkit.createInventory(holder, FORGE_SIZE,
                Msg.mm("<dark_gray>铸剑大师 <dark_gray>| <white>嵌银丝"));
        holder.inventory(inv);
        for (int i = 0; i < FORGE_SIZE; i++) {
            inv.setItem(i, filler());
        }
        inv.setItem(QUIT_SLOT, icon(Material.BARRIER, "<red>放弃锻造", List.of("<gray>材料不退还")));
        renderInlayTrack(s, inv);
        renderInlayInfo(s, inv);
        return inv;
    }

    /** 银丝槽：当前该点的那根最亮，已经嵌上的变蓝，还没轮到的暗青 */
    public void renderInlayTrack(ForgeSession s, Inventory inv) {
        int[] prev = TRACK_STATE.get(inv);
        if (prev == null) {
            prev = new int[TRACK_LEN];
            Arrays.fill(prev, -1);
            TRACK_STATE.put(inv, prev);
        }
        int target = s.inlayTarget();
        boolean[] done = new boolean[TRACK_LEN];
        boolean[] upcoming = new boolean[TRACK_LEN];
        List<Integer> seq = s.inlaySequence();
        for (int k = 0; k < seq.size(); k++) {
            int idx = seq.get(k);
            if (idx < 0 || idx >= TRACK_LEN) {
                continue;
            }
            if (k < s.inlayCursor()) {
                done[idx] = true;
            } else if (idx != target) {
                upcoming[idx] = true;
            }
        }
        boolean fading = s.inlayWindowRatio() < 0.35;
        for (int i = 0; i < TRACK_LEN; i++) {
            boolean isTarget = i == target;
            int state = (isTarget ? 1 : 0) | (done[i] ? 2 : 0) | (upcoming[i] ? 4 : 0)
                    | (isTarget && fading ? 8 : 0);
            if (prev[i] == state) {
                continue;
            }
            prev[i] = state;
            Material mat;
            String name;
            if (isTarget && fading) {
                mat = Material.ORANGE_STAINED_GLASS_PANE;
                name = "<gold><bold>◆</bold> 要灭了";
            } else if (isTarget) {
                mat = Material.WHITE_STAINED_GLASS_PANE;
                name = "<white><bold>◆</bold> 就点这根";
            } else if (done[i]) {
                mat = Material.LIGHT_BLUE_STAINED_GLASS_PANE;
                name = "<#9fe8ff>已嵌";
            } else if (upcoming[i]) {
                mat = Material.CYAN_STAINED_GLASS_PANE;
                name = "<dark_aqua>待嵌";
            } else {
                mat = Material.GRAY_STAINED_GLASS_PANE;
                name = "<dark_gray>·";
            }
            inv.setItem(TRACK_START + i, icon(mat, name, List.of()));
        }
    }

    public void renderInlayInfo(ForgeSession s, Inventory inv) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>银丝 <white>" + Math.min(s.inlaySequence().size(), s.inlayCursor())
                + " / " + s.inlaySequence().size() + "</white> <dark_gray>| 命中 <white>"
                + s.inlayHits() + "</white> <dark_gray>| 失手 <white>" + s.inlayMiss());
        lore.add("<gray>亮窗: <white>" + s.inlayWindowLeft() + " tick");
        lore.add(Msg.bar(s.inlayWindowRatio(), 20,
                s.inlayWindowRatio() < 0.35 ? "#ff9a3c" : "#9fe8ff", "#3a3a3a"));
        long remain = s.inlayDeadline() - System.currentTimeMillis();
        lore.add("<gray>剩余: <white>" + Msg.fmt(Math.max(0, remain) / 1000.0, 1) + "s");
        lore.add("");
        lore.add("<gray>嵌丝成色: <white>" + Msg.fmt(s.inlayScore() * 100, 1) + "%");
        lore.add(Msg.bar(s.inlayScore(), 20, "#c9d6ff", "#3a3a3a"));
        inv.setItem(4, icon(Material.STRING, "<#c9d6ff>嵌银丝", lore));
    }

    // ---------------------------------------------------------------- 淬火

    public Inventory buildQuenching(ForgeSession s, List<Quench> quenches) {
        ForgeHolder holder = new ForgeHolder(s);
        Inventory inv = org.bukkit.Bukkit.createInventory(holder, FORGE_SIZE,
                Msg.mm("<dark_gray>铸剑大师 <dark_gray>| <#9fe8ff>淬火"));
        holder.inventory(inv);
        for (int i = 0; i < FORGE_SIZE; i++) {
            inv.setItem(i, filler());
        }
        inv.setItem(4, icon(Material.WATER_BUCKET, "<#9fe8ff>选择淬火介质", List.of(
                "<gray>介质决定词条池，也影响评分加成。",
                "<dark_gray>只能选一次")));

        int[] slots = QUENCH_SLOTS;
        int i = 0;
        for (Quench q : quenches) {
            if (i >= slots.length) {
                break;
            }
            List<String> lore = new ArrayList<>();
            if (!q.description().isBlank()) {
                lore.add("<gray>" + q.description());
            }
            lore.add("<gray>评分加成: <white>+" + Msg.fmt(q.scoreBonus(), 1));
            if (!q.pool().isEmpty()) {
                lore.add("<gray>词条倾向: <white>" + String.join("<gray>, <white>", q.pool()));
            }
            lore.add("");
            lore.add("<yellow>▶ 左键淬火");
            inv.setItem(slots[i++], icon(q.icon(), q.colored(), lore));
        }
        inv.setItem(QUIT_SLOT, icon(Material.BARRIER, "<red>放弃锻造", List.of("<gray>材料不退还")));
        return inv;
    }

    // ---------------------------------------------------------------- 宝石台

    public Inventory buildGemBench(Player player) {
        GemMenuHolder holder = new GemMenuHolder();
        Inventory inv = org.bukkit.Bukkit.createInventory(holder, MENU_SIZE,
                Msg.mm("<dark_gray>铸剑大师 <dark_gray>| <#b06bff>宝石台"));
        holder.inventory(inv);
        for (int i = 0; i < MENU_SIZE; i++) {
            inv.setItem(i, filler());
        }

        var fm = (com.forgemaster.ForgingMasterPlugin) plugin;
        var gems = fm.gems();
        ItemStack hand = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        boolean forged = fm.items().isForged(hand);

        inv.setItem(4, icon(Material.AMETHYST_SHARD, "<#b06bff><bold>宝石台</bold>",
                List.of("<gray>开孔 → 熔炼宝石 → 镶嵌，三步。",
                        "<dark_gray>宝石孔与附魔孔各一套，互不占用",
                        "",
                        "<gray>主手: <white>" + (hand.getType().isAir() ? "空"
                                : com.forgemaster.material.MaterialGrades.pretty(hand.getType())),
                        "<gray>副手: <white>" + (off.getType().isAir() ? "空"
                                : com.forgemaster.material.MaterialGrades.pretty(off.getType())))));

        int sockets = forged ? gems.socketsOf(hand) : 0;
        var quality = forged ? com.forgemaster.quality.Quality.byName(
                hand.getItemMeta().getPersistentDataContainer().get(
                        com.forgemaster.util.Keys.QUALITY, org.bukkit.persistence.PersistentDataType.STRING))
                : null;
        if (quality == null) {
            quality = com.forgemaster.quality.Quality.COMMON;
        }
        long socketPrice = fm.settings().longOf("gems.socket.cost-coins", 500) * (sockets + 1);
        int socketLimit = forged ? fm.gems().socketLimitOf(hand) : 0;
        int enchSockets = forged ? fm.gems().enchantSocketsOf(hand) : 0;
        int enchLimit = forged ? fm.gems().enchantLimitOf(hand) : 0;
        long enchPrice = fm.settings().longOf("gems.enchant-socket.cost-coins", 800) * (enchSockets + 1);

        // v2.10：宝石孔和附魔孔分家，开孔一对、镶嵌一对，左右并列
        inv.setItem(10, icon(Material.GRINDSTONE, "<#ffd166><bold>一、宝石开孔</bold>",
                List.of("<gray>主手拿锻造品，开一个<white>宝石孔</white>。",
                        "<gray>上限: <white>" + socketLimit + "</white>（按材料档位）",
                        "<gray>当前: <white>" + (forged ? sockets + "/" + socketLimit : "—"),
                        "<dark_gray>只镶宝石，附魔走右边那个",
                        "<gray>费用: <#ffd166>" + fm.economy().format(socketPrice),
                        "<gray>成功率: <white>" + Msg.fmt(Math.max(35, 100 - sockets * 15), 0) + "%",
                        "", "<yellow>▶ 左键开宝石孔")));

        inv.setItem(11, icon(Material.ENCHANTING_TABLE, "<#b06bff><bold>二、附魔开孔</bold>",
                List.of("<gray>主手拿锻造品，开一个<white>附魔孔</white>。",
                        "<gray>上限: <white>" + enchLimit + "</white>（按材料档位）",
                        "<gray>当前: <white>" + (forged ? enchSockets + "/" + enchLimit : "—"),
                        "<dark_gray>只镶附魔书，宝石走左边那个",
                        "<gray>费用: <#ffd166>" + fm.economy().format(enchPrice),
                        "<gray>成功率: <white>" + Msg.fmt(Math.max(35, 100 - enchSockets * 15), 0) + "%",
                        "", "<yellow>▶ 左键开附魔孔")));

        inv.setItem(13, icon(Material.NETHER_STAR, "<#b06bff><bold>三、星熔宝石</bold>",
                List.of("<gray>主手拿下界之星，熔出随机 Lv.1 宝石。",
                        "<dark_gray>宝石只有这一个来源，其它矿物熔不出",
                        "<gray>11 种属性随机（赤玉 / 星辉 / 破军 / 焚天 / 血玉 …）",
                        "<gray>升到 Lv.5 只能靠三合一：3ⁿ 颗",
                        "<gray>费用: <#ffd166>" + fm.economy().format(
                                fm.settings().longOf("gems.smelt.star-cost-coins", 300)),
                        "", "<yellow>▶ 左键星熔")));

        inv.setItem(15, icon(Material.AMETHYST_CLUSTER, "<#7ef2c8><bold>四、宝石镶嵌</bold>",
                List.of("<gray>主手装备 + 副手宝石 → 镶进<white>宝石孔</white>。",
                        "<gray>当前: <white>" + (forged ? gems.gemsOf(hand).size() + "/" + sockets + " 宝石" : "—"),
                        "<dark_gray>附魔孔满了也能继续镶宝石",
                        "<gray>费用: <#ffd166>" + fm.economy().format(
                                fm.settings().longOf("gems.inlay.cost-coins", 200)),
                        "", "<yellow>▶ 左键镶嵌宝石")));

        inv.setItem(16, icon(Material.ENCHANTED_BOOK, "<#b06bff><bold>五、附魔镶嵌</bold>",
                List.of("<gray>主手装备 + 副手附魔书 → 镶进<white>附魔孔</white>。",
                        "<gray>当前: <white>" + (forged ? gems.enchantsOf(hand).size() + "/" + enchSockets + " 附魔" : "—"),
                        "<dark_gray>宝石孔满了也能继续镶附魔",
                        "<gray>费用: <#ffd166>" + fm.economy().format(
                                fm.settings().longOf("gems.enchant.cost-coins", 300)),
                        "", "<yellow>▶ 左键镶嵌附魔")));

        inv.setItem(20, icon(Material.GRINDSTONE, "<#ff9a3c><bold>拆卸宝石</bold>",
                List.of("<gray>主手装备，取下最后一颗宝石。",
                        "<gray>成功率: <white>" + Msg.fmt(
                                fm.settings().num("gems.extract.success", 0.8) * 100, 0) + "%</white>（失败会碎）",
                        "<gray>费用: <#ffd166>" + fm.economy().format(
                                fm.settings().longOf("gems.extract.cost-coins", 300)),
                        "", "<yellow>▶ 左键拆卸")));

        inv.setItem(24, icon(Material.AMETHYST_BLOCK, "<#b06bff><bold>合成宝石（三合一）</bold>",
                List.of("<gray>主手 + 副手 + 背包 1 颗，三颗同级同种。",
                        "<dark_gray>Lv.1→2 85% · Lv.2→3 65% · Lv.3→4 45% · Lv.4→5 25%",
                        "<gray>最高 <white>Lv.5</white>，失败保住一颗。",
                        "<gray>费用: <#ffd166>" + fm.economy().format(
                                fm.settings().longOf("gems.merge.cost-coins", 200)),
                        "", "<yellow>▶ 左键合成")));

        inv.setItem(22, icon(Material.ARROW, "<gray>关闭", List.of("<yellow>▶ 左键关闭")));
        return inv;
    }

    public Inventory buildResult(ForgeSession s, ItemStack result) {
        ForgeHolder holder = new ForgeHolder(s);
        Inventory inv = org.bukkit.Bukkit.createInventory(holder, FORGE_SIZE,
                Msg.mm("<dark_gray>铸剑大师 <dark_gray>| <white>成品"));
        holder.inventory(inv);
        for (int i = 0; i < FORGE_SIZE; i++) {
            inv.setItem(i, filler());
        }
        inv.setItem(13, result);
        inv.setItem(11, icon(Material.PAPER, "<white>锻造报告", report(s)));
        inv.setItem(15, icon(Material.CHEST, "<#ffd166>拿 走", List.of(
                "<gray>左键：放入背包", "<dark_gray>关闭界面也会自动发放")));
        inv.setItem(STRIKE_SLOT, icon(Material.BARRIER, "<red>关闭", List.of()));
        return inv;
    }

    private List<String> report(ForgeSession s) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>材料: " + s.material().display());
        lore.add("<gray>加热评分: <white>" + Msg.fmt(s.heatScore() * 100, 1)
                + " <dark_gray>(贡献 " + Msg.fmt(s.heatScore() * 30, 1) + ")");
        lore.add("<gray>锻打评分: <white>" + Msg.fmt(s.hammerScore() * 100, 1)
                + " <dark_gray>(贡献 " + Msg.fmt(s.hammerScore() * 55, 1) + ")");
        double qb = s.quench() == null ? 0 : s.quench().scoreBonus();
        lore.add("<gray>淬火加成: <white>+" + Msg.fmt(qb, 1));
        lore.add("");
        lore.add("<gray>总评分: <white><bold>" + Msg.fmt(s.finalScore(), 1) + "</bold>");
        lore.add("<gray>品质: " + s.quality().colored());
        lore.add("");
        lore.add("<dark_gray>完美 " + s.perfects() + " 次 / 共 " + s.hits().size() + " 锤");
        return lore;
    }

    // ---------------------------------------------------------------- 工具

    public static ItemStack filler() {
        return icon(Material.GRAY_STAINED_GLASS_PANE, FILLER_NAME, List.of());
    }

    public static ItemStack icon(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // 审计 #4：icon 文案走常量池缓存，高频重绘路径不再每次全量 MiniMessage 解析
            Component display = cachedText(name);
            meta.displayName(display);
            if (!lore.isEmpty()) {
                List<Component> lines = new ArrayList<>();
                for (String line : lore) {
                    lines.add(cachedText(line));
                }
                meta.lore(lines);
            } else {
                meta.lore(List.of());
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String displayMat(Material mat) {
        return com.forgemaster.material.MaterialGrades.pretty(mat);
    }

    public static int count(Player player, Material mat) {
        int total = 0;
        for (ItemStack it : player.getInventory().getContents()) {
            if (it != null && it.getType() == mat) {
                total += it.getAmount();
            }
        }
        return total;
    }

    public static void take(Player player, Material mat, int amount) {
        int remain = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && remain > 0; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType() != mat) {
                continue;
            }
            int take = Math.min(remain, it.getAmount());
            it.setAmount(it.getAmount() - take);
            remain -= take;
            if (it.getAmount() <= 0) {
                player.getInventory().setItem(i, null);
            }
        }
        player.updateInventory();
    }

    /** 品质对应的色调，给 BossBar / 标题用 */
    public static String qualityColor(Quality q) {
        return q.color();
    }
}