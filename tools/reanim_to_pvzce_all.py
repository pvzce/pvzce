#!/usr/bin/env python3
"""Convert the needed PvZ1 reanim files into PVZCE 2D-controller JSON.

This script reuses the low-level parsing/conversion helpers in
``reanim_to_pvzce.py`` and drives them with a per-entity configuration table.
It generates one ``assets/<ns>/animations/<group>/<entity>.json`` per configured
entity plus the referenced part PNGs under
``assets/<ns>/textures/entities/<group>/<entity>/``, where ``<group>`` is the
entity's ``<kind>/<category>`` (for example ``plant/attacker``). A content
definition points at the animation with ``"animation_dir": "<group>"``; the part
textures are named by the controller itself, so they need no declaration.

Run from the repository root:

    python3 tools/reanim_to_pvzce_all.py

Optional:

    python3 tools/reanim_to_pvzce_all.py --entity sunflower
    python3 tools/reanim_to_pvzce_all.py --input-dir refer/anim
"""

from __future__ import annotations

import argparse
import difflib
import importlib.util
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

REPO_ROOT = Path(__file__).resolve().parents[1]
CORE_PATH = Path(__file__).with_name("reanim_to_pvzce.py")


def load_core():
    spec = importlib.util.spec_from_file_location("reanim_core", CORE_PATH)
    if spec is None or spec.loader is None:
        raise SystemExit(f"Could not load core converter from {CORE_PATH}")
    module = importlib.util.module_from_spec(spec)
    sys.modules["reanim_core"] = module
    spec.loader.exec_module(module)
    return module


core = load_core()

DEFAULT_INPUT_DIR = REPO_ROOT / "refer" / "anim"
DEFAULT_RESOURCES = REPO_ROOT / "pvzce-game" / "src" / "main" / "resources"
DEFAULT_NAMESPACE = "pvzce"

# Target visual boxes in world cells, matching the existing flat sprites.
PLANT_BOX = [0.76, 0.76]
ZOMBIE_BOX = [0.70, 0.95]

# Accessory image references inside the combined Zombie.reanim master file.
# Basic/bucket/door zombies share the base body tracks; the accessory tracks
# are filtered per entity so the wrong hat/door never appears.
ZOMBIE_BASE_EXCLUDE = (
    r"(FLAGHAND|SCREENDOOR|DUCKYTUBE|WHITEWATER|SNORKLE|CONE|BUCKET)"
)
ZOMBIE_BUCKET_EXCLUDE = r"(FLAGHAND|SCREENDOOR|DUCKYTUBE|WHITEWATER|SNORKLE|CONE)"
ZOMBIE_DOOR_EXCLUDE = r"(FLAGHAND|DUCKYTUBE|WHITEWATER|SNORKLE|CONE|BUCKET)"
# One accessory kept, the rest dropped: the same master file holds every zombie hat.
ZOMBIE_CONE_EXCLUDE = r"(FLAGHAND|SCREENDOOR|DUCKYTUBE|WHITEWATER|SNORKLE|BUCKET)"
ZOMBIE_FLAG_EXCLUDE = r"(SCREENDOOR|DUCKYTUBE|WHITEWATER|SNORKLE|CONE|BUCKET)"


@dataclass(frozen=True)
class EntityConfig:
    # Content id leaf. The id never carries the category, so grouping the art cannot
    # rename content and invalidate levels, tags or saves.
    output: str
    # Which subdirectory the art belongs in, as "<kind>/<category>" - the same
    # classification the shipped pack uses (see assets/pvzce/animations/). Art is found
    # by path and the path is declared by the content definition (animation_dir), so
    # this is presentation only.
    group: str
    reanim: str
    target_box: Tuple[float, float]
    animations: Dict[str, Dict[str, object]]
    exclude_image_regex: Optional[str] = None
    include_image_regex: Optional[str] = None
    # Drops render pieces by *track* name. Needed when one sprite backs several logic
    # parts - GatlingPea.reanim draws all four barrels from a single image, so which
    # barrels to keep can only be decided by the track that drives them.
    exclude_track_regex: Optional[str] = None
    force_visible_bones: Optional[str] = None
    force_hidden_bones: Optional[str] = None
    fit_height_only: bool = True
    # Accessories the original keeps in their own reanim file: the flag zombie's pole
    # and flag live in Zombie_flagpole.reanim, authored in the *zombie's* model space
    # and at the same fps, so their bones can simply be appended to this controller.
    # Scaling and offsets come from the main reanim's bounding box, which is what keeps
    # them lined up with the hand that holds them.
    extra_reanims: Tuple[str, ...] = ()
    # The bone an extra reanim's parts are welded to. Controller bones are all children
    # of the model root with absolute keyframes, so an extra that is *attached* in the
    # original (a pole in a hand) would otherwise stand still while its host walked and
    # hang in the air after the host died; naming the host bakes its per-frame
    # translation into the extra's keys, which is rigid attachment in everything but
    # rotation.
    extra_bone_host: Optional[str] = None


