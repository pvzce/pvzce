package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

import java.util.List;
import java.util.Optional;

/**
 * A use-on-target tool (shovel / glove / hammer / ...). {@code effect} names
 * a registered tool behavior; {@code targets} are {@code cell}, {@code plant},
 * {@code zombie}, or {@code self}.
 *
 * <p>{@code texture} is the card sprite for when the tool's flat PNG does not sit
 * at the id-derived path - the same override every other entity definition has.
 * Tools carry no animation of their own, so {@code AnimationBindings} is absent;
 * a mod adding one can still declare it in the tool's own JSON through the plant
 * or projectile registry, which is where wearable art belongs.
 */
public record ToolDef(
        Identifier id,
        ResourceCost useCost,
        int cooldownTicks,
        List<String> targets,
        String effect,
        int uses,
        Optional<Identifier> texture
) {
    public static final Codec<ToolDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(ToolDef::id),
            ResourceCost.CODEC.optionalFieldOf("use_cost", ResourceCost.FREE).forGetter(ToolDef::useCost),
            Codec.INT.optionalFieldOf("cooldown", 60).forGetter(ToolDef::cooldownTicks),
            Codec.STRING.listOf().optionalFieldOf("targets", List.of("cell")).forGetter(ToolDef::targets),
            Codec.STRING.optionalFieldOf("effect", "pvzce:none").forGetter(ToolDef::effect),
            Codec.INT.optionalFieldOf("uses", -1).forGetter(ToolDef::uses),
            Identifier.CODEC.optionalFieldOf("texture").forGetter(ToolDef::texture)
    ).apply(i, ToolDef::new));

    /** The common case: a tool whose sprite follows the id convention. */
    public ToolDef(Identifier id, ResourceCost useCost, int cooldownTicks, List<String> targets,
                   String effect, int uses) {
        this(id, useCost, cooldownTicks, targets, effect, uses, Optional.empty());
    }
}
