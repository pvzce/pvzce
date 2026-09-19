package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

/**
 * Periodic server heartbeat for the F3 debug overlay (TPS measurement).
 *
 * <p>Deliberately carries no tick rate: {@link GameSpeedS2C} is the single owner
 * of the target rate. Both used to write the same client field at different
 * frequencies, so the last heartbeat won and the displayed rate silently
 * depended on packet arrival order.
 */
public record DebugInfoS2C(long tickCount, boolean frozen, boolean sprinting) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    public static final PacketStruct.Codec<DebugInfoS2C> CODEC = PacketStruct.<DebugInfoS2C>builder()
    .field(DebugInfoS2C::tickCount, PacketByteBuf::writeLong, PacketByteBuf::readLong)
    .field(DebugInfoS2C::frozen, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
    .field(DebugInfoS2C::sprinting, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
            .build(values -> new DebugInfoS2C((Long) values.get(0), (Boolean) values.get(1), (Boolean) values.get(2)));

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static DebugInfoS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
