#!/usr/bin/env python3
"""Convert the original PvZ particle emitters into PVZCE particle definitions.

The reference art in ``refer/im7/particles`` is 81 PNGs plus 112 XML emitter
scripts. They are the *original* format, so they cannot be loaded as they are;
this tool reads them and writes the project's own form:

    assets/pvzce/particles/<group>/<name>.json
    assets/pvzce/textures/particles/<group>/<name>*.png

The XML files concatenate several ``<Emitter>`` elements with no root element.
Each one becomes a definition, named ``<file>`` for the first and
``<file>_<emitter_name>`` for the rest, so ``PeaSplat.xml`` holding ``PeaSplat``
and ``PeaSplatBits`` produces ``pea_splat`` and ``pea_splat_bits``.

What is translated:

===================  ==========================================================
``ParticleDuration`` particle lifetime (frames at 60fps -> ``life`` in seconds)
``ParticleAlpha``    fade curve -> ``alpha_from`` / ``alpha_to``
``ParticleScale``    minimum size -> ``scale``
``ParticleRed``/...  tint -> ``color``
``Additive``         -> ``additive: true`` (the original blends these additively)
``LaunchSpeed``      -> ``speed`` in cells per second
``LaunchAngle``      -> ``angle`` / ``angle_spread`` in degrees, 0 = right, CCW
``Field/Acceleration`` -> ``gravity`` in cells per second squared
``Field/GroundConstraint`` -> ``bounce`` (particles stop at the ground line)
``ParticleSpinSpeed``  -> ``spin`` degrees per second
``ImageFrames``      -> ``frames`` (the ``<name>``, ``<name>_2`` ... PNG series)
``ImageRow``/``ImageCol`` -> the row-major slice of that series to use
===================  ==========================================================

Values are converted from the original's per-frame pixel units into world cells
and seconds: 60 frames per second, and 80 pixels per lawn cell (the low-resolution
lawn art is 800x600 for a 10x6 board), so a 330 px/frame launch speed becomes
247 cells/s.

**Except for the objects a zombie throws off** (its head, an arm, a cone, a bucket,
a flag - the emitters the original marks with a ``GroundConstraint``). Those are rigid
things that fly out, tumble and land, and the per-frame reading turns them into an arm
that vibrates in place at 50 turns per second and is gone before the eye finds it. They
are converted in the units the original actually authors them in - pixels per second,
degrees per second - which is what ``PROP_SPEED_DIVISOR`` and its neighbours undo. The
rest of the set keeps the per-frame reading on purpose: their launch speeds (2000+ for
the smoke of an explosion) are meant to be damped away by a ``Friction`` field this
converter drops, so converting them honestly would launch every cloud off the screen.

Anything the project has no runtime for is deliberately dropped rather than
approximated into something misleading: emitter volumes (``EmitterRadius``,
``EmitterBox*``), spawn scheduling (``SpawnRate``, ``SystemLoops``), collision
reflection and stretch. A definition only describes one particle's look and
motion; the spawning behaviour lives in the capability that emits it.

Run from the repository root:

    python3 tools/particles_to_pvzce.py --dry-run
    python3 tools/particles_to_pvzce.py
"""

from __future__ import annotations

import argparse
import json
import math
import re
import shutil
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_RESOURCES = REPO_ROOT / "pvzce-game" / "src" / "main" / "resources"
DEFAULT_NAMESPACE = "pvzce"
DEFAULT_INPUT_DIR = REPO_ROOT / "refer" / "im7" / "particles"
DEFAULT_REANIM_DIR = REPO_ROOT / "refer" / "anim"
# The rips split the loose game art between im7/particles and im7/images, so a few
# emitter sprites (dirt, water, melon bits) only exist in the latter.
DEFAULT_IMAGE_DIR = REPO_ROOT / "refer" / "im7" / "images"
# The controller art the entity converter produced. A few emitters draw a part of a
# plant or zombie that only exists there - the snow pea's crystals, a zombie's dirt -
# and copying it out of the generated tree beats leaving the effect undefined.
DEFAULT_ENTITY_DIR = DEFAULT_RESOURCES / "assets" / DEFAULT_NAMESPACE / "textures" / "entities"

FPS = 60.0
CELL_PIXELS = 80.0
# A particle's base footprint in world cells, before its own scale multiplier.
BASE_SCALE_CELLS = 0.16

