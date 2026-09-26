package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * Client request to play this world on another difficulty tier.
 *
 * <p>{@code difficulty} is the tier's lowercase name ({@code easy} / {@code normal} / {@code hard} /
 * {@code hell}). An unknown name is refused rather than defaulted: a client that sent nonsense is
 * either out of date or modified, and silently giving it the original's difficulty would hide both.
 *
 * <p>It carries no world name. The profile being changed is the one the level list is already
 * working with, and the server is the only side that knows which that is - the same rule every
 * other profile command follows.
 */
public record SetDifficultyC2S(String difficulty) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeString(difficulty == null ? "" : difficulty);
    }

    public static SetDifficultyC2S decode(PacketByteBuf buf) {
        return new SetDifficultyC2S(buf.readString());
    }
}
