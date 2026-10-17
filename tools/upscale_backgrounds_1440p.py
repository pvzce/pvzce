#!/usr/bin/env python3
"""Use Upscayl's Flatpak backend, then limit textures to their 1440p display budget.

Input is a separate snapshot of the repository paths listed in the JSON manifest;
it must contain the original bytes, never previously upscaled textures. Example:
  python3 tools/upscale_backgrounds_1440p.py --source-root .smoke/upscayl-1440p/originals
Afterwards run tools/bake_sod_backdrop.py to rebuild the two opening lawns.
Requires Upscayl (org.upscayl.Upscayl), Pillow and a Vulkan GPU.
"""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', required=True, type=Path)
    args = parser.parse_args()
    source_root = args.source_root.resolve()
    manifest = json.loads(Path(__file__).with_suffix('.json').read_text())
    # Validate the whole snapshot before replacing any project texture.
    for entry in manifest['assets']:
        source = source_root / entry['path']
        if hashlib.sha256(source.read_bytes()).hexdigest() != entry['sha256']:
            raise ValueError(f'Expected original source bytes: {source}')

    work = ROOT / '.smoke/upscayl-1440p'
    work.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='batch-', dir=work) as temporary:
        for index, entry in enumerate(manifest['assets']):
            source = source_root / entry['path']
            target = ROOT / entry['path']
            raw = Path(temporary) / f'{index}.png'
            with (work / f'asset-{index:02}.log').open('w') as log:
                subprocess.run([
                    'flatpak', 'run', f'--filesystem={source_root}:ro',
                    f'--filesystem={work}',
                    '--command=/app/upscayl/resources/bin/upscayl-bin',
                    'org.upscayl.Upscayl', '-i', str(source), '-o', str(raw),
                    '-m', '/app/upscayl/resources/models', '-n', manifest['model'],
                    '-s', '4', '-g', '0', '-t', '256', '-f', 'png',
                ], check=True, stdout=log, stderr=subprocess.STDOUT)
            with Image.open(source) as original, Image.open(raw) as enhanced:
                mode = original.mode
                # Only colour is reconstructed by AI. Keep the source silhouette;
                # inferred alpha can introduce opaque fringes on layered scenery.
                colour = enhanced.convert('RGB').resize(
                    entry['target_size'], Image.Resampling.LANCZOS)
                if mode == 'RGBA':
                    colour.putalpha(original.getchannel('A').resize(
                        entry['target_size'], Image.Resampling.LANCZOS))
                colour.save(target, optimize=True)
            print(f"{index + 1}/{len(manifest['assets'])}: {entry['path']} "
                  f"→ {entry['target_size']}", flush=True)


if __name__ == '__main__':
    main()
