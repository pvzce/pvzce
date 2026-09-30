package com.pvzce.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.animation.Affine2;
import com.pvzce.client.animation.AnimationClip;
import com.pvzce.client.animation.AnimationResourceLoader;
import com.pvzce.client.animation.BonePose;
import com.pvzce.client.animation.ControllerFile;
import com.pvzce.client.animation.ControllerModel;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ice-boom shroom's own animation file: that its halves line up, and that they are visible.
 *
 * <p>This plant is the first in the project whose model is hand-written rather than converted, and
 * two of its three mistakes were in <em>this</em> file - both invisible to every other check:
 *
 * <ul>
 *   <li>{@code "visible": {"0": 1}} - a number where the loader reads a JSON boolean, so the track
 *       was dropped and both bones sampled {@code visible=false}. The plant was drawn every frame
 *       at the right size in the right cell and could not be seen; only its shadow was.</li>
 *   <li>the part {@code offset} and the clip's {@code translation} carried the same numbers, and
 *       they <b>add up</b>: the stalk ended up a whole canvas-height below the cap.</li>
 * </ul>
 *
 * <p>So the assertions are geometric and stated in the reader's terms: the two halves join at the
 * neck while standing, they stay joined through the idle bob, and every bone the model declares is
 * visible. A file that satisfies these can still be ugly; one that does not is broken in a way no
 * screenshot of the board will explain.
 */
class ModelPartsJoinUpTest {
    private static final String PATH = "/assets/pvzce/animations/plant/attacker/iceboom_shroom.json";
    private static final String BODY_TEXTURE = "textures/entities/plant/attacker/iceboom_shroom/body";
    private static final String HEAD_TEXTURE = "textures/entities/plant/attacker/iceboom_shroom/head";

    private static ControllerFile load() throws Exception {
        try (var in = ModelPartsJoinUpTest.class.getResourceAsStream(PATH)) {
            assertNotNull(in, "the plant's animation file has to be on the classpath: " + PATH);
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            return (ControllerFile) AnimationResourceLoader.parse(
                    root, Identifier.withDefaultNamespace("plant/attacker/iceboom_shroom"));
        }
    }

    /**
     * Where one layer's drawn pixels sit in model space, as {@code [bottom, top]}.
     *
     * <p>Read from the texture rather than from the part's quad. The quad is the whole canvas -
     * including the transparent margin the two layers' crops leave - so it says nothing about
     * whether the drawings meet; the ink does.
     */
    /** One layer's drawn pixels, in model space, as {@code [bottom, top]}. */
    private record Ink(float bottom, float top) {
    }

