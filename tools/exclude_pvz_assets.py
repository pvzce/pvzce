#!/usr/bin/env python3
"""把**不能公开分发**的原版素材移出仓库工作区，同时保证本地仍能正常游玩。

背景：`assets/pvzce/` 下混了三类素材——从 `refer/` 转换/搬运的原版素材（PopCap/EA 版权）、
基于原版 IP 的二次创作、以及完全自产的。第一类不能随公开仓库分发，但本地构建又需要它。
分类规则与依据在 `tools/classify_assets.py`，本脚本只负责**执行**分类结果。

做法：把 PvZ 类素材整体搬到仓库外的 `../pvzce-assets/`，在那里生成一个标准的 PVZCE 资源包
（`pack.mcmeta` + `assets/pvzce/...`）。游戏本来就优先读游戏目录下的 `resourcepacks/`，
所以本地把那个目录软链或拷进 gameDir 就能玩；`git` 那边因为文件已经不在工作区，自然不会再跟踪。

    python3 tools/exclude_pvz_assets.py --dry-run   # 先看要动哪些文件
    python3 tools/exclude_pvz_assets.py             # 执行（移动）
    python3 tools/exclude_pvz_assets.py --rm        # 反向：搬回原位（撤销）

素材留在**本机**，只是不在 git 工作区里；没推过 GitHub 的仓库也不会因此丢东西。
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import shutil
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
ASSETS = REPO_ROOT / "pvzce-game" / "src" / "main" / "resources" / "assets" / "pvzce"

#: 素材归宿。**在仓库内但被 `.gitignore` 排除**——这样原版素材仍然留在这台机器上、本地构建
#: 照常能打包，但 git 不再跟踪它，推 GitHub 时也不会带上。
DEST = REPO_ROOT / "local-assets"

PACK_MCMETA = {
    "pack": {
        "pack_format": 1,
        "description": "PVZCE 原版素材（本地专用，不随仓库分发）",
    }
}

README = """# PVZCE 原版素材（本地专用，不入库）

这个目录由 `tools/exclude_pvz_assets.py` 生成。它**被 `.gitignore` 排除**，不随仓库分发。

- `assets/pvzce/` —— 从 `refer/` 转换或搬运的原版素材，版权归 **PopCap Games / Electronic Arts**。
- `pack.mcmeta` —— 让它同时是一个合法的 PVZCE 资源包。

## 本地怎么用

不用管：`pvzce-game/build.gradle` 里有个 `stageLocalAssets` 任务，只要这个目录存在，就会把
`local-assets/assets/` 并进构建期资源，所以 `./gradlew :pvzce-game:run` 与冒烟照常带素材跑。
目录不存在（比如刚 clone 下来）时该任务自动跳过，游戏仍然能启动，只是贴图显示为缺失棋盘格。

## 为什么不能公开

见仓库根的 `THIRD-PARTY.md` §4 与 `docs/报告/PvZ开源项目的许可证与版权素材处理调研.md`：
EA 的官方内容政策明确禁止 `game revivals, fan games` 与 `obtain assets through "data mining"`。
分类与依据见 `docs/素材对照表.md`。
"""


def load_classifier():
    spec = importlib.util.spec_from_file_location(
        "classify_assets", Path(__file__).with_name("classify_assets.py"))
    module = importlib.util.module_from_spec(spec)
    sys.modules["classify_assets"] = module
    spec.loader.exec_module(module)
    return module


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--dry-run", action="store_true", help="只打印计划，不动文件")
    ap.add_argument("--rm", action="store_true", help="反向操作：把素材搬回仓库工作区")
    ap.add_argument("--copy", action="store_true", help="复制而不是移动（原文件保留在原地）")
    args = ap.parse_args()

    ca = load_classifier()
    entries, unclassified = ca.walk()

    if unclassified:
        print(f"拒绝执行：有 {len(unclassified)} 个素材没有归类，先补 tools/classify_assets.py "
              f"的规则。例如 {unclassified[:3]}", file=sys.stderr)
        return 1

    targets = [e for e in entries if e.kind == ca.PvZ]
    total = sum(e.size for e in targets)
    verb = "搬回" if args.rm else ("复制" if args.copy else "移出")

    print(f"分类：{len(entries)} 个文件，其中 PvZ 类 {len(targets)} 个 / {total / 1048576:.2f} MB")
    print(f"动作：{verb} → {DEST}")
    for e in targets[:8]:
        print(f"   {e.path}")
    if len(targets) > 8:
        print(f"   …共 {len(targets)} 个")

    if args.dry_run:
        print("\n--dry-run：什么都没动。")
        return 0

    if args.rm:
        moved = 0
        for e in targets:
            src = DEST / "assets" / "pvzce" / e.path
            dst = ASSETS / e.path
            if not src.exists():
                continue
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(src), str(dst))
            moved += 1
        print(f"已搬回 {moved} 个文件。")
        _prune(DEST)
        return 0

    for e in targets:
        src = ASSETS / e.path
        dst = DEST / "assets" / "pvzce" / e.path
        dst.parent.mkdir(parents=True, exist_ok=True)
        if args.copy:
            shutil.copy2(src, dst)
        else:
            shutil.move(str(src), str(dst))
    print(f"已{verb} {len(targets)} 个文件。")

    (DEST / "pack.mcmeta").write_text(
        json.dumps(PACK_MCMETA, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (DEST / "README.md").write_text(README, encoding="utf-8")
    print(f"已生成资源包骨架：{DEST}/pack.mcmeta")
    _prune(ASSETS)
    return 0


def _prune(root: Path) -> None:
    """删掉搬空后剩下的空目录——留着会在素材树里造成"这里有东西"的错觉。"""
    removed = 0
    for path in sorted(root.rglob("*"), key=lambda p: -len(p.parts)):
        if path.is_dir() and not any(path.iterdir()):
            path.rmdir()
            removed += 1
    if removed:
        print(f"顺带清掉 {removed} 个空目录。")


if __name__ == "__main__":
    raise SystemExit(main())
