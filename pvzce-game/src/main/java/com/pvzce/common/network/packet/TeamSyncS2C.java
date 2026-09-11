package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/** The team this client currently controls (local join in M3, LAN in phase 3). */
public record TeamSyncS2C(String teamId, String teamName) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeString(teamId);
        buf.writeString(teamName);
    }

    public static TeamSyncS2C decode(PacketByteBuf buf) {
        return new TeamSyncS2C(buf.readString(), buf.readString());
    }
}
