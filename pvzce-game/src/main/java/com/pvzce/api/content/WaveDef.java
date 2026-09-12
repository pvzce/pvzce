package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

import java.util.List;
import java.util.Locale;

/**
 * One data-driven wave.
 *
 * <p>Timing is relative to the previous wave's spawn moment (the first wave is
 * relative to level start). {@code delay} is measured in ticks. The wave meter
 * fills over that delay; when full the wave triggers and its zombies are
 * released one by one every {@value #SPAWN_INTERVAL_TICKS} ticks.</p>
 */
public record WaveDef(
        WaveType type,
        int delay,
        int warningTicks,
        List<Entry> entries,
        int spawnIntervalTicks
) {
    /** A wave that uses {@link #DEFAULT_SPAWN_INTERVAL_TICKS}. */
    public WaveDef(WaveType type, int delay, int warningTicks, List<Entry> entries) {
        this(type, delay, warningTicks, entries, DEFAULT_SPAWN_INTERVAL_TICKS);
    }

    /** Ticks between two of this wave's zombies, never below a quarter second. */
    public int spawnInterval() {
        return Math.max(15, spawnIntervalTicks);
    }
    /**
     * How long a wave waits between two of its zombies when it does not say.
     *
     * <p>The original's pacing: an easy level trickles, a late one pours. At the 15 ticks
     * this used to be, a three-zombie wave emptied in half a second, which read as "they
     * all appeared at once".
     */
    public static final int DEFAULT_SPAWN_INTERVAL_TICKS = 300;
    public static final int DEFAULT_WARNING_TICKS = 600;

    public enum WaveType {
        SMALL,
        HUGE,
        FINAL;

        public static final Codec<WaveType> CODEC = Codec.STRING.xmap(
                name -> valueOf(name.trim().toUpperCase(Locale.ROOT)),
                type -> type.name().toLowerCase(Locale.ROOT)
        );

        public boolean isHuge() {
            return this == HUGE || this == FINAL;
        }
    }

    /** Exact zombie composition of one wave. */
    public record Entry(Identifier id, int count) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Identifier.CODEC.fieldOf("id").forGetter(Entry::id),
                Codec.INT.optionalFieldOf("count", 1).forGetter(Entry::count)
        ).apply(i, Entry::new));
    }

    public static final Codec<WaveDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            WaveType.CODEC.optionalFieldOf("type", WaveType.SMALL).forGetter(WaveDef::type),
            Codec.INT.fieldOf("delay").forGetter(WaveDef::delay),
            Codec.INT.optionalFieldOf("warning_ticks", DEFAULT_WARNING_TICKS).forGetter(WaveDef::warningTicks),
            Entry.CODEC.listOf().fieldOf("entries").forGetter(WaveDef::entries),
            Codec.INT.optionalFieldOf("spawn_interval", DEFAULT_SPAWN_INTERVAL_TICKS)
                    .forGetter(WaveDef::spawnIntervalTicks)
    ).apply(i, WaveDef::new));

    public int totalZombies() {
        return entries.stream().mapToInt(Entry::count).sum();
    }

    public boolean isHuge() {
        return type.isHuge();
    }

    /**
     * This wave with a different type, keeping every other field.
     *
     * <p>The canonical constructor is deliberately not used here: it takes the four
     * "default interval" arguments and silently replaces a level's own
     * {@code spawn_interval}. {@code normalizeWaves} calls this for the last wave of every
     * level, so that would quietly reset the final wave's pacing - the one wave whose
     * pacing a level tunes hardest.
     */
    public WaveDef asType(WaveType type) {
        return new WaveDef(type, delay, warningTicks, entries, spawnIntervalTicks);
    }
}
