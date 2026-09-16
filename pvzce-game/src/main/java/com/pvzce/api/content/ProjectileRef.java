package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

/**
 * A projectile emission from a plant definition.
 *
 * <p>{@code row_offset} and {@code backward} are what make the fancier shooters data
 * rather than code: the threepeater fires into the rows above and below its own, and
 * the split pea also fires behind itself. Neither was expressible before, so those
 * plants could not exist.
 *
 * <p>{@code rows} says how many rows either side are also checked for a target. The
 * threepeater should only fire while something is in one of its three lanes; a plain
 * shooter leaves it at 0 and looks at its own row alone.
 *
 * <p>{@code range} is how far the shot can travel, in cells, before it expires; 0 means
 * "as far as the board goes". It is one number for both halves of the same fact - when
 * the plant may fire and when the projectile dies - because a shot that outranges its
 * own plant's reach, or a plant that fires at something its spore can never touch, are
 * both bugs of the same kind. Puff-shroom is the plant this exists for.
 */
public record ProjectileRef(Identifier projectile, int damage, int count,
                            int rowOffset, boolean backward, int rows, float range) {
    /** The original's straight shot: this row, forwards, no range limit. */
    public static final float UNLIMITED_RANGE = 0F;

    public static final Codec<ProjectileRef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("projectile").forGetter(ProjectileRef::projectile),
            Codec.INT.optionalFieldOf("damage", 20).forGetter(ProjectileRef::damage),
            Codec.INT.optionalFieldOf("count", 1).forGetter(ProjectileRef::count),
            // Which row, relative to the plant's, the shot appears in.
            Codec.INT.optionalFieldOf("row_offset", 0).forGetter(ProjectileRef::rowOffset),
            // Fires away from the zombies instead of toward them (split pea).
            Codec.BOOL.optionalFieldOf("backward", false).forGetter(ProjectileRef::backward),
            // How many rows either side to look for targets in.
            Codec.INT.optionalFieldOf("rows", 0).forGetter(ProjectileRef::rows),
            // How far the shot flies before it expires; 0 = the whole board.
            Codec.FLOAT.optionalFieldOf("range", UNLIMITED_RANGE).forGetter(ProjectileRef::range)
    ).apply(i, ProjectileRef::new));

    public ProjectileRef {
        range = Math.max(0F, range);
    }

    /** The plain straight shot: this row, forwards, one projectile. */
    public ProjectileRef(Identifier projectile, int damage, int count) {
        this(projectile, damage, count, 0, false, 0, UNLIMITED_RANGE);
    }

    /** A row-covering shot with no range limit (threepeater, split pea). */
    public ProjectileRef(Identifier projectile, int damage, int count,
                         int rowOffset, boolean backward, int rows) {
        this(projectile, damage, count, rowOffset, backward, rows, UNLIMITED_RANGE);
    }

    /** {@code +1} down the lawn toward the zombies, {@code -1} back toward the house. */
    public float direction() {
        return backward ? -1F : 1F;
    }

    /** True when this shot has no range limit and therefore covers the whole board. */
    public boolean hasUnlimitedRange() {
        return range <= 0F;
    }

    /**
     * Whether a zombie at {@code targetX} is a target for a shot fired from {@code originX}.
     *
     * <p>Used for the "is there anything worth firing at" question, and it is the same
     * predicate as the projectile's own reach: in front of the muzzle when firing
     * forwards, behind it when firing backwards, and within {@link #range} either way.
     * Zombies past the range are ignored rather than shot at, which is what makes a
     * short-ranged plant short-ranged instead of merely inaccurate.
     */
    public boolean covers(float originX, float targetX) {
        float delta = (targetX - originX) * direction();
        if (delta <= 0F) {
            return false;
        }
        return hasUnlimitedRange() || delta <= range;
    }

    /**
     * The row offsets this shot's target search covers, plant row first.
     *
     * <p>Only used to decide whether there is anything worth firing at; the
     * projectiles themselves are placed by {@link #rowOffset()}.
     */
    public int[] coveredRowOffsets() {
        int span = Math.max(0, rows);
        int[] offsets = new int[span * 2 + 1];
        for (int i = -span; i <= span; i++) {
            offsets[i + span] = i;
        }
        return offsets;
    }
}
