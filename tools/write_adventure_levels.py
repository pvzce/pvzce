#!/usr/bin/env python3
"""Write all forty adventure levels, 1-1 to 4-10.

This is the only producer of the shipped adventure level files. It replaces the four per-world
scripts that used to sit beside it (`write_night_levels.py`, `write_pool_levels.py`,
`write_pool_levels_2.py`, `write_fog_levels.py`), because after the wave tables were rebuilt
from the original's own tables (see `original_levels.py`) the four of them had nothing left to
say that was theirs: the wave table, the zombie pool, the flag positions, the level's award, its
background, its clock, its fog and its conveyor are all facts of the level's number now.

What is *not* the original's and lives here instead:

* **the prose** - each level's title, description and hints, which this project writes itself;
* **the project's own rewards** - the original hands out nothing on 1-4, 1-9, 2-4, 2-9, 3-4,
  3-9, 4-4 and 4-9, and this project fills those eight with its four tools and four buffs;
* **the mechanics the original has no name for** - the conveyor card tables, the gravestone
  fields, the vase field, the fog geometry, the wave-pacing block on 1-6;
* **the board the background implies** - 1-1 is one lane and 1-2/1-3 are three, which the
  original expresses by turning the rest of the lawn to dirt.

Run from the repository root:

    python3 tools/write_adventure_levels.py [--check]

`--check` writes nothing and fails if a file on disk differs from what this script would write.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Dict, List, Optional

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(Path(__file__).resolve().parent))

import original_levels as original  # noqa: E402

LEVELS_DIR = REPO_ROOT / "pvzce-game/src/main/resources/data/pvzce/levels/yard/adventure"

WIDTH = 9
BACKGROUND = {
    "day": "pvzce:textures/gui/screen/level/background1",
    "night": "pvzce:textures/gui/screen/level/background2",
    "pool": "pvzce:textures/gui/screen/level/background3",
    "fog": "pvzce:textures/gui/screen/level/background4",
}
BACKGROUND_1_ROW = "pvzce:textures/gui/screen/level/background1_1row"
BACKGROUND_3_ROW = "pvzce:textures/gui/screen/level/background1_3row"
MUSIC = {
    "day": "pvzce:music/grasswalk",
    "night": "pvzce:music/moongrains",
    "pool": "pvzce:music/watery_graves",
    "fog": "pvzce:music/rigor_mormist",
}
#: The two conveyor levels that are their own minigame, the three mini-boss levels, and the
#: puzzle level.
MUSIC_MINIGAME = "pvzce:music/loon_boon"
MUSIC_MINI_BOSS = "pvzce:music/ultimate_battle"
MUSIC_PUZZLE = "pvzce:music/cerebrawl"

LAND_ROWS = [0, 1, 4, 5]
WATER_ROWS = [2, 3]

#: Rule overrides that are this project's own tuning rather than the original's: the two
#: minigame levels run their zombies faster than the original's walk, and Whack-a-Zombie keeps
#: its own grave clock and sun-drop rate. Written here so a regeneration cannot silently drop
#: them - they used to exist only inside the level files, which is exactly how a rebuild loses
#: them.
EXTRA_RULES: Dict[int, dict] = {
    5: {"pvzce:zombie_speed_multiplier": 1.5},
    15: {"pvzce:graves_spawn_night": True, "pvzce:zombie_speed_multiplier": 2.6,
         "pvzce:zombie_sun_drop_chance": 0.05, "pvzce:zombie_rise_ticks": 40},
    # Scary Potter's second economy: what the vases do not hold, the zombies that came out of them
    # drop. Ten percent of three suns is what a level with no sky and no producers can pay without
    # the player simply planting everything the pots hand over.
    35: {"pvzce:zombie_sun_drop_chance": 0.1},
}

#: Levels whose unlock block is not just "the level before it". 1-4 is the shop's own demo: it
#: needs the three levels before it *and* 500 coins, which is what the shop page's "buy a level"
#: flow is tested against (`LevelUnlockFlowTest`).
UNLOCK_OVERRIDES: Dict[int, dict] = {
    4: {"requires": [
        {"type": "level", "id": "pvzce:yard/adventure/1_1"},
        {"type": "level", "id": "pvzce:yard/adventure/1_2"},
        {"type": "level", "id": "pvzce:yard/adventure/1_3"},
    ], "cost": 500},
}

#: The eight levels the original leaves empty, filled with this project's own tools and buffs.
SPECIAL_REWARDS: Dict[int, dict] = {
    4: {"type": "unlock", "id": "pvzce:glove"},
    9: {"type": "buff", "id": "pvzce:auto_collect"},
    14: {"type": "unlock", "id": "pvzce:hammer"},
    19: {"type": "buff", "id": "pvzce:mushroom_range"},
    24: {"type": "unlock", "id": "pvzce:watering_can"},
    29: {"type": "buff", "id": "pvzce:kelp_spread"},
    34: {"type": "unlock", "id": "pvzce:vase"},
    39: {"type": "buff", "id": "pvzce:fog_retreat"},
}

#: Replay money, per level. The world finals pay more, and the two "nothing new" levels of an
#: area pay least.
COINS = {
    1: 100, 2: 100, 3: 100, 4: 150, 5: 250, 6: 150, 7: 200, 8: 250, 9: 100, 10: 300,
    11: 200, 12: 200, 13: 200, 14: 300, 15: 300, 16: 300, 17: 300, 18: 300, 19: 100, 20: 300,
    21: 300, 22: 300, 23: 300, 24: 300, 25: 300, 26: 300, 27: 300, 28: 300, 29: 300, 30: 300,
    31: 400, 32: 400, 33: 400, 34: 400, 35: 400, 36: 400, 37: 400, 38: 400, 39: 400, 40: 400,
}

#: The conveyor card tables. The original's conveyor levels deal from a list of the area's
#: plants; the weights here are this project's own (it has one card pool for all of them, and a
#: level that dealt uniformly would bury the player in cherry bombs).
#: ``(card, weight)`` or ``(card, weight, max_count)`` - the third number is the original's
#: "max count", the one belt card that runs out (one grave buster per gravestone).
CONVEYORS: Dict[int, dict] = {
    5: {"interval_ticks": 150, "capacity": 6, "initial_cards": 2,
        "cards": [("pvzce:bowling_nut", 1)]},
    10: {"interval_ticks": 240, "capacity": 6, "initial_cards": 3,
         "cards": [("pvzce:pea_shooter", 3), ("pvzce:wall_nut", 3), ("pvzce:snow_pea", 2),
                   ("pvzce:repeater", 2), ("pvzce:chomper", 2), ("pvzce:potato_mine", 2),
                   ("pvzce:cherry_bomb", 1)]},
    20: {"interval_ticks": 240, "capacity": 6, "initial_cards": 2,
         "cards": [("pvzce:puff_shroom", 10), ("pvzce:fume_shroom", 15),
                   ("pvzce:grave_buster", 20, 13), ("pvzce:hypno_shroom", 10),
                   ("pvzce:scaredy_shroom", 15), ("pvzce:ice_shroom", 15),
                   ("pvzce:doom_shroom", 15)]},
    25: {"interval_ticks": 180, "capacity": 7, "initial_cards": 3,
         "cards": [("pvzce:pea_shooter", 25), ("pvzce:cherry_bomb", 35),
                   ("pvzce:wall_nut", 15), ("pvzce:lily_pad", 25, 18)]},
    30: {"interval_ticks": 240, "capacity": 7, "initial_cards": 3,
         "cards": [("pvzce:pea_shooter", 3), ("pvzce:threepeater", 3),
                   ("pvzce:snow_pea", 3), ("pvzce:torchwood", 2), ("pvzce:tall_nut", 2),
                   ("pvzce:spikeweed", 2), ("pvzce:jalapeno", 1),
                   ("pvzce:lily_pad", 4), ("pvzce:tangle_kelp", 2)]},
}

#: The original's Scary Potter level (4-5): three rounds of vases, each round reaching one
#: column further towards the house, each vase holding a plant or a zombie. Straight out of
#: `Challenge::ScaryPotterPopulate`.
#: `leaf_count` is the original's `ScaryPotterChangePotType`: round 2 turns a couple of its seed
#: pots green and round 3 turns three, so the player is told where a few of the plants are.
#:
#: The vase level's pots, in three rounds. The `sun` pots are the level's economy: there is no
#: sky here (`sun_spawn_interval_*` are both 0) and none of the plants that come out of the pots
#: produces anything, so without them the one card the player is handed - a 150-sun cherry bomb -
#: could never be planted. Three sun pots a round is 225 sun, one bomb and change.
#:
#: **There are at least as many zombie pots as plant pots in every round**, which is what the
#: user asked for after playing the first cut of it (nine plants against four zombies in round 1
#: made the level a formality): every plant pot now hands over one whole plant, so the plants are
#: the player's army and the zombies have to be able to threaten it. The rounds also get harder by
#: *kind*, not only by count - one Buckethead in the first, two plus a football in the second,
#: and the third adds the dancing and jack-in-the-box zombies.
SCARY_POTTER_ROUNDS = [
    {"from_column": 6, "leaf_count": 0, "pots": [
        {"kind": "sun", "id": "pvzce:sun", "count": 2},
        {"kind": "plant", "id": "pvzce:pea_shooter", "count": 3},
        {"kind": "plant", "id": "pvzce:squash", "count": 3},
        {"kind": "zombie", "id": "pvzce:basic_zombie", "count": 6},
        {"kind": "zombie", "id": "pvzce:buckethead_zombie", "count": 1}]},
    {"from_column": 5, "leaf_count": 2, "pots": [
        {"kind": "sun", "id": "pvzce:sun", "count": 3},
        {"kind": "plant", "id": "pvzce:pea_shooter", "count": 3},
        {"kind": "plant", "id": "pvzce:snow_pea", "count": 3},
        {"kind": "plant", "id": "pvzce:squash", "count": 2},
        {"kind": "zombie", "id": "pvzce:basic_zombie", "count": 6},
        {"kind": "zombie", "id": "pvzce:buckethead_zombie", "count": 2},
        {"kind": "zombie", "id": "pvzce:football_zombie", "count": 1}]},
    {"from_column": 4, "leaf_count": 3, "pots": [
        {"kind": "sun", "id": "pvzce:sun", "count": 3},
        {"kind": "plant", "id": "pvzce:pea_shooter", "count": 3},
        {"kind": "plant", "id": "pvzce:snow_pea", "count": 3},
        {"kind": "plant", "id": "pvzce:hypno_shroom", "count": 3},
        {"kind": "zombie", "id": "pvzce:basic_zombie", "count": 8},
        {"kind": "zombie", "id": "pvzce:buckethead_zombie", "count": 3},
        {"kind": "zombie", "id": "pvzce:dancing_zombie", "count": 1},
        {"kind": "zombie", "id": "pvzce:jack_in_the_box_zombie", "count": 1}]},
]
# ---------------------------------------------------------------------------
# Prose: the one thing the original cannot supply
# ---------------------------------------------------------------------------
#
# One entry per level: the title, the description the level-select page and the loading screen
# show, and the hint lines played in the corner. Every line is written against what the level
# now *is* - which zombies it introduces, which plant it hands out, what its gimmick asks of the
# player - so a change to the wave tables that contradicts a line here is a bug in this table.
PROSE: Dict[int, tuple] = {
    1: ("1-1",
        "冒险模式的第一关：只有一条草坪，你手上只有豌豆射手。撑过几只普通僵尸，向日葵就是你的。",
        ["把豌豆射手种在草坪上，它会一直往前打",
         "僵尸走到最左边之前打掉它"]),
    2: ("1-2",
        "草坪宽成三行。天上会掉阳光，向日葵把阳光变成更多的阳光——这一关之后，你就有稳定的经济了。"
        "打完它，樱桃炸弹归你。",
        ["向日葵种在靠房子的一侧，前面留给射手"]),
    3: ("1-3",
        "路障僵尸第一次出现：头上那顶路障比普通僵尸多挨一倍。这一关之后，坚果墙归你。",
        ["路障僵尸要多打几下才倒"]),
    4: ("1-4",
        "五行的草坪全部打开。既然植物会被吃掉，手套就是这一关的礼物：把一株长错地方的植物整株搬走。",
        ["手套可以把植物从一个格子搬到另一个格子",
         "被啃的植物挪到后面还能长回来"]),
    5: ("1-5 坚果保龄球",
        "原版的坚果保龄球：传送带只送坚果，把它放在左边四列，它会自己滚出去把撞到的僵尸一起带翻，"
        "弹得越多硬币越多。打完它，土豆雷归你。",
        ["坚果放在左边的放置区里才会滚出去",
         "传送带发牌是免费的，不用管阳光"]),
    6: ("1-6",
        "撑杆僵尸第一次出现——它会直接跳过你种下的第一株植物。这一关的卡槽是发满的："
        "打完它，寒冰射手归你。",
        ["撑杆僵尸会跳过最前面的一株植物，把它种在第二排"]),
    7: ("1-7",
        "二十波，两面旗帜。大嘴花是这一关的礼物：一口吞下一整只僵尸，然后慢慢嚼。",
        ["大嘴花吞下僵尸之后有一段时间张不开嘴",
         "撑杆僵尸会跳过最前面的一株植物"]),
    8: ("1-8",
        "铁桶僵尸第一次出现，一顶铁桶能挡下很多豌豆。打完这一关，双发射手归你。",
        ["铁桶僵尸的桶打掉之后才是它的本体"]),
    9: ("1-9",
        "二十波的硬仗。这一关不给新植物，给的是自动收集：阳光和硬币落地就会自己进账，"
        "你不用再腾出手去点它们。",
        ["天上掉的阳光会自己收走，专心种植物"]),
    10: ("1-10",
        "白天的最后一关，也是第一场 mini-boss：传送带发牌，僵尸来得又早又密，天色会入夜。"
        "打完它，小喷菇就是你的——蘑菇不需要阳光。",
        ["传送带发的卡是免费的，种得越早越好",
         "入夜之后蘑菇会醒着，放心用"]),
    11: ("2-1",
        "夜里的第一关：天上不再掉阳光，墓碑占住格子，僵尸里多了拿报纸的——报纸被打烂之后，"
        "它会跑得比谁都快。打完它，阳光菇归你。",
        ["墓碑占住的格子种不下东西，绕开它",
         "夜里的阳光只能靠向日葵和阳光菇"]),
    12: ("2-2",
        "二十波。大喷菇是这一关的礼物：它的孢子打一整行，铁桶僵尸也挡不住。",
        ["报纸僵尸的报纸打烂之后会跑得更快，早点打掉它",
         "铁桶僵尸的桶打掉之后才是它的本体"]),
    13: ("2-3",
        "铁门僵尸第一次出现：那面纱门挡得住豌豆，得先打掉它。墓碑吞噬者可以把墓碑吃干净，"
        "那一格就能种东西了。",
        ["铁门僵尸的盾要打掉才伤得到它",
         "墓碑占住的格子这一局都种不下东西，绕着排"]),
    14: ("2-4",
        "夜里最长的一段：二十波，撑杆僵尸回来，墓碑比前几关多。这一关的礼物是锤子——"
        "砸掉一株植物或一个墓碑，腾出格子来。",
        ["撑杆僵尸跳过来之后，第二排要接得住",
         "墓碑多了就用范围伤害清，别一格一格啃"]),
    15: ("2-5 打地鼠",
        "原版的打地鼠：这一关没有僵尸从路上走进来，它们全部从墓碑里钻出来，锤子是你的主业。"
        "卡槽里只有土豆雷、墓碑吞噬者和樱桃炸弹；打完它，催眠菇归你。",
        ["僵尸从墓碑里冒出来，先锤再种",
         "墓碑吞噬者清掉一块地，土豆雷守着它"]),
    16: ("2-6",
        "橄榄球僵尸第一次出现：戴着橄榄球头盔，跑得比普通僵尸快一倍，血也厚。"
        "胆小菇是这一关的礼物，离得远它才敢开枪。",
        ["橄榄球僵尸冲得快，用土豆雷和寒冰射手拦它",
         "胆小菇离僵尸太近就会缩起来"]),
    17: ("2-7",
        "二十波的夜仗，铁门和橄榄球一起来。寒冰菇是这一关的礼物：冻住全场。",
        ["寒冰菇冻住全场，是最值钱的一次喘息"]),
    18: ("2-8",
        "舞王僵尸第一次出现，他会召唤四个伴舞从左右两边挤过来。毁灭菇是这一关的礼物——"
        "炸掉一大片，顺便在那块地上留一个坑。",
        ["舞王僵尸会一直补伴舞，先打他",
         "毁灭菇留下的坑这一局不能再种"]),
    19: ("2-9",
        "二十波，双旗帜。这一关不给新植物，给的是蘑菇的射程：所有蘑菇都能多打一格。",
        ["蘑菇的射程变长了，大喷菇和小喷菇都受益"]),
    20: ("2-10",
        "夜里的 mini-boss：传送带发牌、二十波、僵尸密度三倍。打完它，睡莲就是你的——"
        "下一片草坪有一半是水。",
        ["传送带发牌，把免费的卡尽量铺开",
         "墓碑会在最后一波一起开口"]),
    21: ("3-1",
        "泳池的第一关：草坪中间多出两行水，僵尸也会从水里来——它们套着鸭子救生圈划过来，"
        "只有莲叶能让植物种在水上。水里的小推车是泳池清洁器；这一关的礼物是窝瓜。",
        ["水里先放莲叶，再把射手种在莲叶上",
         "救生圈僵尸只从水里来"]),
    22: ("3-2",
        "陆地上来了铁桶和橄榄球，水里的救生圈也戴上了帽子。三线射手是这一关的礼物："
        "一发打三条路，一条水路也不浪费。",
        ["水里的植物要先用莲叶垫底",
         "三线射手打三条路，种在中间那行最划算"]),
    23: ("3-3",
        "潜水僵尸第一次出现：它在水下的时候打不到，得等它浮上来换气。缠绕水草是这一关的礼物，"
        "把它拖进水里，一口带走。",
        ["潜水僵尸沉在水下时打不中，等它冒头",
         "缠绕水草只能种在水里，一次拖一只"]),
    24: ("3-4",
        "泳池最长的一关：三十波，陆地上报纸、铁桶、撑杆一起来。水壶是这一关的礼物，"
        "点一下把植物浇回满血。",
        ["水壶能给一株植物回满血，冷却一好就用"]),
    25: ("3-5 小僵尸大麻烦",
        "原版的小僵尸大麻烦：传送带只送四张卡，来的全是被缩小的小僵尸——血少、跑得快、数量是"
        "平常的四倍。打完它，火爆辣椒归你。",
        ["小僵尸血少但快，别让它们堆起来",
         "莲叶不够就先把水里的路堵上"]),
    26: ("3-6 地刺上阵",
        "雪橇车僵尸第一次开过来：它不咬植物，直接碾过去，还把压过的地面冻成一层种不下东西的冰"
        "（三十秒化开，火爆辣椒和樱桃炸弹能立刻融掉）。冰面上还会滑出雪橇小队。"
        "地刺是它的反面——长在草里，僵尸从它身上走过去一直受伤。",
        ["雪橇车碾过的地面结冰，那一格一时种不下东西——三十秒后会化开",
         "地刺种在草里，僵尸会从它身上走过"]),
    27: ("3-7 火炬树桩",
        "水里的潜水僵尸和草地上的铁桶一起来，雪橇车也在这条路上。火炬树桩是这一关的礼物："
        "穿过它的豌豆会点着，伤害翻倍。",
        ["一排射手后面站一个火炬树桩，比多种两株划算"]),
    28: ("3-8 高墙",
        "海豚骑士第一次出现：它直接跳过你的第一道防线，落进防线中间。高坚果是它的答案——"
        "跳不过去，只能站在下面啃。",
        ["海豚骑士会跳过第一株植物，第二排要留得住",
         "高坚果挡得住跳，也挡得住撑杆"]),
    29: ("3-9 水草蔓延",
        "泳池的第三十波。这一关不给新植物，给的是缠绕水草的蔓延：种下一株之后，只要旁边或上下"
        "还有水池、而且那一格没有别的植物，它就会自己再长一株。",
        ["种一株水草，它旁边会自己长下一株"]),
    30: ("3-10 池畔终章",
        "泳池的最后一关，也是这一段的 mini-boss：传送带发牌、三十波、密度三倍，雪橇车、雪橇小队、"
        "潜水、海豚全在这片水里。打完它，海蘑菇就是你的——它不要阳光，只能种在水里。",
        ["传送带发牌，水上水下都要铺满",
         "雪橇车留下的冰面上会滑出雪橇小队"]),
    31: ("4-1 起雾了",
        "起雾了。右边那一片看不清，僵尸要走到亮处才现形——路灯花是这一关的礼物，"
        "它照亮的范围里雾会散开。玩偶匣僵尸也第一次来了：它走到半路会停下，然后炸开。",
        ["右边的浓雾里，僵尸要走近才看得见——那不代表它们不在那里",
         "玩偶匣僵尸会自己炸开，别在它旁边堆植物"]),
    32: ("4-2 仙人掌",
        "雾里的第二个夜晚。仙人掌是这一关的礼物：它的刺能打到空中的东西，也能扎穿橄榄球头盔。"
        "玩偶匣和橄榄球一起从雾里走出来。",
        ["雾里先铺向日葵，经济比白天慢"]),
    33: ("4-3 三叶草",
        "气球僵尸从雾里飘出来：它飞在整块草坪上方，地面上的植物够不到它，只有仙人掌和三叶草管用。"
        "三叶草是这一关的礼物，一口气把天上所有的东西都吹走。",
        ["气球僵尸飞在植物头顶，仙人掌才打得到",
         "三叶草能把天上所有飞的东西一次吹走"]),
    34: ("4-4 花瓶工具",
        "雾更浓了，气球和海豚骑士一起来。这一关的礼物是花瓶工具：零阳光、十秒冷却，"
        "可以把一张植物卡塞进花瓶里存着，也可以随时砸开拿回来。",
        ["花瓶工具能存卡，也能把存进去的卡拿回来"]),
    35: ("4-5 恐怖花瓶",
        "原版的恐怖花瓶：没有僵尸从雾里走过来，草坪上摆满了花瓶。点一下花瓶就有一把锤子砸下去："
        "砸开绿色叶子的，里面是一株植物；砸开画着问号的，里面可能是植物，也可能是僵尸，还可能是"
        "这一关唯一的阳光——天上不掉阳光，能种的东西全在这些瓶子里。瓶子里的植物会掉在地上："
        "点一下把它捡起来，再点草坪就种下去，不收阳光。三个回合，每回合开始前草坪都会被清空，"
        "一回合比一回合靠里。打完它，分裂豌豆归你。",
        ["点花瓶就有锤子砸下去：绿叶瓶里是植物，问号瓶里不一定",
         "砸出来的植物掉在地上，点一下捡起来，再点草坪种下去",
         "阳光也在瓶子里，砸光所有花瓶、清掉僵尸，就进下一个回合"]),
    36: ("4-6 杨桃",
        "矿工僵尸第一次出现：他从右边挖地道，从你防线的背后钻出来。杨桃是这一关的礼物，"
        "五个方向都能打，站在中间就不用管僵尸从哪一边来。",
        ["矿工僵尸会从左边钻出来，最后两列留点防守",
         "杨桃五个方向都打，种在中间最划算"]),
    37: ("4-7 南瓜壳",
        "铁桶和玩偶匣一起从雾里来，矿工还在挖地道。南瓜头是这一关的礼物：罩住一株植物，"
        "先替你挨咬。",
        ["南瓜壳罩住植物，被啃掉还能再罩"]),
    38: ("4-8 蹦蹦跳跳",
        "跳跳僵尸第一次出现：它踩着弹簧，一蹦一蹦地跳过你所有的植物，只有高坚果能让它停下来。"
        "磁力菇是这一关的礼物，把僵尸身上的铁件整件拿走。",
        ["跳跳僵尸会一路跳过植物，高坚果能把它拦下来",
         "磁力菇把铁桶吸走，剩下的就好打了"]),
    39: ("4-9 迷雾退缩",
        "雾最浓的一关。这一关不给新植物，给的是迷雾退缩：打完它，雾的边界会往右退一列半。",
        ["雾退了之后，看得见的格子就多了"]),
    40: ("4-10 雾散",
        "雾里的最后一夜：气球、矿工、玩偶匣、跳跳，全都来一遍。打完这一关，卷心菜投手就是你的——"
        "它是下一片草坪的第一株植物，也是唯一能越过屋顶障碍的投手。",
        ["气球和矿工一起从雾里来，前后都要留人"]),
}

CARD_REFUSED = {"trigger": "on_card_refused"}

#: 3-5 is the original's "Big Trouble Little Zombie": the ordinary bodies, drawn at half size and
#: moving twice as fast. This project gives each miniature a zombie card of its own (`mini_*`),
#: so the level's wave table is written with those rather than with the full-sized types the
#: original's own tables name. Which types can appear is still the original's business.
MINI_FORMS = {
    "basic_zombie": "mini_basic_zombie",
    "flag_zombie": "mini_flag_zombie",
    "conehead_zombie": "mini_conehead_zombie",
    "football_zombie": "mini_football_zombie",
    "snorkel_zombie": "mini_snorkel_zombie",
    "ducky_tube_zombie": "mini_ducky_tube_zombie",
    "ducky_tube_conehead_zombie": "mini_ducky_tube_conehead_zombie",
}


def mini_form(card: str) -> str:
    return MINI_FORMS.get(card, card)


# ---------------------------------------------------------------------------
# Building blocks
# ---------------------------------------------------------------------------


#: The nine tombstones 2-5 opens with, by drawing. The original rolls their columns at level
#: start (`WhackAZombiePlaceGraves(9)`) and this level's `grave_spawner` keeps nine standing for
#: the whole run, but the *opening* board is painted: a level whose spawner promises graves it
#: does not paint is reported by `GraveSpawnerMechanic.validate`, and a first grave appearing out
#: of bare lawn mid-level is exactly what that check is about.
WHACK_A_ZOMBIE_GRAVES = {
    "pvzce:grave": ["5,0", "5,2", "7,4"],
    "pvzce:grave_cross": ["7,0", "7,2"],
    "pvzce:grave_slab": ["6,1", "6,3"],
    "pvzce:grave_wide": ["8,1", "8,3"],
}


def scene(facts: original.LevelFacts) -> Dict[str, List[str]]:
    """The board's cells: every row full width, the middle two under water in a pool level."""
    graves = WHACK_A_ZOMBIE_GRAVES if facts.whack_a_zombie else {}
    taken = {cell for cells in graves.values() for cell in cells}
    grass, water = [], []
    for y in range(facts.rows):
        for x in range(WIDTH):
            cell = f"{x},{y}"
            if cell in taken:
                continue
            (water if facts.pool and y in WATER_ROWS else grass).append(cell)
    out = {"pvzce:grass": grass}
    for element, cells in graves.items():
        out[element] = list(cells)
    if facts.pool:
        out["pvzce:water"] = water
    return out