ENTITY_CONFIGS: List[EntityConfig] = [
    # ------------------------------------------------------------------
    # Resource drops
    # ------------------------------------------------------------------
    EntityConfig(
        output="sun",
        group="resource",
        reanim="Sun.reanim",
        target_box=(0.56, 0.56),
        animations={
            "idle": {"range": "all", "loop": True},
            "landed": {"range": "all", "loop": True},
        },
    ),
    # The coin and diamond reanims ship with refer/im5 rather than refer/anim, so
    # these are generated with an explicit --input-dir. Their target boxes cover the
    # glow sprite, which puts the coin face itself at roughly half the box.
    #
    # Four denominations, because the original's are four different objects with four
    # different values (silver 10, gold 50, diamond 1000, money bag 250); a single
    # "coin" sprite could not tell the player which one they just picked up.
    #
    # The glow and the diamond's shine are excluded on purpose. The original blends
    # them additively; this renderer has no additive mode, so a soft radial gradient
    # drawn as an ordinary quad becomes a large opaque disc that swallows the coin.
    EntityConfig(
        output="coin_silver",
        group="resource",
        reanim="Coin_silver.reanim",
        target_box=(0.42, 0.42),
        exclude_image_regex=r"COINGLOW",
        animations={
            "idle": {"range": "all", "loop": True},
            "landed": {"range": "all", "loop": True},
        },
    ),
    EntityConfig(
        output="coin_gold",
        group="resource",
        reanim="Coin_gold.reanim",
        target_box=(0.42, 0.42),
        exclude_image_regex=r"COINGLOW",
        animations={
            "idle": {"range": "all", "loop": True},
            "landed": {"range": "all", "loop": True},
        },
    ),
    EntityConfig(
        output="diamond",
        group="resource",
        reanim="Diamond.reanim",
        target_box=(0.46, 0.46),
        exclude_image_regex=r"DIAMOND_SHINE",
        animations={
            "idle": {"range": "all", "loop": True},
            "landed": {"range": "all", "loop": True},
        },
    ),
    # ------------------------------------------------------------------
    # Plants
    # ------------------------------------------------------------------
    EntityConfig(
        output="sunflower",
        group="plant/producer",
        reanim="SunFlower.reanim",
        target_box=PLANT_BOX,
        animations={
            "idle": {"mask": "anim_idle", "loop": True},
            "produce": {"mask": "anim_idle", "loop": True, "transition": 0.1},
        },
    ),
    EntityConfig(
        output="cherry_bomb",
        group="plant/special",
        reanim="CherryBomb.reanim",
        target_box=PLANT_BOX,
        animations={
            "idle": {"mask": "anim_idle", "loop": True},
            "explode": {
                "mask": "anim_explode",
                "loop": False,
                "on_end": "hold",
                "transition": 0.05,
                "force_visible_hidden": True,
            },
        },
    ),
    EntityConfig(
        output="wall_nut",
        group="plant/defense",
        reanim="Wallnut.reanim",
        target_box=PLANT_BOX,
        animations={"idle": {"mask": "anim_idle", "loop": True}},
    ),
    EntityConfig(
        output="potato_mine",
        group="plant/special",
        reanim="PotatoMine.reanim",
        target_box=PLANT_BOX,
        animations={
            # Buried while the arm-up countdown runs.
            "idle": {"mask": "anim_idle", "loop": True},
            "grow": {"mask": "anim_idle", "loop": True, "transition": 0.1},
            # Server switches to "armed" when the countdown ends: play the
            # emergence clip once, then hold the armed loop.
            "armed": {
                "mask": "anim_rise",
                "loop": False,
                "on_end": "next",
                "next": "armed_loop",
                "transition": 0.1,
                "force_visible_hidden": True,
            },
            "armed_loop": {"mask": "anim_armed", "loop": True, "transition": 0.1},
            "explode": {
                "mask": "anim_mashed",
                "loop": False,
                "on_end": "hold",
                "transition": 0.05,
                "force_visible_hidden": True,
            },
        },
    ),
    EntityConfig(
        output="chomper",
        group="plant/attacker",
        reanim="Chomper.reanim",
        target_box=PLANT_BOX,
        exclude_image_regex=r"ZOMBIE",
        animations={
            "idle": {"mask": "anim_idle", "loop": True},
            "chew": {
                "mask": "anim_chew",
                "loop": False,
                "on_end": "idle",
                "transition": 0.1,
                "force_visible_hidden": True,
            },
            "bite": {
                "mask": "anim_bite",
                "loop": False,
                "on_end": "idle",
                "transition": 0.1,
                "force_visible_hidden": True,
            },
            "swallow": {
                "mask": "anim_swallow",
                "loop": False,
                "on_end": "idle",
                "transition": 0.1,
                "force_visible_hidden": True,
            },
        },
    ),
    EntityConfig(
        output="kernel_pult",
        group="plant/attacker",
        reanim="Cornpult.reanim",
        target_box=PLANT_BOX,
        animations={
            "idle": {"mask": "anim_full_idle", "loop": True, "transition": 0.1},
            "shoot": {
                "mask": "anim_shooting",
                "loop": False,
                "on_end": "idle",
                "transition": 0.1,
                "force_visible_hidden": True,
                "force_visible_exclude_prefixes": ["blink"],
            },
        },
    ),
    EntityConfig(
        output="marigold",
        group="plant/producer",
        reanim="Marigold.reanim",
        target_box=PLANT_BOX,
        animations={
            "idle": {"mask": "anim_idle", "loop": True},
            "produce": {"mask": "anim_idle", "loop": True, "transition": 0.1},
        },
    ),
    EntityConfig(
        output="lily_pad",
        group="plant/environment",
        reanim="LilyPad.reanim",
        target_box=PLANT_BOX,
        animations={"idle": {"mask": "anim_idle", "loop": True}},
    ),
    EntityConfig(
        output="flower_pot",
        group="plant/environment",
        reanim="Pot.reanim",
        target_box=PLANT_BOX,
        animations={"idle": {"mask": "anim_idle", "loop": True}},
    ),
    EntityConfig(
        output="coffee_bean",
        group="plant/environment",
        reanim="Coffeebean.reanim",
        target_box=PLANT_BOX,
        animations={
            "idle": {"mask": "anim_idle", "loop": True},
            "produce": {
                "mask": "anim_twitch",
                "loop": False,
                "on_end": "idle",
                "transition": 0.05,
            },
        },
    ),
    # ------------------------------------------------------------------
    # The rest of the shooter line
    #
    # All of them reuse the same two-mask shape as the pea shooter: an idle loop and a
    # one-shot shoot that returns to it. The differences are numbers in the content
    # JSON (interval, shots, projectile), not art, so each of these is one entry here
    # and one data file there.
    # ------------------------------------------------------------------
    EntityConfig(
        output="snow_pea",
        group="plant/attacker",
        reanim="SnowPea.reanim",
        target_box=PLANT_BOX,
        animations={
            "idle": {"mask": "anim_full_idle", "loop": True, "transition": 0.1},
            "shoot": {
                "mask": "anim_shooting",
                "loop": False,
                "on_end": "idle",
                "transition": 0.1,
                "force_visible_hidden": True,
                "force_visible_exclude_prefixes": ["blink"],
            },
        },
    ),
    EntityConfig(
        output="repeater",
        group="plant/attacker",
        reanim="GatlingPea.reanim",
        target_box=PLANT_BOX,
        # GatlingPea.reanim is the four-barrel model; the two-barrel repeater is the
        # same plant with the extra pair dropped, so the art is shared. The filter is on
        # the *track*, not the image: all four barrels are drawn from one sprite, so an
        # image filter either removed none of them or - with a broader "GATLING" pattern
        # - also removed the head, helmet and mouth, leaving a bare leaf.
        exclude_image_regex=r"GATLINGPEA_HELMET",
        exclude_track_regex=r"GATLINGPEA_BARREL[34]$",
        animations={
            "idle": {"mask": "anim_head_idle", "loop": True, "transition": 0.1,
                     "force_visible": r"(head|mouth|barrel|blink|eyebrow)"},
            "shoot": {
                "mask": "anim_shooting",
                "loop": False,
                "on_end": "idle",
                "transition": 0.1,
                "force_visible_hidden": True,
                "force_visible_exclude_prefixes": ["blink"],
                "force_visible": r"(head|mouth|barrel|blink|eyebrow)",
            },
        },
    ),
    EntityConfig(
        output="gatling_pea",
        group="plant/attacker",
        reanim="GatlingPea.reanim",
        target_box=PLANT_BOX,
        # This reanim splits "body only" (anim_idle, frames 4..28) from "with the
        # head and barrels" (anim_head_idle, 29..53); the head parts are invisible
        # during the first, so the plain anim_idle renders a headless plant.
        animations={
            "idle": {"mask": "anim_head_idle", "loop": True, "transition": 0.1,
                     "force_visible": r"(head|mouth|barrel|helmet|blink)"},
            "shoot": {
                "mask": "anim_shooting",
                "loop": False,
                "on_end": "idle",
                "transition": 0.1,
                "force_visible_hidden": True,
                "force_visible_exclude_prefixes": ["blink"],
                "force_visible": r"(head|mouth|barrel|helmet|blink)",
            },
        },
    ),
    EntityConfig(
        output="threepeater",
        group="plant/attacker",
        reanim="ThreePeater.reanim",
        target_box=PLANT_BOX,
        # The three heads fire in turn: head 1 is visible on frames 4..41, head 3 on
        # 45..82 and head 2 on 86..123, so no single frame range ever shows the plant.
        # The original draws them from three timelines at once; here the whole head
        # group is held visible, which is what the plant looks like standing still.
        force_visible_bones=r"(head|mouth|blink|face)",
        animations={
            "idle": {"mask": "anim_idle", "loop": True, "transition": 0.1},
            "shoot": {
                "mask": "anim_shooting1",
                "loop": False,
                "on_end": "idle",
                "transition": 0.1,
                "force_visible_hidden": True,
                "force_visible_exclude_prefixes": ["blink"],
            },
        },
    ),
    EntityConfig(
        output="split_pea",
        group="plant/attacker",
        reanim="SplitPea.reanim",
        target_box=PLANT_BOX,
        # Same split as the gatling pea: SplitPea_head is only drawn during the
        # head-idle segment.
        animations={
            "idle": {"mask": "anim_head_idle", "loop": True, "transition": 0.1,
                     "force_visible": r"(head|mouth|blink|eyebrow)"},
            "shoot": {
                "mask": "anim_shooting",
                "loop": False,
                "on_end": "idle",
                "transition": 0.1,
                "force_visible_hidden": True,
                "force_visible_exclude_prefixes": ["blink"],
                "force_visible": r"(head|mouth|blink|eyebrow)",
            },
        },
    ),
    EntityConfig(
        output="cactus",
        group="plant/attacker",
        reanim="Cactus.reanim",
        target_box=PLANT_BOX,
        animations={
            "idle": {"mask": "anim_idle", "loop": True, "transition": 0.1},
            # Cactus.reanim carries a separate raised-arms pair (anim_idlehigh /
            # anim_shootinghigh) for shooting at airborne zombies.
            "shoot": {
                "mask": "anim_shooting",
                "loop": False,
                "on_end": "idle",
                "transition": 0.1,
                "force_visible_hidden": True,
                "force_visible_exclude_prefixes": ["blink"],
            },
            "shoot_high": {
                "mask": "anim_shootinghigh",
                "loop": False,
                "on_end": "idle",
                "transition": 0.1,
                "force_visible_hidden": True,
                "force_visible_exclude_prefixes": ["blink"],
            },
        },
    ),
    # ------------------------------------------------------------------
    # The pult line: lobbed shots
    # ------------------------------------------------------------------
    EntityConfig(
        output="cabbage_pult",
        group="plant/attacker",
        reanim="Cabbagepult.reanim",
        target_box=PLANT_BOX,
        animations={
            "idle": {"mask": "anim_idle", "loop": True, "transition": 0.1},
            "shoot": {
                "mask": "anim_shooting",
                "loop": False,
                "on_end": "idle",
                "transition": 0.1,
                "force_visible_hidden": True,
                "force_visible_exclude_prefixes": ["blink"],
            },
        },
    ),
    EntityConfig(
        output="melon_pult",
        group="plant/attacker",
        reanim="Melonpult.reanim",
        target_box=PLANT_BOX,
        animations={
            "idle": {"mask": "anim_idle", "loop": True, "transition": 0.1},
            "shoot": {
                "mask": "anim_shooting",
                "loop": False,
                "on_end": "idle",
                "transition": 0.1,
                "force_visible_hidden": True,
                "force_visible_exclude_prefixes": ["blink"],
            },
        },
    ),
    EntityConfig(
        output="winter_melon",
        group="plant/attacker",
        reanim="WinterMelon.reanim",
        target_box=PLANT_BOX,
        animations={
            "idle": {"mask": "anim_idle", "loop": True, "transition": 0.1},
            "shoot": {
                "mask": "anim_shooting",
                "loop": False,
                "on_end": "idle",
                "transition": 0.1,
                "force_visible_hidden": True,
                "force_visible_exclude_prefixes": ["blink"],
            },
        },
    ),
    # ------------------------------------------------------------------
    # The ash line: one-shot detonations
    # ------------------------------------------------------------------
    EntityConfig(
        output="jalapeno",
        group="plant/special",
        reanim="Jalapeno.reanim",
        target_box=PLANT_BOX,
        animations={
            "idle": {"mask": "anim_idle", "loop": True},
            "explode": {
                "mask": "anim_explode",
                "loop": False,
                "on_end": "hold",
                "transition": 0.05,
                "force_visible_hidden": True,
            },
        },
    ),
    EntityConfig(
        output="doom_shroom",
        group="plant/special",
        reanim="DoomShroom.reanim",
        target_box=PLANT_BOX,
        animations={
            "idle": {"mask": "anim_idle", "loop": True},
            "explode": {
                "mask": "anim_explode",
                "loop": False,
                "on_end": "hold",
                "transition": 0.05,
                "force_visible_hidden": True,
            },
        },
    ),
    EntityConfig(
        output="squash",
        group="plant/special",
        reanim="Squash.reanim",
        target_box=PLANT_BOX,
        animations={
            "idle": {"mask": "anim_idle", "loop": True},
            # The squash winds up (eyes up) and comes down; both are one-shots that
            # end on the mashed frame.
            "grow": {"mask": "anim_jumpup", "loop": False, "on_end": "hold",
                     "transition": 0.05},
            "explode": {
                "mask": "anim_jumpdown",
                "loop": False,
                "on_end": "hold",
                "transition": 0.05,
                "force_visible_hidden": True,
            },
        },
    ),
    # ------------------------------------------------------------------
    # Zombies sharing Zombie.reanim
    # ------------------------------------------------------------------
    EntityConfig(
        output="basic_zombie",
        group="zombie/basic",
        reanim="Zombie.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        exclude_image_regex=ZOMBIE_BASE_EXCLUDE,
        animations={
            "idle": {"mask": "anim_idle", "loop": True},
            "walk": {"mask": "anim_walk", "loop": True},
            "eat": {"mask": "anim_eat", "loop": True},
            "hit": {"mask": "anim_idle2", "loop": True, "transition": 0.05},
            "death": {
                "mask": "anim_death",
                "loop": False,
                "on_end": "hold",
                "transition": 0.05,
            },
        },
    ),
    EntityConfig(
        output="buckethead_zombie",
        group="zombie/armored",
        reanim="Zombie.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        exclude_image_regex=ZOMBIE_BUCKET_EXCLUDE,
        animations={
            "idle": {"mask": "anim_idle", "loop": True},
            "walk": {"mask": "anim_walk", "loop": True},
            "eat": {"mask": "anim_eat", "loop": True},
            "hit": {"mask": "anim_idle2", "loop": True, "transition": 0.05},
            "angry": {"mask": "anim_idle2", "loop": False, "on_end": "idle", "transition": 0.05},
            "death": {
                "mask": "anim_death",
                "loop": False,
                "on_end": "hold",
                "transition": 0.05,
            },
        },
    ),
    EntityConfig(
        output="conehead_zombie",
        group="zombie/armored",
        reanim="Zombie.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        exclude_image_regex=ZOMBIE_CONE_EXCLUDE,
        animations={
            "idle": {"mask": "anim_idle", "loop": True},
            "walk": {"mask": "anim_walk", "loop": True},
            "eat": {"mask": "anim_eat", "loop": True},
            "hit": {"mask": "anim_idle2", "loop": True, "transition": 0.05},
            "angry": {"mask": "anim_idle2", "loop": False, "on_end": "idle", "transition": 0.05},
            "death": {
                "mask": "anim_death",
                "loop": False,
                "on_end": "hold",
                "transition": 0.05,
            },
        },
    ),
    # The flag zombie is a plain zombie holding a flag; the hand is in the master file
    # and the pole comes from its own, so this is the one config with an extra reanim.
    EntityConfig(
        output="flag_zombie",
        group="zombie/basic",
        reanim="Zombie.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        exclude_image_regex=ZOMBIE_FLAG_EXCLUDE,
        extra_reanims=("Zombie_flagpole.reanim",),
        extra_bone_host="flaghand",
        animations={
            "idle": {"mask": "anim_idle", "loop": True},
            "walk": {"mask": "anim_walk", "loop": True},
            "eat": {"mask": "anim_eat", "loop": True},
            "hit": {"mask": "anim_idle2", "loop": True, "transition": 0.05},
            "angry": {"mask": "anim_idle2", "loop": False, "on_end": "idle", "transition": 0.05},
            "death": {
                "mask": "anim_death",
                "loop": False,
                "on_end": "hold",
                "transition": 0.05,
            },
        },
    ),
    EntityConfig(
        output="door_zombie",
        group="zombie/armored",
        reanim="Zombie.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        exclude_image_regex=ZOMBIE_DOOR_EXCLUDE,
        animations={
            "idle": {"mask": "anim_idle", "loop": True},
            "walk": {"mask": "anim_walk", "loop": True},
            "eat": {"mask": "anim_eat", "loop": True},
            "hit": {"mask": "anim_idle2", "loop": True, "transition": 0.05},
            "angry": {"mask": "anim_idle2", "loop": False, "on_end": "idle", "transition": 0.05},
            "death": {
                "mask": "anim_death",
                "loop": False,
                "on_end": "hold",
                "transition": 0.05,
            },
        },
    ),
    # ------------------------------------------------------------------
    # Dedicated zombie reanim files
    # ------------------------------------------------------------------
    EntityConfig(
        output="newspaper_zombie",
        group="zombie/armored",
        reanim="Zombie_paper.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        animations={
            "idle": {"mask": "anim_idle", "loop": True},
            "walk": {"mask": "anim_walk", "loop": True},
            "eat": {"mask": "anim_eat", "loop": True},
            "hit": {"mask": "anim_gasp", "loop": False, "on_end": "idle", "transition": 0.05},
            "angry": {"mask": "anim_gasp", "loop": False, "on_end": "idle", "transition": 0.05},
            "death": {"mask": "anim_death", "loop": False, "on_end": "hold", "transition": 0.05},
        },
    ),
    EntityConfig(
        output="pole_vaulter_zombie",
        group="zombie/special",
        reanim="Zombie_polevaulter.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        animations={
            "idle": {"mask": "anim_idle", "loop": True},
            "walk": {"mask": "anim_walk", "loop": True},
            "eat": {"mask": "anim_eat", "loop": True},
            "jump": {"mask": "anim_jump", "loop": False, "on_end": "walk", "transition": 0.05},
            "hit": {"mask": "anim_idle", "loop": True, "transition": 0.05},
            "death": {"mask": "anim_death", "loop": False, "on_end": "hold", "transition": 0.05},
        },
    ),
    EntityConfig(
        output="balloon_zombie",
        group="zombie/special",
        reanim="Zombie_balloon.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        animations={
            "idle": {"mask": "anim_idle", "loop": True},
            "fly": {"mask": "anim_swing", "loop": True, "transition": 0.1},
            "walk": {"mask": "anim_walk", "loop": True},
            "eat": {"mask": "anim_eat", "loop": True},
            "fall": {"mask": "anim_pop", "loop": False, "on_end": "walk", "transition": 0.05},
            "hit": {"mask": "anim_idle", "loop": True, "transition": 0.05},
            "death": {"mask": "anim_death", "loop": False, "on_end": "hold", "transition": 0.05},
        },
    ),
    EntityConfig(
        output="miner_zombie",
        group="zombie/underground",
        reanim="Zombie_digger.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        animations={
            "idle": {"mask": "anim_idle", "loop": True},
            "walk": {"mask": "anim_walk", "loop": True},
            "eat": {"mask": "anim_eat", "loop": True},
            "dig": {"mask": "anim_dig", "loop": True, "transition": 0.1},
            "dig_exit": {"mask": "anim_landing", "loop": False, "on_end": "walk", "transition": 0.1},
            "hit": {"mask": "anim_dizzy", "loop": False, "on_end": "idle", "transition": 0.05},
            "death": {"mask": "anim_death", "loop": False, "on_end": "hold", "transition": 0.05},
        },
    ),
    EntityConfig(
        output="gargantuar",
        group="zombie/giant",
        reanim="Zombie_gargantuar.reanim",
        target_box=(1.10, 1.55),
        fit_height_only=False,
        animations={
            "idle": {"mask": "anim_idle", "loop": True},
            "walk": {"mask": "anim_walk", "loop": True},
            "eat": {"mask": "anim_smash", "loop": True},
            "hammer": {
                "mask": "anim_smash",
                "loop": False,
                "on_end": "idle",
                "transition": 0.1,
            },
            "hit": {"mask": "anim_idle", "loop": True, "transition": 0.05},
            "death": {"mask": "anim_death", "loop": False, "on_end": "hold", "transition": 0.05},
        },
    ),
    EntityConfig(
        output="zombie_boss",
        group="zombie/boss",
        reanim="Zombie_boss.reanim",
        target_box=(1.40, 2.00),
        fit_height_only=False,
        animations={
            "idle": {"mask": "anim_idle", "loop": True},
            "walk": {"mask": "anim_RV_1", "loop": True},
            "eat": {"mask": "anim_stomp_1", "loop": True},
            "hit": {"mask": "anim_idle", "loop": True, "transition": 0.05},
            "death": {"mask": "anim_death", "loop": False, "on_end": "hold", "transition": 0.05},
        },
    ),
    # ------------------------------------------------------------------
    # Tools
    #
    # A tool card needs one static sprite, and Hammer.reanim is where the original's is.
    # It gets a single idle clip so the generator has something to write; the card draws
    # the first model part, not the clip.
    # ------------------------------------------------------------------
    EntityConfig(
        output="hammer",
        group="tool",
        reanim="Hammer.reanim",
        target_box=(0.5, 0.5),
        animations={"idle": {"mask": "anim_whack_zombie", "loop": True}},
    ),
    EntityConfig(
        output="imp",
        group="zombie/giant",
        reanim="Zombie_imp.reanim",
        target_box=(0.45, 0.60),
        fit_height_only=False,
        animations={
            "idle": {"mask": "anim_walk", "loop": True},
            "walk": {"mask": "anim_walk", "loop": True},
            "eat": {"mask": "anim_eat", "loop": True},
            "thrown": {"mask": "anim_thrown", "loop": False, "on_end": "land", "transition": 0.05},
            "land": {"mask": "anim_land", "loop": False, "on_end": "walk", "transition": 0.05},
            "hit": {"mask": "anim_walk", "loop": True, "transition": 0.05},
            "death": {"mask": "anim_death", "loop": False, "on_end": "hold", "transition": 0.05},
        },
    ),
]


