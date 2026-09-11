package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/** Enter a level inside the selected world; {@code restart} skips save restoration. */
public record RequestLevelC2S(String levelId, String worldName, boolean restart) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeString(levelId);
        buf.writeString(worldName);
        buf.writeBoolean(restart);
    }

    public static RequestLevelC2S decode(PacketByteBuf buf) {
        return new RequestLevelC2S(buf.readString(), buf.readString(), buf.readBoolean());
    }
}
