package com.forgemaster.shop;

import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.material.MaterialGrade;
import org.bukkit.Material;

/**
 * 商会的一条商品。
 *
 * @param kind      MATERIAL = 按档次的原料；BLUEPRINT = 剑胚图纸；SPECIAL = 特殊道具（护胚符等）
 * @param material  MATERIAL 用的原版材料（即剑胚的 cost-item）；BLUEPRINT 固定为 PAPER；SPECIAL 用图标材质
 * @param grade     MATERIAL 用的档次；其余为 null
 * @param forge     对应的剑胚；SPECIAL 为 null
 * @param unitPrice 单价（剑币）
 * @param id        SPECIAL 的道具 id（如 {@code talisman}）；其余为 null
 */
public record ShopEntry(Kind kind, Material material, MaterialGrade grade, ForgeMaterial forge,
                        long unitPrice, String id) {

    /** 兼容旧调用：不带特殊 id */
    public ShopEntry(Kind kind, Material material, MaterialGrade grade, ForgeMaterial forge,
                     long unitPrice) {
        this(kind, material, grade, forge, unitPrice, null);
    }

    public enum Kind {
        MATERIAL,
        BLUEPRINT,
        SPECIAL
    }
}
