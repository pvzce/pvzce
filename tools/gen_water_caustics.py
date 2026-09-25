#!/usr/bin/env python3
"""Build the pool's scrolling caustic layer from the original's own art.

The original animates its pool with a second drawing on top of the surface: a tileable sheet of
soft light cells that drifts across the water. ``refer/im7/images/pool_caustic_effect.jpg`` is
that sheet - a 256x256 greyscale tile - and it had been sitting in the repository unused while
the engine's own procedural caustics tried to stand in for it.

**Why the engine's own caustics were not enough.** They are three crossed cosine trains summed
and sharpened, added as light. That reads as a caustic web on a dark surface, and the pool is
not dark: the surface art the pool now draws is bright cyan (G and B sit at 240-255 over most of
the basin), so an additive term has nowhere to go and the measured per-pixel change over time was
a median of **1.3 of 255** - a surface that does not move, whatever the shader's clock says. The
original's sheet has the thing the procedural version lacks: broad dark lanes between the light,
so it can *modulate* the surface instead of only brightening it.

**The tile is used as-is.** It is already periodic - the original tiles it - so nothing is
resampled or cropped, and the only change is a contrast stretch and a bias:

* the source's own range (59..255) is stretched to 0..255, so the pattern's full depth survives;
* the result is then scaled to 0.75, because the shader *adds* it: a tile whose average were
  mid-grey would brighten the whole pool rather than lay a web over it, and the dark lanes would
  never reach down to the water colour.

Run from the repository root:

    python3 tools/gen_water_caustics.py
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[1]
SOURCE = REPO_ROOT / "refer" / "im7" / "images" / "pool_caustic_effect.jpg"
OUTPUT = (REPO_ROOT / "pvzce-game" / "src" / "main" / "resources" / "assets" / "pvzce"
          / "textures" / "scene" / "water_caustics.png")

#: How much of the stretched range the shader sees. See the module docstring.
DEPTH = 0.75


def main() -> None:
    source = Image.open(SOURCE).convert("L")
    low, high = source.getextrema()
    span = max(1, high - low)
    source = source.point(lambda value: min(255, int(round((value - low) * 255.0 / span))))
    source = source.point(lambda value: min(255, int(round(value * DEPTH))))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    source.save(OUTPUT)
    print(f"wrote {OUTPUT.relative_to(REPO_ROOT)} {source.size} {source.getextrema()}")


if __name__ == "__main__":
    main()
