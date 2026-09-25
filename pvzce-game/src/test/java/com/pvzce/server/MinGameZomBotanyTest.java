package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SeedOptions;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.level.mechanic.MowerMechanic;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.gamerule.GameRules;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ZomBotany, the second mini-game: a day lawn whose zombies wear the plants' heads.
 *
 * <p>What is pinned here is what the level was asked to be - the original's own five ids, its own
 * five waves, and a fixed deck - plus the two things the level deliberately leaves unwritten: a
 * {@code pvzce:mower} block (silence means one mower per row) and a card source (a deck, not a
 * belt). The zombies' counts and the wave delays are balance data and belong to the same tuning
 * pass as every other level's; only the band the level was specified with is checked.
 */
class MinGameZomBotanyTest {
    private static final Identifier LEVEL = PvzceIds.id("yard/minigame/zombotany");
    private static final Identifier FLAG_ZOMBIE = PvzceIds.id("flag_zombie");
    /** The original's mutation: the four zombies whose heads are plants. */
    private static final Set<Identifier> PLANT_HEADED = Set.of(
            PvzceIds.id("zombotany_pea_zombie"),
            PvzceIds.id("zombotany_wallnut_zombie"),
            PvzceIds.id("zombotany_gatling_zombie"),
            PvzceIds.id("zombotany_jalapeno_zombie"));

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static LevelDef def() {
        LevelDef def = BuiltInRegistries.LEVELS.get(LEVEL);
        assertNotNull(def, "the shipped mini-game must load");
        return def;
    }

    /**
     * The board it was specified on: nine columns of day grass, five rows of it, and no water in
     * the scene at all.
     *
     * <p>The terrain class is what is asked, not the element id: a pool painted with the water a
     * mutation floods a lawn with is water for every rule that reads the scene - planting,
     * spawning, swimming alike - and this level's cast is five walkers, so a wet row would be a
     * row none of them could use.
     */
    @Test
    void theShippedLevelIsANineByFiveDayLawn() {
        LevelDef def = def();
        assertEquals(9, def.width());
        assertEquals(5, def.height());
        assertFalse(def.scene().isEmpty(), "a board with no ground has no cell to plant in");
        for (Identifier element : def.scene().keySet()) {
            assertNotEquals(PvzceIds.SURFACE_WATER,
                    BuiltInRegistries.SCENE_ELEMENTS.get(element).surfaceClass(),
                    element + " is water, and this level is a lawn");
        }
        LevelServer level = new LevelServer(def);
        assertEquals(List.of(0, 1, 2, 3, 4), level.landRows(),
                "every row is land, so every row is one the cast may walk");

        // The day lawn's own sky: full daylight, no clock that could leave it, and the sun
        // interval the mid-game day levels use.
        GameRules rules = new GameRules(def.rules());
        assertEquals(0, rules.getInt(PvzceIds.RULE_DAY_LENGTH), "the original plays this one in daylight");
        assertEquals(-1, rules.getInt(PvzceIds.RULE_NIGHT_LENGTH), "and nothing turns it into night");
        assertEquals(480, rules.getInt(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN));
        assertEquals(720, rules.getInt(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX));
        assertEquals(300, rules.getInt(PvzceIds.RULE_SUN_SPAWN_INITIAL_TICKS));
        assertFalse(level.isNight(), "so the first tick is a day tick");
    }

    /**
     * The original's own wave count, ending in its own flag wave.
     *
     * <p>The size band is the one the level was specified with: it is played with a deck of six
     * plants and no belt, so a wave that pours would not be a wave that player can answer.
     */
    @Test
    void theWaveTableIsFiveWavesAndTheLastOneIsTheFlagWave() {
        LevelDef def = def();
        assertEquals(5, def.waves().size(), "the original's own count");

        for (int index = 0; index < def.waves().size(); index++) {
            WaveDef wave = def.waves().get(index);
            assertTrue(wave.totalZombies() >= 2 && wave.totalZombies() <= 6,
                    "wave " + (index + 1) + " sends " + wave.totalZombies()
                            + " zombies, and a mid-game deck answers a handful, not a horde");
        }

        WaveDef last = def.waves().get(def.waves().size() - 1);
        assertEquals(WaveDef.WaveType.FINAL, last.type(), "the last wave is the level's flag wave");
        int flags = 0;
        for (WaveDef.Entry entry : last.entries()) {
            if (entry.id().equals(FLAG_ZOMBIE)) {
                flags += entry.count();
            }
        }
        assertEquals(1, flags, "and exactly one flag leads it");
    }

