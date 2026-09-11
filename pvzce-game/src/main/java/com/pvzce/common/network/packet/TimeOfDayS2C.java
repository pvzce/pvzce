package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * Day-cycle state for the time-of-day shader.
 *
 * <p>The server sends only the authoritative clock and the two cycle lengths; the
 * client derives both the smooth night factor and the day/night answer from the
 * shared {@code DayNightCycle}. The old {@code night} boolean duplicated that
 * decision on the wire and was never read - the client re-derived it with a
 * different formula, so the two could disagree exactly at dusk.
 */
public record TimeOfDayS2C(int dayTicks, int dayLength, int nightLength) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeInt(dayTicks);
        buf.writeInt(dayLength);
        buf.writeInt(nightLength);
    }

    public static TimeOfDayS2C decode(PacketByteBuf buf) {
        return new TimeOfDayS2C(buf.readInt(), buf.readInt(), buf.readInt());
    }
}
