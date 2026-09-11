#!/usr/bin/env python3
"""Renders a preview of the liquid fragment shader on the CPU.

The game's water lives in a GLSL fragment shader that cannot be executed in this
environment (no GL context), and a shader that is never seen until it runs is a
shader that is debugged by the player. This script re-implements the SAME maths in
numpy and writes a PNG, so the look can be checked - and the parameters tuned -
before anyone launches the game.

It is a preview, not a second implementation to keep in sync: the constants are
read from the shipped data file (data/pvzce/pvzce/liquids/water.json) and the cell
layout from a level file, so a change to either shows up here immediately. If the
shader changes, this has to change with it - the alternative is tuning water by
launching the game, which is slower and cannot be diffed.

Usage:
    python3 tools/preview_water.py [--level combat_test.json] [--time 3.0] [--out /tmp/water.png]
"""

import argparse
import json
import struct
import sys
import zlib
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
RESOURCES = ROOT / "pvzce-game/src/main/resources"
LIQUID_FILE = RESOURCES / "data/pvzce/pvzce/liquids/water.json"
LEVEL_DIR = RESOURCES / "data/pvzce/pvzce/levels"

# One board cell in preview pixels, matching the shipped cell aspect (80x100).
CELL_W, CELL_H = 160, 200


def read_png(path):
    """Decodes a non-interlaced 8-bit RGB/RGBA PNG into (h, w, 3) uint8."""
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


def hash2(px, py):
    return np.modf(np.sin(px * 127.1 + py * 311.7) * 43758.5453)[0] % 1.0


def value_noise(px, py):
    ix, iy = np.floor(px), np.floor(py)
    fx, fy = px - ix, py - iy
    ux = fx * fx * (3.0 - 2.0 * fx)
    uy = fy * fy * (3.0 - 2.0 * fy)
    a = hash2(ix, iy)
    b = hash2(ix + 1.0, iy)
    c = hash2(ix, iy + 1.0)
    d = hash2(ix + 1.0, iy + 1.0)
    return (a * (1 - ux) + b * ux) * (1 - uy) + (c * (1 - ux) + d * ux) * uy


def fbm(px, py, octaves=3):
    total = np.zeros_like(px)
    amplitude = 0.5
    for _ in range(octaves):
        total = total + value_noise(px, py) * amplitude
        px, py = px * 2.03, py * 2.03
        amplitude *= 0.5
    return total


def level_scene(path):
    """Reads a level's authored scene map into a set of liquid cells."""
    data = json.loads(path.read_text())
    cells = set()
    for element_id, coordinates in data.get("scene", {}).items():
        if "water" not in element_id:
            continue
        for entry in coordinates:
            x, y = entry.split(",")
            cells.add((int(x), int(y)))
    width = data.get("width", 9)
    height = data.get("height", 5)
    return width, height, cells


