package com.pvzce.common.capability.zombie;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;

import java.util.Optional;

/**
 * Gargantuar: smashes whatever plant is in its cell with one hammer blow and can
 * throw imps onto the lawn ahead of itself.
 */
public final class HammerCapability implements ZombieCapability {
    public static final int DEFAULT_INTERVAL = 90;
    /** Ticks between imp throws when {@code throws_imp} is enabled. */
    public static final int DEFAULT_IMP_INTERVAL = 600;

    private final int hammerIntervalTicks;
    private final boolean throwsImp;
    private final Identifier imp;
    private final int impIntervalTicks;
    private final int impCellsAhead;
    private final Optional<Identifier> hammerSound;

    private int hammerCooldown;
    private int impCooldown;

    public HammerCapability(int hammerIntervalTicks, boolean throwsImp, Identifier imp, int impIntervalTicks,
                            int impCellsAhead, Optional<Identifier> hammerSound) {
        this.hammerIntervalTicks = Math.max(1, hammerIntervalTicks);
        this.throwsImp = throwsImp;
        this.imp = imp;
        this.impIntervalTicks = Math.max(1, impIntervalTicks);
        this.impCellsAhead = Math.max(0, impCellsAhead);
        this.hammerSound = hammerSound;
        this.impCooldown = this.impIntervalTicks;
    }

    public static final MapCodec<HammerCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.optionalFieldOf("interval", DEFAULT_INTERVAL).forGetter(HammerCapability::hammerIntervalTicks),
            Codec.BOOL.optionalFieldOf("throws_imp", false).forGetter(HammerCapability::throwsImp),
            Identifier.CODEC.optionalFieldOf("imp", Identifier.withDefaultNamespace("imp"))
                    .forGetter(HammerCapability::imp),
            Codec.INT.optionalFieldOf("imp_interval", DEFAULT_IMP_INTERVAL)
                    .forGetter(HammerCapability::impIntervalTicks),
            Codec.INT.optionalFieldOf("imp_cells_ahead", 3).forGetter(HammerCapability::impCellsAhead),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(HammerCapability::hammerSound)
    ).apply(i, HammerCapability::new));

    public int hammerIntervalTicks() {
        return hammerIntervalTicks;
    }

    public boolean throwsImp() {
        return throwsImp;
    }

    public Identifier imp() {
        return imp;
    }

    public int impIntervalTicks() {
        return impIntervalTicks;
    }

    public int impCellsAhead() {
        return impCellsAhead;
    }

    public Optional<Identifier> hammerSound() {
        return hammerSound;
    }

    @Override
    public ZombieCapability instantiate() {
        return new HammerCapability(hammerIntervalTicks, throwsImp, imp, impIntervalTicks, impCellsAhead, hammerSound);
    }

    @Override
    public void tick(ZombieEntity zombie, LevelAccess level) {
        if (hammerCooldown > 0) {
            hammerCooldown--;
        } else {
            PlantEntity plant = level.plantAt(zombie.gridX(), zombie.gridY());
            if (plant != null) {
                plant.remove();
                hammerCooldown = hammerIntervalTicks;
                zombie.setAnimation(EntityAnimations.HAMMER);
                level.emitEffect("pvzce:ash_smoke", zombie.cellX(), zombie.cellY(),
                        hammerSound.orElseGet(() -> zombie.def().sounds().special()
                                .orElse(PvzceSounds.ZOMBIE_GARGANTUAR_THUMP)));
            }
        }
        if (!throwsImp) {
            return;
        }
        if (impCooldown > 0) {
            impCooldown--;
            return;
        }
        impCooldown = impIntervalTicks;
        level.spawnZombie(imp, zombie.team(), Math.max(1F, zombie.gridX() - impCellsAhead), zombie.gridY());
        level.emitEffect("", zombie.cellX(), zombie.cellY(), PvzceSounds.ZOMBIE_IMP);
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("hammerCooldown", hammerCooldown);
        tag.putInt("impCooldown", impCooldown);
    }

    @Override
    public void load(CompoundTag tag) {
        hammerCooldown = tag.getInt("hammerCooldown");
        impCooldown = tag.getInt("impCooldown");
    }
}
