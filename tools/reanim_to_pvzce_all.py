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
from typing import Dict, Iterable, List, Optional, Sequence, Set, Tuple

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
# The scaredy-shroom is a small mushroom but not a *tiny* one: its reanim is 71.3px tall against
# the puff-shroom's 38, so fitting it to the small box drew it at half the size the original
# gives it. Measured by the same rule as the rest (1px = 0.010175 cells at the sunflower
# reference), which is what the number below is.
SCAREDY_SHROOM_BOX = [0.530, 0.726]

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
# One accessory kept, the rest dropped - the pool's zombies. `Zombie.reanim` carries a
# `Zombie_duckytube` track (visible in 292 of its 504 frames, so the death masks already drop
# it), which every land variant removes by image name. Keeping it *is* the ducky-tube zombie:
# the floatie is authored art in the zombie's own model space, not a sprite the runtime blits,
# so the ring rides the waist through every clip without a line of code.
DUCKY_BASE_EXCLUDE = r"(FLAGHAND|SCREENDOOR|WHITEWATER|SNORKLE|CONE|BUCKET|MUSTACHE)"
DUCKY_CONE_EXCLUDE = r"(FLAGHAND|SCREENDOOR|WHITEWATER|SNORKLE|BUCKET|MUSTACHE)"
DUCKY_BUCKET_EXCLUDE = r"(FLAGHAND|SCREENDOOR|WHITEWATER|SNORKLE|CONE|MUSTACHE)"


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
    # Bones drawn *in their host's space* rather than in the model's, as ``child -> host``.
    #
    # A reanim has one flat transform space: every track carries absolute coordinates, so a part
    # that rides on another part is authored frame by frame in world space and the parent's motion
    # is baked into its own keys. That is why the threepeater's three heads stand still while its
    # three stems sway - the heads' own tracks are a flat three-second loop, and nothing ties them
    # to the stems they are drawn growing out of. Naming the host here makes the exporter rewrite
    # the child's keys into the host's space and declare it as a child in the model's bone tree, so
    # the engine's own parent transform carries it.
    #
    # Orthogonal to ``extra_bone_host`` (which welds a *translation delta* onto a bone whose keys
    # are already in model space, for a part that comes from a second reanim file). This one is a
    # real parent link, so it is the right tool whenever the host's animation is what should move
    # the child - and the wrong one when the child's keys already say where it goes.
    bone_parents: Dict[str, str] = field(default_factory=dict)
    # Sprites that belong to the model but to no track in it: ``{host bone: ((bone, png), ...)}``.
    #
    # The bobsled team is the case. The original draws its board with the game rather than the
    # reanim, so an image for it is not in the rip at all and no mask mentions one - but the board
    # is part of the zombie's picture, drawn under its feet, and the runtime draws a zombie by
    # drawing its model's bones. A bone declared here copies its host's keys (so it rides whatever
    # the host does), draws its own sprite, and is hidden in every clip until a `force_visible`
    # rule brings it back.
    #
    # The same shape as ``damage_states``, and different in what it is for: a damage state is a
    # family the client picks one member of, while this is a piece that is simply there. A pack
    # that wants "one sprite, shown from a clip" has a damage state with one entry; this is for a
    # sprite that is not a state of anything.
    extra_bones: Dict[str, Tuple[Tuple[str, str, float], ...]] = field(default_factory=dict)
    # ...and each entry is ``(bone, png, offset_y)``: the third number is how far above the host's
    # own origin the sprite hangs, in model units. A board under four riders is *below* the body
    # bone it rides, and the engine draws a part centred on its bone, so the piece needs somewhere
    # to say "down there" - the alternative is padding every such PNG with blank rows.
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
    # How much of its authored alpha an additively blended sprite keeps.
    #
    # Light adds, and a stack of glows adds up: the sun is a saturated yellow core with two
    # pale halos over it, and at full authored alpha the sum clips to white with the core's
    # colour lost inside it. Scaling the additive pass down keeps the halo's shape and the
    # core's colour, which is the pair a player reads a sun by.
    #
    # Applied to the alpha channel the clip already writes, so nothing about the geometry,
    # the clip length or the hit box changes.
    additive_alpha_scale: float = 1.0
    # Extra sprites for one bone, as its damage states: ``{host bone: ((new bone, png), ...)}``.
    #
    # The original swaps a worn cone/bucket/flag for a more damaged drawing in code, so
    # those PNGs are referenced by no track and the converter would never see them. Each
    # entry here becomes one more bone in the model - the host's animation keys, its own
    # texture, and ``visible: false`` in every clip - and the client shows exactly one
    # member of the family from the zombie's synced state (see ``EquipmentDef``).
    # Authoring them invisible is what keeps every existing clip untouched.
    damage_states: Dict[str, Tuple[Tuple[str, str], ...]] = field(default_factory=dict)
    # Bones renamed after they are derived from their sprite: ``{old: new}``.
    #
    # Needed where the original's *file names* do not spell a family the way the runtime
    # looks one up. A worn piece is found by the client as ``<art>_1``, ``<art>_2``, ...,
    # and the bucket's and cone's sprites are already numbered that way
    # (``Zombie_bucket1.png``). The football helmet's intact drawing is not - it is
    # ``helmet`` while its cracked drawings are ``helmet2``/``helmet3`` - so the intact one
    # is renamed into the family here, which is the only place that knows it is the same
    # piece of kit as the other two.
    bone_renames: Dict[str, str] = field(default_factory=dict)


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

# The whack-a-zombie mallet's swing, as a multiple of the reanim's 12fps. The whole blow is
# nine frames, so at authoring speed it takes 0.75 s - three quarters of a second between the
# click and the mallet coming back up, in the one level whose whole rhythm is "hit the next
# zombie". 2.5 puts it at 0.3 s, which is where the original's swing reads: a fast strike and
# a slightly slower recovery, both inside the same nine frames.
HAMMER_SWING_RATE = 2.5

# Every plant's attack, as a multiple of the reanim's own 12fps. The shooting masks are
# authored as one long cycle - 25 frames for a peashooter, 30 for the fume-shroom - which at
# authoring speed is a 2.1 s throw and a 2.5 s breath: longer than the 1.5 s between volleys,
# so the plant is still winding down when the next pea is due and the whole lane reads as
# slow motion. The original plays these masks at twice the file's rate, which puts a shot at
# about a second and leaves the pause between volleys visible - the pea leaves the muzzle at
# the start of the throw, and what the player sees after it is the plant settling back.
#
# It is one number for the whole line (peas, mushrooms and pults) because it is one fact:
# the art for all of them is drawn at the same 12fps and played at the same speed. A clip
# that states its own rate still wins - this is only what the attack masks are given.
SHOOT_ANIMATION_RATE = 2.0

# The cherry bomb's puff-up, as a fraction of the reanim's 30fps. CherryBomb.reanim is
# authored at 30fps and the converter trims its idle mask to the 12 frames that actually
# move, which is a 0.4 s loop - and the fuse is 60 ticks, one second. At authoring speed the
# bomb swells two and a half times over and then explodes in the middle of the third, which
# is what "the cherry bomb's animation is wrong" was: 0.4 puts one puff-up exactly on the
# fuse, so the plant swells once and goes off as it finishes.
#
# It applies to the IDLE ONLY. Slowing the explode mask by the same factor made the blast
# itself 1.17 s long, which is "the explosion takes too long": a half-second burst that
# lingers for over a second reads as a fire that will not go out. The burst is the one half
# of the gesture the original does NOT stretch - 14 frames at 30fps, 0.47 s.
CHERRY_BOMB_ANIMATION_RATE = 0.4

