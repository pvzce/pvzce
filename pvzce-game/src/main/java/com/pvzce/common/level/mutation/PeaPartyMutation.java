package com.pvzce.common.level.mutation;

import com.pvzce.api.content.ProjectileRef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.capability.plant.ShooterCapability;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.level.LevelServer;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Every plant that does not shoot is given a gun: "豌豆派对".
 *
 * <p>The mutation's whole trick is <em>not</em> touching the definitions: it adds a
 * {@link ShooterCapability} to each plant as it is placed
 * ({@code PlantEntity.addTemporaryCapability}), so a sunflower fires a pea every ninety ticks and a
 * wall-nut does too. Rewriting the definitions instead would arm every plant of that kind in every
 * level - including the player's next run, since a definition outlives a level.
 *
 * <p>Plants that already shoot are left alone: they have a shooter of their own, and a second one
 * would double their rate, which is a different mutation ("植物攻击速率") and a worse one - it would
 * make the mutation read as "shooters are twice as good" rather than "everything shoots".
 *
 * <p>The capability is taken back from every plant when the mutation leaves, and a plant that was
 * placed while it was running is disarmed like the rest: the mutation's promise is about the lawn as
 * it stands, and a garden that kept firing after the mutation was gone would be the mutation
 * outliving its own eviction. What a resumed save gets is the same treatment - temporary
 * capabilities are not saved, and the mutation re-arms the plants it finds on the lawn when it
 * re-applies.
 */
final class PeaPartyMutation implements Mutation {
    /** How often an armed plant fires. The peashooter's own rate, so the lawn reads as one weapon. */
    private static final int FIRE_INTERVAL_TICKS = 90;
    /** The pea an armed plant fires, and how hard. */
    private static final Identifier PEA = PvzceIds.PEASHOOTER_PEA;
    private static final int PEA_DAMAGE = 20;

    /**
     * The plants this activation armed, by entity id.
     *
     * <p>In the state rather than in a field, because a mutation instance is shared by every level in
     * the process - see {@code Mutation}.
     */
    @Override
    public Identifier id() {
        return PvzceIds.MUTATION_PEA_PARTY;
    }

    @Override
    public boolean canRun(LevelServer level) {
        return com.pvzce.common.core.BuiltInRegistries.PROJECTILES.get(PEA) != null;
    }

    @Override
    public Object apply(LevelServer level, Mutation.Roll roll) {
        Applied applied = new Applied();
        for (com.pvzce.api.entity.Entity entity : level.entities()) {
            if (entity instanceof PlantEntity plant) {
                arm(plant, applied);
            }
        }
        return applied;
    }

    /**
     * On a restore the lawn is re-armed rather than remembered.
     *
     * <p>The capability instances are not in the save (they belong to the mutation, and the mutation
     * is), so the honest thing is to hand them out again to whatever is standing - which is also what
     * makes a resume behave like the moment the mutation arrived.
     */
    @Override
    public Object applyFromSave(LevelServer level, Mutation.Roll roll) {
        return apply(level, roll);
    }

    /** Arms plants that appear while the mutation is running: the party grows with the lawn. */
    @Override
    public void tick(LevelServer level, Mutation.Roll roll, Object state) {
        if (!(state instanceof Applied applied)) {
            return;
        }
        for (com.pvzce.api.entity.Entity entity : level.entities()) {
            if (entity instanceof PlantEntity plant && !applied.armed.containsKey(plant.id())) {
                arm(plant, applied);
            }
        }
    }

    @Override
    public void revert(LevelServer level, Mutation.Roll roll, Object state) {
        if (!(state instanceof Applied applied)) {
            return;
        }
        for (Map.Entry<Integer, PlantEntity.Instance> entry : applied.armed.entrySet()) {
            PlantEntity plant = plantById(level, entry.getKey());
            if (plant != null) {
                plant.removeTemporaryCapability(entry.getValue());
            }
        }
        applied.armed.clear();
    }

    /** Adds one shooter to one plant, unless it already shoots. */
    private static void arm(PlantEntity plant, Applied applied) {
        if (plant == null || plant.capability(ShooterCapability.class) != null) {
            return;
        }
        ShooterCapability shooter = new ShooterCapability(FIRE_INTERVAL_TICKS,
                java.util.List.of(new ProjectileRef(PEA, PEA_DAMAGE, 1, 0, false, 0,
                        ProjectileRef.UNLIMITED_RANGE, ProjectileRef.NO_BURST_DELAY)),
                Optional.empty(), 0);
        // The shared clock the capability spends is the plant's own `actionStep`, which is already
        // scaled by the level's action-speed rule - so a level that makes plants work faster makes
        // the party faster too, without this mutation knowing about it.
        applied.armed.put(plant.id(), plant.addTemporaryCapability(
                PvzceIds.id("shooter"), shooter));
    }

    private static PlantEntity plantById(LevelServer level, int id) {
        for (com.pvzce.api.entity.Entity entity : level.entities()) {
            if (entity.id() == id && entity instanceof PlantEntity plant) {
                return plant;
            }
        }
        return null;
    }

    @Override
    public java.util.Optional<String> announcement(LevelServer level, Mutation.Roll roll,
                                                  Object state) {
        return java.util.Optional.of("豌豆派对：连向日葵和坚果都开始吐豌豆了");
    }

    /** The plants this activation armed, by entity id. */
    private static final class Applied {
        private final Map<Integer, PlantEntity.Instance> armed = new HashMap<>();
    }
}
