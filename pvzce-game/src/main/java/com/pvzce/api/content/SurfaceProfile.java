package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** An affine elevation field, optionally clamped into a ramp followed by a flat surface. */
public record SurfaceProfile(float elevation, float slopeX, float slopeY, float min, float max) {
    public static final SurfaceProfile FLAT = flat(0F);
    public static final Codec<SurfaceProfile> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.FLOAT.optionalFieldOf("elevation", 0F).forGetter(SurfaceProfile::elevation),
            Codec.FLOAT.optionalFieldOf("slope_x", 0F).forGetter(SurfaceProfile::slopeX),
            Codec.FLOAT.optionalFieldOf("slope_y", 0F).forGetter(SurfaceProfile::slopeY),
            Codec.FLOAT.optionalFieldOf("min", -Float.MAX_VALUE).forGetter(SurfaceProfile::min),
            Codec.FLOAT.optionalFieldOf("max", Float.MAX_VALUE).forGetter(SurfaceProfile::max)
    ).apply(i, SurfaceProfile::new));

    public SurfaceProfile {
        if (!Float.isFinite(elevation) || !Float.isFinite(slopeX) || !Float.isFinite(slopeY)
                || !Float.isFinite(min) || !Float.isFinite(max) || min > max) {
            throw new IllegalArgumentException("Invalid surface elevation profile");
        }
    }

    public static SurfaceProfile flat(float elevation) {
        return new SurfaceProfile(elevation, 0F, 0F, -Float.MAX_VALUE, Float.MAX_VALUE);
    }

    public float at(float x, float y) {
        return Math.max(min, Math.min(max, elevation + slopeX * x + slopeY * y));
    }
}
