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
    private int age;

    public PlantEntity(PlantDef def, Team team, int gridX, int gridY) {
        super(def.id(), team, gridX + 0.5F, gridY + 0.5F, def.health());
        this.def = def;
        for (TypedCapability<PlantCapability> entry : def.resolvedCapabilities()) {
            capabilities.add(new Instance(entry.type(), entry.value().instantiate()));
        }
    }

    public PlantDef def() {
        return def;
    }

    public int age() {
        return age;
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
            return;
        }
        age++;
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
        restoreCapabilities(tag);
    }

    /** Convenience for callers that only know the definition id (HUD, tests). */
    public Identifier definitionId() {
        return def.id();
    }

    private record Instance(Identifier type, PlantCapability capability) {
    }
}
