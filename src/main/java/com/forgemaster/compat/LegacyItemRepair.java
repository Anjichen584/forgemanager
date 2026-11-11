package com.forgemaster.compat;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.util.Keys;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 存量物品收敛（v2.23.1）。
 *
 * <h2>为什么必须有这个类</h2>
 * 上一轮把「越强化越离谱」的滚雪球修掉了，也把吸血/气血/点燃加上了上限——但那些都是
 * <b>产出侧</b>的夹子。盘上已经存在的旧装备里躺着的是旧数值：
 *
 * <ul>
 *   <li>{@code fm_atk} 73（应该 22）</li>
 *   <li>{@code lifesteal} 1.155 = 115%（应该 20%）</li>
 *   <li>{@code fire_ticks} 800 = 40 秒（应该 200 = 10 秒）</li>
 *   <li>{@code damage_reduce} 0.58（应该 0.35）</li>
 *   <li>{@code MAX_HEALTH} 修饰符 +41（应该 20）</li>
 * </ul>
 *
 * <p>产出侧的夹子只对「新锻 / 再强化 / 再蜕变」的物品生效。玩家把那把 130 伤害的剑
 * 挂在墙上供着，它就永远是 130 —— 生存服上这等于给少数人留了外挂。所以必须有主动收敛。</p>
 *
 * <h2>三个触发点，覆盖三类存量位置</h2>
 * <ol>
 *   <li><b>命令</b> {@code /zhujian fixattrs [all|containers]} —— 服主手动扫，立刻见效。</li>
 *   <li><b>玩家上线</b>（{@link LegacyRepairListener}）—— 离线玩家的背包在 {@code playerdata}
 *       里，动它要加载玩家档案，风险不是一个量级。<b>上线扫描等于「等人自己把东西带回来」</b>，
 *       不碰档案，却能保证任何人下次登录时身上就已收敛。</li>
 *   <li><b>加载中的容器</b> —— 箱子/木桶里的存货。只扫「已加载」的区块，
 *       不为了修物品去强制加载世界（那会让服务器卡成 PPT）。</li>
 * </ol>
 *
 * <h2>Lore 为什么不能完美重写（如实说明）</h2>
 * {@code affix_list} 只存词条 id，<b>不存每条 roll 到的数值</b>——数值在锻造那一刻就
 * 化成了 Lore 文本。所以「+115% 吸血」这行字没办法按比例算回「+20%」：两个吸血词条
 * 各 roll 了多少，盘上没有记录。
 *
 * <p>因此这里的做法是：<b>把生效值夹住 + 在 Lore 末尾打一行收敛说明</b>。
 * 玩家看到的解释是明确的（"上限已收敛"），而不是「Lore 写 115%、实战回 2 点」这种
 * 看起来像 bug 的落差。想要 Lore 本身也干净，重铸一次即可（重铸会按词条池重新生成整块 Lore）。</p>
 */
public final class LegacyItemRepair {

    /** 收敛标记行（纯文本前缀，用来幂等识别与替换） */
    private static final String MARKER = "◆ 已收敛";

    private final ForgingMasterPlugin plugin;

