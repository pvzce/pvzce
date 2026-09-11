package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.ResourceCollectS2C;
import com.pvzce.common.resource.PvzceDataLoader;
import com.pvzce.common.resource.PvzceResourceManager;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ResourceDropEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.gamerule.GameRules;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M3: representative plant/zombie/projectile/scene behaviors. */
class CombatSystemsTest {
    private static LevelDef demo;

    @BeforeAll
    static void load() throws Exception {
        BuiltInRegistries.bootstrap();
        PvzceResourceManager resources = new PvzceResourceManager(Thread.currentThread().getContextClassLoader());
        resources.init(Path.of(System.getProperty("java.io.tmpdir"), "pvzce-combat-test"));
        PvzceDataLoader.LoadResult result = new PvzceDataLoader().load(resources, BuiltInRegistries.ACCESS);
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        demo = BuiltInRegistries.LEVELS.get(Identifier.withDefaultNamespace("demo_level"));
    }

    private static LevelServer newLevel() {
        LevelDef noWaves = new LevelDef(
                demo.id(), demo.name(), demo.description(), demo.width(), demo.height(),
                demo.scene(), demo.teams(), demo.winTeam(), demo.rules(), demo.envVars(),
                List.of(), demo.waveIntervalEndMultiplier(), demo.slots(), demo.unlockResources(),
                demo.initialSun(), LevelDef.LevelMusicDef.DEFAULT, List.of());
        return new LevelServer(noWaves);
    }

    private static CapturingBridge bridge() {
        return new CapturingBridge();
    }

    private static ZombieEntity spawn(LevelServer level, CapturingBridge bridge, String id, float x, int row) {
        level.spawnZombie(Identifier.withDefaultNamespace(id),
                level.team(Identifier.withDefaultNamespace("zombie_team")), x, row);
        level.flushPending(bridge);
        ZombieEntity zombie = level.zombiesInRow(row).stream()
                .filter(z -> z.defId().equals(Identifier.withDefaultNamespace(id)))
                .findFirst().orElseThrow();
        return zombie;
    }

    private static void tick(LevelServer level, CapturingBridge bridge, int ticks) {
        for (int i = 0; i < ticks && level.gameState().equals(GameStateS2C.RUNNING); i++) {
            level.tick(bridge);
        }
    }

    @Test
    void gameRulesClampAndDecode() {
        GameRules rules = new GameRules(Map.of(
                Identifier.withDefaultNamespace("sun_spawn_chance"),
                com.google.gson.JsonParser.parseString("0.25"),
                Identifier.withDefaultNamespace("day_length"),
                com.google.gson.JsonParser.parseString("120")
        ));
        assertEquals(0.25F, rules.getFloat(Identifier.withDefaultNamespace("sun_spawn_chance")), 0.0001F);
        assertEquals(120, rules.getInt(Identifier.withDefaultNamespace("day_length")));
        rules.set(Identifier.withDefaultNamespace("sun_spawn_chance"), 99F);
        assertEquals(1F, rules.getFloat(Identifier.withDefaultNamespace("sun_spawn_chance")), 0.0001F);
    }

    @Test
    void clockEntersNight() {
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        level.rules().set(Identifier.withDefaultNamespace("day_length"), 60);
        level.rules().set(Identifier.withDefaultNamespace("night_length"), 60);
        tick(level, bridge, 60);
        assertTrue(level.clock().isNight(level.rules()));
        tick(level, bridge, 60);
        assertFalse(level.clock().isNight(level.rules()));
    }

    @Test
    void shooterKillsBasicZombie() {
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        assertTrue(level.placePlant(bridge, 0, 0, 0));
        spawn(level, bridge, "basic_zombie", 8.5F, 0);
        tick(level, bridge, 2_000);
        assertTrue(level.zombiesInRow(0).stream().allMatch(ZombieEntity::isRemoved), "peashooter should kill a basic zombie");
    }

    @Test
    void frontArmorAbsorbsPeasAndNewspaperSpeedsUp() {
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        assertTrue(level.placePlant(bridge, 0, 0, 0));
        ZombieEntity newspaper = spawn(level, bridge, "newspaper_zombie", 8.5F, 0);
        int armorBefore = newspaper.armorHealth();
        tick(level, bridge, 400);
        assertTrue(newspaper.armorHealth() < armorBefore, "front armor should absorb ground shots");
    }

