#!/usr/bin/env bash
# Pack the GPT Image source without repainting it; author game-native animation/effect tracks.
set -euo pipefail
cd "$(dirname "$0")/.."
assets=pvzce-game/src/main/resources/assets/pvzce
plant_dir="$assets/textures/entities/plant/attacker/echo_lily"
mkdir -p "$plant_dir" "$assets/textures/gui/cards" "$assets/textures/entities/projectile" "$assets/textures/particles/effect" "$assets/sounds/original"
convert tools/art/echo_lily.png -trim +repage -resize 512x512! "$plant_dir/body.png"
convert tools/art/echo_lily.png -trim +repage -resize 432x432 -gravity center -background none -extent 512x512 "$assets/textures/gui/cards/echo_lily.png"
# Sound waves are native geometric effects, rather than another painted character.
convert -size 256x256 xc:none -fill none -stroke '#ffdf78' -strokewidth 20 -draw 'arc 12,12 244,244 -65,65' -stroke '#7af7d2' -strokewidth 16 -draw 'arc 56,56 200,200 -65,65' "$assets/textures/entities/projectile/echo_wave.png"
convert -size 512x512 xc:none -fill none -stroke '#ffe391' -strokewidth 20 -draw 'ellipse 256,256 208,208 0,360' -stroke '#88ffda' -strokewidth 12 -draw 'ellipse 256,256 160,160 0,360' "$assets/textures/particles/effect/echo_ring.png"
ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'aevalsrc=(0.55*sin(2*PI*880*t)+0.3*sin(2*PI*1320*t)+0.15*sin(2*PI*2200*t))*exp(-10*t)*(1-exp(-120*t)):s=44100:d=0.4' -c:a libvorbis "$assets/sounds/original/echo_chime.ogg"
python3 - <<'PY'
import json
import struct
from pathlib import Path

root = Path('pvzce-game/src/main/resources')
sprite = root / 'assets/pvzce/textures/entities/plant/attacker/echo_lily/body.png'
width, height = struct.unpack('>II', sprite.read_bytes()[16:24])
# Texture pixels select the sampled image; model cells determine how large it stands.
# Preserve the original 0.88-cell silhouette when exporting more texels.
model_size = 0.88
size = [model_size, model_size]
rest = {'translation': [0, 0], 'rotation': [0, 0, 0], 'scale': [1, 1]}
model = {'size': size, 'bones': [{'name': 'flower', 'parent': None, 'pivot': [0, 0],
    'transform': rest, 'parts': [{'texture': 'pvzce:textures/entities/plant/attacker/echo_lily/body',
    'uv': [0, 0, width, height], 'size': size, 'offset': [0, size[1] / 2], 'z': 1}]}]}

def clip(length, rotations, scales, loop=False):
    result = {'animation_length': length, 'loop': loop, 'rate': 1, 'transition': 0.06,
        'bones': {'flower': {'translation': {'0': [0, 0]},
            'rotation': {str(t): [0, 0, angle] for t, angle in rotations},
            'scale': {str(t): [x, y] for t, x, y in scales}, 'visible': {'0': True}}},
        'sound_effects': {}, 'particle_effects': {}, 'timeline': {}}
    if not loop:
        result['on_end'] = 'idle'
    return result

idle = clip(2.4, [(0, 0), (0.65, -1.5), (1.55, 1.2), (2.4, 0)],
            [(0, 1, 1), (0.65, 0.99, 1.015), (1.55, 1.01, 0.99), (2.4, 1, 1)], True)
shoot = clip(0.48, [(0, 0), (0.07, -5), (0.15, 7), (0.27, -3), (0.48, 0)],
             [(0, 1, 1), (0.07, 1.04, 0.93), (0.15, 0.95, 1.08), (0.27, 1.025, 0.98), (0.48, 1, 1)])
resonate = clip(0.65, [(0, 0), (0.1, -8), (0.22, 9), (0.36, -5), (0.5, 2), (0.65, 0)],
                [(0, 1, 1), (0.1, 1.08, 0.9), (0.22, 0.94, 1.12), (0.4, 1.04, 0.97), (0.65, 1, 1)])
animation = {'type': 'controller', 'model': model,
             'animations': {'idle': idle, 'shoot': shoot, 'echo': shoot, 'resonate': resonate}}
output = root / 'assets/pvzce/animations/plant/attacker/echo_lily.json'
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(animation, indent=2) + '\n')
print(f'Echo Lily packed: {width}x{height}, clips idle/shoot/echo/resonate')
PY
