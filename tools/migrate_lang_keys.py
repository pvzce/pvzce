#!/usr/bin/env python3
"""One-shot: move every content name in the built-in lang files onto the
``<registry>.<namespace>.<path>`` key shape.

Reads and rewrites ``assets/pvzce/lang/{zh_cn,en_us}.json`` in place. Every key that is not a
content id (the UI strings) is carried over untouched; a content name that the new
``<segment>`` files add later overwrites nothing.

Run once, from the repository root:

    python3 tools/migrate_lang_keys.py
"""
import glob
import json
import os
import sys

GAME = "pvzce-game/src/main/resources"
LANG = os.path.join(GAME, "assets/pvzce/lang")
DATA = os.path.join(GAME, "data/pvzce")

# registry key -> the data directory (or directories) its entries are loaded from
CATEGORIES = {
    "plant": ["plants"],
    "zombie": ["zombies"],
    "projectile": ["projectiles"],
    "resource": ["resources"],
    "slot": ["slots"],
    "tool": ["tools"],
    "scene_element": ["scene_elements"],
    "liquid": ["liquids"],
    "level_theme": ["level_themes"],
    "level_category": ["level_categories"],
    "damage_type": ["damage_types"],
    "dialogue_character": ["dialogue_characters"],
}

# The four keys that were not shaped like either a content id or a plain UI string.
ODD_KEYS = {
    "pvzce.level_theme.yard": "level_theme.pvzce.yard",
    "pvzce.level_category.adventure": "level_category.pvzce.adventure",
    "pvzce.level_category.endless": "level_category.pvzce.endless",
    "pvzce.level_category.minigame": "level_category.pvzce.minigame",
    "pvzce.level_category.survival": "level_category.pvzce.survival",
    # A level id spelled into a key: the id is pvzce:yard/survival/endless_pool, and its
    # display name is already the level file's own `name` field.
    "pvzce.yard/survival/endless_pool": "level.pvzce.yard.survival.endless_pool",
}


def content_ids():
    """Every (new key, old key) pair the shipped data pack defines."""
    pairs = []
    for category, dirs in CATEGORIES.items():
        for directory in dirs:
            for path in sorted(glob.glob(os.path.join(DATA, directory, "*.json"))):
                with open(path, encoding="utf-8") as handle:
                    data = json.load(handle)
                ident = data.get("id")
                if not ident:
                    continue
                namespace, _, name = ident.partition(":")
                pairs.append((f"{category}.{namespace}.{name}",
                              f"{namespace}.{name}", ident))
    return pairs


def migrate(locale, pairs):
    path = os.path.join(LANG, f"{locale}.json")
    with open(path, encoding="utf-8") as handle:
        strings = json.load(handle, object_pairs_hook=dict)

    out = {}
    used_old = set()
    for new_key, old_key, ident in pairs:
        value = strings.get(old_key)
        if value is None:
            # No name shipped under the flat key: the caller (the almanac segment files)
            # writes these; leaving them out here keeps this script from inventing text.
            continue
        out[new_key] = value
        used_old.add(old_key)

    for key, value in strings.items():
        if key in used_old or key in ODD_KEYS:
            continue
        out[key] = value

    for old_key, new_key in ODD_KEYS.items():
        if old_key in strings:
            out[new_key] = strings[old_key]

    ordered = {key: out[key] for key in sorted(out)}
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(ordered, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    return len(ordered)


def main():
    pairs = content_ids()
    for locale in ("zh_cn", "en_us"):
        count = migrate(locale, pairs)
        print(f"{locale}: {count} keys")
    return 0


if __name__ == "__main__":
    sys.exit(main())
