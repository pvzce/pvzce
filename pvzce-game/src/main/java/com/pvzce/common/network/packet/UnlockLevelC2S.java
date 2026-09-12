package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * "Buy this level."
 *
 * <p>A level whose {@code unlock} block names a {@code cost} can be bought outright; the
 * purchase is recorded in the world's profile, so it is paid once. The server re-derives
 * the price and re-checks the wallet from its own copy of the level definition - this
 * packet only says <em>which</em> level in <em>which</em> world, never how much - so a
 * modified client cannot buy anything cheaply.
 *
 * <p>{@code worldName} travels with the packet because a purchase is per world and the
 * menus run while no level is loaded, so the server has no current world to fall back on.
 * Without it the wallet that was charged was whichever world the server last defaulted to,
 * and the level the player was looking at stayed locked.
 */
public record UnlockLevelC2S(String levelId, String worldName) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeString(levelId);
        buf.writeString(worldName);
    }

    public static UnlockLevelC2S decode(PacketByteBuf buf) {
        return new UnlockLevelC2S(buf.readString(), buf.readString());
    }
}
