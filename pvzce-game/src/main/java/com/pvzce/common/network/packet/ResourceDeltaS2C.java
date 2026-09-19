package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

public record ResourceDeltaS2C(String teamId, String resourceId, int totalAmount) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    public static final PacketStruct.Codec<ResourceDeltaS2C> CODEC = PacketStruct.<ResourceDeltaS2C>builder()
    .field(ResourceDeltaS2C::teamId, PacketByteBuf::writeString, PacketByteBuf::readString)
    .field(ResourceDeltaS2C::resourceId, PacketByteBuf::writeString, PacketByteBuf::readString)
    .field(ResourceDeltaS2C::totalAmount, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .build(values -> new ResourceDeltaS2C((String) values.get(0), (String) values.get(1), (Integer) values.get(2)));

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static ResourceDeltaS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
