package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.ResonanceData;
import com.pvzce.api.content.ZombieStatus;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.capability.plant.EchoRelayCapability;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.ProjectileEntity;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.level.mechanic.ResonanceMechanic;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.common.network.packet.EntityUpdateS2C;
import com.pvzce.common.network.packet.EffectEventS2C;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ResourceDropEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Player-facing evidence: paid planting, a whole shipped round, and a finite echo in a loop. */
class EchoConservatoryTest {
    private static LevelDef shipped;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        shipped = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/minigame/echo_conservatory"));
        assertNotNull(shipped);
    }

    private static final class Bridge implements LevelServer.ServerBridge {
        int waves;
        int zombies;
        int resonantShots;
        MechanicSyncS2C resonance;
        final java.util.Map<Integer, String> animations = new java.util.HashMap<>();

        @Override
        public void send(PvzcePacket packet) {
            if (packet instanceof EntitySpawnS2C spawn) {
                if (PvzceIds.ECHO_WAVE.toString().equals(spawn.defId())) {
                    waves++;
                }
                if ("zombie".equals(spawn.entityKind())) {
                    zombies++;
                }
            }
            if (packet instanceof EntityUpdateS2C update) {
                animations.put(update.entityId(), update.animation());
            }
            if (packet instanceof MechanicSyncS2C sync && sync.mechanic().equals(PvzceIds.MECHANIC_RESONANCE)) {
                resonance = sync;
            }
            if (packet instanceof EffectEventS2C effect && PvzceIds.ECHO_CHIME.toString().equals(effect.sound())
                    && effect.pitch() > 1.1F) {
                resonantShots++;
            }
        }
    }

    private static void tick(LevelServer level, Bridge bridge, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.tick(bridge);
            level.flushPending(bridge);
        }
    }

    private static LevelServer quiet(boolean resonance) {
        return new LevelServer(TestLevels.copy(shipped).waves(List.of()).initialEntities(List.of())
                .mechanics(resonance ? shipped.mechanics() : List.of()).build());
    }

    private static PlantEntity lily(LevelServer level, int x, int y) {
        return level.spawnPlant(BuiltInRegistries.PLANTS.get(PvzceIds.ECHO_LILY),
                level.team(PvzceIds.PLANT_TEAM), x, y);
    }

    private static ZombieEntity zombie(LevelServer level, String id, float x, int y) {
        return level.spawnZombie(PvzceIds.id(id), level.team(PvzceIds.ZOMBIE_TEAM), x, y);
    }

    @Test
    void fourConnectedLiliesRingOnceEachAndDiagonalPlantsDoNotCarryEchoes() {
        LevelServer level = quiet(false);
        Bridge bridge = new Bridge();
        lily(level, 1, 1);
        lily(level, 2, 1);
        lily(level, 1, 2);
        lily(level, 2, 2);
        lily(level, 3, 3); // Only diagonal to the square, and no enemy in its row.
        zombie(level, "buckethead_zombie", 8F, 2);
        level.flushPending(bridge);
        tick(level, bridge, 40);
        assertEquals(4, bridge.waves, "a loop gives four visible waves, with no diagonal fifth");
        tick(level, bridge, 150);
        assertEquals(4, bridge.waves, "a loop does not create extra volleys during its shared rest");
    }

    @Test
    void anEchoAlreadyOnItsWayAndTheResonanceRowSurviveSaving() {
        LevelServer level = quiet(true);
        Bridge bridge = new Bridge();
        lily(level, 1, 1);
        lily(level, 1, 2);
        zombie(level, "buckethead_zombie", 8F, 2);
        level.flushPending(bridge);
        tick(level, bridge, 4); // First wave is out; the neighbour's wave is still promised.
        var save = level.save();
        LevelServer resumed = quiet(true);
        resumed.restore(save);
        Bridge restoredBridge = new Bridge();
        resumed.flushPending(restoredBridge);
        resumed.sendFullState(restoredBridge);
        assertNotNull(restoredBridge.resonance, "even a paused joining client hears the current row");
        ResonanceData data = (ResonanceData) shipped.mechanics().getFirst().value();
        assertEquals(ResonanceMechanic.status(level, data),
                ResonanceMechanic.Status.CODEC.decode(restoredBridge.resonance.payloadBuffer()));
        restoredBridge.waves = 0; // Full-state projectiles are existing waves, not new attacks.
        tick(resumed, restoredBridge, 35);
        assertEquals(1, restoredBridge.waves, "the one promised neighbour wave is delivered once");
    }

    @Test
    void theMovingRowComposesWithSlowAndStopsAcceleratingItsOldLane() {
        LevelServer level = quiet(true);
        Bridge bridge = new Bridge();
        ZombieEntity oldRow = zombie(level, "basic_zombie", 8F, 2);
        ZombieEntity nextRow = zombie(level, "basic_zombie", 8F, 3);
        level.flushPending(bridge);
        float base = nextRow.moveSpeed(level);
        assertEquals(base * 1.35F, oldRow.moveSpeed(level), 0.0001F);
        oldRow.applyStatus(ZombieStatus.SLOW, 900, 0.5F);
        assertEquals(base * 1.35F * 0.5F, oldRow.moveSpeed(level), 0.0001F);
        tick(level, bridge, 720);
        assertEquals(base * 0.5F, oldRow.moveSpeed(level), 0.0001F);
        assertEquals(base * 1.35F, nextRow.moveSpeed(level), 0.0001F);
        assertFalse(LevelMechanics.validate(shipped, PvzceIds.MECHANIC_RESONANCE,
                new ResonanceData(720, 5, 1.35F, 2F)).isEmpty());
    }

    @Test
    void ordinaryRelaysChargeTheirOwnHasteAndSaveItsClockWithoutAnExtraVolley() {
        LevelServer level = quiet(false);
        Bridge bridge = new Bridge();
        PlantEntity first = lily(level, 1, 1);
        PlantEntity second = lily(level, 1, 2);
        ZombieEntity target = zombie(level, "gargantuar", 8F, 2);
        target.applyStatus(ZombieStatus.IMMOBILIZED, 2000, 1F);
        level.flushPending(bridge);
        tick(level, bridge, 14);
        assertEquals("shoot_charge_1", bridge.animations.get(first.id()),
                "the root's charge stays visible during its firing animation");
        assertEquals("echo_charge_1", bridge.animations.get(second.id()),
                "the relayed flower publishes its charge during the response too");
        tick(level, bridge, 386);
        assertEquals(6, bridge.waves, "three volleys, one wave per member, including the charge trigger");
        assertEquals(2, bridge.resonantShots, "the third relay lights up without emitting a bonus volley");
        var flying = level.entities().stream().filter(e -> e instanceof ProjectileEntity)
                .map(e -> (ProjectileEntity) e).toList();
        assertFalse(flying.isEmpty());
        assertTrue(flying.stream().allMatch(e -> e.damage() == 60), "haste preserves wave damage");
        LevelServer resumed = quiet(false);
        resumed.restore(level.save());
        Bridge restoredBridge = new Bridge();
        resumed.flushPending(restoredBridge);
        bridge.waves = 0;
        bridge.resonantShots = 0;
        restoredBridge.waves = 0;
        restoredBridge.resonantShots = 0;
        // Compare every tick, so saving cannot hide a shifted next volley or duplicate a promise.
        for (int i = 0; i < 420; i++) {
            tick(level, bridge, 1);
            tick(resumed, restoredBridge, 1);
            assertEquals(bridge.waves, restoredBridge.waves, "same next-shot timing after restore");
            assertEquals(bridge.resonantShots, restoredBridge.resonantShots);
        }
        assertEquals(8, bridge.waves, "four further finite volleys fit into the charged cadence");
    }

    @Test
    void enteringAndLeavingTheRowChangesOnlyFutureCooldownProgress() {
        LevelServer level = quiet(false);
        Bridge bridge = new Bridge();
        PlantEntity lily = lily(level, 1, 2);
        ZombieEntity target = zombie(level, "gargantuar", 8F, 2);
        target.applyStatus(ZombieStatus.IMMOBILIZED, 2000, 1F);
        level.flushPending(bridge);
        tick(level, bridge, 120);
        assertEquals(1, bridge.waves);
        level.installMechanic(shipped.mechanics().getFirst());
        tick(level, bridge, 1);
        assertEquals(1, bridge.waves, "entering haste does not multiply the 120 ticks already elapsed");
        tick(level, bridge, 29);
        assertEquals(1, bridge.waves, "haste accelerates the remaining 61 steps only");
        tick(level, bridge, 1);
        assertEquals(2, bridge.waves);
        level.removeMechanic(PvzceIds.MECHANIC_RESONANCE);
        tick(level, bridge, 179);
        assertEquals(2, bridge.waves, "leaving haste keeps the remaining cooldown, without a burst");
        tick(level, bridge, 1);
        assertEquals(3, bridge.waves, "a solo lily resumes its normal three-second clock");
        CompoundTag capabilitySave = new CompoundTag();
        lily.capability(EchoRelayCapability.class).save(capabilitySave);
        assertEquals(0, capabilitySave.getInt("charge"), "a solo shot cannot charge a relay");
    }

    private record Purchase(Identifier card, int x, int y) {
        Purchase(String card, int x, int y) {
            this(PvzceIds.id(card), x, y);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {17L, 20261001L, 81L})
    void aPlayerCanWinTheShippedLevelWithRealCostsAndCooldowns(long seed) {
        // A fresh profile has not unlocked the lily. The level's fixed deck lends it, and the
        // clear reward then makes it available in ordinary seed selection.
        PlayerProfile profile = PlayerProfile.starter();
        LevelServer level = new LevelServer(shipped, SeedSelection.defaultFor(shipped, profile),
                LevelServer.SeedContext.forProfile(shipped, profile));
        level.random().setSeed(seed);
        Bridge bridge = new Bridge();
        level.flushPending(bridge);
        List<Purchase> plan = new ArrayList<>();
        for (int y = 0; y < 4; y++) {
            plan.add(new Purchase("sunflower", 0, y));
        }
        for (int y : new int[]{0, 3, 4}) {
            plan.add(new Purchase("echo_lily", 1, y));
        }
        for (int y = 0; y < 5; y++) {
            plan.add(new Purchase("echo_lily", 2, y));
        }
        for (int y = 0; y < 5; y++) {
            plan.add(new Purchase("wall_nut", 4, y));
        }
        int next = 0;
        int spent = 0;
        long peakZombies = 0;
        int collected = 0;
        for (int i = 0; i < 24000 && GameStateS2C.RUNNING.equals(level.gameState()); i++) {
            tick(level, bridge, 1);
            for (var entity : level.entities()) {
                if (entity instanceof ResourceDropEntity drop && !drop.isRemoved()) {
                    int before = level.team(PvzceIds.PLANT_TEAM).resourcesOf(PvzceIds.SUN);
                    level.collectResource(bridge, drop.id());
                    collected += level.team(PvzceIds.PLANT_TEAM).resourcesOf(PvzceIds.SUN) - before;
                }
            }
            peakZombies = Math.max(peakZombies, level.aliveZombieCount());
            if (next < plan.size() && i % 30 == 0) {
                Purchase purchase = plan.get(next);
                int slot = -1;
                for (int s = 0; s < level.slotInfos().size(); s++) {
                    if (level.plantPlayer().slot(s).defId().equals(purchase.card())) {
                        slot = s;
                        break;
                    }
                }
                assertTrue(slot >= 0, "the fixed deck includes " + purchase.card());
                int before = level.team(PvzceIds.PLANT_TEAM).resourcesOf(PvzceIds.SUN);
                if (level.placePlant(bridge, slot, purchase.x(), purchase.y())) {
                    spent += before - level.team(PvzceIds.PLANT_TEAM).resourcesOf(PvzceIds.SUN);
                    next++;
                }
            }
        }
        int remaining = level.team(PvzceIds.PLANT_TEAM).resourcesOf(PvzceIds.SUN);
        System.out.printf("[echo] seed=%d difficulty=%s state=%s seconds=%.1f spawned=%d peak=%d waves=%d resonantShots=%d purchases=%d spent=%d collected=%d remaining=%d rowEvery=12.0s lilyRate=2x speed=1.35x%n",
                seed, level.difficulty(), level.gameState(), level.tickCount() / 60F, bridge.zombies,
                peakZombies, bridge.waves, bridge.resonantShots, next, spent, collected, remaining);
        assertEquals(GameStateS2C.WON, level.gameState(), "the paid, connected defence wins");
        assertEquals(35, bridge.zombies, "all authored enemies were fought");
        assertTrue(spent >= 1075, "the win used purchased lilies and producers, with no free planting");
        assertTrue(shipped.rewards().firstClear().stream().anyMatch(r -> r.isUnlock()
                && r.id().orElse(null).equals(PvzceIds.ECHO_LILY)), "the clear unlocks ordinary use");
    }
}
