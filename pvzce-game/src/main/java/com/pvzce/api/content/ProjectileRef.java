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
 */
public record ProjectileRef(Identifier projectile, int damage, int count,
                            int rowOffset, boolean backward, int rows) {
    public static final Codec<ProjectileRef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("projectile").forGetter(ProjectileRef::projectile),
            Codec.INT.optionalFieldOf("damage", 20).forGetter(ProjectileRef::damage),
            Codec.INT.optionalFieldOf("count", 1).forGetter(ProjectileRef::count),
            // Which row, relative to the plant's, the shot appears in.
            Codec.INT.optionalFieldOf("row_offset", 0).forGetter(ProjectileRef::rowOffset),
            // Fires away from the zombies instead of toward them (split pea).
            Codec.BOOL.optionalFieldOf("backward", false).forGetter(ProjectileRef::backward),
            // How many rows either side to look for targets in.
            Codec.INT.optionalFieldOf("rows", 0).forGetter(ProjectileRef::rows)
    ).apply(i, ProjectileRef::new));

    /** The plain straight shot: this row, forwards, one projectile. */
    public ProjectileRef(Identifier projectile, int damage, int count) {
        this(projectile, damage, count, 0, false, 0);
    }

    /** {@code +1} down the lawn toward the zombies, {@code -1} back toward the house. */
    public float direction() {
        return backward ? -1F : 1F;
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
