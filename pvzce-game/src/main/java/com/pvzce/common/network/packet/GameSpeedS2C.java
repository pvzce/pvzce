package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/** Authoritative server tick rate; 60/120/180 are the three button presets. */
public record GameSpeedS2C(float tickRate) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeFloat(tickRate);
    }

    public static GameSpeedS2C decode(PacketByteBuf buf) {
        return new GameSpeedS2C(buf.readFloat());
    }
}
