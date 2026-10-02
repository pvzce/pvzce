#!/usr/bin/env python3
"""The original Plants vs. Zombies adventure levels, as data.

The forty-nine shipped adventure levels are not invented here: every wave table is a *replay* of the
original game's own wave generator. The original does not store waves anywhere - it stores four
tables and a loop, and this module is those four tables plus that loop, so that a reader can
check a level against the game it claims to be:

* ``gZombieWaves`` - how many waves each of the fifty adventure levels has;
* ``gZombieDefs`` - per zombie type: its point value, the level it is first seen on, the first
  wave it may be picked for, and the weight it is picked with;
* ``gZombieAllowedLevels`` - for every type and level, whether that type may appear at all. A
  type whose ``mPickWeight`` is zero is one the game never *picks*: the flag zombie (placed by
  the flag-wave rule), the ducky tube zombie (how a plain zombie is drawn once it is in the
  pool) and the backup dancer (summoned by the dancing zombie);
* ``Board::PickZombieWaves`` - the loop over the waves: a point budget that grows with the wave
  number, the flag waves that spend 2.5x and always carry a flag zombie, the waves a newly
  introduced zombie is guaranteed to appear in, and the final wave, which is guaranteed to
  contain every type the level allows.

Two more tables live here because they are per-level facts of the same kind: the award seed of
every level (``LawnApp::GetAwardSeedForLevel`` - "each area hands out eight plants, and two of
its levels hand out nothing") and the fog's left column (``Board::LeftFogColumn``).

Why a replay instead of a generator
-----------------------------------
The original rolls each wave out of its budget with the game's own RNG, so two plays of 3-6 are
two different sets of zombies. This project's levels are data - they are edited, saved, replayed
and asserted on - so the roll happens **once**, here, with a fixed seed, and the result is
written into the level file. Everything that is deterministic in the original is reproduced
exactly: the number of waves, where the flags are, the budget each wave spends, which types a
level allows at all, when a new type is guaranteed to appear, and what the final wave contains.

Changing ``LEVEL_SEED`` re-rolls every wave table; that is the only thing it does.

Run ``python3 tools/original_levels.py`` for a per-level summary of what the tables say.
"""

from __future__ import annotations

import random
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

# ---------------------------------------------------------------------------
# Pacing constants (`Board.cpp` / `ConstEnums.h`)
# ---------------------------------------------------------------------------

#: The countdown before the first wave of an ordinary adventure level, in ticks.
ZOMBIE_COUNTDOWN_FIRST_WAVE = 1800
#: The countdown between two waves, plus up to ``ZOMBIE_COUNTDOWN_RANGE`` of jitter.
ZOMBIE_COUNTDOWN = 2500
ZOMBIE_COUNTDOWN_RANGE = 600
#: The countdown after a flag wave - the level takes a breath after a big one.
ZOMBIE_COUNTDOWN_BEFORE_FLAG = 4500
#: 1-2 is the tutorial's "more sun" level and waits 5000 ticks for its first zombie.
SECOND_LEVEL_FIRST_WAVE = 5000
#: A mini-boss level (`IsMiniBossLevel`: 1-10, 2-10, 3-10) opens almost immediately.
MINI_BOSS_FIRST_WAVE = 100
#: This project's own opening for a conveyor level (1-5, 2-5, 3-5, 4-10), in ticks.
#:
#: The original's 1800 is a *setup* wait: the player has a lawn to plant and a sun economy to
#: start, and the thirty seconds are what being ready costs. A conveyor level has neither - its
#: cards arrive off the belt by themselves and there is nothing to save up for - so the same
#: thirty seconds is half a minute of empty lawn with nothing to do. Ten seconds is enough to see
#: the belt arrive and place the first card, and it is what the user asked for after playing 1-5:
#: twenty was still "等半天等不到第一只僵尸". The three mini-boss levels keep the original's 100:
#: their opening is part of what makes them one.
CONVEYOR_FIRST_WAVE = 600
#: How long before a flag wave the game raises the banner (``mHugeWaveCountDown = 750``).
HUGE_WAVE_WARNING_TICKS = 750
#: Waves per flag for a level with ten waves or more; a shorter level flags its last wave only.
WAVES_PER_FLAG = 10
#: The original caps one wave at this many zombies.
MAX_ZOMBIES_IN_WAVE = 50
#: The seed the one-and-only roll uses. Change it and every table below changes.
LEVEL_SEED = 20260214
#: The floor this project's ``WaveDef.spawn_interval`` is clamped to (see ``WaveDirector``). The
#: original dumps a wave in a single tick; fifteen ticks apart is the closest a level file can
#: write to that, and it keeps a wave's lanes readable while it comes out.
SPAWN_INTERVAL = 15
#: Sun the player starts with: 150 on the first level (the tutorial), 50 everywhere else.
FIRST_LEVEL_SUN = 150
DEFAULT_SUN = 50

