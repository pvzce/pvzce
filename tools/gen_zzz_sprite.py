#!/usr/bin/env python3
"""Draw the Zzz puff a sleeping mushroom breathes out.

The original has no art for this - there is no `Zzz.png`, no emitter that draws letters,
and the sleeping mushrooms in `refer/anim` are just the same mushroom with its eyes shut.
What the original *does* show is "Zzz" drifting up from a sleeping plant, drawn from its
own bitmap font. This project's font atlas is a text atlas (advance widths, one cell per
glyph), so it cannot be cut into a sprite; the letters are therefore rendered here, once,
in the shape the effect needs: white, black-outlined, three sizes.

Run from the repository root:

    python3 tools/gen_zzz_sprite.py

Outputs ``assets/pvzce/textures/particles/misc/zzz_1.png`` (24px),
``zzz_2.png`` (36px) and ``zzz_3.png`` (48px).

The three sizes are the three puffs of one breath, and they are separate files rather than
one file scaled at runtime because a particle's size is a number in its definition: three
definitions then give three quarters of the spiral for free, and the art stays pixel-exact
at each size. 80 px per cell is the original's scale (see ``particles_to_pvzce.py``), so
0.30 / 0.45 / 0.60 cells is what the particle definitions ask for.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:  # pragma: no cover - a missing dependency is not a code path
    print("Pillow is required (python3 -m pip install pillow)", file=sys.stderr)
    raise SystemExit(2)

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUT = REPO_ROOT / "pvzce-game" / "src" / "main" / "resources" / "assets" / "pvzce" / "textures" / "particles" / "misc"

# The same face the UI font is generated from, so the Zzz belongs to the game's own text.
FONT_CANDIDATES = (
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Black.ttc",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
)

# (file suffix, glyph height in pixels). The sizes are what the three particle definitions
# ask for, and they are the classic "small, medium, large" of a drifting Zzz.
SIZES = (("1", 24), ("2", 36), ("3", 48))

OUTLINE = (18, 24, 44, 255)
FILL = (255, 255, 255, 255)
# Padding around the glyph for the outline plus one pixel of slack, so the sprite is the
# glyph *and* its outline rather than a letter touching its own edge.
PADDING = 4


def find_font() -> str:
    for path in FONT_CANDIDATES:
        if Path(path).is_file():
            return path
    print("No usable font found; tried: " + ", ".join(FONT_CANDIDATES), file=sys.stderr)
    raise SystemExit(2)


def draw_z(size: int, font_path: str, glyph: str) -> Image.Image:
    font = ImageFont.truetype(font_path, size)
    # Measured rather than assumed: the glyph box decides the canvas, so the sprite has no
    # dead margin to make the particle look smaller than its scale says.
    left, top, right, bottom = font.getbbox(glyph)
    width = max(1, right - left) + PADDING * 2
    height = max(1, bottom - top) + PADDING * 2
    image = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    origin = (PADDING - left, PADDING - top)
    # The outline is the glyph stamped in every direction first: no stroke support in the
    # bitmap path, and stamping is exact at these sizes.
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            if dx or dy:
                draw.text((origin[0] + dx, origin[1] + dy), glyph, font=font, fill=OUTLINE)
    draw.text(origin, glyph, font=font, fill=FILL)
    return image


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--glyph", default="z", help="the letter to draw (lower case by default)")
    args = parser.parse_args(argv)

    font_path = find_font()
    args.out.mkdir(parents=True, exist_ok=True)
    for suffix, size in SIZES:
        image = draw_z(size, font_path, args.glyph)
        target = args.out / f"zzz_{suffix}.png"
        image.save(target)
        print(f"{target}: {image.width}x{image.height}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
