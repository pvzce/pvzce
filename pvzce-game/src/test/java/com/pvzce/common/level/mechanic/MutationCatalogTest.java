package com.pvzce.common.level.mechanic;

import com.pvzce.common.level.mutation.Mutation;
import com.pvzce.common.level.mutation.MutationDifficulty;
import com.pvzce.common.level.mutation.MutationLevels;
import com.pvzce.common.level.mutation.MutationRegistry;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.tag.TestContent;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mutation catalogue and the four levels that run it.
 *
 * <p>What this pins is the part a player would notice immediately if it were wrong: that every
 * mutation the code names is registered, that the four tiers differ in exactly the three numbers
 * the mode is balanced around, and that the levels themselves are built with the fourteen-card,
 * ten-buff, pool-day shape the mode promises.
 */
class MutationCatalogTest {
    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    @Test
    void everyMutationTheCatalogueNamesIsRegistered() {
        List<Identifier> expected = List.of(
                PvzceIds.MUTATION_SLOT_REPLACE, PvzceIds.MUTATION_CONVEYOR,
                PvzceIds.MUTATION_SUN_RATE, PvzceIds.MUTATION_PLANT_ATTACK_RATE,
                PvzceIds.MUTATION_ZOMBIE_SPEED, PvzceIds.MUTATION_ZOMBIE_SPAWN_RATE,
                PvzceIds.MUTATION_PLANT_SUN_COST, PvzceIds.MUTATION_NIGHTFALL,
                PvzceIds.MUTATION_BOWLING_NUT, PvzceIds.MUTATION_WHACK_A_ZOMBIE,
                PvzceIds.MUTATION_GRAVE_GROWTH, PvzceIds.MUTATION_ZOMBIE_CRISIS,
                PvzceIds.MUTATION_BUFF_SHIFT, PvzceIds.MUTATION_APOCALYPSE,
                PvzceIds.MUTATION_ZOMBIE_BLAST, PvzceIds.MUTATION_PLANT_BLAST,
                PvzceIds.MUTATION_MENDEL, PvzceIds.MUTATION_KELP_SPREAD,
                // The second catalogue, all twenty.
                PvzceIds.MUTATION_ZOMBIE_HEALTH, PvzceIds.MUTATION_PLANT_FRAGILE,
                PvzceIds.MUTATION_CARD_COOLDOWN, PvzceIds.MUTATION_SUN_SHOWER,
                PvzceIds.MUTATION_SLOT_LOCK, PvzceIds.MUTATION_SLOT_ROULETTE,
                // Group C: the board, and the raids that arrive on it.
                PvzceIds.MUTATION_FOG_ROLL_IN, PvzceIds.MUTATION_FLOOD_LAWN,
                PvzceIds.MUTATION_METEOR_SHOWER, PvzceIds.MUTATION_THORN_LAWN,
                PvzceIds.MUTATION_ICE_GROUND,
                PvzceIds.MUTATION_ZOMBOTANY, PvzceIds.MUTATION_GARGANTUAR_RAID,
                PvzceIds.MUTATION_IMP_AIRDROP, PvzceIds.MUTATION_BUNGEE_RAID,
                PvzceIds.MUTATION_BALLOON_RAID,
                // Group E: the player's own side. This is the whole second catalogue.
                PvzceIds.MUTATION_PEA_PARTY, PvzceIds.MUTATION_RANDOM_SUPPLY,
                PvzceIds.MUTATION_AUTO_RELEASE, PvzceIds.MUTATION_SUN_DRAIN);
        assertEquals(expected.size(), MutationRegistry.all().size(),
                "the catalogue grew or shrank; update the list and the docs together");
        for (Identifier id : expected) {
            assertNotNull(MutationRegistry.get(id), id + " is named in code but not registered");
            assertTrue(MutationRegistry.get(id).weight() > 0, id + " can never be rolled");
        }
    }

    @Test
    void everyMutationRollsANumberInsideItsTiersRange() {
        for (Mutation mutation : MutationRegistry.all()) {
            for (MutationDifficulty tier : MutationDifficulty.values()) {
                for (int i = 0; i < 200; i++) {
                    // A tiny stand-in for the level's dice: the roll's own contract is that the
                    // tier scales both ends, which is checkable without a level.
                    float rolled = tier.rollNumber(new java.util.Random(i));
                    assertTrue(rolled >= MutationDifficulty.ROLL_MIN * tier.rollMultiplier() - 0.001F,
                            mutation.id() + " at " + tier + " rolled below its floor: " + rolled);
                    assertTrue(rolled <= MutationDifficulty.ROLL_MAX * tier.rollMultiplier() + 0.001F,
                            mutation.id() + " at " + tier + " rolled above its ceiling: " + rolled);
                }
            }
        }
    }

