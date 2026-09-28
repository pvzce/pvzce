package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;

import java.util.ArrayList;
import java.util.List;

/**
 * How much of the board a level's fog covers.
 *
 * <p>The original's world 4 is a lawn you cannot see all of: the right-hand side is dark, and a
 * zombie is only drawn once it has walked far enough left to be inside the lit part. This record
 * is the block of the {@code pvzce:fog} level mechanic, and it says three things: where the
 * darkness starts, where it becomes total, and how dark "total" is.
 *
 * <p>Measured in <b>columns</b>, fractional, not pixels: the fog belongs to the board, so a level
 * that widens its lawn does not also have to re-tune its fog. {@code start_column} is the first
 * column the player can no longer see clearly and {@code end_column} is where it is as dark as
 * {@code max_alpha} allows, so the visible half of a level is everything left of
 * {@code start_column}.
 *
 * <h2>Why a mechanic and not a rule</h2>
 *
 * <p>The same reason the conveyor belt and the plantable area are mechanics: a rule is a number
 * with a name, while this carries a shape (a span, a floor and a ceiling on its opacity) and is
 * read by a renderer rather than by the simulation. It is also the thing a <em>mutation</em> wants
 * to install on a lawn that never had it, which is exactly what a mechanic is for - see
 * {@code common.level.mutation.FogRollInMutation}.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>Nothing here touches a rule, a hit test or a spawn. The fog is a fact about what the player
 * can <em>see</em>; a plant behind it shoots exactly as far and a zombie behind it walks exactly
 * as fast. That is the reading the player asked for ("视觉遮挡"), and it is also what keeps the
 * whole feature inside the render layer - there is no server state to keep in step, beyond the
 * one packet that says which fog this level has.
 *
 * @param startColumn the first column that is no longer clear; 0 means the fog starts at the left
 *                    edge
 * @param endColumn   the column at which the fog reaches {@code maxAlpha}
 * @param maxAlpha    how opaque the far end is, 0..1. Below 1 it is a haze rather than a curtain
 */
public record FogData(float startColumn, float endColumn, float maxAlpha) implements MechanicData {
    /** The default span: the right two columns fade out, fully dark just past the last one. */
    public static final float DEFAULT_START_COLUMN = 6F;
    public static final float DEFAULT_END_COLUMN = 9F;
    /**
     * How opaque the deep end is by default.
     *
     * <p>Not 1.0. A fully opaque fog is a wall the player cannot see through at all, which reads
     * as a rendering fault rather than as weather - and the original's own deepest fog is a
     * near-black that still shows a silhouette. 0.94 is "you cannot make out what it is" without
     * being "the screen ends here".
     */
    public static final float DEFAULT_MAX_ALPHA = 0.94F;

    public static final MapCodec<FogData> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.optionalFieldOf("start_column", DEFAULT_START_COLUMN)
                    .forGetter(FogData::startColumn),
            Codec.FLOAT.optionalFieldOf("end_column", DEFAULT_END_COLUMN)
                    .forGetter(FogData::endColumn),
            Codec.FLOAT.optionalFieldOf("max_alpha", DEFAULT_MAX_ALPHA)
                    .forGetter(FogData::maxAlpha)
    ).apply(i, FogData::new));

    public static final Codec<FogData> CODEC = MAP_CODEC.codec();

    public FogData {
        // A fog that ends before it starts is a level author's typo; folding it to an empty span
        // at the start point is the reading that draws nothing rather than one that draws garbage
        // across the whole board.
        endColumn = Math.max(startColumn, endColumn);
        maxAlpha = Math.max(0F, Math.min(1F, maxAlpha));
        startColumn = Math.max(0F, startColumn);
    }

    /** The same fog pushed {@code columns} to the right - how the "fog retreat" buff reads. */
    public FogData retreatedBy(float columns) {
        if (columns <= 0F) {
            return this;
        }
        return new FogData(startColumn + columns, endColumn + columns, maxAlpha);
    }

    /**
     * How opaque the fog is at a column, 0 where it is clear and {@link #maxAlpha} at the far end.
     *
     * <p>The one definition of "how dark is it here", and the answer the hiding test reads: an
     * entity past {@link #hidingColumn()} is not drawn at all.
     *
     * <p>Linear in the span and raised to a power of its own: a straight alpha ramp reads as a hard
     * edge where it tops out, because the eye is far more sensitive near full black than near
     * nothing. Note what this is <em>not</em>: the shape the fog is drawn with. The cloud is a grid
     * of sprites whose own alpha is the picture ({@code FogClientMechanic}), and this curve is what
     * decides which cells are fogged and where something stops being drawn.
     */
    public float alphaAt(float column) {
        if (column <= startColumn) {
            return 0F;
        }
        if (column >= endColumn) {
            return maxAlpha;
        }
        float t = (column - startColumn) / Math.max(0.0001F, endColumn - startColumn);
        return maxAlpha * (float) Math.pow(t, FALLOFF);
    }

    /**
     * The exposure curve: how fast the ramp climbs toward {@link #maxAlpha}.
     *
     * <p>Live data rather than a texture's business. It used to be baked into a gradient sprite the
     * renderer drew (and a test compared the two pixel by pixel); the fog is drawn as cloud tiles
     * now, so this curve has one home and one reader - {@link #alphaAt}.
     */
    public static final float FALLOFF = 1.6F;

    /**
     * The column past which the player can no longer tell what is standing there.
     *
     * <p>What "a zombie is only drawn once it walks into view" means in practice: an entity whose
     * column is beyond this is not drawn at all. A separate number from {@link #endColumn} because
     * the two answer different questions - "how dark is it" against "can this be made out" - and
     * tying them together would mean a level that lightened its fog also silently changed what
     * could be seen through it.
     *
     * <p>Derived rather than declared: the point where the ramp is three quarters of the way to
     * full. A level that wants a different boundary moves {@code startColumn}.
     */
    public float hidingColumn() {
        float full = maxAlpha;
        if (full <= 0.0001F) {
            return Float.MAX_VALUE;
        }
        float target = full * HIDE_FRACTION;
        if (target >= full) {
            return endColumn;
        }
        float t = (float) Math.pow(target / full, 1F / FALLOFF);
        return startColumn + t * (endColumn - startColumn);
    }

    /** How far into the ramp counts as "cannot be made out"; see {@link #hidingColumn()}. */
    public static final float HIDE_FRACTION = 0.75F;

    /** One message per problem, for {@code LevelValidator}. */
    public List<String> validate(int width) {
        List<String> errors = new ArrayList<>();
        if (startColumn < 0F || endColumn < 0F) {
            errors.add("fog has a negative column (start_column=" + startColumn
                    + ", end_column=" + endColumn + ")");
        }
        if (startColumn >= width) {
            errors.add("fog starts at column " + startColumn + " on a " + width
                    + "-column board: nothing would ever be hidden");
        }
        if (maxAlpha <= 0F && endColumn > startColumn) {
            errors.add("fog has a span but max_alpha 0: it would draw nothing");
        }
        return errors;
    }
}