# ---- thrown objects ------------------------------------------------------
#
# NOTE: the shipped definitions of this family are *hand-tuned* after conversion - the
# zombie head and arm were rescaled to 0.4/0.33 cells so they match the head on the model,
# the coin-sized drops carry their own render_scale, and the thrown hats carry the lifetime
# `SystemDuration` implies. Re-running the converter rewrites those files and loses the
# tuning, so regenerate a particle deliberately and diff it, never as a bulk refresh.
# The table at the top of this file reads the original's ``LaunchSpeed`` as pixels per
# frame, which is what the puffs and splats were converted with. That reading is wrong
# for the one family the original marks with a ``GroundConstraint`` - the objects a
# zombie throws off (its head, an arm, a cone, a bucket, a flag). Those are not puffs:
# they are rigid things that fly out, tumble once and land, and under the per-frame
# reading they get a speed of 0.04 cells/s and a spin of 50 turns *per second*, i.e. an
# arm that vibrates in place and is gone before the eye finds it (which is exactly how
# it looked in game). The original authors those fields in the units the rest of its
# engine uses - pixels per second, degrees per second - and its ``Acceleration`` field
# as a per-frame velocity step, which is what these three constants undo:
#
#   speed   = LaunchSpeed / CELL_PIXELS              (px/s -> cells/s)
#   gravity = Acceleration * FPS / CELL_PIXELS       (px per frame, per frame -> cells/s^2)
#   spin    = ParticleSpinSpeed                      (already degrees per second)
#
# A zombie's head comes out at 4.1 cells/s and 12.75 cells/s^2, which arcs it up about
# two thirds of a cell, brings it down inside a third of a second and leaves it lying
# there for the rest of its three-second life - the pop the original draws.
#
# Emitters with a ``Friction`` field deliberately keep the old reading: their launch
# speed (2000+ for the smoke of an explosion) is meant to be damped away almost
# immediately, and this converter drops friction, so converting those speeds honestly
# would launch every cloud off the screen.
PROP_SPEED_DIVISOR = CELL_PIXELS
PROP_GRAVITY_FACTOR = FPS / CELL_PIXELS
# The one thrown prop the original does not constrain to the ground: the newspaper is
# torn out of a zombie's hands and flutters off instead of landing. Listed by definition
# name because the structure cannot say it - everything else without a GroundConstraint
# is debris, a trail or a damped puff.
PROP_DEFINITIONS_WITHOUT_GROUND = ("zombie_newspaper",)

# Which group a definition belongs in, by name pattern. Order matters: the first
# match wins, so the more specific patterns come first.
GROUPS: Sequence[Tuple[str, str]] = (
    (r"^award|^trophy|^present|^pinata|^credits", "award"),
    (r"^zombie|^zomboss|^mowered|^dancer", "zombie"),
    (r"^doom|^explosion|^pow|^powie|^jack|^zamboni|^catapult|^boss", "explosion"),
    (r"^pea|^snow_?pea|^kernel|^cabbage|^butter|^melon|^winter|^star|^cob|^spike|^ice", "plant"),
    (r"^rain|^pool|^water|^splash", "water"),
    (r"^dust|^blast|^grave|^digger|^mower|^lantern|^mind|^imitater|^vase|^daisy", "effect"),
)

# Normalized (``snake_case`` with the underscores removed) tag -> file stem, for the
# handful of tags whose name does not normalize onto the PNG that ships with it.
IMAGE_ALIASES = {
    "zombiehead": "ZombieHead",
    "peasplats": "pea_splats",
    "peasplat": "pea_splats",
}


def snake_case(name: str) -> str:
    name = name.strip()
    name = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name)
    name = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", name)
    name = re.sub(r"[^A-Za-z0-9]+", "_", name)
    return re.sub(r"_+", "_", name).strip("_").lower()


def group_for(name: str) -> str:
    for pattern, group in GROUPS:
        if re.search(pattern, name):
            return group
    return "misc"


# ---------------------------------------------------------------------------
# XML value helpers. The original writes ranges as ``[a b]``, curves as
# ``value,time value,time``. Numbers may be written without a leading zero
# (``.4``), so a pattern that only accepts ``\d+(\.\d+)?`` silently reads ``.4``
# as ``4`` - every helper below goes through NUMBER.
# ---------------------------------------------------------------------------

NUMBER = re.compile(r"-?(?:\d+\.\d*|\.\d+|\d+)")
# How many distinct fades an alpha/scale curve may be reduced to.
CURVE_STEPS = 4
# A ``value,time`` pair's time is a percentage unless it is obviously an absolute tick.
PERCENT_CUTOFF = 2.0


def parse_numbers(text: Optional[str]) -> List[float]:
    if not text:
        return []
    # A range inside a curve (``[.4 .7],70``) picks a value per particle; a fade table
    # cannot express that, so it collapses onto the low end.
    flattened = re.sub(r"\[([^\]]*)\]", lambda match: (NUMBER.findall(match.group(1)) or ["0"])[0],
                       text)
    return [float(m.group(0)) for m in NUMBER.finditer(flattened)]


def first_number(text: Optional[str], fallback: float) -> float:
    values = parse_numbers(text)
    return values[0] if values else fallback


def range_of(text: Optional[str]) -> Optional[Tuple[float, float]]:
    """``[a b]`` (or a bare ``a``) as (min, max), or ``None`` when absent."""
    if not text:
        return None
    match = re.search(r"\[([^\]]*)\]", text)
    values = parse_numbers(match.group(1) if match else text)
    if not values:
        return None
    return (min(values), max(values))