def mower(facts: original.LevelFacts) -> Optional[dict]:
    """The machines on the left edge, when the level has something to say about them.

    A level that says nothing gets one mower per row (`LevelMechanics.effective`), so writing it
    out would be noise - and noise with a cost: the effective list is ordered, and a level that
    names the default explicitly reads differently from one that does not.
    """
    if not facts.pool:
        return None
    return {
        "type": "pvzce:mower",
        "rows": LAND_ROWS,
        "kinds": [
            {"row": row, "kind": "pvzce:pool_cleaner",
             "sound": "pvzce:sfx/ambient/pool_cleaner"}
            for row in WATER_ROWS
        ],
    }


def grave_field(facts: original.LevelFacts) -> Optional[dict]:
    """The tombstones a night level opens with, in the original's own numbers."""
    layout = facts.graves
    if layout is None:
        return None
    count, min_x = layout
    return {"type": "pvzce:grave_field", "count": count, "min_x": min_x, "max_x": 8}


def fog(facts: original.LevelFacts) -> Optional[dict]:
    column = facts.fog_column
    if column is None:
        return None
    return {"type": "pvzce:fog", "start_column": column, "end_column": 9.0, "max_alpha": 0.94}


def conveyor(facts: original.LevelFacts) -> Optional[dict]:
    table = CONVEYORS.get(facts.number)
    if table is None:
        return None
    cards = []
    for entry in table["cards"]:
        card = {"id": entry[0], "weight": entry[1]}
        if len(entry) > 2:
            card["max_count"] = entry[2]
        cards.append(card)
    return {
        "type": "pvzce:conveyor",
        "interval_ticks": table["interval_ticks"],
        "capacity": table["capacity"],
        "initial_cards": table["initial_cards"],
        "cards": cards,
    }


