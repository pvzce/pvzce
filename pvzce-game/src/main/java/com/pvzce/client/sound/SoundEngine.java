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
    private final Map<String, Integer> repeats = new HashMap<>();
    private long traceWindowStart = System.nanoTime();
    private final int[] sfxSources = new int[MAX_SFX_SOURCES];
    private final int[] musicSources = new int[MUSIC_SOURCE_COUNT];
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
    }

    public void setMusicVolume(float volume) {
        this.musicVolume = MathUtil.clamp01(volume);
    }

    public void setSfxVolume(float volume) {
        this.sfxVolume = MathUtil.clamp01(volume);
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
     */
    private int nextSource(String path) {
        int first = cursor % MAX_SFX_SOURCES;
        for (int i = 0; i < MAX_SFX_SOURCES; i++) {
            int candidate = (first + i) % MAX_SFX_SOURCES;
            int source = sfxSources[candidate];
            if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
                cursor = candidate + 1;
                return source;
            }
            if (path.equals(playingPaths.get(source))) {
                return -1;
            }
        }
        // Every player is busy: take the ring's next slot, as before.
        cursor = first + 1;
        return sfxSources[first];
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

    public void stopMusicSource(int musicSourceIndex) {
        if (enabled && musicSourceIndex >= 0 && musicSourceIndex < MUSIC_SOURCE_COUNT) {
            AL10.alSourceStop(musicSources[musicSourceIndex]);
        }
    }

    public void setMusicSourceVolume(int musicSourceIndex, float volume) {
        if (enabled && musicSourceIndex >= 0 && musicSourceIndex < MUSIC_SOURCE_COUNT) {
            AL10.alSourcef(musicSources[musicSourceIndex], AL10.AL_GAIN,
                    masterVolume * musicVolume * MathUtil.clamp01(volume));
        }
    }

    public boolean isMusicSourcePlaying(int musicSourceIndex) {
        if (!enabled || musicSourceIndex < 0 || musicSourceIndex >= MUSIC_SOURCE_COUNT) {
            return false;
        }
        return AL10.alGetSourcei(musicSources[musicSourceIndex], AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING;
    }

    /** Updates live music-source gain when the volume options change. */
    public void refreshMusicSourceVolumes(float[] volumes) {
        if (!enabled || volumes == null) {
            return;
        }
        for (int i = 0; i < Math.min(volumes.length, MUSIC_SOURCE_COUNT); i++) {
            if (isMusicSourcePlaying(i)) {
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
            return uploadOgg(resource.get());
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
        for (int buffer : buffers.values()) {
            if (buffer != 0) {
                AL10.alDeleteBuffers(buffer);
            }
        }
        buffers.clear();
        events.clear();
        ALC10.alcDestroyContext(context);
        ALC10.alcCloseDevice(device);
        enabled = false;
    }
}