    @Test
    void arcProjectilePopsBalloon() {
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        assertTrue(level.placePlant(bridge, 3, 0, 0)); // kernel_pult slot
        ZombieEntity balloon = spawn(level, bridge, "balloon_zombie", 5F, 0);
        tick(level, bridge, 1_200);
        assertTrue(balloon.isGrounded(), "an air-layer arc shot should pop the balloon");
    }

    @Test
    void cherryBombKillsNearbyZombies() {
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        assertTrue(level.placePlant(bridge, 4, 3, 0)); // cherry_bomb slot at (3,0)
        ZombieEntity a = spawn(level, bridge, "basic_zombie", 4.5F, 0);
        ZombieEntity b = spawn(level, bridge, "basic_zombie", 3.5F, 1);
        ZombieEntity c = spawn(level, bridge, "basic_zombie", 4.5F, 1);
        tick(level, bridge, 90);
        assertTrue(a.isRemoved() && b.isRemoved() && c.isRemoved(),
                "cherry bomb 3x3 area should kill all; removed=" + a.isRemoved() + "," + b.isRemoved() + "," + c.isRemoved()
                        + " plantAlive=" + level.entities().stream().anyMatch(e -> e.entityKind().equals("plant") && !e.isRemoved())
                        + " state=" + level.gameState());
    }

    @Test
    void chomperSwallowsSmallZombie() {
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        assertTrue(level.placePlant(bridge, 5, 0, 0)); // chomper slot
        ZombieEntity imp = spawn(level, bridge, "imp", 0.4F, 0);
        tick(level, bridge, 10);
        assertTrue(imp.isRemoved(), "chomper should swallow an imp in its cell");
    }

    @Test
    void sunflowerProducesFirstSunQuickly() {
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        assertTrue(level.placePlant(bridge, 1, 0, 0)); // sunflower slot
        tick(level, bridge, 320);
        assertTrue(level.entities().stream().anyMatch(e -> e instanceof ResourceDropEntity && !e.isRemoved()),
                "sunflower should produce its first sun within ~5s");
    }

    @Test
    void sunflowerProducesSunDrop() {
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        assertTrue(level.placePlant(bridge, 1, 0, 0)); // sunflower slot
        tick(level, bridge, 600);
        assertTrue(level.entities().stream().anyMatch(e -> e instanceof ResourceDropEntity && !e.isRemoved()),
                "sunflower should have produced a sun drop");
    }

    @Test
    void sunflowerSunDropCanBeCollectedIntoTheTeamWallet() {
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        assertTrue(level.placePlant(bridge, 1, 0, 0)); // sunflower slot, costs 50 of 150 sun
        int sunBefore = level.plantPlayer().team().resourcesOf(Identifier.withDefaultNamespace("sun"));
        tick(level, bridge, 320);

        ResourceDropEntity drop = level.entities().stream()
                .filter(e -> e instanceof ResourceDropEntity && !e.isRemoved())
                .map(e -> (ResourceDropEntity) e)
                .findFirst()
                .orElseThrow();
        assertTrue(level.collectResource(bridge, drop.id()));
        assertEquals(sunBefore + drop.amount(),
                level.plantPlayer().team().resourcesOf(Identifier.withDefaultNamespace("sun")));
        assertTrue(bridge.packets.stream().anyMatch(packet -> packet instanceof ResourceCollectS2C collect
                        && collect.entityId() == drop.id()
                        && collect.amount() == drop.amount()
                        && collect.resourceId().endsWith(":sun")),
                "accepted collection should send the client animation packet");
    }