def tool(tool_id: str, default: bool = True, cooldown: int = 0) -> dict:
    """A level that hands the player a tool from the first tick."""
    return {"type": "pvzce:tool", "tool": f"pvzce:{tool_id}", "default": default,
            "cooldown": cooldown, "cost": {"resources": {}}}


def mechanics_for(facts: original.LevelFacts) -> List[dict]:
    """Everything the level declares about how its board behaves."""
    out: List[dict] = []

    if facts.scary_potter:
        # No mowers: a vase level is not lost by letting a zombie through, it is lost by running
        # out of lawn. The original gives Scary Potter no mowers either.
        out.append({
            "type": "pvzce:scary_potter",
            "rounds": [
                {"from_column": rnd["from_column"], "leaf_count": rnd["leaf_count"],
                 "pots": [dict(pot) for pot in rnd["pots"]]}
                for rnd in SCARY_POTTER_ROUNDS
            ],
        })
        # No hammer either, and that is the whole of "点花瓶就有锤子砸下去": the mallet is a
        # gesture the client plays over the vase that was clicked (`SmashContainerC2S`), so a
        # level that granted the tool would ALSO get a mallet cursor, a price and a cooldown for
        # a swing that is not a tool. The pots break in every level, this one included.
        #
        # Declared, not left out: a level that says nothing about mowers gets one per row
        # (`LevelMechanics.effective`), and the original's Scary Potter has none - a vase level
        # is not lost by letting a zombie through, it is lost by running out of lawn.
        out.append({"type": "pvzce:mower", "rows": []})
        return out

    if facts.whack_a_zombie:
        out.append({
            "type": "pvzce:grave_spawner",
            "zombies": ["pvzce:basic_zombie", "pvzce:conehead_zombie",
                        "pvzce:buckethead_zombie"],
            "min_graves": 9, "initial_graves": 9, "graves_per_wave": 1, "interval": 90,
            "min_x": 4, "max_x": 8,
        })
        out.append(tool("hammer"))
        out.append({"type": "pvzce:mower", "rows": []})
        return out

    if facts.number == 5:
        out.append(conveyor(facts))
        out.append({"type": "pvzce:placement_zone", "min_x": 0, "max_x": 3})
        out.append({"type": "pvzce:mower", "rows": []})
        return out

    if facts.number == 24:
        out.append(tool("watering_can"))

    field = grave_field(facts)
    if field is not None:
        out.append(field)

    machine = conveyor(facts)
    if machine is not None:
        out.append(machine)

    machine_rig = mower(facts)
    if machine_rig is not None:
        out.append(machine_rig)

    fog_block = fog(facts)
    if fog_block is not None:
        out.append(fog_block)

    return out


