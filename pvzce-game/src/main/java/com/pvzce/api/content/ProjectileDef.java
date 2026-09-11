package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.ProjectileCapability;
import com.pvzce.api.content.capability.TypedCapability;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.capability.ProjectileCapabilities;

import java.util.List;
import java.util.Optional;

/**
 * Data-driven projectile: motion, impact and status application are all
 * capabilities, so a new projectile shape is data plus (at most) one capability
 * type - not another branch in {@code ProjectileEntity.tick}.
 *
 * <p>The old {@code behavior} (linear/arc) and {@code impact} ("splash") fields
 * were two halves of the same decision split across two string fields; both are
 * now the capabilities that actually implement them.
 */
public record ProjectileDef(
        Identifier id,
        String layer,
        List<TypedCapability<ProjectileCapability>> capabilities,
        Optional<Identifier> behavior,
        ProjectileSounds sounds,
        AnimationBindings animations
) {
    /** Ground-layer shots are blocked by flying/underground zombies. */
    public static final String LAYER_GROUND = "ground";
    public static final String LAYER_AIR = "air";

    public static final Codec<ProjectileDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(ProjectileDef::id),
            Codec.STRING.optionalFieldOf("layer", LAYER_GROUND).forGetter(ProjectileDef::layer),
            ProjectileCapabilities.LIST_CODEC.optionalFieldOf("capabilities", List.of())
                    .forGetter(ProjectileDef::capabilities),
            Identifier.CODEC.optionalFieldOf("behavior").forGetter(ProjectileDef::behavior),
            ProjectileSounds.CODEC.optionalFieldOf("sounds", ProjectileSounds.EMPTY).forGetter(ProjectileDef::sounds),
            AnimationBindings.MAP_CODEC.forGetter(ProjectileDef::animations)
    ).apply(i, ProjectileDef::new));

    public ProjectileDef {
        capabilities = List.copyOf(capabilities);
    }

    public boolean isAirLayer() {
        return LAYER_AIR.equals(layer);
    }

    /** Explicit capabilities when present, otherwise the {@code behavior} preset. */
    public List<TypedCapability<ProjectileCapability>> resolvedCapabilities() {
        return capabilities.isEmpty()
                ? ProjectileBehaviorPresets.expand(behavior.orElse(null))
                : capabilities;
    }

    /** First capability of the given implementation type, if present. */
    public <T extends ProjectileCapability> Optional<T> capability(Class<T> type) {
        for (TypedCapability<ProjectileCapability> entry : resolvedCapabilities()) {
            if (type.isInstance(entry.value())) {
                return Optional.of(type.cast(entry.value()));
            }
        }
        return Optional.empty();
    }

    /** Optional per-projectile sound event overrides. */
    public record ProjectileSounds(Optional<Identifier> impact) {
        public static final ProjectileSounds EMPTY = new ProjectileSounds(Optional.empty());

        public static final MapCodec<ProjectileSounds> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Identifier.CODEC.optionalFieldOf("impact").forGetter(ProjectileSounds::impact)
        ).apply(i, ProjectileSounds::new));

        public static final Codec<ProjectileSounds> CODEC = MAP_CODEC.codec();
    }
}
