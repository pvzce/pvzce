package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;

/**
 * The preparation phase: the stretch of a level that runs before its first zombie.
 *
 * <p>The original's Last Stand opens with a wallet and an empty lawn: the player spends the sun
 * they were given, arranges the defence, and only then starts the waves. This is that phase, made
 * a level mechanic so that any level can have one - the original uses it for one mini-game, and
 * this project also uses it for the rhythm levels, where the chart needs a moment to be read
 * before the first note.
 *
 * <p><b>What it changes, and what it deliberately does not.</b> While the phase is running no
 * wave is released and no sun falls from the sky - the sun the player builds with is the sun the
 * level handed over, which is the whole rule of the phase ({@code docs/决策记录.md}: 「只能花初始
 * 阳光」). Everything else is the ordinary game: the same cards, the same prices, the same
 * placement rules. A level whose preparation lasts forever is a sandbox, not a broken level.
 *
 * @param manual whether the player starts the waves by hand. True is the original's shape - a
 *               button, and the phase lasts exactly as long as the player wants
 * @param ticks  how long the phase lasts when it is not manual, in ticks; ignored when
 *               {@code manual} is true. A level that wants a countdown ("the chart starts in
 *               eight seconds") sets this and leaves {@code manual} false
 * @param refund whether digging a plant up during the phase gives its full price back. True by
 *               default because the phase is where a player *rearranges*: charging them for
 *               changing their mind would make the first wave punish a decision the level gave
 *               them no time to think about. After the phase the ordinary rule applies (the sun
 *               shovel's fraction, or nothing)
 */
public record PreparationData(boolean manual, int ticks, boolean refund) implements MechanicData {
    /** The original's shape: a button, and no clock. */
    public static final PreparationData MANUAL = new PreparationData(true, 0, true);

    public static final MapCodec<PreparationData> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.BOOL.optionalFieldOf("manual", true).forGetter(PreparationData::manual),
            Codec.INT.optionalFieldOf("ticks", 0).forGetter(PreparationData::ticks),
            Codec.BOOL.optionalFieldOf("refund", true).forGetter(PreparationData::refund)
    ).apply(i, PreparationData::new));

    public PreparationData {
        ticks = Math.max(0, ticks);
    }
}
