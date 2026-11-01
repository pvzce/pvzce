#!/usr/bin/env python3
"""Shrink built-in dialogue portraits in place; leave original art and small images alone."""
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
PORTRAITS = ROOT / 'pvzce-game/src/main/resources/assets/pvzce/textures/gui/dialogue'
HEIGHT = int(next(line.split('=', 1)[1] for line in (ROOT / 'gradle.properties').read_text().splitlines()
                  if line.startswith('portrait_height=')))


def main():
    for path in sorted((p for p in PORTRAITS.rglob('*.png') if p.parent != PORTRAITS)):
        with Image.open(path) as image:
            if image.height <= HEIGHT:
                continue
            size = (max(1, round(image.width * HEIGHT / image.height)), HEIGHT)
            resized = image.resize(size, Image.Resampling.LANCZOS)
            resized.save(path, optimize=True)
            print(f'{path.relative_to(ROOT)}: {image.width}x{image.height} -> {size[0]}x{size[1]}')


if __name__ == '__main__':
    main()
