package com.pvzce.client.sound;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.network.packet.MusicEventS2C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Background music controller with four independent named tracks:
 * {@code menu}, {@code background}, {@code battle}, and {@code stinger}.
 *
 * <p>Each track owns two OpenAL sources so a same-track switch can crossfade
 * (old fades out while the new one fades in). Loop tracks keep playing until
 * explicitly changed; one-shot tracks return to silence when they finish.</p>
 */
public final class PvzceMusicController {
    private static final Logger LOGGER = LoggerFactory.getLogger("PVZCE/Music");
    /**
     * The track names are the wire values the server sends ({@link MusicEventS2C}), spelled
     * the same way in level data - one list of them instead of three copies.
     */
    public static final String TRACK_MENU = MusicEventS2C.TRACK_MENU;
    public static final String TRACK_BACKGROUND = MusicEventS2C.TRACK_BACKGROUND;
    public static final String TRACK_BATTLE = MusicEventS2C.TRACK_BATTLE;
    public static final String TRACK_STINGER = MusicEventS2C.TRACK_STINGER;

    private static final int TRACK_COUNT = 4;
    private static final int SOURCES_PER_TRACK = 2;

    private final SoundEngine sound;
    private final TrackState[] tracks = new TrackState[TRACK_COUNT];

    public PvzceMusicController(SoundEngine sound) {
        this.sound = sound;
        for (int i = 0; i < TRACK_COUNT; i++) {
            tracks[i] = new TrackState();
        }
    }

    /** Per-frame fade/one-shot bookkeeping; call from the client loop. */
    public void tick() {
        long now = System.nanoTime();
        for (TrackState track : tracks) {
            track.tick(sound, now);
        }
    }

    /**
     * Holds or releases the music with the game.
     *
     * <p>Called by the in-game screen when its pause dialog opens and closes. The alternative -
     * stopping the track - is what the code did by omission before: the music played on under the
     * pause dialog, which is the one moment a player is listening for "did it stop".
     */
    public void setPaused(boolean paused) {
        sound.setMusicPaused(paused);
    }

    public void playMenu(String event) {
        playCue(TRACK_MENU, event, true, false, 1F, 0.6F);
    }

    /** Starts a menu track only when the menu track is currently silent. */
    /**
     * Plays a menu theme, unless that exact theme is already on the menu track.
     *
     * <p>The point of {@code ensure*} is idempotence: every screen calls it from
     * {@code init()}, which also runs on every window resize, and restarting the same
     * track there would stutter. It used to mean "only if the menu track is silent",
     * which quietly broke every hand-over between menus - leaving the award page's
     * Zen Garden playing over the level list, because that screen's request for the
     * chooser theme found the track busy and did nothing.
     */
    public void ensureMenu(String event) {
        TrackState state = tracks[trackIndex(TRACK_MENU)];
        if (event == null) {
            return;
        }
        String playing = state.incomingSource >= 0 ? state.incomingEvent : state.currentEvent;
        if (event.equals(playing) && (state.currentSource >= 0 || state.incomingSource >= 0)) {
            return;
        }
        playMenu(event);
    }

    /** Enters a level: menu track stops, background starts on grasswalk. */
    public void startLevel(String defaultMusic) {
        stopCue(TRACK_MENU, 0.3F);
        stopCue(TRACK_STINGER, 0.2F);
        playCue(TRACK_BACKGROUND, defaultMusic, true, false, 0.85F, 1F);
    }

    public void leaveLevel() {
        stopCue(TRACK_BACKGROUND, 0.5F);
        stopCue(TRACK_BATTLE, 0.5F);
        stopCue(TRACK_STINGER, 0.5F);
    }

    /**
     * The event a track is playing, or {@code null} when the track is silent.
     *
     * <p>While a crossfade runs this is the incoming event - what the player is about to
     * hear; a track that is only fading out keeps reporting the outgoing event until its fade
     * ends and the source stops. Diagnostics and tests; the client itself reads no state here.
     */
    public String currentEvent(String trackName) {
        TrackState state = tracks[trackIndex(trackName)];
        if (state.incomingSource >= 0) {
            return state.incomingEvent;
        }
        return state.currentSource >= 0 ? state.currentEvent : null;
    }

