# 23 · 锻造核心 / 物品属性 / 战斗数值 · 只读审计（v2.23.0）

审计范围：`forge/ anvil/ strengthen/ gem/ affix/ item/ material/ quality/ quench/ combat/ listener/ duel/ compat/ bigsword/ util/ form/ stage/ proficiency/ evolve`（属性部分）。
只读，未修改任何源码。所有结论按调用链读到最底：事件入口 → 服务层 → 属性/PDC 写入 → Lore/落盘。

统计：**P0 × 2，P1 × 7，P2 × 9**，共 18 条。

---

### [P0] 崩刃摘修饰符时把护甲的「护甲值 / 韧性」词条一起摘掉，修好也回不来（属性永久损坏）
- 位置：`src/main/java/com/forgemaster/item/ItemFactory.java:590-607`（`stripBaseModifiers`）、`src/main/java/com/forgemaster/util/Attrs.java:47-56`（`isBaseKey`）
- 机制：`setDurability` 在「耐久 正值→0」时调 `stripBaseModifiers`，按 `Attrs.isBaseKey(key)` 逐个摘。`isBaseKey` 用**前缀**判定：`k.startsWith("fm_armor_")` / `k.startsWith("fm_toughness_")` 都算基础键。而护甲词条 `armor_plate` / `tough_plate` 在 `ItemFactory:247-249` 写出来的键正是 `fm_armor_plate_chestplate` / `fm_toughness_plate_boots` —— 前缀命中，于是**词条修饰符被当成基础属性摘掉**。注释里写的「键名精确匹配、词条键不会被误摘」与实现相反。修复路径 `restoreIfRepaired`（`:610-617`）→ `StrengthenService.apply` 只重建 `fm_armor_<部位>` 与 `fm_toughness_<部位>`，**不会重建词条键**；词条数值只存在于修饰符里（`AFFIX_LIST` 只存 id），摘掉即永久丢失。
- 触发：穿锻造胸甲/护腿打怪挨打 → `CombatListener.onWearerDamaged` 把虚拟耐久扣到 0（崩刃）→ `/zhujian repair` 研磨修复。每崩一次掉一条护甲/韧性词条的属性，反复崩修反复掉。
- 影响：属性永久损坏；且 Lore 与 `AFFIX_LIST` 仍显示该词条，玩家看到「护甲值 +6」实际为 0，属于显示与判定长期不一致。
- 最小修复：把基础键判定从「前缀」改成「枚举 + 精确后缀」，并让词条键显式排除。
  ```java
  // Attrs.java
  private static final java.util.Set<String> SLOTS = java.util.Set.of(
          "helmet", "chestplate", "leggings", "boots");
  public static boolean isBaseKey(String key) {
      if (key == null) return false;
      String k = key.toLowerCase(java.util.Locale.ROOT);
      // 词条键形如 fm_armor_plate_<部位>，先排除
      if (k.startsWith("fm_armor_plate") || k.startsWith("fm_toughness_plate")) return false;
      if (k.equals("fm_atk") || k.equals("fm_speed")) return true;
      for (String base : new String[]{"fm_atk", "fm_speed", "fm_armor", "fm_toughness"}) {
          if (k.equals(base)) return true;
          if (k.startsWith(base + "_") && SLOTS.contains(k.substring(base.length() + 1))) return true;
      }
      return false;
  }
  ```
  同时 `ItemFactory.stripBaseModifiers:602` 改为 `Attrs.isBaseKey(k.getKey()) && !k.getKey().startsWith("fm_gem_")`（gem 键本身不撞，但顺手把护栏写全）。

### [P0] 铁砧 `anvils.yml` 异步冲刷：旧快照写完无条件清脏 + 主线程增删不加锁 → 剑可被复制 / 冲刷任务被打死
- 位置：`src/main/java/com/forgemaster/anvil/AnvilForgeManager.java:1343-1347`（`flushIfDirty`）、`:1403-1439`（`saveTo`）、`:165`（`placeCore` put）、`:247`（`take` remove）、`:507`（`settle` 碎剑 remove）、`:1327-1333`（异步任务注册）
- 机制：两处并发缺陷叠加。
  1) `saveTo` 是 `synchronized`，但**主线程的 `sessions.put/remove` 完全不加锁**——`synchronized (saveTo)` 保护不到被保护对象。异步线程执行 `new ArrayList<>(sessions.values())`（`:1414`）时主线程正在 `remove`，`LinkedHashMap` 的集合视图迭代会抛 `ConcurrentModificationException`；Bukkit 的重复任务一旦抛异常就**永久取消**，`flushTaskId` 仍是旧 id，`flushIfDirty` 再也不会跑 → 之后所有砧位变更只改内存，关服 `stop()` 里的 `save()` 还会被 `:1407` 的 `!dirty && file.exists()` 短路 → **整个文件永远停在旧内容**。
  2) 即使不抛异常，`saveTo` 用的是**进入方法时拍下的快照**，写完后无条件 `dirty = false`（`:1433-1435`）。写盘窗口（几十 ms）内玩家把剑取回（`dirty` 被置 true），随后被这一行清掉 → 文件里留着「某砧位上有这把剑」，内存里剑已在玩家背包。
