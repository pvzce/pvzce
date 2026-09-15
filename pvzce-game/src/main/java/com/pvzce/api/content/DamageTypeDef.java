package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

/**
 * A registered damage type: what a hit of this kind does to a zombie's armour.
 *
 * <p>Loaded from {@code data/<ns>/damage_types/<name>.json}, like a level theme -
 * the entry is a name and one behavioural flag, and the name is what content
 * declares. Before this existed, "does armour absorb it" was encoded in <em>which
 * Java method the caller happened to invoke</em> ({@code damageBody} bypassed
 * armour, {@code damageImpact} did not, projectiles took a third route), so a
 * content author could not see or change the answer: it was a property of the call
 * site, and the ash line's "explosions ignore armour" was a comment rather than
 * data.
 *
 * <p>What a type does <b>not</b> carry is where it came from. {@code pvzce:impact}
 * is the bowling Wall-nut's hit and a Gargantuar's fist alike; the two still differ
 * in ways that belong to the caller (one is a projectile layer question, one aims
 * at a plant), and inventing a field per difference would put the caller's job back
 * inside the type.
 *
 * @param id           registry id, e.g. {@code pvzce:ash}
 * @param ignoresArmor {@code true} = the hit lands on the body whatever the zombie
 *                     is wearing (the ash line, a lawn mower); {@code false} = the
 *                     zombie's own armour capabilities get first refusal, which is
 *                     what makes a cone cost two peas instead of one
 */
public record DamageTypeDef(Identifier id, boolean ignoresArmor) {
    public static final Codec<DamageTypeDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(DamageTypeDef::id),
            Codec.BOOL.optionalFieldOf("ignores_armor", false).forGetter(DamageTypeDef::ignoresArmor)
    ).apply(i, DamageTypeDef::new));
}
