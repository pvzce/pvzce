package com.pvzce.common;

import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the built-in effects point at.
 *
 * <p>Two of these were wrong in ways only a player notices, which is exactly why they
 * are pinned: a hit used the zombie's <em>head</em> sprite, and the tint decoded from the
 * original's colour tags came out near-black.
 */
class PvzceParticlesTest {
    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    /** A hit is a spark; the head is the death effect. */
    @Test
    void hitsAndDeathsUseDifferentEffects() {
        assertNotEquals(PvzceParticles.HIT_SPARK, PvzceParticles.ZOMBIE_HEAD,
                "a hit must not be the zombie's head coming off");
        assertFalse(PvzceParticles.HIT_SPARK.path().contains("head"),
                "the hit effect is a spark, not a body part: " + PvzceParticles.HIT_SPARK);
        assertTrue(PvzceParticles.ZOMBIE_HEAD.path().contains("head"),
                "and the head effect really is the head: " + PvzceParticles.ZOMBIE_HEAD);
    }

    /** The tint the original wrote as a 0..255 byte must not decode to near-black. */
    @Test
    void theImpactBurstIsNotBlack() {
        var def = BuiltInRegistries.PARTICLES.get(PvzceParticles.MELON_IMPACT);
        assertTrue(def != null, "the impact burst must be defined");
        float[] colour = def.look().colorArray();
        assertTrue(colour[0] > 0.5F,
                "a burst that reads as a black smudge is not a hit: "
                        + java.util.Arrays.toString(colour));
    }

    /** Every default effect has to resolve, or the game silently draws nothing. */
    @Test
    void everyConstantHasADefinition() {
        List<String> missing = new ArrayList<>();
        for (var id : PvzceParticles.all()) {
            if (BuiltInRegistries.PARTICLES.get(id) == null) {
                missing.add(id.toString());
            }
        }
        assertTrue(missing.isEmpty(), "no definition for: " + missing);
    }
}
