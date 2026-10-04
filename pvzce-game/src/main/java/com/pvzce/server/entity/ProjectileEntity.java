package com.pvzce.server.entity;

import com.pvzce.common.level.WorldPosition;
import com.pvzce.api.content.ProjectileDef;
import com.pvzce.api.content.ProjectileRef;
import com.pvzce.api.content.capability.ProjectileCapability;
import com.pvzce.api.content.capability.TypedCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.EntityKind;
import com.pvzce.api.entity.EntityLayers;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.capability.projectile.ArcMotionCapability;
import com.pvzce.common.core.PlantPlacement;
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
    private float direction;
    /** Id of the zombie this shot was aimed at, or -1 for a straight shot. */
    private int targetId;
    private float targetX;
    private float targetHeight;
    private float targetY;
    /**
     * True when the shot was aimed at a cell rather than at a zombie.
     *
     * <p>The two are the same flight with different endings, and this is the flag that says which:
     * without it the impact test below would read "names no zombie" as "has nowhere to be" and an
     * aimed cob would fly off the end of the board instead of going off.
     */
    private boolean aimedAtPoint;
    /**
     * Where the shot was fired from and how far it may travel, in cells
     * ({@link ProjectileRef#UNLIMITED_RANGE} = the whole board).
     *
     * <p>Carried by the entity rather than by the motion capability because it is a
     * property of the <em>shot</em>: the same spore art is fired by a plant that reaches
     * three cells, and a projectile definition that hard-coded a distance would make that
     * unreachable. The reference the plant fired is the only place the number exists.
     */
    private float originX;
    private float originY;
    private float vectorX;
    private float vectorY;
    private float maxRange;
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
        this(def, ref, ownerTeam, cellX, cellY, startHeight, (ZombieEntity) null);
    }

    /** Arc constructor: when {@code target} is given the motion capability solves the launch. */
    public ProjectileEntity(ProjectileDef def, ProjectileRef ref, Team ownerTeam,
                            float cellX, float cellY, float startHeight, ZombieEntity target) {
        this(def, ref, ownerTeam, cellX, cellY, startHeight, target,
                target != null ? target.cellX() : -1F,
                target != null ? target.height() : 0F, false);
    }

    /**
     * Where a hand-aimed shot is to land.
     *
     * <p>A cell rather than a zombie, because the cob cannon's whole mechanic is "the player chose
     * this square": the zombies standing there when the cob arrives are the blast's problem, not
     * the shot's. {@code height} is the ground line of the target cell, so a shot into the pool or
     * onto the roof lands on the surface rather than at y=0.
     */
    public record Aim(float x, float y, float height) {
        public Aim(float x, float height) { this(x, Float.NaN, height); }
    }

    /**
     * Aimed constructor: a shot at a cell the player picked, with no zombie behind it.
     *
     * <p>It still counts as having somewhere to be - that is what {@code aimedAtPoint} records, and
     * without it the impact test below would never fire, because "has a target" is otherwise spelled
     * "names a zombie". The arc solves its launch from the same numbers a homing shot uses, so the
     * flight looks identical; only what it lands on differs.
     */
    public ProjectileEntity(ProjectileDef def, ProjectileRef ref, Team ownerTeam,
                            float cellX, float cellY, float startHeight, Aim aim) {
        this(def, ref, ownerTeam, cellX, cellY, startHeight, null, aim.x(), aim.height(), true);
        targetY = Float.isNaN(aim.y()) ? cellY : aim.y();
        ArcMotionCapability arc = capability(ArcMotionCapability.class);
        if (arc != null) arc.launch(cellX, cellY, height(), targetX, targetY, targetHeight);
    }

    private ProjectileEntity(ProjectileDef def, ProjectileRef ref, Team ownerTeam,
                             float cellX, float cellY, float startHeight, ZombieEntity target,
                             float targetX, float targetHeight, boolean aimedAtPoint) {
        super(def.id(), ownerTeam, cellX, cellY, 1);
        this.def = def;
        this.damage = ref != null ? ref.damage() : 0;
        this.direction = ref != null ? ref.direction() : 1F;
        this.targetId = target != null ? target.id() : -1;
        this.targetX = targetX;
        this.targetY = target == null ? cellY : target.cellY();
        this.targetHeight = targetHeight;
        this.aimedAtPoint = aimedAtPoint;
        this.originX = cellX;
        this.originY = cellY;
        this.vectorX = ref != null ? ref.vectorX() : 1F;
        this.vectorY = ref != null ? ref.vectorY() : 0F;
        this.maxRange = ref != null ? ref.range() : ProjectileRef.UNLIMITED_RANGE;
        setHeight(startHeight + (ref != null ? ref.launchHeight() : 0F));
        for (TypedCapability<ProjectileCapability> entry : def.resolvedCapabilities()) {
            capabilities.add(new Instance(entry.type(), entry.value().instantiate()));
        }
        if (target != null || aimedAtPoint) {
            ArcMotionCapability arc = capability(ArcMotionCapability.class);
            if (arc != null) {
                arc.launch(cellX, cellY, height(), targetX, targetY, targetHeight);
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
        return torch((float) multiplier, burningType);
    }

    public boolean torch(float multiplier, com.pvzce.api.util.Identifier burningType) {
        if (torched) {
            return false;
        }
        torched = true;
        this.damage = Math.max(1, Math.round(damage * Math.max(1F, multiplier)));
        if (burningType != null) {
            this.torchDamageType = burningType;
        }
        // The art follows the fact, and this is the only place the fact exists. A shot that is
        // burning is drawn as the original's fire pea - its own reanim, flames and all - so the
        // state goes out with the next entity sync. Nothing else about the shot changes: the
        // definition stays the pea's (same motion, same impact sound), which is why the swap is a
        // state and not a second projectile.
        setAnimation(EntityAnimations.LIT);
        return true;
    }

    /** {@code +1} down the lawn, {@code -1} back toward the house. */
    public float direction() {
        return direction;
    }

    public float vectorX() { return vectorX; }

    public float vectorY() { return vectorY; }

    /** The zombie this shot is homing on, or {@code -1}. */
    public int targetId() {
        return targetId;
    }

    public float targetX() {
        return targetX;
    }

    /** True once a homing shot has come back down to lawn level. */
    public boolean hasLanded() {
        return height() <= targetHeight + LANDED_HEIGHT;
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
        WorldPosition before = position();
        float beforeX = cellX();
        int beforeRow = gridY();
        for (Instance instance : capabilities) {
            if (instance.capability.move(this, level)) {
                break;
            }
        }
        if (capability(com.pvzce.common.capability.projectile.LinearMotionCapability.class) != null
                && Math.abs(vectorY()) < 0.0001F) {
            com.pvzce.common.level.mechanic.PortalMechanic.Exit exit =
                    com.pvzce.common.level.mechanic.PortalMechanic.cross(
                            level, id(), beforeX, cellX(), beforeRow);
            if (exit != null) {
                float dx = exit.x() - cellX();
                float dy = exit.row() + 0.5F - cellY();
                setCellX(exit.x());
                setCellY(cellY() + dy);
                originX += dx;
                originY += dy;
            }
        }
        if (cellX() > level.width() + 1F || cellX() < -1F
                || cellY() < -1F || cellY() > level.height() + 1F) {
            remove();
            return;
        }
        // A short-ranged shot dies where its plant's reach ends. Measured from the muzzle,
        // which is both where this projectile was born and what the shooter's target search
        // measures from - one origin, so "worth firing at" and "can actually reach" agree.
        if (maxRange > 0F && Math.hypot(cellX() - originX, cellY() - originY) >= maxRange) {
            remove();
            return;
        }
        var obstruction = level.sceneBoard().firstObstruction(before, position());
        if (obstruction.isPresent()) {
            var impact = obstruction.get();
            setCellX(impact.x());
            setCellY(impact.y());
            setHeight(impact.elevation());
            // Impact at the obstruction, including an underside of a bridge deck.
            applyImpact(null, level);
            return;
        }
        ZombieEntity hit = findTarget(level);
        if (hit == null) {
            PlantEntity plant = findPlantTarget(level);
            if (plant != null) {
                // A shot fired by the zombies. It is the only shot in the game that travels the
                // other way, and everything about it is the same except which registry it looks
                // in - see `findPlantTarget`.
                plant.damageFrom(damage);
                remove();
                return;
            }
            if ((targetId >= 0 || aimedAtPoint)
                    && Math.hypot(cellX() - targetX, cellY() - targetY) < HIT_RADIUS_X && hasLanded()) {
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
            for (PlantEntity plant : level.plantsAt(column, gridY()).stream()
                    .sorted(java.util.Comparator.comparingInt((PlantEntity p) -> PlantPlacement.layerIndex(p.def())).reversed()).toList()) {
                if (plant != null && !plant.isRemoved() && height() >= plant.height() - LANDED_HEIGHT
                        && height() <= plant.height() + com.pvzce.common.PvzceConstants.COMBAT_BODY_HEIGHT) {
                    if ("basketball".equals(def.id().path())) {
                        if (Math.abs(cellX() - targetX) > HIT_RADIUS_X
                                || height() > plant.height() + LANDED_HEIGHT) continue;
                        if (com.pvzce.common.capability.plant.UmbrellaLeafCapability.block(
                                level, column, gridY(), plant.team(), plant.surfaceId())) {
                            remove();
                            return null;
                        }
                    }
                    if (com.pvzce.common.core.PlantPlacement.is(plant.def(),
                            com.pvzce.common.tag.PvzceTags.ZOMBIE_PEA_PASSES_OVER)) {
                        continue;
                    }
                    // Left to right, so the last one found is the nearest to a shot flying left.
                    found = plant;
                    break;
                }
            }
        }
        return found;
    }

    private ZombieEntity findTarget(LevelServer level) {
        boolean groundLayer = !def.isAirLayer();
        for (ZombieEntity zombie : (vectorY != 0F ? level.enemiesOf(team()) : level.enemiesInRow(gridY(), team()))) {
            if (zombie.isRemoved()) {
                continue;
            }
            if (!groundLayer && !zombie.canBeHitByArc()) continue;
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
                        && height() >= zombie.height() - LANDED_HEIGHT
                        && height() <= zombie.height() + 0.18F ? zombie : null;
            }
            if (groundLayer && !zombie.canBeHitByGround()) {
                continue;
            }
            if ((vectorY == 0F || Math.abs(zombie.cellY() - cellY()) < 0.45F)
                    && Math.abs(zombie.cellX() - cellX()) < HIT_RADIUS_X + 0.05F
                    && height() >= zombie.height() - LANDED_HEIGHT
                    && height() <= zombie.height() + com.pvzce.common.PvzceConstants.COMBAT_BODY_HEIGHT) {
                return zombie;
            }
        }
        return null;
    }

    @Override
    public CompoundTag saveState() {
        CompoundTag tag = saveBaseState();
        tag.putString("ownerTeam", team() == null ? "" : team().id().toString());
        tag.putInt("damage", damage);
        tag.putInt("targetId", targetId);
        tag.putFloat("targetX", targetX);
        tag.putFloat("targetHeight", targetHeight);
        tag.putFloat("targetY", targetY);
        tag.putByte("aimedAtPoint", (byte) (aimedAtPoint ? 1 : 0));
        tag.putFloat("direction", direction);
        tag.putFloat("vectorX", vectorX);
        tag.putFloat("vectorY", vectorY);
        tag.putFloat("originX", originX);
        tag.putFloat("originY", originY);
        tag.putFloat("maxRange", maxRange);
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
        damage = tag.getInt("damage");
        targetId = tag.contains("targetId") ? tag.getInt("targetId") : -1;
        targetX = tag.getFloat("targetX");
        targetHeight = tag.getFloat("targetHeight");
        targetY = tag.contains("targetY") ? tag.getFloat("targetY") : cellY();
        aimedAtPoint = tag.getInt("aimedAtPoint") != 0;
        direction = tag.contains("direction") ? tag.getFloat("direction") : 1F;
        vectorX = tag.contains("vectorX") ? tag.getFloat("vectorX") : 1F;
        vectorY = tag.getFloat("vectorY");
        originX = tag.contains("originX") ? tag.getFloat("originX") : cellX();
        originY = tag.contains("originY") ? tag.getFloat("originY") : cellY();
        maxRange = tag.getFloat("maxRange");
        CompoundTag saved = tag.getCompound("capabilities");
        for (Instance instance : capabilities) {
            instance.capability.load(saved.getCompound(instance.type.toString()));
        }
    }

    private record Instance(Identifier type, ProjectileCapability capability) {
    }
}
