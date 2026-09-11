package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.content.capability.TypedCapability;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.capability.PlantBehaviorPresets;
import com.pvzce.common.capability.PlantCapabilities;

import java.util.List;
import java.util.Optional;

/**
 * Data-driven plant definition.
 *
 * <p>Behaviour is a list of composable {@link PlantCapability capabilities}
 * ({@code "capabilities": [{"type": "pvzce:shooter", "interval": 90, ...}]}). A
 * plant may combine several (a shooter that also produces sun) and mods add new
 * capability types by registering them, so this record never needs a new field
 * when a plant gains a behaviour.
 *
 * <p>{@code behavior} remains as an optional shorthand preset (see
 * {@code PlantBehaviorPresets}); an explicit {@code capabilities} array always
 * wins. Statistics that used to live here - attack interval, shots, butter
 * chance, blast radius, chew time, produce schedule - moved into the capability
 * that owns them, so each number now has exactly one home.
 */
public record PlantDef(
        Identifier id,
        ResourceCost cost,
        int health,
        PlacementDef placement,
        List<TypedCapability<PlantCapability>> capabilities,
        Optional<Identifier> behavior,
        PlantSounds sounds,
        AnimationBindings animations
) {
    public static final int DEFAULT_HEALTH = 300;

    public static final Codec<PlantDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(PlantDef::id),
            ResourceCost.CODEC.optionalFieldOf("cost", ResourceCost.defaultPlantCost()).forGetter(PlantDef::cost),
            Codec.INT.optionalFieldOf("health", DEFAULT_HEALTH).forGetter(PlantDef::health),
            PlacementDef.CODEC.optionalFieldOf("placement", PlacementDef.PLANTABLE).forGetter(PlantDef::placement),
            PlantCapabilities.LIST_CODEC.optionalFieldOf("capabilities", List.of()).forGetter(PlantDef::capabilities),
            Identifier.CODEC.optionalFieldOf("behavior").forGetter(PlantDef::behavior),
            PlantSounds.CODEC.optionalFieldOf("sounds", PlantSounds.EMPTY).forGetter(PlantDef::sounds),
            AnimationBindings.MAP_CODEC.forGetter(PlantDef::animations)
    ).apply(i, PlantDef::new));

    public PlantDef {
        capabilities = List.copyOf(capabilities);
    }

    /**
     * The effective capability list: the explicit array when present, otherwise
     * the {@code behavior} preset. Callers must use this instead of
     * {@link #capabilities()} so the two sources can never diverge.
     */
    public List<TypedCapability<PlantCapability>> resolvedCapabilities() {
        return capabilities.isEmpty() ? PlantBehaviorPresets.expand(behavior.orElse(null)) : capabilities;
    }

    /** First capability of the given implementation type, if the plant has one. */
    public <T extends PlantCapability> Optional<T> capability(Class<T> type) {
        for (TypedCapability<PlantCapability> entry : resolvedCapabilities()) {
            if (type.isInstance(entry.value())) {
                return Optional.of(type.cast(entry.value()));
            }
        }
        return Optional.empty();
    }

    /** Optional per-plant sound event overrides; empty fields fall back to capability defaults. */
    public record PlantSounds(
            Optional<Identifier> place,
            Optional<Identifier> shoot,
            Optional<Identifier> explode,
            Optional<Identifier> produce,
            Optional<Identifier> melee
    ) {
        public static final PlantSounds EMPTY = new PlantSounds(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        public static final MapCodec<PlantSounds> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Identifier.CODEC.optionalFieldOf("place").forGetter(PlantSounds::place),
                Identifier.CODEC.optionalFieldOf("shoot").forGetter(PlantSounds::shoot),
                Identifier.CODEC.optionalFieldOf("explode").forGetter(PlantSounds::explode),
                Identifier.CODEC.optionalFieldOf("produce").forGetter(PlantSounds::produce),
                Identifier.CODEC.optionalFieldOf("melee").forGetter(PlantSounds::melee)
        ).apply(i, PlantSounds::new));

        public static final Codec<PlantSounds> CODEC = MAP_CODEC.codec();
    }
}
