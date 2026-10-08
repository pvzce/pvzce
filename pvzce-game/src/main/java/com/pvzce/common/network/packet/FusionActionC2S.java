package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

/** A request only; inventory, recipe and payment are checked by the running level. */
public record FusionActionC2S(String action, String ability, int dropId) implements PvzcePacket {
    public static final PacketStruct.Codec<FusionActionC2S> CODEC = PacketStruct.<FusionActionC2S>builder()
            .field(FusionActionC2S::action, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(FusionActionC2S::ability, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(FusionActionC2S::dropId, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .build(v -> new FusionActionC2S((String) v.get(0), (String) v.get(1), (Integer) v.get(2)));
    @Override public ConnectionDirection direction() { return ConnectionDirection.SERVERBOUND; }
    @Override public void encode(PacketByteBuf buf) { CODEC.encode(this, buf); }
    public static FusionActionC2S decode(PacketByteBuf buf) { return CODEC.decode(buf); }
}
