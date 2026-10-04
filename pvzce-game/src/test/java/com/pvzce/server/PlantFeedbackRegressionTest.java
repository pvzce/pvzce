package com.pvzce.server;

import com.pvzce.api.content.ProjectileRef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.capability.plant.ThrowerCapability;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ProjectileEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlantFeedbackRegressionTest {
    @BeforeAll static void load() throws Exception { TestContent.loadBuiltInContentAndTags(); }

    private static LevelServer lawn() {
        return new LevelServer(TestLevels.copy(BuiltInRegistries.LEVELS.get(
                PvzceIds.id("yard/adventure/demo_level"))).waves(List.of()).build());
    }
    private static PlantEntity plant(LevelServer level, String id, int x, int y) {
        return level.spawnPlant(BuiltInRegistries.PLANTS.get(PvzceIds.id(id)),
                level.team(PvzceIds.PLANT_TEAM), x, y);
    }
    private static ZombieEntity zombie(LevelServer level, String id, float x, int y) {
        var zombie = level.spawnZombie(PvzceIds.id(id), level.team(PvzceIds.ZOMBIE_TEAM), x, y);
        level.flushPending(p -> { });
        return zombie;
    }
    private static void tick(LevelServer level, int ticks) {
        for (int i = 0; i < ticks; i++) level.tick(p -> { });
    }
    private static List<ProjectileEntity> shots(LevelServer level) {
        return level.entities().stream().filter(ProjectileEntity.class::isInstance)
                .map(ProjectileEntity.class::cast).toList();
    }

    @Test void squashIsNeverFoodEvenBeforeItsLeap() {
        var level = lawn();
        var squash = plant(level, "squash", 3, 2);
        assertNull(level.biteTargetAt(3, 2));
        squash.damageFrom(100);
        assertEquals(squash.maxHealth(), squash.health());
        var biter = zombie(level, "basic_zombie", 3.5F, 2);
        // Tick the biter alone, leaving the squash waiting, to check the actual eating path.
        for (int i = 0; i < 80; i++) biter.tick(level);
        assertEquals(squash.maxHealth(), squash.health());
        assertTrue(biter.cellX() < 3.5F);
    }

    @Test void coffeeWakesMushroomInsidePumpkinAndWakeSurvivesReload() {
        var level = lawn();
        var mushroom = plant(level, "fume_shroom", 3, 2);
        var pumpkin = plant(level, "pumpkin", 3, 2);
        tick(level, 1);
        assertEquals("sleep", mushroom.animation());
        plant(level, "coffee_bean", 3, 2);
        tick(level, 1);
        assertNotEquals("sleep", mushroom.animation());
        assertSame(pumpkin, level.plantAt(3, 2));
        var restored = lawn();
        restored.restore(level.save());
        tick(restored, 1);
        var loaded = restored.plantsAt(3, 2).stream().filter(p -> p.defId().equals(mushroom.defId()))
                .findFirst().orElseThrow();
        assertNotEquals("sleep", loaded.animation());
    }

    @Test void restoredLampsRebuildOnlyLiveRegistrationsAndRemovalTakesThemBack() {
        var level = lawn();
        plant(level, "plantern", 5, 2);
        plant(level, "torchwood", 7, 2);
        var save = level.save();
        var restored = lawn();
        plant(restored, "plantern", 0, 0); // a stale registration must not survive replacing the field
        restored.restore(save);
        assertEquals(2, restored.fogReveals().size());
        assertTrue(restored.fogReveals().stream().allMatch(lamp -> lamp.x() > 5F));
        restored.plantAt(5, 2).remove();
        tick(restored, 1);
        assertEquals(1, restored.fogReveals().size());
    }

    @Test void butterHasItsOwnDamageInsteadOfInheritingKernelDamage() {
        var level = lawn();
        var kernel = plant(level, "kernel_pult", 1, 2);
        zombie(level, "basic_zombie", 6.5F, 2);
        var shipped = kernel.def().capability(ThrowerCapability.class).orElseThrow();
        assertEquals(PvzceConstants.KERNEL_DAMAGE, shipped.shots().getFirst().damage());
        for (float chance : new float[]{0F, 1F}) {
            var thrower = new ThrowerCapability(170, shipped.shots(), chance, PvzceIds.id("butter"), Optional.empty(), 0);
            assertTrue(thrower.strike(kernel, level));
            for (int i = 0; i <= PvzceConstants.LOB_RELEASE_TICKS; i++) thrower.tickPending(kernel, level);
            level.flushPending(p -> { });
            var shot = shots(level).getLast();
            assertEquals(chance == 0F ? PvzceConstants.KERNEL_DAMAGE : PvzceConstants.BUTTER_DAMAGE, shot.damage());
            assertEquals(PvzceIds.id(chance == 0F ? "kernel" : "butter"), shot.defId());
        }
    }

    @Test void cattailSpikesLeaveTheirOwnRowAndPrioritizeBalloonAcrossRows() {
        var level = lawn();
        var cattail = plant(level, "cattail", 2, 2);
        var ground = zombie(level, "basic_zombie", 4.5F, 2);
        var balloon = zombie(level, "balloon_zombie", 7.5F, 0);
        tick(level, 3);
        var shot = shots(level).stream().filter(p -> p.defId().equals(PvzceIds.id("cattail_spike")))
                .findFirst().orElseThrow();
        assertEquals(cattail.cellY(), shot.cellY(), 0.25F, "birth is at its plant, not in the target lane");
        tick(level, 1);
        assertEquals(balloon.id(), shot.targetId());
        for (int i = 0; i < 150 && !balloon.isGrounded(); i++) tick(level, 1);
        assertTrue(balloon.isGrounded(), "a curved cross-row spike must actually pop the balloon");
        assertEquals(ground.maxHealth(), ground.health(), "the nearer walker cannot steal the balloon volley");
    }

    @Test void aSpikeCanRetargetAfterDeathAndReachBehindThePlant() {
        var level = lawn();
        var source = plant(level, "cattail", 4, 2);
        var first = zombie(level, "basic_zombie", 7.5F, 0);
        var second = zombie(level, "basic_zombie", 1.5F, 4);
        level.spawnProjectile(new ProjectileRef(PvzceIds.id("cattail_spike"), 20, 1),
                source.cellX(), source.cellY(), source);
        level.flushPending(p -> { });
        var shot = shots(level).getFirst();
        shot.tick(level);
        assertEquals(first.id(), shot.targetId());
        first.remove();
        shot.tick(level);
        assertEquals(second.id(), shot.targetId());
        for (int i = 0; i < 160 && !shot.isRemoved(); i++) shot.tick(level);
        assertEquals(second.maxHealth() - 20, second.health());
        assertTrue(shot.isRemoved());
    }

    @Test void cannonOccupiesBothCellsBeforeAndAfterReloadAndRemovalFreesBoth() {
        var level = lawn();
        var cannon = plant(level, "cob_cannon", 3, 2);
        assertEquals(4F, cannon.cellX());
        assertSame(cannon, level.plantAt(3, 2));
        assertSame(cannon, level.plantAt(4, 2));
        for (int x : new int[]{3, 4}) {
            assertFalse(level.canPlacePlant(BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter")), x, 2));
        }
        var restored = lawn();
        restored.restore(level.save());
        assertSame(restored.plantAt(3, 2), restored.plantAt(4, 2));
        restored.plantAt(4, 2).remove();
        restored.flushPending(p -> { });
        assertNull(restored.plantAt(3, 2));
        assertNull(restored.plantAt(4, 2));
    }

    @Test void aLegacySingleCellCannonSaveRestoresAtTheFootprintCentre() {
        var level = lawn();
        var cannon = plant(level, "cob_cannon", 3, 2);
        var legacy = new com.pvzce.common.nbt.CompoundTag();
        cannon.saveState().entries().forEach((key, value) -> {
            if (!key.equals("footprintWidth")) legacy.put(key, value);
        });
        legacy.putFloat("x", 3.5F);
        cannon.restoreState(legacy);
        assertEquals(4F, cannon.cellX());
        assertEquals(3, cannon.gridX());
        assertSame(cannon, level.plantAt(4, 2));
    }

    @Test void pogoActuallyLeavesGroundAndReturnsToIt() {
        var level = lawn();
        var pogo = zombie(level, "pogo_zombie", 8.5F, 2);
        float highest = 0F;
        for (int i = 0; i < 81; i++) { pogo.tick(level); highest = Math.max(highest, pogo.height()); }
        assertTrue(highest >= PvzceConstants.POGO_BOUNCE_HEIGHT - 0.01F);
        assertEquals(0F, pogo.height(), 0.01F);
        assertEquals("pogo", pogo.animation());
    }
}