    /** Applies a server-sent music cue ({@code /level} music timeline). */
    public void playCue(String trackName, String event, boolean loop, boolean stop, float volume, float fadeSeconds) {
        int track = trackIndex(trackName);
        if (stop || event == null || event.isEmpty()) {
            stopCue(trackName, Math.max(0F, fadeSeconds));
            trace(trackName, "(stop)");
            return;
        }
        TrackState state = tracks[track];
        // Re-issuing the same loop is a no-op so page switches never restart music.
        if (loop && event.equals(state.currentEvent) && state.currentSource >= 0 && !state.fading) {
            return;
        }
        if (state.incomingSource >= 0) {
            sound.stopMusicSource(state.incomingSource);
            state.incomingSource = -1;
        }
        int incomingSource = unusedSource(state, track);
        sound.stopMusicSource(incomingSource);
        float safeVolume = Math.max(0F, Math.min(1F, volume));
        float safeFade = Math.max(0F, fadeSeconds);
        if (safeFade <= 0F && state.currentSource >= 0) {
            sound.stopMusicSource(state.currentSource);
            state.currentSource = -1;
            state.currentEvent = null;
        }
        sound.playOnMusicSource(incomingSource, event, safeFade > 0F && state.currentSource >= 0 ? 0F : safeVolume, loop);
        state.beginFade(incomingSource, event, loop, safeVolume, safeFade, now());
        trace(trackName, event);
    }

    /**
     * One line per track change, with what every track is playing.
     *
     * <p>For {@code -Dpvzce.traceMusic}: the four tracks are independent, so "two songs at once" is
     * a thing that happens without anything failing - a screen that asks for a menu theme while a
     * level is running gets both. That is invisible in a screenshot and only audible to whoever is
     * at the machine, which is what this line is for. Same family as {@code pvzce.traceSounds}.
     */
    private void trace(String trackName, String event) {
        if (!Boolean.getBoolean("pvzce.traceMusic")) {
            return;
        }
        LOGGER.info("music trace: {} = {} | menu={}, background={}, battle={}, stinger={}",
                trackName, event, currentEvent(TRACK_MENU), currentEvent(TRACK_BACKGROUND),
                currentEvent(TRACK_BATTLE), currentEvent(TRACK_STINGER));
    }

    /** Stops a track; an in-progress fade completes before the new cue starts. */
    public void stopCue(String trackName, float fadeSeconds) {
        TrackState state = tracks[trackIndex(trackName)];
        float safeFade = Math.max(0F, fadeSeconds);
        if (state.incomingSource >= 0) {
            sound.stopMusicSource(state.incomingSource);
            state.incomingSource = -1;
        }
        if (state.currentSource < 0) {
            state.reset();
            return;
        }
        if (safeFade <= 0F) {
            sound.stopMusicSource(state.currentSource);
            state.reset();
            return;
        }
        state.beginFade(-1, null, false, 0F, safeFade, now());
    }

    public void playWinLose(boolean win) {
        stopCue(TRACK_BACKGROUND, 0.8F);
        stopCue(TRACK_BATTLE, 0.8F);
        stopCue(TRACK_STINGER, 0.2F);
        Identifier stinger = win ? PvzceSounds.MUSIC_WIN : PvzceSounds.MUSIC_LOSE;
        playCue(TRACK_STINGER, stinger.toString(), false, false, 1F, 0.8F);
    }

    public void stopAll() {
        for (int i = 0; i < TRACK_COUNT; i++) {
            TrackState state = tracks[i];
            sound.stopMusicSource(i * SOURCES_PER_TRACK);
            sound.stopMusicSource(i * SOURCES_PER_TRACK + 1);
            state.reset();
        }
    }

    public void onVolumeChanged() {
        float[] volumes = new float[SoundEngine.MUSIC_SOURCE_COUNT];
        for (int i = 0; i < TRACK_COUNT; i++) {
            TrackState state = tracks[i];
            if (state.currentSource >= 0) {
                volumes[state.currentSource] = state.currentVolume;
            }
            if (state.incomingSource >= 0) {
                volumes[state.incomingSource] = state.incomingVolume;
            }
        }
        sound.refreshMusicSourceVolumes(volumes);
    }

