package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/** Client answer to {@link LevelSavePromptS2C}: continue the save or restart fresh. */
public record ResumeLevelC2S(String levelId, String worldName, boolean restart) implements PvzcePacket {
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

    public static ResumeLevelC2S decode(PacketByteBuf buf) {
        return new ResumeLevelC2S(buf.readString(), buf.readString(), buf.readBoolean());
    }
}
