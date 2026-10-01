#!/usr/bin/env python3
"""把 `assets/pvzce/` 下的素材按**版权归属**分类，并生成对照表。

为什么需要这个脚本：仓库里的素材有三个来源——从原版 PvZ 拆包/转换来的、基于原版素材二次
创作的、以及完全自产的。三者在法律上处境不同（前两者不能公开再分发，第三者可以），而靠
文件名是分不出来的：`giant_nut/body.png` 和 `wall_nut/body.png` 逐字节相同（原版坚果复用），
`explosive_nut/body.png` 是原版坚果改了颜色。判断依据只有**文件谱系**（谁生成了它），所以
分类规则集中写在这里，`--check` 用来发现新增的、还没归类的素材。

用法：

    python3 tools/classify_assets.py            # 打印对照表
    python3 tools/classify_assets.py --check    # 只报错：有未分类文件或文件不存在时退出码 1
    python3 tools/classify_assets.py --markdown # 输出可直接贴进文档的 Markdown 表格

分类口径：

    PvZ      原版素材。从 `refer/` 的拆包资源转换或直接拷贝而来，版权归 PopCap/EA。
    DERIV    基于原版 IP 的二次创作。用原版素材改色/拼合，或复刻原版的界面与美术版式
             （卡面、图鉴、标题 logo、关卡缩略图）。法律上仍是衍生作品，与 PvZ 同处理。
    ORIGINAL 原创。自己画或生成的，**没有**照原版的具体造型——四组角色立绘属于这一类：
             题材是豌豆/蘑菇这类通用植物与真菌形象，角色的发型、服装、配色、性格是作者
             自己的设计（所有者 2026-09 明确的口径）。
    OWN      自产。程序生成或手绘的通用图形，不涉及任何角色形象。
    THIRD    第三方开源资源（字体，SIL OFL-1.1），可随仓库分发，但需随附许可。
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
ASSETS = REPO_ROOT / "pvzce-game" / "src" / "main" / "resources" / "assets" / "pvzce"

PvZ, DERIV, ORIGINAL, OWN, THIRD = "PvZ", "DERIV", "ORIGINAL", "OWN", "THIRD"

LABEL = {
    PvZ: "原版素材",
    DERIV: "二次创作",
    ORIGINAL: "原创",
    OWN: "自产",
    THIRD: "第三方开源",
}

#: 分类规则，**顺序敏感**——第一条命中的生效，所以特例要写在通例前面。
#: 每条 = (正则, 分类, 依据)。正则匹配的是相对 `assets/pvzce/` 的路径。
RULES: list[tuple[str, str, str]] = [
    (r"^(textures/entities/plant/environment/resonance_moss/|textures/gui/cards/resonance_moss\.png$|animations/plant/environment/resonance_moss\.json$)", ORIGINAL,
     "GPT Image 原创共鸣苔，源图 tools/art/resonance_moss.png；tools/build_resonance_moss.py 打包高清贴图、卡面与原生动画"),
    (r"^(textures/entities/plant/attacker/echo_lily/|textures/gui/cards/echo_lily\.png$|animations/plant/attacker/echo_lily\.json$)", ORIGINAL,
     "GPT Image 原创回声铃兰，源图 tools/art/echo_lily.png；tools/build_echo_lily.sh 打包卡面与手写动画"),
    (r"^(textures/entities/projectile/echo_wave\.png$|textures/particles/effect/echo_ring\.png$|sounds/original/echo_chime\.ogg$)", OWN,
     "tools/build_echo_lily.sh 生成的声波几何图形与合成铃声，无原版素材"),
    # ---- 原创（**必须先判**，见下面那条注释：通例 `textures/entities/*/.+/` 会把它收走）----
    # 冰爆菇：本项目第一株**不由原版 reanim 转换来的**植物。源图是文生图模型按"冰蓝色蘑菇"
    # 这一通用形象生成的（`tools/art/iceboom_*.png`，随 GPL-3.0 留在仓库里），
    # `tools/gen_iceboom_shroom.py` 把它切成部件贴图并写出卡面与冰弹。
    # 位置在文件最前：`textures/entities/…/.+/`（原版 reanim 零件）与 `textures/gui/cards/`
    # （二创卡面）两条通例都覆盖得到这些路径，规则是第一条命中生效。
    (r"^textures/entities/plant/attacker/iceboom_shroom/", ORIGINAL,
     "冰爆菇的部件贴图，由 `tools/gen_iceboom_shroom.py` 从 `tools/art/iceboom_shroom_body.png` 切出"),
    (r"^textures/entities/projectile/iceboom_bolt\.png$", ORIGINAL,
     "冰爆菇的冰弹，同上脚本由 `tools/art/iceboom_bolt.png` 缩放"),
    (r"^textures/gui/cards/iceboom_shroom\.png$", ORIGINAL,
     "冰爆菇的卡面，同上脚本由 `tools/art/iceboom_shroom_card.png` 缩放"),
    # 这一份**在仓库里**（原版那 83 份动画 JSON 都被移到了 `local-assets/`）：它不是转换来的，
    # 没有可分离的原版素材，骨骼与关键帧都是脚本按这份立绘现写的。
    (r"^animations/plant/attacker/iceboom_shroom\.json$", ORIGINAL,
     "冰爆菇的控制器动画，`tools/gen_iceboom_shroom.py` 手写关键帧"),

    # ---- 原版素材：由工具从 refer/ 转换而来 ----
    (r"^animations/(mechanic|plant|projectile|resource|tool|zombie)/.+\.json$", PvZ,
     "`tools/reanim_to_pvzce_all.py` 从 `refer/anim/*.reanim` 转换；"
     "zombotany 四个是 `tools/make_zombotany_assets.py` 用原版零件拼合"),
    (r"^textures/entities/(mechanic|plant|projectile|resource|tool|zombie)/.+/", PvZ,
     "同上；reanim 图集的零件被裁成独立 PNG"),
    (r"^textures/entities/plant/defense/giant_nut/", PvZ,
     "与 `wall_nut/` 逐字节相同（sha256 1947cf1e…），原版坚果直接复用"),
    (r"^textures/entities/zombie/special/bungee_rig/", PvZ,
     "`tools/make_bungee_rig.py`：从拆包直接拷贝，未作改动"),
    (r"^textures/particles/.+/reanim/", PvZ,
     "原版 reanim 粒子贴图"),
    (r"^textures/entities/status/", PvZ,
     "原版状态图标（冰冻尖刺）"),
    (r"^sounds/music/", PvZ,
     "原版原声（grasswalk/moongrains/ultimate_battle…）"),
    (r"^sounds/sfx/", PvZ,
     "原版音效（chomp/awooga/readysetplant…）"),

    # ---- 原创：自己画的，题材是通用形象 ----
    (r"^textures/gui/dialogue/", ORIGINAL,
     "作者自绘的角色立绘；题材是豌豆/蘑菇这类通用植物与真菌形象，"
     "角色设计（发型、服装、配色、性格）为原创"),

    # ---- 二次创作：复刻原版的界面与美术版式 ----
    # 注：zombotany 是「原版僵尸零件 + 原版植物头」拼合，按所有者口径与 PvZ 同类处理，
    # 因此规则在上面就划给了 PvZ，不在这里。
    (r"^textures/entities/plant/defense/explosive_nut/", DERIV,
     "`tools/gen_explosive_nut.py`：原版坚果改色，形体与线稿相同"),
    (r"^textures/gui/cards/", DERIV,
     "卡面；植物形象源自原版（gen_ui_icons.py / render_controller_icons.py 生成）"),
    (r"^textures/gui/almanac/", DERIV,
     "图鉴 UI，复刻原版图鉴的版式"),
    (r"^textures/gui/hud/", DERIV,
     "HUD，复刻原版布局"),
    (r"^textures/gui/dialog/", DERIV,
     "对话框 UI"),
    (r"^textures/gui/button/", DERIV,
     "按钮 UI"),
    (r"^textures/gui/buff/", DERIV,
     "`gen_*.py` 生成的状态图标，取自原版概念"),
    (r"^textures/gui/award/", DERIV,
     "奖励图标（钱袋/存钱罐），原版形象"),
    (r"^textures/gui/icon/", DERIV,
     "图标（锁），原版形象"),
    (r"^textures/gui/screen/title/title_logo\.png$", DERIV,
     "自绘「植物大战僵尸 社区版」标题，沿用原版字体与配色"),
    (r"^textures/gui/screen/", DERIV,
     "标题/选卡/关卡背景，含复刻的原版关卡缩略图"),
    (r"^textures/scene/", DERIV,
     "场景贴图；关卡地面复刻原版（水的部分由 `gen_water_*.py` 生成）"),
    (r"^textures/particles/", DERIV,
     "`tools/particles_to_pvzce.py` 由原版 emitter 转换，另有自绘"),
    (r"^textures/resource/", DERIV,
     "资源图标（阳光/金币/能量豆），原版形象"),

    # ---- 自产 ----
    (r"^textures/entities/plant/defense/(bowling_nut|giant_nut)/", OWN,
     "自建实体，贴图自绘"),
    (r"^textures/entities/(mechanic|plant|projectile|resource|tool)/[^/]+\.png$", OWN,
     "扁平散图，由 `tools/group_entity_assets.py` 归组，未见于任何转换器"),
    (r"^textures/entities/(mechanic|plant|projectile|resource|tool)/[^/]+/[^/]+\.png$", OWN,
     "同上"),
    (r"^textures/mission\.png$", OWN, "自绘"),
    (r"^textures/", OWN, "其余未见于转换器的贴图"),

    # ---- 第三方开源 ----
    (r"^font/", THIRD, "SIL OFL-1.1 开源字体（Noto Sans/Serif SC、站酷快乐体）"),

    (r"^lang/", OWN, "语言文件"),
    (r"^sounds\.json$", OWN, "音效索引"),
    (r"^icon\.png$", OWN, "项目图标"),
]

COMPILED = [(re.compile(p), k, n) for p, k, n in RULES]


@dataclass
class Entry:
    path: str
    kind: str
    note: str
    size: int


def classify(rel: str) -> tuple[str, str] | None:
    for pat, kind, note in COMPILED:
        if pat.search(rel):
            return kind, note
    return None


def walk() -> tuple[list[Entry], list[str]]:
    entries: list[Entry] = []
    unclassified: list[str] = []
    for root, _dirs, files in os.walk(ASSETS):
        for name in sorted(files):
            full = Path(root) / name
            rel = full.relative_to(ASSETS).as_posix()
            hit = classify(rel)
            if hit is None:
                unclassified.append(rel)
                continue
            entries.append(Entry(rel, hit[0], hit[1], full.stat().st_size))
    return entries, unclassified


def group(entries: list[Entry]) -> dict[tuple[str, str, str], list[Entry]]:
    """按 (分类, 目录, 依据) 聚合——对照表的每一行就是这个粒度。"""
    out: dict[tuple[str, str, str], list[Entry]] = {}
    for e in entries:
        directory = e.path.rsplit("/", 1)[0] if "/" in e.path else ""
        key = (e.kind, directory, e.note)
        out.setdefault(key, []).append(e)
    return out


def fmt_size(n: int) -> str:
    return f"{n / 1048576:.2f} MB" if n >= 1024 else f"{n / 1024:.0f} KB"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--check", action="store_true", help="只校验：有未分类文件就失败")
    ap.add_argument("--markdown", action="store_true", help="输出 Markdown 表格")
    ap.add_argument("--summary", action="store_true",
                    help="按 分类×顶层目录 汇总，用来看改动量")
    args = ap.parse_args()

    entries, unclassified = walk()

    if unclassified:
        print(f"有 {len(unclassified)} 个素材文件没有归类：", file=sys.stderr)
        for p in unclassified[:40]:
            print(f"  {p}", file=sys.stderr)
        if len(unclassified) > 40:
            print(f"  …还有 {len(unclassified) - 40} 个", file=sys.stderr)
        if args.check:
            return 1

    if args.check:
        missing = [e.path for e in entries if not (ASSETS / e.path).exists()]
        if missing:
            print(f"分类表引用了不存在的文件：{missing[:5]}", file=sys.stderr)
            return 1
        print(f"素材分类完整：{len(entries)} 个文件全部有归属。")
        return 0

    buckets = group(entries)
    totals: dict[str, list[int]] = {}
    for e in entries:
        t = totals.setdefault(e.kind, [0, 0])
        t[0] += 1
        t[1] += e.size

    if args.summary:
        agg: dict[tuple[str, str], list[int]] = {}
        for e in entries:
            parts = e.path.split("/")
            top = "/".join(parts[:2]) if len(parts) > 2 else e.path
            a = agg.setdefault((e.kind, top), [0, 0])
            a[0] += 1
            a[1] += e.size
        for kind in (PvZ, DERIV, ORIGINAL, OWN, THIRD):
            print(f"===== {LABEL[kind]}（{kind}）=====")
            n = s = 0
            for (k, top), (c, z) in sorted(agg.items()):
                if k != kind:
                    continue
                print(f"   {top:48} {c:5} {fmt_size(z):>10}")
                n += c
                s += z
            print(f"   {'小计':48} {n:5} {fmt_size(s):>10}\n")
        return 0

    if args.markdown:
        print("| 素材路径 | 文件数 | 体积 | 归属 | 依据 |")
        print("|---|---:|---:|---|---|")
        for (kind, directory, note), items in sorted(
                buckets.items(), key=lambda kv: (kv[0][0], kv[0][1])):
            size = sum(i.size for i in items)
            where = f"`{directory}`" if directory else "（资源根）"
            print(f"| {where} | {len(items)} | {fmt_size(size)} | **{LABEL[kind]}** | {note} |")
        print()
        print("| 汇总 | 文件数 | 体积 |")
        print("|---|---:|---:|")
        for kind in (PvZ, DERIV, ORIGINAL, OWN, THIRD):
            n, s = totals.get(kind, [0, 0])
            print(f"| {LABEL[kind]}（`{kind}`） | {n} | {fmt_size(s)} |")
        return 0

    print(f"{'分类':10} {'目录':56} {'文件':>5} {'体积':>10}")
    for (kind, directory, _note), items in sorted(
            buckets.items(), key=lambda kv: (kv[0][0], kv[0][1])):
        size = sum(i.size for i in items)
        print(f"{LABEL[kind]:10} {directory:56} {len(items):5} {fmt_size(size):>10}")
    print()
    for kind in (PvZ, DERIV, ORIGINAL, OWN, THIRD):
        n, s = totals.get(kind, [0, 0])
        print(f"  {LABEL[kind]:10} {n:5} 文件  {fmt_size(s):>10}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
