package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

public record PlacePlantC2S(int slotIndex, int gridX, int gridY) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    public static final PacketStruct.Codec<PlacePlantC2S> CODEC = PacketStruct.<PlacePlantC2S>builder()
    .field(PlacePlantC2S::slotIndex, PacketByteBuf::writeInt, PacketByteBuf::readInt)
    .field(PlacePlantC2S::gridX, PacketByteBuf::writeInt, PacketByteBuf::readInt)
    .field(PlacePlantC2S::gridY, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .build(values -> new PlacePlantC2S((Integer) values.get(0), (Integer) values.get(1), (Integer) values.get(2)));

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static PlacePlantC2S decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