- 触发：玩家右键取剑（`take`）与 5 秒一轮的异步冲刷撞在同一时刻；随后服务器崩溃 / 正常重启（`loadFrom` 会把文件里的砧位原样恢复，`:1357-1391`）→ 玩家背包有一把，砧位上又冒出一把。反向路径（刚放上剑就崩）表现是吞：手上已被拿走，文件里没有 → 剑消失。
- 影响：刷物品（复制剑与全部强化/词条/宝石）、吞物品（异步窗口内崩溃）；冲刷任务死后全部砧位失去持久化。
- 最小修复：让所有结构变更走同一把锁，并把「写盘期间是否又脏了」做成代数比较。
  ```java
  private long dirtySeq;            // 每次变更 ++（主线程，volatile）
  // placeCore / take / settle / dropReturn / releaseBlock 里：dirty = true; dirtySeq++;

  private void flushIfDirty() { if (dirty) save(); }

  public void saveTo(File to) {
      long seq;
      java.util.List<AnvilForgeSession> snap;
      synchronized (this) {                 // 与 put/remove 同一把锁
          if (to.equals(file) && !dirty && file.exists()) return;
          seq = dirtySeq;
          snap = new ArrayList<>(sessions.values());
      }
      // …只在锁内拍快照，写盘在锁外进行…
      try { cfg.save(to); } catch (IOException ex) { /* 保留 dirty */ return; }
      synchronized (this) { if (to.equals(file) && dirtySeq == seq) dirty = false; }
  }
  ```
  并把 `placeCore:165` / `take:247` / `settle:506-509` / `dropReturn:1225-1227` 里的 `sessions.put/remove` + `dirty = true` 全部包进 `synchronized (this) { … }`。

### [P1] 护甲词条重铸 / 词条符移植的 modifier 键缺部位后缀：旧值删不掉、新值另挂一条 → 属性叠加；`armor_plate`/`tough_plate` 直接被拦死变成 0
- 位置：`src/main/java/com/forgemaster/affix/Reforge.java:467-492`（`applyModifier`）、`:494-509`（`removeAffixModifier`）、`:577-596`（`addAffixRaw` / `removeAffixRaw`，被 `Imprint.graftApply:281` 复用）
- 机制：`ItemFactory` 给护甲写的是 `fm_<词条id>_<部位>`（`:247-249`），而 `Reforge` 一律用 `"fm_" + id`（`:470`、`:478`、`:595`），**两侧键名规则不一致**：
  - 非 `*_plate` 词条（vitality / knockback_resist / aegis）：`removeAffixModifier` 找 `fm_vitality`，物品上是 `fm_vitality_chestplate` → **删不到**；随后 `addAttributeModifier` 挂上新的 `fm_vitality`。原值（部位后缀那条）永久留在物品上 → 同属性两条 modifier 同时结算。
  - `armor_plate` / `tough_plate`：`isBaseKey("fm_armor_plate")` 为 true → `:496` 直接 `return`（不删），`:479-485` 也 `return`（不挂，还会打一条「词条 id 与基础属性冲突」的误导日志）→ 词条数值 0，Lore 仍显示数值。
- 触发：手持锻造护甲执行 `/zhujian reforge`（`armor=true` 分支，`:414-416`），或用词条符对护甲 `/zhujian strengthen graft`。
- 影响：属性叠加（重铸后旧值 + 新值同时生效，玩家可反复重铸把生命/击退抗性刷高）；或词条属性永久为 0（数值失真）。
- 最小修复：键名统一走 `Attrs` 的部位后缀，并把 `*_plate` 从基础键判定里排除（与 P0 第 1 条同一处 `isBaseKey` 修复）。
  ```java
  private static String affixKey(ForgingMasterPlugin plugin, Affix a, ItemMeta meta, boolean armor) {
      String base = "fm_" + a.id().toLowerCase(Locale.ROOT);
      if (!armor) return base;
      String slot = meta.getPersistentDataContainer().get(Keys.ARMOR_SLOT, PersistentDataType.STRING);
      return slot == null ? base : base + "_" + slot.toLowerCase(Locale.ROOT);
  }
  // applyModifier: removeAffixModifier(meta, old.attribute(), affixKey(plugin, old, meta, armor));
  //                String key = affixKey(plugin, fresh, meta, armor);
  // removeAffixRaw / addAffixRaw 同样传部位信息（或直接从 meta 的 ARMOR_SLOT 推）
  ```

