package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;

/**
 * The lawn itself hurts what walks on it: "荆棘草坪".
 *
 * <p>Every ground zombie bleeds a little, on a clock, for as long as the mutation stands. The damage
 * is a <em>percentage of the zombie's own maximum</em>, with a floor of one point, and that is the
 * whole balance of the mutation: a flat number would be lethal to a basic zombie and invisible to a
 * Gargantuar, while a share of its health means the thorns buy time against everything equally.
 *
 * <p>Two limits worth stating:
 *
 * <ul>
 *   <li><b>The ground layer only.</b> A balloon drifting over the lawn and a miner still underground
 *       are not walking on it - the layer is the engine's own answer to "is this thing on the
 *       ground", the same one the mower and the projectiles read.</li>
 *   <li><b>Armour takes it first.</b> The damage type is {@code pvzce:impact}, so a Conehead loses
 *       cone before health: thorns are pressure against what is bare and a slow grind against what
 *       is armoured, which is what keeps a lane of Bucketheads a real threat under this
 *       mutation.</li>
 * </ul>
 */
final class ThornLawnMutation implements Mutation {
    /** How often a walker is pricked, in ticks. Fixed, not scaled: this is a rate of damage. */
    private static final int PRICK_INTERVAL_TICKS = 60;
    /**
     * What one prick takes, as a share of the zombie's own maximum health.
     *
     * <p>One percent every second. It was two percent every half-second, which was a fresh basic
     * zombie dead around the middle of the lawn: the arithmetic in the old comment assumed a
     * fifteen-second crossing, but a basic zombie walks at 0.23 cells/s, so nine and a half cells
     * take about forty seconds and the thorns were taking a hundred and fifty percent of its
     * health over that walk. At one percent a second the same walk costs about forty percent -
     * felt, and survivable.
     */
    private static final float PRICK_SHARE = 0.01F;

    @Override
    public Identifier id() {
        return PvzceIds.MUTATION_THORN_LAWN;
    }

    @Override
    public void tick(LevelServer level, Mutation.Roll roll, Object state) {
        if (level.tickCount() % PRICK_INTERVAL_TICKS != 0) {
            return;
        }
        com.pvzce.api.content.DamageTypeDef type = ZombieEntity.damageType(PvzceIds.DAMAGE_IMPACT);
        if (type == null) {
            return;
        }
        for (com.pvzce.api.entity.Entity entity : level.entities()) {
            if (!(entity instanceof ZombieEntity zombie) || !zombie.isAlive()) {
                continue;
            }
            if (zombie.layer() != com.pvzce.api.entity.EntityLayers.GROUND) {
                continue;
            }
            int damage = Math.max(1, Math.round(zombie.maxHealth() * PRICK_SHARE));
            zombie.damage(damage, type, level);
        }
    }

    @Override
    public java.util.Optional<String> announcement(LevelServer level, Mutation.Roll roll,
                                                  Object state) {
        return java.util.Optional.of("荆棘草坪：走在草坪上的僵尸会一直掉血");
    }
}
