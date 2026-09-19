package com.pvzce.client.animation;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientEntity;
import com.pvzce.client.api.Animatable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wall-clock edge of the animation system: how fast a clip is played.
 *
 * <p>Two factors multiply into a playback's speed and both are invisible in the authored data -
 * the clip's own {@code rate} (how fast the original played this action) and, for a locomotion
 * clip, the ratio between how fast the target is actually travelling and the ground speed the
 * cycle was drawn for. Feet that do not match the ground are the classic symptom: an armoured
 * zombie walks at 0.18 cells/s with art drawn for 0.23, which is an 18% slide unless the
 * playback is slowed to match.
 *
 * <p>Measured against the wall clock because that is what the drawn position advances on, so
 * the speed assertions sleep for real time rather than for a game tick.
 */
class AnimationLocomotionTest {
    private static final String WALK_MODEL = """
            {
              "type": "controller",
              "model": {"bones": [
                {"name": "root", "parent": null, "pivot": [0, 0]},
                {"name": "body", "parent": "root", "pivot": [0, 0], "parts": [
                  {"texture": "test:body", "uv": [0, 0, 8, 8], "size": [1, 1],
                   "offset": [0, 0], "z": 0}
                ]}
              ]},
              "animations": {
                "walk": {"animation_length": 1.0, "loop": true, "reference_speed": REF,
                  "bones": {"body": {"translation": {"0.0": [0, 0], "1.0": [0, 0]}}}},
                "eat": {"animation_length": 3.25, "loop": true, "rate": 1.5,
                  "bones": {"body": {"translation": {"0.0": [0, 0], "3.25": [0, 1]}}}}
              }
            }
            """;

    /**
     * An entity whose drawn position the test drives by hand.
     *
     * <p>It implements {@link com.pvzce.client.api.MovingTarget} - the side of an animatable that
     * has a ground speed - which is what the measurement asks for. A target that only plays clips
     * (a mechanic's prop, a UI preview) is not one, and its locomotion clips are left at their
     * authored rate, which is the other half of the contract.
     */
    private static final class SlidingTarget implements com.pvzce.client.api.MovingTarget {
        private float x = 5F;
        private final float y = 2.5F;

        void moveTo(float cellX) {
            this.x = cellX;
        }

        @Override
        public float drawnX() {
            return x;
        }

        @Override
        public float drawnY() {
            return y;
        }

        @Override
        public AnimationHandle playAnimation(String animation) {
            return AnimationHandle.NONE;
        }

        @Override
        public void stopAnimation() {
        }

        @Override
        public String currentAnimation() {
            return "walk";
        }
    }

    private static ControllerFile fileWithReferenceSpeed(float referenceSpeed) {
        return (ControllerFile) AnimationResourceLoader.parse(
                com.google.gson.JsonParser.parseString(
                        WALK_MODEL.replace("REF", Float.toString(referenceSpeed))).getAsJsonObject(),
                Identifier.parse("test:locomotion"));
    }

    /**
     * A playback on its own, with a manager that has no level.
     *
     * <p>The manager is needed because changing a speed re-anchors the clip's time base, which
     * reads the shared clock; one with no level answers 0 for every call, which is all the
     * arithmetic needs. Passing null instead is what made this test fail with an NPE rather than
     * an assertion - worth knowing, but not worth papering over in the playback.
     */
    private static ControllerPlayback playbackFor(float referenceSpeed, Animatable target) {
        ControllerFile file = fileWithReferenceSpeed(referenceSpeed);
        return new ControllerPlayback(new AnimationManager(null, null, null), target, file,
                file.clips().get("walk"), "walk", "walk", null, 0D);
    }

    @Test
    void theGroundSpeedComesFromTheDrawnPosition() {
        SlidingTarget target = new SlidingTarget();
        ControllerPlayback playback = playbackFor(0.23F, target);

        // Settle, then move 0.02 cells. Two measurements straddle the move, which is how the
        // render loop samples it: once per frame, off the position the sprite is drawn at.
        playback.measureLocomotion(0D);
        sleep(80L);
        target.moveTo(target.drawnX() + 0.02F);
        sleep(80L);
        playback.measureLocomotion(0D);

        // 0.02 cells over 80 ms is 0.25 cells/s against a 0.23 reference speed. The band is wide
        // because a test JVM's clock is not precise to the millisecond; what it pins is that the
        // clip is scaled by the ratio of ground speed to the speed the art was drawn for.
        assertTrue(playback.measuredSpeed() > 0.1F,
                "the ground speed has to be measured off the drawn position, was "
                        + playback.measuredSpeed());
        float scale = playback.locomotionScale();
        assertTrue(scale > 0.5F && scale < 1.6F,
                "a target moving at about 0.25 against art drawn for 0.23 plays at about 1.1, was "
                        + scale);
    }

    @Test
    void aStationaryTargetPlaysALocomotionClipAtAuthoringSpeed() {
        SlidingTarget target = new SlidingTarget();
        ControllerPlayback playback = playbackFor(0.23F, target);

        playback.measureLocomotion(0D);
        sleep(40L);
        playback.measureLocomotion(0D);

        assertEquals(1F, playback.locomotionScale(), 0.0001F,
                "a target that is not moving plays at 1x, not at 0x: a walking zombie held up by"
                        + " the plant it is eating must not freeze mid-stride");
    }

