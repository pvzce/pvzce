package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

import java.util.List;

/**
 * "Throw the saved run away and start this level over."
 *
 * <p>An explicit choice, so the server deletes the save instead of asking about it. Carries
 * a card bar because the two ways to reach it - the pause menu's 重新开始, which picks the
 * level's own cards again, and the save prompt's 重新开始, which asks for new ones - both
 * know which cards the fresh run should use.
 *
 * <p>The save prompt's "restart" opens the seed chooser first, so it arrives as
 * {@link PlayLevelC2S} with {@code restart()} set on that packet instead; this one is for the
 * restart that needs no chooser.
 */
public record RestartLevelC2S(String levelId, String worldName,
                              List<String> selectedSeeds) implements PvzcePacket {
    public RestartLevelC2S {
        selectedSeeds = List.copyOf(selectedSeeds);
    }

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeString(levelId);
        buf.writeString(worldName);
        buf.writeStringList(selectedSeeds);
    }

    public static RestartLevelC2S decode(PacketByteBuf buf) {
        return new RestartLevelC2S(buf.readString(), buf.readString(), buf.readStringList());
    }
}
