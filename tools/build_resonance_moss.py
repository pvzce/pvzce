#!/usr/bin/env python3
"""Package the original GPT Image mat at 512px; model size stays in world cells."""
import json
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'pvzce-game/src/main/resources/assets/pvzce'
source = Image.open(ROOT / 'tools/art/resonance_moss.png').convert('RGBA')
# Disregard very faint alpha specks when finding export bounds; keep the original alpha.
source = source.crop(source.getchannel('A').point(lambda a: 255 if a > 8 else 0).getbbox())
body = source.resize((512, round(512 * source.height / source.width)), Image.Resampling.LANCZOS)
folder = ASSETS / 'textures/entities/plant/environment/resonance_moss'
folder.mkdir(parents=True, exist_ok=True)
body.save(folder / 'body.png')
card = Image.new('RGBA', (512, 512))
preview = source.copy()
preview.thumbnail((448, 448), Image.Resampling.LANCZOS)
card.alpha_composite(preview, ((512 - preview.width) // 2, (512 - preview.height) // 2))
card.save(ASSETS / 'textures/gui/cards/resonance_moss.png')
size = [0.96, 0.96 * body.height / body.width]
model = {'size': size, 'bones': [{'name': 'moss', 'parent': None, 'pivot': [0, 0],
    'transform': {'translation': [0, 0], 'rotation': [0, 0, 0], 'scale': [1, 1]},
    'parts': [{'texture': 'pvzce:textures/entities/plant/environment/resonance_moss/body',
        'uv': [0, 0, body.width, body.height], 'size': size, 'offset': [0, 0.10], 'z': 1}]}]}

def clip(length, amplitude):
    return {'animation_length': length, 'loop': True, 'rate': 1, 'transition': 0.08,
        'bones': {'moss': {'translation': {'0': [0, 0]}, 'rotation': {'0': [0, 0, 0]},
            'scale': {'0': [1, 1], str(length / 2): [1 + amplitude, 1 - amplitude],
                str(length): [1, 1]}, 'visible': {'0': True}}},
        'sound_effects': {}, 'particle_effects': {}, 'timeline': {}}

output = ASSETS / 'animations/plant/environment/resonance_moss.json'
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps({'type': 'controller', 'model': model,
    'animations': {'idle': clip(2.4, 0.015), 'linked': clip(1.5, 0.025),
        'charged': clip(0.9, 0.05)}}, indent=2) + '\n')
print(f'Resonance Moss: {body.width}x{body.height}, idle/linked/charged')
