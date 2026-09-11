package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/** Ask the server for the dynamic level registry and per-world save status. */
public record RequestLevelListC2S(String worldName) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeString(worldName);
    }

    public static RequestLevelListC2S decode(PacketByteBuf buf) {
        return new RequestLevelListC2S(buf.readString());
    }
}
