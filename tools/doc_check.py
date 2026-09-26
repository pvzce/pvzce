#!/usr/bin/env python3
"""收尾提醒：这一轮改了源码，文档是不是也该动？

`AGENTS.md` 的「收尾」一节要求每轮任务结束时过一遍 `docs/README.md` 的台账。这份脚本是那一步的
**兜底**：它读 git 的改动清单，按"改了哪个包 → 哪几份文档描述它"给候选，免得收尾时漏掉一整份分册。
它**不是**门（永远退出 0）：要不要改只有人/agent 知道，脚本只负责把问题摆到眼前。

分工（见 `docs/README.md`）：
- 本脚本 = **收尾提醒**（改了 src 却没改 docs）；
- `:pvzce-game:test` 里的 `DocumentedSymbolsTest` / `DocStatsTest` / `DocLedgerTest` = **真伪校验**
  （文档里的符号、规模统计块、台账覆盖率是不是真的）。

用法：在仓库根 `python3 tools/doc_check.py`。
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import PurePosixPath

REPO_MARKER = "docs/README.md"

# 改了哪个包 → 哪几份文档描述它。前缀匹配，长的先试。
BOOKS_BY_PACKAGE = {
    "pvzce-game/src/main/java/com/pvzce/client/": ["docs/架构-客户端.md"],
    "pvzce-game/src/main/java/com/pvzce/server/": ["docs/架构-服务端.md"],
    "pvzce-game/src/main/java/com/pvzce/common/capability/": [
        "docs/架构-实体与能力.md",
        "docs/架构-扩展点与约定.md",
    ],
    "pvzce-game/src/main/java/com/pvzce/common/core/": ["docs/架构-扩展点与约定.md"],
    "pvzce-game/src/main/java/com/pvzce/common/network/": ["docs/架构-框架层.md"],
    "pvzce-game/src/main/java/com/pvzce/common/": ["docs/架构-框架层.md"],
    "pvzce-game/src/main/java/com/pvzce/api/": [
        "docs/架构-实体与能力.md",
        "docs/架构-扩展点与约定.md",
    ],
    "pvzce-game/src/main/java/com/pvzce/launcher/": ["docs/当前项目架构.md"],
    "pvzce-game/src/main/resources/data/": ["docs/架构-服务端.md", "docs/架构-实体与能力.md"],
    "pvzce-game/src/main/resources/assets/": ["docs/架构-客户端.md"],
    "pvzce-api/src/": ["docs/架构-框架层.md"],
    "pvzce-loader/src/": ["docs/架构-框架层.md"],
}

# 源码改动一律值得看一眼这两份（几乎每轮都要过一遍）。
ALWAYS = ["docs/todo.md"]

# 文档侧：改了源码就该记一笔的地方。
CHANGELOG = "docs/架构变更记录.md"
LEDGER = "docs/README.md"


def git(*args: str) -> str:
    try:
        return subprocess.run(
            # core.quotePath=false：否则 git 会把中文路径写成八进制转义，下面的前缀匹配全部失效。
            ["git", "-c", "core.quotePath=false", *args],
            capture_output=True,
            text=True,
            check=True,
        ).stdout
    except (subprocess.CalledProcessError, FileNotFoundError):
        return ""


def changed_files() -> list[tuple[str, str]]:
    """工作区相对 HEAD 的改动（含未跟踪）：(状态码, 路径)，路径用 / 分隔。

    状态码是 `git status --porcelain` 前两位：`??` 未跟踪、`A ` 新增到暂存区、`M ` / ` M` 修改。
    只有"新增"才需要提醒登记台账 —— 把修改也当成新增会让提醒每次都刷一屏，然后就没人看了。
    """
    listing = git("status", "--porcelain", "--untracked-files=all")
    files = []
    for line in listing.splitlines():
        if len(line) < 4:
            continue
        code, path = line[:2], line[3:].strip()
        if " -> " in path:  # 重命名：取新路径
            path = path.split(" -> ", 1)[1]
        files.append((code, path.strip('"')))
    return files


def is_new(code: str) -> bool:
    return code == "??" or code.startswith("A")


def is_source(path: str) -> bool:
    return path.startswith(("pvzce-game/src/main/", "pvzce-api/src/", "pvzce-loader/src/")) or (
        path.endswith(".gradle") or path.startswith("tools/")
    )


def is_docs(path: str) -> bool:
    return path.startswith("docs/") or path == "AGENTS.md"


def candidates(sources: list[str]) -> list[str]:
    found: list[str] = []
    for path in sources:
        for prefix, books in BOOKS_BY_PACKAGE.items():
            if path.startswith(prefix):
                for book in books:
                    if book not in found:
                        found.append(book)
                break
    for book in ALWAYS:
        if book not in found:
            found.append(book)
    return found


def main() -> int:
    import os

    if not os.path.isfile(REPO_MARKER):
        print("不在仓库根（找不到 docs/README.md），本脚本从仓库根运行。")
        return 0

    entries = changed_files()
    if not entries:
        print("工作区干净：没有改动，收尾这一步没什么可提醒的。")
        return 0

    sources = [path for _, path in entries if is_source(path)]
    docs = [path for _, path in entries if is_docs(path)]

    print(f"改动：源码/工具 {len(sources)} 个，文档 {len(docs)} 个。\n")

    if docs:
        print("已改的文档：")
        for path in sorted(docs):
            print(f"  {path}")
        print()

    # 新增的文档要登记台账（修改不用 —— 否则每次收尾都刷一屏，然后就没人看了）
    new_docs = [
        path
        for code, path in entries
        if is_new(code)
        and path.startswith("docs/")
        and path.endswith(".md")
        and PurePosixPath(path).parent == PurePosixPath("docs")
        and path != LEDGER
    ]
    for path in new_docs:
        print(f"提示：{path} 是新增的 docs/ 根下文档 —— 在 {LEDGER} 里给它一行归属档。")
    if new_docs:
        print()

    if sources and not docs:
        print("!! 改了源码/工具，但一份文档都没改。按 docs/README.md 的台账过一遍，候选：")
        for book in candidates(sources):
            print(f"  - {book}")
        print()
        print(f"  - 行为/结构变了就记一笔 {CHANGELOG}（现象 → 定论）")
        print(f"  - 新增或删除文档要登记 {LEDGER}")
        return 0

    if docs and CHANGELOG not in docs and any(
        f.startswith("pvzce-game/src/main/") for f in sources
    ):
        print(f"提示：改了主源码但没动 {CHANGELOG} —— 只有**行为/结构真的变了**才需要记，")
        print("      纯重构、改名、补测试不用记（见 验证约定.md §8）。")
        return 0

    if not sources:
        print("只改了文档：确认这几份的档位对（活文档可以改，冻结文档不该动，见 docs/README.md）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