    @Test
    void aClipWithoutAReferenceSpeedKeepsItsAuthoredRate() {
        SlidingTarget target = new SlidingTarget();
        AnimationPlayback playback = new ControllerPlayback(
                null, target, fileWithReferenceSpeed(0.23F), fileWithReferenceSpeed(0.23F).clips().get("eat"), "eat", "eat", null, 0D);
        playback.measureLocomotion(0D);
        sleep(40L);
        target.moveTo(9F);
        sleep(40L);

        playback.measureLocomotion(0D);
        assertEquals(1F, playback.locomotionScale(), 0.0001F,
                "a clip that declares no reference speed is not a locomotion clip and must never"
                        + " be touched by the measurement - eating and dying have no ground speed");
        assertEquals(1.5F, playback.effectiveSpeed(), 0.0001F,
                "and its authored rate survives untouched");
    }

    @Test
    void aTargetThatCannotMoveKeepsTheAuthoredRate() {
        // The other half of the contract: a mechanic's prop or a UI preview plays clips and has
        // no ground speed, so a locomotion clip on one of them is played as drawn.
        Animatable prop = new Animatable() {
            @Override
            public AnimationHandle playAnimation(String animation) {
                return AnimationHandle.NONE;
            }

            @Override
            public void stopAnimation() {
            }

            @Override
            public String currentAnimation() {
                return "walk";
            }
        };
        ControllerPlayback playback = playbackFor(0.23F, prop);
        playback.measureLocomotion(0D);
        sleep(40L);
        playback.measureLocomotion(0D);

        assertEquals(1F, playback.locomotionScale(), 0.0001F,
                "a target with no position measures no speed and must not be scaled");
    }

    @Test
    void theCallersSpeedMultipliesTheAuthoredRateInsteadOfReplacingIt() {
        // The whole reason rate and speed are two fields: folding them together meant the second
        // writer silently erased the first, so an `eat` clip asked to run at 1x stopped being
        // 1.5x. setSpeed re-anchors the clock, so it needs a manager; the clock that manager
        // reads is the level's, and one with no level answers 0 for every call, which is enough
        // to exercise the arithmetic.
        SlidingTarget target = new SlidingTarget();
        ControllerFile file = fileWithReferenceSpeed(0.23F);
        AnimationPlayback playback = new ControllerPlayback(
                new AnimationManager(null, null, null), target, file, file.clips().get("eat"),
                "eat", "eat", null, 0D);
        assertEquals(1.5F, playback.effectiveSpeed(), 0.0001F);

        playback.setSpeed(2F);
        assertEquals(3F, playback.effectiveSpeed(), 0.0001F,
                "2x of a 1.5x clip is 3x, not 2x");
    }

    @Test
    void theShippedWalkClipsDeclareTheSpeedTheZombieActuallyWalksAt() throws Exception {
        // The number has to agree with the content definitions, or the correction is applied in
        // the wrong direction and the feet slide exactly as much as before. Both sides are read
        // from the shipped data.
        com.pvzce.common.tag.TestContent.loadBuiltInContentAndTags();
        ControllerFile file = shippedZombieAnimation();
        float reference = file.clips().get("walk").referenceSpeed();
        var def = com.pvzce.common.core.BuiltInRegistries.ZOMBIES.get(
                Identifier.parse("pvzce:basic_zombie"));
        assertTrue(def != null, "the plain zombie has to be registered");
        assertEquals(def.moveSpeed(), reference, 0.01F,
                "the walk cycle's reference speed is the speed the plain zombie walks at, so that"
                        + " zombie plays at 1x and the slower armoured ones are corrected");
    }

    @Test
    void aDeathClipPlaysFasterThanItWasAuthored() throws Exception {
        com.pvzce.common.tag.TestContent.loadBuiltInContentAndTags();
        ControllerClip death = shippedZombieAnimation().clips().get("death");
        assertTrue(death.rate() > 1.5F,
                "the original plays its death sequences at 24-30 fps against the file's 12, so the"
                        + " rate has to be well above 1, was " + death.rate());
        // And the drawn death still fits inside the corpse that is left behind.
        float drawnSeconds = death.duration() / death.rate();
        assertTrue(drawnSeconds < 6F,
                "the death has to finish before the 6s corpse does, was " + drawnSeconds + "s");
    }

    private static ControllerFile shippedZombieAnimation() throws Exception {
        try (var in = AnimationLocomotionTest.class.getClassLoader()
                .getResourceAsStream("assets/pvzce/animations/zombie/basic/basic_zombie.json")) {
            assertTrue(in != null, "the shared zombie animation has to exist");
            return (ControllerFile) AnimationResourceLoader.parse(
                    com.google.gson.JsonParser.parseReader(
                                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
                            .getAsJsonObject(),
                    Identifier.parse("pvzce:zombie/basic/basic_zombie"));
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
