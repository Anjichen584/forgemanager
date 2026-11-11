# 27 · 修复记录（v2.23.1）· 锻造核心 / 战斗监听 / 界面监听

修复工程师：`fixer-forge`　｜　目标版本：v2.23.0 → **v2.23.1**
改动文件（严格在授权范围内）：

| 文件 | 改动条数 |
|---|---|
| `src/main/java/com/forgemaster/forge/ForgeManager.java` | A、B |
| `src/main/java/com/forgemaster/listener/CombatListener.java` | C、D |
| `src/main/java/com/forgemaster/listener/ForgeListener.java` | E、F（Lead 追加的 onDrag） |

未触碰：`AnvilForgeManager.java`、`StrengthenService.java`、`Attrs.java`、`ItemFactory.java`、`Reforge.java`、`config.yml`、`ConfigMigrations.java`、`SelfTestSuite.java`、`README.md`、`metrics/`、`webhook/`。
未新增依赖（只用 Paper API + JDK），未改动任何公开方法签名，未运行 `mvn`（`target/` 留给 Lead）。

编译自检方式：`javac -proc:none -encoding UTF-8 -d %TEMP% -cp "<~/.m2 全部 jar>" -sourcepath src/main/java` 单独编译这三个文件 → **我的三个文件零错误**（剩余报错只有 `com.forgemaster.webhook.*`，是别人正在改的包；另有一条既有的 `DamageModifier.ARMOR` deprecation 提示，位于未改动的 `onWearerDamaged`）。

---

## A【P1】研磨修复会复制物品（同胚同耐久被覆盖成克隆）

### 位置
- `ForgeManager.beginRepair` — 记录槽位：`ForgeManager.java:257`
- `ForgeManager.finishRepair` — 取值并传递：`ForgeManager.java:1036-1068`
- `ForgeManager.replaceRepaired` — 重写：`ForgeManager.java:1095-1126`
- 新增字段 `repairSlots`：`ForgeManager.java:65-73`；清理点 `removeSession`：`ForgeManager.java:634-644`

### 根因
旧 `replaceRepaired` 按「`materialId` + 旧耐久」**全背包线性扫**，只比对这两项就 `inv.setItem(i, repaired)`。
背包里两把同胚剑（同 `material_id`）且耐久恰好相同（同一次副本里挨了同样刀数）时，研磨 A 会命中 B：
- A 原地不动（仍是磨损状态，但已被"修好"的克隆存在）；
- B 被覆盖成 A 的克隆（耐久已满） → **白送一件满强化成品**；
- B 自身的强化等级 / 品质 / 词条 / 宝石 / `FORGER` 一并消失 → **吞物品**。

### 改法
1. `beginRepair` 把 `player.getInventory().getHeldItemSlot()` 记进 `repairSlots`（键 = 玩家 UUID）。
   放在 `ForgeManager` 而不是 `ForgeSession`：同一玩家同时只有一个会话，语义等价，且不必改会话类的公开面。
2. `finishRepair` 在 `removeSession`（会清这张表）**之前**取出 `repairSlot`，取不到则 `-1`。
3. `replaceRepaired` 改为只认槽位，并做三重校验：
   - 该格仍是锻造品（`items.isForged`）；
   - `materialIdOf` 与修好的那件一致；
   - `durabilityOf == beforeDurability`（修复前的值）。
   任一不满足 → 返回 `false`，**不再做任何模糊匹配**。
4. 返回 `false` 时走既有 `giveBack` 路径：主手空则放主手，否则 `addItem`，塞不下的 `dropItemNaturally` 掉在脚下；聊天提示改为「原来的那一格已经不是这件装备了（被挪走或丢掉），修好的这件已发给你。」

### 改后行为
- 槽位没变（正常流程）→ 原地替换，一次替换、零复制。
- 槽位变了（换手 / 丢出 / 被 `/clear` / 被别的插件挪走）→ **多一件**：修好的成品发给玩家，原物该在哪还在哪。宁可多一件也不复制——这是本次明确取舍。
- `repairSlots` 随 `removeSession` 清理（cancel / discard / finishRepair / closeAfterResult / maybeExplode 全覆盖），不会随玩家数只增不减。

