package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.buff.BuiltInBuffs;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 3-6 to 3-10: the pool's second half, and the content they hand out.
 *
 * <p>Five levels are a lot of JSON to get subtly wrong, and every wrong one fails the same way -
 * the player walks in and something is missing. So the things worth pinning are the ones a player
 * would notice: that the chain runs without a gap, that each level's reward is the thing the next
 * level expects them to have, that the new bodies actually appear, and that the one new buff does
 * what its own description says.
 */
class PoolSecondHalfTest {
    private static final Identifier PLANT_TEAM = PvzceIds.PLANT_TEAM;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static LevelDef level(String path) {
        LevelDef def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/" + path));
        assertNotNull(def, path + " has to exist");
        return def;
    }

    /** All five are there, and they are the pool: six rows with water in the middle two. */
    @Test
    void theFiveLevelsAreThePool() {
        for (String path : new String[]{"3_6", "3_7", "3_8", "3_9", "3_10"}) {
            LevelDef def = level(path);
            assertEquals(9, def.width(), path + " is nine columns");
            assertEquals(6, def.height(), path + " is six rows, because the pool is");
            assertTrue(def.scene().containsKey(PvzceIds.WATER), path + " has water");
            assertEquals(Identifier.withDefaultNamespace(
                            "textures/gui/screen/level/background3"),
                    def.background().orElseThrow(),
                    path + " uses the pool backdrop, which is what tells LevelStage how to draw it");
        }
    }

    /** The chain runs 3-5 → 3-6 → … → 3-10 with no gap and no repeat. */
    @Test
    void theUnlockChainHasNoGap() {
        for (int index = 6; index <= 10; index++) {
            LevelDef def = level("3_" + index);
            List<String> requires = def.unlock().requires().stream()
                    .map(require -> require.id().orElseThrow().toString()).toList();
            assertEquals(List.of("pvzce:yard/adventure/3_" + (index - 1)), requires,
                    "3-" + index + " has to be gated on the level before it");
        }
    }

    /**
     * Every reward is the thing the level is about, and it is paid once.
     *
     * <p>The one reward that is a buff rather than a card is 3-9's, and it is the reason this is
     * asserted at all: a level whose reward is a buff and a level whose reward is a plant are two
     * different shapes in the same field.
     */
    @Test
    void theRewardsAreWhatEachLevelIsAbout() {
        assertEquals("pvzce:spikeweed", firstRewardId("3_6"));
        assertEquals("pvzce:torchwood", firstRewardId("3_7"));
        assertEquals("pvzce:tall_nut", firstRewardId("3_8"));
        assertEquals("pvzce:kelp_spread", firstRewardId("3_9"));
        // The original's world 3 ends with the sea-shroom, not the plantern: the plantern is
        // 4-1's gift, because it is the fog world's first need.
        assertEquals("pvzce:sea_shroom", firstRewardId("3_10"));

        LevelDef nine = level("3_9");
        assertEquals("buff", nine.rewards().firstClear().get(0).type(),
                "3-9 hands out the kelp buff rather than a card");
        assertTrue(BuiltInRegistries.LEVEL_BUFFS.containsKey(PvzceIds.BUFF_KELP_SPREAD),
                "and that buff has to exist, or the reward would grant nothing");
    }

    private static String firstRewardId(String path) {
        return level(path).rewards().firstClear().get(0).id().orElseThrow().toString();
    }

    /** The new bodies are actually in the levels: a reward nobody meets is a reward untested. */
    @Test
    void theNewZombiesAppear() {
        Set<String> seen = new LinkedHashSet<>();
        for (String path : new String[]{"3_6", "3_7", "3_8", "3_9", "3_10"}) {
            for (var wave : level(path).waves()) {
                for (var entry : wave.entries()) {
                    seen.add(entry.id().path());
                }
            }
        }
        assertTrue(seen.contains("zamboni_zombie"), "the zamboni has to turn up somewhere");
        assertTrue(seen.contains("bobsled_zombie"), "and the team it tows onto the ice");
        assertTrue(seen.contains("dolphin_rider_zombie"), "the pool's own riders are in the pool");
        assertTrue(seen.contains("snorkel_zombie"), "and the divers");
        // The Gargantuar is not part of worlds 3 and 4 at all: the original introduces it on
        // 5-8. It used to close 3-10 here, which was this project's invention.
        assertFalse(seen.contains("gargantuar"), "no Gargantuar before the roof");
    }

    /**
     * The kelp buff does what its description says.
     *
     * <p>"Plant one, and it grows another beside it, unless every neighbour has a plant or is not
     * water" - so the test plants one in water and counts, and then plants one on grass and
     * checks nothing happened. Both halves matter: a rule that spread onto grass would be a
     * different buff, and one that spread into an occupied cell would delete a plant.
     */
    @Test
    void theKelpBuffSpreadsInWaterAndOnlyInWater() {
        LevelServer pool = poolWith("3_9");
        pool.setActiveBuffs(List.of(BuiltInBuffs.KELP_SPREAD));
        com.pvzce.api.content.PlantDef kelp = BuiltInRegistries.PLANTS.get(PvzceIds.TANGLE_KELP);
        assertNotNull(kelp, "the buff is about the tangle kelp");

        // Through the card path, which is where the buff lives: `spawnPlant` is the low-level
        // "make a plant exist" API the mutations and the AI use, and a buff about *planting* has
        // no business firing for those.
        assertTrue(pool.placePlant(packet -> { }, kelpSlot(pool), 4, 2),
                "the kelp has to be plantable in the pool");
        assertEquals(2, kelpCount(pool),
                "planting one kelp in the water grows a second one beside it");

        LevelServer dry = poolWith("3_9");
        dry.setActiveBuffs(List.of(BuiltInBuffs.KELP_SPREAD));
        assertFalse(dry.placePlant(packet -> { }, kelpSlot(dry), 4, 0),
                "a tangle kelp cannot be planted on grass at all, let alone spread there");
        assertEquals(0, kelpCount(dry));

        LevelServer plain = poolWith("3_9");
        plain.setActiveBuffs(List.of());
        assertTrue(plain.placePlant(packet -> { }, kelpSlot(plain), 4, 2));
        assertEquals(1, kelpCount(plain), "without the buff one planting is one plant");
    }

    private static int kelpCount(LevelServer level) {
        int count = 0;
        for (var entity : level.entities()) {
            if (entity instanceof PlantEntity plant && PvzceIds.TANGLE_KELP.equals(plant.def().id())) {
                count++;
            }
        }
        return count;
    }

    /** The tangle kelp's index on a pool level's bar; the level pins nothing, so it is the pool's. */
    private static int kelpSlot(LevelServer level) {
        level.plantPlayer().replaceSlots(com.pvzce.server.PvzcePlayer.deckSlots(
                List.of(PvzceIds.TANGLE_KELP, PvzceIds.SUN)));
        return level.plantPlayer().slots().stream()
                .filter(slot -> PvzceIds.TANGLE_KELP.equals(slot.defId()))
                .map(com.pvzce.common.core.Slot::index)
                .findFirst().orElseThrow();
    }

    private static LevelServer poolWith(String path) {
        return new LevelServer(com.pvzce.testutil.TestLevels.copy(level(path))
                .waves(List.of()).build());
    }

    /** Every level's waves are well formed, which is what the validator says. */
    @Test
    void everyWaveSendsSomething() {
        for (String path : new String[]{"3_6", "3_7", "3_8", "3_9", "3_10"}) {
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
}
