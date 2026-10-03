package com.pvzce.server;

import com.pvzce.api.content.FogData;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.VaseFieldData;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.buff.BuiltInBuffs;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.FogMechanic;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * World 4: ten levels of fog, and the two rewards that are not cards.
 *
 * <p>What is worth pinning here is what a player would notice if it broke. The fog is the world's
 * difficulty curve and lives in ten files, so it is asserted as a curve rather than as ten
 * numbers. 4-5 is the original's Scary Potter level - a night lawn of vases with no waves at all,
 * so a 4-5 that lost them would be an empty level rather than a hard one. And 4-9's reward is the
 * buff that shortens the fog the world is about - a reward that has to be visible in the fog the
 * very next level draws.
 */
class FogWorldLevelsTest {
    private static final List<String> LEVELS = List.of(
            "4_1", "4_2", "4_3", "4_4", "4_5", "4_6", "4_7", "4_8", "4_9", "4_10");

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static LevelDef level(String path) {
        LevelDef def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/" + path));
        assertNotNull(def, path + " has to exist");
        return def;
    }

    /**
     * Nine of the ten are the foggy pool; 4-5 is the night-lawn vase level and 4-10 is the storm.
     *
     * <p>Both exceptions are the original's own, and both are cases of a level in the Fog area
     * that is not a foggy night pool: `PickBackground` sends Scary Potter back to the night lawn,
     * and 4-10 - the area's finale - is the game's one thunderstorm, with no fog in it at all
     * ("there is not actually any fog in this level, despite being on the Fog stage"). What the
     * player sees there is the storm's own darkness, which covers the whole board rather than its
     * right-hand side.
     */
    @Test
    void theFogLevelsAreTheFoggyPoolAndFourFiveIsTheNightLawn() {
        for (String path : LEVELS) {
            LevelDef def = level(path);
            assertEquals(9, def.width(), path + " is nine columns");
            if (path.equals("4_5")) {
                // `PickBackground` sends a Scary Potter level back to the night lawn: five rows,
                // no water, and no fog either.
                assertEquals(5, def.height(), "4-5 is a five-lane lawn");
                assertFalse(def.scene().containsKey(PvzceIds.WATER), "and has no pool");
                assertEquals(Identifier.withDefaultNamespace(
                                "textures/gui/screen/level/background2"),
                        def.background().orElseThrow(),
                        "4-5 is played on the night lawn");
                assertNull(LevelMechanics.fogData(def), "and has no fog to speak of");
                continue;
            }
            assertEquals(6, def.height(), path + " is six rows, because the pool is");
            assertTrue(def.scene().containsKey(PvzceIds.WATER), path + " has water");
            assertEquals(Identifier.withDefaultNamespace(
                            "textures/gui/screen/level/background4"),
                    def.background().orElseThrow(),
                    path + " uses the night pool backdrop, which is what tells LevelStage how to"
                            + " draw it");
            if (path.equals("4_10")) {
                assertNull(LevelMechanics.fogData(def),
                        "4-10 is the storm level: the fog of the nine before it is what the storm"
                                + " comes out of, and the level itself has none");
                continue;
            }
            assertNotNull(LevelMechanics.fogData(def), path + " has to declare fog: that is world 4");
        }
    }

    /**
     * 4-10 is the storm the world's fog was building up to, and it is still a conveyor level.
     *
     * <p>Both halves are one bug. The level was rebuilt from the original's own tables in a pass
     * that keyed the conveyor on "is this a mini-boss or a minigame", 4-10 is neither, and it
     * shipped as an ordinary level: a card chooser for a level that deals its own cards, a wave
     * table walking in off the road, a `music` block playing the night theme, and a fog span that
     * claimed to be the deepest in the world. What the original has there is the one storm level
     * in the game, and it is a conveyor level with no fog - so this asserts the four facts
     * together, because any one of them alone still reads as a different level.
     */
    @Test
    void fourTenIsTheStormAndItDealsItsOwnCards() {
        LevelDef def = level("4_10");
        assertNotNull(LevelMechanics.stormData(def), "4-10 declares the storm mechanic");
        assertNull(LevelMechanics.fogData(def), "and no fog: the storm is the darkness");
        assertTrue(LevelMechanics.dealsItsOwnCards(def),
                "its cards come off a belt, like the other three area finales'");
        assertTrue(com.pvzce.testutil.TestLevels.plantSlots(def).isEmpty(),
                "a belt level carries no deck of its own");
        assertTrue(def.music().cues().isEmpty(),
                "and it has no background music: in the original this is the one level whose"
                        + " soundtrack is the rain, and the level data says so by saying nothing");
    }

