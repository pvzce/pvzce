#!/usr/bin/env python3
"""Regenerate the UI bitmap font atlas (`assets/pvzce/font/ui.{png,json}`).

The game's text is drawn from one pre-baked atlas: `ui.png` is a grid of square
cells and `ui.json` says which character sits in which cell and how far the pen
advances after it. Nothing in the repository produced that pair, so a missing
glyph (喵, once) or a change of charset meant hand-editing a 700KB JSON - this
script is that generator.

The charset is taken from an existing atlas, so "add a few characters" is: edit
the source atlas once (or point `--charset-from` at any atlas with more glyphs),
then regenerate at whatever cell size and width the driver can actually take.

Usage:
  python3 tools/generate_font_atlas.py \
      --charset-from pvzce-game/src/main/resources/assets/pvzce/font/ui.json \
      --out pvzce-game/src/main/resources/assets/pvzce/font

Sizing notes (learned the hard way):
  * `--cell` and `--font-size` go together: the original atlas used a 128px cell
    with Noto Sans CJK SC Black at 98px. Halving both halves the atlas' pixels
    (and its VRAM) with no visible difference - the renderer draws a line of
    text at ~18 logical pixels, so even a 64px cell is far more resolution than
    any screen shows.
  * Keep `--columns * --cell` at or below 8192. A 16384-wide atlas silently lost
    its alpha channel on a software GL fallback here (text came out as opaque
    black boxes), while the same glyph set at 8192 wide was correct.
  * `--advance` is scaled from the source atlas per glyph, so proportional Latin
    stays proportional; CJK keeps its full-width advance.
"""

import argparse
import json
import math
import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:  # pragma: no cover - a missing dependency is not a code path
    sys.exit("Pillow is required: pip install pillow")

DEFAULT_FONT = "/usr/share/fonts/opentype/noto/NotoSansCJK-Black.ttc"
DEFAULT_FONT_INDEX = 2  # 0 JP / 1 KR / 2 SC / 3 TC / 4 HK inside NotoSansCJK-*.ttc
# The reference atlas' own geometry: a 128px cell drawn with a 98px font. The
# script keeps every other size relative to these two numbers.
REFERENCE_CELL = 128
REFERENCE_FONT_SIZE = 98
REFERENCE_LINE_HEIGHT = 128


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--charset-from", required=True,
                        help="an existing ui.json whose glyph list is the charset to render")
    parser.add_argument("--out", required=True, help="directory to write ui.png / ui.json into")
    parser.add_argument("--font", default=DEFAULT_FONT, help="TTF/TTC to render with")
    parser.add_argument("--font-index", type=int, default=DEFAULT_FONT_INDEX,
                        help="face index inside a .ttc (Noto Sans CJK: 2 = Simplified Chinese)")
    parser.add_argument("--cell", type=int, default=64, help="cell size in pixels")
    parser.add_argument("--font-size", type=int, default=None,
                        help="font size in pixels (default: cell / 128 * 98)")
    parser.add_argument("--columns", type=int, default=128,
                        help="cells per row (width = columns * cell; keep <= 8192)")
    parser.add_argument("--margin", type=int, default=3, help="blank pixels around a glyph inside its cell")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    font_size = args.font_size or round(args.cell / REFERENCE_CELL * REFERENCE_FONT_SIZE)
    scale = font_size / REFERENCE_FONT_SIZE

    source = json.loads(Path(args.charset_from).read_text())
    entries = source["glyphs"]
    chars = [entry["char"] for entry in entries]
    advances = {entry["char"]: entry["advance"] for entry in entries}

    font = ImageFont.truetype(args.font, font_size, index=args.font_index)
    rows = math.ceil(len(chars) / args.columns)
    width, height = args.columns * args.cell, rows * args.cell
    atlas = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(atlas)

    glyphs = []
    for index, char in enumerate(chars):
        cell_x = (index % args.columns) * args.cell
        cell_y = (index // args.columns) * args.cell
        # Control characters have no glyph to draw (the atlas keeps a row for
        # "\n" only because the renderer advances the pen on it); Pillow would
        # read one as multi-line text and refuse to place it.
        if not char.isprintable():
            glyphs.append({
                "char": char,
                "x": cell_x, "y": cell_y, "w": args.cell, "h": args.cell,
                "advance": max(1, round(advances.get(char, REFERENCE_CELL) * scale)),
            })
            continue
        # Every glyph is placed by its own layout box, so a descender or a tall
        # radical cannot be clipped by the cell edge.
        box = font.getbbox(char, anchor="lt")
        draw.text((cell_x + args.margin - box[0], cell_y + args.margin - box[1]), char,
                  font=font, fill=(255, 255, 255, 255), anchor="lt")
        glyphs.append({
            "char": char,
            "x": cell_x,
            "y": cell_y,
            "w": args.cell,
            "h": args.cell,
            "advance": max(1, round(advances.get(char, REFERENCE_CELL) * scale)),
        })

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    atlas.save(out / "ui.png")
    (out / "ui.json").write_text(json.dumps({
        "line_height": round(REFERENCE_LINE_HEIGHT * args.cell / REFERENCE_CELL),
        "glyphs": glyphs,
    }, ensure_ascii=False))
    print(f"{out/'ui.png'}: {width}x{height}, {len(glyphs)} glyphs, "
          f"cell {args.cell}px, font {font_size}px ({font.getname()[0]})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
