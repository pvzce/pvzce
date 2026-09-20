package com.pvzce.client.animation;

import com.pvzce.api.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AnimationVariantsTest {
    @Test
    void artWithOneDeathAlwaysPlaysThatDeath() {
        AnimationFile file = fileWith("idle", "death");

        assertEquals("death", AnimationVariants.resolve(file, "death", 0L));
        assertEquals("death", AnimationVariants.resolve(file, "death", 1L));
        assertEquals("death", AnimationVariants.resolve(file, "death", 7L));
    }

    @Test
    void artWithTwoDeathsSplitsThemBySeed() {
        AnimationFile file = fileWith("idle", "death", "death2");

        assertEquals("death", AnimationVariants.resolve(file, "death", 0L));
        assertEquals("death2", AnimationVariants.resolve(file, "death", 1L));
        assertEquals("death", AnimationVariants.resolve(file, "death", 2L));
        assertEquals("death2", AnimationVariants.resolve(file, "death", 3L));
        // A negative seed still lands inside the pool rather than off the end of it.
        assertEquals(true, List.of("death", "death2").contains(
                AnimationVariants.resolve(file, "death", -1L)));
    }

    /** The bug this class exists for: art with no {@code death2} must never be handed one. */
    @Test
    void artWithoutASecondDeathNeverResolvesToDeath2() {
        AnimationFile file = fileWith("idle", "death", "death_superlong");

        for (long seed = 0L; seed < 8L; seed++) {
            assertEquals("death", AnimationVariants.resolve(file, "death", seed),
                    "seed " + seed + " picked a sequence the art does not have");
        }
    }

    @Test
    void drowningFallsBackToTheOrdinaryDeathItHas() {
        AnimationFile file = fileWith("idle", "death", "death2");

        assertEquals("death", AnimationVariants.resolve(file, "death_water", 0L),
                "a drowned body with no water sequence sinks with an ordinary one");
        assertEquals("death2", AnimationVariants.resolve(file, "death_water", 1L));
    }

    @Test
    void drowningPlaysItsOwnSequenceWhereTheArtHasOne() {
        AnimationFile file = fileWith("idle", "death", "death2", "death_water");

        assertEquals("death_water", AnimationVariants.resolve(file, "death_water", 0L));
        assertEquals("death_water", AnimationVariants.resolve(file, "death_water", 1L));
    }

    @Test
    void theLongDeathIsNotPartOfTheRandomPool() {
        AnimationFile file = fileWith("idle", "death", "death2", "death_superlong");

        for (long seed = 0L; seed < 8L; seed++) {
            String picked = AnimationVariants.resolve(file, "death", seed);
            assertEquals(true, List.of("death", "death2").contains(picked),
                    "the super-long sequence is a definition's explicit choice, not a roll");
        }
    }

    /** A state the file has no clip for at all is the caller's signal to fall back to idle. */
    @Test
    void anUnknownStateResolvesToNothing() {
        AnimationFile file = fileWith("idle", "death");

        assertNull(AnimationVariants.resolve(file, "angry", 0L));
    }

    @Test
    void aStateIsItsOwnClipWhenTheArtHasIt() {
        AnimationFile file = fileWith("idle", "walk", "eat");

        assertEquals("walk", AnimationVariants.resolve(file, "walk", 0L));
        assertEquals("eat", AnimationVariants.resolve(file, "eat", 3L));
    }

    /** A file with no death clip at all - the charred model - has nothing to fall into. */
    @Test
    void artWithNoDeathClipResolvesToNothing() {
        AnimationFile file = fileWith("idle", "death_burned");

        assertNull(AnimationVariants.resolve(file, "death", 0L));
        assertNull(AnimationVariants.resolve(file, "death_water", 0L));
        assertEquals("death_burned", AnimationVariants.resolve(file, "death_burned", 0L));
    }

    private static AnimationFile fileWith(String... clipNames) {
        Map<String, FlipbookClip> clips = new LinkedHashMap<>();
        for (String name : clipNames) {
            clips.put(name, new FlipbookClip(
                    List.of(Identifier.withDefaultNamespace("textures/test")),
                    new float[]{1F}, true, AnimationClip.OnEnd.HOLD, "", 0F, 1F, 0F,
                    List.of(), List.of()));
        }
        return new FlipbookFile(clips, 1F, 1F, 0F, 0F);
    }
}
