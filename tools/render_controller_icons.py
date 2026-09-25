#!/usr/bin/env python3
"""Render 128x128 card icons from generated controller animation JSON.

The card icons use the controller model's rest/idle pose (with a few
entity-specific overrides such as the armed potato mine) so the seed packet
shows the same artwork as the in-game animation instead of the old flat
placeholder sprites.

Run from the repository root:

    python3 tools/render_controller_icons.py

Outputs ``assets/pvzce/textures/gui/cards/<entity>.png``.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np
from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_RESOURCES = REPO_ROOT / "pvzce-game" / "src" / "main" / "resources"
DEFAULT_NAMESPACE = "pvzce"
ICON_SIZE = 128
PIXELS_PER_CELL = 200.0
PADDING = 8.0

# entity -> (animation clip, sample time seconds)
ICON_POSES: Dict[str, Tuple[str, float]] = {
    "pea_shooter": ("idle", 0.0),
    "sunflower": ("idle", 0.0),
    "cherry_bomb": ("idle", 0.0),
    "wall_nut": ("idle", 0.0),
    "potato_mine": ("armed_loop", 0.0),
    "chomper": ("idle", 0.0),
    "kernel_pult": ("idle", 0.0),
    "marigold": ("idle", 0.0),
    "lily_pad": ("idle", 0.0),
    "tangle_kelp": ("idle", 0.0),
    "flower_pot": ("idle", 0.0),
    "coffee_bean": ("idle", 0.0),
    # The shooter and pult lines added with the original's own art.
    "snow_pea": ("idle", 0.0),
    "repeater": ("idle", 0.0),
    "gatling_pea": ("idle", 0.0),
    "threepeater": ("idle", 0.0),
    "split_pea": ("idle", 0.0),
    "cactus": ("idle", 0.0),
    "cabbage_pult": ("idle", 0.0),
    "melon_pult": ("idle", 0.0),
    "winter_melon": ("idle", 0.0),
    "jalapeno": ("idle", 0.0),
    "doom_shroom": ("idle", 0.0),
    "ice_shroom": ("idle", 0.0),
    "scaredy_shroom": ("idle", 0.0),
    "squash": ("idle", 0.0),
    "puff_shroom": ("idle", 0.0),
    "sun_shroom": ("idle", 0.0),
    # A tool card, drawn from Hammer.reanim's own idle pose.
    "hammer": ("idle", 0.0),
}

# Content id -> the directory its animation lives in, mirroring the content JSON's
# ``animation_dir``. The art is grouped by kind while the ids stay flat, so this table
# is what lets a tool that reads the files directly find them.
ANIMATION_DIRS: Dict[str, str] = {
    "sun": "resource",
    "coin_silver": "resource",
    "coin_gold": "resource",
    "diamond": "resource",
    "pea_shooter": "plant/attacker",
    "chomper": "plant/attacker",
    "kernel_pult": "plant/attacker",
    "snow_pea": "plant/attacker",
    "repeater": "plant/attacker",
    "gatling_pea": "plant/attacker",
    "threepeater": "plant/attacker",
    "split_pea": "plant/attacker",
    "cactus": "plant/attacker",
    "cabbage_pult": "plant/attacker",
    "melon_pult": "plant/attacker",
    "winter_melon": "plant/attacker",
    "puff_shroom": "plant/attacker",
    "fume_shroom": "plant/attacker",
    "scaredy_shroom": "plant/attacker",
    "sunflower": "plant/producer",
    "sun_shroom": "plant/producer",
    "marigold": "plant/producer",
    "wall_nut": "plant/defense",
    "lily_pad": "plant/environment",
    "tangle_kelp": "plant/environment",
    "flower_pot": "plant/environment",
    "coffee_bean": "plant/environment",
    "cherry_bomb": "plant/special",
    "potato_mine": "plant/special",
    "jalapeno": "plant/special",
    "doom_shroom": "plant/special",
    "ice_shroom": "plant/special",
    "squash": "plant/special",
    "grave_buster": "plant/special",
    "hypno_shroom": "plant/special",
    "hammer": "tool",
    "basic_zombie": "zombie/basic",
    "flag_zombie": "zombie/basic",
    "conehead_zombie": "zombie/armored",
    "buckethead_zombie": "zombie/armored",
    "door_zombie": "zombie/armored",
    "newspaper_zombie": "zombie/armored",
    "balloon_zombie": "zombie/special",
    "pole_vaulter_zombie": "zombie/special",
    "miner_zombie": "zombie/underground",
    "gargantuar": "zombie/giant",
    "imp": "zombie/giant",
    "zombie_boss": "zombie/boss",
    # World 3's second half and world 4. The group must match the `group` field of the converter
    # config that produced each file, or this renderer looks for an animation that is not there.
    "spikeweed": "plant/special",
    "torchwood": "plant/special",
    "tall_nut": "plant/defense",
    "sea_shroom": "plant/attacker",
    "plantern": "plant/special",
    "blover": "plant/special",
    "starfruit": "plant/attacker",
    "pumpkin": "plant/defense",
    "magnet_shroom": "plant/special",
}

PLANT_ENTITIES = [
    "pea_shooter", "sunflower", "cherry_bomb", "wall_nut", "potato_mine",
    "chomper", "kernel_pult", "marigold", "lily_pad", "flower_pot", "coffee_bean",
    # The shooter and pult lines, converted from the original's own reanims.
    "snow_pea", "repeater", "gatling_pea", "threepeater", "split_pea", "cactus",
    "cabbage_pult", "melon_pult", "winter_melon", "jalapeno", "doom_shroom", "squash",
    "puff_shroom", "sun_shroom",
    # 2-2 and 2-3's unlocks, converted from the original's own reanims like the rest.
    "fume_shroom", "grave_buster", "hypno_shroom",
    # The Night area's last two plants.
    "scaredy_shroom", "ice_shroom",
    # The pool's own plant: it is drawn from its converted reanim like every other card here.
    "tangle_kelp",
    # World 3's second half and world 4: nine plants, each drawn from its own reanim like the
    # rest. The rake is here rather than in TOOL_ENTITIES because its card is a picture of the
    # fixture, not of a model the player holds - and `gen_ui_icons.py` draws it by hand, so it is
    # deliberately *not* in this list.
    "spikeweed", "torchwood", "tall_nut", "sea_shroom", "plantern",
    "blover", "starfruit", "pumpkin", "magnet_shroom",
]

# Tools whose card is drawn from a controller model rather than a flat PNG.
#
# The shovel and the glove have always had a single sprite and keep it, but the hammer
# only exists as a reanim: its card pointed at
# `textures/entities/tool/hammer/hammer`, and that file was never generated - the
# directory holds the three *parts* (`1`/`2`/`3`) the model is built from. Rendering the
# model is therefore the only way to get a picture of a hammer, and it is the same
# painter the plant cards use.
TOOL_ENTITIES = [
    "hammer",
]


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--entity", action="append", default=None,
                        help="only render this entity icon (repeatable)")
    parser.add_argument("--resources", type=Path, default=DEFAULT_RESOURCES)
    parser.add_argument("--namespace", default=DEFAULT_NAMESPACE)
    return parser.parse_args(argv)


def resource_path(resources: Path, namespace: str, identifier: str, suffix: str = ".png") -> Path:
    ns, path = identifier.split(":", 1)
    return Path(str(resources / "assets" / ns / path.lstrip("/")) + suffix)


def vector(value, length: int, fallback: Sequence[float]) -> np.ndarray:
    if not isinstance(value, list) or len(value) < length:
        return np.array(fallback, dtype=float)
    return np.array([float(value[i]) for i in range(length)], dtype=float)


def sample_vector(channel, time: float, fallback: Sequence[float], length: int) -> np.ndarray:
    if not isinstance(channel, dict) or not channel:
        return np.array(fallback, dtype=float)
    items = sorted((float(key), vector(value, length, fallback)) for key, value in channel.items())
    if time <= items[0][0]:
        return items[0][1]
    if time >= items[-1][0]:
        return items[-1][1]
    for (t0, v0), (t1, v1) in zip(items, items[1:]):
        if time < t1:
            span = max(1.0E-9, t1 - t0)
            alpha = max(0.0, min(1.0, (time - t0) / span))
            return v0 + (v1 - v0) * alpha
    return items[-1][1]


def sample_visible(channel, time: float, fallback: bool = True) -> bool:
    if not isinstance(channel, dict) or not channel:
        return fallback
    items = sorted((float(key), bool(value)) for key, value in channel.items())
    if time < items[0][0]:
        return items[0][1]
    value = items[0][1]
    for key, candidate in items:
        if key <= time:
            value = candidate
    return value


def local_matrix(translation: np.ndarray, rotation: np.ndarray, scale: np.ndarray, pivot: np.ndarray):
    rx, ry, rz = (math.radians(float(v)) for v in rotation)
    sx, sy = float(scale[0]), float(scale[1])
    a = sx * math.cos(rx)
    b = sy * math.sin(ry)
    c = -sx * math.sin(rx)
    d = sy * math.cos(ry)
    cz, sz = math.cos(rz), math.sin(rz)
    m00 = cz * a - sz * c
    m01 = cz * b - sz * d
    m10 = sz * a + cz * c
    m11 = sz * b + cz * d
    tx = float(pivot[0]) + float(translation[0])
    ty = float(pivot[1]) + float(translation[1])
    return (m00, m01, tx, m10, m11, ty)


def multiply(a, b):
    am00, am01, am02, am10, am11, am12 = a
    bm00, bm01, bm02, bm10, bm11, bm12 = b
    return (
        am00 * bm00 + am01 * bm10,
        am00 * bm01 + am01 * bm11,
        am00 * bm02 + am01 * bm12 + am02,
        am10 * bm00 + am11 * bm10,
        am10 * bm01 + am11 * bm11,
        am10 * bm02 + am11 * bm12 + am12,
    )


def transform(matrix, x: float, y: float) -> Tuple[float, float]:
    m00, m01, m02, m10, m11, m12 = matrix
    return (m00 * x + m01 * y + m02, m10 * x + m11 * y + m12)


def sample_pose(animation: dict, model_bones: Dict[str, dict], time: float) -> Dict[str, dict]:
    clip_bones = animation.get("bones", {})
    poses: Dict[str, dict] = {}
    for name, bone in model_bones.items():
        transform = bone.get("transform", {}) if isinstance(bone.get("transform"), dict) else {}
        rest_translation = vector(transform.get("translation"), 2, [0.0, 0.0])
        rest_rotation = vector(transform.get("rotation"), 3, [0.0, 0.0, 0.0])
        rest_scale = vector(transform.get("scale"), 2, [1.0, 1.0])
        channels = clip_bones.get(name, {}) if isinstance(clip_bones.get(name), dict) else {}
        poses[name] = {
            "translation": sample_vector(channels.get("translation"), time, rest_translation, 2),
            "rotation": sample_vector(channels.get("rotation"), time, rest_rotation, 3),
            "scale": sample_vector(channels.get("scale"), time, rest_scale, 2),
            "visible": sample_visible(channels.get("visible"), time, True),
        }
    return poses


def world_matrices(model_bones: Dict[str, dict], poses: Dict[str, dict]) -> Dict[str, tuple]:
    world: Dict[str, tuple] = {}
    visiting: set[str] = set()

    def resolve(name: str) -> tuple:
        if name in world:
            return world[name]
        if name in visiting:
            return (1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        visiting.add(name)
        bone = model_bones.get(name, {})
        pose = poses.get(name, {})
        pivot = vector(bone.get("pivot"), 2, [0.0, 0.0])
        local = local_matrix(
            pose.get("translation", np.zeros(2)),
            pose.get("rotation", np.zeros(3)),
            pose.get("scale", np.ones(2)),
            pivot,
        )
        parent_name = bone.get("parent")
        parent = resolve(parent_name) if isinstance(parent_name, str) and parent_name in model_bones else (1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        matrix = multiply(parent, local)
        world[name] = matrix
        visiting.discard(name)
        return matrix

    for name in model_bones:
        resolve(name)
    return world


def draw_quad(canvas: np.ndarray, source: np.ndarray, points, uvs) -> None:
    height, width = canvas.shape[:2]
    source_height, source_width = source.shape[:2]
    # JSON UVs are top-left pixel coordinates; convert them to 0..1 here.
    uvs = [(float(u) / source_width, float(v) / source_height) for u, v in uvs]
    xs = [point[0] for point in points]
    ys = [point[1] for point in points]
    min_x = max(0, int(math.floor(min(xs))) - 1)
    max_x = min(width - 1, int(math.ceil(max(xs))) + 1)
    min_y = max(0, int(math.floor(min(ys))) - 1)
    max_y = min(height - 1, int(math.ceil(max(ys))) + 1)

    for tri in ((0, 1, 2), (0, 2, 3)):
        (ax, ay), (bx, by), (cx, cy) = [points[i] for i in tri]
        denominator = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
        if abs(denominator) < 1.0E-9:
            continue
        for py in range(min_y, max_y + 1):
            for px in range(min_x, max_x + 1):
                fx, fy = px + 0.5, py + 0.5
                l1 = ((by - cy) * (fx - cx) + (cx - bx) * (fy - cy)) / denominator
                l2 = ((cy - ay) * (fx - cx) + (ax - cx) * (fy - cy)) / denominator
                l3 = 1.0 - l1 - l2
                if l1 < -1.0E-4 or l2 < -1.0E-4 or l3 < -1.0E-4:
                    continue
                u = l1 * uvs[tri[0]][0] + l2 * uvs[tri[1]][0] + l3 * uvs[tri[2]][0]
                v = l1 * uvs[tri[0]][1] + l2 * uvs[tri[1]][1] + l3 * uvs[tri[2]][1]
                sx = min(source_width - 1, max(0, int(u * source_width)))
                sy = min(source_height - 1, max(0, int(v * source_height)))
                src = source[sy, sx]
                alpha = float(src[3])
                if alpha <= 0.0:
                    continue
                canvas[py, px, :3] = src[:3] * alpha + canvas[py, px, :3] * (1.0 - alpha)
                canvas[py, px, 3] = alpha + canvas[py, px, 3] * (1.0 - alpha)


def render_entity(entity: str, resources: Path, namespace: str) -> Path:
    animation_path = (resources / "assets" / namespace / "animations"
                      / ANIMATION_DIRS.get(entity, "") / f"{entity}.json")
    if not animation_path.is_file():
        raise SystemExit(f"Missing animation JSON: {animation_path}")
    data = json.loads(animation_path.read_text(encoding="utf-8"))
    model = data.get("model", {})
    model_bones_list = model.get("bones", [])
    model_bones = {bone.get("name"): bone for bone in model_bones_list if isinstance(bone, dict) and bone.get("name")}
    animations = data.get("animations", {})
    animation_name, sample_time = ICON_POSES.get(entity, ("idle", 0.0))
    if animation_name not in animations:
        animation_name = "idle" if "idle" in animations else next(iter(animations))
    animation = animations[animation_name]

    poses = sample_pose(animation, model_bones, sample_time)
    world = world_matrices(model_bones, poses)

    # First pass: collect transformed quads and canvas bounds.
    quads = []
    min_x = min_y = float("inf")
    max_x = max_y = float("-inf")
    for name, bone in model_bones.items():
        pose = poses.get(name, {})
        if not pose.get("visible", True):
            continue
        matrix = world.get(name)
        if matrix is None:
            continue
        for part in bone.get("parts", []):
            if not isinstance(part, dict):
                continue
            texture_id = part.get("texture")
            if not isinstance(texture_id, str):
                continue
            texture_path = resource_path(resources, namespace, texture_id)
            if not texture_path.is_file():
                continue
            uv = vector(part.get("uv"), 4, [0.0, 0.0, 1.0, 1.0])
            size = vector(part.get("size"), 2, [1.0, 1.0])
            offset = vector(part.get("offset"), 2, [0.0, 0.0])
            half_w = size[0] * 0.5
            half_h = size[1] * 0.5
            local = [
                (offset[0] - half_w, offset[1] - half_h),  # bl
                (offset[0] + half_w, offset[1] - half_h),  # br
                (offset[0] + half_w, offset[1] + half_h),  # tr
                (offset[0] - half_w, offset[1] + half_h),  # tl
            ]
            points = []
            for x, y in local:
                wx, wy = transform(matrix, float(x), float(y))
                points.append((wx * PIXELS_PER_CELL, -wy * PIXELS_PER_CELL))
            uvs = [
                (uv[0], uv[3]),  # bl -> bottom-left of source
                (uv[2], uv[3]),  # br -> bottom-right
                (uv[2], uv[1]),  # tr -> top-right
                (uv[0], uv[1]),  # tl -> top-left
            ]
            for px, py in points:
                min_x = min(min_x, px)
                max_x = max(max_x, px)
                min_y = min(min_y, py)
                max_y = max(max_y, py)
            quads.append((float(part.get("z", 0.0)), texture_path, points, uvs))

    if not quads or not math.isfinite(min_x):
        raise SystemExit(f"{entity}: no visible parts to render")

    width = int(math.ceil(max_x - min_x + PADDING * 2.0))
    height = int(math.ceil(max_y - min_y + PADDING * 2.0))
    offset_x = -min_x + PADDING
    offset_y = -min_y + PADDING
    canvas = np.zeros((max(1, height), max(1, width), 4), dtype=np.float32)

    # Painter's order by the part's own ``z``. Drawing in model order put the front
    # leaf over the heads it is supposed to sit behind, which is what made the
    # threepeater, split pea and repeater icons come out as a bare leaf.
    for _, texture_path, points, uvs in sorted(quads, key=lambda quad: quad[0]):
        source = np.asarray(Image.open(texture_path).convert("RGBA"), dtype=np.float32) / 255.0
        shifted = [(x + offset_x, y + offset_y) for x, y in points]
        draw_quad(canvas, source, shifted, uvs)

    alpha = canvas[:, :, 3]
    ys, xs = np.nonzero(alpha > 0.01)
    if len(xs) == 0:
        raise SystemExit(f"{entity}: rendered icon is empty")
    crop = canvas[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    crop_image = Image.fromarray((np.clip(crop, 0.0, 1.0) * 255.0).astype(np.uint8))
    scale = min(ICON_SIZE / crop_image.width, ICON_SIZE / crop_image.height)
    new_size = (max(1, int(round(crop_image.width * scale))), max(1, int(round(crop_image.height * scale))))
    resized = crop_image.resize(new_size, Image.LANCZOS)
    icon = Image.new("RGBA", (ICON_SIZE, ICON_SIZE), (0, 0, 0, 0))
    icon.paste(resized, ((ICON_SIZE - new_size[0]) // 2, (ICON_SIZE - new_size[1]) // 2), resized)

    output_dir = resources / "assets" / namespace / "textures" / "gui" / "cards"
    output_dir.mkdir(parents=True, exist_ok=True)
    output = output_dir / f"{entity}.png"
    icon.save(output)
    return output


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    wanted = set(args.entity) if args.entity else None
    entities = [entity for entity in PLANT_ENTITIES + TOOL_ENTITIES
                if wanted is None or entity in wanted]
    if not entities:
        print("No matching entities", file=sys.stderr)
        return 2
    for entity in entities:
        try:
            output = render_entity(entity, args.resources, args.namespace)
            print(f"{entity}: {output}")
        except SystemExit as exc:
            print(f"ERROR {entity}: {exc}", file=sys.stderr)
            raise
    print(f"Rendered {len(entities)} card icon(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
