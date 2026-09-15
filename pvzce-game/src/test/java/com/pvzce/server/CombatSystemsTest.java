package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.EntityDespawnS2C;
import com.pvzce.common.network.packet.EntityUpdateS2C;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.ResourceCollectS2C;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ResourceDropEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.gamerule.GameRules;
import com.pvzce.server.level.LevelServer;
import com.pvzce.server.level.LevelValidator;
import com.pvzce.common.tag.TestContent;
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
        // Content and convention tags together: the placement rules read tags,
        // so a data-only load would leave every cell unplantable.
        TestContent.loadBuiltInContentAndTags();
        demo = BuiltInRegistries.LEVELS.get(Identifier.withDefaultNamespace("yard/adventure/demo_level"));
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
        assertTrue(a.isDying() && b.isDying() && c.isDying(),
                "cherry bomb 3x3 area should kill all; dying=" + a.isDying() + "," + b.isDying() + "," + c.isDying()
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
        // `isDying` rather than `isRemoved`: a dead zombie stays in the world for its
        // death animation (see ZombieEntity.CORPSE_TICKS), so "is it gone" is no longer
        // the same question as "is it dead".
        assertTrue(zombie.isDying(), "potato mine should kill the zombie in its cell");
        // The mine answers the same way now: the blast is instant, but the plant stays on
        // the field for its explode clip (see ExplosiveCapability.LINGER_TICKS), so "has
        // it gone off" is `occupiesCell` rather than `isRemoved`.
        assertFalse(mine.occupiesCell(), "an armed potato mine must stop being the plant in its cell");
        tick(level, bridge, com.pvzce.common.capability.plant.ExplosiveCapability.LINGER_TICKS);
        assertTrue(mine.isRemoved(), "the explosion drawing has to leave the field by itself");
    }

    /**
     * The reported bug: a cherry bomb used to be removed in the very tick it detonated, so
     * the client heard about the blast only as a despawn and never played the clip.
     */
    @Test
    void anExplosionPublishesItsClipBeforeThePlantLeaves() {
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        // demo_level's fifth card is the cherry bomb; a timed explosive has a 60-tick fuse.
        assertTrue(level.placePlant(bridge, 4, 2, 0));
        level.flushPending(bridge);
        PlantEntity bomb = level.plantsAt(2, 0).get(0);
        bridge.packets.clear();

        EntityUpdateS2C exploded = null;
        int despawnTick = -1;
        for (int i = 1; i <= 200; i++) {
            level.tick(bridge);
            for (PvzcePacket packet : bridge.packets) {
                if (packet instanceof EntityUpdateS2C update && update.entityId() == bomb.id()
                        && "explode".equals(update.animation()) && exploded == null) {
                    exploded = update;
                }
                if (packet instanceof EntityDespawnS2C despawn && despawn.entityId() == bomb.id()) {
                    despawnTick = i;
                }
            }
            bridge.packets.clear();
        }

        assertNotNull(exploded, "the client has to be told the plant is exploding");
        assertTrue(despawnTick > 0, "and then that it is gone");
        assertTrue(despawnTick > 60,
                "the despawn must not ride in the same breath as the blast: tick " + despawnTick);
    }

    /**
     * The reported bug: a zombie ate the cherry bomb before it went off, because the ash
     * line's 100 health is exactly one bite.
     */
    @Test
    void aZombieChewsOnAnUnexplodedBombForNothing() {
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        assertTrue(level.placePlant(bridge, 4, 2, 0));
        level.flushPending(bridge);
        PlantEntity bomb = level.plantsAt(2, 0).get(0);
        ZombieEntity zombie = spawn(level, bridge, "basic_zombie", 2.5F, 0);

        tick(level, bridge, 5);
        assertEquals("eat", zombie.animation(), "the zombie still stops and bites it");
        assertEquals(100, bomb.health(), "and the bites cost the bomb nothing");
        assertFalse(bomb.isRemoved(), "an unexploded bomb cannot be eaten");
        assertTrue(bomb.occupiesCell(), "it is still what the zombie is standing on");
    }

    /** The other half: a giant swings at a bomb too, and also for nothing. */
    @Test
    void theHammerCannotSmashAnUnexplodedBomb() {
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        assertTrue(level.placePlant(bridge, 4, 2, 0));
        level.flushPending(bridge);
        PlantEntity bomb = level.plantsAt(2, 0).get(0);
        assertFalse(bomb.damageFrom(10_000), "an unexploded bomb shrugs the hit off");
        assertEquals(100, bomb.health());
    }

    /**
     * The ash line's blast is a registered damage type, and what the type buys is exactly
     * "armour does not absorb this".
     */
    @Test
    void ashIgnoresArmorWhileImpactDoesNot() {
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        ZombieEntity buckethead = spawn(level, bridge, "buckethead_zombie", 6.5F, 0);

        int armor = buckethead.armorHealth();
        buckethead.damage(50, ZombieEntity.damageType(com.pvzce.common.PvzceIds.DAMAGE_ASH), level);
        assertEquals(armor, buckethead.armorHealth(), "an ash hit must not touch the bucket");
        assertEquals(150, buckethead.health(), "it lands on the body instead");

        // The same 50 points as an impact go to the bucket first, which is what makes a
        // Conehead cost two bowling hits instead of one.
        buckethead.damage(50, ZombieEntity.damageType(com.pvzce.common.PvzceIds.DAMAGE_IMPACT), level);
        assertEquals(armor - 50, buckethead.armorHealth(), "an impact is absorbed by armour");
        assertEquals(150, buckethead.health(), "and the body is untouched while it holds");
    }

    /** The registry is data-backed: the shipped pack declares the entries the code falls back to. */
    @Test
    void damageTypesLoadFromTheDataPack() {
        for (String path : List.of("ash", "splash", "mower", "projectile", "impact")) {
            Identifier id = Identifier.withDefaultNamespace(path);
            assertNotNull(BuiltInRegistries.DAMAGE_TYPES.get(id), "missing damage type " + id);
        }
        assertTrue(BuiltInRegistries.DAMAGE_TYPES.get(com.pvzce.common.PvzceIds.DAMAGE_ASH).ignoresArmor());
        assertTrue(BuiltInRegistries.DAMAGE_TYPES.get(com.pvzce.common.PvzceIds.DAMAGE_SPLASH).ignoresArmor());
        assertFalse(BuiltInRegistries.DAMAGE_TYPES.get(com.pvzce.common.PvzceIds.DAMAGE_PROJECTILE).ignoresArmor());
        assertFalse(BuiltInRegistries.DAMAGE_TYPES.get(com.pvzce.common.PvzceIds.DAMAGE_IMPACT).ignoresArmor());
        // And nobody points at a type that is not registered: this is the pass the server
        // runs on every /reload, so a typo in a content file is named instead of being
        // silently read as "armour applies".
        assertEquals(List.of(), LevelValidator.validateDamageTypes());
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
        // A roof is plantable directly (#c:plantable) as well as pot-able
        // (#c:ground), which is why the pot below is optional there.
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

    @Test
    void aHitIsHeldLongEnoughToBeSeen() {
        // The flinch used to be published for the single tick the damage landed on, and the
        // entity sync runs every third tick - so the client usually never heard about it, and
        // a zombie under fire just kept its walk cycle. When it did hear, it played a
        // second-long clip for one tick and blended straight back out. Both read as choppy.
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        ZombieEntity zombie = spawn(level, bridge, "basic_zombie", 6.5F, 0);

        zombie.damageBody(10, level);
        assertEquals("hit", zombie.animation(), "a hit has to say so");
        zombie.tick(level);
        assertEquals("hit", zombie.animation(), "and still be saying it on the next tick");

        // Held for the whole beat, then handed back to the walk cycle - a zombie that never
        // stopped flinching would be just as wrong as one that never flinched.
        tick(level, bridge, ZombieEntity.HIT_HOLD_TICKS);
        assertEquals("walk", zombie.animation(), "the flinch has to end");
    }

    @Test
    void aFlinchDoesNotStopTheZombie() {
        // The hold is a change of *pose*, not of behaviour: the original's zombie walks
        // into the peas. Pausing it would turn one zombie's flinch into a stutter for the
        // whole lane.
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        ZombieEntity zombie = spawn(level, bridge, "basic_zombie", 6.5F, 0);
        float before = zombie.cellX();

        zombie.damageBody(10, level);
        zombie.tick(level);

        assertTrue(zombie.cellX() < before,
                "a flinching zombie must keep walking: " + before + " -> " + zombie.cellX());
    }

    @Test
    void aFlinchSurvivesASaveAndLoad() {
        // hitTicks is state, so it goes in the snapshot like every other counter. Without it
        // a save taken mid-flinch comes back as a zombie that stopped flinching early.
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        ZombieEntity zombie = spawn(level, bridge, "basic_zombie", 6.5F, 0);
        zombie.damageBody(10, level);
        zombie.tick(level);

        ZombieEntity restored = new ZombieEntity(
                BuiltInRegistries.ZOMBIES.get(Identifier.withDefaultNamespace("basic_zombie")),
                level.team(Identifier.withDefaultNamespace("zombie_team")), 6.5F, 0);
        restored.restoreState(zombie.saveState());
        assertEquals(zombie.animation(), restored.animation());
        restored.tick(level);
        assertEquals("hit", restored.animation(),
                "a restored mid-flinch zombie must still be flinching");
    }

    private static final class CapturingBridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
        }
    }
}
