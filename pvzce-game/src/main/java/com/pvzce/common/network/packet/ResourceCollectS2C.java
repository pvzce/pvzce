package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
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

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeInt(entityId);
        buf.writeString(resourceId);
        buf.writeInt(amount);
        buf.writeFloat(x);
        buf.writeFloat(y);
        buf.writeFloat(height);
        buf.writeString(icon);
    }

    public static ResourceCollectS2C decode(PacketByteBuf buf) {
        return new ResourceCollectS2C(buf.readInt(), buf.readString(), buf.readInt(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readString());
    }
}
