package com.pvzce.common.network.packet;

import com.pvzce.common.level.WorldPosition;
import com.pvzce.common.level.SceneBoard;
import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

/**
 * Client-side presentation event: a particle at a world position (x,y,elevation), an optional locally played
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
                             String ripple, float rippleStrength, float elevation, String surfaceId) implements PvzcePacket {
    public EffectEventS2C(String particle, float x, float y, String sound, float volume, float pitch,
                          String ripple, float rippleStrength) {
        this(particle, x, y, sound, volume, pitch, ripple, rippleStrength, 0F,
                SceneBoard.DEFAULT_SURFACE);
    }
    public WorldPosition position() {
        return new WorldPosition(x, y, elevation);
    }

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

    public static final PacketStruct.Codec<EffectEventS2C> CODEC = PacketStruct.<EffectEventS2C>builder()
    .field(EffectEventS2C::particle, PacketByteBuf::writeString, PacketByteBuf::readString)
    .field(EffectEventS2C::x, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
    .field(EffectEventS2C::y, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
    .field(EffectEventS2C::sound, PacketByteBuf::writeString, PacketByteBuf::readString)
    .field(EffectEventS2C::volume, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
    .field(EffectEventS2C::pitch, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
    .field(EffectEventS2C::ripple, PacketByteBuf::writeString, PacketByteBuf::readString)
    .field(EffectEventS2C::rippleStrength, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
    .field(EffectEventS2C::elevation, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
    .field(EffectEventS2C::surfaceId, PacketByteBuf::writeString, PacketByteBuf::readString)
            .build(values -> new EffectEventS2C((String) values.get(0), (Float) values.get(1), (Float) values.get(2), (String) values.get(3), (Float) values.get(4), (Float) values.get(5), (String) values.get(6), (Float) values.get(7), (Float) values.get(8), (String) values.get(9)));

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static EffectEventS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
