#!/usr/bin/env python3
"""Write the Pool area's first five levels (3-1 .. 3-5).

One-off authoring helper, kept out of the shipped tree: the five levels are data, and the
script exists so the board geometry, the lane split and the descriptions are written once
instead of five times. Run from the repository root:

    python3 tools/write_pool_levels.py

The pool is the only board in the game that is not the front lawn: nine columns and SIX
85px lanes, with the middle two (rows 2 and 3) under water. Every wave entry therefore says
which lanes it arrives in - floaties in the water, walkers on the grass - because the
alternative is a land zombie drowning in the pool and a ducky-tube zombie wading across the
lawn.
"""

from __future__ import annotations

import json
from pathlib import Path
from write_adventure_levels import MINI_ATTRIBUTES

REPO_ROOT = Path(__file__).resolve().parents[1]
LEVELS = (REPO_ROOT / "pvzce-game" / "src" / "main" / "resources"
          / "data" / "pvzce" / "levels" / "yard" / "adventure")

WIDTH, HEIGHT = 9, 6
BACKGROUND = "pvzce:textures/gui/screen/level/background3"

# The pool's two water lanes, and the four the lawn keeps.
WATER_ROWS = [2, 3]
GRASS_ROWS = [0, 1, 4, 5]

# The pool cleaner waits on the poolside in the water lanes and the ordinary mower on the
# kerb on the lawn; the original has both, `kinds` is what says which row gets which, and both
# machines share the one anchor half a cell off the left edge of the board.
MOWER = {
    "type": "pvzce:mower",
    "rows": GRASS_ROWS,
    "kinds": [
        {"row": 2, "kind": "pvzce:pool_cleaner", "sound": "pvzce:sfx/ambient/pool_cleaner"},
        {"row": 3, "kind": "pvzce:pool_cleaner", "sound": "pvzce:sfx/ambient/pool_cleaner"},
    ],
}


def scene() -> dict[str, list[str]]:
    """The board: four grass rows and the pool's two, every cell painted."""
    grass = [f"{x},{y}" for y in GRASS_ROWS for x in range(WIDTH)]
    water = [f"{x},{y}" for y in WATER_ROWS for x in range(WIDTH)]
    return {"pvzce:grass": grass, "pvzce:water": water}


def line(text: str, trigger: str = "on_start", resource: str | None = None) -> dict:
    entry: dict = {"trigger": trigger, "text": text, "duration_ticks": 0}
    if resource is not None:
        entry["resource"] = resource
    return entry


def zombie(zid: str, count: int, *, water: bool = False) -> dict:
    """One wave entry, pinned to the lanes it may arrive in."""
    return {"id": f"pvzce:{zid}", "count": count,
            "rows": WATER_ROWS if water else GRASS_ROWS}


def little(zid: str, count: int, *, water: bool = False) -> dict:
    """A normal content id with the canonical 3-5 per-entity attributes."""
    return dict(zombie(zid, count, water=water), attributes=MINI_ATTRIBUTES)


def wave(kind: str, delay: int, entries: list[dict], interval: int,
         warning: int | None = None) -> dict:
    block: dict = {"type": kind, "delay": delay, "entries": entries,
                   "spawn_interval": interval}
    if warning is not None:
        block["warning_ticks"] = warning
    return block


def unlock(card: str) -> list[dict]:
    return [{"type": "unlock", "id": f"pvzce:{card}"}]


def base(level_id: str, name: str, description: str, *, requires: str,
         waves: list[dict], first_clear: list[dict] | None,
         slots: list[str] | None = None, max_seed_slots: int | None = None,
         initial_sun: int = 50, unlock_sun: bool = True,
         music: str = "pvzce:music/watery_graves",
         hints: list[dict] | None = None,
         mechanics: list[dict] | None = None,
         buffs: list[str] | None = None,
         rules_override: dict | None = None) -> dict:
    level: dict = {
        "id": level_id,
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
        # A day pool: the sun falls from the sky like it does on the front lawn, and the
        # water is drawn by the liquid pass, so this is the one area that does NOT turn the
        # shaders off (`disable_shaders` would flatten the pool to a static tile).
        "rules": {
            "pvzce:day_length": 0,
            "pvzce:night_length": -1,
            "pvzce:sun_spawn_interval_min": 480,
            "pvzce:sun_spawn_interval_max": 720,
            "pvzce:sun_spawn_initial_ticks": 300,
        },
        "env_vars": {},
        "wave_interval_end_multiplier": 1.0,
        "slots": slots if slots is not None else [],
        "initial_sun": initial_sun,
        "mechanics": mechanics if mechanics is not None else [dict(MOWER)],
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
        "unlock": {"requires": [{"type": "level", "id": requires}]},
        "background": BACKGROUND,
        "hidden_scene_elements": ["pvzce:grass"],
        "disable_shaders": False,
    }
    if rules_override is not None:
        level["rules"] = rules_override
    if max_seed_slots is not None:
        level["max_seed_slots"] = max_seed_slots
    if unlock_sun:
        level["unlock_resources"] = {"pvzce:sun": True}
    if buffs is not None:
        level["buffs"] = buffs
    if hints:
        level["hints"] = hints
    if first_clear is not None:
        level["rewards"] = {
            "first_clear": first_clear,
            "repeat": [{"type": "coins", "amount": 300}],
            "coin_drop": "pvzce:coin_silver",
            "coin_drop_chance": 0.25,
            "coin_drop_amount": 1,
        }
    return level


