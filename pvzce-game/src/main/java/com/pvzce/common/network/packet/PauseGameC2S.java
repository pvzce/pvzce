package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * Client request to pause/resume the single-player level simulation. The
 * server freezes the level tick while {@code paused} is true; the client still
 * renders the board and the pause dialog.
 */
public record PauseGameC2S(boolean paused) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeBoolean(paused);
    }

    public static PauseGameC2S decode(PacketByteBuf buf) {
        return new PauseGameC2S(buf.readBoolean());
    }
}
