package com.pvzce.server.level;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.pvzce.api.content.EnvValue;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.content.LevelUnlock;
import com.pvzce.api.content.MowerData;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.TeamDef;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.capability.zombie.SubmergeCapability;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pool's rules that are not content: lanes, mower kinds, the watering can and the kelp.
 *
 * <p>Each of these is a rule the five shipped levels lean on, tested here on a small board so a
 * failure says which rule broke rather than which level file.
 */
class PoolMechanicsTest {
    private static final Identifier PLANT_TEAM = PvzceIds.PLANT_TEAM;
    private static final List<Integer> WATER_ROWS = List.of(2, 3);
    private static final List<Integer> GRASS_ROWS = List.of(0, 1, 4, 5);

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static final class Bridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
        }
    }

    /** A 9x6 pool board: two wet rows, a mower per row (the pool cleaner in the water). */
    private static LevelDef poolLevel(List<WaveDef> waves, List<Identifier> slots) {
        Map<Identifier, List<String>> scene = new LinkedHashMap<>();
        List<String> grass = new ArrayList<>();
        List<String> water = new ArrayList<>();
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 9; x++) {
                (WATER_ROWS.contains(y) ? water : grass).add(x + "," + y);
            }
        }
        scene.put(Identifier.withDefaultNamespace("grass"), grass);
        scene.put(Identifier.withDefaultNamespace("water"), water);

        MowerData mowers = new MowerData(Optional.of(GRASS_ROWS), List.of(
                new MowerData.MowerKind(2, Identifier.withDefaultNamespace("pool_cleaner"),
                        Optional.of(Identifier.withDefaultNamespace("sfx/ambient/pool_cleaner"))),
                new MowerData.MowerKind(3, Identifier.withDefaultNamespace("pool_cleaner"),
                        Optional.empty())));

        return new LevelDef(
                Identifier.withDefaultNamespace("pool_test"), "泳池测试", "",
                9, 6, scene,
                List.of(new TeamDef(PLANT_TEAM, "植物方", "survive_waves"),
                        new TeamDef(PvzceIds.ZOMBIE_TEAM, "僵尸方", "plant_side_lost")),
                PLANT_TEAM, Map.<Identifier, JsonElement>of(), Map.<Identifier, EnvValue>of(),
                waves, 1F, slots, Map.of(), 1000,
                new LevelDef.LevelMusicDef(List.of()), List.of(), 8,
                LevelRewards.NONE, LevelUnlock.NONE,
                List.of(new com.pvzce.api.content.mechanic.TypedMechanic(
                        PvzceIds.MECHANIC_MOWER, mowers)),
                com.pvzce.api.content.LevelDialogue.EMPTY);
    }

    private static void tick(LevelServer level, LevelServer.ServerBridge bridge, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.tick(bridge);
        }
    }

    // ------------------------------------------------------------------
    // Lanes
    // ------------------------------------------------------------------

    /**
     * A wave entry that names lanes sends every one of its zombies to one of them.
     *
     * <p>Without this a pool level's land zombies walk into the water and drown, so the rule is
     * worth a test that watches the spawns rather than the parse.
     */
    @Test
    void anEntryOnlySpawnsInTheLanesItNames() {
        WaveDef wave = new WaveDef(WaveDef.WaveType.SMALL, 1, 5,
                List.of(new WaveDef.Entry(Identifier.withDefaultNamespace("ducky_tube_zombie"),
                        6, WATER_ROWS)));
        LevelServer level = new LevelServer(poolLevel(List.of(wave), List.of()));
        Bridge bridge = new Bridge();

        List<Integer> rows = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            level.tick(bridge);
            for (ZombieEntity zombie : level.zombiesInRow(0)) {
                rows.add(zombie.gridY());
            }
            for (int row : WATER_ROWS) {
                for (ZombieEntity zombie : level.zombiesInRow(row)) {
                    rows.add(zombie.gridY());
                }
            }
            if (!rows.isEmpty()) {
                break;
            }
        }
        assertFalse(rows.isEmpty(), "the wave must have released something");
        for (int row : rows) {
            assertTrue(WATER_ROWS.contains(row), "a floatie arrived in row " + row);
        }
    }

    /**
     * An entry that names <em>no</em> lanes still keeps walkers out of the pool.
     *
     * <p>The reported bug: a land zombie spawned in a water row died before it appeared. The wave
     * spawns off the right edge and the entity's column is clamped to the last one, so on a pool
     * board the spawn cell was water - and {@code ZombieEntity.tick} drowns a non-swimmer on its
     * very first tick. Levels that write {@code rows} per entry were safe (see the test above);
     * the ones that leave it open, and every spawn that does not come from a wave at all, were not.
     */
    @Test
    void aWalkerWithNoNamedLanesStillNeverArrivesInTheWater() {
        WaveDef wave = new WaveDef(WaveDef.WaveType.SMALL, 1, 5,
                List.of(new WaveDef.Entry(Identifier.withDefaultNamespace("basic_zombie"), 24)));
        LevelServer level = new LevelServer(poolLevel(List.of(wave), List.of()));
        Bridge bridge = new Bridge();

        List<Integer> seen = new ArrayList<>();
        for (int i = 0; i < 4_000; i++) {
            level.tick(bridge);
            for (int row = 0; row < level.height(); row++) {
                for (ZombieEntity zombie : level.zombiesInRow(row)) {
                    seen.add(zombie.gridY());
                }
            }
        }
        assertTrue(seen.size() >= 20,
                "the wave has to have actually sent zombies, saw " + seen.size());
        for (int row : seen) {
            assertTrue(GRASS_ROWS.contains(row),
                    "a zombie that cannot swim arrived in water row " + row);
        }
    }

    /**
     * The same rule for spawns that do not come from a wave.
     *
     * <p>This is the half the wave director cannot cover: a mutation's random lane, a boss summon,
     * a dancer's escort. They all land on {@code LevelServer.spawnZombie}, which is where the rule
     * is enforced for them.
     */
    @Test
    void everySpawnPathRedirectsAWalkerOutOfTheWater() {
        LevelServer level = new LevelServer(poolLevel(List.of(), List.of()));
        for (int row : WATER_ROWS) {
            ZombieEntity walker = level.spawnZombie(
                    Identifier.withDefaultNamespace("basic_zombie"), level.width() + 0.6F, row);
            assertNotNull(walker);
            assertTrue(GRASS_ROWS.contains(walker.gridY()),
                    "a walker asked for water row " + row + " must be moved to land, was "
                            + walker.gridY());
            walker.remove();
        }
        for (int row : WATER_ROWS) {
            ZombieEntity swimmer = level.spawnZombie(
                    Identifier.withDefaultNamespace("ducky_tube_zombie"), level.width() + 0.6F, row);
            assertNotNull(swimmer);
            assertEquals(row, swimmer.gridY(),
                    "and a swimmer that asked for the water stays exactly where it asked");
            swimmer.remove();
        }
    }

    /** The lanes are the codec's business too, and a row off the board is reported. */
    @Test
    void lanesRoundTripThroughJsonAndAreValidated() {
        WaveDef wave = WaveDef.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {
                  "type": "small",
                  "delay": 300,
                  "entries": [ { "id": "pvzce:basic_zombie", "count": 2, "rows": [0, 4] } ]
                }
                """)).getOrThrow();
        assertEquals(List.of(0, 4), wave.entries().get(0).rows());
        assertTrue(wave.entries().get(0).restrictedToRows());

        WaveDef unwritten = WaveDef.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                { "type": "small", "delay": 300,
                  "entries": [ { "id": "pvzce:basic_zombie", "count": 1 } ] }
                """)).getOrThrow();
        assertEquals(List.of(), unwritten.entries().get(0).rows(), "unwritten means any lane");
        assertFalse(unwritten.entries().get(0).restrictedToRows());

        LevelDef broken = poolLevel(List.of(
                new WaveDef(WaveDef.WaveType.SMALL, 300, 5,
                        List.of(new WaveDef.Entry(Id.of("basic_zombie"), 2, List.of(7)),
                                new WaveDef.Entry(Id.of("conehead_zombie"), 0, List.of(1))))),
                List.of());
        List<String> problems = LevelValidator.validateWaves(broken);
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("row 7"), problems.toString());
        assertTrue(problems.get(1).contains("sends none"), problems.toString());
    }

    // ------------------------------------------------------------------
    // Mower kinds
    // ------------------------------------------------------------------

    /** A row named only by a kind still gets a mower, and the kind is what was written. */
    @Test
    void kindsAddRowsAndSayWhatStandsInThem() {
        MowerData data = MowerData.MAP_CODEC.codec().parse(JsonOps.INSTANCE,
                JsonParser.parseString("""
                        { "rows": [0, 1, 4, 5],
                          "kinds": [ { "row": 2, "kind": "pvzce:pool_cleaner",
                                       "sound": "pvzce:sfx/ambient/pool_cleaner" },
                                     { "row": 3, "kind": "pvzce:pool_cleaner" } ] }
                        """)).getOrThrow();

        assertEquals(List.of(0, 1, 2, 3, 4, 5), data.rowsFor(6));
        assertEquals(Identifier.withDefaultNamespace("pool_cleaner"), data.kindFor(2).kind());
        assertTrue(data.kindFor(2).sound().isPresent());
        assertEquals(MowerData.DEFAULT_KIND, data.kindFor(0).kind());
        assertTrue(data.validate(6).isEmpty());

        // A row the board does not have is reported rather than silently dropped.
        MowerData offBoard = new MowerData(Optional.empty(),
                List.of(new MowerData.MowerKind(9, MowerData.DEFAULT_KIND, Optional.empty())));
        assertFalse(offBoard.validate(6).isEmpty());
    }

    /** The rig carries each row's kind, and a pool cleaner sounds like one when it starts. */
    @Test
    void theRigKnowsWhichVehicleIsInWhichRow() {
        WaveDef wave = new WaveDef(WaveDef.WaveType.SMALL, 600, 5,
                List.of(new WaveDef.Entry(Id.of("ducky_tube_zombie"), 1, WATER_ROWS)));
        LevelServer level = new LevelServer(poolLevel(List.of(wave), List.of()));
        Bridge bridge = new Bridge();

        // Walk a zombie up to the house in a water row; the pool cleaner in that row starts.
        ZombieEntity zombie = level.spawnZombie(Id.of("ducky_tube_zombie"), 0.0F, 2);
        assertNotNull(zombie);
        tick(level, bridge, 4000);
        assertTrue(zombie.isRemoved(), "the water row's mower must have run it over");
    }

    /**
     * A floatie in the water paddles; one on the lawn walks.
     *
     * <p>The ducky-tube zombie's own complaint was "it looks like it is walking on the water
     * surface". Nothing about the zombie's rules is different in the water - it is hit, it moves,
     * it bites - so the whole fix is which clip the walk loop publishes, and that is what this
     * checks: {@code swim} in a water cell, {@code walk} on the grass.
     */
    @Test
    void aFloatieInTheWaterPublishesTheSwimState() {
        LevelServer level = new LevelServer(poolLevel(List.of(), List.of()));
        Bridge bridge = new Bridge();

        ZombieEntity floating = level.spawnZombie(Id.of("ducky_tube_zombie"), 4.0F, 2);
        ZombieEntity walking = level.spawnZombie(Id.of("ducky_tube_zombie"), 4.0F, 0);
        assertNotNull(floating);
        assertNotNull(walking);
        tick(level, bridge, 2);

        assertEquals(SubmergeCapability.SWIM_STATE, floating.animation(),
                "a ducky-tube zombie in the pool paddles instead of walking");
        assertTrue(floating.capability(com.pvzce.common.capability.zombie.FloatCapability.class)
                .isFloating());
        assertEquals("walk", walking.animation(),
                "the same body on the lawn walks, so the state follows the cell it is in");
        assertFalse(walking.capability(com.pvzce.common.capability.zombie.FloatCapability.class)
                .isFloating());
    }

    // ------------------------------------------------------------------
    // The watering can
    // ------------------------------------------------------------------

    /**
     * Watering heals the plant, winds its clock forward, and ripens what can ripen.
     *
     * <p>Three effects, and all three are the tool's contract: a plain heal would be a repair
     * click, and the faster clock is what makes watering a plant worth more than replacing it.
     */
    @Test
    void theWateringCanHealsRipensAndSpeedsThePlantUp() {
        LevelServer level = new LevelServer(poolLevel(List.of(),
                List.of(Id.of("watering_can"), Id.of("sun_shroom"))));
        Bridge bridge = new Bridge();
        int slot = slotOf(level, "watering_can");

        PlantDef shroomDef = BuiltInRegistries.PLANTS.get(Id.of("sun_shroom"));
        PlantEntity shroom = level.spawnPlant(shroomDef, level.team(PLANT_TEAM), 1, 0);
        shroom.damage(120);
        assertTrue(shroom.health() < shroomDef.health(), "it must start hurt");

        Object producerBefore = growTicksOf(shroom);
        assertTrue(level.useTool(bridge, slot, 1, 0), "the can must accept a plant");
        assertEquals(shroomDef.health(), shroom.health(), "watering heals it to full");
        assertTrue(shroom.watered(), "and it works faster for a while");
        assertTrue(producerBefore instanceof Integer before && before > 1,
                "the sun-shroom had not grown up yet");
        assertTrue(growTicksOf(shroom) instanceof Integer after && after <= 1,
                "watering ripens it on the spot");
    }

    /** A click on an empty cell is refused, so it costs neither the cooldown nor a use. */
    @Test
    void wateringEmptyGroundIsRefused() {
        LevelServer level = new LevelServer(poolLevel(List.of(),
                List.of(Id.of("watering_can"), Id.of("sun"))));
        Bridge bridge = new Bridge();
        int slot = slotOf(level, "watering_can");

        assertFalse(level.useTool(bridge, slot, 4, 0), "nothing there to water");
        PlantEntity plant = level.spawnPlant(BuiltInRegistries.PLANTS.get(Id.of("pea_shooter")),
                level.team(PLANT_TEAM), 4, 0);
        assertTrue(level.useTool(bridge, slot, 4, 0), "and the same click works once something is");
        assertNotNull(plant);
    }

    // ------------------------------------------------------------------
    // The tangle kelp and the snorkel
    // ------------------------------------------------------------------

    /**
     * The kelp takes exactly one zombie with it, and stops swimming.
     *
     * <p>"One" is the whole balance of the plant: six floaties cost six kelp, and a kelp that
     * kept working would be a lane-wide wall for 25 sun.
     */
    @Test
    void theTangleKelpDragsOneZombieUnder() {
        LevelServer level = new LevelServer(poolLevel(List.of(), List.of()));
        Bridge bridge = new Bridge();
        level.spawnPlant(BuiltInRegistries.PLANTS.get(Id.of("tangle_kelp")),
                level.team(PLANT_TEAM), 4, 2);

        ZombieEntity first = level.spawnZombie(Id.of("ducky_tube_zombie"), 4.45F, 2);
        ZombieEntity second = level.spawnZombie(Id.of("ducky_tube_zombie"), 5.6F, 2);
        tick(level, bridge, 60);

        assertFalse(first.isAlive(), "the first one is dragged under");
        assertEquals(0, first.health(), "and it is the drag that killed it");
        assertNull(level.plantAt(4, 2), "the kelp is spent by the grab");
        assertTrue(second.isAlive(), "and the one behind it keeps coming");
    }

    /** A snorkel is only reachable while it is eating; a gargantuar walks over the kelp. */
    @Test
    void theSnorkelIsUnreachableUntilItSurfaces() {
        LevelServer level = new LevelServer(poolLevel(List.of(), List.of()));
        Bridge bridge = new Bridge();
        ZombieEntity snorkel = level.spawnZombie(Id.of("snorkel_zombie"), 6.5F, 2);
        assertNotNull(snorkel);

        tick(level, bridge, 10);
        assertFalse(snorkel.canBeHitByGround(), "a pea flies over a submerged snorkel");
        assertEquals(SubmergeCapability.SWIM_STATE, snorkel.animation(),
                "and the client is told it is swimming");

        // A plant in the water in front of it: it stands up to bite, and becomes reachable.
        level.spawnPlant(BuiltInRegistries.PLANTS.get(Id.of("lily_pad")),
                level.team(PLANT_TEAM), 6, 2);
        level.spawnPlant(BuiltInRegistries.PLANTS.get(Id.of("pea_shooter")),
                level.team(PLANT_TEAM), 6, 2);
        for (int i = 0; i < 900 && !snorkel.canBeHitByGround(); i++) {
            level.tick(bridge);
        }
        assertTrue(snorkel.canBeHitByGround(), "it surfaces to eat, and can be shot at");
        assertEquals(com.pvzce.api.entity.EntityAnimations.EAT, snorkel.animation());
    }

    private static int slotOf(LevelServer level, String card) {
        return level.plantPlayer().slots().stream()
                .filter(slot -> slot.defId().equals(Id.of(card)))
                .map(slot -> slot.index())
                .findFirst().orElseThrow();
    }

    /** The sun-shroom's remaining growth, read through its producer capability. */
    private static Object growTicksOf(PlantEntity plant) {
        var producer = plant.capability(
                com.pvzce.common.capability.plant.ProducerCapability.class);
        assertNotNull(producer, "the sun-shroom is a producer");
        return growTicksField(producer);
    }

    private static Object growTicksField(Object producer) {
        try {
            var field = producer.getClass().getDeclaredField("growTicks");
            field.setAccessible(true);
            return field.get(producer);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("ProducerCapability.growTicks moved: " + e);
        }
    }

    private static void assertNull(Object value, String message) {
        org.junit.jupiter.api.Assertions.assertNull(value, message);
    }

    /** Short id helper: every id in this file is in the default namespace. */
    private static final class Id {
        static Identifier of(String path) {
            return Identifier.withDefaultNamespace(path);
        }
    }
}
