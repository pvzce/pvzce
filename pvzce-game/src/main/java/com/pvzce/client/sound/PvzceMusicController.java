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
    /**
     * Which track the level's layered music is on, or {@code -1} for "this level has no layer".
     *
     * <p>One layer per level is the whole need: the night roof's drums are a second performance of
     * the same song, and a level with two layers would be a level whose music is a mixing desk.
     */
    private int layerTrack = -1;
    /** The layer's own written volume, which it is faded up to when the lawn is crowded. */
    private float layerVolume = 1F;
    /** Whether the lawn is crowded enough for the layer to be heard. */
    private boolean manyZombies;

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
        playCue(TRACK_MENU, event, true, false, 1F, 0.6F, false);
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

    /**
     * Gets an event ready to play, without playing it: decode now, silence until cued.
     *
     * <p>The level's music is decoded while the level loads rather than when its cue arrives - a
     * three-minute track costs about a tenth of a second of stb_vorbis, which is nine ticks of a
     * rhythm chart's clock (see {@code 踩坑清单} 147).
     */
    public void preload(String event) {
        if (event == null || event.isEmpty()) {
            return;
        }
        sound.preload(event);
        tracePreload(event);
    }

    /** Enters a level: menu track stops, background starts on grasswalk. */
    public void startLevel(String defaultMusic) {
        stopCue(TRACK_MENU, 0.3F);
        stopCue(TRACK_STINGER, 0.2F);
        playCue(TRACK_BACKGROUND, defaultMusic, true, false, 0.85F, 1F, false);
    }

    public void leaveLevel() {
        layerTrack = -1;
        layerVolume = 1F;
        manyZombies = false;
        silenceForTheRoad(TRACK_BACKGROUND, 0.5F);
        silenceForTheRoad(TRACK_BATTLE, 0.5F);
        silenceForTheRoad(TRACK_STINGER, 0.5F);
    }

    /**
     * Stops one track on the way out of a level, and does not take the track state's word for it.
     *
     * <p>{@link #stopCue} fades out whatever the track <em>knows</em> it is playing, which is the
     * right thing for a hand-over between two cues and not enough for this one: a track can believe
     * it is silent while one of its sources still holds a cue - that is exactly what a paused
     * one-shot used to do (see {@link SoundEngine#musicSourceActive}). Leaving a level is the one
     * moment the player must hear nothing, so when the state had nothing to fade, both of the
     * track's sources are stopped outright. A track that did have something keeps its fade: the
     * belt is for the case where the braces are wrong, not a replacement for them.
     */
    private void silenceForTheRoad(String trackName, float fadeSeconds) {
        TrackState state = tracks[trackIndex(trackName)];
        boolean owned = state.currentSource >= 0 || state.incomingSource >= 0;
        stopCue(trackName, fadeSeconds);
        if (!owned) {
            int first = trackIndex(trackName) * SOURCES_PER_TRACK;
            sound.stopMusicSource(first);
            sound.stopMusicSource(first + 1);
        }
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

    /**
     * Applies a server-sent music cue ({@code /level} music timeline).
     *
     * @param manyZombiesLayer this cue is a layer of a song rather than a song: it plays on its
     *                         own track, in step with the cue it was sent beside, and is heard
     *                         only while the lawn is crowded (see {@link #setManyZombiesLayer})
     */
    public void playCue(String trackName, String event, boolean loop, boolean stop, float volume,
                        float fadeSeconds, boolean manyZombiesLayer) {
        int track = trackIndex(trackName);
        if (stop || event == null || event.isEmpty()) {
            stopCue(trackName, Math.max(0F, fadeSeconds));
            if (manyZombiesLayer) {
                layerTrack = -1;
            }
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
        if (manyZombiesLayer) {
            // Remembered before it starts: the crowd may already be there by the time the layer
            // arrives - a client joining a run in progress - and the theme's own cue, which comes
            // first in the batch, has to have something to start it silent with either way.
            layerTrack = track;
            layerVolume = safeVolume;
            safeVolume = manyZombies ? safeVolume : 0F;
        }
        sound.playOnMusicSource(incomingSource, event, safeFade > 0F && state.currentSource >= 0 ? 0F : safeVolume, loop);
        state.beginFade(incomingSource, event, loop, safeVolume, safeFade, now());
        state.targetVolume = safeVolume;
        state.lastVolumeStepNanos = now();
        trace(trackName, event);
    }

    /**
     * Tells the layered track whether the lawn is crowded, and fades it in or out accordingly.
     *
     * <p>Called every frame from the level screen rather than driven by a packet per zombie: the
     * count changes on almost every tick of a busy wave, and what the mix needs is only the two
     * transitions. The layer is <em>not</em> stopped when the crowd thins - it keeps playing at
     * zero gain, which is what keeps it in step with the song; starting it again when the lawn
     * fills up would put the drums back on bar one while the music was somewhere else.
     */
    public void setManyZombiesLayer(boolean crowded) {
        if (crowded == manyZombies) {
            return;
        }
        manyZombies = crowded;
        if (layerTrack >= 0) {
            tracks[layerTrack].targetVolume = crowded ? layerVolume : 0F;
            tracks[layerTrack].lastVolumeStepNanos = now();
        }
        if (Boolean.getBoolean("pvzce.traceMusic")) {
            LOGGER.info("music trace: layered track {} {}", layerTrack, crowded ? "in (crowded)" : "out");
        }
    }

    /** True while the layered track is being heard; diagnostics and tests. */
    public boolean manyZombiesLayerAudible() {
        return manyZombies && layerTrack >= 0
                && tracks[layerTrack].currentSource >= 0 && tracks[layerTrack].currentVolume > 0F;
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

    /** One line for a preload, which changes no track's state and would otherwise be invisible. */
    private void tracePreload(String event) {
        if (Boolean.getBoolean("pvzce.traceMusic")) {
            LOGGER.info("music trace: (preload) {}", event);
        }
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
            trace(trackName, "(already silent)");
            return;
        }
        if (safeFade <= 0F) {
            sound.stopMusicSource(state.currentSource);
            state.reset();
            trace(trackName, "(stop)");
            return;
        }
        state.beginFade(-1, null, false, 0F, safeFade, now());
        trace(trackName, "(stopping over " + safeFade + "s)");
    }

    public void playWinLose(boolean win) {
        stopCue(TRACK_BACKGROUND, 0.8F);
        stopCue(TRACK_BATTLE, 0.8F);
        stopCue(TRACK_STINGER, 0.2F);
        Identifier stinger = win ? PvzceSounds.MUSIC_WIN : PvzceSounds.MUSIC_LOSE;
        playCue(TRACK_STINGER, stinger.toString(), false, false, 1F, 0.8F, false);
    }

    public void stopAll() {
        layerTrack = -1;
        manyZombies = false;
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
        /**
         * How long the layered track takes to come in or go out.
         *
         * <p>Two and a half seconds: long enough that the drums arrive as a swell rather than as a
         * switch, short enough that "the lawn just filled up" is heard as the reason. A hard cut
         * would be a click in the middle of a bar.
         */
        private static final float LAYER_FADE_SECONDS = 2.5F;

        int currentSource = -1;
        String currentEvent;
        boolean currentLoop;
        float currentVolume = 1F;
        /**
         * Where {@link #currentVolume} is heading while the track is not crossfading, for the
         * layered track's fade in and out. Equal to {@code currentVolume} when nothing is moving.
         */
        float targetVolume = 1F;
        int incomingSource = -1;
        String incomingEvent;
        boolean incomingLoop;
        float incomingVolume;
        long fadeStartNanos;
        float fadeSeconds;
        boolean fading;
        /** When {@link #easeTargetVolume} last moved the volume; the fade's own clock. */
        long lastVolumeStepNanos;

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

        /**
         * Moves the playing source towards {@link #targetVolume} at the layer fade's own rate.
         *
         * <p>Rate-limited rather than eased with a start time, because the target may be moved
         * again before the last move finished - a wave that fills the lawn, thins and fills again
         * must not restart the fade from the beginning each time. Doing nothing while the two
         * agree keeps an ordinary track off the mixer entirely.
         */
        private void easeTargetVolume(SoundEngine sound) {
            if (currentSource < 0 || currentVolume == targetVolume) {
                return;
            }
            float step = (now() - lastVolumeStepNanos) / 1_000_000_000F / LAYER_FADE_SECONDS;
            float difference = targetVolume - currentVolume;
            currentVolume = Math.abs(difference) <= step ? targetVolume
                    : currentVolume + Math.signum(difference) * step;
            sound.setMusicSourceVolume(currentSource, currentVolume);
        }

        void reset() {
            currentSource = -1;
            currentEvent = null;
            incomingSource = -1;
            incomingEvent = null;
            incomingVolume = 0F;
            currentVolume = 1F;
            targetVolume = 1F;
            fading = false;
            fadeSeconds = 0F;
        }

        void tick(SoundEngine sound, long nowNanos) {
            if (!fading) {
                easeTargetVolume(sound);
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
                    if (incomingSource < 0) {
                        traceStop();
                    }
                }
                if (incomingSource >= 0) {
                    sound.setMusicSourceVolume(incomingSource, incomingVolume);
                    currentSource = incomingSource;
                    currentEvent = incomingEvent;
                    currentLoop = incomingLoop;
                    currentVolume = incomingVolume;
                    lastVolumeStepNanos = nowNanos;
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

        /** One line when a fade lands, so a track that never goes quiet can be seen doing it. */
        private void traceStop() {
            if (Boolean.getBoolean("pvzce.traceMusic")) {
                LOGGER.info("music trace: a fade-out finished and its source was stopped");
            }
        }

        private void finishOneShotIfDone(SoundEngine sound) {
            if (currentSource >= 0 && !currentLoop && !sound.musicSourceActive(currentSource)) {
                currentSource = -1;
                currentEvent = null;
                currentVolume = 1F;
            }
        }
    }
}
