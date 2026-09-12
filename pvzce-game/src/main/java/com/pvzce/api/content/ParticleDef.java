package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

import java.util.List;

/**
 * A data-driven particle: how one effect looks and how it moves.
 *
 * <p>Particles used to be a hardcoded {@code switch} on the effect name inside
 * {@code ParticleEngine}, which could only draw a coloured square; every sprite in
 * the original's particle set (pea splats, explosion clouds, flying zombie heads,
 * dust) was unreachable. A definition names a texture - or a frame sequence - plus
 * the numbers that shape it, so a new effect is data.
 *
 * <p>What is deliberately <em>not</em> here: how many particles an effect spawns and
 * when. That belongs to the capability that emits it ({@code level.emitEffect}),
 * because the same sprite serves several effects with different counts.
 *
 * <p>The JSON is flat even though the record is nested - {@link ParticleLook} and
 * {@link ParticleMotion} write their fields at the top level, because a definition
 * file reads better as one object and DFU's {@code group} tops out around sixteen
 * fields, which this definition needs more of.
 *
 * <p>Distances are world cells and times are seconds. The shipped definitions were
 * converted from the original's per-frame pixel units by
 * {@code tools/particles_to_pvzce.py}, which documents the conversion factors.
 */
public record ParticleDef(
        Identifier id,
        ParticleLook look,
        ParticleMotion motion,
        boolean additive,
        int count,
        int countSpread
) {
    /** The default effect: a small, briefly visible puff. */
    public static final float DEFAULT_SCALE = 0.16F;
    public static final float DEFAULT_LIFETIME = 0.5F;

    public static final Codec<ParticleDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(ParticleDef::id),
            // ``MAP_CODEC.codec().fieldOf`` nests the sub-record under its own key.
            // ``MAP_CODEC.forGetter`` would instead flatten its fields into this object,
            // which silently asked the top level for a "texture" and failed there.
            ParticleLook.MAP_CODEC.codec().fieldOf("look").forGetter(ParticleDef::look),
            // A definition that does not move omits ``motion`` entirely; most effects
            // are a sprite that fades where it was spawned.
            ParticleMotion.MAP_CODEC.codec()
                    .optionalFieldOf("motion", ParticleMotion.STILL).forGetter(ParticleDef::motion),
            Codec.BOOL.optionalFieldOf("additive", false).forGetter(ParticleDef::additive),
            Codec.INT.optionalFieldOf("count", 1).forGetter(ParticleDef::count),
            Codec.INT.optionalFieldOf("count_spread", 0).forGetter(ParticleDef::countSpread)
    ).apply(i, ParticleDef::new));

    /**
     * What a particle looks like: its art, its size, and how both change as it ages.
     *
     * @param texture         the sprite, or the first frame when {@code frames} is set
     * @param frames          the full frame sequence, empty for a single sprite
     * @param framesPerSecond animation rate; 0 freezes on the first frame
     * @param loop            whether the frame sequence repeats over the lifetime
     * @param lifetime        seconds the particle lives
     * @param scale           base size in world cells
     * @param scaleSpread     +/- random variation on {@code scale}
     * @param spin            degrees per second; 0 keeps the particle upright
     * @param randomSpin      start at a random angle as well
     * @param alphaFrom       opacity at birth
     * @param alphaTo         opacity at death, when there is no curve
     * @param alphaCurve      optional {@code [[time, value], ...]} fade table
     * @param scaleCurve      optional {@code [[time, value], ...]} size table
     * @param color           tint as {r, g, b} in 0..1
     */
    public record ParticleLook(
            Identifier texture,
            List<Identifier> frames,
            float framesPerSecond,
            boolean loop,
            float lifetime,
            float scale,
            float scaleSpread,
            float spin,
            boolean randomSpin,
            float alphaFrom,
            float alphaTo,
            List<List<Float>> alphaCurve,
            List<List<Float>> scaleCurve,
            List<Float> color
    ) {
        /**
         * One {@code [time, value]} keyframe of a fade table.
         *
         * <p>Declared before {@link #MAP_CODEC} because Java runs static initialisers
         * in source order and the codec below reads it; with the two swapped, the whole
         * particle registry failed to class-load.
         */
        private static final Codec<List<Float>> CURVE_POINT = Codec.FLOAT.listOf();

        public static final MapCodec<ParticleLook> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Identifier.CODEC.fieldOf("texture").forGetter(ParticleLook::texture),
                Identifier.CODEC.listOf().optionalFieldOf("frames", List.of()).forGetter(ParticleLook::frames),
                Codec.FLOAT.optionalFieldOf("frames_per_second", 0F).forGetter(ParticleLook::framesPerSecond),
                Codec.BOOL.optionalFieldOf("loop", true).forGetter(ParticleLook::loop),
                Codec.FLOAT.optionalFieldOf("life", DEFAULT_LIFETIME).forGetter(ParticleLook::lifetime),
                Codec.FLOAT.optionalFieldOf("scale", DEFAULT_SCALE).forGetter(ParticleLook::scale),
                Codec.FLOAT.optionalFieldOf("scale_spread", 0F).forGetter(ParticleLook::scaleSpread),
                Codec.FLOAT.optionalFieldOf("spin", 0F).forGetter(ParticleLook::spin),
                Codec.BOOL.optionalFieldOf("random_spin", false).forGetter(ParticleLook::randomSpin),
                Codec.FLOAT.optionalFieldOf("alpha_from", 1F).forGetter(ParticleLook::alphaFrom),
                Codec.FLOAT.optionalFieldOf("alpha_to", 0F).forGetter(ParticleLook::alphaTo),
                curve("alpha_curve").forGetter(ParticleLook::alphaCurve),
                curve("scale_curve").forGetter(ParticleLook::scaleCurve),
                Codec.FLOAT.listOf().optionalFieldOf("color", List.of(1F, 1F, 1F)).forGetter(ParticleLook::color)
        ).apply(i, ParticleLook::new));

        /**
         * A {@code [[time, value], ...]} table, empty by default.
         *
         * <p>Optional on purpose: a straight fade is the common case and the two
         * endpoints already express it, so only a genuinely curved one carries a table.
         *
         * <p>Returns a {@link MapCodec} so the caller adds its own {@code forGetter};
         * this DFU version has no free-standing overload that would accept it here
         * otherwise.
         */
        private static MapCodec<List<List<Float>>> curve(String name) {
            return CURVE_POINT.listOf().optionalFieldOf(name, List.of());
        }

        public ParticleLook {
            frames = List.copyOf(frames);
            alphaCurve = List.copyOf(alphaCurve);
            scaleCurve = List.copyOf(scaleCurve);
            color = color.size() >= 3 ? List.copyOf(color.subList(0, 3)) : List.of(1F, 1F, 1F);
        }

        /** The frame list when animated, otherwise the single texture. */
        public List<Identifier> allFrames() {
            return frames.isEmpty() ? List.of(texture) : frames;
        }

        public boolean animated() {
            return frames.size() > 1 && framesPerSecond > 0F;
        }

        /** The tint as {r, g, b}, for the renderer. */
        public float[] colorArray() {
            return new float[]{color.get(0), color.get(1), color.get(2)};
        }

        /**
         * The opacity at {@code progress} (0..1) through the particle's life.
         *
         * <p>A curve table wins over the two endpoints; it exists because the
         * original's fades are not straight lines (a puff that swells then vanishes, a
         * glow that pulses), and the converter normalises them so the engine only has
         * to interpolate.
         */
        public float alphaAt(float progress) {
            if (!alphaCurve.isEmpty()) {
                return sample(alphaCurve, progress, alphaFrom);
            }
            return alphaFrom + (alphaTo - alphaFrom) * clamp01(progress);
        }

        /** The size multiplier at {@code progress} (0..1) through the particle's life. */
        public float scaleAt(float progress) {
            return scaleCurve.isEmpty() ? 1F : sample(scaleCurve, progress, 1F);
        }
    }

    /**
     * How a particle moves.
     *
     * @param speed       initial speed in cells per second
     * @param speedSpread +/- random variation on {@code speed}
     * @param angle       launch direction in degrees; 0 is right, counter-clockwise
     * @param angleSpread total width of the launch cone, in degrees
     * @param gravity     downward acceleration in cells per second squared
     * @param drag        fraction of velocity shed per second
     * @param bounce      stop at the ground line instead of falling through it
     */
    public record ParticleMotion(
            float speed,
            float speedSpread,
            float angle,
            float angleSpread,
            float gravity,
            float drag,
            boolean bounce
    ) {
        /** A particle that never moves: the default when a definition omits {@code motion}. */
        public static final ParticleMotion STILL =
                new ParticleMotion(0F, 0F, 90F, 0F, 0F, 0F, false);

        public static final MapCodec<ParticleMotion> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.FLOAT.optionalFieldOf("speed", 0F).forGetter(ParticleMotion::speed),
                Codec.FLOAT.optionalFieldOf("speed_spread", 0F).forGetter(ParticleMotion::speedSpread),
                Codec.FLOAT.optionalFieldOf("angle", 90F).forGetter(ParticleMotion::angle),
                Codec.FLOAT.optionalFieldOf("angle_spread", 0F).forGetter(ParticleMotion::angleSpread),
                Codec.FLOAT.optionalFieldOf("gravity", 0F).forGetter(ParticleMotion::gravity),
                Codec.FLOAT.optionalFieldOf("drag", 0F).forGetter(ParticleMotion::drag),
                Codec.BOOL.optionalFieldOf("bounce", false).forGetter(ParticleMotion::bounce)
        ).apply(i, ParticleMotion::new));
    }

    private static float sample(List<List<Float>> curve, float progress, float fallback) {
        if (curve.isEmpty()) {
            return fallback;
        }
        float previousTime = curve.get(0).get(0);
        float previousValue = curve.get(0).get(1);
        for (List<Float> point : curve) {
            float time = point.get(0);
            float value = point.get(1);
            if (time >= progress) {
                if (time <= previousTime) {
                    return value;
                }
                float ratio = (progress - previousTime) / (time - previousTime);
                return previousValue + (value - previousValue) * ratio;
            }
            previousTime = time;
            previousValue = value;
        }
        return curve.get(curve.size() - 1).get(1);
    }

    private static float clamp01(float value) {
        return value < 0F ? 0F : (value > 1F ? 1F : value);
    }
}
