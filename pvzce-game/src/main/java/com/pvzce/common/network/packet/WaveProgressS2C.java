package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
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

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeInt(currentWave);
        buf.writeInt(totalWaves);
        buf.writeFloat(progress);
        buf.writeBoolean(warningActive);
        buf.writeBoolean(finalWarning);
    }

    public static WaveProgressS2C decode(PacketByteBuf buf) {
        return new WaveProgressS2C(buf.readInt(), buf.readInt(), buf.readFloat(),
                buf.readBoolean(), buf.readBoolean());
    }
}
