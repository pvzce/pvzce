package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;

import java.util.ArrayList;
import java.util.List;

/**
 * A thunderstorm: the board is black, and lightning keeps lighting it up again.
 *
 * <p>This record is the block of the {@code pvzce:storm} level mechanic. It says how often the
 * storm flashes and how dark it gets in between, and nothing else - the ramp from "lit" back to
 * "black" is a curve rather than a knob, and it lives in {@link StormState} beside the two
 * functions that read it, so the drawing and the hiding test cannot disagree about it.
 *
 * <h2>Why a mechanic and not a rule</h2>
 *
 * <p>The same reason the fog is one: a rule is a number with a name, while this is read by a
 * renderer and never by the simulation. Nothing about a storm touches a hit test or a spawn; a
 * zombie walks and is shot at exactly as it would be on a sunny lawn, and only the picture
 * changes. That is also what keeps the whole feature out of the save file beyond one counter.
 *
 * <h2>Where it came from</h2>
 *
 * <p>The original has exactly one level like this - 4-10, the fog world's finale, where the fog
 * of the previous nine levels breaks into a storm: the lawn is pitch black and a lightning
 * flash is the only way to see where the zombies are (which is why the level is also the one
 * level in the game with no background music - the rain is the soundtrack). This mechanic is
 * that level's, and a mod may put it on any board it likes.
 *
 * @param intervalTicks how long one whole cycle lasts, the strike included
 * @param flashTicks    how long a strike lasts, from the start of a cycle and flicker included.
 *                      The lightning's own shape - see {@code StormState.PATTERNS} - is drawn
 *                      across this span, so the number is "how long the storm has the board"
 *                      rather than "how many ticks of full light there are"
 * @param maxAlpha      how dark it gets between strikes, 0..1. Below 1 the lawn is a dim night
 *                      rather than a blackout; 1 is "you cannot see the board at all"
 */
public record StormData(int intervalTicks, int flashTicks, float maxAlpha) implements MechanicData {
    /**
     * The gap between strikes, from the original's own feel.
     *
     * <p>Five seconds: long enough that the dark is the level's normal state rather than a
     * strobe, short enough that a player is never left blind for long. It is the number a level
     * tunes - a shorter interval is a harder level, not a brighter one, because the strike is
     * also when the zombies can be seen.
     */
    public static final int DEFAULT_INTERVAL_TICKS = 300;
    /**
     * How long a strike lasts, flicker and all.
     *
     * <p>1.6 seconds, and it is the whole span the lightning shape is drawn across - the shape
     * itself ({@code StormState.PATTERNS}) is what decides how much of that span is actually
     * bright. A strike that was a quarter of a second of light was reported as too short to use:
     * the player has to be able to look around, count the lanes and put something down, and that
     * is a second and a half of on-and-off light rather than one instant of it.
     */
    public static final int DEFAULT_FLASH_TICKS = 96;
    /**
     * How opaque the dark is by default.
     *
     * <p>Not 1.0 even though "pitch black" is what the original does: a completely opaque
     * overlay is indistinguishable from a renderer that stopped drawing, and this project
     * already reads the fog's ceiling the same way (see {@link FogData#DEFAULT_MAX_ALPHA}). At
     * 0.94 the lawn is a black silhouette the player can navigate by, which is dark enough to
     * hide a zombie - {@link StormState#hidesAt} answers that question separately - without
     * being a black screen.
     */
    public static final float DEFAULT_MAX_ALPHA = 0.94F;

    /** The shortest cycle a storm may have; below this the strikes run into each other. */
    public static final int MIN_INTERVAL_TICKS = 60;
    /** A strike shorter than this cannot hold a flicker: it is one frame of light. */
    public static final int MIN_FLASH_TICKS = 24;

    public static final MapCodec<StormData> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.optionalFieldOf("interval_ticks", DEFAULT_INTERVAL_TICKS)
                    .forGetter(StormData::intervalTicks),
            Codec.INT.optionalFieldOf("flash_ticks", DEFAULT_FLASH_TICKS)
                    .forGetter(StormData::flashTicks),
            Codec.FLOAT.optionalFieldOf("max_alpha", DEFAULT_MAX_ALPHA)
                    .forGetter(StormData::maxAlpha)
    ).apply(i, StormData::new));

    public static final Codec<StormData> CODEC = MAP_CODEC.codec();

    public StormData {
        // A strike at least as long as the cycle would be a storm that never gets dark, and one of
        // zero ticks is a storm nobody can see. Both are folded rather than refused, so a level
        // author's typo is a slightly wrong storm instead of a board that throws.
        intervalTicks = Math.max(MIN_INTERVAL_TICKS, intervalTicks);
        flashTicks = Math.max(MIN_FLASH_TICKS, Math.min(intervalTicks, flashTicks));
        maxAlpha = Math.max(0F, Math.min(1F, maxAlpha));
    }

    /** The state this level's storm opens on: the first flash, at tick zero. */
    public com.pvzce.common.level.mechanic.StormState openingState() {
        return com.pvzce.common.level.mechanic.StormState.at(0L, 0);
    }

    /** One message per problem, for {@code LevelValidator}. */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (intervalTicks < MIN_INTERVAL_TICKS) {
            errors.add("storm flashes every " + intervalTicks + " ticks: no storm is shorter than "
                    + MIN_INTERVAL_TICKS);
        }
        if (flashTicks < MIN_FLASH_TICKS) {
            errors.add("storm strike lasts " + flashTicks + " ticks: below "
                    + MIN_FLASH_TICKS + " there is no room for the flicker inside it");
        }
        if (flashTicks > intervalTicks) {
            errors.add("storm strikes for " + flashTicks + " ticks out of a " + intervalTicks
                    + "-tick cycle: the board would never be dark");
        }
        return errors;
    }
}
