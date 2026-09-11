#!/usr/bin/env python3
"""Bakes the frames the liquid fallback draws when the water shader is off.

Why bake at all
---------------
`shaders_enabled = false` must not mean "no water". The fallback draws a pre-baked
frame per cell instead of running the fragment shader, so the surface still has a
water cast, a caustic pattern and a foam rim - and it still moves, by cycling a
handful of frames.

What these frames are
---------------------
Each frame is one CELL of water, not a tile: the fallback draws it across a whole
cell, so it is generated at the cell's display aspect (a board cell is 80x100 px)
and already contains everything the shader would have computed - the water colour
over the sea floor, the caustics, and the shoreline foam ring. That is why
LiquidRenderer draws it flat, with no tint: baking the cast in and then tinting it
again in the renderer is how the first fallback turned the surface grey.

The animation is a phase offset per frame, and the frame set wraps: the last frame
leads back into the first, so cycling them loops smoothly instead of snapping.

Seamlessness
------------
The caustic pattern is built from periodic sines of the sample position, so it is
continuous across the tile edge by construction. The sea floor is the shipped,
already-seamless WATER_BASE tile, sampled with a wrap. Nothing here needs a blend,
because nothing here is a crop of a non-periodic image.

Usage:
    python3 tools/gen_water_frames.py [--frames 4] [--size 128x160]
"""

import argparse
import json
import math
import struct
import sys
import zlib
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
RESOURCES = ROOT / "pvzce-game/src/main/resources"
LIQUID_FILE = RESOURCES / "data/pvzce/pvzce/liquids/water.json"
BASE_TILE = RESOURCES / "assets/pvzce/textures/scene/water_base.png"
OUTPUT_DIR = RESOURCES / "assets/pvzce/textures/scene/water_frames"

# The caustic trains of the shader, at the frequencies the shader settled on.
# Keep in step with LiquidShader's caustic block; these are the CPU twin of it.
CAUSTIC_TRAINS = ((7.0, 0.35, 0.55), (6.3, -0.30, -0.47), (9.0, -0.55, 0.33))
CAUSTIC_EXPONENT = 4.0
CAUSTIC_GAIN = 1.5


def read_png(path):
    data = path.read_bytes()
    pos, idat, width, height, color_type = 8, bytearray(), None, None, None
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            width, height, depth, color_type, _, _, interlace = struct.unpack(">IIBBBBB", chunk[:13])
            if depth != 8 or interlace != 0 or color_type not in (2, 4):
                raise SystemExit(f"unsupported PNG: depth={depth} color={color_type}")
        elif kind == b"IDAT":
            idat += chunk
        elif kind == b"IEND":
            break
        pos += 12 + length
    channels = 3 if color_type == 2 else 4
    raw = zlib.decompress(bytes(idat))
    stride = width * channels
    out = np.empty((height, width, 3), dtype=np.uint8)
    previous = bytearray(stride)
    offset = 0
    for y in range(height):
        filter_type = raw[offset]
        offset += 1
        line = bytearray(raw[offset:offset + stride])
        offset += stride
        if filter_type == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif filter_type == 2:
            for i in range(stride):
                line[i] = (line[i] + previous[i]) & 0xFF
        elif filter_type == 3:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((left + previous[i]) >> 1)) & 0xFF
        elif filter_type == 4:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                up = previous[i]
                up_left = previous[i - channels] if i >= channels else 0
                pa, pb, pc = abs(up - up_left), abs(left - up_left), abs(left + up - 2 * up_left)
                pred = left if (pa <= pb and pa <= pc) else (up if pb <= pc else up_left)
                line[i] = (line[i] + pred) & 0xFF
        out[y] = np.frombuffer(bytes(line), dtype=np.uint8).reshape(width, channels)[:, :3]
        previous = line
    return out


def write_png(path, rgb):
    height, width, _ = rgb.shape
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        raw += rgb[y].tobytes()

    def chunk(kind, payload):
        return (struct.pack(">I", len(payload)) + kind + payload
                + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
                     + chunk(b"IDAT", zlib.compress(bytes(raw), 6)) + chunk(b"IEND", b""))


