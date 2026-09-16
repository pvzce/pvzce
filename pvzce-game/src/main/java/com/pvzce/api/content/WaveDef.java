package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One data-driven wave.
 *
 * <p>Timing is relative to the previous wave's spawn moment (the first wave is
 * relative to level start). {@code delay} is measured in ticks. The wave meter
 * fills over that delay; when full the wave triggers and its zombies are
 * released one by one every {@value #SPAWN_INTERVAL_TICKS} ticks.</p>
 *
 * <p>The opening waves are the exception: a level's first
 * {@value #EARLY_WAVE_COUNT} small waves release their zombies one at a time,
 * waiting for each one to die (see {@link #holdUntilDead(int)}).</p>
 */
public record WaveDef(
        WaveType type,
        int delay,
        int warningTicks,
        List<Entry> entries,
        int spawnIntervalTicks,
        /**
         * How long the next zombie waits for the previous one to die, or empty to follow the
         * level's own rule.
         *
         * <p>Three-state on purpose, like {@code MowerData.rows}: empty means "whatever the
         * opening waves do by default", {@code 0} means "pace me by {@code spawn_interval}
         * and nothing else", and a positive number is that many ticks. A single int could
         * not say both "unwritten" and "explicitly off", and the two mean opposite things to
         * a wave that has been authored to pour.
         */
        Optional<Integer> holdUntilDeadTicks
) {
    /** A wave paced by {@link #spawnIntervalTicks}, with the default death gate. */
    public WaveDef(WaveType type, int delay, int warningTicks, List<Entry> entries,
                   int spawnIntervalTicks) {
        this(type, delay, warningTicks, entries, spawnIntervalTicks, Optional.empty());
    }

    /** A wave that uses {@link #DEFAULT_SPAWN_INTERVAL_TICKS}. */
    public WaveDef(WaveType type, int delay, int warningTicks, List<Entry> entries) {
        this(type, delay, warningTicks, entries, DEFAULT_SPAWN_INTERVAL_TICKS, Optional.empty());
    }

    public WaveDef {
        holdUntilDeadTicks = holdUntilDeadTicks == null ? Optional.empty() : holdUntilDeadTicks;
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

    /**
     * How many waves at the start of a level pace themselves by the player's kills.
     *
     * <p>The opening is where the player is still building: two zombies arriving inside ten
     * seconds is not something a one-plant lawn answers, and the level cannot know how fast
     * the player is. Waiting for the previous one to die makes the pace follow the player
     * instead - see {@link #holdUntilDead(int)}.
     */
    public static final int EARLY_WAVE_COUNT = 2;
    /** How long an opening wave waits for its previous zombie before giving up on it. */
    public static final int DEFAULT_EARLY_HOLD_TICKS = 20 * PvzceConstants.TICKS_PER_SECOND;

    /**
     * How long this wave waits for its previous zombie to die before releasing the next.
     *
     * <p>Written in the file wins: {@code 0} means "do not wait at all" and any positive
     * number is the cap. Unwritten, the rule is the opening's alone - the first
     * {@value #EARLY_WAVE_COUNT} waves of a level, and only when they are
     * {@link WaveType#SMALL} ones. A huge or final wave is the level pouring everything it
     * has, and holding those back one at a time would turn a two-minute wave into twenty.
     *
     * @param waveIndex this wave's position in the level, zero-based
     * @return ticks to wait for the previous zombie, or {@code 0} for no waiting
     */
    public int holdUntilDead(int waveIndex) {
        if (holdUntilDeadTicks.isPresent()) {
            return Math.max(0, holdUntilDeadTicks.get());
        }
        boolean opening = waveIndex >= 0 && waveIndex < EARLY_WAVE_COUNT && type == WaveType.SMALL;
        return opening ? DEFAULT_EARLY_HOLD_TICKS : 0;
    }

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
                    .forGetter(WaveDef::spawnIntervalTicks),
            Codec.INT.optionalFieldOf("hold_until_dead").forGetter(WaveDef::holdUntilDeadTicks)
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
     * pacing a level tunes hardest. The death gate is carried for the same reason.
     */
    public WaveDef asType(WaveType type) {
        return new WaveDef(type, delay, warningTicks, entries, spawnIntervalTicks,
                holdUntilDeadTicks);
    }
}
