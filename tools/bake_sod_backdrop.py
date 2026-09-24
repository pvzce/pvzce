#!/usr/bin/env python3
"""Bake the original's unfinished lawns into backdrop copies for 1-1 .. 1-3.

The original's first three Day levels are played on a lawn that is only partly finished:
`background1unsodded` is bare dirt and the finished rows were drawn on top of it. 1-1 has one
finished row, 1-2 and 1-3 have three.

This does not cut a rectangle out of anything. It composites the finished backdrop's own grass
onto the bare dirt, pixel by pixel, using the art itself as the mask:

* the rows come from `background1.png` (the backdrop 1-4 and later use, whose lawn is finished),
  so there is no resampling, no colour work and none of the transparent black margin that makes
  the rip's separate row sprites (`refer/im7/images/sod1row.jpg`, `sod3row.*`) leave a dark halo
  when they are blended by hand;
* **which pixels count as grass is measured from that art** - a pixel is grass when its green is
  clearly above both its red and its blue. That keeps the grass's own rim (the darker green line
  along a row's top and bottom) and its own texture, and it means the horizontal cut follows the
  art's grass all the way onto the road-side curb instead of stopping at the playable lawn;
* every pixel that is not grass keeps the unsodded backdrop, so nothing outside the rows is
  touched at all.

There is deliberately no falloff at the top and bottom of a row: the finished backdrop's rows are
grass from edge to edge, so a fade there would be inventing an edge the art does not have. What
the original's grass edge looks like is what this reproduces.

Run from the repository root:  python3 tools/bake_sod_backdrop.py
Needs Pillow and numpy.
"""

from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
LEVEL_ART = (ROOT / "pvzce-game" / "src" / "main" / "resources" / "assets" / "pvzce"
             / "textures" / "gui" / "screen" / "level")

# The 1400x600 backdrop's lawn: five 100px rows starting at y=80.
LAWN_Y, ROW_HEIGHT, LAWN_ROWS = 80, 100, 5
# How much greener than the other channels a pixel has to be to count as grass, and the ramp
# width that softens the mask's own boundary. The rim along a row's top and bottom is a darker
# green (about 20 above red), so the threshold sits below it; the ramp is what keeps the mask
# from drawing a one-pixel outline of itself.
GRASS_MIN_GREEN_LEAD, GRASS_MASK_RAMP = 8.0, 24.0

OUTPUTS = {
    "background1_1row.png": 1,
    "background1_3row.png": 3,
}


def grass_mask(sodded: np.ndarray) -> np.ndarray:
    red, green, blue = sodded[:, :, 0], sodded[:, :, 1], sodded[:, :, 2]
    lead = np.minimum(green - red, green - blue)
    return np.clip((lead - GRASS_MIN_GREEN_LEAD) / GRASS_MASK_RAMP, 0.0, 1.0)


def bake(finished_rows: int) -> np.ndarray:
    sodded = np.asarray(Image.open(LEVEL_ART / "background1.png").convert("RGB")).astype(float)
    unsodded = np.asarray(Image.open(LEVEL_ART / "background1unsodded.png").convert("RGB")).astype(float)

    first = (5 - finished_rows) // 2
    top = LAWN_Y + first * ROW_HEIGHT
    bottom = top + finished_rows * ROW_HEIGHT

    mask = np.zeros(sodded.shape[:2])
    mask[top:bottom, :] = 1.0
    mask = (mask * grass_mask(sodded))[:, :, None]
    return np.clip(sodded * mask + unsodded * (1.0 - mask), 0, 255).astype(np.uint8)


def main() -> int:
    for name, rows in OUTPUTS.items():
        Image.fromarray(bake(rows)).save(LEVEL_ART / name)
        first = (5 - rows) // 2
        print(f"{name}: {rows} finished row(s), lawn rows {first}..{first + rows - 1},"
              f" grass taken from background1.png by its own colour")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
