package com.pvzce.server.entity;

import com.pvzce.api.content.ProjectileDef;
import com.pvzce.api.content.ProjectileRef;
import com.pvzce.api.content.capability.ProjectileCapability;
import com.pvzce.api.content.capability.TypedCapability;
import com.pvzce.api.entity.EntityKind;
import com.pvzce.api.entity.EntityLayers;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.capability.projectile.ArcMotionCapability;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.Team;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.List;

/**
 * Data-driven projectile: motion, impact and status application all come from its
 * {@link ProjectileCapability} instances.
 *
 * <p>The collision rule (which zombies a shot may touch) is shared by every
 * projectile and stays here; what used to make {@code linear} and {@code arc}
 * behave differently is now the motion capability that owns it.
 */
public class ProjectileEntity extends PvzceEntity {
    /** Horizontal hit window against a zombie, in cells. */
    private static final float HIT_RADIUS_X = 0.4F;
    /** Air shots must also match height within this tolerance. */
    private static final float HIT_TOLERANCE_Y = 0.2F;
    /** A homing shot counts as landed at or below this height. */
    private static final float LANDED_HEIGHT = 0.15F;

    private final ProjectileDef def;
    private final List<Instance> capabilities = new ArrayList<>();
    private final int damage;
    /** Id of the zombie this shot was aimed at, or -1 for a straight shot. */
    private final int targetId;
    private final float targetX;

    public ProjectileEntity(ProjectileDef def, ProjectileRef ref, Team ownerTeam,
                            float cellX, float cellY, float startHeight) {
        this(def, ref, ownerTeam, cellX, cellY, startHeight, null);
    }

    /** Arc constructor: when {@code target} is given the motion capability solves the launch. */
    public ProjectileEntity(ProjectileDef def, ProjectileRef ref, Team ownerTeam,
                            float cellX, float cellY, float startHeight, ZombieEntity target) {
        super(def.id(), ownerTeam, cellX, cellY, 1);
        this.def = def;
        this.damage = ref != null ? ref.damage() : 0;
        this.targetId = target != null ? target.id() : -1;
        this.targetX = target != null ? target.cellX() : -1F;
        setHeight(startHeight);
        for (TypedCapability<ProjectileCapability> entry : def.resolvedCapabilities()) {
            capabilities.add(new Instance(entry.type(), entry.value().instantiate()));
        }
        if (target != null) {
            ArcMotionCapability arc = capability(ArcMotionCapability.class);
            if (arc != null) {
                arc.launch(cellX, startHeight, target.cellX(), target.height());
            }
        }
    }

    public ProjectileDef def() {
        return def;
    }

    public int damage() {
        return damage;
    }

    /** The zombie this shot is homing on, or {@code -1}. */
    public int targetId() {
        return targetId;
    }

    public float targetX() {
        return targetX;
    }

    /** True once a homing shot has come back down to lawn level. */
    public boolean hasLanded() {
        return height() <= LANDED_HEIGHT;
    }

    public <T extends ProjectileCapability> T capability(Class<T> type) {
        for (Instance instance : capabilities) {
            if (type.isInstance(instance.capability)) {
                return type.cast(instance.capability);
            }
        }
        return null;
    }

    @Override
    public String entityKind() {
        return EntityKind.PROJECTILE;
    }

    @Override
    public int layer() {
        return def.isAirLayer() ? EntityLayers.AIR : EntityLayers.PROJECTILE;
    }

    @Override
    public void tick(LevelServer level) {
        if (removed) {
            return;
        }
        for (Instance instance : capabilities) {
            if (instance.capability.move(this, level)) {
                break;
            }
        }
        if (cellX() > level.width() + 1F) {
            remove();
            return;
        }
        ZombieEntity hit = findTarget(level);
        if (hit == null) {
            if (targetId >= 0 && Math.abs(cellX() - targetX) < HIT_RADIUS_X && hasLanded()) {
                applyImpact(null, level);
            }
            return;
        }
        applyImpact(hit, level);
    }

    private void applyImpact(ZombieEntity hit, LevelServer level) {
        boolean replacesDirectHit = false;
        for (Instance instance : capabilities) {
            if (instance.capability.replacesDirectHit()) {
                replacesDirectHit = true;
            }
            instance.capability.onHit(this, hit, level);
        }
        if (!replacesDirectHit && hit != null) {
            hit.damage(def, damage, level);
        }
        for (Instance instance : capabilities) {
            if (instance.capability.pierces() && hit != null) {
                return;
            }
        }
        remove();
    }

    private ZombieEntity findTarget(LevelServer level) {
        boolean groundLayer = !def.isAirLayer();
        for (ZombieEntity zombie : level.zombiesInRow(gridY())) {
            if (zombie.isRemoved()) {
                continue;
            }
            if (targetId >= 0) {
                if (zombie.id() != targetId) {
                    continue;
                }
                // Homing shots compare against the target's top, so a lobbed shot
                // can still connect while it is descending.
                return Math.abs(cellX() - zombie.cellX()) < HIT_RADIUS_X
                        && height() <= zombie.height() + 0.18F ? zombie : null;
            }
            if (groundLayer && !zombie.canBeHitByGround()) {
                continue;
            }
            if (Math.abs(zombie.cellX() - cellX()) < HIT_RADIUS_X + 0.05F
                    && Math.abs(height() - zombie.height()) < HIT_TOLERANCE_Y) {
                return zombie;
            }
        }
        return null;
    }

    @Override
    public CompoundTag saveState() {
        CompoundTag tag = saveBaseState();
        tag.putInt("damage", damage);
        tag.putInt("targetId", targetId);
        tag.putFloat("targetX", targetX);
        CompoundTag saved = new CompoundTag();
        for (Instance instance : capabilities) {
            CompoundTag capabilityTag = new CompoundTag();
            instance.capability.save(capabilityTag);
            saved.put(instance.type.toString(), capabilityTag);
        }
        tag.put("capabilities", saved);
        return tag;
    }

    @Override
    public void restoreState(CompoundTag tag) {
        restoreBaseState(tag);
        CompoundTag saved = tag.getCompound("capabilities");
        for (Instance instance : capabilities) {
            instance.capability.load(saved.getCompound(instance.type.toString()));
        }
    }

    private record Instance(Identifier type, ProjectileCapability capability) {
    }
}
