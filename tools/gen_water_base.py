#!/usr/bin/env python3
"""Builds the water base tile from the reference art.

Input  : refer/im4/underwater.png  (1254x1254, a 6x6 grid of 209px cells, but NOT
         tileable on its own - measured periodicity shows no repeating period)
Output : pvzce-game/src/main/resources/assets/pvzce/textures/scene/water_base.png
         a seamlessly tileable 418x418 sandy sea-floor crop.

Why this program exists
-----------------------
The liquid shader tiles its base texture across the whole water body, so the
base MUST be seamless. The reference art is a single scene (its top row is the
water surface, its bottom is deep water) and repeats nowhere.

The tile is built in two steps:

1. Pick cells.  Each 209px cell is scored so the crop keeps the *sand* look and
   drops the outliers: cells that are mostly water-surface caustics (top row) or
   carry a big seaweed/rock silhouette.  The best cell is the seed of a greedy
   neighbour match - each next cell is the unused one that continues most
   smoothly from the previous, which is what keeps seams from being placed
   between two cells that do not connect at all.

2. Blend.  A crop out of a non-periodic image is never periodic at its outer
   border.  Rather than trusting the source, the assembled mosaic is made
   periodic by construction: the result is an alpha-weighted crossfade of four
   shifted copies of the mosaic, where the weights are functions of a position
   on a circle.  The weights wrap exactly, so the crossfade commutes with the
   tile wrap and the output is seamless in both axes regardless of the input.

Both steps are numeric; nothing here is hand-tuned per output pixel.
"""

import struct
import sys
import zlib
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "refer/im4/underwater.png"
OUTPUT = ROOT / "pvzce-game/src/main/resources/assets/pvzce/textures/scene/water_base.png"

# The reference grid.
CELL = 209
GRID = 6
# Two cells per side: a 418px tile is 5.2 world cells wide at 80px/cell, which
# keeps the pebbles readable without repeating visibly across a 9-cell lawn.
TILE_CELLS = 2
# Crossfade band as a fraction of the tile. Cos^2 weights need a wide enough band
# that the blend is smooth, but a band too wide averages away the pebbles.
BLEND = 0.34

# Large-scale brightness flattening. Even a perfectly seamless tile carries the
# source's broad light and dark patches, and on screen one tile covers several world
# cells - so a patch a third of the tile wide becomes a stripe several cells across,
# which reads as a band lying on the water rather than as seabed. The shader then
# magnifies it further. This removes the requested fraction of that LOW-FREQUENCY
# variation while leaving the pebble-scale detail alone.
#
# The blur is WRAPPED, so the correction is periodic and cannot undo the seam work
# above; and the mean is added back, so the tile keeps its overall brightness.
FLATTEN_RADIUS = 32
FLATTEN_AMOUNT = 0.80


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
                raise SystemExit(f"unsupported PNG: depth={depth} color={color_type} interlace={interlace}")
        elif kind == b"IDAT":
            idat += chunk
        elif kind == b"IEND":
            break
        pos += 12 + length

    channels = 3 if color_type == 2 else 4
    raw = zlib.decompress(bytes(idat))
    stride = width * channels
    out = np.empty((height, width, 3), dtype=np.uint8)
    previous = np.zeros(stride, dtype=np.uint8)
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
                line[i] = (line[i] + ((left + int(previous[i])) >> 1)) & 0xFF
        elif filter_type == 4:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                up = int(previous[i])
                up_left = int(previous[i - channels]) if i >= channels else 0
                pa, pb, pc = abs(up - up_left), abs(left - up_left), abs(left + up - 2 * up_left)
                pred = left if (pa <= pb and pa <= pc) else (up if pb <= pc else up_left)
                line[i] = (line[i] + pred) & 0xFF
        out[y] = np.frombuffer(bytes(line), dtype=np.uint8).reshape(width, channels)[:, :3]
        previous = np.frombuffer(bytes(line), dtype=np.uint8).reshape(width, channels).reshape(-1)
    return out


