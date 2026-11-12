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
| `README.md` | foreman | normative | 叙事版历史 + 配置 + 命令表（4400+ 行，按章节读） |
| `.agents/README.md` | foreman | normative | 身份卡格式与三条铁律 |
| `audit/*.md` | auditor | informative | 历史审计报告 22 份，按文件名挑着读 |
| `docs/handoff/archive/` | foreman | archive | 每轮交付报告归档（只增不改）：v2.25.6 |
