package com.pvzce.common.network.packet;

import com.pvzce.common.network.PacketByteBuf;

/**
 * One card in a player's card bar.
 *
 * <p>{@code defId} is the slot's <em>content</em> id (which plant/tool/resource the
 * card represents), not the slot registry id: the client passes the card index
 * back when placing, so the only thing it may assume about {@code defId} is that
 * it names the thing being drawn.
 */
public record SlotInfo(int index, String defId, String kind, int costSun, int cooldownLeft,
                       int usesLeft, boolean available) {
    /** {@code -1} means the card has unlimited uses. */
    public static final int UNLIMITED_USES = -1;

    /**
     * {@code -1} as a price means "this card has no price", which is not the same as
     * costing nothing: a conveyor-belt card is handed to the player rather than bought, and
     * a printed "0" would read as a price that happened to be free.
     */
    public static final int NO_PRICE = -1;

    /** True when the card's price must not be drawn at all. */
    public boolean priceless() {
        return costSun == NO_PRICE;
    }

    /** Convenience for unlimited cards (the common case). */
    public SlotInfo(int index, String defId, String kind, int costSun, int cooldownLeft, boolean available) {
        this(index, defId, kind, costSun, cooldownLeft, UNLIMITED_USES, available);
    }

    public static final com.pvzce.common.network.PacketStruct.Codec<SlotInfo> CODEC =
            com.pvzce.common.network.PacketStruct.<SlotInfo>builder()
                    .field(SlotInfo::index, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                    .field(SlotInfo::defId, PacketByteBuf::writeString, PacketByteBuf::readString)
                    .field(SlotInfo::kind, PacketByteBuf::writeString, PacketByteBuf::readString)
                    .field(SlotInfo::costSun, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                    .field(SlotInfo::cooldownLeft, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                    .field(SlotInfo::usesLeft, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                    .field(SlotInfo::available, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
                    .build(values -> new SlotInfo((Integer) values.get(0), (String) values.get(1),
                            (String) values.get(2), (Integer) values.get(3), (Integer) values.get(4),
                            (Integer) values.get(5), (Boolean) values.get(6)));

    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static SlotInfo decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }

    public boolean limited() {
        return usesLeft != UNLIMITED_USES;
    }
}
