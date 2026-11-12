# .zcode

**这个目录只放本地计划与过程，不入库**（见 `.gitignore`）。所以：

- `plans/plan-*.md`：单次任务的计划草稿。**只有本机能看**。
- 任何「下一轮还需要知道」的结论，必须搬到下面这两处，否则等于没写：

| 要写的 | 写到哪里 |
| --- | --- |
| 规则 / 分工 / 我的坑 | [`.agents/zcode/agent.md`](../.agents/zcode/agent.md)（入库） |
| 本轮交付内容 / 下一轮待办 | [`docs/handoff/archive/v<版本>.md`](../docs/handoff/) + `LATEST.md`（入库） |

## 和 workbuddy 抢 `src/**` 怎么办

先拿锁：

```powershell
& $py tools\agentdocs.py status
& $py tools\agentdocs.py lock --as zcode
```

收尾立刻 `unlock`。锁在别人手里时不要绕过（`--force` 只在确认对方进程已死时用）。