def hints_for(facts: original.LevelFacts) -> List[dict]:
    lines = [{"trigger": "on_start", "text": text, "duration_ticks": 600}
             for text in PROSE[facts.number][2]]
    lines.append(dict(CARD_REFUSED))
    return lines


def rules_for(facts: original.LevelFacts) -> dict:
    """The clock and the sky.

    A night level is written as "there is no day, and the night is very long", which is how the
    client reads "always dark"; a day level has no night at all. 1-10 is the one level whose sky
    changes: its day is 3600 ticks and the night after it is the rest of the run.
    """
    if facts.number == 10:
        # The one level whose sky changes: a minute of day, then night for the rest of the run.
        # The old build also doubled its spawn *speed* here, which was its stand-in for the
        # original's mini-boss density; the wave table now carries that density itself (the
        # original's own `IsMiniBossLevel` triples the points per wave), so the multiplier is
        # gone rather than doubling it twice.
        return {
            "pvzce:day_length": 3600,
            "pvzce:night_length": 360000,
            "pvzce:sun_spawn_interval_min": 0,
            "pvzce:sun_spawn_interval_max": 0,
        }
    if facts.night:
        rules = {
            "pvzce:day_length": 0,
            "pvzce:night_length": 360000,
            "pvzce:sun_spawn_interval_min": 0,
            "pvzce:sun_spawn_interval_max": 0,
        }
        rules.update(EXTRA_RULES.get(facts.number, {}))
        return rules
    if facts.conveyor:
        rules = {
            "pvzce:day_length": 0,
            "pvzce:night_length": -1,
            "pvzce:sun_spawn_interval_min": 0,
            "pvzce:sun_spawn_interval_max": 0,
        }
        rules.update(EXTRA_RULES.get(facts.number, {}))
        return rules
    rules = {
        "pvzce:day_length": 0,
        "pvzce:night_length": -1,
        "pvzce:sun_spawn_interval_min": 480,
        "pvzce:sun_spawn_interval_max": 720,
        "pvzce:sun_spawn_initial_ticks": 300,
    }
    rules.update(EXTRA_RULES.get(facts.number, {}))
    return rules