    private static long now() {
        return System.nanoTime();
    }

    private static int trackIndex(String trackName) {
        return switch (trackName == null ? TRACK_BACKGROUND : trackName.toLowerCase(Locale.ROOT)) {
            case TRACK_MENU -> 0;
            case TRACK_BACKGROUND -> 1;
            case TRACK_BATTLE -> 2;
            case TRACK_STINGER -> 3;
            default -> 1;
        };
    }

    private static int unusedSource(TrackState state, int track) {
        int first = track * SOURCES_PER_TRACK;
        return state.currentSource == first ? first + 1 : first;
    }

    private static final class TrackState {
        int currentSource = -1;
        String currentEvent;
        boolean currentLoop;
        float currentVolume = 1F;
        int incomingSource = -1;
        String incomingEvent;
        boolean incomingLoop;
        float incomingVolume;
        long fadeStartNanos;
        float fadeSeconds;
        boolean fading;

        void beginFade(int incoming, String event, boolean loop, float incomingVol, float fadeSeconds, long nowNanos) {
            if (incoming >= 0 && incoming == currentSource) {
                currentVolume = incomingVol;
                return;
            }
            if (incomingSource >= 0) {
                // A queued cue is replaced by the newest request.
                fadeStartNanos = nowNanos;
                fadeSeconds = Math.max(0.001F, fadeSeconds);
                fading = true;
            } else if (currentSource < 0) {
                currentSource = incoming;
                currentEvent = event;
                currentLoop = loop;
                currentVolume = incomingVol;
                fadeStartNanos = 0;
                fadeSeconds = 0;
                fading = false;
                return;
            } else if (fadeSeconds <= 0F) {
                currentSource = incoming;
                currentEvent = event;
                currentLoop = loop;
                currentVolume = incomingVol;
                incomingSource = -1;
                incomingEvent = null;
                fadeStartNanos = 0;
                fadeSeconds = 0;
                fading = false;
                return;
            } else {
                incomingSource = incoming;
                incomingEvent = event;
                incomingLoop = loop;
                incomingVolume = incomingVol;
                fadeStartNanos = nowNanos;
                this.fadeSeconds = fadeSeconds;
                fading = true;
            }
        }

        void reset() {
            currentSource = -1;
            currentEvent = null;
            incomingSource = -1;
            incomingEvent = null;
            incomingVolume = 0F;
            currentVolume = 1F;
            fading = false;
            fadeSeconds = 0F;
        }

        void tick(SoundEngine sound, long nowNanos) {
            if (!fading) {
                finishOneShotIfDone(sound);
                return;
            }
            float progress = Math.min(1F, (nowNanos - fadeStartNanos) / 1_000_000_000F / Math.max(0.001F, fadeSeconds));
            float oldGain = currentSource >= 0 ? currentVolume * (1F - progress) : 0F;
            float newGain = incomingSource >= 0 ? incomingVolume * progress : 0F;
            if (currentSource >= 0) {
                sound.setMusicSourceVolume(currentSource, oldGain);
            }
            if (incomingSource >= 0) {
                sound.setMusicSourceVolume(incomingSource, newGain);
            }
            if (progress >= 1F) {
                if (currentSource >= 0) {
                    sound.stopMusicSource(currentSource);
                }
                if (incomingSource >= 0) {
                    sound.setMusicSourceVolume(incomingSource, incomingVolume);
                    currentSource = incomingSource;
                    currentEvent = incomingEvent;
                    currentLoop = incomingLoop;
                    currentVolume = incomingVolume;
                } else {
                    currentSource = -1;
                    currentEvent = null;
                    currentVolume = 1F;
                }
                incomingSource = -1;
                incomingEvent = null;
                incomingVolume = 0F;
                fading = false;
                fadeSeconds = 0F;
                finishOneShotIfDone(sound);
            }
        }

        private void finishOneShotIfDone(SoundEngine sound) {
            if (currentSource >= 0 && !currentLoop && !sound.isMusicSourcePlaying(currentSource)) {
                currentSource = -1;
                currentEvent = null;
                currentVolume = 1F;
            }
        }
    }
}
