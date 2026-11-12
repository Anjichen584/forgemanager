# .agents —— 每个 agent 的身份卡与写权限

这个目录解决三件事：**谁能写什么**（不打架）、**文档在哪**（不漏读）、**谁的规则算数**（不冲突）。

## 目录约定

```
.agents/
├── README.md            ← 本文件（约定说明）
├── _index.md            ← 自动生成：全部文档地图，别手改
├── _lock.json           ← 当前谁在写 src（gitignore，用于串行）
├── foreman/agent.md     ← 总装 / 定时轮次 / 根文档唯一主人
├── auditor/agent.md     ← 只写 audit/**
├── workbuddy/agent.md
└── zcode/agent.md
```

**只有挂在这个目录下、并且在 `_index.md` 里出现过的文档才算「存在」。** 散落在各处的临时笔记
一律视为不存在 —— 这是刻意的，避免「我以为写了但没人能读到」。

## 身份卡格式

`s/<名字>/agent.md` 开头必须是这段 YAML front-matter：

```yaml
---
agent: workbuddy                 # 唯一标识，和目录名一致
role: 实现 / 检修                 # 一句话职责
authority: normative             # normative（规则，必须遵守）/ informative（参考）
status: active                   # active / paused / archived
owns-docs: [".workbuddy/**"]     # 你「拥有」的文档范围（覆盖率检查用）
may-write: ["src/**", "pom.xml"]  # 你允许改的代码范围（提交前检查用）
must-not-write: ["README.md"]    # 绝对不许碰
read-first: ["AGENTS.md", "docs/handoff/LATEST.md"]
serialize-with: ["zcode"]        # 和谁共享写范围，必须靠锁串行
---
```

后面随便写：你的工作流、你自己的坑、你的常用命令。

## 三条铁律

1. **文档必须入库。** 被 gitignore 的目录（`.workbuddy/memory/`、`.zcode/plans/`）只放**过程流水**，
   任何「下一轮还需要知道」的东西必须提到 `docs/handoff/` 或身份卡里。理由：换机器、新会话、
   别人接手时，gitignore 的东西等于不存在 —— 这就是以前「漏读」的根因。
2. **不要改别人的 `owns-docs`。** 想给 foreman 提文档，写在自己的归档里，让 foreman 合并。
   根 `README.md` 只有 foreman 能改。
3. **共享 `src/**` 的 agent 必须拿锁再动手**（`python tools/agentdocs.py lock --as <名字>`）。

## 常用命令

```powershell
# 校验所有身份卡 + 文档覆盖率 + 写范围冲突
& $py tools\agentdocs.py check

# 只校验「我这次要提交的文件」是否越界（放在 commit 前）
& $py tools\agentdocs.py check --staged --as workbuddy

# 重新生成 .agents/_index.md 和 AGENTS.md 里的文档地图
& $py tools\agentdocs.py index

# 体检：README 多大、哪些 md 没挂索引、哪些文档可能过期
& $py tools\agentdocs.py doctor

# 串行写 src
& $py tools\agentdocs.py lock --as workbuddy
& $py tools\agentdocs.py unlock --as workbuddy
```