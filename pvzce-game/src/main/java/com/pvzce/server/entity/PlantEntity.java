package com.pvzce.server.entity;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.content.capability.TypedCapability;
import com.pvzce.api.entity.EntityKind;
import com.pvzce.api.entity.EntityLayers;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.Team;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.List;

/**
 * Data-driven plant entity: a thin shell that owns position/health/age and
 * forwards every behaviour to its {@link PlantCapability} instances.
 *
 * <p>There is deliberately no {@code behavior} string here any more. Adding a
 * plant behaviour is a new capability type (or new data), never a new branch in
 * this class, and a plant can combine capabilities (a shooter that also produces
 * sun) without either one knowing about the other.
 */
public class PlantEntity extends PvzceEntity {
    private final PlantDef def;
    private final List<Instance> capabilities = new ArrayList<>();

    /**
     * This plant's capability instances, for the one caller that has to walk them all.
     *
     * <p>The level, when the plant stops existing: {@code PlantCapability.onRemoved} is what a
     * capability uses to withdraw something it registered when it was placed - the plantern takes
     * its lamp out of the fog - and the entity is the only thing that knows what it is carrying.
     */
    public List<Instance> capabilityInstances() {
        return List.copyOf(capabilities);
    }
    private int age;

    /**
     * Ticks left of the clip this plant plays while it is being consumed; zero when it is not.
     *
     * <p>A consumed plant is already out of the game ({@link #isRemoved()} is true and
     * {@link #occupiesCell(PlantCapability)} answers false); this is the drawing catching up.
     * It is a server counter rather than "let the client finish the clip", for the same reason
     * every other timed state is: the server does not know how long a clip is and must not
     * pretend to.
     */
    private int vanishTicks;

    /**
     * Ticks left of "this plant has just been watered"; zero when it is dry.
     *
     * <p>Not a hidden buff: it is the answer to "does this plant work faster right now", and it
     * is what the watering can buys besides the health. Kept here rather than in each capability
     * because every capability that counts something down wants the same accelerated clock, and
     * a per-capability copy would be several answers to one question.
     */
    private int wateredTicks;

    /**
     * How fast this plant works, as a multiplier on the rate.
     *
     * <p>Pushed in by the level at planting time (and again when a mutation rewrites the rule)
     * rather than asked per step, because the rate is read by capabilities that have no level
     * handle - the shooter and the producer spend it, and neither was written to ask.
     */
    private float actionSpeedMultiplier = 1F;

    /**
     * What being watered is worth, as a rate rather than as "a tick every third one".
     *
     * <p>Three halves rather than four thirds. A third faster was invisible in play - the player
     * watered a plant, watched it, and could not tell whether anything had happened, which is the
     * report this answers. The watering can's whole reason to exist is that it makes one plant
     * visibly better for fifteen seconds, so the number has to be one a player can see: half again
     * as many shots is a difference you notice inside a single volley.
     */
    private static final float WATERED_ACTION_SPEED = 3F / 2F;
    /**
     * The clock {@link #actionStep()} spends, for capabilities that keep no clock of their own.
     *
     * <p>Sharing one is only safe for capabilities that are the plant's <em>only</em> counting
     * clock; the shooter and the producer each hold their own, because a plant may have both.
     */
    private final com.pvzce.common.level.RateClock sharedClock =
            new com.pvzce.common.level.RateClock();


    public PlantEntity(PlantDef def, Team team, int gridX, int gridY) {
        this(def, team, gridX, gridY, def.health());
    }

