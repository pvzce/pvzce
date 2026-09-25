package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

import java.util.List;

/**
 * The mutations a level is running with, and what the client has to draw for them.
 *
 * <p>One packet for the whole feature rather than one per mutation: a mutation changes what the
 * <em>set</em> looks like (the panel lists them in arrival order, the "next one in N seconds"
 * line counts down against the level's clock, and two mutations may want the same overlay), so
 * the client is always told the whole picture. The list is small - fifty entries at the very
 * worst, each an id and a status.
 *
 * <p>Reversibility is the reason this exists at all: a mutation can be evicted, so the client
 * cannot derive the panel from the level's definition. The level says "this level mutates"; this
 * says what it has become.
 *
 * @param intervalTicks how long the level waits between two mutations - the panel's countdown,
 *                      so a player can see the next one coming rather than being ambushed
 * @param rollMultiplier the difficulty tier's strength multiplier, shown on the panel so
 *                      "×1.5" is readable as "and this is why"
 * @param cardBarKind    {@code deck} or {@code conveyor}: which bar the client should be drawing
 *                       right now, which a mutation can change mid-level
 * @param beltCapacity   how many cards the tray the client should draw holds - the mutation's
 *                       belt has no block in the level definition for the client to read it from
 *                       (which is what made it draw a one-card tray), so the server says it
 * @param effects        the union of every running mutation's {@code MutationEffects} bits
 * @param tools          the tools a mutation granted, in full: the client's click handler has to
 *                       know which one it is holding and on what terms, and it has no other way to
 *                       learn about a tool that is not in the level's file
 * @param entries        every mutation on the field, oldest first
 */
public record MutationStateS2C(
        String difficulty,
        int tierLimit,
        int intervalTicks,
        int ticksUntilNext,
        float rollMultiplier,
        String cardBarKind,
        int beltCapacity,
        int effects,
        List<ToolGrant> tools,
        List<Entry> entries
) implements PvzcePacket {
    /**
     * One tool a mutation handed over.
     *
     * <p>All four fields rather than just the id, because a granted tool is a runtime decision:
     * Whack-a-Zombie's mallet is free and instant, and the same hammer is a priced card elsewhere.
     * The client cannot read that off the tool's own definition, so the server says it.
     */
    public record ToolGrant(String tool, boolean isDefault, boolean free, int cooldownTicks) {
        public static final PacketStruct.Codec<ToolGrant> CODEC = PacketStruct.<ToolGrant>builder()
                .field(ToolGrant::tool, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(ToolGrant::isDefault, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
                .field(ToolGrant::free, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
                .field(ToolGrant::cooldownTicks, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                .build(values -> new ToolGrant((String) values.get(0), (Boolean) values.get(1),
                        (Boolean) values.get(2), (Integer) values.get(3)));
    }

    /** One mutation as the panel shows it. */
    public record Entry(String mutation, String status, float multiplier, String subject) {
        public static final PacketStruct.Codec<Entry> CODEC = PacketStruct.<Entry>builder()
                .field(Entry::mutation, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(Entry::status, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(Entry::multiplier, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
                .field(Entry::subject, PacketByteBuf::writeString, PacketByteBuf::readString)
                .build(values -> new Entry((String) values.get(0), (String) values.get(1),
                        (Float) values.get(2), (String) values.get(3)));
    }

    public static final PacketStruct.Codec<MutationStateS2C> CODEC =
            PacketStruct.<MutationStateS2C>builder()
                    .field(MutationStateS2C::difficulty, PacketByteBuf::writeString,
                            PacketByteBuf::readString)
                    .field(MutationStateS2C::tierLimit, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                    .field(MutationStateS2C::intervalTicks, PacketByteBuf::writeInt,
                            PacketByteBuf::readInt)
                    .field(MutationStateS2C::ticksUntilNext, PacketByteBuf::writeInt,
                            PacketByteBuf::readInt)
                    .field(MutationStateS2C::rollMultiplier, PacketByteBuf::writeFloat,
                            PacketByteBuf::readFloat)
                    .field(MutationStateS2C::cardBarKind, PacketByteBuf::writeString,
                            PacketByteBuf::readString)
                    .field(MutationStateS2C::beltCapacity, PacketByteBuf::writeInt,
                            PacketByteBuf::readInt)
                    .field(MutationStateS2C::effects, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                    .list(MutationStateS2C::tools, ToolGrant.CODEC::encode, ToolGrant.CODEC::decode)
                    .list(MutationStateS2C::entries, Entry.CODEC::encode, Entry.CODEC::decode)
                    .build(values -> new MutationStateS2C(
                            (String) values.get(0),
                            (Integer) values.get(1),
                            (Integer) values.get(2),
                            (Integer) values.get(3),
                            (Float) values.get(4),
                            (String) values.get(5),
                            (Integer) values.get(6),
                            (Integer) values.get(7),
                            castTools(values.get(8)),
                            castEntries(values.get(9))));

    @SuppressWarnings("unchecked")
    private static List<ToolGrant> castTools(Object value) {
        return (List<ToolGrant>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Entry> castEntries(Object value) {
        return (List<Entry>) value;
    }

    public MutationStateS2C {
        difficulty = difficulty == null ? "" : difficulty;
        cardBarKind = cardBarKind == null ? "" : cardBarKind;
        tools = tools == null ? List.of() : List.copyOf(tools);
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static MutationStateS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
