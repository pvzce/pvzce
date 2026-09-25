package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.server.level.LevelServer;

import java.util.List;

/**
 * Peas come out as one of three peas, at random: Mendel's contribution to the lawn.
 *
 * <p>Only the two pea bullets are rewritten - a Cactus spike or a melon is not a pea, and a
 * mutation that rewrote every projectile would be "the projectiles are random now", which is a
 * different and much less legible mutation. What comes out is one of the three: the ordinary pea,
 * the snow pea, or the fire pea. The shooter's own damage is kept, so a Gatling Pea's fire pea hits
 * as hard as its ordinary one; what changes is the bullet.
 *
 * <p>Rolled per shot rather than per plant, because that is what "乱入" means: the player cannot
 * plan around it, and a row of Peashooters firing a mixed stream is the picture the mutation is
 * named after.
 */
final class MendelMutation implements Mutation, ProjectileSubstitution {
    /** What an ordinary pea may become. Nothing else is a pea. */
    private static final List<Identifier> PEAS = List.of(
            PvzceIds.PEASHOOTER_PEA, PvzceIds.SNOW_PEA, PvzceIds.FIRE_PEA);

    @Override
    public Identifier id() {
        return PvzceIds.MUTATION_MENDEL;
    }

    @Override
    public boolean canRun(LevelServer level) {
        // Every target has to exist, or the substitution would fire nothing at all: a pea that
        // becomes an unregistered projectile is a shot that silently disappears.
        for (Identifier pea : PEAS) {
            if (BuiltInRegistries.PROJECTILES.get(pea) == null) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Identifier replacementFor(Identifier projectileId, java.util.Random random) {
        if (!PEAS.contains(projectileId)) {
            return null;
        }
        return PEAS.get(random.nextInt(PEAS.size()));
    }
}