### 自检建议
1. 准备两把同胚同耐久的剑（如两把「+50 青锋·精良」耐久都是 300），把 A 放 1 号槽、B 放 2 号槽，手持 1 号槽跑一次研磨：预期 **1 号槽被修好、2 号槽原封不动**，背包里仍是两把不同的剑。
2. 反复磨：每次只磨损手持那一把的耐久，另一把耐久不变。
3. 研磨途中把剑丢到地上（或 `/clear`），结算后：地上/原处不变，背包多出一把修好的 → 提示「原来的那一格已经不是这件装备了」。
4. 反例回归：正常单剑研磨仍是原地替换（不产生第二把）。
5. `Selftest`：`SelfTestSuite` 里若有研磨用例，应仍通过（`replaceRepaired` 为私有，签名变更不外泄）。

---

## B【P1】RESULT 阶段断线 → 成品静默销毁

### 位置
- `ForgeManager.discard`：`ForgeManager.java:1978-2012`
- 新增 `deliverResultOnDiscard`：`ForgeManager.java:2014-2051`
- 新增 `offlineDropLocation` / `loadedWorldOf`：`ForgeManager.java:2053-2087`
- 新增「最后站位」快照：`ForgeManager.java:670-674`（`tickSession`，字段在 `75-82`）；清理点同 A 的 `removeSession`

### 根因
`settle` 已经把成品写进 `session.result`、扣完全部材料与锻造费、抽完词条，甚至清掉了玩家 PDC 的剑魂传承（`applyInherit`）。
此后玩家在这几秒里掉线 / 被反作弊踢 / `/kick`，`tickSession` 的离线判定走 `discard(s)`，而 `discard` **不调用** `giveResult`——
`cancel` / `onInventoryClosed` / `shutdown` 三条路都会发，唯独 `discard` 不发：成品连同已清零的剑魂传承永久消失，聊天与日志里一个字都没有。

### 改法
1. `discard` 的第一行调用 `deliverResultOnDiscard(s)`，**必须在 `removeSession` / `s.stage(null)` 之前**——定位要用 stage 的原点。
2. `deliverResultOnDiscard` 的落地策略（不丢为上）：
   - 玩家其实还在线（极端时序）→ 走正常 `giveResult`（塞背包，塞不下掉脚下）；
   - 玩家离线 → `world.dropItemNaturally(loc, item)`，落点优先级：
     **① 砧位 / 演出台原点（`stage().origin()`）** → **② tick 里留的最后站位（每秒刷新）** → **③ 主世界出生点**；
   - 三种落点都必须先过 `loadedWorldOf()`：用 `Bukkit.getWorld(name)` 回查，**世界已卸载则不用那个位置**（拿 `Location#getWorld()` 往已卸载世界丢物品 = 丢进虚空）。
   - 落地后置 `resultGiven(true)`，并写两行日志：
     - `warning`「玩家 X 在成品界面断线，成品已掉落：<物品名> @ <世界> x,y,z」；
     - `info`「可追溯记录：uuid / 品质 / 得分 / 阶段=RESULT(离线补发)」。
   - 只有在「连一个已加载世界都拿不到」时才 `severe` 一行「成品未能落地，需要人工补偿」并 return（不置 `resultGiven`，保持状态可查）。

### 改后行为
- RESULT 阶段断线/被踢：成品一定离开会话，落在砧边（正常情况下玩家回服走两步就能捡），控制台有明确两行记录。
- 不会重复发放：`resultGiven(true)` 与 `giveResult` 内部的幂等判断双保险，`shutdown` 的补发分支不会二次给。
- `lastLocations` / `repairSlots` 都在 `removeSession` 里清理，无泄漏。

### 自检建议
1. 在淬火结算动画那 1~2 秒里 `kill <player>`（或用反作弊踢出）：回服后在砧/演出台旁能找到成品，控制台出现 warning + info 各一行。
2. 同一场景下再执行 `/reload confirm` 或直接重启：不应出现「背包一把 + 砧上一把」（`resultGiven` 已置）。
3. 世界卸载场景：用 Multiverse 卸载演出台所在世界后再断线 → 成品应落到玩家最后站位所在世界（或主世界出生点），而不是消失。
4. 正常路径回归：结算后点「拿走」/ 关界面仍只发一次。

