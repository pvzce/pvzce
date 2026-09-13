package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * "Load the run that is already saved for this level."
 *
 * <p>Sent as the answer to {@link LevelSavePromptS2C}, and the only way to enter a level with
 * a save: the card bar and the team come from the save, so nothing about them travels here.
 *
 * <p>The three entry packets are separate types rather than one packet with a flag, because
 * the flag <em>is</em> the whole message: the server used to receive a {@code restart}
 * boolean beside a "confirmed" boolean it tracked itself, and had to reconstruct which of
 * "continue", "restart" and "start with these cards" the player had actually chosen.
 */
public record ContinueLevelC2S(String levelId, String worldName) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeString(levelId);
        buf.writeString(worldName);
    }

    public static ContinueLevelC2S decode(PacketByteBuf buf) {
        return new ContinueLevelC2S(buf.readString(), buf.readString());
    }
}
