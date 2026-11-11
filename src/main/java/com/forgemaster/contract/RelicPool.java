package com.forgemaster.contract;

import com.forgemaster.config.Settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 圣物定义表：内置 12 个 ForgeTax 风格圣物，config 里 {@code contract.relics.disabled}
 * 可以拉黑。圣物不落 config 定义——效果挂在代码钩子上，config 只管开关。
 */
public final class RelicPool {

    /** 单个圣物定义：id / 名 / 描述（MiniMessage）/ 稀有度 1-5 */
    public record Def(String id, String name, String desc, int rarity) {
    }

    private final Map<String, Def> defs = new LinkedHashMap<>();
    private final List<String> disabled = new ArrayList<>();

    public RelicPool(Settings settings) {
        this();
        load(settings);
    }

    public void load(Settings settings) {
        disabled.clear();
        List<String> off = settings.strList("contract.relics.disabled", java.util.List.of());
        if (off != null) {
            disabled.addAll(off);
        }
    }

    public RelicPool() {
        for (Def d : defaults()) {
            defs.put(d.id(), d);
        }
    }

    public static Def[] defaults() {
        return new Def[]{
                new Def("bent-nail", "弯钉", "强化成功率 <white>+4%</white>", 1),
                new Def("lucky-coal", "幸运煤", "大成概率 <white>+5%</white>", 2),
                new Def("black-knot", "黑结", "碎裂率 <white>-10%</white>", 1),
                new Def("dragonscale", "龙鳞片", "碎裂率再 <white>-15%</white>", 3),
                new Def("inspection-stamp", "验讫章", "剑估值 <white>+15%</white>", 2),
                new Def("sealed-rebate", "税章回执", "税额 <white>-10%</white>", 3),
                new Def("giants-spine", "巨人脊椎", "炉魂狂热 <white>+2</white> 次", 2),
                new Def("wax-thread", "蜡线", "损伤/空锤<white>不断连击</white>", 3),
                new Def("golden-cog", "金齿轮", "炉火每次失败多攒 <white>2</white> 点", 2),
                new Def("unyielding-stone", "不灭基石", "基石护体可用 <white>2</white> 次", 4),
                new Def("phoenix-ash", "凤凰灰", "每个周目日第一次碎裂<white>免碎</white>（等级减半）", 5),
                new Def("broken-oath", "残誓", "剑魂传承 <white>+15%</white>", 2),
        };
    }

    public Def get(String id) {
        return defs.get(id);
    }

    public boolean enabled(String id) {
        return defs.containsKey(id) && !disabled.contains(id);
    }

    public List<Def> all() {
        List<Def> list = new ArrayList<>();
        for (Def d : defs.values()) {
            if (!disabled.contains(d.id())) {
                list.add(d);
            }
        }
        return list;
    }

    /** 抽 n 个不重复的圣物，排除已有；池子不够时能抽多少算多少 */
    public List<Def> offer(List<String> owned, int n, Random random) {
        List<Def> candidates = new ArrayList<>();
        for (Def d : all()) {
            if (!owned.contains(d.id())) {
                candidates.add(d);
            }
        }
        List<Def> offer = new ArrayList<>();
        while (offer.size() < n && !candidates.isEmpty()) {
            offer.add(candidates.remove(random.nextInt(candidates.size())));
        }
        return offer;
    }
}
