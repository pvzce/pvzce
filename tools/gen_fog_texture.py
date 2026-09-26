#!/usr/bin/env python3
"""Bake the fog overlay's sprite from the original's own fog art.

`FogClientMechanic` used to draw the fog as one flat black quad with a procedurally ramped
alpha (`textures/gui/screen/fog_alpha.png`, `gen_fog_gradient.py`). It was dark and it hid what
it covered, but it did not look like fog: a uniform gradient with no shape in it reads as a
black rectangle with a soft edge, which is what the player reported.

The original's own fog is a picture. `refer/im7/images/fog.jpg` is the rip's fog bitmap - a
1680x190 greyscale band of soft cloud noise - and it is drawn **once across the fogged part of
the lawn**, not tiled: this tool measured its autocorrelation and it is not seamless at any
offset (the most self-similar offset, 40px, is twice as different as two unrelated columns), so
tiling it would put a visible seam every 1680px. One tile per nine columns is also what the
geometry wants: the yard's lawn is nine columns wide, so a 9-column tile lands exactly on the
board and the band's own left-to-right structure survives at native scale.

Three transforms, and each one is a decision:

* **Colour.** The bitmap is greyscale and mid-grey (mean 190/255). Fog on a night lawn is not a
  grey wash, so the sprite is tinted a cool blue-grey; the noise rides on top as a brightness
  variation (0.78..1.06) rather than as an alpha variation, which is what makes it read as cloud
  with a shape rather than as a dirty window.
* **Opacity in the clouds.** Alpha is the noise mapped into `MIN_ALPHA..1`, so the thin parts of
  the cloud let a little of the lawn through and the dense parts do not. A flat alpha over a
  noisy colour would look like a photograph of fog; this looks like fog.
* **The fade-in at the left edge, and the feathering at the top and bottom.** The mechanic draws
  this sprite as one quad across the whole fog band, *under* the opacity ramp the mechanic
  already draws as a stair-step of quads (the engine has no per-vertex colour), so the sprite is
  the fog's texture and the ramp is its shape. The leading edge is feathered so the clouds do not
  begin on a hard vertical line, and the top and bottom rows feather out so the band does not end
  on a hard line above the road.

`FogData.alphaAt` is still the only definition of "how dark is it here" - this tool changes what
the dark *looks* like, not where it starts or what it hides.

Run from the repository root:

    python3 tools/gen_fog_texture.py
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[1]
SOURCE = REPO_ROOT / "refer" / "im7" / "images" / "fog.jpg"
OUTPUT = (REPO_ROOT / "pvzce-game" / "src" / "main" / "resources" / "assets" / "pvzce"
          / "textures" / "gui" / "screen" / "fog_cloud.png")

#: Fog on a night lawn: cool, slightly blue, and a touch brighter than the board it covers so it
#: reads as something in the air in front of the lawn rather than as a hole in the picture.
TINT = (0.62, 0.68, 0.78)

#: How much of the alpha the noise's own brightness is allowed to take away at its darkest.
MIN_ALPHA = 0.70

#: The noise's brightness range, relative to mid-grey. Above 1 in the dense parts of the cloud,
#: below it in the thin ones; the mean is what the source's own mean works out to.
BRIGHTNESS_LOW = 0.78
BRIGHTNESS_HIGH = 1.06

#: The left-edge fade, as a fraction of the sprite's width.
#:
#: This is the one number that has to agree with the simulation, and it is why it is small.
#: `FogData.alphaAt` ramps from 0 at `start_column` to `max_alpha` at `end_column`, and that same
#: ramp decides where a zombie stops being drawn (`hidingColumn`). A cloud whose soft edge ran
#: over a third of the band would say "not dark yet" where the hiding rule already says "cannot
#: be made out", and the two would disagree about the one line the player learns to read. Half a
#: column of feathering is the same scale the mechanic's own opacity stair-step resolves, so the
#: sprite's edge and the hiding rule describe the same boundary.
FADE_IN_FRACTION = 0.05

#: Rows of vertical feathering at each edge, in pixels of the 190px band.
FEATHER_ROWS = 26

#: Rows the band is stretched over, in cells. Nine columns of board are 720px and the source
#: band is 1680x190, so covering three rows keeps the clouds at their own aspect rather than
#: squashing them flat; the mechanic draws the whole band across the board's height.
BAND_ROWS = 3.0


def main() -> None:
    source = Image.open(SOURCE).convert("L")
    width, height = source.size
    out = Image.new("RGBA", (width, height))
    pixels = out.load()
    src = source.load()

    fade_pixels = max(1, int(width * FADE_IN_FRACTION))
    for x in range(width):
        # Smoothstep, so the fog's leading edge has no corner in it at either end of the fade.
        if x >= fade_pixels:
            fade = 1.0
        else:
            t = x / fade_pixels
            fade = t * t * (3.0 - 2.0 * t)
        for y in range(height):
            # The noise, as a 0..1 position between the two brightness ends.
            noise = (src[x, y] - 137) / (249 - 137)
            noise = min(1.0, max(0.0, noise))
            brightness = BRIGHTNESS_LOW + (BRIGHTNESS_HIGH - BRIGHTNESS_LOW) * noise
            alpha = (MIN_ALPHA + (1.0 - MIN_ALPHA) * noise) * fade
            # Vertical feathering: the band's own edges are the top of the road and the bottom of
            # the lawn, and a fog wall that stops in a straight line there reads as a texture
            # that ran out rather than as weather.
            if y < FEATHER_ROWS:
                alpha *= y / FEATHER_ROWS
            elif y >= height - FEATHER_ROWS:
                alpha *= (height - 1 - y) / FEATHER_ROWS
            r = min(1.0, TINT[0] * brightness)
            g = min(1.0, TINT[1] * brightness)
            b = min(1.0, TINT[2] * brightness)
            pixels[x, y] = (round(r * 255), round(g * 255), round(b * 255), round(alpha * 255))

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    out.save(OUTPUT)
    print(f"wrote {OUTPUT.relative_to(REPO_ROOT)}: {width}x{height} from "
          f"{SOURCE.relative_to(REPO_ROOT)}, drawn over {BAND_ROWS} rows")


if __name__ == "__main__":
    main()
