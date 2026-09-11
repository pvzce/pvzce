package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

public record EntityUpdateS2C(int entityId, float cellX, float cellY, int health, String animation, float height) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeInt(entityId);
        buf.writeFloat(cellX);
        buf.writeFloat(cellY);
        buf.writeInt(health);
        buf.writeString(animation);
        buf.writeFloat(height);
    }

    public static EntityUpdateS2C decode(PacketByteBuf buf) {
        return new EntityUpdateS2C(buf.readInt(), buf.readFloat(), buf.readFloat(), buf.readInt(), buf.readString(), buf.readFloat());
    }
}
