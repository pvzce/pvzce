package com.pvzce.client.animation;

import com.pvzce.api.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnimationSamplingTest {
    @Test
    void flipbookSamplingIsIndependentOfFrameRate() {
        FlipbookClip clip = new FlipbookClip(
                List.of(Identifier.withDefaultNamespace("a"), Identifier.withDefaultNamespace("b")),
                new float[]{0.25F, 0.25F}, true, AnimationClip.OnEnd.HOLD, "", 0F, List.of(), List.of());

        int frameAt30 = sampleFlipbookFrame(clip, 0.75D, 30);
        int frameAt60 = sampleFlipbookFrame(clip, 0.75D, 60);
        int frameAt144 = sampleFlipbookFrame(clip, 0.75D, 144);

        assertEquals(frameAt30, frameAt60);
        assertEquals(frameAt60, frameAt144);
        assertEquals(1, frameAt30);
    }

    private static int sampleFlipbookFrame(FlipbookClip clip, double endTime, int fps) {
        double step = 1.0D / fps;
        double time = 0D;
        int frame = 0;
        while (time <= endTime + 1.0E-9D) {
            frame = clip.frameIndex(time);
            time += step;
        }
        return clip.frameIndex(endTime);
    }

    @Test
    void bonePoseAppliesTranslationAndZRotation() {
        BonePose pose = new BonePose(new float[]{1F, 2F}, new float[]{0F, 0F, 90F}, new float[]{1F, 1F}, true);
        Affine2 affine = pose.toAffine(new float[]{0F, 0F});

        assertEquals(1F, affine.transformX(1F, 0F), 0.0001F);
        assertEquals(3F, affine.transformY(1F, 0F), 0.0001F);
    }

    @Test
    void controllerSamplingIsIndependentOfFrameRate() {
        ControllerModel model = new ControllerModel(List.of(
                new ControllerModel.Bone("root", null, new float[]{0F, 0F}, BonePose.IDENTITY, List.of()),
                new ControllerModel.Bone("leaf", "root", new float[]{0F, 0F}, BonePose.IDENTITY, List.of())));

        VectorTrack translation = new VectorTrack(List.of(
                new Keyframe<>(0F, new float[]{0F, 0F}, Easing.LINEAR),
                new Keyframe<>(1F, new float[]{1F, 0F}, Easing.LINEAR)));
        ControllerClip clip = new ControllerClip(1F, false, AnimationClip.OnEnd.HOLD, "", 0F,
                java.util.Map.of("leaf", new ControllerClip.BoneTracks(translation, null, null, null)),
                List.of(), List.of());

        float at30 = sampleControllerX(model, clip, 1.0D, 30);
        float at60 = sampleControllerX(model, clip, 1.0D, 60);
        float at144 = sampleControllerX(model, clip, 1.0D, 144);

        assertEquals(at30, at60, 0.0001F);
        assertEquals(at60, at144, 0.0001F);
        assertEquals(1F, at30, 0.0001F);
    }

    private static float sampleControllerX(ControllerModel model, ControllerClip clip, double endTime, int fps) {
        double step = 1.0D / fps;
        double time = 0D;
        float x = 0F;
        while (time <= endTime + 1.0E-9D) {
            x = clip.samplePose(model, time).get("leaf").translation()[0];
            time += step;
        }
        return clip.samplePose(model, endTime).get("leaf").translation()[0];
    }
}
