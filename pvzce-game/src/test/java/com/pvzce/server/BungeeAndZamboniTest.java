package com.pvzce.server;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.packet.SceneSyncS2C;
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
     * The zamboni crushes what it drives over and leaves ice behind, and the client is told.
     *
     * <p>The trail is the half worth testing: a zamboni that only destroyed plants would be a fast
     * zombie, and it is the unplantable lane it leaves that makes it a problem the player has to
     * answer rather than repair.
     *
     * <p>The packet is asserted as well as the grid, because for a while the packet was the half
     * that was missing: {@code leaveIce} wrote the cell with {@code setScene} and never called
     * {@code sendSceneCell}, so the server had ice and the player saw lawn - and on the zamboni
     * levels, which hide {@code pvzce:grass}, not even that: the lane showed the backdrop. The
     * grid-only assertion below stayed green through all of it.
     */
    @Test
    void theZamboniCrushesAndLeavesIce() {
        LevelServer level = lawn();
        PlantEntity victim = place(level, "pea_shooter", 5, 2);
        ZombieEntity zamboni = level.spawnZombie(
                Identifier.withDefaultNamespace("zamboni_zombie"),
                level.team(ZOMBIE_TEAM), 6F, 2);
        assertNotNull(zamboni);
        List<com.pvzce.common.network.PvzcePacket> sent = new java.util.ArrayList<>();
        level.flushPending(sent::add);

        for (int i = 0; i < 900; i++) {
            level.tick(sent::add);
        }

        assertTrue(victim.isRemoved(), "the plant in its way is crushed");
        assertEquals(PvzceIds.ICE, level.sceneAt(5, 2).id(),
                "and the ground it crossed is ice");
        assertFalse(level.canPlacePlant(
                        BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace("pea_shooter")),
                        5, 2),
                "which nothing can be planted in");
        assertTrue(sent.stream().anyMatch(packet -> packet instanceof SceneSyncS2C sync
                        && sync.cells().stream().anyMatch(cell ->
                                cell.x() == 5 && cell.y() == 2
                                        && PvzceIds.ICE.toString().equals(cell.elementId()))),
                "and the client was told that cell is ice, or it draws grass over it forever");
    }

    /**
     * A spikeweed wrecks the machine instead of being driven over.
     *
     * <p>The reported "冰车僵尸应该被地刺扎毁而不是干掉地刺": a zomboni on the spikeweed's cell now spends
     * the plant destroying the *zombie*, which is what "扎毁" means and what keeps one spikeweed
     * from clearing every zomboni in the lane. Both go, and nothing is left frozen - the machine
     * never got past the cell it died on.
     */
    @Test
    void aSpikeweedWrecksTheZamboni() {
        LevelServer level = lawn();
        ZombieEntity zamboni = level.spawnZombie(
                Identifier.withDefaultNamespace("zamboni_zombie"),
                level.team(ZOMBIE_TEAM), 6F, 2);
        assertNotNull(zamboni);
        level.flushPending(packet -> { });
        // Planted after the spawn so the zamboni's own spawn packet is not what "removed" is
        // measured against: this is one zombie and one plant on an empty lawn.
        PlantEntity spike = place(level, "spikeweed", 4, 2);

        tick(level, 400);

        assertTrue(spike.isRemoved(), "the spike is spent wrecking the machine");
        // Killed, not despawned: a body stays for its death clip (see CORPSE_TICKS), and the
        // wreck's own particles are what plays over it.
        assertFalse(zamboni.isAlive(), "and the machine is wrecked");
    }

    /**
     * The trail is terrain with a clock: every frozen cell melts back into lawn.
     *
     * <p>The original's own thirty seconds. It is a rule rather than a hardcoded constant because
     * a lane the zamboni took is a lane the *player* lost, not one the level lost: a level that
     * wants the old permanent reading writes {@code ice_melt: 0}.
     */
    @Test
    void theIceMeltsBackIntoLawnOnTheLevelsClock() {
        LevelServer level = lawn();
        level.spawnZombie(Identifier.withDefaultNamespace("zamboni_zombie"),
                level.team(ZOMBIE_TEAM), 6F, 2);
        tick(level, 200);
        int lastFrozen = -1;
        for (int x = 0; x < 9; x++) {
            if (PvzceIds.ICE.equals(level.sceneAt(x, 2).id())) {
                lastFrozen = x;
            }
        }
        assertTrue(lastFrozen >= 0, "the zamboni laid a trail to melt");
        final int frozen = lastFrozen;

        List<com.pvzce.common.network.PvzcePacket> sent = new java.util.ArrayList<>();
        int meltTicks = level.rules().getInt(PvzceIds.RULE_ICE_MELT);
        assertTrue(meltTicks > 0, "the shipped default melts");
        for (int i = 0; i < meltTicks; i++) {
            level.tick(sent::add);
        }

        assertEquals(PvzceIds.GRASS, level.sceneAt(frozen, 2).id(),
                "a cell that has been frozen for the whole clock is lawn again");
        assertTrue(sent.stream().anyMatch(packet -> packet instanceof SceneSyncS2C sync
                        && sync.cells().stream().anyMatch(cell ->
                                cell.x() == frozen && cell.y() == 2
                                        && PvzceIds.GRASS.toString().equals(cell.elementId()))),
                "and the client is told, the same way it was told about the ice");
    }

    /**
     * Fire takes the trail back: a cherry bomb melts the ice it covered, a jalapeno its whole row.
     *
     * <p>The two shapes are the two blasts. The ice outside them is the control: if a bomb melted
     * the lawn it did not cover, "fire melts ice" would be indistinguishable from "ice melts".
     */
    @Test
    void fireMeltsTheIceItCovers() {
        LevelServer level = lawn();
        freeze(level, 4, 2);
        freeze(level, 8, 2);
        place(level, "cherry_bomb", 4, 1);

        tick(level, 80);

        assertEquals(PvzceIds.GRASS, level.sceneAt(4, 2).id(),
                "the cell the blast covered is lawn again");
        assertEquals(PvzceIds.ICE, level.sceneAt(8, 2).id(),
                "and the ice four cells away is not; fire melts what it reached");

        LevelServer rowLevel = lawn();
        freeze(rowLevel, 1, 2);
        freeze(rowLevel, 8, 2);
        freeze(rowLevel, 4, 3);
        place(rowLevel, "jalapeno", 4, 2);

        tick(rowLevel, 80);

        assertEquals(PvzceIds.GRASS, rowLevel.sceneAt(1, 2).id(),
                "the jalapeno burns its whole row, one end to the other");
        assertEquals(PvzceIds.GRASS, rowLevel.sceneAt(8, 2).id(), "both ends of it");
        assertEquals(PvzceIds.ICE, rowLevel.sceneAt(4, 3).id(),
                "and nothing in the row next door");
    }

    /** Freezes one cell the way the zamboni does, packet included. */
    private static void freeze(LevelServer level, int x, int y) {
        level.setScene(x, y, PvzceIds.ICE);
        level.sendSceneCell(x, y);
    }
}
