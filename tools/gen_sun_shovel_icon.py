#!/usr/bin/env python3
"""Composes the shop's "sun shovel" icon out of the original's own pieces.

The project shipped this icon as a hand-drawn 1254x1254 illustration - a photorealistic shovel
with a sun and an arrow, in a style nothing else in the game uses. The reported problem is exactly
that: "阳光铲的贴图错误".

The original has no single sun-shovel drawing either: the upgrade is shown as the ordinary shovel
card plus the sun bank's sticker. Both pieces are in the reference art, so this composites them the
way the original's own card art is composed - the shovel on the diagonal, the sun above its
shoulder - into the 128-square every other buff and card icon uses.

    python3 tools/gen_sun_shovel_icon.py
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[1]
IMAGES = REPO_ROOT / "refer" / "im7" / "images"
OUT = (REPO_ROOT / "pvzce-game" / "src" / "main" / "resources" / "assets" / "pvzce"
       / "textures" / "gui" / "buff" / "sun_shovel.png")

#: The canvas every icon in the game uses, and how much of it the shovel takes.
CANVAS = 128
SHOVEL_SHARE = 0.86
#: The sun sticker: the sun disc out of the middle of `SunBank.png`, at this share of the shovel's
#: size, centred on the handle's top. The bank around it is a wooden button - it is dropped, because
#: the card already *is* the frame and a button inside a card reads as two objects.
SUN_SHARE = 0.40
SUN_AT = (0.58, 0.02)
#: Where the sun sits inside `SunBank.png`, as a fraction of the image: the glowing disc, above the
#: blank label the bank leaves for its own price.
SUN_CROP = (0.10, -0.02, 0.90, 0.72)
#: The disc's own radius as a fraction of the crop's width, measured off the bank: everything past
#: it is the wooden button's rim, which is dropped - the card is already a frame, and a second frame
#: inside it reads as two objects.
SUN_RADIUS = 0.40


def main() -> None:
    shovel = Image.open(IMAGES / "Shovel.png").convert("RGBA")
    shovel = shovel.crop(shovel.getbbox())
    sun = Image.open(IMAGES / "SunBank.png").convert("RGBA")
    sun = sun.crop((round(sun.width * SUN_CROP[0]), round(sun.height * SUN_CROP[1]),
                    round(sun.width * SUN_CROP[2]), round(sun.height * SUN_CROP[3])))
    # Only the disc: a soft circular mask, feathered over the outer tenth so the rim does not alias.
    pixels = sun.load()
    radius = sun.width * SUN_RADIUS
    for y in range(sun.height):
        for x in range(sun.width):
            r, g, b, a = pixels[x, y]
            distance = ((x - sun.width / 2) ** 2 + (y - sun.height * 0.46) ** 2) ** 0.5
            feather = max(0.0, min(1.0, (radius - distance) / max(1.0, radius * 0.12)))
            pixels[x, y] = (r, g, b, int(a * feather))

    target = CANVAS * SHOVEL_SHARE
    scale = target / max(shovel.width, shovel.height)
    shovel = shovel.resize((round(shovel.width * scale), round(shovel.height * scale)),
                           Image.LANCZOS)
    sun_side = round(target * SUN_SHARE)
    sun = sun.resize((sun_side, sun_side), Image.LANCZOS)

    canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    canvas.alpha_composite(shovel, ((CANVAS - shovel.width) // 2,
                                    round((CANVAS - shovel.height) * 0.62)))
    canvas.alpha_composite(sun, (round(CANVAS * SUN_AT[0]), round(CANVAS * SUN_AT[1])))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(OUT)
    print(f"wrote {OUT.relative_to(REPO_ROOT)} ({CANVAS}x{CANVAS})")


if __name__ == "__main__":
    main()
