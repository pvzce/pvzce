#!/usr/bin/env python3
"""Derives the explosive bowling nut's art from the wall-nut's.

Wall-nut Bowling 2 hands out two nuts the original's first one does not: a giant one and an
explosive one. The reference art in `refer/anim` is the ordinary wall-nut (the giant is that
same drawing enlarged - `render_scale` is what a plant declares for that), so the explosive
one has nothing of its own to convert.

It does need to be *readable*, though: a belt that deals two nuts which look identical is a
belt where the player cannot tell which one will blow up. So this copies the wall-nut's model
and tints it red, the same way `gen_upgrade_packet.py` recolours the seed packet - by rotating
each pixel's hue, which keeps the drawing's own shading instead of flattening it under a
multiply.

    python3 tools/gen_explosive_nut.py

Writes:
* `assets/pvzce/animations/plant/defense/explosive_nut.json` (the model, pointing at the new
  textures and keeping every clip the wall-nut has)
* `assets/pvzce/textures/entities/plant/defense/explosive_nut/*.png`
"""

from __future__ import annotations

import json
import shutil
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
ASSETS = REPO_ROOT / "pvzce-game" / "src" / "main" / "resources" / "assets" / "pvzce"
ANIMATIONS = ASSETS / "animations" / "plant" / "defense"
TEXTURES = ASSETS / "textures" / "entities" / "plant" / "defense"

SOURCE_NAME = "wall_nut"
OUTPUT_NAME = "explosive_nut"

#: The tint: the wall-nut's browns sit around 25 degrees, and 355 (a red) is far enough from
#: every other nut on the lawn to be unmistakable at a glance.
HUE_SHIFT = 330.0


def main() -> int:
    from PIL import Image

    source = ANIMATIONS / f"{SOURCE_NAME}.json"
    model = json.loads(source.read_text(encoding="utf-8"))
    out_dir = TEXTURES / OUTPUT_NAME
    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True)

    old_prefix = f"pvzce:textures/entities/plant/defense/{SOURCE_NAME}/"
    new_prefix = f"pvzce:textures/entities/plant/defense/{OUTPUT_NAME}/"

    def rewrite(bone: dict) -> None:
        for part in bone.get("parts", []) or []:
            texture = part.get("texture", "")
            if not texture.startswith(old_prefix):
                raise SystemExit(f"unexpected texture reference {texture!r}")
            name = texture[len(old_prefix):]
            part["texture"] = new_prefix + name
            _tint(Image, TEXTURES / SOURCE_NAME / f"{name}.png", out_dir / f"{name}.png")

    for bone in model["model"]["bones"]:
        rewrite(bone)

    (ANIMATIONS / f"{OUTPUT_NAME}.json").write_text(
        json.dumps(model, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {ANIMATIONS.relative_to(REPO_ROOT)}/{OUTPUT_NAME}.json")
    print(f"wrote {out_dir.relative_to(REPO_ROOT)}/ ({len(list(out_dir.iterdir()))} files)")
    return 0


def _tint(Image, src: Path, dst: Path) -> None:
    image = Image.open(src).convert("RGBA")
    out = []
    for r, g, b, a in image.getdata():
        h, s, v = _rgb_to_hsv(r / 255.0, g / 255.0, b / 255.0)
        if s >= 0.12:
            h = (h + HUE_SHIFT) % 360.0
        nr, ng, nb = _hsv_to_rgb(h, s, v)
        out.append((round(nr * 255), round(ng * 255), round(nb * 255), a))
    tinted = Image.new("RGBA", image.size)
    tinted.putdata(out)
    tinted.save(dst)


def _rgb_to_hsv(r: float, g: float, b: float):
    high = max(r, g, b)
    low = min(r, g, b)
    span = high - low
    if span == 0:
        return 0.0, 0.0, high
    if high == r:
        h = ((g - b) / span) % 6.0
    elif high == g:
        h = (b - r) / span + 2.0
    else:
        h = (r - g) / span + 4.0
    return h * 60.0, span / high, high


def _hsv_to_rgb(h: float, s: float, v: float):
    c = v * s
    x = c * (1 - abs((h / 60.0) % 2 - 1))
    m = v - c
    if h < 60:
        r, g, b = c, x, 0.0
    elif h < 120:
        r, g, b = x, c, 0.0
    elif h < 180:
        r, g, b = 0.0, c, x
    elif h < 240:
        r, g, b = 0.0, x, c
    elif h < 300:
        r, g, b = x, 0.0, c
    else:
        r, g, b = c, 0.0, x
    return r + m, g + m, b + m


if __name__ == "__main__":
    raise SystemExit(main())
