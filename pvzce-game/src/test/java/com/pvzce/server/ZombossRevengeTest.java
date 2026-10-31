package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.ZombieStatus;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.capability.zombie.ZombossCapability;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.MowerMechanic;
import com.pvzce.common.level.mechanic.ZombossMechanic;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.server.level.cardsource.BeltCardSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.pvzce.common.PvzceConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic combat interactions, using the shipped board, plants and damage pipeline. */
class ZombossRevengeTest {
    private static final LevelServer.ServerBridge SILENT = packet -> {};
    private static LevelDef def;

    @BeforeAll static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/minigame/zomboss_revenge"));
        assertNotNull(def);
    }

    private static LevelServer board() {
        LevelServer level = new LevelServer(def);
        level.random().setSeed(20261031);
        level.flushPending(SILENT);
        return level;
    }
    private static void tick(LevelServer level, int count) {
        for (int i = 0; i < count; i++) level.tick(SILENT);
    }
    private static ZombieEntity boss(LevelServer level) { return ZombossMechanic.boss(level); }
    private static ZombossCapability cap(LevelServer level) { return boss(level).capability(ZombossCapability.class); }
    private static CompoundTag state(LevelServer level, String phase, int ticks) {
        CompoundTag tag = new CompoundTag();
        cap(level).save(tag);
        tag.putString("phase", phase); tag.putInt("timer", ticks); tag.putInt("age", 100);
        tag.putInt("headClock", 10000); tag.putInt("spawnLeft", 10000);
        tag.putInt("bungeeLeft", 10000); tag.putInt("stompLeft", 10000);
        return tag;
    }
    private static PlantEntity plant(LevelServer level, String id, int x, int y) {
        PlantEntity plant = level.spawnPlant(BuiltInRegistries.PLANTS.get(PvzceIds.id(id)),
                level.team(PvzceIds.PLANT_TEAM), x, y);
        level.flushPending(SILENT);
        return plant;
    }

    @Test void shippedBoardHasOriginalPoolOpeningAndStationaryBoss() {
        LevelServer level = board();
        assertEquals(60000, boss(level).maxHealth());
        assertTrue(def.waves().isEmpty());
        for (var id : ZombossCapability.summonIds()) assertNotNull(BuiltInRegistries.ZOMBIES.get(id), id.toString());
        assertTrue(level.isNight());
        BeltCardSource source = (BeltCardSource) level.cardSource();
        assertEquals(List.of("cabbage_pult", "jalapeno", "cabbage_pult", "ice_shroom"),
                source.belt().cards().stream().map(c -> c.cardId().path()).toList());
        assertEquals(List.of("flower_pot", "melon_pult", "jalapeno", "cabbage_pult", "kernel_pult", "ice_shroom"),
                source.belt().def().cards().stream().map(c -> c.card().path()).toList());
        float x = boss(level).cellX();
        tick(level, 250);
        assertEquals(x, boss(level).cellX());
        assertEquals(GameStateS2C.RUNNING, level.gameState());
    }

    @Test void hiddenBossRejectsAshAndButterButLoweredHeadCanBeLobbedFromEveryRow() {
        LevelServer level = board();
        boss(level).damage(1800, ZombieEntity.damageType(PvzceIds.DAMAGE_ASH), level);
        boss(level).applyStatus(ZombieStatus.IMMOBILIZED, 100, 1F);
        assertEquals(60000, boss(level).health());
        assertFalse(boss(level).frozen());
        for (int row = 0; row < 5; row++) plant(level, "cabbage_pult", 1, row);
        cap(level).load(state(level, "head_before", 1000));
        boss(level).applyStatus(ZombieStatus.BUTTERED, 100, 1F);
        assertFalse(boss(level).buttered());
        for (int row = 0; row < 5; row++) assertTrue(level.enemiesInRow(row, level.team(PvzceIds.PLANT_TEAM)).contains(boss(level)));
        tick(level, 300);
        assertTrue(boss(level).health() <= 60000 - 5 * 40, "all five lobbers must hit the shared head");
    }

    @Test void iceShroomExtinguishesFireBallEvenWithHiddenHeadAndDoesNotDestroyIceBall() {
        LevelServer level = board();
        CompoundTag tag = state(level, "idle", 1000);
        tag.putInt("ballType", 1); tag.putInt("ballRow", 4); tag.putFloat("ballX", 7);
        cap(level).load(tag);
        plant(level, "ice_shroom", 0, 1);
        tick(level, 50);
        assertEquals(0, cap(level).status(boss(level)).ballType());
        assertFalse(boss(level).frozen());
        tag = state(level, "idle", 1000);
        tag.putInt("ballType", 2); tag.putFloat("ballX", 7);
        cap(level).load(tag);
        plant(level, "ice_shroom", 0, 1);
        tick(level, 50);
        assertEquals(2, cap(level).status(boss(level)).ballType());
    }

    @Test void jalapenoOnlyMeltsIceInItsOwnLaneAndThawsAnExposedBoss() {
        LevelServer level = board();
        CompoundTag tag = state(level, "head_before", 1000);
        tag.putInt("ballType", 2); tag.putInt("ballRow", 4); tag.putFloat("ballX", 7);
        cap(level).load(tag);
        boss(level).applyStatus(ZombieStatus.IMMOBILIZED, 1000, 1F);
        plant(level, "jalapeno", 0, 1);
        tick(level, 65);
        assertEquals(2, cap(level).status(boss(level)).ballType());
        assertEquals(60000 - 1800, boss(level).health(), "a row burn hits the whole lowered head once");
        assertFalse(boss(level).frozen());
        plant(level, "jalapeno", 0, 4);
        tick(level, 65);
        assertEquals(0, cap(level).status(boss(level)).ballType());
        assertEquals(60000 - 3600, boss(level).health());
    }

    @Test void rollingBallCrushesPlantsAndMowerWithoutLaunchingIt() {
        LevelServer level = board();
        PlantEntity cabbage = plant(level, "cabbage_pult", 0, 2);
        CompoundTag tag = state(level, "idle", 1000);
        tag.putInt("ballType", 1); tag.putInt("ballRow", 2); tag.putFloat("ballX", 0.5F);
        cap(level).load(tag);
        tick(level, 80);
        assertTrue(cabbage.isRemoved());
        assertTrue(level.plantsAt(0, 2).isEmpty(), "the flower pot is crushed too");
        MowerMechanic.Rig rig = level.mechanicStateOrNull(PvzceIds.MECHANIC_MOWER, MowerMechanic.Rig.class);
        assertEquals(MowerMechanic.STATE_USED, rig.state().rows().stream().filter(r -> r.row() == 2).findFirst().orElseThrow().state());
        assertEquals(GameStateS2C.RUNNING, level.gameState());
    }

    @Test void releasedRoofCleanerPassesThroughTheLoweredMachine() {
        LevelServer level = board();
        cap(level).load(state(level, "head_before", 1000));
        boss(level).setCellX(ZOMBOSS_HEAD_X);
        boss(level).setCellY(ZOMBOSS_HEAD_Y);
        assertTrue(level.releaseMower((int)ZOMBOSS_HEAD_Y));
        tick(level, 200);
        assertEquals(60000, boss(level).health());
    }

    @Test void stompAndCamperContactsHaveDifferentFootprints() {
        LevelServer level = board();
        PlantEntity safe = plant(level, "cabbage_pult", 4, 1);
        PlantEntity front = plant(level, "cabbage_pult", 5, 1);
        CompoundTag tag = state(level, "stomp", ZOMBOSS_STOMP_TICKS / 2 + 1);
        tag.putInt("actionTicks", ZOMBOSS_STOMP_TICKS); tag.putInt("targetRow", 1); tag.putInt("targetColumn", 5);
        cap(level).load(tag);
        tick(level, 1);
        assertFalse(safe.isRemoved()); assertTrue(front.isRemoved());
        tag = state(level, "rv", ZOMBOSS_RV_TICKS - Math.round(ZOMBOSS_RV_TICKS * 0.65F) + 1);
        tag.putInt("actionTicks", ZOMBOSS_RV_TICKS); tag.putInt("targetColumn", 2); tag.putInt("targetRow", 1);
        cap(level).load(tag);
        tick(level, 1);
        assertTrue(safe.isRemoved());
        assertNotNull(level.plantAt(1, 1), "outside the 3 × 2 camper footprint survives");
    }

    @Test void bungeesTargetingEmptyCellsLeaveAndBossContinuesToItsNextAttack() {
        LevelServer level = board();
        for (var entity : level.entities()) if (entity instanceof PlantEntity p) p.remove();
        level.flushPending(SILENT);
        CompoundTag tag = state(level, "bungee", ZOMBOSS_BUNGEE_TICKS - Math.round(ZOMBOSS_BUNGEE_TICKS * 0.4F) + 1);
        tag.putInt("actionTicks", ZOMBOSS_BUNGEE_TICKS);
        tag.putInt("headClock", 1);
        cap(level).load(tag);
        tick(level, 1);
        assertEquals(1 + ZOMBOSS_BUNGEE_COUNT, level.aliveZombieCount());
        LevelServer resumed = new LevelServer(def);
        resumed.restore(level.save());
        tick(resumed, 600);
        assertEquals(1, resumed.aliveZombieCount());
        assertTrue(cap(resumed).exposed(), "empty theft targets cannot block the next head attack");
    }

    @Test void frozenActionAndTravellingBallResumeWithoutReplayingTheContact() {
        LevelServer level = board();
        CompoundTag tag = state(level, "head_spit", 150);
        tag.putInt("actionTicks", ZOMBOSS_SPIT_TICKS); tag.putInt("ballType", 2);
        tag.putInt("ballRow", 4); tag.putFloat("ballX", 6);
        cap(level).load(tag);
        boss(level).applyStatus(ZombieStatus.IMMOBILIZED, 100, 1F);
        tick(level, 15);
        LevelServer resumed = new LevelServer(def);
        resumed.restore(level.save());
        assertEquals(cap(level).status(boss(level)), cap(resumed).status(boss(resumed)));
        tick(level, 20); tick(resumed, 20);
        assertEquals(cap(level).status(boss(level)), cap(resumed).status(boss(resumed)));
        assertEquals(150, cap(resumed).status(boss(resumed)).ticksLeft());
    }

    @Test void lethalPlantTickCompletesVictoryEvenWhenCorpseExpiresBeforeTheNextMechanicTick() {
        LevelServer level = board();
        cap(level).load(state(level, "head_before", 1000));
        boss(level).damage(60000 - 1800, ZombieEntity.damageType(PvzceIds.DAMAGE_ASH), level);
        plant(level, "jalapeno", 0, 4);
        tick(level, 65 + ZOMBOSS_DEATH_TICKS);
        assertEquals(GameStateS2C.WON, level.gameState());
    }

    @Test void killingBossSweepsSummonsAndWinsAfterDeathAnimationIncludingResume() {
        LevelServer level = board();
        cap(level).load(state(level, "head_before", 1000));
        level.spawnZombie(PvzceIds.id("gargantuar"), level.team(PvzceIds.ZOMBIE_TEAM), 7.5F, 3);
        level.flushPending(SILENT);
        boss(level).damage(60000, ZombieEntity.damageType(PvzceIds.DAMAGE_ASH), level);
        tick(level, 30);
        assertEquals(GameStateS2C.RUNNING, level.gameState());
        assertEquals(0, level.aliveZombieCount());
        LevelServer resumed = new LevelServer(def);
        resumed.restore(level.save());
        tick(resumed, ZOMBOSS_DEATH_TICKS);
        assertEquals(GameStateS2C.WON, resumed.gameState());
    }
}
