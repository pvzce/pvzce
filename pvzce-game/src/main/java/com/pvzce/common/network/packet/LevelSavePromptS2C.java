package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * Server asks the client whether an existing per-level save should be
 * continued or restarted. Sent instead of starting the level when a running
 * save exists and the player has not confirmed an action yet.
 */
public record LevelSavePromptS2C(
        String levelId,
        String worldName,
        String levelName,
        int tickCount,
        int plantCount,
        int sun
) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeString(levelId);
        buf.writeString(worldName);
        buf.writeString(levelName);
        buf.writeInt(tickCount);
        buf.writeInt(plantCount);
        buf.writeInt(sun);
    }

    public static LevelSavePromptS2C decode(PacketByteBuf buf) {
        return new LevelSavePromptS2C(buf.readString(), buf.readString(), buf.readString(),
                buf.readInt(), buf.readInt(), buf.readInt());
    }
}