# ---------------------------------------------------------------------------
# ``gZombieDefs`` + ``gZombieAllowedLevels``, in the original's enum order
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class ZombieType:
    """One row of ``gZombieDefs``, joined with its row of ``gZombieAllowedLevels``."""

    #: The original's enum name, kept so a reader can grep the decompiled source for it.
    name: str
    #: What this project calls it. Empty when the project has no such card yet.
    repo_id: str
    #: ``mZombieValue``: what one of these costs out of a wave's point budget.
    value: int
    #: ``mStartingLevel``: the level it is first seen on - and therefore the level that
    #: introduces it (``GetIntroducedZombieType``).
    starting_level: int
    #: ``mFirstAllowedWave``: the earliest wave (one-based) it may be picked for.
    first_allowed_wave: int
    #: ``mPickWeight``: its share of the random picks; zero means "never picked at random".
    pick_weight: int
    #: ``gZombieAllowedLevels``, as fifty characters of "0"/"1", levels 1..50.
    levels: str

    def allowed_on(self, level: int) -> bool:
        return 1 <= level <= len(self.levels) and self.levels[level - 1] == "1"

    def pickable_on(self, level: int) -> bool:
        """Whether the level's *random* picks may draw this type.

        Both halves matter: a level the type is not allowed on, and a type the game only ever
        places by rule (``mPickWeight == 0``).
        """
        return self.pick_weight > 0 and self.starting_level <= level and self.allowed_on(level)


