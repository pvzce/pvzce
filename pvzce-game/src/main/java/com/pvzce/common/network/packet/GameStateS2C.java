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
 * @param state        {@link #RUNNING}, {@link #WON} or {@link #LOST}
 * @param winTeamId    the team that won, or empty while the level runs
 * @param wavesArrived how many waves the level actually released
 * @param kills        zombies killed this run
 * @param survivedTicks how long the run lasted, in ticks
 */
public record GameStateS2C(String state, String winTeamId, int wavesArrived, int kills,
                           int survivedTicks) implements PvzcePacket {
    public static final String RUNNING = "running";
    public static final String WON = "won";
    public static final String LOST = "lost";

    public static final PacketStruct.Codec<GameStateS2C> CODEC = PacketStruct.<GameStateS2C>builder()
            .field(GameStateS2C::state, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(GameStateS2C::winTeamId, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(GameStateS2C::wavesArrived, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(GameStateS2C::kills, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(GameStateS2C::survivedTicks, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .build(values -> new GameStateS2C((String) values.get(0), (String) values.get(1),
                    (Integer) values.get(2), (Integer) values.get(3), (Integer) values.get(4)));

    /** A state packet with no run behind it: what the level sends while it is still running. */
    public GameStateS2C(String state, String winTeamId) {
        this(state, winTeamId, 0, 0, 0);
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
