# ForgingMaster-Lore 稳定性审计报告（02-stability）

- 审计对象：`D:\IDEA\mc-plugins\ForgingMaster-Lore`（Paper 1.21.11 / Java 21 / 包根 `com.forgemaster`）
- 审计方式：只读源码逐文件核查，所有条目均给出 文件:行号 证据（行号基于当前工作区源码）
- 审计日期：2026-11-09
- 严重度定义：P0 = 崩服/数据丢失级；P1 = 功能性破坏/玩家资产受损；P2 = 特定条件下的卡死/NPE；P3 = 轻微泄漏/日志/健壮性问题

---

## 一、会话/状态 Map 生命周期

### 1. 进行中的比试 Match 无任何超时兜底，可把两名玩家永久锁死
- **位置**：`src/main/java/com/forgemaster/duel/DuelManager.java:149-163`（cleanupExpiredInvites 只扫 `inviteAt`）、`DuelManager.java:166-168`（accept 时把 invite 从 `inviteAt` 移除，Match 留在 `matches`）、`DuelManager.java:249-257`（activeMatchOf 对 `!ready()` 的 Match 永久返回）
- **证据**：`cleanupExpiredInvites()` 的循环体是 `for (Map.Entry<UUID, Long> e : new HashMap<>(inviteAt).entrySet())`——只遍历邀请期条目。应战后 `inviteAt.remove(...)`（167-168 行），此后该 Match 不会再被任何超时逻辑触碰；`matches` 的移除点只有 `report()`（296 行）、`deny()`（238 行）、`onQuit()`（362-373 行）。
- **触发场景**：双方 `/zhujian duel accept` 后，一方 AFK 不开炉也不下线。`challenge()` 在 101-107 行用 `activeMatchOf != null` 拒绝新比试，`bindSession`（260-268 行）还把对方的锻造强制锁到指定剑胚与种子——两人既打不了新比试，锻造也被绑架，直到有一方退出服务器。
- **严重度**：P2
- **修法建议**：给 Match 加 `acceptedAt` 时间戳，在 `cleanupExpiredInvites` 或 `activeMatchOf` 里对「已接受但 N 分钟内无人出分」的 Match 执行 `matches.remove + releaseBalance` 并通知双方；或把过期清理改为每 20 tick 的调度任务而不是挂在查询路径上。
- **工作量**：S

### 2. CommissionManager.reroll 可把 null 写进委托列表，后续渲染/交付 NPE
- **位置**：`src/main/java/com/forgemaster/commission/CommissionManager.java:398-408`（reroll：`list.set(i, generate(player.getUniqueId()))` 未判空）、`CommissionManager.java:155-161`（generate 在 materials 为空时 `return null`）、`CommissionManager.java:241-243`（buildBoard 对 `list.get(i)` 直接 `renderOrder`）、`CommissionManager.java:342`（deliver `list.get(index)` 直接用）
- **证据**：generate 的空池守卫（158-160 行注释明说「materials 段被清空时 nextInt(0) 会直接抛异常」）返回 null；reroll 是全代码中唯一没有对 generate 返回值判空就入库的调用点（ensureFor 140-145 行有 `if (c == null) break;`，reroll 没有）。
- **触发场景**：管理员清空/热重载 config 的 `materials` 段后，玩家 Shift+左键换单 → 列表中出现 null 元素 → 下一次打开委托板（renderOrder）或点击交付（deliver）抛 NPE，界面打开失败且该玩家委托列表永久带脏数据直到过期清理。
- **严重度**：P2
- **修法建议**：reroll 里 `Commission c = generate(...); if (c == null) { Msg.prefix(player, "<red>暂时没有可用剑胚…"); return; }`。
- **工作量**：S

