package com.pvzce.common.level;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 */
class RhythmChartsTest {
    private static final String WRITE_FLAG = "pvzce.writeRhythmLevels";

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static Identifier idOf(RhythmCharts.Tier tier) {
        return PvzceIds.id("yard/minigame/rhythm_" + tier.suffix());
    }

    private static JsonObject generated(RhythmCharts.Tier tier) {
        return JsonParser.parseString(RhythmCharts.levelJson(idOf(tier), tier.displayName(),
                "pvzce:music/cerebrawl", tier.bpm(), tier.density(), tier.rows(), tier.cols(),
                tier.waves(), 300, 1)).getAsJsonObject();
    }

    private static Path shippedPath(RhythmCharts.Tier tier) {
        Path root = SourceTree.root();
        return root == null ? null : root.resolve("pvzce-game/src/main/resources/data/pvzce/levels")
                .resolve("yard/minigame/rhythm_" + tier.suffix() + ".json");
    }

    /** Same arguments, same file: what makes "regenerate and compare" a meaningful check at all. */
    @Test
    void theGeneratorIsDeterministic() {
        RhythmCharts.Tier tier = RhythmCharts.TIERS[0];
        assertEquals(RhythmCharts.levelJson(idOf(tier), tier.displayName(), "pvzce:music/cerebrawl",
                        120D, tier.density(), tier.rows(), tier.cols(), tier.waves(), 300, 1),
                RhythmCharts.levelJson(idOf(tier), tier.displayName(), "pvzce:music/cerebrawl",
                        120D, tier.density(), tier.rows(), tier.cols(), tier.waves(), 300, 1));
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
                    "pvzce:music/cerebrawl", tier.bpm(), tier.density(), tier.rows(), tier.cols(),
                    tier.waves(), 300, 1);
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

    /** The four tiers are a curve: each one plays more notes than the one before it. */
    @Test
    void theTiersAreACurve() {
        int previous = 0;
        for (RhythmCharts.Tier tier : RhythmCharts.TIERS) {
            int lanes = tier.rows().size() + tier.cols().size();
            int notes = 0;
            for (int row : tier.rows()) {
                notes += countNotes(tier.density(), 0);
            }
            for (int col : tier.cols()) {
                notes += countNotes(tier.density(), 0);
            }
            assertTrue(lanes >= 2, tier.suffix() + " has at least two lanes to play");
            assertTrue(notes > previous, tier.suffix() + " is denser than the tier before it");
            previous = notes;
        }
    }

    private static int countNotes(double density, int ordinal) {
        double step = 1D / Math.max(0.2D, density);
        int count = 0;
        for (double beat = 4D; beat < RhythmCharts.LENGTH_BEATS; beat += step) {
            count++;
        }
        return count;
    }

    /** The data loader knows where these files live; the path built here must agree. */
    @Test
    void theTierIdsAreWhereTheGeneratorWritesThem() {
        for (RhythmCharts.Tier tier : RhythmCharts.TIERS) {
            Identifier id = idOf(tier);
            assertEquals("pvzce", id.namespace(), tier.suffix() + " is in this pack");
            assertEquals("yard/minigame/rhythm_" + tier.suffix(), id.path(),
                    tier.suffix() + "'s path is its file's path, which is what decides its page");
        }
        assertTrue(PvzceDataLoader.CONTENT_REGISTRIES.stream()
                        .anyMatch(entry -> entry.contentPath().equals("levels")),
                "levels are loaded from the directory the generator writes into");
    }
}