def growth_curve(text: Optional[str]) -> Optional[List[List[float]]]:
    """The authored size trajectory as a table, or ``None`` for a constant size.

    A range is not a fade and must not be read as one. ``ParticleScale [.7 .9]``
    means "start at .7, end at .9", but the generic curve reader sees three numbers
    in ``.2 .5,7`` and pairs them as (value, time) keyframes, which flattens the
    ramp into "0.2 for the whole life" - the explosion's flash was drawn at its
    starting size and never grew.

    Everything before the first comma is the value trajectory; everything after it
    is the curve *type* and where it starts, which the engine's table does not
    carry (it samples linearly between its own points). ``value`` reaches ``value2``
    either by the end of the life, or - when the type names a fractional time, as in
    ``.5,60 0`` - by that point and then falls to nothing.
    """
    if not text:
        return None
    head, _, tail = text.partition(",")
    match = re.search(r"\[([^\]]*)\]", head)
    if match:
        numbers = parse_numbers(match.group(1))
    else:
        # A bare pair *has* to be the whole head; ``.4 EaseIn 10,10`` and ``1 25``
        # also open with numbers, but there the pair is a value and its *time*, and
        # there is no range to read.
        pair = re.match(r"^\s*(-?(?:\d+\.?\d*|\.\d+))\s+(-?(?:\d+\.?\d*|\.\d+))\s*$", head)
        numbers = [float(pair.group(1)), float(pair.group(2))] if pair else []
    if not numbers:
        return None
    start = numbers[0]
    end = numbers[1] if len(numbers) > 1 else start
    if abs(end - start) < 1e-6:
        return None
    tail_numbers = parse_numbers(tail)
    if tail_numbers:
        # A trailing curve type with a *time* in front of it (``,7``, ``,50 1``)
        # describes a curve shape this table cannot carry, and guessing which of the
        # two numbers is the time - ``,60 0`` versus ``,7`` - produces tables that
        # are plainly wrong (a flash that grows to 60%, or one that holds for 7% and
        # then jumps to its final size). An authored ramp is only read when the
        # string has no time in it at all; the rest stay the constant the base
        # ``scale`` already expresses.
        return None
    if max(start, end) > 1.0:
        # Not a fraction, so not a size: ``10 15`` and ``1600`` are the original
        # writing pixel sizes for effects this project draws from its own sprites.
        return None
    if min(start, end) < 0:
        return None
    return [[0.0, start], [1.0, end]]


def parse_curve(text: Optional[str]) -> List[Tuple[float, float]]:
    """A fade curve as ``(time, value)`` pairs with the time normalised to 0..1.

    The original writes curves in three shapes and, for a short one, the reading is
    genuinely ambiguous from the token positions alone:

    * ``1,80 0,100`` - alternating value, time(%), value, time(%).
    * ``0 1,.4 1,99.5 0`` - a leading time, then repeated value,time(%) pairs.
    * ``.9,70 0`` - value, time(%), then a final value at the end.
    * ``.2 .5,7`` - the same shape with a bare time in seconds.

    Each possible pairing is built, then filtered, then scored; nothing is guessed
    mid-flight. A candidate survives only if its times rise strictly from 0 to 100%
    once percentages are divided out, and the survivor with the fewest values that
    had to be reinterpreted wins. That last test is what tells ``1,80 0,100`` (alphas
    1 and 0, times 80% and 100%) apart from reading the same tokens as alphas 80 and
    100, which reaches 100% but needs both values rescaled.
    """
    values = parse_numbers(text)
    if not values:
        return []
    candidates: List[List[Tuple[float, float]]] = []

    def add(raw: List[Tuple[float, float]]) -> None:
        """``raw`` is (time, value) with the time still in the file's own units."""
        if not raw:
            return
        # Times are percentages of the lifetime; a curve may spell them as fractions
        # instead, which the magnitude tells apart. Normalising first is what lets the
        # keyframes below be added at the right scale.
        if max(time for time, _ in raw) > PERCENT_CUTOFF:
            raw = [(time / 100.0, value) for time, value in raw]
        # A curve may start late or stop early; outside its keyframes the nearest value
        # holds. Both ends are closed off so every reading spans the whole lifetime.
        if raw[0][0] > 1e-6:
            raw = [(0.0, raw[0][1])] + raw
        if raw[-1][0] < 1.0 - 1e-6:
            raw = raw + [(1.0, raw[-1][1])]
        times = [time for time, _ in raw]
        if any(b <= a + 1e-9 for a, b in zip(times, times[1:])):
            return
        if abs(times[0]) > 1e-6 or abs(times[-1] - 1.0) > 1e-6:
            return
        # A fade curve's values are fractions in 0..1. Every value outside that range
        # had to be something else, so each one costs a point - and the times of a
        # mis-paired reading land there (an alpha of 80 is not a time either). The
        # reading with the fewest costs wins; more keyframes break a tie, so the
        # literal reading is preferred over a shorter improvisation.
        penalty = sum(1 for _, value in raw if not -0.001 <= value <= 1.001)
        if penalty:
            # No reading of these tokens looks like a fade curve; better to report
            # nothing and let the definition use its default than to invent one.
            return
        candidates.append(sorted(raw, key=lambda pair: pair[0]))
        penalties.append((penalty, len(raw)))

    penalties: List[Tuple[int, int]] = []

    def pairs_from(rest: Sequence[float]) -> List[Tuple[float, float]]:
        """Consecutive ``value, time`` pairs out of the token list."""
        return [(rest[i + 1], rest[i]) for i in range(0, len(rest) - 1, 2)]

    # value,time value,time ...
    add(pairs_from(values))
    # time value,time value,time ... (a leading time)
    add(pairs_from(values[1:]))
    if len(values) % 2 == 1:
        # A value at 0%, then value,time pairs.
        add([(0.0, values[0])] + pairs_from(values[1:]))
        # value,time pairs, then a final value at 100%.
        add(pairs_from(values[:-1]) + [(100.0, values[-1])])
    if not candidates:
        return []
    best = min(range(len(candidates)), key=lambda index: (penalties[index][0], -penalties[index][1]))
    return candidates[best]


