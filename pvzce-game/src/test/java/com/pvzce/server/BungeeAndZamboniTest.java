package com.pvzce.server;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two raiders: the bungee zombie takes a plant, the zamboni takes the lane.
 *
 * <p>Neither is a zombie that walks up and eats something, so neither is covered by the tests the
 * ordinary bodies have. What matters about each is a rule no other zombie has: the bungee zombie
 * cannot be shot while it works and leaves a plant <em>gone</em> rather than damaged, and the
 * zamboni turns the ground behind it into something nothing can be planted in.
 */
class BungeeAndZamboniTest {
    private static final Identifier PLANT_TEAM = PvzceIds.PLANT_TEAM;
    private static final Identifier ZOMBIE_TEAM = PvzceIds.ZOMBIE_TEAM;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    /**
     * A five-row lawn.
     *
     * <p>Built rather than borrowed from 1-1: that level is a single grass row (it is the tutorial
     * that paints one), and both of these tests need a lane with room to drive down and a cell the
     * level actually painted - {@code sceneAt} answers null for a cell nobody painted.
     */
    private static LevelServer lawn() {
        java.util.Map<Identifier, List<String>> scene = new java.util.LinkedHashMap<>();
        List<String> grass = new java.util.ArrayList<>();
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 9; x++) {
                grass.add(x + "," + y);
            }
        }
        scene.put(PvzceIds.GRASS, grass);
        var def = com.pvzce.testutil.TestLevels.copy(
                        BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_1")))
                .waves(List.of()).build();
        return new LevelServer(new com.pvzce.api.content.LevelDef(
                def.id(), def.name(), def.description(), 9, 5, scene, def.teams(), def.winTeam(),
                def.rules(), def.envVars(), List.of(), def.waveIntervalEndMultiplier(),
                def.slots(), def.unlockResources(), def.initialSun(), def.music(),
                def.initialEntities()));
    }

    private static PlantEntity place(LevelServer level, String id, int x, int y) {
        PlantDef def = BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace(id));
        assertNotNull(def, id + " has to be registered");
        PlantEntity plant = level.spawnPlant(def, level.team(PLANT_TEAM), x, y);
        level.flushPending(packet -> { });
        assertNotNull(plant);
        return plant;
    }

    private static void tick(LevelServer level, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.tick(packet -> { });
        }
    }

    /** Both are registered, with the capabilities that make them what they are. */
    @Test
    void bothAreContent() {
        var bungee = BuiltInRegistries.ZOMBIES.get(Identifier.withDefaultNamespace("bungee_zombie"));
        var zamboni = BuiltInRegistries.ZOMBIES.get(Identifier.withDefaultNamespace("zamboni_zombie"));
        assertNotNull(bungee, "the bungee zombie has to be registered");
        assertNotNull(zamboni, "the zamboni has to be registered");
        assertTrue(bungee.capabilities().stream().anyMatch(capability ->
                capability.value() instanceof com.pvzce.common.capability.zombie.BungeeCapability));
        assertTrue(zamboni.capabilities().stream().anyMatch(capability ->
                capability.value() instanceof com.pvzce.common.capability.zombie.ZamboniCapability));
    }

    /**
     * It takes a plant off the lawn and leaves with it.
     *
     * <p>The plant is gone rather than damaged, and the zombie is gone too - which is what makes
     * the raid a raid: nothing is left behind to shoot.
     */
    @Test
    void theBungeeZombieTakesAPlantAndLeaves() {
        LevelServer level = lawn();
        PlantEntity victim = place(level, "pea_shooter", 3, 2);
        ZombieEntity bungee = level.spawnZombie(
                Identifier.withDefaultNamespace("bungee_zombie"),
                level.team(ZOMBIE_TEAM), 8F, 2);
        assertNotNull(bungee);
        level.flushPending(packet -> { });

        tick(level, 400);

        assertTrue(victim.isRemoved(), "the plant it came for is gone");
        assertFalse(bungee.isAlive(), "and the zombie left with it");
    }

    /** A lawn with nothing on it is not worth a raid: it goes home. */
    @Test
    void theBungeeZombieLeavesAnEmptyLawnAlone() {
        LevelServer level = lawn();
        ZombieEntity bungee = level.spawnZombie(
                Identifier.withDefaultNamespace("bungee_zombie"),
                level.team(ZOMBIE_TEAM), 8F, 2);
        assertNotNull(bungee);
        level.flushPending(packet -> { });

        tick(level, 300);
        assertFalse(bungee.isAlive(), "nothing to take, so it leaves rather than hovering");
    }

    /** It cannot be shot while it works; the only answer is to have nothing worth taking. */
    @Test
    void theBungeeZombieCannotBeShotWhileItWorks() {
        LevelServer level = lawn();
        place(level, "pea_shooter", 3, 2);
        ZombieEntity bungee = level.spawnZombie(
                Identifier.withDefaultNamespace("bungee_zombie"),
                level.team(ZOMBIE_TEAM), 3F, 2);
        assertNotNull(bungee);
        level.flushPending(packet -> { });

        tick(level, 20);
        assertTrue(bungee.cellY() >= 2F, "it is on the lawn, mid-raid");
        assertFalse(bungee.canBeHitByGround(), "and ground fire cannot reach it");
    }

    /**
     * The zamboni crushes what it drives over and leaves ice behind.
     *
     * <p>The trail is the half worth testing: a zamboni that only destroyed plants would be a fast
     * zombie, and it is the unplantable lane it leaves that makes it a problem the player has to
     * answer rather than repair.
     */
    @Test
    void theZamboniCrushesAndLeavesIce() {
        LevelServer level = lawn();
        PlantEntity victim = place(level, "pea_shooter", 5, 2);
        ZombieEntity zamboni = level.spawnZombie(
                Identifier.withDefaultNamespace("zamboni_zombie"),
                level.team(ZOMBIE_TEAM), 6F, 2);
        assertNotNull(zamboni);
        level.flushPending(packet -> { });

        tick(level, 900);

        assertTrue(victim.isRemoved(), "the plant in its way is crushed");
        assertEquals(PvzceIds.ICE, level.sceneAt(5, 2).id(),
                "and the ground it crossed is ice");
        assertFalse(level.canPlacePlant(
                        BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace("pea_shooter")),
                        5, 2),
                "which nothing can be planted in");
    }
}
