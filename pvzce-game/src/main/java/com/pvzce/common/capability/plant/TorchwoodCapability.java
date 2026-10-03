package com.pvzce.common.capability.plant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ProjectileEntity;

/**
 * Sets alight every shot that flies through it (the torchwood).
 *
 * <p>The one plant whose target is another plant's projectile. A pea that crosses a torchwood keeps
 * flying, keeps its lane and its art - and does twice the damage as a burning hit, which is what
 * makes a torchwood behind a row of repeaters the strongest thing on the lawn.
 *
 * <h2>How it finds the shots</h2>
 *
 * <p>By asking the level what is in its own cell, once a tick. The alternative - an engine hook
 * that offers every projectile to every plant it passes - is a new concept in the hot path for one
 * plant; the level already keeps its entities in a list, and a cell's worth of them is a scan of a
 * few dozen. A torchwood on a board with no shooters does that scan and finds nothing, which costs
 * less than the bookkeeping the hook would need.
 *
 * <h2>Once per shot</h2>
 *
 * <p>{@code ProjectileEntity.torch} is what remembers, not this class: a row of torchwoods must
 * burn a pea once rather than multiplying it by the row's length. That is a fact about the shot's
 * history, so it lives on the shot.
 */
public final class TorchwoodCapability implements PlantCapability {
    /** What a burning shot is worth, as a multiple of the plain one. */
    public static final int DEFAULT_MULTIPLIER = 2;
    /**
     * What kind of hit a burning shot is: whatever the projectile's own definition says.
     *
     * <p>Null, and that is the honest answer rather than a shortcut. The fire pea already exists
     * (the Mendel mutation substitutes one for an ordinary pea) and its definition carries no
     * special damage type - "burning" in this build is a projectile <em>identity</em> rather than a
     * damage type, and inventing a {@code pvzce:fire} type here would be a second answer to a
     * question the content already answered.
     *
     * <p>So what the torchwood changes is the damage, and a level or a mod that wants the hit
     * itself to be different writes {@code burning_type} in the block.
     */
    public static final Identifier DEFAULT_BURNING_TYPE = null;

    private final int multiplier;
    private final Identifier burningType;

    public TorchwoodCapability(int multiplier, Identifier burningType) {
        this.multiplier = Math.max(1, multiplier);
        this.burningType = burningType;
    }

    public static final MapCodec<TorchwoodCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.optionalFieldOf("multiplier", DEFAULT_MULTIPLIER)
                    .forGetter(TorchwoodCapability::multiplier),
            Identifier.CODEC.optionalFieldOf("burning_type")
                    .forGetter(capability -> java.util.Optional.ofNullable(
                            capability.burningType()))
    ).apply(i, (multiplier, burningType) ->
            new TorchwoodCapability(multiplier, burningType.orElse(null))));

    public int multiplier() {
        return multiplier;
    }

    /** The type a burning hit uses, or {@code null} to keep the projectile's own. */
    public Identifier burningType() {
        return burningType;
    }

    @Override
    public PlantCapability instantiate() {
        return this;
    }

    @Override
    public void tick(PlantEntity plant, LevelAccess level) {
        boolean lit = false;
        float weather = level.weatherTorchMultiplier();
        if (weather <= 0F) {
            litThisTick = false;
            return;
        }
        for (ProjectileEntity shot : level.projectilesInCell(plant.gridX(), plant.gridY())) {
            // Only its own side's shots. A torchwood that burned the zombies' own peas would be a
            // defensive plant as well as an offensive one, which is not what it is.
            if (com.pvzce.server.level.LevelServer.isEnemyOf(shot.team(), plant.team())
                    && !shot.team().equals(plant.team())) {
                continue;
            }
            if (shot.torch(multiplier * weather, burningType)) {
                lit = true;
            }
        }
        if (lit) {
            // The plant's own art does not change - the flame is its idle - so the report is the
            // sound and the flag: the original's ignite, which is a different thing from the pea
            // hitting something and used to be played as the mallet's knock by mistake.
            litThisTick = true;
            level.emitEffect("", plant.cellX(), plant.cellY(),
                    com.pvzce.common.PvzceSounds.PLANT_FIREPEA);
        } else {
            litThisTick = false;
        }
    }

    private boolean litThisTick;

    /** True on the tick this torchwood lit at least one shot. */
    public boolean litThisTick() {
        return litThisTick;
    }

    @Override
    public void save(CompoundTag tag) {
        // Nothing of its own: the shots it has lit carry that fact themselves, and a torchwood
        // that is reloaded must not re-light a pea that is already burning.
    }
}
