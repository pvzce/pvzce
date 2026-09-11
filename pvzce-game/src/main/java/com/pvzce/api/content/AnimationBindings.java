package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

import java.util.Map;
import java.util.Optional;

/**
 * Optional per-entity animation resource overrides.
 *
 * <p>{@code animation} replaces the entity's whole animation file; {@code
 * animations} overrides individual logical states (walk, shoot, ...). Values
 * are resource identifiers relative to {@code assets/<namespace>/animations/}
 * and omit the {@code .json} suffix.</p>
 */
public record AnimationBindings(Optional<Identifier> animation, Map<String, Identifier> animations) {
    public static final AnimationBindings EMPTY = new AnimationBindings(Optional.empty(), Map.of());

    public static final Codec<AnimationBindings> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.optionalFieldOf("animation").forGetter(AnimationBindings::animation),
            Codec.unboundedMap(Codec.STRING, Identifier.CODEC)
                    .optionalFieldOf("animations", Map.of()).forGetter(AnimationBindings::animations)
    ).apply(i, AnimationBindings::new));

    /**
     * Flat {@link com.mojang.serialization.MapCodec} form: {@code animation} and
     * {@code animations} are read at the <em>parent</em> level, which is how every
     * entity definition embeds them. Entity definitions must use this instance
     * instead of re-declaring the two fields, so the JSON shape has one owner
     * (previously the same pair was inlined in three different def codecs while
     * this record's codec sat unused).
     */
    public static final com.mojang.serialization.MapCodec<AnimationBindings> MAP_CODEC =
            RecordCodecBuilder.mapCodec(i -> i.group(
                    Identifier.CODEC.optionalFieldOf("animation").forGetter(AnimationBindings::animation),
                    Codec.unboundedMap(Codec.STRING, Identifier.CODEC)
                            .optionalFieldOf("animations", Map.of()).forGetter(AnimationBindings::animations)
            ).apply(i, AnimationBindings::new));

    public AnimationBindings {
        animations = Map.copyOf(animations);
    }

    /** State override first, then whole-entity file override. */
    public Optional<Identifier> resolve(String state) {
        Identifier stateOverride = animations.get(state);
        if (stateOverride != null) {
            return Optional.of(stateOverride);
        }
        return animation;
    }

    public boolean isEmpty() {
        return animation.isEmpty() && animations.isEmpty();
    }
}
