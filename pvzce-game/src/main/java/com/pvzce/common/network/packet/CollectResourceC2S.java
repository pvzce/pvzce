package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

public record CollectResourceC2S(int entityId) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeInt(entityId);
    }

    public static CollectResourceC2S decode(PacketByteBuf buf) {
        return new CollectResourceC2S(buf.readInt());
    }
}
