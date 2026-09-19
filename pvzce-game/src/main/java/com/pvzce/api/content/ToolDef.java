package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;

import java.util.List;
import java.util.Optional;

/**
 * A use-on-target tool (shovel / glove / hammer / ...). {@code effect} names
 * a registered tool behavior; {@code targets} are {@code cell}, {@code plant},
 * {@code zombie}, or {@code self}.
 *
 * <p>{@code damage} is what one swing of a damaging tool is worth and {@code damage_type} is
 * what kind of hit it is (which armour rule applies) - the same pair a projectile carries, for
 * the same reason: "how hard" and "does armour absorb it" are two facts, and both belong to the
 * content rather than to the code that swings. Both are meaningless for a tool that damages
 * nothing (the shovel, the glove), which is why they have defaults rather than being required.
 *
 * <p>{@code texture} is the card sprite for when the tool's flat PNG does not sit
 * at the id-derived path - the same override every other entity definition has - and
 * {@code animations} is where a tool's own controller art is declared, exactly as it is for a
 * plant or a resource. The hammer has both: a card sprite for the bar and a two-clip animation
 * (held, and the swing) for the mallet the player aims with in Whack-a-Zombie.
 *
 * @param damage     what one swing is worth; see {@link #DEFAULT_DAMAGE}
 * @param damageType the registered damage type one swing lands as; defaults to
 *                   {@code pvzce:impact}, which is "armour absorbs it"
 * @param animations which animation file this tool's art lives in, and its state overrides;
 *                   empty for a tool that is only ever a card sprite, which is every tool
 *                   that shipped before the mallet's cursor became an animation
 */
public record ToolDef(
        Identifier id,
        ResourceCost useCost,
        int cooldownTicks,
        List<String> targets,
        String effect,
        int uses,
        Optional<Identifier> texture,
        int damage,
        /**
         * The registered damage type one swing lands as; {@link #DEFAULT_DAMAGE_TYPE} when
         * unwritten.
         *
         * <p>A plain id rather than an {@link Optional} because the default is a real value
         * rather than "absent": every damaging tool has a type, and the codec can say so.
         */
        Identifier damageType,
        /**
         * How far around the clicked point a swing reaches, in cells; 0 = the clicked cell.
         *
         * <p>A damaging tool is aimed at a <em>thing</em>, not at a square: the player points at
         * the zombie they mean, and asking them to also land inside that zombie's cell is a
         * second, invisible target. This is the radius that makes "point at it and it is hit"
         * true. Tools that act on a cell (the shovel, the glove) leave it at 0 and keep the cell
         * rule they always had.
         */
        float range,
        AnimationBindings animations
) {
    /**
     * What one swing is worth when the tool does not say: a normal zombie's health.
     *
     * <p>A normal zombie's health <em>only</em>. The hammer is not a lawn mower, and an armoured
     * zombie is meant to cost more than one swing; the number is the plainest true statement
     * about what a mallet does, and a content author who wants something else writes it.
     */
    public static final int DEFAULT_DAMAGE = 200;

    /** The common case for a damaging tool: a hit armour absorbs. */
    public static final Identifier DEFAULT_DAMAGE_TYPE = PvzceIds.DAMAGE_IMPACT;

    /**
     * How far a swing reaches when the tool does not say.
     *
     * <p>Just under a cell either way, so a click anywhere in the target's own cell or on the
     * line beside it connects, and a click on the far side of the next cell does not: the
     * player aims at a zombie, and a zombie is about a cell wide.
     */
    public static final float DEFAULT_RANGE = 0.9F;

    public static final Codec<ToolDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(ToolDef::id),
            ResourceCost.CODEC.optionalFieldOf("use_cost", ResourceCost.FREE).forGetter(ToolDef::useCost),
            Codec.INT.optionalFieldOf("cooldown", 60).forGetter(ToolDef::cooldownTicks),
            Codec.STRING.listOf().optionalFieldOf("targets", List.of("cell")).forGetter(ToolDef::targets),
            Codec.STRING.optionalFieldOf("effect", "pvzce:none").forGetter(ToolDef::effect),
            Codec.INT.optionalFieldOf("uses", -1).forGetter(ToolDef::uses),
            Identifier.CODEC.optionalFieldOf("texture").forGetter(ToolDef::texture),
            Codec.INT.optionalFieldOf("damage", DEFAULT_DAMAGE).forGetter(ToolDef::damage),
            Identifier.CODEC.optionalFieldOf("damage_type", DEFAULT_DAMAGE_TYPE)
                    .forGetter(ToolDef::damageType),
            Codec.FLOAT.optionalFieldOf("range", DEFAULT_RANGE).forGetter(ToolDef::range),
            AnimationBindings.MAP_CODEC.forGetter(ToolDef::animations)
    ).apply(i, ToolDef::new));

    /** The common case: a tool whose sprite follows the id convention. */
    public ToolDef(Identifier id, ResourceCost useCost, int cooldownTicks, List<String> targets,
                   String effect, int uses) {
        this(id, useCost, cooldownTicks, targets, effect, uses, Optional.empty(),
                DEFAULT_DAMAGE, DEFAULT_DAMAGE_TYPE, DEFAULT_RANGE, AnimationBindings.EMPTY);
    }

    /** As above, with an explicit card sprite. */
    public ToolDef(Identifier id, ResourceCost useCost, int cooldownTicks, List<String> targets,
                   String effect, int uses, Optional<Identifier> texture) {
        this(id, useCost, cooldownTicks, targets, effect, uses, texture,
                DEFAULT_DAMAGE, DEFAULT_DAMAGE_TYPE, DEFAULT_RANGE, AnimationBindings.EMPTY);
    }

    public ToolDef {
        damage = Math.max(0, damage);
        damageType = damageType == null ? DEFAULT_DAMAGE_TYPE : damageType;
        range = Math.max(0F, range);
        animations = animations == null ? AnimationBindings.EMPTY : animations;
    }
}
