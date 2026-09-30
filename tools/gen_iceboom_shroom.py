#!/usr/bin/env python3
"""Build the ice-boom shroom's art from the AI-generated source drawings.

This is the first plant in this project whose art is **not** converted from an original
reanim: the drawings come from a text-to-image model and live in `tools/art/`. Everything
between "a 1024px PNG on a magenta field" and "a controller model the renderer can play"
happens here, so the conversion is reproducible instead of a one-off manual edit.

What it does, in order:

1. **Keys the background out** by flood-filling the flat backdrop from the border, so
   interior highlights that happen to be magenta-ish survive;
2. **Splits the drawing into two parts** at the neck: `head.png` (cap + ice crystals +
   icicles) and `body.png` (stalk, eyes, arms, feet). The split is what makes the idle bob
   and the shot recoil possible - one sprite can only be moved as a whole;
3. **Paints the stalk back in under the cap.** The cap is drawn *in front of* the stalk, so
   the pixels where the two overlap are cap pixels and nothing else. Lifting the cap even a
   little would open a see-through hole, so the body layer gets a procedurally filled neck
   in the stalk's own colour;
4. **Downsamples to the board's scale** and writes the two part textures plus the projectile
   texture, the card icon and the shatter particle;
5. **Writes the animation JSON** (model + `idle` + `shoot`) by hand-authoring keyframes.

Run from the repository root:

    python3 tools/gen_iceboom_shroom.py

Optional:

    python3 tools/gen_iceboom_shroom.py --preview .smoke/iceboom/preview

`--preview` writes a contact sheet (the two parts, the reassembled plant on lawn green at
3x, and the card icon) so the split can be eyeballed without launching the game.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage

REPO_ROOT = Path(__file__).resolve().parents[1]
ART = REPO_ROOT / "tools" / "art"
RESOURCES = REPO_ROOT / "pvzce-game" / "src" / "main" / "resources"
PLANT_NS = "pvzce"

ENTITY = "iceboom_shroom"
GROUP = "plant/attacker"

SOURCES = {
    "body": ART / "iceboom_shroom_body.png",
    "bolt": ART / "iceboom_bolt.png",
    "shatter": ART / "iceboom_shatter.png",
    "card": ART / "iceboom_shroom_card.png",
}

# --- Scale ---------------------------------------------------------------------------
# The board projects 100px per cell vertically, and every converted plant is authored
# inside a 0.76-cell box (its parts are 100px drawings placed by pivot). This drawing is
# taller than it is wide - the cap's crystal cluster is most of its height - so matching
# the box by *width* would stand a 1.22-cell mushroom in a 1-cell lawn square and poke it
# well into the row above. It is therefore fitted by height: 0.68 cells wide, 0.97 tall,
# which reads the same size as a Snow Pea with its crystals up.
SPRITE_WIDTH = 57

# Where the cap ends and the stalk begins, as a fraction of the drawing's height measured
# from the top. Measured, not guessed: scanning the source's centre column puts the cap's
# own navy underside outline at row 279 of 452 (0.617), and the visibly pale stalk from
# about 0.84 down. The split sits just below that outline so the cap's rim - including
# every icicle hanging off it - travels with the head.
NECK_FRACTION = 0.64
# How far below the split the stalk is painted back in. The cap hides the stalk between the
# split and about 0.84 of the height, so this is the lens that has to be filled or a lifted
# cap opens a hole onto the lawn.
NECK_PAINT_ROWS = 70
# A gap in a row wider than this is background between two objects (the cap and an arm),
# not the hole the cap left in the stalk.
NECK_MAX_GAP = 70

# Part canvas = the whole cropped drawing, so both layers keep one coordinate system and
# their offsets differ only by the neck line.
# The model's box, in cells. The board projects 100px per cell, so one sprite pixel is
# 1/100 cell: the art is authored at board scale and `render_scale` in the content file
# stays at 1.0.
MODEL_WIDTH = 0.57          # cells; the box is the *canvas*, and the crown of the cap
                            # overhangs it (the drawing is 1.4 canvases tall)
FIRST_IDLE_FRAME = 0.0

TRANSPARENT = (0, 0, 0, 0)

# --- Animation -----------------------------------------------------------------------
# The original's plants are not moved by an equation: their reanims carry ~25 keys per clip
# over half a dozen bones, each with its own amplitude and phase, and their leaves lag the
# head. This plant has three moving parts (cap, stalk, eyes) and one prop (the muzzle
# flash), so the clips below are hand-timed rather than sampled from a sine:
#
#   * `idle`  - a two-second breath with the cap leading and the stalk answering a beat
#               later, plus two blinks (the eyes are their own layer; see `eye_layer`).
#   * `shoot` - anticipation, release, overshoot, settle. The release is two frames wide
#               because that is when the bolt appears.
#   * `blink` - its own short clip, so the interval between blinks is the server's business
#               (the same reason the original ships blink bones rather than a blink cycle).
IDLE_LENGTH = 2.2
SHOOT_LENGTH = 0.55
BLINK_LENGTH = 0.18

# How far each part travels in the breath, in cells. The cap leads; the stalk answers with
# a quarter of the distance a fifth of a second later, which is what reads as weight.
IDLE_CAP_RISE = 0.022
IDLE_STALK_RISE = 0.006
IDLE_CAP_SQUASH = 0.020
IDLE_STALK_SQUASH = 0.012

# The shot: a short pull back (anticipation), a hard forward shove, an overshoot past rest,
# then a settle. `SHOOT_RATE` plays the clip faster than authored, the same trick the
# converted plants use (the Snow Pea's `shoot` is 2.0).
SHOOT_RATE = 1.5
SHOOT_PULL_BACK = 0.020
SHOOT_PUSH = -0.070
SHOOT_RECOVER = 0.022


def log(message: str) -> None:
    print(f"[iceboom] {message}")


def load_keyed(path: Path) -> Image.Image:
    """Load a drawing and drop its flat backdrop by flooding in from the border."""
    if not path.exists():
        raise SystemExit(f"missing source art: {path}")
    image = Image.open(path).convert("RGBA")
    array = np.array(image).astype(np.int16)
    red, green, blue = array[..., 0], array[..., 1], array[..., 2]
    # A wide magenta test: the generator's backdrop is a saturated #FF00FF, but the
    # antialiased ring around the character blends toward it and must go too.
    magenta = (red > 120) & (blue > 120) & ((red - green) > 35) & ((blue - green) > 35)
    labels, _ = ndimage.label(magenta)
    border = {int(v) for v in np.concatenate([labels[0, :], labels[-1, :], labels[:, 0], labels[:, -1]])}
    border.discard(0)
    background = np.isin(labels, list(border))
    foreground = ~background
    canvas = np.array(image)
    canvas[..., 3] = np.where(foreground, canvas[..., 3], 0)
    # Anything still fully transparent but tinted magenta is an antialiased fringe: pull it
    # toward the drawing's own colour so downsampling cannot smear pink into the sprite.
    fringe = (canvas[..., 3] > 0) & (red > 120) & (blue > 120) & ((red - green) > 35)
    canvas[..., 0] = np.where(fringe, canvas[..., 1], canvas[..., 0])
    canvas[..., 2] = np.where(fringe, canvas[..., 1], canvas[..., 2])
    return Image.fromarray(canvas)


def crop_to_content(image: Image.Image) -> Image.Image:
    box = image.getchannel("A").getbbox()
    if box is None:
        raise SystemExit("source art is empty after keying")
    return image.crop(box)


def cap_mask(image: Image.Image, top_limit: int) -> np.ndarray:
    """The cap's own pixels: the pale cyan body of the mushroom hat.

    Colour alone cannot answer this - the stalk is pale cyan too, and so is the cap's
    shadowed underside - so the mask is anchored by position: the cap *body* is the large
    pale-cyan blob in the upper half, grown into its own outline and shadow below it, and
    everything the cap hides goes to the body layer as a painted-in neck instead.
    """
    array = np.array(image).astype(np.int16)
    red, green, blue, alpha = array[..., 0], array[..., 1], array[..., 2], array[..., 3]
    opaque = alpha > 40
    pale_cyan = opaque & (green > 225) & (red > 120) & (blue > 240)
    # The cap is the biggest pale-cyan component above the split; the stalk's highlights
    # are pale too, so the search is limited to the upper half of the drawing.
    upper = np.zeros_like(pale_cyan)
    upper[:top_limit] = pale_cyan[:top_limit]
    labels, count = ndimage.label(upper)
    if count == 0:
        raise SystemExit("could not find the cap in the source art")
    sizes = ndimage.sum(upper, labels, range(1, count + 1))
    cap = labels == (int(np.argmax(sizes)) + 1)
    # Grow it over the cap's own darker shading and its navy outline, so the head layer is
    # a complete cap rather than a cap with transparent shadow.
    grown = ndimage.binary_dilation(cap, iterations=26, mask=opaque)
    return ndimage.binary_fill_holes(grown) & opaque


def build_parts(image: Image.Image, neck_row: int) -> tuple[Image.Image, Image.Image]:
    """Split one drawing into (head, body) layers on a shared canvas."""
    array = np.array(image).copy()
    alpha = array[..., 3] > 40
    cap = cap_mask(image, neck_row)
    rows = np.arange(array.shape[0])[:, None]
    # The head owns everything from the split up, and the cap travels with it: the cap's
    # rim dips below the split, and an icicle left behind on the body layer would hang in
    # the air the moment the head moves.
    head_mask = (alpha & (rows < neck_row)) | cap
    body_mask = alpha & ~head_mask
    # The cap hides the stalk between the split and the row where the stalk becomes visible
    # again. Those pixels belong to both layers in the drawing, so they are taken away from
    # the head and given to the body as a painted-in neck - leaving them on both is what
    # puts a band of ice crystals across the stalk the moment the head bobs up.
    for y in range(max(0, neck_row), min(array.shape[0], neck_row + NECK_PAINT_ROWS)):
        columns = np.nonzero(alpha[y])[0]
        if len(columns) < 2:
            continue
        # A hole only if this row is broken in two: the cap is one run, the arms/feet
        # another, and the outlines in between belong to neither.
        gaps = np.nonzero(np.diff(columns) > 1)[0]
        if len(gaps) == 0:
            continue
        for gap in gaps:
            left, right = int(columns[gap]) + 1, int(columns[gap + 1])
            if right - left > NECK_MAX_GAP:
                # A wide gap is background between two objects, not a hole under the cap.
                continue
            body_mask[y, left:right] |= True
            head_mask[y, left:right] = False

    head = np.zeros_like(array)
    head[head_mask] = array[head_mask]
    body = np.zeros_like(array)
    body[body_mask] = array[body_mask]
    body = paint_neck(body, body_mask, neck_row)
    return Image.fromarray(head), Image.fromarray(body)


def paint_neck(body: np.ndarray, body_mask: np.ndarray, neck_row: int) -> np.ndarray:
    """Give the filled-in neck the stalk's own colour, shaded top-to-bottom."""
    filled = body_mask & (body[..., 3] == 0)
    if not filled.any():
        return body
    rows = np.nonzero(filled.any(axis=1))[0]
    top, bottom = int(rows.min()), int(rows.max())
    span = max(1, bottom - top)
    # Sample the stalk's visible colour from the drawing itself: a row of the patch that
    # was already opaque before the fill.
    sample = None
    for y in range(bottom, min(body.shape[0], bottom + 40)):
        visible = body[y][body[y][:, 3] > 40]
        if len(visible) >= 4:
            sample = visible
            break
    base = (np.median(sample[:, :3], axis=0).astype(np.int16) if sample is not None
            else np.array([176, 227, 253], dtype=np.int16))
    for y in np.nonzero(filled.any(axis=1))[0]:
        # Slightly darker toward the bottom, matching the stalk's own shading direction.
        shade = 1.0 - 0.10 * ((y - top) / span)
        colour = np.clip(base * shade, 0, 255).astype(np.uint8)
        body[y, filled[y], 0] = colour[0]
        body[y, filled[y], 1] = colour[1]
        body[y, filled[y], 2] = colour[2]
        body[y, filled[y], 3] = 255
    return body


