package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/** Save and leave the current level (returning to the world/level menus). */
public record LeaveLevelC2S() implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
    }

    public static LeaveLevelC2S decode(PacketByteBuf buf) {
        return new LeaveLevelC2S();
    }
}
