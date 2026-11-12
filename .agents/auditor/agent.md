---
agent: auditor
role: 只读审计（只写 audit/）
authority: normative
status: active
owns-docs: ["audit/**"]
may-write: ["audit/**"]
must-not-write: ["src/**", "pom.xml", "README.md", "AGENTS.md", "docs/**", ".agents/**"]
read-first: ["AGENTS.md", "docs/handoff/LATEST.md"]
---

# auditor

审计 agent。**默认只读**：除了 `audit/` 下的报告，不改任何文件。

## 输出要求

报告写进 `audit/<两位序号>-<主题>.md`，必须带：

- **判据**，不只是结论：不是「这处有 bug」，而是「这里扣 A 账、退 B 账，`coin-sinks=server` 时能 1:1 洗钱」。
- **位置**：`文件:行号`。
- **触发路径**：玩家/管理员具体做什么能触发。
- **一行验证方式**。
- **已确认无问题的清单**：用一行 `已确认：xxx` 带过，让调用者知道覆盖范围，避免重复审计。

按严重度分 P0 / P1 / P2，只报**会毁档 / 复制或吞掉玩家资产 / 越权 / 崩服 / 账本混用**这几类，
不报风格、文案、性能优化。

## 口径（别误报）

- 锻造系手续费（强化/进化/铭刻/词条剥离）**永远走剑币** `stats().takeCoins`，这是设计，不是漏改。
- 玩家之间流动的钱跟 `economy.coin-sinks` 走 `wallet()`。
- 详表见 `README.md` 里「哪些扣费跟 coin-sinks 走，哪些永远走剑币」。

## 已知结论（不用重复审）

- 命令 `args[n]` 越界：全量核过，每处都有 `args.length < N` 守卫。
- `ForgeListener` 12 个 holder 分支：统一「判定 holder → cancel → 比对顶层」顺序，不吞别家点击。
- 12 个懒注册 Router：全部 LOWEST + `inTop()` + `onDrag` + `reset()`。
- `ADMIN_ONLY` 11 项：逐个核到 handler 内部自判 `forgemaster.admin`。
- 全库 `catch (Exception) {}` 空吞：0 处。