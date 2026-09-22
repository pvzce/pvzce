#!/usr/bin/env python3
"""Write the Night area's second half (2-6 .. 2-10).

One-off authoring helper, kept out of the shipped tree: the five levels are data, and the
script exists so the dialogue is transcribed once instead of five times. Run from the
repository root:

    python3 tools/write_night_levels.py
"""

from __future__ import annotations

import json
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
LEVELS = REPO_ROOT / "pvzce-game" / "src" / "main" / "resources" / "data" / "pvzce" / "levels" / "yard" / "adventure"

WIDTH, HEIGHT = 9, 5
BACKGROUND = "pvzce:textures/gui/screen/level/background2"


def grass() -> list[str]:
    return [f"{x},{y}" for y in range(HEIGHT) for x in range(WIDTH)]


def line(portrait: str, text: str, side: str = "left", shake: bool = False) -> dict:
    entry = {
        "character": "pvzce:purwhite",
        "portrait": portrait,
        "text": text,
        "voice": "",
        "side": side,
    }
    if shake:
        entry["animation"] = {"type": "shake"}
    return entry


def wave(kind: str, delay: int, entries: list[tuple[str, int]], interval: int,
         warning: int | None = None) -> dict:
    block = {
        "type": kind,
        "delay": delay,
        "entries": [{"id": f"pvzce:{zid}", "count": count} for zid, count in entries],
    }
    if interval:
        block["spawn_interval"] = interval
    if warning is not None:
        block["warning_ticks"] = warning
    return block


def base(level_id: str, name: str, description: str, *, requires: str,
         dialogue: list[dict], waves: list[dict], mechanics: list[dict],
         first_clear: list[dict] | None, slots: list[str] | None = None,
         initial_sun: int = 50, unlock_sun: bool = True,
         music: str = "pvzce:music/moongrains") -> dict:
    level = {
        "id": level_id,
        "name": name,
        "description": description,
        "width": WIDTH,
        "height": HEIGHT,
        "scene": {"pvzce:grass": grass()},
        "teams": [
            {"id": "pvzce:plant_team", "name": "植物方", "win_condition": "survive_waves"},
            {"id": "pvzce:zombie_team", "name": "僵尸方", "win_condition": "plant_side_lost"},
        ],
        "win_team": "pvzce:plant_team",
        "playable_teams": ["pvzce:plant_team"],
        "rules": {
            "pvzce:day_length": 0,
            "pvzce:night_length": 360000,
            "pvzce:sun_spawn_chance": 0,
        },
        "env_vars": {},
        "wave_interval_end_multiplier": 1.0,
        "slots": slots if slots is not None else [],
        "initial_sun": initial_sun,
        "mechanics": mechanics,
        "waves": waves,
        "music": {
            "cues": [{
                "at_tick": 0,
                "track": "background",
                "event": music,
                "loop": True,
                "volume": 0.85,
                "fade_seconds": 1.0,
            }]
        },
        "dialogue": {"lines": dialogue},
        "unlock": {"requires": [{"type": "level", "id": requires}]},
        "background": BACKGROUND,
        "hidden_scene_elements": ["pvzce:grass"],
        "disable_shaders": True,
    }
    if unlock_sun:
        level["unlock_resources"] = {"pvzce:sun": True}
    if first_clear is not None:
        level["rewards"] = {
            "first_clear": first_clear,
            "repeat": [{"type": "coins", "amount": 300}],
            "coin_drop": "pvzce:coin_silver",
            "coin_drop_chance": 0.25,
            "coin_drop_amount": 1,
        }
    return level


def unlock(card: str) -> list[dict]:
    return [{"type": "unlock", "id": card}]


def grave_field(count: int) -> dict:
    return {"type": "pvzce:grave_field", "count": count}


