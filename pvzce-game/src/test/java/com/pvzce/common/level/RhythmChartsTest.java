package com.pvzce.common.level;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.resource.PvzceDataLoader;
import com.pvzce.common.tag.TestContent;
import com.pvzce.testutil.SourceTree;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four rhythm tiers are the generator's output, and stay that way.
 *
 * <p>A generated level is only trustworthy while it is <em>still</em> what the generator writes: the
 * moment somebody hand-edits one of the four files, the editor's button and the shipped content
 * disagree, and the next person to press it silently reverts the edit. This test is the thing that
 * notices, and {@code -Ppvzce.smoke=pvzce.writeRhythmLevels=true} rewrites them instead of failing -
 * so regenerating is one command rather than a hand copy.
 *
 * <p>The rest of the class pins the four things a rhythm level has to get right and that nothing
 * else would notice: one kind of lane per level, a chart written against the track's own beats, an
 * end that the level actually stops at, and a bar with no cooldowns on it.
 */
class RhythmChartsTest {
    private static final String WRITE_FLAG = "pvzce.writeRhythmLevels";

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static Identifier idOf(RhythmCharts.Tier tier) {
        return PvzceIds.id("yard/rhythm/rhythm_" + tier.suffix());
    }

    private static final int VOLLEYS = com.pvzce.api.content.RhythmChartData.DEFAULT_ATTACK_VOLLEYS;
    private static final int PERFECT_SUN = com.pvzce.api.content.RhythmChartData.DEFAULT_PERFECT_SUN;

    private static JsonObject generated(RhythmCharts.Tier tier) {
        return JsonParser.parseString(RhythmCharts.levelJson(idOf(tier), tier.displayName(),
                RhythmCharts.MUSIC, RhythmCharts.BPM, tier, VOLLEYS, PERFECT_SUN,
                RhythmCharts.DEFAULT_INITIAL_SUN))
                .getAsJsonObject();
    }

    private static Path shippedPath(RhythmCharts.Tier tier) {
        Path root = SourceTree.root();
        return root == null ? null : root.resolve("pvzce-game/src/main/resources/data/pvzce/levels")
                .resolve("yard/rhythm/rhythm_" + tier.suffix() + ".json");
    }

    /** The chart block of a generated level. */
    private static JsonObject rhythmBlock(JsonObject level) {
        for (var element : level.getAsJsonArray("mechanics")) {
            JsonObject mechanic = element.getAsJsonObject();
            if ("pvzce:rhythm".equals(mechanic.get("type").getAsString())) {
                return mechanic;
            }
        }
        throw new AssertionError("the generated level has no rhythm block");
    }

    /** Every note of a chart, as `lane kind/index -> beat`, flattened to beats. */
    private static List<Double> notesOf(JsonObject rhythm) {
        List<Double> notes = new ArrayList<>();
        for (var laneElement : rhythm.getAsJsonArray("lanes")) {
            for (var note : laneElement.getAsJsonObject().getAsJsonArray("notes")) {
                notes.add(note.getAsDouble());
            }
        }
        return notes;
    }

    /** Same arguments, same file: what makes "regenerate and compare" a meaningful check at all. */
    @Test
    void theGeneratorIsDeterministic() {
        assertEquals(RhythmCharts.levelJson(idOf(RhythmCharts.TIERS[0]),
                        RhythmCharts.TIERS[0].displayName(), RhythmCharts.MUSIC, RhythmCharts.BPM,
                        RhythmCharts.TIERS[0], VOLLEYS, PERFECT_SUN, RhythmCharts.DEFAULT_INITIAL_SUN),
                RhythmCharts.levelJson(idOf(RhythmCharts.TIERS[0]),
                        RhythmCharts.TIERS[0].displayName(), RhythmCharts.MUSIC, RhythmCharts.BPM,
                        RhythmCharts.TIERS[0], VOLLEYS, PERFECT_SUN, RhythmCharts.DEFAULT_INITIAL_SUN));
    }

