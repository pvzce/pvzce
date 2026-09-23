package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.ProjectileRef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ProjectileEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What 远距蘑菇 does to a puff-shroom, measured on a running level.
 *
 * <p>This exists because the buff shipped broken in a way no pure-function test could see:
 * {@code LevelServer} lengthened the projectile at the moment it was born, while
 * {@code ShooterCapability} went on deciding whether to fire from the <em>definition's</em>
 * range. The shot could reach half a cell further and the plant never took the shot, so the
 * observable behaviour was "the buff does nothing" - even though
 * {@code LevelServer.sporeRangeMultiplier} answered 1.5, {@code ProjectileRef.scaledRange} was
 * correct, and every unit test was green.
 *
 * <p>The measurement is therefore taken where the two halves meet: <strong>how far away the
 * zombie was on the tick the plant first fired</strong>. A spore's own flight distance cannot
 * answer it, because a zombie walks into range and the unbuffed plant fires at three cells
 * sooner or later either way.
 */
class MushroomRangeBuffTest {
    private static final Identifier PUFF = Identifier.parse("pvzce:puff_shroom");
    private static final Identifier RANGE = Identifier.parse("pvzce:mushroom_range");
    private static final Identifier PLANT_TEAM = Identifier.parse("pvzce:plant_team");
    private static final Identifier ZOMBIE_TEAM = Identifier.parse("pvzce:zombie_team");
    /** The unbuffed reach of a puff-shroom, from its own definition: {@code "range": 3.0}. */
    private static final float PUFF_RANGE = 3F;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static final class Bridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> sent = new java.util.ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            sent.add(packet);
        }
    }

    /**
     * 2-1 with the buff switched on or off, and no waves of its own.
     *
     * <p>A real night level rather than a fixture: a mushroom in daylight is asleep and fires
     * nothing at all, which reads exactly like a broken buff.
     */
    private static LevelServer night(boolean withBuff) {
        LevelDef def = BuiltInRegistries.LEVELS.get(Identifier.parse("pvzce:yard/adventure/2_1"));
        assertNotNull(def, "2-1 is the built-in night level this test leans on");
        LevelDef quiet = TestLevels.copy(def)
                .waves(List.of())
                .initialEntities(List.of())
                .buffs(new LevelDef.LevelBuffPlan(withBuff ? List.of(RANGE) : List.of(),
                        LevelDef.LevelBuffPlan.UNSET_MAX_BUFF_SLOTS))
                .build();
        return new LevelServer(quiet);
    }

    /** One run's measurement: where the zombie was when the plant committed to its first shot. */
    private record Shot(float distanceAtFirstShot, boolean fired) {
    }

    private static Shot measure(boolean withBuff) {
        LevelServer level = night(withBuff);
        Bridge bridge = new Bridge();
        PlantEntity plant = level.spawnPlant(BuiltInRegistries.PLANTS.get(PUFF),
                level.team(PLANT_TEAM), 1, 2);
        ZombieEntity zombie = level.spawnZombie(Identifier.parse("pvzce:basic_zombie"),
                level.team(ZOMBIE_TEAM), 6F, 2);
        level.flushPending(bridge);
        assertTrue(level.isNight(), "2-1 is always night, so the mushroom is awake");
        assertFalse(plant.isAsleep(level));

        float muzzleX = plant.cellX() + 0.3F;
        for (int i = 0; i < 1200; i++) {
            level.tick(bridge);
            for (com.pvzce.server.entity.PvzceEntity entity : level.entities()) {
                if (entity instanceof ProjectileEntity projectile && !projectile.isRemoved()) {
                    return new Shot(zombie.cellX() - muzzleX, true);
                }
            }
            if (zombie.isRemoved()) {
                break;
            }
        }
        return new Shot(-1F, false);
    }

    /** With the buff, the plant fires at a zombie that is out of its unbuffed reach. */
    @Test
    void theBuffLetsAPuffShroomOpenFireBeyondItsOwnRange() {
        Shot plain = measure(false);
        Shot buffed = measure(true);

        assertTrue(plain.fired() && buffed.fired(), "both runs have to fire for this to compare");
        assertTrue(plain.distanceAtFirstShot() <= PUFF_RANGE,
                "without the buff the plant waits until the zombie is inside its own " + PUFF_RANGE
                        + " cells, and it fired at " + plain.distanceAtFirstShot());
        assertTrue(buffed.distanceAtFirstShot() > PUFF_RANGE,
                "with the buff it opens fire past that reach, at " + buffed.distanceAtFirstShot());
        assertTrue(buffed.distanceAtFirstShot() > plain.distanceAtFirstShot() + 1F,
                "and the difference is the half again the buff promises: "
                        + plain.distanceAtFirstShot() + " -> " + buffed.distanceAtFirstShot());
    }

    /** The same rule, read directly: one multiplier decides both halves of the shot. */
    @Test
    void theScaledShotIsWhatThePlantAimsWith() {
        ProjectileRef puff = new ProjectileRef(Identifier.parse("pvzce:puff"), 20, 1, 0, false, 0,
                PUFF_RANGE);
        float muzzleX = 1.8F;
        assertEquals(4.5F, puff.scaledRange(1.5F).range(), 0.0001F,
                "3 cells of its own, half again as far");
        assertFalse(puff.covers(muzzleX, muzzleX + 4F), "the definition's own reach stops at 3");
        assertTrue(puff.scaledRange(1.5F).covers(muzzleX, muzzleX + 4F),
                "and the scaled one reaches the zombie the plant is now willing to shoot at");
    }

    /** A plant that never fires is unaffected: the buff is for spore shooters only. */
    @Test
    void aPeaShooterKeepsItsOwnReach() {
        LevelServer level = night(true);
        Bridge bridge = new Bridge();
        PlantEntity pea = level.spawnPlant(BuiltInRegistries.PLANTS.get(Identifier.parse("pvzce:pea_shooter")),
                level.team(PLANT_TEAM), 1, 3);
        level.flushPending(bridge);
        assertEquals(1F, level.sporeRangeMultiplier(pea), "the buff is for mushrooms");
    }
}
