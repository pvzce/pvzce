package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * One click of the glove: lift the plant on this cell, or put down the one being carried.
 *
 * <p>The original's glove is a two-click move - pick up, then drop - and this is the
 * packet for each half. It carries only the slot and the cell; whether this is a pick-up
 * or a drop is the server's state to know, so a client cannot claim to be carrying
 * something it never picked up.
 */
public record MovePlantC2S(int slotIndex, int gridX, int gridY) implements PvzcePacket {
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

    public static MovePlantC2S decode(PacketByteBuf buf) {
        return new MovePlantC2S(buf.readInt(), buf.readInt(), buf.readInt());
    }
}
