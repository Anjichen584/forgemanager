#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""agentdocs —— 多 agent 协作的文档索引 / 写权限 / 串行锁工具。

解决的问题（都是真实踩过的）：

1. **漏读**：每个 agent 各建一个目录写 README，其中 `.workbuddy/`、`.zcode/` 被 gitignore，
   换机器 / 新会话 / 别人接手时等于不存在。→ `doctor` 会把「没入库的文档」点名。
2. **读取不到**：没有总入口，agent 只能猜。→ `index` 生成唯一地图，写进 `AGENTS.md`
   （DSH 会自动把 `AGENTS.md` 注入上下文，这是杠杆最高的位置）。
3. **打架**：多个 agent 同时改 `src/**` 或同时改根 `README.md`。→ `check --staged` 按身份卡的
   `may-write` / `must-not-write` 拦，`lock` 让共享范围的 agent 串行。

命令：
    python tools/agentdocs.py index
    python tools/agentdocs.py check
    python tools/agentdocs.py check --staged --as workbuddy
    python tools/agentdocs.py doctor
    python tools/agentdocs.py lock --as workbuddy [--ttl 120]
    python tools/agentdocs.py unlock --as workbuddy
    python tools/agentdocs.py status

退出码：0 = 干净，1 = 有问题（可以直接挂在 commit 前当钩子）。
"""

from __future__ import annotations

import fnmatch
import json
import os
import subprocess
import sys
import time
from datetime import datetime

if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AGENTS_DIR = os.path.join(ROOT, ".agents")
INDEX_FILE = os.path.join(AGENTS_DIR, "_index.md")
LOCK_FILE = os.path.join(AGENTS_DIR, "_lock.json")
AGENTS_MD = os.path.join(ROOT, "AGENTS.md")
BEGIN = "<!-- agents:index:begin -->"
# README 体量预算（v2.25.6 拆分后定）。超了 `check` 直接报错，不靠自觉。
# 现状 386 行 / 37 KB，留一点余量够加索引行和零星修订。
README_MAX_LINES = 450
README_MAX_KB = 48
END = "<!-- agents:index:end -->"

REQUIRED_KEYS = ("agent", "role", "authority", "status")
SKIP_DIRS = {"target", "build", "out", "node_modules", ".git", "testserver",
             "logs", "world", "world_nether", "world_the_end", "plugins"}


# --------------------------------------------------------------------------- 基础

def rel(path: str) -> str:
    return os.path.relpath(path, ROOT).replace("\\", "/")


def read(path: str) -> str:
    with open(path, "r", encoding="utf-8") as fh:
        return fh.read()


def write(path: str, text: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def parse_front_matter(text: str) -> dict:
    """极简 YAML front-matter：`key: value`、`key: [a, b]`、`key:` 换行 `- a`。"""
    if not text.startswith("---"):
        return {}
    end = text.find("\n---", 3)
    if end == -1:
        return {}
    body = text[3:end].strip("\n")
    data: dict = {}
    current_list_key = None
    for raw in body.split("\n"):
        line = raw.rstrip()
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if line.lstrip().startswith("- ") and current_list_key:
            data.setdefault(current_list_key, []).append(_scalar(line.lstrip()[2:]))
            continue
        if ":" not in line:
            continue
        key, _, value = line.partition(":")
        key = key.strip()
        value = value.strip()
        if not value:
            current_list_key = key
            data.setdefault(key, [])
            continue
        current_list_key = None
        if value.startswith("[") and value.endswith("]"):
            inner = value[1:-1].strip()
            data[key] = [_scalar(x) for x in _split_list(inner)]
        else:
            data[key] = _scalar(value)
    return data


def _split_list(inner: str) -> list:
    if not inner:
        return []
    parts, buf, quote = [], "", None
    for ch in inner:
        if quote:
            if ch == quote:
                quote = None
            buf += ch
        elif ch in "\"'":
            quote = ch
            buf += ch
        elif ch == ",":
            parts.append(buf)
            buf = ""
        else:
            buf += ch
    if buf.strip():
        parts.append(buf)
    return parts


def _scalar(text: str):
    text = text.strip()
    if len(text) >= 2 and text[0] == text[-1] and text[0] in "\"'":
        return text[1:-1]
    return text


def load_cards() -> list[dict]:
    cards = []
    if not os.path.isdir(AGENTS_DIR):
        return cards
    for name in sorted(os.listdir(AGENTS_DIR)):
        path = os.path.join(AGENTS_DIR, name, "agent.md")
        if not os.path.isfile(path):
            continue
        meta = parse_front_matter(read(path))
        meta["_dir"] = name
        meta["_path"] = rel(path)
        cards.append(meta)
    return cards


def matches_any(path: str, patterns) -> str | None:
    if isinstance(patterns, str):
        patterns = [patterns]
    p = path.replace("\\", "/")
    for pat in patterns or []:
        pat = pat.strip()
        if not pat:
            continue
        if fnmatch.fnmatch(p, pat):
            return pat
        # "src/**" 也要匹配 "src" 本身与 "src/x"
        base = pat[:-3] if pat.endswith("/**") else None
        if base and (p == base or p.startswith(base + "/")):
            return pat
    return None


def tracked_files() -> list[str]:
    try:
        out = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True,
                             text=True, encoding="utf-8", check=True).stdout
    except Exception:
        return []
    return [line.strip() for line in out.splitlines() if line.strip()]


# --------------------------------------------------------------------------- index

def build_index(cards: list[dict]) -> str:
    lines = ["| 文档 | 归属 | 级别 | 说明 |", "| --- | --- | --- | --- |"]
    # 身份卡
    for c in cards:
        lines.append("| `{}` | {} | {} | {} |".format(
            c["_path"], c.get("agent", "?"), c.get("authority", "?"),
            c.get("role", "")))
    # 固定文档
    fixed = [
        ("AGENTS.md", "foreman", "normative", "唯一入口：读的顺序 / 写权限 / 硬规则"),
        ("docs/handoff/LATEST.md", "foreman", "normative", "上一轮交付报告（**必读**）"),
        ("docs/handoff/README.md", "foreman", "normative", "交接报告怎么用"),
        ("README.md", "foreman", "normative", "项目索引 + 命令 + 配置 + 技术实现（v2.25.6 从 282 KB 拆到 35 KB）"),
        (".agents/README.md", "foreman", "normative", "身份卡格式与三条铁律"),
    ]
    for doc, owner, level, desc in fixed:
        if os.path.exists(os.path.join(ROOT, doc)):
            lines.append("| `{}` | {} | {} | {} |".format(doc, owner, level, desc))
    # docs/manual、docs/history、docs/dev：按目录点名，不逐个列
    for sub, desc in (("manual", "玩法手册（玩法/经济/成长线/升级）"),
                      ("history", "逐版本叙事（查「以前修过没」「数值为什么这么定」）"),
                      ("dev", "构建与测试服操作")):
        d = os.path.join(ROOT, "docs", sub)
        if os.path.isdir(d):
            items = sorted(x for x in os.listdir(d) if x.endswith(".md"))
            if items:
                lines.append("| `docs/{}/*.md` | foreman | {} | {}：{} |".format(
                    sub, "informative" if sub != "dev" else "normative", desc,
                    " / ".join(x[:-3] for x in items)))
    # audit 目录只点名，不逐个列（22 份，列全了反而看不清）
    audit_dir = os.path.join(ROOT, "audit")
    if os.path.isdir(audit_dir):
        n = len([x for x in os.listdir(audit_dir) if x.endswith(".md")])
        lines.append("| `audit/*.md` | auditor | informative | 历史审计报告 {} 份，按文件名挑着读 |".format(n))
    # 交付报告归档
    arch = os.path.join(ROOT, "docs", "handoff", "archive")
    if os.path.isdir(arch):
        versions = sorted(x for x in os.listdir(arch) if x.endswith(".md"))
        if versions:
            lines.append("| `docs/handoff/archive/` | foreman | archive | 每轮交付报告归档（只增不改）：{} |".format(
                " / ".join(v[:-3] for v in versions[-5:])))
    return "\n".join(lines)


def cmd_index() -> int:
    cards = load_cards()
    table = build_index(cards)
    write(INDEX_FILE, "# 文档索引（自动生成，别手改）\n\n"
                      "> 由 `python tools/agentdocs.py index` 生成。手改会被下次覆盖。\n\n"
                      + table + "\n")
    if os.path.isfile(AGENTS_MD):
        text = read(AGENTS_MD)
        if BEGIN in text and END in text:
            head, rest = text.split(BEGIN, 1)
            _, tail = rest.split(END, 1)
            text = head + BEGIN + "\n" + table + "\n" + END + tail
            write(AGENTS_MD, text)
    print("[index] 已生成 {} 与 AGENTS.md 的文档地图（{} 张身份卡）".format(rel(INDEX_FILE), len(cards)))
    return 0


# --------------------------------------------------------------------------- check

def cmd_check(staged: bool, as_agent: str | None) -> int:
    cards = load_cards()
    errors, warns = [], []
    by_name = {c.get("agent"): c for c in cards}

    if not cards:
        print("[check] 没找到任何身份卡（.agents/*/agent.md）")
        return 1
    # 0) 本地专属目录不许入库（v2.25.9）
    # 踩过的坑：.workbuddy/README.md 与 .zcode/README.md 被 `git add -A` 顺手推上去了，
    # 而这两个文件开头就写着「本目录只在本地、不入库」—— 自相矛盾。
    # 单个 agent 的身份卡在 .agents/<名>/agent.md，交接结论在 docs/handoff/，那边才该入库。
    tracked = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True,
                             text=True, encoding="utf-8", errors="replace")
    for _line in tracked.stdout.splitlines():
        _p = _line.strip()
        if _p.startswith(".workbuddy/") or _p.startswith(".zcode/"):
            errors.append(
                "`{}` 是本地专属目录，不该入库 —— 执行 `git rm --cached {}`"
                "（文件保留在磁盘；结论写到 .agents/<名>/agent.md 或 docs/handoff/）".format(_p, _p))


    # 1) 必填键
    for c in cards:
        for key in REQUIRED_KEYS:
            if not c.get(key):
                errors.append("{}: 缺字段 `{}`".format(c["_path"], key))
        if c.get("agent") != c["_dir"]:
            errors.append("{}: `agent: {}` 与目录名 `{}` 不一致".format(
                c["_path"], c.get("agent"), c["_dir"]))
        if not c.get("may-write"):
            errors.append("{}: 没声明 `may-write`，等于可以写任何文件".format(c["_path"]))

    # 2) 自己和自己打架
    for c in cards:
        for pat in c.get("must-not-write", []) or []:
            hit = matches_any(pat.rstrip("*").rstrip("/") or pat, c.get("may-write", []))
            if hit:
                errors.append("{}: `must-not-write` 的 `{}` 与 `may-write` 的 `{}` 互相矛盾".format(
                    c["_path"], pat, hit))

    # 3) 共享写范围必须互相声明 serialize-with
    #    例外：`integrator: true` 的总装方（foreman）本来就该能改任何文件，它是「设计上的超集」，
    #    不是「没协商好的冲突」。但总装方动共享范围时同样要拿锁 —— 只降级成提醒，不静默放行。
    for i in range(len(cards)):
        for j in range(i + 1, len(cards)):
            a, b = cards[i], cards[j]
            overlap = _scope_overlap(a, b)
            if not overlap:
                continue
            a_int = str(a.get("integrator", "")).lower() == "true"
            b_int = str(b.get("integrator", "")).lower() == "true"
            if a_int or b_int:
                boss = a if a_int else b
                warns.append("{} 是总装方，可写范围覆盖 {} 的 {}：动这些文件时也要 `lock`".format(
                    boss.get("agent"), (b if a_int else a).get("agent"),
                    ", ".join(sorted(overlap))))
                continue
            sa = set(a.get("serialize-with", []) or [])
            sb = set(b.get("serialize-with", []) or [])
            if b.get("agent") not in sa or a.get("agent") not in sb:
                errors.append("{} 与 {} 的写范围重叠（{}），必须互相写进 `serialize-with`".format(
                    a.get("agent"), b.get("agent"), ", ".join(sorted(overlap))))
            else:
                warns.append("{} 与 {} 共享 {}：靠锁串行（别忘了 `lock`）".format(
                    a.get("agent"), b.get("agent"), ", ".join(sorted(overlap))))

    # 4) 每份入库文档都要有人认领
    owned = [(c.get("agent"), c.get("owns-docs", []) or []) for c in cards]
    for doc in tracked_files():
        if not doc.endswith(".md"):
            continue
        if doc.startswith(".agents/"):
            continue
        if not any(matches_any(doc, pats) for _, pats in owned):
            errors.append("`{}` 没被任何身份卡的 `owns-docs` 认领（漏读风险）".format(doc))

    # 5) 索引是否最新
    if os.path.isfile(INDEX_FILE) and os.path.isfile(AGENTS_MD):
        table = build_index(cards)
        if table not in read(AGENTS_MD):
            warns.append("AGENTS.md 的文档地图不是最新的，跑一下 `index`")
    else:
        warns.append("还没有 `.agents/_index.md`，跑一下 `index`")

    # 5.5) README 体量预算 —— 防止又攒成一坨
    # v2.25.6 之前 README 涨到 4460 行 / 282 KB，agent 整读会炸上下文，只读一半就动手。
    # 拆完之后加了这道闸：超预算直接报错，并告诉人该往哪写。
    readme_path = os.path.join(ROOT, "README.md")
    if os.path.isfile(readme_path):
        raw = read(readme_path)
        n_lines = len(raw.splitlines())
        kb = len(raw.encode("utf-8")) / 1024
        if n_lines > README_MAX_LINES or kb > README_MAX_KB:
            errors.append(
                "README.md 超预算：{} 行 / {:.0f} KB（上限 {} 行 / {} KB）。\n"
                "         本轮叙事不要往 README 里加小节 —— 写进 `docs/history/v<版本段>.md`（长期价值）\n"
                "         或 `docs/handoff/archive/v<版本>.md`（本轮交接），README 只加一行索引。".format(
                    n_lines, kb, README_MAX_LINES, README_MAX_KB))
        elif n_lines > README_MAX_LINES * 0.9 or kb > README_MAX_KB * 0.9:
            warns.append("README.md 已到 {} 行 / {:.0f} KB，接近上限（{} 行 / {} KB），下一轮该往回搬点东西".format(
                n_lines, kb, README_MAX_LINES, README_MAX_KB))

    # 6) 提交前：staged 文件是否越界
    if staged:
        if not as_agent:
            errors.append("`--staged` 必须配 `--as <agent>`，否则不知道用谁的权限卡")
        else:
            card = by_name.get(as_agent)
            if card is None:
                errors.append("没有名为 `{}` 的身份卡".format(as_agent))
            else:
                for path in staged_files():
                    if path.startswith(".agents/") or path.startswith("docs/handoff/"):
                        # 任何 agent 都可以读，但只有 foreman 能写；这里放行 .agents 之外的自身卡
                        if path.startswith(".agents/") and as_agent != "foreman" \
                                and not path.startswith(".agents/{}/".format(as_agent)):
                            errors.append("`{}` 不是你的身份卡，别改".format(path))
                        continue
                    bad = matches_any(path, card.get("must-not-write", []))
                    if bad:
                        errors.append("`{}` 命中 `{}` 的 must-not-write（`{}`）".format(
                            path, as_agent, bad))
                        continue
                    if not matches_any(path, card.get("may-write", [])):
                        errors.append("`{}` 不在 `{}` 的 may-write 范围内".format(path, as_agent))
                _check_lock(as_agent, errors)

    for w in warns:
        print("[warn ] " + w)
    for e in errors:
        print("[ERROR] " + e)
    if errors:
        print("\n[check] 失败：{} 个错误".format(len(errors)))
        return 1
    print("[check] 通过（{} 张身份卡，{} 条提醒）".format(len(cards), len(warns)))
    return 0


def _scope_overlap(a: dict, b: dict) -> set:
    """粗判：把 `x/**` 归一成 `x/`，看前缀是否互相包含。"""
    def norm(pats):
        out = set()
        for p in pats or []:
            p = p.replace("\\", "/").rstrip("*").rstrip("/")
            out.add(p)
        return out
    sa, sb = norm(a.get("may-write")), norm(b.get("may-write"))
    hits = set()
    for x in sa:
        for y in sb:
            if x == y or x.startswith(y + "/") or y.startswith(x + "/"):
                hits.add(x if len(x) < len(y) else y)
    return hits


def staged_files() -> list[str]:
    try:
        out = subprocess.run(["git", "diff", "--cached", "--name-only"], cwd=ROOT,
                             capture_output=True, text=True, encoding="utf-8",
                             check=True).stdout
    except Exception:
        return []
    return [line.strip() for line in out.splitlines() if line.strip()]


def _check_lock(as_agent: str, errors: list) -> None:
    info = _read_lock()
    if not info:
        errors.append("提交前要先拿锁：`python tools/agentdocs.py lock --as {}`".format(as_agent))
        return
    if info.get("holder") != as_agent:
        errors.append("锁在 `{}` 手里（{} 分钟前），不能提交".format(
            info.get("holder"), int((time.time() - info.get("at", 0)) / 60)))


# --------------------------------------------------------------------------- lock

def _read_lock() -> dict | None:
    if not os.path.isfile(LOCK_FILE):
        return None
    try:
        info = json.loads(read(LOCK_FILE))
    except Exception:
        return None
    ttl = info.get("ttl", 120)
    if time.time() - info.get("at", 0) > ttl * 60:
        return None  # 过期自动失效，避免死锁
    return info


def cmd_lock(as_agent: str, ttl: int) -> int:
    if not os.path.isfile(os.path.join(AGENTS_DIR, as_agent, "agent.md")):
        print("[lock] 没有名为 `{}` 的身份卡".format(as_agent))
        return 1
    cur = _read_lock()
    if cur and cur.get("holder") != as_agent:
        mins = int((time.time() - cur.get("at", 0)) / 60)
        print("[lock] 被 `{}` 占着（{} 分钟前拿的，{} 分钟过期）。".format(
            cur.get("holder"), mins, cur.get("ttl", 120)))
        print("       等对方 `unlock`，或确认他挂了之后 `lock --force`。")
        return 1
    write(LOCK_FILE, json.dumps({"holder": as_agent, "at": time.time(), "ttl": ttl},
                                ensure_ascii=False, indent=2))
    print("[lock] 已由 `{}` 持有（{} 分钟后自动失效）".format(as_agent, ttl))
    return 0


def cmd_unlock(as_agent: str) -> int:
    cur = _read_lock()
    if cur and cur.get("holder") != as_agent:
        print("[unlock] 锁不在你手里（在 `{}`）".format(cur.get("holder")))
        return 1
    if os.path.isfile(LOCK_FILE):
        os.remove(LOCK_FILE)
    print("[unlock] 已释放")
    return 0


def cmd_status() -> int:
    cur = _read_lock()
    if cur:
        mins = int((time.time() - cur.get("at", 0)) / 60)
        print("[status] 锁：`{}` 持有中（{} 分钟前，{} 分钟过期）".format(
            cur.get("holder"), mins, cur.get("ttl", 120)))
    else:
        print("[status] 锁：空闲")
    return 0


# --------------------------------------------------------------------------- doctor

def cmd_doctor() -> int:
    cards = load_cards()
    print("== 身份卡 ==")
    for c in cards:
        print("  {:<11} {:<12} {}".format(c.get("agent", "?"), c.get("status", "?"),
                                          c.get("role", "")))
    print("== 文档体量 ==")
    readme = os.path.join(ROOT, "README.md")
    if os.path.isfile(readme):
        size = os.path.getsize(readme)
        lines = len(read(readme).splitlines())
        flag = "  ← 太大，整读会炸上下文，按章节读" if size > 80_000 else ""
        print("  README.md  {:.0f} KB / {} 行{}".format(size / 1024, lines, flag))
    latest = os.path.join(ROOT, "docs", "handoff", "LATEST.md")
    if os.path.isfile(latest):
        print("  LATEST.md  {:.0f} KB（必读的那一份，理应很小）".format(
            os.path.getsize(latest) / 1024))

    print("== 没入库的文档（gitignore 里 = 换机器就没了）==")
    tracked = set(tracked_files())
    found = 0
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            if not fn.endswith(".md"):
                continue
            full = os.path.join(dirpath, fn)
            r = rel(full)
            if r in tracked:
                continue  # 已入库，换机器也还在
            found += 1
            print("  {}  ({:.0f} KB) ← 未入库".format(r, os.path.getsize(full) / 1024))
    if not found:
        print("  （无）")
    else:
        print("  提示：过程流水放这就行；但「下一轮还需要知道」的结论必须搬到 docs/handoff/ 或 .agents/<你>/agent.md")

    print("== 已入库但没挂索引的文档 ==")
    unindexed = 0
    for doc in sorted(tracked):
        if not doc.endswith(".md") or doc.startswith(".agents/"):
            continue
        owner = None
        for c in cards:
            if matches_any(doc, c.get("owns-docs", []) or []):
                owner = c.get("agent")
                break
        if owner is None:
            unindexed += 1
            print("  {} ← 没有身份卡认领（`check` 会报错）".format(doc))
    if not unindexed:
        print("  （无）")

    print("== 可能过期的文档 ==")
    newest_java = 0
    for dirpath, dirnames, filenames in os.walk(os.path.join(ROOT, "src")):
        for fn in filenames:
            if fn.endswith(".java"):
                newest_java = max(newest_java, os.path.getmtime(os.path.join(dirpath, fn)))
    for base in ("AGENTS.md", "docs/handoff/LATEST.md"):
        p = os.path.join(ROOT, base)
        if not os.path.isfile(p):
            print("  {} 不存在".format(base))
            continue
        age_days = (newest_java - os.path.getmtime(p)) / 86400
        if age_days > 3:
            print("  {}  比最新代码旧 {:.1f} 天 ← 可能没跟上".format(base, age_days))
    print("== 完 ==")
    return 0


# --------------------------------------------------------------------------- main

def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 0
    cmd = argv[0]
    rest = argv[1:]

    def opt(name: str, default=None):
        if name in rest:
            i = rest.index(name)
            if i + 1 < len(rest):
                return rest[i + 1]
        return default

    if cmd == "index":
        return cmd_index()
    if cmd == "check":
        return cmd_check("--staged" in rest, opt("--as"))
    if cmd == "doctor":
        return cmd_doctor()
    if cmd == "lock":
        name = opt("--as")
        if not name:
            print("[lock] 要 `--as <agent>`")
            return 1
        try:
            ttl = int(opt("--ttl", "120"))
        except ValueError:
            ttl = 120
        if "--force" in rest and os.path.isfile(LOCK_FILE):
            os.remove(LOCK_FILE)
        return cmd_lock(name, ttl)
    if cmd == "unlock":
        name = opt("--as")
        if not name:
            print("[unlock] 要 `--as <agent>`")
            return 1
        return cmd_unlock(name)
    if cmd == "status":
        return cmd_status()
    print(__doc__)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
