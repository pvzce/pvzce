package com.pvzce.server.entity;

import com.pvzce.api.content.ProjectileDef;
import com.pvzce.api.content.ZombieDef;
import com.pvzce.api.content.ZombieStatus;
import com.pvzce.api.content.capability.TypedCapability;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.EntityKind;
import com.pvzce.api.entity.EntityLayers;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.core.PlantPlacement;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.tag.PvzceTags;
import com.pvzce.server.Team;
import com.pvzce.server.level.LevelServer;
import com.pvzce.common.PvzceParticles;

import java.util.ArrayList;
import java.util.List;

/**
 * Data-driven zombie: walks and eats (the loop every zombie shares) and delegates
 * everything else to its {@link ZombieCapability} instances.
 *
 * <p>The {@code behavior} string switch, the armor list, the phase list and every
 * per-special timer that used to live here are gone; only state common to all
 * zombies (position, health, bite cooldown, speed boost, statuses) remains.
 */
public class ZombieEntity extends PvzceEntity {
    private final ZombieDef def;
    private final List<Instance> capabilities = new ArrayList<>();
    private final List<StatusInstance> statuses = new ArrayList<>();
    private int biteCooldown;
    private int leftCountdown;
    private int speedBoostTicks;
    /** Balloon zombies fly until something pops the balloon. */
    private boolean grounded = true;

    public ZombieEntity(ZombieDef def, Team team, float cellX, int gridY) {
        super(def.id(), team, cellX, gridY + 0.5F, def.health());
        this.def = def;
        for (TypedCapability<ZombieCapability> entry : def.resolvedCapabilities()) {
            capabilities.add(new Instance(entry.type(), entry.value().instantiate()));
        }
        this.grounded = capabilities.stream().noneMatch(instance -> instance.capability.spawnsAirborne());
    }

    public ZombieDef def() {
        return def;
    }

    /** True while a flier is still airborne (its balloon has not been popped). */
    public boolean isGrounded() {
        return grounded;
    }

    public void setGrounded(boolean grounded) {
        this.grounded = grounded;
    }

    public void setSpeedBoost(int ticks) {
        this.speedBoostTicks = Math.max(this.speedBoostTicks, ticks);
    }

    public boolean canBeHitByGround() {
        for (Instance instance : capabilities) {
            if (!instance.capability.canBeHitByGround(this)) {
                return false;
            }
        }
        return true;
    }

    /** The live capability instance of the given type, or {@code null}. */
    public <T extends ZombieCapability> T capability(Class<T> type) {
        for (Instance instance : capabilities) {
            if (type.isInstance(instance.capability)) {
                return type.cast(instance.capability);
            }
        }
        return null;
    }

    @Override
    public String entityKind() {
        return EntityKind.ZOMBIE;
    }

    @Override
    public int layer() {
        for (Instance instance : capabilities) {
            int override = instance.capability.layerOverride(this);
            if (override != Integer.MIN_VALUE) {
                return override;
            }
        }
        return EntityLayers.GROUND;
    }

    @Override
    public void tick(LevelServer level) {
        if (removed) {
            return;
        }
        tickStatuses();
        if (drownInWater(level)) {
            return;
        }
        for (Instance instance : capabilities) {
            instance.capability.tick(this, level);
            if (removed) {
                return;
            }
        }
        for (Instance instance : capabilities) {
            if (instance.capability.tickMovement(this, level)) {
                return;
            }
        }
        walkOrEat(level);
    }

    /** Land zombies without {@code can_swim} drown when their cell becomes water. */
    private boolean drownInWater(LevelServer level) {
        if (!grounded || def.canSwim()) {
            return false;
        }
        var scene = level.sceneAt(gridX(), gridY());
        // The tag, not the surface class string: a pack that adds its own water tile
        // (swamp, pool) tags it #c:water and drowning follows without a code change.
        if (scene != null && PlantPlacement.terrainTagged(
                PlantPlacement.Terrain.of(scene), PvzceTags.SCENE_WATER)) {
            remove();
            setAnimation(EntityAnimations.DEATH);
            level.emitEffect(PvzceParticles.POOL_SPLASH.toString(), cellX(), cellY(), PvzceSounds.ZOMBIE_SPLASH);
            // A body going under disturbs the surface, and this is the one place the
            // simulation decides that happened - so the ripple is raised here rather
            // than guessed at by the renderer from a zombie disappearing from view.
            level.emitRippleAt(gridX(), gridY(), 1F);
            return true;
        }
        return false;
    }

