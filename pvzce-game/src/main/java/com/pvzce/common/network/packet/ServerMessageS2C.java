package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

public record ServerMessageS2C(String message) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeString(message);
    }

    public static ServerMessageS2C decode(PacketByteBuf buf) {
        return new ServerMessageS2C(buf.readString());
    }
}