def bone_name_for(image_ref: str, input_path: Path) -> str:
    name = image_ref
    if name.startswith("IMAGE_REANIM_"):
        name = name[len("IMAGE_REANIM_") :]
    name = core.snake_case(name)
    name = core.remove_entity_prefix(name, input_path)
    name = re.sub(r"([a-z])([0-9]+)$", r"\1_\2", name)
    name = re.sub(r"_+", "_", name).strip("_")
    return name or "part"


def discover_assets_all(input_dir: Path, input_path: Path, refs: Sequence[str]) -> Dict[str, core.ImageAsset]:
    """Map refs across every PNG in the directory, not just the entity prefix.

    Several PvZ reanims embed shared base tracks from another entity (for
    example SunFlower.reanim contains PeaShooter body tracks).  The generic
    converter therefore scans the whole directory and matches by normalized
    image key.
    """

    candidates = sorted(input_dir.glob("*.png"))
    if not candidates:
        raise SystemExit(f"No PNG files found in {input_dir}")

    by_key: Dict[str, List[Path]] = {}
    for candidate in candidates:
        key = core.image_match_key(candidate.stem, input_path)
        by_key.setdefault(key, []).append(candidate)

    assets: Dict[str, core.ImageAsset] = {}
    for ref in sorted(set(refs)):
        key = core.image_match_key(ref, input_path)
        paths = by_key.get(key)
        if not paths and ref in core.IMAGE_REF_ALIASES:
            alias = core.IMAGE_REF_ALIASES[ref]
            paths = by_key.get(core.image_match_key(Path(alias).stem, input_path))
        if not paths:
            close = difflib.get_close_matches(key, list(by_key.keys()), n=1, cutoff=0.55)
            if close:
                paths = by_key[close[0]]
        if not paths:
            raise SystemExit(f"Could not map image ref {ref!r} (key {key!r}) to a PNG in {input_dir}")
        source = paths[0]
        width, height = core.read_png_size(source)
        assets[ref] = core.ImageAsset(ref=ref, source=source, width=width, height=height)
    return assets