### [P1] 研磨修复按「材料 + 旧耐久」在背包里找目标：同档同耐久的另一把会被替换成修好那把的克隆 → 数值复制
- 位置：`src/main/java/com/forgemaster/forge/ForgeManager.java:1061-1079`（`replaceRepaired`）、`:1017-1040`（`finishRepair`）
- 机制：`finishRepair` 在 `repairTarget`（开局时的 `held.clone()`）上恢复耐久，然后 `replaceRepaired(p, target, before)` 从头线性扫背包，只比对 `materialIdOf` 与 `durabilityOf(it) == beforeDurability`，**不比对强化等级、品质、词条、宝石、`FORGER`**。命中第一个就 `inv.setItem(i, repaired)` —— 直接把那一格覆盖成「目标剑的克隆（耐久已修）」。
- 触发：背包里有两把同胚剑（同 `material_id`）且耐久恰好相同（例如两把都在同一次副本里挨了同样的刀数），研磨其中一把。若被命中的是另一把，则：目标剑本身原地不动，另一把变成目标剑的副本 → 两把一模一样的成品。
- 影响：物品复制（把 +0 的替身换成 +50 那把，等于白送一把满强化成品）；被命中的那把原属性被吃掉（吞物品）。
- 最小修复：开局记录槽位，收尾时按槽位替换，槽位失效才退回「全字段一致」匹配。
  ```java
  // beginRepair(): 记下 int slot = player.getInventory().getHeldItemSlot(); session.repairSlot(slot);
  private boolean replaceRepaired(Player p, ItemStack repaired, int beforeDurability, int slot) {
      var inv = p.getInventory();
      if (slot >= 0 && slot < inv.getSize()) {
          ItemStack it = inv.getItem(slot);
          if (it != null && items.isForged(it)
                  && java.util.Objects.equals(items.materialIdOf(it), items.materialIdOf(repaired))
                  && items.durabilityOf(it) == beforeDurability) {
              inv.setItem(slot, repaired);
              return true;
          }
      }
      return false; // 槽位已变：走「直接发给玩家」，绝不做模糊匹配（宁可多一件也不复制）
  }
  ```

### [P1] 结算完成但仍在 RESULT 界面时断线 / 被踢 → 成品静默销毁
- 位置：`src/main/java/com/forgemaster/forge/ForgeManager.java:1929-1942`（`discard`）、`:634-638`（`tickSession` 离线判定）、对照 `:1858-1865`（`cancel` 在 RESULT 阶段会先 `giveResult`）
- 机制：`settle` 已经把成品放进 `session.result`、扣掉全部材料与锻造费、抽完词条、可能还消耗了玩家身上的「剑魂传承」（`applyInherit` 会把玩家 PDC 的 `inherit_level` 清零）。此后若玩家离线，`tickSession` 走 `discard(s)`，而 `discard` **不调用 `giveResult`**（与 `cancel`、`onInventoryClosed`、`shutdown` 三条路径都不同）。
- 触发：淬火结算出动画的那几秒里掉线 / 被反作弊踢 / `/kick`；或结算瞬间被传送到另一个服。
- 影响：吞物品 —— 成品剑连同已清空的剑魂传承永久消失，聊天里连一句提示都没有。
- 最小修复：`discard` 里补上 RESULT 阶段的发放（离线玩家走玩家 PDC 暂存或丢在原地）。
  ```java
  private void discard(ForgeSession s) {
      if (s.phase() == ForgePhase.RESULT && s.result() != null && !s.resultGiven()) {
          // 离线发放：落到玩家 PDC 的 pending 列表，登录时 inventory 满了就掉在原地，绝不静默丢弃
          com.forgemaster.item.PendingDelivery.queue(plugin, s.uuid(), s.result());
          s.resultGiven(true);
      }
      s.finished(true);
      releaseCopy(s, s.player());
      removeSession(s);
      // …原有 stage / bossBar 清理…
  }
  ```

### [P1] 「碎裂被护胚符顶住」分支返回的 `toLevel` 是错的（实际没掉级却报 −1）
- 位置：`src/main/java/com/forgemaster/strengthen/StrengthenService.java:1229-1240`
- 机制：护胚符命中后：有稳胚符 → 保住等级（不写 `writeLevel`）；无稳胚符 → `writeLevel(item, level-1)`。两种情况**都** `return new StrikeResult(Tier.BREAK, level, Math.max(0, level - 1), …)`。也就是稳胚符托住时物品仍是 `level`，结果对象却说 `toLevel = level-1`。
- 触发：连击 4+ 或碎裂率命中，同时背包里有护胚符 + 稳胚符，敲一锤。
- 影响：数值失真 —— `AnvilForgeManager.oneLine`（`:549-552`）在 ActionBar 上显示「碎了 +12→+11」，物品实际还是 +12；批量总结的 `endLevel` 走物品读取所以正确，于是同一次操作里两条文案互相矛盾；`milestone` 还会按 `Tier.BREAK` 刷一条「碎了」的聊天行。
- 最小修复：把两条分支的 `toLevel` 分开返回。
  ```java
  int keptLevel = level;
  StringBuilder note = new StringBuilder("…");
  if (steady) { /* 不动等级 */ } else { keptLevel = Math.max(0, level - 1); writeLevel(item, keptLevel, cap); }
  apply(item);
  return new StrikeResult(Tier.BREAK, level, keptLevel, cap, comboNow, threshold,
          false, false, cost, need, head + note + tail);
  ```

