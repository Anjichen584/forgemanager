package com.forgemaster.relicset;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.selftest.SelfTestSuite;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.TreeMap;

/**
 * 套装矩阵（v2.23 P1-5）的自检项：全部只读、纯函数优先。
 *
 * <p>注册方式（外部模块口）：
 * <pre>{@code
 * com.forgemaster.relicset.SelfTestRelicSet.register(suite);
 * }</pre>
 * 建议挂在 {@code SelfTestSuite#register()} 尾部或 {@code ForgingCommand#selftest}
 * 的 {@code new SelfTestSuite(plugin)} 之后；运行入口 {@code /zhujian selftest}。</p>
 *
 * <p>四个用例：</p>
 * <ol>
 *   <li>内置五套的判定材料 id 都能在 {@code materials} 注册表找到（找不到的套永远穿不齐）；</li>
 *   <li>件数判定是纯函数：2 / 4 两档的套装穿 3 件只吃 2 件档，不吃满；</li>
 *   <li>内置与配置的合并顺序：内置在前、配置同 id 整段覆盖（位置不后移）、新 id 追加尾部、空档位段不覆盖；</li>
 *   <li>GUI 标题与格子数纯函数：54 格、21 个套装槽、返回 / 关闭槽不与套装槽重叠、标题拼接正确。</li>
 * </ol>
 */
public final class SelfTestRelicSet {

    private SelfTestRelicSet() {
    }

    /** 把本模块的自检项挂进套件（SelfTestSuite.addExternal 的外部调用点） */
    public static void register(SelfTestSuite suite) {
        suite.addExternal("v2.23 套装矩阵", "内置五套的判定材料 id 都能在 materials 注册表找到",
                SelfTestRelicSet::checkBuiltinMaterials);
        suite.addExternal("v2.23 套装矩阵", "件数判定纯函数：穿 3 件只吃 2 件档，不吃满档",
                SelfTestRelicSet::checkTierPure);
        suite.addExternal("v2.23 套装矩阵", "内置与配置覆盖的合并顺序",
                SelfTestRelicSet::checkMergeOrder);
        suite.addExternal("v2.23 套装矩阵", "GUI 标题 / 格子数纯函数",
                SelfTestRelicSet::checkGuiPure);
    }

    // ------------------------------------------------------------ 用例 1：材料 id 注册表

    private static void checkBuiltinMaterials() throws Exception {
        ForgingMasterPlugin plugin = resolvePlugin();
        List<RelicSetService.RelicSet> builtins = RelicSetService.defaultSets();
        if (builtins.size() != 5) {
            throw new IllegalStateException("内置套装应为 5 套，实际 " + builtins.size());
        }
        for (RelicSetService.RelicSet set : builtins) {
            if (plugin.materials() == null || plugin.materials().get(set.id()) == null) {
                throw new IllegalStateException("内置套装 " + set.id() + " 的材料 id 在 materials 注册表里找不到");
            }
        }
    }

