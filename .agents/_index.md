# 文档索引（自动生成，别手改）

> 由 `python tools/agentdocs.py index` 生成。手改会被下次覆盖。

| 文档 | 归属 | 级别 | 说明 |
| --- | --- | --- | --- |
| `.agents/auditor/agent.md` | auditor | normative | 只读审计（只写 audit/） |
| `.agents/foreman/agent.md` | foreman | normative | 总装 / 定时轮次 / 根文档唯一主人 |
| `.agents/workbuddy/agent.md` | workbuddy | normative | 代码实现 / 检修（可写 src，需与 zcode 串行） |
| `.agents/zcode/agent.md` | zcode | normative | 代码实现 / 计划（可写 src，需与 workbuddy 串行） |
| `AGENTS.md` | foreman | normative | 唯一入口：读的顺序 / 写权限 / 硬规则 |
| `docs/handoff/LATEST.md` | foreman | normative | 上一轮交付报告（**必读**） |
| `docs/handoff/README.md` | foreman | normative | 交接报告怎么用 |
| `README.md` | foreman | normative | 项目索引 + 命令 + 配置 + 技术实现（v2.25.6 从 282 KB 拆到 35 KB） |
| `.agents/README.md` | foreman | normative | 身份卡格式与三条铁律 |
| `docs/manual/*.md` | foreman | informative | 玩法手册（玩法/经济/成长线/升级）：economy / gameplay / progression / upgrade |
| `docs/history/*.md` | foreman | informative | 逐版本叙事（查「以前修过没」「数值为什么这么定」）：v2.10.x / v2.18-2.21 / v2.23.x / v2.24.x / v2.25.x / v2.26.x |
| `docs/dev/*.md` | foreman | normative | 构建与测试服操作：build |
| `audit/*.md` | auditor | informative | 历史审计报告 22 份，按文件名挑着读 |
| `docs/handoff/archive/` | foreman | archive | 每轮交付报告归档（只增不改）：v2.25.6 / v2.25.7 / v2.25.8 / v2.25.9 / v2.26.0 |
