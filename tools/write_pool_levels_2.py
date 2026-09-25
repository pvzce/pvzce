#!/usr/bin/env python3
"""Writes 3-6 to 3-10, the second half of the pool.

The companion to `write_pool_levels.py`, which wrote 3-1..3-5: the same board (nine columns,
six rows, water in the middle two), the same mower rig, the same rules block, and a wave table
whose pacing tightens level by level. Kept as a script rather than five hand-written files
because the five differ only in their wave tables and their one line of reward - a diff between
two of them is the whole content.

Run from the repository root:

    python3 tools/write_pool_levels_2.py
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Dict, List, Optional

REPO_ROOT = Path(__file__).resolve().parents[1]
LEVELS = (REPO_ROOT / "pvzce-game/src/main/resources/data/pvzce/levels/yard/adventure")

WIDTH, HEIGHT = 9, 6
LAND_ROWS = [0, 1, 4, 5]
WATER_ROWS = [2, 3]

BACKGROUND = "pvzce:textures/gui/screen/level/background3"


def scene() -> Dict[str, List[str]]:
    grass, water = [], []
    for y in range(HEIGHT):
        for x in range(WIDTH):
            (water if y in WATER_ROWS else grass).append(f"{x},{y}")
    return {"pvzce:grass": grass, "pvzce:water": water}


def mower() -> Dict[str, object]:
    return {
        "type": "pvzce:mower",
        "rows": LAND_ROWS,
        "kinds": [
            {"row": row, "kind": "pvzce:pool_cleaner",
             "sound": "pvzce:sfx/ambient/pool_cleaner"}
            for row in WATER_ROWS
        ],
    }


def entry(zombie: str, count: int, rows: List[int]) -> Dict[str, object]:
    return {"id": f"pvzce:{zombie}", "count": count, "rows": rows}


def wave(kind: str, delay: int, interval: int, entries: List[Dict[str, object]],
         warning: Optional[int] = None) -> Dict[str, object]:
    body: Dict[str, object] = {
        "type": kind, "delay": delay, "entries": entries, "spawn_interval": interval}
    if warning is not None:
        body["warning_ticks"] = warning
    return body


def land(*pairs) -> List[Dict[str, object]]:
    """Entries for the four grass rows."""
    return [entry(zombie, count, LAND_ROWS) for zombie, count in pairs]


def water(*pairs) -> List[Dict[str, object]]:
    """Entries for the two water rows."""
    return [entry(zombie, count, WATER_ROWS) for zombie, count in pairs]


def level(index: int, name: str, description: str, waves: List[Dict[str, object]],
          music: str, reward: Dict[str, str], hints: List[Dict[str, object]],
          initial_sun: int = 50, buffs: Optional[List[str]] = None,
          coins: int = 300) -> Dict[str, object]:
    body: Dict[str, object] = {
        "id": f"pvzce:yard/adventure/3_{index}",
        "name": name,
        "description": description,
        "width": WIDTH,
        "height": HEIGHT,
        "scene": scene(),
        "teams": [
            {"id": "pvzce:plant_team", "name": "植物方", "win_condition": "survive_waves"},
            {"id": "pvzce:zombie_team", "name": "僵尸方", "win_condition": "plant_side_lost"},
        ],
        "win_team": "pvzce:plant_team",
        "playable_teams": ["pvzce:plant_team"],
        "rules": {
            "pvzce:day_length": 0,
            "pvzce:night_length": -1,
            "pvzce:sun_spawn_interval_min": 480,
            "pvzce:sun_spawn_interval_max": 720,
            "pvzce:sun_spawn_initial_ticks": 300,
        },
        "env_vars": {},
        "wave_interval_end_multiplier": 1.0,
        "slots": [],
        "unlock_resources": {"pvzce:sun": True},
        "initial_sun": initial_sun,
        "mechanics": [mower()],
        "waves": waves,
        "music": {"cues": [{"at_tick": 0, "track": "background",
                            "event": music, "loop": True, "volume": 0.85,
                            "fade_seconds": 1.0}]},
        "unlock": {"requires": [
            {"type": "level", "id": f"pvzce:yard/adventure/3_{index - 1}"}]},
        "background": BACKGROUND,
        "hidden_scene_elements": ["pvzce:grass"],
        "disable_shaders": False,
        "buffs": buffs if buffs is not None else ["pvzce:player_choice"],
        "hints": hints,
        "rewards": {
            "first_clear": [reward],
            "repeat": [{"type": "coins", "amount": coins}],
            "coin_drop": "pvzce:coin_silver",
            "coin_drop_chance": 0.25,
            "coin_drop_amount": 1,
        },
    }
    return body


# 3-6: the spikeweed, and the first vehicle. The zamboni is what the reward answers - a plant
# that hurts what walks over it is a strange thing to be given until a machine that drives over
# plants shows up, and the level is built so the player meets it in the same run.
THREE_SIX = level(
    6, "3-6 地刺上阵",
    "水池第六关。这一关会开过来一辆雪橇车：它不咬植物，它直接碾过去，并且把压过的地面冻成一层"
    "种不下东西的冰。地刺是它的反面——一样是「在地面上」，可地刺是长在草里、僵尸绕着走的那个。",
    [
        wave("small", 1200, 300, land(("basic_zombie", 6), ("conehead_zombie", 1))
             + water(("ducky_tube_zombie", 3))),
        wave("small", 1200, 280, land(("basic_zombie", 7), ("conehead_zombie", 2))
             + water(("ducky_tube_zombie", 3), ("ducky_tube_conehead_zombie", 1))),
        wave("small", 1400, 260, land(("basic_zombie", 7), ("conehead_zombie", 2),
                                      ("zamboni_zombie", 1))
             + water(("ducky_tube_zombie", 4), ("snorkel_zombie", 1))),
        wave("huge", 1700, 200, land(("flag_zombie", 1), ("basic_zombie", 10),
                                     ("conehead_zombie", 3), ("zamboni_zombie", 1))
             + water(("ducky_tube_zombie", 5), ("snorkel_zombie", 2)), warning=180),
        wave("small", 1400, 200, land(("basic_zombie", 9), ("buckethead_zombie", 1),
                                      ("zamboni_zombie", 1))
             + water(("ducky_tube_conehead_zombie", 3), ("snorkel_zombie", 2))),
        wave("small", 1400, 180, land(("basic_zombie", 10), ("conehead_zombie", 3),
                                      ("pole_vaulter_zombie", 1))
             + water(("ducky_tube_zombie", 6), ("snorkel_zombie", 2))),
        wave("final", 1600, 150, land(("flag_zombie", 1), ("basic_zombie", 12),
                                      ("conehead_zombie", 4), ("buckethead_zombie", 2),
                                      ("zamboni_zombie", 1))
             + water(("ducky_tube_zombie", 7), ("ducky_tube_buckethead_zombie", 1),
                     ("snorkel_zombie", 3)), warning=180),
    ],
    "pvzce:music/watery_graves",
    {"type": "unlock", "id": "pvzce:spikeweed"},
    [
        {"trigger": "on_start", "text": "雪橇车会碾过植物，被它压过的地面结冰，不能再种东西",
         "duration_ticks": 600},
        {"trigger": "on_start", "text": "地刺种在草里，僵尸会从它身上走过并一直受伤",
         "duration_ticks": 600},
        {"trigger": "on_card_refused"},
    ],
)

# 3-7: fire. The torchwood doubles what passes through it, and the level is a wall of ordinary
# shooters to put behind it - which is the only way the plant reads as worth 175 sun.
THREE_SEVEN = level(
    7, "3-7 火炬与气球",
    "水里的两种僵尸这一关都来齐了：潜水的要等它冒头，海豚骑士会直接跳过你的第一道防线。"
    "草地这边，火炬树桩能把穿过它的豌豆点着，伤害翻倍——一排射手后面站一个，比多种两株划算。",
    [
        wave("small", 1200, 280, land(("basic_zombie", 7), ("conehead_zombie", 1))
             + water(("ducky_tube_zombie", 4), ("snorkel_zombie", 1))),
        wave("small", 1200, 260, land(("basic_zombie", 7), ("newspaper_zombie", 1))
             + water(("snorkel_zombie", 3), ("ducky_tube_conehead_zombie", 1))),
        wave("small", 1400, 240, land(("basic_zombie", 8), ("conehead_zombie", 2))
             + water(("dolphin_rider_zombie", 2), ("snorkel_zombie", 2))),
        wave("huge", 1700, 190, land(("flag_zombie", 1), ("basic_zombie", 10),
                                     ("buckethead_zombie", 2))
             + water(("dolphin_rider_zombie", 3), ("snorkel_zombie", 3)), warning=180),
        wave("small", 1400, 190, land(("basic_zombie", 9), ("conehead_zombie", 3),
                                      ("football_zombie", 1))
             + water(("snorkel_zombie", 3), ("ducky_tube_buckethead_zombie", 1))),
        wave("small", 1400, 180, land(("basic_zombie", 10), ("pole_vaulter_zombie", 2))
             + water(("dolphin_rider_zombie", 3), ("snorkel_zombie", 3))),
        wave("small", 1400, 170, land(("basic_zombie", 11), ("conehead_zombie", 3),
                                      ("buckethead_zombie", 2))
             + water(("dolphin_rider_zombie", 3), ("snorkel_zombie", 2))),
        wave("final", 1600, 150, land(("flag_zombie", 1), ("basic_zombie", 13),
                                      ("conehead_zombie", 4), ("buckethead_zombie", 2),
                                      ("football_zombie", 1))
             + water(("dolphin_rider_zombie", 4), ("snorkel_zombie", 4),
                     ("ducky_tube_buckethead_zombie", 1)), warning=180),
    ],
    "pvzce:music/watery_graves",
    {"type": "unlock", "id": "pvzce:torchwood"},
    [
        {"trigger": "on_start", "text": "潜水僵尸在水下打不到，等它冒头", "duration_ticks": 600},
        {"trigger": "on_start", "text": "穿过火炬树桩的豌豆会变成火豌豆，伤害翻倍",
         "duration_ticks": 600},
        {"trigger": "on_card_refused"},
    ],
)

# 3-8: the tall-nut. The pole vaulter has been clearing the first plant since 1-9, and this is
# the level that hands out the answer - so the wave table leans on vaulters hard.
THREE_EIGHT = level(
    8, "3-8 高墙",
    "撑杆僵尸从 1-9 起就是「第一株植物白种了」的同义词。高坚果是它的答案：跳不过去，只能站在"
    "下面啃。这一关的撑杆僵尸比哪一关都多，海豚骑士也一样。",
    [
        wave("small", 1200, 270, land(("basic_zombie", 8), ("pole_vaulter_zombie", 3))
             + water(("ducky_tube_zombie", 3), ("dolphin_rider_zombie", 1))),
        wave("small", 1200, 250, land(("basic_zombie", 8), ("conehead_zombie", 2),
                                      ("pole_vaulter_zombie", 3))
             + water(("snorkel_zombie", 3), ("dolphin_rider_zombie", 2))),
        wave("small", 1400, 230, land(("basic_zombie", 9), ("pole_vaulter_zombie", 4),
                                      ("newspaper_zombie", 1))
             + water(("dolphin_rider_zombie", 3), ("snorkel_zombie", 2))),
        wave("huge", 1700, 190, land(("flag_zombie", 1), ("basic_zombie", 11),
                                     ("pole_vaulter_zombie", 4), ("buckethead_zombie", 2))
             + water(("dolphin_rider_zombie", 4), ("snorkel_zombie", 3)), warning=180),
        wave("small", 1400, 190, land(("basic_zombie", 10), ("pole_vaulter_zombie", 4),
                                      ("football_zombie", 1))
             + water(("dolphin_rider_zombie", 3), ("snorkel_zombie", 3),
                     ("ducky_tube_conehead_zombie", 1))),
        wave("small", 1400, 180, land(("basic_zombie", 11), ("conehead_zombie", 3),
                                      ("pole_vaulter_zombie", 5))
             + water(("dolphin_rider_zombie", 4), ("snorkel_zombie", 3))),
        wave("small", 1400, 170, land(("basic_zombie", 12), ("buckethead_zombie", 2),
                                      ("pole_vaulter_zombie", 5))
             + water(("dolphin_rider_zombie", 4), ("snorkel_zombie", 4))),
        wave("huge", 1700, 160, land(("flag_zombie", 1), ("basic_zombie", 13),
                                     ("conehead_zombie", 4), ("pole_vaulter_zombie", 5),
                                     ("football_zombie", 2))
             + water(("dolphin_rider_zombie", 5), ("snorkel_zombie", 4),
                     ("ducky_tube_buckethead_zombie", 1)), warning=180),
        wave("final", 1600, 150, land(("flag_zombie", 1), ("basic_zombie", 15),
                                      ("conehead_zombie", 4), ("buckethead_zombie", 3),
                                      ("pole_vaulter_zombie", 6), ("football_zombie", 2))
             + water(("dolphin_rider_zombie", 5), ("snorkel_zombie", 5),
                     ("ducky_tube_buckethead_zombie", 1)), warning=180),
    ],
    "pvzce:music/watery_graves",
    {"type": "unlock", "id": "pvzce:tall_nut"},
    [
        {"trigger": "on_start", "text": "撑杆僵尸跳不过高坚果，但普通坚果墙挡不住它",
         "duration_ticks": 600},
        {"trigger": "on_card_refused"},
    ],
)

# 3-9: the reward level. No new plant - the prize is the buff that makes the pool answerable,
# and the level is a long water-heavy run that shows what it is for.
THREE_NINE = level(
    9, "3-9 水草蔓延",
    "水池的第九关，这一段的奖励关：打完它，缠绕水草会开始自己长。种下一株之后，只要旁边或上下"
    "还有水池、而且那一格没有别的植物，它就会再长一株——两行水从此不用一格一格地铺。",
    [
        *[wave("small", 1200, 250,
               land(("basic_zombie", 7 + i), ("conehead_zombie", 1 + i // 3))
               + water(("ducky_tube_zombie", 5 + i), ("snorkel_zombie", 2)))
          for i in range(0, 3)],
        wave("huge", 1700, 190, land(("flag_zombie", 1), ("basic_zombie", 12),
                                     ("buckethead_zombie", 2), ("pole_vaulter_zombie", 2))
             + water(("ducky_tube_zombie", 8), ("dolphin_rider_zombie", 2),
                     ("snorkel_zombie", 3)), warning=180),
        *[wave("small", 1400, 180,
               land(("basic_zombie", 11 + i), ("conehead_zombie", 3),
                    ("football_zombie", i + 1))
               + water(("ducky_tube_conehead_zombie", 4 + i), ("snorkel_zombie", 3 + i)))
          for i in range(0, 3)],
        wave("small", 1400, 160, land(("basic_zombie", 15), ("buckethead_zombie", 3),
                                      ("pole_vaulter_zombie", 3))
             + water(("ducky_tube_buckethead_zombie", 3), ("dolphin_rider_zombie", 4),
                     ("snorkel_zombie", 4))),
        wave("huge", 1700, 150, land(("flag_zombie", 1), ("basic_zombie", 16),
                                     ("conehead_zombie", 5), ("football_zombie", 2))
             + water(("ducky_tube_zombie", 9), ("dolphin_rider_zombie", 4),
                     ("snorkel_zombie", 5)), warning=180),
        wave("small", 1400, 150, land(("basic_zombie", 16), ("buckethead_zombie", 3),
                                      ("pole_vaulter_zombie", 4))
             + water(("ducky_tube_conehead_zombie", 6), ("snorkel_zombie", 5))),
        wave("final", 1600, 140, land(("flag_zombie", 1), ("basic_zombie", 18),
                                      ("conehead_zombie", 6), ("buckethead_zombie", 4),
                                      ("football_zombie", 2), ("pole_vaulter_zombie", 4))
             + water(("ducky_tube_zombie", 11), ("ducky_tube_buckethead_zombie", 2),
                     ("dolphin_rider_zombie", 5), ("snorkel_zombie", 6)), warning=180),
    ],
    "pvzce:music/loon_boon",
    {"type": "buff", "id": "pvzce:kelp_spread"},
    [
        {"trigger": "on_start", "text": "水草很多，传送带之外的两行水也要守住", "duration_ticks": 600},
        {"trigger": "on_card_refused"},
    ],
)

# 3-10: the finale. The Gargantuar arrives, and the plantern is the answer to the dark the next
# world is made of.
THREE_TEN = level(
    10, "3-10 池畔终章",
    "水池的最后一关。巨人僵尸第一次走上草坪：一锤一株植物，血量高得离谱，只有灰烬系的爆炸和"
    "小推车拦得住它。打完这一关，你会拿到路灯花——下一片草坪是黑的，它是那里的第一盏灯。",
    [
        wave("small", 1200, 250, land(("basic_zombie", 8), ("conehead_zombie", 2))
             + water(("ducky_tube_zombie", 4), ("snorkel_zombie", 1))),
        wave("small", 1200, 230, land(("basic_zombie", 9), ("pole_vaulter_zombie", 2))
             + water(("snorkel_zombie", 3), ("dolphin_rider_zombie", 2))),
        wave("small", 1400, 220, land(("basic_zombie", 10), ("buckethead_zombie", 2))
             + water(("ducky_tube_conehead_zombie", 2), ("snorkel_zombie", 3))),
        wave("huge", 1700, 190, land(("flag_zombie", 1), ("basic_zombie", 12),
                                     ("conehead_zombie", 3), ("gargantuar", 1))
             + water(("dolphin_rider_zombie", 3), ("snorkel_zombie", 3)), warning=180),
        wave("small", 1400, 190, land(("basic_zombie", 12), ("football_zombie", 2))
             + water(("ducky_tube_buckethead_zombie", 2), ("snorkel_zombie", 4))),
        wave("small", 1400, 180, land(("basic_zombie", 13), ("pole_vaulter_zombie", 3),
                                      ("buckethead_zombie", 3))
             + water(("dolphin_rider_zombie", 4), ("snorkel_zombie", 4))),
        wave("huge", 1700, 170, land(("flag_zombie", 1), ("basic_zombie", 14),
                                     ("gargantuar", 1), ("conehead_zombie", 4))
             + water(("ducky_tube_zombie", 8), ("dolphin_rider_zombie", 3),
                     ("snorkel_zombie", 4)), warning=180),
        wave("small", 1400, 170, land(("basic_zombie", 15), ("football_zombie", 2),
                                      ("pole_vaulter_zombie", 3))
             + water(("ducky_tube_conehead_zombie", 5), ("snorkel_zombie", 5))),
        wave("small", 1400, 160, land(("basic_zombie", 16), ("buckethead_zombie", 4),
                                      ("newspaper_zombie", 2))
             + water(("ducky_tube_buckethead_zombie", 3), ("dolphin_rider_zombie", 4),
                     ("snorkel_zombie", 5))),
        wave("huge", 1700, 150, land(("flag_zombie", 1), ("basic_zombie", 17),
                                     ("gargantuar", 2), ("football_zombie", 2))
             + water(("ducky_tube_zombie", 10), ("dolphin_rider_zombie", 4),
                     ("snorkel_zombie", 5)), warning=180),
        wave("small", 1400, 150, land(("basic_zombie", 18), ("conehead_zombie", 5),
                                      ("pole_vaulter_zombie", 4))
             + water(("ducky_tube_buckethead_zombie", 3), ("snorkel_zombie", 6))),
        wave("final", 1600, 140, land(("flag_zombie", 1), ("basic_zombie", 20),
                                      ("conehead_zombie", 6), ("buckethead_zombie", 4),
                                      ("gargantuar", 2), ("football_zombie", 3),
                                      ("pole_vaulter_zombie", 4))
             + water(("ducky_tube_zombie", 12), ("ducky_tube_buckethead_zombie", 3),
                     ("dolphin_rider_zombie", 5), ("snorkel_zombie", 7)), warning=180),
    ],
    "pvzce:music/ultimate_battle",
    {"type": "unlock", "id": "pvzce:plantern"},
    [
        {"trigger": "on_start", "text": "巨人僵尸一锤一株植物，用小推车和爆炸对付它",
         "duration_ticks": 600},
        {"trigger": "on_card_refused"},
    ],
    initial_sun=0,
)


def main() -> None:
    for body in (THREE_SIX, THREE_SEVEN, THREE_EIGHT, THREE_NINE, THREE_TEN):
        name = body["id"].split("/")[-1]
        path = LEVELS / f"{name}.json"
        path.write_text(json.dumps(body, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"wrote {path} ({len(body['waves'])} waves)")


if __name__ == "__main__":
    main()
