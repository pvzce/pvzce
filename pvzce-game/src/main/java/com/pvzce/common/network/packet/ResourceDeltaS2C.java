package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

public record ResourceDeltaS2C(String teamId, String resourceId, int totalAmount) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeString(teamId);
        buf.writeString(resourceId);
        buf.writeInt(totalAmount);
    }

    public static ResourceDeltaS2C decode(PacketByteBuf buf) {
        return new ResourceDeltaS2C(buf.readString(), buf.readString(), buf.readInt());
    }
}
