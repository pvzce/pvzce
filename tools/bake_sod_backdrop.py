#!/usr/bin/env python3
"""Bake the original's unfinished lawns into backdrop copies for 1-1 .. 1-3.

The original's first three Day levels are played on a lawn that is only partly finished:
`background1unsodded` is bare dirt, and the rows the player may plant on are drawn on top of it.
Level 1-1 has one finished row, 1-2 and 1-3 have three.

The rows are **not** composited from the rip's separate sprites (`refer/im7/images/sod1row.jpg`
and `sod3row.*`): those are the same art with a transparent black margin, and blending them by
hand leaves a dark halo over the dirt. The same rows are already painted, at the right scale and
with the right lighting, in `background1.png` - the backdrop 1-4 and later use - so this script
copies a band of it onto the unsodded dirt instead. No resampling, no colour work.

**The band is cut where the art's own grass ends, not where the board does.** The finished
backdrop's grass runs a little past the playable lawn on both sides (about 8px into the house
side and 20px over the road-side curb), because the original drew the whole sprite; cutting at
the board's edge leaves the grass sliced off square at both ends. The band is therefore taken
from the art's measured grass span, and only the outer few pixels are faded, so the source's own
soft grass-to-dirt edge is what ends up next to the brick.

Run from the repository root:  python3 tools/bake_sod_backdrop.py
Needs Pillow and numpy.
"""

import math
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
LEVEL_ART = (ROOT / "pvzce-game" / "src" / "main" / "resources" / "assets" / "pvzce"
             / "textures" / "gui" / "screen" / "level")

# The 1400x600 backdrop's lawn: nine 80px columns, five 100px rows.
LAWN_Y, ROW_HEIGHT = 80, 100
# The art's own grass span, measured from `background1.png` (longest run of grass on a lawn
# row). It is deliberately wider than the playable lawn, 256..976.
GRASS_X0, GRASS_X1 = 251, 996
# Pixels of dirt kept outside the grass rows, and the length of the fade at every cut edge. The
# rip's row sprites leave 6..12px of dirt around their grass, which is what the source art has
# at each row boundary; the fade is only there so the cut itself is not a hard line.
FRINGE_PX, FADE_PX = 8, 6

OUTPUTS = {
    "background1_1row.png": 1,
    "background1_3row.png": 3,
}


def fade(index: int, length: int) -> float:
    """A raised-cosine ramp, the shape the art's own grass edges have."""
    return 0.5 - 0.5 * math.cos(math.pi * (index + 1) / (length + 1))


def bake(finished_rows: int) -> np.ndarray:
    sodded = np.asarray(Image.open(LEVEL_ART / "background1.png").convert("RGB")).astype(float)
    unsodded = np.asarray(Image.open(LEVEL_ART / "background1unsodded.png").convert("RGB")).astype(float)

    first = (5 - finished_rows) // 2
    top = LAWN_Y + first * ROW_HEIGHT - FRINGE_PX
    bottom = LAWN_Y + (first + finished_rows) * ROW_HEIGHT + FRINGE_PX
    left = GRASS_X0 - FRINGE_PX
    right = GRASS_X1 + 1 + FRINGE_PX

    out = unsodded.copy()
    out[top:bottom, left:right] = sodded[top:bottom, left:right]
    for i in range(FADE_PX):
        alpha = fade(i, FADE_PX)
        for y in (top + i, bottom - 1 - i):
            out[y, left:right] = out[y, left:right] * alpha + unsodded[y, left:right] * (1 - alpha)
        for x in (left + i, right - 1 - i):
            out[top:bottom, x] = out[top:bottom, x] * alpha + unsodded[top:bottom, x] * (1 - alpha)
    return np.clip(out, 0, 255).astype(np.uint8)


def main() -> int:
    for name, rows in OUTPUTS.items():
        Image.fromarray(bake(rows)).save(LEVEL_ART / name)
        first = (5 - rows) // 2
        print(f"{name}: {rows} finished row(s) (lawn rows {first}..{first + rows - 1}),"
              f" art x={GRASS_X0 - FRINGE_PX}..{GRASS_X1 + FRINGE_PX}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
