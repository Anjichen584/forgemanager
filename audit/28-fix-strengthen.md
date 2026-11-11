# 修复报告：强化结算（StrengthenService）

- 版本标记：v2.23.1
- 唯一改动文件：`src/main/java/com/forgemaster/strengthen/StrengthenService.java`
- 来源：`audit/23-bughunt-core.md` 的 A / B / C 三条（行号为核实时实测值，与本文件记录一致）
- 未运行 `mvn`（按分工由 Lead 统一编译）

---

## A【P1】碎裂被稳胚符托住时 `toLevel` 返回错值 —— 已修

### 位置
`src/main/java/com/forgemaster/strengthen/StrengthenService.java`
护胚符分支：原 `1226-1240` 行（修改后为 `1235-1256` 行）

### 根因
`tier == Tier.BREAK` 且玩家有护胚符时，这一锤有三种结局，**落锤后的等级并不相同**：

| 情形 | 物品实际等级 | 是否 `writeLevel` |
| --- | --- | --- |
| 还有凤凰灰（前置分支） | `level / 2` | 是 |
| 护胚符 + 稳胚符 | 仍是 `level` | **否** |
| 护胚符，无稳胚符 | `level - 1` | 是 |

但原代码在「护胚符」的两种结局上只写了一份 return，`toLevel` 无条件算成 `Math.max(0, level - 1)`：

```java
if (Charm.enabled(plugin, Charm.STEADY) && Charm.count(player, Charm.STEADY) > 0) {
    Charm.take(player, Charm.STEADY, 1);
    note.append("，<#6bd3ff>稳胚符</#6bd3ff>又托住了等级");   // ← 这里没有 writeLevel，等级不变
} else {
    int dropped = Math.max(0, level - 1);
    writeLevel(item, dropped, cap);
    note.append("，掉到 <white>+").append(dropped).append("</white>");
}
apply(item);
return new StrikeResult(Tier.BREAK, level, Math.max(0, level - 1), cap, comboNow, threshold,
        false, false, cost, need, ...);                       // ← 两种结局都报 level-1
```

### 改法
抽出 `keptLevel` 表示「落锤后物品真实等级」，两种结局各写各的：

```java
int keptLevel = level;
if (Charm.enabled(plugin, Charm.STEADY) && Charm.count(player, Charm.STEADY) > 0) {
    Charm.take(player, Charm.STEADY, 1);
    note.append("，<#6bd3ff>稳胚符</#6bd3ff>又托住了等级");
} else {
    keptLevel = Math.max(0, level - 1);
    writeLevel(item, keptLevel, cap);
    note.append("，掉到 <white>+").append(keptLevel).append("</white>");
}
apply(item);
return new StrikeResult(Tier.BREAK, level, keptLevel, cap, comboNow, threshold,
        false, false, cost, matUsed, ...);
```

### 改后行为（调用方已逐个核对，未被误判）
- `AnvilForgeManager.oneLine`（`anvil/AnvilForgeManager.java:582`）按 `toLevel == fromLevel` 走两分支：
  - 稳胚符托住 → `toLevel == fromLevel` → 渲染 `<dark_red>碎了</dark_red> +12`（等级没降，如实显示「碎了但没掉级」）；
  - 没稳胚符 → `toLevel = level-1` → 渲染 `碎了 +12→+11`（与新等级一致）。
  原来两种都渲染成 `+12→+11`，而物品还是 `+12`。
- `milestone`（`:590`）对 `BREAK` 恒定 `true`，聊天里仍会出一条结果行 —— 语义不变（确实发生了碎裂判定）。
- `broken` 仍然 `false`（剑没碎），`AnvilForgeManager.settle` 的 `broken[0]` 只由 `onBroken` 回调置真（`:538-543`），所以砧位不会被误清、不会误删剑。
- 真碎分支（`addInt(Keys.STRENGTHEN_BROKEN, 1)` + `recordDestruction` + `onBroken`）与凤凰灰分支**一字未动**，`tier` 依旧是 `BREAK`。

### 自检建议
`/zhujian selftest` 里加一条：给剑塞 1 张护胚符 + 1 张稳胚符，把碎率拉到 100%（或直接构造 `Tier.BREAK` 分支），断言 `r.broken() == false && r.toLevel() == r.fromLevel() == 原等级`，且 `levelOf(sword)` 未变。

---

## B【P2】剑尘抵扣时虚报材料消耗 —— 已修

