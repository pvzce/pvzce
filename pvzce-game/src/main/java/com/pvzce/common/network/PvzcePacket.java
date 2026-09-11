package com.pvzce.common.network;

/** A serializable game packet. C2S packets flow to the server, S2C to the client. */
public interface PvzcePacket {
    ConnectionDirection direction();

    void encode(PacketByteBuf buf);
}
