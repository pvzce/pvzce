#!/usr/bin/env python3
"""The tile a flooded lawn cell is drawn with.

`pvzce:water` is a *liquid* element: it has no per-cell art at all, because the pool draws its
whole surface in one shader pass on the stage's own basin frame (`LevelStage.LiquidFrame`). That
is right for a pool and wrong for a lawn a mutation has just flooded - a water cell outside the
basin is drawn on the basin, so the flooded rows look like grass.

So the flood mutation places a second element, `pvzce:flood_water`: the same `surface` class
(which is what the rules read - land plants will not go there, and a walker in it is swimming),
with ordinary per-cell art instead of a liquid. This tool makes that art: one 128x128 tile of the
pool's own water, cropped from `water_surface.png` and darkened slightly at the edges so a field
of the tiles still reads as separate cells rather than as one flat colour.

Run from the repository root:

    python3 tools/gen_flood_water.py
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

REPO_ROOT = Path(__file__).resolve().parents[1]
TEXTURES = REPO_ROOT / "pvzce-game/src/main/resources/assets/pvzce/textures"
SOURCE = TEXTURES / "scene/water_surface.png"
TARGET = TEXTURES / "scene/flood_water.png"

SIZE = 128
# Where in the source to sample from: the middle of the strip, where the clouds are softest.
SOURCE_BOX = (256, 256, 256 + SIZE, 256 + SIZE)
EDGE = 3


def main() -> None:
    source = Image.open(SOURCE).convert("RGBA")
    tile = source.crop(SOURCE_BOX).resize((SIZE, SIZE), Image.LANCZOS)

    # A one-pixel inner shadow, drawn rather than baked into the crop: without it a flooded field
    # is one continuous sheet, and the player cannot count the cells the mutation took.
    shade = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(shade)
    for i in range(EDGE):
        alpha = int(46 * (1 - i / EDGE))
        draw.rectangle([i, i, SIZE - 1 - i, SIZE - 1 - i], outline=(6, 30, 52, alpha))
    tile = Image.alpha_composite(tile, shade)

    TARGET.parent.mkdir(parents=True, exist_ok=True)
    tile.save(TARGET)
    print(f"wrote {TARGET.relative_to(REPO_ROOT)} ({SIZE}x{SIZE})")


if __name__ == "__main__":
    main()
