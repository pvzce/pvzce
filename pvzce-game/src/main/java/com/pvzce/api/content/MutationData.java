package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.api.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The block of the {@code pvzce:mutation} mechanic: whether the level rolls its mutations at
 * random, and which ones it stages by hand.
 *
 * <p>Until the tutorial level existed the block was empty - declaring the mechanic meant "this run
 * rewrites itself, on the tier's clock" - and that is still what a level that writes nothing gets
 * ({@code random} defaults to true, {@code schedule} to empty). What the tutorial needs is the
 * opposite: a <em>script</em>, so the player meets one kind of mutation at a time, in an order
 * somebody chose.
 *
 * <pre>
 * { "type": "pvzce:mutation" }                                  // random, as before
 * { "type": "pvzce:mutation", "random": false,
 *   "schedule": [ { "id": "pvzce:slot_replace", "at_tick": 900 }, … ] }
 * </pre>
 *
 * <p>The two are independent, not exclusive: a level may stage three mutations and still roll the
 * rest ({@code random: true} with a schedule). What is <em>not</em> allowed is a schedule entry
 * naming something that does not exist or a tick that goes backwards - both are reported by
 * {@code MutationMechanic.validate} rather than throwing during a run.
 *
 * @param random   true when the level also rolls mutations on the tier's clock
 * @param schedule the mutations this level stages by hand, in tick order
 */
public record MutationData(boolean random, List<Planned> schedule) implements MechanicData {
    /** The default: random arrivals, nothing staged. What every level before the tutorial meant. */
    public static final MutationData RANDOM = new MutationData(true, List.of());

    public static final MapCodec<MutationData> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.BOOL.optionalFieldOf("random", true).forGetter(MutationData::random),
            Planned.CODEC.listOf().optionalFieldOf("schedule", List.of())
                    .forGetter(MutationData::schedule)
    ).apply(i, MutationData::new));

    public static final Codec<MutationData> CODEC = MAP_CODEC.codec();

    public MutationData {
        schedule = schedule == null ? List.of() : List.copyOf(schedule);
    }

    /** Every problem with this block, one message per problem. */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        int previous = -1;
        List<Identifier> seen = new ArrayList<>();
        for (Planned planned : schedule) {
            if (planned.id() == null) {
                errors.add("a scheduled mutation has no id");
                continue;
            }
            if (planned.atTick() < previous) {
                errors.add("scheduled mutation '" + planned.id() + "' is due at tick "
                        + planned.atTick() + ", before the one above it (" + previous
                        + "): the order of this list is the order they arrive in");
            }
            previous = planned.atTick();
            if (seen.contains(planned.id())) {
                // Not forbidden - a tutorial may well show the same mutation twice - but the save
                // records how many have fired, so a repeat is worth naming in case it was a typo.
                errors.add("scheduled mutation '" + planned.id() + "' appears more than once");
            }
            seen.add(planned.id());
        }
        return errors;
    }

    /**
     * One staged mutation.
     *
     * @param id     the mutation to add at that moment
     * @param atTick the tick of the level's own clock, counted from the run's start
     */
    public record Planned(Identifier id, int atTick) {
        public static final Codec<Planned> CODEC = RecordCodecBuilder.create(i -> i.group(
                Identifier.CODEC.fieldOf("id").forGetter(Planned::id),
                Codec.INT.fieldOf("at_tick").forGetter(Planned::atTick)
        ).apply(i, Planned::new));

        public Planned {
            atTick = Math.max(0, atTick);
        }
    }
}
