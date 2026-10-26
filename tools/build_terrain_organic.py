#!/usr/bin/env python3
"""Package the image_gen terrain edits without moving the stage's existing geometry.

Requires Pillow, numpy, and the repository's existing Upscayl Flatpak backend.
Run from any directory: python3 tools/build_terrain_organic.py
Generated source pixels live in tools/art/terrain-organic/generated/ as lossless WebP.
Only density recovery and edge wrapping happen here;
the material artwork itself comes from the recorded image_gen edits.
"""

import hashlib
import io
import json
from pathlib import Path
import subprocess

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / "tools/art/terrain-organic"
ASSETS = ROOT / "pvzce-game/src/main/resources/assets/pvzce"
WORK = ROOT / ".smoke/terrain-organic"
MODEL = "high-fidelity-4x"


def fingerprint(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def original(meta, relative):
    path = "pvzce-game/src/main/resources/assets/pvzce/" + relative
    data = subprocess.check_output(["git", "show", meta["baseline_commit"] + ":" + path], cwd=ROOT)
    if hashlib.sha256(data).hexdigest() != meta["sources"][relative]["sha256"]:
        raise ValueError(f"Baseline changed: {relative}")
    return Image.open(io.BytesIO(data)).copy()


def recover_density(meta):
    """Use the same model as the existing 1440p asset pipeline; cache by source bytes."""
    inputs, outputs = WORK / "upscale-input-hashed", WORK / "upscale-output"
    inputs.mkdir(parents=True, exist_ok=True)
    outputs.mkdir(parents=True, exist_ok=True)
    paths = {}
    missing = []
    for key, entry in meta["selected"].items():
        if key != "ground":
            continue
        source = ROOT / entry["generated_source"]
        if fingerprint(source) != entry["stored_sha256"]:
            raise ValueError(f"Generated source changed: {key}")
        destination = outputs / (entry["stored_sha256"] + ".png")
        paths[key] = destination
        if not destination.exists():
            with Image.open(source) as image:
                image.convert("RGB").save(inputs / destination.name)
            missing.append(key)
    if missing:
        with (WORK / "upscale.log").open("w") as log:
            subprocess.run([
                "flatpak", "run", f"--filesystem={WORK}",
                "--command=/app/upscayl/resources/bin/upscayl-bin", "org.upscayl.Upscayl",
                "-i", str(inputs), "-o", str(outputs),
                "-m", "/app/upscayl/resources/models", "-n", MODEL,
                "-s", "4", "-g", "0", "-t", "256", "-j", "1:1:1", "-f", "png",
            ], check=True, stdout=log, stderr=subprocess.STDOUT)
    return paths


def repeat_edges(image):
    """Close only a narrow wrap band; the atlas centers and artwork stay where drawn."""
    pixels = np.asarray(image.convert("RGB"), dtype=np.float32).copy()
    height, width = pixels.shape[:2]
    band = max(2, min(width, height) // 64)
    for offset in range(band):
        weight = 0.5 + 0.5 * np.cos(np.pi * offset / band)
        left, right = pixels[:, offset].copy(), pixels[:, -offset - 1].copy()
        mean = (left + right) / 2
        pixels[:, offset] = left * (1 - weight) + mean * weight
        pixels[:, -offset - 1] = right * (1 - weight) + mean * weight
    for offset in range(band):
        weight = 0.5 + 0.5 * np.cos(np.pi * offset / band)
        top, bottom = pixels[offset].copy(), pixels[-offset - 1].copy()
        mean = (top + bottom) / 2
        pixels[offset] = top * (1 - weight) + mean * weight
        pixels[-offset - 1] = bottom * (1 - weight) + mean * weight
    return Image.fromarray(np.clip(pixels + 0.5, 0, 255).astype(np.uint8))


def main():
    meta_path = ART / "provenance.json"
    meta = json.loads(meta_path.read_text())
    enhanced = recover_density(meta)
    for key, entry in meta["selected"].items():
        source = ROOT / entry["generated_source"]
        if fingerprint(source) != entry["stored_sha256"]:
            raise ValueError(f"Generated source changed: {key}")
        baseline = original(meta, entry["target"])
        with Image.open(enhanced.get(key, source)) as image:
            colour = image.convert("RGB").resize(baseline.size, Image.Resampling.LANCZOS)
        if entry["target"].startswith("textures/scene/"):
            result = repeat_edges(colour)
            if baseline.mode == "RGBA":
                # These are full-cell materials. The old roof variants' partial alpha
                # belongs to placeholder lettering, not to a transparent silhouette.
                result = result.convert("RGBA")
        else:
            result = colour
        target = ASSETS / entry["target"]
        result.save(target, optimize=True)
        entry["installed_sha256"] = fingerprint(target)
        entry["installed_size"] = list(result.size)
        print("packaged", entry["target"], result.size, flush=True)
    subprocess.run(["python3", str(ROOT / "tools/gen_water_frames.py")], cwd=ROOT, check=True)
    derived = [f"textures/scene/water_frames/water_{n}.png" for n in range(4)]
    for relative in derived:
        path = ASSETS / relative
        with Image.open(path) as image:
            meta["derived"][relative] = {"sha256": fingerprint(path), "size": list(image.size)}
    meta["packaging"] = {"density_model": MODEL,
                         "materials": "original resource dimensions, narrow periodic wrap band"}
    meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
