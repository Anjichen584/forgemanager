# .workbuddy

**这个目录只放本地过程流水，不入库**（见 `.gitignore`）。所以：

- `memory/<日期>.md`：我每天干活的过程日志。**只有本机能看**。
- 任何「下一轮还需要知道」的结论，必须搬到下面这两处，否则等于没写：

| 要写的 | 写到哪里 |
| --- | --- |
| 规则 / 工作流 / 我自己的坑 | [`.agents/workbuddy/agent.md`](../.agents/workbuddy/agent.md)（入库） |
| 本轮交付内容 / 下一轮待办 | [`docs/handoff/archive/v<版本>.md`](../docs/handoff/) + `LATEST.md`（入库） |

## 为什么

以前规则只写在 `.workbuddy/RULES.md`，而整个目录被 gitignore ——
换机器、开新会话、别人接手时，这些规则**根本读不到**。漏读就是这么来的。

现在规则在 `.agents/workbuddy/agent.md`，`python tools/agentdocs.py doctor` 会把它算进文档地图。