    private void walkOrEat(LevelServer level) {
        var scene = level.sceneAt(gridX(), gridY());
        if (scene != null) {
            setHeight(scene.heightAt(cellX(), level.width()));
        }
        if (isImmobilized()) {
            setAnimation(EntityAnimations.IDLE);
            return;
        }
        PlantEntity plant = level.plantAt(gridX(), gridY());
        if (plant != null) {
            setAnimation(EntityAnimations.EAT);
            if (biteCooldown > 0) {
                biteCooldown--;
            } else {
                int damage = Math.round(def.biteDamage()
                        * level.rules().getFloat(PvzceIds.RULE_ZOMBIE_DAMAGE_MULTIPLIER));
                plant.damage(damage);
                biteCooldown = def.biteIntervalTicks();
                level.emitEffect(PvzceParticles.CHOMP.toString(), plant.cellX(), plant.cellY(),
                        def.sounds().bite().orElse(PvzceSounds.EFFECT_BITE));
            }
            return;
        }
        setAnimation(EntityAnimations.WALK);
        setCellX(cellX() - moveSpeed(level) / PvzceConstants.TICKS_PER_SECOND);
        if (biteCooldown > 0) {
            biteCooldown--;
        }
        checkReachedLeft(level);
    }

    /** Move speed after capability multipliers, the speed boost and statuses. */
    public float moveSpeed(LevelAccess level) {
        float speed = def.moveSpeed();
        for (Instance instance : capabilities) {
            speed *= instance.capability.speedMultiplier(this);
        }
        if (speedBoostTicks > 0) {
            speed *= 3F;
        }
        for (StatusInstance status : statuses) {
            if (status.status == ZombieStatus.SLOW) {
                speed *= status.magnitude;
            }
        }
        return speed * level.rules().getFloat(PvzceIds.RULE_ZOMBIE_SPEED_MULTIPLIER);
    }

    public void checkReachedLeft(LevelAccess level) {
        if (cellX() <= -0.4F) {
            leftCountdown++;
            if (leftCountdown >= 60) {
                level.zombieReachedLeft(this);
            }
        } else {
            leftCountdown = 0;
        }
    }

    /**
     * Projectile damage pipeline: capabilities get first refusal (armor), then a
     * flier is grounded, then the body takes the hit.
     */
    public void damage(ProjectileDef projectile, int amount, LevelAccess level) {
        if (removed) {
            return;
        }
        int dmg = Math.max(1, Math.round(amount
                * level.rules().getFloat(PvzceIds.RULE_PLANT_DAMAGE_MULTIPLIER)));
        for (Instance instance : capabilities) {
            if (instance.capability.onProjectileHit(this, projectile, dmg, level)) {
                return;
            }
        }
        if (!grounded) {
            grounded = true;
            setAnimation(EntityAnimations.FALL);
            level.emitEffect("", cellX(), cellY(),
                    def.sounds().special().orElse(PvzceSounds.ZOMBIE_BALLOON_POP));
        }
        damageBody(dmg, level);
        if (!removed) {
            Identifier hitSound = projectile.sounds().impact()
                    .orElse(def.sounds().hit().orElse(PvzceSounds.PROJECTILE_HIT));
            level.emitEffect(PvzceParticles.HIT_SPARK.toString(), cellX(), cellY(), hitSound);
        }
    }

    /**
     * Impact damage from something that is not a shot: a rolling bowling nut, a giant's
     * fist, a hammer.
     *
     * <p>Armor still absorbs it (a Conehead has to be hit twice by a Wall-nut, a
     * Buckethead three times, exactly as in the original), which is why this is not
     * {@link #damageBody}: that path exists for explosions and deliberately ignores armor.
     */
    public void damageImpact(int amount, LevelAccess level) {
        if (removed) {
            return;
        }
        int dmg = Math.max(1, Math.round(amount
                * level.rules().getFloat(PvzceIds.RULE_PLANT_DAMAGE_MULTIPLIER)));
        for (Instance instance : capabilities) {
            if (instance.capability.onImpact(this, dmg, level)) {
                return;
            }
        }
        damageBody(dmg, level);
    }

