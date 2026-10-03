#!/usr/bin/env python3
"""Prepare roof tool, buff, ladder and basketball sprites in ignored local-assets/."""
from pathlib import Path
import shutil
ROOT = Path(__file__).resolve().parents[1]
for source, target in [
    ("refer/im7/images/Fertilizer.png", "textures/gui/cards/fertilizer.png"),
    ("refer/anim/Cornpult_butter.png", "textures/gui/buff/butter_plenty.png"),
    ("refer/anim/Cornpult_butter_splat.png", "textures/entities/status/butter.png"),
    ("refer/anim/Zombie_ladder_1.png", "textures/entities/plant/environment/roof_ladder.png"),
    ("refer/anim/Zombie_catapult_basketball.png", "textures/entities/projectile/basketball.png"),
]:
    dest = ROOT / "local-assets/assets/pvzce" / target
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / source, dest)
    print(dest.relative_to(ROOT))

# This rip has the projectile art but no original Melon/WinterMelon particle sheet.
# Stable 3x3 fragments keep the flight texture distinct and avoid whole-mini-melon splats.
from PIL import Image
for name, source in [("melon", "Melonpult_melon.png"),
                     ("winter_melon", "WinterMelon_projectile.png")]:
    art = Image.open(ROOT / "refer/anim" / source).convert("RGBA")
    for row in range(3):
        for column in range(3):
            dest = ROOT / "local-assets/assets/pvzce/textures/particles/plant" / (
                f"{name}_chunk_{row * 3 + column + 1}.png")
            dest.parent.mkdir(parents=True, exist_ok=True)
            art.crop((column * art.width // 3, row * art.height // 3,
                      (column + 1) * art.width // 3, (row + 1) * art.height // 3)).save(dest)