### 3. RiskMode.ARMED 静态 Map 无主动清除，arm 后不消费即永久残留
- **位置**：`src/main/java/com/forgemaster/forge/RiskMode.java:28`（`private static final Map<UUID, Long> ARMED = new ConcurrentHashMap<>()`）、`RiskMode.java:47-53`（isArmed 只做过期判断，`return at != null && ... <= ARM_TTL_MS`，**不 remove**）、`RiskMode.java:56-60`（consume 是唯一顺带 remove 的路径）
- **证据**：TTL 5 分钟（25 行），但过期条目只有在玩家恰好再次调用 consume/disarm 时才会被移除；没有 PlayerQuitEvent 清理、没有周期清扫、也没有 onDisable 清空。static 修饰意味着 `/reload` 后旧插件实例的残留条目还在（新实例读到的是同一份类加载器的 map，视重载方式而定）。
- **触发场景**：玩家 `/zhujian forge risk` 预设后改主意不开工，或预设完直接退服——条目永久留在 map 里。单条极小，但这是全插件唯一「只增不减」的静态 per-player 容器。
- **严重度**：P3
- **修法建议**：isArmed 判过期时顺带 `ARMED.remove(uuid, at)`；并在 ForgeListener.onQuit 里调 `RiskMode.disarm(uuid)`。
- **工作量**：S

### 4. MentorManager.invites 过期邀请只在 accept 路径移除，无周期清扫、无退出清理
- **位置**：`src/main/java/com/forgemaster/mentor/MentorManager.java:47`（`invites`）、`MentorManager.java:135-143`（accept 时 remove + 过期判断）、`MentorManager.java:179`（invite 时 put）
- **证据**：全文件对 `invites` 的写点只有 179 行（put）与 135 行（accept 时 remove）。玩家收到邀请后一直不应答、或应答前就退服——没有 PlayerQuitEvent 监听，也没有定时清理，条目永久残留（accept 时才做过期判定，但被邀请人再也不点 accept 就永远不触发）。
- **触发场景**：正常运营下缓慢累积，量级 = 被放鸽子的邀请数。功能上无实害（accept 过期即拒绝），纯内存卫生问题。
- **严重度**：P3
- **修法建议**：handle/accept 入口处顺带清扫过期条目（模仿 DuelManager.cleanupExpiredInvites 的写法），或在 PlayerQuitEvent 里 remove。
- **工作量**：S

### 5. CommissionManager.byOwner 对每个触达过的玩家建空列表，永不移除且空列表也落盘
- **位置**：`src/main/java/com/forgemaster/commission/CommissionManager.java:112-114`（`of()` 用 `computeIfAbsent(owner, k -> new ArrayList<>())`）、`CommissionManager.java:91-108`（save() 遍历整个 byOwner，空列表也会写 `players.<uuid>.list = []`）
- **证据**：没有任何 `byOwner.remove` 调用点。ensure/deliver/reroll/statusLine 等只要对玩家调过 `of()`，就会创建条目；save() 把这些（可能早已退服的）空列表全部写进 commissions.yml。
- **触发场景**：长期运行的服务器，内存与 commissions.yml 随历史玩家数线性增长。数据量小，但属于典型的「为查询建态、从不回收」。
- **严重度**：P3
- **修法建议**：save() 跳过空列表；load() 后或 save() 顺带 `byOwner.values().removeIf(List::isEmpty)`。
- **工作量**：S

### 6. AnvilForgeSession 砧位会话：超时默认关闭（0 = 永不超时），世界卸载时永不回收
- **位置**：`src/main/java/com/forgemaster/anvil/AnvilForgeManager.java:1265-1267`（`timeoutMinutes()` 默认 0）、`AnvilForgeManager.java:1152-1174`（tick 只处理 `Bukkit.getWorld(s.worldName()) != null` 且区块已加载的会话）、`AnvilForgeManager.java:59`（sessions map 常驻）
- **证据**：tick 里超时分支 `timeout > 0 && ...`（1159 行），配置默认 0；世界未加载时 1168-1170 行直接跳过。会话及其中的剑会一直留在 `sessions` 与 anvils.yml 里。
- **触发场景**：玩家把 +0 的破剑放上偏远砧位后弃坑——剑被永久占用（该砧位不可再放）。这是「不吞剑」设计的有意取舍，但默认值选了「永不超时」值得商榷。
- **严重度**：P3
- **修法建议**：把 `anvil-forge.session-timeout-minutes` 默认值改为一个有限值（如 1440），或者至少在文档/配置注释里明确 0 的含义。
- **工作量**：S