### [P1] `CombatListener.onHit` 只认 `damager instanceof Player`：箭/三叉戟不吃词条，也不扣武器耐久（可无限远程白嫖）
- 位置：`src/main/java/com/forgemaster/listener/CombatListener.java:115-131`（早退点）、`:146-161`（耐久扣账）
- 机制：玩家射出的箭，`EntityDamageByEntityEvent#getDamager()` 是 `Arrow`（`Projectile`），不是 `Player`，第 117 行直接 `return`。既没有 `Projectile#getShooter` 归因，也没有走到后面的耐久扣账与暴击/吸血/点燃结算。
- 触发：主手拿锻造剑（词条暴击/吸血/点燃），用弓或弩射击 —— 这一发完全不结算词条、不磨损。
- 影响：功能失效（远程武器上的词条是死属性，与 `ItemFactory:668-670` 那条「暴击率在胸甲上是死属性」同类问题）；耐久账漏扣，配合 `unbreakable` 等于远程永不磨损。
- 最小修复：damager 展开成「玩家本人」或「玩家射出的投掷物」，并把 weapon 取回 shooter 的主手。
  ```java
  Player player = null;
  if (event.getDamager() instanceof Player p) {
      player = p;
  } else if (event.getDamager() instanceof org.bukkit.entity.Projectile proj
          && proj.getShooter() instanceof Player shooter) {
      player = shooter;
  }
  if (player == null) return;
  ItemStack weapon = player.getInventory().getItemInMainHand();
  ```

### [P1] `strike` / `strikeBatch` 不检查 `anvil-forge.enabled`：服主关掉功能后存量砧位仍能继续锤
- 位置：`src/main/java/com/forgemaster/anvil/AnvilForgeManager.java:176-179`（`place` 有 `enabled()` 检查）对比 `:276-283`（`strike`）、`:320-328`（`strikeBatch`）
- 机制：`place` 第一行就 `if (!enabled()) return "<red>铁砧强化已关闭。";`，但落锤与批量落锤两条路径都没有这道门。`StrengthenService.strike` 只检查 `strengthen.enabled()`（`:1012`），那是另一个开关。
- 触发：服主把 `anvil-forge.enabled` 设为 false（例如数值出问题临时关停、或开活动期间关掉），砧上已有的剑仍可被无限制锤下去，继续扣材料与剑币。
- 影响：功能失效（关停开关不生效，可被利用在关停期间刷强化等级）；砧位与全息仍在，玩家无从得知功能已关。
- 最小修复：两条路径开头补同一道门。
  ```java
  public void strikeBatch(Player player, Block block) {
      if (!enabled()) { Msg.prefix(player, "<red>铁砧强化已关闭。"); return; }
      // …
  }
  public String strike(Player player, Block block) {
      if (!enabled()) { return "<red>铁砧强化已关闭。"; }
      // …
  }
  ```

### [P1] 目标世界已卸载时 `dropReturn` 先把会话删掉再发现世界为 null → 剑静默消失
- 位置：`src/main/java/com/forgemaster/anvil/AnvilForgeManager.java:1224-1249`（`dropReturn` / `dropAt`）
- 机制：`dropReturn` 先 `sessions.remove(s.key())` + `despawn`，然后：主人在线 → `addItem`，剩下的走 `dropAt`；主人离线 → 直接 `dropAt`。而 `dropAt` 在 `Bukkit.getWorld(s.worldName()) == null` 时 `return`（`:1244-1247`），**什么都不做**。`blockPresent()` 在「世界拿不到」时返回 false（`AnvilForgeSession:141-144`），正好把会话送进这条路径。
- 触发：砧位所在世界被卸载（Multiverse 卸载世界 / 服务器只加载主世界、砧位在资源世界），会话被 `tick` 的方块校验回收，主人此时离线。
- 影响：吞物品 —— 剑（含全部强化/词条/宝石）从内存和文件里同时消失，没有任何落盘或补偿。
- 最小修复：世界拿不到就不要销毁会话，留到世界回来再处理；确实要放弃时把物品存进「离线待领」。
  ```java
  private void dropReturn(AnvilForgeSession s) {
      World w = Bukkit.getWorld(s.worldName());
      Player owner = Bukkit.getPlayer(s.owner());
      if (w == null && (owner == null || !owner.isOnline())) {
          return; // 世界没加载、人也不在：会话保留，等下一次 tick / 世界加载后再处理
      }
      // …原逻辑…
  }
  ```

