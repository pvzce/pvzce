package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

public record PickCardC2S(int slotIndex) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeInt(slotIndex);
    }

    public static PickCardC2S decode(PacketByteBuf buf) {
        return new PickCardC2S(buf.readInt());
    }
}
