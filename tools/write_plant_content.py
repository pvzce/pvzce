#!/usr/bin/env python3
"""Write the content JSON for the shooter, pult and ash lines.

The art for these plants comes from the original's own reanims (see
``reanim_to_pvzce_all.py``); this script writes the matching definitions so the two
cannot drift apart. Numbers follow the original game, converted to this project's
units (ticks at 60tps, world cells):

=================  ======  ==================================================================
plant              cost    shots
=================  ======  ==================================================================
snow_pea           175     20 damage + slow 50% for 4s
repeater           200     2 x 20
threepeater        325     20 into its own row and the two beside it, fires if any is occupied
split_pea          125     20 forwards and 20 backwards, fires if either side is occupied
gatling_pea        250     4 x 20, plus a faster first volley
cactus             125     20, and the raised-arm clip for airborne targets
cabbage_pult       100     lobbed 40
melon_pult         300     lobbed 80 with a 1.5 cell splash
winter_melon       200     lobbed 80, splash, plus slow
puff_shroom        0       20, three cells of reach, asleep in daylight (coffee bean wakes it)
sun_shroom         25      15 sun every 24s, asleep in daylight (the night's sun)
jalapeno           125     one row, 1800
doom_shroom        125     3.5 cell blast, 1800, leaves a crater, asleep in daylight
squash             50      one cell, 1800, proximity triggered
=================  ======  ==================================================================

Run from the repository root:

    python3 tools/write_plant_content.py
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Dict, List, Optional, Sequence

REPO_ROOT = Path(__file__).resolve().parents[1]
RESOURCES = REPO_ROOT / "pvzce-game" / "src" / "main" / "resources"
NS = "pvzce"
DATA = RESOURCES / "data" / NS
LANG = RESOURCES / "assets" / NS / "lang"


def cost(sun: int, cooldown: int) -> Dict[str, object]:
    return {"resources": {f"{NS}:sun": sun}, "cooldown": cooldown}


def shooter_plant(plant_id: str, group: str, sun: int, cooldown: int, interval: int,
                  shots: List[Dict[str, object]], sound: Optional[str] = None,
                  health: int = 300) -> Dict[str, object]:
    definition: Dict[str, object] = {
        "id": f"{NS}:{plant_id}",
        "cost": cost(sun, cooldown),
        "health": health,
        "capabilities": [{"type": f"{NS}:shooter", "interval": interval, "shots": shots}],
        "animation_dir": group,
        "texture": f"{NS}:textures/entities/{group}/{plant_id}",
    }
    if sound:
        definition["sounds"] = {"shoot": sound}
    return definition


PLANTS: Dict[str, Dict[str, object]] = {
    "snow_pea": shooter_plant(
        "snow_pea", "plant/attacker", 175, 450, 90,
        [{"projectile": f"{NS}:snow_pea", "damage": 20, "count": 1}],
        f"{NS}:sfx/plant/shoot_pea"),
    # Two peas per volley is the whole difference from a peashooter; no new capability.
    "repeater": shooter_plant(
        "repeater", "plant/attacker", 200, 450, 90,
        [{"projectile": f"{NS}:pea", "damage": 20, "count": 2}],
        f"{NS}:sfx/plant/shoot_pea"),
    # Three lanes at once: row_offset -1/0/+1, but the volley only fires when one of the
    # three actually holds a zombie, which is what ``rows`` on the first shot expresses.
    "threepeater": shooter_plant(
        "threepeater", "plant/attacker", 325, 450, 90,
        [
            {"projectile": f"{NS}:pea", "damage": 20, "count": 1, "row_offset": -1, "rows": 2},
            {"projectile": f"{NS}:pea", "damage": 20, "count": 1, "row_offset": 0},
            {"projectile": f"{NS}:pea", "damage": 20, "count": 1, "row_offset": 1},
        ],
        f"{NS}:sfx/plant/shoot_pea"),
    # The rebound shot is the same pea with ``backward`` set: the projectile carries the
    # direction, so one definition serves both halves of the volley.
    "split_pea": shooter_plant(
        "split_pea", "plant/attacker", 125, 450, 90,
        [
            {"projectile": f"{NS}:pea", "damage": 20, "count": 1},
            {"projectile": f"{NS}:pea", "damage": 20, "count": 1, "backward": True},
        ],
        f"{NS}:sfx/plant/shoot_pea"),
    "gatling_pea": shooter_plant(
        "gatling_pea", "plant/attacker", 250, 450, 90,
        [{"projectile": f"{NS}:pea", "damage": 20, "count": 4}],
        f"{NS}:sfx/plant/shoot_pea"),
    "cactus": shooter_plant(
        "cactus", "plant/attacker", 125, 450, 90,
        [{"projectile": f"{NS}:cactus_spike", "damage": 20, "count": 1}],
        f"{NS}:sfx/plant/shoot_pea"),
    "cabbage_pult": shooter_plant(
        "cabbage_pult", "plant/attacker", 100, 450, 170,
        [{"projectile": f"{NS}:cabbage", "damage": 40, "count": 1}],
        f"{NS}:sfx/plant/throw"),
    "melon_pult": shooter_plant(
        "melon_pult", "plant/attacker", 300, 450, 130,
        [{"projectile": f"{NS}:melon", "damage": 80, "count": 1}],
        f"{NS}:sfx/plant/throw"),
    "winter_melon": shooter_plant(
        "winter_melon", "plant/attacker", 200, 450, 130,
        [{"projectile": f"{NS}:winter_melon", "damage": 80, "count": 1}],
        f"{NS}:sfx/plant/throw"),
    # The night's sun: cheap, and the reason a level with no sky sun is playable at all. The
    # original's small form (15 per harvest); its grown form is art this version does not use
    # yet, so the amount does not step up.
    "sun_shroom": {
        "id": f"{NS}:sun_shroom",
        "cost": cost(25, 300),
        "health": 300,
        "capabilities": [
            {"type": f"{NS}:nocturnal"},
            {"type": f"{NS}:producer", "resource": f"{NS}:sun", "amount": 15, "every": 1440},
        ],
        "animation_dir": "plant/producer",
        "texture": f"{NS}:textures/entities/plant/producer/sun_shroom",
    },
    # The mushroom: free, weak, and short-ranged - the spore dies after three cells, which
    # is the ``range`` on the shot (the same number the shooter uses to decide it has a
    # target). Nocturnal, so in daylight it sleeps until a coffee bean wakes it.
    "puff_shroom": {
        "id": f"{NS}:puff_shroom",
        "cost": cost(0, 300),
        "health": 300,
        "capabilities": [
            {"type": f"{NS}:nocturnal"},
            {"type": f"{NS}:shooter", "interval": 90, "shots": [
                {"projectile": f"{NS}:puff", "damage": 20, "count": 1, "range": 3.0}]},
        ],
        "sounds": {"shoot": f"{NS}:sfx/plant/puff"},
        "animation_dir": "plant/attacker",
        "texture": f"{NS}:textures/entities/plant/attacker/puff_shroom",
    },
    # The ash line: a timed fuse that detonates on its own. Jalapeno burns its whole row,
    # which is the same blast with a wide, shallow radius.
    "jalapeno": {
        "id": f"{NS}:jalapeno",
        "cost": cost(125, 3000),
        "health": 100,
        "capabilities": [{
            "type": f"{NS}:explosive",
            "trigger": "timed",
            "fuse_ticks": 60,
            "radius": 4.5,
            "damage": 1800,
        }],
        "sounds": {"explode": f"{NS}:sfx/plant/jalapeno"},
        "animation_dir": "plant/special",
        "texture": f"{NS}:textures/entities/plant/special/jalapeno",
    },
    "doom_shroom": {
        "id": f"{NS}:doom_shroom",
        "cost": cost(125, 3000),
        "health": 100,
        "capabilities": [
            {"type": f"{NS}:nocturnal"},
            {
                "type": f"{NS}:explosive",
                "trigger": "timed",
                "fuse_ticks": 90,
                "radius": 3.5,
                "damage": 1800,
            },
        ],
        "sounds": {"explode": f"{NS}:sfx/plant/doomshroom"},
        "animation_dir": "plant/special",
        "texture": f"{NS}:textures/entities/plant/special/doom_shroom",
    },
    # Proximity like the potato mine: it arms, waits for something to step on it, then
    # comes down. Reusing the mine's trigger is what keeps this from needing new code.
    "squash": {
        "id": f"{NS}:squash",
        "cost": cost(50, 1800),
        "health": 300,
        "capabilities": [{
            "type": f"{NS}:explosive",
            "trigger": "proximity",
            "fuse_ticks": 60,
            "radius": 0.8,
            "damage": 1800,
            "trigger_range": 0.6,
        }],
        "sounds": {"explode": f"{NS}:sfx/plant/squash_hmm"},
        "animation_dir": "plant/special",
        "texture": f"{NS}:textures/entities/plant/special/squash",
    },
}

PROJECTILES: Dict[str, Dict[str, object]] = {
    # The snow pea is the pea with a slow attached; the status carries its own numbers.
    "snow_pea": {
        "id": f"{NS}:snow_pea",
        "layer": "ground",
        "capabilities": [
            {"type": f"{NS}:linear", "speed": 2.0},
            {"type": f"{NS}:status", "effects": [
                {"status": "slow", "ticks": 240, "magnitude": 0.5}]},
        ],
        "sounds": {"impact": f"{NS}:sfx/plant/snow_pea_sparkles"},
        "texture": f"{NS}:textures/entities/projectile/pea",
    },
    "cactus_spike": {
        "id": f"{NS}:cactus_spike",
        "layer": "ground",
        "capabilities": [{"type": f"{NS}:linear", "speed": 2.4}],
        "sounds": {"impact": f"{NS}:sfx/projectile/hit"},
        "texture": f"{NS}:textures/entities/projectile/butter",
    },
    # The spore. Its short reach is not here: it belongs to the shot that fires it
    # (``range`` on the plant's ``shots`` entry), so any plant can fire a bounded puff.
    "puff": {
        "id": f"{NS}:puff",
        "layer": "ground",
        "capabilities": [{"type": f"{NS}:linear", "speed": 2.0}],
        "sounds": {"impact": f"{NS}:sfx/projectile/hit"},
        "texture": f"{NS}:textures/entities/projectile/puff",
    },
    "cabbage": {
        "id": f"{NS}:cabbage",
        "layer": "air",
        "capabilities": [
            {"type": f"{NS}:arc", "speed": 2.0, "gravity": 9.0},
            {"type": f"{NS}:splash", "radius": 0.7},
        ],
        "sounds": {"impact": f"{NS}:sfx/plant/kernelpult2"},
        "texture": f"{NS}:textures/entities/projectile/cabbage",
    },
    "winter_melon": {
        "id": f"{NS}:winter_melon",
        "layer": "air",
        "capabilities": [
            {"type": f"{NS}:arc", "speed": 2.0, "gravity": 9.0},
            {"type": f"{NS}:splash", "radius": 1.5},
            {"type": f"{NS}:status", "effects": [
                {"status": "slow", "ticks": 240, "magnitude": 0.5}]},
        ],
        "sounds": {"impact": f"{NS}:sfx/plant/melonimpact2"},
        "texture": f"{NS}:textures/entities/projectile/melon",
    },
}

# Card names. The lang key is the MC convention ``<namespace>.<path>``.
NAMES: Dict[str, Dict[str, str]] = {
    "snow_pea": {"en_us": "Snow Pea", "zh_cn": "寒冰射手"},
    "repeater": {"en_us": "Repeater", "zh_cn": "双发射手"},
    "threepeater": {"en_us": "Threepeater", "zh_cn": "三线射手"},
    "split_pea": {"en_us": "Split Pea", "zh_cn": "分裂豌豆"},
    "gatling_pea": {"en_us": "Gatling Pea", "zh_cn": "机枪射手"},
    "cactus": {"en_us": "Cactus", "zh_cn": "仙人掌"},
    "cabbage_pult": {"en_us": "Cabbage-pult", "zh_cn": "卷心菜投手"},
    "melon_pult": {"en_us": "Melon-pult", "zh_cn": "西瓜投手"},
    "winter_melon": {"en_us": "Winter Melon", "zh_cn": "冰西瓜"},
    "jalapeno": {"en_us": "Jalapeno", "zh_cn": "火爆辣椒"},
    "doom_shroom": {"en_us": "Doom-shroom", "zh_cn": "毁灭菇"},
    "puff_shroom": {"en_us": "Puff-shroom", "zh_cn": "小喷菇"},
    "sun_shroom": {"en_us": "Sun-shroom", "zh_cn": "阳光菇"},
    "squash": {"en_us": "Squash", "zh_cn": "窝瓜"},
}

# Card icon per plant. Cards are drawn from the controller art (see
# tools/render_controller_icons.py), and the slot definition points at that PNG.
ICON_DIR = f"{NS}:textures/gui/cards"


def slot_for(plant_id: str) -> Dict[str, object]:
    return {
        "id": f"{NS}:{plant_id}",
        "kind": "plant",
        "content": f"{NS}:{plant_id}",
        "cost": {"resources": {}, "cooldown": 0},
        "icon": f"{ICON_DIR}/{plant_id}",
    }


def write_json(path: Path, data: Dict[str, object], changed: List[str]) -> None:
    text = json.dumps(data, indent=2, ensure_ascii=False) + "\n"
    if path.exists() and path.read_text(encoding="utf-8") == text:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    changed.append(str(path.relative_to(REPO_ROOT)))


def update_lang(locale: str, changed: List[str]) -> None:
    path = LANG / f"{locale}.json"
    data = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
    for plant_id, names in NAMES.items():
        key = f"{NS}.{plant_id}"
        if locale in names:
            data[key] = names[locale]
    # Keep the file in a stable order so a re-run is a no-op.
    ordered = {key: data[key] for key in sorted(data)}
    text = json.dumps(ordered, indent=2, ensure_ascii=False) + "\n"
    if path.exists() and path.read_text(encoding="utf-8") == text:
        return
    path.write_text(text, encoding="utf-8")
    changed.append(str(path.relative_to(REPO_ROOT)))


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)

    changed: List[str] = []
    for plant_id, definition in PLANTS.items():
        write_json(DATA / "plants" / f"{plant_id}.json", definition, changed)
        write_json(DATA / "slots" / f"{plant_id}.json", slot_for(plant_id), changed)
    for projectile_id, definition in PROJECTILES.items():
        write_json(DATA / "projectiles" / f"{projectile_id}.json", definition, changed)
    for locale in ("en_us", "zh_cn"):
        update_lang(locale, changed)

    if args.dry_run:
        print(f"would write {len(changed)} file(s)")
        for path in changed:
            print("  ", path)
        return 0
    print(f"wrote {len(changed)} file(s)")
    for path in changed:
        print("  ", path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
