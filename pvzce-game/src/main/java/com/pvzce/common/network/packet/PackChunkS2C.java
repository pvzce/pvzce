package com.pvzce.common.network.packet;

import com.pvzce.common.network.*;

/** One bounded archive chunk; the client requests the next only after consuming this one. */
public record PackChunkS2C(String hash, int index, String data, boolean last) implements PvzcePacket {
    public static final PacketStruct.Codec<PackChunkS2C> CODEC = PacketStruct.<PackChunkS2C>builder()
            .field(PackChunkS2C::hash, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(PackChunkS2C::index, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(PackChunkS2C::data, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(PackChunkS2C::last, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
            .build(v -> new PackChunkS2C((String)v.get(0), (Integer)v.get(1), (String)v.get(2), (Boolean)v.get(3)));
    @Override public ConnectionDirection direction() { return ConnectionDirection.CLIENTBOUND; }
    @Override public void encode(PacketByteBuf buf) { CODEC.encode(this, buf); }
    public static PackChunkS2C decode(PacketByteBuf buf) { return CODEC.decode(buf); }
}
