package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

import java.util.List;

/** One boss phase trigger: at this HP fraction, summon and/or cast an ability. */
public record BossPhaseDef(float atHp, List<Identifier> summons, String ability) {
    public static final Codec<BossPhaseDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.FLOAT.fieldOf("at_hp").forGetter(BossPhaseDef::atHp),
            Identifier.CODEC.listOf().optionalFieldOf("summons", List.of()).forGetter(BossPhaseDef::summons),
            Codec.STRING.optionalFieldOf("ability", "").forGetter(BossPhaseDef::ability)
    ).apply(i, BossPhaseDef::new));
}
