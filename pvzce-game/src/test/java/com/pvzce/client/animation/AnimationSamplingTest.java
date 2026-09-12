package com.pvzce.client.animation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnimationSamplingTest {
    @Test
    void bonePoseAppliesTranslationAndZRotation() {
        BonePose pose = new BonePose(new float[]{1F, 2F}, new float[]{0F, 0F, 90F}, new float[]{1F, 1F}, true);
        Affine2 affine = pose.toAffine(new float[]{0F, 0F});

        assertEquals(1F, affine.transformX(1F, 0F), 0.0001F);
        assertEquals(3F, affine.transformY(1F, 0F), 0.0001F);
    }
}
