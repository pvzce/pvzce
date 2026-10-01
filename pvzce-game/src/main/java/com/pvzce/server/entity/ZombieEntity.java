package com.pvzce.server.entity;

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
import com.pvzce.common.capability.zombie.FlyCapability;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.PlantPlacement;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.tag.PvzceTags;
import com.pvzce.server.Team;
import com.pvzce.server.level.LevelServer;
import com.pvzce.common.PvzceParticles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
     * How far in front a zombie can bite, in cells from centre to centre.
     *
     * <p>The same 0.55 the ground projectiles use to decide they have connected: a zombie's
     * reach and a pea's are the same reach because they are the same question, and a charmed
     * zombie that stopped a hair further away than a projectile would look like it was chewing
     * air.
     */
    public static final float BITE_REACH = 0.55F;

    /**
     * The climb a level gets when its {@code pvzce:zombie_rise_ticks} rule is unwritten.
     *
     * <p>One second of climbing, during which the zombie neither walks nor bites and is drawn
     * below the lawn surface: the client uses the height this publishes - in
     * {@link PvzceConstants#ZOMBIE_RISE_DEPTH_CELLS} units - to cut the buried part off at
     * the row's ground line, so the body slides out rather than appearing on its feet.
     */
    public static final int RISE_TICKS = PvzceConstants.ZOMBIE_RISE_TICKS;

    private final ZombieDef def;
    private final List<Instance> capabilities = new ArrayList<>();
    private final List<StatusInstance> statuses = new ArrayList<>();
    /**
     * Running tallies of hits that stack, keyed by whatever the hitter calls them; see
     * {@link #addBuildup}. Separate from {@link #statuses} because these have no clock.
     */
    private final Map<Identifier, Integer> buildups = new HashMap<>();
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
    /** How long this zombie's climb lasts, in ticks: the level's rule, or {@link #RISE_TICKS}. */
    private int riseTicksTotal = RISE_TICKS;
    /** Ticks left of this zombie's own death animation; 0 while it is alive. */
    private int corpseTicks;
    /** Balloon zombies fly until something pops the balloon. */
    private boolean grounded = true;

    public ZombieEntity(ZombieDef def, Team team, float cellX, int gridY) {
        this(def, team, cellX, gridY, 1F);
    }

    /**
     * A zombie whose body is heavier than its definition, which is what an endless round asks for.
     *
     * <p>{@code healthScale} multiplies the definition's own health once, at spawn, and the
     * result is this zombie's full health for the rest of its life: the half-health arm-loss
     * transition still reads {@code def.health()} as its denominator (every zombie of a wave
     * scales together, so the fraction is unchanged), and the client draws the bar from the health
     * it was sent, so a scaled zombie's bar still reads full.
     *
     * <p>Deliberately not a level rule: a rule would be one number for the whole lawn, and a
     * mutation that also scales zombie speed or damage would have to share it.
     */
    public ZombieEntity(ZombieDef def, Team team, float cellX, int gridY, float healthScale) {
        super(def.id(), team, cellX, gridY + 0.5F,
                Math.max(1, Math.round(def.health() * (healthScale > 0F ? healthScale : 1F))));
        this.def = def;
        for (TypedCapability<ZombieCapability> entry : def.resolvedCapabilities()) {
            capabilities.add(new Instance(entry.type(), entry.value().instantiate()));
        }
        this.grounded = !def.spawnsAirborne();
    }

    public ZombieDef def() {
        return def;
    }

    /**
     * How many ticks this zombie still has to climb out of the ground, or zero.
     *
     * <p>The client reads the height this drives to draw the zombie sinking into (or rising
     * out of) the lawn: negative height alone would paint the part below the ground line
     * *over* the tile underneath, so a riser is cut off at the ground line instead. See
     * {@link PvzceConstants#ZOMBIE_RISE_DEPTH_CELLS} for how far down it starts.
     */
    public int riseTicks() {
        return riseTicks;
    }

    /** How far through its climb this zombie is, 0 (buried) to 1 (standing). */
    public float riseProgress() {
        return riseTicks <= 0 ? 1F : 1F - riseTicks / (float) riseTicksTotal;
    }

    /**
     * Sends this zombie up out of the ground: it spends {@link #RISE_TICKS} climbing.
     *
     * <p>The height is set here, not on the first tick, so that the spawn packet already
     * describes a body that is underground. A client that is told "here is a zombie, at
     * height zero" and only then "and it is buried" draws it standing for the three ticks the
     * mirror spends sliding from one to the other - the pop this whole state exists to avoid.
     */
    public void beginRise() {
        beginRise(RISE_TICKS);
    }

    /** The same climb at a speed the level chose; see {@code pvzce:zombie_rise_ticks}. */
    public void beginRise(int ticks) {
        riseTicksTotal = Math.max(1, ticks);
        riseTicks = riseTicksTotal;
        setHeight(-PvzceConstants.ZOMBIE_RISE_DEPTH_CELLS);
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
            // capabilities - it is not on the lawn yet - and the client cuts it off at the
            // row's ground line for as long as this lasts (see
            // `InGameScreen#renderRisingZombies`). Being *shot* while it climbs is allowed,
            // exactly as in the original.
            riseTicks--;
            setAnimation(EntityAnimations.IDLE);
            // Below the ground line while it climbs. Height is the entity's own "how high off
            // its cell" and already travels every sync, so the climb needs no new field - and
            // the client reads it back as "how deep this body still is", which is how the
            // buried part ends up behind the lawn instead of on top of the row below.
            setHeight(-PvzceConstants.ZOMBIE_RISE_DEPTH_CELLS * (1F - riseProgress()));
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

    /**
     * Takes this body off the board without killing it.
     *
     * <p>For the blover's gust, and for the balloon zombie whose balloon is shot out - which are
     * the same event as far as everything downstream is concerned: the zombie is gone, nothing
     * about it counts as destroyed, and no corpse is left. The direction is only used by the
     * effect, so a gust reads as leaving the way it was facing.
     *
     * <p>Deliberately not {@code damage(100000)}: a blover is not a kill, and a level that counts
     * kills (or pays for them) must not be paid for a zombie that merely left. It is also not
     * {@code remove()}: the animation is different - a body that is blown away does not fall over.
     */
    public void blowAway(float direction) {
        remove();
        setAnimation(EntityAnimations.FLY);
        this.blowAwayDirection = direction;
    }

    /**
     * Tells every capability that a container let this zombie out rather than a wave.
     *
     * <p>One loop, and the entity stays ignorant of which capabilities care: a fuse measured in
     * cells walked has nothing to count for a zombie that was never dropped into a lane
     * ({@code JackInTheBoxCapability.onReleased}). Called by the pot that released it, once.
     */
    public void onReleased(LevelAccess level) {
        for (Instance instance : capabilities) {
            instance.capability.onReleased(this, level);
        }
    }

    /** Which way a blown-away body left, for the client's own effect. */
    public float blowAwayDirection() {
        return blowAwayDirection;
    }

    private float blowAwayDirection;

    /**
     * Kills this body with its own blast: a death, but not a kill (the jack-in-the-box).
     *
     * <p>It dies the ordinary death - the corpse, the clip, the wave director hearing about it -
     * because everything downstream of "a zombie stopped being alive" has to happen or a wave
     * gated on this zombie's death waits for ever. What it does not do is <em>pay</em>: the
     * player did not kill it, so the sun and the coin roll a kill is worth would be a reward for
     * standing next to a bomb (see {@code LevelServer.zombieDied}).
     *
     * @see #selfDestructed()
     */
    public void selfDestruct(LevelAccess level) {
        if (!isAlive()) {
            return;
        }
        selfDestructed = true;
        damageBody(health(), level, false);
    }

    /**
     * True when this body died of its own explosion rather than of anything the player did.
     *
     * <p>Read by the level's payout path and by nothing else; the flag is not saved, because it
     * is only ever read on the tick the body dies.
     */
    public boolean selfDestructed() {
        return selfDestructed;
    }

    private boolean selfDestructed;

    /**
     * Ends this body because the level did, not because anything hit it.
     *
     * <p>This is the ending a rhythm level's song has: the chart runs out, the lawn is swept, and
     * every body still standing dies the ordinary death - corpse, clip, head, the wave director
     * hearing about it. The one thing it does not do is <em>pay</em>, for the same reason
     * {@link #selfDestruct} does not: the player did not kill these. It is not the same event
     * though, and the difference is visible - a jack-in-the-box is gone when it goes off and
     * leaves no head behind, while a swept zombie was standing there normally and does.
     */
    public void sweptAway(LevelAccess level) {
        if (!isAlive()) {
            return;
        }
        swept = true;
        damageBody(health(), level, false);
    }

    private boolean swept;

    /**
     * True when nothing the player did killed this body, so a kill's payout is not owed.
     *
     * <p>Two ways to earn that: blowing yourself up, and being swept away when the level ended.
     * Read by the level's payout path and by nothing else.
     */
    public boolean unearned() {
        return selfDestructed || swept;
    }

    /** True when this zombie is still wearing anything. */
    public boolean hasArmor() {
        com.pvzce.common.capability.zombie.ArmorCapability armor =
                capability(com.pvzce.common.capability.zombie.ArmorCapability.class);
        return armor != null && armor.hasArmor();
    }

    /**
     * Takes one piece of armour off, for the magnet-shroom.
     *
     * <p>The zombie-side half of the magnet's pull, and it lives here because the entity is what
     * owns its capabilities: a plant reaching into another entity's capability list would be the
     * one place in the game that does, and {@code ArmorCapability} has no business knowing that
     * magnets exist.
     *
     * @param level where the "the piece came off" effect is played; losing a piece throws the
     *              same debris a hit that finished it would
     * @return true when something came off
     */
    public boolean stripArmor(LevelAccess level) {
        com.pvzce.common.capability.zombie.ArmorCapability armor =
                capability(com.pvzce.common.capability.zombie.ArmorCapability.class);
        return armor != null && armor.strip(this, level);
    }

    /** Land zombies without {@code can_swim} drown when their cell becomes water. */
    private boolean drownInWater(LevelServer level) {
        if (!grounded || def.canSwim()) {
            return false;
        }
        FlyCapability flight = capability(FlyCapability.class);
        if (flight != null && flight.falling()) return false;
        var scene = level.sceneAt(gridX(), gridY());
        // The tag, not the surface class string: a pack that adds its own water tile
        // (swamp, pool) tags it #c:water and drowning follows without a code change.
        if (scene != null && PlantPlacement.terrainTagged(
                PlantPlacement.Terrain.of(scene), PvzceTags.SCENE_WATER)) {
            remove();
            // A body going under has its own sequence in the original - it does not fall
            // over, it sinks - so the state says *how* it died rather than reusing the
            // ordinary death. Art without the clip plays the ordinary death instead: that
            // is the client's fallback for the death family, not this side's business.
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
            // A charmed zombie fights for the other side: it *turns around* and walks back up
            // the lane looking for the zombies it used to belong to, and it does not touch the
            // plants - not even the one it is standing on, which is what makes "the hypno-shroom's
            // own cell is safe after the charm" true without a special case.
            biteOrWalk(level, enemyZombieInFront(level));
            return;
        }
        // `biteTargetAt` rather than `plantAt`: a spikeweed is a plant you walk over, and
        // stopping to eat one is the opposite of what it is for.
        PlantEntity plant = level instanceof LevelServer server
                ? server.biteTargetAt(gridX(), gridY())
                : level.plantAt(gridX(), gridY());
        if (plant != null) {
            bitePlant(level, plant);
            return;
        }
        setAnimation(walkState());
        setCellX(cellX() + walkDirection() * moveSpeed(level) / PvzceConstants.TICKS_PER_SECOND);
        if (biteCooldown > 0) {
            biteCooldown--;
        }
        checkReachedEdge(level);
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
     * zombie's mouth occupies: what it can bite is the thing it is walking into. "Forward" is
     * the way it walks - toward the house for a zombie of the horde, back up the lane for one
     * the hypno-shroom has turned - so the search looks on the side it is travelling towards.
     */
    private ZombieEntity enemyZombieInFront(LevelServer level) {
        float facing = walkDirection();
        for (ZombieEntity other : level.enemiesInRow(gridY(), team())) {
            if (other.id() == id()) {
                continue;
            }
            // The same layer rule the shooters use: a balloon zombie overhead and a miner
            // underground are not in front of anybody's teeth.
            if (!other.canBeHitByGround()) {
                continue;
            }
            float delta = (other.cellX() - cellX()) * facing;
            if (delta > -ZombieEntity.BITE_REACH && delta < ZombieEntity.BITE_REACH) {
                return other;
            }
        }
        return null;
    }

    private String eatState() {
        for (Instance entry : capabilities) {
            String state = entry.capability.eatState(this);
            if (state != null) return state;
        }
        return EntityAnimations.EAT;
    }

    public Identifier magneticItem() {
        for (Instance entry : capabilities) {
            Identifier item = entry.capability.magneticItem(this);
            if (item != null) return item;
        }
        return null;
    }

    public boolean removeMagneticItem(LevelAccess level) {
        for (Instance entry : capabilities) {
            if (entry.capability.removeMagneticItem(this, level)) return true;
        }
        return false;
    }

    /** Travel and bite direction, including charm and a surfaced miner's return journey. */
    public float walkDirection() {
        if (isCharmed()) return 1F;
        for (Instance entry : capabilities) {
            float direction = entry.capability.walkDirection(this);
            if (!Float.isNaN(direction)) return direction;
        }
        return -1F;
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
            setAnimation(eatState());
            if (biteCooldown > 0) {
                biteCooldown--;
                return;
            }
            int damage = Math.round(def.biteDamage()
                    * level.rules().getFloat(PvzceIds.RULE_ZOMBIE_DAMAGE_MULTIPLIER));
            target.damage(damage, ZombieEntity.damageType(PvzceIds.DAMAGE_IMPACT), level);
            biteCooldown = biteIntervalTicks();
            level.emitEffect(PvzceParticles.CHOMP.toString(), target.cellX(), target.cellY(),
                    def.sounds().bite().orElse(PvzceSounds.EFFECT_BITE));
            return;
        }
        setAnimation(walkState());
        setCellX(cellX() + walkDirection() * moveSpeed(level) / PvzceConstants.TICKS_PER_SECOND);
        if (biteCooldown > 0) {
            biteCooldown--;
        }
        checkReachedEdge(level);
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
        setAnimation(eatState());
        if (biteCooldown > 0) {
            biteCooldown--;
            return;
        }
        biteCooldown = biteIntervalTicks();
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
        // The bite is the sound and the plant losing health, and nothing else. It used to fire
        // `pvzce:chomp` - the puff-shroom's eight big spore puffs - at the plant, which put a
        // purple cloud on the lawn for every bite any zombie ever took.
        level.emitEffect("", plant.cellX(), plant.cellY(),
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

    /**
     * How long this zombie waits between bites, once its statuses have had their say.
     *
     * <p>A chilled zombie bites at half rate as well as walking at half speed - the original's
     * "cold" is one status that slows both, and a snow pea that only made zombies <em>walk</em>
     * slower would leave its jaws on their own clock. Expressed as a longer interval rather
     * than as a second timer: the countdown in {@code bitePlant} is the only bite clock there
     * is, and it is already what "how often" means here.
     */
    private int biteIntervalTicks() {
        int interval = def.biteIntervalTicks();
        for (StatusInstance status : statuses) {
            if (status.status == ZombieStatus.SLOW) {
                interval = Math.round(interval / Math.max(0.05F, status.magnitude));
            }
        }
        return Math.max(1, interval);
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
        return speed * level.rules().getFloat(PvzceIds.RULE_ZOMBIE_SPEED_MULTIPLIER)
                * level.zombieSpeedMultiplier(this);
    }

    /** Leaving the board by the edge this zombie walks towards; see {@link #checkReachedEdge}. */
    public void checkReachedLeft(LevelAccess level) {
        checkReachedEdge(level);
    }

    /**
     * Leaving the board, at whichever edge this zombie is walking towards.
     *
     * <p>A zombie of the horde that reaches the house (the left edge) loses the level for the
     * plants. A charmed one is walking the other way and simply leaves by the other edge: it has
     * done its job, and reporting it would end a level as a loss on the strength of a zombie the
     * player already turned.
     */
    private void checkReachedEdge(LevelAccess level) {
        if (walkDirection() > 0) {
            if (cellX() >= level.width() + 0.4F) {
                remove();
            }
            return;
        }
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
        damage(projectile, amount, level, null);
    }

    /**
     * The same hit, with the damage type overridden.
     *
     * <p>For the one thing in the game that changes a shot already in the air: a pea that flew
     * through a torchwood is a burning hit rather than a plain one, and the projectile's own
     * definition still describes everything else about it (its layer, its pierce, its art). A
     * {@code null} override - every ordinary shot - reads the definition as before.
     */
    public void damage(ProjectileDef projectile, int amount, LevelAccess level,
                       com.pvzce.api.util.Identifier typeOverride) {
        if (!isAlive()) {
            return;
        }
        int dmg = scaled(amount, level);
        DamageTypeDef type = (typeOverride != null ? java.util.Optional.of(typeOverride)
                : projectile.damageType()).map(ZombieEntity::damageType).orElse(null);
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
            // The shot's own splash, not a generic spark. This used to fire `pvzce:starburst`
            // - twenty-five golden stars meant for the star fruit and the award screen - on
            // every single hit, which is why being shot at looked like being showered in sun.
            // What the player reads at the point of impact is the thing they fired breaking,
            // and a projectile that has not drawn a splat plays only its sound.
            level.emitEffect(projectile.impactParticle().map(Identifier::toString).orElse(""),
                    cellX(), cellY(), hitSound);
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
                if (instance.capability.onImpact(this, dmg, level, type)) {
                    return;
                }
            }
        }
        // The type travels into the body hit because the *death* has to know what killed it: a
        // blast from the ash line leaves a charred body and a pea does not, and the difference
        // is the hit's own declaration rather than a list of ids here. `dismembers` rides along
        // for the same reason - the mower is not the only thing that could throw a head.
        damageBody(dmg, level, burns(type), type != null && type.dismembers());
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
        var flight = capability(FlyCapability.class);
        if (flight != null) flight.pop(this, level);
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
     * The body hit itself, with what kind of damage it was.
     *
     * @param burns true when this hit is fire or ash, so a kill leaves a charred body; see
     *              {@link EntityAnimations#DEATH_BURNED}
     */
    public void damageBody(int amount, LevelAccess level, boolean burns) {
        damageBody(amount, level, burns, false);
    }

    /**
     * The body hit itself, with what kind of damage it was.
     *
     * @param burns      true when this hit is fire or ash, so a kill leaves a charred body; see
     *                   {@link EntityAnimations#DEATH_BURNED}
     * @param dismembered true when the hit threw the head and arm off itself (the lawn mower),
     *                   so the death must not throw a second pair
     */
    public void damageBody(int amount, LevelAccess level, boolean burns, boolean dismembered) {
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
        // A hit that burns a body to ash does not also tear an arm off it: the charred model
        // has no arms and no head to begin with, and the original's ash line leaves exactly
        // that drawing behind. This is why the arm and the head below both ask `burns`.
        if (!burns && !dismembered && def.dropsArm() && armorHealth() <= 0
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
            // through the definition's own `animations` map - a state the art does not define
            // falls back to `idle` on the client, so a definition that opts into fire deaths
            // has to map it at the charred file.
            String death = burns ? EntityAnimations.DEATH_BURNED : EntityAnimations.DEATH;
            if (!burns) {
                for (Instance instance : capabilities) {
                    String override = instance.capability.deathState(this);
                    if (override != null) { death = override; break; }
                }
            }
            setAnimation(death);
            // The head leaves the body as its own particle. The model keeps it hidden in the
            // death clip - that is how the original is authored - so this is the only thing
            // that puts one on the lawn, and the sprite's own motion is what makes it drop
            // where the zombie fell rather than fly off the board (see
            // data/pvzce/particles/zombie/zombie_head.json).
            // Exactly one head per body. The lawn mower throws its own (a mowed zombie loses
            // its head and arm *to the mower*, and `pvzce:mower` says so with `dismembers`),
            // and a burnt body is drawn without one - both used to get a second head here.
            // A body that blew itself up leaves no head behind: it is not there at all (see
            // `JackInTheBoxCapability.explode`, which removes it), and a head popping out of a
            // cloud the zombie was standing at the centre of reads as a second, unrelated death.
            if (!burns && !selfDestructed) {
                Identifier deathSound = def.sounds().death().orElse(PvzceSounds.ZOMBIE_LIMBS_POP);
                String head = dismembered
                        ? PvzceParticles.MOWERED_ZOMBIE_HEAD.toString()
                        : def.dropsHead() ? PvzceParticles.ZOMBIE_HEAD.toString() : "";
                level.emitEffect(head, cellX(), cellY(), deathSound);
                if (dismembered) {
                    // The mower takes the arm with it too, and the half-health pop above is
                    // suppressed for this hit - otherwise a mowed zombie shed two arms.
                    level.emitEffect(PvzceParticles.MOWERED_ZOMBIE_ARM.toString(),
                            cellX(), cellY(), null);
                }
            }
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

    /**
     * A running count of hits of one kind that have landed on this zombie, by the key the
     * caller chooses (the ice-boom shroom counts "cold" until the third one freezes it).
     *
     * <p>On the zombie rather than on the shot, and that is the whole reason this exists: a
     * projectile is destroyed by the hit that lands it, so a counter it owns can never reach
     * two - the plant that stacks cold would fire forever and never freeze anything. It is
     * also not a {@link ZombieStatus}: statuses are strengths and durations, while this is a
     * tally with no clock of its own (the chill status the same hits land is what the player
     * watches run out).
     *
     * <p>Lives and dies with the body, and is written to the save with it: a lawn reloaded
     * mid-stack carries the stack, which is what the player expects after the game told them
     * "one more bolt and that one is ice".
     */
    public int addBuildup(Identifier key, int by) {
        int next = Math.max(0, buildups.getOrDefault(key, 0) + by);
        if (next == 0) {
            buildups.remove(key);
        } else {
            buildups.put(key, next);
        }
        return next;
    }

    /** How many hits of this kind are stacked on the zombie right now. */
    public int buildup(Identifier key) {
        return buildups.getOrDefault(key, 0);
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

    /**
     * Held solid: the ice-shroom's freeze, and the one thing the client draws ice for.
     *
     * <p>The same status {@link #walkOrEat} reads to stop the zombie walking; published so the
     * client can stop the *animation* too, which the server cannot do from its side.
     */
    @Override
    public boolean frozen() {
        return isImmobilized();
    }

    public boolean isImmobilized() {
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

        CompoundTag buildupTag = new CompoundTag();
        for (Map.Entry<Identifier, Integer> entry : buildups.entrySet()) {
            buildupTag.putInt(entry.getKey().toString(), entry.getValue());
        }
        tag.put("buildups", buildupTag);
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

        buildups.clear();
        CompoundTag buildupTag = tag.getCompound("buildups");
        for (Map.Entry<String, com.pvzce.common.nbt.Tag> entry : buildupTag.entries().entrySet()) {
            Identifier id = Identifier.tryParse(entry.getKey());
            int count = buildupTag.getInt(entry.getKey());
            if (id != null && count > 0) {
                buildups.put(id, count);
            }
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
