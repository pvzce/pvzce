#!/usr/bin/env python3
"""Prepare roof tool, buff, ladder and basketball sprites in ignored local-assets/."""
from pathlib import Path
import shutil
ROOT = Path(__file__).resolve().parents[1]
for source, target in [
    ("refer/im7/images/Fertilizer.png", "textures/gui/cards/fertilizer.png"),
    ("refer/anim/Cornpult_butter.png", "textures/gui/buff/butter_plenty.png"),
    ("refer/anim/Zombie_ladder_1.png", "textures/entities/plant/environment/roof_ladder.png"),
    ("refer/anim/Zombie_catapult_basketball.png", "textures/entities/projectile/basketball.png"),
]:
    dest = ROOT / "local-assets/assets/pvzce" / target
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / source, dest)
    print(dest.relative_to(ROOT))
