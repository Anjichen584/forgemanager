# 铸剑大师 · ForgingMaster v2.26.6

Minecraft **1.21.11**（Paper / Purpur 兼容）锻造小游戏插件。把「烧红 → 敲打 → 叠钢 → 开刃 → 抛光 → 嵌银丝 → 淬火」的手感搬进 MC，
锻完之后还有委托、比试、耐久、剑币经济、师徒、成就、段位、剑灵、剑冢、世界事件、剧情、名人堂、商会、拍卖行、排行与赛季。

- 目标 Java：**21+**
- 构建产物：`target/ForgingMaster-2.26.6.jar`
- 无第三方依赖，纯 Paper API + MiniMessage
- `config-version`：**23**

---

## 〇、更新索引（每轮必须同步，别再让头部烂掉）

> **维护规则**：每次改动只做两件事就够 —— ① 在本表最上面加一行；② 顺手核对文件头三行（标题版本 / 构建产物 jar 名 / `config-version`）。
> 这三处曾经从 v2.19.0 一直烂到 v2.23.1（跨 7 个版本），因为只往文件末尾追加、没人回头看头部。

### 一轮改动的固定收尾（五步，缺一步都不算完成）

| 步 | 动作 | 不做的后果 |
| --- | --- | --- |
| 1 | `mvn -B clean package` | 只改 `resources/` 也必须重打 —— 配置是打进 jar 的，改盘上文件没用 |
| 2 | 部署到 `testserver` 并跑 `/zhujian selftest` | 只编译不跑，就是 v2.23.3 的状态：**5 条失败藏在里面没人知道** |
| 3 | 自检必须 `失败 0`，且断言数不少于上一版 | 断言数掉了说明有人删断言糊弄绿 |
| 4 | 更新本表**加一行** + 核对文件头三行 + 本轮叙事写进 `docs/history/v<段>.md` | 不核对头部 → 版本号跨 7 个版本没人回头看（v2.19.0→v2.23.1 真实事故） |
| 5 | 代码与文档 **同一个 commit** 一起推 | 分两次推，文档迟早漏推 |

> **第 4 步为什么不再写「新增一个小节」**：这条规则本身就是 README 涨到 4460 行的原因 ——
> 每轮追加一节，攒了 30 多轮就成了一坨。现在本轮叙事写进 `docs/history/v<段>.md`（按版本段归档），
> README 只加**一行索引**。`tools/agentdocs.py check` 会拦：README 超过 450 行 / 48 KB 直接报错。
>
> 判断标准：**「这次改动，三个月后还有人需要主动读吗？」**
> 需要 → `docs/history/`；只是本轮的来龙去脉 → `docs/handoff/archive/v<版本>.md`；两者都不写就丢结论。

> 第 2 步是真机验证，不是「看代码觉得没问题」。v2.23.4 就是这么挖出「老物品强化白做」和「每日悬赏重启变空」的 —— 两条都是**纯读代码看不出来**的缺陷。