def curve_endpoints(text: Optional[str]) -> Optional[Tuple[float, float]]:
    """First and last value of a ``value,time`` curve."""
    curve = parse_curve(text)
    if not curve:
        return None
    return curve[0][1], curve[-1][1]


def sampled_curve(text: Optional[str]) -> Optional[List[List[float]]]:
    """A curve reduced to at most {@link CURVE_STEPS} evenly spaced points.

    Returns ``None`` for a straight fade, which the engine's ``alpha_from`` /
    ``alpha_to`` pair already expresses, so only genuinely curved fades pay for a
    table.
    """
    curve = parse_curve(text)
    if len(curve) < 3:
        return None
    deduped: List[Tuple[float, float]] = []
    for time, value in curve:
        if deduped and abs(deduped[-1][0] - time) < 1e-6:
            deduped[-1] = (time, value)
        else:
            deduped.append((time, value))
    if len(deduped) < 3 or deduped[0][0] > 0.001 or abs(deduped[-1][0] - 1.0) > 0.001:
        return None
    points = [[round(step / (CURVE_STEPS - 1), 4),
               round(sample_at(deduped, step / (CURVE_STEPS - 1)), 4)]
              for step in range(CURVE_STEPS)]
    first, last = points[0][1], points[-1][1]
    straight = True
    for index, point in enumerate(points):
        expected = first + (last - first) * (index / (CURVE_STEPS - 1))
        if abs(point[1] - expected) > 0.02:
            straight = False
            break
    return None if straight else points


def sample_at(curve: Sequence[Tuple[float, float]], time: float) -> float:
    previous_time, previous_value = curve[0]
    for point_time, point_value in curve:
        if point_time >= time:
            if point_time <= previous_time:
                return point_value
            ratio = (time - previous_time) / (point_time - previous_time)
            return previous_value + (point_value - previous_value) * ratio
        previous_time, previous_value = point_time, point_value
    return curve[-1][1]


def number_out(value: float, digits: int = 6) -> float:
    """Rounds for the JSON text without destroying small magnitudes.

    ``round(x, 6)`` is wrong for the physical constants here: a gravity of
    ``-5.9e-05`` becomes ``-0.0``. Six significant digits stay readable and exact
    enough to round-trip through a float.
    """
    if value == 0 or abs(value) < 1e-9:
        return 0.0
    magnitude = math.floor(math.log10(abs(value)))
    return round(value, max(0, digits - 1 - magnitude))