    public LegacyItemRepair(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 修一件物品。**幂等**：同一件物品反复修，第二次之后一律返回 false。
     *
     * @return true = 这次真的改动了数值（用于统计「到底修了多少件」）
     */
    public boolean repair(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }
        if (!plugin.items().isForged(item)) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        String before = signature(meta);
        double atkBefore = attrSum(meta, Attribute.ATTACK_DAMAGE);
        double lsBefore = nz(meta.getPersistentDataContainer(), Keys.LIFESTEAL);
        double drBefore = nz(meta.getPersistentDataContainer(), Keys.DAMAGE_REDUCE);
        int fireBefore = fireOf(meta);
        double hpBefore = attrSum(meta, Attribute.MAX_HEALTH);

        // ① 基础属性（攻/速/甲/韧）：走强化侧那条已经被天花板保护的重算路径。
        //    用 applyStatsOnly 而不是 apply：收敛只该动数值，不该顺手重建玩家的 Lore 等级行。
        //    （一件只超了吸血的剑，等级行也被重建一次，玩家会以为整套属性都被改了。）
        var strengthen = plugin.strengthen();
        if (strengthen == null) {
            return false;
        }
        strengthen.applyStatsOnly(item);

        meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        // ② 词条类数值：PDC 里存的是旧版累加值，直接夹（上限走统一入口，不在这里写第三份默认值）
        var caps = com.forgemaster.combat.DamageBudget.Caps.fromConfig(plugin.getConfig());
        clampDouble(pdc, Keys.LIFESTEAL, caps.maxLifesteal());
        clampDouble(pdc, Keys.DAMAGE_REDUCE, caps.maxDamageReduce());
        Integer fire = pdc.get(Keys.FIRE_TICKS, PersistentDataType.INTEGER);
        if (fire != null && fire > 0) {
            int capped = caps.clampFireTicks(fire);
            if (capped != fire) {
                pdc.set(Keys.FIRE_TICKS, PersistentDataType.INTEGER, capped);
            }
        }

        // ③ 气血：属性 modifier 的合计值封顶，按比例缩（保留各条的键，不破坏词条身份）
        clampHealth(meta, caps.maxBonusHealth());

        String after = signature(meta);
        if (before.equals(after)) {
            // 数值没变，但可能还留着上次的标记行（比如服主改大上限后又想撤）→ 保持原样不折腾
            return false;
        }

        // 只写「真正被压过」的那几项。全量写一遍会让玩家以为所有数值都被动过，
        // 反而更慌；而且服主排查时也分不清到底哪一项超标。
        List<String> changed = new ArrayList<>();
        if (attrSum(meta, Attribute.ATTACK_DAMAGE) < atkBefore - 1e-9) {
            changed.add("攻 " + trim(attrSum(meta, Attribute.ATTACK_DAMAGE)));
        }
        if (nz(pdc, Keys.LIFESTEAL) < lsBefore - 1e-9) {
            changed.add("吸血 " + trim(nz(pdc, Keys.LIFESTEAL) * 100) + "%");
        }
        if (fireOf(meta) < fireBefore) {
            changed.add("点燃 " + trim(fireOf(meta) / 20.0) + "s");
        }
        if (nz(pdc, Keys.DAMAGE_REDUCE) < drBefore - 1e-9) {
            changed.add("减伤 " + trim(nz(pdc, Keys.DAMAGE_REDUCE) * 100) + "%");
        }
        if (attrSum(meta, Attribute.MAX_HEALTH) < hpBefore - 1e-9) {
            changed.add("气血 " + trim(attrSum(meta, Attribute.MAX_HEALTH)));
        }
        stampMarker(meta, changed);
        item.setItemMeta(meta);
        return true;
    }

    // ------------------------------------------------------------ 扫描