    /** 运行中的 ForgingMasterPlugin 实例（SelfTestSuite 未暴露 plugin 句柄，扫注册表拿） */
    private static ForgingMasterPlugin resolvePlugin() throws Exception {
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (p instanceof ForgingMasterPlugin master && p.isEnabled()) {
                return master;
            }
        }
        throw new IllegalStateException("ForgingMasterPlugin 未启用，无法做注册表检查");
    }

    // ------------------------------------------------------------ 用例 2：件数判定纯函数

    private static void checkTierPure() throws Exception {
        // 与内置套 / 星陨同构的两档套装：2 件档 +3 护甲，4 件档 +8 护甲 + 落星斩
        RelicSetService.RelicSet set = new RelicSetService.RelicSet("selftest", "自检套", 30,
                java.util.Collections.unmodifiableNavigableMap(tiers(
                        2, new RelicSetService.RelicTier(3.0, null),
                        4, new RelicSetService.RelicTier(8.0, "fallen-star"))));
        expect(RelicSetService.tierFor(0, set.tiers()) == 0, "0 件应无档位");
        expect(RelicSetService.tierFor(1, set.tiers()) == 0, "1 件应无档位");
        expect(RelicSetService.tierFor(2, set.tiers()) == 2, "2 件应吃到 2 件档");
        expect(RelicSetService.tierFor(3, set.tiers()) == 2, "3 件只应吃到 2 件档（不吃满 4 件档）");
        expect(RelicSetService.tierFor(4, set.tiers()) == 4, "4 件应吃满 4 件档");
        expect(RelicSetService.tierFor(9, set.tiers()) == 4, "超穿也应停在最高档");
    }

    private static TreeMap<Integer, RelicSetService.RelicTier> tiers(
            int need1, RelicSetService.RelicTier t1, int need2, RelicSetService.RelicTier t2) {
        TreeMap<Integer, RelicSetService.RelicTier> m = new TreeMap<>();
        m.put(need1, t1);
        m.put(need2, t2);
        return m;
    }

    // ------------------------------------------------------------ 用例 3：合并顺序

    private static void checkMergeOrder() throws Exception {
        RelicSetService.RelicSet ironBuiltin = RelicSetService.defaultSets().get(0);
        // 同 id 的配置覆盖（换名换档），全新 id 追加尾部，空档位的配置段不覆盖
        RelicSetService.RelicSet ironOverride = new RelicSetService.RelicSet("iron", "配置铁", 15,
                java.util.Collections.unmodifiableNavigableMap(tiers(
                        3, new RelicSetService.RelicTier(9.0, null),
                        5, new RelicSetService.RelicTier(15.0, null))));
        RelicSetService.RelicSet custom = new RelicSetService.RelicSet("custom", "自定义", 30,
                java.util.Collections.unmodifiableNavigableMap(tiers(
                        2, new RelicSetService.RelicTier(1.0, null),
                        4, new RelicSetService.RelicTier(2.0, null))));
        RelicSetService.RelicSet emptyTiers = new RelicSetService.RelicSet("gold", "空档位", 30,
                java.util.Collections.unmodifiableNavigableMap(new TreeMap<>()));

        List<RelicSetService.RelicSet> merged = RelicSetService.merge(
                RelicSetService.defaultSets(), List.of(ironOverride, emptyTiers, custom));

        expect(merged.size() == 6, "覆盖 iron + 追加 custom 后应为 6 套，实际 " + merged.size());
        expect("iron".equals(merged.get(0).id()), "iron 应保持在第 0 位（覆盖不后移）");
        expect("配置铁".equals(merged.get(0).name()), "iron 应被配置段整段覆盖（新名）");
        expect(merged.get(0).tiers().containsKey(5), "覆盖后的档位应来自配置（5 件档）");
        expect("custom".equals(merged.get(5).id()), "custom 应追加在尾部");
        // 空档位段没有覆盖 gold：gold 仍是内置名与内置档位
        RelicSetService.RelicSet gold = null;
        for (RelicSetService.RelicSet s : merged) {
            if ("gold".equals(s.id())) {
                gold = s;
            }
        }
        expect(gold != null && "鎏金".equals(gold.name()), "空档位的配置段不应覆盖内置鎏金");
        expect(gold != null && gold.tiers().containsKey(2) && gold.tiers().containsKey(4),
                "内置鎏金的 2 / 4 件档位应保留");
        // 内置原对象不被合并改动
        expect("铁卫".equals(ironBuiltin.name()), "合并不得改写内置定义本身");
    }

    // ------------------------------------------------------------ 用例 4：GUI 纯函数

    private static void checkGuiPure() throws Exception {
        expect(RelicSetGui.SIZE == 54, "GUI 应为 54 格（6 行）");
        expect(RelicSetGui.LIST_SLOTS.length == 21, "列表页套装槽位应为 21 个");
        for (int slot : RelicSetGui.LIST_SLOTS) {
            expect(slot >= 0 && slot < RelicSetGui.SIZE, "套装槽位 " + slot + " 越界");
            expect(slot != RelicSetGui.BACK_SLOT && slot != RelicSetGui.CLOSE_SLOT
                    && slot != RelicSetGui.INFO_SLOT, "套装槽位 " + slot + " 与功能槽冲突");
        }
        String list = RelicSetGui.listTitle(6);
        expect(list.contains("6"), "列表页标题应含套装总数");
        String detail = RelicSetGui.detailTitle("星陨", 3);
        expect(detail.contains("星陨") && detail.contains("3/4"), "详情页标题应含套装名与收集进度");
        expect(RelicSetService.skillDisplay("swift-burst").equals("疾风爆发")
                && RelicSetService.skillDisplay("heavy-reap").equals("重击吸血")
                && RelicSetService.skillDisplay("unknown-x").equals("unknown-x"),
                "技能显示名映射不正确");
    }

    // ------------------------------------------------------------ 工具

    private static void expect(boolean cond, String msg) throws Exception {
        if (!cond) {
            throw new IllegalStateException(msg);
        }
    }
}