    /**
     * Every id that can arrive is one of the five, and every entry says which lanes it may use.
     *
     * <p>An entry with no {@code rows} means "any lane", which is the classic way a land zombie
     * ends up somewhere it cannot walk. Naming the lanes is therefore not decoration here: it is
     * the fact that keeps this level's cast on this level's lawn.
     */
    @Test
    void everyEntrySendsOneOfTheFiveAllowedZombiesIntoALandLane() {
        LevelDef def = def();
        Set<Integer> landRows = new LinkedHashSet<>(new LevelServer(def).landRows());
        Set<Identifier> allowed = new LinkedHashSet<>(PLANT_HEADED);
        allowed.add(FLAG_ZOMBIE);
        for (Identifier id : PLANT_HEADED) {
            assertNotNull(BuiltInRegistries.ZOMBIES.get(id), id + " must be a registered zombie");
        }

        for (int index = 0; index < def.waves().size(); index++) {
            WaveDef wave = def.waves().get(index);
            String where = "wave " + (index + 1);
            for (WaveDef.Entry entry : wave.entries()) {
                assertTrue(allowed.contains(entry.id()),
                        where + " sends " + entry.id() + ", which is not part of the cast");
                assertTrue(entry.restrictedToRows(),
                        where + " entry '" + entry.id() + "' names no rows, so any lane may get it");
                assertEquals(landRows, new LinkedHashSet<>(entry.rows()),
                        where + " entry '" + entry.id() + "' has to name every land lane and no other");
            }
        }
    }

    /**
     * The bar is the level's own deck: the six plants, the sun card and the shovel.
     *
     * <p>{@code declaresMaxSeedSlots} is part of "fixed", not a detail of it - a level that
     * followed the backpack would offer a player with a bigger bag extra cards to add, and this
     * level was specified as one deck for everyone.
     */
    @Test
    void theBarIsTheFixedDeckAndNotABelt() {
        LevelDef def = def();
        assertEquals(List.of("pvzce:sun", "pvzce:pea_shooter", "pvzce:sunflower", "pvzce:wall_nut",
                        "pvzce:cherry_bomb", "pvzce:potato_mine", "pvzce:snow_pea", "pvzce:shovel"),
                def.slots().stream().map(Identifier::toString).toList(),
                "the fixed cards, in the order the level deals them");
        assertFalse(LevelMechanics.has(def, PvzceIds.MECHANIC_CONVEYOR),
                "a belt would be the card bar, and this level's bar is a deck");

        assertTrue(def.declaresMaxSeedSlots(), "a fixed deck has to say how big its bar is");
        int bar = def.effectiveMaxSeedSlots(PvzceConstants.DEFAULT_SEED_SLOTS);
        assertEquals(def.slots().size(), bar, "the eight cards fill it exactly");
        assertTrue(SeedOptions.hasNothingToChoose(SeedOptions.forLevel(def), bar,
                        SeedOptions.lockedSlotIds(def)),
                "so the seed chooser never opens: there is nothing left to choose");
        assertTrue(def.unlockResources().getOrDefault(PvzceIds.SUN, false),
                "the sun card is in the bar, so the level has to unlock the resource it collects");
    }

    /**
     * The mower block is omitted on purpose, and that is a statement: silence means the ordinary
     * lawn with one mower per row. The brief for this level is "every row", so there is nothing
     * for a block to narrow.
     */
    @Test
    void theMowerBlockIsOmittedAndEveryRowStillHasOne() {
        LevelServer level = new LevelServer(def());
        level.tick(packet -> { });
        MowerMechanic.Rig rig = level.mechanicStateOrNull(PvzceIds.MECHANIC_MOWER,
                MowerMechanic.Rig.class);
        assertNotNull(rig, "an implicit mechanic still has to be running on the level");
        for (int row = 0; row < level.height(); row++) {
            assertTrue(rig.hasRow(row), "row " + row + " has no mower, and nothing asked for fewer");
        }
        assertEquals(level.height(), rig.readyCount(), "all five are parked: none has been spent");
    }

    /**
     * The level as it plays: five waves, then the ordinary win.
     *
     * <p>Nothing is added to it - no extra zombies, and no plant AI, which the level must not turn
     * on: what is under test is the file the wave director reads.
     */
    @Test
    void theLevelPlaysFiveWavesAndIsNotAnEndlessRun() {
        LevelDef def = def();
        LevelServer level = new LevelServer(def);
        assertFalse(level.envVars().getBoolean(PvzceIds.ENV_PLANT_AI, false),
                "the plant AI belongs to the demo levels, not to this one");
        level.tick(packet -> { });

        assertEquals(5, def.waves().size(), "the original's own count");
        assertEquals(5, level.totalWaves(), "and the director got all five, not a schedule of its own");
        assertFalse(LevelMechanics.has(def, PvzceIds.MECHANIC_ENDLESS),
                "an endless level is a different level: this one ends after the flag wave");
        assertFalse(level.isRoundClearPending(),
                "nothing to pick between rounds, because there is only the one round");
    }
}