    /**
     * The four shipped files are what the generator writes for their tier.
     *
     * <p>Compared field by field on the parts the generator decides - the chart, the tempo and the
     * wave table - rather than as text: the files are pretty-printed and a formatter's line breaks
     * are not a fact about the content.
     */
    @Test
    void theShippedTiersAreWhatTheGeneratorWrites() throws IOException {
        Path file = shippedPath(RhythmCharts.TIERS[0]);
        Assumptions.assumeTrue(file != null, "not running from a source checkout");
        boolean write = Boolean.getBoolean(WRITE_FLAG);
        for (RhythmCharts.Tier tier : RhythmCharts.TIERS) {
            Path path = shippedPath(tier);
            String expected = RhythmCharts.levelJson(idOf(tier), tier.displayName(),
                    RhythmCharts.MUSIC, RhythmCharts.BPM, tier, VOLLEYS, PERFECT_SUN,
                    RhythmCharts.DEFAULT_INITIAL_SUN);
            if (write) {
                Files.writeString(path, expected + System.lineSeparator());
                continue;
            }
            assertTrue(Files.exists(path), "the shipped tier is missing: " + path);
            JsonObject actual = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            JsonObject want = JsonParser.parseString(expected).getAsJsonObject();
            assertEquals(want.get("mechanics"), actual.get("mechanics"),
                    tier.suffix() + " has been edited away from the generator;"
                            + " re-run with -Ppvzce.smoke=" + WRITE_FLAG + "=true");
            assertEquals(want.get("waves"), actual.get("waves"),
                    tier.suffix() + "'s wave table is the generator's");
            assertEquals(want.get("name").getAsString(), actual.get("name").getAsString(),
                    tier.suffix() + "'s name is the generator's");
            assertEquals(want.get("music"), actual.get("music"),
                    tier.suffix() + "'s music timeline is the generator's");
        }
        if (write) {
            return;
        }
        // And the shipped files are the ones the game loads, not copies beside it.
        for (RhythmCharts.Tier tier : RhythmCharts.TIERS) {
            assertNotNull(BuiltInRegistries.LEVELS.get(idOf(tier)),
                    "the game loads rhythm_" + tier.suffix());
        }
    }

    /**
     * One level, one way of playing it: every shipped tier is played on the same six columns.
     *
     * <p>The columns and not the rows, because the picture the player reads is a note flying down
     * its own column to the judgement line under the board - and six, because that is what the two
     * hands rest on ({@code S D F J K L}); the seventh to ninth columns are ordinary lawn.
     */
    @Test
    void everyTierPlaysTheSameSixColumns() {
        for (RhythmCharts.Tier tier : RhythmCharts.TIERS) {
            JsonObject rhythm = rhythmBlock(generated(tier));
            JsonArray lanes = rhythm.getAsJsonArray("lanes");
            assertEquals(6, lanes.size(), tier.suffix() + " plays six columns");
            for (int i = 0; i < lanes.size(); i++) {
                JsonObject lane = lanes.get(i).getAsJsonObject();
                assertEquals("col", lane.get("kind").getAsString(),
                        tier.suffix() + " is played on columns, so its key hints have one home");
                assertEquals(i, lane.get("index").getAsInt(),
                        tier.suffix() + "'s lanes are the lawn's first six columns, in order");
            }
        }
    }

    /**
     * The chart holds the lawn's fire, and its notes are worth attacks.
     *
     * <p>The mode's two halves: a level whose plants shot by themselves would be played by the lawn,
     * and a hold-fire chart whose notes ordered nothing would be a level where nothing can happen
     * ({@code RhythmMechanic.validate} refuses that one).
     */
    @Test
    void theChartHoldsTheLawnsFireAndPaysForNotes() {
        for (RhythmCharts.Tier tier : RhythmCharts.TIERS) {
            JsonObject rhythm = rhythmBlock(generated(tier));
            assertTrue(rhythm.get("plants_hold_fire").getAsBoolean(),
                    tier.suffix() + " is played on the keyboard rather than by the plants");
            assertEquals(VOLLEYS, rhythm.get("attack_volleys").getAsInt(),
                    tier.suffix() + "'s perfect note is worth three attacks");
            assertEquals(PERFECT_SUN, rhythm.get("perfect_sun").getAsInt(),
                    tier.suffix() + " drops a sun, not a credit");
            assertEquals(com.pvzce.api.content.RhythmChartData.DEFAULT_APPROACH_TICKS,
                    rhythm.get("approach_ticks").getAsInt(),
                    tier.suffix() + "'s notes fly for the standard flight, which is what the"
                            + " windows are fractions of");
            assertFalse(rhythm.has("perfect_ticks"),
                    tier.suffix() + " writes no window of its own: the windows are the flight's");
        }
    }

