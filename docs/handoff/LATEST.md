# v2.26.7 交付报告（给失忆的自己）

> v2.26.3/v2.26.4 的内容保留在下方 §1（§1.0 是 v2.26.4 审计轮；§1.9 之后为 v2.26.5 余项）。

> v2.26.3 部分（契约 quit 真因 / 吞书 / 双触发 / 假绿钉子修复）保留在下方 §1；
> §1.9 起是 v2.26.4 追加的全仓审计轮收口。

> 上一轮：v2.26.2（上书防复制 + join 守卫；但契约刷满没断根）

## 0. 一分钟状态

| 项 | 值 |
| --- | --- |
| 版本 | **2.26.7**（整模块移除：龙税契约） |
| 产物 | `target/ForgingMaster-2.26.7.jar`（main 干净版；priv 版在 dist/） |
| `config-version` | 23（本轮不加配置键） |
| config 事故与修复 | 删 contract 段时误切走后面 10 个段（87 键丢失，自检当场红）——从上一版 git 对象重建完整 config.yml。教训：按顶层键切段，不能用两个注释锚点夹逼 |
| 全量检修 | 三路子智能体检修 27 条全部落地：Tab expect/guard 死行/死重载/死键/EvolutionStage 默认表/REQUIRED 清单/玩家文案与注释 |
| main config 补修 | 契约移除时误删 10 段的 config 修复只落在 priv——main 的 jar 内置 config 一直缺 87 键。本轮从 priv 版去 admin 段后同步 main（60 顶层段，YAML 校验过） |
| priv config 补修 | 多轮 rebase 解冲突把 priv config.yml 的 admin 段弄丢了——BUILTIN 兜底（代码默认 enabled=true + 内置 uskeno）掩盖了缺失，功能未断但配置不可调。已补回并部署 |
| 事故自纠 | cherry-pick 解冲突时把提交标题残行带进了 ForgingCommand（4cd08b5 不编译且已推送）——下一提交立即修复。教训：解完冲突必须 mvn compile 过了再 continue，commit 前后都要构建验证 |
| Tab 终审 | 36 个 tab helper 逐一对账 handler：29 全对齐；7 处差异全修——money 两处玩家名补全加 admin 门（查他人已是 admin 口径）、strengthen 补 insure 候选与 help、imprint 删幽灵 insure/baoxian、spirit 删幽灵 show/hide（v2.26.1 已删的功能）+ 补 reroll/[confirm] 三层、help 文案三处修正 |
| 自检 | **实测 549 项 / 通过 549 / 失败 0**（priv 2.26.7 真机 RCON 实测；main = 549 − 3 管理员钉 = 546 推算） |
| 诊断增强 | 模拟器概率钉子的失败信息带上 o[3]/o[4]/successChance 实际值（上轮一次红为重启竞态，重跑即绿；带数值的断言让下一条红能直接定位） |
| 静态钉子跳过数 | **0**（上一轮是 22 —— 这是本轮最重要的修复） |
**终审轮（v2.26.6 收口）**：独立回归审查确认三轮修复无 P1/P2 回归；6 条 P3 当场清掉 5 条
（Keys 注释复位、webhook 字段 volatile、行会 autosave 挂守卫、TaskGuard 重入约定、
isSimilar 收剑唯一点钉子补齐——原注释宣称有钉但没写）。剩余 1 条 P3（事件投放名额
被卸载区块条目占用的极端情况）记录备查。
| 压测 | v2.26.5：压测装置随服迁移（FM_SERVER=D:/game/1.21.10server、RCON 25575/bmtest123、PORT 25565、bot 协议 1.21.10、默认 16 bot ≤ max-players=20）。**--key 五阶段实测 107/0/0；全量 17 阶段实测 PASS 347 / WARN 2 / FAIL 0**（botCount=16 且 botCountAlive=16、skipped 0、timedOut false，`_stress-20261115-010408.json`）。2 条 WARN 均为装置边界如实声明：①比试真实结算盲驱不了（历轮已知）②1/16 bot 瞬时没生成守卫、正确退款——双花守恒由其余 15 bot 验证。装置已适配真机世界：难度自动切换（Peaceful↔easy，跑完恢复） |
| README | 396 行 / 41.4 KB（上限 450 行 / 48 KB） |
| 分支 | `main` 干净可推送；`local-priv` 私有永不推送 |

## 1. 本轮改了什么

## 1. 本轮改了什么

### 1.0 v2.26.7（main）：整模块移除龙税契约

- 删 `contract/` 包 + ContractMenu/Holder + `/zhujian contract` + 圣物加成（StrengthenService 7 点
  中性化）+ 图鉴圣物分类（六类→五类）+ forbidden 占位符 + ForgeManager autoJoin + contract.yml
  备份项（21 份）+ config.yml contract 段
- 迁移 step33：盘上残留 contract.* 整体清理（config-version 23→24）
- 钉子适配：断约罚金（引用已删文件）删除、数值夹钉去掉估值部分、contest history 钉去 pick 断言
  并加「契约残留清零」反向断言
