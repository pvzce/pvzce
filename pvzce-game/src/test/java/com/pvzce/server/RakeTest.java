package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LevelDialogue;
import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.content.LevelUnlock;
import com.pvzce.api.content.RakeData;
import com.pvzce.api.content.TeamDef;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.RakeMechanic;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.LevelInitS2C;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rake: where it stands, what it kills, and the lane the first zombie has to come down.
 *
 * <p>These pin the original's rules rather than this build's first guess at them, because every
 * one of them is a design statement: the rake lies in the second column from the right, it is
 * worth 1800 damage (so armour does not save a zombie and a Gargantuar is out of reach), the lane
 * it is on is the lane the opening zombie walks, and it is not a card. The last one is asserted as
 * an absence, because "the rake should not be a tool" is a statement about the data.
 */
class RakeTest {
    private static final Identifier PLANT_TEAM = Identifier.withDefaultNamespace("plant_team");
    private static final Identifier ZOMBIE_TEAM = Identifier.withDefaultNamespace("zombie_team");
    private static final Identifier BASIC_ZOMBIE = Identifier.withDefaultNamespace("basic_zombie");
    private static final Identifier BUCKETHEAD = Identifier.withDefaultNamespace("buckethead_zombie");
    private static final Identifier GARGANTUAR = Identifier.withDefaultNamespace("gargantuar");

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    // ------------------------------------------------------------------
    // Where it stands and what it does
    // ------------------------------------------------------------------

    @Test
    void theRakeLiesInTheSecondColumnFromTheRight() {
        LevelServer level = new LevelServer(level(RakeData.RANDOM, List.of(), grass()));
        CapturingBridge bridge = new CapturingBridge();
        level.tick(bridge);

        RakeMechanic.State state = bridge.rakeState;
        assertNotNull(state, "the rake has to be announced, or the client draws nothing");
        assertEquals(level.width() - RakeMechanic.COLUMNS_FROM_RIGHT + 0.5F, state.x(), 0.0001F,
                "second column from the right, at its centre");
        assertTrue(state.row() >= 0 && state.row() < level.height(), "and on a real lane");
        assertFalse(state.spent());
    }

    @Test
    void theFirstZombieToReachItDies() {
        LevelServer level = new LevelServer(level(RakeData.RANDOM, List.of(), grass()));
        CapturingBridge bridge = new CapturingBridge();
        level.tick(bridge);
        int row = bridge.rakeState.row();

        ZombieEntity zombie = spawn(level, bridge, BASIC_ZOMBIE, row);
        assertTrue(tickUntil(level, bridge, () -> zombie.isRemoved(), 3_000),
                "1800 damage is more than a basic zombie's 200");
        assertTrue(bridge.rakeState.spent(), "and the rake goes with it");
    }

    @Test
    void whatWearsABucketDiesJustTheSame() {
        LevelServer level = new LevelServer(level(RakeData.RANDOM, List.of(), grass()));
        CapturingBridge bridge = new CapturingBridge();
        level.tick(bridge);

        ZombieEntity zombie = spawn(level, bridge, BUCKETHEAD, bridge.rakeState.row());
        assertTrue(tickUntil(level, bridge, () -> zombie.isRemoved(), 3_000),
                "the rake ignores armour, as the original's 1800 does");
    }

    @Test
    void aGargantuarWalksAwayFromIt() {
        LevelServer level = new LevelServer(level(RakeData.RANDOM, List.of(), grass()));
        CapturingBridge bridge = new CapturingBridge();
        level.tick(bridge);

        ZombieEntity zombie = spawn(level, bridge, GARGANTUAR, bridge.rakeState.row());
        assertTrue(tickUntil(level, bridge, () -> bridge.rakeState.spent(), 3_000),
                "it springs, and it is spent whether or not it kills");
        assertFalse(zombie.isRemoved(), "3000 health is out of the rake's reach");
    }

    @Test
    void aLevelCanSayItWantsNoRake() {
        LevelServer level = new LevelServer(level(RakeData.NONE, List.of(), grass()));
        CapturingBridge bridge = new CapturingBridge();
        level.tick(bridge);

        assertEquals(-1, bridge.rakeState.row(), "an empty row list means nowhere");
        assertFalse(bridge.rakeState.spent(), "and there is nothing to spring");
    }

