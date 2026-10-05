package com.pvzce.server;

import com.pvzce.api.content.FogData;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The adventure tables, including the explicitly customized 5-10 conveyor finale.
 *
 * <p>Levels 1..49 replay the original's own wave
 * generator, and the tables at the bottom of this file are the original's
 * ({@code gZombieWaves}, {@code gZombieDefs}, {@code gZombieAllowedLevels},
 * {@code LawnApp::GetAwardSeedForLevel}). This test re-derives the parts of a level that are
 * deterministic in the original and checks the shipped files against the answer:
 *
 * <ul>
 *   <li>how many waves the level has, and which of them carry a flag;</li>
 *   <li>that no wave sends a zombie the level does not allow - the claim that worlds 3 and 4
 *       never see a newspaper, a screen door or a dancing zombie, that the football zombie only
 *       turns up on 4-2, that the pole vaulter only turns up on 3-9, and so on;</li>
 *   <li>that the final wave sends every type the level allows (the original's
 *       {@code PutInMissingZombies});</li>
 *   <li>that a level's newly introduced zombie arrives where the original puts it - the middle
 *       wave and the final one, or the seventh and the final for the digger and the balloon;</li>
 *   <li>that the reward chain is the original's, with this project's own ten substitutions on
 *       the levels the original leaves empty;</li>
 *   <li>the stage facts that come with the level's number: its background, whether it is night,
 *       how tall the board is, where the fog starts, which levels deal their own cards, and how
 *       much sun it starts with.</li>
 * </ul>
 *
 * <p>The tables are copied here deliberately. A test that read them out of the generator would
 * only prove the generator agrees with itself; this one is a second, independent statement of
 * what the original says, and the two have to agree. {@code tools/original_levels.py} names the
 * decompiled function each table comes from.
 * The user-defined 5-10 has thirty waves, the 5-9 enemy pool and a Gloom-shroom reward;
 * it deliberately replaces the original's boss encounter.
 */
class OriginalAdventureLevelsTest {
    /** The ten levels the original leaves empty, filled with this project's own tools/buffs. */
    private static final Map<Integer, String> SUBSTITUTE_REWARDS = Map.of(
            4, "glove",
            9, "auto_collect",
            14, "hammer",
            19, "mushroom_range",
            24, "watering_can",
            29, "kelp_spread",
            34, "vase",
            39, "fog_retreat",
            44, "fertilizer",
            49, "butter_plenty");

    /** The zombie that walks in the pool: this project draws it as a type of its own. */
    private static final Map<String, String> POOL_FORMS = Map.of(
            "ducky_tube_zombie", "basic_zombie",
            "ducky_tube_conehead_zombie", "conehead_zombie",
            "ducky_tube_buckethead_zombie", "buckethead_zombie");

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static int waveCount(int number) {
        if (number == 35) {
            return 0; // Scary Potter: its zombies come out of the vases, there are no waves
        }
        if (number == 15) {
            return 8; // Whack-a-Zombie runs eight waves of its own pacing
        }
        return ZOMBIE_WAVES[number - 1];
    }

    private static LevelDef level(int number) {
        String name = "%d_%d".formatted((number - 1) / 10 + 1, (number - 1) % 10 + 1);
        LevelDef def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/" + name));
        assertNotNull(def, name + " must be part of the built-in adventure");
        return def;
    }

    private static String label(int number) {
        return "%d-%d".formatted((number - 1) / 10 + 1, (number - 1) % 10 + 1);
    }

    /** One-based numbers of the waves that carry a flag. */
    private static Set<Integer> flagWaves(int number) {
        int count = waveCount(number);
        Set<Integer> flags = new LinkedHashSet<>();
        if (count == 0 || number == 1) {
            return flags;
        }
        int perFlag = Math.min(count, 10);
        for (int wave = 1; wave <= count; wave++) {
            if (wave % perFlag == 0) {
                flags.add(wave);
            }
        }
        return flags;
    }

    /** The types a level's random picks may draw (its {@code mZombieAllowed}). */
    private static Set<String> poolFor(int number) {
        Set<String> pool = new LinkedHashSet<>();
        if (number == 50) return poolFor(49); // User-defined finale: the preceding roof's enemies.
        if (number == 45) return Set.of("basic_zombie", "conehead_zombie", "buckethead_zombie", "ladder");
        for (ZombieType zombie : ZOMBIES) {
            if (zombie.pickableOn(number)) {
                pool.add(zombie.id());
            }
        }
        return pool;
    }

