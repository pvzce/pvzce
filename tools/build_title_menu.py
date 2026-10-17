#!/usr/bin/env python3
"""Package the Chinese selector sprites and extract local-only original scenery.

The Chinese edits are made with built-in image_gen; prompts and untouched outputs
live in tools/art/title-menu/. This script only crops transparent padding and
resamples those outputs for the runtime. Original JPG/alpha-mask pairs remain in
local-assets/ and are never added to the distributable resources.
"""
from pathlib import Path

from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / 'tools/art/title-menu'
DEST = ROOT / 'pvzce-game/src/main/resources/assets/pvzce/textures/gui/screen/title/selector'
LOCAL = ROOT / 'local-assets/assets/pvzce/textures/gui/screen/title/selector'

# Native menu sizes; 2x textures stay sharp at the reference 1080p window.
SIZES = {
    'start': (329, 143), 'workshop': (313, 133), 'mods': (286, 122),
    'packs': (266, 90), 'player': (293, 150), 'switch': (291, 71),
    'shop': (130, 89), 'settings': (68, 26), 'quit': (48, 26),
}


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    for name, size in SIZES.items():
        source = Image.open(ART / f'{name}.png').convert('RGBA')
        bounds = source.getchannel('A').point(lambda a: 255 if a > 12 else 0).getbbox()
        if bounds is None:
            raise ValueError(f'Empty generated sprite: {name}')
        source = source.crop(bounds)
        target = (size[0] * 2, size[1] * 2)
        sprite = ImageOps.contain(source, target, Image.Resampling.LANCZOS)
        frame = Image.new('RGBA', target)
        frame.alpha_composite(sprite, ((target[0] - sprite.width) // 2,
                                      (target[1] - sprite.height) // 2))
        frame.save(DEST / f'{name}.png', optimize=True)

    LOCAL.mkdir(parents=True, exist_ok=True)
    original = ROOT / 'refer/anim'
    for source_name, name in [('BG', 'sky'), ('BG_Center', 'center'),
                              ('BG_Left', 'left'), ('BG_Right', 'right')]:
        image = Image.open(original / f'SelectorScreen_{source_name}.jpg').convert('RGBA')
        mask = original / f'SelectorScreen_{source_name}_.png'
        if mask.exists():
            image.putalpha(Image.open(mask).convert('L'))
        image.save(LOCAL / f'{name}.png', optimize=True)
    for source_name, name in [('Cloud5', 'cloud'), ('Leaves', 'leaves'),
                              ('Flower1', 'flower1'), ('Flower2', 'flower2'),
                              ('Flower3', 'flower3')]:
        Image.open(original / f'SelectorScreen_{source_name}.png').save(LOCAL / f'{name}.png')


if __name__ == '__main__':
    main()
