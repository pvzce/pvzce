package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

public record SlotSyncS2C(SlotInfo slot) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        slot.encode(buf);
    }

    public static SlotSyncS2C decode(PacketByteBuf buf) {
        return new SlotSyncS2C(SlotInfo.decode(buf));
    }
}