# ---------------------------------------------------------------------------
# 2-6: the football zombie arrives; the answer is the hypno-shroom from 2-5
# ---------------------------------------------------------------------------
LEVEL_2_6 = base(
    "pvzce:yard/adventure/2_6", "2-6",
    "夜里的第六关：橄榄球僵尸第一次上场——它比普通僵尸快将近一倍，脸上那副面罩还能扛住一千四百点伤害。"
    "刚拿到的魅惑菇是这一关最好的答案：让它替你撞开后面的一整排。",
    requires="pvzce:yard/adventure/2_5",
    first_clear=unlock("pvzce:scaredy_shroom"),
    mechanics=[grave_field(7)],
    waves=[
        wave("small", 1500, [("basic_zombie", 3)], 420),
        wave("small", 1500, [("basic_zombie", 3), ("conehead_zombie", 1)], 390),
        wave("small", 1500, [("basic_zombie", 4), ("conehead_zombie", 1), ("football_zombie", 1)], 360),
        wave("huge", 1860, [("flag_zombie", 1), ("basic_zombie", 4), ("conehead_zombie", 2),
                            ("football_zombie", 1)], 180, warning=180),
        wave("small", 1500, [("basic_zombie", 4), ("conehead_zombie", 2), ("football_zombie", 1)], 330),
        wave("final", 1680, [("flag_zombie", 1), ("basic_zombie", 5), ("conehead_zombie", 2),
                             ("football_zombie", 2)], 150, warning=180),
    ],
    dialogue=[
        line("charmed", "♡♡"),
        line("charmed", "唔♡~唔诶~"),
        line("startled", "诶！？等，等一下！", shake=True),
        line("panic", "我...我刚才是不是有点不对劲"),
        line("scared", "你...你没看见对不对"),
        line("angry", "可恶的魅惑菇"),
        line("angry", "略略略到时候给你也吃一颗魅惑菇，哼~"),
        line("startled", "唔等一下...", shake=True),
        line("startled", "好...好像有厉害的僵尸过来了..."),
        line("gentle", "能不能..."),
        line("gentle", "帮忙打败它..."),
        line("flighty", "求你啦~", side="center"),
    ])

# ---------------------------------------------------------------------------
# 2-7: the most graves so far; the scaredy-shroom is the new toy
# ---------------------------------------------------------------------------
LEVEL_2_7 = base(
    "pvzce:yard/adventure/2_7", "2-7",
    "夜里的第七关：草坪上站着十一个墓碑，是夜晚最多的一关——清不掉的墓碑会在最后一波一起开门。"
    "铁门僵尸和橄榄球僵尸同时上场。刚拿到的胆小菇射得远，可僵尸一靠近就会缩回去，把它种在后排。",
    requires="pvzce:yard/adventure/2_6",
    first_clear=unlock("pvzce:ice_shroom"),
    mechanics=[grave_field(11)],
    waves=[
        wave("small", 1500, [("basic_zombie", 4)], 420),
        wave("small", 1500, [("basic_zombie", 4), ("conehead_zombie", 2)], 390),
        wave("small", 1500, [("basic_zombie", 4), ("conehead_zombie", 2), ("door_zombie", 1)], 360),
        wave("huge", 1860, [("flag_zombie", 1), ("basic_zombie", 5), ("conehead_zombie", 2),
                            ("door_zombie", 2), ("football_zombie", 1)], 180, warning=180),
        wave("small", 1500, [("basic_zombie", 4), ("conehead_zombie", 2), ("door_zombie", 1),
                             ("football_zombie", 1)], 330),
        wave("small", 1500, [("basic_zombie", 4), ("conehead_zombie", 3), ("door_zombie", 2),
                             ("football_zombie", 1)], 300),
        wave("final", 1680, [("flag_zombie", 1), ("basic_zombie", 6), ("conehead_zombie", 3),
                             ("door_zombie", 3), ("football_zombie", 2)], 140, warning=180),
    ],
    dialogue=[
        line("gentle", "是胆小菇诶"),
        line("depressed", ""),
        line("depressed", "唔..."),
        line("smile", "唔唔！？"),
        line("smile", "没...没什么啦..."),
        line("care", "只是感觉胆小菇姐姐有点可怜呢"),
        line("care", "明明很害怕，还要出来打僵尸..."),
        line("depressed", "嗯..."),
        line("forced_smile", ""),
    ])

