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
import math
import re
import sys
from dataclasses import dataclass, field
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
# Plants the original draws small, fitted to their own height rather than to the 0.76 a
# full-sized plant gets.
#
# The box is what makes a plant's size absolute: fitting *every* plant to the same height
# throws away the only thing that said the puff-shroom is a mushroom and not a shrub. One
# reference plant (sunflower, 74.7px) is 0.76 cells, so 1px is 0.010175 cells and the box
# below is that factor applied to the reanim's own bounding box - the original's
# proportions, not a guess. A plant that *grows* is measured by its small form and the
# grown form then comes out at its true relative size, because both are authored in one
# reanim space (see sun_shroom).
SMALL_PLANT_BOX = [0.38, 0.38]

# Accessory image references inside the combined Zombie.reanim master file.
# Basic/bucket/door zombies share the base body tracks; the accessory tracks
# are filtered per entity so the wrong hat/door never appears.
#
# MUSTACHE is excluded for every zombie: the original only draws it in its
# moustache easter egg, while the reanim's own track keeps it visible for the
# whole idle loop. Dropping the image here is what removes the bone.
ZOMBIE_BASE_EXCLUDE = (
    r"(FLAGHAND|SCREENDOOR|DUCKYTUBE|WHITEWATER|SNORKLE|CONE|BUCKET|MUSTACHE)"
)
ZOMBIE_BUCKET_EXCLUDE = r"(FLAGHAND|SCREENDOOR|DUCKYTUBE|WHITEWATER|SNORKLE|CONE|MUSTACHE)"
ZOMBIE_DOOR_EXCLUDE = r"(FLAGHAND|DUCKYTUBE|WHITEWATER|SNORKLE|CONE|BUCKET|MUSTACHE)"
# One accessory kept, the rest dropped: the same master file holds every zombie hat.
ZOMBIE_CONE_EXCLUDE = r"(FLAGHAND|SCREENDOOR|DUCKYTUBE|WHITEWATER|SNORKLE|BUCKET|MUSTACHE)"
ZOMBIE_FLAG_EXCLUDE = r"(SCREENDOOR|DUCKYTUBE|WHITEWATER|SNORKLE|CONE|BUCKET|MUSTACHE)"


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
    # Bones left out of the box the model is *sized* by, as a regex on the bone name.
    #
    # The box is what ``target_box`` is fitted to, and it is also the origin every bone
    # translation is measured from, so leaving a hat out of it makes the model as tall as
    # the *body*: without this, a Conehead is fitted to 0.95 cells including its cone and
    # therefore stands 15% shorter than a bare-headed zombie - the hat costs the body its
    # size. Feet do not move (a hat never extends below them), so the only other effect is
    # the horizontal origin, which shifts by however much the hat was off-centre.
    measure_exclude_regex: Optional[str] = None
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
    # Image references whose part is blended additively: light rather than paint.
    #
    # The reanim format has no blend flag - PopCap's renderer decided it in code, per
    # sprite - so the only place this knowledge can live is a table like this one. The
    # original blends its glows this way and draws everything else source-over, and
    # drawing a glow source-over is visibly wrong: a soft radial gradient becomes a flat
    # opaque disc that hides what is underneath. That is why the coin's glow used to be
    # excluded from the conversion entirely.
    #
    # A regex over the image reference, because a reanim names its glows by number
    # (``IMAGE_REANIM_SUN2``/``SUN3``) and there is no other pattern to key on.
    additive_images: Optional[str] = None
    # Extra sprites for one bone, as its damage states: ``{host bone: ((new bone, png), ...)}``.
    #
    # The original swaps a worn cone/bucket/flag for a more damaged drawing in code, so
    # those PNGs are referenced by no track and the converter would never see them. Each
    # entry here becomes one more bone in the model - the host's animation keys, its own
    # texture, and ``visible: false`` in every clip - and the client shows exactly one
    # member of the family from the zombie's synced state (see ``EquipmentDef``).
    # Authoring them invisible is what keeps every existing clip untouched.
    damage_states: Dict[str, Tuple[Tuple[str, str], ...]] = field(default_factory=dict)


# ---------------------------------------------------------------------------
# Playback rates and locomotion references
# ---------------------------------------------------------------------------
#
# A reanim file carries one frame rate, but the original game then played individual
# actions at speeds of its own. These are the numbers for the conversions that have one;
# a clip that states neither plays at authoring speed, which is what every other clip in
# the shipped set does today and is why leaving them out changes nothing.
#
#   * eat   - the original bites three times a second (180 ticks of mEatAnimDuration for
#             a three-bite sequence) while anim_eat holds two bites in 40 frames, so the
#             cycle runs 1.5x. At 1x a zombie chewed a plant to death long before its jaw
#             finished a cycle.
#   * death - the original plays its death sequences at 24-30 fps rather than the file's
#             12: Zombie::PlayDeathAnim picks a random rate in that band. 2.5x puts the
#             39-frame sequence at 1.3 s, the fast end of that band, which keeps the
#             drawn death shorter than the 6 s corpse it leaves behind.
#   * walk  - see ZOMBIE_WALK_REFERENCE_SPEED: the cycle duration is only meaningful
#             relative to a ground speed, and the runtime does the division.
ZOMBIE_EAT_RATE = 1.5
ZOMBIE_DEATH_RATE = 2.5
# What the zombie walk art assumes it is travelling at, in cells per second. The cycle is
# 47 frames at 12fps drawn as one stride, and every zombie that shares this art walks at
# 0.23 cells/s (the plain, flag and imp zombies) or 0.18 (the armoured ones). 0.23 is the
# reference so the plain zombie plays at exactly 1x and the armoured ones slow to 0.78x
# and stop sliding their feet.
ZOMBIE_WALK_REFERENCE_SPEED = 0.23