class Emitter:
    """One ``<Emitter>`` element, read into the handful of fields we translate."""

    def __init__(self, element: ET.Element, name: str):
        self.name = name
        self.raw = element

    def text(self, tag: str) -> Optional[str]:
        found = self.raw.find(tag)
        return found.text if found is not None else None

    def field_y(self, field_type: str) -> Optional[str]:
        for field in self.raw.findall("Field"):
            kind = field.find("FieldType")
            if kind is not None and (kind.text or "").strip().lower() == field_type.lower():
                y = field.find("Y")
                return y.text if y is not None else None
        return None

    def additive(self) -> bool:
        text = (self.text("Additive") or "").strip()
        return text not in ("", "0")

    def image_stem(self, known: Dict[str, str]) -> Optional[str]:
        """The real file stem behind this emitter's ``<Image>`` tag.

        The tag is the *original's* name for the sprite (``IMAGE_PEA_SPLATS``,
        ``IMAGE_REANIM_ZOMBIE_CONE3``), which frequently differs from the file name in
        the rip only by case and underscores, so the match is on a normalized key.
        ``known`` maps that key to the stem to use; every PNG the converter can see -
        particle sprites, reanim parts, loose images - is in it.
        """
        tag = (self.text("Image") or "").strip()
        if not tag:
            return None
        raw = tag[len("IMAGE_"):] if tag.startswith("IMAGE_") else tag
        raw = raw[len("REANIM_"):] if raw.startswith("REANIM_") else raw
        key = name_key(raw)
        return known.get(key) or IMAGE_ALIASES.get(key) or raw

    def frame_count(self) -> int:
        """``<ImageFrames>`` - how many frames in the sheet's grid (1 when absent)."""
        return max(1, int(first_number(self.text("ImageFrames"), 1.0)))

    def sheet_rows(self) -> int:
        """``<ImageRows>``, or the row the emitter picks plus one when it is unstated."""
        rows = int(first_number(self.text("ImageRows"), 0.0))
        if rows > 0:
            return rows
        return int(first_number(self.text("ImageRow"), 0.0)) + 1

    def sheet_row(self) -> int:
        return int(first_number(self.text("ImageRow"), 0.0))

    def sheet_col(self) -> Optional[int]:
        """``<ImageCol>`` - which column of a one-row grid this emitter draws."""
        text = self.text("ImageCol")
        return None if text is None else int(first_number(text, 0.0))

    def to_definition(self, image_stem: str, frames: int = 1) -> Dict[str, object]:
        """One emitter as a definition, with the engine's own grouping.

        The JSON mirrors the record it decodes into: everything about how the particle
        looks goes under ``look``, everything about how it moves under ``motion``. The
        texture itself is written by the caller, which is the only place that knows the
        group the definition landed in.
        """
        look: Dict[str, object] = {}
        motion: Dict[str, object] = {}

        # --- motion -------------------------------------------------------
        # An object the emitter throws onto the lawn, as opposed to a puff of smoke: it
        # is the family whose units the per-frame reading gets wrong in a way the player
        # can see. See PROP_SPEED_DIVISOR.
        thrown = (self.field_y("GroundConstraint") is not None
                  or self.name in PROP_DEFINITIONS_WITHOUT_GROUND)
        speed = first_number(self.text("LaunchSpeed"), 0.0)
        if speed > 0:
            speed_divisor = PROP_SPEED_DIVISOR if thrown else FPS * CELL_PIXELS
            motion["speed"] = number_out(speed / speed_divisor)
        angle = range_of(self.text("LaunchAngle"))
        if angle is not None:
            motion["angle"] = round((angle[0] + angle[1]) / 2.0, 2)
            spread = abs(angle[1] - angle[0])
            if spread > 0.01:
                motion["angle_spread"] = round(spread, 2)
        acceleration_y = self.field_y("Acceleration")
        if acceleration_y:
            # The original's Y acceleration is positive downwards (screen coordinates) and
            # so is the project's `gravity` field, so the sign carries over untouched. It
            # used to be negated here, which made gravity push particles *up*: un noticeable
            # while the converted values were around 1e-5, and the reason a thrown cone
            # sailed over the lawn instead of landing on it once they were not.
            gravity = first_number(acceleration_y, 0.0)
            motion["gravity"] = number_out(
                gravity * PROP_GRAVITY_FACTOR if thrown
                else gravity / FPS / FPS / CELL_PIXELS)
        if self.field_y("GroundConstraint") is not None:
            motion["bounce"] = True
        spin = range_of(self.text("ParticleSpinSpeed"))
        if spin is not None and (abs(spin[0]) > 0.01 or abs(spin[1]) > 0.01):
            spin_factor = 1.0 if thrown else FPS
            look["spin"] = round((spin[0] + spin[1]) / 2.0 * spin_factor, 2)
            # Almost every emitter names a *range* of spin speeds, and the midpoint of a
            # symmetric one is zero: `[-720 720]` - the head a lawn mower throws - came out
            # as a head that did not rotate at all, so it read as sliding across the grass
            # rather than tumbling. The half-width goes to the engine, which rolls a speed
            # per particle (ParticleLook#spinSpread).
            spin_spread = (max(spin) - min(spin)) / 2.0 * spin_factor
            if spin_spread > 0.01:
                look["spin_spread"] = round(spin_spread, 2)
        if (self.text("RandomLaunchSpin") or "0").strip() not in ("", "0"):
            look["random_spin"] = True

        # --- look ---------------------------------------------------------
        alpha_text = self.text("ParticleAlpha") or self.text("SystemAlpha")
        alpha = curve_endpoints(alpha_text)
        if alpha is not None:
            if abs(alpha[0] - 1.0) > 0.001:
                look["alpha_from"] = round(alpha[0], 4)
            if abs(alpha[1]) > 0.001:
                look["alpha_to"] = round(alpha[1], 4)
        curve = sampled_curve(alpha_text)
        if curve is not None:
            look["alpha_curve"] = curve
        scale_text = self.text("ParticleScale")
        scale = range_of(scale_text)
        if scale is not None and abs(scale[0] - 1.0) > 0.001:
            look["scale"] = round(scale[0], 4)
        scale_curve = sampled_curve(scale_text)
        if scale_curve is None:
            # Nothing curve-shaped came out, but a bracketed range is still a ramp:
            # see growth_curve. The base ``scale`` is the low end, so the table holds
            # the ratio and the two agree at t=0.
            scale_curve = growth_curve(scale_text)
        if scale_curve is not None:
            look["scale_curve"] = scale_curve
        # ``ParticleRed`` is a fraction when it is small (``.7``) and a 0..255 byte when it
        # is large (``1`` meaning "full", as in the original's Powie). Dividing everything
        # by 255 turned the second spelling into near-black - a hit that read as a black
        # smudge instead of a yellow burst.
        tint = [first_number(self.text(f"Particle{c}"), 255.0) for c in ("Red", "Green", "Blue")]
        if any(abs(channel - 255.0) > 0.5 for channel in tint):
            scale = 255.0 if any(channel > 1.5 for channel in tint) else 1.0
            look["color"] = [round(min(1.0, channel / scale), 4) for channel in tint]

        # --- lifetime and frames -----------------------------------------
        # `ParticleDuration` is each particle's life; when the emitter does not give one, the
        # particles live as long as the system that spawns them (`SystemDuration`), and only
        # then does the 30-frame default apply. Reading the default straight away cut every
        # emitter that spells only `SystemDuration` down to half a second - the cone and the
        # bucket fly off the zombie's head with `SystemDuration 50` (0.83s) and were gone
        # before they landed, which is why a knocked-off hat read as a hat that vanished.
        duration_text = self.text("ParticleDuration") or self.text("SystemDuration")
        look["life"] = round(max(1.0, first_number(duration_text, 30.0)) / FPS, 4)
        look["frames_per_second"] = FPS
        look["loop"] = True
        # A one-shot emitter (a splat that appears and stays) should not loop its
        # frames; only a sheet that is meant to cycle does.
        if frames <= 1:
            look["loop"] = True

        definition: Dict[str, object] = {"id": f"{DEFAULT_NAMESPACE}:{self.name}", "look": look}
        if motion:
            definition["motion"] = motion
        if self.additive():
            definition["additive"] = True
        count = range_of(self.text("SpawnMinActive")) or range_of(self.text("SpawnMaxLaunched"))
        if count is not None:
            average = (count[0] + count[1]) / 2.0
            definition["count"] = max(1, round(average))
            spread = abs(count[1] - count[0]) / 2.0
            if spread >= 0.5:
                definition["count_spread"] = max(1, round(spread))
        return definition