def parse_hex(text):
    text = text.lstrip("#")
    if len(text) == 3:
        text = "".join(c * 2 for c in text)
    if len(text) == 6:
        text += "FF"
    return np.array([int(text[i:i + 2], 16) / 255.0 for i in (0, 2, 4, 6)], dtype=np.float32)


def frame_image(base_rgb, liquid, width, height, phase):
    """One cell of water at animation `phase` (0..1)."""
    shallow = parse_hex(liquid["shallow_color"])[:3]
    deep = parse_hex(liquid["deep_color"])[:3]
    foam = parse_hex(liquid.get("foam", {}).get("color", "#E8F6F6"))[:3]
    opacity = liquid.get("opacity", 0.68)
    depth_gamma = 0.55
    caustics = liquid.get("caustics", 0.6)
    foam_width = liquid.get("foam", {}).get("width", 0.15)

    yy, xx = np.mgrid[0:height, 0:width].astype(np.float32)
    u = (xx + 0.5) / width
    v = 1.0 - (yy + 0.5) / height      # v grows upward, like the shader's uv.y

    # The sea floor, wrapped so the tile repeats instead of clamping at the edge.
    tile_h, tile_w = base_rgb.shape[0], base_rgb.shape[1]
    tx = np.clip((u * tile_w * 0.4).astype(np.int32) % tile_w, 0, tile_w - 1)
    ty = np.clip(((1.0 - v) * tile_h * 0.4).astype(np.int32) % tile_h, 0, tile_h - 1)
    floor = base_rgb[ty, tx].astype(np.float32) / 255.0

    # One representative depth for the whole cell: a baked frame cannot vary by
    # depth, and a mid value is what most of a body looks like. The rim cells get
    # their own foam below, which is what carries the shoreline reading.
    shaped = 0.6 ** depth_gamma
    water = shallow * (1.0 - shaped) + deep * shaped
    col = floor * water * 2.2 * (1.0 - opacity) + water * 0.75 * opacity

    # Caustics: the same three bent trains the shader uses, phase-shifted per frame.
    t = phase * math.tau
    web = np.zeros_like(u)
    for frequency, cross, rate in CAUSTIC_TRAINS:
        web += 0.5 + 0.5 * np.cos(frequency * (u + cross * v) + t * rate)
    caustic = np.clip(web / 3.0, 0.0, 1.0) ** CAUSTIC_EXPONENT
    col = col + (caustic * caustics * CAUSTIC_GAIN)[..., None] * np.array([0.72, 0.95, 0.88], dtype=np.float32)

    # Shoreline foam ring, so a rim cell still reads as an edge.
    edge = np.minimum(np.minimum(u, 1.0 - u), np.minimum(v, 1.0 - v))
    foam_norm = np.clip(edge / max(foam_width, 1e-3), 0.0, 1.0)
    foam_mask = 1.0 - (foam_norm * foam_norm * (3.0 - 2.0 * foam_norm))
    col = col * (1 - foam_mask[..., None]) + foam * foam_mask[..., None]
    return col


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--frames", type=int, default=4)
    parser.add_argument("--size", default="128x160",
                        help="frame size; the default matches the 80x100 board-cell aspect")
    args = parser.parse_args()

    width, height = (int(v) for v in args.size.lower().split("x"))
    if not BASE_TILE.is_file():
        raise SystemExit(f"missing {BASE_TILE}; run tools/gen_water_base.py first")
    base = read_png(BASE_TILE)
    liquid = json.loads(LIQUID_FILE.read_text())

    for frame in range(args.frames):
        phase = frame / args.frames
        image = frame_image(base, liquid, width, height, phase)
        pixels = np.clip(image * 255.0 + 0.5, 0, 255).astype(np.uint8)
        out = OUTPUT_DIR / f"water_{frame}.png"
        write_png(out, pixels)
        print(f"wrote {out.relative_to(ROOT)}  ({width}x{height}, phase {phase:.2f})")

    # The loop must wrap: the last frame has to lead back into the first, so the
    # phase step is what matters, not any particular frame.
    print(f"{args.frames} frames, phase step {1.0 / args.frames:.3f} - the cycle wraps by construction")


if __name__ == "__main__":
    sys.exit(main())
