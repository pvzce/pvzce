package com.pvzce.common.network.packet;

import com.pvzce.common.network.*;

/** Room control, deliberately distinct from gameplay and local world management. */
public record RoomC2S(String action, String value, String token) implements PvzcePacket {
    public static final PacketStruct.Codec<RoomC2S> CODEC = PacketStruct.<RoomC2S>builder()
            .field(RoomC2S::action, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(RoomC2S::value, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(RoomC2S::token, PacketByteBuf::writeString, PacketByteBuf::readString)
            .build(v -> new RoomC2S((String)v.get(0), (String)v.get(1), (String)v.get(2)));
    @Override public ConnectionDirection direction() { return ConnectionDirection.SERVERBOUND; }
    @Override public void encode(PacketByteBuf buf) { CODEC.encode(this, buf); }
    public static RoomC2S decode(PacketByteBuf buf) { return CODEC.decode(buf); }
}
