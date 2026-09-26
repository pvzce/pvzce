#!/usr/bin/env python3
"""Composes the four ZomBotany zombies' animations from the ordinary zombie plus a plant head.

The original's ZomBotany zombies are the ordinary zombie body with the head **replaced** by a
plant - and that art is **not in `refer/`**: the 146 reanims in this repository include every
other zombie the game ships and none of the four. What *is* here is both halves:

* the converted `zombie/basic/basic_zombie.json`, whose bones the client already draws with the
  parent transform applied (`ControllerPlayback.worldTransforms` multiplies a bone by its
  parent's), and
* the plant heads, as the part sheets the plants' own animations are built from.

So each ZomBotany zombie is the basic body with one extra bone: `plant_head`, parented to
`head`, carrying one plant part. That is not a cheap imitation of the original - it is the same
composition, done in the engine's own terms, and it costs one file per zombie instead of one
redrawn body.

What the script does, exactly:

1. reads `basic_zombie.json` and copies it four times;
2. appends a `plant_head` bone whose single part is the plant head texture, sized in cells the
   same way every other part is;
3. hides every bone the zombie draws its own face with (`head`, `hair`, `jaw`, `tongue`) in every
   clip, and gives `plant_head` a `visible` and an `alpha` track **copied from the original
   `head` track** - so a head that pops off on death takes the plant with it, and a corpse that
   fades does the same;
4. writes `animations/zombie/zombotany/<name>.json`.

The plant is the zombie's head, not a second one growing out of it: the ordinary head is hidden
rather than covered, because the art under it is a full face - an earlier version left the eyes
and jaw visible under the plant and read as two heads.

No per-frame transform is written for `plant_head` at all: it inherits the head's, which is the
whole reason it is a child. Its offset seats the plant's bottom at the neck, where the hidden
head's bottom was; see `ZOMBIE_HEAD_HEIGHT` below.

Run from the repository root:

    python3 tools/make_zombotany_assets.py
"""

from __future__ import annotations

import json
import shutil
import struct
from pathlib import Path
from typing import Dict, List

REPO_ROOT = Path(__file__).resolve().parents[1]
ASSETS = REPO_ROOT / "pvzce-game/src/main/resources/assets/pvzce"
ANIMATIONS = ASSETS / "animations"
TEXTURES = ASSETS / "textures/entities"

SOURCE = ANIMATIONS / "zombie/basic/basic_zombie.json"
OUT_DIR = ANIMATIONS / "zombie/zombotany"

# One entry per zombie: the plant whose head it wears, and which of that plant's parts is the
# head. The part names are the converted files' own, so they cannot drift from the plant.
ZOMBIES: Dict[str, Dict[str, str]] = {
    "zombotany_pea_zombie": {
        "plant": "plant/attacker/pea_shooter",
        "part": "peashooter_head.png",
    },
    "zombotany_wallnut_zombie": {
        # The wall-nut has one part and it is the whole nut, which is what the original's
        # Wall-nut Zombie wears.
        "plant": "plant/defense/wall_nut",
        "part": "body.png",
    },
    "zombotany_gatling_zombie": {
        "plant": "plant/attacker/gatling_pea",
        "part": "head.png",
    },
    "zombotany_jalapeno_zombie": {
        # The jalapeno's body IS its head: it is a pepper with a face.
        "plant": "plant/special/jalapeno",
        "part": "body.png",
    },
}

# How the plant head sits where the zombie's own head was, in the head bone's own local cells.
#
# The zombie's head is 53x48 px at 0.01 cells/px, i.e. 0.395 x 0.358 cells, centred on its bone's
# origin; its bottom edge is therefore 0.179 cells below that origin. The original's plant-headed
# zombies have **no zombie head left** - the plant *is* the head - so the zombie head, hair, jaw
# and tongue are hidden (see HIDDEN_HEAD_BONES) and each plant head is placed with its own bottom
# at the neck, which is where the head's bottom was. The vertical offset is therefore
# "half the plant minus half the head", computed per zombie from the plant part's real pixel size
# rather than written down: a pea head and a wall-nut are different heights and a single constant
# made the tall ones float.
PLANT_X = -0.04
ZOMBIE_HEAD_HEIGHT = 0.357786

# How big a plant head is drawn, as a fraction of its own pixel size in cells. 0.01 is the
# project's pixels-per-cell (see `tools/reanim_to_pvzce.py`); 0.62 makes a pea head a little
# smaller than the zombie's own head, which is how the original draws it.
PIXELS_PER_CELL = 0.01
HEAD_SCALE = 0.62

