package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.core.BuiltInRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * How a level's zombies arrive, wave by wave.
 *
 * <p>The block of the {@code pvzce:wave_pacing} level mechanic. Before it there was one way: the
 * wave table, where {@code delay} is the gap after the previous wave finished releasing and
 * {@code spawn_interval} is the gap between the zombies inside a wave. That is still the
 * default, and a level that says nothing plays exactly as it did - but it cannot express the
 * three things levels asked for:
 *
 * <ul>
 *   <li>the player cleared the field and is standing on an empty lawn waiting out a countdown
 *       written for a slower player ({@code clear_reward_factor});</li>
 *   <li>a wave should keep a <em>presence</em> on the lawn rather than trickle one zombie every
 *       five seconds no matter how many were just killed ({@code stockpile});</li>
 *   <li>the wave table's composition should be a budget spent on a pool rather than a list
 *       written by hand ({@code budget}).</li>
 * </ul>
 *
 * <pre>
 * { "type": "pvzce:wave_pacing",
 *   "clear_reward_factor": 3.0, "early_wave_kill_ratio": 0.7,
 *   "default_mode": "fixed",
 *   "waves": [ { "waves": [1, 2], "mode": "stockpile", "max_alive": 2 },
 *              { "waves": [3], "mode": "budget", "budget": 20, "pool": ["pvzce:basic_zombie"] } ] }
 * </pre>
 *
 * <p>The per-wave table is keyed by the wave's <em>number</em>, one-based, which is the number
 * the wave editor shows. Inserting a wave in the middle therefore shifts the ones after it -
 * the trade is deliberate: a level author reads {@code "waves": [1, 2]} and knows which waves
 * those are, which an index could not say.
 *
 * @param clearRewardFactor how much faster the countdown runs when the lawn is empty and the
 *                          previous wave has finished releasing; {@code 1} turns the reward
 *                          off, and the countdown is never shortened below
 *                          {@code clearRewardMinTicks}
 * @param clearRewardMinTicks the shortest gap a clear bonus may leave, in ticks
 * @param earlyWaveKillRatio of a wave that has arrived but is not finished, the share that has
 *                           to die before the next wave's countdown is allowed to be shortened;
 *                           {@code 1} (or more) means "only a cleared lawn counts"
 * @param earlyKillDelayFactor how much the opening waves' death gate tightens each time it runs
 *                             out; {@code 1} keeps the written wait for every zombie
 * @param healthDrain whether a countdown may be cut to {@code WaveDirector.HEALTH_DRAIN_TICKS}
 *                    the moment the wave on the lawn is beaten - the original's own pace, and
 *                    {@code false} for a level whose pacing is the point (a lesson, a scripted
 *                    tutorial) rather than a battle
 * @param nextWaveButton whether the HUD offers the "next wave" button once the wave on the lawn
 *                    is finished and the next one has not arrived yet; {@code false} for a level
 *                    whose waves are a script rather than a fight. A level that cannot be called
 *                    anyway - a song, a preparation stage - never shows it, whatever this says
 * @param defaultMode the mode of a wave that declares none
 * @param waves per-wave overrides, applied in order, later entries winning
 */
