package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

/**
 * The plant a glove is holding, or nothing.
 *
 * <p>The carry is simulation state - the server decides what was lifted and whether the cell
 * the player clicks next can take it - but the player has to be able to <em>see</em> it: the
 * plant is off the board while it is carried, and without this the cursor would be holding
 * something invisible. The id travels rather than the entity, so the client draws the very
 * art the definition names and a mod's plant needs no extra work.
 *
 * <p>Sent on every change - lifted, put down, and forgotten (a carry that timed out, or a
 * plant that was eaten while it was held) - so "the client thinks something is carried" and
 * "the server is carrying something" cannot drift apart.
 */
public record CarrySyncS2C(String plantId) implements PvzcePacket {
    /** Nothing is carried. */
    public static final CarrySyncS2C NONE = new CarrySyncS2C("");

    public static final PacketStruct.Codec<CarrySyncS2C> CODEC = PacketStruct.<CarrySyncS2C>builder()
            .field(CarrySyncS2C::plantId, PacketByteBuf::writeString, PacketByteBuf::readString)
            .build(values -> new CarrySyncS2C((String) values.get(0)));

    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static CarrySyncS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }

    /** True when a plant is being carried. */
    public boolean carrying() {
        return plantId != null && !plantId.isEmpty();
    }

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }
}