    /**
     * A plant whose full health is not the one its definition prints.
     *
     * <p>The level's {@code plant_health_multiplier} - a mutation that makes plants fragile - is
     * read once, when the plant is planted, and the result is kept here rather than recomputed:
     * "full" is what the watering can heals to and what a half-eaten plant is half <em>of</em>.
     * Recomputing it from the rule later would mean a mutation arriving mid-run silently healing
     * every wounded plant on the lawn, and reading the definition instead would let a watering can
     * heal a fragile plant past its own maximum.
     *
     * <p>It travels in the save with the rest of the entity's state, so a plant that survives a
     * resume is still exactly as fragile as it was planted.
     */
    public PlantEntity(PlantDef def, Team team, int gridX, int gridY, int fullHealth) {
        super(def.id(), team, gridX + 0.5F, gridY + 0.5F, Math.max(1, fullHealth));
        this.def = def;
        for (TypedCapability<PlantCapability> entry : def.resolvedCapabilities()) {
            capabilities.add(new Instance(entry.type(), entry.value().instantiate()));
        }
    }

    public PlantDef def() {
        return def;
    }

    /** How much health this plant had when it was planted; what {@link #water} heals it to. */
    public int fullHealth() {
        return maxHealth();
    }

    public int age() {
        return age;
    }

    /** True while this plant is working on the faster clock a watering gives it. */
    public boolean watered() {
        return wateredTicks > 0;
    }

    /** How much longer the watering lasts, in ticks. */
    public int wateredTicks() {
        return wateredTicks;
    }

    /**
     * Waters this plant: full health, {@code ticks} of the faster clock, and every capability
     * gets asked whether it has something of its own to do with the water.
     *
     * <p>The fan-out lives here rather than at the call site for the same reason {@link #wake}
     * does: "ask every capability" is the plant's own shape, and a tool that reached into the
     * capability list would be a second implementation of it. The hook is what makes the
     * sun-shroom ripen on the spot; a capability that ignores it is untouched.
     *
     * <p>To full, not by a number: watering a half-eaten plant is how the original's garden keeps
     * it alive, and "how much did that restore" is one of the two things the caller reports.
     */
    public Watering water(int ticks, LevelAccess level) {
        int before = health();
        setHealth(maxHealth());
        wateredTicks = Math.max(wateredTicks, Math.max(1, ticks));
        boolean used = false;
        for (Instance instance : capabilities) {
            used |= instance.capability.water(this, level);
        }
        return new Watering(health() - before, used);
    }

    /**
     * What one watering did: how much health it restored, and whether a capability used it.
     *
     * <p>Both halves are feedback the player can see - a plant that was hurt looks repaired, a
     * producer that ripened plays its growth performance - and the caller needs them apart to
     * decide which of the two to show.
     */
    public record Watering(int healed, boolean ripened) {
    }

    /**
     * One tick of progress on whatever this plant is counting down, at the level's default rate.
     *
     * <p>What a shooter spends. A capability whose subject has a rate of its own on top of the
     * plant's - a producer, whose sun arrives at {@code sun_rate_multiplier} as well - passes
     * that rate to {@link #actionStep(float)} instead.
     */
    public float actionRate() {
        return actionRate(1F);
    }

    /**
     * How fast this plant works right now, as steps per tick.
     *
     * <p>The level's {@code plant_action_speed_multiplier}, the watering tool's "a quarter faster"
     * and the caller's own rate folded into one number, which each capability then feeds to its own
     * {@link com.pvzce.common.level.RateClock}: 1.0 is one step per tick, 2.0 is two, 0.5 is one
     * every other tick.
     *
     * <p>Kept here rather than written out in the shooter and the producer so the two can never
     * disagree about what either the water or a mutation does - and <em>only</em> here, because
     * the counting down itself has to be per capability (see {@code RateClock}).
     *
     * @param extraRate a further multiplier for this particular clock, e.g. the sun rate a
     *                  producer's drop arrives at
     */
    public float actionRate(float extraRate) {
        return actionSpeedMultiplier * Math.max(0.1F, extraRate)
                * (wateredTicks > 0 ? WATERED_ACTION_SPEED : 1F);
    }

