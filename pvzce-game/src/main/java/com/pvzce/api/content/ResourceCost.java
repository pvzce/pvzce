package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;

import java.util.Map;

/** Cost of a card/action. {@code resources} maps resource registry ids to amounts. */
public record ResourceCost(Map<Identifier, Integer> resources, int cooldownTicks) {
    public static final Codec<ResourceCost> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Identifier.CODEC, Codec.INT).fieldOf("resources").forGetter(ResourceCost::resources),
            Codec.INT.optionalFieldOf("cooldown", PvzceConstants.PLANT_CARD_COOLDOWN_TICKS)
                    .forGetter(ResourceCost::cooldownTicks)
    ).apply(i, ResourceCost::new));

    public static final ResourceCost FREE = new ResourceCost(Map.of(), 0);

    /**
     * The standard card cost for a plant that does not declare one: no resources
     * and the default card cooldown. Single owner of those two numbers, so the
     * codec default, the built-in definitions and the editor cannot drift.
     */
    public static ResourceCost defaultPlantCost() {
        return new ResourceCost(Map.of(), PvzceConstants.PLANT_CARD_COOLDOWN_TICKS);
    }

    public int amountOf(Identifier resource) {
        return resources.getOrDefault(resource, 0);
    }
}