# ---------------------------------------------------------------------------
# 3-1: the pool itself. The only new idea is that half the board is water, so
# the level teaches it before it asks anything hard.
# ---------------------------------------------------------------------------
LEVEL_3_1 = base(
    "pvzce:yard/adventure/3_1", "3-1",
    "泳池的第一关：草坪中间多出了两行水，僵尸也会从水里来——它们套着鸭子救生圈，从池子里划过来。"
    "除了莲叶，没有植物能直接种在水上：先把莲叶放进水里，再把射手种在莲叶上。水里的小推车是泳池清洁器，"
    "它和割草机一样是每行的最后一道防线。",
    requires="pvzce:yard/adventure/2_10",
    first_clear=unlock("squash"),
    buffs=["pvzce:player_choice"],
    hints=[
        line("水里要先放莲叶，植物才能种在水上"),
        line("救生圈僵尸只从水里来，看好两行水池", trigger="on_resource",
             resource="pvzce:sun"),
        {"trigger": "on_card_refused"},
    ],
    waves=[
        wave("small", 1500, [zombie("basic_zombie", 2), zombie("ducky_tube_zombie", 2, water=True)], 420),
        wave("small", 1500, [zombie("basic_zombie", 3), zombie("conehead_zombie", 1),
                             zombie("ducky_tube_zombie", 2, water=True)], 390),
        wave("small", 1500, [zombie("basic_zombie", 3), zombie("conehead_zombie", 2),
                             zombie("ducky_tube_zombie", 3, water=True)], 360),
        wave("huge", 1860, [zombie("flag_zombie", 1), zombie("basic_zombie", 4),
                            zombie("conehead_zombie", 2),
                            zombie("ducky_tube_conehead_zombie", 2, water=True)], 180, warning=180),
        wave("small", 1500, [zombie("basic_zombie", 4), zombie("conehead_zombie", 2),
                             zombie("ducky_tube_zombie", 3, water=True)], 330),
        wave("final", 1680, [zombie("flag_zombie", 1), zombie("basic_zombie", 5),
                             zombie("conehead_zombie", 3),
                             zombie("ducky_tube_zombie", 4, water=True),
                             zombie("ducky_tube_conehead_zombie", 2, water=True)], 140, warning=180),
    ])

# ---------------------------------------------------------------------------
# 3-2: the pool starts sending its armoured swimmers, and the lawn its readers.
# ---------------------------------------------------------------------------
LEVEL_3_2 = base(
    "pvzce:yard/adventure/3_2", "3-2",
    "泳池的第二关：铁桶僵尸下了水，报纸僵尸也跟来了。铁桶救生圈僵尸要打掉一千一百点的桶才会开始掉血；"
    "报纸僵尸平时走得慢，报纸一被打掉就提速冲过来，前排放个坚果墙能多拖它一会儿。水里那两行记得留莲叶，"
    "池子里的每一行都有自己的泳池清洁器。",
    requires="pvzce:yard/adventure/3_1",
    first_clear=unlock("threepeater"),
    buffs=["pvzce:player_choice"],
    hints=[{"trigger": "on_card_refused"}],
    waves=[
        wave("small", 1500, [zombie("basic_zombie", 3), zombie("ducky_tube_zombie", 2, water=True)], 420),
        wave("small", 1500, [zombie("basic_zombie", 3), zombie("conehead_zombie", 2),
                             zombie("ducky_tube_zombie", 3, water=True)], 390),
        wave("small", 1500, [zombie("basic_zombie", 4), zombie("conehead_zombie", 2),
                             zombie("newspaper_zombie", 1),
                             zombie("ducky_tube_zombie", 3, water=True)], 360),
        wave("huge", 1860, [zombie("flag_zombie", 1), zombie("basic_zombie", 5),
                            zombie("conehead_zombie", 2),
                            zombie("ducky_tube_conehead_zombie", 2, water=True),
                            zombie("ducky_tube_buckethead_zombie", 1, water=True)], 180, warning=180),
        wave("small", 1500, [zombie("basic_zombie", 4), zombie("conehead_zombie", 3),
                             zombie("newspaper_zombie", 2),
                             zombie("ducky_tube_zombie", 3, water=True),
                             zombie("ducky_tube_buckethead_zombie", 1, water=True)], 330),
        wave("small", 1500, [zombie("basic_zombie", 4), zombie("buckethead_zombie", 1),
                             zombie("newspaper_zombie", 2),
                             zombie("ducky_tube_zombie", 4, water=True),
                             zombie("ducky_tube_conehead_zombie", 2, water=True)], 300),
        wave("final", 1680, [zombie("flag_zombie", 1), zombie("basic_zombie", 6),
                             zombie("conehead_zombie", 3), zombie("buckethead_zombie", 1),
                             zombie("newspaper_zombie", 2),
                             zombie("ducky_tube_zombie", 4, water=True),
                             zombie("ducky_tube_conehead_zombie", 2, water=True),
                             zombie("ducky_tube_buckethead_zombie", 1, water=True)], 140, warning=180),
    ])

