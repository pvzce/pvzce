package com.pvzce.client.gui.components;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two full-screen banners, sampled without a window.
 *
 * <p>The timing and the poses come from the original's reanims
 * ({@code refer/anim/StartReadySetPlant.reanim}, {@code refer/anim/FinalWave.reanim}), so
 * what is worth pinning is the shape of the sequence: one word at a time, in order, each
 * word opaque, and the "FINAL WAVE" banner arriving transparent and leaving the same way.
 */
class BannerAnimationTest {

    @Test
    void readySetPlantShowsOneWordAtATimeInOrder() {
        BannerAnimation banner = BannerAnimation.readySetPlant();
        // The beats are the sound's (`readysetplant.ogg`): the words land on its two thuds at 0.00s
        // and 0.60s, and 植物！holds over its passage, which runs to 3.30s.
        assertEquals(3.25F, banner.duration(), 0.01F);

        List<BannerAnimation.Placed> first = banner.sample(0.1F);
        assertEquals(1, first.size(), "exactly one word is on screen at a time");
        assertEquals("准备…", first.get(0).text(), "the words are the game's, in the player's language");
        assertEquals(1F, first.get(0).alpha(), 0.0001F, "the words are opaque");

        List<BannerAnimation.Placed> second = banner.sample(0.6F);
        assertEquals(1, second.size());
        assertEquals("安放…", second.get(0).text());
        List<BannerAnimation.Placed> third = banner.sample(1.2F);
        assertEquals(1, third.size());
        assertEquals("植物！", third.get(0).text());
        assertEquals(1.3F, third.get(0).scale(), 0.0001F, "\"Plant!\" lands at its authored size");

        // It holds, rather than flashing past: the last word is still up a second later.
        assertEquals("植物！", banner.sample(2.2F).get(0).text());
        assertTrue(banner.sample(banner.duration()).isEmpty(), "and it is over when it is over");
        assertTrue(banner.sample(-1F).isEmpty(), "a negative clock draws nothing");
    }

    @Test
    void eachWordGrowsAndDriftsWhileItIsUp() {
        BannerAnimation banner = BannerAnimation.readySetPlant();
        BannerAnimation.Placed start = banner.sample(0.0F).get(0);
        BannerAnimation.Placed end = banner.sample(0.45F).get(0);

        assertTrue(end.scale() > start.scale(), "the word grows in");
        assertTrue(end.offsetX() < start.offsetX(), "and drifts to the left, as the reanim does");
        assertTrue(end.offsetY() < start.offsetY(), "and upwards");
    }

    @Test
    void finalWaveFliesInFromOffCentreAndFadesOut() {
        BannerAnimation banner = BannerAnimation.finalWave();
        List<BannerAnimation.Placed> start = banner.sample(0F);
        assertEquals(1, start.size());
        assertEquals(0F, start.get(0).alpha(), 0.001F, "it starts invisible");
        assertEquals(3.905F, start.get(0).scale(), 0.001F, "and much too big");
        // Far enough left to be off the word's own width, which is what "flies in" means.
        assertTrue(start.get(0).offsetX() < -1F, "and off to the left: " + start.get(0).offsetX());

        // The arrival is stretched to reach the anchored pose at 1.10s, which is where
        // `awooga.ogg` starts building towards its peak at 2.20s.
        BannerAnimation.Placed settled = banner.sample(1.10F).get(0);
        assertEquals(1F, settled.alpha(), 0.001F);
        assertEquals(1F, settled.scale(), 0.001F);
        assertEquals(0F, settled.offsetX(), 0.0001F, "the resting pose is the anchored one");

        assertTrue(banner.sample(2.6F).get(0).alpha() > 0.9F, "it holds through the peak of the sound");
        float last = banner.sample(banner.duration() - 0.01F).get(0).alpha();
        assertTrue(last < 0.2F, "and fades out on its last frame, not by being cut: " + last);
    }

    /**
     * The words are sized to the space the banner has, not to a fixed scale.
     *
     * <p>Height leads and width guards: the art's words were all one height with different
     * widths, and Chinese is compact enough that the height usually decides - but
     * 一大波僵尸！is six characters, and at the height the English lettering had it would be
     * wider than the window.
     */
    @Test
    void aWordFitsTheWidthTheBannerIsDrawnAt() {
        float drawn = 240F;
        float unit = 16F;

        float longWord = BannerAnimation.wordScale(drawn, unit, 6F * unit);
        float shortWord = BannerAnimation.wordScale(drawn, unit, 3F * unit);
        assertTrue(longWord * 6F * unit <= drawn * 0.92F + 0.01F, "a six-character shout has to fit");
        assertTrue(shortWord * 3F * unit <= drawn * 0.92F + 0.01F, "and so does a three-character one");
        assertEquals(2F * longWord, shortWord, 0.001F, "half the characters, twice the scale");

        // At three characters or more the width is what binds, at any size - which is why every
        // word of the banner comes out as wide as the banner and the longer one is shorter, the way
        // the original's two arts relate to each other (READY... is 102px of lettering, FINAL WAVE
        // 67px, in cells of about the same width).
        assertEquals(drawn * 0.92F / (3F * unit), shortWord, 0.001F);
        // A two-character word is short enough that the height takes over instead.
        assertEquals(drawn * 0.36F / unit, BannerAnimation.wordScale(drawn, unit, 2F * unit), 0.001F);
    }
}
