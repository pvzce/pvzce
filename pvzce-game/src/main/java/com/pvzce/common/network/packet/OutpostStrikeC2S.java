package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

/** A world-scoped action checked by the running level before changing any state. */
public record OutpostStrikeC2S(String levelId, String worldName, int point, int x, int y, String surface) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    public static final PacketStruct.Codec<OutpostStrikeC2S> CODEC = PacketStruct.<OutpostStrikeC2S>builder()
            .field(OutpostStrikeC2S::levelId, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(OutpostStrikeC2S::worldName, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(OutpostStrikeC2S::point, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(OutpostStrikeC2S::x, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(OutpostStrikeC2S::y, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(OutpostStrikeC2S::surface, PacketByteBuf::writeString, PacketByteBuf::readString)
            .build(v -> new OutpostStrikeC2S((String) v.get(0), (String) v.get(1), (Integer) v.get(2), (Integer) v.get(3), (Integer) v.get(4), (String) v.get(5)));

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static OutpostStrikeC2S decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
