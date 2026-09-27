package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.RhythmChartData;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.level.mechanic.RhythmMechanic;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rhythm levels: the chart is the level's, the judgement is the client's, the score is the
 * server's.
 *
 * <p>What is worth pinning here is the split. A press that names a note which is not due is refused
 * however confident the client is; a note that is due is counted once and only once; a counted note
 * makes its lane attack, and the lane is the shape the chart says - a row across, a column down;
 * and a note nobody plays is a miss when its window closes, which is what makes the mode a mode
 * rather than a button that always works.
 */
class RhythmTest {
    private static final Identifier PLANT_TEAM = PvzceIds.PLANT_TEAM;
    private static final Identifier ZOMBIE_TEAM = PvzceIds.ZOMBIE_TEAM;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static final class CapturingBridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();
        final List<String> messages = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
            if (packet instanceof com.pvzce.common.network.packet.ServerMessageS2C message) {
                messages.add(message.message());
            }
        }
    }

    /**
     * A flat board with one hand-written chart and no waves.
     *
     * <p>Deliberately not a shipped level: the shipped ones are four difficulties of the same
     * track, and a test that played one would be testing a generator's output rather than the
     * rules. The chart below is two lanes of two notes, which is the smallest thing that can show
     * a row attack from a column attack.
     */
    private static LevelServer board(RhythmChartData chart) {
        LevelDef demo = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/demo_level"));
        assertNotNull(demo, "demo_level must load");
        return new LevelServer(com.pvzce.testutil.TestLevels.copy(demo)
                .waves(List.of())
                .mechanics(List.of(new com.pvzce.api.content.mechanic.TypedMechanic(
                        PvzceIds.MECHANIC_RHYTHM, chart)))
                .build());
    }

    /**
     * Two lanes of two notes at 240 BPM, which is fifteen ticks to the beat.
     *
     * <p>A fast tempo on purpose: the whole chart then fits in under two hundred ticks, so a test
     * can play it out without ticking a level for ten seconds. The note ticks are the point of the
     * test and they are written out in each assertion rather than computed again here.
     */
    private static RhythmChartData chart() {
        return new RhythmChartData(240D, 100, 4, 9, 1, 300,
                List.of(new RhythmChartData.Lane(RhythmChartData.LaneKind.COL, 5,
                                List.of(0D, 4D)),
                        new RhythmChartData.Lane(RhythmChartData.LaneKind.ROW, 2,
                                List.of(8D, 12D))));
    }

    private static void tick(LevelServer level, CapturingBridge bridge, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.tick(bridge);
            level.flushPending(bridge);
        }
    }

    private static int tickTo(LevelServer level, CapturingBridge bridge, int target) {
        while (level.tickCount() < target) {
            level.tick(bridge);
            level.flushPending(bridge);
        }
        return level.tickCount();
    }

    /** One beat at 60 BPM is four ticks, so the chart's first row note lands on tick 100. */
    @Test
    void aNoteLandsWhereTheTempoSaysItDoes() {
        RhythmChartData chart = chart();
        assertEquals(15D, chart.ticksPerBeat(), 0.0001D, "240 BPM is fifteen ticks to the beat");
        assertEquals(100, chart.tickOf(0D), "the first note is at the declared offset");
        assertEquals(160, chart.tickOf(4D), "and four beats later is sixty ticks later");
        assertEquals(280, chart.lastTick(), "the chart ends with its last note");
    }

    /** A counted note damages the lane it names, and the two lane kinds are two shapes. */
    @Test
    void aCountedNoteFiresItsOwnLane() {
        LevelServer level = board(chart());
        CapturingBridge bridge = new CapturingBridge();
        // One zombie in the row that is played, one in the column, one in neither.
        ZombieEntity inRow = level.spawnZombie(PvzceIds.id("basic_zombie"),
                level.team(ZOMBIE_TEAM), 4.5F, 2);
        ZombieEntity inColumn = level.spawnZombie(PvzceIds.id("basic_zombie"),
                level.team(ZOMBIE_TEAM), 5.5F, 0);
        ZombieEntity elsewhere = level.spawnZombie(PvzceIds.id("basic_zombie"),
                level.team(ZOMBIE_TEAM), 1.5F, 4);
        assertNotNull(inRow);
        assertNotNull(inColumn);
        assertNotNull(elsewhere);
        level.flushPending(bridge);
        int rowFull = inRow.health();
        int columnFull = inColumn.health();
        int elsewhereFull = elsewhere.health();

        // The column's first note is due on tick 100 and the row's on 220. The column is played
        // first on purpose: a zombie walks out of its column in a couple of seconds, so the lane
        // that has to catch one standing still has to be the early one.
        tickTo(level, bridge, 100);
        assertTrue(level.rhythmHit("col", 5, 100, 0), "the column note is due and is counted");
        assertTrue(inColumn.health() < columnFull, "the zombie in that column was hit");
        assertEquals(rowFull, inRow.health(), "and the one in the row was not");

        tickTo(level, bridge, 220);
        assertTrue(level.rhythmHit("row", 2, 220, 0), "the row note is counted");
        assertTrue(inRow.health() < rowFull, "the zombie in that row was hit");
        assertEquals(elsewhereFull, elsewhere.health(),
                "and the one in neither lane is untouched by either");
    }

    /** A press that names a note nowhere near now is refused, however sure the client is. */
    @Test
    void aPressOutsideTheWindowIsRefused() {
        LevelServer level = board(chart());
        CapturingBridge bridge = new CapturingBridge();

        tickTo(level, bridge, 100);
        assertFalse(level.rhythmHit("col", 5, 500, 0),
                "a note four hundred ticks away is not what was pressed");
        assertFalse(level.rhythmHit("col", 7, 100, 0), "and there is no such lane");
        assertFalse(level.rhythmHit("col", 5, 104, 0),
                "nor a note at a tick the lane does not have");
    }

    /** One note, one score: a client that sends the same hit twice gets nothing the second time. */
    @Test
    void aNoteIsCountedOnceAndOnlyOnce() {
        LevelServer level = board(chart());
        CapturingBridge bridge = new CapturingBridge();

        tickTo(level, bridge, 100);
        assertTrue(level.rhythmHit("col", 5, 100, 0), "the first press counts");
        assertFalse(level.rhythmHit("col", 5, 100, 0),
                "the same note cannot be scored twice, however many times it is sent");
        assertEquals(1, RhythmMechanic.score(level).good() + RhythmMechanic.score(level).perfect(),
                "and the tally moved once");
    }

    /** A perfect note pays the sun the chart says it does; a merely good one does not. */
    @Test
    void aPerfectNotePaysSun() {
        LevelServer level = board(chart());
        CapturingBridge bridge = new CapturingBridge();
        level.team(level.humanTeamId()).putResource(PvzceIds.SUN, 0);

        tickTo(level, bridge, 100);
        assertTrue(level.rhythmHit("col", 5, 100, 0), "dead on the beat");
        assertEquals(1, RhythmMechanic.score(level).perfect(), "which is perfect");
        assertEquals(1, level.team(level.humanTeamId()).resourcesOf(PvzceIds.SUN),
                "and pays a sun");

        tickTo(level, bridge, 226);
        assertTrue(level.rhythmHit("row", 2, 220, 5),
                "six ticks late is inside the window but outside the perfect one");
        assertEquals(1, RhythmMechanic.score(level).good(), "so it is merely good");
        assertEquals(1, level.team(level.humanTeamId()).resourcesOf(PvzceIds.SUN),
                "and pays nothing");
    }

    /** A note whose window closes with nobody playing it is a miss, and it breaks the combo. */
    @Test
    void anUnplayedNoteIsMissedWhenItsWindowCloses() {
        LevelServer level = board(chart());
        CapturingBridge bridge = new CapturingBridge();

        tickTo(level, bridge, 100);
        assertTrue(level.rhythmHit("col", 5, 100, 0), "play the first note");
        assertEquals(1, RhythmMechanic.score(level).combo(), "the combo is one");

        // The second column note is due at tick 160 and its window closes nine ticks later.
        tickTo(level, bridge, 200);

        RhythmMechanic.Score score = RhythmMechanic.score(level);
        assertEquals(1, score.missed(), "the note nobody played is a miss");
        assertEquals(0, score.combo(), "and the combo is gone");
        assertEquals(1, score.bestCombo(), "though the best one is remembered");
    }

    /**
     * The build phase is the chart's buffer: nothing is due, and nothing is missed, until the
     * player says the waves may start.
     *
     * <p>This is the whole reason the rhythm levels use {@code pvzce:preparation}. Without it the
     * chart would be running from the level's first tick, and a player who spent ten seconds
     * placing a peashooter would open on a screen full of misses - and the first note, which is
     * five seconds in, would already have gone by.
     */
    @Test
    void theChartWaitsForTheBuildPhaseToEnd() {
        LevelDef demo = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/demo_level"));
        LevelServer level = new LevelServer(com.pvzce.testutil.TestLevels.copy(demo)
                .waves(List.of())
                .mechanics(List.of(
                        new com.pvzce.api.content.mechanic.TypedMechanic(
                                PvzceIds.MECHANIC_PREPARATION, new com.pvzce.api.content.PreparationData(
                                        true, 0, true)),
                        new com.pvzce.api.content.mechanic.TypedMechanic(
                                PvzceIds.MECHANIC_RHYTHM, chart())))
                .build());
        CapturingBridge bridge = new CapturingBridge();
        assertTrue(com.pvzce.common.level.mechanic.PreparationMechanic.isPreparing(level),
                "the level opens in its build phase");

        // Two hundred ticks of building: past every note in the chart, and none of them counts.
        tick(level, bridge, 200);
        assertEquals(0, RhythmMechanic.score(level).missed(),
                "nothing is missed while the player is still building");
        assertFalse(level.rhythmHit("col", 5, 100, 0), "and no press counts either");

        level.beginWaves();
        tick(level, bridge, 1);
        assertFalse(com.pvzce.common.level.mechanic.PreparationMechanic.isPreparing(level),
                "the waves are running");
        // The chart's own clock starts here, so its first note is a hundred ticks away again.
        tickTo(level, bridge, level.tickCount() + 100);
        assertTrue(level.rhythmHit("col", 5, 100, 0),
                "and the first note is due a hundred ticks after the build phase, not after loading");
    }

    /** A rhythm level refuses the speed control: 2x would halve the time to answer the chart. */
    @Test
    void aRhythmLevelRefusesASpeedChange() {
        LevelServer level = board(chart());
        assertTrue(level.forbidsSpeedChange(), "the chart is written against the level's own ticks");

        LevelDef plain = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_1"));
        assertFalse(new LevelServer(plain).forbidsSpeedChange(),
                "and an ordinary level does not care");
    }

    /** Each shipped tier resolves and plays: four difficulties, one per file. */
    @Test
    void theFourShippedTiersEachHaveAPlayableChart() {
        int[] counts = new int[4];
        String[] suffixes = {"easy", "normal", "hard", "expert"};
        for (int i = 0; i < suffixes.length; i++) {
            LevelDef def = BuiltInRegistries.LEVELS.get(
                    PvzceIds.id("yard/minigame/rhythm_" + suffixes[i]));
            assertNotNull(def, "rhythm_" + suffixes[i] + " must load");
            RhythmChartData chart = LevelMechanics
                    .dataOf(def, PvzceIds.MECHANIC_RHYTHM, RhythmChartData.class)
                    .orElse(null);
            assertNotNull(chart, suffixes[i] + " declares a chart");
            assertTrue(chart.lanes().size() >= 2, suffixes[i] + " has lanes to play");
            assertTrue(chart.lastTick() > 0, suffixes[i] + " has notes");
            assertTrue(new LevelServer(def).forbidsSpeedChange(),
                    suffixes[i] + " forbids the speed control");
            counts[i] = chart.lanes().stream().mapToInt(lane -> lane.notes().size()).sum();
        }
        assertTrue(counts[0] < counts[1] && counts[1] < counts[2] && counts[2] < counts[3],
                "the four tiers are a curve, not four copies: " + java.util.Arrays.toString(counts));
    }
}
