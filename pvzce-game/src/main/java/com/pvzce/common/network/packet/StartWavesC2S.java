package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * The player has finished preparing and wants the waves to start.
 *
 * <p>Carries nothing: "start" is the whole message, and the level is the one that knows whether it
 * was still preparing when this arrived (a second press, or one that raced the level's own
 * countdown, is a no-op rather than a second start). See {@code PreparationMechanic}.
 */
public record StartWavesC2S() implements PvzcePacket {
    public static final StartWavesC2S INSTANCE = new StartWavesC2S();

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
    }

    public static StartWavesC2S decode(PacketByteBuf buf) {
        return INSTANCE;
    }
}
