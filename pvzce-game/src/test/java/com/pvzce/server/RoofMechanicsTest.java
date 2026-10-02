package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.ZombieStatus;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.buff.BuiltInBuffs;
import com.pvzce.common.capability.zombie.BungeeCapability;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Real roof simulations: the slope, delivered enemies, ladders and timed care must meet. */
class RoofMechanicsTest {
    @BeforeAll static void load() throws Exception { TestContent.loadBuiltInContentAndTags(); }
    private static LevelServer roof() {
        var def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/5_9"));
        return new LevelServer(TestLevels.copy(def).waves(List.of()).initialEntities(List.of())
                .slots(List.of(PvzceIds.FERTILIZER)).buffs(LevelDef.LevelBuffPlan.NONE).build(), 20261002L);
    }
    private static PlantEntity plant(LevelServer level, String id, int x, int y) {
        if (level.plantsAt(x, y).isEmpty()) level.spawnPlant(BuiltInRegistries.PLANTS.get(PvzceIds.id("flower_pot")), level.team(PvzceIds.PLANT_TEAM), x, y);
        return level.spawnPlant(BuiltInRegistries.PLANTS.get(PvzceIds.id(id)), level.team(PvzceIds.PLANT_TEAM), x, y);
    }
    private static ZombieEntity zombie(LevelServer level, String id, float x, int y) {
        return level.spawnZombie(PvzceIds.id(id), level.team(PvzceIds.ZOMBIE_TEAM), x, y);
    }
    private static void tick(LevelServer level, int ticks, List<PvzcePacket> sent) {
        level.flushPending(sent::add);
        for (int i = 0; i < ticks; i++) level.tick(sent::add);
    }
    @Test void lobbersActuallyHitAcrossTheSlope() {
        for (String id : List.of("cabbage_pult", "kernel_pult", "melon_pult")) {
            var level = roof(); plant(level, id, 0, 2);
            var target = zombie(level, "basic_zombie", 7.5F, 2);
            target.applyStatus(ZombieStatus.IMMOBILIZED, 600, 1F);
            tick(level, 450, new ArrayList<>());
            assertTrue(target.health() < target.maxHealth(), id + " must solve an arc onto the raised flat roof");
        }
    }
    @Test void fertilizerIsFreeAndExpiresBeforeItsCooldown() {
        var level = roof(); var flower = plant(level, "sunflower", 0, 2);
        var sent = new ArrayList<PvzcePacket>();
        assertFalse(level.useTool(sent::add, 0, 8, 2));
        assertEquals(0, level.plantPlayer().slot(0).cooldownLeft());
        assertTrue(level.useTool(sent::add, 0, 0, 2));
        assertEquals(2700, level.plantPlayer().slot(0).cooldownLeft());
        assertEquals(1.5F, flower.actionRate());
        assertFalse(level.useTool(sent::add, 0, 0, 2));
        tick(level, 1200, sent);
        assertEquals(0, flower.fertilizedTicks());
        assertEquals(1F, flower.actionRate());
        assertEquals(1500, level.plantPlayer().slot(0).cooldownLeft());
        var restored = new PlantEntity(flower.def(), flower.team(), 0, 2);
        flower.fertilize(); restored.restoreState(flower.saveState());
        assertEquals(1200, restored.fertilizedTicks());
    }
    private record Output(long shots, long sun) {}
    private static Output measure(boolean fertilizer) {
        var level = roof(); var kernel = plant(level, "kernel_pult", 0, 2);
        level.setRule(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN, 0);
        level.setRule(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX, 0);
        var flower = plant(level, "sunflower", 0, 1);
        var target = zombie(level, "basic_zombie", 7.5F, 2);
        var state = target.saveState(); state.putInt("health", 100000); target.restoreState(state); target.applyStatus(ZombieStatus.IMMOBILIZED, 1500, 1F);
        if (fertilizer) { kernel.fertilize(); flower.fertilize(); }
        var sent = new ArrayList<PvzcePacket>(); tick(level, 1200, sent);
        return new Output(sent.stream().filter(p -> p instanceof EntitySpawnS2C e && (e.defId().equals("pvzce:kernel") || e.defId().equals("pvzce:butter"))).count(),
                sent.stream().filter(p -> p instanceof EntitySpawnS2C e && e.entityKind().equals("resource") && e.defId().equals("pvzce:sun")).count());
    }
    @Test void twentySecondsOfFertilizerReallyProducesMore() {
        var normal = measure(false); var boosted = measure(true);
        System.out.printf("[roof-care] normal20s=%s fertilized20s=%s; duration=20s recharge=45s single-target duty=44.4%% average-rate=1.222x%n", normal, boosted);
        assertTrue(boosted.shots > normal.shots);
        assertTrue(boosted.sun > normal.sun);
    }
    @Test void laddersOpenTheWallAndCanBePulledOrBurnt() {
        var level = roof(); var wall = plant(level, "wall_nut", 3, 2);
        var ladder = zombie(level, "ladder", 3.7F, 2);
        tick(level, 110, new ArrayList<>());
        assertTrue(wall.laddered()); assertEquals(wall.maxHealth(), wall.health());
        assertNull(ladder.magneticItem());
        var walker = zombie(level, "basic_zombie", 3.6F, 2);
        tick(level, 20, new ArrayList<>()); assertEquals(wall.maxHealth(), wall.health());
        var magnet = plant(level, "magnet_shroom", 1, 2); magnet.wake();
        tick(level, 10, new ArrayList<>()); assertFalse(wall.laddered());
        wall.setLaddered(true);
        level.damageRow(BuiltInRegistries.DAMAGE_TYPES.get(PvzceIds.id("ash")), 2, 1800, wall.team());
        assertFalse(wall.laddered());
    }
    @Test void umbrellaStopsBothCargoAndBasketballsEvenUnderPumpkin() {
        for (boolean shield : List.of(false, true)) {
            var level = roof(); var flower = plant(level, "sunflower", 0, 2);
            if (shield) { plant(level, "umbrella_leaf", 1, 2); plant(level, "pumpkin", 1, 2); }
            var vehicle = zombie(level, "catapult", 7.1F, 2);
            tick(level, 180, new ArrayList<>());
            assertEquals(shield ? 300 : 225, flower.health());
            var carrier = zombie(level, "bungee_zombie", 8F, 2);
            carrier.capability(BungeeCapability.class).deliver(PvzceIds.id("basic_zombie"), 1, 1F);
            var sent = new ArrayList<PvzcePacket>(); tick(level, 200, sent);
            assertEquals(!shield, sent.stream().anyMatch(p -> p instanceof EntitySpawnS2C e && e.defId().equals("pvzce:basic_zombie")));
        }
    }
    @Test void emptyCatapultLaneKeepsMoving() {
        var level = roof(); var vehicle = zombie(level, "catapult", 7F, 2);
        tick(level, 120, new ArrayList<>()); assertTrue(vehicle.cellX() < 6.8F);
    }
    @Test void aSavedBasketballKeepsItsEnemyTeamAndRaisedLandingPoint() {
        var level = roof(); plant(level, "sunflower", 0, 2);
        zombie(level, "catapult", 7.1F, 2);
        tick(level, 30, new ArrayList<>());
        var resumed = new LevelServer(level.def(), 17L);
        resumed.restore(level.save());
        tick(resumed, 150, new ArrayList<>());
        assertEquals(225, resumed.plantAt(0, 2).health());
    }
    @Test void everyShippedRoofWaveSequenceCanReachSettlement() {
        // A late-game prebuilt formation checks the complete wave/cargo/settlement path.
        // This does not claim that an early-game player can afford or own this opening.
        for (int sub = 1; sub <= 9; sub++) {
            var def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/5_" + sub));
            var level = new LevelServer(def, 20261002L);
            for (int row = 0; row < 5; row++) {
                plant(level, "kernel_pult", 0, row);
                plant(level, "umbrella_leaf", 1, row);
                for (int x = 2; x <= 4; x++) plant(level, "melon_pult", x, row);
            }
            long peak = 0;
            for (int t = 0; t < 90000 && "running".equals(level.gameState()); t++) {
                level.tick(packet -> {});
                peak = Math.max(peak, level.aliveZombieCount());
            }
            System.out.printf("[roof-flow] 5-%d state=%s waves=%d seconds=%.1f peak=%d remaining-plants=%d%n",
                    sub, level.gameState(), level.currentWave(), level.tickCount() / 60.0, peak, level.plantCount());
            assertEquals("won", level.gameState(), "prebuilt roof formation must finish 5-" + sub);
        }
    }
    @Test void butterBuffChangesOnlyKernelPultAndOldProfilesReceiveBothRewards() {
        var level = roof(); var kernel = plant(level, "kernel_pult", 0, 2); var melon = plant(level, "melon_pult", 0, 1);
        level.setActiveBuffs(List.of(BuiltInBuffs.BUTTER_PLENTY));
        assertEquals(.4F, level.butterChance(kernel, .25F)); assertEquals(.25F, level.butterChance(melon, .25F));
        var profile = PlayerProfile.load(new com.pvzce.common.nbt.CompoundTag());
        for (String name : List.of("5_4", "5_9")) {
            var def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/" + name));
            RewardSettlement.applyLevelRewards(def.id(), def.rewards(), false, profile);
        }
        assertTrue(profile.ownsCard(PvzceIds.FERTILIZER)); assertTrue(profile.ownsBuff(PvzceIds.BUFF_BUTTER_PLENTY));
        long normal = butterSample(false), boosted = butterSample(true);
        System.out.printf("[roof-butter] same-seed 1000 volleys: normal=%d buff=%d; declared chance=25%% -> 40%%; expected butter/stun output=1.6x%n", normal, boosted);
        assertTrue(boosted > normal, "the buff must actually replace more kernels with butter");
    }
    private static long butterSample(boolean buff) {
        var level = roof(); var kernel = plant(level, "kernel_pult", 0, 2);
        zombie(level, "basic_zombie", 7.5F, 2);
        if (buff) level.setActiveBuffs(List.of(BuiltInBuffs.BUTTER_PLENTY));
        var sent = new ArrayList<PvzcePacket>();
        for (int i = 0; i < 1000; i++) {
            kernel.capability(com.pvzce.common.capability.plant.ThrowerCapability.class).strike(kernel, level);
            level.flushPending(sent::add);
        }
        return sent.stream().filter(p -> p instanceof EntitySpawnS2C e && "pvzce:butter".equals(e.defId())).count();
    }
}