    /**
     * One tick of progress, for a capability that has no clock of its own.
     *
     * <p>Kept as a convenience for the callers that were written against it (the melee plant, the
     * thrower) and as this plant's shared clock. A capability that needs its own phase - anything
     * counting a cooldown down next to another one on the same plant - holds a
     * {@link com.pvzce.common.level.RateClock} and calls {@link #actionRate} instead.
     */
    public int actionStep() {
        return actionStep(1F);
    }

    /** One tick of progress at a further multiplier, through this plant's shared clock. */
    public int actionStep(float extraRate) {
        int gain = sharedClock.step(actionRate(extraRate));
        return gain;
    }

    /**
     * Sets how fast this plant works.
     *
     * <p>Called by the level when the plant is placed and again whenever the rule changes, so a
     * mutation that makes every plant faster reaches the ones already standing on the lawn - a
     * rule read only at planting time would make the mutation apply to whatever the player
     * planted after it, which is not what "the plants now work faster" means.
     */
    public void setActionSpeedMultiplier(float multiplier) {
        this.actionSpeedMultiplier = Math.max(0.1F, multiplier);
    }

    /**
     * Consumes the plant, or starts its vanish clip.
     *
     * <p>The one door every "this plant is spent" path goes through (a placement that consumes
     * it, a shovel, a zombie eating it to zero health), so a content author declares the clip
     * once on a capability and every way of losing the plant shows it. A plant with no clip
     * keeps the instant removal it has always had.
     */
    @Override
    public void remove() {
        if (removed) {
            return;
        }
        removed = true;
        java.util.Optional<PlantCapability.VanishAnimation> vanish = vanishAnimation();
        if (vanish.isPresent()) {
            // Out of the game already (`removed`), still on the board for the length of its
            // clip. See `vanishing()` for the one question the two states differ on.
            vanishTicks = vanish.get().ticks();
            setAnimation(vanish.get().clip());
        }
    }

