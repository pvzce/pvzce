package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

/**
 * Server asks the client whether an existing per-level save should be
 * continued or restarted. A failed load is reported without a level init and
 * offers restart or return instead of continue.
 */
public record LevelSavePromptS2C(
        String levelId,
        String worldName,
        String levelName,
        int tickCount,
        int plantCount,
        int sun,
        boolean loadFailed
) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    public static final PacketStruct.Codec<LevelSavePromptS2C> CODEC = PacketStruct.<LevelSavePromptS2C>builder()
    .field(LevelSavePromptS2C::levelId, PacketByteBuf::writeString, PacketByteBuf::readString)
    .field(LevelSavePromptS2C::worldName, PacketByteBuf::writeString, PacketByteBuf::readString)
    .field(LevelSavePromptS2C::levelName, PacketByteBuf::writeString, PacketByteBuf::readString)
    .field(LevelSavePromptS2C::tickCount, PacketByteBuf::writeInt, PacketByteBuf::readInt)
    .field(LevelSavePromptS2C::plantCount, PacketByteBuf::writeInt, PacketByteBuf::readInt)
    .field(LevelSavePromptS2C::sun, PacketByteBuf::writeInt, PacketByteBuf::readInt)
    .field(LevelSavePromptS2C::loadFailed, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
            .build(values -> new LevelSavePromptS2C((String) values.get(0), (String) values.get(1), (String) values.get(2), (Integer) values.get(3), (Integer) values.get(4), (Integer) values.get(5), (Boolean) values.get(6)));

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static LevelSavePromptS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
