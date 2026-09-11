#!/usr/bin/env python3
"""Convert a PvZ1 reanim XML file into the PVZCE 2D-controller JSON format.

The converter intentionally has no third-party dependencies.  It:

* wraps the root-less reanim XML in a synthetic ``<root>`` element;
* resolves per-track frame inheritance (``x``, ``y``, ``kx``, ``ky``, ``sx``,
  ``sy``, ``f`` and ``i``);
* scans the reanim directory and maps ``IMAGE_REANIM_*`` references to the
  actual PNG files by normalized/fuzzy name matching;
* splits tracks that switch images (for example blink1/blink2) into one bone
  per image, using the ``visible`` channel;
* computes a joint visible-frame bounding box and maps it to the PVZCE
  controller coordinate system (1 pixel = 0.01 cells, y axis up);
* writes ``idle`` and ``shoot`` animations using the visible ranges of the
  ``anim_full_idle`` and ``anim_shooting`` mask tracks;
* copies only the referenced PNGs to the output texture directory.

Run from the repository root with no arguments to generate the PeaShooter
resources:

    python3 tools/reanim_to_pvzce.py

Optional arguments allow overriding the input, JSON output and texture output
paths.
"""

from __future__ import annotations

import argparse
import difflib
import json
import math
import os
import re
import shutil
import struct
import sys
import tempfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

REPO_ROOT = Path(__file__).resolve().parents[1]

DEFAULT_INPUT = REPO_ROOT / "refer" / "reanim" / "PeaShooter.reanim"
DEFAULT_JSON = (
    REPO_ROOT
    / "pvzce-game"
    / "src"
    / "main"
    / "resources"
    / "assets"
    / "pvzce"
    / "animations"
    / "pea_shooter.json"
)
DEFAULT_TEXTURES = (
    REPO_ROOT
    / "pvzce-game"
    / "src"
    / "main"
    / "resources"
    / "assets"
    / "pvzce"
    / "textures"
    / "entities"
    / "pea_shooter"
)

FPS_FALLBACK = 12.0
PIXELS_PER_CELL = 0.01

# Animation masks.  The ranges are parsed from the mask tracks; the fallbacks
# are only used if a mask track is absent or has no visible frames.
ANIMATION_SPECS: Tuple[Dict[str, object], ...] = (
    {
        "name": "idle",
        "mask_track": "anim_full_idle",
        "fallback_range": (79, 103),
        "loop": True,
        "on_end": None,
        "transition": 0.1,
    },
    {
        "name": "shoot",
        "mask_track": "anim_shooting",
        "fallback_range": (54, 78),
        "loop": False,
        "on_end": "idle",
        "transition": 0.1,
        # The source reanim hides the base body tracks during its shooting
        # segment; the original game composes them from a separate base
        # animation.  Our single-clip controller keeps permanently-hidden
        # non-blink parts visible so the stem/leaves do not pop out.
        "force_visible": True,
        "force_visible_exclude_prefixes": ("blink",),
    },
)

# A small correction table is explicitly allowed by the task.  Only genuinely
# irregular names need to live here; normalized matching handles case and
# underscore differences automatically.
TRACK_NAME_ALIASES: Dict[str, str] = {
    # The source track uses the words in the opposite order from the canonical
    # bone/image name.
    "frontleaf_tip_left": "frontleaf_left_tip",
    "frontleaf_tip_right": "frontleaf_right_tip",
    # This render track carries the head image despite its control-style name.
    "anim_face": "head",
}

# Optional correction table for image references whose filename differs beyond
# case/underscore normalization.  Empty for the current PeaShooter assets.
IMAGE_REF_ALIASES: Dict[str, str] = {}

WARNINGS: List[str] = []


def warn(message: str) -> None:
    WARNINGS.append(message)
    print(f"WARNING: {message}", file=sys.stderr)


# ---------------------------------------------------------------------------
# Data model
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class FrameState:
    """One fully inherited frame state for a track."""

    x: float = 0.0
    y: float = 0.0
    kx: float = 0.0
    ky: float = 0.0
    sx: float = 1.0
    sy: float = 1.0
    f: float = 0.0
    image: Optional[str] = None

    @property
    def visible(self) -> bool:
        return self.f >= 0.0 and self.image is not None


@dataclass
class Track:
    name: str
    frames: List[FrameState]


@dataclass
class ImageAsset:
    ref: str
    source: Path
    width: int
    height: int


@dataclass
class RenderPiece:
    """A single (track, image) pair before duplicate names are merged."""

    track_name: str
    image_ref: str
    asset: ImageAsset
    output_name: str
    states: List[FrameState]
    visibility: List[bool]
    order: int


@dataclass
class Bone:
    name: str
    asset: ImageAsset
    states: List[FrameState]
    visibility: List[bool]
    order: int
    track_names: List[str] = field(default_factory=list)


