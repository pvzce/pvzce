package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;

/**
 * Every zombie blows up like a Potato Mine when it dies.
 *
 * <p>Not a themed effect but a tactical one: the blast does not check sides, so a lawn packed with
 * plants clears itself as the zombies die - and a Gargantuar dying next to the player's defence is
 * a disaster. That symmetry is the whole mutation, which is why it is its own entry rather than a
 * modifier on the plant version.
 *
 * <p>A zombie that was itself killed by a blast does <b>not</b> go off again: without that rule
 * the first death would cascade down a lane one ordinary zombie per blast, which is not the
 * mutation anybody asked for. See {@link MutantBlast}.
 */
final class ZombieBlastMutation implements Mutation, MutationHooks {
    @Override
    public Identifier id() {
        return PvzceIds.MUTATION_ZOMBIE_BLAST;
    }

    @Override
    public void onZombieDied(LevelServer level, ZombieEntity zombie) {
        if (zombie.diedToBlast(level.tickCount())) {
            return;
        }
        MutantBlast.detonate(level, zombie.position(), zombie.surfaceId());
    }
}
