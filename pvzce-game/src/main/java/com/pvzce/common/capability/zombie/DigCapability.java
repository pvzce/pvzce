package com.pvzce.common.capability.zombie;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.EntityLayers;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.ZombieEntity;
import java.util.Optional;

/** Tunnels once; after surfacing the shared walking and biting loop runs facing right. */
public final class DigCapability implements ZombieCapability {
    public static final float DEFAULT_SPEED = 1.2F;
    public static final int DEFAULT_EMERGE_TICKS = PvzceConstants.DIGGER_RISE_TICKS;
    private final float digSpeed;
    private final int emergeTicks;
    private final Optional<Identifier> sound;
    private boolean underground = true;
    private boolean movingRight;
    private boolean hasAxe = true;
    private boolean surfaced;
    private int riseLeft;
    private int dizzyLeft;
    private int pauseLeft;

    public DigCapability(float digSpeed, int emergeTicks, Optional<Identifier> sound) {
        this.digSpeed = digSpeed;
        this.emergeTicks = Math.max(1, emergeTicks);
        this.sound = sound;
    }
    public static final MapCodec<DigCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.optionalFieldOf("speed", DEFAULT_SPEED).forGetter(DigCapability::digSpeed),
            Codec.INT.optionalFieldOf("emerge_ticks", DEFAULT_EMERGE_TICKS).forGetter(DigCapability::emergeTicks),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(DigCapability::sound)
    ).apply(i, DigCapability::new));
    public float digSpeed() { return digSpeed; }
    public int emergeTicks() { return emergeTicks; }
    public Optional<Identifier> sound() { return sound; }
    public boolean isUnderground() { return underground; }
    @Override public ZombieCapability instantiate() { return new DigCapability(digSpeed, emergeTicks, sound); }
    @Override public float walkDirection(ZombieEntity zombie) { return movingRight ? 1F : -1F; }
    @Override public String walkState(ZombieEntity zombie) {
        return surfaced ? (movingRight ? (hasAxe ? "walk_right" : "walk_noaxe_right") : "walk_noaxe") : null;
    }
    @Override public String eatState(ZombieEntity zombie) {
        return movingRight ? (hasAxe ? "eat_right" : "eat_noaxe_right") : (!hasAxe ? "eat_noaxe" : null);
    }
    @Override public String deathState(ZombieEntity zombie) {
        return movingRight ? (hasAxe ? "death_right" : "death_noaxe_right") : (!hasAxe ? "death_noaxe" : null);
    }
    @Override public boolean tickMovement(ZombieEntity zombie, LevelAccess level) {
        if (zombie.isImmobilized()) return true;
        if (pauseLeft > 0) { pauseLeft--; zombie.setAnimation("dig_noaxe"); return true; }
        if (riseLeft > 0) {
            zombie.setAnimation(riseLeft > PvzceConstants.DIGGER_LAND_TICKS
                    ? (hasAxe ? "dig_rise" : "dig_rise_noaxe")
                    : (hasAxe ? EntityAnimations.DIG_EXIT : "dig_exit_noaxe"));
            // The drill artwork already contains the dirt and rising body. Height is the
            // terrain anchor; burial and damage eligibility belong to the underground layer.
            zombie.setHeight(level.surfaceHeight(zombie.surfaceId(), zombie.cellX(), zombie.cellY()));
            if (--riseLeft == 0) {
                underground = false; surfaced = true; zombie.setHeight(level.surfaceHeight(zombie.surfaceId(), zombie.cellX(), zombie.cellY()));
                movingRight = hasAxe;
                dizzyLeft = hasAxe ? PvzceConstants.DIGGER_DIZZY_TICKS : 0;
            }
            return true;
        }
        if (dizzyLeft > 0) {
            dizzyLeft--; zombie.setAnimation(hasAxe ? "dig_dizzy_right" : "dig_dizzy_noaxe_right"); return true;
        }
        if (surfaced) return false;
        if (underground && (zombie.cellX() <= 0.5F || !hasAxe)) {
            riseLeft = emergeTicks;
            level.emitEffect("", zombie.position(), zombie.surfaceId(), PvzceSounds.EFFECT_DIRT_RISE);
            return true;
        }
        zombie.setHeight(level.surfaceHeight(zombie.surfaceId(), zombie.cellX(), zombie.cellY()));
        zombie.setAnimation(EntityAnimations.DIG);
        zombie.setCellX(zombie.cellX() - digSpeed / PvzceConstants.TICKS_PER_SECOND
                * zombie.moveSpeed(level) / zombie.def().moveSpeed());
        return true;
    }
    @Override public int layerOverride(ZombieEntity zombie) {
        return underground ? EntityLayers.UNDERGROUND : Integer.MIN_VALUE;
    }
    @Override public boolean canBeHitByGround(ZombieEntity zombie) { return !underground; }
    @Override public Identifier magneticItem(ZombieEntity zombie) {
        return hasAxe ? PvzceIds.id("pickaxe") : null;
    }
    @Override public boolean removeMagneticItem(ZombieEntity zombie, LevelAccess level) {
        if (!hasAxe) return false;
        hasAxe = false;
        if (underground && riseLeft == 0) pauseLeft = PvzceConstants.DIGGER_AXE_PAUSE_TICKS;
        return true;
    }
    @Override public void save(CompoundTag tag) {
        tag.putInt("underground", underground ? 1 : 0); tag.putInt("movingRight", movingRight ? 1 : 0);
        tag.putInt("hasAxe", hasAxe ? 1 : 0); tag.putInt("surfaced", surfaced ? 1 : 0);
        tag.putInt("riseLeft", riseLeft); tag.putInt("dizzyLeft", dizzyLeft); tag.putInt("pauseLeft", pauseLeft);
    }
    @Override public void load(CompoundTag tag) {
        underground = tag.getInt("underground") != 0;
        movingRight = tag.getInt("movingRight") != 0;
        hasAxe = !tag.contains("hasAxe") || tag.getInt("hasAxe") != 0;
        surfaced = tag.contains("surfaced") ? tag.getInt("surfaced") != 0 : movingRight;
        riseLeft = tag.contains("riseLeft") ? tag.getInt("riseLeft") : tag.getInt("emergeCooldown");
        dizzyLeft = tag.getInt("dizzyLeft"); pauseLeft = tag.getInt("pauseLeft");
    }
}