# ---------------------------------------------------------------------------
# 3-3: the snorkel zombie. It swims under the surface and stands up only to bite,
# which is what makes it the level's whole puzzle.
# ---------------------------------------------------------------------------
LEVEL_3_3 = base(
    "pvzce:yard/adventure/3_3", "3-3",
    "泳池的第三关：潜水僵尸来了。它整段路都潜在水面以下，豌豆会从它头上飞过去打不到它；"
    "只有它凑到植物跟前、站起来开咬的那几秒才挨得了打，所以窝瓜、火爆辣椒这类不看直线的植物才是答案。"
    "刚拿到的缠绕水草专治它：谁走进水草的格子，就被拖到水下。",
    requires="pvzce:yard/adventure/3_2",
    first_clear=unlock("tangle_kelp"),
    buffs=["pvzce:player_choice"],
    hints=[
        line("潜水僵尸在水下打不到，等它冒头再打"),
        {"trigger": "on_card_refused"},
    ],
    waves=[
        wave("small", 1500, [zombie("basic_zombie", 3), zombie("ducky_tube_zombie", 2, water=True)], 420),
        wave("small", 1500, [zombie("basic_zombie", 3), zombie("conehead_zombie", 2),
                             zombie("snorkel_zombie", 1, water=True)], 390),
        wave("small", 1500, [zombie("basic_zombie", 4), zombie("conehead_zombie", 2),
                             zombie("ducky_tube_zombie", 2, water=True),
                             zombie("snorkel_zombie", 1, water=True)], 360),
        wave("huge", 1860, [zombie("flag_zombie", 1), zombie("basic_zombie", 4),
                            zombie("conehead_zombie", 3),
                            zombie("ducky_tube_conehead_zombie", 2, water=True),
                            zombie("snorkel_zombie", 2, water=True)], 180, warning=180),
        wave("small", 1500, [zombie("basic_zombie", 4), zombie("conehead_zombie", 3),
                             zombie("ducky_tube_zombie", 3, water=True),
                             zombie("snorkel_zombie", 2, water=True)], 330),
        wave("small", 1500, [zombie("basic_zombie", 5), zombie("conehead_zombie", 3),
                             zombie("ducky_tube_conehead_zombie", 2, water=True),
                             zombie("snorkel_zombie", 3, water=True)], 300),
        wave("final", 1680, [zombie("flag_zombie", 1), zombie("basic_zombie", 6),
                             zombie("conehead_zombie", 3),
                             zombie("ducky_tube_zombie", 4, water=True),
                             zombie("ducky_tube_buckethead_zombie", 1, water=True),
                             zombie("snorkel_zombie", 4, water=True)], 140, warning=180),
    ])