### 位置
`src/main/java/com/forgemaster/strengthen/StrengthenService.java`
扣减块 `1093-1103`（新增 `matUsed/costText` 于 `1104-1112`）、成功文案原 `1173` 行、7 处 `StrikeResult` 构造

### 根因
材料不够时用剑尘按 1:1 抵差额，**只从背包扣 `need - dustUse` 个材料**，差额由剑尘出：

```java
MaterialGrades.consume(player, costItem, need - dustUse);   // 实际扣的材料数
if (dustUse > 0) { DissolveService.takeDust(player, dustUse); }
```

但对外一致报 `need`：文案写「花掉 `need` 个」（原 `1173`），`StrikeResult.materials` 也填 `need`（7 处）。
于是「材料 3/5 + 剑尘抵 2」时玩家被告知花掉 5 个铁锭，`AnvilForgeManager.strikeBatch` 的
`materials += r.materials()`（`:400`）也按 5 个累加，批量总结（`:423`）里的消耗数是虚的。

### 改法
新增单一真值来源，文案与 `materials` 都用它：

```java
int matUsed = costItem == null ? 0 : Math.max(0, need - dustUse);
String costText = costItem == null
        ? " · 没有材料消耗"
        : " · 花掉 " + matUsed + " 个 " + MaterialGrades.pretty(costItem)
                + (dustUse > 0 ? " + 剑尘 " + dustUse : "");
```

- 成功文案：`+ " · 花掉 " + need + " 个 " + pretty(costItem)` → `+ costText`。
- 7 处 `new StrikeResult(..., false, cost, need, ...)` → `..., cost, matUsed, ...`
  （成功族、凤凰灰、真碎、护胚符、空锤、损伤-稳胚、损伤-掉落）。
- `StrikeResult` 记录定义本文件内（`:594`），但**未新增字段**：`materials` 语义本应就是「实际扣掉的材料数」，
  改值即可，不动 record 结构，避免影响其它读取方。

### 改后行为
- 无剑尘抵扣时 `matUsed == need`，文案与原来完全一致（零回归）。
- 有剑尘抵扣时：`花掉 3 个铁锭 + 剑尘 2`，`materials == 3`，批量总结累加的是真实材料数。
- `costItem == null`（材料段配漏）时文案为「没有材料消耗」、`materials == 0`，不再对 `pretty(null)` 输出「未知材料」并报一个不存在的消耗。
- `Keys.STRENGTHEN_INVEST` 仍加 `need`（这是「投入强度」语义，剑尘等价于材料投入，未纳入本次范围）。

---

## C【P2】`baseOf` 把已放大的 modifier 当 +0 基准 —— **未修，存疑已澄清**

### 位置
`src/main/java/com/forgemaster/strengthen/StrengthenService.java:1935`（`baseOf`）
调用点 `:1912-1919`，写 modifier 在 `:1916`，写快照在 `:1918`

### 现场
```java
private double baseOf(ItemMeta meta, NamespacedKey snapshotKey, Attribute attribute,
                      String modifierKey, double fallback) {
    Double stored = meta.getPersistentDataContainer().get(snapshotKey, PersistentDataType.DOUBLE);
    if (stored != null) return stored;                       // ① 快照
    Collection<AttributeModifier> mods = meta.getAttributeModifiers(attribute);
    for (AttributeModifier m : mods) {
        if (modifierKey.equals(m.getKey().getKey())) return m.getAmount();   // ② 现有 modifier
    }
    return fallback;                                          // ③ 当前配置
}
```

### 为什么「按倍率除回去」的修法不能落地（推理不闭环）
设物品 modifier 的写入值为 `round(base × mult_written)`，而
`mult = (1 + bonusRatio(level)) * evoMult`（`:1893`），`mult` 由 `apply()` **当前** 的 `level` 决定。

1. **除的对象不对**：`strike()` 先 `writeLevel(item, next, cap)` 再 `apply(item)`（原 `1135→1139`），
   所以 `apply()` 里的 `level` 已经是 **新等级**，而 modifier 是**上一等级**写的：
   `mult_written = mult(L_old)`，当前 `mult = mult(L_new)`。
   `amount / mult(L_new) = base × mult(L_old)/mult(L_new) > base` —— **除完仍然偏大**，不是精确反推。
