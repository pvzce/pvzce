package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/** Use a tool card on a board cell. */
public record UseToolC2S(int slotIndex, int gridX, int gridY) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeInt(slotIndex);
        buf.writeInt(gridX);
        buf.writeInt(gridY);
    }

    public static UseToolC2S decode(PacketByteBuf buf) {
        return new UseToolC2S(buf.readInt(), buf.readInt(), buf.readInt());
    }
}
