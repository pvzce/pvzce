package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;

/**
 * One mutation on a level's field, with the number it rolled.
 *
 * <p>Immutable except for {@link #state}: the definition, the roll and the moment it arrived are
 * facts about this activation, and the only thing that changes over its life is the object
 * {@link Mutation#apply} handed back. The manager keeps the list of these in arrival order,
 * which is what "the oldest one is evicted" reads.
 */
final class MutationEntry {
    private final Mutation mutation;
    private final Mutation.Roll roll;
    private Object state;
    private boolean applied;

    MutationEntry(Mutation mutation, Mutation.Roll roll) {
        this.mutation = mutation;
        this.roll = roll == null ? Mutation.Roll.NONE : roll;
    }

    Mutation mutation() {
        return mutation;
    }

    Identifier id() {
        return mutation.id();
    }

    Mutation.Roll roll() {
        return roll;
    }

    /** The object {@link Mutation#apply} returned, or {@code null} when it keeps none. */
    Object state() {
        return state;
    }

    void setState(Object state) {
        this.state = state;
    }

    /** True while this mutation's effects are on the level. */
    boolean isApplied() {
        return applied;
    }

    void setApplied(boolean applied) {
        this.applied = applied;
    }
}
