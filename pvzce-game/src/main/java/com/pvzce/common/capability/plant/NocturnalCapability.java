package com.pvzce.common.capability.plant;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;

/**
 * A mushroom: asleep in daylight, awake at night, and woken for good by a coffee bean.
 *
 * <p>The original's rule, and the reason mushrooms are cheap. The state itself is
 * <em>derived</em> - {@code awake || level.isNight()} - rather than a flag toggled at
 * dusk and dawn, so a level whose clock starts at night, jumps, or is rolled back by
 * {@code /time} cannot leave a plant asleep in the dark. The one thing that is stored
 * is the coffee bean's gift: once woken, this plant never sleeps again, which is why
 * that flag is in the save file and the "is it night" half is not.
 *
 * <p>Suppressing a sleeping plant's behaviour is not done here: {@code PlantEntity}
 * skips every capability that does not opt in through
 * {@link #ticksWhileAsleep(PlantEntity)}, so this capability's only jobs are to publish
 * the {@code sleep} animation and to answer the two questions.
 *
 * <p>One stored bit and no tuning: which plants are nocturnal is the plant definition's
 * business, and the only thing that has to survive a save is the waking.
 */
public final class NocturnalCapability implements PlantCapability {
    /**
     * The codec's unit value. Never handed to a plant - {@link #instantiate()} makes one per
     * plant - so the flag on this copy stays false for the life of the process.
     */
    public static final NocturnalCapability INSTANCE = new NocturnalCapability();

    public static final MapCodec<NocturnalCapability> CODEC = MapCodec.unit(INSTANCE);

    /** Set by a coffee bean; saved, because "woken once" outlives the night it was woken in. */
    private boolean awake;

    public NocturnalCapability() {
    }

    /** True once a coffee bean has woken this plant; then it never sleeps again. */
    public boolean isAwake() {
        return awake;
    }

    @Override
    public PlantCapability instantiate() {
        // The waking flag is per plant, so this is the one built-in capability whose
        // instance carries state and therefore cannot be shared between two mushrooms.
        return new NocturnalCapability();
    }

    @Override
    public boolean asleep(PlantEntity plant, LevelAccess level) {
        return !awake && !level.isNight();
    }

    @Override
    public boolean ticksWhileAsleep(PlantEntity plant) {
        // The sleeping plant still has to say that it is asleep; everything else about it
        // is skipped by PlantEntity.
        return true;
    }

    @Override
    public void tick(PlantEntity plant, LevelAccess level) {
        if (asleep(plant, level)) {
            // Through `setState`, so a grown sun-shroom sleeps in its grown art
            // (`sleep_big`): the mushroom's growth is not this capability's business, and
            // the suffix is what keeps it from having to be.
            plant.setState(EntityAnimations.SLEEP);
        }
    }

    @Override
    public boolean wake(PlantEntity plant) {
        if (awake) {
            return false;
        }
        awake = true;
        plant.setState(EntityAnimations.IDLE);
        return true;
    }

    @Override
    public void save(CompoundTag tag) {
        if (awake) {
            tag.putByte("Awake", (byte) 1);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        awake = tag.getInt("Awake") != 0;
    }
}
