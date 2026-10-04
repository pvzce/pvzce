package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.PreparationData;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.network.packet.MusicEventS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
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
 * The preparation phase: build first, then start the waves.
 *
 * <p>Three facts, and every one of them is a rule the phase exists for: nothing arrives while it
 * runs (so the sun the player builds with is the sun the level gave them), the waves really do
 * start when it ends, and a plant taken up during it comes back at full price - which is what
 * makes rearranging a layout possible at all.
 */
class PreparationTest {
    private static final Identifier PLANT_TEAM = PvzceIds.PLANT_TEAM;
    private static final Identifier ZOMBIE_TEAM = PvzceIds.ZOMBIE_TEAM;
    private static final Identifier SUNFLOWER = PvzceIds.id("sunflower");
    private static final Identifier BASIC_ZOMBIE = PvzceIds.id("basic_zombie");

    private static LevelDef waveBoard;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    /**
     * A board with a sun clock short enough to matter, and the phase under test.
     *
     * <p>Copied from a shipped board and changed in three places rather than hand-built: the level
     * definition has thirty fields, and a test that lists them is a test that breaks the next time
     * one is added.
     */
    private static LevelDef board(PreparationData preparation) {
        LevelDef demo = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/demo_level"));
        assertNotNull(demo, "demo_level must load");
        java.util.Map<Identifier, com.google.gson.JsonElement> rules =
                new java.util.LinkedHashMap<>(demo.rules());
        // A sun every second: any tick the phase lets the clock run shows up as a drop.
        rules.put(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN, new com.google.gson.JsonPrimitive(60));
        rules.put(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX, new com.google.gson.JsonPrimitive(60));
        return com.pvzce.testutil.TestLevels.copy(demo)
                .height(1)
                .scene(singleRow(9))
                .rules(rules)
                .slots(List.of(SUNFLOWER, PvzceIds.id("shovel")))
                .initialSun(500)
                .mechanics(List.of(new TypedMechanic(PvzceIds.MECHANIC_PREPARATION, preparation)))
                .build();
    }

