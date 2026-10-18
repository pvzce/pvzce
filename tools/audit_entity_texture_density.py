#!/usr/bin/env python3
"""Check that every built-in plant/zombie PNG has an explicit density decision.

Run after asset generation or upscaling: python3 tools/audit_entity_texture_density.py
Uses the source/target sizes in the upscaling manifests rather than maintaining a
second family list. Requires the project's local-assets and Pillow.
"""

import json
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
RESOURCE_ROOTS = (
    'pvzce-game/src/main/resources/assets/pvzce',
    'local-assets/assets/pvzce',
)
MANIFESTS = ('upscale_sprites_1440p.json', 'upscale_remaining_entities_1440p.json')


def audit(root=ROOT):
    decisions = {}
    for name in MANIFESTS:
        manifest = json.loads((root / 'tools' / name).read_text())
        for entry in manifest['assets']:
            decisions[entry['path']] = tuple(entry['target_size'])
        for entry in manifest.get('retained_high_density', []):
            decisions[entry['path']] = tuple(entry['size'])

    errors = []
    checked = 0
    for resource in RESOURCE_ROOTS:
        for kind in ('plant', 'zombie'):
            for path in sorted((root / resource / 'textures/entities' / kind).rglob('*.png')):
                relative = str(path.relative_to(root))
                expected = decisions.get(relative)
                if expected is None:
                    errors.append(f'No density decision: {relative}')
                    continue
                with Image.open(path) as image:
                    image.load()
                    if image.size != expected:
                        errors.append(f'{relative}: expected {expected}, found {image.size}')
                checked += 1
    if errors:
        raise ValueError('\n'.join(errors))
    if checked == 0:
        raise ValueError('No plant/zombie textures found; run in a populated project checkout')
    return checked


if __name__ == '__main__':
    print(f'Plant/zombie texture coverage complete: {audit()} PNGs checked.')
