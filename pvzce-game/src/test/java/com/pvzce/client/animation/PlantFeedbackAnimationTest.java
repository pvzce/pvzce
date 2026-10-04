package com.pvzce.client.animation;

import com.google.gson.JsonParser;
import com.pvzce.api.util.Identifier;
import java.io.InputStreamReader;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PlantFeedbackAnimationTest {
    private static ControllerFile asset(String path) throws Exception {
        var stream = PlantFeedbackAnimationTest.class.getClassLoader().getResourceAsStream(
                "assets/pvzce/animations/" + path + ".json");
        assumeTrue(stream != null, "original assets are optional in a public checkout");
        try (var reader = new InputStreamReader(stream)) {
            return (ControllerFile) AnimationResourceLoader.parse(JsonParser.parseReader(reader).getAsJsonObject(),
                    Identifier.withDefaultNamespace("feedback"));
        }
    }

    @Test void gatlingIdleAnimatesBodyAndHeadTogetherWithoutBlinkOverlays() throws Exception {
        var file = asset("plant/attacker/gatling_pea");
        var clip = file.clips().get("idle");
        for (String bone : new String[]{"head", "peashooter_stalk_top", "peashooter_frontleaf"}) {
            var first = clip.samplePose(file.model(), 0D).get(bone);
            var later = clip.samplePose(file.model(), 0.6D).get(bone);
            assertTrue(first.visible() && later.visible(), bone + " is part of the whole plant");
            assertTrue(Math.abs(first.translation()[1] - later.translation()[1]) > 0.0001F,
                    bone + " must move through its own idle timeline");
        }
        for (double t = 0; t < clip.duration(); t += 0.05) {
            var pose = clip.samplePose(file.model(), t);
            assertTrue(pose.entrySet().stream().filter(e -> e.getKey().startsWith("blink"))
                    .noneMatch(e -> e.getValue().visible()), "idle must not force the closed eyes over the face");
        }
    }

    @Test void balloonFloatsGentlyWithoutScalingItsClockToTravelSpeed() throws Exception {
        var file = asset("zombie/special/balloon_zombie");
        var clip = file.clips().get("fly");
        assertEquals(0F, clip.referenceSpeed());
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (double t = 0; t < clip.duration(); t += 0.05) {
            var pose = clip.samplePose(file.model(), t).get("body_1");
            assertTrue(pose.visible());
            min = Math.min(min, pose.translation()[0]);
            max = Math.max(max, pose.translation()[0]);
        }
        assertTrue(max - min < 0.05F, "flight cannot play the broad side-to-side swing");
    }

    @Test void headAttachmentUsesParentTransformAndTheHeadQuad() {
        var file = (ControllerFile) AnimationResourceLoader.parse(JsonParser.parseString("""
                {"type":"controller","model":{"bones":[
                  {"name":"root","pivot":[0,0]},
                  {"name":"head","parent":"root","parts":[
                    {"texture":"test:head","uv":[0,0,8,8],"size":[0.4,0.6],"offset":[0.1,0.2]}]}]},
                 "animations":{"idle":{"animation_length":1,"loop":true,"bones":{
                   "root":{"translation":{"0.0":[1,2]}},
                   "head":{"translation":{"0.0":[0.5,0.3]}}}}}}
                """).getAsJsonObject(), Identifier.of("test", "head"));
        var playback = new ControllerPlayback(null, null, file, file.clips().get("idle"),
                "idle", "idle", null, 0D);
        assertArrayEquals(new float[]{1.6F, 2.8F}, playback.boneTop("head", 0D), 0.0001F);
    }
}
