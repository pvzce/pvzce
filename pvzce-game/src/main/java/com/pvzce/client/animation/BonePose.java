package com.pvzce.client.animation;

/** Sampled pose of a single controller bone. */
public record BonePose(float[] translation, float[] rotation, float[] scale, boolean visible) {
    public static final BonePose IDENTITY = new BonePose(
            new float[]{0F, 0F}, new float[]{0F, 0F, 0F}, new float[]{1F, 1F}, true);

    public BonePose {
        translation = translation == null ? new float[]{0F, 0F} : translation.clone();
        rotation = rotation == null ? new float[]{0F, 0F, 0F} : rotation.clone();
        scale = scale == null ? new float[]{1F, 1F} : scale.clone();
    }

    public static BonePose lerp(BonePose a, BonePose b, float t) {
        if (a == null) {
            return b == null ? IDENTITY : b;
        }
        if (b == null) {
            return a;
        }
        return new BonePose(
                new float[]{lerp(a.translation[0], b.translation[0], t), lerp(a.translation[1], b.translation[1], t)},
                new float[]{
                        lerp(a.rotation[0], b.rotation[0], t),
                        lerp(a.rotation[1], b.rotation[1], t),
                        lerp(a.rotation[2], b.rotation[2], t)
                },
                new float[]{lerp(a.scale[0], b.scale[0], t), lerp(a.scale[1], b.scale[1], t)},
                t < 0.5F ? a.visible : b.visible);
    }

    private static float lerp(float a, float b, float t) {
        return com.pvzce.common.util.MathUtil.lerp(a, b, t);
    }

    /**
     * Builds the bone's local affine matrix. The bone origin is at
     * {@code pivot + translation}; rotation/skew/scale are applied around it.
     */
    public Affine2 toAffine(float[] pivot) {
        float rx = (float) Math.toRadians(rotation[0]);
        float ry = (float) Math.toRadians(rotation[1]);
        float rz = (float) Math.toRadians(rotation[2]);
        float sx = scale[0];
        float sy = scale[1];

        float a = sx * (float) Math.cos(rx);
        float b = sy * (float) Math.sin(ry);
        float c = -sx * (float) Math.sin(rx);
        float d = sy * (float) Math.cos(ry);

        float cosZ = (float) Math.cos(rz);
        float sinZ = (float) Math.sin(rz);
        float m00 = cosZ * a - sinZ * c;
        float m01 = cosZ * b - sinZ * d;
        float m10 = sinZ * a + cosZ * c;
        float m11 = sinZ * b + cosZ * d;
        float tx = (pivot == null ? 0F : pivot[0]) + translation[0];
        float ty = (pivot == null ? 0F : pivot[1]) + translation[1];
        return new Affine2(m00, m01, tx, m10, m11, ty);
    }
}
