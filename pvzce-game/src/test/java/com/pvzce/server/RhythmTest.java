package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.RhythmChartData;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.level.mechanic.RhythmMechanic;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ResourceDropEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rhythm levels: the chart is the level's, the judgement is the client's, the score is the
 * server's, and the lawn is played rather than left to shoot by itself.
 *
 * <p>What is worth pinning here is the split. A press that names a note which is not due is refused
 * however confident the client is; a note that is due is counted once and only once; a counted note
 * orders the plants in its lane to attack as many times as the verdict is worth, which is nothing
 * at all for a MISS; a PERFECT drops a sun out of one of those plants; nothing on the lawn attacks
 * on its own while the chart says so; and a note nobody plays is a miss when its window closes,
 * which is what makes the mode a mode rather than a button that always works.
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
     * a column order from a row one.
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
     * <p>A fast tempo on purpose: the whole chart then fits in under three hundred ticks, so a test
     * can play it out without ticking a level for ten seconds. The note ticks are the point of the
     * test and they are written out in each assertion rather than computed again here.
     *
     * <p>One column lane and one row lane, which is a board level <em>data</em> may not describe
     * (see {@link #aChartThatMixesRowsAndColumnsIsRefused}) and the mechanic still has to run: the
     * two lane kinds are two shapes of order - "everything down this column" and "everything across
     * this row" - and both are pinned here rather than in a file.
     */
    private static RhythmChartData chart() {
        return chart(RhythmChartData.DEFAULT_END_BEAT);
    }

    /** As above, with the chart's own end - what a chart written against a whole track has. */
    private static RhythmChartData chart(double endBeat) {
        return chart(endBeat, true);
    }

    /** As above, saying whether the level's plants wait for a note or keep their own clocks. */
    private static RhythmChartData chart(double endBeat, boolean holdFire) {
        return new RhythmChartData(240D, 100, RhythmChartData.DEFAULT_APPROACH_TICKS,
                RhythmChartData.DEFAULT_PERFECT_SUN, RhythmChartData.DEFAULT_ATTACK_VOLLEYS,
                holdFire, endBeat,
                List.of(new RhythmChartData.Lane(RhythmChartData.LaneKind.COL, 5,
                                List.of(0D, 4D)),
                        new RhythmChartData.Lane(RhythmChartData.LaneKind.ROW, 2,
                                List.of(8D, 12D))));
    }

    private static PlantEntity plant(LevelServer level, String id, int column, int row) {
        var def = BuiltInRegistries.PLANTS.get(PvzceIds.id(id));
        assertNotNull(def, id + " must load");
        return level.spawnPlant(def, level.team(PLANT_TEAM), column, row);
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

    /** The sun (or coin) lying on the lawn right now, or {@code null}. */
    private static ResourceDropEntity dropOnTheLawn(LevelServer level) {
        for (var entity : level.entities()) {
            if (entity instanceof ResourceDropEntity drop && !drop.isRemoved() && !drop.collected()) {
                return drop;
            }
        }
        return null;
    }

    /** One beat at 60 BPM is four ticks, so the chart's first column note lands on tick 100. */
    @Test
    void aNoteLandsWhereTheTempoSaysItDoes() {
        RhythmChartData chart = chart();
        assertEquals(15D, chart.ticksPerBeat(), 0.0001D, "240 BPM is fifteen ticks to the beat");
        assertEquals(100, chart.tickOf(0D), "the first note is at the declared offset");
        assertEquals(160, chart.tickOf(4D), "and four beats later is sixty ticks later");
        assertEquals(280, chart.lastTick(), "the chart ends with its last note");
    }

    /**
     * The windows are fractions of the flight, which is what makes the picture trustworthy.
     *
     * <p>Eighty ticks of flight, so a note within four ticks of the line is a PERFECT, eight a GOOD
     * and twelve the last thing that counts - the numbers the mode is described in, derived from
     * one number that also decides how far above the line the note is drawn.
     */
    @Test
    void theWindowsAreSharesOfTheFlight() {
        RhythmChartData chart = chart();
        assertEquals(RhythmChartData.DEFAULT_APPROACH_TICKS, chart.approachTicks());
        assertEquals(4, chart.perfectTicks(), "five percent of eighty");
        assertEquals(8, chart.goodTicks(), "ten percent");
        assertEquals(12, chart.fairTicks(), "fifteen percent");
        assertEquals(RhythmChartData.Grade.PERFECT, chart.gradeOf(-4));
        assertEquals(RhythmChartData.Grade.GOOD, chart.gradeOf(5));
        assertEquals(RhythmChartData.Grade.FAIR, chart.gradeOf(-12));
        assertEquals(RhythmChartData.Grade.MISS, chart.gradeOf(13));
        // Early and late are the same mistake, and worth the same attacks.
        assertEquals(chart.volleys(chart.gradeOf(4)), chart.volleys(chart.gradeOf(-4)));
        assertEquals(3, chart.volleys(RhythmChartData.Grade.PERFECT));
        assertEquals(2, chart.volleys(RhythmChartData.Grade.GOOD));
        assertEquals(1, chart.volleys(RhythmChartData.Grade.FAIR));
        assertEquals(0, chart.volleys(RhythmChartData.Grade.MISS));
    }

    /** A counted note orders the plants in the column it names, and not the ones beside them. */
    @Test
    void aCountedNoteFiresItsOwnColumn() {
        LevelServer level = board(chart());
        CapturingBridge bridge = new CapturingBridge();
        PlantEntity inColumn = plant(level, "pea_shooter", 5, 0);
        plant(level, "pea_shooter", 7, 0);
        // One zombie in front of the played column, one in a row no plant is aiming down.
        ZombieEntity inFront = level.spawnZombie(PvzceIds.id("basic_zombie"),
                level.team(ZOMBIE_TEAM), 6.5F, 0);
        ZombieEntity otherRow = level.spawnZombie(PvzceIds.id("basic_zombie"),
                level.team(ZOMBIE_TEAM), 6.5F, 3);
        assertNotNull(inFront);
        assertNotNull(otherRow);
        level.flushPending(bridge);
        int inFrontFull = inFront.health();
        int otherRowFull = otherRow.health();

        tickTo(level, bridge, 100);
        assertTrue(level.rhythmHit("col", 5, 100, 0), "the column note is due and is counted");
        assertEquals(RhythmChartData.DEFAULT_ATTACK_VOLLEYS, inColumn.pendingStrikes(),
                "the plant in that column was ordered to attack three times");
        tick(level, bridge, 60);
        assertTrue(inFront.health() < inFrontFull, "and its peas hit what was in its row");
        assertEquals(otherRowFull, otherRow.health(),
                "while the rows nobody played are untouched");
    }

    /** A row lane is the other shape of the same order: everything across that row attacks. */
    @Test
    void aCountedRowNoteFiresItsOwnRow() {
        LevelServer level = board(chart());
        CapturingBridge bridge = new CapturingBridge();
        PlantEntity inRow = plant(level, "pea_shooter", 3, 2);
        ZombieEntity inFront = level.spawnZombie(PvzceIds.id("basic_zombie"),
                level.team(ZOMBIE_TEAM), 4.5F, 2);
        assertNotNull(inFront);
        level.flushPending(bridge);
        int full = inFront.health();

        tickTo(level, bridge, 220);
        assertTrue(level.rhythmHit("row", 2, 220, 0), "the row note is counted");
        assertEquals(RhythmChartData.DEFAULT_ATTACK_VOLLEYS, inRow.pendingStrikes());
        tick(level, bridge, 60);
        assertTrue(inFront.health() < full, "the plant in that row fired down it");
    }

    /**
     * An ordered volley leaves whole: a repeater fires both peas, not the first one only.
     *
     * <p>The bug this pins is a gate skipping more than it meant to. {@code plant_whole_column}...
     * no: {@code plants_hold_fire} stops a shooter's <em>tick</em>, and a burst's tail - the second
     * pea of a repeater, the other three of a gatling pea - is born on those ticks, so on a rhythm
     * level a repeater was a peashooter with a longer name. A PERFECT orders three volleys, so the
     * difference is 120 damage against 60.
     */
    @Test
    void anOrderedVolleyKeepsItsBurst() {
        LevelServer level = board(chart());
        CapturingBridge bridge = new CapturingBridge();
        plant(level, "repeater", 5, 0);
        ZombieEntity target = level.spawnZombie(PvzceIds.id("basic_zombie"),
                level.team(ZOMBIE_TEAM), 6.5F, 0);
        assertNotNull(target);
        level.flushPending(bridge);
        int full = target.health();

        tickTo(level, bridge, 100);
        assertTrue(level.rhythmHit("col", 5, 100, 0), "the note orders the column to attack");
        tick(level, bridge, 80);
        assertEquals(full - 120, target.health(),
                "three volleys of two peas each is six peas, not three");
    }

    /** And the other half of the same bug: a chomper that bit has to finish chewing. */
    @Test
    void anOrderedBiteStillFinishesItsChew() {
        LevelServer level = board(chart());
        CapturingBridge bridge = new CapturingBridge();
        PlantEntity chomper = plant(level, "chomper", 5, 0);
        level.spawnZombie(PvzceIds.id("basic_zombie"), level.team(ZOMBIE_TEAM), 5.4F, 0);
        level.flushPending(bridge);

        tickTo(level, bridge, 100);
        assertTrue(level.rhythmHit("col", 5, 100, 0), "the note orders the column to attack");
        tick(level, bridge, 1900);
        assertTrue(chomper.isRemoved(),
                "a chomper that bit leaves when the chew ends, even on a level holding its fire");
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

    /** The verdict decides how many attacks the column gets: 3, 2, 1, and none at all. */
    @Test
    void theVerdictDecidesHowOftenThePlantsAttack() {
        assertEquals(3, volleysFor(0), "dead on the line");
        assertEquals(2, volleysFor(6), "inside the good window");
        assertEquals(1, volleysFor(10), "inside the fair one");
    }

    /** One board's answer to "how many attacks did this press order", at this distance. */
    private static int volleysFor(int distance) {
        LevelServer level = board(chart());
        CapturingBridge bridge = new CapturingBridge();
        PlantEntity shooter = plant(level, "pea_shooter", 5, 0);
        level.flushPending(bridge);
        tickTo(level, bridge, 100 + distance);
        assertTrue(level.rhythmHit("col", 5, 100, distance),
                "a press " + distance + " ticks off the note counts");
        return shooter.pendingStrikes();
    }

    /**
     * A PERFECT drops the sun as an entity out of the plant the note fired.
     *
     * <p>An entity and not a credit: the mode pays in the game's own currency, so a level with
     * auto-pickup gathers it a quarter of a second later and a level without one makes the player
     * click it. A lane with nothing planted pays nothing - the sun comes out of a plant.
     */
    @Test
    void aPerfectNoteDropsSunOutOfAPlant() {
        LevelServer level = board(chart());
        CapturingBridge bridge = new CapturingBridge();
        plant(level, "pea_shooter", 5, 0);
        level.flushPending(bridge);
        level.team(PLANT_TEAM).putResource(PvzceIds.SUN, 0);

        tickTo(level, bridge, 100);
        assertTrue(level.rhythmHit("col", 5, 100, 0), "dead on the beat");
        assertEquals(1, RhythmMechanic.score(level).perfect(), "which is perfect");
        tick(level, bridge, 1);
        ResourceDropEntity drop = dropOnTheLawn(level);
        assertNotNull(drop, "the perfect note dropped a sun on the lawn");
        assertEquals(RhythmChartData.DEFAULT_PERFECT_SUN, drop.amount(),
                "worth the chart's perfect_sun");
        assertEquals(0, level.team(PLANT_TEAM).resourcesOf(PvzceIds.SUN),
                "and it is lying there rather than already in the bank");

        // The row lane has nothing planted in row two, so the same perfect verdict pays nothing.
        int drops = level.entities().stream().filter(ResourceDropEntity.class::isInstance).toList().size();
        tickTo(level, bridge, 220);
        assertTrue(level.rhythmHit("row", 2, 220, 0), "the row note is perfect too");
        tick(level, bridge, 1);
        assertEquals(drops, level.entities().stream()
                        .filter(ResourceDropEntity.class::isInstance).toList().size(),
                "a lane with no plants in it drops nothing");
    }

    /** A merely good note pays no sun at all - the drop is what a PERFECT is worth. */
    @Test
    void aGoodNotePaysNoSun() {
        LevelServer level = board(chart());
        CapturingBridge bridge = new CapturingBridge();
        plant(level, "pea_shooter", 5, 0);
        level.flushPending(bridge);

        tickTo(level, bridge, 106);
        assertTrue(level.rhythmHit("col", 5, 100, 6), "six ticks late is inside the good window");
        assertEquals(1, RhythmMechanic.score(level).good(), "so it is merely good");
        tick(level, bridge, 1);
        assertNull(dropOnTheLawn(level), "and pays nothing");
    }

    /**
     * Nothing on the lawn attacks by itself while the chart holds its fire.
     *
     * <p>The mode's defining rule, and the reason the report that started this existed: a peashooter
     * that shot on its own clock played the level for the player. With the chart's own field off, the
     * same lawn shoots as it always did - which is what a hand-written chart that wants ordinary
     * plants gets.
     */
    @Test
    void thePlantsHoldTheirFireUntilANoteIsPlayed() {
        LevelServer held = board(chart(RhythmChartData.DEFAULT_END_BEAT, true));
        CapturingBridge heldBridge = new CapturingBridge();
        plant(held, "pea_shooter", 5, 0);
        ZombieEntity idle = held.spawnZombie(PvzceIds.id("basic_zombie"),
                held.team(ZOMBIE_TEAM), 6.5F, 0);
        assertNotNull(idle);
        held.flushPending(heldBridge);
        int full = idle.health();
        tick(held, heldBridge, 120);
        assertEquals(full, idle.health(), "a hundred and twenty ticks with nothing played: no shots");

        LevelServer ordinary = board(chart(RhythmChartData.DEFAULT_END_BEAT, false));
        CapturingBridge ordinaryBridge = new CapturingBridge();
        plant(ordinary, "pea_shooter", 5, 0);
        ZombieEntity bitten = ordinary.spawnZombie(PvzceIds.id("basic_zombie"),
                ordinary.team(ZOMBIE_TEAM), 6.5F, 0);
        assertNotNull(bitten);
        ordinary.flushPending(ordinaryBridge);
        int ordinaryFull = bitten.health();
        tick(ordinary, ordinaryBridge, 120);
        assertTrue(bitten.health() < ordinaryFull,
                "and the same lawn with `plants_hold_fire` off shoots as it always did");
    }

    /** A note whose window closes with nobody playing it is a miss, and it breaks the combo. */
    @Test
    void anUnplayedNoteIsMissedWhenItsWindowCloses() {
        LevelServer level = board(chart());
        CapturingBridge bridge = new CapturingBridge();

        tickTo(level, bridge, 100);
        assertTrue(level.rhythmHit("col", 5, 100, 0), "play the first note");
        assertEquals(1, RhythmMechanic.score(level).combo(), "the combo is one");

        // The second column note is due at tick 160 and its window closes twelve ticks later.
        tickTo(level, bridge, 200);

        RhythmMechanic.Score score = RhythmMechanic.score(level);
        assertEquals(1, score.missed(), "the note nobody played is a miss");
        assertEquals(0, score.combo(), "and the combo is gone");
        assertEquals(1, score.bestCombo(), "though the best one is remembered");
    }

    /** A press inside the middle window is a fair note, and it is counted as one. */
    @Test
    void aLatePressIsAFairNote() {
        LevelServer level = board(chart());
        CapturingBridge bridge = new CapturingBridge();

        tickTo(level, bridge, 110);
        assertTrue(level.rhythmHit("col", 5, 100, 10), "ten ticks late is inside the last window");
        RhythmMechanic.Score score = RhythmMechanic.score(level);
        assertEquals(1, score.fair(), "which is a fair note");
        assertEquals(0, score.missed(), "and not a miss");
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

    /** The level holds the lawn's fire exactly when its chart says so. */
    @Test
    void theLevelHoldsTheLawnsFireWhenTheChartSaysSo() {
        assertTrue(board(chart(RhythmChartData.DEFAULT_END_BEAT, true)).plantsHoldFire(),
                "a hold-fire chart's level says so");

        LevelDef plain = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_1"));
        assertFalse(new LevelServer(plain).plantsHoldFire(),
                "and a level with no chart at all leaves its plants alone");
    }

    /**
     * A hold-fire chart whose notes are worth no attacks is a level where nothing can happen.
     *
     * <p>The plants never act on their own and the keyboard does not tell them to, so the lawn can
     * only be lost. Data error rather than odd tuning, and the validator says so.
     */
    @Test
    void aHoldFireChartWorthNoAttacksIsRefused() {
        LevelDef demo = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/demo_level"));
        assertNotNull(demo);
        RhythmChartData toothless = new RhythmChartData(240D, 100,
                RhythmChartData.DEFAULT_APPROACH_TICKS, 0, 0, true, 0D,
                List.of(new RhythmChartData.Lane(RhythmChartData.LaneKind.COL, 5, List.of(0D))));
        List<String> errors = LevelMechanics.validate(demo, PvzceIds.MECHANIC_RHYTHM, toothless);
        assertTrue(errors.stream().anyMatch(error -> error.contains("no attacks")),
                "a chart that orders nothing is refused: " + errors);
    }

    /** Each shipped tier resolves and plays: four difficulties, one per file. */
    @Test
    void theFourShippedTiersEachHaveAPlayableChart() {
        int[] counts = new int[4];
        String[] suffixes = {"easy", "normal", "hard", "expert"};
        for (int i = 0; i < suffixes.length; i++) {
            LevelDef def = BuiltInRegistries.LEVELS.get(
                    PvzceIds.id("yard/rhythm/rhythm_" + suffixes[i]));
            assertNotNull(def, "rhythm_" + suffixes[i] + " must load");
            RhythmChartData chart = LevelMechanics
                    .dataOf(def, PvzceIds.MECHANIC_RHYTHM, RhythmChartData.class)
                    .orElse(null);
            assertNotNull(chart, suffixes[i] + " declares a chart");
            assertTrue(chart.lanes().size() >= 2, suffixes[i] + " has lanes to play");
            assertTrue(chart.lastTick() > 0, suffixes[i] + " has notes");
            assertTrue(new LevelServer(def).forbidsSpeedChange(),
                    suffixes[i] + " forbids the speed control");
            assertTrue(chart.hasEnd(), suffixes[i] + " ends with the track");
            assertTrue(chart.plantsHoldFire(),
                    suffixes[i] + " is played on the keyboard rather than by the plants");
            double speed = def.rules().get(PvzceIds.RULE_ZOMBIE_SPEED_MULTIPLIER).getAsDouble();
            assertTrue(speed >= 2D,
                    suffixes[i] + " walks its zombies at least twice as fast: " + speed);
            assertTrue(def.rules().get(PvzceIds.RULE_ZOMBIE_SPAWN_SPEED_MULTIPLIER).getAsDouble() > 1D,
                    suffixes[i] + " pours its zombies in faster than the tables were written for");
            counts[i] = chart.lanes().stream().mapToInt(lane -> lane.notes().size()).sum();
        }
        assertTrue(counts[0] < counts[1] && counts[1] < counts[2] && counts[2] < counts[3],
                "the four tiers are a curve, not four copies: " + java.util.Arrays.toString(counts));
    }

    /**
     * The end of the track is the end of the level: the lawn is swept and the run is won.
     *
     * <p>This is the ending a rhythm level has instead of "the last wave was shot" - the song is the
     * clock, so a player still standing when it runs out has survived it. The bodies that are still
     * walking die where they stand, and they die the ordinary death, so a wave waiting on one of
     * them hears about it.
     */
    @Test
    void theChartEndsTheLevelAndSweepsTheLawn() {
        LevelServer level = board(chart(20D));
        CapturingBridge bridge = new CapturingBridge();
        ZombieEntity survivor = level.spawnZombie(PvzceIds.id("basic_zombie"),
                level.team(ZOMBIE_TEAM), 4.5F, 1);
        assertNotNull(survivor);

        // Beat twenty at 240 BPM is tick 100 + 300 = 400, and the chart has no note anywhere near
        // it: the sweep is the chart's own end rather than anything the player did.
        tickTo(level, bridge, 399);
        assertTrue(survivor.isAlive(), "nothing has swept the lawn yet");
        tickTo(level, bridge, 401);
        assertFalse(survivor.isAlive(), "the end of the track swept it");
        assertEquals(GameStateS2C.WON, level.gameState(),
                "and a player who was still standing has survived the song");
    }

    /** A chart with no end of its own leaves the level's ending to the level. */
    @Test
    void aChartWithoutAnEndDoesNotEndTheLevel() {
        LevelServer level = board(chart());
        CapturingBridge bridge = new CapturingBridge();
        ZombieEntity survivor = level.spawnZombie(PvzceIds.id("basic_zombie"),
                level.team(ZOMBIE_TEAM), 4.5F, 1);
        assertNotNull(survivor);

        tickTo(level, bridge, 401);
        assertTrue(survivor.isAlive(), "a chart that stops is not a chart that finishes");
        assertEquals(GameStateS2C.RUNNING, level.gameState());
    }

    /**
     * A level plays one way: a chart with rows <em>and</em> columns is a data error.
     *
     * <p>The keys are the reason. The picture the player reads is a note flying down its own column
     * to a judgement line at the bottom of the board, and there is only one place that picture can
     * be drawn - so a chart with both shapes is a chart whose keys the player has to work out per
     * note. This was a report about the shipped tiers, and it is now something the validator refuses.
     */
    @Test
    void aChartThatMixesRowsAndColumnsIsRefused() {
        LevelDef demo = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/demo_level"));
        assertNotNull(demo);
        List<String> errors = LevelMechanics.validate(demo, PvzceIds.MECHANIC_RHYTHM, chart());
        assertTrue(errors.stream().anyMatch(error -> error.contains("mixes row and column")),
                "a mixed chart is refused: " + errors);
    }

    /** A note after the chart's own end is a note nobody could reach, and is refused too. */
    @Test
    void aNoteAfterTheEndIsRefused() {
        LevelDef demo = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/demo_level"));
        assertNotNull(demo);
        // The chart's last note is on beat 12; ending it at beat 4 leaves two notes outside it.
        List<String> errors = LevelMechanics.validate(demo, PvzceIds.MECHANIC_RHYTHM, chart(4D));
        assertTrue(errors.stream().anyMatch(error -> error.contains("notes after it")),
                "a chart that ends before its own notes is refused: " + errors);
    }

    // ------------------------------------------------------------------
    // The energy bar, the streak, and what they buy
    // ------------------------------------------------------------------

    /**
     * One lane of consecutive notes, one beat apart.
     *
     * <p>The chart the rules below are played on. The two-note chart above cannot make a streak of
     * thirty or fill a bar to three thousand, and both of those are rules about <em>long</em> runs
     * of perfect notes - so the test plays a long one. What is under test is the threshold and what
     * it buys, not the counting of notes, which the tests above pin.
     */
    private static RhythmChartData longChart(int notes) {
        return longChart(notes, RhythmChartData.DEFAULT_ATTACK_VOLLEYS);
    }

    /** The same, with the verdict's worth written down: one volley makes a note a single attack. */
    private static RhythmChartData longChart(int notes, int volleys) {
        java.util.List<Double> beats = new java.util.ArrayList<>();
        for (int i = 0; i < notes; i++) {
            beats.add((double) i);
        }
        return new RhythmChartData(240D, 100, RhythmChartData.DEFAULT_APPROACH_TICKS,
                RhythmChartData.DEFAULT_PERFECT_SUN, volleys,
                true, 0D,
                List.of(new RhythmChartData.Lane(RhythmChartData.LaneKind.COL, 5, beats)));
    }

    /**
     * Plays {@code count} notes of {@link #longChart} dead on their ticks, and answers the level.
     *
     * <p>Returns the level so a test can carry on with it; every note is asserted, because a press
     * the server refused would make every count below silently wrong.
     */
    private static LevelServer playPerfectly(RhythmChartData chart, int count,
                                             CapturingBridge bridge) {
        LevelServer level = board(chart);
        for (int i = 0; i < count; i++) {
            int tick = 100 + i * 15;
            tickTo(level, bridge, tick);
            assertTrue(level.rhythmHit("col", 5, tick, 0), "note " + i + " is playable on its tick");
        }
        return level;
    }

    /** Every fire tongue the clients were sent, as the effect events carry them. */
    private static long fireTongues(CapturingBridge bridge) {
        return bridge.packets.stream()
                .filter(packet -> packet instanceof com.pvzce.common.network.packet.EffectEventS2C)
                .map(packet -> (com.pvzce.common.network.packet.EffectEventS2C) packet)
                .filter(effect -> effect.particle().equals(
                        com.pvzce.common.PvzceParticles.JALAPENO_FIRE.toString()))
                .count();
    }

    /**
     * What each verdict is worth, measured as the step it makes in the bar.
     *
     * <p>Measured either side of the press and with no tick in between, so the number is the
     * verdict's own and not the verdict's minus however much the bar happened to bleed in the same
     * frame. A PERFECT pays a hundred, a GOOD fifty, a FAIR twenty.
     */
    @Test
    void everyVerdictPaysTheBar() {
        LevelServer level = board(chart());
        CapturingBridge bridge = new CapturingBridge();

        tickTo(level, bridge, 100);
        int before = RhythmMechanic.score(level).energy();
        assertTrue(level.rhythmHit("col", 5, 100, 0), "a perfect note");
        assertEquals(PvzceConstants.ENERGY_PERFECT, RhythmMechanic.score(level).energy() - before,
                "which is worth a hundred");

        // The second column note is due at 160; six ticks late is inside the good window.
        tickTo(level, bridge, 166);
        before = RhythmMechanic.score(level).energy();
        assertTrue(level.rhythmHit("col", 5, 160, 6), "a good note");
        assertEquals(PvzceConstants.ENERGY_GOOD, RhythmMechanic.score(level).energy() - before);
    }

    /** And the bleed: five points a second, whatever the player is doing. */
    @Test
    void theBarBleedsFiveASecond() {
        LevelServer level = board(chart());
        CapturingBridge bridge = new CapturingBridge();
        tickTo(level, bridge, 100);
        assertTrue(level.rhythmHit("col", 5, 100, 0), "bank a hundred");
        int before = RhythmMechanic.score(level).energy();

        tick(level, bridge, PvzceConstants.TICKS_PER_SECOND);
        assertEquals(PvzceConstants.ENERGY_DRAIN_PER_SECOND,
                before - RhythmMechanic.score(level).energy(),
                "a second of chart costs exactly five points, no matter what was banked");
    }

    /** A miss costs ten, on top of the bleed, and never takes the bar below empty. */
    @Test
    void aMissCostsTheBarTenPoints() {
        LevelServer level = board(chart());
        CapturingBridge bridge = new CapturingBridge();

        // An empty bar first: the miss's ten points land on nothing, and the clamp is what keeps
        // the bar from going negative.
        tickTo(level, bridge, 173);
        assertEquals(1, RhythmMechanic.score(level).missed(), "the first note went by unplayed");
        assertEquals(0, RhythmMechanic.score(level).energy(),
                "a miss on an empty bar leaves it empty rather than negative");
    }

    /** The bar has a ceiling, and a PERFECT at the top of it pays nothing. */
    @Test
    void theBarIsCapped() {
        CapturingBridge bridge = new CapturingBridge();
        LevelServer level = playPerfectly(longChart(120), 120, bridge);
        assertEquals(PvzceConstants.ENERGY_MAX, RhythmMechanic.score(level).energy(),
                "the bar fills and stops at " + PvzceConstants.ENERGY_MAX
                        + " however many notes are played");
    }

    /**
     * The gates: at three thousand every plant fires twice the bullets, at five thousand three.
     *
     * <p>Asked of the level rather than of the bar, because the level's answer is the one the lawn
     * acts on - the bar is only what it is read from.
     */
    @Test
    void theBarDecidesHowManyBulletsAPlantFires() {
        LevelServer empty = board(chart());
        PlantEntity shooter = plant(empty, "pea_shooter", 3, 2);
        assertEquals(1, empty.projectileCountMultiplier(shooter), "an empty bar buys nothing");

        // Thirty-one perfect notes is 3100 points against a bleed of about forty: over the gate.
        CapturingBridge bridge = new CapturingBridge();
        LevelServer doubling = playPerfectly(longChart(40), 31, bridge);
        assertTrue(RhythmMechanic.score(doubling).energy() >= PvzceConstants.ENERGY_DOUBLE_AT,
                "the bar is over the first gate: " + RhythmMechanic.score(doubling).energy());
        assertEquals(2, doubling.projectileCountMultiplier(plant(doubling, "pea_shooter", 3, 2)));

        LevelServer tripling = playPerfectly(longChart(60), 52, bridge);
        assertTrue(RhythmMechanic.score(tripling).energy() >= PvzceConstants.ENERGY_TRIPLE_AT,
                "and the second: " + RhythmMechanic.score(tripling).energy());
        assertEquals(3, tripling.projectileCountMultiplier(plant(tripling, "pea_shooter", 3, 2)));
    }

    /**
     * A doubled volley really is two peas, and the second one is a separate object.
     *
     * <p>The spacing is the half a count alone would not show: two peas born on the same tick at the
     * same point are one pea on the screen and one hit on the zombie, which is why the repeater has
     * a burst delay at all.
     */
    @Test
    void aDoubledVolleyIsTwoPeasOnTheirOwnTicks() {
        CapturingBridge bridge = new CapturingBridge();
        // One volley per note, so the peas counted below are the volley's own and not the three a
        // PERFECT is worth on a shipped chart (`RhythmChartData#volleys`).
        LevelServer level = playPerfectly(longChart(40, 1), 31, bridge);
        // In the lane the chart plays, which is the only one whose plants a note can order.
        PlantEntity shooter = plant(level, "pea_shooter", 5, 2);
        assertEquals(2, level.projectileCountMultiplier(shooter), "the bar is over the gate");

        long before = projectilesOnTheLawn(level);
        int tick = 100 + 31 * 15;
        tickTo(level, bridge, tick);
        assertTrue(level.rhythmHit("col", 5, tick, 0), "a note orders the column to fire");
        // The order is queued on the plant rather than fired from the packet handler, so the pea
        // leaves on the plant's own next tick (`PlantEntity.queueStrikes`).
        tick(level, bridge, 1);
        assertEquals(before + 1, projectilesOnTheLawn(level), "the first pea leaves on that tick");
        tick(level, bridge, com.pvzce.common.capability.plant.ShooterCapability
                .MULTIPLIED_BURST_DELAY);
        assertEquals(before + 2, projectilesOnTheLawn(level),
                "and the second a burst later, so the two are separate shots");
    }

    /**
     * Thirty PERFECTs in a row sets every row of the board alight, and a GOOD breaks the streak.
     *
     * <p>The reward the mode is named after: a jalapeno in each row - "五行每行一个" - which between
     * them burn the whole lawn. What is asserted is the volley (one row of fire per row of the
     * board, all in one tick) and the streak that paid it.
     */
    @Test
    void thirtyPerfectsInARowBurnEveryRow() {
        CapturingBridge bridge = new CapturingBridge();
        LevelServer level = playPerfectly(longChart(40), PvzceConstants.PERFECT_STREAK_MILESTONES[0],
                bridge);
        // The milestone is crossed in `judge`, which runs while the server handles packets - and a
        // level can play no effect there, so the volley is owed until the level's own tick. One
        // tick later the whole board is on fire, which is the half a test of the counter alone
        // would never see.
        tick(level, bridge, 1);
        RhythmMechanic.Score score = RhythmMechanic.score(level);
        assertEquals(PvzceConstants.PERFECT_STREAK_MILESTONES[0], score.perfectStreak());
        assertEquals(1, score.jalapenos(), "the first milestone paid a volley");
        assertEquals(level.height() * level.width(), fireTongues(bridge),
                "one tongue per cell of every row, which is " + level.height() + " rows of "
                        + level.width());
    }

    /** One short of the milestone pays nothing, which is what makes the number mean something. */
    @Test
    void twentyNinePerfectsBurnNothing() {
        CapturingBridge bridge = new CapturingBridge();
        LevelServer level = playPerfectly(longChart(40),
                PvzceConstants.PERFECT_STREAK_MILESTONES[0] - 1, bridge);
        assertEquals(0, RhythmMechanic.score(level).jalapenos(), "one short of the first milestone");
        assertEquals(0, fireTongues(bridge), "so nothing on the lawn is on fire");
    }

    /** A GOOD is not a PERFECT, and the streak is named after what it counts. */
    @Test
    void aGoodBreaksThePerfectStreak() {
        LevelServer level = board(chart());
        CapturingBridge bridge = new CapturingBridge();
        tickTo(level, bridge, 100);
        assertTrue(level.rhythmHit("col", 5, 100, 0), "a perfect");
        assertEquals(1, RhythmMechanic.score(level).perfectStreak());

        tickTo(level, bridge, 166);
        assertTrue(level.rhythmHit("col", 5, 160, 6), "then a good, six ticks late");
        assertEquals(0, RhythmMechanic.score(level).perfectStreak(), "and the streak is gone");
    }

    /**
     * Past the last named milestone the streak keeps paying, every twenty.
     *
     * <p>The four numbers are not where the rewards stop: a run that has played a flawless hundred
     * notes keeps being paid for it rather than falling off a cliff exactly where it became
     * impressive.
     */
    @Test
    void pastTheLastMilestoneTheStreakKeepsPayingEveryTwenty() {
        assertEquals(30, RhythmMechanic.nextStreakReward(0));
        assertEquals(50, RhythmMechanic.nextStreakReward(30));
        assertEquals(80, RhythmMechanic.nextStreakReward(50));
        assertEquals(100, RhythmMechanic.nextStreakReward(80));
        assertEquals(120, RhythmMechanic.nextStreakReward(100));
        assertEquals(140, RhythmMechanic.nextStreakReward(120));
        assertEquals(200, RhythmMechanic.nextStreakReward(181),
                "a streak past a step is still paid at the next one");
    }

    /** The bar and the streak travel in the save, unrounded. */
    @Test
    void theBarAndTheStreakTravelInTheSave() {
        CapturingBridge bridge = new CapturingBridge();
        LevelServer level = playPerfectly(longChart(40), 12, bridge);
        RhythmChartData chart = longChart(40);
        int energy = RhythmMechanic.score(level).energy();
        assertTrue(energy > 0, "some points were earned");

        var typed = new com.pvzce.api.content.mechanic.TypedMechanic(
                PvzceIds.MECHANIC_RHYTHM, chart);
        CompoundTag save = new CompoundTag();
        LevelMechanics.collectSave(typed, level, save);

        LevelServer resumed = board(chart);
        LevelMechanics.applySave(typed, resumed, save);
        assertEquals(energy, RhythmMechanic.score(resumed).energy(),
                "the bar comes back where it was");
        assertEquals(12, RhythmMechanic.score(resumed).perfectStreak());
    }

    /** How many projectiles are on the lawn right now. */
    private static long projectilesOnTheLawn(LevelServer level) {
        return level.entities().stream()
                .filter(entity -> entity instanceof com.pvzce.server.entity.ProjectileEntity)
                .count();
    }
}