### 存疑 / 取舍
- 项目里**没有**现成的 `PendingDelivery` / `offline-give` / 待领机制（已 grep：`auction` 的 mail 是拍卖行专用，`spirit` 的 pending 是灵性节流，都不适用），所以按任务的第二条路走「原地掉落 + warning」。若后续要做「离线待领」，建议新增 `item/PendingDelivery`（PDC/落盘 + `PlayerJoinEvent` 领取），本次不在授权文件范围内。

---

## C【P1】箭矢 / 三叉戟不结算词条、不扣耐久

### 位置
`CombatListener.onHit`：`CombatListener.java:136-148`（damager 展开）

### 根因
首行 `if (!(event.getDamager() instanceof Player player)) return;`。玩家射出的箭/三叉戟，`EntityDamageByEntityEvent#getDamager()` 是 `Projectile`，不是 `Player` → 直接 return。
于是整段（暴击 / 吸血 / 点燃 / 剑灵养灵 / **武器耐久扣账**）对投掷物全部不生效：远程等于无限免费词条 + 永不磨损（配合 `unbreakable` 更是零成本）。

### 改法
把 damager 展开成两条来源之一，其余早退：

```java
Player player;
if (event.getDamager() instanceof Player direct) {
    player = direct;
} else if (event.getDamager() instanceof Projectile projectile
        && projectile.getShooter() instanceof Player shooter) {
    player = shooter;
} else {
    return;                       // 骷髅 / 掠夺者 / 发射器的投掷物：与改动前一致，不结算
}
ItemStack weapon = player.getInventory().getItemInMainHand();   // 口径不变：取该玩家主手
```

新增 `import org.bukkit.entity.Projectile;`。后续所有逻辑（崩刃早退、耐久扣账、剑灵、暴击、吸血、点燃）保持原样，因此对投掷物自动生效。

### 改后行为
- 玩家投掷物命中：词条与耐久**与近战同一套代码**结算（含崩刃那一击不吃词条的既有语义）。
- 非玩家射手的投掷物：行为与改动前完全一致（不归因、不扣账）。这一条是硬要求，已在代码注释里写明。

### 自检建议
1. 手持锻造剑（带暴击/吸血/点燃），先用弓射出箭、**在箭落地前切回锻造剑**，命中时应看到暴击粒子/吸血回升/目标着火，且剑耐久 −1。
2. 让骷髅射玩家：不得出现剑耐久变化、不得触发吸血/点燃。
3. 三叉戟：投掷命中后同样结算（注意三叉戟投出后主手会变空，见下方「存疑」）。
4. 崩刃状态持剑远程命中：只发「剑已崩刃」提示，不吃词条（与近战一致）。

### 存疑 / 取舍
- 「武器取主手」这条口径有一处物理限制：**弓 / 弩 / 三叉戟在发射瞬间占着主手**，所以「发射后一直没切武器」的箭不会结算（主手是弓，不是锻造品，`FORGED` 判断早退）。真正会吃到结算的是「发射后切到锻造武器再命中」的多 tick 飞行场景。
  若设计上要求「按发射瞬间的武器结算」，需要一张 `projectile UUID → weapon 快照` 的表（Paper 的 `Projectile` 是实体，可在 `ProjectileLaunchEvent` 记、命中/过期清），这属于新机制、且 `ForgeSession` 不在我的可写范围。**建议 Lead 决定是否单开一条任务**，本次严格按审计给的最小修法落地。
- 副作用（符合任务要求、但值得记录）：投掷物命中也会扣主手武器耐久；如果主手武器刚好在命中时被替换，透支的是替换后的那把（与近战同一缺陷，未在本条扩面）。

---

## D【P2】暴击写在 `HIGH` 会被更高优先级覆盖或叠乘

### 位置
`CombatListener.onHit`：`CombatListener.java:136`（优先级）、`201-218`（`dealt` 与吸血）

### 根因
1. 处理器挂在 `EventPriority.HIGH` 上 `setDamage(damage * crit.multiplier())`。HIGH 之上还有 HIGHEST / MONITOR 两档：别的战斗插件在更高优先级重写 `setDamage` 会让暴击乘区整段丢失；反之若它先乘、本插件再乘，则乘区叠加两次。
2. 吸血紧接着读 `event.getFinalDamage()`，那个值里含护甲/附魔修饰符与其它插件在同一优先级上的改写 → 时序不稳，吸血量漂移。

