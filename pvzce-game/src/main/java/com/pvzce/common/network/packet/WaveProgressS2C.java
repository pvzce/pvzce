package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

/**
 * PvZ-style wave progress: the position in the round, the meter's fill, and the warning state.
 *
 * <p>The wave numbers are <em>inside the round</em>, not inside the run: a round is what the
 * meter draws and what a player reads ("wave 7 of 12"), and on an endless level a run-wide
 * number would climb forever while the bar crawled. The round number rides along so the HUD can
 * say which round those waves belong to.
 *
 * @param currentWave   how many waves of the round have arrived
 * @param totalWaves    how many the round holds
 * @param progress      the meter's fill towards the next wave
 * @param warningActive whether a big wave is being announced
 * @param finalWarning  whether that big wave is the level's last one (never true on an endless
 *                      level, whose waves do not run out)
 * @param round         which round this is, one-based; always 1 on a level that does not
 *                      generate its waves
 */
public record WaveProgressS2C(
        int currentWave,
        int totalWaves,
        float progress,
        boolean warningActive,
        boolean finalWarning,
        int round
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
    .field(WaveProgressS2C::round, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .build(values -> new WaveProgressS2C((Integer) values.get(0), (Integer) values.get(1), (Float) values.get(2), (Boolean) values.get(3), (Boolean) values.get(4), (Integer) values.get(5)));

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static WaveProgressS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
