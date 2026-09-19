package com.pvzce.client.animation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvzce.api.util.Identifier;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The alpha channel, which the pipeline used to parse and then drop on the floor.
 *
 * <p>Every frame of a reanim carries an alpha, and the art uses it: the sun's two glow layers
 * are authored at 0.5-0.84, the boss's eyes and mouth ramp from 0, the pole vaulter's pole
 * fades in during its vault. Those tracks reached the runtime as opaque quads, so a soft halo
 * arrived as a flat disc and a fade-in arrived as a pop.
 */
class AnimationAlphaTest {
    private static final String SOURCE = "test:alpha";

    private static ControllerFile parseController(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return (ControllerFile) AnimationResourceLoader.parse(root, Identifier.parse(SOURCE));
    }

    @Test
    void samplesAlphaBetweenKeyframes() {
        ControllerFile file = parseController("""
                {
                  "type": "controller",
                  "model": {"bones": [
                    {"name": "root", "parent": null, "pivot": [0, 0]},
                    {"name": "glow", "parent": "root", "pivot": [0, 0], "parts": [
                      {"texture": "test:glow", "uv": [0, 0, 8, 8], "size": [1, 1],
                       "offset": [0, 0], "z": 0}
                    ]}
                  ]},
                  "animations": {
                    "idle": {
                      "animation_length": 1.0, "loop": false,
                      "bones": {"glow": {
                        "translation": {"0.0": [0, 0]},
                        "visible": {"0.0": true},
                        "alpha": {"0.0": 0.25, "0.5": 0.75}
                      }}
                    }
                  }
                }
                """);
        ControllerClip clip = file.clips().get("idle");
        assertEquals(0.25F, alphaAt(clip, file, 0D), 0.001F, "the first key's alpha");
        assertEquals(0.5F, alphaAt(clip, file, 0.25D), 0.001F, "halfway between the keys");
        assertEquals(0.75F, alphaAt(clip, file, 0.5D), 0.001F, "the last key's alpha");
        assertEquals(0.75F, alphaAt(clip, file, 0.9D), 0.001F, "held after the last key");
    }

    @Test
    void aBoneWithoutAnAlphaTrackIsOpaque() {
        ControllerFile file = parseController("""
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
                    "idle": {"animation_length": 1.0, "loop": true,
                      "bones": {"body": {"translation": {"0.0": [0, 0]}}}}
                  }
                }
                """);
        ControllerClip clip = file.clips().get("idle");
        assertEquals(1F, alphaAt(clip, file, 0.5D), 0.0001F,
                "a clip that never mentions alpha draws opaque, which is what keeps the channel"
                        + " out of 145 of the 164 shipped clips");
    }

    @Test
    void anAuthoredAlphaOutsideZeroToOneIsClamped() {
        // A colour multiplier above 1 brightens in the additive pass and a negative one
        // inverts the sprite, so a data typo has to be caught at the pose, not at the GPU.
        ControllerFile file = parseController("""
                {
                  "type": "controller",
                  "model": {"bones": [
                    {"name": "root", "parent": null, "pivot": [0, 0]},
                    {"name": "glow", "parent": "root", "pivot": [0, 0], "parts": [
                      {"texture": "test:glow", "uv": [0, 0, 8, 8], "size": [1, 1],
                       "offset": [0, 0], "z": 0}
                    ]}
                  ]},
                  "animations": {
                    "idle": {"animation_length": 1.0, "loop": false,
                      "bones": {"glow": {"alpha": {"0.0": -3.0, "1.0": 4.0}}}}
                  }
                }
                """);
        ControllerClip clip = file.clips().get("idle");
        assertEquals(0F, alphaAt(clip, file, 0D), 0.0001F);
        assertEquals(1F, alphaAt(clip, file, 1D), 0.0001F);
    }

    @Test
    void aPartCanAskToBeAddedRatherThanPainted() {
        ControllerFile file = parseController("""
                {
                  "type": "controller",
                  "model": {"bones": [
                    {"name": "root", "parent": null, "pivot": [0, 0]},
                    {"name": "core", "parent": "root", "pivot": [0, 0], "parts": [
                      {"texture": "test:core", "uv": [0, 0, 8, 8], "size": [1, 1],
                       "offset": [0, 0], "z": 0}
                    ]},
                    {"name": "halo", "parent": "root", "pivot": [0, 0], "parts": [
                      {"texture": "test:halo", "uv": [0, 0, 8, 8], "size": [1, 1],
                       "offset": [0, 0], "z": 1, "blend": "add"}
                    ]}
                  ]},
                  "animations": {"idle": {"animation_length": 1.0, "loop": true, "bones": {}}}
                }
                """);
        assertEquals(BlendMode.NORMAL, firstPart(file, "core").blend());
        assertEquals(BlendMode.ADD, firstPart(file, "halo").blend());
    }