    /**
     * The horde is part of the tier, and every tier is faster than the tables are written for.
     *
     * <p>The three numbers a tier is: how fast its zombies walk, how fast they arrive, and how many
     * the table holds. The report this answers was "the zombies are too few and too slow", so the
     * floors - twice the walk speed, faster than written arrival, every entry naming every row - are
     * pinned rather than left to the eye.
     */
    @Test
    void everyTierIsAHorde() {
        double previousSpeed = 0D;
        int previousCount = 0;
        for (RhythmCharts.Tier tier : RhythmCharts.TIERS) {
            JsonObject level = generated(tier);
            JsonObject rules = level.getAsJsonObject("rules");
            double speed = rules.get("pvzce:zombie_speed_multiplier").getAsDouble();
            double cadence = rules.get("pvzce:zombie_spawn_speed_multiplier").getAsDouble();
            assertTrue(speed >= 2D, tier.suffix() + " walks its zombies at least twice as fast");
            assertTrue(cadence > 1D, tier.suffix() + " arrives faster than the table is written");
            assertTrue(speed > previousSpeed, tier.suffix() + " is faster than the tier below it");
            previousSpeed = speed;

            int count = 0;
            JsonArray waves = level.getAsJsonArray("waves");
            assertTrue(waves.size() >= 6, tier.suffix() + " has a table long enough for the song");
            for (var waveElement : waves) {
                JsonObject wave = waveElement.getAsJsonObject();
                for (var entryElement : wave.getAsJsonArray("entries")) {
                    JsonObject entry = entryElement.getAsJsonObject();
                    count += entry.get("count").getAsInt();
                    assertEquals(5, entry.getAsJsonArray("rows").size(),
                            tier.suffix() + " names every row, so no row can be left alone");
                }
            }
            assertTrue(count > previousCount, tier.suffix() + " is a bigger horde than the last");
            previousCount = count;
            assertTrue(count >= 30, tier.suffix() + " is a horde and not a queue: " + count);
        }
    }

    /** The four tiers are a curve: each one asks for more of the track's accents than the last. */
    @Test
    void theTiersAreACurve() {
        int previous = 0;
        for (RhythmCharts.Tier tier : RhythmCharts.TIERS) {
            List<Double> notes = notesOf(rhythmBlock(generated(tier)));
            assertFalse(notes.isEmpty(), tier.suffix() + " has notes");
            assertTrue(notes.size() > previous,
                    tier.suffix() + " asks for more notes than the tier before it");
            previous = notes.size();
        }
    }

    /**
     * The notes are the track's own accents, not a metronome over it.
     *
     * <p>What "follows the music" means in a number: the easy tier takes the loudest beats, so most
     * of its notes sit on a beat the analysis calls strong - and every tier's notes fall inside the
     * chart window rather than trailing into the file's silent run-out.
     */
    @Test
    void theNotesLandOnTheTracksAccents() {
        JsonObject easy = rhythmBlock(generated(RhythmCharts.TIERS[0]));
        List<Double> notes = notesOf(easy);
        int strong = 0;
        for (double beat : notes) {
            int slot = (int) Math.round(beat * 4);
            if (RhythmCharts.onsetStrength(slot) >= 8) {
                strong++;
            }
        }
        assertTrue(strong * 10 >= notes.size() * 7,
                "the easy tier takes the strong beats: " + strong + " of " + notes.size());
        for (RhythmCharts.Tier tier : RhythmCharts.TIERS) {
            for (double beat : notesOf(rhythmBlock(generated(tier)))) {
                assertTrue(beat >= RhythmCharts.FIRST_BEAT && beat < RhythmCharts.END_BEAT,
                        tier.suffix() + " has a note at beat " + beat + ", outside the track");
            }
        }
    }