    /**
     * The fog's edge only ever walks towards the house, in the original's three steps.
     *
     * <p>`Board::LeftFogColumn`: 4-1 hides the last three columns, 4-2 to 4-6 hide four, and 4-7
     * onwards hide five. Written as a claim about the sequence rather than as ten numbers, because
     * ten numbers in a test is a second copy of the data that can agree with itself while the
     * levels say something else.
     */
    @Test
    void theFogRampOnlyEverMovesTowardsTheHouse() {
        float previous = Float.MAX_VALUE;
        for (String path : LEVELS) {
            FogData fog = LevelMechanics.fogData(level(path));
            if (path.equals("4_5") || path.equals("4_10")) {
                continue; // neither is a foggy board: the vase level and the storm
            }
            assertNotNull(fog, path + " has fog");
            assertTrue(fog.startColumn() <= previous,
                    path + " starts its fog at " + fog.startColumn()
                            + ", further from the house than the level before it");
            assertTrue(fog.startColumn() >= 0F && fog.startColumn() < fog.endColumn(),
                    path + " declares a fog span that is inside the board");
            previous = fog.startColumn();
        }
        assertEquals(6F, LevelMechanics.fogData(level("4_1")).startColumn(), 0.001F,
                "4-1 is the original's first fog step");
        assertEquals(4F, LevelMechanics.fogData(level("4_9")).startColumn(), 0.001F,
                "and 4-9 is the deepest fog the world has: `Board::LeftFogColumn` stops at 4.0, and"
                        + " 4-10 - which the old ramp claimed was deeper still - has no fog at all");
    }

    /** The chain runs 3-10 → 4-1 → … → 4-10 with no gap. */
    @Test
    void theUnlockChainHasNoGap() {
        for (int index = 1; index <= 10; index++) {
            LevelDef def = level("4_" + index);
            List<String> requires = def.unlock().requires().stream()
                    .map(require -> require.id().orElseThrow().toString()).toList();
            String previous = index == 1 ? "3_10" : "4_" + (index - 1);
            assertEquals(List.of("pvzce:yard/adventure/" + previous), requires,
                    "4-" + index + " has to be gated on the level before it");
        }
    }

    /**
     * Every reward is the thing that level is about, and the two non-card rewards exist.
     *
     * <p>4-4 hands out the vase <em>tool</em> and 4-9 a buff; a level whose reward is a tool and a
     * level whose reward is a plant are two shapes in one field, which is why the type is checked
     * next to the id.
     */
    @Test
    void theRewardsAreWhatEachLevelIsAbout() {
        // The original's own chain through the fog: the plantern first, because the dark is what
        // this world is about, and the cabbage-pult last, because it is the first plant of the
        // roof world the player is about to unlock.
        assertEquals("pvzce:plantern", firstRewardId("4_1"));
        assertEquals("pvzce:cactus", firstRewardId("4_2"));
        assertEquals("pvzce:blover", firstRewardId("4_3"));
        assertEquals("pvzce:vase", firstRewardId("4_4"));
        assertEquals("pvzce:split_pea", firstRewardId("4_5"));
        assertEquals("pvzce:starfruit", firstRewardId("4_6"));
        assertEquals("pvzce:pumpkin", firstRewardId("4_7"));
        assertEquals("pvzce:magnet_shroom", firstRewardId("4_8"));
        assertEquals("pvzce:fog_retreat", firstRewardId("4_9"));
        assertEquals("pvzce:cabbage_pult", firstRewardId("4_10"));

        assertEquals("unlock", level("4_4").rewards().firstClear().get(0).type(),
                "4-4 unlocks a card: the vase tool is what the player carries out of it");
        LevelDef nine = level("4_9");
        assertEquals("buff", nine.rewards().firstClear().get(0).type(),
                "4-9 hands out the retreat buff rather than a card");
        assertTrue(BuiltInRegistries.LEVEL_BUFFS.containsKey(PvzceIds.BUFF_FOG_RETREAT),
                "and that buff has to exist, or the reward would grant nothing");
    }

