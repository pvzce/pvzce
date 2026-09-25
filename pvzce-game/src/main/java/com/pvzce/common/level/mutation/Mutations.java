package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;

/**
 * The built-in mutation catalogue.
 *
 * <p>One place that lists them, and one method that registers them, so "what can this level do to
 * itself" is a list a reader can take in. Each entry's own class holds its numbers and its
 * doc comment; nothing about any one mutation lives here except its existence.
 *
 * <p>Registration order is the order the weights are walked in {@code MutationRegistry.roll} and
 * the order a panel lists a level's mutations, so it is deliberately grouped: the quiet numeric
 * ones first, then the card-bar takeovers, then the ones that change the board, then the ones that
 * change how things die. A level whose first mutation is "everything explodes" reads differently
 * from one that opens with a price change, and the ordering is the only lever over that.
 */
public final class Mutations {
    private Mutations() {
    }

    /** Registers every built-in mutation; called from {@code BuiltInRegistries.bootstrap()}. */
    public static void bootstrap() {
        register(PvzceIds.MUTATION_SUN_RATE, RateMutation.sunRate());
        register(PvzceIds.MUTATION_PLANT_ATTACK_RATE, RateMutation.plantAttackRate());
        register(PvzceIds.MUTATION_ZOMBIE_SPEED, RateMutation.zombieSpeed());
        register(PvzceIds.MUTATION_ZOMBIE_SPAWN_RATE, RateMutation.zombieSpawnRate());
        register(PvzceIds.MUTATION_PLANT_SUN_COST, RateMutation.plantSunCost());

        // The second catalogue, in the groups the plan laid out: the quiet numeric ones (two of
        // which are new rules read where a zombie or a plant is created), then the card bar, then
        // the board, then what arrives on it, then what the player's own side does.
        register(PvzceIds.MUTATION_ZOMBIE_HEALTH, RateMutation.zombieHealth());
        register(PvzceIds.MUTATION_PLANT_FRAGILE, RateMutation.plantFragile());
        register(PvzceIds.MUTATION_CARD_COOLDOWN, RateMutation.cardCooldown());
        register(PvzceIds.MUTATION_SUN_SHOWER, new SunShowerMutation());

        register(PvzceIds.MUTATION_SLOT_LOCK, new SlotLockMutation());
        // The board: the weather, the terrain and what walks on it.
        register(PvzceIds.MUTATION_FOG_ROLL_IN, new FogRollInMutation());
        register(PvzceIds.MUTATION_FLOOD_LAWN, new FloodLawnMutation());
        register(PvzceIds.MUTATION_METEOR_SHOWER, new MeteorShowerMutation());
        register(PvzceIds.MUTATION_THORN_LAWN, new ThornLawnMutation());
        register(PvzceIds.MUTATION_ICE_GROUND, new IceGroundMutation());
        // And the raids: one class, five shapes.
        for (Mutation raid : RaidMutation.all()) {
            register(raid.id(), raid);
        }
        // The player's own side: what the lawn does for and to them.
        register(PvzceIds.MUTATION_PEA_PARTY, new PeaPartyMutation());
        for (Mutation mower : MowerMutation.all()) {
            register(mower.id(), mower);
        }
        register(PvzceIds.MUTATION_SUN_DRAIN, new SunDrainMutation());
        register(PvzceIds.MUTATION_SLOT_ROULETTE, new SlotRouletteMutation());

        register(PvzceIds.MUTATION_SLOT_REPLACE, new SlotReplaceMutation());
        register(PvzceIds.MUTATION_CONVEYOR, new ConveyorMutation());

        register(PvzceIds.MUTATION_NIGHTFALL, new NightfallMutation());
        register(PvzceIds.MUTATION_BOWLING_NUT, new BowlingNutMutation());
        register(PvzceIds.MUTATION_WHACK_A_ZOMBIE, new WhackAZombieMutation());
        register(PvzceIds.MUTATION_GRAVE_GROWTH, new GraveGrowthMutation());
        register(PvzceIds.MUTATION_ZOMBIE_CRISIS, new ZombieCrisisMutation());
        register(PvzceIds.MUTATION_BUFF_SHIFT, new BuffShiftMutation());
        register(PvzceIds.MUTATION_KELP_SPREAD, new KelpSpreadMutation());

        register(PvzceIds.MUTATION_ZOMBIE_BLAST, new ZombieBlastMutation());
        register(PvzceIds.MUTATION_PLANT_BLAST, new PlantBlastMutation());
        register(PvzceIds.MUTATION_MENDEL, new MendelMutation());
        register(PvzceIds.MUTATION_APOCALYPSE, new ApocalypseMutation());
    }

    private static void register(Identifier id, Mutation mutation) {
        MutationRegistry.register(id, mutation);
    }
}
