package com.pvzce.client.sound;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.resource.PackResource;
import com.pvzce.common.resource.PvzceResourceManager;
import com.pvzce.common.util.MathUtil;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * OpenAL sound engine. Event ids come from assets/<ns>/sounds.json; OGG
 * buffers are decoded with STB Vorbis.
 *
 * <p>Events may contain several weighted sound files; a random variant is
 * picked per play and a small random pitch offset is applied. Repeated plays
 * of the same event are throttled to avoid bite/hit sound spam.</p>
 */
public final class SoundEngine implements AutoCloseable {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("PVZCE/Sound");
    public static final int MAX_SFX_SOURCES = 16;
    public static final int MUSIC_SOURCE_COUNT = 8;
    /**
     * True while every music source is held paused.
     *
     * <p>Pausing rather than stopping is the whole point: a stopped source loses its playback
     * position, so a level's music would restart from the top every time the player opened the
     * pause dialog - which is the difference between "the game is paused" and "the song is over".
     */
    private boolean musicPaused;
    private static final long SFX_MIN_INTERVAL_NANOS = 130_000_000L;
    private static final Random RANDOM = new Random();

    private record SoundVariant(String file, int weight, float volume, float pitch) {
    }

    private record EventDefinition(List<SoundVariant> variants) {
    }

    private final PvzceResourceManager resources;
    private final Map<String, EventDefinition> events = new HashMap<>();
    private final Map<String, Integer> buffers = new HashMap<>();
    private final Map<String, Long> lastSfxPlay = new HashMap<>();
    /** Which event each one-shot player is currently playing; the duplicate guard. */
    private final Map<Integer, String> playingPaths = new HashMap<>();
    /**
     * The one-shot players currently held by a {@link #startLoop}, by OpenAL source id.
     *
     * <p>A loop that lived in the ring like any other sound would be killed by whatever plays
     * next ({@link #play} stops and rebinds the player it picks), so the ring has to know which
     * of its players are not free: {@link #nextSource} skips these, and only a full ring can
     * take one.
     */
    private final Map<Integer, String> loopingPaths = new HashMap<>();
    private final Map<String, Integer> repeats = new HashMap<>();
    private long traceWindowStart = System.nanoTime();
    private final int[] sfxSources = new int[MAX_SFX_SOURCES];
    private final int[] musicSources = new int[MUSIC_SOURCE_COUNT];
    /**
     * The one looping ambient player: the weather of a level (see {@code StormClientMechanic}).
     *
     * <p>Its own source rather than a slot in the one-shot ring, for the same reason the music
     * tracks have theirs: the ring recycles players, and {@link #play} stops and rebinds
     * whatever it finds - a loop living in the ring is a loop the next pea shot kills. One is
     * enough because a board has one weather.
     */
    private int ambientSource;
    /** The event the ambient player is looping, or {@code null}; what makes a restart a no-op. */
    private String ambientEvent;
    /** The volume the ambient loop was asked for, kept so a volume change can re-apply it. */
    private float ambientVolume = 1F;
    private long device;
    private long context;
    private int cursor;
    private boolean enabled;
    private float masterVolume = 1F;
    private float musicVolume = 0.7F;
    private float sfxVolume = 0.8F;

    public SoundEngine(PvzceResourceManager resources) {
        this.resources = resources;
        init();
    }