    /** The vanish animation this plant's capabilities ask for, if any. */
    private java.util.Optional<PlantCapability.VanishAnimation> vanishAnimation() {
        for (Instance instance : capabilities) {
            java.util.Optional<PlantCapability.VanishAnimation> vanish = instance.capability.vanishAnimation();
            if (vanish.isPresent()) {
                return vanish;
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Advances a vanish clip, and reports that it is over.
     *
     * <p>Runs while the plant is {@code removed}: the removal flag is what "out of the game"
     * means everywhere else, and this counter is the drawing catching up with it.
     *
     * @return true when the clip has finished and the plant may leave the entity list
     */
    private boolean tickVanish() {
        if (vanishTicks <= 0) {
            return true;
        }
        if (--vanishTicks > 0) {
            return false;
        }
        vanishTicks = 0;
        return true;
    }

    /**
     * True while this plant is out of the game but still being drawn.
     *
     * <p>{@link #isRemoved()} is already true by then; this is the narrower question the
     * level's removal pass and the entity sync ask, because a vanishing plant still has to
     * reach the client and still has to be taken off the board once its clip ends.
     */
    public boolean vanishing() {
        return removed && vanishTicks > 0;
    }

    @Override
    public String entityKind() {
        return EntityKind.PLANT;
    }

    @Override
    public int layer() {
        return EntityLayers.PLANT;
    }

    @Override
    public void tick(LevelServer level) {
        if (removed) {
            tickVanish();
            return;
        }
        age++;
        if (wateredTicks > 0) {
            wateredTicks--;
        }
        // A sleeping plant is not acting: only the capability that puts it to sleep keeps
        // ticking (it is the one publishing the animation). Asking here rather than in each
        // active capability is what makes "asleep" one fact instead of a check every new
        // behaviour has to remember.
        boolean asleep = isAsleep(level);
        for (Instance instance : capabilities) {
            if (asleep && !instance.capability.ticksWhileAsleep(this)) {
                continue;
            }
            instance.capability.tick(this, level);
            if (removed) {
                break;
            }
        }
        if (asleep && !removed) {
            // The sleeping pose wins, whatever else ticked this tick. A capability that keeps
            // working while its plant sleeps (a Sun-shroom's producer) publishes its own state as
            // it goes - `produce`, then `idle_big` - while this plant's sleep is derived rather
            // than published, so without this the mushroom would be drawn awake on every tick it
            // happened to produce on. Publishing here rather than suppressing the producer is
            // deliberate: the sun still has to arrive.
            setState(com.pvzce.api.entity.EntityAnimations.SLEEP);
        }
    }

    /** True while a capability says this plant is asleep (a nocturnal mushroom in daylight). */
    public boolean isAsleep(LevelAccess level) {
        for (Instance instance : capabilities) {
            if (instance.capability.asleep(this, level)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Takes a hit from a zombie's bite or a Gargantuar's fist.
     *
     * <p>A plant whose capabilities declare it invulnerable loses nothing. This is the
     * one door every "something hits the plant" path goes through, so the ash line's
     * "chew on it all you like, it still goes off" holds for the giant as much as for an
     * ordinary zombie - the alternative was a list of plant ids at each call site.
     *
     * <p>Not called {@code damage}: {@link PvzceEntity} already has
     * {@code damage(int)}, and a second overload would change what a one-argument call
     * resolves to depending on imports.
     *
     * @return {@code true} when the hit landed, {@code false} when the plant shrugged it
     *         off - a caller that swings on a timer (the Gargantuar's hammer) has to be
     *         able to tell, or it spends its blow on a bomb and counts it as a smash
     */
    public boolean damageFrom(int amount) {
        if (isInvulnerable()) {
            return false;
        }
        damage(amount);
        return true;
    }

    /**
     * Runs this plant's capabilities for one zombie bite, before any damage lands.
     *
     * <p>Asked in declaration order and stopped at the first {@code true}: a plant that reacts
     * to being bitten has taken the bite, and two capabilities disagreeing about what a bite
     * means is not a case worth inventing rules for. The zombie is passed in because the hook's
     * whole point is <em>who</em> bit it.
     *
     * @return true when the bite was consumed (the biter was charmed) rather than being damage
     */
    public boolean onBittenBy(ZombieEntity zombie, LevelServer level) {
        if (isRemoved()) {
            return false;
        }
        for (Instance instance : capabilities) {
            if (instance.capability.onBittenBy(this, zombie, level)) {
                return true;
            }
        }
        return false;
    }

    /** True while a capability says nothing may hurt this plant (an armed bomb). */
    public boolean isInvulnerable() {
        for (Instance instance : capabilities) {
            if (instance.capability.invulnerable(this)) {
                return true;
            }
        }
        return false;
    }

    /** Called by the level right after the plant is committed to the field. */
    public void onPlaced(LevelServer level) {
        for (Instance instance : capabilities) {
            instance.capability.onPlaced(this, level);
            if (removed) {
                break;
            }
        }
    }

    /** True when placing this plant consumes it immediately (coffee bean). */
    public boolean consumesOnPlace() {
        return capabilities.stream().anyMatch(instance -> instance.capability.consumesOnPlace());
    }

    /**
     * Publishes a plant animation state, in the art variant this plant is in.
     *
     * <p>Every capability that names a state goes through here rather than calling
     * {@code setAnimation} directly: a grown sun-shroom's {@code idle} is {@code idle_big},
     * and the capability that sleeps has no business knowing that growth exists. The suffix
     * comes from {@link PlantCapability#variantSuffix}, so "which art is this plant wearing"
     * is one answer derived from the capabilities that decide it - not a field that the
     * growing capability and every publisher would have to keep in step.
     */
    public void setState(String state) {
        setAnimation(state + variantSuffix());
    }

    /** The clip-name suffix of the art variant this plant is currently in. */
    public String variantSuffix() {
        for (Instance instance : capabilities) {
            String suffix = instance.capability.variantSuffix(this);
            if (suffix != null && !suffix.isEmpty()) {
                return "_" + suffix;
            }
        }
        return "";
    }

    /**
     * True when the plant is still part of its cell, i.e. a zombie may eat it and a tool
     * may remove it. A capability that moves the plant out of its cell (a bowling nut)
     * answers false; every capability has to agree, so combining a stationary behaviour
     * with a moving one cannot silently produce a plant that is half on the board.
     */
    public boolean occupiesCell() {
        if (vanishing()) {
            // A plant that has been consumed is not in its cell any more, whatever its
            // capabilities would say: the clip is the drawing, not a second life. This is the
            // same door the rolling bowling Wall-nut and a detonated bomb use.
            return false;
        }
        for (Instance instance : capabilities) {
            if (!instance.capability.occupiesCell(this)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Wakes this plant up (a coffee bean); a no-op for a plant that does not sleep.
     *
     * <p>Replaces the old {@code boost()} fan-out: the coffee bean is the only thing that
     * ever called it, and the original's coffee bean wakes a sleeping mushroom and does
     * nothing else. The generic "instant activation" an energy bean will want is a
     * different mechanic (see {@code 02-第二阶段} §2.2.5) and gets its own hook.
     *
     * @return {@code true} when something was actually asleep and is now awake, so the
     *         caller can tell a used coffee bean from a wasted one
     */
    public boolean wake() {
        boolean woke = false;
        for (Instance instance : capabilities) {
            woke |= instance.capability.wake(this);
        }
        return woke;
    }

    /**
     * Remaining arm-up ticks of a timed/proximity charge, or zero when the plant
     * has no explosive capability. Convenience for tests and the HUD.
     */
    public int armTicksLeft() {
        com.pvzce.common.capability.plant.ExplosiveCapability explosive =
                capability(com.pvzce.common.capability.plant.ExplosiveCapability.class);
        return explosive == null ? 0 : explosive.fuseLeft();
    }

    /** The live capability instance of the given type, or {@code null}. */
    public <T extends PlantCapability> T capability(Class<T> type) {
        for (Instance instance : capabilities) {
            if (type.isInstance(instance.capability)) {
                return type.cast(instance.capability);
            }
        }
        return null;
    }

    @Override
    public CompoundTag saveState() {
        CompoundTag tag = saveBaseState();
        tag.putInt("age", age);
        tag.putInt("watered", wateredTicks);
        CompoundTag saved = new CompoundTag();
        for (Instance instance : capabilities) {
            CompoundTag capabilityTag = new CompoundTag();
            instance.capability.save(capabilityTag);
            saved.put(instance.type.toString(), capabilityTag);
        }
        tag.put("capabilities", saved);
        return tag;
    }

    /**
     * Puts back everything about this plant except where it is standing.
     *
     * <p>Used by the glove, which re-spawns the plant where the player dropped it and then
     * restores its state. A moved potato mine must not re-arm, a moved lily pad must still
     * carry, and a damaged plant must stay damaged - but none of that includes its old
     * cell or its old height in the stack.
     */
    public void restoreStateWithoutPosition(CompoundTag tag) {
        super.restoreStateWithoutPosition(tag);
        age = tag.getInt("age");
        // A save written before watering existed has no key, and getInt answers 0 - the plant
        // reads back dry, which is what it was.
        wateredTicks = Math.max(0, tag.getInt("watered"));
        restoreCapabilities(tag);
    }

    private void restoreCapabilities(CompoundTag tag) {
        CompoundTag saved = tag.getCompound("capabilities");
        for (Instance instance : capabilities) {
            CompoundTag capabilityTag = saved.getCompound(instance.type.toString());
            instance.capability.load(capabilityTag);
        }
    }

    @Override
    public void restoreState(CompoundTag tag) {
        restoreBaseState(tag);
        age = tag.getInt("age");
        wateredTicks = Math.max(0, tag.getInt("watered"));
        restoreCapabilities(tag);
    }

    /** Convenience for callers that only know the definition id (HUD, tests). */
    public Identifier definitionId() {
        return def.id();
    }

    public record Instance(Identifier type, PlantCapability capability) {
    }
}