- 强留项：剑型 FORM 键/valueMult 转展示口径、剑魂传承、CoinReason.CONTRACT 枚举（旧流水解析）


### 1.0 v2.26.4 追加：全仓审计轮（5 路并行）收口 22 条

5 路 Explore 扫描（经济守恒 / 物品复制 / 存档一致性 / 重复领取 / 注入面），每路从**发放点倒查全部入口
守卫链**。已核实并修掉：

| 组 | 内容 |
| --- | --- |
| P0 钱货时序 | `settleDay`/`pickRelic` 同步落盘（原来是 30 秒重放窗口）；谱系/图鉴/行会委托领奖 save 失败回滚「已领」拒发（原 save 吞 IOException） |
| P1 跨账本 ×7 | 剑冢复刻、秘境通关、通行证等级奖、行会解散退款、周赛会长奖、大赛 top5、行会委托——奖金全部收口 `wallet().give/giveTo`（server 模式下原来扣 Vault 发剑币账 = 凭空造币） |
| P3 | `money balance <玩家>` 查他人要 admin；8 个二级 Tab helper 权限过滤；`coins` volatile；三处饱和加法；估值链 isNaN/Infinity + 1e12 夹紧 + pool 饱和；`contract.tax.base` 夹下界；`premiumLong` contains→磁盘判定 |
| 注入面 | `Msg.esc/escText` 统一清洗口；附魔台物品名、剑冢兜底名×2、殿堂铭牌、剑谱名、蜕变品名 五处出口套用；行会名字符白名单；剑名拒收 RTL/零宽/控制字符 |

钉子 +6，v2.26.5 再 +2（余项守卫 / 回档对齐；回档对齐钉子的 marker 曾写错——restore 是
public synchronized String 签名、metrics 重建在 reloadAllSilently，自检当场抓出当场修）。
priv 分支追加：helpLines 钉子改切 3 参 owner 重载（priv 拆了重载后 2 参薄壳切不出 SAVE_FILES.length，属 priv 专属假红）。
均按仓库惯例带「找不到源码树即跳过」守卫（不带 flag 起服不假红；带 `-Dforgemaster.source-root` 才真正执行）。
**逐条证据与完整台账见 `docs/history/v2.26.x.md` 十五·五。**


### 1.1 契约刷满的真因：不是 join，是 quit（用户直接报的）

用户原话：「`/zhujian contract join` 这个他说是每次输入都能选一个，多输几次就满属性了」。

v2.26.2 以为是「重复 join」，把守卫改成「已签即拒绝」。玩家照样能刷满。

**真正的口子在另一个方法里**：

```java
// 旧 quit()
store.remove(p.getUniqueId());        // 档案整个删掉
```

`RelicPool.offer(owned, ...)` 只排除**已持有**。档案被删 → 重签时 `owned` 空
→ 从 12 个圣物里重抽 3 个。`quit`→`join` 无限循环 = 无限重掷，定向钓
「凤凰灰」（稀有度 5、每周目日免碎一次）几轮就满。

第二道口子叠在同一处：`join()` 用 `store.ensure()` 判在契，而 `ensure()` 会**建一个
`joined = true` 的空档** → 首次 join 反被判「已签」、什么都不发。

三条一起改才成立：

| # | 改法 |
| --- | --- |
| ① | 判在契用**只读** `store.run()`，不用 `ensure()` |
| ② | `quit` 只翻 `joined = false`，并清掉未领的 `pickPending` |
| ③ | 重签走「复活」分支，**继承原圣物池**，不再发三选一 |

`relicBonus()` 本就带 `!r.joined` 判断，退契期间圣物自动失效、重签自动恢复。

### 1.2 附魔台整叠吞书

`EnchantService.apply` 末尾无条件 `gui.setItem(BOOK_SLOT, new ItemStack(AIR))`。
书格放 2 本以上时，点一次只结算 1 本的代价却清空整格，多余附魔书凭空消失。
改为 `consumeOneBook()` 按件递减。

### 1.3 潜行右键铁砧开两次面板

`PlayerInteractEvent` 对一次右键会**分别**派发主手、副手两次。
`ForgeListener.onInteract` 没过滤 `getHand()` → 开两次面板（取完剑立刻又弹菜单）。
加 `!= EquipmentSlot.HAND` 返回。

### 1.4 最要紧的一条：22 条静态钉子是假绿

**这是「派了人查、跑了自检、还是漏」的真正原因**，比上面三个 bug 严重。

22 条「按源码形状断言」的钉子都写成：

```java
java.io.File pkgRoot = findSourcePackageRoot();
if (pkgRoot == null) {
    return;      // ← 找不到就 return，等价于「通过」
}
```

`findSourcePackageRoot()` 靠「从服务器工作目录向上 6 层 + 兄弟目录」猜源码树。
测试服从 `D:\IDEA\mc-plugins\ForgingMaster\testserver`（仓库兄弟目录）搬到
`D:\game\1.21.10server`（**不同盘符树**）后永远找不到。