#: ``gZombieDefs`` order. The last five types only ever appear on the roof and in the boss fight,
#: and are listed for completeness - the forty shipped levels cannot draw them.
ZOMBIES: Tuple[ZombieType, ...] = (
    ZombieType("ZOMBIE_NORMAL", "basic_zombie", 1, 1, 1, 4000,
               "11111111111111111111111111111111111111111111111111"),
    ZombieType("ZOMBIE_FLAG", "flag_zombie", 1, 1, 1, 0,
               "11111111111111111111111111111111111111111111111111"),
    ZombieType("ZOMBIE_TRAFFIC_CONE", "conehead_zombie", 2, 3, 1, 4000,
               "00111111110111111111111111111111111111111111111111"),
    ZombieType("ZOMBIE_POLEVAULTER", "pole_vaulter_zombie", 2, 6, 5, 2000,
               "00000110110001100000000100001000000000000100000000"),
    ZombieType("ZOMBIE_PAIL", "buckethead_zombie", 4, 8, 1, 3000,
               "00000001110100100000010100101100000010110100100011"),
    ZombieType("ZOMBIE_NEWSPAPER", "newspaper_zombie", 2, 11, 1, 1000,
               "00000000001100100000010100000000000000000000000000"),
    ZombieType("ZOMBIE_DOOR", "door_zombie", 4, 13, 5, 3500,
               "00000000000011001011000000000000000000000000000000"),
    ZombieType("ZOMBIE_FOOTBALL", "football_zombie", 7, 16, 5, 2000,
               "00000000000000011001010010000001000000000001000000"),
    ZombieType("ZOMBIE_DANCER", "dancing_zombie", 5, 18, 5, 1000,
               "00000000000000000111000000000000000000000000000000"),
    ZombieType("ZOMBIE_BACKUP_DANCER", "backup_dancer", 1, 18, 1, 0,
               "00000000000000000111000000000000000000000000000000"),
    ZombieType("ZOMBIE_DUCKY_TUBE", "ducky_tube_zombie", 1, 21, 5, 0,
               "00000000000000000000000000000000000000000000000000"),
    ZombieType("ZOMBIE_SNORKEL", "snorkel_zombie", 3, 23, 10, 2000,
               "00000000000000000000001110100100000000000000000000"),
    ZombieType("ZOMBIE_ZAMBONI", "zamboni_zombie", 7, 26, 10, 2000,
               "00000000000000000000000001101100000000000000000000"),
    ZombieType("ZOMBIE_BOBSLED", "bobsled_zombie", 3, 26, 10, 2000,
               "00000000000000000000000001101100000000000000000000"),
    ZombieType("ZOMBIE_DOLPHIN_RIDER", "dolphin_rider_zombie", 3, 28, 10, 1500,
               "00000000000000000000000000011100010000000000000000"),
    ZombieType("ZOMBIE_JACK_IN_THE_BOX", "jack_in_the_box_zombie", 3, 31, 10, 1000,
               "00000000000000000000000000000011000010010000000011"),
    ZombieType("ZOMBIE_BALLOON", "balloon_zombie", 2, 33, 10, 2000,
               "00000000000000000000000000000000110000110000000000"),
    ZombieType("ZOMBIE_DIGGER", "miner_zombie", 4, 36, 10, 1000,
               "00000000000000000000000000000000000110010000000000"),
    ZombieType("ZOMBIE_POGO", "pogo_zombie", 4, 38, 10, 1000,
               "00000000000000000000000000000000000001110001000000"),
    ZombieType("ZOMBIE_YETI", "", 4, 40, 1, 1,
               "00000000000000000000000000000000000000000000000000"),
    ZombieType("ZOMBIE_BUNGEE", "bungee_zombie", 3, 41, 10, 1000,
               "00000000000000000000000000000000000000001100001011"),
    ZombieType("ZOMBIE_LADDER", "ladder", 4, 43, 10, 1000,
               "00000000000000000000000000000000000000000011101011"),
    ZombieType("ZOMBIE_CATAPULT", "catapult", 5, 46, 10, 1500,
               "00000000000000000000000000000000000000000000011011"),
    ZombieType("ZOMBIE_GARGANTUAR", "gargantuar", 10, 48, 15, 1500,
               "00000000000000000000000000000000000000000000000111"),
    ZombieType("ZOMBIE_IMP", "imp", 10, 48, 1, 0,
               "00000000000000000000000000000000000000000000000111"),
    ZombieType("ZOMBIE_BOSS", "zombie_boss", 10, 50, 1, 0,
               "00000000000000000000000000000000000000000000000000"),
)

BY_NAME: Dict[str, ZombieType] = {z.name: z for z in ZOMBIES}
BY_ID: Dict[str, ZombieType] = {z.repo_id: z for z in ZOMBIES if z.repo_id}

#: ``IsZombieTypePoolOnly``: these two are *only* ever in the water.
POOL_ONLY = ("ZOMBIE_SNORKEL", "ZOMBIE_DOLPHIN_RIDER")
#: ``Zombie::ZombieTypeCanGoInPool``: these walk into the pool and are drawn with a ducky tube.
CAN_GO_IN_POOL = ("ZOMBIE_NORMAL", "ZOMBIE_TRAFFIC_CONE", "ZOMBIE_PAIL", "ZOMBIE_FLAG")

