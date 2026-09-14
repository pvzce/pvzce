package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * A streamed entity's changed state.
 *
 * <p>{@code armor} is {@link EntitySpawnS2C#NO_ARMOR} for anything that wears none, and
 * the remaining armour otherwise (0 = it was shot off). The client needs it to draw the
 * right cone on a Conehead; nothing about it is simulated on that side.
 */
public record EntityUpdateS2C(int entityId, float cellX, float cellY, int health, String animation,
                              float height, int armor) implements PvzcePacket {
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
        buf.writeInt(armor);
    }

    public static EntityUpdateS2C decode(PacketByteBuf buf) {
        return new EntityUpdateS2C(buf.readInt(), buf.readFloat(), buf.readFloat(), buf.readInt(),
                buf.readString(), buf.readFloat(), buf.readInt());
    }
}
