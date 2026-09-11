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
        List<Entry> entries
) {
    /** Zombies are released one at a time on this fixed interval within a wave. */
    public static final int SPAWN_INTERVAL_TICKS = 15;
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
            Entry.CODEC.listOf().fieldOf("entries").forGetter(WaveDef::entries)
    ).apply(i, WaveDef::new));

    public int totalZombies() {
        return entries.stream().mapToInt(Entry::count).sum();
    }

    public boolean isHuge() {
        return type.isHuge();
    }

    public WaveDef asType(WaveType type) {
        return new WaveDef(type, delay, warningTicks, entries);
    }
}
