package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * Picks up a seed packet lying on the lawn.
 *
 * <p>The entity, not the cell: two packets can be dropped in one cell by two containers broken a
 * moment apart, and "the one under the cursor" is the client's answer to which (it draws them).
 * The server checks that the entity is a packet and that nobody is already carrying one.
 */
public record PickUpCardC2S(int entityId) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeInt(entityId);
    }

    public static PickUpCardC2S decode(PacketByteBuf buf) {
        return new PickUpCardC2S(buf.readInt());
    }
}
