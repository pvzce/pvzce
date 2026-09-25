#!/usr/bin/env python3
"""Bakes the fog overlay's gradient into a texture.

The engine has no per-vertex colour: `SpriteRenderer.textured` draws a quad with one tint
and one alpha for all four corners, and `RenderSystem` has no matrix stack to build a
gradient out of. So "darker toward the right" has to be a texture whose *alpha* ramps from
0 to 255 across its width, drawn once with alpha 1 - see `FogClientMechanic`.

128 pixels wide is far more than a 1280-wide window needs (a fog span of four cells is
about 570 device pixels, so each texel covers ~4.5 of them) and it keeps the file small.
One pixel tall: the gradient is horizontal, so the vertical axis is a single row that the
sampler stretches.

    python3 tools/gen_fog_gradient.py
"""

from pathlib import Path

from PIL import Image

OUT = (Path(__file__).resolve().parent.parent
       / "pvzce-game/src/main/resources/assets/pvzce/textures/gui/screen/fog_alpha.png")

WIDTH = 128
# The curve, not a straight line: a linear ramp reads as a hard edge where it reaches full
# black (the eye is far more sensitive near the top of the range), and the whole point of
# the fog is that the boundary is a soft place you can still see into.
GAMMA = 1.6


def main() -> None:
    image = Image.new("RGBA", (WIDTH, 1))
    pixels = image.load()
    for x in range(WIDTH):
        t = x / (WIDTH - 1)
        alpha = round(255 * (t ** GAMMA))
        pixels[x, 0] = (0, 0, 0, alpha)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    image.save(OUT)
    print(f"wrote {OUT} ({WIDTH}x1)")


if __name__ == "__main__":
    main()