    /** Explosions / area damage bypass armor. */
    public void damageBody(int amount, LevelAccess level) {
        if (removed) {
            return;
        }
        setHealth(Math.max(0, health() - amount));
        setAnimation(EntityAnimations.HIT);
        if (health() <= 0) {
            remove();
            // No detached-head particle: the sprite is a whole head with its own motion,
            // and drawn as one burst per death it read as a second zombie rather than as
            // the first one coming apart. The sound is the death cue.
            level.emitEffect("", cellX(), cellY(),
                    def.sounds().death().orElse(PvzceSounds.ZOMBIE_LIMBS_POP));
            for (Instance instance : capabilities) {
                instance.capability.onDeath(this, level);
            }
            // Last, so a capability cannot resurrect a zombie that already paid out.
            level.zombieDied(this);
        }
    }

    /** Applies (or refreshes) a status effect; the caller supplies strength and duration. */
    public void applyStatus(ZombieStatus status, int ticks, float magnitude) {
        if (ticks <= 0) {
            return;
        }
        for (StatusInstance instance : statuses) {
            if (instance.status == status) {
                instance.ticks = Math.max(instance.ticks, ticks);
                if (status == ZombieStatus.SLOW) {
                    instance.magnitude = magnitude;
                }
                return;
            }
        }
        statuses.add(new StatusInstance(status, ticks, magnitude));
    }

    /** Total remaining armor HP, or zero when the zombie has no armor capability. */
    public int armorHealth() {
        com.pvzce.common.capability.zombie.ArmorCapability armor =
                capability(com.pvzce.common.capability.zombie.ArmorCapability.class);
        return armor == null ? 0 : armor.totalHealth();
    }

    /** True when the zombie currently carries the given status (tests / HUD). */
    public boolean hasStatus(ZombieStatus status) {
        return statuses.stream().anyMatch(instance -> instance.status == status);
    }

    private void tickStatuses() {
        if (speedBoostTicks > 0) {
            speedBoostTicks--;
        }
        for (StatusInstance status : statuses) {
            status.ticks--;
        }
        statuses.removeIf(status -> status.ticks <= 0);
    }

    private boolean isImmobilized() {
        return statuses.stream().anyMatch(status -> status.status == ZombieStatus.IMMOBILIZED);
    }

    @Override
    public CompoundTag saveState() {
        CompoundTag tag = saveBaseState();
        tag.putInt("biteCooldown", biteCooldown);
        tag.putInt("leftCountdown", leftCountdown);
        tag.putInt("speedBoostTicks", speedBoostTicks);
        tag.putInt("grounded", grounded ? 1 : 0);

        CompoundTag saved = new CompoundTag();
        for (Instance instance : capabilities) {
            CompoundTag capabilityTag = new CompoundTag();
            instance.capability.save(capabilityTag);
            saved.put(instance.type.toString(), capabilityTag);
        }
        tag.put("capabilities", saved);

        ListTag statusList = new ListTag();
        for (StatusInstance status : statuses) {
            CompoundTag statusTag = new CompoundTag();
            statusTag.putString("status", status.status.name());
            statusTag.putInt("ticks", status.ticks);
            statusTag.putFloat("magnitude", status.magnitude);
            statusList.add(statusTag);
        }
        tag.put("statuses", statusList);
        return tag;
    }

    @Override
    public void restoreState(CompoundTag tag) {
        restoreBaseState(tag);
        biteCooldown = tag.getInt("biteCooldown");
        leftCountdown = tag.getInt("leftCountdown");
        speedBoostTicks = tag.getInt("speedBoostTicks");
        grounded = !tag.contains("grounded") || tag.getInt("grounded") != 0;

        CompoundTag saved = tag.getCompound("capabilities");
        for (Instance instance : capabilities) {
            instance.capability.load(saved.getCompound(instance.type.toString()));
        }

        statuses.clear();
        for (var element : tag.getList("statuses").values()) {
            if (!(element instanceof CompoundTag statusTag)) {
                continue;
            }
            try {
                ZombieStatus status = ZombieStatus.valueOf(statusTag.getString("status"));
                int ticks = statusTag.getInt("ticks");
                if (ticks > 0) {
                    statuses.add(new StatusInstance(status, ticks, statusTag.getFloat("magnitude")));
                }
            } catch (IllegalArgumentException ignored) {
                // Unknown status from a future/foreign save; skip it rather than fail the load.
            }
        }
    }

    private record Instance(Identifier type, ZombieCapability capability) {
    }

    private static final class StatusInstance {
        private final ZombieStatus status;
        private int ticks;
        private float magnitude;

        private StatusInstance(ZombieStatus status, int ticks, float magnitude) {
            this.status = status;
            this.ticks = ticks;
            this.magnitude = magnitude;
        }
    }
}
