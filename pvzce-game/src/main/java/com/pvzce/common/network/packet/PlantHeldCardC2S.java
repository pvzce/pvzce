package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * Plants the seed packet the player is carrying on a cell.
 *
 * <p>Its own request rather than a {@code PlacePlantC2S} with a made-up slot index: the card in
 * hand came out of a container, is not in the bar, costs no sun and starts no cooldown, and a
 * packet that named a bar slot would have to say all of that with a sentinel. The cell is the
 * target; "which card" is the server's own state (see {@code HeldCardS2C}).
 */
public record PlantHeldCardC2S(int gridX, int gridY) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeInt(gridX);
        buf.writeInt(gridY);
    }

    public static PlantHeldCardC2S decode(PacketByteBuf buf) {
        return new PlantHeldCardC2S(buf.readInt(), buf.readInt());
    }
}
