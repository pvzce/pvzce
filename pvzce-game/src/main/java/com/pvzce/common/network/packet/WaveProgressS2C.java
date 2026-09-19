package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

/** PvZ-style wave progress: current wave index, wave-meter fill for the next wave, and warning state. */
public record WaveProgressS2C(
        int currentWave,
        int totalWaves,
        float progress,
        boolean warningActive,
        boolean finalWarning
) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    public static final PacketStruct.Codec<WaveProgressS2C> CODEC = PacketStruct.<WaveProgressS2C>builder()
    .field(WaveProgressS2C::currentWave, PacketByteBuf::writeInt, PacketByteBuf::readInt)
    .field(WaveProgressS2C::totalWaves, PacketByteBuf::writeInt, PacketByteBuf::readInt)
    .field(WaveProgressS2C::progress, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
    .field(WaveProgressS2C::warningActive, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
    .field(WaveProgressS2C::finalWarning, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
            .build(values -> new WaveProgressS2C((Integer) values.get(0), (Integer) values.get(1), (Float) values.get(2), (Boolean) values.get(3), (Boolean) values.get(4)));

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static WaveProgressS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
