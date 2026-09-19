package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

public record SlotSyncS2C(SlotInfo slot) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    public static final PacketStruct.Codec<SlotSyncS2C> CODEC = PacketStruct.<SlotSyncS2C>builder()
    .nested(SlotSyncS2C::slot, SlotInfo.CODEC)
            .build(values -> new SlotSyncS2C((SlotInfo) values.get(0)));

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static SlotSyncS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
