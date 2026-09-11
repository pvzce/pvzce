package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * Client request from the in-level speed button. {@code speedIndex} is 1, 2 or
 * 3 and maps to 60/120/180 server ticks per second.
 */
public record SetGameSpeedC2S(int speedIndex) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeVarInt(speedIndex);
    }

    public static SetGameSpeedC2S decode(PacketByteBuf buf) {
        return new SetGameSpeedC2S(buf.readVarInt());
    }
}
