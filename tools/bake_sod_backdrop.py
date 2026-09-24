#!/usr/bin/env python3
"""Bake the original's unfinished lawns into backdrop copies for 1-1 .. 1-3.

The original's first three Day levels are played on a lawn that is only partly finished:
`background1unsodded` is bare dirt, and the rows the player may plant on were drawn on top of
it. Level 1-1 has one finished row, 1-2 and 1-3 have three.

Two things about that art decide how this script works:

* **The rows are already in the repository**, painted at the right pixel scale in
  `background1.png` - the backdrop levels 1-4 and later use, whose whole lawn is finished.
  Copying a band of it onto the unsodded dirt reproduces the original exactly, lighting and
  pebbles included, without rescaling anything.
* **The rip's separate row sprites** (`refer/im7/images/sod1row.jpg` + `sod1row_.png`,
  `sod3row.*`) are the same art with a transparent black margin around it. Compositing those by
  hand is what this script used to do, and it never quite matched: the margin has to be faded
  out or it draws a dark halo over the dirt. Cutting the same rows out of the finished backdrop
  has no such problem.

The levels then point at the baked image and keep hiding the grass pass, exactly like 1-4 and
later hide it over art that already has a lawn.

Run from the repository root:  python3 tools/bake_sod_backdrop.py
Needs Pillow and numpy.
"""

from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
LEVEL_ART = (ROOT / "pvzce-game" / "src" / "main" / "resources" / "assets" / "pvzce"
             / "textures" / "gui" / "screen" / "level")

# The lawn region of the 1400x600 backdrop: nine 80px columns and five 100px rows.
LAWN_X, LAWN_Y, LAWN_WIDTH, LAWN_HEIGHT = 256, 80, 720, 500
ROW_HEIGHT = LAWN_HEIGHT // 5
# How many pixels of the band's own edge are faded into the dirt below and above it. The
# finished backdrop has a soft grass-to-dirt line along its lawn's edge; without this the band
# ends on a hard edge wherever it is cut short of the art's own boundary.
EDGE_FEATHER_PX = 4

# Each output and the rows of the lawn it finishes. The rows are the centre of the five - the
# same place `LevelStage.board` puts a board that is not five rows tall - so the finished grass
# is what the player can plant on.
OUTPUTS = {
    "background1_1row.png": 1,
    "background1_3row.png": 3,
}


def bake(finished_rows: int) -> np.ndarray:
    sodded = np.asarray(Image.open(LEVEL_ART / "background1.png").convert("RGB")).astype(float)
    unsodded = np.asarray(Image.open(LEVEL_ART / "background1unsodded.png").convert("RGB")).astype(float)

    first = (5 - finished_rows) // 2
    top = LAWN_Y + first * ROW_HEIGHT
    bottom = top + finished_rows * ROW_HEIGHT
    columns = slice(LAWN_X, LAWN_X + LAWN_WIDTH)

    out = unsodded.copy()
    out[top:bottom, columns] = sodded[top:bottom, columns]
    for i in range(EDGE_FEATHER_PX):
        alpha = (i + 1) / (EDGE_FEATHER_PX + 1)
        for y in (top + i, bottom - 1 - i):
            out[y, columns] = (out[y, columns] * alpha
                               + unsodded[y, columns] * (1.0 - alpha))
    return np.clip(out, 0, 255).astype(np.uint8)


def main() -> int:
    for name, rows in OUTPUTS.items():
        Image.fromarray(bake(rows)).save(LEVEL_ART / name)
        first = (5 - rows) // 2
        print(f"{name}: {rows} finished row(s), lawn rows {first}..{first + rows - 1}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
