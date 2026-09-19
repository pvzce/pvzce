package com.pvzce.server.entity;

import com.pvzce.api.content.AnimationBindings;
import com.pvzce.api.content.DamageTypeDef;
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
import com.pvzce.common.capability.zombie.ArmorCapability;
import com.pvzce.common.core.BuiltInRegistries;
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
    /**
     * How long a dead zombie's body stays on the lawn, in ticks (6s at 60tps).
     *
     * <p>Long enough for the longest death clip to play out and for the body to lie there
     * for a moment afterwards: the clips are 3.25s for an ordinary zombie and 4.9s for a
     * gargantuar, and both end holding the fallen pose. It used to be removed on the tick
     * it died, so the death animation never played at all - the client was told to despawn
     * the entity in the same update that would have started the clip, and a zombie that was
     * shot simply vanished.
     */
    public static final int CORPSE_TICKS = 360;
    /**
     * The death sequences a zombie chooses between when it is killed.
     *
     * <p>The original picks one at random so that a lane of zombies does not fall over in
     * unison, and its three sequences are all in the shared reanim. A zombie whose art has
     * only an ordinary {@code death} asks for one of these names anyway and the client falls
     * back to the clip it has - see {@code AnimationPlayback}'s silent-fallback rule - so a
     * definition never has to declare which deaths its art carries.
     *
     * <p>The super-long sequence is left out of the random pool: it is a heavy body's death,
     * not a variation on an ordinary one, and a definition that wants it points its
     * {@code animations} map at the clip instead. The pool is a pair of *states*, not clip
     * names - a definition that maps them both to the same clip simply gets that clip twice,
     * which is the honest outcome for art that only drew one death.
     */
    private static final String[] RANDOM_DEATHS = {
            EntityAnimations.DEATH, EntityAnimations.DEATH2,
    };

    /**
     * How far in front a zombie can bite, in cells from centre to centre.
     *
     * <p>The same 0.55 the ground projectiles use to decide they have connected: a zombie's
     * reach and a pea's are the same reach because they are the same question, and a charmed
     * zombie that stopped a hair further away than a projectile would look like it was chewing
     * air.
     */
    public static final float BITE_REACH = 0.55F;

    /**
     * How long a zombie's own rise out of a grave takes, in ticks.
     *
     * <p>One second of climbing, during which it neither walks nor bites and is drawn below
     * the ground line (see {@code InGameScreen}, which paints risers before the scene so the
     * lawn covers the part that is still underground).
     */
    public static final int RISE_TICKS = 60;
    /** How far below its cell a rising zombie starts, in cells. */
    public static final float RISE_DEPTH = 0.55F;

    private final ZombieDef def;
    private final List<Instance> capabilities = new ArrayList<>();
    private final List<StatusInstance> statuses = new ArrayList<>();
    private int biteCooldown;
    private int leftCountdown;
    private int speedBoostTicks;
    /**
     * Ticks left of this zombie's climb out of a grave, or zero for one that is on its feet.
     *
     * <p>A state, not a spawn delay: the zombie exists from the moment the grave opens, so a
     * save taken mid-rise comes back mid-rise, and the client can draw the part that is
     * still underground.
     */
    private int riseTicks;
    /** Ticks left of this zombie's own death animation; 0 while it is alive. */
    private int corpseTicks;
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

    /**
     * How many ticks this zombie still has to climb out of the ground, or zero.
     *
     * <p>The client reads it to draw the zombie sinking into (or rising out of) the lawn:
     * negative height alone would paint the part below the ground line *over* the tile
     * underneath, so a riser is drawn before the scene instead. See {@link #RISE_DEPTH} for
     * how far down it starts.
     */
    public int riseTicks() {
        return riseTicks;
    }

    /** How far through its climb this zombie is, 0 (buried) to 1 (standing). */
    public float riseProgress() {
        return riseTicks <= 0 ? 1F : 1F - riseTicks / (float) RISE_TICKS;
    }

    /** Sends this zombie up out of the ground: it spends {@link #RISE_TICKS} climbing. */
    public void beginRise() {
        riseTicks = RISE_TICKS;
        setAnimation(EntityAnimations.IDLE);
    }

    /**
     * True while this zombie is still a zombie: it walks, bites, is aimed at and counted.
     *
     * <p>A body playing its death animation is none of those things, but it is still in the
     * world and still drawn. Every scan that asks "which zombies are here" wants this rather
     * than {@link #isRemoved()}, which is only ever true once the body is gone.
     */
    public boolean isAlive() {
        return !removed && corpseTicks <= 0;
    }

    /** True for a body that has died and is playing out its death animation. */
    public boolean isDying() {
        return !removed && corpseTicks > 0;
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
        if (corpseTicks > 0) {
            // A dead body only ages. It does not walk, bite, drown, or run its
            // capabilities: `damage*` refuses it and every scan that looks for zombies
            // asks `isAlive()`, so nothing in the world can interact with it.
            if (--corpseTicks <= 0) {
                remove();
            }
            return;
        }
        if (riseTicks > 0) {
            // Still climbing out of its grave. It does not walk, bite, drown or run its
            // capabilities - it is not on the lawn yet - and the client draws it below the
            // ground line for as long as this lasts (see `InGameScreen#renderRisingZombies`).
            // Being *shot* while it climbs is allowed, exactly as in the original.
            riseTicks--;
            setAnimation(EntityAnimations.IDLE);
            // Below the ground line while it climbs. Height is the entity's own "how high off
            // its cell" and already travels every sync, so the climb needs no new field - and
            // the client draws everything with a negative height *before* the scene, which is
            // what keeps the buried half behind the lawn instead of on top of the row below.
            setHeight(-RISE_DEPTH * (1F - riseProgress()));
            tickStatuses();
            if (riseTicks <= 0) {
                setHeight(0F);
            }
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
            // A body going under has its own sequence in the original - it does not fall
            // over, it sinks - so the state says *how* it died rather than reusing the
            // ordinary death. Art without the clip falls back to the death it does have.
            setAnimation(EntityAnimations.DEATH_WATER);
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
        // Being hit does not change the pose. It used to hold the `hit` clip for eight ticks,
        // and that clip is the zombie's *standing* pose - so a zombie walking into a stream
        // of peas froze for an eighth of a second on every hit, restarted its walk cycle when
        // it came back, and jumped its pole (or its arms) to a pose that belonged to the
        // other clip. The original has no hurt animation at all: the splat particle and the
        // impact sound are the feedback, and the legs keep walking. Armour hits are the same
        // story - `ArmorCapability` no longer reaches for a clip either.
        if (isCharmed()) {
            // A charmed zombie fights for the other side: it walks the lane looking for the
            // zombies it used to belong to, and it does not touch the plants - not even the one
            // it is standing on, which is what makes "the hypno-shroom's own cell is safe after
            // the charm" true without a special case.
            biteOrWalk(level, enemyZombieInFront(level));
            return;
        }
        PlantEntity plant = level.plantAt(gridX(), gridY());
        if (plant != null) {
            bitePlant(level, plant);
            return;
        }
        setAnimation(walkState());
        setCellX(cellX() - moveSpeed(level) / PvzceConstants.TICKS_PER_SECOND);
        if (biteCooldown > 0) {
            biteCooldown--;
        }
        checkReachedLeft(level);
    }

    /**
     * Whether this zombie is fighting for the side it was spawned against.
     *
     * <p>Read from the team rather than kept in a flag: the team <em>is</em> the fact, and a
     * second copy of it is how "the client draws it charmed" and "the simulation treats it as
     * charmed" would drift apart. See {@code CharmCapability} for what sets it.
     */
    @Override
    public boolean charmed() {
        return isCharmed();
    }

    public boolean isCharmed() {
        return team() != null && !PvzceIds.ZOMBIE_TEAM.equals(team().id());
    }

    /**
     * The enemy zombie this one is close enough to bite, or {@code null}.
     *
     * <p>Forward only, and by the same reach the shooters use, because that is the space a
     * zombie's mouth occupies: it walks toward the house, so what it can bite is the thing in
     * front of it. A charmed zombie walking right would be a different animation and a
     * different problem, and nothing in the game produces one.
     */
    private ZombieEntity enemyZombieInFront(LevelServer level) {
        for (ZombieEntity other : level.enemiesInRow(gridY(), team())) {
            if (other.id() == id()) {
                continue;
            }
            // The same layer rule the shooters use: a balloon zombie overhead and a miner
            // underground are not in front of anybody's teeth.
            if (!other.canBeHitByGround()) {
                continue;
            }
            float delta = other.cellX() - cellX();
            if (delta > -ZombieEntity.BITE_REACH && delta < ZombieEntity.BITE_REACH) {
                return other;
            }
        }
        return null;
    }

    /**
     * Bites whatever is in front, or walks on.
     *
     * <p>The same shared cooldown and the same sounds as biting a plant: one bite timer per
     * zombie, whichever side it is on. {@code target == null} is simply "nothing there", which
     * is why this is one method rather than a branch at each call site.
     */
    private void biteOrWalk(LevelServer level, ZombieEntity target) {
        if (target != null) {
            setAnimation(EntityAnimations.EAT);
            if (biteCooldown > 0) {
                biteCooldown--;
                return;
            }
            int damage = Math.round(def.biteDamage()
                    * level.rules().getFloat(PvzceIds.RULE_ZOMBIE_DAMAGE_MULTIPLIER));
            target.damage(damage, ZombieEntity.damageType(PvzceIds.DAMAGE_IMPACT), level);
            biteCooldown = def.biteIntervalTicks();
            level.emitEffect(PvzceParticles.CHOMP.toString(), target.cellX(), target.cellY(),
                    def.sounds().bite().orElse(PvzceSounds.EFFECT_BITE));
            return;
        }
        setAnimation(walkState());
        setCellX(cellX() - moveSpeed(level) / PvzceConstants.TICKS_PER_SECOND);
        if (biteCooldown > 0) {
            biteCooldown--;
        }
        checkReachedLeft(level);
    }

    /**
     * One bite of a plant, on the shared bite timer.
     *
     * <p>The plant gets the first word ({@code onBittenBy}), because one of them is not eaten by
     * being chewed: the hypno-shroom turns the biter instead, and the bite that turned it does
     * not also cost it health. A capability that answers true has taken the bite, and the chomp
     * sound still plays - it was a bite, whatever came of it.
     */
    private void bitePlant(LevelServer level, PlantEntity plant) {
        setAnimation(EntityAnimations.EAT);
        if (biteCooldown > 0) {
            biteCooldown--;
            return;
        }
        biteCooldown = def.biteIntervalTicks();
        if (plant.onBittenBy(this, level)) {
            // The bite was consumed: a plant that answers true has done something *instead* of
            // being eaten, and it is gone either way. The hypno-shroom is the case - the
            // original's mushroom does not survive charming the zombie that ate it, and that is
            // the price that makes "which zombie do I feed it to" a decision.
            // The removal itself is flushed by the level at the end of its tick, which is where
            // every other removal is applied - its entity list is being iterated while this
            // runs, so taking the plant out of it here would be a concurrent modification.
            plant.remove();
        } else {
            int damage = Math.round(def.biteDamage()
                    * level.rules().getFloat(PvzceIds.RULE_ZOMBIE_DAMAGE_MULTIPLIER));
            plant.damageFrom(damage);
        }
        level.emitEffect(PvzceParticles.CHOMP.toString(), plant.cellX(), plant.cellY(),
                def.sounds().bite().orElse(PvzceSounds.EFFECT_BITE));
    }

    /**
     * The state this zombie's walk loop publishes.
     *
     * <p>Plain {@code walk} unless a capability says otherwise, because "how does this one
     * walk" is a property of what it is carrying (see {@link
     * com.pvzce.api.content.capability.ZombieCapability#walkState}). Asked here rather than
     * set by the capability itself: the walk loop runs after the capabilities, so a state
     * they published would be replaced on the same tick.
     */
    private String walkState() {
        for (Instance instance : capabilities) {
            String state = instance.capability.walkState(this);
            if (state != null && !state.isBlank()) {
                return state;
            }
        }
        return EntityAnimations.WALK;
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

    /**
     * Reaching the house, for the zombies that are attacking it.
     *
     * <p>A charmed zombie walking off the left edge has reached its own side's house: it leaves
     * the board and nothing else happens. Reporting it would end the level as a loss on the
     * strength of a zombie the player has already turned - and "it walked into the house" is
     * exactly what the original does with one, except that in the original it stops and stays
     * there, which this level's win check already handles (a charmed zombie is not hostile).
     */
    public void checkReachedLeft(LevelAccess level) {
        if (cellX() <= -0.4F) {
            leftCountdown++;
            if (leftCountdown >= 60 && !isCharmed()) {
                level.zombieReachedLeft(this);
            }
        } else {
            leftCountdown = 0;
        }
    }

    /**
     * Projectile damage pipeline: capabilities get first refusal (armor), then a
     * flier is grounded, then the body takes the hit.
     *
     * <p>{@code projectile.damageType()} is what separates the fume-shroom from a pea. A shot
     * normally asks the armour capabilities first ({@code onProjectileHit}, whose answer is
     * which slot the layer meets), but a type that ignores armour skips them entirely - a spray
     * that goes through a screen door does not get stopped by it and does not stop to break it
     * either, exactly as it does not against a bucket. Which slot a <em>shot</em> meets is still
     * the layer's answer; whether armour may absorb it at all is the type's, and it is the same
     * answer the non-projectile entry point gives.
     */
    public void damage(ProjectileDef projectile, int amount, LevelAccess level) {
        if (!isAlive()) {
            return;
        }
        int dmg = scaled(amount, level);
        DamageTypeDef type = projectile.damageType().map(ZombieEntity::damageType).orElse(null);
        if (!ignoresArmor(type)) {
            // A shot is what a projectile layer means, so this is the one caller that
            // passes the hit down to the armour capabilities itself: which slot it meets
            // (a shield first, a hat instead of no shield at all) is a property of the
            // shot, not of the damage type.
            for (Instance instance : capabilities) {
                if (instance.capability.onProjectileHit(this, projectile, dmg, level)) {
                    return;
                }
            }
        }
        ground(level);
        damageBody(dmg, level, burns(type));
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
     * <p>Goes through the registered {@code pvzce:impact} damage type rather than
     * straight to {@link #damageBody}: armour absorbs it (a Conehead has to be hit twice
     * by a Wall-nut, a Buckethead three times, exactly as in the original), which is why
     * {@code damageBody} is not the entry point - that one is for damage types authored
     * with {@code ignores_armor}.
     */
    public void damageImpact(int amount, LevelAccess level) {
        damage(amount, impactType(), level);
    }

    /**
     * The one damage entry point: every hit, from every source, arrives here.
     *
     * <p>{@code type} decides whether the zombie's own armour capabilities get first
     * refusal. A type that ignores armour skips them entirely, so a cherry bomb kills a
     * Buckethead and a lawn mower flattens one; a type that does not runs the impact
     * hooks, whose {@code onImpact} answers front-before-top (a shield, then a hat).
     *
     * <p>The plant damage multiplier is applied here, once, for the same reason the
     * routing is: three call sites each multiplying would make {@code /rule
     * plant_damage_multiplier} mean a different thing depending on which one ran. A
     * {@code null} type is read as {@code pvzce:projectile}, so an unknown or
     * not-yet-loaded registry cannot turn a hit into a free pass.
     */
    public void damage(int amount, DamageTypeDef type, LevelAccess level) {
        if (!isAlive()) {
            return;
        }
        int dmg = scaled(amount, level);
        if (!ignoresArmor(type)) {
            for (Instance instance : capabilities) {
                if (instance.capability.onImpact(this, dmg, level)) {
                    return;
                }
            }
        }
        // The type travels into the body hit because the *death* has to know what killed it: a
        // blast from the ash line leaves a charred body and a pea does not, and the difference
        // is the hit's own declaration rather than a list of ids here.
        damageBody(dmg, level, burns(type));
    }

    /** The registered damage type for {@code id}, or the projectile fallback. */
    public static DamageTypeDef damageType(Identifier id) {
        DamageTypeDef type = id == null ? null : BuiltInRegistries.DAMAGE_TYPES.get(id);
        return type == null ? projectileType() : type;
    }

    private static DamageTypeDef projectileType() {
        return damageType(PvzceIds.DAMAGE_PROJECTILE);
    }

    private static DamageTypeDef impactType() {
        return damageType(PvzceIds.DAMAGE_IMPACT);
    }

    private static boolean ignoresArmor(DamageTypeDef type) {
        return type != null && type.ignoresArmor();
    }

    /**
     * Whether a hit of this type leaves a charred body, for the death state.
     *
     * <p>A {@code null} type answers false, like {@code ignoresArmor}: an unknown or not-yet
     * loaded registry must not turn an ordinary pea into a fireball.
     */
    private static boolean burns(DamageTypeDef type) {
        return type != null && type.burns();
    }

    private static int scaled(int amount, LevelAccess level) {
        return Math.max(1, Math.round(amount
                * level.rules().getFloat(PvzceIds.RULE_PLANT_DAMAGE_MULTIPLIER)));
    }

    /** Grounds a flier that something just hit, with the balloon popping. */
    private void ground(LevelAccess level) {
        if (grounded) {
            return;
        }
        grounded = true;
        setAnimation(EntityAnimations.FALL);
        level.emitEffect("", cellX(), cellY(),
                def.sounds().special().orElse(PvzceSounds.ZOMBIE_BALLOON_POP));
    }

    /**
     * Explosions / area damage bypass armor.
     *
     * <p>Named for what it is rather than called {@code damage}: the entry point is
     * {@link #damage(int, DamageTypeDef, LevelAccess)}, and a second overload would only
     * make "which one did this call site mean" a question again.
     */
    public void damageBody(int amount, LevelAccess level) {
        // Nothing that arrives here without a type burns: the callers are the ones that already
        // know what their hit is (a bowling nut, a mower at full force).
        damageBody(amount, level, false);
    }

    /**
     * Which of the original's death sequences this body falls over with.
     *
     * <p>Random rather than fixed, because that is what makes a crowd read as a crowd: the
     * original shuffles its sequences so two zombies killed by the same melon do not collapse
     * in lockstep.
     *
     * <p>Two things a definition can say with the {@code animations} map it already has, and
     * neither needs a new field:
     *
     * <ul>
     *   <li>art with only one death must not be asked for the other, because a state a file
     *       does not define falls back to {@code idle} on the client - a corpse standing about
     *       instead of falling over. So the second variant is used only when the definition
     *       maps <em>both</em> states onto the same file, which is exactly the statement "this
     *       file was drawn with more than one death in it";</li>
     *   <li>a body that should take its time going down pins its {@code death} state at the
     *       long sequence ({@code "death": "pvzce:zombie/giant/gargantuar"} with that clip in
     *       the file), which also silences the random pick - the second variant is no longer
     *       mapped to the same file, so it is never chosen.</li>
     * </ul>
     *
     * @param random the level's stream, so a replay of the same level picks the same deaths
     */
    private String pickDeathAnimation(java.util.Random random) {
        String picked = RANDOM_DEATHS[random.nextInt(RANDOM_DEATHS.length)];
        return EntityAnimations.DEATH2.equals(picked) && !hasDeathVariants()
                ? EntityAnimations.DEATH
                : picked;
    }

    /** True when this zombie's art was drawn with more than one death sequence in it. */
    private boolean hasDeathVariants() {
        AnimationBindings bindings = def.animations();
        return bindings.resolve(EntityAnimations.DEATH)
                .equals(bindings.resolve(EntityAnimations.DEATH2));
    }

    /**
     * The body hit itself, with what kind of damage it was.
     *
     * @param burns true when this hit is fire or ash, so a kill leaves a charred body; see
     *              {@link EntityAnimations#DEATH_BURNED}
     */
    public void damageBody(int amount, LevelAccess level, boolean burns) {
        if (!isAlive()) {
            return;
        }
        int before = health();
        setHealth(Math.max(0, health() - amount));
        // Half health costs an ordinary zombie its outer arm, as it does in the original -
        // but only once nothing is left on its head: a Conehead loses the arm at half of
        // the health it has *under* the cone, and only after the cone is gone. Losing an
        // arm while still wearing a pristine cone read as the armour being ignored.
        // The loss is a *transition*, so it is read off the two health values rather than
        // kept in a flag: a restored save at 40% health is already armless and must not
        // pop a second arm.
        if (def.dropsArm() && armorHealth() <= 0
                && before * 2 > def.health() && health() * 2 <= def.health()) {
            level.emitEffect(PvzceParticles.ZOMBIE_ARM.toString(), cellX(), cellY(),
                    def.sounds().death().orElse(PvzceSounds.ZOMBIE_LIMBS_POP));
        }
        if (health() <= 0) {
            // The body stays for the death clip (see CORPSE_TICKS) instead of leaving the
            // world on this tick: the animation is the only thing that says what just
            // happened, and a despawn in the same breath never showed any of it.
            corpseTicks = CORPSE_TICKS;
            // The burnt clip is a different model (the original's charred zombie), reached
            // through the definition's own `animations` map. A zombie whose art has no such clip
            // asks for it anyway and the animation manager falls back - which is the right
            // failure, because the alternative is a fire death that silently looks like any
            // other and nothing in the log to say why.
            setAnimation(burns ? EntityAnimations.DEATH_BURNED : pickDeathAnimation(level.random()));
            // The head leaves the body as its own particle. The model keeps it hidden in the
            // death clip - that is how the original is authored - so this is the only thing
            // that puts one on the lawn, and the sprite's own motion is what makes it drop
            // where the zombie fell rather than fly off the board (see
            // data/pvzce/particles/zombie/zombie_head.json).
            level.emitEffect(def.dropsHead() ? PvzceParticles.ZOMBIE_HEAD.toString() : "",
                    cellX(), cellY(),
                    def.sounds().death().orElse(PvzceSounds.ZOMBIE_LIMBS_POP));
            dropEquipment(level);
            for (Instance instance : capabilities) {
                instance.capability.onDeath(this, level);
            }
            // Last, so a capability cannot resurrect a zombie that already paid out.
            level.zombieDied(this);
        }
    }

    /**
     * Throws off whatever the zombie is still carrying when it dies.
     *
     * <p>The cone of a Conehead that was shot to pieces earlier is already gone and must
     * not appear a second time; one that died with its cone on drops it. That distinction
     * is why the armour-driven entries ask the capability rather than the definition.
     */
    private void dropEquipment(LevelAccess level) {
        ArmorCapability armor = capability(ArmorCapability.class);
        for (com.pvzce.api.content.EquipmentDef entry : def.equipment()) {
            if (entry.dropParticle().isEmpty()) {
                continue;
            }
            if (entry.armorDriven() && (armor == null || !armor.wearing(entry.piece().get()))) {
                continue;
            }
            level.emitEffect(entry.dropParticle().get().toString(), cellX(), cellY(), null);
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

    /**
     * The number the client draws worn equipment from: remaining armour, or
     * {@link com.pvzce.common.network.packet.EntitySpawnS2C#NO_ARMOR} when this zombie
     * never wore any.
     */
    @Override
    public int armor() {
        com.pvzce.common.capability.zombie.ArmorCapability armor =
                capability(com.pvzce.common.capability.zombie.ArmorCapability.class);
        return armor == null
                ? com.pvzce.common.network.packet.EntitySpawnS2C.NO_ARMOR
                : armor.totalHealth();
    }

    /** True when the zombie currently carries the given status (tests / HUD). */
    public boolean hasStatus(ZombieStatus status) {
        return statuses.stream().anyMatch(instance -> instance.status == status);
    }

    /**
     * A slowed zombie is drawn frozen.
     *
     * <p>"Chilled" is exactly the {@code slow} status and nothing else: the client has no
     * clock for it and no way to tell a slowed zombie from a normal one by looking at the
     * position it was sent, so the state goes out with the rest of the entity's visible
     * state (see {@code EntityUpdateS2C#chilled}).
     */
    @Override
    public boolean chilled() {
        return hasStatus(ZombieStatus.SLOW);
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
        tag.putInt("riseTicks", riseTicks);
        tag.putInt("leftCountdown", leftCountdown);
        tag.putInt("speedBoostTicks", speedBoostTicks);
        tag.putInt("grounded", grounded ? 1 : 0);
        // A body saved mid-animation comes back as a body, not as a living zombie.
        tag.putInt("corpseTicks", corpseTicks);

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
        riseTicks = Math.max(0, tag.getInt("riseTicks"));
        leftCountdown = tag.getInt("leftCountdown");
        speedBoostTicks = tag.getInt("speedBoostTicks");
        grounded = !tag.contains("grounded") || tag.getInt("grounded") != 0;
        corpseTicks = Math.max(0, Math.min(CORPSE_TICKS, tag.getInt("corpseTicks")));

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
