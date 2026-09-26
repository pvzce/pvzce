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
 *
 * <p>{@code burst_delay} is how many ticks apart the {@code count} projectiles of one volley
 * leave the muzzle. It is what makes a repeater a repeater: born on the same tick at the same
 * point, its two peas are perfectly coincident - the second one draws nothing, lands on the
 * same zombie in the same tick, and the plant reads as a peashooter that happens to deal
 * double damage. The original staggers them, and now so does this: {@code count 2,
 * burst_delay 12} is the repeater, {@code count 4, burst_delay 12} the gatling pea. Zero -
 * the default, and what every single-shot plant means - keeps the volley on the firing tick.
 *
 * <p>{@code initial_delay} is the other half of that idea: a volley that does not leave on the
 * firing tick at all. It exists so one plant's several *entries* can be a sequence rather than a
 * simultaneous spread, and {@code burst_delay} still spaces the shots <em>within</em> an entry.
 * Nothing shipped uses it today - the threepeater, which it was written for, fires all three
 * heads at once by request (see {@code plants/threepeater.json}) - but it stays on the codec
 * because it is the only way a content author can state "this head is late", and removing the
 * field would not remove the code that reads it.
 */
public record ProjectileRef(Identifier projectile, int damage, int count,
                            int rowOffset, boolean backward, int rows, float range,
                            int burstDelay, int initialDelay) {
    /** The original's straight shot: this row, forwards, no range limit. */
    public static final float UNLIMITED_RANGE = 0F;

    /** A volley whose projectiles all leave together, which is every shot but a repeater's. */
    public static final int NO_BURST_DELAY = 0;

    /** A shot that leaves on the firing tick, which is every shipped shot. */
    public static final int NO_INITIAL_DELAY = 0;

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
            Codec.FLOAT.optionalFieldOf("range", UNLIMITED_RANGE).forGetter(ProjectileRef::range),
            // Ticks between the projectiles of one volley; 0 = all on the firing tick.
            Codec.INT.optionalFieldOf("burst_delay", NO_BURST_DELAY)
                    .forGetter(ProjectileRef::burstDelay),
            // Ticks to wait before this entry leaves at all; 0 = on the firing tick.
            Codec.INT.optionalFieldOf("initial_delay", NO_INITIAL_DELAY)
                    .forGetter(ProjectileRef::initialDelay)
    ).apply(i, ProjectileRef::new));

    public ProjectileRef {
        range = Math.max(0F, range);
        burstDelay = Math.max(0, burstDelay);
        initialDelay = Math.max(0, initialDelay);
    }

    /** The plain straight shot: this row, forwards, one projectile. */
    public ProjectileRef(Identifier projectile, int damage, int count) {
        this(projectile, damage, count, 0, false, 0, UNLIMITED_RANGE, NO_BURST_DELAY,
                NO_INITIAL_DELAY);
    }

    /** A row-covering shot with no range limit (threepeater, split pea). */
    public ProjectileRef(Identifier projectile, int damage, int count,
                         int rowOffset, boolean backward, int rows) {
        this(projectile, damage, count, rowOffset, backward, rows, UNLIMITED_RANGE,
                NO_BURST_DELAY, NO_INITIAL_DELAY);
    }

    /** A ranged shot whose volley leaves together - every shot written before bursts existed. */
    public ProjectileRef(Identifier projectile, int damage, int count,
                         int rowOffset, boolean backward, int rows, float range) {
        this(projectile, damage, count, rowOffset, backward, rows, range, NO_BURST_DELAY,
                NO_INITIAL_DELAY);
    }

    /**
     * A shot with a burst but no initial delay - every shot written before the threepeater's
     * stagger existed.
     */
    public ProjectileRef(Identifier projectile, int damage, int count,
                         int rowOffset, boolean backward, int rows, float range, int burstDelay) {
        this(projectile, damage, count, rowOffset, backward, rows, range, burstDelay,
                NO_INITIAL_DELAY);
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
     * The same shot with its range multiplied - how a level buff makes a mushroom reach further.
     *
     * <p>An unlimited range stays unlimited: "the whole board" times anything is still the whole
     * board. A multiplier of 1 returns {@code this} unchanged, so the overwhelmingly common case
     * allocates nothing - which is why the caller compares against 1 rather than always copying.
     */
    public ProjectileRef scaledRange(float multiplier) {
        if (multiplier == 1F || hasUnlimitedRange()) {
            return this;
        }
        return new ProjectileRef(projectile, damage, count, rowOffset, backward, rows,
                Math.max(0F, range * multiplier), burstDelay, initialDelay);
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