    /** One row of grass, {@code width} columns wide. */
    private static java.util.Map<Identifier, List<String>> singleRow(int width) {
        List<String> cells = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            cells.add(x + ",0");
        }
        return java.util.Map.of(PvzceIds.GRASS, cells);
    }

    private static final class CapturingBridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
        }
    }

    private static void tick(LevelServer level, CapturingBridge bridge, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.tick(bridge);
            level.flushPending(bridge);
        }
    }

    private static long sunDrops(CapturingBridge bridge) {
        return bridge.packets.stream()
                .filter(packet -> packet instanceof EntitySpawnS2C spawn
                        && "resource".equals(spawn.entityKind()))
                .count();
    }

    @Test
    void nothingArrivesWhileThePhaseRuns() {
        LevelServer level = new LevelServer(board(PreparationData.MANUAL));
        CapturingBridge bridge = new CapturingBridge();
        assertTrue(level.isPreparing(), "the mechanic starts the phase when the level is built");

        tick(level, bridge, 600);

        assertEquals(0, sunDrops(bridge), "no sun falls: the phase is built on the initial sun");
        assertEquals(0, level.aliveZombieCount(), "and no wave is released");
        assertTrue(level.isPreparing(), "a manual phase waits for the player, however long that is");
    }

    @Test
    void startingReleasesTheWavesAndTheSky() {
        LevelServer level = new LevelServer(board(PreparationData.MANUAL));
        CapturingBridge bridge = new CapturingBridge();
        tick(level, bridge, 120);
        level.beginWaves();
        assertFalse(level.isPreparing(), "the player's button ends the phase");
        bridge.packets.clear();

        tick(level, bridge, 900);

        assertTrue(sunDrops(bridge) > 0, "the sky starts dropping once the phase is over");
    }

    /** The other half of "no sun falls": the clock is not ticked either, so the first drop waits. */
    @Test
    void theSunClockDoesNotRunDuringThePhase() {
        LevelServer level = new LevelServer(board(PreparationData.MANUAL));
        CapturingBridge bridge = new CapturingBridge();
        // Ten times the interval, then start: a clock that had been running would drop at once.
        tick(level, bridge, 600);
        level.beginWaves();
        tick(level, bridge, 30);

        assertEquals(0, sunDrops(bridge),
                "the first drop is a full interval after the phase, not the tick it ends");
    }

    /** An automatic phase ends on its own clock. */
    @Test
    void aTimedPhaseStartsItself() {
        LevelServer level = new LevelServer(board(new PreparationData(false, 300, true)));
        CapturingBridge bridge = new CapturingBridge();
        tick(level, bridge, 299);
        assertTrue(level.isPreparing(), "still preparing one tick before the clock runs out");
        tick(level, bridge, 2);
        assertFalse(level.isPreparing(), "and started when it did");
    }

    /**
     * Digging a plant up during the phase pays its whole price back.
     *
     * <p>Once, and only for the plant that was there: the phase is a rearrangement, and the test
     * asserts the wallet, not the intent.
     */
    @Test
    void diggingUpDuringThePhaseRefundsTheWholePrice() {
        LevelServer level = new LevelServer(board(PreparationData.MANUAL));
        CapturingBridge bridge = new CapturingBridge();
        PlantDef sunflower = BuiltInRegistries.PLANTS.get(SUNFLOWER);
        int price = sunflower.cost().amountOf(PvzceIds.SUN);
        PlantEntity planted = level.spawnPlant(sunflower, level.team(PLANT_TEAM), 3, 0);
        level.flushPending(bridge);
        int before = level.team(PLANT_TEAM).resourcesOf(PvzceIds.SUN);

        level.useTool(bridge, 1, 3, 0);

        assertTrue(planted.isRemoved(), "the shovel takes one plant");
        assertEquals(before + price, level.team(PLANT_TEAM).resourcesOf(PvzceIds.SUN),
                "and pays back exactly what it cost");
    }

    /** The client is told whether the phase is running; that flag is what draws the button. */
    @Test
    void theClientHearsWhereThePhaseStands() {
        LevelServer level = new LevelServer(board(PreparationData.MANUAL));
        CapturingBridge bridge = new CapturingBridge();
        tick(level, bridge, 1);
        assertTrue(bridge.packets.stream().anyMatch(packet -> packet instanceof MechanicSyncS2C sync
                        && PvzceIds.MECHANIC_PREPARATION.equals(sync.mechanic())),
                "the state is streamed, or the HUD would never learn there is a phase");

        bridge.packets.clear();
        level.beginWaves();
        tick(level, bridge, 1);
        assertTrue(bridge.packets.stream().anyMatch(packet -> packet instanceof MechanicSyncS2C sync
                        && PvzceIds.MECHANIC_PREPARATION.equals(sync.mechanic())),
                "and again when it ends, so the button goes away");
    }

    /**
     * Cards do not recharge while the phase runs, and they do again once it is over.
     *
     * <p>The build phase is where a level hands the player a purse and nothing else; a seed packet
     * that made them stand and wait for it is a wait with no level behind it. Last Stand is the
     * level this was reported on - its whole opening is "arrange a defence with the sun you were
     * given" - and the rule is the phase's rather than that level's, because it is what the phase
     * means: nothing is happening yet.
     */
    @Test
    void cardsDoNotRechargeWhileThePhaseRuns() {
        LevelServer level = new LevelServer(board(PreparationData.MANUAL));
        CapturingBridge bridge = new CapturingBridge();
        com.pvzce.common.core.Slot slot = level.plantPlayer().slots().stream()
                .filter(candidate -> SUNFLOWER.equals(candidate.defId()))
                .findFirst().orElse(null);
        assertNotNull(slot, "the board's bar has the sunflower on it");
        assertTrue(slot.cooldownTicks() > 0, "and it has a cooldown to skip");

        assertEquals(0, level.effectiveCooldownTicks(slot),
                "during the phase a spent card is ready again at once");

        level.beginWaves();
        tick(level, bridge, 1);
        assertEquals(slot.cooldownTicks(), level.effectiveCooldownTicks(slot),
                "once the waves run, the card waits its own cooldown again");
    }

    /**
     * A cue written against the waves does not fire while the phase is still up.
     *
     * <p>This is the whole reason a cue has two clocks. A rhythm level's song <em>is</em> its chart's
     * clock, so it may only start on the tick the chart does - and that tick is the one the player
     * chose when they pressed 开始, which no level file can name in advance. Without the second
     * trigger the song played over the build phase and the notes arrived however many seconds late
     * the player had spent arranging the lawn.
     */
    @Test
    void aCueWrittenAgainstTheWavesWaitsForThem() {
        LevelDef demo = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/demo_level"));
        assertNotNull(demo);
        LevelDef board = com.pvzce.testutil.TestLevels.copy(demo)
                .waves(List.of())
                .music(new LevelDef.LevelMusicDef(List.of(
                        new LevelDef.MusicCue(LevelDef.MusicCue.Trigger.LEVEL_START, 0, "background",
                                java.util.Optional.empty(), false, true, 1F, 0F, false),
                        new LevelDef.MusicCue(LevelDef.MusicCue.Trigger.WAVES_START, 0, "background",
                                java.util.Optional.of(PvzceIds.id("music/ancient_egypt_ultimate_battle")),
                                false, false, 0.85F, 0F, false))))
                .mechanics(List.of(new TypedMechanic(PvzceIds.MECHANIC_PREPARATION,
                        PreparationData.MANUAL)))
                .build();
        LevelServer level = new LevelServer(board);
        CapturingBridge bridge = new CapturingBridge();

        tick(level, bridge, 1);
        List<MusicEventS2C> before = music(bridge);
        assertEquals(1, before.size(), "only the level-start cue has fired: the song is waiting");
        assertTrue(before.get(0).stop(), "and what it fired is the silence of the build phase");

        bridge.packets.clear();
        level.beginWaves();
        tick(level, bridge, 1);
        List<MusicEventS2C> after = music(bridge);
        assertEquals(1, after.size(), "the song starts on the tick the waves do");
        assertEquals("pvzce:music/ancient_egypt_ultimate_battle", after.get(0).event());
        assertEquals(0F, after.get(0).fadeSeconds(), 0.0001F,
                "with no fade: a fade is a start time that is not the cue's, and a chart cannot"
                        + " wait for one");
    }

    private static List<MusicEventS2C> music(CapturingBridge bridge) {
        return bridge.packets.stream().filter(MusicEventS2C.class::isInstance)
                .map(MusicEventS2C.class::cast).toList();
    }
}
