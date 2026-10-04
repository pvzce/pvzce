package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

/**
 * Server-triggered background-music cue. A non-empty event plays on the named
 * track; {@code stop} (or an empty event) stops that track.
 *
 * @param manyZombiesLayer this cue is a <em>layer</em> of the level's song rather than the song
 *                         itself: it plays in sync with the other cues of the same batch, and the
 *                         client keeps it silent until the lawn is crowded (more than
 *                         {@code PvzceConstants.MANY_ZOMBIES_LAYER_COUNT} zombies) and fades it in
 *                         when it is. The night roof's drums are the one user of this - two files
 *                         of the same length, started on the same tick, are one performance
 */
public record MusicEventS2C(String track, String event, boolean loop, boolean stop, float volume,
                            float fadeSeconds, boolean preload, boolean manyZombiesLayer)
        implements PvzcePacket {
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
        return new MusicEventS2C(track, "", false, true, 0F, RESET_FADE_SECONDS, false, false);
    }

    /**
     * A cue that only asks the client to get ready: decode {@code event} now, play nothing.
     *
     * <p>Music is decoded whole, on the thread that asks to play it, and a three-minute track takes
     * about a tenth of a second to come out of stb_vorbis and into a buffer. That tenth is the
     * difference between a chart that can be played and one that cannot: the song started nine ticks
     * (149 ms, measured) after the tick its cue was written for while the notes are judged against
     * that tick - so a player following the <em>music</em> could never do better than FAIR, and the
     * level's own opening theme started late too (see {@code 踩坑清单} 147).
     *
     * <p>Sent with the level's other music packets, before {@code LevelInitS2C}, so the decode
     * happens while the client is still loading - which is also the moment nothing is judged.
     */
    public static MusicEventS2C preload(String track, String event) {
        return new MusicEventS2C(track, event, false, false, 0F, 0F, true, false);
    }

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    public static final PacketStruct.Codec<MusicEventS2C> CODEC = PacketStruct.<MusicEventS2C>builder()
    .field(MusicEventS2C::track, PacketByteBuf::writeString, PacketByteBuf::readString)
    .field(MusicEventS2C::event, PacketByteBuf::writeString, PacketByteBuf::readString)
    .field(MusicEventS2C::loop, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
    .field(MusicEventS2C::stop, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
    .field(MusicEventS2C::volume, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
    .field(MusicEventS2C::fadeSeconds, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
    .field(MusicEventS2C::preload, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
    .field(MusicEventS2C::manyZombiesLayer, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
            .build(values -> new MusicEventS2C((String) values.get(0), (String) values.get(1), (Boolean) values.get(2), (Boolean) values.get(3), (Float) values.get(4), (Float) values.get(5), (Boolean) values.get(6), (Boolean) values.get(7)));

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static MusicEventS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
