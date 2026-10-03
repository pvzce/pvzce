package com.pvzce.client.animation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvzce.api.util.Identifier;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cactus's attack clips draw <em>one</em> body overlay, not two.
 *
 * <p>{@code Cactus.reanim} animates one track that cycles two images
 * ({@code IMAGE_REANIM_CACTUS_BODY_OVERLAY} and {@code ..._OVERLAY2}), which the exporter turns
 * into two bones. Every mask therefore permanently hides one of them - and the exporter's
 * {@code force_visible_hidden} rescue, whose job is to bring back a bone a clip never shows, was
 * bringing back both: at the <em>same</em> transform, since both bones sit where the source track
 * put them. Two 48x13 images that agree on 536 of their 538 pixels, drawn on top of each other, is
 * the overlap the player reported ("the cactus's attack animation overlaps"), and it was only ever
 * the two attack clips - {@code idle} and {@code idle_high} each show exactly one.
 *
 * <p>Read through the loader the game itself uses, so what is asserted is the clip the renderer
 * plays and not the JSON's shape. The file lives with the rest of the original's art in
 * {@code local-assets/}, which is on the test classpath at build time; the test is skipped when it
 * is not there, because a checkout without the original assets is a supported state.
 */
class CactusClipTest {
    private static final String PATH = "assets/pvzce/animations/plant/attacker/cactus.json";
    /** How finely each clip is sampled, in seconds; finer than a frame at 12fps. */
    private static final double STEP = 0.02D;

    @Test
    void theAttackClipsDrawOneBodyOverlayAtATime() throws Exception {
        ControllerFile file = cactus();
        for (String clipName : List.of("idle", "idle_high", "shoot", "shoot_high", "rise", "lower")) {
            ControllerClip clip = file.clips().get(clipName);
            assertNotNull(clip, "the cactus has a " + clipName + " clip");
            for (double time = 0D; time <= clip.duration() + STEP; time += STEP) {
                Map<String, BonePose> poses = clip.samplePose(file.model(), time);
                boolean overlay = visible(poses, "body_overlay");
                boolean overlay2 = visible(poses, "body_overlay_2");
                assertFalse(overlay && overlay2,
                        clipName + " draws both body overlays at t=" + time
                                + ", which is two copies of the same pixels on top of each other");
            }
        }
    }

    /** The two attack clips are the ones that were wrong; each must still draw one of them. */
    @Test
    void theAttackClipsStillDrawTheirOverlay() throws Exception {
        ControllerFile file = cactus();
        for (String clipName : List.of("shoot", "shoot_high")) {
            ControllerClip clip = file.clips().get(clipName);
            boolean any = false;
            for (double time = 0D; time <= clip.duration() + STEP; time += STEP) {
                Map<String, BonePose> poses = clip.samplePose(file.model(), time);
                any |= visible(poses, "body_overlay") || visible(poses, "body_overlay_2");
            }
            assertTrue(any, clipName + " lost its body overlay altogether, which is the opposite"
                    + " mistake: the rescue is what draws it, so excluding both would leave the"
                    + " cactus without the highlight the original gives it");
        }
    }

    private static boolean visible(Map<String, BonePose> poses, String bone) {
        BonePose pose = poses.get(bone);
        return pose != null && pose.visible();
    }

    private static ControllerFile cactus() throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (InputStream in = loader.getResourceAsStream(PATH)) {
            org.junit.jupiter.api.Assumptions.assumeTrue(in != null,
                    "the original animation set is not on the classpath (local-assets/ is missing)");
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject();
            AnimationFile parsed = AnimationResourceLoader.parse(
                    root, Identifier.withDefaultNamespace("animations/plant/attacker/cactus"));
            assertTrue(parsed instanceof ControllerFile,
                    "the cactus is a controller animation, was " + parsed.getClass().getSimpleName());
            return (ControllerFile) parsed;
        }
    }
}