    /** 扫一个玩家的主背包 + 盔甲 + 副手 + 末影箱；返回修好的件数 */
    public int sweepPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return 0;
        }
        int fixed = 0;
        fixed += sweepInventory(player.getInventory());
        // 盔甲与副手可能已包含在 getContents() 里，重复扫也无害（幂等），
        // 但为了不漏掉某些实现差异，显式再扫一次盔甲/副手
        for (ItemStack it : player.getInventory().getArmorContents()) {
            if (repair(it)) {
                fixed++;
            }
        }
        if (repair(player.getInventory().getItemInOffHand())) {
            fixed++;
        }
        try {
            fixed += sweepInventory(player.getEnderChest());
        } catch (RuntimeException ex) {
            plugin.getLogger().fine("末影箱扫描失败（已跳过）：" + ex);
        }
        return fixed;
    }

    /** 扫全服在线玩家；返回修好的件数 */
    public int sweepOnline() {
        int fixed = 0;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            fixed += sweepPlayer(p);
        }
        return fixed;
    }

    /**
     * 扫「已加载」区块里的容器（箱子 / 木桶 / 漏斗 / 熔炉…）。
     *
     * <p><b>不强制加载区块</b>：为了修一个箱子里可能存在的旧装备去把整个世界 load 一遍，
     * 是拿服务器卡顿换一个没必要的东西。只扫已经在内存里的，剩下的等它自然被加载时再处理
     * （或者服主再跑一次命令）。</p>
     *
     * @return 修好的件数
     */
    public int sweepLoadedContainers() {
        int fixed = 0;
        for (var world : plugin.getServer().getWorlds()) {
            for (var chunk : world.getLoadedChunks()) {
                for (var state : chunk.getTileEntities()) {
                    if (state instanceof org.bukkit.inventory.InventoryHolder holder) {
                        Inventory inv = holder.getInventory();
                        if (inv != null) {
                            fixed += sweepInventory(inv);
                        }
                    }
                }
            }
        }
        return fixed;
    }

    /** 扫一个容器/背包，返回修好的件数 */
    public int sweepInventory(Inventory inv) {
        if (inv == null) {
            return 0;
        }
        int fixed = 0;
        for (ItemStack it : inv.getContents()) {
            if (repair(it)) {
                fixed++;
            }
        }
        return fixed;
    }

    // ------------------------------------------------------------ 内部

    private double limit(String path, double def) {
        double v = plugin.getConfig().getDouble(path, def);
        return Double.isNaN(v) ? def : v;
    }

    private void clampDouble(PersistentDataContainer pdc,
                             org.bukkit.NamespacedKey key, double cap) {
        if (cap <= 0) {
            return;
        }
        Double v = pdc.get(key, PersistentDataType.DOUBLE);
        if (v != null && v > cap) {
            pdc.set(key, PersistentDataType.DOUBLE, cap);
        }
    }

    /**
     * 气血合计封顶。按比例缩放每一条，而不是删了重加一条 ——
     * 保留原始键名，重铸/拆卸词条的逻辑才不会认不出来。
     */
    private void clampHealth(ItemMeta meta, double cap) {
        if (cap <= 0) {
            return;
        }
        Collection<AttributeModifier> mods = meta.getAttributeModifiers(Attribute.MAX_HEALTH);
        if (mods == null || mods.isEmpty()) {
            return;
        }
        List<AttributeModifier> list = new ArrayList<>(mods);
        double sum = 0;
        for (AttributeModifier m : list) {
            sum += m.getAmount();
        }
        if (sum <= cap + 1e-9) {
            return;
        }
        double ratio = cap / sum;
        for (AttributeModifier m : list) {
            meta.removeAttributeModifier(Attribute.MAX_HEALTH, m);
            meta.addAttributeModifier(Attribute.MAX_HEALTH, new AttributeModifier(
                    m.getKey(), m.getAmount() * ratio, m.getOperation(), m.getSlotGroup()));
        }
    }

    /**
     * 打收敛标记（幂等：先删旧标记行再打一行新的）。
     *
     * <p>只写**这次真的被压过**的项。全量列一遍的写法会让玩家以为所有数值都被动过 ——
     * 一件只超了吸血的剑，标成「攻 22 / 吸血 20% / 减伤 35% / 气血 20 / 点燃 10s」
     * 既吓人又不准确，服主排查时也分不清到底哪项超标。</p>
     */
    private void stampMarker(ItemMeta meta, List<String> changed) {
        List<net.kyori.adventure.text.Component> lore = meta.lore() == null
                ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.removeIf(c -> plain(c).contains(MARKER));
        if (changed.isEmpty()) {
            // 只动了基础属性（不含攻击，例如攻速/护甲/韧性）—— 也得给句话，
            // 否则玩家看到数值变了却没有任何说明
            changed.add("基础属性");
        }
        lore.add(com.forgemaster.util.Msg.mm("<dark_gray>" + MARKER
                + "：<gray>" + String.join(" · ", changed) + "</gray>"
                + "<dark_gray>（超出当前上限的部分已压回；Lore 上方词条文字未改，重铸可刷新）</dark_gray>"));
        meta.lore(lore);
    }

    /** 某属性的 modifier 合计值（用来判断「这一项这次有没有被压过」） */
    private double attrSum(ItemMeta meta, Attribute attribute) {
        Collection<AttributeModifier> mods = meta.getAttributeModifiers(attribute);
        double sum = 0;
        if (mods != null) {
            for (AttributeModifier m : mods) {
                sum += m.getAmount();
            }
        }
        return sum;
    }

    private int fireOf(ItemMeta meta) {
        Integer v = meta.getPersistentDataContainer().get(Keys.FIRE_TICKS, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    /** 数字显示：去掉没意义的 .0（22.0 → 22，2.4 → 2.4） */
    private String trim(double v) {
        if (Math.abs(v - Math.round(v)) < 1e-9) {
            return String.valueOf(Math.round(v));
        }
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private String plain(net.kyori.adventure.text.Component c) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(c);
    }

    /**
     * 数值签名：只要这些数一个都没变，就说明这件物品已经收敛过。
     *
     * <p>不含 Lore —— Lore 里还留着旧词条文本，那是没法算回来的（见类注释），
     * 拿它当签名会导致每次扫描都判成「有改动」，命令报出来的数就永远不准。</p>
     */
    private String signature(ItemMeta meta) {
        StringBuilder sb = new StringBuilder();
        for (Attribute a : new Attribute[]{Attribute.ATTACK_DAMAGE, Attribute.ATTACK_SPEED,
                Attribute.ARMOR, Attribute.ARMOR_TOUGHNESS, Attribute.MAX_HEALTH}) {
            Collection<AttributeModifier> mods = meta.getAttributeModifiers(a);
            double sum = 0;
            if (mods != null) {
                for (AttributeModifier m : mods) {
                    sum += m.getAmount();
                }
            }
            sb.append(a.name()).append('=').append(Math.round(sum * 1000)).append(';');
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        sb.append("ls=").append(Math.round(nz(pdc, Keys.LIFESTEAL) * 1000)).append(';');
        sb.append("dr=").append(Math.round(nz(pdc, Keys.DAMAGE_REDUCE) * 1000)).append(';');
        Integer fire = pdc.get(Keys.FIRE_TICKS, PersistentDataType.INTEGER);
        sb.append("fire=").append(fire == null ? 0 : fire).append(';');
        return sb.toString();
    }

    private double nz(PersistentDataContainer pdc, org.bukkit.NamespacedKey key) {
        Double v = pdc.get(key, PersistentDataType.DOUBLE);
        return v == null ? 0 : v;
    }
}