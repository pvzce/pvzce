package com.pvzce.common.network.packet;

import com.pvzce.common.network.*;

/** Ordered chunks of the authoritative content snapshot, ending with a SHA-256 digest. */
public record ContentS2C(int index, String data, boolean last) implements PvzcePacket {
    public static final PacketStruct.Codec<ContentS2C> CODEC = PacketStruct.<ContentS2C>builder()
            .field(ContentS2C::index, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(ContentS2C::data, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(ContentS2C::last, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
            .build(v -> new ContentS2C((Integer)v.get(0), (String)v.get(1), (Boolean)v.get(2)));
    @Override public ConnectionDirection direction() { return ConnectionDirection.CLIENTBOUND; }
    @Override public void encode(PacketByteBuf buf) { CODEC.encode(this, buf); }
    public static ContentS2C decode(PacketByteBuf buf) { return CODEC.decode(buf); }
}
