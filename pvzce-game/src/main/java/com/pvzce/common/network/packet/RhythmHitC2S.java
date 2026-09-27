package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * A rhythm note was pressed: which lane, which note, and how well it went.
 *
 * <p>The client is the only side that can answer the last field - it has the frame the key arrived
 * in and a clock interpolated to the millisecond - and the server is the only side that can answer
 * the first two, because it owns the chart. So both halves travel: the client proposes, the server
 * disposes (see {@code RhythmMechanic.judge}).
 *
 * <p>{@code noteTick} is the note's own tick rather than an index into the lane, so a press names a
 * moment rather than a position: the server can then ask "is that a note, and is it now" without
 * depending on the two sides agreeing about how the list is ordered.
 *
 * <p>{@code perceivedTicks} is how far off the beat the client thought it was. It is a hint, not a
 * number the score rests on: the server re-measures the distance with its own clock and takes the
 * better of the two, which is what lets a slightly laggy client still earn a perfect on a note the
 * server saw as a few ticks late.
 */
public record RhythmHitC2S(String laneKind, int laneIndex, int noteTick, int perceivedTicks)
        implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeString(laneKind == null ? "row" : laneKind);
        buf.writeInt(laneIndex);
        buf.writeInt(noteTick);
        buf.writeInt(perceivedTicks);
    }

    public static RhythmHitC2S decode(PacketByteBuf buf) {
        return new RhythmHitC2S(buf.readString(), buf.readInt(), buf.readInt(), buf.readInt());
    }
}
