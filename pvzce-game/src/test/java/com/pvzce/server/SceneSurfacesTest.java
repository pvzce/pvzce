package com.pvzce.server;

import com.mojang.serialization.JsonOps;
import com.pvzce.api.content.*;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.renderer.LevelStage;
import com.pvzce.client.renderer.PvzceCamera;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.SceneBoard;
import com.pvzce.common.level.WorldPosition;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.EffectEventS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.*;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Roof, pool and overlapping deck simulations exercise the same surface contract. */
class SceneSurfacesTest {
    private static final String BRIDGE = "pvzce:bridge";
    @BeforeAll static void load() throws Exception { TestContent.loadBuiltInContentAndTags(); }
    private static LevelDef bridgeDef() {
        var base = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_9"));
        var deck = new SceneSurfaceDef(Identifier.parse(BRIDGE), "桥面", SurfaceProfile.flat(1.4F), 0.2F,
                Map.of(PvzceIds.GRASS, java.util.stream.IntStream.range(0, 45)
                        .mapToObj(i -> (i % 9) + "," + (i / 9)).toList()));
        return TestLevels.copy(base).mechanics(List.of()).waves(List.of()).initialEntities(List.of()).initialSun(10000)
                .slots(List.of(PvzceIds.id("shovel"))).surfaces(List.of(deck)).build();
    }
    private static PlantEntity plant(LevelServer level, String id, int x, int y, String surface) {
        return level.spawnPlant(BuiltInRegistries.PLANTS.get(PvzceIds.id(id)), level.team(PvzceIds.PLANT_TEAM), x, y, surface);
    }
    private static ZombieEntity zombie(LevelServer level, float x, int y, String surface) {
        return level.spawnZombie(PvzceIds.id("basic_zombie"), level.team(PvzceIds.ZOMBIE_TEAM), x, y, 1F, surface);
    }
    private static void ticks(LevelServer level, int count, List<PvzcePacket> packets) {
        level.flushPending(packets::add);
        for (int i = 0; i < count; i++) level.tick(packets::add);
    }
    @Test void overlappingCellsPlantAndShovelIndependentlyAndRejectMissingSurfaces() {
        var level = new LevelServer(bridgeDef(), 1L);
        var ground = plant(level, "pea_shooter", 2, 2, SceneBoard.DEFAULT_SURFACE);
        level.flushPending(p -> {});
        var def = ground.def();
        assertTrue(level.canPlacePlant(def, 2, 2, BRIDGE));
        assertFalse(level.canPlacePlant(def, 2, 2, "pvzce:missing"));
        var upper = plant(level, "pea_shooter", 2, 2, BRIDGE);
        level.flushPending(p -> {});
        assertEquals(0F, ground.height()); assertEquals(1.4F, upper.height());
        assertSame(ground, level.plantAt(2, 2)); assertSame(upper, level.plantAt(2, 2, BRIDGE));
        var packets = new ArrayList<PvzcePacket>();
        assertTrue(level.useTool(packets::add, 0, 2, 2, BRIDGE));
        assertTrue(upper.isRemoved()); assertFalse(ground.isRemoved());
        var effect = packets.stream().filter(EffectEventS2C.class::isInstance).map(EffectEventS2C.class::cast)
                .filter(e -> !e.particle().isEmpty()).findFirst().orElseThrow();
        assertEquals(BRIDGE, effect.surfaceId()); assertEquals(2.5F, effect.y()); assertEquals(1.4F, effect.elevation());
    }
    @Test void zombieAboveCannotBitePlantBelow() {
        var level = new LevelServer(bridgeDef(), 2L);
        var lower = plant(level, "wall_nut", 2, 2, SceneBoard.DEFAULT_SURFACE);
        var walker = zombie(level, 2.6F, 2, BRIDGE);
        ticks(level, 120, new ArrayList<>());
        assertEquals(lower.maxHealth(), lower.health());
        assertTrue(walker.cellX() < 2.5F);
    }
    @Test void explosionAndRowFireRespectDeckAndChosenPath() {
        var level = new LevelServer(bridgeDef(), 3L);
        var below = zombie(level, 4.5F, 2, SceneBoard.DEFAULT_SURFACE);
        var above = zombie(level, 4.5F, 2, BRIDGE);
        level.flushPending(p -> {});
        var ash = ZombieEntity.damageType(PvzceIds.DAMAGE_ASH);
        level.damageArea(ash, new WorldPosition(4.5F, 2.5F, 1.4F), 3F, 50, level.team(PvzceIds.PLANT_TEAM), true);
        assertEquals(below.maxHealth(), below.health()); assertEquals(above.maxHealth() - 50, above.health());
        level.damageRow(ash, 2, 40, level.team(PvzceIds.PLANT_TEAM), SceneBoard.DEFAULT_SURFACE);
        assertEquals(below.maxHealth() - 40, below.health()); assertEquals(above.maxHealth() - 50, above.health());
    }
    @Test void bridgeInterceptsVerticalAndFastShotsButAllowsTravelUnderIt() {
        var board = SceneBoard.forLevel(bridgeDef());
        assertTrue(board.obstructed(new WorldPosition(4.5F, 2.5F, 3F), new WorldPosition(4.5F, 2.5F, .4F)));
        assertTrue(board.obstructed(new WorldPosition(4.5F, 2.5F, .4F), new WorldPosition(4.5F, 2.5F, 3F)));
        assertFalse(board.obstructed(new WorldPosition(.5F, 2.5F, .3F), new WorldPosition(7.5F, 2.5F, .3F)));
        var contact = board.firstObstruction(new WorldPosition(4.5F, 2.5F, 3F), new WorldPosition(4.5F, 2.5F, .4F)).orElseThrow();
        assertEquals(1.39F, contact.elevation(), .001F);
        var level = new LevelServer(bridgeDef(), 4L);
        var source = plant(level, "melon_pult", 0, 2, BRIDGE);
        var target = zombie(level, 6.5F, 2, SceneBoard.DEFAULT_SURFACE);
        target.applyStatus(ZombieStatus.IMMOBILIZED, 600, 1F);
        level.spawnArcProjectile(new ProjectileRef(PvzceIds.id("melon"), 100, 1, 0, false, 0), source.cellX(), source.cellY(), source, target);
        ticks(level, 360, new ArrayList<>());
        assertEquals(target.maxHealth(), target.health(), "lob must explode on the bridge before reaching the protected zombie");
    }
    @Test void roofCoverAndReadbackKeepFoundationAndParticleElevation() {
        var def = TestLevels.copy(BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/5_9")))
                .waves(List.of()).initialEntities(List.of()).build();
        var level = new LevelServer(def, 5L);
        float height = level.surfaceHeight(SceneBoard.DEFAULT_SURFACE, 2.5F, 2.5F);
        level.setScene(2, 2, PvzceIds.ICE);
        assertEquals(height, level.surfaceHeight(SceneBoard.DEFAULT_SURFACE, 2.5F, 2.5F));
        var reloaded = new LevelServer(def, 6L); reloaded.restore(level.save());
        reloaded.meltIceRow(2);
        assertEquals(PvzceIds.ROOF_SLOPE, reloaded.sceneAt(2, 2).id());
        assertEquals(height, reloaded.surfaceHeight(SceneBoard.DEFAULT_SURFACE, 2.5F, 2.5F));
        var packets = new ArrayList<PvzcePacket>();
        com.pvzce.common.capability.plant.ExplosiveCapability.fireRow(reloaded, 2, 0, PvzceIds.DAMAGE_ASH,
                true, null, reloaded.team(PvzceIds.PLANT_TEAM));
        // A real typed placement effect proves no client-only roof correction is required.
        plant(reloaded, "flower_pot", 2, 2, SceneBoard.DEFAULT_SURFACE);
        ticks(reloaded, 1, packets);
        assertEquals(height, reloaded.plantAt(2, 2).spawnPacket().height());
    }
    @Test void poolDropsLandAtWaterSurfaceAndCameraPicksThatSameCell() {
        var def = TestLevels.copy(BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/3_1")))
                .waves(List.of()).initialEntities(List.of()).build();
        var level = new LevelServer(def, 7L);
        var packets = new ArrayList<PvzcePacket>();
        plant(level, "lily_pad", 3, 2, SceneBoard.DEFAULT_SURFACE);
        level.spawnProducedResource(PvzceIds.SUN, 25, 3.5F, 2.5F, level.team(PvzceIds.PLANT_TEAM), 1F, SceneBoard.DEFAULT_SURFACE);
        ticks(level, 125, packets);
        float water = level.surfaceHeight(SceneBoard.DEFAULT_SURFACE, 3.5F, 2.5F);
        assertTrue(water < 0F);
        var drop = level.entities().stream().filter(ResourceDropEntity.class::isInstance).map(ResourceDropEntity.class::cast).findFirst().orElseThrow();
        assertTrue(drop.landed()); assertEquals(water, drop.height(), .001F);
        var camera = new PvzceCamera(1920, 1080, 9, 6, LevelStage.POOL, 0F).scene(level.sceneBoard(), SceneBoard.DEFAULT_SURFACE);
        double px = camera.screenX(3.5F), py = 1080 - camera.screenY(2.5F + water);
        assertEquals(3, camera.cellX(px, py)); assertEquals(2, camera.cellY(px, py));
    }
    @Test void oldPoolSaveMigratesHeightWithoutChangingTheOriginalTag() {
        var def = TestLevels.copy(BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/3_1")))
                .waves(List.of()).initialEntities(List.of()).build();
        var original = new LevelServer(def, 10L);
        plant(original, "lily_pad", 3, 2, SceneBoard.DEFAULT_SURFACE);
        original.flushPending(p -> {});
        var save = original.save();
        var savedPlant = save.getList("Entities").getCompound(0);
        savedPlant.entries().remove("surface");
        savedPlant.putFloat("height", 0F);
        var restored = new LevelServer(def, 11L);
        restored.restore(save);
        assertEquals(restored.surfaceHeight(SceneBoard.DEFAULT_SURFACE, 3.5F, 2.5F),
                restored.plantAt(3, 2).height(), .001F);
        assertFalse(savedPlant.contains("surface"));
        assertEquals(0F, savedPlant.getFloat("height"));
    }
    @Test void thinAndZeroThicknessDecksStillBlockCrossingShots() {
        for (float thickness : new float[]{0F, .005F}) {
            var base = bridgeDef();
            var deck = base.surfaces().getFirst();
            var board = SceneBoard.forLevel(TestLevels.copy(base).surfaces(List.of(
                    new SceneSurfaceDef(deck.id(), deck.name(), deck.profile(), thickness, deck.scene()))).build());
            assertTrue(board.obstructed(new WorldPosition(2.5F, 2.5F, 3F), new WorldPosition(2.5F, 2.5F, .4F)));
            assertFalse(board.obstructed(new WorldPosition(.5F, 2.5F, .3F), new WorldPosition(7.5F, 2.5F, .3F)));
        }
    }
    @Test void sparseBridgeSortsAboveRoofEvenWhenItsCentreCellIsMissing() {
        var base = bridgeDef();
        var sparse = new SceneSurfaceDef(Identifier.parse(BRIDGE), "桥面", SurfaceProfile.flat(1.4F), .2F,
                Map.of(PvzceIds.GRASS, List.of("0,0")));
        var board = SceneBoard.forLevel(TestLevels.copy(base)
                .scene(Map.of(PvzceIds.ROOF_SLOPE, List.of("4,2"))).surfaces(List.of(sparse)).build());
        assertEquals(List.of(SceneBoard.DEFAULT_SURFACE, BRIDGE), board.surfacesBottomFirst());
    }
    @Test void authoredBridgeIceMeltsBackToItsDeclaredFoundation() {
        var base = bridgeDef();
        var cells = new LinkedHashMap<Identifier, List<String>>();
        cells.put(PvzceIds.GRASS, List.of("2,2"));
        cells.put(PvzceIds.ICE, List.of("2,2"));
        var deck = new SceneSurfaceDef(Identifier.parse(BRIDGE), "桥面", SurfaceProfile.flat(1.4F), .2F, cells);
        var level = new LevelServer(TestLevels.copy(base).surfaces(List.of(deck)).build(), 15L);
        assertEquals(PvzceIds.ICE, level.sceneAt(2, 2, BRIDGE).id());
        level.meltIceRow(2, BRIDGE);
        assertEquals(PvzceIds.GRASS, level.sceneAt(2, 2, BRIDGE).id());
        assertEquals(1.4F, level.surfaceHeight(BRIDGE, 2.5F, 2.5F));
        assertEquals(0F, level.surfaceHeight(SceneBoard.DEFAULT_SURFACE, 2.5F, 2.5F));
    }
    @Test void flatShotHitsOnlyBodiesAtItsActualElevation() {
        var level = new LevelServer(bridgeDef(), 12L);
        var source = plant(level, "pea_shooter", 0, 2, BRIDGE);
        var lower = zombie(level, 3.5F, 2, SceneBoard.DEFAULT_SURFACE);
        var upper = zombie(level, 3.5F, 2, BRIDGE);
        lower.applyStatus(ZombieStatus.IMMOBILIZED, 300, 1F);
        upper.applyStatus(ZombieStatus.IMMOBILIZED, 300, 1F);
        level.spawnProjectile(new ProjectileRef(PvzceIds.id("pea"), 20, 1, 0, false, 0),
                source.cellX(), source.cellY(), source);
        ticks(level, 180, new ArrayList<>());
        assertEquals(lower.maxHealth(), lower.health());
        assertTrue(upper.health() < upper.maxHealth());
    }
    @Test void magneticTransferKeepsElevationAndCannotPullThroughADeck() {
        var level = new LevelServer(bridgeDef(), 13L);
        var magnet = plant(level, "magnet_shroom", 2, 2, BRIDGE); magnet.wake();
        var below = level.spawnZombie(PvzceIds.id("buckethead_zombie"), level.team(PvzceIds.ZOMBIE_TEAM),
                3.5F, 2, 1F, SceneBoard.DEFAULT_SURFACE);
        var above = level.spawnZombie(PvzceIds.id("buckethead_zombie"), level.team(PvzceIds.ZOMBIE_TEAM),
                3.5F, 2, 1F, BRIDGE);
        var packets = new ArrayList<PvzcePacket>(); ticks(level, 2, packets);
        assertNotNull(below.magneticItem()); assertNull(above.magneticItem());
        var transfer = packets.stream().filter(com.pvzce.common.network.packet.MagnetItemS2C.class::isInstance)
                .map(com.pvzce.common.network.packet.MagnetItemS2C.class::cast).findFirst().orElseThrow();
        assertEquals(1.4F, transfer.elevation(), .001F);
        var reloaded = new LevelServer(bridgeDef(), 14L); reloaded.restore(level.save());
        var restored = reloaded.plantAt(2, 2, BRIDGE);
        var capability = restored.capability(com.pvzce.common.capability.plant.MagnetCapability.class);
        assertEquals(1.4F, capability.itemSnapshot(restored, reloaded.tickCount(), reloaded).elevation(), .001F);
    }
    @Test void codecPacketsAndSaveRetainUpperSurfaceOccupants() {
        var def = bridgeDef();
        var encoded = LevelDef.CODEC.encodeStart(JsonOps.INSTANCE, def).getOrThrow();
        var decoded = LevelDef.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(def.surfaces(), decoded.surfaces());
        var level = new LevelServer(decoded, 8L);
        var upper = plant(level, "pea_shooter", 2, 2, BRIDGE); level.flushPending(p -> {});
        assertEquals(BRIDGE, upper.spawnPacket().surfaceId()); assertEquals(BRIDGE, upper.updatePacket().surfaceId());
        var reloaded = new LevelServer(decoded, 9L); reloaded.restore(level.save());
        assertEquals(1.4F, reloaded.plantAt(2, 2, BRIDGE).height());
        var mirror = new SceneBoard(9, 5);
        for (var cell : level.sceneBoard().snapshot()) mirror.apply(cell);
        assertEquals(1.4F, mirror.elevationAt(BRIDGE, 2.5F, 2.5F));
        var camera = new PvzceCamera(1920, 1080, 9, 5, LevelStage.YARD, 0F).scene(mirror, BRIDGE);
        double px = camera.screenX(2.5F), py = 1080 - camera.cellScreenY(2, 2);
        assertEquals(2, camera.cellY(px, py)); assertTrue(camera.inBoard(px, py));
    }
}