def filter_pieces(pieces: Sequence[core.RenderPiece], config: EntityConfig) -> List[core.RenderPiece]:
    result: List[core.RenderPiece] = []
    include = re.compile(config.include_image_regex, re.IGNORECASE) if config.include_image_regex else None
    exclude = re.compile(config.exclude_image_regex, re.IGNORECASE) if config.exclude_image_regex else None
    exclude_track = (re.compile(config.exclude_track_regex, re.IGNORECASE)
                     if config.exclude_track_regex else None)
    for piece in pieces:
        ref = piece.image_ref
        if include is not None and not include.search(ref):
            continue
        if exclude is not None and exclude.search(ref):
            continue
        if exclude_track is not None and exclude_track.search(piece.track_name):
            continue
        result.append(piece)
    return result


def rename_bones(bones: Sequence[core.Bone], input_path: Path) -> List[core.Bone]:
    used: Dict[str, int] = {}
    renamed: List[core.Bone] = []
    for bone in bones:
        base = bone_name_for(bone.asset.ref, input_path)
        count = used.get(base, 0)
        used[base] = count + 1
        name = base if count == 0 else f"{base}_{count + 1}"
        renamed.append(
            core.Bone(
                name=name,
                asset=bone.asset,
                states=bone.states,
                visibility=bone.visibility,
                order=bone.order,
                track_names=bone.track_names,
            )
        )
    return renamed