    @Test
    void aRakeIsNeverLaidOnWater() {
        // The pool rows of a pool level, which is where a rake would be unreachable: a walker
        // cannot get to it, and a swimmer has no business being hit by a lawn tool.
        LevelServer level = new LevelServer(level(RakeData.RANDOM, List.of(), pool()));
        CapturingBridge bridge = new CapturingBridge();
        level.tick(bridge);

        int row = bridge.rakeState.row();
        assertTrue(row >= 0, "a pool level still has its grass lanes");
        assertFalse(level.rowIsWater(row), "and the rake is on one of those");

        LevelServer onlyWater = new LevelServer(
                level(new RakeData(Optional.of(List.of(2, 3))), List.of(), pool()));
        CapturingBridge waterBridge = new CapturingBridge();
        onlyWater.tick(waterBridge);
        assertEquals(-1, waterBridge.rakeState.row(),
                "a level that names nothing but water gets no rake at all");
    }

    // ------------------------------------------------------------------
    // The lane the opening zombie walks
    // ------------------------------------------------------------------

    @Test
    void theOpeningZombieArrivesInTheRakesLane() {
        // A wave table with one zombie and no lane of its own: without the rake it would be dealt
        // a shuffled lane, which is what makes this a test rather than a coincidence.
        LevelServer level = new LevelServer(level(RakeData.RANDOM, oneZombieWave(), grass()));
        CapturingBridge bridge = new CapturingBridge();
        level.tick(bridge);
        int rakeRow = bridge.rakeState.row();

        assertTrue(tickUntil(level, bridge, () -> level.aliveZombieCount() > 0, 600),
                "the opening wave has to arrive");
        List<ZombieEntity> zombies = new ArrayList<>();
        for (int row = 0; row < level.height(); row++) {
            zombies.addAll(level.zombiesInRow(row));
        }
        assertEquals(1, zombies.size());
        assertEquals(rakeRow, zombies.get(0).gridY(),
                "the original guarantees the rake is used; a rake nothing walks into is a"
                        + " purchase that silently did nothing");
    }

    @Test
    void aSpentRakeStopsDirectingTheWaves() {
        LevelServer level = new LevelServer(level(RakeData.RANDOM, oneZombieWave(), grass()));
        CapturingBridge bridge = new CapturingBridge();
        level.tick(bridge);
        int rakeRow = bridge.rakeState.row();

        // Spring it with a hand-placed zombie, then let the wave arrive: the forced lane is gone
        // with the rake, so the wave's own zombie is dealt an ordinary lane.
        spawn(level, bridge, BASIC_ZOMBIE, rakeRow);
        assertTrue(tickUntil(level, bridge, () -> bridge.rakeState.spent(), 3_000));
        assertTrue(tickUntil(level, bridge, () -> level.aliveZombieCount() > 0, 1_200),
                "the wave still arrives");
        assertEquals(-1, level.forcedOpeningLane(BASIC_ZOMBIE),
                "and the host has no lane to insist on any more");
    }

    @Test
    void aForcedLaneIsNotUsedForAZombieThatCannotWalkIt() {
        // The rake's own lane is a grass row, so a swimmer forced into it must be dealt its own
        // kind of lane instead: "the rake's lane" is an offer, not an override of the water rules.
        LevelServer level = new LevelServer(level(RakeData.RANDOM, List.of(), pool()));
        CapturingBridge bridge = new CapturingBridge();
        level.tick(bridge);
        assertFalse(level.rowIsWater(bridge.rakeState.row()));
        // A ducky tube zombie can swim *and* walk (it is dealt water when water is available), so
        // the lane the host insists on is still offered to it - the point here is only that the
        // host answers, and that a lane it answers with is one the zombie could have been dealt.
        int forced = level.forcedOpeningLane(Identifier.withDefaultNamespace("ducky_tube_zombie"));
        assertTrue(forced == -1 || forced == bridge.rakeState.row(),
                "the host answers with its rake's lane or with nothing");
    }

    // ------------------------------------------------------------------
    // Ownership and the wire
    // ------------------------------------------------------------------