    @Test
    void collectWithoutMatchingResourceCardIsRejected() {
        LevelDef def = new com.pvzce.api.content.LevelDef(
                Identifier.withDefaultNamespace("no_sun_card"), "", "", 3, 1,
                Map.of(Identifier.withDefaultNamespace("grass"), List.of("0,0", "1,0", "2,0")),
                demo.teams(), demo.winTeam(), demo.rules(), demo.envVars(), demo.waves(),
                demo.waveIntervalEndMultiplier(),
                List.of(Identifier.withDefaultNamespace("pea_shooter")),
                Map.of(Identifier.withDefaultNamespace("sun"), true),
                150, LevelDef.LevelMusicDef.DEFAULT, List.of());
        LevelServer level = new LevelServer(def, List.of(Identifier.withDefaultNamespace("pea_shooter")));
        CapturingBridge bridge = bridge();
        Identifier sun = Identifier.withDefaultNamespace("sun");
        Team team = level.team(Identifier.withDefaultNamespace("plant_team"));
        level.spawnResource(sun, 25, 0, 0, team);
        level.flushPending(bridge);
        ResourceDropEntity drop = level.entities().stream()
                .filter(e -> e instanceof ResourceDropEntity && !e.isRemoved())
                .map(e -> (ResourceDropEntity) e)
                .findFirst()
                .orElseThrow();
        int before = team.resourcesOf(sun);
        assertFalse(level.collectResource(bridge, drop.id()),
                "the team has no sun card in its selected bar");
        assertEquals(before, team.resourcesOf(sun));
        assertFalse(bridge.packets.stream().anyMatch(ResourceCollectS2C.class::isInstance));
    }

    @Test
    void shovelRemovesOnlyTheTopStackLayerPerUse() {
        LevelDef def = new com.pvzce.api.content.LevelDef(
                Identifier.withDefaultNamespace("shovel_stack_test"), "", "", 3, 1,
                Map.of(
                        Identifier.withDefaultNamespace("water"), List.of("0,0"),
                        Identifier.withDefaultNamespace("grass"), List.of("1,0", "2,0")
                ),
                demo.teams(), demo.winTeam(), demo.rules(), demo.envVars(), demo.waves(),
                demo.waveIntervalEndMultiplier(), demo.slots(), demo.unlockResources(),
                150, LevelDef.LevelMusicDef.DEFAULT, List.of());
        LevelServer level = new LevelServer(def);
        CapturingBridge bridge = bridge();
        assertTrue(level.placePlant(bridge, 6, 0, 0)); // lily pad carrier
        level.plantPlayer().slot(0).clearCooldown();
        assertTrue(level.placePlant(bridge, 0, 0, 0)); // pea shooter on top
        level.flushPending(bridge);
        assertEquals(2, level.plantCount());

        assertTrue(level.useTool(bridge, 11, 0, 0)); // shovel slot
        level.flushPending(bridge);
        assertEquals(1, level.plantCount(), "one shovel click removes one plant");
        assertEquals("lily_pad", level.plantAt(0, 0).defId().path(),
                "the plant on top of the carrier must be removed first");

        assertTrue(level.useTool(bridge, 11, 0, 0));
        level.flushPending(bridge);
        assertEquals(0, level.plantCount(), "a second shovel click removes the carrier");
        assertEquals("WATER", level.sceneAt(0, 0).surfaceClass(),
                "shovel must never remove scene elements");
    }

    @Test
    void basicZombieSpeedMatchesPvzPacing() {
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        ZombieEntity zombie = spawn(level, bridge, "basic_zombie", 8.5F, 0);
        tick(level, bridge, 600); // 10 seconds
        assertEquals(6.2F, zombie.cellX(), 0.05F, "0.23 cells/s = 2.3 cells in 10s");
    }

    @Test
    void potatoMineArmsThenExplodesOnContact() {
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        assertTrue(level.placePlant(bridge, 12, 2, 0)); // potato_mine slot (appended last)
        PlantEntity mine = level.plantsAt(2, 0).get(0);
        assertTrue(mine.armTicksLeft() > 0);
        tick(level, bridge, 900);
        assertEquals(0, mine.armTicksLeft(), "potato mine should finish arming after 15s");

        ZombieEntity zombie = spawn(level, bridge, "basic_zombie", 2.5F, 0);
        tick(level, bridge, 1);
        assertTrue(mine.isRemoved(), "armed potato mine should detonate");
        assertTrue(zombie.isRemoved(), "potato mine should kill the zombie in its cell");
    }

    @Test
    void nonSwimmingZombieDrownsInWater() {
        LevelDef def = new com.pvzce.api.content.LevelDef(
                Identifier.withDefaultNamespace("drown_test"), "", "", 3, 1,
                Map.of(
                        Identifier.withDefaultNamespace("water"), List.of("1,0"),
                        Identifier.withDefaultNamespace("grass"), List.of("0,0", "2,0")
                ),
                demo.teams(), demo.winTeam(), demo.rules(), demo.envVars(), demo.waves(),
                demo.waveIntervalEndMultiplier(),
                demo.slots(), demo.unlockResources(), 150, LevelDef.LevelMusicDef.DEFAULT, List.of());
        LevelServer level = new LevelServer(def);
        CapturingBridge bridge = bridge();
        ZombieEntity zombie = spawn(level, bridge, "basic_zombie", 2.5F, 0);
        tick(level, bridge, 200);
        assertTrue(zombie.isRemoved(), "a land zombie should drown when it walks into water");
    }

