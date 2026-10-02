package com.pvzce.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.jev.JevSettings;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.level.mechanic.VersusMechanic;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.common.network.packet.ServerMessageS2C;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.ai.JevBrain;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.PvzceEntity;
import com.pvzce.server.entity.ResourceDropEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对战: the economy, the race, and an opponent that is actually driving the level.
 *
 * <p>The levels are built here from JSON rather than loaded from the shipped files, because every
 * case needs different numbers and the point is the mechanism, not the balance - the shipped files
 * are covered by the ordinary level validation that runs over every registered level. Reading them
 * through {@link LevelDef#CODEC} rather than assembling records by hand means the tests also decode
 * the mode block the way a level file does.
 *
 * <p>The last two cases are the ones worth having: an opponent that plays a real move, through the
 * real transport, in a real level - and never outside the columns the level gave it.
 */
class VersusModeTest {
    private static final String PLANT = "pvzce:plant_team";
    private static final String ZOMBIE = "pvzce:zombie_team";

    private HttpServer stub;

    @BeforeAll
    static void load() throws Exception {
        // Content and convention tags together: the placement rules read tags, so a data-only load
        // would leave every cell unplantable.
        TestContent.loadBuiltInContentAndTags();
    }

    @AfterEach
    void stopStub() {
        if (stub != null) {
            stub.stop(0);
            stub = null;
        }
    }

    /** The mode block's fields, as a level file writes them. */
    private record Mode(int sunGoal, int plantSun, int zombieSun, int incomeSun, int incomeTicks,
                        int refundPercent, int decisionTicks) {
    }

    private static Mode defaultMode() {
        return new Mode(0, 50, 150, 0, 240, 0, 60);
    }

    private static LevelDef level(Mode mode, String plantCards, String zombieCards, String waves) {
        String json = """
                {
                  "id": "pvzce:test/versus",
                  "name": "test",
                  "description": "",
                  "width": 9,
                  "height": 2,
                  "teams": [
                    {"id": "pvzce:plant_team", "name": "植物方", "win_condition": "collect_sun"},
                    {"id": "pvzce:zombie_team", "name": "僵尸方", "win_condition": "plant_side_lost"}
                  ],
                  "win_team": "pvzce:plant_team",
                  "playable_teams": ["pvzce:plant_team", "pvzce:zombie_team"],
                  "rules": {
                    "pvzce:day_length": 0,
                    "pvzce:night_length": -1,
                    "pvzce:sun_spawn_interval_min": 0,
                    "pvzce:sun_spawn_interval_max": 0,
                    "pvzce:sun_spawn_initial_ticks": 0
                  },
                  "waves": %s,
                  "slots": [],
                  "max_seed_slots": 8,
                  "seed_screen": false,
                  "initial_sun": 0,
                  "mechanics": [
                    {"type": "pvzce:versus",
                     "sun_goal": %d,
                     "plant_initial_sun": %d,
                     "zombie_initial_sun": %d,
                     "zombie_income_sun": %d,
                     "zombie_income_ticks": %d,
                     "eat_refund_percent": %d,
                     "decision_ticks": %d,
                     "zombie_start_ticks": %d,
                     "plant_cards": %s,
                     "zombie_cards": %s},
                    {"type": "pvzce:placement_zone", "min_x": 0, "max_x": 4},
                    {"type": "pvzce:zombie_zone", "min_x": 5, "max_x": 8}
                  ]
                }
                """.formatted(waves, mode.sunGoal(), mode.plantSun(), mode.zombieSun(),
                mode.incomeSun(), mode.incomeTicks(), mode.refundPercent(), mode.decisionTicks(),
                0, plantCards, zombieCards);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        // The lawn is eighteen grass cells; writing them out in the template would bury the fields
        // the tests are actually about.
        JsonArray grass = new JsonArray();
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 9; x++) {
                grass.add(x + "," + y);
            }
        }
        JsonObject scene = new JsonObject();
        scene.add("pvzce:grass", grass);
        root.add("scene", scene);
        return LevelDef.CODEC.parse(JsonOps.INSTANCE, root).getOrThrow();
    }

    private static LevelDef level(Mode mode) {
        return level(mode, "[\"pvzce:sun\", \"pvzce:sunflower\", \"pvzce:pea_shooter\"]",
                "[\"pvzce:basic_zombie\", \"pvzce:conehead_zombie\"]", "[]");
    }

    private static final class CapturingBridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
        }
    }

    private static void tick(LevelServer level, CapturingBridge bridge, int ticks) {
        for (int i = 0; i < ticks && "running".equals(level.gameState()); i++) {
            level.tick(bridge);
        }
    }

    private static int sunOf(LevelServer level, String team) {
        Team found = level.team(Identifier.parse(team));
        return found == null ? 0 : found.resourcesOf(PvzceIds.SUN);
    }

    @Test
    void thePlantSideWinsByPickingUpItsGoal() {
        LevelDef def = level(new Mode(100, 0, 0, 0, 240, 0, 600));
        LevelServer level = new LevelServer(def);
        CapturingBridge bridge = new CapturingBridge();
        level.tick(bridge);

        for (int i = 0; i < 4; i++) {
            assertTrue(level.dropSkySun(4, 0), "the sky drops a sun");
            // The drop is queued by `addEntity` and joins the board on the next tick, which is also
            // when a player would first see it.
            level.tick(bridge);
            ResourceDropEntity drop = firstDrop(level);
            assertNotNull(drop, "the sun is on the lawn");
            assertTrue(level.autoCollectDrop(drop), "the plant side picks its own sun up");
        }
        tick(level, bridge, 2);
        // Four suns of 25: collected, not granted, which is the whole point of the goal's wording.
        assertEquals(4 * level.rules().getInt(PvzceIds.RULE_SUN_VALUE),
                VersusMechanic.run(level).collected);
        assertEquals("won", level.gameState(),
                "the plant side that has collected its goal has won");
        assertEquals(Identifier.parse(PLANT), level.winner());
    }

    @Test
    void openingSunAndTheGoalAreNotTheSameNumber() {
        // 150 to start with, a goal of 100: a side that has not picked anything up has not won.
        LevelDef def = level(new Mode(100, 150, 150, 0, 240, 0, 600));
        LevelServer level = new LevelServer(def);
        CapturingBridge bridge = new CapturingBridge();
        tick(level, bridge, 5);
        assertEquals(150, sunOf(level, PLANT));
        assertEquals(0, VersusMechanic.run(level).collected);
        assertEquals("running", level.gameState());
    }

    @Test
    void theZombieSideIsPaidOnItsOwnClock() {
        LevelDef def = level(new Mode(0, 0, 0, 25, 240, 0, 600));
        LevelServer level = new LevelServer(def);
        CapturingBridge bridge = new CapturingBridge();
        tick(level, bridge, 239);
        assertEquals(0, sunOf(level, ZOMBIE), "the first payment is not early");
        tick(level, bridge, 1);
        assertEquals(25, sunOf(level, ZOMBIE));
        tick(level, bridge, 240);
        assertEquals(50, sunOf(level, ZOMBIE));
    }

    @Test
    void aZombieThatEatsAPlantIsPaidHalfItsCost() {
        LevelDef def = level(new Mode(0, 0, 0, 0, 240, 50, 600));
        LevelServer level = new LevelServer(def);
        CapturingBridge bridge = new CapturingBridge();
        level.tick(bridge);
        PlantEntity plant = level.spawnPlant(BuiltInRegistries.PLANTS.get(id("sunflower")),
                level.team(Identifier.parse(PLANT)), 4, 0);
        assertNotNull(plant);
        level.spawnZombie(id("basic_zombie"), level.team(Identifier.parse(ZOMBIE)), 4.6F, 0);

        tick(level, bridge, 3000);
        assertTrue(plant.isRemoved(), "the zombie ate the sunflower");
        int cost = BuiltInRegistries.PLANTS.get(id("sunflower")).cost().amountOf(PvzceIds.SUN);
        assertEquals(Math.round(cost * 0.5F), sunOf(level, ZOMBIE),
                "eating is what funds the next zombie");
    }

    @Test
    void theBuiltInOpponentNeverPlaysOutsideItsZone() {
        // The player is the plant side, so the opponent is the zombie side, and the whole point of
        // the zone is that its placements land in the right four columns.
        LevelDef def = level(new Mode(0, 50, 200, 50, 30, 100, 30));
        LevelServer level = new LevelServer(def);
        CapturingBridge bridge = new CapturingBridge();
        tick(level, bridge, 1200);

        List<EntitySpawnS2C> zombies = bridge.packets.stream()
                .filter(EntitySpawnS2C.class::isInstance)
                .map(EntitySpawnS2C.class::cast)
                .filter(spawn -> "zombie".equals(spawn.entityKind()))
                .toList();
        assertFalse(zombies.isEmpty(), "the opponent played something in twenty seconds");
        for (EntitySpawnS2C spawn : zombies) {
            assertTrue(spawn.cellX() >= 5F, "a zombie arrived at x=" + spawn.cellX()
                    + ", left of the zombie zone");
        }
        assertEquals(JevBrain.Status.BUILTIN, level.jevBrain().status(),
                "no key configured means the built-in opponent, and it says so");
    }

    @Test
    void thePlantOpponentBuildsInsideItsOwnZone() {
        LevelDef def = level(new Mode(0, 200, 50, 0, 240, 0, 30));
        // The player takes the zombie side, which is the only way the opponent is the plant side.
        LevelServer level = new LevelServer(def, def.slots(), LevelServer.SeedContext.all(def), null,
                null, Identifier.parse(ZOMBIE));
        CapturingBridge bridge = new CapturingBridge();
        tick(level, bridge, 1200);

        List<PlantEntity> plants = level.entities().stream()
                .filter(PlantEntity.class::isInstance)
                .map(PlantEntity.class::cast)
                .toList();
        assertFalse(plants.isEmpty(), "the plant opponent planted something");
        for (PlantEntity plant : plants) {
            assertTrue(plant.gridX() <= 4, "a plant went up at x=" + plant.gridX()
                    + ", right of the plant zone");
        }
    }

    @Test
    void aRealAnswerFromJevIsPlayedInTheLevel() throws IOException {
        startStubThatEchoesTheFirstOption();
        LevelDef def = level(new Mode(0, 0, 200, 50, 20, 100, 20));
        LevelServer level = new LevelServer(def);
        CapturingBridge bridge = new CapturingBridge();
        level.setJevSettings(new JevSettings(
                "http://127.0.0.1:" + stub.getAddress().getPort() + "/v1/systemone",
                "typesafe/jev-1.13", "test-key"));
        level.tick(bridge);

        long deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline && level.jevBrain().status() != JevBrain.Status.JEV) {
            level.tick(bridge);
        }
        assertEquals(JevBrain.Status.JEV, level.jevBrain().status(),
                "the opponent's move came from Jev, not from the built-in policy");
        assertTrue(level.jevBrain().lastCardId().startsWith("pvzce:"),
                "the opponent remembers what it played: " + level.jevBrain().lastCardId());
        assertTrue(level.entities().stream().anyMatch(ZombieEntity.class::isInstance),
                "and the move is on the board");
    }

    @Test
    void theModeReportsItsOwnMistakes() {
        LevelDef wrongDeck = level(defaultMode(),
                "[\"pvzce:sun\", \"pvzce:pea_shooter\"]",
                "[\"pvzce:pea_shooter\"]", "[]");
        List<String> problems = LevelMechanics.validate(wrongDeck);
        assertTrue(problems.stream().anyMatch(p -> p.contains("among the zombie cards")),
                problems.toString());

        LevelDef noSunCard = level(defaultMode(), "[\"pvzce:pea_shooter\"]",
                "[\"pvzce:basic_zombie\"]", "[]");
        assertTrue(LevelMechanics.validate(noSunCard).stream()
                        .anyMatch(p -> p.contains("pvzce:sun card")),
                "a plant side that cannot collect the sun it races for is reported");

        LevelDef withWaves = level(defaultMode(), "[\"pvzce:sun\"]",
                "[\"pvzce:basic_zombie\"]",
                "[{\"type\": \"final\", \"delay\": 60, \"entries\": []}]");
        assertTrue(LevelMechanics.validate(withWaves).stream().anyMatch(p -> p.contains("waves")),
                "a versus level's zombies come from the other side, not from a wave table");
    }

    /**
     * The shipped levels, played out: does a match end, and roughly when?
     *
     * <p>The plant side is driven by a small script here because there is no AI for the human's
     * side - an economy-first routine (six sunflowers, then a defender in the lane that needs one)
     * is a stand-in for a competent player, which is what the pacing of these levels is calibrated
     * against. The zombie side is the real thing: the same built-in policy a player without a key
     * faces.
     *
     * <p>The bounds are deliberately wide. This is not a balance assertion - a number like "15
     * minutes" cannot be pinned by a test without making every future tuning pass look like a
     * regression - it is a guard that a shipped versus level terminates, and that it terminates
     * somewhere in the range a match is meant to take. The measured numbers are printed.
     */
    @Test
    void theShippedLevelsRunToAnEnd() {
        for (String id : List.of("duel_1", "duel_2", "duel_3")) {
            LevelDef def = BuiltInRegistries.LEVELS.get(
                    Identifier.withDefaultNamespace("yard/versus/" + id));
            assertNotNull(def, id + " is a shipped level");
            LevelServer level = new LevelServer(def, def.slots(), LevelServer.SeedContext.all(def),
                    null, null, Identifier.parse(PLANT));
            CapturingBridge bridge = new CapturingBridge();
            int limit = 60 * 60 * 40;
            int ticks = 0;
            while (ticks < limit && "running".equals(level.gameState())) {
                level.tick(bridge);
                ticks++;
                if (ticks % 60 == 0) {
                    playThePlantSide(level);
                }
            }
            VersusMechanic.Run run = VersusMechanic.run(level);
            int goal = LevelMechanics.versusData(def).orElseThrow().sunGoal();
            System.out.println("VERSUS-SOAK " + id + ": ticks=" + ticks
                    + " minutes=" + (ticks / 60 / 60) + ":" + String.format("%02d", (ticks / 60) % 60)
                    + " state=" + level.gameState() + " winner=" + level.winner()
                    + " collected=" + run.collected + "/" + goal
                    + " fizzled=" + level.jevBrain().status());
            assertFalse("running".equals(level.gameState()),
                    id + " never ended within " + (limit / 60) + " seconds of simulated play");
        }
    }

    /**
     * One tick of a stand-in for a competent plant player.
     *
     * <p>Collect, then build: six sunflowers first, then the cheapest defender that can go in the
     * lane whose frontmost zombie is closest to the house. It is the shape the mode rewards, and it
     * is the shape the shipped goals are meant to be reachable with.
     */
    private static void playThePlantSide(LevelServer level) {
        Team team = level.team(Identifier.parse(PLANT));
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof ResourceDropEntity drop && drop.landed() && !drop.collected()
                    && team.equals(drop.team())) {
                level.autoCollectDrop(drop);
            }
        }
        // Defend a lane that is about to be reached before building the economy further: a player
        // who plants six sunflowers while the first zombies walk in has already lost, and a script
        // that does it would measure the script rather than the level.
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof ZombieEntity zombie && zombie.isAlive() && zombie.cellX() < 7.5F) {
                int lane = zombie.gridY();
                if (shooters(level, lane) == 0) {
                    if (plantInLane(level, team, "pea_shooter", lane, 2)) {
                        return;
                    }
                }
                // A wall at the front of the lane a zombie has almost reached: the zombie stops to
                // eat it, which is the time the shooter behind it needs.
                if (zombie.cellX() < 6.5F && plantInLane(level, team, "wall_nut", lane, 4)) {
                    return;
                }
            }
        }
        int sunflowers = 0;
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof PlantEntity plant && !plant.isRemoved()
                    && "sunflower".equals(plant.defId().path())) {
                sunflowers++;
            }
        }
        if (sunflowers < 6) {
            plant(level, team, "sunflower", 1);
            return;
        }
        // Then defend: a wall at the front of the lane whose frontmost zombie is closest, and a
        // shooter behind it. This is the shape a player would build - the mowers are a free second
        // line, so a lane can be defended thinly and still hold.
        float front = Float.MAX_VALUE;
        int lane = -1;
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof ZombieEntity zombie && zombie.isAlive() && zombie.cellX() < front) {
                front = zombie.cellX();
                lane = zombie.gridY();
            }
        }
        if (lane >= 0) {
            if (!plantInLane(level, team, "pea_shooter", lane, 1)) {
                plantInLane(level, team, "wall_nut", lane, 4);
            }
        }
        // Otherwise spend: a shooter in the lane that has fewest. A player converts income into
        // defense continuously; a script that only fills empty lanes hoards sun and measures the
        // script instead of the level.
        int fewest = Integer.MAX_VALUE;
        int target = -1;
        for (int y = 0; y < level.height(); y++) {
            if (shooters(level, y) < fewest) {
                fewest = shooters(level, y);
                target = y;
            }
        }
        if (target >= 0 && plantInLane(level, team, "pea_shooter", target, 2)) {
            return;
        }
        if (target >= 0) {
            plantInLane(level, team, "wall_nut", target, 4);
        }
    }

    private static int shooters(LevelServer level, int lane) {
        int found = 0;
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof PlantEntity plant && !plant.isRemoved() && plant.gridY() == lane
                    && !"sunflower".equals(plant.defId().path())) {
                found++;
            }
        }
        return found;
    }

    /** Puts one plant in an exact cell, paying for it. */
    private static boolean plantInLane(LevelServer level, Team team, String kind, int lane, int x) {
        var def = BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace(kind));
        if (def == null || lane < 0 || lane >= level.height()) {
            return false;
        }
        int cost = def.cost().amountOf(PvzceIds.SUN);
        if (!level.canPlacePlant(def, x, lane) || team.resourcesOf(PvzceIds.SUN) < cost
                || !team.consume(PvzceIds.SUN, cost)) {
            return false;
        }
        if (level.spawnPlant(def, team, x, lane) != null) {
            return true;
        }
        team.addResource(PvzceIds.SUN, cost);
        return false;
    }

    /** Tries to put one plant of that kind in the back of a lane the level allows. */
    private static boolean plant(LevelServer level, Team team, String kind, int lane) {
        var def = BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace(kind));
        if (def == null) {
            return false;
        }
        int cost = def.cost().amountOf(PvzceIds.SUN);
        if (team.resourcesOf(PvzceIds.SUN) < cost) {
            return false;
        }
        for (int x = 1; x >= 0; x--) {
            if (lane >= 0 && lane < level.height() && level.canPlacePlant(def, x, lane)
                    && team.consume(PvzceIds.SUN, cost)) {
                if (level.spawnPlant(def, team, x, lane) != null) {
                    return true;
                }
                team.addResource(PvzceIds.SUN, cost);
            }
        }
        if (lane >= 0) {
            return false;
        }
        for (int y = 0; y < level.height(); y++) {
            for (int x = 1; x >= 0; x--) {
                if (level.canPlacePlant(def, x, y) && team.consume(PvzceIds.SUN, cost)) {
                    if (level.spawnPlant(def, team, x, y) != null) {
                        return true;
                    }
                    team.addResource(PvzceIds.SUN, cost);
                }
            }
        }
        return false;
    }

    /**
     * The zone binds the player too, not only the opponent.
     *
     * <p>The level's own `placeZombie` is the path a human's click takes, and the rule "zombies go
     * in the four right columns" is the level's statement - so it has to hold for a card the player
     * is holding exactly as it holds for a decision that came over the network.
     */
    @Test
    void theHumanZombieSideCannotPlaceOutsideItsZone() {
        LevelDef def = level(new Mode(0, 200, 200, 0, 240, 0, 600));
        LevelServer level = new LevelServer(def, def.slots(), LevelServer.SeedContext.all(def), null,
                null, Identifier.parse(ZOMBIE));
        CapturingBridge bridge = new CapturingBridge();
        level.tick(bridge);

        assertTrue(level.placeZombie(bridge, 0, 5, 0),
                "a zombie card on the zombie side's own half is accepted");
        assertFalse(level.placeZombie(bridge, 0, 4, 0),
                "the same card one column to the left is refused");
        // Spawning is queued and joins the board on the next tick, so the count is taken after one.
        level.tick(bridge);
        long zombies = level.entities().stream().filter(ZombieEntity.class::isInstance).count();
        assertEquals(1, zombies, "the refused click spawned nothing");
        assertTrue(bridge.packets.stream()
                        .filter(ServerMessageS2C.class::isInstance)
                        .map(ServerMessageS2C.class::cast)
                        .anyMatch(message -> message.message().startsWith("gui.pvzce.versus.")),
                "the refusal is a language key the client resolves, not a sentence the server wrote");
    }

    /**
     * A versus run survives being saved and resumed, and it resumes as the same side.
     *
     * <p>Two facts live in the save: the run state (sun collected, the income clock, the build
     * window) and which side the player was on. The second is read <em>before</em> the level is
     * constructed - the side is a constructor input, and a level re-seated afterwards would resume
     * with the other side's card bar.
     */
    @Test
    void aRunResumesWithItsOwnProgressAndItsOwnSide() {
        // The zombie side holds enough sun to act: a side that cannot place anything at all is
        // "stuck" by the ordinary end condition, and the run would end before it could be saved.
        LevelDef def = level(new Mode(500, 0, 200, 25, 240, 0, 600));
        LevelServer level = new LevelServer(def, def.slots(), LevelServer.SeedContext.all(def), null,
                null, Identifier.parse(ZOMBIE));
        CapturingBridge bridge = new CapturingBridge();
        level.tick(bridge);
        for (int i = 0; i < 3; i++) {
            level.dropSkySun(4, 0);
            level.tick(bridge);
            // The plant team owns the sun, so a player on the zombie side cannot pick it up; the
            // counter is driven directly for this test's purpose.
            VersusMechanic.run(level).collected += 25;
        }
        assertEquals(75, VersusMechanic.run(level).collected);
        CompoundTag save = level.save();

        LevelServer resumed = new LevelServer(def, def.slots(), LevelServer.SeedContext.all(def),
                null, null, LevelServer.humanTeamFromSave(def, save));
        resumed.restore(save);
        assertEquals(75, VersusMechanic.run(resumed).collected,
                "the race does not restart from zero when a match is continued");
        assertEquals(Identifier.parse(ZOMBIE), resumed.humanTeamId(),
                "and the player is still on the side they were playing");
        assertEquals("running", resumed.gameState());
    }

    private static ResourceDropEntity firstDrop(LevelServer level) {
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof ResourceDropEntity drop && !drop.collected()) {
                return drop;
            }
        }
        return null;
    }

    private static Identifier id(String path) {
        return Identifier.withDefaultNamespace(path);
    }

    /**
     * A stand-in for Jev that answers "the first option you offered", for every question.
     *
     * <p>It reads the request rather than hard-coding an answer, which is what makes the test
     * independent of the deck: whatever card this level lists first is the card that comes back, so
     * the assertion is about the path (ask - answer - re-check - spawn) and not about a fixture.
     */
    private void startStubThatEchoesTheFirstOption() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/v1/systemone", exchange -> {
            String body = readBody(exchange);
            JsonObject request = JsonParser.parseString(body).getAsJsonObject();
            JsonObject questions = request.getAsJsonObject("questions");
            JsonObject answers = new JsonObject();
            answers.add("action", choice(firstKey(questions.getAsJsonObject("action"))));
            answers.add("row", choice(firstKey(questions.getAsJsonObject("row"))));
            answers.add("column", choice(firstKey(questions.getAsJsonObject("column"))));
            JsonObject response = new JsonObject();
            response.addProperty("model", "stub");
            response.add("answers", answers);
            respond(exchange, response.toString());
        });
        stub.start();
    }

    private static String firstKey(JsonObject question) {
        return question.getAsJsonObject("criteria").keySet().iterator().next();
    }

    private static JsonObject choice(String key) {
        JsonObject answer = new JsonObject();
        answer.addProperty("type", "choice");
        answer.addProperty("choice", key);
        answer.addProperty("confidence", 0.9D);
        return answer;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
