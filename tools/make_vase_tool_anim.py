#!/usr/bin/env python3
"""Writes the vase tool's cursor animation from the flower pot's own art.

The `pvzce:vase` tool is the flower pot's picture on a card (`tools/vase.json` says so:
`texture: textures/gui/cards/flower_pot`), so the thing the player carries while the card is
selected should be the same pot - and the pot already exists as a converted controller,
`animations/plant/environment/flower_pot.json`, with its parts, its pivots and its idle bob.

What this script adds is the half a *plant* does not need:

* a **root** bone entry, which the plant model has none of (a plant is drawn at its cell, so
  it never has to be moved as a whole) - the cursor draws the tool near the pointer and the
  scale here is what keeps a 0.91x0.76-cell pot inside the tool cursor's box;
* an **`attack`** clip. A tool cursor plays `attack` on the click that used it
  (`InGameScreen.swingDefaultToolCursor`), and a controller with no such clip falls back to
  `idle` *silently* - the click would look like nothing happened. The gesture is "put it
  down": the whole pot dips and comes back, which is the one motion this tool has.

Run from the repository root:

    python3 tools/make_vase_tool_anim.py
"""

from __future__ import annotations

import json
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
ASSETS = REPO_ROOT / "pvzce-game/src/main/resources/assets/pvzce/animations"
SOURCE = ASSETS / "plant/environment/flower_pot.json"
TARGET = ASSETS / "tool/vase.json"

# The pot's own model is 0.907 x 0.76 cells - the size it is drawn at on the lawn. The tool
# cursor's box is about half a cell (the mallet is 0.462 x 0.5, the watering can 0.538 x 0.5),
# so the whole pot is scaled down rather than the model's numbers being edited: the art, the
# pivots and the plant's own animation all stay exactly as converted.
ROOT_SCALE = 0.66
# How far the pot dips when the player puts a vase down, in the model's own units.
DIP = -0.13
# The dip's beats: down, hold, up. Length is the last one.
ATTACK_KEYS = [(0.0, 0.0), (0.12, DIP), (0.3, DIP), (0.5, 0.0)]


def root_track(translations) -> dict:
    keys = {str(t): [0.0, y] for t, y in translations}
    return {
        "translation": keys,
        "rotation": {"0.0": [0.0, 0.0, 0.0]},
        "scale": {"0.0": [ROOT_SCALE, ROOT_SCALE]},
        "visible": {"0.0": True},
    }


def frozen(track: dict) -> dict:
    """One bone's tracks reduced to their first keyframe, so it holds still through `attack`."""
    frozen_track = {}
    for name, keys in track.items():
        first = sorted(keys.items(), key=lambda item: float(item[0]))[0]
        frozen_track[name] = {first[0]: first[1]}
    return frozen_track


def main() -> None:
    source = json.loads(SOURCE.read_text(encoding="utf-8"))
    idle = source["animations"]["idle"]

    model = source["model"]
    model["bones"] = [dict(bone) for bone in model["bones"]]

    idle_bones = {name: dict(tracks) for name, tracks in idle["bones"].items()}
    idle_bones["root"] = root_track([(0.0, 0.0)])
    attack_bones = {name: frozen(tracks) for name, tracks in idle["bones"].items()}
    attack_bones["root"] = root_track(ATTACK_KEYS)

    vase = {
        "type": "controller",
        "model": model,
        "animations": {
            # The plant's own one-second bob, kept: a pot held under the pointer that breathes
            # exactly like the pot on the lawn is the same object, which is the point.
            "idle": {
                "animation_length": idle.get("animation_length", 1.0),
                "loop": True,
                "transition": idle.get("transition", 0.1),
                "bones": idle_bones,
            },
            "attack": {
                "animation_length": ATTACK_KEYS[-1][0],
                "loop": False,
                "on_end": "idle",
                "transition": 0.05,
                "bones": attack_bones,
            },
        },
    }
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_text(json.dumps(vase, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {TARGET.relative_to(REPO_ROOT)} "
          f"({len(model['bones'])} bones, clips {sorted(vase['animations'])})")


if __name__ == "__main__":
    main()
