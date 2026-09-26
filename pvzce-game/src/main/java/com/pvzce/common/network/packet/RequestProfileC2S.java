package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * Client asks for the world's profile: the wallet, the card slots and the unlocks.
 *
 * <p>Exists because the profile used to travel <em>only</em> with the level list, and the shop is
 * reachable without ever opening that list: the title screen's corner tray goes straight there. On
 * a fresh session the client has no profile yet - {@code PvzceClient.setCurrentWorld} resets it
 * whenever the world changes, precisely so one world's coins are never shown while another world's
 * list loads - so the shop drew "0" until the player started a game (which requests the list) and
 * came back.
 *
 * <p>The world is named rather than implied, for the same reason {@code RequestLevelListC2S} names
 * it: the menu's world is a client-side choice, and a request that left the server to guess would
 * answer about whichever world was asked for last.
 */
public record RequestProfileC2S(String worldName) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeString(worldName);
    }

    public static RequestProfileC2S decode(PacketByteBuf buf) {
        return new RequestProfileC2S(buf.readString());
    }
}
