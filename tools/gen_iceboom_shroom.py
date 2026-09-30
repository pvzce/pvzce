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
IDLE_LENGTH = 2.4
SHOOT_LENGTH = 0.75

# How far the cap bobs, in cells. The head sits on the stalk, so moving it far reads as
# the cap leaving the body; a tenth of a cell is a breath.
IDLE_BOB = 0.018
IDLE_SQUASH = 0.015

# The shot: the cap shoves forward and down, the body flattens under it, then both come
# back. Rate 1.6 makes the clip play a little faster than it is authored, which is the
# same trick the converted plants use (see the Snow Pea's `shoot`, rate 2.0).
SHOOT_RATE = 1.6
SHOOT_HEAD_PUSH = -0.055
SHOOT_HEAD_DIP = -0.045
SHOOT_BODY_SQUASH = 0.075


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


def animation_json(body_part: dict, head_part: dict, head_rest: float) -> dict:
    """The controller model plus its two clips, in the shape the loader reads."""
    model = {
        "size": [MODEL_WIDTH, body_part["model_height"]],
        "bones": [
            {
                "name": "body",
                "parent": None,
                "pivot": body_part["pivot"],
                "parts": [body_part["part"]],
            },
            {
                "name": "head",
                "parent": None,
                "pivot": head_part["pivot"],
                "parts": [head_part["part"]],
            },
        ],
    }
    # The pose a bone's clip carries is a **delta on top of the rest pose**, not the rest
    # position: a part's `offset` already places its canvas, and a translation track with the
    # same numbers in it moves the drawing by that much a second time. The first version of
    # this model did exactly that and stood the stalk a whole canvas-height above the cap.
    # A part's `offset` already places its canvas; a clip's `translation` is a delta on top of
    # that. So the head's rest position is the *shift* the crop forces on it, and every key in
    # the clip is that shift plus the animation.
    body_y = 0.0
    head_y = head_rest
    # Idle: the cap breathes up and down, the stalk answers with a small counter-squash.
    # Two seconds and a bit, so a lawn of them does not pulse in lockstep with the peas.
    steps = 8
    head_pairs: dict[float, tuple[float, float]] = {}
    body_scale: dict[float, tuple[float, float]] = {}
    for i in range(steps + 1):
        t = IDLE_LENGTH * i / steps
        phase = np.sin(2 * np.pi * i / steps)
        head_pairs[round(t, 4)] = (0.0, head_y + IDLE_BOB * phase)
        body_scale[round(t, 4)] = (1.0 + IDLE_SQUASH * -phase, 1.0 + IDLE_SQUASH * phase)
    idle = {
        "animation_length": IDLE_LENGTH,
        "loop": True,
        "transition": 0.12,
        "bones": {
            "body": {
                "translation": vec_track({0.0: (0.0, body_y)}),
                "scale": scalar_track(body_scale),
                "visible": visible_track(),
            },
            "head": {
                "translation": vec_track(head_pairs),
                "rotation": vec_track({0.0: (0.0, 0.0, 0.0)}),
                "visible": visible_track(),
            },
        },
        "sound_effects": {},
        "particle_effects": {},
        "timeline": {},
    }
    # Shoot: the cap rams forward and dips, the stalk flattens under it, everything is back
    # where it started by the last key so the blend into `idle` is invisible.
    shoot = {
        "animation_length": SHOOT_LENGTH,
        "loop": False,
        "on_end": "idle",
        "rate": SHOOT_RATE,
        "transition": 0.08,
        "bones": {
            "body": {
                "translation": vec_track({0.0: (0.0, body_y)}),
                "scale": scalar_track({
                    0.0: (1.0, 1.0),
                    0.10: (1.0 + SHOOT_BODY_SQUASH, 1.0 - SHOOT_BODY_SQUASH),
                    0.30: (1.0 - SHOOT_BODY_SQUASH * 0.5, 1.0 + SHOOT_BODY_SQUASH * 0.5),
                    SHOOT_LENGTH: (1.0, 1.0),
                }),
                "visible": visible_track(),
            },
            "head": {
                "translation": vec_track({
                    0.0: (0.0, head_y),
                    0.10: (SHOOT_HEAD_PUSH, head_y + SHOOT_HEAD_DIP),
                    0.30: (-SHOOT_HEAD_PUSH * 0.5, head_y + IDLE_BOB),
                    SHOOT_LENGTH: (0.0, head_y),
                }),
                "rotation": vec_track({
                    0.0: (0.0, 0.0, 0.0),
                    0.10: (0.0, 0.0, -6.0),
                    0.30: (0.0, 0.0, 2.0),
                    SHOOT_LENGTH: (0.0, 0.0, 0.0),
                }),
                "visible": visible_track(),
            },
        },
        "sound_effects": {},
        "particle_effects": {},
        "timeline": {},
    }
    return {"type": "controller", "model": model, "animations": {"idle": idle, "shoot": shoot}}


