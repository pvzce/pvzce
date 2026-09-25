package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.level.LevelServer;

/**
 * Every plant blows up like a Potato Mine when it dies.
 *
 * <p>The other half of {@link ZombieBlastMutation}, and the same bargain from the player's side: a
 * Sunflower eaten by a zombie takes the zombie with it, and so does the Wall-nut standing next to
 * it. That friendly fire is what makes it a mutation rather than a gift - the player's own defence
 * becomes a minefield that a single Gargantuar swing can set off.
 *
 * <p>A plant being <em>removed</em> counts: dug up by the shovel, spent after one shot, eaten, or
 * caught in a blast. Anything else would leave the player with a way to disarm the field for free,
 * and "it exploded when I dug it up" is a legible rule where "it explodes unless you remove it
 * yourself" is not.
 */
final class PlantBlastMutation implements Mutation, MutationHooks {
    @Override
    public Identifier id() {
        return PvzceIds.MUTATION_PLANT_BLAST;
    }

    @Override
    public void onPlantDied(LevelServer level, PlantEntity plant) {
        if (plant == null) {
            return;
        }
        MutantBlast.detonate(level, plant.cellX(), plant.cellY(), plant.team());
    }
}
