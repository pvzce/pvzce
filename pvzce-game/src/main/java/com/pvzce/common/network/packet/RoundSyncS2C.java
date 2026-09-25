package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

import java.util.List;

/**
 * Where a run stands: which round, how long it is, and what the meter should draw.
 *
 * <p>Sent on entering a level, on resuming a save, and every time a round changes - which is the
 * point of it existing at all. {@link LevelInitS2C} carries the wave list once, when the level
 * starts, and an endless level's list is not a property of the run: it is regenerated per round,
 * so the client cannot derive it from anything it was told at the start.
 *
 * <p>One packet for the whole picture rather than one field per fact, and the wave types ride
 * with it: a client that learned the round number and the types from two packets could draw a
 * meter of the previous round's flags for a frame.
 *
 * @param round           which round the run is in, one-based
 * @param wavesInRound    how many waves that round holds
 * @param cumulativeWaves how many waves the run has released in total, across every round
 * @param roundClearPending whether the round is over and the level is waiting for a card choice
 * @param endless         whether the level generates its waves; what the HUD draws a round line
 *                        for. An ordinary level answers with round 1 and a wave count too, so
 *                        the numbers alone cannot say which kind of level the client is on
 * @param waveTypes       the current round's wave types, lowercase - the meter's flags
 */
public record RoundSyncS2C(
        int round,
        int wavesInRound,
        int cumulativeWaves,
        boolean roundClearPending,
        boolean endless,
        List<String> waveTypes
) implements PvzcePacket {
    public RoundSyncS2C {
        waveTypes = List.copyOf(waveTypes);
    }

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    public static final PacketStruct.Codec<RoundSyncS2C> CODEC =
            PacketStruct.<RoundSyncS2C>builder()
                    .field(RoundSyncS2C::round, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                    .field(RoundSyncS2C::wavesInRound, PacketByteBuf::writeInt,
                            PacketByteBuf::readInt)
                    .field(RoundSyncS2C::cumulativeWaves, PacketByteBuf::writeInt,
                            PacketByteBuf::readInt)
                    .field(RoundSyncS2C::roundClearPending, PacketByteBuf::writeBoolean,
                            PacketByteBuf::readBoolean)
                    .field(RoundSyncS2C::endless, PacketByteBuf::writeBoolean,
                            PacketByteBuf::readBoolean)
                    .stringList(RoundSyncS2C::waveTypes)
                    .build(values -> new RoundSyncS2C((Integer) values.get(0), (Integer) values.get(1),
                            (Integer) values.get(2), (Boolean) values.get(3), (Boolean) values.get(4),
                            (List<String>) values.get(5)));

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static RoundSyncS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