# The head part's z, above every part of the head itself (the highest is the jaw at 13).
PLANT_HEAD_Z = 20

# Every bone the ordinary zombie uses to draw its own head. The plant head replaces all of them;
# hiding only `head` left the jaw and tongue floating under the plant, which is what the first
# version did and what the report saw.
HIDDEN_HEAD_BONES = ("head", "hair", "jaw", "tongue")


def png_size(path: Path) -> tuple:
    header = path.read_bytes()[:24]
    if header[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit(f"not a PNG: {path}")
    return struct.unpack(">II", header[16:24])


def build(name: str, spec: Dict[str, str]) -> None:
    data = json.loads(SOURCE.read_text(encoding="utf-8"))
    plant_dir = TEXTURES / spec["plant"]
    part_path = plant_dir / spec["part"]
    if not part_path.is_file():
        raise SystemExit(f"missing plant part: {part_path}")
    width, height = png_size(part_path)
    texture_name = part_path.stem

    # The part is *copied* into the zombie's own texture directory rather than referenced from
    # the plant's. A controller model names its parts by texture id and the client resolves them
    # per file: a zombie that pointed into `plant/attacker/pea_shooter/` would break the day that
    # plant's conversion renamed a part, and the two files are meant to be independent.
    target_dir = TEXTURES / "zombie/zombotany" / name
    target_dir.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(part_path, target_dir / f"{texture_name}.png")

    plant_height_cells = height * PIXELS_PER_CELL * HEAD_SCALE
    data["model"]["bones"].append({
        "name": "plant_head",
        "parent": "head",
        "pivot": [0.0, 0.0],
        "parts": [{
            "texture": f"pvzce:textures/entities/zombie/zombotany/{name}/{texture_name}",
            "uv": [0, 0, width, height],
            "size": [round(width * PIXELS_PER_CELL * HEAD_SCALE, 6),
                     round(plant_height_cells, 6)],
            "offset": [PLANT_X, round(plant_height_cells / 2 - ZOMBIE_HEAD_HEIGHT / 2, 6)],
            "z": PLANT_HEAD_Z,
        }],
    })

    # A `shoot` clip, copied from `eat`.
    #
    # The basic zombie's file has no shooting pose - it has never needed one - and a state whose
    # clip is missing falls back to `idle` with nothing but a log line (see `踩坑清单.md` item 2),
    # so an armed zombie would stand perfectly still while firing. `eat` is the right source
    # because it is already "mouth open, leaning forward": the difference between biting something
    # and spitting a pea at it is which way the head is turned, and neither the art nor the player
    # can tell at this size.
    if "shoot" not in data["animations"]:
        eat = json.loads(json.dumps(data["animations"]["eat"]))
        eat["loop"] = False
        eat["on_end"] = "walk"
        eat["transition"] = 0.1
        data["animations"]["shoot"] = eat

    model_bone_names = {bone.get("name") for bone in data["model"]["bones"]}
    for clip_name, clip in data["animations"].items():
        head = clip["bones"].get("head")
        if head is None:
            head_visible = {"0.0": True}
            head_alpha = {"0.0": 1.0}
        else:
            # Read *before* hiding: the plant head rides the head's own visibility and alpha, so a
            # head that pops off on death takes the plant with it, and a corpse that fades fades as
            # one object.
            head_visible = head.get("visible", {"0.0": True})
            head_alpha = head.get("alpha", {"0.0": 1.0})
        # A clip with no track for a bone draws it at its rest pose, so every hidden head bone
        # needs an explicit "not drawn" track in *every* clip, not only the ones that already
        # animate it (the jaw opens while eating, and the head is absent from that track only
        # because it does not move).
        #
        # `setdefault` and not a fresh dict: the head's translation track is what carries the
        # plant head too, because the plant is a child of the head. Replacing the head's track
        # instead of editing its `visible` dropped the plant to the entity's anchor - it was
        # drawn at the zombie's feet.
        for bone in HIDDEN_HEAD_BONES:
            if bone in model_bone_names:
                clip["bones"].setdefault(bone, {})["visible"] = {"0.0": False}
        clip["bones"]["plant_head"] = {"visible": dict(head_visible), "alpha": dict(head_alpha)}

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    output = OUT_DIR / f"{name}.json"
    output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{name}: {width}x{height}px from {spec['plant']}/{spec['part']} -> {output}")


def main() -> None:
    for name, spec in ZOMBIES.items():
        build(name, spec)


if __name__ == "__main__":
    main()