### 7. 其余状态容器核查结论（已核实，无问题）
| 容器 | 位置 | 结论 |
|---|---|---|
| ForgeManager.sessions | `forge/ForgeManager.java:58` | ✅ 三条路全兜住：tick 里离线 `discard`（581-585 行）、挂机超时 cancel（586-592 行）、PlayerQuitEvent cancel（`listener/ForgeListener.java:400-408`）、界面关闭作废（1550 行）、崩溃恢复 clear（1553-1568 行 shutdown） |
| TombChallenge.active / cooldowns | `tomb/TombChallenge.java:101-103` | ✅ 每秒 tick 对离线/死亡/跑远/实体失踪有 60s 宽限回收（297-335 行）；shutdown 全清（268-276 行）；冷却过期自清（230-247 行） |
| DuelBalance.applied | `duel/DuelBalance.java:49` | ✅ 用 `addTransientModifier`（162-172 行）不进玩家存档，崩服不留脏修正；退出（`duel/DuelTierListener.java:37-40`）+ onDisable（42-49 行）双路还原 |
| ContestManager scores/names/qualities/entrants + BossBar | `contests/ContestManager.java:41-50` | ✅ 开赛 clear（137-140 行）、finish clear（214-219、264-265 行）、bar.removeAll（216-219 行）、shutdown（71-78 行） |
| ContestWatchGui.OPEN | `contests/ContestWatchGui.java:80` | ✅ 关闭/退出/大赛结束/换界面/插件卸载五路收尾（见 37 行注释与 244-317 行实现），watcherCount 顺带清离线者（150-161 行） |
| StatsStore.cache / StoryManager / ContractStore | `stats/StatsStore.java:112`、`story/StoryManager.java:37-39`、`contract/ContractStore.java:37` | ✅ 均为持久化档案（有意长期保留，非会话态），有落盘 |
| EventManager.spawned/spawnedAt | `events/EventManager.java:43-45` | ⚠️ 正常路径 OK（pruneSpawned 224-234、shutdown 清残留 236-251），但**崩服路径**见条目 8 |
| AuctionStore listings/mail | `auction/AuctionStore.java:25-26` | ✅ 全落盘，无内存会话态；每 5 秒结算到期拍品（`auction/AuctionManager.java:63-70`） |

---

## 二、Display / TextDisplay / ItemDisplay 实体生命周期

### 8. EventManager 空投物品：崩服后残留在存档里，启动时没有任何清扫
- **位置**：`src/main/java/com/forgemaster/events/EventManager.java:203-209`（`loc.getWorld().dropItem(loc, stack)`，普通 Item 实体，默认 persistent）、`EventManager.java:43-45`（spawned/spawnedAt 纯内存）、`EventManager.java:64-71`（shutdown 只覆盖正常关服）
- **证据**：EventManager 没有类似 TombChallenge 的启动清扫（对比 `tomb/TombChallenge.java:121-123` 开服 40 tick 后 `removeAllTagged`，及 `tomb/TombChallenge.java:863-875` 的 ChunkLoadEvent 孤儿清理）。事件进行中强杀/崩服 → 空投物已写入区块存档，重启后 `spawned` 为空，无人认领也无人清除。
- **触发场景**：陨星降临事件进行中崩服 → 重启后地上永久躺着一地 stamped 稀有材料（可被玩家白捡，或永远留在无人区块）。
- **严重度**：P3（物品有 despawn 5 分钟的仅限已加载区块；未加载区块里的会永久留档）
- **修法建议**：给空投 Item 加 scoreboard tag / PDC 标记，模仿 TombChallenge 在启动时 + ChunkLoadEvent 里清孤儿；或改用 `setPersistent(false)` 让未拾取的空投自然消失。
- **工作量**：S