@dataclass
class BBox:
    min_x: float
    min_y: float
    max_x: float
    max_y: float

    @property
    def center_x(self) -> float:
        return (self.min_x + self.max_x) / 2.0

    @property
    def base_y(self) -> float:
        return self.max_y

    @property
    def width(self) -> float:
        return self.max_x - self.min_x

    @property
    def height(self) -> float:
        return self.max_y - self.min_y


# ---------------------------------------------------------------------------
# CLI and parsing helpers
# ---------------------------------------------------------------------------


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Convert a PvZ1 reanim XML animation to PVZCE 2D-controller JSON "
            "and copy the referenced PNG textures."
        )
    )
    parser.add_argument(
        "--input",
        type=Path,
        default=DEFAULT_INPUT,
        help=f"input .reanim file (default: {DEFAULT_INPUT})",
    )
    parser.add_argument(
        "--json",
        dest="json_path",
        type=Path,
        default=DEFAULT_JSON,
        help=f"output controller JSON (default: {DEFAULT_JSON})",
    )
    parser.add_argument(
        "--textures",
        dest="textures_dir",
        type=Path,
        default=DEFAULT_TEXTURES,
        help=f"output PNG directory (default: {DEFAULT_TEXTURES})",
    )
    return parser.parse_args(argv)


def read_png_size(path: Path) -> Tuple[int, int]:
    """Read width/height from a PNG IHDR chunk without external libraries."""

    with path.open("rb") as handle:
        header = handle.read(24)
    if len(header) < 24 or header[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"not a PNG file: {path}")
    if header[12:16] != b"IHDR":
        raise ValueError(f"PNG does not start with an IHDR chunk: {path}")
    width, height = struct.unpack(">II", header[16:24])
    if width <= 0 or height <= 0:
        raise ValueError(f"invalid PNG dimensions for {path}: {width}x{height}")
    return int(width), int(height)


def load_reanim(path: Path) -> Tuple[float, List[Track]]:
    """Parse a root-less reanim XML file and resolve all frame inheritance."""

    if not path.is_file():
        raise SystemExit(f"Input reanim file does not exist: {path}")

    text = path.read_text(encoding="utf-8-sig")
    # Some exporters include an XML declaration even though the document has no
    # single root element.  Strip it before wrapping the contents.
    text = re.sub(r"^\s*<\?xml[^>]*\?>\s*", "", text, count=1)

    try:
        root = ET.fromstring(f"<root>{text}</root>")
    except ET.ParseError as exc:
        raise SystemExit(f"Failed to parse {path}: {exc}") from exc

    fps_text = root.findtext("fps")
    if fps_text is None:
        warn(f"{path.name} has no <fps>; using {FPS_FALLBACK}")
        fps = FPS_FALLBACK
    else:
        try:
            fps = float(fps_text)
        except ValueError:
            warn(f"Invalid <fps>{fps_text!r}; using {FPS_FALLBACK}")
            fps = FPS_FALLBACK
    if fps <= 0:
        warn(f"Non-positive fps {fps!r}; using {FPS_FALLBACK}")
        fps = FPS_FALLBACK

    tracks: List[Track] = []
    for track_element in root.findall("track"):
        name = track_element.findtext("name")
        if not name:
            warn("Skipping an unnamed <track>")
            continue

        state: Dict[str, object] = {
            "x": 0.0,
            "y": 0.0,
            "kx": 0.0,
            "ky": 0.0,
            "sx": 1.0,
            "sy": 1.0,
            "f": 0.0,
            "image": None,
        }
        frames: List[FrameState] = []

        for frame_element in track_element.findall("t"):
            raw_values: Dict[str, str] = {}

            # The original reanim format stores fields as child elements, but
            # accepting attributes as well makes the parser a little more
            # robust to hand-edited files.
            for key in ("x", "y", "kx", "ky", "sx", "sy", "f", "a", "i"):
                value: Optional[str] = frame_element.attrib.get(key)
                if value is None:
                    child = frame_element.find(key)
                    if child is not None:
                        value = child.text
                if value is not None and value.strip() != "":
                    raw_values[key] = value.strip()

            for key, value in raw_values.items():
                if key == "i":
                    state["image"] = value
                elif key in state:
                    try:
                        state[key] = float(value)
                    except ValueError:
                        warn(
                            f"{name}: ignoring non-numeric <{key}>{value!r} "
                            f"at frame {len(frames)}"
                        )

            frames.append(
                FrameState(
                    x=float(state["x"]),
                    y=float(state["y"]),
                    kx=float(state["kx"]),
                    ky=float(state["ky"]),
                    sx=float(state["sx"]),
                    sy=float(state["sy"]),
                    f=float(state["f"]),
                    image=state["image"] if isinstance(state["image"], str) else None,
                )
            )

        tracks.append(Track(name=name, frames=frames))

    if not tracks:
        raise SystemExit(f"No <track> elements found in {path}")
    return fps, tracks


