#!/usr/bin/env python3
"""Bake the original's unfinished lawns into backdrop copies for 1-1 .. 1-3.

The original's first three Day levels are played on a lawn that is only partly finished:
`background1unsodded` is bare dirt and the finished rows were drawn on top of it. 1-1 has one
finished row, 1-2 and 1-3 have three.

The rows come from `background1.png` - the backdrop 1-4 and later use, whose lawn is finished.
It is the same art as the rip's separate row sprites (`refer/im7/images/sod1row.jpg`,
`sod3row.*`) but without their transparent black margin, so copying needs no resampling, no
colour work, and none of the dark halo a hand-blended sprite leaves.

Two things decide the shape of the result:

* **Horizontally the band follows the art, not the board.** The finished backdrop's grass runs
  a little past the playable lawn on both sides (a few pixels into the house side, about twenty
  over the road-side curb). Cutting at the board's edge (256..976) slices the grass off square
  at both ends; the band is taken from the measured grass span instead.
* **Vertically the band is taken a `VERTICAL_REACH` past each row boundary and faded out over
  that distance.** The finished backdrop's rows are grass from edge to edge, so there is no
  grass-to-dirt edge in the art to copy, and stopping at the row boundary would lay a straight
  cut across the dirt. The reach is what turns that cut into the soft edge the original's own
  sprite has - the rip's row sprites fade over roughly the same distance.

The rows themselves are untouched (the copy is pixel for pixel), so the grass keeps its own
texture, its darker rim and its pebbles.

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

# The 1400x600 backdrop's lawn: five 100px rows starting at y=80.
LAWN_Y, ROW_HEIGHT = 80, 100
# The art's own grass span, measured on a lawn row of `background1.png` as the longest run of
# grass. Wider than the playable lawn (256..976) on purpose: that is where the grass ends.
GRASS_X0, GRASS_X1 = 251, 996
# How far past a row boundary the band runs before it has faded out, and the same for the two
# horizontal ends. Long enough to read as a transition rather than a line, short enough that the
# grass does not turn into a glow.
VERTICAL_REACH = 24
HORIZONTAL_REACH = 6

OUTPUTS = {
    "background1_1row.png": 1,
    "background1_3row.png": 3,
}


def ramp(distance: float, length: float) -> float:
    """A raised-cosine falloff, the shape the art's own grass edges have."""
    return 0.5 - 0.5 * math.cos(math.pi * distance / length)


def bake(finished_rows: int) -> np.ndarray:
    sodded = np.asarray(Image.open(LEVEL_ART / "background1.png").convert("RGB")).astype(float)
    unsodded = np.asarray(Image.open(LEVEL_ART / "background1unsodded.png").convert("RGB")).astype(float)

    first = (5 - finished_rows) // 2
    row_top = LAWN_Y + first * ROW_HEIGHT
    row_bottom = row_top + finished_rows * ROW_HEIGHT
    top, bottom = row_top - VERTICAL_REACH, row_bottom + VERTICAL_REACH
    left, right = GRASS_X0 - HORIZONTAL_REACH, GRASS_X1 + HORIZONTAL_REACH

    out = unsodded.copy()
    out[top:bottom, left:right] = sodded[top:bottom, left:right]
    # From the cut line (fully transparent) to the row boundary (opaque): inside the rows the
    # copy is untouched, outside it the grass fades into the dirt.
    for i in range(VERTICAL_REACH):
        alpha = ramp(i + 1, VERTICAL_REACH + 1)
        for y in (top + i, bottom - 1 - i):
            out[y, left:right] = out[y, left:right] * alpha + unsodded[y, left:right] * (1 - alpha)
    for i in range(HORIZONTAL_REACH):
        alpha = ramp(HORIZONTAL_REACH - i, HORIZONTAL_REACH)
        for x in (left + i, right - 1 - i):
            out[top:bottom, x] = out[top:bottom, x] * alpha + unsodded[top:bottom, x] * (1 - alpha)
    return np.clip(out, 0, 255).astype(np.uint8)


def main() -> int:
    for name, rows in OUTPUTS.items():
        Image.fromarray(bake(rows)).save(LEVEL_ART / name)
        first = (5 - rows) // 2
        print(f"{name}: {rows} finished row(s) (lawn rows {first}..{first + rows - 1}),"
              f" art x={GRASS_X0 - HORIZONTAL_REACH}..{GRASS_X1 + HORIZONTAL_REACH},"
              f" vertical fade {VERTICAL_REACH}px")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
