---
agent: zcode
role: 代码实现 / 计划（可写 src，需与 workbuddy 串行）
authority: normative
status: active
owns-docs: [".zcode/**"]
may-write: ["src/**", "pom.xml", "tools/**", ".zcode/**"]
must-not-write: ["README.md", "AGENTS.md", "audit/**", "docs/**", ".agents/**"]
read-first: ["AGENTS.md", "docs/handoff/LATEST.md", ".agents/zcode/agent.md"]
serialize-with: ["workbuddy"]
---

# zcode

代码实现方之二。和 `workbuddy` 共享 `src/**`，**动手前必须拿锁**：

```powershell
& $py tools\agentdocs.py lock --as zcode
# ... 改代码 ...
& $py tools\agentdocs.py unlock --as zcode
```

## 和 workbuddy 的分工建议

两边都能写 `src/**`，靠锁串行是硬保证；但为了少抢锁，建议按任务切：

- 拿锁前先 `status` 看有没有人在写，别硬等。
- 一次锁只做一件事（一个功能 / 一条修复），做完立刻放，别长时间持有。
- 跨模块的大改（动 `command/` + `gui/` + 服务层）优先交给 foreman 排期，不要两个 agent 各改一半。

## 计划文件

计划写 `.zcode/plans/plan-*.md`（本地，gitignore）。**结论**要写进
`docs/handoff/archive/v<版本>.md` 或本文件，否则等于没写。

## 写代码的硬约束（和 workbuddy 相同）

1. 新存档走 `com.forgemaster.util.YamlIo`。
2. 命令三处同源：switch + `SUBCOMMANDS` + `helpLines()`。
3. 管理命令进 `ADMIN_ONLY` 且 handler 内自判权限。
4. 新功能配自检钉子，项数只增不减（当前 520）。
5. 锻造系手续费永远是剑币。
6. 压测固定 4 并发。