def visible_ranges(track: Track) -> List[Tuple[int, int]]:
    """Return inclusive [start, end] ranges where the track is visible."""

    ranges: List[Tuple[int, int]] = []
    start: Optional[int] = None
    for index, state in enumerate(track.frames):
        if state.f >= 0.0:
            if start is None:
                start = index
        else:
            if start is not None:
                ranges.append((start, index - 1))
                start = None
    if start is not None:
        ranges.append((start, len(track.frames) - 1))
    return ranges


def find_mask_range(
    tracks: Sequence[Track], spec: Dict[str, object]
) -> Tuple[int, int]:
    """Resolve an animation range from its mask track, with fallback."""

    mask_name = str(spec["mask_track"])
    fallback = tuple(spec["fallback_range"])  # type: ignore[arg-type]
    track = next((candidate for candidate in tracks if candidate.name == mask_name), None)

    if track is None:
        warn(f"Mask track {mask_name!r} not found; using fallback range {fallback}")
        return int(fallback[0]), int(fallback[1])

    ranges = visible_ranges(track)
    if not ranges:
        warn(f"Mask track {mask_name!r} has no visible frames; using fallback {fallback}")
        return int(fallback[0]), int(fallback[1])
    if len(ranges) > 1:
        warn(
            f"Mask track {mask_name!r} has multiple visible ranges {ranges}; "
            f"using the first one"
        )
    return ranges[0]


# ---------------------------------------------------------------------------
# Name normalization and image mapping
# ---------------------------------------------------------------------------


def compact_name(value: str) -> str:
    return re.sub(r"[^a-z0-9]", "", value.lower())


def snake_case(value: str) -> str:
    """Convert CamelCase / mixed separators to lower snake_case."""

    value = re.sub(r"(?<=[a-z0-9])(?=[A-Z])", "_", value)
    value = re.sub(r"(?<=[A-Z])(?=[A-Z][a-z])", "_", value)
    value = re.sub(r"[^A-Za-z0-9]+", "_", value)
    value = re.sub(r"_+", "_", value).strip("_").lower()
    return value


def entity_compact(input_path: Path) -> str:
    return compact_name(input_path.stem)


def entity_snake(input_path: Path) -> str:
    return snake_case(input_path.stem)


def remove_entity_prefix(name: str, input_path: Path) -> str:
    """Strip the source entity prefix (for example PeaShooter_) from a name."""

    entity = entity_compact(input_path)
    if not entity:
        return name

    # The source uses both PEASHOOTER and PeaShooter spellings.
    prefixes = {entity, entity_snake(input_path)}
    for prefix in sorted(prefixes, key=len, reverse=True):
        if name == prefix:
            return ""
        if name.startswith(prefix + "_"):
            return name[len(prefix) + 1 :]
        if name.startswith(prefix):
            remainder = name[len(prefix) :].lstrip("_")
            if remainder:
                return remainder
    return name


def image_match_key(value: str, input_path: Path) -> str:
    """Create a compact key for matching image refs against PNG stems."""

    key = compact_name(value)
    if key.startswith("imagereanim"):
        key = key[len("imagereanim") :]
    entity = entity_compact(input_path)
    if entity and key.startswith(entity):
        key = key[len(entity) :]
    return key


def canonical_ref_name(ref: str, input_path: Path) -> str:
    """Canonical output name for an image reference (used for multi-image tracks)."""

    name = ref
    if name.startswith("IMAGE_REANIM_"):
        name = name[len("IMAGE_REANIM_") :]
    name = snake_case(name)
    name = remove_entity_prefix(name, input_path)
    # blink1 -> blink_1
    name = re.sub(r"([a-z])([0-9]+)$", r"\1_\2", name)
    # frontleaflefttip -> frontleaf_left_tip
    name = name.replace("lefttip", "left_tip").replace("righttip", "right_tip")
    name = re.sub(r"_+", "_", name).strip("_")
    return name


def canonical_track_name(track_name: str, input_path: Path) -> str:
    """Canonical output name for a single-image track."""

    name = snake_case(track_name)
    name = remove_entity_prefix(name, input_path)
    name = TRACK_NAME_ALIASES.get(name, name)

    # frontleaf_tip_left -> frontleaf_left_tip
    name = re.sub(r"_tip_(left|right)$", r"_\1_tip", name)

    # 2ndfarthest -> 2nd_farthest, 3rdfarthest -> 3rd_farthest
    name = re.sub(r"([0-9](?:st|nd|rd|th))(farthest|nearest)", r"\1_\2", name)

    # Split attached words such as lefttip/righttip if present.
    name = name.replace("lefttip", "left_tip").replace("righttip", "right_tip")
    name = re.sub(r"_+", "_", name).strip("_")
    return name


