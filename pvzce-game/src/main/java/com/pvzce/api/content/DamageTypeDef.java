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
 * @param burns        {@code true} = a zombie this kills leaves a charred body, which is what
 *                     the original's ash line does and what the {@code death_burned} clip is
 *                     for. A flag on the type rather than a list of ids in the death code:
 *                     "does this hit burn?" is a property of the hit, so a mod's own fire
 *                     damage can answer it without a code change
 * @param ignoresFrontArmor {@code true} = a piece held <em>in front</em> of the zombie (a screen
 *                     door, a newspaper) does not stop this hit, while what it wears on its head
 *                     still does. The fume-shroom's spray is the case: the original's gas goes
 *                     through a screen door and is still absorbed by a cone or a football helmet,
 *                     which a single "armour" flag cannot say - the two are one boolean away from
 *                     each other, and the difference is the whole reason the fume-shroom is the
 *                     answer to a Screen Door Zombie and not to a Conehead.
 */
public record DamageTypeDef(Identifier id, boolean ignoresArmor, boolean burns, boolean dismembers,
                            boolean ignoresFrontArmor) {
    public static final Codec<DamageTypeDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(DamageTypeDef::id),
            Codec.BOOL.optionalFieldOf("ignores_armor", false).forGetter(DamageTypeDef::ignoresArmor),
            // Written by the ash line; unwritten means "this hit leaves a body like any other",
            // which is what every type did before the field existed.
            Codec.BOOL.optionalFieldOf("burns", false).forGetter(DamageTypeDef::burns),
            // A hit that takes the head and arm off *itself* (the lawn mower throws both as
            // it goes over), so the death must not throw a second pair.
            Codec.BOOL.optionalFieldOf("dismembers", false).forGetter(DamageTypeDef::dismembers),
            // Written by the fume-shroom's spray; unwritten means "a shield is a shield", which
            // is what every type meant before the field existed.
            Codec.BOOL.optionalFieldOf("ignores_front_armor", false)
                    .forGetter(DamageTypeDef::ignoresFrontArmor)
    ).apply(i, DamageTypeDef::new));

    /** A plain hit: armour stops it, it leaves no charred body, it throws nothing of its own. */
    public DamageTypeDef(Identifier id, boolean ignoresArmor) {
        this(id, ignoresArmor, false, false, false);
    }

    /** A hit that burns but does not throw anything off by itself. */
    public DamageTypeDef(Identifier id, boolean ignoresArmor, boolean burns) {
        this(id, ignoresArmor, burns, false, false);
    }

    /** A hit that also takes a scrap of armour with it. */
    public DamageTypeDef(Identifier id, boolean ignoresArmor, boolean burns, boolean dismembers) {
        this(id, ignoresArmor, burns, dismembers, false);
    }
}
