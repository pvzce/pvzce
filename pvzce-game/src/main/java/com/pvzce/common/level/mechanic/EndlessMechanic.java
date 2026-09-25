package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.EndlessScheduleDef;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * Waves that do not run out: the original's Survival Endless, played in rounds.
 *
 * <p>A level with this mechanic generates its waves instead of writing them. Each round is a
 * fixed number of waves; when the last of them has arrived <em>and the lawn is clear</em>, the
 * round is over, the player picks their cards again, and the next round starts heavier. The
 * round number is what the difficulty is a function of, and the whole curve lives in the
 * level's {@link EndlessScheduleDef} rather than in a table.
 *
 * <p><b>Nothing is expanded ahead of time.</b> The mechanic block names a schedule; the wave
 * director asks for one wave at a time and gets it back from
 * {@code common.level.endless.EndlessWaves}. That is the point of the shape: the version this
 * replaced expanded a four-thousand-entry list when the level was constructed, which capped
 * how long "endless" could be at what the network packet could carry and cost every client
 * the whole table. A generated wave costs one wave.
 *
 * <p>The tier's own rules still apply. Zombie definitions decide health and speed (times the
 * round's own health growth), {@code zombie_spawn_speed_multiplier} still divides the cadence,
 * and a mutation may still rewrite any of it - so an endless level is a level like any other,
 * with a wave source instead of a wave table.
 */
public final class EndlessMechanic implements LevelMechanic<EndlessMechanic.Data> {
    /**
     * The block a level writes: {@code {"type": "pvzce:endless", "schedule": "pvzce:..."}}.
     *
     * <p>The schedule is optional and defaults to the pool one, so a level that wants vanilla
     * Survival Endless writes the type and nothing else.
     *
     * @param schedule which endless schedule this level generates its waves from
     */
    public record Data(Identifier schedule) implements MechanicData {
        /** The schedule a level that does not name one gets. */
        public static final Identifier DEFAULT_SCHEDULE = PvzceIds.ENDLESS_SCHEDULE_POOL;

        public static final MapCodec<Data> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Identifier.CODEC.optionalFieldOf("schedule", DEFAULT_SCHEDULE).forGetter(Data::schedule)
        ).apply(i, Data::new));
    }

    @Override
    public MapCodec<Data> codec() {
        // The record codec is already a MapCodec: it reads the block's own keys, so a level
        // writes `{"type": "pvzce:endless", "schedule": "pvzce:..."}` and one that omits the
        // schedule gets the default one rather than a missing-field error.
        return Data.CODEC;
    }

    @Override
    public List<String> validate(LevelDef def, Data data) {
        List<String> errors = new ArrayList<>();
        if (!def.waves().isEmpty()) {
            // The generator replaces the table rather than adding to it: two answers to "what
            // comes next" is one too many, and the one that loses would be the author's.
            errors.add("endless generates its own waves, so the " + def.waves().size()
                    + " waves this level writes are ignored; delete them or drop the mechanic");
        }
        EndlessScheduleDef schedule = BuiltInRegistries.ENDLESS_SCHEDULES.get(data.schedule());
        if (schedule == null) {
            errors.add("endless names unknown schedule '" + data.schedule() + "'");
            return errors;
        }
        if (schedule.pool().isEmpty()) {
            errors.add("endless schedule '" + data.schedule() + "' has an empty pool, so no wave"
                    + " could ever be sent");
        }
        for (EndlessScheduleDef.ZombieEntry entry : schedule.pool()) {
            if (BuiltInRegistries.ZOMBIES.get(entry.zombie()) == null) {
                errors.add("endless schedule '" + data.schedule() + "' names unknown zombie '"
                        + entry.zombie() + "'");
            }
        }
        return errors;
    }

    /** The schedule a level runs with, or {@code null} when it names one that is not loaded. */
    public static EndlessScheduleDef scheduleOf(LevelDef def) {
        for (com.pvzce.api.content.mechanic.TypedMechanic typed : def.mechanics()) {
            if (typed.is(PvzceIds.MECHANIC_ENDLESS) && typed.value() instanceof Data data) {
                return BuiltInRegistries.ENDLESS_SCHEDULES.get(data.schedule());
            }
        }
        return null;
    }

    /** True when this level generates its waves rather than reading them. */
    public static boolean generatesWaves(LevelDef def) {
        return LevelMechanics.has(def, PvzceIds.MECHANIC_ENDLESS);
    }

    /**
     * The waves a round holds, as the level list wants them for its preview.
     *
     * <p>Only the first round: a level list entry is not a run, and the player has not started
     * counting rounds yet.
     */
    public static List<WaveDef> previewWaves(LevelDef def) {
        EndlessScheduleDef schedule = scheduleOf(def);
        if (schedule == null) {
            return List.of();
        }
        return com.pvzce.common.level.endless.EndlessWaves.preview(schedule, def.height());
    }
}
