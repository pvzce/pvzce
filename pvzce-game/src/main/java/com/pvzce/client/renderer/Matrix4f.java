package com.pvzce.client.renderer;

import java.nio.FloatBuffer;
import java.util.Arrays;

import org.lwjgl.BufferUtils;

/** Minimal column-major 4x4 matrix for the orthographic camera. */
public final class Matrix4f {
    private final float[] m = new float[16];

    public static Matrix4f identity() {
        Matrix4f matrix = new Matrix4f();
        matrix.m[0] = matrix.m[5] = matrix.m[10] = matrix.m[15] = 1;
        return matrix;
    }

    public static Matrix4f ortho(float left, float right, float bottom, float top, float near, float far) {
        Matrix4f matrix = new Matrix4f();
        matrix.m[0] = 2F / (right - left);
        matrix.m[5] = 2F / (top - bottom);
        matrix.m[10] = -2F / (far - near);
        matrix.m[12] = -(right + left) / (right - left);
        matrix.m[13] = -(top + bottom) / (top - bottom);
        matrix.m[14] = -(far + near) / (far - near);
        matrix.m[15] = 1F;
        return matrix;
    }

    public FloatBuffer toBuffer() {
        FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
        buffer.put(m).flip();
        return buffer;
    }

    @Override
    public String toString() {
        return Arrays.toString(m);
    }
}
