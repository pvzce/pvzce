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
import com.pvzce.server.entity.ProjectileEntity;
import com.pvzce.server.entity.ResourceDropEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.gamerule.GameRules;
import com.pvzce.server.level.LevelServer;
import com.pvzce.server.level.LevelValidator;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /** Spawns one zombie and hands back exactly that one, not the first of its row. */
    private static ZombieEntity spawnExact(LevelServer level, CapturingBridge bridge, Identifier id,
                                           float x, int row) {
        ZombieEntity zombie = level.spawnZombie(id, level.team(Identifier.withDefaultNamespace("zombie_team")),
                x, row);
        level.flushPending(bridge);
        assertNotNull(zombie, id + " must be registered");
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
                Identifier.withDefaultNamespace("sun_spawn_interval_min"),
                com.google.gson.JsonParser.parseString("240"),
                Identifier.withDefaultNamespace("day_length"),
                com.google.gson.JsonParser.parseString("120")
        ));
        assertEquals(240, rules.getInt(Identifier.withDefaultNamespace("sun_spawn_interval_min")));
        assertEquals(120, rules.getInt(Identifier.withDefaultNamespace("day_length")));
        rules.set(Identifier.withDefaultNamespace("sun_spawn_interval_min"), 999_999);
        assertEquals(36000, rules.getInt(Identifier.withDefaultNamespace("sun_spawn_interval_min")));
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

    /**
     * A burst volley puts its peas on the lawn one at a time, not one on top of another.
     *
     * <p>The reported bug: a repeater looked exactly like a peashooter. Its definition has always
     * said {@code count 2}, and the capability has always spawned two peas - but on the same tick
     * from the same point, so they were the same object twice: one sprite on screen, one zombie
     * hit, double damage that reads as a single pea. {@code burst_delay} is the fix and this is
     * what it has to keep true: the second pea exists, and it is behind the first one.
     *
     * <p>The gap is asserted rather than the tick number because it is the thing the player sees:
     * twelve ticks at the pea's four cells a second is four fifths of a cell, which is what makes
     * a volley read as a volley.
     */
    @Test
    void aBurstVolleyLeavesOnePeaAtATime() {
        assertBurstSpacing("repeater", 2);
        assertBurstSpacing("gatling_pea", 4);
    }

    private static void assertBurstSpacing(String plantId, int count) {
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        com.pvzce.api.content.PlantDef def = BuiltInRegistries.PLANTS.get(
                Identifier.withDefaultNamespace(plantId));
        assertNotNull(def, plantId + " has to exist");
        level.spawnPlant(def, level.team(Identifier.withDefaultNamespace("plant_team")), 1, 0);
        level.flushPending(bridge);
        spawn(level, bridge, "basic_zombie", 8.5F, 0);

        // The firing tick: one pea in the air, the rest still in the barrel.
        tick(level, bridge, 2);
        assertEquals(1, liveProjectiles(level).size(),
                plantId + " should have exactly one pea in the air on the tick it fires");

        // Twelve ticks per pea: by the last one the whole volley is out and none has reached
        // the zombie yet, so every pea of the burst is on the board at once.
        tick(level, bridge, 12 * (count - 1));
        List<Float> xs = liveProjectiles(level).stream()
                .map(ProjectileEntity::cellX)
                .sorted()
                .toList();
        assertEquals(count, xs.size(), plantId + " should have its whole volley in the air");
        for (int i = 1; i < xs.size(); i++) {
            assertTrue(xs.get(i) - xs.get(i - 1) > 0.5F,
                    plantId + "'s peas have to be separate objects, not a stack: "
                            + xs);
        }
    }

    private static List<ProjectileEntity> liveProjectiles(LevelServer level) {
        return level.entities().stream()
                .filter(ProjectileEntity.class::isInstance)
                .map(ProjectileEntity.class::cast)
                .filter(projectile -> !projectile.isRemoved())
                .toList();
    }

    /**
     * The threepeater opens all three heads at once.
     *
     * <p>Its three shots are three separate entries, one per lane, and they used to be staggered by
     * {@code initial_delay} 0/30/60 so that each pea left on the tick its own head's window opened
     * in the art. The reported behaviour is the opposite - "三线射手应该三个头同时发射子弹，而不是
     * 分开发射" - so all three entries now leave on the firing tick, which is also what every other
     * multi-row shot in the game does (see {@code ShooterCapability}'s "one tick, one volley").
     *
     * <p>Three entries and not one entry with {@code rows}: the rows have to be the plant's own row
     * plus one either side, and {@code rows} counts a contiguous block downward.
     */
    @Test
    void theThreepeaterOpensAllThreeHeadsAtOnce() {
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        com.pvzce.api.content.PlantDef def = BuiltInRegistries.PLANTS.get(
                Identifier.withDefaultNamespace("threepeater"));
        assertNotNull(def, "threepeater has to exist");
        level.spawnPlant(def, level.team(Identifier.withDefaultNamespace("plant_team")), 1, 2);
        level.flushPending(bridge);
        // A target in its own row: that is what lets it fire at all, and the row-2 entry is the
        // one with no row offset.
        spawn(level, bridge, "basic_zombie", 8.5F, 2);

        tick(level, bridge, 2);
        assertEquals(3, liveProjectiles(level).size(),
                "one volley is three peas, one per lane, and all three leave together");
        // The rows are the plant's own and the two beside it, which is the shape a single body
        // cannot state: 1 is above, 2 is its own, 3 is below.
        assertEquals(java.util.List.of(1, 2, 3),
                liveProjectiles(level).stream().map(p -> p.gridY()).sorted().toList(),
                "the three peas cover the plant's row and the one either side");
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

    /**
     * The reported bug: a cherry bomb covered less than the 3x3 the original clears.
     *
     * <p>The blast is a square, {@code |dx| <= radius} per axis, and the radius was 1.0 - which
     * reaches exactly the *centre* of each neighbouring cell. A zombie in the far half of an
     * adjacent cell, the one about to step on the bomb, was outside it; a zombie two cells away
     * must stay outside whatever the number is. The three zombies below are at the two ends of
     * "in the 3x3", so the test cannot pass by being lucky about where they stood.
     */
    @Test
    void cherryBombCoversTheWholeThreeByThree() {
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        assertTrue(level.placePlant(bridge, 4, 3, 0)); // the bomb itself at (3,0)
        // The far edge of the cell to its right, the far edge of the cell diagonally below
        // it, and one more cell out in a straight line. Spawned directly rather than through
        // the local helper, which hands back the first zombie of the row.
        Identifier basic = Identifier.withDefaultNamespace("basic_zombie");
        ZombieEntity farRight = spawnExact(level, bridge, basic, 4.99F, 0);
        ZombieEntity diagonal = spawnExact(level, bridge, basic, 4.99F, 1);
        ZombieEntity outside = spawnExact(level, bridge, basic, 5.6F, 0);

        tick(level, bridge, 90);
        assertTrue(farRight.isDying(), "the neighbouring cell is inside the blast, all of it");
        assertTrue(diagonal.isDying(), "and so is the diagonal");
        assertFalse(outside.isDying(), "but the third cell over is not");
    }

    /**
     * A slowed zombie is drawn frozen, which means the flag has to reach the client.
     *
     * <p>The status itself is server state and the client cannot derive it from anything else
     * it is sent, so it travels with the entity's other visible state. Pinned here because
     * "the client draws it" and "the server says it" are two ends of one wire field.
     */
    @Test
    void aSlowedZombieReportsItselfChilled() {
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        ZombieEntity zombie = spawnExact(level, bridge,
                Identifier.withDefaultNamespace("basic_zombie"), 6.5F, 0);
        assertFalse(zombie.chilled(), "nothing has slowed it yet");
        assertFalse(zombie.updatePacket().chilled(), "and the wire says so");
        float normalSpeed = zombie.moveSpeed(level);

        zombie.applyStatus(com.pvzce.api.content.ZombieStatus.SLOW, 240, 0.5F);
        assertTrue(zombie.chilled(), "a slow status is what the frozen look is drawn from");
        assertTrue(zombie.updatePacket().chilled(), "and it is streamed");
        assertEquals(normalSpeed * 0.5F, zombie.moveSpeed(level), 0.0001F,
                "the status still halves its speed: the flag is presentation, not the effect");

        tick(level, bridge, 241);
        assertFalse(zombie.chilled(), "and the flag goes away with the status");
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
        // the field for its explode clip (see ExplosiveCapability.DEFAULT_LINGER_TICKS), so
        // "has it gone off" is `occupiesCell` rather than `isRemoved`.
        assertFalse(mine.occupiesCell(), "an armed potato mine must stop being the plant in its cell");
        tick(level, bridge,
                com.pvzce.common.capability.plant.ExplosiveCapability.DEFAULT_LINGER_TICKS);
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

    /**
     * The Doom-shroom leaves one hole: the cell it was planted on.
     *
     * <p>Not the footprint of its blast. Reusing the blast radius turned a 7x7 explosion into a
     * 7x7 crater, so the lawn around the mushroom was unplantable for the whole recovery - the
     * original's doom shroom gives up the one tile it stood on, which is a cost the player chose
     * when they planted it.
     */
    @Test
    void theDoomShroomLeavesAHoleOnlyWhereItStood() {
        // A night board: the doom shroom is a mushroom, and a sleeping plant's fuse never runs.
        LevelDef night = com.pvzce.testutil.TestLevels.copy(demo)
                .rules(Map.of(
                        Identifier.withDefaultNamespace("day_length"),
                        com.google.gson.JsonParser.parseString("0"),
                        Identifier.withDefaultNamespace("night_length"),
                        com.google.gson.JsonParser.parseString("360000")))
                .waves(List.of())
                .build();
        LevelServer level = new LevelServer(night);
        CapturingBridge bridge = bridge();
        com.pvzce.api.content.PlantDef doom =
                BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace("doom_shroom"));
        assertNotNull(doom, "the doom shroom has to exist");
        level.spawnPlant(doom, level.team(Identifier.withDefaultNamespace("plant_team")), 4, 2);
        level.flushPending(bridge);

        // Long enough for the fuse (90 ticks) plus a tick.
        tick(level, bridge, 120);

        for (int x = 0; x < level.width(); x++) {
            for (int y = 0; y < level.height(); y++) {
                boolean crater = "CRATER".equals(surface(level, x, y));
                boolean ownCell = x == 4 && y == 2;
                assertEquals(ownCell, crater, "crater at " + x + "," + y);
            }
        }
    }

    /** The surface class of a cell, or an empty string when the cell is not painted. */
    private static String surface(LevelServer level, int x, int y) {
        var element = level.sceneAt(x, y);
        return element == null ? "" : element.surfaceClass();
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

    /**
     * A hit does not touch the animation.
     *
     * <p>It used to hold the {@code hit} clip for eight ticks, and that clip is the zombie's
     * *standing* pose: a zombie walking into a stream of peas froze for an eighth of a second
     * on every hit and restarted its walk cycle when it came back, which read as the walk
     * animation being reset. The original has no hurt animation - the splat and the sound are
     * the feedback - so the pose is left exactly as the walk loop published it.
     */
    @Test
    void aHitLeavesTheWalkCycleAlone() {
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        ZombieEntity zombie = spawn(level, bridge, "basic_zombie", 6.5F, 0);
        tick(level, bridge, 2);
        assertEquals("walk", zombie.animation());

        float before = zombie.cellX();
        zombie.damageBody(10, level);
        assertEquals("walk", zombie.animation(), "being shot is not a change of pose");
        zombie.tick(level);
        assertEquals("walk", zombie.animation(), "and it is still walking on the next tick");
        assertTrue(zombie.cellX() < before, "and still moving: " + before + " -> " + zombie.cellX());
    }

    /**
     * Armour hits play the armour's own sound, not the shot's.
     *
     * <p>A pea hitting a bucket used to splat with the projectile's generic impact sound,
     * because that sound was preferred over the zombie's {@code armor_hit}. End to end here
     * (a real peashooter, a real conehead) rather than by calling the damage method, because
     * the effect only travels while the level is inside a tick.
     */
    @Test
    void armorHitsUseTheZombiesOwnSound() {
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        assertTrue(level.placePlant(bridge, 0, 1, 0)); // pea_shooter slot
        spawn(level, bridge, "conehead_zombie", 7F, 0);

        tick(level, bridge, 900);
        assertNotNull(effect(bridge, "plastichit"),
                "the cone is a plastic hat and must clank like one");
        assertNull(effect(bridge, "sfx/projectile/hit"),
                "and it must not splat like a bare body while the cone is still on");
    }

    /**
     * A knocked-off cone flies from the zombie's head, and it is the cone that flies.
     *
     * <p>Two things were wrong here: the debris was spawned at the zombie's feet, so a hat
     * came off its boots, and the particle's lifetime was half a second - the cone was gone
     * before it landed, which reads as a hat that vanished rather than one that fell.
     */
    @Test
    void aBrokenConeFallsFromTheHead() {
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        // Two shooters, so the cone's 370 durability is gone in about 900 ticks - before the
        // zombie reaches them. The effects only travel while the level is inside a tick, so
        // the hits have to come from a real shooter rather than a direct damage call.
        var peaShooter = BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace("pea_shooter"));
        assertNotNull(peaShooter);
        var plantTeam = level.team(Identifier.withDefaultNamespace("plant_team"));
        level.spawnPlant(peaShooter, plantTeam, 1, 0);
        level.spawnPlant(peaShooter, plantTeam, 2, 0);
        ZombieEntity conehead = spawn(level, bridge, "conehead_zombie", 6.5F, 0);
        float rowCentre = conehead.cellY();

        tick(level, bridge, 1_200);
        var debris = particle(bridge, "zombie_traffic_cone");
        assertNotNull(debris, "the cone itself is the debris when it breaks");
        assertTrue(debris.y() > rowCentre + 0.3F,
                "and it comes off the head, not the boots: y=" + debris.y() + " row " + rowCentre);
    }

    /**
     * An armed potato mine rises once, then holds the armed pose.
     *
     * <p>Asking for the emergence ({@code armed}) every tick restarted it every time its clip
     * handed over to the loop, so a mine that had already surfaced kept climbing out of the
     * ground while it waited for a zombie.
     */
    @Test
    void aPotatoMineRisesOnceThenHolds() {
        LevelServer level = newLevel();
        CapturingBridge bridge = bridge();
        assertTrue(level.placePlant(bridge, 12, 3, 0)); // potato_mine slot in demo_level
        PlantEntity mine = level.entities().stream()
                .filter(e -> e instanceof PlantEntity p && p.defId() != null
                        && p.defId().path().equals("potato_mine"))
                .map(e -> (PlantEntity) e)
                .findFirst().orElseThrow();

        // The arm-up countdown is 900 ticks, and `grow` is what it publishes while buried.
        tick(level, bridge, 1);
        assertEquals("grow", mine.animation(), "buried while the fuse burns");
        tick(level, bridge, 899);
        assertEquals("armed", mine.animation(), "the tick it surfaces");
        tick(level, bridge, 2);
        assertEquals("armed_loop", mine.animation(),
                "and from then on it holds the loop, not the emergence");
    }

    /** The last effect event carrying the given particle, or null. */
    private static com.pvzce.common.network.packet.EffectEventS2C particle(CapturingBridge bridge, String id) {
        com.pvzce.common.network.packet.EffectEventS2C found = null;
        for (PvzcePacket packet : bridge.packets) {
            if (packet instanceof com.pvzce.common.network.packet.EffectEventS2C event
                    && event.particle().contains(id)) {
                found = event;
            }
        }
        return found;
    }

    /** The last effect event whose sound contains {@code needle}, or null. */
    private static com.pvzce.common.network.packet.EffectEventS2C effect(CapturingBridge bridge, String needle) {
        com.pvzce.common.network.packet.EffectEventS2C found = null;
        for (PvzcePacket packet : bridge.packets) {
            if (packet instanceof com.pvzce.common.network.packet.EffectEventS2C event
                    && event.sound().contains(needle)) {
                found = event;
            }
        }
        return found;
    }

    private static final class CapturingBridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
        }
    }
}
