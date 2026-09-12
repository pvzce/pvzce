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
    /**
     * The tracks a level's music can play on, spelled exactly as level data and the client's
     * {@code PvzceMusicController} spell them.
     *
     * <p>Named here because both ends need them: the client indexes its per-track state by
     * them, and the server has to name the level-owned tracks when it silences a finished run.
     * {@link #TRACK_MENU} is not one of those - menus stop themselves when a level starts.
     */
    public static final String TRACK_MENU = "menu";
    public static final String TRACK_BACKGROUND = "background";
    public static final String TRACK_BATTLE = "battle";
    public static final String TRACK_STINGER = "stinger";

    /** Fade used when a finished run's level music is silenced. */
    public static final float RESET_FADE_SECONDS = 0.5F;

    /** A cue that stops {@code track}, fading it out over {@link #RESET_FADE_SECONDS}. */
    public static MusicEventS2C reset(String track) {
        return new MusicEventS2C(track, "", false, true, 0F, RESET_FADE_SECONDS);
    }

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