def read_emitters(path: Path) -> List[ET.Element]:
    """The file's ``<Emitter>`` elements, tolerating a document with no root."""
    text = path.read_text(encoding="latin-1")
    try:
        root = ET.fromstring(text)
        return [root] if root.tag == "Emitter" else list(root.findall("Emitter"))
    except ET.ParseError:
        # Concatenated emitters: wrap them so the parser sees a single document.
        # A declaration or comment before the first one is dropped, and the wrapper
        # is thrown away again, so only the original elements are returned.
        stripped = re.sub(r"<\?xml[^>]*\?>", "", text)
        try:
            return list(ET.fromstring(f"<Emitters>{stripped}</Emitters>").findall("Emitter"))
        except ET.ParseError as error:
            print(f"  !! {path.name}: {error}", file=sys.stderr)
            return []


def discover_pngs(input_dir: Path) -> Dict[str, List[str]]:
    """File names per image family, keyed by lower-case base name.

    ``<name>.png`` is frame 0 and ``<name>_N.png`` is frame N, ordered by N so a
    two-digit frame cannot sort before a one-digit one.
    """
    families: Dict[str, List[str]] = {}
    for png in sorted(input_dir.glob("*.png")):
        base = re.sub(r"_\d+$", "", png.stem).lower()
        families.setdefault(base, []).append(png.stem)

    def frame_index(stem: str) -> int:
        match = re.search(r"_(\d+)$", stem)
        return int(match.group(1)) if match else 0

    for stems in families.values():
        stems.sort(key=frame_index)
    return families


