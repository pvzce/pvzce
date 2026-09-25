#!/usr/bin/env python3
"""Draws the level buffs' own icons.

`todo.md` carried a debt here: the first two buffs borrowed a sprite each - the resource
directory's sun for "picks things up for you", and a puff-shroom card face for "mushrooms
shoot further" - so the icon row said nothing about what either buff did. These are drawn
rather than borrowed, which is not the same as being hand-painted, but it is the difference
between an icon that means the right thing and one that means a different thing.

Four shapes, one per buff, all on the same 64x64 field so the card renderer's aspect fit has
nothing to do:

* `auto_collect`   a sun with a small hand closing on it
* `mushroom_range` a spore arc reaching past a dashed line
* `fog_retreat`    a bank of fog with the right-hand bars pushed away

Run from the repository root:

    python3 tools/gen_buff_icons.py
"""

from pathlib import Path

from PIL import Image, ImageDraw

OUT_DIR = (Path(__file__).resolve().parent.parent
           / "pvzce-game/src/main/resources/assets/pvzce/textures/gui/buff")

SIZE = 64
SUN = (255, 214, 64, 255)
SUN_DARK = (214, 148, 22, 255)
LEAF = (108, 190, 62, 255)
LEAF_DARK = (60, 132, 44, 255)
FOG = (206, 214, 226, 255)
FOG_DARK = (140, 152, 172, 255)
INK = (58, 48, 38, 255)


def sun(draw: ImageDraw.ImageDraw, cx: int, cy: int, radius: int) -> None:
    draw.ellipse((cx - radius, cy - radius, cx + radius, cy + radius),
                 fill=SUN, outline=SUN_DARK, width=2)
    for step in range(8):
        import math
        angle = math.pi * step / 4
        x0 = cx + int((radius + 3) * math.cos(angle))
        y0 = cy + int((radius + 3) * math.sin(angle))
        x1 = cx + int((radius + 9) * math.cos(angle))
        y1 = cy + int((radius + 9) * math.sin(angle))
        draw.line((x0, y0, x1, y1), fill=SUN_DARK, width=3)


def auto_collect(draw: ImageDraw.ImageDraw) -> None:
    """A sun already half inside a closed hand: the buff does the clicking."""
    sun(draw, 26, 24, 13)
    # The hand: a cupped palm under the sun and a thumb over it.
    draw.polygon([(16, 44), (46, 44), (50, 56), (12, 56)], fill=(240, 200, 160, 255),
                 outline=INK)
    draw.arc((14, 30, 46, 54), start=200, end=340, fill=INK, width=3)
    draw.line((30, 20, 40, 32), fill=INK, width=3)


def mushroom_range(draw: ImageDraw.ImageDraw) -> None:
    """A spore arc that crosses the line it would not have reached before."""
    # The dashed "before" reach.
    for y in range(12, 56, 8):
        draw.line((30, y, 30, y + 4), fill=FOG_DARK, width=2)
    # The mushroom.
    draw.pieslice((8, 20, 40, 48), start=180, end=360, fill=LEAF, outline=LEAF_DARK, width=2)
    draw.rectangle((20, 32, 28, 52), fill=(238, 226, 206, 255), outline=INK)
    # The arc, past the dashes on purpose.
    draw.arc((10, 10, 58, 46), start=300, end=20, fill=SUN_DARK, width=3)
    draw.polygon([(52, 20), (58, 26), (50, 28)], fill=SUN_DARK)


def fog_retreat(draw: ImageDraw.ImageDraw) -> None:
    """Fog on the left, clear air on the right, and an arrow pushing the boundary over."""
    for index, (y, width) in enumerate([(16, 34), (26, 42), (36, 30), (46, 38)]):
        draw.rounded_rectangle((6, y, 6 + width, y + 8), radius=4,
                               fill=FOG if index % 2 == 0 else FOG_DARK)
    draw.line((46, 8, 46, 56), fill=SUN_DARK, width=3)
    # A single arrow head on a shaft, not two triangles: the two-triangle version read as a
    # diamond marker rather than as "the fog moves this way".
    draw.line((48, 32, 58, 32), fill=SUN_DARK, width=4)
    draw.polygon([(56, 24), (64, 32), (56, 40)], fill=SUN_DARK)


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for name, painter in (("auto_collect", auto_collect),
                          ("mushroom_range", mushroom_range),
                          ("fog_retreat", fog_retreat)):
        image = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
        painter(ImageDraw.Draw(image))
        path = OUT_DIR / f"{name}.png"
        image.save(path)
        print(f"wrote {path}")


if __name__ == "__main__":
    main()
