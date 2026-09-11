package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/** Server tells the client to open the in-game level editor for a level id. */
public record OpenEditorS2C(String levelId) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeString(levelId);
    }

    public static OpenEditorS2C decode(PacketByteBuf buf) {
        return new OpenEditorS2C(buf.readString());
    }
}
