package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

/** Server-owned connection and haste presentation; the client never re-derives the buff. */
public record EchoNetworkS2C(int entityId, int networkId, int lilies, int charge, float rate)
        implements PvzcePacket {
    public static EchoNetworkS2C empty(int entityId) {
        return new EchoNetworkS2C(entityId, 0, 0, 0, 1F);
    }

    public static final PacketStruct.Codec<EchoNetworkS2C> CODEC = PacketStruct.<EchoNetworkS2C>builder()
            .field(EchoNetworkS2C::entityId, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
            .field(EchoNetworkS2C::networkId, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
            .field(EchoNetworkS2C::lilies, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
            .field(EchoNetworkS2C::charge, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
            .field(EchoNetworkS2C::rate, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
            .build(v -> new EchoNetworkS2C((Integer) v.get(0), (Integer) v.get(1),
                    (Integer) v.get(2), (Integer) v.get(3), (Float) v.get(4)));

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static EchoNetworkS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
