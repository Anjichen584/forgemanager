# AGENTS.md · 任何 AI 进来先读这一份

> **这是唯一入口。** 仓库里有多份 README（本文件、`README.md`、各 agent 的 `.agents/<名>/agent.md`、
> `audit/`、`docs/handoff/`），不要凭猜去哪找 —— 按下面这张表读。

## 一、读的顺序（别跳，也别全读）

| 顺序 | 文件 | 什么时候读 | 大小 |
| --- | --- | --- | --- |
| 1 | **本文件** | 永远第一个读 | 小 |
| 2 | `docs/handoff/LATEST.md` | 永远读。**上一轮的交付报告**：当前版本、自检项数下限、压测数字、下一轮该做什么、不能重犯的坑 | 8 KB |
| 3 | `.agents/<你的名字>/agent.md` | 读你自己那份身份卡：你能写哪些文件、绝对不能碰哪些 | 小 |
| 4 | `README.md` | **只在需要时按章节读**（它 4400+ 行 / 280 KB，整读会炸上下文）| 大 |
| 5 | `audit/*.md` | 只在查历史审计结论时读，按文件名挑，别通读 22 份 | 大 |

`tools/agentdocs.py doctor` 会打印「哪些文档过期、哪些没被索引、README 多大」。

## 二、谁能写什么（防止互相打架）

| Agent | 能写 | 绝对不能写 |
| --- | --- | --- |
| `foreman` | `AGENTS.md`、`README.md`、`docs/**`、`.agents/**`、`tools/agentdocs.py` | `audit/**` |
| `auditor` | `audit/**` | `src/**`、`README.md`、`docs/**` |
| `workbuddy` | `src/**`、`pom.xml`、`tools/guitest/**` | `README.md`、`AGENTS.md`、`audit/**`、`docs/**` |
| `zcode` | `src/**`、`pom.xml`、`tools/**` | `README.md`、`AGENTS.md`、`audit/**`、`docs/**` |

- `workbuddy` 与 `zcode` **都在改 `src/**`**，所以它们之间用**锁**串行：动手前
  `python tools/agentdocs.py lock --as workbuddy`，收尾 `unlock`。不拿锁就提交会被 `check --staged` 拦住。
- **根目录 `README.md` 只有 `foreman` 能改。** 这是上一轮的真实教训：两个 agent 同时编辑
  README，一次编辑把 `## 十四·补十三` 的标题吃掉，事后才发现。其他 agent 想改文档，写进
  `docs/handoff/archive/v<版本>.md` 交给 foreman 合并。

## 三、硬规则（违反会导致数据或历史丢失）

1. **新存档一律走 `com.forgemaster.util.YamlIo`**。裸 `yml.save(file)` 会被自检的「存档守卫」判红。
2. **命令三处同源**：`onCommand` 的 switch 分派 + `SUBCOMMANDS` Tab 表 + `helpLines()`。漏一处自检红。
3. **加功能前先搜现有命令面**（`SUBCOMMANDS` + `helpLines()`）。差点加过 `/zhujian top rep`，而 `rank reputation` 早就有了。
4. **管理命令进 `ADMIN_ONLY`，并且 handler 内部自判 `forgemaster.admin`**。只靠 Tab 隐藏不算。
5. **锻造系手续费永远是剑币**（`stats().takeCoins`）：强化 / 进化 / 铭刻 / 词条剥离。不是漏改 Wallet。
6. **自检项数只许增不许减**（当前 520）。删钉子等于删安全网。
7. **并发压测固定 4 个 bot**，绝不上 10（429 / 握手排队）。

## 四、改完必须跑两个门

```powershell
& 'D:\apache-maven-3.8.8\bin\mvn.cmd' -B -q clean package          # 必须 exit 0
# 自检（测试服，约 30 秒）
cd D:\IDEA\mc-plugins\ForgingMaster\testserver
& 'C:\Users\heiya\AppData\Local\Programs\Python\Python312\python.exe' _run_selftest_full.py
# 压测（12 阶段 4 并发，约 5 分钟）
cd D:\IDEA\mc-plugins\ForgingMaster-Lore
& 'C:\Users\heiya\AppData\Local\Programs\Python\Python312\python.exe' tools\guitest\run-stress.py
```

自检 **失败 0**、压测 **FAIL 0** 才算完成。这两个数字写在 `docs/handoff/LATEST.md` 里，对不上就是退步。

## 五、文档地图（自动生成，别手改这一段）

<!-- agents:index:begin -->
| 文档 | 归属 | 级别 | 说明 |
| --- | --- | --- | --- |
| `.agents/auditor/agent.md` | auditor | normative | 只读审计（只写 audit/） |
| `.agents/foreman/agent.md` | foreman | normative | 总装 / 定时轮次 / 根文档唯一主人 |
| `.agents/workbuddy/agent.md` | workbuddy | normative | 代码实现 / 检修（可写 src，需与 zcode 串行） |
| `.agents/zcode/agent.md` | zcode | normative | 代码实现 / 计划（可写 src，需与 workbuddy 串行） |
| `AGENTS.md` | foreman | normative | 唯一入口：读的顺序 / 写权限 / 硬规则 |
| `docs/handoff/LATEST.md` | foreman | normative | 上一轮交付报告（**必读**） |
| `docs/handoff/README.md` | foreman | normative | 交接报告怎么用 |
| `README.md` | foreman | normative | 叙事版历史 + 配置 + 命令表（4400+ 行，按章节读） |
| `.agents/README.md` | foreman | normative | 身份卡格式与三条铁律 |
| `audit/*.md` | auditor | informative | 历史审计报告 22 份，按文件名挑着读 |
| `docs/handoff/archive/` | foreman | archive | 每轮交付报告归档（只增不改）：v2.25.6 |
<!-- agents:index:end -->

## 六、为什么这么设计

以前每个 agent 各自在自己目录里写 README（`.workbuddy/`、`.zcode/`），结果是：

- 那两个目录**被 gitignore**，换机器 / 新会话 / 别人接手时根本看不到 → **漏读**；
- 没有总入口，agent 只能猜去哪找 → **读取不到**；
- 谁都可能去改根 `README.md` → **打架**，而且改坏了没人发现。

现在：**文档必须入库**（`git ls-files` 能看到的才算存在）、**必须挂在索引上**（`doctor` 会点名漏挂的）、
**写权限按 agent 切分**（`check --staged` 会拦）。精度靠「少读但读对」：
先读 8 KB 的 `LATEST.md` 拿到全部硬状态，需要细节再按章节钻 `README.md`。