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
    /**
     * What one hit of this shot is worth.
     *
     * <p>Not final, because a torchwood upgrades a pea as it flies through it - the one thing in
     * the game that changes a shot already in the air. The upgrade is guarded by {@link #torched}
     * so a row of torchwoods cannot stack: the original burns a pea once, whatever it flies
     * through on the way.
     */
    private int damage;
    /** True once a torchwood has had its way with this shot. */
    private boolean torched;
    /**
     * Which way this shot travels: {@code +1} down the lawn, {@code -1} back toward the
     * house. A split pea fires both at once, so the direction belongs to the shot
     * rather than to the projectile definition - the same pea is used for both.
     */
    private final float direction;
    /** Id of the zombie this shot was aimed at, or -1 for a straight shot. */
    private final int targetId;
    private final float targetX;
    /**
     * Where the shot was fired from and how far it may travel, in cells
     * ({@link ProjectileRef#UNLIMITED_RANGE} = the whole board).
     *
     * <p>Carried by the entity rather than by the motion capability because it is a
     * property of the <em>shot</em>: the same spore art is fired by a plant that reaches
     * three cells, and a projectile definition that hard-coded a distance would make that
     * unreachable. The reference the plant fired is the only place the number exists.
     */
    private final float originX;
    private final float maxRange;
    /**
     * Zombies this shot has already damaged.
     *
     * <p>A piercing shot does not disappear on its first hit - that is what
     * {@link com.pvzce.common.capability.projectile.PierceCapability} claims - so without this
     * it damages whatever it is overlapping on <em>every</em> tick it overlaps it. A spray
     * moving one cell per 25 ticks spends a dozen ticks inside a zombie, and each of them was
     * another hit: a 20-damage fume spray killed a 200-health zombie in ten consecutive ticks
     * and looked like an instant kill. "Pierces" means "passes through and hits the next one",
     * not "hits this one again".
     *
     * <p>Ids rather than a single "last hit" because the shot is not guaranteed to leave a
     * target before reaching the next: two zombies standing in each other's cells are both
     * inside the hit radius, and a shot that only remembered the last one would damage the
     * first over and over from the other side.
     */
    private final java.util.Set<Integer> hitIds = new java.util.HashSet<>();

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
        this.direction = ref != null ? ref.direction() : 1F;
        this.targetId = target != null ? target.id() : -1;
        this.targetX = target != null ? target.cellX() : -1F;
        this.originX = cellX;
        this.maxRange = ref != null ? ref.range() : ProjectileRef.UNLIMITED_RANGE;
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

    /** What one hit of this shot is worth, after any torchwood that lit it. */
    public int damage() {
        return damage;
    }

    /** True once a torchwood has already upgraded this shot. */
    public boolean torched() {
        return torched;
    }

    /** The damage type a hit from this shot uses when a torchwood lit it, or {@code null}. */
    public com.pvzce.api.util.Identifier torchDamageType() {
        return torchDamageType;
    }

    private com.pvzce.api.util.Identifier torchDamageType;

    /**
     * Sets a flying shot alight: more damage, a burning type, and once only.
     *
     * <p>The whole of the torchwood's behaviour on the receiving end. A method on the projectile
     * rather than something the plant does to the projectile's fields, because the one rule that
     * matters - "this happens at most once" - is a fact about the shot's history rather than about
     * any one plant, and a row of torchwoods must not stack.
     *
     * @return true when this call is what lit it, false when it was already burning
     */
    public boolean torch(int multiplier, com.pvzce.api.util.Identifier burningType) {
        if (torched) {
            return false;
        }
        torched = true;
        this.damage = Math.max(1, damage * Math.max(1, multiplier));
        if (burningType != null) {
            this.torchDamageType = burningType;
        }
        return true;
    }

    /** {@code +1} down the lawn, {@code -1} back toward the house. */
    public float direction() {
        return direction;
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
        // A short-ranged shot dies where its plant's reach ends. Measured from the muzzle,
        // which is both where this projectile was born and what the shooter's target search
        // measures from - one origin, so "worth firing at" and "can actually reach" agree.
        if (maxRange > 0F && Math.abs(cellX() - originX) >= maxRange) {
            remove();
            return;
        }
        ZombieEntity hit = findTarget(level);
        if (hit == null) {
            PlantEntity plant = findPlantTarget(level);
            if (plant != null) {
                // A shot fired by the zombies. It is the only shot in the game that travels the
                // other way, and everything about it is the same except which registry it looks
                // in - see `findPlantTarget`.
                plant.damage(damage);
                remove();
                return;
            }
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
            hit.damage(def, damage, level, torchDamageType);
            hitIds.add(hit.id());
        }
        for (Instance instance : capabilities) {
            if (instance.capability.pierces() && hit != null) {
                return;
            }
        }
        remove();
    }

    /**
     * A plant in this shot's way, or {@code null}.
     *
     * <p>The projectile router only ever looked for zombies, because until the ZomBotany line only
     * the plants had guns: {@link #findTarget} asks {@code level.enemiesInRow}, whose answer is
     * always a list of zombies. A zombie's pea needs the other half, and this is the whole of it -
     * the same lane, the same hit radius, the other registry.
     *
     * <p>It answers null for a plant's own shot, which is what keeps the two apart: a projectile
     * hits whatever is an enemy of <em>its</em> team, and a plant's team is not its own enemy.
     *
     * <p>No armour, no damage type and no pierce: a plant has none of those, and giving a
     * zombie's pea a damage type would mean asking which of the game's damage rules apply to a
     * target that has never had any.
     */
    private PlantEntity findPlantTarget(LevelServer level) {
        Team plantTeam = level.team(com.pvzce.common.PvzceIds.PLANT_TEAM);
        if (plantTeam == null || !LevelServer.isEnemyOf(plantTeam, team())) {
            return null;
        }
        PlantEntity found = null;
        for (int column = 0; column < level.width(); column++) {
            if (Math.abs(column + 0.5F - cellX()) > HIT_RADIUS_X) {
                continue;
            }
            PlantEntity plant = level.plantAt(column, gridY());
            if (plant != null && !plant.isRemoved()) {
                // Left to right, so the last one found is the nearest to a shot flying left.
                found = plant;
            }
        }
        return found;
    }

    private ZombieEntity findTarget(LevelServer level) {
        boolean groundLayer = !def.isAirLayer();
        for (ZombieEntity zombie : level.enemiesInRow(gridY(), team())) {
            if (zombie.isRemoved()) {
                continue;
            }
            // A shot that has already landed on this one keeps flying - it does not land again.
            if (hitIds.contains(zombie.id())) {
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
