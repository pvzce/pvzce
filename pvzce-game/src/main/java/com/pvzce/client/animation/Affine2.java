package com.pvzce.client.animation;

/**
 * Minimal 2D affine transform stored as
 * {@code [m00 m01 m02; m10 m11 m12]}.
 */
public record Affine2(float m00, float m01, float m02, float m10, float m11, float m12) {
    public static final Affine2 IDENTITY = new Affine2(1F, 0F, 0F, 0F, 1F, 0F);

    /** this * other (apply {@code other} first, then {@code this}). */
    public Affine2 multiply(Affine2 other) {
        return new Affine2(
                m00 * other.m00 + m01 * other.m10,
                m00 * other.m01 + m01 * other.m11,
                m00 * other.m02 + m01 * other.m12 + m02,
                m10 * other.m00 + m11 * other.m10,
                m10 * other.m01 + m11 * other.m11,
                m10 * other.m02 + m11 * other.m12 + m12);
    }

    public float transformX(float x, float y) {
        return m00 * x + m01 * y + m02;
    }

    public float transformY(float x, float y) {
        return m10 * x + m11 * y + m12;
    }
}