def discover_image_assets(
    input_path: Path, image_refs: Sequence[str]
) -> Dict[str, ImageAsset]:
    """Scan the input directory and map every image ref to an actual PNG."""

    image_dir = input_path.parent
    candidates = sorted(image_dir.glob(f"{input_path.stem}_*.png"))
    if not candidates:
        candidates = sorted(image_dir.glob("*.png"))
    if not candidates:
        raise SystemExit(f"No PNG files found next to {input_path}")

    by_key: Dict[str, List[Path]] = {}
    for candidate in candidates:
        key = image_match_key(candidate.stem, input_path)
        by_key.setdefault(key, []).append(candidate)

    assets: Dict[str, ImageAsset] = {}
    for ref in sorted(set(image_refs)):
        key = image_match_key(ref, input_path)
        paths = by_key.get(key)

        if not paths and ref in IMAGE_REF_ALIASES:
            alias = IMAGE_REF_ALIASES[ref]
            alias_key = image_match_key(Path(alias).stem, input_path)
            paths = by_key.get(alias_key)

        if not paths:
            close = difflib.get_close_matches(key, list(by_key.keys()), n=1, cutoff=0.55)
            if close:
                paths = by_key[close[0]]
                warn(
                    f"Image ref {ref!r} did not match exactly; "
                    f"using fuzzy match {paths[0].name!r}"
                )

        if not paths:
            raise SystemExit(
                f"Could not map image ref {ref!r} to any PNG in {image_dir} "
                f"(normalized key {key!r})"
            )

        if len(paths) > 1:
            warn(
                f"Multiple PNGs match image ref {ref!r}: "
                f"{[path.name for path in paths]}; using {paths[0].name!r}"
            )

        source = paths[0]
        width, height = read_png_size(source)
        assets[ref] = ImageAsset(
            ref=ref,
            source=source,
            width=width,
            height=height,
        )

    return assets


# ---------------------------------------------------------------------------
# Model construction
# ---------------------------------------------------------------------------


def build_render_pieces(
    tracks: Sequence[Track],
    assets: Dict[str, ImageAsset],
    input_path: Path,
    frame_count: int,
) -> List[RenderPiece]:
    """Create one piece per (render track, image) pair."""

    pieces: List[RenderPiece] = []
    order = 0

    for track in tracks:
        distinct_images: List[str] = []
        seen_images: set[str] = set()
        for state in track.frames:
            if state.image and state.image not in seen_images:
                seen_images.add(state.image)
                distinct_images.append(state.image)

        if not distinct_images:
            # Control tracks such as anim_stem have no image and are not bones.
            continue

        for image_ref in distinct_images:
            if image_ref not in assets:
                raise SystemExit(
                    f"Track {track.name!r} references unmapped image {image_ref!r}"
                )

            if len(distinct_images) == 1:
                output_name = canonical_track_name(track.name, input_path)
            else:
                output_name = canonical_ref_name(image_ref, input_path)

            if not output_name:
                output_name = canonical_ref_name(image_ref, input_path)

            states: List[FrameState] = []
            visibility: List[bool] = []

            for frame_index in range(frame_count):
                if frame_index < len(track.frames):
                    state = track.frames[frame_index]
                elif track.frames:
                    state = track.frames[-1]
                else:
                    state = FrameState()

                states.append(state)
                visibility.append(state.f >= 0.0 and state.image == image_ref)

            pieces.append(
                RenderPiece(
                    track_name=track.name,
                    image_ref=image_ref,
                    asset=assets[image_ref],
                    output_name=output_name,
                    states=states,
                    visibility=visibility,
                    order=order,
                )
            )
            order += 1

    if not pieces:
        raise SystemExit("No renderable tracks with image references were found")

    return pieces