    /**
     * 4-5 is the original's vase level: three rounds of pots, and no waves at all.
     *
     * <p>Its plants, and half its zombies, are inside the pots - so a 4-5 that lost them would be
     * a level with nothing in it, which is exactly what an empty wave table looks like when a
     * mechanic goes missing.
     */
    @Test
    void fourFiveArmsThePlayerOutOfVases() {
        LevelDef def = level("4_5");
        assertTrue(def.waves().isEmpty(), "a vase level has no waves: nothing walks in");
        var rounds = LevelMechanics.dataOf(def, Identifier.parse("pvzce:scary_potter"),
                com.pvzce.api.content.ScaryPotterData.class);
        assertTrue(rounds.isPresent(), "4-5 has to declare its pots");
        assertEquals(3, rounds.orElseThrow().rounds().size(),
                "the original's adventure vase level is three rounds");
        assertEquals(List.of(6, 5, 4), rounds.orElseThrow().rounds().stream()
                        .map(com.pvzce.api.content.ScaryPotterData.Round::fromColumn).toList(),
                "and each round reaches one column further towards the house");
        // Two cards, and the second one is the *bar* rather than a plant: without the sun card in
        // it the resource is not collectible at all (`collectible_without_card: false`), so the
        // sun out of the pots and the sun off the zombies would both be dead drops - and the bank
        // HUD, which draws only for a bar that carries the card, would never appear either.
        assertEquals(List.of("pvzce:sun", "pvzce:cherry_bomb"),
                def.slots().stream().map(Identifier::toString).toList(),
                "the player is handed the sun card and one plant: a cherry bomb");
    }

    /** Every wave names its lanes, and every level ends on a final wave. */
    @Test
    void everyWaveSendsSomething() {
        for (String path : LEVELS) {
            LevelDef def = level(path);
            if (path.equals("4_5")) {
                continue; // the vase level: its zombies come out of the pots
            }
            assertFalse(def.waves().isEmpty(), path + " has waves");
            for (int index = 0; index < def.waves().size(); index++) {
                var wave = def.waves().get(index);
                assertFalse(wave.entries().isEmpty(), path + " wave " + index + " sends nothing");
                for (var entry : wave.entries()) {
                    assertTrue(entry.count() > 0,
                            path + " wave " + index + " asks for " + entry.count() + " of "
                                    + entry.id());
                    assertTrue(entry.restrictedToRows(),
                            path + " wave " + index + " has an entry with no lanes named, which on"
                                    + " a pool board is how a land zombie ends up in the water");
                }
            }
            List<String> types = new ArrayList<>(
                    def.waves().stream().map(wave -> wave.type().name().toLowerCase()).toList());
            assertTrue(types.contains("final"), path + " ends on a final wave");
        }
    }

    /**
     * The vase level's preview names what its pots are hiding.
     *
     * <p>4-5's wave table is empty by design - every zombie in the level comes out of a vase - so a
     * preview built from the waves alone showed a lawn and no zombie at all on the one screen that
     * exists to say what a level sends at the player. The list is asked of the mechanic now, which
     * is the layer that knows what a pot holds.
     */
    @Test
    void theVaseLevelPreviewsWhatItsPotsHold() {
        LevelDef def = level("4_5");
        assertTrue(def.previewZombieIds().isEmpty(), "4-5's waves name nobody");

        List<String> preview = LevelMechanics.previewZombieIds(def);
        assertFalse(preview.isEmpty(), "so the preview has to come from the pots");
        assertTrue(preview.contains("pvzce:basic_zombie"), "the vases are full of them: " + preview);
        assertTrue(preview.contains("pvzce:football_zombie"), "and of worse: " + preview);
        assertFalse(preview.contains("pvzce:pea_shooter"),
                "while the plants inside the other pots are not zombies: " + preview);
    }

    /** The world's other levels still preview their waves, now with their mechanics appended. */
    @Test
    void theWaveLevelsPreviewTheirWaves() {
        for (String path : LEVELS) {
            LevelDef def = level(path);
            List<String> waves = def.previewZombieIds();
            List<String> preview = LevelMechanics.previewZombieIds(def);
            assertTrue(preview.size() >= waves.size(), path + " previews at least its waves");
            assertEquals(waves, preview.subList(0, waves.size()),
                    path + " keeps its wave table's order at the front of the preview");
        }
        assertTrue(LevelMechanics.previewZombieIds(level("4_1"))
                        .contains("pvzce:jack_in_the_box_zombie"),
                "and 4-1 still leads with the zombies its waves send");
    }

