package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.RandomPlantsData;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.entity.EntityKind;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.PlantPlacement;
import com.pvzce.common.level.mechanic.EndlessMechanic;
import com.pvzce.common.level.mechanic.RandomPlantsMechanic;
import com.pvzce.common.level.RandomPlantsState;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.NbtIo;
import com.pvzce.common.network.packet.CommandC2S;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.network.packet.PlayLevelC2S;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.RoundClearS2C;
import com.pvzce.common.network.packet.RoundSyncS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;
import com.pvzce.testutil.ServerHarness;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RandomPlantsEndlessTest {
    @BeforeAll static void load() throws Exception { TestContent.loadBuiltInContentAndTags(); }
    private static LevelDef endless() {
        return BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/minigame/random_plants_belt_endless"));
    }

    /** Deterministic ability fixtures; the shipped level must still roll fresh seeds. */
    private static LevelDef fixedEndless(long seed) {
        var def = endless();
        var data = RandomPlantsMechanic.data(def);
        return TestLevels.copy(def).mechanics(def.mechanics().stream().map(m ->
                m.type().equals(PvzceIds.MECHANIC_RANDOM_PLANTS) ? new TypedMechanic(m.type(),
                        new RandomPlantsData(seed, data.upgradesWithoutBases(), data.graveBustersAnywhere(),
                                data.carriersOnWater(), data.abilityPlacement())) : m).toList()).build();
    }

    @Test void thePoolContinuesWithSurvivorsAndKeepsItsBeltAfterResume() {
        var def = endless();
        assertNotNull(def);
        assertTrue(def.waves().isEmpty());
        LevelServer level = new LevelServer(def);
        AtomicInteger choices = new AtomicInteger();
        List<RoundSyncS2C> syncs = new ArrayList<>();
        LevelServer.ServerBridge bridge = packet -> {
            if (packet instanceof RoundClearS2C) choices.incrementAndGet();
            if (packet instanceof RoundSyncS2C sync) syncs.add(sync);
        };
        for (int t = 0; t < 20000 && level.round() < 2; t++) {
            parkEnemies(level);
            level.tick(bridge);
        }
        assertEquals(2, level.round(), "survivors must not stop the endless conveyor");
        assertTrue(level.entities().stream().anyMatch(e -> e instanceof ZombieEntity && !e.isRemoved()));
        assertEquals(0, choices.get(), "no card chooser is sent between stages");
        assertFalse(level.isRoundClearPending());
        assertEquals(GameStateS2C.RUNNING, level.gameState());
        assertTrue(syncs.stream().anyMatch(s -> s.round() == 2 && !s.roundClearPending()));
        var cards = level.plantPlayer().slots().stream().map(s -> s.defId()).toList();
        LevelServer resumed = new LevelServer(def);
        resumed.restore(level.save());
        resumed.flushPending();
        assertEquals(RandomPlantsMechanic.seed(level), RandomPlantsMechanic.seed(resumed));
        assertEquals(cards, resumed.plantPlayer().slots().stream().map(s -> s.defId()).toList());
        assertEquals(level.cumulativeWaves(), resumed.cumulativeWaves());
        for (int t = 0; t < 30000 && resumed.round() < 3; t++) {
            parkEnemies(resumed);
            resumed.tick(bridge);
        }
        assertEquals(3, resumed.round());
        assertEquals(0, choices.get());
        assertEquals(GameStateS2C.RUNNING, resumed.gameState());
        var schedule = EndlessMechanic.scheduleOf(def);
        assertTrue(schedule.statGrowth().healthMultiplier(200) > schedule.statGrowth().healthMultiplier(100),
                "health must keep growing after the count and spawn interval reach their limits");
        // Breach handling must still accept a loss in continuous mode.
        resumed.zombieReachedLeft(resumed.spawnZombie(PvzceIds.id("basic_zombie"), -1F, 0));
        resumed.tick(bridge);
        assertEquals(GameStateS2C.LOST, resumed.gameState());
    }

    private static void parkEnemies(LevelServer level) {
        for (var entity : level.entities()) if (entity instanceof ZombieEntity zombie) zombie.setCellX(8F);
    }

    @Test void originalIdentityDoesNotRestrictTheShuffledAbilityRoles() {
        LevelServer level = new LevelServer(fixedEndless(RandomPlantsMechanic.data(endless()).seed()));
        var recipes = RandomPlantsMechanic.recipes(level);
        for (var original : BuiltInRegistries.PLANTS) {
            var actual = RandomPlantsMechanic.plant(level, original);
            assertTrue(level.canPlacePlant(original, 3, 0), original.id() + " can stand on grass");
            assertEquals(PlantPlacement.isCarrier(actual), level.canPlacePlant(original, 3, 2),
                    original.id() + " needs an actual carrier ability to stand on bare water");
            assertFalse(level.canPlacePlant(original, 9, 0));
        }
        var carrier = recipes.values().stream().map(r -> RandomPlantsMechanic.plant(level, r.plant()))
                .filter(PlantPlacement::isCarrier)
                .filter(p -> p.resolvedCapabilities().stream().noneMatch(c -> c.value().consumesOnPlace()))
                .findFirst().orElseThrow();
        level.spawnPlant(carrier, level.plantPlayer().team(), 3, 2);
        var host = recipes.values().stream().map(r -> RandomPlantsMechanic.plant(level, r.plant()))
                .filter(p -> p.placement().layer() == 1)
                .filter(p -> p.resolvedCapabilities().stream().noneMatch(c -> c.value().consumesOnPlace()))
                .findFirst().orElseThrow();
        assertTrue(level.canPlacePlant(host, 3, 2));
        level.spawnPlant(host, level.plantPlayer().team(), 3, 2);
        for (var recipe : recipes.values()) {
            var actual = RandomPlantsMechanic.plant(level, recipe.plant());
            if (actual.placement().layer() == 2)
                assertTrue(level.canPlacePlant(actual, 3, 2), actual.id() + " can overlay its water host");
        }
        assertFalse(level.canPlacePlant(host, 3, 2), "two ordinary ground plants still conflict");
        var sleepingShell = level.spawnPlant(BuiltInRegistries.PLANTS.get(PvzceIds.id("hypno_shroom")),
                level.plantPlayer().team(), 3, 2);
        assertTrue(sleepingShell.isAsleep(level));
        var wake = BuiltInRegistries.PLANTS.get(PvzceIds.id("cabbage_pult"));
        assertTrue(level.canPlacePlant(wake, 3, 2));
        level.spawnPlant(wake, level.plantPlayer().team(), 3, 2);
        assertFalse(sleepingShell.isAsleep(level), "shuffled wake targets the top host, including a shell");

        assertFalse(level.canPlacePlant(BuiltInRegistries.PLANTS.get(PvzceIds.id("cob_cannon")), 8, 0));
    }

    @Test void restartingRerollsTheWireStateButContinuingKeepsIt(@TempDir Path dir) throws Exception {
        String id = endless().id().toString();
        String world = "randomseeds";
        RandomPlantsState saved;
        try (var server = ServerHarness.createWithWorld(dir, world, true)) {
            server.send(new PlayLevelC2S(id, world, true, List.of()));
            var first = awaitRecipes(server);
            server.clear();
            // RestartLevelC2S is used by the level list's fresh-start action.
            server.requestLevel(id, world, true);
            var restarted = awaitRecipes(server);
            assertNotEquals(first.seed(), restarted.seed());
            assertEquals(RandomPlantsMechanic.seed(server.server().level()), restarted.seed());
            server.clear();
            // PlayLevelC2S is used by the pause menu's restart action.
            server.send(new PlayLevelC2S(id, world, true, List.of()));
            saved = awaitRecipes(server);
            assertNotEquals(restarted.seed(), saved.seed());
            server.clear();
            server.requestLevel(id, world, false);
            assertEquals(saved, awaitRecipes(server), "continuing the running level keeps every recipe");
            server.send(new CommandC2S("/save"));
            server.waitForFile(dir.resolve("saves/" + world
                    + "/levels/70767a6365__yard%2Fminigame%2Frandom_plants_belt_endless/level.dat"), 5_000);
        }
        try (var server = ServerHarness.create(dir)) {
            server.requestLevel(id, world, false);
            assertEquals(saved, awaitRecipes(server), "disk continuation must send the same seed and recipes");
        }
    }

    private static RandomPlantsState awaitRecipes(ServerHarness server) throws Exception {
        server.waitFor(p -> p instanceof MechanicSyncS2C s
                && s.mechanic().equals(PvzceIds.MECHANIC_RANDOM_PLANTS), 5_000);
        var packet = server.packets().stream().filter(p -> p instanceof MechanicSyncS2C s
                && s.mechanic().equals(PvzceIds.MECHANIC_RANDOM_PLANTS))
                .map(p -> (MechanicSyncS2C) p).reduce((a, b) -> b).orElseThrow();
        var buf = packet.payloadBuffer();
        try { return RandomPlantsState.CODEC.decode(buf); }
        finally { buf.delegate().release(); }
    }

    @Test void savedSeedsRestoreBeforePlantsAndLegacySavesKeepTheirAbilities(@TempDir Path dir) throws Exception {
        long authored = RandomPlantsMechanic.data(endless()).seed();
        for (long seed : new long[]{authored, (1L << 60) + 123, Long.MIN_VALUE + 123}) {
            LevelServer before = new LevelServer(fixedEndless(seed));
            before.spawnPlant(BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter")),
                    before.plantPlayer().team(), 1, 0);
            for (int t = 0; t < 180; t++) before.tick(p -> {});
            CompoundTag tag = before.save();
            if (seed != authored) {
                CompoundTag state = new CompoundTag();
                state.putLong("Seed", seed);
                tag.put("RandomPlants", state);
            }
            // The authored case intentionally has no seed block, like saves predating random starts.
            Path file = dir.resolve("seed-" + seed + ".dat");
            NbtIo.writeCompressed(tag, file);
            LevelServer after = new LevelServer(endless());
            after.restore(NbtIo.readCompressed(file));
            assertEquals(seed, RandomPlantsMechanic.seed(after));
            var restoredRecipes = RandomPlantsMechanic.recipes(after);
            for (var entry : RandomPlantsMechanic.recipes(before).entrySet()) {
                var expected = entry.getValue();
                var actual = restoredRecipes.get(entry.getKey());
                assertEquals(PlantDef.CODEC.encodeStart(JsonOps.INSTANCE, expected.plant()).getOrThrow(),
                        PlantDef.CODEC.encodeStart(JsonOps.INSTANCE, actual.plant()).getOrThrow(), entry.getKey().toString());
                assertEquals(expected.shot(), actual.shot());
                assertEquals(expected.product(), actual.product());
                assertEquals(expected.launchSpeed(), actual.launchSpeed());
            }
            assertEquals(before.slotInfos(), after.slotInfos(), "restoring seed cannot replace the dealt cards or tools");
            var plant = after.entities().stream().filter(e -> e instanceof PlantEntity p
                    && p.defId().equals(PvzceIds.id("pea_shooter"))).map(e -> (PlantEntity) e).findFirst().orElseThrow();
            assertEquals(PlantDef.CODEC.encodeStart(JsonOps.INSTANCE,
                            RandomPlantsMechanic.plant(before, BuiltInRegistries.PLANTS.get(plant.defId()))).getOrThrow(),
                    PlantDef.CODEC.encodeStart(JsonOps.INSTANCE, plant.def()).getOrThrow());
            assertEquals(seed, after.save().getCompound("RandomPlants").getLong("Seed"));
        }
    }

    @Test void firedPlantsDealOnlyTheirOwnAttackDamageEvenAfterSave() {
        var def = TestLevels.withWaves(BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_1")), List.of());
        LevelServer level = new LevelServer(def);
        var source = new PlantEntity(BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter")),
                level.plantPlayer().team(), 0, 0);
        var enemy = level.spawnZombie(PvzceIds.id("basic_zombie"), 3F, 0);
        var plant = (PlantEntity) level.spawnRandomPayload(
                new RandomPlantsMechanic.Payload(EntityKind.PLANT, PvzceIds.id("pea_shooter")),
                source, 2F, 0.5F, 0F, 777, 1, 60F, 1F, 0F);
        level.flushPending();
        LevelServer resumed = new LevelServer(def);
        resumed.restore(level.save());
        resumed.flushPending();
        var restored = resumed.entities().stream().filter(e -> e instanceof PlantEntity)
                .map(e -> (PlantEntity) e).findFirst().orElseThrow();
        var target = resumed.entities().stream().filter(e -> e instanceof ZombieEntity)
                .map(e -> (ZombieEntity) e).findFirst().orElseThrow();
        int before = target.health();
        restored.tickLaunch(resumed);
        assertFalse(restored.launched());
        assertEquals(before, target.health(), "landing cannot inherit the emitting shot's 777 damage");
        assertTrue(restored.cellX() < target.cellX(), "a fast plant must land facing its target");
        for (int t = 0; t < 240 && target.health() == before; t++) {
            target.setCellX(3F);
            resumed.tick(packet -> {});
        }
        assertTrue(target.health() < before, "the landed peashooter fires its own pea");
        assertTrue(before - target.health() < 777);
        var wall = (PlantEntity) resumed.spawnRandomPayload(
                new RandomPlantsMechanic.Payload(EntityKind.PLANT, PvzceIds.id("wall_nut")),
                source, 2F, 0.5F, 0F, 777, 1, 60F, 1F, 0F);
        before = target.health();
        wall.tickLaunch(resumed);
        assertEquals(before, target.health(), "a non-attacking plant has no invented projectile damage");
    }
}
