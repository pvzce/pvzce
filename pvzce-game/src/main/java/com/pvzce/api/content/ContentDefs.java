package com.pvzce.api.content;

import com.mojang.serialization.Codec;

/**
 * The bits of a content definition that every entity definition carries.
 *
 * <p>Small on purpose, but shared rather than copied: a plant, a zombie, a projectile
 * and a resource all answer "how big should the client draw me", and four hand-written
 * copies of one codec is how {@code drop_motion} and {@code texture} ended up meaning
 * slightly different things in different files.
 */
public final class ContentDefs {
    /** Drawn at the size the art declares. */
    public static final float DEFAULT_RENDER_SCALE = 1F;
    /**
     * Reasonable bounds for {@code render_scale}.
     *
     * <p>Clamped inside the codec rather than trusted: the value goes straight into a
     * vertex position, so a typo like {@code "render_scale": 1000} would otherwise fill
     * the whole framebuffer with one sunflower. The range is deliberately wide - it is a
     * presentation knob, not a game rule - and a value outside it is clamped, not
     * rejected, so a bad pack still loads.
     */
    public static final float MIN_RENDER_SCALE = 0.05F;
    public static final float MAX_RENDER_SCALE = 8F;

    /**
     * {@code render_scale}: how much bigger than its art the client draws this content.
     *
     * <p>Presentation only. It is not a hit box, not a collection radius and not
     * anything the server simulates; a definition that leaves it out is drawn at
     * exactly the size its animation or sprite already describes, which is what every
     * definition did before this field existed.
     */
    public static final com.mojang.serialization.MapCodec<Float> RENDER_SCALE_CODEC =
            Codec.FLOAT.optionalFieldOf("render_scale", DEFAULT_RENDER_SCALE)
                    .xmap(ContentDefs::clamp, value -> value);

    private static float clamp(float value) {
        if (Float.isNaN(value)) {
            return DEFAULT_RENDER_SCALE;
        }
        return Math.max(MIN_RENDER_SCALE, Math.min(MAX_RENDER_SCALE, value));
    }

    private ContentDefs() {
    }
}