def pieces_to_bones(pieces: Sequence[core.RenderPiece], input_path: Path) -> List[core.Bone]:
    """Create one bone per render piece without cross-image name merging."""

    used: Dict[str, int] = {}
    bones: List[core.Bone] = []
    for piece in pieces:
        base = bone_name_for(piece.asset.ref, input_path)
        count = used.get(base, 0)
        used[base] = count + 1
        name = base if count == 0 else f"{base}_{count + 1}"
        bones.append(
            core.Bone(
                name=name,
                asset=piece.asset,
                states=list(piece.states),
                visibility=list(piece.visibility),
                order=piece.order,
                track_names=[piece.track_name],
            )
        )
    return bones


def reference_range(config: EntityConfig, tracks: Sequence[core.Track]) -> Tuple[int, int]:
    """Pick a stable animation range for model scale/bbox calculation.

    Using every exported range makes long actions (pole vault, boss RV)
    shrink the whole model.  Prefer a multi-frame idle clip, otherwise the
    longest exported clip.
    """

    candidates: List[Tuple[int, str, int, int]] = []
    for state, spec in config.animations.items():
        start, end = resolve_range(spec, tracks)
        candidates.append((end - start + 1, state, start, end))
    for count, state, start, end in candidates:
        if state == "idle" and count >= 2:
            return start, end
    return max(candidates)[2:]


