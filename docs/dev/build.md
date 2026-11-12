# build.md

> 从 `README.md` 拆出来的版本叙事。**为什么拆**：README 一度到 286 KB / 4460 行，
> agent 整读会炸上下文，于是只读一半就动手 —— 真正的「读取不到」。
> 现在 README 只留索引 + 核心参考，历史按版本段放这里。

[← 回 README](../../README.md) · [← 版本索引](../../README.md#〇更新索引每轮必须同步别再让头部烂掉)

---

## 二十五、构建

> ### ⚠️ 每次更新都必须 `mvn clean package`（不许省 `clean`）
>
> ```bash
> cd ForgingMaster
> mvn -B clean package          # 唯一正确的构建命令
> # 产物：target/ForgingMaster-<版本>.jar
> ```
>
> **为什么是硬要求**：不带 `clean` 的 `mvn package` 只会覆盖同名产物，历次版本的 jar 会一直堆在
> `target/` 里。本项目所有验证脚本（`tools/selftest.py` 等）都是按 **mtime 取「最新的那个 jar」**
> 部署的——残留物一多，你验的很可能不是刚改的那一版，于是出现「明明改了却没生效」这种最难查的假象。
>
> 改完代码的标准流程，四步不许跳：
>
> ```bash
> mvn -B clean package     # ① 清空重建，确认 BUILD SUCCESS
> # ② 把 target/ForgingMaster-<版本>.jar 丢进 testserver/plugins/（覆盖旧 jar，别留两个）
> # ③ 重启服务端，看启动日志无 ForgingMaster 异常
> # ④ 进服跑 /zhujian selftest，全绿；失败项当阻塞，不许放过
> ```
>
> 只改 `config.yml` / 资源文件也必须走 `clean package` —— 资源是打进 jar 的，
> 不重建的话服务端读到的还是旧 jar 里的那份。
>
> 顺带提醒：`config-version` 一旦抬了，老盘上的 `config.yml` 会在首次启动时被迁移改写并落盘。
> 升级前先备份 `plugins/ForgingMaster/`。

```bash
cd ForgingMaster
mvn clean package
# 产物：target/ForgingMaster-2.23.4.jar
```

把 jar 丢进 `plugins/`，启动服务器即可。

### v2.19 构建验证记录（收尾双工序）

在 `testserver`（Paper 1.21.11）真跑过：

```
mvn clean package                        # BUILD SUCCESS，target/ 清空后重建，产物仅 ForgingMaster-2.23.2.jar（1.49 MB）
java -jar paper.jar --nogui              # Done (16~53s)，无 ForgingMaster 异常
/zhujian selftest                        # 372 项，通过 372，失败 0
```

构建一律走 `clean`：不带 `clean` 的 `mvn package` 只会覆盖同名产物，`target/` 里历次版本（2.17.1 / 2.17.2 / 2.17.3 / 2.18.0）的 jar 会一直堆着，`tools/selftest.py` 又是按 mtime 取「最新的那个 jar」部署，残留物多了容易看错验的是哪一版。

配置迁移也真跑过：把盘上的 `config.yml` 保持 2.18 状态（`config-version: 1`，没有那 10 个新键），
开服后日志出一行「配置迁移：补上收尾双工序的 10 个新键（settings.polishing-* / settings.inlay-* /
difficulty.polish-* / difficulty.inlay-*），值均为出厂默认，已有键不动。」，落盘后 10 个键带中文注释就位、
`config-version: 2`；再启动一次不再有任何迁移输出（no-op）。

### v2.16 构建验证记录（铁砧强化重写）

在 `testserver`（Paper 1.21.11）真跑过：

```
mvn -B -q package                # exit 0，产物 ForgingMaster-2.16.0.jar（855 KB）
java -jar paper.jar --nogui      # Done (26.9s)，无 ForgingMaster 异常
/zhujian selftest                # 321 项，通过 321，失败 0
```

自检里跟铁砧相关的实证项（不是「读配置」而是真的动实体 / 动方块 / 动文件）：

| 检查 | 验的是什么 |
| --- | --- |
| 砧位全息真生成、文本随等级变、撤得干净 | 在世界里真放一个 `ANVIL` 方块 → `placeCore` → 断言 `ItemDisplay`/`TextDisplay` 实体真的出现、`+7` 改成 `+30` 后文本跟着变、撤掉后实体一个不剩 |
| 关服不丢剑：砧位写盘后能原样读回 | 用独立临时管理器把会话写进 `anvils-selftest.yml`，换一个新实例读回，断言等级 42 / 连击 3 / 上限 99 / 主人 / 世界全部还原（不碰线上 `anvils.yml`） |
| 明牌五项非负且和恒为 1 | 六个等级点逐个验概率表，杜绝「五项加起来不是 100%」 |
| 无损连击：必成、不碎、五项仍和为 1 | 碎裂 / 损伤 / 空锤三项必须精确为 0 |
| 推进规则：成功 +1、大成 +2、损伤掉 1、无损清零连击 | 纯函数 `afterStrike` 的四种走向 |
| 抽票落点：骰子边界归到正确的档 | 0.30 / 0.60 这种浮点边界不能被误差吃掉一档 |
| 重锤：插件锤与原版重锤都认，剑不认 | PDC 标记 + 材质两条路都对 |
| 砧位只认三档铁砧方块 | ANVIL / CHIPPED_ANVIL / DAMAGED_ANVIL |

本轮为了让新模型自洽，改掉了 7 条与旧上限（+20/+22/…）绑定的老断言：之前「前两级必成」「铁为 20」「材料随等级递增」「费用比旧曲线六成」「连击抬 odds[4]」「老物品按档位现算 20」「契约骨架默认关」。这些不是放宽标准，而是**旧模型的前提被 v2.16 换掉了**，断言跟着换成新前提（统一 +99、每锤固定材料、连击抬总成功率、契约周目已默认接入）。

顺手修掉一处历史残留：`warnMissingKeys` 里还在检查 `vanilla-guard.block-events`，这个键代码里早就不读了，每次开服刷一条假警告。

### v2.16.1：握着锻造剑，还是提示「主手先拿一件锻造品」

玩家报的现象：手里明明握着一把锻造剑，`/zhujian evolve` 被顶回来，提示「主手先拿一件锻造品」。

根因在 `EvolutionService.stageOf()`——它拿「PDC 里有没有 `evolution` 键」当「是不是锻造品」的证据：

```java
Integer v = intOf(item, Keys.EVOLUTION);
return v == null ? -1 : v;   // 出厂剑没这个键 → -1 → 被当成非锻造品
```

而 `evolution` 键只在**蜕变成功那一刻**才写进去，所以「一次都没蜕变过」的剑永远是 -1。偏偏两个入口都拿 `stageOf(...) < 0` 当拒绝条件（`/zhujian evolve` 命令、蜕变界面的确认按钮），于是每把新剑第一次进蜕变都会被判成「不是锻造品」。同一份代码里 `BountyManager` / `ContractManager` 读的是 `getOrDefault(EVOLUTION, 0)`，所以只有蜕变这条路坏了——三处语义本来就不一致，这次统一到「0 = 未蜕变」上，并让方法注释和实现对齐。

修法：锻造品身份只认 `ItemFactory.isForged()`（`FORGED` 标记），锻阶缺失按 0 算：

```java
public int stageOf(ItemStack item) {
    if (item == null || item.getType().isAir() || !plugin.items().isForged(item)) return -1;
    Integer v = intOf(item, Keys.EVOLUTION);
    return v == null ? 0 : Math.max(0, v);
}
```

顺带把混在一起的两种错拆开：空手 →「主手先拿一件锻造品。」；非锻造品 →「主手这把不是锻造品，蜕变不了——锻造台打出来的才算。」原来两种情况共用一个词，玩家分不清自己是哪种错。

界面也补了明牌：成功率牌多一行强化进度，确认按钮在没锻满时直接写「先把这一阶锻满 +5」，不用点了才知道白点。

#### 门槛就是「这一阶的强化上限」

`evolution.stages.N.level` 不是「要锻到 +99 才算」，而是**这一阶允许的最高强化等级**：锻到 +5 之后砧上就落不下去了（`pendingGate` 拦），必须蜕变成「精钢」才解锁 +6 以上；再锻到 +10，拦住，再蜕变。所以「强化到当前上限才能蜕变」和「蜕变才能突破当前上限」是同一件事的两面，代码里只有一条规则。

配套的三句提示统一成同一套说法，玩家不会两种话术对不上：

| 位置 | 触发 | 文案 |
| --- | --- | --- |
| `/zhujian evolve`、界面「确认蜕变」 | 没锻满当前上限 | 「强化还没满这一阶：上限是 +5，你才 +0（还差 5 级）。」+「继续强化到 +5，砧上敲满了再来蜕变。」 |
| 铁砧落锤（`StrengthenService.strike`） | 已到当前上限 | 「已到这一阶的强化上限 +5——先蜕变「精钢」才解锁 +6 以上。/zhujian evolve」 |
| 蜕变界面成功率牌 | 第 3 行 | 「强化进度：+5/+5 ✓ 已满」/「强化还没满：+0/+5（还差 5 级）」 |

#### 真机验证（bot + rcon，Paper 1.21.11）

| 场景 | 操作 | 结果 |
| --- | --- | --- |
| A 出厂剑（+0，没有 `evolution` 键） | 主手握刚 `/zhujian give` 出来的铁剑 → `/zhujian evolve` | 界面正常弹出《〔锻阶〕蜕变 · 精钢》，不再报「不是锻造品」 |
| B 普通钻石剑 | 主手握原版钻石剑 → `/zhujian evolve` | 仍然拦下，提示「主手这把不是锻造品」 |
| C +0 锻造剑直掷 | `/zhujian evolve now` | 「强化还没满这一阶：上限是 +5，你才 +0（还差 5 级）」+「继续强化到 +5，砧上敲满了再来蜕变」 |
| D +5 锻造剑点确认 | `/zhujian strengthen set 5` → 界面点槽 22 | 槽 10 明牌「强化进度：+5/+5 ✓ 已满」；点击后「蜕变成功 —— 锻阶〔精钢〕· 成功率 97% · 属性乘区 ×1.08」（v2.17.3 乘区重定后各阶 1.08~1.12，连乘 ≈×1.77） |
| E +0 剑点确认 | 界面点槽 22（没锻满） | 槽 10「强化还没满：+0/+5（还差 5 级）」，槽 22「先把这一阶锻满 +5」；点击后给同样的「继续强化」提示，不掷骰 |
| F 到上限再落锤 | 放剑上砧 → 持重锤左键敲（+5 已达门槛） | 「已到这一阶的强化上限 +5——先蜕变「精钢」才解锁 +6 以上。/zhujian evolve」，剑不碎不掉级 |

自检到 **347 项，通过 347，失败 0**（统一 347 项基线口径，留档 testserver/RESULT-selftest-2.17.3.txt）。此前口径 342 与更早的 328/329 为旧版本数字。新增一项直接钉住这个 bug：出厂剑 `stageOf` 必须读 0（并断言它的 PDC 里确实没有 `evolution` 键，防止以后有人给样本补键、让用例变成空跑），普通钻石剑必须读 -1；门槛边界另有 `gateAt(0,4)` 为空、`gateAt(0,5)`/`gateAt(0,7)` 命中、已蜕变不拦同一阶这几项。

两个 bot 回归脚本落在 `tools/guitest/`：`evolve-check.js`（A–E，走命令与界面）与 `anvil-gate-check.js`（F，真放铁砧、真落锤）。

### v2.16.2：剑没躺在砧面上，是插在砧子里

玩家反馈：「强化好像还不是剑平放在铁砧上」。

原因不在交互，在姿态。剑的 `ItemDisplay` 用的是原版 `GROUND` 变换，而原版那套对物品是**先缩到 0.5、再往下沉 2/16 格**（`display.ground` 里的 `translation [0, -2, 0]`）。我们的实体又放在 `y + 1.03`，两下一叠——0.5 的尺寸加上 0.125 的下沉——剑看着又小又半埋进方块，像插进砧子里，而不像躺在砧面上。参数怎么调都救不回来，因为那层变换在模型里，不在我们的 `Transformation` 上。

改成自己算姿态：

```java
d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);  // 不吃原版 display.ground
// 模型按图标姿态立在 XY 平面、朝 +Z；先绕 X 扳倒 90° 成平躺，再绕 Y 转到朝向
Quaternionf rot = new Quaternionf()
        .rotateY(Math.toRadians(swordYaw()))
        .rotateX(Math.toRadians(swordPitch()));   // 默认 -90
```

`NONE` 没有那层 0.5 缩放，所以 `sword-scale` 默认从 0.70 提到 **0.95**（铺满砧面不出边），`sword-height` 从 1.02 提到 **1.03**（贴面不穿模）。

**老配置会自动迁移**：v2.16.1 及之前的 `config.yml` 里没有 `sword-transform` 这个键，开服时补上并落盘，同时把「0 = 按 GROUND 平放」这类过期注释换成新的，最后写一个 `pose-version: 2` 作为一次性标记——之后服主自己改成 `GROUND` 也不会被反复覆盖。只推动「还是旧默认值」的项（`pitch 0 → -90`、`scale 0.70 → 0.95`、`height 1.02 → 1.03`），手动调过的值一律保留。

这条是用「真放一个砧位、量实体」的自检验出来的，不是读配置：

| 检查 | 验的是什么 |
| --- | --- |
| 剑是躺在砧面上，不是插在砧子里 | 真放 `ANVIL` 方块 → `placeCore` → 抓到剑的 `ItemDisplay`，断言 `ItemDisplayTransform` 等于配置、剑心 `y - blockY` 落在 1.0~1.15、并把它姿态四元数作用到模型正面法线 `(0,0,1)` 上，结果必须几乎竖直（`\|y\| > 0.99`）——立着的话这个值会是 0 |
| 剑平躺参数：贴面、不顶出砧面 | 高度/大小/朝向三个配置项的取值范围 |

自检到 **329 项，通过 329，失败 0**（新增 1 项）。升级路径也真跑过：把 `config.yml` 改回 v2.16 的老值（`sword-pitch: 0`、`scale 0.70`、`height 1.02`、没有 `sword-transform`），开服后自动迁移到 `NONE / -90 / 0.95 / 1.03` 并写 `pose-version: 2`，注释一并换新；铁砧全流程（放剑 → 落锤 → 到上限被拦）回归通过。

### v2.16.3：敲打演出、剑越锻越大、锤子门槛降下来

三件事，都是为了「敲铁砧」这件事看着有手感。

#### 一、剑越锻越大

砧面上那把剑的尺寸跟着强化等级走：`sword-scale`（+0）→ `sword-scale-max`（到品质上限，默认 1.30）。以前 `setTransformation` 只在实体生成时算一次，后面 `refresh` 只换 `itemStack`，所以哪怕配了缩放也不会变——现在每次刷新都重写姿态。

**v2.17.1 三处修正（旧版「看着没生效、还鬼畜」）：**

1. **曲线默认改成开方** `sword-growth-curve: SQRT`，`t = √(等级/上限)`。线性插值下普通剑（上限 50）敲到 +5 只大 3.5%，眼睛看不出来，整条线等于没做；开方后 +5 就是 ×1.12，+25 到一半，到上限正好 1.30（×1.37）。想要老手感可设 `LINEAR`。
2. **删掉砸中那一帧的缩放脉冲**：旧代码在命中帧把剑临时放大到 1.06、下一帧弹回 1.0，两个 tick 的缩放抖动就是「强化时剑在鬼畜」。现在打击感只交给火花和音效，剑的大小只由等级决定，一锤之内不动。
3. 顺带两条：`refresh` 不再每锤都 `setItemStack`（材质没换就跳过），避免客户端整套重建模型闪一下；全息和放剑那行加上 `剑身 ×1.12`，让「越锻越大」有个能被读到的数字。

```java
private void applySwordPose(ItemDisplay display, ItemStack sword, double scaleMul) {
    float scale = (float) (swordScaleFor(sword) * scaleMul);   // 等级 → 大小
    Quaternionf left = new Quaternionf()
            .rotateY(Math.toRadians(swordYaw()))
            .rotateX(Math.toRadians(swordPitch()));            // 平躺
    display.setTransformation(new Transformation(new Vector3f(), left,
            new Vector3f(scale, scale, scale), new Quaternionf()));
}
```

只影响外观，公式里不掺任何数值。

#### 二、锤子门槛：默认发铁斧

原版重锤（MACE）要打过试炼密室，拿它当强化工具等于把强化线锁在后期。改成列表配置：

```yaml
anvil-forge:
  hammer:
    items: [IRON_AXE, MACE, STONE_AXE, IRON_PICKAXE]   # 第一项 = /zhujian hammer 发的那个
    allow-vanilla: true
```

`/zhujian hammer` 现在发**铁斧**改的「铁匠重锤」（PDC 标记仍在），重锤照样认；老配置只写了单项 `hammer.item` 的话，列表为空时自动退回那一项。

#### 三、落锤演出：重锤竖着砸下来 + 火花

第一版我做歪了：拿**铁斧**当锤子、还在模型平面里绕 X 转，结果是一把斧头躺在砧面上左右扭——被玩家一句话点破：「跟死鱼似的蛄蛹」。改了两处根子上的事：

1. **画的是真锤子**：`animation.hammer-item` 默认 `MACE`（原版重锤），不再拿玩家手里那把铁斧充数。手里用什么都无所谓，演出里锤头就是锤头。
2. **动作只有一条竖直线**：`StrikeFrame` 里**只有 y 和 roll，没有 x / z**——水平位置永远是砧心。所以「竖直砸下」是结构上保证的，不是调参调出来的。

```java
public record StrikeFrame(double y, float roll, boolean impact, boolean done) {}

public StrikeFrame strikeFrame(int t) {
    if (t < impact) {                    // 加速落体：k² 起手慢、临到砧面最快
        double k = t / (double) impact;
        return new StrikeFrame(startY + (hitY - startY) * (k * k), roll + windup * (1 - k), false, false);
    }
    ...
}
```

| tick | 动作（v2.17.2 起为「柄底不动、锤头绕柄旋转」模型） |
| --- | --- |
| 0 ~ 6 | 锤柄钉在 pivot（柄底，砧面上方 1.10 格）不动，锤头绕柄画弧：角度从 `swing-start-deg`（−25°，锤头后扬）加速扫向 `swing-hit-deg`（170°，越过正下方压到剑面），`k²` 缓急 |
| 7（`impact-tick`） | 锤头落在剑面上（y≈1.06，剑躺在 1.03）→ 迸火花，剑被敲得缩一下（×1.06） |
| 8 | 反震：锤头被砧面顶回 `swing-rebound-deg`（12°），剑弹回原大小 |
| 9 ~ 12 | 锤头沿原弧抬回起手角度，然后撤掉实体 |

平移量每帧取 `−R(θ)·(0, L, 0)`（Minecraft 变换顺序 T·R·S，平移在旋转之后作用于世界空间），所以柄底严格钉在原地、锤头画弧——以前「整体垂直下落」的旧版看着像一把锤子从天上掉下来，就是被这套参数换掉的（`start` 与 `hit` 取对称值会让轨迹弦变成竖直线，注释里有提醒）。`billboard: CENTER` 让锤子永远面向玩家，从任何角度看都是一整把锤子。飞行段每 tick 在锤头位置（`headOffset` ≈ 柄长 + 模型头长）撒火星，砸中一捧火 + 岩浆 + 烟。

火花按结果分档：普通成功 `CRIT + FIREWORK`，大成/无损加 `END_ROD`，碎裂换成 `LAVA + LARGE_SMOKE`。**演出不参与判定**：不扣钱、不改等级，演挂了最多不好看，这一锤的结算早就落地了。剑碎了（会话已注销）也照样把火花演完，不然「碎」这个最需要反馈的结果反而没画面。

老配置由 `pose-version` 迁移兜住（v2.17.3 已到 7）：`hammer-item: IRON_AXE` → `MACE`、`duration-ticks: 10` → `12`、补 `impact-tick / billboard / hammer-scale`（v3）、补 `anvil-forge.caps` 六档品质上限（v4）、`pivot-height / swing-* / flame.*` 绕柄旋转与火焰（v5）、砸中角改 150°（v6）、v2.17.3 定稿 −25°/170°/pivot 2.10（v7）。服主改过的值一律不动，废弃键不再写进老配置。

#### 真机验证

```
/zhujian selftest                            333 项，通过 333，失败 0
hammer-anim-check.js（bot 实测）
  /zhujian hammer                            → iron_axe（铁斧改的「铁匠重锤」）      PASS
  放剑后砧上 item_display 数量                → 1（只有剑）
  落锤瞬间 item_display 峰值                  → 2                                      PASS
  演出结束后                                  → 回到 1，锤子收干净                     PASS
  锤子 Y 轨迹（20×20ms 采样，相对砧面）
    -57.968 → -58.021 → -58.110 → -58.234 → -58.394 → -58.589 → -58.820（最低）
    → -58.540 → -58.403 → -58.265 → -58.127 → -57.990（收回）
  落差 0.85 格，X 漂移 0.000，Z 漂移 0.000                                            PASS ×3
anvil-gate-check.js（到上限再落锤）            → 仍被拦下，指向蜕变                    PASS
```

那些数字就是「竖着砸下来」的证据：高度一路单调下降到底、水平零漂移、最低点正好贴在砧面上方 1.18 格。自检里对应的是帧序列断言（`strikeFrame` 逐帧走：下落段严格递减、砸中帧恰好等于 `hit-height`、`done` 帧必须出现），不看配置读出来的字符串。

另外两条实体级断言（不是读配置）：升级到上限 → `refresh` → 抓回剑的 `ItemDisplay`，断言缩放真的从 0.95 涨到 `sword-scale-max`，且中间等级严格落在两端之间（防「锻到一半反而变小」）。

### 管理员发东西：装备 / 宝石（v2.16.3 补）

原来 `/zhujian give` 只能发剑胚，装备虽然写了 `armor-<剑胚>-<部位>` 的解析，但没在 help 里出现过；宝石干脆**没有任何发放指令**——只能拿「下界之星」去 `gem smelt` 熔，服主想给玩家发颗指定宝石都做不到。补上：

```text
/zhujian give <玩家> armor-<剑胚>-<部位> [品质] [分数]
    例：/zhujian give Steve armor-iron-chestplate LEGENDARY 90
/zhujian gem give <玩家|all> <类型id|random> [L1~L5] [数量]
    例：/zhujian gem give Steve attack L5 3
        /zhujian gem give all random L3 1
/zhujian gem list           # 11 种宝石的 id / 中文名 / 效果
```

几个实现细节：

- **控制台能跑**。`gem()` 开头原本是一句「要在游戏里用」的玩家判定，`give`/`list` 得提到判定之前——否则后台上发不了东西。
- **中文名也认**：`attack` 和 `赤玉` 都能当入参，省得管理员去背 id。
- **等级兼容旧名**：`L1~L5`，另认 `COMMON/FINE/EPIC/LEGENDARY`（`Grade.byName` 本来就带这段兼容）。
- **发出的宝石一定带 PDC 标记**：走的是 `GemService.createGem`，和熔炼出来的完全同一种物品，镶嵌那边不会认不出。自检为此加了一条：11 种 id 全部能解析回自己、五档等级解析一致、`createGem` 出来的东西 `isGem` 必须为真且带 `GEM` 键。
- Tab 补全跟着补了：`give` 第 3 参现在会把 `armor-<剑胚>-<部位>` 全列出来，`gem give` 逐级补玩家 → 类型 → 等级 → 数量。

**真机验证**（`tools/guitest/gem-give-check.js`，bot 实跑）：

```text
/zhujian give GemBot armor-iron-chestplate LEGENDARY 90
  → 拿到 iron_chestplate，Lore：✦「传世」✦ / ★★★★★☆ / 传说 90.0 分 / +0/+20 锻造等级 / 词条四条   PASS
/zhujian gem give GemBot attack L5 3
  → 收到 3 颗 Lv.5 赤玉，Lore「—— 锻造宝石 ——」「赤玉 攻击力+5.45 ▪」                          PASS
/zhujian gem give all random L3 1     → 收到 Lv.3 破军石（随机确实在随机）                        PASS
/zhujian gem socket                   → 开孔成功！宝石孔现在是 1/1                                 PASS
主手装备 + 副手赤玉 → /zhujian gem inlay → 镶嵌成功：Lv.5 赤玉 · 攻击力（1/1），Lore 出「赤玉 攻击力+5.45 ▪」 PASS
```

也就是说「发出来的装备 + 发出来的宝石」这条链是真能走通的，不是只把物品塞进背包。

顺带一个坑：改完代码重启，`/zhujian selftest` 数目没变——**Paper 会缓存 remap 后的 jar**（`plugins/.paper-remapped/ForgingMaster-2.16.3.jar`），同名文件直接复用，于是跑的还是旧类。删掉该缓存（或换版本号）再启动才生效。这次就是踩了这个坑才发现的，已清。

### 本轮修的真 bug（2.8.0）

2.8 开工前把 v2.7 的代码整体过了一遍（剑冢残影 / 词条套装 / 剑意 / 观战 / 赛季传承 / 锻造核心 / 强化），
下面这些是**确认存在**并已修掉的，不是「可能有问题」：

| 问题 | 现象 | 根因 | 处理 |
| --- | --- | --- | --- |
| 词条套装整套是个摆设 | Lore 没有「套装: 饮血」行，`bonus-attack` 永不加成，只有吸血 / 减伤 / 斩杀在生效 | `AffixSetService.apply()` 全项目只有自检在调，`ItemFactory.build` 从来没接过线 | 成品出厂、词条重铸后各刷一次（`ItemFactory.applyAffixSet`） |
| 成品会凭空消失 | 打完不点结算，挂机 90 秒 / 直接退游戏 / 关界面 → 成品没了，连锻链还断了 | `cancel()` 不区分阶段，`RESULT` 阶段的取消直接扔会话 | `cancel()` 在 `RESULT` 阶段先 `giveResult()` 再收尾，且不断链 |
| 缺图纸也扣钱 | `blueprint.required: true` 且没图纸时，锻造费和材料照扣，然后才提示缺图纸 | 图纸校验写在扣费之后 | 校验提到扣费之前 |
| 旺火符白烧 | +0 ~ +2 本来就 100% 成功，符照样被吃掉，提示里连 +15% 都不显示 | 只判了「炉火满」，没判「基础成功率已经 100%」 | 成功率已满时不烧符 |
| 词条重铸白扣钱 | 候选池被占满 / 词条被服主删掉时，钱扣了物品没变 | 命令层先 `takeCoins` 再调 `reforge` | 先重铸，真成功才扣 |
| 加热评分断层 | 温度刚好偏出窗口一点，分数反而比贴着窗口内沿更高（0.78 → 0.95 跳变） | 两段公式在 `dev == tol` 不连续 | 抽出 `ForgeManager.heatScore()` 纯函数，第二段从 0.78 起衰减 |
| 一个会话炸掉，全服卡死 | 任何会话在 tick 里抛异常，Bukkit 会永久 cancel 整个 ticker | `tick()` 没有 per-session try/catch | 每个会话单独兜异常，只中断那一炉 |
| 观战面板能被拿走图标 | 没先开过赛况面板、直接用 `/zhujian contest watch` 时，点击不取消、关界面也不停表 | 路由懒注册只在 `ContestGui.open` 里调 | `ContestWatchGui.open` 自己也 `ensure()`；`ready` 在 `onDisable` 复位 |
| 赛季名剑可能刻成假人 | 自检留下的空名档案（`fm-strengthen-rule`，+22）会被选成「本季最强」 | 候选过滤只排 `SelfTest`，没排空名 | 空名档案直接不算候选 |
| 剑冢「挑战次数」失真 | 挑战次数恒等于斩影次数（只有赢才 +1） | 计数写在死亡结算里 | 召唤时 +1，死亡只加胜场 |
| 孤儿残影清不掉 | 崩服 / 区块卸载后残影留在存档里，重启扫不到（只扫已加载区块） | 残影 `persistent` + 一次性全量扫描 | 补 `ChunkLoadEvent` 清「带 tag 但不在进行中挑战里」的残影 |
| 剑意记到错的剑上 | 拉弓射死怪、或丢三叉戟时，剑意加在手里那把剑上 | 只看 `getKiller()` 的主手，不看伤害来源 | 远程（投射物）击杀不计剑意 |
| 大赛编号可能抛异常 | 种子落在 `0xFFFFF` 以内时 `substring(0,6)` 越界（约 5e-14） | 十六进制串不足 6 位 | 改 `String.format("%06X", seed & 0xFFFFFF)` |
| 碎剑留幽灵堆叠 | 兜底分支用 `setAmount(0)` 移除物品，客户端会出现数量 0 的非法堆叠 | Bukkit 不支持 0 数量 | 按索引清格 |
| 残影生成被取消时 NPE | 领地 / 反刷怪插件取消生成时 `spawnEntity` 可能返回 null，随后 `remove()` 空指针 | 没判 null | 加空判，直接当生成失败 |
| 缺键告警永远不响 | 老配置缺新键，启动却没有任何提示 | `getConfig()` 带运行时默认值，`contains()` 恒真 | 改成读盘上的原始 `config.yml` |

### 二轮审查修掉的（2.9.0 内，同版本）

2.9 收尾后又做了一轮只读审查（经验/天赋链 + 词条符/修复接线），确认并修掉：

| 问题 | 现象 | 处理 |
| --- | --- | --- |
| 剥空词条会连带删掉宝石/附魔行 | 剥到 0 条时按「▪ 开头」倒序扫 Lore，镶嵌条目也是 ▪ → 宝石行被清 | 改成与重绘一致的「以 ◆ 词条 标题为界整块清」（附回归自检） |
| 研磨修复吃不到「刃感」天赋 | 修理会话没固化 Kit，绿区加成恒 0 | beginRepair 里补固化 |
| 「勤学」违反开局固化承诺 | record() 读实时档案，锻造中途加点/洗点影响这一炉的经验 | record 加带倍率重载，结算传开工固化的 Kit.expMultiplier |
| 异步存盘可能炸 CME | 落盘遍历 talents 时主线程 add/reset 同一个 map | save 前浅拷贝 |
| 满级后经验无限堆积 | 60 级继续锻，exp 涨到几万；日后调大 max-level 会连跳+天赋点爆仓 | 满级后不再累加经验 |
| 幽灵天赋键吃点数 | data.yml 残留已删除的天赋 id，计入 spent 但面板看不到 | 读档时丢弃枚举里不存在的 id |
| 剥空占位行位置 | Lore 被外部插件洗过时占位行插到「锻造者」后面 | 找不到词条块时先找「锻造者」行再插 |
| 剥离/移植失败白扣钱 | 写物品抛异常时钱已扣 | 写入阶段兜异常，失败退剑币 |
| 满级面板显示「再升 5 级得 1 点」 | 误导 | 满级改显示「已到等级上限」 |

### 三轮审查修掉的（2.9.0 内，同版本）

第三轮换了打法：前两轮没碰过的模块（宝石 / 经济 / 拍卖 / 商会 / 师徒 / 比试 / 排行 / 成就 / 段位 / 世界事件 / 剧情 / 剑灵 / 委托 / 大赛本体 / 备份）分四片深扫，专盯钱、存盘、并发、残留。确认并修掉 16 个：

| # | 问题 | 危害 | 处理 |
| --- | --- | --- | --- |
| 1 | 宝石属性 modifier 按列表下标挂载，宝石串一条解码失败就整体错位 | 拆卸时误删别的宝石属性，或留下永久残留 modifier | 镶嵌 / 拆卸后全量重挂 `fm_gem_*` |
| 2 | 拆卸宝石调两参 Lore 重绘 | 同时镶了附魔时，附魔条目整块被清掉 | 改传四参（带附魔表） |
| 3 | 镶嵌先扣费后校验宝石数据 | 副手宝石数据损坏 → 钱白扣 | 校验提到扣费前 |
| 4 | 附魔镶孔零效果照扣钱扣书 | 同名同级的书反复镶，钱和书都白费 | 零效果直接拦截 |
| 5 | 拍卖行转账与挂单状态非原子 | 崩溃窗口：竞价双退款刷钱 / 卖家二次收款 / 买家钱货两空 | 全部改为「先落盘归属，再动钱」 |
| 6 | auction.yml 解析失败被当空文件 | 一条物品串损坏 → 整个拍卖行静默清空 | 失败时改名保留原文件 |
| 7 | 回档后定时存盘把旧数据盖回去 | 回档等于白回 | 回档前强制落盘、回档后按磁盘重建缓存 |
| 8 | 备份清单缺 season/hall/story 等 4 个文件 | 回档后赛季与名人堂错位 | SAVE_FILES 补齐 |
| 9 | data.yml 异步落盘撞上主线程改动 → CME，整份存档写不出 | 剑币 / 档案丢失 | byQuality 拷贝 + 按玩家兜 CME，撞了保持脏下一轮重存 |
| 10 | 比试邀请没人应战永不清理 | 双方被永久锁死（发不了新比试、锻造被锁胚） | 邀请 60 秒过期，自动退押 |
| 11 | 比试负方 / 平局也给 6 声望 | 两小号互刷比试，每场白嫖 31 点段位声望 | 负方 / 平局 0 声望 |
| 12 | 师徒成就读的字段从没被写过 | 「薪火相传」师父永远拿不到（150币/20声望） | bind / unlink 同步维护该字段 |
| 13 | 赛季结算先重置后播报 | 结算播报的榜单永远是空的 | 播报移到重置前 |
| 14 | 大赛同分并列按列表序发奖 | 同分两人拿不同声望 / 剑币 | 同分并列同名次 |
| 15 | 大赛关闭时管理员强开 → 没有 ticker 永不结算 | BossBar 常驻、状态卡死 | forceStart 拦截 |
| 16 | 世界事件空投掉落物在卸载区块里清不掉 | 星材永久留在世界里（与残影同款坑） | 记录落点，清理时拉回区块再删 |

顺手改的还有：委托板槽位钳到 3（配多了永远无法交付）、交付前复查过期、材料表清空不再抛异常、
剑灵养灵加 `spirit.pvp` 开关（默认关，互砍刷灵性堵上）、剑谱 id 碰撞加盐不再覆盖旧谱、
以及新增 `/zhujian money coin give|take|balance` —— **管理员发「剑币」专用**（原来的
`money give` 走外部经济 Vault/CMI，发出去的钱在商会拍卖行花不了）。

### 验证状态（2.9.0）

| 项目 | 结果 |
| --- | --- |
| `mvn -DskipTests package` | BUILD SUCCESS，105 个源文件 |
| Paper 1.21.11 启动加载 | `Enabling ForgingMaster v2.9.0`，`Done (31.960s)`，无 ERROR / Exception |
| `/zhujian selftest` | 共 **252** 项，通过 252，失败 0（v2.8/v2.9 的 11 项新检查 + 1 项「剥空不清宝石行」回归） |
| 词条符（2.8） | 剥离后词条从 `affix_list` / 属性 modifier / Lore 三处同时消失；移植后属性与 Lore 一起回来；反复重画不堆「◆ 词条」标题 |
| 经验曲线（2.9） | 精良 75 分一把从 34 → 20 经验；`expToNext(20)` 从 880 → 1220；老档案只补级不降级 |
| 天赋（2.9） | 5/10/60 级分别 1/2/12 点；点数用尽与单项到顶都拦得住；七项满点 20 点（100 级才够，60 级必然取舍）；洗点退回全部点数 |
| 关服 | RCON `stop` 正常保存并退出 |
| 三轮修复冒烟 | `money coin balance` 控制台可用；缺键告警正确列出 `spirit.pvp` |

> 仍需真人验证：天赋加点的手感（例如「眼力 +2 格」是否让锻打变太简单）、词条符剥了再装的数值、
> 大赛观战观感、剑冢残影难度、以及 Vulcan 在真人客户端上会不会误报 Velocity。

### 本轮收尾（2.2.0 补完）

| 问题 | 处理 |
| --- | --- |
| 委托过期从不清理，委托板会挂失效条目 | 每 5 分钟自动清理 |
| 演出 Display 实体不回收，玩家跑远会残留 | 主循环检查距离 / 跨维度，自动 despawn |
| `settings.progression-enabled` 是假开关 | 接进 `StatsStore`，关掉后所有等级加成归零 |
| `contest.min-players` 是假开关 | 自动开赛前检查在线人数，不够就顺延 1 分钟 |
| 商会图纸买了完全没用（`blueprintOf` 无人调用） | 加热容差 +15%，每把消耗 1 张；可开 `required` 门槛 |
| `econ.auction-cut`、`shop.price-*` 是死配置 | 删除，实际生效键（`auction.tax-rate` / `shop.materials`）写进注释 |
| 剑冢缺少管理删除（`adminRemove` 没接命令） | 新增 `/zhujian tomb remove <编号>`（需 admin） |
| 研磨修复不清旧装备（可复制） | 改为原地替换 |
| 宝石镶嵌后「（空孔）」残留、宝石跑到下面 | 整块清理 + 原位重建 |
| 开刃加压/减压按钮被轨道覆盖，看不见 | 移到第一行两端（0 / 8 号槽） |
| 宝石来源做错：各矿物各出各的 | 改为**只有下界之星**、固定 Lv.1 |

### 验证状态（2.2.0）

| 项目 | 结果 |
| --- | --- |
| `mvn clean package -DskipTests` | BUILD SUCCESS，80 个源文件 |
| Paper 1.21.11 启动加载 | `Enabling ForgingMaster v2.2.0`，无 ERROR / Exception |
| 配置键检查 | 启动无缺失键告警（覆盖 `economy` / `armor` / `gems` / `compat` / `blueprint` / `difficulty`） |
| **CMI 经济** | `货币后端：CMI Economy`（CMI 9.8.7.4，Economy 模块开启） |
| **Vault 经济** | 装 EssentialsX 2.22.0 后 `货币后端：Vault（VaultEconomyProvider）` |
| **Vulcan 兼容** | Vulcan 2.9.7.29 + packetevents 2.14.0 正常启用，插件输出兼容接管日志 |
| `/zhujian selftest` | 共 151 项，通过 151，失败 0 |
| **29 条子命令冒烟** | 控制台逐条执行，全部返回合理提示，日志 0 Exception |
| `/zhujian reload` | 重载后再次自检 151 项仍全过 |
| 大赛 BossBar | 强制开赛 / 结束无异常，标题颜色正常解析 |
| 关服 | RCON `stop` 正常保存并退出 |

自检输出存档：`testserver/RESULT-selftest-2.2.0-diff.txt`。

### 验证状态（2.4.0）

| 项目 | 结果 |
| --- | --- |
| `mvn -DskipTests package` | BUILD SUCCESS，81 个源文件 |
| Paper 1.21.11 启动加载 | `Enabling ForgingMaster v2.4.0`，`Done (29.099s)`，无 ERROR / Exception |
| 配置键检查 | 启动无缺失键告警（新增 `affix-scaling` / `strengthen` / `armor.tiers` 共 13 个期望键） |
| `/zhujian selftest` | 共 **274** 项，通过 274，失败 0（较 2.2 新增 121 项：老物品兼容、镶嵌孔、原版封禁、强化概率、炉火、模拟器、履历、三符、剑冢留档、竞技分级、名人堂、强化落库、词条重铸、剑意、赛季追赶、重铸界面、剑冢残影、词条套装、大赛观战、赛季传承、临摹解锁、双孔分离、护甲修正） |
| `/zhujian help` | 新增 `/zhujian strengthen` 一行正常显示 |
| 生成配置核对 | 铁剑 `bonus-attack: 1.4`、星陨 `5.25`；`affix-scaling.tiers` 五档；现行核对项为 `anvil-forge.caps` 六档（粗糙 40 / 普通 50 / 精良 60 / 史诗 72 / 传说 86 / 神话 99）全部落盘——`strengthen.caps` 阶梯仅在 `anvil-forge.enabled=false` 遗留分支生效 |
| 关服 | RCON `stop` 正常保存并退出 |

> **关于 CMI 与 Vault**：CMI 默认不会向 Vault 注册经济服务，官方要求配合 CMIInjector 或重编译版 Vault，否则 Vault 侧会报 `economy engine is missing`。所以本插件在 Vault 拿不到经济时，会直连 CMI 的 `Modules.Economy.Economy` 静态接口——这条路已实测可用，不需要额外装 injector。
>
> 两条路径的优先级是 Vault → CMI → 内置剑币。装了 EssentialsX 这类标准 Vault 经济插件时走 Vault；只有 CMI 时走 CMI 直连。
>
> 自动化能覆盖的是逻辑与命令层；**仍需真人验证**的是：锻造 GUI 的点击手感与节奏难度、宝石开孔/合成/镶嵌的整条流程、装备穿戴后的属性面板与减伤、多人同场大赛 / 决斗、以及 Vulcan 在你的客户端上会不会误报 Velocity。

---

---