### [P2] 铁砧全息的明牌概率用的是「无符、无圣物」的裸数据，比实际掷骰低
- 位置：`src/main/java/com/forgemaster/anvil/AnvilForgeManager.java:1070`（`boardText`）、`:1147`（`boardLine`）对比 `strengthen/StrengthenService.java:1075-1089`（`strike` 带 `kindleBonus` + `rb`）
- 机制：全息调 `st.oddsOf(sword, 0)`（两参重载 → `RelicBonus.ZERO`、旺火符为 0），而 `strike` 用 `odds(level, combo, armed, kindleBonus, rb, frenzy, cornerstone)`。旺火符 +N 个百分点、圣物 `success()`/`great()`/`breakMult()` 全都不进全息。
- 触发：背包里有旺火符（或带龙税圣物）站到砧前看全息。
- 影响：数值失真 —— 全息写的碎裂率高于真实值、成功率低于真实值，玩家按全息决策（例如不敢锤），与「明牌」的设计承诺相反。
- 最小修复：全息按当前玩家算同一份 odds。
  ```java
  var rb = st.relicBonusOf(player);                    // boardText 需要 Player 参数
  double kindle = Charm.enabled(plugin, Charm.KINDLE) && Charm.count(player, Charm.KINDLE) > 0
          ? Charm.kindleBonus(plugin) : 0;
  double[] odds = st.oddsOf(sword, kindle, rb);
  ```

### [P2] `strikeBatch` 把「砧位消失」一律报成「剑碎了，残骸已进剑冢」
- 位置：`src/main/java/com/forgemaster/anvil/AnvilForgeManager.java:340-346`、`:399-428`（`batchSummary`）
- 机制：循环里 `AnvilForgeSession cur = sessionAt(block)` 为 null 时（超时归还 `dropReturn`、方块被拆 `releaseBlock`、别的路径 `take`），直接 `stop = done > 0 ? BatchStop.BROKEN : null`。`BROKEN` 分支的文案是「剑碎了 · 残骸已进剑冢流程 · 砧位清空」。
- 触发：批量落锤进行中，砧位被另一次以同方块为目标的 `tick` 超时回收 / 方块被 TNT 炸掉。
- 影响：误导 —— 剑其实好端端在背包里，玩家会以为碎了去剑冢找。
- 最小修复：区分「真碎」与「砧位消失」，新增 `BatchStop.VANISHED` 并给对应文案。
  ```java
  if (cur == null) { stop = done > 0 ? BatchStop.VANISHED : null; break; }
  // batchSummary: case VANISHED -> "<yellow>批量落锤中断：砧位没了（剑已归还/掉落，去背包或砧边找）</yellow>"
  ```

### [P2] 批量落锤零冷却连发，但每一锤都播完整音效（碎裂锤两发）
- 位置：`src/main/java/com/forgemaster/anvil/AnvilForgeManager.java:340-382`（循环）、`:500-529`（`settle`）、`:564-580`（`playStrikeSound`）
- 机制：`settle(…, playAnim=false)` 只关掉了**动画**，`playStrikeSound(player, block, result)` 在 `:517` 无条件执行。批量一次 10~50 锤全部在同一 tick 内跑完，`BREAK` 档还会 `playSound` 两次（`:567-570`）。
- 触发：按住 Shift 左键铁砧（`batch-max` 默认 10，上限 50）。
- 影响：客户端音效风暴（同一 tick 10~50 个 `BLOCK_ANVIL_USE`，音量叠加到爆音），体验明显劣化；与「演出只播最后一锤」的既有决策不一致。
- 最小修复：音效跟随动画同样只留最后一次，批量内部静默。
  ```java
  private Settled settle(Player player, Block block, AnvilForgeSession session, boolean announce) {
      // …
      if (announce) { playStrikeSound(player, block, result); playStrikeAnimation(session, block, result.tier()); }
      // …
  }
  ```

