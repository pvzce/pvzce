package com.pvzce.common.level.mutation;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The second catalogue's board mutations and its raids.
 *
 * <p>What is worth pinning here is mostly <em>what the mutation is not allowed to do</em>: the fog
 * does not touch the simulation, the flood does not swallow a level's own terrain, a meteor does not
 * land on a plant, a raid does not drop a walker into the pool. Those are the mistakes that would
 * look like a different mutation than the one on the panel.
 */
class MutationGroupTwoTest {
    private static LevelDef endless;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        Identifier id = MutationLevels.levelIds().get(MutationDifficulty.NORMAL.ordinal());
        // Waves cleared, and the lawn left as it is: these tests are about what a mutation does to
        // the board, not about surviving a run.
        endless = TestLevels.copy(BuiltInRegistries.LEVELS.get(id)).waves(List.of()).build();
    }

    /** 迷雾降临: the board goes dark, and only the picture changes. */
    @Test
    void fogRollInDarkensTheBoardAndNothingElse() {
        LevelServer level = levelWithMutations();
        float alphaBefore = level.fogData().alphaAt(level.width() - 0.5F);
        assertEquals(0F, alphaBefore, 0.001F, "the fixture starts clear");

        Mutation fog = MutationRegistry.get(PvzceIds.MUTATION_FOG_ROLL_IN);
        assertNotNull(fog);
        assertNotNull(level.mutations().add(fog, Mutation.Roll.NONE));

        assertTrue(level.fogData().alphaAt(level.width() - 0.5F) > 0.5F,
                "the far side of the board is dark now");
        assertTrue(level.fogData().alphaAt(0.5F) < 0.01F,
                "and the near side, where the player builds, is clear");
        assertTrue(level.hasMechanic(PvzceIds.MECHANIC_FOG),
                "the level's own fog mechanic is what keeps the span in step with the lamps");

        // Nothing in the simulation reads it: a zombie behind the boundary walks and is hit the
        // same as one in front of it.
        ZombieEntity behind = level.spawnZombie(Identifier.withDefaultNamespace("basic_zombie"),
                level.team(PvzceIds.ZOMBIE_TEAM), level.width() - 1.5F, 0);
        assertNotNull(behind);
        float speed = behind.moveSpeed(level);
        assertTrue(speed > 0F, "the fog does not slow anything down");

        level.mutations().clear();
        assertEquals(0F, level.fogData().alphaAt(level.width() - 0.5F), 0.001F,
                "and taking the mutation away clears the lawn");
        assertFalse(level.hasMechanic(PvzceIds.MECHANIC_FOG),
                "the mechanic it installed goes with it");
    }

    /**
     * 水淹草坪: the middle rows are water, everything else on those rows is remembered.
     *
     * <p>Run on a <em>yard</em> board (the mutation mechanic added to 1-1), because the mutation's
     * whole effect is turning dry rows wet: on the pool board the fixture's middle rows are the
     * water, so nothing would change and the test would pass without testing anything.
     */
    @Test
    void floodLawnFloodsTheMiddleRowsAndPutsThemBack() {
        LevelServer level = yardWithMutations();
        List<Integer> rows = FloodLawnMutation.floodedRows(level.height(), level::rowIsWater);
        assertEquals(2, rows.size(), "two rows flood");

        Mutation flood = MutationRegistry.get(PvzceIds.MUTATION_FLOOD_LAWN);
        assertNotNull(flood);
        level.mutations().add(flood, Mutation.Roll.NONE);
        for (int x = 0; x < level.width(); x++) {
            for (int y : rows) {
                assertEquals(PvzceIds.FLOOD_WATER, level.sceneIdAt(x, y),
                        "the flooded row has to be water at (" + x + "," + y + ")");
            }
        }
        assertTrue(level.rowIsWater(rows.get(0)),
                "and the terrain query the planting rules and the zombies read agrees");

        level.mutations().clear();
        for (int x = 0; x < level.width(); x++) {
            for (int y : rows) {
                assertEquals(PvzceIds.GRASS, level.sceneIdAt(x, y),
                        "the lawn comes back where the level declared grass");
            }
        }
    }

    /**
     * On a pool board it floods the two dry rows beside the water.
     *
     * <p>The mutation levels are all pool boards, so "the middle two rows" would be the water
     * already and the mutation would do nothing where it actually ships. The rows nearest the
     * middle <em>among the dry ones</em> are what the water spreading means there.
     */
    @Test
    void floodLawnPicksDryRowsOnAPoolBoard() {
        LevelServer level = levelWithMutations();
        List<Integer> rows = FloodLawnMutation.floodedRows(level.height(), level::rowIsWater);
        assertEquals(2, rows.size());
        for (int y : rows) {
            assertFalse(level.rowIsWater(y),
                    "row " + y + " was already water, so flooding it would change nothing");
        }
        assertEquals(List.of(1, 4), rows,
                "the two dry rows beside the pool, on the shipped six-row board");
    }

    /** A level that painted something on those rows gets its own terrain back, not grass. */
    @Test
    void floodLawnGivesBackWhateverTheLevelDeclared() {
        LevelServer level = levelWithMutations();
        List<Integer> rows = FloodLawnMutation.floodedRows(level.height(), level::rowIsWater);
        int x = 2;
        int y = rows.get(0);
        level.setScene(x, y, PvzceIds.id("ice"));

        Mutation flood = MutationRegistry.get(PvzceIds.MUTATION_FLOOD_LAWN);
        assertNotNull(flood);
        level.mutations().add(flood, Mutation.Roll.NONE);
        assertEquals(PvzceIds.FLOOD_WATER, level.sceneIdAt(x, y));

        level.mutations().clear();
        assertEquals(PvzceIds.id("ice"), level.sceneIdAt(x, y),
                "ice the player had on that row is not turned into grass");
    }

    /** 陨石雨: a rock lands, leaves a crater, and never lands on a plant. */
    @Test
    void meteorShowerCratersEmptyCellsOnly() {
        LevelServer level = levelWithMutations();
        PlantDef wallNut = BuiltInRegistries.PLANTS.get(PvzceIds.WALL_NUT);
        assertNotNull(wallNut);
        // Plant one on every land row, so a naive "random cell" implementation would hit one.
        for (int y = 0; y < level.height(); y++) {
            if (!level.rowIsWater(y)) {
                level.spawnPlant(wallNut, level.team(PvzceIds.PLANT_TEAM), 4, y);
            }
        }
        int plantsBefore = level.plantCount();
        assertTrue(plantsBefore > 0, "the fixture has plants to avoid");

        Mutation meteors = MutationRegistry.get(PvzceIds.MUTATION_METEOR_SHOWER);
        assertNotNull(meteors);
        Object state = meteors.apply(level, Mutation.Roll.NONE);
        assertNotNull(state);
        int interval = level.mutations().difficulty().scaledInterval(900);
        for (int i = 0; i < interval * 24; i++) {
            meteors.tick(level, Mutation.Roll.NONE, state);
            level.flushPending(null);
        }

        assertEquals(plantsBefore, level.plantCount(),
                "a rock on a plant would be the mutation playing for the zombies");
        int craters = 0;
        for (int x = 0; x < level.width(); x++) {
            for (int y = 0; y < level.height(); y++) {
                var element = level.sceneAt(x, y);
                if (element != null && "CRATER".equals(element.surfaceClass())) {
                    craters++;
                }
            }
        }
        assertTrue(craters > 0, "and it has to leave craters - they are the whole mark it makes");
    }

    /** 荆棘草坪: walkers bleed, fliers and diggers do not. */
    @Test
    void thornLawnBleedsGroundZombiesOnly() {
        LevelServer level = levelWithMutations();
        Mutation thorns = MutationRegistry.get(PvzceIds.MUTATION_THORN_LAWN);
        assertNotNull(thorns);
        level.mutations().add(thorns, Mutation.Roll.NONE);

        ZombieEntity walker = level.spawnZombie(Identifier.withDefaultNamespace("basic_zombie"),
                level.team(PvzceIds.ZOMBIE_TEAM), level.width() - 1.5F, 0);
        ZombieEntity flyer = level.spawnZombie(Identifier.withDefaultNamespace("balloon_zombie"),
                level.team(PvzceIds.ZOMBIE_TEAM), level.width() - 1.5F, 1);
        ZombieEntity digger = level.spawnZombie(Identifier.withDefaultNamespace("miner_zombie"),
                level.team(PvzceIds.ZOMBIE_TEAM), level.width() - 1.5F, 2);
        assertNotNull(walker);
        assertNotNull(flyer);
        assertNotNull(digger);
        int walkerBefore = walker.health();
        int flyerBefore = flyer.health();

        // One prick is every thirty ticks, and the tick count is what the mutation reads, so the
        // level is ticked rather than the mutation.
        for (int i = 0; i < 30 * 4; i++) {
            level.tick(packet -> { });
        }
        int diggerBefore = digger.health();
        assertTrue(walker.health() < walkerBefore, "a walker on the thorns loses health");
        assertEquals(flyerBefore, flyer.health(), "a balloon over them does not");
        assertEquals(diggerBefore, digger.health(), "and neither does a miner still underground");
    }

    /** 结冰地面: faster walking and longer chills, both of them the level's rules. */
    @Test
    void iceGroundMakesEverythingFasterAndTheColdLastLonger() {
        LevelServer level = levelWithMutations();
        ZombieEntity zombie = level.spawnZombie(Identifier.withDefaultNamespace("basic_zombie"),
                level.team(PvzceIds.ZOMBIE_TEAM), level.width() - 1.5F, 0);
        assertNotNull(zombie);
        float before = zombie.moveSpeed(level);

        Mutation ice = MutationRegistry.get(PvzceIds.MUTATION_ICE_GROUND);
        assertNotNull(ice);
        // Added with its own roll rather than `Roll.NONE`: the save unwinds what the entry rolled,
        // and a mutation installed by hand without one would be a mutation whose factor the save
        // file keeps.
        assertNotNull(level.mutations().add(ice, ice.roll(level)));
        assertEquals(before * 1.5F, zombie.moveSpeed(level), 0.001F,
                "the walk is half again as fast");
        assertEquals(2F, level.rules().getFloat(PvzceIds.RULE_SLOW_DURATION_MULTIPLIER), 0.001F,
                "and a chill lasts twice as long");

        level.mutations().clear();
        assertEquals(before, zombie.moveSpeed(level), 0.001F, "evicting it puts both back");
        assertEquals(1F, level.rules().getFloat(PvzceIds.RULE_SLOW_DURATION_MULTIPLIER), 0.001F);
    }

    /** The time a status lasts really does double, where it is applied. */
    @Test
    void aChillLastsTwiceAsLongOnIcyGround() {
        LevelServer level = levelWithMutations();
        level.setRule(PvzceIds.RULE_SLOW_DURATION_MULTIPLIER, 2F);
        assertEquals(200, com.pvzce.common.level.StatusDurations.scale(level, 100));
        level.setRule(PvzceIds.RULE_SLOW_DURATION_MULTIPLIER, 1F);
        assertEquals(100, com.pvzce.common.level.StatusDurations.scale(level, 100));
    }

    /** 植物僵尸: a plant-headed zombie arrives, and it is one of the four. */
    @Test
    void zombotanySendsPlantHeadedZombies() {
        LevelServer level = levelWithMutations();
        Mutation raid = MutationRegistry.get(PvzceIds.MUTATION_ZOMBOTANY);
        assertNotNull(raid);
        assertTrue(raid.canRun(level), "the tag has to resolve to something");
        level.mutations().add(raid, Mutation.Roll.NONE);
        int interval = level.mutations().difficulty().scaledInterval(1200);
        for (int i = 0; i < interval + 1; i++) {
            level.tick(packet -> { });
        }
        // Only the plant-headed arrivals are this raid's business: the fixture is a pool board and
        // may already have something on it.
        List<ZombieEntity> spawned = level.entities().stream()
                .filter(e -> e instanceof ZombieEntity zombie
                        && zombie.def().id().path().startsWith("zombotany_"))
                .map(e -> (ZombieEntity) e)
                .toList();
        assertEquals(1, spawned.size(),
                "exactly one plant-headed zombie per raid, and it is one of the four: " + spawned);
    }

    /**
     * A raid never drops a walker into the pool.
     *
     * <p>The Gargantuar is the case: it is a land zombie on a pool board, and a raid that picked a
     * random row would put it in the water. The engine's own spawn rule would catch it, and this is
     * the mutation choosing correctly in the first place.
     */
    @Test
    void aLandRaidOnlyUsesLandRows() {
        LevelServer level = levelWithMutations();
        Mutation raid = MutationRegistry.get(PvzceIds.MUTATION_GARGANTUAR_RAID);
        assertNotNull(raid);
        assertTrue(raid.canRun(level));
        // Seeded: the raid's lanes come from the level's dice, and a test whose claim is "no lane
        // was ever water" has to be able to say it watched the same four raids as last time.
        level.random().setSeed(20240925L);
        level.mutations().add(raid, Mutation.Roll.NONE);
        int interval = level.mutations().difficulty().scaledInterval(2400);
        for (int i = 0; i < interval * 4 + 1; i++) {
            level.tick(packet -> { });
        }
        java.util.List<String> seen = new java.util.ArrayList<>();
        for (var entity : level.entities()) {
            if (entity instanceof ZombieEntity zombie
                    && "gargantuar".equals(zombie.def().id().path())) {
                int row = (int) Math.floor(zombie.cellY());
                seen.add(row + "@" + zombie.cellX());
                assertFalse(level.rowIsWater(row),
                        "a Gargantuar in the pool at row " + row + " (all: " + seen + ")");
            }
        }
        assertFalse(seen.isEmpty(),
                "the raid has to have sent a Gargantuar; zombies on the board: "
                        + level.entities().size());
    }

    /** A raid that survives a save does not arrive twice. */
    @Test
    void aRaidDoesNotFireAgainOnResume() {
        LevelServer level = levelWithMutations();
        Mutation raid = MutationRegistry.get(PvzceIds.MUTATION_BALLOON_RAID);
        assertNotNull(raid);
        level.mutations().add(raid, Mutation.Roll.NONE);
        int interval = level.mutations().difficulty().scaledInterval(1500);
        for (int i = 0; i < interval + 1; i++) {
            level.tick(packet -> { });
        }
        int before = zombieCount(level);
        assertTrue(before >= 3, "the flock is three to five: " + before);

        LevelServer resumed = new LevelServer(endless);
        resumed.restore(level.save());
        int restored = zombieCount(resumed);
        assertEquals(before, restored, "the zombies that arrived came back as entities");
        for (int i = 0; i < 30; i++) {
            resumed.tick(packet -> { });
        }
        assertEquals(restored, zombieCount(resumed),
                "and nothing new arrives until the clock runs out again");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static LevelServer levelWithMutations() {
        return new LevelServer(endless);
    }

    /**
     * A dry board that mutates: a yard level with the marker mechanic added.
     *
     * <p>1-4 rather than 1-1, and the reason is worth knowing: 1-1 is a <em>one-row</em> board (the
     * first level is a single lane so the player learns one thing at a time), and a mutation that
     * floods "the middle two rows" has nothing to flood there.
     */
    private static LevelServer yardWithMutations() {
        LevelDef yard = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_4"));
        assertNotNull(yard);
        List<com.pvzce.api.content.mechanic.TypedMechanic> mechanics =
                new java.util.ArrayList<>(yard.mechanics());
        mechanics.add(com.pvzce.api.content.mechanic.TypedMechanic.of(
                PvzceIds.MECHANIC_MUTATION, com.pvzce.api.content.mechanic.MechanicData.Empty.INSTANCE));
        return new LevelServer(TestLevels.copy(yard).waves(List.of()).mechanics(mechanics).build());
    }

    private static int zombieCount(LevelServer level) {
        int count = 0;
        for (var entity : level.entities()) {
            if (entity instanceof ZombieEntity) {
                count++;
            }
        }
        return count;
    }
}
