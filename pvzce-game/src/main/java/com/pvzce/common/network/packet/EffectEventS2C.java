package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * Client-side presentation event: a particle at (x,y), an optional locally played
 * sound, and an optional liquid ripple. The server never streams audio itself, and
 * it never streams a ripple's animation either - it only says that one happened.
 *
 * <p>{@code ripple} is the liquid to disturb (content id, e.g. {@code pvzce:water})
 * and {@code rippleStrength} is 0..1. Both default to "no ripple", so the many
 * callers that only want a particle or a sound are unaffected. A ripple travels on
 * this packet rather than one of its own because it is the same kind of thing as
 * the particle and the sound already here: a presentation event at a world
 * position, driven by the server, that the simulation never reads back.
 */
public record EffectEventS2C(String particle, float x, float y, String sound, float volume, float pitch,
                             String ripple, float rippleStrength) implements PvzcePacket {
    public EffectEventS2C(String particle, float x, float y) {
        this(particle, x, y, "", 1F, 1F, "", 0F);
    }

    public EffectEventS2C(String particle, float x, float y, String sound, float volume, float pitch) {
        this(particle, x, y, sound, volume, pitch, "", 0F);
    }

    /** True when this event also disturbs a liquid surface. */
    public boolean hasRipple() {
        return !ripple.isEmpty() && rippleStrength > 0F;
    }

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeString(particle);
        buf.writeFloat(x);
        buf.writeFloat(y);
        buf.writeString(sound);
        buf.writeFloat(volume);
        buf.writeFloat(pitch);
        buf.writeString(ripple);
        buf.writeFloat(rippleStrength);
    }

    public static EffectEventS2C decode(PacketByteBuf buf) {
        return new EffectEventS2C(buf.readString(), buf.readFloat(), buf.readFloat(),
                buf.readString(), buf.readFloat(), buf.readFloat(),
                buf.readString(), buf.readFloat());
    }
}
