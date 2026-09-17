package com.pvzce.common.network.packet;

import com.pvzce.common.network.PacketByteBuf;

/**
 * One card in a player's card bar.
 *
 * <p>{@code defId} is the slot's <em>content</em> id (which plant/tool/resource the
 * card represents), not the slot registry id: the client passes the card index
 * back when placing, so the only thing it may assume about {@code defId} is that
 * it names the thing being drawn.
 *
 * <p>{@code cooldownTotal} is how long this card's cooldown lasts in this level (the
 * card's own number times the level's multiplier), and it travels beside
 * {@code cooldownLeft} because the two are read as a fraction: the bar draws the
 * recharge as "this share of the card is still unavailable". The client used to divide the
 * remainder by a hard-coded 300, which was only ever right for the cards whose cooldown
 * happened to be 300 - a Jalapeno's 3000-tick recharge filled its last tenth, and any level
 * that scaled cooldowns would have shown every card as partially charged the moment it was
 * spent. Zero means the card has no cooldown at all (the sun bank, a belt card).
 */
public record SlotInfo(int index, String defId, String kind, int costSun, int cooldownLeft,
                       int cooldownTotal, int usesLeft, boolean available) {
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

    /**
     * Convenience for cards with no cooldown and unlimited uses (tests, belt cards).
     *
     * <p>There is deliberately no overload that takes {@code usesLeft} without
     * {@code cooldownTotal}: with the two ints side by side, a caller that named one and
     * meant the other would compile and silently read the wrong number.
     */
    public SlotInfo(int index, String defId, String kind, int costSun, int cooldownLeft,
                    boolean available) {
        this(index, defId, kind, costSun, cooldownLeft, 0, UNLIMITED_USES, available);
    }

    /**
     * 0..1 of this card's cooldown still to run, or 0 when the card has none.
     *
     * <p>The single derivation the card bar draws from, so "how the recharge looks" cannot
     * disagree with "how long the card is unavailable": both are this one number.
     */
    public float cooldownRatio() {
        if (cooldownTotal <= 0 || cooldownLeft <= 0) {
            return 0F;
        }
        return Math.min(1F, cooldownLeft / (float) cooldownTotal);
    }

    public static final com.pvzce.common.network.PacketStruct.Codec<SlotInfo> CODEC =
            com.pvzce.common.network.PacketStruct.<SlotInfo>builder()
                    .field(SlotInfo::index, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                    .field(SlotInfo::defId, PacketByteBuf::writeString, PacketByteBuf::readString)
                    .field(SlotInfo::kind, PacketByteBuf::writeString, PacketByteBuf::readString)
                    .field(SlotInfo::costSun, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                    .field(SlotInfo::cooldownLeft, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                    .field(SlotInfo::cooldownTotal, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                    .field(SlotInfo::usesLeft, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                    .field(SlotInfo::available, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
                    .build(values -> new SlotInfo((Integer) values.get(0), (String) values.get(1),
                            (String) values.get(2), (Integer) values.get(3), (Integer) values.get(4),
                            (Integer) values.get(5), (Integer) values.get(6), (Boolean) values.get(7)));

    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static SlotInfo decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }

    public boolean limited() {
        return usesLeft != UNLIMITED_USES;
    }

    /** True while this card has uses left; an unlimited card always does. */
    public boolean hasUsesLeft() {
        return usesLeft == UNLIMITED_USES || usesLeft > 0;
    }
}