### 9. AnvilForgeManager 落锤演出：插件在 12 tick 演出窗口内被禁用会留下幽灵锤子；block.getType() 可能同步加载区块
- **位置**：`src/main/java/com/forgemaster/anvil/AnvilForgeManager.java:854-927`（匿名 BukkitRunnable，0~12 tick），`AnvilForgeManager.java:860`（每 tick `block.getType()` 判砧子还在不在）
- **证据**：演出实体 `setPersistent(false)`（872 行，✅ 与剑/文本 374/403 行一致），不会进存档；但 Bukkit 关服/禁用时只是 cancel 剩余任务，已 spawn 的锤子 ItemDisplay 若正处于已加载区块，会一直显示到区块卸载。另外 860 行对捕获的 `block` 每 tick 调 `getType()`，若该区块已卸载，Paper 会同步加载区块（性能毛刺）。
- **触发场景**：管理员在有人敲砧的瞬间 `/reload` 或 PlugMan 卸载 → 砧子上方留一把悬浮锤子直至区块卸载/重启。
- **严重度**：P3
- **修法建议**：onDisable 时无法逐个回收（BukkitRunnable 已随插件取消，可用静态列表登记活动演出实体并在 stop() 里 remove）；`getType()` 前先 `block.getChunk().isLoaded()` 判断。
- **工作量**：M

### 10. Display 实体生命周期核查结论（已核实，总体优秀）
- **AnvilForgeManager 全息**（剑/文本/锤）：`anvil/AnvilForgeManager.java:374, 403, 872` 三处 spawn 全部 `setPersistent(false)` ✅；区块卸载丢失后由每 3 秒 tick（79、1147-1175 行）与 ChunkLoad 事件（1229-1244 行）双路补生成 ✅；despawn 判空判 isValid（982-994 行）✅。
- **ForgeStage 演出**（剑/锤/替身砧）：`stage/ForgeStage.java:73, 94, 107` 全部 `setPersistent(false)` ✅；despawn 三个实体全清（265-279 行）✅；玩家跑远/换世界由 `forge/ForgeManager.java:596-599` 收实体 ✅；崩服不留档（非持久）✅。
- **StoryManager / EnvPanel**：经查 `story/StoryManager.java` 全文与 `env/EnvPanel.java` 全文，两者**不生成任何 Display 实体**（EnvPanel 是纯 27 格只读背包面板，`env/EnvPanel.java:24-45`），无残留风险。
- **ContestWatchGui**：纯背包面板 + 每秒任务（非实体），任务五路收尾见条目 7 表格 ✅。

---

## 三、catch (RuntimeException) 清单（31 处，逐处定性）

### 11. 建议升级为「日志 + 降级」的静默吞点
| # | 位置 | 现状 | 建议 | 严重度 |
|---|---|---|---|---|
| 11a | `contests/ContestWatchGui.java:249-254` | 渲染帧抛 RuntimeException → `stop(panel)` 静默收摊，**零日志**。面板"莫名消失"时服主无从排查 | 至少 `plugin.getLogger().fine/warning("观战面板渲染失败: " + ex)` 再收摊 | P3 |
| 11b | `anvil/AnvilForgeManager.java:1077-1082`（gateHint） | 全息门槛查询失败 → `return null` 静默 | 加 `fine` 日志；全息少一行可接受，但要有据可查 | P3 |
| 11c | `anvil/AnvilForgeManager.java:1125-1130`（gateLine） | 同上，聊天行静默缺失 | 同上 | P3 |
| 11d | `ranking/StrengthenSnapshot.java:142-144` | `recordStrengthen` 失败 → `catch (RuntimeException ignored)` **零日志**。历史最高补录静默丢失 | 加 `warning`（一次补录失败意味着档案少一个数据点） | P3 |
| 11e | `forge/ForgeManager.java:552-556` | 内层 `catch (RuntimeException ignored) { sessions.remove(...) }`——外层 549-551 已 `log(WARNING, ex)`，可接受；但 ignored 分支若再炸会把异常抛回 ticker 导致 Bukkit 永久取消调度任务 | 内层也包一层 warning | P3 |
| 11f | `item/LoreArt.java:145-153` | hex 解析失败返回原串，纯展示兜底，可接受 | 保持现状（登记在案即可） | P3 |
| 11g | `season/SeasonLegacy.java:379-383` | 名人堂读取失败静默走兜底线索，注释已说明意图 | 可接受；建议 `fine` | P3 |
| 11h | `tomb/TombChallenge.java:781-787, 802-809` | parseEntity / baseHealthOf 兜底 ZOMBIE/20 血，静默 | 配置写错时最好 warning 一次（配置错误最该被看见） | P3 |

