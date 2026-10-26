#!/usr/bin/env python3
"""Restore pre-repaint, High Fidelity 1440p PvZ stages into local-assets.

Uses the fingerprinted baseline retained by the terrain art pipeline. Original
art stays outside the public resources; stage IDs and reference geometry stay
unchanged. Also rebuilds the single-row and three-row opening lawns.
"""

import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    meta = json.loads((ROOT / "tools/art/terrain-organic/provenance.json").read_text())
    for entry in meta["retired_backdrops"].values():
        relative = entry["target"]
        source = "pvzce-game/src/main/resources/assets/pvzce/" + relative
        data = subprocess.check_output(
            ["git", "show", meta["baseline_commit"] + ":" + source], cwd=ROOT)
        if hashlib.sha256(data).hexdigest() != meta["sources"][relative]["sha256"]:
            raise ValueError(f"Baseline changed: {relative}")
        target = ROOT / "local-assets/assets/pvzce" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        print("restored", target.relative_to(ROOT))

    subprocess.run(["python3", str(ROOT / "tools/bake_sod_backdrop.py")], cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
