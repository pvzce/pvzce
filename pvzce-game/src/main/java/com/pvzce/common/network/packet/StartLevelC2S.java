package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

import java.util.List;

/**
 * Enter a level with an explicit seed selection. Unlike {@link RequestLevelC2S},
 * this carries the player's chosen card list; an empty list means the player
 * intentionally starts with no cards.
 */
public record StartLevelC2S(String levelId, String worldName, boolean restart,
                            List<String> selectedSeeds) implements PvzcePacket {
    public StartLevelC2S {
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
        buf.writeBoolean(restart);
        buf.writeStringList(selectedSeeds);
    }

    public static StartLevelC2S decode(PacketByteBuf buf) {
        return new StartLevelC2S(buf.readString(), buf.readString(), buf.readBoolean(), buf.readStringList());
    }
}