# ---------------------------------------------------------------------------
# 3-4: the watering can. Everything the pool has at once, and a level that is
# meant to be survived with healing rather than with another shooter.
# ---------------------------------------------------------------------------
LEVEL_3_4 = base(
    "pvzce:yard/adventure/3_4", "3-4",
    "泳池的第四关：铁桶僵尸、撑杆僵尸、潜水僵尸和救生圈僵尸一起上，两条水线几乎没有空档。"
    "打通这一关会拿到水壶——对着植物点一下，它会被浇透：回满血、立刻长大（阳光菇这类会直接成熟），"
    "并且在十五秒里干活快四分之一。前面扛不住的时候，记得浇水比补种便宜。",
    requires="pvzce:yard/adventure/3_3",
    first_clear=unlock("watering_can"),
    buffs=["pvzce:player_choice"],
    # The can is in hand for this level and becomes a card for every level after it, which is
    # the same hand-over 2-4 -> 2-5 does with the mallet: a reward the player has already used
    # is one they will remember to use again.
    mechanics=[
        {"type": "pvzce:tool", "tool": "pvzce:watering_can", "default": True,
         "cooldown": 0, "cost": {"resources": {}}},
        dict(MOWER),
    ],
    hints=[
        line("水壶就在手上：点植物回满血，还能让它干活更快"),
        {"trigger": "on_card_refused"},
    ],
    waves=[
        wave("small", 1500, [zombie("basic_zombie", 3), zombie("ducky_tube_zombie", 2, water=True)], 420),
        wave("small", 1500, [zombie("basic_zombie", 3), zombie("conehead_zombie", 2),
                             zombie("snorkel_zombie", 1, water=True)], 390),
        wave("small", 1500, [zombie("basic_zombie", 4), zombie("newspaper_zombie", 1),
                             zombie("ducky_tube_zombie", 3, water=True),
                             zombie("snorkel_zombie", 1, water=True)], 360),
        wave("huge", 1860, [zombie("flag_zombie", 1), zombie("basic_zombie", 4),
                            zombie("conehead_zombie", 3), zombie("newspaper_zombie", 2),
                            zombie("ducky_tube_conehead_zombie", 2, water=True),
                            zombie("snorkel_zombie", 2, water=True)], 180, warning=180),
        wave("small", 1500, [zombie("basic_zombie", 4), zombie("buckethead_zombie", 2),
                             zombie("newspaper_zombie", 2),
                             zombie("ducky_tube_buckethead_zombie", 1, water=True),
                             zombie("snorkel_zombie", 2, water=True)], 330),
        wave("small", 1500, [zombie("basic_zombie", 5), zombie("conehead_zombie", 3),
                             zombie("pole_vaulter_zombie", 2),
                             zombie("ducky_tube_zombie", 4, water=True),
                             zombie("snorkel_zombie", 2, water=True)], 300),
        wave("huge", 1860, [zombie("flag_zombie", 1), zombie("basic_zombie", 5),
                            zombie("conehead_zombie", 3), zombie("pole_vaulter_zombie", 2),
                            zombie("buckethead_zombie", 2),
                            zombie("ducky_tube_conehead_zombie", 3, water=True),
                            zombie("snorkel_zombie", 3, water=True)], 180, warning=180),
        wave("final", 1680, [zombie("flag_zombie", 1), zombie("basic_zombie", 6),
                             zombie("conehead_zombie", 4), zombie("buckethead_zombie", 2),
                             zombie("pole_vaulter_zombie", 2), zombie("newspaper_zombie", 2),
                             zombie("ducky_tube_zombie", 4, water=True),
                             zombie("ducky_tube_buckethead_zombie", 2, water=True),
                             zombie("snorkel_zombie", 3, water=True)], 140, warning=180),
    ])

# ---------------------------------------------------------------------------
# 3-5: the bonus level, and the original's own "Big Trouble Little Zombie".
#
# A CONVEYOR BELT, not a chosen deck: the original deals Peashooter / Cherry Bomb /
# Wall-nut / Lily Pad at 25% / 35% / 15% / 25%, with the lily pads capped at 18 - one
# for every water cell on the board. The belt is also why the level has no sun: the
# cards are free, and the only decision left is where they go.
#
# The crowd is the little zombies: the original does not draw new bodies for them, it scales
# the ordinary ones down, quarters their health and doubles their speed. The same content ids
# carry instance attributes for half-size art and faster movement and bites. Their health is HALF rather than the original's quarter, which
# is this build's own difficulty call: at a quarter, three peas removed one of them and the
# crowd was made of paper. Their hitboxes are NOT scaled, which is the part the player
# feels: a little zombie still fills a whole lane. The original's own list for 3-5 is the small
# versions of the plain, cone, football, ducky-tube and snorkel zombies, and its flag bearer.
# ---------------------------------------------------------------------------
BELT_CARDS = [
    ("pea_shooter", 25, None),
    ("cherry_bomb", 35, None),
    ("wall_nut", 15, None),
    # Exactly enough to plant the whole pool: the original's own cap.
    ("lily_pad", 25, 18),
]

BELT = {
    "type": "pvzce:conveyor",
    "interval_ticks": 180,
    "capacity": 7,
    "initial_cards": 3,
    "cards": [
        {"id": f"pvzce:{card}", "weight": weight}
        if limit is None else {"id": f"pvzce:{card}", "weight": weight, "max_count": limit}
        for card, weight, limit in BELT_CARDS
    ],
}