def compute_bbox(bones: Sequence[core.Bone], ranges: Iterable[Tuple[int, int]]) -> core.BBox:
    min_x = float("inf")
    min_y = float("inf")
    max_x = float("-inf")
    max_y = float("-inf")
    for bone in bones:
        width = bone.asset.width
        height = bone.asset.height
        for start, end in ranges:
            for frame in range(start, end + 1):
                if frame >= len(bone.states) or not bone.visibility[frame]:
                    continue
                state = bone.states[frame]
                for x, y in core.transform_corners(state, width, height):
                    min_x = min(min_x, x)
                    min_y = min(min_y, y)
                    max_x = max(max_x, x)
                    max_y = max(max_y, y)
    if not all(isinstance(value, float) and value != float("inf") and value != float("-inf")
               for value in (min_x, min_y, max_x, max_y)):
        raise SystemExit("Could not compute a joint bounding box")
    return core.BBox(min_x=min_x, min_y=min_y, max_x=max_x, max_y=max_y)


def mask_range(tracks: Sequence[core.Track], mask_name: str) -> Tuple[int, int]:
    track = next((candidate for candidate in tracks if candidate.name == mask_name), None)
    if track is None:
        raise SystemExit(f"Mask track {mask_name!r} not found")
    ranges = core.visible_ranges(track)
    if not ranges:
        raise SystemExit(f"Mask track {mask_name!r} has no visible frames")
    return ranges[0]


def values_close(a: Sequence[float], b: Sequence[float], epsilon: float = 1.0E-4) -> bool:
    if len(a) != len(b):
        return False
    return all(abs(float(x) - float(y)) <= epsilon for x, y in zip(a, b))