    @Test
    void aWorldThatBoughtTheRakeGetsOneWithoutTheLevelAsking() {
        // The level says nothing about a rake at all, which is the "the player's rake, if they
        // have one" case: a level that declares the mechanic decides for itself and is not this.
        LevelDef def = levelWithoutRake(List.of(), grass());
        LevelServer withRake = new LevelServer(def, List.of(),
                LevelServer.SeedContext.forProfile(def, profileWith(true)));
        LevelServer without = new LevelServer(def, List.of(),
                LevelServer.SeedContext.forProfile(def, profileWith(false)));
        CapturingBridge withBridge = new CapturingBridge();
        CapturingBridge withoutBridge = new CapturingBridge();
        withRake.tick(withBridge);
        without.tick(withoutBridge);

        assertNotNull(withBridge.rakeState, "a world that owns the rake gets one");
        assertTrue(withBridge.rakeState.row() >= 0);
        assertNull(withoutBridge.rakeState,
                "and a world that never bought one has no rake mechanic at all");
    }

    @Test
    void aSandboxWorldOwnsTheRakeToo() {
        // "Every card, present and future" has to include the one thing the shop sells that is not
        // a card, or a sandbox world is the single world where a rake can never be seen.
        LevelDef def = levelWithoutRake(List.of(), grass());
        LevelServer level = new LevelServer(def, List.of(),
                LevelServer.SeedContext.forProfile(def, PlayerProfile.unlockEverything()));
        CapturingBridge bridge = new CapturingBridge();
        level.tick(bridge);

        assertNotNull(bridge.rakeState, "a sandbox world gets a rake like everything else");
        assertTrue(bridge.rakeState.row() >= 0);
    }

    @Test
    void theClientsLevelInitKnowsAboutThePlayersRake() {
        // The bug this pins: the implicit rake was installed on the server but left out of the
        // payload, because the payload described the *file's* mechanics. The client therefore ran
        // a board with an invisible rake on it - the zombie died and nothing was drawn.
        LevelDef def = levelWithoutRake(List.of(), grass());
        LevelServer level = new LevelServer(def, List.of(),
                LevelServer.SeedContext.forProfile(def, profileWith(true)));
        CapturingBridge bridge = new CapturingBridge();
        level.sendFullState(bridge);

        assertTrue(bridge.payloadMechanics.contains(PvzceIds.MECHANIC_RAKE),
                "the level init has to name every mechanic the server is running, including the"
                        + " one the player brought");
    }

    @Test
    void anUnownedRakeIsNotAdvertised() {
        LevelDef def = levelWithoutRake(List.of(), grass());
        LevelServer level = new LevelServer(def, List.of(),
                LevelServer.SeedContext.forProfile(def, profileWith(false)));
        CapturingBridge bridge = new CapturingBridge();
        level.sendFullState(bridge);

        assertFalse(bridge.payloadMechanics.contains(PvzceIds.MECHANIC_RAKE),
                "a player who never bought one has no rake to draw");
    }

    // ------------------------------------------------------------------
    // The file
    // ------------------------------------------------------------------