    private void init() {
        try {
            device = ALC10.alcOpenDevice((ByteBuffer) null);
            if (device == 0) {
                return;
            }
            ALCCapabilities deviceCaps = ALC.createCapabilities(device);
            context = ALC10.alcCreateContext(device, (IntBuffer) null);
            if (context == 0) {
                ALC10.alcCloseDevice(device);
                return;
            }
            ALC10.alcMakeContextCurrent(context);
            AL.createCapabilities(deviceCaps);
            for (int i = 0; i < MAX_SFX_SOURCES; i++) {
                sfxSources[i] = AL10.alGenSources();
            }
            for (int i = 0; i < MUSIC_SOURCE_COUNT; i++) {
                musicSources[i] = AL10.alGenSources();
            }
            ambientSource = AL10.alGenSources();
            enabled = true;
        } catch (Throwable t) {
            LOGGER.warn("Sound engine unavailable; the game runs silently.", t);
            enabled = false;
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public void setMasterVolume(float volume) {
        this.masterVolume = MathUtil.clamp01(volume);
        refreshAmbientVolume();
    }

    public void setMusicVolume(float volume) {
        this.musicVolume = MathUtil.clamp01(volume);
    }

    public void setSfxVolume(float volume) {
        this.sfxVolume = MathUtil.clamp01(volume);
        refreshAmbientVolume();
    }

    /**
     * Applies the current master and effect volumes to the running ambient loop.
     *
     * <p>The loop can be minutes long, so it cannot wait for its next start to pick up a volume
     * the player just changed; without this, dragging the volume slider during a storm does
     * nothing until the level is re-entered.
     */
    private void refreshAmbientVolume() {
        if (!enabled || ambientEvent == null) {
            return;
        }
        if (AL10.alGetSourcei(ambientSource, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
            return;
        }
        SoundVariant variant = pickVariant(definition(eventPath(ambientEvent)));
        if (variant != null) {
            AL10.alSourcef(ambientSource, AL10.AL_GAIN,
                    masterVolume * sfxVolume * ambientVolume * variant.volume());
        }
    }

    public float masterVolume() {
        return masterVolume;
    }

    public float musicVolume() {
        return musicVolume;
    }

    public float sfxVolume() {
        return sfxVolume;
    }

    /**
     * Plays an event such as {@code pvzce:sfx/plant/shoot_pea}.
     *
     * <p>Two guards, and both are about the same failure: a one-shot sound becoming an
     * endless one.
     *
     * <ol>
     *   <li><b>Rate limit</b> ({@link #SFX_MIN_INTERVAL_NANOS}): repeats of one event
     *       inside a frame or two are collapsed, which is what keeps a volley of peas
     *       from stacking into a buzz.</li>
     *   <li><b>One player per event</b> ({@link #nextSource}): an event that is asked to
     *       start again while its own sound is still audible is <em>dropped</em>, not
     *       layered. This is the guard that makes a loop audible as a single sound instead
     *       of a machine-gun: for the six-second huge-wave call it is six seconds of silence
     *       from that event, whatever asks for it.</li>
     * </ol>
     *
     * <p>Neither guard is a list of "sounds that may only play once" - the events a level
     * announces itself with have changed once already, and a list would have had to change
     * with them.
     */
    public void play(String soundId, float volume, float pitch) {
        if (!enabled || soundId == null || soundId.isEmpty()) {
            return;
        }
        String path = eventPath(soundId);
        long now = System.nanoTime();
        long last = lastSfxPlay.getOrDefault(path, 0L);
        if (now - last < SFX_MIN_INTERVAL_NANOS) {
            return;
        }
        if (Boolean.getBoolean("pvzce.traceSounds")) {
            repeats.merge(path, 1, Integer::sum);
            long window = now - traceWindowStart;
            if (window > 1_000_000_000L) {
                if (repeats.size() > 1 || repeats.values().stream().anyMatch(c -> c > 3)) {
                    LOGGER.info("sound trace: last second played {}", repeats);
                }
                repeats.clear();
                traceWindowStart = now;
            }
        }
        SoundVariant variant = pickVariant(definition(path));
        if (variant == null) {
            return;
        }
        int buffer = bufferForFile(variant.file());
        if (buffer == 0) {
            return;
        }
        int source = nextSource(path);
        if (source < 0) {
            return;
        }
        lastSfxPlay.put(path, now);
        // Stop before rebinding, and never hand a stale loop flag to a new buffer.
        //
        // A source is a ring buffer of MAX_SFX_SOURCES players, so the source a six-second
        // siren landed on is reused by whatever plays next. Binding a buffer to a source
        // that is still playing is implementation-defined - some drivers keep playing,
        // some keep the old playback offset - and the loop flag in particular sticks to the
        // source, not to the buffer: a source that was ever asked to loop loops whatever is
        // bound to it next, forever. That is a one-shot sound (the huge-wave call is the
        // long one people notice) turning into an endless one, which is why the stop is
        // unconditional rather than only when the source looks busy.
        AL10.alSourceStop(source);
        AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE);
        AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
        AL10.alSourcef(source, AL10.AL_GAIN, masterVolume * sfxVolume * volume * variant.volume());
        AL10.alSourcef(source, AL10.AL_PITCH, clampPitch(pitch * variant.pitch() * randomPitchFactor()));
        AL10.alSourcePlay(source);
        playingPaths.put(source, path);
    }

    /**
     * Starts a sound that repeats until {@link #stopLoop} is called, and returns its handle.
     *
     * <p>For a sound that belongs to a <em>thing</em> rather than to a moment: the
     * jack-in-the-box's music box, which plays under the whole of its walk and has to stop the
     * instant the box opens or the body dies. A one-shot cannot do that - six seconds of music
     * fired at the wrong end of a twenty-second walk is what this build used to do - and the
     * ambient player is no better (one board has one weather, and it is not per entity).
     *
     * <p>The handle is the OpenAL source id, which is what {@link #stopLoop} needs; {@code -1} means
     * nothing was started (the engine is off, the event is unknown, or every player is busy).
     * Deliberately no rate limit and no duplicate guard: this is asked for once per entity by a
     * caller that already knows whether it is playing.
     */
    public int startLoop(String soundId, float volume, float pitch) {
        if (!enabled || soundId == null || soundId.isEmpty()) {
            return -1;
        }
        String path = eventPath(soundId);
        SoundVariant variant = pickVariant(definition(path));
        if (variant == null) {
            return -1;
        }
        int buffer = bufferForFile(variant.file());
        if (buffer == 0) {
            return -1;
        }
        int source = nextSource(path, true);
        if (source < 0) {
            return -1;
        }
        AL10.alSourceStop(source);
        AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
        AL10.alSourcef(source, AL10.AL_GAIN, masterVolume * sfxVolume * volume * variant.volume());
        AL10.alSourcef(source, AL10.AL_PITCH, clampPitch(pitch * variant.pitch()));
        AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_TRUE);
        AL10.alSourcePlay(source);
        playingPaths.put(source, path);
        loopingPaths.put(source, path);
        return source;
    }

    /** Stops a loop started by {@link #startLoop}; a no-op for a handle that is not looping. */
    public void stopLoop(int handle) {
        releaseLoop(loopingPaths, handle, source -> {
            playingPaths.remove(source);
            AL10.alSourceStop(source);
            AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE);
        });
    }

