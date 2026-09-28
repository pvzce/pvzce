#!/usr/bin/env python3
"""Bake the fog's cloud sheet from the original's own fog art.

`FogClientMechanic` used to draw the fog as one flat black quad with a procedurally ramped
alpha (`textures/gui/screen/fog_alpha.png`, `gen_fog_gradient.py`), then as a cloud picture
stretched over the whole band with a stair-step of black quads on top. Both read as "a black
rectangle with a soft edge" rather than as weather, because neither is how the original draws
it: the original's fog is a **grid of cloud sprites**, one per board cell, and the sheet this
tool bakes is that sprite - `IMAGE_FOG` from the rip, eight 210x190 frames laid out in a row.

What the PNG has to be, and why:

* **Geometry.** 1680x190 = 8 frames of 210x190. The mechanic draws frame `n` at
  `x*80-15, y*85-30` in the pool board's own pixels (an 80x85 cell grid), so consecutive
  frames overlap by 130px across and 105px down - which is what makes the band continuous
  instead of eight separate blobs. `FogClientMechanic` reads the frame size back off the
  loaded texture, so reslicing this file is all it takes to change the grid; this doc and
  that class are the two places the layout is stated.
* **Alpha is the cloud.** The rip is a JPEG, so it has no alpha channel at all: the cloud is
  white-ish and the gaps are black-ish, and this tool turns the greyscale into the alpha and
  throws the JPEG's colour noise away. Low values are pushed down harder than high ones
  (`ALPHA_GAMMA`) because a JPEG's "black" background is not black - it is 20..40 of ringing
  around every edge, and a linear map would render that as a grey haze over the whole band.
* **Colour is a light blue-grey.** The original tints its cloud per cell at draw time
  (`255 - celLook*1.5 - motion*1.5` or so), so the sheet's own colour only has to be the
  bright end of that: a white cloud the mechanic darkens per cell. Tinted a little cool, so
  fog over a night pool does not read as a warm grey.

Run from the repository root:

    python3 tools/gen_fog_texture.py
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[1]
SOURCE = REPO_ROOT / "refer" / "im7" / "images" / "fog_.jpg"
OUTPUT = (REPO_ROOT / "pvzce-game" / "src" / "main" / "resources" / "assets" / "pvzce"
          / "textures" / "gui" / "screen" / "fog_cloud.png")

#: The original's sheet is eight frames in a row; this is the check that the file really is
#: that shape, not a guess the mechanic would silently draw wrong.
FRAMES = 8

#: The cloud's own colour, before the mechanic's per-cell darkening.
#:
#: A night blue-grey, and this one was picked by eye off three baked candidates (pale grey, this,
#: and a darker one) rendered in the game: the source is a white cloud, and a white cloud over a
#: night pool is a bright wall - the fog the player recognised as fog is this, a cloud the moon is
#: not lighting. The mechanic still darkens it per cell on top (0.55..1.0), so the sheet's colour
#: is the bright end of the range.
TINT = (0.58, 0.63, 0.75)

#: Below this normalized value the pixel is pure background, and the rest of the range is
#: stretched back over 0..1. The rip's puffs sit around 0.5..1.0 and its background around
#: 0.0..0.12, so the cut is nowhere near either of them.
ALPHA_FLOOR = 0.08

#: How hard what is left is pushed down. 1.0 is linear; much above it the cloud's soft edges go
#: and the puffs read as hard shapes, which is the one thing fog must not do.
ALPHA_GAMMA = 1.0

#: The alpha even the thinnest part of a puff keeps.
#:
#: Not a style knob: it is what makes the band fog instead of lace. A single frame of this sheet
#: is a puff with a lot of air around it (a third of 210x190 is above half opacity, at 0.274 mean),
#: and the band is those frames composited at a 80px stride - measured, that composition lands at
#: 0.76 mean with a tenth of it below 0.17, which is a milky haze with the lawn showing through.
#: Lifting every non-background pixel by a quarter brings the same measurement to 0.92 with its
#: tenth percentile at 0.71, which is the original's own "you cannot see what is in there".
ALPHA_LIFT = 0.25


def main() -> None:
    source = Image.open(SOURCE).convert("L")
    width, height = source.size
    if width % FRAMES != 0:
        raise SystemExit(f"{SOURCE.name} is {width}px wide, which is not {FRAMES} frames")
    src = source.load()

    out = Image.new("RGBA", (width, height))
    pixels = out.load()
    for y in range(height):
        for x in range(width):
            value = src[x, y] / 255.0
            alpha = 0.0
            if value > ALPHA_FLOOR:
                alpha = ((value - ALPHA_FLOOR) / (1.0 - ALPHA_FLOOR)) ** ALPHA_GAMMA
                alpha = ALPHA_LIFT + alpha * (1.0 - ALPHA_LIFT)
            pixels[x, y] = (round(TINT[0] * 255), round(TINT[1] * 255), round(TINT[2] * 255),
                            round(min(1.0, alpha) * 255))

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    out.save(OUTPUT)
    print(f"wrote {OUTPUT.relative_to(REPO_ROOT)}: {width}x{height} = "
          f"{FRAMES} frames of {width // FRAMES}x{height} from "
          f"{SOURCE.relative_to(REPO_ROOT)}")


if __name__ == "__main__":
    main()