2. **会把正常老物品改弱**：`baseOf` 的 fallback 分支本就是给「没有快照的老物品」用的，
   而按本方法 javadoc（`:1926-1933`）的约定，老物品 modifier 记录的是 **等级 0 的原值**（`amount == base`）。
   对它除以 `mult > 1` 得到 `base / mult`，等于把老物品的基础属性按强化等级削一遍 ——
   正是 javadoc 明文承诺「不会被新配置的基础属性削一遍」要避免的事，属于**新引入的经济/数值回归**。
3. **真正会膨胀的窗口极窄且不可判定**：`ItemFactory` 在建物品时就写全了四个快照
   （`item/ItemFactory.java:297-301` 写 `BASE_ARMOR/BASE_TOUGH/BASE_ATK/BASE_SPEED`），
   而 `apply()` 每次都在写 modifier 之后立刻补写快照（`:1918`）。
   因此「有 modifier 但没快照」只可能是：① 快照键出现之前铸的老物品；② 快照被外部剥掉的物品
   （`selftest/SelfTestSuite.java:4562-4565` 就在模拟这种物品）。
   两者在 PDC 上**完全无法区分**，靠 `baseOf` 内部信息判不出来。
4. 唯一能判的补充信号是 `Keys.STRENGTHEN` 等级，但老物品同样有等级，区分不出来。

**结论：不闭环，按「宁可不改」处理。** 强行除以 `mult` 会拿「罕见过大」换「普遍过小」，
而且对正常掉落/新铸物品是必现回归，代价明显更大。

### 若要彻底修，建议的闭环路线（留给后续，不在本次范围）
不要猜，而是**让快照缺失变成一次性事件并补写**：
在 `apply()` 里检测 `stored == null`，用 `Math.min(现有 modifier 值, fallback)` 作为基准
（`fallback` 与 `ItemFactory:300` 同式 `mat.bonusAttack() * q.power()`，配置未改时与真实 `base` 相等），
并把结果**立刻写进 PDC**，此后永远走快路径。
- 老物品（`amount == base`）：`min` 取原值，不被削；配置调高时也保原值（符合 javadoc 承诺）。
- 被放大过的物品（`amount = base × mult > base`）：`min` 取 `fallback`，膨胀纠正。
- 残留风险仅剩「管理员主动下调 `materials.*.bonus-attack`」这一种 —— 那是可回滚的配置动作，
  与「不可逆的属性膨胀」相比可以接受。

本次不动它的理由：改的是全服既有物品的基础数值语义，需要 Lead 拍板 + 一次全量物品体检
（`/zhujian strengthen rebuild` 之类的既有修复入口，见 `:1724-1770` 附近的重算逻辑），
不适合夹在 P1/P2 修复里静默上线。

---

## 改动清单

| 文件 | 位置（修改后行号） | 内容 |
| --- | --- | --- |
| `strengthen/StrengthenService.java` | `1104-1112` | 新增 `matUsed` / `costText`（材料消耗真值） |
| 同上 | `1182` | 成功文案改用 `costText` |
| 同上 | `1187` | 成功族 `materials = matUsed` |
| 同上 | `1214` | 凤凰灰分支 `materials = matUsed` |
| 同上 | `1230` | 真碎分支 `materials = matUsed` |
| 同上 | `1235-1256` | **A 修复**：`keptLevel` 分开记 + `materials = matUsed` |
| 同上 | `1265` | 空锤 `materials = matUsed` |
| 同上 | `1274` | 损伤-稳胚 `materials = matUsed` |
| 同上 | `1283` | 损伤-掉落 `materials = matUsed` |

- 未改任何公开签名、未加依赖、未动 `StrikeResult` 结构、未动 `AnvilForgeManager` 等其它文件。
- 全部 `need` 的剩余引用都是有意保留的：`MaterialGrades.consume(..., need - dustUse)`（`:1095`）、
  `addInt(Keys.STRENGTHEN_INVEST, need)`（`:1116`）、材料不足的拒绝提示（`:1053-1055`）。
- 括号平衡已核对（`{` 388 / `}` 388）；未使用 import 无新增（`MaterialGrades` 原已在用）。

## 建议的验证顺序（交给 Lead 编译后）
1. `mvn -B -q compile`
2. `/zhujian selftest`（若有强化相关断言）
3. 手动三条：
   - 材料不足 + 有剑尘 → 看文案是否「花掉 N 个 + 剑尘 M」，`N + M == need`，背包材料确实只少 N 个；
   - 护胚符 + 稳胚符 + 高碎率 → ActionBar 显示「碎了 +12」而非「碎了 +12→+11」，物品仍是 +12；
   - 批量落锤总结的「消耗」与背包实际减少量一致。