def slice_sheet(source: Path, frames: int, rows: int, row: int, destination: Path,
                names: Sequence[str]) -> None:
    """Cuts a sprite sheet into one PNG per frame of the requested row.

    The original's ``<ImageFrames>`` counts the whole grid and ``<ImageRow>`` picks a
    strip out of it (``dirtsmall`` is 8 frames x 2 rows). The renderer takes one
    texture per frame, so the grid is cut apart once here rather than taught to
    sample sub-rectangles at run time.
    """
    from PIL import Image

    with Image.open(source) as sheet:
        width, height = sheet.size
        columns = max(1, frames // max(1, rows))
        step_x = width // columns
        step_y = height // max(1, rows)
        if step_x <= 0 or step_y <= 0 or columns * step_x != width or rows * step_y != height:
            # Not an even grid: leave the sheet whole and use it as a single frame.
            shutil.copy2(source, destination / f"{names[0]}.png")
            return
        for index, name in enumerate(names):
            column = index % columns
            frame = sheet.crop((column * step_x, row * step_y,
                                (column + 1) * step_x, (row + 1) * step_y))
            frame.save(destination / f"{name}.png")


def slice_row(source: Path, column: int, destination: Path, name: str) -> None:
    """Cuts one column out of a single-row sheet (``<ImageCol>`` with no frames)."""
    from PIL import Image

    with Image.open(source) as sheet:
        width, height = sheet.size
        if width % (column + 1) != 0:
            shutil.copy2(source, destination / f"{name}.png")
            return
        step = width // (column + 1)
        sheet.crop((column * step, 0, (column + 1) * step, height)).save(
            destination / f"{name}.png")


def asset_name(name: str) -> str:
    """A file name that is also a valid resource path.

    Identifier paths are lower-case only, and the renderer builds its file path from
    the texture id, so the copied PNG has to be lower-cased together with the id it is
    referenced by. The original's names are camel case (``ZombieHead``,
    ``AwardRays2``), which is why this is not a no-op.
    """
    return name.lower()


def name_key(name: str) -> str:
    """Lookup key for a PNG name: case- and underscore-insensitive.

    The original's ``<Image>`` tags and the rip's file names disagree on both
    (``IMAGE_REANIM_ZOMBIE_CONE3`` -> ``Zombie_cone3.png``), so every name lookup in
    this tool goes through one normalisation.
    """
    return name.lower().replace("_", "")


def recursive_index(directory: Path) -> Dict[str, str]:
    """Normalized stem -> relative path, for a whole tree.

    <p>The generated entity art is nested by kind and category, and one stem can appear
    under several entities (every zombie has a ``head.png``). The first hit in sorted
    order wins, which is stable and points at the generic zombie whenever a name is
    ambiguous.
    """
    index: Dict[str, str] = {}
    if not directory.is_dir():
        return index
    for png in sorted(directory.rglob("*.png")):
        index.setdefault(name_key(png.stem), str(png.relative_to(directory)))
    return index


def case_insensitive_index(directory: Path) -> Dict[str, str]:
    """Normalized PNG stem -> the real stem, for one directory."""
    index: Dict[str, str] = {}
    if not directory.is_dir():
        return index
    for png in directory.glob("*.png"):
        index.setdefault(name_key(png.stem), png.stem)
    return index


def frame_index(stem: str) -> int:
    match = re.search(r"_(\d+)$", stem)
    return int(match.group(1)) if match else 0


def frame_family(stem: str, index: Dict[str, str]) -> List[str]:
    """``<stem>.png`` plus its ``<stem>_N.png`` siblings, ordered by N."""
    real = index.get(name_key(stem))
    if real is None:
        return []
    base = name_key(re.sub(r"_\d+$", "", real))
    return sorted([name for name in index.values()
                   if name_key(re.sub(r"_\d+$", "", name)) == base],
                  key=frame_index)


def convert(input_dir: Path, reanim_dir: Path, image_dir: Path, entity_dir: Path,
            resources: Path, dry_run: bool, verbose: bool) -> int:
    """Writes one definition per emitter, plus the PNGs it references."""
    particle_families = discover_pngs(input_dir)
    particle_index = case_insensitive_index(input_dir)
    reanim_index = case_insensitive_index(reanim_dir)
    loose_index = case_insensitive_index(image_dir)
    entity_index = recursive_index(entity_dir)
    # Every name an <Image> tag could mean, normalized. Particle sprites win over
    # reanim parts, which win over the loose image directory.
    known: Dict[str, str] = {}
    # Particle sprites win, then reanim parts, then loose images, then the generated
    # entity art: the more specific the source, the more likely it is the intended one.
    for index in (entity_index, loose_index, reanim_index, particle_index):
        known.update(index)
    out_textures = resources / "assets" / DEFAULT_NAMESPACE / "textures" / "particles"
    out_defs = resources / "data" / DEFAULT_NAMESPACE / "particles"

    written = 0
    copied: Dict[Tuple[str, str], bool] = {}
    skipped: List[str] = []
    for xml in sorted(input_dir.glob("*.xml")):
        file_stem = xml.stem
        elements = read_emitters(xml)
        if not elements:
            skipped.append(xml.name)
            continue
        primary = snake_case(file_stem)
        for index, element in enumerate(elements):
            emitter_name = (element.findtext("Name") or "").strip()
            name = primary if index == 0 else f"{primary}_{snake_case(emitter_name) or index}"
            emitter = Emitter(element, name)
            # Rebound per emitter: a file's second emitter must never inherit the
            # first one's sheet split.
            sheet = None
            column = None
            stem = emitter.image_stem(known)
            if not stem:
                skipped.append(f"{xml.name}#{index} (no Image)")
                continue
            group = group_for(name)
            # A particle sprite is copied under its own name. An emitter that draws a
            # reanim part shares that PNG with the entity art, so it goes into a
            # ``reanim`` subdirectory under a snake_cased name - a bare ``head.png``
            # would collide with every other zombie part.
            sheet_frames = emitter.frame_count()
            if name_key(stem) in particle_index:
                files = frame_family(stem, particle_index)
                subdir = group
                source = input_dir
                names = files
            elif (name_key(stem) in reanim_index or name_key(stem) in loose_index
                  or name_key(stem) in entity_index):
                from_reanim = name_key(stem) in reanim_index
                from_entity = not from_reanim and name_key(stem) not in loose_index
                if from_reanim:
                    files = [reanim_index[name_key(stem)]]
                    source = reanim_dir
                    subdir = f"{group}/reanim"
                elif from_entity:
                    files = [entity_index[name_key(stem)]]
                    source = entity_dir
                    subdir = f"{group}/parts"
                else:
                    files = [loose_index[name_key(stem)]]
                    source = image_dir
                    subdir = group
                # Keep the part numbering: Zombie_cone3 -> zombie_cone_3.
                names = [re.sub(r"([a-z])(\d+)$", r"\1_\2", snake_case(stem))]
            else:
                skipped.append(f"{xml.name}#{index} (no PNG for {stem})")
                continue

            # A sheet is one PNG holding several frames, which the renderer cannot use
            # directly - it takes one texture per frame - so the grid is cut apart here.
            # This applies wherever the file came from: the dirt and water sprites live
            # in the loose image directory, not the particle one.
            if sheet_frames > 1 and len(files) == 1:
                rows = emitter.sheet_rows()
                columns = max(1, sheet_frames // max(1, rows))
                names = [f"{files[0]}_{frame + 1}" for frame in range(columns)]
                sheet = (source / f"{files[0]}.png", columns, rows, emitter.sheet_row())
            elif emitter.sheet_col() is not None and len(files) == 1:
                names = [f"{files[0]}_{emitter.sheet_col() + 1}"]
                column = (source / f"{files[0]}.png", int(emitter.sheet_col()))

            definition = emitter.to_definition(stem, len(names))
            definition["id"] = f"{DEFAULT_NAMESPACE}:{name}"
            look = definition["look"]
            images = [f"{DEFAULT_NAMESPACE}:textures/particles/{subdir}/{asset_name(file)}"
                      for file in names]
            look["texture"] = images[0]
            if len(images) > 1:
                look["frames"] = images

            target = out_defs / group / f"{name}.json"
            if not dry_run:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(json.dumps(definition, indent=2, ensure_ascii=False) + "\n",
                                  encoding="utf-8")
            destination = out_textures / subdir
            if not dry_run:
                destination.mkdir(parents=True, exist_ok=True)
            for file, out_name in zip(files, [asset_name(n) for n in names]):
                key = (subdir, out_name)
                if copied.get(key):
                    continue
                copied[key] = True
                if dry_run:
                    continue
                if sheet is not None:
                    # Slicing writes the row's whole frame set at once, so every emitter
                    # sharing the sheet is served by the first call. The unsliced sheet is
                    # left in place: it is unreferenced, and deleting it would break the
                    # next group that slices the same file.
                    slice_sheet(sheet[0], sheet[1], sheet[2], sheet[3], destination,
                                [asset_name(n) for n in names])
                    break
                if column is not None:
                    slice_row(column[0], column[1], destination, out_name)
                    continue
                shutil.copy2(source / (file if file.endswith(".png") else f"{file}.png"),
                             destination / f"{out_name}.png")
            written += 1
            if verbose:
                print(f"  {name} -> {group}/{name}.json  frames={len(names)}")

    print(f"Wrote {written} particle definition(s), "
          f"{len(copied)} texture file(s)")
    if skipped:
        print(f"Skipped {len(skipped)}:")
        for entry in skipped:
            print(f"  - {entry}")
    if dry_run:
        print("dry run: nothing was written")
    return 0


def copy_textures(input_dir: Path, out_dir: Path, stems: Sequence[str], dry_run: bool) -> int:
    if dry_run:
        return len(stems)
    out_dir.mkdir(parents=True, exist_ok=True)
    copied = 0
    for stem in stems:
        source = input_dir / f"{stem}.png"
        if source.exists():
            shutil.copy2(source, out_dir / source.name)
            copied += 1
    return copied


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--input-dir", type=Path, default=DEFAULT_INPUT_DIR)
    parser.add_argument("--reanim-dir", type=Path, default=DEFAULT_REANIM_DIR,
                        help="source of the reanim part PNGs some emitters draw")
    parser.add_argument("--image-dir", type=Path, default=DEFAULT_IMAGE_DIR,
                        help="source of the loose sprites (dirt, water) some emitters draw")
    parser.add_argument("--entity-dir", type=Path, default=DEFAULT_ENTITY_DIR,
                        help="source of the generated entity art some emitters draw a part of")
    parser.add_argument("--resources", type=Path, default=DEFAULT_RESOURCES)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args(argv)
    return convert(args.input_dir, args.reanim_dir, args.image_dir, args.entity_dir,
                   args.resources, args.dry_run, args.verbose)


if __name__ == "__main__":
    raise SystemExit(main())
