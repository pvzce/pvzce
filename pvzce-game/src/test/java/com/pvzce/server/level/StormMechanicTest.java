package com.pvzce.server.level;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.StormData;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.level.mechanic.StormMechanic;
import com.pvzce.common.level.mechanic.StormState;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The storm: the flash cycle, the darkness it publishes, and what that darkness hides.
 *
 * <p>The fog's twin in every respect - presentation only, one piece of server state, one packet -
 * with one difference worth testing: it <em>changes over time</em>, so the things a player would
 * notice if they broke are all about when. A storm that never gets dark is not a storm level; one
 * that never flashes is a black screen; one whose hiding rule lags its drawing shows zombies on a
 * black lawn, which is the one thing the level is built not to do.
 */
class StormMechanicTest {
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

    /** 4-10 with its waves taken out, so a tick is the storm's and nothing else's. */
    private static LevelServer storm() {
        LevelDef def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/4_10"));
        assertNotNull(def, "the shipped 4-10 has to load");
        return new LevelServer(TestLevels.copy(def).waves(List.of()).build());
    }

    /** The shipped level's own numbers, read from the file rather than restated here. */
    @Test
    void theShippedLevelDeclaresAStorm() {
        LevelDef def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/4_10"));
        StormData storm = LevelMechanics.stormData(def);
        assertNotNull(storm, "4-10 is the level the storm mechanic exists for");
        assertNotNull(def, "and it has to be a level at all");
        assertTrue(storm.intervalTicks() > storm.flashTicks(),
                "a storm is dark for most of its cycle: " + storm.intervalTicks() + " vs "
                        + storm.flashTicks());
        assertTrue(storm.maxAlpha() > 0.5F,
                "and dark enough to be a blackout rather than a dim evening");
        assertTrue(storm.validate().isEmpty(), "the shipped block has to be a valid one");
    }

