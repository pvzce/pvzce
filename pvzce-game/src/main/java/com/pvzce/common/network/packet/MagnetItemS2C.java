package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

/** A metal object removed by the server, flying towards and then held by one magnet. */
public record MagnetItemS2C(int plantId, String item, float x, float y, int startTick,
                            int pullTicks, int holdTicks) implements PvzcePacket {
    public static final PacketStruct.Codec<MagnetItemS2C> CODEC = PacketStruct.<MagnetItemS2C>builder()
            .field(MagnetItemS2C::plantId, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(MagnetItemS2C::item, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(MagnetItemS2C::x, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
            .field(MagnetItemS2C::y, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
            .field(MagnetItemS2C::startTick, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(MagnetItemS2C::pullTicks, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(MagnetItemS2C::holdTicks, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .build(v -> new MagnetItemS2C((Integer) v.get(0), (String) v.get(1), (Float) v.get(2),
                    (Float) v.get(3), (Integer) v.get(4), (Integer) v.get(5), (Integer) v.get(6)));
    @Override public ConnectionDirection direction() { return ConnectionDirection.CLIENTBOUND; }
    @Override public void encode(PacketByteBuf buf) { CODEC.encode(this, buf); }
    public static MagnetItemS2C decode(PacketByteBuf buf) { return CODEC.decode(buf); }
}