def music_for(facts: original.LevelFacts) -> str:
    if facts.mini_boss:
        return MUSIC_MINI_BOSS
    if facts.whack_a_zombie or facts.number in (5, 25):
        return MUSIC_MINIGAME
    if facts.scary_potter:
        return MUSIC_PUZZLE
    return MUSIC[facts.kind]


def background_for(facts: original.LevelFacts) -> str:
    if facts.number == 1:
        return BACKGROUND_1_ROW
    if facts.number <= 3:
        return BACKGROUND_3_ROW
    return BACKGROUND[facts.kind]


def reward_for(facts: original.LevelFacts) -> Optional[dict]:
    if facts.number in SPECIAL_REWARDS:
        return dict(SPECIAL_REWARDS[facts.number])
    if facts.award is None:
        return None
    return {"type": "unlock", "id": f"pvzce:{facts.award}"}


def waves_for(facts: original.LevelFacts) -> List[dict]:
    """The level's wave table.

    Two levels do not use their rolled waves: Whack-a-Zombie raises its zombies out of the
    gravestones (the waves carry only the pacing and the progress bar) and Scary Potter has no
    waves at all.
    """
    if facts.scary_potter:
        return []
    out: List[dict] = []
    for index, wave in enumerate(original.waves_for(facts.number)):
        if facts.whack_a_zombie:
            # Whack-a-Zombie's own clock: the level opens like any other and then gives each
            # wave exactly 2000 ticks while the graves do the spawning
            # (`Challenge::WhackAZombieSpawning`). The rolled zombies are dropped - nothing
            # walks in on this level.
            delay = (original.ZOMBIE_COUNTDOWN_FIRST_WAVE if index == 0 else 2000)
        else:
            delay = wave.delay
        entries = wave.entries
        if facts.little_trouble:
            entries = [dict(entry,
                            id="pvzce:" + mini_form(entry["id"].removeprefix("pvzce:")))
                       for entry in entries]
        body: Dict[str, object] = {
            "type": wave.type,
            "delay": delay,
            "entries": [] if facts.whack_a_zombie else entries,
            "spawn_interval": wave.spawn_interval,
        }
        if wave.warning_ticks is not None:
            body["warning_ticks"] = wave.warning_ticks
        out.append(body)
    return out


