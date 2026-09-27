package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * Puts a zombie on the lawn: I, Zombie's click.
 *
 * <p>Field for field the same message as {@code PlacePlantC2S} - which card, which cell - and a
 * packet of its own because it is a different game: the card is looked up in the zombie registry
 * rather than the plant one, and the sender has to be the side that owns the zombies. Folding the
 * two into one packet would have made "which registry does this id go in" a field of the message,
 * which is a decision the client would then be making.
 */
public record PlaceZombieC2S(int slotIndex, int gridX, int gridY) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeInt(slotIndex);
        buf.writeInt(gridX);
        buf.writeInt(gridY);
    }

    public static PlaceZombieC2S decode(PacketByteBuf buf) {
        return new PlaceZombieC2S(buf.readInt(), buf.readInt(), buf.readInt());
    }
}