LEVEL_3_5 = base(
    "pvzce:yard/adventure/3_5", "3-5 小僵尸大麻烦",
    "泳池的第五关，也是这一段的奖励关：植物由传送带免费送来——豌豆射手、樱桃炸弹、坚果墙和莲叶，"
    "莲叶一共只发十八张，正好够把两行水池铺满。来的是原版那关的小僵尸：身体被缩掉一半，血量减半，"
    "可是走得更快、啃得也更快，一群涌上来照样能把整条防线吃穿。樱桃炸弹是这一关真正的输出，别舍不得用。",
    requires="pvzce:yard/adventure/3_4",
    first_clear=unlock("jalapeno"),
    slots=[],
    initial_sun=0,
    unlock_sun=False,
    # The original gives this level the mini-game track, the same one the bowling level plays.
    music="pvzce:music/loon_boon",
    buffs=["pvzce:auto_collect"],
    hints=[
        line("传送带免费送卡，小僵尸又小又快"),
        {"trigger": "on_card_refused"},
    ],
    mechanics=[dict(BELT), dict(MOWER)],
    rules_override={
        "pvzce:day_length": 0,
        "pvzce:night_length": -1,
        "pvzce:sun_spawn_interval_min": 0,
        "pvzce:sun_spawn_interval_max": 0,
        # No `zombie_speed_multiplier`: how fast a little zombie is belongs to the little
        # zombie, and the level is a crowd rather than a modifier.
    },
    waves=[
        wave("small", 1200, [little("basic_zombie", 8),
                             little("ducky_tube_zombie", 4, water=True)], 300),
        wave("small", 1200, [little("basic_zombie", 10),
                             little("ducky_tube_zombie", 5, water=True)], 270),
        wave("small", 1500, [little("basic_zombie", 11), little("conehead_zombie", 2),
                             little("ducky_tube_zombie", 5, water=True),
                             little("snorkel_zombie", 2, water=True)], 240),
        wave("huge", 1860, [little("flag_zombie", 1), little("basic_zombie", 13),
                            little("conehead_zombie", 2),
                            little("ducky_tube_zombie", 7, water=True),
                            little("snorkel_zombie", 4, water=True)], 180, warning=180),
        wave("small", 1500, [little("basic_zombie", 13), little("football_zombie", 2),
                             little("ducky_tube_zombie", 7, water=True),
                             little("snorkel_zombie", 2, water=True)], 210),
        wave("small", 1500, [little("basic_zombie", 15), little("conehead_zombie", 3),
                             little("ducky_tube_zombie", 8, water=True),
                             little("snorkel_zombie", 4, water=True)], 180),
        wave("huge", 1860, [little("flag_zombie", 1), little("basic_zombie", 15),
                            little("conehead_zombie", 4), little("football_zombie", 2),
                            little("ducky_tube_zombie", 9, water=True),
                            little("snorkel_zombie", 4, water=True)], 150, warning=180),
        wave("small", 1500, [little("basic_zombie", 17), little("football_zombie", 2),
                             little("ducky_tube_zombie", 9, water=True),
                             little("snorkel_zombie", 4, water=True)], 150),
        wave("small", 1500, [little("basic_zombie", 18), little("conehead_zombie", 4),
                             little("football_zombie", 2),
                             little("ducky_tube_zombie", 9, water=True),
                             little("snorkel_zombie", 4, water=True)], 140),
        wave("final", 1680, [little("flag_zombie", 1), little("basic_zombie", 22),
                             little("conehead_zombie", 4), little("football_zombie", 3),
                             little("ducky_tube_zombie", 11, water=True),
                             little("snorkel_zombie", 6, water=True)], 120, warning=180),
    ])


def write(level: dict) -> None:
    path = LEVELS / (level["id"].rsplit("/", 1)[1] + ".json")
    path.write_text(json.dumps(level, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    waves = level["waves"]
    zombies = sum(e["count"] for w in waves for e in w["entries"])
    ticks = sum(w["delay"] + w["spawn_interval"] * max(0, sum(e["count"] for e in w["entries"]) - 1)
                for w in waves)
    print(f"wrote {path.relative_to(REPO_ROOT)}: {len(waves)} waves, {zombies} zombies, "
          f"~{ticks / 60 / 60:.1f} min of release")


def main() -> None:
    for level in (LEVEL_3_1, LEVEL_3_2, LEVEL_3_3, LEVEL_3_4, LEVEL_3_5):
        write(level)


if __name__ == "__main__":
    main()