    /** A wave's zombies, with a walker's pool drawing folded back onto its own type. */
    private static Set<String> sentBy(WaveDef wave) {
        Set<String> out = new LinkedHashSet<>();
        for (WaveDef.Entry entry : wave.entries()) {
            String path = entry.id().path();
            String id = path.substring(path.lastIndexOf('/') + 1);
            out.add(POOL_FORMS.getOrDefault(id, id));
        }
        return out;
    }

    /** {@code LawnApp::GetAwardSeedForLevel}: seeds owned while playing {@code level}. */
    private static int seedsAvailable(int level) {
        int area = (level - 1) / 10 + 1;
        int sub = (level - 1) % 10 + 1;
        int count = (area - 1) * 8 + sub;
        if (sub >= 10) {
            count -= 2; // two levels of each area hand out nothing
        } else if (sub >= 5) {
            count -= 1; // and one more hands out nothing either
        }
        return Math.min(count, AWARD_SEEDS.size());
    }

    @Test
    void everyLevelHasTheOriginalsWaveCount() {
        for (int number = 1; number <= 50; number++) {
            assertEquals(waveCount(number), level(number).waves().size(),
                    label(number) + " must have the original's number of waves");
        }
    }

    @Test
    void flagsStandWhereTheOriginalRaisesThem() {
        for (int number = 1; number <= 50; number++) {
            LevelDef def = level(number);
            int count = def.waves().size();
            Set<Integer> flags = flagWaves(number);
            for (int index = 0; index < count; index++) {
                WaveDef wave = def.waves().get(index);
                int oneBased = index + 1;
                if (oneBased == count) {
                    assertEquals(WaveDef.WaveType.FINAL, wave.type(),
                            label(number) + " must end on a final wave");
                } else if (flags.contains(oneBased)) {
                    assertEquals(WaveDef.WaveType.HUGE, wave.type(),
                            label(number) + " wave " + oneBased + " carries a flag");
                } else {
                    assertEquals(WaveDef.WaveType.SMALL, wave.type(),
                            label(number) + " wave " + oneBased + " is an ordinary wave");
                }
                if (flags.contains(oneBased)) {
                    assertEquals(750, wave.warningTicks(), label(number) + " wave " + oneBased
                            + " must give the original's 750-tick huge-wave warning");
                }
                assertTrue(wave.delay() > 0, label(number) + " wave " + oneBased + " has a delay");
            }
        }
    }

    @Test
    void noLevelEverSendsAZombieItDoesNotAllow() {
        for (int number = 1; number <= 50; number++) {
            if (number == 15 || number == 35) {
                continue; // the two levels whose zombies do not arrive in waves
            }
            Set<String> pool = poolFor(number);
            LevelDef def = level(number);
            for (int index = 0; index < def.waves().size(); index++) {
                for (String id : sentBy(def.waves().get(index))) {
                    if (id.equals("flag_zombie") || number == 45 && id.equals("bungee_zombie") && flagWaves(number).contains(index + 1)) {
                        continue; // placed by the flag-wave rule, never picked
                    }
                    assertTrue(pool.contains(id), label(number) + " wave " + (index + 1) + " sends "
                            + id + ", which that level does not allow");
                }
            }
        }
    }

    @Test
    void theFinalWaveSendsEveryZombieTheLevelAllows() {
        for (int number = 1; number <= 50; number++) {
            if (number == 15 || number == 35) {
                continue; // the two levels whose zombies do not arrive in waves
            }
            LevelDef def = level(number);
            WaveDef last = def.waves().get(def.waves().size() - 1);
            Set<String> sent = sentBy(last);
            for (String id : poolFor(number)) {
                assertTrue(sent.contains(id), label(number) + "'s final wave must contain " + id
                        + ", which the level allows");
            }
        }
    }

