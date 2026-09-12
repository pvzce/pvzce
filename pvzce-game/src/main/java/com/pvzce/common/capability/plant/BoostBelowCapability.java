package com.pvzce.common.capability.plant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceSounds;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.common.PvzceParticles;

import java.util.List;
import java.util.Optional;

/**
 * Instant-use support plant: boosts the plant it was stacked on and is consumed
 * by the placement (coffee bean).
 *
 * <p>This used to be an {@code onPlaced} special case keyed on the string
 * {@code "pvzce:support"} plus a hard-coded "the plant below must be a shooter"
 * assumption; the boost is now a capability the boosted plant declares.
 */
public final class BoostBelowCapability implements PlantCapability {
    private final Optional<Identifier> sound;
    /** Overrides the boosted plant's sound; empty = use the boosted plant's own produce sound. */
    private final Optional<Identifier> boostedSound;

    public BoostBelowCapability(Optional<Identifier> sound, Optional<Identifier> boostedSound) {
        this.sound = sound;
        this.boostedSound = boostedSound;
    }

    public static final MapCodec<BoostBelowCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Identifier.CODEC.optionalFieldOf("sound").forGetter(BoostBelowCapability::sound),
            Identifier.CODEC.optionalFieldOf("boosted_sound").forGetter(BoostBelowCapability::boostedSound)
    ).apply(i, BoostBelowCapability::new));

    public Optional<Identifier> sound() {
        return sound;
    }

    public Optional<Identifier> boostedSound() {
        return boostedSound;
    }

    @Override
    public PlantCapability instantiate() {
        return this;
    }

    @Override
    public boolean consumesOnPlace() {
        return true;
    }

    @Override
    public void onPlaced(PlantEntity plant, LevelAccess level) {
        List<PlantEntity> stacked = level.plantsAt(plant.gridX(), plant.gridY());
        PlantEntity target = null;
        for (int i = stacked.size() - 1; i >= 0; i--) {
            if (stacked.get(i) != plant) {
                target = stacked.get(i);
                break;
            }
        }
        if (target != null) {
            PlantEntity boosted = target;
            boosted.boost();
            level.emitEffect(PvzceParticles.LANTERN_SHINE.toString(), plant.cellX(), plant.cellY(),
                    boostedSound.orElseGet(() -> boosted.def().sounds().produce()
                            .orElse(sound.orElse(PvzceSounds.PLANT_WAKEUP))));
        } else {
            level.emitEffect(PvzceParticles.LANTERN_SHINE.toString(), plant.cellX(), plant.cellY(),
                    sound.orElse(PvzceSounds.UI_TAP));
        }
        plant.remove();
    }
}
