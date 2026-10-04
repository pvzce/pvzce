package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.content.WeatherData;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.WeatherMechanic;
import com.pvzce.common.level.mechanic.WeatherState;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.network.packet.MusicEventS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The night roof's two pieces of showmanship: the forecast, and the drum layer.
 *
 * <p>Both are about the same moment from two sides - the sky is about to change, and the lawn is
 * about to get busy - and both are checked here because both are timing, which is the thing a
 * screenshot cannot show.
 */
class NightRoofShowTest {
    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    /**
     * A roof level with a written forecast and a written wave table, and nothing else moving.
     *
     * <p>Built from 6-1's own body so the roof geometry, the night clock and the level's rules are
     * the real ones; only the waves and the weather are replaced, because this is a test about
     * when a banner appears rather than about the shipped pacing.
     */
    private static LevelServer roof(List<WaveDef> waves, WeatherData forecast) {
        var def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/6_1"));
        return new LevelServer(TestLevels.copy(def)
                .waves(waves)
                .initialEntities(List.of())
                .mechanics(List.of(
                        new TypedMechanic(PvzceIds.MECHANIC_WEATHER, forecast),
                        new TypedMechanic(PvzceIds.MECHANIC_MOWER,
                                new com.pvzce.api.content.MowerData(Optional.of(List.of())))))
                .build());
    }

    private static WaveDef small(int delay) {
        return new WaveDef(WaveDef.WaveType.SMALL, delay, 0,
                List.of(new WaveDef.Entry(Identifier.withDefaultNamespace("basic_zombie"), 1)),
                Optional.of(30), Optional.of(0));
    }

    private static WaveDef last(int delay) {
        return new WaveDef(WaveDef.WaveType.FINAL, delay, 0,
                List.of(new WaveDef.Entry(Identifier.withDefaultNamespace("basic_zombie"), 1)),
                Optional.of(30), Optional.of(0));
    }

    /** The weather as the level last published it. */
    private static WeatherState weather(LevelServer level) {
        return WeatherMechanic.stateOf(level);
    }

    /**
     * The forecast is shouted ten seconds before the wave that changes the sky, and once.
     *
     * <p>The lead is the whole feature: a warning at the wrong time is either a spoiler or a
     * notice that arrives after the rain. The wave table is written so the boundary is a known
     * tick - wave 1 on tick 10, wave 2 six hundred ticks later - and the change is put on wave 2.
     */
    @Test
    void theForecastArrivesTenSecondsBeforeTheSkyChanges() {
        LevelServer level = roof(List.of(small(10), last(1200)),
                new WeatherData(List.of(new WeatherData.Phase(1, WeatherData.Kind.CLEAR),
                        new WeatherData.Phase(2, WeatherData.Kind.RAIN))));
        List<PvzcePacket> sent = new ArrayList<>();

        // Wave 1 arrives on tick 10 and wave 2 is written 1200 ticks after it, so the sky changes
        // on the tick wave 2 arrives - around 1210 - and the forecast is due ten seconds before
        // that. Everything before the lead is silence: a warning given early is one the player
        // stops believing.
        tick(level, 609, sent);
        assertEquals(WeatherData.Kind.CLEAR, weather(level).weather());
        assertNull(weather(level).warning(), "the lead is not over yet");

        tick(level, 2, sent);
        assertEquals(WeatherState.Warning.MOON_BLOCKED, weather(level).warning(),
                "clear turning wet is announced as the moon goes behind the clouds");
        // The lead itself: at the moment the banner goes up there are no more than ten seconds
        // left, which is the number the feature is. Not exactly ten: the mechanic reads the wave
        // clock before the tick that moves it, so it can go up one tick late and never early.
        int left = level.countdownToWave(2);
        assertTrue(left > 0 && left <= com.pvzce.common.PvzceConstants.WEATHER_WARNING_TICKS,
                "the forecast is due inside the ten-second lead, was " + left + " ticks");

        // It stays up for the rest of the lead, without being repeated: the sequence counter is
        // what the client watches for "a new banner", so it may not move while one is up.
        int sequence = weather(level).sequence();
        tick(level, 500, sent);
        assertEquals(WeatherState.Warning.MOON_BLOCKED, weather(level).warning());
        assertEquals(sequence, weather(level).sequence(), "one banner per forecast, not per tick");

        tick(level, 100, sent);
        assertEquals(WeatherData.Kind.RAIN, weather(level).weather(), "the wave brought the rain");
        assertNull(weather(level).warning(), "and the forecast goes down with it");
    }

