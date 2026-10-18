#!/usr/bin/env python3
"""Use Upscayl's Flatpak backend, then limit textures to their 1440p display budget.

Input is a separate snapshot of the repository paths listed in the JSON manifest;
it must contain the original bytes, never previously upscaled textures. Example:
  python3 tools/upscale_backgrounds_1440p.py --source-root .smoke/upscayl-1440p/originals
Afterwards run tools/bake_sod_backdrop.py to rebuild the two opening lawns.
For entity/UI textures, add --manifest tools/upscale_sprites_1440p.json and use
a separate --work-dir. Include the manifest's controller files in the snapshot;
their pixel UVs are scaled without changing model geometry or animation tracks.
Identical source bytes share one inference in Upscayl's directory batch mode.
Requires Upscayl (org.upscayl.Upscayl), Pillow and a Vulkan GPU.
"""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', required=True, type=Path)
    parser.add_argument('--manifest', type=Path, default=Path(__file__).with_suffix('.json'))
    parser.add_argument('--work-dir', type=Path, default=ROOT / '.smoke/upscayl-1440p')
    args = parser.parse_args()
    source_root = args.source_root.resolve()
    manifest = json.loads(args.manifest.read_text())
    # Validate the whole snapshot before replacing any project texture.
    for entry in manifest['assets'] + manifest.get('controllers', []):
        source = source_root / entry['path']
        if hashlib.sha256(source.read_bytes()).hexdigest() != entry['sha256']:
            raise ValueError(f'Expected original source bytes: {source}')

    work = args.work_dir.resolve()
    work.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='batch-', dir=work) as temporary:
        inputs = Path(temporary) / 'input'
        outputs = Path(temporary) / 'output'
        inputs.mkdir()
        outputs.mkdir()
        for entry in manifest['assets']:
            copy = inputs / (entry['sha256'] + '.png')
            if not copy.exists():
                shutil.copy2(source_root / entry['path'], copy)
        with (work / 'inference.log').open('w') as log:
            subprocess.run([
                'flatpak', 'run', f'--filesystem={work}',
                '--command=/app/upscayl/resources/bin/upscayl-bin',
                'org.upscayl.Upscayl', '-i', str(inputs), '-o', str(outputs),
                '-m', '/app/upscayl/resources/models', '-n', manifest['model'],
                '-s', '4', '-g', '0', '-t', '256', '-j', '1:1:1', '-f', 'png',
            ], check=True, stdout=log, stderr=subprocess.STDOUT)
        for index, entry in enumerate(manifest['assets']):
            source = source_root / entry['path']
            target = ROOT / entry['path']
            raw = outputs / (entry['sha256'] + '.png')
            with Image.open(source) as original, Image.open(raw) as enhanced:
                mode = original.mode
                # Only colour is reconstructed by AI. Keep the source silhouette;
                # inferred alpha can introduce opaque fringes on layered scenery.
                colour = enhanced.convert('RGB').resize(
                    entry['target_size'], Image.Resampling.LANCZOS)
                if mode in ('RGBA', 'LA', 'P') or 'transparency' in original.info:
                    if mode == 'LA':
                        colour = colour.convert('L')
                    colour.putalpha(original.convert('RGBA').getchannel('A').resize(
                        entry['target_size'], Image.Resampling.LANCZOS))
                colour.save(target, optimize=True)
            print(f"{index + 1}/{len(manifest['assets'])}: {entry['path']} "
                  f"→ {entry['target_size']}", flush=True)

    # Controller UVs are top-left pixel coordinates. Geometry, anchors, poses and
    # clocks remain in their original world units; only the sampling rectangle grows.
    density = {}
    for entry in manifest['assets']:
        texture = entry['path'].split('assets/pvzce/', 1)[1].removesuffix('.png')
        density['pvzce:' + texture] = [new / old for new, old in
                                      zip(entry['target_size'], entry['source_size'])]
    for entry in manifest.get('controllers', []):
        source = source_root / entry['path']
        controller = json.loads(source.read_text())
        for bone in controller['model']['bones']:
            for part in bone.get('parts', []):
                scale = density.get(part['texture'])
                if scale is not None:
                    part['uv'] = [value * scale[index % 2]
                                  for index, value in enumerate(part['uv'])]
        (ROOT / entry['path']).write_text(json.dumps(controller, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
