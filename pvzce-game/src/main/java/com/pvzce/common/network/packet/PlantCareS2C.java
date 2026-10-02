package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

/** Authoritative fertilizer and attached-ladder presentation; changed states only. */
public record PlantCareS2C(int entityId, boolean fertilized, boolean laddered) implements PvzcePacket {
    public static final PacketStruct.Codec<PlantCareS2C> CODEC = PacketStruct.<PlantCareS2C>builder()
            .field(PlantCareS2C::entityId, PacketByteBuf::writeVarInt, PacketByteBuf::readVarInt)
            .field(PlantCareS2C::fertilized, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
            .field(PlantCareS2C::laddered, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
            .build(v -> new PlantCareS2C((Integer) v.get(0), (Boolean) v.get(1), (Boolean) v.get(2)));
    @Override public ConnectionDirection direction() { return ConnectionDirection.CLIENTBOUND; }
    @Override public void encode(PacketByteBuf buf) { CODEC.encode(this, buf); }
    public static PlantCareS2C decode(PacketByteBuf buf) { return CODEC.decode(buf); }
}
