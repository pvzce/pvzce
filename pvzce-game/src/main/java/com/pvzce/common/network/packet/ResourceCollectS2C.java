package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

/**
 * Server acknowledgement that a resource drop was collected. The resource is
 * already credited by the time this packet is sent; the client uses it purely
 * to animate the drop flying from {@code x}/{@code y} to the resource area.
 */
public record ResourceCollectS2C(int entityId, String resourceId, int amount,
                                 float x, float y, float height, String icon) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    public static final PacketStruct.Codec<ResourceCollectS2C> CODEC = PacketStruct.<ResourceCollectS2C>builder()
    .field(ResourceCollectS2C::entityId, PacketByteBuf::writeInt, PacketByteBuf::readInt)
    .field(ResourceCollectS2C::resourceId, PacketByteBuf::writeString, PacketByteBuf::readString)
    .field(ResourceCollectS2C::amount, PacketByteBuf::writeInt, PacketByteBuf::readInt)
    .field(ResourceCollectS2C::x, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
    .field(ResourceCollectS2C::y, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
    .field(ResourceCollectS2C::height, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
    .field(ResourceCollectS2C::icon, PacketByteBuf::writeString, PacketByteBuf::readString)
            .build(values -> new ResourceCollectS2C((Integer) values.get(0), (String) values.get(1), (Integer) values.get(2), (Float) values.get(3), (Float) values.get(4), (Float) values.get(5), (String) values.get(6)));

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static ResourceCollectS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
