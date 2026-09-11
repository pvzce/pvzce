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

/** Pole vaulter: hops over the first plant it meets, then walks normally. */
public final class VaultCapability implements ZombieCapability {
    /** Cells cleared by the hop; lands past the vaulted plant. */
    public static final float DEFAULT_JUMP_DISTANCE = 1.4F;

    private final float jumpDistance;
    private final Optional<Identifier> sound;

    private boolean jumped;

    public VaultCapability(float jumpDistance, Optional<Identifier> sound) {
        this.jumpDistance = jumpDistance;
        this.sound = sound;
    }

    public static final MapCodec<VaultCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.optionalFieldOf("jump_distance", DEFAULT_JUMP_DISTANCE)
                    .forGetter(VaultCapability::jumpDistance),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(VaultCapability::sound)
    ).apply(i, VaultCapability::new));

    public float jumpDistance() {
        return jumpDistance;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    @Override
    public ZombieCapability instantiate() {
        return new VaultCapability(jumpDistance, sound);
    }

    @Override
    public boolean tickMovement(ZombieEntity zombie, LevelAccess level) {
        if (jumped) {
            return false;
        }
        PlantEntity plant = level.plantAt(zombie.gridX(), zombie.gridY());
        if (plant == null) {
            return false;
        }
        jumped = true;
        zombie.setCellX(zombie.cellX() - jumpDistance);
        zombie.setAnimation(EntityAnimations.JUMP);
        level.emitEffect("", zombie.cellX(), zombie.cellY(),
                sound.orElseGet(() -> zombie.def().sounds().special().orElse(PvzceSounds.ZOMBIE_POLEVAULT)));
        return true;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("jumped", jumped ? 1 : 0);
    }

    @Override
    public void load(CompoundTag tag) {
        jumped = tag.getInt("jumped") != 0;
    }
}