def merge_pieces(pieces: Sequence[RenderPiece], frame_count: int) -> List[Bone]:
    """Merge pieces that canonicalize to the same bone name.

    Blink tracks are the motivating case: the source uses two separate tracks
    (``anim_blink`` and ``idle_shoot_blink``) that both switch between blink1
    and blink2.  Their frames do not overlap, so both can contribute to the
    same ``blink_1`` / ``blink_2`` bones.
    """

    bones_by_name: Dict[str, Bone] = {}
    order: List[str] = []

    for piece in pieces:
        existing = bones_by_name.get(piece.output_name)

        if existing is None:
            bones_by_name[piece.output_name] = Bone(
                name=piece.output_name,
                asset=piece.asset,
                states=list(piece.states),
                visibility=list(piece.visibility),
                order=piece.order,
                track_names=[piece.track_name],
            )
            order.append(piece.output_name)
            continue

        # Same canonical name but a different source image: make the new bone
        # unique instead of silently overwriting the first one.
        if existing.asset.source.resolve() != piece.asset.source.resolve():
            base_name = piece.output_name
            suffix = 2
            new_name = f"{base_name}_{suffix}"
            while new_name in bones_by_name:
                suffix += 1
                new_name = f"{base_name}_{suffix}"
            warn(
                f"Bone name collision {base_name!r} for different images; "
                f"renaming {piece.asset.source.name!r} piece to {new_name!r}"
            )
            bones_by_name[new_name] = Bone(
                name=new_name,
                asset=piece.asset,
                states=list(piece.states),
                visibility=list(piece.visibility),
                order=piece.order,
                track_names=[piece.track_name],
            )
            order.append(new_name)
            continue

        # Merge frame by frame.  A visible frame from any source wins over a
        # hidden frame; overlapping visible frames keep the first track's state.
        merged_states: List[FrameState] = []
        merged_visibility: List[bool] = []
        conflicts: List[int] = []

        for frame_index in range(frame_count):
            old_visible = existing.visibility[frame_index]
            new_visible = piece.visibility[frame_index]

            if new_visible and not old_visible:
                merged_states.append(piece.states[frame_index])
                merged_visibility.append(True)
            elif old_visible and new_visible:
                merged_states.append(existing.states[frame_index])
                merged_visibility.append(True)
                conflicts.append(frame_index)
            else:
                merged_states.append(existing.states[frame_index])
                merged_visibility.append(old_visible)

        if conflicts:
            warn(
                f"Bone {piece.output_name!r} has overlapping visible frames from "
                f"{existing.track_names[-1]!r} and {piece.track_name!r} at "
                f"frames {conflicts[:8]}{'...' if len(conflicts) > 8 else ''}; "
                f"kept the first state"
            )

        existing.states = merged_states
        existing.visibility = merged_visibility
        existing.track_names.append(piece.track_name)

    return [bones_by_name[name] for name in order]


# ---------------------------------------------------------------------------
# Coordinate conversion
# ---------------------------------------------------------------------------


def transform_corners(state: FrameState, width: int, height: int) -> List[Tuple[float, float]]:
    """Return the four image corners in reanim pixel coordinates."""

    kx = math.radians(state.kx)
    ky = math.radians(state.ky)
    x_axis = (math.cos(kx) * state.sx, math.sin(kx) * state.sx)
    y_axis = (-math.sin(ky) * state.sy, math.cos(ky) * state.sy)

    corners: List[Tuple[float, float]] = []
    for u in (0.0, float(width)):
        for v in (0.0, float(height)):
            corners.append(
                (
                    state.x + x_axis[0] * u + y_axis[0] * v,
                    state.y + x_axis[1] * u + y_axis[1] * v,
                )
            )
    return corners


def compute_joint_bbox(
    tracks: Sequence[Track], assets: Dict[str, ImageAsset]
) -> BBox:
    """Union of all visible image corners across every render track/frame."""

    min_x = math.inf
    min_y = math.inf
    max_x = -math.inf
    max_y = -math.inf

    for track in tracks:
        for state in track.frames:
            if state.f < 0.0 or not state.image:
                continue
            asset = assets.get(state.image)
            if asset is None:
                continue
            for x, y in transform_corners(state, asset.width, asset.height):
                min_x = min(min_x, x)
                min_y = min(min_y, y)
                max_x = max(max_x, x)
                max_y = max(max_y, y)

    if not all(math.isfinite(value) for value in (min_x, min_y, max_x, max_y)):
        raise SystemExit("Could not compute a joint bounding box from visible frames")

    return BBox(min_x=min_x, min_y=min_y, max_x=max_x, max_y=max_y)


def model_center_px(
    state: FrameState, asset: ImageAsset, bbox: BBox
) -> Tuple[float, float]:
    """Apply the controller's specified 2D model matrix to an image center."""

    top_left_x = state.x - bbox.center_x
    top_left_y = bbox.base_y - state.y

    kx = math.radians(state.kx)
    ky = math.radians(state.ky)

    # M = [[sx*cos(kx), sy*sin(ky)],
    #      [-sx*sin(kx), sy*cos(ky)]]
    m00 = state.sx * math.cos(kx)
    m01 = state.sy * math.sin(ky)
    m10 = -state.sx * math.sin(kx)
    m11 = state.sy * math.cos(ky)

    half_w = asset.width / 2.0
    half_h = asset.height / 2.0
    # Part local space is y-up and the image is centred on the part, so the
    # image's top-left is (-half_w, +half_h) in that space.  M maps local
    # y-up coordinates to the model, hence the -half_h below.
    return (
        top_left_x + m00 * half_w - m01 * half_h,
        top_left_y + m10 * half_w - m11 * half_h,
    )


def format_time(seconds: float) -> str:
    if abs(seconds) < 1e-12:
        return "0.0"
    text = f"{seconds:.6f}".rstrip("0").rstrip(".")
    if "." not in text:
        text += ".0"
    return text


def round_float(value: float, digits: int = 6) -> float:
    rounded = round(value, digits)
    # Avoid "-0.0" in the output JSON.
    if rounded == 0.0:
        return 0.0
    return rounded