结果：自检报 **539 / 通过 539**，其中 22 条一次没执行。日志里躺着 12 行
`[selftest] 找不到源码树，跳过 XXX`，但那是 INFO 级，没人看。

修法三层：

1. `-Dforgemaster.source-root=<仓库根>` 显式指定，优先于自动探测；
2. 找不到时打 **WARNING** 明说「这些钉子是跳过，不是通过」；
3. `tools/selftest.py` 默认带这个参数（`--source-root`）。

修完后跳过数 **0**，22 条钉子真正执行——**第一次跑就抓出我自己写的断言过严**
（`join` 里「首次签约」分支的 `ensure()` 是合法的，被我一刀切误伤），说明它活了。

### 1.5 两道门

```
mvn -B -q clean package  → exit 0
tools/selftest.py        → 544 项 / 通过 544 / 失败 0，静态钉子跳过 0
真机（Paper 1.21.10-130）→ join 刷满复现测试全过，服务器日志零异常
变异测试                 → 把 consumeOneBook 改回整格清 AIR，钉子立刻红（v2.26.3 附魔台…）
```

**压测（17 阶段全量）本轮没跑。** 发版前要补：
`python tools/guitest/run-stress.py`，且核对 `botCount=32`、`botCountAlive=32`、`FAIL 0`。

真机验证输出（契约刷满）：

```
[1] 首次 join        → 契约已签
[2] 重复 join x3     → 三次都是「你已经在契约中了」
[4] quit→join 循环x4 → 每次都「契约已续……圣物依旧在你手上」
[5] 循环后待选       → 「你现在没有待选的圣物」
```

## 2. 下一轮先做这 3 条

| # | 事 | 位置 | 为什么 |
| --- | --- | --- | --- |
| 1 | **补跑 17 阶段全量压测** | `tools/guitest/run-stress.py` | 本轮只做了手测，发版前必须补 |
| 2 | 天赋「刷满」待用户给复现步骤 | `growth/Talents.java` | 我查了加点/洗点/点数来源，没找到重复给点的结构。**等具体现象再查，比盲扫快** |
| 3 | 同一 `quit` 清档形状扫一遍 | `TombManager` / `DissolveService` / 行会 | 「退出即删档 → 重进重抽」是通用形状，不只契约有 |

## 3. 不能重犯的坑

1. **守卫加在一个方法里，漏洞可能在另一个方法里。** v2.26.2 只堵「重复 join」，
   没看 `quit` 删档。查「能不能刷」必须把**进入 / 退出 / 领奖**三条路径一起读。
2. **`ensure()` 有副作用，不能拿来判存在。** 它会建 `joined = true` 的空档。
3. **「找不到就 return」的钉子等于没有钉子。** 静态检查类钉子必须：
   找不到输入时打 **WARNING 并明确说「跳过」**，绝不能静默 return。
4. **项数没涨本身就是信号。** 本轮加 3 条钉子却只涨 1（543→544），顺着差额才查出
   22 条根本没跑。**加钉子后先看项数对不对，再看绿不绿**。
5. **变异测试是唯一能证明钉子活着的方法。** 改坏被测代码，看它红不红。
6. 其余照旧：锻造手续费永远 `stats().takeCoins`；新存档一律 `YamlIo`；
   命令三处同源；README ≤ 450 行 / 48 KB；自检与压测串行且先停服。

## 4. 命令速查

```powershell
& 'D:\apache-maven-3.8.8\bin\mvn.cmd' -B -q clean package

# 自检（先停服！--source-root 默认就是仓库根，别再手写）
cd D:\IDEA\mc-plugins\ForgingMaster-Lore
$env:PYTHONIOENCODING='utf-8'
& 'C:\Users\heiya\AppData\Local\Programs\Python\Python312\python.exe' tools\selftest.py `
    --server D:\game\1.21.10server --rcon-port 25575 --password bmtest123

# 压测
& 'C:\Users\heiya\AppData\Local\Programs\Python\Python312\python.exe' tools\guitest\run-stress.py

# 文档门
python tools\agentdocs.py check
```

## 5. 60 秒自查

- [ ] `pom.xml` 版本 = README 首行 = 部署 jar 名（**先改版本号再 build**）
- [ ] 自检项数 ≥ **544**，且日志里**没有** `找不到源码树`
- [ ] 压测 `botCount=32` 且 `botCountAlive=32`、`FAIL 0`（本轮欠账）
- [ ] README ≤ 450 行 / 48 KB
- [ ] `main` 干净可推送，`local-priv` 的提权代码没混进 `main`
- [ ] 提交推送了

## 6. 交付物

- 改动：`contract/ContractManager.java`、`enchant/EnchantService.java`、
  `listener/ForgeListener.java`、`selftest/SelfTestSuite.java`、`tools/selftest.py`
- 文档：`AGENTS.md`（544 + 静态钉子规矩）、`README.md`、`docs/history/v2.26.x.md`（十五·四）、本文件