    @Test
    void theFourTiersDifferInAllThreeNumbers() {
        assertEquals(10, MutationDifficulty.EASY.maxConcurrent());
        assertEquals(20, MutationDifficulty.NORMAL.maxConcurrent());
        assertEquals(30, MutationDifficulty.HARD.maxConcurrent());
        assertEquals(50, MutationDifficulty.HELL.maxConcurrent());
        assertEquals(120 * PvzceConstants.TICKS_PER_SECOND, MutationDifficulty.EASY.intervalTicks());
        assertEquals(90 * PvzceConstants.TICKS_PER_SECOND, MutationDifficulty.NORMAL.intervalTicks());
        assertEquals(60 * PvzceConstants.TICKS_PER_SECOND, MutationDifficulty.HARD.intervalTicks());
        assertEquals(30 * PvzceConstants.TICKS_PER_SECOND, MutationDifficulty.HELL.intervalTicks());
        assertEquals(1F, MutationDifficulty.EASY.rollMultiplier());
        assertEquals(1.5F, MutationDifficulty.NORMAL.rollMultiplier());
        assertEquals(2F, MutationDifficulty.HARD.rollMultiplier());
        assertEquals(3F, MutationDifficulty.HELL.rollMultiplier());
    }

    @Test
    void aHarderTierRunsAMutationsOwnClockFaster() {
        // The tier's multiplier scales an interval a mutation keeps for itself, so "more
        // mutations at once" is not the only way a tier is harder.
        assertEquals(100, MutationDifficulty.EASY.scaledInterval(100));
        assertEquals(67, MutationDifficulty.NORMAL.scaledInterval(100));
        assertEquals(50, MutationDifficulty.HARD.scaledInterval(100));
        assertEquals(33, MutationDifficulty.HELL.scaledInterval(100));
    }

    @Test
    void anUnrecognisedTierNameFallsBackToTheMiddleOne() {
        assertEquals(MutationDifficulty.HELL, MutationDifficulty.parse("hell"));
        assertEquals(MutationDifficulty.HELL, MutationDifficulty.parse(" HELL "));
        assertEquals(MutationDifficulty.DEFAULT, MutationDifficulty.parse("nightmare"));
        assertEquals(MutationDifficulty.DEFAULT, MutationDifficulty.parse(null));
        assertTrue(MutationDifficulty.isKnown("easy"));
        assertTrue(!MutationDifficulty.isKnown("nightmare"));
    }

    @Test
    void theFourLevelsAreRegisteredUnderTheSharedEndlessCategory() {
        List<Identifier> ids = MutationLevels.levelIds();
        assertEquals(4, ids.size());
        for (Identifier id : ids) {
            LevelDef def = BuiltInRegistries.LEVELS.get(id);
            assertNotNull(def, id + " is missing from the level registry");
            // The same page as the plain endless level: two endless modes on two tabs of one
            // theme was the reported "they are under two categories" bug.
            assertTrue(id.path().startsWith("yard/survival/"),
                    "the id must put the level on the survival page, was " + id);
            assertTrue(BuiltInRegistries.LEVEL_CATEGORIES.containsKey(PvzceIds.CATEGORY_SURVIVAL),
                    "the survival category must exist for the level to have a page");
        }
    }

    @Test
    void aMutationLevelIsThePoolDayWithAFixedFourteenCardTenBuffBar() {
        LevelDef def = BuiltInRegistries.LEVELS.get(
                MutationLevels.levelIds().get(1));
        assertNotNull(def);
        assertEquals(9, def.width());
        assertEquals(6, def.height());
        assertEquals(14, def.maxSeedSlots(), "the mode fixes fourteen card slots");
        assertEquals(10, def.buffPlan().maxBuffSlots(), "and ten buff slots");
        assertEquals(50, def.initialSun());
        assertEquals(0, def.rules().get(PvzceIds.RULE_DAY_LENGTH).getAsInt(),
                "the base is the day pool: no day length and no night");
        assertEquals(-1, def.rules().get(PvzceIds.RULE_NIGHT_LENGTH).getAsInt());
        assertTrue(def.background().isPresent(), "the pool backdrop");
        // background3 is the pool by daylight - the same picture the shipped 3-x levels are
        // played on. background4 is the same pool after dark, and using it here made a brand new
        // run *look* like night while its rules said day: mushrooms slept on a black lawn.
        assertEquals("background3", def.background().get().path().replaceAll(".*/", ""),
                "a fresh mutation run starts in daylight, so it starts on the day backdrop");
    }

    @Test
    void everyTierOfLevelNamesItsOwnDifficultyRule() {
        for (MutationDifficulty tier : MutationDifficulty.values()) {
            Identifier id = MutationLevels.levelIds()
                    .get(tier.ordinal());
            LevelDef def = BuiltInRegistries.LEVELS.get(id);
            assertNotNull(def);
            JsonElement configured = def.rules().get(PvzceIds.RULE_MUTATION_DIFFICULTY);
            assertNotNull(configured, id + " must fix its tier");
            assertEquals(tier.tierName(), configured.getAsString());
            // The grace period is the mode's own number on every tier, not the tier's interval:
            // a player has to have planted something before the lawn starts rewriting itself.
            assertEquals(PvzceConstants.MUTATION_INITIAL_TICKS,
                    def.rules().get(PvzceIds.RULE_MUTATION_INITIAL_TICKS).getAsInt());
        }
    }