    /**
     * Every lane of the pool gets its share of the world.
     *
     * <p>The bug this pins was a bias in the lane deal, not in the director: the generator restarted
     * its lane cursor at zero for every wave, so the first zombie of each wave was dealt row 0 and -
     * because most of these tables are one- and two-zombie waves - entry after entry was narrowed to
     * `rows: [0]`. The director deals the lanes an entry names and nothing else, so the level put
     * nearly everything in the bottom two rows. Measured on 4-1 before the fix: rows 0/1 took 19 of
     * 21 zombies, the water took 2, and rows 4/5 took none at all - two lanes a player never saw a
     * zombie in.
     *
     * <p>Asserted as a spread rather than as counts: the water rows can only take swimmers (a walker
     * dealt into the pool would drown, which is why `usableLanes` refuses it), so the four land rows
     * are what has to come out even.
     */
    @Test
    void everyLaneGetsItsShare() {
        for (String path : LEVELS) {
            LevelDef def = level(path);
            if (def.waves().isEmpty()) {
                continue; // the vase level: its zombies come out of the pots
            }
            int[] perRow = new int[def.height()];
            int total = 0;
            for (var wave : def.waves()) {
                for (var entry : wave.entries()) {
                    List<Integer> rows = entry.restrictedToRows()
                            ? entry.rows() : List.of(0);
                    for (int i = 0; i < entry.count(); i++) {
                        // The director's own rotation: `rowFor` walks the entry's lanes in order.
                        perRow[rows.get(i % rows.size())]++;
                        total++;
                    }
                }
            }
            for (int row = 0; row < perRow.length; row++) {
                assertTrue(perRow[row] > 0,
                        path + " sends nothing down row " + row + ": " + java.util.Arrays.toString(perRow));
            }
            int bottom = perRow[0] + perRow[1];
            assertTrue(bottom * 10 < total * 6,
                    path + " puts " + bottom + " of " + total + " zombies in the bottom two rows: "
                            + java.util.Arrays.toString(perRow));
        }
    }

    /** The world's new bodies turn up: the balloon is what the fog is hiding. */
    @Test
    void theNewZombiesAppear() {
        Set<String> seen = new LinkedHashSet<>();
        for (String path : LEVELS) {
            for (var wave : level(path).waves()) {
                for (var entry : wave.entries()) {
                    seen.add(entry.id().path());
                }
            }
        }
        assertTrue(seen.contains("balloon_zombie"), "the balloon arrives out of the fog");
        assertTrue(seen.contains("jack_in_the_box_zombie"), "the jack-in-the-box debuts here");
        assertTrue(seen.contains("miner_zombie"), "and the digger comes up behind the lawn");
        assertTrue(seen.contains("pogo_zombie"), "with the pogo zombie to finish the world");
        assertFalse(seen.contains("snorkel_zombie"), "the divers stay in world 3");
        assertFalse(seen.contains("gargantuar"), "and no Gargantuar before the roof");
    }

    /**
     * The retreat buff moves the fog, on a real level and with nothing else changed.
     *
     * <p>Fog is presentation, so the number this test reads is the number the renderer reads -
     * there is no second copy of "how far in is the fog" anywhere in the simulation.
     *
     * <p>Run on 4-9, the level the buff is the reward for: the player carries it out of 4-9 and
     * sees it on the next level they play, which is any of the fog levels they replay. It used to
     * run on 4-10, and that is one of the ways the level's real shape stayed hidden - a level with
     * no fog cannot show anything about fog, and the test that "proved" the buff moved the fog was
     * reading a span that should not have been there at all.
     */
    @Test
    void theRetreatBuffPushesTheFogBack() {
        LevelDef def = TestLevels.copy(level("4_9")).waves(List.of()).build();
        LevelServer without = new LevelServer(def);
        LevelServer with = new LevelServer(def);
        with.setActiveBuffs(List.of(BuiltInBuffs.FOG_RETREAT));

        FogData plain = without.fogData();
        FogData retreated = with.fogData();
        assertNotNull(plain);
        assertNotNull(retreated);
        assertEquals(plain.startColumn() + BuiltInBuffs.FOG_RETREAT.fogRetreat(),
                retreated.startColumn(), 0.001F,
                "the buff moves the fog's edge away from the house by its own number");
        assertEquals(plain.endColumn() + BuiltInBuffs.FOG_RETREAT.fogRetreat(),
                retreated.endColumn(), 0.001F,
                "the fog is a band, so the far edge travels with the near one -"
                        + " a span that only grew on the near side would be a fog that got"
                        + " thinner rather than one that moved");
        assertTrue(FogMechanic.alphaAt(retreated, List.of(), 8, 0)
                        < FogMechanic.alphaAt(plain, List.of(), 8, 0),
                "and the cell at the back of the lawn is genuinely less dark for it");
    }

    private static String firstRewardId(String path) {
        return level(path).rewards().firstClear().get(0).id().orElseThrow().toString();
    }
}
