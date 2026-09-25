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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * World 4: ten fog levels, and the two rewards that are not cards.
 *
 * <p>What is worth pinning here is what a player would notice if it broke. The fog ramp is the
 * world's difficulty curve and lives in ten numbers in ten files, so it is asserted as a curve
 * rather than as ten numbers. 4-4's vases are the level's only source of plants, so a 4-4 that
 * lost them is a level the player cannot play at all. And 4-9's reward is the buff that shortens
 * the fog the world is about - a reward that has to be visible in the fog the very next level
 * draws.
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

    /** All ten are there, they are the pool, and every one of them is foggy. */
    @Test
    void theTenLevelsAreTheFoggyPool() {
        for (String path : LEVELS) {
            LevelDef def = level(path);
            assertEquals(9, def.width(), path + " is nine columns");
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
     * The fog walks right to left, and 4-10 steps back.
     *
     * <p>The curve is the world's whole difficulty ramp beyond the wave tables, and 4-9 is the
     * level that pays out the buff which shortens it - so the ramp is not monotonic by accident:
     * 4-10's step backwards is the reward being visible. Written as a claim about the sequence
     * rather than as ten numbers, because ten numbers in a test is a second copy of the data that
     * can agree with itself while the levels say something else.
     */
    @Test
    void theFogRampOnlyEverMovesTowardsTheHouse() {
        // 4-1 to 4-9: the ramp proper. 4-10 is the step back, asserted below.
        float previous = Float.MAX_VALUE;
        for (String path : LEVELS.subList(0, 9)) {
            FogData fog = LevelMechanics.fogData(level(path));
            assertNotNull(fog, path + " has fog");
            assertTrue(fog.startColumn() <= previous,
                    path + " starts its fog at " + fog.startColumn()
                            + ", further from the house than the level before it");
            assertTrue(fog.startColumn() >= 0F && fog.startColumn() < fog.endColumn(),
                    path + " declares a fog span that is inside the board");
            previous = fog.startColumn();
        }
        // The one deliberate move back, and it is the reward of the level before it.
        assertTrue(LevelMechanics.fogData(level("4_10")).startColumn()
                        > LevelMechanics.fogData(level("4_9")).startColumn(),
                "4-9 hands out the retreat buff, so 4-10 has to be the level where the fog is"
                        + " visibly further away");
        assertTrue(LevelMechanics.fogData(level("4_9")).startColumn() < 2.6F,
                "and 4-9 is the worst of them, or 'the fog recedes' would mean nothing");
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
        assertEquals("pvzce:sea_shroom", firstRewardId("4_1"));
        assertEquals("pvzce:blover", firstRewardId("4_2"));
        assertEquals("pvzce:cactus", firstRewardId("4_3"));
        assertEquals("pvzce:vase", firstRewardId("4_4"));
        assertEquals("pvzce:starfruit", firstRewardId("4_5"));
        assertEquals("pvzce:pumpkin", firstRewardId("4_6"));
        assertEquals("pvzce:magnet_shroom", firstRewardId("4_7"));
        assertEquals("pvzce:split_pea", firstRewardId("4_8"));
        assertEquals("pvzce:fog_retreat", firstRewardId("4_9"));
        assertEquals("pvzce:coffee_bean", firstRewardId("4_10"));

        assertEquals("unlock", level("4_4").rewards().firstClear().get(0).type(),
                "4-4 unlocks a card: the vase tool is what the player carries out of it");
        LevelDef nine = level("4_9");
        assertEquals("buff", nine.rewards().firstClear().get(0).type(),
                "4-9 hands out the retreat buff rather than a card");
        assertTrue(BuiltInRegistries.LEVEL_BUFFS.containsKey(PvzceIds.BUFF_FOG_RETREAT),
                "and that buff has to exist, or the reward would grant nothing");
    }

    /**
     * 4-4's plants are all in vases.
     *
     * <p>The level hands out no cards and gives the player fifty sun, so the vases are the only way
     * to put a plant on the lawn: a 4-4 that lost them is not a hard level, it is an impossible one.
     */
    @Test
    void fourFourArmsThePlayerOutOfVases() {
        LevelDef def = level("4_4");
        VaseFieldData field = null;
        for (TypedMechanic mechanic : def.mechanics()) {
            if (mechanic.is(PvzceIds.MECHANIC_VASE_FIELD)
                    && mechanic.value() instanceof VaseFieldData data) {
                field = data;
            }
        }
        assertNotNull(field, "4-4 has to declare its vases");
        assertEquals(8, field.vases().size(), "one vase per card the level intends to hand out");
        assertTrue(field.validate(def.width(), def.height()).isEmpty(),
                "the block has to be well formed: " + field.validate(def.width(), def.height()));
        for (VaseFieldData.Vase vase : field.vases()) {
            assertFalse(level("4_4").scene().getOrDefault(PvzceIds.WATER, List.of())
                            .contains(vase.x() + "," + vase.y()),
                    "a vase in the pool would be a vase the player cannot plant behind");
        }
        assertFalse(def.unlockResources().isEmpty(), "and the level does still unlock sun");
    }

    /** Every wave names its lanes, and every level ends on a final wave. */
    @Test
    void everyWaveSendsSomething() {
        for (String path : LEVELS) {
            LevelDef def = level(path);
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
        assertTrue(seen.contains("snorkel_zombie"), "and the pool's divers are in the pool");
        assertTrue(seen.contains("gargantuar"), "with the Gargantuar at the end of the world");
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