    /**
     * The four phrases, one per kind of change.
     *
     * <p>The mapping is a table rather than a rule, so it is pinned as one: a level's own forecast
     * decides which of these it can ever say, and getting two of them the wrong way round is the
     * kind of mistake that reads as a typo in the data rather than as a bug in the engine.
     */
    @Test
    void everyPhaseChangeHasItsOwnPhrase() {
        assertEquals(WeatherState.Warning.MOON_BLOCKED,
                WeatherState.warningFor(WeatherData.Kind.CLEAR, WeatherData.Kind.CLOUDY));
        assertEquals(WeatherState.Warning.MOON_BLOCKED,
                WeatherState.warningFor(WeatherData.Kind.CLEAR, WeatherData.Kind.RAIN));
        assertEquals(WeatherState.Warning.CLOUDS_COMING,
                WeatherState.warningFor(WeatherData.Kind.CLOUDY, WeatherData.Kind.RAIN));
        assertEquals(WeatherState.Warning.CLOUDS_CLEARING,
                WeatherState.warningFor(WeatherData.Kind.CLOUDY, WeatherData.Kind.CLEAR));
        assertEquals(WeatherState.Warning.MOON_RETURNING,
                WeatherState.warningFor(WeatherData.Kind.RAIN, WeatherData.Kind.CLEAR));
        assertNull(WeatherState.warningFor(WeatherData.Kind.RAIN, WeatherData.Kind.RAIN),
                "a phase that does not change the sky says nothing");
    }

