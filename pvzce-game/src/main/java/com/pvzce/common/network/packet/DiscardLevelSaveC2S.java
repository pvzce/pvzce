package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * "Forget the run I left on disk for this level."
 *
 * <p>Sent when the player answers the save prompt with 重新开始, which is a decision about the
 * <em>saved run</em> rather than about the next one: the card chooser that follows can be backed
 * out of, and a player who does so has still said no to the run they were offered. Without this
 * the save sat there until the next run actually started, so backing out of the chooser and
 * picking the level again offered the same night the player had just refused.
 *
 * <p>The world is named as well as the level because the save lives under it; the server checks
 * both before deleting anything.
 */
public record DiscardLevelSaveC2S(String levelId, String worldName) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeString(levelId);
        buf.writeString(worldName);
    }

    public static DiscardLevelSaveC2S decode(PacketByteBuf buf) {
        return new DiscardLevelSaveC2S(buf.readString(), buf.readString());
    }
}