### [P2] 暴击在 `EventPriority.HIGH` 上改伤害，会被 HIGHEST / MONITOR 的后续结算覆盖或重复
- 位置：`src/main/java/com/forgemaster/listener/CombatListener.java:115`（`@EventHandler(priority = HIGH, ignoreCancelled = true)`）、`:176-182`（`event.setDamage(event.getDamage() * crit.multiplier())`）
- 机制：`setDamage` 写的是基础伤害，`HIGH` 之后还有 `HIGHEST` / `MONITOR` 两档。别的战斗插件（伤害上限、减伤、反作弊复算）在更高优先级重写 `setDamage` 时，暴击乘区被整段丢弃；反之若它先乘算、本插件再乘，则乘区叠加两次。`lifesteal` 紧接着在同一优先级读 `getFinalDamage()`，读到的是「暴击已生效但护甲/附魔修饰符是否已应用」取决于事件时序的值。
- 触发：服务器装有任何一个动伤害的插件（战斗、副本、反作弊），与锻造暴击同时生效。
- 影响：数值失真（暴击时高时低、吸血量随之漂移）；与 `crit-2.21.0` 里「口径全在 Crit 里」的设计意图不符。
- 最小修复：乘区放到 `HIGHEST`，并把吸血挪到同一处用刚算出的值，避免二次读 `getFinalDamage()`。
  ```java
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onHit(EntityDamageByEntityEvent event) { … }
  // 暴击后：double dealt = event.getDamage() * crit.multiplier();
  //        event.setDamage(dealt);
  //        lifesteal 用 dealt（而不是 event.getFinalDamage()）
  ```

### [P2] 剑尘抵扣时提示「花掉 N 个材料」，与实际从背包扣走的数量不符
- 位置：`src/main/java/com/forgemaster/strengthen/StrengthenService.java:1046-1058`（`dustUse` 计算）、`:1093-1103`（实际扣料）、`:1173`（文案）、`:1177-1178`（`StrikeResult.materials = need`）
- 机制：材料不足时 `dustUse = need - have`，实际从背包只扣 `need - dustUse` 个材料，差额由剑尘补。但成功文案写 `"花掉 " + need + " 个 " + pretty(costItem)`，`StrikeResult.materials` 也填 `need`，于是批量总结累加的是 `need` 而不是「材料 + 剑尘」的实际构成。
- 触发：背包材料不够、靠剑尘抵一锤。
- 影响：数值失真（玩家对账时发现材料没少那么多，或以为材料够用）；批量总结里「消耗材料 N」与实际背包减少量不一致。
- 最小修复：把 `need` 拆成两段返回/展示。
  ```java
  int matUsed = need - dustUse;
  String costText = matUsed + " 个 " + MaterialGrades.pretty(costItem)
          + (dustUse > 0 ? " + 剑尘 " + dustUse : "");
  return new StrikeResult(tier, level, next, cap, newCombo, threshold,
          threshold > 0 && newCombo >= threshold, false, cost, need, body);
  // body 里用 costText；StrikeResult 增补 dust 字段供 batchSummary 正确归集
  ```

### [P2] `ForgeListener.onClick` 不校验点击者是否为该会话的主人（其它 GUI 都校验）
- 位置：`src/main/java/com/forgemaster/listener/ForgeListener.java:52-68`（ForgeHolder 分支）对比 `:80`（ContractHolder）、`:96`（EvolutionHolder）
- 机制：`ForgeHolder` 分支取消点击后直接 `manager.handleClick(session, rawSlot, shift)`，没有 `player.getUniqueId().equals(session.uuid())`；`ContractHolder` / `EvolutionHolder` / `ManualStore` 分支都有这道校验，说明这是漏写而非设计。`handleClick` 内部所有落账都用 `session.player()`，所以第三方只能推进别人的进度、无法直接拿走成品 —— 但按钮语义（放弃、淬火选择）同样会被代按。
- 触发：任何让第三方看到同一份 `Inventory` 的途径（`/invsee` 类插件、共享界面插件、GUI 同步 mod）。原版路径下不可达，属于纵深防御缺口。
- 影响：越权操作面（可替他人「放弃锻造」白丢材料）。
- 最小修复：补一行与其它分支一致的校验。
  ```java
  if (holder instanceof ForgeHolder forgeHolder) {
      event.setCancelled(true);
      if (!(event.getWhoClicked() instanceof Player player)) return;
      ForgeSession session = forgeHolder.session();
      if (session == null || session.finished()) return;
      if (!player.getUniqueId().equals(session.uuid())) return;   // ← 补这行
      // …
  }
  ```

### [P2] `baseOf` 回退把「已放大的 modifier 数值」当成 +0 基准，无快照物品会被再乘一次
- 位置：`src/main/java/com/forgemaster/strengthen/StrengthenService.java:1919-1935`（`baseOf`）、`:1896-1903`（调用点）
- 机制：`baseOf` 优先级是「PDC 快照 → 现有基础 modifier 的 `amount` → 配置」。第二条注释称老物品的 modifier 是「等级 0 时的原值」，但物品上的 `fm_atk` 往往已经是 `base × (1+bonusRatio(level)) × evoMult`（`replace()` 写的就是放大后的值）。被 `/give` 造出、被别的插件重建 NBT、或从旧备份回滚的剑没有 `BASE_ATK`，于是 `base = 放大后的值`，`apply` 再乘一次 → 一跳抬升。
- 触发：任何让 `BASE_ATK/BASE_SPEED/BASE_ARMOR` 缺失但 `fm_*` 仍在的路径（跨服同步、第三方物品编辑、备份回滚），随后触发任意一次 `apply`（强化、`ensureCap`、开孔、重铸、登录修复都会调）。
- 影响：属性阶梯膨胀（一次约 +0.6%×level×evoMult）。只跳一次就自愈（快照随即被写回），但每次重建都会再来一次。
- 最小修复：反推基准时按当前倍率除回去。
  ```java
  private double baseOf(ItemMeta meta, NamespacedKey snapshotKey, Attribute attribute,
                        String modifierKey, double fallback, double mult) {
      Double stored = meta.getPersistentDataContainer().get(snapshotKey, PersistentDataType.DOUBLE);
      if (stored != null) return stored;
      // …遍历 modifier…
      //   return m.getAmount() / Math.max(1e-6, mult);   ← 而不是直接 return m.getAmount()
      return fallback;
  }
  ```

