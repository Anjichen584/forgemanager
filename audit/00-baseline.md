# 阶段 0 · 基线体检报告（ForgingMaster 2.17.3）

日期：2026-11-09 ｜ 分支：无 git（工作区直接改） ｜ 构建：`mvn package` 通过 → `target/ForgingMaster-2.17.3.jar`

## 1. 代码盘点（实测，非估算）

| 维度 | 数值 |
|---|---|
| Java 源码 | 40461 行 / 130+ 类 |
| 最大类 | SelfTestSuite 4557 · ForgingCommand 2765 · StrengthenService 1927 · ForgeManager 1614 · GemService 1393 · AnvilForgeManager 1377 |
| 常驻调度任务 | 12 个 runTaskTimer（见 §2） |
| 事件监听热点 | ForgeListener 6 处 @EventHandler · VanillaGuardListener 5 · AnvilForgeListener 5 |
| 高频调用存量 | getItemMeta() ≈190 处 · Msg.mm() ≈193 处 |
| 自检基线 | `/zhujian selftest` 286 项（2.17.2 口径），2.17.3 结果见 §4 |

## 2. 常驻定时任务清单（12 个）

| 类 | 间隔 | 用途 | 初评 |
|---|---|---|---|
| ForgeManager | **1 tick** | 锻造小游戏每刻推进 | 唯一每 tick 任务，待事件化评估 |
| AnvilForgeManager 演出 | 1 tick | 落锤动画（临时，12 tick 生命周期） | 正常 |
| ContestManager | 20L | 大赛推进 | 待查空转 |
| ContestWatchGui | 1s | 观战面板刷新 | 仅面板打开时应有任务 |
| ContractManager | 1s | 龙税时钟 | 待查能否拉长 |
| EventManager | 1s | 世界事件 | 待查 |
| AnvilForgeManager.tick | 3s | 砧位校验 | 砧位多时 O(n) |
| StoryManager | interval×20L | 剧情检查 | 配置间隔 |
| TombChallenge | 20L | 剑冢挑战 | 临时任务 |
| AuctionManager | 5s | 过期拍卖结算 | 待查 |
| SeasonManager | 60s | 赛季时钟 | 合理 |
| HallOfFame | — | 刷新 | 待查间隔 |
| ForgingMasterPlugin | — | 杂项 | 待查 |
| StatsStore | 300s | 自动保存 | 合理 |

## 3. 本阶段产物

- `tools/rcon.py` —— 极简 RCON 客户端（标准库，支持 stop 后连接重置）
- `tools/selftest.py` —— 一键自检：部署 target 最新 jar → 起服 → RCON 跑 `/zhujian selftest` → 抓汇总 → 关服；旧包自动移入 `testserver/_jar-backup/`
- 已知环境事项：测试服 `D:\IDEA\mc-plugins\ForgingMaster\testserver`，RCON 25599 / 密码 testpass / 游戏端口 25577

## 4. 2.17.3 启动验证与自检结果

- **启动验证 ✅**：paper 1.21.11 冷启 15~18s，2.17.3 正常启用；乘区迁移（0.02/级 → 0.006/级、蜕变 1.25~1.40 → 1.08~1.12）与动画迁移日志均正确触发；8 砧位、106 剑冢、28 档案全部恢复
- **自检基线：共 342 项，通过 341，失败 1**（留档 `testserver/RESULT-selftest-2.17.3.txt`）
  - 唯一失败：「强化后基础属性按倍率放大」——断言硬编码旧乘区 ×1.20（2%/级时代），实现按新配置 0.006/级 计算正确；断言已改为动态读 `bonusRatio(10)`，非产品 bug
- 自检结论：**2.17.3 基线全绿（修正断言后 342/342）**，可以此为对照进入修复验证
- **修复版验证（同日）**：全部修复包（A/B/C/D）+ 拓展（图鉴/分解/合成/PAPI+殿堂/事件日历）集成后 **347 项，通过 347，失败 0**（342 + 修复包 A 新增 5 条性能断言）
- 工具链备注：`tools/selftest.py` 曾因缺 RCON 认证拿不到回包（未认证会话被服务端静默丢弃），已修——见 `rcon.py login()`

## 5. 审计报告索引

| 报告 | 状态 | 条目 |
|---|---|---|
| audit/01-performance.md | ✅ 完成 | 16 条（P0×1：CombatListener 每次命中全量重写耐久 Lore） |
| audit/02-stability.md | ✅ 完成 | 17 条（P2×2：委托 reroll 空指针、比试 Match 无超时兜底） |
| audit/03-economy.md | ✅ 完成 | 9 失衡点（🔴 金币通缩 / 符经济劫持 / 攻击与攻速通胀） |
| audit/04-quality.md | ✅ 完成 | 62 条（含 1 个真实行为 bug：folding-base-seconds 用 getInt 截断小数） |