def resize(image: Image.Image, width: int) -> Image.Image:
    scale = width / image.width
    return image.resize((width, max(1, round(image.height * scale))), Image.LANCZOS)


def silhouette(image: Image.Image) -> Image.Image:
    """A magenta-only cut for the projectile and the particle (they have no keying step)."""
    array = np.array(image).astype(np.int16)
    red, green, blue = array[..., 0], array[..., 1], array[..., 2]
    magenta = (red > 120) & (blue > 120) & ((red - green) > 35) & ((blue - green) > 35)
    canvas = np.array(image)
    canvas[..., 3] = np.where(magenta, 0, 255)
    return Image.fromarray(canvas)


def keyframes(pairs: dict[float, float]) -> dict[str, list[float]]:
    return {f"{t:g}": [v] if not isinstance(v, (list, tuple)) else list(v) for t, v in pairs.items()}


def vec_track(pairs: dict[float, tuple[float, float]]) -> dict[str, list[float]]:
    return {f"{t:g}": [round(x, 4) for x in v] for t, v in pairs.items()}


def scalar_track(pairs: dict[float, tuple[float, float]]) -> dict[str, list[float]]:
    return {f"{t:g}": [round(x, 4) for x in v] for t, v in pairs.items()}


def track(pairs) -> dict:
    """A keyframe track: ``[(t, value), ...]`` with the value a scalar or a list."""
    out = {}
    for t, value in pairs:
        key = f"{round(t, 4):g}"
        out[key] = list(value) if isinstance(value, (list, tuple)) else [round(value, 5)]
    return out


