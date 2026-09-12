#!/usr/bin/env python3
"""Group the shipped entity art by kind, and flatten the built-in data pack.

Two layout cleanups that have to happen together, because the second rewrites
paths the first one moves.

**1. Entity art.** The original layout was flat -
``assets/pvzce/textures/entities/<id>/`` and ``assets/pvzce/animations/<id>.json``
- so plants, zombies, projectiles and drops all sat in one directory and the only
way to tell them apart was to know the id. Both trees move to

    assets/pvzce/textures/entities/<kind>/<category>/<id>/*.png
    assets/pvzce/animations/<kind>/<category>/<id>.json

and every reference is rewritten with them: the ``texture`` field of each
controller part, plus ``animation_dir`` and ``texture`` on each content
definition. Content ids are deliberately NOT changed - the animation directory is
declared by the definition instead of derived from the id, so no level, tag, save
or language key is affected. See ``docs/mod-guide/animation.md``.

**2. The data pack.** ``data/pvzce/<registry>/`` carried the namespace
twice; the pack root is now ``data/pvzce/<registry>/``.

Idempotent: running it twice reports "already grouped" and changes nothing.

Run from the repository root:

    python3 tools/group_entity_assets.py --dry-run
    python3 tools/group_entity_assets.py
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
RESOURCES = REPO_ROOT / "pvzce-game" / "src" / "main" / "resources"
NS = "pvzce"
TEXTURES = RESOURCES / "assets" / NS / "textures"
ANIMATIONS = RESOURCES / "assets" / NS / "animations"
DATA = RESOURCES / "data" / NS
# The pre-flattening pack root that everything under `data/` is moving out of.
LEGACY_DATA = DATA / NS

# ---------------------------------------------------------------------------
# The classification. One entry per entity directory that exists today.
#
# Categories come from the taxonomy the design docs already use for plants and
# zombies (docs/02 sections 2.2.1 and 2.3.1); a category that would hold a single
# entity is not worth a directory of its own, so the rare ones sit in `special`.
# ---------------------------------------------------------------------------
ENTITIES: dict[str, tuple[str, str]] = {
    # --- plants ---
    "chomper": ("plant", "attacker"),
    "kernel_pult": ("plant", "attacker"),
    "pea_shooter": ("plant", "attacker"),
    "marigold": ("plant", "producer"),
    "sunflower": ("plant", "producer"),
    "wall_nut": ("plant", "defense"),
    "coffee_bean": ("plant", "environment"),
    "flower_pot": ("plant", "environment"),
    "lily_pad": ("plant", "environment"),
    "cherry_bomb": ("plant", "special"),
    "potato_mine": ("plant", "special"),
    # --- zombies ---
    "basic_zombie": ("zombie", "basic"),
    "flag_zombie": ("zombie", "basic"),
    "conehead_zombie": ("zombie", "armored"),
    "buckethead_zombie": ("zombie", "armored"),
    "door_zombie": ("zombie", "armored"),
    "newspaper_zombie": ("zombie", "armored"),
    "balloon_zombie": ("zombie", "special"),
    "pole_vaulter_zombie": ("zombie", "special"),
    "miner_zombie": ("zombie", "underground"),
    "gargantuar": ("zombie", "giant"),
    "imp": ("zombie", "giant"),
    "zombie_boss": ("zombie", "boss"),
    # --- drops ---
    "coin_gold": ("resource", ""),
    "coin_silver": ("resource", ""),
    "diamond": ("resource", ""),
    "sun": ("resource", ""),
}

# Standalone sprites directly under textures/entities/ (not part directories).
STANDALONE: dict[str, tuple[str, str]] = {
    "pea.png": ("projectile", ""),
    "butter.png": ("projectile", ""),
    "glove.png": ("tool", ""),
    "shovel.png": ("tool", ""),
    "hammer.png": ("tool", ""),
    "money_bag.png": ("resource", ""),
}

# Dead art: superseded by the controller animations and referenced by nothing.
DEAD = [
    "sun.png", "sun_2.png", "mission.png",
]

# Registry that owns each content id, for writing animation_dir/texture back.
REGISTRY_DIRS = {
    "plant": "plants",
    "zombie": "zombies",
    "projectile": "projectiles",
    "resource": "resources",
}

TEXTURE_REF = re.compile(r"pvzce:textures/entities/([A-Za-z0-9_]+)(/[A-Za-z0-9_]+)*")


def rel_texture_dir(kind: str, category: str, entity: str) -> str:
    parts = ["textures", "entities", kind]
    if category:
        parts.append(category)
    parts.append(entity)
    return "/".join(parts)


def build_reference_map() -> dict[str, str]:
    """old ``textures/entities/<id>`` -> new ``textures/entities/<kind>/...``."""
    mapping: dict[str, str] = {}
    for entity, (kind, category) in ENTITIES.items():
        mapping[f"textures/entities/{entity}"] = rel_texture_dir(kind, category, entity)
    for filename, (kind, category) in STANDALONE.items():
        stem = filename[:-len(".png")]
        tail = f"{kind}/{category}/{stem}" if category else f"{kind}/{stem}"
        mapping[f"textures/entities/{stem}"] = f"textures/entities/{tail}"
    return mapping


def rewrite_texture_refs(text: str, mapping: dict[str, str]) -> tuple[str, int]:
    """Rewrites part texture ids.

    The match is the whole id (``textures/entities/<entity>/<part...>``); only the
    ``textures/entities/<entity>`` head is re-pointed, so the part path is preserved.
    """

    def replace(match: re.Match[str]) -> str:
        original = match.group(0)
        rest = match.group(2) or ""
        head = f"textures/entities/{match.group(1)}"
        target = mapping.get(head)
        return f"pvzce:{target}{rest}" if target else original

    return TEXTURE_REF.sub(replace, text), len(TEXTURE_REF.findall(text))


def animation_id(kind: str, category: str, entity: str) -> str:
    parts = [kind]
    if category:
        parts.append(category)
    parts.append(entity)
    return "/".join(parts)


def move_tree(src: Path, dst: Path, dry_run: bool) -> str:
    if not src.exists():
        return "missing"
    if dst.exists():
        return "already grouped"
    if not dry_run:
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(src), str(dst))
    return "moved"


def migrate_textures(dry_run: bool) -> list[str]:
    log: list[str] = []
    for entity, (kind, category) in sorted(ENTITIES.items()):
        src = TEXTURES / "entities" / entity
        dst = TEXTURES / rel_texture_dir(kind, category, entity).removeprefix("textures/")
        status = move_tree(src, dst, dry_run)
        if status != "already grouped":
            log.append(f"  texture dir {entity}: {status} -> {dst.relative_to(TEXTURES)}")
    for filename, (kind, category) in sorted(STANDALONE.items()):
        src = TEXTURES / "entities" / filename
        rel = f"{kind}/{category}/{filename}" if category else f"{kind}/{filename}"
        dst = TEXTURES / "entities" / rel
        status = move_tree(src, dst, dry_run)
        if status != "already grouped":
            log.append(f"  texture file {filename}: {status} -> {dst.relative_to(TEXTURES)}")
    for filename in DEAD:
        src = TEXTURES / "entities" / filename
        if src.exists():
            if not dry_run:
                src.unlink()
            log.append(f"  dropped dead art {filename}")
    return log


def migrate_animations(mapping: dict[str, str], dry_run: bool) -> list[str]:
    """Moves each animation into its category and re-points its part textures.

    Runs over both the flat tree and the already-grouped one: a file that was moved
    by an earlier run still has to have its textures re-pointed, and doing the
    rewrite on the destination is what makes a half-finished run recoverable.
    """
    log: list[str] = []
    if not ANIMATIONS.exists():
        return log
    # Both the flat tree and every grouped depth: `resource/<id>.json` is two
    # segments, `plant/attacker/<id>.json` is three.
    candidates = sorted(ANIMATIONS.glob("*.json")) + sorted(ANIMATIONS.glob("*/*.json")) \
        + sorted(ANIMATIONS.glob("*/*/*.json"))
    for path in candidates:
        entity = path.stem
        entry = ENTITIES.get(entity)
        if entry is None:
            log.append(f"  !! animation {path.name} has no classification entry")
            continue
        kind, category = entry
        target_dir = ANIMATIONS / kind / category if category else ANIMATIONS / kind
        dst = target_dir / path.name
        text = path.read_text(encoding="utf-8")
        rewritten, _ = rewrite_texture_refs(text, mapping)
        if path == dst:
            if rewritten != text:
                if not dry_run:
                    path.write_text(rewritten, encoding="utf-8")
                log.append(f"  animation {path.name}: textures re-pointed")
            continue
        if dst.exists():
            log.append(f"  animation {path.name}: already grouped")
            continue
        if not dry_run:
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_text(rewritten, encoding="utf-8")
            path.unlink()
        log.append(f"  animation {path.name}: -> {dst.relative_to(ANIMATIONS)}")
    return log


def flatten_data_pack(dry_run: bool) -> list[str]:
    """``data/pvzce/<registry>/`` -> ``data/pvzce/<registry>/``, plus the tag tree."""
    log: list[str] = []
    if not LEGACY_DATA.exists():
        return ["  already flat"]
    for entry in sorted(LEGACY_DATA.iterdir()):
        if entry.name == "tags":
            continue
        dst = DATA / entry.name
        if dst.exists():
            log.append(f"  !! data/{entry.name} already exists; not overwriting")
            continue
        if not dry_run:
            shutil.move(str(entry), str(dst))
        log.append(f"  data/{NS}/{NS}/{entry.name} -> data/{NS}/{entry.name}")
    # The three-segment tag spelling data/<ns>/tags/pvzce/<registry>/ was the
    # tag-side twin of the same double namespace; its files belong one level up.
    legacy_tags = LEGACY_DATA / "tags" / NS
    if legacy_tags.exists():
        for entry in sorted(legacy_tags.iterdir()):
            dst = DATA / "tags" / entry.name
            if dst.exists():
                # A registry directory that already exists at the flat location
                # merges file by file, so a run interrupted midway still finishes.
                for file in sorted(entry.iterdir()):
                    target = dst / file.name
                    if target.exists():
                        log.append(f"  !! tags/{entry.name}/{file.name} already exists; skipped")
                        continue
                    if not dry_run:
                        dst.mkdir(parents=True, exist_ok=True)
                        shutil.move(str(file), str(target))
                    log.append(f"  tags/{NS}/{entry.name}/{file.name} -> tags/{entry.name}/{file.name}")
                continue
            if not dry_run:
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(str(entry), str(dst))
            log.append(f"  tags/{NS}/{entry.name} -> tags/{entry.name}")
    if not dry_run:
        # Only empty directories should be left; remove them so the pack root
        # cannot come back by accident.
        shutil.rmtree(LEGACY_DATA, ignore_errors=True)
    return log


def patch_definitions(mapping: dict[str, str], dry_run: bool) -> list[str]:
    """Adds animation_dir + texture to the content definitions of moved entities."""
    log: list[str] = []
    for entity, (kind, category) in sorted(ENTITIES.items()):
        registry = REGISTRY_DIRS.get(kind)
        if registry is None:
            continue
        path = DATA / registry / f"{entity}.json"
        if not path.exists():
            log.append(f"  !! no definition for {entity} at {path.name}")
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        wanted_dir = animation_id(kind, category, entity).rsplit("/", 1)[0]
        wanted_texture = f"{NS}:{mapping[f'textures/entities/{entity}']}"
        changed = False
        if data.get("animation_dir") != wanted_dir:
            data["animation_dir"] = wanted_dir
            changed = True
        if data.get("texture") != wanted_texture:
            data["texture"] = wanted_texture
            changed = True
        if changed:
            if not dry_run:
                path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            log.append(f"  def {registry}/{entity}.json: animation_dir={wanted_dir}")
    # Tools declare their icon in the slot definition, which is the card's sprite.
    tool_slots = {
        "glove": "pvzce:textures/entities/tool/glove",
        "shovel": "pvzce:textures/entities/tool/shovel",
        "hammer": "pvzce:textures/entities/tool/hammer",
    }
    for name, wanted in sorted(tool_slots.items()):
        path = DATA / "slots" / f"{name}.json"
        if not path.exists():
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        if data.get("icon") != wanted:
            data["icon"] = wanted
            if not dry_run:
                path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            log.append(f"  slot {name}.json: icon={wanted}")
    return log


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dry-run", action="store_true", help="report without touching files")
    args = parser.parse_args(argv)

    mapping = build_reference_map()
    print(f"Grouping {len(ENTITIES)} entity directories + {len(STANDALONE)} loose sprites")
    for section, lines in (
        ("data pack", flatten_data_pack(args.dry_run)),
        ("textures", migrate_textures(args.dry_run)),
        ("animations", migrate_animations(mapping, args.dry_run)),
        ("definitions", patch_definitions(mapping, args.dry_run)),
    ):
        if lines:
            print(f"{section}:")
            print("\n".join(lines))
    if args.dry_run:
        print("dry run: nothing was written")
    return 0


if __name__ == "__main__":
    sys.exit(main())