    @Test
    void theAdditionPassRunsAfterThePaintPass() {
        // The two-pass split is what keeps a glowing part from forcing a blend-state change
        // per part, and it is also what decides that a halo is drawn *over* its core. Both
        // are properties of the plan, so they can be asserted without a GL context.
        ControllerFile file = parseController("""
                {
                  "type": "controller",
                  "model": {"bones": [
                    {"name": "root", "parent": null, "pivot": [0, 0]},
                    {"name": "halo", "parent": "root", "pivot": [0, 0], "parts": [
                      {"texture": "test:halo", "uv": [0, 0, 8, 8], "size": [1, 1],
                       "offset": [0, 0], "z": 0, "blend": "add"}
                    ]},
                    {"name": "core", "parent": "root", "pivot": [0, 0], "parts": [
                      {"texture": "test:core", "uv": [0, 0, 8, 8], "size": [1, 1],
                       "offset": [0, 0], "z": 1}
                    ]}
                  ]},
                  "animations": {"idle": {"animation_length": 1.0, "loop": true, "bones": {}}}
                }
                """);
        var plan = ControllerPlayback.planDrawOrder(file.model()).stream()
                .filter(entry -> !entry.parts().isEmpty())
                .toList();
        // The two bones that carry parts are ordered by their z, not by the order the file
        // happens to list them in.
        assertEquals(2, plan.size(), "both part-bearing bones are in the plan");
        assertEquals("halo", plan.get(0).bone().name(), "z 0 draws first");
        assertEquals("core", plan.get(1).bone().name(), "z 1 draws second");
        assertEquals(BlendMode.ADD, plan.get(0).parts().get(0).blend());
        assertEquals(BlendMode.NORMAL, plan.get(1).parts().get(0).blend());
    }

    private static ControllerModel.Part firstPart(ControllerFile file, String bone) {
        for (ControllerModel.Bone entry : file.model().bones().values()) {
            if (entry.name().equals(bone)) {
                assertFalse(entry.parts().isEmpty(), bone + " must have a part");
                return entry.parts().get(0);
            }
        }
        throw new AssertionError("no bone named " + bone);
    }

    private static float alphaAt(ControllerClip clip, ControllerFile file, double time) {
        var pose = clip.samplePose(file.model(), time).get("glow");
        if (pose == null) {
            pose = clip.samplePose(file.model(), time).get("body");
        }
        assertNotNull(pose, "the sampled pose has to contain the bone under test");
        return pose.alpha();
    }

    @Test
    void theShippedSunDrawsItsHaloThroughTheAdditionPass() throws Exception {
        // The case the whole channel exists for: a 36px core at full alpha under two glows
        // authored at 0.5 and 0.84. Opaque, they are flat discs that hide the core.
        ControllerFile file;
        try (var in = getClass().getClassLoader()
                .getResourceAsStream("assets/pvzce/animations/resource/sun.json")) {
            assertNotNull(in, "the sun needs an animation resource");
            file = (ControllerFile) AnimationResourceLoader.parse(
                    JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject(),
                    Identifier.parse("pvzce:resource/sun"));
        }
        ControllerClip clip = file.clips().get("idle");
        // The two glows pulse rather than sitting at one value: the outer one breathes
        // between 0.5 and 0.75 and the inner one between 0.51 and 1.0, twice per second, which
        // is the "sunlight" shimmer. Sampling the extremes is what shows the channel moving at
        // all - at t=0 the inner glow happens to be at the top of its range.
        float coreAtStart = clip.samplePose(file.model(), 0D).get("1").alpha();
        float innerMin = Float.MAX_VALUE;
        float outerMin = Float.MAX_VALUE;
        float outerMax = 0F;
        for (int step = 0; step <= 12; step++) {
            var pose = clip.samplePose(file.model(), step / 12D);
            innerMin = Math.min(innerMin, pose.get("2").alpha());
            outerMin = Math.min(outerMin, pose.get("3").alpha());
            outerMax = Math.max(outerMax, pose.get("3").alpha());
        }
        assertEquals(1F, coreAtStart, 0.01F, "the core is opaque, so the sprite keeps an edge");
        assertTrue(innerMin < 0.7F, "the inner glow dips to " + innerMin + ", it must be translucent");
        assertTrue(outerMin < 0.7F && outerMax < 0.9F,
                "the outer glow stays translucent, was " + outerMin + ".." + outerMax);
        assertEquals(BlendMode.ADD, firstPart(file, "2").blend());
        assertEquals(BlendMode.ADD, firstPart(file, "3").blend());
    }
}
