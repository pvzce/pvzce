#!/usr/bin/env python3
"""Derives the purple seed packet the eight upgrade plants are sold in.

The original draws an upgrade packet in lavender rather than the green every ordinary packet
uses, so "this card is planted on another plant" is readable from the bar without reading the
name. Its packet art is the same drawing in a different palette, and the reference art the
project has is only the green one (`textures/gui/hud/seed_packet.png`) - so this rotates that
palette's greens to purple in HSV, per pixel, keeping each pixel's value and saturation.

Rotating the hue rather than tinting at draw time matters: a tint multiplies, which turns the
packet's shading muddy and its highlights grey, while a hue rotation keeps the highlight bright
and the shadow dark - which is what makes it read as the same packet in another colour rather
than as the green one with a purple filter over it.

    python3 tools/gen_upgrade_packet.py
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[1]
HUD = (REPO_ROOT / "pvzce-game" / "src" / "main" / "resources" / "assets" / "pvzce"
       / "textures" / "gui" / "hud")
SOURCE = HUD / "seed_packet.png"
OUT = HUD / "seed_packet_upgrade.png"

#: Where the packet's own hue starts: a green in the region of 100 degrees. What the packet's
#: palette is rotated *to* - 285 degrees is the lavender the original's upgrade packets use.
SOURCE_HUE = 100.0
TARGET_HUE = 285.0

#: Below this saturation a pixel is a highlight or a shadow, not a colour: rotating its hue would
#: only shift the noise in it, so it is copied through.
NEUTRAL_SATURATION = 0.12


def main() -> int:
    image = Image.open(SOURCE).convert("RGBA")
    out = Image.new("RGBA", image.size)
    pixels = []
    shift = (TARGET_HUE - SOURCE_HUE) % 360.0
    for r, g, b, a in image.getdata():
        h, s, v = _rgb_to_hsv(r / 255.0, g / 255.0, b / 255.0)
        if s >= NEUTRAL_SATURATION:
            h = (h + shift) % 360.0
        nr, ng, nb = _hsv_to_rgb(h, s, v)
        pixels.append((round(nr * 255), round(ng * 255), round(nb * 255), a))
    out.putdata(pixels)
    out.save(OUT)
    print(f"wrote {OUT.relative_to(REPO_ROOT)} ({out.width}x{out.height})")
    return 0


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
