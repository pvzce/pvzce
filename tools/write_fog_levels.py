#!/usr/bin/env python3
"""Writes 4-1 to 4-10: the fog.

World 4 is the pool after dark with the right-hand side of the board hidden - the same nine by
six board as world 3, the same mower rig, and one extra mechanic: `pvzce:fog`. The fog's boundary
walks right to left across the ten levels, which is the whole of the difficulty curve beyond the
wave tables, and it is one number per level.

Three things this script exists to keep in one place:

* **the fog ramp.** 4-1 starts it at column 5.5 and 4-9 - the level that hands out the buff that
  shortens it - starts at 2.5. A level whose fog was written by hand would be a level where the
  curve is not visible anywhere.
* **the rewards.** One per level, and three of them are the ones the user named: 4-4's vase tool
  and 4-9's retreat buff, with 4-1/2/3/5/6/7/8/10 handing out the cards the world is about.
* **the wave entries' lanes.** Every entry names its rows, because a pool board is exactly where
  an unnamed lane drops a walker into the water (see `PoolSecondHalfTest`).

Run from the repository root:

    python3 tools/write_fog_levels.py
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
BACKGROUND = "pvzce:textures/gui/screen/level/background4"

# One row per level: the fog's start column, the reward, the music, and the wave count.
# `start_column` is where the fog begins - everything to its left is clear - and it is the whole
# difficulty curve of the world: 4-1 hides the last three and a half columns, 4-9 hides six.
PLAN = [
    (1, 5.5, {"type": "unlock", "id": "pvzce:sea_shroom"}, "pvzce:music/watery_graves", 7,
     "4-1 起雾了", "起雾了。右边那一片看不清，僵尸要走到亮处才现形——海蘑菇是这一关的礼物，"
     "它不要阳光，只能种在水里。"),
    (2, 5.0, {"type": "unlock", "id": "pvzce:blover"}, "pvzce:music/watery_graves", 8,
     "4-2 空中来客", "气球僵尸从雾里飘出来，地面上的植物够不到它。三叶草能吹一口气，"
     "把天上所有的东西都吹走。"),
    (3, 4.5, {"type": "unlock", "id": "pvzce:cactus"}, "pvzce:music/watery_graves", 8,
     "4-3 仙人掌", "仙人掌的刺能打到空中的僵尸，也是这一关唯一能提前拦下气球的东西。"),
    (4, 4.0, {"type": "unlock", "id": "pvzce:vase"}, "pvzce:music/watery_graves", 8,
     "4-4 花瓶", "这一关没有白给的植物：八只花瓶里各塞着一张卡，点一下砸开它，"
     "卡就归你了。打完这一关你会拿到花瓶工具——零阳光、十秒冷却，"
     "可以把植物卡塞进去存着，也可以随时砸开拿回来。"),
    (5, 3.5, {"type": "unlock", "id": "pvzce:starfruit"}, "pvzce:music/watery_graves", 9,
     "4-5 杨桃", "雾更浓了。杨桃五个方向都能打，站在中间就不用管僵尸从哪一边来。"),
    (6, 3.2, {"type": "unlock", "id": "pvzce:pumpkin"}, "pvzce:music/watery_graves", 9,
     "4-6 南瓜壳", "雾里啃食来得比看得见的更突然。南瓜头罩住一株植物，先替你挨咬。"),
    (7, 3.0, {"type": "unlock", "id": "pvzce:magnet_shroom"}, "pvzce:music/watery_graves", 10,
     "4-7 磁力", "铁桶、铁门、橄榄球头盔——雾里走过来的是这些东西。磁力菇能把它们整件拿走。"),
    (8, 2.8, {"type": "unlock", "id": "pvzce:split_pea"}, "pvzce:music/watery_graves", 10,
     "4-8 背后", "矿工僵尸从右边挖地道，从你防线的背后钻出来。分裂豌豆前后都能打。"),
    (9, 2.5, {"type": "buff", "id": "pvzce:fog_retreat"}, "pvzce:music/loon_boon", 11,
     "4-9 迷雾退缩", "雾最浓的一关，也是这一段的奖励关：打完它，雾会退后一列半。"),
    (10, 3.0, {"type": "unlock", "id": "pvzce:coffee_bean"}, "pvzce:music/ultimate_battle", 12,
     "4-10 雾散", "巨人僵尸又来了，这次是从雾里。咖啡豆是最后一株植物——"
     "把它种在别的植物上，能把睡着的蘑菇叫醒。"),
]


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


VASE_CARDS = [
    (1, 0, "sunflower"), (2, 0, "pea_shooter"),
    (1, 1, "wall_nut"), (2, 1, "snow_pea"),
    (1, 4, "repeater"), (2, 4, "tall_nut"),
    (1, 5, "cherry_bomb"), (2, 5, "jalapeno"),
]


def vase_field() -> Dict[str, object]:
    """4-4's opening board: the level's plants arrive inside vases.

    <p>Eight of them, in the two columns nearest the house, one card each - the player has 50
    sun and nothing to spend it on until they break one open. Written out rather than rolled:
    a gift that lands somewhere else every attempt is a dice roll, not a difficulty curve.
    """
    return {
        "type": "pvzce:vase_field",
        "vases": [{"x": x, "y": y, "card": f"pvzce:{card}"} for x, y, card in VASE_CARDS],
    }


def entry(zombie: str, count: int, rows: List[int]) -> Dict[str, object]:
    return {"id": f"pvzce:{zombie}", "count": count, "rows": rows}


def land(*pairs) -> List[Dict[str, object]]:
    return [entry(zombie, count, LAND_ROWS) for zombie, count in pairs]


def water(*pairs) -> List[Dict[str, object]]:
    return [entry(zombie, count, WATER_ROWS) for zombie, count in pairs]


def wave(kind: str, delay: int, interval: int, entries: List[Dict[str, object]],
         warning: Optional[int] = None) -> Dict[str, object]:
    body: Dict[str, object] = {
        "type": kind, "delay": delay, "entries": entries, "spawn_interval": interval}
    if warning is not None:
        body["warning_ticks"] = warning
    return body


def waves_for(index: int, count: int) -> List[Dict[str, object]]:
    """The wave table for one level.

    <p>Built from the level's number rather than written out ten times: the shape is the same and
    what changes is how many zombies and how often, which is exactly the curve a reader wants to
    see in one place. The last wave is `final`, the ones before it alternate `huge` and `small`,
    and the pile grows with the index.
    """
    scale = index - 1
    tables: List[Dict[str, object]] = []
    for step in range(count):
        first, last = step == 0, step == count - 1
        delay = max(1150, 1500 - step * 25)
        interval = max(130, 300 - step * 14)
        basic = 6 + scale * 2 + step
        cone = 1 + scale // 2 + step // 3
        bucket = scale // 3 + step // 4
        floatie = 3 + scale + step // 2
        diver = 1 + scale // 2 + step // 4
        if first:
            entries = (land(("basic_zombie", basic), ("conehead_zombie", cone))
                       + water(("ducky_tube_zombie", floatie)))
            tables.append(wave("small", 1200, 300, entries))
            continue
        if last:
            entries = (land(("flag_zombie", 1), ("basic_zombie", basic + 6),
                            ("conehead_zombie", cone + 3), ("buckethead_zombie", bucket + 2),
                            ("football_zombie", 1 + scale // 3))
                       + water(("ducky_tube_zombie", floatie + 4),
                               ("ducky_tube_buckethead_zombie", 1 + scale // 3),
                               ("snorkel_zombie", diver + 2)))
            if index >= 5:
                entries = entries + land(("balloon_zombie", 1 + scale // 3))
            if index >= 8:
                entries = entries + land(("miner_zombie", 1 + scale // 4))
            if index >= 9:
                entries = entries + land(("gargantuar", 1 + scale // 5))
            tables.append(wave("final", 1600, 140, entries, warning=180))
            continue
        huge = step % 3 == 2
        entries = land(("basic_zombie", basic), ("conehead_zombie", cone))
        if bucket > 0:
            entries = entries + land(("buckethead_zombie", bucket))
        entries = entries + water(("ducky_tube_zombie", floatie), ("snorkel_zombie", diver))
        if index >= 2 and step >= 2:
            # The balloon is what the world is about: a flier the lawn cannot reach, arriving out
            # of a fog the player cannot see through.
            entries = entries + land(("balloon_zombie", 1 + scale // 3))
        if index >= 6 and step >= 3:
            entries = entries + land(("football_zombie", 1 + scale // 4),
                                     ("door_zombie", 1 + scale // 5))
        if index >= 7 and step >= 4:
            entries = entries + land(("miner_zombie", 1 + scale // 4))
        if index >= 9 and step >= 5:
            entries = entries + land(("gargantuar", 1))
        if huge:
            entries = [entry("flag_zombie", 1, LAND_ROWS)] + entries
            tables.append(wave("huge", delay + 400, interval - 40, entries, warning=180))
        else:
            tables.append(wave("small", delay, interval, entries))
    return tables


def hints(index: int) -> List[Dict[str, object]]:
    """What the level says, per level.

    <p>Every level gets the fog line - it is the world's one new rule, and the game never
    otherwise explains that the dark half of the lawn is not empty. 4-4 adds the one that
    matters there: its plants are inside the vases, and a player who does not know that has a
    board with nothing to plant on it. Every level ends on the same refusal line, which the
    engine fires with its own text.
    """
    lines = [{"trigger": "on_start",
              "text": "右边一片浓雾，僵尸走到亮处才看得见——那不代表它们不在那里",
              "duration_ticks": 600}]
    if index == 4:
        lines.append({"trigger": "on_start",
                      "text": "草地上那八只花瓶里装着这一关的植物，点一下就把它们砸开",
                      "duration_ticks": 600})
    lines.append({"trigger": "on_card_refused"})
    return lines


def level(index: int, fog: float, reward: Dict[str, str], music: str, wave_count: int,
          name: str, description: str) -> Dict[str, object]:
    return {
        "id": f"pvzce:yard/adventure/4_{index}",
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
        "initial_sun": 50,
        "mechanics": [
            mower(),
            {"type": "pvzce:fog", "start_column": fog, "end_column": 9.0,
             "max_alpha": 0.94},
        ]
        # 4-4 is the level that has to hand its own plants over, because the reward it pays out
        # is the vase tool: a level about vases whose board had none would teach nothing.
        + ([vase_field()] if index == 4 else []),
        "waves": waves_for(index, wave_count),
        "music": {"cues": [{"at_tick": 0, "track": "background", "event": music,
                            "loop": True, "volume": 0.85, "fade_seconds": 1.0}]},
        "unlock": {"requires": [{"type": "level",
                                 "id": f"pvzce:yard/adventure/"
                                       f"{'3_10' if index == 1 else '4_' + str(index - 1)}"}]},
        "background": BACKGROUND,
        "hidden_scene_elements": ["pvzce:grass"],
        "disable_shaders": False,
        "buffs": ["pvzce:player_choice"],
        "hints": hints(index),
        "rewards": {
            "first_clear": [reward],
            "repeat": [{"type": "coins", "amount": 400}],
            "coin_drop": "pvzce:coin_silver",
            "coin_drop_chance": 0.25,
            "coin_drop_amount": 1,
        },
    }


def main() -> None:
    for index, fog, reward, music, count, name, description in PLAN:
        body = level(index, fog, reward, music, count, name, description)
        path = LEVELS / f"4_{index}.json"
        path.write_text(json.dumps(body, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"wrote {path} (fog from {fog}, {count} waves, reward {reward['id']})")


if __name__ == "__main__":
    main()
