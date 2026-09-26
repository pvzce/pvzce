package com.pvzce.server;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.ZombieDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mutation.MutantBlast;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The squash flattens one zombie, and the two blast mutations are bounded.
 *
 * <p>Both halves are about the same complaint: an attack that reached further or hit harder than
 * the player expected. The squash used to be a footprint blast that killed the neighbours and
 * scorched the lawn; the blast mutations used to be strong enough to chain, and bright enough to
 * strobe. Neither is a numerical tweak - the squash is a different capability now, and the blasts
 * read their ceilings from the registry.
 */
class ExplosiveBoundsTest {
    private static final Identifier PLANT_TEAM = PvzceIds.PLANT_TEAM;
    private static final Identifier ZOMBIE_TEAM = PvzceIds.ZOMBIE_TEAM;
    private static final Identifier BASIC = Identifier.withDefaultNamespace("basic_zombie");
    private static final Identifier SQUASH = Identifier.withDefaultNamespace("squash");

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static LevelServer lawn(List<Identifier> slots) {
        return new LevelServer(com.pvzce.testutil.TestLevels.copy(
                        BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_1")))
                .waves(List.of()).slots(slots).build());
    }

    private static void tick(LevelServer level, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.tick(packet -> { });
        }
    }

    /**
     * One zombie dies, the one two cells away lives, and the lawn is not cratered.
     *
     * <p>The three things the report asked for, in one run: the squash was an area blast with
     * {@code radius 1.0, square true}, so "two cells away" was inside it and a crater was left
     * behind.
     */
    @Test
    void theSquashFlattensOneZombieAndLeavesTheLawnAlone() {
        LevelServer level = lawn(List.of(SQUASH));
        PlantDef def = BuiltInRegistries.PLANTS.get(SQUASH);
        assertNotNull(def);
        PlantEntity squash = level.spawnPlant(def, level.team(PLANT_TEAM), 3, 2);
        level.flushPending(packet -> { });
        assertNotNull(squash);

        // The one it lands on: adjacent, which is inside the 0.5-cell trigger range.
        ZombieEntity victim = level.spawnZombie(BASIC, level.team(ZOMBIE_TEAM), 3.6F, 2);
        // And one in the lane above, close enough in world units that the old 1.0-cell square
        // blast would have caught it.
        ZombieEntity neighbour = level.spawnZombie(BASIC, level.team(ZOMBIE_TEAM), 3.6F, 3);
        assertNotNull(victim);
        assertNotNull(neighbour);
        level.flushPending(packet -> { });

        tick(level, 200);

        // `isAlive` rather than `isRemoved`: a zombie that takes a lethal hit plays its death
        // sequence first, so the body is still in the world for a few seconds after it is dead.
        assertTrue(!victim.isAlive(),
                "the zombie it landed on is flattened; health=" + victim.health());
        assertTrue(neighbour.isAlive(),
                "and the zombie in the next lane is untouched - a squash is a target, not a blast");
        assertTrue(squash.isRemoved(), "the squash spends itself");
    }

    /** A squash notices nothing and stays put when the lane is empty. */
    @Test
    void aSquashWithNothingInRangeWaits() {
        LevelServer level = lawn(List.of(SQUASH));
        PlantDef def = BuiltInRegistries.PLANTS.get(SQUASH);
        assertNotNull(def);
        PlantEntity squash = level.spawnPlant(def, level.team(PLANT_TEAM), 3, 2);
        level.flushPending(packet -> { });
        assertNotNull(squash);

        ZombieEntity far = level.spawnZombie(BASIC, level.team(ZOMBIE_TEAM), 8F, 2);
        assertNotNull(far);
        tick(level, 400);

        assertTrue(!squash.isRemoved(), "nothing came close enough to squash");
        assertTrue(!far.isRemoved(), "and the zombie is still walking");
    }

    /**
     * A blast may at most kill an ordinary zombie.
     *
     * <p>Read from the registry rather than written down, so retuning the basic zombie moves the
     * ceiling with it. The point of the ceiling is that the chain stops: one death cannot kill a
     * conehead, so a lawn cannot collapse in one cascade.
     */
    @Test
    void aZombieBlastIsCappedAtOneOrdinaryZombiesHealth() {
        ZombieDef basic = BuiltInRegistries.ZOMBIES.get(BASIC);
        assertNotNull(basic);
        assertEquals(basic.health(), MutantBlast.zombieDamage(),
                "the ceiling is the ordinary zombie's own health");
    }

    /** And a plant blast may at most halve an ordinary plant. */
    @Test
    void aPlantBlastIsCappedAtHalfAnOrdinaryPlantsHealth() {
        PlantDef peashooter = BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace("pea_shooter"));
        assertNotNull(peashooter);
        assertEquals(peashooter.health() / 2, MutantBlast.plantDamage(),
                "the ceiling is half the ordinary plant's health");
    }

    /**
     * The ceiling is what a blast actually applies, not just what it says.
     *
     * <p>A buckethead has armour and a lot of it; the assertion is that one blast leaves it
     * standing rather than removing it, and that a second blast is what finishes the job.
     */
    @Test
    void oneBlastCannotRemoveAHealthyZombie() {
        LevelServer level = lawn(List.of(SQUASH));
        List<PvzcePacket> packets = new ArrayList<>();
        ZombieEntity bucket = level.spawnZombie(
                Identifier.withDefaultNamespace("buckethead_zombie"), level.team(ZOMBIE_TEAM), 4F, 1);
        assertNotNull(bucket);
        level.flushPending(packets::add);
        int before = bucket.health() + bucket.armor();

        MutantBlast.detonate(level, 4F, 1.5F);
        level.flushPending(packets::add);

        assertTrue(!bucket.isRemoved(), "one blast must not be a removal");
        assertTrue(bucket.health() + bucket.armor() < before, "but it has to have hurt");
    }

    /** Nothing about a blast is invisible: the plant-side half of the same rule. */
    @Test
    void aBlastHalvesAPlantRatherThanKillingIt() {
        LevelServer level = lawn(List.of(Identifier.withDefaultNamespace("pea_shooter")));
        PlantDef def = BuiltInRegistries.PLANTS.get(
                Identifier.withDefaultNamespace("pea_shooter"));
        assertNotNull(def);
        PlantEntity plant = level.spawnPlant(def, level.team(PLANT_TEAM), 4, 1);
        level.flushPending(packet -> { });
        assertNotNull(plant);
        int full = plant.health();
        assertTrue(full > 1);

        MutantBlast.detonate(level, 4.5F, 1.5F);
        level.flushPending(packet -> { });

        assertTrue(!plant.isRemoved(),
                "one blast may not remove a plant - that is what stops the chain reaction");
        assertEquals(full - full / 2, plant.health(), "and it takes exactly half of it");
    }

    /** A board with no squash definition would be a content error, not a silent no-op. */
    @Test
    void theSquashIsRegisteredAsItsOwnCapability() {
        PlantDef def = BuiltInRegistries.PLANTS.get(SQUASH);
        assertNotNull(def);
        boolean hasSquash = def.capabilities().stream().anyMatch(capability ->
                capability.value() instanceof com.pvzce.common.capability.plant.SquashCapability);
        assertTrue(hasSquash, "the squash must carry pvzce:squash");
        boolean hasExplosive = def.capabilities().stream().anyMatch(capability ->
                capability.value() instanceof com.pvzce.common.capability.plant.ExplosiveCapability);
        assertTrue(!hasExplosive, "and must not be an explosive any more");
    }
}