def resolve_range(spec: Dict[str, object], tracks: Sequence[core.Track]) -> Tuple[int, int]:
    if "range" in spec:
        value = spec["range"]
        if value == "all":
            frame_count = max(len(track.frames) for track in tracks)
            return 0, frame_count - 1
        if isinstance(value, (list, tuple)) and len(value) == 2:
            return int(value[0]), int(value[1])
        raise SystemExit(f"Invalid range spec: {value!r}")
    return mask_range(tracks, str(spec["mask"]))


def build_animation(
    state: str,
    spec: Dict[str, object],
    bones: Sequence[core.Bone],
    tracks: Sequence[core.Track],
    fps: float,
    scale: float,
    bbox: core.BBox,
    config: EntityConfig,
    attached_bones: Optional[set] = None,
) -> Dict[str, object]:
    start, end = resolve_range(spec, tracks)
    frame_count = end - start + 1
    animation_bones: Dict[str, object] = {}
    force_visible = bool(spec.get("force_visible_hidden"))
    exclude_prefixes = tuple(str(value) for value in spec.get("force_visible_exclude_prefixes", ()))
    force_visible_re = re.compile(config.force_visible_bones, re.IGNORECASE) if config.force_visible_bones else None
    force_hidden_re = re.compile(config.force_hidden_bones, re.IGNORECASE) if config.force_hidden_bones else None
    # A per-clip "draw this bone whatever the source says". ``force_visible_hidden``
    # above only rescues bones that are hidden in *every* frame of the file, which does
    # not cover a reanim that splits one pose across several masks: the head parts of the
    # gatling pea are hidden in the body-idle segment but alive elsewhere, so they are
    # not permanently hidden and were still dropped.
    clip_visible_re = (re.compile(str(spec["force_visible"]), re.IGNORECASE)
                       if isinstance(spec.get("force_visible"), str) else None)

    host_bone = None
    if attached_bones and config.extra_bone_host:
        host_bone = next((b for b in bones
                          if b.name.lower() == str(config.extra_bone_host).lower()), None)
        if host_bone is None:
            raise SystemExit(
                f"{config.output}: extra_bone_host {config.extra_bone_host!r} is not a bone")

    def host_translation(frame: int) -> List[float]:
        state_data = host_bone.states[frame]
        center_x, center_y = core.model_center_px(state_data, host_bone.asset, bbox)
        return [center_x * scale, center_y * scale]

    for bone in bones:
        # An attached bone is drawn exactly when its host is: the original hangs the pole
        # off the hand, so the frame that hides the hand (the death clip) hides the flag
        # too rather than leaving it hovering over the collapsing body.
        source = host_bone if (attached_bones and bone.name in attached_bones) else bone
        permanently_hidden = not any(source.visibility[index] for index in range(start, end + 1))
        visibility: List[bool] = []
        for frame in range(start, end + 1):
            is_visible = bool(source.visibility[frame])
            if force_visible and permanently_hidden and not bone.name.startswith(exclude_prefixes):
                is_visible = True
            if force_visible_re is not None and force_visible_re.search(bone.name):
                is_visible = True
            if clip_visible_re is not None and clip_visible_re.search(bone.name):
                is_visible = True
            if force_hidden_re is not None and force_hidden_re.search(bone.name):
                is_visible = False
            visibility.append(is_visible)

        def key(frame: int) -> str:
            return core.format_time((frame - start) / fps)

        weld = host_translation if (attached_bones and bone.name in attached_bones) else None

        def translation_at(frame: int) -> List[float]:
            state_data = bone.states[frame]
            center_x, center_y = core.model_center_px(state_data, bone.asset, bbox)
            value = [center_x * scale, center_y * scale]
            if weld is not None:
                # Rigid attachment: follow the host's movement, keep our own pose.
                host_x, host_y = weld(frame)
                rest_x, rest_y = weld(start)
                value[0] += host_x - rest_x
                value[1] += host_y - rest_y
            return [core.round_float(value[0]), core.round_float(value[1])]

        def rotation_at(frame: int) -> List[float]:
            state_data = bone.states[frame]
            return [core.round_float(state_data.kx), core.round_float(state_data.ky), 0.0]

        def scale_at(frame: int) -> List[float]:
            state_data = bone.states[frame]
            return [core.round_float(state_data.sx), core.round_float(state_data.sy)]

        def vector_keys(value_at) -> Dict[str, List[float]]:
            keys: Dict[str, List[float]] = {}
            last_value: Optional[List[float]] = None
            last_key_frame: Optional[int] = None
            last_key_value: Optional[List[float]] = None
            for frame in range(start, end + 1):
                value = value_at(frame)
                if last_value is None or not values_close(value, last_value):
                    if last_key_frame is not None and last_key_frame < frame - 1 and last_key_value is not None:
                        keys[key(frame - 1)] = list(last_key_value)
                    keys[key(frame)] = list(value)
                    last_key_frame = frame
                    last_key_value = value
                last_value = value
            return keys

        def boolean_keys() -> Dict[str, bool]:
            keys: Dict[str, bool] = {}
            last_value: Optional[bool] = None
            for offset, frame in enumerate(range(start, end + 1)):
                value = visibility[offset]
                if last_value is None or value != last_value:
                    keys[key(frame)] = value
                last_value = value
            return keys

        animation_bones[bone.name] = {
            "translation": vector_keys(translation_at),
            "rotation": vector_keys(rotation_at),
            "scale": vector_keys(scale_at),
            "visible": boolean_keys(),
        }

    animation: Dict[str, object] = {
        "animation_length": round(frame_count / fps, 10),
        "loop": bool(spec.get("loop", False)),
    }
    if spec.get("on_end") is not None:
        animation["on_end"] = str(spec["on_end"])
    if spec.get("next") is not None:
        animation["next"] = str(spec["next"])
    animation["transition"] = float(spec.get("transition", 0.1))
    animation["bones"] = animation_bones
    animation["sound_effects"] = {}
    animation["particle_effects"] = {}
    animation["timeline"] = {}
    return animation


