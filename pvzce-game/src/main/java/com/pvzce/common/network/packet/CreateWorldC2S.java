package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * "I created this world, give it a profile."
 *
 * <p>The world directory itself is created on the client, because the world list
 * is read straight off disk; this packet carries the one part of world creation
 * that must not be: the record of what that world has unlocked and how many coins
 * it holds. {@code unlockAll} is the sandbox checkbox - a world that starts with
 * every card, including cards added by later content.
 */
public record CreateWorldC2S(String worldName, boolean unlockAll) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeString(worldName);
        buf.writeBoolean(unlockAll);
    }

    public static CreateWorldC2S decode(PacketByteBuf buf) {
        return new CreateWorldC2S(buf.readString(), buf.readBoolean());
    }
}
