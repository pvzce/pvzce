package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

/**
 * The seed packet the player is carrying, or nothing.
 *
 * <p>The card drop's half of what {@link CarrySyncS2C} says for the glove, and a separate packet
 * rather than a flag on that one because the two carries are <em>answered differently</em>: a
 * carried plant goes back down through the glove card, and a carried packet through its own
 * request. One packet with a "which kind" field would be two facts in one place.
 *
 * <p>The entity id travels with the card so the client can stop drawing the packet where it fell
 * and draw it in the player's hand instead. The card id is the packet's own definition id, so the
 * client draws the very art the card names.
 *
 * <p>Sent on every change - picked up, planted, put back - so "the client is holding something"
 * and "the server is holding something" cannot drift apart.
 */
public record HeldCardS2C(int entityId, String cardId) implements PvzcePacket {
    /** Nothing is held. */
    public static final HeldCardS2C NONE = new HeldCardS2C(-1, "");

    public static final PacketStruct.Codec<HeldCardS2C> CODEC = PacketStruct.<HeldCardS2C>builder()
            .field(HeldCardS2C::entityId, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(HeldCardS2C::cardId, PacketByteBuf::writeString, PacketByteBuf::readString)
            .build(values -> new HeldCardS2C((Integer) values.get(0), (String) values.get(1)));

    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static HeldCardS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }

    /** True when a seed packet is in the player's hand. */
    public boolean holding() {
        return cardId != null && !cardId.isEmpty();
    }

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }
}