# All three of the original's death sequences exist in Zombie.reanim. Which one a zombie
# plays is the server's choice (see EntityAnimations.DEATH2 / DEATH_SUPERLONG), so all
# three are exported; waterdeath is the one for a body that drowns.
ZOMBIE_DEATH_CLIPS: Dict[str, Dict[str, object]] = {
    "death": {"mask": "anim_death", "loop": False, "on_end": "hold",
              "transition": 0.05, "rate": ZOMBIE_DEATH_RATE},
    "death2": {"mask": "anim_death2", "loop": False, "on_end": "hold",
               "transition": 0.05, "rate": ZOMBIE_DEATH_RATE},
    "death_superlong": {"mask": "anim_superlongdeath", "loop": False, "on_end": "hold",
                        "transition": 0.05, "rate": ZOMBIE_DEATH_RATE},
    "death_water": {"mask": "anim_waterdeath", "loop": False, "on_end": "hold",
                    "transition": 0.05, "rate": ZOMBIE_DEATH_RATE},
}


def zombie_animations(*, walk: bool = True, angry: bool = False,
                      eat_rate: float = ZOMBIE_EAT_RATE,
                      all_deaths: bool = True) -> Dict[str, Dict[str, object]]:
    """The clip set a zombie sharing Zombie.reanim gets.

    Every zombie that wears this body gets the same locomotion, bite and death clips; the
    original picks a death at random in code, and reproducing that needs all of them in
    the file. The ``hit`` clip is deliberately absent: it is the zombie's *standing* pose
    on a 1.25 s loop, and the server stopped publishing a hurt state (see
    ZombieEntity.walkOrEat) because holding it froze a walking zombie for an eighth of a
    second on every pea.
    """
    clips: Dict[str, Dict[str, object]] = {
        "idle": {"mask": "anim_idle", "loop": True},
    }
    if walk:
        clips["walk"] = {"mask": "anim_walk", "loop": True,
                         "reference_speed": ZOMBIE_WALK_REFERENCE_SPEED}
    clips["eat"] = {"mask": "anim_eat", "loop": True, "rate": eat_rate}
    if angry:
        clips["angry"] = {"mask": "anim_idle2", "loop": False, "on_end": "idle", "transition": 0.05}
    # Only Zombie.reanim, the shared master file, carries all four death sequences. Every
    # zombie with a reanim of its own has exactly one, so asking for the variants there is
    # a hard error rather than a silent fallback - which is the right shape: a missing
    # clip in the data is a content bug, and it should say so at generation time.
    if all_deaths:
        clips.update(ZOMBIE_DEATH_CLIPS)
    else:
        clips["death"] = ZOMBIE_DEATH_CLIPS["death"]
    return clips


# Bones the original hides in code rather than in the art.
#
# Zombie.reanim carries one tongue and one head of hair and shows them for the whole
# export; Zombie::SetupReanimLayers hides them for the variants that must not have them.
# A converter that only reads track visibility therefore gave every plain zombie a
# permanently lolling tongue and every armoured one a head of hair under its helmet.
ZOMBIE_NEVER_VISIBLE = ("tongue", "hair")

