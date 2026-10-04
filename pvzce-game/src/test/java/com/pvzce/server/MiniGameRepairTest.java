package com.pvzce.server;

import com.pvzce.api.content.*;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SeedOptions;
import com.pvzce.common.level.mechanic.MowerMechanic;
import com.pvzce.common.level.mechanic.PortalMechanic;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.CardDropEntity;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ProjectileEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Regressions through the real tick/save/packet paths used by the shipped minigames. */
class MiniGameRepairTest {
    private static final LevelServer.ServerBridge SILENT = packet -> {};

    @BeforeAll
    static void content() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static LevelDef shipped(String name) {
        return BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/minigame/" + name));
    }

    private static void tick(LevelServer level, LevelServer.ServerBridge bridge, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.tick(bridge);
            level.flushPending(bridge);
        }
    }

    private static void clearZombies(LevelServer level) {
        for (var entity : level.entities()) {
            if (entity instanceof ZombieEntity zombie && zombie.isAlive()) {
                zombie.damageBody(zombie.health(), level);
            }
        }
    }

    private static int sun(LevelServer level) {
        return level.team(PvzceIds.PLANT_TEAM).resourcesOf(PvzceIds.SUN);
    }

    @Test
    void repeatedPreparationWaitsForTheWholeFlagAndSurvivesReload() {
        WaveDef wave = WaveDef.declaringSpawnInterval(WaveDef.WaveType.SMALL, 1, 0,
                List.of(new WaveDef.Entry(PvzceIds.id("basic_zombie"), 2, List.of(0))), 90);
        LevelDef def = TestLevels.copy(shipped("last_stand"))
                .waves(List.of(wave, wave, wave))
                .mechanics(List.of(new TypedMechanic(PvzceIds.MECHANIC_PREPARATION,
                        new PreparationData(true, 0, true, 1, 250, List.of()))))
                .build();
        LevelServer level = new LevelServer(def);
        var plant = level.spawnPlant(BuiltInRegistries.PLANTS.get(PvzceIds.id("wall_nut")),
                level.team(PvzceIds.PLANT_TEAM), 3, 0);
        level.flushPending(SILENT);
        level.beginWaves();
        tick(level, SILENT, 2);
        assertEquals(1, level.currentWave());
        clearZombies(level);
        tick(level, SILENT, 30);
        assertFalse(level.isPreparing(), "the second zombie is still queued in the flag wave");
        assertEquals(1, level.currentWave(), "the next stage must not begin early");
        assertEquals(5000, sun(level));
        for (int i = 0; i < 150 && !level.isPreparing(); i++) {
            clearZombies(level);
            tick(level, SILENT, 1);
        }
        assertTrue(level.isPreparing());
        assertEquals(5250, sun(level));
        assertFalse(plant.isRemoved(), "the defence stays between stages");
        LevelServer resumed = new LevelServer(def);
        resumed.restore(level.save());
        tick(resumed, SILENT, 120);
        assertTrue(resumed.isPreparing());
        assertEquals(5250, sun(resumed), "reloading must not grant the stage supply twice");
        resumed.beginWaves();
        for (int i = 0; i < 250 && !resumed.isPreparing(); i++) {
            clearZombies(resumed);
            tick(resumed, SILENT, 1);
        }
        assertEquals(2, resumed.currentWave());
        assertTrue(resumed.isPreparing());
        assertEquals(5500, sun(resumed));
    }

    @Test
    void lastStandCannotChooseOrPlantSunProducers() {
        LevelDef def = shipped("last_stand");
        var pool = SeedOptions.cardPool(def, null);
        for (String name : List.of("sunflower", "twin_sunflower", "sun_shroom", "marigold")) {
            assertFalse(pool.contains(PvzceIds.id(name)), name);
            LevelServer level = new LevelServer(def);
            assertFalse(level.canPlacePlant(BuiltInRegistries.PLANTS.get(PvzceIds.id(name)), 0, 0));
        }
        assertEquals(6, def.height());
        assertEquals(50, def.waves().size());
    }

    private static LevelDef portalBoard(PortalData portals) {
        return TestLevels.copy(shipped("portal_combat")).waves(List.of())
                .mechanics(List.of(new TypedMechanic(PvzceIds.MECHANIC_PORTAL, portals))).build();
    }

    @Test
    void balloonZombiesUseTheSamePortalsAsWalkers() {
        LevelServer level = new LevelServer(portalBoard(
                new PortalData(List.of(new PortalData.Pair(2, 0, 6, 3)))));
        ZombieEntity balloon = level.spawnZombie(PvzceIds.id("balloon_zombie"),
                level.team(PvzceIds.ZOMBIE_TEAM), 7F, 3);
        level.flushPending(SILENT);
        tick(level, SILENT, 120);
        assertEquals(0, balloon.gridY());
        assertTrue(balloon.cellX() < 2.5F);
    }

    @Test
    void shotsAndMowersTravelAndHitTheDestinationLane() {
        LevelDef def = portalBoard(new PortalData(List.of(new PortalData.Pair(2, 0, 6, 3))));
        LevelServer level = new LevelServer(def);
        var shot = new ProjectileEntity(BuiltInRegistries.PROJECTILES.get(PvzceIds.id("pea")),
                new ProjectileRef(PvzceIds.id("pea"), 20, 1),
                level.team(PvzceIds.PLANT_TEAM), 2.4F, 0.5F, 0F);
        var target = level.spawnZombie(PvzceIds.id("basic_zombie"),
                level.team(PvzceIds.ZOMBIE_TEAM), 7.3F, 3);
        level.addEntity(shot);
        level.flushPending(SILENT);
        int health = target.health();
        tick(level, SILENT, 30);
        assertTrue(target.health() < health, "a pea entering row 0 hits a zombie in row 3");

        LevelServer mowerLevel = new LevelServer(def);
        var destination = mowerLevel.spawnZombie(PvzceIds.id("buckethead_zombie"),
                mowerLevel.team(PvzceIds.ZOMBIE_TEAM), 7.5F, 3);
        var skipped = mowerLevel.spawnZombie(PvzceIds.id("buckethead_zombie"),
                mowerLevel.team(PvzceIds.ZOMBIE_TEAM), 4F, 3);
        mowerLevel.flushPending(SILENT);
        assertTrue(mowerLevel.releaseMower(0));
        List<PvzcePacket> packets = new ArrayList<>();
        tick(mowerLevel, packets::add, 58);
        assertTrue(skipped.isAlive(), "teleporting must not mow the stretch skipped at the destination");
        MowerMechanic.State state = packets.stream()
                .filter(p -> p instanceof MechanicSyncS2C sync && sync.mechanic().equals(PvzceIds.MECHANIC_MOWER))
                .map(p -> MowerMechanic.State.CODEC.decode(((MechanicSyncS2C) p).payloadBuffer()))
                .reduce((a, b) -> b).orElseThrow();
        assertEquals(3, state.rows().stream().filter(row -> row.row() == 0).findFirst().orElseThrow().lane());
        LevelServer resumed = new LevelServer(def);
        resumed.restore(mowerLevel.save());
        tick(resumed, SILENT, 25);
        assertEquals(1, resumed.aliveZombieCount(), "the resumed mower hits ahead but leaves the skipped zombie");
        assertTrue(destination.isAlive(), "the fixture was saved before the mower reached it");
    }

    @Test
    void relocatingPortalPositionsAndCountdownSurviveReload() {
        LevelDef def = portalBoard(new PortalData(List.of(new PortalData.Pair(2, 0, 6, 3)), 100, 5));
        LevelServer level = new LevelServer(def);
        List<PvzcePacket> packets = new ArrayList<>();
        tick(level, packets::add, 5);
        PortalMechanic.State moved = packets.stream()
                .filter(p -> p instanceof MechanicSyncS2C sync && sync.mechanic().equals(PvzceIds.MECHANIC_PORTAL))
                .map(p -> PortalMechanic.State.CODEC.decode(((MechanicSyncS2C) p).payloadBuffer()))
                .reduce((a, b) -> b).orElseThrow();
        assertNotEquals(List.of(new PortalData.Pair(2, 0, 6, 3)), moved.pairs());
        LevelServer resumed = new LevelServer(def);
        resumed.restore(level.save());
        List<PvzcePacket> restoredPackets = new ArrayList<>();
        resumed.sendFullState(restoredPackets::add);
        PortalMechanic.State restored = restoredPackets.stream()
                .filter(p -> p instanceof MechanicSyncS2C sync && sync.mechanic().equals(PvzceIds.MECHANIC_PORTAL))
                .map(p -> PortalMechanic.State.CODEC.decode(((MechanicSyncS2C) p).payloadBuffer()))
                .findFirst().orElseThrow();
        assertEquals(moved, restored);
        assertEquals(level.save().getCompound("Portals").getInt("Ticks"),
                resumed.save().getCompound("Portals").getInt("Ticks"));
    }

    @Test
    void fallingSeedsKeepTheirLifetimeUntilLandingAndTheirCapAfterReload() {
        var rain = new SeedRainData(30, 0,
                List.of(new SeedRainData.Card(PvzceIds.id("pea_shooter"), 1, 1)), 30, false, true);
        LevelDef def = TestLevels.copy(shipped("raining_seeds")).waves(List.of())
                .mechanics(List.of(new TypedMechanic(PvzceIds.MECHANIC_SEED_RAIN, rain))).build();
        LevelServer level = new LevelServer(def);
        tick(level, SILENT, 1);
        CardDropEntity first = level.entities().stream().filter(CardDropEntity.class::isInstance)
                .map(CardDropEntity.class::cast).findFirst().orElseThrow();
        float high = first.position().projectedY();
        tick(level, SILENT, 20);
        assertTrue(first.position().projectedY() < high);
        assertTrue(first.position().projectedY() > first.landingRow() + 0.5F);
        assertEquals(first.landingRow() + 0.5F, first.cellY(), .001F);
        assertEquals(PvzceConstants.CARD_DROP_LIFETIME_TICKS, first.ticksLeft());
        LevelServer resumed = new LevelServer(def);
        resumed.restore(level.save());
        CardDropEntity saved = resumed.entities().stream().filter(CardDropEntity.class::isInstance)
                .map(CardDropEntity.class::cast).findFirst().orElseThrow();
        assertEquals(first.cellY(), saved.cellY());
        assertEquals(first.height(), saved.height());
        tick(resumed, SILENT, 500);
        assertEquals(saved.landingRow() + 0.5F, saved.cellY(), 0.001F);
        assertTrue(saved.ticksLeft() < PvzceConstants.CARD_DROP_LIFETIME_TICKS);
        saved.remove();
        tick(resumed, SILENT, 120);
        assertTrue(resumed.entities().stream().noneMatch(CardDropEntity.class::isInstance),
                "a consumed capped packet must not be drawn again after reloading");
    }

    private static boolean buy(LevelServer level, String name, int x, int row) {
        for (var slot : level.slotInfos()) {
            if (slot.defId().equals("pvzce:" + name)) {
                return level.placePlant(SILENT, slot.index(), x, row);
            }
        }
        throw new AssertionError("missing card " + name);
    }

    @Test
    void shippedIceStorageAndBowlingFinishUsingOnlyTheirConveyorCards() {
        for (String name : List.of("ice_storage", "wallnut_bowling_2")) {
            LevelServer level = new LevelServer(shipped(name), 20261002);
            int used = 0;
            long peak = 0;
            for (int t = 0; t < 120000 && GameStateS2C.RUNNING.equals(level.gameState()); t++) {
                tick(level, SILENT, 1);
                peak = Math.max(peak, level.aliveZombieCount());
                if (t % 15 != 0) continue;
                for (var slot : level.slotInfos()) {
                    if (name.equals("wallnut_bowling_2")) {
                        var target = level.entities().stream().filter(e -> e instanceof ZombieEntity z
                                        && z.isAlive() && z.cellX() < 8.8F)
                                .map(ZombieEntity.class::cast)
                                .min(java.util.Comparator.comparingDouble(ZombieEntity::cellX)).orElse(null);
                        if (target != null && level.placePlant(SILENT, slot.index(), 2, target.gridY())) used++;
                    } else {
                        String card = slot.defId();
                        List<Integer> rows = new ArrayList<>(List.of(0, 1, 2, 3, 4));
                        rows.sort(java.util.Comparator.comparingLong(row -> level.entities().stream()
                                .filter(e -> e instanceof PlantEntity p && !p.isRemoved()
                                        && p.gridY() == row && p.defId().toString().equals(card)).count()));
                        boolean placed = false;
                        for (int row : rows) {
                            for (int x : card.endsWith("wall_nut") ? new int[]{5, 4} : new int[]{1, 0, 2}) {
                                if (level.placePlant(SILENT, slot.index(), x, row)) {
                                    used++;
                                    placed = true;
                                    break;
                                }
                            }
                            if (placed) break;
                        }
                    }
                    // The bar is rebuilt after a placement: read new indices on the next action.
                    break;
                }
            }
            System.out.printf("[conveyor] level=%s state=%s waves=%d seconds=%.1f peak=%d cardsUsed=%d%n",
                    name, level.gameState(), level.currentWave(), level.tickCount() / 60.0, peak, used);
            assertEquals(GameStateS2C.WON, level.gameState(), name + " completes with its own dealt cards");
        }
    }

    @Test
    void whackMalletMatchesTheOneTwoThreeSwingHintAndHitsOneZombie() {
        LevelServer level = new LevelServer(shipped("whack_a_zombie"));
        var tool = com.pvzce.common.level.mechanic.ToolMechanic.defaultTool(level.def()).orElseThrow();
        String[] ids = {"basic_zombie", "conehead_zombie", "buckethead_zombie"};
        for (int type = 0; type < ids.length; type++) {
            ZombieEntity zombie = level.spawnZombie(PvzceIds.id(ids[type]),
                    level.team(PvzceIds.ZOMBIE_TEAM), 4.5F, 0);
            level.flushPending(SILENT);
            for (int swing = 1; swing <= type + 1; swing++) {
                assertTrue(level.useGrantedTool(SILENT, tool, 4, 0));
                assertEquals(swing < type + 1, zombie.isAlive(), ids[type] + " swing " + swing);
            }
        }
        ZombieEntity first = level.spawnZombie(PvzceIds.id("basic_zombie"),
                level.team(PvzceIds.ZOMBIE_TEAM), 4.5F, 0);
        ZombieEntity second = level.spawnZombie(PvzceIds.id("basic_zombie"),
                level.team(PvzceIds.ZOMBIE_TEAM), 4.7F, 0);
        level.flushPending(SILENT);
        assertTrue(level.useGrantedTool(SILENT, tool, 4, 0));
        assertFalse(first.isAlive());
        assertTrue(second.isAlive(), "overlapping zombies require separate clicks");
    }

    @Test
    void shippedWhackRunsAllTwelveWavesAndItsFinalGraveBurst() {
        LevelServer level = new LevelServer(shipped("whack_a_zombie"), 20261002);
        var tool = com.pvzce.common.level.mechanic.LevelMechanics.dataOf(level.def(),
                PvzceIds.MECHANIC_TOOL, ToolData.class).orElseThrow();
        java.util.Set<Identifier> seen = new java.util.HashSet<>();
        int swings = 0;
        for (int t = 0; t < 30000 && GameStateS2C.RUNNING.equals(level.gameState()); t++) {
            tick(level, SILENT, 1);
            for (var entity : level.entities()) {
                if (entity instanceof ZombieEntity z && z.isAlive()) {
                    seen.add(z.defId());
                    if (level.useGrantedTool(SILENT, tool, z.gridX(), z.gridY())) swings++;
                }
            }
        }
        System.out.printf("[whack] state=%s waves=%d seconds=%.1f swings=%d types=%s%n",
                level.gameState(), level.currentWave(), level.tickCount() / 60.0, swings, seen);
        assertEquals(GameStateS2C.WON, level.gameState());
        assertEquals(12, level.currentWave());
        assertEquals(java.util.Set.of(PvzceIds.id("basic_zombie"), PvzceIds.id("conehead_zombie"),
                PvzceIds.id("buckethead_zombie")), seen);
        assertTrue(level.save().getCompound("GraveSpawner").getInt("FinalBurst") != 0);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {17, 81, 20261002})
    void shippedSleepDeprivationFinishesWithPaidCoffeeAndSleepingMushrooms(long seed) {
        LevelServer level = new LevelServer(shipped("sleep_deprivation"), seed);
        int purchases = 0;
        for (int t = 0; t < 36000 && GameStateS2C.RUNNING.equals(level.gameState()); t++) {
            tick(level, SILENT, 1);
            for (var entity : level.entities()) {
                if (entity instanceof com.pvzce.server.entity.ResourceDropEntity drop && !drop.isRemoved())
                    level.collectResource(SILENT, drop.id());
            }
            if (t % 15 != 0) continue;
            // Five income mushrooms first, then three attackers per lane, waking every purchase.
            for (int row : new int[]{0, 1, 2, 3, 4}) {
                if (level.plantAt(0, row) == null && buy(level, "sun_shroom", 0, row)) purchases++;
            }
            List<PlantEntity> sleeping = level.entities().stream()
                    .filter(e -> e instanceof PlantEntity p && !p.isRemoved() && p.isAsleep(level) && p.gridX() < 6)
                    .map(PlantEntity.class::cast).sorted(java.util.Comparator.comparingDouble(p -> {
                        if (p.defId().equals(PvzceIds.id("sun_shroom"))) return -100;
                        long awake = level.entities().stream().filter(e -> e instanceof PlantEntity other
                                && !other.isRemoved() && other.gridY() == p.gridY() && !other.isAsleep(level)
                                && other.defId().equals(PvzceIds.id("puff_shroom"))).count();
                        double nearest = level.entities().stream().filter(e -> e instanceof ZombieEntity z
                                        && z.isAlive() && z.gridY() == p.gridY())
                                .mapToDouble(e -> e.cellX()).min().orElse(10);
                        return awake * 20 + nearest - p.cellX() * 0.01;
                    })).toList();
            boolean woke = false;
            for (PlantEntity p : sleeping) {
                if (buy(level, "coffee_bean", p.gridX(), p.gridY())) {
                    purchases++;
                    woke = true;
                    break;
                }
            }
            // Sleeping, free puffs in front buy time without spending the coffee budget.
            for (int row = 0; row < 5; row++) {
                if (level.plantAt(6, row) == null && buy(level, "puff_shroom", 6, row)) purchases++;
            }
            if (woke) continue;
            for (int x : new int[]{4, 3, 2}) {
                for (int row = 0; row < 5; row++) {
                    if (level.plantAt(x, row) == null && buy(level, "puff_shroom", x, row)) purchases++;
                }
            }
        }
        if (!GameStateS2C.WON.equals(level.gameState())) {
            for (var entity : level.entities()) {
                if (entity instanceof ZombieEntity z && z.isAlive())
                    System.out.printf("[sleep-zombie] %s row=%d x=%.2f health=%d%n", z.defId(), z.gridY(), z.cellX(), z.health());
                if (entity instanceof PlantEntity p && !p.isRemoved())
                    System.out.printf("[sleep-plant] %s row=%d x=%.2f asleep=%s%n", p.defId(), p.gridY(), p.cellX(), p.isAsleep(level));
            }
        }
        System.out.printf("[sleep] state=%s waves=%d seconds=%.1f purchases=%d remaining=%d%n",
                level.gameState(), level.currentWave(), level.tickCount() / 60.0, purchases, sun(level));
        assertEquals(GameStateS2C.WON, level.gameState());
    }

    @Test
    void shippedLastStandCompletesFiveFlagsWithAPaidPoolDefence() {
        List<Identifier> cards = List.of("sun", "shovel", "lily_pad", "repeater", "torchwood", "tall_nut",
                "spikeweed", "cherry_bomb", "jalapeno", "snow_pea").stream().map(PvzceIds::id).toList();
        LevelServer level = new LevelServer(shipped("last_stand"), cards, 20261002);
        for (int row = 0; row < 6; row++) {
            if (row == 2 || row == 3) {
                for (int x : new int[]{0, 1, 2, 4}) assertTrue(buy(level, "lily_pad", x, row));
            } else assertTrue(buy(level, "spikeweed", 6, row));
            assertTrue(buy(level, "repeater", 0, row));
            assertTrue(buy(level, "repeater", 1, row));
            assertTrue(buy(level, "torchwood", 2, row));
            assertTrue(buy(level, "tall_nut", 4, row));
        }
        int openingSun = sun(level);
        int stages = 1;
        long peak = 0;
        level.beginWaves();
        for (int t = 0; t < 90000 && GameStateS2C.RUNNING.equals(level.gameState()); t++) {
            tick(level, SILENT, 1);
            peak = Math.max(peak, level.aliveZombieCount());
            for (int row = 0; row < 6; row++) {
                final int lane = row;
                boolean danger = level.entities().stream().anyMatch(entity -> entity instanceof ZombieEntity z
                        && z.isAlive() && z.gridY() == lane && z.cellX() < 3.8F);
                if (danger) buy(level, "jalapeno", 3, row);
            }
            if (level.isPreparing()) {
                stages++;
                // Repair with the actual stage supply; no free plants or injected money.
                for (int row = 0; row < 6; row++) {
                    PlantEntity wall = level.plantAt(4, row);
                    if (wall == null || !wall.defId().equals(PvzceIds.id("tall_nut"))) {
                        if (wall == null && (row == 2 || row == 3)) buy(level, "lily_pad", 4, row);
                        buy(level, "tall_nut", 4, row);
                    }
                }
                level.beginWaves();
            }
        }
        if (!GameStateS2C.WON.equals(level.gameState())) {
            for (var entity : level.entities()) if (entity instanceof ZombieEntity z && z.isAlive())
                System.out.printf("[survivor] %s row=%d x=%.2f health=%d%n", z.defId(), z.gridY(), z.cellX(), z.health());
        }
        System.out.printf("[last-stand] state=%s waves=%d stages=%d seconds=%.1f peak=%d openingSpent=%d remaining=%d%n",
                level.gameState(), level.currentWave(), stages, level.tickCount() / 60.0,
                peak, 5000 - openingSun, sun(level));
        assertEquals(5, stages);
        assertEquals(GameStateS2C.WON, level.gameState(), "the paid defence completes the shipped five flags");
    }
}
