package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

import java.util.List;

/**
 * A use-on-target tool (shovel / glove / hammer / ...). {@code effect} names
 * a registered tool behavior; {@code targets} are {@code cell}, {@code plant},
 * {@code zombie}, or {@code self}.
 */
public record ToolDef(
        Identifier id,
        ResourceCost useCost,
        int cooldownTicks,
        List<String> targets,
        String effect,
        int uses
) {
    public static final Codec<ToolDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(ToolDef::id),
            ResourceCost.CODEC.optionalFieldOf("use_cost", ResourceCost.FREE).forGetter(ToolDef::useCost),
            Codec.INT.optionalFieldOf("cooldown", 60).forGetter(ToolDef::cooldownTicks),
            Codec.STRING.listOf().optionalFieldOf("targets", List.of("cell")).forGetter(ToolDef::targets),
            Codec.STRING.optionalFieldOf("effect", "pvzce:none").forGetter(ToolDef::effect),
            Codec.INT.optionalFieldOf("uses", -1).forGetter(ToolDef::uses)
    ).apply(i, ToolDef::new));
}
