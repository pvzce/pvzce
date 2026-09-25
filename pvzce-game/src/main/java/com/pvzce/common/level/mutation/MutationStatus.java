package com.pvzce.common.level.mutation;

/**
 * Whether a mutation on the field is doing anything.
 *
 * <p>A mutation can be on the list without acting, and the player is told which: "waiting" is a
 * mutation whose own prerequisites are not met yet, and "suppressed" is one another running
 * mutation has taken the job from. Keeping the two apart matters because they resolve
 * differently - a suppressed mutation comes back on its own the moment the one above it is
 * evicted, while a waiting one comes back when the board changes.
 */
public enum MutationStatus {
    /** Doing its thing right now. */
    ACTIVE,
    /** On the field, but its own {@code canRun} says not yet (no gravestone, no water). */
    WAITING,
    /** Held back by a later mutation that does the same job better. */
    SUPPRESSED
}