    /**
     * A level with no forecast change never warns, however long it runs.
     *
     * <p>The negative case, and the one that would have shipped a banner on every roof level: a
     * forecast with a single phase has no next phase, and "the next phase is null" has to mean
     * silence rather than "warn about nothing".
     */
    @Test
    void aLevelWithOnePhaseNeverWarns() {
        LevelServer level = roof(List.of(small(10), last(1200)),
                new WeatherData(List.of(new WeatherData.Phase(1, WeatherData.Kind.RAIN))));
        List<PvzcePacket> sent = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            level.tick(sent::add);
            assertNull(weather(level).warning(), "one phase, nothing to announce");
        }
        assertEquals(WeatherData.Kind.RAIN, weather(level).weather());
    }

    /**
     * The written music is one batch: the theme and its drum layer start together.
     *
     * <p>What makes the drums land on the beat is that both cues fire on the same tick, so the
     * thing worth pinning is that the layer is sent in the same batch as the song - and that it
     * carries the flag, because that is what tells the client to keep it silent until the lawn is
     * crowded. The two files are the same length (144.000 s), so nothing else has to be arranged.
     */
    @Test
    void theRoofShipsItsThemeAndDrumLayerAsOneCue() throws Exception {
        for (String name : List.of("6_1", "6_2", "6_3", "6_4")) {
            LevelDef def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/" + name));
            assertNotNull(def, name + " is a shipped level");
            List<LevelDef.MusicCue> cues = def.music().cues();
            assertEquals(2, cues.size(), name + " plays a song and its drum layer");

            LevelDef.MusicCue theme = cues.get(0);
            LevelDef.MusicCue drums = cues.get(1);
            assertFalse(theme.manyZombiesLayer(), name + "'s first cue is the song itself");
            assertEquals("pvzce:original/roof_after_dark", theme.event().orElseThrow().toString());
            assertTrue(drums.manyZombiesLayer(), name + "'s second cue is the layer");
            assertEquals("pvzce:original/roof_after_dark_drums", drums.event().orElseThrow().toString());
            assertEquals(theme.trigger(), drums.trigger(), "written on the same clock");
            assertEquals(theme.atTick(), drums.atTick(), "and fired on the same tick");
            assertTrue(drums.volume() <= 1F && drums.volume() > 0F, "the layer has a level of its own");

            // And the files are really there, at the length the two of them have to share: two
            // layers of one song that did not end together would drift a little further apart on
            // every loop.
            assertEquals(oggSeconds(theme), oggSeconds(drums), 0.001,
                    name + "'s drum layer must be the same length as its song");
            assertEquals(144.006, oggSeconds(theme), 0.01, name + "'s song is the shipped file");
        }
    }

    /** The length of a shipped music file, read from its last Ogg page's granule position. */
    private static double oggSeconds(LevelDef.MusicCue cue) throws Exception {
        String path = "assets/pvzce/sounds/" + cue.event().orElseThrow().path() + ".ogg";
        java.net.URL resource = NightRoofShowTest.class.getClassLoader().getResource(path);
        assertNotNull(resource, path + " must be on the classpath");
        byte[] data = resource.openStream().readAllBytes();
        long granule = 0;
        int rate = 44100;
        for (int i = 0; i + 27 < data.length; i++) {
            if (data[i] != 'O' || data[i + 1] != 'g' || data[i + 2] != 'g' || data[i + 3] != 'S') {
                continue;
            }
            long page = 0;
            for (int b = 7; b >= 0; b--) {
                page = (page << 8) | (data[i + 6 + b] & 0xFFL);
            }
            granule = Math.max(granule, page);
            if (i > 0 && data[i + 4] == 1) {
                rate = (data[i + 12] & 0xFF) | ((data[i + 13] & 0xFF) << 8)
                        | ((data[i + 14] & 0xFF) << 16) | ((data[i + 15] & 0xFF) << 24);
            }
        }
        return granule / (double) Math.max(1, rate);
    }

    /**
     * The layer reaches the client marked as a layer, and the theme does not.
     *
     * <p>The packet's own round trip is {@code PacketProtocolTest}'s job; what this checks is the
     * level's timeline turning into the right packets at the right moment - the piece that would
     * silently ship a level whose drums are just a second song.
     */
    @Test
    void theTimelineSendsTheLayerOnTheSameTickAsTheTheme() {
        LevelDef def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/6_3"));
        LevelServer level = new LevelServer(TestLevels.copy(def).waves(List.of(small(10), last(600)))
                .initialEntities(List.of()).build());
        List<PvzcePacket> sent = new ArrayList<>();
        level.flushPending(sent::add);
        // The timeline is played from the level's tick, not from its construction.
        level.tick(sent::add);

        List<MusicEventS2C> music = sent.stream().filter(MusicEventS2C.class::isInstance)
                .map(MusicEventS2C.class::cast).toList();
        assertEquals(2, music.size(), "the theme and its layer, in one batch");
        assertFalse(music.get(0).manyZombiesLayer());
        assertEquals("pvzce:original/roof_after_dark", music.get(0).event());
        assertTrue(music.get(1).manyZombiesLayer(), "the second cue is the layer");
        assertEquals("pvzce:original/roof_after_dark_drums", music.get(1).event());
        assertTrue(music.get(1).loop(), "a layer loops with the song it belongs to");

        // A client that joins later is sent the same batch, or it would hear the drums from the
        // top while the song was a minute in.
        List<PvzcePacket> rejoined = new ArrayList<>();
        level.sendFullState(rejoined::add);
        assertEquals(2, rejoined.stream().filter(MusicEventS2C.class::isInstance).count(),
                "a joining client is told about both halves of the song");
    }

    private static void tick(LevelServer level, int ticks, List<PvzcePacket> sent) {
        for (int i = 0; i < ticks; i++) {
            level.tick(sent::add);
        }
    }
}