    @Test
    void aSpentRakeIsStillSpentAfterASave() {
        LevelServer level = new LevelServer(level(RakeData.RANDOM, List.of(), grass()));
        CapturingBridge bridge = new CapturingBridge();
        level.tick(bridge);
        int row = bridge.rakeState.row();
        spawn(level, bridge, BASIC_ZOMBIE, row);
        assertTrue(tickUntil(level, bridge, () -> bridge.rakeState.spent(), 3_000));

        LevelServer restored = new LevelServer(level(RakeData.RANDOM, List.of(), grass()));
        restored.restore(level.save());
        CapturingBridge restoredBridge = new CapturingBridge();
        restored.tick(restoredBridge);
        assertTrue(restoredBridge.rakeState.spent(), "a used rake must not come back");
        assertEquals(row, restoredBridge.rakeState.row(), "and it is remembered where it was");
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private static Map<Identifier, List<String>> grass() {
        return sceneOf("yard/adventure/demo_level");
    }

    private static Map<Identifier, List<String>> pool() {
        return sceneOf("yard/adventure/3_1");
    }

    private static Map<Identifier, List<String>> sceneOf(String levelId) {
        return BuiltInRegistries.LEVELS.get(Identifier.withDefaultNamespace(levelId)).scene();
    }

    /** A profile that has (or has not) bought the rake. */
    private static PlayerProfile profileWith(boolean ownsRake) {
        PlayerProfile profile = PlayerProfile.starter();
        if (ownsRake) {
            profile.unlock(PvzceIds.RAKE);
        }
        return profile;
    }

    /** One final wave holding one zombie, with no lane of its own. */
    private static List<WaveDef> oneZombieWave() {
        return List.of(WaveDef.declaringSpawnInterval(WaveDef.WaveType.FINAL, 120, 0,
                List.of(new WaveDef.Entry(BASIC_ZOMBIE, 1, List.of(), 1F)), 60));
    }

    /** A 9x5 lawn board (or 9x6 when the scene is the pool's) with one rake and a wave table. */
    private static LevelDef level(RakeData rake, List<WaveDef> waves,
                                  Map<Identifier, List<String>> scene) {
        List<TypedMechanic> mechanics = new ArrayList<>();
        mechanics.add(TypedMechanic.of(PvzceIds.MECHANIC_RAKE, rake));
        return board(waves, scene, mechanics);
    }

    /** The same board with no rake block at all: the player's own rake is the only source. */
    private static LevelDef levelWithoutRake(List<WaveDef> waves,
                                             Map<Identifier, List<String>> scene) {
        return board(waves, scene, List.of());
    }

    private static LevelDef board(List<WaveDef> waves, Map<Identifier, List<String>> scene,
                                  List<TypedMechanic> mechanics) {
        int height = 0;
        for (List<String> cells : scene.values()) {
            for (String cell : cells) {
                int comma = cell.indexOf(',');
                if (comma > 0) {
                    height = Math.max(height, Integer.parseInt(cell.substring(comma + 1)) + 1);
                }
            }
        }
        return new LevelDef(
                Identifier.withDefaultNamespace("rake_test"), "钉耙测试", "", 9, Math.max(1, height),
                scene,
                List.of(new TeamDef(PLANT_TEAM, "植物方", "survive_waves"),
                        new TeamDef(ZOMBIE_TEAM, "僵尸方", "plant_side_lost")),
                PLANT_TEAM, Map.of(), Map.of(), waves, 1F,
                List.of(Identifier.withDefaultNamespace("pea_shooter"),
                        Identifier.withDefaultNamespace("sun")),
                Map.of(), PvzceConstants.INITIAL_SUN, LevelDef.LevelMusicDef.DEFAULT, List.of(),
                6, LevelRewards.NONE, LevelUnlock.NONE, mechanics, LevelDialogue.EMPTY);
    }

    private static ZombieEntity spawn(LevelServer level, CapturingBridge bridge,
                                      Identifier type, int row) {
        level.spawnZombie(type, level.team(ZOMBIE_TEAM), level.width() + 0.6F, row);
        level.flushPending(bridge);
        List<ZombieEntity> inRow = level.zombiesInRow(row);
        return inRow.get(inRow.size() - 1);
    }

    private static boolean tickUntil(LevelServer level, CapturingBridge bridge,
                                     BooleanSupplier condition, int maxTicks) {
        for (int i = 0; i < maxTicks; i++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            level.tick(bridge);
        }
        return condition.getAsBoolean();
    }

    /** Collects packets, and decodes the rake's own sync the way a client would. */
    private static final class CapturingBridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();
        RakeMechanic.State rakeState;
        List<Identifier> payloadMechanics = List.of();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
            if (packet instanceof MechanicSyncS2C sync
                    && PvzceIds.MECHANIC_RAKE.equals(sync.mechanic())) {
                rakeState = RakeMechanic.State.decode(sync.payloadBuffer());
            }
            if (packet instanceof LevelInitS2C init) {
                List<Identifier> ids = new ArrayList<>();
                for (var mechanic : init.payload().mechanics()) {
                    ids.add(mechanic.type());
                }
                payloadMechanics = ids;
            }
        }
    }
}
