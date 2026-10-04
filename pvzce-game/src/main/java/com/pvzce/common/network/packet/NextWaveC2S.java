package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * The player pressed the HUD's "next wave" button: cut the wait short and send the next wave.
 *
 * <p>Carries nothing: the request is the whole message. Whether it is honoured is the level's
 * answer and not the button's - the client only draws the button while the server says the wave
 * can be called, but a press can still arrive after the countdown has run out on its own, or
 * after the player was defeated, and the level is the one that knows which (see
 * {@code LevelServer.callNextWave}).
 */
public record NextWaveC2S() implements PvzcePacket {
    public static final NextWaveC2S INSTANCE = new NextWaveC2S();

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
    }

    public static NextWaveC2S decode(PacketByteBuf buf) {
        return INSTANCE;
    }
}
