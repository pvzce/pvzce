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

    /** Nine of the ten are the foggy pool; 4-5 is the original's night-lawn vase level. */
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
            assertNotNull(LevelMechanics.fogData(def), path + " has to declare fog: that is world 4");
        }
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
            if (path.equals("4_5")) {
                continue; // the vase level is played on the clear night lawn
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
        assertEquals(4F, LevelMechanics.fogData(level("4_10")).startColumn(), 0.001F,
                "and the last level is the deepest one, with no step back");
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
        assertEquals(1, def.slots().size(), "the player is handed one card: a cherry bomb");
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
     * there is no second copy of "how far in is the fog" anywhere in the simulation. 4-10 is the
     * level the buff is meant to be seen on, so it is the level the test runs on.
     */
    @Test
    void theRetreatBuffPushesTheFogBack() {
        LevelDef def = TestLevels.copy(level("4_10")).waves(List.of()).build();
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
