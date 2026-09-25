package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

/**
 * An endless run's round has ended and the next one is waiting on the player.
 *
 * <p>The server stops simulating when this goes out: the round's last wave is dead, the lawn is
 * clear, and the player is about to choose their next cards. It is not an end-of-level packet -
 * {@link GameStateS2C} says "won" or "lost", and an endless run does neither - so the client
 * shows a dialog over the board rather than a results screen.
 *
 * <p>The three numbers are what the round amounted to, which is the only score this mode has.
 *
 * @param round          the round that just finished, one-based
 * @param cumulativeWaves how many waves the run has released in total
 * @param kills          zombies killed this run
 * @param survivedTicks  how long the run has lasted, in ticks
 */
public record RoundClearS2C(
        int round,
        int cumulativeWaves,
        int kills,
        int survivedTicks
) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    /** The round's length in whole seconds, for a summary line. */
    public int survivedSeconds() {
        return survivedTicks / 60;
    }

    public static final PacketStruct.Codec<RoundClearS2C> CODEC =
            PacketStruct.<RoundClearS2C>builder()
                    .field(RoundClearS2C::round, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                    .field(RoundClearS2C::cumulativeWaves, PacketByteBuf::writeInt,
                            PacketByteBuf::readInt)
                    .field(RoundClearS2C::kills, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                    .field(RoundClearS2C::survivedTicks, PacketByteBuf::writeInt,
                            PacketByteBuf::readInt)
                    .build(values -> new RoundClearS2C((Integer) values.get(0), (Integer) values.get(1),
                            (Integer) values.get(2), (Integer) values.get(3)));

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static RoundClearS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