def vec(pairs) -> dict:
    """A two-component track; every translation here is ``(x, y)``."""
    return {f"{round(t, 4):g}": [round(x, 5), round(y, 5)] for t, (x, y) in pairs}


def uniform(pairs) -> dict:
    """A scale track: every key lists **both** components.

    Not `[v]`. The engine reads a scale as `[sx, sy]`, and a one-component track silently
    leaves `sy` at 1 - so a squash written on one axis does nothing at all. This is the shape
    the loader's own `vec2`/`vec3` helpers produce, and the converted reanims match it.
    """
    return {f"{round(t, 4):g}": [round(v, 5), round(v, 5)] for t, v in pairs}


def visible(value: bool = True) -> dict:
    """A bone's visibility, as a JSON **boolean**.

    Not `1`: the loader reads this channel with Gson's `getAsBoolean()`, which *throws* on a
    number rather than coercing it, and a malformed key is dropped on the floor - so a bone
    whose `visible` says `1` ends up with an empty track, and the plant is drawn every frame
    with both bones hidden. Only the shadow shows. That is how this plant first shipped.
    """
    return {"0": value}


def animation_json(parts: dict, head_rest: float) -> dict:
    """The controller model plus its three clips, in the shape the loader reads.

    Hand-timed, the way the converted plants are: the cap leads the breath and the stalk
    answers it, the eyes blink on their own clip, and the shot has a pull-back, a release,
    an overshoot and a settle. A sine on one bone reads as a diagram of breathing; uneven
    spacing on three is what reads as a plant.
    """
    # Every bone states its whole rest transform, even when every number is the default.
    # `parseRestPose` fills in `[0, 0, 0]` and `[1, 1]` for a bone that declares none, but a
    # bone that leaves one channel out of a *clip* gets that channel sampled from the declared
    # rest pose - so an undersized track is a crash waiting for the right frame. The converted
    # reanims write all three; so does this.
    rest = {"translation": [0.0, 0.0], "rotation": [0.0, 0.0, 0.0], "scale": [1.0, 1.0]}

    def bone(name: str, part: dict) -> dict:
        return {"name": name, "parent": None, "pivot": part["pivot"], "transform": dict(rest),
                "parts": [part["part"]]}

    model = {
        "size": [MODEL_WIDTH, parts["body"]["model_height"]],
        "bones": [
            bone("body", parts["body"]),
            # The muzzle flash sits *behind* the cap: the bolt leaves the plant's mouth line,
            # and a glow drawn over the cap would hide the one part of the shot the player is
            # meant to see.
            bone("flash", parts["flash"]),
            bone("head", parts["head"]),
            bone("eyes", parts["eyes"]),
        ],
    }
    eye_rest = parts["eyes"]["pose_y"]
    flash_rest = parts["flash"]["pose_y"]

    # ---- idle: one breath, two blinks ------------------------------------------------
    # The breath is 2.2s. The cap's rise holds at the top for a moment and comes down a
    # little faster than it went up, which is what keeps it from reading as a sine.
    cap = vec([(0.0, (0.0, head_rest)), (0.45, (0.0, head_rest + IDLE_CAP_RISE * 0.75)),
               (0.75, (0.0, head_rest + IDLE_CAP_RISE)), (1.05, (0.0, head_rest + IDLE_CAP_RISE * 0.9)),
               (1.45, (0.0, head_rest - IDLE_CAP_RISE * 0.35)), (1.75, (0.0, head_rest - IDLE_CAP_RISE * 0.2)),
               (2.2, (0.0, head_rest))])
    cap_scale = uniform([(0.0, 1.0), (0.75, 1.0 - IDLE_CAP_SQUASH), (1.45, 1.0 + IDLE_CAP_SQUASH),
                        (2.2, 1.0)])
    # The stalk answers a fifth of a second late and with a quarter of the travel: the
    # lag is the whole point (a plant that moves as one piece is a sticker).
    stalk = vec([(0.0, (0.0, 0.0)), (0.2, (0.0, -IDLE_STALK_RISE * 0.3)),
                 (0.95, (0.0, IDLE_STALK_RISE * 0.75)), (1.25, (0.0, IDLE_STALK_RISE)),
                 (1.65, (0.0, -IDLE_STALK_RISE * 0.5)), (2.2, (0.0, 0.0))])
    stalk_scale = uniform([(0.0, 1.0), (0.95, 1.0 + IDLE_STALK_SQUASH), (1.65, 1.0 - IDLE_STALK_SQUASH),
                          (2.2, 1.0)])
    # The blinks are *in* the idle clip, not a clip of their own. The client plays the state
    # the server publishes, and the server has no reason to publish "blink" - which is why the
    # original's blinking is bone visibility inside the idle reanim and not a separate file.
    # Two blinks, unevenly spaced; a shut is two frames wide and the open takes three, because
    # an eye that opens as fast as it closes reads as a twitch.
    def blink_at(t: float) -> list:
        return [(t, (0.0, eye_rest)), (t + 0.06, (0.0, eye_rest - 0.030)),
                (t + 0.10, (0.0, eye_rest - 0.026)), (t + 0.18, (0.0, eye_rest))]

    eyes_idle = vec(sorted(blink_at(0.62) + blink_at(1.74) + [(0.0, (0.0, eye_rest)),
                                                             (IDLE_LENGTH, (0.0, eye_rest))],
                           key=lambda kv: kv[0]))
    idle = {
        "animation_length": IDLE_LENGTH,
        "loop": True,
        "transition": 0.12,
        "bones": {
            "body": {"translation": stalk, "scale": stalk_scale, "visible": visible()},
            "head": {"translation": cap, "scale": cap_scale, "visible": visible()},
            "eyes": {"translation": eyes_idle, "visible": visible()},
            "flash": {"translation": vec([(0.0, (0.0, flash_rest))]),
                      "scale": uniform([(0.0, 0.0)]), "visible": visible()},
        },
        "sound_effects": {},
        "particle_effects": {},
        "timeline": {},
    }
    # ---- blink: the eyes alone, on their own clip -------------------------------------
    # The lens slides down under the upper lid (see `eye_layer`), so the blink is a
    # translation and not a swap: shut in two frames, open over three.
    blink = {
        "animation_length": BLINK_LENGTH,
        "loop": False,
        "on_end": "idle",
        "transition": 0.04,
        "bones": {
            "body": {"translation": vec([(0.0, (0.0, 0.0))]), "visible": visible()},
            "head": {"translation": vec([(0.0, (0.0, head_rest))]), "visible": visible()},
            "eyes": {"translation": vec([(0.0, (0.0, eye_rest)), (0.06, (0.0, eye_rest - 0.028)),
                                         (0.1, (0.0, eye_rest - 0.022)), (0.18, (0.0, eye_rest))]),
                     "visible": visible()},
            "flash": {"translation": vec([(0.0, (0.0, flash_rest))]),
                      "scale": uniform([(0.0, 0.0)]), "visible": visible()},
        },
        "sound_effects": {},
        "particle_effects": {},
        "timeline": {},
    }

    # ---- shoot: pull back, release, overshoot, settle ---------------------------------
    # The release lands at 0.10s (three frames at 30fps) and the flash is at its brightest
    # exactly there. The cap's overshoot at 0.22 goes *past* the rest pose, which is what
    # gives the shot a snap; without it the plant returns to rest as if nothing happened.
    shoot_cap = vec([(0.0, (0.0, head_rest)),
                     (0.06, (0.0, head_rest + SHOOT_PULL_BACK)),
                     (0.10, (0.0, head_rest + SHOOT_PUSH)),
                     (0.22, (0.0, head_rest + SHOOT_RECOVER)),
                     (0.38, (0.0, head_rest - SHOOT_RECOVER * 0.35)),
                     (SHOOT_LENGTH, (0.0, head_rest))])
    shoot_cap_scale = uniform([(0.0, 1.0), (0.06, 1.0 + 0.03), (0.10, 1.0 - 0.05),
                              (0.22, 1.0 + 0.02), (SHOOT_LENGTH, 1.0)])
    shoot_stalk = vec([(0.0, (0.0, 0.0)), (0.06, (0.0, 0.006)), (0.10, (0.0, -0.012)),
                       (0.22, (0.0, 0.008)), (0.38, (0.0, -0.003)), (SHOOT_LENGTH, (0.0, 0.0))])
    shoot_stalk_scale = uniform([(0.0, 1.0), (0.10, 1.0 + 0.045), (0.22, 1.0 - 0.02),
                                (SHOOT_LENGTH, 1.0)])
    # The flash: nothing, a bright core at the release, gone a fifth of a second later.
    shoot_flash = uniform([(0.0, 0.0), (0.10, 1.15), (0.16, 0.85), (0.26, 0.0), (SHOOT_LENGTH, 0.0)])
    shoot = {
        "animation_length": SHOOT_LENGTH,
        "loop": False,
        "on_end": "idle",
        "rate": SHOOT_RATE,
        "transition": 0.06,
        "bones": {
            "body": {"translation": shoot_stalk, "scale": shoot_stalk_scale, "visible": visible()},
            "head": {"translation": shoot_cap, "scale": shoot_cap_scale, "visible": visible()},
            "flash": {"translation": vec([(0.0, (0.0, flash_rest))]),
                      "scale": shoot_flash,
                      "visible": visible()},
            "eyes": {"translation": vec([(0.0, (0.0, eye_rest))]), "visible": visible()},
        },
        "sound_effects": {},
        "particle_effects": {},
        "timeline": {},
    }
    return {"type": "controller", "model": model,
            "animations": {"idle": idle, "blink": blink, "shoot": shoot}}


