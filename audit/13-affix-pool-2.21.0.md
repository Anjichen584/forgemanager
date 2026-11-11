# v2.21.0 · 词条乱到胸甲上出暴击率

服主：**「词条都是乱的，锻造个胸甲会出暴击率词条，就算是全局生效，那也有点说不过去吧」**

先说结论：**不是「全局生效」那么简单，它在胸甲上是彻头彻尾的死属性。**
`CombatListener` 只读主手物品的 PDC（第 36~50 行），护甲上的暴击率写进了 PDC、
写进了 Lore，然后永远没有任何代码去读它。

而这个 bug 顺着查下去，牵出了三个：

| # | 病 | 影响 |
| --- | --- | --- |
| 1 | `buildDirect` 给护甲用淬火池（全攻击向） | 胸甲出暴击率/吸血/火焰 |
| 2 | `pick()` 抽不满就回退**整张词条表** | 剑上出「护甲值」，护甲出攻击词条 |
| 3 | 护甲词条 modifier 键不带部位 | **同一词条出在两件护甲上，只有一件生效** |

自检 402 → **406**。

---

## 一、三条路径，只有一条是对的

护甲词条有三条写入路径：

| 路径 | 选池方式 | 修前 |
| --- | --- | --- |
| 正常锻造 `settle` | `pickStrict(ARMOR_AFFIX_POOL)` | ✅ 对 |
| 重铸 `Reforge` | `reforge.armor-pool` | ✅ 对（含 aegis，我一开始看漏了） |
| `buildDirect`（`/zhujian give`、`givefull`） | `pick(quench.pool())` | ❌ **无护甲分支** |

第三条：

```java
for (Affix a : affixes.pick(quench.pool(), count)) {
```

`quench.pool()` 是淬火介质的池子——油淬 `[attack_damage, crit_chance]`、
血淬 `[lifesteal, crit_damage]`。全是攻击向。护甲走这条路必然出暴击率。

而且 **`givefull` 是我上一轮刚写的**，沿用 `buildDirect`，所以我自己也在发带暴击的胸甲。

## 二、更严重的是 `pick()` 的兜底

```java
while (result.size() < count && !candidates.isEmpty()) { ... }
if (result.size() < count) {
    List<Affix> fallback = new ArrayList<>(affixes.values());   // ← 整张表
```

池子抽不满时**回退到整张词条表**。而淬火池只有 2~3 条，精良以上品质要 4~6 条：

| 淬火 | 池深 | 品质要 | 从全表捞 |
| --- | --- | --- | --- |
| 水淬 | 2 | 精良 4 | 2 条（可能是护甲值、生命上限） |
| 油淬 | 2 | 史诗 5 | 3 条 |
| 冰淬 | 3 | 传说 6 | 3 条 |

所以**剑上会出「护甲值」**——这才是「词条都是乱的」说的那件事。
服主最初注意到的是胸甲出暴击率，但反过来的方向伤害更大：剑抽到防御词条，
等于白瞎一个词条位。

修法：加了 `WEAPON_AFFIX_POOL`（整表 13 条减掉护甲 5 条 = 8 条武器向），
`pick(pool, count, fallbackIds)` 只在武器向里补。

## 三、护甲词条撞键：v2.10 起就没生效过

改完池子，自检立刻炸了两条 v2.10 的老断言。看了一眼，**是断言对、代码错**：

```
护甲应带 4 个不同修饰符，实得 5 个：[fm_armor_leggings, fm_armor_helmet,
fm_armor_plate, fm_armor_boots, fm_armor_chestplate]
```

`fm_armor_plate` 是「护甲值」词条的 modifier 键——**不带部位**。

护甲是一次穿四件的。两件都 roll 到「护甲值」，键完全相同，原版按键去重，
**后面那件直接失效**。玩家看到 Lore 上两件都写着「护甲值 +1.4」，
实际只有一件算数。

为什么这条一直没被发现：自检里「四件穿一起不会因为同一个键互相顶掉」
只检查 `Attribute.ARMOR` 上的键，而护甲上原本全是攻击词条（bug 1），
压根没有护甲属性词条参与这项检查。**两个 bug 互相掩护。**

修法：
1. 新造护甲的键改成 `fm_<词条id>_<部位>`
2. `rebalanceArmor` 里加 `migrateAffixKeys`，把老物品的 `fm_<词条id>`
   原地改名成带部位的键——**数值不动，搬家而已，不改变任何玩家已有强度**
3. `ARMOR_REBALANCE_VERSION` 1 → 2，让已经校准过的老装备再跑一遍

## 四、收口：不再依赖「每个调用点都记得」

三处调用点修完，但我没有就此收手。`buildDirect` 这个 bug 活了一整年，
说明「每个调用点自己选对池子」不是可靠的设计。

所以在 `ItemFactory` **真正写 PDC 的那一个点**上加了一道守卫：

```java
if (armor) {
    for (迭代 affixes) {
        if (!ARMOR_AFFIX_POOL.contains(a.id())) {
            it.remove();
            log.warning("护甲抽到了非防御词条 X，已丢弃（调用点选错了词条池）");
        }
    }
}
```

位置很讲究——必须在**暴击/吸血/火焰累加之前**。第一版我插在累加之后，
那样词条虽然摘掉了，但 `crit += value` 已经算过，PDC 里的数值照样留着。

## 五、断言

| # | 断言 |
| --- | --- |
| 1 | 浅池（2 条）+ 要 6 条 → 只用武器池补，不漏防御词条、不出重复 |
| 2 | `buildDirect` 连造 6 件胸甲：词条全在防御池、CRIT_CHANCE 和 LIFESTEAL 都是 0 |
| 3 | `buildDirect` 连造 6 把剑：词条全不在防御池 |
| 4 | 四件护甲的基础护甲键各自存在且互不相同 |
| 5 | 两件都挂「护甲值」→ 键必须一个带 `_chestplate`、一个带 `_leggings` |

第 4 条是从老的 `seen.size() == 4` 改来的：护甲现在正常带防御词条，
键数必然多于 4，硬要 ``== 4`` 反而会挡住正确行为。断言要守的是
**唯一性和存在性**，不是那个具体数字。

---

## 六、顺手清掉一个假的修复

我一度以为 `reforge.armor-pool` 漏了 `aegis`（锻造池 5 条、重铸池 4 条），
连 `step20` 迁移都写好了。写的时候断言炸了，回去看原文——

```yaml
armor-pool:
  - armor_plate
  - tough_plate
  - vitality
  - knockback_resist
  - aegis          # ← 一直都在
```

是我前面用 `-Context 0,4` 只截了 4 行，把第 5 条切掉了。
**没漂移，`step20` 是假的，已删除，`CURRENT_VERSION` 保持 10。**

教训：`grep -A N` 的 N 是「显示几行」，不是「这里一共几行」。

---

## 七、没动的

- **老物品的词条值不能重算**：`migrateAffixKeys` 只换键名。
  如果一件老胸甲当年 roll 的是攻击词条，那个词条仍然在，仍然是死属性。
  要清理得逐件重 roll，那会改变玩家资产，不做。
- **`pick(pool, count)` 两参版保留**了旧的整表兜底：`SpiritManager` 和
  `ForgingCommand` 的命令预览还在用。它们不产出物品，风险低，
  但哪天要在那些路径上产出成品，记得换成三参版。
