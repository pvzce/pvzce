package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * "I changed {@code config/pvzce-packs.json}; rebuild your side."
 *
 * <p>Carries nothing: the list is a file both sides read, and sending it would make a second copy
 * that could disagree with the one the server is about to load. The packet is the order of
 * operations, not the data - the server reloads first and answers with a message, and only then
 * does the client rebuild its own caches, because the level definitions and registries travel
 * from the server to the client (see {@code PvzceClient.reloadContent}).
 */
public record ReloadPacksC2S() implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
    }

    public static ReloadPacksC2S decode(PacketByteBuf buf) {
        return new ReloadPacksC2S();
    }
}
