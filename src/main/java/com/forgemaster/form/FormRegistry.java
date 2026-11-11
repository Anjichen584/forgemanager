package com.forgemaster.form;

import com.forgemaster.config.Settings;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 剑型配置表：{@code forms.<id>.{display,color,weight,value-mult}}，
 * 整段缺省时落回内置默认。权重为 0 的剑型不参与抽取但保留定义（估值要用）。
 */
public final class FormRegistry {

    private final Map<String, SwordForm> forms = new LinkedHashMap<>();
    private int totalWeight;

    public FormRegistry(Settings settings) {
        load(settings);
    }

    public void load(Settings settings) {
        forms.clear();
        totalWeight = 0;
        ConfigurationSection root = settings.section("forms");
        if (root == null) {
            for (SwordForm f : SwordForm.defaults()) {
                put(f);
            }
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection cs = root.getConfigurationSection(key);
            if (cs == null) {
                continue;
            }
            SwordForm def = forms.containsKey(key) ? forms.get(key) : defOf(key);
            String display = cs.getString("display", def.display());
            String color = cs.getString("color", def.color());
            int weight = Math.max(0, cs.getInt("weight", def.weight()));
            double mult = Math.max(0.1, cs.getDouble("value-mult", def.valueMult()));
            put(new SwordForm(key, display, color, weight, mult));
        }
        if (forms.isEmpty()) {
            for (SwordForm f : SwordForm.defaults()) {
                put(f);
            }
        }
    }

    private SwordForm defOf(String key) {
        for (SwordForm f : SwordForm.defaults()) {
            if (f.id().equals(key)) {
                return f;
            }
        }
        return new SwordForm(key, key, "#9e9e9e", 10, 1.0);
    }

    private void put(SwordForm f) {
        forms.put(f.id(), f);
        totalWeight += f.weight();
    }

    public SwordForm get(String id) {
        return id == null ? null : forms.get(id);
    }

    public List<SwordForm> all() {
        return new ArrayList<>(forms.values());
    }

    public boolean isEmpty() {
        return forms.isEmpty();
    }

    /** 按权重随机抽一个剑型；全零权重时返回第一个 */
    public SwordForm randomPick(Random random) {
        if (forms.isEmpty()) {
            return null;
        }
        if (totalWeight <= 0) {
            return all().get(0);
        }
        int roll = random.nextInt(totalWeight);
        for (SwordForm f : forms.values()) {
            roll -= f.weight();
            if (roll < 0) {
                return f;
            }
        }
        return all().get(all().size() - 1);
    }
}