### 改法
1. 优先级 `HIGH` → **`HIGHEST`**，暴击乘区只落一次。
2. 引入局部变量 `double dealt = event.getDamage();`，暴击时 `dealt = dealt * crit.multiplier(); event.setDamage(dealt);`
3. 吸血改读 `dealt`（本次基础伤害 × 暴击乘区），**不再二次读 `getFinalDamage()`**。

### 改后行为
- 暴击乘区在所有 HIGH 档插件之后、MONITOR 观测之前落定，观察者看到的是一致的数值。
- 吸血与暴击同源同值：出暴击则吸血按暴击后的伤害算，不再出现「暴击了但吸血按未暴击算」。
- 与同文件 `ForgeListener.onDamaged`（HIGH 档，词条减伤）的顺序变得确定：**先减伤（HIGH）→ 再暴击乘区（HIGHEST）**，符合直觉。
- **口径变化（有意）**：吸血基数由「实际掉血（吃护甲）」变成「基础伤害 × 暴击」。这会让吸血量比以前略高，是审计指定的口径（避免时序依赖）。若产品口径要求「按实际掉血吸血」，正确做法是另开一个 `MONITOR` 监听读 `getFinalDamage()`，本次未做。

### 自检建议
1. 单插件环境：暴击伤害 = 基础 × `crit.multiplier()`，吸血 = `dealt × lifesteal`，与改动前（无双插件时）数值一致。
2. 装一个在 HIGHEST/MONITOR 改伤害的战斗插件：暴击不再翻倍/丢失，实测伤害落在预期区间。
3. 穿高护甲目标：吸血量应随 `dealt`（而非掉血量）线性变化，且同一剑在有无护甲目标上吸血一致。
4. 确认没有第二次注册：全仓 `onHit` 只有一个 `@EventHandler`（`CombatListener.java:136`），`onWearerDamaged` 仍是 HIGH 且只扣护甲耐久。

---

## E【P2】ForgeHolder 分支未校验点击者=会话主人

### 位置
`ForgeListener.onClick`，ForgeHolder 分支：`ForgeListener.java:52-77`（新增校验在 `67-74`）

### 根因
同文件 `ContractHolder`（`:79-93`）与 `EvolutionHolder`（`:95-…`）都有 `player.getUniqueId().equals(holder.player())`，唯独最常开、最有权重的 `ForgeHolder` 漏了。
`handleClick` 内部所有落账用 `session.player()`，所以第三方不能直接拿走成品，但**按钮语义**（放弃锻造、淬火选择、拿走）可以被代按——最轻也是「替别人放弃、材料白丢」。

### 改法
在 `session == null || session.finished()` 之后补一行：

```java
if (!player.getUniqueId().equals(session.uuid())) {
    return;      // 事件已在上面 setCancelled(true)，不会放行任何点击
}
```

### 改后行为
- 只有会话主人能推进自己的锻造界面；第三方点击被静默忽略（事件仍取消，物品不会移动）。
- 与其余分支的纵深防御口径一致。

### 自检建议
1. 双人测试：A 开锻造台，B 用 `/invsee`（或 GUI 同步 mod）点「放弃」→ A 的会话不应被作废。
2. 正常单人流程全流程回归（加热→锻打→叠钢→开刃→抛光→嵌丝→淬火→拿走）不应任何阻塞。
3. 自检夹具里若有 `simulateClick` 直调（不走事件）的用例，不受影响（这条校验只在事件入口）。

---

## F【P1】`onDrag` 漏了 ContractHolder / EvolutionHolder / EnvPanelHolder（Lead 追加，审计 22）

### 位置
`ForgeListener.onDrag`：`ForgeListener.java:354-399`

### 根因
`onClick` 里逐分支处理了 12 种 holder，但 `onDrag` 只拦了 9 个，漏掉：
`ContractHolder`（龙税契约）、`EvolutionHolder`（蜕变）、`EnvPanel.EnvPanelHolder`（环境面板）。
往这三种界面里拖动，物品会落在自绘格子里（`InventoryDragEvent` 未被取消），界面一关随清空一起蒸发 = **物品丢失**。

### 改法
把 `onDrag` 判定改为统一取 `InventoryHolder holder = event.getView().getTopInventory().getHolder();` 再逐项 `instanceof`，补齐三个漏项，并加「整段取消」注释。

### 改后行为
12 种插件自绘界面一律不接受拖入/拖出；与 `onClick` 的 holder 清单逐项对齐。

### holder 清单复核表（Lead 用）

