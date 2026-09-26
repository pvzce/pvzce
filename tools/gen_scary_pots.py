#!/usr/bin/env python3
"""Slices the original's scary pots out of the game's own sheet.

4-5 is the original's Scary Potter level: a lawn of vases, most of them with a question mark on
them and a few green ones that are known to hold a plant. Both drawings are cels of one sheet in
the reference art (`refer/im7/images/Scary_Pot.png`, three cels across and two down), and the
engine draws a scene element from one texture per element - so this script cuts the two cels the
level uses and frames each of them the way the other scene textures are framed (128x128, art
anchored to the bottom of the cell, the same width `vase.png` uses).

The third cel of the sheet - the red pot that only the endless puzzle mode reveals a Gargantuar
with - is deliberately not exported: no shipped level can show it.

    python3 tools/gen_scary_pots.py
"""

from pathlib import Path

from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[1]
SHEET = REPO_ROOT / "refer/im7/images/Scary_Pot.png"
OUT_DIR = REPO_ROOT / "pvzce-game/src/main/resources/assets/pvzce/textures/scene"

#: What the sheet's cels are, left to right, in the original's own state order.
CELS = {
    "pot_question": 0,  # `GRIDITEM_STATE_SCARY_POT_QUESTION`
    "pot_leaf": 1,      # `GRIDITEM_STATE_SCARY_POT_LEAF`: the ones that hold a plant
}

#: The canvas every scene texture uses, and the width the art is scaled to. `vase.png` is
#: 65 px wide inside its 128 px cell, and a pot that was a different size would read as a
#: different object standing on the same lawn.
CANVAS = 128
ART_WIDTH = 65


def main() -> None:
    sheet = Image.open(SHEET).convert("RGBA")
    cel_width = sheet.width // 3
    cel_height = sheet.height // 2
    for name, column in CELS.items():
        cel = sheet.crop((column * cel_width, cel_height,
                          (column + 1) * cel_width, sheet.height))
        scale = ART_WIDTH / cel_width
        cel = cel.resize((ART_WIDTH, round(cel_height * scale)), Image.LANCZOS)
        canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
        # Bottom-centred, which is where `vase.png` sits: a scene element's art is drawn at the
        # bottom of its cell, so the transparent space belongs above it.
        canvas.alpha_composite(cel, ((CANVAS - cel.width) // 2, CANVAS - cel.height))
        OUT_DIR.mkdir(parents=True, exist_ok=True)
        out = OUT_DIR / f"{name}.png"
        canvas.save(out)
        print(f"wrote {out} ({canvas.width}x{canvas.height}, art {cel.width}x{cel.height})")


if __name__ == "__main__":
    main()