    /**
     * The chart ends where the song does, and the level ends with it.
     *
     * <p>The one place a rhythm level's ending is written down: {@code end_beat} is the tick the
     * lawn is swept and the run is won, so a chart without it would be a chart the level never
     * stops for.
     */
    @Test
    void everyTierEndsWithTheTrack() {
        for (RhythmCharts.Tier tier : RhythmCharts.TIERS) {
            JsonObject rhythm = rhythmBlock(generated(tier));
            assertEquals(RhythmCharts.END_BEAT, rhythm.get("end_beat").getAsDouble(), 0.0001D,
                    tier.suffix() + " ends where the track's last bar lands");
            for (double beat : notesOf(rhythm)) {
                assertTrue(beat < RhythmCharts.END_BEAT,
                        tier.suffix() + " has a note after its own end, which nobody could reach");
            }
        }
    }

    /** The song starts when the waves do: the chart's clock and the music's are the same tick. */
    @Test
    void theSongStartsWithTheWaves() {
        for (RhythmCharts.Tier tier : RhythmCharts.TIERS) {
            JsonArray cues = generated(tier).getAsJsonObject("music").getAsJsonArray("cues");
            assertEquals(2, cues.size(), tier.suffix() + " silences the theme and then plays the song");
            JsonObject silence = cues.get(0).getAsJsonObject();
            assertEquals("level_start", silence.get("trigger").getAsString(),
                    "the build phase is quiet from the level's own first tick");
            assertTrue(silence.get("stop").getAsBoolean());
            JsonObject song = cues.get(1).getAsJsonObject();
            assertEquals("waves_start", song.get("trigger").getAsString(),
                    tier.suffix() + "'s song may only start on the tick the chart does");
            assertEquals(RhythmCharts.MUSIC, song.get("event").getAsString());
            assertFalse(song.get("loop").getAsBoolean(),
                    "the song's last bar is the level's last tick, so there is nothing to loop into");
            assertEquals(0.0D, song.get("fade_seconds").getAsDouble(), 0.0001D,
                    "a fade is a start time that is not the cue's, and a chart cannot wait for one");
        }
    }

    /** The bar is free: no cooldowns, and enough sun to fill a lawn before the first beat. */
    @Test
    void theBarHasNoCooldownsAndEnoughSun() {
        for (RhythmCharts.Tier tier : RhythmCharts.TIERS) {
            JsonObject level = generated(tier);
            assertEquals(0.0D, level.getAsJsonObject("rules")
                    .get("pvzce:seed_cooldown_multiplier").getAsDouble(), 0.0001D,
                    tier.suffix() + " hands out cards with no recharge");
            assertEquals(RhythmCharts.DEFAULT_INITIAL_SUN, level.get("initial_sun").getAsInt(),
                    tier.suffix() + " opens with the sun the mode is tuned around");
            assertTrue(level.getAsJsonObject("rules").get("pvzce:plant_whole_column").getAsBoolean(),
                    tier.suffix() + " plants a whole column for one card");
        }
    }

    /** The data loader knows where these files live; the path built here must agree. */
    @Test
    void theTierIdsAreWhereTheGeneratorWritesThem() {
        for (RhythmCharts.Tier tier : RhythmCharts.TIERS) {
            Identifier id = idOf(tier);
            assertEquals("pvzce", id.namespace(), tier.suffix() + " is in this pack");
            assertEquals("yard/rhythm/rhythm_" + tier.suffix(), id.path(),
                    tier.suffix() + "'s path is its file's path, which is what decides its page");
        }
        assertTrue(PvzceDataLoader.CONTENT_REGISTRIES.stream()
                        .anyMatch(entry -> entry.contentPath().equals("levels")),
                "levels are loaded from the directory the generator writes into");
    }

    /** The track the generator is written against is a registered music event, or it is nothing. */
    @Test
    void theProfiledTrackIsReal() {
        assertEquals(PvzceSounds.MUSIC_ANCIENT_EGYPT_ULTIMATE_BATTLE.toString(), RhythmCharts.MUSIC);
        assertNotNull(BuiltInRegistries.SOUND_EVENTS.get(PvzceIds.id("music/ancient_egypt_ultimate_battle")),
                "the chart generator names a track the pack actually has");
    }
}