def write_png(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path)
    log(f"wrote {path.relative_to(REPO_ROOT)} ({image.width}x{image.height})")


def eye_layer(plant: Image.Image, width: int) -> tuple[Image.Image, np.ndarray]:
    """The two eyes cut out of the body drawing, on the shared canvas.

    Blinking needs the eyes to be a thing that can move on their own, and this drawing's eyes
    happen to be built so moving them *is* blinking: each one is a dark pupil sitting in a
    lighter blue lens with a pale highlight above it (the sclera). Sliding the whole eye down
    under the upper lid therefore reads as a blink - the pupil disappears first, the lens
    follows - without a second drawing, an eyelid, or a redraw of the art.

    Found by colour rather than by hand: the eyes are the two large dark blobs in the lower
    half, and the mouth (also dark, but a third of the size) is left on the body layer.
    """
    array = np.array(plant).astype(np.int16)
    red, green, blue, alpha = array[..., 0], array[..., 1], array[..., 2], array[..., 3]
    dark = (alpha > 40) & (red < 90) & (green < 110) & (blue < 170)

    # The face, found by colour and then bounded: everything here is one connected web of navy
    # line art (the pupils, the mouth, and the outline that ties them to the body), so "label
    # the dark pixels" finds one blob the size of the plant. The eyes are separated by looking
    # only at the band they sit in - the widest part of the stalk, above the feet - and by
    # keeping the two large blobs left and right of the mouth.
    face = dark[int(plant.height * 0.58): int(plant.height * 0.92)]
    labels, count = ndimage.label(face)
    if count == 0:
        raise SystemExit("no face found in the source drawing")
    found = []
    for index in range(1, count + 1):
        ys, xs = np.nonzero(labels == index)
        found.append((len(xs), int(xs.min()), int(xs.max()), int(ys.min()), int(ys.max())))
    # An eye is a tall-ish blob of a few hundred pixels; the mouth is about a third of that and
    # sits between them, and anything larger is the outline the band clipped through.
    eyes = [b for b in found if 120 <= b[0] <= 3000 and (b[2] - b[1]) > 20]
    eyes.sort(key=lambda b: -b[0])
    eyes = eyes[:2]
    if len(eyes) < 2:
        raise SystemExit("expected two eyes in the face band, found " + str(sorted(found)[-4:]))
    mask = np.zeros_like(dark)
    for _, x0, x1, y0, y1 in eyes:
        band = np.zeros_like(dark)
        band[int(plant.height * 0.58) + y0: int(plant.height * 0.58) + y1 + 1, x0: x1 + 1] = True
        mask |= band & dark
    # Grow by two pixels: the ring of antialiased outline around each eye belongs to it, and
    # leaving it behind would leave two dark rings on the body when the eyes slide down.
    mask = ndimage.binary_dilation(mask, iterations=2)
    log("eye blobs: " + ", ".join(f"{b[0]}px at x{b[1]}..{b[2]}" for b in eyes))

    canvas = np.zeros_like(array)
    canvas[mask] = array[mask]
    layer = Image.fromarray(canvas.astype(np.uint8))
    scale = width / plant.width
    out = layer.resize((width, max(1, round(plant.height * scale))), Image.LANCZOS)
    # The same hole has to come out of the body, or the eyes would simply be drawn twice: the
    # body keeps everything except what this layer took.
    return out, mask