def build_controller_json(
    config: EntityConfig,
    bones: Sequence[core.Bone],
    tracks: Sequence[core.Track],
    fps: float,
    bbox: core.BBox,
    scale: float,
    attached_bones: Optional[set] = None,
) -> Dict[str, object]:
    model_bones: List[Dict[str, object]] = [
        {
            "name": "root",
            "parent": None,
            "pivot": [0.0, 0.0],
        }
    ]
    for index, bone in enumerate(bones):
        model_bones.append(
            {
                "name": bone.name,
                "parent": "root",
                "pivot": [0.0, 0.0],
                "parts": [
                    {
                        "texture": (f"{DEFAULT_NAMESPACE}:textures/entities/"
                                    f"{config.group}/{config.output}/{bone.name}"),
                        "uv": [0, 0, bone.asset.width, bone.asset.height],
                        "size": [
                            core.round_float(bone.asset.width * scale),
                            core.round_float(bone.asset.height * scale),
                        ],
                        "offset": [0.0, 0.0],
                        "z": index,
                    }
                ],
            }
        )

    animations: Dict[str, object] = {}
    for state, spec in config.animations.items():
        animations[state] = build_animation(state, spec, bones, tracks, fps, scale, bbox, config,
                                            attached_bones or set())

    return {
        "type": "controller",
        "model": {
            "size": [
                core.round_float(bbox.width * scale),
                core.round_float(bbox.height * scale),
            ],
            "bones": model_bones,
        },
        "animations": animations,
    }


def process_entity(
    config: EntityConfig,
    input_dir: Path,
    resources_dir: Path,
    verbose: bool = True,
) -> Dict[str, object]:
    input_path = input_dir / config.reanim
    if not input_path.is_file():
        raise SystemExit(f"Missing reanim file: {input_path}")

    fps, tracks = core.load_reanim(input_path)
    refs: List[str] = []
    for track in tracks:
        for state in track.frames:
            if state.image and state.image not in refs:
                refs.append(state.image)
    assets = discover_assets_all(input_dir, input_path, refs)
    frame_count = max(len(track.frames) for track in tracks)
    pieces = core.build_render_pieces(tracks, assets, input_path, frame_count)
    pieces = filter_pieces(pieces, config)
    if not pieces:
        raise SystemExit(f"{config.output}: no renderable tracks survived filtering")
    bones = pieces_to_bones(pieces, input_path)

    # The bounding box is the main reanim's, deliberately: the extras are authored in
    # that same space, so measuring them too would scale the host sprite down to fit a
    # flag that is supposed to stick out of it.
    bbox = compute_bbox(bones, [reference_range(config, tracks)])
    attached_bones: set = set()

    for extra_name in config.extra_reanims:
        extra_path = input_dir / extra_name
        if not extra_path.is_file():
            raise SystemExit(f"Missing extra reanim file: {extra_path}")
        _, extra_tracks = core.load_reanim(extra_path)
        extra_refs: List[str] = []
        for track in extra_tracks:
            for state in track.frames:
                if state.image and state.image not in extra_refs:
                    extra_refs.append(state.image)
        extra_assets = discover_assets_all(input_dir, extra_path, extra_refs)
        # frame_count is the main reanim's: core.build_render_pieces pads a shorter
        # track by repeating its last frame, which is how a 13-frame flag sway lives
        # inside a 504-frame zombie animation.
        extra_pieces = core.build_render_pieces(extra_tracks, extra_assets, extra_path, frame_count)
        extra_bones = pieces_to_bones(extra_pieces, extra_path)
        attached_bones.update(bone.name for bone in extra_bones)
        bones.extend(extra_bones)
    if config.fit_height_only:
        scale = config.target_box[1] / max(1.0, bbox.height)
    else:
        scale = min(
            config.target_box[0] / max(1.0, bbox.width),
            config.target_box[1] / max(1.0, bbox.height),
        )

    controller = build_controller_json(config, bones, tracks, fps, bbox, scale, attached_bones)

    json_path = (resources_dir / "assets" / DEFAULT_NAMESPACE / "animations"
                 / config.group / f"{config.output}.json")
    textures_dir = (resources_dir / "assets" / DEFAULT_NAMESPACE / "textures" / "entities"
                    / config.group / config.output)
    old_names = core.read_old_generated_texture_names(json_path)
    removed = core.clean_generated_textures(textures_dir, old_names)
    core.atomic_write_json(json_path, controller)
    written = core.copy_textures(bones, textures_dir)

    if verbose:
        print(
            f"{config.output}: {len(bones)} bones, {len(config.animations)} clips, "
            f"bbox={bbox.width:.1f}x{bbox.height:.1f}px scale={scale:.6f}, "
            f"{len(written)} textures"
        )
        if removed:
            print(f"  removed stale textures: {', '.join(removed)}")
    return {
        "output": config.output,
        "bones": len(bones),
        "clips": len(config.animations),
        "json": json_path,
        "textures": written,
    }


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--entity", action="append", default=None,
                        help="only process this output name (repeatable)")
    parser.add_argument("--input-dir", type=Path, default=DEFAULT_INPUT_DIR)
    parser.add_argument("--resources", type=Path, default=DEFAULT_RESOURCES)
    return parser.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    wanted = set(args.entity) if args.entity else None
    configs = [config for config in ENTITY_CONFIGS if wanted is None or config.output in wanted]
    if not configs:
        print("No matching entity configs", file=sys.stderr)
        return 2

    print(f"Converting {len(configs)} entities from {args.input_dir}")
    results = []
    for config in configs:
        try:
            results.append(process_entity(config, args.input_dir, args.resources))
        except SystemExit as exc:
            print(f"ERROR {config.output}: {exc}", file=sys.stderr)
            raise
    print()
    print(f"Generated {len(results)} entity animation(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
