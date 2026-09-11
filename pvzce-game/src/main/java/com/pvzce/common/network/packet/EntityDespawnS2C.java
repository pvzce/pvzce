package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

public record EntityDespawnS2C(int entityId) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeInt(entityId);
    }

    public static EntityDespawnS2C decode(PacketByteBuf buf) {
        return new EntityDespawnS2C(buf.readInt());
    }
}