    @Test
    void anEndlessRoundGrowsWithTheRoundNumberAndKeepsTheFloatiesInTheWater() {
        com.pvzce.api.content.EndlessScheduleDef schedule =
                com.pvzce.common.level.endless.EndlessSchedules.poolEndless();
        // Rounds get longer, up to the cap; the waves get bigger and arrive tighter.
        assertEquals(11, com.pvzce.common.level.endless.EndlessRamp.wavesInRound(schedule, 1));
        assertEquals(30, com.pvzce.common.level.endless.EndlessRamp.wavesInRound(schedule, 20));
        var first = com.pvzce.common.level.endless.EndlessWaves.wave(schedule, 1, 0,
                List.of(0, 1, 4, 5), List.of(2, 3), new java.util.Random(1L));
        var later = com.pvzce.common.level.endless.EndlessWaves.wave(schedule, 12, 0,
                List.of(0, 1, 4, 5), List.of(2, 3), new java.util.Random(1L));
        assertNotNull(first);
        assertNotNull(later);
        assertTrue(later.totalZombies() > first.totalZombies(),
                "a later round has to send more, or the ramp is not one");
        assertTrue(later.delay() < first.delay(), "and to arrive sooner");
        // Every floatie has to name the water rows: an ordinary zombie sent into the pool row
        // drowns, and a ducky sent onto the grass is not a ducky.
        for (int round = 1; round <= 3; round++) {
            for (com.pvzce.api.content.WaveDef wave : com.pvzce.common.level.endless.EndlessWaves
                    .round(schedule, round,
                            new com.pvzce.common.level.endless.EndlessWaves.Rows(
                                    List.of(0, 1, 4, 5), List.of(2, 3)),
                            new java.util.Random(7L))) {
                for (com.pvzce.api.content.WaveDef.Entry entry : wave.entries()) {
                    if (entry.id().path().startsWith("ducky_tube")) {
                        assertEquals(List.of(2, 3), entry.rows(),
                                "a floatie zombie has to say which rows it uses");
                    } else {
                        assertEquals(List.of(0, 1, 4, 5), entry.rows(),
                                "and a walker must stay off the water");
                    }
                }
            }
        }
    }

    /**
     * A tombstone may not stand in the pool.
     *
     * <p>The mutation levels put their opening graves on the same 9x6 board the pool levels use,
     * so two of their six rows are water. The scatter used to be free to pick those rows - the
     * only gate was "is there a scene element here, and is anything planted on it" - and a stone
     * raised in a pool lane punches a hole in the water, refuses the lily pad that belongs
     * there, and leaves a gravestone drawn over a lawn the level hides. Meanwhile the walkers
     * were already kept off those rows (see the floatie test above), which is the tell that the
     * water was never meant to hold anything.
     */
    @Test
    void aGravestoneNeverStandsOnThePoolsWater() {
        LevelDef def = BuiltInRegistries.LEVELS.get(
                MutationLevels.levelIds().get(MutationDifficulty.DEFAULT.ordinal()));
        assertNotNull(def);
        com.pvzce.server.level.LevelServer level =
                new com.pvzce.server.level.LevelServer(def, def.slots(),
                        com.pvzce.server.level.LevelServer.SeedContext.all(def));

        for (int x = 0; x < level.width(); x++) {
            for (int y = 0; y < level.height(); y++) {
                boolean water = com.pvzce.common.core.PlantPlacement.terrainTagged(
                        com.pvzce.common.core.PlantPlacement.Terrain.of(level.sceneAt(x, y)),
                        com.pvzce.common.tag.PvzceTags.SCENE_WATER);
                boolean placed = level.placeGrave(PvzceIds.id("grave"), x, y);
                if (water) {
                    assertTrue(!placed, "a gravestone was raised in the water at " + x + "," + y);
                }
            }
        }

        // And the same thing asked of the whole board the mode actually opens with: whatever the
        // scatter managed to place, none of it is in the pool.
        for (var grave : level.graveCells()) {
            assertTrue(!com.pvzce.common.core.PlantPlacement.terrainTagged(
                            com.pvzce.common.core.PlantPlacement.Terrain.of(
                                    level.sceneAt(grave.x(), grave.y())),
                            com.pvzce.common.tag.PvzceTags.SCENE_WATER),
                    "a gravestone ended up in the water at " + grave.x() + "," + grave.y());
        }
    }

    /** The rules a test overrides, built the way {@code TestLevels} wants them. */
    static Map<Identifier, JsonElement> rulesWith(Object... pairs) {
        Map<Identifier, JsonElement> rules = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            rules.put((Identifier) pairs[i], new JsonPrimitive((Number) pairs[i + 1]));
        }
        return rules;
    }

    /** A mutation level with its rules changed, for the manager's own tests. */
    static LevelDef levelWith(MutationDifficulty tier, Map<Identifier, JsonElement> overrides) {
        LevelDef source = BuiltInRegistries.LEVELS.get(
                MutationLevels.levelIds().get(tier.ordinal()));
        Map<Identifier, JsonElement> rules = new LinkedHashMap<>(source.rules());
        rules.putAll(overrides);
        return TestLevels.copy(source).rules(rules).build();
    }
}