#: The blocks the writer carries over from the file it is about to replace. A level's
#: conversation is content - the editor edits it in place, and it has nothing to do with the
#: wave tables, the stage facts or the rewards this script generates - so overwriting it would
#: be the generator deleting work it never owned. Everything else is rebuilt from scratch, which
#: is what makes a regeneration reviewable.
CARRIED_BLOCKS = ("dialogue",)


def unlock_for(facts: original.LevelFacts) -> dict:
    """What has to be true before this level opens: the one before it, unless it is special."""
    if facts.number in UNLOCK_OVERRIDES:
        return dict(UNLOCK_OVERRIDES[facts.number])
    if facts.number == 1:
        return {}
    previous = original.level_facts(facts.number - 1).name.replace("-", "_")
    return {"requires": [{"type": "level", "id": f"pvzce:yard/adventure/{previous}"}]}


def build(facts: original.LevelFacts, carried: Optional[dict] = None) -> dict:
    deck = facts.deck
    slots = [] if deck is None else [f"pvzce:{card}" for card in deck]
    if deck is not None and facts.sun > 0:
        slots.append("pvzce:sun")

    level: Dict[str, object] = {
        "id": f"pvzce:yard/adventure/{facts.name.replace('-', '_')}",
        "name": PROSE[facts.number][0],
        "description": PROSE[facts.number][1],
        "width": WIDTH,
        "height": facts.rows,
        "scene": scene(facts),
        "teams": [
            {"id": "pvzce:plant_team", "name": "植物方", "win_condition": "survive_waves"},
            {"id": "pvzce:zombie_team", "name": "僵尸方", "win_condition": "plant_side_lost"},
        ],
        "win_team": "pvzce:plant_team",
        "playable_teams": ["pvzce:plant_team"],
        "rules": rules_for(facts),
        "env_vars": {},
        "wave_interval_end_multiplier": 1.0,
        "slots": slots,
        "initial_sun": facts.sun,
        "mechanics": mechanics_for(facts),
        "waves": waves_for(facts),
        "music": {"cues": [{
            "at_tick": 0, "track": "background", "event": music_for(facts),
            "loop": True, "volume": 0.85, "fade_seconds": 1.0,
        }]},
        "unlock": unlock_for(facts),
        "background": background_for(facts),
        "hidden_scene_elements": ["pvzce:grass"],
        "disable_shaders": not facts.pool and facts.number != 10,
        "hints": hints_for(facts),
    }

    if facts.sun > 0:
        level["unlock_resources"] = {"pvzce:sun": True}
    # A level whose cards are not chosen says so by filling its own bar: "nothing to choose"
    # is `total slots - the level's fixed cards <= 0`, which is what sends the seed screen into
    # its preview-only mode. 4-5 is the case that matters here - the vase level hands the player
    # one cherry bomb and nothing else, and everything else arrives out of the pots
    # (`CardSource.receiveCard` appends past the cap, so the freed cards still fit).
    if facts.scary_potter:
        # The sun card is part of the level's own bar, like every other level's: without it in the
        # bar the resource is not collectible at all (`collectible_without_card: false`), so the
        # sun out of the pots and the sun off the zombies would both be dead drops - and the bank
        # HUD, which draws only when the card is in the bar, would never appear either.
        if "pvzce:sun" not in level["slots"]:
            level["slots"] = ["pvzce:sun"] + list(level["slots"])
        level["max_seed_slots"] = len(level["slots"])
        # Nothing to choose and no buff page to choose it on: the level starts as soon as it is
        # picked from the list, without the card screen (`seed_screen: false`).
        level["seed_screen"] = False
    elif facts.number == 1:
        level["max_seed_slots"] = 2
    elif facts.number == 2:
        level["max_seed_slots"] = 4
    elif facts.number == 3:
        level["max_seed_slots"] = 5
    elif facts.number == 4:
        level["max_seed_slots"] = 8

    buffs: List[str] = []
    if facts.conveyor or facts.whack_a_zombie or facts.scary_potter:
        # A level whose cards are dealt to it (or taken out of its vases) has nothing to collect
        # by hand: sun and coins go straight to the bank.
        buffs.append("pvzce:auto_collect")
    elif deck is None:
        buffs.append("pvzce:player_choice")
    if buffs:
        level["buffs"] = buffs

    # 1-6 is the level the wave-pacing block exists for: a wall of ordinary zombies that the
    # player is meant to thin out, so the next wave is allowed to arrive early once the lawn is
    # quiet again.
    if facts.number == 6:
        level["mechanics"] = list(level["mechanics"]) + [{
            "type": "pvzce:wave_pacing",
            "early_wave_kill_ratio": 0.6,
            "clear_reward_grace_ticks": 600,
            "waves": [{"waves": [4, 5, 6, 7, 8, 9, 10], "mode": "survival_ratio",
                       "kill_ratio": 0.6}],
        }]

    if carried:
        # Inserted where the level files have always had it (right after the music), so a diff
        # of a regenerated level does not move the conversation around.
        ordered: Dict[str, object] = {}
        for key, value in level.items():
            ordered[key] = value
            if key == "music":
                for block in CARRIED_BLOCKS:
                    if block in carried:
                        ordered[block] = carried[block]
        level = ordered

    reward = reward_for(facts)
    if reward is not None:
        level["rewards"] = {
            "first_clear": [reward],
            "repeat": [{"type": "coins", "amount": COINS[facts.number]}],
            "coin_drop": "pvzce:coin_silver",
            "coin_drop_chance": 0.25,
            "coin_drop_amount": 1,
        }
    return level


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="write nothing; fail if a file differs")
    args = parser.parse_args()

    failed = False
    for facts in original.LEVELS:
        path = LEVELS_DIR / (facts.name.replace("-", "_") + ".json")
        carried: Dict[str, object] = {}
        if path.exists():
            existing = json.loads(path.read_text(encoding="utf-8"))
            carried = {block: existing[block] for block in CARRIED_BLOCKS if block in existing}
        body = build(facts, carried)
        text = json.dumps(body, ensure_ascii=False, indent=2) + "\n"
        if args.check:
            current = path.read_text(encoding="utf-8") if path.exists() else ""
            if current != text:
                print(f"stale: {path}")
                failed = True
            continue
        path.write_text(text, encoding="utf-8")
        print("wrote %s (%d waves)" % (path.name, len(body["waves"])))
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