### [P2] 单锤 `strike` 在冷却窗口内完全静默，玩家无法区分「冷却中」与「没反应」
- 位置：`src/main/java/com/forgemaster/anvil/AnvilForgeManager.java:284-291`
- 机制：`cooldownMs()` 默认 450ms，窗口内 `return null` 且不写 ActionBar（注释说明是为了避免刷屏）。但客户端的 `LEFT_CLICK_BLOCK` 连发与「手抖点了空地 / 没对准方块」产生的都是同一个 null，调用方无从区分。同时 `session.lastStrikeAt(now)` 在 `settle` **之前**写入（`:291`），若 `settle` 抛异常（剑冢写入、名人堂、属性重算任一抛出），这一锤的剑币与材料已扣、冷却已吃，等级却没动，且没有任何补偿路径。
- 触发：快速连点（正常手感），或服务端在结算中途抛异常（见下）。
- 影响：体验（点十下只成一锤却不给任何提示）+ 异常时扣费不结算（数值失真）。
- 最小修复：冷却里给一条轻量 ActionBar；把 `lastStrikeAt` 挪到 `settle` 成功之后，并对 `settle` 包一层兜底。
  ```java
  if (cd > 0 && now - session.lastStrikeAt() < cd) {
      player.sendActionBar(Msg.mm("<dark_gray>锤子还没抬起来…</dark_gray>"));
      return null;
  }
  Settled s;
  try { s = settle(player, block, session, true); }
  catch (RuntimeException ex) {
      plugin.getLogger().warning("落锤结算异常: " + ex.getMessage());
      return "<red>这一锤出了问题，剑还在砧上，请再敲一次。";
  }
  session.lastStrikeAt(now);
  ```

---

## 附 A：Lead 指定四个热点的逐项核对结论

### A1 `strikeBatch` 与单锤 `settle` 的逐分支等价性 —— 等价，但有 3 处差异
逐条对照 `settle(player, block, session, playAnim)`（`:500-529`）在两条路径下的行为：

| 环节 | 单锤 | 批量 | 是否等价 |
|---|---|---|---|
| 冷却 | `:289` 拦截 | `:355` 跳过（注释声明为设计） | 有意不等价，见下 |
| 掷骰 / 扣费 / 属性重建 | `settle` | `settle` | 同一份代码，等价 |
| 音效 | `:517` 每锤 | `:517` **每锤**（`playAnim` 只管动画） | 不等价，见 P2「零冷却连发但每锤播完整音效」 |
| 动画 | `:519-521` | `:385-387` 只播最后一锤 | 有意不等价，正确 |
| 全息刷新 | `:522-527` 每锤 | 每锤 `settle` 内部刷新（`playAnim=false` 不影响） | 等价 |
| 真碎清理 | `onBroken` 回调 `:504-510` | 同一回调 | 等价 |
| ActionBar | `:304` | `:370` 每锤 `batchLine` | 等价（文案不同） |
| 结算异常兜底 | 无 | 无 | 均缺失，见 P2「单锤 strike 在冷却窗口内完全静默…」 |

**冷却绕过不构成等效加速刷收益**：批量每锤都独立走 `StrengthenService.strike` 的完整扣费（`takeCoins` + `MaterialGrades.consume`），没有任何一锤被免费连击；`batchStopBefore` 在掷骰前判 CAP、REFUSED 在扣费前返回，所以绕过冷却只能省掉「手速」，不能产生额外等级或额外材料。`batchMax()` 默认 10 / `clampBatchMax` 上限 50，进一步封住单次点击的收益上限。

**优先级顺序正确**：`i > max`（`batchStopBefore`）→ `level >= cap`（同函数）→ REFUSED（扣费前）→ 真碎（`batchStopAfter` 的 `broken` 参数）→ CAP（`batchStopAfter`）。剑碎一定先于 CAP 命中，不会出现「碎了还报已到顶」。