    /**
     * Where a layer's drawn pixels sit in model space.
     *
     * <p>Read from the texture's own alpha rather than from the part's quad: the quad is the whole
     * canvas, including the transparent margin the crop leaves, so it says nothing about whether
     * the two drawings meet. The ink does - and "do the cap and the stalk touch" is precisely a
     * question about the ink.
     */
    private static Ink ink(ControllerModel model, Map<String, BonePose> poses, String bone,
                           String texture) throws Exception {
        ControllerModel.Bone b = model.bones().get(bone);
        assertNotNull(b, "the model has to declare a '" + bone + "' bone");
        ControllerModel.Part part = b.parts().get(0);
        assertEquals(texture, part.texture().path(),
                "the fixture names the layer it expects '" + bone + "' to draw");
        Identifier id = part.texture();
        String path = "/assets/" + id.namespace() + "/" + id.path() + ".png";
        java.awt.image.BufferedImage image;
        try (var in = ModelPartsJoinUpTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "the layer's texture has to be on the classpath: " + path);
            image = javax.imageio.ImageIO.read(in);
        }
        assertNotNull(image, "and it has to be a readable PNG: " + path);
        int first = -1;
        int last = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            boolean opaque = false;
            for (int x = 0; x < image.getWidth() && !opaque; x++) {
                opaque = (image.getRGB(x, y) >>> 24) > 40;
            }
            if (opaque) {
                if (first < 0) {
                    first = y;
                }
                last = y;
            }
        }
        assertTrue(first >= 0, "the layer has to have drawn pixels: " + path);
        // One sprite pixel is 1/100 cell, the canvas bottom is the model's origin, and the bone's
        // clip translation moves the drawing by that many cells.
        float px = part.sizeY() / image.getHeight();
        float canvasBottom = part.offsetY() - part.sizeY() / 2F + poses.getOrDefault(bone, b.restPose()).translation()[1];
        return new Ink(canvasBottom + (image.getHeight() - 1 - last) * px,
                canvasBottom + (image.getHeight() - first) * px);
    }

    /**
     * The stalk's feet stand on the model's origin and the plant is plant-sized.
     *
     * <p>{@code y = 0} is the ground line the renderer anchors a plant to, so art that starts
     * half a cell up floats and art that starts below zero sinks into the lawn. The upper bound
     * is the other half of the same fact: the drawing is 1.4 canvas-heights tall (the cap's
     * crystal crown overhangs the box the model declares, exactly as the Snow Pea's crystals
     * do), and a plant that stood two cells tall would be drawn across the row above it.
     */
    @Test
    void theStalkStandsOnTheGroundLine() throws Exception {
        ControllerFile file = load();
        Map<String, BonePose> poses = file.clips().get("idle").samplePose(file.model(), 0.0);
        Ink body = ink(file.model(), poses, "body", BODY_TEXTURE);
        Ink head = ink(file.model(), poses, "head", HEAD_TEXTURE);
        assertEquals(0.0F, body.bottom(), 0.03F, "the stalk's feet belong on the ground line");
        assertTrue(head.top() > 0.7F && head.top() < 1.3F,
                "the plant has to be about a cell tall, cap and all, was " + head.top());
        assertEquals(body.top(), head.bottom(), 0.03F,
                "the cap's crop line and the stalk's crown are the same line; body=" + body
                        + " head=" + head);
    }

    /**
     * The cap sits on the stalk, and the two travel together through the idle bob.
     *
     * <p>Two independent numbers place the head - its part {@code offset} and the clip's
     * {@code translation} - and they <b>add</b>. The first version carried the same value in both,
     * which raised the cap by a whole canvas; the assertion is therefore on the number a player
     * sees, the gap between the two drawings, and not on either field.
     *
     * <p>The variation across the clip is bounded by the bob's own amplitude: a cap that breathes
     * is the point, a cap that leaves is the bug.
     */
    @Test
    void theCapSitsOnTheStalkThroughIdle() throws Exception {
        ControllerFile file = load();
        com.pvzce.client.animation.ControllerClip idle = file.clips().get("idle");
        assertNotNull(idle, "idle");
        float atRest = 0F;
        float worst = 0F;
        for (int step = 0; step <= 12; step++) {
            double t = idle.duration() * step / 12.0;
            Map<String, BonePose> poses = idle.samplePose(file.model(), t);
            float gap = ink(file.model(), poses, "head", HEAD_TEXTURE).bottom()
                    - ink(file.model(), poses, "body", BODY_TEXTURE).top();
            if (step == 0) {
                atRest = gap;
                assertEquals(0.0F, gap, 0.03F,
                        "standing still, the cap's cut line has to meet the stalk's crown");
            }
            worst = Math.max(worst, Math.abs(gap));
        }
        assertTrue(worst < 0.06F,
                "and it has to stay on the stalk while it breathes; the worst gap was " + worst
                        + " cells (at rest " + atRest + ")");
    }

    /** Every bone is drawn in the clip the plant stands in; a hidden bone is an invisible plant. */
    @Test
    void everyBoneIsVisibleInIdle() throws Exception {
        ControllerFile file = load();
        Map<String, BonePose> poses = file.clips().get("idle").samplePose(file.model(), 0.0);
        assertTrue(poses.size() >= 2, "the model is the cap and the stalk: " + poses.keySet());
        for (var entry : poses.entrySet()) {
            assertTrue(entry.getValue().visible(),
                    "bone '" + entry.getKey() + "' is hidden in idle, so the plant draws nothing at"
                            + " all - check the clip's \"visible\" literals are JSON booleans, not 0/1");
        }
    }

    /**
     * Every channel of every bone is the length it has to be, in every clip.
     *
     * <p>This is the crash guard, and it is here because the crash happened: a hand-written
     * keyframe of {@code [0.0]} where a rotation is {@code [x, y, z]} reached
     * {@code BonePose.toAffine} and threw {@code ArrayIndexOutOfBoundsException} out of the
     * render loop - the game died mid-level. A model can be parsed and measured perfectly and
     * still be unrenderable, so the shapes are asserted and not just the geometry.
     */
    @Test
    void everyChannelIsTheLengthItsChannelHas() throws Exception {
        ControllerFile file = load();
        for (var clipEntry : file.clips().entrySet()) {
            var clip = (com.pvzce.client.animation.ControllerClip) clipEntry.getValue();
            for (var poseEntry : clip.samplePose(file.model(), 0.0).entrySet()) {
                BonePose pose = poseEntry.getValue();
                String where = clipEntry.getKey() + "/" + poseEntry.getKey();
                assertEquals(2, pose.translation().length, where + " translation is (x, y)");
                assertEquals(3, pose.rotation().length, where + " rotation is (x, y, z)");
                assertEquals(2, pose.scale().length, where + " scale is (x, y)");
            }
        }
    }

    /** Both clips the shooter asks for exist, and `shoot` hands back to `idle`. */
    @Test
    void theTwoClipsTheSimulationAsksForExist() throws Exception {
        ControllerFile file = load();
        assertNotNull(file.clips().get("idle"), "idle");
        var shoot = file.clips().get("shoot");
        assertNotNull(shoot, "a shooter publishes `shoot` every volley; that clip has to exist");
        assertTrue(AnimationClip.OnEnd.IDLE == shoot.onEnd(),
                "and it has to hand back to idle, or the plant freezes in its firing pose: "
                        + shoot.onEnd());
    }
}