# ---------------------------------------------------------------------------
# 2-8: the dancing zombie summons its crew; the ice-shroom freezes the party
# ---------------------------------------------------------------------------
LEVEL_2_8 = base(
    "pvzce:yard/adventure/2_8", "2-8",
    "夜里的第八关：舞王僵尸第一次登场。他会停下来招出四名伴舞僵尸，倒下一个还会再补一个——"
    "把整支舞队一起清掉才算完。上一关拿到的寒冰菇正好能把这场舞会冻住。",
    requires="pvzce:yard/adventure/2_7",
    first_clear=unlock("pvzce:doom_shroom"),
    mechanics=[grave_field(7)],
    waves=[
        wave("small", 1500, [("basic_zombie", 4)], 420),
        wave("small", 1500, [("basic_zombie", 4), ("conehead_zombie", 2)], 390),
        wave("small", 1500, [("basic_zombie", 4), ("conehead_zombie", 2), ("dancing_zombie", 1)], 360),
        wave("huge", 1860, [("flag_zombie", 1), ("basic_zombie", 5), ("conehead_zombie", 2),
                            ("dancing_zombie", 1)], 180, warning=180),
        wave("small", 1500, [("basic_zombie", 5), ("conehead_zombie", 2), ("dancing_zombie", 1)], 330),
        wave("final", 1680, [("flag_zombie", 1), ("basic_zombie", 6), ("conehead_zombie", 3),
                             ("dancing_zombie", 2)], 140, warning=180),
    ],
    dialogue=[
        line("cold", "唔，好冷好冷"),
        line("cold", "是寒冰菇大人吗？"),
        line("cold", "感觉咱快被冻住了"),
        line("gentle", "寒冰菇大人也来了吗？"),
        line("star", "那...我们蘑菇家族就要齐了诶"),
        line("depressed", "太...太好了..."),
        line("smile", "那就开始战斗吧！打败僵尸！"),
    ])

# ---------------------------------------------------------------------------
# 2-9: the note. Two flags, eleven graves, doors and dancers
# ---------------------------------------------------------------------------
LEVEL_2_9 = base(
    "pvzce:yard/adventure/2_9", "2-9",
    "夜里的第九关：铁门僵尸和舞王僵尸一起上，墓碑还是十一个。刚拿到的毁灭菇能一口气清掉一大片，"
    "但别把它种得太靠左——它会在地上留一个弹坑。打完这一关，僵尸们会送来一张便条。",
    requires="pvzce:yard/adventure/2_8",
    first_clear=None,
    mechanics=[grave_field(11)],
    waves=[
        wave("small", 1500, [("basic_zombie", 4)], 420),
        wave("small", 1500, [("basic_zombie", 4), ("conehead_zombie", 2)], 390),
        wave("small", 1500, [("basic_zombie", 4), ("conehead_zombie", 2), ("door_zombie", 1)], 360),
        wave("huge", 1860, [("flag_zombie", 1), ("basic_zombie", 5), ("conehead_zombie", 2),
                            ("door_zombie", 2), ("dancing_zombie", 1)], 180, warning=180),
        wave("small", 1500, [("basic_zombie", 5), ("conehead_zombie", 3), ("door_zombie", 1),
                             ("dancing_zombie", 1)], 300),
        wave("huge", 1860, [("flag_zombie", 1), ("basic_zombie", 6), ("conehead_zombie", 3),
                            ("door_zombie", 2), ("dancing_zombie", 1)], 180, warning=180),
        wave("final", 1680, [("flag_zombie", 1), ("basic_zombie", 7), ("conehead_zombie", 3),
                             ("door_zombie", 3), ("dancing_zombie", 2)], 140, warning=180),
    ],
    dialogue=[
        line("smile", "又来了一位新成员嘛？"),
        line("startled", "等等！这位是？", shake=True),
        line("startled", "毁—灭—菇—大—人"),
        line("star", "他...他是最...最厉害的蘑菇！"),
        line("star", "超...超级厉害！"),
        line("star", "轰的一下子，一大片僵尸都消失了"),
        line("wink", "有他的帮助，打僵尸会很容易呢"),
    ])

# ---------------------------------------------------------------------------
# 2-10: the Night finale, a conveyor belt over thirteen graves
# ---------------------------------------------------------------------------
CONVEYOR_CARDS = [
    ("puff_shroom", 10, None),
    ("fume_shroom", 15, None),
    ("grave_buster", 20, 13),
    ("hypno_shroom", 10, None),
    ("scaredy_shroom", 15, None),
    ("ice_shroom", 15, None),
    ("doom_shroom", 15, None),
]