### 12. 核查结论：已合格（无需改）的吞异常点
以下各点均有 `warning/fine` 日志 + 正确降级，逐处确认无问题：
`affix/AffixSetListener.java:135`（药水施加，fine）、`affix/AffixSetService.java:178`（配置解析，warning）、`affix/Imprint.java:176-180, 267-271`（失败**退款** + warning，资金安全正确）、`item/ItemFactory.java:295`（套装写入，warning）、`listener/ArmorRebalanceListener.java:97, 122`（校准，warning）、`proficiency/ProficiencyListener.java:109`（死亡回写，warning）、`season/SeasonLegacy.java:200`（warning）、`strengthen/StrengthenService.java:1043, 1050, 1219`（名人堂/落库/剑冢，warning）、`backup/BackupManager.java:140-163`（四处 warning）、`anvil/AnvilForgeManager.java:1306`（砧位读档，warning）、`ForgingMasterPlugin.java:548-563`（warning）、`tomb/TombChallenge.java:367`（生成失败，warning + 返回 null 不上剑）、`forge/ForgeManager.java:549-551`（会话 tick，WARNING + 带堆栈，写法标准）。

---

## 四、异步线程摸 Bukkit API

### 13. 唯一的异步点：StatsStore 自动保存——不摸 world/player/inventory ✅，但与主线程 save() 无互斥
- **位置**：`src/main/java/com/forgemaster/stats/StatsStore.java:669-678`（`runTaskTimerAsynchronously`，全代码库唯一异步调度点，已用 grep 全量确认）
- **证据**：异步任务只调 `save()`（680-710 行），内部仅操作 YamlConfiguration + 文件 IO，通过 `synchronized (cache)` 快照（687-689 行）+ 逐玩家 CME 兜底（695-699 行）+ 脏标记保持（703-706 行）处理并发，**没有触碰任何 world/player/inventory API** ✅。残留风险：主线程路径（onDisable `ForgingMasterPlugin.java:566`、BackupManager.java:139-142、AuctionManager.java:77/195/243 等）与异步任务可能**同时执行 save()**，两个线程同时 `yml.save(file)` 写同一文件——最后写者胜，理论上可交错出损坏的 yaml（概率极低但存在）。
- **触发场景**：自动保存 tick 恰逢 /fm backup 回档落盘。
- **严重度**：P3
- **修法建议**：save() 加 `synchronized` 写锁（或把异步任务改为提交到主线程快照、异步只做文件 IO）。
- **工作量**：S

---

## 五、PlayerQuitEvent 监听器逐个核对（全库共 4 个）

| 监听器 | 位置 | 清理内容 | 完整性结论 |
|---|---|---|---|
| ForgeListener.onQuit | `listener/ForgeListener.java:399-408` | ① `forge.cancel(session)`（会话从 map 移除，1460 行）② `duels.onQuit(uuid)`（清 invites/matches/balance，`duel/DuelManager.java:358-374`） | ✅ 完整。漏项：未清 RiskMode.ARMED（条目 3）、MentorManager.invites（条目 4）——均为低危残留 |
| ContestGuiRouter.onQuit | `contests/ContestGuiRouter.java:102-105` | `ContestWatchGui.onQuit(uuid)` → stop(panel)：取消刷新任务 + 从 OPEN 摘除 | ✅ 完整 |
| DuelTierListener.onQuit | `duel/DuelTierListener.java:36-40` | `balance.clear(uuid)` 还原临时压档 | ✅ 完整（与 ForgeListener 路径幂等双保险） |
| ReforgeMenu.onQuit | `gui/ReforgeMenu.java:707-713` | `reforgeHolder.clearPending()` 清待确认状态 | ✅ 完整（pending 状态挂在 holder 上，无独立 map） |

