package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

import java.util.List;

/**
 * The whole conveyor belt, replacing the receiver's card bar.
 *
 * <p>A belt is not a bar with holes in it: cards appear and disappear, and a card that
 * has been spent must leave the client's bar rather than sit there looking usable. An
 * upsert per card (what {@link SlotSyncS2C} does) cannot express "gone", so the belt
 * sends its entire contents whenever they change. Belts hold a handful of cards and
 * change a few times a minute, so the cost is nothing and the receiver needs no
 * bookkeeping.
 *
 * <p>{@code SlotInfo.index} is the card's belt id, not its position: positions shift
 * when the front card is spent, ids do not.
 */
public record BeltSyncS2C(List<SlotInfo> cards) implements PvzcePacket {
    public BeltSyncS2C {
        cards = List.copyOf(cards);
    }

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeList(cards, SlotInfo::encode);
    }

    public static BeltSyncS2C decode(PacketByteBuf buf) {
        return new BeltSyncS2C(buf.readList(SlotInfo::decode));
    }
}