    @Test
    void newlyIntroducedZombiesArriveWhereTheOriginalPutsThem() {
        for (int number = 2; number <= 49; number++) {
            ZombieType intro = null;
            for (ZombieType zombie : ZOMBIES) {
                if (zombie.startingLevel() != number) {
                    continue;
                }
                // The yeti needs a finished adventure, and the ducky tube is skipped by name in
                // the original's own loop: a ducky is how a walker is drawn in the pool, so 3-1
                // has nothing to show off.
                if (zombie.id().equals("yeti") || zombie.id().equals("ducky_tube_zombie")) {
                    continue;
                }
                intro = zombie;
                break;
            }
            int count = waveCount(number);
            if (intro == null || count == 0) {
                continue;
            }
            LevelDef def = level(number);
            // The digger and the balloon are the two the original shows off in the seventh wave;
            // every other newcomer waits for the middle of the level. All of them appear in the
            // final wave as well.
            int first = switch (intro.id()) {
                case "miner_zombie", "balloon_zombie" -> 7;
                default -> count / 2 + 1;
            };
            for (int oneBased : List.of(first, count)) {
                assertTrue(sentBy(def.waves().get(oneBased - 1)).contains(intro.id()),
                        label(number) + " wave " + oneBased + " must show off " + intro.id()
                                + ", the zombie that level introduces");
            }
        }
    }

    @Test
    void theRewardChainMatchesTheApprovedAdventure() {
        for (int number = 1; number <= 50; number++) {
            LevelDef def = level(number);
            String expected = number == 50 ? "gloom_shroom" : SUBSTITUTE_REWARDS.get(number);
            if (expected == null) {
                int before = seedsAvailable(number);
                int after = seedsAvailable(number + 1);
                expected = after > before ? AWARD_SEEDS.get(after - 1) : null;
            }
            List<String> handed = def.rewards().firstClear().stream()
                    .flatMap(reward -> reward.id().stream())
                    .map(Identifier::path)
                    .toList();
            if (expected == null) {
                assertTrue(handed.isEmpty(),
                        label(number) + " hands out nothing new in the original, and hands out "
                                + handed);
            } else {
                assertTrue(handed.contains(expected),
                        label(number) + " must hand out " + expected + ", and hands out " + handed);
            }
        }
    }

    @Test
    void theStageFactsComeFromTheLevelNumber() {
        for (int number = 1; number <= 50; number++) {
            LevelDef def = level(number);
            String name = label(number);

            int expectedHeight = switch (number) {
                case 1 -> 1;
                case 2, 3 -> 3;
                default -> number >= 21 && number <= 40 && number != 35 ? 6 : 5;
            };
            assertEquals(expectedHeight, def.height(), name + "'s board height");

            String background = def.background().orElseThrow().toString();
            if (number <= 3) {
                assertTrue(background.endsWith("background1_1row")
                                || background.endsWith("background1_3row"),
                        name + " plays on the unsodded lawn, and uses " + background);
            } else {
                String expected = switch (number == 35 ? "night" : switch ((number - 1) / 10) {
                    case 0 -> "day";
                    case 1 -> "night";
                    case 2 -> "pool";
                    case 3 -> "fog";
                    default -> "roof";
                }) {
                    case "day" -> "background1";
                    case "night" -> "background2";
                    case "pool" -> "background3";
                    case "fog" -> "background4";
                    default -> "background5";
                };
                assertTrue(background.endsWith(expected),
                        name + " must use " + expected + ", and uses " + background);
            }

            // 2-x and 4-x are night (`StageIsNight` covers the fog background), and 4-5 -
            // the vase level - is played on the night lawn rather than in the fog.
            boolean night = number >= 11 && number <= 20 || number >= 31 && number <= 40;
            var sunInterval = def.rules().get(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX);
            boolean skySun = !night && !isConveyor(number);
            assertEquals(skySun, sunInterval != null && sunInterval.getAsFloat() > 0F,
                    name + " must " + (skySun ? "" : "not ") + "have sun falling from the sky");

            FogData fog = LevelMechanics.fogData(def);
            // The two area-4 levels that are not foggy boards: 4-5 is the vase level on the night
            // lawn, and 4-10 is the storm - the level the fog world spends nine levels building up
            // to, and the one level in the original's Fog area with no fog in it.
            if (number >= 31 && number <= 40 && number != 35 && number != 40) {
                assertNotNull(fog, name + " is a fog level");
                float expectedFog = number == 31 ? 6F : number <= 36 ? 5F : 4F;
                assertEquals(expectedFog, fog.startColumn(), 0.001F,
                        name + " must start its fog where the original does");
            } else {
                assertNull(fog, name + " has no fog");
            }

            if (isConveyor(number)) {
                assertTrue(com.pvzce.testutil.TestLevels.plantSlots(def).isEmpty(),
                        name + " deals its own cards");
            }
            assertEquals(
                    number == 1 ? 150 : (isConveyor(number) || number == 15 || number == 35 ? 0 : 50),
                    def.initialSun(), name + "'s starting sun");
        }
    }

