package com.pvzce.client.animation;

import com.google.gson.JsonParser;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.tag.TestContent;
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

    /**
     * A clip can hand over to another clip by naming it, and one that names nothing is reported.
     *
     * <p>{@code "on_end": "hide_loop"} is how the art generator writes a hand-over (the
     * scaredy-shroom ducks into its held pose, the grave buster lands and chews), and the loader
     * used to read every name it did not recognise as {@code hold} - so both of those played once
     * and then stood frozen on their last frame. The shipped files are the real assertion here:
     * this parses them off the classpath rather than a hand-written sample.
     */
    @Test
    void aClipNamedByOnEndIsTheClipThatPlaysNext() throws Exception {
        ControllerFile scaredy = (ControllerFile) parseClasspath("scaredy_shroom");
        ControllerClip hide = (ControllerClip) scaredy.clip("hide").orElseThrow();
        assertEquals(AnimationClip.OnEnd.NEXT, hide.onEnd(), "the duck hands over to its loop");
        assertEquals("hide_loop", hide.next());
        assertFalse(hide.loop(), "and the hand-over itself is a one-shot");

        ControllerFile graveBuster = (ControllerFile) parseClasspath("grave_buster");
        ControllerClip landing = (ControllerClip) graveBuster.clip("idle").orElseThrow();
        assertEquals(AnimationClip.OnEnd.NEXT, landing.onEnd(), "the landing hands over to the chew");
        assertEquals("chew", landing.next());

        // A name no clip answers to stays a hold - that is the safe reading of a broken file -
        // and the loader says so out loud instead of swallowing it.
        var sample = com.google.gson.JsonParser.parseString("""
                {
                  "type": "controller",
                  "model": { "bones": [ { "name": "root" } ] },
                  "animations": {
                    "idle": { "loop": true, "bones": {} },
                    "broken": { "loop": false, "on_end": "typo_clip", "bones": {} }
                  }
                }
                """).getAsJsonObject();
        ControllerFile parsed = (ControllerFile) AnimationResourceLoader.parse(sample,
                Identifier.withDefaultNamespace("on_end_sample"));
        assertEquals(AnimationClip.OnEnd.HOLD, parsed.clip("broken").orElseThrow().onEnd());
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
        assertEquals(16, controller.model().bones().size());
        // The idle loop is 24 frames of travel, not 25: its last frame is the frame *before*
        // the jump back to the first, so counting the whole 25 made every loop hold its final
        // pose for an extra frame (see the converter's duration_frames).
        assertEquals(2.0F, controller.clip("idle").orElseThrow().duration(), 0.001F);
        // A one-shot keeps its full count, because its last frame is meant to be seen.
        assertEquals(2.083333F, controller.clip("shoot").orElseThrow().duration(), 0.001F);
        assertEquals(AnimationClip.OnEnd.IDLE, controller.clip("shoot").orElseThrow().onEnd());

        var shoot = (ControllerClip) controller.clip("shoot").orElseThrow();
        assertTrue(shoot.samplePose(controller.model(), 0D).get("peashooter_stalk_bottom").visible());
        assertTrue(shoot.samplePose(controller.model(), 0D).get("peashooter_backleaf").visible());
        assertFalse(shoot.samplePose(controller.model(), 0D).get("peashooter_blink_1").visible());
    }

    /**
     * Every generated loop is still moving when it ends.
     *
     * <p>That is the invariant a loop has to satisfy and the one that is invisible in the
     * authored data: a clip whose duration overshoots its last key frame holds that pose before
     * wrapping, so a walk stalls on the spot once per cycle and a death lies frozen for a
     * quarter of a second. The frame values are right either way - only the length is wrong -
     * so this samples the last slice of the clip and requires that it moved, and that the move
     * is comparable to the clip's own typical slice rather than a jump.
     */
    @Test
    void everyGeneratedLoopIsStillMovingAtItsEnd() throws Exception {
        String[] entities = {
                "pea_shooter", "sunflower", "wall_nut", "potato_mine", "chomper",
                "basic_zombie", "conehead_zombie", "flag_zombie", "balloon_zombie",
                "newspaper_zombie", "sun", "marigold"
        };
        int checked = 0;
        for (String entity : entities) {
            ControllerFile controller = (ControllerFile) parseClasspath(entity);
            for (var entry : controller.clips().entrySet()) {
                ControllerClip clip = entry.getValue();
                if (!clip.loop() || clip.duration() <= 0.05F) {
                    continue;
                }
                // Sampled around the middle of the clip's last frame. Measured at the very end
                // of the clip the value is always the last key's - that is what interpolating a
                // discrete authored frame means - so the question "did the length swallow a
                // frame" is asked where that frame's own travel is visible.
                float frame = clip.duration() / 10F;
                var before = clip.samplePose(controller.model(), clip.duration() - frame);
                var after = clip.samplePose(controller.model(), clip.duration() - frame * 0.5F);
                float moved = 0F;
                for (String bone : before.keySet()) {
                    float[] a = before.get(bone).translation();
                    float[] b = after.get(bone).translation();
                    moved = Math.max(moved, (float) Math.hypot(b[0] - a[0], b[1] - a[1]));
                }
                assertTrue(moved > 1.0E-4F, entity + "/" + entry.getKey()
                        + " is frozen over the back half of its last frame: its duration runs "
                        + "past its last key frame");
                assertTrue(moved < 0.25F, entity + "/" + entry.getKey()
                        + " moves " + moved + " cells in half of its last frame, which is a "
                        + "jump rather than a step");
                checked++;
            }
        }
        assertTrue(checked > 8, "the sample should cover several clips, was " + checked);
    }

    /**
     * Every zombie that wears the same body is drawn at the same size.
     *
     * <p>This is not a trivial claim, and it is not a claim about the art: the armoured and
     * flag zombies *are* the plain one plus a hat or a pole, so their bodies have to measure
     * identically or a lane of mixed zombies is a row of visibly different creatures. It broke
     * exactly that way - twice, for two different reasons - and both times the cause was a
     * measurement that saw more than the body: first a hat deciding how tall the body was
     * (the shared model was measured with the hat excluded while the fit used a rectangle that
     * included it, so every armoured zombie came out 15% smaller), then a flagpole doing the
     * same to the flag zombie (its body landed at 65%).
     *
     * <p>Asserted on a body part's size rather than on `model.size`, because `model.size` is
     * legitimately bigger for a zombie holding something: the pole sticks out of the box on
     * purpose. What must not move is the body.
     */
    @Test
    void everyZombieWearingTheSameBodyIsDrawnAtTheSameSize() throws Exception {
        float[] plain = bodyPartSize((ControllerFile) parseClasspath("basic_zombie"));
        assertTrue(plain[0] > 0F && plain[1] > 0F, "the plain zombie needs a measurable body");
        for (String zombie : new String[]{"conehead_zombie", "buckethead_zombie", "door_zombie",
                "flag_zombie"}) {
            float[] body = bodyPartSize((ControllerFile) parseClasspath(zombie));
            assertEquals(plain[0], body[0], 0.001F, zombie + " body width");
            assertEquals(plain[1], body[1], 0.001F,
                    zombie + " body height: it wears the plain zombie's body, so it has to be"
                            + " drawn at the plain zombie's size");
        }
    }

    /** The size of the model's `body` part, which every shared-body zombie has. */
    private static float[] bodyPartSize(ControllerFile file) {
        for (ControllerModel.Bone bone : file.model().bones().values()) {
            if (bone.name().equals("body") && !bone.parts().isEmpty()) {
                ControllerModel.Part part = bone.parts().get(0);
                return new float[]{part.sizeX(), part.sizeY()};
            }
        }
        throw new AssertionError("this zombie has no `body` bone to measure");
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

    /**
     * Every animation state the ash line asks for has to exist.
     *
     * <p>{@code ExplosiveCapability} drives the fuse from the server and the client looks
     * the state up by name; a name the file does not define does not fail, it silently
     * falls back to {@code idle} - which is how a cherry bomb spent its whole fuse asking
     * for a {@code grow} clip that only the two mines have, and how nobody noticed. The
     * two states are pinned here: a mine grows out of the ground, and everything that
     * explodes has an {@code explode} clip.
     */
    @Test
    void everyAshPlantDefinesTheClipsItsCapabilityAsksFor() throws Exception {
        record Ash(String id, String fuseState) {
        }
        for (Ash ash : new Ash[]{
                new Ash("cherry_bomb", "idle"),
                new Ash("jalapeno", "idle"),
                new Ash("doom_shroom", "idle"),
                new Ash("potato_mine", "grow"),
                new Ash("squash", "grow")}) {
            String path = animationPath(ash.id());
            ControllerFile controller = (ControllerFile) parseClasspath(ash.id());
            assertTrue(controller.clip(ash.fuseState()).isPresent(),
                    path + " has no '" + ash.fuseState() + "' clip, so its fuse silently plays idle");
            assertTrue(controller.clip("explode").isPresent(),
                    path + " has no 'explode' clip, so the blast is never drawn");
        }
    }

    /**
     * The nocturnal plants have to be able to look asleep.
     *
     * <p>Same trap as the ash line's fuse: the server publishes the {@code sleep} state by
     * name and a file without that clip plays {@code idle} instead - so a mushroom asleep in
     * daylight would look exactly like a wide-awake one, which is the one thing the player
     * has to be able to see before spending a coffee bean on it.
     */
    @Test
    void theNocturnalPlantsDefineASleepClip() throws Exception {
        for (String plant : new String[]{"puff_shroom", "doom_shroom"}) {
            String path = animationPath(plant);
            ControllerFile controller = (ControllerFile) parseClasspath(plant);
            assertTrue(controller.clip("sleep").isPresent(),
                    path + " has no 'sleep' clip, so a sleeping plant is drawn awake");
        }
    }

    private static AnimationFile parseClasspath(String path) throws Exception {
        String resolved = animationPath(path);
        try (var stream = AnimationResourceLoaderTest.class.getResourceAsStream(resolved)) {
            if (stream == null) {
                throw new IllegalStateException("Missing test resource " + resolved);
            }
            var reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
            return AnimationResourceLoader.parse(JsonParser.parseReader(reader).getAsJsonObject(),
                    Identifier.withDefaultNamespace(path));
        }
    }

    /**
     * Where a content id's animation file actually lives.
     *
     * <p>Asked of the production resolver rather than written out here: the shipped
     * art is grouped by kind ({@code animations/plant/attacker/pea_shooter.json}), and
     * a test that hardcodes the path would keep passing against a layout the game no
     * longer loads.
     */
    private static String animationPath(String defId) throws Exception {
        TestContent.loadBuiltInContentAndTags();
        Identifier fileId = com.pvzce.common.core.EntityArt.animationFile(
                Identifier.withDefaultNamespace(defId));
        assertNotNull(fileId, defId + " must resolve to an animation file id");
        return "/assets/" + fileId.namespace() + "/animations/" + fileId.path() + ".json";
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

    /**
     * An ash-line plant has to stay on the field at least as long as its own blast takes to draw.
     *
     * <p>The blast is instantaneous on the server and the {@code explode} clip is not: the plant
     * is removed when its {@code linger_ticks} run out, and a linger shorter than the clip cuts
     * the animation off mid-gesture. Thirty ticks was long enough for the plants it was chosen
     * for and far too short for the rest - the doom-shroom's growing cloud is two and three
     * quarter seconds, so it was drawn for a fifth of itself and then vanished.
     *
     * <p>Both numbers are read from where they live: the clip from the art file (its length
     * divided by its own playback rate) and the linger from the plant definition's capability.
     * A content author who lengthens a clip or slows it down gets told here rather than in a
     * screenshot.
     */
    @Test
    void everyAshPlantStaysLongEnoughToDrawItsBlast() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        for (String plant : new String[]{"cherry_bomb", "jalapeno", "doom_shroom", "potato_mine",
                "squash"}) {
            var def = com.pvzce.common.core.BuiltInRegistries.PLANTS.get(
                    Identifier.withDefaultNamespace(plant));
            assertNotNull(def, plant + " has to exist");
            var explosive = def.capabilities().stream()
                    .map(com.pvzce.api.content.capability.TypedCapability::value)
                    .filter(com.pvzce.common.capability.plant.ExplosiveCapability.class::isInstance)
                    .map(com.pvzce.common.capability.plant.ExplosiveCapability.class::cast)
                    .findFirst()
                    .orElse(null);
            assertNotNull(explosive, plant + " is an ash-line plant and has to carry the capability");

            ControllerClip explode = (ControllerClip) parseClasspath(plant).clip("explode")
                    .orElseThrow();
            // Clip seconds per world second: a slowed clip is on screen for longer, and the
            // linger is in ticks at 60tps.
            float screenSeconds = explode.duration() / explode.rate();
            int needed = (int) Math.ceil(screenSeconds * 60F);
            assertTrue(explosive.lingerTicks() >= needed,
                    plant + "'s explode clip needs " + needed + " ticks on screen but the plant"
                            + " is removed after " + explosive.lingerTicks() + ", so the blast is"
                            + " cut off before it finishes");
        }
    }

    /**
     * The ash line's blast draws the particles it names, and every one of them exists.
     *
     * <p>A missing particle draws nothing and says so once per second from the client, which is a
     * symptom with no address; a blast that is a composition of nine is nine chances to typo one.
     * The doom-shroom's cloud pieces are additionally required to be placed around the blast
     * rather than on top of each other - that placement is the whole reason they are a list.
     */
    @Test
    void theBlastsNameParticlesThatExistAndArePlaced() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        for (String plant : new String[]{"cherry_bomb", "doom_shroom"}) {
            var def = com.pvzce.common.core.BuiltInRegistries.PLANTS.get(
                    Identifier.withDefaultNamespace(plant));
            var explosive = def.capabilities().stream()
                    .map(com.pvzce.api.content.capability.TypedCapability::value)
                    .filter(com.pvzce.common.capability.plant.ExplosiveCapability.class::isInstance)
                    .map(com.pvzce.common.capability.plant.ExplosiveCapability.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertTrue(explosive.particles().size() > 1,
                    plant + " has an explosion of its own rather than the default flash");
            for (Identifier particle : explosive.particles()) {
                var particleDef = com.pvzce.common.core.BuiltInRegistries.PARTICLES.get(particle);
                assertNotNull(particleDef, plant + " names " + particle + ", which has no definition");
            }
        }

        var doom = com.pvzce.common.core.BuiltInRegistries.PLANTS.get(
                Identifier.withDefaultNamespace("doom_shroom"));
        var cloud = doom.capabilities().stream()
                .map(com.pvzce.api.content.capability.TypedCapability::value)
                .filter(com.pvzce.common.capability.plant.ExplosiveCapability.class::isInstance)
                .map(com.pvzce.common.capability.plant.ExplosiveCapability.class::cast)
                .findFirst()
                .orElseThrow();
        long placed = cloud.particles().stream()
                .map(com.pvzce.common.core.BuiltInRegistries.PARTICLES::get)
                .filter(java.util.Objects::nonNull)
                .filter(def -> def.motion().offsetX() != 0F || def.motion().offsetY() != 0F)
                .count();
        assertTrue(placed >= 5,
                "the doom-shroom's cloud is a mushroom shape, which only works if its pieces are"
                        + " born at different points; only " + placed + " of them are");
    }
}