# ---------------------------------------------------------------------------
# JSON assembly
# ---------------------------------------------------------------------------


def build_controller_json(
    bones: Sequence[Bone],
    tracks: Sequence[Track],
    assets: Dict[str, ImageAsset],
    fps: float,
) -> Tuple[Dict[str, object], BBox, Dict[str, Tuple[int, int]]]:
    bbox = compute_joint_bbox(tracks, assets)
    frame_count = max(len(track.frames) for track in tracks)

    model_bones: List[Dict[str, object]] = [
        {
            "name": "root",
            "parent": None,
            "pivot": [0.0, 0.0],
        }
    ]

    for bone in bones:
        part = {
            "texture": f"pvzce:textures/entities/pea_shooter/{bone.name}",
            "uv": [0, 0, bone.asset.width, bone.asset.height],
            "size": [
                round_float(bone.asset.width * PIXELS_PER_CELL),
                round_float(bone.asset.height * PIXELS_PER_CELL),
            ],
            "offset": [0.0, 0.0],
            "z": 0,
        }
        model_bones.append(
            {
                "name": bone.name,
                "parent": "root",
                "pivot": [0.0, 0.0],
                "parts": [part],
            }
        )

    animations: Dict[str, object] = {}
    animation_ranges: Dict[str, Tuple[int, int]] = {}

    for spec in ANIMATION_SPECS:
        animation_name = str(spec["name"])
        start_frame, end_frame = find_mask_range(tracks, spec)
        if end_frame >= frame_count:
            raise SystemExit(
                f"Animation {animation_name!r} range {start_frame}..{end_frame} "
                f"extends beyond the source frame count {frame_count}"
            )
        animation_ranges[animation_name] = (start_frame, end_frame)

        animation_bones: Dict[str, object] = {}
        for bone in bones:
            translation: Dict[str, List[float]] = {}
            rotation: Dict[str, List[float]] = {}
            scale: Dict[str, List[float]] = {}
            visible: Dict[str, bool] = {}

            for frame_index in range(start_frame, end_frame + 1):
                key = format_time((frame_index - start_frame) / fps)
                state = bone.states[frame_index]
                is_visible = bool(bone.visibility[frame_index])

                center_x, center_y = model_center_px(state, bone.asset, bbox)
                translation[key] = [
                    round_float(center_x * PIXELS_PER_CELL),
                    round_float(center_y * PIXELS_PER_CELL),
                ]
                rotation[key] = [
                    round_float(state.kx),
                    round_float(state.ky),
                    0.0,
                ]
                scale[key] = [
                    round_float(state.sx),
                    round_float(state.sy),
                ]
                if spec.get("force_visible"):
                    excluded = tuple(spec.get("force_visible_exclude_prefixes", ()))
                    source_visible = any(
                        bone.visibility[index]
                        for index in range(start_frame, end_frame + 1)
                    )
                    if not source_visible and not bone.name.startswith(excluded):
                        is_visible = True
                visible[key] = is_visible

            animation_bones[bone.name] = {
                "translation": translation,
                "rotation": rotation,
                "scale": scale,
                "visible": visible,
            }

        animation: Dict[str, object] = {
            "animation_length": round((end_frame - start_frame + 1) / fps, 10),
            "loop": bool(spec["loop"]),
        }
        if spec["on_end"] is not None:
            animation["on_end"] = str(spec["on_end"])
        if spec["transition"] is not None:
            animation["transition"] = float(spec["transition"])
        animation["bones"] = animation_bones
        animation["sound_effects"] = {}
        animation["particle_effects"] = {}
        animation["timeline"] = {}

        animations[animation_name] = animation

    controller = {
        "type": "controller",
        "model": {
            "bones": model_bones,
        },
        "animations": animations,
    }
    return controller, bbox, animation_ranges


# ---------------------------------------------------------------------------
# Output and validation
# ---------------------------------------------------------------------------


