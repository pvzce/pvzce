package com.pvzce.api.content;

import com.pvzce.common.level.SceneBoard;
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
 * <p>Timing is relative to the previous wave's <em>trigger</em> (the first wave is relative to
 * level start). {@code delay} is measured in ticks. The wave meter fills over that delay; when
 * full the wave triggers and its zombies are released one by one every
 * {@value #DEFAULT_SPAWN_INTERVAL_TICKS} ticks unless the wave writes its own
 * {@code spawn_interval}.</p>
 *
 * <p>Two things can push an arrival later than {@code delay}, and both are deliberate: a wave
 * does not trigger while the one before it is still releasing its zombies (never two release
 * queues at once), and the opening waves hand their own pace to the player
 * (see {@link #holdUntilDead(int)}).</p>
 */
public record WaveDef(
        WaveType type,
        int delay,
        int warningTicks,
        List<Entry> entries,
        /**
         * Ticks between two of this wave's zombies, or empty to use
         * {@link #DEFAULT_SPAWN_INTERVAL_TICKS}.
         *
         * <p>Three-state on purpose, like {@code holdUntilDeadTicks} and {@code MowerData.rows}:
         * empty is not the same fact as an explicit {@code 300}, because the opening waves read
         * "did this wave say how fast it wants to be" as "does it want the death gate". See
         * {@link #holdUntilDead(int)}.
         */
        Optional<Integer> spawnIntervalTicks,
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
    /**
     * A wave that wrote {@code spawnIntervalTicks} in its file: it paces itself, and it keeps no
     * opening death gate.
     *
     * <p>The two facts travel together, which is why this is a named factory rather than a
     * five-argument constructor: writing {@code spawn_interval} is exactly what
     * {@link #holdUntilDead(int)} reads as "this wave has already decided how fast it wants to
     * be". A wave with no interval of its own - built with the four-argument constructor, or
     * parsed from a file without the field - is the one the gate is for.
     */
    public static WaveDef declaringSpawnInterval(WaveType type, int delay, int warningTicks,
                                                 List<Entry> entries, int spawnIntervalTicks) {
        return new WaveDef(type, delay, warningTicks, entries,
                Optional.of(spawnIntervalTicks), Optional.empty());
    }

    /** A wave that uses {@link #DEFAULT_SPAWN_INTERVAL_TICKS} and the engine's opening rule. */
    public WaveDef(WaveType type, int delay, int warningTicks, List<Entry> entries) {
        this(type, delay, warningTicks, entries, Optional.empty(), Optional.empty());
    }

    public WaveDef {
        spawnIntervalTicks = spawnIntervalTicks == null ? Optional.empty() : spawnIntervalTicks;
        holdUntilDeadTicks = holdUntilDeadTicks == null ? Optional.empty() : holdUntilDeadTicks;
    }

    /** Ticks between two of this wave's zombies: the written one, or the default. */
    public int spawnInterval() {
        return Math.max(15, spawnIntervalTicks.orElse(DEFAULT_SPAWN_INTERVAL_TICKS));
    }

    /**
     * True when this wave wrote an interval of its own.
     *
     * <p>The one question {@link #holdUntilDead(int)} asks besides "is this an opening wave": a
     * wave that said how fast it wants to come out has already decided its own pace.
     */
    public boolean declaresSpawnInterval() {
        return spawnIntervalTicks.isPresent();
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
     * instead - see {@link #holdUntilDead(int)}, which is also where "unless the wave wrote an
     * interval of its own" lives.
     */
    public static final int EARLY_WAVE_COUNT = 2;
    /** How long an opening wave waits for its previous zombie before giving up on it. */
    public static final int DEFAULT_EARLY_HOLD_TICKS = 20 * PvzceConstants.TICKS_PER_SECOND;

    /**
     * How long this wave waits for its previous zombie to die before releasing the next.
     *
     * <p>Written in the file wins: {@code 0} means "do not wait at all" and any positive
     * number is the cap. Unwritten, the rule is the opening's alone - the first
     * {@value #EARLY_WAVE_COUNT} waves of a level, only when they are {@link WaveType#SMALL}
     * ones, and only when they wrote no interval of their own. A huge or final wave is the level
     * pouring everything it has, and holding those back one at a time would turn a two-minute
     * wave into twenty.
     *
     * <p><b>Writing {@code spawn_interval} opts the wave out of the gate.</b> The gate answers
     * "how fast should this wave be", and it answers it by handing the question to the player's
     * kills. A wave that already said ({@code declaresSpawnInterval()}) has answered it: its
     * zombies are to come out on that clock whatever the player is doing. Without this the gate
     * silently overrode the number - which is how 1-5's second conehead came out at 50 s with a
     * {@code "spawn_interval": 15} sitting in the file, and why every shipped level's opening
     * wave had to be read as "twenty seconds per zombie" rather than as what it wrote.
     *
     * @param waveIndex this wave's position in the level, zero-based
     * @return ticks to wait for the previous zombie, or {@code 0} for no waiting
     */
    public int holdUntilDead(int waveIndex) {
        if (holdUntilDeadTicks.isPresent()) {
            return Math.max(0, holdUntilDeadTicks.get());
        }
        boolean opening = waveIndex >= 0 && waveIndex < EARLY_WAVE_COUNT && type == WaveType.SMALL;
        return opening && !declaresSpawnInterval() ? DEFAULT_EARLY_HOLD_TICKS : 0;
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

    /**
     * Exact zombie composition of one wave.
     *
     * @param id          the zombie's content id
     * @param count       how many of it
     * @param rows        the lanes it may arrive in, or empty for any lane
     * @param healthScale what this wave multiplies the zombie's own health by; {@code 1} for an
     *                    ordinary wave, and above one only for the waves an endless round
     *                    generates (see {@code EndlessScheduleDef.StatGrowth}). It lives on the
     *                    entry rather than on the level because it is a property of the wave a
     *                    zombie arrives in, and a level-wide zombie-health rule would be
     *                    rewritten under a running mutation's feet.
     */
    public record Entry(Identifier id, int count, List<Integer> rows, float healthScale, String surface) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Identifier.CODEC.fieldOf("id").forGetter(Entry::id),
                Codec.INT.optionalFieldOf("count", 1).forGetter(Entry::count),
                Codec.INT.listOf().optionalFieldOf("rows", List.of()).forGetter(Entry::rows),
                Codec.FLOAT.optionalFieldOf("health_scale", 1F).forGetter(Entry::healthScale),
                Codec.STRING.optionalFieldOf("surface", SceneBoard.DEFAULT_SURFACE)
                        .forGetter(Entry::surface)
        ).apply(i, Entry::new));

        public Entry(Identifier id, int count, List<Integer> rows, float healthScale) {
            this(id, count, rows, healthScale, SceneBoard.DEFAULT_SURFACE);
        }

        public Entry(Identifier id, int count, List<Integer> rows) {
            this(id, count, rows, 1F);
        }

        public Entry(Identifier id, int count) {
            this(id, count, List.of(), 1F);
        }

        public Entry {
            rows = rows == null ? List.of() : List.copyOf(rows);
            healthScale = healthScale > 0F ? healthScale : 1F;
            surface = surface == null ? SceneBoard.DEFAULT_SURFACE : surface;
        }

        /**
         * True when this entry says which lanes it arrives in.
         *
         * <p>Unwritten means "any lane", which is what every wave table written before the pool
         * meant and what the director does by default: it walks a shuffled list of every row.
         * A pool level is the case that needs the filter - a floatie zombie sent to a grass row
         * and a land zombie sent into the water are both wrong, and the second one drowns - so
         * the entry, not the level, is where "where does this one walk" belongs.
         */
        public boolean restrictedToRows() {
            return !rows.isEmpty();
        }
    }

    public static final Codec<WaveDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            WaveType.CODEC.optionalFieldOf("type", WaveType.SMALL).forGetter(WaveDef::type),
            Codec.INT.fieldOf("delay").forGetter(WaveDef::delay),
            Codec.INT.optionalFieldOf("warning_ticks", DEFAULT_WARNING_TICKS).forGetter(WaveDef::warningTicks),
            Entry.CODEC.listOf().fieldOf("entries").forGetter(WaveDef::entries),
            // No default value: "wrote no interval" and "wrote 300" are two different facts (see
            // the record's own note on the field), and a codec that folded them together would
            // hand every level in the game the opening death gate.
            Codec.INT.optionalFieldOf("spawn_interval").forGetter(WaveDef::spawnIntervalTicks),
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
     * <p>The canonical constructor's own arguments are all reused, the two optionals included:
     * {@code normalizeWaves} retypes the last wave of every level, and dropping either of those
     * on the way through would silently rewrite the one wave whose pacing a level tunes hardest
     * (and turn its opening gate back on).
     */
    public WaveDef asType(WaveType type) {
        return new WaveDef(type, delay, warningTicks, entries, spawnIntervalTicks,
                holdUntilDeadTicks);
    }
}
