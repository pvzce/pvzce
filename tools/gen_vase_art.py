#!/usr/bin/env python3
"""Cuts the vasebreaker vase out of the original's sheet and writes the vase tool's art.

Two things were wrong and both come from the same place - the project drew its own vase and then
used the *flower pot's* controller for the tool:

* `tools/vase.json` pointed its card at `gui/cards/flower_pot` and its animation at
  `animations/tool/vase.json`, which `make_vase_tool_anim.py` had built by copying the flower pot's
  model. So the thing the player carried while placing a vase, and the card they picked it from,
  were a flower pot - the reported "工具花瓶的贴图错误（被用成了花盆的贴图）";
* `textures/scene/vase*.png` were hand-drawn placeholders whose proportions are not the original's
  (the original's vase is noticeably taller and narrower, and has a neck) - the reported
  "花瓶的大小错误……似乎花瓶被缩放了".

The original's own vases are all one sheet, `refer/im7/images/Scary_Pot.png`: three columns by two
rows of 80x101 cels. The top row is the *empty* vase in three colours - brown, green, black - and
the bottom row is the scary-potter pots (question mark, leaf, skull). Cell (0, 0) is the plain brown
vase, which is the one the vasebreaker levels stand on the lawn, and it is what both the scene
element and the tool now use.

Outputs:

* `assets/pvzce/textures/scene/vase.png` - the empty vase, in the 128 canvas every scene texture uses;
* `assets/pvzce/textures/scene/vase_full.png` - the green one, for "a vase with something in it";
* `assets/pvzce/textures/gui/cards/vase.png` - the tool's card face (a 128 square, so it fits the
  seed-card icon box the way every other card does);
* `assets/pvzce/animations/tool/vase.json` - the carried tool, one bone, two clips.

Run from the repository root:

    python3 tools/gen_vase_art.py
"""

from __future__ import annotations

import json
from pathlib import Path

from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[1]
SHEET = REPO_ROOT / "refer" / "im7" / "images" / "Scary_Pot.png"
ASSETS = REPO_ROOT / "pvzce-game" / "src" / "main" / "resources" / "assets" / "pvzce"

#: The sheet's grid: three cels across, two down. Cell (column, row).
COLUMNS, ROWS = 3, 2
#: (0, 0) is the empty brown vase; (1, 0) is the same drawing in green, which is the "holds a
#: plant" colour the scary-potter pots also use.
EMPTY_CEL = (0, 0)
FULL_CEL = (1, 0)

#: Every scene texture is a 128 square with the art anchored to the bottom of the cell, and the
#: art is drawn at the ~65px the project's other lawn objects use (the flower pot is 65 wide inside
#: its own 128 canvas). A vase drawn at a different width would read as a different-sized object
#: standing on the same lawn, which is what the report was about.
CANVAS = 128
ART_WIDTH = 65

#: The tool cursor's own size, in world cells: the mallet is 0.462 x 0.5 and the watering can
#: 0.538 x 0.5, so this keeps the vase in the same box as every other carried tool.
CURSOR_WIDTH_CELLS = 0.5
#: How far the vase dips when the player puts it down, in cells.
DIP = 0.07
#: The dip's beats: down, hold, up.
ATTACK_KEYS = ((0.0, 0.0), (0.12, -DIP), (0.3, -DIP), (0.5, 0.0))


def cel(sheet: Image.Image, column: int, row: int) -> Image.Image:
    width, height = sheet.width // COLUMNS, sheet.height // ROWS
    return sheet.crop((column * width, row * height, (column + 1) * width, (row + 1) * height))


def scene_texture(sheet: Image.Image, position: tuple[int, int], out: Path) -> tuple[int, int]:
    """One cel as a 128-canvas scene texture, bottom-centred and scaled to ART_WIDTH."""
    art = cel(sheet, *position)
    scale = ART_WIDTH / art.width
    art = art.resize((ART_WIDTH, round(art.height * scale)), Image.LANCZOS)
    canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    canvas.alpha_composite(art, ((CANVAS - art.width) // 2, CANVAS - art.height))
    out.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(out)
    return art.size


def card_texture(sheet: Image.Image, out: Path) -> tuple[int, int]:
    """The same cel as a card face: the whole 128 square, art centred."""
    art = cel(sheet, *EMPTY_CEL)
    scale = CANVAS * 0.8 / art.height
    art = art.resize((round(art.width * scale), round(art.height * scale)), Image.LANCZOS)
    canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    canvas.alpha_composite(art, ((CANVAS - art.width) // 2, (CANVAS - art.height) // 2))
    out.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(out)
    return art.size


def tool_animation(out: Path, texture: str, art: tuple[int, int]) -> None:
    """The carried tool: one quad, an idle bob and the "put it down" dip.

    <p>A single bone rather than the flower pot's eight: the vase's art *is* one picture, and the
    tool cursor only needs somewhere to hang it. The bone's quad is the art's own size scaled so the
    model is {@link CURSOR_WIDTH_CELLS} wide, and the idle bob is the same shallow rise the other
    carried tools have (a tool that is perfectly still reads as a sticker on the cursor).
    """
    width = float(CURSOR_WIDTH_CELLS)
    height = width * art[1] / art[0]
    bone = {
        "translation": {"0.0": [0.0, 0.0], "0.7": [0.0, 0.012], "1.4": [0.0, 0.0]},
        "rotation": {"0.0": [0.0, 0.0, 0.0]},
        "scale": {"0.0": [1.0, 1.0]},
        "visible": {"0.0": True},
    }
    attack = dict(bone)
    attack["translation"] = {f"{time}": [0.0, y] for time, y in ATTACK_KEYS}
    model = {
        "size": [round(width, 6), round(height, 6)],
        "bones": [
            {"name": "root", "parent": None, "pivot": [0.0, 0.0]},
            {
                "name": "vase",
                "parent": "root",
                "pivot": [0.0, 0.0],
                "parts": [{
                    "texture": texture,
                    "uv": [0, 0, CANVAS, CANVAS],
                    "size": [round(width, 6), round(height, 6)],
                    "offset": [0.0, 0.0],
                    "z": 0,
                }],
            },
        ],
    }
    document = {
        "type": "controller",
        "model": model,
        "animations": {
            "idle": {
                "animation_length": 1.4,
                "loop": True,
                "transition": 0.2,
                "bones": {"root": bone, "vase": bone},
                "sound_effects": {},
                "particle_effects": {},
                "timeline": {},
            },
            "attack": {
                "animation_length": 0.5,
                "loop": False,
                "on_end": "idle",
                "transition": 0.05,
                "bones": {"root": attack, "vase": attack},
                "sound_effects": {},
                "particle_effects": {},
                "timeline": {},
            },
        },
    }
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    sheet = Image.open(SHEET).convert("RGBA")
    wrote = [
        ("scene/vase.png", scene_texture(sheet, EMPTY_CEL, ASSETS / "textures/scene/vase.png")),
        ("scene/vase_full.png",
         scene_texture(sheet, FULL_CEL, ASSETS / "textures/scene/vase_full.png")),
        ("gui/cards/vase.png", card_texture(sheet, ASSETS / "textures/gui/cards/vase.png")),
    ]
    for name, size in wrote:
        print(f"wrote {name} (art {size[0]}x{size[1]})")
    tool_animation(ASSETS / "animations/tool/vase.json",
                   "pvzce:textures/gui/cards/vase", wrote[2][1])


if __name__ == "__main__":
    main()
