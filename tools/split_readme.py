#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 README.md 里「逐版本叙事」那一大段拆到 docs/history/，README 只留索引 + 核心参考。

为什么用脚本而不是手改：这个文件 4460 行 / 286 KB，手工 edit 已经两次吃掉过
`## 十四·补十三` 的标题（fork 出去的 agent 也差点再犯）。脚本能保证：

1. 切分按行号做，不做文本替换 —— 不存在「匹配到了别处」。
2. 切完逐条核对标题清单：**拆前有几条，拆后必须恰好每一条都在，且只出现一次**。
3. 正文非空白字符数守恒（允许只多出各分段文件的表头）。
4. 索引表里的锚点链接自动改写成 `docs/history/<文件>#<锚点>`。

用法：
    python tools/split_readme.py --dry-run     # 只看报告，不动文件
    python tools/split_readme.py --apply       # 真拆
"""

from __future__ import annotations

import argparse
import difflib
import os
import re
import sys

if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
README = os.path.join(ROOT, "README.md")
HISTORY = os.path.join(ROOT, "docs", "history")

# 迁移规则：每个「## 」小节按标题归到某个文件。
# 用「标题关键词 → 目标文件」的显式表，不用正则猜 —— 猜错了就是内容错位。
# 两类去处：
#   docs/history/  逐版本叙事（查「以前修过没」「这个数值为什么这么定」时才读）
#   docs/manual/   玩法手册（服主/玩家看的手感与规则，写代码时基本不用读）
ERA_RULES = [
    ("十四·补十五", "history", "v2.25.x.md"),
    ("十四·补十四", "history", "v2.25.x.md"),
    ("十四·补十三", "history", "v2.25.x.md"),
    ("十四·补十二", "history", "v2.25.x.md"),
    ("十四·补十一", "history", "v2.25.x.md"),
    ("十四·补十、", "history", "v2.25.x.md"),
    ("十四·补九", "history", "v2.25.x.md"),
    ("十四·补八", "history", "v2.24.x.md"),
    ("十四·补七", "history", "v2.24.x.md"),
    ("十四·补六", "history", "v2.23.x.md"),
    ("十四·补五", "history", "v2.23.x.md"),
    ("十四·补四", "history", "v2.23.x.md"),
    ("十四·补三", "history", "v2.23.x.md"),
    ("十四·补二", "history", "v2.23.x.md"),
    ("十四·补、", "history", "v2.23.x.md"),
    ("十五、", "history", "v2.18-2.21.md"),
    ("十六、", "history", "v2.18-2.21.md"),
    ("十七、", "history", "v2.18-2.21.md"),
    ("十八、", "history", "v2.18-2.21.md"),
    ("十九、", "history", "v2.18-2.21.md"),
    ("二十、", "history", "v2.18-2.21.md"),
    ("二十一、", "history", "v2.10.x.md"),
    ("二十二、", "history", "v2.10.x.md"),
    ("二十三、", "history", "v2.10.x.md"),
    ("二十四、", "history", "v2.10.x.md"),
    # 玩法手册：写代码时不需要，服主查规则时才要
    ("二、玩法流程", "manual", "gameplay.md"),
    ("三、货币", "manual", "economy.md"),
    ("四、连锻链", "manual", "gameplay.md"),
    ("五、师徒", "manual", "progression.md"),
    ("六、成就", "manual", "progression.md"),
    ("七、环境修正", "manual", "gameplay.md"),
    ("八、剑灵", "manual", "progression.md"),
    ("九、委托", "manual", "progression.md"),
    ("十、专精等级", "manual", "progression.md"),
    # 升级指南（1.x → 2.0）：换版本时才看
    ("十四、升级指南", "manual", "upgrade.md"),
    # 构建与本机测试服操作：开发和 CI 参考，不是玩法
    ("二十五、构建", "dev", "build.md"),
]

MANUAL_TITLE = {
    "upgrade.md": "从 1.x 升级到 2.0",
    "build.md": "构建、测试服与压测",
    "gameplay.md": "玩法流程与核心手感",
    "economy.md": "货币、锻造费与装备",
    "progression.md": "成长线（师徒 / 成就 / 剑灵 / 委托 / 专精）",
}

ERA_TITLE = {
    "v2.25.x.md": "v2.25.x 版本叙事（2.25.0 ~ 2.25.6）",
    "v2.24.x.md": "v2.24.x 版本叙事（2.24.0 ~ 2.24.1）",
    "v2.23.x.md": "v2.23.x 版本叙事（2.22.x ~ 2.23.7）",
    "v2.18-2.21.md": "v2.18 ~ v2.21 版本叙事",
    "v2.10.x.md": "v2.10.x 版本叙事",
}

# README 里保留哪些「## 」小节（其余凡是能匹配上 ERA_RULES 的都搬走）
KEEP_HEADINGS = (
    "〇、", "一、", "十一、", "十二、", "十三、", "二十六、",
)


def slug(text: str) -> str:
    """GitHub 风格锚点：小写、去标点、空格转连字符、保留中日韩与字母数字。"""
    t = text.lower()
    out = []
    for ch in t:
        if ch.isalnum() or ch in "-_" or "\u4e00" <= ch <= "\u9fff":
            out.append(ch)
        elif ch == " ":
            out.append("-")
        # 其它（·、，。：+（）「」等）直接丢掉
    return "".join(out)


def split_blocks(lines: list[str]) -> list[tuple[str | None, int, int]]:
    """按 `## ` 切成 (标题, 起, 止) 块。第一块是文件头（标题为 None）。"""
    starts = [i for i, ln in enumerate(lines) if ln.startswith("## ")]
    blocks = []
    if not starts:
        return [(None, 0, len(lines))]
    if starts[0] > 0:
        blocks.append((None, 0, starts[0]))
    for idx, s in enumerate(starts):
        e = starts[idx + 1] if idx + 1 < len(starts) else len(lines)
        blocks.append((lines[s][3:].strip(), s, e))
    return blocks


def route_of(heading: str):
    """返回 (子目录, 文件名)；None 表示留在 README。"""
    for key, sub, target in ERA_RULES:
        if heading.startswith(key):
            return sub, target
    return None


def body_chars(text: str) -> int:
    return len(re.sub(r"\s", "", text))


def fix_relative_links(text: str, depth: int) -> str:
    """把「相对仓库根」的链接改成相对新位置（文件在 docs/<子目录>/ 下，要退 depth 层）。

    README 原来在仓库根，所以 `audit/08-expansion-plan.md`、`src/main/java/...` 这类
    链接是相对根写的。搬到 `docs/history/xxx.md` 之后，同样一段文字就指向
    `docs/history/audit/...`（不存在）。第一次拆完内链自检抓到 16 条死链，全是这个原因。
    """
    prefix = "../" * depth

    def repl(m):
        target = m.group(2).strip()
        if not target:
            return m.group(0)
        if target.startswith(("http://", "https://", "mailto:", "#", "/", "../")):
            return m.group(0)
        # 注意 group(1) 已经带了左括号，这里不能再加一个（第一次就多打了一个，
        # 生成出 `[x]((../../audit/...))` 这种双括号，内链自检当场抓到 8 条）
        return "{}{}{})".format(m.group(1), prefix, target)

    return re.sub(r"(\[[^\]]*\]\()([^)]+)\)", repl, text)


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args(argv)
    apply = args.apply

    with open(README, "r", encoding="utf-8") as fh:
        original = fh.read()
    lines = original.split("\n")

    blocks = split_blocks(lines)
    keep, move = [], []
    for heading, s, e in blocks:
        if heading is None:
            keep.append((heading, s, e))
            continue
        if any(heading.startswith(k) for k in KEEP_HEADINGS):
            keep.append((heading, s, e))
            continue
        route = route_of(heading)
        if route is None:
            # 没配规则的小节：默认留在 README（宁可少搬，不可错搬）
            print("[warn] 没配迁移规则，保留在 README：## {}".format(heading))
            keep.append((heading, s, e))
            continue
        move.append((heading, s, e, route[0], route[1]))

    print("== 切分计划 ==")
    print("  保留 {} 个小节（含文件头）".format(len(keep)))
    print("  迁出 {} 个小节".format(len(move)))
    by_file: dict[str, list] = {}
    for heading, s, e, sub, target in move:
        by_file.setdefault(sub + "/" + target, []).append((heading, s, e))
    for name in sorted(by_file):
        chars = sum(body_chars("\n".join(lines[s:e])) for _, s, e in by_file[name])
        print("    {}: {:>2} 节, {:>7} 字".format(name, len(by_file[name]), chars))

    if not move:
        print("[!] 没有可迁出的小节，检查 ERA_RULES")
        return 1

    # ---- 锚点映射：把索引表里 (#锚点) 改指到新文件 ----
    # 关键：要收**所有层级**的标题（`###` 也要），因为有些链接指向被搬走的
    # 小节内部的子标题（例如「哪些扣费跟 coin-sinks 走」在「三、货币」里面）。
    # 而且原表里的锚点有手写误差，不能要求 slug 完全相等 —— 用「相同则直配，
    # 否则取最相似且是该锚点前缀的那条」来兜。
    moved_slugs: dict[str, str] = {}
    for heading, s, e, sub, target in move:
        dest = "docs/{}/{}".format(sub, target)
        block_text = "\n".join(lines[s:e])
        for m in re.finditer(r"(?m)^#{1,6} (.+)$", block_text):
            moved_slugs[slug(m.group(1).strip())] = dest

    keep_slugs: set[str] = set()
    for heading, s, e in keep:
        block_text = "\n".join(lines[s:e])
        for m in re.finditer(r"(?m)^#{1,6} (.+)$", block_text):
            keep_slugs.add(slug(m.group(1).strip()))

    # 全量锚点表：slug → 目标文件（None = 还在 README），用于「修死链 + 搬迁」两件事
    all_slugs: dict[str, str | None] = {}
    for cand in moved_slugs:
        all_slugs[cand] = moved_slugs[cand]
    for cand in keep_slugs:
        all_slugs.setdefault(cand, None)
    keep_all_slugs = keep_slugs

    def resolve_anchor(anchor: str):
        """返回 (目标文件 或 None 表示还在 README, 正确的锚点)。

        原索引表里有 4 条锚点**在拆分之前就是死链**（手写漏字，例如
        `#十四补十一v22524-并发实测查错轮4` 少了 `+` 换算出的那个 `-`）。
        拆分时正好一起修正：按相似度找回真实标题，链接文字跟着换成真 slug。
        """
        if anchor in keep_slugs:
            return None, anchor
        if anchor in moved_slugs:
            return moved_slugs[anchor], anchor
        if anchor in keep_all_slugs:
            return None, anchor
        # 模糊匹配：在所有真实标题里找最像的那个
        best, best_score, best_dest = None, 0.0, None
        for cand, dest in all_slugs.items():
            score = difflib.SequenceMatcher(None, anchor, cand).ratio()
            if score > best_score:
                best, best_score, best_dest = cand, score, dest
        if best and best_score >= 0.82:
            fixed = best if best_dest is None else best
            return best_dest, fixed
        return None, anchor

    def rewrite_link(m):
        anchor = m.group(1)
        dest, fixed = resolve_anchor(anchor)
        if dest is None:
            return "(#{})".format(fixed)
        return "({}#{})".format(dest, fixed)

    def rewrite_anchors_in_block(text: str, current: str) -> str:
        """搬走的段落里也可能有 `#锚点` 链接，而那个标题被搬到了**另一个文件**。

        实例：`v2.25.x.md` 里引用「哪些扣费跟 coin-sinks 走」（在 `manual/economy.md`）。
        不处理就是死链。规则：还在 README → 指回 `../../README.md#锚点`；
        同文件 → 只留 `#锚点`；别的文件 → 相对路径 `../子目录/文件.md#锚点`。
        """
        def repl(m):
            anchor = m.group(1)
            dest, fixed = resolve_anchor(anchor)
            if dest is None:
                return "(../../README.md#{})".format(fixed)
            if dest == current:
                return "(#{})".format(fixed)
            sub, _, base = dest.split("/")[1].partition("/") if dest.count("/") >= 2 else ("", "", "")
            parts = dest.split("/")
            if len(parts) == 3:
                return "(../{}/{}#{})".format(parts[1], parts[2], fixed)
            return "(../../{}#{})".format(dest, fixed)

        return re.sub(r"\(#([^)]+)\)", repl, text)

    new_readme = re.sub(r"\(#([^)]+)\)", rewrite_link, original)
    rewritten = len(re.findall(r"docs/[a-z]+/[^)#]+#", new_readme))
    print("  索引表锚点改写到 docs/ 的：{} 处".format(rewritten))

    # ---- 生成历史文件 ----
    outputs: dict[str, str] = {}
    for name, items in by_file.items():
        sub, _, base = name.partition("/")
        title = MANUAL_TITLE.get(base) if sub == "manual" else ERA_TITLE.get(base)
        parts = [
            "# {}".format(title or base),
            "",
            "> 从 `README.md` 拆出来的版本叙事。**为什么拆**：README 一度到 286 KB / 4460 行，",
            "> agent 整读会炸上下文，于是只读一半就动手 —— 真正的「读取不到」。",
            "> 现在 README 只留索引 + 核心参考，历史按版本段放这里。",
            "",
            "[← 回 README](../../README.md) · [← 版本索引](../../README.md#〇更新索引每轮必须同步别再让头部烂掉)",
            "",
            "---",
            "",
        ]
        for heading, s, e in sorted(items, key=lambda x: x[1]):
            block = "\n".join(lines[s:e]).rstrip("\n")
            block = fix_relative_links(block, depth=2)
            block = rewrite_anchors_in_block(block, "docs/{}/{}".format(sub, base))
            parts.append(block)
            parts.append("")
            parts.append("---")
            parts.append("")
        outputs[name] = "\n".join(parts).rstrip("\n") + "\n"

    # ---- 重建 README：保留块 + 版本历史入口 ----
    keep_sorted = sorted(keep, key=lambda x: x[1])
    rebuilt = []
    inserted_history = False
    for heading, s, e in keep_sorted:
        block = "\n".join(lines[s:e])
        # 「版本历史」入口插在「二十五、构建」之前
        if heading and heading.startswith("二十五、") and not inserted_history:
            rebuilt.append(history_section(sorted(by_file)))
            inserted_history = True
        rebuilt.append(block)
    if not inserted_history:
        rebuilt.append(history_section(sorted(by_file)))
    new_readme_final = "\n".join(rebuilt)
    new_readme_final = re.sub(r"\n{4,}", "\n\n\n", new_readme_final)

    # 索引表链接改写（在重建后的文本上再跑一次，覆盖保留块里的引用）
    new_readme_final = re.sub(r"\(#([^)]+)\)", rewrite_link, new_readme_final)

    # ---- 核对 ----
    before_headings = [h for h, _, _ in blocks if h]
    after_headings = re.findall(r"^## (.+)$", new_readme_final, re.M)
    for text in outputs.values():
        after_headings += re.findall(r"^## (.+)$", text, re.M)

    missing = [h for h in before_headings if h not in after_headings]
    dupes = [h for h in set(after_headings) if after_headings.count(h) > 1]
    lost_chars = body_chars(original) - body_chars(new_readme_final) - sum(
        body_chars(t) for t in outputs.values())

    print("== 核对 ==")
    print("  拆前二级标题 {} 条，拆后 {} 条".format(len(before_headings), len(after_headings)))
    print("  丢失的标题：{}".format(missing if missing else "无"))
    print("  重复的标题：{}".format(dupes if dupes else "无"))
    print("  正文非空白字符差：{} （负数=新增了表头，正数=有内容没落盘）".format(lost_chars))
    print("  README: {} 行 / {:.0f} KB → {} 行 / {:.0f} KB".format(
        len(lines), len(original.encode("utf-8")) / 1024,
        len(new_readme_final.split("\n")), len(new_readme_final.encode("utf-8")) / 1024))

    ok = not missing and not dupes and lost_chars <= 400
    print("  结论：{}".format("✅ 可以落地" if ok else "❌ 有问题，别落地"))
    if not ok:
        return 1

    if not apply:
        print("\n（dry-run，没有改动任何文件。加 --apply 才真写）")
        return 0

    for name, text in outputs.items():
        out_path = os.path.join(ROOT, "docs", *name.split("/"))
        os.makedirs(os.path.dirname(out_path), exist_ok=True)
        with open(out_path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
        print("  写入 docs/{}".format(name))
    with open(README, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(new_readme_final)
    print("  写入 README.md")
    return 0


def history_section(files: list[str]) -> str:
    rows = [
        "## 版本历史（已拆分到 `docs/history/`）",
        "",
        "> 为什么拆：README 一度 **286 KB / 4460 行**，agent 整读会炸上下文，只能读一半就动手 ——",
        "> 这才是真正的「读取不到」。现在 README 只留**索引 + 核心参考**（玩法/货币/规则/命令/配置/构建），",
        "> 逐版本叙事按版本段放在下面这些文件里，需要时按版本号挑一份读。",
        "",
        "| 版本段 | 文件 |",
        "| --- | --- |",
    ]
    for name in files:
        sub, _, base = name.partition("/")
        title = (MANUAL_TITLE.get(base) if sub == "manual" else ERA_TITLE.get(base)) or base
        rows.append("| {} | [`docs/{}/{}`](docs/{}/{}) |".format(title, sub, base, sub, base))
    rows += [
        "",
        "**什么时候读历史**：查「这个 bug 以前修过没」「这个数值为什么定成这样」「某个功能的原始设计意图」。",
        "日常开发**不需要读**：当前状态看 `docs/handoff/LATEST.md`，玩法和配置看本文件上面的章节。",
        "",
        "---",
        "",
    ]
    return "\n".join(rows)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))