---
agent: workbuddy
role: 代码实现 / 检修（可写 src，需与 zcode 串行）
authority: normative
status: active
owns-docs: [".workbuddy/**"]
may-write: ["src/**", "pom.xml", "tools/guitest/**", ".workbuddy/**"]
must-not-write: ["README.md", "AGENTS.md", "audit/**", "docs/**", ".agents/**"]
read-first: ["AGENTS.md", "docs/handoff/LATEST.md", ".agents/workbuddy/agent.md"]
serialize-with: ["zcode"]
---

# workbuddy

代码实现方之一。和 `zcode` 共享 `src/**`，所以**动手前必须拿锁**：

```powershell
& $py tools\agentdocs.py lock --as workbuddy
# ... 改代码 ...
& $py tools\agentdocs.py unlock --as workbuddy
```

不拿锁直接提交会被拦住（`check --staged` 会看到锁在别人手里）。

## 写代码的硬约束

1. 新存档走 `com.forgemaster.util.YamlIo`；裸 `yml.save(file)` 自检判红。
2. 命令三处同源：switch 分派 + `SUBCOMMANDS` + `helpLines()`。
3. 管理命令进 `ADMIN_ONLY` **且** handler 内自判 `forgemaster.admin`。
4. 新功能必须配自检钉子，**项数只增不减**（当前 520）。
5. 锻造系手续费永远是剑币 `stats().takeCoins`，别改成 `wallet()`。

## 改完填这里（下一轮靠它接手）

- 本轮动了哪些文件：
- 新增/修改的自检钉子：
- 自检：___/___（失败必须 0）
- 压测：PASS ___ / WARN ___ / FAIL ___
- 留了哪些坑给下一轮：

## 历史流水

过程记录还在 `.workbuddy/memory/<日期>.md`（被 gitignore，只当本地日志）。
**任何「下一轮还需要知道」的结论必须搬到 `docs/handoff/` 或本文件**，
否则换机器/新会话就等于没写 —— 这是以前漏读的根因。