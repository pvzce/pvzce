package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;

import java.util.Random;

/**
 * A mutation that rewrites what a shot carries: the "peas are randomly one of three peas" rule.
 *
 * <p>A named interface rather than the {@code MutationHooks} event, because this one is asked
 * <em>during</em> a shot rather than told about it after: the level is choosing the projectile it
 * is about to spawn, and the answer it needs is "which one instead", not "here is what I did".
 */
public interface ProjectileSubstitution {
    /**
     * The projectile to fire instead of {@code projectileId}.
     *
     * @param random the level's own dice, so a shot is reproducible from a seed
     * @return the replacement, or {@code null} to fire what the plant asked for
     */
    Identifier replacementFor(Identifier projectileId, Random random);
}