LEVEL_2_10 = base(
    "pvzce:yard/adventure/2_10", "2-10",
    "夜里的最后一关：植物由传送带免费送来，草坪上密密麻麻站着十三个墓碑——"
    "墓碑破坏者一共只发十三张，正好够清完。橄榄球僵尸和舞王僵尸都会来，撑过两大波，夜晚就结束了。",
    requires="pvzce:yard/adventure/2_9",
    first_clear=unlock("pvzce:lily_pad"),
    initial_sun=0,
    unlock_sun=False,
    # The finale's theme, the same one 1-10 (the Day conveyor) plays.
    music="pvzce:music/ultimate_battle",
    mechanics=[
        {
            "type": "pvzce:conveyor",
            "interval_ticks": 240,
            "capacity": 6,
            "initial_cards": 2,
            "cards": [
                {"id": f"pvzce:{card}", "weight": weight}
                if limit is None else {"id": f"pvzce:{card}", "weight": weight, "max_count": limit}
                for card, weight, limit in CONVEYOR_CARDS
            ],
        },
        grave_field(13),
    ],
    waves=[
        wave("small", 1500, [("basic_zombie", 4)], 420),
        wave("small", 1500, [("basic_zombie", 4), ("conehead_zombie", 2)], 390),
        wave("small", 1500, [("basic_zombie", 4), ("conehead_zombie", 2), ("door_zombie", 1)], 360),
        wave("huge", 1860, [("flag_zombie", 1), ("basic_zombie", 5), ("conehead_zombie", 2),
                            ("door_zombie", 2), ("football_zombie", 1)], 180, warning=180),
        wave("small", 1500, [("basic_zombie", 5), ("conehead_zombie", 2), ("football_zombie", 1),
                             ("dancing_zombie", 1)], 330),
        wave("small", 1500, [("basic_zombie", 5), ("conehead_zombie", 3), ("door_zombie", 2),
                             ("football_zombie", 1)], 300),
        wave("huge", 1860, [("flag_zombie", 1), ("basic_zombie", 6), ("conehead_zombie", 3),
                            ("door_zombie", 3), ("football_zombie", 2), ("dancing_zombie", 1)],
             180, warning=180),
        wave("final", 1680, [("flag_zombie", 1), ("basic_zombie", 7), ("conehead_zombie", 4),
                             ("door_zombie", 3), ("football_zombie", 2), ("dancing_zombie", 2)],
             140, warning=180),
    ],
    dialogue=[
        line("angry", "可恶的僵尸们...又拖家带口的来了"),
        line("angry", "一来一大片！"),
        line("confident", "哼，咱们不会输掉的"),
        line("confused", "虽然还有几位蘑菇没有来"),
        line("playful", "没关系的，也许下一个夜晚就会遇到了呢？"),
        line("playful", "哼哼~"),
    ])


def write(level: dict) -> None:
    path = LEVELS / (level["id"].rsplit("/", 1)[1] + ".json")
    path.write_text(json.dumps(level, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {path.relative_to(REPO_ROOT)}")


def restructure_existing() -> None:
    """2-1 and 2-4 move their hand-written graves into the random field.

    The original scatters them over the far half of the lawn, so the file cannot hold the
    cells any more; the count is what it wrote down, and it is the only thing that survives.
    The cells the graves were standing on go back to grass - a board with a hole in it is a
    hole the renderer draws the backdrop through and the server refuses to plant on, and the
    gravel that used to cover it is now laid out by the mechanic.
    """
    for name, count in (("2_1", 4), ("2_4", 7)):
        path = LEVELS / f"{name}.json"
        level = json.loads(path.read_text(encoding="utf-8"))
        scene = level["scene"]
        kept = {element: cells for element, cells in scene.items()
                if not element.startswith("pvzce:grave")}
        grass = [cell for cell in kept.get("pvzce:grass", [])]
        painted = {cell for cells in kept.values() for cell in cells}
        for x in range(level.get("width", WIDTH)):
            for y in range(level.get("height", HEIGHT)):
                cell = f"{x},{y}"
                if cell not in painted:
                    grass.append(cell)
        # The level's own order is column-within-row; the appended cells are sorted the same
        # way so a diff reads as "these cells came back", not as a reshuffled board.
        grass.sort(key=lambda cell: (int(cell.split(",")[1]), int(cell.split(",")[0])))
        level["scene"] = kept
        level["scene"]["pvzce:grass"] = grass
        mechanics = level.setdefault("mechanics", [])
        mechanics.insert(0, grave_field(count))
        path.write_text(json.dumps(level, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"updated {path.relative_to(REPO_ROOT)}: {count} graves are now scattered")


def main() -> None:
    for level in (LEVEL_2_6, LEVEL_2_7, LEVEL_2_8, LEVEL_2_9, LEVEL_2_10):
        write(level)
    restructure_existing()


if __name__ == "__main__":
    main()