    /** Audio handles are source ids, which are unrelated to a pool's array indices. */
    static boolean releaseLoop(Map<Integer, String> owned, int source, java.util.function.IntConsumer stop) {
        if (source <= 0 || owned.remove(source) == null) return false;
        stop.accept(source);
        return true;
    }

    /**
     * A free source for {@code path}, or {@code -1} when this event is already audible.
     *
     * <p>Without the second half, a sound asked to start again while it is still playing
     * simply layers on the next source in the ring - and a client that is asked for the
     * same event hundreds of times a second (a stuck emitter, a replayed packet, a
     * restored level that announces its last wave twice) turns one six-second call into a
     * permanent one: the ring cycling forever, which is what "the sound loops" means to a
     * player. Dropping the duplicate makes a loop sound like the single sound it is.
     *
     * <p>Deliberately not a rotation: the event is refused until its own player has
     * finished, so a legitimate repeat is never cut short - the rate limit in {@link #play}
     * already handles volleys.
     *
     * <p>A player held by {@link #startLoop} is skipped - walking past it would take a sound that
     * belongs to an entity away from it - so a ring with nothing else free answers {@code -1} to a
     * one-shot rather than cutting a loop short.
     */
    private int nextSource(String path) {
        return nextSource(path, false);
    }

    private int nextSource(String path, boolean forLoop) {
        int first = cursor % MAX_SFX_SOURCES;
        for (int i = 0; i < MAX_SFX_SOURCES; i++) {
            int candidate = (first + i) % MAX_SFX_SOURCES;
            int source = sfxSources[candidate];
            if (loopingPaths.containsKey(source)) {
                continue;
            }
            if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
                cursor = candidate + 1;
                return source;
            }
            if (path.equals(playingPaths.get(source))) {
                return -1;
            }
        }
        if (forLoop) {
            return -1;
        }
        // Every player is busy: take the ring's next slot, as before.
        // A busy loop remains owned by its entity even when every other source is busy.
        for (int i = 0; i < MAX_SFX_SOURCES; i++) {
            int slot = (first + i) % MAX_SFX_SOURCES;
            if (!loopingPaths.containsKey(sfxSources[slot])) {
                cursor = slot + 1;
                return sfxSources[slot];
            }
        }
        return -1;
    }

    /**
     * Starts an event on one of the music sources owned by the
     * {@link PvzceMusicController}.
     */
    public void playOnMusicSource(int musicSourceIndex, String soundId, float volume, boolean loop) {
        if (!enabled || soundId == null || soundId.isEmpty() || musicSourceIndex < 0
                || musicSourceIndex >= MUSIC_SOURCE_COUNT) {
            return;
        }
        SoundVariant variant = pickVariant(definition(eventPath(soundId)));
        if (variant == null) {
            return;
        }
        int buffer = bufferForFile(variant.file());
        if (buffer == 0) {
            return;
        }
        int source = musicSources[musicSourceIndex];
        // Same rule as the one-shot path: rebind from a stopped source. The controller
        // stops the source it is replacing, but a source whose last cue ended on its own
        // (a one-shot stinger) is still holding that buffer, and a track's two sources are
        // swapped on every crossfade.
        AL10.alSourceStop(source);
        AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
        AL10.alSourcef(source, AL10.AL_GAIN, masterVolume * musicVolume * volume * variant.volume());
        AL10.alSourcef(source, AL10.AL_PITCH, variant.pitch());
        AL10.alSourcei(source, AL10.AL_LOOPING, loop ? AL10.AL_TRUE : AL10.AL_FALSE);
        AL10.alSourcePlay(source);
    }

    /**
     * Decodes a sound into its buffer now, so that playing it later is immediate.
     *
     * <p>What it is for: a music file is decoded <em>whole</em>, on the thread that asks to play it,
     * and a three-minute track takes about a tenth of a second - nine ticks of a rhythm chart, which
     * the song would spend not being audible while the notes were already being judged. The level's
     * music is therefore decoded while the client is still loading it (see
     * {@code PvzceMusicController.preload} and {@code 踩坑清单} 147).
     *
     * <p>Synchronous, and deliberately so: it runs where a hitch costs nothing (the loading screen),
     * and the alternative - a worker thread - would have to make the OpenAL context current on that
     * thread and still leave the first play racing the decode.
     */
    public void preload(String soundId) {
        if (!enabled || soundId == null || soundId.isEmpty()) {
            return;
        }
        EventDefinition definition = definition(eventPath(soundId));
        for (SoundVariant variant : definition.variants()) {
            bufferForFile(variant.file());
        }
    }

    public void stopMusicSource(int musicSourceIndex) {
        if (enabled && musicSourceIndex >= 0 && musicSourceIndex < MUSIC_SOURCE_COUNT) {
            AL10.alSourceStop(musicSources[musicSourceIndex]);
        }
    }

    /**
     * Starts the level's looping ambient sound - the rain of a storm.
     *
     * <p>Idempotent, because the caller is a per-level overlay built while the level is being
     * entered and the question "is the rain already falling" has no good answer on the client:
     * a restart, a resize or a second entry would each start it again, and layered copies of a
     * rain loop are the sound of a broken engine. Asking for the event that is already playing
     * is therefore a no-op.
     *
     * <p>Deliberately outside the music controller: the rain is not a track. It does not
     * crossfade, it has no cue timeline, and it plays while the music track is silent - which is
     * exactly the arrangement the original's storm level has.
     */
    public void playAmbient(String soundId, float volume) {
        if (!enabled || soundId == null || soundId.isEmpty()) {
            return;
        }
        if (soundId.equals(ambientEvent)
                && AL10.alGetSourcei(ambientSource, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) {
            return;
        }
        String path = eventPath(soundId);
        SoundVariant variant = pickVariant(definition(path));
        if (variant == null) {
            return;
        }
        int buffer = bufferForFile(variant.file());
        if (buffer == 0) {
            return;
        }
        // Stop before rebinding: the loop flag sticks to the source rather than to the buffer,
        // and a source that was ever asked to loop loops whatever is bound to it next.
        AL10.alSourceStop(ambientSource);
        AL10.alSourcei(ambientSource, AL10.AL_LOOPING, AL10.AL_TRUE);
        AL10.alSourcei(ambientSource, AL10.AL_BUFFER, buffer);
        ambientVolume = MathUtil.clamp01(volume);
        AL10.alSourcef(ambientSource, AL10.AL_GAIN,
                masterVolume * sfxVolume * ambientVolume * variant.volume());
        AL10.alSourcef(ambientSource, AL10.AL_PITCH, variant.pitch());
        AL10.alSourcePlay(ambientSource);
        ambientEvent = soundId;
        traceAmbient("started " + soundId + " (" + bufferSamples(buffer) + " frames)");
    }

    /** Stops the ambient loop, if one is playing. Called when the player leaves the level. */
    /**
     * Holds or releases every music source.
     *
     * <p>One flag for all eight because the tracks are independent but the pause is not: a level
     * has background music and a stinger and they are paused because the <em>game</em> is paused.
     * The flag is also what a later {@code playMusic} consults, so a cue that arrives while the
     * game is paused does not start playing under the pause dialog.
     */
    public void setMusicPaused(boolean paused) {
        if (musicPaused == paused) {
            return;
        }
        musicPaused = paused;
        if (musicSources == null) {
            return;
        }
        for (int source : musicSources) {
            if (source == 0) {
                continue;
            }
            if (paused) {
                AL10.alSourcePause(source);
            } else {
                // Only the ones that were actually playing: OpenAL has no "resume what was
                // playing", and `alSourcePlay` on a stopped source would restart it from zero -
                // which is what the pause was for.
                int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
                if (state == AL10.AL_PAUSED) {
                    AL10.alSourcePlay(source);
                }
            }
        }
    }

    /** True while music is held paused; read by the controller so a cue can be deferred. */
    public boolean musicPaused() {
        return musicPaused;
    }

    public void stopAmbient() {
        if (!enabled) {
            return;
        }
        if (ambientEvent != null) {
            traceAmbient("stopped " + ambientEvent);
        }
        AL10.alSourceStop(ambientSource);
        // The flag is cleared as well, so the next level's one-shot sounds cannot inherit a
        // looping source even if something rebinds it without going through `playAmbient`.
        AL10.alSourcei(ambientSource, AL10.AL_LOOPING, AL10.AL_FALSE);
        ambientEvent = null;
    }

    /** The event currently looping on the ambient player, or {@code null}. Diagnostics and tests. */
    public String ambientEvent() {
        return ambientEvent;
    }

    /**
     * One line per ambient start and stop, for {@code -Dpvzce.traceAmbient}.
     *
     * <p>Because "the loop is choppy" has two completely different causes that sound alike: the
     * <em>asset</em> not being loopable (the seam every few seconds), and the <em>engine</em>
     * restarting the loop (a player that stops and starts, which needs the caller looked at). The
     * line says which one it is - a start per level is the asset, a start every few seconds is a
     * caller.
     */
    private static void traceAmbient(String what) {
        if (Boolean.getBoolean("pvzce.traceAmbient")) {
            LOGGER.info("ambient trace: {}", what);
        }
    }

    /** How many frames a buffer holds, or -1 when the driver will not say. Diagnostics only. */
    private static int bufferSamples(int buffer) {
        return AL10.alGetBufferi(buffer, AL10.AL_SIZE) / 2;
    }

    public void setMusicSourceVolume(int musicSourceIndex, float volume) {
        if (enabled && musicSourceIndex >= 0 && musicSourceIndex < MUSIC_SOURCE_COUNT) {
            AL10.alSourcef(musicSources[musicSourceIndex], AL10.AL_GAIN,
                    masterVolume * musicVolume * MathUtil.clamp01(volume));
        }
    }

    /**
     * Whether a music source still holds something: playing <em>or</em> paused.
     *
     * <p>Not "is it playing", which is the question this used to answer and the reason a song
     * could outlive its level. The pause dialog pauses every music source, and a paused source
     * reports {@code AL_PAUSED} - so a caller asking "has this one-shot finished" was told yes by
     * a track that was merely paused, forgot the source, and left it holding the song; releasing
     * the pause then played it again with nothing left that could ever stop it (see
     * {@code PvzceMusicController.finishOneShotIfDone} and {@code 踩坑清单} 144). Only
     * {@code AL_STOPPED} - the buffer ran out or somebody stopped it - is an ending.
     *
     * <p>Used for both questions the controller asks of a source: "may I forget it" and "should
     * a volume change reach it" (a paused source's gain has to follow the slider too, or the
     * music comes back at the old volume).
     */
    public boolean musicSourceActive(int musicSourceIndex) {
        if (!enabled || musicSourceIndex < 0 || musicSourceIndex >= MUSIC_SOURCE_COUNT) {
            return false;
        }
        int state = AL10.alGetSourcei(musicSources[musicSourceIndex], AL10.AL_SOURCE_STATE);
        return state == AL10.AL_PLAYING || state == AL10.AL_PAUSED;
    }

    /** Updates live music-source gain when the volume options change. */
    public void refreshMusicSourceVolumes(float[] volumes) {
        if (!enabled || volumes == null) {
            return;
        }
        for (int i = 0; i < Math.min(volumes.length, MUSIC_SOURCE_COUNT); i++) {
            if (musicSourceActive(i)) {
                setMusicSourceVolume(i, volumes[i]);
            }
        }
    }

    private EventDefinition definition(String eventPath) {
        EventDefinition cached = events.get(eventPath);
        if (cached != null) {
            return cached;
        }
        EventDefinition parsed = parseDefinition(eventPath);
        events.put(eventPath, parsed);
        return parsed;
    }

    private EventDefinition parseDefinition(String eventPath) {
        try {
            var meta = resources.getAsset(Identifier.withDefaultNamespace("sounds.json"));
            if (meta.isPresent()) {
                JsonObject root = JsonParser.parseString(meta.get().readString()).getAsJsonObject();
                JsonObject event = root.getAsJsonObject(eventPath);
                if (event != null && event.has("sounds")) {
                    List<SoundVariant> variants = new ArrayList<>();
                    for (JsonElement element : event.getAsJsonArray("sounds")) {
                        JsonObject sound = element.getAsJsonObject();
                        String name = sound.get("name").getAsString();
                        String file = name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
                        if (file.startsWith("sounds/")) {
                            file = file.substring("sounds/".length());
                        }
                        int weight = sound.has("weight") ? Math.max(1, sound.get("weight").getAsInt()) : 1;
                        float volume = sound.has("volume") ? sound.get("volume").getAsFloat() : 1F;
                        float pitch = sound.has("pitch") ? sound.get("pitch").getAsFloat() : 1F;
                        variants.add(new SoundVariant(file, weight, volume, pitch));
                    }
                    if (!variants.isEmpty()) {
                        return new EventDefinition(variants);
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed to parse sound event " + eventPath + "; using the file-name fallback.", t);
        }
        // Compatibility fallback: event id doubles as the sound file name.
        return new EventDefinition(List.of(new SoundVariant(eventPath, 1, 1F, 1F)));
    }

    private SoundVariant pickVariant(EventDefinition definition) {
        List<SoundVariant> variants = definition.variants();
        if (variants.isEmpty()) {
            return null;
        }
        if (variants.size() == 1) {
            return variants.get(0);
        }
        int total = 0;
        for (SoundVariant variant : variants) {
            total += variant.weight();
        }
        int roll = RANDOM.nextInt(Math.max(1, total));
        for (SoundVariant variant : variants) {
            roll -= variant.weight();
            if (roll < 0) {
                return variant;
            }
        }
        return variants.get(variants.size() - 1);
    }

    private int bufferForFile(String file) {
        Integer cached = buffers.get(file);
        if (cached != null) {
            return cached;
        }
        int buffer = loadBuffer(file);
        buffers.put(file, buffer);
        return buffer;
    }

    private int loadBuffer(String file) {
        try {
            var resource = resources.getResource("assets/pvzce/sounds/" + file + ".ogg");
            if (resource.isEmpty()) {
                Identifier id = Identifier.tryParse("pvzce:sounds/" + file);
                if (id != null) {
                    resource = resources.getAsset(id.withSuffix(".ogg"));
                }
            }
            if (resource.isEmpty()) {
                LOGGER.warn("Missing sound file {}", file);
                return 0;
            }
            long decodeStarted = System.nanoTime();
            int uploaded = uploadOgg(resource.get());
            long decodeMillis = (System.nanoTime() - decodeStarted) / 1_000_000L;
            if (Boolean.getBoolean("pvzce.traceMusic") || Boolean.getBoolean("pvzce.traceSounds")) {
                // A music file is decoded whole, on the thread that asked to play it, so this
                // number is how late the song starts relative to the tick its cue was written for
                // - which is the difference between a chart that can be played and one that cannot
                // (see 踩坑清单 147).
                LOGGER.info("[sound] decoded {} in {} ms", file, decodeMillis);
            }
            return uploaded;
        } catch (Throwable t) {
            LOGGER.warn("Failed to load sound file " + file, t);
            return 0;
        }
    }

    private int uploadOgg(PackResource resource) {
        byte[] bytes = resource.bytes();
        ByteBuffer input = BufferUtils.createByteBuffer(bytes.length);
        input.put(bytes).flip();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer channels = stack.mallocInt(1);
            IntBuffer sampleRate = stack.mallocInt(1);
            ShortBuffer samples = STBVorbis.stb_vorbis_decode_memory(input, channels, sampleRate);
            if (samples == null) {
                return 0;
            }
            int format = channels.get(0) == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
            int buffer = AL10.alGenBuffers();
            AL10.alBufferData(buffer, format, samples, sampleRate.get(0));
            return buffer;
        }
    }

    private static String eventPath(String soundId) {
        return soundId.contains(":") ? soundId.substring(soundId.indexOf(':') + 1) : soundId;
    }

    /** Pitch is clamped to the OpenAL-safe range in one place. */
    private static float clampPitch(float pitch) {
        return com.pvzce.common.util.MathUtil.clamp(pitch, MIN_PITCH, MAX_PITCH);
    }

    /** Lowest pitch OpenAL is asked for. */
    public static final float MIN_PITCH = 0.5F;
    /** Highest pitch OpenAL is asked for. */
    public static final float MAX_PITCH = 2.0F;

    private static float randomPitchFactor() {
        return 0.95F + RANDOM.nextFloat() * 0.10F;
    }

    /**
     * Drops the parsed event table and the decoded buffers, so the next play re-reads them.
     *
     * <p>Both caches are keyed by event path and file name and are never revalidated - which is
     * fine for a session that reads its packs once, and wrong the moment a pack changes the
     * {@code sounds.json} or a file behind an event: the old bytes would keep playing.
     */
    public void invalidate() {
        if (enabled) {
            for (int buffer : buffers.values()) {
                if (buffer != 0) {
                    AL10.alDeleteBuffers(buffer);
                }
            }
        }
        buffers.clear();
        events.clear();
    }

    @Override
    public void close() {
        if (!enabled) {
            return;
        }
        for (int source : sfxSources) {
            AL10.alDeleteSources(source);
        }
        for (int source : musicSources) {
            AL10.alDeleteSources(source);
        }
        invalidate();
        ALC10.alcDestroyContext(context);
        ALC10.alcCloseDevice(device);
        enabled = false;
    }
}
