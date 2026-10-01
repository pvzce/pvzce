package com.pvzce.server;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.tag.TestContent;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.MagnetItemS2C;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ProjectileEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

/** End-to-end regressions for broken world-four counters and weapon directions. */
class FogEntitiesRegressionTest {
    @BeforeAll static void load() throws Exception { TestContent.loadBuiltInContentAndTags(); }
    private static LevelServer night(boolean fog) {
        var def = TestLevels.copy(BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/4_1")))
                .waves(List.of());
        if (!fog) def.mechanics(List.of());
        return new LevelServer(def.build());
    }
    private static PlantEntity plant(LevelServer level, String id, int x, int y) {
        var plant = level.spawnPlant(BuiltInRegistries.PLANTS.get(PvzceIds.id(id)),
                level.team(PvzceIds.PLANT_TEAM), x, y);
        level.flushPending(p -> { });
        assertNotNull(plant);
        return plant;
    }
    private static ZombieEntity zombie(LevelServer level, String id, float x, int row) {
        var zombie = level.spawnZombie(PvzceIds.id(id), level.team(PvzceIds.ZOMBIE_TEAM), x, row);
        level.flushPending(p -> { });
        assertNotNull(zombie);
        return zombie;
    }
    private static void tick(LevelServer level, int ticks) {
        for (int i = 0; i < ticks; i++) level.tick(p -> { });
    }
    @Test void cactusRaisesAndPopsABalloonThenLowers() {
        var level = night(false);
        var cactus = plant(level, "cactus", 2, 0);
        var balloon = zombie(level, "balloon_zombie", 6.5F, 0);
        tick(level, 2);
        assertEquals("rise", cactus.animation());
        tick(level, 180);
        assertTrue(balloon.isGrounded(), "cactus must actually hit an airborne target");
        tick(level, 90);
        assertFalse(cactus.animation().contains("high"), "without balloons it must lower its arms");
    }
    @Test void splitPeaOnlyFiresItsRearDoubleWhenThereIsNoFrontTarget() {
        var level = night(false);
        var split = plant(level, "split_pea", 4, 0);
        zombie(level, "basic_zombie", 1.5F, 0);
        for (int i = 0; i < 20; i++) { split.tick(level); level.flushPending(p -> { }); }
        var shots = level.entities().stream().filter(ProjectileEntity.class::isInstance)
                .map(ProjectileEntity.class::cast).toList();
        assertEquals(2, shots.size());
        assertTrue(shots.stream().allMatch(p -> p.direction() < 0));
        assertEquals("shoot_back", split.animation());
    }
    @Test void starfruitHitsATargetAboveItsLaneAndUsesStars() {
        var level = night(false);
        plant(level, "starfruit", 4, 1);
        var target = zombie(level, "basic_zombie", 4.8F, 0);
        tick(level, 25);
        assertTrue(target.health() < target.def().health(), "vertical star must collide across rows");
        assertTrue(level.entities().stream().filter(ProjectileEntity.class::isInstance)
                .allMatch(p -> p.defId().equals(PvzceIds.id("star"))));
    }
    @Test void splitPeaFrontHeadDoesNotWaitForTheRearHeadsCooldown() {
        var level = night(false);
        var split = plant(level, "split_pea", 4, 0);
        zombie(level, "basic_zombie", 1.5F, 0);
        tick(level, 20);
        zombie(level, "basic_zombie", 7F, 0);
        for (int i = 0; i < 3; i++) { split.tick(level); level.flushPending(p -> { }); }
        assertTrue(level.entities().stream().filter(ProjectileEntity.class::isInstance)
                .map(ProjectileEntity.class::cast).anyMatch(p -> p.direction() > 0));
    }
    @Test void seaShroomDoesNotFireAtAnOutOfRangeSwimmer() {
        var level = night(false);
        var sea = plant(level, "sea_shroom", 1, 2);
        zombie(level, "ducky_tube_zombie", 7F, 2);
        tick(level, 30);
        assertTrue(level.entities().stream().noneMatch(ProjectileEntity.class::isInstance));
        assertEquals("idle", sea.animation());
    }
    @Test void bloverClearsFogAndFogReturnsWithoutDeletingItsDefinition() {
        var level = night(true);
        float original = level.fogData().startColumn();
        plant(level, "blover", 2, 0);
        tick(level, 62);
        assertEquals(level.fogData().endColumn(), level.fogData().startColumn());
        tick(level, PvzceConstants.BLOVER_FOG_CLEAR_TICKS);
        assertEquals(original, level.fogData().startColumn());
    }
    @Test void magnetRejectsPlasticAndTakesCarriedMetalThenWaitsToRecover() {
        var level = night(false);
        var magnet = plant(level, "magnet_shroom", 3, 0);
        var cone = zombie(level, "conehead_zombie", 3.8F, 1);
        var pogo = zombie(level, "pogo_zombie", 5F, 0);
        tick(level, 2);
        assertTrue(cone.hasArmor(), "a traffic cone is not magnetic");
        assertNull(pogo.magneticItem(), "the stick must be removable independently of armour");
        var bucket = zombie(level, "buckethead_zombie", 4F, 1);
        tick(level, 140);
        assertTrue(bucket.hasArmor(), "magnet cannot take another item while charging");
        assertEquals("magnet_hold", magnet.animation());
    }
    @Test void magneticPickaxeLossMakesMinerSurfaceLocallyAndMusicBoxCannotExplodeAfterRemoval() {
        var level = night(false);
        var miner = zombie(level, "miner_zombie", 6.5F, 0);
        assertTrue(miner.removeMagneticItem(level));
        tick(level, 180);
        assertTrue(miner.canBeHitByGround());
        assertEquals(-1F, miner.walkDirection());
        assertTrue(miner.cellX() > 5F, "it surfaces where the axe was taken");
        var jack = zombie(level, "jack_in_the_box_zombie", 4F, 1);
        assertTrue(jack.removeMagneticItem(level));
        var walnut = plant(level, "wall_nut", 3, 1);
        tick(level, 200);
        assertTrue(jack.isAlive());
        assertTrue(walnut.health() > 0 && !walnut.isRemoved());
        assertNull(jack.magneticItem());
    }
    @Test void surfacedMinerFacesRightAndBitesWithoutTunnellingAgain() {
        var level = night(false);
        var walnut = plant(level, "wall_nut", 0, 0);
        var miner = zombie(level, "miner_zombie", 0.5F, 0);
        tick(level, PvzceConstants.DIGGER_RISE_TICKS + PvzceConstants.DIGGER_DIZZY_TICKS + 20);
        assertEquals(1F, miner.walkDirection());
        assertTrue(walnut.health() < walnut.def().health());
        assertEquals("eat_right", miner.animation());
        walnut.remove();
        miner.setCellX(level.width() + 0.5F);
        tick(level, 1);
        assertTrue(miner.isRemoved(), "it leaves through the right edge rather than digging again");
    }
    @Test void clearedFogAndRemovedPickaxeSurviveSaveAndRestore() {
        var level = night(true);
        level.blowFog(PvzceConstants.BLOVER_FOG_CLEAR_TICKS);
        var miner = zombie(level, "miner_zombie", 6F, 0);
        assertTrue(miner.removeMagneticItem(level));
        tick(level, 10);
        var restored = night(true);
        restored.restore(level.save());
        assertEquals(level.fogData(), restored.fogData());
        var savedMiner = restored.entities().stream().filter(ZombieEntity.class::isInstance)
                .map(ZombieEntity.class::cast).findFirst().orElseThrow();
        assertNull(savedMiner.magneticItem());
        tick(restored, 180);
        assertTrue(savedMiner.canBeHitByGround());
    }

    @Test void magnetSleepsInDaylightInsteadOfTakingABucket() {
        var level = new LevelServer(TestLevels.copy(BuiltInRegistries.LEVELS.get(
                PvzceIds.id("yard/adventure/1_1"))).waves(List.of()).mechanics(List.of()).build());
        var magnet = plant(level, "magnet_shroom", 2, 0);
        var bucket = zombie(level, "buckethead_zombie", 4F, 0);
        tick(level, 10);
        assertEquals("sleep", magnet.animation());
        assertTrue(bucket.hasArmor());
    }

    @Test void magnetWaitsTheFullRecoveryBeforeTakingASecondItem() {
        var level = night(false);
        plant(level, "magnet_shroom", 3, 0);
        var first = zombie(level, "pogo_zombie", 5F, 0);
        tick(level, 1);
        assertNull(first.magneticItem());
        first.remove();
        var second = zombie(level, "buckethead_zombie", 4F, 1);
        tick(level, PvzceConstants.MAGNET_RECOVERY_TICKS - 1);
        assertTrue(second.hasArmor());
        tick(level, 2);
        assertFalse(second.hasArmor());
        System.out.printf("磁力菇普通档：两次吸取间隔 %.3f 秒，首件跳跳杆、第二件铁桶；恢复期间铁桶保持完整。%n",
                (PvzceConstants.MAGNET_RECOVERY_TICKS + 1F) / PvzceConstants.TICKS_PER_SECOND);
    }
    @Test void aJoiningOrRestoredClientReceivesTheHeldMetalAtItsCurrentAge() {
        var level = night(false);
        plant(level, "magnet_shroom", 3, 0);
        zombie(level, "pogo_zombie", 5F, 0);
        tick(level, 150);
        List<PvzcePacket> joining = new ArrayList<>();
        level.sendFullState(joining::add);
        var held = joining.stream().filter(MagnetItemS2C.class::isInstance)
                .map(MagnetItemS2C.class::cast).findFirst().orElseThrow();
        assertEquals("pvzce:pogo_stick", held.item());
        assertEquals(149, level.tickCount() - held.startTick());
        var restored = night(false);
        restored.restore(level.save());
        List<PvzcePacket> reconnecting = new ArrayList<>();
        restored.sendFullState(reconnecting::add);
        var saved = reconnecting.stream().filter(MagnetItemS2C.class::isInstance)
                .map(MagnetItemS2C.class::cast).findFirst().orElseThrow();
        assertEquals(held.item(), saved.item());
        assertEquals(149, restored.tickCount() - saved.startTick());
    }

    @Test void balloonFinishesFallingBeforeWalkingAtGroundSpeed() {
        var level = night(false);
        var balloon = zombie(level, "balloon_zombie", 6F, 0);
        balloon.damage(BuiltInRegistries.PROJECTILES.get(PvzceIds.id("cactus_spike_air")), 20, level);
        float x = balloon.cellX();
        tick(level, 20);
        assertEquals("fall", balloon.animation());
        assertEquals(x, balloon.cellX());
        tick(level, PvzceConstants.BALLOON_FALL_TICKS);
        assertEquals(0.23F, balloon.moveSpeed(level), 0.0001F);
        assertTrue(balloon.cellX() < x);
    }

    @Test void aSavedVerticalStarKeepsItsDirectionAndDamage() {
        var level = night(false);
        plant(level, "starfruit", 4, 1);
        zombie(level, "basic_zombie", 4.8F, 0);
        tick(level, 3);
        var restored = night(false);
        restored.restore(level.save());
        var star = restored.entities().stream().filter(ProjectileEntity.class::isInstance)
                .map(ProjectileEntity.class::cast).filter(p -> p.vectorX() == 0 && p.vectorY() < 0)
                .findFirst().orElseThrow();
        float y = star.cellY();
        tick(restored, 1);
        assertTrue(star.cellY() < y);
        assertEquals(20, star.damage());
    }

    @Test void aPoppedBalloonFallsBeforeSinkingIntoThePool() {
        var level = night(false);
        var balloon = zombie(level, "balloon_zombie", 6F, 2);
        assertEquals(2, balloon.gridY(), "a flier must be allowed to spawn over the pool");
        balloon.damage(BuiltInRegistries.PROJECTILES.get(PvzceIds.id("cactus_spike_air")), 20, level);
        tick(level, 20);
        assertFalse(balloon.isRemoved());
        assertEquals("fall", balloon.animation());
        tick(level, PvzceConstants.BALLOON_FALL_TICKS);
        assertTrue(balloon.isRemoved());
    }

    @Test void removedEquipmentDoesNotReappearOnADeathClip() {
        var level = night(false);
        var jack = zombie(level, "jack_in_the_box_zombie", 6F, 0);
        assertTrue(jack.removeMagneticItem(level));
        jack.damageBody(jack.health(), level);
        assertEquals("death_no_box", jack.animation());
        var miner = zombie(level, "miner_zombie", 6F, 1);
        assertTrue(miner.removeMagneticItem(level));
        miner.damageBody(miner.health(), level);
        assertEquals("death_noaxe", miner.animation());
    }
}
