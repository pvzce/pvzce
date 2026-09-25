#!/usr/bin/env python3
"""Build the pool's water-surface texture from the original's own art.

The original draws its pool as one flat surface image, ``refer/im7/images/pool_base.jpg``: a
721x162 strip of soft cyan clouds that is drawn ONCE across the basin - not tiled per cell.
An engine whose liquid base is sampled in world-cell space can still use it unchanged, because
the strip's own size and the basin's are the same size:

    the yard's water is 9 columns wide, and the strip is 721 px wide
        9 cells x 80 px = 720 px  ->  one strip across the whole basin, at native scale

so this tool pads the strip into a square tile whose vertical phase puts it exactly where the
basin is. With ``base_scale = 1/9`` (one tile per nine cells) a water cell at row 2 samples
``uv.y = 2/9 .. 3/9`` and row 3 ``3/9 .. 4/9`` - two ninths of the tile, and the strip is two
rows tall, so the two are the same size and only their PHASE has to be found.

**The phase is mirrored, and that is the engine's convention, not a choice.** The liquid shader
samples the base texture with ``v`` growing the way the board's rows are numbered, and a board's
rows are numbered upward from its bottom while a PNG is stored top-down, so a fragment at world
row Y reads image row ``(9 - Y)`` of a nine-cell tile. The water rows 2..3 therefore read image
rows 5/9..7/9 - pixels 392..548 - and the strip is pasted there, the right way up, repeated
vertically to fill the tile. Every internal tile boundary then lands on the basin's own top and
bottom edges, where a seam is invisible, and the tile's outer wrap lands on a row no board has.

A tiling pool floor is orientation-free, which is why this convention only showed up when a
drawing with a top and a bottom arrived: without the phase the strip's dark upper edge landed
between the two water rows and read as a line across the middle of the pool. The marker-tile
check that found it is recorded in ``LiquidDefinitionTest.theSurfaceArtIsPhasedOntoTheWaterRows``,
which samples where the water reads rather than where the arithmetic "should" put it.

The result is the original's water, at the original's scale, aligned with the original's basin -
with no shader-side mapping mode and no per-level numbers beyond the one ``base_scale``.

Run from the repository root:

    python3 tools/gen_water_surface.py
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[1]
SOURCE = REPO_ROOT / "refer" / "im7" / "images" / "pool_base.jpg"
OUTPUT = (REPO_ROOT / "pvzce-game" / "src" / "main" / "resources" / "assets" / "pvzce"
          / "textures" / "scene" / "water_surface.png")

# One tile per nine cells, and the basin starts at row 2: see the module docstring.
COLUMNS = 9
WATER_TOP_ROW = 2


def main() -> None:
    strip = interior(Image.open(SOURCE).convert("RGB"))
    size = strip.width                 # a square tile, so the sampling stays isotropic
    band = round(size * 2 / COLUMNS)   # two water rows' worth of the tile, in pixels
    # Mirrored phase: world row Y reads image row (9 - Y). See the module docstring.
    top = round(size * (COLUMNS - WATER_TOP_ROW - 2) / COLUMNS)

    # The strip resized to exactly one water band of the tile.
    fitted = strip.resize((size, band), Image.LANCZOS)

    tile = Image.new("RGB", (size, size))
    y = top
    while y > -band:                   # copies above the first one, wrapping off the top
        tile.paste(fitted, (0, y))
        y -= band
    y = top + band
    while y < size:                    # and below it, to the bottom of the tile
        tile.paste(fitted, (0, y))
        y += band

    tile.save(OUTPUT)
    print(f"wrote {OUTPUT.relative_to(REPO_ROOT)}: {tile.width}x{tile.height}, strip at "
          f"y={top}..{top + band} (water rows {WATER_TOP_ROW}..{WATER_TOP_ROW + 2}, mirrored)")
    print(f"use base_scale = 1/{COLUMNS} = {1 / COLUMNS:.6f}")


def interior(image: Image.Image, threshold: int = 40) -> Image.Image:
    """The water without the pool's own inner shadow.

    The rip's strip is the basin's *finished* interior: a black band a few pixels wide runs
    around it, which is the pool's edge shading. Our backdrop draws that edge itself, so
    pasting the rip's one verbatim puts a black outline inside the water's own rim - and, worse
    for tiling, on every copy boundary.
    """
    grey = image.convert("L")
    width, height = grey.size
    pixels = grey.load()

    def row_mean(y: int) -> float:
        return sum(pixels[x, y] for x in range(0, width, 4)) / (width // 4)

    def column_mean(x: int) -> float:
        return sum(pixels[x, y] for y in range(0, height, 4)) / (height // 4)

    top = next(y for y in range(height) if row_mean(y) >= threshold)
    bottom = next(y for y in range(height - 1, -1, -1) if row_mean(y) >= threshold)
    left = next(x for x in range(width) if column_mean(x) >= threshold)
    right = next(x for x in range(width - 1, -1, -1) if column_mean(x) >= threshold)
    return image.crop((left, top, right + 1, bottom + 1))


if __name__ == "__main__":
    main()