def visible_track(value: bool = True) -> dict[str, bool]:
    """A bone's visibility, as a JSON **boolean**.

    <p>Not `1`: the loader reads this channel with Gson's `getAsBoolean()`, which *throws* on a
    number rather than coercing it, and a malformed key is dropped on the floor - so a bone
    whose `visible` says `1` ends up with an empty track, and an empty track samples the bone's
    own rest pose. That is how the first version of this plant shipped a model that drew its
    two bones with `visible=false` every frame: the plant was rendered, correctly sized, at the
    right cell, and completely invisible - only its shadow showed.
    """
    return {"0": value}


def write_png(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path)
    log(f"wrote {path.relative_to(REPO_ROOT)} ({image.width}x{image.height})")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preview", type=Path, default=None,
                        help="write a contact sheet here (not part of the build)")
    args = parser.parse_args(argv)

    plant = crop_to_content(load_keyed(SOURCES["body"]))
    neck_row = int(round(plant.height * NECK_FRACTION))
    head_full, body_full = build_parts(plant, neck_row)
    log(f"source {plant.width}x{plant.height}, neck at row {neck_row}")

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
    center_x = 0.0
    part_size = [round(canvas_w * px, 4), model_height]
    texture_base = f"{PLANT_NS}:textures/entities/{GROUP}/{ENTITY}"
    body_part = {
        "part": {"texture": f"{texture_base}/body", "uv": [0, 0, canvas_w, canvas_h],
                 "size": part_size, "offset": [center_x, center_y], "z": 0},
        "pivot": [0.0, 0.0],
        "pose_y": 0.0,
        "model_height": model_height,
        "image_width": canvas_w,
        "image_height": canvas_h,
    }
    head_part = {
        "part": {"texture": f"{texture_base}/head", "uv": [0, 0, canvas_w, canvas_h],
                 "size": part_size,
                 "offset": [center_x, center_y], "z": 1},
        "pivot": [0.0, 0.0],
        "pose_y": head_rest,
        "image_width": canvas_w,
        "image_height": canvas_h,
    }
    animation = animation_json(body_part, head_part, head_rest)
    animation_path = RESOURCES / "assets" / PLANT_NS / "animations" / GROUP / f"{ENTITY}.json"
    animation_path.parent.mkdir(parents=True, exist_ok=True)
    animation_path.write_text(json.dumps(animation, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    log(f"wrote {animation_path.relative_to(REPO_ROOT)}")

    bolt = resize(crop_to_content(silhouette(load_keyed(SOURCES["bolt"]))), 40)
    write_png(bolt, RESOURCES / "assets" / PLANT_NS / "textures" / "entities" / "projectile" / "iceboom_bolt.png")

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
        assembled.alpha_composite(head_canvas)
        sheet.alpha_composite(assembled, (canvas_w * 2 + 30, 10))
        sheet.alpha_composite(icon, (10, canvas_h + 20))
        sheet = sheet.resize((sheet.width * 3, sheet.height * 3), Image.NEAREST)
        sheet.save(args.preview / "sheet.png")
        log(f"preview: {args.preview / 'sheet.png'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