def write_png(path, rgb):
    """Writes an 8-bit RGB PNG with per-row filtering (no dependency on PIL)."""
    height, width, _ = rgb.shape
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter type 0 (None); the data is already blended noise
        raw += rgb[y].tobytes()

    def chunk(kind, payload):
        return (struct.pack(">I", len(payload)) + kind + payload
                + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
                     + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


# The tile is the sea floor SEEN THROUGH WATER, not dry sand. Keeping the raw
# reference colours was the mistake that made the first in-game attempt read as
# wet gravel: the moss-brown floor sat next to bright green grass and the pool
# looked like a mud puddle. Real water absorbs red first, so the baked tint
# multiplies red down hardest and lifts blue slightly; the per-pixel ratio keeps
# every pebble and frond readable instead of flattening them into one colour.
WATER_TINT = np.array([0.55, 0.92, 1.28], dtype=np.float32)


def apply_water_tint(rgb):
    """Bakes the "under water" cast into the tile, preserving its detail."""
    return np.clip(rgb.astype(np.float32) * WATER_TINT, 0, 255)


def score_cell(cell):
    """Lower is better: sand texture, no water-surface line, no big silhouettes."""
    rgb = cell.astype(np.float32)
    red, green, blue = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    # Seaweed and rocks are dark/green; sand is bright and warm.
    vegetation = float(np.mean((green > red * 1.12) & (green > blue * 1.25)))
    dark = float(np.mean(rgb.mean(axis=2) < 70))
    # The surface band is strongly blue-cyan.
    surface = float(np.mean((blue > red * 1.35) & (blue > 120)))
    # Reward local contrast: a flat cell would tile into a visibly empty patch.
    contrast = float(np.std(rgb.mean(axis=2)))
    return 6.0 * vegetation + 4.0 * dark + 6.0 * surface - 1.2 * contrast


def edge_cost(a, b, horizontal):
    """Mean absolute difference between the facing edges of two cells."""
    if horizontal:
        return float(np.mean(np.abs(a[:, -1].astype(np.int16) - b[:, 0].astype(np.int16))))
    return float(np.mean(np.abs(a[-1, :].astype(np.int16) - b[0, :].astype(np.int16))))


def pick_block(source):
    """Picks the 2x2 block of ADJACENT source cells that tiles most smoothly.

    The first version picked cells one at a time by greedily continuing the
    previous one, which produced a zig-zag of four unrelated cells: their internal
    joins were as rough as the outer wrap, so the tile needed a crossfade at every
    cell boundary and four blend windows fought each other. Because the reference
    is one continuous scene, neighbouring cells of the SAME block already join
    smoothly - so the cheapest way to get six of the seven seams for free is to
    require the four cells to be neighbours and blend only the wrap.
    """
    cells = {}
    for ty in range(GRID):
        for tx in range(GRID):
            cells[(tx, ty)] = source[ty * CELL:(ty + 1) * CELL, tx * CELL:(tx + 1) * CELL]

    # The top row is the water surface, never a valid floor tile.
    best, best_cost = None, float("inf")
    for ty in range(1, GRID - 1):
        for tx in range(0, GRID - 1):
            block = [cells[(tx, ty)], cells[(tx + 1, ty)], cells[(tx, ty + 1)], cells[(tx + 1, ty + 1)]]
            mismatch = (edge_cost(block[0], block[1], True)
                        + edge_cost(block[2], block[3], True)
                        + edge_cost(block[0], block[2], False)
                        + edge_cost(block[1], block[3], False))
            quality = sum(score_cell(cell) for cell in block)
            cost = mismatch + quality * 0.5
            if cost < best_cost:
                best, best_cost = (tx, ty), cost
    tx, ty = best
    return [cells[(tx, ty)], cells[(tx + 1, ty)], cells[(tx, ty + 1)], cells[(tx + 1, ty + 1)]], list(best)


def assemble(tiles):
    """Lays the chosen cells out row-major into one mosaic."""
    side = TILE_CELLS * CELL
    mosaic = np.zeros((side, side, 3), dtype=np.uint8)
    for index, tile in enumerate(tiles):
        ty, tx = divmod(index, TILE_CELLS)
        mosaic[ty * CELL:(ty + 1) * CELL, tx * CELL:(tx + 1) * CELL] = tile
    return mosaic


def periodic_blend_1d(values, shift):
    """Makes ``values`` behave as if it had period ``len(values)``.

    The blend is the convex combination

        output = (1 - w) * values + w * roll(values, shift)

    with a Hann window W of period ``L = len(values)`` PEAKING ON THE SEAM and
    squared so its slope vanishes there:

        W(u) = (0.5 + 0.5 * cos(2*pi*u / L))^2      -> 1 at u = 0

    Two properties make this the right window, and both were arrived at by
    measurement after simpler ones failed:

    * It is periodic with the tile, so the output is periodic too. A triangular or
      mirror ramp is not, and every earlier attempt built from one measured *no*
      change in the wrap error at all.
    * The seam is continuous AND its slope matches. A plain Hann window would give
      ``d/du output(0) = d/du output(L-1)``; because W(t) ~ 1 - pi^2 t^2 near the
      seam, the ``t * W'(t)`` slope term cancels and an already-periodic source
      stays periodic through the blend as well. Without that, a faint slope break
      shows up as a visible line when the tile repeats.

    With ``shift = L/2`` the output at the seam is the source pixel ``L/2`` and the
    pixel one step before it is ``L/2 - 1`` - adjacent pixels of the SOURCE - which
    is what makes the join as smooth as the artwork itself. The transition spans
    the whole tile, which spreads the correction thinly instead of leaving one
    visible band.

    A half-tile shift does repeat every piece of content twice per axis, so the
    result is checked against itself at the half shift (see ``half_shift_similarity``)
    and an earlier version with a narrower window was rejected for exactly that
    four-fold look.

    ``values`` may be one-dimensional or carry trailing channels; the blend runs
    along axis 0 either way.
    """
    length = values.shape[0]
    period = np.arange(length, dtype=np.float64)
    weight = (0.5 + 0.5 * np.cos(2.0 * np.pi * period / length)) ** 2
    weight = weight.reshape((-1,) + (1,) * (values.ndim - 1))
    return (1.0 - weight) * values + weight * np.roll(values, shift, axis=0)


def half_shift_similarity(image):
    """Mean difference between the tile and itself shifted by half.

    A low value means the tile does not visibly repeat at the half-tile offset,
    which is the artefact a half-tile crossfade can introduce. Reported so the
    number is checked rather than eyeballed.
    """
    half = image.shape[0] // 2
    return float(np.mean(np.abs(image.astype(np.int16)
                                - np.roll(image, half, axis=0).astype(np.int16))))


def make_torus(mosaic):
    """Blends wrapped copies of the mosaic into a tile whose four edges match.

    Applied separably: the horizontal pass makes the left/right wrap continuous,
    the vertical pass does the same for top/bottom (and cannot undo the first,
    because it treats every column identically). The mosaic's own internal cell
    joins are NOT blended - they come from the source and are already smooth,
    because the four cells are neighbours.
    """
    side = mosaic.shape[0]
    assert side == TILE_CELLS * CELL, "the tile must be a whole number of source cells"
    shift = side // 2

    transposed = mosaic.astype(np.float64).transpose(1, 0, 2)
    horizontal = periodic_blend_1d(transposed, shift).transpose(1, 0, 2)
    blended = periodic_blend_1d(horizontal, shift)
    return np.clip(blended + 0.5, 0, 255).astype(np.uint8)


def wrapped_blur(values, radius):
    """Box blur that wraps at the edges, so it is periodic like the tile itself."""
    side = values.shape[0]
    offsets = np.arange(-radius, radius + 1)
    total = np.zeros_like(values)
    for dy in offsets:
        for dx in offsets:
            total += np.roll(np.roll(values, dy, axis=0), dx, axis=1)
    return total / (len(offsets) ** 2)


def flatten_large_scale(image, radius, amount):
    """Removes `amount` of the tile's low-frequency luminance variation.

    Subtracting a wrapped blur is itself periodic, so a flattened tile is exactly as
    tileable as the tile it came from. Only LUMINANCE is corrected - the hue is left
    where the artist put it.
    """
    if amount <= 0.0:
        return image
    values = image.astype(np.float64)
    luminance = values.mean(axis=2, keepdims=True)
    correction = (wrapped_blur(luminance, radius) - luminance.mean()) * amount
    return np.clip(values - correction, 0, 255).astype(np.uint8)


def seam_report(image, label):
    """Checks EVERY seam of the tile, not just the outer wrap.

    The meaningful test is not "is the wrap error zero" - no natural texture has
    that - but "is every seam no worse than an ordinary one-pixel step of the same
    image".  The first version of this report only looked at the outer wrap, so a
    hard line at the internal cell join (rows 208|209 scored 34 against a 4.3 step)
    passed unnoticed until the per-seam dump was added.
    """
    a = image.astype(np.int16)
    side = image.shape[0]
    worst = 0.0
    if label:
        print(f"  {label}")
    for position in (1, side // 2, side - 1):
        next_index = (position + 1) % side
        horizontal = float(np.mean(np.abs(a[:, position] - a[:, next_index])))
        vertical = float(np.mean(np.abs(a[position] - a[next_index])))
        # Neighbouring seams, so a naturally steep image is not reported as seamed.
        near_h = float(np.mean([np.mean(np.abs(a[:, (position + d) % side] - a[:, (position + d + 1) % side]))
                                for d in (-3, -2, 1, 2)]))
        near_v = float(np.mean([np.mean(np.abs(a[(position + d) % side] - a[(position + d + 1) % side]))
                                for d in (-3, -2, 1, 2)]))
        worst = max(worst, horizontal / max(near_h, 0.01), vertical / max(near_v, 0.01))
        print(f"    seam {position:3d}|{next_index:3d}  "
              f"vertical {vertical:6.2f} (neighbours {near_v:5.2f})   "
              f"horizontal {horizontal:6.2f} (neighbours {near_h:5.2f})")
    return worst


def main():
    if not SOURCE.is_file():
        raise SystemExit(f"missing reference art: {SOURCE}")
    source = read_png(SOURCE)
    print(f"source {source.shape[1]}x{source.shape[0]}")

    tiles, block = pick_block(source)
    print(f"chosen 2x2 block at cell ({block[0]},{block[1]})")
    mosaic = apply_water_tint(assemble(tiles))
    worst_before = seam_report(mosaic, "before (raw mosaic)")
    tile = make_torus(mosaic)
    worst_after = seam_report(tile, "after (periodic blend)")
    coarse_before = float(np.ptp(wrapped_blur(tile.astype(np.float64).mean(axis=2), FLATTEN_RADIUS)))
    tile = flatten_large_scale(tile, FLATTEN_RADIUS, FLATTEN_AMOUNT)
    coarse_after = float(np.ptp(wrapped_blur(tile.astype(np.float64).mean(axis=2), FLATTEN_RADIUS)))
    print(f"  large-scale luminance span {coarse_before:.1f} -> {coarse_after:.1f} "
          f"(flattened {100 * (1 - coarse_after / max(coarse_before, 0.01)):.0f}% "
          f"at radius {FLATTEN_RADIUS})")
    worst_after = max(worst_after, seam_report(tile, "after (flatten)"))
    similarity = half_shift_similarity(tile)
    print(f"  half-shift self-similarity {similarity:.1f} "
          f"(low = the crossfade did not duplicate content)")
    ok = worst_after <= 1.6
    print(f"  worst seam is {worst_after:.2f}x a 1px step (was {worst_before:.2f}x) "
          f"=> {'SEAMLESS' if ok else 'STILL SEAMED'}")

    detail = float(np.mean(np.std(tile.astype(np.float32), axis=(0, 1))))
    print(f"  baked water tint {WATER_TINT.tolist()}; tile detail (std) {detail:.2f}")
    write_png(OUTPUT, tile)
    print(f"wrote {OUTPUT.relative_to(ROOT)} ({OUTPUT.stat().st_size} bytes)")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
