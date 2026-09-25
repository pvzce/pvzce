package com.pvzce.common.level.mutation;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;

/**
 * The extra things a mutation may want to do that are not one of the four base hooks.
 *
 * <p>One interface rather than a dozen: a mutation implements the part it cares about and
 * {@code MutationManager} hands it the event, so nothing has to be added to the base
 * {@link Mutation} interface (or to every mutation) when a new event appears. Every method returns
 * a default "carry on", so an implementation that overrides nothing is valid and harmless.
 *
 * <p>Every event carries the {@link LevelServer} rather than letting an implementation find it:
 * these fire from inside entity ticks, where the level is the only thing that knows what a blast
 * should hit and where the board's edges are.
 *
 * <p>These are <em>events</em>, not vetoes, with one exception: {@link #replacePlantedPlant} is
 * asked before a plant appears and may answer with a different one. Everything else is told what
 * happened and decides what to do about it.
 */
public interface MutationHooks {
    /**
     * The plant that really appears where one was about to be planted.
     *
     * <p>Asked at the one door every placement goes through, before the entity exists: the
     * bowling-nut mutation is the case it was written for - a Wall-nut the player pays for has to
     * become a bowling Wall-nut, and rewriting the definition afterwards would leave the old
     * plant's capabilities in place.
     *
     * @return the definition to plant instead, or {@code null} to plant what was asked for
     */
    default PlantDef replacePlantedPlant(PlantDef def, int x, int y) {
        return null;
    }

    /** A plant has just been placed on the lawn. */
    default void onPlantPlaced(LevelServer level, PlantEntity plant) {
    }

    /** A plant has died: eaten, blown up, dug up, or spent. */
    default void onPlantDied(LevelServer level, PlantEntity plant) {
    }

    /** A zombie has died. */
    default void onZombieDied(LevelServer level, ZombieEntity zombie) {
    }

    /** A zombie has spawned. */
    default void onZombieSpawned(LevelServer level, ZombieEntity zombie) {
    }

    /** A projectile has been born with {@code projectileId} in flight. */
    default void onProjectileFired(LevelServer level, Identifier projectileId) {
    }

    /** One tick of the level, for a hook that would rather count here than in {@code state}. */
    default void onLevelTick(LevelServer level) {
    }
}
