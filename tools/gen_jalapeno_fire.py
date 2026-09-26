#!/usr/bin/env python3
"""Writes the jalapeno's row of fire, from the original's own flame animation.

The reported problem: "火爆辣椒的特效错误，不是樱桃炸弹的，而应该是当前整行的火焰". It did the
cherry bomb's {@code pow} cloud, because that is what {@code ExplosiveCapability} defaults to and
nothing had given the jalapeno a composition of its own.

The original's flame is `refer/anim/fire.reanim` - a 13-frame loop whose frames swap through
`fire1.png`, `fire2.png`, ... - which is the same animation for the *whole row*, spawned once per
cell. The runtime here draws one sprite per particle and animates it through a frame list, so this
copies the thirteen PNGs into the particle texture directory in play order and writes the
definition that walks them.

    python3 tools/gen_jalapeno_fire.py
"""

from __future__ import annotations

import json
import shutil
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
SOURCE = REPO_ROOT / "refer" / "anim"
TEXTURES = (REPO_ROOT / "pvzce-game" / "src" / "main" / "resources" / "assets" / "pvzce"
            / "textures" / "particles" / "effect")
DATA = (REPO_ROOT / "pvzce-game" / "src" / "main" / "resources" / "data" / "pvzce"
        / "particles" / "effect")

#: The flame's own frame order, from `fire.reanim`'s `Layer 1` track: it swaps images in this
#: sequence, one per frame, and then holds. The `_1`/`_2`/`_3` variants are the other flames the
#: file carries for its second and third copies of the row.
FRAMES = ("fire1", "fire2", "fire3", "fire4", "fire4b", "fire5", "fire5b",
          "fire6", "fire6b", "fire7", "fire7b", "fire8")
#: The file runs at 12fps (its own `<fps>` element).
FPS = 12.0
#: The sprite is 113x87 px at the reference scale of 0.010175 cells per pixel, which is a little
#: wider than one cell - the original overlaps them along the row.
SCALE = 1.13
#: How much of one cell the flame covers when the row is tiled, in cells. Under the sprite's own
#: width so consecutive flames overlap and read as one sheet of fire.
STEP = 0.75
#: How long the fire burns for, and how much of that the fade takes. The clip is ~1.1s; the blast's
#: own linger is 65 ticks (1.08s), which is the tick the plant goes.
LIFE = 1.15


def main() -> None:
    TEXTURES.mkdir(parents=True, exist_ok=True)
    DATA.mkdir(parents=True, exist_ok=True)
    names = []
    for index, frame in enumerate(FRAMES, start=1):
        source = SOURCE / f"{frame}.png"
        if not source.is_file():
            raise SystemExit(f"Missing flame frame: {source}")
        target = TEXTURES / f"jalapeno_fire_{index}.png"
        shutil.copyfile(source, target)
        names.append(f"pvzce:textures/particles/effect/jalapeno_fire_{index}")

    definition = {
        "id": "pvzce:jalapeno_fire",
        "look": {
            "texture": names[0],
            "frames": names[1:],
            "scale": SCALE,
            "aspect": 87 / 113,
            "life": LIFE,
            # The clip is a loop of its own; one pass is what the blast lasts, so the last frame is
            # held rather than the animation restarting under the fade.
            "frames_per_second": FPS,
            "loop": False,
            "alpha_curve": [[0.0, 0.85], [0.25, 1.0], [0.7, 0.9], [1.0, 0.0]],
        },
        # The fire is fire: it rises while it burns, and each tongue gets its own nudge so the row
        # is not one flame repeated at even spacing.
        "motion": {
            "speed": 0.35,
            "speed_spread": 0.2,
            "angle": 90.0,
            "angle_spread": 25.0,
        },
        "count": 1,
    }
    out = DATA / "jalapeno_fire.json"
    out.write_text(json.dumps(definition, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {out.relative_to(REPO_ROOT)} and {len(names)} textures "
          f"(tile step {STEP} cells)")


if __name__ == "__main__":
    main()
