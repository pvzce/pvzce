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
 *
 * <p>{@code animation_dir} is the directory the entity's own file lives in, for
 * when the animation is grouped by kind instead of mirroring the content id; see
 * {@link AnimationSource}. It is the third field here rather than a sibling in
 * every definition codec because all five entity definition kinds embed this
 * same flat map codec.</p>
 */
public record AnimationBindings(Optional<Identifier> animation, Map<String, Identifier> animations,
                                Optional<String> animationDir) {
    public static final AnimationBindings EMPTY =
            new AnimationBindings(Optional.empty(), Map.of(), Optional.empty());

    public static final Codec<AnimationBindings> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.optionalFieldOf("animation").forGetter(AnimationBindings::animation),
            Codec.unboundedMap(Codec.STRING, Identifier.CODEC)
                    .optionalFieldOf("animations", Map.of()).forGetter(AnimationBindings::animations),
            AnimationSource.CODEC.optionalFieldOf("animation_dir").forGetter(AnimationBindings::animationDir)
    ).apply(i, AnimationBindings::new));

    /**
     * Flat {@link com.mojang.serialization.MapCodec} form: {@code animation},
     * {@code animations} and {@code animation_dir} are read at the <em>parent</em>
     * level, which is how every entity definition embeds them. Entity definitions
     * must use this instance instead of re-declaring the fields, so the JSON shape
     * has one owner (previously the same pair was inlined in three different def
     * codecs while this record's codec sat unused).
     */
    public static final com.mojang.serialization.MapCodec<AnimationBindings> MAP_CODEC =
            RecordCodecBuilder.mapCodec(i -> i.group(
                    Identifier.CODEC.optionalFieldOf("animation").forGetter(AnimationBindings::animation),
                    Codec.unboundedMap(Codec.STRING, Identifier.CODEC)
                            .optionalFieldOf("animations", Map.of()).forGetter(AnimationBindings::animations),
                    AnimationSource.CODEC.optionalFieldOf("animation_dir").forGetter(AnimationBindings::animationDir)
            ).apply(i, AnimationBindings::new));

    public AnimationBindings {
        animations = Map.copyOf(animations);
        animationDir = animationDir.map(AnimationSource::normalize);
    }

    /** State override first, then whole-entity file override. */
    public Optional<Identifier> resolve(String state) {
        Identifier stateOverride = animations.get(state);
        if (stateOverride != null) {
            return Optional.of(stateOverride);
        }
        return animation;
    }

    /**
     * The id of this entity's own animation file.
     *
     * <p>With {@code animation_dir} this is {@code <ns>:<dir>/<leaf>}; without it,
     * the id itself, which is the {@code assets/<ns>/animations/<id.path()>.json}
     * convention a mod gets for free.
     */
    public Identifier fileId(Identifier defId) {
        return AnimationSource.fileId(defId, animationDir.orElse(null));
    }

    public boolean isEmpty() {
        return animation.isEmpty() && animations.isEmpty();
    }
}
