package com.pvzce.client.animation;

import com.google.gson.JsonParser;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    /**
     * The threepeater draws three heads standing still, and one head at a time firing.
     *
     * <p>Its three heads live on three timelines that never overlap in the source (head 1's face
     * on frames 4..41, head 3's on 45..82, head 2's on 86..123), so no single frame of the file
     * shows the plant. The converter's rescue rule forces the head group on for the standing
     * pose - and it is matched with {@code fullmatch} against de-duplicated names, so a pattern
     * that named the family without allowing the {@code _2}/{@code _3} suffixes silently dropped
     * two of the three heads and every headleaf. A threepeater at rest drew one head on three
     * stems, and mid-volley it drew all three faces on the same pixel.
     *
     * <p>Both halves are pinned here because they fail in opposite directions: the idle clip has
     * to show <em>every</em> head, and the shoot clip has to show exactly one per volley.
     */
    @Test
    void theThreepeaterDrawsThreeHeadsIdleAndOneAtATimeFiring() throws Exception {
        ControllerFile controller = (ControllerFile) parseClasspath("threepeater");
        String[] heads = {"head", "head_2", "head_3"};
        String[] mouths = {"mouth", "mouth_2", "mouth_3"};

        ControllerClip idle = (ControllerClip) controller.clip("idle").orElseThrow();
        var idlePose = idle.samplePose(controller.model(), 0D);
        for (String head : heads) {
            assertTrue(idlePose.get(head).visible(),
                    head + " must be drawn in the idle pose; a standing threepeater has three heads");
        }
        for (String mouth : mouths) {
            assertTrue(idlePose.get(mouth).visible(), mouth + " belongs to a head that is up");
        }

        // The volley: three shooting masks laid end to end, one head per phase. The invariant is
        // "never two at once" over every frame, plus "all three get a turn" - the defect being
        // guarded is three faces drawn on the same pixel, and a sampling scheme that only looked
        // at three guessed moments would have missed it.
        ControllerClip shoot = (ControllerClip) controller.clip("shoot").orElseThrow();
        // Three masks of 13 source frames each, laid end to end with no dead time: the clip's
        // length is their sum, not the 95-frame span they live in.
        assertEquals(3.25D, shoot.duration(), 0.001D,
                "the three volleys back to back, not the whole span they sit in");
        java.util.Set<String> fired = new java.util.HashSet<>();
        for (int step = 0; step <= 200; step++) {
            double at = shoot.duration() * step / 200.0;
            var pose = shoot.samplePose(controller.model(), at);
            int up = 0;
            for (String head : heads) {
                if (pose.get(head).visible()) {
                    up++;
                    fired.add(head);
                }
            }
            assertTrue(up <= 1,
                    "two heads were drawn at once at " + at + "s; the volley fires one at a time");
        }
        assertEquals(3, fired.size(),
                "every head takes its turn over one clip, saw " + fired);
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

    /**
     * Every animation the pack ships parses, not only the handful named above.
     *
     * <p>The bungee zombie shipped with two bones called {@code hand_2}: the converter's suffix
     * counter was keyed per base name, so parts that canonicalized to {@code hand} / {@code hand_2}
     * and to {@code hand2} produced a collision. {@code ControllerModel} rejects a duplicate
     * outright, the loader logged one warning and rendered nothing, and the zombie was the
     * missing-texture tile - a failure a screenshot caught and no test did. This walks the whole
     * shipped tree so the next one cannot ship silently.
     */
    @Test
    void everyShippedAnimationParses() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of(
                AnimationResourceLoaderTest.class.getResource("/assets/pvzce/animations").toURI());
        int parsed = 0;
        try (var paths = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path path : paths
                    .filter(candidate -> candidate.toString().endsWith(".json")).toList()) {
                var json = JsonParser.parseReader(java.nio.file.Files.newBufferedReader(
                        path, StandardCharsets.UTF_8)).getAsJsonObject();
                AnimationResourceLoader.parse(json,
                        Identifier.withDefaultNamespace(path.getFileName().toString()));
                parsed++;
            }
        }
        assertTrue(parsed > 50,
                "the walk should have seen the whole shipped tree, saw " + parsed);
    }

    /**
     * A mat-like plant is about one cell wide, not two.
     *
     * <p>The spikeweed is a flat, wide mat (its drawn box is 83x36) and the converter fits every
     * plant by <em>height</em>, so it was scaled up until the model was 0.76 cells tall and 1.8
     * cells wide - drawn across the neighbouring cells. The fix is to fit it by both axes; this
     * pins the result so a future converter pass cannot quietly put it back.
     */
    @Test
    void theSpikeweedIsAboutOneCellWide() throws Exception {
        ControllerFile spikeweed = (ControllerFile) parseClasspath("spikeweed");
        float widest = 0F;
        for (ControllerModel.Bone bone : spikeweed.model().bones().values()) {
            for (ControllerModel.Part part : bone.parts()) {
                widest = Math.max(widest, part.sizeX());
            }
        }
        // The model carries no render_scale - the plant definition's 0.85 is applied at draw time
        // - so the asserted number is the model's own width, a little over one cell.
        assertTrue(widest <= 1.15F,
                "the spikeweed's widest part is " + widest + " cells; it would spill into the"
                        + " cells beside it");
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

    /**
     * An attack clip must not rescale the parts the idle clip established.
     *
     * <p>The threepeater's reported "the attack animation is very weird": its {@code shoot} clip
     * held all nine leaf and stem bones at a single keyframe of {@code scale 1.0} parked at an
     * untransformed pixel position, while {@code idle} animated the same bones at
     * {@code scale 0.555} around the model's centre. So the instant it fired, the whole plant
     * ballooned by 1.8x and jumped outward - and snapped back when the clip handed over to idle.
     *
     * <p>Every sibling attacker keeps the idle pose for the parts it does not animate during the
     * attack (a peashooter's leaves do not move when it shoots), so the rule is a property of the
     * attack family rather than of one file. This walks every plant that has both clips and
     * compares, bone by bone, wherever the attack clip <em>does</em> have a scale for a bone the
     * idle clip animates.
     */
    @Test
    void anAttackClipDoesNotRescaleThePartsTheIdleClipEstablished() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        List<String> offenders = new ArrayList<>();
        for (Identifier plantId : com.pvzce.common.core.BuiltInRegistries.PLANTS.keySet()) {
            AnimationFile file = readClasspathAnimation(plantId);
            if (!(file instanceof ControllerFile controller)) {
                continue;
            }
            ControllerClip idle = asControllerClip(controller.clip("idle").orElse(null));
            ControllerClip shoot = asControllerClip(controller.clip("shoot").orElse(null));
            if (idle == null || shoot == null) {
                continue;
            }
            for (Map.Entry<String, ControllerClip.BoneTracks> entry : idle.bones().entrySet()) {
                ControllerClip.BoneTracks idleTracks = entry.getValue();
                if (idleTracks.scale().maxTime() <= 0F) {
                    // A bone the idle clip itself parks at one instant: nothing was established
                    // for the attack clip to preserve.
                    continue;
                }
                ControllerClip.BoneTracks shootTracks = shoot.tracks(entry.getKey());
                if (shootTracks == null || shootTracks.scale().isEmpty()) {
                    continue;
                }
                float[] idleAtStart = idleTracks.scale().sample(0, null);
                float[] shootAtStart = shootTracks.scale().sample(0, null);
                if (idleAtStart == null || shootAtStart == null) {
                    continue;
                }
                float idleScale = idleAtStart[0];
                float shootScale = shootAtStart[0];
                float ratio = shootScale / Math.max(0.0001F, idleScale);
                if (ratio < 0.75F || ratio > 1.25F) {
                    offenders.add(plantId.path() + "." + entry.getKey() + " idle=" + idleScale
                            + " shoot=" + shootScale);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "these bones change size between idle and the attack, which reads as the plant"
                        + " inflating when it fires: " + offenders);
    }

    /**
     * An action clip must not hide, for its whole length, a part the idle clip shows.
     *
     * <p>The watering can's reported "the animation is missing": its {@code attack} clip set all
     * four of the ordinary can's bones to {@code visible: false} for the full 0.85s and switched on
     * the three <em>gold</em> ones instead. The gold can is the Zen Garden's upgrade and nothing in
     * the renderer ever asks for a variant, so clicking the can swapped the sprite to a gold can
     * and back - an animation that plays, but is not the animation of the object the player is
     * holding. The idle clip shows the ordinary can, so the rule is "whatever the object looks like
     * standing still, the action clip cannot make all of it disappear".
     *
     * <p>Sampled rather than read off the keyframes: "hidden for the whole clip" is a statement
     * about every instant in it, and the threepeater legitimately hides two of its three heads for
     * part of each volley.
     */
    @Test
    void anActionClipDoesNotHideEveryBoneTheIdleClipShows() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        List<String> offenders = new ArrayList<>();
        for (String id : new String[]{"watering_can", "shovel", "glove", "hammer",
                "pea_shooter", "sunflower", "wall_nut", "threepeater"}) {
            AnimationFile file = readClasspathAnimation(Identifier.withDefaultNamespace(id));
            if (!(file instanceof ControllerFile controller)) {
                continue;
            }
            ControllerClip idle = asControllerClip(controller.clip("idle").orElse(null));
            if (idle == null) {
                continue;
            }
            for (String action : new String[]{"attack", "shoot"}) {
                ControllerClip clip = asControllerClip(controller.clip(action).orElse(null));
                if (clip == null || clip.duration() <= 0F) {
                    continue;
                }
                for (Map.Entry<String, ControllerClip.BoneTracks> entry : idle.bones().entrySet()) {
                    if (!entry.getValue().visible().sample(0F, false)) {
                        continue;
                    }
                    ControllerClip.BoneTracks tracks = clip.tracks(entry.getKey());
                    if (tracks == null) {
                        // Absent from the clip: the bone keeps its rest pose, which is visible.
                        continue;
                    }
                    boolean everVisible = false;
                    for (int step = 0; step <= 16; step++) {
                        float time = clip.duration() * step / 16F;
                        if (tracks.visible().sample(time, true)) {
                            everVisible = true;
                            break;
                        }
                    }
                    if (!everVisible) {
                        offenders.add(id + "." + action + " hides " + entry.getKey()
                                + " for the whole clip");
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "these action clips make the object disappear for their whole length: " + offenders);
    }

    /**
     * Every tool that declares art has that art, and the cursor family has both clips.
     *
     * <p>A tool card that is selected draws the tool itself under the pointer
     * ({@code InGameScreen.renderDefaultToolCursor}), and it plays {@code attack} on the click that
     * used it. Both halves fail quietly: a missing file draws nothing, and a missing clip falls
     * back to {@code idle} - so a tool whose art was never written is a cursor the player does not
     * have, and one whose click has no clip looks like the click did nothing. The vase tool shipped
     * that way (its {@code animation_dir} pointed at a file nobody had written), which is what this
     * pins.
     *
     * <p>{@code attack} is required of the <b>cursor family</b> ({@code animation_dir: "tool"}) and
     * not of every tool, because the two families are two different objects: {@code tool/} is the
     * thing under the pointer, which has a gesture, while {@code mechanic/} is a prop that stands on
     * the lawn (the rake) and is drawn from its {@code idle} at a place the level owns. A rake that
     * held still in the player's hand is a rake; a vase that did would be a broken cursor.
     */
    @Test
    void everyToolThatDeclaresArtHasTheClipsItPlays() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        List<String> offenders = new ArrayList<>();
        for (Identifier toolId : com.pvzce.common.core.BuiltInRegistries.TOOLS.keySet()) {
            String dir = com.pvzce.common.core.EntityArt.animationDir(toolId);
            if (com.pvzce.common.core.BuiltInRegistries.TOOLS.get(toolId) == null || dir == null) {
                // No declared directory: this tool is card art only, which is a supported state.
                continue;
            }
            AnimationFile file = readClasspathAnimation(toolId);
            if (!(file instanceof ControllerFile controller)) {
                offenders.add(toolId + " declares art in '" + dir + "' but its file is missing");
                continue;
            }
            if (controller.clip("idle").isEmpty()) {
                offenders.add(toolId + " has no 'idle' clip, so there is nothing to draw");
            }
            if ("tool".equals(dir) && controller.clip("attack").isEmpty()) {
                offenders.add(toolId + " is cursor art with no 'attack' clip, so its click"
                        + " would look like nothing happened");
            }
        }
        assertTrue(offenders.isEmpty(),
                "these tools would be drawn as nothing, or would not react to a click: "
                        + offenders);
    }

    /** The controller form of a clip, or {@code null} when it is a flipbook or missing. */
    private static ControllerClip asControllerClip(AnimationClip clip) {
        return clip instanceof ControllerClip controller ? controller : null;
    }

    /** Resolves a content id to its animation file by parsing it straight off the classpath. */
    private static AnimationFile readClasspathAnimation(Identifier defId) throws Exception {
        Identifier fileId = com.pvzce.common.core.EntityArt.animationFile(defId);
        if (fileId == null) {
            return null;
        }
        String resource = "/assets/" + fileId.namespace() + "/animations/" + fileId.path() + ".json";
        try (var stream = AnimationResourceLoaderTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                return null;
            }
            var reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
            return AnimationResourceLoader.parse(JsonParser.parseReader(reader).getAsJsonObject(),
                    fileId);
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
        for (String plant : new String[]{"cherry_bomb", "jalapeno", "doom_shroom", "potato_mine"}) {
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
     * The squash holds its landing pose for as long as the landing pose takes to draw.
     *
     * <p>The same invariant as the ash line's, for the one plant that used to be on that list and
     * no longer is: the squash stopped being a blast (it leaps onto one zombie instead of exploding
     * in a footprint) so it no longer carries {@code ExplosiveCapability}, but it still holds an
     * {@code explode} clip after the strike and would still cut it off by removing itself early.
     */
    @Test
    void theSquashHoldsItsLandingPoseLongEnoughToDraw() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        var def = com.pvzce.common.core.BuiltInRegistries.PLANTS.get(
                Identifier.withDefaultNamespace("squash"));
        assertNotNull(def, "squash has to exist");
        var squash = def.capabilities().stream()
                .map(com.pvzce.api.content.capability.TypedCapability::value)
                .filter(com.pvzce.common.capability.plant.SquashCapability.class::isInstance)
                .map(com.pvzce.common.capability.plant.SquashCapability.class::cast)
                .findFirst()
                .orElse(null);
        assertNotNull(squash, "the squash is a leaping plant and has to carry pvzce:squash");

        ControllerClip explode = (ControllerClip) parseClasspath("squash").clip("explode")
                .orElseThrow();
        float screenSeconds = explode.duration() / explode.rate();
        int needed = (int) Math.ceil(screenSeconds * 60F);
        assertTrue(squash.lingerTicks() >= needed,
                "squash's explode clip needs " + needed + " ticks on screen but the plant is"
                        + " removed after " + squash.lingerTicks() + ", so the landing is cut off"
                        + " before it finishes");
    }

    /**
     * The ash line's blast draws the particles it names, and every one of them exists.
     *
     * <p>A missing particle draws nothing and says so once per second from the client, which is a
     * symptom with no address; a blast that is a composition of nine is nine chances to typo one.
     * The doom-shroom's cloud pieces are additionally required to be placed around the blast
     * rather than on top of each other - that placement is the whole reason they are a list.
     *
     * <p>The potato mine is why the loop names its plants instead of scanning the ash line: it
     * shipped with no {@code particles} at all, so it silently drew the capability's default
     * flash - which happens to be the middle piece of the cherry bomb's composition. Falling back
     * to that default is a wrong answer for a plant that has an explosion of its own in the
     * original's emitter list, so the mine is checked against the default explicitly.
     */
    @Test
    void theBlastsNameParticlesThatExistAndArePlaced() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        for (String plant : new String[]{"cherry_bomb", "doom_shroom", "potato_mine"}) {
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
            assertNotEquals(com.pvzce.common.capability.plant.ExplosiveCapability.DEFAULT_PARTICLES,
                    explosive.particles(),
                    plant + " must not draw the ash line's generic flash");
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