ENTITY_CONFIGS: List[EntityConfig] = [
    # ------------------------------------------------------------------
    # Resource drops
    # ------------------------------------------------------------------
    EntityConfig(
        output="sun",
        group="resource",
        reanim="Sun.reanim",
        target_box=(0.56, 0.56),
        # The sun is three stacked quads: a 36px core at full alpha and two glows authored
        # at 0.50-0.84. The glows are light, and the original adds them; drawn source-over
        # they are flat discs that swallow the core, which is why the sun used to read as a
        # bright blob with no shape. The core stays normal, so the sprite keeps an edge.
        additive_images=r"SUN2|SUN3",
        animations={
            "idle": {"range": "all", "loop": True},
            "landed": {"range": "all", "loop": True},
        },
    ),
    # The coin and diamond reanims ship with refer/im5 rather than refer/anim, so
    # these are generated with an explicit --input-dir.
    #
    # Four denominations, because the original's are four different objects with four
    # different values (silver 10, gold 50, diamond 1000, money bag 250); a single
    # "coin" sprite could not tell the player which one they just picked up.
    #
    # The glow is drawn, and additively - which is what the original does with it and what
    # the renderer could not do when this conversion was written, so the art was cut instead.
    # It is still not part of the *model*: a glow quad is authored wider than the coin it
    # belongs to (80px against 43), and since every bone in a controller model is sized by one
    # shared factor and drawn as one sprite, a model that carries it makes the coin face
    # authored at 54% of the number the box claims. The glow is a particle's job now; the coin
    # model is the coin.
    #
    # Sizing is the coin's own sprite, fitted on width - all four denominations are drawn the
    # same width on the lawn, which is the property a player reads ("that is a coin") and the
    # reason the fit is not height-driven here: a coin face is wider than it is tall, so
    # fitting the height would make every denomination a different width.
    EntityConfig(
        output="coin_silver",
        group="resource",
        reanim="Coin_silver.reanim",
        target_box=(0.34, 0.34),
        fit_height_only=False,
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
        target_box=(0.34, 0.34),
        fit_height_only=False,
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
        target_box=(0.34, 0.34),
        fit_height_only=False,
        exclude_image_regex=r"DIAMOND_SHINE",
        # The shine quad is as big as the gem's own drawing and is drawn around it, so
        # measuring the model against it makes the gem smaller than the box it was fitted to
        # (76% of it). Measuring the shine out is the same rule the armoured zombies use to
        # keep a bucket from deciding how tall a zombie is - and unlike excluding additive
        # parts in general, it is honest: the halo is still drawn at its own size, this only
        # says which sprite the model is measured by.
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
            # The rise is the one clip that needs the rescue (the rocks only come out with
            # it), and even there the blink overlay stays out: it is hidden for the whole
            # emergence and belongs to the armed loop.
            "armed": {
                "mask": "anim_rise",
                "loop": False,
                "on_end": "next",
                "next": "armed_loop",
                "transition": 0.1,
                "force_visible_hidden": True,
                "force_visible_exclude_prefixes": ["blink"],
            },
            "armed_loop": {"mask": "anim_armed", "loop": True, "transition": 0.1},
            # One frame of the flattened mine, and *nothing* may be rescued into it: the
            # rescue put the body, the stem and all six rocks back, so the blast held the
            # mine exactly as it looked before it went off.
            "explode": {
                "mask": "anim_mashed",
                "loop": False,
                "on_end": "hold",
                "transition": 0.05,
            },
        },
    ),
    EntityConfig(
        output="chomper",
        group="plant/attacker",
        reanim="Chomper.reanim",
        target_box=PLANT_BOX,
        # The two ZOMBIE tracks are the arm hanging out of the mouth: the original draws it
        # from `Zombie_outerarm_hand`/`_lower`, visible for frames 50..84 - the whole chew
        # and the first half of the swallow. They used to be filtered out by an image
        # regex, which is why a chewing chomper had an empty mouth. The same two sprites
        # are shared with the zombie models, so nothing new had to be drawn.
        animations={
            "idle": {"mask": "anim_idle", "loop": True},
            # No force_visible_hidden on any of the three: every part these masks draw is
            # already visible in its own frame range, so the rescue could only add parts the
            # original keeps hidden - and it did. `Chomper_stomach` (visible 79..93 only) and
            # the tongue-lick overlay came back for the whole bite and chew, which is the
            # purple lump that hung behind a chewing chomper's head.
            "chew": {
                "mask": "anim_chew",
                "loop": False,
                "on_end": "idle",
                "transition": 0.1,
            },
            "bite": {
                "mask": "anim_bite",
                "loop": False,
                "on_end": "idle",
                "transition": 0.1,
            },
            "swallow": {
                "mask": "anim_swallow",
                "loop": False,
                "on_end": "idle",
                "transition": 0.1,
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
        output="sun_shroom",
        group="plant/producer",
        reanim="SunShroom.reanim",
        target_box=SMALL_PLANT_BOX,
        # The original carries both forms of the mushroom in one timeline - `anim_grow`
        # (27..38) turns the small one into the grown one, `anim_bigidle` (39..50) and the
        # second half of `anim_sleep` (51..62) are that grown form - so both are exported
        # and the server picks the pair by name (`grow` / `idle_big` / `sleep_big`).
        #
        # The model is sized by the *small* form, which is the `idle` clip and therefore
        # what `reference_range` measures; the grown form then lands at the size the
        # original draws it (58.3px against the small one's 38.6px, i.e. about 0.59 of a
        # lawn cell) without a second scale factor anywhere.
        #
        # `anim_sleep` is both the small and the grown sleeping pose, which is why one of
        # the two clips below has to name its frame range instead of a mask: a mask
        # resolves to its first visible range, and that is the small one.
        animations={
            "idle": {"mask": "anim_idle", "loop": True, "transition": 0.1},
            "sleep": {"mask": "anim_sleep", "loop": True, "transition": 0.1},
            "grow": {"mask": "anim_grow", "loop": False, "on_end": "idle_big", "transition": 0.1},
            "idle_big": {"mask": "anim_bigidle", "loop": True, "transition": 0.1},
            "sleep_big": {"range": [51, 62], "loop": True, "transition": 0.1},
            # `produce` is what the producer capability publishes when a sun pops out, and
            # this plant has no dedicated one in the original: producing is its idle pose, in
            # whichever form it is in. Both are exported so the grown mushroom does not fall
            # back to the *small* idle for that beat - a missing clip resolves to `idle`, and
            # `produce_big` is only missing because nobody drew one.
            "produce": {"mask": "anim_idle", "loop": True, "transition": 0.1},
            "produce_big": {"mask": "anim_bigidle", "loop": True, "transition": 0.1},
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
            # The bean coming apart, which is what a placement spends it on. It is the
            # original's own `anim_crumble`; nothing else in the game plays it, because a
            # coffee bean is never eaten - it is *used*.
            "vanish": {
                "mask": "anim_crumble",
                "loop": False,
                "on_end": "hold",
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
        output="puff_shroom",
        group="plant/attacker",
        reanim="PuffShroom.reanim",
        target_box=SMALL_PLANT_BOX,
        # The original files the sleeping pose as its own mask (a slow 17-frame breathing
        # loop), so the server can ask for a "sleep" clip by name. No force_visible_hidden
        # here: the only bone the shooting mask hides is the closed-eye overlay, and
        # rescuing it would draw a sleeping face on a plant that is firing.
        animations={
            "idle": {"mask": "anim_idle", "loop": True, "transition": 0.1},
            "shoot": {
                "mask": "anim_shooting",
                "loop": False,
                "on_end": "idle",
                "transition": 0.1,
            },
            "sleep": {"mask": "anim_sleep", "loop": True, "transition": 0.1},
        },
    ),
    # Fume-shroom is the same mushroom as the Doom-shroom and is authored in the big
    # mushroom's box, so it stands a head taller than the small mushrooms - which is how
    # the original draws it. Its spout is a separate sprite that the shooting mask slides
    # out, hidden in the idle mask; rescuing permanently-hidden bones would leave the spout
    # poking out of a resting mushroom, so only the sleeping face is hidden explicitly.
    EntityConfig(
        output="fume_shroom",
        group="plant/attacker",
        reanim="FumeShroom.reanim",
        target_box=PLANT_BOX,
        animations={
            "idle": {"mask": "anim_idle", "loop": True, "transition": 0.1},
            "shoot": {
                "mask": "anim_shooting",
                "loop": False,
                "on_end": "idle",
                "transition": 0.1,
                "force_visible_hidden": True,
                "force_visible_exclude_prefixes": ["sleep"],
            },
            "sleep": {"mask": "anim_sleep", "loop": True, "transition": 0.1},
        },
    ),
    # Hypno-shroom. Every bone in its reanim is visible in every frame of both masks - the
    # two eyes simply swap sprites - so the sleeping face and the open eyes are drawn by the
    # same bones and nothing has to be hidden or rescued: the masks are the whole difference.
    EntityConfig(
        output="hypno_shroom",
        group="plant/special",
        reanim="HypnoShroom.reanim",
        target_box=PLANT_BOX,
        animations={
            "idle": {"mask": "anim_idle", "loop": True, "transition": 0.1},
            "sleep": {"mask": "anim_sleep", "loop": True, "transition": 0.1},
        },
    ),
    # Grave buster: the original's own two usable clips. `anim_land` is the drop onto the
    # tombstone, `anim_idle` is the chewing, and the plant is removed by the server when the
    # grave goes - there is no "finished" clip because the original never needed one.
    EntityConfig(
        output="grave_buster",
        group="plant/special",
        reanim="Gravebuster.reanim",
        target_box=PLANT_BOX,
        animations={
            "idle": {"mask": "anim_land", "loop": False, "on_end": "chew", "transition": 0.05},
            "chew": {"mask": "anim_idle", "loop": True, "transition": 0.1},
        },
    ),
    # ------------------------------------------------------------------
    # The two pea shooters
    #
    # The original names its reanims after the gun, not after the plant:
    # `PeaShooterSingle.reanim` is the one-headed Peashooter (its extra `anim_sprout` is
    # the sprout on top of the head) and `PeaShooter.reanim` is the *Repeater* - the same
    # plant with a second head, whose six `idle_headleaf_*` tracks are the back head's
    # leaves. Reading the two files the other way round is what shipped the repeater's
    # art on the peashooter's card and vice versa.
    #
    # Both keep the shooter line's shape: an `anim_full_idle` loop (79..103, the range
    # where body *and* head are visible) and a one-shot `anim_shooting`, whose range
    # (54..78) hides the leaves in the source, hence `force_visible_hidden`.
    # ------------------------------------------------------------------
    EntityConfig(
        output="pea_shooter",
        group="plant/attacker",
        reanim="PeaShooterSingle.reanim",
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
        reanim="PeaShooter.reanim",
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
            # A mushroom sleeps in daylight (pvzce:nocturnal), and the original draws the
            # sleeping head from its own sprite.
            "sleep": {"mask": "anim_sleep", "loop": True, "transition": 0.1},
            # The sleeping head is its own sprite (see `sleep`), and it is hidden for the
            # whole blast - so the rescue brought a closed-eyed head back for the explosion.
            "explode": {
                "mask": "anim_explode",
                "loop": False,
                "on_end": "hold",
                "transition": 0.05,
                "force_visible_hidden": True,
                "force_visible_exclude_prefixes": ["sleep"],
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
        force_hidden_bones=r"tongue|hair",
        animations=zombie_animations(),
    ),
    EntityConfig(
        output="buckethead_zombie",
        group="zombie/armored",
        reanim="Zombie.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        exclude_image_regex=ZOMBIE_BUCKET_EXCLUDE,
        force_hidden_bones=r"tongue|hair",
        # The bucket sits above the head: measure the zombie, not its hat, or the body is
        # fitted to the bucket's height and ends up a size smaller than a bare zombie's.
        measure_exclude_regex=r"^bucket_",
        damage_states={
            "bucket_1": (("bucket_2", "Zombie_bucket2.png"),
                         ("bucket_3", "Zombie_bucket3.png")),
        },
        animations=zombie_animations(angry=True),
    ),
    EntityConfig(
        output="conehead_zombie",
        group="zombie/armored",
        reanim="Zombie.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        exclude_image_regex=ZOMBIE_CONE_EXCLUDE,
        force_hidden_bones=r"tongue|hair",
        measure_exclude_regex=r"^cone_",
        damage_states={
            "cone_1": (("cone_2", "Zombie_cone2.png"),
                       ("cone_3", "Zombie_cone3.png")),
        },
        animations=zombie_animations(angry=True),
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
        # The pole's own hand is *not* the tongue: only the shared body's spare parts go.
        force_hidden_bones=r"tongue|hair",
        # The flag has two drawings in the rip (whole and tattered); the original swaps
        # them in code, so the tattered one is referenced by no track.
        damage_states={
            "zombie_flag_1": (("zombie_flag_3", "Zombie_flag3.png"),),
        },
        animations=zombie_animations(angry=True),
    ),
    EntityConfig(
        output="door_zombie",
        group="zombie/armored",
        reanim="Zombie.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        exclude_image_regex=ZOMBIE_DOOR_EXCLUDE,
        force_hidden_bones=r"tongue|hair",
        measure_exclude_regex=r"^screendoor_",
        damage_states={
            "screendoor_1": (("screendoor_2", "Zombie_screendoor2.png"),
                             ("screendoor_3", "Zombie_screendoor3.png")),
        },
        animations=zombie_animations(angry=True),
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
        measure_exclude_regex=r"^paper_",
        # The newspaper zombie's own hair and its paper hairpiece are its silhouette, so
        # only the shared body's tongue goes.
        force_hidden_bones=r"tongue",
        damage_states={
            "paper_1": (("paper_2", "Zombie_paper_paper2.png"),
                        ("paper_3", "Zombie_paper_paper3.png")),
        },
        # This zombie has a whole second gait and bite for the state where its paper is
        # gone (anim_walk_nopaper / anim_eat_nopaper), which the original switches to in
        # code. Both are exported and the server asks for them by name.
        animations=zombie_animations(walk=False, all_deaths=False) | {
            "walk": {"mask": "anim_walk", "loop": True,
                     "reference_speed": ZOMBIE_WALK_REFERENCE_SPEED},
            "walk_nopaper": {"mask": "anim_walk_nopaper", "loop": True,
                             "reference_speed": ZOMBIE_WALK_REFERENCE_SPEED},
            "eat_nopaper": {"mask": "anim_eat_nopaper", "loop": True, "rate": ZOMBIE_EAT_RATE},
            "angry": {"mask": "anim_gasp", "loop": False, "on_end": "idle",
                      "transition": 0.05},
        },
    ),
    EntityConfig(
        output="pole_vaulter_zombie",
        group="zombie/special",
        reanim="Zombie_polevaulter.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        # Two walking clips, and the difference between them is the pole: `anim_run` (13..49)
        # is the jog towards the plant with the pole held out, `anim_walk` (93..137) is the
        # walk after the vault. `anim_jump` (50..92) is the vault itself, and `_ground` marks
        # exactly the two grounded ranges - so which clip is which is the file's own answer,
        # not a guess. Only the walk used to be exported, so the zombie jogged the
        # empty-handed clip while still carrying the pole.
        # The pole vaulter walks at 0.3 cells/s, faster than the 0.23 the shared walk art
        # assumes - which is exactly why it has its own jogging clip, and why both of its
        # ground clips name that speed instead of the shared constant.
        animations=zombie_animations(walk=False, all_deaths=False) | {
            "run": {"mask": "anim_run", "loop": True, "reference_speed": 0.3},
            "walk": {"mask": "anim_walk", "loop": True, "reference_speed": 0.3},
            "jump": {"mask": "anim_jump", "loop": False, "on_end": "walk", "transition": 0.05},
        },
    ),
    EntityConfig(
        output="balloon_zombie",
        group="zombie/special",
        reanim="Zombie_balloon.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        # Both of this zombie's ways of moving are locomotion: it drifts at 0.47 cells/s
        # with the balloon and at 0.23 on foot, so each clip names the speed it was drawn for.
        animations=zombie_animations(walk=False, all_deaths=False) | {
            "fly": {"mask": "anim_swing", "loop": True, "transition": 0.1,
                    "reference_speed": 0.47},
            "walk": {"mask": "anim_walk", "loop": True, "reference_speed": 0.23},
            "fall": {"mask": "anim_pop", "loop": False, "on_end": "walk", "transition": 0.05},
        },
    ),
    EntityConfig(
        output="miner_zombie",
        group="zombie/underground",
        reanim="Zombie_digger.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        # Travels at 0.3, tunnels at the same speed: one number for both of its gaits.
        animations=zombie_animations(walk=False, all_deaths=False) | {
            "walk": {"mask": "anim_walk", "loop": True, "reference_speed": 0.3},
            "dig": {"mask": "anim_dig", "loop": True, "transition": 0.1,
                    "reference_speed": 0.3},
            "dig_exit": {"mask": "anim_landing", "loop": False, "on_end": "walk",
                         "transition": 0.1},
        },
    ),
    EntityConfig(
        output="gargantuar",
        group="zombie/giant",
        reanim="Zombie_gargantuar.reanim",
        target_box=(1.10, 1.55),
        fit_height_only=False,
        # eHeavy: the smash cycle is the animation's own clock, which marks the beat the
        # hammer lands on, so it is played as drawn rather than at the shared zombie eat
        # rate. 1.4 cells/s is what the 4.08 s walk cycle is drawn for.
        # Its bite is a smash, and there is no separate eat cycle to override - so the
        # shared set is built and then the one clip this brute does not have is replaced.
        animations=(zombie_animations(walk=False, eat_rate=1.0, all_deaths=False)
                    | {
                        "walk": {"mask": "anim_walk", "loop": True, "reference_speed": 0.14},
                        "eat": {"mask": "anim_smash", "loop": True, "rate": 1.0},
                    }) | {
            "hammer": {
                "mask": "anim_smash",
                "loop": False,
                "on_end": "idle",
                "transition": 0.1,
            },
        },
    ),
    EntityConfig(
        output="zombie_boss",
        group="zombie/boss",
        reanim="Zombie_boss.reanim",
        target_box=(1.40, 2.00),
        fit_height_only=False,
        # The RV's wheels turn in every frame of its clip, so the drive is a genuine
        # locomotion clip: 3.17 s per cycle drawn for 0.15 cells/s.
        animations=zombie_animations(walk=False, eat_rate=1.0, all_deaths=False) | {
            "walk": {"mask": "anim_RV_1", "loop": True, "reference_speed": 0.15},
            "eat": {"mask": "anim_stomp_1", "loop": True, "rate": 1.0},
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
    # ------------------------------------------------------------------
    # Level props
    #
    # The lawn mower is not content (it has no registry entry), but its art is still the
    # original's reanim: two clips over the `anim_normal` mask, which is the mower as the
    # lawn shows it (the other mask is the Zen Garden's tricked-out version). One cell
    # wide, a little over half a cell tall, which is its size in the original.
    #
    # `idle` is a single frame on purpose. The source reanim's wheel tracks rotate in
    # every frame of `anim_normal`, so exporting the whole range gave a parked mower
    # whose wheels never stopped turning; freezing it at frame 0 is the pose the
    # original holds until the mower is tripped, and `drive` is the rolling one.
    # ------------------------------------------------------------------
    EntityConfig(
        output="lawn_mower",
        group="mechanic",
        reanim="LawnMower.reanim",
        target_box=(1.0, 0.62),
        animations={
            "idle": {"mask": "anim_normal", "range": [0, 0], "loop": True, "transition": 0.05},
            "drive": {"mask": "anim_normal", "loop": True, "transition": 0.05},
        },
    ),
    EntityConfig(
        output="imp",
        group="zombie/giant",
        reanim="Zombie_imp.reanim",
        target_box=(0.45, 0.60),
        fit_height_only=False,
        # No `hit`: the server stopped publishing a hurt state (see ZombieEntity.walkOrEat),
        # and a clip nothing can ask for is a clip that will rot. The imp's idle *is* its
        # walk - it has one gait - which is why the two names point at the same mask.
        animations={
            "idle": {"mask": "anim_walk", "loop": True},
            "walk": {"mask": "anim_walk", "loop": True},
            "eat": {"mask": "anim_eat", "loop": True},
            "thrown": {"mask": "anim_thrown", "loop": False, "on_end": "land", "transition": 0.05},
            "land": {"mask": "anim_land", "loop": False, "on_end": "walk", "transition": 0.05},
            "death": {"mask": "anim_death", "loop": False, "on_end": "hold", "transition": 0.05},
        },
    ),
    # ------------------------------------------------------------------
    # The burnt corpse
    #
    # What the ash line leaves behind. This is not a clip on the zombie model: the original
    # draws it from its own reanim, and the two share no bones - the charred body is a pile
    # with a skull in it. So it is its own controller file, and a zombie definition reaches
    # it through its per-state `animations` map:
    #
    #     "animations": { "death_burned": "pvzce:zombie/charred/zombie_charred" }
    #
    # The clip is named after the *state* (`death_burned`), not after the source mask, so
    # the manager finds it by the name the server publishes. `anim_crumble` is the collapse
    # and ends held: the corpse stays as the pile of ash the original leaves, which is what
    # `on_end: hold` means everywhere else.
    #
    # One model for every zombie on purpose: the original uses the same charred sprite for a
    # bare zombie and a Conehead, because whatever it was wearing is gone by then. The
    # dedicated `Zombie_charred_*` files in the rip are the Gargantuar, the digger and the
    # imp, which have their own bodies and are not covered by this.
    # ------------------------------------------------------------------
    EntityConfig(
        output="zombie_charred",
        group="zombie/charred",
        reanim="Zombie_charred.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        animations={
            "death_burned": {
                "mask": "anim_crumble",
                "loop": False,
                "on_end": "hold",
                "transition": 0.05,
            },
            # A zombie that is asked for its idle while it is a pile of ash (a save restored
            # mid-death, a client that missed the transition) holds the pile rather than
            # finding nothing at all.
            "idle": {"mask": "anim_crumble", "range": [0, 0], "loop": True},
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


def measure_bones(bones: Sequence[core.Bone], config: EntityConfig) -> List[core.Bone]:
    """The bones the model's size is measured from; see ``measure_exclude_regex``."""

    if not config.measure_exclude_regex:
        return list(bones)
    excluded = re.compile(config.measure_exclude_regex, re.IGNORECASE)
    kept = [bone for bone in bones if not excluded.search(bone.name)]
    if not kept:
        raise SystemExit(
            f"{config.output}: measure_exclude_regex {config.measure_exclude_regex!r} "
            "excluded every bone")
    return kept


def load_damage_state_asset(input_dir: Path, file_name: str) -> core.ImageAsset:
    """One damaged-equipment PNG, by file name next to the reanim that uses it."""

    source = input_dir / file_name
    if not source.is_file():
        raise SystemExit(f"Missing damage-state image: {source}")
    width, height = core.read_png_size(source)
    return core.ImageAsset(ref=file_name, source=source, width=width, height=height)


def apply_damage_states(
    config: EntityConfig,
    bones: List[core.Bone],
    attached_bones: set,
    input_dir: Path,
) -> None:
    """Appends a bone per declared damage state, hidden in every clip.

    The new bone shares its host's frame states - so it moves exactly like the intact
    sprite - and keeps its own texture. Hidden everywhere is the point: the client turns
    exactly one member of the family on from the zombie's synced state, so no clip has to
    know which cone a zombie is wearing.
    """

    for host_name, entries in config.damage_states.items():
        host = next((bone for bone in bones if bone.name == host_name), None)
        if host is None:
            raise SystemExit(
                f"{config.output}: damage-state host bone {host_name!r} is not in the model")
        for new_name, file_name in entries:
            if any(bone.name == new_name for bone in bones):
                raise SystemExit(f"{config.output}: duplicate damage-state bone {new_name!r}")
            bones.append(
                core.Bone(
                    name=new_name,
                    asset=load_damage_state_asset(input_dir, file_name),
                    states=list(host.states),
                    visibility=[False] * len(host.states),
                    order=host.order,
                    track_names=[f"damage_state:{host_name}"],
                    hidden=True,
                )
            )
            # A damage state of an *attached* bone (the flag zombie's flag) has to be
            # welded to the same host hand, or the damaged flag stands still while the
            # intact one swings with the arm.
            if host_name in attached_bones:
                attached_bones.add(new_name)


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
            start, end = 0, frame_count - 1
        elif isinstance(value, (list, tuple)) and len(value) == 2:
            start, end = int(value[0]), int(value[1])
        else:
            raise SystemExit(f"Invalid range spec: {value!r}")
    else:
        start, end = mask_range(tracks, str(spec["mask"]))
    # trim_end exists because a mask range can end on a frame that repeats the one before it.
    # That is a legitimate authoring choice for a one-shot (the pose is held) and a visible
    # stall for a loop, and only the clip's author knows which this is - the length maths
    # cannot tell a deliberate hold from a mistake.
    trim = int(spec.get("trim_end", 0))
    if trim > 0:
        end = max(start, end - trim)
    return start, end


def excluded_from_rescue(name: str, prefixes: Sequence[str]) -> bool:
    """Whether ``force_visible_hidden`` must leave this bone alone.

    <p>A bone's name is built from the *image* it draws, so it can carry the source
    entity's prefix - ``PeaShooter_blink1.png`` becomes ``peashooter_blink_1`` for the pea
    shooter, while the snow pea's equivalent is a bare ``blink_1``. Matching the prefix
    with ``str.startswith`` therefore caught one plant's closed-eye overlay and rescued the
    other's for the whole clip, which drew a blink over both eyes for the entire shooting
    animation. The check is per underscore-separated word instead, so a prefix means "this
    word starts the name or a word in it".
    """

    lowered = name.lower()
    return any(
        lowered.startswith(prefix.lower())
        or any(word.startswith(prefix.lower()) for word in lowered.split("_"))
        for prefix in prefixes
    )


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
    # fullmatch, not search: a bone name is an identity, and "hide hair" must not also
    # hide the newspaper zombie's `hairpiece`, which is the whole point of its silhouette.
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
            if force_visible and permanently_hidden and not excluded_from_rescue(bone.name, exclude_prefixes):
                is_visible = True
            if force_visible_re is not None and force_visible_re.fullmatch(bone.name):
                is_visible = True
            if clip_visible_re is not None and clip_visible_re.search(bone.name):
                is_visible = True
            if force_hidden_re is not None and force_hidden_re.fullmatch(bone.name):
                is_visible = False
            if bone.hidden:
                # A damage-state sprite is never drawn by a clip: the client picks one
                # member of the family from the zombie's synced state. This is last so no
                # force_visible rule can resurrect it.
                is_visible = False
            visibility.append(is_visible)

        def key(frame: int) -> str:
            return core.format_time((frame - start) / fps)

        weld = host_translation if (attached_bones and bone.name in attached_bones) else None
        # The host's rest position, which is the pivot a welded bone swings around.
        weld_pivot = weld(start) if weld is not None else None

        def translation_at(frame: int) -> List[float]:
            state_data = bone.states[frame]
            center_x, center_y = core.model_center_px(state_data, bone.asset, bbox)
            value = [center_x * scale, center_y * scale]
            if weld is not None:
                # The piece is carried by the host: its authored position plus however far the
                # host has moved since the clip began.
                #
                # Translation only, and that is not a simplification - the authored translation
                # of a held thing is *already* where it sits in the grip, so the vector from the
                # host to the piece is the piece's own drawing and rotating it turns the piece a
                # second time. Both earlier attempts did exactly that: rotating about the host
                # (which swings a pole that is already at the fist) and rotating the piece's own
                # near-zero offset (which swings nothing and lets the grip drift 0.16 cells).
                # The host's *rotation* is not lost by this: the piece's own keys carry the same
                # swing the host does, because the art draws them in the same frame sequence.
                host_x, host_y = weld(frame)
                rest_x, rest_y = weld_pivot
                value[0] += host_x - rest_x
                value[1] += host_y - rest_y
            return [core.round_float(value[0]), core.round_float(value[1])]


        def rotation_at(frame: int) -> List[float]:
            # The piece's own drawn shear, untouched. It already carries the same swing the host
            # does - the art was drawn as one animation - so tilting it by the host's delta
            # rotates the pole twice about a pivot it is already positioned against.
            state_data = bone.states[frame]
            return [core.round_float(state_data.kx), core.round_float(state_data.ky), 0.0]

        def scale_at(frame: int) -> List[float]:
            state_data = bone.states[frame]
            if weld is not None:
                # The piece is held *in* the hand, so it is drawn at the hand's size rather than
                # at whatever the source happened to draw it at. The flagpole is the case: the
                # hand is authored at scale 0.80 and the pole and flag at 0.88-1.00, so a flag
                # welded without its scale arrived a fifth larger than the fist gripping it.
                host_scale = host_bone.states[frame]
                return [core.round_float(state_data.sx * host_scale.sx),
                        core.round_float(state_data.sy * host_scale.sy)]
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

        def alpha_keys() -> Dict[str, float]:
            """The track's alpha, but only when the art actually varies it.

            Every reanim frame carries an alpha and almost every one of them is 1, so
            emitting the channel unconditionally would add a constant block to all 2688
            bone tracks in the shipped set to say nothing. A bone whose alpha never leaves
            1 omits the channel, and the runtime's default (1) is already the answer.
            """
            values = [bone.states[frame].a for frame in range(start, end + 1)]
            if all(abs(value - 1.0) <= 1e-6 for value in values):
                return {}
            keys: Dict[str, float] = {}
            last: Optional[float] = None
            for offset, frame in enumerate(range(start, end + 1)):
                value = values[offset]
                if last is None or abs(value - last) > 1e-6:
                    keys[key(frame)] = core.round_float(value)
                last = value
            return keys

        entry: Dict[str, object] = {
            "translation": vector_keys(translation_at),
            "rotation": vector_keys(rotation_at),
            "scale": vector_keys(scale_at),
            "visible": boolean_keys(),
        }
        alpha = alpha_keys()
        if alpha:
            entry["alpha"] = alpha
        animation_bones[bone.name] = entry

    loop = bool(spec.get("loop", False))
    animation: Dict[str, object] = {
        "animation_length": round(max(1e-6, loop_frames(bones, start, end, loop)) / fps, 10),
        "loop": loop,
    }
    if spec.get("on_end") is not None:
        animation["on_end"] = str(spec["on_end"])
    if spec.get("next") is not None:
        animation["next"] = str(spec["next"])
    animation["transition"] = float(spec.get("transition", 0.1))
    rate = spec.get("rate")
    if rate is not None and abs(float(rate) - 1.0) > 1e-9:
        animation["rate"] = float(rate)
    reference_speed = spec.get("reference_speed")
    if reference_speed is not None and float(reference_speed) > 0.0:
        animation["reference_speed"] = float(reference_speed)
    animation["bones"] = animation_bones
    animation["sound_effects"] = {}
    animation["particle_effects"] = {}
    animation["timeline"] = {}
    return animation


def additive_regex(config: EntityConfig):
    """The compiled additive_images pattern, or None."""
    return re.compile(config.additive_images, re.IGNORECASE) if config.additive_images else None


def additive_search(config: EntityConfig, image_ref: str):
    """Whether this image reference is one of the additively blended sprites."""
    pattern = additive_regex(config)
    return pattern.search(image_ref) if pattern is not None else None


def planar_step(a: core.FrameState, b: core.FrameState) -> float:
    """How far a piece travels between two frames, ignoring alpha and image swaps."""
    return math.hypot(a.x - b.x, a.y - b.y)


def loop_frames(bones: Sequence[core.Bone], start: int, end: int, loop: bool) -> int:
    """How many frames of travel one loop of this clip covers.

    A looping clip has to come back to where it started, and reanim art says so in one of two
    ways - which the data itself distinguishes, so this reads it rather than assuming:

    * the run closes on itself: the seam from the last frame back to the first is an ordinary
      step. The last frame is then where the cycle already ends, and counting it holds that
      pose for one more frame interval. The sampler interpolates *between keys*, so a duration
      that runs one frame past the last key is a visible stall - the zombie's walk stood still
      for 83 ms on the spot once per cycle and the boss's death held a frozen corpse for a
      quarter of a second.
    * the run does not close: the last pose is somewhere else, and the seam jump is large
      (the balloon's drift jumps 25.9 px against a 2.2 px typical step). The clip is then a
      pass rather than a cycle, and cutting it a frame short would wrap before that pose is
      reached at all - a backwards snap. Its full count is what the sampler can render without
      a hold, because every frame including the last has a key of its own.

    The test is "is the seam an ordinary step", not "are the first and last frames identical",
    because a cycle can end on the *extreme* of a symmetric swing: the pea shooter's idle ends
    0.1 px from where it starts against a 0.47 px typical step, and comparing poses for
    equality would call that open and reintroduce the stall.

    A one-shot clip takes the full count either way: its last frame is meant to be seen, and
    `on_end` decides what happens after.

    Conservative about short tracks: a bone whose source track is shorter than the clip (a
    13-frame flagpole inside a 504-frame zombie) contributes nothing, because its own "last
    frame" is not the clip's.
    """
    if not loop:
        return end - start + 1
    steps: List[float] = []
    seam = 0.0
    for bone in bones:
        if end + 1 > len(bone.states) or start + 1 > len(bone.states):
            continue
        if bone.states[start].image is None:
            continue
        for frame in range(start, end):
            steps.append(planar_step(bone.states[frame], bone.states[frame + 1]))
        seam = max(seam, planar_step(bone.states[end], bone.states[start]))
    if not steps:
        return end - start
    steps.sort()
    median = steps[len(steps) // 2]
    return end - start if seam <= max(median * 1.5, 1e-3) else end - start + 1


def model_extent(bones: Sequence[core.Bone], frames: Iterable[int],
                 exclude: Optional["re.Pattern[str]"] = None) -> Tuple[float, float]:
    """The rectangle the model actually draws in, in source pixels, over the given frames.

    Computed with the same transformations the runtime uses - each part is a quad centred on
    its bone's origin, rotated by the source's own shear - because the number this feeds is
    read back as the entity's visual size, and it has to describe the sprite as drawn. The
    reanim bounding box it used to be derived from is a union of *rotated corner* positions
    over every sprite and every frame of the whole file, which is a different (and larger)
    rectangle: for the coin it was 80x80px where the drawn coin face is 43x40, so a drop's
    stated size was bigger than the thing on the lawn and every sizing rule inherited it.

    Measured over every frame of the reference clip rather than one pose, so a model whose
    parts move apart during an idle does not report itself as smaller than it gets.
    """
    min_x = min_y = float("inf")
    max_x = max_y = -float("inf")
    for bone in bones:
        if exclude is not None and exclude.search(bone.name):
            continue
        half_w = bone.asset.width * 0.5
        half_h = bone.asset.height * 0.5
        for frame in frames:
            if frame >= len(bone.states):
                continue
            state = bone.states[frame]
            if state.f < 0.0 or state.image is None:
                continue
            # The four corners of the drawn quad, which is what the sprite actually covers:
            # the bone matrix is a shear, so the *axes* it produces are not the rectangle -
            # a coin spinning about its vertical axis has a rotated x-axis pointing straight
            # down at the halfway point, and a union of matrices would report the coin as twice
            # as wide as it is while drawing it as a sliver.
            for x, y in core.transform_corners(state, bone.asset.width, bone.asset.height):
                # reanim pixel space -> model space: x right, y up.
                min_x = min(min_x, x)
                min_y = min(min_y, -y)
                max_x = max(max_x, x)
                max_y = max(max_y, -y)
    if not all(math.isfinite(value) for value in (min_x, min_y, max_x, max_y)):
        return 0.0, 0.0
    return max_x - min_x, max_y - min_y


def build_controller_json(
    config: EntityConfig,
    bones: Sequence[core.Bone],
    tracks: Sequence[core.Track],
    fps: float,
    bbox: core.BBox,
    scale: float,
    drawn: Tuple[float, float],
    attached_bones: Optional[set] = None,
) -> Dict[str, object]:
    model_bones: List[Dict[str, object]] = [
        {
            "name": "root",
            "parent": None,
            "pivot": [0.0, 0.0],
        }
    ]
    additive = additive_regex(config)
    for index, bone in enumerate(bones):
        part: Dict[str, object] = {
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
        if additive is not None and additive.search(bone.asset.ref):
            part["blend"] = "add"
        model_bones.append(
            {
                "name": bone.name,
                "parent": "root",
                "pivot": [0.0, 0.0],
                "parts": [part],
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
                core.round_float(drawn[0] * scale),
                core.round_float(drawn[1] * scale),
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

    # The host's own art, kept before the extras are appended. The bounding box is the main
    # reanim's and so is the extent below, deliberately: the extras are authored in that same
    # space, so measuring them too would scale the host sprite down to fit a flag that is
    # supposed to stick out of it. The extras are then drawn at the host's factor.
    host_measure = measure_bones(bones, config)
    bbox = compute_bbox(host_measure, [reference_range(config, tracks)])
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
        # wrap_source: an extra reanim is its own short loop (the flagpole is 13 frames
        # inside a 504-frame zombie), so it has to cycle through the host's length rather
        # than stop at its own end.
        extra_pieces = core.build_render_pieces(extra_tracks, extra_assets, extra_path, frame_count,
                                                wrap_source=True)
        extra_bones = pieces_to_bones(extra_pieces, extra_path)
        attached_bones.update(bone.name for bone in extra_bones)
        bones.extend(extra_bones)
    # After the extras, so a damage state of an attached bone (the flag) is welded like
    # its host; before the scale, because the extras are invisible and must not move it.
    apply_damage_states(config, bones, attached_bones, input_dir)
    # What the model *draws*, in source pixels, measured with the same affine the runtime uses
    # (see model_extent). The fit works off this rather than the reanim bounding box because
    # the number written into `model.size` - which the runtime reads back as the entity's
    # visual size and which a drop's whole size story is built on - is this rectangle, and a
    # fit that targets a different rectangle than the one it publishes lands the art at a size
    # nobody asked for (fitted on width, the coins came out 0.339 wide instead of 0.34, because
    # the bounding box the fit divided by was the larger one).
    # Every frame the model can be posed in, so the published size is the room it needs rather
    # than the room it happens to occupy at one instant of one clip.
    size_start, size_end = reference_range(config, tracks)
    # Same exclusions the model is measured by, for the same reason: a hat must not decide how
    # tall the body is. Applying them to only one of the two rectangles is what made every
    # armoured zombie 15% smaller than a plain one - the fit divided by the body's height while
    # the published size was the hat's, so the fraction that came out was the hat's too.
    measure_exclude = (re.compile(config.measure_exclude_regex, re.IGNORECASE)
                       if config.measure_exclude_regex else None)
    drawn = model_extent(host_measure, range(size_start, size_end + 1), measure_exclude)
    if config.fit_height_only:
        scale = config.target_box[1] / max(1.0, drawn[1])
    else:
        scale = min(
            config.target_box[0] / max(1.0, drawn[0]),
            config.target_box[1] / max(1.0, drawn[1]),
        )

    controller = build_controller_json(config, bones, tracks, fps, bbox, scale, drawn, attached_bones)

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