def atomic_write_json(path: Path, data: Dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    file_descriptor, temporary_name = tempfile.mkstemp(
        prefix=path.name + ".", suffix=".tmp", dir=str(path.parent)
    )
    try:
        with os.fdopen(file_descriptor, "w", encoding="utf-8") as handle:
            json.dump(data, handle, indent=2, ensure_ascii=False)
            handle.write("\n")
        os.replace(temporary_name, path)
        try:
            os.chmod(path, 0o644)
        except OSError:
            pass
    except BaseException:
        try:
            os.unlink(temporary_name)
        except OSError:
            pass
        raise


def read_old_generated_texture_names(json_path: Path) -> Optional[set[str]]:
    """Read texture names referenced by a previous output JSON, if present."""

    if not json_path.is_file():
        return None

    try:
        with json_path.open("r", encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, json.JSONDecodeError):
        warn(f"Could not read old JSON {json_path}; skipping stale-PNG cleanup")
        return None

    names: set[str] = set()
    model = data.get("model")
    if isinstance(model, dict):
        bones = model.get("bones")
        if isinstance(bones, list):
            for bone in bones:
                if not isinstance(bone, dict):
                    continue
                for part in bone.get("parts", []):
                    if not isinstance(part, dict):
                        continue
                    texture = part.get("texture")
                    if isinstance(texture, str):
                        names.add(texture.rsplit("/", 1)[-1])

    return names


def clean_generated_textures(
    textures_dir: Path, old_names: Optional[set[str]]
) -> List[str]:
    """Delete PNGs that a previous run's JSON identified as generated.

    ``copy_textures`` always overwrites the names generated by the current
    run.  This function additionally removes stale names from the previous
    JSON, while leaving unrelated files in the dedicated directory alone.
    """

    textures_dir.mkdir(parents=True, exist_ok=True)
    if old_names is None:
        return []

    removed: List[str] = []
    for png_path in sorted(textures_dir.glob("*.png")):
        if png_path.stem in old_names and (
            png_path.is_file() or png_path.is_symlink()
        ):
            png_path.unlink()
            removed.append(png_path.name)
    return removed


def copy_textures(bones: Sequence[Bone], textures_dir: Path) -> List[Path]:
    textures_dir.mkdir(parents=True, exist_ok=True)
    written: List[Path] = []
    for bone in bones:
        destination = textures_dir / f"{bone.name}.png"
        if destination.exists():
            destination.unlink()
        shutil.copyfile(bone.asset.source, destination)
        written.append(destination)
    return written


def validate_output(
    json_path: Path,
    textures_dir: Path,
    expected_keyframes: Dict[str, int],
) -> Dict[str, object]:
    with json_path.open("r", encoding="utf-8") as handle:
        data = json.load(handle)

    if data.get("type") != "controller":
        raise SystemExit("Validation failed: top-level type is not 'controller'")
    if not isinstance(data.get("model"), dict):
        raise SystemExit("Validation failed: missing model object")
    bones = data["model"].get("bones")
    if not isinstance(bones, list) or not bones:
        raise SystemExit("Validation failed: model.bones is empty")
    bone_names = [bone.get("name") for bone in bones if isinstance(bone, dict)]
    if "root" not in bone_names:
        raise SystemExit("Validation failed: root bone is missing")

    texture_count = 0
    for bone in bones:
        if not isinstance(bone, dict):
            continue
        for part in bone.get("parts", []):
            if not isinstance(part, dict):
                continue
            texture = part.get("texture")
            if not isinstance(texture, str):
                raise SystemExit("Validation failed: part has no texture string")
            texture_name = texture.rsplit("/", 1)[-1]
            texture_path = textures_dir / f"{texture_name}.png"
            if not texture_path.is_file():
                raise SystemExit(
                    f"Validation failed: texture file does not exist: {texture_path}"
                )
            texture_count += 1

    animations = data.get("animations")
    if not isinstance(animations, dict):
        raise SystemExit("Validation failed: missing animations object")

    animation_summary: Dict[str, object] = {}
    for animation_name in ("idle", "shoot"):
        animation = animations.get(animation_name)
        if not isinstance(animation, dict):
            raise SystemExit(f"Validation failed: missing animation {animation_name!r}")

        animation_bones = animation.get("bones")
        if not isinstance(animation_bones, dict) or not animation_bones:
            raise SystemExit(
                f"Validation failed: animation {animation_name!r} has no bones"
            )

        times: set[str] = set()
        for bone_animation in animation_bones.values():
            if not isinstance(bone_animation, dict):
                continue
            translation = bone_animation.get("translation")
            if not isinstance(translation, dict):
                raise SystemExit(
                    f"Validation failed: {animation_name} bone has no translation"
                )
            times.update(translation.keys())

        if len(times) != expected_keyframes.get(animation_name):
            raise SystemExit(
                f"Validation failed: animation {animation_name!r} has "
                f"{len(times)} distinct keyframe times, expected "
                f"{expected_keyframes.get(animation_name)}"
            )

        if animation_name == "idle":
            if animation.get("loop") is not True:
                raise SystemExit("Validation failed: idle.loop is not true")
        else:
            if animation.get("loop") is not False:
                raise SystemExit("Validation failed: shoot.loop is not false")
            if animation.get("on_end") != "idle":
                raise SystemExit("Validation failed: shoot.on_end is not 'idle'")
            if animation.get("transition") != 0.1:
                raise SystemExit("Validation failed: shoot.transition is not 0.1")

        animation_summary[animation_name] = {
            "keyframe_times": len(times),
            "animation_length": animation.get("animation_length"),
            "loop": animation.get("loop"),
        }

    return {
        "bone_count": len(bones),
        "texture_count": texture_count,
        "animations": animation_summary,
    }


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main(argv: Optional[Sequence[str]] = None) -> int:
    WARNINGS.clear()
    args = parse_args(argv)

    input_path = args.input.expanduser().resolve()
    json_path = args.json_path.expanduser().resolve()
    textures_dir = args.textures_dir.expanduser().resolve()

    print(f"Input:    {input_path}")
    print(f"JSON:     {json_path}")
    print(f"Textures: {textures_dir}")

    fps, tracks = load_reanim(input_path)
    frame_count = max(len(track.frames) for track in tracks)

    image_refs: List[str] = []
    seen_refs: set[str] = set()
    for track in tracks:
        for state in track.frames:
            if state.image and state.image not in seen_refs:
                seen_refs.add(state.image)
                image_refs.append(state.image)

    assets = discover_image_assets(input_path, image_refs)

    # Safety guard: never let the "clean old generated PNGs" step touch the
    # original reanim texture directory.
    for asset in assets.values():
        try:
            asset.source.resolve().relative_to(textures_dir)
        except ValueError:
            continue
        raise SystemExit(
            "Refusing to use a texture output directory that contains the "
            f"source PNG {asset.source}. Choose a different --textures path."
        )

    pieces = build_render_pieces(tracks, assets, input_path, frame_count)
    bones = merge_pieces(pieces, frame_count)

    controller, bbox, animation_ranges = build_controller_json(
        bones, tracks, assets, fps
    )

    print()
    print("Image mapping:")
    for bone in bones:
        print(
            f"  {bone.asset.ref} -> {bone.asset.source.name} "
            f"-> {bone.name}.png"
        )

    used_sources = {asset.source.resolve() for asset in assets.values()}
    input_pngs = sorted(input_path.parent.glob(f"{input_path.stem}_*.png"))
    unused_inputs = [
        png_path for png_path in input_pngs if png_path.resolve() not in used_sources
    ]
    if unused_inputs:
        print()
        print("Unused input PNGs (not referenced by any render track):")
        for png_path in unused_inputs:
            print(f"  {png_path.name}")

    print()
    print(
        "Joint visible-frame bbox (reanim px): "
        f"min=({bbox.min_x:.3f}, {bbox.min_y:.3f}) "
        f"max=({bbox.max_x:.3f}, {bbox.max_y:.3f})"
    )
    print(
        f"  centerX={bbox.center_x:.3f}, baseY={bbox.base_y:.3f}, "
        f"size={bbox.width:.3f}x{bbox.height:.3f}px "
        f"({bbox.width * PIXELS_PER_CELL:.6f}x"
        f"{bbox.height * PIXELS_PER_CELL:.6f} cells)"
    )

    print()
    print("Animations:")
    for animation_name, (start_frame, end_frame) in animation_ranges.items():
        animation = controller["animations"][animation_name]  # type: ignore[index]
        frame_total = end_frame - start_frame + 1
        print(
            f"  {animation_name}: frames {start_frame}..{end_frame} "
            f"({frame_total} keyframes), "
            f"length={animation['animation_length']}, "  # type: ignore[index]
            f"loop={animation['loop']}"  # type: ignore[index]
        )

    old_generated_names = read_old_generated_texture_names(json_path)
    if old_generated_names is None and any(textures_dir.glob("*.png")):
        warn(
            f"No previous JSON manifest at {json_path}; existing PNGs in "
            f"{textures_dir} cannot be identified as generated and were left "
            f"in place"
        )
    removed = clean_generated_textures(textures_dir, old_generated_names)
    if removed:
        print()
        print(f"Removed {len(removed)} stale generated PNG(s) from {textures_dir}")

    written_textures = copy_textures(bones, textures_dir)
    atomic_write_json(json_path, controller)

    expected_keyframes = {
        animation_name: end_frame - start_frame + 1
        for animation_name, (start_frame, end_frame) in animation_ranges.items()
    }
    validation = validate_output(json_path, textures_dir, expected_keyframes)

    print()
    print("Generated:")
    print(f"  {json_path}")
    for texture_path in written_textures:
        print(f"  {texture_path}")
    print(
        f"  Total: 1 JSON + {len(written_textures)} PNG "
        f"= {1 + len(written_textures)} file(s)"
    )

    print()
    print("Validation:")
    print(f"  model bones: {validation['bone_count']}")
    print(f"  texture references: {validation['texture_count']}")
    for animation_name, summary in validation["animations"].items():  # type: ignore[union-attr]
        print(
            f"  {animation_name}: keyframe_times={summary['keyframe_times']}, "
            f"animation_length={summary['animation_length']}, "
            f"loop={summary['loop']}"
        )

    if WARNINGS:
        print()
        print(f"Warnings ({len(WARNINGS)}):")
        for message in WARNINGS:
            print(f"  - {message}")
    else:
        print()
        print("Warnings: none")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
