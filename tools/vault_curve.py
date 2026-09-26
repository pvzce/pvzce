#!/usr/bin/env python3
"""Reads the jumping zombies' hop out of the exported clip and prints it as Java.

`VaultCapability` moves a vaulting zombie by the same curve its art is drawn with, so the sprite is
where the simulation says it is. Both halves of that motion are read from the **converted** clip
rather than from the raw reanim, because the conversion bakes the source's shear and scale into
every bone's translation - the drawn position is the model-space one, and the raw pixel
coordinates are a different curve that only looks similar.

The two halves:

* **travel**, from the torso (`body_1`). Every body bone agrees on the span to within a few per
  cent (1.06-1.13 cells), and the torso is the one the player reads as "where the zombie is";
  the feet disagree more, because the legs also swing about the hips. Normalised so the first
  frame is 0 and the last is 1, which makes it "how far across the hop";
* **lift**, from the lowest foot's bottom edge. That is the arc itself - the feet leave the ground
  at about 0.5 s, peak at 0.88 cells and are back down before the clip ends - and it is what the
  entity's own `height` follows, or the zombie hops with its legs stretched instead of tucked.

Run from the repository root:

    python3 tools/vault_curve.py

The output is `VAULT_TRAVEL_KEYS` / `VAULT_LIFT_KEYS` in `VaultCapability`, and the printed span is
`DEFAULT_JUMP_DISTANCE`. `PoleVaultTest.theArtAndTheSimulationAgreeOnWhereTheZombieIs` fails if the
two ever drift apart.
"""

from __future__ import annotations

import json
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
CLIP = (REPO_ROOT / "pvzce-game" / "src" / "main" / "resources" / "assets" / "pvzce"
        / "animations" / "zombie" / "special" / "pole_vaulter_zombie.json")

#: The clip's length in ticks, which is `VaultCapability.DEFAULT_JUMP_TICKS`.
TICKS = 210
#: One sample per this many ticks, which is the tables' own spacing.
STEP = 5
#: The bone whose x is the zombie's own position, and the feet that measure the arc.
BODY_BONE = "body_1"
FOOT_BONES = ("innerleg_foot", "outerleg_foot")


def sample(track: dict, time: float) -> list[float]:
    keys = sorted((float(k), v) for k, v in track.items())
    if time <= keys[0][0]:
        return list(keys[0][1])
    if time >= keys[-1][0]:
        return list(keys[-1][1])
    for index in range(len(keys) - 1):
        if keys[index][0] <= time <= keys[index + 1][0]:
            span = keys[index + 1][0] - keys[index][0]
            fraction = 0.0 if span <= 0 else (time - keys[index][0]) / span
            return [keys[index][1][axis] * (1 - fraction)
                    + keys[index + 1][1][axis] * fraction
                    for axis in (0, 1)]
    raise AssertionError("unreachable")


def main() -> None:
    document = json.loads(CLIP.read_text(encoding="utf-8"))
    clip = document["animations"]["jump"]
    length = clip["animation_length"]
    bones = clip["bones"]
    model = {bone["name"]: bone for bone in document["model"]["bones"]}

    def foot_bottom(bone: str, tick: int) -> float:
        translation = sample(bones[bone]["translation"], length * tick / TICKS)
        return translation[1] - model[bone]["parts"][0]["size"][1] / 2.0

    # Sampled at the *clip time* each tick is drawn on, which is only the same thing as a source
    # frame when the two rates agree - the clip is 43 frames over 210 ticks, so most samples land
    # between two keys and the runtime interpolates them. Reading the keys directly instead puts the
    # table a fortieth of the hop away from what is drawn, which was enough to fail the test below.
    def at(tick: int) -> float:
        return length * tick / TICKS

    positions = [sample(bones[BODY_BONE]["translation"], at(tick))[0]
                 for tick in range(0, TICKS + 1, STEP)]
    lifts = [min(foot_bottom(bone, tick) for bone in FOOT_BONES)
             for tick in range(0, TICKS + 1, STEP)]

    origin_x, origin_lift = positions[0], lifts[0]
    span = positions[-1] - origin_x
    if abs(span) < 1e-6:
        raise SystemExit("The jump clip does not travel; the mask or the clip changed")

    print(f"// {len(positions)} samples over {TICKS} ticks, from {CLIP.name}'s jump clip")
    print(f"// hop = {abs(span):.4f} cells (DEFAULT_JUMP_DISTANCE), "
          f"peak foot lift = {max(lifts) - origin_lift:.4f} cells")
    for name, values in (
        ("VAULT_TRAVEL_KEYS", [(x - origin_x) / span for x in positions]),
        ("VAULT_LIFT_KEYS", [lift - origin_lift for lift in lifts]),
    ):
        print(f"    private static final float[] {name} = {{")
        for start in range(0, len(values), 8):
            chunk = ", ".join(f"{value:+.4f}F" for value in values[start:start + 8])
            print(f"            {chunk},")
        print("    };")


if __name__ == "__main__":
    main()
