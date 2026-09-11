package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * Server-triggered background-music cue. A non-empty event plays on the named
 * track; {@code stop} (or an empty event) stops that track.
 */
public record MusicEventS2C(String track, String event, boolean loop, boolean stop, float volume,
                            float fadeSeconds) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeString(track);
        buf.writeString(event);
        buf.writeBoolean(loop);
        buf.writeBoolean(stop);
        buf.writeFloat(volume);
        buf.writeFloat(fadeSeconds);
    }

    public static MusicEventS2C decode(PacketByteBuf buf) {
        return new MusicEventS2C(buf.readString(), buf.readString(), buf.readBoolean(),
                buf.readBoolean(), buf.readFloat(), buf.readFloat());
    }
}
