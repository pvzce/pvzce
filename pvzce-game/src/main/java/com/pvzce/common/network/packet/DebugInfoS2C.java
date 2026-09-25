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
 *
 * <p>{@code levelTick} is the running <em>level's</em> own tick counter, which is not
 * {@code tickCount}: the latter is the server's clock and keeps counting across levels, so a
 * client that timed anything per level against it would see every moment of a new level already
 * passed. The tutorial's timed lines are written against the level's counter, so it travels here -
 * one integer on a heartbeat that already exists, rather than a packet of its own.
 */
public record DebugInfoS2C(long tickCount, int levelTick, boolean frozen, boolean sprinting)
        implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    public static final PacketStruct.Codec<DebugInfoS2C> CODEC = PacketStruct.<DebugInfoS2C>builder()
    .field(DebugInfoS2C::tickCount, PacketByteBuf::writeLong, PacketByteBuf::readLong)
    .field(DebugInfoS2C::levelTick, PacketByteBuf::writeInt, PacketByteBuf::readInt)
    .field(DebugInfoS2C::frozen, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
    .field(DebugInfoS2C::sprinting, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
            .build(values -> new DebugInfoS2C((Long) values.get(0), (Integer) values.get(1),
                    (Boolean) values.get(2), (Boolean) values.get(3)));

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static DebugInfoS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
