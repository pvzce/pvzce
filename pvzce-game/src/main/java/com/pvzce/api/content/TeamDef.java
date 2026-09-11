package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

public record TeamDef(Identifier id, String name, String winCondition) {
    public static final Codec<TeamDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(TeamDef::id),
            Codec.STRING.optionalFieldOf("name", "").forGetter(TeamDef::name),
            Codec.STRING.optionalFieldOf("win_condition", "survive_waves").forGetter(TeamDef::winCondition)
    ).apply(i, TeamDef::new));
}