    /**
     * The cycle is a clock, and a whole interval lands back on a strike.
     *
     * <p>Asserted as the three edges that matter rather than as a trace: the level opens on a
     * strike, the next one comes exactly {@code interval_ticks} later, and the tick immediately
     * after a strike is no longer one. The last of the three is the one that would fail if the
     * counter were ever compared with {@code <=} instead of {@code ==}.
     */
    @Test
    void theCycleStrikesOncePerInterval() {
        LevelServer level = storm();
        StormData data = LevelMechanics.stormData(
                BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/4_10")));
        Bridge bridge = new Bridge();

        assertEquals(0, StormMechanic.stateOf(level, data).tick(),
                "a storm level opens on a strike, so the player sees the board they start on");
        assertTrue(StormMechanic.stateOf(level, data).striking());

        level.tick(bridge);
        assertFalse(StormMechanic.stateOf(level, data).striking(), "one tick later it is not");

        // Up to the last tick before the next strike: one short of a whole interval, because the
        // strike itself is the tick after that.
        for (int i = 1; i < data.intervalTicks() - 1; i++) {
            level.tick(bridge);
        }
        StormState state = StormMechanic.stateOf(level, data);
        assertEquals(data.intervalTicks() - 1, state.tick(),
                "a full interval of ticks gets to the end of the cycle");
        level.tick(bridge);
        state = StormMechanic.stateOf(level, data);
        assertEquals(1L, state.pulse(), "and the next tick is the next strike");
        assertEquals(0, state.tick(), "which starts the cycle over");
        assertTrue(state.striking(), "and is a strike");
    }

    /**
     * A strike is a shape, not a fade: it starts and ends in the dark and flickers in between.
     *
     * <p>Reported by the player about the first cut - "有光的时间太短了，且太均匀了" - and both
     * halves are properties of the shape table rather than of the level's numbers. The length is
     * asserted from the level's own {@code flash_ticks}; the unevenness is asserted as "the
     * brightness goes up and down more than once", which is what a smooth curve of any length
     * fails and what the eye is actually reading when it says "that is lightning".
     */
    @Test
    void aStrikeStartsAndEndsDarkAndFlickersInBetween() {
        StormData data = new StormData(600, 96, 0.94F);
        assertEquals(0F, StormState.at(0L, 0).darkness(data), 0.0001F,
                "the strike's own instant is full light: the board is exactly as bright as it is"
                        + " without a storm");
        assertEquals(0F, StormState.at(0L, 1).darkness(data), 0.0001F,
                "and the tick after it, so the strike opens on its brightest sample");
        assertEquals(data.maxAlpha(), StormState.at(0L, data.flashTicks()).darkness(data), 0.0001F,
                "the end of the strike is the dark again, so the shape joins the dark phase");
        assertEquals(data.maxAlpha(),
                StormState.at(0L, data.intervalTicks() - 1).darkness(data), 0.0001F,
                "and so is every tick until the next one");

        // The flicker: the light has to come back up at least once after falling, or the strike is
        // the one bright instant the player complained about.
        int rises = 0;
        float previous = StormState.at(0L, 0).exposure(data);
        for (int tick = 1; tick < data.flashTicks(); tick++) {
            float exposure = StormState.at(0L, tick).exposure(data);
            if (exposure > previous + 0.05F) {
                rises++;
            }
            previous = exposure;
        }
        assertTrue(rises >= 2, "a strike flickers - the light comes back up - rather than fading"
                + " once; this one rises " + rises + " times");

        // And no two consecutive strikes use the same shape.
        assertNotSame(StormState.patternFor(0L), StormState.patternFor(1L),
                "consecutive strikes have to differ, or the storm reads as a blinking light");
    }

    /**
     * The hiding rule turns over with the light, and not a tick late.
     *
     * <p>The storm's whole idea is "the flash is the only way to see", so the two have to be the
     * same transition: a frame of either one alone is a zombie the player can see on a black lawn
     * or a lawn that dims onto an empty board.
     */
    @Test
    void whatTheDarkHidesIsWhatTheDarkCovers() {
        StormData data = new StormData(600, 96, 0.94F);
        boolean hiddenWhileLit = false;
        boolean visibleWhileDark = false;
        for (int tick = 0; tick < data.intervalTicks(); tick++) {
            StormState state = StormState.at(0L, tick);
            boolean dark = state.darkness(data) >= data.maxAlpha() * 0.5F;
            if (state.hidesAt(data) && !dark) {
                hiddenWhileLit = true;
            }
            if (!state.hidesAt(data) && dark) {
                visibleWhileDark = true;
            }
        }
        assertFalse(hiddenWhileLit, "nothing may be hidden while the board is lit");
        assertFalse(visibleWhileDark, "and nothing may be drawn while it is black");
    }

    /** A level with no storm hides nothing and draws nothing, whatever its state slot says. */
    @Test
    void aLevelWithoutAStormIsNeverDark() {
        assertNull(LevelMechanics.stormData(
                BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/4_9"))));
        assertFalse(StormState.at(0L, 0).hidesAt(null), "no data, no dark, nothing hidden");
        assertEquals(1F, StormState.at(0L, 0).exposure(null), 0.0001F);
    }

    /**
     * A level with no music says so out loud, once.
     *
     * <p>The client enters a level with the ordinary theme already playing
     * ({@code PvzceMusicController.startLevel}) and waits for the level's first cue to replace it.
     * A level whose block is empty therefore has to <em>stop</em> the tracks rather than say
     * nothing, or the previous level's music plays over it - which is what 4-10 did, the one level
     * in the game whose soundtrack is meant to be the rain and nothing else.
     */
    @Test
    void aLevelWithNoMusicStopsTheTracksAtTickZero() {
        LevelServer level = storm();
        Bridge bridge = new Bridge();
        level.tick(bridge);
        List<com.pvzce.common.network.packet.MusicEventS2C> stops = bridge.packets.stream()
                .filter(com.pvzce.common.network.packet.MusicEventS2C.class::isInstance)
                .map(com.pvzce.common.network.packet.MusicEventS2C.class::cast)
                .toList();
        assertFalse(stops.isEmpty(), "silence has to be sent, not implied");
        assertTrue(stops.stream().allMatch(com.pvzce.common.network.packet.MusicEventS2C::stop),
                "and it is a stop, not a track with an empty name");
        assertTrue(stops.stream().anyMatch(s -> "background".equals(s.track())),
                "the track a level's own theme plays on is the one that has to be silenced");

        Bridge later = new Bridge();
        level.tick(later);
        assertTrue(later.packets.stream()
                        .noneMatch(com.pvzce.common.network.packet.MusicEventS2C.class::isInstance),
                "and it is said once, not every tick");
    }

    /** The state travels: one sync a tick, carrying the cycle the client draws from. */
    @Test
    void everyTickPublishesTheCycle() {
        LevelServer level = storm();
        Bridge bridge = new Bridge();
        level.tick(bridge);
        List<MechanicSyncS2C> syncs = bridge.packets.stream()
                .filter(MechanicSyncS2C.class::isInstance)
                .map(MechanicSyncS2C.class::cast)
                .filter(packet -> PvzceIds.MECHANIC_STORM.equals(packet.mechanic()))
                .toList();
        assertEquals(1, syncs.size(), "one storm state per tick, no more and no fewer");
        StormState.Wire decoded =
                StormState.Wire.decode(syncs.get(0).payloadBuffer());
        assertEquals(0L, decoded.pulse(), "carrying the strike the level is on");
        assertTrue(decoded.exposure() > 0.5F, "and the brightness it is at - the level opens lit");
    }

    /** A resumed run keeps its strike count instead of restarting the storm. */
    @Test
    void theCycleSurvivesASave() {
        LevelServer level = storm();
        StormData data = LevelMechanics.stormData(
                BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/4_10")));
        Bridge bridge = new Bridge();
        for (int i = 0; i < 100; i++) {
            level.tick(bridge);
        }
        long pulse = StormMechanic.stateOf(level, data).pulse();
        int tick = StormMechanic.stateOf(level, data).tick();

        com.pvzce.common.nbt.CompoundTag root = new com.pvzce.common.nbt.CompoundTag();
        StormMechanic mechanic = LevelMechanics.STORM;
        mechanic.collectSave(level, data, root);
        LevelServer restored = storm();
        mechanic.applySave(restored, data, root);

        assertEquals(pulse, StormMechanic.stateOf(restored, data).pulse());
        assertEquals(tick, StormMechanic.stateOf(restored, data).tick());
    }

    /**
     * The hiding rule is a fraction of the level's own ceiling, so a level that only dims its
     * board never hides anything.
     *
     * <p>Half of 0.4 is 0.2, and the dark phase of that storm is 0.4 - so the whole cycle is
     * playable at dusk. It is the same rule the fog uses expressed as a fraction for this reason:
     * "cannot be made out" has to mean the same thing on a level that blackens the lawn and on one
     * that only darkens it.
     */
    @Test
    void aDimStormHidesNothing() {
        StormData dim = new StormData(600, 60, 0.4F);
        for (int tick = 0; tick < dim.intervalTicks(); tick += 7) {
            assertFalse(StormState.at(0L, tick).hidesAt(dim),
                    "a dim evening still shows the lawn, at tick " + tick);
        }
    }

    /**
     * A block nobody could play is folded into one they could.
     *
     * <p>The folding happens in the record's own constructor, so <b>no JSON can express a broken
     * storm</b>: {@code validate()} therefore reports nothing for these, and asserting that it did
     * was asserting the wrong half. What is worth pinning is the half that runs - each number
     * lands in its own range rather than being refused, so a level author's typo is a slightly
     * wrong storm instead of a board that throws or a black screen that never lifts.
     */
    @Test
    void aBrokenBlockIsFoldedIntoAPlayableOne() {
        StormData tooFast = new StormData(1, 1, 0.9F);
        assertEquals(StormData.MIN_INTERVAL_TICKS, tooFast.intervalTicks(),
                "a storm that re-strikes every tick is a strobe, not a level");
        assertEquals(StormData.MIN_FLASH_TICKS, tooFast.flashTicks(),
                "and a flash below the floor is a flicker, not lightning");
        assertTrue(tooFast.validate().isEmpty(), "so there is nothing left to report");

        StormData tooLong = new StormData(600, 900, 0.9F);
        assertEquals(600, tooLong.flashTicks(),
                "a flash that outlasts its cycle is folded to the whole cycle, and not to a board"
                        + " that never gets dark");
        assertEquals(1F, new StormData(600, 60, 4F).maxAlpha(), 0.0001F,
                "an out-of-range alpha is clamped rather than left to the renderer");
        assertEquals(0F, new StormData(600, 60, -1F).maxAlpha(), 0.0001F);
    }
}
