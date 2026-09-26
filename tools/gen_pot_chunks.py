#!/usr/bin/env python3
"""Cuts each vase/pot scene texture into the chunks a shatter throws.

Breaking a vase in this build used to play the cherry bomb's `pow` cloud - the same picture
as a bomb going off, which is not what a pot does. The original has its own emitter for this
(`refer/im7/particles/VaseShatter.xml`: sixteen chunks, a launch speed, gravity, a ground
constraint, a spin range), but **the original's chunk sprites are not in `refer/`**: the XML
names `IMAGE_VASE_CHUNKS` with nine frames and there is no such PNG, which is why
`tools/particles_to_pvzce.py` has always skipped the emitter (its dry run says so:
``VaseShatter.xml#0 (no PNG for VASE_CHUNKS)``).

So the chunks are cut from the art this project already ships. Each scene texture is a
bottom-anchored pot on a 128px canvas; this script takes the art's own bounding box and cuts
it into a 3x3 grid - nine pieces that fit together exactly, which is what the emitter's nine
frames are. The pieces are written as the frame series a particle definition names
(`<name>.png`, `<name>_2.png`, ...), one series per material:

    assets/pvzce/textures/particles/effect/pot_shatter{,_2..9}.png       question pot
    assets/pvzce/textures/particles/effect/pot_shatter_leaf{,_2..9}.png  leaf pot
    assets/pvzce/textures/particles/effect/vase_shatter{,_2..9}.png      the player's vase

All nine frames of one series are the same size (the art box divided by three), so the
particle engine can draw any of them at one scale and aspect - which is the invariant the
project's particle format has (one `scale` and one `aspect` per definition).

    python3 tools/gen_pot_chunks.py
"""

from pathlib import Path

from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[1]
SCENE_DIR = REPO_ROOT / "pvzce-game/src/main/resources/assets/pvzce/textures/scene"
OUT_DIR = REPO_ROOT / "pvzce-game/src/main/resources/assets/pvzce/textures/particles/effect"

#: Which shipped scene texture feeds which chunk series.
SOURCES = {
    "pot_shatter": "pot_question.png",
    "pot_shatter_leaf": "pot_leaf.png",
    "vase_shatter": "vase.png",
}

#: The grid the art box is cut into. The original's chunk sheet is nine frames, and a 3x3
#: cut is the one that keeps every piece the same size.
COLUMNS = 3
ROWS = 3


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for series, source in SOURCES.items():
        sheet = Image.open(SCENE_DIR / source).convert("RGBA")
        box = sheet.getbbox()
        if box is None:
            raise SystemExit(f"{source} is empty; nothing to cut")
        art = sheet.crop(box)
        piece_w = art.width // COLUMNS
        piece_h = art.height // ROWS
        index = 0
        for row in range(ROWS):
            for column in range(COLUMNS):
                index += 1
                piece = art.crop((column * piece_w, row * piece_h,
                                  (column + 1) * piece_w, (row + 1) * piece_h))
                name = f"{series}.png" if index == 1 else f"{series}_{index}.png"
                piece.save(OUT_DIR / name)
        print(f"wrote {series}: {COLUMNS * ROWS} pieces of {piece_w}x{piece_h}px"
              f" (art {art.width}x{art.height} from {source})")


if __name__ == "__main__":
    main()