def liquid_default(key, fallback):
    """Reads one key out of the shipped liquid file, for an argparse default."""
    text = LIQUID_FILE.read_text()
    data = json.loads(text)
    for section in (data, *[v for v in data.values() if isinstance(v, dict)]):
        if key in section:
            return section[key]
    return fallback


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--level", default="combat_test.json")
    parser.add_argument("--time", type=float, default=2.5)
    parser.add_argument("--out", default="/tmp/pvzce-water-preview.png")
    parser.add_argument("--quality", default="high", choices=["low", "medium", "high"])
    # Tuning overrides. These exist so a parameter can be swept and LOOKED AT
    # without editing the shipped data file, which is how the shipped values were
    # chosen in the first place.
    parser.add_argument("--foam-width", type=float)
    parser.add_argument("--depth-gamma", type=float, default=0.55,
                        help="must match LiquidShader.DEPTH_GAMMA")
    parser.add_argument("--caustics", type=float)
    parser.add_argument("--specular", type=float)
    parser.add_argument("--probe", help="x,y in pixels: print the intermediate values there")
    parser.add_argument("--seams", action="store_true",
                        help="attribute the jump across a cell boundary to a stage")
    parser.add_argument("--grid", action="store_true",
                        help="also print a text map of which cells read as water")
    parser.add_argument("--no-inset", action="store_true", help="sweep: disable the inset remap")
    parser.add_argument("--foam-strength", type=float, default=1.0,
                        help="sweep: scale the foam's opacity")
    parser.add_argument("--bare", action="store_true",
                        help="base texture only: isolates the sampler from the effects")
    args = parser.parse_args()

    liquid = json.loads(LIQUID_FILE.read_text())
    shallow = parse_hex(liquid.get("shallow_color", "#7FB6BE80"))
    deep = parse_hex(liquid.get("deep_color", "#12405C"))
    foam = parse_hex(liquid.get("foam", {}).get("color", "#EAF7F2"))
    reflect = parse_hex(liquid.get("reflect_color", "#9FC7E8"))
    opacity = liquid.get("opacity", 0.82)
    depth_scale = liquid.get("depth_scale", 2.0)
    foam_width = liquid.get("foam", {}).get("width", 0.075)
    wave = liquid.get("wave", {})
    wave_speed = wave.get("speed", 0.055)
    wave_amplitude = wave.get("amplitude", 0.55)
    wave_density = wave.get("density", 2.0)
    caustics = liquid.get("caustics", 0.30)
    depth_gamma = args.depth_gamma
    fresnel_strength = liquid.get("fresnel", 0.35)
    specular = liquid.get("specular", 0.65)
    if args.foam_width is not None:
        foam_width = args.foam_width
    if args.caustics is not None:
        caustics = args.caustics
    if args.specular is not None:
        specular = args.specular
    specular_power = liquid.get("specular_power", 48.0)
    base_scale = liquid.get("base_scale", 1.0 / 3.5)

    features = {
        "low": {"waves": False, "caustics": False, "fresnel": False, "specular": False, "shore": True},
        "medium": {"waves": True, "caustics": False, "fresnel": False, "specular": True, "shore": True},
        "high": {"waves": True, "caustics": True, "fresnel": True, "specular": True, "shore": True},
    }[args.quality]

    debug_probe = None
    if args.probe:
        px, py = (int(v) for v in args.probe.split(","))
        debug_probe = (py, px)   # stored as row, column
    width, height, water = level_scene(LEVEL_DIR / args.level)
    canvas_w, canvas_h = width * CELL_W, height * CELL_H

    # --- build the per-pixel arrays over the whole board -------------------------
    yy, xx = np.mgrid[0:canvas_h, 0:canvas_w].astype(np.float32)
    # Screen y grows downwards in the image; the board's y grows upwards.
    cell_x = np.floor(xx / CELL_W)
    cell_y = height - 1 - np.floor(yy / CELL_H)
    local_u = (xx % CELL_W) / CELL_W
    local_v = (yy % CELL_H) / CELL_H   # 0 at the top of the cell

    # Depth: a CONTINUOUS distance-to-land field, sampled at each cell's four corners
    # and interpolated across it, exactly like LiquidGeometry + LiquidBatch + the
    # shader. This replaced an integer step count, which could only ever give one depth
    # per cell: on a small pond its normaliser collapsed to 1 and the water came out
    # binary, so the preview showed a hard-edged plate where the game did.
    #
    # Land is every cell that is not water, INCLUDING the ring outside the board, and
    # a cell's land area is its own rectangle - so a lattice corner measures 0 as soon
    # as any of the four cells meeting there is land.
    reach = max(2, int(np.ceil(depth_scale * 3)))
    land_cells = [(lx, ly) for ly in range(-reach, height + reach)
                  for lx in range(-reach, width + reach) if (lx, ly) not in water]

    def corner_distance(cx, cy):
        best = float(reach)
        for (lx, ly) in land_cells:
            dx = max(lx - cx, 0, cx - (lx + 1))
            dy = max(ly - cy, 0, cy - (ly + 1))
            best = min(best, float(np.hypot(dx, dy)))
        return best

    # One depth factor per cell corner (NW, NE, SE, SW - the shader's uv order).
    corner_depth = {}
    for y in range(height):
        for x in range(width):
            if (x, y) not in water:
                continue
            corner_depth[(x, y)] = np.array([
                corner_distance(x, y + 1),      # NW
                corner_distance(x + 1, y + 1),  # NE
                corner_distance(x + 1, y),      # SE
                corner_distance(x, y),          # SW
            ], dtype=np.float32) / depth_scale

    is_water = np.zeros((canvas_h, canvas_w), dtype=bool)
    depth = np.zeros((canvas_h, canvas_w), dtype=np.float32)
    for y in range(height):
        for x in range(width):
            if (x, y) not in water:
                continue
            rows = slice((height - 1 - y) * CELL_H, (height - y) * CELL_H)
            cols = slice(x * CELL_W, (x + 1) * CELL_W)
            is_water[rows, cols] = True
            nw, ne, se, sw = corner_depth[(x, y)]
            # local_v is 0 at the top of the cell, so 1 - local_v is the shader's uv.y.
            v = 1.0 - local_v[rows, cols]
            u = local_u[rows, cols]
            top = nw * (1 - u) + ne * u
            bottom = sw * (1 - u) + se * u
            depth[rows, cols] = bottom * (1 - v) + top * v

    # --- shoreline field, in cell units, exactly like LiquidShader.shoreField ----
    far = 1e6
    u = local_u
    v = 1.0 - local_v  # the shader's uv.y grows upwards
    shore = np.full((canvas_h, canvas_w), far, dtype=np.float32)
    corners = np.zeros((canvas_h, canvas_w), dtype=np.float32)
    for y in range(height):
        for x in range(width):
            if (x, y) not in water:
                continue
            borders = 0
            if (x, y + 1) not in water:
                borders |= 1
            if (x + 1, y) not in water:
                borders |= 2
            if (x, y - 1) not in water:
                borders |= 4
            if (x - 1, y) not in water:
                borders |= 8
            corner_mask = 0
            for bit, (dx, dy) in ((1, (1, 1)), (2, (1, -1)), (4, (-1, -1)), (8, (-1, 1))):
                if (x + dx, y + dy) not in water:
                    corner_mask |= bit
            rows = slice((height - 1 - y) * CELL_H, (height - y) * CELL_H)
            cols = slice(x * CELL_W, (x + 1) * CELL_W)
            field = np.full((CELL_H, CELL_W), far, dtype=np.float32)
            if borders & 1:
                field = np.minimum(field, 1.0 - v[rows, cols])
            if borders & 2:
                field = np.minimum(field, u[rows, cols])
            if borders & 4:
                field = np.minimum(field, v[rows, cols])
            if borders & 8:
                field = np.minimum(field, 1.0 - u[rows, cols])
            if corner_mask & 1:
                field = np.minimum(field, np.hypot(u[rows, cols] - 1.0, v[rows, cols] - 1.0))
            if corner_mask & 2:
                field = np.minimum(field, np.hypot(u[rows, cols] - 1.0, v[rows, cols]))
            if corner_mask & 4:
                field = np.minimum(field, np.hypot(u[rows, cols], v[rows, cols]))
            if corner_mask & 8:
                field = np.minimum(field, np.hypot(u[rows, cols], v[rows, cols] - 1.0))
            shore[rows, cols] = np.maximum(field, 0.0)
            corners[rows, cols] = corner_mask

    # --- base texture: tile the shipped seamless tile over whole cells -----------
    base_rgb = read_png(RESOURCES / "assets/pvzce/textures/scene/water_base.png").astype(np.float32) / 255.0
    tile_h, tile_w = base_rgb.shape[0], base_rgb.shape[1]
    base_u = ((cell_x + local_u) * base_scale) % 1.0
    base_v = ((cell_y + v) * base_scale) % 1.0
    tx = np.clip((base_u * tile_w).astype(np.int32), 0, tile_w - 1)
    ty = np.clip(((1.0 - base_v) * tile_h).astype(np.int32), 0, tile_h - 1)
    base = base_rgb[ty, tx]

    # --- the shader's terms, in the shader's order -------------------------------
    inset_raw = np.clip(shore / 0.55, 0, 1)
    inset = (1.0 - (inset_raw * inset_raw * (3.0 - 2.0 * inset_raw))) * np.where(is_water & features["shore"], 1.0, 0.0)
    if args.no_inset:
        inset = inset * 0.0
    body_u = local_u * (1 - inset) + 0.5 * inset
    body_v = (1.0 - local_v) * (1 - inset) + 0.5 * inset

    if args.bare:
        image = np.clip(base * 255.0 + 0.5, 0, 255).astype(np.uint8)
        write_png(Path(args.out), image)
        print(f"wrote {args.out} (bare base texture only)")
        return 0
    # The ramp is ASYMPTOTIC, not clamped: it approaches full depth without ever
    # arriving, so a body larger than its depth_scale keeps deepening instead of
    # flattening into a plate with a boundary around it - and it starts off LINEAR so
    # the waterline does not spend the whole gradient in its first fraction of a cell.
    # Must match LiquidShader.FRAGMENT.
    ramp = 1.0 - (1.0 + depth) * np.exp(-depth)
    shaped = ramp ** depth_gamma
    water_col = shallow[:3] * (1 - shaped[..., None]) + deep[:3] * shaped[..., None]
    # Absorption over a VISIBLE floor; see the shader comment for why this order.
    col = base * water_col * 2.2 * (1 - opacity) + water_col * 0.75 * opacity
    if debug_probe:
        ty_, tx_ = debug_probe
        print(f"[probe {tx_},{ty_}] is_water={bool(is_water[ty_, tx_])} depth={depth[ty_, tx_]:.3f} "
              f"shape={shaped[ty_, tx_]:.3f} base={np.round(base[ty_, tx_], 3)} "
              f"water={np.round(water_col[ty_, tx_], 3)} col={np.round(col[ty_, tx_], 3)} "
              f"shore={shore[ty_, tx_]:.3f}")

    flow_x, flow_y = args.time * wave_speed, args.time * wave_speed * 0.55
    # + body, NOT just the cell index: see the shader. Without the local coordinate
    # every term below is constant across its cell and the surface is a mosaic.
    world_x = cell_x + body_u
    world_y = cell_y + body_v
    wave_tilt = np.zeros_like(cell_x)
    if features["waves"]:
        coarse = fbm(world_x * wave_density + flow_x * 0.7, world_y * wave_density + flow_y * 0.7)
        fine = fbm(world_x * wave_density * 2.7 - flow_x, world_y * wave_density * 2.7 - flow_y)
        wave_tilt = (coarse - 0.5) * wave_amplitude + (fine - 0.5) * wave_amplitude * 0.4

    if features["caustics"]:
        # Keep these in step with LiquidShader's caustic block.
        bent_x = world_x + wave_tilt * 0.22
        bent_y = world_y + wave_tilt * 0.22
        web = (0.5 + 0.5 * np.cos(7.0 * (bent_x + 0.35 * bent_y) + args.time * 0.55)
               + 0.5 + 0.5 * np.cos(6.3 * (bent_y - 0.30 * bent_x) - args.time * 0.47)
               + 0.5 + 0.5 * np.cos(9.0 * (bent_x - 0.55 * bent_y) + args.time * 0.33))
        caustic = np.clip(web / 3.0, 0, 1) ** 4.0
        caustic = caustic * 0.55 * (1.0 - 0.6 * shaped)
        col = col + (caustic * caustics * 1.5 + caustics * wave_tilt * 0.14)[..., None] * np.array([0.72, 0.95, 0.88], dtype=np.float32)

    if args.seams:
        x0, y0 = 4 * CELL_W, (height - 1 - 2) * CELL_H
        sl = (slice(y0 + CELL_H // 4, y0 + 3 * CELL_H // 4), slice(x0 + CELL_W // 4, x0 + 3 * CELL_W // 4))
        for name, arr in (("base", base), ("water_col", water_col), ("wave_tilt", wave_tilt), ("col", col)):
            patch = arr[sl]
            print(f"  layer {name:10s} std {float(np.mean(np.std(patch, axis=(0, 1)))):7.3f}")

    surface_before_rim = col.copy()

    if features["specular"]:
        light_x, light_y = width * 0.5, height * 1.4
        off_x = world_x - light_x
        off_y = world_y - light_y
        distance = np.maximum(np.hypot(off_x, off_y), 1e-3)
        nx, ny = off_x / distance, off_y / distance
        slope = distance * 0.45 + wave_tilt * 0.9
        nz = np.ones_like(slope)
        length = np.sqrt((nx * slope) ** 2 + (ny * slope) ** 2 + 1.0)
        dot = np.clip((nx * nx * slope + ny * ny * slope + 0.35) / (length * np.sqrt(nx * nx + ny * ny + 0.1225)), 0, 1)
        highlight = dot ** specular_power
        falloff = 1.0 / (1.0 + distance * 0.35)
        col = col + highlight[..., None] * specular * 0.4 * falloff[..., None]

    if features["fresnel"]:
        openness = np.clip(depth_scale * 0.75 - shore, 0.0, 1.0)
        fres = np.clip(openness * fresnel_strength * 0.30, 0.0, 0.35)
        col = col * (1 - fres[..., None]) + reflect[:3] * np.array([0.55, 0.68, 0.88], dtype=np.float32) * fres[..., None]

    surface_after_terms = col.copy()

    # THE WATER BODY IS OPAQUE; only the waterline itself feathers out.
    #
    # The feather is driven by the CONTINUOUS distance-to-land field (`depth`, in
    # units of depth_scale), NOT by `shore`. `shore` is measured against each cell's
    # own outline, so two cells sharing an edge disagree about it completely - one
    # reads 255, the other 0 - and feathering on it cut a transparent slit along every
    # interior join, with the ground showing through. `depth` is sampled at the cell
    # CORNERS, so neighbours share those samples and the value is continuous.
    #
    # Must match LiquidShader.FEATHER_CELLS.
    feather = 0.12
    feather_norm = np.clip((depth * depth_scale) / feather, 0.0, 1.0)
    alpha = feather_norm * feather_norm * (3.0 - 2.0 * feather_norm)
    if features["shore"]:
        # NOTE the clamp BEFORE the smoothstep. A raw value feeds the polynomial
        # outside [0,1] and flips the result negative, which zeroed the whole mask.
        foam_norm = np.clip(shore / max(foam_width, 1e-3), 0.0, 1.0)
        foam_mask = 1.0 - (foam_norm * foam_norm * (3.0 - 2.0 * foam_norm))
        crest_norm = np.clip((shore - foam_width * 0.5) / max(foam_width * 0.65, 1e-3), 0.0, 1.0)
        crest_left = np.clip(shore / max(foam_width * 0.5, 1e-3), 0.0, 1.0)
        crest = ((1.0 - (crest_norm * crest_norm * (3.0 - 2.0 * crest_norm)))
                 * (crest_left * crest_left * (3.0 - 2.0 * crest_left)))
        # vShore is the per-cell foam strength; without ripples it is the cell's own
        # shoreStrength, so the preview uses 1.0 (its cells are all fully shore).
        strength = args.foam_strength
        col = col * (1 - np.clip(foam_mask * strength, 0, 0.85)[..., None]) + foam[:3] * np.clip(foam_mask * strength, 0, 0.85)[..., None]
        col = col * (1 - np.clip(crest * strength, 0, 0.30)[..., None]) + foam[:3] * np.clip(crest * strength, 0, 0.30)[..., None]

    if debug_probe:
        ty_, tx_ = debug_probe
        print(f"[after terms] col={np.round(col[ty_, tx_], 3)} alpha={alpha[ty_, tx_]:.3f}")
    if args.seams:
        # A cell join should produce a pixel-to-pixel jump NO BIGGER than the jumps
        # immediately beside it. Comparing against the image's average step instead -
        # the first version of this check - is meaningless: the caustic pattern's own
        # gradients are ~12 per pixel while a flat area's are ~0.1, so the same
        # harmless jump measured x60 in one place and x1 in another, and two rounds
        # were spent "fixing" frequencies that were never at fault.
        def jump(arr, x):
            return float(np.mean(np.abs(arr[200:800, x] - arr[200:800, x + 1])))

        # WITHIN-CELL VARIATION. A per-cell constant pattern has zero spread inside a
        # cell and a step at its border, which is invisible to a boundary-jump check
        # when the step happens to be small - that is how a surface made of flat
        # squares passed an earlier version of this tool.
        print("within-cell variation (a per-cell constant term scores ~0):")
        for cell in ((4, 2), (5, 3), (3, 2)):
            x0, y0 = cell[0] * CELL_W, (height - 1 - cell[1]) * CELL_H
            patch = col[y0 + CELL_H // 4:y0 + 3 * CELL_H // 4, x0 + CELL_W // 4:x0 + 3 * CELL_W // 4]
            print(f"  cell {cell}: colour std {float(np.mean(np.std(patch, axis=(0, 1)))):6.2f}/255")
        print("cell-join check (at-boundary jump vs its immediate neighbours):")
        for boundary in (320, 480, 640, 800, 1120):
            at = jump(col, boundary)
            near = float(np.mean([jump(col, boundary - 3), jump(col, boundary - 2),
                                  jump(col, boundary + 1), jump(col, boundary + 2)]))
            verdict = "OK" if at <= near * 3 + 0.5 else "SEAM"
            print(f"  x={boundary:4d}  at {at:6.2f}   neighbours {near:6.2f}   {verdict}")

    # --- composite over the unsodded dirt background -----------------------------
    # Grass, because that is what surrounds water on a real board. Previewing over
    # a flat brown stand-in hid how washed-out the surface actually was.
    background = np.array([0.22, 0.55, 0.16], dtype=np.float32)
    image = np.where(is_water[..., None], col * alpha[..., None] + background * (1 - alpha[..., None]), background)
    if debug_probe:
        ty_, tx_ = debug_probe
        print(f"[final]      {np.round(image[ty_, tx_], 3)}")
    image = np.clip(image * 255.0 + 0.5, 0, 255).astype(np.uint8)
    if args.grid:
        print("rendered cell map (top row first; W = reads as water, . = does not):")
        for y in range(height - 1, -1, -1):
            row = ""
            for x in range(width):
                px = image[(height - 1 - y) * CELL_H + CELL_H // 2,
                           x * CELL_W + CELL_W // 2] / 255.0
                row += "W" if (px[2] > px[1] > px[0]) else "."
            print("   ", row)
    write_png(Path(args.out), image)
    print(f"wrote {args.out} ({canvas_w}x{canvas_h}, level={args.level}, "
          f"quality={args.quality}, time={args.time}s, water cells={len(water)})")


if __name__ == "__main__":
    sys.exit(main())