public record WavePacingData(
        float clearRewardFactor,
        int clearRewardMinTicks,
        int clearRewardGraceTicks,
        float earlyWaveKillRatio,
        float earlyKillDelayFactor,
        boolean earlyAdvance,
        boolean healthDrain,
        boolean nextWaveButton,
        WaveMode defaultMode,
        List<WavePacing> waves
) implements MechanicData {
    /**
     * Only the wave table: the arrival waits out its {@code delay}, and each zombie its
     * {@code spawn_interval}.
     */
    public static final WaveMode DEFAULT_MODE = WaveMode.FIXED;
    /**
     * Three, not ten: a clear bonus that removes the whole gap turns a level into one continuous
     * wave for a player who is winning, and the interval the author wrote is also what gives
     * them time to replant.
     */
    public static final float DEFAULT_CLEAR_REWARD_FACTOR = 3F;
    /** Five seconds: the shortest "the next wave is coming" a player can react to. */
    public static final int DEFAULT_CLEAR_REWARD_MIN_TICKS = 5 * PvzceConstants.TICKS_PER_SECOND;
    /**
     * How much of a countdown the clear bonus may not touch at all.
     *
     * <p>The bonus accelerates the part of the wait that is still ahead, but never the last
     * {@code clear_reward_grace_ticks} of it: clearing the lawn is the player's reward for
     * finishing a wave, and a reward that turns every level into one continuous wave is not one.
     * Ten seconds is also roughly "enough time to replant what the last wave ate".
     */
    public static final int DEFAULT_CLEAR_REWARD_GRACE_TICKS = 10 * PvzceConstants.TICKS_PER_SECOND;
    /**
     * Seventy percent of an arrived wave dead is "the player is winning this one", which is the
     * point at which waiting out the rest of the countdown stops being pacing and starts being
     * a pause.
     */
    public static final float DEFAULT_EARLY_WAVE_KILL_RATIO = 0.7F;
    /**
     * A quarter tighter each time: the opening death gate is a cap, and a cap that never moves
     * is what makes the first two waves of a slow start feel like a stall.
     */
    public static final float DEFAULT_EARLY_KILL_DELAY_FACTOR = 0.75F;
    /**
     * The health drain is on unless a level turns it off.
     *
     * <p>On, because it is the original's own pacing and the shipped tables' {@code delay}s are the
     * original's numbers - a level that serves them in full sits on an empty lawn for half a
     * minute. Off is for a level whose point is the script rather than the fight.
     */
    public static final boolean DEFAULT_HEALTH_DRAIN = true;
    /**
     * The next-wave button is offered unless a level turns it off.
     *
     * <p>On, for the same reason the clear bonus is: the shipped tables are the original's
     * numbers, and the original lets a player who has already finished a wave call the next one
     * instead of standing on an empty lawn. Off is for a level whose beats are written rather
     * than fought.
     */
    public static final boolean DEFAULT_NEXT_WAVE_BUTTON = true;
    /** A mode's {@code max_alive} when the wave does not say. */
    public static final int DEFAULT_MAX_ALIVE = 8;
    /**
     * How fast a clear bonus may ever leave the next wave.
     *
     * <p>The floor of the shortened countdown: {@code max(clear_reward_min_ticks, delay / this)}.
     * Three, because the clear bonus's job is to remove a wait the player has already served, not
     * to remove the pause between waves - and the pause is what a player spends replanting.
     */
    public static final int FASTEST_CLEAR_DELAY_DIVISOR = 3;
    /** What {@code early_wave_kill_ratio} may not go below: less than this is "no gate at all". */
    public static final float MIN_KILL_RATIO = 0.05F;

    /** The mode names a level may write, and the only ones this codec accepts. */
    public enum WaveMode {
        /** The wave table as written; the default. */
        FIXED,
        /**
         * The wave keeps up to {@code max_alive} of its own zombies on the lawn.
         *
         * <p>The gap between two zombies shrinks in proportion to how far below the cap the lawn
         * is, so a cleared lawn is refilled in about one wave interval rather than one zombie per
         * interval, and the trickle the author wrote is what it returns to once the cap is
         * reached. The cap is per wave and read as "this wave's zombies", not "all zombies": a
         * level whose first wave is a stockpile of two is not slowed down by the second wave
         * walking in.
         */
        STOCKPILE,
        /**
         * The wave releases everything and then waits to be finished.
         *
         * <p>Once its last zombie is out, the next wave's countdown is shortened as soon as
         * {@code kill_ratio} of this wave's zombies are dead, and dropped to nothing after
         * {@code max_extra_wait} ticks whether they are or not - so a player who is winning
         * meets the next wave early and a player who is losing still meets it.
         */
        SURVIVAL_RATIO,
        /**
         * The wave's composition comes from a point budget rather than from {@code entries}.
         *
         * <p>Every zombie costs points (see {@code ZombieDef.budgetCost()}), and the wave spends
         * {@code budget} of them on a random selection from {@code pool}. This is where a wave
         * stops being a recipe and becomes a threat level: the same twenty points are three
         * bucketheads or a buckethead and a crowd of ordinary zombies, and the level does not
         * have to write both down.
         */
        BUDGET
    }

    public static final MapCodec<WavePacingData> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.optionalFieldOf("clear_reward_factor", DEFAULT_CLEAR_REWARD_FACTOR)
                    .forGetter(WavePacingData::clearRewardFactor),
            Codec.INT.optionalFieldOf("clear_reward_min_ticks", DEFAULT_CLEAR_REWARD_MIN_TICKS)
                    .forGetter(WavePacingData::clearRewardMinTicks),
            Codec.INT.optionalFieldOf("clear_reward_grace_ticks", DEFAULT_CLEAR_REWARD_GRACE_TICKS)
                    .forGetter(WavePacingData::clearRewardGraceTicks),
            Codec.FLOAT.optionalFieldOf("early_wave_kill_ratio", DEFAULT_EARLY_WAVE_KILL_RATIO)
                    .forGetter(WavePacingData::earlyWaveKillRatio),
            Codec.FLOAT.optionalFieldOf("early_kill_delay_factor", DEFAULT_EARLY_KILL_DELAY_FACTOR)
                    .forGetter(WavePacingData::earlyKillDelayFactor),
            Codec.BOOL.optionalFieldOf("early_advance", true).forGetter(WavePacingData::earlyAdvance),
            Codec.BOOL.optionalFieldOf("health_drain", DEFAULT_HEALTH_DRAIN)
                    .forGetter(WavePacingData::healthDrain),
            Codec.BOOL.optionalFieldOf("next_wave_button", DEFAULT_NEXT_WAVE_BUTTON)
                    .forGetter(WavePacingData::nextWaveButton),
            Codec.STRING.optionalFieldOf("default_mode", DEFAULT_MODE.name().toLowerCase(Locale.ROOT))
                    .forGetter(data -> data.defaultMode().name().toLowerCase(Locale.ROOT)),
            WavePacing.CODEC.listOf().optionalFieldOf("waves", List.of())
                    .forGetter(WavePacingData::waves)
    ).apply(i, WavePacingData::parse));

    /**
     * Builds the block from the codec's view, decoding {@code default_mode} by name.
     *
     * <p>An unknown name falls back to {@link #DEFAULT_MODE} and is reported by the validator
     * rather than failing the level: a level with a typo should still load, and "the wave pacing
     * you asked for" is exactly the kind of thing a level author needs named.
     */
    public static WavePacingData parse(float clearRewardFactor, int clearRewardMinTicks,
                                       int clearRewardGraceTicks, float earlyWaveKillRatio,
                                       float earlyKillDelayFactor, boolean earlyAdvance,
                                       boolean healthDrain, boolean nextWaveButton, String defaultMode,
                                       List<WavePacing> waves) {
        return new WavePacingData(clearRewardFactor, clearRewardMinTicks, clearRewardGraceTicks,
                earlyWaveKillRatio, earlyKillDelayFactor, earlyAdvance, healthDrain, nextWaveButton,
                parseMode(defaultMode), waves);
    }

    public WavePacingData {
        waves = waves == null ? List.of() : List.copyOf(waves);
    }

    /** The level-level defaults: everything on, no per-wave overrides. */
    public static final WavePacingData DEFAULT = new WavePacingData(
            DEFAULT_CLEAR_REWARD_FACTOR, DEFAULT_CLEAR_REWARD_MIN_TICKS,
            DEFAULT_CLEAR_REWARD_GRACE_TICKS, DEFAULT_EARLY_WAVE_KILL_RATIO,
            DEFAULT_EARLY_KILL_DELAY_FACTOR, true, DEFAULT_HEALTH_DRAIN,
            DEFAULT_NEXT_WAVE_BUTTON, DEFAULT_MODE, List.of());

    /**
     * How long the next wave's countdown may run down to while the clear bonus is active.
     *
     * <p>Three times as fast, and never below {@code clear_reward_min_ticks}: a bonus that
     * removed the gap entirely would turn a level into one continuous wave for the player who is
     * winning, and the pause is what they spend replanting.
     */
    public int clearRewardDelay(int delayTicks) {
        if (!(clearRewardFactor > 1F)) {
            return Math.max(1, delayTicks);
        }
        // The grace window is paid in full, and only what is left of the countdown is
        // accelerated - so a gap the author already wrote short stays exactly as written, and a
        // long one is shortened without ever becoming "no gap at all".
        int grace = Math.max(clearRewardMinTicks,
                Math.min(delayTicks, Math.max(0, clearRewardGraceTicks)));
        int accelerated = Math.round(Math.max(0, delayTicks - grace) / clearRewardFactor);
        return Math.max(1, Math.min(delayTicks, grace + accelerated));
    }

    /**
     * The same block with every acceleration switched off: the wave table, exactly as written.
     *
     * <p>The health drain goes with the clear bonus, because the two are the same promise - "a
     * countdown may be cut short" - and a caller that asked for the second asked for the first.
     * Tests use this to read a table's own pacing; a level that wants it writes
     * {@code "health_drain": false} and leaves the clear bonus alone.
     */
    public WavePacingData clearRewardOff() {
        return new WavePacingData(1F, 0, 0, earlyWaveKillRatio, earlyKillDelayFactor, earlyAdvance,
                false, nextWaveButton, defaultMode, waves);
    }

    /**
     * A block that only chooses modes: every reward and gate setting stays at its default.
     *
     * <p>The shape a level that wants "these waves are stockpiles" writes, and the one the tests
     * build: eight positional numbers where six are boilerplate is a constructor call nobody can
     * read, and the two that matter are the mode and the rows.
     */
    public static WavePacingData ofModes(WaveMode defaultMode, WavePacing... waves) {
        return new WavePacingData(DEFAULT_CLEAR_REWARD_FACTOR, DEFAULT_CLEAR_REWARD_MIN_TICKS,
                DEFAULT_CLEAR_REWARD_GRACE_TICKS, DEFAULT_EARLY_WAVE_KILL_RATIO,
                DEFAULT_EARLY_KILL_DELAY_FACTOR, true, DEFAULT_HEALTH_DRAIN,
                DEFAULT_NEXT_WAVE_BUTTON, defaultMode, List.of(waves));
    }

    /** The same block with the health drain off: for a level whose script is the point. */
    public WavePacingData healthDrainOff() {
        return new WavePacingData(clearRewardFactor, clearRewardMinTicks, clearRewardGraceTicks,
                earlyWaveKillRatio, earlyKillDelayFactor, earlyAdvance, false, nextWaveButton,
                defaultMode, waves);
    }

    /** The same block with the health drain on and nothing else touched. */
    public WavePacingData drainOn() {
        return new WavePacingData(clearRewardFactor, clearRewardMinTicks, clearRewardGraceTicks,
                earlyWaveKillRatio, earlyKillDelayFactor, earlyAdvance, true, nextWaveButton,
                defaultMode, waves);
    }

    /** The same block with the next-wave button refused: for a level whose beats are written. */
    public WavePacingData nextWaveButtonOff() {
        return new WavePacingData(clearRewardFactor, clearRewardMinTicks, clearRewardGraceTicks,
                earlyWaveKillRatio, earlyKillDelayFactor, earlyAdvance, healthDrain, false,
                defaultMode, waves);
    }

    /** One row for one wave, in this mode, with nothing else changed. */
    public static WavePacing row(int waveNumber, WaveMode mode) {
        String name = mode.name().toLowerCase(Locale.ROOT);
        return new WavePacing(List.of(waveNumber), Optional.of(name), Optional.of(mode),
                Optional.empty(), Optional.empty(), Optional.empty(), List.of(), Optional.empty(),
                Optional.empty());
    }

    /** A mode by name, or {@link #DEFAULT_MODE} for anything unrecognised. */
    public static WaveMode parseMode(String name) {
        if (name == null) {
            return DEFAULT_MODE;
        }
        try {
            return WaveMode.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DEFAULT_MODE;
        }
    }

    /**
     * What this level's wave {@code waveNumber} (one-based) runs as.
     *
     * <p>The per-wave table is read back to front so a later entry wins, and a wave that declares
     * a {@code budget} is a budget wave whether or not it also said {@code "mode": "budget"} -
     * writing the budget is already saying it, and requiring both would be two ways to say one
     * thing.
     */
    public Pace forWave(int waveNumber) {
        boolean modeSet = false;
        WaveMode mode = defaultMode;
        int maxAlive = DEFAULT_MAX_ALIVE;
        float killRatio = earlyWaveKillRatio;
        int budget = 0;
        List<Identifier> pool = List.of();
        int minCount = 0;
        int maxCount = 0;
        for (int i = waves.size() - 1; i >= 0; i--) {
            WavePacing entry = waves.get(i);
            if (!entry.waves().contains(waveNumber)) {
                continue;
            }
            if (!modeSet) {
                mode = entry.mode().orElse(mode);
                maxAlive = entry.maxAlive().orElse(maxAlive);
                killRatio = entry.killRatio().orElse(killRatio);
                budget = entry.budget().orElse(budget);
                if (pool.isEmpty()) {
                    pool = entry.pool();
                }
                minCount = entry.minCount().orElse(minCount);
                maxCount = entry.maxCount().orElse(maxCount);
                modeSet = true;
            }
        }
        if (budget > 0) {
            mode = WaveMode.BUDGET;
        }
        return new Pace(mode, maxAlive, killRatio, budget, pool, minCount, maxCount);
    }

    /** How one wave runs, as {@link #forWave} resolved it. */
    public record Pace(WaveMode mode, int maxAlive, float killRatio, int budget,
                       List<Identifier> pool, int minCount, int maxCount) {
        public Pace {
            pool = pool == null ? List.of() : List.copyOf(pool);
        }

        public boolean isStockpile() {
            return mode == WaveMode.STOCKPILE;
        }
    }

    /**
     * One row of the per-wave table: which waves it applies to, and what it changes.
     *
     * <p>Every field is optional so a row can change one thing - a level that wants wave 4 to be
     * a stockpile of three writes only that, and inherits the rest.
     *
     * @param waves    the wave numbers this row applies to, one-based
     * @param mode     the mode, or empty to keep the level's default
     * @param maxAlive {@code stockpile} only: how many of this wave's zombies may stand at once
     * @param killRatio {@code survival_ratio} only: the share of this wave that has to die
     * @param budget   {@code budget} only: the points this wave may spend
     * @param pool     {@code budget} only: the zombies it may spend them on; empty means every
     *                 registered zombie
     * @param minCount {@code budget} only: never fewer than this, however cheap the picks are
     * @param maxCount {@code budget} only: never more than this, however cheap they are; {@code 0}
     *                 means no ceiling
     */
    public record WavePacing(List<Integer> waves, Optional<String> modeName, Optional<WaveMode> mode,
                             Optional<Integer> maxAlive, Optional<Float> killRatio,
                             Optional<Integer> budget, List<Identifier> pool,
                             Optional<Integer> minCount, Optional<Integer> maxCount) {
        public static final MapCodec<WavePacing> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.INT.listOf().fieldOf("waves").forGetter(WavePacing::waves),
                Codec.STRING.optionalFieldOf("mode").forGetter(WavePacing::modeName),
                Codec.INT.optionalFieldOf("max_alive").forGetter(WavePacing::maxAlive),
                Codec.FLOAT.optionalFieldOf("kill_ratio").forGetter(WavePacing::killRatio),
                Codec.INT.optionalFieldOf("budget").forGetter(WavePacing::budget),
                Identifier.CODEC.listOf().optionalFieldOf("pool", List.of()).forGetter(WavePacing::pool),
                Codec.INT.optionalFieldOf("min_count").forGetter(WavePacing::minCount),
                Codec.INT.optionalFieldOf("max_count").forGetter(WavePacing::maxCount)
        ).apply(i, WavePacing::parse));

        public static final Codec<WavePacing> CODEC = MAP_CODEC.codec();

        /**
         * Builds a row from the codec's own view: the mode arrives as the name written.
         *
         * <p>A named factory rather than the canonical constructor, because the mode is kept both
         * ways - the name so a round trip does not reword the level's file, and the parsed enum
         * so the engine does not parse it on every tick - and a lambda that had to call the
         * canonical constructor could not say that.
         */
        public static WavePacing parse(List<Integer> waves, Optional<String> modeName,
                                       Optional<Integer> maxAlive, Optional<Float> killRatio,
                                       Optional<Integer> budget, List<Identifier> pool,
                                       Optional<Integer> minCount, Optional<Integer> maxCount) {
            Optional<String> name = modeName == null ? Optional.empty() : modeName;
            Optional<WaveMode> parsed = name.filter(value -> !value.isBlank())
                    .flatMap(value -> {
                        try {
                            return Optional.of(WaveMode.valueOf(value.trim().toUpperCase(Locale.ROOT)));
                        } catch (IllegalArgumentException e) {
                            return Optional.empty();
                        }
                    });
            return new WavePacing(waves, name, parsed, maxAlive, killRatio, budget, pool, minCount,
                    maxCount);
        }

        public WavePacing {
            waves = waves == null ? List.of() : List.copyOf(waves);
            pool = pool == null ? List.of() : List.copyOf(pool);
            modeName = modeName == null ? Optional.empty() : modeName;
            mode = mode == null ? Optional.empty() : mode;
        }

        /** True when the written mode name is not one this version knows. */
        public boolean hasUnknownMode() {
            return modeName.isPresent() && mode.isEmpty();
        }

        /**
         * Reports every problem with this row, one message per problem.
         *
         * @param waveCount how many waves the level has, for the range check
         */
        public List<String> validate(int waveCount) {
            List<String> errors = new java.util.ArrayList<>();
            if (hasUnknownMode()) {
                errors.add("wave_pacing mode '" + modeName.orElse("")
                        + "' is not one of fixed / stockpile / survival_ratio / budget");
            }
            if (waves.isEmpty()) {
                errors.add("wave_pacing row names no waves; it would never apply to anything");
            }
            Map<Integer, Integer> seen = new LinkedHashMap<>();
            for (int number : waves) {
                if (number < 1 || number > waveCount) {
                    errors.add("wave_pacing row names wave " + number + ", but the level has "
                            + waveCount + " waves");
                }
                seen.merge(number, 1, Integer::sum);
            }
            Set<Integer> duplicated = new java.util.HashSet<>();
            seen.forEach((number, count) -> {
                if (count > 1) {
                    duplicated.add(number);
                }
            });
            if (!duplicated.isEmpty()) {
                errors.add("wave_pacing row names the same wave twice: " + duplicated);
            }
            maxAlive.ifPresent(value -> {
                if (value < 1) {
                    errors.add("wave_pacing max_alive must be at least 1, was " + value);
                }
            });
            killRatio.ifPresent(value -> {
                if (!(value > 0F) || value > 1F) {
                    errors.add("wave_pacing kill_ratio must be inside (0, 1], was " + value);
                }
            });
            budget.ifPresent(value -> {
                if (value < 1) {
                    errors.add("wave_pacing budget must be at least 1, was " + value);
                }
            });
            minCount.ifPresent(value -> {
                if (value < 0) {
                    errors.add("wave_pacing min_count cannot be negative, was " + value);
                }
            });
            maxCount.ifPresent(value -> {
                if (value < 0) {
                    errors.add("wave_pacing max_count cannot be negative, was " + value);
                }
            });
            for (Identifier id : pool) {
                if (BuiltInRegistries.ZOMBIES.get(id) == null) {
                    errors.add("wave_pacing pool names unknown zombie '" + id + "'");
                }
            }
            if (budget.isPresent() && maxCount.isPresent() && minCount.isPresent()
                    && maxCount.get() > 0 && minCount.get() > maxCount.get()) {
                errors.add("wave_pacing min_count " + minCount.get() + " is above max_count "
                        + maxCount.get());
            }
            return errors;
        }
    }
}