### A2 属性 modifier 的移除是否按 key —— 五个前缀里有两个在用前缀匹配，是 P0/P1 两条的根因
- `fm_atk` / `fm_speed` / `fm_set_*` / `fm_gem_*`：全部按 `NamespacedKey` **精确比较**后 `removeAttributeModifier`，没有 UUID 随机生成，也没有漏摘（`StrengthenService.replace:1938-1962`、`AffixSetService.apply:366-374`、`GemService.reapplyGemEffects:1043-1053`）。
- `fm_armor*` / `fm_toughness*`：只有 `Attrs.isBaseKey`（`:47-56`）用 `startsWith` 前缀判断，于是把 `fm_armor_plate_<部位>`、`fm_toughness_plate_<部位>` 两个**词条键**吞进「基础键」集合 → 崩刃摘除会摘掉它们（P0 第 1 条），`Reforge` 的写入护栏也会拦掉它们（P1 第 1 条）。这是同一处判定失误的两个出口，修一处即可同时解决。
- **崩刃 → 修复这条链不会重复挂**：`restoreIfRepaired` → `StrengthenService.apply` → `replace()` 每次先按精确 key 摘掉再挂，幂等；`Attrs.normalize` 也用 `containsSame`（键 + 属性）去重。**漏挂的只有词条键**（见 A2 第二点）。

### A3 碎裂路径「碎了又还在」（复制）—— 已排空，不存在
- 唯一带 `onBroken` 回调的调用点是 `AnvilForgeManager.settle:503`（全仓仅两处 `strike(` 调用点，另一处是 `AnvilForgeListener:144` 转发的 `manager.strike`）。
- 真碎分支（`StrengthenService.strike:1211-1224`）：`addInt(STRENGTHEN_BROKEN,1)` → `recordDestruction`（写剑冢快照 + `grantInherit`）→ `onBroken.accept(item)`，回调里 `sessions.remove` + `despawn` + `dirty=true`，**剑既不留在背包也不留在砧位**；`settle` 随后因 `broken[0]=true` 跳过 `session.sword(sword)` 与 `refresh`（`:522`），不会留下带剑的会话。铁砧路径下剑本来就不在背包里，`removeFromInventory` 不会被调用（`:1214-1218` 的 if/else 保证互斥）。
- 命令入口（`onBroken == null`）走 `removeFromInventory`，主手 `isSimilar` 命中即清空，否则全背包线性查找清空 —— 两条路都不会出现「记了 BROKEN 但剑还在手上」。
- 唯一残留风险是 `grantInherit` 的等级传递（每次真碎都记一次），但那需要「同一把剑反复真碎」，而剑在第一次真碎时已被销毁，不可达。

### A4 `GemService` 孔位索引与 enchant-socket 分离后是否越界 / 串位 —— 不越界
- `fm_gem_<下标>` 的下标是**宝石列表下标**；`reapplyGemEffects` 每次都「先摘光全部 `fm_gem_*`、再按新下标整组重挂」（`:1043-1059`），插入 / 取出 / 中间删除都会重排，不存在下标漂移。
- `encryptSocketsOf` 与 `socketsOf` 已完全分离：`freezeEnchantSockets`（`:153-160`）在**开宝石孔之前**把回退值落成实值，`socket():446` 与 `socketEnchant():603` 都先冻结再自增，`gems.size() >= sockets`（`:965`）与 `sockets >= limit`（`:612`）用的是各自的计数，不会互相白送。
- `planEnchant`（`:475-566`）是纯函数且 `enchantsOf` 取了「PDC 表 ∪ 真实附魔」的并集（`:222-239`），因此 `usedEnchantSockets` 只会 ≥ 真实值，不会因为别的插件挂了原版附魔而超限。
- 剩余的小缺口：`applyGem` 的键前缀是 `fm_gem_`，若**服主改配置把某个宝石的 `attribute` 去掉**，老物品上那条 `fm_gem_<下标>` 就落在 `reapplyGemEffects` 的清理集合（`attrs`，由当前 `GemType.values()` 推出）之外，变成永久残留 modifier。属 P2 级配置变更残留，未单列条目。

## 附 B：核对后判定「不是 bug」的三处（避免重复上报）

1. `VanillaGuardListener:212` `player.getInventory().getItem(event.getHotbarButton())` —— 只在 `ClickType.NUMBER_KEY` 分支进入，该分支 `getHotbarButton()` 恒为 0..8，不会越界。
2. `Attrs.normalize`（`util/Attrs.java:77-133`）反复调用不会堆修饰符：武器分支用 `containsSame`（键 + 属性）去重，且在 `merged.size()` 不变时直接返回；装备分支是 `removeIf` 再重建，幂等。
3. `AffixRegistry.pickStrict(ARMOR_AFFIX_POOL, 6)`（`ForgeManager:1648`，池只有 5 条）不会死循环：`while` 条件带 `!candidates.isEmpty()`，抽不满就少给，与「装备只吃防御词条」的设计一致。