#!/usr/bin/env python3
"""Draws the bobsled team's sled.

The original's rip has no sled sprite: `Zombie_bobsled.reanim` is four bodies of one rider's art,
offset along the lane, and the board they sit on is drawn by the game itself rather than by the
reanim. So this is a **new drawing** rather than a conversion, and it is the one place in the
project's art that is - everything else in `assets/pvzce/` is cut or converted from `refer/`.

What it is modelled on:

* the palette of the rider's own art (`Zombie_bobsled_body1.png`: the red tunic at RGB 175,30,28,
  boots at 105,21,21, skin at 199,138,91), so the board belongs to the same drawing;
* the original's own layout, which the report asks for - "一块板子载四个人": one long deck with the
  lead on the nose and three bench seats behind it - and its
  `(position + 1) * 50 px` spacing, which is what `BobsledCapability.DEFAULT_SPACING` is.

    python3 tools/gen_bobsled_sled.py
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

REPO_ROOT = Path(__file__).resolve().parents[1]
#: Where the drawing lives. **Not** under the assets the game loads: the exporter copies a part
#: from its source into `assets/.../<entity>/<bone>.png` and deletes everything else in that
#: directory first, so a source that lived there would delete itself on the next re-export. The
#: path below is the one `EntityConfig.extra_bones` names.
OUT = REPO_ROOT / "tools" / "art" / "bobsled_sled.png"

#: The sprite's own size in pixels. The model is drawn at ~0.006 world cells per source pixel
#: (`Zombie_bobsled.reanim`'s own 155px-tall rider becomes the zombie's 0.95), so this is about
#: 1.9 x 0.44 cells: four riders at the team's 0.625-cell spacing.
WIDTH, HEIGHT = 300, 72

#: Colours, sampled from the rider's art so the board is the same object as the people on it.
RUNNER = (58, 62, 72)
RUNNER_LIGHT = (150, 158, 172)
STRUT = (92, 52, 28)
DECK = (128, 74, 38)
DECK_LIGHT = (183, 118, 62)
DECK_DARK = (74, 38, 18)
SEAT = (175, 30, 28)
SEAT_LIGHT = (214, 74, 62)
RED_DARK = (105, 21, 21)

#: How much of the sprite's height the deck and the runners take, top down. The rest above the
#: deck is the transparent space the riders' feet sit in.
DECK_TOP, DECK_BOTTOM = 30, 46
RUNNER_TOP, RUNNER_BOTTOM = 56, 66
#: The nose curls up at the front (the squad slides to the left, so the front is the left edge).
NOSE_WIDTH = 54
NOSE_LIFT = 22


def main() -> None:
    image = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    def rect(x0, y0, x1, y1, colour):
        draw.rectangle([x0, y0, x1, y1], fill=colour)

    # The deck: a plank, with the nose turned up at the front.
    rect(NOSE_WIDTH - 6, DECK_TOP, WIDTH - 6, DECK_BOTTOM, DECK)
    rect(NOSE_WIDTH - 6, DECK_TOP, WIDTH - 6, DECK_TOP + 4, DECK_LIGHT)
    rect(NOSE_WIDTH - 6, DECK_BOTTOM - 5, WIDTH - 6, DECK_BOTTOM, DECK_DARK)
    # The nose: a quarter-turn of the same plank, drawn as a run of short columns.
    for step in range(NOSE_WIDTH):
        progress = step / NOSE_WIDTH
        lift = round(NOSE_LIFT * (1 - progress) ** 2)
        rect(step, DECK_TOP - lift, step + 1, DECK_BOTTOM - lift, DECK)
        rect(step, DECK_TOP - lift, step + 1, DECK_TOP - lift + 3, DECK_LIGHT)
        rect(step, DECK_BOTTOM - lift - 4, step + 1, DECK_BOTTOM - lift, DECK_DARK)

    # Three bench seats behind the lead's place on the nose.
    first_seat = NOSE_WIDTH + 22
    for index in range(3):
        x = first_seat + index * 74
        rect(x, DECK_TOP - 12, x + 44, DECK_TOP, SEAT)
        rect(x, DECK_TOP - 12, x + 44, DECK_TOP - 9, SEAT_LIGHT)
        rect(x, DECK_TOP - 4, x + 44, DECK_TOP, RED_DARK)
        # The back rest, which is what makes a bench a bench rather than a stripe.
        rect(x + 38, DECK_TOP - 26, x + 44, DECK_TOP - 12, SEAT)
        rect(x + 38, DECK_TOP - 26, x + 44, DECK_TOP - 23, SEAT_LIGHT)

    # The runners, and the struts that carry the deck.
    for x in range(NOSE_WIDTH - 2, WIDTH - 4, 58):
        rect(x, DECK_BOTTOM, x + 7, RUNNER_TOP + 2, STRUT)
    rect(2, RUNNER_TOP, WIDTH - 4, RUNNER_BOTTOM - 6, RUNNER)
    rect(2, RUNNER_TOP, WIDTH - 4, RUNNER_TOP + 3, RUNNER_LIGHT)
    # The runners curl up at the nose too, so the sled reads as a sled from the side.
    for step in range(NOSE_WIDTH):
        progress = step / NOSE_WIDTH
        lift = round((NOSE_LIFT + 6) * (1 - progress) ** 2)
        rect(step, RUNNER_TOP - lift, step + 1, RUNNER_BOTTOM - 6 - lift, RUNNER)
        rect(step, RUNNER_TOP - lift, step + 1, RUNNER_TOP + 3 - lift, RUNNER_LIGHT)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    image.save(OUT)
    print(f"wrote {OUT.relative_to(REPO_ROOT)} ({WIDTH}x{HEIGHT})")
    print("now re-export the part: python3 tools/reanim_to_pvzce_all.py --entity bobsled_zombie")


if __name__ == "__main__":
    main()
