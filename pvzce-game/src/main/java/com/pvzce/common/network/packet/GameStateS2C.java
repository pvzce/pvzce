package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

public record GameStateS2C(String state, String winTeamId) implements PvzcePacket {
    public static final String RUNNING = "running";
    public static final String WON = "won";
    public static final String LOST = "lost";

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeString(state);
        buf.writeString(winTeamId);
    }

    public static GameStateS2C decode(PacketByteBuf buf) {
        return new GameStateS2C(buf.readString(), buf.readString());
    }
}