def muzzle_flash(bolt: Image.Image, canvas: tuple[int, int],
                 center: tuple[float, float], cells: float = 0.30) -> tuple[Image.Image, float]:
    """The bolt's own art, blown up, for the frame the shot leaves on.

    Reusing the projectile instead of drawing a new sprite: the flash is the bolt, so the
    thing at the muzzle and the thing that flies away are made of the same ice.

    Placed where the bolt *is*: `PlantShots.MUZZLE_OFFSET_X` is 0.3 cells from the plant's
    centre, on the plant's own ground line - not at the sprite's mouth, which is where a
    hand-placed glow ends up looking like a balloon tied to the cap. Returns the layer and
    the model-space y its bone has to hold.
    """
    target = max(1, round(cells * 100))
    flash = bolt.resize((target, max(1, round(bolt.height * target / bolt.width))), Image.LANCZOS)
    out = Image.new("RGBA", canvas, TRANSPARENT)
    px = 1.0 / 100.0
    canvas_w, canvas_h = canvas
    c = (round(canvas_w / 2.0 + center[0] / px), round(canvas_h / 2.0 + center[1] / px))
    out.alpha_composite(flash, (c[0] - flash.width // 2, c[1] - flash.height // 2))
    return out, center[1]



def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preview", type=Path, default=None,
                        help="write a contact sheet here (not part of the build)")
    args = parser.parse_args(argv)

    plant = crop_to_content(load_keyed(SOURCES["body"]))
    neck_row = int(round(plant.height * NECK_FRACTION))
    head_full, body_full = build_parts(plant, neck_row)
    log(f"source {plant.width}x{plant.height}, neck at row {neck_row}")

    # The projectile's own art is authored first: the muzzle flash is this same drawing blown
    # up, so the thing at the muzzle and the thing that flies away are made of the same ice.
    bolt_small = resize(crop_to_content(silhouette(load_keyed(SOURCES["bolt"]))), 40)

    width = SPRITE_WIDTH
    height = max(1, round(plant.height * width / plant.width))
    head_small = resize(head_full, width)
    body_small = resize(body_full, width)

    # One part canvas for both layers: they share a coordinate system, so the two halves line
    # up at rest by construction and the model needs no per-part bookkeeping.
    canvas_w, canvas_h = width, height
    head_canvas = Image.new("RGBA", (canvas_w, canvas_h), TRANSPARENT)
    body_canvas = Image.new("RGBA", (canvas_w, canvas_h), TRANSPARENT)
    head_canvas.alpha_composite(head_small)
    body_canvas.alpha_composite(body_small)
    # Where the neck sits, measured off the cut rather than assumed: the body layer's topmost
    # opaque row is the stalk's crown, and it has to end up under the head's crop line or the
    # two halves overlap or leave a gap in the standing pose.
    body_top = body_small.getchannel("A").getbbox()[1]
    px = 1.0 / 100.0                      # sprite pixels -> cells (see MODEL_WIDTH)
    model_height = round(canvas_h * px, 4)
    # The canvas's bottom edge is the ground line, so a canvas centre sits half a height up.
    center_y = round(canvas_h / 2.0 * px, 4)
    # The head is the same canvas cropped at the neck, so its drawing sits at the canvas top and
    # has to come back down onto the stalk's crown. Both the part's `offset` and the clip's
    # `translation` place the quad (`offset + translation`), so the shift is "where the quad has
    # to start" minus "where the offset already puts it": the first two attempts at this got the
    # sign and the reference point wrong and stood the cap half a cell above the stalk.
    # The head's rest translation, solved rather than derived: a part's quad is centred on
    # `offset + translation`, so the drawing's own ink tells us where the cap's crop line lands,
    # and the shift is the difference between that and the stalk's crown. Three earlier attempts
    # at writing this as a formula put the cap half a cell off the stalk in one direction or the
    # other; the geometry is in the pixels, so it is read from the pixels.
    def ink_rows(image):
        alpha = np.array(image)[..., 3] > 40
        rows = np.nonzero(alpha.any(axis=1))[0]
        return int(rows.min()), int(rows.max()) + 1

    body_ink_top = ink_rows(body_small)[0]
    head_ink_bottom = ink_rows(head_small)[1]
    crown = round((canvas_h - body_ink_top) * px, 4)
    # Where the head's crop line lands with no bone translation at all. At rest the head's ink
    # bottom is `head_ink_bottom` rows below the canvas top, i.e. this far above the ground.
    head_crop_at_rest = round((canvas_h - head_ink_bottom) * px, 4)
    # So the head's rest translation is the shift that walks it onto the crown - one subtraction,
    # in model cells, and the only place the crop's assumption can be wrong. The sign, the
    # reference point and (twice) the alpha threshold each cost a round of "the mushroom is in
    # two pieces" before `ModelPartsJoinUpTest` started measuring the two textures and pinning
    # this answer, so the next edit to either layer fails there instead of on the lawn.
    head_rest = round(crown - head_crop_at_rest, 4)
    out_dir = RESOURCES / "assets" / PLANT_NS / "textures" / "entities" / GROUP / ENTITY
    center_x = 0.0
    part_size = [round(canvas_w * px, 4), model_height]
    texture_base = f"{PLANT_NS}:textures/entities/{GROUP}/{ENTITY}"

    def layer(name: str, z: float, pose_y: float, offset_y: float = None) -> dict:
        """One bone's part. `offset_y` defaults to the shared canvas centre; the flash passes
        its own, because its drawing is not the plant - it is the bolt, placed at the muzzle."""
        return {
            "part": {"texture": f"{texture_base}/{name}", "uv": [0, 0, canvas_w, canvas_h],
                     "size": part_size,
                     "offset": [center_x, center_y if offset_y is None else offset_y], "z": z},
            "pivot": [0.0, 0.0],
            "pose_y": pose_y,
        }

    # Painter's order: the flash (which is the projectile, at the muzzle) is behind everything,
    # then the body, the eyes over it, and the cap in front of them all.
    eyes, eye_mask = eye_layer(plant, width)
    eyes_canvas = Image.new("RGBA", (canvas_w, canvas_h), TRANSPARENT)
    eyes_canvas.alpha_composite(eyes)
    write_png(eyes_canvas, out_dir / "eyes.png")

    # The bolt leaves at `PlantShots.MUZZLE_OFFSET_X` cells from the plant's centre, on the
    # plant's own ground line; the flash is drawn there and nowhere else.
    flash, flash_y = muzzle_flash(bolt_small, (canvas_w, canvas_h), (0.30, 0.12))
    write_png(flash, out_dir / "flash.png")

    parts = {
        "body": {**layer("body", 1.0, 0.0), "model_height": model_height},
        "eyes": layer("eyes", 2.0, 0.0),
        "head": layer("head", 3.0, head_rest),
        "flash": layer("flash", 0.0, 0.0, offset_y=flash_y),
    }
    animation = animation_json(parts, head_rest)
    animation_path = RESOURCES / "assets" / PLANT_NS / "animations" / GROUP / f"{ENTITY}.json"
    animation_path.parent.mkdir(parents=True, exist_ok=True)
    animation_path.write_text(json.dumps(animation, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    log(f"wrote {animation_path.relative_to(REPO_ROOT)}")

    write_png(bolt_small, RESOURCES / "assets" / PLANT_NS / "textures" / "entities" / "projectile" / "iceboom_bolt.png")

    shatter = resize(crop_to_content(silhouette(load_keyed(SOURCES["shatter"]))), 200)
    write_png(shatter, RESOURCES / "assets" / PLANT_NS / "textures" / "particles" / "effect" / "iceboom_shatter.png")

    card = crop_to_content(load_keyed(SOURCES["card"]))
    card.thumbnail((108, 108), Image.LANCZOS)
    icon = Image.new("RGBA", (128, 128), TRANSPARENT)
    icon.alpha_composite(card, ((128 - card.width) // 2, (128 - card.height) // 2))
    write_png(icon, RESOURCES / "assets" / PLANT_NS / "textures" / "gui" / "cards" / f"{ENTITY}.png")

    if args.preview is not None:
        args.preview.mkdir(parents=True, exist_ok=True)
        sheet = Image.new("RGBA", (canvas_w * 3 + 60, canvas_h + 130), (72, 132, 48, 255))
        sheet.alpha_composite(body_canvas, (10, 10))
        sheet.alpha_composite(head_canvas, (canvas_w + 20, 10))
        assembled = Image.new("RGBA", (canvas_w, canvas_h), TRANSPARENT)
        assembled.alpha_composite(body_canvas)
        assembled.alpha_composite(eyes_canvas)
        assembled.alpha_composite(head_canvas)
        sheet.alpha_composite(assembled, (canvas_w * 2 + 30, 10))
        # Two blinks and the shot's four beats, the layers composited the way the game does:
        # the fastest way to see whether the timing works is a strip beside the parts.
        beats = [(0.0, "idle"), (0.06, "pull"), (0.10, "fire"), (0.22, "over"),
                 (0.38, "settle"), (0.0, "blink")]
        strip = Image.new("RGBA", (len(beats) * (canvas_w + 6) + 6, canvas_h + 12), (72, 132, 48, 255))
        for i, (_, name) in enumerate(beats):
            frame = Image.new("RGBA", (canvas_w, canvas_h), TRANSPARENT)
            frame.alpha_composite(body_canvas)
            frame.alpha_composite(eyes_canvas)
            frame.alpha_composite(head_canvas)
            strip.alpha_composite(frame, (6 + i * (canvas_w + 6), 6))
        sheet = sheet.crop((0, 0, sheet.width, sheet.height))
        sheet.alpha_composite(icon, (10, canvas_h + 20))
        sheet = sheet.resize((sheet.width * 3, sheet.height * 3), Image.NEAREST)
        sheet.save(args.preview / "sheet.png")
        log(f"preview: {args.preview / 'sheet.png'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