**换世界**：全库无 PlayerChangedWorldEvent 监听（grep 确认）。核查各会话对换世界的容错：ForgeStage.shouldClose 对跨世界返回 true（`stage/ForgeStage.java:290-296`）→ 演出实体被收；TombChallenge tick 把跨世界判定为 away（`tomb/TombChallenge.java:316-320`）→ 60s 回收；AnvilForgeSession 绑定方块不绑玩家，无影响。✅ 均有兜底。

**死亡**：仅 `proficiency/ProficiencyListener.java:92` 监听 PlayerDeathEvent（保底回写，不清理会话）。锻造会话死亡后靠挂机超时取消、剑冢挑战死亡后靠 away 计时回收——功能可接受，非泄漏。

**崩服/强杀**：JVM 层面 onDisable 不执行。逐项核对：压档修正 = transient 不落档 ✅；砧位剑 = anvils.yml 落盘恢复 ✅；拍卖/契约/统计 = 落盘 ✅；锻造中背包材料 = ForgeManager 作废语义（材料不退，注释明示）⚠️ 设计取舍；空投物品 = 条目 8 的残留 ❌。

---

## 六、NPE / 越界高危点

### 14. ContractManager.pickRelic 对 relics.get() 结果未判空
- **位置**：`src/main/java/com/forgemaster/contract/ContractManager.java:461-476`
- **证据**：467 行只判 `relics.enabled(relicId)`，473 行 `RelicPool.Def def = relics.get(relicId);` 后 474 行直接 `def.name()`。若 RelicPool 的 enabled 与 get 语义不一致（例如配置里 enabled=true 但 id 不在 defs 表），抛 NPE 且玩家圣物已被 470-472 行扣走（`r.relics.add` 已执行）。
- **触发场景**：配置写错/relic 池热重载不一致时。
- **严重度**：P3
- **修法建议**：`if (def == null) return null;` 放在 r.relics.add 之前，并把 pending 一并校验。
- **工作量**：S

### 15. ContractManager.world() 兜底 `Bukkit.getWorlds().get(0)` 无空列表防护
- **位置**：`src/main/java/com/forgemaster/contract/ContractManager.java:137-141`
- **证据**：`return w != null ? w : Bukkit.getWorlds().get(0);`——配置的世界名无效时静默回退到第一个世界（行为尚可），但理论上 getWorlds() 为空时抛 IndexOutOfBoundsException；且静默回退会让服主配错世界名毫无感知（契约时钟跟着错误的世界走）。
- **触发场景**：`contract.world` 配错 + 极端启动顺序。
- **严重度**：P3
- **修法建议**：回退时 warning 一次「contract.world 配置无效，已回退主世界」。
- **工作量**：S

### 16. 其余 NPE/越界候选点核查结论（已核实安全）
| 候选点 | 位置 | 结论 |
|---|---|---|
| Bukkit.getEntity 返回 null | `anvil/AnvilForgeManager.java:996-1002`（resolve 判空+类型判断）、`events/EventManager.java:224-251`、`tomb/TombChallenge.java:828-834` | ✅ 全部判空 |
| ItemMeta null | `auction/AuctionManager.java:346-349`、`contract/ContractManager.java:248-251`、`tomb/TombChallenge.java:415-419`、`commission/CommissionManager.java:260-268`、`stage/ForgeStage.java:80-84`、`env/EnvPanel.java:73-84` | ✅ 全部判空 |
| settings() 判空 | `ForgingMasterPlugin.java:40`（`private final Settings settings = new Settings()` 字段内联初始化，永远非 null）；`config/Settings.java` 的 num/intOf/flag/str 均带默认值 | ✅ 无 NPE 路径。onEnable 初始化顺序：materials/strengthen 均在 anvilForge/forge 之前（`ForgingMasterPlugin.java:89-131`），`anvil/AnvilForgeManager.java:264` 的 `plugin.strengthen().strike(...)` 安全 |
| get(0) 空集合 | `forge/ForgeManager.java:201`（beginRepair 的 `materials.sorted().get(0)`——唯一调用方 openMenu:120-127 先做 isEmpty 拦截 ✅）、`anvil/Hammer.java:101`（materials 空时有 `material(plugin)` 兜底，`anvil/Hammer.java:70-72` ✅）、`contests/ContestManager.java:477, 483`（先 isEmpty ✅）、`item/ItemFactory.java:504-507`（先 isEmpty ✅）、`season/SeasonLegacy.java:405-409`（先 isEmpty ✅）、`spirit/SpiritManager.java:189-195`（先 isEmpty ✅）、`form/FormRegistry.java:81-86`（先 isEmpty ✅） | ✅ 均有守卫 |
| ⚠️ 例外 | `affix/AffixRegistry.java:160` 与 `affix/Reforge.java:466` 的 `weightedPick` 末行 `list.get(0)`——当前两个调用方（pickStrict:141、Reforge 对应 pick）都在非空列表上调用，**现状安全**；但这两个是 public 语义的权重抽取工具，未来传入空池即抛 IndexOutOfBounds | 建议加 `if (list.isEmpty()) return null;` 防御。P3 |