| # | Holder | 包 | `onClick` 有分支 | `onDrag` 已拦 | 界面是否放玩家物品 |
|---|---|---|---|---|---|
| 1 | `ForgeHolder` | `gui` | ✅ | ✅ | 否（成品格只读） |
| 2 | `ContractHolder` | `gui` | ✅ | ✅（本次补） | 否（贡品面板只读） |
| 3 | `EvolutionHolder` | `evolve` | ✅ | ✅（本次补） | 否 |
| 4 | `EnvPanel.EnvPanelHolder` | `env` | ✅ | ✅（本次补） | 否（纯只读面板） |
| 5 | `ManualStore` | `manuals` | ✅ | ✅ | 否（只点购买） |
| 6 | `AchvHolder` | `growth` | ✅ | ✅ | 否（`AchvMenu.click` 首行即取消） |
| 7 | `BoardHolder` | `commission` | ✅ | ✅ | 否（只点交付，物品从背包扣） |
| 8 | `TombGui` | `tomb` | ✅ | ✅ | 否 |
| 9 | `GemMenuHolder` | `gui` | ✅ | ✅ | 否 |
| 10 | `MainMenuHolder` | `gui` | ✅ | ✅ | 否 |
| 11 | `WeaponMenuHolder` | `gui` | ✅ | ✅ | 否 |
| 12 | `ArmorMenuHolder` | `gui` | ✅ | ✅ | 否 |

**结论：`onClick` 里出现的 12 种 holder 现在我全部在 `onDrag` 里拦住了，无遗漏。**
（全仓 `implements InventoryHolder` 的自绘 holder 共 12 个，与此表一一对应；`auction` / `guild` / `duel` 等界面走独立 `Listener`，不在 `ForgeListener` 范围内，未动。）

### 自检建议
1. 打开龙税契约界面，把背包物品拖到贡品格 → 应被拒（物品留在背包）。
2. 打开蜕变界面 / 环境面板，同样拖入 → 应被拒。
3. 界面关闭后检查背包物品数量不变。
4. 回归其余 9 种界面拖动仍被拒（原行为不变）。

---

## 没能修 / 存疑的点（交给 Lead 决策）

1. **A 的取舍**：槽位失效时「宁可多一件」。极端场景（玩家把剑丢给别人研磨后自己又跑了一遍流程）会多出一把修好的成品——这是刻意选择，若要改成「多的一件直接销毁」会变成吞物品，不做。
2. **B 的离线待领**：项目内没有现成的 PendingDelivery 机制，本次走「原地掉落 + warning」。要做得更好需要新增 `item/PendingDelivery`（PDB/落盘 + 登录领取），文件不在我的授权范围，建议单开任务。
3. **B 的极端兜底**：`Bukkit.getWorlds()` 为空时只 `severe` 不发放（运行期几乎不可达：`shutdown()` 会先 `stop()` 掉 ticker，不会走到 `discard`）。要 100% 不丢需要落盘暂存。
4. **C 的发射瞬间武器快照**：现在按「命中瞬间的主手」取武器；弓/弩/三叉戟在发射时占主手，所以「不切武器」的远程不结算。要不要按发射瞬间快照（需要 `ProjectileLaunchEvent` + 映射表 + 实体过期清理）请 Lead 定。
5. **D 的吸血基数**：由 `getFinalDamage()` 改为 `dealt`（不含护甲减伤），吸血量会比 v2.23.0 略高。若产品口径是「按实际掉血」，应改到 MONITOR 档另读（本次按审计指定实现）。
6. **未修（不在我的可写范围）**：`audit/23` 里的 P0 ×2（`Attrs.isBaseKey` 前缀误判、`AnvilForgeManager` 异步冲刷）与其余 P1/P2（`Reforge` 键名、`StrengthenService` 的 `toLevel`/剑尘文案/`baseOf`、`AnvilForgeManager` 的 `enabled` 门、`dropReturn`、全息概率、批量音效/`VANISHED`、单锤冷却静默）全部保持原样。
7. **`ForgeListener.onDamaged` 仍在 HIGH**：它做的是词条减伤（`setDamage * (1-reduce)`），与 `CombatListener.onHit` 的 HIGHEST 暴击乘区形成「先减伤再暴击」的确定顺序，本次未改其优先级（审计未要求，且改动会改变既有数值口径）。