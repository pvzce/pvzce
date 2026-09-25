package com.pvzce.common.capability.zombie;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.common.PvzceParticles;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;

/**
 * Goes off when it dies, hurting the plants around it (the Jalapeno-head zombie).
 *
 * <p>The ZomBotany line's one zombie that is a threat after it is dealt with: shooting it in the
 * middle of a lane costs the player whatever was standing next to it. That is the point - the
 * answer to a bomber is to kill it early and far away, not to kill it well.
 *
 * <h2>The ceiling is the plant one</h2>
 *
 * <p>Half an ordinary plant, which is {@code MutantBlast.plantDamage()} - the same number the
 * blast mutations were given when the "no chain reactions" rule was set. It matters more here than
 * there: a zombie that died to one pea and took two peashooters with it would make the whole line
 * unplayable, and half means a plant survives the first bomb and only dies to a second.
 *
 * <p>Deliberately narrower than the blast mutations as well: 0.5 cells rather than a footprint, so
 * it hits the plant it died next to and not the whole neighbourhood.
 */
public final class DeathBlastCapability implements ZombieCapability {
    public static final float DEFAULT_RADIUS = 0.5F;

    private final float radius;
    private final int damage;

    public DeathBlastCapability(float radius, int damage) {
        this.radius = Math.max(0.1F, radius);
        // *Not* clamped up to 1. Zero is the block's way of saying "I did not name one", and the
        // ceiling it then falls back to is the plant one; clamping here turned every definition
        // that left the field out into a one-point blast - which is the kind of bug that looks
        // like "the bomb does nothing" rather than like an arithmetic error.
        this.damage = damage;
    }

    public static final MapCodec<DeathBlastCapability> CODEC =
            RecordCodecBuilder.mapCodec(i -> i.group(
                    Codec.FLOAT.optionalFieldOf("radius", DEFAULT_RADIUS)
                            .forGetter(DeathBlastCapability::radius),
                    Codec.INT.optionalFieldOf("damage", 0)
                            .forGetter(DeathBlastCapability::damage)
            ).apply(i, DeathBlastCapability::new));

    public float radius() {
        return radius;
    }

    /** The blast's damage, or 0 when the definition did not name one. */
    public int damage() {
        return damage;
    }

    @Override
    public ZombieCapability instantiate() {
        return this;
    }

    @Override
    public void onDeath(ZombieEntity zombie, LevelAccess level) {
        if (!(level instanceof LevelServer server)) {
            return;
        }
        int amount = damage > 0 ? damage : com.pvzce.common.level.mutation.MutantBlast.plantDamage();
        for (int column = 0; column < server.width(); column++) {
            for (PlantEntity plant : server.plantsAt(column, zombie.gridY())) {
                if (plant.isRemoved()) {
                    continue;
                }
                if (Math.abs(plant.cellX() - zombie.cellX()) > radius + 0.5F) {
                    continue;
                }
                // `damage` and not `damageFrom`: a bomb that goes off beside a plant should reach
                // the one sitting on its fuse, which is the same reading the blast mutations use.
                plant.damage(amount);
            }
        }
        server.emitEffect(PvzceParticles.EXPLOSION_POW.toString(),
                zombie.cellX(), zombie.cellY(), PvzceSounds.EFFECT_EXPLOSION);
    }

    @Override
    public void save(CompoundTag tag) {
        // Nothing of its own: it happens once, on the way out.
    }
}