#: What this project calls the pool drawing of a type that walked into the water. The flag
#: zombie has no such art here (and the original's flag zombie keeps its flag either way), so it
#: stays on the lawn.
DUCKY_FORMS = {
    "ZOMBIE_NORMAL": "ducky_tube_zombie",
    "ZOMBIE_TRAFFIC_CONE": "ducky_tube_conehead_zombie",
    "ZOMBIE_PAIL": "ducky_tube_buckethead_zombie",
}

# ---------------------------------------------------------------------------
# ``gZombieWaves`` - how many waves each of the fifty levels has
# ---------------------------------------------------------------------------

GZOMBIE_WAVES: Tuple[int, ...] = (
    4, 6, 8, 10, 8, 10, 20, 10, 20, 20,
    10, 20, 10, 20, 10, 10, 20, 10, 20, 20,
    10, 20, 20, 30, 20, 20, 30, 20, 30, 30,
    10, 20, 10, 20, 20, 10, 20, 10, 20, 20,
    10, 20, 20, 30, 20, 20, 30, 20, 30, 30,
)

# ---------------------------------------------------------------------------
# ``LawnApp::GetAwardSeedForLevel`` - "each area hands out eight plants"
# ---------------------------------------------------------------------------

#: The original's ``SeedType`` order, as this project's plant ids. Only the entries up to 4-10's
#: award are needed today; the rest are here so the formula stays the formula.
AWARD_SEEDS: Tuple[str, ...] = (
    "pea_shooter", "sunflower", "cherry_bomb", "wall_nut", "potato_mine",
    "snow_pea", "chomper", "repeater", "puff_shroom", "sun_shroom",
    "fume_shroom", "grave_buster", "hypno_shroom", "scaredy_shroom", "ice_shroom",
    "doom_shroom", "lily_pad", "squash", "threepeater", "tangle_kelp",
    "jalapeno", "spikeweed", "torchwood", "tall_nut", "sea_shroom",
    "plantern", "cactus", "blover", "split_pea", "starfruit",
    "pumpkin", "magnet_shroom", "cabbage_pult", "flower_pot", "kernel_pult",
    "coffee_bean", "garlic", "umbrella_leaf", "marigold", "melon_pult",
)


def seeds_available(level: int) -> int:
    """``GetAwardSeedForLevel``: how many seeds the player owns *while* playing ``level``."""
    area = (level - 1) // 10 + 1
    sub = (level - 1) % 10 + 1
    count = (area - 1) * 8 + sub
    if sub >= 10:
        count -= 2  # two levels of every area hand out no plant
    elif sub >= 5:
        count -= 1  # and one more hands out none either
    return min(count, len(AWARD_SEEDS))


def award_seed(level: int) -> Optional[str]:
    """The plant completing ``level`` hands out, or ``None`` when it hands out nothing new.

    The formula counts *available* seeds, so the award is the one that becomes available on the
    next level - which is also why the two "nothing" levels of an area come out of it for free.
    """
    before = seeds_available(level)
    after = seeds_available(level + 1)
    if after <= before:
        return None
    return AWARD_SEEDS[after - 1]


def deck_for(level: int) -> List[str]:
    """The seeds a level with no seed chooser hands out, in the original's own order.

    First-time adventure mode has no chooser on its first seven levels: the bank is filled with
    every seed the player owns, in order (``Board::InitLevel``).
    """
    return list(AWARD_SEEDS[:seeds_available(level)])


# ---------------------------------------------------------------------------
# Per-level facts: which stage, which clock, which fog, which award
# ---------------------------------------------------------------------------

