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
        // Three words of six frames each at 12 fps.
        assertEquals(1.833F, banner.duration(), 0.01F);

        List<BannerAnimation.Placed> first = banner.sample(0.1F);
        assertEquals(1, first.size(), "exactly one word is on screen at a time");
        assertEquals("ready", first.get(0).texture().path().substring("textures/gui/hud/announce/".length()));
        assertEquals(1F, first.get(0).alpha(), 0.0001F, "the words are opaque");

        List<BannerAnimation.Placed> second = banner.sample(0.6F);
        assertEquals(1, second.size());
        assertTrue(second.get(0).texture().path().endsWith("set"));
        List<BannerAnimation.Placed> third = banner.sample(1.2F);
        assertEquals(1, third.size());
        assertTrue(third.get(0).texture().path().endsWith("plant"));
        assertEquals(1.3F, third.get(0).scale(), 0.0001F, "\"Plant!\" lands at its authored size");

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

        BannerAnimation.Placed settled = banner.sample(0.75F).get(0);
        assertEquals(1F, settled.alpha(), 0.001F);
        assertEquals(1F, settled.scale(), 0.001F);
        assertEquals(0F, settled.offsetX(), 0.0001F, "the resting pose is the anchored one");

        assertTrue(banner.sample(1.5F).get(0).alpha() > 0.9F, "it stays up for about a second");
        float last = banner.sample(banner.duration() - 0.01F).get(0).alpha();
        assertTrue(last < 0.2F, "and fades out on its last frame, not by being cut: " + last);
    }

}
