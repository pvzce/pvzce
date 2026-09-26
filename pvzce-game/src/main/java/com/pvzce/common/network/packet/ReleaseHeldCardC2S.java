package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * Puts a carried seed packet back on the lawn.
 *
 * <p>A right-click, and the way out of a pick-up the player did not mean: the packet returns to
 * the cell it fell in with the time it had left, because the clock does not run while it is in
 * hand. No arguments - the only packet the player can be carrying is the server's own state.
 */
public record ReleaseHeldCardC2S() implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
    }

    public static ReleaseHeldCardC2S decode(PacketByteBuf buf) {
        return new ReleaseHeldCardC2S();
    }
}
