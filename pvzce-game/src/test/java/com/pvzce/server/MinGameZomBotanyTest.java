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

/** The shipped first Zombotany: two plant heads, twenty waves, and player-selected seeds. */
class MinGameZomBotanyTest {
    private static final Identifier LEVEL = PvzceIds.id("yard/minigame/zombotany");

    private static final Set<Identifier> PLANT_HEADED = Set.of(
            PvzceIds.id("zombotany_pea_zombie"),
            PvzceIds.id("zombotany_wallnut_zombie"));

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static LevelDef def() {
        LevelDef def = BuiltInRegistries.LEVELS.get(LEVEL);
        assertNotNull(def, "the shipped mini-game must load");
        return def;
    }

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

    @Test
    void twentyWavesEndInTwoFlagsWithoutOrdinaryFlagZombies() {
        assertEquals(20, def().waves().size());
        assertEquals(2, def().waves().stream().filter(wave -> wave.type().isHuge()).count());
        assertEquals(WaveDef.WaveType.FINAL, def().waves().get(19).type());
    }

    @Test
    void everyWaveContainsOnlyTheTwoFirstGameHeadsInLandLanes() {
        Set<Integer> land = new LinkedHashSet<>(new LevelServer(def()).landRows());
        Set<Identifier> seen = new LinkedHashSet<>();
        for (WaveDef wave : def().waves()) {
            for (WaveDef.Entry entry : wave.entries()) {
                assertTrue(PLANT_HEADED.contains(entry.id()), "second-game heads do not belong here");
                assertTrue(land.containsAll(entry.rows()));
                seen.add(entry.id());
            }
        }
        assertEquals(PLANT_HEADED, seen);
    }

    @Test
    void thePlayerChoosesSeedsForTheOrdinaryDayEconomy() {
        assertEquals(List.of(PvzceIds.SUN, PvzceIds.id("shovel")), def().slots());
        assertEquals(50, def().initialSun());
        assertFalse(LevelMechanics.has(def(), PvzceIds.MECHANIC_CONVEYOR));
        int bar = def().effectiveMaxSeedSlots(PvzceConstants.DEFAULT_SEED_SLOTS);
        assertFalse(SeedOptions.hasNothingToChoose(SeedOptions.forLevel(def()), bar,
                SeedOptions.lockedSlotIds(def())));
        assertTrue(def().unlockResources().getOrDefault(PvzceIds.SUN, false));
    }

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

    @Test
    void theLevelPlaysTwentyWavesAndIsNotAnEndlessRun() {
        LevelDef def = def();
        LevelServer level = new LevelServer(def);
        assertFalse(level.envVars().getBoolean(PvzceIds.ENV_PLANT_AI, false),
                "the plant AI belongs to the demo levels, not to this one");
        level.tick(packet -> { });

        assertEquals(20, def.waves().size(), "the original's own count");
        assertEquals(20, level.totalWaves(), "and the director got all five, not a schedule of its own");
        assertFalse(LevelMechanics.has(def, PvzceIds.MECHANIC_ENDLESS),
                "an endless level is a different level: this one ends after the flag wave");
        assertFalse(level.isRoundClearPending(),
                "nothing to pick between rounds, because there is only the one round");
    }
}
