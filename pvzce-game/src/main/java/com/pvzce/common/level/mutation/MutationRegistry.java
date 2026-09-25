package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Registration and lookup for mutations.
 *
 * <p>Mirrors {@code LevelMechanics} and {@code LevelBuffs} one system over: the built-ins are
 * registered from {@code BuiltInRegistries.bootstrap()}, and every other class asks this one
 * question - "what is this id" - instead of reaching into the registry. There is no codec,
 * because a mutation has no data block: which one is running is the whole state, and the number
 * it rolled travels with it rather than with its definition.
 */
public final class MutationRegistry {
    private MutationRegistry() {
    }

    /** Registers every built-in mutation; called from {@code BuiltInRegistries.bootstrap()}. */
    public static void bootstrap() {
        Mutations.bootstrap();
    }

    /** Registers one mutation. A mod calls this before a mutation level is created. */
    public static void register(Identifier id, Mutation mutation) {
        BuiltInRegistries.registerStatic(BuiltInRegistries.MUTATIONS, id.toString(), mutation);
    }

    public static Mutation get(Identifier id) {
        return id == null ? null : BuiltInRegistries.MUTATIONS.get(id);
    }

    /**
     * Every registered mutation, in registration order.
     *
     * <p>Registration order is the catalogue's order, which is also the order the panel lists a
     * level's mutations in - a stable order costs nothing and makes a screenshot comparable
     * between two runs.
     */
    public static List<Mutation> all() {
        List<Mutation> result = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.MUTATIONS.keySet()) {
            Mutation mutation = BuiltInRegistries.MUTATIONS.get(id);
            if (mutation != null) {
                result.add(mutation);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Picks one mutation by weight.
     *
     * <p>Weights rather than equal chances, because the catalogue is deliberately uneven: there
     * is one "everything on the board explodes" and a dozen small numeric twists, and a level
     * that rolled each with the same probability would be a level of explosions.
     *
     * @param random the level's own dice, so a run reproduced from a seed rolls the same order
     * @return the chosen mutation, or {@code null} when nothing is registered
     */
    public static Mutation roll(Random random) {
        List<Mutation> candidates = all();
        int total = 0;
        for (Mutation mutation : candidates) {
            total += Math.max(0, mutation.weight());
        }
        if (total <= 0) {
            return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
        }
        int pick = random.nextInt(total);
        for (Mutation mutation : candidates) {
            pick -= Math.max(0, mutation.weight());
            if (pick < 0) {
                return mutation;
            }
        }
        return candidates.get(candidates.size() - 1);
    }
}
