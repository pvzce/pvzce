#!/usr/bin/env python3
"""Copies the bungee zombie's two props out of the rip and into the asset tree.

The bungee zombie's reanim (`Zombie_bungi.reanim`) has no cord and no target on it: in the
original those are two objects the game draws *around* the zombie - a rope from the top of the
screen to its hands, and a dart-on-a-target marking where it will land. They live in the rip as
loose files (`refer/im7/images/BungeeCord.png`, `BungeeTarget.png`), which is why they are copied
here rather than converted.

They go under their own directory, `textures/entities/zombie/special/bungee_rig/`, and not into
the zombie's own texture folder: that folder is owned by the animation converter, which deletes
every file it does not recognise as one of the model's parts on each regeneration (see
`tools/reanim_to_pvzce_all.py`'s `clean_generated_textures`). A hand-added prop there would be
removed the next time the zombie's art is converted.

Run from the repository root:

    python3 tools/make_bungee_rig.py
"""

from __future__ import annotations

import shutil
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
SOURCE = REPO_ROOT / "refer" / "im7" / "images"
TARGET = (REPO_ROOT / "pvzce-game" / "src" / "main" / "resources" / "assets" / "pvzce"
          / "textures" / "entities" / "zombie" / "special" / "bungee_rig")

PROPS = {
    "BungeeCord.png": "cord.png",
    "BungeeTarget.png": "target.png",
}


def main() -> None:
    TARGET.mkdir(parents=True, exist_ok=True)
    for source_name, target_name in PROPS.items():
        source = SOURCE / source_name
        if not source.is_file():
            raise SystemExit(f"missing rip asset: {source}")
        destination = TARGET / target_name
        shutil.copyfile(source, destination)
        print(f"{source.name} -> {destination.relative_to(REPO_ROOT)}")


if __name__ == "__main__":
    main()
