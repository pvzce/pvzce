package com.pvzce.common.level.mutation;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.entity.EntityLayers;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.MowerMechanic;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ProjectileEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The second catalogue's last group: what the lawn does for and to the player.
 *
 * <p>These four are the ones a player feels in their wallet or their lanes rather than on the board,
 * so each is checked for the thing that would make it unfair rather than for the mechanic it uses: a
 * pea party arms plants that do not shoot and leaves the ones that do alone; a mower supply gives
 * back what was spent and never a second mower in one lane; a mower release only sends one that is
 * still parked; and a sun drain never empties the bank.
 */
class MutationGroupThreeTest {
    private static LevelDef endless;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        Identifier id = MutationLevels.levelIds().get(MutationDifficulty.NORMAL.ordinal());
        endless = TestLevels.copy(BuiltInRegistries.LEVELS.get(id)).waves(List.of()).build();
    }

    /** 豌豆派对: a sunflower shoots, and a peashooter does not shoot twice as often. */
    @Test
    void peaPartyArmsOnlyThePlantsThatDoNotShoot() {
        LevelServer level = levelWithMutations();
        PlantDef sunflower = BuiltInRegistries.PLANTS.get(PvzceIds.id("sunflower"));
        PlantDef peashooter = BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter"));
        assertNotNull(sunflower);
        assertNotNull(peashooter);
        PlantEntity flower = level.spawnPlant(sunflower, level.team(PvzceIds.PLANT_TEAM), 1, 1);
        PlantEntity shooter = level.spawnPlant(peashooter, level.team(PvzceIds.PLANT_TEAM), 1, 2);
        assertNotNull(flower);
        assertNotNull(shooter);

        level.mutations().add(MutationRegistry.get(PvzceIds.MUTATION_PEA_PARTY),
                Mutation.Roll.NONE);
        assertNotNull(flower.capability(com.pvzce.common.capability.plant.ShooterCapability.class),
                "the sunflower has a gun now");
        assertTrue(shooter.capabilityInstances().stream()
                        .filter(instance -> instance.capability()
                                instanceof com.pvzce.common.capability.plant.ShooterCapability)
                        .count() == 1,
                "and the peashooter still has exactly the one it was defined with");

        // A plant placed while the mutation is running is armed too, and one placed after it leaves
        // is not: the party is the lawn as it stands.
        PlantEntity late = level.spawnPlant(sunflower, level.team(PvzceIds.PLANT_TEAM), 1, 3);
        assertNotNull(late);
        for (int i = 0; i < 3; i++) {
            level.tick(packet -> { });
        }
        assertNotNull(late.capability(com.pvzce.common.capability.plant.ShooterCapability.class),
                "a plant that appears during the party joins it");

        level.mutations().clear();
        // Every armed plant is disarmed, the ones that joined later included: a garden that kept
        // firing after the mutation was evicted would be the mutation outliving its own eviction.
        for (PlantEntity plant : List.of(flower, late)) {
            assertTrue(plant.capability(
                            com.pvzce.common.capability.plant.ShooterCapability.class) == null,
                    "the temporary shooter is gone from "
                            + plant.def().id());
        }
        // And no new peas appear over a full firing interval.
        int before = projectilesArmed(level);
        for (int i = 0; i < 200; i++) {
            level.tick(packet -> { });
        }
        assertEquals(before, projectilesArmed(level),
                "a lawn with no party does not fire by itself");
    }

    /** 割草机补给: a spent mower comes back, exactly once per lane. */
    @Test
    void randomSupplyRestoresOneSpentMower() {
        LevelServer level = levelWithMutations();
        MowerMechanic.Rig rig = rig(level);
        assertNotNull(rig, "the fixture has mowers");
        assertTrue(rig.release(0));
        // Let it run off the board so its row is genuinely spent.
        for (int i = 0; i < 900 && stateOf(rig, 0) != MowerMechanic.STATE_USED; i++) {
            level.tick(packet -> { });
        }
        assertEquals(MowerMechanic.STATE_USED, stateOf(rig, 0), "the fixture spent row 0's mower");
        int readyBefore = rig.readyCount();

        level.mutations().add(MutationRegistry.get(PvzceIds.MUTATION_RANDOM_SUPPLY),
                Mutation.Roll.NONE);
        int interval = level.mutations().difficulty().scaledInterval(1800);
        for (int i = 0; i < interval + 1; i++) {
            level.tick(packet -> { });
        }
        assertEquals(readyBefore + 1, rig.readyCount(), "one mower came back");
        assertEquals(MowerMechanic.STATE_READY, stateOf(rig, 0),
                "and it is the row that had lost one");
        assertEquals(MowerMechanic.IDLE_X, rowOf(rig, 0).x(), 0.0001F,
                "parked exactly where a fresh mower starts");
    }

    /** 割草机自走: a parked mower leaves, and a spent one is not re-sent. */
    @Test
    void autoReleaseSendsOnlyAParkedMower() {
        LevelServer level = levelWithMutations();
        MowerMechanic.Rig rig = rig(level);
        assertNotNull(rig);
        assertEquals(0, countInState(rig, MowerMechanic.STATE_ROLLING),
                "nothing is rolling to begin with");

        level.mutations().add(MutationRegistry.get(PvzceIds.MUTATION_AUTO_RELEASE),
                Mutation.Roll.NONE);
        int interval = level.mutations().difficulty().scaledInterval(2400);
        for (int i = 0; i < interval + 1; i++) {
            level.tick(packet -> { });
        }
        assertEquals(1, countInState(rig, MowerMechanic.STATE_ROLLING)
                        + countInState(rig, MowerMechanic.STATE_USED),
                "exactly one mower went, without anything in its lane");
    }

    /** 阳光流失: the bank leaks, and never runs dry. */
    @Test
    void sunDrainTakesAShareAndStopsAtZero() {
        LevelServer level = levelWithMutations();
        level.team(PvzceIds.PLANT_TEAM).putResource(PvzceIds.SUN, 1000);
        level.mutations().add(MutationRegistry.get(PvzceIds.MUTATION_SUN_DRAIN),
                Mutation.Roll.NONE);
        for (int i = 0; i < 60; i++) {
            level.tick(packet -> { });
        }
        assertEquals(990, level.team(PvzceIds.PLANT_TEAM).resourcesOf(PvzceIds.SUN),
                "one percent, once a second");

        level.team(PvzceIds.PLANT_TEAM).putResource(PvzceIds.SUN, 1);
        for (int i = 0; i < 60; i++) {
            level.tick(packet -> { });
        }
        assertEquals(0, level.team(PvzceIds.PLANT_TEAM).resourcesOf(PvzceIds.SUN),
                "the last sun is taken and the bank stops there");
        for (int i = 0; i < 120; i++) {
            level.tick(packet -> { });
        }
        assertEquals(0, level.team(PvzceIds.PLANT_TEAM).resourcesOf(PvzceIds.SUN),
                "and it never goes negative");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static LevelServer levelWithMutations() {
        return new LevelServer(endless);
    }

    private static MowerMechanic.Rig rig(LevelServer level) {
        level.tick(packet -> { });
        return level.mechanicStateOrNull(PvzceIds.MECHANIC_MOWER, MowerMechanic.Rig.class);
    }

    private static int stateOf(MowerMechanic.Rig rig, int row) {
        return rowOf(rig, row).state();
    }

    private static MowerMechanic.Row rowOf(MowerMechanic.Rig rig, int row) {
        for (MowerMechanic.Row entry : rig.state().rows()) {
            if (entry.row() == row) {
                return entry;
            }
        }
        throw new AssertionError("no mower in row " + row);
    }

    private static int countInState(MowerMechanic.Rig rig, int state) {
        int count = 0;
        for (MowerMechanic.Row entry : rig.state().rows()) {
            if (entry.state() == state) {
                count++;
            }
        }
        return count;
    }


    private static int projectilesArmed(LevelServer level) {
        int count = 0;
        for (var entity : level.entities()) {
            if (entity instanceof ProjectileEntity projectile
                    && projectile.layer() == EntityLayers.PROJECTILE) {
                count++;
            }
        }
        return count;
    }
}
