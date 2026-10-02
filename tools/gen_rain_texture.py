#!/usr/bin/env python3
"""Convert the original LA rain tile to the runtime's RGBA texture format."""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / 'local-assets/assets/pvzce/textures/particles/water/rain.png'

if __name__ == '__main__':
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    Image.open(ROOT / 'refer/im7/particles/Rain.png').convert('RGBA').save(TARGET)
    print(TARGET)
