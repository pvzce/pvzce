#!/usr/bin/env python3
"""Bake the original's sod rows into a copy of the unsodded lawn backdrop.

The original's first Day levels are played on a lawn that is only partly finished.
`background1unsodded` is bare dirt; the rows the player may plant on are drawn on top of it,
and the rip keeps them as `sod1row` (one middle row) and `sod3row` (three centre rows) with a
same-size alpha mask (`sod1row_.png`, `sod3row_.png`).

The project cannot express those sprites as scene elements - a scene element is one cell, and
these are nine-cell rows of one specific art - so they are baked into a copy of the backdrop,
which is how `background1` (whose sod is part of the art) has always worked for 1-4 onwards.
The level files then point at the baked image and hide the grass pass, as they already do.

Run from the repository root:  python3 tools/bake_sod_backdrop.py
Needs Pillow (the rip's sod art is JPEG, and its shape is an alpha PNG).
"""

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
REFERENCE = ROOT / "refer" / "im7" / "images"
ASSETS = ROOT / "pvzce-game" / "src" / "main" / "resources" / "assets" / "pvzce" / "textures"
LEVEL_ART = ASSETS / "gui" / "screen" / "level"

# The lawn region of the 1400x600 backdrop: nine 80px columns and five 100px rows.
LAWN_X, LAWN_Y, LAWN_WIDTH, LAWN_HEIGHT = 256, 80, 720, 500
ROW_HEIGHT = LAWN_HEIGHT // 5

# Each sod sprite and the rows of the lawn it covers. Both are 771px wide with a few pixels of
# transparent margin; the mask says exactly which pixels are opaque, and the sprite is scaled so
# that opaque part spans the lawn's width.
SPRITES = {
    "sod1row": {"rows": 1, "output": "background1_1row.png"},
    "sod3row": {"rows": 3, "output": "background1_3row.png"},
}


def compose(name: str, spec: dict) -> Path:
    art = Image.open(REFERENCE / f"{name}.jpg").convert("RGB")
    mask = Image.open(REFERENCE / f"{name}_.png").convert("L")
    if art.size != mask.size:
        raise SystemExit(f"{name}: art {art.size} does not match mask {mask.size}")

    opaque = mask.point(lambda value: 255 if value > 8 else 0).getbbox()
    if opaque is None:
        raise SystemExit(f"{name}: the mask is empty")
    left, top, right, bottom = opaque

    rows = spec["rows"]
    # The same centre the board uses for a lawn that is not five rows tall: the finished rows
    # sit in the middle, with bare dirt above and below (`LevelStage.board` splits the leftover
    # evenly). The art is placed by its own opaque rectangle so that rectangle lands exactly on
    # the rows those cells occupy - the sprite's transparent margin then hangs outside the
    # lawn, which is harmless because it is transparent.
    first_row = LAWN_Y + (5 - rows) * ROW_HEIGHT // 2
    scale = LAWN_WIDTH / (right - left)
    width = max(1, round(art.width * scale))
    height = max(1, round(art.height * scale))
    resized_art = art.resize((width, height), Image.LANCZOS)
    resized_mask = mask.resize((width, height), Image.LANCZOS)
    target_x = LAWN_X - round(left * scale)
    target_y = first_row - round(top * scale)

    backdrop = Image.open(LEVEL_ART / "background1unsodded.png").convert("RGB")
    backdrop.paste(resized_art, (target_x, target_y), resized_mask)
    out = LEVEL_ART / spec["output"]
    backdrop.save(out)
    print(f"{out.relative_to(ROOT)}: {rows} row(s) at y={first_row}..{first_row + rows * ROW_HEIGHT}"
          f" from {name} (opaque {right - left}x{bottom - top} scaled to {LAWN_WIDTH}px wide)")
    return out


def main() -> int:
    for name, spec in SPRITES.items():
        compose(name, spec)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