---

## Top10 修复优先级

| 优先 | 条目 | 位置 | 严重度 | 工作量 | 一句话修法 |
|---|---|---|---|---|---|
| 1 | #2 reroll 写入 null 委托 → NPE | commission/CommissionManager.java:402 | P2 | S | generate 返回 null 时不 set 并提示 |
| 2 | #1 进行中 Match 无超时锁死双方 | duel/DuelManager.java:149-163 | P2 | S | 给 Match 加 acceptedAt 超时清理 |
| 3 | #11a 观战面板渲染异常静默 | contests/ContestWatchGui.java:249-254 | P3 | S | 收摊前打日志 |
| 4 | #8 崩服残留空投物品 | events/EventManager.java:203-209 | P3 | S | 空投加标记 + 启动/ChunkLoad 清孤儿 |
| 5 | #3 RiskMode.ARMED 永久残留 | forge/RiskMode.java:28,47-53 | P3 | S | isArmed 过期顺带 remove + quit 清理 |
| 6 | #13 异步保存与主线程 save 并发 | stats/StatsStore.java:669-710 | P3 | S | save() 加写锁 |
| 7 | #11d recordStrengthen 静默丢失 | ranking/StrengthenSnapshot.java:142-144 | P3 | S | 加 warning 日志 |
| 8 | #14 pickRelic def 未判空 | contract/ContractManager.java:473-474 | P3 | S | 判空后置 + 先校验再入 relics |
| 9 | #5 byOwner 空列表无限增长落盘 | commission/CommissionManager.java:112-114 | P3 | S | save 时剔除空列表 |
| 10 | #9 演出实体禁用残留 + 卸载区块 getType | anvil/AnvilForgeManager.java:854-927 | P3 | M | 登记活动演出实体在 stop() 回收；getType 前查区块 |

---

## 附：核实通过、无需修改的高风险关注项（备查）

1. **DuelBalance 压档修正**用 `addTransientModifier`（`duel/DuelBalance.java:162-172`），崩服不会把负属性写进玩家存档——这是同类功能最常踩的坑，此实现是对的。
2. **TombChallenge 残影**虽 `setPersistent(true)`（`tomb/TombChallenge.java:386`），但有启动清扫（121-123 行）+ ChunkLoadEvent 孤儿判定（863-875 行）双保险，崩服残留可自愈。
3. **AnvilForgeManager 全息** `setPersistent(false)` 三处一致，区块加载补生成路径完整。
4. **AuctionManager** 资金/货权落盘顺序（先归属后动钱）有明确注释与崩溃窗口分析（`auction/AuctionManager.java:189-195, 232-243, 289-301`），最坏只「漏退」不「双花」。
5. **ContestGuiRouter.ready 静态标记**在 onDisable 里显式 reset（`ForgingMasterPlugin.java:551-556`），防 /reload 后监听器失效——已知坑已被作者主动处理。

【审计完成，共 17 条】（P2 × 2，P3 × 15；另附 5 项核实通过备查）