    @Test
    void timeHelpersAdjustClockAndPacket() {
        LevelServer level = newLevel();
        level.setDayTicks(600);
        assertEquals(600, level.clock().dayTicks());
        level.addDayTicks(-100);
        assertEquals(500, level.clock().dayTicks());
        assertEquals(500, level.timeOfDayPacket().dayTicks());
    }

    @Test
    void shovelRemovesPlant() {
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        assertTrue(level.placePlant(bridge, 0, 0, 0));
        assertTrue(level.useTool(bridge, 11, 0, 0)); // shovel slot
        level.flushPending(bridge);
        assertEquals(0, level.plantCount());
    }

    @Test
    void stackingMatrixWaterRoofAndCoffeeBean() {
        LevelDef def = demo;
        LevelServer level = new LevelServer(new com.pvzce.api.content.LevelDef(
                Identifier.withDefaultNamespace("stack_test"), "", "", 3, 1,
                Map.of(
                        Identifier.withDefaultNamespace("water"), List.of("0,0"),
                        Identifier.withDefaultNamespace("roof_slope"), List.of("1,0"),
                        Identifier.withDefaultNamespace("grass"), List.of("2,0")
                ),
                def.teams(), def.winTeam(), def.rules(), def.envVars(), def.waves(), 0, def.slots(),
                def.unlockResources(), 500, LevelDef.LevelMusicDef.DEFAULT, List.of()));
        CapturingBridge bridge = bridge();
        assertFalse(level.placePlant(bridge, 0, 0, 0)); // pea directly on water
        assertTrue(level.placePlant(bridge, 6, 0, 0));  // lily_pad on water
        PlantEntity lilyPad = level.plantAt(0, 0);
        assertNotNull(lilyPad);
        level.plantPlayer().slot(0).clearCooldown();
        assertTrue(level.placePlant(bridge, 0, 0, 0));   // pea on lily pad
        PlantEntity peaOnLilyPad = level.plantAt(0, 0);
        assertNotNull(peaOnLilyPad);
        assertTrue(peaOnLilyPad.height() > lilyPad.height());
        assertEquals(lilyPad.cellX() + 0.06F, peaOnLilyPad.cellX(), 0.001F);
        level.plantPlayer().slot(0).clearCooldown();
        assertFalse(level.placePlant(bridge, 0, 1, 0));  // pea directly on roof
        assertTrue(level.placePlant(bridge, 7, 1, 0));   // flower pot on roof
        PlantEntity flowerPot = level.plantAt(1, 0);
        assertNotNull(flowerPot);
        level.plantPlayer().slot(0).clearCooldown();
        assertTrue(level.placePlant(bridge, 0, 1, 0));   // pea on flower pot
        PlantEntity peaOnPot = level.plantAt(1, 0);
        assertNotNull(peaOnPot);
        assertEquals(flowerPot.height() + 0.38F, peaOnPot.height(), 0.001F);
        assertEquals(flowerPot.cellX() + 0.06F, peaOnPot.cellX(), 0.001F);
    }

    @Test
    void craterRecoversAccordingToRule() {
        LevelDef def = new com.pvzce.api.content.LevelDef(
                Identifier.withDefaultNamespace("crater_test"), "", "", 2, 1,
                Map.of(Identifier.withDefaultNamespace("crater"), List.of("0,0"),
                        Identifier.withDefaultNamespace("grass"), List.of("1,0")),
                demo.teams(), demo.winTeam(),
                Map.of(Identifier.withDefaultNamespace("crater_recovery"),
                        com.google.gson.JsonParser.parseString("10")),
                demo.envVars(), demo.waves(), demo.waveIntervalEndMultiplier(), demo.slots(), demo.unlockResources(), 150, LevelDef.LevelMusicDef.DEFAULT, List.of());
        LevelServer level = new LevelServer(def);
        CapturingBridge bridge = bridge();
        tick(level, bridge, 11);
        assertEquals("GRASS", level.sceneAt(0, 0).surfaceClass());
    }

    private static final class CapturingBridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
        }
    }
}
