package com.pvzce.common.network.packet;

import com.pvzce.common.network.*;
import java.util.List;

/** The receiving seat's identity and a public room roster. Token is private to that seat. */
public record RoomS2C(String state, String levelId, String playerId, String token,
                      boolean host, List<String> players, String message) implements PvzcePacket {
    public static final PacketStruct.Codec<RoomS2C> CODEC = PacketStruct.<RoomS2C>builder()
            .field(RoomS2C::state, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(RoomS2C::levelId, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(RoomS2C::playerId, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(RoomS2C::token, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(RoomS2C::host, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
            .stringList(RoomS2C::players)
            .field(RoomS2C::message, PacketByteBuf::writeString, PacketByteBuf::readString)
            .build(v -> new RoomS2C((String)v.get(0), (String)v.get(1), (String)v.get(2),
                    (String)v.get(3), (Boolean)v.get(4), (List<String>)v.get(5), (String)v.get(6)));
    @Override public ConnectionDirection direction() { return ConnectionDirection.CLIENTBOUND; }
    @Override public void encode(PacketByteBuf buf) { CODEC.encode(this, buf); }
    public static RoomS2C decode(PacketByteBuf buf) { return CODEC.decode(buf); }
}
