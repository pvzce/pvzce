package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.util.MathUtil;

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
     * @param aspect          how much wider than tall the drawn sprite is; 1 is a square
     * @param spin            degrees per second; 0 keeps the particle upright
     * @param spinSpread      +/- random variation on {@code spin}, so the pieces of one
     *                        burst do not tumble in lockstep
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
            float aspect,
            float spin,
            float spinSpread,
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
                // A sprite is not always square, and the original never drew one into a square
                // box: the arm it throws is 26x50 pixels. `scale` stays what it always was -
                // the height, in cells - and this is the factor the width is drawn with.
                Codec.FLOAT.optionalFieldOf("aspect", 1F).forGetter(ParticleLook::aspect),
                Codec.FLOAT.optionalFieldOf("spin", 0F).forGetter(ParticleLook::spin),
                Codec.FLOAT.optionalFieldOf("spin_spread", 0F).forGetter(ParticleLook::spinSpread),
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
            aspect = aspect <= 0F ? 1F : aspect;
            frames = List.copyOf(frames);
            alphaCurve = List.copyOf(alphaCurve);
            scaleCurve = List.copyOf(scaleCurve);
            color = color.size() >= 3 ? List.copyOf(color.subList(0, 3)) : List.of(1F, 1F, 1F);
        }

        /**
         * How much wider than tall the sprite is drawn; 1 is a square.
         *
         * <p>The original never drew a particle into a box of the wrong shape: a thrown arm is
         * 26x50 pixels and stays that shape on the lawn. This engine drew every particle as a
         * square - which is right for the ~120 sprites that are square, and wrong for the
         * pieces that come off a zombie: an arm came out 1.4 times too wide, and a screen door
         * taller than it is wide came out squat. {@code scale} is still the height in cells;
         * this is what the width is multiplied by.
         */
        public float aspect() {
            return aspect;
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
            return alphaFrom + (alphaTo - alphaFrom) * MathUtil.clamp01(progress);
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
     * @param groundFriction how much horizontal speed one landing keeps, 0..1
     */
    public record ParticleMotion(
            float speed,
            float speedSpread,
            float angle,
            float angleSpread,
            float gravity,
            float drag,
            boolean bounce,
            float groundFriction,
            /**
             * Where a {@code bounce} particle's ground line sits, in cells below the point it
             * was spawned at.
             *
             * <p>Relative to the spawn and not to the world, because that is the only thing
             * that works for every row: a particle spawned in row 3 has to land on the lawn
             * under row 3. It is data rather than a constant because the spawn point is not
             * always the ground - a hat leaves the zombie's *head*, which is half a cell up,
             * and with the old fixed 0.42 it "landed" in mid-air just under the head and
             * skittered there. The emitter's own number (the original's {@code GroundConstraint})
             * is the right one, so it travels.
             */
            float groundOffset,
            /**
             * Where this particle is born, in cells, relative to the point the effect was
             * emitted at. Zero for a particle that appears exactly where it was asked for.
             *
             * <p>One definition describes one sprite, and some effects are several sprites
             * arranged around a centre: the doom-shroom's blast is a stem, seven pieces of cap
             * and a word, each of which the original places with its own {@code SystemPosition}.
             * Without a birth offset the only way to draw that shape is to emit each piece from
             * the capability at a hard-coded coordinate, which puts the art's layout in the
             * code that causes the explosion rather than in the definitions the art already
             * lives in. With it, an effect that is a composition is still just a list of
             * particle ids - see {@code pvzce:explosive}'s {@code particles}.
             *
             * <p>{@code +y} is up, like every other vertical in this project, and the sprite is
             * still centred on the offset point (the engine draws particles centred). A
             * {@code bounce} particle's ground line is measured from where it was born, so an
             * offset piece still lands under itself.
             */
            float offsetX,
            float offsetY
    ) {
        /**
         * What one bounce does to horizontal speed when a definition says nothing.
         *
         * <p>1 keeps every bit of it, which is what every definition written before this
         * field existed was authored against: the engine used to zero {@code vy} on landing
         * and leave {@code vx} completely alone, so a thrown head kept sliding sideways at
         * its launch speed for the rest of its life. That is a real behaviour some of those
         * definitions look fine with - a hat skittering along the lawn reads as a hat - so
         * the default preserves it and content opts into stopping by asking for less.
         *
         * <p>A thrown head that has to "drop where it fell" wants a small value: see
         * {@code data/pvzce/particles/zombie/zombie_head.json}.
         *
         * <p>Declared before {@link #STILL} because Java runs static initialisers in source
         * order and the constant below reads it.
         */
        public static final float DEFAULT_GROUND_FRICTION = 1F;

        /** A particle that never moves: the default when a definition omits {@code motion}. */
        /**
         * The ground line a {@code bounce} particle gets when it does not say.
         *
         * <p>0.42 cells below the spawn point: the distance from a cell's centre to the grass
         * a zombie stands on. A definition spawned at head height has to name a bigger one.
         */
        public static final float DEFAULT_GROUND_OFFSET = 0.42F;

        public static final ParticleMotion STILL =
                new ParticleMotion(0F, 0F, 90F, 0F, 0F, 0F, false, DEFAULT_GROUND_FRICTION,
                        DEFAULT_GROUND_OFFSET, 0F, 0F);

        public static final MapCodec<ParticleMotion> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.FLOAT.optionalFieldOf("speed", 0F).forGetter(ParticleMotion::speed),
                Codec.FLOAT.optionalFieldOf("speed_spread", 0F).forGetter(ParticleMotion::speedSpread),
                Codec.FLOAT.optionalFieldOf("angle", 90F).forGetter(ParticleMotion::angle),
                Codec.FLOAT.optionalFieldOf("angle_spread", 0F).forGetter(ParticleMotion::angleSpread),
                Codec.FLOAT.optionalFieldOf("gravity", 0F).forGetter(ParticleMotion::gravity),
                Codec.FLOAT.optionalFieldOf("drag", 0F).forGetter(ParticleMotion::drag),
                Codec.BOOL.optionalFieldOf("bounce", false).forGetter(ParticleMotion::bounce),
                Codec.floatRange(0F, 1F).optionalFieldOf("ground_friction", DEFAULT_GROUND_FRICTION)
                        .forGetter(ParticleMotion::groundFriction),
                Codec.FLOAT.optionalFieldOf("ground_offset", DEFAULT_GROUND_OFFSET)
                        .forGetter(ParticleMotion::groundOffset),
                Codec.FLOAT.optionalFieldOf("offset_x", 0F).forGetter(ParticleMotion::offsetX),
                Codec.FLOAT.optionalFieldOf("offset_y", 0F).forGetter(ParticleMotion::offsetY)
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

}