# All three of the original's death sequences exist in Zombie.reanim. Which one a zombie
# plays is the client's choice among the clips the file has (see the client's
# `AnimationVariants`), so all three are exported; waterdeath is the one for a body that
# drowns, and death_superlong is only ever reached by a definition that pins its `death`
# state at a file where that is the clip.
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
                      all_deaths: bool = True,
                      swim: bool = False) -> Dict[str, Dict[str, object]]:
    """The clip set a zombie sharing Zombie.reanim gets.

    Every zombie that wears this body gets the same locomotion, bite and death clips; the
    original picks a death at random in code, and reproducing that needs all of them in
    the file. The ``hit`` clip is deliberately absent: it is the zombie's *standing* pose
    on a 1.25 s loop, and the server stopped publishing a hurt state (see
    ZombieEntity.walkOrEat) because holding it froze a walking zombie for an eighth of a
    second on every pea.

    ``swim`` is the floatie zombies' water gait and only they get it: ``anim_swim`` is the
    same body with the legs hidden, the tube's *in-water* drawing swapped in (the tube track
    switches image per frame, so the swap is already in the art) and a whitewater wake at the
    waterline. The original switches to it in code while a ducky-tube zombie floats in a pool
    - see ``FloatCapability`` - which is exactly why one that only ever walks looks like it is
    walking on the surface.
    """
    clips: Dict[str, Dict[str, object]] = {
        "idle": {"mask": "anim_idle", "loop": True},
    }
    if walk:
        clips["walk"] = {"mask": "anim_walk", "loop": True,
                         "reference_speed": ZOMBIE_WALK_REFERENCE_SPEED}
    if swim:
        clips["swim"] = {"mask": "anim_swim", "loop": True,
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
        #
        # And the glows are held back to 70% of their authored alpha: added at full strength
        # on top of a yellow core they clip to white, which is what "the sun is a white blob"
        # was. The remaining 30% is what the core's own colour needs to stay readable through
        # them; the rest of the fix is the resource's own warm `tint` (see resources/sun.json).
        additive_images=r"SUN2|SUN3",
        additive_alpha_scale=0.7,
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
    # Projectiles
    #
    # One, and it is the one the original draws as an animation rather than as a sprite: every
    # other shot in the game is a single PNG (`ProjectilePea.png`, `ProjectileSnowPea.png`, ...),
    # which is why their content definitions point straight at a texture. A pea that has been
    # through a torchwood is a body with a flame and three sparks over it, so its `FirePea.reanim`
    # is the whole of its art.
    #
    # The file has no `anim_*` mask tracks at all - all five tracks are drawn for all 25 frames -
    # so the clip is `range: all` rather than a mask, and the same 25 frames answer to both state
    # names the runtime asks for: a shot that is simply flying (`idle`) and one that is burning
    # (`lit`, which is what a torched pea is set to and the name its own definition binds it to).
    # ------------------------------------------------------------------
    EntityConfig(
        output="fire_pea",
        group="projectile",
        reanim="FirePea.reanim",
        target_box=(0.24, 0.24),
        # The pea is the part that has to be the size of a pea. The flame is drawn wider than the
        # body and the sparks fly off it, so measuring the whole model would fit the *fire* to a
        # cell and leave a pea two thirds the size of the one next to it.
        measure_exclude_regex=r"flame|spark",
        animations={
            "idle": {"range": "all", "loop": True},
            "lit": {"range": "all", "loop": True},
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
        # The idle is slowed so one puff-up fills the one-second fuse; the explode is NOT, so the
        # burst stays the original's half second. See CHERRY_BOMB_ANIMATION_RATE for both halves
        # of the arithmetic.
        animations={
            "idle": {"mask": "anim_idle", "loop": True, "rate": CHERRY_BOMB_ANIMATION_RATE},
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
        # The walnut's two cracked drawings. They are referenced by no track in `Wallnut.reanim`
        # - the original swaps them in code as the nut is chewed - so without this the plant was
        # drawn whole from full health to zero, which is the reported "坚果墙被啃了一部分后，外观
        # 没有变化". The client picks one member of the family from the plant's synced health; see
        # `PlantDef#damageTextures`.
        damage_states={
            "body": (("cracked_1", "Wallnut_cracked1.png"),
                     ("cracked_2", "Wallnut_cracked2.png")),
        },
    ),
    # ------------------------------------------------------------------
    # World 3 and 4, second half
    #
    # Nine plants, added in one go. Each is its own reanim with its own masks; the notes below
    # are only about the ones whose conversion is not the plain "idle + attack" shape.
    # ------------------------------------------------------------------
    # The spikeweed: `Caltrop.reanim` is the plant the original names Spikeweed, and the file
    # that draws it. It has an `anim_attack` mask - the spikes jab - which the spike capability
    # asks for on every damage tick, so the plant visibly stabs what is standing on it.
    #
    # Fitted by *width*, unlike every other plant. `fit_height_only` (the default) fits the
    # model's drawn height to the box and lets the width fall out of the source's aspect, which
    # is right for a standing plant. The caltrop is a flat, wide mat - its drawn box is 83x36px,
    # an aspect of 2.3 - so fitting its height blew it up 2.1x and it was drawn 1.8 cells wide,
    # spilling into both neighbours. Fitting both axes to a box a cell across keeps the body at
    # about the one cell the original draws it at. See `docs/踩坑清单.md`.
    EntityConfig(
        output="spikeweed",
        group="plant/special",
        reanim="Caltrop.reanim",
        target_box=(0.9, 0.9),
        fit_height_only=False,
        animations={
            "idle": {"mask": "anim_idle", "loop": True},
            "attack": {"mask": "anim_attack", "loop": False, "on_end": "idle",
                       "transition": 0.05},
        },
    ),
    # The torchwood: a stump with a flame, and nothing else. Its whole behaviour is what it
    # does to a pea that flies through it, which the plant itself does not animate.
    EntityConfig(
        output="torchwood",
        group="plant/special",
        reanim="Torchwood.reanim",
        target_box=PLANT_BOX,
        animations={"idle": {"mask": "anim_idle", "loop": True}},
    ),
    # The tall-nut: a wall-nut that is tall.
    #
    # Its two cracked drawings live in `Tallnut_cracked1/2.png`, referenced by no track - the
    # original swaps them in code as the nut is chewed - so they are declared as damage states
    # here, exactly like the wall-nut's. The comment this replaces claimed the two were exported
    # as part of the reanim's own blink masks, which they are not: nothing was drawn between full
    # health and zero.
    #
    # Its *size* is its own number rather than `PLANT_BOX`. Fitting the 146px-tall body to the
    # 0.76-cell box the wall-nut uses drew the tall-nut exactly as tall as the wall-nut, which is
    # the reported "高坚果体积不对": the original's tall-nut body is 146px against the wall-nut's
    # 100px, i.e. 1.46x its height.
    #
    # The pair below is `0.76 * (tallnut drawn box / tallnut body sprite)` per axis: the fit
    # divides by the *drawn* box, not by the sprite, and the two differ because the blinking eyes
    # widen it and the idle sway shortens it (119.824 x 84.672 source pixels against the sprite's
    # 146 x 99). Both axes are scaled by the same fraction, so the shape is the source's - this is
    # a size, not a stretch.
    EntityConfig(
        output="tall_nut",
        group="plant/defense",
        reanim="Tallnut.reanim",
        target_box=(0.623743, 1.093202),
        animations={"idle": {"mask": "anim_idle", "loop": True}},
        damage_states={
            "body": (("cracked_1", "Tallnut_cracked1.png"),
                     ("cracked_2", "Tallnut_cracked2.png")),
        },
    ),
    # The sea-shroom: a mushroom that lives in the water. `anim_idle_aquarium` is the
    # Zen Garden's tank variant and `anim_waterline` is the foam at the water line, which the
    # *water* idle needs - so the ordinary idle is the mask that draws both.
    EntityConfig(
        output="sea_shroom",
        group="plant/attacker",
        reanim="SeaShroom.reanim",
        target_box=PLANT_BOX,
        animations={
            "idle": {"mask": "anim_idle", "loop": True},
            "sleep": {"mask": "anim_sleep", "loop": True},
            "shoot": {"mask": "anim_shooting", "loop": False, "on_end": "idle",
                      "transition": 0.1, "rate": SHOOT_ANIMATION_RATE},
        },
    ),
    # The plantern: a lamp on a stalk. `anim_face` is the lit face and `anim_idle` is the
    # sway, so the ordinary pair is all there is.
    EntityConfig(
        output="plantern",
        group="plant/special",
        reanim="Plantern.reanim",
        target_box=PLANT_BOX,
        animations={"idle": {"mask": "anim_idle", "loop": True}},
    ),
    # The blover: `anim_blow` is the gust, and it is a one-shot that ends back on the idle
    # loop. `anim_loop` is the same fan turning while it waits, which is the idle.
    EntityConfig(
        output="blover",
        group="plant/special",
        reanim="Blover.reanim",
        target_box=PLANT_BOX,
        animations={
            "idle": {"mask": "anim_loop", "loop": True},
            "shoot": {"mask": "anim_blow", "loop": False, "on_end": "idle",
                      "transition": 0.05},
        },
    ),
    # The starfruit: five-pointed, and its `anim_shoot` is one burst. Five shots leave on one
    # tick, so one clip is the whole gesture.
    EntityConfig(
        output="starfruit",
        group="plant/attacker",
        reanim="Starfruit.reanim",
        target_box=PLANT_BOX,
        animations={
            "idle": {"mask": "anim_idle", "loop": True},
            "shoot": {"mask": "anim_shoot", "loop": False, "on_end": "idle",
                      "transition": 0.1, "rate": SHOOT_ANIMATION_RATE},
        },
    ),
    # The pumpkin: a shell. One mask, one clip - what it *does* is stand around a plant and
    # take the bites meant for it, and the art has no gesture for that.
    EntityConfig(
        output="pumpkin",
        group="plant/defense",
        reanim="Pumpkin.reanim",
        target_box=PLANT_BOX,
        animations={"idle": {"mask": "anim_idle", "loop": True}},
    ),
    # The magnet-shroom: `anim_nonactive_idle` is the mushroom with nothing to pull (the
    # idle the player sees almost all the time - there is rarely metal on the lawn), and
    # `anim_shooting` is the pull itself. The `anim_eyes` family is the Zen Garden's
    # expression overlays and is deliberately not exported: nothing asks for it.
    EntityConfig(
        output="magnet_shroom",
        group="plant/special",
        reanim="Magnetshroom.reanim",
        target_box=PLANT_BOX,
        animations={
            "idle": {"mask": "anim_nonactive_idle", "loop": True},
            "shoot": {"mask": "anim_shooting", "loop": False, "on_end": "idle",
                      "transition": 0.1},
        },
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
                "rate": SHOOT_ANIMATION_RATE,
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
    # The pool's one plant that eats a zombie: it drags the first one that walks onto it under
    # the surface. `anim_grab` is that drag and ends held (the plant is gone the moment it has
    # pulled something down, and the frames after the grab in the source are the empty pot).
    EntityConfig(
        output="tangle_kelp",
        group="plant/environment",
        reanim="Tanglekelp.reanim",
        target_box=PLANT_BOX,
        animations={
            "idle": {"mask": "anim_idle", "loop": True},
            "grab": {"mask": "anim_grab", "loop": False, "on_end": "hold", "transition": 0.05},
        },
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
                "rate": SHOOT_ANIMATION_RATE,
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
                "rate": SHOOT_ANIMATION_RATE,
                "mask": "anim_shooting",
                "loop": False,
                "on_end": "idle",
                "transition": 0.1,
            },
            "sleep": {"mask": "anim_sleep", "loop": True, "transition": 0.1},
        },
    ),
    # Scaredy-shroom: the same little mushroom as the puff-shroom, with the one thing that
    # makes it a different plant - it ducks. The original files the duck as two masks: a
    # 13-frame `anim_scared` that puts its head down and a 11-frame `anim_scaredidle` that
    # holds it there, which is the same "entry then loop" pair the potato mine's arming is,
    # so the two are exported as two clips and the *client* chains them (`on_end:
    # hide_loop`). The server publishes only `hide`: it does not know how long a clip is,
    # and a state it has to time is a state that would break the day someone re-exports
    # the art.
    #
    # `anim_blink` (1..3) is drawn over the idle frames only and is not a clip of its own -
    # the blink is already part of every mask's frame data, exactly as it is for the other
    # mushrooms.
    EntityConfig(
        output="scaredy_shroom",
        group="plant/attacker",
        reanim="ScaredyShroom.reanim",
        target_box=SCAREDY_SHROOM_BOX,
        animations={
            "idle": {"mask": "anim_idle", "loop": True, "transition": 0.1},
            "shoot": {
                "rate": SHOOT_ANIMATION_RATE,
                "mask": "anim_shooting",
                "loop": False,
                "on_end": "idle",
                "transition": 0.1,
            },
            "hide": {
                "mask": "anim_scared",
                "loop": False,
                "on_end": "hide_loop",
                "transition": 0.05,
            },
            "hide_loop": {"mask": "anim_scaredidle", "loop": True, "transition": 0.1},
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
                "rate": SHOOT_ANIMATION_RATE,
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
    # Ice-shroom. The original's reanim has no blast mask at all: the mushroom shivers
    # (`anim_idle`, 4..20), then the *screen* freezes over - the effect is particles and a
    # white flash over the whole board, not a clip on the plant - and the mushroom is gone.
    # So the two clips here are the two poses it can be seen in, and the burst is drawn by
    # the capability's particles. A missing `explode` clip is not an omission: there is
    # nothing in the art to point one at.
    EntityConfig(
        output="ice_shroom",
        group="plant/special",
        reanim="IceShroom.reanim",
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
                "rate": SHOOT_ANIMATION_RATE,
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
                "rate": SHOOT_ANIMATION_RATE,
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
                "rate": SHOOT_ANIMATION_RATE,
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
        # Three heads are drawn from three timelines that never overlap: head 1's face lives on
        # frames 4..41, head 3's on 45..82, head 2's on 86..123, and the base idle pose - all
        # three heads and the whole stem - only on 124..148. No single frame range therefore ever
        # shows the plant, which is why the whole head group is forced visible standing still.
        #
        # The regex is matched with fullmatch against a bone's name, and a name is de-duplicated
        # when two images yield the same one: `ThreePeater_head1.png` is `head`, `..._head2.png`
        # is `head_2` and `..._head3.png` is `head_3`. A bare `(head|mouth|blink|face)` matches
        # only the FIRST head of each family, so the other two heads and every headleaf were
        # written out as `visible: false` for the whole idle clip and a standing threepeater drew
        # one head on three stems. `\w*` is what makes the pattern cover the family.
        force_visible_bones=r"(head|mouth|blink|face)\w*",
        animations={
            "idle": {"mask": "anim_idle", "loop": True, "transition": 0.1},
            # A full volley in one clip: the three shooting runs (29..41, 70..82, 111..123) laid
            # end to end, three 13-frame bursts with no dead time between them. Written as a
            # list of segments rather than one 29..123 span, because the gaps between those runs
            # belong to other phases of the same 149-frame file - playing the span would spend
            # two and a half of its three seconds on a plant with no head drawn.
            #
            # Every head is claimed by the window of the phase it fires in. Two things follow
            # from that claim, and together they are "a threepeater fires three times without
            # ever losing a head":
            #
            # * the head stays on screen for the whole clip. The source hides it while another
            #   head fires, because there its own timeline is the one running - read into one
            #   end-to-end clip, that turns the plant into a single head that sprouts and flies
            #   in, twice, on every volley;
            # * outside its own window it holds the idle pose. Its track sits at the unset pose
            #   (scale 1.0, off-model pixel) while another head is firing, and drawing *that* for
            #   the whole clip puts two faces on the same pixel - the "three heads stacked" the
            #   plant showed before the windows existed.
            #
            # `head_2` is head 3's face and `head_3` is head 2's: the suffixes come from the
            # order the images are discovered in, not from the heads' own numbering. The images
            # are identical bitmaps, so what a window has to get right is *which phase* a bone
            # belongs to, and that is what the masks say.
            "shoot": {
                # The three bursts laid end to end are 39 frames = 3.25 s at the file's 12fps,
                # and the plant fires every 90 ticks (1.5 s). At the line's usual 2x that is
                # 1.625 s - a volley that is still finishing when the next one is due, so the
                # clip is re-triggered partway through every single time. 3.25/1.5 = 2.1667
                # puts the last head's recoil on the tick the next volley leaves: the faster
                # rate is the plant's own cadence rather than a style choice, which is why it
                # is not `SHOOT_ANIMATION_RATE`.
                "rate": 2.1666667,
                "range": [[29, 41], [70, 82], [111, 123]],
                "loop": False,
                "on_end": "idle",
                "transition": 0.1,
                "force_visible_hidden": True,
                "force_visible_exclude_prefixes": ["blink"],
                "phase_windows": [
                    # Every pattern is anchored, because these are de-duplicated names and a
                    # prefix is not a head: `head` prefixes `head_2` and `head_3` (the other two
                    # faces), and `headleaf_1` prefixes `headleaf_1_2` and `headleaf_1_3` (two
                    # more leaves). An unanchored pattern puts two heads' faces up at once.
                    #
                    # Which leaf belongs to which head is the *source track's* answer, not the
                    # bone name's: the head art is three images repeated, so a bone's suffix
                    # counts the de-duplication, not the head. The frames each track's image
                    # starts on are the giveaway - head 1's parts appear at frame 1..4, head 3's
                    # at 42..45, head 2's at 83..86 - and the masks line up with them
                    # (`anim_face1` 4..41, `anim_face3` 45..82, `anim_face2` 86..123).
                    #
                    # Getting this wrong is quiet: a leaf claimed by the wrong phase simply
                    # holds the idle pose while its own head fires, which reads as "one leaf
                    # does not move" rather than as a missing part.
                    {"mask": "anim_shooting1",
                     "bones": r"headleaf_1$|head$|mouth$|blink_1$|blink_2$"},
                    {"mask": "anim_shooting3",
                     "bones": r"headleaf_3$|headleaf_2$|headleaf_1_2$|head_2$|mouth_2$"
                              r"|blink_1_2|blink_2_2"},
                    {"mask": "anim_shooting2",
                     "bones": r"headleaf_2_2$|headleaf_1_3$|head_3$|mouth_3$|blink_1_3|blink_2_3"},
                ],
            },
        },
        # Each head rides the stem it grows out of. The head art's own tracks are a *flat* loop -
        # `anim_face1/2/3` do not move a pixel across the whole idle, because in the original the
        # heads are drawn by the stems' motion - so without this the stems sway and the three heads
        # hang in the air: the reported "三线射手 idle 时只有身体在摆动，头没有跟着动".
        #
        # Which head belongs to which stem is measured from the art, not guessed: at the idle's rest
        # frame the head faces sit at x=50.5/33.0/15.2 and the stems' drawn bodies at x=27.2/23.1/
        # 19.1, and a head's bottom edge lands on exactly one of them (face2 on stem3 within half a
        # pixel; the other two within 13, against 23 for the next candidate). `head` is head 1,
        # `head_2` is head 3's face and `head_3` is head 2's - the suffixes count the deduplication
        # order of the images, not the plant's own numbering (see the phase windows above).
        bone_parents={
            "head": "stem_1",
            "head_2": "stem_2",
            "head_3": "stem_3",
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
                "rate": SHOOT_ANIMATION_RATE,
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
                "rate": SHOOT_ANIMATION_RATE,
                "mask": "anim_shooting",
                "loop": False,
                "on_end": "idle",
                "transition": 0.1,
                "force_visible_hidden": True,
                "force_visible_exclude_prefixes": ["blink"],
            },
            "shoot_high": {
                "rate": SHOOT_ANIMATION_RATE,
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
                "rate": SHOOT_ANIMATION_RATE,
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
                "rate": SHOOT_ANIMATION_RATE,
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
                "rate": SHOOT_ANIMATION_RATE,
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
            # The glance before the leap: the squash notices the zombie that tripped it and
            # turns its eyes that way first. One clip per side, because the art has one per
            # side (the server picks by which side the target is on).
            "look_left": {"mask": "anim_lookleft", "loop": False, "on_end": "hold",
                          "transition": 0.05},
            "look_right": {"mask": "anim_lookright", "loop": False, "on_end": "hold",
                           "transition": 0.05},
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
    # The pool's three floatie zombies: the plain, cone and bucket bodies, each with the
    # ducky tube left in. They are separate content ids rather than a per-row variant of the
    # land zombies because that is what a wave table needs to say: "these four in the water
    # rows, those four on the grass". See `DUCKY_*_EXCLUDE` above.
    EntityConfig(
        output="ducky_tube_zombie",
        group="zombie/basic",
        reanim="Zombie.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        exclude_image_regex=DUCKY_BASE_EXCLUDE,
        force_hidden_bones=r"tongue|hair",
        animations=zombie_animations(swim=True),
    ),
    EntityConfig(
        output="ducky_tube_conehead_zombie",
        group="zombie/armored",
        reanim="Zombie.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        exclude_image_regex=DUCKY_CONE_EXCLUDE,
        force_hidden_bones=r"tongue|hair",
        measure_exclude_regex=r"^cone_",
        damage_states={
            "cone_1": (("cone_2", "Zombie_cone2.png"),
                       ("cone_3", "Zombie_cone3.png")),
        },
        animations=zombie_animations(angry=True, swim=True),
    ),
    EntityConfig(
        output="ducky_tube_buckethead_zombie",
        group="zombie/armored",
        reanim="Zombie.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        exclude_image_regex=DUCKY_BUCKET_EXCLUDE,
        force_hidden_bones=r"tongue|hair",
        measure_exclude_regex=r"^bucket_",
        damage_states={
            "bucket_1": (("bucket_2", "Zombie_bucket2.png"),
                         ("bucket_3", "Zombie_bucket3.png")),
        },
        animations=zombie_animations(angry=True, swim=True),
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
    # The football zombie's helmet is the original's second piece of head armour, and its
    # reanim ships the three drawings (`Zombie_football_helmet{,2,3}.png`) — only the first
    # is referenced by a track, and the original swaps in the other two in code as the
    # helmet wears, exactly like the bucket. So the helmet becomes a damage-state family
    # (`zombie_football_helmet_1..3`) and the definition wears it through them.
    #
    # Its `zombie_football_upperbody2/3` tracks are *body* damage states (the jersey with
    # the shoulder pad gone, then torn), which this project does not model for any zombie:
    # limb loss is `drops_arm`/`drops_head` here and the body is drawn whole. They are
    # dropped by track so the three bodies do not all draw at once.
    EntityConfig(
        output="football_zombie",
        group="zombie/armored",
        reanim="Zombie_football.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        # Every clip in this file is its own: the shared master's idle/eat/death masks are
        # not in it, so the walk is declared with the speed it was drawn for (the football
        # zombie is the fast one, 0.43 cells/s).
        exclude_track_regex=r"zombie_football_upperbody[23]",
        measure_exclude_regex=r"^helmet_1$",
        bone_renames={"helmet": "helmet_1"},
        damage_states={
            "helmet_1": (
                ("helmet_2", "Zombie_football_helmet2.png"),
                ("helmet_3", "Zombie_football_helmet3.png"),
            ),
        },
        animations=zombie_animations(walk=False, all_deaths=False) | {
            "walk": {"mask": "anim_walk", "loop": True, "reference_speed": 0.43},
        },
    ),
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
    # The bungee zombie: it never walks. Its five masks are the whole life cycle - wait, drop,
    # grab, hold, rise - and every one of them is a state the server publishes, so the clip names
    # are the state names rather than anything the animation system has to guess at.
    #
    # `anim_idle` is what it does while it hangs off the top of the screen deciding where to go,
    # and it is also the closest thing the file has to a "being carried" pose, so it is exported
    # under both names the server can ask for.
    EntityConfig(
        output="bungee_zombie",
        group="zombie/special",
        reanim="Zombie_bungi.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        animations={
            "idle": {"mask": "anim_idle", "loop": True},
            "walk": {"mask": "anim_idle", "loop": True},
            "bungee_drop": {"mask": "anim_drop", "loop": False, "on_end": "hold",
                            "transition": 0.05},
            "bungee_grab": {"mask": "anim_grab", "loop": False, "on_end": "hold",
                            "transition": 0.05},
            "bungee_hold": {"mask": "anim_hold", "loop": True},
            "bungee_rise": {"mask": "anim_raise", "loop": False, "on_end": "hold",
                            "transition": 0.05},
        },
    ),
    # The zamboni: a driver on a machine, and the machine is the thing that matters. Three masks
    # over 30fps - driving, and two wheelie poses - because the original animates the ice machine
    # rather than the zombie steering it.
    #
    # `anim_drive` is the whole of its motion, so `walk` and `drive` are the same clip; the
    # wheelies are the two states it plays while it is crushing something, which the capability
    # asks for by name.
    EntityConfig(
        output="zamboni_zombie",
        group="zombie/special",
        reanim="Zombie_zamboni.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        animations={
            "idle": {"mask": "anim_drive", "loop": True},
            "walk": {"mask": "anim_drive", "loop": True},
            "drive": {"mask": "anim_drive", "loop": True},
            "wheelie": {"mask": "anim_wheelie1", "loop": False, "on_end": "walk",
                        "transition": 0.05},
        },
        # The machine's own two damaged drawings. `Zombie_zamboni_1_damage1/2.png` are referenced by
        # no track - the original swapped them in by health - so without this a zomboni is drawn
        # pristine from full health to the tick it explodes, which is the reported "冰车也要有破损
        # 痕迹". The client picks one from the zombie's synced health (see `EquipmentDef` with no
        # `piece`: a family with more than one drawing wears through the original's thirds).
        damage_states={
            # The machine is drawn in four numbered slices, and it is `Zombie_zamboni_1` that has
            # the two damaged drawings. That piece's name after the conversion is the bare bone `1`
            # (the entity prefix is stripped), which `bone_renames` turns into `zamboni_body`
            # *before* the damage states are applied - so both the host and the family are named
            # the way the runtime will look them up.
            "zamboni_body": (("zamboni_body_damage1", "Zombie_zamboni_1_damage1.png"),
                             ("zamboni_body_damage2", "Zombie_zamboni_1_damage2.png")),
        },
        bone_renames={"1": "zamboni_body"},
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
    # The pool's two swimming zombies. Both files carry a submerged gait of their own, which is
    # the whole reason they need to be converted rather than borrowed:
    #
    #   * the snorkel zombie's `anim_swim` range draws ONLY the head-at-the-waterline sprite and
    #     its wake (verified against the file: one track plus the whitewater), so "underwater"
    #     is a clip rather than a height offset - which is why `pvzce:submerge` can publish a
    #     state and be done;
    #   * the dolphin rider's `anim_walkdolphin` is the ride and `anim_walk` is what is left of
    #     it after the dolphin is gone. The engine's vault asks for `run` before the hop and
    #     `walk` after it, so the two clips map onto those two states and `anim_dolphinjump` is
    #     the hop itself.
    EntityConfig(
        output="snorkel_zombie",
        group="zombie/special",
        reanim="Zombie_snorkle.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        # 0.2 cells/s: the original's snorkel is the slow one of the pool, and both of its
        # gaits are drawn for that speed.
        animations=zombie_animations(walk=False, all_deaths=False) | {
            "walk": {"mask": "anim_walk", "loop": True, "reference_speed": 0.2},
            "swim": {"mask": "anim_swim", "loop": True, "reference_speed": 0.2},
            "idle": {"mask": "anim_idle", "loop": True},
        },
    ),
    # The bobsled team. Its file is the team's own gait and nothing else: `anim_walk` is the
    # slide, `anim_push` is the shove that starts it and `anim_jump` the spill, and `_ground`
    # marks which of them are on the ice (walk and push, not the spill) - so the ride and the
    # crash are told apart by the art rather than by a guess. The ride is what this project
    # plays, and its 47 frames are drawn for the 0.55 cells/s a sled slides at.
    #
    # The board the four sit on is the one piece of art the rip does not have: the original draws
    # it with the game rather than with the reanim, and there is no image for it anywhere under
    # `refer/`. `tools/gen_bobsled_sled.py` draws it new - see that file for what it is modelled
    # on - and it is declared here as an extra bone, so the sled is part of the zombie's own model
    # rather than a second entity whose position has to be kept in sync with the team. It is
    # measured *out* of the model, or four zombies shrink to fit a board longer than they are tall.
    #
    # The board rides `body_1`, which is the body of whichever rider this is: the team is four
    # bodies of this one definition, so the lead and the three behind it all draw the board, and
    # the overlapping copies land within a few centimetres of each other and read as one sled.
    # `hidden_bones` on the definition would remove it from a rider; nothing does, because the
    # alternative - one board on the lead only - is a fourth body that draws nothing extra and a
    # team that loses its board for the frame between a crash and the four walking apart.
    EntityConfig(
        output="bobsled_zombie",
        group="zombie/special",
        reanim="Zombie_bobsled.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        measure_exclude_regex=r"^sled$",
        animations=zombie_animations(walk=False, all_deaths=False) | {
            "walk": {"mask": "anim_walk", "loop": True, "reference_speed": 0.55,
                     "force_visible": r"^sled$"},
        },
        extra_bones={
            # `(bone, png, offset_y)`: the board hangs this far below the body bone it rides, and
            # the path is repository-relative because the sprite is not in `refer/`.
            "body_1": (("sled", "tools/art/bobsled_sled.png", -0.4917),),
        },
    ),
    EntityConfig(
        output="dolphin_rider_zombie",
        group="zombie/special",
        reanim="Zombie_dolphinrider.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        # 0.3 cells/s on the dolphin and on foot: the original's rider keeps its pace after the
        # hop, so one number covers both gaits.
        animations=zombie_animations(walk=False, all_deaths=False) | {
            "run": {"mask": "anim_walkdolphin", "loop": True, "reference_speed": 0.3},
            "walk": {"mask": "anim_walk", "loop": True, "reference_speed": 0.3},
            "jump": {"mask": "anim_dolphinjump", "loop": False, "on_end": "walk",
                     "transition": 0.05},
            "idle": {"mask": "anim_idle", "loop": True},
        },
    ),
    # The jack-in-the-box: it limps in winding, and it goes off. Its `pop` mask is the one clip
    # this zombie cannot do without - the server publishes `pop` by name for the whole of its
    # 110-tick fuse, and a file without that clip falls back to `idle`, which is an explosion
    # coming out of a body standing perfectly still. The rate is authored so the 16-frame lid
    # opening fills exactly those 110 ticks (16 / (12 * 0.7273) = 1.83 s), so the box is fully
    # open on the frame the blast lands.
    EntityConfig(
        output="jack_in_the_box_zombie",
        group="zombie/special",
        reanim="Zombie_jackbox.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        # 0.12 cells/s: the original's limp, half an ordinary walk, and the walk cycle is drawn
        # for it.
        animations=zombie_animations(walk=False, all_deaths=False) | {
            "walk": {"mask": "anim_walk", "loop": True, "reference_speed": 0.12},
            "pop": {"mask": "anim_pop", "loop": False, "on_end": "hold", "rate": 0.7273},
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
    # The pogo zombie: it bounces over everything, for ever, and the stick is the whole of it.
    # `anim_pogo` draws the body already up on the stick - `_ground` is false across all eleven
    # frames of it - so the clip is one bounce and the capability's own 80-tick cycle is the
    # other half; the rate below puts exactly one bounce on one cycle (11 / (12 * 0.6875) =
    # 1.33 s). It declares no `reference_speed` on purpose: the bounce is the stick compressing
    # rather than feet meeting the ground, and a locomotion scale would make it play four times
    # over while the zombie is sailing across a plant.
    #
    # `anim_walk` is what the zombie is left with after a tall-nut snaps the stick, and it is
    # exported because it then walks that clip for the rest of its life.
    EntityConfig(
        output="pogo_zombie",
        group="zombie/special",
        reanim="Zombie_pogo.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        animations=zombie_animations(walk=False, all_deaths=False) | {
            "walk": {"mask": "anim_walk", "loop": True, "reference_speed": 0.23},
            "pogo": {"mask": "anim_pogo", "loop": True, "rate": 0.6875},
        },
    ),
    # The dancing zombie and its backup dancers. Two files, one behaviour: the dancer
    # moonwalks in (`anim_moonwalk`, the original's fast entrance), raises his arms
    # (`anim_armraise`) and calls the dancers, then dances forward at a fifth of the speed.
    # The arm raise is exported as a one-shot that hands back to the walk, so the summon
    # has a face, and the moonwalk is its own clip because it is a different gait at a
    # different speed - the same pair of facts the pole vaulter's run/walk are.
    #
    # The art is `Zombie_disco.reanim`: that is the file the original draws its Dancing
    # Zombie from (the afro, the sunglasses and the white suit), and it is the one that
    # carries the moonwalk. `Zombie_Jackson.reanim` and `Zombie_dancer.reanim` in the rip
    # are the same character in its other two outfits and are not used.
    #
    # The backup file is authored at a different rate from the master's (24fps against 12),
    # which the converter reads per file - so the clip lengths are the originals' own and
    # the only number stated here is the ground speed each walk was drawn for: the
    # formation's 0.18 cells/s.
    EntityConfig(
        output="dancing_zombie",
        group="zombie/special",
        reanim="Zombie_disco.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        # No idle mask in this file either: one standing pose, and it is the walk.
        animations=zombie_animations(walk=False, all_deaths=False) | {
            "idle": {"mask": "anim_walk", "loop": True},
            "walk": {"mask": "anim_walk", "loop": True, "reference_speed": 0.18},
            "moonwalk": {"mask": "anim_moonwalk", "loop": True, "reference_speed": 0.67},
            "armraise": {"mask": "anim_armraise", "loop": False, "on_end": "walk",
                         "transition": 0.05},
        },
    ),
    EntityConfig(
        output="backup_dancer",
        group="zombie/special",
        reanim="Zombie_backup.reanim",
        target_box=ZOMBIE_BOX,
        fit_height_only=True,
        # Its hair and earring tracks are visible in no frame of the file (the dancers wear
        # the disco wigs their own sprites draw), so they are dropped rather than exported
        # as bones nothing can show.
        exclude_track_regex=r"^(anim_hair|anim_earing)$",
        animations=zombie_animations(walk=False, all_deaths=False) | {
            "idle": {"mask": "anim_walk", "loop": True},
            "walk": {"mask": "anim_walk", "loop": True, "reference_speed": 0.18},
            "armraise": {"mask": "anim_armraise", "loop": False, "on_end": "walk",
                         "transition": 0.05},
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
    # A tool card needs one static sprite, and Hammer.reanim is where the original's is;
    # the card draws the first model part, not a clip.
    #
    # The two clips are that file's `anim_whack_zombie` range cut in half. Read as one
    # 9-frame cycle it starts at the raised pose, lands the blow on its second frame and
    # lifts back to where it started - so the swing is the whole range played once, and
    # the pose it is held in between swings is the last frame of it. The range's other
    # half (`anim_open_pot`) is the Zen Garden's pot-opening flourish and is not exported.
    #
    # `rate` is the original's own swing speed rather than the file's 12fps: the whole blow
    # is over in a third of a second there, and at authoring speed the mallet hung in the air
    # for three quarters of one, which reads as a swing through treacle in a level that asks
    # the player to hit a zombie every second. See HAMMER_SWING_RATE.
    # ------------------------------------------------------------------
    EntityConfig(
        output="hammer",
        group="tool",
        reanim="Hammer.reanim",
        target_box=(0.5, 0.5),
        animations={
            "idle": {"mask": "anim_whack_zombie", "range": [8, 8], "loop": True},
            "attack": {"mask": "anim_whack_zombie", "loop": False, "on_end": "idle",
                       "transition": 0.05, "rate": HAMMER_SWING_RATE},
        },
    ),
    # The watering can: the tool 3-4 hands over, drawn from the Zen Garden's own reanim. Two
    # clips, because the source file has two: `anim_water` pours on one pot and
    # `anim_water_area` sweeps across several (the golden can's animation, which the original
    # only reaches for once the garden is upgraded). The sweep is what a level's watering uses -
    # the player is watering a plant on a lawn, not a single pot in a greenhouse - and `idle`
    # is one frozen frame of the pour, the pose the cursor holds between clicks.
    EntityConfig(
        output="watering_can",
        group="tool",
        reanim="ZenGarden_wateringcan.reanim",
        target_box=(0.5, 0.5),
        animations={
            "idle": {"mask": "anim_water", "range": [7, 7], "loop": True},
            "attack": {"mask": "anim_water_area", "loop": False, "on_end": "idle",
                       "transition": 0.05},
        },
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
    # The pool's cleaner: the same mechanic as a lawn mower, in a water row. Its reanim has
    # four masks - on land, floating in water, cleaning in water, cleaning on land - and the
    # pool uses the middle two.
    #
    # The parked pose is `anim_land`, the whole 11-frame range: the original's cleaner waits ON
    # THE POOLSIDE (the kerb the shared mower anchor already points at) with its wheels down and
    # its funnel swaying, and that range is where both are drawn - `body` (the wheeled body, not
    # the in-water sliver), the four wheel tracks, and the funnel turning through about five
    # degrees while the machine bobs. Its wheels do not turn there (`kx` is 0 all the way across),
    # so looping the whole range parks it rather than spinning it in place. `anim_water` - no
    # wheels, the in-water body, the whitewater wake - is the *driving* pose, which is what
    # `anim_suck` already gives the roll.
    EntityConfig(
        output="pool_cleaner",
        group="mechanic",
        reanim="PoolCleaner.reanim",
        target_box=(1.0, 0.62),
        animations={
            "idle": {"mask": "anim_land", "loop": True, "transition": 0.05},
            "drive": {"mask": "anim_suck", "loop": True, "transition": 0.05},
        },
    ),
    # The rake: the shop item, and the only fixture on the board that is not a machine.
    #
    # Its reanim has no `anim_*` mask tracks at all - it is two layer tracks and nothing else,
    # because in the original it is never animated. So the clip is declared by range rather
    # than by mask, which is the converter's supported way of saying "the whole file, no
    # phases". One cell wide and about half a cell tall, which is how it sits on the lawn.
    EntityConfig(
        output="rake",
        group="mechanic",
        reanim="Rake.reanim",
        target_box=(1.0, 0.5),
        animations={
            "idle": {"range": "all", "loop": True, "transition": 0.05},
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


def unique_bone_name(base: str, taken: Set[str]) -> str:
    """A bone name that no other bone in this model already uses.

    The suffix counter used to be keyed *per base* (``hand`` -> ``hand``, ``hand_2``;
    ``hand_2`` -> ``hand_2`` as well), so a model whose parts canonicalize to both a name and
    that name plus a digit produced two bones called ``hand_2``. The controller parser rejects
    a duplicate bone outright, which is why the bungee zombie drew as the missing-texture tile
    from the day it was converted. Counting against every name handed out already - not just
    the same base - is the whole fix.
    """

    if base not in taken:
        taken.add(base)
        return base
    suffix = 2
    while f"{base}_{suffix}" in taken:
        suffix += 1
    name = f"{base}_{suffix}"
    taken.add(name)
    return name


def rename_bones(bones: Sequence[core.Bone], input_path: Path) -> List[core.Bone]:
    taken: Set[str] = set()
    renamed: List[core.Bone] = []
    for bone in bones:
        base = bone_name_for(bone.asset.ref, input_path)
        name = unique_bone_name(base, taken)
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

    taken: Set[str] = set()
    bones: List[core.Bone] = []
    for piece in pieces:
        base = bone_name_for(piece.asset.ref, input_path)
        name = unique_bone_name(base, taken)
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


def hierarchy_host(bones: Sequence[core.Bone], config: EntityConfig,
                   bone: core.Bone) -> Optional[core.Bone]:
    """The bone this one is parented to, or ``None``.

    <p>Looked up by name and validated loudly: a typo in ``bone_parents`` would otherwise be a
    model that quietly does not move, which is the exact symptom this field exists to fix.
    """
    host_name = config.bone_parents.get(bone.name)
    if host_name is None:
        return None
    host = next((candidate for candidate in bones if candidate.name == host_name), None)
    if host is None:
        raise SystemExit(
            f"{config.output}: bone_parents maps {bone.name!r} to {host_name!r}, "
            "which is not a bone")
    return host


def rename_declared_bones(bones: List[core.Bone], config: EntityConfig) -> None:
    """Applies ``bone_renames`` in place; see that field for why it exists."""

    for old, new in config.bone_renames.items():
        target = next((bone for bone in bones if bone.name == old), None)
        if target is None:
            raise SystemExit(f"{config.output}: bone_renames names {old!r}, which is not in the model")
        if any(bone.name == new for bone in bones):
            raise SystemExit(f"{config.output}: bone_renames would duplicate {new!r}")
        bones[bones.index(target)] = core.Bone(
            name=new,
            asset=target.asset,
            states=target.states,
            visibility=target.visibility,
            order=target.order,
            track_names=target.track_names,
        )


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


def load_damage_state_asset(input_dir: Path, file_name: str,
                            host: Optional[core.ImageAsset] = None) -> core.ImageAsset:
    """One damaged-equipment PNG, by file name next to the reanim that uses it.

    <p>A damage state that is a *shorter* drawing than the sprite it replaces is padded back up to
    it, centred. The bone carries no translation of its own - it copies the host's keys - so the
    drawn position is the image's own centre, and a state two pixels short is a state drawn one
    pixel low. ``Tallnut_cracked2.png`` is exactly that: 99x144 against the body's 99x146. Padding
    is written next to the generated part textures (the ``refer/`` copy is never edited) and is the
    same picture, so nothing about the art changes - only which pixel is its middle.
    """

    source = input_dir / file_name
    if not source.is_file():
        raise SystemExit(f"Missing damage-state image: {source}")
    width, height = core.read_png_size(source)
    if host is None or (width == host.width and height == host.height):
        return core.ImageAsset(ref=file_name, source=source, width=width, height=height)
    if width > host.width or height > host.height:
        raise SystemExit(
            f"Damage-state image {file_name} is larger than the sprite it replaces "
            f"({width}x{height} against {host.width}x{host.height}); it would not be centred")
    from PIL import Image  # noqa: PLC0415 - only needed by this branch

    padded_dir = REPO_ROOT / "build" / "damage_states"
    padded_dir.mkdir(parents=True, exist_ok=True)
    padded = padded_dir / file_name
    canvas = Image.new("RGBA", (host.width, host.height), (0, 0, 0, 0))
    with Image.open(source) as art:
        canvas.alpha_composite(art.convert("RGBA"),
                               ((host.width - width) // 2, (host.height - height) // 2))
    canvas.save(padded)
    return core.ImageAsset(ref=file_name, source=padded, width=host.width, height=host.height)


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
                    asset=load_damage_state_asset(input_dir, file_name, host.asset),
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


def apply_extra_bones(
    config: EntityConfig,
    bones: List[core.Bone],
    input_dir: Path,
    extra_bone_offsets: Dict[str, float],
) -> None:
    """Appends a bone per declared extra sprite, hidden in every clip.

    The twin of {@link apply_damage_states} with one difference that matters: the sprite is not a
    state of its host, it is a piece of the model the rip never drew. Its own PNG, its host's keys
    so it rides whatever the host does, and `hidden` so no clip has to mention it - a clip that
    wants it says so with `force_visible`.
    """

    for host_name, entries in config.extra_bones.items():
        host = next((bone for bone in bones if bone.name == host_name), None)
        if host is None:
            raise SystemExit(
                f"{config.output}: extra-bone host {host_name!r} is not in the model")
        for new_name, file_name, offset_y in entries:
            if any(bone.name == new_name for bone in bones):
                raise SystemExit(f"{config.output}: duplicate extra bone {new_name!r}")
            # A bare name is a PNG next to the reanim; a path with a separator is relative to the
            # repository. The second form is what an extra that the rip *does not have* needs - a
            # sprite the project draws itself lives in the repository, not in `refer/`, and
            # `refer/` is read-only by the rules in AGENTS.md.
            source = (REPO_ROOT / file_name) if "/" in file_name else (input_dir / file_name)
            if not source.is_file():
                raise SystemExit(f"Missing extra-bone image: {source}")
            width, height = core.read_png_size(source)
            bones.append(
                core.Bone(
                    name=new_name,
                    asset=core.ImageAsset(ref=file_name, source=source,
                                          width=width, height=height),
                    states=list(host.states),
                    visibility=[False] * len(host.states),
                    order=host.order,
                    track_names=[f"extra_bone:{host_name}"],
                    hidden=True,
                )
            )
            extra_bone_offsets[new_name] = offset_y


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
    """The clip's source frames as an inclusive ``(start, end)``.

    <p>Kept for the callers that only need the extent; a composite range reports its first and
    last frame, and {@link range_frames} is what a caller wants when it walks the clip.
    """
    frames = range_frames(spec, tracks)
    return frames[0], frames[-1]


def idle_first_frame(config: "EntityConfig", tracks: Sequence[core.Track]) -> Optional[int]:
    """The source frame the `idle` clip starts on, or ``None`` when there is no idle clip.

    <p>The pose a rescued bone borrows. Read from the config rather than assumed to be frame 0:
    several entities' idle masks begin partway into the file (the threepeater's begins at 124),
    and borrowing frame 0 there would substitute one unset pose for another.
    """
    idle = config.animations.get("idle")
    if idle is None:
        return None
    frames = range_frames(idle, tracks)
    return frames[0] if frames else None


def range_frames(spec: Dict[str, object], tracks: Sequence[core.Track]) -> List[int]:
    """The source frames a clip plays, in order.

    <p>A single ``[from, to]`` is played straight through. A <em>list of pairs</em> is played one
    segment after another, which is how the original's parallel timelines are put back into one
    clip: the threepeater fires from three masks on frames 29..41, 70..82 and 111..123, and
    playing the whole span 29..123 would spend two and a half seconds of every three showing
    nothing at all - the gaps between those ranges belong to phases this clip is not.
    """
    if "range" in spec:
        value = spec["range"]
        if value == "all":
            frame_count = max(len(track.frames) for track in tracks)
            frames = list(range(0, frame_count))
        elif isinstance(value, (list, tuple)) and len(value) == 2 \
                and all(isinstance(bound, int) for bound in value):
            frames = list(range(int(value[0]), int(value[1]) + 1))
        elif isinstance(value, (list, tuple)):
            frames = []
            for segment in value:
                if not isinstance(segment, (list, tuple)) or len(segment) != 2:
                    raise SystemExit(f"Invalid range segment: {segment!r}")
                # Each segment is inclusive, like a single range is.
                frames.extend(range(int(segment[0]), int(segment[1]) + 1))
        else:
            raise SystemExit(f"Invalid range spec: {value!r}")
    else:
        start, end = mask_range(tracks, str(spec["mask"]))
        frames = list(range(start, end + 1))
    # trim_end exists because a mask range can end on a frame that repeats the one before it.
    # That is a legitimate authoring choice for a one-shot (the pose is held) and a visible
    # stall for a loop, and only the clip's author knows which this is - the length maths
    # cannot tell a deliberate hold from a mistake.
    trim = int(spec.get("trim_end", 0))
    if trim > 0:
        frames = frames[: max(1, len(frames) - trim)]
    if not frames:
        raise SystemExit("A clip's range selected no frames")
    return frames


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


def build_phase_windows(
    spec: Dict[str, object],
    tracks: Sequence[core.Track],
    fps: float,
    frames: Sequence[int],
    config: EntityConfig,
) -> List[Tuple["re.Pattern", int, int, str]]:
    """Compiles a clip's ``phase_windows`` into ``(bone pattern, from, to)``.

    <p>A window names a mask track and the bones that belong to it, and means "this is the
    stretch of the clip in which these bones act" - in the clip's own frame numbering, so a
    window written against a composite ``range`` lines up with the phase it names. A bone a
    window claims is drawn for the <em>whole</em> clip and borrows the idle pose outside its own
    stretch; see ``build_animation`` for why that is the pair that has to go together.

    This exists because a reanim can split one character across several masks that never overlap,
    and the converter's other two visibility knobs cannot express "in turn":

    * ``force_visible_bones`` forces a bone on for the whole clip, which is what a standing pose
      wants and what the threepeater's three heads need for ``idle``;
    * ``force_visible_hidden`` only rescues bones hidden in *every* frame, so it cannot say
      "head 2 acts during head 2's turn and not during head 1's".

    Without it, a clip built over a composite range draws every phase at once: the threepeater's
    ``shoot`` had all three heads firing on top of each other, at the same position, in the first
    volley's window.
    """
    raw = spec.get("phase_windows")
    if raw is None:
        return []
    if not isinstance(raw, (list, tuple)):
        raise SystemExit(f"{config.output}/{spec.get('mask') or spec.get('range')}:"
                         " phase_windows must be a list")
    windows: List[Tuple["re.Pattern", int, int, str]] = []
    for entry in raw:
        if not isinstance(entry, dict) or "mask" not in entry or "bones" not in entry:
            raise SystemExit(f"{config.output}: each phase window needs 'mask' and 'bones'")
        mask_name = str(entry["mask"])
        track = next((candidate for candidate in tracks if candidate.name == mask_name), None)
        if track is None:
            raise SystemExit(f"{config.output}: phase window names mask {mask_name!r},"
                             " which is not in the reanim")
        ranges = core.visible_ranges(track)
        if not ranges:
            raise SystemExit(f"{config.output}: phase window mask {mask_name!r} is never"
                             " visible, so the bones it claims would never be drawn")
        mask_start, mask_end = ranges[0]
        trim = int(entry.get("trim_end", 0))
        if trim > 0:
            mask_end = max(mask_start, mask_end - trim)
        if mask_start not in frames or mask_end not in frames:
            raise SystemExit(f"{config.output}: phase window {mask_name!r} covers source"
                             f" frames {mask_start}..{mask_end}, which the clip's own range does"
                             " not play; the window would never be on")
        try:
            pattern = re.compile(str(entry["bones"]), re.IGNORECASE)
        except re.error as exc:
            raise SystemExit(f"{config.output}: phase window {mask_name!r} has an invalid"
                             f" bone pattern: {exc}") from exc
        windows.append((pattern, frames.index(mask_start), frames.index(mask_end), mask_name))
    return windows


def window_for(
    windows: Sequence[Tuple["re.Pattern", int, int, str]],
    bone_name: str,
) -> Optional[Tuple[int, int]]:
    """The clip positions a bone's own phase covers, or ``None`` when no window claims it.

    <p>Matched with ``search`` against the bone's name, because the names a window has to reach
    are the de-duplicated ones: three heads drawn from three images become ``head``, ``head_2``
    and ``head_3``, and a pattern written the way an author thinks of them - ``head`` - has to
    cover all three. A bone belongs to exactly one phase, so two windows claiming it is an
    authoring mistake rather than a precedence question, and it is reported with both names.
    """
    claimed = [window for window in windows if window[0].search(bone_name)]
    if not claimed:
        return None
    if len(claimed) > 1:
        names = ", ".join(f"{window[3]!r}" for window in claimed)
        raise SystemExit(f"Bone {bone_name!r} is claimed by {len(claimed)} phase windows"
                         f" ({names}); a bone can only belong to one phase")
    _, first, last, _ = claimed[0]
    return first, last


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
    extra_bone_offsets: Optional[Dict[str, float]] = None,
) -> Dict[str, object]:
    extra_bone_offsets = extra_bone_offsets or {}
    frames = range_frames(spec, tracks)
    start, end = frames[0], frames[-1]
    frame_count = len(frames)
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
    windows = build_phase_windows(spec, tracks, fps, frames, config)

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

    # The frame of the `idle` clip this one borrows a pose from; see `rescued_pose_frame`.
    idle_pose_frame = idle_first_frame(config, tracks)

    for bone in bones:
        # An attached bone is drawn exactly when its host is: the original hangs the pole
        # off the hand, so the frame that hides the hand (the death clip) hides the flag
        # too rather than leaving it hovering over the collapsing body.
        source = host_bone if (attached_bones and bone.name in attached_bones) else bone
        permanently_hidden = not any(source.visibility[index] for index in frames)
        # A bone this clip never draws and that `force_visible_hidden` puts back on screen is
        # drawn in the pose the idle clip gives it, not in the pose its own track holds across
        # these frames. The two are not the same thing: a reanim that splits one character's
        # pose across several masks leaves the tracks of the parts it is not currently showing
        # at their *unset* values - scale 1.0 at the origin - and those values are only mean-
        # ingful to a renderer that is not drawing them. The threepeater is the case that got
        # this wrong: its nine leaf and stem tracks sit at scale 1.0 with no translation for
        # frames 0..123 and only reach their drawn pose at frame 124 (the base-idle mask), so
        # rescuing them made the whole plant balloon by 1.8x and jump outward the instant it
        # fired. A bone the clip *does* animate is untouched, which is why the pea-shooter -
        # whose leaves happen to be placed correctly throughout its shooting mask - comes out
        # of this unchanged.
        rescued = force_visible and permanently_hidden \
            and not excluded_from_rescue(bone.name, exclude_prefixes) \
            and idle_pose_frame is not None
        # The bone's own phase, in clip positions, when a phase window claims it.
        phase = window_for(windows, bone.name)

        def pose_frame(frame: int, position: int) -> int:
            """Which source frame this bone is drawn *as* at clip position ``position``.

            <p>A bone outside its own phase borrows the idle clip's pose, exactly like a
            rescued bone does and for the same reason: a reanim that splits one character
            across several masks leaves the tracks of the parts it is not currently showing
            at their unset values, and those values are only meaningful to a renderer that is
            not drawing them. The threepeater is the case: head 2 and head 3 sit at the same
            off-model pixel while another head fires, so a clip that simply forced them
            visible drew all three faces on top of each other.
            """
            if phase is not None:
                return frame if phase[0] <= position <= phase[1] else idle_pose_frame
            return idle_pose_frame if rescued else frame

        visibility: List[bool] = []
        for position, frame in enumerate(frames):
            is_visible = bool(source.visibility[frame])
            if force_visible and permanently_hidden and not excluded_from_rescue(bone.name, exclude_prefixes):
                is_visible = True
            if force_visible_re is not None and force_visible_re.fullmatch(bone.name):
                is_visible = True
            if phase is not None:
                # A bone one of the clip's phases claims is drawn for the whole clip: the
                # source only hides it while some *other* phase's timeline is the one
                # running, and a clip that lays those timelines end to end is not "a plant
                # with one head" - it is one plant firing three times. What moves is the
                # bone's pose (see ``pose_frame``), not whether the head is there.
                #
                # The phase counts in CLIP frames, which is what an author reads off the
                # exported clip; `position` is where this source frame sits in that clip.
                is_visible = True
            if force_hidden_re is not None and force_hidden_re.fullmatch(bone.name):
                is_visible = False
            if bone.hidden:
                # A sprite no track draws - a damage state, an extra bone - is off unless the
                # *clip* says otherwise. It is off by default so that no clip has to know about
                # it, and a clip that does say so is the one case where the sprite is part of
                # that clip's picture: the bobsled's board is drawn by the ride (`walk`) and not
                # by the death, and it is the same declaration either way. A config-level
                # `force_visible_bones` cannot resurrect one, which is what keeps the client's
                # own choice of damage state the only thing that turns a cracked nut on.
                is_visible = clip_visible_re is not None \
                    and clip_visible_re.search(bone.name) is not None
            visibility.append(is_visible)

        def key(position: int) -> str:
            return core.format_time(position / fps)

        weld = host_translation if (attached_bones and bone.name in attached_bones) else None
        # The host's rest position, which is the pivot a welded bone swings around.
        weld_pivot = weld(frames[0]) if weld is not None else None
        # The bone this one is *parented* to, which is a different statement from "welded": a
        # parented bone's keys are rewritten into its host's space, so the host's own animation
        # carries it. See `bone_parents` on EntityConfig.
        parent_bone = hierarchy_host(bones, config, bone)
        parent_frame = idle_pose_frame

        def parent_world(frame: int, position: int) -> Optional[List[float]]:
            if parent_bone is None:
                return None
            state_data = parent_bone.states[pose_frame_for(parent_bone, frame, position)]
            center_x, center_y = core.model_center_px(state_data, parent_bone.asset, bbox)
            return [center_x * scale, center_y * scale]

        def pose_frame_for(target: "core.Bone", frame: int, position: int) -> int:
            """``pose_frame`` for another bone.

            <p>A parented bone is posed by its host's frame, not by its own: the host is where the
            motion comes from, and a host that borrows the idle pose outside its window would drag
            its child somewhere the child's own track never goes.
            """
            phase = window_for(windows, target.name)
            rescued_target = (force_visible and not any(target.visibility[i] for i in frames)
                              and parent_frame is not None)
            if phase is not None:
                return frame if phase[0] <= position <= phase[1] else parent_frame
            return parent_frame if rescued_target else frame

        def translation_at(frame: int, position: int) -> List[float]:
            state_data = bone.states[pose_frame(frame, position)]
            center_x, center_y = core.model_center_px(state_data, bone.asset, bbox)
            value = [center_x * scale, center_y * scale]
            extra_offset = extra_bone_offsets.get(bone.name)
            if extra_offset is not None:
                # An extra bone hangs where its declaration says, in *model* units: the offset is
                # added after the fit's scale, because "half a cell below the body" is a statement
                # about the board and not about the source pixels it was drawn in.
                value[1] += extra_offset
            host_world = parent_world(frame, position)
            if host_world is not None:
                # Local space: base is the host's own origin, so the child's keys have to be the
                # child's world position *minus the host's*. `ControllerPlayback` multiplies the
                # parent's world matrix by the child's pose, so a child left in world coordinates
                # would be carried twice - once by its own keys and once by its parent.
                value[0] -= host_world[0]
                value[1] -= host_world[1]
                return [core.round_float(value[0]), core.round_float(value[1])]
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


        def rotation_at(frame: int, position: int) -> List[float]:
            # The piece's own drawn shear, untouched. It already carries the same swing the host
            # does - the art was drawn as one animation - so tilting it by the host's delta
            # rotates the pole twice about a pivot it is already positioned against.
            #
            # A parented bone is the exception: its host's rotation is *already applied* by the
            # playback, so the local key is the difference. The threepeater's heads are the case -
            # the stems do not rotate in the idle, but a bone parented to one that did would spin
            # twice otherwise.
            state_data = bone.states[pose_frame(frame, position)]
            kx, ky = state_data.kx, state_data.ky
            if parent_bone is not None:
                host_state = parent_bone.states[pose_frame_for(parent_bone, frame, position)]
                kx -= host_state.kx
                ky -= host_state.ky
            return [core.round_float(kx), core.round_float(ky), 0.0]

        def scale_at(frame: int, position: int) -> List[float]:
            state_data = bone.states[pose_frame(frame, position)]
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
            last_key_position: Optional[int] = None
            last_key_value: Optional[List[float]] = None
            # Positions, not frames: a clip's frames are not necessarily consecutive (a
            # composite range is several runs laid end to end), and both the key's time and the
            # "is this a gap worth closing with a hold key" question are about the clip.
            for position, frame in enumerate(frames):
                value = value_at(frame, position)
                if last_value is None or not values_close(value, last_value):
                    if (last_key_position is not None and last_key_position < position - 1
                            and last_key_value is not None):
                        keys[key(position - 1)] = list(last_key_value)
                    keys[key(position)] = list(value)
                    last_key_position = position
                    last_key_value = value
                last_value = value
            return keys

        def boolean_keys() -> Dict[str, bool]:
            keys: Dict[str, bool] = {}
            last_value: Optional[bool] = None
            for position in range(len(frames)):
                value = visibility[position]
                if last_value is None or value != last_value:
                    keys[key(position)] = value
                last_value = value
            return keys

        def alpha_keys() -> Dict[str, float]:
            """The track's alpha, but only when the art actually varies it.

            Every reanim frame carries an alpha and almost every one of them is 1, so
            emitting the channel unconditionally would add a constant block to all 2688
            bone tracks in the shipped set to say nothing. A bone whose alpha never leaves
            1 omits the channel, and the runtime's default (1) is already the answer.

            ``additive_alpha_scale`` is applied *before* that test, so a glow whose source
            alpha is a flat 1 still gets the channel it needs to be held back.
            """
            scale = (config.additive_alpha_scale
                     if additive_search(config, bone.asset.ref) else 1.0)
            values = [bone.states[frame].a * scale for frame in frames]
            if all(abs(value - 1.0) <= 1e-6 for value in values):
                return {}
            keys: Dict[str, float] = {}
            last: Optional[float] = None
            for position in range(len(frames)):
                value = values[position]
                if last is None or abs(value - last) > 1e-6:
                    keys[key(position)] = core.round_float(value)
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
        "animation_length": round(max(1e-6, loop_frames(bones, start, end, loop, frames)) / fps, 10),
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


def loop_frames(bones: Sequence[core.Bone], start: int, end: int, loop: bool,
                frames: Optional[Sequence[int]] = None) -> int:
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
    if frames is not None:
        played = list(frames)
    else:
        played = list(range(start, end + 1))
    if not loop:
        return len(played)
    steps: List[float] = []
    seam = 0.0
    for bone in bones:
        if end + 1 > len(bone.states) or start + 1 > len(bone.states):
            continue
        if bone.states[start].image is None:
            continue
        for index in range(len(played) - 1):
            steps.append(planar_step(bone.states[played[index]], bone.states[played[index + 1]]))
        seam = max(seam, planar_step(bone.states[played[-1]], bone.states[played[0]]))
    if not steps:
        return len(played) - 1
    steps.sort()
    median = steps[len(steps) // 2]
    return len(played) - 1 if seam <= max(median * 1.5, 1e-3) else len(played)


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
    extra_bone_offsets: Optional[Dict[str, float]] = None,
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
                "parent": config.bone_parents.get(bone.name, "root"),
                "pivot": [0.0, 0.0],
                "parts": [part],
            }
        )

    animations: Dict[str, object] = {}
    for state, spec in config.animations.items():
        animations[state] = build_animation(state, spec, bones, tracks, fps, scale, bbox, config,
                                            attached_bones or set(), extra_bone_offsets)

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
    # Before everything that looks a bone up by name: the fit, the damage states and the
    # controller all have to see the name the runtime will use.
    rename_declared_bones(bones, config)
    # Model-space y each extra bone hangs by, filled in as they are appended; see
    # `apply_extra_bones` and `translation_at`.
    extra_bone_offsets: Dict[str, float] = {}

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
    # After the damage states, so a piece declared as both is not appended twice, and before
    # the scale, for the same reason: neither is measured.
    apply_extra_bones(config, bones, input_dir, extra_bone_offsets)
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

    controller = build_controller_json(config, bones, tracks, fps, bbox, scale, drawn, attached_bones,
                                       extra_bone_offsets)

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
