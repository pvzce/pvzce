package com.pvzce.client.renderer;

import com.pvzce.client.PvzceClient;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The board's lighting, and the one place the level's time of day is read before the level runs.
 *
 * <p>The seed chooser used to preview a night level in broad daylight, because the only clock it
 * could read was the one inside a running level. It reads the level's own file now; what this
 * pins down is that the numbers that come out of that file are the numbers the level itself
 * lights up with - the same helper, one clock instead of two - and that reading a level's rules
 * without a window is possible at all.
 */
class TimeOfDayLightingTest {
    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    /** A level with no day is night immediately - not after a dusk nobody will ever see. */
    @Test
    void aFixedNightLevelIsLitAsNight() {
        TimeOfDayLighting.Lighting night = TimeOfDayLighting.compute(0F, 0, 360000, 9, 5);
        assertEquals(1F, night.nightBlend(), 0.0001F);
        assertEquals(0.40F, night.tintR(), 0.0001F);
        assertEquals(0.46F, night.tintG(), 0.0001F);
        assertEquals(0.78F, night.tintB(), 0.0001F, "the cool blue moonlight tint");
        assertEquals(0.34F, night.strength(), 0.0001F);

        TimeOfDayLighting.Lighting later = TimeOfDayLighting.compute(60 * 60 * 24F, 0, 360000, 9, 5);
        assertEquals(night.tintR(), later.tintR(), 0.0001F, "and it stays night");
        assertEquals(night.nightBlend(), later.nightBlend(), 0.0001F);
    }

    /** Day levels are untouched by any of this: warm, neutral, bright. */
    @Test
    void aDayLevelIsLitAsDay() {
        TimeOfDayLighting.Lighting day = TimeOfDayLighting.compute(0F, 0, -1, 9, 5);
        assertEquals(0F, day.nightBlend(), 0.0001F);
        assertEquals(1.04F, day.tintR(), 0.0001F);
        assertEquals(0.94F, day.tintB(), 0.0001F, "warm by day, not blue");
        assertTrue(day.tintB() > day.tintR() - 0.2F);
    }

    /** 1-10 opens in its minute of daylight and crosses over at the end of it. */
    @Test
    void aCycleLevelOpensInItsDayAndTurnsOver() {
        assertEquals(0F, TimeOfDayLighting.compute(0F, 3600, 360000, 9, 5).nightBlend(), 0.0001F,
                "the preview at tick zero is the level's own first minute of daylight");
        assertEquals(0F, TimeOfDayLighting.compute(3400F, 3600, 360000, 9, 5).nightBlend(), 0.0001F,
                "still day well before the end of it");
        float dusk = TimeOfDayLighting.compute(3600F, 3600, 360000, 9, 5).nightBlend();
        assertEquals(0.5F, dusk, 0.0001F, "the sun is half set exactly when the day ends");
        assertEquals(1F, TimeOfDayLighting.compute(3800F, 3600, 360000, 9, 5).nightBlend(), 0.0001F);
    }

    /** The glow is a distance, so a screen drawing in its own pixels has to move it. */
    @Test
    void theGlowMovesIntoTheCallersOwnPixels() {
        TimeOfDayLighting.Lighting world = TimeOfDayLighting.compute(0F, 0, 360000, 9, 5);
        TimeOfDayLighting.Lighting moved = TimeOfDayLighting.inRect(world, 100F, 200F, 80F, 100F);
        assertEquals(100F + world.sunX() * 80F, moved.sunX(), 0.01F);
        assertEquals(200F + world.sunY() * 100F, moved.sunY(), 0.01F);
        assertEquals(world.sunRadius() * 90F, moved.sunRadius(), 0.01F, "the mean of the two cells");
        assertEquals(world.tintR(), moved.tintR(), 0.0001F, "the tint is not positional");

        TimeOfDayLighting.Lighting tintOnly = TimeOfDayLighting.withoutGlow(moved);
        assertEquals(0F, tintOnly.strength(), 0.0001F, "a sub-view drops the glow, keeps the tint");
        assertEquals(moved.tintB(), tintOnly.tintB(), 0.0001F);
    }

    /** What the seed chooser asks: a night level is night, and a day level is not. */
    @Test
    void theChooserSeesTheLevelsOwnTimeOfDay() {
        assertNotNull(PvzceClient.levelLighting("pvzce:yard/adventure/2_1", 0F, 0F, 80F, 100F));
        assertEquals(1F, PvzceClient.levelLighting("pvzce:yard/adventure/2_1", 0F, 0F, 80F, 100F)
                .nightBlend(), 0.0001F, "2-1 is previewed at night");
        assertEquals(0F, PvzceClient.levelLighting("pvzce:yard/adventure/1_4", 0F, 0F, 80F, 100F)
                .nightBlend(), 0.0001F, "and a Day level is previewed in daylight");
        assertEquals(0F, PvzceClient.levelLighting("pvzce:yard/adventure/1_10", 0F, 0F, 80F, 100F)
                .nightBlend(), 0.0001F, "1-10 is previewed in its opening daylight");
        assertNull(PvzceClient.levelLighting("pvzce:nope", 0F, 0F, 80F, 100F),
                "an unknown level leaves the shader alone");
    }

    /** A level file that says nothing about time is a Day level, by the registry's defaults. */
    @Test
    void aLevelWithoutTimeRulesIsADayLevel() {
        int dayDefault = (int) com.pvzce.common.core.BuiltInRegistries.GAME_RULES
                .get(com.pvzce.common.PvzceIds.RULE_DAY_LENGTH).defaultValue();
        int nightDefault = (int) com.pvzce.common.core.BuiltInRegistries.GAME_RULES
                .get(com.pvzce.common.PvzceIds.RULE_NIGHT_LENGTH).defaultValue();
        assertEquals(0, dayDefault);
        assertEquals(-1, nightDefault);
        assertEquals(0F, TimeOfDayLighting.compute(0F, dayDefault, nightDefault, 9, 5).nightBlend(),
                0.0001F, "silence means daylight, which is what every Day level relies on");
    }
}
