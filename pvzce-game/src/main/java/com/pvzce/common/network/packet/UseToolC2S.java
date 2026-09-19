package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

/** Use a tool card on a board cell. */
public record UseToolC2S(int slotIndex, int gridX, int gridY) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    public static final PacketStruct.Codec<UseToolC2S> CODEC = PacketStruct.<UseToolC2S>builder()
    .field(UseToolC2S::slotIndex, PacketByteBuf::writeInt, PacketByteBuf::readInt)
    .field(UseToolC2S::gridX, PacketByteBuf::writeInt, PacketByteBuf::readInt)
    .field(UseToolC2S::gridY, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .build(values -> new UseToolC2S((Integer) values.get(0), (Integer) values.get(1), (Integer) values.get(2)));

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static UseToolC2S decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