| 版本 | 一句话 | 详情 |
| --- | --- | --- |
| **2.26.6** | **第三路审计（剧情/事件/webhook、PAPI/GUI 渲染竞态）+ 碎剑唯一 id 落地**：`ITEM_UID` 键在 ItemFactory.build 收口写一次、`isSimilar` 自动变严、碎剑收剑按 uid 精确匹配（存量老物 strike 入口惰性补发，收剑找不到宁留不吞）；`TaskGuard` 收口 8 个裸奔 ticker（大赛/事件/赛季/名人堂/殿堂/剧情/行会周赛/附魔——抛一次异常就被 Bukkit 永久取消的老病根）；webhook 熔断 `==`→`>=` 可重臂 + reload 复位；PAPI `strengthen` NPE 消除、`title` 离线不再同步读 .dat；EnchantRouter 关闭归属校验先于退款、行会仓库面板绑定 viewer；EventManager 空投「区块卸载」不再当「已消失」；story 章节 type 白名单 + 未知 type 日志。钉子 +3 | [十五·七](docs/history/v2.26.x.md) |
| **2.26.5** | **审计余项 + 自检口径换代**：蜕变 `cumulativeMult` 改 headMap 按真实阶乘乘（PDC 残留超大 stage 原来空转 2³¹ 次卡死主线程）；锻打速度乘区夹 0.1 下限（配置负值 → Infinity tick 敲打指针卡死）；附魔台扣费**前**先探随机池（注册表全空时原来扣完币矿才 return）；`stats <名>`/`profile` 改 peek（原来 admin 反复查随机名刷空档）；回档 `seasons/` 清掉备份点没有的归档（原来只增不删，季号退回能看到「未来」）；回档后看板缓存重建 + 裁剪游标重置；EnchantRouter 不再反向放行取消事件；强化殿堂物品名过 escText。钉子 +2。**自检装置换代**：旧 testserver 与 `_run_selftest_full.py` 已失效，改为真机 RCON + `-Dforgemaster.source-root`（已写入 start.bat） | [十五·六](docs/history/v2.26.x.md) |
| **2.26.4** | **全仓审计轮（5 路扫描）收口 22 条**：P0——契约结算/pick 圣物改**同步落盘**（30 秒异步窗内崩溃会整场结算重放、奖金重复发）；谱系/图鉴/行会委托领奖改 saveChecked 契约（save 吞 IOException = 重启后可重领，失败回滚「已领」拒发）；P1——剑冢/秘境/通行证/行会解散/周赛/大赛/行会委托 **7 处奖金收口 Wallet**（原来扣 Vault 币、发剑币账，跨账本凭空造币，coin-sinks=server 时变现）；P3——`money balance <玩家>` 查他人要 admin（原来零权限泄两本账）、8 个二级 Tab 管理动词按权限过滤（restore 泄备份清单）、`coins` volatile + 三处**饱和加法**（Long.MAX 溢出把余额清零）、龙税估值连乘链 isNaN/Infinity 守卫 + 1e12 夹紧（原裸奔可溢出变负池误判破产）、premiumLong 的 contains 假绿改磁盘判定；注入面——新增 `Msg.esc/escText` 统一清洗口，附魔台物品名/剑冢兜底名/殿堂铭牌/剑谱名/蜕变品名 五处出口套用，行会名字符白名单，剑名拒收 RTL/零宽字符。自检钉子 +6 | [十五·五](docs/history/v2.26.x.md) |
| **2.26.3** | **契约刷满真因 + 自检系统性假绿**：`contract join` 刷满不是「重复 join」而是 `quit` 把档案整个 remove（重签时 `owned` 清空 → 重抽 3 个圣物，反复退出重进即可定向钓凤凰灰）；现 `quit` 只翻 `joined=false`，重签继承圣物池。另修附魔台整叠吞书（书格放 N 本点一次全清）、潜行右键铁砧主副手各开一次面板。**最要紧的是自检**：`findSourcePackageRoot()` 找不到源码树就 `return`，22 条静态钉子在新测试服（不同盘符树）下一条都没跑，界面照样 539/539 全绿 —— 加 `-Dforgemaster.source-root` 后才真正执行 |
| **2.26.2** | **两条「刷物品 / 刷资源」堵口**：附魔上书防复制（`applyBook` 原来无条件写回主手，主手书+副手剑右键一次白丢一本书还多出一把剑——量产复制；现按「主手是否还拿书」写回原手，监听器成功后才扣书）；contract join 重复领圣物（已签守卫原来带 day 例外，周目第 1 天重复输入每次重发一轮三选一，刷满圣物池；改已签即拒绝）。自检钉子 +2（计数守恒 + 守卫形状）（自检项数 539→541，自检装置本轮未跑：旧 testserver 路径已不存在） | [十五·三](docs/history/v2.26.x.md) |
| **2.26.1** | **三件「不可逆操作」与「回档链路」收口**：悬赏领赏改**两段式确认**（原来一步收剑、不可撤销，玩家整理背包时手滑就丢主属性最好的剑；现照 `spirit reroll` 的写法预览 + `confirm`，并用「品质+强化+锻阶+剑名+灵性」指纹把确认绑到预览那把剑上，退服即清）；`bounty-data.yml` 补 `flush`（原是全仓唯一「有 reload 无 flush」的项，回档后内存旧 `claimed` 会整份回写、静默吞掉回档）；`HallShrine` 补 `reloadOrigin`（`origin` 是字段缓存，回档换了 `hall.yml` 它不知道，殿堂原点停在旧坐标直到重启）。`metrics-export-*.csv` 与 `papi-cache/` 查证后确认**不该进备份**（可再生 / 是缓存），改为写清原因。**另按用户要求整模块移除极限锻造（`forge risk`）与剑身环绕（`spirit show\|hide`）**——两者都来自 `5c9b40e`「v2.23.1 查错+收敛」，非本轮所加；连带清掉整条碎剑率统计链路（`breaks` 唯一数据源就是炸剑，留着是永远 0 的假指标）、`risk-made`/`risk-lost` 称号维度、两个 PDC 键（自检 539/539，550→539 是随功能删钉） | [十五·二](docs/history/v2.26.x.md) |
| **2.26.0** | **战力曲线 + 历届赛果 + 比试排行 + 公开档案**：新增 `power compare` / `power log`（最近 12 条评分曲线）、`contest history`（历届冠军档案）、`rank duel` 第六维、`stats of`（白名单公开档案）；**三轮审计共修 26 条**：`stats of` 用 `get()` 会为任意名字建空档案（改 `peek`）、`contest.history-keep` 只在 Java 默认值里（补 config + step32）、三份存档漏在备份清单外、`metrics/` 整目录替换是死代码（快照实为根目录散文件）、`story-progress.yml` 裸 `save()` 违反 YamlIo 规则、存档守卫表三行文件名写错导致三个模块从未被检查、压测超时会读旧报告假装全绿（自检 550/550 当时、压测 32 并发 17 阶段 667/1/0） | [十五·一](docs/history/v2.26.x.md) |
| **2.25.9** | **三条「钱货时序」收口**：金库存钱 / 捐基金写盘失败就退回（虚拟钱不进内存账）、签到存档失败回滚内存进度（不再「已签但没发奖」）；行会两个钱口统一走 `wallet().take`；压测补 3 阶段并修掉「掉线 bot 仍参与断言」的脚本坑（自检 528/528，压测 318/0/0） | [十四·补二十](docs/history/v2.25.x.md#十四补二十v2259行会金库的账本口径--签到落盘返回值--压测补三个阶段) |
| **2.25.8** | **签到发奖顺序 + 取剑写盘成败**两条 P1 收口：构造奖励提到发钱之前、写盘失败不再「币到手记录回滚」；取剑落盘失败不再静默（自检 525/525） | [十四·补十九](docs/history/v2.25.x.md#十四补十九v2258签到与取剑的两处钱货时序) |
| **2.25.7** | **四条 P1 收口（钱货时序）**：付费轨「先扣钱后记录」→ 先落盘再扣钱（写盘失败一分不扣）；领奖记录落盘失败不再静默（可对账，防重启重领）；黑市限购改成「先占额度→落盘→才发货」（防崩溃刷折扣货）；`wallet().give` 返回值不再丢（照拍卖行 `warnLost`）；`anvil-forge.enabled` 补到 `strike`/`strikeBatch`（原来只关了进场） | [v2.25.x](docs/history/v2.25.x.md) |
| **2.25.6** | **三条 P0 收口 + 砧位并发写盘**：秘境「守卫失踪」被当成通关（300 门票换 800 币可无限刷）、剑冢冷却退服即清空（24h 冷却失效）、订单托管扣剑币却退金币（1:1 洗钱口）；顺带修 `YamlIo` 固定 tmp 名与 `anvils.yml` 无写锁（/reload 撞异步冲刷会复制或丢剑）。新增 `/zhujian anvil` 自查（自检 520/520） | [十四·补十五](docs/history/v2.25.x.md#十四补十五v2256三条-p0-收口--砧位并发写盘) |
| **2.25.5** | **T0 收口：存档原子写 + 坏档留档**：17 份存档从裸 `yml.save` 换成 tmp→fsync→ATOMIC_MOVE，坏档改名 `.corrupt-*` 不再被当成空档覆盖；修掉 `anvils.yml`（砧位上的玩家实物剑）、`pass-data.yml`（付费轨已购）、`codex claimed`（清空即可重复领奖造币）三个 T0；`pass` 清脏条件恒真、契约入贡 30 秒吞剑窗口同修（自检 517/517） | [十四·补十四](docs/history/v2.25.x.md#十四补十四v2255t0-收口存档原子写--坏档留档) |
| **2.25.4** | **压测面 7→12 阶段 + 4 并发 PASS 53**：新增兑换/词条剥离/剑冢取回/竞价/只读风暴五个并发面；加管理员命令 stats rep give\|take（声望首个手动入口）；顺手把「哪些扣费跟 coin-sinks 走、哪些永远走剑币」摸清写成表（自检 515/515） | [十四·补十三](docs/history/v2.25.x.md#十四补十三v2254压测面扩到-12-阶段账本口径分类) |
| **2.25.3** | **/zhujian help 收口**：原来漏了 11 条命令（contract / shangjin / money / spirit / tomb / tombrun / redeem / forge / event / ench / evolve）；改成「玩家段 + 管理员段」两段式，清单抽成纯函数由自检逐条核对 | [十四·补十二](docs/history/v2.25.x.md#十四补十二v2253zhujian-help-收口玩家段--管理员段自检逐条核对) |
| **2.25.2** | **4 并发实测 + 查错轮4**：21 条全修 —— 6 个异步冲刷任务被异常打死、两本账漏改的商会/订单、退服不清的二次确认，自检 513/513；新增 4 bot 并发压测器 31 PASS | [十四·补十一](docs/history/v2.25.x.md#十四补十一v22524-并发实测--查错轮421-条全修) |
| **2.25.1** | **查错轮3（组合面）**：16 条全修 —— 保险双账本、重抽净丢词条、比试刷分、无主 holder、ContractStore 丢脏窗口、迁移死代码 | [十四·补十](docs/history/v2.25.x.md#十四补十v2251查错轮3--组合面16-条全修) |
| **2.25.0** | **拓展第二轮**：血怒/坚韧附魔 + 灵契重抽 + 强化保险；查错轮2 修 15 条，自检 511/511 | [十四·补九](docs/history/v2.25.x.md#十四补九v2250拓展第二轮血怒坚韧--灵契重抽--强化保险) |
| **2.24.1** | **查错轮2**：15 条全修（回档脏冲刷盖回、领奖可复领、黑市跨零点、备份原子化…），自检 498/498 | [十四·补八](docs/history/v2.24.x.md#十四补八v2241查错轮215-条全修) |
| **2.24.0** | **三方向拓展**：附魔新机制 淬锋/饮命 + 词条 冰噬/处决；剑灵灵击 / 冢主温养；商会基金科技 / 声望兑换所，自检 498/498 | [十四·补七](docs/history/v2.24.x.md#十四补七v2240三方向拓展新机制附魔--剑灵灵击--商会科技与声望兑换) |
| **2.23.7** | **全面查错轮**：14 条发现修 13（卸力反向增伤 P1、残影代打、赛季通重复领币…），真机自检 484/484 | [十四·补六](docs/history/v2.23.x.md#十四补六v2237全面查错轮14-条发现修-13) |
| **2.23.5** | **「内置耐久不掉」的真因是 Lore 数字冻住（5%=195 刀才跳一次）**，档宽加绝对上限；Tab 补全改成「分派表镜像」+ 自检钉子，484/484 | [十四·补五](docs/history/v2.23.x.md#十四补五v2235内置耐久不掉的真因与-tab-补全收口) |
| **2.23.4** | **真机自检 483/483 全过**：修掉 5 个只有进服才暴露的缺陷（老物品强化白做、每日悬赏重启变空…） | [十四·补四](docs/history/v2.23.x.md#十四补四v2234真机自检第一次把插件跑起来) |
| **2.23.3** | 机制型原创附魔：12 条原版没有的机制（溅射/蓄势/刻痕/卸力/替身…）+ 54 格附魔台 | [十四·补三](docs/history/v2.23.x.md#十四补三v2233机制型原创附魔不是原版的数值皮肤) |
| ~~2.23.2~~ | ~~原创附魔台初版：15 条附魔~~ —— **已被否掉并整条替换**（名字与机制都是原版附魔换皮） | 见 [十四·补三](docs/history/v2.23.x.md#十四补三v2233机制型原创附魔不是原版的数值皮肤) 开头的教训表 |
| 2.23.1 | 全面查错 + 数值收敛：属性膨胀 P0、伤害预算夹子、存量旧装备收敛、成品界面「取回无反应」P0 | [十四·补二](docs/history/v2.23.x.md#十四补二v2231全面查错与修复两条-p0二十余条-p1p2) · [audit/32](audit/32-verify-2.23.1.md) |
| 2.23.0 | 剑体玩法重定、耐久账补全、全量拓展（收藏图鉴 / 剑魄 / 星陨套装 / 名剑谱 / 通行证 / 看板 / Webhook） | 十四·补 |
| 2.22.x | 手上悬浮剑与护甲放大下线、配置漂移治理 | 十四·补 |
| 2.21.0 | 沉浸式强化、蜕变独立、剑体放大 | 十五 |
| 2.20.0 | 经济流水与服主数据看板（切片 1） | 十六 |
| 2.19.2 | 配置漂移、黑市空货架、蜕变重排 | 十七 |
| 2.19.1 | 双账本对账、模拟器口径、高段曲线 | 十八 |
| 2.19.0 | 收尾双工序（抛光 / 嵌银丝） | 十九 |

**2.23.1 这一轮到底修了什么**（服主最该先看的四条）：

1. **属性膨胀 P0** —— 强化时 `baseOf()` 把「已被倍率乘过的值」当成基准再乘一次，越锤越离谱且写回污染快照。修法：基准值加天花板 + 快照只存干净值。
2. **伤害预算夹子** —— 攻击属性 ≤22、单次伤害 ≤60、吸血 ≤20%（单刀 ≤2 点、≤10% 最大生命）、减伤 ≤35%、气血 ≤20、点燃 ≤10 秒。夹子**必须在暴击之后**，否则满配照样一刀 130。
3. **存量旧装备收敛** —— 产出侧的夹子管不到盘上的旧剑。新增 `LegacyItemRepair`：`/zhujian fixattrs [all|containers]` + 玩家上线自动扫（不碰 playerdata）。收敛只动数值，Lore 不动，末尾追加一行「◆ 已收敛」说明。
4. **成品界面「取不回」P0** —— `open()` 里 `transitioning` 同步清掉，而旧界面的 `InventoryCloseEvent` 常常下一 tick 才送达 → 进成品界面那一次「切换」被当成「玩家关了界面」，会话被销毁：界面还在、点不动、ESC 后剑看起来消失。修法是语义判断 `isTransitionClose()` + 下一 tick 清标志 + `reclaim()` 兜底。

---

## 一、功能概览

| 模块 | 简介 |
| --- | --- |
| 锻造七工序 | 加热（控温）→ 锻打（节奏）→ 叠钢（序列连点）→ 开刃（控压）→ 抛光（光泽下刀）→ 嵌银丝（亮窗抢点）→ 淬火（选介质定词条），总分 100 制（v2.19 加收尾双工序） |
| 耐久口径 | 只有插件自己的虚拟耐久，原版耐久条不参与（2.10.4） |
| 词条分档 | 词条数值按材料档位缩放：铁剑又低又稳，星陨又高又飘（v2.4） |
| 锻造等级 | 花剑币强化锻造品，上限按品质 40 → 99（粗糙 40 / 普通 50 / 精良 60 / 史诗 72 / 传说 86 / 神话 99），每级 +0.6% 基础属性，只放大基础属性（v2.17.3） |
| 剑币经济 | 锻造、委托、大赛、剑冢、拍卖行、师徒分成、成就都能产出；商会、拍卖、剑灵、剑冢收取 |
| 连锻链 | 连续成功锻造叠倍率剑币，炸剑或超时断链 |
| 师徒 | 拜师收徒，徒弟锻造时师父分剑币，师父在线时徒弟容差加成 |
| 成就与匠衔 | 20 项成就（发剑币与积分）；声望划分匠衔段位，赛季回落 |
| 环境修正 | 天气与时辰影响加热、淬火收益、叠钢时间与开刃绿区 |
| 剑灵 | 击中敌人养灵，可喂养、起名，灵性到档位解锁灵契词条 |
| 剑冢 | 埋剑入冢全服可见，他人付费挑战复刻，成功得剑币与声望 |
| 世界事件 | 定时或手动开启（如陨星降临），地面刷星材 |
| 剧情 | 按锻造与委托进度推进章节 |
| 名人堂 | 盔甲架陈列前几名，定时刷新 |
| 商会 | 用剑币买材料与图纸 |
| 拍卖行 | 挂单、竞价、一口价、领取，成交收抽成 |
| 排行与赛季 | 排行榜 GUI、档案面板；按时长自动结算赛季，归档前二十名 |
| 词条符 | 把一条词条从一把剑上剥下来，原值装进另一把；武器 / 装备分池（v2.8） |
| 收藏图鉴 | `/zhujian collection`：五类收藏进度 + 分类奖励 + 全收集称号，进度存 `codex-data.yml`（拓展第一档） |
| 剑魄分解 | `/zhujian dissolve [confirm]`：主手锻造品销毁产出「剑尘」，不带 confirm 只报价 |
| 词条合成 | `/zhujian imprint merge`：3 张同名词条符 → 1 张数值 ×1.5 的同名词条符 |
| 星陨套装 | `/zhujian relicset`：按材料件数判定的独立套装（config 驱动，与词条套装共存），示例套装带「落星斩」技能（拓展第二档） |
| 名剑谱 | `/zhujian spectrum`：条目 config 驱动的名剑谱，达成登记可领奖，记录存 `spectrum-data.yml`（拓展第二档） |
| 赛季通行证 | `/zhujian pass`：免费轨道通行证，锻造行为攒进度，记录存 `pass-data.yml`（拓展第二档） |
| 行会商会 | `/zhujian guild`：行会商会社交层，数据存 `guild-data.yml`（拓展第三档） |
| 名人殿堂 | `/zhujian hall`（admin）：ItemDisplay 平躺展示排行前三名，30s 定时重刷（拓展第一档） |
| PAPI 占位符 | 装 PlaceholderAPI 后开放 8 个 `%forgemaster_*%` 占位符（反射挂载，没装静默跳过）（拓展第一档） |
| 匠人天赋 | 每 5 级 1 点，七项自选（容差 / 命中区 / 控火 / 叠钢 / 开刃 / 词条 / 经验）；经验曲线同时砍陡（v2.9） |
| 备份回档 | 一键备份存档文件，回档前自动备份当前存档 |
| 黑市 | `/zhujian blackmarket`（别名 heishi）：每个现实日按权重抽 4 件消耗型特价上架（只卖符 / 锻造原料 / 图纸），折扣 0.55~0.8；同一天全服同货同价，每人每件每天限购 2 次，进度存 `daily-data.yml`（v2.18.0） |
| 每日签到 | `/zhujian signin`（别名 qiandao）：7 天一轮递增（第 7 天大奖 2500 币 + 旺火符×2），断签不清零——只记本轮内已签天数，隔天没签不前进也不清零，下次接着领下一档（v2.18.0） |
| 剑冢秘境 | `/zhujian tombrun`（别名 mijing）：付 300 币门票连刷 3 波残影守卫（2 / 3 / 4 只，血攻倍率 0.8 / 1.0 / 1.3），180 秒内清算通关得 800 币 + 2 张残页，残页可在剑冢挑战时每张抵 10% 挑战费（一次至多 5 张）（v2.18.0） |
| 师徒出师 | `/zhujian master graduate` + `graduate-confirm` 两步确认（30 秒内有效）：满足条件（累计锻造 100 / 声望 200 / 最高强化 +20 / 最高锻阶 2）后出师，师徒分成即止、不占解除冷却，师徒各领出师奖励（v2.18.0） |
| 称号扩展 | `/zhujian title`（别名 chenghao）：称号 config 条件解锁（5 种条件：材料 / 强化等级 / 锻阶 / 品质 / 统计），手动佩戴优先于声望称号，聊天前缀经 `%forgemaster_title%` 渲染，内置 5 条示例（v2.18.0） |

---

## 二、命令与权限

### 玩家命令（默认权限 `forgemaster.use`，所有人可用）

| 命令 | 说明 |
| --- | --- |
| `/zhujian` | 打开锻造台（别名 `/forge` `/zj` `/dazhu` `/zhujianmaster`） |
| `/zhujian stats [玩家]` | 锻造档案（查他人需 `forgemaster.admin`） |
| `/zhujian profile [玩家]` | 档案面板：剑币、连锻、声望、成就（查他人需 `forgemaster.admin`） |
| `/zhujian talent` | 匠人天赋面板：等级、已点 / 可用点数、七项进度与当前合计（别名 `tianfu`） |
| `/zhujian talent add <id>` | 加一点天赋（`touch` `eye` `ember` `fold` `edge` `luck` `study`） |
| `/zhujian talent reset [confirm]` | 洗点：不带 confirm 只报价，带 confirm 才真洗（收剑币） |
| `/zhujian rank [coins\|reputation\|streak\|level] [text]` | 排行榜 GUI，`text` 输出文字版 |
| `/zhujian season` | 查看当前赛季 |
| `/zhujian codex` | 锻造图鉴 |
| `/zhujian env` | 当前天气 / 时辰 / 修正面板 |
| `/zhujian story` | 当前剧情章节 |
| `/zhujian board` | 委托板 |
| `/zhujian duel <玩家>` | 发起比试；`duel accept` 应战，`duel deny` 拒绝 |
| `/zhujian repair` | 研磨修复手上的剑 |
| `/zhujian manual` | 剑谱库 |
| `/zhujian record` | 登记手上的成品为剑谱 |
| `/zhujian copy <谱号>` | 临摹；`copy stop` 取消 |
| `/zhujian gem esocket` | 开一个附魔孔（v2.10，与宝石孔分开） |
| `/zhujian contest` | 大赛状态；`contest list` 看榜 |
| `/zhujian grade` | 材料分级对照 / 手上档次 |
| `/zhujian salvage` | 熔掉手上的锻造品，回一半材料 |
| `/zhujian top` | 名人堂（按最高分） |
| `/zhujian achievements` | 成就界面 |
| `/zhujian shop` | 商会 |
| `/zhujian gem` | 宝石台：开孔 / 熔炼 / 镶嵌（别名 `baoshi`） |
| `/zhujian gem socket\|smelt\|inlay\|extract\|merge` | 直接执行对应一步 |
| `/zhujian enchant` | 附魔书镶孔：主手装备 + 副手附魔书，与宝石共用孔位（别名 `fumo`） |
| `/zhujian enchant extract` | 取回最后一颗镶进去的附魔，成功时变成附魔书（别名 `quetu` `chai`） |
| `/zhujian strengthen` | 强化主手锻造品的锻造等级（别名 `qianghua`）；加 `info` 看当前等级、上限、下一级花费 |
| `/zhujian strengthen sim [等级]` | 期望模拟：锻到目标要多少材料/剑币、期望碎几次、达成率（纯模拟不扣费，别名 `moni`） |
| `/zhujian strengthen curve` | 六档曲线表：+40/+50/+60/+72/+86/+99 逐档的达成率与期望消耗，带相邻档边际成本（只读，v2.19.1，别名 `quxian`/`tubiao`） |
| `/zhujian strengthen reforge [序号]` | 词条重铸：只重抽一条，不列出序号时显示全部词条与预览（别名 `chongzhu` `xilian`） |
| `/zhujian strengthen reforge gui` | 打开词条重铸界面（同一功能的图形版，带二次确认） |
| `/zhujian strengthen jianyi` | 看手上这把剑的剑意档位与加成（别名 `prof` `shulian`） |
| `/zhujian strengthen strip [序号]` | 词条符：把第 N 条词条剥成可交易的「词条符」，不带序号只列词条与费用（别名 `bo`） |
| `/zhujian strengthen graft` | 词条符：主手锻造品 + 副手词条符，把词条原值装进去（别名 `yizhi` `zhuang`） |
| `/zhujian tomb challenge <编号>` | 召唤那条剑冢记录的残影，打赢返材料与彩头（界面左键同理） |
| `/zhujian contest watch` | 大赛实时观战面板（每秒刷新，别名 `guanzhan` `kan`） |
| `/zhujian contest gui` | 大赛赛况面板（含观战入口） |
| `/zhujian top legacy` | 历代名剑（赛季传承记录，别名 `lishi` `chuancheng`） |
| `/zhujian auction` | 拍卖行（`sell` / `bid` / `buy` / `cancel` / `claim`） |
| `/zhujian collection`（别名 `shoucang` `tujian`） | 收藏图鉴：五类收藏进度、分类奖励与全收集称号 |
| `/zhujian dissolve [confirm]`（别名 `jiapo` `fenjie`） | 剑魄分解：销毁主手锻造品产出「剑尘」，不带 confirm 只报价 |
| `/zhujian imprint merge`（别名 `cifu`） | 词条合成：3 张同名词条符 → 1 张数值 ×1.5 的同名词条符 |
| `/zhujian relicset`（别名 `taozhuang`） | 星陨套装状态查询（纯读）：按材料件数判定的套装效果与「落星斩」 |
| `/zhujian spectrum`（别名 `mingjian` `pu`） | 名剑谱：条目 config 驱动，达成登记可领奖 |
| `/zhujian pass`（别名 `tongxingzheng`） | 赛季通行证（免费轨道）：按锻造行为攒进度 |
| `/zhujian guild`（别名 `hanghui`） | 行会商会 |
| `/zhujian blackmarket`（别名 `heishi`） | 黑市（v2.18.0）：今日特价面板，按现实日轮换——每天按权重抽 4 件上架、折扣 0.55~0.8，同一天全服同货同价，每人每件每天限购 2 次 |
| `/zhujian signin`（别名 `qiandao`） | 每日签到（v2.18.0）：每天领 `daily.rewards` 当前档奖励，7 天一轮递增；断签不清零，下次接着领下一档 |
| `/zhujian tombrun`（别名 `mijing`） | 剑冢秘境（v2.18.0）：付门票连刷 3 波残影守卫，限时 180 秒全清算通关，得剑币与残页；超时 / 死亡 / 跑太远 / 放弃判负，门票不退 |
| `/zhujian title`（别名 `chenghao`） | 称号面板（v2.18.0）：列出全部称号与解锁条件，点击佩戴 / 脱下；条件 config `titles` 定义，解锁实时推导 |
| `/zhujian master [玩家\|leave\|expel 徒弟名]` | 师徒：拜师 / 离开 / 逐出 / 查看状态（别名 `shifu`） |
| `/zhujian master graduate`（别名 `graduates` `chutu`） | 师徒出师·第一步（v2.18.0）：预约出师，条件齐了才放行（累计锻造 / 声望 / 最高强化 / 最高锻阶，config `mentor.graduation.*`） |
| `/zhujian master graduate-confirm`（别名 `queren`） | 师徒出师·第二步（v2.18.0）：30 秒（`confirm-seconds`）内确认；出师后师父分成终止、不占解除师徒的拜师冷却，师徒各领奖励 |
| `/zhujian accept` | 收下拜师邀请 |
| `/zhujian spirit [info\|feed\|name 剑名\|show\|hide]` | 剑灵（别名 `jianling`）；`show` / `hide` 开关主手显形浮游体（灵性达标才有效） |
| `/zhujian tomb [list\|bury\|take 编号\|challenge 编号\|info 编号\|remove 编号]` | 剑冢（别名 `jiantomb` `jianzhong`；`remove` 需 admin） |
| `/zhujian event [status]` | 查看世界事件 |
| `/zhujian contract [join\|quit\|deposit]`（别名 `longshui` `shui`） | 龙税契约（v2.19.2）：不带参开面板，`join` 签约 / `quit` 退出 / `deposit` 交税 |
| `/zhujian shangjin [claim <序号>]`（别名 `bounty` `xuanshang`） | 每日悬赏：不带参看今日货单，`claim` 领赏（按现实日换单，v2.23.4 修「重启当天变空」） |
| `/zhujian order [post\|cancel]`（别名 `dingdan`） | 锻造订单：不带参开面板 · `post` 发单悬赏 · `cancel` 取消 |
| `/zhujian redeem [info\|coin <数量>\|charm <符id>]`（别名 `duihuan` `huan`） | 声望兑换所（v2.24）：声望换剑币 1:10（单次 ≤500）或保底护胚类符 |
| `/zhujian power`（别名 `zhanli`） | 战力评分：手上这把剑的分数拆解与建议价（v2.23 切片 5） |
| `/zhujian ench [open\|list\|book <id> [等级]]`（别名 `fumotai` `yuangchuang`） | 原创附魔台 54 格（v2.23.3 机制型附魔，与 `enchant` 的附魔书镶孔是两回事） |
| `/zhujian hammer`（别名 `chongchui` `tiechui`） | 领一把铁匠重锤（原版重锤 MACE 也认） |
| `/zhujian evolve [sim\|now]`（别名 `tuibian` `jinhua` `jieduan`） | 六阶蜕变：不带参开界面 · `sim` 只看结果 · `now` 直接掷 |
| `/zhujian help` | 帮助：**玩家段 + 管理员段**两段式，管理员看两段、普通玩家只看玩家段（v2.25.3） |

### 管理员命令（权限 `forgemaster.admin`，默认 OP）

| 命令 | 说明 |
| --- | --- |
| `/zhujian give <玩家> <剑胚> [品质] [分数]` | 直接发一把成品（玩家需在线） |
| `/zhujian give <玩家> armor-<剑胚>-<部位> [品质] [分数]` | 直接发装备，部位 `helmet/chestplate/leggings/boots`，例 `armor-iron-chestplate LEGENDARY 90` |
| `/zhujian gem give <玩家\|all> <类型\|random> [L1~L5] [数量]` | 发宝石（v2.16.3），控制台可用；等级也认旧名 `COMMON/FINE/EPIC/LEGENDARY` |
| `/zhujian gem list` | 列出 11 种宝石的 id / 中文名 / 效果，发东西时对照 |
| `/zhujian money [balance] [玩家]` | 查余额（玩家查自己可省略参数） |
| `/zhujian money give\|take <玩家> <数量>` | 发钱 / 扣钱，自动走当前经济后端（在线玩家） |
| `/zhujian reload` | 重载 config（不会补全缺失的新段，见升级指南） |
| `/zhujian selftest` | 运行时自检 |
| `/zhujian season end` | 立即结算当季（归档 + 重置剑币 / 声望 / 连锻） |
| `/zhujian contest start` | 强开一场大赛 |
| `/zhujian grade give <材料> <档次> [数量]` / `grade up` | 发分级材料 / 手上升一档 |
| `/zhujian halloffame set` / `clear` | 把名人堂盔甲架设在脚下 / 清理 |
| `/zhujian hall set\|show\|del` | 名人殿堂：脚下设原点 / 立即重摆前三名 / 删除（ItemDisplay 平躺展示，30s 自动重刷） |
| `/zhujian event start <id>` / `stop` | 手动开启 / 结束世界事件 |
| `/zhujian backup` | 备份存档并列出备份 |
| `/zhujian restore <备份id>` | 回档（回档前自动备份当前存档；回档后**要重启服务器**，否则内存会把旧数据写回去） |
| `/zhujian fixattrs [all\|containers]`（别名 `xiufu`） | **存量数据同步**：不带参收敛主手那一件 · `all` 扫全部在线玩家（背包+盔甲+副手+末影箱）· `containers` 扫已加载区块的容器（v2.23.1） |
| `/zhujian config check [all]` | 配置体检：盘上缺哪些必需键 + 「只写在代码里」的实际生效值（只读，v2.19.2，别名 `peizhi`） |
| `/zhujian metrics [trend\|csv\|sample\|gui]` | 经济看板：不带参实时报表 · `trend [天数]` · `csv [天数]` · `sample` 立刻采样 · `gui` 三页面板（服主，v2.20.0，别名 `jingji`） |
| `/zhujian givefull <玩家> <剑胚\|armor-剑胚-部位> [品质]` | 懒人指令：一次发出满配锻造品（满强化 · 满蜕变 · 满词条 · 满宝石，v2.21.0，别名 `maxitem`/`manpei`） |
| `/zhujian money coin give\|take <玩家> <数量>` | 内置剑币账本的发放 / 扣除（与 `money give\|take` 走同一 `Wallet` 收口） |
| `/zhujian stats rep give\|take <玩家> <数量>` | 发 / 扣声望（v2.25.4）：`take` 不够**整条不动**；声望不是货币，不跟 `economy.coin-sinks` 走 |

### PlaceholderAPI 占位符（拓展第一档）

装上 PlaceholderAPI 即自动挂载（反射，没装 PAPI 或 `placeholder.enabled: false` 时静默跳过），共 8 个：

| 占位符 | 说明 |
| --- | --- |
| `%forgemaster_coins%` | 当前剑币 |
| `%forgemaster_reputation%` | 声望 |
| `%forgemaster_title%` | 称号渲染（v2.18.0）：手动佩戴的称号优先，没佩戴回退声望称号（匠衔段位名） |
| `%forgemaster_top_quality%` | 历史最高品质名 |
| `%forgemaster_top_score%` | 历史最高锻造评分 |
| `%forgemaster_strengthen%` | 历史最高强化等级 |
| `%forgemaster_forge_count%` | 累计锻造次数 |
| `%forgemaster_forbidden%` | 是否在契（龙税周目） |

### 其他

- **潜行 + 右键铁砧**：直接打开锻造台（`settings.anvil-shortcut`）。
- 主菜单若干格位提供委托板、图鉴、档案、成就等入口。

> 说明：`plugin.yml` 的 `usage` 已在 v2.25.3 按上面两张表重写（`/help zhujian` 看到的就是它）；`permissions` 段仍是 1.x 的简略描述，以本节和代码为准。
>
> **help 与补全同源**：`/zhujian help` 的内容由 `ForgingCommand.helpLines(label, admin)` 产出，可见性（玩家段 / 管理员段）与首层 Tab 补全、`ADMIN_ONLY` 名单三处同源；自检里有一条钉子逐条核对「分派表每个主名都在 help 里」，漏写直接红。

---

## 三、配置说明（`config.yml`）

配置分段，每段都有中文注释。简述：

| 段 | 作用 |
| --- | --- |
| `settings` | 锻造核心参数：温度、风箱、锤速、叠钢、开刃、**抛光 `polishing-enabled/passes/speed/window`**、**嵌银丝 `inlay-enabled/window-ticks/length`**、专精、耐久、研磨、比试、剑名池、谱库上限、演出开关 |
| `contest` | 大赛开关、间隔、时长、最少在线人数 |
| `rank` | 排行榜 GUI 开关 |
| `season` | 赛季开关、天数、起始编号（之后以 `season.yml` 为准） |
| `coins` | 剑币产出数值：锻造底薪与倍率、委托、大赛名次奖励 |
| `commissions` | 委托开关、板位数、过期时间、委托人名字池 |
| `materials` | 剑胚：成品物品、最佳温度与容差、锤数、命中区、消耗材料、基础攻速 |
| `affixes` | 词条：种类（属性 / 暴击 / 吸血 / 点燃）、取值区间、权重 |
| `affix-scaling` | 词条按材料档位缩放：1~5 档各自的数值倍率 `value` 与波动倍率 `spread` |
| `strengthen` | 锻造等级：开关、每级加成、总封顶、三符 `talisman`/`steady`/`kindle`、炉火 `furnace.*`。成功率 / 碎裂率现行走 `anvil-forge.success.*` / `anvil-forge.break.*`——`success.*` 与 `fail.break-*` 是死键（仅存档兼容，调了无效）；`cost.*` / `material.*` / `caps` 仅 `anvil-forge.enabled=false` 的遗留分支生效 |
| `quench` | 淬火介质：加成、词条池、图标 |
| `mentor` | 师徒：开关、徒弟上限、邀请时效、解除冷却、分成比例、容差加成 |
| `environment` | 天气与时辰修正 |
| `shop` | 商会：开关、材料单价（按档次）、图纸单价、特殊道具 `specials.*`（护胚符 / 稳胚符 / 旺火符） |
| `duel` | 竞技分级：开关、档位边界 `bounds`、是否允许跨档、跨档是否压档 |
| `reforge` | 词条重铸：开关、费用 `cost.*`、材料 `material.*`、装备词条池 `armor-pool`（默认复用 `ForgeManager.ARMOR_AFFIX_POOL`） |
| `imprint` | 词条符：开关、词条上限 `max-affixes`、剥离费 `strip.*`、移植费 `graft.*`（v2.8） |
| `hall-of-fame.strengthen` | 名人堂「最高锻造等级」：开关、最低登记等级、播报、是否多摆一排 |
| `rank.strengthen` | 排行「强化」维度：开关、缓存秒数（数据为历史最高等级） |
| `progression` | 专精经验：曲线 `curve-base` / `curve-per-level`、单次收益 `gain-*`、等级上限（v2.9） |
| `talent` | 匠人天赋：每几点给 1 点 `points-per-level`、洗点费用 `reset-cost`（v2.9） |
| `proficiency` | 剑意：开关、Lore 开关、门槛 `tiers`、档名 `tier-names`、加成 `bonuses`、PVP 开关、播报门槛 |
| `strengthen.season-catchup` | 赛季追赶：开关、上限 `max-bonus`、等级阈值 `level-threshold`、每日 `per-day` |
| `events` | 世界事件：间隔、时长、刷星频率、各事件类型 |
| `spirit` | 剑灵：灵性上限、喂养花费、灵契词条数、档位名 |
| `tomb` | 剑冢：每人上限、取回规则、挑战费与奖惩 |
| `hall-of-fame` | 名人堂：陈列人数、刷新间隔、位置（由 `halloffame set` 写入） |
| `achievements` | 成就：开关与各成就定义（指标、需求、奖励） |
| `craft-rank` | 匠衔段位：依据、赛季回落、档位门槛 |
| `auction` | 拍卖行：开关、抽成、单价上限、最小加价、挂单时长 |
| `economy` | 货币后端（auto / vault / cmi / coins）与货币名后缀 |
| `armor` | 装备锻造开关与价格系数 |
| `gems` | 宝石：开关、**按材料档位的孔数上限**、开孔费用与材料、熔炼费用与投料量、镶嵌 / 附魔镶孔 / 拆卸 / 合成费用与成功率 |
| `compat` | 反作弊兼容：开关、移速与击退的收敛上限 |
| `difficulty` | 难度曲线：各工序随档位加码的系数（含 v2.19 的 `polish-speed-per-tier` / `polish-window-per-tier` / `inlay-window-per-tier`）与总分压分 |

- `materials` 的 `price` 是该档锻造费；`costs` 支持多耗材（星陨剑就是五种矿物块），单材料仍可用 `cost-item` / `cost-amount`。

- `materials`、`affixes`、`quench` 的字段说明写在 `config.yml` 各段注释中。
- 锻造耗材在菜单、熔炼、委托报酬里统一显示中文名（铁锭 / 铜锭 / 金锭 / 钻石 / 下界合金锭 / 下界之星等），映射表在 `MaterialGrades.CN_NAMES`；表外材料自动回退英文名，按需补充即可。
- 文本全部使用 **MiniMessage**（`<red>`、`<gradient:#ffd166:#ff7a18>`、`<#4ee36b>`），可以直接改配色。
- 内置剑胚：铁 → 铜 → 金 → 钻石 → 下界合金 → 星陨（6 档），锻造费依次 200 / 400 / 500 / 1000 / 2000 / 5000。

---

## 四、数据文件

所有数据位于 `plugins/ForgingMaster/`：

| 文件 | 内容 |
| --- | --- |
| `config.yml` | 配置 |
| `data.yml` | 玩家档案（剑币、声望、师徒、成就、段位、剑灵、剑冢统计等），每 5 分钟异步保存，关服再存 |
| `commissions.yml` | 委托板状态 |
| `manuals.yml` | 剑谱库 |
| `auction.yml` | 拍卖行挂单 |
| `tombs.yml` | 剑冢 |
| `season.yml` | 当前赛季编号与开始时间 |
| `seasons/season-N.yml` | 第 N 季归档（前二十名与当季剑币、声望、最高连锻） |
| `backups/<备份id>/` | 备份，包含 `data.yml`、`commissions.yml`、`manuals.yml`、`auction.yml`、`tombs.yml` |

---

## 五、技术实现

```
com.forgemaster
├── ForgingMasterPlugin        插件入口，注册各 Manager 与监听器
├── command/ForgingCommand     命令分发 + Tab 补全 + help
├── config/Settings            settings 段参数与通用访问器
├── forge/                     锻造会话、ForgeManager（单 ticker）、阶段与极限模式
├── gui/                       锻造台界面与主菜单（纯渲染）
├── stage/ForgeStage           铁砧演出（Display 实体，persistent=false）
├── item/ItemFactory           成品物品：名称、Lore、属性、耐久、PDC 标记
├── material/ quality/ affix/ quench/   剑胚、品质、词条、淬火介质
├── material/ArmorCatalog     装备胚：剑胚档位 × 部位 → 护甲材质与加成
├── econ/                      CoinRules（剑币公式）、StreakTracker（连锻链）
├── econ/EconomyBridge        货币桥接：Vault → CMI → 内置剑币（全反射）
├── gem/                      宝石：GemType（属性池）、GemService（开孔 / 熔炼 / 镶嵌 / 拆卸 / 合成）
├── compat/AntiCheatCompat    反作弊兼容：移速与击退加成收敛
├── mentor/MentorManager       师徒：邀请、绑定、冷却、分成、容差加成
├── growth/                    成就判定、匠衔段位、成就界面
├── env/                       环境修正（天气 / 时辰）
├── spirit/ tomb/              剑灵、剑冢
├── events/ story/             世界事件、剧情
├── commission/ duel/ manuals/ contests/   委托、比试、剑谱、大赛
├── shop/ auction/             商会、拍卖行
├── ranking/ hall/             排行榜 GUI、档案面板、名人堂
├── season/ backup/            赛季结算与归档、备份回档
├── stats/StatsStore           data.yml 档案（剑币、声望、师徒、段位等），异步落盘
└── util/                      MiniMessage 工具、PDC 键
```

- **单 ticker**：锻造会话由一个 1-tick 任务驱动。
- **界面防漏**：`InventoryHolder` 路由点击，非本界面物品取消，拖拽与 Shift 全拦。
- **选淬火前单调**：工序分数在选介质前确定，结算只做加权。
- **物品防伪**：成品打 `PersistentDataContainer` 标记，复制品不走自定义战斗逻辑。
- **演出实体**：全部 `persistent=false`，掉线、关服、跑远后自动清理。
- **数据落盘**：`data.yml` 异步保存，写失败只告警不崩。


## 六、文档地图（玩法手册 / 版本历史 / 构建 分别在哪）

> **为什么要拆**：README 一度 **286 KB / 4460 行**。agent 整读会炸上下文，于是只读一半就动手 ——
> 这才是真正的「读取不到」，比目录难找更隐蔽。现在 README 只留**索引 + 开发者参考**，
> 玩法与历史按用途分开，需要哪块读哪块。

### 我现在要做什么？→ 读这个

| 你的目的 | 读 |
| --- | --- |
| 接手开发 / 定时轮次 | **[`AGENTS.md`](AGENTS.md)** → **[`docs/handoff/LATEST.md`](docs/handoff/LATEST.md)**（这两份必读，都很小） |
| 查命令怎么写、权限怎么配 | 本文件 [二、命令与权限](#二命令与权限) |
| 查配置项 `config.yml` | 本文件 [三、配置说明](#三配置说明configyml) |
| 查数据文件在哪 | 本文件 [四、数据文件](#四数据文件) |
| 编译 / 起测试服 / 跑自检与压测 | [`docs/dev/build.md`](docs/dev/build.md) |
| 查某个版本的改动细节 | [`docs/history/`](docs/history/) 按版本挑（见下表） |
| 查玩法手感、数值曲线、经济口径 | [`docs/manual/`](docs/manual/)（见下表） |
| 从 1.x 升级上来 | [`docs/manual/upgrade.md`](docs/manual/upgrade.md) |
| 查当年审计结论 | [`audit/`](audit/) 按文件名挑，22 份别通读 |

### 玩法手册 `docs/manual/`

| 内容 | 文件 |
| --- | --- |
| 玩法流程与核心手感（烧红→敲打→叠钢→开刃→抛光→嵌银丝→淬火、连锻链、环境修正） | [`gameplay.md`](docs/manual/gameplay.md) |
| 货币、锻造费与装备（**剑币 vs 服务器经济**的口径表在这里） | [`economy.md`](docs/manual/economy.md) |
| 成长线（师徒 / 成就与匠衔 / 剑灵 / 委托比试耐久声望 / 专精天赋） | [`progression.md`](docs/manual/progression.md) |
| 从 1.x 升级到 2.0（配置迁移、旧物品处理） | [`upgrade.md`](docs/manual/upgrade.md) |

### 版本历史 `docs/history/`

| 版本段 | 文件 | 大致内容 |
| --- | --- | --- |
| v2.25.x | [`v2.25.x.md`](docs/history/v2.25.x.md) | 查错轮 1~4、`help` 收口、压测 12 阶段→**32 并发**、T0 存档原子写、三条 P0、连接上限归因（非 429/非 IP 节流）、签到与取剑钱货时序 |
| v2.24.x | [`v2.24.x.md`](docs/history/v2.24.x.md) | 三方向拓展（新机制附魔 / 剑灵灵击 / 商会科技）、查错轮 2 |
| v2.23.x | [`v2.23.x.md`](docs/history/v2.23.x.md) | 剑体玩法重定、原创附魔、真机自检首跑、全量查错 |
| v2.18 ~ v2.21 | [`v2.18-2.21.md`](docs/history/v2.18-2.21.md) | 经济看板、配置漂移、双账本对账、收尾双工序、沉浸式强化 |
| v2.10.x | [`v2.10.x.md`](docs/history/v2.10.x.md) | 属性组件被顶掉、本体耐久、重铸费用、暴击宝石 |

**什么时候读历史**：查「这个 bug 以前修过没」「这个数值为什么定成这样」「某个功能的原始设计意图」。
**日常开发不需要读**：当前状态看 `docs/handoff/LATEST.md`，玩法和配置看本文件上面的章节。

---
