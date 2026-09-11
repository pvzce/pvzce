package com.pvzce.client.animation;

import com.google.gson.JsonParser;
import com.pvzce.api.util.Identifier;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationResourceLoaderTest {
    @Test
    void parsesFlipbookWithExplicitType() {
        var json = JsonParser.parseString("""
                {
                  "type": "flipbook",
                  "size": [0.7, 0.95],
                  "anchor": [0.5, 0.0],
                  "animations": {
                    "idle": {
                      "frames": ["pvzce:textures/entities/basic_zombie"],
                      "delay": 0.25,
                      "loop": true
                    },
                    "walk": {
                      "frames": [
                        "pvzce:textures/entities/basic_zombie",
                        "pvzce:textures/entities/basic_zombie_2"
                      ],
                      "delays": [0.25, 0.25],
                      "loop": true
                    }
                  }
                }
                """).getAsJsonObject();

        AnimationFile file = AnimationResourceLoader.parse(json, Identifier.withDefaultNamespace("test"));

        assertInstanceOf(FlipbookFile.class, file);
        assertEquals(AnimationFile.AnimationType.FLIPBOOK, file.type());
        FlipbookClip walk = (FlipbookClip) file.clip("walk").orElseThrow();
        assertEquals(0.5F, walk.duration(), 0.0001F);
        assertEquals(0, walk.frameIndex(0.0D));
        assertEquals(1, walk.frameIndex(0.25D));
        assertEquals(0, walk.frameIndex(0.5D));
    }

    @Test
    void parsesControllerAndDerivesLengthFromKeyframes() {
        var json = JsonParser.parseString("""
                {
                  "type": "controller",
                  "model": {
                    "bones": [
                      {"name": "root", "parent": null, "pivot": [0.0, 0.0]},
                      {
                        "name": "leaf",
                        "parent": "root",
                        "pivot": [0.0, 0.0],
                        "parts": [{
                          "texture": "pvzce:textures/entities/test/leaf",
                          "uv": [0, 0, 8, 8],
                          "size": [0.08, 0.08]
                        }]
                      }
                    ]
                  },
                  "animations": {
                    "idle": {
                      "loop": true,
                      "bones": {
                        "leaf": {
                          "translation": {
                            "0.0": [0.0, 0.0],
                            "1.0": {"vector": [0.5, 0.0], "easing": "linear"}
                          },
                          "visible": {"0.0": true}
                        }
                      }
                    }
                  }
                }
                """).getAsJsonObject();

        AnimationFile file = AnimationResourceLoader.parse(json, Identifier.withDefaultNamespace("test"));

        assertInstanceOf(ControllerFile.class, file);
        ControllerFile controller = (ControllerFile) file;
        ControllerClip clip = (ControllerClip) controller.clip("idle").orElseThrow();
        assertEquals(1.0F, clip.duration(), 0.0001F);
        assertTrue(clip.loop());

        var pose = clip.samplePose(controller.model(), 0.5D).get("leaf");
        assertEquals(0.25F, pose.translation()[0], 0.0001F);
        assertEquals(0.0F, pose.translation()[1], 0.0001F);
    }

    @Test
    void zombieModelSizesDifferByEntity() throws Exception {
        ControllerFile basic = (ControllerFile) parseClasspath("basic_zombie");
        ControllerFile gargantuar = (ControllerFile) parseClasspath("gargantuar");
        ControllerFile boss = (ControllerFile) parseClasspath("zombie_boss");
        ControllerFile imp = (ControllerFile) parseClasspath("imp");

        assertEquals(0.95F, basic.model().sizeY(), 0.01F);
        assertTrue(gargantuar.model().sizeY() > basic.model().sizeY() * 1.30F);
        assertTrue(boss.model().sizeY() > basic.model().sizeY() * 1.80F);
        assertTrue(imp.model().sizeY() < basic.model().sizeY() * 0.80F);
    }

    @Test
    void parsesGeneratedPeaShooterControllerResource() throws Exception {
        AnimationFile file = parseClasspath("pea_shooter");

        assertInstanceOf(ControllerFile.class, file);
        ControllerFile controller = (ControllerFile) file;
        assertEquals(20, controller.model().bones().size());
        assertEquals(2.083333F, controller.clip("idle").orElseThrow().duration(), 0.001F);
        assertEquals(2.083333F, controller.clip("shoot").orElseThrow().duration(), 0.001F);
        assertEquals(AnimationClip.OnEnd.IDLE, controller.clip("shoot").orElseThrow().onEnd());

        var shoot = (ControllerClip) controller.clip("shoot").orElseThrow();
        assertTrue(shoot.samplePose(controller.model(), 0D).get("stalk_bottom").visible());
        assertTrue(shoot.samplePose(controller.model(), 0D).get("backleaf").visible());
        assertFalse(shoot.samplePose(controller.model(), 0D).get("blink_1").visible());
    }

    @Test
    void parsesAllGeneratedEntityControllersAndTextures() throws Exception {
        String[] entities = {
                "sun", "sunflower", "cherry_bomb", "wall_nut", "potato_mine", "chomper",
                "kernel_pult", "marigold", "lily_pad", "flower_pot", "coffee_bean",
                "basic_zombie", "buckethead_zombie", "door_zombie", "newspaper_zombie",
                "pole_vaulter_zombie", "balloon_zombie", "miner_zombie", "gargantuar",
                "zombie_boss", "imp"
        };
        for (String entity : entities) {
            AnimationFile file = parseClasspath(entity);
            assertInstanceOf(ControllerFile.class, file, entity);
            ControllerFile controller = (ControllerFile) file;
            assertTrue(controller.clip("idle").isPresent(), entity + " missing idle clip");
            for (ControllerModel.Bone bone : controller.model().bones().values()) {
                for (ControllerModel.Part part : bone.parts()) {
                    String path = "/assets/" + part.texture().toPath() + ".png";
                    assertNotNull(AnimationResourceLoaderTest.class.getResourceAsStream(path),
                            entity + " missing texture " + path);
                }
            }
        }
    }

    private static AnimationFile parseClasspath(String path) throws Exception {
        try (var stream = AnimationResourceLoaderTest.class.getResourceAsStream("/assets/pvzce/animations/" + path + ".json")) {
            if (stream == null) {
                throw new IllegalStateException("Missing test resource " + path);
            }
            var reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
            return AnimationResourceLoader.parse(JsonParser.parseReader(reader).getAsJsonObject(),
                    Identifier.withDefaultNamespace(path));
        }
    }

    @Test
    void infersControllerTypeFromModelAndBones() {
        var json = JsonParser.parseString("""
                {
                  "model": {"bones": [{"name": "root"}]},
                  "animations": {"idle": {"bones": {}}}
                }
                """).getAsJsonObject();

        AnimationFile file = AnimationResourceLoader.parse(json, Identifier.withDefaultNamespace("inferred"));

        assertEquals(AnimationFile.AnimationType.CONTROLLER, file.type());
        assertFalse(file.clip("missing").isPresent());
    }
}
