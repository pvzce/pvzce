package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

/**
 * The level's outcome, and what the run amounted to.
 *
 * <p>The three numbers at the end are the end-of-level summary, and they exist because a level
 * does not always end in a win: an endless level never does, and "you lost" without "after how
 * long, and how much did you kill" tells the player nothing about whether they are improving. On
 * an ordinary level they are the same shape - waves survived, zombies killed, time taken - so the
 * screen has one thing to draw either way.
 *
 * <p>{@code score} is the rhythm levels' own report, and it is empty on every other level. Those
 * levels are not measured in waves - they have none - so the three numbers above say nothing about
 * a run of theirs; what the player wants to read is how well they played the song. It travels here
 * rather than on a packet of its own because it is the same fact as "the run is over" and arrives at
 * the same moment (see {@code RhythmScore}).
 *
 * @param state        {@link #RUNNING}, {@link #WON} or {@link #LOST}
 * @param winTeamId    the team that won, or empty while the level runs
 * @param wavesArrived how many waves the level actually released
 * @param kills        zombies killed this run
 * @param survivedTicks how long the run lasted, in ticks
 * @param score        the rhythm levels' run report, or {@link RhythmScore#NONE}
 */
public record GameStateS2C(String state, String winTeamId, int wavesArrived, int kills,
                           int survivedTicks, RhythmScore score) implements PvzcePacket {
    /**
     * How one rhythm run went, for the page at the end of it.
     *
     * <p>{@code played} is what says whether there is a report at all: a level with no chart has
     * nothing to report, and "no chart" is not the same fact as "a chart on which nothing was ever
     * judged" - the second is a real (if humiliating) run, and a flag is one field where a
     * convention about zeroes would be a rule every reader has to know.
     */
    public record RhythmScore(boolean played, int perfect, int good, int fair, int missed,
                              int bestCombo, int bestPerfectStreak, int jalapenos) {
        /** The report of a level that has no chart: nothing to say. */
        public static final RhythmScore NONE = new RhythmScore(false, 0, 0, 0, 0, 0, 0, 0);

        public static final PacketStruct.Codec<RhythmScore> CODEC =
                PacketStruct.<RhythmScore>builder()
                        .field(RhythmScore::played, PacketByteBuf::writeBoolean,
                                PacketByteBuf::readBoolean)
                        .field(RhythmScore::perfect, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                        .field(RhythmScore::good, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                        .field(RhythmScore::fair, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                        .field(RhythmScore::missed, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                        .field(RhythmScore::bestCombo, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                        .field(RhythmScore::bestPerfectStreak, PacketByteBuf::writeInt,
                                PacketByteBuf::readInt)
                        .field(RhythmScore::jalapenos, PacketByteBuf::writeInt,
                                PacketByteBuf::readInt)
                        .build(values -> new RhythmScore((Boolean) values.get(0),
                                (Integer) values.get(1), (Integer) values.get(2),
                                (Integer) values.get(3), (Integer) values.get(4),
                                (Integer) values.get(5), (Integer) values.get(6),
                                (Integer) values.get(7)));

        /** The report of a run, from the mechanic's own tally. */
        public static RhythmScore of(com.pvzce.common.level.mechanic.RhythmMechanic.Score score) {
            return new RhythmScore(true, score.perfect(), score.good(), score.fair(),
                    score.missed(), score.bestCombo(), score.bestPerfectStreak(),
                    score.jalapenos());
        }

        /** How many notes were judged at all, which is the denominator of every other number. */
        public int judged() {
            return perfect + good + fair + missed;
        }
    }
    public static final String RUNNING = "running";
    public static final String WON = "won";
    public static final String LOST = "lost";

    public static final PacketStruct.Codec<GameStateS2C> CODEC = PacketStruct.<GameStateS2C>builder()
            .field(GameStateS2C::state, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(GameStateS2C::winTeamId, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(GameStateS2C::wavesArrived, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(GameStateS2C::kills, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(GameStateS2C::survivedTicks, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .nested(GameStateS2C::score, RhythmScore.CODEC)
            .build(values -> new GameStateS2C((String) values.get(0), (String) values.get(1),
                    (Integer) values.get(2), (Integer) values.get(3), (Integer) values.get(4),
                    (RhythmScore) values.get(5)));

    /** A state packet with no run behind it: what the level sends while it is still running. */
    public GameStateS2C(String state, String winTeamId) {
        this(state, winTeamId, 0, 0, 0, RhythmScore.NONE);
    }

    /** The same, for a level that is not a rhythm one: waves, kills and a clock, and no report. */
    public GameStateS2C(String state, String winTeamId, int wavesArrived, int kills,
                        int survivedTicks) {
        this(state, winTeamId, wavesArrived, kills, survivedTicks, RhythmScore.NONE);
    }

    /** The run's length in whole seconds, for a summary line. */
    public int survivedSeconds() {
        return survivedTicks / 60;
    }

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static GameStateS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