    /**
     * `HasConveyorBeltSeedBank` for adventure levels: the three mini-bosses, the two minigames
     * and the storm.
     *
     * <p>Not the same list as "levels whose cards are not chosen" - Whack-a-Zombie and Scary
     * Potter have fixed little decks instead of a belt - which is why this is spelled out rather
     * than derived from `LevelFacts.deck`.
     *
     * <p><b>4-10 is the entry that was missing.</b> The original's storm level deals its cards off
     * a belt like the other three area finales, and the level was rebuilt from the original's own
     * tables in a pass that asked "is this a mini-boss or a minigame" - 4-10 is neither, so it
     * fell through to the ordinary path and shipped with a card chooser instead of a belt.
     */
    private static boolean isConveyor(int number) {
        return number == 5 || number == 10 || number == 20 || number == 25 || number == 30
                || number == 40 || number == 45 || number == 50;
    }

    @Test
    void theTwoLevelsWithNoWalkInZombiesSayWhoSpawnsThem() {
        // 2-5 (Whack-a-Zombie) and 4-5 (Scary Potter) are the two adventure levels whose
        // zombies do not walk in. A level that lost its spawner or its vases would still pass
        // every test above - it would simply be an empty level.
        LevelDef whack = level(15);
        assertTrue(LevelMechanics.dataOf(whack, Identifier.parse("pvzce:grave_spawner"),
                        com.pvzce.api.content.GraveSpawnerData.class).isPresent(),
                "2-5 must raise its zombies out of the gravestones");
        for (WaveDef wave : whack.waves()) {
            assertTrue(wave.entries().isEmpty(), "2-5 sends nothing down the road");
        }

        LevelDef potter = level(35);
        assertEquals(0, potter.waves().size(), "4-5 has no waves at all");
        assertEquals(5, potter.height(), "4-5 is a night lawn, not the fog pool");
        assertEquals(java.util.List.of("pvzce:sun", "pvzce:cherry_bomb"),
                potter.slots().stream().map(Identifier::toString).toList(),
                "4-5 hands the player the sun card and one plant - the sun card because nothing"
                        + " in the level is collectable without it");
    }