#: ``AddGraveStones`` per night level: how many tombstones stand when the level opens, and the
#: column the leftmost of them may be in. The original writes one call per column (2-1 gets one
#: in column 6, one in 7 and two in 8); a level file has no per-column counts, so what travels
#: here is the total and the left edge, and the columns in between keep the original's spread.
GRAVE_LAYOUTS: Dict[int, tuple] = {
    11: (4, 6), 12: (4, 6), 13: (4, 6),
    14: (7, 5), 16: (7, 5),
    17: (11, 4), 18: (11, 4), 19: (11, 4),
    20: (13, 3),
}

AREA_KINDS = ("day", "night", "pool", "fog", "roof")


@dataclass(frozen=True)
class LevelFacts:
    number: int
    #: "day" / "night" / "pool" / "fog" - the original's four adventure backgrounds. 4-5 is the
    #: exception inside the fog area: `PickBackground` sends a Scary Potter level back to the
    #: night lawn, which is why that level has no fog and no pool.
    kind: str
    #: Which seed the level hands out first time through, if any.
    award: Optional[str]
    #: 1-5 rolls wall-nuts, 3-5 throws miniature zombies: both are conveyor levels.
    wallnut_bowling: bool
    little_trouble: bool
    #: `IsMiniBossLevel`: 1-10, 2-10 and 3-10. Conveyor, triple budget, no waiting around.
    mini_boss: bool
    #: 2-5 is Whack-a-Zombie (a hammer and gravestones, no walk-ins) and 4-5 is Scary Potter
    #: (three rounds of vases, no waves at all).
    whack_a_zombie: bool
    scary_potter: bool
    #: 4-10 is the game's one thunderstorm: the lawn is black and lightning is the only light.
    #:
    #: A flag of its own rather than "level 40" written into the level builder, because what was
    #: missed about this level is not a number but a *kind*: it is a conveyor level, and while the
    #: card table is keyed by level number, the fact that says "this level deals cards at all" is
    #: this one - a conveyor table nobody reads is exactly how 4-10 shipped as an ordinary level.
    storm: bool = False
    bungee_blitz: bool = False

    @property
    def name(self) -> str:
        return "%d-%d" % ((self.number - 1) // 10 + 1, (self.number - 1) % 10 + 1)

    @property
    def night(self) -> bool:
        return self.kind in ("night", "fog")

    @property
    def pool(self) -> bool:
        return self.kind in ("pool", "fog")

    @property
    def rows(self) -> int:
        """How tall the board is.

        1-1 is one lane and 1-2/1-3 are three: the original turns the rest of the lawn to dirt
        (`mPlantRow`), and this project draws the matching background instead.
        """
        if self.pool:
            return 6
        if self.number == 1:
            return 1
        if self.number <= 3:
            return 3
        return 5

    @property
    def conveyor(self) -> bool:
        """``HasConveyorBeltSeedBank`` for adventure levels.

        The storm is in this list rather than only in the card table: the original's 4-10 hands
        the player its cards off a belt like the other area finales, and the area's own table
        (``CONVEYORS[40]`` in the level writer) is what it deals.
        """
        return (self.mini_boss or self.wallnut_bowling or self.little_trouble
                or self.storm or self.bungee_blitz)

    @property
    def fog_column(self) -> Optional[float]:
        """``LeftFogColumn``: the leftmost fogged column, or ``None`` on a clear level."""
        if self.kind != "fog":
            return None
        if self.number == 31:
            return 6.0
        if self.number <= 36:
            return 5.0
        return 4.0

    @property
    def first_wave_ticks(self) -> int:
        if self.mini_boss:
            return MINI_BOSS_FIRST_WAVE
        if self.number == 2:
            return SECOND_LEVEL_FIRST_WAVE
        if self.conveyor:
            return CONVEYOR_FIRST_WAVE
        return ZOMBIE_COUNTDOWN_FIRST_WAVE

    @property
    def wave_count(self) -> int:
        """``mNumWaves``.

        Two adventure levels override the table: Whack-a-Zombie runs eight waves of its own
        pacing, and Scary Potter has no waves at all - its zombies climb out of the vases.
        """
        if self.scary_potter:
            return 0
        if self.whack_a_zombie:
            return 8
        return GZOMBIE_WAVES[self.number - 1]

    @property
    def sun(self) -> int:
        """``mSunMoney``: 150 on the tutorial, nothing on the two levels that forbid planting."""
        if self.number == 1:
            return FIRST_LEVEL_SUN
        if self.conveyor or self.whack_a_zombie or self.scary_potter:
            return 0
        return DEFAULT_SUN

    @property
    def deck(self) -> Optional[List[str]]:
        """The cards a level with no seed chooser hands out, or ``None`` if it has a chooser.

        First-time adventure mode picks its own seeds from 1-8 on; before that the bank is
        filled for the player, and the two special levels fill it with a fixed handful.
        """
        if self.conveyor:
            return None
        if self.whack_a_zombie:
            # `Board::InitLevel`: potato mine, grave buster, cherry bomb.
            return ["potato_mine", "grave_buster", "cherry_bomb"]
        if self.scary_potter:
            # One packet, and it is the only plant the player is given.
            return ["cherry_bomb"]
        if self.number <= 7:
            return deck_for(self.number)
        return None

    @property
    def graves(self) -> Optional[tuple]:
        """``(count, leftmost column)`` of the tombstones the level opens with, or ``None``.

        The night lawn is the only stage that has them, and Whack-a-Zombie is the one night
        level whose graves are its mechanic rather than its scenery - it raises nine of them
        itself (`grave_spawner`).
        """
        return GRAVE_LAYOUTS.get(self.number)

    def flag_waves(self) -> List[int]:
        """One-based numbers of the waves that carry a flag.

        A level with fewer than ten waves flags its last wave only, and 1-1 - the tutorial - has
        no flag wave at all.
        """
        count = self.wave_count
        if count == 0:
            return []
        per_flag = count if count < WAVES_PER_FLAG else WAVES_PER_FLAG
        if self.number == 1:
            return []
        return [w for w in range(1, count + 1) if w % per_flag == 0]

    def budget_for(self, wave_index: int) -> int:
        """``aZombiePoints`` for a zero-based wave index, before any adjustment.

        The order matters and is the original's: the flag wave's ``*2.5`` (and the plain zombies
        and the flag it places on the way) happens first, and the level's own multiplier - the
        mini-boss's triple, the two conveyor levels' quadruple - multiplies what is *left*. See
        ``waves_for``.
        """
        return wave_index // 3 + 1

    @property
    def budget_multiplier(self) -> int:
        """``IsMiniBossLevel`` / ``IsLittleTroubleLevel`` / ``IsWallnutBowlingLevel``."""
        if self.mini_boss:
            return 3
        if self.bungee_blitz:
            return 2
        if self.little_trouble or self.wallnut_bowling:
            return 4
        return 1


def level_facts(number: int) -> LevelFacts:
    kind = AREA_KINDS[(number - 1) // 10]
    if number == 35:
        kind = "night"  # Scary Potter is played on the night lawn, not in the fog
    return LevelFacts(
        number=number,
        kind=kind,
        award=award_seed(number),
        wallnut_bowling=number == 5,
        little_trouble=number == 25,
        mini_boss=number in (10, 20, 30),
        whack_a_zombie=number == 15,
        scary_potter=number == 35,
        storm=number == 40,
        bungee_blitz=number == 45,
    )


#: Levels 1..49 - the shipped adventure, before the roof boss.
LEVELS: Tuple[LevelFacts, ...] = tuple(level_facts(n) for n in range(1, 50))


# ---------------------------------------------------------------------------
# ``Board::PickZombieWaves``
# ---------------------------------------------------------------------------


@dataclass
class Wave:
    """One wave, in the shape this project's level files want."""

    type: str  # "small" / "huge" / "final"
    delay: int
    entries: List[dict]
    spawn_interval: int
    warning_ticks: Optional[int] = None
    #: What the original rolled, kept for tests and for a reader comparing against the game.
    rolled: Tuple[str, ...] = ()


def pool_for(level: int) -> List[ZombieType]:
    """Every type the level's random picks may draw (``mZombieAllowed``)."""
    if level == 45:
        # Challenge::InitZombieWaves: the bungees are scripted flag-wave raids, not random picks.
        return [BY_NAME[n] for n in ("ZOMBIE_NORMAL", "ZOMBIE_TRAFFIC_CONE",
                                     "ZOMBIE_PAIL", "ZOMBIE_LADDER")]
    return [z for z in ZOMBIES if z.pickable_on(level)]


def introduced_zombie(level: int) -> Optional[ZombieType]:
    """``GetIntroducedZombieType``: the type whose first level this is, if any.

    The original walks its enum in order and takes the first match, so a level that introduces
    two types at once (3-6 brings both the zamboni and the bobsled team) introduces the earlier
    one by name; the other still shows up through the level's ordinary picks and its final wave.
    A type whose ``mStartingLevel`` is this level but which the player has not unlocked at all
    is skipped - which is what keeps the yeti out of 4-10.
    """
    if level == 1:
        return None
    for zombie in ZOMBIES:
        if zombie.starting_level != level or not zombie.repo_id:
            continue
        if zombie.name == "ZOMBIE_YETI":
            # `CanSpawnYetis` needs a finished adventure; the yeti is a trophy, not a resident.
            continue
        if zombie.name == "ZOMBIE_DUCKY_TUBE":
            # The original skips this one by name in `PickZombieWaves`: a ducky tube is how a
            # plain zombie is *drawn* once it is in the pool, so 3-1 has nothing to show off -
            # it sends them from its ordinary rolls like every other pool level.
            return None
        return zombie
    return None


def _rows_for(zombie: ZombieType, facts: LevelFacts, wave_index: int) -> List[int]:
    """The lanes one rolled zombie may be dealt into.

    Mirrors ``RowCanHaveZombieType``: pool-only types are water-only, the four that can swim are
    dealt across the whole board (but only from the sixth wave on - before that the original
    keeps the pool for the swimmers), and everything else is lawn-only.
    """
    water = [2, 3]
    land = [0, 1, 4, 5] if facts.pool else list(range(facts.rows))
    if not facts.pool:
        return land
    if zombie.name in POOL_ONLY:
        return water
    if zombie.name in CAN_GO_IN_POOL and zombie.name in DUCKY_FORMS and wave_index >= 5:
        return sorted(land + water)
    return land


def _as_entry(zombie: ZombieType, row: int, facts: LevelFacts) -> str:
    """What one zombie becomes once it has a lane: the id it is spawned under."""
    if facts.pool and row in (2, 3) and zombie.name in DUCKY_FORMS:
        return DUCKY_FORMS[zombie.name]
    if not zombie.repo_id:
        raise ValueError("no card for %s, which level %d can send" % (zombie.name, facts.number))
    return zombie.repo_id


def waves_for(level: int) -> List[Wave]:
    """Replay ``PickZombieWaves`` for one level and return its waves.

    Deterministic: the RNG is seeded from the level number, so the tables this writes are stable
    across runs and machines.
    """
    facts = level_facts(level)
    rng = random.Random(LEVEL_SEED * 1000 + level)
    count = facts.wave_count
    flags = set(facts.flag_waves())
    intro = introduced_zombie(level)
    pool = pool_for(level)
    waves: List[Wave] = []
    previous_was_flag = False

    for index in range(count):
        number = index + 1
        is_flag = number in flags
        is_final = number == count
        budget = facts.budget_for(index)
        rolled: List[str] = []

        def place(zombie: ZombieType) -> None:
            nonlocal budget
            rolled.append(zombie.name)
            budget -= zombie.value

        raid_only = facts.bungee_blitz and is_flag and not is_final
        if facts.bungee_blitz and is_flag:
            # The five raiders are inserted before the point budget is assigned.
            rolled.extend(["ZOMBIE_BUNGEE"] * 5)
        if raid_only:
            budget = 0
        elif is_flag:
            plain = min(budget, 8)
            budget = int(budget * 2.5)
            for _ in range(plain):
                place(BY_NAME["ZOMBIE_NORMAL"])
            place(BY_NAME["ZOMBIE_FLAG"])

        # The level's own multiplier comes after the flag wave has had its say (`PickZombieWaves`
        # multiplies the picker's remaining points).
        budget *= facts.budget_multiplier

        if intro is not None and not raid_only:
            # A new zombie is shown off: the digger and the balloon arrive in the seventh wave,
            # everything else in the middle of the level - and all of them in the final wave.
            if intro.name in ("ZOMBIE_DIGGER", "ZOMBIE_BALLOON"):
                appear = number == 7 or is_final
            else:
                appear = index == count // 2 or is_final
            if appear:
                place(intro)

        if is_final:
            # "The final wave of an adventure level includes every zombie type of the level."
            present = set(rolled)
            for zombie in pool:
                if zombie.name not in present:
                    place(zombie)

        guard = 0
        while budget > 0 and len(rolled) < MAX_ZOMBIES_IN_WAVE:
            guard += 1
            if guard > 4 * MAX_ZOMBIES_IN_WAVE:  # a pick the budget can never pay for
                break
            candidates = [(z, z.pick_weight) for z in pool
                          if z.first_allowed_wave <= number and z.value <= budget]
            if not candidates:
                break
            total = sum(weight for _, weight in candidates)
            dart = rng.randrange(total)
            for zombie, weight in candidates:
                dart -= weight
                if dart < 0:
                    place(zombie)
                    break

        # Deal lanes: round-robin over each zombie's eligible lanes, so a wave spreads out the
        # way the original's row picker does (which weights lanes that have not been mowed
        # lately, and is otherwise uniform).
        lanes: Dict[str, List[int]] = {}
        cursor = 0
        for name in rolled:
            zombie = BY_NAME[name]
            eligible = _rows_for(zombie, facts, index)
            row = eligible[cursor % len(eligible)]
            cursor += 1
            entry_id = _as_entry(zombie, row, facts)
            lanes.setdefault(entry_id, []).append(row)

        entries = [{"id": "pvzce:" + entry_id, "count": len(rows), "rows": sorted(set(rows))}
                   for entry_id, rows in lanes.items()]

        if index == 0:
            delay = facts.first_wave_ticks
        elif previous_was_flag:
            delay = ZOMBIE_COUNTDOWN_BEFORE_FLAG
        else:
            delay = ZOMBIE_COUNTDOWN + rng.randrange(ZOMBIE_COUNTDOWN_RANGE)

        waves.append(Wave(
            type="final" if is_final else ("huge" if is_flag else "small"),
            delay=delay,
            entries=entries,
            spawn_interval=SPAWN_INTERVAL,
            warning_ticks=HUGE_WAVE_WARNING_TICKS if is_flag else None,
            rolled=tuple(rolled),
        ))
        previous_was_flag = is_flag

    return waves


def _main() -> None:
    for facts in LEVELS:
        waves = waves_for(facts.number)
        total = sum(len(w.rolled) for w in waves)
        kinds: Dict[str, int] = {}
        for wave in waves:
            for name in wave.rolled:
                kinds[name] = kinds.get(name, 0) + 1
        print("%-5s %-5s waves=%-3d zombies=%-4d flags=%-10s award=%-14s %s" % (
            facts.name, facts.kind, len(waves), total,
            ",".join(str(w) for w in facts.flag_waves()) or "-",
            facts.award or "(none)",
            " ".join("%s:%d" % (BY_NAME[n].repo_id or n, c) for n, c in sorted(kinds.items())),
        ))


if __name__ == "__main__":
    _main()
