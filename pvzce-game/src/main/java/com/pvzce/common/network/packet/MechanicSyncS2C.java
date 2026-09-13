package com.pvzce.common.network.packet;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;
import io.netty.buffer.Unpooled;

/**
 * A level mechanic's own runtime state, addressed by mechanic id.
 *
 * <p>This is the second half of "a mechanic owns its data": the definitions travel once
 * inside {@link LevelPayload#mechanics()}, and whatever a mechanic has to keep streaming
 * afterwards travels here. The protocol table does not grow when a mechanic is added, and
 * a mod's mechanic can stream state without registering a packet of its own.
 *
 * <p>The payload is opaque bytes because the shape belongs to the mechanic. That is a
 * deliberate exception to the "declare the fields once" rule that {@link PacketStruct}
 * enforces for every other packet: the mechanic declares its payload's fields once, in its
 * own {@code PacketStruct.Codec} (see {@code ConveyorMechanic.BarState.CODEC}), and both
 * the server and the client encode and decode through that single declaration.
 */
public record MechanicSyncS2C(Identifier mechanic, byte[] payload) implements PvzcePacket {
    public MechanicSyncS2C {
        payload = payload == null ? new byte[0] : payload.clone();
    }

    /** Encodes one mechanic's state with the codec it declared. */
    public static <S> MechanicSyncS2C of(Identifier mechanic, PacketStruct.Codec<S> codec, S state) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        codec.encode(state, buf);
        byte[] bytes = new byte[buf.readableBytes()];
        buf.delegate().readBytes(bytes);
        return new MechanicSyncS2C(mechanic, bytes);
    }

    /** A buffer over the payload, for the receiving side's own codec. */
    public PacketByteBuf payloadBuffer() {
        return new PacketByteBuf(Unpooled.wrappedBuffer(payload));
    }

    /**
     * Records compare their arrays by identity, which would make two identical syncs
     * unequal - and a round-trip test would pass or fail depending on the allocator.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof MechanicSyncS2C sync
                && mechanic.equals(sync.mechanic)
                && java.util.Arrays.equals(payload, sync.payload);
    }

    @Override
    public int hashCode() {
        return 31 * mechanic.hashCode() + java.util.Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "MechanicSyncS2C[mechanic=" + mechanic + ", payload=" + payload.length + " bytes]";
    }

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeIdentifier(mechanic);
        buf.writeVarInt(payload.length);
        buf.delegate().writeBytes(payload);
    }

    public static MechanicSyncS2C decode(PacketByteBuf buf) {
        Identifier mechanic = buf.readIdentifierOrNull();
        if (mechanic == null) {
            throw new PacketByteBuf.DecoderException("Mechanic sync without a mechanic id");
        }
        int length = buf.readVarInt();
        if (length < 0 || length > buf.readableBytes()) {
            throw new PacketByteBuf.DecoderException("Mechanic sync payload of " + length
                    + " bytes does not fit in " + buf.readableBytes() + " remaining");
        }
        byte[] payload = new byte[length];
        buf.delegate().readBytes(payload);
        return new MechanicSyncS2C(mechanic, payload);
    }
}