    @Test
    void noAdventureLevelSendsTheZombiesWorldsThreeAndFourNeverHave() {
        // The most visible thing this round fixed: worlds 3 and 4 used to send newspaper,
        // screen-door and dancing zombies, and a gargantuar - none of which the original allows
        // there. Stated once, by name, so a regression reads as itself rather than as a table
        // mismatch.
        // Newspaper is deliberately not in this list: `gZombieAllowedLevels` allows it on 3-2
        // and 3-4, and those two levels send it.
        Set<String> neverInPoolOrFog = Set.of(
                "door_zombie", "dancing_zombie", "backup_dancer",
                "gargantuar", "imp", "bungee_zombie");
        for (int number = 21; number <= 40; number++) {
            if (number == 35) {
                continue; // vase level: its zombies come out of the pots
            }
            for (WaveDef wave : level(number).waves()) {
                for (String id : sentBy(wave)) {
                    assertFalse(neverInPoolOrFog.contains(id), label(number) + " sends " + id
                            + ", which the original never sends in worlds 3 and 4");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // The original's tables
    // ------------------------------------------------------------------

    /** `gZombieWaves`: how many waves each of the fifty adventure levels has. */
    private static final int[] ZOMBIE_WAVES = {
             4,  6,  8, 10,  8, 10, 20, 10, 20, 20,
            10, 20, 10, 20, 10, 10, 20, 10, 20, 20,
            10, 20, 20, 30, 20, 20, 30, 20, 30, 30,
            10, 20, 10, 20, 20, 10, 20, 10, 20, 20,
            10, 20, 20, 30, 20, 20, 30, 20, 30, 30,
    };

    /**
     * `gZombieDefs` + `gZombieAllowedLevels`, in the original's enum order.
     *
     * @param id               what this project calls the type
     * @param value            `mZombieValue`: what one costs out of a wave's budget
     * @param startingLevel    `mStartingLevel`: the level that first allows it
     * @param firstAllowedWave `mFirstAllowedWave`: earliest wave it may be picked for
     * @param pickWeight       `mPickWeight`; zero means the rule places it, never a pick
     * @param levels           `gZombieAllowedLevels`, fifty characters, levels 1..50
     */
    private record ZombieType(String id, int value, int startingLevel, int firstAllowedWave,
                              int pickWeight, String levels) {
        boolean pickableOn(int level) {
            return pickWeight > 0 && startingLevel <= level && levels.charAt(level - 1) == '1';
        }
    }

    private static final List<ZombieType> ZOMBIES = List.of(
            new ZombieType("basic_zombie", 1, 1, 1, 4000,
                    "11111111111111111111111111111111111111111111111111"),
            new ZombieType("flag_zombie", 1, 1, 1, 0,
                    "11111111111111111111111111111111111111111111111111"),
            new ZombieType("conehead_zombie", 2, 3, 1, 4000,
                    "00111111110111111111111111111111111111111111111111"),
            new ZombieType("pole_vaulter_zombie", 2, 6, 5, 2000,
                    "00000110110001100000000100001000000000000100000000"),
            new ZombieType("buckethead_zombie", 4, 8, 1, 3000,
                    "00000001110100100000010100101100000010110100100011"),
            new ZombieType("newspaper_zombie", 2, 11, 1, 1000,
                    "00000000001100100000010100000000000000000000000000"),
            new ZombieType("door_zombie", 4, 13, 5, 3500,
                    "00000000000011001011000000000000000000000000000000"),
            new ZombieType("football_zombie", 7, 16, 5, 2000,
                    "00000000000000011001010010000001000000000001000000"),
            new ZombieType("dancing_zombie", 5, 18, 5, 1000,
                    "00000000000000000111000000000000000000000000000000"),
            new ZombieType("backup_dancer", 1, 18, 1, 0,
                    "00000000000000000111000000000000000000000000000000"),
            new ZombieType("ducky_tube_zombie", 1, 21, 5, 0,
                    "00000000000000000000000000000000000000000000000000"),
            new ZombieType("snorkel_zombie", 3, 23, 10, 2000,
                    "00000000000000000000001110100100000000000000000000"),
            new ZombieType("zamboni_zombie", 7, 26, 10, 2000,
                    "00000000000000000000000001101100000000000000000000"),
            new ZombieType("bobsled_zombie", 3, 26, 10, 2000,
                    "00000000000000000000000001101100000000000000000000"),
            new ZombieType("dolphin_rider_zombie", 3, 28, 10, 1500,
                    "00000000000000000000000000011100010000000000000000"),
            new ZombieType("jack_in_the_box_zombie", 3, 31, 10, 1000,
                    "00000000000000000000000000000011000010010000000011"),
            new ZombieType("balloon_zombie", 2, 33, 10, 2000,
                    "00000000000000000000000000000000110000110000000000"),
            new ZombieType("miner_zombie", 4, 36, 10, 1000,
                    "00000000000000000000000000000000000110010000000000"),
            new ZombieType("pogo_zombie", 4, 38, 10, 1000,
                    "00000000000000000000000000000000000001110001000000"),
            new ZombieType("bungee_zombie", 3, 41, 10, 1000,
                    "00000000000000000000000000000000000000001100001011"),
            new ZombieType("ladder", 4, 43, 10, 1000,
                    "00000000000000000000000000000000000000000011101011"),
            new ZombieType("catapult", 5, 46, 10, 1500,
                    "00000000000000000000000000000000000000000000011011"),
            new ZombieType("gargantuar", 10, 48, 15, 1500,
                    "00000000000000000000000000000000000000000000000111"),
            new ZombieType("imp", 10, 48, 1, 0,
                    "00000000000000000000000000000000000000000000000111"),
            new ZombieType("zombie_boss", 10, 50, 1, 0,
                    "00000000000000000000000000000000000000000000000000"));

    /**
     * `LawnApp::GetAwardSeedForLevel`'s order: the seed a level hands out is the one
     * that pushes the count up, i.e. `AWARD_SEEDS[available - 1]` of the next level.
     */
    private static final List<String> AWARD_SEEDS = List.of(
            "pea_shooter", "sunflower", "cherry_bomb", "wall_nut", "potato_mine",
            "snow_pea", "chomper", "repeater", "puff_shroom", "sun_shroom",
            "fume_shroom", "grave_buster", "hypno_shroom", "scaredy_shroom", "ice_shroom",
            "doom_shroom", "lily_pad", "squash", "threepeater", "tangle_kelp",
            "jalapeno", "spikeweed", "torchwood", "tall_nut", "sea_shroom",
            "plantern", "cactus", "blover", "split_pea", "starfruit",
            "pumpkin", "magnet_shroom", "cabbage_pult", "flower_pot", "kernel_pult",
            "coffee_bean", "garlic", "umbrella_leaf", "marigold", "melon_pult");
}
