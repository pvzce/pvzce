package com.pvzce.common.level.mechanic;

import com.pvzce.api.content.StormData;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;

/**
 * Where a running storm is in its cycle, and how bright the lightning is right now.
 *
 * <p>The server owns the whole thing: the cycle position, which of the lightning shapes this
 * strike is, and the brightness that follows from the two. The client is told the last of the
 * three - see {@link Wire} - because darkness and hiding have to be the same number on both sides
 * and asking the client to re-derive it is how the zombie the server hid keeps being drawn.
 *
 * <p>Lives beside the mechanic rather than in {@code api} because it carries a wire codec, which
 * is a {@code common} concern (compare {@code FogMechanic.Wire}, which does the same for the fog).
 *
 * @param pulse which strike this is, counted from zero. What makes "a new strike" decidable, what
 *              picks the shape of this one, and what a save round-trips so a resumed run carries
 *              on striking rather than starting the count again
 * @param tick  how far into the cycle, 0 at the instant of the strike
 */
public record StormState(long pulse, int tick) {
    public static final PacketStruct.Codec<StormState> CODEC = PacketStruct.<StormState>builder()
            .field(StormState::pulse, PacketByteBuf::writeLong, PacketByteBuf::readLong)
            .field(StormState::tick, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
            .build(values -> new StormState((Long) values.get(0), (Integer) values.get(1)));

    public static StormState at(long pulse, int tick) {
        return new StormState(Math.max(0L, pulse), Math.max(0, tick));
    }

    /** The same strike, one tick further in. */
    public StormState advanced() {
        return new StormState(pulse, tick + 1);
    }

    /**
     * How lit the board is right now: 1 at the brightest instant of a strike, 0 in the dark.
     *
     * <p>A <b>shape</b> rather than a fade: real lightning is two or three spikes with the light
     * dying between them, not one bright instant followed by a smooth ramp, and a storm whose
     * every strike looked the same would read as a blinking light rather than as weather. The
     * shapes live in {@link #PATTERNS}, one is picked per strike, and the whole of the strike's
     * length - {@link StormData#flashTicks()} - is mapped across the shape so a level tunes how
     * long the lightning lasts without losing the flicker inside it.
     */
    public float exposure(StormData data) {
        if (data == null) {
            return 1F;
        }
        if (tick <= 0) {
            return 1F;
        }
        if (tick >= data.flashTicks()) {
            return 0F;
        }
        float[] pattern = patternFor(pulse);
        // Sampled so that the strike's first and last ticks land on the shape's first and last
        // samples: index 0 is the strike, and the last tick before the dark phase is the shape's
        // closing zero. Dividing by `flashTicks` instead would leave the shape's tail unplayed.
        int span = Math.max(1, data.flashTicks() - 1);
        int index = Math.round((float) tick / span * (pattern.length - 1));
        return pattern[Math.min(pattern.length - 1, Math.max(0, index))];
    }

    /**
     * How opaque the dark is right now, 0 while the lightning is at its brightest.
     *
     * <p>The number the renderer draws with. Kept here rather than multiplied out at the call
     * site so the overlay and any test read one expression.
     */
    public float darkness(StormData data) {
        float ceiling = data == null ? 0F : data.maxAlpha();
        return ceiling * (1F - exposure(data));
    }

    /**
     * True at the instant of the strike, which is when the thunder lands.
     *
     * <p>Separate from "exposure &gt; 0" on purpose: every flicker of a strike is still some
     * light, and rolling thunder on all of them would be a drum roll rather than a clap.
     */
    public boolean striking() {
        return tick == 0;
    }

    /**
     * True when something standing where it is this dark is not drawn.
     *
     * <p>The storm's half of {@code FogClientMechanic.hides}, and the same rule: what cannot be
     * made out is not drawn, because a half-visible zombie is one the player will argue about.
     *
     * <p>Two conditions, and the first is the one that matters to a level author:
     * <b>{@code max_alpha} decides whether this storm hides things at all</b>. A storm dark
     * enough to be a blackout hides what is standing between the flickers; one that only dims the
     * lawn is dusk, and dusk is a level that is merely hard to read rather than one where the
     * player cannot see. Measuring the threshold as a fraction of the ceiling alone - which is
     * what the fog does, because a fog span is dark by definition - would have made a 0.4 storm
     * hide its board at the bottom of every fade, which is neither what "dim" means nor what the
     * level asked for.
     *
     * <p>Half: a strike's spikes are brief and its dim stretches are most of its length, so a
     * threshold low enough to keep the lawn visible through the flicker would also keep the
     * zombies visible through it - and "the lightning is the only way to see" is the level.
     */
    public boolean hidesAt(StormData data) {
        if (data == null || data.maxAlpha() < HIDING_CEILING) {
            return false;
        }
        return darkness(data) >= data.maxAlpha() * HIDE_FRACTION;
    }

    /**
     * The shape of strike {@code pulse}: which of {@link #PATTERNS} it uses.
     *
     * <p>Rotated rather than random so two consecutive strikes are never the same shape - the
     * thing a player notices about lightning is that it never repeats - and deterministic rather
     * than seeded so a resumed run draws the same strike it would have drawn.
     */
    public static float[] patternFor(long pulse) {
        return PATTERNS[(int) Math.floorMod(pulse, PATTERNS.length)];
    }

    /**
     * The lightning shapes, as exposure over the strike's own length.
     *
     * <p>Read as "how lit is the board at this fraction of the flash". Every one of them starts
     * and ends at zero - a strike begins from darkness and returns to it, or the board would jump
     * between the shape and the dark phase - and every one has an uneven middle, which is the
     * whole of what makes it read as lightning: the eye is looking for the shape of the flicker,
     * and a smooth curve of any length reads as a light being switched on.
     *
     * <p>Four of them, hand-written rather than generated: a double strike (the common one), a
     * flicker train, a slow wash, and one bright spike with a long afterglow. A fifth would not
     * add anything a player could name.
     */
    private static final float[][] PATTERNS = {
            // Double strike: the common one - a bright hold, a black gap, a second spike, then an
            // uneven afterglow that dies in two steps.
            {1F, 1F, 1F, 0.98F, 0.74F, 0.34F, 0.12F, 0.03F, 0.01F, 0F,
             0.72F, 1F, 1F, 0.92F, 0.60F, 0.30F, 0.14F, 0.12F, 0.04F, 0.01F, 0F},
            // Flicker train: the light never settles. What a close strike looks like.
            {1F, 0.96F, 0.80F, 0.38F, 0.10F, 0.02F, 0.66F, 1F, 1F, 0.72F, 0.34F,
             0.52F, 0.92F, 1F, 0.64F, 0.26F, 0.10F, 0.14F, 0.04F, 0.01F, 0F},
            // Slow wash: a storm still far away - dim, wide, and gone before it is ever bright.
            {0.30F, 0.52F, 0.70F, 0.82F, 0.88F, 0.84F, 0.74F, 0.62F, 0.48F, 0.34F,
             0.22F, 0.12F, 0.06F, 0.03F, 0.01F, 0F, 0F, 0F, 0F, 0F, 0F},
            // One sharp spike and a long afterglow that never quite reaches the dark until the
            // strike is over.
            {1F, 1F, 0.92F, 0.70F, 0.52F, 0.44F, 0.40F, 0.36F, 0.30F, 0.24F, 0.20F,
             0.16F, 0.12F, 0.09F, 0.06F, 0.04F, 0.03F, 0.02F, 0.01F, 0.01F, 0F},
    };

    /**
     * How dark a storm has to get before it hides what stands on the board.
     *
     * <p>Below this the storm is a dim evening and nothing is hidden, whatever the flicker is
     * doing: the level is hard to read, not impossible, and a player who cannot see a zombie at
     * all is playing a different level from the one the file describes.
     */
    public static final float HIDING_CEILING = 0.6F;

    /**
     * How dark it has to be before what is standing there is not drawn.
     *
     * <p>Half of the level's own ceiling - see {@link #hidesAt}.
     */
    public static final float HIDE_FRACTION = 0.5F;

    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static StormState decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }

    /**
     * What the server sends every tick: which strike, and how lit the board is.
     *
     * <p>Two fields rather than the cycle position, because the brightness is the whole of what
     * the client needs and it is <em>not</em> derivable from the position without also shipping
     * the shape table - one fixed table on the server that a data pack cannot change, one on the
     * client that could have been built from a different version of the game. Sending the answer
     * is six bytes; sending the question would be a second implementation of the lightning.
     *
     * <p>{@code pulse} still travels: it is how the client tells one strike's thunder from the
     * next, since every strike starts at tick zero and two of them in a row are otherwise
     * indistinguishable from one long one.
     */
    public record Wire(long pulse, float exposure) {
        public static final PacketStruct.Codec<Wire> CODEC = PacketStruct.<Wire>builder()
                .field(Wire::pulse, PacketByteBuf::writeLong, PacketByteBuf::readLong)
                .field(Wire::exposure, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
                .build(values -> new Wire((Long) values.get(0), (Float) values.get(1)));

        public Wire {
            exposure = Math.max(0F, Math.min(1F, exposure));
        }

        public static Wire of(StormState state, StormData data) {
            return new Wire(state.pulse(), state.exposure(data));
        }

        public void encode(PacketByteBuf buf) {
            CODEC.encode(this, buf);
        }

        public static Wire decode(PacketByteBuf buf) {
            return CODEC.decode(buf);
        }
    }
}
